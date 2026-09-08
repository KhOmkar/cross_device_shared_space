package com.anonymous.fileshare.server

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, session-scoped Rate Limiter.
 * Enforces max failed pairing attempts (default: 5) and temporary lockout (default: 30s)
 * to prevent brute forcing of short manual pairing codes.
 */
class RateLimiter(
    private val maxAttempts: Int = 5,
    private val lockoutDurationMs: Long = 30_000L
) {
    private data class AttemptRecord(
        var failedCount: Int = 0,
        var lockedUntilMs: Long = 0L
    )

    private val records = ConcurrentHashMap<String, AttemptRecord>()

    /**
     * Checks if the given key (e.g. client IP) is currently locked out.
     * @return true if allowed to proceed, false if locked out.
     */
    fun isAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val record = records[key] ?: return true

        if (record.lockedUntilMs > now) {
            return false
        }

        // If lockout expired, reset counter
        if (record.lockedUntilMs in 1..now) {
            record.failedCount = 0
            record.lockedUntilMs = 0L
        }

        return record.failedCount < maxAttempts
    }

    /**
     * Records a failed attempt. Locks out if threshold exceeded.
     * @return Remaining allowed attempts before lockout.
     */
    fun recordFailure(key: String): Int {
        val now = System.currentTimeMillis()
        val record = records.computeIfAbsent(key) { AttemptRecord() }

        record.failedCount++
        if (record.failedCount >= maxAttempts) {
            record.lockedUntilMs = now + lockoutDurationMs
            return 0
        }

        return maxAttempts - record.failedCount
    }

    /**
     * Resets failure counter upon successful authentication.
     */
    fun recordSuccess(key: String) {
        records.remove(key)
    }

    /**
     * Actively wipes all records from memory.
     */
    fun reset() {
        records.clear()
    }
}
