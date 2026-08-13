package com.zoya.ai.assistant.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage backed by Android Keystore. Provides:
 *   - AES-GCM encryption for SharedPreferences values
 *   - Biometric authentication gate for sensitive operations
 *   - Transparent encrypt/decrypt API
 *
 * Sensitive data (credentials, API keys, tokens) must be stored via this module.
 * Never store secrets in plaintext.
 */
class SecureStorage(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("zoya_secure", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureKeyExists()
    }

    /**
     * Retrieves the encrypted key from the keystore or generates a new one.
     */
    private val secretKey: SecretKey
        get() {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? SecretKeyEntry
            return entry?.secretKey ?: generateKey()
        }

    private fun ensureKeyExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts a plaintext string and stores it under [key].
     * Format: base64(iv) + ":" + base64(ciphertext)
     */
    fun encrypt(key: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        val ctB64 = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
        prefs.edit().putString(key, "$ivB64:$ctB64").apply()
    }

    /**
     * Decrypts a value stored under [key]. Returns null if not found.
     */
    fun decrypt(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        val parts = stored.split(":")
        if (parts.size != 2) return null
        val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val ciphertext = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(IV_SIZE_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Removes a stored encrypted value.
     */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Returns a stable AES key for cloud-sync payload encryption. The key is
     * generated once, stored encrypted via the Keystore-wrapped master key,
     * and returned as raw material for the sync cipher.
     */
    fun syncKey(): javax.crypto.SecretKey {
        decrypt(SYNC_KEY_ALIAS)?.let { stored ->
            return javax.crypto.spec.SecretKeySpec(
                android.util.Base64.decode(stored, android.util.Base64.NO_WRAP),
                "AES"
            )
        }
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        encrypt(SYNC_KEY_ALIAS, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }

    /**
     * Checks whether biometric authentication is available and enrolled.
     */
    fun canUseBiometric(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows biometric prompt and calls [onAuthenticated] on success.
     * [onCancelled] called when user cancels.
     * [onError] called on error.
     */
    fun authenticateWithBiometric(
        activity: FragmentActivity,
        title: String = "Authenticate to access secure settings",
        subtitle: String = "Use your fingerprint, face, or device PIN",
        onAuthenticated: () -> Unit,
        onCancelled: () -> Unit = {},
        onError: (code: Int, message: CharSequence) -> Unit = { _, _ -> }
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        onCancelled()
                    } else {
                        onError(errorCode, errString)
                    }
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setNegativeButtonText("Cancel")
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zoya_secure_key"
        private const val SYNC_KEY_ALIAS = "zoya_sync_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE_BITS = 12 * 8 // 96-bit IV for GCM
    }
}
