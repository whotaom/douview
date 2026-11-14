package com.example.data

import android.content.Context
import android.content.SharedPreferences

class LivePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("douyin_live_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_URL = "${StreamLines.LINE_1}/"
        const val API_ENDPOINT_URL = "${StreamLines.LINE_1}/live.php"
        private const val KEY_TARGET_URL = "target_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_DESKTOP_UA = "desktop_ua"
        private const val KEY_FORCE_LANDSCAPE = "force_landscape"
        private const val KEY_FULLSCREEN_ORIENTATION = "fullscreen_orientation"
        private const val KEY_HISTORY_URLS = "history_urls"
        private const val KEY_AUTO_CLEAR_CACHE = "auto_clear_cache"
        private const val KEY_LAST_CLEAR_CACHE_TIME = "last_clear_cache_time"
        const val MAX_HISTORY_URLS = 5
        const val AUTO_CLEAR_CACHE_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24小时自动清理周期

        fun sanitizeWebUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isBlank()) return DEFAULT_URL
            if (trimmed == API_ENDPOINT_URL) return DEFAULT_URL
            if (trimmed.contains("whomao.com", ignoreCase = true)) return DEFAULT_URL
            return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed else DEFAULT_URL
        }
    }

    var targetUrl: String
        get() = sanitizeWebUrl(prefs.getString(KEY_TARGET_URL, DEFAULT_URL))
        set(value) {
            val sanitized = sanitizeWebUrl(value)
            prefs.edit().putString(KEY_TARGET_URL, sanitized).apply()
            recordHistoryUrl(sanitized)
        }

    val activeBaseUrl: String
        get() = StreamLines.resolveBaseUrl(targetUrl)

    /**
     * 获取记忆的切换地址列表（限定最多 5 个，最近使用的排在最前）
     */
    fun getHistoryUrls(): List<String> {
        val raw = prefs.getString(KEY_HISTORY_URLS, null) ?: return emptyList()
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("https://", ignoreCase = true) && !it.contains("whomao.com", ignoreCase = true) }
            .take(MAX_HISTORY_URLS)
    }

    /**
     * 自动记忆新地址并限定最多五个（自动去重并置顶，仅保存安全 HTTPS 地址）
     */
    fun recordHistoryUrl(newUrl: String) {
        val clean = newUrl.trim()
        if (!clean.startsWith("https://", ignoreCase = true) || clean.contains("whomao.com", ignoreCase = true)) return
        val current = getHistoryUrls().toMutableList()
        current.remove(clean)
        current.add(0, clean)
        val trimmed = current.take(MAX_HISTORY_URLS)
        prefs.edit().putString(KEY_HISTORY_URLS, trimmed.joinToString("\n")).apply()
    }

    /**
     * 移除单条记忆的地址
     */
    fun removeHistoryUrl(urlToRemove: String) {
        val current = getHistoryUrls().toMutableList()
        current.remove(urlToRemove.trim())
        prefs.edit().putString(KEY_HISTORY_URLS, current.joinToString("\n")).apply()
    }

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

    var autoClearCacheEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_CACHE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_CACHE, value).apply()

    var lastClearCacheTime: Long
        get() = prefs.getLong(KEY_LAST_CLEAR_CACHE_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CLEAR_CACHE_TIME, value).apply()

    /**
     * 判定是否达到 24 小时定期自动清理缓存周期
     */
    fun shouldAutoClearCache(): Boolean {
        if (!autoClearCacheEnabled) return false
        val now = System.currentTimeMillis()
        return (now - lastClearCacheTime) >= AUTO_CLEAR_CACHE_INTERVAL_MS
    }

    /**
     * 记录本次完成缓存清理的时间戳
     */
    fun recordCacheCleared() {
        lastClearCacheTime = System.currentTimeMillis()
    }
}
