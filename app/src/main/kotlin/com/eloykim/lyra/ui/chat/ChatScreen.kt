package com.eloykim.lyra.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eloykim.lyra.R
import com.eloykim.lyra.network.ThinkingLevel
import com.eloykim.lyra.settings.SettingsStore

/**
 * 聊天页。
 *
 * - 消息列表是 RecyclerView；
 * - 助手消息的 Markdown 经词法分析 -> AST -> HTML，由 WebView 渲染（支持表格/代码块）；
 * - 顶部标题下方是会话下拉框，右上角是单词形式的思考等级。
 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val conversations by ChatStore.conversations.collectAsStateWithLifecycle()
    val activeId by ChatStore.activeId.collectAsStateWithLifecycle()
    val ready by ChatStore.ready.collectAsStateWithLifecycle()
    val sending by ChatController.sending.collectAsStateWithLifecycle()
    val settings by SettingsStore.state.collectAsStateWithLifecycle()
    val chatStyle = rememberChatStyle()

    var input by rememberSaveable { mutableStateOf("") }
    var pendingQuote by remember { mutableStateOf<ChatMessage?>(null) }
    var scrollToLatestHeadKey by remember { mutableStateOf(0) }
    var conversationsExpanded by remember { mutableStateOf(false) }
    var deleteConversationTarget by remember { mutableStateOf<ChatConversation?>(null) }
    var thinkingExpanded by remember { mutableStateOf(false) }

    val activeConversation = conversations.firstOrNull { it.id == activeId }
    val messages = activeConversation?.messages.orEmpty()
    val activeTitle = activeConversation?.title?.ifBlank { "新会话" } ?: "新会话"
    val keyMissing = settings.apiKeyHint.isBlank() &&
        settings.brandId != "local" &&
        settings.brandId != "openrouter"

    LaunchedEffect(Unit) {
        ChatStore.restore()
        ChatStore.ensureActive()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                    )
                }

                Box(Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { conversationsExpanded = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text("聊天", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$activeTitle · ${settings.brand.label} · ${settings.model}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = conversationsExpanded,
                        onDismissRequest = { conversationsExpanded = false },
                        modifier = Modifier.widthIn(min = 220.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("新会话") },
                            leadingIcon = {
                                Text(
                                    text = "＋",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                ChatStore.newConversation()
                                conversationsExpanded = false
                            },
                        )
                        if (conversations.isNotEmpty()) {
                            HorizontalDivider()
                            conversations.forEach { conversation ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = conversation.title.ifBlank { "新会话" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${conversation.messages.size} 条消息",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (conversation.id == activeId) {
                                                Text(
                                                    text = "✓",
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    conversationsExpanded = false
                                                    deleteConversationTarget = conversation
                                                },
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_delete),
                                                    contentDescription = "删除会话",
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        ChatStore.select(conversation.id)
                                        conversationsExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Box {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.clickable { thinkingExpanded = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = settings.thinkingLevel.wireValue,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = thinkingExpanded,
                        onDismissRequest = { thinkingExpanded = false },
                    ) {
                        ThinkingLevel.entries.forEach { level ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = level.wireValue,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                trailingIcon = {
                                    if (level == settings.thinkingLevel) {
                                        Text(
                                            text = "✓",
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                onClick = {
                                    SettingsStore.setThinkingLevel(level)
                                    thinkingExpanded = false
                                },
                            )
                        }
                    }
                }

                if (messages.isNotEmpty()) {
                    IconButton(onClick = { ChatController.clear() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = "清空会话",
                        )
                    }
                }
            }
            HorizontalDivider()

            if (keyMissing) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "还没有配置 API Key，配置后才能对话",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onOpenSettings) {
                            Text("去设置")
                        }
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    messages.isEmpty() -> EmptyChat(onSuggestion = ChatController::send)

                    else -> ChatMessageList(
                        messages = messages,
                        style = chatStyle,
                        onReaction = { messageId, reaction ->
                            ChatStore.activeId.value?.let { conversationId ->
                                ChatStore.setReaction(conversationId, messageId, reaction)
                            }
                        },
                        onQuote = { messageId ->
                            pendingQuote = messages.firstOrNull { it.id == messageId }
                        },
                        onRegenerate = { messageId -> ChatController.regenerate(messageId) },
                        scrollToLatestHeadKey = scrollToLatestHeadKey,
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                    )
                }
            }

            ChatComposer(
                value = input,
                onValueChange = { input = it },
                sending = sending,
                ready = ready,
                quote = pendingQuote,
                quoteCandidates = messages
                    .filter { !it.pending && it.content.isNotBlank() }
                    .takeLast(8)
                    .reversed(),
                onPickQuote = { pendingQuote = it },
                onCancelQuote = {
                    pendingQuote = null
                },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        input = ""
                        val quote = pendingQuote
                        pendingQuote = null
                        ChatController.send(text, quote)
                    }
                },
            )
        }
    }

    deleteConversationTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConversationTarget = null },
            title = { Text("删除会话") },
            text = { Text("删除「${target.title}」及其全部消息？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ChatStore.deleteConversation(target.id)
                        deleteConversationTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConversationTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyChat(onSuggestion: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "L",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "和 Lyra 聊点什么",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "回答支持 Markdown；右上角可以调思考等级。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "用 Markdown 写一段自我介绍",
            "写一个 Kotlin 协程的示例",
            "把「今天辛苦了」翻译成日文",
        ).forEach { suggestion ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestion(suggestion) },
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    ready: Boolean,
    quote: ChatMessage?,
    quoteCandidates: List<ChatMessage>,
    onPickQuote: (ChatMessage) -> Unit,
    onCancelQuote: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = value.isNotBlank() && !sending && ready
    val submit = { if (canSend) onSend() }
    var quotePickerExpanded by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            if (quote != null) {
                QuoteStrip(
                    quote = quote,
                    onCancel = onCancelQuote,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .onPreviewKeyEvent { event ->
                        // 硬件键盘：Enter 发送，Shift+Enter 换行。
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Enter &&
                            !event.isShiftPressed
                        ) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                placeholder = {
                    Text(
                        text = "问点什么…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                trailingIcon = {
                    Box {
                        IconButton(onClick = { quotePickerExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_format_quote),
                                contentDescription = "引用历史消息",
                            )
                        }
                        DropdownMenu(
                            expanded = quotePickerExpanded,
                            onDismissRequest = { quotePickerExpanded = false },
                        ) {
                            if (quoteCandidates.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无可引用的消息") },
                                    onClick = { quotePickerExpanded = false },
                                    enabled = false,
                                )
                            } else {
                                quoteCandidates.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = if (candidate.role == ChatRole.User) {
                                                        "你"
                                                    } else {
                                                        "Lyra"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                Text(
                                                    text = candidate.content
                                                        .replace('\n', ' ')
                                                        .take(48),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        onClick = {
                                            quotePickerExpanded = false
                                            onPickQuote(candidate)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { submit() },
                ),
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun QuoteStrip(
    quote: ChatMessage,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            Text(
                text = if (quote.role == ChatRole.User) "你" else "Lyra",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = quote.content.replace('\n', ' ').take(120),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
            Text(
                text = "×",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
