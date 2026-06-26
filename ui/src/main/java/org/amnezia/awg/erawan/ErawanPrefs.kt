/*
 * Local storage for the Erawan one-tap connect device identity.
 */
package org.amnezia.awg.erawan

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class ErawanPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    var appToken: String?
        get() = prefs.getString(KEY_APP_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_APP_TOKEN, value).apply()
        }

    fun isRegistered(): Boolean = !appToken.isNullOrEmpty()

    /** Null means "Auto" — no specific server pinned, backend auto-picks on /app/connect. */
    val selectedServerId: Int?
        get() = prefs.getInt(KEY_SELECTED_SERVER_ID, -1).takeIf { it != -1 }

    val selectedServerName: String?
        get() = prefs.getString(KEY_SELECTED_SERVER_NAME, null)

    fun setSelectedServer(id: Int?, name: String?) {
        prefs.edit().apply {
            if (id == null) {
                remove(KEY_SELECTED_SERVER_ID)
                remove(KEY_SELECTED_SERVER_NAME)
            } else {
                putInt(KEY_SELECTED_SERVER_ID, id)
                putString(KEY_SELECTED_SERVER_NAME, name)
            }
        }.apply()
    }

    // False when never recorded (e.g. a tunnel that predates this tracking), so callers
    // safely fall back to refetching via /app/connect instead of trusting a stale config.
    fun matchesCachedConfig(serverId: Int?): Boolean {
        if (!prefs.contains(KEY_CONFIG_SERVER_ID)) return false
        val stored = prefs.getInt(KEY_CONFIG_SERVER_ID, AUTO_SENTINEL).takeIf { it != AUTO_SENTINEL }
        return stored == serverId
    }

    fun rememberConfigServerId(serverId: Int?) {
        prefs.edit().putInt(KEY_CONFIG_SERVER_ID, serverId ?: AUTO_SENTINEL).apply()
    }

    var tier: String
        get() = prefs.getString(KEY_TIER, "free") ?: "free"
        set(value) { prefs.edit().putString(KEY_TIER, value).apply() }

    var premiumExpiresAt: String?
        get() = prefs.getString(KEY_PREMIUM_EXPIRES_AT, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_PREMIUM_EXPIRES_AT).apply()
            else prefs.edit().putString(KEY_PREMIUM_EXPIRES_AT, value).apply()
        }

    fun isPremium(): Boolean = tier == "paid" || tier == "vip"

    companion object {
        private const val PREFS_NAME = "erawan_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_APP_TOKEN = "app_token"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_SELECTED_SERVER_NAME = "selected_server_name"
        private const val KEY_CONFIG_SERVER_ID = "config_server_id"
        private const val KEY_TIER = "tier"
        private const val KEY_PREMIUM_EXPIRES_AT = "premium_expires_at"
        private const val AUTO_SENTINEL = -1
    }
}
