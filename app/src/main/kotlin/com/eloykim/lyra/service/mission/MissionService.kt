package com.eloykim.lyra.service.mission

import android.content.Context
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqLong
import com.eloykim.lyra.service.reqString
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mission 服务：Lyra 应用内的任务/使命列表。
 *
 * 和 TaskService 的区别：
 * - TaskService 管理**系统任务记录**（runs/steps/账本/画像）；
 * - MissionService 管理**应用内任务**（用户/Agent 创建、开始、完成、取消）。
 *
 * 数据落在 app 私有 SharedPreferences，不触碰系统任务列表。
 */
class MissionService(context: Context) : BasePhoneService() {

    override val id: String = "mission"
    override val title: String = "应用内任务"
    override val description: String =
        "创建/查询/更新应用内任务（pending/running/done/failed/cancelled），与系统任务记录分离。"

    private val prefs = context.applicationContext
        .getSharedPreferences("lyra_missions", Context.MODE_PRIVATE)
    private val lock = Any()

    private data class Mission(
        val id: Long,
        val title: String,
        val goal: String,
        val status: String,
        val note: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出应用内任务",
                parameters = Schema.params(
                    "status" to Schema.string("按状态过滤"),
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> list(args.optStringOr("status"), args.optIntOr("limit", 20)) }

        register(
            ServiceMethod(
                name = "get",
                summary = "读取单个任务",
                parameters = Schema.params(
                    "id" to Schema.integer("任务 id"),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args -> get(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "create",
                summary = "创建一个应用内任务",
                parameters = Schema.params(
                    "title" to Schema.string("标题"),
                    "goal" to Schema.string("目标/说明"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            create(
                title = args.reqString("title").take(120),
                goal = args.optStringOr("goal").take(1_000),
            )
        }

        register(
            ServiceMethod(
                name = "update",
                summary = "更新任务状态/备注",
                parameters = Schema.params(
                    "id" to Schema.integer("任务 id"),
                    "status" to Schema.string(
                        "状态",
                        listOf("pending", "running", "done", "failed", "cancelled"),
                    ),
                    "note" to Schema.string("备注"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            update(
                id = args.reqLong("id"),
                status = args.reqString("status"),
                note = args.optStringOr("note"),
            )
        }

        register(
            ServiceMethod(
                name = "start",
                summary = "把任务标记为 running",
                parameters = Schema.params(
                    "id" to Schema.integer("任务 id"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> update(args.reqLong("id"), "running", "") }

        register(
            ServiceMethod(
                name = "complete",
                summary = "把任务标记为 done",
                parameters = Schema.params(
                    "id" to Schema.integer("任务 id"),
                    "note" to Schema.string("备注"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> update(args.reqLong("id"), "done", args.optStringOr("note")) }

        register(
            ServiceMethod(
                name = "cancel",
                summary = "把任务标记为 cancelled",
                parameters = Schema.params(
                    "id" to Schema.integer("任务 id"),
                    "note" to Schema.string("备注"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> update(args.reqLong("id"), "cancelled", args.optStringOr("note")) }

        register(
            ServiceMethod(
                name = "remove",
                summary = "删除一个应用内任务",
                parameters = Schema.params(
                    "id" to Schema.integer("任务 id"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> remove(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "stats",
                summary = "按状态统计应用内任务",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { stats() }
    }

    private fun list(status: String, limit: Int): ServiceResult {
        val filter = status.trim().lowercase()
        val missions = load()
            .asSequence()
            .filter { filter.isEmpty() || it.status == filter }
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 100))
            .toList()
        val array = JSONArray()
        missions.forEach { array.put(it.toJson()) }
        return ServiceResult.ok(
            "${missions.size} missions",
            JSONObject()
                .put("missions", array)
                .put("count", missions.size),
        )
    }

    private fun get(id: Long): ServiceResult {
        val mission = load().firstOrNull { it.id == id }
            ?: return ServiceResult.fail("not_found", "没有任务 $id")
        return ServiceResult.ok("found", mission.toJson())
    }

    private fun create(title: String, goal: String): ServiceResult = synchronized(lock) {
        val missions = load()
        val now = System.currentTimeMillis()
        val id = (missions.maxOfOrNull { it.id } ?: 0L) + 1L
        val mission = Mission(
            id = id,
            title = title,
            goal = goal,
            status = "pending",
            note = "",
            createdAt = now,
            updatedAt = now,
        )
        missions += mission
        save(missions)
        ServiceResult.ok(
            "created",
            mission.toJson(),
        )
    }

    private fun update(id: Long, status: String, note: String): ServiceResult =
        synchronized(lock) {
            val normalized = normalizeStatus(status)
                ?: return ServiceResult.fail(
                    "bad_args",
                    "status 只支持 pending/running/done/failed/cancelled",
                )
            val missions = load()
            val index = missions.indexOfFirst { it.id == id }
            if (index < 0) return ServiceResult.fail("not_found", "没有任务 $id")
            val current = missions[index]
            val updated = current.copy(
                status = normalized,
                note = note.ifBlank { current.note }.take(1_000),
                updatedAt = System.currentTimeMillis(),
            )
            missions[index] = updated
            save(missions)
            ServiceResult.ok("updated", updated.toJson())
        }

    private fun remove(id: Long): ServiceResult = synchronized(lock) {
        val missions = load()
        val removed = missions.removeAll { it.id == id }
        if (!removed) return ServiceResult.fail("not_found", "没有任务 $id")
        save(missions)
        ServiceResult.ok("removed", JSONObject().put("id", id))
    }

    private fun stats(): ServiceResult {
        val missions = load()
        val byStatus = JSONObject()
        listOf("pending", "running", "done", "failed", "cancelled").forEach { status ->
            byStatus.put(status, missions.count { it.status == status })
        }
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("total", missions.size)
                .put("byStatus", byStatus),
        )
    }

    private fun normalizeStatus(raw: String): String? {
        val status = raw.trim().lowercase()
        return status.takeIf {
            it in setOf("pending", "running", "done", "failed", "cancelled")
        }
    }

    private fun load(): MutableList<Mission> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val missions = ArrayList<Mission>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            missions += Mission(
                id = item.optLong("id"),
                title = item.optString("title"),
                goal = item.optString("goal"),
                status = item.optString("status", "pending"),
                note = item.optString("note"),
                createdAt = item.optLong("createdAt"),
                updatedAt = item.optLong("updatedAt"),
            )
        }
        return missions
    }

    private fun save(missions: List<Mission>) {
        val array = JSONArray()
        missions.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun Mission.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("goal", goal)
        .put("status", status)
        .put("note", note)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private companion object {
        const val KEY = "missions"
    }
}
