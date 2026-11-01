package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.engine.DouyinStreamResolver
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 军工级原生 ExoPlayer 直播 Activity
 * 实现要求：
 * 1. 显式通过 player.stop() 停止当前播放，在新流准备好后重置 MediaItem 并调用 player.setPlayWhenReady(true)；
 * 2. 监听 Player.Listener 处理音频焦点，彻底解决切换后的静音与卡顿；
 * 3. 抖音竖屏直播全屏时智能为竖屏全屏；
 * 4. 防黑屏骨架加载屏（等待首帧上屏后平滑淡出）；
 * 5. 息屏再开自动唤醒重拉流，防止假死。
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 沉浸式全屏
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
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
        var isBuffering by remember { mutableStateOf(true) }
        var isFirstFrameRendered by remember { mutableStateOf(false) }
        var isPortraitStream by remember { mutableStateOf(true) }
        var showLineDropdown by remember { mutableStateOf(false) }
        var isUserManualRotated by remember { mutableStateOf(false) }

        // 设置播放状态监听器以管理音频焦点与防黑屏
        DisposableEffect(exoPlayer) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            isBuffering = true
                        }
                        Player.STATE_READY -> {
                            isBuffering = false
                            exoPlayer?.volume = 1.0f
                        }
                        Player.STATE_ENDED -> {
                            isBuffering = false
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onRenderedFirstFrame() {
                    // 首帧已真正渲染，平滑淡出加载占位层，彻底解决载入前黑屏
                    isFirstFrameRendered = true
                    isBuffering = false
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val isPortrait = (videoSize.height >= videoSize.width)
                        isPortraitStream = isPortrait
                        if (!isUserManualRotated) {
                            // 抖音竖屏直播全屏时智能为竖屏全屏，横屏直播转横屏全屏
                            requestedOrientation = if (isPortrait) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    // 确保音频焦点分配后音量非静音
                    exoPlayer?.volume = 1.0f
                }

                override fun onPlayerError(error: PlaybackException) {
                    isBuffering = false
                    Toast.makeText(this@PlayerActivity, "信号中断，正在自愈重试...", Toast.LENGTH_SHORT).show()
                    currentUrl?.let { switchStreamSource(it, currentLine) }
                }
            }

            exoPlayer?.addListener(listener)
            onDispose {
                exoPlayer?.removeListener(listener)
            }
        }

        // 初始拉流
        LaunchedEffect(roomId) {
            if (!initialStreamUrl.isNullOrBlank()) {
                currentUrl = initialStreamUrl
                switchStreamSource(initialStreamUrl, "直连线路")
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
                    switchStreamSource(firstUrl, firstKey)
                } else {
                    Toast.makeText(this@PlayerActivity, "当前未开播或无法拉取流地址", Toast.LENGTH_LONG).show()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // ExoPlayer 核心视图
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

            // 防黑屏骨架加载层 (在首帧上屏前展示高质感流媒体接入动效，避免冷冰冰的纯黑屏)
            AnimatedVisibility(
                visible = !isFirstFrameRendered,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(500))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF1A1A24),
                                    Color(0xFF09090C),
                                    Color(0xFF121218)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 呼吸式直播光环
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.9f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scale"
                        )
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 0.9f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "alpha"
                        )

                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(DouyinRed.copy(alpha = 0.15f * scale)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(DouyinRed.copy(alpha = alpha)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Text(
                            text = anchor.ifBlank { "正在接入直播信号" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Text(
                            text = "低延迟极速流解码准备中，防黑屏自适应缓冲...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = DouyinRed,
                            strokeWidth = 2.5.dp
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
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(DouyinGreen)
                        )
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 控制操作区
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 线路切换
                    if (streamsMap.isNotEmpty()) {
                        Surface(
                            onClick = { showLineDropdown = true },
                            shape = RoundedCornerShape(18.dp),
                            color = DouyinRed.copy(alpha = 0.85f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = currentLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "切换线路",
                                    tint = Color.White,
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
                                        currentLine = name
                                        currentUrl = url
                                        // 核心调用：stop -> setMediaItem -> setPlayWhenReady(true)
                                        switchStreamSource(url, name)
                                    }
                                )
                            }
                        }
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

    /**
     * 核心实现：
     * 显式通过 player.stop() 停止当前播放，在新流准备好后重置 MediaItem 并调用 player.setPlayWhenReady(true)，
     * 彻底解决切换后的静音与卡顿问题。
     */
    private fun switchStreamSource(newUrl: String, lineName: String) {
        val player = exoPlayer ?: return

        // 步骤 1：显式通过 player.stop() 停止当前播放，释放解码硬件与音频缓存
        player.stop()

        // 步骤 2：重置 MediaItem
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

        // 步骤 3：准备播放器资源
        player.prepare()

        // 步骤 4：显式调用 setPlayWhenReady(true) 保证切流后立即发声起播
        player.playWhenReady = true

        // 步骤 5：确保音量恢复满格
        player.volume = 1.0f
    }

    /**
     * 修复息屏再开也要重载才行：
     * 在 onResume 中检查流的活性，自动触发 prepare 与 setPlayWhenReady(true)
     */
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
        isPlayingBeforePause = exoPlayer?.isPlaying == true
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
