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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 电视大屏全屏原生播放器（Media3 / ExoPlayer 原生渲染方案）
 * 彻底解决电视盒子（Amlogic、Rockchip、全志等）在 WebView 下播放视频黑屏但有声音的问题：
 * 1. ExoPlayer 原生 SurfaceView 渲染，绕过 WebView 复杂的离屏混合合成；
 * 2. 深度适配 tv_fuxiaoya_com 后端接口：自动请求 live.php?rid=xxxx 获取高清 HLS 真实流；
 * 3. 军工级清晰度自动降级策略：origin (原画) -> uhd -> hd (高清) -> sd (标清) -> ld，遇到解码失败或卡顿缓冲超时自动平滑降级；
 * 4. 遥控器原生交互优化：OK 键控制播放/暂停，上下方向键手动微调清晰度，返回键秒停流无残留背景音。
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"                 // 兼容传入的网页、带参地址或直接流
        const val EXTRA_RID = "extra_rid"                 // 明确指定房间号
        const val EXTRA_STREAM_URL = "extra_stream_url"   // 明确指定直接 HLS 流地址 (.m3u8)
        const val EXTRA_BASE_URL = "extra_base_url"       // 基础服务域名，默认 https://tv.fuxiaoya.com
        const val EXTRA_TITLE = "extra_title"             // 兼容标题参数
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var rootContainer: FrameLayout
    private lateinit var tipView: TextView

    // 清晰度列表：List<Pair<清晰度名称, HLS媒体流地址>>，已按高到低排序
    private var qualityList: List<Pair<String, String>> = emptyList()
    private var currentQualityIndex = 0

    // 缓冲超时监测机制（12秒缓冲无法播放自动降级）
    private var bufferingTimeoutRunnable: Runnable? = null
    private val BUFFERING_TIMEOUT_MS = 12_000L

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 真正沉浸式全屏 + 屏幕常亮守护
        enterTrueFullscreen()

        // 2. 根容器纯黑底色，杜绝边缘白边
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

        // 4. 极简屏幕提示层（加载状态 / 降级提示 / 异常告知）
        tipView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = android.view.Gravity.CENTER
            }
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#99000000"))
            text = "正在解析并连接直播流..."
            visibility = View.VISIBLE
        }
        rootContainer.addView(tipView)

        // 5. 遥控器返回键干净退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitPlayer()
            }
        })

        // 6. 解析参数并启动流媒体播放
        startPlay()
    }

    private fun startPlay() {
        // 场景 A：已直接传入解析好的 HLS 直播流
        val directStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        if (!directStreamUrl.isNullOrBlank()) {
            qualityList = listOf("默认流" to directStreamUrl.trim())
            currentQualityIndex = 0
            playCurrentQuality()
            return
        }

        // 场景 B：从传入的 EXTRA_URL 或 EXTRA_RID 智能解析房间号与中控地址
        val rawUrl = intent.getStringExtra(EXTRA_URL)?.trim() ?: ""
        var rid = intent.getStringExtra(EXTRA_RID)?.trim() ?: ""
        var baseUrl = intent.getStringExtra(EXTRA_BASE_URL)?.trimEnd('/') ?: "https://tv.fuxiaoya.com"

        if (rid.isBlank() && rawUrl.isNotBlank()) {
            if (rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8?")) {
                // 直接是 m3u8 地址
                qualityList = listOf("默认流" to rawUrl)
                currentQualityIndex = 0
                playCurrentQuality()
                return
            }

            // 尝试从 URL 查询参数解析 rid（例如：https://tv.fuxiaoya.com/?rid=127453393722）
            try {
                val uri = Uri.parse(rawUrl)
                val paramRid = uri.getQueryParameter("rid")
                if (!paramRid.isNullOrBlank()) {
                    rid = paramRid
                }
                if (!uri.host.isNullOrBlank() && uri.scheme != null) {
                    baseUrl = "${uri.scheme}://${uri.host}"
                    if (uri.port != -1 && uri.port != 80 && uri.port != 443) {
                        baseUrl += ":${uri.port}"
                    }
                }
            } catch (_: Exception) {
            }

            // 若仍为空且纯为数字，则整段作为 rid
            if (rid.isBlank() && rawUrl.all { it.isDigit() }) {
                rid = rawUrl
            }
        }

        if (rid.isBlank()) {
            showTip("未检测到直播间房间号\n请在中控台设置带 ?rid=xxxx 的地址\n（例如：https://tv.fuxiaoya.com/?rid=127453393722）")
            return
        }

        // 场景 C：调用 tv.fuxiaoya.com 后端 live.php 解析真实 HLS 多清晰度地址
        val targetBaseUrl = baseUrl
        val targetRid = rid
        lifecycleScope.launch {
            showTip("正在获取房间 [$targetRid] 的高清直播流...")
            qualityList = fetchQualityList(targetBaseUrl, targetRid)

            if (qualityList.isEmpty()) {
                showTip("未能从服务器解析到可用的 HLS 直播流\n请检查房间是否正在直播，按返回键退出")
                return@launch
            }

            currentQualityIndex = 0
            playCurrentQuality()
        }
    }

    /**
     * 调用 live.php 获取并按优先级从高到低排序清晰度列表
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

                    if (code != 200 || streamsObj == null) {
                        return@withContext emptyList()
                    }

                    val priorityKeys = listOf("origin", "uhd", "hd", "sd", "ld", "default")
                    val result = mutableListOf<Pair<String, String>>()

                    // 1. 优先按推荐清晰度梯度加入
                    for (key in priorityKeys) {
                        val streamItem = streamsObj.optJSONObject(key)
                        val hlsUrl = streamItem?.optString("hls")?.trim()
                        if (!hlsUrl.isNullOrBlank()) {
                            val name = streamItem.optString("name", key).ifBlank { key }
                            result.add(name to hlsUrl)
                        }
                    }

                    // 2. 补齐其他可能存在的自定义清晰度（避免遗漏）
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
     * 开始播放当前索引对应的清晰度流
     */
    private fun playCurrentQuality() {
        if (currentQualityIndex !in qualityList.indices) {
            showTip("所有可用清晰度均尝试失败，按返回键退出")
            return
        }

        val (name, hlsUrl) = qualityList[currentQualityIndex]
        showTip("正在连接 [$name] 画质...")

        releasePlayer()

        // 针对电视大屏定制的平稳缓冲策略，有效平抑抖动
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                12_000,   // 最小缓冲 12 秒
                45_000,   // 最大缓冲 45 秒
                2_000,    // 开播缓冲 2 秒
                5_000     // 重新缓冲 5 秒
            )
            .build()

        // 原生数据源，设置 TV 专属 User-Agent 与超时
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
                        // 播放异常时自动触发降级机制
                        switchToLowerQuality("播放异常")
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                showTip("正在缓冲 [$name]...")
                                scheduleBufferingTimeout()
                            }
                            Player.STATE_READY -> {
                                cancelBufferingTimeout()
                                tipView.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                                cancelBufferingTimeout()
                                showTip("直播已结束")
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
     * 自动向下一个清晰度梯度降级
     */
    private fun switchToLowerQuality(reason: String) {
        cancelBufferingTimeout()
        currentQualityIndex++

        if (currentQualityIndex >= qualityList.size) {
            showTip("所有清晰度均播放失败 ($reason)\n请检查网络或按遥控器返回键退出")
            return
        }

        val (nextName, _) = qualityList[currentQualityIndex]
        showTip("$reason，正在自动降级切换至: $nextName")

        playerView.postDelayed({
            playCurrentQuality()
        }, 1200L)
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

    // 遥控器 D-pad 原生键位拦截支持
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                exitPlayer()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
            // 遥控器上下键：手动切换清晰度
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (qualityList.size > 1 && currentQualityIndex < qualityList.size - 1) {
                    currentQualityIndex++
                    playCurrentQuality()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (qualityList.size > 1 && currentQualityIndex > 0) {
                    currentQualityIndex--
                    playCurrentQuality()
                    return true
                }
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
