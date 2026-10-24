package com.eloykim.lyra.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.eloykim.lyra.R
import com.eloykim.lyra.markdown.Markdown
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.WeakHashMap
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 从 Compose 主题传进 RecyclerView 的颜色与尺寸。 */
internal data class ChatStyle(
    val userBubble: Int,
    val assistantBubble: Int,
    val errorBubble: Int,
    val userText: Int,
    val assistantText: Int,
    val errorText: Int,
    val mutedText: Int,
    val primary: Int,
    val outline: Int,
    val codeBackground: Int,
    val tableHeader: Int,
    val avatarBackground: Int,
    val avatarText: Int,
    val thinkingHeaderBackground: Int,
    val thinkingBackground: Int,
    val reactionSelectedBackground: Int,
    val reactionSelectedText: Int,
    val actionBackground: Int,
    val actionIcon: Int,
    val actionRipple: Int,
    val density: Float,
    val maxBubbleWidthPx: Int,
)

@Composable
internal fun rememberChatStyle(): ChatStyle {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val maxBubbleDp = (screenWidthDp - 80).coerceIn(220, 340)

    return remember(
        scheme.primaryContainer,
        scheme.surfaceContainerLow,
        scheme.errorContainer,
        scheme.onPrimaryContainer,
        scheme.onSurface,
        scheme.onErrorContainer,
        scheme.onSurfaceVariant,
        scheme.primary,
        scheme.outlineVariant,
        scheme.surfaceContainerHighest,
        scheme.surfaceContainerHigh,
        scheme.secondaryContainer,
        scheme.onSecondaryContainer,
        scheme.surfaceContainerHigh,
        scheme.onSurfaceVariant,
        scheme.primary,
        scheme.onPrimary,
        density,
        maxBubbleDp,
    ) {
        ChatStyle(
            userBubble = scheme.primaryContainer.toArgb(),
            assistantBubble = scheme.surfaceContainerLow.toArgb(),
            errorBubble = scheme.errorContainer.toArgb(),
            userText = scheme.onPrimaryContainer.toArgb(),
            assistantText = scheme.onSurface.toArgb(),
            errorText = scheme.onErrorContainer.toArgb(),
            mutedText = scheme.onSurfaceVariant.toArgb(),
            primary = scheme.primary.toArgb(),
            outline = scheme.outlineVariant.toArgb(),
            codeBackground = scheme.surfaceContainerHighest.toArgb(),
            tableHeader = scheme.surfaceContainerHigh.toArgb(),
            avatarBackground = scheme.secondaryContainer.toArgb(),
            avatarText = scheme.onSecondaryContainer.toArgb(),
            thinkingHeaderBackground = scheme.surfaceContainerHighest.copy(alpha = 0.6f).toArgb(),
            thinkingBackground = scheme.surfaceContainerHighest.copy(alpha = 0.35f).toArgb(),
            reactionSelectedBackground = scheme.primary.toArgb(),
            reactionSelectedText = scheme.onPrimary.toArgb(),
            actionBackground = scheme.surfaceContainerHigh.toArgb(),
            actionIcon = scheme.onSurfaceVariant.toArgb(),
            actionRipple = scheme.primary.copy(alpha = 0.18f).toArgb(),
            density = density.density,
            maxBubbleWidthPx = with(density) { maxBubbleDp.dp.roundToPx() },
        )
    }
}

/**
 * 消息列表：RecyclerView。
 *
 * 助手消息用 WebView 渲染 HTML（支持表格、代码块），用户/错误消息用 TextView。
 * 每个 item 自己上报 WebView 内容高度，RecyclerView 再按真实高度布局。
 */
@Composable
internal fun ChatMessageList(
    messages: List<ChatMessage>,
    style: ChatStyle,
    onReaction: (Long, String?) -> Unit,
    onQuote: (Long) -> Unit,
    onRegenerate: (Long) -> Unit,
    scrollToLatestHeadKey: Int = 0,
    onMentionClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listAdapter = remember {
        ChatMessageAdapter(style, onReaction, onQuote, onRegenerate, onMentionClick)
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val recycler = ScrollLockRecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                adapter = listAdapter
                itemAnimator = null
                clipChildren = true
                clipToPadding = false
                val padding = (12 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                overScrollMode = View.OVER_SCROLL_ALWAYS
                setHasFixedSize(false)
            }
            // FrameLayout 明确 clipChildren，防止 WebView 在 RecyclerView 视口外绘制。
            FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(
                    recycler,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                tag = recycler
            }.also {
                listAdapter.attach(recycler)
                listAdapter.submit(messages)
            }
        },
        update = { host ->
            val recycler = host.tag as? RecyclerView
            if (recycler != null) {
                listAdapter.onReaction = onReaction
                listAdapter.onQuote = onQuote
                listAdapter.onRegenerate = onRegenerate
                listAdapter.onMentionClick = onMentionClick
                listAdapter.updateStyle(style)
                listAdapter.submit(messages)
                listAdapter.scrollToLatestHeadIfNeeded(scrollToLatestHeadKey)
                recycler.requestLayout()
            }
        },
        onRelease = {
            listAdapter.release()
        },
    )
}

internal class ChatMessageAdapter(
    initialStyle: ChatStyle,
    initialOnReaction: (Long, String?) -> Unit,
    initialOnQuote: (Long) -> Unit,
    initialOnRegenerate: (Long) -> Unit,
    initialOnMentionClick: ((String) -> Unit)? = null,
) : ListAdapter<ChatMessage, ChatMessageAdapter.Holder>(Diff) {

    private var style: ChatStyle = initialStyle
    var onReaction: (Long, String?) -> Unit = initialOnReaction
    var onQuote: (Long) -> Unit = initialOnQuote
    var onRegenerate: (Long) -> Unit = initialOnRegenerate
    var onMentionClick: ((String) -> Unit)? = initialOnMentionClick
    private var recyclerView: RecyclerView? = null
    private val expandedThinking = HashSet<Long>()
    private val pendingMessageIds = HashSet<Long>()
    private val views = ArrayList<ChatMessageItemView>()
    private var lastScrollToHeadKey = 0
    private var atBottom = true
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) {
                atBottom = !recyclerView.canScrollVertically(1)
            }
        }
    }

    fun attach(recycler: RecyclerView) {
        recyclerView = recycler
        recycler.addOnScrollListener(scrollListener)
    }

    fun submit(messages: List<ChatMessage>) {
        if (currentList == messages) return
        val old = currentList
        // 只改表情时不滚到底，避免点个赞把正在看的位置顶走。
        val onlyReactions = old.size == messages.size && old.indices.all { index ->
            old[index].id == messages[index].id &&
                old[index].copy(feedback = messages[index].feedback) == messages[index]
        }
        submitList(messages) {
            if (!onlyReactions) scrollToBottom()
        }
    }

    fun updateStyle(next: ChatStyle) {
        if (style == next) return
        style = next
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    fun release() {
        recyclerView?.removeOnScrollListener(scrollListener)
        views.forEach { it.releaseWebViews() }
        views.clear()
        recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = ChatMessageItemView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        views += view
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = getItem(position)
        val animateTypewriter = if (message.pending) {
            pendingMessageIds += message.id
            false
        } else {
            pendingMessageIds.remove(message.id) &&
                message.role == ChatRole.Assistant &&
                !message.error &&
                message.content.isNotBlank()
        }
        holder.view.bind(
            message = message,
            style = style,
            expanded = expandedThinking.contains(message.id),
            onToggleThinking = { id -> toggleThinking(id) },
            onMeasured = { onItemMeasured() },
            onReaction = { id, reaction -> onReaction(id, reaction) },
            onQuote = { text -> onQuote(text) },
            onRegenerate = { id -> onRegenerate(id) },
            onMentionClick = onMentionClick,
            showRegenerate = message.role == ChatRole.Assistant &&
                !message.pending &&
                position == itemCount - 1,
            animateTypewriter = animateTypewriter,
        )
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.view.onRecycled()
    }

    private fun toggleThinking(id: Long) {
        val wasExpanded = expandedThinking.contains(id)
        if (wasExpanded) expandedThinking.remove(id) else expandedThinking.add(id)
        val index = currentList.indexOfFirst { it.id == id }
        if (index < 0) return
        notifyItemChanged(index)
        // 关闭思考区后，把当前这条消息的头部对齐到视口，而不是滚到列表底部。
        if (wasExpanded) {
            val recycler = recyclerView ?: return
            recycler.post { scrollToItemHead(index) }
            recycler.postDelayed({ scrollToItemHead(index) }, 220)
        }
    }

    private fun onItemMeasured() {
        val recycler = recyclerView ?: return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        if (itemCount == 0) return
        // 用户已经向上滚了就不要把他拽回底部；只有原本在底部才跟随增长。
        if (!atBottom) return
        recycler.post {
            if (atBottom && manager.findLastVisibleItemPosition() >= itemCount - 1) {
                recycler.scrollToPosition(itemCount - 1)
            }
        }
    }

    fun scrollToLatestHeadIfNeeded(key: Int) {
        if (key == lastScrollToHeadKey) return
        lastScrollToHeadKey = key
        scrollToLatestHead()
    }

    /** 关闭引用/思考后，把指定消息的顶部对齐到视口。 */
    private fun scrollToItemHead(index: Int) {
        val recycler = recyclerView ?: return
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        if (index !in 0 until itemCount) return
        atBottom = false
        recycler.post {
            if (index in 0 until itemCount) {
                manager.scrollToPositionWithOffset(index, 0)
            }
        }
    }

    private fun scrollToLatestHead() {
        if (itemCount > 0) scrollToItemHead(itemCount - 1)
    }

    private fun scrollToBottom() {
        val recycler = recyclerView ?: return
        atBottom = true
        recycler.post {
            if (itemCount > 0) recycler.scrollToPosition(itemCount - 1)
        }
    }

    class Holder(val view: ChatMessageItemView) : RecyclerView.ViewHolder(view)

    private object Diff : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
            oldItem == newItem
    }
}

/**
 * 单条消息的 Android View。
 *
 * 结构：
 * - 头像（仅助手）
 * - 气泡
 *   - 思考过程（可折叠）
 *   - 正文（WebView / TextView）
 */
@SuppressLint("ViewConstructor")
internal class ChatMessageItemView(context: Context) : LinearLayout(context) {

    private val avatar = TextView(context)
    private val contentColumn = LinearLayout(context)
    private val bubble = LinearLayout(context)
    private val quotedBox = LinearLayout(context)
    private val quotedBar = View(context)
    private val quotedLabel = TextView(context)
    private val quotedText = TextView(context)
    private val footer = LinearLayout(context)
    private val timeText = TextView(context)
    private val copyButton = ImageButton(context)
    private val refreshButton = ImageButton(context)
    private val shareButton = ImageButton(context)
    private val quoteButton = ImageButton(context)
    private val thumbUp = TextView(context)
    private val thumbDown = TextView(context)
    private val customReaction = TextView(context)
    private val moreButton = ImageButton(context)
    private val thinkingHeader = LinearLayout(context)
    private val thinkingChevron = TextView(context)
    private val thinkingLabel = TextView(context)
    private val thinkingAction = TextView(context)
    private val thinkingBox = LinearLayout(context)
    private var thinkingWeb: WebView? = null
    private val pendingRow = LinearLayout(context)
    private val pendingProgress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
    private val pendingLabel = TextView(context)
    private val contentText = TextView(context)
    private var contentWeb: WebView? = null

    private var currentMessage: ChatMessage? = null
    private var currentStyle: ChatStyle? = null
    private var lastContent: String? = null
    private var lastReasoning: String? = null
    private var lastExpanded: Boolean = false
    private var currentReaction: String? = null
    private var onToggleThinking: ((Long) -> Unit)? = null
    private var onMeasured: (() -> Unit)? = null
    private var onReaction: ((Long, String?) -> Unit)? = null
    private var onQuote: ((Long) -> Unit)? = null
    private var onRegenerate: ((Long) -> Unit)? = null
    private var onMentionClick: ((String) -> Unit)? = null
    private var hasScrollableBlocks = false
    private var typewriterRunnable: Runnable? = null
    private var typewriterTicks = 0

    private val moreEmojis = listOf(
        "❤️",
        "😂",
        "😄",
        "😢",
        "😠",
        "😮",
        "🎉",
        "🔥",
        "👏",
        "🤔",
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        setPadding(0, 0, 0, dp(8))

        avatar.apply {
            text = "L"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) }
            background = oval(0)
            visibility = GONE
        }

        bubble.orientation = VERTICAL
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10))

        thinkingHeader.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = GONE
        }
        thinkingChevron.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        thinkingLabel.apply {
            text = "思考过程"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(6), 0, 0, 0)
        }
        thinkingAction.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        thinkingHeader.addView(thinkingChevron)
        thinkingHeader.addView(thinkingLabel)
        thinkingHeader.addView(thinkingAction)

        thinkingBox.orientation = VERTICAL
        thinkingBox.setPadding(dp(10), dp(8), dp(10), dp(8))
        thinkingBox.visibility = GONE
        thinkingBox.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }

        pendingRow.orientation = HORIZONTAL
        pendingRow.gravity = Gravity.CENTER_VERTICAL
        pendingRow.visibility = GONE
        pendingProgress.layoutParams = LayoutParams(dp(14), dp(14))
        pendingLabel.apply {
            text = "正在生成…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), 0, 0, 0)
        }
        pendingRow.addView(pendingProgress)
        pendingRow.addView(pendingLabel)

        contentText.apply {
            visibility = GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.2f)
            includeFontPadding = false
        }

        quotedBox.apply {
            orientation = HORIZONTAL
            visibility = GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        quotedBar.layoutParams = LayoutParams(dp(3), dp(32))
        quotedLabel.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            includeFontPadding = false
        }
        quotedText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val quotedColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(quotedLabel)
            addView(quotedText)
        }
        quotedBox.addView(quotedBar)
        quotedBox.addView(quotedColumn)

        bubble.addView(quotedBox)
        bubble.addView(thinkingHeader)
        bubble.addView(thinkingBox)
        bubble.addView(pendingRow)
        bubble.addView(contentText)

        timeText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(2), 0)
        }

        listOf(copyButton, refreshButton, shareButton, quoteButton, moreButton).forEach { button ->
            button.apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = LayoutParams(dp(30), dp(30)).apply { marginStart = dp(2) }
            }
        }
        copyButton.apply {
            setImageResource(R.drawable.ic_copy)
            contentDescription = "复制"
            setOnClickListener { showCopyMenu(copyButton) }
        }
        refreshButton.apply {
            setImageResource(R.drawable.ic_refresh)
            contentDescription = "重新生成"
            setOnClickListener {
                currentMessage?.let { message -> onRegenerate?.invoke(message.id) }
            }
        }
        shareButton.apply {
            setImageResource(R.drawable.ic_share)
            contentDescription = "分享"
            setOnClickListener { shareContent() }
        }
        quoteButton.apply {
            setImageResource(R.drawable.ic_format_quote)
            contentDescription = "引用"
            setOnClickListener { quoteContent() }
        }
        moreButton.apply {
            setImageResource(R.drawable.ic_more_horiz)
            contentDescription = "更多表情"
            setOnClickListener { showReactionPicker(moreButton) }
        }

        listOf(
            thumbUp to "👍",
            thumbDown to "👎",
        ).forEach { (view, emoji) ->
            view.apply {
                text = emoji
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                includeFontPadding = false
                layoutParams = LayoutParams(dp(30), dp(30)).apply { marginStart = dp(2) }
                isClickable = true
                isFocusable = true
            }
        }
        thumbUp.contentDescription = "点赞"
        thumbDown.contentDescription = "点踩"
        thumbUp.setOnClickListener { selectReaction("👍") }
        thumbDown.setOnClickListener { selectReaction("👎") }

        customReaction.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            layoutParams = LayoutParams(dp(30), dp(30)).apply { marginStart = dp(2) }
            contentDescription = "已选表情"
            visibility = GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { showReactionPicker(customReaction) }
        }

        footer.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), 0)
            addView(timeText)
            addView(copyButton)
            addView(refreshButton)
            addView(shareButton)
            addView(quoteButton)
            addView(thumbUp)
            addView(thumbDown)
            addView(customReaction)
            addView(moreButton)
        }

        contentColumn.apply {
            orientation = VERTICAL
            gravity = Gravity.START
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(bubble)
            addView(
                footer,
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        addView(avatar)
        addView(contentColumn)
    }

    fun bind(
        message: ChatMessage,
        style: ChatStyle,
        expanded: Boolean,
        onToggleThinking: (Long) -> Unit,
        onMeasured: () -> Unit,
        onReaction: (Long, String?) -> Unit,
        onQuote: (Long) -> Unit,
        onRegenerate: (Long) -> Unit,
        onMentionClick: ((String) -> Unit)?,
        showRegenerate: Boolean,
        animateTypewriter: Boolean,
    ) {
        cancelTypewriter()
        val styleChanged = currentStyle != style
        currentStyle = style
        currentMessage = message
        this.onToggleThinking = onToggleThinking
        this.onMeasured = onMeasured
        this.onReaction = onReaction
        this.onQuote = onQuote
        this.onRegenerate = onRegenerate
        this.onMentionClick = onMentionClick
        hasScrollableBlocks = message.content.contains("```")
        refreshButton.visibility = if (showRegenerate) VISIBLE else GONE

        val isUser = message.role == ChatRole.User
        // 头像与气泡顶部对齐（Telegram 式）。
        gravity = if (isUser) Gravity.END or Gravity.TOP else Gravity.START or Gravity.TOP
        contentColumn.gravity = if (isUser) Gravity.END else Gravity.START
        footer.gravity = if (isUser) Gravity.END else Gravity.START
        footer.visibility = if (message.pending) GONE else VISIBLE

        avatar.visibility = if (isUser) GONE else VISIBLE
        avatar.background = oval(style.avatarBackground)
        avatar.setTextColor(style.avatarText)
        (avatar.layoutParams as? LayoutParams)?.let { params ->
            params.topMargin = dp(2)
            avatar.layoutParams = params
        }

        timeText.text = formatTime(message.createdAt)
        timeText.setTextColor(style.mutedText)
        applyActionStyles(style)
        applyReaction(message.feedback?.emoji)

        val quote = message.quote
        if (quote != null && quote.content.isNotBlank()) {
            quotedBox.visibility = VISIBLE
            quotedBox.background = rounded(style.codeBackground, 8)
            quotedBar.setBackgroundColor(style.primary)
            quotedLabel.setTextColor(style.primary)
            quotedLabel.text = quote.authorName
                ?: if (quote.role == ChatRole.User) "你" else "Lyra"
            quotedText.setTextColor(if (isUser) style.userText else style.mutedText)
            quotedText.text = quote.content
        } else {
            quotedBox.visibility = GONE
        }

        val bubbleColor = when {
            message.error -> style.errorBubble
            isUser -> style.userBubble
            else -> style.assistantBubble
        }
        bubble.background = bubbleDrawable(bubbleColor, isUser)
        val fixedAssistantWidth = !isUser && !message.error && !message.pending
        bubble.layoutParams = LayoutParams(
            if (fixedAssistantWidth) style.maxBubbleWidthPx else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        thinkingHeader.visibility = GONE
        thinkingBox.visibility = GONE
        pendingRow.visibility = GONE
        contentText.visibility = GONE
        contentWeb?.visibility = GONE

        val reasoning = message.reasoning
        if (!isUser && !reasoning.isNullOrBlank()) {
            thinkingHeader.visibility = VISIBLE
            thinkingHeader.background = rounded(style.thinkingHeaderBackground, 10)
            thinkingChevron.setTextColor(style.primary)
            thinkingLabel.setTextColor(style.mutedText)
            thinkingAction.setTextColor(style.primary)
            thinkingChevron.text = if (expanded) "▾" else "▸"
            thinkingAction.text = if (expanded) "收起" else "展开"
            thinkingHeader.setOnClickListener { currentMessage?.let { onToggleThinking(it.id) } }

            thinkingBox.visibility = if (expanded) VISIBLE else GONE
            if (expanded) {
                thinkingBox.background = rounded(style.thinkingBackground, 10)
                loadThinkingWeb(
                    markdown = reasoning,
                    style = style,
                    reload = true,
                )
            }
        }

        when {
            message.pending -> {
                pendingRow.visibility = VISIBLE
                pendingLabel.setTextColor(style.mutedText)
                pendingProgress.indeterminateTintList = ColorStateList.valueOf(style.primary)
            }

            isUser -> showTextContent(message.content, style.userText, style)
            message.error -> showTextContent(message.content, style.errorText, style)
            animateTypewriter -> startTypewriter(message.content, style)
            else -> loadContentWeb(
                markdown = message.content,
                style = style,
                reload = styleChanged || lastContent != message.content,
            )
        }

        lastContent = message.content
        lastReasoning = reasoning
        lastExpanded = expanded
    }

    private fun applyActionStyles(style: ChatStyle) {
        val tint = ColorStateList.valueOf(style.actionIcon)
        listOf(copyButton, refreshButton, shareButton, quoteButton, moreButton).forEach { button ->
            button.background = actionDrawable(style.actionBackground, style.actionRipple)
            button.imageTintList = tint
        }
        listOf(thumbUp, thumbDown).forEach { view ->
            view.background = actionDrawable(style.actionBackground, style.actionRipple)
            view.setTextColor(style.actionIcon)
        }
    }

    private fun actionDrawable(background: Int, ripple: Int): Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(background)
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), content, null)
    }

    private fun quoteContent() {
        val message = currentMessage ?: return
        onQuote?.invoke(message.id)
    }

    private fun selectReaction(emoji: String) {
        val message = currentMessage ?: return
        val next = if (currentReaction == emoji) null else emoji
        applyReaction(next)
        onReaction?.invoke(message.id, next)
    }

    private fun applyReaction(reaction: String?) {
        currentReaction = reaction
        val style = currentStyle ?: return
        styleReaction(thumbUp, reaction == "👍", style)
        styleReaction(thumbDown, reaction == "👎", style)

        val custom = reaction?.takeIf { it != "👍" && it != "👎" }
        customReaction.visibility = if (custom != null) VISIBLE else GONE
        if (custom != null) {
            customReaction.text = custom
            styleReaction(customReaction, true, style)
        }
        moreButton.imageTintList = ColorStateList.valueOf(
            if (custom != null) style.primary else style.actionIcon,
        )
    }

    private fun styleReaction(view: TextView, selected: Boolean, style: ChatStyle) {
        val content = if (selected) {
            rounded(style.reactionSelectedBackground, 15)
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(style.actionBackground)
            }
        }
        view.background = RippleDrawable(
            ColorStateList.valueOf(style.actionRipple),
            content,
            null,
        )
        view.setTextColor(if (selected) style.reactionSelectedText else style.actionIcon)
        view.isSelected = selected
    }

    private fun showReactionPicker(anchor: View) {
        val popup = PopupMenu(context, anchor)
        moreEmojis.forEachIndexed { index, emoji ->
            // 纯下拉选择：不画 radio 圆圈，点一次即关闭。
            popup.menu.add(0, index, index, emoji)
        }
        popup.setOnMenuItemClickListener { item ->
            selectReaction(item.title.toString())
            true
        }
        popup.show()
    }

    private fun showCopyMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 0, 0, "文本")
        popup.menu.add(0, 1, 1, "Markdown")
        popup.setOnMenuItemClickListener { item ->
            val message = currentMessage ?: return@setOnMenuItemClickListener true
            when (item.itemId) {
                0 -> copyToClipboard(
                    Markdown.toPlainText(message.content),
                    "已复制文本",
                )

                1 -> copyToClipboard(message.content, "已复制 Markdown")
            }
            true
        }
        popup.show()
    }

    private fun copyToClipboard(text: String, toast: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Lyra 消息", text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    private fun shareContent() {
        val message = currentMessage ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message.content)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享消息")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun formatTime(createdAt: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = createdAt }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(createdAt))
    }

    private fun startTypewriter(text: String, style: ChatStyle) {
        if (text.isEmpty()) {
            loadContentWeb(text, style, reload = true)
            return
        }
        contentText.visibility = VISIBLE
        contentText.setTextColor(style.assistantText)
        contentText.maxWidth = style.maxBubbleWidthPx
        contentText.text = "▍"
        val total = text.length
        val step = (total / 120).coerceAtLeast(1)
        var index = 0
        typewriterTicks = 0
        val runnable = object : Runnable {
            override fun run() {
                if (currentMessage?.content != text) {
                    typewriterRunnable = null
                    return
                }
                index = (index + step).coerceAtMost(total)
                contentText.text = if (index < total) {
                    text.substring(0, index) + "▍"
                } else {
                    text
                }
                if (index < total) {
                    typewriterTicks += 1
                    if (typewriterTicks % 6 == 0) onMeasured?.invoke()
                    postDelayed(this, 18)
                } else {
                    typewriterRunnable = null
                    contentText.visibility = GONE
                    loadContentWeb(text, style, reload = true)
                    requestLayout()
                    onMeasured?.invoke()
                }
            }
        }
        typewriterRunnable = runnable
        post(runnable)
    }

    private fun cancelTypewriter() {
        typewriterRunnable?.let { removeCallbacks(it) }
        typewriterRunnable = null
    }

    fun onRecycled() {
        cancelTypewriter()
        contentWeb?.stopLoading()
        contentWeb?.loadUrl("about:blank")
        contentWeb?.visibility = GONE
        thinkingWeb?.stopLoading()
        thinkingWeb?.loadUrl("about:blank")
        thinkingWeb?.visibility = GONE
        lastContent = null
        lastReasoning = null
    }

    fun releaseWebViews() {
        cancelTypewriter()
        destroyWeb(contentWeb)
        contentWeb = null
        destroyWeb(thinkingWeb)
        thinkingWeb = null
    }

    // ---------------------------------------------------------------- WebView

    private fun loadContentWeb(markdown: String, style: ChatStyle, reload: Boolean) {
        val fresh = contentWeb == null
        val web = ensureContentWeb()
        web.visibility = VISIBLE
        if (reload || fresh) {
            resetWebHeight(web)
            web.loadDataWithBaseURL(
                null,
                ChatHtml.render(markdown, style, style.assistantText, compact = false),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    private fun loadThinkingWeb(markdown: String, style: ChatStyle, reload: Boolean) {
        val fresh = thinkingWeb == null
        val web = ensureThinkingWeb()
        web.visibility = VISIBLE
        if (reload || fresh) {
            resetWebHeight(web)
            web.loadDataWithBaseURL(
                null,
                ChatHtml.render(markdown, style, style.mutedText, compact = true),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    /** 换内容时先缩回 1dp，避免复用视图时沿用上一条消息的巨大高度。 */
    private fun resetWebHeight(web: WebView) {
        val params = web.layoutParams ?: return
        if (params.height != dp(1)) {
            params.height = dp(1)
            web.layoutParams = params
            web.requestLayout()
        }
    }

    private fun ensureContentWeb(): WebView {
        contentWeb?.let { return it }
        val web = createWebView { onMeasured?.invoke() }
        web.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )
        bubble.addView(web)
        contentWeb = web
        return web
    }

    private fun ensureThinkingWeb(): WebView {
        thinkingWeb?.let { return it }
        // 展开思考区只改变高度，不主动把列表滚到底，避免标题被顶出视口。
        val web = createWebView { }
        web.layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )
        thinkingBox.addView(web)
        thinkingWeb = web
        return web
    }

    private fun createWebView(onHeightChanged: () -> Unit): WebView {
        val web = WebView(context)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isNestedScrollingEnabled = true
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.webViewClient = ExternalLinkClient(context) { name ->
            onMentionClick?.invoke(name)
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = false
            loadsImagesAutomatically = true
            textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(80, 200)
        }
        web.addJavascriptInterface(
            HeightBridge(web, resources.displayMetrics.density, onHeightChanged),
            "LyraHeight",
        )
        web.addJavascriptInterface(CopyBridge(context), "LyraCopy")
        web.addJavascriptInterface(AnchorBridge(web), "LyraAnchor")
        web.addJavascriptInterface(HitBridge(web), "LyraHit")
        web.addJavascriptInterface(RectBridge(web), "LyraRects")
        web.setOnTouchListener { view, event ->
            val target = view as? WebView ?: return@setOnTouchListener false
            val rects = codeHitRects[target].orEmpty()
            if (rects.isEmpty()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val inside = rects.any { it.contains(event.x, event.y) }
                    codeHitLocked[target] = inside
                    if (inside) lockParentScroll(view, true)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (codeHitLocked[target] == true) lockParentScroll(view, false)
                    codeHitLocked[target] = false
                }
            }
            false
        }
        return web
    }

    private fun destroyWeb(web: WebView?) {
        if (web == null) return
        (web.parent as? ViewGroup)?.removeView(web)
        web.stopLoading()
        web.loadUrl("about:blank")
        web.removeJavascriptInterface("LyraHeight")
        web.removeJavascriptInterface("LyraCopy")
        web.removeJavascriptInterface("LyraAnchor")
        web.removeJavascriptInterface("LyraHit")
        web.removeJavascriptInterface("LyraRects")
        codeHitRects.remove(web)
        codeHitLocked.remove(web)
        web.destroy()
    }

    // ---------------------------------------------------------------- 文本 / 背景

    private fun showTextContent(text: String, color: Int, style: ChatStyle) {
        contentText.visibility = VISIBLE
        contentText.text = text
        contentText.setTextColor(color)
        contentText.maxWidth = style.maxBubbleWidthPx
    }

    private fun bubbleDrawable(color: Int, isUser: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            val large = dp(20).toFloat()
            val small = dp(6).toFloat()
            cornerRadii = if (isUser) {
                floatArrayOf(large, large, large, large, small, small, large, large)
            } else {
                floatArrayOf(large, large, large, large, large, large, small, small)
            }
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** WebView 内容高度上报；CSS px -> 物理 px。 */
private val codeHitRects = WeakHashMap<WebView, List<RectF>>()
private val codeHitLocked = WeakHashMap<WebView, Boolean>()

private class RectBridge(private val web: WebView) {
    @JavascriptInterface
    fun update(json: String) {
        val density = web.resources.displayMetrics.density
        val rects = runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        RectF(
                            (item.optDouble("x", 0.0) * density).toFloat(),
                            (item.optDouble("y", 0.0) * density).toFloat(),
                            ((item.optDouble("x", 0.0) + item.optDouble("w", 0.0)) * density).toFloat(),
                            ((item.optDouble("y", 0.0) + item.optDouble("h", 0.0)) * density).toFloat(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        codeHitRects[web] = rects
    }
}

private class ScrollLockRecyclerView(context: Context) : RecyclerView(context) {
    var scrollLocked: Boolean = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean =
        if (scrollLocked) false else super.onInterceptTouchEvent(e)

    override fun onTouchEvent(e: MotionEvent): Boolean =
        if (scrollLocked) false else super.onTouchEvent(e)
}

private fun View.findScrollLockRecycler(): ScrollLockRecyclerView? {
    var parent: ViewParent? = this.parent
    while (parent != null) {
        if (parent is ScrollLockRecyclerView) return parent
        parent = parent.parent
    }
    return null
}

private fun lockParentScroll(view: View, locked: Boolean) {
    var parent: ViewParent? = view.parent
    while (parent != null) {
        parent.requestDisallowInterceptTouchEvent(locked)
        if (parent is RecyclerView) break
        parent = parent.parent
    }
}

private class HitBridge(private val web: WebView) {
    @JavascriptInterface
    fun begin() {
        web.post {
            (web.findRecycler() as? ScrollLockRecyclerView)?.scrollLocked = true
            lockParentScroll(web, true)
        }
    }

    @JavascriptInterface
    fun end() {
        web.post {
            (web.findRecycler() as? ScrollLockRecyclerView)?.scrollLocked = false
            lockParentScroll(web, false)
        }
    }
}

private class AnchorBridge(private val web: WebView) {
    private var blockTopCss = 0f
    private var oldScreenY = 0

    @JavascriptInterface
    fun beforeToggle(topCss: Float) {
        blockTopCss = topCss
        oldScreenY = screenY()
    }

    @JavascriptInterface
    fun afterToggle() {
        web.postDelayed({
            val delta = screenY() - oldScreenY
            if (delta != 0) web.findRecycler()?.scrollBy(0, delta)
        }, 120)
        web.postDelayed({
            val delta = screenY() - oldScreenY
            if (delta != 0) web.findRecycler()?.scrollBy(0, delta)
        }, 420)
    }

    private fun screenY(): Int {
        val location = IntArray(2)
        web.getLocationOnScreen(location)
        val density = web.resources.displayMetrics.density
        return location[1] + (blockTopCss * density).toInt()
    }
}

private fun View.findRecycler(): RecyclerView? {
    var parent: ViewParent? = this.parent
    while (parent != null) {
        if (parent is RecyclerView) return parent
        parent = parent.parent
    }
    return null
}

private class ScrollBridge(private val web: WebView) {
    @JavascriptInterface
    fun begin() {
        web.post { lockParentScroll(web, true) }
    }

    @JavascriptInterface
    fun end() {
        web.post { lockParentScroll(web, false) }
    }
}

private class CopyBridge(private val context: Context) {
    @JavascriptInterface
    fun copy(text: String) {
        Handler(Looper.getMainLooper()).post {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@post
            manager.setPrimaryClip(ClipData.newPlainText("Lyra", text))
        }
    }
}

private class HeightBridge(
    private val webView: WebView,
    private val density: Float,
    private val onMeasured: () -> Unit,
) {
    @JavascriptInterface
    fun postMessage(value: String) {
        val cssHeight = value.toFloatOrNull() ?: return
        val height = (cssHeight * density).toInt().coerceAtLeast(1)
        webView.post {
            val params = webView.layoutParams ?: return@post
            if (params.height != height) {
                params.height = height
                webView.layoutParams = params
                webView.requestLayout()
                onMeasured()
            }
        }
    }
}

private class ExternalLinkClient(
    private val context: Context,
    private val onMention: ((String) -> Unit)? = null,
) : WebViewClient() {

    /** 本地图片走虚拟域名，避免 WebView 直接开 file:// 权限。 */
    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (url.host == "lyra.local" && url.path == "/image") {
            val path = url.getQueryParameter("path") ?: return null
            val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
            val roots = listOfNotNull(
                context.filesDir,
                context.cacheDir,
                context.getExternalFilesDir(null),
            )
            val allowed = roots.any { root ->
                file.path.startsWith(root.canonicalPath + File.separator)
            }
            if (allowed && file.isFile) {
                val mime = when (file.extension.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/png"
                }
                val stream = runCatching { file.inputStream() }.getOrNull() ?: return null
                return WebResourceResponse(mime, null, stream)
            }
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url: Uri = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme == "lyra-mention") {
            val name = url.host ?: url.lastPathSegment
            if (!name.isNullOrBlank()) onMention?.invoke(Uri.decode(name))
            return true
        }
        if (scheme == "http" || scheme == "https" || scheme == "mailto") {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        return true
    }
}

/** Markdown HTML 模板：表格、代码块、引用、链接的 WebView 样式。 */
internal object ChatHtml {

    fun render(
        markdown: String,
        style: ChatStyle,
        textColor: Int,
        compact: Boolean,
    ): String {
        val body = Markdown.toHtml(markdown)
        val foreground = hex(textColor)
        val muted = hex(style.mutedText)
        val primary = hex(style.primary)
        val outline = hex(style.outline)
        val codeBackground = hex(style.codeBackground)
        val tableHeader = hex(style.tableHeader)
        val fontSize = if (compact) 14 else 15

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
              <meta charset="utf-8">
              <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; frame-src 'self' data:; base-uri 'none'; form-action 'none';">
              <style>
                * { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; background: transparent; }
                body {
                  color: $foreground;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans SC", sans-serif;
                  font-size: ${fontSize}px;
                  line-height: 1.55;
                  overflow-wrap: anywhere;
                  word-break: break-word;
                }
                p { margin: 0 0 8px; }
                p:last-child { margin-bottom: 0; }
                h1, h2, h3, h4, h5, h6 { margin: 12px 0 6px; font-weight: 600; line-height: 1.3; }
                h1 { font-size: 1.5em; }
                h2 { font-size: 1.3em; }
                h3 { font-size: 1.15em; }
                h4, h5, h6 { font-size: 1em; }
                ul, ol { margin: 0 0 8px; padding-left: 20px; }
                li { margin: 2px 0; }
                blockquote {
                  margin: 8px 0;
                  padding: 4px 10px;
                  border-left: 3px solid $primary;
                  color: $muted;
                }
                code {
                  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
                  background: $codeBackground;
                  padding: 1px 4px;
                  border-radius: 4px;
                  font-size: 0.92em;
                }
                pre {
                  background: $codeBackground;
                  padding: 10px;
                  border-radius: 8px;
                  overflow-x: auto;
                  white-space: pre;
                  margin: 8px 0;
                }
                pre code { background: transparent; padding: 0; font-size: 0.9em; }
                a { color: $primary; text-decoration: none; }
                hr { border: 0; border-top: 1px solid $outline; margin: 12px 0; }
                .table-wrap { width: 100%; overflow-x: auto; margin: 8px 0; }
                table { border-collapse: collapse; width: 100%; font-size: 0.92em; }
                th, td {
                  border: 1px solid $outline;
                  padding: 5px 8px;
                  vertical-align: top;
                  text-align: left;
                }
                th { background: $tableHeader; font-weight: 600; }
                picture { display: block; max-width: 100%; }
                picture img { display: block; max-width: 100%; height: auto; }
                .lyra-canvas {
                  position: relative;
                  width: 100%;
                  margin: 8px 0;
                  overflow: hidden;
                  border-radius: 8px;
                  background: #fff;
                  touch-action: none;
                }
                .lyra-canvas canvas {
                  display: block;
                  width: 100%;
                  height: auto;
                  transform-origin: 0 0;
                }
                .lyra-canvas-hint {
                  position: absolute;
                  right: 8px;
                  bottom: 8px;
                  padding: 2px 6px;
                  border-radius: 4px;
                  background: rgba(0, 0, 0, 0.45);
                  color: #fff;
                  font-size: 11px;
                  line-height: 1.4;
                  pointer-events: none;
                }
                .code-block {
                  margin: 8px 0;
                  border: 1px solid $outline;
                  border-radius: 8px;
                  overflow: hidden;
                }
                .code-bar {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  padding: 5px 10px;
                  background: $codeBackground;
                  border-bottom: 1px solid $outline;
                  color: $muted;
                  font-size: 12px;
                }
                .code-block pre {
                  margin: 0;
                  border-radius: 0;
                  background: $codeBackground;
                }
                .lyra-copy {
                  border: 0;
                  background: transparent;
                  color: $primary;
                  font: inherit;
                  cursor: pointer;
                  padding: 2px 4px;
                  border-radius: 4px;
                }
                .lyra-copy:active { background: rgba(0, 0, 0, 0.08); }
                .lyra-html-actions {
                  display: flex;
                  align-items: center;
                  gap: 10px;
                }
                .lyra-html-block {
                  margin: 8px 0;
                  border: 1px solid $outline;
                  border-radius: 8px;
                  overflow: hidden;
                  background: #fff;
                }
                .lyra-html-bar {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 10px;
                  padding: 6px 10px;
                  background: $codeBackground;
                  border-bottom: 1px solid $outline;
                  font-size: 12px;
                }
                .lyra-html-title { font-weight: 600; letter-spacing: 0.5px; }
                .lyra-html-switch {
                  display: flex;
                  align-items: center;
                  gap: 6px;
                  color: $muted;
                  cursor: pointer;
                  user-select: none;
                }
                .lyra-html-switch input { display: none; }
                .lyra-html-track {
                  position: relative;
                  width: 32px;
                  height: 18px;
                  border-radius: 9px;
                  background: $outline;
                  transition: background 0.15s;
                }
                .lyra-html-thumb {
                  position: absolute;
                  top: 2px;
                  left: 2px;
                  width: 14px;
                  height: 14px;
                  border-radius: 50%;
                  background: #fff;
                  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
                  transition: transform 0.15s;
                }
                .lyra-html-switch input:checked + .lyra-html-track { background: $primary; }
                .lyra-html-switch input:checked + .lyra-html-track .lyra-html-thumb {
                  transform: translateX(14px);
                }
                .lyra-html-body { position: relative; }
                .lyra-html-code {
                  display: none;
                  margin: 0;
                  max-height: 360px;
                  overflow: auto;
                  border-radius: 0;
                  background: $codeBackground;
                }
                .code-block pre {
                  max-height: 360px;
                  overflow: auto;
                }
                .lyra-html-block .lyra-html {
                  display: block;
                  width: 100%;
                  height: 360px;
                  margin: 0;
                  border: 0;
                  border-radius: 0;
                  background: #fff;
                  overflow: hidden;
                }
                .lyra-html-code::-webkit-scrollbar,
                .code-block pre::-webkit-scrollbar { width: 8px; height: 8px; }
                .lyra-html-code::-webkit-scrollbar-thumb,
                .code-block pre::-webkit-scrollbar-thumb {
                  background: rgba(0, 0, 0, 0.3);
                  border-radius: 4px;
                }
                .lyra-html-code::-webkit-scrollbar-track,
                .code-block pre::-webkit-scrollbar-track { background: transparent; }
                .lyra-html-block.show-code .lyra-html-code { display: block; }
                .lyra-html-block.show-code .lyra-html { display: none; }
                ::-webkit-scrollbar { width: 0; height: 0; }
              </style>
            </head>
            <body>
              $body
              <script>
                (function () {
                  function report() {
                    var body = document.body;
                    var html = document.documentElement;
                    // 只用 body 的内容高度；html 的视口高度会把上一条消息的高度反馈回来。
                    var height = Math.max(body.scrollHeight, body.offsetHeight);
                    if (window.LyraHeight) window.LyraHeight.postMessage(String(height));
                  }
                  window.addEventListener('load', report);
                  document.addEventListener('DOMContentLoaded', report);
                  setTimeout(report, 50);
                  setTimeout(report, 300);
                  if (window.ResizeObserver) {
                    new ResizeObserver(report).observe(document.body);
                  }
                })();
              </script>
              <script>
                (function () {
                  "use strict";

                  function decodeSpec(b64) {
                    var bin = atob(b64);
                    var bytes = new Uint8Array(bin.length);
                    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    return JSON.parse(new TextDecoder("utf-8").decode(bytes));
                  }

                  function n(o, keys, d) {
                    for (var i = 0; i < keys.length; i++) {
                      var k = keys[i];
                      if (o[k] !== undefined && o[k] !== null) return Number(o[k]) || 0;
                    }
                    return d || 0;
                  }

                  function color(v, fallback) {
                    return (typeof v === "string" && v.trim()) ? v : fallback;
                  }

                  function apply(ctx, el) {
                    var stroke = el.stroke || el.strokeColor || "";
                    if (stroke) {
                      ctx.strokeStyle = stroke;
                      ctx.lineWidth = n(el, ["strokeWidth", "lineWidth"], 3);
                      return true;
                    }
                    ctx.fillStyle = color(el.fill || el.color || el.background, "#000");
                    return false;
                  }

                  function drawSpec(canvas, spec) {
                    var w = Math.max(16, Math.min(4096, Number(spec.width) || 600));
                    var h = Math.max(16, Math.min(4096, Number(spec.height) || 400));
                    canvas.width = w;
                    canvas.height = h;
                    var ctx = canvas.getContext("2d");
                    ctx.clearRect(0, 0, w, h);
                    ctx.fillStyle = color(spec.background, "#fff");
                    ctx.fillRect(0, 0, w, h);
                    var elements = spec.elements || [];
                    for (var i = 0; i < elements.length; i++) {
                      var el = elements[i] || {};
                      var type = String(el.type || "").toLowerCase();
                      ctx.save();
                      if (type === "rect" || type === "rectangle" || type === "square") {
                        var x = n(el, ["x", "left"]), y = n(el, ["y", "top"]);
                        var rw = n(el, ["w", "width"]), rh = n(el, ["h", "height"]);
                        var rs = apply(ctx, el);
                        ctx.beginPath();
                        if (ctx.roundRect) ctx.roundRect(x, y, rw, rh, n(el, ["radius", "cornerRadius", "rx"]));
                        else ctx.rect(x, y, rw, rh);
                        if (rs) ctx.stroke(); else ctx.fill();
                      } else if (type === "circle" || type === "dot") {
                        var cx = n(el, ["cx", "x", "centerX"]);
                        var cy = n(el, ["cy", "y", "centerY"]);
                        var cr = Math.max(1, n(el, ["r", "radius"]));
                        var cs = apply(ctx, el);
                        ctx.beginPath(); ctx.arc(cx, cy, cr, 0, Math.PI * 2);
                        if (cs) ctx.stroke(); else ctx.fill();
                      } else if (type === "ellipse" || type === "oval") {
                        var ex = n(el, ["cx", "x", "centerX"]);
                        var ey = n(el, ["cy", "y", "centerY"]);
                        var rx = Math.max(1, n(el, ["rx", "radiusX", "radius"]));
                        var ry = Math.max(1, n(el, ["ry", "radiusY", "radius"]));
                        var es = apply(ctx, el);
                        ctx.beginPath(); ctx.ellipse(ex, ey, rx, ry, 0, 0, Math.PI * 2);
                        if (es) ctx.stroke(); else ctx.fill();
                      } else if (type === "line") {
                        ctx.strokeStyle = color(el.color || el.stroke, "#000");
                        ctx.lineWidth = n(el, ["strokeWidth", "width"], 3);
                        var st = el.start || [], en = el.end || [];
                        ctx.beginPath();
                        ctx.moveTo(n(el, ["x1", "startX"]) || Number(st[0]) || 0, n(el, ["y1", "startY"]) || Number(st[1]) || 0);
                        ctx.lineTo(n(el, ["x2", "endX"]) || Number(en[0]) || 0, n(el, ["y2", "endY"]) || Number(en[1]) || 0);
                        ctx.stroke();
                      } else if (type === "text" || type === "label") {
                        var size = Math.max(8, Math.min(512, n(el, ["size", "textSize", "fontSize"], 48)));
                        ctx.fillStyle = color(el.color || el.fill, "#000");
                        ctx.font = (el.bold ? "bold " : "") + size + "px sans-serif";
                        var align = String(el.align || "left").toLowerCase();
                        ctx.textAlign = align === "center" ? "center" : (align === "right" ? "right" : "left");
                        var text = el.text || el.content || "";
                        if (text) ctx.fillText(text, n(el, ["x", "left"]), n(el, ["y", "baseline"]));
                      } else if (type === "polygon") {
                        var points = el.points || [];
                        if (points.length >= 3) {
                          var ps = apply(ctx, el);
                          ctx.beginPath();
                          for (var j = 0; j < points.length; j++) {
                            var pt = points[j] || [];
                            if (j === 0) ctx.moveTo(Number(pt[0]) || 0, Number(pt[1]) || 0);
                            else ctx.lineTo(Number(pt[0]) || 0, Number(pt[1]) || 0);
                          }
                          ctx.closePath();
                          if (ps) ctx.stroke(); else ctx.fill();
                        }
                      } else if (type === "path") {
                        var commands = el.commands || [];
                        if (commands.length) {
                          var pc = apply(ctx, el);
                          ctx.beginPath();
                          for (var k = 0; k < commands.length; k++) {
                            var cmd = commands[k] || {};
                            var op = String(cmd.op || "").toLowerCase();
                            if (op === "moveto" || op === "m") ctx.moveTo(n(cmd, ["x"]), n(cmd, ["y"]));
                            else if (op === "lineto" || op === "l") ctx.lineTo(n(cmd, ["x"]), n(cmd, ["y"]));
                            else if (op === "quadto" || op === "q" || op === "quadratic") {
                              ctx.quadraticCurveTo(
                                n(cmd, ["cx", "x1", "controlX"]), n(cmd, ["cy", "y1", "controlY"]),
                                n(cmd, ["x", "x2", "endX"]), n(cmd, ["y", "y2", "endY"])
                              );
                            } else if (op === "cubicto" || op === "c" || op === "curve" || op === "bezier") {
                              ctx.bezierCurveTo(
                                n(cmd, ["x1", "control1X"]), n(cmd, ["y1", "control1Y"]),
                                n(cmd, ["x2", "control2X"]), n(cmd, ["y2", "control2Y"]),
                                n(cmd, ["x", "x3", "endX"]), n(cmd, ["y", "y3", "endY"])
                              );
                            } else if (op === "close" || op === "z") ctx.closePath();
                          }
                          if (pc) ctx.stroke(); else ctx.fill();
                        }
                      } else if (type === "bezier" || type === "curve") {
                        var bs = el.start || [], bc1 = el.control1 || el.c1 || [];
                        var bc2 = el.control2 || el.c2 || [], be = el.end || [];
                        var bstroke = apply(ctx, el);
                        ctx.beginPath();
                        ctx.moveTo(Number(bs[0]) || 0, Number(bs[1]) || 0);
                        ctx.bezierCurveTo(
                          Number(bc1[0]) || 0, Number(bc1[1]) || 0,
                          Number(bc2[0]) || 0, Number(bc2[1]) || 0,
                          Number(be[0]) || 0, Number(be[1]) || 0
                        );
                        if (bstroke) ctx.stroke(); else ctx.fill();
                      }
                      ctx.restore();
                    }
                  }

                  function attachGestures(box, canvas) {
                    var scale = 1, tx = 0, ty = 0;
                    var pointers = new Map();
                    var startX = 0, startY = 0, startTx = 0, startTy = 0;
                    var startDist = 0, startScale = 1, startMid = null;
                    function applyTransform() {
                      var max = 8, min = 0.5;
                      scale = Math.max(min, Math.min(max, scale));
                      canvas.style.transform = "translate(" + tx + "px," + ty + "px) scale(" + scale + ")";
                    }
                    function distance() {
                      var list = Array.from(pointers.values());
                      var dx = list[0].x - list[1].x, dy = list[0].y - list[1].y;
                      return Math.sqrt(dx * dx + dy * dy);
                    }
                    function midpoint() {
                      var list = Array.from(pointers.values());
                      return { x: (list[0].x + list[1].x) / 2, y: (list[0].y + list[1].y) / 2 };
                    }
                    box.addEventListener("pointerdown", function (e) {
                      box.setPointerCapture(e.pointerId);
                      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
                      if (pointers.size === 1) {
                        startX = e.clientX; startY = e.clientY;
                        startTx = tx; startTy = ty;
                      } else if (pointers.size === 2) {
                        startDist = distance(); startScale = scale; startMid = midpoint();
                        startTx = tx; startTy = ty;
                      }
                      e.preventDefault();
                    });
                    box.addEventListener("pointermove", function (e) {
                      if (!pointers.has(e.pointerId)) return;
                      pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
                      if (pointers.size === 1) {
                        tx = startTx + (e.clientX - startX);
                        ty = startTy + (e.clientY - startY);
                        applyTransform();
                      } else if (pointers.size >= 2) {
                        var dist = distance();
                        scale = startScale * (dist / Math.max(1, startDist));
                        var mid = midpoint();
                        tx = startTx + (mid.x - startMid.x);
                        ty = startTy + (mid.y - startMid.y);
                        applyTransform();
                      }
                      e.preventDefault();
                    });
                    function up(e) {
                      pointers.delete(e.pointerId);
                      if (pointers.size === 0) {
                        startTx = tx; startTy = ty;
                      } else if (pointers.size === 1) {
                        var remaining = Array.from(pointers.values())[0];
                        startX = remaining.x; startY = remaining.y;
                        startTx = tx; startTy = ty;
                      }
                    }
                    box.addEventListener("pointerup", up);
                    box.addEventListener("pointercancel", up);
                    box.addEventListener("wheel", function (e) {
                      e.preventDefault();
                      scale = scale * (e.deltaY < 0 ? 1.1 : 0.9);
                      applyTransform();
                    }, { passive: false });
                  }

                  function reportHitRects() {
                    if (!window.LyraRects) return;
                    var areas = document.querySelectorAll(
                      ".lyra-html-code, .code-block pre, .lyra-html"
                    );
                    var rects = [];
                    for (var i = 0; i < areas.length; i++) {
                      var el = areas[i];
                      if (el.offsetParent === null) continue;
                      var r = el.getBoundingClientRect();
                      if (r.width <= 0 || r.height <= 0) continue;
                      rects.push({ x: r.left, y: r.top, w: r.width, h: r.height });
                    }
                    window.LyraRects.update(JSON.stringify(rects));
                  }

                  function setup() {
                    var boxes = document.querySelectorAll(".lyra-canvas");
                    for (var i = 0; i < boxes.length; i++) {
                      var box = boxes[i];
                      var canvas = box.querySelector("canvas");
                      if (!canvas) continue;
                      try {
                        var spec = decodeSpec(box.getAttribute("data-spec") || "");
                        drawSpec(canvas, spec);
                        var ratio = canvas.height / Math.max(1, canvas.width);
                        box.style.height = Math.round(box.clientWidth * ratio) + "px";
                        attachGestures(box, canvas);
                      } catch (e) {
                        box.textContent = "绘图数据无效";
                      }
                    }
                    var codeAreas = document.querySelectorAll(".lyra-html-code, .code-block pre");
                    for (var c = 0; c < codeAreas.length; c++) enableCodeTouchScroll(codeAreas[c]);
                    var htmlBlocks = document.querySelectorAll(".lyra-html-block");
                    for (var h = 0; h < htmlBlocks.length; h++) {
                      (function (block) {
                        var input = block.querySelector(".lyra-html-switch input");
                        if (!input) return;
                        function apply() {
                          block.classList.toggle("show-code", !input.checked);
                          setTimeout(reportHitRects, 0);
                          setTimeout(reportHitRects, 300);
                        }
                        input.addEventListener("change", function () {
                          var top = block.getBoundingClientRect().top;
                          if (window.LyraAnchor) window.LyraAnchor.beforeToggle(top);
                          apply();
                          if (window.LyraAnchor) {
                            setTimeout(function () { window.LyraAnchor.afterToggle(); }, 80);
                            setTimeout(function () { window.LyraAnchor.afterToggle(); }, 420);
                          }
                        });
                        apply();
                      })(htmlBlocks[h]);
                    }
                    function enableCodeTouchScroll(el) {
                      if (!el || el.__lyraTouchScroll) return;
                      el.__lyraTouchScroll = true;
                      var startX = 0, startY = 0, startLeft = 0, startTop = 0, tracking = false;
                      el.addEventListener("touchstart", function (e) {
                        if (e.touches.length !== 1) return;
                        tracking = true;
                        startX = e.touches[0].clientX;
                        startY = e.touches[0].clientY;
                        startLeft = el.scrollLeft;
                        startTop = el.scrollTop;
                        holdScroll(true);
                      }, { passive: true });
                      el.addEventListener("touchmove", function (e) {
                        if (!tracking || e.touches.length !== 1) return;
                        var dx = e.touches[0].clientX - startX;
                        var dy = e.touches[0].clientY - startY;
                        el.scrollLeft = startLeft - dx;
                        el.scrollTop = startTop - dy;
                        e.preventDefault();
                        e.stopPropagation();
                      }, { passive: false });
                      function stop() {
                        tracking = false;
                        holdScroll(false);
                      }
                      el.addEventListener("touchend", stop, { passive: true });
                      el.addEventListener("touchcancel", stop, { passive: true });
                    }

                    function holdScroll(active) {
                      if (window.LyraHit) {
                        if (active) window.LyraHit.begin();
                        else window.LyraHit.end();
                      }
                    }
                    document.addEventListener("pointerdown", function (e) {
                      var target = e.target;
                      if (target && target.closest && target.closest(".lyra-html-code, .code-block pre")) {
                        holdScroll(true);
                      }
                    }, true);
                    ["pointerup", "pointercancel", "blur"].forEach(function (name) {
                      window.addEventListener(name, function () { holdScroll(false); }, true);
                    });
                    window.addEventListener("message", function (e) {
                      if (!e.data || e.data.type !== "lyra-scroll") return;
                      var frames = document.querySelectorAll(".lyra-html");
                      var fromPreview = false;
                      for (var f = 0; f < frames.length; f++) {
                        if (frames[f].contentWindow === e.source) {
                          fromPreview = true;
                          break;
                        }
                      }
                      if (fromPreview) holdScroll(!!e.data.active);
                    });
                    function fallbackCopy(text) {
                      var area = document.createElement("textarea");
                      area.value = text;
                      area.style.position = "fixed";
                      area.style.opacity = "0";
                      document.body.appendChild(area);
                      area.select();
                      try { document.execCommand("copy"); } catch (e) {}
                      document.body.removeChild(area);
                    }
                    document.addEventListener("click", function (e) {
                      var target = e.target;
                      var button = target && target.closest ? target.closest(".lyra-copy") : null;
                      if (!button) return;
                      var block = button.closest(".code-block, .lyra-html-block");
                      var code = block ? block.querySelector("pre code") : null;
                      if (!code) return;
                      var text = code.textContent || "";
                      try {
                        if (window.LyraCopy) window.LyraCopy.copy(text);
                        else fallbackCopy(text);
                      } catch (err) {
                        fallbackCopy(text);
                      }
                      var old = button.textContent;
                      button.textContent = "已复制";
                      setTimeout(function () { button.textContent = old; }, 1200);
                    });
                    reportHitRects();
                    setTimeout(reportHitRects, 300);
                    setTimeout(reportHitRects, 900);
                    window.addEventListener("resize", reportHitRects);
                    if (window.ResizeObserver) new ResizeObserver(function () {
                      if (window.LyraHeight) window.LyraHeight.postMessage(String(Math.max(document.body.scrollHeight, document.body.offsetHeight)));
                      reportHitRects();
                    }).observe(document.body);
                  }

                  if (document.readyState === "loading") {
                    document.addEventListener("DOMContentLoaded", setup);
                  } else {
                    setup();
                  }
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
