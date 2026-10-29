package com.eloykim.lyra.amp.protocol.database.repository

import com.eloykim.lyra.amp.protocol.database.entity.ProcessEntity
import com.eloykim.lyra.amp.protocol.database.entity.ProcessTable
import com.eloykim.lyra.amp.protocol.database.entity.StepEntity
import com.eloykim.lyra.amp.protocol.database.entity.StepTable
import com.eloykim.lyra.amp.protocol.database.entity.ThreadEntity
import com.eloykim.lyra.amp.protocol.database.entity.ThreadTable
import com.eloykim.lyra.amp.protocol.database.sql.DatabaseSync
import com.eloykim.lyra.amp.protocol.database.sql.JsJson
import com.eloykim.lyra.amp.protocol.database.sql.Row
import com.eloykim.lyra.amp.protocol.database.sql.SelectOptions
import com.eloykim.lyra.amp.protocol.database.sql.TableSql
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.thread.ChargeResult
import com.eloykim.lyra.amp.protocol.thread.CreateThreadInput
import com.eloykim.lyra.amp.protocol.thread.PatchField
import com.eloykim.lyra.amp.protocol.thread.ThreadClaim
import com.eloykim.lyra.amp.protocol.thread.ThreadPatch
import com.eloykim.lyra.amp.protocol.thread.ThreadProcess
import com.eloykim.lyra.amp.protocol.thread.ThreadProcessPort
import com.eloykim.lyra.amp.protocol.thread.ThreadRecord
import com.eloykim.lyra.amp.protocol.thread.ThreadRecovery
import com.eloykim.lyra.amp.protocol.thread.ThreadState
import com.eloykim.lyra.amp.protocol.thread.ThreadStore
import com.eloykim.lyra.amp.protocol.thread.canThreadTransition
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Usage

/**
 * SQLite execution adapter: processes, tasks and steps through [TableSql]
 * (port of `src/database/repository/executionRepository.ts`).
 *
 * State machines stay in the domain; this repository owns rows, atomic
 * conditional updates and fencing leases.
 *
 * ## Sibling ports
 *
 * - Threads are already ported as `thread/ThreadStore` in `thread/Thread.kt`, so
 *   this class implements that port method-for-method. `ThreadProcessPort` is
 *   implemented as well, which lets `spawnThread` validate against a process
 *   row.
 * - `src/process/process.ts`, `src/process/transitions.ts` and
 *   `src/error/kernelError.ts` have no Kotlin sibling yet. This file defines the
 *   narrow seam they would expose ([ProcessState], [ProcessSpec],
 *   [ProcessRecord], [ProcessPatch], [ProcessEdges], [KernelError]) with the
 *   reference field names and the reference edge table copied verbatim.
 */

/** Process lifecycle state (reference `ProcessState`). */
enum class ProcessState(val wire: String) {
    CREATED("created"),
    RUNNING("running"),
    IDLE("idle"),
    SUSPENDED("suspended"),
    TERMINATED("terminated"),
    ;

    companion object {
        fun fromWire(value: String): ProcessState =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("unknown process state: $value")
    }
}

/**
 * Agent lifecycle edges, copied from `src/process/transitions.ts`:
 * `terminated` has no outgoing edge.
 */
val ProcessEdges: Map<ProcessState, List<ProcessState>> = linkedMapOf(
    ProcessState.CREATED to listOf(
        ProcessState.RUNNING,
        ProcessState.IDLE,
        ProcessState.SUSPENDED,
        ProcessState.TERMINATED,
    ),
    ProcessState.RUNNING to listOf(ProcessState.IDLE, ProcessState.SUSPENDED, ProcessState.TERMINATED),
    ProcessState.IDLE to listOf(ProcessState.RUNNING, ProcessState.SUSPENDED, ProcessState.TERMINATED),
    ProcessState.SUSPENDED to listOf(ProcessState.RUNNING, ProcessState.IDLE, ProcessState.TERMINATED),
    ProcessState.TERMINATED to emptyList(),
)

fun canProcessTransition(from: ProcessState, to: ProcessState): Boolean =
    ProcessEdges.getValue(from).contains(to)

/** Error raised by the execution/scheduler seam (reference `KernelError`). */
class KernelError(
    val code: String,
    val details: List<String> = emptyList(),
) : IllegalStateException("$code: ${details.joinToString(", ")}")

/** Reference `ProcessSpec`: what is needed to create an agent. */
data class ProcessSpec(
    val id: Id,
    val name: String,
    val role: String? = null,
    val priority: Int? = null,
    val budget: Budget? = null,
    val parentPid: Int? = null,
)

/** Reference `ProcessRecord`, with optional fields omitted when absent. */
data class ProcessRecord(
    val pid: Int,
    val id: Id,
    val name: String,
    val state: ProcessState,
    val priority: Int,
    val budget: Budget,
    val used: Usage,
    val createdAt: String,
    val role: String? = null,
    val parentPid: Int? = null,
    val startedAt: String? = null,
    val terminatedAt: String? = null,
    val exitCode: String? = null,
    val exitNote: String? = null,
)

/** Reference `ProcessPatch`; a null field means "not supplied". */
data class ProcessPatch(
    val startedAt: String? = null,
    val terminatedAt: String? = null,
    val exitCode: String? = null,
    val exitNote: String? = null,
)

/** Reference `StepRecordInput` for [ExecutionRepository.writeStep]. */
data class StepRecordInput(
    val pid: Int,
    val tid: Int,
    val at: String,
    val status: String,
    val usage: JsonValue,
    val receiptId: String,
    val note: String? = null,
)

private val USAGE_DIMENSIONS: List<String> = listOf("tokens", "ms", "calls", "interrupts", "money")

private fun emptyUsageJson(): String = JsJson.encode(
    jsonObj(
        "tokens" to jsonNumber(0),
        "ms" to jsonNumber(0),
        "calls" to jsonNumber(0),
        "interrupts" to jsonNumber(0),
        "money" to jsonNumber(0),
    ),
)

private fun readDimension(budget: Budget, dimension: String): Double? = when (dimension) {
    "tokens" -> budget.tokens?.toDouble()
    "ms" -> budget.ms?.toDouble()
    "calls" -> budget.calls?.toDouble()
    "interrupts" -> budget.interrupts?.toDouble()
    "money" -> budget.money
    else -> null
}

private fun budgetFromJson(text: String): Budget =
    Budget.fromJson(JsonParser.parse(text))

private fun usageJson(used: Map<String, Double>): String = JsJson.encode(
    jsonObj(*USAGE_DIMENSIONS.map { dimension -> dimension to jsonNumber(used[dimension] ?: 0.0) }.toTypedArray()),
)

private fun charge(budget: Budget, usedInput: Usage, usage: Budget): Pair<Map<String, Double>, List<String>> {
    val used = LinkedHashMap<String, Double>()
    USAGE_DIMENSIONS.forEach { dimension ->
        used[dimension] = readDimension(usedInput, dimension) ?: 0.0
    }
    val exceeded = ArrayList<String>()
    USAGE_DIMENSIONS.forEach { dimension ->
        val amount = readDimension(usage, dimension)
        if (amount == null || amount == 0.0) {
            return@forEach
        }
        val next = (used[dimension] ?: 0.0) + amount
        used[dimension] = next
        val limit = readDimension(budget, dimension)
        if (limit != null && next > limit) {
            exceeded += dimension
        }
    }
    return used to exceeded
}

private fun toProcess(row: ProcessEntity): ProcessRecord = ProcessRecord(
    pid = row.pid,
    id = row.id,
    name = row.name,
    state = ProcessState.fromWire(row.state),
    priority = row.priority,
    budget = budgetFromJson(row.budget),
    used = budgetFromJson(row.used),
    createdAt = row.created_at,
    role = row.role,
    parentPid = row.parent_pid,
    startedAt = row.started_at,
    terminatedAt = row.terminated_at,
    exitCode = row.exit_code,
    exitNote = row.exit_note,
)

private fun toThread(row: ThreadEntity): ThreadRecord = ThreadRecord(
    tid = row.tid,
    id = row.id,
    pid = row.pid,
    goal = row.goal,
    statement = row.statement,
    success = (JsonParser.parse(row.success) as JsonValue.Arr).items.map(Criterion::fromJson),
    state = ThreadState.fromWire(row.state),
    priority = row.priority,
    quantumMs = row.quantum_ms.toLong(),
    sliceUsedMs = row.slice_used_ms.toLong(),
    budget = budgetFromJson(row.budget),
    used = budgetFromJson(row.used),
    blockedOn = row.blocked_on,
    wakeAt = row.wake_at,
    createdAt = row.created_at,
    finishedAt = row.finished_at,
    exitNote = row.exit_note,
    ownerWorker = row.owner_worker,
    lease = row.lease,
    version = row.version,
)

/**
 * Execution repository (reference `ExecutionRepository`).
 *
 * `all`, `findById`, `insert` and friends come from [BaseRepository]; the
 * aggregate methods below mirror the reference one-for-one.
 */
class ExecutionRepository(db: DatabaseSync) :
    BaseRepository<ProcessEntity, Int>(db, ProcessTable, ProcessEntity::fromRow),
    ThreadStore,
    ThreadProcessPort {

    private val threads: TableSql<ThreadEntity> = TableSql(db, ThreadTable, ThreadEntity::fromRow)
    private val steps: TableSql<StepEntity> = TableSql(db, StepTable, StepEntity::fromRow)

    // ------------------------------------------------------------------
    // processes (reference `process.ts` + `ExecutionStore` process methods)
    // ------------------------------------------------------------------

    fun findAgentByExternalId(id: String): ProcessRecord? = queries.select(
        SelectOptions(where = "id = ?", params = listOf(id), limit = 1),
    ).firstOrNull()?.let(::toProcess)

    fun listAgents(state: ProcessState? = null): List<ProcessRecord> {
        val options = if (state == null) {
            SelectOptions(orderBy = "ORDER BY pid ASC")
        } else {
            SelectOptions(where = "state = ?", params = listOf(state.wire), orderBy = "ORDER BY pid ASC")
        }
        return queries.select(options).map(::toProcess)
    }

    fun createAgent(spec: ProcessSpec, at: String): ProcessRecord {
        val result = queries.insert(
            mapOf(
                "id" to spec.id,
                "name" to spec.name,
                "role" to spec.role,
                "state" to ProcessState.CREATED.wire,
                "priority" to (spec.priority ?: 0),
                "budget" to JsJson.encode((spec.budget ?: Budget()).toJson()),
                "used" to emptyUsageJson(),
                "parent_pid" to spec.parentPid,
                "created_at" to at,
            ),
        )
        return getAgent(result.insertedId)
    }

    fun beginAgent(pid: Int, at: String): Boolean {
        val row = findById(pid) ?: return false
        if (row.state != ProcessState.CREATED.wire && row.state != ProcessState.IDLE.wire) {
            return false
        }
        val changed = queries.updateWhere(
            "pid = ? AND state IN ('created','idle')",
            mapOf(
                "state" to ProcessState.RUNNING.wire,
                "started_at" to (row.started_at ?: at),
                "version" to row.version + 1,
            ),
            pid,
        )
        return changed == 1
    }

    fun getAgent(pid: Int): ProcessRecord {
        val row = findById(pid) ?: throw KernelError("unknown_process", listOf("pid $pid"))
        return toProcess(row)
    }

    fun transitionAgent(
        pid: Int,
        from: List<ProcessState>,
        to: ProcessState,
        patch: ProcessPatch = ProcessPatch(),
    ): ProcessRecord {
        val row = findById(pid) ?: throw KernelError("unknown_process", listOf("pid $pid"))
        val current = ProcessState.fromWire(row.state)
        if (current !in from || !canProcessTransition(current, to)) {
            throw KernelError(
                "invalid_transition",
                listOf("process $pid: ${row.state} -> ${to.wire} is not an edge"),
            )
        }
        val values = LinkedHashMap<String, Any?>()
        values["state"] = to.wire
        values["version"] = row.version + 1
        patch.startedAt?.let { values["started_at"] = row.started_at ?: it }
        patch.terminatedAt?.let { values["terminated_at"] = it }
        patch.exitCode?.let { values["exit_code"] = it }
        patch.exitNote?.let { values["exit_note"] = it }
        val changed = queries.updateWhere("pid = ?", values, pid)
        if (changed != 1) {
            throw KernelError("invalid_transition", listOf("process $pid was not updated"))
        }
        return getAgent(pid)
    }

    fun chargeAgent(pid: Int, usage: Budget, at: String): ChargeResult {
        val row = findById(pid) ?: throw KernelError("unknown_process", listOf("pid $pid"))
        val (used, exceeded) = charge(budgetFromJson(row.budget), budgetFromJson(row.used), usage)
        if (exceeded.isNotEmpty()) {
            queries.updateWhere(
                "pid = ?",
                mapOf(
                    "used" to usageJson(used),
                    "state" to ProcessState.SUSPENDED.wire,
                    "exit_code" to "BUDGET",
                    "exit_note" to "budget exceeded: ${exceeded.joinToString(", ")}",
                ),
                pid,
            )
            return ChargeResult(ok = false, exceeded = exceeded)
        }
        queries.updateWhere("pid = ?", mapOf("used" to usageJson(used)), pid)
        return ChargeResult(ok = true, exceeded = emptyList())
    }

    fun terminateAgent(pid: Int, note: String, at: String, code: String = "TERMINATED"): ProcessRecord =
        transitionAgent(
            pid = pid,
            from = listOf(
                ProcessState.CREATED,
                ProcessState.RUNNING,
                ProcessState.IDLE,
                ProcessState.SUSPENDED,
            ),
            to = ProcessState.TERMINATED,
            patch = ProcessPatch(terminatedAt = at, exitCode = code, exitNote = note),
        )

    // ------------------------------------------------------------------
    // tasks (the `thread/ThreadStore` port)
    // ------------------------------------------------------------------

    override fun process(pid: Int): ThreadProcess? {
        val row = findById(pid) ?: return null
        return ThreadProcess(id = row.id, state = row.state)
    }

    override fun getTask(tid: Int): ThreadRecord {
        val row = threads.findById(tid) ?: throw KernelError("unknown_thread", listOf("tid $tid"))
        return toThread(row)
    }

    override fun findTaskByExternalId(id: Id): ThreadRecord? = threads.select(
        SelectOptions(where = "id = ?", params = listOf(id), limit = 1),
    ).firstOrNull()?.let(::toThread)

    override fun listTasks(pid: Int?): List<ThreadRecord> {
        val options = if (pid == null) {
            SelectOptions(orderBy = "ORDER BY tid ASC")
        } else {
            SelectOptions(where = "pid = ?", params = listOf(pid), orderBy = "ORDER BY tid ASC")
        }
        return threads.select(options).map(::toThread)
    }

    override fun findActiveTaskByGoal(pid: Int, goal: Id): ThreadRecord? = threads.select(
        SelectOptions(
            where = "pid = ? AND goal = ? AND state IN ('ready','running','blocked')",
            params = listOf(pid, goal),
            limit = 1,
        ),
    ).firstOrNull()?.let(::toThread)

    override fun countTasks(pid: Int): Int = threads.count("pid = ?", pid)

    override fun createTask(input: CreateThreadInput): ThreadRecord {
        val result = threads.insert(
            mapOf(
                "id" to input.id,
                "pid" to input.pid,
                "goal" to input.goal,
                "statement" to input.statement,
                "success" to JsJson.encode(JsonValue.Arr(input.success.map { it.toJson() })),
                "state" to ThreadState.READY.wire,
                "priority" to (input.priority ?: 0),
                "quantum_ms" to (input.quantumMs?.toInt() ?: 1000),
                "slice_used_ms" to 0,
                "budget" to JsJson.encode((input.budget ?: Budget()).toJson()),
                "used" to emptyUsageJson(),
                "created_at" to input.at,
            ),
        )
        return getTask(result.insertedId)
    }

    override fun nextReadyTask(): ThreadRecord? = threads.queryOne(
        """SELECT t.* FROM threads t
             JOIN processes p ON p.pid = t.pid
             WHERE t.state = 'ready' AND p.state IN ('created','idle','running')
             ORDER BY t.priority DESC, t.tid ASC
             LIMIT 1""",
    )?.let(ThreadEntity::fromRow)?.let(::toThread)

    override fun claimTask(tid: Int, workerId: String): ThreadClaim {
        val row = threads.findById(tid)
        if (row == null || row.state != ThreadState.READY.wire) {
            throw KernelError("claim_failed", listOf("thread $tid is not ready"))
        }
        val lease = row.lease + 1
        val changed = threads.updateWhere(
            "tid = ? AND state = 'ready'",
            mapOf(
                "state" to ThreadState.RUNNING.wire,
                "owner_worker" to workerId,
                "lease" to lease,
                "slice_used_ms" to 0,
            ),
            tid,
        )
        if (changed != 1) {
            throw KernelError("claim_failed", listOf("thread $tid was claimed"))
        }
        return ThreadClaim(tid = tid, lease = lease, workerId = workerId)
    }

    override fun releaseTask(tid: Int, workerId: String, lease: Int): Boolean =
        threads.updateWhere(
            "tid = ? AND state = 'running' AND owner_worker = ? AND lease = ?",
            mapOf("state" to ThreadState.READY.wire, "owner_worker" to null),
            tid,
            workerId,
            lease,
        ) == 1

    override fun transitionTask(
        tid: Int,
        from: List<ThreadState>,
        to: ThreadState,
        patch: ThreadPatch,
        lease: Int?,
    ): ThreadRecord {
        val row = threads.findById(tid) ?: throw KernelError("unknown_thread", listOf("tid $tid"))
        val current = ThreadState.fromWire(row.state)
        if (current !in from || !canThreadTransition(current, to)) {
            throw KernelError(
                "invalid_transition",
                listOf("thread $tid: ${row.state} -> ${to.wire} is not an edge"),
            )
        }
        if (lease != null && row.lease != lease) {
            throw KernelError(
                "lease_lost",
                listOf("thread $tid: lease $lease is stale, current is ${row.lease}"),
            )
        }
        val values = LinkedHashMap<String, Any?>()
        values["state"] = to.wire
        values["version"] = row.version + 1
        when (val field = patch.blockedOn) {
            is PatchField.Value -> values["blocked_on"] = field.value
            PatchField.Clear -> values["blocked_on"] = null
            PatchField.Absent -> Unit
        }
        when (val field = patch.wakeAt) {
            is PatchField.Value -> values["wake_at"] = field.value
            PatchField.Clear -> values["wake_at"] = null
            PatchField.Absent -> Unit
        }
        when (val field = patch.sliceUsedMs) {
            is PatchField.Value -> values["slice_used_ms"] = field.value.toInt()
            PatchField.Clear -> values["slice_used_ms"] = null
            PatchField.Absent -> Unit
        }
        when (val field = patch.finishedAt) {
            is PatchField.Value -> values["finished_at"] = field.value
            PatchField.Clear -> values["finished_at"] = null
            PatchField.Absent -> Unit
        }
        when (val field = patch.exitNote) {
            is PatchField.Value -> values["exit_note"] = field.value
            PatchField.Clear -> values["exit_note"] = null
            PatchField.Absent -> Unit
        }
        val params = if (lease == null) arrayOf<Any?>(tid) else arrayOf<Any?>(tid, lease)
        val changed = threads.updateWhere("tid = ?" + if (lease == null) "" else " AND lease = ?", values, *params)
        if (changed != 1) {
            throw KernelError("invalid_transition", listOf("thread $tid was not updated"))
        }
        return getTask(tid)
    }

    override fun chargeTask(tid: Int, usage: Budget, at: String): ChargeResult {
        val row = threads.findById(tid) ?: throw KernelError("unknown_thread", listOf("tid $tid"))
        val (used, exceeded) = charge(budgetFromJson(row.budget), budgetFromJson(row.used), usage)
        if (exceeded.isNotEmpty()) {
            threads.updateWhere(
                "tid = ?",
                mapOf(
                    "used" to usageJson(used),
                    "state" to ThreadState.FAILED.wire,
                    "finished_at" to at,
                    "exit_note" to "task budget exceeded: ${exceeded.joinToString(", ")}",
                ),
                tid,
            )
            return ChargeResult(ok = false, exceeded = exceeded)
        }
        threads.updateWhere("tid = ?", mapOf("used" to usageJson(used)), tid)
        return ChargeResult(ok = true, exceeded = emptyList())
    }

    override fun notify(condition: String): Int {
        val woken = threads.updateWhere(
            "state = 'blocked' AND blocked_on = ?",
            mapOf("state" to ThreadState.READY.wire, "blocked_on" to null),
            condition,
        )
        promoteIdleAgents()
        return woken
    }

    override fun wake(at: String): Int {
        val woke = threads.updateWhere(
            "state = 'blocked' AND wake_at IS NOT NULL AND wake_at <= ?",
            mapOf("state" to ThreadState.READY.wire, "wake_at" to null, "blocked_on" to null),
            at,
        )
        promoteIdleAgents()
        return woke
    }

    override fun recover(workerId: String?): ThreadRecovery {
        val rows = if (workerId == null) {
            threads.select(SelectOptions(where = "state = 'running'"))
        } else {
            threads.select(
                SelectOptions(
                    where = "state = 'running' AND (owner_worker IS NULL OR owner_worker = ?)",
                    params = listOf(workerId),
                ),
            )
        }
        var recovered = 0
        rows.forEach { row ->
            recovered += threads.updateWhere(
                "tid = ? AND state = 'running'",
                mapOf(
                    "state" to ThreadState.READY.wire,
                    "owner_worker" to null,
                    "lease" to row.lease + 1,
                    "slice_used_ms" to 0,
                ),
                row.tid,
            )
        }
        val processes = idleAgentsWithoutRunning()
        return ThreadRecovery(threads = recovered, processes = processes)
    }

    fun closeWorker(workerId: String) {
        val rows = threads.select(
            SelectOptions(where = "state = 'running' AND owner_worker = ?", params = listOf(workerId)),
        )
        rows.forEach { row ->
            threads.updateWhere(
                "tid = ? AND state = 'running' AND owner_worker = ?",
                mapOf("state" to ThreadState.READY.wire, "owner_worker" to null, "lease" to row.lease + 1),
                row.tid,
                workerId,
            )
        }
        idleAgentsWithoutRunning()
    }

    override fun runningTaskCount(pid: Int): Int = threads.count("pid = ? AND state = 'running'", pid)

    override fun readyTaskCount(pid: Int): Int = threads.count("pid = ? AND state = 'ready'", pid)

    override fun liveTaskCount(pid: Int): Int =
        threads.count("pid = ? AND state IN ('ready','running','blocked')", pid)

    // ------------------------------------------------------------------
    // steps
    // ------------------------------------------------------------------

    fun countSteps(tid: Int): Int = steps.count("tid = ?", tid)

    fun writeStep(step: StepRecordInput) {
        steps.insert(
            mapOf(
                "pid" to step.pid,
                "tid" to step.tid,
                "at" to step.at,
                "outcome" to step.status,
                "usage" to JsJson.encode(step.usage),
                "note" to step.note,
                "receipt_id" to step.receiptId,
            ),
        )
    }

    private fun promoteIdleAgents(): Int = queries.updateWhere(
        "state = 'idle' AND EXISTS (SELECT 1 FROM threads t WHERE t.pid = processes.pid AND t.state = 'ready')",
        mapOf("state" to ProcessState.RUNNING.wire),
    )

    private fun idleAgentsWithoutRunning(): Int = queries.updateWhere(
        "state = 'running' AND NOT EXISTS (SELECT 1 FROM threads t WHERE t.pid = processes.pid AND t.state = 'running')",
        mapOf("state" to ProcessState.IDLE.wire),
    )
}

/** Unused marker kept so [Row] stays referenced in this file's imports. */
private typealias ExecutionRow = Row
