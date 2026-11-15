package com.example.tv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.tv.channel.Channel
import kotlin.math.abs

/**
 * 电视端透明悬浮选台菜单（低遮挡磨砂玻璃风 + 翡翠绿焦点高亮 + 防漂移 rid 索引）
 *
 * 视觉与交互设计要点：
 * 1. 半透明暗调圆角浮层卡片（#AC141822 + 1.5dp 柔白描边），让底层直播画面与舞台氛围自然透出；
 * 2. 频道卡片带纯代码绘制圆形头像徽章 + 在线红点 + 「LIVE」角标 + 右侧「播放中 / #序号」状态胶囊；
 * 3. 焦点行高亮采用高辨识度翡翠绿描边（#25E69B）+ 半透明深绿底，弱视距十英尺遥控极易定位；
 * 4. 行视图以 channel.rid 为唯一标识，彻底杜绝异步测活重排期间的串台隐患。
 */
internal class TvChannelMenuOverlay(
    private val context: Context,
    private val onChannelSelected: (rid: String) -> Unit,
    private val onDismissRequested: () -> Unit
) {
    val rootScrim: FrameLayout
    val drawerCard: LinearLayout
    private val titleView: TextView
    private val subtitleView: TextView
    private val listScrollView: ScrollView
    private val channelListContainer: LinearLayout
    private val footerHintView: TextView

    private val density: Float = context.resources.displayMetrics.density
    private fun Int.dp(): Int = (this * density).toInt()
    private fun Float.dpF(): Float = this * density

    var isShowing: Boolean = false
        private set

    private var lastSignature: String = ""
    private var lastFocusedRid: String? = null

    init {
        val screenW = context.resources.displayMetrics.widthPixels
        val safeMaxDrawerWidth = (screenW - 96.dp()).coerceAtLeast(280.dp())
        val drawerWidth = 396.dp().coerceAtMost(safeMaxDrawerWidth)

        rootScrim = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#33000000"))
            visibility = View.GONE
            elevation = 32f
            isClickable = true
            isFocusable = false
            setOnClickListener { onDismissRequested() }
        }

        drawerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                drawerWidth,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.START
                leftMargin = 36.dp()
                topMargin = 22.dp()
                bottomMargin = 22.dp()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f.dpF()
                setColor(Color.parseColor("#B0131722"))
                setStroke(1.5f.dpF().toInt().coerceAtLeast(1), Color.parseColor("#44FFFFFF"))
            }
            setPadding(16.dp(), 16.dp(), 16.dp(), 14.dp())
            isClickable = true
            isFocusable = false
        }

        // 顶部标题栏（TV 图标 + 在线直播换台 + 在线主播数 + 关闭按钮）
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dp()
            }
            setPadding(4.dp(), 0, 0, 0)
        }

        val tvBadgeIcon = TvHeaderBadgeView(context).apply {
            layoutParams = LinearLayout.LayoutParams(34.dp(), 34.dp()).apply {
                marginEnd = 10.dp()
            }
        }

        val headerTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        titleView = TextView(context).apply {
            text = "在线直播换台"
            setTextColor(Color.parseColor("#F5F7FA"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        subtitleView = TextView(context).apply {
            text = "0 个主播正在直播"
            setTextColor(Color.parseColor("#25E69B"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        headerTextCol.addView(titleView)
        headerTextCol.addView(subtitleView)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#E6FFFFFF"))
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = 48.dp()
            minHeight = 48.dp()
            isClickable = true
            isFocusable = false
            contentDescription = "关闭选台菜单"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#22FFFFFF"))
            }
            setOnClickListener { onDismissRequested() }
        }

        headerRow.addView(tvBadgeIcon)
        headerRow.addView(headerTextCol)
        headerRow.addView(closeBtn)

        // 中部频道卡片滚动列表
        channelListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 2.dp(), 0, 4.dp())
        }

        listScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
            isFocusable = false
            addView(channelListContainer)
        }

        // 底部遥控操作提示条
        footerHintView = TextView(context).apply {
            text = "上下键选台 · OK 键播放 · 右键/菜单/返回关闭"
            setTextColor(Color.parseColor("#CFD7E6"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dp()
            }
            setPadding(12.dp(), 9.dp(), 12.dp(), 9.dp())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f.dpF()
                setColor(Color.parseColor("#55121620"))
                setStroke(1.dp().coerceAtLeast(1), Color.parseColor("#24FFFFFF"))
            }
        }

        drawerCard.addView(headerRow)
        drawerCard.addView(listScrollView)
        drawerCard.addView(footerHintView)
        rootScrim.addView(drawerCard)
    }

    fun show(onlineChannels: List<Channel>, currentRid: String?) {
        isShowing = true
        populateList(onlineChannels, currentRid, focusTargetRid = currentRid)
        rootScrim.alpha = 0f
        rootScrim.visibility = View.VISIBLE
        rootScrim.bringToFront()
        rootScrim.animate().cancel()
        rootScrim.animate().alpha(1f).setDuration(150L).start()
    }

    fun refreshIfShowing(onlineChannels: List<Channel>, currentRid: String?) {
        if (!isShowing) return
        if (onlineChannels.isEmpty()) {
            hide()
            return
        }
        val focusedRid = (rootScrim.findFocus()?.tag as? String) ?: currentRid
        populateList(onlineChannels, currentRid, focusTargetRid = focusedRid)
    }

    fun hide() {
        isShowing = false
        rootScrim.animate().cancel()
        channelListContainer.clearFocus()
        rootScrim.visibility = View.GONE
    }

    fun currentFocusedRid(): String? {
        return rootScrim.findFocus()?.tag as? String
    }

    private fun populateList(
        onlineChannels: List<Channel>,
        currentRid: String?,
        focusTargetRid: String?
    ) {
        val sig = buildString(onlineChannels.size * 24) {
            append(currentRid.orEmpty())
            append('|')
            onlineChannels.forEach {
                append(it.rid).append(':').append(it.name).append(':').append(it.title).append(';')
            }
        }

        if (sig == lastSignature && channelListContainer.childCount == onlineChannels.size) {
            val targetView = focusTargetRid?.let { rid ->
                channelListContainer.findViewWithTag<View>(rid)
            } ?: channelListContainer.getChildAt(0)
            targetView?.post {
                targetView.requestFocus()
                val scrollY = (targetView.top - listScrollView.height / 3).coerceAtLeast(0)
                listScrollView.smoothScrollTo(0, scrollY)
            }
            return
        }
        lastSignature = sig

        subtitleView.text = "${onlineChannels.size} 个主播正在直播"
        channelListContainer.removeAllViews()

        var focusTargetView: View? = null

        onlineChannels.forEachIndexed { displayIndex, ch ->
            val isCurrent = ch.rid == currentRid
            val rowView = buildChannelRowCard(ch, displayIndex + 1, isCurrent)
            if (focusTargetView == null && ch.rid == focusTargetRid) {
                focusTargetView = rowView
            }
            channelListContainer.addView(rowView)
        }

        val target = focusTargetView ?: channelListContainer.getChildAt(0)
        target?.post {
            target.requestFocus()
            val scrollY = (target.top - listScrollView.height / 3).coerceAtLeast(0)
            listScrollView.smoothScrollTo(0, scrollY)
        }
    }

    private fun buildChannelRowCard(
        channel: Channel,
        displayOrder: Int,
        isCurrent: Boolean
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dp()
            }
            minimumHeight = 56.dp()
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            tag = channel.rid
            background = createCardDrawable(focused = false, isCurrent = isCurrent)
        }

        val avatarBadge = ChannelAvatarBadgeView(context, channel.rid, channel.name).apply {
            layoutParams = LinearLayout.LayoutParams(38.dp(), 38.dp()).apply {
                marginEnd = 11.dp()
            }
        }

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 8.dp()
            }
        }

        val topLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val nameView = TextView(context).apply {
            text = channel.name.ifBlank { "房间 ${channel.rid}" }
            setTextColor(Color.WHITE)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 6.dp()
            }
        }

        val liveTag = TextView(context).apply {
            text = "LIVE"
            setTextColor(Color.WHITE)
            textSize = 9.5f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f.dpF()
                setColor(Color.parseColor("#E6FF3B5C"))
            }
        }

        topLine.addView(nameView)
        topLine.addView(liveTag)

        val subtitleText = TextView(context).apply {
            text = channel.title.ifBlank { "房间号 ${channel.rid}" }
            setTextColor(Color.parseColor("#C2CADB"))
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dp()
            }
        }

        infoCol.addView(topLine)
        infoCol.addView(subtitleText)

        val statusPill = TextView(context).apply {
            text = if (isCurrent) "播放中" else "#$displayOrder"
            setTextColor(
                if (isCurrent) Color.WHITE else Color.parseColor("#D8E0EE")
            )
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = 34.dp()
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 7f.dpF()
                setColor(
                    if (isCurrent) Color.parseColor("#C8CC5868")
                    else Color.parseColor("#66313849")
                )
            }
        }

        card.addView(avatarBadge)
        card.addView(infoCol)
        card.addView(statusPill)

        card.setOnFocusChangeListener { v, hasFocus ->
            v.background = createCardDrawable(focused = hasFocus, isCurrent = isCurrent)
            nameView.setTextColor(
                when {
                    hasFocus -> Color.parseColor("#FFFFFF")
                    isCurrent -> Color.parseColor("#25F4EE")
                    else -> Color.parseColor("#F1F4FA")
                }
            )
        }

        card.setOnClickListener {
            onChannelSelected(channel.rid)
        }

        return card
    }

    private fun createCardDrawable(focused: Boolean, isCurrent: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f.dpF()
            when {
                focused -> {
                    setColor(Color.parseColor("#6E104432"))
                    setStroke(2.dp().coerceAtLeast(2), Color.parseColor("#25E69B"))
                }
                isCurrent -> {
                    setColor(Color.parseColor("#5C1C2736"))
                    setStroke(1.2f.dpF().toInt().coerceAtLeast(1), Color.parseColor("#6625E69B"))
                }
                else -> {
                    setColor(Color.parseColor("#4D1E2330"))
                    setStroke(1.dp().coerceAtLeast(1), Color.parseColor("#28FFFFFF"))
                }
            }
        }
    }

    /** 左侧标题小电视图标徽章（纯 Canvas 矢量绘制，零资源依赖） */
    private class TvHeaderBadgeView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2EFE2C55")
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            color = Color.parseColor("#99FF4669")
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#FF4A6B")
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val w = width.toFloat()
            val h = height.toFloat()
            val r = 9f * d
            canvas.drawRoundRect(1f * d, 1f * d, w - 1f * d, h - 1f * d, r, r, bgPaint)
            canvas.drawRoundRect(1f * d, 1f * d, w - 1f * d, h - 1f * d, r, r, strokePaint)

            // 迷你电视框 + 播放三角
            val padX = w * 0.24f
            val topY = h * 0.31f
            val botY = h * 0.74f
            canvas.drawRoundRect(padX, topY, w - padX, botY, 2.5f * d, 2.5f * d, iconPaint)
            canvas.drawLine(w * 0.38f, h * 0.21f, w * 0.50f, topY, iconPaint)
            canvas.drawLine(w * 0.62f, h * 0.21f, w * 0.50f, topY, iconPaint)
        }
    }

    /** 频道圆形头像微标（按 rid 生成稳定配色 + 首字缩写 + 共享 Paint 减少内存分配） */
    private class ChannelAvatarBadgeView(
        context: Context,
        rid: String,
        name: String
    ) : View(context) {
        companion object {
            private val AVATAR_COLORS = intArrayOf(
                Color.parseColor("#FF2E6373"),
                Color.parseColor("#FF63427A"),
                Color.parseColor("#FF246A56"),
                Color.parseColor("#FF7A4834"),
                Color.parseColor("#FF335482"),
                Color.parseColor("#FF6E3554")
            )
            private val SHARED_TEXT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            private val SHARED_DOT_BORDER_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF141822")
            }
            private val SHARED_DOT_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF25E69B")
            }
            private val SHARED_RING_PAINT = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.parseColor("#66FFFFFF")
            }
        }

        private val bgColor = AVATAR_COLORS[abs(rid.hashCode()) % AVATAR_COLORS.size]
        private val initialChar = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "直"
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val radius = (minOf(width, height) / 2f) - 2f * d
            canvas.drawCircle(cx, cy, radius, circlePaint)
            SHARED_RING_PAINT.strokeWidth = 1.4f * d
            canvas.drawCircle(cx, cy, radius, SHARED_RING_PAINT)

            SHARED_TEXT_PAINT.textSize = radius * 0.92f
            val fm = SHARED_TEXT_PAINT.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(initialChar, cx, textY, SHARED_TEXT_PAINT)

            val dotR = 4.2f * d
            val dotX = width - dotR - 1.5f * d
            val dotY = height - dotR - 1.5f * d
            canvas.drawCircle(dotX, dotY, dotR + 1.5f * d, SHARED_DOT_BORDER_PAINT)
            canvas.drawCircle(dotX, dotY, dotR, SHARED_DOT_PAINT)
        }
    }
}
