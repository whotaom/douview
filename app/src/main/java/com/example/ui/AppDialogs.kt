package com.example.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.ui.theme.*
import com.example.util.AppReleaseInfo
import com.example.util.UpdateManager

@Composable
fun ChangeUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirmUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { LivePreferences(context) }
    var inputUrl by remember { mutableStateOf(currentUrl) }
    var historyUrls by remember { mutableStateOf(preferences.getHistoryUrls()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier.widthIn(max = 560.dp),
        title = {
            Text(
                text = "更换中控服务地址",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "输入自定义中控台或前端部署地址（自动记忆，最多5个）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = inputUrl,
                    onValueChange = { inputUrl = it },
                    singleLine = true,
                    placeholder = { Text("https://...") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = DouyinRed,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedContainerColor = DarkSurfaceElevated,
                        unfocusedContainerColor = DarkSurfaceElevated
                    )
                )

                // 记忆的历史地址 (最多5个)
                if (historyUrls.isNotEmpty()) {
                    Text(
                        text = "记忆切换地址 (${historyUrls.size}/${LivePreferences.MAX_HISTORY_URLS})：",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        historyUrls.take(LivePreferences.MAX_HISTORY_URLS).forEach { histUrl ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceElevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { inputUrl = histUrl }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = DouyinCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = histUrl,
                                            fontSize = 12.sp,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            preferences.removeHistoryUrl(histUrl)
                                            historyUrls = preferences.getHistoryUrls()
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除记录",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 快捷预设地址
                Text(
                    text = "快捷默认预设：",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(
                        onClick = { inputUrl = LivePreferences.DEFAULT_URL },
                        label = { Text("线路一 (默认)", fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = DarkSurfaceElevated,
                            labelColor = DouyinCyan
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = DarkSurfaceBorder
                        )
                    )
                    SuggestionChip(
                        onClick = { inputUrl = LivePreferences.LINE_TWO_URL },
                        label = { Text("线路二 (备用)", fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = DarkSurfaceElevated,
                            labelColor = DouyinRed
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = DarkSurfaceBorder
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inputUrl.isNotBlank()) {
                        val trimmed = inputUrl.trim()
                        preferences.recordHistoryUrl(trimmed)
                        onConfirmUrl(trimmed)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("确定生效")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

@Composable
fun ClearCacheConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        icon = {
            Icon(
                imageVector = Icons.Default.DeleteSweep,
                contentDescription = null,
                tint = DouyinRed,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text("确认清理缓存？", color = TextPrimary)
        },
        text = {
            Text(
                "将清除 WebView 的网络缓存、临时文件与本地 Cookie。已保存的直播间列表可能会被重置。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("确认清理")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        }
    )
}

/**
 * 在线版本检查与直接更新弹窗
 */
@Composable
fun UpdateDialog(
    onDismiss: () -> Unit,
    onDownloadApk: (url: String) -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var releaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val result = UpdateManager.checkUpdate(context)
        isLoading = false
        result.fold(
            onSuccess = { releaseInfo = it },
            onFailure = { errorMessage = it.localizedMessage ?: "检查更新失败" }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier.widthIn(max = 560.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = DouyinCyan,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "版本更新检查",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = DouyinCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在检索在线最新版本...", color = TextSecondary, fontSize = 13.sp)
                    }
                } else if (errorMessage != null) {
                    Text(
                        text = "无法连接至发布节点: $errorMessage",
                        color = DouyinRed,
                        fontSize = 13.sp
                    )
                } else if (releaseInfo != null) {
                    val info = releaseInfo!!
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DarkSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = info.title,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (info.hasUpdate) DouyinRed.copy(alpha = 0.2f) else DouyinGreen.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = if (info.hasUpdate) "有新版: ${info.latestVersion}" else "已是最新版",
                                        color = if (info.hasUpdate) DouyinRed else DouyinGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Text(
                                text = "当前运行版本: v${info.currentVersion}",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Text(
                        text = "更新说明：",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                    ) {
                        Text(
                            text = info.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (releaseInfo != null) {
                val info = releaseInfo!!
                Button(
                    onClick = {
                        val targetUrl = info.apkUrl ?: info.releasePageUrl
                        onDownloadApk(targetUrl)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (info.hasUpdate) DouyinRed else DouyinCyan,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .focusable()
                        .height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "下载安装",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusable()) {
                Text("关闭", color = TextSecondary)
            }
        }
    )
}
