package com.impeesa.finance.core.model.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** A PIN stored as PBKDF2-HMAC-SHA256 with a per-install salt (IMPLEMENTATION_PLAN.md §12.4). Never the PIN itself. */
data class PinHash(val salt: ByteArray, val hash: ByteArray, val iterations: Int) {
    override fun equals(other: Any?) = other is PinHash && salt.contentEquals(other.salt) && hash.contentEquals(other.hash) && iterations == other.iterations
    override fun hashCode() = 31 * salt.contentHashCode() + hash.contentHashCode()
}

object PinHasher {
    const val DEFAULT_ITERATIONS = 150_000
    val LENGTHS = 4..6

    fun isValid(pin: String): Boolean = pin.length in LENGTHS && pin.all(Char::isDigit)

    fun hash(pin: String, iterations: Int = DEFAULT_ITERATIONS, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): PinHash {
        require(isValid(pin)) { "PIN must be 4–6 digits" }
        return PinHash(salt, derive(pin, salt, iterations), iterations)
    }

    /** Constant-time comparison, so timing doesn't leak how much of a guess was right. */
    fun verify(pin: String, stored: PinHash): Boolean =
        isValid(pin) && MessageDigest.isEqual(derive(pin, stored.salt, stored.iterations), stored.hash)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/** When the app locks, and how long wrong PINs keep it locked (§12.1, §12.4). */
object LockPolicy {
    /** Lock-after choices in seconds: immediately, 1 minute, 5 minutes. */
    val TIMEOUTS = listOf(0, 60, 300)
    const val FREE_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 60L * 60 * 1000

    /** Lock on return if the app was in the background at least [timeoutSeconds]. */
    fun shouldLock(enabled: Boolean, backgroundedAt: Long?, now: Long, timeoutSeconds: Int): Boolean =
        enabled && (backgroundedAt == null || now - backgroundedAt >= timeoutSeconds * 1000L)

    /** After 5 wrong PINs: 30 s, then doubling each time, up to an hour. */
    fun lockoutMillis(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0
        val steps = (failures - FREE_ATTEMPTS).coerceAtMost(10)
        return (BASE_LOCKOUT_MS shl steps).coerceAtMost(MAX_LOCKOUT_MS)
    }
}
