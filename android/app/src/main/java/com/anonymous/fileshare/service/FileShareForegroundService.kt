package com.anonymous.fileshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.anonymous.fileshare.MainActivity
import com.anonymous.fileshare.server.EmbeddedHttpServer
import com.anonymous.fileshare.server.SessionManager
import com.anonymous.fileshare.server.WebSocketHandler
import com.anonymous.fileshare.transfer.StorageManager
import com.anonymous.fileshare.transfer.WebSocketTransferChannel
import com.anonymous.fileshare.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground Service running the Embedded HTTP & WebSocket server.
 * Keeps CPU active with WakeLock and displays an ongoing notification.
 */
class FileShareForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var sessionManager: SessionManager
        private set
    lateinit var storageManager: StorageManager
        private set
    private var httpServer: EmbeddedHttpServer? = null
    var transferChannel: WebSocketTransferChannel? = null
        private set

    private val _serviceState = MutableStateFlow(ServiceStatus.STOPPED)
    val serviceState: StateFlow<ServiceStatus> = _serviceState.asStateFlow()

    val connectedPeerAlias = MutableStateFlow<String?>(null)

    enum class ServiceStatus {
        STOPPED,
        RUNNING,
        PAIRED,
        TRANSFERRING
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FileShareForegroundService = this@FileShareForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnonymousFileShare:WakeLock").apply {
            setReferenceCounted(false)
        }

        sessionManager = SessionManager()
        storageManager = StorageManager(applicationContext)
        transferChannel = WebSocketTransferChannel(sessionManager, storageManager, serviceScope)
        AppLogger.i("Service", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                AppLogger.i("Service", "Received ACTION_STOP_SERVICE")
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SERVICE -> {
                AppLogger.i("Service", "Received ACTION_START_SERVICE")
                startServer()
            }
        }

        val notification = buildNotification("Server ready • Code: ${sessionManager.pairingCode}")
        startForeground(NOTIFICATION_ID, notification)
        wakeLock?.acquire(30 * 60 * 1000L) // 30 mins max

        return START_NOT_STICKY
    }

    fun startServer(port: Int = 8080) {
        if (httpServer != null) {
            AppLogger.w("Service", "startServer called but server is already running")
            return
        }

        sessionManager.renewSession()
        AppLogger.i("Service", "Starting server on port $port, pairingCode=${sessionManager.pairingCode}")

        httpServer = EmbeddedHttpServer(
            context = applicationContext,
            sessionManager = sessionManager,
            port = port
        ) { wsHandler, clientToken ->
            handleIncomingWebSocket(wsHandler, clientToken)
        }

        httpServer?.start()
        _serviceState.value = ServiceStatus.RUNNING
        updateNotification("Server running • Code: ${sessionManager.pairingCode}")
        AppLogger.i("Service", "HTTP/WS server listening on port $port")
    }

    private fun handleIncomingWebSocket(ws: WebSocketHandler, clientToken: String) {
        AppLogger.i("Service", "Incoming WebSocket connection from guest (token=$clientToken)")
        if (clientToken.isNotEmpty() && !sessionManager.validateToken(clientToken) && clientToken != "manual_pairing") {
            AppLogger.w("Service", "Invalid session token rejected: $clientToken")
            ws.sendText("{\"type\":\"error\",\"message\":\"Invalid session token\"}")
            ws.close(1008, "Invalid session token")
            return
        }
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 1. Await PAKE Init frame
                val initFrame = ws.readFrame()
                if (initFrame !is WebSocketHandler.Frame.Text) {
                    AppLogger.w("Service", "Expected PAKE Init text frame, got $initFrame")
                    ws.close(1003, "Expected PAKE Init text frame")
                    return@launch
                }

                val initJson = JSONObject(initFrame.message)
                if (initJson.optString("type") != "pake_init") {
                    AppLogger.w("Service", "Invalid handshake frame type: ${initJson.optString("type")}")
                    ws.close(1003, "Invalid handshake type")
                    return@launch
                }

                val clientPubHex = initJson.getString("client_pub")
                val code = initJson.optString("code", "")
                val clientAlias = initJson.optString("device_alias", "Connected Guest")
                AppLogger.d("Service", "PAKE Init received: code=$code, alias=$clientAlias")

                // Validate Pairing Code
                if (!sessionManager.validatePairingCode(code)) {
                    AppLogger.w("Service", "Pairing code validation failed: received=$code, expected=${sessionManager.pairingCode}")
                    sessionManager.rateLimiter.recordFailure("guest")
                    ws.sendText("{\"type\":\"error\",\"message\":\"Invalid pairing code\"}")
                    ws.close(1008, "Invalid pairing code")
                    return@launch
                }

                // Initialize Server PAKE Handshake & Derive Master Key
                val serverPubHex = sessionManager.pakeExchange.initHandshake()
                val sessionKey = sessionManager.pakeExchange.completeKeyAgreement(clientPubHex)
                sessionManager.setDerivedKey(sessionKey)
                AppLogger.d("Service", "PAKE key agreement complete. Sending pake_resp.")

                // Send PAKE Resp with server public key and host alias
                ws.sendText("{\"type\":\"pake_resp\",\"server_pub\":\"$serverPubHex\",\"server_alias\":\"Android Device • Host\"}")

                // 2. Await Client Auth Confirmation
                val authFrame = ws.readFrame()
                if (authFrame !is WebSocketHandler.Frame.Text) {
                    AppLogger.w("Service", "Expected Client Auth text frame, got $authFrame")
                    ws.close(1003, "Expected Client Auth text frame")
                    return@launch
                }

                val authJson = JSONObject(authFrame.message)
                val clientAuth = authJson.optString("auth")
                if (!sessionManager.pakeExchange.verifyClientAuth(clientAuth)) {
                    AppLogger.w("Service", "Client auth verification failed")
                    sessionManager.rateLimiter.recordFailure("guest")
                    ws.sendText("{\"type\":\"error\",\"message\":\"Authentication verification failed\"}")
                    ws.close(1008, "Auth mismatch")
                    return@launch
                }

                // Send PAKE Confirmation
                val serverAuth = sessionManager.pakeExchange.computeServerAuth()
                ws.sendText("{\"type\":\"pake_confirmed\",\"server_auth\":\"$serverAuth\"}")
                AppLogger.i("Service", "PAKE handshake fully confirmed & encrypted!")

                sessionManager.rateLimiter.recordSuccess("guest")
                connectedPeerAlias.value = clientAlias
                _serviceState.value = ServiceStatus.PAIRED
                updateNotification("Guest paired ($clientAlias) • Session active")

                // Hand over WebSocket to Transfer Channel
                transferChannel?.attachWebSocket(ws, sessionKey)
            } catch (e: Exception) {
                AppLogger.e("Service", "Handshake exception: ${e.message}", e)
                ws.close(1011, "Handshake error: ${e.message}")
            }
        }
    }

    fun stopServer() {
        AppLogger.i("Service", "Stopping server...")
        serviceScope.launch {
            transferChannel?.close()
        }
        httpServer?.stop()
        httpServer = null
        sessionManager.destroy()
        storageManager.clearAll()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        connectedPeerAlias.value = null
        _serviceState.value = ServiceStatus.STOPPED
        AppLogger.i("Service", "Server stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Share Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active anonymous file sharing status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, FileShareForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pendingStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Anonymous File Share")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Session", pendingStop)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "anonymous_fileshare_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVICE = "com.anonymous.fileshare.action.START"
        const val ACTION_STOP_SERVICE = "com.anonymous.fileshare.action.STOP"
    }
}
