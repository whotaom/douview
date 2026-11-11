package com.example.tv.channel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 电视端频道管理器（与 tv_fuxiaoya_com 网页版完全一致）
 * 1. 内置官方默认频道列表（央视网快看、电影频道云影院、央视频等）；
 * 2. 支持导出为 JSON / 纯房间号 TXT 文件；
 * 3. 支持本地导入（JSON / TXT / CSV）与远程导入（直接拉取或网页版安全代理）；
 * 4. 支持合并或替换现有列表，持久化保存。
 */
class ChannelManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "tv_channels_v1"
        private const val KEY_CHANNELS = "channels_json"
        private const val EXPORT_DIR_NAME = "DouyinLiveChannels"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // 与网页版完全一致的默认频道
    fun getDefaultChannels(): List<Channel> = listOf(
        Channel("127453393722", "央视网快看", "总台央视新闻频道正在播出", isLive = true),
        Channel("232551178364", "电影频道云影院", "直播丨𝐂𝐂𝐓𝐕𝟔电影频道🎬", isLive = true),
        Channel("50828500437", "央视频", "新闻正在直播", isLive = true),
        Channel("783811579858", "央视三农", "CCTV-17节目真好看！", isLive = true),
        Channel("193671986973", "安静听歌（24小时听歌）", "热门流行伤感经典好听伤感怀旧音乐老歌1000首！", isLive = true),
        Channel("662843050230", "央视文艺", "金秋综艺特辑", isLive = false),
        Channel("971054279946", "央视网美学", "央视纪录片频道直播", isLive = false),
        Channel("296728101980", "央视网财经", "央视财经频道节目直播", isLive = false)
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
                val rid = parts.firstOrNull { it.matches(Regex("\\d{3,22}")) } ?: return@mapNotNull null
                val name = parts.getOrNull(1)?.ifBlank { "房间 $rid" } ?: "房间 $rid"
                val title = parts.getOrNull(2) ?: ""
                Channel(rid = rid, name = name, title = title, isLive = true)
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
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
                )
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
                val encodedUrl = URLEncoder.encode(remoteUrl.trim(), "UTF-8")
                val proxyUrl = "${baseUrl.trimEnd('/')}/live.php?action=remote_import&remote_url=$encodedUrl"
                val request = Request.Builder()
                    .url(proxyUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36")
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

    private fun parseJsonArray(jsonStr: String): List<Channel> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Channel>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val rid = obj.optString("rid").trim()
                if (rid.matches(Regex("\\d{3,22}"))) {
                    list.add(
                        Channel(
                            rid = rid,
                            name = obj.optString("name", "房间 $rid").ifBlank { "房间 $rid" },
                            title = obj.optString("title", ""),
                            avatar = obj.optString("avatar", ""),
                            isLive = obj.optBoolean("isLive", true)
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
            }
            array.put(obj)
        }
        return array.toString()
    }
}
