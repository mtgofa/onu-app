package com.hg8145v5.manager.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Creds(val ip: String, val user: String, val pass: String)

/**
 * Persistence for router credentials + app settings.
 *
 * Credentials are encrypted at rest with a fresh AES-GCM key held in the Android Keystore
 * (a direct javax.crypto implementation). Settings that aren't secrets (language, theme,
 * download cache, ...) live plain so reading them at app start touches *no crypto at all*.
 *
 * Why not EncryptedSharedPreferences? Its Tink bootstrap throws "Signature/MAC verification
 * failed" on some devices/ROMs when read from a locked device / after OTA or backup restore,
 * which crashed the app at the very first frame. Direct Keystore AES-GCM keys are gated by
 * the system and don't fail that way — and building the key is deferred until real use.
 */
class CredStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("onu_settings", Context.MODE_PRIVATE)

    /** Master alias whose AES-GCM key lives in the Android Keystore. */
    private val keyAlias = "onu_master_v2"

    private var key: SecretKey? = null

    @Synchronized
    private fun masterKey(): SecretKey {
        key?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(keyAlias)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            kg.generateKey()
        }
        key = ks.getKey(keyAlias, null) as SecretKey
        return key!!
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val i = blob.indexOf(':')
        if (i <= 0) throw IllegalArgumentException("bad blob")
        val iv = Base64.decode(blob.substring(0, i), Base64.NO_WRAP)
        val ct = Base64.decode(blob.substring(i + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun save(c: Creds) = runCatching {
        prefs.edit()
            .putString("ip", encrypt(c.ip))
            .putString("user", encrypt(c.user))
            .putString("pass", encrypt(c.pass))
            .putBoolean("saved", true)
            .apply()
    }

    fun clear() = prefs.edit()
        .remove("ip").remove("user").remove("pass").remove("saved")
        .putBoolean("fast_login", false)
        .apply()

    /** Returns saved credentials, or null when absent / locked / key invalidated. */
    fun load(): Creds? {
        if (!prefs.getBoolean("saved", false)) return null
        return runCatching {
            Creds(
                decrypt(prefs.getString("ip", "")!!),
                decrypt(prefs.getString("user", "")!!),
                decrypt(prefs.getString("pass", "")!!)
            )
        }.getOrNull()
    }

    // ---- plain settings (not secrets) ----
    var lang: String
        get() = prefs.getString("lang", "en")!!
        set(v) = prefs.edit().putString("lang", v).apply()

    var themeMode: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()

    var fastLogin: Boolean
        get() = prefs.getBoolean("fast_login", false)
        set(v) = prefs.edit().putBoolean("fast_login", v).apply()

    var speeds: String
        get() = prefs.getString("speeds", "")!!
        set(v) = prefs.edit().putString("speeds", v).apply()

    var deviceCache: String
        get() = prefs.getString("device_cache", "")!!
        set(v) = prefs.edit().putString("device_cache", v).apply()
}