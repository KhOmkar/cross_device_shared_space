package com.anonymous.fileshare.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anonymous.fileshare.service.FileShareForegroundService
import com.anonymous.fileshare.service.HotspotManager
import com.anonymous.fileshare.transfer.FileMetadata
import com.anonymous.fileshare.transfer.TransferDirection
import com.anonymous.fileshare.transfer.TransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class TransferItemUiState(
    val transferId: String,
    val filename: String,
    val totalBytes: Long,
    val bytesTransferred: Long = 0L,
    val progressPct: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val isUpload: Boolean = true,
    val status: TransferStatus = TransferStatus.TRANSFERRING,
    val errorMessage: String? = null
)

enum class TransferStatus {
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AppUiState(
    val isServerRunning: Boolean = false,
    val isGuestConnected: Boolean = false,
    val connectedPeerAlias: String? = null,
    val saveFolderDisplayName: String = "Downloads/AnonymousShare",
    val pairingCode: String = "",
    val sessionToken: String = "",
    val serverUrl: String = "",
    val shortUrl: String = "http://share.local:8080",
    val ipUrl: String = "",
    val activeTransfers: List<TransferItemUiState> = emptyList(),
    val statusMessage: String = "Tap 'Start Server' to begin"
)

class TransferStateViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    val logs: StateFlow<List<String>> = com.anonymous.fileshare.util.AppLogger.logs

    private val hotspotManager = HotspotManager(application)
    private var boundService: FileShareForegroundService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FileShareForegroundService.LocalBinder
            boundService = binder.getService()
            isBound = true

            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            isBound = false
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val intent = Intent(getApplication(), FileShareForegroundService::class.java)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        val service = boundService ?: return

        service.transferChannel?.onIncomingTransferEvent = { txId, filename, totalSize, bytesReceived, isCompleted, error ->
            val pct = if (totalSize > 0) ((bytesReceived.toDouble() / totalSize) * 100).toInt() else 0
            val status = when {
                error != null && error.contains("cancel", ignoreCase = true) -> TransferStatus.CANCELLED
                error != null -> TransferStatus.FAILED
                isCompleted -> TransferStatus.COMPLETED
                else -> TransferStatus.TRANSFERRING
            }

            _uiState.update { state ->
                val existing = state.activeTransfers.find { it.transferId == txId }
                val updatedList = if (existing == null) {
                    listOf(
                        TransferItemUiState(
                            transferId = txId,
                            filename = filename,
                            totalBytes = totalSize,
                            bytesTransferred = bytesReceived,
                            progressPct = pct,
                            isUpload = false,
                            status = status,
                            errorMessage = error
                        )
                    ) + state.activeTransfers
                } else {
                    state.activeTransfers.map {
                        if (it.transferId == txId) {
                            it.copy(
                                bytesTransferred = bytesReceived,
                                progressPct = pct,
                                status = status,
                                errorMessage = error
                            )
                        } else it
                    }
                }
                state.copy(activeTransfers = updatedList)
            }
        }

        viewModelScope.launch {
            service.serviceState.collect { status ->
                val isRunning = status != FileShareForegroundService.ServiceStatus.STOPPED
                val isPaired = status == FileShareForegroundService.ServiceStatus.PAIRED ||
                        status == FileShareForegroundService.ServiceStatus.TRANSFERRING

                val ip = hotspotManager.getLocalIpAddress() ?: "192.168.43.1"
                val code = service.sessionManager.pairingCode
                val token = service.sessionManager.sessionToken
                val shortUrl = "http://share.local:8080"
                val shortUrlWithAuth = "http://share.local:8080/?token=$token&code=$code"
                val ipUrl = "http://$ip:8080"

                _uiState.update {
                    it.copy(
                        isServerRunning = isRunning,
                        isGuestConnected = isPaired,
                        pairingCode = code,
                        sessionToken = token,
                        serverUrl = shortUrlWithAuth,
                        shortUrl = shortUrl,
                        ipUrl = ipUrl,
                        saveFolderDisplayName = service.storageManager.saveFolderDisplayName,
                        statusMessage = if (isPaired) "Guest Connected & Encrypted" else if (isRunning) "Ready for Guest Connection" else "Server Stopped"
                    )
                }
            }
        }

        // Periodically refresh IP address when hotspot is enabled/changed while server is running
        viewModelScope.launch {
            while (true) {
                delay(2000)
                val state = _uiState.value
                val s = boundService
                if (state.isServerRunning && !state.isGuestConnected && s != null) {
                    val currentIp = hotspotManager.getLocalIpAddress() ?: "192.168.43.1"
                    val expectedIpUrl = "http://$currentIp:8080"

                    if (state.ipUrl != expectedIpUrl) {
                        com.anonymous.fileshare.util.AppLogger.i("ViewModel", "Network IP updated: $currentIp")
                        _uiState.update {
                            it.copy(ipUrl = expectedIpUrl)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            service.connectedPeerAlias.collect { alias ->
                _uiState.update { it.copy(connectedPeerAlias = alias) }
            }
        }
    }

    fun setCustomSaveDirectory(uri: Uri?) {
        val service = boundService ?: return
        service.storageManager.customDestinationUri = uri
        _uiState.update { it.copy(saveFolderDisplayName = service.storageManager.saveFolderDisplayName) }
    }

    fun cancelTransfer(transferId: String) {
        val service = boundService ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                service.transferChannel?.cancelTransfer(transferId)
            } catch (e: Exception) {
                com.anonymous.fileshare.util.AppLogger.e("ViewModel", "Cancel error: ${e.message}")
            }
        }
        _uiState.update { state ->
            state.copy(
                activeTransfers = state.activeTransfers.map {
                    if (it.transferId == transferId) {
                        it.copy(status = TransferStatus.CANCELLED, errorMessage = "Cancelled by you")
                    } else it
                }
            )
        }
    }

    fun startServer() {
        _uiState.update { it.copy(activeTransfers = emptyList()) }
        val context = getApplication<Application>()
        val startIntent = Intent(context, FileShareForegroundService::class.java).apply {
            action = FileShareForegroundService.ACTION_START_SERVICE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            context.startService(startIntent)
        }
    }

    fun stopServer() {
        _uiState.update { it.copy(activeTransfers = emptyList()) }
        val context = getApplication<Application>()
        val stopIntent = Intent(context, FileShareForegroundService::class.java).apply {
            action = FileShareForegroundService.ACTION_STOP_SERVICE
        }
        context.startService(stopIntent)
    }

    fun clearTransfers() {
        _uiState.update { it.copy(activeTransfers = emptyList()) }
    }

    fun clearLogs() {
        com.anonymous.fileshare.util.AppLogger.clear()
    }

    fun openHotspotSettings() {
        hotspotManager.openTetheringSettings()
    }

    private val sendMutex = Mutex()

    fun sendFile(uri: Uri, filename: String, size: Long) {
        val service = boundService ?: return
        val channel = service.transferChannel ?: return

        val transferId = "tx_" + UUID.randomUUID().toString().take(8)
        val chunkSize = 1024 * 1024
        val actualSize = if (size > 0L) size else 1024L
        val totalChunks = ((actualSize + chunkSize - 1) / chunkSize).toInt()

        val meta = FileMetadata(
            transferId = transferId,
            filename = filename,
            size = actualSize,
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            direction = TransferDirection.PHONE_TO_GUEST
        )

        val newTransfer = TransferItemUiState(
            transferId = transferId,
            filename = filename,
            totalBytes = actualSize,
            isUpload = true
        )

        _uiState.update { it.copy(activeTransfers = listOf(newTransfer) + it.activeTransfers) }

        viewModelScope.launch(Dispatchers.IO) {
            sendMutex.withLock {
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                val inStream = getApplication<Application>().contentResolver.openInputStream(uri)
                if (inStream == null) {
                    updateTransferState(transferId, TransferStatus.FAILED, 0, 0, "Could not open file")
                    return@withLock
                }

                val result = channel.sendFile(meta, inStream) { sent, total ->
                    val now = System.currentTimeMillis()
                    val dt = (now - lastTime) / 1000.0
                    val speed = if (dt >= 0.5) ((sent - lastBytes) / dt).toLong() else 0L
                    if (dt >= 0.5) {
                        lastTime = now
                        lastBytes = sent
                    }

                    val pct = if (total > 0) ((sent.toDouble() / total) * 100).toInt() else 0
                    updateTransferProgress(transferId, sent, pct, speed)
                }

                when (result) {
                    is TransferResult.Success -> {
                        updateTransferState(transferId, TransferStatus.COMPLETED, actualSize, 100, null)
                    }
                    is TransferResult.Failure -> {
                        updateTransferState(transferId, TransferStatus.FAILED, 0, 0, result.error)
                    }
                }
            }
        }
    }

    private fun updateTransferProgress(id: String, sent: Long, pct: Int, speed: Long) {
        _uiState.update { state ->
            state.copy(
                activeTransfers = state.activeTransfers.map {
                    if (it.transferId == id) it.copy(
                        bytesTransferred = sent,
                        progressPct = pct,
                        speedBytesPerSec = if (speed > 0) speed else it.speedBytesPerSec
                    ) else it
                }
            )
        }
    }

    private fun updateTransferState(id: String, status: TransferStatus, sent: Long, pct: Int, error: String?) {
        _uiState.update { state ->
            state.copy(
                activeTransfers = state.activeTransfers.map {
                    if (it.transferId == id) it.copy(
                        status = status,
                        bytesTransferred = sent,
                        progressPct = pct,
                        errorMessage = error
                    ) else it
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}
