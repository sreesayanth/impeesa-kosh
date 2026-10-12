package com.impeesa.finance.core.model.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup file could not be opened. */
sealed class BackupException(message: String) : Exception(message) {
    class NotABackup : BackupException("This isn't an Kosh backup file")
    class UnsupportedVersion(val version: Int) : BackupException("This backup was made by a newer version of Kosh")
    class WrongPassphrase : BackupException("Wrong passphrase, or the file is damaged")
}

/**
 * The encrypted backup file (IMPLEMENTATION_PLAN.md §9.8):
 *
 * `magic "IMPKBK" | version (1 B) | PBKDF2 iterations (4 B) | salt (16 B) | IV (12 B) | AES-256-GCM(gzip(payload))`
 *
 * The key comes from the user's passphrase with PBKDF2-HMAC-SHA256, so a backup opens on any phone.
 * Built-in JCA only. The header is authenticated as GCM associated data, so tampering is detected.
 */
object BackupFormat {
    private val MAGIC = "IMPKBK".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 8
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 6 + 1 + 4 + SALT_BYTES + IV_BYTES

    fun encrypt(payload: ByteArray, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase must be at least $MIN_PASSPHRASE characters" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION.toByte()).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(gzip(payload))
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        if (file.size < HEADER_BYTES + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BackupException.NotABackup()
        val buffer = ByteBuffer.wrap(file)
        buffer.position(MAGIC.size)
        val version = buffer.get().toInt() and 0xFF
        if (version > VERSION) throw BackupException.UnsupportedVersion(version)
        val iterations = buffer.int
        if (iterations !in 1_000..10_000_000) throw BackupException.NotABackup()
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val iv = ByteArray(IV_BYTES).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(file, 0, HEADER_BYTES)
        val compressed = try {
            cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (_: AEADBadTagException) {
            throw BackupException.WrongPassphrase()
        }
        return gunzip(compressed)
    }

    /** Reads the version without the passphrase, to fail early on files from newer apps. */
    fun peekVersion(file: ByteArray): Int? =
        if (file.size > MAGIC.size && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) file[MAGIC.size].toInt() and 0xFF else null

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

/** CSV export (§9.8): RFC 4180 quoting, and a guard against spreadsheet formula injection. */
object Csv {
    fun row(fields: List<String?>): String = fields.joinToString(",") { escape(it.orEmpty()) }

    fun escape(value: String): String {
        // A cell starting with = + - @ would run as a formula in Excel/Sheets; prefix it so it's text.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || safe != safe.trim()
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
