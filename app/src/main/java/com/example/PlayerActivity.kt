package com.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.db.StreamRecord
import com.example.data.db.StreamRecordRepository
import com.example.engine.DouyinStreamResolver
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 军工级原生 ExoPlayer 直播 Activity
 * 核心特性：
 * 1. resetPlayer()：显式调用 player.stop() 与 player.clearMediaItems()；
 * 2. 线路重载前显式展示 Loading UI，并通过 player.prepare() 与 player.setPlayWhenReady(true) 实现无缝切换；
 * 3. 布局中居中展示 CircularProgressIndicator，基于 Player.Listener 监听，在 STATE_BUFFERING 或 STATE_IDLE 时显示，杜绝黑屏；
 * 4. 抖音竖屏直播全屏自适应与电视端 D-pad 遥控适配；
 * 5. 全面画中画 (Picture-in-Picture) 模式支持与切后台无缝播放；
 * 6. 状态栏明亮高对比度文字与息屏唤醒自愈拉流。
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_TITLE = "extra_room_title"
        const val EXTRA_ROOM_ANCHOR = "extra_room_anchor"
        const val EXTRA_STREAM_URL = "extra_stream_url"

        fun createIntent(
            context: Context,
            roomId: String,
            title: String = "",
            anchor: String = "",
            streamUrl: String = ""
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ROOM_TITLE, title)
                putExtra(EXTRA_ROOM_ANCHOR, anchor)
                putExtra(EXTRA_STREAM_URL, streamUrl)
            }
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentPlayerView: PlayerView? = null
    private var currentUrl: String? = null
    private var isPlayingBeforePause: Boolean = false
    private lateinit var recordRepository: StreamRecordRepository

    // 画中画状态与视频流宽高比
    private val isInPipModeState = mutableStateOf(false)
    private var isCurrentStreamPortrait: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        recordRepository = StreamRecordRepository.getInstance(this)

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏与状态栏文字亮度保障 (确保暗色背景下状态栏图标清晰可见)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "10443341948"
        val roomTitle = intent.getStringExtra(EXTRA_ROOM_TITLE) ?: "抖音高清直播"
        val anchorName = intent.getStringExtra(EXTRA_ROOM_ANCHOR) ?: "直播中控"
        val directStreamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        initializeExoPlayer()

        setContent {
            MyApplicationTheme {
                PlayerScreen(
                    roomId = roomId,
                    initialTitle = roomTitle,
                    initialAnchor = anchorName,
                    initialStreamUrl = directStreamUrl
                )
            }
        }
    }

    private fun initializeExoPlayer() {
        if (exoPlayer != null) return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 5000,
                /* bufferForPlaybackMs = */ 600,
                /* bufferForPlaybackAfterRebufferMs = */ 1200
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .build()
    }

    /**
     * 重置播放器：
     * 显式调用 player.stop() 与 player.clearMediaItems()，彻底释放旧流硬解与音频队列
     */
    fun resetPlayer() {
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
    }

    /**
     * 核心重构实现：无缝切换线路
     * 1. 重载新流前显式置为 Loading 状态；
     * 2. 执行 resetPlayer() (stop + clearMediaItems)；
     * 3. 准备新流并调用 player.prepare() 与 player.setPlayWhenReady(true)。
     */
    fun switchStreamSource(
        newUrl: String,
        lineName: String,
        onSetLoading: () -> Unit
    ) {
        val player = exoPlayer ?: return
        currentUrl = newUrl

        // 1. 在重载新流前添加显示 Loading UI 的逻辑
        onSetLoading()

        // 2. 显式调用 resetPlayer 方法 (stop + clearMediaItems)
        resetPlayer()

        // 3. 构建并设置新流 MediaItem
        val mediaItem = MediaItem.Builder()
            .setUri(newUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.05f)
                    .setMinPlaybackSpeed(0.95f)
                    .build()
            )
            .build()
        player.setMediaItem(mediaItem, /* resetPosition = */ true)

        // 4. 准备流并显式调用 setPlayWhenReady(true) 实现无缝切换
        player.prepare()
        player.playWhenReady = true

        // 5. 确保音量完全激活
        player.volume = 1.0f
    }

    /**
     * 军工级安全画中画模式调起
     */
    fun enterPipModeSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val paramsBuilder = PictureInPictureParams.Builder()
                // 根据当前流横竖屏设置精准画中画比例
                val aspectRatio = if (isCurrentStreamPortrait) Rational(9, 16) else Rational(16, 9)
                paramsBuilder.setAspectRatio(aspectRatio)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(true)
                }
                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    enterPictureInPictureMode()
                } catch (e2: Exception) {
                    Toast.makeText(this, "当前设备暂不支持画中画", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(this, "当前系统版本低于 Android 8.0，不支持画中画", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户按 Home 键离开时自动切入画中画
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && exoPlayer?.isPlaying == true) {
            enterPipModeSafely()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exoPlayer?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exoPlayer?.pause()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
                    finish()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Composable
    fun PlayerScreen(
        roomId: String,
        initialTitle: String,
        initialAnchor: String,
        initialStreamUrl: String?
    ) {
        var title by remember { mutableStateOf(initialTitle) }
        var anchor by remember { mutableStateOf(initialAnchor) }
        var streamsMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var currentLine by remember { mutableStateOf("默认线路") }

        // 播放器状态监听：STATE_IDLE, STATE_BUFFERING, STATE_READY, STATE_ENDED
        var playbackState by remember { mutableIntStateOf(Player.STATE_IDLE) }
        var isManualLoading by remember { mutableStateOf(true) }
        var isPortraitStream by remember { mutableStateOf(true) }
        var showLineDropdown by remember { mutableStateOf(false) }
        var isUserManualRotated by remember { mutableStateOf(false) }

        val isInPip by isInPipModeState

        val coroutineScope = rememberCoroutineScope()
        val recordKey = if (roomId.isNotBlank()) roomId else (initialStreamUrl ?: "")
        val recordFlow = remember(recordKey) { recordRepository.observeRecord(recordKey) }
        val currentRecord by recordFlow.collectAsState(initial = null)
        val isFavorite = currentRecord?.isFavorite == true

        // 注册 Player.Listener 监听播放器状态
        DisposableEffect(exoPlayer) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    playbackState = state
                    if (state == Player.STATE_READY) {
                        isManualLoading = false
                        exoPlayer?.volume = 1.0f
                    }
                }

                override fun onRenderedFirstFrame() {
                    // 首帧渲染上屏，彻底消除黑屏
                    isManualLoading = false
                    playbackState = Player.STATE_READY
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val isPortrait = (videoSize.height >= videoSize.width)
                        isPortraitStream = isPortrait
                        isCurrentStreamPortrait = isPortrait
                        if (!isUserManualRotated && !isInPip) {
                            // 抖音竖屏直播全屏自适应
                            requestedOrientation = if (isPortrait) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isManualLoading = false
                    Toast.makeText(
                        this@PlayerActivity,
                        "流媒体播放异常，建议切换备用线路",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            exoPlayer?.addListener(listener)
            onDispose {
                exoPlayer?.removeListener(listener)
            }
        }

        // 解析并启动流播放
        LaunchedEffect(roomId, initialStreamUrl) {
            isManualLoading = true
            if (!initialStreamUrl.isNullOrBlank()) {
                currentUrl = initialStreamUrl
                lifecycleScope.launch(Dispatchers.IO) {
                    recordRepository.recordAccess(
                        roomId = roomId,
                        streamUrl = initialStreamUrl,
                        title = initialTitle,
                        anchorName = initialAnchor
                    )
                }
                switchStreamSource(initialStreamUrl, "直连接口") {
                    isManualLoading = true
                }
            } else {
                val roomInfo = DouyinStreamResolver.resolveLiveRoom(roomId)
                if (roomInfo != null && roomInfo.streams.isNotEmpty()) {
                    title = roomInfo.title
                    anchor = roomInfo.anchorName
                    streamsMap = roomInfo.streams
                    val firstKey = roomInfo.streams.keys.first()
                    val firstUrl = roomInfo.streams[firstKey]!!
                    currentUrl = firstUrl
                    currentLine = firstKey
                    lifecycleScope.launch(Dispatchers.IO) {
                        recordRepository.recordAccess(
                            roomId = roomId,
                            streamUrl = firstUrl,
                            title = roomInfo.title,
                            anchorName = roomInfo.anchorName,
                            coverUrl = roomInfo.avatarUrl
                        )
                    }
                    switchStreamSource(firstUrl, firstKey) {
                        isManualLoading = true
                    }
                } else {
                    isManualLoading = false
                    Toast.makeText(this@PlayerActivity, "当前未开播或无法拉取流地址", Toast.LENGTH_LONG).show()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ExoPlayer 核心视频视图
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        player = exoPlayer
                        currentPlayerView = this
                    }
                }
            )

            // 画中画模式下隐藏所有浮层，全屏仅保留纯净视频
            if (!isInPip) {
                // 核心实现：当播放器处于 STATE_BUFFERING 或 STATE_IDLE（或换线加载中）时居中显示 CircularProgressIndicator
                val showLoading = isManualLoading ||
                    playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE

                if (showLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF09090C).copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = DouyinRed,
                                strokeWidth = 3.5.dp
                            )
                            Text(
                                text = if (playbackState == Player.STATE_BUFFERING) "正在缓冲直播信号..." else "正在接入直播通道...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // 顶部浮动控制胶囊
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 主播与房间信息
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = DarkSurface.copy(alpha = 0.75f),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .border(0.8.dp, DarkSurfaceBorder, RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(DouyinRed.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LiveTv,
                                    contentDescription = null,
                                    tint = DouyinRed,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = anchor,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 右侧功能键：收藏、画中画、重载、线路、旋转、退出
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 收藏按钮
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    val targetId = currentRecord?.id ?: if (roomId.isNotBlank()) roomId else (currentUrl ?: "")
                                    recordRepository.recordAccess(
                                        roomId = roomId,
                                        streamUrl = currentUrl ?: "",
                                        title = title,
                                        anchorName = anchor
                                    )
                                    recordRepository.toggleFavorite(targetId)
                                    val newFav = !isFavorite
                                    Toast.makeText(
                                        this@PlayerActivity,
                                        if (newFav) "已收藏该直播间" else "已取消收藏",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.75f))
                                .focusable()
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (isFavorite) "取消收藏" else "收藏直播间",
                                tint = if (isFavorite) Color(0xFFFFB800) else TextPrimary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 画中画 (PiP) 模式按钮
                        IconButton(
                            onClick = {
                                enterPipModeSafely()
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.75f))
                                .focusable()
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "画中画",
                                tint = DouyinCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 换线下拉
                        if (streamsMap.isNotEmpty()) {
                            Box {
                                Surface(
                                    onClick = { showLineDropdown = true },
                                    shape = RoundedCornerShape(18.dp),
                                    color = DarkSurface.copy(alpha = 0.75f),
                                    modifier = Modifier
                                        .height(36.dp)
                                        .border(0.8.dp, DarkSurfaceBorder, RoundedCornerShape(18.dp))
                                        .focusable()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = currentLine,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = DouyinCyan
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "切换线路",
                                            tint = DouyinCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showLineDropdown,
                                    onDismissRequest = { showLineDropdown = false },
                                    modifier = Modifier.background(DarkSurfaceElevated)
                                ) {
                                    streamsMap.forEach { (name, url) ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = name,
                                                    color = if (name == currentLine) DouyinRed else TextPrimary,
                                                    fontWeight = if (name == currentLine) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                showLineDropdown = false
                                                if (name != currentLine) {
                                                    currentLine = name
                                                    switchStreamSource(url, name) {
                                                        isManualLoading = true
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 重载当前线路
                        IconButton(
                            onClick = {
                                currentUrl?.let { url ->
                                    switchStreamSource(url, currentLine) {
                                        isManualLoading = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.75f))
                                .focusable()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重载",
                                tint = TextPrimary,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 旋转屏幕
                        IconButton(
                            onClick = {
                                isUserManualRotated = true
                                requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.75f))
                                .focusable()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "旋转屏幕",
                                tint = DouyinCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 退出 Activity
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.75f))
                                .focusable()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        exoPlayer?.let { player ->
            if (currentUrl != null && isPlayingBeforePause) {
                player.prepare()
                player.playWhenReady = true
                player.volume = 1.0f
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 画中画模式下保持播放，不执行 pause
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return
        }
        isPlayingBeforePause = exoPlayer?.isPlaying == true
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        resetPlayer()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
