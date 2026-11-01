package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 抖音直播间流地址原生解析引擎 (OkHttp + 协程)
 * 支持纯数字房间号、抖音分享短链 (v.douyin.com/...) 解析并获取真实 HLS 直播流
 */
object DouyinStreamResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false) // 手动拦截 302 提取短链真实重定向房间 ID
        .build()

    private var cachedTtwid: String = ""

    data class LiveRoomInfo(
        val roomId: String,
        val anchorName: String,
        val title: String,
        val avatarUrl: String,
        val isLive: Boolean,
        val streams: Map<String, String> // 包含清晰度如 "超清", "高清", "标清" 对应的 HLS 播放地址
    )

    /**
     * 获取或刷新 ttwid Token (字节跳动安全通行证)
     */
    suspend fun fetchTtwid(): String = withContext(Dispatchers.IO) {
        if (cachedTtwid.isNotEmpty()) return@withContext cachedTtwid
        try {
            val postBody = JSONObject().apply {
                put("region", "cn")
                put("aid", 1768)
                put("needFid", false)
                put("service", "www.ixigua.com")
                put("migrate_info", JSONObject().put("ticket", "").put("source", "node"))
                put("cbUrlProtocol", "https")
                put("union", true)
            }.toString()

            val request = Request.Builder()
                .url("https://ttwid.bytedance.com/ttwid/union/register/")
                .post(postBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val cookieHeader = response.header("Set-Cookie") ?: ""
            val matcher = Pattern.compile("ttwid=([^;\\s]+)").matcher(cookieHeader)
            if (matcher.find()) {
                cachedTtwid = matcher.group(1) ?: ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext cachedTtwid
    }

    /**
     * 解析短链或口令中的实际房间号
     */
    suspend fun extractRoomId(input: String): String? = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        // 1. 如果本身就是数字房间号
        if (trimmed.matches(Regex("^\\d{6,22}$"))) {
            return@withContext trimmed
        }

        // 2. 正则查找 URL 短链
        val urlMatcher = Pattern.compile("https?://v\\.douyin\\.com/[a-zA-Z0-9_-]+/?").matcher(trimmed)
        val shortUrl = if (urlMatcher.find()) urlMatcher.group(0) else if (trimmed.startsWith("http")) trimmed else null

        if (shortUrl != null) {
            try {
                val request = Request.Builder()
                    .url(shortUrl)
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X)")
                    .build()
                val response = client.newCall(request).execute()
                val location = response.header("Location") ?: ""

                val idMatcher = Pattern.compile("(?:reflow/|room_id=|live\\.douyin\\.com/)(\\d{10,22})").matcher(location)
                if (idMatcher.find()) {
                    return@withContext idMatcher.group(1)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. 备用纯数字正则提取
        val numMatcher = Pattern.compile("\\b\\d{10,20}\\b").matcher(trimmed)
        if (numMatcher.find()) {
            return@withContext numMatcher.group(0)
        }

        return@withContext null
    }

    /**
     * 获取直播间信息与各清晰度 HLS 流地址
     */
    suspend fun resolveLiveRoom(webRid: String): LiveRoomInfo? = withContext(Dispatchers.IO) {
        try {
            val ttwid = fetchTtwid()
            val url = "https://live.douyin.com/webcast/room/web/enter/?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN&enter_from=web_live&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=124.0.0.0&web_rid=$webRid"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36")
                .header("Referer", "https://live.douyin.com/$webRid")
                .header("Cookie", "ttwid=$ttwid")
                .build()

            val response = client.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return@withContext null
            val root = JSONObject(jsonStr)
            val roomData = root.optJSONObject("data")?.optJSONArray("data")?.optJSONObject(0) ?: return@withContext null

            val status = roomData.optInt("status")
            val isLive = (status == 2)
            val title = roomData.optString("title", "抖音直播间")
            val anchorName = roomData.optJSONObject("owner")?.optString("nickname", "主播") ?: "主播"
            val avatarUrl = roomData.optJSONObject("owner")?.optJSONObject("avatar_thumb")?.optJSONArray("url_list")?.optString(0) ?: ""

            val streamUrlObj = roomData.optJSONObject("stream_url")
            val streamDataRaw = streamUrlObj?.optJSONObject("live_core_sdk_data")?.optJSONObject("pull_data")?.optString("stream_data")

            val streams = mutableMapOf<String, String>()
            if (!streamDataRaw.isNullOrEmpty()) {
                val streamTree = JSONObject(streamDataRaw).optJSONObject("data")
                val uhdHls = streamTree?.optJSONObject("uhd")?.optJSONObject("main")?.optString("hls")
                val hdHls = streamTree?.optJSONObject("hd")?.optJSONObject("main")?.optString("hls")
                val sdHls = streamTree?.optJSONObject("sd")?.optJSONObject("main")?.optString("hls")

                if (!uhdHls.isNullOrEmpty()) streams["超清"] = uhdHls.replace("http://", "https://")
                if (!hdHls.isNullOrEmpty()) streams["高清"] = hdHls.replace("http://", "https://")
                if (!sdHls.isNullOrEmpty()) streams["标清"] = sdHls.replace("http://", "https://")
            }

            if (streams.isEmpty()) {
                val defaultHls = streamUrlObj?.optString("hls_pull_url")
                if (!defaultHls.isNullOrEmpty()) {
                    streams["默认线路"] = defaultHls.replace("http://", "https://")
                }
            }

            return@withContext LiveRoomInfo(
                roomId = webRid,
                anchorName = anchorName,
                title = title,
                avatarUrl = avatarUrl,
                isLive = isLive,
                streams = streams
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
