package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.LivePreferences
import com.example.data.StreamLines
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import com.example.tv.components.safeRequestFocus
import com.example.tv.components.tvInteractive
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 电视端频道管理弹窗（宽屏双栏 10-foot 适配 + 完整 D-pad 焦点高亮）
 * 1. 移除窄屏 AlertDialog 宽度约束，大屏自动采用左控右列双栏布局，窄屏自适应堆叠且列表保底可视；
 * 2. 动作按钮采用 2x2 网格 + 紧凑内边距，彻底避免按钮文字挤压换行或被截断；
 * 3. 仅保留默认主线（tv.fuxiaoya.com）预置源，移除线路二。
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = DarkSurface,
            border = BorderStroke(1.dp, DarkSurfaceBorder),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .widthIn(max = 880.dp)
                .fillMaxHeight(0.88f)
                .heightIn(max = 540.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isWideLayout = maxWidth >= 560.dp

                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部标题栏 + 关闭按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = DouyinCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "频道管理与导入/导出",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceElevated
                            ) {
                                Text(
                                    text = "共 ${channels.size} 台 · 在线 ${channels.count { it.isLive }} 台",
                                    fontSize = 12.sp,
                                    color = DouyinCyan,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            border = if (isCloseFocused) BorderStroke(2.5.dp, Color.White) else null,
                            colors = ButtonDefaults.buttonColors(containerColor = DouyinCyan),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("channel_dialog_close")
                                .focusRequester(closeFocusRequester)
                                .onFocusChanged { isCloseFocused = it.isFocused }
                        ) {
                            Text(
                                text = "关闭",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    HorizontalDivider(color = DarkSurfaceBorder, thickness = 1.dp)

                    val controlsSection: @Composable (Modifier) -> Unit = { sectionModifier ->
                        Column(
                            modifier = sectionModifier,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 远程导入区
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = DarkSurfaceElevated,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "远程导入（支持 JSON / TXT 源）：",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = remoteUrlInput,
                                            onValueChange = { remoteUrlInput = it },
                                            singleLine = true,
                                            placeholder = {
                                                Text(
                                                    "https://.../channels.json",
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
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
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                            border = if (isImportBtnFocused) BorderStroke(2.5.dp, Color.White) else null,
                                            colors = ButtonDefaults.buttonColors(containerColor = DouyinCyan),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("channel_dialog_import_btn")
                                                .onFocusChanged { isImportBtnFocused = it.isFocused }
                                        ) {
                                            Text(
                                                text = if (isImporting) "导入中" else "立即拉取",
                                                color = Color.Black,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    var replaceRowFocused by remember { mutableStateOf(false) }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (replaceRowFocused) DarkBg else Color.Transparent,
                                        border = if (replaceRowFocused) BorderStroke(1.5.dp, DouyinCyan) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .tvInteractive(
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
                                                color = if (replaceRowFocused) DouyinCyan else TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // 仅保留单一默认主线预置源
                                    var presetFocused by remember { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "默认预置源：",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = DarkBg,
                                            border = BorderStroke(
                                                if (presetFocused) 2.dp else 1.dp,
                                                if (presetFocused) DouyinCyan else DarkSurfaceBorder
                                            ),
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .minimumInteractiveComponentSize()
                                                .tvInteractive(
                                                    onClick = { remoteUrlInput = "${StreamLines.LINE_1}/dy.json" },
                                                    testTagValue = "channel_dialog_preset_default",
                                                    onFocusedChange = { presetFocused = it }
                                                )
                                        ) {
                                            Text(
                                                text = "默认主线 (tv.fuxiaoya.com)",
                                                color = DouyinCyan,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 2x2 动作按钮网格：宽松宽度 + 明确 Padding，彻底杜绝文字被挤扁截断
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusGroup(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DialogToolButton(
                                        text = if (isImporting) "检测中..." else "立即测活",
                                        icon = Icons.Default.Refresh,
                                        enabled = !isImporting,
                                        accentColor = DouyinCyan,
                                        testTagValue = "channel_dialog_probe_btn",
                                        modifier = Modifier.weight(1f),
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
                                        }
                                    )

                                    DialogToolButton(
                                        text = "恢复默认",
                                        icon = Icons.Default.RestartAlt,
                                        accentColor = DouyinRed,
                                        testTagValue = "channel_dialog_reset_btn",
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val defaults = channelManager.resetToDefault()
                                            channels = defaults
                                            Toast.makeText(context, "已恢复官方默认频道列表", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DialogToolButton(
                                        text = "导出 JSON",
                                        icon = Icons.Default.FileDownload,
                                        accentColor = DouyinCyan,
                                        testTagValue = "channel_dialog_export_json_btn",
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val file = channelManager.exportJson(channels)
                                            Toast.makeText(context, "已导出 JSON: ${file.name}", Toast.LENGTH_LONG).show()
                                        }
                                    )

                                    DialogToolButton(
                                        text = "导出 TXT",
                                        icon = Icons.Default.Description,
                                        accentColor = DouyinCyan,
                                        testTagValue = "channel_dialog_export_txt_btn",
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val file = channelManager.exportTxt(channels)
                                            Toast.makeText(context, "已导出 TXT: ${file.name}", Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            }
                        }
                    }

                    val listSection: @Composable (Modifier) -> Unit = { sectionModifier ->
                        Column(
                            modifier = sectionModifier,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "当前频道列表（按 OK 直接全屏开播，右移可删除）：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                            shape = RoundedCornerShape(10.dp),
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
                                            shape = RoundedCornerShape(10.dp),
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
                                                modifier = Modifier.size(48.dp),
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
                    }

                    if (isWideLayout) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            controlsSection(
                                Modifier
                                    .weight(0.45f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                            )
                            VerticalDivider(color = DarkSurfaceBorder, thickness = 1.dp)
                            listSection(
                                Modifier
                                    .weight(0.55f)
                                    .fillMaxHeight()
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            controlsSection(Modifier.fillMaxWidth())
                            listSection(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .heightIn(min = 160.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogToolButton(
    text: String,
    icon: ImageVector,
    accentColor: Color,
    testTagValue: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) accentColor else DarkSurfaceBorder
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (accentColor == DouyinRed) DouyinRed else TextPrimary
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .testTag(testTagValue)
            .onFocusChanged { focused = it.isFocused }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (focused) accentColor else if (accentColor == DouyinRed) DouyinRed else DouyinCyan,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
