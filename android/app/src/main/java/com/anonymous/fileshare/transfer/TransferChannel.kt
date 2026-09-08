package com.anonymous.fileshare.transfer

import java.io.InputStream
import kotlinx.coroutines.flow.Flow

/**
 * Data model for file transfer metadata exchanged across the channel.
 */
data class FileMetadata(
    val transferId: String,
    val filename: String,
    val size: Long,
    val chunkSize: Int = 1024 * 1024,
    val totalChunks: Int,
    val direction: TransferDirection
)

enum class TransferDirection {
    PHONE_TO_GUEST,
    GUEST_TO_PHONE
}

sealed class TransferResult {
    data class Success(val transferId: String, val checksum: String) : TransferResult()
    data class Failure(val transferId: String, val error: String) : TransferResult()
}

/**
 * Abstraction Boundary for File Transfer Channels.
 * Decouples the UI and Pairing layer from the underlying transport protocol.
 *
 * - V1 implements this over local WebSocket connections.
 * - V2 can implement this exact interface over WebRTC DataChannels for remote transfers.
 */
interface TransferChannel {

    /**
     * Connects or establishes the channel with the peer.
     */
    suspend fun connect(endpoint: String, token: String)

    /**
     * Sends a file stream to the peer with chunking, encryption, and backpressure.
     *
     * @param metadata File descriptor
     * @param dataStream Plaintext input stream
     * @param onProgress Callback receiving (bytesSent, totalBytes)
     * @return Result containing verified SHA-256 or failure reason
     */
    suspend fun sendFile(
        metadata: FileMetadata,
        dataStream: InputStream,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
    ): TransferResult

    /**
     * Registers a handler for receiving incoming file streams from the peer.
     */
    fun setReceiveHandler(
        handler: suspend (metadata: FileMetadata, chunkFlow: Flow<ByteArray>) -> Unit
    )

    /**
     * Cancels a specific transfer by ID.
     */
    fun cancelTransfer(transferId: String)

    /**
     * Cancels any active transfer and cleans up associated buffers.
     */
    suspend fun cancel()

    /**
     * Closes the channel and zeroes active cryptographic session keys.
     */
    suspend fun close()
}
