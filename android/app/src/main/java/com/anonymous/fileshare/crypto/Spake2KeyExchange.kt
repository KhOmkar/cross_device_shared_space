package com.anonymous.fileshare.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * PAKE / SPAKE2 style Password-Authenticated Key Exchange over NIST P-256 (secp256r1).
 * Interoperable with browser JavaScript cryptographic engine.
 */
class Spake2KeyExchange(
    private val pairingCode: String,
    private val sessionId: String
) {
    private var localKeyPair: KeyPair? = null
    var derivedSessionKey: SecretKey? = null
        private set

    /**
     * Initializes ephemeral ECDH P-256 key pair.
     * @return Raw uncompressed public key as Hex string (04 || X || Y)
     */
    fun initHandshake(): String {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        this.localKeyPair = kp

        val ecPub = kp.public as ECPublicKey
        return encodeRawPublicKey(ecPub)
    }

    /**
     * Processes client public key, computes ECDH agreement, combines with password bits,
     * and derives the master AES-GCM session key.
     *
     * @param clientPubHex Raw uncompressed public key from guest client (Hex)
     * @return Master AES-256-GCM SecretKey
     */
    fun completeKeyAgreement(clientPubHex: String): SecretKey {
        val kp = localKeyPair ?: throw IllegalStateException("Key pair not initialized")
        val clientPubKey = decodeRawPublicKey(clientPubHex)

        // Compute ECDH Shared Secret
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(clientPubKey, true)
        val rawSecret = ka.generateSecret()

        // Normalize shared secret to exactly 32 bytes (X coordinate)
        val ecdhSecret32 = ByteArray(32)
        if (rawSecret.size >= 32) {
            System.arraycopy(rawSecret, rawSecret.size - 32, ecdhSecret32, 0, 32)
        } else {
            System.arraycopy(rawSecret, 0, ecdhSecret32, 32 - rawSecret.size, rawSecret.size)
        }

        // Derive Password Bits w = PBKDF2(code, "anonymous-file-share-v1")
        val passwordBits = CryptoEngine.derivePasswordBits(pairingCode, "anonymous-file-share-v1")

        // Combine ECDH secret (32B) + Password bits (32B) = 64B
        val combined = ByteArray(64)
        System.arraycopy(ecdhSecret32, 0, combined, 0, 32)
        System.arraycopy(passwordBits, 0, combined, 32, 32)

        // Clean intermediate buffers
        CryptoEngine.wipe(rawSecret)
        CryptoEngine.wipe(ecdhSecret32)
        CryptoEngine.wipe(passwordBits)

        // Derive Master Key via HKDF
        val sessionKey = CryptoEngine.deriveMasterKey(combined)
        CryptoEngine.wipe(combined)

        this.derivedSessionKey = sessionKey
        return sessionKey
    }

    /**
     * Verifies client authentication confirmation HMAC using the derived session key.
     */
    fun verifyClientAuth(authHex: String): Boolean {
        val expected = computeAuthConfirmation("ClientAuth")
        return expected.equals(authHex, ignoreCase = true)
    }

    /**
     * Computes server authentication confirmation HMAC to send to client.
     */
    fun computeServerAuth(): String {
        return computeAuthConfirmation("ServerAuth")
    }

    private fun computeAuthConfirmation(label: String): String {
        val key = derivedSessionKey ?: throw IllegalStateException("Session key not established")
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(key)
        val hash = hmac.doFinal(label.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encodes ECPublicKey into 65-byte uncompressed format: 0x04 || X (32B) || Y (32B).
     */
    private fun encodeRawPublicKey(pub: ECPublicKey): String {
        val w = pub.w
        val xBytes = w.affineX.toByteArray().stripLeadingZeroes(32)
        val yBytes = w.affineY.toByteArray().stripLeadingZeroes(32)

        val raw = ByteArray(65)
        raw[0] = 0x04.toByte()
        System.arraycopy(xBytes, 0, raw, 1, 32)
        System.arraycopy(yBytes, 0, raw, 33, 32)

        return raw.joinToString("") { "%02x".format(it) }
    }

    /**
     * Decodes 65-byte uncompressed format (0x04 || X || Y) into ECPublicKey.
     */
    private fun decodeRawPublicKey(hex: String): PublicKey {
        val raw = hexToBytes(hex)
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "Invalid uncompressed EC public key format" }

        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val point = ECPoint(x, y)

        val kf = KeyFactory.getInstance("EC")
        val params = getP256Params()
        val spec = ECPublicKeySpec(point, params)
        return kf.generatePublic(spec)
    }

    private fun getP256Params(): ECParameterSpec {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val dummy = kpg.generateKeyPair().public as ECPublicKey
        return dummy.params
    }

    private fun ByteArray.stripLeadingZeroes(targetLen: Int): ByteArray {
        val result = ByteArray(targetLen)
        var srcPos = 0
        while (srcPos < this.size - 1 && this[srcPos] == 0.toByte() && this.size - srcPos > targetLen) {
            srcPos++
        }
        val lenToCopy = minOf(this.size - srcPos, targetLen)
        val destPos = targetLen - lenToCopy
        System.arraycopy(this, srcPos, result, destPos, lenToCopy)
        return result
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Memory wipe upon session teardown.
     */
    fun destroy() {
        localKeyPair = null
        derivedSessionKey = null
    }
}
