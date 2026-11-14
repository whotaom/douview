package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class AppReleaseInfo(
    val currentVersion: String,
    val latestVersion: String,
    val title: String,
    val changelog: String,
    val apkUrl: String?,
    val hasUpdate: Boolean,
    val releasePageUrl: String
)

object UpdateManager {

    private const val GITHUB_REPO_API = "https://api.github.com/repos/whotaom/douview/releases/latest"
    const val GITHUB_REPO_PAGE = "https://github.com/whotaom/douview/releases"

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.1"
        } catch (e: Exception) {
            "1.0.1"
        }
    }

    suspend fun checkUpdate(context: Context): Result<AppReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val currentVer = getCurrentVersion(context)
            val request = Request.Builder()
                .url(GITHUB_REPO_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DouView-Android-App")
                .get()
                .build()

            val (isSuccess, responseBody) = NetworkClient.okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful to (response.body?.string() ?: "")
            }

            if (isSuccess && responseBody.isNotBlank()) {
                val json = JSONObject(responseBody)
                val tagName = json.optString("tag_name", "").trim()
                val releaseTitle = json.optString("name", "新版本更新").ifBlank { "新版本 $tagName" }
                val body = json.optString("body", "包含多项性能提升、直播流稳定性优化与体验改善。")
                val htmlUrl = sanitizeHttpsUrl(json.optString("html_url", GITHUB_REPO_PAGE))

                var apkDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url")
                                .takeIf { it.startsWith("https://", ignoreCase = true) }
                            break
                        }
                    }
                }

                val cleanRemoteVer = tagName.removePrefix("v").removePrefix("V")
                val cleanCurrentVer = currentVer.removePrefix("v").removePrefix("V")
                val hasUpdate = isNewerVersion(cleanRemoteVer, cleanCurrentVer)

                Result.success(
                    AppReleaseInfo(
                        currentVersion = currentVer,
                        latestVersion = if (tagName.isNotBlank()) tagName else "v$currentVer",
                        title = releaseTitle,
                        changelog = body,
                        apkUrl = apkDownloadUrl ?: htmlUrl,
                        hasUpdate = hasUpdate,
                        releasePageUrl = htmlUrl
                    )
                )
            } else {
                Result.success(
                    AppReleaseInfo(
                        currentVersion = currentVer,
                        latestVersion = "v$currentVer",
                        title = "当前版本已是最新 (v$currentVer)",
                        changelog = "【v1.0.1 稳定版更新要点】\n1. 全屏播放极简高稳单层 PlayerView 架构，杜绝黑屏风险；\n2. 遵循 Android TV Overscan 安全区与全向 D-pad 焦点管理；\n3. 全链路 HTTPS 安全策略与网络稳定性优化。",
                        apkUrl = GITHUB_REPO_PAGE,
                        hasUpdate = false,
                        releasePageUrl = GITHUB_REPO_PAGE
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isNewerVersion(remote: String, current: String): Boolean {
        if (remote.isBlank()) return false
        val rParts = remote.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        if (rParts.isEmpty()) return false
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun sanitizeHttpsUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed else GITHUB_REPO_PAGE
    }

    /**
     * 零权限合规更新入口：通过系统浏览器打开官方 Release 发布页；
     * 若电视盒子系统精简了浏览器组件，自动降级复制发布页链接到剪贴板并提示用户。
     */
    fun openReleasePage(context: Context, url: String) {
        val safeUrl = sanitizeHttpsUrl(url)
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (browserIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(browserIntent)
                Toast.makeText(context, "正在前往官方发布页面...", Toast.LENGTH_SHORT).show()
            } else {
                copyReleaseUrlFallback(context, safeUrl)
            }
        } catch (_: Exception) {
            copyReleaseUrlFallback(context, safeUrl)
        }
    }

    private fun copyReleaseUrlFallback(context: Context, safeUrl: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("release_url", safeUrl))
            Toast.makeText(context, "设备未安装浏览器，已复制发布页地址：$safeUrl", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "请使用手机或电脑访问：$safeUrl", Toast.LENGTH_LONG).show()
        }
    }
}
