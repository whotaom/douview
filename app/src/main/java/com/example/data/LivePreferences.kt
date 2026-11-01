package com.example.data

import android.content.Context
import android.content.SharedPreferences

class LivePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("douyin_live_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_URL = "https://tv.fuxiaoya.com/"
        const val API_ENDPOINT_URL = "https://tv.fuxiaoya.com/live.php"
        private const val KEY_TARGET_URL = "target_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_DESKTOP_UA = "desktop_ua"
        private const val KEY_FORCE_LANDSCAPE = "force_landscape"
    }

    var targetUrl: String
        get() = prefs.getString(KEY_TARGET_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_TARGET_URL, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var isDesktopUa: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_UA, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_UA, value).apply()

    var forceLandscape: Boolean
        get() = prefs.getBoolean(KEY_FORCE_LANDSCAPE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_LANDSCAPE, value).apply()
}
