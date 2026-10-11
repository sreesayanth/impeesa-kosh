package com.impeesa.finance.core.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the random 256-bit database key, encrypted with an AES key that never leaves
 * Android Keystore. The Keystore key needs no user authentication so SMS capture keeps
 * working while the app is locked (see IMPLEMENTATION_PLAN.md §12.3).
 */
class DatabaseKeyManager(context: Context) {
    private val keyFile = File(context.noBackupFilesDir, KEY_FILE)

    @Synchronized
    fun passphrase(): ByteArray {
        if (keyFile.exists()) return decrypt(keyFile.readBytes())
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val temp = File(keyFile.parentFile, "$KEY_FILE.tmp")
        temp.writeBytes(encrypt(raw))
        check(temp.renameTo(keyFile)) { "Could not store database key" }
        return raw
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        val ivLength = stored[0].toInt()
        val iv = stored.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(stored, 1 + ivLength, stored.size - 1 - ivLength)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "impeesa_db_wrapping_key"
        const val KEY_FILE = "db.key.enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
