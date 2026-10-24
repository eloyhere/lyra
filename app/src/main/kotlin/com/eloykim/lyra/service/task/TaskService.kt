package com.eloykim.lyra.service.task

import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 系统任务服务：任务记录、常用应用、账本与画像。
 *
 * 页面导航归 WindowService，应用内任务归 MissionService。
 */
class TaskService(
    private val history: HistoryRepository,
) : BasePhoneService() {

    override val id: String = "task"
    override val title: String = "系统任务"
    override val description: String =
        "系统任务记录：历史任务列表与数量、AMP 账本/校验。"

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出任务记录，可按状态过滤",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                    "status" to Schema.string("只看某个状态，例如 succeeded/failed/needs_user"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> list(args.optIntOr("limit", 10), args.optStringOr("status")) }

        register(
            ServiceMethod(
                name = "status",
                summary = "任务/步骤/应用/账本数量",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }

        register(
            ServiceMethod(
                name = "ledger",
                summary = "AMP 账本尾部事件",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> ledger(args.optIntOr("limit", 12)) }

        register(
            ServiceMethod(
                name = "verify",
                summary = "校验 AMP 账本哈希链",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { verify() }
    }

    private suspend fun list(limit: Int, status: String): ServiceResult {
        val runs = history.recentRuns(limit.coerceIn(1, 50) * if (status.isBlank()) 1 else 4)
            .filter { status.isBlank() || it.status.equals(status, ignoreCase = true) }
            .take(limit.coerceIn(1, 50))
        val array = JSONArray()
        runs.forEach { run ->
            array.put(
                JSONObject()
                    .put("id", run.id)
                    .put("task", run.task)
                    .put("status", run.status)
                    .put("startedAt", run.startedAt)
                    .put("finishedAt", run.finishedAt)
                    .put("summary", run.summary),
            )
        }
        return ServiceResult.ok(
            "${runs.size} tasks",
            JSONObject().put("tasks", array).put("count", runs.size),
        )
    }

    private suspend fun ledger(limit: Int): ServiceResult = withContext(Dispatchers.IO) {
        val events = history.ledger.loadAll()
        val array = JSONArray()
        events.takeLast(limit.coerceIn(1, 100)).forEach { event ->
            array.put(
                JSONObject()
                    .put("seq", event.seq)
                    .put("at", event.at)
                    .put("actor", event.actor)
                    .put("type", event.type)
                    .put("refs", JSONArray(event.refs))
                    .put("payload", event.payload.take(500))
                    .put("digest", event.digest)
                    .put("prevDigest", event.prevDigest),
            )
        }
        ServiceResult.ok(
            "${array.length()} events",
            JSONObject()
                .put("events", array)
                .put("total", events.size)
                .put("count", array.length()),
        )
    }

    private suspend fun verify(): ServiceResult = withContext(Dispatchers.IO) {
        val events = history.ledger.loadAll()
        val problems = history.ledger.verify()
        ServiceResult.ok(
            if (problems.isEmpty()) "chain intact" else "chain broken",
            JSONObject()
                .put("events", events.size)
                .put("intact", problems.isEmpty())
                .put("problems", JSONArray(problems)),
        )
    }

    private suspend fun status(): ServiceResult {
        val (runs, steps) = history.counts()
        val packages = history.packageCount()
        val events = withContext(Dispatchers.IO) { history.ledger.size() }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("runs", runs)
                .put("steps", steps)
                .put("packages", packages)
                .put("events", events),
        )
    }
}


