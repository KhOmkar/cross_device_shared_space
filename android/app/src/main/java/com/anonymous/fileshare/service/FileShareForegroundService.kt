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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.anonymous.fileshare.MainActivity
import com.anonymous.fileshare.server.EmbeddedHttpServer
import com.anonymous.fileshare.server.MdnsResponder
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Foreground Service running the Embedded HTTP & WebSocket server.
 * Keeps CPU active with WakeLock and displays an ongoing notification.
 */
class FileShareForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val _serviceState = MutableStateFlow(ServiceStatus.STOPPED)
    val serviceState: StateFlow<ServiceStatus> = _serviceState.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var sessionManager: SessionManager
        private set
    lateinit var storageManager: StorageManager
        private set
    private var httpServer: EmbeddedHttpServer? = null
    var transferChannel: WebSocketTransferChannel? = null
        private set

    private var mdnsResponder: MdnsResponder? = null
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private val hotspotManager by lazy { HotspotManager(applicationContext) }

    val connectedPeerAlias = MutableStateFlow<String?>(null)
    val discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val incomingConnectionRequest = MutableStateFlow<ConnectionRequest?>(null)

    private var activeDiscoveryWs: WebSocketHandler? = null
    private var pendingClientPub: String? = null
    private var pendingClientAlias: String? = null

    data class DiscoveredPeer(
        val peerId: String,
        val alias: String,
        val platform: String = "desktop",
        val icon: String = "desktop",
        val lastSeenMs: Long = System.currentTimeMillis()
    )

    data class ConnectionRequest(
        val peerId: String,
        val alias: String,
        val platform: String = "desktop",
        val clientPubHex: String? = null
    )

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

        // Start mDNS Responder & NSD Service Discovery
        mdnsResponder = MdnsResponder(
            context = applicationContext,
            scope = serviceScope,
            ipProvider = { hotspotManager.getLocalIpAddress() ?: "192.168.43.1" }
        ).apply {
            start()
        }
        registerNsdService(port)

        _serviceState.value = ServiceStatus.RUNNING
        updateNotification("Server running • Code: ${sessionManager.pairingCode}")
        AppLogger.i("Service", "HTTP/WS server listening on port $port (mDNS share.local active)")
    }

    private fun handleIncomingWebSocket(ws: WebSocketHandler, clientToken: String) {
        AppLogger.i("Service", "Incoming WebSocket connection from guest (token=$clientToken)")
        if (clientToken.isNotEmpty() && !sessionManager.validateToken(clientToken) && clientToken != "manual_pairing" && clientToken != "discovery") {
            AppLogger.w("Service", "Invalid session token rejected: $clientToken")
            ws.sendText("{\"type\":\"error\",\"message\":\"Invalid session token\"}")
            ws.close(1008, "Invalid session token")
            return
        }
        activeDiscoveryWs = ws

        serviceScope.launch(Dispatchers.IO) {
            try {
                while (serviceState.value != ServiceStatus.STOPPED) {
                    val frame = ws.readFrame()
                    if (frame !is WebSocketHandler.Frame.Text) break
                    val json = JSONObject(frame.message)
                    val type = json.optString("type")

                    when (type) {
                        "peer_announce" -> {
                            val peerId = json.optString("peer_id", "guest_" + (System.currentTimeMillis() % 10000))
                            val alias = json.optString("device_alias", "Connected Guest")
                            val platform = json.optString("platform", "desktop")
                            val icon = json.optString("icon", "desktop")

                            val peer = DiscoveredPeer(peerId, alias, platform, icon)
                            discoveredPeers.update { list ->
                                listOf(peer) + list.filter { it.peerId != peerId }
                            }
                            AppLogger.d("Service", "Discovered peer announced: $alias ($platform)")

                            // Respond with host announcement
                            ws.sendText("{\"type\":\"peer_announce\",\"peer_id\":\"host\",\"device_alias\":\"Android Host Phone\",\"platform\":\"phone\",\"icon\":\"phone\"}")
                        }
                        "conn_request" -> {
                            val peerId = json.optString("peer_id", "guest")
                            val alias = json.optString("device_alias", "Connected Guest")
                            val platform = json.optString("platform", "desktop")
                            val clientPubHex = json.optString("client_pub", "")
                            val code = json.optString("code", "")
                            AppLogger.i("Service", "Connection request from: $alias ($platform)")

                            if (code.isNotEmpty() && sessionManager.validatePairingCode(code)) {
                                completePakeHandshake(ws, clientPubHex, alias, code, isApproved = true)
                                break
                            } else {
                                pendingClientPub = clientPubHex
                                pendingClientAlias = alias
                                incomingConnectionRequest.value = ConnectionRequest(peerId, alias, platform, clientPubHex)
                            }
                        }
                        "pake_init" -> {
                            val clientPubHex = json.getString("client_pub")
                            val code = json.optString("code", "")
                            val clientAlias = json.optString("device_alias", "Connected Guest")
                            completePakeHandshake(ws, clientPubHex, clientAlias, code, isApproved = false)
                            break
                        }
                        "conn_rejected" -> {
                            AppLogger.i("Service", "Connection rejected by peer: ${json.optString("reason")}")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d("Service", "WebSocket handler loop ended: ${e.message}")
            }
        }
    }

    private suspend fun completePakeHandshake(
        ws: WebSocketHandler,
        clientPubHex: String,
        clientAlias: String,
        code: String,
        isApproved: Boolean
    ) {
        try {
            if (!isApproved && !sessionManager.validatePairingCode(code)) {
                AppLogger.w("Service", "Pairing code validation failed: received=$code")
                sessionManager.rateLimiter.recordFailure("guest")
                ws.sendText("{\"type\":\"error\",\"message\":\"Invalid pairing code\"}")
                ws.close(1008, "Invalid pairing code")
                return
            }

            // Initialize Server PAKE Handshake & Derive Master Key
            val serverPubHex = sessionManager.pakeExchange.initHandshake()
            val sessionKey = sessionManager.pakeExchange.completeKeyAgreement(clientPubHex)
            sessionManager.setDerivedKey(sessionKey)
            AppLogger.d("Service", "PAKE key agreement complete. Sending pake_resp.")

            // Send PAKE Resp with server public key and host alias
            ws.sendText("{\"type\":\"pake_resp\",\"server_pub\":\"$serverPubHex\",\"server_alias\":\"Android Host Phone\",\"code\":\"${sessionManager.pairingCode}\"}")

            // Await Client Auth Confirmation
            val authFrame = ws.readFrame()
            if (authFrame !is WebSocketHandler.Frame.Text) {
                AppLogger.w("Service", "Expected Client Auth text frame, got $authFrame")
                ws.close(1003, "Expected Client Auth text frame")
                return
            }

            val authJson = JSONObject(authFrame.message)
            val clientAuth = authJson.optString("auth")
            if (!sessionManager.pakeExchange.verifyClientAuth(clientAuth)) {
                AppLogger.w("Service", "Client auth verification failed")
                sessionManager.rateLimiter.recordFailure("guest")
                ws.sendText("{\"type\":\"error\",\"message\":\"Authentication verification failed\"}")
                ws.close(1008, "Auth mismatch")
                return
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

    fun acceptIncomingConnection() {
        val ws = activeDiscoveryWs ?: return
        val clientPub = pendingClientPub ?: return
        val alias = pendingClientAlias ?: "Connected Guest"
        incomingConnectionRequest.value = null
        serviceScope.launch(Dispatchers.IO) {
            completePakeHandshake(ws, clientPub, alias, sessionManager.pairingCode, isApproved = true)
        }
    }

    fun rejectIncomingConnection() {
        val ws = activeDiscoveryWs
        incomingConnectionRequest.value = null
        serviceScope.launch(Dispatchers.IO) {
            try {
                ws?.sendText("{\"type\":\"conn_rejected\",\"reason\":\"Declined by host\"}")
            } catch (_: Exception) {}
        }
    }

    fun invitePeer(peerId: String) {
        val ws = activeDiscoveryWs ?: return
        serviceScope.launch(Dispatchers.IO) {
            try {
                ws.sendText("{\"type\":\"conn_invite\",\"server_alias\":\"Android Host Phone\",\"code\":\"${sessionManager.pairingCode}\"}")
            } catch (_: Exception) {}
        }
    }

    fun stopServer() {
        AppLogger.i("Service", "Stopping server...")
        serviceScope.launch {
            transferChannel?.close()
        }
        httpServer?.stop()
        httpServer = null

        mdnsResponder?.stop()
        mdnsResponder = null

        try {
            nsdRegistrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            AppLogger.w("Service", "Error unregistering NSD service: ${e.message}")
        }
        nsdRegistrationListener = null
        nsdManager = null

        sessionManager.destroy()
        storageManager.clearAll()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        connectedPeerAlias.value = null
        discoveredPeers.value = emptyList()
        incomingConnectionRequest.value = null
        activeDiscoveryWs = null
        pendingClientPub = null
        pendingClientAlias = null
        _serviceState.value = ServiceStatus.STOPPED
        AppLogger.i("Service", "Server stopped")
    }

    private fun registerNsdService(port: Int) {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "share"
                serviceType = "_http._tcp."
                setPort(port)
            }
            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    AppLogger.i("Service", "NSD HTTP service registered: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    AppLogger.w("Service", "NSD registration failed: code $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    AppLogger.d("Service", "NSD service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    AppLogger.w("Service", "NSD unregistration failed: code $errorCode")
                }
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (e: Exception) {
            AppLogger.w("Service", "Could not register NSD service: ${e.message}")
        }
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
