package com.anonymous.fileshare.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance, memory-safe Cryptography Engine for Anonymous Local File Transfer.
 * Implements AES-256-GCM chunk encryption, PBKDF2 password derivation, HKDF key expansion,
 * and memory wiping routines.
 */
object CryptoEngine {

    private const val GCM_TAG_LENGTH_BITS = 128
    private const val NONCE_LENGTH_BYTES = 12
    private const val PBKDF2_ITERATIONS = 10000
    private const val AES_KEY_SIZE_BYTES = 32 // 256 bits

    private val secureRandom = SecureRandom()

    /**
     * Derives password bits w from short pairing code using PBKDF2-HMAC-SHA256.
     */
    fun derivePasswordBits(pairingCode: String, saltStr: String = "anonymous-file-share-v1"): ByteArray {
        val spec = PBEKeySpec(pairingCode.toCharArray(), saltStr.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 256)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    /**
     * HKDF-SHA256 Key Derivation Function to derive 256-bit AES-GCM master key.
     */
    fun deriveMasterKey(
        sharedSecret: ByteArray,
        salt: ByteArray = "anonymous-file-share-salt".toByteArray(Charsets.UTF_8),
        info: ByteArray = "AnonymousFileShare-V1-AESGCM".toByteArray(Charsets.UTF_8)
    ): SecretKey {
        // Step 1: HKDF-Extract: PRK = HMAC-SHA256(salt, sharedSecret)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = hmac.doFinal(sharedSecret)

        // Step 2: HKDF-Expand: OKM = HMAC-SHA256(PRK, info || 0x01)
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update(info)
        hmac.update(0x01.toByte())
        val okm = hmac.doFinal()

        // Clean up PRK from memory
        Arrays.fill(prk, 0.toByte())

        val keyBytes = okm.copyOfRange(0, AES_KEY_SIZE_BYTES)
        Arrays.fill(okm, 0.toByte())

        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts a chunk buffer with AES-256-GCM.
     * Frame format: [SeqNum 4B (BigEndian)][Nonce 12B][Ciphertext + Tag 16B]
     *
     * @param sessionKey Master AES-256 key
     * @param chunkIndex 0-indexed sequence number
     * @param plaintext Raw chunk bytes
     * @param direction 1 = PhoneToGuest, 2 = GuestToPhone
     * @return Complete binary frame byte array
     */
    fun encryptChunk(sessionKey: SecretKey, chunkIndex: Int, plaintext: ByteArray, direction: Byte = 1): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        secureRandom.nextBytes(nonce)
        nonce[0] = direction
        ByteBuffer.wrap(nonce, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(chunkIndex)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec)

        val ciphertext = cipher.doFinal(plaintext)

        val frame = ByteBuffer.allocate(4 + NONCE_LENGTH_BYTES + ciphertext.size)
        frame.order(ByteOrder.BIG_ENDIAN)
        frame.putInt(chunkIndex)
        frame.put(nonce)
        frame.put(ciphertext)

        return frame.array()
    }

    /**
     * Decrypts a binary frame with AES-256-GCM.
     *
     * @param sessionKey Master AES-256 key
     * @param frameBytes Complete binary frame byte array
     * @return Pair of (chunkIndex, decrypted plaintext byte array)
     */
    fun decryptChunk(sessionKey: SecretKey, frameBytes: ByteArray): Pair<Int, ByteArray> {
        require(frameBytes.size >= 4 + NONCE_LENGTH_BYTES + 16) { "Invalid frame size for AES-GCM" }

        val buffer = ByteBuffer.wrap(frameBytes).order(ByteOrder.BIG_ENDIAN)
        val chunkIndex = buffer.int

        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        buffer.get(nonce)

        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)

        val plaintext = cipher.doFinal(ciphertext)
        return Pair(chunkIndex, plaintext)
    }

    /**
     * Generates a cryptographically secure 128-bit hex session token.
     */
    fun generateSessionToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a 6-digit user-friendly numeric pairing code.
     */
    fun generateShortPairingCode(): String {
        val num = secureRandom.nextInt(900000) + 100000
        return num.toString()
    }

    /**
     * Explicit memory wiping for sensitive byte arrays and keys.
     */
    fun wipe(bytes: ByteArray?) {
        if (bytes != null) {
            Arrays.fill(bytes, 0.toByte())
        }
    }
}
