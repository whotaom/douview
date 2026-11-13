package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.tv.components.safeRequestFocus
import com.example.tv.components.tvInteractive
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

    val confirmFocusRequester = remember { FocusRequester() }
    var isConfirmFocused by remember { mutableStateOf(false) }
    var isCancelFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        confirmFocusRequester.safeRequestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = 560.dp),
        title = {
            Text(
                text = "更换中控服务地址",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_url_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = DouyinCyan,
                        unfocusedBorderColor = DarkSurfaceBorder,
                        focusedContainerColor = DarkSurfaceElevated,
                        unfocusedContainerColor = DarkSurfaceElevated
                    )
                )

                // 记忆的历史地址 (最多5个) - 完整 D-pad 聚焦与高亮反馈
                if (historyUrls.isNotEmpty()) {
                    Text(
                        text = "记忆切换地址 (${historyUrls.size}/${LivePreferences.MAX_HISTORY_URLS})：",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        historyUrls.take(LivePreferences.MAX_HISTORY_URLS).forEach { histUrl ->
                            var isRowFocused by remember { mutableStateOf(false) }
                            var isDeleteFocused by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isRowFocused) DarkSurfaceElevated else DarkSurfaceElevated.copy(alpha = 0.78f),
                                    border = BorderStroke(
                                        if (isRowFocused) 2.dp else 1.dp,
                                        if (isRowFocused) DouyinCyan else DarkSurfaceBorder
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .tvInteractive(
                                            onClick = { inputUrl = histUrl },
                                            onFocusedChange = { isRowFocused = it }
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.History,
                                            contentDescription = null,
                                            tint = DouyinCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = histUrl,
                                            fontSize = 12.sp,
                                            color = if (isRowFocused) DouyinCyan else TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isDeleteFocused) DouyinRed.copy(alpha = 0.25f) else DarkSurfaceElevated,
                                    border = BorderStroke(
                                        if (isDeleteFocused) 2.dp else 1.dp,
                                        if (isDeleteFocused) DouyinRed else DarkSurfaceBorder
                                    ),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .tvInteractive(
                                            onClick = {
                                                preferences.removeHistoryUrl(histUrl)
                                                historyUrls = preferences.getHistoryUrls()
                                            },
                                            onFocusedChange = { isDeleteFocused = it }
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier.size(40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "删除记录",
                                            tint = if (isDeleteFocused) DouyinRed else TextSecondary,
                                            modifier = Modifier.size(16.dp)
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    var chip1Focused by remember { mutableStateOf(false) }
                    var chip2Focused by remember { mutableStateOf(false) }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        border = BorderStroke(
                            if (chip1Focused) 2.dp else 1.dp,
                            if (chip1Focused) DouyinCyan else DarkSurfaceBorder
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .tvInteractive(
                                onClick = { inputUrl = LivePreferences.DEFAULT_URL },
                                onFocusedChange = { chip1Focused = it }
                            )
                    ) {
                        Text(
                            text = "线路一 (默认)",
                            color = DouyinCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        border = BorderStroke(
                            if (chip2Focused) 2.dp else 1.dp,
                            if (chip2Focused) DouyinRed else DarkSurfaceBorder
                        ),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .tvInteractive(
                                onClick = { inputUrl = LivePreferences.LINE_TWO_URL },
                                onFocusedChange = { chip2Focused = it }
                            )
                    ) {
                        Text(
                            text = "线路二 (备用)",
                            color = DouyinRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
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
                border = if (isConfirmFocused) BorderStroke(2.5.dp, DouyinCyan) else null,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dialog_url_confirm")
                    .focusRequester(confirmFocusRequester)
                    .onFocusChanged { isConfirmFocused = it.isFocused }
            ) {
                Text("确定生效", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(
                    if (isCancelFocused) 2.dp else 1.dp,
                    if (isCancelFocused) DouyinCyan else DarkSurfaceBorder
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dialog_url_cancel")
                    .onFocusChanged { isCancelFocused = it.isFocused }
            ) {
                Text("取消", color = if (isCancelFocused) DouyinCyan else TextSecondary)
            }
        }
    )
}

@Composable
fun ClearCacheConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    var isCancelFocused by remember { mutableStateOf(false) }
    var isConfirmFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        cancelFocusRequester.safeRequestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = 520.dp),
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
                "将清除应用网络缓存、流地址缓存与临时文件。已保存的直播间列表与基础配置会尽量保留。",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                border = if (isConfirmFocused) BorderStroke(2.5.dp, DouyinCyan) else null,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dialog_clear_cache_confirm")
                    .onFocusChanged { isConfirmFocused = it.isFocused }
            ) {
                Text("确认清理", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(
                    if (isCancelFocused) 2.5.dp else 1.dp,
                    if (isCancelFocused) DouyinCyan else DarkSurfaceBorder
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dialog_clear_cache_cancel")
                    .focusRequester(cancelFocusRequester)
                    .onFocusChanged { isCancelFocused = it.isFocused }
            ) {
                Text("取消", color = if (isCancelFocused) DouyinCyan else TextSecondary)
            }
        }
    )
}

/**
 * 在线版本检查与合规发布页导航弹窗
 */
@Composable
fun UpdateDialog(
    onDismiss: () -> Unit,
    onOpenReleasePage: (url: String) -> Unit
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var releaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val closeFocusRequester = remember { FocusRequester() }
    var isCloseFocused by remember { mutableStateOf(false) }
    var isOpenPageFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        closeFocusRequester.safeRequestFocus()
    }

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
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = 560.dp),
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
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                            .heightIn(max = 150.dp)
                    ) {
                        Text(
                            text = info.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (releaseInfo != null && releaseInfo!!.hasUpdate) {
                val info = releaseInfo!!
                Button(
                    onClick = {
                        onOpenReleasePage(info.releasePageUrl)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DouyinRed,
                        contentColor = Color.White
                    ),
                    border = if (isOpenPageFocused) BorderStroke(2.5.dp, DouyinCyan) else null,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("dialog_update_open_page")
                        .onFocusChanged { isOpenPageFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "前往发布页",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(
                    if (isCloseFocused) 2.5.dp else 1.dp,
                    if (isCloseFocused) DouyinCyan else DarkSurfaceBorder
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dialog_update_close")
                    .focusRequester(closeFocusRequester)
                    .onFocusChanged { isCloseFocused = it.isFocused }
            ) {
                Text("关闭", color = if (isCloseFocused) DouyinCyan else TextSecondary)
            }
        }
    )
}
