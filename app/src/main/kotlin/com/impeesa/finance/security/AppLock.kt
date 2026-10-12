package com.impeesa.finance.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.impeesa.finance.core.model.security.LockPolicy
import com.impeesa.finance.core.model.security.PinHash
import com.impeesa.finance.core.model.security.PinHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsBeforeWait: Int) : PinResult
    data class LockedOut(val untilMillis: Long) : PinResult
}

/**
 * The app lock (IMPLEMENTATION_PLAN.md §12.4): a UI gate over the whole app, including notification taps.
 * The PIN is stored only as a salted PBKDF2 hash. It does not encrypt the database (§12.3), so capture
 * keeps working while locked.
 */
class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.contains(KEY_HASH))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Locked from a cold start whenever a PIN is set. */
    private val _locked = MutableStateFlow(_enabled.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _biometric = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC, true))
    val biometric: StateFlow<Boolean> = _biometric.asStateFlow()

    private val _timeout = MutableStateFlow(prefs.getInt(KEY_TIMEOUT, 60))
    val timeoutSeconds: StateFlow<Int> = _timeout.asStateFlow()

    private val _hideInRecents = MutableStateFlow(prefs.getBoolean(KEY_RECENTS, true))
    val hideInRecents: StateFlow<Boolean> = _hideInRecents.asStateFlow()

    private val _hideBalances = MutableStateFlow(prefs.getBoolean(KEY_HIDE_BALANCES, false))
    val hideBalances: StateFlow<Boolean> = _hideBalances.asStateFlow()

    private var backgroundedAt: Long? = null

    fun setPin(pin: String) {
        val hash = PinHasher.hash(pin)
        prefs.edit()
            .putString(KEY_HASH, b64(hash.hash)).putString(KEY_SALT, b64(hash.salt)).putInt(KEY_ITERATIONS, hash.iterations).putInt(KEY_LENGTH, pin.length)
            .putInt(KEY_FAILURES, 0).remove(KEY_LOCKOUT)
            .apply()
        _enabled.value = true
    }

    fun disable() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_ITERATIONS).remove(KEY_LENGTH).remove(KEY_FAILURES).remove(KEY_LOCKOUT).apply()
        _enabled.value = false
        _locked.value = false
    }

    fun verify(pin: String, now: Long = System.currentTimeMillis()): PinResult {
        val until = prefs.getLong(KEY_LOCKOUT, 0L)
        if (until > now) return PinResult.LockedOut(until)
        val stored = storedHash() ?: return PinResult.Ok
        if (PinHasher.verify(pin, stored)) {
            prefs.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCKOUT).apply()
            unlock()
            return PinResult.Ok
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val wait = LockPolicy.lockoutMillis(failures)
        prefs.edit().putInt(KEY_FAILURES, failures).apply {
            if (wait > 0) putLong(KEY_LOCKOUT, now + wait)
        }.apply()
        return if (wait > 0) PinResult.LockedOut(now + wait) else PinResult.Wrong(LockPolicy.FREE_ATTEMPTS - failures)
    }

    /** After the fingerprint or device credential succeeds. */
    fun unlock() {
        prefs.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCKOUT).apply()
        _locked.value = false
    }

    fun lockoutUntil(): Long = prefs.getLong(KEY_LOCKOUT, 0L)

    /** How many digits the PIN has, so the lock screen can check as soon as the last one is typed. */
    fun pinLength(): Int = prefs.getInt(KEY_LENGTH, 4)

    fun onBackground(now: Long) {
        backgroundedAt = now
    }

    fun onForeground(now: Long) {
        if (_locked.value) return
        if (backgroundedAt != null && LockPolicy.shouldLock(_enabled.value, backgroundedAt, now, _timeout.value)) _locked.value = true
        backgroundedAt = null
    }

    fun setBiometric(on: Boolean) = prefs.edit().putBoolean(KEY_BIOMETRIC, on).apply().also { _biometric.value = on }
    fun setTimeout(seconds: Int) = prefs.edit().putInt(KEY_TIMEOUT, seconds).apply().also { _timeout.value = seconds }
    fun setHideInRecents(on: Boolean) = prefs.edit().putBoolean(KEY_RECENTS, on).apply().also { _hideInRecents.value = on }
    fun setHideBalances(on: Boolean) = prefs.edit().putBoolean(KEY_HIDE_BALANCES, on).apply().also { _hideBalances.value = on }

    private fun storedHash(): PinHash? {
        val hash = prefs.getString(KEY_HASH, null) ?: return null
        val salt = prefs.getString(KEY_SALT, null) ?: return null
        return PinHash(unb64(salt), unb64(hash), prefs.getInt(KEY_ITERATIONS, PinHasher.DEFAULT_ITERATIONS))
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        const val PREFS = "security"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_LENGTH = "pin_length"
        private const val KEY_FAILURES = "pin_failures"
        private const val KEY_LOCKOUT = "pin_lockout_until"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_TIMEOUT = "lock_after_seconds"
        private const val KEY_RECENTS = "hide_in_recents"
        private const val KEY_HIDE_BALANCES = "hide_balances"
    }
}

/** Fingerprint/face and device screen lock, through the system prompt. */
object SystemAuth {
    fun biometricAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Whether the phone has a screen lock (PIN, pattern, password) to fall back on. */
    fun deviceCredentialAvailable(context: Context): Boolean {
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        return BiometricManager.from(context).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the system prompt. [allowDeviceCredential] lets the phone's own PIN/pattern unlock too
     * ("Forgot PIN", delete all data).
     */
    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, allowDeviceCredential: Boolean, onSuccess: () -> Unit, onFail: (String?) -> Unit = {}) {
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
        if (allowDeviceCredential) {
            builder.setAllowedAuthenticators(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        } else {
            builder.setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText("Use PIN")
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
            },
        )
        prompt.authenticate(builder.build())
    }
}

/**
 * Keeps the weekly-backup passphrase on the phone, encrypted with a Keystore key, so the backup can run
 * in the background. Deleted with "Delete all data" or when weekly backup is turned off.
 */
class PassphraseVault(context: Context) {
    private val file = File(context.noBackupFilesDir, "backup.pass.enc")

    fun store(passphrase: CharArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = String(passphrase).toByteArray(Charsets.UTF_8)
        file.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain))
        plain.fill(0)
    }

    fun read(): CharArray? {
        if (!file.exists()) return null
        return runCatching {
            val stored = file.readBytes()
            val ivLength = stored[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(1, 1 + ivLength)))
            String(cipher.doFinal(stored, 1 + ivLength, stored.size - 1 - ivLength), Charsets.UTF_8).toCharArray()
        }.getOrNull()
    }

    fun clear() {
        file.delete()
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.let { if (it.containsAlias(ALIAS)) it.deleteEntry(ALIAS) }
    }

    fun exists(): Boolean = file.exists()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "impeesa_backup_passphrase_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
