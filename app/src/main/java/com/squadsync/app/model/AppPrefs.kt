package com.squadsync.app.model

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

object AppPrefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext
            .getSharedPreferences("squadsync_prefs", Context.MODE_PRIVATE)
        // One-time migrations:
        // 1) Empty relayUrl -> default hosted relay.
        // 2) Typo domain sync.he4966.cn -> correct sync.he66.cn.
        val current = sp.getString("relayUrl", null)
        if (current == null || current.isEmpty() || current == "wss://sync.he4966.cn") {
            sp.edit { putString("relayUrl", DEFAULT_RELAY_URL) }
        }
    }

    var roomCode: String
        get() = sp.getString("roomCode", "1234") ?: "1234"
        set(v) = sp.edit { putString("roomCode", v) }

    var deviceName: String
        get() = sp.getString("deviceName", defaultName()) ?: defaultName()
        set(v) = sp.edit { putString("deviceName", v) }

    var lastWsPort: Int
        get() = sp.getInt("wsPort", 7878)
        set(v) = sp.edit { putInt("wsPort", v) }

    /**
     * URL of the optional remote relay (e.g. "wss://relay.example.com"
     * or "http://1.2.3.4:7879"). Defaults to the hosted sync.he66.cn
     * relay so new users don't have to type anything. Empty string means
     * "LAN only".
     */
    var relayUrl: String
        get() = sp.getString("relayUrl", DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
        set(v) = sp.edit { putString("relayUrl", v) }

    private const val DEFAULT_RELAY_URL = "wss://sync.he66.cn"

    private fun defaultName(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Phone"
        val model = Build.MODEL ?: "Device"
        return "$manufacturer $model"
    }
}
