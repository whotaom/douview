package com.example.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
    private const val GITHUB_REPO_PAGE = "https://github.com/whotaom/douview/releases"

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    suspend fun checkUpdate(context: Context): Result<AppReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val currentVer = getCurrentVersion(context)
            val url = URL(GITHUB_REPO_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "DouView-Android-App")
            }

            if (connection.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val tagName = json.optString("tag_name", "").trim()
                val releaseTitle = json.optString("name", "新版本更新").ifBlank { "新版本 $tagName" }
                val body = json.optString("body", "包含多项性能提升、直播流稳定性优化与体验改善。")
                val htmlUrl = json.optString("html_url", GITHUB_REPO_PAGE)

                var apkDownloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", null)
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
                // 如果 GitHub 暂无 Release 或被限流，优雅降级返回已是最新或提供主页
                Result.success(
                    AppReleaseInfo(
                        currentVersion = currentVer,
                        latestVersion = "v$currentVer",
                        title = "当前版本已是最新",
                        changelog = "当前已是工业级稳定版本，暂无更高版本发布。",
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

    private fun isNewerVersion(remote: String, current: String): Boolean {
        if (remote.isBlank()) return false
        val rParts = remote.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * 调起系统下载服务下载 APK 并通知安装
     */
    fun startDownloadApk(context: Context, downloadUrl: String, fileName: String = "douview_update.apk") {
        try {
            val uri = Uri.parse(downloadUrl)
            if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
                val request = DownloadManager.Request(uri).apply {
                    setTitle("正在下载抖眸流控新版本")
                    setDescription(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    setMimeType("application/vnd.android.package-archive")
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                if (dm != null) {
                    val downloadId = dm.enqueue(request)
                    Toast.makeText(context, "已开始在后台下载更新包，下载完成后将提示安装", Toast.LENGTH_LONG).show()

                    // 注册广播监听下载完成事件，自动拉起安装器
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(recvContext: Context?, intent: Intent?) {
                            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                                if (id == downloadId) {
                                    try {
                                        installDownloadedApk(context, fileName)
                                        context.unregisterReceiver(this)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            receiver,
                            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                            Context.RECEIVER_EXPORTED
                        )
                    } else {
                        context.registerReceiver(
                            receiver,
                            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                        )
                    }
                    return
                }
            }

            // 备用机制：调起系统默认浏览器打开下载或Release主页
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            Toast.makeText(context, "正在前往下载页面...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // 兜底方案：调起浏览器访问主页
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_PAGE)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            } catch (err: Exception) {
                Toast.makeText(context, "无法拉起下载: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installDownloadedApk(context: Context, fileName: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (file.exists()) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    } else {
                        Uri.fromFile(file)
                    }
                    setDataAndType(uri, "application/vnd.android.package-archive")
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
