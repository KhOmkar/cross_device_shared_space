package com.anonymous.fileshare.server

import com.anonymous.fileshare.crypto.CryptoEngine
import com.anonymous.fileshare.crypto.Spake2KeyExchange
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

/**
 * Manages the single-session lifecycle, ephemeral tokens, timeouts, single-guest restriction,
 * and memory clearing upon session termination.
 */
class SessionManager(
    val idleTimeoutMs: Long = 5 * 60 * 1000L,       // 5 minutes idle
    val hardExpiryDurationMs: Long = 30 * 60 * 1000L, // 30 minutes hard expiry
    val maxFileSizeBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GB
    val maxSessionBytes: Long = 10L * 1024 * 1024 * 1024 // 10 GB total
) {
    var sessionToken: String = CryptoEngine.generateSessionToken()
        private set

    var pairingCode: String = CryptoEngine.generateShortPairingCode()
        private set

    val createdAtMs: Long = System.currentTimeMillis()
    val expiresAtMs: Long = createdAtMs + hardExpiryDurationMs

    @Volatile
    var lastActivityAtMs: Long = System.currentTimeMillis()
        private set

    val rateLimiter = RateLimiter(maxAttempts = 5, lockoutDurationMs = 30_000L)

    var pakeExchange: Spake2KeyExchange = Spake2KeyExchange(pairingCode, sessionToken)
        private set

    var sessionKey: SecretKey? = null
        private set

    private var activeGuestIp: String? = null
    private val isGuestConnected = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)

    /**
     * Checks if the session is active and not expired.
     */
    fun isSessionValid(): Boolean {
        if (isDestroyed.get()) return false
        val now = System.currentTimeMillis()
        if (now > expiresAtMs) return false
        if (now - lastActivityAtMs > idleTimeoutMs) return false
        return true
    }

    /**
     * Touches session to refresh idle activity timer.
     */
    fun touch() {
        lastActivityAtMs = System.currentTimeMillis()
    }

    /**
     * Validates session token provided by client.
     */
    fun validateToken(token: String?): Boolean {
        if (!isSessionValid()) return false
        if (token.isNullOrEmpty()) return false
        return sessionToken.equals(token, ignoreCase = true)
    }

    /**
     * Validates manual pairing code.
     */
    fun validatePairingCode(code: String?): Boolean {
        if (!isSessionValid()) return false
        if (code.isNullOrEmpty()) return false
        return pairingCode.replace("-", "").equals(code.replace("-", "").trim(), ignoreCase = true)
    }

    /**
     * Enforces single-guest rule. Returns true if connection accepted, false if another guest is active.
     */
    @Synchronized
    fun registerGuestConnection(ip: String): Boolean {
        if (!isSessionValid()) return false
        if (isGuestConnected.get() && activeGuestIp != null && activeGuestIp != ip) {
            // Reject concurrent connection from different guest
            return false
        }
        activeGuestIp = ip
        isGuestConnected.set(true)
        touch()
        return true
    }

    @Synchronized
    fun unregisterGuestConnection() {
        isGuestConnected.set(false)
        activeGuestIp = null
    }

    fun setDerivedKey(key: SecretKey) {
        this.sessionKey = key
    }

    /**
     * Generates a new session token and code, wiping previous keys.
     */
    @Synchronized
    fun renewSession() {
        destroy()
        isDestroyed.set(false)
        sessionToken = CryptoEngine.generateSessionToken()
        pairingCode = CryptoEngine.generateShortPairingCode()
        pakeExchange = Spake2KeyExchange(pairingCode, sessionToken)
        rateLimiter.reset()
        touch()
    }

    /**
     * Explicit memory wipe and teardown.
     */
    @Synchronized
    fun destroy() {
        isDestroyed.set(true)
        isGuestConnected.set(false)
        activeGuestIp = null
        pakeExchange.destroy()
        sessionKey = null
        rateLimiter.reset()
    }
}
