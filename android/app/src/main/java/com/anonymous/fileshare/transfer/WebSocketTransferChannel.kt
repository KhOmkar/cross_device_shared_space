package com.anonymous.fileshare.transfer

import com.anonymous.fileshare.crypto.CryptoEngine
import com.anonymous.fileshare.server.SessionManager
import com.anonymous.fileshare.server.WebSocketHandler
import java.io.InputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Concrete implementation of TransferChannel over local WebSocket connections.
 */
class WebSocketTransferChannel(
    private val sessionManager: SessionManager,
    private val storageManager: StorageManager,
    private val scope: CoroutineScope
) : TransferChannel {

    private var activeWsHandler: WebSocketHandler? = null
    private var activeSessionKey: SecretKey? = null
    private var receiveHandler: (suspend (FileMetadata, Flow<ByteArray>) -> Unit)? = null
    private var messageLoopJob: Job? = null

    var onIncomingTransferEvent: ((transferId: String, filename: String, totalSize: Long, bytesReceived: Long, isCompleted: Boolean, error: String?) -> Unit)? = null

    fun attachWebSocket(handler: WebSocketHandler, sessionKey: SecretKey) {
        this.activeWsHandler = handler
        this.activeSessionKey = sessionKey

        startIncomingMessageLoop(handler, sessionKey)
    }

    override suspend fun connect(endpoint: String, token: String) {
        // In V1 Local Hotspot server, incoming WebSocket connection is accepted by EmbeddedHttpServer
        // and attached via attachWebSocket()
    }

    override suspend fun sendFile(
        metadata: FileMetadata,
        dataStream: InputStream,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): TransferResult {
        val ws = activeWsHandler ?: return TransferResult.Failure(metadata.transferId, "Channel not connected")
        val key = activeSessionKey ?: return TransferResult.Failure(metadata.transferId, "Session key not established")

        return ChunkStreamer.streamFile(metadata, dataStream, key, ws, onProgress)
    }

    override fun setReceiveHandler(
        handler: suspend (metadata: FileMetadata, chunkFlow: Flow<ByteArray>) -> Unit
    ) {
        this.receiveHandler = handler
    }

    private fun startIncomingMessageLoop(ws: WebSocketHandler, key: SecretKey) {
        messageLoopJob?.cancel()
        messageLoopJob = scope.launch(Dispatchers.IO) {
            var currentIncomingTransferId: String? = null
            var currentFilename = ""
            var currentTotalSize = 0L
            var currentReceivedBytes = 0L

            try {
                while (isActive && !ws.isClosed) {
                    val frame = ws.readFrame() ?: break
                    sessionManager.touch()

                    when (frame) {
                        is WebSocketHandler.Frame.Text -> {
                            val json = JSONObject(frame.message)
                            when (json.optString("type")) {
                                "meta" -> {
                                    val txId = json.getString("transferId")
                                    val filename = json.getString("filename")
                                    val size = json.getLong("size")

                                    currentIncomingTransferId = txId
                                    currentFilename = filename
                                    currentTotalSize = size
                                    currentReceivedBytes = 0L

                                    try {
                                        storageManager.startIncomingTransfer(txId, filename, size)
                                        onIncomingTransferEvent?.invoke(txId, filename, size, 0L, false, null)
                                    } catch (e: Exception) {
                                        ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"error\",\"message\":\"${e.message}\"}")
                                        onIncomingTransferEvent?.invoke(txId, filename, size, 0L, false, e.message ?: "Init failed")
                                        currentIncomingTransferId = null
                                    }
                                }
                                "done" -> {
                                    val txId = json.getString("transferId")
                                    val checksum = json.getString("checksum")

                                    try {
                                        storageManager.finalizeTransfer(txId, checksum)
                                        ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"ok\",\"verified\":true}")
                                        onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentTotalSize, true, null)
                                    } catch (e: Exception) {
                                        ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"error\",\"message\":\"${e.message}\"}")
                                        onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentReceivedBytes, false, e.message ?: "Verification failed")
                                    }
                                    currentIncomingTransferId = null
                                }
                                "cancel" -> {
                                    val txId = json.optString("transferId")
                                    if (txId.isNotEmpty()) {
                                        storageManager.cancelTransfer(txId)
                                    }
                                    currentIncomingTransferId = null
                                }
                            }
                        }
                        is WebSocketHandler.Frame.Binary -> {
                            // Decrypt AES-256-GCM chunk
                            try {
                                val (_, plaintext) = CryptoEngine.decryptChunk(key, frame.data)
                                currentIncomingTransferId?.let { txId ->
                                    storageManager.appendChunk(txId, plaintext)
                                    currentReceivedBytes += plaintext.size
                                    onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentReceivedBytes, false, null)
                                }
                            } catch (e: Exception) {
                                currentIncomingTransferId?.let { storageManager.cancelTransfer(it) }
                                currentIncomingTransferId = null
                            }
                        }
                        is WebSocketHandler.Frame.Close -> {
                            break
                        }
                        else -> {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                currentIncomingTransferId?.let { storageManager.cancelTransfer(it) }
                sessionManager.unregisterGuestConnection()
            }
        }
    }

    override suspend fun cancel() {
        activeWsHandler?.sendText("{\"type\":\"cancel\",\"reason\":\"user_cancelled\"}")
        storageManager.clearAll()
    }

    override suspend fun close() {
        messageLoopJob?.cancel()
        activeWsHandler?.close(1000, "Session Ended")
        activeWsHandler = null
        activeSessionKey = null
        storageManager.clearAll()
    }
}
