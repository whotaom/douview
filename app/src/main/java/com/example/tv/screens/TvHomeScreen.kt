package com.example.tv.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.tv.components.TvActionButton
import com.example.tv.components.TvFocusableCard
import com.example.tv.components.TvTopBar
import com.example.ui.ClearCacheConfirmDialog
import com.example.ui.UpdateDialog
import com.example.ui.theme.*
import com.example.util.UpdateManager

@Composable
fun TvHomeScreen(
    onEnterPlayer: (url: String) -> Unit,
    onOpenUrlDialog: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { LivePreferences(context) }
    var currentTargetUrl by remember { mutableStateOf(preferences.targetUrl) }
    var historyUrls by remember { mutableStateOf(preferences.getHistoryUrls()) }
    var isDesktopUa by remember { mutableStateOf(preferences.isDesktopUa) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // 顶部大屏标题栏
        TvTopBar(
            title = "抖眸流控 · 电视大屏监控中控台",
            subtitle = "当前中控地址: $currentTargetUrl"
        ) {
            TvActionButton(
                text = if (isDesktopUa) "桌面 UA" else "移动 UA",
                icon = if (isDesktopUa) Icons.Default.DesktopWindows else Icons.Default.Smartphone,
                onClick = {
                    val next = !isDesktopUa
                    preferences.isDesktopUa = next
                    isDesktopUa = next
                    Toast.makeText(context, if (next) "已切换为桌面版 UA 模式" else "已切换为移动版 UA 模式", Toast.LENGTH_SHORT).show()
                }
            )

            TvActionButton(
                text = "清理缓存",
                icon = Icons.Default.DeleteSweep,
                onClick = { showClearCacheDialog = true }
            )

            TvActionButton(
                text = "版本更新",
                icon = Icons.Default.SystemUpdate,
                isPrimary = true,
                onClick = { showUpdateDialog = true }
            )
        }

        // 主体操作内容区
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // 1. 中控线路切换通道 (横向 D-pad 导航)
                Text(
                    text = "快速线路通道（遥控器左右选择，按 OK 生效）：",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        TvFocusableCard(
                            onClick = {
                                preferences.targetUrl = LivePreferences.DEFAULT_URL
                                currentTargetUrl = LivePreferences.DEFAULT_URL
                                Toast.makeText(context, "已切换至线路一（默认中控）", Toast.LENGTH_SHORT).show()
                            },
                            isHighlighted = currentTargetUrl == LivePreferences.DEFAULT_URL,
                            modifier = Modifier.width(240.dp)
                        ) { isFocused ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (currentTargetUrl == LivePreferences.DEFAULT_URL) DouyinGreen else Color.Gray, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "线路一（主线推荐）",
                                        color = if (isFocused) DouyinCyan else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = LivePreferences.DEFAULT_URL,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    item {
                        TvFocusableCard(
                            onClick = {
                                preferences.targetUrl = LivePreferences.LINE_TWO_URL
                                currentTargetUrl = LivePreferences.LINE_TWO_URL
                                Toast.makeText(context, "已切换至线路二（备用中控）", Toast.LENGTH_SHORT).show()
                            },
                            isHighlighted = currentTargetUrl == LivePreferences.LINE_TWO_URL,
                            modifier = Modifier.width(240.dp)
                        ) { isFocused ->
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(if (currentTargetUrl == LivePreferences.LINE_TWO_URL) DouyinGreen else Color.Gray, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "线路二（备用防拥堵）",
                                        color = if (isFocused) DouyinCyan else TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = LivePreferences.LINE_TWO_URL,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // 历史自定义地址
                    items(historyUrls) { histUrl ->
                        if (histUrl != LivePreferences.DEFAULT_URL && histUrl != LivePreferences.LINE_TWO_URL) {
                            TvFocusableCard(
                                onClick = {
                                    preferences.targetUrl = histUrl
                                    currentTargetUrl = histUrl
                                    Toast.makeText(context, "已切换至自定义节点", Toast.LENGTH_SHORT).show()
                                },
                                isHighlighted = currentTargetUrl == histUrl,
                                modifier = Modifier.width(240.dp)
                            ) { isFocused ->
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = DouyinCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "记忆节点",
                                            color = if (isFocused) DouyinCyan else TextPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = histUrl,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    item {
                        TvFocusableCard(
                            onClick = onOpenUrlDialog,
                            modifier = Modifier.width(200.dp)
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
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "输入自定义地址",
                                    color = if (isFocused) DouyinCyan else TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2. 核心大屏功能卡片区
                Text(
                    text = "电视大屏流控操作中心：",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 主功能卡片：进入全屏大屏监控
                    TvFocusableCard(
                        onClick = { onEnterPlayer(currentTargetUrl) },
                        focusedBorderColor = DouyinRed,
                        modifier = Modifier
                            .weight(1.8f)
                            .height(140.dp)
                    ) { isFocused ->
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isFocused) DouyinRed else DouyinRed.copy(alpha = 0.2f),
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(20.dp))
                            Column {
                                Text(
                                    text = "进入大屏直播全屏监控",
                                    color = TextPrimary,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "全屏硬件加速沉浸播放，遥控器返回键随时唤起退出浮层",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // 辅助功能卡片：保持屏幕常亮
                    TvFocusableCard(
                        onClick = {
                            val next = !preferences.keepScreenOn
                            preferences.keepScreenOn = next
                            Toast.makeText(context, if (next) "大屏屏幕常亮已开启" else "大屏屏幕常亮已关闭", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1.2f)
                            .height(140.dp)
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
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "大屏防休眠状态",
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (preferences.keepScreenOn) "常亮守护运行中（无黑屏休眠）" else "系统默认休眠策略",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // 3. 底部遥控器快捷指南
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceElevated.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Text("【方向键】移动高亮焦点", color = TextSecondary, fontSize = 12.sp)
                    Text("•", color = TextTertiary)
                    Text("【OK 键】进入大屏 / 确认生效", color = DouyinCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("•", color = TextTertiary)
                    Text("【返回键】退出全屏 / 返回上层", color = TextSecondary, fontSize = 12.sp)
                    Text("•", color = TextTertiary)
                    Text("【主页键】回到电视系统主屏幕", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }

    if (showClearCacheDialog) {
        ClearCacheConfirmDialog(
            onDismiss = { showClearCacheDialog = false },
            onConfirm = {
                showClearCacheDialog = false
                preferences.recordCacheCleared()
                Toast.makeText(context, "电视端缓存已成功彻底清空", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showUpdateDialog) {
        UpdateDialog(
            onDismiss = { showUpdateDialog = false },
            onDownloadApk = { url ->
                UpdateManager.startDownloadApk(context, url)
                showUpdateDialog = false
            }
        )
    }
}
