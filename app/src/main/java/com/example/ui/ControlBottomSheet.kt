package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.data.StreamLines
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlBottomSheet(
    currentUrl: String,
    pageTitle: String,
    keepScreenOn: Boolean,
    isDesktopUa: Boolean,
    forceLandscape: Boolean,
    onDismissRequest: () -> Unit,
    onReload: () -> Unit,
    onKeepScreenOnToggle: (Boolean) -> Unit,
    onDesktopUaToggle: (Boolean) -> Unit,
    onOrientationToggle: () -> Unit,
    onOpenUrlDialog: () -> Unit,
    onOpenQuickRoomDialog: () -> Unit = {},
    onClearCache: () -> Unit,
    fullscreenOrientationMode: Int = 0,
    onOrientationModeSelect: (Int) -> Unit = {},
    onHealAudioStream: () -> Unit = {},
    onLaunchNativePlayer: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onSwitchLine: (String) -> Unit = {},
    autoClearCacheEnabled: Boolean = true,
    onAutoClearCacheToggle: (Boolean) -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DouyinRed.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = DouyinRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "抖眸流控 · 控制中心",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (pageTitle.isNotBlank()) pageTitle else "抖音直播聚合中控台",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DouyinGreen.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(DouyinGreen)
                        )
                        Text(
                            text = "在线",
                            style = MaterialTheme.typography.labelSmall,
                            color = DouyinGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Current URL Info Card
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "当前服务节点",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        TextButton(
                            onClick = onOpenUrlDialog,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = DouyinCyan
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("切换地址", color = DouyinCyan, fontSize = 12.sp)
                        }
                    }

                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )

                    // 线路一与线路二一键直达
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isLine1 = currentUrl.startsWith(StreamLines.LINE_1)
                        val isLine2 = currentUrl.startsWith(StreamLines.LINE_2)
                        SuggestionChip(
                            onClick = { onSwitchLine(LivePreferences.DEFAULT_URL) },
                            label = { Text("线路一 (主线)", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (isLine1) DouyinCyan.copy(alpha = 0.2f) else DarkSurface,
                                labelColor = if (isLine1) DouyinCyan else TextSecondary
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = if (isLine1) DouyinCyan else DarkSurfaceBorder
                            )
                        )
                        SuggestionChip(
                            onClick = { onSwitchLine(StreamLines.LINE_2) },
                            label = { Text("线路二 (备用)", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (isLine2) DouyinRed.copy(alpha = 0.2f) else DarkSurface,
                                labelColor = if (isLine2) DouyinRed else TextSecondary
                            ),
                            border = SuggestionChipDefaults.suggestionChipBorder(
                                enabled = true,
                                borderColor = if (isLine2) DouyinRed else DarkSurfaceBorder
                            )
                        )
                    }
                }
            }

            // Picture-in-Picture (PiP) Seamless Floating Mode Launcher
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEnterPip() }
                    .focusable(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(DouyinCyan.copy(alpha = 0.35f))
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(DouyinCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = null,
                                tint = DouyinCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "画中画浮窗播放 (PiP)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "与网页端流控无缝接洽 · 置顶小窗 · 边聊边看",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = DouyinCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Quick Actions Grid
            Text(
                text = "快捷操作",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.VolumeUp,
                    label = "声画自愈",
                    accentColor = DouyinCyan,
                    modifier = Modifier.weight(1f),
                    onClick = onHealAudioStream
                )

                QuickActionButton(
                    icon = Icons.Default.Refresh,
                    label = "刷新重载",
                    accentColor = DouyinGreen,
                    modifier = Modifier.weight(1f),
                    onClick = onReload
                )

                QuickActionButton(
                    icon = Icons.Default.ScreenRotation,
                    label = when (fullscreenOrientationMode) {
                        1 -> "横屏全屏"
                        2 -> "竖屏全屏"
                        else -> "智能流向"
                    },
                    accentColor = Color(0xFFFFB74D),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val next = (fullscreenOrientationMode + 1) % 3
                        onOrientationModeSelect(next)
                    }
                )
            }

            Divider(color = DarkSurfaceBorder, thickness = 0.8.dp)

            // Preference Toggles List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingSwitchRow(
                    icon = Icons.Default.LightMode,
                    title = "屏幕防休眠常亮",
                    subtitle = "长时间监控或观看直播时防止手机自动锁屏",
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOnToggle
                )

                SettingSwitchRow(
                    icon = Icons.Default.DesktopMac,
                    title = "桌面版标识 (User-Agent)",
                    subtitle = "切换为PC桌面UA，适合平板宽屏多列中控布局",
                    checked = isDesktopUa,
                    onCheckedChange = onDesktopUaToggle
                )

                SettingSwitchRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "定期自动巡检清理缓存 (24小时)",
                    subtitle = "每24小时自动巡检并清理过期临时网络分片，保持应用轻盈疾速",
                    checked = autoClearCacheEnabled,
                    onCheckedChange = onAutoClearCacheToggle
                )
            }

            // System Actions: Check Update & Clear Cache
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckUpdate,
                    modifier = Modifier.weight(1f).focusable(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DouyinCyan),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(DouyinCyan.copy(alpha = 0.4f))
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("检查版本更新", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onClearCache,
                    modifier = Modifier.weight(1f).focusable(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("清理网络缓存", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.focusable(),
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceElevated,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (checked) DouyinRed.copy(alpha = 0.15f) else DarkSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) DouyinRed else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = DouyinRed,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = DarkSurfaceElevated
            )
        )
    }
}
