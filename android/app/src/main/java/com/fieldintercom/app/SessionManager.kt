package com.fieldintercom.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists just enough to survive an app restart without forcing a re-login,
 * while still enforcing the 8-hour session timeout from when the user
 * originally typed the password.
 */
class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("intercom_session", Context.MODE_PRIVATE)

    companion object {
        const val SESSION_DURATION_MS = 8L * 60 * 60 * 1000 // 8 hours
        private const val KEY_SERVER_ADDRESS = "server_address"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_LOGIN_TIME = "login_time"
    }

    var serverAddress: String
        get() = prefs.getString(KEY_SERVER_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_ADDRESS, value).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    fun markLoggedIn() {
        prefs.edit().putLong(KEY_LOGIN_TIME, System.currentTimeMillis()).apply()
    }

    fun isSessionValid(): Boolean {
        val loginTime = prefs.getLong(KEY_LOGIN_TIME, 0L)
        if (loginTime == 0L) return false
        return (System.currentTimeMillis() - loginTime) < SESSION_DURATION_MS
    }

    fun clearSession() {
        prefs.edit().remove(KEY_LOGIN_TIME).apply()
    }
}
