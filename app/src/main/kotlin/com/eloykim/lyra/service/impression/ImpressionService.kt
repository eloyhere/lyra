package com.eloykim.lyra.service.impression

import android.content.Context
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.database.ReadOnlyDatabase
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 印象服务：只读访问用户画像与 `profile_facts`。
 *
 * 画像只包含统计量（常用应用/动作/活跃时段），不含屏幕原文；
 * 写入画像不在本服务开放，避免模型自我强化或篡改用户偏好。
 */
class ImpressionService(
    private val history: HistoryRepository,
    context: Context,
    private val database: ReadOnlyDatabase = ReadOnlyDatabase(context),
) : BasePhoneService() {

    override val id: String = "impression"
    override val title: String = "用户印象"
    override val description: String =
        "只读查看用户画像：常用应用/动作/时段、画像事实与数量统计。"

    init {
        register(
            ServiceMethod(
                name = "summary",
                summary = "给 prompt 用的一行画像摘要",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { summary() }

        register(
            ServiceMethod(
                name = "profile",
                summary = "完整画像 JSON（统计量）",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { profile() }

        register(
            ServiceMethod(
                name = "facts",
                summary = "画像事实表（key/value/confidence）",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> facts(args.optIntOr("limit", 50)) }

        register(
            ServiceMethod(
                name = "get",
                summary = "按 key 读取一条画像事实",
                parameters = Schema.params(
                    "key" to Schema.string("画像事实 key"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> get(args.reqString("key")) }

        register(
            ServiceMethod(
                name = "topApps",
                summary = "最常用的应用",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> topApps(args.optIntOr("limit", 8)) }

        register(
            ServiceMethod(
                name = "topActions",
                summary = "最常用的动作",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> topActions(args.optIntOr("limit", 8)) }

        register(
            ServiceMethod(
                name = "hours",
                summary = "24 小时活跃度",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { hours() }
    }

    private suspend fun summary(): ServiceResult = ServiceResult.ok(
        "profile",
        JSONObject().put("text", history.profileText()),
    )

    private suspend fun profile(): ServiceResult {
        val profile = history.profileJson()
        return ServiceResult.ok(
            "profile",
            JSONObject()
                .put("text", history.profileText())
                .put("profile", profile),
        )
    }

    private suspend fun facts(limit: Int): ServiceResult = withContext(Dispatchers.IO) {
        val page = runCatching { database.facts(limit) }.getOrNull()
            ?: return@withContext ServiceResult.fail("query_failed", "读取画像事实失败")
        ServiceResult.ok(
            "${page.rows.size} facts",
            JSONObject()
                .put("columns", JSONArray(page.columns))
                .put("facts", JSONArray(page.rows))
                .put("count", page.rows.size)
                .put("truncated", page.truncated),
        )
    }

    private suspend fun get(key: String): ServiceResult = withContext(Dispatchers.IO) {
        val page = runCatching {
            database.query(
                "SELECT key, value, confidence, updated_at FROM profile_facts WHERE key = ?",
                listOf(key),
                1,
            )
        }.getOrNull() ?: return@withContext ServiceResult.fail(
            "query_failed",
            "读取画像事实失败",
        )
        val row = page.rows.firstOrNull()
            ?: return@withContext ServiceResult.fail("not_found", "没有画像事实 $key")
        ServiceResult.ok("found", row)
    }

    private suspend fun topApps(limit: Int): ServiceResult {
        val rows = history.topPackages(limit.coerceIn(1, 50))
        val array = JSONArray()
        rows.forEach { row ->
            array.put(
                JSONObject()
                    .put("package", row.packageName)
                    .put("label", row.label)
                    .put("launchCount", row.launchCount)
                    .put("stepCount", row.stepCount)
                    .put("lastSeenAt", row.lastSeenAt),
            )
        }
        return ServiceResult.ok(
            "${rows.size} apps",
            JSONObject().put("apps", array).put("count", rows.size),
        )
    }

    private suspend fun topActions(limit: Int): ServiceResult {
        val rows = history.topActions(limit.coerceIn(1, 50))
        val array = JSONArray()
        rows.forEach { row ->
            array.put(
                JSONObject()
                    .put("action", row.action)
                    .put("count", row.count),
            )
        }
        return ServiceResult.ok(
            "${rows.size} actions",
            JSONObject().put("actions", array).put("count", rows.size),
        )
    }

    private suspend fun hours(): ServiceResult {
        val values = history.hourlyActivity()
        val array = JSONArray()
        values.forEachIndexed { hour, count ->
            array.put(JSONObject().put("hour", hour).put("count", count))
        }
        return ServiceResult.ok(
            "24 hours",
            JSONObject().put("hours", array),
        )
    }
}
