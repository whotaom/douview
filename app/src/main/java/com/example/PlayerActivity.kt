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
 * 4. 抖音竖屏直播全屏自适应；
 * 5. 息屏唤醒自愈拉流。
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        recordRepository = StreamRecordRepository.getInstance(this)

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

    /**
     * 重构方法：重置播放器
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
        var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
        var isManualLoading by remember { mutableStateOf(true) }
        var isPortraitStream by remember { mutableStateOf(true) }
        var showLineDropdown by remember { mutableStateOf(false) }
        var isUserManualRotated by remember { mutableStateOf(false) }

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
                    } else if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) {
                        // 处于 STATE_BUFFERING 或 STATE_IDLE 状态
                    }
                }

                override fun onRenderedFirstFrame() {
                    // 首帧真正上屏渲染，结束一切过渡加载，杜绝黑屏
                    isManualLoading = false
                    playbackState = Player.STATE_READY
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val isPortrait = (videoSize.height >= videoSize.width)
                        isPortraitStream = isPortrait
                        if (!isUserManualRotated) {
                            // 抖音竖屏直播全屏自适应
                            requestedOrientation = if (isPortrait) {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    exoPlayer?.volume = 1.0f
                }

                override fun onPlayerError(error: PlaybackException) {
                    isManualLoading = false
                    Toast.makeText(this@PlayerActivity, "信号中断，自动重连中...", Toast.LENGTH_SHORT).show()
                    currentUrl?.let { url ->
                        switchStreamSource(url, currentLine) {
                            isManualLoading = true
                        }
                    }
                }
            }

            exoPlayer?.addListener(listener)
            onDispose {
                exoPlayer?.removeListener(listener)
            }
        }

        // 初始拉流与持久化到 Room 数据库
        LaunchedEffect(roomId) {
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
                switchStreamSource(initialStreamUrl, "直连线路") {
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
                    // 线路无缝切换下拉菜单
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
                                        // 核心调用：重载前显示 Loading UI -> resetPlayer() -> prepare() -> setPlayWhenReady(true)
                                        switchStreamSource(url, name) {
                                            isManualLoading = true
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // 收藏星标按钮 (Room 本地数据库持久化)
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                val newFav = recordRepository.toggleFavorite(recordKey)
                                Toast.makeText(
                                    this@PlayerActivity,
                                    if (newFav) "已添加收藏 ⭐" else "已取消收藏",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DarkSurface.copy(alpha = 0.75f))
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "加入收藏",
                            tint = if (isFavorite) Color(0xFFFFB800) else Color.White,
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
        resetPlayer()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}
