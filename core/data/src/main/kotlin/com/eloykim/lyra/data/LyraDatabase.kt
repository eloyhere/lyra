package com.eloykim.lyra.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地库：操作历史 + 应用目录 + 模型目录 + 画像事实。
 *
 * v2 起，模型目录和应用信息进 SQLite，不再写死在代码里 ——
 * 代码里只保留一份「出厂种子」，随时可被 refreshCatalog() 覆盖。
 */
internal class LyraDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createHistory(db)
        createV2(db)
        createEvents(db)
        createFrames(db)
        createChat(db)
        createAddresses(db)
        createGroupChat(db)
        createServiceTables(db)
        seedCatalog(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 每次打开都同步服务 -> 表登记，新增服务/表不需要再改版本号。
        createServiceTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v33：入职申请——部长提议，财务/资源审批，人力部创建。
        if (oldVersion < 33) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_staffing(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    position_key TEXT NOT NULL,
                    nickname TEXT NOT NULL,
                    requested_by INTEGER,
                    state TEXT NOT NULL,
                    finance_ok INTEGER NOT NULL DEFAULT 0,
                    resource_ok INTEGER NOT NULL DEFAULT 0,
                    hr_ok INTEGER NOT NULL DEFAULT 0,
                    agent_id INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        // v32：成员单独预算额度（0 表示按均分份额）。
        if (oldVersion < 32) {
            db.execSQL("ALTER TABLE group_agents ADD COLUMN token_quota INTEGER NOT NULL DEFAULT 0")
        }
        // v31：成员 token 消耗（预算切片用）。
        if (oldVersion < 31) {
            db.execSQL("ALTER TABLE group_agents ADD COLUMN tokens_used INTEGER NOT NULL DEFAULT 0")
        }
        // v30：任务记录派活时的团队摘要，用于检测"派活后团队变了"。
        if (oldVersion < 30) {
            db.execSQL("ALTER TABLE group_tasks ADD COLUMN manifest_digest TEXT")
        }
        // v29：团队清单快照（成员 + 职位 + 座位），用于检测"目录过期"。
        if (oldVersion < 29) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_manifests(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    digest TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }
        // v28：成员绑定职位（决定接力发言顺序）。
        if (oldVersion < 28) {
            db.execSQL("ALTER TABLE group_agents ADD COLUMN position_key TEXT")
        }
        // v27：反对票计数（虚假共识检测）。
        if (oldVersion < 27) {
            db.execSQL("ALTER TABLE group_tasks ADD COLUMN dissent_count INTEGER NOT NULL DEFAULT 0")
        }
        // v26：未知（blocking 的必须显式，回答后解除）。
        if (oldVersion < 26) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_unknowns(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    question TEXT NOT NULL,
                    blocking INTEGER NOT NULL,
                    resolved INTEGER NOT NULL DEFAULT 0,
                    raised_by INTEGER,
                    created_at INTEGER NOT NULL,
                    resolved_at INTEGER
                )
                """.trimIndent(),
            )
        }
        // v25：承诺记录（posted / grounded / discharged / breached）。
        if (oldVersion < 25) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_commitments(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    from_agent INTEGER NOT NULL,
                    to_agent INTEGER NOT NULL,
                    proposition TEXT NOT NULL,
                    satisfaction TEXT NOT NULL,
                    state TEXT NOT NULL,
                    due_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_commitments_session ON group_commitments(session_id, state)")
        }
        // v24：任务板——群聊的共享状态（负责人/状态/依赖/凭据等级）。
        if (oldVersion < 24) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_tasks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    title TEXT NOT NULL,
                    owner_agent_id INTEGER,
                    state TEXT NOT NULL,
                    depends_on INTEGER,
                    due_at INTEGER,
                    warrant_tier TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_group_tasks_session ON group_tasks(session_id, state)")
        }
        // v23：职务表——职务是数据，不是枚举；默认四席由代码种子写入。
        if (oldVersion < 23) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_positions(
                    session_id INTEGER NOT NULL,
                    key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    capabilities TEXT NOT NULL,
                    system INTEGER NOT NULL,
                    sort_order INTEGER NOT NULL,
                    PRIMARY KEY(session_id, key)
                )
                """.trimIndent(),
            )
        }
        // v22：核心座位持久化（总经理/财务/记录者），存量群聊按新模型重建。
        if (oldVersion < 22) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_seats(
                    session_id INTEGER NOT NULL,
                    seat TEXT NOT NULL,
                    agent_id INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(session_id, seat)
                )
                """.trimIndent(),
            )
        }
        if (oldVersion < 2) {
            createV2(db)
        }
        // v3：目录种子更新（DeepSeek v4.1 等）。模型行用 REPLACE，保证旧数据被覆盖。
        if (oldVersion < 3) {
            seedCatalog(db)
        }
        // v4：模型目录结构变化（旧模型标记 deprecated）。清空后重播种，避免残留过期行。
        if (oldVersion < 4) {
            db.delete("llm_models", null, null)
            seedCatalog(db)
        }
        // v5：AMP 只追加事件日志（X-1）。表一旦建立，UPDATE/DELETE 被触发器拒绝。
        if (oldVersion < 5) {
            createEvents(db)
        }
        // v6：给实时拉取的模型补价格元数据（合并语义后价格不会再被覆盖）。
        if (oldVersion < 6) {
            seedCatalog(db)
        }
        // v7：AMP 压缩。删除触发器从「一律禁止」改为「只允许删除已被 checkpoint 承诺的前缀」。
        if (oldVersion < 7) {
            db.execSQL("DROP TRIGGER IF EXISTS amp_events_no_delete")
            createCompactionPolicy(db)
        }
        // v11：核心座位持久化（总经理/财务/记录者），存量群聊按新模型重建。
        if (oldVersion < 11) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_seats(
                    session_id INTEGER NOT NULL,
                    seat TEXT NOT NULL,
                    agent_id INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(session_id, seat)
                )
                """.trimIndent(),
            )
        }
        // v10：帧上记录僵局状态。
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE frames ADD COLUMN impasse TEXT")
        }
        // v9：AMP Frame 落库（帧链）。
        if (oldVersion < 9) {
            createFrames(db)
        }
        // v8：修正触发器里 OLD.seq 的写法（v7 用了表名，SQLite 在 BEFORE DELETE 里解析失败）。
        if (oldVersion < 8) {
            db.execSQL("DROP TRIGGER IF EXISTS amp_events_no_delete")
            createCompactionPolicy(db)
        }
        // v11：聊天记录持久化（会话 + 消息）。
        if (oldVersion < 11) {
            createChat(db)
        }
        // v12：消息单选表情。
        if (oldVersion == 11) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN reaction TEXT")
        }
        // v13：地址库；同时登记各服务对应的表结构，方便迁移与自检。
        if (oldVersion < 13) {
            createAddresses(db)
            createServiceTables(db)
        }
        // v14：消息引用（QQ/微信式引用，不把原文塞进输入框）。
        if (oldVersion in 11..13) {
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN quoted_id INTEGER")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN quoted_role TEXT")
            db.execSQL("ALTER TABLE chat_messages ADD COLUMN quoted_content TEXT")
        }
        // v15：ChatSession 元数据 + 聊天 token 账本。
        if (oldVersion in 11..14) {
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN provider_id TEXT")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN model TEXT")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN thinking_level TEXT")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN input_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN output_tokens INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN cost_micros INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN message_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chat_conversations ADD COLUMN last_message_at INTEGER")
        }
        if (oldVersion < 15) {
            createChatUsage(db)
        }
        // v16：群聊（会话/成员/消息/全局角色）。
        if (oldVersion < 16) {
            createGroupChat(db)
        }
        // v17：群聊消息引用。
        if (oldVersion == 16) {
            db.execSQL("ALTER TABLE group_messages ADD COLUMN quoted_id INTEGER")
            db.execSQL("ALTER TABLE group_messages ADD COLUMN quoted_author TEXT")
            db.execSQL("ALTER TABLE group_messages ADD COLUMN quoted_content TEXT")
        }
        // v18：角色服务权限（只有人力资源部可以管理角色）。
        if (oldVersion == 17) {
            db.execSQL(
                "ALTER TABLE group_roles ADD COLUMN can_manage_roles INTEGER NOT NULL DEFAULT 0",
            )
        }
        // v19：角色职责 + 财务预算/协调；群会话预算与回复策略。
        if (oldVersion == 18) {
            db.execSQL("ALTER TABLE group_roles ADD COLUMN duty TEXT NOT NULL DEFAULT ''")

            db.execSQL(
                "ALTER TABLE group_roles ADD COLUMN can_manage_budget INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE group_roles ADD COLUMN can_coordinate_replies INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE group_sessions ADD COLUMN token_budget INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE group_sessions ADD COLUMN tokens_used INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                "ALTER TABLE group_sessions ADD COLUMN reply_policy TEXT NOT NULL DEFAULT ''",
            )
        }
        // v20：场景与角色绑定；群会话记住当前场景/角色。
        if (oldVersion == 19) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_scenarios(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("ALTER TABLE group_roles ADD COLUMN scenario_id INTEGER")
            db.execSQL("ALTER TABLE group_sessions ADD COLUMN scenario_id INTEGER")
            db.execSQL("ALTER TABLE group_sessions ADD COLUMN role_id INTEGER")
            val now = System.currentTimeMillis()
            val scenarioId = db.insertOrThrow(
                "group_scenarios",
                null,
                ContentValues().apply {
                    put("name", "工程")
                    put("description", "需求、实现、测试与交付")
                    put("created_at", now)
                },
            )
            db.execSQL(
                "UPDATE group_roles SET scenario_id = ? WHERE scenario_id IS NULL",
                arrayOf(scenarioId),
            )
        }
        // v21：群聊 token/费用账本。
        if (oldVersion == 20) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_usage(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id INTEGER NOT NULL,
                    agent_id INTEGER,
                    provider_id TEXT,
                    model TEXT,
                    input_tokens INTEGER NOT NULL DEFAULT 0,
                    output_tokens INTEGER NOT NULL DEFAULT 0,
                    cost_micros INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_group_usage_session " +
                    "ON group_usage(session_id, created_at)",
            )
        }
    }

    /** 从京东/美团等页面识别出的收货地址。 */
    private fun createAddresses(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS addresses(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                phone TEXT,
                region TEXT,
                detail TEXT NOT NULL,
                source_app TEXT,
                raw TEXT,
                is_default INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_addresses_updated ON addresses(updated_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_addresses_phone ON addresses(phone)")
    }

    /** 服务 -> SQLite 表的登记表；新增持久化服务时在这里同步。 */
    private fun createServiceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS service_tables(
                service_id TEXT PRIMARY KEY,
                tables TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        val now = System.currentTimeMillis()
        val mappings = mapOf(
            "chat_message" to "chat_conversations,chat_messages",
            "chat_session" to "chat_conversations,chat_usage",
            "group_chat" to "group_scenarios,group_roles,group_sessions,group_agents,group_messages,group_usage",
            "address" to "addresses",
            "application" to "packages",
            "provider" to "llm_providers,llm_models",
            "usage" to "runs,steps",
            "impression" to "profile_facts,runs,steps",
            "task" to "runs,steps",
        )
        mappings.forEach { (serviceId, tables) ->
            db.insertWithOnConflict(
                "service_tables",
                null,
                ContentValues().apply {
                    put("service_id", serviceId)
                    put("tables", tables)
                    put("schema_version", VERSION)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    /** 聊天会话与消息：供聊天页恢复，以及 ChatMessageStore 供服务层查询。 */
    private fun createChat(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_conversations(
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                provider_id TEXT,
                model TEXT,
                thinking_level TEXT,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                cost_micros INTEGER NOT NULL DEFAULT 0,
                message_count INTEGER NOT NULL DEFAULT 0,
                last_message_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_messages(
                id INTEGER PRIMARY KEY,
                conversation_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                reasoning TEXT,
                reaction TEXT,
                quoted_id INTEGER,
                quoted_role TEXT,
                quoted_content TEXT,
                pending INTEGER NOT NULL DEFAULT 0,
                error INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_messages_conv " +
                "ON chat_messages(conversation_id, created_at)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_messages_time " +
                "ON chat_messages(created_at)",
        )
        createChatUsage(db)
    }

    /** 群聊：全局角色、会话、成员、消息。 */
    private fun createGroupChat(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_scenarios(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_roles(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scenario_id INTEGER,
                name TEXT NOT NULL,
                brand_id TEXT NOT NULL,
                model TEXT NOT NULL,
                thinking_level TEXT NOT NULL,
                can_manage_roles INTEGER NOT NULL DEFAULT 0,
                duty TEXT NOT NULL DEFAULT '',
                can_manage_budget INTEGER NOT NULL DEFAULT 0,
                can_coordinate_replies INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                token_budget INTEGER NOT NULL DEFAULT 0,
                tokens_used INTEGER NOT NULL DEFAULT 0,
                reply_policy TEXT NOT NULL DEFAULT '',
                scenario_id INTEGER,
                role_id INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_agents(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                role_id INTEGER,
                nickname TEXT NOT NULL,
                identity TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_messages(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                role TEXT NOT NULL,
                author_id INTEGER,
                author_name TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL,
                mentions TEXT NOT NULL DEFAULT '',
                quoted_id INTEGER,
                quoted_author TEXT,
                quoted_content TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_agents_session " +
                "ON group_agents(session_id)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_messages_session " +
                "ON group_messages(session_id, created_at)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_usage(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                agent_id INTEGER,
                provider_id TEXT,
                model TEXT,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                cost_micros INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_group_usage_session " +
                "ON group_usage(session_id, created_at)",
        )
    }

    private fun createChatUsage(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_usage(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id INTEGER NOT NULL,
                provider_id TEXT,
                model TEXT,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                cost_micros INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_chat_usage_session " +
                "ON chat_usage(conversation_id, created_at)",
        )
    }

    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS runs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                status TEXT NOT NULL DEFAULT 'running',
                summary TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS steps(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                kind TEXT NOT NULL,
                action TEXT,
                package_name TEXT,
                target_label TEXT,
                node_id INTEGER,
                ok INTEGER,
                code TEXT,
                message TEXT,
                started_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_steps_run ON steps(run_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_steps_pkg ON steps(package_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_runs_started ON runs(started_at)")
    }

    private fun createV2(db: SQLiteDatabase) {
        // 应用目录：包名是主键，记录版本与使用次数
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS packages(
                package_name TEXT PRIMARY KEY,
                label TEXT,
                version_name TEXT,
                version_code INTEGER,
                is_system INTEGER NOT NULL DEFAULT 0,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                launch_count INTEGER NOT NULL DEFAULT 0,
                step_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        // 模型服务商
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS llm_providers(
                id TEXT PRIMARY KEY,
                label TEXT NOT NULL,
                base_url TEXT,
                docs_url TEXT,
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        // 模型清单（多对一 provider）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS llm_models(
                provider_id TEXT NOT NULL,
                id TEXT NOT NULL,
                label TEXT,
                context_window INTEGER,
                supports_tools INTEGER NOT NULL DEFAULT 1,
                supports_vision INTEGER NOT NULL DEFAULT 0,
                input_price REAL,
                output_price REAL,
                deprecated INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(provider_id, id)
            )
            """.trimIndent(),
        )

        // 画像事实：键值 + 置信度，给模型注入用
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS profile_facts(
                key TEXT PRIMARY KEY,
                value TEXT,
                confidence REAL NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun seedCatalog(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()

        Catalog.providers.forEach { provider ->
            val values = ContentValues().apply {
                put("id", provider.id)
                put("label", provider.label)
                put("base_url", provider.baseUrl)
                put("docs_url", provider.docsUrl)
                put("sort_order", provider.sort)
                put("updated_at", now)
            }
            // 已存在则只更新展示信息，不覆盖用户可能改过的 base_url
            db.insertWithOnConflict("llm_providers", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }

        Catalog.models.forEach { model ->
            val values = ContentValues().apply {
                put("provider_id", model.providerId)
                put("id", model.id)
                put("label", model.label)
                put("context_window", model.contextWindow)
                put("supports_tools", if (model.supportsTools) 1 else 0)
                put("supports_vision", if (model.supportsVision) 1 else 0)
                put("input_price", model.inputPrice)
                put("output_price", model.outputPrice)
                put("sort_order", model.sort)
                put("deprecated", if (model.deprecated) 1 else 0)
                put("updated_at", now)
            }
            db.insertWithOnConflict("llm_models", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        val meta = ContentValues().apply {
            put("key", "catalog_version")
            put("value", Catalog.VERSION.toString())
            put("confidence", 1.0)
            put("updated_at", now)
        }
        db.insertWithOnConflict("profile_facts", null, meta, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** AMP 事件日志：只追加，哈希链在 Write 时计算（见 AmpEvent/EventLog）。 */
    private fun createEvents(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS amp_events(
                seq INTEGER PRIMARY KEY,
                at INTEGER NOT NULL,
                actor TEXT NOT NULL,
                type TEXT NOT NULL,
                refs TEXT NOT NULL,
                payload TEXT NOT NULL,
                digest TEXT NOT NULL,
                prev_digest TEXT
            )
            """.trimIndent(),
        )
        // X-1：只追加。触发器是最后一道闸，绕过 DAO 也删不掉。
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS amp_events_no_update
            BEFORE UPDATE ON amp_events
            BEGIN
                SELECT RAISE(ABORT, 'amp_events is append-only');
            END
            """.trimIndent(),
        )
        createCompactionPolicy(db)
    }

    /**
     * X-1 与压缩的折中：删除只允许发生在「已被 checkpoint 承诺」的前缀上。
     * 没有对应 checkpoint 的行仍然删不掉——账本依旧只追加。
     */
    private fun createCompactionPolicy(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ledger_checkpoints(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                from_seq INTEGER NOT NULL,
                to_seq INTEGER NOT NULL,
                count INTEGER NOT NULL,
                root TEXT NOT NULL,
                tip TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS amp_events_no_delete
            BEFORE DELETE ON amp_events
            WHEN NOT EXISTS (
                SELECT 1 FROM ledger_checkpoints WHERE OLD.seq <= ledger_checkpoints.to_seq
            )
            BEGIN
                SELECT RAISE(ABORT, 'amp_events is append-only outside checkpoints');
            END
            """.trimIndent(),
        )
    }

    /** AMP Frame 快照：一次注意周期一行，payload 里带 digest 与槽位。 */
    private fun createFrames(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS frames(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id INTEGER NOT NULL,
                cycle INTEGER NOT NULL,
                mode TEXT NOT NULL,
                goal TEXT,
                digest TEXT NOT NULL,
                payload TEXT NOT NULL,
                impasse TEXT,
                at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_frames_run ON frames(run_id, cycle)")
    }
    companion object {
        const val NAME = "lyra_history.db"
        const val VERSION = 33
    }
}