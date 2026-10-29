package com.eloykim.lyra.amp.protocol.kernel

import com.eloykim.lyra.amp.protocol.concurrency.heartbeatWorker
import com.eloykim.lyra.amp.protocol.concurrency.recoverExpiredWorkers
import com.eloykim.lyra.amp.protocol.concurrency.registerWorker
import com.eloykim.lyra.amp.protocol.concurrency.unregisterWorker
import com.eloykim.lyra.amp.protocol.error.KernelCode
import com.eloykim.lyra.amp.protocol.error.KernelError
import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.receipt.ReceiptOp
import com.eloykim.lyra.amp.protocol.thread.PatchField
import com.eloykim.lyra.amp.protocol.thread.ThreadClaim
import com.eloykim.lyra.amp.protocol.thread.ThreadPatch
import com.eloykim.lyra.amp.protocol.thread.ThreadRecord
import com.eloykim.lyra.amp.protocol.thread.ThreadState
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.window.ContextWindow
import com.eloykim.lyra.amp.protocol.window.ContextWindowOptions
import com.eloykim.lyra.amp.protocol.window.ObjectRepository
import com.eloykim.lyra.amp.protocol.window.ObjectRow
import com.eloykim.lyra.amp.protocol.window.WindowPageInput
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * `src/kernel/scheduler.ts` 的忠实移植：进程是智能体，线程是任务/目标的持久调度器。
 *
 * 每次 [step] 挑一个就绪任务、用 fencing lease 认领它、在智能体为 `running` 时运行
 * handler，然后在一个事务里提交预算记账、handler 的对象、receipt 与 step 行。任务完成，
 * 智能体回到 `idle`，可以接下一个任务。
 *
 * ## 与参考实现的绑定
 *
 * 参考实现的构造函数接收 `DatabaseSync`，并直接持有 ObjectRepository/WindowRepository/
 * ExecutionStore。[KernelStore] 把四者组合起来；默认的 [InMemoryKernelStore] 实现了
 * 同一组语义。事件 sink 不再接收数据库句柄（宿主在闭包里捕获自己的连接）。
 *
 * ## 同步而非 async
 *
 * 参考实现另有 `stepAsync`/`runAsync`：claim 与 commit 保持短事务，await 的 handler 在
 * SQLite 写事务之外跑，飞行期间用心跳续租。Kotlin 端口不引入协程依赖，按本项目
 * `runtime/KernelRuntime.kt` 的约定把异步面留给宿主：在后台 dispatcher 上同步调用
 * [step]，需要长时间等待时由宿主自行心跳。因此本文件不提供 `stepAsync`。
 *
 * ## 时钟
 *
 * 缺省时钟输出与 JS `Date#toISOString()` 同形的 UTC 毫秒时间；测试应注入固定时钟。
 * worker id 缺省用随机后缀代替 Node 的 `process.pid`（Android 上可取 pid，但协议模块
 * 不依赖 android.*）。
 */
class KernelScheduler(
    private val store: KernelStore,
    options: KernelSchedulerOptions = KernelSchedulerOptions(),
) {

    val quantumMs: Long = options.quantumMs
    val workerId: String = options.workerId ?: defaultWorkerId()
    val leaseMs: Long = options.leaseMs
    val windowTokens: Int = options.windowTokens

    private val nowFn: () -> Timestamp = options.now ?: ::jsNow
    private val execution: KernelExecutionStore = options.execution ?: store.execution
    private val objects: KernelObjectStore = store.objects
    private val windows: KernelWindowStore = store.windows
    private val workers = store.workers
    private val agentContextFn = options.agentContext
    private val attentionFn = options.attention
    private val eventSink: KernelStepEventSink? = options.eventSink
    private var tick: Int = 0

    init {
        registerWorker(
            store = workers,
            input = com.eloykim.lyra.amp.protocol.concurrency.WorkerInput(
                workerId = workerId,
                leaseMs = leaseMs,
            ),
            at = now(),
        )
    }

    fun now(): Timestamp = nowFn()

    /** 回收过期 worker 名下的线程，再回收本 worker 自己的遗留 claim。 */
    fun recover(): KernelRecovery {
        val at: Timestamp = now()
        val expired = recoverExpiredWorkers(workers, at)
        val own = execution.recover(workerId)
        return KernelRecovery(
            threads = expired.threads + own.threads,
            processes = expired.processes + own.processes,
        )
    }

    /** 释放本 worker 的 claim 并删除注册行。 */
    fun close() {
        execution.closeWorker(workerId)
        unregisterWorker(workers, workerId)
    }

    /**
     * 跑一个任务 step；没有可运行任务时返回 null。
     *
     * claim 是原子的条件更新并返回 fencing lease：两个 worker 可能选中同一任务，只有
     * 一个认领成功。handler 抛错等价于 `status = "fail"`，note 取错误消息。
     */
    fun step(handler: KernelStepHandler): KernelStepReport? = stepInternal(taskId = null, handler = handler)

    /**
     * 只跑一个确切的任务，而不是“下一个就绪任务”。
     *
     * 参考实现把 `taskId` 参数放在 `stepAsync` 上；这里没有协程，但保留同一寻址语义，
     * 供 `runtime/KernelRuntime.kt` 的窄适配器按 tid 驱动。任务不存在时抛
     * `KernelError(unknown_thread)`，存在但非 ready 时返回 null。
     */
    fun stepTask(taskId: Int, handler: KernelStepHandler): KernelStepReport? =
        stepInternal(taskId = taskId, handler = handler)

    private fun stepInternal(taskId: Int?, handler: KernelStepHandler): KernelStepReport? {
        val prepared: Prepared = prepare(taskId) ?: return null
        val outcome: KernelStepOutcome = try {
            handler.handle(prepared.context)
        } catch (error: Exception) {
            KernelStepOutcome.Fail(note = error.message ?: error.toString())
        }
        return store.transaction {
            commit(
                pid = prepared.pid,
                tid = prepared.tid,
                claim = prepared.claim,
                outcome = outcome,
                at = prepared.at,
                window = prepared.context.window,
            )
        }
    }

    /**
     * 排空队列，并用 `maxSteps` 防止 bug 导致死循环。
     *
     * 参数用函数类型而不是 [KernelStepHandler]，并且调用时必须写命名参数
     * `kernel.run(handler = { ... })`：裸尾随 lambda `kernel.run { ... }` 会与 Kotlin
     * 标准库的零参 `run(block)` 竞争，且后者被优先选中，结果会静默变成作用域调用。
     */
    fun run(handler: (KernelStepContext) -> KernelStepOutcome, maxSteps: Int = 1000): List<KernelStepReport> {
        val reports = ArrayList<KernelStepReport>()
        val stepHandler = KernelStepHandler { context -> handler(context) }
        for (index in 0 until maxSteps) {
            val report: KernelStepReport = step(stepHandler) ?: break
            reports += report
        }
        return reports
    }

    /** 认领任务并组装 step 上下文；空闲时返回 null。 */
    private fun prepare(taskId: Int? = null): Prepared? {
        val at: Timestamp = now()
        tick += 1
        if (!heartbeatWorker(workers, workerId, at, leaseMs)) {
            registerWorker(
                store = workers,
                input = com.eloykim.lyra.amp.protocol.concurrency.WorkerInput(
                    workerId = workerId,
                    leaseMs = leaseMs,
                ),
                at = at,
            )
        }
        if (tick % 32 == 0) {
            recoverExpiredWorkers(workers, at)
        }
        execution.wake(at)

        val candidate: ThreadRecord? = if (taskId == null) {
            execution.nextReadyTask()
        } else {
            execution.getTask(taskId)
        }
        if (candidate == null || (taskId != null && candidate.state != ThreadState.READY)) {
            return null
        }

        val claim: ThreadClaim = try {
            execution.claimTask(candidate.tid, workerId)
        } catch (error: KernelError) {
            if (error.typedCode == KernelCode.CLAIM_FAILED) {
                return null
            }
            throw error
        }

        val started: Boolean = execution.beginAgent(candidate.pid, at)
        if (!started) {
            val current: ProcessRecord = execution.getAgent(candidate.pid)
            if (current.state != ProcessState.RUNNING) {
                execution.releaseTask(candidate.tid, workerId, claim.lease)
                return null
            }
        }

        val agent: ProcessRecord = execution.getAgent(candidate.pid)
        val task: ThreadRecord = execution.getTask(candidate.tid)
        val context = KernelStepContext(
            execution = execution,
            agent = agent,
            task = task,
            now = at,
            window = windowFor(candidate.tid, at),
            agentContext = agentContextFn?.invoke(candidate.pid),
            attention = attentionFn?.invoke(candidate.pid, task),
        )
        return Prepared(
            pid = candidate.pid,
            tid = candidate.tid,
            claim = claim,
            context = context,
            at = at,
        )
    }

    /** 提交一次 step；调用方已经包在 [KernelStore.transaction] 里。 */
    private fun commit(
        pid: Int,
        tid: Int,
        claim: ThreadClaim,
        outcome: KernelStepOutcome,
        at: Timestamp,
        window: ContextWindow,
    ): KernelStepReport {
        val task: ThreadRecord = execution.getTask(tid)
        if (task.state != ThreadState.RUNNING ||
            task.ownerWorker != claim.workerId ||
            task.lease != claim.lease
        ) {
            throw KernelError(
                KernelCode.LEASE_LOST,
                listOf("thread $tid is owned by ${task.ownerWorker ?: "none"} at lease ${task.lease}"),
            )
        }

        windows.replacePages(tid, window.snapshot(), at, windowTokens)
        val usage: Budget = usageOf(outcome)
        val taskCharge = execution.chargeTask(tid, usage, at)
        val agentCharge = execution.chargeAgent(pid, usage, at)

        if (!taskCharge.ok) {
            val receipt = writeReceipt(
                task = task,
                at = at,
                usage = usage,
                outcome = Outcome.BLOCKED,
                note = "task budget exceeded: ${taskCharge.exceeded.joinToString(", ")}",
            )
            writeStep(pid, tid, at, "budget", usage, receipt.id, "task budget exceeded")
            settleAgent(pid)
            return report(pid, tid, KernelStepStatus.FAIL, usage, receipt.id, "task budget exceeded")
        }

        if (!agentCharge.ok) {
            execution.transitionTask(
                tid = tid,
                from = listOf(ThreadState.RUNNING),
                to = ThreadState.FAILED,
                patch = ThreadPatch(
                    finishedAt = PatchField.Value(at),
                    exitNote = PatchField.Value("agent budget exceeded"),
                ),
                lease = claim.lease,
            )
            val receipt = writeReceipt(
                task = task,
                at = at,
                usage = usage,
                outcome = Outcome.BLOCKED,
                note = "agent budget exceeded: ${agentCharge.exceeded.joinToString(", ")}",
            )
            writeStep(pid, tid, at, "budget", usage, receipt.id, "agent budget exceeded")
            settleAgent(pid)
            return report(pid, tid, KernelStepStatus.FAIL, usage, receipt.id, "agent budget exceeded")
        }

        for (objectRow in outcome.objects) {
            objects.putObject(objectRow.kind, objectRow.id, objectRow.body, at)
        }
        if (outcome.objects.isNotEmpty()) {
            execution.notify("object:$pid")
        }

        return when (outcome) {
            is KernelStepOutcome.Done -> {
                execution.transitionTask(
                    tid = tid,
                    from = listOf(ThreadState.RUNNING),
                    to = ThreadState.DONE,
                    patch = ThreadPatch(
                        finishedAt = PatchField.Value(at),
                        exitNote = PatchField.Value("completed"),
                    ),
                    lease = claim.lease,
                )
                val receipt = writeReceipt(
                    task = task,
                    at = at,
                    usage = usage,
                    outcome = Outcome.SUCCESS,
                    note = outcome.note ?: "task completed",
                )
                settleAgent(pid)
                writeStep(pid, tid, at, "done", usage, receipt.id, outcome.note)
                emit(outcome.events, at)
                report(pid, tid, KernelStepStatus.DONE, usage, receipt.id, outcome.note)
            }

            is KernelStepOutcome.Fail -> {
                execution.transitionTask(
                    tid = tid,
                    from = listOf(ThreadState.RUNNING),
                    to = ThreadState.FAILED,
                    patch = ThreadPatch(
                        finishedAt = PatchField.Value(at),
                        exitNote = PatchField.Value(outcome.note ?: "failed"),
                    ),
                    lease = claim.lease,
                )
                val receipt = writeReceipt(
                    task = task,
                    at = at,
                    usage = usage,
                    outcome = Outcome.FAILURE,
                    note = outcome.note ?: "task failed",
                )
                settleAgent(pid)
                writeStep(pid, tid, at, "fail", usage, receipt.id, outcome.note)
                emit(outcome.events, at)
                report(pid, tid, KernelStepStatus.FAIL, usage, receipt.id, outcome.note)
            }

            is KernelStepOutcome.Sleep -> {
                execution.transitionTask(
                    tid = tid,
                    from = listOf(ThreadState.RUNNING),
                    to = ThreadState.BLOCKED,
                    patch = ThreadPatch(
                        wakeAt = PatchField.Value(outcome.wakeAt),
                        blockedOn = PatchField.Clear,
                        exitNote = outcome.note?.let { PatchField.Value(it) } ?: PatchField.Absent,
                    ),
                    lease = claim.lease,
                )
                val receipt = writeReceipt(
                    task = task,
                    at = at,
                    usage = usage,
                    outcome = Outcome.PARTIAL,
                    note = outcome.note ?: "sleeps until ${outcome.wakeAt}",
                )
                settleAgent(pid)
                writeStep(pid, tid, at, "sleep", usage, receipt.id, outcome.note)
                emit(outcome.events, at)
                report(pid, tid, KernelStepStatus.SLEEP, usage, receipt.id, outcome.note)
            }

            is KernelStepOutcome.Block -> {
                execution.transitionTask(
                    tid = tid,
                    from = listOf(ThreadState.RUNNING),
                    to = ThreadState.BLOCKED,
                    patch = ThreadPatch(
                        blockedOn = PatchField.Value(outcome.blockedOn),
                        wakeAt = PatchField.Clear,
                        exitNote = outcome.note?.let { PatchField.Value(it) } ?: PatchField.Absent,
                    ),
                    lease = claim.lease,
                )
                val receipt = writeReceipt(
                    task = task,
                    at = at,
                    usage = usage,
                    outcome = Outcome.PARTIAL,
                    note = outcome.note ?: "blocked on ${outcome.blockedOn}",
                )
                settleAgent(pid)
                writeStep(pid, tid, at, "block", usage, receipt.id, outcome.note)
                emit(outcome.events, at)
                report(pid, tid, KernelStepStatus.BLOCK, usage, receipt.id, outcome.note)
            }

            is KernelStepOutcome.Yield -> {
                execution.transitionTask(
                    tid = tid,
                    from = listOf(ThreadState.RUNNING),
                    to = ThreadState.READY,
                    patch = ThreadPatch(
                        blockedOn = PatchField.Clear,
                        wakeAt = PatchField.Clear,
                    ),
                    lease = claim.lease,
                )
                val receipt = writeReceipt(
                    task = task,
                    at = at,
                    usage = usage,
                    outcome = Outcome.SUCCESS,
                    note = outcome.note ?: "quantum yielded",
                )
                settleAgent(pid)
                writeStep(pid, tid, at, "yield", usage, receipt.id, outcome.note)
                emit(outcome.events, at)
                report(pid, tid, KernelStepStatus.YIELD, usage, receipt.id, outcome.note)
            }
        }
    }

    private fun emit(events: List<KernelStepEvent>, at: Timestamp) {
        events.forEach { event -> eventSink?.emit(event, at) }
    }

    /** 智能体只在任务运行时是 `running`，否则是 `idle`。 */
    private fun settleAgent(pid: Int) {
        val agent: ProcessRecord = execution.getAgent(pid)
        if (agent.state == ProcessState.TERMINATED || agent.state == ProcessState.SUSPENDED) {
            return
        }
        if (execution.runningTaskCount(pid) > 0) {
            if (agent.state != ProcessState.RUNNING) {
                execution.transitionAgent(pid, listOf(agent.state), ProcessState.RUNNING)
            }
            return
        }
        if (agent.state == ProcessState.RUNNING) {
            execution.transitionAgent(pid, listOf(ProcessState.RUNNING), ProcessState.IDLE)
        }
    }

    /** 载入任务的页表并为本 step 构建窗口。 */
    private fun windowFor(tid: Int, at: Timestamp): ContextWindow {
        val window = ContextWindow(
            ContextWindowOptions(
                capacityTokens = windowTokens,
                objects = ObjectRepository { kind, id ->
                    objects.find(kind, id)?.let { row -> ObjectRow(row.body) }
                },
                now = { Instant.parse(at).toEpochMilli() },
            ),
        )
        val pages: List<WindowPageInput> = windows.pages(tid)
        if (pages.isNotEmpty()) {
            window.restore(pages)
        }
        return window
    }

    private fun writeReceipt(
        task: ThreadRecord,
        at: Timestamp,
        usage: Budget,
        outcome: Outcome,
        note: String,
    ): Receipt {
        val count: Int = execution.countSteps(task.tid)
        val receipt = Receipt(
            id = "rc.${task.tid}.${count + 1}",
            op = ReceiptOp.ACT,
            target = Ref(kind = RefKind.THREAD, id = task.id),
            at = at,
            usage = usage,
            outcome = outcome,
            note = note,
        )
        objects.putObject(kind = "receipt", id = receipt.id, body = receipt.toJson(), at = at)
        return receipt
    }

    private fun writeStep(
        pid: Int,
        tid: Int,
        at: Timestamp,
        status: String,
        usage: Budget,
        receiptId: Id,
        note: String?,
    ) {
        execution.writeStep(
            KernelStepRecord(
                pid = pid,
                tid = tid,
                at = at,
                status = status,
                usage = usage,
                receiptId = receiptId,
                note = note,
            ),
        )
    }

    private fun report(
        pid: Int,
        tid: Int,
        status: KernelStepStatus,
        usage: Budget,
        receiptId: Id,
        note: String?,
    ): KernelStepReport {
        val agent: ProcessRecord = execution.getAgent(pid)
        val task: ThreadRecord = execution.getTask(tid)
        return KernelStepReport(
            pid = pid,
            tid = tid,
            status = status,
            usage = usage,
            receiptId = receiptId,
            processState = agent.state.wire,
            threadState = task.state.wire,
            note = note,
        )
    }

    /** `{calls: 1, ...outcome.usage}`。 */
    private fun usageOf(outcome: KernelStepOutcome): Budget {
        val usage: Budget? = outcome.usage
        return Budget(
            tokens = usage?.tokens,
            ms = usage?.ms,
            calls = usage?.calls ?: 1,
            interrupts = usage?.interrupts,
            money = usage?.money,
        )
    }

    private data class Prepared(
        val pid: Int,
        val tid: Int,
        val claim: ThreadClaim,
        val context: KernelStepContext,
        val at: Timestamp,
    )
}

private fun defaultWorkerId(): String =
    "w-" + UUID.randomUUID().toString().replace("-", "").take(6)

private val JS_ISO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC)

/** `new Date().toISOString()` 的同形输出（UTC、三位毫秒）。 */
fun jsNow(): Timestamp = JS_ISO.format(Instant.now())
