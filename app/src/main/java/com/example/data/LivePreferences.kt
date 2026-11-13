package com.example.data

import android.content.Context
import android.content.SharedPreferences

class LivePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("douyin_live_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_URL = "${StreamLines.LINE_1}/"
        const val LINE_TWO_URL = StreamLines.LINE_2
        const val API_ENDPOINT_URL = "${StreamLines.LINE_1}/live.php"
        private const val LEGACY_LINE_TWO_URL = "https://www.whomao.com"
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
    }

    var targetUrl: String
        get() {
            val url = prefs.getString(KEY_TARGET_URL, DEFAULT_URL) ?: DEFAULT_URL
            // 向下兼容与平滑迁移：旧版 live.php 或旧线路二域名自动迁移到新地址
            return when {
                url == API_ENDPOINT_URL -> DEFAULT_URL
                url.trimEnd('/').equals(LEGACY_LINE_TWO_URL, ignoreCase = true) -> LINE_TWO_URL
                else -> url
            }
        }
        set(value) {
            prefs.edit().putString(KEY_TARGET_URL, value).apply()
            recordHistoryUrl(value)
        }

    val currentLine: Int
        get() = StreamLines.detectLine(targetUrl)

    val activeBaseUrl: String
        get() = StreamLines.baseUrl(currentLine)

    /**
     * 获取记忆的切换地址列表（限定最多 5 个，最近使用的排在最前）
     */
    fun getHistoryUrls(): List<String> {
        val raw = prefs.getString(KEY_HISTORY_URLS, null) ?: return emptyList()
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_HISTORY_URLS)
    }

    /**
     * 自动记忆新地址并限定最多五个（自动去重并置顶）
     */
    fun recordHistoryUrl(newUrl: String) {
        val clean = newUrl.trim()
        if (clean.isBlank()) return
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
