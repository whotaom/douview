package com.example.tv.player

import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * 电视大屏全屏原生播放器（完整优化版：左侧在线选台浮层 + 顶部防遮挡高亮换台提示 + 流缓存 + RK3528专项加固）
 * 1. 遥控器【菜单键 / 信息键】：仅在左侧滑出 420dp 悬浮面板，只展示「在线频道」，文字超长自动省略，不遮挡右侧直播画面；
 * 2. 换台反馈修复：顶部居中 30dp 高层级悬浮提示，带 160ms 淡入 / 250ms 淡出动效，绝不被底层视频画面遮盖；
 * 3. 换台毫秒级响应：5 分钟 TTL 流缓存 + 600ms 防抖；
 * 4. 完美保持 H96 Max RK3528 硬件级渲染修复（SurfaceView 硬件加速、解码器回退、关闭隧道模式）。
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TVPlayer"
        const val DEFAULT_BASE_URL = "https://tv.fuxiaoya.com"
        const val TV_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_RID = "extra_rid"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_TITLE = "extra_title"

        // 全局静态流缓存 (rid -> 清晰度列表)，支持跨页面或清理缓存
        val streamCache = mutableMapOf<String, List<Pair<String, String>>>()
        val streamCacheTime = mutableMapOf<String, Long>()
        const val CACHE_TTL = 5 * 60 * 1000L // 5 分钟有效时间

        fun clearStreamCache() {
            streamCache.clear()
            streamCacheTime.clear()
        }
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

    // 左侧频道选择抽屉菜单相关
    private var isChannelMenuShowing = false
    private lateinit var channelMenuContainer: FrameLayout
    private lateinit var channelMenuList: LinearLayout

    // 媒体流技术信息悬浮面板 (OK / DPAD_CENTER 呼出)
    private lateinit var infoOverlay: TextView

    // 换台防抖控制 (600ms)
    private var lastSwitchTime = 0L
    private val SWITCH_DEBOUNCE = 600L

    // 提示框动画与自动隐藏句柄
    private var tipHideRunnable: Runnable? = null

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

        // 2. 加载带 app:surface_type="surface_view" 的专用布局
        setContentView(R.layout.activity_tv_fullscreen_player)
        rootContainer = findViewById(R.id.rootContainer)
        playerView = findViewById(R.id.playerView)
        tipView = findViewById(R.id.tipView)

        playerView.apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }

        // 3. 初始化左侧在线频道选台抽屉与技术信息面板
        initChannelMenu()
        initInfoOverlay()

        // 4. 遥控器返回键处理
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isChannelMenuShowing) {
                    hideChannelMenu()
                } else if (infoOverlay.visibility == View.VISIBLE) {
                    hideLiveInfo()
                } else {
                    exitPlayer()
                }
            }
        })

        // 5. 初始化频道列表与播放策略
        channelManager = ChannelManager(this)
        setupPlayListAndStart()
    }

    /**
     * 构建电视大屏左侧选台面板（仅占左侧 420dp，不遮挡右侧直播画面）
     */
    private fun initChannelMenu() {
        channelMenuContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                420.dpToPx(),
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.gravity = Gravity.START
            }
            setBackgroundColor(Color.parseColor("#E6121212")) // 半透明深色，右侧画面清晰透出
            visibility = View.GONE
            elevation = 20f
            isClickable = true
            isFocusable = true
        }

        val title = TextView(this).apply {
            text = "在线频道"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(28, 28, 28, 12)
            setTypeface(null, Typeface.BOLD)
        }

        channelMenuList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 0, 12, 24)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.topMargin = 72 }
            addView(channelMenuList)
        }

        channelMenuContainer.addView(title)
        channelMenuContainer.addView(scrollView)
        rootContainer.addView(channelMenuContainer)
    }

    /**
     * 弹出左侧选台浮层（仅展示在线频道，文字过长自动省略，焦点自动落到当前台）
     */
    private fun showChannelMenu() {
        if (isChannelMenuShowing) return
        hideLiveInfo()
        isChannelMenuShowing = true
        channelMenuList.removeAllViews()

        // 仅筛选在线频道展示，同时保留真实全局索引
        val onlineWithIndex = playList.mapIndexed { index, channel ->
            index to channel
        }.filter { it.second.isLive }

        if (onlineWithIndex.isEmpty()) {
            showTip("当前没有在线频道")
            isChannelMenuShowing = false
            return
        }

        onlineWithIndex.forEach { (realIndex, channel) ->
            val isCurrent = (realIndex == currentChannelIndex)

            val item = TextView(this).apply {
                tag = realIndex // 绑定真实全局索引，确保跳转准确无误
                text = buildString {
                    append(if (isCurrent) "▶ " else "   ")
                    append(channel.name)
                }
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END // 文字过长省略
                setTextColor(if (isCurrent) Color.parseColor("#4FC3F7") else Color.WHITE)
                textSize = 17f
                setPadding(20, 16, 20, 16)
                isFocusable = true
                isClickable = true
                setBackgroundColor(if (isCurrent) Color.parseColor("#224FC3F7") else Color.TRANSPARENT)

                setOnFocusChangeListener { _, hasFocus ->
                    setBackgroundColor(
                        if (hasFocus) Color.parseColor("#33FFFFFF") else (if (isCurrent) Color.parseColor("#224FC3F7") else Color.TRANSPARENT)
                    )
                    if (hasFocus) {
                        setTextColor(Color.parseColor("#4FC3F7"))
                    } else {
                        setTextColor(if (isCurrent) Color.parseColor("#4FC3F7") else Color.WHITE)
                    }
                }

                setOnClickListener { v ->
                    val target = v.tag as? Int ?: return@setOnClickListener
                    hideChannelMenu()
                    switchToChannel(target, force = true)
                }
            }
            channelMenuList.addView(item)
        }

        channelMenuContainer.visibility = View.VISIBLE
        channelMenuContainer.bringToFront()

        // 焦点自动精准锁定在当前播放的在线频道
        val focusIndex = onlineWithIndex.indexOfFirst { it.first == currentChannelIndex }.coerceAtLeast(0)
        channelMenuList.getChildAt(focusIndex)?.requestFocus()
    }

    private fun hideChannelMenu() {
        isChannelMenuShowing = false
        channelMenuContainer.visibility = View.GONE
    }

    /**
     * 初始化技术信息悬浮面板 (OK / DPAD_CENTER 呼出)
     */
    private fun initInfoOverlay() {
        infoOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.CENTER
            }
            setBackgroundColor(Color.parseColor("#E6121212")) // 深色半透明背景
            setTextColor(Color.WHITE)
            textSize = 15f
            setLineSpacing(8f, 1.25f)
            setPadding(40, 32, 40, 32)
            visibility = View.GONE
            elevation = 35f
            isFocusable = false
            isClickable = false
        }
        rootContainer.addView(infoOverlay)
    }

    /**
     * 展示当前频道的媒体流技术信息面板
     */
    private fun showLiveInfo() {
        val exo = player ?: return
        hideChannelMenu()

        val channel = playList.getOrNull(currentChannelIndex)
        val channelName = channel?.name ?: "未知频道"
        val rid = channel?.rid ?: "-"
        val isLiveText = if (channel?.isLive == true) "在线直播中" else "离线"

        val videoFormat = exo.videoFormat
        val audioFormat = exo.audioFormat
        val width = videoFormat?.width ?: -1
        val height = videoFormat?.height ?: -1
        val resText = if (width > 0 && height > 0) "${width} × ${height}" else "解析中"

        val videoCodec = videoFormat?.sampleMimeType ?: "未知"
        val audioCodec = audioFormat?.sampleMimeType ?: "未知"

        val bitrateEstimate = videoFormat?.bitrate?.takeIf { it > 0 }
            ?: (videoFormat?.averageBitrate?.takeIf { it > 0 })
        val bitrateText = if (bitrateEstimate != null && bitrateEstimate > 0) {
            "${bitrateEstimate / 1000} kbps"
        } else {
            "自适应"
        }

        val bufferedMs = exo.totalBufferedDuration

        val playbackStateStr = when (exo.playbackState) {
            Player.STATE_IDLE -> "空闲"
            Player.STATE_BUFFERING -> "缓冲中"
            Player.STATE_READY -> if (exo.isPlaying) "正在播放" else "已暂停"
            Player.STATE_ENDED -> "已结束"
            else -> "未知"
        }

        val (curQuality, _) = qualityList.getOrNull(currentQualityIndex) ?: ("默认" to "")

        val infoText = buildString {
            append("【当前频道流信息】\n")
            append("频道名称：$channelName\n")
            append("房间号 (RID)：$rid\n")
            append("直播状态：$isLiveText\n")
            append("清晰度流：$curQuality\n")
            append("视频分辨率：$resText\n")
            append("视频编码：$videoCodec\n")
            append("音频编码：$audioCodec\n")
            append("当前码率：$bitrateText\n")
            append("缓冲时长：${bufferedMs} ms\n")
            append("播放状态：$playbackStateStr\n\n")
            append("（再次按 OK 或返回键关闭）")
        }

        infoOverlay.text = infoText
        infoOverlay.visibility = View.VISIBLE
        infoOverlay.bringToFront()
    }

    private fun hideLiveInfo() {
        infoOverlay.visibility = View.GONE
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
     * 播放当前选中的频道（带缓存支持）
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

        // 优先检查 5 分钟内的有效流缓存
        val now = System.currentTimeMillis()
        val cached = streamCache[channel.rid]
        val cacheTime = streamCacheTime[channel.rid] ?: 0L
        if (cached != null && now - cacheTime < CACHE_TTL && cached.isNotEmpty()) {
            qualityList = cached
            currentQualityIndex = 0
            playCurrentQuality()
            return
        }

        lifecycleScope.launch {
            val fetched = fetchQualityList(baseUrl, channel.rid)
            if (fetched.isEmpty()) {
                switchToNextChannel("当前频道解析失败")
                return@launch
            }
            // 写入本地高速缓存
            streamCache[channel.rid] = fetched
            streamCacheTime[channel.rid] = System.currentTimeMillis()

            qualityList = fetched
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
                    .header("User-Agent", TV_USER_AGENT)
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

        // 关键：针对 H96 Max RK3528 的解码器回退与渲染器优化
        val renderersFactory = DefaultRenderersFactory(this).apply {
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
            .setUserAgent(TV_USER_AGENT)
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
                                playerView.bringToFront()
                                tipView.bringToFront()
                            }
                            Player.STATE_ENDED -> {
                                cancelBufferingTimeout()
                                showTip("直播已结束，按上下键换台或菜单键选台")
                            }
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.d(TAG, "视频尺寸已更新: ${videoSize.width}x${videoSize.height}")
                        cancelBufferingTimeout()
                        playerView.bringToFront()
                        tipView.bringToFront()
                    }

                    override fun onRenderedFirstFrame() {
                        Log.d(TAG, "第一帧画面已渲染")
                        cancelBufferingTimeout()
                        playerView.bringToFront()
                        tipView.bringToFront()
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

    /**
     * 切换到指定索引的电视频道（带防抖、流缓存与即时文案反馈）
     */
    private fun switchToChannel(index: Int, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSwitchTime < SWITCH_DEBOUNCE) return
        lastSwitchTime = now

        if (index !in playList.indices) return
        if (!force && index == currentChannelIndex) return

        hideLiveInfo()
        currentChannelIndex = index
        val channel = playList[index]
        showTip("已切换到：${channel.name}")

        // 优先使用缓存实现毫秒级换台
        val cached = streamCache[channel.rid]
        val cacheTime = streamCacheTime[channel.rid] ?: 0L
        if (cached != null && now - cacheTime < CACHE_TTL && cached.isNotEmpty()) {
            qualityList = cached
            currentQualityIndex = 0
            playCurrentQuality()
            return
        }

        startCurrentChannel()
    }

    /**
     * 切换到下一个频道
     */
    private fun switchToNextChannel(reason: String = "") {
        cancelBufferingTimeout()
        if (currentChannelIndex >= playList.size - 1) {
            showTip(if (reason.isNotEmpty()) "已是最后一个频道（$reason）" else "已经是最后一个频道")
            return
        }
        val next = playList[currentChannelIndex + 1]
        showTip("↓ 正在切换到：${next.name}")
        switchToChannel(currentChannelIndex + 1)
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
        val prev = playList[currentChannelIndex - 1]
        showTip("↑ 正在切换到：${prev.name}")
        switchToChannel(currentChannelIndex - 1)
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

    /**
     * 具备平滑淡入淡出动效的高层级屏幕提示（确保不被画面盖住）
     */
    private fun showTip(msg: String, autoHideMs: Long = 2500) {
        tipView.bringToFront()
        tipView.text = msg
        tipView.visibility = View.VISIBLE
        tipView.alpha = 0f

        tipView.animate()
            .alpha(1f)
            .setDuration(160)
            .start()

        tipHideRunnable?.let { tipView.removeCallbacks(it) }
        if (autoHideMs > 0) {
            tipHideRunnable = Runnable {
                tipView.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {
                        tipView.visibility = View.GONE
                    }
                    .start()
            }
            tipView.postDelayed(tipHideRunnable!!, autoHideMs)
        }
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

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // 遥控器 D-pad 与 菜单键原生全适配
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 播放系统导航音效
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                try {
                    val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                    am?.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP)
                } catch (_: Exception) {
                }
            }
        }

        // 1. 频道选择悬浮菜单开启时的按键优先拦截
        if (isChannelMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_BACK -> {
                    hideChannelMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    // 主动获取当前获焦的项，直接执行跳转，防止部分电视盒子遥控器不向子 View 分发 performClick
                    val focusedView = currentFocus ?: channelMenuList.focusedChild
                    val target = (focusedView?.tag as? Int)
                    if (target != null) {
                        hideChannelMenu()
                        switchToChannel(target, force = true)
                        return true
                    }
                    return false
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // 2. 媒体流技术信息浮层开启时的按键拦截（按 OK、Enter、Back、Menu、Info 均关闭）
        if (infoOverlay.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_MENU -> {
                    hideLiveInfo()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        // 3. 正常播放状态下的遥控器控制
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                exitPlayer()
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                showChannelMenu()
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
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                showLiveInfo()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
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
        tipHideRunnable?.let { tipView.removeCallbacks(it) }
        cancelBufferingTimeout()
        releasePlayer()
        super.onDestroy()
    }
}
