package com.example.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * 电视端专用遥控器高亮卡片组件
 * 支持 D-pad 获得焦点时的平滑缩放、霓虹描边与光晕阴影
 */
@Composable
fun TvFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedBorderColor: Color = DouyinCyan,
    isHighlighted: Boolean = false,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(180),
        label = "tvCardScale"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> focusedBorderColor
            isHighlighted -> DouyinRed.copy(alpha = 0.6f)
            else -> DarkSurfaceBorder
        },
        animationSpec = tween(180),
        label = "tvCardBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused -> DarkSurfaceElevated.copy(alpha = 0.95f)
            isHighlighted -> DarkSurfaceElevated
            else -> DarkSurface
        },
        animationSpec = tween(180),
        label = "tvCardBg"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (isFocused) 3.dp else 1.dp, borderColor),
        modifier = modifier
            .scale(scale)
            .shadow(if (isFocused) 16.dp else 2.dp, RoundedCornerShape(14.dp), spotColor = if (isFocused) focusedBorderColor else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content(isFocused)
        }
    }
}

/**
 * 电视端顶部状态栏与标题组件
 */
@Composable
fun TvTopBar(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DouyinRed,
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text(
                        text = "TV 大屏版",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            actions()
        }
    }
}

/**
 * 电视端按钮组件（遥控器可聚焦）
 */
@Composable
fun TvActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1.0f, label = "btnScale")
    val bgColor = if (isFocused) {
        if (isPrimary) DouyinRed else DouyinCyan
    } else {
        if (isPrimary) DouyinRed.copy(alpha = 0.8f) else DarkSurfaceElevated
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(if (isFocused) 2.dp else 1.dp, if (isFocused) Color.White else DarkSurfaceBorder),
        modifier = modifier
            .scale(scale)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused && !isPrimary) Color.Black else Color.White,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                color = if (isFocused && !isPrimary) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
