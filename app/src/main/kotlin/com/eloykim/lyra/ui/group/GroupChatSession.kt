package com.eloykim.lyra.ui.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.data.GroupAgentRecord
import com.eloykim.lyra.data.GroupMessageRecord
import com.eloykim.lyra.data.GroupRoleRecord
import com.eloykim.lyra.data.GroupSessionRecord
import com.eloykim.lyra.network.LlmClients
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 群聊页一次渲染所需的全部数据。 */
data class GroupChatSnapshot(
    val session: GroupSessionRecord,
    val agents: List<GroupAgentRecord>,
    val roles: List<GroupRoleRecord>,
    val messages: List<GroupMessageRecord>,
)

/**
 * 群聊会话：接力发言、预算切片、流式输出。
 *
 * 顺序：产品部长 → 产品部职员 → 工程部长 → 工程师 → 运营 → 职能；
 * 每位成员开口前检查自己的额度，说完再由下一位接手。
 */
class GroupChatSession(private val sessionId: Long) {

    private val store get() = AppGraph.history.groupChat

    suspend fun snapshot(): GroupChatSnapshot {
        val session = store.session(sessionId) ?: store.ensureDefaultSession()
        return GroupChatSnapshot(
            session = session,
            agents = store.agents(sessionId),
            roles = store.roles(),
            messages = store.messages(sessionId, 80),
        )
    }

    suspend fun send(
        text: String,
        agents: List<GroupAgentRecord>,
        roles: List<GroupRoleRecord>,
        quote: GroupMessageRecord? = null,
        onTyping: (Long?) -> Unit = {},
        onDelta: (Long, String, String?) -> Unit = { _, _, _ -> },
        onReply: (Long, String, String?) -> Unit = { _, _, _ -> },
        onUserMessage: () -> Unit = {},
        userMessageStored: Boolean = false,
    ): Int {
        val content = text.trim()
        if (content.isEmpty() || agents.isEmpty()) return 0

        val mentioned = agents.filter { agent ->
            content.contains("@${agent.nickname}") ||
                content.contains("@${positionTitleOf(agent, roles)}")
        }

        if (!userMessageStored) {
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
            onUserMessage()
        }

        val relay = relayOrder(agents, roles)
        val queue = ArrayDeque<Pair<GroupAgentRecord, Int>>()
        if (mentioned.isNotEmpty()) {
            mentioned.forEach { queue.add(it to 0) }
        } else {
            relay.take(1).forEach { queue.add(it to 0) }
            relay.drop(1).take(5).forEach { queue.add(it to 0) }
            relay.firstOrNull { agent ->
                agent.positionKey == "PRODUCT_MINISTER" ||
                    roles.firstOrNull { it.id == agent.roleId }?.name?.contains("产品部长") == true
            }?.let { queue.add(it to 1) }
        }

        val groupSession = store.session(sessionId)
        val replyPolicy = groupSession?.replyPolicy.orEmpty()
        val budget = groupSession?.tokenBudget ?: 0L
        val key = SettingsStore.apiKey().orEmpty()
        val catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(emptyList())
        val replied = mutableSetOf<Long>()
        var replies = 0

        while (queue.isNotEmpty() && replies < 6) {
            val (agent, depth) = queue.removeFirst()
            if (depth == 0 && !replied.add(agent.id)) continue

            val members = agents.map { a ->
                com.eloykim.lyra.attention.GroupMember(
                    id = a.id,
                    name = a.positionKey ?: a.nickname,
                    weight = if (a.positionKey?.endsWith("_MINISTER") == true) 0.6 else 0.4,
                    spentTokens = a.tokensUsed,
                )
            }
            val quota = com.eloykim.lyra.attention.GroupAttention.memberShare(
                totalTokens = budget,
                memberId = agent.id,
                members = members,
                quota = agent.quotaTokens,
                isMinister = agent.positionKey?.endsWith("_MINISTER") == true,
            )
            if (budget > 0 && quota > 0 && agent.tokensUsed >= quota) {
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = null,
                    authorName = "系统",
                    content = "（${positionTitleOf(agent, roles)}的预算份额已用完，本轮跳过；请财务部长调整额度）",
                    mentions = "财务部长",
                )
                continue
            }

            val role = roles.firstOrNull { it.id == agent.roleId }
            if (role == null) {
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = agent.id,
                    authorName = positionTitleOf(agent, roles),
                    content = "（还没有分配角色，请人力部长 assign_role）",
                    mentions = "",
                )
                continue
            }

            val baseUrl = if (role.brandId == "local") {
                SettingsStore.state.value.localBaseUrl.ifBlank { null }
            } else {
                catalog.firstOrNull { it.provider.id == role.brandId }
                    ?.provider?.baseUrl?.ifBlank { null }
            }
            if (baseUrl == null) {
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = agent.id,
                    authorName = positionTitleOf(agent, roles),
                    content = "（模型服务 ${role.brandId} 不可用）",
                    mentions = "",
                )
                continue
            }

            val thinking = com.eloykim.lyra.network.ThinkingLevel.entries
                .firstOrNull { it.wireValue == role.thinkingLevel }
                ?: com.eloykim.lyra.network.ThinkingLevel.Medium
            val client = LlmClients.create(
                providerId = role.brandId,
                baseUrl = baseUrl,
                apiKey = key,
                model = role.model,
                thinking = thinking,
            )

            val history = store.messages(sessionId, 24).map { message ->
                LlmMessage(
                    role = if (message.role == "user") "user" else "assistant",
                    content = message.content,
                )
            }

            val system = buildString {
                append("你是「${positionTitleOf(agent, roles)}」。")
                append("回复不要以职位名或「xx部长：」开头——系统会自动标注发言人。")
                append("本轮是接力发言：轮到你时必须发言，没轮到你时不要插话。")
                if (role.duty.isNotBlank()) append("职责：${role.duty}。")
                if (replyPolicy.isNotBlank()) append("回复策略：$replyPolicy。")
                append("说出可执行事项时先说明，不要只在文字里承诺。")
            }

            onTyping(agent.id)
            var deltaCount = 0
            val streamed = StringBuilder()
            val reasoning = StringBuilder()
            var reply = ""
            runCatching {
                client.stream(
                    listOf(LlmMessage("system", system)) + history,
                    emptyList(),
                ) { piece, think ->
                    if (think != null) reasoning.append(think)
                    if (piece.isNotEmpty()) {
                        streamed.append(piece)
                        deltaCount += 1
                        onDelta(agent.id, piece, think)
                    }
                }
            }.onFailure {
                val fallback = runCatching {
                    client.complete(listOf(LlmMessage("system", system)) + history)
                }.getOrNull()
                reply = fallback?.text.orEmpty()
                fallback?.reasoning?.let { reasoning.append(it) }
            }
            onTyping(null)
            android.util.Log.d("GroupDelta", "agent=${agent.id} deltas=$deltaCount chars=${streamed.length}")
            if (reply.isBlank()) reply = streamed.toString()
            reply = stripTitlePrefixes(reply).trim()
            if (reply.isBlank()) continue

            store.addMessage(
                sessionId = sessionId,
                role = "assistant",
                authorId = agent.id,
                authorName = positionTitleOf(agent, roles),
                content = reply,
                mentions = "",
                reasoning = reasoning.toString().takeIf { it.isNotBlank() },
            )
            onReply(agent.id, reply, reasoning.toString().takeIf { it.isNotBlank() })
            replies += 1
        }
        return replies
    }
}

object GroupChatController {
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    suspend fun send(
        session: GroupChatSession,
        text: String,
        agents: List<GroupAgentRecord>,
        roles: List<GroupRoleRecord>,
        quote: GroupMessageRecord? = null,
        onTyping: (Long?) -> Unit = {},
        onDelta: (Long, String, String?) -> Unit = { _, _, _ -> },
        onReply: (Long, String, String?) -> Unit = { _, _, _ -> },
        onUserMessage: () -> Unit = {},
        userMessageStored: Boolean = false,
    ): Int {
        if (_sending.value) return 0
        _sending.value = true
        return try {
            session.send(text, agents, roles, quote, onTyping, onDelta, onReply, onUserMessage, userMessageStored)
        } finally {
            _sending.value = false
        }
    }
}

/** 接力顺序：按职位把成员排成一条链。 */
private fun relayOrder(
    agents: List<GroupAgentRecord>,
    roles: List<GroupRoleRecord>,
): List<GroupAgentRecord> {
    fun rank(agent: GroupAgentRecord): Int = when (agent.positionKey) {
        "PRODUCT_MINISTER" -> 0
        "PRODUCT", "DESIGNER", "ANALYST", "RESEARCH" -> 1
        "ENGINEERING_MINISTER", "TECH_LEAD" -> 2
        "BACKEND", "FRONTEND", "MOBILE", "QA", "OPS" -> 3
        "OPERATIONS", "MARKETING", "SALES", "SUPPORT", "WRITER" -> 4
        "FINANCE", "LEGAL", "HR", "ADMIN", "PROCUREMENT", "RESOURCE" -> 5
        else -> if (roles.firstOrNull { it.id == agent.roleId }?.name?.contains("产品部长") == true) 0 else 6
    }
    return agents.sortedWith(compareBy({ rank(it) }, { it.id }))
}

/** 群成员对外只用职位，不显示昵称。 */
private fun positionTitleOf(
    agent: GroupAgentRecord,
    roles: List<GroupRoleRecord>,
): String {
    agent.positionKey?.let { key ->
        com.eloykim.lyra.attention.Positions.byKey(key)?.let { return it.title }
    }
    return roles.firstOrNull { it.id == agent.roleId }?.name ?: "成员"
}

/** 去掉模型自带的「职位：」前缀，最多剥三层。 */
private fun stripTitlePrefixes(reply: String): String {
    var text = reply.trimStart()
    val titles = com.eloykim.lyra.attention.Positions.defaults.map { it.title } +
        listOf("董事长", "总经理", "记录者")
    val prefix = Regex("^(" + titles.joinToString("|") { Regex.escape(it) } + ")\\s*[：:]\\s*")
    repeat(3) {
        val next = prefix.replaceFirst(text, "")
        if (next != text) text = next
    }
    return text
}
