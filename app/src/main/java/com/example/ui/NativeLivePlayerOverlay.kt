package com.example.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.example.engine.DouyinStreamResolver
import com.example.engine.LivePlayerEngine
import com.example.ui.theme.*

@Composable
fun NativeLivePlayerOverlay(
    roomInfo: DouyinStreamResolver.LiveRoomInfo,
    playerEngine: LivePlayerEngine,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val playbackState by playerEngine.playbackState.collectAsState()
    val isPortraitStream by playerEngine.isPortraitStream.collectAsState()
    val currentLineName by playerEngine.currentLineName.collectAsState()

    var showQualityMenu by remember { mutableStateOf(false) }
    var isUserLandscape by remember { mutableStateOf(false) }

    // 当检测到流的横竖比更新且用户未手动强制旋转时，自适应屏幕方向
    LaunchedEffect(isPortraitStream, isUserLandscape) {
        if (!isUserLandscape) {
            activity?.requestedOrientation = if (isPortraitStream) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ExoPlayer 原生渲染视图
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    playerEngine.attachPlayerView(this)
                }
            },
            update = { view ->
                playerEngine.attachPlayerView(view)
            }
        )

        // 顶部控制与主播信息栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 主播信息胶囊
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = DarkSurface.copy(alpha = 0.75f),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .border(0.8.dp, DarkSurfaceBorder, RoundedCornerShape(24.dp))
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
                            text = roomInfo.anchorName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = roomInfo.title,
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

            // 右侧操作群组
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 清晰度/线路无缝切换下拉
                Surface(
                    onClick = { showQualityMenu = true },
                    shape = RoundedCornerShape(18.dp),
                    color = DouyinRed.copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = currentLineName,
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
                    expanded = showQualityMenu,
                    onDismissRequest = { showQualityMenu = false },
                    modifier = Modifier.background(DarkSurfaceElevated)
                ) {
                    Text(
                        text = "选择直播线路 / 画质",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    roomInfo.streams.forEach { (line, url) ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = line,
                                        color = if (line == currentLineName) DouyinRed else TextPrimary,
                                        fontWeight = if (line == currentLineName) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (line == currentLineName) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = DouyinRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                showQualityMenu = false
                                // 核心调用：无缝切换线路并强制 setPlayWhenReady(true)
                                playerEngine.switchStreamSource(url, line)
                            }
                        )
                    }
                }

                // 旋转屏幕切换
                IconButton(
                    onClick = {
                        isUserLandscape = !isUserLandscape
                        activity?.requestedOrientation = if (isUserLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    },
                    modifier = Modifier
                        .size(34.dp)
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

                // 退出原生播放器
                IconButton(
                    onClick = {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        onClose()
                    },
                    modifier = Modifier
                        .size(34.dp)
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

        // 缓冲与加载状态提示
        when (val state = playbackState) {
            is LivePlayerEngine.PlaybackState.Buffering -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = DouyinRed,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }
            }
            is LivePlayerEngine.PlaybackState.Error -> {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DouyinRed,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Button(
                            onClick = { playerEngine.reloadCurrentStream() },
                            colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("重新拉流")
                        }
                    }
                }
            }
            else -> {}
        }
    }
}
