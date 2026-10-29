package com.eloykim.lyra.amp.protocol.thread

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Usage

/**
 * `src/thread/thread.ts` 的忠实移植：thread 是一个智能体正在执行的一个任务（或目标）。
 *
 * 智能体是进程；线程是类 CPU 调度器运行的工作单元。同一个智能体可以有多个 ready 任务，
 * 但同一时刻最多一个 `running`（参考实现用部分唯一索引保证）：一次做一件事，切换靠调度
 * 另一个线程。
 *
 * ## 绑定
 *
 * 参考实现的函数体全部委托给 `ExecutionRepository`（`src/database/repository/
 * executionRepository.ts`，未移植）。这里把该仓库收敛成 [ThreadStore] 窄端口，逐方法对应：
 * getTask/findTaskByExternalId/listTasks/createTask/nextReadyTask/transitionTask/claimTask/
 * releaseTask/chargeTask/notify/wake/recover/liveTaskCount/readyTaskCount/runningTaskCount，
 * 另加 spawn 需要的 findActiveTaskByGoal 与 countTasks。`getProcess` 由 [ThreadProcessPort]
 * 提供。适配器实现这一端口即可把调度器接到任意存储。
 *
 * [ThreadPatch] 用 [PatchField] 表达参考 `string | null | undefined` 的三态：`Absent`
 * 保持原值，`Clear` 显式清空，`Value` 设为新值。
 *
 * 参考在 `thread.ts` 中定义 `ThreadState`，`transitions.ts` 反向导入它；Kotlin 把枚举放在
 * [Transitions.kt]（`Transitions.kt` 不依赖本文件），等价于参考 `index.ts` 的统一再导出。
 */

data class ThreadSpec(
    /** 缺省为 `t.<pid>.<n>`；必须满足 AMP id 语法。 */
    val id: Id? = null,
    val pid: Int,
    /** 该任务服务的目标。 */
    val goal: Id,
    val statement: String,
    /** 至少一个可观察的完成判据。 */
    val success: List<Criterion>,
    val priority: Int? = null,
    val budget: Budget? = null,
    val quantumMs: Long? = null,
)

data class ThreadRecord(
    val tid: Int,
    val id: Id,
    val pid: Int,
    val goal: Id,
    val statement: String,
    val success: List<Criterion>,
    val state: ThreadState,
    val priority: Int,
    val quantumMs: Long,
    val sliceUsedMs: Long,
    val budget: Budget,
    val used: Usage,
    val blockedOn: String? = null,
    val wakeAt: String? = null,
    val createdAt: String,
    val finishedAt: String? = null,
    val exitNote: String? = null,
    val ownerWorker: String? = null,
    val lease: Int,
    val version: Int,
)

/** 三态补丁字段：未给出 / 显式清空 / 设为值。 */
sealed interface PatchField<out T> {

    data object Absent : PatchField<Nothing>

    data object Clear : PatchField<Nothing>

    data class Value<T>(val value: T) : PatchField<T>
}

data class ThreadPatch(
    val blockedOn: PatchField<String> = PatchField.Absent,
    val wakeAt: PatchField<String> = PatchField.Absent,
    val sliceUsedMs: PatchField<Long> = PatchField.Absent,
    val finishedAt: PatchField<String> = PatchField.Absent,
    val exitNote: PatchField<String> = PatchField.Absent,
)

data class ThreadClaim(
    val tid: Int,
    val lease: Int,
    val workerId: String,
)

/** `chargeTask` 的结果：是否仍在上限内，以及超出的维度。 */
data class ChargeResult(
    val ok: Boolean,
    val exceeded: List<String>,
)

/** 崩溃恢复报告。 */
data class ThreadRecovery(
    val threads: Int,
    val processes: Int,
)

data class ThreadProcess(
    val id: Id,
    val state: String,
)

interface ThreadProcessPort {

    fun process(pid: Int): ThreadProcess?
}

/** `createTask` 的入参；缺省字段由存储层按参考实现补默认值。 */
data class CreateThreadInput(
    val id: Id,
    val pid: Int,
    val goal: Id,
    val statement: String,
    val success: List<Criterion>,
    val priority: Int?,
    val quantumMs: Long?,
    val budget: Budget?,
    val at: String,
)

/**
 * `ExecutionRepository` 的窄端口。每个方法与参考仓库方法同名同义；实现者负责事务与
 * `version`/`lease` 自增。
 */
interface ThreadStore {

    fun getTask(tid: Int): ThreadRecord

    fun findTaskByExternalId(id: Id): ThreadRecord?

    fun listTasks(pid: Int?): List<ThreadRecord>

    fun findActiveTaskByGoal(pid: Int, goal: Id): ThreadRecord?

    fun countTasks(pid: Int): Int

    fun createTask(input: CreateThreadInput): ThreadRecord

    fun nextReadyTask(): ThreadRecord?

    fun transitionTask(
        tid: Int,
        from: List<ThreadState>,
        to: ThreadState,
        patch: ThreadPatch,
        lease: Int?,
    ): ThreadRecord

    fun claimTask(tid: Int, workerId: String): ThreadClaim

    fun releaseTask(tid: Int, workerId: String, lease: Int): Boolean

    fun chargeTask(tid: Int, usage: Budget, at: String): ChargeResult

    fun notify(condition: String): Int

    fun wake(at: String): Int

    fun recover(workerId: String?): ThreadRecovery

    fun liveTaskCount(pid: Int): Int

    fun readyTaskCount(pid: Int): Int

    fun runningTaskCount(pid: Int): Int
}

class ThreadError(
    val code: String,
    val details: List<String> = emptyList(),
) : IllegalArgumentException("$code: ${details.joinToString(", ")}")

fun getThread(store: ThreadStore, tid: Int): ThreadRecord = store.getTask(tid)

fun findThread(store: ThreadStore, id: Id): ThreadRecord? = store.findTaskByExternalId(id)

fun listThreads(store: ThreadStore, pid: Int? = null): List<ThreadRecord> = store.listTasks(pid)

fun spawnThread(
    store: ThreadStore,
    processes: ThreadProcessPort,
    spec: ThreadSpec,
    at: String,
): ThreadRecord {
    val agent: ThreadProcess = processes.process(spec.pid)
        ?: throw ThreadError("unknown_process", listOf("pid ${spec.pid}"))
    if (agent.state !in listOf("created", "idle", "running")) {
        throw ThreadError("invalid_transition", listOf("agent ${agent.id} is ${agent.state}"))
    }
    if (!ProtocolIds.isValid(spec.goal)) {
        throw ThreadError("invalid_thread", listOf("goal ${spec.goal} is not a valid AMP id"))
    }
    if (spec.statement.trim().isEmpty()) {
        throw ThreadError("invalid_thread", listOf("statement is empty"))
    }
    if (spec.success.isEmpty()) {
        throw ThreadError("invalid_thread", listOf("success must contain at least one criterion"))
    }
    for (criterion in spec.success) {
        if (!ProtocolIds.isValid(criterion.id) ||
            criterion.statement.trim().isEmpty() ||
            criterion.check.trim().isEmpty()
        ) {
            throw ThreadError("invalid_thread", listOf("criterion ${criterion.id} is incomplete"))
        }
    }
    val duplicate: ThreadRecord? = store.findActiveTaskByGoal(spec.pid, spec.goal)
    if (duplicate != null) {
        throw ThreadError(
            "duplicate_task",
            listOf("goal ${spec.goal} already has active task ${duplicate.tid}"),
        )
    }
    val count: Int = store.countTasks(spec.pid)
    val id: Id = spec.id ?: "t.${spec.pid}.${count + 1}"
    if (!ProtocolIds.isValid(id)) {
        throw ThreadError("invalid_thread", listOf("id $id is not a valid AMP id"))
    }
    return store.createTask(
        CreateThreadInput(
            id = id,
            pid = spec.pid,
            goal = spec.goal,
            statement = spec.statement,
            success = spec.success,
            priority = spec.priority,
            quantumMs = spec.quantumMs,
            budget = spec.budget,
            at = at,
        ),
    )
}

/** 最老的、最高优先级的、其智能体可以运行的任务。 */
fun nextReadyThread(store: ThreadStore): ThreadRecord? = store.nextReadyTask()

fun transitionThread(
    store: ThreadStore,
    tid: Int,
    from: List<ThreadState>,
    to: ThreadState,
    patch: ThreadPatch = ThreadPatch(),
    lease: Int? = null,
): ThreadRecord = store.transitionTask(tid, from, to, patch, lease)

fun transitionThread(
    store: ThreadStore,
    tid: Int,
    from: ThreadState,
    to: ThreadState,
    patch: ThreadPatch = ThreadPatch(),
    lease: Int? = null,
): ThreadRecord = transitionThread(store, tid, listOf(from), to, patch, lease)

/** 原子地把 ready 任务改为 running；lease 是 fencing token。 */
fun claimThread(store: ThreadStore, tid: Int, workerId: String): ThreadClaim =
    store.claimTask(tid, workerId)

fun releaseThread(store: ThreadStore, tid: Int, workerId: String, lease: Int): Boolean =
    store.releaseTask(tid, workerId, lease)

/** 计入任务自己的时间片。耗尽的是任务，不是智能体。 */
fun chargeThread(store: ThreadStore, tid: Int, usage: Budget, at: String): ChargeResult =
    store.chargeTask(tid, usage, at)

/** 唤醒所有等待同一条件（邮箱、对象、handle）的线程。 */
fun notifyCondition(store: ThreadStore, condition: String): Int = store.notify(condition)

/** 提升 `wake_at` 已过的睡眠任务。 */
fun wakeSleeping(store: ThreadStore, at: String): Int = store.wake(at)

/** 崩溃恢复：重新排队死 worker 名下的 running 任务；智能体回到 idle。 */
fun recoverThreads(store: ThreadStore, workerId: String? = null): ThreadRecovery = store.recover(workerId)

fun liveThreadCount(store: ThreadStore, pid: Int): Int = store.liveTaskCount(pid)

fun readyThreadCount(store: ThreadStore, pid: Int): Int = store.readyTaskCount(pid)

fun runningThreadCount(store: ThreadStore, pid: Int): Int = store.runningTaskCount(pid)
