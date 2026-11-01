package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LivePreferences
import com.example.ui.theme.*

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

                // Preset 1: Full Console
                PresetUrlCard(
                    title = "默认中控台 (包含UI及实时播放器)",
                    url = LivePreferences.DEFAULT_URL,
                    isSelected = customUrl == LivePreferences.DEFAULT_URL,
                    onClick = { customUrl = LivePreferences.DEFAULT_URL }
                )

                // Preset 2: Direct API
                PresetUrlCard(
                    title = "live.php 直连接口 (JSON流解析响应)",
                    url = LivePreferences.API_ENDPOINT_URL,
                    isSelected = customUrl == LivePreferences.API_ENDPOINT_URL,
                    onClick = { customUrl = LivePreferences.API_ENDPOINT_URL }
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
    onInjectRoom: (String) -> Unit
) {
    var roomInput by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text("快速加房 / 粘贴口令", color = TextPrimary)
        },
        text = {
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (roomInput.isNotBlank()) {
                        onInjectRoom(roomInput.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = DouyinRed),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("注入并播放")
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
