package com.example.tv.channel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 电视端频道管理器（与 tv_fuxiaoya_com 网页版完全一致）
 * 1. 内置官方默认频道列表（不写死 isLive，全部交由服务端测活确认）；
 * 2. 支持导出为 JSON / 纯房间号 TXT 文件；
 * 3. 支持本地导入（JSON / TXT / CSV）与远程导入（直接拉取或网页版安全代理）；
 * 4. 权威测活（与网页版 live.php?batch= 完全同源：只认 status=="live"，unknown 不强改离线）。
 */
class ChannelManager(private val context: Context) {

    companion object {
        private const val TAG = "Probe"
        private const val PREFS_NAME = "tv_channels_v1"
        private const val KEY_CHANNELS = "channels_json"
        private const val EXPORT_DIR_NAME = "DouyinLiveChannels"
        const val DEFAULT_BASE_URL = "https://tv.fuxiaoya.com"
        const val TV_UA =
            "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val RID_REGEX = Regex("^\\d{3,22}$")

        /**
         * 规范化基础域名，去除多余 path 与尾部斜杠，确保始终拼出 https://host/live.php
         */
        fun normalizeBaseUrl(rawUrl: String?): String {
            val trimmed = rawUrl?.trim()?.trimEnd('/') ?: ""
            if (trimmed.isBlank()) return DEFAULT_BASE_URL
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme
                val authority = uri.authority
                if (!scheme.isNullOrBlank() && !authority.isNullOrBlank()) {
                    "$scheme://$authority"
                } else {
                    trimmed.removeSuffix("/live.php").trimEnd('/')
                }
            } catch (_: Exception) {
                trimmed.removeSuffix("/live.php").trimEnd('/')
            }
        }

        /**
         * 解析网页版 live.php?batch= 返回的权威 JSON：
         * {
         *   "code": 200,
         *   "batch": {
         *     "127453393722": { "status": "live" | "offline" | "unknown", "title": "...", "avatar": "...", "name": "..." }
         *   }
         * }
         * 规则：
         * - "live" -> isLive = true, statusKnown = true
         * - "offline" -> isLive = false, statusKnown = true
         * - "unknown" / 其他 -> 绝不强改原 isLive，仅标 statusKnown = false
         * 若 code != 200 或 batch 缺失/为空，返回 null（不破坏本地列表）。
         */
        fun extractAvatarUrl(obj: JSONObject?): String {
            if (obj == null) return ""
            val raw = listOf("avatar", "avatarUrl", "posterUrl", "cover")
                .firstNotNullOfOrNull { key ->
                    obj.optString(key, "").trim().takeIf { it.isNotBlank() }
                } ?: ""
            return if (raw.startsWith("//")) "https:$raw" else raw
        }

        fun applyBatchProbeJson(
            currentMap: MutableMap<String, Channel>,
            jsonBody: String
        ): Boolean {
            if (jsonBody.isBlank()) return false
            val root = try {
                JSONObject(jsonBody)
            } catch (_: Exception) {
                return false
            }

            val code = root.optInt("code", 0)
            val batch = root.optJSONObject("batch")
            if (code != 200 || batch == null || batch.length() == 0) {
                return false
            }

            val keys = batch.keys()
            while (keys.hasNext()) {
                val rid = keys.next()
                val old = currentMap[rid] ?: continue
                val item = batch.optJSONObject(rid) ?: continue

                val statusStr = item.optString("status", "").trim().lowercase()
                val nextName = item.optString("name", "").trim().takeIf { it.isNotBlank() } ?: old.name
                val nextTitle = item.optString("title", "").trim().takeIf { it.isNotBlank() } ?: old.title
                val nextAvatar = extractAvatarUrl(item).takeIf { it.isNotBlank() } ?: old.avatar

                currentMap[rid] = when (statusStr) {
                    "live" -> old.copy(
                        isLive = true,
                        statusKnown = true,
                        name = nextName,
                        title = nextTitle,
                        avatar = nextAvatar
                    )
                    "offline" -> old.copy(
                        isLive = false,
                        statusKnown = true,
                        name = nextName,
                        title = nextTitle,
                        avatar = nextAvatar
                    )
                    else -> old.copy(
                        // unknown：保留原 isLive，仅标记状态未知
                        statusKnown = false,
                        name = nextName,
                        title = nextTitle,
                        avatar = nextAvatar
                    )
                }
            }
            return true
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // 与网页版完全一致的默认频道（不预设写死 isLive，由服务端 live.php?batch= 探测）
    fun getDefaultChannels(): List<Channel> = listOf(
        Channel("127453393722", "央视网快看", "总台央视新闻频道正在播出"),
        Channel("232551178364", "电影频道云影院", "直播丨𝐂𝐂𝐓𝐕𝟔电影频道🎬"),
        Channel("50828500437", "央视频", "新闻正在直播"),
        Channel("783811579858", "央视三农", "CCTV-17节目真好看！"),
        Channel("193671986973", "安静听歌（24小时听歌）", "热门流行伤感经典好听伤感怀旧音乐老歌1000首！"),
        Channel("662843050230", "央视文艺", "金秋综艺特辑"),
        Channel("971054279946", "央视网美学", "央视纪录片频道直播"),
        Channel("296728101980", "央视网财经", "央视财经频道节目直播")
    )

    /**
     * 加载本地保存的频道（若无则返回默认列表）
     */
    fun loadChannels(): List<Channel> {
        val raw = prefs.getString(KEY_CHANNELS, null)
        if (raw.isNullOrBlank()) return getDefaultChannels()
        return try {
            val list = parseJsonArray(raw)
            if (list.isEmpty()) getDefaultChannels() else list
        } catch (_: Exception) {
            getDefaultChannels()
        }
    }

    /**
     * 保存频道列表到 SharedPreferences
     */
    fun saveChannels(list: List<Channel>) {
        val jsonStr = channelsToJsonString(list)
        prefs.edit().putString(KEY_CHANNELS, jsonStr).apply()
    }

    /**
     * 恢复默认频道列表
     */
    fun resetToDefault(): List<Channel> {
        val defaults = getDefaultChannels()
        saveChannels(defaults)
        return defaults
    }

    /**
     * 导出为 JSON 文件，返回 File 句柄
     */
    fun exportJson(list: List<Channel>): File {
        val dir = File(context.getExternalFilesDir(null), EXPORT_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "douyin_live_channels_${System.currentTimeMillis()}.json")
        file.writeText(channelsToJsonString(list), Charsets.UTF_8)
        return file
    }

    /**
     * 导出为纯房间号 TXT 文件
     */
    fun exportTxt(list: List<Channel>): File {
        val dir = File(context.getExternalFilesDir(null), EXPORT_DIR_NAME).apply { mkdirs() }
        val file = File(dir, "douyin_live_channels_${System.currentTimeMillis()}.txt")
        file.writeText(list.joinToString("\n") { it.rid }, Charsets.UTF_8)
        return file
    }

    /**
     * 解析导入内容（兼容网页版 JSON 数组 / TXT / CSV 格式）
     */
    fun parseImportContent(content: String): List<Channel> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1. 尝试 JSON 数组解析
        if (trimmed.startsWith("[")) {
            val list = parseJsonArray(trimmed)
            if (list.isNotEmpty()) return list
        }

        // 2. TXT / CSV：每行一个房间号，或 "rid,name,title"
        return trimmed.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(",", "\t", " ").map { it.trim().removeSurrounding("\"") }
                val rid = parts.firstOrNull { it.matches(RID_REGEX) } ?: return@mapNotNull null
                val name = parts.getOrNull(1)?.ifBlank { "房间 $rid" } ?: "房间 $rid"
                val title = parts.getOrNull(2) ?: ""
                Channel(rid = rid, name = name, title = title, isLive = false, statusKnown = false)
            }
            .distinctBy { it.rid }
    }

    /**
     * 远程导入：直接拉取 URL 内容并解析
     */
    suspend fun remoteImport(url: String): Result<List<Channel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url.trim())
                .header("User-Agent", TV_UA)
                .get()
                .build()

            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("响应内容为空"))
                val list = parseImportContent(body)
                if (list.isEmpty()) {
                    Result.failure(Exception("未解析出任何有效频道"))
                } else {
                    Result.success(list)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 通过网页版安全代理远程导入（对应网页 live.php?action=remote_import&remote_url=xxx）
     */
    suspend fun remoteImportViaProxy(baseUrl: String, remoteUrl: String): Result<List<Channel>> =
        withContext(Dispatchers.IO) {
            try {
                val base = normalizeBaseUrl(baseUrl)
                val encodedUrl = URLEncoder.encode(remoteUrl.trim(), "UTF-8")
                val proxyUrl = "$base/live.php?action=remote_import&remote_url=$encodedUrl"
                val request = Request.Builder()
                    .url(proxyUrl)
                    .header("User-Agent", TV_UA)
                    .header("Referer", "$base/")
                    .get()
                    .build()

                okHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("代理请求失败 HTTP ${response.code}"))
                    }
                    val body = response.body?.string() ?: return@withContext Result.failure(Exception("代理响应为空"))

                    val json = JSONObject(body)
                    val code = json.optInt("code", -1)
                    if (code != 200) {
                        return@withContext Result.failure(Exception(json.optString("msg", "远程代理拦截")))
                    }
                    val raw = json.optString("raw", "")
                    val list = parseImportContent(raw)
                    if (list.isEmpty()) {
                        Result.failure(Exception("未解析出有效频道"))
                    } else {
                        Result.success(list)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 合并频道列表（按 rid 去重，新列表优先覆盖旧条目）
     */
    fun mergeChannels(oldList: List<Channel>, newList: List<Channel>): List<Channel> {
        val map = LinkedHashMap<String, Channel>()
        oldList.forEach { map[it.rid] = it }
        newList.forEach { map[it.rid] = it }
        return map.values.toList()
    }

    /**
     * 与网页版 live.php?batch= 完全一致的权威测活：
     * - 每批最多 20 个（与 PHP 一致）
     * - status == "live" -> isLive = true, statusKnown = true
     * - status == "offline" -> isLive = false, statusKnown = true
     * - status == "unknown" / 失败 -> 不强改 isLive，仅标记 statusKnown = false
     */
    suspend fun probeLiveStatus(
        baseUrl: String = DEFAULT_BASE_URL,
        channels: List<Channel>
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) {
            return@withContext ProbeOutcome(channels = channels, networkFailed = false)
        }

        val map = channels.associateBy { it.rid }.toMutableMap()
        val base = normalizeBaseUrl(baseUrl)
        var anySucceeded = false
        var anyAttempted = false

        channels.chunked(20).forEach { chunk ->
            val ids = chunk.map { it.rid.trim() }.filter { it.matches(RID_REGEX) }
            if (ids.isEmpty()) return@forEach
            anyAttempted = true

            val url = "$base/live.php?batch=${ids.joinToString(",")}"
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", TV_UA)
                    .header("Referer", "$base/")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                okHttp.newCall(req).execute().use { resp ->
                    val httpCode = resp.code
                    if (httpCode == 304) {
                        Log.d(TAG, "url=$url code=304 (unchanged)")
                        anySucceeded = true
                        return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    Log.d(TAG, "url=$url code=$httpCode body=${body.take(200)}")

                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP $httpCode for batch url=$url")
                        return@use
                    }

                    val ok = applyBatchProbeJson(map, body)
                    if (ok) {
                        anySucceeded = true
                    } else {
                        Log.w(TAG, "bad batch response for url=$url body=${body.take(200)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "batch failed url=$url", e)
            }
        }

        val updated = channels.map { map[it.rid] ?: it }
        val liveCount = updated.count { it.isLive }
        val unknownCount = updated.count { !it.statusKnown }
        Log.d(TAG, "probe finished total=${updated.size} live=$liveCount unknown=$unknownCount anySucceeded=$anySucceeded")

        ProbeOutcome(
            channels = updated,
            networkFailed = anyAttempted && !anySucceeded,
            liveCount = liveCount,
            unknownCount = unknownCount
        )
    }

    /**
     * 兼容接口：直接返回更新后的列表（失败时保留原列表不清空）
     */
    suspend fun checkLiveStatusViaWeb(
        baseUrl: String = DEFAULT_BASE_URL,
        channels: List<Channel>
    ): List<Channel> = probeLiveStatus(baseUrl, channels).channels

    suspend fun checkLiveStatus(
        baseUrl: String = DEFAULT_BASE_URL,
        channels: List<Channel>
    ): List<Channel> = probeLiveStatus(baseUrl, channels).channels

    private fun parseJsonArray(jsonStr: String): List<Channel> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Channel>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val rid = obj.optString("rid").trim()
                if (rid.matches(RID_REGEX)) {
                    list.add(
                        Channel(
                            rid = rid,
                            name = obj.optString("name", "房间 $rid").ifBlank { "房间 $rid" },
                            title = obj.optString("title", ""),
                            avatar = extractAvatarUrl(obj),
                            isLive = obj.optBoolean("isLive", false),
                            statusKnown = obj.optBoolean("statusKnown", false)
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun channelsToJsonString(list: List<Channel>): String {
        val array = JSONArray()
        for (c in list) {
            val obj = JSONObject().apply {
                put("rid", c.rid)
                put("name", c.name)
                put("title", c.title)
                put("avatar", c.avatar)
                put("isLive", c.isLive)
                put("statusKnown", c.statusKnown)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
