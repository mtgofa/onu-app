package com.hg8145v5.manager.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class Creds(val ip: String, val user: String, val pass: String)

/** Encrypted persistence for router credentials (Android Keystore-backed). */
class CredStore(context: Context) {
    private val prefs = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "hg8145v5_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(c: Creds) = prefs.edit()
        .putString("ip", c.ip).putString("user", c.user).putString("pass", c.pass)
        .putBoolean("saved", true).apply()

    fun clear() = prefs.edit()
        .remove("ip").remove("user").remove("pass").remove("saved")
        .putBoolean("fast_login", false)
        .apply()

    fun load(): Creds? {
        if (!prefs.getBoolean("saved", false)) return null
        return Creds(
            prefs.getString("ip", "192.168.100.1")!!,
            prefs.getString("user", "admin")!!,
            prefs.getString("pass", "")!!
        )
    }

    // language preference (not sensitive, kept here for simplicity)
    var lang: String
        get() = prefs.getString("lang", "en")!!
        set(v) = prefs.edit().putString("lang", v).apply()

    // theme: "system" | "light" | "dark"
    var themeMode: String
        get() = prefs.getString("theme", "system")!!
        set(v) = prefs.edit().putString("theme", v).apply()

    // fast one-tap login
    var fastLogin: Boolean
        get() = prefs.getBoolean("fast_login", false)
        set(v) = prefs.edit().putBoolean("fast_login", v).apply()

    // per-device speed limits, "MAC=label,MAC=label" — survives app restarts
    var speeds: String
        get() = prefs.getString("speeds", "")!!
        set(v) = prefs.edit().putString("speeds", v).apply()

    // last known good device list (JSON) — shown instantly on open, refreshed in background
    var deviceCache: String
        get() = prefs.getString("device_cache", "")!!
        set(v) = prefs.edit().putString("device_cache", v).apply()
}
