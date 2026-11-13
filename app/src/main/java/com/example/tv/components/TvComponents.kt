package com.example.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Android TV 10-foot 过扫描 (Overscan) 安全区常量（约 5% 安全留白）
 */
object TvSafeAreaDefaults {
    val HorizontalPadding = 48.dp
    val VerticalPadding = 27.dp
}

/**
 * 安全请求焦点：避免未附着节点时抛出 "FocusRequester is not initialized" 运行时崩溃
 */
fun FocusRequester.safeRequestFocus(): Boolean {
    return try {
        requestFocus()
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: Exception) {
        false
    }
}

/**
 * 统一的 TV 单焦点目标修饰器：
 * 兼顾触摸模式 TV 盒子（RK3528 等）、遥控器 D-pad、鼠标点击、无障碍与 Compose UI 测试，
 * 且仅挂载单一 FocusTarget，避免 `.focusable()` + `.clickable()` 双重聚焦陷阱。
 */
@Composable
fun Modifier.tvInteractive(
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    testTagValue: String? = null,
    onFocusedChange: (Boolean) -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    return this
        .then(if (testTagValue != null) Modifier.testTag(testTagValue) else Modifier)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { onFocusedChange(it.isFocused) }
        .focusable(interactionSource = interactionSource)
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp &&
                (event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter)
            ) {
                currentOnClick()
                true
            } else {
                false
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { currentOnClick() })
        }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick(action = {
                currentOnClick()
                true
            })
        }
}

/**
 * 电视端专用遥控器高亮卡片组件
 * 支持 D-pad 获得焦点时的平滑缩放、霓虹描边与光晕阴影
 */
@Composable
fun TvFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    testTagValue: String? = null,
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
            isHighlighted -> DouyinRed.copy(alpha = 0.65f)
            else -> DarkSurfaceBorder
        },
        animationSpec = tween(180),
        label = "tvCardBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isFocused -> DarkSurfaceElevated.copy(alpha = 0.96f)
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
            .minimumInteractiveComponentSize()
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 16.dp else 2.dp,
                shape = RoundedCornerShape(14.dp),
                spotColor = if (isFocused) focusedBorderColor else Color.Transparent
            )
            .tvInteractive(
                onClick = onClick,
                focusRequester = focusRequester,
                testTagValue = testTagValue,
                onFocusedChange = { isFocused = it }
            )
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content(isFocused)
        }
    }
}

/**
 * 电视端顶部状态栏与标题组件（遵循 48.dp 水平 / 27.dp 顶部过扫描安全区）
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
            .padding(
                start = TvSafeAreaDefaults.HorizontalPadding,
                end = TvSafeAreaDefaults.HorizontalPadding,
                top = TvSafeAreaDefaults.VerticalPadding,
                bottom = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            actions()
        }
    }
}

/**
 * 电视端按钮组件（遥控器可聚焦，满足 >=48dp 最小交互热区）
 */
@Composable
fun TvActionButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    testTagValue: String? = null,
    isPrimary: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.07f else 1.0f,
        animationSpec = tween(180),
        label = "btnScale"
    )
    val bgColor = if (isFocused) {
        if (isPrimary) Color(0xFF1E88E5) else Color(0xFF3A3A3A)
    } else {
        if (isPrimary) Color(0xFF1565C0) else Color(0xFF2A2A2A)
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(
            if (isFocused) 2.5.dp else 1.dp,
            if (isFocused) DouyinCyan else DarkSurfaceBorder
        ),
        shadowElevation = if (isFocused) 12.dp else 0.dp,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 14.dp else 0.dp,
                shape = RoundedCornerShape(10.dp),
                spotColor = if (isFocused) DouyinCyan else Color.Transparent
            )
            .tvInteractive(
                onClick = onClick,
                focusRequester = focusRequester,
                testTagValue = testTagValue,
                onFocusedChange = { isFocused = it }
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isFocused) DouyinCyan else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
