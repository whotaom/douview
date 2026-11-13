package com.example.tv.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.data.StreamLines
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import com.example.tv.components.TvActionButton
import com.example.tv.components.TvFocusableCard
import com.example.tv.components.TvSafeAreaDefaults
import com.example.tv.components.TvTopBar
import com.example.tv.components.safeRequestFocus
import com.example.tv.player.TvFullscreenPlayerActivity
import com.example.ui.ChannelManageDialog
import com.example.ui.ClearCacheConfirmDialog
import com.example.ui.UpdateDialog
import com.example.ui.theme.*
import com.example.util.UpdateManager

private enum class TvDialogReturnTarget {
    CHANNEL_MANAGE,
    CLEAR_CACHE,
    UPDATE,
    CUSTOM_URL
}

@Composable
fun TvHomeScreen(
    onEnterPlayer: (url: String) -> Unit,
    onOpenUrlDialog: () -> Unit,
    urlDialogReturnSignal: Int = 0
) {
    val context = LocalContext.current
    val preferences = remember { LivePreferences(context) }
    val channelManager = remember { ChannelManager(context) }

    var currentTargetUrl by remember { mutableStateOf(preferences.targetUrl) }
    val currentLine = remember(currentTargetUrl) { StreamLines.detectLine(currentTargetUrl) }
    val activeBaseUrl = remember(currentLine) { StreamLines.baseUrl(currentLine) }

    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(preferences.keepScreenOn) }
    var channels by remember { mutableStateOf(channelManager.loadChannels()) }
    var probeStatusTip by remember { mutableStateOf("正在检测在线状态...") }

    val sortedChannels = remember(channels) {
        channels.sortedWith(
            compareBy { if (it.isLive) 0 else if (!it.statusKnown) 1 else 2 }
        )
    }

    // D-pad 焦点管理与弹窗关闭后焦点安全归位
    val firstChannelFocusRequester = remember { FocusRequester() }
    val heroPlayFocusRequester = remember { FocusRequester() }
    val channelManageFocusRequester = remember { FocusRequester() }
    val clearCacheFocusRequester = remember { FocusRequester() }
    val updateBtnFocusRequester = remember { FocusRequester() }
    val customUrlFocusRequester = remember { FocusRequester() }
    var pendingReturnTarget by remember { mutableStateOf<TvDialogReturnTarget?>(null) }

    // 首次进入界面：等首帧组合挂载完成后安全赋予默认焦点（优先首位频道，其次一键畅播）
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (sortedChannels.isNotEmpty()) {
            if (!firstChannelFocusRequester.safeRequestFocus()) {
                heroPlayFocusRequester.safeRequestFocus()
            }
        } else {
            heroPlayFocusRequester.safeRequestFocus()
        }
    }

    // 外部 URL 弹窗关闭后同步地址并恢复「输入自定义地址」卡片焦点
    LaunchedEffect(urlDialogReturnSignal) {
        if (urlDialogReturnSignal > 0) {
            currentTargetUrl = preferences.targetUrl
            withFrameNanos { }
            if (!customUrlFocusRequester.safeRequestFocus()) {
                heroPlayFocusRequester.safeRequestFocus()
            }
        }
    }

    // 内部弹窗关闭后精确还原焦点的安全守护
    val anyInternalDialogOpen = showChannelDialog || showClearCacheDialog || showUpdateDialog
    LaunchedEffect(anyInternalDialogOpen) {
        if (!anyInternalDialogOpen && pendingReturnTarget != null) {
            val target = pendingReturnTarget
            pendingReturnTarget = null
            withFrameNanos { }
            val restored = when (target) {
                TvDialogReturnTarget.CHANNEL_MANAGE -> channelManageFocusRequester.safeRequestFocus()
                TvDialogReturnTarget.CLEAR_CACHE -> clearCacheFocusRequester.safeRequestFocus()
                TvDialogReturnTarget.UPDATE -> updateBtnFocusRequester.safeRequestFocus()
                TvDialogReturnTarget.CUSTOM_URL -> customUrlFocusRequester.safeRequestFocus()
                null -> false
            }
            if (!restored) {
                heroPlayFocusRequester.safeRequestFocus()
            }
        }
    }

    // 进页及切换主备线路时：先展示本地列表，再自动调用当前线路的 live.php?batch= 探测真实在线状态
    LaunchedEffect(activeBaseUrl) {
        probeStatusTip = "正在检测在线状态..."
        try {
            val outcome = channelManager.probeLiveStatus(activeBaseUrl, channels)
            if (outcome.networkFailed) {
                probeStatusTip = "在线检测失败，请检查网络"
                Toast.makeText(context, "在线检测失败，请检查网络", Toast.LENGTH_SHORT).show()
            } else {
                channelManager.saveChannels(outcome.channels)
                channels = outcome.channels
                probeStatusTip = when {
                    outcome.unknownCount > 0 -> "在线 ${outcome.liveCount} 台 · ${outcome.unknownCount} 台状态未知"
                    else -> "状态已同步 · 在线 ${outcome.liveCount} 台"
                }
            }
        } catch (_: Exception) {
            probeStatusTip = "在线检测失败，请检查网络"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // 顶部大屏标题栏（含 TV 过扫描安全区）
        TvTopBar(
            title = "抖眸流控 · 电视大屏监控中控台",
            subtitle = "当前中控服务: $activeBaseUrl · $probeStatusTip"
        ) {
            TvActionButton(
                text = "频道管理",
                icon = Icons.Default.LiveTv,
                isPrimary = true,
                focusRequester = channelManageFocusRequester,
                testTagValue = "tv_btn_channel_manage",
                onClick = {
                    pendingReturnTarget = TvDialogReturnTarget.CHANNEL_MANAGE
                    showChannelDialog = true
                }
            )

            TvActionButton(
                text = "清理缓存",
                icon = Icons.Default.DeleteSweep,
                focusRequester = clearCacheFocusRequester,
                testTagValue = "tv_btn_clear_cache",
                onClick = {
                    pendingReturnTarget = TvDialogReturnTarget.CLEAR_CACHE
                    showClearCacheDialog = true
                }
            )

            TvActionButton(
                text = "版本更新",
                icon = Icons.Default.SystemUpdate,
                focusRequester = updateBtnFocusRequester,
                testTagValue = "tv_btn_check_update",
                onClick = {
                    pendingReturnTarget = TvDialogReturnTarget.UPDATE
                    showUpdateDialog = true
                }
            )
        }

        // 主体操作内容区（遵循 48.dp 水平、27.dp 底部过扫描安全区）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = TvSafeAreaDefaults.HorizontalPadding,
                    end = TvSafeAreaDefaults.HorizontalPadding,
                    top = 6.dp,
                    bottom = TvSafeAreaDefaults.VerticalPadding
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 1. 精选电视频道通道 (横向 D-pad 左右快速切台浏览)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "精选直播频道（遥控器左右选择，按 OK 键即刻全屏开播）：",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "共 ${channels.size} 台 · 在线 ${channels.count { it.isLive }} 台",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup()
                ) {
                    itemsIndexed(
                        items = sortedChannels,
                        key = { _, ch -> ch.rid }
                    ) { index, channel ->
                        TvFocusableCard(
                            focusRequester = if (index == 0) firstChannelFocusRequester else null,
                            testTagValue = "tv_channel_card_${channel.rid}",
                            onClick = {
                                val intent = Intent(context, TvFullscreenPlayerActivity::class.java).apply {
                                    putExtra(TvFullscreenPlayerActivity.EXTRA_RID, channel.rid)
                                    putExtra(TvFullscreenPlayerActivity.EXTRA_LINE, currentLine)
                                    putExtra(TvFullscreenPlayerActivity.EXTRA_BASE_URL, activeBaseUrl)
                                    putExtra(TvFullscreenPlayerActivity.EXTRA_TITLE, channel.name)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.width(220.dp)
                        ) { isFocused ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                when {
                                                    channel.isLive -> DouyinGreen
                                                    !channel.statusKnown -> Color(0xFFFFB74D)
                                                    else -> Color.Gray
                                                },
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = channel.name,
                                        color = if (isFocused) DouyinCyan else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (channel.title.isNotBlank()) channel.title else "房间号: ${channel.rid}",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    item {
                        TvFocusableCard(
                            testTagValue = "tv_channel_card_manage",
                            onClick = {
                                pendingReturnTarget = TvDialogReturnTarget.CHANNEL_MANAGE
                                showChannelDialog = true
                            },
                            modifier = Modifier.width(180.dp)
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddCircleOutline,
                                    contentDescription = null,
                                    tint = if (isFocused) DouyinCyan else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "导入/管理",
                                    color = if (isFocused) DouyinCyan else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // 2. 线路与自定义中控区
                Text(
                    text = "快捷服务节点切换：",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup()
                ) {
                    item {
                        TvFocusableCard(
                            testTagValue = "tv_line_card_1",
                            onClick = {
                                preferences.targetUrl = LivePreferences.DEFAULT_URL
                                currentTargetUrl = LivePreferences.DEFAULT_URL
                                TvFullscreenPlayerActivity.clearStreamCache()
                                Toast.makeText(context, "已切至主线（tv.fuxiaoya.com）", Toast.LENGTH_SHORT).show()
                            },
                            isHighlighted = currentLine == 1,
                            modifier = Modifier.width(220.dp)
                        ) { isFocused ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (currentLine == 1) DouyinGreen else Color.Gray, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "官方主线 (线路一)",
                                        color = if (isFocused) DouyinCyan else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = StreamLines.LINE_1,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    item {
                        TvFocusableCard(
                            testTagValue = "tv_line_card_2",
                            onClick = {
                                preferences.targetUrl = StreamLines.LINE_2
                                currentTargetUrl = StreamLines.LINE_2
                                TvFullscreenPlayerActivity.clearStreamCache()
                                Toast.makeText(context, "已切至线路二（dy.whomao.com）", Toast.LENGTH_SHORT).show()
                            },
                            isHighlighted = currentLine == 2,
                            modifier = Modifier.width(220.dp)
                        ) { isFocused ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (currentLine == 2) DouyinGreen else Color.Gray, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "备用线路 (线路二)",
                                        color = if (isFocused) DouyinCyan else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = StreamLines.LINE_2,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    item {
                        TvFocusableCard(
                            focusRequester = customUrlFocusRequester,
                            testTagValue = "tv_line_card_custom",
                            onClick = onOpenUrlDialog,
                            modifier = Modifier.width(180.dp)
                        ) { isFocused ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = if (isFocused) DouyinCyan else TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "输入自定义地址",
                                    color = if (isFocused) DouyinCyan else TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // 3. 核心大屏功能卡片区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 主功能卡片：一键全屏畅播
                    TvFocusableCard(
                        focusRequester = heroPlayFocusRequester,
                        testTagValue = "tv_hero_play_card",
                        onClick = { onEnterPlayer("") },
                        focusedBorderColor = DouyinRed,
                        modifier = Modifier
                            .weight(1.8f)
                            .height(112.dp)
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isFocused) DouyinRed else DouyinRed.copy(alpha = 0.2f),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "一键全屏畅播（在线优先）",
                                    color = TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "无需房间号直接开播，原生 ExoPlayer HLS，遥控器上下键随时切台",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // 辅助功能卡片：屏幕常亮守护
                    TvFocusableCard(
                        testTagValue = "tv_keep_screen_on_card",
                        onClick = {
                            val next = !keepScreenOn
                            keepScreenOn = next
                            preferences.keepScreenOn = next
                            Toast.makeText(
                                context,
                                if (next) "大屏屏幕常亮已开启" else "大屏屏幕常亮已关闭",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .weight(1.2f)
                            .height(112.dp)
                    ) { isFocused ->
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = if (isFocused) DouyinCyan else DouyinGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "大屏常亮守护",
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (keepScreenOn) "常亮守护运行中（无黑屏休眠）" else "系统默认休眠策略",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // 4. 底部遥控器快捷指南
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceElevated.copy(alpha = 0.72f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Text("【方向键】选择频道与功能", color = TextSecondary, fontSize = 12.sp)
                    Text("•", color = TextTertiary)
                    Text("【OK 键】直接开播 / 信息面板", color = DouyinCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("•", color = TextTertiary)
                    Text("【↑ / ↓ 或 CH+/CH-】秒级换台", color = DouyinGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("•", color = TextTertiary)
                    Text("【菜单/返回键】选台/退出", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    if (showChannelDialog) {
        ChannelManageDialog(
            activeBaseUrl = activeBaseUrl,
            onDismiss = {
                showChannelDialog = false
                channels = channelManager.loadChannels()
            },
            onSelectChannelToPlay = { channel: Channel ->
                showChannelDialog = false
                val intent = Intent(context, TvFullscreenPlayerActivity::class.java).apply {
                    putExtra(TvFullscreenPlayerActivity.EXTRA_RID, channel.rid)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_LINE, currentLine)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_BASE_URL, activeBaseUrl)
                    putExtra(TvFullscreenPlayerActivity.EXTRA_TITLE, channel.name)
                }
                context.startActivity(intent)
            }
        )
    }

    if (showClearCacheDialog) {
        ClearCacheConfirmDialog(
            onDismiss = { showClearCacheDialog = false },
            onConfirm = {
                showClearCacheDialog = false
                preferences.recordCacheCleared()
                TvFullscreenPlayerActivity.clearStreamCache()
                Toast.makeText(context, "电视端缓存与流地址缓存已彻底清空", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showUpdateDialog) {
        UpdateDialog(
            onDismiss = { showUpdateDialog = false },
            onOpenReleasePage = { url ->
                UpdateManager.openReleasePage(context, url)
                showUpdateDialog = false
            }
        )
    }
}
