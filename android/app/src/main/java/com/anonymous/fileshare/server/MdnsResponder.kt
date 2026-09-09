package com.anonymous.fileshare.server

import android.content.Context
import android.net.wifi.WifiManager
import com.anonymous.fileshare.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections

/**
 * Lightweight, zero-dependency Multicast DNS (RFC 6762) Responder.
 *
 * Listens on UDP 224.0.0.251:5353 and authoritatively answers standard DNS A-record queries
 * for memorable short domain names:
 *   - share.local
 *   - drop.local
 *   - air.local
 *   - fileshare.local
 *
 * This allows guest PCs, Macs, iPhones, and Android devices connected to the hotspot
 * to simply navigate to http://share.local:8080 without typing complex IP addresses.
 */
class MdnsResponder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ipProvider: () -> String?
) {

    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listenJob: Job? = null
    private var announceJob: Job? = null

    @Volatile
    private var isRunning = false

    private val supportedDomains = setOf(
        "share.local",
        "drop.local",
        "air.local",
        "fileshare.local"
    )

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("AnonymousFileShareMdnsLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            AppLogger.d("MdnsResponder", "Multicast lock acquired")
        } catch (e: Exception) {
            AppLogger.w("MdnsResponder", "Could not acquire MulticastLock: ${e.message}")
        }

        listenJob = scope.launch(Dispatchers.IO) {
            runResponderLoop()
        }

        announceJob = scope.launch(Dispatchers.IO) {
            // Initial gratuitous announcements
            repeat(3) {
                delay(1000)
                if (isActive && isRunning) {
                    announce()
                }
            }
            // Periodic keep-alive announcement every 30 seconds
            while (isActive && isRunning) {
                delay(30000)
                announce()
            }
        }
    }

    private fun runResponderLoop() {
        val group = InetAddress.getByName("224.0.0.251")
        val port = 5353

        try {
            val mSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                timeToLive = 255
            }
            socket = mSocket

            // Join group on all active multicast-capable interfaces
            joinMulticastGroup(mSocket, group, port)

            AppLogger.i("MdnsResponder", "mDNS responder active on 224.0.0.251:5353 for ${supportedDomains.joinToString(", ")}")

            val buffer = ByteArray(2048)
            while (isRunning && !mSocket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    mSocket.receive(packet)
                    handleIncomingPacket(mSocket, packet)
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        } catch (e: Exception) {
            AppLogger.w("MdnsResponder", "mDNS loop error: ${e.message}")
        } finally {
            closeSocket()
        }
    }

    private fun joinMulticastGroup(mSocket: MulticastSocket, group: InetAddress, port: Int) {
        try {
            mSocket.joinGroup(group)
        } catch (_: Exception) {}

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                try {
                    if (nif.isUp && !nif.isLoopback && nif.supportsMulticast()) {
                        mSocket.joinGroup(InetSocketAddress(group, port), nif)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun handleIncomingPacket(mSocket: MulticastSocket, packet: DatagramPacket) {
        val data = packet.data
        val len = packet.length
        if (len < 12) return

        // Parse DNS Header
        val txId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

        // Only process standard queries (QR bit == 0)
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery || qdCount <= 0) return

        var offset = 12
        for (q in 0 until qdCount) {
            if (offset >= len) break
            val (domain, nextOffset) = parseQName(data, offset, len)
            offset = nextOffset
            if (offset + 4 > len) break

            val qType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val qClassRaw = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4

            val unicastResponse = (qClassRaw and 0x8000) != 0
            val qClass = qClassRaw and 0x7FFF

            // Match Type A (1) or ANY (255) with Class IN (1)
            if ((qType == 1 || qType == 255) && (qClass == 1 || qClass == 255)) {
                val matchedDomain = supportedDomains.find { it.equals(domain, ignoreCase = true) }
                if (matchedDomain != null) {
                    val currentIp = ipProvider() ?: "192.168.43.1"
                    val responseBytes = buildMdnsResponse(matchedDomain, currentIp, txId)

                    AppLogger.d("MdnsResponder", "Answering mDNS query for $matchedDomain -> $currentIp to ${packet.address}:${packet.port}")

                    // Send response: unicast to sender
                    try {
                        val unicastPacket = DatagramPacket(responseBytes, responseBytes.size, packet.address, packet.port)
                        mSocket.send(unicastPacket)
                    } catch (e: Exception) {
                        AppLogger.w("MdnsResponder", "Unicast send failed: ${e.message}")
                    }

                    // Also multicast response to 224.0.0.251:5353 if standard mDNS query
                    if (!unicastResponse && packet.port == 5353) {
                        try {
                            val multicastPacket = DatagramPacket(responseBytes, responseBytes.size, InetAddress.getByName("224.0.0.251"), 5353)
                            mSocket.send(multicastPacket)
                        } catch (_: Exception) {}
                    }
                    break
                }
            }
        }
    }

    /**
     * Sends gratuitous mDNS announcements to announce presence on the network.
     */
    fun announce() {
        val currentIp = ipProvider() ?: "192.168.43.1"
        val mSocket = socket ?: return
        if (mSocket.isClosed) return

        try {
            val group = InetAddress.getByName("224.0.0.251")
            for (domain in supportedDomains) {
                val responseBytes = buildMdnsResponse(domain, currentIp, 0)
                val packet = DatagramPacket(responseBytes, responseBytes.size, group, 5353)
                mSocket.send(packet)
            }
            AppLogger.d("MdnsResponder", "Sent gratuitous mDNS announcement for $currentIp")
        } catch (_: Exception) {}
    }

    private fun parseQName(data: ByteArray, startOffset: Int, maxLen: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var offset = startOffset
        var jumped = false
        var returnOffset = startOffset

        while (offset < maxLen) {
            val length = data[offset].toInt() and 0xFF
            if (length == 0) {
                offset += 1
                if (!jumped) returnOffset = offset
                break
            }

            if ((length and 0xC0) == 0xC0) {
                // Compression pointer
                if (offset + 1 >= maxLen) break
                val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                if (!jumped) {
                    returnOffset = offset + 2
                    jumped = true
                }
                offset = pointer
                continue
            }

            offset += 1
            if (offset + length > maxLen) break
            val label = String(data, offset, length, Charsets.UTF_8)
            labels.add(label)
            offset += length
            if (!jumped) returnOffset = offset
        }

        return Pair(labels.joinToString(".").lowercase(), if (jumped) returnOffset else offset)
    }

    private fun buildMdnsResponse(domain: String, ipStr: String, txId: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        // DNS Header (12 bytes)
        dos.writeShort(txId)       // ID
        dos.writeShort(0x8400)     // Flags: QR=1 (Response), AA=1 (Authoritative), RCODE=0
        dos.writeShort(0)          // QDCOUNT = 0
        dos.writeShort(1)          // ANCOUNT = 1
        dos.writeShort(0)          // NSCOUNT = 0
        dos.writeShort(0)          // ARCOUNT = 0

        // Answer Record: QNAME
        for (part in domain.split(".")) {
            val partBytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(partBytes.size)
            dos.write(partBytes)
        }
        dos.writeByte(0) // End of domain labels

        // TYPE = 1 (A Record)
        dos.writeShort(1)
        // CLASS = 0x8001 (IN Class with Cache-Flush bit per RFC 6762)
        dos.writeShort(0x8001)
        // TTL = 120 seconds
        dos.writeInt(120)
        // RDLENGTH = 4 bytes
        dos.writeShort(4)

        // RDATA = IPv4 Bytes
        val ipParts = ipStr.split(".")
        for (part in ipParts) {
            dos.writeByte(part.toIntOrNull() ?: 0)
        }

        dos.flush()
        return bos.toByteArray()
    }

    fun stop() {
        isRunning = false
        listenJob?.cancel()
        announceJob?.cancel()
        listenJob = null
        announceJob = null

        closeSocket()

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null

        AppLogger.i("MdnsResponder", "mDNS responder stopped")
    }

    private fun closeSocket() {
        try {
            socket?.let {
                if (!it.isClosed) it.close()
            }
        } catch (_: Exception) {}
        socket = null
    }
}
