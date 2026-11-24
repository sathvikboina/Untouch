package com.untouch.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>()
    data class Error(val reason: String) : StorageResult<Nothing>()
}

data class UntouchConfig(
    val isOnboardingComplete: Boolean,
    val blockedPackages: Set<String>,
    val isStrictModeActive: Boolean,
    val strictModeStartTimeMillis: Long,
    val blockedAttemptCount: Int
)

/**
 * Process-wide encrypted settings store.
 *
 * A single instance is shared by the UI, services and receivers so the
 * Keystore key and the decrypted config are cached in memory. Without both
 * caches the Accessibility Service would perform several Keystore operations
 * on every window change.
 */
class SecureStorage private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Immutable snapshot; invalidated/updated on every write. Guarded by [synchronized]. */
    private var cachedConfig: UntouchConfig? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(KEY_LOCK) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            val key = existing ?: run {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
            cachedKey = key
            return key
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): StorageResult<String> {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_SIZE) {
                return StorageResult.Error("Corrupted encrypted entry (invalid length).")
            }
            val iv = combined.copyOfRange(0, IV_SIZE)
            val ciphertext = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            StorageResult.Success(String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            StorageResult.Error("Encrypted vault unreadable: ${e.javaClass.simpleName}")
        }
    }

    private fun readString(key: String): StorageResult<String?> {
        val raw = prefs.getString(key, null) ?: return StorageResult.Success(null)
        return decrypt(raw)
    }

    private fun writeString(key: String, value: String): StorageResult<Unit> {
        return try {
            prefs.edit().putString(key, encrypt(value)).apply()
            StorageResult.Success(Unit)
        } catch (e: Exception) {
            StorageResult.Error("Failed to write encrypted vault: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun readConfig(): StorageResult<UntouchConfig> {
        cachedConfig?.let { return StorageResult.Success(it) }

        val onboarding = when (val r = readString(KEY_ONBOARDING)) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value == "true"
        }
        val blocked = when (val r = readString(KEY_BLOCKED_PACKAGES)) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }
        val strict = when (val r = readString(KEY_STRICT_MODE)) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value == "true"
        }
        val strictStart = when (val r = readString(KEY_STRICT_START)) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value?.toLongOrNull() ?: 0L
        }
        val attempts = when (val r = readString(KEY_BLOCKED_COUNT)) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value?.toIntOrNull() ?: 0
        }
        val config = UntouchConfig(
            isOnboardingComplete = onboarding,
            blockedPackages = blocked,
            isStrictModeActive = strict,
            strictModeStartTimeMillis = strictStart,
            blockedAttemptCount = attempts
        )
        cachedConfig = config
        return StorageResult.Success(config)
    }

    @Synchronized
    fun setOnboardingComplete(complete: Boolean): StorageResult<Unit> {
        val result = writeString(KEY_ONBOARDING, complete.toString())
        if (result is StorageResult.Success) {
            cachedConfig = cachedConfig?.copy(isOnboardingComplete = complete)
        }
        return result
    }

    @Synchronized
    fun setBlockedPackages(packages: Set<String>): StorageResult<Unit> {
        val result = writeString(KEY_BLOCKED_PACKAGES, packages.joinToString("\n"))
        if (result is StorageResult.Success) {
            cachedConfig = cachedConfig?.copy(blockedPackages = packages)
        }
        return result
    }

    @Synchronized
    fun setStrictModeActive(active: Boolean): StorageResult<Unit> {
        val r1 = writeString(KEY_STRICT_MODE, active.toString())
        if (r1 is StorageResult.Error) return r1
        val startMillis = if (active) System.currentTimeMillis() else 0L
        val r2 = writeString(KEY_STRICT_START, startMillis.toString())
        if (r2 is StorageResult.Error) return r2
        cachedConfig = cachedConfig?.copy(
            isStrictModeActive = active,
            strictModeStartTimeMillis = startMillis
        )
        return StorageResult.Success(Unit)
    }

    @Synchronized
    fun incrementBlockedAttempts(): StorageResult<Unit> {
        val current = cachedConfig?.blockedAttemptCount ?: when (val r = readString(KEY_BLOCKED_COUNT)) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value?.toIntOrNull() ?: 0
        }
        val next = current + 1
        val result = writeString(KEY_BLOCKED_COUNT, next.toString())
        if (result is StorageResult.Success) {
            cachedConfig = cachedConfig?.copy(blockedAttemptCount = next)
        }
        return result
    }

    fun readPinHash(): StorageResult<String?> = readString(KEY_PIN_HASH)
    fun readPinSalt(): StorageResult<String?> = readString(KEY_PIN_SALT)

    fun savePinCredentials(hashB64: String, saltB64: String): StorageResult<Unit> {
        val r1 = writeString(KEY_PIN_SALT, saltB64)
        if (r1 is StorageResult.Error) return r1
        val r2 = writeString(KEY_PIN_HASH, hashB64)
        if (r2 is StorageResult.Error) return r2
        val r3 = writeString(KEY_FAILED_ATTEMPTS, "0")
        if (r3 is StorageResult.Error) return r3
        return writeString(KEY_LOCKOUT_UNTIL, "0")
    }

    fun readFailedPinAttempts(): StorageResult<Int> {
        return when (val r = readString(KEY_FAILED_ATTEMPTS)) {
            is StorageResult.Error -> r
            is StorageResult.Success -> StorageResult.Success(r.value?.toIntOrNull() ?: 0)
        }
    }

    fun writeFailedPinAttempts(count: Int): StorageResult<Unit> =
        writeString(KEY_FAILED_ATTEMPTS, count.toString())

    fun readLockoutUntilMillis(): StorageResult<Long> {
        return when (val r = readString(KEY_LOCKOUT_UNTIL)) {
            is StorageResult.Error -> r
            is StorageResult.Success -> StorageResult.Success(r.value?.toLongOrNull() ?: 0L)
        }
    }

    fun writeLockoutUntilMillis(untilMillis: Long): StorageResult<Unit> =
        writeString(KEY_LOCKOUT_UNTIL, untilMillis.toString())

    @Synchronized
    fun resetCorruptedVault() {
        prefs.edit().clear().commit()
        cachedConfig = null
        synchronized(KEY_LOCK) {
            // Cached key belongs to the deleted Keystore entry; drop it so the
            // next write generates a fresh key.
            cachedKey = null
            try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    keyStore.deleteEntry(KEY_ALIAS)
                }
            } catch (_: Exception) {
                // Keystore entry already absent or inaccessible
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "untouch_vault"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "untouch_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        private const val KEY_ONBOARDING = "k_onboarding"
        private const val KEY_BLOCKED_PACKAGES = "k_blocked_pkgs"
        private const val KEY_STRICT_MODE = "k_strict_mode"
        private const val KEY_STRICT_START = "k_strict_start"
        private const val KEY_PIN_HASH = "k_pin_hash"
        private const val KEY_PIN_SALT = "k_pin_salt"
        private const val KEY_FAILED_ATTEMPTS = "k_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "k_lockout_until"
        private const val KEY_BLOCKED_COUNT = "k_blocked_count"

        private val KEY_LOCK = Any()

        @Volatile
        private var cachedKey: SecretKey? = null

        @Volatile
        private var instance: SecureStorage? = null

        fun get(context: Context): SecureStorage {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                return SecureStorage(context.applicationContext).also { instance = it }
            }
        }
    }
}
