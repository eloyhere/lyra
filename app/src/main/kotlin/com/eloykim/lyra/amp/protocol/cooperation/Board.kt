package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.protocol.Lease
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * 显式共享任务板（`src/cooperation/board.ts` 的忠实移植）。
 *
 * 任务板是回答“谁在做什么”的唯一权威位置。一个目标最多只有一个未完成任务；
 * 完成或取消会释放该目标，允许重试。
 *
 * 绑定：`Lease` 复用 `protocol/Wire.kt`（`src/delegate/team.ts` 的可见运行时
 * 形状），本文件不重新定义租约。
 */

/** 任务状态。 */
enum class TaskState(val wire: String) {
    PENDING("pending"),
    LEASED("leased"),
    DONE("done"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(value: String): TaskState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 task state: $value")
    }
}

/** 任务板上的一条任务。`owner`/`lease` 在认领前缺席。 */
data class BoardTask(
    val id: Id,
    val goalDigest: Digest,
    var state: TaskState,
    var owner: Id? = null,
    var lease: Id? = null,
    var attempts: Int,
) {

    init {
        ProtocolIds.require(id, "board task.id")
        require(attempts >= 0) { "board task.attempts 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "goalDigest" to jsonText(goalDigest),
        "state" to jsonText(state.wire),
        "owner" to owner?.let { jsonText(it) },
        "lease" to lease?.let { jsonText(it) },
        "attempts" to jsonNumber(attempts),
    )

    companion object {
        fun fromJson(value: JsonValue): BoardTask {
            val obj = value.asObject("board task")
            return BoardTask(
                id = obj.requireString("id"),
                goalDigest = obj.requireString("goalDigest"),
                state = TaskState.fromWire(obj.requireString("state")),
                owner = obj.string("owner"),
                lease = obj.string("lease"),
                attempts = obj.requireInt("attempts"),
            )
        }
    }
}

/** 同一目标已有未完成任务。 */
class DuplicateGoal(message: String) : IllegalStateException(message) {

    val code: String = "duplicate_goal"
}

/** 任务板计数器，供协调指标使用。 */
data class BoardStats(
    val added: Int,
    val rejected: Int,
    val open: Int,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "added" to jsonNumber(added),
        "rejected" to jsonNumber(rejected),
        "open" to jsonNumber(open),
    )

    companion object {
        fun fromJson(value: JsonValue): BoardStats {
            val obj = value.asObject("board stats")
            return BoardStats(
                added = obj.requireInt("added"),
                rejected = obj.requireInt("rejected"),
                open = obj.requireInt("open"),
            )
        }
    }
}

/**
 * 单一权威的任务板。
 *
 * `added` 统计所有加入尝试（含被拒绝的重复目标），`rejected` 统计因重复而被拒绝
 * 的次数——与参考实现一致，重复项会先计入 added 再计入 rejected。
 */
class TaskBoard {

    private val tasks = LinkedHashMap<Id, BoardTask>()
    private val openByGoal = LinkedHashMap<Digest, Id>()
    private var seq = 0
    private var rejected = 0
    private var added = 0

    fun add(goalDigest: Digest): BoardTask {
        added += 1
        val open = openByGoal[goalDigest]
        if (open != null) {
            rejected += 1
            throw DuplicateGoal("goal already has task $open")
        }
        seq += 1
        val task = BoardTask(id = "tb.$seq", goalDigest = goalDigest, state = TaskState.PENDING, attempts = 0)
        tasks[task.id] = task
        openByGoal[goalDigest] = task.id
        return task
    }

    fun claim(taskId: Id, lease: Lease): BoardTask {
        val task = get(taskId)
        if (task.state == TaskState.DONE || task.state == TaskState.CANCELLED) {
            throw IllegalStateException("task $taskId is ${task.state.wire}")
        }
        if (task.lease != null && task.lease != lease.id && task.state == TaskState.LEASED) {
            throw IllegalStateException("task $taskId is already leased to ${task.owner}")
        }
        task.state = TaskState.LEASED
        task.owner = lease.holder
        task.lease = lease.id
        task.attempts += 1
        return task
    }

    fun complete(taskId: Id): BoardTask {
        val task = get(taskId)
        task.state = TaskState.DONE
        openByGoal.remove(task.goalDigest)
        return task
    }

    fun cancel(taskId: Id): BoardTask {
        val task = get(taskId)
        task.state = TaskState.CANCELLED
        openByGoal.remove(task.goalDigest)
        return task
    }

    fun get(taskId: Id): BoardTask =
        tasks[taskId] ?: throw IllegalStateException("unknown task $taskId")

    fun list(): List<BoardTask> = tasks.values.toList()

    /**
     * 任务板级计数器，喂给协调指标。
     */
    fun stats(): BoardStats {
        val open = list().count { it.state == TaskState.PENDING || it.state == TaskState.LEASED }
        return BoardStats(added = added, rejected = rejected, open = open)
    }
}
