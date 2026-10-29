package com.eloykim.lyra.amp.protocol.kernel

import com.eloykim.lyra.amp.protocol.concurrency.RecoveryReport
import com.eloykim.lyra.amp.protocol.concurrency.WorkerInput
import com.eloykim.lyra.amp.protocol.concurrency.WorkerRecord
import com.eloykim.lyra.amp.protocol.concurrency.WorkerStore
import com.eloykim.lyra.amp.protocol.error.KernelCode
import com.eloykim.lyra.amp.protocol.error.KernelError
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.storage.GENESIS
import com.eloykim.lyra.amp.protocol.thread.ChargeResult
import com.eloykim.lyra.amp.protocol.thread.CreateThreadInput
import com.eloykim.lyra.amp.protocol.thread.PatchField
import com.eloykim.lyra.amp.protocol.thread.ThreadClaim
import com.eloykim.lyra.amp.protocol.thread.ThreadPatch
import com.eloykim.lyra.amp.protocol.thread.ThreadRecord
import com.eloykim.lyra.amp.protocol.thread.ThreadRecovery
import com.eloykim.lyra.amp.protocol.thread.ThreadState
import com.eloykim.lyra.amp.protocol.thread.canThreadTransition
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.window.WindowPageInput

/**
 * Android 参考实现：内核所需的执行表、窗口页表、对象链与 worker 表都在内存里。
 *
 * `InMemoryWorkerStore` 只保留 worker 恢复所需的 `threads`/`processes` 列，而且与内核的
 * 任务表各存一份；这里把两张表合成同一组 `processes`/`threads`，否则 worker 恢复与
 * `ExecutionStore.recover` 会看到不同的状态。语义逐条对应参考 SQL：
 *
 * - `threads` 每个 pid 最多一个 `running`（claim 时检查）；
 * - `nextReadyTask` 按 `priority DESC, tid ASC`，且 agent 必须处于
 *   `created/idle/running`；
 * - claim 只从 `ready` 迁移并自增 lease；transition/release/recover 用 lease 做 fencing；
 * - `charge` 逐维累加并报告超限维度，任务超限置 `failed`，智能体超限置 `suspended`；
 * - worker 过期恢复把名下 running 任务改回 ready 并 lease+1，再把“有 ready、无 running”
 *   的 running 进程改回 idle。
 *
 * [transaction] 用整体快照回滚。参考实现是 SQLite `BEGIN IMMEDIATE`/`ROLLBACK`；在内存
 * 实现里复制所有可变表即可得到相同的“提交失败则一次都不生效”语义。注意 claim 发生在
 * 事务之前（与参考调度器一致），所以提交失败不会归还已经拿到的 lease。
 */
class InMemoryKernelStore :
    KernelStore,
    KernelExecutionStore,
    KernelWindowStore,
    KernelObjectStore,
    WorkerStore {

    private val processes = LinkedHashMap<Int, ProcessRecord>()
    private val threads = LinkedHashMap<Int, ThreadRecord>()
    private val stepRows = ArrayList<KernelStepRecord>()
    private val windowPages = LinkedHashMap<Int, List<WindowPageInput>>()
    private val objectRows = ArrayList<KernelObjectRow>()
    private val workerRows = LinkedHashMap<String, WorkerRecord>()

    private var nextPid: Int = 1
    private var nextTid: Int = 1
    private var nextSeq: Int = 1

    override val execution: KernelExecutionStore get() = this
    override val windows: KernelWindowStore get() = this
    override val objects: KernelObjectStore get() = this
    override val workers: WorkerStore get() = this

    // ------------------------------------------------------------------
    // 事务
    // ------------------------------------------------------------------

    private data class Snapshot(
        val processes: Map<Int, ProcessRecord>,
        val threads: Map<Int, ThreadRecord>,
        val steps: List<KernelStepRecord>,
        val windowPages: Map<Int, List<WindowPageInput>>,
        val objectRows: List<KernelObjectRow>,
        val workerRows: Map<String, WorkerRecord>,
        val nextPid: Int,
        val nextTid: Int,
        val nextSeq: Int,
    )

    private fun snapshot(): Snapshot = Snapshot(
        processes = LinkedHashMap(processes),
        threads = LinkedHashMap(threads),
        steps = ArrayList(stepRows),
        windowPages = LinkedHashMap(windowPages),
        objectRows = ArrayList(objectRows),
        workerRows = LinkedHashMap(workerRows),
        nextPid = nextPid,
        nextTid = nextTid,
        nextSeq = nextSeq,
    )

    private fun restore(snapshot: Snapshot) {
        processes.clear()
        processes.putAll(snapshot.processes)
        threads.clear()
        threads.putAll(snapshot.threads)
        stepRows.clear()
        stepRows.addAll(snapshot.steps)
        windowPages.clear()
        windowPages.putAll(snapshot.windowPages)
        objectRows.clear()
        objectRows.addAll(snapshot.objectRows)
        workerRows.clear()
        workerRows.putAll(snapshot.workerRows)
        nextPid = snapshot.nextPid
        nextTid = snapshot.nextTid
        nextSeq = snapshot.nextSeq
    }

    @Synchronized
    override fun <T> transaction(block: () -> T): T {
        val snapshot = snapshot()
        return try {
            block()
        } catch (error: Throwable) {
            restore(snapshot)
            throw error
        }
    }

    // ------------------------------------------------------------------
    // 进程（智能体）
    // ------------------------------------------------------------------

    /**
     * 建一个智能体。参考实现里这是 `process.ts#createProcess`，不属于
     * `ExecutionStore`；进程模块尚未移植，所以作为内存 store 的种子入口提供。
     */
    @Synchronized
    fun createAgent(
        id: Id,
        name: String,
        at: Timestamp,
        budget: Budget = Budget(),
        priority: Int = 0,
        role: String? = null,
        parentPid: Int? = null,
    ): ProcessRecord {
        if (!ProtocolIds.isValid(id)) {
            throw KernelError(KernelCode.INVALID_PROCESS, listOf("id $id is not a valid AMP id"))
        }
        if (name.trim().isEmpty()) {
            throw KernelError(KernelCode.INVALID_PROCESS, listOf("name is empty"))
        }
        if (processes.values.any { it.id == id }) {
            throw KernelError(KernelCode.INVALID_PROCESS, listOf("id $id already exists"))
        }
        val pid: Int = nextPid++
        val record = ProcessRecord(
            pid = pid,
            id = id,
            name = name,
            role = role,
            state = ProcessState.CREATED,
            priority = priority,
            budget = budget,
            used = emptyUsage(),
            parentPid = parentPid,
            createdAt = at,
        )
        processes[pid] = record
        return record
    }

    @Synchronized
    override fun beginAgent(pid: Int, at: Timestamp): Boolean {
        val row: ProcessRecord = processes[pid] ?: return false
        if (row.state != ProcessState.CREATED && row.state != ProcessState.IDLE) {
            return false
        }
        processes[pid] = row.copy(
            state = ProcessState.RUNNING,
            startedAt = row.startedAt ?: at,
            version = row.version + 1,
        )
        return true
    }

    @Synchronized
    override fun getAgent(pid: Int): ProcessRecord =
        processes[pid] ?: throw KernelError(KernelCode.UNKNOWN_PROCESS, listOf("pid $pid"))

    @Synchronized
    override fun transitionAgent(
        pid: Int,
        from: List<ProcessState>,
        to: ProcessState,
        patch: ProcessPatch,
    ): ProcessRecord {
        val row: ProcessRecord = processes[pid]
            ?: throw KernelError(KernelCode.UNKNOWN_PROCESS, listOf("pid $pid"))
        if (!from.contains(row.state) || !canProcessTransition(row.state, to)) {
            throw KernelError(
                KernelCode.INVALID_TRANSITION,
                listOf("process $pid: ${row.state.wire} -> ${to.wire} is not an edge"),
            )
        }
        val next = row.copy(
            state = to,
            startedAt = row.startedAt ?: patch.startedAt,
            terminatedAt = patch.terminatedAt ?: row.terminatedAt,
            exitCode = patch.exitCode ?: row.exitCode,
            exitNote = patch.exitNote ?: row.exitNote,
            version = row.version + 1,
        )
        processes[pid] = next
        return next
    }

    @Synchronized
    override fun chargeAgent(pid: Int, usage: Budget, at: Timestamp): ChargeResult {
        val row: ProcessRecord = processes[pid]
            ?: throw KernelError(KernelCode.UNKNOWN_PROCESS, listOf("pid $pid"))
        val charged = charge(row.budget, row.used, usage)
        return if (charged.exceeded.isEmpty()) {
            processes[pid] = row.copy(used = charged.used)
            ChargeResult(ok = true, exceeded = emptyList())
        } else {
            processes[pid] = row.copy(
                used = charged.used,
                state = ProcessState.SUSPENDED,
                exitCode = "BUDGET",
                exitNote = "budget exceeded: ${charged.exceeded.joinToString(", ")}",
            )
            ChargeResult(ok = false, exceeded = charged.exceeded)
        }
    }

    @Synchronized
    override fun terminateAgent(pid: Int, note: String, at: Timestamp, code: String): ProcessRecord =
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
    // 任务（线程）
    // ------------------------------------------------------------------

    private fun task(tid: Int): ThreadRecord =
        threads[tid] ?: throw KernelError(KernelCode.UNKNOWN_THREAD, listOf("tid $tid"))

    @Synchronized
    override fun getTask(tid: Int): ThreadRecord = task(tid)

    @Synchronized
    override fun findTaskByExternalId(id: Id): ThreadRecord? =
        threads.values.firstOrNull { it.id == id }

    @Synchronized
    override fun listTasks(pid: Int?): List<ThreadRecord> =
        threads.values.filter { pid == null || it.pid == pid }.sortedBy { it.tid }

    @Synchronized
    override fun findActiveTaskByGoal(pid: Int, goal: Id): ThreadRecord? =
        threads.values.firstOrNull {
            it.pid == pid && it.goal == goal &&
                it.state in listOf(ThreadState.READY, ThreadState.RUNNING, ThreadState.BLOCKED)
        }

    @Synchronized
    override fun countTasks(pid: Int): Int = threads.values.count { it.pid == pid }

    @Synchronized
    override fun createTask(input: CreateThreadInput): ThreadRecord {
        if (!processes.containsKey(input.pid)) {
            throw KernelError(KernelCode.UNKNOWN_PROCESS, listOf("pid ${input.pid}"))
        }
        if (threads.values.any { it.id == input.id }) {
            throw KernelError(KernelCode.DUPLICATE_TASK, listOf("task ${input.id} already exists"))
        }
        val tid: Int = nextTid++
        val record = ThreadRecord(
            tid = tid,
            id = input.id,
            pid = input.pid,
            goal = input.goal,
            statement = input.statement,
            success = input.success,
            state = ThreadState.READY,
            priority = input.priority ?: 0,
            quantumMs = input.quantumMs ?: 1000,
            sliceUsedMs = 0,
            budget = input.budget ?: Budget(),
            used = emptyUsage(),
            createdAt = input.at,
            lease = 0,
            version = 0,
        )
        threads[tid] = record
        return record
    }

    @Synchronized
    override fun nextReadyTask(): ThreadRecord? =
        threads.values
            .filter { task ->
                task.state == ThreadState.READY &&
                    processes[task.pid]?.state in
                    listOf(ProcessState.CREATED, ProcessState.IDLE, ProcessState.RUNNING)
            }
            .sortedWith(compareByDescending<ThreadRecord> { it.priority }.thenBy { it.tid })
            .firstOrNull()

    @Synchronized
    override fun transitionTask(
        tid: Int,
        from: List<ThreadState>,
        to: ThreadState,
        patch: ThreadPatch,
        lease: Int?,
    ): ThreadRecord {
        val row: ThreadRecord = task(tid)
        if (!from.contains(row.state) || !canThreadTransition(row.state, to)) {
            throw KernelError(
                KernelCode.INVALID_TRANSITION,
                listOf("thread $tid: ${row.state.wire} -> ${to.wire} is not an edge"),
            )
        }
        if (lease != null && row.lease != lease) {
            throw KernelError(
                KernelCode.LEASE_LOST,
                listOf("thread $tid: lease $lease is stale, current is ${row.lease}"),
            )
        }
        val next: ThreadRecord = applyThreadPatch(row, patch).copy(
            state = to,
            version = row.version + 1,
        )
        threads[tid] = next
        return next
    }

    @Synchronized
    override fun claimTask(tid: Int, workerId: String): ThreadClaim {
        val row: ThreadRecord = threads[tid]
            ?: throw KernelError(KernelCode.CLAIM_FAILED, listOf("thread $tid is not ready"))
        if (row.state != ThreadState.READY) {
            throw KernelError(KernelCode.CLAIM_FAILED, listOf("thread $tid is not ready"))
        }
        val alreadyRunning = threads.values.any {
            it.pid == row.pid && it.state == ThreadState.RUNNING && it.tid != tid
        }
        if (alreadyRunning) {
            throw KernelError(KernelCode.CLAIM_FAILED, listOf("thread $tid was claimed"))
        }
        val claimed: ThreadRecord = row.copy(
            state = ThreadState.RUNNING,
            ownerWorker = workerId,
            lease = row.lease + 1,
            sliceUsedMs = 0,
        )
        threads[tid] = claimed
        return ThreadClaim(tid = tid, lease = claimed.lease, workerId = workerId)
    }

    @Synchronized
    override fun releaseTask(tid: Int, workerId: String, lease: Int): Boolean {
        val row: ThreadRecord = threads[tid] ?: return false
        if (row.state != ThreadState.RUNNING || row.ownerWorker != workerId || row.lease != lease) {
            return false
        }
        threads[tid] = row.copy(state = ThreadState.READY, ownerWorker = null)
        return true
    }

    @Synchronized
    override fun chargeTask(tid: Int, usage: Budget, at: Timestamp): ChargeResult {
        val row: ThreadRecord = task(tid)
        val charged = charge(row.budget, row.used, usage)
        return if (charged.exceeded.isEmpty()) {
            threads[tid] = row.copy(used = charged.used)
            ChargeResult(ok = true, exceeded = emptyList())
        } else {
            threads[tid] = row.copy(
                used = charged.used,
                state = ThreadState.FAILED,
                finishedAt = at,
                exitNote = "task budget exceeded: ${charged.exceeded.joinToString(", ")}",
            )
            ChargeResult(ok = false, exceeded = charged.exceeded)
        }
    }

    @Synchronized
    override fun notify(condition: String): Int {
        var woken = 0
        for (row in threads.values.toList()) {
            if (row.state == ThreadState.BLOCKED && row.blockedOn == condition) {
                threads[row.tid] = row.copy(state = ThreadState.READY, blockedOn = null)
                woken += 1
            }
        }
        promoteIdleAgents()
        return woken
    }

    @Synchronized
    override fun wake(at: Timestamp): Int {
        var woken = 0
        for (row in threads.values.toList()) {
            val wakeAt: String = row.wakeAt ?: continue
            if (row.state == ThreadState.BLOCKED && wakeAt <= at) {
                threads[row.tid] = row.copy(
                    state = ThreadState.READY,
                    wakeAt = null,
                    blockedOn = null,
                )
                woken += 1
            }
        }
        promoteIdleAgents()
        return woken
    }

    @Synchronized
    override fun recover(workerId: String?): ThreadRecovery {
        var recovered = 0
        for (row in threads.values.toList()) {
            if (row.state != ThreadState.RUNNING) {
                continue
            }
            if (workerId != null && row.ownerWorker != null && row.ownerWorker != workerId) {
                continue
            }
            threads[row.tid] = row.copy(
                state = ThreadState.READY,
                ownerWorker = null,
                lease = row.lease + 1,
                sliceUsedMs = 0,
            )
            recovered += 1
        }
        val processesChanged: Int = idleAgentsWithoutRunning()
        return ThreadRecovery(threads = recovered, processes = processesChanged)
    }

    @Synchronized
    override fun liveTaskCount(pid: Int): Int =
        threads.values.count {
            it.pid == pid && it.state in
                listOf(ThreadState.READY, ThreadState.RUNNING, ThreadState.BLOCKED)
        }

    @Synchronized
    override fun readyTaskCount(pid: Int): Int =
        threads.values.count { it.pid == pid && it.state == ThreadState.READY }

    @Synchronized
    override fun runningTaskCount(pid: Int): Int =
        threads.values.count { it.pid == pid && it.state == ThreadState.RUNNING }

    @Synchronized
    override fun countSteps(tid: Int): Int = stepRows.count { it.tid == tid }

    @Synchronized
    override fun writeStep(input: KernelStepRecord) {
        stepRows += input
    }

    /** 测试/审计入口：按提交顺序返回 step 行。 */
    @Synchronized
    fun stepRecords(): List<KernelStepRecord> = ArrayList(stepRows)

    @Synchronized
    override fun closeWorker(workerId: String) {
        for (row in threads.values.toList()) {
            if (row.state == ThreadState.RUNNING && row.ownerWorker == workerId) {
                threads[row.tid] = row.copy(
                    state = ThreadState.READY,
                    ownerWorker = null,
                    lease = row.lease + 1,
                )
            }
        }
        idleAgentsWithoutRunning()
    }

    private fun promoteIdleAgents() {
        for (row in processes.values.toList()) {
            if (row.state != ProcessState.IDLE) {
                continue
            }
            val hasReady: Boolean = threads.values.any {
                it.pid == row.pid && it.state == ThreadState.READY
            }
            if (hasReady) {
                processes[row.pid] = row.copy(state = ProcessState.RUNNING)
            }
        }
    }

    private fun idleAgentsWithoutRunning(): Int {
        var changed = 0
        for (row in processes.values.toList()) {
            if (row.state != ProcessState.RUNNING) {
                continue
            }
            val hasRunning: Boolean = threads.values.any {
                it.pid == row.pid && it.state == ThreadState.RUNNING
            }
            if (!hasRunning) {
                processes[row.pid] = row.copy(state = ProcessState.IDLE)
                changed += 1
            }
        }
        return changed
    }

    // ------------------------------------------------------------------
    // 窗口页表
    // ------------------------------------------------------------------

    @Synchronized
    override fun pages(tid: Int): List<WindowPageInput> =
        windowPages[tid].orEmpty().sortedBy { it.lastUsed }

    @Synchronized
    override fun replacePages(
        tid: Int,
        pages: List<WindowPageInput>,
        at: Timestamp,
        capacityTokens: Int,
    ) {
        windowPages[tid] = ArrayList(pages)
    }

    /** 测试/调试入口：当前页表（参考实现 `windows.used_tokens` 由页和计算）。 */
    @Synchronized
    fun storedPages(tid: Int): List<WindowPageInput> = windowPages[tid].orEmpty()

    // ------------------------------------------------------------------
    // 对象链
    // ------------------------------------------------------------------

    @Synchronized
    override fun putObject(
        kind: String,
        id: String,
        body: JsonValue,
        at: Timestamp,
    ): KernelPutObjectResult {
        require(objectRows.none { it.kind == kind && it.id == id }) {
            "object $kind/$id already exists"
        }
        val prev: Digest = objectRows.lastOrNull()?.hash ?: GENESIS
        val bodyText: String = CanonicalJson.encode(body)
        val hash: Digest = objectHash(prev = prev, kind = kind, id = id, body = bodyText)
        val seq: Int = nextSeq++
        objectRows += KernelObjectRow(
            seq = seq,
            kind = kind,
            id = id,
            body = bodyText,
            hash = hash,
            prev = prev,
            at = at,
        )
        return KernelPutObjectResult(seq = seq, hash = hash)
    }

    @Synchronized
    override fun find(kind: String, id: String): KernelObjectRow? =
        objectRows.firstOrNull { it.kind == kind && it.id == id }

    @Synchronized
    fun objectRows(): List<KernelObjectRow> = ArrayList(objectRows)

    /** 重算对象链；与 `verifyObjectChain` 相同的两条原因。 */
    @Synchronized
    fun verifyObjects(): VerifyResult {
        val reasons = ArrayList<String>()
        var prev: Digest = GENESIS
        objectRows.forEach { row ->
            if (row.prev != prev) {
                reasons += "seq ${row.seq}: prev does not continue the chain"
            }
            val expected: Digest = objectHash(row.prev, row.kind, row.id, row.body)
            if (row.hash != expected) {
                reasons += "seq ${row.seq}: hash does not cover its body"
            }
            prev = row.hash
        }
        return VerifyResult(reasons.isEmpty(), reasons)
    }

    // ------------------------------------------------------------------
    // worker 注册表
    // ------------------------------------------------------------------

    @Synchronized
    override fun upsert(
        input: WorkerInput,
        startedAt: Timestamp,
        heartbeatAt: Timestamp,
        expiresAt: Timestamp,
    ): WorkerRecord {
        val existing: WorkerRecord? = workerRows[input.workerId]
        val record: WorkerRecord = if (existing == null) {
            WorkerRecord(
                workerId = input.workerId,
                startedAt = startedAt,
                heartbeatAt = heartbeatAt,
                expiresAt = expiresAt,
                host = input.host,
                pid = input.pid,
            )
        } else {
            existing.copy(
                heartbeatAt = heartbeatAt,
                expiresAt = expiresAt,
                host = input.host,
                pid = input.pid,
            )
        }
        workerRows[input.workerId] = record
        return record
    }

    @Synchronized
    override fun updateHeartbeat(workerId: String, at: Timestamp, expiresAt: Timestamp): Boolean {
        val existing: WorkerRecord = workerRows[workerId] ?: return false
        workerRows[workerId] = existing.copy(heartbeatAt = at, expiresAt = expiresAt)
        return true
    }

    @Synchronized
    override fun delete(workerId: String) {
        workerRows.remove(workerId)
    }

    @Synchronized
    override fun all(): List<WorkerRecord> = workerRows.values.sortedBy { it.workerId }

    @Synchronized
    override fun recoverExpired(at: Timestamp): RecoveryReport {
        val expired: List<String> = workerRows.values
            .filter { it.expiresAt <= at }
            .map { it.workerId }
        var threadsChanged = 0
        for (workerId in expired) {
            for (row in threads.values.toList()) {
                if (row.state == ThreadState.RUNNING && row.ownerWorker == workerId) {
                    threads[row.tid] = row.copy(
                        state = ThreadState.READY,
                        ownerWorker = null,
                        lease = row.lease + 1,
                        sliceUsedMs = 0,
                    )
                    threadsChanged += 1
                }
            }
        }
        for (workerId in expired) {
            workerRows.remove(workerId)
        }
        var processesChanged = 0
        for (row in processes.values.toList()) {
            if (row.state != ProcessState.RUNNING) {
                continue
            }
            val hasReady: Boolean = threads.values.any {
                it.pid == row.pid && it.state == ThreadState.READY
            }
            val hasRunning: Boolean = threads.values.any {
                it.pid == row.pid && it.state == ThreadState.RUNNING
            }
            if (hasReady && !hasRunning) {
                processes[row.pid] = row.copy(state = ProcessState.IDLE)
                processesChanged += 1
            }
        }
        return RecoveryReport(
            workers = expired,
            threads = threadsChanged,
            processes = processesChanged,
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun applyThreadPatch(record: ThreadRecord, patch: ThreadPatch): ThreadRecord {
        val blockedOn: String? = when (val field = patch.blockedOn) {
            PatchField.Absent -> record.blockedOn
            PatchField.Clear -> null
            is PatchField.Value<*> -> field.value as? String
        }
        val wakeAt: String? = when (val field = patch.wakeAt) {
            PatchField.Absent -> record.wakeAt
            PatchField.Clear -> null
            is PatchField.Value<*> -> field.value as? String
        }
        val sliceUsedMs: Long = when (val field = patch.sliceUsedMs) {
            PatchField.Absent -> record.sliceUsedMs
            PatchField.Clear -> 0L
            is PatchField.Value<*> -> field.value as? Long ?: record.sliceUsedMs
        }
        val finishedAt: String? = when (val field = patch.finishedAt) {
            PatchField.Absent -> record.finishedAt
            PatchField.Clear -> null
            is PatchField.Value<*> -> field.value as? String
        }
        val exitNote: String? = when (val field = patch.exitNote) {
            PatchField.Absent -> record.exitNote
            PatchField.Clear -> null
            is PatchField.Value<*> -> field.value as? String
        }
        return record.copy(
            blockedOn = blockedOn,
            wakeAt = wakeAt,
            sliceUsedMs = sliceUsedMs,
            finishedAt = finishedAt,
            exitNote = exitNote,
        )
    }

    private class Charged(val used: Budget, val exceeded: List<String>)

    /** `executionRepository.ts#charge`：逐维累加，为零/缺省的用量不记账。 */
    private fun charge(budget: Budget, usedInput: Budget, usage: Budget): Charged {
        var tokens: Int = usedInput.tokens ?: 0
        var ms: Long = usedInput.ms ?: 0L
        var calls: Int = usedInput.calls ?: 0
        var interrupts: Int = usedInput.interrupts ?: 0
        var money: Double = usedInput.money ?: 0.0
        val exceeded = ArrayList<String>()

        usage.tokens?.let { amount ->
            if (amount != 0) {
                tokens += amount
                budget.tokens?.let { limit -> if (tokens > limit) exceeded += "tokens" }
            }
        }
        usage.ms?.let { amount ->
            if (amount != 0L) {
                ms += amount
                budget.ms?.let { limit -> if (ms > limit) exceeded += "ms" }
            }
        }
        usage.calls?.let { amount ->
            if (amount != 0) {
                calls += amount
                budget.calls?.let { limit -> if (calls > limit) exceeded += "calls" }
            }
        }
        usage.interrupts?.let { amount ->
            if (amount != 0) {
                interrupts += amount
                budget.interrupts?.let { limit -> if (interrupts > limit) exceeded += "interrupts" }
            }
        }
        usage.money?.let { amount ->
            if (amount != 0.0) {
                money += amount
                budget.money?.let { limit -> if (money > limit) exceeded += "money" }
            }
        }
        return Charged(
            used = Budget(
                tokens = tokens,
                ms = ms,
                calls = calls,
                interrupts = interrupts,
                money = money,
            ),
            exceeded = exceeded,
        )
    }
}

/** `hash = digestOf({prev, kind, id, body})`，与参考 `putObject` 一致。 */
private fun objectHash(prev: Digest, kind: String, id: String, body: String): Digest =
    CanonicalJson.digest(
        jsonObj(
            "prev" to JsonValue.Str(prev),
            "kind" to JsonValue.Str(kind),
            "id" to JsonValue.Str(id),
            "body" to JsonValue.Str(body),
        ),
    )

/** 对象链校验结果；与 registry 的 `VerifyReport` 形状相同但保持内核自包含。 */
data class VerifyResult(
    val ok: Boolean,
    val reasons: List<String>,
)
