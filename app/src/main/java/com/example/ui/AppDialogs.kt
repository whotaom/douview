package com.example.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.data.db.StreamRecord
import com.example.data.db.StreamRecordRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ChangeUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onConfirmUrl: (String) -> Unit
) {
    var customUrl by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = DouyinCyan
                )
                Text("配置中控台接入地址", color = TextPrimary)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "快速预设线路：",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )

                // 官方综合中控台
                PresetUrlCard(
                    title = "默认中控台 (包含UI及实时播放器)",
                    url = LivePreferences.DEFAULT_URL,
                    isSelected = customUrl == LivePreferences.DEFAULT_URL,
                    onClick = { customUrl = LivePreferences.DEFAULT_URL }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "自定义私有节点 / 域名：",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )

                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it.trim() },
                    placeholder = { Text("https://your-domain.com/live/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (customUrl.isNotBlank()) {
                        onConfirmUrl(customUrl)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("确认切换")
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
fun PresetUrlCard(
    title: String,
    url: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DouyinRed.copy(alpha = 0.15f) else DarkSurfaceElevated
        ),
        border = if (isSelected) {
            ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(DouyinRed)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) DouyinRed else TextPrimary
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = DouyinRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun QuickRoomDialog(
    onDismiss: () -> Unit,
    onInjectRoom: (String) -> Unit,
    onPlayInNativeEngine: ((String) -> Unit)? = null,
    repository: StreamRecordRepository? = null
) {
    val context = LocalContext.current
    val recordRepo = remember(repository) {
        repository ?: StreamRecordRepository.getInstance(context)
    }
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var roomInput by remember { mutableStateOf("") }

    val favorites by recordRepo.favoriteRecords.collectAsState(initial = emptyList())
    val recents by recordRepo.recentRecords.collectAsState(initial = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "直播间中枢与收藏",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkSurfaceElevated,
                    contentColor = DouyinRed,
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("快速加房", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("收藏", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (favorites.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("(${favorites.size})", fontSize = 11.sp, color = Color(0xFFFFB800))
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("最近", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (recents.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("(${recents.size})", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 360.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        // 快速输入 / 粘贴口令
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "支持直接粘贴抖音分享口令、短链 (v.douyin.com/...) 或纯数字房间号：",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            OutlinedTextField(
                                value = roomInput,
                                onValueChange = { roomInput = it },
                                placeholder = { Text("例如: 7421839218 或 https://v.douyin.com/...") },
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

                            Button(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        roomInput = clip
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = DouyinCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("从剪贴板读取内容", color = TextPrimary, fontSize = 12.sp)
                            }

                            if (onPlayInNativeEngine != null) {
                                OutlinedButton(
                                    onClick = {
                                        if (roomInput.isNotBlank()) {
                                            val input = roomInput.trim()
                                            coroutineScope.launch {
                                                recordRepo.recordAccess(roomId = input)
                                            }
                                            onPlayInNativeEngine(input)
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = DouyinCyan
                                    ),
                                    border = ButtonDefaults.outlinedButtonBorder.copy(
                                        brush = androidx.compose.ui.graphics.SolidColor(DouyinCyan)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = DouyinCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("直接以原生 ExoPlayer 播放", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    1 -> {
                        // 收藏列表（Room 本地持久化）
                        if (favorites.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB800).copy(alpha = 0.4f),
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("暂无收藏的直播间", color = TextSecondary, fontSize = 13.sp)
                                Text("在播放时点击顶部 ⭐ 即可一键收藏", color = TextSecondary.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(favorites, key = { it.id }) { record ->
                                    StreamRecordItemRow(
                                        record = record,
                                        onPlayNative = {
                                            coroutineScope.launch { recordRepo.recordAccess(record.roomId, record.streamUrl, record.title, record.anchorName) }
                                            if (onPlayInNativeEngine != null) {
                                                onPlayInNativeEngine(record.roomId.ifBlank { record.streamUrl })
                                            } else {
                                                onInjectRoom(record.roomId.ifBlank { record.streamUrl })
                                            }
                                        },
                                        onInjectWeb = {
                                            coroutineScope.launch { recordRepo.recordAccess(record.roomId, record.streamUrl, record.title, record.anchorName) }
                                            onInjectRoom(record.roomId.ifBlank { record.streamUrl })
                                        },
                                        onToggleFavorite = {
                                            coroutineScope.launch { recordRepo.toggleFavorite(record.id) }
                                        },
                                        onDelete = {
                                            coroutineScope.launch { recordRepo.delete(record.id) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    2 -> {
                        // 最近访问列表（Room 本地持久化）
                        if (recents.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = TextSecondary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("暂无最近访问记录", color = TextSecondary, fontSize = 13.sp)
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("最近播放记录 (${recents.size})", color = TextSecondary, fontSize = 11.sp)
                                    TextButton(
                                        onClick = {
                                            coroutineScope.launch { recordRepo.clearHistoryOnly() }
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text("清理历史(保留收藏)", color = DouyinRed, fontSize = 11.sp)
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(recents, key = { it.id }) { record ->
                                        StreamRecordItemRow(
                                            record = record,
                                            onPlayNative = {
                                                coroutineScope.launch { recordRepo.recordAccess(record.roomId, record.streamUrl, record.title, record.anchorName) }
                                                if (onPlayInNativeEngine != null) {
                                                    onPlayInNativeEngine(record.roomId.ifBlank { record.streamUrl })
                                                } else {
                                                    onInjectRoom(record.roomId.ifBlank { record.streamUrl })
                                                }
                                            },
                                            onInjectWeb = {
                                                coroutineScope.launch { recordRepo.recordAccess(record.roomId, record.streamUrl, record.title, record.anchorName) }
                                                onInjectRoom(record.roomId.ifBlank { record.streamUrl })
                                            },
                                            onToggleFavorite = {
                                                coroutineScope.launch { recordRepo.toggleFavorite(record.id) }
                                            },
                                            onDelete = {
                                                coroutineScope.launch { recordRepo.delete(record.id) }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                Button(
                    onClick = {
                        if (roomInput.isNotBlank()) {
                            val input = roomInput.trim()
                            coroutineScope.launch {
                                recordRepo.recordAccess(roomId = input)
                            }
                            onInjectRoom(input)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("中控台载入")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TextSecondary)
            }
        }
    )
}

@Composable
fun StreamRecordItemRow(
    record: StreamRecord,
    onPlayNative: () -> Unit,
    onInjectWeb: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceElevated),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.anchorName.ifBlank { "主播" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DouyinCyan.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (record.roomId.isNotBlank()) "房号 ${record.roomId}" else "直链",
                            color = DouyinCyan,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = record.title.ifBlank { record.streamUrl.ifBlank { "暂无标题" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 收藏星标
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (record.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (record.isFavorite) "取消收藏" else "加入收藏",
                        tint = if (record.isFavorite) Color(0xFFFFB800) else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 原生极速播放
                IconButton(
                    onClick = onPlayNative,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DouyinRed.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "原生播放",
                        tint = DouyinRed,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 删除
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
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
