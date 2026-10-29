package com.eloykim.lyra.ui.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.attention.Positions
import com.eloykim.lyra.data.GroupAgentRecord
import com.eloykim.lyra.data.GroupMessageRecord
import com.eloykim.lyra.data.GroupRoleRecord
import com.eloykim.lyra.data.GroupSessionRecord
import com.eloykim.lyra.data.GroupTaskRecord
import com.eloykim.lyra.data.StaffingRecord
import com.eloykim.lyra.group.GroupActions
import com.eloykim.lyra.network.LlmClients
import com.eloykim.lyra.network.LlmMessage
import com.eloykim.lyra.network.LlmResult
import com.eloykim.lyra.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** 群聊页一次渲染所需的全部数据。 */
data class GroupChatSnapshot(
    val session: GroupSessionRecord,
    val agents: List<GroupAgentRecord>,
    val roles: List<GroupRoleRecord>,
    val messages: List<GroupMessageRecord>,
)

/** AMP 群聊回合：董事长消息之后，职位严格串行接力。 */
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

    /** Record the chairman's input before organization reconciliation begins. */
    suspend fun recordChairmanSpeech(
        text: String,
        quote: GroupMessageRecord?,
        turnId: String,
    ): Long {
        val content = text.trim()
        val agents = store.agents(sessionId)
        val roles = store.roles()
        val mentioned = agents.filter { agent ->
            content.contains("@${agent.nickname}") ||
                content.contains("@${positionTitleOf(agent, roles)}")
        }
        val messageId = store.addMessage(
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
        appendEvent(
            actor = "chairman",
            type = "group.chairman.spoke",
            payload = JSONObject()
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("messageId", messageId)
                .put("positionKey", Positions.CHAIRMAN)
                .put("content", content)
                .toString(),
        )
        appendEvent(
            actor = "chairman",
            type = "group.turn.started",
            payload = JSONObject()
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("positionKey", Positions.CHAIRMAN)
                .put("input", content)
                .toString(),
        )
        return messageId
    }

    suspend fun send(
        text: String,
        agents: List<GroupAgentRecord>,
        roles: List<GroupRoleRecord>,
        turnId: String,
        onTyping: (Long?) -> Unit = {},
        onReply: suspend (Long, String, String?) -> Unit = { _, _, _ -> },
    ): Int {
        val content = text.trim()
        if (content.isEmpty()) return 0

        // 只让「和这件事有关」的人发言：总经理 + 相关部门的部长（最多 4 人）。
        // 以前是 8 个部长 + 总经理固定全说一遍，每轮 9 次模型调用，讨论成本高、
        // 却没有任何人能改变状态——这是"只讨论不干活"的根因之一。
        val openTasks = runCatching { store.tasks(sessionId) }.getOrDefault(emptyList())
        val openHiring = runCatching { store.openStaffing(sessionId) }.getOrDefault(emptyList())
        val relay = selectSpeakers(agents, content, openTasks, openHiring)
        if (relay.isEmpty()) {
            appendSystem("本轮没有可用的总经理或部长，已结束接力。")
            appendEvent(
                actor = "recorder",
                type = "group.turn.completed",
                payload = JSONObject().put("sessionId", sessionId).put("turnId", turnId).put("replies", 0).toString(),
            )
            return 0
        }

        val groupSession = store.session(sessionId)
        val replyPolicy = groupSession?.replyPolicy.orEmpty()
        val budget = groupSession?.tokenBudget ?: 0L
        // Group members use the same provider, model, thinking level and API key
        // chosen in Settings. A saved role only supplies its department duties.
        val settings = SettingsStore.state.value
        val key = SettingsStore.apiKey().orEmpty()
        val catalog = runCatching { AppGraph.history.catalog() }.getOrDefault(emptyList())
        val provider = catalog.firstOrNull { it.provider.id == settings.brandId }?.provider
        val baseUrl = if (settings.brandId == "local") {
            settings.localBaseUrl.ifBlank { null }
        } else {
            provider?.baseUrl?.ifBlank { null }
        }
        val keyOptional = settings.brandId == "local" || settings.brandId == "openrouter"
        var replies = 0
        var actions = 0
        val roster = buildRoster(agents, openTasks, openHiring, relay)

        relay.forEachIndexed { index, agent ->
            val positionKey = Positions.canonicalKey(agent.positionKey.orEmpty())
            val positionTitle = positionTitleOf(agent, roles)
            val position = Positions.byKey(positionKey)
            val agentTurn = "$turnId-$index"

            try {
                val members = agents.map { member ->
                    val tier = Positions.byKey(member.positionKey.orEmpty())?.tier ?: 3
                    com.eloykim.lyra.attention.GroupMember(
                        id = member.id,
                        name = Positions.canonicalKey(member.positionKey ?: member.nickname),
                        weight = if (tier <= 2) 0.6 else 0.4,
                        spentTokens = member.tokensUsed,
                    )
                }
                val quota = com.eloykim.lyra.attention.GroupAttention.memberShare(
                    totalTokens = budget,
                    memberId = agent.id,
                    members = members,
                    quota = agent.quotaTokens,
                    isMinister = (position?.tier ?: 3) <= 2,
                )
                if (budget > 0 && quota > 0 && agent.tokensUsed >= quota) {
                    throw GroupTurnSkip("预算份额已用完")
                }

                val role = roles.firstOrNull { it.id == agent.roleId }
                    ?: throw GroupTurnSkip("还没有分配模型角色")
                if (provider == null && settings.brandId != "local") {
                    throw GroupTurnSkip("设置中的模型服务 ${settings.brandId} 不可用")
                }
                if (baseUrl.isNullOrBlank()) {
                    throw GroupTurnSkip("设置中的模型服务 ${settings.brandId} 未配置接口地址")
                }
                if (!keyOptional && key.isBlank()) {
                    throw GroupTurnSkip("设置中的 ${settings.brandId} 还没有配置 API Key")
                }

                val thinking = settings.thinkingLevel
                val thinkingEnabled = thinking != com.eloykim.lyra.network.ThinkingLevel.Off
                val client = LlmClients.create(
                    providerId = settings.brandId,
                    baseUrl = baseUrl,
                    apiKey = key,
                    model = settings.model,
                    thinking = thinking,
                )
                val history = store.messages(sessionId, 24).map { message ->
                    LlmMessage(
                        role = if (message.role == "user") "user" else "assistant",
                        content = message.content,
                    )
                }
                val system = buildString {
                    append("你是「$positionTitle」。")
                    append("回复不要以职位名或「xx部长：」开头，系统会自动标注发言人。")
                    if (role.duty.isNotBlank()) append("职责：${role.duty}。")
                    if (replyPolicy.isNotBlank()) append("回复策略：$replyPolicy。")
                    append("\n本轮在场：").append(relay.joinToString("、") { positionTitleOf(it, roles) })
                    append("。你只代表自己，不要替别人表态。")
                    append("\n群状态：\n").append(roster)
                    append("\n写回复：先结论、再下一步，≤120 字；不要复述别人刚说过的话；")
                    append("事情已经定了就一句话确认，不要重新讨论。\n")
                    append(GroupActions.contract())
                }

                val streamed = StringBuilder()
                val reasoning = StringBuilder()
                var result: LlmResult? = null
                var streamFailure: Throwable? = null
                if (thinkingEnabled) {
                    appendEvent(
                        actor = agent.id.toString(),
                        type = "group.agent.thinking.started",
                        payload = JSONObject()
                            .put("sessionId", sessionId)
                            .put("turnId", turnId)
                            .put("agentTurnId", agentTurn)
                            .put("agentId", agent.id)
                            .put("positionKey", positionKey)
                            .put("order", index)
                            .put("providerId", settings.brandId)
                            .put("model", settings.model)
                            .put("thinkingLevel", thinking.wireValue)
                            .toString(),
                    )
                }
                onTyping(agent.id)
                try {
                    result = client.stream(
                        listOf(LlmMessage("system", system)) + history,
                        emptyList(),
                    ) { piece, think ->
                        if (think != null) reasoning.append(think)
                        if (piece.isNotEmpty()) {
                            streamed.append(piece)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    streamFailure = failure
                }
                // Some compatible gateways close a stream with an empty result
                // even though the request itself was accepted. Treat that the
                // same as a failed stream and make one non-streaming retry before
                // declaring this seat incomplete. This keeps the relay from
                // silently skipping a department on a transient empty stream.
                val streamWasEmpty = streamed.isBlank() && result?.text.isNullOrBlank()
                if (streamWasEmpty) {
                    result = runCatching {
                        client.complete(listOf(LlmMessage("system", system)) + history)
                    }.getOrNull()
                }
                if ((result == null || result?.text.isNullOrBlank()) && streamed.isBlank()) {
                    throw streamFailure ?: IllegalStateException("模型没有返回内容")
                }

                if (thinkingEnabled && reasoning.isBlank()) {
                    result?.reasoning?.takeIf { it.isNotBlank() }?.let(reasoning::append)
                }
                val usage = result?.usage ?: com.eloykim.lyra.network.LlmUsage()
                val totalTokens = usage.inputTokens.toLong() + usage.outputTokens.toLong()
                store.recordUsage(
                    sessionId = sessionId,
                    agentId = agent.id,
                    providerId = settings.brandId,
                    model = settings.model,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    costMicros = 0L,
                )
                store.addSessionTokens(sessionId, totalTokens)
                store.addAgentTokens(agent.id, totalTokens)

                var reply = result?.text.orEmpty().ifBlank { streamed.toString() }
                reply = stripTitlePrefixes(reply).trim()
                // 成员可以用一行 <<ACTION {...}>> 改变群状态；正文不显示这行。
                val parsed = com.eloykim.lyra.group.GroupActions.parse(reply)
                reply = parsed.text
                if (reply.isBlank() && parsed.action == null) {
                    throw IllegalStateException("模型返回空消息")
                }

                if (thinkingEnabled) {
                    appendEvent(
                        actor = agent.id.toString(),
                        type = "group.agent.thinking.completed",
                        payload = JSONObject()
                            .put("sessionId", sessionId)
                            .put("turnId", turnId)
                            .put("agentTurnId", agentTurn)
                            .put("agentId", agent.id)
                            .put("positionKey", positionKey)
                            .put("tokens", totalTokens)
                            .toString(),
                    )
                }
                // Streaming callbacks are deliberately released only after the thinking
                // boundary. This keeps the user-visible protocol strictly serial.
                appendEvent(
                    actor = agent.id.toString(),
                    type = "group.agent.speaking.started",
                    payload = JSONObject()
                        .put("sessionId", sessionId)
                        .put("turnId", turnId)
                        .put("agentTurnId", agentTurn)
                        .put("agentId", agent.id)
                        .put("positionKey", positionKey)
                        .put("order", index)
                        .toString(),
                )
                val shown = reply.ifBlank { "（${positionTitle} 执行了动作，见下方回执）" }
                store.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    authorId = agent.id,
                    authorName = positionTitle,
                    content = shown,
                    mentions = "",
                    reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                )
                appendEvent(
                    actor = agent.id.toString(),
                    type = "group.agent.reply.completed",
                    payload = JSONObject()
                        .put("sessionId", sessionId)
                        .put("turnId", turnId)
                        .put("agentTurnId", agentTurn)
                        .put("agentId", agent.id)
                        .put("positionKey", positionKey)
                        .put("order", index)
                        .put("tokens", totalTokens)
                        .toString(),
                )
                appendEvent(
                    actor = agent.id.toString(),
                    type = "group.agent.speaking.completed",
                    payload = JSONObject()
                        .put("sessionId", sessionId)
                        .put("turnId", turnId)
                        .put("agentTurnId", agentTurn)
                        .put("agentId", agent.id)
                        .put("positionKey", positionKey)
                        .put("order", index)
                        .toString(),
                )
                parsed.action?.let { action ->
                    actions++
                    val feedback = runCatching {
                        com.eloykim.lyra.group.GroupActions.execute(sessionId, agent, action)
                    }.getOrElse { "动作执行失败：${it.message}" }
                    appendSystem("（$positionTitle）$feedback")
                    appendEvent(
                        actor = agent.id.toString(),
                        type = "group.agent.action.executed",
                        payload = JSONObject()
                            .put("sessionId", sessionId)
                            .put("turnId", turnId)
                            .put("agentId", agent.id)
                            .put("positionKey", positionKey)
                            .put("action", action.optString("type"))
                            .put("result", feedback.take(300))
                            .toString(),
                    )
                }
                replies++
                onReply(agent.id, shown, reasoning.toString().takeIf { it.isNotBlank() })
                // Let Compose commit the completed message before the next
                // speaker's typing state is published.
                kotlinx.coroutines.yield()
            } catch (skip: GroupTurnSkip) {
                appendSystem("（$positionTitle：${skip.message}，本轮跳过）")
                appendFailureEvent(turnId, agentTurn, agent, positionKey, skip.message ?: "skipped", false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
            } catch (failure: Throwable) {
                appendSystem("（$positionTitle：${failure.message ?: "模型调用失败"}，本轮跳过）")
                appendFailureEvent(turnId, agentTurn, agent, positionKey, failure.message ?: failure::class.java.simpleName, true)
            } finally {
                onTyping(null)
            }
        }

        // 一轮没有任何状态变化时明说，避免"看起来讨论过了"却没有结论。
        if (replies > 0 && actions == 0) {
            appendSystem("本轮 $replies 人发言，没有任务、增员或状态变化。")
        }
        appendEvent(
            actor = "recorder",
            type = "group.turn.completed",
            payload = JSONObject()
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("replies", replies)
                .put("actions", actions)
                .put("order", relay.map { Positions.canonicalKey(it.positionKey.orEmpty()) })
                .toString(),
        )
        return replies
    }

    private fun appendFailureEvent(
        turnId: String,
        agentTurn: String,
        agent: GroupAgentRecord,
        positionKey: String,
        error: String,
        retryable: Boolean,
    ) {
        appendEvent(
            actor = agent.id.toString(),
            type = "group.agent.reply.failed",
            payload = JSONObject()
                .put("sessionId", sessionId)
                .put("turnId", turnId)
                .put("agentTurnId", agentTurn)
                .put("agentId", agent.id)
                .put("positionKey", positionKey)
                .put("error", error)
                .put("retryable", retryable)
                .toString(),
        )
    }

    private suspend fun appendSystem(content: String) {
        store.addMessage(
            sessionId = sessionId,
            role = "assistant",
            authorId = null,
            authorName = "系统",
            content = content,
            mentions = "",
        )
    }

    private fun appendEvent(actor: String, type: String, payload: String) {
        AppGraph.history.ledger.append(actor = actor, type = type, payload = payload)
    }
}

private class GroupTurnSkip(message: String) : IllegalStateException(message)

object GroupChatController {
    private val _sending = MutableStateFlow(false)
    private val turnActive = AtomicBoolean(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    suspend fun runTurn(block: suspend () -> Int): Int {
        if (!turnActive.compareAndSet(false, true)) return 0
        _sending.value = true
        return try {
            block()
        } finally {
            _sending.value = false
            turnActive.set(false)
        }
    }
}

private fun positionTitleOf(agent: GroupAgentRecord, roles: List<GroupRoleRecord>): String {
    agent.positionKey?.let { key -> Positions.byKey(key)?.let { return it.title } }
    return roles.firstOrNull { it.id == agent.roleId }?.name ?: "成员"
}

/** 部门 → 话题关键词：用来决定这一轮该谁开口。 */
private val departmentKeywords = mapOf(
    "工程" to listOf("前端", "后端", "客户端", "代码", "接口", "实现", "技术", "测试", "运维", "bug", "开发"),
    "产品" to listOf("需求", "原型", "设计", "体验", "功能", "流程"),
    "运营" to listOf("运营", "推广", "渠道", "活动", "文案", "销售", "客服", "增长"),
    "法务" to listOf("合规", "合同", "隐私", "条款", "风险", "授权"),
    "人力" to listOf("招聘", "入职", "编制", "人力", "绩效"),
    "财务" to listOf("预算", "成本", "费用", "钱", "额度", "token 预算"),
    "资源" to listOf("资源", "采购", "算力", "服务器", "供应商"),
    "职能" to listOf("行政", "流程", "制度"),
)

/**
 * 本轮发言者：总经理 + 相关部门部长（最多 2 个），总共不超过 4 人。
 *
 * 判断"相关"的三个来源：董事长这句话里的关键词、任务板上未完成任务的负责人部门、
 * 待批增员申请所属部门。都没有时退回「产品 + 工程」——需求与实现是默认主线。
 */
private fun selectSpeakers(
    agents: List<GroupAgentRecord>,
    text: String,
    tasks: List<GroupTaskRecord>,
    hiring: List<StaffingRecord>,
): List<GroupAgentRecord> {
    val ordered = Positions.relay.mapNotNull { position ->
        agents.firstOrNull { Positions.canonicalKey(it.positionKey.orEmpty()) == position.key }
    }
    if (ordered.isEmpty()) return emptyList()

    val focus = LinkedHashSet<String>()
    departmentKeywords.forEach { (department, words) ->
        if (words.any { keyword -> text.contains(keyword, ignoreCase = true) }) focus += department
    }
    tasks.filter { com.eloykim.lyra.attention.TaskFlow.isOpen(it.state) }.forEach { task ->
        agents.firstOrNull { it.id == task.ownerAgentId }
            ?.let { Positions.byKey(it.positionKey.orEmpty())?.department }
            ?.takeIf { it.isNotBlank() }
            ?.let { focus += it }
    }
    hiring.forEach { if (it.department.isNotBlank()) focus += it.department }

    val picked = LinkedHashSet<GroupAgentRecord>()
    ordered.firstOrNull { Positions.canonicalKey(it.positionKey.orEmpty()) == "GENERAL_MANAGER" }
        ?.let { picked += it }
    ordered.filter { Positions.byKey(it.positionKey.orEmpty())?.tier == 2 }
        .filter { Positions.byKey(it.positionKey.orEmpty())?.department in focus }
        .take(2)
        .forEach { picked += it }
    if (picked.size <= 1) {
        listOf("PRODUCT_MINISTER", "ENGINEERING_MINISTER").forEach { key ->
            agents.firstOrNull { Positions.canonicalKey(it.positionKey.orEmpty()) == key }?.let { picked += it }
        }
    }
    if (picked.isEmpty()) picked += ordered.first()
    return picked.take(4).toList()
}

/** 群状态摘要：谁在、任务板上有什么、哪些增员在等批。 */
private fun buildRoster(
    agents: List<GroupAgentRecord>,
    tasks: List<GroupTaskRecord>,
    hiring: List<StaffingRecord>,
    speakers: List<GroupAgentRecord>,
): String = buildString {
    val byDepartment = agents.groupBy { agent ->
        Positions.byKey(agent.positionKey.orEmpty())?.department?.takeIf { it.isNotBlank() } ?: "未分部门"
    }
    append("成员 ").append(agents.size).append(" 人：")
    append(
        byDepartment.entries.joinToString("；") { (department, members) ->
            val names = members.map {
                Positions.byKey(it.positionKey.orEmpty())?.title ?: it.nickname
            }.take(6).joinToString("、")
            "$department（${members.size}）$names"
        },
    )
    val openTasks = tasks.filter { com.eloykim.lyra.attention.TaskFlow.isOpen(it.state) }
    if (openTasks.isEmpty()) {
        append("\n任务板：空")
    } else {
        append("\n任务板：")
        openTasks.takeLast(6).forEach { task ->
            val owner = agents.firstOrNull { it.id == task.ownerAgentId }
                ?.let { Positions.byKey(it.positionKey.orEmpty())?.title ?: it.nickname }
                ?: "待认领"
            append("\n#${task.id} ${task.title} · ${task.state} · $owner")
        }
    }
    if (hiring.isNotEmpty()) {
        append("\n待董事长批准的增员：")
        append(hiring.take(4).joinToString("；") { "#${it.id} ${it.department}·${it.nickname}（${it.reason.take(40)}）" })
    }
    append("\n本轮发言者：")
    append(speakers.joinToString("、") { Positions.byKey(it.positionKey.orEmpty())?.title ?: it.nickname })
}

private fun stripTitlePrefixes(reply: String): String {
    var text = reply.trimStart()
    val titles = Positions.defaults.map { it.title } + listOf("董事长", "总经理", "记录者")
    val prefix = Regex("^(" + titles.joinToString("|") { Regex.escape(it) } + ")\\s*[：:]\\s*")
    repeat(3) {
        val next = prefix.replaceFirst(text, "")
        if (next != text) text = next
    }
    return text
}
