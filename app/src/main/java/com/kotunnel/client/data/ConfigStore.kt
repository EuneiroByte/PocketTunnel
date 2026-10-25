package com.kotunnel.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigStore @Inject constructor(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kotunnel_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var tunnelId: String
        get() = prefs.getString("tunnel_id", "").orEmpty()
        set(v) = prefs.edit().putString("tunnel_id", v.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.openai.com").orEmpty()
            .trimEnd('/')
        set(v) = prefs.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    val instanceId: String by lazy {
        var existing = prefs.getString("instance_id", null)
        if (existing.isNullOrBlank()) {
            existing = UUID.randomUUID().toString()
            prefs.edit().putString("instance_id", existing).apply()
        }
        existing
    }

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(v) = prefs.edit().putInt("theme_mode", v).apply()

    /** "builtin" / "http" / "feishu" */
    var mcpMode: String
        get() = prefs.getString("mcp_mode", "builtin").orEmpty().ifBlank { "builtin" }
        set(v) = prefs.edit().putString("mcp_mode", v).apply()

    var mcpServerUrl: String
        get() = prefs.getString("mcp_server_url", "").orEmpty().trim()
        set(v) = prefs.edit().putString("mcp_server_url", v.trim()).apply()

    // ---- Feishu (Lark) ----
    var feishuAppId: String
        get() = prefs.getString("feishu_app_id", "").orEmpty().trim()
        set(v) = prefs.edit().putString("feishu_app_id", v.trim()).apply()

    var feishuAppSecret: String
        get() = prefs.getString("feishu_app_secret", "").orEmpty().trim()
        set(v) = prefs.edit().putString("feishu_app_secret", v.trim()).apply()

    var feishuUserAccessToken: String
        get() = prefs.getString("feishu_user_access_token", "").orEmpty()
        set(v) = prefs.edit().putString("feishu_user_access_token", v).apply()

    var feishuTokenExpiry: Long
        get() = prefs.getLong("feishu_token_expiry", 0L)
        set(v) = prefs.edit().putLong("feishu_token_expiry", v).apply()
}
