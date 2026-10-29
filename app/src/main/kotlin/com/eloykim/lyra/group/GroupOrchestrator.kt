package com.eloykim.lyra.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.attention.Position
import com.eloykim.lyra.attention.Positions
import com.eloykim.lyra.data.GroupMessageRecord
import com.eloykim.lyra.data.GroupAgentRecord
import com.eloykim.lyra.data.GroupRoleRecord
import com.eloykim.lyra.settings.SettingsStore
import com.eloykim.lyra.ui.group.GroupChatController
import com.eloykim.lyra.ui.group.GroupChatSession
import org.json.JSONObject

/**
 * 群聊的唯一入口（G1）。
 *
 * UI 与 service 都经这里触发「一轮群聊」：谁发言、发几轮、预算怎么扣，
 * 只有一处实现。服务侧因此也能让角色开口，而不只是管理状态。
 */
object GroupOrchestrator {

    private val store get() = AppGraph.history.groupChat

    /**
     * 组织的唯一事实来源。空群、旧群、被早期版本写入旧 key 的群都会先经过这里。
     * 返回值已经按数据库中的最新职位绑定读取，调用方不需要再自己补人。
     */
    suspend fun ensureOrganization(sessionId: Long): List<GroupAgentRecord> {
        store.seedPositions(sessionId)
        var roles = store.roles()
        if (roles.isEmpty()) {
            val settings = SettingsStore.state.value
            store.ensurePresetRoles(settings.brandId, settings.model, settings.thinkingLevel.wireValue)
            roles = store.roles()
        }

        val required = Positions.relay
        val requiredKeys = required.map { it.key }.toSet()
        val existing = store.agents(sessionId)
        val byPosition = LinkedHashMap<String, GroupAgentRecord>()
        val unassigned = ArrayDeque<GroupAgentRecord>()
        var changed = false

        existing.forEach { agent ->
            val canonical = agent.positionKey?.let(Positions::canonicalKey)
            if (canonical != null && canonical != agent.positionKey) {
                store.setAgentPosition(agent.id, canonical)
                changed = true
            }
            if (canonical != null && canonical in requiredKeys && !byPosition.containsKey(canonical)) {
                byPosition[canonical] = agent.copy(positionKey = canonical)
            } else if (agent.positionKey.isNullOrBlank()) {
                unassigned.addLast(agent)
            }
        }

        required.forEach { position ->
            if (byPosition.containsKey(position.key)) return@forEach
            val reused = unassigned.removeFirstOrNull()
            if (reused != null) {
                store.setAgentPosition(reused.id, position.key)
                byPosition[position.key] = reused.copy(positionKey = position.key)
                changed = true
                return@forEach
            }
            val role = roleFor(position, roles) ?: return@forEach
            val id = store.addAgent(
                sessionId = sessionId,
                roleId = role.id,
                nickname = position.title,
                identity = "",
                positionKey = position.key,
            )
            byPosition[position.key] = GroupAgentRecord(
                id = id,
                sessionId = sessionId,
                roleId = role.id,
                nickname = position.title,
                identity = "",
                positionKey = position.key,
                createdAt = System.currentTimeMillis(),
            )
            changed = true
        }

        val result = store.agents(sessionId)
        store.rebuildSeats(sessionId, result.map { it.id })
        refreshManifest(sessionId)
        if (changed) {
            AppGraph.history.ledger.append(
                actor = "chairman",
                type = "group.organization.reconciled",
                payload = JSONObject()
                    .put("sessionId", sessionId)
                    .put("positions", result.mapNotNull { it.positionKey?.let(Positions::canonicalKey) })
                    .put("memberCount", result.size)
                    .toString(),
            )
        }
        return result
    }

    private fun roleFor(position: Position, roles: List<GroupRoleRecord>): GroupRoleRecord? {
        val terms = when (position.key) {
            "GENERAL_MANAGER" -> listOf("总经理", "经理", "项目经理")
            Positions.PRODUCT_MINISTER -> listOf("产品", "项目")
            Positions.ENGINEERING_MINISTER -> listOf("工程", "技术", "质量")
            Positions.OPERATIONS_MINISTER -> listOf("运营", "市场", "客户")
            Positions.STAFF_MINISTER -> listOf("职能", "行政", "人力")
            "LEGAL_MINISTER" -> listOf("法务", "合规", "安全")
            "HR_MINISTER" -> listOf("人力", "招聘")
            "FINANCE_MINISTER" -> listOf("财务", "预算")
            "RESOURCE_MINISTER" -> listOf("资源", "采购")
            else -> emptyList()
        }
        return roles.firstOrNull { role -> terms.any { term -> role.name.contains(term) } }
            ?: roles.firstOrNull()
    }

    /** 触发一轮群聊，返回新增消息条数。 */
    suspend fun send(
        sessionId: Long,
        text: String,
        quote: GroupMessageRecord? = null,
        onTyping: (Long?) -> Unit = {},
        onReply: suspend (Long, String, String?) -> Unit = { _, _, _ -> },
        onUserMessage: () -> Unit = {},
    ): Int {
        return GroupChatController.runTurn {
            val content = text.trim()
            if (content.isEmpty()) return@runTurn 0
            val turnId = "group-$sessionId-${System.nanoTime()}"
            val session = GroupChatSession(sessionId)

            // The chairman's message is the first durable user-facing operation in
            // the turn. The UI does not write it separately, so service and UI calls
            // follow exactly the same path.
            session.recordChairmanSpeech(content, quote, turnId)
            onUserMessage()
            AppGraph.history.ledger.append(
                actor = "recorder",
                type = "group.recruitment.started",
                payload = JSONObject()
                    .put("sessionId", sessionId)
                    .put("turnId", turnId)
                    .toString(),
            )
            val agents = ensureOrganization(sessionId)
            AppGraph.history.ledger.append(
                actor = "recorder",
                type = "group.recruitment.completed",
                payload = JSONObject()
                    .put("sessionId", sessionId)
                    .put("turnId", turnId)
                    .put("memberCount", agents.size)
                    .put("positionKeys", agents.mapNotNull { it.positionKey?.let(Positions::canonicalKey) })
                    .toString(),
            )
            val roles = store.roles()
            session.send(
                text = content,
                agents = agents,
                roles = roles,
                turnId = turnId,
                onTyping = onTyping,
                onReply = onReply,
            )
        }
    }

    /**
     * 总经理补位：财务席位空着且有未被使用的角色时，自动拉一个成员入群。
     * 只补空缺，不换人；没有可用角色时保持空缺（失败关闭）。
     */
    suspend fun ensureSeats(sessionId: Long): Int {
        val before = store.agents(sessionId).size
        val after = ensureOrganization(sessionId).size
        return (after - before).coerceAtLeast(0)
    }

    /**
     * 存量群聊迁移：老数据没有"座位"概念，这里按新规则补齐——
     * 每个群至少补到两个成员（总经理 + 财务），角色取未被占用的；
     * 记录者由程序担任，不需要成员。幂等：已满足的群不动。
     */
    suspend fun migrateExisting(): Int {
        var changed = 0
        store.sessions().forEach { session ->
            val before = store.agents(session.id).size
            val after = ensureOrganization(session.id).size
            changed += (after - before).coerceAtLeast(0)
        }
        return changed
    }

    /**
     * 团队清单：成员 + 职位 + 座位 的摘要。变化时追加一条快照，
     * 用于回答"这次派活时团队是什么样"以及检测目录过期。
     */
    suspend fun refreshManifest(sessionId: Long): String? {
        val agents = store.agents(sessionId)
        if (agents.isEmpty()) return null
        val seats = store.seats(sessionId)
        val payload = buildString {
            agents.sortedBy { it.id }.forEach { agent ->
                append(agent.id).append(':').append(agent.positionKey ?: "none").append(';')
            }
            append('|')
            seats.toSortedMap().forEach { (seat, holder) -> append(seat).append('=').append(holder).append(';') }
        }
        val digest = "sha256:" + com.eloykim.lyra.amp.protocol.semantic.Sha256.hex(payload)
        val latest = store.latestManifest(sessionId)
        if (latest?.first != digest) store.appendManifest(sessionId, digest, payload)
        return digest
    }

    suspend fun agents(sessionId: Long) = store.agents(sessionId)

    suspend fun roles() = store.roles()
}
