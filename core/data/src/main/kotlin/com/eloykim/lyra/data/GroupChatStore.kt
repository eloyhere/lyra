package com.eloykim.lyra.data

import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 场景：角色按场景分组，角色是场景的子表。 */
data class GroupScenarioRecord(
    val id: Long,
    val name: String,
    val description: String,
    val createdAt: Long,
)

/** Agent 角色：品牌 + 模型 + 思考等级 + 职责；绑定场景。 */
data class GroupRoleRecord(
    val id: Long,
    val scenarioId: Long?,
    val name: String,
    val brandId: String,
    val model: String,
    val thinkingLevel: String,
    val createdAt: Long,
    val canManageRoles: Boolean = false,
    val duty: String = "",
    val canManageBudget: Boolean = false,
    val canCoordinateReplies: Boolean = false,
)

data class GroupSessionRecord(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val tokenBudget: Long = 0,
    val tokensUsed: Long = 0,
    val quotaTokens: Long = 0,
    val replyPolicy: String = "",
    val scenarioId: Long? = null,
    val roleId: Long? = null,
)

data class GroupAgentRecord(
    val id: Long,
    val sessionId: Long,
    val roleId: Long?,
    val nickname: String,
    val identity: String,
    val positionKey: String? = null,
    val tokensUsed: Long = 0,
    val quotaTokens: Long = 0,
    val createdAt: Long,
)

data class GroupMessageRecord(
    val id: Long,
    val sessionId: Long,
    val role: String,
    val authorId: Long?,
    val authorName: String,
    val content: String,
    val mentions: String,
    val createdAt: Long,
    val quotedId: Long? = null,
    val quotedAuthor: String? = null,
    val quotedContent: String? = null,
)

/**
 * 群聊存储：group_roles / group_sessions / group_agents / group_messages。
 *
 * 群聊与 1:1 的 chat_messages 完全分开；这里不做整表快照，按行追加。
 */
/** 任务板上的一行：谁负责、什么状态、卡在谁身上。 */
data class GroupTaskRecord(
    val id: Long,
    val sessionId: Long,
    val title: String,
    val ownerAgentId: Long?,
    val state: String,
    val dependsOn: Long?,
    val dueAt: Long?,
    val warrantTier: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val dissentCount: Int = 0,
    val manifestDigest: String? = null,
)

/** 一条承诺：谁答应谁、答应什么、什么算完成。 */
data class GroupCommitmentRecord(
    val id: Long,
    val sessionId: Long,
    val fromAgent: Long,
    val toAgent: Long,
    val proposition: String,
    val satisfaction: String,
    val state: String,
    val dueAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 一条未知：blocking 的会阻止开工，回答后解除。 */
data class AgentUsage(
    val agentId: Long,
    val tokens: Long,
    val costMicros: Long,
    val calls: Int,
)

data class GroupUnknownRecord(
    val id: Long,
    val sessionId: Long,
    val question: String,
    val blocking: Boolean,
    val resolved: Boolean,
    val raisedBy: Long?,
    val createdAt: Long,
)
class GroupChatStore internal constructor(private val helper: LyraDatabase) {

    suspend fun roles(scenarioId: Long? = null): List<GroupRoleRecord> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<GroupRoleRecord>()
            val where = scenarioId?.let { "WHERE scenario_id = ?" }.orEmpty()
            val args = scenarioId?.let { arrayOf(it.toString()) }
            helper.readableDatabase.rawQuery(
                "SELECT id, scenario_id, name, brand_id, model, thinking_level, created_at, " +
                    "can_manage_roles, duty, can_manage_budget, can_coordinate_replies " +
                    "FROM group_roles $where ORDER BY created_at",
                args,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += GroupRoleRecord(
                        id = cursor.getLong(0),
                        scenarioId = cursor.getLong(1).takeIf { !cursor.isNull(1) },
                        name = cursor.getString(2),
                        brandId = cursor.getString(3),
                        model = cursor.getString(4),
                        thinkingLevel = cursor.getString(5),
                        createdAt = cursor.getLong(6),
                        canManageRoles = cursor.getInt(7) != 0,
                        duty = cursor.getString(8),
                        canManageBudget = cursor.getInt(9) != 0,
                        canCoordinateReplies = cursor.getInt(10) != 0,
                    )
                }
            }
            result
        }

    suspend fun createRole(
        scenarioId: Long? = null,
        name: String,
        brandId: String,
        model: String,
        thinkingLevel: String,
        canManageRoles: Boolean = false,
        duty: String = "",
        canManageBudget: Boolean = false,
        canCoordinateReplies: Boolean = false,
    ): Long = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow(
            "group_roles",
            null,
            ContentValues().apply {
                put("scenario_id", scenarioId)
                put("name", name.trim())
                put("brand_id", brandId)
                put("model", model)
                put("thinking_level", thinkingLevel)
                put("can_manage_roles", if (canManageRoles) 1 else 0)
                put("duty", duty.trim())
                put("can_manage_budget", if (canManageBudget) 1 else 0)
                put("can_coordinate_replies", if (canCoordinateReplies) 1 else 0)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    suspend fun updateRole(
        id: Long,
        name: String,
        brandId: String,
        model: String,
        thinkingLevel: String,
        duty: String = "",
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_roles",
            ContentValues().apply {
                put("name", name.trim())
                put("brand_id", brandId)
                put("model", model)
                put("thinking_level", thinkingLevel)
                put("duty", duty.trim())
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    suspend fun deleteRole(id: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.update(
            "group_agents",
            ContentValues().apply { putNull("role_id") },
            "role_id = ?",
            arrayOf(id.toString()),
        )
        db.delete("group_roles", "id = ?", arrayOf(id.toString()))
    }

    /**
     * 首次使用时预置常用角色；HR 角色带 can_manage_roles 权限。
     * 使用当前设置里的品牌/模型/思考等级，保证开箱可用。
     */
    suspend fun ensurePresetRoles(
        brandId: String,
        model: String,
        thinkingLevel: String,
    ) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val existing = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM group_roles", null).use { cursor ->
            while (cursor.moveToNext()) existing += cursor.getString(0)
        }
        data class Preset(
            val name: String,
            val duty: String,
            val manageRoles: Boolean = false,
            val manageBudget: Boolean = false,
            val coordinate: Boolean = false,
        )
        val presets = listOf(
            Preset("产品经理", "拆解需求、确定优先级、定义验收标准。"),
            Preset("工程师", "技术方案、代码实现、测试与排期。"),
            Preset("设计师", "交互流程、视觉规范、可用性检查。"),
            Preset("运营", "活动策划、内容运营、增长指标。"),
            Preset("人力资源部", "管理全局角色与成员；只有该角色能调用角色服务。", manageRoles = true),
            Preset(
                "财务",
                "管理群聊 token 预算，核算成本，协调每个 Agent 的回复策略与逻辑。",
                manageBudget = true,
                coordinate = true,
            ),
            Preset("质量保证", "制定测试计划、回归验证、风险与缺陷跟踪。"),
            Preset("项目经理", "拆分任务、跟踪进度、协调依赖与交付。", coordinate = true),
            Preset("安全合规", "检查权限、隐私、注入风险与合规边界。"),
            Preset("数据分析", "指标定义、数据分析、实验设计与解读。"),
            Preset("客户支持", "理解用户问题、给出可执行答复、升级风险。"),
            Preset("法务顾问", "条款审核、风险提示、合规建议。"),
        )
        presets.filter { it.name !in existing }.forEach { preset ->
            db.insertOrThrow(
                "group_roles",
                null,
                ContentValues().apply {
                    put("name", preset.name)
                    put("brand_id", brandId)
                    put("model", model)
                    put("thinking_level", thinkingLevel)
                    put("can_manage_roles", if (preset.manageRoles) 1 else 0)
                    put("duty", preset.duty)
                    put("can_manage_budget", if (preset.manageBudget) 1 else 0)
                    put("can_coordinate_replies", if (preset.coordinate) 1 else 0)
                    put("created_at", System.currentTimeMillis())
                },
            )
        }
    }

    /**
     * 场景是角色的父级；每个场景有独立角色子表。
     * 预置场景首次出现时补齐角色，不覆盖已有同名角色。
     */
    suspend fun ensurePresetScenarios(
        brandId: String,
        model: String,
        thinkingLevel: String,
    ) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        data class RoleSeed(
            val name: String,
            val duty: String,
            val manageRoles: Boolean = false,
            val manageBudget: Boolean = false,
            val coordinate: Boolean = false,
        )
        data class ScenarioSeed(
            val name: String,
            val description: String,
            val roles: List<RoleSeed>,
        )
        val seeds = listOf(
            ScenarioSeed(
                "工程",
                "需求、实现、测试与交付",
                listOf(
                    RoleSeed("产品经理", "拆解需求、优先级和验收标准。"),
                    RoleSeed("工程师", "技术方案、实现与排期。"),
                    RoleSeed("设计师", "交互、视觉与可用性。"),
                    RoleSeed("质量保证", "测试计划、回归与缺陷跟踪。"),
                    RoleSeed("安全合规", "权限、隐私与注入风险。"),
                    RoleSeed("项目经理", "任务拆分、进度与依赖。", coordinate = true),
                    RoleSeed("数据分析", "指标、实验与数据解读。"),
                    RoleSeed("人力资源部", "管理角色与成员。", manageRoles = true),
                    RoleSeed("财务", "token 预算、成本与回复策略。", manageBudget = true, coordinate = true),
                ),
            ),
            ScenarioSeed(
                "演绎",
                "角色扮演、剧情与舞台表达",
                listOf(
                    RoleSeed("导演", "整体调度、节奏与表演方向。"),
                    RoleSeed("编剧", "剧情结构、台词与冲突。"),
                    RoleSeed("演员", "角色塑造、情绪与即兴。"),
                    RoleSeed("旁白", "叙述、氛围与转场。"),
                    RoleSeed("美术指导", "视觉风格、场景与道具。"),
                    RoleSeed("音效师", "声音设计、配乐与节奏。"),
                ),
            ),
            ScenarioSeed(
                "产品",
                "需求发现、方案设计与验证",
                listOf(
                    RoleSeed("产品经理", "需求、优先级与验收。"),
                    RoleSeed("用户研究", "访谈、画像与洞察。"),
                    RoleSeed("数据分析", "指标、漏斗与实验。"),
                    RoleSeed("设计师", "原型、交互与视觉。"),
                    RoleSeed("运营", "上线、内容与增长。"),
                ),
            ),
            ScenarioSeed(
                "运营",
                "内容、活动与增长",
                listOf(
                    RoleSeed("内容运营", "选题、文案与发布节奏。"),
                    RoleSeed("活动策划", "活动机制、预算与执行。"),
                    RoleSeed("增长", "渠道、转化与留存。"),
                    RoleSeed("客户支持", "用户问题、答复与升级。"),
                ),
            ),
            ScenarioSeed(
                "研究",
                "调研、分析与合规",
                listOf(
                    RoleSeed("研究员", "资料检索、访谈与结论。"),
                    RoleSeed("数据分析", "数据清洗、统计与可视化。"),
                    RoleSeed("法务顾问", "条款、风险与合规。"),
                    RoleSeed("安全合规", "安全边界与隐私。"),
                ),
            ),
            ScenarioSeed(
                "管理",
                "组织、预算与治理",
                listOf(
                    RoleSeed("人力资源部", "角色、成员与权限。", manageRoles = true),
                    RoleSeed("财务", "预算、成本与回复策略。", manageBudget = true, coordinate = true),
                    RoleSeed("项目经理", "跨角色协调与交付。", coordinate = true),
                    RoleSeed("法务顾问", "合同、合规与风险。"),
                ),
            ),
        )
        val existingScenarios = mutableMapOf<String, Long>()
        db.rawQuery("SELECT id, name FROM group_scenarios", null).use { cursor ->
            while (cursor.moveToNext()) existingScenarios[cursor.getString(1)] = cursor.getLong(0)
        }
        seeds.forEach { seed ->
            val scenarioId = existingScenarios[seed.name] ?: db.insertOrThrow(
                "group_scenarios",
                null,
                ContentValues().apply {
                    put("name", seed.name)
                    put("description", seed.description)
                    put("created_at", System.currentTimeMillis())
                },
            )
            val existingRoles = mutableSetOf<String>()
            db.rawQuery(
                "SELECT name FROM group_roles WHERE scenario_id = ?",
                arrayOf(scenarioId.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) existingRoles += cursor.getString(0)
            }
            seed.roles.filter { it.name !in existingRoles }.forEach { role ->
                db.insertOrThrow(
                    "group_roles",
                    null,
                    ContentValues().apply {
                        put("scenario_id", scenarioId)
                        put("name", role.name)
                        put("brand_id", brandId)
                        put("model", model)
                        put("thinking_level", thinkingLevel)
                        put("can_manage_roles", if (role.manageRoles) 1 else 0)
                        put("duty", role.duty)
                        put("can_manage_budget", if (role.manageBudget) 1 else 0)
                        put("can_coordinate_replies", if (role.coordinate) 1 else 0)
                        put("created_at", System.currentTimeMillis())
                    },
                )
            }
        }
    }

    suspend fun ensureDefaultSession(): GroupSessionRecord = withContext(Dispatchers.IO) {
        latestSession() ?: run {
            val now = System.currentTimeMillis()
            val id = helper.writableDatabase.insertOrThrow(
                "group_sessions",
                null,
                ContentValues().apply {
                    put("title", "群聊")
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            GroupSessionRecord(id, "群聊", now, now)
        }
    }

    suspend fun latestSession(): GroupSessionRecord? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at, token_budget, tokens_used, reply_policy, " +
                "scenario_id, role_id FROM group_sessions ORDER BY updated_at DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            cursor.toSession()
        }
    }

    suspend fun session(id: Long): GroupSessionRecord? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at, token_budget, tokens_used, reply_policy, " +
                "scenario_id, role_id FROM group_sessions WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.toSession()
        }
    }

    suspend fun sessions(): List<GroupSessionRecord> = withContext(Dispatchers.IO) {
        val result = ArrayList<GroupSessionRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at, token_budget, tokens_used, reply_policy, " +
                "scenario_id, role_id FROM group_sessions ORDER BY updated_at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toSession()
        }
        result
    }

    suspend fun scenarios(): List<GroupScenarioRecord> = withContext(Dispatchers.IO) {
        val result = ArrayList<GroupScenarioRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, name, description, created_at FROM group_scenarios ORDER BY created_at",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += GroupScenarioRecord(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    description = cursor.getString(2),
                    createdAt = cursor.getLong(3),
                )
            }
        }
        result
    }

    suspend fun createScenario(name: String, description: String): GroupScenarioRecord =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = helper.writableDatabase.insertOrThrow(
                "group_scenarios",
                null,
                ContentValues().apply {
                    put("name", name.trim())
                    put("description", description.trim())
                    put("created_at", now)
                },
            )
            GroupScenarioRecord(id, name.trim(), description.trim(), now)
        }

    suspend fun updateScenario(id: Long, name: String, description: String) =
        withContext(Dispatchers.IO) {
            helper.writableDatabase.update(
                "group_scenarios",
                ContentValues().apply {
                    put("name", name.trim())
                    put("description", description.trim())
                },
                "id = ?",
                arrayOf(id.toString()),
            )
        }

    suspend fun deleteScenario(id: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.update(
            "group_agents",
            ContentValues().apply { putNull("role_id") },
            "role_id IN (SELECT id FROM group_roles WHERE scenario_id = ?)",
            arrayOf(id.toString()),
        )
        db.delete("group_roles", "scenario_id = ?", arrayOf(id.toString()))
        db.delete("group_scenarios", "id = ?", arrayOf(id.toString()))
    }

    suspend fun clearMessages(sessionId: Long): Int = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete(
            "group_messages",
            "session_id = ?",
            arrayOf(sessionId.toString()),
        )
    }

    suspend fun setSessionScenario(id: Long, scenarioId: Long?, roleId: Long?) =
        withContext(Dispatchers.IO) {
            helper.writableDatabase.update(
                "group_sessions",
                ContentValues().apply {
                    put("scenario_id", scenarioId)
                    put("role_id", roleId)
                },
                "id = ?",
                arrayOf(id.toString()),
            )
        }

    suspend fun createSession(title: String): GroupSessionRecord = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = helper.writableDatabase.insertOrThrow(
            "group_sessions",
            null,
            ContentValues().apply {
                put("title", title.trim().ifBlank { "群聊" })
                put("created_at", now)
                put("updated_at", now)
            },
        )
        // 建群即预置职位：董事长/总经理/财务/记录者，不依赖后续迁移
        seedPositions(id)
        GroupSessionRecord(id, title.trim().ifBlank { "群聊" }, now, now)
    }

    suspend fun renameSession(id: Long, title: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_sessions",
            ContentValues().apply {
                put("title", title.trim())
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    suspend fun deleteSession(id: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.delete("group_agents", "session_id = ?", arrayOf(id.toString()))
        db.delete("group_messages", "session_id = ?", arrayOf(id.toString()))
        db.delete("group_sessions", "id = ?", arrayOf(id.toString()))
    }

    suspend fun recordUsage(
        sessionId: Long,
        agentId: Long?,
        providerId: String?,
        model: String?,
        inputTokens: Int,
        outputTokens: Int,
        costMicros: Long,
    ) = withContext(Dispatchers.IO) {
        if (inputTokens <= 0 && outputTokens <= 0) return@withContext
        helper.writableDatabase.insertOrThrow(
            "group_usage",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("agent_id", agentId)
                put("provider_id", providerId)
                put("model", model)
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens)
                put("cost_micros", costMicros)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    /** 按成员汇总：token、金额、调用次数。 */
    suspend fun usageByAgent(sessionId: Long): List<AgentUsage> = withContext(Dispatchers.IO) {
        val out = ArrayList<AgentUsage>()
        helper.readableDatabase.rawQuery(
            """
            SELECT agent_id,
                   COALESCE(SUM(input_tokens + output_tokens), 0),
                   COALESCE(SUM(cost_micros), 0),
                   COUNT(*)
            FROM group_usage WHERE session_id = ? AND agent_id IS NOT NULL
            GROUP BY agent_id
            """.trimIndent(),
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += AgentUsage(
                    agentId = cursor.getLong(0),
                    tokens = cursor.getLong(1),
                    costMicros = cursor.getLong(2),
                    calls = cursor.getInt(3),
                )
            }
        }
        out
    }

    suspend fun usageByProvider(days: Int = 7): List<UsageProviderSummary> =
        withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - days.coerceIn(1, 30) * 86_400_000L
            val result = ArrayList<UsageProviderSummary>()
            helper.readableDatabase.rawQuery(
                """
                SELECT provider_id, model,
                       COALESCE(SUM(input_tokens), 0),
                       COALESCE(SUM(output_tokens), 0),
                       COALESCE(SUM(cost_micros), 0),
                       COUNT(*)
                FROM group_usage
                WHERE created_at >= ?
                GROUP BY provider_id, model
                ORDER BY SUM(input_tokens + output_tokens) DESC
                """.trimIndent(),
                arrayOf(since.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += UsageProviderSummary(
                        providerId = cursor.getString(0) ?: "unknown",
                        model = cursor.getString(1) ?: "—",
                        inputTokens = cursor.getLong(2),
                        outputTokens = cursor.getLong(3),
                        costMicros = cursor.getLong(4),
                        calls = cursor.getInt(5),
                    )
                }
            }
            result
        }

    suspend fun usageSummary(days: Int = 7): ChatUsageSummary = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - days.coerceIn(1, 30) * 86_400_000L
        helper.readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(input_tokens), 0),
                   COALESCE(SUM(output_tokens), 0),
                   COALESCE(SUM(cost_micros), 0),
                   COUNT(*),
                   COUNT(DISTINCT session_id)
            FROM group_usage WHERE created_at >= ?
            """.trimIndent(),
            arrayOf(since.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext ChatUsageSummary()
            ChatUsageSummary(
                inputTokens = cursor.getLong(0),
                outputTokens = cursor.getLong(1),
                costMicros = cursor.getLong(2),
                calls = cursor.getInt(3),
                sessions = cursor.getInt(4),
            )
        }
    }

    suspend fun addSessionTokens(id: Long, tokens: Long) = withContext(Dispatchers.IO) {
        if (tokens <= 0) return@withContext
        helper.writableDatabase.execSQL(
            "UPDATE group_sessions SET tokens_used = tokens_used + ?, " +
                "updated_at = ? WHERE id = ?",
            arrayOf(tokens, System.currentTimeMillis(), id),
        )
    }

    suspend fun setBudget(id: Long, tokens: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_sessions",
            ContentValues().apply { put("token_budget", tokens.coerceAtLeast(0)) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    suspend fun setReplyPolicy(id: Long, policy: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_sessions",
            ContentValues().apply { put("reply_policy", policy.trim()) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    private fun android.database.Cursor.toSession(): GroupSessionRecord = GroupSessionRecord(
        id = getLong(0),
        title = getString(1),
        createdAt = getLong(2),
        updatedAt = getLong(3),
        tokenBudget = getLong(4),
        tokensUsed = getLong(5),
        replyPolicy = getString(6),
        scenarioId = getLong(7).takeIf { !isNull(7) },
        roleId = getLong(8).takeIf { !isNull(8) },
    )

    /** 职务表：默认四席 + 用户自建；未初始化时返回默认值。 */
    suspend fun positions(sessionId: Long): List<com.eloykim.lyra.attention.Position> = withContext(Dispatchers.IO) {
        val out = ArrayList<com.eloykim.lyra.attention.Position>()
        helper.readableDatabase.rawQuery(
            "SELECT key, title, capabilities, system, sort_order FROM group_positions WHERE session_id = ? ORDER BY sort_order",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += com.eloykim.lyra.attention.Position(
                    key = cursor.getString(0),
                    title = cursor.getString(1),
                    capabilities = cursor.getString(2).split("|").filter { it.isNotBlank() },
                    system = cursor.getInt(3) == 1,
                    order = cursor.getInt(4),
                )
            }
        }
        if (out.isEmpty()) com.eloykim.lyra.attention.Positions.defaults else out
    }

    /** 写入默认职务（幂等）。 */
    suspend fun seedPositions(sessionId: Long) = withContext(Dispatchers.IO) {
        // 预置会随版本调整：先清掉旧的系统职位再写，保证与代码一致
        helper.writableDatabase.delete("group_positions", "session_id = ? AND system = 1", arrayOf(sessionId.toString()))
        com.eloykim.lyra.attention.Positions.defaults.forEach { position ->
            helper.writableDatabase.insertWithOnConflict(
                "group_positions", null,
                android.content.ContentValues().apply {
                    put("session_id", sessionId)
                    put("key", position.key)
                    put("title", position.title)
                    put("capabilities", position.capabilities.joinToString("|"))
                    put("system", if (position.system) 1 else 0)
                    put("sort_order", position.order)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
    }
    /** 读取持久化的核心座位；为空表示尚未重建。 */
    suspend fun seats(sessionId: Long): Map<String, Long> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, Long>()
        helper.readableDatabase.rawQuery(
            "SELECT seat, agent_id FROM group_seats WHERE session_id = ?",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) map[cursor.getString(0)] = cursor.getLong(1)
        }
        map
    }

    /** 重建座位：总经理=第一成员，财务=第二成员，记录者=程序（-1）。 */
    suspend fun rebuildSeats(sessionId: Long, memberIds: List<Long>) = withContext(Dispatchers.IO) {
        val ordered = memberIds.distinct().sorted()
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("group_seats", "session_id = ?", arrayOf(sessionId.toString()))
            val assignments = LinkedHashMap<String, Long>()
            ordered.getOrNull(0)?.let { assignments["GENERAL_MANAGER"] = it }
            ordered.getOrNull(1)?.let { assignments["FINANCE"] = it }
            assignments["RECORDER"] = -1L
            assignments.forEach { (seat, agentId) ->
                db.insertWithOnConflict(
                    "group_seats", null,
                    android.content.ContentValues().apply {
                        put("session_id", sessionId)
                        put("seat", seat)
                        put("agent_id", agentId)
                        put("updated_at", now)
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    suspend fun tasks(sessionId: Long): List<GroupTaskRecord> = withContext(Dispatchers.IO) {
        val out = ArrayList<GroupTaskRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, session_id, title, owner_agent_id, state, depends_on, due_at, warrant_tier, created_at, updated_at, dissent_count, manifest_digest " +
                "FROM group_tasks WHERE session_id = ? ORDER BY id",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += GroupTaskRecord(
                    id = cursor.getLong(0),
                    sessionId = cursor.getLong(1),
                    title = cursor.getString(2),
                    ownerAgentId = if (cursor.isNull(3)) null else cursor.getLong(3),
                    state = cursor.getString(4),
                    dependsOn = if (cursor.isNull(5)) null else cursor.getLong(5),
                    dueAt = if (cursor.isNull(6)) null else cursor.getLong(6),
                    warrantTier = cursor.getString(7),
                    createdAt = cursor.getLong(8),
                    updatedAt = cursor.getLong(9),
                    dissentCount = cursor.getInt(10),
                )
            }
        }
        out
    }

    suspend fun createTask(
        sessionId: Long,
        title: String,
        ownerAgentId: Long? = null,
        dueAt: Long? = null,
        warrantTier: String? = null,
        manifestDigest: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.insertOrThrow(
            "group_tasks",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("title", title.trim())
                put("owner_agent_id", ownerAgentId)
                put("state", if (ownerAgentId == null) "proposed" else "claimed")
                put("due_at", dueAt)
                put("warrant_tier", warrantTier)
                put("manifest_digest", manifestDigest)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    /** 记一票反对：用于虚假共识检测。 */
    suspend fun addDissent(taskId: Long): Int = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE group_tasks SET dissent_count = dissent_count + 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), taskId),
        )
        helper.readableDatabase.rawQuery(
            "SELECT dissent_count FROM group_tasks WHERE id = ?",
            arrayOf(taskId.toString()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    suspend fun moveTask(taskId: Long, state: String): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_tasks",
            ContentValues().apply {
                put("state", state)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(taskId.toString()),
        ) > 0
    }

    suspend fun claimTask(taskId: Long, agentId: Long): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_tasks",
            ContentValues().apply {
                put("owner_agent_id", agentId)
                put("state", "claimed")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ? AND owner_agent_id IS NULL",
            arrayOf(taskId.toString()),
        ) > 0
    }
    suspend fun commitments(sessionId: Long): List<GroupCommitmentRecord> = withContext(Dispatchers.IO) {
        val out = ArrayList<GroupCommitmentRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, session_id, from_agent, to_agent, proposition, satisfaction, state, due_at, created_at, updated_at " +
                "FROM group_commitments WHERE session_id = ? ORDER BY id",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += GroupCommitmentRecord(
                    id = cursor.getLong(0),
                    sessionId = cursor.getLong(1),
                    fromAgent = cursor.getLong(2),
                    toAgent = cursor.getLong(3),
                    proposition = cursor.getString(4),
                    satisfaction = cursor.getString(5),
                    state = cursor.getString(6),
                    dueAt = if (cursor.isNull(7)) null else cursor.getLong(7),
                    createdAt = cursor.getLong(8),
                    updatedAt = cursor.getLong(9),
                )
            }
        }
        out
    }

    suspend fun postCommitment(
        sessionId: Long,
        fromAgent: Long,
        toAgent: Long,
        proposition: String,
        satisfaction: String,
        dueAt: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.insertOrThrow(
            "group_commitments",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("from_agent", fromAgent)
                put("to_agent", toAgent)
                put("proposition", proposition)
                put("satisfaction", satisfaction)
                put("state", "posted")
                put("due_at", dueAt)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    suspend fun setCommitmentState(id: Long, state: String): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_commitments",
            ContentValues().apply {
                put("state", state)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString()),
        ) > 0
    }
    suspend fun raiseUnknown(
        sessionId: Long,
        question: String,
        blocking: Boolean = true,
        raisedBy: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow(
            "group_unknowns",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("question", question.trim())
                put("blocking", if (blocking) 1 else 0)
                put("resolved", 0)
                put("raised_by", raisedBy)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    suspend fun resolveUnknown(id: Long): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_unknowns",
            ContentValues().apply {
                put("resolved", 1)
                put("resolved_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id.toString()),
        ) > 0
    }

    suspend fun unknowns(sessionId: Long): List<GroupUnknownRecord> = withContext(Dispatchers.IO) {
        val out = ArrayList<GroupUnknownRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, session_id, question, blocking, resolved, raised_by, created_at " +
                "FROM group_unknowns WHERE session_id = ? ORDER BY id",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += GroupUnknownRecord(
                    id = cursor.getLong(0),
                    sessionId = cursor.getLong(1),
                    question = cursor.getString(2),
                    blocking = cursor.getInt(3) == 1,
                    resolved = cursor.getInt(4) == 1,
                    raisedBy = if (cursor.isNull(5)) null else cursor.getLong(5),
                    createdAt = cursor.getLong(6),
                )
            }
        }
        out
    }
    suspend fun appendManifest(sessionId: Long, digest: String, payload: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow(
            "group_manifests",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("digest", digest)
                put("payload", payload)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    /** 最近一次团队清单：返回 digest 与 payload。 */
    suspend fun latestManifest(sessionId: Long): Pair<String, String>? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT digest, payload FROM group_manifests WHERE session_id = ? ORDER BY id DESC LIMIT 1",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
        }
    }
    suspend fun createStaffing(
        sessionId: Long,
        positionKey: String,
        nickname: String,
        requestedBy: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.insertOrThrow(
            "group_staffing", null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("position_key", positionKey)
                put("nickname", nickname)
                put("requested_by", requestedBy)
                put("state", "proposed")
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    /** 部门审批：finance / resource / hr；三项齐全后由人力部创建成员。 */
    suspend fun approveStaffing(id: Long, department: String): Boolean = withContext(Dispatchers.IO) {
        val column = when (department) {
            "finance" -> "finance_ok"
            "resource" -> "resource_ok"
            "hr" -> "hr_ok"
            else -> return@withContext false
        }
        helper.writableDatabase.execSQL(
            "UPDATE group_staffing SET $column = 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
        true
    }

    suspend fun staffingRequest(id: Long): Map<String, Any?>? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT session_id, position_key, nickname, finance_ok, resource_ok, hr_ok, agent_id FROM group_staffing WHERE id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            mapOf(
                "sessionId" to cursor.getLong(0),
                "positionKey" to cursor.getString(1),
                "nickname" to cursor.getString(2),
                "financeOk" to (cursor.getInt(3) == 1),
                "resourceOk" to (cursor.getInt(4) == 1),
                "hrOk" to (cursor.getInt(5) == 1),
                "agentId" to if (cursor.isNull(6)) null else cursor.getLong(6),
            )
        }
    }

    suspend fun markStaffingCreated(id: Long, agentId: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE group_staffing SET state = 'created', agent_id = ?, updated_at = ? WHERE id = ?",
            arrayOf(agentId, System.currentTimeMillis(), id),
        )
    }
    suspend fun agents(sessionId: Long): List<GroupAgentRecord> = withContext(Dispatchers.IO) {
        val result = ArrayList<GroupAgentRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT id, session_id, role_id, nickname, identity, created_at, position_key, tokens_used, token_quota " +
                "FROM group_agents WHERE session_id = ? ORDER BY created_at",
            arrayOf(sessionId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += GroupAgentRecord(
                    id = cursor.getLong(0),
                    sessionId = cursor.getLong(1),
                    roleId = cursor.getLong(2).takeIf { !cursor.isNull(2) },
                    nickname = cursor.getString(3),
                    identity = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                    positionKey = if (cursor.isNull(6)) null else cursor.getString(6),
                    tokensUsed = cursor.getLong(7),
                    quotaTokens = cursor.getLong(8),
                )
            }
        }
        result
    }

    /** 给成员绑定/改绑职位（决定接力顺序）。 */
    /** 累加某成员的 token 消耗（预算切片用）。 */
    suspend fun addAgentTokens(agentId: Long, delta: Long) = withContext(Dispatchers.IO) {
        if (delta <= 0) return@withContext
        helper.writableDatabase.execSQL(
            "UPDATE group_agents SET tokens_used = tokens_used + ? WHERE id = ?",
            arrayOf(delta, agentId),
        )
    }

    suspend fun setAgentQuota(agentId: Long, tokens: Long): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_agents",
            ContentValues().apply { put("token_quota", tokens) },
            "id = ?",
            arrayOf(agentId.toString()),
        ) > 0
    }

    suspend fun setAgentPosition(agentId: Long, positionKey: String?): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_agents",
            ContentValues().apply { put("position_key", positionKey) },
            "id = ?",
            arrayOf(agentId.toString()),
        ) > 0
    }

    suspend fun addAgent(
        sessionId: Long,
        roleId: Long?,
        nickname: String,
        identity: String,
        positionKey: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertOrThrow(
            "group_agents",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("role_id", roleId)
                put("nickname", nickname.trim())
                put("identity", identity.trim())
                put("position_key", positionKey)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    suspend fun updateAgent(
        agentId: Long,
        roleId: Long?,
        nickname: String,
        identity: String,
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.update(
            "group_agents",
            ContentValues().apply {
                put("role_id", roleId)
                put("nickname", nickname.trim())
                put("identity", identity.trim())
            },
            "id = ?",
            arrayOf(agentId.toString()),
        )
    }

    suspend fun removeAgent(agentId: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("group_agents", "id = ?", arrayOf(agentId.toString()))
    }

    suspend fun messages(sessionId: Long, limit: Int = 100): List<GroupMessageRecord> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<GroupMessageRecord>()
            helper.readableDatabase.rawQuery(
                "SELECT id, session_id, role, author_id, author_name, content, mentions, created_at, " +
                    "quoted_id, quoted_author, quoted_content " +
                    "FROM group_messages WHERE session_id = ? " +
                    "ORDER BY created_at DESC LIMIT ?",
                arrayOf(sessionId.toString(), limit.coerceIn(1, 500).toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += GroupMessageRecord(
                        id = cursor.getLong(0),
                        sessionId = cursor.getLong(1),
                        role = cursor.getString(2),
                        authorId = cursor.getLong(3).takeIf { !cursor.isNull(3) },
                        authorName = cursor.getString(4),
                        content = cursor.getString(5),
                        mentions = cursor.getString(6),
                        createdAt = cursor.getLong(7),
                        quotedId = cursor.getLong(8).takeIf { !cursor.isNull(8) },
                        quotedAuthor = cursor.getString(9),
                        quotedContent = cursor.getString(10),
                    )
                }
            }
            result.reversed()
        }

    suspend fun addMessage(
        sessionId: Long,
        role: String,
        authorId: Long?,
        authorName: String,
        content: String,
        mentions: String,
        quotedId: Long? = null,
        quotedAuthor: String? = null,
        quotedContent: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val id = db.insertOrThrow(
            "group_messages",
            null,
            ContentValues().apply {
                put("session_id", sessionId)
                put("role", role)
                put("author_id", authorId)
                put("author_name", authorName)
                put("content", content)
                put("mentions", mentions)
                put("quoted_id", quotedId)
                put("quoted_author", quotedAuthor)
                put("quoted_content", quotedContent)
                put("created_at", System.currentTimeMillis())
            },
        )
        db.update(
            "group_sessions",
            ContentValues().apply { put("updated_at", System.currentTimeMillis()) },
            "id = ?",
            arrayOf(sessionId.toString()),
        )
        id
    }
}
