package com.eloykim.lyra.service.groupchat

import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqLong
import com.eloykim.lyra.service.reqString
import com.eloykim.lyra.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * GroupChat 服务：群聊会话/成员/全局角色的管理接口。
 *
 * 群聊回复本身由 GroupChatController 走群聊上下文，不经过 lyra_service，
 * 避免多个 Agent 同时操作手机。
 */
class GroupChatService(private val history: HistoryRepository) : BasePhoneService() {

    override val id: String = "group_chat"
    override val title: String = "群聊"
    override val description: String =
        "查看群聊会话、成员和全局角色；新增/移除成员，创建角色（品牌+模型+思考等级）。"

    init {
        register(
            ServiceMethod(
                name = "information",
                summary = "当前群聊信息（成员/消息数）",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { information() }

        register(
            ServiceMethod(
                name = "sessions",
                summary = "列出群聊会话",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { sessions() }

        register(
            ServiceMethod(
                name = "new_session",
                summary = "新建群聊会话",
                parameters = Schema.params(
                    "title" to Schema.string("群名"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val title = args.optStringOr("title").ifBlank { "群聊" }
            val session = history.groupChat.createSession(title)
            ServiceResult.ok("群聊已创建", JSONObject().put("id", session.id).put("title", session.title))
        }

        register(
            ServiceMethod(
                name = "rename_session",
                summary = "重命名群聊",
                parameters = Schema.params(
                    "id" to Schema.integer("会话 id"),
                    "title" to Schema.string("新群名"),
                    required = listOf("id", "title"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            history.groupChat.renameSession(args.reqLong("id"), args.reqString("title"))
            ServiceResult.ok("群名已更新")
        }

        register(
            ServiceMethod(
                name = "delete_session",
                summary = "删除群聊及其成员/消息",
                parameters = Schema.params(
                    "id" to Schema.integer("会话 id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            history.groupChat.deleteSession(args.reqLong("id"))
            ServiceResult.ok("群聊已删除")
        }

        register(
            ServiceMethod(
                name = "clear_history",
                summary = "清空当前群聊的历史消息（保留成员与角色）",
                parameters = Schema.params(
                    "sessionId" to Schema.integer("会话 id；不传当前群"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val id = args.optIntOr("sessionId", 0)
                .takeIf { it > 0 }
                ?.toLong()
                ?: history.groupChat.ensureDefaultSession().id
            val deleted = history.groupChat.clearMessages(id)
            ServiceResult.ok("已清空 $deleted 条消息", JSONObject().put("deleted", deleted))
        }

        register(
            ServiceMethod(
                name = "budget",
                summary = "查看当前群聊 token 预算",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { budget() }

        register(
            ServiceMethod(
                name = "set_budget",
                summary = "设置群聊 token 预算（仅财务）",
                parameters = Schema.params(
                    "tokens" to Schema.integer("预算 token 数"),
                    "sessionId" to Schema.integer("会话 id；不传当前群"),
                    required = listOf("tokens"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val id = args.optIntOr("sessionId", 0)
                .takeIf { it > 0 }
                ?.toLong()
                ?: history.groupChat.ensureDefaultSession().id
            history.groupChat.setBudget(id, args.reqLong("tokens"))
            ServiceResult.ok("预算已更新", JSONObject().put("sessionId", id))
        }

        register(
            ServiceMethod(
                name = "set_reply_policy",
                summary = "设置群聊回复策略（仅财务/协调者）",
                parameters = Schema.params(
                    "policy" to Schema.string("回复策略文本"),
                    "sessionId" to Schema.integer("会话 id；不传当前群"),
                    required = listOf("policy"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val id = args.optIntOr("sessionId", 0)
                .takeIf { it > 0 }
                ?.toLong()
                ?: history.groupChat.ensureDefaultSession().id
            history.groupChat.setReplyPolicy(id, args.reqString("policy"))
            ServiceResult.ok("回复策略已更新", JSONObject().put("sessionId", id))
        }

        register(
            ServiceMethod(
                name = "agents",
                summary = "列出群成员",
                parameters = Schema.params(
                    "sessionId" to Schema.integer("会话 id；不传则默认群"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> agents(args.optIntOr("sessionId", 0).toLong()) }

        register(
            ServiceMethod(
                name = "roles",
                summary = "列出全局 Agent 角色",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { roles() }

        register(
            ServiceMethod(
                name = "create_role",
                summary = "在场景下创建角色（品牌/模型/思考等级）",
                parameters = Schema.params(
                    "scenarioId" to Schema.integer("场景 id；不传则默认场景"),
                    "name" to Schema.string("角色名"),
                    "brandId" to Schema.string("品牌 id"),
                    "model" to Schema.string("模型 id"),
                    "thinking" to Schema.string("off/minimum/medium/maximum"),
                    "duty" to Schema.string("职责说明"),
                    "canManageRoles" to Schema.boolean("是否拥有角色管理权限"),
                    "canManageBudget" to Schema.boolean("是否拥有 token 预算权限"),
                    "canCoordinateReplies" to Schema.boolean("是否可以制定回复策略"),
                    required = listOf("name", "brandId", "model", "thinking"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            ensurePresets()
            val scenarioId = args.optIntOr("scenarioId", 0)
                .takeIf { it > 0 }
                ?.toLong()
                ?: history.groupChat.scenarios().firstOrNull()?.id
            val id = history.groupChat.createRole(
                scenarioId = scenarioId,
                name = args.reqString("name"),
                brandId = args.reqString("brandId"),
                model = args.reqString("model"),
                thinkingLevel = args.reqString("thinking"),
                canManageRoles = args.optBoolOr("canManageRoles", false),
                duty = args.optStringOr("duty"),
                canManageBudget = args.optBoolOr("canManageBudget", false),
                canCoordinateReplies = args.optBoolOr("canCoordinateReplies", false),
            )
            ServiceResult.ok("角色已创建", JSONObject().put("id", id))
        }

        register(
            ServiceMethod(
                name = "update_role",
                summary = "修改全局角色（仅人力资源部）",
                parameters = Schema.params(
                    "id" to Schema.integer("角色 id"),
                    "name" to Schema.string("角色名"),
                    "brandId" to Schema.string("品牌 id"),
                    "model" to Schema.string("模型 id"),
                    "thinking" to Schema.string("off/minimum/medium/maximum"),
                    "duty" to Schema.string("职责说明"),
                    required = listOf("id", "name", "brandId", "model", "thinking"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            history.groupChat.updateRole(
                id = args.reqLong("id"),
                name = args.reqString("name"),
                brandId = args.reqString("brandId"),
                model = args.reqString("model"),
                thinkingLevel = args.reqString("thinking"),
                duty = args.optStringOr("duty"),
            )
            ServiceResult.ok("角色已修改", JSONObject().put("id", args.reqLong("id")))
        }

        register(
            ServiceMethod(
                name = "delete_role",
                summary = "删除全局角色（仅人力资源部）",
                parameters = Schema.params(
                    "id" to Schema.integer("角色 id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            history.groupChat.deleteRole(args.reqLong("id"))
            ServiceResult.ok("角色已删除", JSONObject().put("id", args.reqLong("id")))
        }

        register(
            ServiceMethod(
                name = "assign_role",
                summary = "给成员分配角色（仅人力资源部）",
                parameters = Schema.params(
                    "agentId" to Schema.integer("成员 id"),
                    "roleId" to Schema.integer("角色 id"),
                    required = listOf("agentId", "roleId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val agentId = args.reqLong("agentId")
            val roleId = args.reqLong("roleId")
            val agent = history.groupChat.agents(
                history.groupChat.ensureDefaultSession().id,
            ).firstOrNull { it.id == agentId }
                ?: return@register ServiceResult.fail("not_found", "没有成员 $agentId")
            history.groupChat.updateAgent(
                agentId = agent.id,
                roleId = roleId,
                nickname = agent.nickname,
                identity = agent.identity,
            )
            ServiceResult.ok("角色已分配", JSONObject().put("agentId", agentId))
        }

        register(
            ServiceMethod(
                name = "add_agent",
                summary = "把角色作为 Agent 加入群聊",
                parameters = Schema.params(
                    "nickname" to Schema.string("群昵称"),
                    "roleId" to Schema.integer("全局角色 id"),
                    "identity" to Schema.string("身份/职责，可空"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val session = history.groupChat.ensureDefaultSession()
            val id = history.groupChat.addAgent(
                sessionId = session.id,
                roleId = args.optIntOr("roleId", 0).takeIf { it > 0 }?.toLong(),
                nickname = args.reqString("nickname"),
                identity = args.optStringOr("identity"),
            )
            ServiceResult.ok("成员已加入", JSONObject().put("id", id).put("sessionId", session.id))
        }

        register(
            ServiceMethod(
                name = "remove_agent",
                summary = "移除群成员",
                parameters = Schema.params(
                    "id" to Schema.integer("成员 id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            history.groupChat.removeAgent(args.reqLong("id"))
            ServiceResult.ok("成员已移除", JSONObject().put("id", args.reqLong("id")))
        }

        register(
            ServiceMethod(
                name = "messages",
                summary = "读取群聊消息",
                parameters = Schema.params(
                    "limit" to Schema.integer("条数", 1, 100),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> messages(args.optIntOr("limit", 30)) }
    }

    private suspend fun ensurePresets() {
        val settings = SettingsStore.state.value
        history.groupChat.ensurePresetScenarios(
            brandId = settings.brandId,
            model = settings.model,
            thinkingLevel = settings.thinkingLevel.wireValue,
        )
    }

    private suspend fun information(): ServiceResult {
        ensurePresets()
        val session = history.groupChat.ensureDefaultSession()
        val agents = history.groupChat.agents(session.id)
        val messages = history.groupChat.messages(session.id, 500)
        return ServiceResult.ok(
            "${agents.size} 个成员 · ${messages.size} 条消息",
            JSONObject()
                .put("id", session.id)
                .put("title", session.title)
                .put("agentCount", agents.size)
                .put("messageCount", messages.size)
                .put("createdAt", session.createdAt)
                .put("updatedAt", session.updatedAt),
        )
    }

    private suspend fun sessions(): ServiceResult {
        val array = JSONArray()
        history.groupChat.sessions().forEach { session ->
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                    .put("tokenBudget", session.tokenBudget)
                    .put("tokensUsed", session.tokensUsed),
            )
        }
        return ServiceResult.ok("${array.length()} 个群聊", JSONObject().put("sessions", array))
    }

    private suspend fun budget(): ServiceResult {
        val session = history.groupChat.ensureDefaultSession()
        return ServiceResult.ok(
            "${session.tokensUsed}/${session.tokenBudget} tokens",
            JSONObject()
                .put("sessionId", session.id)
                .put("budget", session.tokenBudget)
                .put("used", session.tokensUsed)
                .put("remaining", (session.tokenBudget - session.tokensUsed).coerceAtLeast(0))
                .put("replyPolicy", session.replyPolicy),
        )
    }

    private suspend fun agents(sessionId: Long): ServiceResult {
        val session = if (sessionId > 0) {
            sessionId
        } else {
            history.groupChat.ensureDefaultSession().id
        }
        val array = JSONArray()
        history.groupChat.agents(session).forEach { agent ->
            array.put(
                JSONObject()
                    .put("id", agent.id)
                    .put("nickname", agent.nickname)
                    .put("roleId", agent.roleId)
                    .put("identity", agent.identity),
            )
        }
        return ServiceResult.ok("${array.length()} 个成员", JSONObject().put("agents", array))
    }

    private suspend fun roles(): ServiceResult {
        ensurePresets()
        val array = JSONArray()
        history.groupChat.roles().forEach { role ->
            array.put(
                JSONObject()
                    .put("id", role.id)
                    .put("name", role.name)
                    .put("brandId", role.brandId)
                    .put("model", role.model)
                    .put("thinking", role.thinkingLevel)
                    .put("canManageRoles", role.canManageRoles)
                    .put("duty", role.duty)
                    .put("canManageBudget", role.canManageBudget)
                    .put("canCoordinateReplies", role.canCoordinateReplies),
            )
        }
        return ServiceResult.ok("${array.length()} 个角色", JSONObject().put("roles", array))
    }

    private suspend fun messages(limit: Int): ServiceResult {
        val session = history.groupChat.ensureDefaultSession()
        val array = JSONArray()
        history.groupChat.messages(session.id, limit).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role)
                    .put("author", message.authorName)
                    .put("content", message.content)
                    .put("createdAt", message.createdAt),
            )
        }
        return ServiceResult.ok("${array.length()} 条消息", JSONObject().put("messages", array))
    }
}
