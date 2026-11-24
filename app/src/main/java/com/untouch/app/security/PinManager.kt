package com.untouch.app.security

import android.util.Base64
import com.untouch.app.data.SecureStorage
import com.untouch.app.data.StorageResult
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed class PinVerificationResult {
    data object Success : PinVerificationResult()
    data object NotConfigured : PinVerificationResult()
    data class Invalid(val remainingBeforeLockout: Int) : PinVerificationResult()
    data class LockedOut(val remainingSeconds: Long) : PinVerificationResult()
    data class StorageFailure(val reason: String) : PinVerificationResult()
}

class PinManager(private val storage: SecureStorage) {

    fun isPinConfigured(): StorageResult<Boolean> {
        val hash = when (val r = storage.readPinHash()) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value
        }
        val salt = when (val r = storage.readPinSalt()) {
            is StorageResult.Error -> return r
            is StorageResult.Success -> r.value
        }
        return StorageResult.Success(!hash.isNullOrBlank() && !salt.isNullOrBlank())
    }

    fun setPin(pin: String): StorageResult<Unit> {
        require(pin.length >= MIN_PIN_LENGTH) { "PIN must be at least $MIN_PIN_LENGTH digits." }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = deriveHash(pin, salt)
        return storage.savePinCredentials(
            hashB64 = Base64.encodeToString(hash, Base64.NO_WRAP),
            saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        )
    }

    fun getRemainingLockoutSeconds(): StorageResult<Long> {
        return when (val r = storage.readLockoutUntilMillis()) {
            is StorageResult.Error -> r
            is StorageResult.Success -> {
                val now = System.currentTimeMillis()
                val rem = if (r.value > now) (r.value - now + 999) / 1000 else 0L
                StorageResult.Success(rem)
            }
        }
    }

    fun verifyPin(inputPin: String): PinVerificationResult {
        val lockoutRemaining = when (val r = getRemainingLockoutSeconds()) {
            is StorageResult.Error -> return PinVerificationResult.StorageFailure(r.reason)
            is StorageResult.Success -> r.value
        }
        if (lockoutRemaining > 0) {
            return PinVerificationResult.LockedOut(lockoutRemaining)
        }

        val saltB64 = when (val r = storage.readPinSalt()) {
            is StorageResult.Error -> return PinVerificationResult.StorageFailure(r.reason)
            is StorageResult.Success -> r.value ?: return PinVerificationResult.NotConfigured
        }
        val hashB64 = when (val r = storage.readPinHash()) {
            is StorageResult.Error -> return PinVerificationResult.StorageFailure(r.reason)
            is StorageResult.Success -> r.value ?: return PinVerificationResult.NotConfigured
        }

        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
        val actualHash = deriveHash(inputPin, salt)

        return if (MessageDigest.isEqual(expectedHash, actualHash)) {
            storage.writeFailedPinAttempts(0)
            storage.writeLockoutUntilMillis(0L)
            PinVerificationResult.Success
        } else {
            val prevAttempts = when (val r = storage.readFailedPinAttempts()) {
                is StorageResult.Error -> return PinVerificationResult.StorageFailure(r.reason)
                is StorageResult.Success -> r.value
            }
            val attempts = prevAttempts + 1
            storage.writeFailedPinAttempts(attempts)

            val lockoutDurationMs = when {
                attempts >= 10 -> 30L * 60L * 1000L
                attempts >= 7 -> 10L * 60L * 1000L
                attempts >= 5 -> 2L * 60L * 1000L
                attempts >= 3 -> 30L * 1000L
                else -> 0L
            }

            if (lockoutDurationMs > 0L) {
                storage.writeLockoutUntilMillis(System.currentTimeMillis() + lockoutDurationMs)
                PinVerificationResult.LockedOut(lockoutDurationMs / 1000)
            } else {
                PinVerificationResult.Invalid(3 - attempts)
            }
        }
    }

    private fun deriveHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    companion object {
        const val MIN_PIN_LENGTH = 6
        private const val SALT_BYTES = 16
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH_BITS = 256
    }
}
