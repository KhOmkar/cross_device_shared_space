package com.anonymous.fileshare.server

import android.content.Context
import android.util.Base64
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded HTTP/1.1 and WebSocket Server for Android.
 * Runs with zero external web server dependencies, serves bundled assets, enforces strict CSP,
 * handles WebSocket upgrades, and delegates active connections to the transfer engine.
 */
class EmbeddedHttpServer(
    private val context: Context,
    val sessionManager: SessionManager,
    private val port: Int = 8080,
    private val onWebSocketConnected: (WebSocketHandler, String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    companion object {
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val CSP_HEADER = "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; object-src 'none'; base-uri 'none'; frame-ancestors 'none';"
    }

    @Synchronized
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        executor = Executors.newCachedThreadPool()

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", port))
        this.serverSocket = ss

        executor?.submit {
            while (isRunning.get() && !ss.isClosed) {
                try {
                    val clientSocket = ss.accept()
                    clientSocket.tcpNoDelay = true
                    executor?.submit {
                        handleClientConnection(clientSocket)
                    }
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handleClientConnection(socket: Socket) {
        val clientIp = socket.inetAddress.hostAddress ?: "unknown"

        try {
            val inStream = socket.getInputStream()
            val outStream = socket.getOutputStream()
            val lines = readHttpHeaderLines(inStream) ?: return
            if (lines.isEmpty()) return

            val requestLine = lines[0]
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val headerLine = lines[i]
                if (headerLine.isEmpty()) break
                val headerParts = headerLine.split(":", limit = 2)
                if (headerParts.size == 2) {
                    headers[headerParts[0].trim().lowercase()] = headerParts[1].trim()
                }
            }

            val uriParts = fullPath.split("?", limit = 2)
            val path = uriParts[0]
            val queryParams = parseQueryParams(if (uriParts.size > 1) uriParts[1] else "")

            when {
                path == "/" && method == "GET" -> {
                    serveStaticAsset(outStream, "web/index.html", "text/html; charset=UTF-8")
                    socket.close()
                }
                path == "/session-info" && method == "GET" -> {
                    serveSessionInfo(outStream)
                    socket.close()
                }
                path == "/transfer" && method == "GET" -> {
                    val token = queryParams["token"]
                    val isUpgrade = headers["upgrade"]?.equals("websocket", ignoreCase = true) == true

                    if (!isUpgrade) {
                        sendHttpResponse(outStream, 400, "Bad Request", "text/plain", "WebSocket upgrade required")
                        socket.close()
                        return
                    }

                    // Check Rate Limiter
                    if (!sessionManager.rateLimiter.isAllowed(clientIp)) {
                        sendHttpResponse(outStream, 429, "Too Many Requests", "application/json", "{\"error\": \"Rate limited. Try again later.\"}")
                        socket.close()
                        return
                    }

                    // Enforce Single Guest
                    if (!sessionManager.registerGuestConnection(clientIp)) {
                        sendHttpResponse(outStream, 403, "Forbidden", "application/json", "{\"error\": \"Another guest connection is already active.\"}")
                        socket.close()
                        return
                    }

                    // Complete WebSocket Handshake
                    val secKey = headers["sec-websocket-key"] ?: ""
                    val acceptKey = computeSecWebSocketAccept(secKey)

                    val responseHeaders = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

                    outStream.write(responseHeaders.toByteArray(Charsets.UTF_8))
                    outStream.flush()

                    val wsHandler = WebSocketHandler(socket, inStream, outStream)
                    onWebSocketConnected(wsHandler, token ?: "")
                }
                else -> {
                    sendHttpResponse(outStream, 404, "Not Found", "text/plain", "Not Found")
                    socket.close()
                }
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun serveStaticAsset(out: OutputStream, assetPath: String, contentType: String) {
        try {
            val assetStream = context.assets.open(assetPath)
            val content = assetStream.readBytes()
            assetStream.close()

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${content.size}\r\n" +
                    "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                    "$CSP_HEADER\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n"

            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(content)
            out.flush()
        } catch (e: Exception) {
            sendHttpResponse(out, 500, "Internal Server Error", "text/plain", "Failed to load asset")
        }
    }

    private fun serveSessionInfo(out: OutputStream) {
        val json = "{" +
                "\"token_required\": true," +
                "\"expires_at\": ${sessionManager.expiresAtMs}," +
                "\"max_file_size\": ${sessionManager.maxFileSizeBytes}" +
                "}"
        sendHttpResponse(out, 200, "OK", "application/json", json)
    }

    private fun sendHttpResponse(out: OutputStream, statusCode: Int, statusText: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "$CSP_HEADER\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun computeSecWebSocketAccept(key: String): String {
        val combined = key.trim() + WEBSOCKET_GUID
        val sha1 = MessageDigest.getInstance("SHA-1").digest(combined.toByteArray(Charsets.ISO_8859_1))
        return Base64.encodeToString(sha1, Base64.NO_WRAP)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isEmpty()) return map
        for (param in query.split("&")) {
            val parts = param.split("=", limit = 2)
            val k = URLDecoder.decode(parts[0], "UTF-8")
            val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
            map[k] = v
        }
        return map
    }

    private fun readHttpHeaderLines(inStream: InputStream): List<String>? {
        val headerBytes = ByteArrayOutputStream()
        var lastFour = 0
        while (true) {
            val b = inStream.read()
            if (b == -1) return if (headerBytes.size() > 0) String(headerBytes.toByteArray(), Charsets.UTF_8).lines() else null
            headerBytes.write(b)
            if (headerBytes.size() > 65536) throw IllegalArgumentException("HTTP headers too large")

            lastFour = (lastFour shl 8) or (b and 0xFF)
            if ((lastFour and 0xFFFFFFFF.toInt()) == 0x0D0A0D0A || (lastFour and 0xFFFF) == 0x0A0A) {
                break
            }
        }
        val text = String(headerBytes.toByteArray(), Charsets.UTF_8)
        return text.lines().map { it.trimEnd('\r') }
    }
}
