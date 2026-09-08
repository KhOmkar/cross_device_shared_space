package com.anonymous.fileshare.server

import com.anonymous.fileshare.util.AppLogger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Robust, lightweight RFC 6455 WebSocket Frame Decoder and Encoder.
 * Supports Text (JSON control frames), Binary (AES-GCM encrypted chunks),
 * Ping/Pong heartbeats, Close frames, and client unmasking.
 */
class WebSocketHandler(
    private val socket: Socket,
    private val inStream: InputStream,
    private val outStream: OutputStream
) {
    private val dataIn = DataInputStream(BufferedInputStream(inStream))
    private val bufferedOut = BufferedOutputStream(outStream)

    @Volatile
    var isClosed: Boolean = false
        private set

    private var fragmentedOpcode: Int = 0
    private val fragmentedBuffer = ByteArrayOutputStream()

    sealed class Frame {
        data class Text(val message: String) : Frame()
        data class Binary(val data: ByteArray) : Frame()
        data class Close(val code: Int, val reason: String) : Frame()
        object Ping : Frame()
        object Pong : Frame()
    }

    /**
     * Reads the next incoming WebSocket frame from the client.
     * Reassembles fragmented frames (continuation frames) seamlessly.
     * Blocks until a full message is received or socket closed.
     */
    fun readFrame(): Frame? {
        if (isClosed || socket.isClosed) return null

        try {
            while (!isClosed && !socket.isClosed) {
                val b1 = dataIn.readUnsignedByte()
                val isFin = (b1 and 0x80) != 0
                val opcode = b1 and 0x0F

                val b2 = dataIn.readUnsignedByte()
                val isMasked = (b2 and 0x80) != 0
                var payloadLength = (b2 and 0x7F).toLong()

                if (payloadLength == 126L) {
                    payloadLength = dataIn.readUnsignedShort().toLong()
                } else if (payloadLength == 127L) {
                    payloadLength = dataIn.readLong()
                }

                require(payloadLength >= 0 && payloadLength <= 100 * 1024 * 1024) { "Invalid payload length: $payloadLength" }

                val mask = if (isMasked) {
                    val m = ByteArray(4)
                    dataIn.readFully(m)
                    m
                } else null

                val payload = ByteArray(payloadLength.toInt())
                dataIn.readFully(payload)

                if (isMasked && mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }

                when (opcode) {
                    0x00 -> {
                        // Continuation frame
                        fragmentedBuffer.write(payload)
                        if (isFin) {
                            val completeData = fragmentedBuffer.toByteArray()
                            val originalOpcode = fragmentedOpcode
                            fragmentedBuffer.reset()
                            fragmentedOpcode = 0

                            return if (originalOpcode == 0x01) {
                                Frame.Text(String(completeData, Charsets.UTF_8))
                            } else {
                                Frame.Binary(completeData)
                            }
                        }
                    }
                    0x01, 0x02 -> {
                        // Text (0x01) or Binary (0x02)
                        if (isFin) {
                            fragmentedBuffer.reset()
                            fragmentedOpcode = 0
                            return if (opcode == 0x01) {
                                Frame.Text(String(payload, Charsets.UTF_8))
                            } else {
                                Frame.Binary(payload)
                            }
                        } else {
                            // Start of fragmented message
                            fragmentedOpcode = opcode
                            fragmentedBuffer.reset()
                            fragmentedBuffer.write(payload)
                        }
                    }
                    0x08 -> {
                        // Close frame
                        isClosed = true
                        val code = if (payload.size >= 2) ByteBuffer.wrap(payload, 0, 2).short.toInt() else 1000
                        val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                        return Frame.Close(code, reason)
                    }
                    0x09 -> {
                        // Ping -> auto-reply Pong
                        sendPong(payload)
                        if (fragmentedOpcode == 0) {
                            return Frame.Ping
                        }
                    }
                    0x0A -> {
                        if (fragmentedOpcode == 0) {
                            return Frame.Pong
                        }
                    }
                    else -> {
                        AppLogger.w("WebSocketHandler", "Unknown opcode: $opcode, ignoring")
                    }
                }
            }
            return null
        } catch (e: EOFException) {
            isClosed = true
            return null
        } catch (e: Exception) {
            AppLogger.e("WebSocketHandler", "readFrame socket exception: ${e.message}", e)
            isClosed = true
            return null
        }
    }

    /**
     * Sends a Text frame to client.
     */
    @Synchronized
    fun sendText(text: String) {
        if (isClosed || socket.isClosed) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeFrame(0x01, bytes)
    }

    /**
     * Sends a Binary frame to client.
     */
    @Synchronized
    fun sendBinary(data: ByteArray) {
        if (isClosed || socket.isClosed) return
        writeFrame(0x02, data)
    }

    /**
     * Sends a Pong reply.
     */
    @Synchronized
    fun sendPong(payload: ByteArray = ByteArray(0)) {
        if (isClosed || socket.isClosed) return
        writeFrame(0x0A, payload)
    }

    /**
     * Sends Close frame and closes socket.
     */
    @Synchronized
    fun close(code: Int = 1000, reason: String = "Normal Closure") {
        if (isClosed) return
        isClosed = true
        try {
            val reasonBytes = reason.toByteArray(Charsets.UTF_8)
            val payload = ByteBuffer.allocate(2 + reasonBytes.size)
                .putShort(code.toShort())
                .put(reasonBytes)
                .array()
            writeFrame(0x08, payload)
            socket.close()
        } catch (_: Exception) {}
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val len = payload.size
        // FIN bit set (0x80) + opcode
        bufferedOut.write(0x80 or (opcode and 0x0F))

        // Server-to-client frames are unmasked (mask bit = 0)
        when {
            len <= 125 -> {
                bufferedOut.write(len)
            }
            len <= 65535 -> {
                bufferedOut.write(126)
                bufferedOut.write((len shr 8) and 0xFF)
                bufferedOut.write(len and 0xFF)
            }
            else -> {
                bufferedOut.write(127)
                for (i in 7 downTo 0) {
                    bufferedOut.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        if (len > 0) {
            bufferedOut.write(payload)
        }
        bufferedOut.flush()
    }
}
