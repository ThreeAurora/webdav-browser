package com.webdav.browser

import android.content.Context

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("s", Context.MODE_PRIVATE)
    var serverUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()
    var username: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v).apply()
    var password: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()
    var confirmDelete: Boolean
        get() = sp.getBoolean("cd", false)
        set(v) = sp.edit().putBoolean("cd", v).apply()
    var deleteButtonPos: String
        get() = sp.getString("dp", "bottom-right") ?: "bottom-right"
        set(v) = sp.edit().putString("dp", v).apply()
    var sortBy: String
        get() = sp.getString("sb", "name") ?: "name"
        set(v) = sp.edit().putString("sb", v).apply()
    var sortAsc: Boolean
        get() = sp.getBoolean("sa", true)
        set(v) = sp.edit().putBoolean("sa", v).apply()
    fun isConfigured() = serverUrl.isNotBlank()
}
