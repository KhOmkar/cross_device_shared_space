package com.anonymous.fileshare.transfer

import com.anonymous.fileshare.crypto.ChecksumVerifier
import com.anonymous.fileshare.crypto.CryptoEngine
import com.anonymous.fileshare.server.WebSocketHandler
import java.io.InputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles streaming file chunks with AES-256-GCM encryption, incremental SHA-256 hashing,
 * and backpressure pacing over the WebSocket.
 */
object ChunkStreamer {

    private const val DEFAULT_CHUNK_SIZE = 1024 * 1024 // 1 MB

    suspend fun streamFile(
        metadata: FileMetadata,
        dataStream: InputStream,
        sessionKey: SecretKey,
        wsHandler: WebSocketHandler,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): TransferResult = withContext(Dispatchers.IO) {
        val hasher = ChecksumVerifier()
        val buffer = ByteArray(metadata.chunkSize)
        var bytesSent = 0L
        var chunkIndex = 0

        try {
            // 1. Send Metadata Control Frame
            val metaJson = "{" +
                    "\"type\":\"meta\"," +
                    "\"transferId\":\"${metadata.transferId}\"," +
                    "\"filename\":\"${metadata.filename}\"," +
                    "\"size\":${metadata.size}," +
                    "\"chunkSize\":${metadata.chunkSize}," +
                    "\"totalChunks\":${metadata.totalChunks}," +
                    "\"direction\":\"phone_to_guest\"" +
                    "}"
            wsHandler.sendText(metaJson)

            // 2. Stream Binary Encrypted Chunks
            while (bytesSent < metadata.size) {
                if (wsHandler.isClosed) {
                    return@withContext TransferResult.Failure(metadata.transferId, "Connection closed by peer")
                }

                val bytesRead = dataStream.read(buffer, 0, minOf(buffer.size.toLong(), metadata.size - bytesSent).toInt())
                if (bytesRead == -1) break

                val chunkData = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)

                // Update incremental SHA-256
                hasher.update(chunkData)

                // Encrypt chunk with AES-256-GCM
                val encryptedFrame = CryptoEngine.encryptChunk(
                    sessionKey = sessionKey,
                    chunkIndex = chunkIndex,
                    plaintext = chunkData,
                    direction = 1 // Phone to guest
                )

                // Send binary frame
                wsHandler.sendBinary(encryptedFrame)

                bytesSent += bytesRead
                chunkIndex++

                onProgress(bytesSent, metadata.size)
            }

            // 3. Send Done Frame with SHA-256 Checksum
            val checksum = hasher.finalHex()
            val doneJson = "{" +
                    "\"type\":\"done\"," +
                    "\"transferId\":\"${metadata.transferId}\"," +
                    "\"checksum\":\"$checksum\"" +
                    "}"
            wsHandler.sendText(doneJson)

            return@withContext TransferResult.Success(metadata.transferId, checksum)
        } catch (e: Exception) {
            return@withContext TransferResult.Failure(metadata.transferId, e.message ?: "Transfer failed")
        } finally {
            CryptoEngine.wipe(buffer)
            try { dataStream.close() } catch (_: Exception) {}
        }
    }
}
