package com.eloykim.lyra.data

import com.eloykim.lyra.attention.Metrics
import com.eloykim.lyra.attention.MetricsCalculator
import com.eloykim.lyra.attention.ReceiptSample

import android.content.ContentValues
import android.content.Context
import com.eloykim.lyra.agent.RunRecorder
import com.eloykim.lyra.agent.StepRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RunSummary(
    val id: Long,
    val task: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val summary: String?,
)

data class PackageRow(
    val packageName: String,
    val label: String?,
    val versionName: String?,
    val launchCount: Int,
    val stepCount: Int,
    val lastSeenAt: Long,
)

data class ActionRow(
    val action: String,
    val count: Int,
)

data class ProviderRow(
    val id: String,
    val label: String,
    val baseUrl: String?,
)

data class ModelRow(
    val id: String,
    val label: String,
    val contextWindow: Int?,
    val inputPrice: Double?,
    val outputPrice: Double?,
    val supportsVision: Boolean,
)

data class ProviderWithModels(
    val provider: ProviderRow,
    val models: List<ModelRow>,
)

/**
 * 本地数据入口：操作历史、应用目录、模型目录、画像。
 *
 * 写入走 [RunRecorder] 端口（Agent 每步调用一次）；
 * 读取给「最近任务」「设置页下拉」「用户画像」用。
 */
class HistoryRepository(context: Context) : RunRecorder {

    private val appContext = context.applicationContext
    private val helper = LyraDatabase(appContext)

    /** AMP 只追加事件日志。 */
    val ledger: LedgerStore by lazy { LedgerStore(helper) }

    /** ChatMessage 存储层：持久化 + 给 ChatMessageService 查询历史。 */
    val chatMessages: ChatMessageStore by lazy { ChatMessageStore(helper) }

    /** 聊天 token/费用账本。 */
    val chatUsage: ChatUsageStore by lazy { ChatUsageStore(helper) }

    /** 群聊存储。 */
    val groupChat: GroupChatStore by lazy { GroupChatStore(helper) }

    /** 地址库：AddressService 扫描后写入。 */
    val addresses: AddressStore by lazy { AddressStore(helper) }

    /** AMP Frame 帧链。 */
    val frames: FrameStore by lazy { FrameStore(helper) }

    /** App 启动时把上次异常退出遗留的 running 任务标记为 interrupted。 */
    fun markInterrupted(now: Long = System.currentTimeMillis()) {
        runCatching {
            helper.writableDatabase.execSQL(
                "UPDATE runs SET status='interrupted', finished_at=? WHERE status='running'",
                arrayOf(now),
            )
        }
    }

    // ------------------------------------------------------------ 写入

    override suspend fun startRun(task: String): Long = withContext(Dispatchers.IO) {
        pruneInternal(days = 30)
        val values = ContentValues().apply {
            put("task", task.take(200))
            put("started_at", System.currentTimeMillis())
            put("status", "running")
        }
        helper.writableDatabase.insert("runs", null, values)
    }

    override suspend fun recordStep(runId: Long, step: StepRecord) = withContext(Dispatchers.IO) {
        if (runId <= 0L) return@withContext
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("run_id", runId)
            put("seq", step.seq)
            put("kind", step.kind)
            put("action", step.action)
            put("package_name", step.packageName)
            put("target_label", step.targetLabel)
            step.nodeId?.let { put("node_id", it) }
            step.ok?.let { put("ok", if (it) 1 else 0) }
            put("code", step.code)
            put("message", step.message?.take(300))
            put("started_at", step.startedAt)
            put("duration_ms", step.durationMs)
        }
        db.insert("steps", null, values)

        step.packageName?.takeIf { it.isNotBlank() }?.let { touchPackage(db, it, launched = false) }
        step.targetPackage?.takeIf { it.isNotBlank() }?.let { touchPackage(db, it, launched = true) }
        Unit
    }

    override suspend fun finishRun(runId: Long, status: String, summary: String) =
        withContext(Dispatchers.IO) {
            if (runId <= 0L) return@withContext
            val values = ContentValues().apply {
                put("finished_at", System.currentTimeMillis())
                put("status", status)
                put("summary", summary.take(300))
            }
            helper.writableDatabase.update("runs", values, "id = ?", arrayOf(runId.toString()))
            Unit
        }

    // ------------------------------------------------------------ 读取

    suspend fun recentRuns(limit: Int = 10): List<RunSummary> = withContext(Dispatchers.IO) {
        val result = ArrayList<RunSummary>()
        helper.readableDatabase.rawQuery(
            "SELECT id, task, status, started_at, finished_at, summary FROM runs ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += RunSummary(
                    id = cursor.getLong(0),
                    task = cursor.getString(1),
                    status = cursor.getString(2),
                    startedAt = cursor.getLong(3),
                    finishedAt = if (cursor.isNull(4)) null else cursor.getLong(4),
                    summary = cursor.getString(5),
                )
            }
        }
        result
    }

    suspend fun counts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val runs = scalar("SELECT COUNT(*) FROM runs")
        val steps = scalar("SELECT COUNT(*) FROM steps")
        runs to steps
    }

    suspend fun packageCount(): Int = withContext(Dispatchers.IO) {
        scalar("SELECT COUNT(*) FROM packages")
    }

    suspend fun topPackages(limit: Int = 8): List<PackageRow> = withContext(Dispatchers.IO) {
        val result = ArrayList<PackageRow>()
        helper.readableDatabase.rawQuery(
            """
            SELECT package_name, label, version_name, launch_count, step_count, last_seen_at
            FROM packages ORDER BY (launch_count * 2 + step_count) DESC, last_seen_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PackageRow(
                    packageName = cursor.getString(0),
                    label = cursor.getString(1),
                    versionName = cursor.getString(2),
                    launchCount = cursor.getInt(3),
                    stepCount = cursor.getInt(4),
                    lastSeenAt = cursor.getLong(5),
                )
            }
        }
        result
    }

    /** 最常用的动作：tap / input / scroll …，按次数倒序。 */
    suspend fun topActions(limit: Int = 6): List<ActionRow> = withContext(Dispatchers.IO) {
        val result = ArrayList<ActionRow>()
        helper.readableDatabase.rawQuery(
            """
            SELECT action, COUNT(*) c FROM steps
            WHERE action IS NOT NULL AND action != ''
            GROUP BY action ORDER BY c DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ActionRow(
                    action = cursor.getString(0),
                    count = cursor.getInt(1),
                )
            }
        }
        result
    }

    /** 24 小时活跃度直方图；没有记录时返回全 0。 */
    suspend fun hourlyActivity(): IntArray = withContext(Dispatchers.IO) {
        val hours = IntArray(24)
        helper.readableDatabase.rawQuery(
            """
            SELECT strftime('%H', started_at / 1000, 'unixepoch', 'localtime') h, COUNT(*) c
            FROM steps GROUP BY h
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val hour = cursor.getString(0)?.toIntOrNull()
                if (hour != null && hour in 0..23) {
                    hours[hour] = cursor.getInt(1)
                }
            }
        }
        hours
    }

    /** 设置页的两个下拉：服务商 + 每个服务商下的模型。 */
    suspend fun catalog(): List<ProviderWithModels> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val providers = ArrayList<ProviderRow>()
        db.rawQuery(
            "SELECT id, label, base_url FROM llm_providers ORDER BY sort_order, label",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                providers += ProviderRow(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            }
        }

        providers.map { provider ->
            val models = ArrayList<ModelRow>()
            db.rawQuery(
                """
                SELECT id, label, context_window, input_price, output_price, supports_vision
                FROM llm_models WHERE provider_id = ? AND deprecated = 0
                ORDER BY sort_order, label
                """.trimIndent(),
                arrayOf(provider.id),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    models += ModelRow(
                        id = cursor.getString(0),
                        label = cursor.getString(1) ?: cursor.getString(0),
                        contextWindow = if (cursor.isNull(2)) null else cursor.getInt(2),
                        inputPrice = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        outputPrice = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        supportsVision = cursor.getInt(5) == 1,
                    )
                }
            }
            ProviderWithModels(provider, models)
        }
    }

    suspend fun catalogVersion(): Int = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT value FROM profile_facts WHERE key = 'catalog_version'",
            null,
        ).use { if (it.moveToFirst()) it.getString(0)?.toIntOrNull() ?: 0 else 0 }
    }

    /**
     * 读取某个模型的价格（USD / 1M tokens），包含目录里标记 deprecated 的旧模型。
     *
     * 用度计算只关心「用户当前设置的模型」实际价格，不能因为模型被标记为旧版
     * 就回退到历史 cost；设置页仍使用 [catalog] 的未废弃过滤。
     */
    suspend fun modelPrices(providerId: String, modelId: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            helper.readableDatabase.rawQuery(
                "SELECT input_price, output_price FROM llm_models " +
                    "WHERE provider_id = ? AND id = ? LIMIT 1",
                arrayOf(providerId, modelId),
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                    cursor.getDouble(0) to cursor.getDouble(1)
                } else {
                    null
                }
            }
        }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        helper.writableDatabase.apply {
            delete("steps", null, null)
            delete("runs", null, null)
            // 不清 packages / 模型目录：它们不是「一次性历史」
        }
    }

    // ------------------------------------------------------------ 画像

    /**
     * 用户画像：最常用应用 / 动作 / 活跃时段 / 已见应用数。
     * 只有统计量，没有屏幕原文。
     */
    suspend fun profileJson(): JSONObject = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val obj = JSONObject()

        obj.put("runs", scalar("SELECT COUNT(*) FROM runs"))
        obj.put("steps", scalar("SELECT COUNT(*) FROM steps"))
        obj.put("packages", scalar("SELECT COUNT(*) FROM packages"))

        val apps = JSONArray()
        db.rawQuery(
            "SELECT package_name, launch_count, step_count FROM packages ORDER BY (launch_count * 2 + step_count) DESC LIMIT 5",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                apps.put(
                    JSONObject()
                        .put("package", cursor.getString(0))
                        .put("launches", cursor.getInt(1))
                        .put("steps", cursor.getInt(2)),
                )
            }
        }
        obj.put("topApps", apps)

        val actions = JSONArray()
        db.rawQuery(
            "SELECT action, COUNT(*) c FROM steps WHERE action IS NOT NULL GROUP BY action ORDER BY c DESC LIMIT 5",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                actions.put(JSONObject().put("action", cursor.getString(0)).put("count", cursor.getInt(1)))
            }
        }
        obj.put("topActions", actions)

        val hours = JSONArray()
        db.rawQuery(
            """
            SELECT strftime('%H', started_at / 1000, 'unixepoch', 'localtime') h, COUNT(*) c
            FROM steps GROUP BY h ORDER BY c DESC LIMIT 5
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                hours.put(JSONObject().put("hour", cursor.getString(0)).put("count", cursor.getInt(1)))
            }
        }
        obj.put("activeHours", hours)

        obj
    }

    /** 给 prompt 用的一行摘要。 */
    suspend fun profileText(): String = withContext(Dispatchers.IO) {
        val profile = profileJson()
        val apps = profile.optJSONArray("topApps")?.let { array ->
            (0 until array.length()).joinToString("、") { index ->
                val item = array.getJSONObject(index)
                "${item.optString("package")}×${item.optInt("launches") + item.optInt("steps")}"
            }
        }.orEmpty()
        val actions = profile.optJSONArray("topActions")?.let { array ->
            (0 until array.length()).joinToString("、") { index ->
                val item = array.getJSONObject(index)
                "${item.optString("action")}×${item.optInt("count")}"
            }
        }.orEmpty()
        "共 ${profile.optInt("runs")} 次任务、${profile.optInt("packages")} 个应用；" +
            "常用应用：${apps.ifBlank { "无" }}；常用动作：${actions.ifBlank { "无" }}"
    }

    // ------------------------------------------------------------ 用度

    /**
     * 从 AMP 账本聚合用度：token、动作耗时、按模型单价折算的花费。
     *
     * 只读本机账本，不额外落表；没有 receipt 时返回全 0 的报表。
     */
    suspend fun usageReport(days: Int = 7, recentLimit: Int = 20): UsageReport =
        withContext(Dispatchers.IO) {
            val events = runCatching { ledger.loadAll() }.getOrNull().orEmpty()
            val problems = runCatching { ledger.verify() }.getOrNull().orEmpty()
            UsageAggregator.aggregate(
                events = events,
                chainProblems = problems,
                days = days,
                recentLimit = recentLimit,
            )
        }

    // ------------------------------------------------------------ 内部

    private fun touchPackage(
        db: android.database.sqlite.SQLiteDatabase,
        packageName: String,
        launched: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val label = labelOf(packageName)
        val versionName = versionOf(packageName)
        val versionCode = versionCodeOf(packageName)

        val seed = ContentValues().apply {
            put("package_name", packageName)
            put("label", label)
            put("version_name", versionName)
            put("version_code", versionCode)
            put("first_seen_at", now)
            put("last_seen_at", now)
            put("launch_count", if (launched) 1 else 0)
            put("step_count", if (launched) 0 else 1)
        }
        db.insertWithOnConflict("packages", null, seed, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)

        db.execSQL(
            if (launched) {
                "UPDATE packages SET launch_count = launch_count + 1, last_seen_at = ?, label = COALESCE(?, label), version_name = COALESCE(?, version_name) WHERE package_name = ?"
            } else {
                "UPDATE packages SET step_count = step_count + 1, last_seen_at = ?, label = COALESCE(?, label), version_name = COALESCE(?, version_name) WHERE package_name = ?"
            },
            arrayOf<Any?>(now, label, versionName, packageName),
        )
    }

    private fun labelOf(packageName: String): String? = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun versionOf(packageName: String): String? = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    private fun versionCodeOf(packageName: String): Int? = runCatching {
        val info = appContext.packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrNull()

    private fun scalar(sql: String): Int =
        helper.readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun pruneInternal(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        helper.writableDatabase.apply {
            execSQL(
                "DELETE FROM steps WHERE run_id IN (SELECT id FROM runs WHERE started_at < ?)",
                arrayOf(cutoff),
            )
            delete("runs", "started_at < ?", arrayOf(cutoff.toString()))
        }
    }
    /**
     * 从服务商的 /models 端点拉取真实模型清单。
     *
     * 只补新模型（INSERT OR IGNORE），不覆盖手工维护的价格/上下文；
     * 返回新增数量。网络与鉴权失败会抛异常，由 UI 展示。
     */
    suspend fun refreshModels(providerId: String, baseUrl: String, apiKey: String): Int =
        withContext(Dispatchers.IO) {
            val endpoint = java.net.URL(baseUrl.trimEnd('/') + "/models")
            val connection = endpoint.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            if (apiKey.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.take(160)
                        .orEmpty()
                    throw IllegalStateException("HTTP $code $detail")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val data = org.json.JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
                val now = System.currentTimeMillis()
                val db = helper.writableDatabase
                var added = 0
                db.beginTransaction()
                try {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val values = ContentValues().apply {
                            put("provider_id", providerId)
                            put("id", id)
                            put("label", id)
                            put("supports_tools", 1)
                            put("supports_vision", 0)
                            put("deprecated", 0)
                            put("sort_order", 100)
                            put("updated_at", now)
                        }
                        // 合并语义：模型是否存在于 /models 是事实；价格/上下文是宿主声明。
                        // 已存在时只刷新 updated_at 与 deprecated，保留手工维护的价格等元数据。
                        val existed = android.database.DatabaseUtils.longForQuery(
                            db,
                            "SELECT COUNT(*) FROM llm_models WHERE provider_id = ? AND id = ?",
                            arrayOf(providerId, id),
                        ) > 0
                        if (!existed) {
                            db.insertWithOnConflict(
                                "llm_models", null, values,
                                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                            )
                            added++
                        } else {
                            db.execSQL(
                                "UPDATE llm_models SET updated_at = ?, deprecated = 0 " +
                                    "WHERE provider_id = ? AND id = ?",
                                arrayOf<Any?>(now, providerId, id),
                            )
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                added
            } finally {
                connection.disconnect()
            }
        }
    /** 拉取失败时清空该服务商的模型，界面上显示「无数据」。 */
    suspend fun clearModels(providerId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("llm_models", "provider_id = ?", arrayOf(providerId))
    }

    /**
     * 打开页面时并行拉取所有服务商的模型列表。
     *
     * - 每个服务商独立跑，互不影响；
     * - 有 base_url 才拉；本地服务用用户配置的地址；
     * - 拉不到（无 Key / 网络不通 / 401）就清空，让下拉框显示「无数据」。
     */
    suspend fun refreshAllProviders(
        providers: List<ProviderRow>,
        localBaseUrl: String,
        keyFor: (String) -> String?,
        dispatcher: CoroutineDispatcher,
    ) = coroutineScope {
        providers.forEach { provider ->
            launch(dispatcher) {
                val baseUrl = if (provider.id == "local") {
                    localBaseUrl.ifBlank { null }
                } else {
                    provider.baseUrl
                }
                if (baseUrl == null) {
                    clearModels(provider.id)
                    return@launch
                }
                val key = keyFor(provider.id)
                val keyRequired = provider.id != "local" && provider.id != "openrouter"
                if (keyRequired && key.isNullOrBlank()) {
                    // 没 Key 就不发请求：直接视为「拉不到」，避免 8 个服务商各等一次超时
                    clearModels(provider.id)
                    return@launch
                }
                runCatching {
                    refreshModels(provider.id, baseUrl, key.orEmpty())
                }.onFailure { error ->
                    // 只有「明确没有权限/不存在」才清空；网络抖动保留已有数据
                    val message = error.message.orEmpty()
                    val definitive = message.startsWith("HTTP 401") ||
                        message.startsWith("HTTP 403") ||
                        message.startsWith("HTTP 404")
                    if (definitive) clearModels(provider.id)
                }
            }
        }
    }
    /**
     * 从事件账本算三个指标（AMP §13）。
     *
     * 回执里的 predicted/realized VOI 是校准的唯一事实来源；
     * 样本不足 10 条时 [Metrics.reliable] 为 false，避免用几条数据下结论。
     */
    suspend fun metrics(): Metrics = withContext(Dispatchers.IO) {
        val events = runCatching { ledger.loadAll() }.getOrDefault(emptyList())
        val receipts = events.filter { it.type == "receipt" }.mapNotNull { event ->
            runCatching {
                val payload = org.json.JSONObject(event.payload)
                ReceiptSample(
                    tokensIn = payload.optInt("tokens_in"),
                    tokensOut = payload.optInt("tokens_out"),
                    costMicros = payload.optLong("cost_micros"),
                    predictedVoi = payload.optDouble("predicted_voi", 0.0),
                    realizedVoi = payload.optDouble("realized_voi", 0.0),
                )
            }.getOrNull()
        }
        MetricsCalculator.compute(
            receipts = receipts,
            runs = events.count { it.type == "run.started" },
            impasses = events.count { it.type == "impasse" },
        )
    }
}