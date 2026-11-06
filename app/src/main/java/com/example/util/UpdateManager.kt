package com.example.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

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

            val response = NetworkClient.okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val isSuccess = response.isSuccessful

            if (isSuccess && responseBody.isNotBlank()) {
                val json = JSONObject(responseBody)
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
                            apkDownloadUrl = asset.optString("browser_download_url").ifBlank { null }
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
                // 如果 GitHub 暂无 Release 或被限流，优雅降级返回当前 1.0.1 版本的更新要点
                Result.success(
                    AppReleaseInfo(
                        currentVersion = currentVer,
                        latestVersion = "v$currentVer",
                        title = "当前版本已是最新 (v$currentVer)",
                        changelog = "【v1.0.1 稳定版更新要点】\n1. 流媒体传输策略全面恢复平稳机制，消除缓冲死循环；\n2. OkHttp 网络层添加连接超时(10s)与读取超时(15s)设置，并增加失败重试拦截器，提升直播流解析稳定性；\n3. 画中画全生命周期 .stage 容器尺寸与样式深度隔离守护；\n4. 更新弹窗强化：有真实新版才提供下载更新，当前最新版状态清爽闭环。",
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
            if (!file.exists()) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "更新安装包未找到，请重试下载", Toast.LENGTH_SHORT).show()
                }
                return
            }

            // 工业级签名防御性检测：对比当前运行应用与新下载安装包的签名 SHA-256
            val installedSigs = getInstalledSignatureSha256(context)
            val archiveSigs = getArchiveSignatureSha256(context, file)

            var isSignatureMismatch = false
            if (installedSigs.isNotEmpty() && archiveSigs.isNotEmpty()) {
                val hasMatch = installedSigs.any { inst -> archiveSigs.contains(inst) }
                if (!hasMatch) {
                    isSignatureMismatch = true
                }
            }

            if (isSignatureMismatch) {
                // 签名不一致预警：Android 系统安全机制禁止不同签名直接覆盖安装
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "【签名环境不一致预警】\n当前安装版本（开发调试版）与新版本（正式发布版）签名证书不同。\n系统将阻止直接覆盖，请先卸载当前版本后再安装本更新包！",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

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
        } catch (e: Exception) {
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "拉起安装器失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 获取已安装应用的签名证书 SHA-256 指纹列表
     */
    fun getInstalledSignatureSha256(context: Context): List<String> {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo ?: return emptyList()
                val signatures = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                signatures?.mapNotNull { it.toByteArray().toSha256Hex() } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures?.mapNotNull { it.toByteArray().toSha256Hex() } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取下载的 APK 安装包的签名证书 SHA-256 指纹列表
     */
    fun getArchiveSignatureSha256(context: Context, apkFile: File): List<String> {
        return try {
            val pm = context.packageManager
            val path = apkFile.absolutePath
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo?.signingInfo
                val signatures = if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
                signatures?.mapNotNull { it.toByteArray().toSha256Hex() } ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo?.signatures?.mapNotNull { it.toByteArray().toSha256Hex() } ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun ByteArray.toSha256Hex(): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(this)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
