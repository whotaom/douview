package com.example.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
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
 * 电视大屏全屏原生播放器（针对 H96 Max RK3528 等 32位电视芯片深度定制版）
 * 彻底解决机顶盒「有声音但黑屏」的核心方案：
 * 1. 强制通过 activity_tv_fullscreen_player.xml 实例化 SurfaceView（app:surface_type="surface_view"），解决 TextureView 在 RK 芯片上的合成黑屏；
 * 2. 启用 DefaultRenderersFactory.setEnableDecoderFallback(true)，硬件解码器异常时自动回退至兼容渲染管线；
 * 3. 显式关闭隧道模式 (Tunneling Mode)，解除部分 Android TV 13 盒子固件在隧道直通模式下的视频图层锁死；
 * 4. 监听 onVideoSizeChanged 与 onRenderedFirstFrame，第一帧渲染即刻置顶 SurfaceView 并消除任何提示遮罩；
 * 5. 网页版逻辑全量对齐：在线优先、自动取流、清晰度自动降级、遥控器上下键丝滑切台。
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TVPlayer"
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

        // 2. 加载带 app:surface_type="surface_view" 的专用布局，解决 RK3528 芯片视频层无法显示的问题
        setContentView(R.layout.activity_tv_fullscreen_player)
        rootContainer = findViewById(R.id.rootContainer)
        playerView = findViewById(R.id.playerView)
        tipView = findViewById(R.id.tipView)

        // 确保播放器视图初始设置
        playerView.apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }

        // 3. 遥控器返回键处理
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPlayer()
            }
        })

        // 4. 初始化频道列表与播放策略
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

        // 加载全部持久化/默认频道并按「在线优先」排序
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
     * 播放当前选中的频道（向 live.php 请求多清晰度流）
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
                switchToNextChannel("当前频道解析失败")
                return@launch
            }
            currentQualityIndex = 0
            playCurrentQuality()
        }
    }

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

                    for (key in priorityKeys) {
                        val streamItem = streamsObj.optJSONObject(key)
                        val hlsUrl = streamItem?.optString("hls")?.trim()
                        if (!hlsUrl.isNullOrBlank()) {
                            val name = streamItem.optString("name", key).ifBlank { key }
                            result.add(name to hlsUrl)
                        }
                    }

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
     * 播放当前频道的当前清晰度（针对 RK3528 盒子的硬件渲染加强配置）
     */
    private fun playCurrentQuality() {
        if (currentQualityIndex !in qualityList.indices) {
            switchToNextChannel("清晰度全部失败")
            return
        }

        val (qualityName, hlsUrl) = qualityList[currentQualityIndex]
        val channel = playList.getOrNull(currentChannelIndex)
        val channelName = channel?.name ?: ""
        val indexInfo = "【${currentChannelIndex + 1}/${playList.size}】"
        showTip("$indexInfo $channelName · $qualityName")

        releasePlayer()

        // ========== 关键：针对 H96 Max RK3528 的解码器回退与渲染器优化 ==========
        val renderersFactory = DefaultRenderersFactory(this).apply {
            // 启用解码器回退：若硬件解码器协商失败，自动回退至兼容模式，避免黑屏
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setMediaCodecSelector(MediaCodecSelector.DEFAULT)
        }

        // 关键：显式关闭隧道模式 (Tunneling Mode)，避免 RK 芯片视频图层锁死
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // 最小缓冲 15 秒
                50_000,   // 最大缓冲 50 秒
                3_000,    // 开播缓冲 3 秒
                6_000     // 重新缓冲 6 秒
            )
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo

                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "播放异常: ${error.errorCodeName} | ${error.message}")
                        switchToLowerQuality("播放异常: ${error.errorCodeName}")
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
                                playerView.bringToFront()
                            }
                            Player.STATE_ENDED -> {
                                cancelBufferingTimeout()
                                showTip("直播已结束，按遥控器上下键切台")
                            }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        // 视频尺寸发生变化表明视频轨已正常解码并建立画面 Surface
                        Log.d(TAG, "视频尺寸已更新: ${videoSize.width}x${videoSize.height}")
                        cancelBufferingTimeout()
                        tipView.visibility = View.GONE
                        playerView.bringToFront()
                    }

                    override fun onRenderedFirstFrame() {
                        // 第一帧渲染完毕，立即置顶 SurfaceView 确保绝无遮挡
                        Log.d(TAG, "第一帧画面已渲染")
                        cancelBufferingTimeout()
                        tipView.visibility = View.GONE
                        playerView.bringToFront()
                    }
                })

                val mediaItem = MediaItem.fromUri(hlsUrl)
                exo.setMediaItem(mediaItem)
                exo.prepare()
                exo.playWhenReady = true
                scheduleBufferingTimeout()
            }
    }

    private fun switchToLowerQuality(reason: String) {
        cancelBufferingTimeout()
        currentQualityIndex++

        if (currentQualityIndex >= qualityList.size) {
            switchToNextChannel("清晰度全部失败")
            return
        }

        val (nextName, _) = qualityList[currentQualityIndex]
        showTip("$reason，正在降级切换至: $nextName")

        playerView.postDelayed({
            playCurrentQuality()
        }, 1200L)
    }

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
