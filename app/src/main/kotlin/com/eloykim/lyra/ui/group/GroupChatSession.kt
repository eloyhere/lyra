package com.eloykim.lyra.ui.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.data.GroupAgentRecord
import com.eloykim.lyra.data.GroupMessageRecord
import com.eloykim.lyra.data.GroupRoleRecord
import com.eloykim.lyra.data.GroupSessionRecord
import com.eloykim.lyra.network.LlmClients
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.network.LlmTool
import com.eloykim.lyra.network.LlmToolCall
import com.eloykim.lyra.network.ThinkingLevel
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** 群聊一屏所需的不可变快照。 */
data class GroupChatSnapshot(
    val session: GroupSessionRecord,
    val agents: List<GroupAgentRecord>,
    val roles: List<GroupRoleRecord>,
    val messages: List<GroupMessageRecord>,
)

/**
 * GroupChatSession：一个群聊会话的读写与回复调度。
 *
 * 规则：只有被 @ 的 Agent 回复；Agent 不调用 lyra_service，只做对话。
 */
class GroupChatSession(private val sessionId: Long) {

    suspend fun snapshot(): GroupChatSnapshot {
        val store = AppGraph.history.groupChat
        return GroupChatSnapshot(
            session = store.session(sessionId) ?: store.ensureDefaultSession(),
            agents = store.agents(sessionId),
            roles = store.roles(),
            messages = store.messages(sessionId, 200),
        )
    }

    /** 发送用户消息并让被 @ 的成员依次回复；返回实际回复的成员数。 */
    suspend fun send(
        text: String,
        agents: List<GroupAgentRecord>,
        roles: List<GroupRoleRecord>,
        quote: GroupMessageRecord? = null,
    ): Int {
        val content = text.trim()
        if (content.isEmpty()) return 0
        val store = AppGraph.history.groupChat
        val mentioned = agents.filter { agent ->
            content.contains("@${agent.nickname}")
        }
        store.addMessage(
            sessionId = sessionId,
            role = "user",
            authorId = null,
            authorName = "你",
            content = content,
            mentions = mentioned.joinToString(",") { it.nickname },
            quotedId = quote?.id,
            quotedAuthor = quote?.authorName,
            quotedContent = quote?.content,
        )
        val currentSession = store.session(sessionId)
        if (currentSession != null && isAutoTitle(currentSession.title)) {
            val title = titleFrom(content)
            if (title.isNotBlank() && title != currentSession.title) {
                store.renameSession(sessionId, title)
            }
        }
        if (mentioned.isEmpty()) return 0

        val catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(emptyList())
        val key = SettingsStore.apiKey().orEmpty()
        val groupSession = store.session(sessionId)
        val replyPolicy = groupSession?.replyPolicy.orEmpty()
        var replies = 0
        val replied = mutableSetOf<Long>()
        val queue = ArrayDeque<Pair<GroupAgentRecord, Int>>()
        mentioned.forEach { queue.add(it to 0) }
        while (queue.isNotEmpty() && replies < 8) {
            val (agent, depth) = queue.removeFirst()
            if (!replied.add(agent.id)) continue
            val budgetState = store.session(sessionId)
            if (budgetState != null && budgetState.tokenBudget > 0 &&
                budgetState.tokensUsed >= budgetState.tokenBudget
            ) {
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = null,
                    authorName = "系统",
                    content = "（群聊 token 预算已用完，请让财务调整预算）",
                    mentions = "",
                )
                break
            }
            val role = roles.firstOrNull { it.id == agent.roleId }
            if (role == null) {
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = agent.id,
                    authorName = agent.nickname,
                    content = "（还没有分配角色，点我的头像设置）",
                    mentions = "",
                )
                continue
            }
            val provider = catalog.firstOrNull { it.provider.id == role.brandId }?.provider
            val baseUrl = if (role.brandId == "local") {
                SettingsStore.state.value.localBaseUrl.ifBlank { null }
            } else {
                provider?.baseUrl?.ifBlank { null }
            }
            if (baseUrl == null) {
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = agent.id,
                    authorName = agent.nickname,
                    content = "（模型服务 ${role.brandId} 不可用）",
                    mentions = "",
                )
                continue
            }
            val prices = runCatching {
                AppGraph.history.modelPrices(role.brandId, role.model)
            }.getOrNull()
            val thinking = ThinkingLevel.entries
                .firstOrNull { it.wireValue == role.thinkingLevel }
                ?: ThinkingLevel.Medium
            val client = LlmClients.create(
                providerId = role.brandId,
                baseUrl = baseUrl,
                apiKey = key,
                model = role.model,
                thinking = thinking,
            )
            val stateForContext = store.session(sessionId)
            val aggressive = stateForContext != null &&
                stateForContext.tokenBudget > 0 &&
                stateForContext.tokensUsed.toDouble() / stateForContext.tokenBudget > 0.7
            val history = GroupContextCompressor.build(
                messages = store.messages(sessionId, 80),
                aggressive = aggressive,
            )
            val messages = ArrayList<LlmMessage>()
            messages += LlmMessage(
                role = "system",
                content = buildString {
                    append("你是群聊里的成员「${agent.nickname}」。")
                    append("角色：${role.name}。")
                    if (role.duty.isNotBlank()) append("职责：${role.duty}。")
                    append("只回复明确 @ 你的消息；可以 @用户或其他成员（@昵称）。")
                    append("需要手机信息时可以调用 lyra_service；所有成员共用一把服务锁，")
                    append("同一时间只允许一个 Agent 调用服务。不要操作 UI。")
                    append("角色管理命令（新增/修改/删除角色）只有「人力资源部」角色可以调用。")
                    append("用户明确要求清空群聊历史时，调用 group_chat.clear_history。")
                    if (replyPolicy.isNotBlank()) {
                        append("当前群聊回复策略（由财务/协调者制定）：")
                        append(replyPolicy)
                    }
                },
            )
            messages += history

            var reply = ""
            for (round in 0 until 3) {
                val result = runCatching {
                    client.complete(messages, listOf(SERVICE_TOOL))
                }.getOrNull() ?: break
                val inputTokens = result.usage.inputTokens
                val outputTokens = result.usage.outputTokens
                val costMicros = if (prices != null) {
                    (inputTokens * prices.first + outputTokens * prices.second).toLong()
                } else {
                    0L
                }
                store.addSessionTokens(sessionId, (inputTokens + outputTokens).toLong())
                store.recordUsage(
                    sessionId = sessionId,
                    agentId = agent.id,
                    providerId = role.brandId,
                    model = role.model,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    costMicros = costMicros,
                )
                if (result.toolCalls.isEmpty()) {
                    reply = result.text?.trim().orEmpty()
                    break
                }
                result.toolCalls.take(2).forEach { call ->
                    val output = serviceMutex.withLock { executeServiceCall(call, role) }
                    messages += LlmMessage(
                        "assistant",
                        "工具调用：${call.name}(${call.argumentsJson})",
                    )
                    messages += LlmMessage(
                        "user",
                        "工具结果：\n$output\n\n请基于结果继续回答，不要重复调用同一个工具。",
                    )
                }
            }
            if (reply.isBlank()) reply = "（没有生成回复）"
            val mentionNames = Regex("@[\\p{L}\\p{N}_-]+")
                .findAll(reply)
                .map { it.value.removePrefix("@") }
                .toSet()
            store.addMessage(
                sessionId = sessionId,
                role = "assistant",
                authorId = agent.id,
                authorName = agent.nickname,
                content = reply,
                mentions = mentionNames.joinToString(","),
            )
            replies += 1
            // Agent 可以 @ 另一个 Agent，但最多再连续回复 1 轮。
            if (depth == 0) {
                agents.filter { other ->
                    other.id != agent.id &&
                        other.id !in replied &&
                        other.nickname in mentionNames
                }.forEach { other -> queue.add(other to 1) }
            }
        }
        return replies
    }

    private fun isAutoTitle(title: String): Boolean =
        title.isBlank() ||
            title == "群聊" ||
            Regex("^群聊 \\d+$").matches(title.trim())

    /** 从第一条有效内容生成简短、可读的群名。 */
    private fun titleFrom(text: String): String {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith(">") }
            .orEmpty()
        val cleaned = firstLine
            .replace(Regex("@[\\p{L}\\p{N}_-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('，', '。', '！', '？', ',', '.', '!', '?', '：', ':', '；', ';', ' ')
        if (cleaned.isBlank()) return "群聊"
        return if (cleaned.length > 16) cleaned.take(16) + "…" else cleaned
    }

    private suspend fun executeServiceCall(
        call: LlmToolCall,
        callerRole: GroupRoleRecord,
    ): String {
        if (call.name != SERVICE_TOOL.name) return "未知工具：${call.name}"
        val root = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
            ?: return "工具参数不是合法 JSON"
        var service = root.optString("service").trim()
        var method = root.optString("method").trim()
        if (method.isBlank() && '.' in service) {
            method = service.substringAfterLast('.')
            service = service.substringBeforeLast('.')
        }
        if (service.isBlank() || method.isBlank()) return "缺少 service 或 method"
        val args = when (val raw = root.opt("args")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
            else -> JSONObject()
        }
        val command = AppGraph.commands.find("$service.$method")
            ?: return "没有服务命令：$service.$method"
        if (command.serviceId == "group_chat") {
            val method = command.id.substringAfterLast('.')
            if (method in ROLE_MANAGE_COMMANDS && !callerRole.canManageRoles) {
                return "只有「人力资源部」角色可以管理角色"
            }
            if (method == "set_budget" && !callerRole.canManageBudget) {
                return "只有「财务」角色可以管理 token 预算"
            }
            if (method == "set_reply_policy" &&
                !(callerRole.canManageBudget || callerRole.canCoordinateReplies)
            ) {
                return "只有「财务」或协调角色可以制定回复策略"
            }
        }
        if (command.risk == ServiceRisk.CONTROL || command.risk == ServiceRisk.COMMUNICATION) {
            return "群聊 Agent 不能调用控制/通信类服务：${command.id}"
        }
        if (command.risk == ServiceRisk.INPUT &&
            command.serviceId !in setOf("chat_message", "chat_session", "group_chat", "address", "file", "photo")
        ) {
            return "群聊 Agent 不能调用该输入类服务：${command.id}"
        }
        val result = runCatching {
            AppGraph.commands.run(command.id, args)
        }.getOrElse {
            return "服务调用失败：${it.message ?: it::class.java.simpleName}"
        }
        return buildString {
            append(result.message)
            if (result.data.length() > 0) {
                append('\n')
                append(result.data)
            }
        }
    }

    private companion object {
        /** 群聊所有成员共用：同一时间只允许一个 Agent 执行服务。 */
        val serviceMutex = Mutex()

        val ROLE_MANAGE_COMMANDS = setOf(
            "create_role",
            "update_role",
            "delete_role",
            "assign_role",
        )

        val SERVICE_TOOL = LlmTool(
            name = "lyra_service",
            description = "调用 Lyra 手机服务；service 是服务 id，method 是方法名，args 是 JSON 参数。",
            parametersJson = """
                {"type":"object","properties":{
                  "service":{"type":"string"},
                  "method":{"type":"string"},
                  "args":{"type":"object"}
                },"required":["service","method"]}
            """.trimIndent(),
        )
    }
}

/**
 * 群聊上下文压缩/剪枝：
 * - 未超阈值：保留完整最近消息。
 * - 超字符/条数阈值：老消息压缩成摘要，最近消息保留原文。
 * - 预算使用超过 70% 时进入 aggressive 模式，阈值更低、保留更少。
 */
private object GroupContextCompressor {
    fun build(messages: List<GroupMessageRecord>, aggressive: Boolean): List<LlmMessage> {
        val items = messages.filter { it.content.isNotBlank() }
        if (items.isEmpty()) return emptyList()
        val maxChars = if (aggressive) 8_000 else 20_000
        val keep = if (aggressive) 8 else 16
        val totalChars = items.sumOf { line(it).length }
        if (items.size <= 30 && totalChars <= maxChars) {
            return items.map(::toLlm)
        }

        val recent = items.takeLast(keep)
        val older = items.dropLast(keep)
        val summary = buildString {
            append("历史压缩摘要（更早的群聊已剪枝）：\n")
            older.takeLast(40).forEach { message ->
                append("- ")
                append(line(message).take(140))
                append('\n')
                if (length > 3_500) return@buildString
            }
        }.trim()
        return buildList {
            if (summary.isNotBlank()) add(LlmMessage("system", summary))
            recent.forEach { add(toLlm(it)) }
        }
    }

    private fun line(message: GroupMessageRecord): String {
        val quote = message.quotedContent?.let {
            "（引用 ${message.quotedAuthor ?: "消息"}：${it.replace('\n', ' ').take(100)}）"
        }.orEmpty()
        return "${message.authorName}：$quote${message.content.replace('\n', ' ')}"
    }

    private fun toLlm(message: GroupMessageRecord): LlmMessage = LlmMessage(
        role = if (message.role == "user") "user" else "assistant",
        content = line(message),
    )
}

/** 群聊发送状态；同一时间只允许一个群聊请求。 */
object GroupChatController {
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    suspend fun send(
        session: GroupChatSession,
        text: String,
        agents: List<GroupAgentRecord>,
        roles: List<GroupRoleRecord>,
        quote: GroupMessageRecord? = null,
    ): Int {
        if (_sending.value) return 0
        _sending.value = true
        return try {
            session.send(text, agents, roles, quote)
        } finally {
            _sending.value = false
        }
    }
}
