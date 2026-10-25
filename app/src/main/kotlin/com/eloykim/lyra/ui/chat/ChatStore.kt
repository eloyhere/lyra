package com.eloykim.lyra.ui.chat

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.data.ChatConversationRecord
import com.eloykim.lyra.data.ChatMessageRecord
import com.eloykim.lyra.network.LlmClients
import com.eloykim.lyra.network.LlmHttpException
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.network.LlmResult
import com.eloykim.lyra.network.LlmTool
import com.eloykim.lyra.network.LlmToolCall
import com.eloykim.lyra.service.ServiceCommand
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.ServiceText
import com.eloykim.lyra.settings.SettingsStore
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ChatRole { User, Assistant }

/** 一条消息上的单选 emoji 评论（赞/踩/表情）。 */
internal data class MessageFeedback(
    val emoji: String,
)

/** 消息引用的原消息快照。 */
internal data class MessageQuote(
    val messageId: Long,
    val role: ChatRole,
    val content: String,
    val authorName: String? = null,
)

internal data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val content: String,
    /** 思维链；为空时不显示「思考过程」视图。 */
    val reasoning: String? = null,
    /** 这条消息收到的评论；为空表示没有。 */
    val feedback: MessageFeedback? = null,
    /** 引用的原消息；显示在气泡顶部。 */
    val quote: MessageQuote? = null,
    val pending: Boolean = false,
    val error: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** 群聊里的作者名；1:1 为空。 */
    val authorName: String? = null,
)

internal data class ChatConversation(
    val id: Long,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long,
    val updatedAt: Long,
    val providerId: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costMicros: Long = 0,
)

/**
 * 会话与消息都只在内存里保存：切页不丢，进程结束即清空。
 * 每条会话可以单独切换，列表按最近更新时间排序。
 */
internal object ChatStore {

    private val _conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    val conversations: StateFlow<List<ChatConversation>> = _conversations.asStateFlow()

    private val _activeId = MutableStateFlow<Long?>(null)
    val activeId: StateFlow<Long?> = _activeId.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var nextConversationId = 0L
    private var nextMessageId = 0L
    private var version = 0L
    private var observingReactions = false

    /** 从 SQLite 恢复会话；进程重启后第一次打开聊天页时调用。 */
    suspend fun restore() {
        if (_ready.value) return
        val records = runCatching { AppGraph.history.chatMessages.loadAll() }.getOrDefault(emptyList())
        val loaded = records.map { record ->
            ChatConversation(
                id = record.id,
                title = record.title,
                messages = record.messages.map { message ->
                    val interrupted = message.pending
                    ChatMessage(
                        id = message.id,
                        role = if (message.role == "user") ChatRole.User else ChatRole.Assistant,
                        content = if (interrupted && message.content.isBlank()) {
                            "上次回答没有生成完成"
                        } else {
                            message.content
                        },
                        reasoning = message.reasoning,
                        feedback = message.reaction?.let(::MessageFeedback),
                        quote = message.quotedContent?.takeIf { it.isNotBlank() }?.let { content ->
                            MessageQuote(
                                messageId = message.quotedMessageId ?: 0L,
                                role = if (message.quotedRole == "user") {
                                    ChatRole.User
                                } else {
                                    ChatRole.Assistant
                                },
                                content = content,
                            )
                        },
                        pending = false,
                        error = message.error || interrupted,
                        createdAt = message.createdAt,
                    )
                },
                createdAt = record.createdAt,
                updatedAt = record.updatedAt,
                providerId = record.providerId,
                model = record.model,
                thinkingLevel = record.thinkingLevel,
                inputTokens = record.inputTokens,
                outputTokens = record.outputTokens,
                costMicros = record.costMicros,
            )
        }.sortedByDescending { it.updatedAt }

        synchronized(this) {
            if (loaded.isNotEmpty()) {
                _conversations.value = loaded
                _activeId.value = loaded.first().id
                nextConversationId = (loaded.maxOfOrNull { it.id } ?: -1L) + 1
                nextMessageId = (loaded.flatMap { it.messages }.maxOfOrNull { it.id } ?: -1L) + 1
            }
            _ready.value = true
        }
        observeServiceReactions()
        ensureActive()
    }

    /** ChatMessageService 给消息点赞/点踩/评论后，聊天页实时同步。 */
    private fun observeServiceReactions() {
        if (observingReactions) return
        observingReactions = true
        AppGraph.appScope.launch {
            AppGraph.history.chatMessages.reactionChanges.collect { change ->
                synchronized(this@ChatStore) {
                    _conversations.value = _conversations.value.map { conversation ->
                        if (conversation.id != change.conversationId) {
                            conversation
                        } else {
                            conversation.copy(
                                messages = conversation.messages.map { message ->
                                    if (message.id == change.messageId) {
                                        message.copy(
                                            feedback = change.reaction?.let(::MessageFeedback),
                                        )
                                    } else {
                                        message
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun ensureActive(): Long = synchronized(this) {
        val id = ensureActiveLocked()
        persist()
        id
    }

    /** 新会话；如果当前会话还是空的，直接复用，避免空记录堆积。 */
    fun newConversation(): Long = synchronized(this) {
        val active = activeConversationLocked()
        if (active != null && active.messages.isEmpty()) {
            return active.id
        }
        val created = createConversationLocked()
        _activeId.value = created.id
        persist()
        created.id
    }

    fun select(conversationId: Long) {
        if (_conversations.value.any { it.id == conversationId }) {
            _activeId.value = conversationId
        }
    }

    fun messagesOf(conversationId: Long): List<ChatMessage> =
        _conversations.value.firstOrNull { it.id == conversationId }?.messages.orEmpty()

    fun addMessage(
        conversationId: Long,
        role: ChatRole,
        content: String,
        pending: Boolean = false,
        error: Boolean = false,
        quote: MessageQuote? = null,
    ): Long = synchronized(this) {
        val id = nextMessageId++
        val now = System.currentTimeMillis()
        _conversations.value = _conversations.value
            .map { conversation ->
                if (conversation.id != conversationId) {
                    conversation
                } else {
                    conversation.copy(
                        title = if (conversation.messages.isEmpty() && role == ChatRole.User) {
                            titleOf(content)
                        } else {
                            conversation.title
                        },
                        messages = conversation.messages + ChatMessage(
                            id = id,
                            role = role,
                            content = content,
                            pending = pending,
                            error = error,
                            quote = quote,
                            createdAt = now,
                        ),
                        updatedAt = now,
                    )
                }
            }
            .sortedByDescending { it.updatedAt }
        persist()
        id
    }

    fun updateMessage(
        conversationId: Long,
        messageId: Long,
        content: String,
        pending: Boolean,
        error: Boolean,
        reasoning: String?,
        quote: MessageQuote? = null,
    ) = synchronized(this) {
        val now = System.currentTimeMillis()
        _conversations.value = _conversations.value
            .map { conversation ->
                if (conversation.id != conversationId) {
                    conversation
                } else {
                    conversation.copy(
                        messages = conversation.messages.map { message ->
                            if (message.id == messageId) {
                                message.copy(
                                    content = content,
                                    pending = pending,
                                    error = error,
                                    reasoning = reasoning,
                                    quote = quote,
                                )
                            } else {
                                message
                            }
                        },
                        updatedAt = now,
                    )
                }
            }
            .sortedByDescending { it.updatedAt }
        persist()
    }

    /** 删除指定消息及其后的所有消息（用于重新生成最后一条回答）。 */
    fun removeFrom(conversationId: Long, messageId: Long) = synchronized(this) {
        val now = System.currentTimeMillis()
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id != conversationId) {
                conversation
            } else {
                val index = conversation.messages.indexOfFirst { it.id == messageId }
                val kept = if (index >= 0) {
                    conversation.messages.take(index)
                } else {
                    conversation.messages
                }
                conversation.copy(
                    title = if (kept.isEmpty()) "新会话" else conversation.title,
                    messages = kept,
                    updatedAt = now,
                )
            }
        }.sortedByDescending { it.updatedAt }
        persist()
    }

    /** 删除会话；如果删的是当前会话，自动切换到最近会话或新建一个。 */
    fun deleteConversation(conversationId: Long) = synchronized(this) {
        _conversations.value = _conversations.value.filterNot { it.id == conversationId }
        if (_activeId.value == conversationId) {
            val next = _conversations.value.maxByOrNull { it.updatedAt }
            _activeId.value = next?.id ?: createConversationLocked().id
        }
        ensureActiveLocked()
        persist()
    }

    fun updateSessionConfig(
        conversationId: Long,
        providerId: String,
        model: String,
        thinkingLevel: String,
    ) = synchronized(this) {
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(
                    providerId = providerId,
                    model = model,
                    thinkingLevel = thinkingLevel,
                )
            } else {
                conversation
            }
        }
        persist()
    }

    fun recordUsage(
        conversationId: Long,
        inputTokens: Int,
        outputTokens: Int,
        costMicros: Long,
    ) = synchronized(this) {
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(
                    inputTokens = conversation.inputTokens + inputTokens,
                    outputTokens = conversation.outputTokens + outputTokens,
                    costMicros = conversation.costMicros + costMicros,
                )
            } else {
                conversation
            }
        }
        persist()
    }

    fun rename(conversationId: Long, title: String) = synchronized(this) {
        val clean = title.trim().take(80)
        if (clean.isEmpty()) return@synchronized
        _conversations.value = _conversations.value.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(title = clean, updatedAt = System.currentTimeMillis())
            } else {
                conversation
            }
        }.sortedByDescending { it.updatedAt }
        persist()
    }

    /** 表情是单选：设置新值会覆盖旧值，传 null 取消。 */
    fun setReaction(conversationId: Long, messageId: Long, reaction: String?) =
        synchronized(this) {
            _conversations.value = _conversations.value.map { conversation ->
                if (conversation.id != conversationId) {
                    conversation
                } else {
                    conversation.copy(
                        messages = conversation.messages.map { message ->
                            if (message.id == messageId) {
                                message.copy(feedback = reaction?.let(::MessageFeedback))
                            } else {
                                message
                            }
                        },
                    )
                }
            }
            persist()
        }

    /** 清空当前会话的消息，但保留会话本身，其他会话不受影响。 */
    fun clearActive() = synchronized(this) {
        val id = ensureActiveLocked()
        val now = System.currentTimeMillis()
        _conversations.value = _conversations.value
            .map { conversation ->
                if (conversation.id == id) {
                    conversation.copy(title = "新会话", messages = emptyList(), updatedAt = now)
                } else {
                    conversation
                }
            }
            .sortedByDescending { it.updatedAt }
        persist()
    }

    private fun persist() {
        if (!_ready.value) return
        version += 1
        val snapshotVersion = version
        val snapshot = _conversations.value.map { conversation ->
            ChatConversationRecord(
                id = conversation.id,
                title = conversation.title,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
                providerId = conversation.providerId,
                model = conversation.model,
                thinkingLevel = conversation.thinkingLevel,
                inputTokens = conversation.inputTokens,
                outputTokens = conversation.outputTokens,
                costMicros = conversation.costMicros,
                messageCount = conversation.messages.size,
                lastMessageAt = conversation.messages.maxOfOrNull { it.createdAt }
                    ?: conversation.updatedAt,
                messages = conversation.messages.map { message ->
                    ChatMessageRecord(
                        id = message.id,
                        conversationId = conversation.id,
                        role = if (message.role == ChatRole.User) "user" else "assistant",
                        content = message.content,
                        reasoning = message.reasoning,
                        reaction = message.feedback?.emoji,
                        quotedMessageId = message.quote?.messageId,
                        quotedRole = message.quote?.role?.let {
                            if (it == ChatRole.User) "user" else "assistant"
                        },
                        quotedContent = message.quote?.content,
                        pending = message.pending,
                        error = message.error,
                        createdAt = message.createdAt,
                    )
                },
            )
        }
        AppGraph.appScope.launch {
            runCatching { AppGraph.history.chatMessages.saveSnapshot(snapshotVersion, snapshot) }
        }
    }

    private fun ensureActiveLocked(): Long {
        val active = _activeId.value
        if (active != null && _conversations.value.any { it.id == active }) return active
        val created = createConversationLocked()
        _activeId.value = created.id
        return created.id
    }

    private fun activeConversationLocked(): ChatConversation? =
        _conversations.value.firstOrNull { it.id == _activeId.value }

    private fun createConversationLocked(): ChatConversation {
        val now = System.currentTimeMillis()
        val conversation = ChatConversation(
            id = nextConversationId++,
            title = "新会话",
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        _conversations.value = (listOf(conversation) + _conversations.value)
            .sortedByDescending { it.updatedAt }
        return conversation
    }

    private fun titleOf(text: String): String {
        val oneLine = text.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "新会话" }
        return if (oneLine.length > 24) oneLine.take(24) + "…" else oneLine
    }
}

/**
 * 聊天请求控制器。
 *
 * 请求跑在 AppGraph.appScope 上，切去别的页面也会继续完成；
 * 同一时间只允许一个请求，避免消息顺序错乱。
 */
internal object ChatController {

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var activeJob: Job? = null

    fun send(text: String, quote: ChatMessage? = null) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _sending.value || !ChatStore.ready.value) return

        val conversationId = ChatStore.ensureActive()
        ChatStore.addMessage(
            conversationId = conversationId,
            role = ChatRole.User,
            content = prompt,
            quote = quote?.let {
                MessageQuote(
                    messageId = it.id,
                    role = it.role,
                    content = it.content,
                )
            },
        )
        launchReply(conversationId)
    }

    /** 重新生成最后一条 assistant 回答：删掉它，再用同一段历史请求一次。 */
    fun regenerate(messageId: Long) {
        if (_sending.value) return
        val conversationId = ChatStore.activeId.value ?: return
        val messages = ChatStore.messagesOf(conversationId)
        val message = messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatRole.Assistant || message.pending) return
        if (messages.lastOrNull()?.id != messageId) return
        ChatStore.removeFrom(conversationId, messageId)
        launchReply(conversationId)
    }

    private fun launchReply(conversationId: Long) {
        val replyId = ChatStore.addMessage(
            conversationId = conversationId,
            role = ChatRole.Assistant,
            content = "",
            pending = true,
        )
        _sending.value = true

        activeJob = AppGraph.appScope.launch {
            try {
                val reply = requestReply(conversationId)
                ChatStore.updateMessage(
                    conversationId = conversationId,
                    messageId = replyId,
                    content = stripContextAnnotations(reply.result.text?.trim().orEmpty())
                        .ifBlank { "（模型没有返回文本）" },
                    pending = false,
                    error = false,
                    reasoning = reply.result.reasoning?.trim()?.takeIf { it.isNotBlank() },
                    quote = reply.quote,
                )
                recordChatUsage(
                    conversationId = conversationId,
                    inputTokens = reply.inputTokens,
                    outputTokens = reply.outputTokens,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                ChatStore.updateMessage(
                    conversationId = conversationId,
                    messageId = replyId,
                    content = "请求失败：${describe(t)}",
                    pending = false,
                    error = true,
                    reasoning = null,
                )
            } finally {
                _sending.value = false
            }
        }
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        ChatStore.clearActive()
        _sending.value = false
    }

    private data class ChatReply(
        val result: LlmResult,
        val quote: MessageQuote?,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
    )

    private data class ToolExecution(val output: String, val quote: MessageQuote? = null)

    private suspend fun requestReply(conversationId: Long): ChatReply {
        val settings = SettingsStore.state.value
        val key = SettingsStore.apiKey()
        val catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(emptyList())
        val provider = catalog.firstOrNull { it.provider.id == settings.brandId }?.provider
        val baseUrl = if (settings.brandId == "local") {
            settings.localBaseUrl.ifBlank { null }
        } else {
            provider?.baseUrl?.ifBlank { null }
        }

        if (provider == null || baseUrl == null) {
            error("还没有可用的模型服务，请先去设置页配置")
        }
        val keyOptional = settings.brandId == "local" || settings.brandId == "openrouter"
        if (!keyOptional && key.isNullOrBlank()) {
            error("还没有配置 API Key，请先去设置页填写")
        }

        val client = LlmClients.create(
            providerId = provider.id,
            baseUrl = baseUrl,
            apiKey = key.orEmpty(),
            model = settings.model,
            thinking = settings.thinkingLevel,
        )
        ChatStore.updateSessionConfig(
            conversationId = conversationId,
            providerId = provider.id,
            model = settings.model,
            thinkingLevel = settings.thinkingLevel.wireValue,
        )
        val history = ChatStore.messagesOf(conversationId)
            .filter { it.content.isNotBlank() && !it.error }
            .takeLast(20)
            .map { message ->
                // 引用和评论都属于这条消息的上下文，不能丢。
                val content = buildString {
                    message.quote?.let { quote ->
                        append("> 引用")
                        append(if (quote.role == ChatRole.User) "用户" else "助手")
                        append("：")
                        append(quote.content.replace('\n', ' ').take(200))
                        append("\n\n")
                    }
                    append(message.content)
                    message.feedback?.let { feedback ->
                        append("\n\n[这条消息的评论：${feedback.emoji}]")
                    }
                }
                LlmMessage(
                    role = if (message.role == ChatRole.User) "user" else "assistant",
                    content = content,
                )
            }

        // 通过 ChatMessageService 检索本机其他会话，作为当前回答的参考上下文。
        val prompt = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val related = if (prompt.isBlank()) {
            emptyList()
        } else {
            runCatching {
                AppGraph.history.chatMessages.search(prompt, limit = 4)
                    .filterNot { it.conversationId == conversationId }
            }.getOrDefault(emptyList())
        }
        val memory = if (related.isEmpty()) {
            ""
        } else {
            buildString {
                appendLine()
                appendLine("本机其他历史对话片段（供参考，可能不完整）：")
                related.forEach { hit ->
                    val who = if (hit.role == "user") "用户" else "助手"
                    appendLine("[${hit.conversationTitle}] $who：${hit.content.take(300)}")
                }
            }
        }

        val serviceCatalog = runCatching { chatServiceCatalog() }.getOrDefault("")
        val system = buildString {
            append(SYSTEM_PROMPT)
            if (serviceCatalog.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("可用手机服务（service.method；聊天里禁止操作屏幕）：")
                append(serviceCatalog.take(2_500))
            }
            appendLine()
            appendLine("需要手机信息时调用 lyra_service；不要编造服务结果。")
            appendLine("photo.draw 支持 path.commands（moveTo/lineTo/quadTo/cubicTo/close）和 bezier(start/control1/control2/end)。")
            appendLine("photo.draw 返回的 markdown 是 ```canvas 节点，直接原样输出；不要改成图片链接。")
            appendLine("3D/WebGL 用 ```html 节点，内容在 sandbox iframe 内运行，不能访问聊天页 DOM。")
            appendLine("聊天里不能点击、滑动、输入、启动应用、发短信或弹窗；这些服务不会出现在列表里。")
            appendLine("调用 chat_message.quote 时，引用卡片会自动附加到回复顶部；不要在正文重复引用内容或元数据。")
        }

        // 条件压缩与剪枝：小会话原样发送，长会话折叠较早部分（P-2 显式丢弃 + 摘要承诺）
        val condensed = com.eloykim.lyra.attention.ChatCondenser.condense(
            turns = history.mapIndexed { index, message ->
                com.eloykim.lyra.attention.ChatTurn(index.toLong(), message.role, message.content)
            },
            keepRecent = com.eloykim.lyra.chat.ChatContextPolicy.KEEP_RECENT,
            maxTurns = com.eloykim.lyra.chat.ChatContextPolicy.MAX_TURNS,
            maxChars = com.eloykim.lyra.chat.ChatContextPolicy.MAX_CHARS,
        )
        val historyForModel: List<LlmMessage> = if (condensed.condensed) {
            buildList {
                condensed.summary?.let { summary ->
                    add(LlmMessage("system", "更早的对话（已压缩）：$summary"))
                }
                addAll(condensed.turns.map { LlmMessage(it.role, it.text) })
            }
        } else {
            history
        }

        val messages = ArrayList<LlmMessage>(historyForModel.size + 8)
        messages += LlmMessage("system", system + memory)
        messages += historyForModel

        var last: LlmResult? = null
        var replyQuote: MessageQuote? = null
        var inputTokens = 0
        var outputTokens = 0
        repeat(3) {
            val result = client.complete(messages, listOf(SERVICE_TOOL))
            last = result
            inputTokens += result.usage.inputTokens
            outputTokens += result.usage.outputTokens
            if (result.toolCalls.isEmpty()) {
                return ChatReply(result, replyQuote, inputTokens, outputTokens)
            }

            result.toolCalls.take(3).forEach { call ->
                val execution = executeServiceCall(call)
                execution.quote?.let { replyQuote = it }
                messages += LlmMessage(
                    "assistant",
                    "工具调用：${call.name}(${call.argumentsJson})",
                )
                messages += LlmMessage(
                    "user",
                    "工具结果：\n${execution.output}\n\n请基于工具结果继续回答用户，不要重复调用同一个工具。",
                )
            }
        }

        val final = last ?: error("模型没有返回结果")
        return ChatReply(
            result = final.copy(
                text = final.text?.takeIf { it.isNotBlank() }
                    ?: "（服务调用次数已达上限，请补充信息后重试）",
                toolCalls = emptyList(),
            ),
            quote = replyQuote,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
        )
    }

    /** 聊天里的通用手机服务调用：service.method + JSON 参数。 */
    private suspend fun executeServiceCall(call: LlmToolCall): ToolExecution {
        if (call.name != SERVICE_TOOL.name) {
            return ToolExecution("未知工具：${call.name}")
        }
        val root = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
            ?: return ToolExecution("工具参数不是合法 JSON")
        var service = root.optString("service").trim()
        var method = root.optString("method").trim()
        if (method.isBlank() && '.' in service) {
            method = service.substringAfterLast('.')
            service = service.substringBeforeLast('.')
        }
        if (service.isBlank() || method.isBlank()) {
            return ToolExecution("缺少 service 或 method")
        }
        val args = when (val raw = root.opt("args")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
            else -> JSONObject()
        }
        val command = AppGraph.commands.find("$service.$method")
            ?: return ToolExecution("没有服务命令：$service.$method")
        if (!isChatCommandAllowed(command)) {
            return ToolExecution("聊天中不能调用与 UI 操作相关的服务：${command.id}")
        }
        val result = runCatching {
            AppGraph.commands.run(command.id, args)
        }.getOrElse {
            ServiceResult.fail("tool_error", it.message ?: "服务调用失败")
        }

        if (command.id == "chat_message.quote" && result.ok) {
            val data = result.data
            val messageId = data.optLong("messageId")
            val content = data.optString("content")
            if (messageId > 0 && content.isNotBlank()) {
                return ToolExecution(
                    output = "引用已附加到这条回复的顶部（消息 #$messageId）。" +
                        "不要在正文里重复引用块、消息 id、会话 id、角色或 JSON 字段，直接回答用户。",
                    quote = MessageQuote(
                        messageId = messageId,
                        role = if (data.optString("role") == "user") {
                            ChatRole.User
                        } else {
                            ChatRole.Assistant
                        },
                        content = content,
                    ),
                )
            }
        }
        return ToolExecution(ServiceText.format(result).take(4_000))
    }

    /** 聊天窗口只允许无 UI 操作的服务；屏幕自动化、点击、输入、启动、弹窗全部排除。 */
    private fun isChatCommandAllowed(command: ServiceCommand): Boolean {
        if (command.serviceId in CHAT_UI_BLOCKED_SERVICES) return false
        // 媒体键控制不是屏幕 UI 操作，允许在聊天里播放/暂停/调音量。
        if (command.serviceId == "media") return true
        if (command.id == "address.scan") return false
        if (command.risk == ServiceRisk.CONTROL || command.risk == ServiceRisk.COMMUNICATION) {
            return false
        }
        if (command.risk == ServiceRisk.INPUT &&
            command.serviceId !in setOf(
                "chat_message",
                "chat_session",
                "address",
                "file",
                "photo",
                "grant",
            )
        ) {
            return false
        }
        return true
    }

    private fun chatServiceCatalog(): String =
        AppGraph.commands.commands
            .filter(::isChatCommandAllowed)
            .groupBy { it.serviceId }
            .entries
            .joinToString("\n") { (service, commands) ->
                "$service: " + commands.joinToString(", ") { command ->
                    command.method + command.parameterHint()
                }
            }

    /** 注释只给模型看；模型偶尔会把它抄进回答，落库前清掉。 */
    private fun stripContextAnnotations(text: String): String =
        text.replace(Regex("""\s*\[这条消息的评论：[^\]]*]\s*"""), "\n").trim()

    private suspend fun recordChatUsage(
        conversationId: Long,
        inputTokens: Int,
        outputTokens: Int,
    ) {
        if (inputTokens <= 0 && outputTokens <= 0) return
        val settings = SettingsStore.state.value
        val prices = runCatching {
            AppGraph.history.modelPrices(settings.brandId, settings.model)
        }.getOrNull()
        val cost = if (prices != null) {
            (inputTokens * prices.first + outputTokens * prices.second).toLong()
        } else {
            0L
        }
        runCatching {
            AppGraph.history.chatUsage.record(
                conversationId = conversationId,
                providerId = settings.brandId,
                model = settings.model,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costMicros = cost,
            )
        }
        ChatStore.recordUsage(
            conversationId = conversationId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costMicros = cost,
        )
    }

    private val SERVICE_TOOL = LlmTool(
        name = "lyra_service",
        description = "调用 Lyra 的手机服务（不能操作 UI）。service 是服务 id，method 是方法名，args 是 JSON 参数对象。可用命令见 system prompt。",
        parametersJson = """
            {"type":"object","properties":{
              "service":{"type":"string","description":"服务 id，例如 application / chat_message / address"},
              "method":{"type":"string","description":"方法名，例如 list / search / quote"},
              "args":{"type":"object","description":"参数对象，例如 {\"limit\":5}"}
            },"required":["service","method"]}
        """.trimIndent(),
    )

    /** 聊天里绝对不能触碰 UI 的服务；屏幕读取/截图也一并排除。 */
    private val CHAT_UI_BLOCKED_SERVICES = setOf(
        "touch",
        "swipe",
        "text",
        "window",
        "intent",
        "dialog",
        "snackbar",
        "short_message",
        "alarm",
        "task",
        "mission",
        "screen",
        "screenshot",
        "voice",
    )

    private fun describe(t: Throwable): String = when (t) {
        is LlmHttpException -> t.friendly()
        else -> t.message ?: t::class.java.simpleName
    }

    private val SYSTEM_PROMPT = """
        你是 Lyra，一个运行在 Android 手机上的助手。请使用用户使用的语言回答。
        需要使用结构化内容时使用标准 Markdown：标题、列表、引用、表格、行内代码和代码块。
        不要输出原始 HTML。
        消息末尾的「[这条消息的评论：emoji]」是用户或助手对这条消息的反应，不是消息正文。
        只回答用户询问的内容；不要声称已经操作了手机或读取了屏幕，除非用户明确要求。
    """.trimIndent()
}
