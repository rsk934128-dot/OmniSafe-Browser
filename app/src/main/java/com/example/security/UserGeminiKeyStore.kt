package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stores an optional user-provided Gemini API key encrypted with Android Keystore AES-256-GCM
 * so installed APKs can run AI features even when Firebase App Check attestation is unavailable
 * outside the AI Studio Preview harness.
 */
object UserGeminiKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "onionshield_user_gemini_key_v1"
    private const val PREFS_NAME = "onionshield_user_gemini_prefs"
    private const val PREF_ENCRYPTED_KEY = "encrypted_api_key_v1"
    private const val PREF_PREFER_PERSONAL = "prefer_personal_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private val secureRandom = SecureRandom()

    private val _hasPersonalKeyFlow = MutableStateFlow(false)
    val hasPersonalKeyFlow: StateFlow<Boolean> = _hasPersonalKeyFlow.asStateFlow()

    private val _preferPersonalKeyFlow = MutableStateFlow(true)
    val preferPersonalKeyFlow: StateFlow<Boolean> = _preferPersonalKeyFlow.asStateFlow()

    fun syncFromPrefs(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasKey = !getApiKey(context).isNullOrBlank()
        val preferPersonal = prefs.getBoolean(PREF_PREFER_PERSONAL, true)
        _hasPersonalKeyFlow.value = hasKey
        _preferPersonalKeyFlow.value = preferPersonal
    }

    fun hasApiKey(context: Context): Boolean = !getApiKey(context).isNullOrBlank()

    fun isPersonalKeyPreferred(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_PREFER_PERSONAL, true).also {
            _preferPersonalKeyFlow.value = it
        }
    }

    fun setPersonalKeyPreferred(context: Context, preferred: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_PREFER_PERSONAL, preferred).commit()
        _preferPersonalKeyFlow.value = preferred
    }

    fun saveApiKey(context: Context, rawKey: String, preferPersonal: Boolean = true): Boolean {
        val clean = rawKey.trim()
        if (clean.isBlank()) return false
        val appCtx = context.applicationContext
        val cipherKey = getOrCreateCipherKey(appCtx)
        val encryptedEnvelope = encryptSecret(clean, cipherKey)
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.edit()
            .putString(PREF_ENCRYPTED_KEY, encryptedEnvelope)
            .putBoolean(PREF_PREFER_PERSONAL, preferPersonal)
            .commit()
        if (saved) {
            _hasPersonalKeyFlow.value = true
            _preferPersonalKeyFlow.value = preferPersonal
        }
        return saved
    }

    fun getApiKey(context: Context): String? {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val envelope = prefs.getString(PREF_ENCRYPTED_KEY, null) ?: return null
        if (envelope.isBlank()) return null
        return runCatching {
            val cipherKey = getOrCreateCipherKey(appCtx)
            decryptSecret(envelope, cipherKey).trim().takeIf { it.isNotEmpty() }
        }.onFailure {
            // Clear unreadable ciphertext (e.g. after backup restore to another Keystore device)
            prefs.edit().remove(PREF_ENCRYPTED_KEY).commit()
            _hasPersonalKeyFlow.value = false
        }.getOrNull()
    }

    fun clearApiKey(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_ENCRYPTED_KEY).commit()
        _hasPersonalKeyFlow.value = false
    }

    private fun getOrCreateCipherKey(context: Context): SecretKey {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGen.init(spec)
            return keyGen.generateKey()
        }

        val fallbackSeed = "${context.packageName}:user_gemini_key_v1".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(fallbackSeed)
        return SecretKeySpec(digest, "AES")
    }

    private fun encryptSecret(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv: ByteArray
        if (key is SecretKeySpec) {
            iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            iv = cipher.iv
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "uk1:$ivB64:$ctB64"
    }

    private fun decryptSecret(envelope: String, key: SecretKey): String {
        val parts = envelope.split(':')
        require(parts.size == 3 && parts[0] == "uk1") { "Invalid key envelope" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ct = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
