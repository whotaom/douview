package com.example.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 电视大屏全屏原生播放器（完全对齐 tv_fuxiaoya_com 网页版逻辑）
 * 1. 自动连接官方默认服务 https://tv.fuxiaoya.com，无需手动设置房间号；
 * 2. 深度集成 ChannelManager，支持「在线优先」智能排序；
 * 3. 自动通过 live.php?rid=xxx 提取 HLS 原生多码率高清直播流；
 * 4. 军工级故障降级：清晰度失败自动向下兼容（原画 -> 高清 -> 标清），全部失败自动切换至下一可用电视频道；
 * 5. 遥控器原生全适配：上下键一键切台，OK键控制暂停/播放，返回键秒停流退出。
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_BASE_URL = "https://tv.fuxiaoya.com"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_RID = "extra_rid"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var channelManager: ChannelManager
    private var playList: List<Channel> = emptyList()
    private var currentChannelIndex = 0

    // 当前频道的清晰度梯度列表: List<Pair<清晰度名称, HLS地址>>
    private var qualityList: List<Pair<String, String>> = emptyList()
    private var currentQualityIndex = 0

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var rootContainer: FrameLayout
    private lateinit var tipView: TextView

    // 缓冲超时监测机制（12秒连续缓冲无响应自动降清晰度）
    private var bufferingTimeoutRunnable: Runnable? = null
    private val BUFFERING_TIMEOUT_MS = 12_000L

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 进入真实沉浸全屏与屏幕常亮
        enterTrueFullscreen()

        // 2. 根布局容器
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        // 3. 原生 Media3 PlayerView（无多余控制栏，保持纯净视频画面）
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        rootContainer.addView(playerView)

        // 4. 极简屏幕提示层
        tipView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.CENTER }
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#99000000"))
            text = "正在连接直播服务..."
            visibility = View.VISIBLE
        }
        rootContainer.addView(tipView)

        // 5. 遥控器返回键处理
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPlayer()
            }
        })

        // 6. 初始化频道列表与播放策略
        channelManager = ChannelManager(this)
        setupPlayListAndStart()
    }

    private fun setupPlayListAndStart() {
        // 场景 A：直接指定了 HLS 媒体流 (.m3u8)
        val directStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.trim()
        if (!directStreamUrl.isNullOrBlank()) {
            playList = listOf(Channel(rid = "direct", name = "直接媒体流", isLive = true))
            currentChannelIndex = 0
            qualityList = listOf("默认流" to directStreamUrl)
            currentQualityIndex = 0
            playCurrentQuality()
            return
        }

        // 加载全部持久化/默认频道并按「在线优先」排序（与网页版逻辑完全一致）
        val loadedChannels = channelManager.loadChannels()
        val sortedChannels = loadedChannels.sortedBy { if (it.isLive) 0 else 1 }.toMutableList()

        // 检查 Intent 中是否带有指定的特定房间号或链接
        var targetRid: String? = intent.getStringExtra(EXTRA_RID)?.trim()
        val rawUrl = intent.getStringExtra(EXTRA_URL)?.trim() ?: ""

        if (targetRid.isNullOrBlank() && rawUrl.isNotBlank()) {
            if (rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8?")) {
                playList = listOf(Channel(rid = "direct", name = "直接流媒体", isLive = true))
                currentChannelIndex = 0
                qualityList = listOf("默认流" to rawUrl)
                currentQualityIndex = 0
                playCurrentQuality()
                return
            }
            try {
                val uri = Uri.parse(rawUrl)
                val paramRid = uri.getQueryParameter("rid")
                if (!paramRid.isNullOrBlank()) {
                    targetRid = paramRid
                }
            } catch (_: Exception) {
            }
            if (targetRid.isNullOrBlank() && rawUrl.all { it.isDigit() }) {
                targetRid = rawUrl
            }
        }

        // 若指定了目标房间号，优先定位到该频道；若不在列表中则插入首位
        if (!targetRid.isNullOrBlank()) {
            val existingIndex = sortedChannels.indexOfFirst { it.rid == targetRid }
            if (existingIndex >= 0) {
                currentChannelIndex = existingIndex
            } else {
                sortedChannels.add(0, Channel(rid = targetRid, name = "自定义频道 ($targetRid)", isLive = true))
                currentChannelIndex = 0
            }
        } else {
            currentChannelIndex = 0
        }

        playList = sortedChannels
        startCurrentChannel()
    }

    /**
     * 播放当前选中的频道（自动向 live.php 请求多清晰度流）
     */
    private fun startCurrentChannel() {
        if (currentChannelIndex !in playList.indices) {
            showTip("没有可用频道，按返回键退出")
            return
        }

        val channel = playList[currentChannelIndex]
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
        val channelTag = "【第 ${currentChannelIndex + 1}/${playList.size} 台】"
        showTip("正在解析：$channelTag ${channel.name}")

        lifecycleScope.launch {
            qualityList = fetchQualityList(baseUrl, channel.rid)
            if (qualityList.isEmpty()) {
                // 当前频道取流失败，自动无缝切到下一个频道
                switchToNextChannel("当前频道解析失败")
                return@launch
            }
            currentQualityIndex = 0
            playCurrentQuality()
        }
    }

    /**
     * 调用 live.php 获取并按优先级排序清晰度列表
     * 优先级：origin (原画) -> uhd -> hd (高清) -> sd (标清) -> ld -> default
     */
    private suspend fun fetchQualityList(baseUrl: String, rid: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val apiUrl = "$baseUrl/live.php?rid=${rid.trim()}"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
                    )
                    .header("Referer", "$baseUrl/")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()

                    val json = JSONObject(body)
                    val code = json.optInt("code", 0)
                    val streamsObj = json.optJSONObject("streams")
                    if (code != 200 || streamsObj == null) return@withContext emptyList()

                    val priorityKeys = listOf("origin", "uhd", "hd", "sd", "ld", "default")
                    val result = mutableListOf<Pair<String, String>>()

                    // 1. 优先按梯队放入清晰度
                    for (key in priorityKeys) {
                        val streamItem = streamsObj.optJSONObject(key)
                        val hlsUrl = streamItem?.optString("hls")?.trim()
                        if (!hlsUrl.isNullOrBlank()) {
                            val name = streamItem.optString("name", key).ifBlank { key }
                            result.add(name to hlsUrl)
                        }
                    }

                    // 2. 补齐其他可能存在的自定义清晰度（避免漏流）
                    val keysIterator = streamsObj.keys()
                    while (keysIterator.hasNext()) {
                        val key = keysIterator.next()
                        if (key !in priorityKeys) {
                            val streamItem = streamsObj.optJSONObject(key)
                            val hlsUrl = streamItem?.optString("hls")?.trim()
                            if (!hlsUrl.isNullOrBlank() && result.none { it.second == hlsUrl }) {
                                val name = streamItem.optString("name", key).ifBlank { key }
                                result.add(name to hlsUrl)
                            }
                        }
                    }
                    result
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    /**
     * 播放当前频道的当前清晰度
     */
    private fun playCurrentQuality() {
        if (currentQualityIndex !in qualityList.indices) {
            // 当前频道的所有清晰度均告失败 → 自动切换至下一个频道
            switchToNextChannel("清晰度全部失败")
            return
        }

        val (qualityName, hlsUrl) = qualityList[currentQualityIndex]
        val channel = playList.getOrNull(currentChannelIndex)
        val channelName = channel?.name ?: ""
        val indexInfo = "【${currentChannelIndex + 1}/${playList.size}】"
        showTip("$indexInfo $channelName · $qualityName")

        releasePlayer()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                12_000,   // 最小缓冲 12 秒
                45_000,   // 最大缓冲 45 秒
                2_000,    // 开播缓冲 2 秒
                5_000     // 重新缓冲 5 秒
            )
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo

                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        // 当前清晰度出错 → 自动降清晰度
                        switchToLowerQuality("播放异常")
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                showTip("正在缓冲 [$qualityName]...")
                                scheduleBufferingTimeout()
                            }
                            Player.STATE_READY -> {
                                cancelBufferingTimeout()
                                tipView.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                                cancelBufferingTimeout()
                                showTip("直播已结束，按遥控器上下键切台")
                            }
                        }
                    }
                })

                val mediaItem = MediaItem.fromUri(hlsUrl)
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.playWhenReady = true
                scheduleBufferingTimeout()
            }
    }

    /**
     * 降清晰度核心方法
     */
    private fun switchToLowerQuality(reason: String) {
        cancelBufferingTimeout()
        currentQualityIndex++

        if (currentQualityIndex >= qualityList.size) {
            // 当前频道所有清晰度全部失败，自动尝试下一个可用频道
            switchToNextChannel("清晰度全部失败")
            return
        }

        val (nextName, _) = qualityList[currentQualityIndex]
        showTip("$reason，正在降级切换至: $nextName")

        playerView.postDelayed({
            playCurrentQuality()
        }, 1200L)
    }

    /**
     * 切换到下一个频道
     */
    private fun switchToNextChannel(reason: String = "") {
        cancelBufferingTimeout()
        currentChannelIndex++
        if (currentChannelIndex >= playList.size) {
            showTip("所有电视频道均已尝试完毕${if (reason.isNotEmpty()) "（$reason）" else ""}，按返回键退出")
            return
        }
        val next = playList[currentChannelIndex]
        showTip("自动切台至：${next.name}")
        playerView.postDelayed({
            startCurrentChannel()
        }, 1000L)
    }

    /**
     * 切换到上一个频道
     */
    private fun switchToPrevChannel() {
        cancelBufferingTimeout()
        if (currentChannelIndex <= 0) {
            showTip("已经是第一个频道")
            return
        }
        currentChannelIndex--
        val prev = playList[currentChannelIndex]
        showTip("切换到：${prev.name}")
        startCurrentChannel()
    }

    private fun scheduleBufferingTimeout() {
        cancelBufferingTimeout()
        bufferingTimeoutRunnable = Runnable {
            if (player?.playbackState == Player.STATE_BUFFERING) {
                switchToLowerQuality("缓冲超时")
            }
        }.also {
            playerView.postDelayed(it, BUFFERING_TIMEOUT_MS)
        }
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { playerView.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    private fun showTip(msg: String) {
        tipView.text = msg
        tipView.visibility = View.VISIBLE
    }

    private fun enterTrueFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun releasePlayer() {
        cancelBufferingTimeout()
        playerView.player = null
        player?.stop()
        player?.release()
        player = null
    }

    private fun exitPlayer() {
        releasePlayer()
        finish()
    }

    // 遥控器 D-pad 原生键位交互
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                exitPlayer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                switchToPrevChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                switchToNextChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        enterTrueFullscreen()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        cancelBufferingTimeout()
        releasePlayer()
        super.onDestroy()
    }
}
