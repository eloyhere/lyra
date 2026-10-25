package com.eloykim.lyra.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.data.GroupMessageRecord
import com.eloykim.lyra.ui.group.GroupChatSession

/**
 * 群聊的唯一入口（G1）。
 *
 * UI 与 service 都经这里触发「一轮群聊」：谁发言、发几轮、预算怎么扣，
 * 只有一处实现。服务侧因此也能让角色开口，而不只是管理状态。
 */
object GroupOrchestrator {

    private val store get() = AppGraph.history.groupChat

    /** 触发一轮群聊，返回新增消息条数。 */
    suspend fun send(
        sessionId: Long,
        text: String,
        quote: GroupMessageRecord? = null,
    ): Int {
        val agents = store.agents(sessionId)
        val roles = store.roles()
        return GroupChatSession(sessionId).send(text, agents, roles, quote)
    }

    /**
     * 总经理补位：财务席位空着且有未被使用的角色时，自动拉一个成员入群。
     * 只补空缺，不换人；没有可用角色时保持空缺（失败关闭）。
     */
    suspend fun ensureSeats(sessionId: Long): Int {
        val agents = store.agents(sessionId)
        val seats = com.eloykim.lyra.attention.GroupSeats.preset(agents.map { it.id })
        if (seats.holder(com.eloykim.lyra.attention.CoreSeat.FINANCE) != null) return 0
        val usedRoles = agents.mapNotNull { it.roleId }.toSet()
        val candidate = store.roles().firstOrNull { it.id !in usedRoles } ?: return 0
        store.addAgent(sessionId, candidate.id, candidate.name, identity = "")
        return 1
    }

    /**
     * 存量群聊迁移：老数据没有"座位"概念，这里按新规则补齐——
     * 每个群至少补到两个成员（总经理 + 财务），角色取未被占用的；
     * 记录者由程序担任，不需要成员。幂等：已满足的群不动。
     */
    suspend fun migrateExisting(): Int {
        var changed = 0
        val roles = store.roles()
        store.sessions().forEach { session ->
            store.seedPositions(session.id)
            val agents = store.agents(session.id)
            if (agents.size >= 2) {
                // 已有人数也重建一次座位，保证存量群聊与新区则一致
                store.rebuildSeats(session.id, agents.map { it.id })
                refreshManifest(session.id)
                return@forEach
            }
            val used = agents.mapNotNull { it.roleId }.toSet()
            val candidates = roles.filter { it.id !in used }
            var need = 2 - agents.size
            candidates.forEach { role ->
                if (need > 0) {
                    store.addAgent(session.id, role.id, role.name, identity = "")
                    need--
                    changed++
                }
            }
            store.rebuildSeats(session.id, store.agents(session.id).map { it.id })
            refreshManifest(session.id)
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
        val digest = com.eloykim.lyra.amp.Digests.sha256(payload)
        val latest = store.latestManifest(sessionId)
        if (latest?.first != digest) store.appendManifest(sessionId, digest, payload)
        return digest
    }

    suspend fun agents(sessionId: Long) = store.agents(sessionId)

    suspend fun roles() = store.roles()
}
