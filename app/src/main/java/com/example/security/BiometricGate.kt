package com.example.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SECURITY (Agent #14) — item 6: biometric authentication for sensitive operations.
 *
 * The point of using a CryptoObject (rather than a plain prompt) is that the prompt
 * is cryptographically bound to a Keystore key that requires user authentication.
 * A plain BiometricPrompt result can be spoofed by a hooked client; a successful
 * doFinal() on `setUserAuthenticationRequired(true)` keys cannot.
 *
 * Gated operations: item deletion, and any future export/share of wardrobe data.
 */
class BiometricGate(private val activity: FragmentActivity) {

    private val keystore: KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true) // re-enrolment revokes the key
                .build()
        )
        return generator.generateKey()
    }

    /** true when the device can actually satisfy the gate (biometric or credential). */
    fun isAvailable(): Boolean =
        BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Runs [onSuccess] only after a hardware-backed biometric/credential check.
     * [onUnavailable] lets the caller decide whether to hard-fail the operation.
     */
    fun authenticate(
        reason: String,
        onSuccess: () -> Unit,
        onUnavailable: () -> Unit = {}
    ) {
        if (!isAvailable()) {
            onUnavailable()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Only trust the callback when the crypto object actually came back.
                    if (result.cryptoObject?.cipher != null) onSuccess() else onUnavailable()
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) = onUnavailable()

                override fun onAuthenticationFailed() {
                    // Individual rejection: BiometricPrompt reports the terminal
                    // outcome through onAuthenticationError, so nothing to do here.
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Confirm it's you")
                .setSubtitle(reason)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "styledrop_sensitive_ops_v1"
    }
}
