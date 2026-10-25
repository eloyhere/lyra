package com.eloykim.lyra.service.groupchat

import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.AppGraph
import com.eloykim.lyra.group.GroupOrchestrator
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
                name = "hire_member",
                summary = "董事长直聘：跳过三方审批直接建成员（仅用户本人可用，用于冷启动）",
                parameters = Schema.params(
                    "positionKey" to Schema.string("职位 key"),
                    "nickname" to Schema.string("成员称呼"),
                    "roleId" to Schema.integer("角色 id（可选）"),
                    required = listOf("positionKey", "nickname"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val actor = args.optLong("actorAgentId", 0L)
            if (actor > 0) {
                return@register ServiceResult.fail("not_chairman", "只有董事长可以直接聘用")
            }
            val session = history.groupChat.ensureDefaultSession()
            val agentId = history.groupChat.addAgent(
                sessionId = session.id,
                roleId = args.optIntOr("roleId", 0).takeIf { it > 0 }?.toLong(),
                nickname = args.reqString("nickname"),
                identity = "",
                positionKey = args.reqString("positionKey"),
            )
            com.eloykim.lyra.group.GroupOrchestrator.refreshManifest(session.id)
            runCatching {
                history.ledger.append(
                    "group",
                    "signal.direct_hire",
                    JSONObject().put("agentId", agentId).put("position", args.reqString("positionKey")).toString(),
                )
            }
            ServiceResult.ok("已直聘", JSONObject().put("agentId", agentId))
        }

        register(
            ServiceMethod(
                name = "staffing_request",
                summary = "部长提入职申请（需财务/资源/人力三方审批后才创建成员）",
                parameters = Schema.params(
                    "positionKey" to Schema.string("职位 key"),
                    "nickname" to Schema.string("成员称呼"),
                    "requestedBy" to Schema.integer("提议人成员 id"),
                    required = listOf("positionKey", "nickname"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val session = history.groupChat.ensureDefaultSession()
            val id = history.groupChat.createStaffing(
                sessionId = session.id,
                positionKey = args.reqString("positionKey"),
                nickname = args.reqString("nickname"),
                requestedBy = args.optIntOr("requestedBy", 0).takeIf { it > 0 }?.toLong(),
            )
            ServiceResult.ok("入职申请已提交，等待三方审批", JSONObject().put("requestId", id))
        }

        register(
            ServiceMethod(
                name = "staffing_approve",
                summary = "部门审批入职申请：finance / resource / hr；三方齐全后自动创建成员",
                parameters = Schema.params(
                    "requestId" to Schema.integer("申请 id"),
                    "department" to Schema.string("finance / resource / hr"),
                    required = listOf("requestId", "department"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val requestId = args.reqLong("requestId")
            val department = args.reqString("department")
            if (!history.groupChat.approveStaffing(requestId, department)) {
                return@register ServiceResult.fail("bad_department", "部门只能是 finance/resource/hr")
            }
            val request = history.groupChat.staffingRequest(requestId)
                ?: return@register ServiceResult.fail("not_found", "申请不存在")
            val financeOk = request["financeOk"] == true
            val resourceOk = request["resourceOk"] == true
            val hrOk = request["hrOk"] == true
            if (!(financeOk && resourceOk && hrOk)) {
                return@register ServiceResult.ok(
                    "已记录 $department 审批，仍待其余部门",
                    JSONObject().put("requestId", requestId)
                        .put("finance", financeOk).put("resource", resourceOk).put("hr", hrOk),
                )
            }
            if (request["agentId"] != null) {
                return@register ServiceResult.ok("该申请已创建过成员", JSONObject().put("requestId", requestId))
            }
            val agentId = history.groupChat.addAgent(
                sessionId = request["sessionId"] as Long,
                roleId = null,
                nickname = request["nickname"] as String,
                identity = "",
                positionKey = request["positionKey"] as String,
            )
            history.groupChat.markStaffingCreated(requestId, agentId)
            com.eloykim.lyra.group.GroupOrchestrator.refreshManifest(request["sessionId"] as Long)
            ServiceResult.ok(
                "三方通过，成员已创建",
                JSONObject().put("requestId", requestId).put("agentId", agentId),
            )
        }

        register(
            ServiceMethod(
                name = "metrics",
                summary = "群级指标：每人 token、金额、调用次数",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { _ ->
            val session = history.groupChat.ensureDefaultSession()
            val usage = history.groupChat.usageByAgent(session.id).associateBy { it.agentId }
            val array = JSONArray()
            var totalTokens = 0L
            var totalCost = 0L
            var totalCalls = 0
            history.groupChat.agents(session.id).forEach { agent ->
                val row = usage[agent.id]
                val tokens = row?.tokens ?: 0L
                val cost = row?.costMicros ?: 0L
                val calls = row?.calls ?: 0
                totalTokens += tokens
                totalCost += cost
                totalCalls += calls
                array.put(
                    JSONObject()
                        .put("id", agent.id)
                        .put(
                            "position",
                            com.eloykim.lyra.attention.Positions.byKey(agent.positionKey.orEmpty())?.title
                                ?: agent.positionKey,
                        )
                        .put("tokens", tokens)
                        .put("costMicros", cost)
                        .put("calls", calls),
                )
            }
            ServiceResult.ok(
                "共 $totalCalls 次调用 / $totalTokens tokens",
                JSONObject()
                    .put("tokens", totalTokens)
                    .put("costMicros", totalCost)
                    .put("calls", totalCalls)
                    .put("members", array),
            )
        }

        register(
            ServiceMethod(
                name = "set_member_quota",
                summary = "给某个成员单独设 token 额度（0 表示回到均分；仅财务）",
                parameters = Schema.params(
                    "agentId" to Schema.integer("成员 id"),
                    "tokens" to Schema.integer("额度，0 表示均分"),
                    required = listOf("agentId", "tokens"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            guardSeat("groupchat.set_budget", args, history.groupChat.ensureDefaultSession().id)
                ?.let { return@register it }
            val ok = history.groupChat.setAgentQuota(args.reqLong("agentId"), args.reqLong("tokens"))
            if (ok) ServiceResult.ok("额度已更新", JSONObject().put("agentId", args.reqLong("agentId")))
            else ServiceResult.fail("not_found", "成员不存在")
        }

        register(
            ServiceMethod(
                name = "manifest",
                summary = "团队清单：成员、职位、座位的当前摘要",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { _ ->
            val session = history.groupChat.ensureDefaultSession()
            val digest = com.eloykim.lyra.group.GroupOrchestrator.refreshManifest(session.id)
            val agents = history.groupChat.agents(session.id)
            val array = JSONArray()
            agents.forEach { agent ->
                array.put(
                    JSONObject()
                        .put("id", agent.id)
                        .put(
                            "position",
                            com.eloykim.lyra.attention.Positions.byKey(agent.positionKey.orEmpty())?.title
                                ?: agent.positionKey,
                        ),
                )
            }
            ServiceResult.ok(
                "${agents.size} 名成员",
                JSONObject().put("digest", digest).put("members", array),
            )
        }

        register(
            ServiceMethod(
                name = "assign_position",
                summary = "给成员绑定职位（决定接力发言顺序）",
                parameters = Schema.params(
                    "agentId" to Schema.integer("成员 id"),
                    "positionKey" to Schema.string("职位 key，如 PRODUCT_MINISTER；传空则解绑"),
                    required = listOf("agentId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            guardSeat("groupchat.assign_role", args, history.groupChat.ensureDefaultSession().id)
                ?.let { return@register it }
            val key = args.optStringOr("positionKey").takeIf { it.isNotBlank() }
            val ok = history.groupChat.setAgentPosition(args.reqLong("agentId"), key)
            if (ok) ServiceResult.ok("职位已更新", JSONObject().put("agentId", args.reqLong("agentId")).put("positionKey", key))
            else ServiceResult.fail("not_found", "成员不存在")
        }

        register(
            ServiceMethod(
                name = "task_dissent",
                summary = "对任务记一票反对（虚假共识检测用）",
                parameters = Schema.params(
                    "taskId" to Schema.integer("任务 id"),
                    "reason" to Schema.string("反对理由"),
                    required = listOf("taskId", "reason"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val count = history.groupChat.addDissent(args.reqLong("taskId"))
            runCatching {
                history.ledger.append(
                    "service",
                    "dissent.recorded",
                    JSONObject()
                        .put("taskId", args.reqLong("taskId"))
                        .put("reason", args.reqString("reason"))
                        .put("total", count)
                        .toString(),
                )
            }
            ServiceResult.ok("反对已记录（共 $count 票）", JSONObject().put("dissent", count))
        }

        register(
            ServiceMethod(
                name = "unknown_raise",
                summary = "记一条未知（blocking 的会阻止开工）",
                parameters = Schema.params(
                    "question" to Schema.string("待澄清的问题"),
                    "blocking" to Schema.boolean("是否阻塞开工，默认 true"),
                    "raisedBy" to Schema.integer("提问成员 id"),
                    required = listOf("question"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val session = history.groupChat.ensureDefaultSession()
            val id = history.groupChat.raiseUnknown(
                sessionId = session.id,
                question = args.reqString("question"),
                blocking = args.optBoolean("blocking", true),
                raisedBy = args.optIntOr("raisedBy", 0).takeIf { it > 0 }?.toLong(),
            )
            ServiceResult.ok("未知已记录", JSONObject().put("id", id))
        }

        register(
            ServiceMethod(
                name = "unknown_resolve",
                summary = "解除一条未知（用户回答之后）",
                parameters = Schema.params(
                    "unknownId" to Schema.integer("未知 id"),
                    required = listOf("unknownId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val ok = history.groupChat.resolveUnknown(args.reqLong("unknownId"))
            if (ok) ServiceResult.ok("已解除") else ServiceResult.fail("not_found", "未知不存在")
        }

        register(
            ServiceMethod(
                name = "unknown_list",
                summary = "查看未解除的未知",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { _ ->
            val session = history.groupChat.ensureDefaultSession()
            val list = history.groupChat.unknowns(session.id).filterNot { it.resolved }
            val array = JSONArray()
            list.forEach { unknown ->
                array.put(
                    JSONObject()
                        .put("id", unknown.id)
                        .put("question", unknown.question)
                        .put("blocking", unknown.blocking),
                )
            }
            ServiceResult.ok("${list.size} 条未解除", JSONObject().put("unknowns", array))
        }

        register(
            ServiceMethod(
                name = "commitment_post",
                summary = "记录一条承诺（谁答应谁、什么算完成）",
                parameters = Schema.params(
                    "fromAgentId" to Schema.integer("承诺方成员 id"),
                    "toAgentId" to Schema.integer("接收方成员 id"),
                    "proposition" to Schema.string("承诺内容"),
                    "satisfaction" to Schema.string("什么算完成"),
                    "dueAt" to Schema.integer("截止时间戳"),
                    required = listOf("fromAgentId", "toAgentId", "proposition", "satisfaction"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val session = history.groupChat.ensureDefaultSession()
            val id = history.groupChat.postCommitment(
                sessionId = session.id,
                fromAgent = args.reqLong("fromAgentId"),
                toAgent = args.reqLong("toAgentId"),
                proposition = args.reqString("proposition"),
                satisfaction = args.reqString("satisfaction"),
                dueAt = args.optIntOr("dueAt", 0).takeIf { it > 0 }?.toLong(),
            )
            ServiceResult.ok("承诺已记录（posted）", JSONObject().put("id", id))
        }

        register(
            ServiceMethod(
                name = "commitment_ground",
                summary = "接收方确认承诺（posted → grounded，需复述理解）",
                parameters = Schema.params(
                    "commitmentId" to Schema.integer("承诺 id"),
                    "understanding" to Schema.string("接收方复述：我理解…"),
                    "firstStep" to Schema.string("接收方第一步做什么"),
                    required = listOf("commitmentId", "understanding"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val ok = history.groupChat.setCommitmentState(args.reqLong("commitmentId"), "grounded")
            if (ok) ServiceResult.ok("已 grounded", JSONObject().put("commitmentId", args.reqLong("commitmentId")))
            else ServiceResult.fail("not_found", "承诺不存在")
        }

        register(
            ServiceMethod(
                name = "commitment_discharge",
                summary = "承诺完成（grounded → discharged）",
                parameters = Schema.params(
                    "commitmentId" to Schema.integer("承诺 id"),
                    required = listOf("commitmentId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val ok = history.groupChat.setCommitmentState(args.reqLong("commitmentId"), "discharged")
            if (ok) ServiceResult.ok("已解除") else ServiceResult.fail("not_found", "承诺不存在")
        }

        register(
            ServiceMethod(
                name = "handoff_task",
                summary = "带交接单派活（背景/现状/判断/请求/验收），生成承诺并认领任务",
                parameters = Schema.params(
                    "taskId" to Schema.integer("任务 id"),
                    "toAgentId" to Schema.integer("接活成员 id"),
                    "background" to Schema.string("背景：这件事从哪来"),
                    "status" to Schema.string("现状：已做什么、卡在哪"),
                    "assessment" to Schema.string("判断：建议怎么做"),
                    "request" to Schema.string("请求：你要做的具体动作"),
                    "acceptance" to Schema.string("验收：什么算完成"),
                    required = listOf("taskId", "toAgentId", "request", "acceptance"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val session = history.groupChat.ensureDefaultSession()
            val toAgent = args.reqLong("toAgentId")
            val request = args.reqString("request")
            val commitment = history.groupChat.postCommitment(
                sessionId = session.id,
                fromAgent = toAgent,
                toAgent = toAgent,
                proposition = request,
                satisfaction = args.reqString("acceptance"),
            )
            history.groupChat.claimTask(args.reqLong("taskId"), toAgent)
            ServiceResult.ok(
                "交接已发出，等待接收方复述",
                JSONObject().put("commitmentId", commitment).put("taskId", args.reqLong("taskId")),
            )
        }

        register(
            ServiceMethod(
                name = "task_synthesis",
                summary = "接收方复述理解并开工（承诺 grounded，任务进入 doing）",
                parameters = Schema.params(
                    "taskId" to Schema.integer("任务 id"),
                    "commitmentId" to Schema.integer("承诺 id"),
                    "understanding" to Schema.string("我理解…"),
                    "firstStep" to Schema.string("我的第一步…"),
                    required = listOf("taskId", "commitmentId", "understanding", "firstStep"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            history.groupChat.setCommitmentState(args.reqLong("commitmentId"), "grounded")
            history.groupChat.moveTask(args.reqLong("taskId"), "doing")
            ServiceResult.ok(
                "已复述并开工",
                JSONObject()
                    .put("taskId", args.reqLong("taskId"))
                    .put("understanding", args.reqString("understanding"))
                    .put("firstStep", args.reqString("firstStep")),
            )
        }

        register(
            ServiceMethod(
                name = "task_create",
                summary = "在任务板上新建任务（可指定负责人）",
                parameters = Schema.params(
                    "title" to Schema.string("任务标题"),
                    "ownerAgentId" to Schema.integer("负责人成员 id"),
                    "dueAt" to Schema.integer("截止时间戳（毫秒）"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val session = history.groupChat.ensureDefaultSession()
            val id = history.groupChat.createTask(
                sessionId = session.id,
                title = args.reqString("title"),
                ownerAgentId = args.optIntOr("ownerAgentId", 0).takeIf { it > 0 }?.toLong(),
                dueAt = args.optIntOr("dueAt", 0).takeIf { it > 0 }?.toLong(),
                manifestDigest = com.eloykim.lyra.group.GroupOrchestrator.refreshManifest(session.id),
            )
            runCatching {
                val title = args.reqString("title")
                val duplicate = history.groupChat.tasks(session.id).any { existing ->
                    existing.id != id && existing.state != "dropped" && similarity(existing.title, title) >= 0.8
                }
                if (duplicate) {
                    history.ledger.append(
                        "service",
                        "signal.duplicate_work",
                        JSONObject().put("taskId", id).put("title", title).toString(),
                    )
                }
            }
            ServiceResult.ok("任务已创建", JSONObject().put("id", id).put("sessionId", session.id))
        }

        register(
            ServiceMethod(
                name = "task_list",
                summary = "查看任务板",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { _ ->
            val session = history.groupChat.ensureDefaultSession()
            val tasks = history.groupChat.tasks(session.id)
            val array = JSONArray()
            tasks.forEach { task ->
                array.put(
                    JSONObject()
                        .put("id", task.id)
                        .put("title", task.title)
                        .put("ownerAgentId", task.ownerAgentId)
                        .put("state", task.state)
                        .put("dueAt", task.dueAt),
                )
            }
            ServiceResult.ok("${tasks.size} 个任务", JSONObject().put("tasks", array))
        }

        register(
            ServiceMethod(
                name = "task_claim",
                summary = "认领一个未分配的任务",
                parameters = Schema.params(
                    "taskId" to Schema.integer("任务 id"),
                    "agentId" to Schema.integer("认领者成员 id"),
                    required = listOf("taskId", "agentId"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val ok = history.groupChat.claimTask(args.reqLong("taskId"), args.reqLong("agentId"))
            if (!ok) ServiceResult.fail("taken", "任务已被认领或不存在")
            else ServiceResult.ok("已认领", JSONObject().put("taskId", args.reqLong("taskId")))
        }

        register(
            ServiceMethod(
                name = "task_move",
                summary = "移动任务状态（doing/blocked/done/dropped）",
                parameters = Schema.params(
                    "taskId" to Schema.integer("任务 id"),
                    "state" to Schema.string("目标状态"),
                    required = listOf("taskId", "state"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val state = args.reqString("state")
            // 协作信号：越级开工 / 无复述交接 / 承诺没有解除
            runCatching {
                val session = history.groupChat.ensureDefaultSession()
                val task = history.groupChat.tasks(session.id).firstOrNull { it.id == args.reqLong("taskId") }
                val commitments = history.groupChat.commitments(session.id)
                fun signal(name: String, extra: String = "") {
                    history.ledger.append(
                        "service",
                        name,
                        JSONObject().put("taskId", args.reqLong("taskId")).put("extra", extra).toString(),
                    )
                }
                if (state == "doing" && task?.state == "proposed") signal("signal.intake_skipped")
                if (state == "doing" && commitments.none { it.state == "grounded" }) {
                    signal("signal.handoff_without_synthesis")
                }
                if (state == "done" && commitments.none { it.state == "discharged" }) {
                    signal("signal.commitment_orphan")
                }
                val currentDigest = com.eloykim.lyra.group.GroupOrchestrator.refreshManifest(session.id)
                if (task?.manifestDigest != null && currentDigest != null && task.manifestDigest != currentDigest) {
                    signal("signal.stale_directory", "manifest_changed")
                }
                val agents = history.groupChat.agents(session.id)
                if (task?.ownerAgentId != null && agents.none { it.id == task.ownerAgentId }) {
                    signal("signal.stale_directory")
                }
                if (state == "done" && task != null &&
                    task.dissentCount == 0 &&
                    (task.warrantTier == "T2" || task.warrantTier == "T3")
                ) {
                    signal("signal.false_consensus")
                }
            }
            // 澄清完成后自动交棒：由产品部职员翻译与细化需求
            runCatching {
                val session = history.groupChat.ensureDefaultSession()
                val moving = history.groupChat.tasks(session.id).firstOrNull { it.id == args.reqLong("taskId") }
                if (state == "done" && moving != null && moving.title.startsWith("需求澄清")) {
                    val exists = history.groupChat.tasks(session.id).any { it.title.startsWith("需求翻译与细化") }
                    if (!exists) {
                        history.groupChat.createTask(
                            sessionId = session.id,
                            title = "需求翻译与细化：把口语需求整理成目标/范围/验收",
                            warrantTier = "T1",
                        )
                    }
                }
            }
            val allowed = setOf(
                "proposed", "needs_info", "ready", "claimed", "doing", "blocked", "done", "dropped",
            )
            if (state !in allowed) {
                ServiceResult.fail("bad_state", "状态只能是 ${allowed.joinToString()}")
            } else {
                val ok = history.groupChat.moveTask(args.reqLong("taskId"), state)
                if (!ok) ServiceResult.fail("not_found", "任务不存在")
                else ServiceResult.ok("已移动", JSONObject().put("taskId", args.reqLong("taskId")).put("state", state))
            }
        }

        register(
            ServiceMethod(
                name = "send",
                summary = "让群里的角色就某话题发言（触发一轮群聊）",
                parameters = Schema.params(
                    "sessionId" to Schema.integer("会话 id"),
                    "text" to Schema.string("话题或问题"),
                    required = listOf("text"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            val requested = args.optLong("sessionId", 0L)
            val sessionId = if (requested > 0) requested
                else AppGraph.history.groupChat.latestSession()?.id
                ?: return@register ServiceResult.fail("no_session", "还没有群聊会话")
            val text = args.optStringOr("text")
            GroupOrchestrator.ensureSeats(sessionId)
            val added = GroupOrchestrator.send(sessionId, text)
            ServiceResult.ok("$added messages", JSONObject().put("sessionId", sessionId).put("added", added))
        }

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
            guardSeat("groupchat.clear_history", args, id)?.let { return@register it }
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
            guardSeat("groupchat.set_budget", args, id)?.let { return@register it }
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
            guardSeat("groupchat.set_reply_policy", args, id)?.let { return@register it }
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
            guardSeat("groupchat.create_role", args, history.groupChat.ensureDefaultSession().id)?.let { return@register it }
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
            guardSeat("groupchat.assign_role", args, history.groupChat.ensureDefaultSession().id)?.let { return@register it }
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
            guardSeat("groupchat.add_agent", args, session.id)?.let { return@register it }
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
            guardSeat("groupchat.remove_agent", args, history.groupChat.ensureDefaultSession().id)?.let { return@register it }
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
                .put("updatedAt", session.updatedAt)
                .put("seats", seatsJson(session.id, messages.size)),
        )
    }

    /**
     * 当前群的核心职位（董事长—总经理—财务—记录者）。
     *
     *  seats 只读展示：座位由建群时预分配，换人只换坐的人；
     *  董事长（用户）不在座位表里，但他拥有全部否决权。
     */
    /**
     * 座位校验：成员只能行使自己座位的权力，董事长（不传 actorAgentId）不受限。
     * 拒绝时写 seat.denied 事件，便于事后审计"谁越权想干什么"。
     */
    private suspend fun guardSeat(
        method: String,
        args: JSONObject,
        sessionId: Long,
    ): ServiceResult? {
        val actor = args.optLong("actorAgentId", 0L).takeIf { it > 0 } ?: return null
        val seats = loadSeats(sessionId)
        if (seats.mayCall(actor, method)) return null
        runCatching {
            history.ledger.append(
                "service",
                "seat.denied",
                JSONObject().put("method", method).put("actor", actor).put("session", sessionId).toString(),
            )
        }
        return ServiceResult.fail(
            "not_seated",
            "该操作需要核心职位；成员 $actor 不在对应座位上（$method）",
        )
    }

    /** 座位优先读持久化表；没有记录时才按成员顺序推导。 */
    private suspend fun loadSeats(sessionId: Long): com.eloykim.lyra.attention.GroupSeats {
        val stored = history.groupChat.seats(sessionId)
        if (stored.isEmpty()) {
            val ids = history.groupChat.agents(sessionId).map { it.id }
            return com.eloykim.lyra.attention.GroupSeats.preset(ids)
        }
        return com.eloykim.lyra.attention.GroupSeats(
            stored.mapNotNull { (seat, agentId) ->
                runCatching { com.eloykim.lyra.attention.CoreSeat.valueOf(seat) to agentId }.getOrNull()
            }.toMap(),
        )
    }

    /** 字符二元组 Jaccard 相似度，用于重复劳动检测。 */
    private fun similarity(a: String, b: String): Double {
        fun grams(s: String): Set<String> {
            val clean = s.replace(Regex("[\\s，。：,.:；;、/()（）]"), "")
            if (clean.length < 2) return clean.map { it.toString() }.toSet()
            return clean.windowed(2).toSet()
        }
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        return ga.intersect(gb).size.toDouble() / ga.union(gb).size
    }

    private suspend fun seatsJson(sessionId: Long, messageCount: Int): JSONObject {
        val seats = loadSeats(sessionId)
        val board = JSONObject()
        com.eloykim.lyra.attention.CoreSeat.entries.forEach { seat ->
            seats.holder(seat)?.let { holder ->
                board.put(
                    seat.title,
                    if (holder == com.eloykim.lyra.attention.GroupSeats.SYSTEM_RECORDER) {
                        "系统（代码）"
                    } else {
                        holder
                    },
                )
            }
        }
        return JSONObject()
            .put("board", board)
            .put(
                "recorder",
                JSONObject()
                    .put("holder", "系统（代码）")
                    .put("messagesWritten", messageCount)
                    .put("eventsWritten", runCatching { history.ledger.size() }.getOrDefault(0)),
            )
            .put("chairman", "用户（不受座位限制）")
            .put("vacant", com.eloykim.lyra.attention.CoreSeat.entries
                .filter { seats.holder(it) == null }
                .map { it.title })
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
        val agents = history.groupChat.agents(session.id)
        val shares = if (session.tokenBudget > 0) {
            com.eloykim.lyra.attention.GroupAttention.budgetShares(
                session.tokenBudget,
                agents.map { a ->
                    com.eloykim.lyra.attention.GroupMember(
                        id = a.id,
                        name = a.positionKey ?: a.nickname,
                        spentTokens = a.tokensUsed,
                    )
                },
            )
        } else {
            emptyMap()
        }
        val members = JSONArray()
        agents.forEach { agent ->
            members.put(
                JSONObject()
                    .put("id", agent.id)
                    .put(
                        "position",
                        com.eloykim.lyra.attention.Positions.byKey(agent.positionKey.orEmpty())?.title
                            ?: agent.positionKey,
                    )
                    .put("used", agent.tokensUsed)
                    .put(
                        "share",
                        com.eloykim.lyra.attention.GroupAttention.memberShare(
                            totalTokens = session.tokenBudget,
                            memberId = agent.id,
                            members = agents.map { a ->
                                com.eloykim.lyra.attention.GroupMember(
                                    id = a.id,
                                    name = a.positionKey ?: a.nickname,
                                    spentTokens = a.tokensUsed,
                                )
                            },
                            quota = agent.quotaTokens,
                            isMinister = agent.positionKey?.endsWith("_MINISTER") == true,
                        ),
                    ),
            )
        }
        return ServiceResult.ok(
            "预算 ${session.tokenBudget}，已用 ${session.tokensUsed}",
            JSONObject()
                .put("budget", session.tokenBudget)
                .put("used", session.tokensUsed)
                .put("members", members),
        )
    }

    private suspend fun legacyBudget(): ServiceResult {
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
