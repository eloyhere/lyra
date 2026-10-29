package com.eloykim.lyra.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.attention.HiringAsk
import com.eloykim.lyra.attention.HiringDecision
import com.eloykim.lyra.attention.HiringFacts
import com.eloykim.lyra.attention.HiringPolicy
import com.eloykim.lyra.attention.Positions
import com.eloykim.lyra.data.StaffingRecord
import org.json.JSONObject

/**
 * 招聘台：申请 → 策略判定 → （自动入职 | 等董事长）→ 入职。
 *
 * 群聊动作（[GroupActions]）和服务（`groupchat.staffing_request`）共用这一份实现，
 * 避免"人点一下一个样、模型调一次另一个样"。
 */
object HiringDesk {

    data class Outcome(
        val ok: Boolean,
        val message: String,
        val requestId: Long? = null,
        val agentId: Long? = null,
        val pending: Boolean = false,
    )

    private val store get() = AppGraph.history.groupChat

    /** 提一条增员申请；制度内当场入职，例外进待批队列。 */
    suspend fun request(
        sessionId: Long,
        positionKeyRaw: String,
        reason: String,
        expected: String,
        by: Long?,
    ): Outcome {
        val positionKey = Positions.canonicalKey(positionKeyRaw)
        val position = Positions.byKey(positionKey)
            ?: return Outcome(false, "职位不存在：$positionKeyRaw")
        if (position.tier == 0 || position.key == Positions.CHAIRMAN) {
            return Outcome(false, "${position.title} 不是可以招聘的席位")
        }
        val facts = facts(sessionId, positionKey)
        val ask = HiringAsk(
            positionKey = positionKey,
            department = position.department,
            reason = reason.trim(),
            expected = expected.trim(),
        )
        val decision = HiringPolicy.evaluate(ask, facts)
        if (decision is HiringDecision.Reject) {
            ledger("group.hiring.rejected", sessionId, 0L, positionKey, decision.reason)
            return Outcome(false, "增员被拒：${decision.reason}")
        }
        val nickname = HiringPolicy.defaultNickname(
            positionKey,
            facts.departmentCount[position.department] ?: 0,
        )
        val requestId = store.createStaffing(
            sessionId = sessionId,
            positionKey = positionKey,
            nickname = nickname,
            requestedBy = by,
            department = position.department,
            reason = ask.reason,
            expected = ask.expected,
        )
        ledger("group.hiring.requested", sessionId, requestId, positionKey, ask.reason)
        if (decision is HiringDecision.NeedChairman) {
            return Outcome(
                ok = true,
                message = "增员申请 #$requestId 已提交：${position.title}（${position.department}）。" +
                    "原因：${decision.reason}。在群聊页点「批准」即可入职。",
                requestId = requestId,
                pending = true,
            )
        }
        store.decideStaffing(requestId, "approved", decidedBy = by, note = decision.reason, auto = true)
        val created = store.staffing(requestId) ?: return Outcome(false, "申请 #$requestId 读取失败")
        val hired = provision(sessionId, created)
        return if (hired.agentId != null) Outcome(true, hired.message, requestId, hired.agentId) else hired
    }

    /** 董事长（或总经理）对一条待批申请做决定。 */
    suspend fun decide(
        sessionId: Long,
        requestId: Long,
        approve: Boolean,
        by: Long?,
        note: String?,
    ): Outcome {
        val request = store.staffing(requestId)
            ?: return Outcome(false, "申请 #$requestId 不存在")
        if (request.sessionId != sessionId) return Outcome(false, "申请不属于当前群聊")
        if (request.state != "proposed") {
            return Outcome(false, "申请 #$requestId 已经是 ${request.state}，不用再批")
        }
        val text = note?.trim()?.takeIf { it.isNotBlank() }
            ?: if (approve) "董事长批准" else "董事长驳回"
        if (!approve) {
            store.decideStaffing(requestId, "rejected", decidedBy = by, note = text)
            ledger("group.hiring.rejected.by.chairman", sessionId, requestId, request.positionKey, text)
            return Outcome(true, "已驳回 #$requestId（${request.nickname}）：$text")
        }
        store.decideStaffing(requestId, "approved", decidedBy = by, note = text)
        val refreshed = store.staffing(requestId) ?: request
        val hired = provision(sessionId, refreshed)
        return if (hired.agentId != null) {
            Outcome(true, "已批准 #$requestId。" + hired.message, requestId, hired.agentId)
        } else {
            hired
        }
    }

    /** 入职：建成员 + 绑角色 + 刷新团队清单。幂等。 */
    suspend fun provision(sessionId: Long, request: StaffingRecord): Outcome {
        if (request.agentId != null) {
            return Outcome(true, "申请 #${request.id} 已入职过（成员 ${request.agentId}）", request.id, request.agentId)
        }
        val roles = store.roles()
        if (roles.isEmpty()) return Outcome(false, "没有可用角色：先在设置里建一个全局角色")
        val position = Positions.byKey(request.positionKey)
        val terms = departmentTerms(position?.department ?: request.department)
        val role = roles.firstOrNull { candidate -> terms.any { candidate.name.contains(it) } }
            ?: roles.first()
        val agentId = store.addAgent(
            sessionId = sessionId,
            roleId = role.id,
            nickname = request.nickname,
            identity = "",
            positionKey = request.positionKey,
        )
        store.markStaffingCreated(request.id, agentId)
        GroupOrchestrator.refreshManifest(sessionId)
        ledger("group.hiring.completed", sessionId, request.id, request.positionKey, "agent=$agentId")
        val title = position?.title ?: request.nickname
        return Outcome(true, "已入职：$title（#${request.id}，角色 ${role.name}）。", request.id, agentId)
    }

    suspend fun facts(sessionId: Long, positionKey: String): HiringFacts {
        val members = store.agents(sessionId)
        val session = store.session(sessionId)
        val departmentCount = members
            .mapNotNull { Positions.byKey(it.positionKey.orEmpty())?.department }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        return HiringFacts(
            memberCount = members.size,
            departmentCount = departmentCount,
            openRequests = store.openStaffing(sessionId).size,
            positionTaken = members.any {
                Positions.canonicalKey(it.positionKey.orEmpty()) == positionKey
            },
            budgetTokens = session?.tokenBudget ?: 0,
            spentTokens = session?.tokensUsed ?: 0,
        )
    }

    fun departmentTerms(department: String): List<String> = when (department) {
        "工程" -> listOf("工程", "技术", "前端", "后端", "客户端")
        "产品" -> listOf("产品", "设计", "需求")
        "运营" -> listOf("运营", "市场", "销售", "客服")
        "法务" -> listOf("法务", "合规")
        "人力" -> listOf("人力", "招聘")
        "财务" -> listOf("财务", "预算")
        "资源" -> listOf("资源", "采购")
        "职能" -> listOf("职能", "行政")
        else -> listOf(department)
    }

    private suspend fun ledger(type: String, sessionId: Long, id: Long, key: String, extra: String) {
        runCatching {
            AppGraph.history.ledger.append(
                actor = "recorder",
                type = type,
                payload = JSONObject()
                    .put("sessionId", sessionId)
                    .put("id", id)
                    .put("positionKey", key)
                    .put("detail", extra.take(300))
                    .toString(),
            )
        }
    }
}
