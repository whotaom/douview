package com.example.tv.player

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
 * 电视大屏全屏直播播放器（极简高稳单个 PlayerView 架构）
 * 1. 仅使用单个原生 SurfaceView PlayerView（RESIZE_MODE_FIT），纯暗底显示，彻底杜绝动态背景与表面冲突导致的黑屏
 * 2. 「先备后切」(prepare-then-swap)：冷启动直绑 Surface；切台后台静默缓冲至 READY 再瞬切并立即释放旧解码器
 * 3. 硬件解码首帧超时（4.5s）或解码异常自动切软件兼容解码；支持直播窗口落后（BEHIND_LIVE_WINDOW）无缝回拉直播前沿
 * 4. 菜单仅展示在线频道并带实时在线台数「在线频道（N）」；OK 键技术参数面板 1 秒实时刷新
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_BASE_URL = StreamLines.LINE_1
        private const val TAG = "TVPlayer"
        private const val TV_UA = ChannelManager.TV_UA
        private const val FIRST_FRAME_WATCHDOG_MS = 4_500L

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

        fun normalizeStreamUrl(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            return if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        }

        fun buildLiveMediaItem(streamUrl: String): MediaItem {
            return MediaItem.Builder()
                .setUri(streamUrl)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(4_000L)
                        .setMinOffsetMs(1_500L)
                        .setMaxOffsetMs(12_000L)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )
                .build()
        }

        enum class RemoteAction {
            STEP_PREV_CHANNEL,
            STEP_NEXT_CHANNEL,
            OPEN_MENU,
            CLOSE_MENU,
            MENU_CONFIRM,
            TOGGLE_INFO,
            CLOSE_INFO,
            MEDIA_PLAY,
            MEDIA_PAUSE,
            MEDIA_PLAY_PAUSE,
            BACK_EXIT,
            NONE
        }

        fun resolveRemoteAction(
            keyCode: Int,
            isMenuShowing: Boolean,
            isInfoShowing: Boolean
        ): RemoteAction {
            if (isMenuShowing) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_BACK -> RemoteAction.CLOSE_MENU
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteAction.MENU_CONFIRM
                    else -> RemoteAction.NONE
                }
            }
            if (isInfoShowing) {
                return when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_MENU -> RemoteAction.CLOSE_INFO
                    else -> RemoteAction.NONE
                }
            }
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> RemoteAction.BACK_EXIT
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO -> RemoteAction.OPEN_MENU
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> RemoteAction.STEP_PREV_CHANNEL
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT -> RemoteAction.STEP_NEXT_CHANNEL
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteAction.TOGGLE_INFO
                KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteAction.MEDIA_PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteAction.MEDIA_PAUSE
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteAction.MEDIA_PLAY_PAUSE
                else -> RemoteAction.NONE
            }
        }

        fun shouldIgnoreRepeatKey(keyCode: Int, repeatCount: Int): Boolean {
            if (repeatCount <= 0) return false
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        }
    }

    private lateinit var channelManager: ChannelManager
    private lateinit var preferences: LivePreferences

    // UI 组件（单个 PlayerView + TV 安全区交互浮层）
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView            // 唯一全屏主播放器（SurfaceView + RESIZE_MODE_FIT）
    private lateinit var tipView: TextView                 // 顶部高层级换台与状态提示（与缓冲胶囊互斥）
    private lateinit var bufferingPill: LinearLayout       // 顶部安全区居中缓冲指示胶囊
    private lateinit var bufferingText: TextView
    private lateinit var errorOverlay: LinearLayout        // 可遥控操作的高对比度失败兜底面板
    private lateinit var errorTitleView: TextView
    private lateinit var errorDetailView: TextView
    private lateinit var btnRetryChannel: Button
    private lateinit var btnNextChannel: Button
    private lateinit var btnExitPlayer: Button
    private lateinit var channelMenu: FrameLayout          // 左侧在线选台悬浮面板
    private lateinit var menuTitleView: TextView           // 选台菜单标题：「在线频道（N）」
    private lateinit var channelMenuList: LinearLayout
    private lateinit var infoScrollView: ScrollView        // OK 键技术参数可滚动外框（防止小屏底部截断）
    private lateinit var infoOverlay: TextView             // OK 键技术参数浮层（1s 实时刷新）

    // 主播放器与后台备播播放器
    private var player: ExoPlayer? = null
    private var preparingPlayer: ExoPlayer? = null
    private var isMenuShowing = false
    private var isInfoShowing = false
    private var wasStoppedInBackground = false

    // 解码自愈状态
    private var hasRenderedFirstFrameForCurrent: Boolean = false
    private var preferSoftwareDecoderForCurrent: Boolean = false
    private var activeStreamUrl: String = ""
    private var activeChannelName: String = ""
    private var firstFrameWatchdogRunnable: Runnable? = null

    // 播放列表与当前索引
    private var playList: List<Channel> = emptyList()
    private var currentIndex = 0
    private var qualityList: List<Pair<String, String>> = emptyList()
    private var qualityIndex = 0

    // 单飞 Job、防竞态、自动跳台防死循环
    private var switchJob: Job? = null
    private var probeJob: Job? = null
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
        preferences = LivePreferences(this)
        channelManager = ChannelManager(this)
        buildUi()

        setupPlayListAndStart()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        failedRidsThisRound.clear()
        preferSoftwareDecoderForCurrent = false
        setupPlayListAndStart()
    }

    private fun activeBaseUrl(): String {
        val extraBase = intent.getStringExtra(EXTRA_BASE_URL)?.trim()
        if (!extraBase.isNullOrBlank()) {
            return StreamLines.resolveBaseUrl(extraBase)
        }
        return preferences.activeBaseUrl
    }

    // -------------------- UI 结构构建 --------------------

    @OptIn(UnstableApi::class)
    private fun buildUi() {
        setContentView(R.layout.activity_tv_fullscreen_player)
        root = findViewById(R.id.rootContainer)
        playerView = findViewById(R.id.playerView)
        tipView = findViewById(R.id.tipView)
        bufferingPill = findViewById(R.id.bufferingPill)
        bufferingText = findViewById(R.id.bufferingText)
        errorOverlay = findViewById(R.id.errorOverlay)
        errorTitleView = findViewById(R.id.errorTitleView)
        errorDetailView = findViewById(R.id.errorDetailView)
        btnRetryChannel = findViewById(R.id.btnRetryChannel)
        btnNextChannel = findViewById(R.id.btnNextChannel)
        btnExitPlayer = findViewById(R.id.btnExitPlayer)

        val darkBgColor = Color.parseColor("#09090C")
        root.setBackgroundColor(darkBgColor)

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setBackgroundColor(darkBgColor)
        playerView.setShutterBackgroundColor(darkBgColor)
        playerView.keepScreenOn = preferences.keepScreenOn
        playerView.isFocusable = false
        playerView.isFocusableInTouchMode = false
        playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        val safeScreenWidth = (resources.displayMetrics.widthPixels - 96.dp()).coerceAtLeast(280.dp())
        errorOverlay.layoutParams = (errorOverlay.layoutParams as FrameLayout.LayoutParams).apply {
            width = safeScreenWidth.coerceAtMost(540.dp())
        }

        val bindErrorBtnFocusStyle = { btn: Button ->
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate()
                    .scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f)
                    .setDuration(120)
                    .start()
                v.alpha = if (hasFocus) 1.0f else 0.85f
            }
        }
        bindErrorBtnFocusStyle(btnRetryChannel)
        bindErrorBtnFocusStyle(btnNextChannel)
        bindErrorBtnFocusStyle(btnExitPlayer)

        btnRetryChannel.setOnClickListener {
            hideErrorOverlay()
            failedRidsThisRound.clear()
            switchToChannel(currentIndex, force = true)
        }
        btnNextChannel.setOnClickListener {
            hideErrorOverlay()
            failedRidsThisRound.clear()
            val nextIdx = if (playList.isNotEmpty()) (currentIndex + 1) % playList.size else 0
            switchToChannel(nextIdx, force = true)
        }
        btnExitPlayer.setOnClickListener {
            releaseAll()
            finish()
        }

        // 1. 左侧在线选台悬浮面板（遵循 TV 48dp 左侧与 27dp 上下过扫描安全区）
        channelMenu = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                safeScreenWidth.coerceAtMost(420.dp()),
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.gravity = Gravity.START
            }
            setBackgroundColor(Color.parseColor("#EB101218"))
            setPadding(48.dp(), 27.dp(), 16.dp(), 27.dp())
            visibility = View.GONE
            elevation = 24f
            isClickable = true
            isFocusable = false
        }

        menuTitleView = TextView(this).apply {
            text = "在线频道（0）"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(12.dp(), 8.dp(), 12.dp(), 8.dp())
        }
        channelMenuList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp(), 0, 4.dp(), 12.dp())
        }
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).also { it.topMargin = 48.dp() }
            isFocusable = false
            addView(channelMenuList)
        }
        channelMenu.addView(menuTitleView)
        channelMenu.addView(scroll)
        root.addView(channelMenu)

        // 2. OK 键技术参数信息面板（可滚动容器 + 顶部留出 HUD 安全距，彻底杜绝截断与重叠）
        infoScrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                safeScreenWidth.coerceAtMost(540.dp()),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.CENTER
                it.leftMargin = 48.dp()
                it.rightMargin = 48.dp()
                it.topMargin = 72.dp()
                it.bottomMargin = 27.dp()
            }
            background = ContextCompat.getDrawable(this@TvFullscreenPlayerActivity, R.drawable.bg_tv_overlay_panel)
            elevation = 48f
            visibility = View.GONE
            isVerticalScrollBarEnabled = true
            isFocusable = false
        }
        infoOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24.dp(), 16.dp(), 24.dp(), 16.dp())
            setLineSpacing(2f, 1.12f)
        }
        infoScrollView.addView(infoOverlay)
        root.addView(infoScrollView)
    }

    // -------------------- 播放与切台核心逻辑 --------------------

    private fun setupPlayListAndStart() {
        probeJob?.cancel()

        val directStreamUrl = normalizeStreamUrl(intent.getStringExtra(EXTRA_STREAM_URL))
        if (directStreamUrl.isNotBlank()) {
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
                val normalized = normalizeStreamUrl(rawUrl)
                playList = listOf(Channel(rid = "direct", name = "直接流媒体", isLive = true, statusKnown = true))
                currentIndex = 0
                qualityList = listOf("默认流" to normalized)
                qualityIndex = 0
                prepareThenSwap(++switchGen, normalized, "直接流媒体")
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
                sortedChannels.add(
                    0,
                    Channel(
                        rid = targetRid,
                        name = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "自定义频道 ($targetRid)",
                        isLive = true,
                        statusKnown = false
                    )
                )
                currentIndex = 0
            }
        } else {
            currentIndex = 0
        }

        playList = sortedChannels
        switchToChannel(currentIndex, force = true)

        // 异步与当前线路同源测活，保证菜单展示真实在线台数与状态并锁定 currentIndex 不偏移
        val base = activeBaseUrl()
        probeJob = lifecycleScope.launch {
            val outcome = channelManager.probeLiveStatus(base, playList)
            if (!isActive || outcome.networkFailed) return@launch
            val playingRid = playList.getOrNull(currentIndex)?.rid
            val sorted = outcome.channels.sortedWith(
                compareBy { if (it.isLive) 0 else if (!it.statusKnown) 1 else 2 }
            )
            playList = sorted
            if (playingRid != null) {
                val syncedIdx = sorted.indexOfFirst { it.rid == playingRid }
                if (syncedIdx >= 0) {
                    currentIndex = syncedIdx
                }
            }
            channelManager.saveChannels(sorted)
        }
    }

    /**
     * 单飞换台（「先备后切」）：
     * 1. 取消未完成的换台协程并释放未上屏的备播 Player
     * 2. 换台过程中不停止当前正在播放的旧流，避免中途黑场或断音
     * 3. 新流在后台备播至 STATE_READY 后，瞬时切换并释放旧播放器
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
        hideErrorOverlay()

        // 明确已知离线的频道直接跳过到下一在线台（防死循环）
        if (channel.statusKnown && !channel.isLive && !fromAutoSkip && playList.any { it.isLive }) {
            onChannelUnplayable("已离线")
            return
        }

        showBuffering("准备切换到：${channel.name}")
        preferSoftwareDecoderForCurrent = false

        val base = activeBaseUrl()

        switchJob = lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val cached = streamCache[channel.rid]
                    val t = streamCacheTime[channel.rid] ?: 0L
                    if (cached != null && now - t < CACHE_TTL && cached.isNotEmpty()) {
                        cached
                    } else {
                        fetchQualities(base, channel.rid).also { fetched ->
                            if (fetched.isNotEmpty()) {
                                streamCache[channel.rid] = fetched
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
            hideBuffering()
            showErrorOverlay(
                title = "暂无可播放信号",
                detail = "$name $reason，本轮全部候选频道均暂时不可用。\n可使用遥控器重试当前频道、尝试下一台或返回主页。"
            )
            return
        }

        showTip("$name $reason，正在切换下一台…", autoHide = false)
        switchToChannel(next, force = true, fromAutoSkip = true)
    }

    /**
     * 准备新流并切换：
     * - 冷启动（player == null）：直接绑定 playerView 再 prepare，确保首帧 IDR 直接落到 SurfaceView
     * - 换台（player != null）：新播放器以 playWhenReady = false 后台静默缓冲至 STATE_READY，保持旧流不断；
     *   就绪瞬间切流并立即释放旧播放器，避免低端 TV 盒子双路硬件 VPU 内存抢占导致的黑屏
     */
    @OptIn(UnstableApi::class)
    private fun prepareThenSwap(
        gen: Long,
        hls: String,
        name: String,
        preferSoftwareDecoder: Boolean = preferSoftwareDecoderForCurrent
    ) {
        if (gen != switchGen || isFinishing || isDestroyed) return
        releasePreparing()
        cancelFirstFrameWatchdog()

        preferSoftwareDecoderForCurrent = preferSoftwareDecoder
        activeStreamUrl = hls
        activeChannelName = name

        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setMediaCodecSelector(buildMediaCodecSelector(preferSoftwareDecoder))
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 25_000, 1_500, 4_000)
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

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val liveSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(0.97f)
            .setFallbackMaxPlaybackSpeed(1.03f)
            .build()

        val next = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLivePlaybackSpeedControl(liveSpeedControl)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val isColdStart = (player == null)
        if (isColdStart) {
            player = next
            preparingPlayer = null
            hasRenderedFirstFrameForCurrent = false
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView.player = next
        } else {
            preparingPlayer = next
        }

        next.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (gen != switchGen) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        scheduleBufferingTimeout(gen, next, name)
                        val qLabel = qualityList.getOrNull(qualityIndex)?.first ?: "默认"
                        if (preparingPlayer === next) {
                            showBuffering("正在切换：$name [$qLabel]")
                        } else if (player === next) {
                            showBuffering("正在缓冲：$name [$qLabel]")
                        }
                    }
                    Player.STATE_READY -> {
                        cancelBufferingTimeout()
                        hideBuffering()
                        hideErrorOverlay()
                        if (preparingPlayer === next) {
                            swapTo(next, gen, name)
                        } else if (player === next) {
                            if (!hasRenderedFirstFrameForCurrent &&
                                next.playWhenReady &&
                                next.videoFormat != null
                            ) {
                                scheduleFirstFrameWatchdog(gen, next)
                            }
                            if (tipView.visibility == View.VISIBLE) {
                                tipView.postDelayed(hideTipRunnable, 600)
                            }
                        }
                    }
                    Player.STATE_ENDED -> {
                        cancelBufferingTimeout()
                        hideBuffering()
                        if (player === next) {
                            onChannelUnplayable("直播已结束")
                        }
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                if (gen != switchGen) return
                cancelBufferingTimeout()
                hideBuffering()
                hideErrorOverlay()
                Log.i(
                    TAG,
                    "onRenderedFirstFrame model=${Build.MODEL} sw=$preferSoftwareDecoderForCurrent channel=$name size=${next.videoSize.width}x${next.videoSize.height}"
                )
                if (preparingPlayer === next) {
                    swapTo(next, gen, name)
                }
                if (player === next) {
                    cancelFirstFrameWatchdog()
                    hasRenderedFirstFrameForCurrent = true
                    if (tipView.visibility == View.VISIBLE) {
                        tipView.removeCallbacks(hideTipRunnable)
                        tipView.postDelayed(hideTipRunnable, 600)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (gen != switchGen) return
                Log.e(TAG, "播放异常: ${error.errorCodeName} | ${error.message}")
                cancelBufferingTimeout()
                cancelFirstFrameWatchdog()

                // 直播分片窗口过期时自动拉回最新直播前沿继续播，绝不误判下播
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player === next) {
                    next.seekToDefaultPosition()
                    next.prepare()
                    next.playWhenReady = true
                    return
                }

                if (!preferSoftwareDecoderForCurrent && isLikelyDecoderOrRenderError(error)) {
                    retryWithSoftwareDecoder(gen, "解码器异常自动切软件兼容解码")
                    return
                }

                if (preparingPlayer === next) {
                    releasePreparing()
                } else if (player === next) {
                    releasePlayerOnly()
                }

                // 自动降级尝试下一清晰度；若全部失败则触发自动切台
                val nextQIdx = qualityIndex + 1
                if (nextQIdx in qualityList.indices) {
                    qualityIndex = nextQIdx
                    preferSoftwareDecoderForCurrent = false
                    prepareThenSwap(gen, qualityList[nextQIdx].second, name, preferSoftwareDecoder = false)
                } else {
                    playList.getOrNull(currentIndex)?.rid?.let { rid ->
                        streamCache.remove(rid)
                        streamCacheTime.remove(rid)
                    }
                    onChannelUnplayable("无法播放")
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.d(TAG, "视频尺寸已更新: ${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio}")
            }
        })

        if (gen != switchGen) {
            next.release()
            if (preparingPlayer === next) preparingPlayer = null
            if (player === next) player = null
            return
        }

        next.playWhenReady = isColdStart
        next.setMediaItem(buildLiveMediaItem(hls))
        next.prepare()
        scheduleBufferingTimeout(gen, next, name)
    }

    /**
     * 瞬间切换（零黑屏空窗且避免双 VPU 解码抢占）：
     * 1. 先赋值 playerView.player = next（内部自动解除旧 Surface，零 null 空窗）
     * 2. 立即释放旧播放器，腾出硬件 VPU 解码通道与 CMA 内存
     * 3. 置 next.playWhenReady = true，使新流从首关键帧直接解码渲染到 SurfaceView
     */
    @OptIn(UnstableApi::class)
    private fun swapTo(next: ExoPlayer, gen: Long, name: String) {
        if (gen != switchGen || isFinishing || isDestroyed) {
            if (preparingPlayer === next) preparingPlayer = null
            next.release()
            return
        }

        cancelFirstFrameWatchdog()
        failedRidsThisRound.clear()
        val old = player
        player = next
        if (preparingPlayer === next) {
            preparingPlayer = null
        }

        hasRenderedFirstFrameForCurrent = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.player = next

        try {
            old?.stop()
            old?.clearMediaItems()
            old?.release()
        } catch (_: Exception) {}

        next.playWhenReady = true

        if (next.playbackState == Player.STATE_READY &&
            next.playWhenReady &&
            next.videoFormat != null
        ) {
            scheduleFirstFrameWatchdog(gen, next)
        }

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

    @OptIn(UnstableApi::class)
    private fun buildMediaCodecSelector(preferSoftware: Boolean): MediaCodecSelector {
        if (!preferSoftware) return MediaCodecSelector.DEFAULT
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            all.sortedBy { info ->
                val isSw = info.softwareOnly ||
                    info.name.startsWith("c2.android.", ignoreCase = true) ||
                    info.name.startsWith("OMX.google.", ignoreCase = true)
                if (isSw) 0 else 1
            }
        }
    }

    private fun isLikelyDecoderOrRenderError(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> true
            else -> error.message?.contains("surface", ignoreCase = true) == true
        }
    }

    private fun scheduleFirstFrameWatchdog(gen: Long, targetPlayer: ExoPlayer) {
        cancelFirstFrameWatchdog()
        if (hasRenderedFirstFrameForCurrent || preferSoftwareDecoderForCurrent) return
        firstFrameWatchdogRunnable = Runnable {
            if (isFinishing || isDestroyed || gen != switchGen) return@Runnable
            if (player === targetPlayer &&
                !hasRenderedFirstFrameForCurrent &&
                !preferSoftwareDecoderForCurrent &&
                targetPlayer.playbackState == Player.STATE_READY &&
                targetPlayer.playWhenReady &&
                targetPlayer.videoFormat != null
            ) {
                retryWithSoftwareDecoder(gen, "硬件解码首帧超时(${FIRST_FRAME_WATCHDOG_MS}ms)")
            }
        }.also {
            mainHandler.postDelayed(it, FIRST_FRAME_WATCHDOG_MS)
        }
    }

    private fun cancelFirstFrameWatchdog() {
        firstFrameWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        firstFrameWatchdogRunnable = null
    }

    private fun retryWithSoftwareDecoder(gen: Long, reason: String) {
        if (gen != switchGen || preferSoftwareDecoderForCurrent || isFinishing || isDestroyed) return
        val url = activeStreamUrl
        if (url.isBlank()) return
        val name = activeChannelName.ifBlank { playList.getOrNull(currentIndex)?.name ?: "当前频道" }
        Log.w(TAG, "硬件解码未出画，切软件兼容解码重试: reason=$reason model=${Build.MODEL}")
        showTip("正切换兼容解码模式…", autoHide = true)
        releasePreparing()
        releasePlayerOnly()
        prepareThenSwap(gen, url, name, preferSoftwareDecoder = true)
    }

    // -------------------- 取流与超时 --------------------

    private suspend fun fetchQualities(
        baseUrl: String,
        rid: String
    ): List<Pair<String, String>> =
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
                        val streamUrl = normalizeStreamUrl(
                            streamItem?.optString("hls")?.takeIf { it.isNotBlank() }
                                ?: streamItem?.optString("url")
                        )
                        if (streamUrl.isNotBlank()) {
                            val name = streamItem?.optString("name", key)?.ifBlank { key } ?: key
                            result.add(name to streamUrl)
                        }
                    }

                    val keysIterator = streamsObj.keys()
                    while (keysIterator.hasNext()) {
                        val key = keysIterator.next()
                        if (key !in priorityKeys) {
                            val streamItem = streamsObj.optJSONObject(key)
                            val streamUrl = normalizeStreamUrl(
                                streamItem?.optString("hls")?.takeIf { it.isNotBlank() }
                                    ?: streamItem?.optString("url")
                            )
                            if (streamUrl.isNotBlank() && result.none { it.second == streamUrl }) {
                                val name = streamItem?.optString("name", key)?.ifBlank { key } ?: key
                                result.add(name to streamUrl)
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
                    preferSoftwareDecoderForCurrent = false
                    prepareThenSwap(gen, qualityList[nextQIdx].second, name, preferSoftwareDecoder = false)
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
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = 48.dp()
                setPadding(14.dp(), 10.dp(), 14.dp(), 10.dp())
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
        val focusTarget = channelMenuList.getChildAt(focus)
        focusTarget?.post { focusTarget.requestFocus() }
    }

    private fun hideMenu() {
        isMenuShowing = false
        channelMenuList.clearFocus()
        channelMenu.visibility = View.GONE
    }

    // -------------------- OK 键流信息浮层（1 秒实时刷新） --------------------

    private fun toggleInfo() {
        if (isInfoShowing) {
            hideLiveInfo()
            return
        }
        isInfoShowing = true
        tipView.animate().cancel()
        tipView.removeCallbacks(hideTipRunnable)
        tipView.visibility = View.GONE
        renderInfoOverlay()
        infoScrollView.scrollTo(0, 0)
        infoScrollView.visibility = View.VISIBLE
        infoScrollView.bringToFront()
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
        val baseHost = activeBaseUrl().removePrefix("https://").removePrefix("http://")
        val lineLabel = if (baseHost.equals(StreamLines.LINE_1.removePrefix("https://"), ignoreCase = true)) {
            "默认主线（$baseHost）"
        } else {
            baseHost
        }
        val liveStatusText = when {
            ch.isLive -> "在线直播中"
            !ch.statusKnown -> "状态未知"
            else -> "已离线"
        }

        val decodeDesc = if (preferSoftwareDecoderForCurrent) "软件兼容解码" else "硬件直出解码"

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
            append("解码引擎：$decodeDesc（首帧：${if (hasRenderedFirstFrameForCurrent) "已出画" else "等待中"}）\n")
            append("播放状态：$playStateText\n")
            append("────────────────\n（再次按 OK 或返回键关闭）")
        }
    }

    private fun hideLiveInfo() {
        mainHandler.removeCallbacks(infoRefreshRunnable)
        infoScrollView.visibility = View.GONE
        isInfoShowing = false
    }

    // -------------------- 遥控器与按键交互 --------------------

    private fun handleBack() {
        when {
            isMenuShowing -> hideMenu()
            isInfoShowing -> hideLiveInfo()
            errorOverlay.visibility == View.VISIBLE -> {
                releaseAll()
                finish()
            }
            else -> {
                releaseAll()
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (shouldIgnoreRepeatKey(keyCode, event?.repeatCount ?: 0)) {
            return true
        }
        if (errorOverlay.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                handleBack()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        val action = resolveRemoteAction(keyCode, isMenuShowing, isInfoShowing)
        when (action) {
            RemoteAction.CLOSE_MENU -> {
                hideMenu()
                return true
            }
            RemoteAction.MENU_CONFIRM -> {
                val focusedView = currentFocus ?: channelMenuList.focusedChild
                val target = focusedView?.tag as? Int
                if (target != null) {
                    hideMenu()
                    switchToChannel(target, force = true)
                    return true
                }
                return false
            }
            RemoteAction.CLOSE_INFO -> {
                hideLiveInfo()
                return true
            }
            RemoteAction.BACK_EXIT -> {
                handleBack()
                return true
            }
            RemoteAction.OPEN_MENU -> {
                showMenu()
                return true
            }
            RemoteAction.STEP_PREV_CHANNEL -> {
                stepChannel(forward = false)
                return true
            }
            RemoteAction.STEP_NEXT_CHANNEL -> {
                stepChannel(forward = true)
                return true
            }
            RemoteAction.TOGGLE_INFO -> {
                toggleInfo()
                return true
            }
            RemoteAction.MEDIA_PLAY -> {
                player?.let {
                    it.play()
                    showTip("继续播放", autoHide = true)
                }
                return true
            }
            RemoteAction.MEDIA_PAUSE -> {
                player?.let {
                    it.pause()
                    showTip("已暂停", autoHide = true)
                }
                return true
            }
            RemoteAction.MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) {
                        it.pause()
                        showTip("已暂停", autoHide = true)
                    } else {
                        it.play()
                        showTip("继续播放", autoHide = true)
                    }
                }
                return true
            }
            RemoteAction.NONE -> {
                return super.onKeyDown(keyCode, event)
            }
        }
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

    private fun showBuffering(message: String) {
        tipView.animate().cancel()
        tipView.removeCallbacks(hideTipRunnable)
        tipView.visibility = View.GONE
        bufferingText.text = message
        if (bufferingPill.visibility != View.VISIBLE) {
            bufferingPill.visibility = View.VISIBLE
        }
        bufferingPill.bringToFront()
        if (isInfoShowing) {
            infoScrollView.bringToFront()
        }
    }

    private fun hideBuffering() {
        bufferingPill.visibility = View.GONE
    }

    private fun showErrorOverlay(title: String, detail: String) {
        hideLiveInfo()
        errorTitleView.text = title
        errorDetailView.text = detail
        errorOverlay.visibility = View.VISIBLE
        errorOverlay.bringToFront()
        btnRetryChannel.post { btnRetryChannel.requestFocus() }
    }

    private fun hideErrorOverlay() {
        errorOverlay.visibility = View.GONE
    }

    private fun showTip(msg: String, autoHide: Boolean) {
        hideBuffering()
        tipView.bringToFront()
        if (isInfoShowing) {
            infoScrollView.bringToFront()
        }
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
        cancelFirstFrameWatchdog()
        playerView.player = null
        try {
            player?.stop()
            player?.clearMediaItems()
            player?.release()
        } catch (_: Exception) {}
        player = null
    }

    private fun releaseAll() {
        switchJob?.cancel()
        probeJob?.cancel()
        cancelFirstFrameWatchdog()
        switchGen++
        hideLiveInfo()
        tipView.removeCallbacks(hideTipRunnable)
        releasePreparing()
        releasePlayerOnly()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(infoRefreshRunnable)
        cancelFirstFrameWatchdog()
        wasStoppedInBackground = true
        player?.playWhenReady = false
        releasePreparing()
        cancelBufferingTimeout()
    }

    override fun onStart() {
        super.onStart()
        player?.let { active ->
            if (wasStoppedInBackground) {
                wasStoppedInBackground = false
                if (active.isCurrentMediaItemLive) {
                    active.seekToDefaultPosition()
                }
            }
            active.playWhenReady = true
            if (!hasRenderedFirstFrameForCurrent &&
                active.playbackState == Player.STATE_READY &&
                active.videoFormat != null
            ) {
                scheduleFirstFrameWatchdog(switchGen, active)
            }
        }
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
