package com.anonymous.fileshare.crypto

import java.security.MessageDigest

/**
 * Streaming incremental SHA-256 hasher for verifying file integrity.
 */
class ChecksumVerifier {

    private val digest = MessageDigest.getInstance("SHA-256")

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        digest.update(data, offset, length)
    }

    fun finalHex(): String {
        val hash = digest.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun verify(data: ByteArray, expectedHex: String): Boolean {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(data)
            val computedHex = hash.joinToString("") { "%02x".format(it) }
            return computedHex.equals(expectedHex, ignoreCase = true)
        }
    }
}
