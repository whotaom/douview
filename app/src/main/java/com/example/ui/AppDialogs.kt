package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.data.db.StreamRecord
import com.example.data.db.StreamRecordRepository
import com.example.ui.theme.*
import com.example.util.AppReleaseInfo
import com.example.util.UpdateManager
import kotlinx.coroutines.launch

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
                        label = { Text("官方默认", fontSize = 11.sp) },
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
                        onClick = { inputUrl = "https://live.douyin.com" },
                        label = { Text("抖音网页", fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = DarkSurfaceElevated,
                            labelColor = TextPrimary
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

/**
 * 军工级快速加房与本地持久化中枢
 * 原生 ExoPlayer 极速模式：
 * 1. 严格去除口令与短链接添加，去除剪贴板直接读取；
 * 2. 修正为仅可添加 <= 15 位的纯数字 RID；
 * 3. 添加时自动加入本地 Room 直播数据库列表（最近与收藏中枢实时同步）。
 */
@Composable
fun QuickRoomDialog(
    onDismiss: () -> Unit,
    repository: StreamRecordRepository? = null,
    onInjectRoom: (String) -> Unit,
    onPlayInNativeEngine: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val recordRepo = remember(repository) {
        repository ?: StreamRecordRepository.getInstance(context)
    }

    val favorites by recordRepo.favoriteRecords.collectAsState(initial = emptyList())
    val recents by recordRepo.recentRecords.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    var roomInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 原生极速加房, 1: 收藏列表, 2: 最近历史

    val isInputValidRid = remember(roomInput) {
        roomInput.isNotBlank() && roomInput.length <= 15 && roomInput.all { it.isDigit() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        modifier = Modifier.widthIn(max = 640.dp),
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
                        text = { Text("快速加房 (RID)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
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
                    .heightIn(min = 210.dp, max = 380.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        // 快速加房（纯数字 RID <= 15 位，且自动加列表）
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "输入抖音直播间号（仅支持 ≤15 位纯数字 RID，自动加进直播列表）：",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            OutlinedTextField(
                                value = roomInput,
                                onValueChange = { newVal ->
                                    // 军工级严格过滤：只允许 0-9 纯数字，最大 15 位，杜绝多余字符与口令
                                    val digitsOnly = newVal.filter { it.isDigit() }.take(15)
                                    roomInput = digitsOnly
                                },
                                placeholder = { Text("输入纯数字房间号 (例如: 7421839218)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                supportingText = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (roomInput.isEmpty()) {
                                                "请输入 1~15 位纯数字 RID"
                                             } else if (isInputValidRid) {
                                                "格式正确 (自动加进直播列表)"
                                             } else {
                                                "必须为纯数字且不超过15位"
                                             },
                                            color = if (isInputValidRid) DouyinGreen else TextSecondary,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = "${roomInput.length}/15",
                                            color = if (roomInput.length == 15) DouyinRed else TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusable(),
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
                                    if (isInputValidRid) {
                                        val input = roomInput.trim()
                                        coroutineScope.launch {
                                            recordRepo.recordAccess(roomId = input)
                                        }
                                        onInjectRoom(input)
                                    }
                                },
                                enabled = isInputValidRid,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DouyinRed,
                                    disabledContainerColor = DarkSurfaceElevated
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusable()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isInputValidRid) Color.White else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("立即接入直播流并加入列表", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                OutlinedButton(
                    onClick = {
                        if (isInputValidRid) {
                            val input = roomInput.trim()
                            coroutineScope.launch {
                                recordRepo.recordAccess(roomId = input)
                            }
                            onInjectRoom(input)
                        }
                    },
                    enabled = isInputValidRid,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DouyinCyan),
                    modifier = Modifier.focusable()
                ) {
                    Text("中控台解析")
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
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
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
                    modifier = Modifier.size(32.dp).focusable()
                ) {
                    Icon(
                        imageVector = if (record.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (record.isFavorite) "取消收藏" else "加入收藏",
                        tint = if (record.isFavorite) Color(0xFFFFB800) else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 播放直播
                IconButton(
                    onClick = onInjectWeb,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(DouyinRed.copy(alpha = 0.15f))
                        .focusable()
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "播放直播",
                        tint = DouyinRed,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 删除
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp).focusable()
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
                        containerColor = if (info.hasUpdate) DouyinRed else DouyinCyan
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.focusable()
                ) {
                    Icon(
                        imageVector = if (info.hasUpdate) Icons.Default.CloudDownload else Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (info.hasUpdate) "立即下载更新 (APK)" else "访问发布页面")
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
