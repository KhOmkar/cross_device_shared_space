package com.anonymous.fileshare.transfer

import com.anonymous.fileshare.crypto.CryptoEngine
import com.anonymous.fileshare.server.SessionManager
import com.anonymous.fileshare.server.WebSocketHandler
import com.anonymous.fileshare.util.AppLogger
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
        AppLogger.i("WebSocketChannel", "WebSocket attached with established session key")
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

        AppLogger.i("WebSocketChannel", "Starting outgoing sendFile: ${metadata.filename} (${metadata.size} bytes)")
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
                AppLogger.d("WebSocketChannel", "Incoming message loop started")
                while (isActive && !ws.isClosed) {
                    val frame = ws.readFrame() ?: break
                    sessionManager.touch()

                    when (frame) {
                        is WebSocketHandler.Frame.Text -> {
                            val json = JSONObject(frame.message)
                            val type = json.optString("type")
                            AppLogger.d("WebSocketChannel", "Received text frame: type=$type")

                            when (type) {
                                "meta" -> {
                                    val txId = json.getString("transferId")
                                    val filename = json.getString("filename")
                                    val size = json.getLong("size")

                                    currentIncomingTransferId = txId
                                    currentFilename = filename
                                    currentTotalSize = size
                                    currentReceivedBytes = 0L

                                    AppLogger.i("WebSocketChannel", "Incoming meta: id=$txId, name=$filename, size=$size")

                                    try {
                                        storageManager.startIncomingTransfer(txId, filename, size)
                                        onIncomingTransferEvent?.invoke(txId, filename, size, 0L, false, null)
                                    } catch (e: Exception) {
                                        AppLogger.e("WebSocketChannel", "Failed to init incoming transfer: ${e.message}", e)
                                        ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"error\",\"message\":\"${e.message}\"}")
                                        onIncomingTransferEvent?.invoke(txId, filename, size, 0L, false, e.message ?: "Init failed")
                                        currentIncomingTransferId = null
                                    }
                                }
                                "done" -> {
                                    val txId = json.getString("transferId")
                                    val checksum = json.getString("checksum")

                                    AppLogger.i("WebSocketChannel", "Incoming done: id=$txId, checksum=$checksum")

                                    if (!storageManager.hasActiveSession(txId)) {
                                        AppLogger.w("WebSocketChannel", "Ignoring done frame for inactive/cancelled transfer $txId")
                                        ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"error\",\"message\":\"Transfer session was cancelled or failed\"}")
                                    } else {
                                        try {
                                            storageManager.finalizeTransfer(txId, checksum)
                                            AppLogger.i("WebSocketChannel", "Sending ok ack for $txId")
                                            ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"ok\",\"verified\":true}")
                                            onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentTotalSize, true, null)
                                        } catch (e: Exception) {
                                            AppLogger.e("WebSocketChannel", "Verification failed for $txId: ${e.message}", e)
                                            ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"error\",\"message\":\"${e.message}\"}")
                                            onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentReceivedBytes, false, e.message ?: "Verification failed")
                                        }
                                    }
                                    currentIncomingTransferId = null
                                }
                                "cancel" -> {
                                    val txId = json.optString("transferId")
                                    AppLogger.w("WebSocketChannel", "Received cancel frame for $txId")
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
                                val (chunkIdx, plaintext) = CryptoEngine.decryptChunk(key, frame.data)
                                currentIncomingTransferId?.let { txId ->
                                    storageManager.appendChunk(txId, plaintext)
                                    currentReceivedBytes += plaintext.size
                                    AppLogger.d("WebSocketChannel", "Decrypted chunk #$chunkIdx (${plaintext.size} B, total $currentReceivedBytes/$currentTotalSize)")
                                    onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentReceivedBytes, false, null)
                                } ?: run {
                                    AppLogger.w("WebSocketChannel", "Received binary chunk with no active incoming transferId")
                                }
                            } catch (e: Exception) {
                                AppLogger.e("WebSocketChannel", "Chunk decrypt error: ${e.message}", e)
                                currentIncomingTransferId?.let { txId ->
                                    storageManager.cancelTransfer(txId)
                                    ws.sendText("{\"type\":\"ack\",\"transferId\":\"$txId\",\"status\":\"error\",\"message\":\"Decrypt error: ${e.message}\"}")
                                    onIncomingTransferEvent?.invoke(txId, currentFilename, currentTotalSize, currentReceivedBytes, false, "Decrypt error: ${e.message}")
                                }
                                currentIncomingTransferId = null
                            }
                        }
                        is WebSocketHandler.Frame.Close -> {
                            AppLogger.i("WebSocketChannel", "Client sent Close frame: code=${frame.code}, reason=${frame.reason}")
                            break
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("WebSocketChannel", "Loop exception: ${e.message}", e)
            } finally {
                AppLogger.i("WebSocketChannel", "Incoming message loop terminated")
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
