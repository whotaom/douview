package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tv.channel.Channel
import com.example.tv.channel.ChannelManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 电视端频道管理弹窗（与 tv_fuxiaoya_com 网页版完全对齐）
 * 1. 频道列表浏览与删除；
 * 2. 导出为 JSON / 纯房间号 TXT 文件；
 * 3. 远程导入（支持直接拉取与网页版安全代理，支持合并/替换模式）；
 * 4. 一键恢复官方默认频道。
 */
@Composable
fun ChannelManageDialog(
    onDismiss: () -> Unit,
    onSelectChannelToPlay: (Channel) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val channelManager = remember { ChannelManager(context) }

    var channels by remember { mutableStateOf(channelManager.loadChannels()) }
    var remoteUrlInput by remember { mutableStateOf("") }
    var replaceMode by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxHeight(0.85f),
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
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
                                modifier = Modifier.weight(1f),
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
                                            baseUrl = "https://tv.fuxiaoya.com",
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
                                colors = ButtonDefaults.buttonColors(containerColor = DouyinCyan)
                            ) {
                                Text(if (isImporting) "导入中..." else "立即拉取", color = Color.Black, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = replaceMode,
                                onCheckedChange = { replaceMode = it },
                                colors = CheckboxDefaults.colors(checkedColor = DouyinCyan)
                            )
                            Text(
                                text = "替换现有频道（默认合并去重）",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.clickable { replaceMode = !replaceMode }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "快捷预置源：",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            SuggestionChip(
                                onClick = { remoteUrlInput = "https://tv.fuxiaoya.com/dy.json" },
                                label = { Text("官方主线 (dy.json)", fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = DarkBg,
                                    labelColor = DouyinCyan
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = DarkSurfaceBorder
                                )
                            )
                            SuggestionChip(
                                onClick = { remoteUrlInput = "https://tt.fuxiaoya.com/dy.json" },
                                label = { Text("备用加速 (tt.fuxiaoya.com)", fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = DarkBg,
                                    labelColor = DouyinGreen
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = DarkSurfaceBorder
                                )
                            )
                        }
                    }
                }

                // 2. 导出与重置工具栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val file = channelManager.exportJson(channels)
                            Toast.makeText(context, "已导出 JSON: ${file.name}", Toast.LENGTH_LONG).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出 JSON", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val file = channelManager.exportTxt(channels)
                            Toast.makeText(context, "已导出 TXT: ${file.name}", Toast.LENGTH_LONG).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出 TXT", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val defaults = channelManager.resetToDefault()
                            channels = defaults
                            Toast.makeText(context, "已恢复官方默认频道列表", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DouyinRed),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(imageVector = Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("恢复默认", fontSize = 12.sp)
                    }
                }

                // 3. 当前频道列表（点击直接开播，支持删除）
                Text(
                    text = "当前频道列表（点击直接全屏开播）：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(channels) { channel ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChannelToPlay(channel) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (channel.isLive) DouyinGreen else Color.Gray,
                                                CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = channel.name,
                                            color = TextPrimary,
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

                                IconButton(
                                    onClick = {
                                        val updated = channels.filter { it.rid != channel.rid }
                                        channelManager.saveChannels(updated)
                                        channels = updated
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "删除频道",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
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
                colors = ButtonDefaults.buttonColors(containerColor = DouyinCyan)
            ) {
                Text("关闭", color = Color.Black)
            }
        }
    )
}
