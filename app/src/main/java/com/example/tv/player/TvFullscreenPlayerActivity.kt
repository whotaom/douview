package com.example.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.example.R
import com.example.data.LivePreferences
import com.example.data.StreamLines
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 电视大屏全屏直播播放器（顶级 TV 直播客户端方案）
 * 1. 横屏全屏自适应：横屏源自动铺满；竖屏源居中清晰显示（RESIZE_MODE_FIT）+ 两侧单解码低耗动态磨砂背景 + 35% 压暗
 * 2. 「先备后切」(prepare-then-swap)：换台期间旧流不中断，新流 STATE_READY/首帧瞬间阻断旧流并上屏
 * 3. 离线/无法播放自动切换下一在线台，记录本轮失败集合彻底防死循环
 * 4. 菜单仅展示在线频道，标题带实时在线台数「在线频道（N）」
 * 5. OK 键信息面板每秒实时刷新码率、缓冲、分辨率、音视频编码、线路与播放状态
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_BASE_URL = StreamLines.LINE_1
        private const val TAG = "TVPlayer"
        private const val TV_UA = ChannelManager.TV_UA

        const val EXTRA_URL = "extra_url"
        const val EXTRA_RID = "extra_rid"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_LINE = "extra_line"
        const val EXTRA_TITLE = "extra_title"

        // 全局静态流缓存 (rid -> 清晰度列表)，支持 5 分钟有效时间
        val streamCache = mutableMapOf<String, List<Pair<String, String>>>()
        val streamCacheTime = mutableMapOf<String, Long>()
        const val CACHE_TTL = 5 * 60 * 1000L

        fun clearStreamCache() {
            streamCache.clear()
            streamCacheTime.clear()
        }
    }

    private lateinit var channelManager: ChannelManager
    private lateinit var preferences: LivePreferences
    private var currentLine: Int = 1
    private var dynamicBgEnabled: Boolean = true
    private var allowBlur: Boolean = false

    // UI 组件
    private lateinit var root: FrameLayout
    private lateinit var videoStageView: AmbientVideoStageView // 单解码双层直出舞台（默认清晰 ZOOM，可选 ≤5px 轻磨砂，零压暗）
    private lateinit var tipView: TextView                 // 顶部高层级换台与状态提示
    private lateinit var channelMenu: FrameLayout          // 左侧在线选台悬浮面板
    private lateinit var menuTitleView: TextView           // 选台菜单标题：「在线频道（N）」
    private lateinit var channelMenuList: LinearLayout
    private lateinit var infoOverlay: TextView             // OK 键技术参数浮层（1s 实时刷新）

    // 主播放器与后台备播播放器（先备后切）
    private var player: ExoPlayer? = null
    private var preparingPlayer: ExoPlayer? = null
    private var isMenuShowing = false
    private var isInfoShowing = false
    private var isVideoPortrait = false

    // 播放列表与当前索引
    private var playList: List<Channel> = emptyList()
    private var currentIndex = 0
    private var qualityList: List<Pair<String, String>> = emptyList()
    private var qualityIndex = 0

    // 单飞 Job、防竞态、自动跳台防死循环
    private var switchJob: Job? = null
    private var switchGen = 0L
    private var lastSwitchAt = 0L
    private val SWITCH_MIN_INTERVAL = 250L
    private val failedRidsThisRound = mutableSetOf<String>()

    // 缓冲超时监测 (12秒未就绪自动降清晰度)
    private var bufferingTimeoutRunnable: Runnable? = null
    private val BUFFERING_TIMEOUT_MS = 12_000L

    // OK 信息面板 1 秒实时刷新
    private val mainHandler = Handler(Looper.getMainLooper())
    private val infoRefreshRunnable = object : Runnable {
        override fun run() {
            if (isInfoShowing && !isFinishing && !isDestroyed) {
                renderInfoOverlay()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        buildUi()

        preferences = LivePreferences(this)
        channelManager = ChannelManager(this)
        currentLine = resolveInitialLine()
        dynamicBgEnabled = preferences.dynamicBgEnabled
        allowBlur = preferences.dynamicBgBlurEnabled
        applyDynamicBackground()

        setupPlayListAndStart()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun resolveInitialLine(): Int {
        val extraLine = intent.getIntExtra(EXTRA_LINE, 0)
        if (extraLine == 1 || extraLine == 2) return extraLine
        val extraBase = intent.getStringExtra(EXTRA_BASE_URL)
        if (!extraBase.isNullOrBlank()) return StreamLines.detectLine(extraBase)
        return preferences.currentLine
    }

    private fun activeBaseUrl(): String {
        val extraBase = intent.getStringExtra(EXTRA_BASE_URL)?.trim()
        if (!extraBase.isNullOrBlank()) {
            return ChannelManager.normalizeBaseUrl(extraBase)
        }
        return StreamLines.baseUrl(currentLine)
    }

    // -------------------- UI 结构构建 --------------------

    private fun buildUi() {
        setContentView(R.layout.activity_tv_fullscreen_player)
        root = findViewById(R.id.rootContainer)
        videoStageView = findViewById(R.id.videoStageView)
        tipView = findViewById(R.id.tipView)

        videoStageView.onFirstFrameRendered = {
            if (!isFinishing && !isDestroyed && tipView.visibility == View.VISIBLE) {
                tipView.removeCallbacks(hideTipRunnable)
                tipView.postDelayed(hideTipRunnable, 600)
            }
        }

        // 1. 左侧在线选台悬浮面板（仅占 400dp，无全屏遮罩）
        channelMenu = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(400.dp(), FrameLayout.LayoutParams.MATCH_PARENT).also {
                it.gravity = Gravity.START
            }
            setBackgroundColor(Color.parseColor("#E6121212"))
            visibility = View.GONE
            elevation = 24f
            isClickable = true
            isFocusable = true
        }

        menuTitleView = TextView(this).apply {
            text = "在线频道（0）"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(24, 24, 24, 8)
        }
        channelMenuList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 0, 8, 16)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.topMargin = 64.dp() }
            addView(channelMenuList)
        }
        channelMenu.addView(menuTitleView)
        channelMenu.addView(scroll)
        root.addView(channelMenu)

        // 2. OK 键技术参数信息面板
        infoOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(500.dp(), FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER
            }
            setBackgroundColor(Color.parseColor("#E6101010"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(26, 20, 26, 20)
            elevation = 48f
            visibility = View.GONE
            setLineSpacing(5f, 1.2f)
        }
        root.addView(infoOverlay)
    }

    // -------------------- 播放与切台核心逻辑 --------------------

    private fun setupPlayListAndStart() {
        val directStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.trim()
        if (!directStreamUrl.isNullOrBlank()) {
            playList = listOf(Channel(rid = "direct", name = "直接媒体流", isLive = true, statusKnown = true))
            currentIndex = 0
            qualityList = listOf("默认流" to directStreamUrl)
            qualityIndex = 0
            prepareThenSwap(++switchGen, directStreamUrl, "直接媒体流")
            return
        }

        val loadedChannels = channelManager.loadChannels()
        val sortedChannels = loadedChannels.sortedWith(
            compareBy { if (it.isLive) 0 else if (!it.statusKnown) 1 else 2 }
        ).toMutableList()

        var targetRid: String? = intent.getStringExtra(EXTRA_RID)?.trim()
        val rawUrl = intent.getStringExtra(EXTRA_URL)?.trim() ?: ""

        if (targetRid.isNullOrBlank() && rawUrl.isNotBlank()) {
            if (rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8?")) {
                playList = listOf(Channel(rid = "direct", name = "直接流媒体", isLive = true, statusKnown = true))
                currentIndex = 0
                qualityList = listOf("默认流" to rawUrl)
                qualityIndex = 0
                prepareThenSwap(++switchGen, rawUrl, "直接流媒体")
                return
            }
            try {
                val uri = Uri.parse(rawUrl)
                val paramRid = uri.getQueryParameter("rid")
                if (!paramRid.isNullOrBlank()) targetRid = paramRid
            } catch (_: Exception) {}
            if (targetRid.isNullOrBlank() && rawUrl.all { it.isDigit() }) {
                targetRid = rawUrl
            }
        }

        if (!targetRid.isNullOrBlank()) {
            val existingIndex = sortedChannels.indexOfFirst { it.rid == targetRid }
            if (existingIndex >= 0) {
                currentIndex = existingIndex
            } else {
                sortedChannels.add(0, Channel(rid = targetRid, name = "自定义频道 ($targetRid)", isLive = true, statusKnown = false))
                currentIndex = 0
            }
        } else {
            currentIndex = 0
        }

        playList = sortedChannels
        switchToChannel(currentIndex, force = true)

        // 异步与当前线路同源测活，保证菜单展示真实在线台数与状态，同时锁定 currentIndex 不偏移
        val base = activeBaseUrl()
        lifecycleScope.launch {
            val outcome = channelManager.probeLiveStatus(base, playList)
            if (!outcome.networkFailed) {
                val playingRid = playList.getOrNull(currentIndex)?.rid
                val sorted = outcome.channels.sortedWith(
                    compareBy { if (it.isLive) 0 else if (!it.statusKnown) 1 else 2 }
                )
                playList = sorted
                if (playingRid != null) {
                    val syncedIdx = sorted.indexOfFirst { it.rid == playingRid }
                    if (syncedIdx >= 0) currentIndex = syncedIdx
                }
                channelManager.saveChannels(sorted)
            }
        }
    }

    /**
     * 单飞换台（「先备后切」）：
     * 1. 取消未完成的换台协程并释放未上屏的备播 Player
     * 2. 换台过程中 **不停止当前正在播放的旧流**，杜绝黑场/断音
     * 3. 新流在后台 ExoPlayer 准备至 STATE_READY 后，瞬间阻断旧流并切换上屏
     */
    private fun switchToChannel(
        index: Int,
        force: Boolean = false,
        fromAutoSkip: Boolean = false
    ) {
        if (index !in playList.indices) return
        if (!force && index == currentIndex && player?.playbackState == Player.STATE_READY) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSwitchAt < SWITCH_MIN_INTERVAL) return
        lastSwitchAt = now

        if (!fromAutoSkip) {
            failedRidsThisRound.clear()
        }

        switchJob?.cancel()
        releasePreparing()
        cancelBufferingTimeout()

        val gen = ++switchGen
        currentIndex = index
        val channel = playList[index]

        hideLiveInfo()

        // 明确已知离线的频道直接跳过到下一在线台（防死循环）
        if (channel.statusKnown && !channel.isLive && !fromAutoSkip && playList.any { it.isLive }) {
            onChannelUnplayable("已离线")
            return
        }

        if (!fromAutoSkip) {
            showTip("正在切换到：${channel.name}", autoHide = false)
        }

        val base = activeBaseUrl()

        switchJob = lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val cached = streamCache[channel.rid]
                    val t = streamCacheTime[channel.rid] ?: 0L
                    if (cached != null && now - t < CACHE_TTL && cached.isNotEmpty()) {
                        cached
                    } else {
                        fetchQualities(base, channel.rid).also {
                            if (it.isNotEmpty()) {
                                streamCache[channel.rid] = it
                                streamCacheTime[channel.rid] = System.currentTimeMillis()
                            }
                        }
                    }
                }

                if (gen != switchGen || !isActive) return@launch

                if (list.isEmpty()) {
                    onChannelUnplayable("无法播放")
                    return@launch
                }

                qualityList = list
                qualityIndex = 0
                prepareThenSwap(gen, list[0].second, channel.name)
            } catch (_: CancellationException) {
                // 被更新的换台请求正常取消
            } catch (e: Exception) {
                if (gen == switchGen) {
                    onChannelUnplayable("无法播放")
                }
            }
        }
    }

    /**
     * 离线或不可播时自动切换下一在线台（通过 failedRidsThisRound 彻底防死循环）
     */
    private fun onChannelUnplayable(reason: String) {
        val cur = playList.getOrNull(currentIndex)
        if (cur != null) failedRidsThisRound.add(cur.rid)
        val name = cur?.name ?: "当前频道"

        val nextOnline = (currentIndex + 1..playList.lastIndex)
            .firstOrNull { playList[it].isLive && playList[it].rid !in failedRidsThisRound }
            ?: (0 until currentIndex)
                .firstOrNull { playList[it].isLive && playList[it].rid !in failedRidsThisRound }

        val next = nextOnline ?: (currentIndex + 1..playList.lastIndex)
            .firstOrNull {
                !(playList[it].statusKnown && !playList[it].isLive) &&
                    playList[it].rid !in failedRidsThisRound
            }
            ?: (0 until currentIndex).firstOrNull {
                !(playList[it].statusKnown && !playList[it].isLive) &&
                    playList[it].rid !in failedRidsThisRound
            }

        if (next == null) {
            showTip("没有更多可播放频道", autoHide = true)
            return
        }

        showTip("$name $reason，正在切换下一台…", autoHide = false)
        switchToChannel(next, force = true, fromAutoSkip = true)
    }

    /**
     * 后台准备新流，STATE_READY 后瞬间替换当前播放器
     */
    private fun prepareThenSwap(gen: Long, hls: String, name: String) {
        if (gen != switchGen || isFinishing || isDestroyed) return
        releasePreparing()

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(8_000, 24_000, 1_500, 4_000)
            .build()
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTunnelingEnabled(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .build()
        }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(TV_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        val next = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        preparingPlayer = next

        next.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (gen != switchGen) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        scheduleBufferingTimeout(gen, next, name)
                        if (preparingPlayer === next) {
                            showTip("正在切换到：$name（缓冲中）", autoHide = false)
                        } else if (player === next) {
                            val qLabel = qualityList.getOrNull(qualityIndex)?.first ?: "默认"
                            showTip("正在缓冲 [$qLabel]...", autoHide = false)
                        }
                    }
                    Player.STATE_READY -> {
                        cancelBufferingTimeout()
                        if (preparingPlayer === next) {
                            swapTo(next, gen, name)
                        } else if (player === next && tipView.visibility == View.VISIBLE) {
                            tipView.postDelayed(hideTipRunnable, 600)
                        }
                    }
                    Player.STATE_ENDED -> {
                        cancelBufferingTimeout()
                        if (player === next) {
                            onChannelUnplayable("直播已结束")
                        }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                if (gen != switchGen) return
                cancelBufferingTimeout()
                if (preparingPlayer === next) {
                    swapTo(next, gen, name)
                } else if (player === next && tipView.visibility == View.VISIBLE) {
                    tipView.removeCallbacks(hideTipRunnable)
                    tipView.postDelayed(hideTipRunnable, 600)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (gen != switchGen) return
                Log.e(TAG, "播放异常: ${error.errorCodeName} | ${error.message}")
                cancelBufferingTimeout()

                if (preparingPlayer === next) {
                    releasePreparing()
                }

                // 自动降级尝试下一清晰度；若全部失败则触发自动切台
                val nextQIdx = qualityIndex + 1
                if (nextQIdx in qualityList.indices) {
                    qualityIndex = nextQIdx
                    prepareThenSwap(gen, qualityList[nextQIdx].second, name)
                } else {
                    playList.getOrNull(currentIndex)?.rid?.let { rid ->
                        streamCache.remove(rid)
                        streamCacheTime.remove(rid)
                    }
                    onChannelUnplayable("无法播放")
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (player === next && videoSize.width > 0 && videoSize.height > 0) {
                    val effectiveW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                    isVideoPortrait = videoSize.height > effectiveW
                    videoStageView.setVideoSize(effectiveW, videoSize.height)
                }
                Log.d(TAG, "视频尺寸已更新: ${videoSize.width}x${videoSize.height}")
            }
        })

        if (gen != switchGen) {
            next.release()
            if (preparingPlayer === next) preparingPlayer = null
            return
        }

        next.setMediaItem(MediaItem.fromUri(hls))
        next.prepare()
        next.playWhenReady = true
        scheduleBufferingTimeout(gen, next, name)
    }

    /**
     * 瞬间切换：先解绑并释放旧播放器，再把新流绑定到单一 SurfaceTexture 舞台
     */
    private fun swapTo(next: ExoPlayer, gen: Long, name: String) {
        if (gen != switchGen || isFinishing || isDestroyed) {
            if (preparingPlayer === next) preparingPlayer = null
            next.release()
            return
        }

        failedRidsThisRound.clear()
        val old = player
        if (preparingPlayer === next) {
            preparingPlayer = null
        }

        videoStageView.detachPlayer(old)
        try {
            old?.stop()
            old?.clearMediaItems()
            old?.release()
        } catch (_: Exception) {}

        onPlayerBound(next)
        showTip("已切换到：$name", autoHide = true)
    }

    private fun releasePreparing() {
        try {
            preparingPlayer?.stop()
            preparingPlayer?.clearMediaItems()
            preparingPlayer?.release()
        } catch (_: Exception) {}
        preparingPlayer = null
    }

    // -------------------- 单解码动态背景配置（默认清晰 ZOOM，可选 ≤5px 轻磨砂，零压暗） --------------------

    private fun applyDynamicBackground() {
        videoStageView.setBackgroundConfig(
            enabled = dynamicBgEnabled,
            blur = allowBlur
        )
    }

    private fun onPlayerBound(exo: ExoPlayer) {
        player = exo
        videoStageView.attachPlayer(exo)
        val vs = exo.videoSize
        if (vs.width > 0 && vs.height > 0) {
            val effectiveW = (vs.width * vs.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            isVideoPortrait = vs.height > effectiveW
            videoStageView.setVideoSize(effectiveW, vs.height)
        }
        applyDynamicBackground()
    }

    // -------------------- 取流与超时 --------------------

    private suspend fun fetchQualities(baseUrl: String, rid: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val base = ChannelManager.normalizeBaseUrl(baseUrl)
            val apiUrl = "$base/live.php?rid=${rid.trim()}"
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", TV_UA)
                    .header("Referer", "$base/")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val httpCode = response.code
                    val body = response.body?.string().orEmpty()
                    Log.d(TAG, "fetchQualities url=$apiUrl code=$httpCode body=${body.take(200)}")
                    if (!response.isSuccessful || body.isBlank()) return@withContext emptyList()
                    val json = JSONObject(body)
                    if (json.optInt("code", 0) != 200) return@withContext emptyList()
                    val streamsObj = json.optJSONObject("streams") ?: return@withContext emptyList()

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
                Log.e(TAG, "解析流地址失败: $rid url=$apiUrl", e)
                emptyList()
            }
        }

    private fun scheduleBufferingTimeout(gen: Long, targetPlayer: ExoPlayer, name: String) {
        cancelBufferingTimeout()
        bufferingTimeoutRunnable = Runnable {
            if (gen != switchGen || isFinishing || isDestroyed) return@Runnable
            if (targetPlayer.playbackState == Player.STATE_BUFFERING &&
                (preparingPlayer === targetPlayer || player === targetPlayer)
            ) {
                val nextQIdx = qualityIndex + 1
                if (nextQIdx in qualityList.indices) {
                    qualityIndex = nextQIdx
                    prepareThenSwap(gen, qualityList[nextQIdx].second, name)
                } else {
                    if (preparingPlayer === targetPlayer) releasePreparing()
                    playList.getOrNull(currentIndex)?.rid?.let { rid ->
                        streamCache.remove(rid)
                        streamCacheTime.remove(rid)
                    }
                    onChannelUnplayable("加载超时")
                }
            }
        }.also {
            mainHandler.postDelayed(it, BUFFERING_TIMEOUT_MS)
        }
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    // -------------------- 选台菜单（仅展示在线频道 + 在线台数） --------------------

    private fun showMenu() {
        if (isMenuShowing) return
        hideLiveInfo()

        val online = playList.mapIndexed { i, c -> i to c }.filter { it.second.isLive }
        if (online.isEmpty()) {
            showTip("当前没有在线频道", autoHide = true)
            return
        }

        isMenuShowing = true
        menuTitleView.text = "在线频道（${online.size}）"
        channelMenuList.removeAllViews()

        online.forEach { (realIdx, ch) ->
            val isCurrent = (realIdx == currentIndex)
            val tv = TextView(this).apply {
                text = (if (isCurrent) "▶ " else "   ") + ch.name
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (isCurrent) Color.parseColor("#4FC3F7") else Color.WHITE)
                textSize = 17f
                setPadding(18, 14, 18, 14)
                isFocusable = true
                isClickable = true
                tag = realIdx

                setOnFocusChangeListener { v, hasFocus ->
                    v.setBackgroundColor(if (hasFocus) Color.parseColor("#33FFFFFF") else Color.TRANSPARENT)
                    (v as TextView).setTextColor(
                        if (hasFocus || (v.tag as Int) == currentIndex) Color.parseColor("#4FC3F7")
                        else Color.WHITE
                    )
                }

                setOnClickListener { v ->
                    val idx = v.tag as Int
                    hideMenu()
                    switchToChannel(idx, force = true)
                }
            }
            channelMenuList.addView(tv)
        }

        channelMenu.visibility = View.VISIBLE
        channelMenu.bringToFront()
        val focus = online.indexOfFirst { it.first == currentIndex }.coerceAtLeast(0)
        channelMenuList.getChildAt(focus)?.requestFocus()
    }

    private fun hideMenu() {
        isMenuShowing = false
        channelMenu.visibility = View.GONE
    }

    // -------------------- OK 键流信息浮层（1 秒实时刷新） --------------------

    private fun toggleInfo() {
        if (isInfoShowing) {
            hideLiveInfo()
            return
        }
        isInfoShowing = true
        renderInfoOverlay()
        infoOverlay.visibility = View.VISIBLE
        infoOverlay.bringToFront()
        mainHandler.removeCallbacks(infoRefreshRunnable)
        mainHandler.postDelayed(infoRefreshRunnable, 1000L)
    }

    private fun renderInfoOverlay() {
        val ch = playList.getOrNull(currentIndex) ?: return
        val p = player
        val vf = p?.videoFormat
        val af = p?.audioFormat
        val formatBitrate = vf?.bitrate?.takeIf { it > 0 } ?: vf?.averageBitrate?.takeIf { it > 0 }
        val estBitrate = DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate.takeIf { it > 0 }
        val kbps = (formatBitrate?.toLong() ?: estBitrate ?: 0L) / 1000L
        val (curQuality, _) = qualityList.getOrNull(qualityIndex) ?: ("默认" to "")

        val playStateText = when {
            p == null -> "未启动"
            p.playbackState == Player.STATE_BUFFERING -> "缓冲中"
            p.playbackState == Player.STATE_ENDED -> "已结束"
            p.isPlaying -> "播放中"
            else -> "已暂停"
        }
        val lineLabel = if (currentLine == 2) {
            "线路二（${StreamLines.LINE_2.removePrefix("https://")}）"
        } else {
            "线路一（${StreamLines.LINE_1.removePrefix("https://")}）"
        }
        val liveStatusText = when {
            ch.isLive -> "在线直播中"
            !ch.statusKnown -> "状态未知"
            else -> "已离线"
        }

        val bgStatusText = when {
            !dynamicBgEnabled -> "关闭（纯黑底）"
            allowBlur -> "开启（≤5px 轻磨砂 · 零压暗）"
            else -> "开启（清晰 ZOOM 铺满 · 零压暗）"
        }

        infoOverlay.text = buildString {
            append("频道：${ch.name}\n")
            append("房间：${ch.rid}\n")
            append("状态：$liveStatusText\n")
            append("清晰度：$curQuality\n")
            append("────────────────\n")
            append("分辨率：${vf?.width ?: "-"} × ${vf?.height ?: "-"}\n")
            append("视频编码：${vf?.sampleMimeType ?: "-"}\n")
            append("音频编码：${af?.sampleMimeType ?: "-"}\n")
            append("码率：${if (kbps > 0) "约 $kbps kbps" else "自适应"}\n")
            append("缓冲：${p?.totalBufferedDuration ?: 0} ms\n")
            append("线路：$lineLabel\n")
            append("动态背景：$bgStatusText\n")
            append("播放状态：$playStateText\n")
            append("────────────────\n（再次按 OK 或返回键关闭）")
        }
    }

    private fun hideLiveInfo() {
        mainHandler.removeCallbacks(infoRefreshRunnable)
        infoOverlay.visibility = View.GONE
        isInfoShowing = false
    }

    // -------------------- 遥控器与按键交互 --------------------

    private fun handleBack() {
        when {
            isMenuShowing -> hideMenu()
            isInfoShowing -> hideLiveInfo()
            else -> {
                releaseAll()
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isMenuShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_BACK -> {
                    hideMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> {
                    val focusedView = currentFocus ?: channelMenuList.focusedChild
                    val target = (focusedView?.tag as? Int)
                    if (target != null) {
                        hideMenu()
                        switchToChannel(target, force = true)
                        return true
                    }
                    return false
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        if (isInfoShowing) {
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

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBack()
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO -> {
                showMenu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                stepChannel(forward = false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                stepChannel(forward = true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                toggleInfo()
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

    private fun stepChannel(forward: Boolean) {
        val onlineIndices = playList.indices.filter { playList[it].isLive }
        if (onlineIndices.isNotEmpty()) {
            val targetIdx = if (forward) {
                onlineIndices.firstOrNull { it > currentIndex }
            } else {
                onlineIndices.lastOrNull { it < currentIndex }
            }
            if (targetIdx != null) {
                switchToChannel(targetIdx)
            } else {
                showTip(
                    if (forward) "没有更多可播放频道" else "已经是第一个在线频道",
                    autoHide = true
                )
            }
        } else {
            val targetIdx = if (forward) currentIndex + 1 else currentIndex - 1
            if (targetIdx in playList.indices) {
                switchToChannel(targetIdx)
            } else {
                showTip(
                    if (forward) "没有更多可播放频道" else "已经是第一个频道",
                    autoHide = true
                )
            }
        }
    }

    private fun showTip(msg: String, autoHide: Boolean) {
        tipView.bringToFront()
        tipView.text = msg
        if (tipView.visibility != View.VISIBLE) {
            tipView.alpha = 0f
            tipView.visibility = View.VISIBLE
        }
        tipView.animate().cancel()
        tipView.animate().alpha(1f).setDuration(150).start()

        tipView.removeCallbacks(hideTipRunnable)
        if (autoHide) {
            tipView.postDelayed(hideTipRunnable, 2000)
        }
    }

    private val hideTipRunnable = Runnable {
        tipView.animate().alpha(0f).setDuration(200).withEndAction {
            tipView.visibility = View.GONE
        }.start()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun releasePlayerOnly() {
        cancelBufferingTimeout()
        val old = player
        videoStageView.detachPlayer(old)
        try {
            old?.stop()
            old?.clearMediaItems()
            old?.release()
        } catch (_: Exception) {}
        player = null
    }

    private fun releaseAll() {
        switchJob?.cancel()
        switchGen++
        hideLiveInfo()
        tipView.removeCallbacks(hideTipRunnable)
        releasePreparing()
        releasePlayerOnly()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(infoRefreshRunnable)
        player?.playWhenReady = false
        releasePreparing()
        cancelBufferingTimeout()
    }

    override fun onResume() {
        super.onResume()
        videoStageView.onResume()
    }

    override fun onPause() {
        videoStageView.onPause()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        dynamicBgEnabled = preferences.dynamicBgEnabled
        allowBlur = preferences.dynamicBgBlurEnabled
        applyDynamicBackground()
        player?.playWhenReady = true
        if (isInfoShowing) {
            mainHandler.removeCallbacks(infoRefreshRunnable)
            mainHandler.postDelayed(infoRefreshRunnable, 1000L)
        }
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
