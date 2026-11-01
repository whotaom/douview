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
        private const val KEY_FULLSCREEN_ORIENTATION = "fullscreen_orientation"
    }

    var targetUrl: String
        get() {
            val url = prefs.getString(KEY_TARGET_URL, DEFAULT_URL) ?: DEFAULT_URL
            // 向下兼容与平滑迁移：若此前曾选中 live.php 纯接口地址，自动回退至综合前端界面
            return if (url == API_ENDPOINT_URL) DEFAULT_URL else url
        }
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

    // 0: 智能适配 (竖屏直播竖全屏，横屏直播横全屏)
    // 1: 始终强制横屏
    // 2: 始终强制竖屏
    var fullscreenOrientationMode: Int
        get() = prefs.getInt(KEY_FULLSCREEN_ORIENTATION, 0)
        set(value) = prefs.edit().putInt(KEY_FULLSCREEN_ORIENTATION, value).apply()
}
