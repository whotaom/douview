package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.StreamLines
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import com.example.tv.components.safeRequestFocus
import com.example.tv.components.tvInteractive
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 电视端频道管理弹窗（完整 D-pad 焦点高亮 + TV 安全尺寸）
 * 1. 频道列表浏览、D-pad 高亮开播与删除；
 * 2. 导出为 JSON / 纯房间号 TXT 文件；
 * 3. 远程导入（支持直接拉取与网页版安全代理，支持合并/替换模式）；
 * 4. 一键恢复官方默认频道。
 */
@Composable
fun ChannelManageDialog(
    activeBaseUrl: String? = null,
    onDismiss: () -> Unit,
    onSelectChannelToPlay: (Channel) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val channelManager = remember { ChannelManager(context) }
    val preferences = remember { LivePreferences(context) }
    val resolvedBaseUrl = activeBaseUrl?.takeIf { it.isNotBlank() } ?: preferences.activeBaseUrl

    var channels by remember { mutableStateOf(channelManager.loadChannels()) }
    var remoteUrlInput by remember { mutableStateOf("") }
    var replaceMode by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    val closeFocusRequester = remember { FocusRequester() }
    var isCloseFocused by remember { mutableStateOf(false) }
    var isImportBtnFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        closeFocusRequester.safeRequestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = 720.dp)
            .fillMaxHeight(0.86f),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = DouyinCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "频道管理与导入/导出",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Text(
                    text = "共 ${channels.size} 个频道",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 远程导入区域
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "远程导入（支持网页版 JSON / TXT 源）：",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = remoteUrlInput,
                                onValueChange = { remoteUrlInput = it },
                                singleLine = true,
                                placeholder = { Text("https://.../channels.json", fontSize = 12.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("channel_dialog_remote_input"),
                                shape = RoundedCornerShape(8.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedBorderColor = DouyinCyan,
                                    unfocusedBorderColor = DarkSurfaceBorder,
                                    focusedContainerColor = DarkBg,
                                    unfocusedContainerColor = DarkBg
                                )
                            )

                            Button(
                                onClick = {
                                    if (remoteUrlInput.isBlank()) {
                                        Toast.makeText(context, "请输入有效的远程源地址", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    isImporting = true
                                    coroutineScope.launch {
                                        val res = channelManager.remoteImportViaProxy(
                                            baseUrl = resolvedBaseUrl,
                                            remoteUrl = remoteUrlInput
                                        )
                                        isImporting = false
                                        res.onSuccess { newList ->
                                            val merged = if (replaceMode) newList else channelManager.mergeChannels(channels, newList)
                                            channelManager.saveChannels(merged)
                                            channels = merged
                                            Toast.makeText(context, "成功导入 ${newList.size} 个频道", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, "导入失败: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                enabled = !isImporting,
                                shape = RoundedCornerShape(8.dp),
                                border = if (isImportBtnFocused) BorderStroke(2.5.dp, Color.White) else null,
                                colors = ButtonDefaults.buttonColors(containerColor = DouyinCyan),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("channel_dialog_import_btn")
                                    .onFocusChanged { isImportBtnFocused = it.isFocused }
                            ) {
                                Text(
                                    text = if (isImporting) "导入中..." else "立即拉取",
                                    color = Color.Black,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        var replaceRowFocused by remember { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (replaceRowFocused) DarkBg else Color.Transparent,
                            border = if (replaceRowFocused) BorderStroke(1.5.dp, DouyinCyan) else null,
                            modifier = Modifier.tvInteractive(
                                onClick = { replaceMode = !replaceMode },
                                onFocusedChange = { replaceRowFocused = it }
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = replaceMode,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = DouyinCyan)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "替换现有频道（默认合并去重）",
                                    fontSize = 12.sp,
                                    color = if (replaceRowFocused) DouyinCyan else TextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.focusGroup(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "快捷预置源：",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            var preset1Focused by remember { mutableStateOf(false) }
                            var preset2Focused by remember { mutableStateOf(false) }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkBg,
                                border = BorderStroke(
                                    if (preset1Focused) 2.dp else 1.dp,
                                    if (preset1Focused) DouyinCyan else DarkSurfaceBorder
                                ),
                                modifier = Modifier.tvInteractive(
                                    onClick = { remoteUrlInput = "${StreamLines.LINE_1}/dy.json" },
                                    onFocusedChange = { preset1Focused = it }
                                )
                            ) {
                                Text(
                                    text = "主线 (tv.fuxiaoya.com)",
                                    color = DouyinCyan,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkBg,
                                border = BorderStroke(
                                    if (preset2Focused) 2.dp else 1.dp,
                                    if (preset2Focused) DouyinGreen else DarkSurfaceBorder
                                ),
                                modifier = Modifier.tvInteractive(
                                    onClick = { remoteUrlInput = "${StreamLines.LINE_2}/dy.json" },
                                    onFocusedChange = { preset2Focused = it }
                                )
                            ) {
                                Text(
                                    text = "线路二 (dy.whomao.com)",
                                    color = DouyinGreen,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // 2. 导出与测活重置工具栏（focusGroup + 焦点高亮边框）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    var fProbe by remember { mutableStateOf(false) }
                    var fJson by remember { mutableStateOf(false) }
                    var fTxt by remember { mutableStateOf(false) }
                    var fReset by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = {
                            isImporting = true
                            coroutineScope.launch {
                                val outcome = channelManager.probeLiveStatus(resolvedBaseUrl, channels)
                                isImporting = false
                                if (outcome.networkFailed) {
                                    Toast.makeText(context, "在线检测失败，请检查网络", Toast.LENGTH_SHORT).show()
                                } else {
                                    channelManager.saveChannels(outcome.channels)
                                    channels = outcome.channels
                                    val msg = if (outcome.unknownCount > 0) {
                                        "测活完成：在线 ${outcome.liveCount} 台 · ${outcome.unknownCount} 台状态未知"
                                    } else {
                                        "测活完成：共 ${outcome.channels.size} 台 · 在线 ${outcome.liveCount} 台"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isImporting,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (fProbe) 2.dp else 1.dp, if (fProbe) DouyinCyan else DarkSurfaceBorder),
                        modifier = Modifier
                            .weight(1.1f)
                            .minimumInteractiveComponentSize()
                            .onFocusChanged { fProbe = it.isFocused }
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isImporting) "检测中" else "立即测活", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val file = channelManager.exportJson(channels)
                            Toast.makeText(context, "已导出 JSON: ${file.name}", Toast.LENGTH_LONG).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (fJson) 2.dp else 1.dp, if (fJson) DouyinCyan else DarkSurfaceBorder),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .onFocusChanged { fJson = it.isFocused }
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出 JSON", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val file = channelManager.exportTxt(channels)
                            Toast.makeText(context, "已导出 TXT: ${file.name}", Toast.LENGTH_LONG).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (fTxt) 2.dp else 1.dp, if (fTxt) DouyinCyan else DarkSurfaceBorder),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .onFocusChanged { fTxt = it.isFocused }
                    ) {
                        Icon(imageVector = Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出 TXT", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val defaults = channelManager.resetToDefault()
                            channels = defaults
                            Toast.makeText(context, "已恢复官方默认频道列表", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (fReset) 2.dp else 1.dp, if (fReset) DouyinRed else DarkSurfaceBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DouyinRed),
                        modifier = Modifier
                            .weight(1.1f)
                            .minimumInteractiveComponentSize()
                            .onFocusChanged { fReset = it.isFocused }
                    ) {
                        Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("恢复默认", fontSize = 12.sp)
                    }
                }

                // 3. 当前频道列表（点击或按 OK 直接开播，支持方向键右移删除）
                Text(
                    text = "当前频道列表（按 OK 直接全屏开播，右移可删除）：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusGroup(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { it.rid }) { channel ->
                        var rowFocused by remember { mutableStateOf(false) }
                        var delFocused by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (rowFocused) DarkSurfaceElevated else DarkSurfaceElevated.copy(alpha = 0.8f),
                                border = BorderStroke(
                                    if (rowFocused) 2.5.dp else 1.dp,
                                    if (rowFocused) DouyinCyan else DarkSurfaceBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .tvInteractive(
                                        onClick = { onSelectChannelToPlay(channel) },
                                        testTagValue = "channel_manage_row_${channel.rid}",
                                        onFocusedChange = { rowFocused = it }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = channel.name,
                                            color = if (rowFocused) DouyinCyan else TextPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "房间号: ${channel.rid}${if (channel.title.isNotBlank()) " · ${channel.title}" else ""}",
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (delFocused) DouyinRed.copy(alpha = 0.25f) else DarkSurfaceElevated,
                                border = BorderStroke(
                                    if (delFocused) 2.dp else 1.dp,
                                    if (delFocused) DouyinRed else DarkSurfaceBorder
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .tvInteractive(
                                        onClick = {
                                            val updated = channels.filter { it.rid != channel.rid }
                                            channelManager.saveChannels(updated)
                                            channels = updated
                                        },
                                        onFocusedChange = { delFocused = it }
                                    )
                            ) {
                                Box(
                                    modifier = Modifier.size(44.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除频道",
                                        tint = if (delFocused) DouyinRed else TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp),
                border = if (isCloseFocused) BorderStroke(2.5.dp, Color.White) else null,
                colors = ButtonDefaults.buttonColors(containerColor = DouyinCyan),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("channel_dialog_close")
                    .focusRequester(closeFocusRequester)
                    .onFocusChanged { isCloseFocused = it.isFocused }
            ) {
                Text("关闭", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    )
}
