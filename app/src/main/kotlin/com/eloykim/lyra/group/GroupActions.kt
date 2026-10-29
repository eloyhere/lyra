package com.eloykim.lyra.group

import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.attention.Positions
import com.eloykim.lyra.attention.TaskFlow
import com.eloykim.lyra.data.GroupAgentRecord
import org.json.JSONObject

/**
 * 群聊成员的**行动通道**。
 *
 * 只有发言通道时，部长们的产出永远是"我建议…"，不会真的招人、开任务。
 * 这里给每个成员一条极窄的写操作面：回复末尾一行 `<<ACTION {...}>>`，
 * 由宿主解析、鉴权、执行，再把结果作为系统消息写回群里。
 *
 * 为什么不用 function calling：`LlmMessage` 只有 system/user/assistant 文本，
 * 承载不了 tool 结果；不同网关对流式 tool_calls 的支持也不一致。
 * 文本协议在任何服务商上都成立，代价是提示词里要写清楚格式。
 */
object GroupActions {

    private val marker = Regex("""(?m)^\s*<<ACTION\s*(\{.*\})\s*>>\s*$""")

    data class Parsed(val text: String, val action: JSONObject?)

    /**
     * 把回复拆成「正文」和「动作的原始 JSON 文本」。
     *
     * 单独暴露这一层是为了能在 JVM 单测里验证解析规则——
     * `org.json` 在本地单测里是空实现，碰不得。
     */
    fun split(reply: String): Pair<String, String?> {
        val match = marker.find(reply) ?: return reply.trim() to null
        val text = reply.removeRange(match.range).trim()
        return text to match.groupValues[1]
    }

    /** 把回复拆成「给人看的正文」和「给系统执行的动作」。 */
    fun parse(reply: String): Parsed {
        val (text, raw) = split(reply)
        val action = raw?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
        return Parsed(text, action)
    }

    /** 提示词里的动作说明（所有成员共用）。 */
    fun contract(): String = buildString {
        appendLine("你可以用动作改变群的状态；动作必须写在回复**最后一行**，整行只放一个 JSON：")
        appendLine("""<<ACTION {"type":"request_hire","position":"FRONTEND","reason":"哪件事缺人","expected":"入职第一周交付什么"}>>""")
        appendLine("""<<ACTION {"type":"create_task","title":"任务标题","owner":"BACKEND","acceptance":"什么算完成"}>>""")
        appendLine("""<<ACTION {"type":"move_task","id":12,"state":"review"}>>""")
        appendLine("动作可用类型：request_hire / create_task / move_task / claim_task。")
        appendLine("规则：")
        appendLine("· 先说结论和下一步；不要复述别人刚说过的话。")
        appendLine("· 缺人就用 request_hire，不要只喊「需要招人」。制度内的申请会自动批准并入职；")
        appendLine("  超编、超预算或敏感席位会转给董事长，你在回复里说明等批即可。")
        appendLine("· 一个回复最多一个动作；没有动作就别写这一行。")
        appendLine("· 任务完成先进 review，由发起人或部长验收，不能自己直接标 done。")
    }

    /**
     * 执行动作，返回要写进群里的系统回执。
     * 鉴权失败、状态非法都返回可读文本，不抛异常（模型下一轮能看到原因）。
     */
    suspend fun execute(
        sessionId: Long,
        actor: GroupAgentRecord,
        action: JSONObject,
    ): String {
        val type = action.optString("type")
        val positionKey = Positions.canonicalKey(actor.positionKey.orEmpty())
        val position = Positions.byKey(positionKey)
        val isLead = (position?.tier ?: 3) <= 2

        return when (type) {
            "request_hire" -> requestHire(sessionId, actor, action, isLead)
            "create_task" -> createTask(sessionId, action, isLead)
            "move_task" -> moveTask(sessionId, actor, action, isLead)
            "claim_task" -> claimTask(sessionId, actor, action)
            else -> "未知动作：$type"
        }
    }

    private suspend fun requestHire(
        sessionId: Long,
        actor: GroupAgentRecord,
        action: JSONObject,
        isLead: Boolean,
    ): String {
        if (!isLead) return "只有部长或总经理可以提增员申请。"
        val outcome = HiringDesk.request(
            sessionId = sessionId,
            positionKeyRaw = action.optString("position"),
            reason = action.optString("reason").ifBlank { action.optString("why") },
            expected = action.optString("expected"),
            by = actor.id,
        )
        return outcome.message
    }

    private suspend fun createTask(
        sessionId: Long,
        action: JSONObject,
        isLead: Boolean,
    ): String {
        if (!isLead) return "只有部长或总经理可以开任务。"
        val store = AppGraph.history.groupChat
        val title = action.optString("title").ifBlank { action.optString("task") }.trim()
        if (title.isBlank()) return "任务缺标题。"
        val ownerKey = action.optString("owner").ifBlank { action.optString("ownerPosition") }
        val owner = ownerKey.takeIf { it.isNotBlank() }?.let { key ->
            store.agents(sessionId).firstOrNull {
                Positions.canonicalKey(it.positionKey.orEmpty()) == Positions.canonicalKey(key)
            }
        }
        val acceptance = action.optString("acceptance").trim()
        val taskTitle = if (acceptance.isBlank()) title else "$title｜验收：$acceptance"
        val duplicate = store.tasks(sessionId)
            .any { TaskFlow.isOpen(it.state) && it.title == taskTitle }
        if (duplicate) return "任务板上已有同名任务：$title"
        val id = store.createTask(
            sessionId = sessionId,
            title = taskTitle,
            ownerAgentId = owner?.id,
            warrantTier = action.optString("tier").takeIf { it.isNotBlank() },
        )
        val ownerText = owner?.let { Positions.byKey(it.positionKey.orEmpty())?.title ?: it.nickname } ?: "待认领"
        return "已开任务 #$id：$title（负责人：$ownerText）"
    }

    private suspend fun moveTask(
        sessionId: Long,
        actor: GroupAgentRecord,
        action: JSONObject,
        isLead: Boolean,
    ): String {
        val store = AppGraph.history.groupChat
        val id = action.optLong("id")
        val task = store.tasks(sessionId).firstOrNull { it.id == id }
            ?: return "任务 #$id 不存在。"
        val target = action.optString("state")
        if (target !in TaskFlow.states) return "状态只能是 ${TaskFlow.states.joinToString()}。"
        if (!TaskFlow.canMove(task.state, target)) return "任务 #$id 不能从 ${task.state} 到 $target。"
        if (!isLead && task.ownerAgentId != actor.id) return "只能推进自己负责的任务。"
        if (target == TaskFlow.DONE && task.ownerAgentId == actor.id && !isLead) {
            return "验收由发起人或部长做，不能自己验自己。"
        }
        store.moveTask(id, target)
        return "任务 #$id 已到 $target。"
    }

    private suspend fun claimTask(
        sessionId: Long,
        actor: GroupAgentRecord,
        action: JSONObject,
    ): String {
        val store = AppGraph.history.groupChat
        val id = action.optLong("id")
        val task = store.tasks(sessionId).firstOrNull { it.id == id }
            ?: return "任务 #$id 不存在。"
        if (task.ownerAgentId != null) return "任务 #$id 已有负责人。"
        val doingCount = store.tasks(sessionId)
            .count { it.state == TaskFlow.DOING && it.ownerAgentId == actor.id }
        if (doingCount >= 1) return "你手上已有进行中的任务，做完再领新的（WIP=1）。"
        if (!store.claimTask(id, actor.id)) return "任务 #$id 已被别人领走。"
        return "你已认领任务 #$id。"
    }
}
