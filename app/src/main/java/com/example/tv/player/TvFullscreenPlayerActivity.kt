package com.example.tv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.media3.ui.PlayerView
import com.example.R
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
 * 电视大屏全屏直播播放器（TV 标准方案）
 * 1. 单一全屏 PlayerView 铺满整个电视屏幕（RESIZE_MODE_FIT 保证画面不拉伸畸变，彻底杜绝“中间只有一小块”问题）
 * 2. 单 ExoPlayer 驱动，硬件解码降级与电视低功耗缓冲策略，完美兼容 RK3528 等 32 位电视盒子
 * 3. 单飞 Job + switchGen 换台防竞态，先 stop/clear 再起播，避免连续换台失效或旧台覆盖新台
 * 4. 换台反馈与首帧渲染/就绪深度绑定，真正出画后才淡出
 * 5. 菜单仅筛选在线直播频道，左侧 400dp 无全屏遮罩
 * 6. OK 键实时调出直播流技术指标面板（编码、分辨率、码率、缓冲时长）
 */
class TvFullscreenPlayerActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_BASE_URL = "https://tv.fuxiaoya.com"
        private const val TAG = "TVPlayer"
        private const val TV_UA =
            "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_RID = "extra_rid"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_BASE_URL = "extra_base_url"
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

    // UI 组件
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView            // 单一真全屏播放器
    private lateinit var tipView: TextView                 // 顶部高层级换台与状态提示
    private lateinit var channelMenu: FrameLayout          // 左侧在线选台悬浮面板
    private lateinit var channelMenuList: LinearLayout
    private lateinit var infoOverlay: TextView             // OK 键技术参数浮层

    private var player: ExoPlayer? = null
    private var isMenuShowing = false
    private var isInfoShowing = false

    // 播放列表与当前索引
    private var playList: List<Channel> = emptyList()
    private var currentIndex = 0
    private var qualityList: List<Pair<String, String>> = emptyList()
    private var qualityIndex = 0

    // 单飞 Job 与换台防竞态
    private var switchJob: Job? = null
    private var switchGen = 0L
    private var lastSwitchAt = 0L
    private val SWITCH_MIN_INTERVAL = 350L
    private var pendingTipForChannel: String? = null

    // 缓冲超时监测 (12秒连续缓冲自动降清晰度)
    private var bufferingTimeoutRunnable: Runnable? = null
    private val BUFFERING_TIMEOUT_MS = 12_000L

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        buildUi()

        channelManager = ChannelManager(this)
        setupPlayListAndStart()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    // -------------------- UI 结构构建 --------------------

    private fun buildUi() {
        setContentView(R.layout.activity_tv_fullscreen_player)
        root = findViewById(R.id.rootContainer)
        playerView = findViewById(R.id.playerView)
        tipView = findViewById(R.id.tipView)

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

        val menuTitle = TextView(this).apply {
            text = "在线频道"
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
        channelMenu.addView(menuTitle)
        channelMenu.addView(scroll)
        root.addView(channelMenu)

        // 2. OK 键技术参数信息面板
        infoOverlay = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(480.dp(), FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER
            }
            setBackgroundColor(Color.parseColor("#E6101010"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(24, 20, 24, 20)
            elevation = 48f
            visibility = View.GONE
            setLineSpacing(5f, 1.18f)
        }
        root.addView(infoOverlay)
    }

    // -------------------- 播放与切台核心逻辑 --------------------

    private fun setupPlayListAndStart() {
        val directStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.trim()
        if (!directStreamUrl.isNullOrBlank()) {
            playList = listOf(Channel(rid = "direct", name = "直接媒体流", isLive = true))
            currentIndex = 0
            qualityList = listOf("默认流" to directStreamUrl)
            qualityIndex = 0
            playQuality(switchGen)
            return
        }

        val loadedChannels = channelManager.loadChannels()
        val sortedChannels = loadedChannels.sortedBy { if (it.isLive) 0 else 1 }.toMutableList()

        var targetRid: String? = intent.getStringExtra(EXTRA_RID)?.trim()
        val rawUrl = intent.getStringExtra(EXTRA_URL)?.trim() ?: ""

        if (targetRid.isNullOrBlank() && rawUrl.isNotBlank()) {
            if (rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8?")) {
                playList = listOf(Channel(rid = "direct", name = "直接流媒体", isLive = true))
                currentIndex = 0
                qualityList = listOf("默认流" to rawUrl)
                qualityIndex = 0
                playQuality(switchGen)
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
                sortedChannels.add(0, Channel(rid = targetRid, name = "自定义频道 ($targetRid)", isLive = true))
                currentIndex = 0
            }
        } else {
            currentIndex = 0
        }

        playList = sortedChannels
        switchToChannel(currentIndex, force = true)

        // 异步后台拉取最新批次在线状态，保证后续选台菜单为真实在线
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
        lifecycleScope.launch {
            val updated = channelManager.checkLiveStatusViaWeb(baseUrl, playList)
            playList = updated.sortedBy { if (it.isLive) 0 else 1 }
            channelManager.saveChannels(playList)
        }
    }

    /**
     * 单飞换台（带 Job.cancel、switchGen 防竞态、出画绑定反馈）
     */
    private fun switchToChannel(index: Int, force: Boolean = false) {
        if (index !in playList.indices) return

        val now = System.currentTimeMillis()
        if (!force && now - lastSwitchAt < SWITCH_MIN_INTERVAL) return
        lastSwitchAt = now

        // 1. 取消上一次未完成的换台协程
        switchJob?.cancel()
        val gen = ++switchGen
        currentIndex = index
        val channel = playList[index]

        hideLiveInfo()
        pendingTipForChannel = channel.name
        showTip("正在切换到：${channel.name}", autoHide = false)

        // 2. 立即打断当前播放，避免“听着旧台、看着新台提示”
        try {
            player?.pause()
            player?.stop()
            player?.clearMediaItems()
        } catch (_: Exception) {}

        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL

        switchJob = lifecycleScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val cached = streamCache[channel.rid]
                    val t = streamCacheTime[channel.rid] ?: 0L
                    if (cached != null && now - t < CACHE_TTL && cached.isNotEmpty()) {
                        cached
                    } else {
                        fetchQualities(baseUrl, channel.rid).also {
                            if (it.isNotEmpty()) {
                                streamCache[channel.rid] = it
                                streamCacheTime[channel.rid] = System.currentTimeMillis()
                            }
                        }
                    }
                }

                // 3. 已过期的换台请求直接丢弃
                if (gen != switchGen || !isActive) return@launch

                if (list.isEmpty()) {
                    pendingTipForChannel = null
                    showTip("「${channel.name}」暂无法播放", autoHide = true)
                    // 可选自动尝试下一个可用频道
                    if (currentIndex < playList.lastIndex) {
                        playerView.postDelayed({
                            if (gen == switchGen) switchToChannel(currentIndex + 1)
                        }, 1200)
                    }
                    return@launch
                }

                qualityList = list
                qualityIndex = 0
                playQuality(gen)
            } catch (_: CancellationException) {
                // 正常被更新的换台动作取消
            } catch (e: Exception) {
                if (gen == switchGen) {
                    pendingTipForChannel = null
                    showTip("切换失败，请重试", autoHide = true)
                }
            }
        }
    }

    private fun playQuality(gen: Long) {
        if (gen != switchGen) return
        if (qualityIndex !in qualityList.indices) {
            if (gen == switchGen) {
                pendingTipForChannel = null
                showTip("清晰度解析失败", autoHide = true)
            }
            return
        }

        val (qName, hls) = qualityList[qualityIndex]
        val channel = playList.getOrNull(currentIndex)
        val channelName = channel?.name ?: ""
        if (pendingTipForChannel != null) {
            showTip("正在切换到：$channelName · $qName", autoHide = false)
        }

        releasePlayerOnly()

        // 针对电视盒子的专属解码与负载配置
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 28_000, 2_000, 5_000)
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

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                // 全屏单播放器承载画面
                playerView.player = exo

                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (gen != switchGen) return
                        Log.e(TAG, "播放异常: ${error.errorCodeName} | ${error.message}")
                        val rid = playList.getOrNull(currentIndex)?.rid
                        if (qualityIndex >= qualityList.lastIndex && rid != null) {
                            streamCache.remove(rid)
                            streamCacheTime.remove(rid)
                        }
                        qualityIndex++
                        playerView.postDelayed({ playQuality(gen) }, 800)
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (gen != switchGen) return
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                if (pendingTipForChannel != null) {
                                    showTip("正在切换到：$pendingTipForChannel（缓冲中）", autoHide = false)
                                } else {
                                    showTip("正在缓冲 [$qName]...", autoHide = false)
                                }
                                scheduleBufferingTimeout(gen)
                            }
                            Player.STATE_READY -> {
                                cancelBufferingTimeout()
                                onSwitchCompleted(gen)
                            }
                            Player.STATE_ENDED -> {
                                cancelBufferingTimeout()
                                pendingTipForChannel = null
                                showTip("直播已结束，按上下键换台或菜单键选台", autoHide = true)
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        if (gen != switchGen) return
                        cancelBufferingTimeout()
                        onSwitchCompleted(gen)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        Log.d(TAG, "视频尺寸已更新: ${videoSize.width}x${videoSize.height}")
                    }
                })

                if (gen != switchGen) {
                    exo.release()
                    return@also
                }

                exo.setMediaItem(MediaItem.fromUri(hls))
                exo.prepare()
                exo.playWhenReady = true
                scheduleBufferingTimeout(gen)
            }
    }

    private fun onSwitchCompleted(gen: Long) {
        if (gen != switchGen) return
        if (pendingTipForChannel != null) {
            val name = pendingTipForChannel
            pendingTipForChannel = null
            showTip("已切换到：$name", autoHide = true)
        } else {
            tipView.visibility = View.GONE
        }
    }

    private suspend fun fetchQualities(baseUrl: String, rid: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val apiUrl = "${baseUrl.trimEnd('/')}/live.php?rid=${rid.trim()}"
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", TV_UA)
                    .header("Referer", "$baseUrl/")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
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
                Log.e(TAG, "解析流地址失败: $rid", e)
                emptyList()
            }
        }

    private fun scheduleBufferingTimeout(gen: Long) {
        cancelBufferingTimeout()
        bufferingTimeoutRunnable = Runnable {
            if (gen == switchGen && player?.playbackState == Player.STATE_BUFFERING) {
                qualityIndex++
                playQuality(gen)
            }
        }.also {
            playerView.postDelayed(it, BUFFERING_TIMEOUT_MS)
        }
    }

    private fun cancelBufferingTimeout() {
        bufferingTimeoutRunnable?.let { playerView.removeCallbacks(it) }
        bufferingTimeoutRunnable = null
    }

    // -------------------- 选台菜单（仅展示在线频道） --------------------

    private fun showMenu() {
        if (isMenuShowing) return
        hideLiveInfo()

        // 仅筛选在线频道，同时绑定全局原始真实索引
        val online = playList.mapIndexed { i, c -> i to c }.filter { it.second.isLive }
        if (online.isEmpty()) {
            showTip("当前没有在线直播", autoHide = true)
            return
        }

        isMenuShowing = true
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

    // -------------------- OK 键流信息浮层 --------------------

    private fun toggleInfo() {
        if (isInfoShowing) {
            hideLiveInfo()
            return
        }
        val ch = playList.getOrNull(currentIndex) ?: return
        val p = player
        val vf = p?.videoFormat
        val af = p?.audioFormat
        val br = (vf?.bitrate?.takeIf { it > 0 } ?: vf?.averageBitrate?.takeIf { it > 0 } ?: 0) / 1000
        val (curQuality, _) = qualityList.getOrNull(qualityIndex) ?: ("默认" to "")

        infoOverlay.text = buildString {
            append("【当前频道流信息】\n")
            append("频道名称：${ch.name}\n")
            append("房间号 (RID)：${ch.rid}\n")
            append("直播状态：${if (ch.isLive) "在线" else "离线"}\n")
            append("清晰度流：$curQuality\n")
            append("────────────────\n")
            append("分辨率：${vf?.width ?: "-"} × ${vf?.height ?: "-"}\n")
            append("视频编码：${vf?.sampleMimeType ?: "-"}\n")
            append("音频编码：${af?.sampleMimeType ?: "-"}\n")
            append("当前码率：${if (br > 0) "$br kbps" else "自适应"}\n")
            append("缓冲时长：${p?.totalBufferedDuration ?: 0} ms\n")
            append("────────────────\n（再次按 OK 或返回键关闭）")
        }
        infoOverlay.visibility = View.VISIBLE
        infoOverlay.bringToFront()
        isInfoShowing = true
    }

    private fun hideLiveInfo() {
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
        // 1. 频道选择菜单处于激活状态时的按键捕获
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

        // 2. 信息面板处于激活状态时的按键拦截
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

        // 3. 正常全屏播放下的遥控器控制
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
                if (currentIndex > 0) {
                    switchToChannel(currentIndex - 1)
                } else {
                    showTip("已经是第一个频道", autoHide = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentIndex < playList.lastIndex) {
                    switchToChannel(currentIndex + 1)
                } else {
                    showTip("已经是最后一个频道", autoHide = true)
                }
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

    private fun showTip(msg: String, autoHide: Boolean) {
        tipView.bringToFront()
        tipView.text = msg
        tipView.visibility = View.VISIBLE
        tipView.alpha = 1f
        tipView.removeCallbacks(hideTipRunnable)
        if (autoHide) {
            tipView.postDelayed(hideTipRunnable, 1800)
        }
    }

    private val hideTipRunnable = Runnable {
        tipView.animate().alpha(0f).setDuration(220).withEndAction {
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
        playerView.player = null
        player?.release()
        player = null
    }

    private fun releaseAll() {
        switchJob?.cancel()
        releasePlayerOnly()
        streamCache.clear()
        streamCacheTime.clear()
    }

    override fun onStop() {
        super.onStop()
        player?.playWhenReady = false
    }

    override fun onStart() {
        super.onStart()
        player?.playWhenReady = true
    }

    override fun onDestroy() {
        releaseAll()
        super.onDestroy()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
