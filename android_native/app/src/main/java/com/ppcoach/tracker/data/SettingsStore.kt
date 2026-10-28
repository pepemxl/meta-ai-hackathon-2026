package com.ppcoach.tracker.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}

data class Settings(
    val language: String = "", // "" = follow the phone the first time
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val storage: StorageKind = StorageKind.PHONE,
    val technical: Boolean = false,
    val chunkProfile: String = "dev",
    val overlapMs: Int = 2000,
    val highResolution: Boolean = false,
    val serverUrl: String = "",
    val sessionEmail: String = "",
    val sessionName: String = "",
    val loggedIn: Boolean = false,
    val consentVersion: String = "",
    val consentAt: String = "",
) {
    val profile: ChunkProfile get() = ChunkProfile.of(chunkProfile, overlapMs)
}

/**
 * Settings in SharedPreferences. The session JWT is stored ENCRYPTED with
 * SecretBox: preferences hold only the ciphertext.
 */
class SettingsStore(context: Context, private val defaultProfile: String) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val box = SecretBox("pp-coach-tracker-session")

    fun load(): Settings {
        val profile = prefs.getString("chunkProfile", null) ?: defaultProfile
        return Settings(
            language = prefs.getString("language", "") ?: "",
            theme = ThemeMode.fromKey(prefs.getString("theme", null)),
            storage = StorageKind.fromKey(prefs.getString("storage", null)),
            technical = prefs.getBoolean("technical", false),
            chunkProfile = profile,
            overlapMs = prefs.getInt("overlapMs", ChunkProfile.of(profile).overlapMs),
            highResolution = prefs.getBoolean("highResolution", false),
            serverUrl = prefs.getString("serverUrl", "") ?: "",
            sessionEmail = prefs.getString("sessionEmail", "") ?: "",
            sessionName = prefs.getString("sessionName", "") ?: "",
            loggedIn = prefs.contains("sessionToken"),
            consentVersion = prefs.getString("consentVersion", "") ?: "",
            consentAt = prefs.getString("consentAt", "") ?: "",
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("language", s.language)
            .putString("theme", s.theme.key)
            .putString("storage", s.storage.key)
            .putBoolean("technical", s.technical)
            .putString("chunkProfile", s.chunkProfile)
            .putInt("overlapMs", s.overlapMs)
            .putBoolean("highResolution", s.highResolution)
            .putString("serverUrl", s.serverUrl)
            .putString("consentVersion", s.consentVersion)
            .putString("consentAt", s.consentAt)
            .apply()
    }

    fun saveSession(session: Session) {
        prefs.edit()
            .putString("sessionToken", box.encrypt(session.token))
            .putString("sessionEmail", session.email)
            .putString("sessionName", session.name)
            .apply()
    }

    /** The JWT, decrypted when needed (never passed through WorkManager's input data). */
    fun token(): String? = prefs.getString("sessionToken", null)?.let { runCatching { box.decrypt(it) }.getOrNull() }

    fun clearSession() {
        prefs.edit().remove("sessionToken").remove("sessionEmail").remove("sessionName").apply()
    }
}

/** AES-256-GCM with a key that never leaves the Android Keystore. */
class SecretBox(private val alias: String) {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }
}
