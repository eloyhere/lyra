package com.eloykim.lyra.amp.protocol.kernel

import com.eloykim.lyra.amp.protocol.attention.AttentionPlan
import com.eloykim.lyra.amp.protocol.concurrency.WorkerStore
import com.eloykim.lyra.amp.protocol.context.AgentContext
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.thread.ChargeResult
import com.eloykim.lyra.amp.protocol.thread.ThreadRecord
import com.eloykim.lyra.amp.protocol.thread.ThreadStore
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.window.ContextWindow
import com.eloykim.lyra.amp.protocol.window.WindowPageInput

/**
 * `src/kernel/scheduler.ts` 的类型面（`Kernel` 本体在 `KernelScheduler.kt`）。
 *
 * 命名上统一加 `Kernel` 前缀，避免与已存在的窄端口 `runtime/KernelRuntime.kt`
 * （其 `Kernel` / `StepReport` 是给认知运行时用的最小适配面）重名。
 *
 * `process.ts` 尚未移植，所以这里按参考文件原样声明内核真正用到的进程状态、记录与
 * 迁移谓词；它们只服务调度器与执行端口，不声称是完整的 process 模块。
 */

/** 进程（智能体）生命周期；`terminated` 没有出边。 */
enum class ProcessState(val wire: String) {
    CREATED("created"),
    RUNNING("running"),
    IDLE("idle"),
    SUSPENDED("suspended"),
    TERMINATED("terminated"),
    ;

    companion object {
        fun fromWire(value: String): ProcessState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 process state: $value")
    }
}

/** `src/process/transitions.ts` 的 `ProcessEdges`。 */
val ProcessEdges: Map<ProcessState, List<ProcessState>> = linkedMapOf(
    ProcessState.CREATED to listOf(
        ProcessState.RUNNING,
        ProcessState.IDLE,
        ProcessState.SUSPENDED,
        ProcessState.TERMINATED,
    ),
    ProcessState.RUNNING to listOf(
        ProcessState.IDLE,
        ProcessState.SUSPENDED,
        ProcessState.TERMINATED,
    ),
    ProcessState.IDLE to listOf(
        ProcessState.RUNNING,
        ProcessState.SUSPENDED,
        ProcessState.TERMINATED,
    ),
    ProcessState.SUSPENDED to listOf(
        ProcessState.RUNNING,
        ProcessState.IDLE,
        ProcessState.TERMINATED,
    ),
    ProcessState.TERMINATED to emptyList(),
)

fun canProcessTransition(from: ProcessState, to: ProcessState): Boolean =
    ProcessEdges.getValue(from).contains(to)

/** 参考实现 `process.ts` 的 `ProcessRecord`（只保留内核读取的字段形状）。 */
data class ProcessRecord(
    val pid: Int,
    val id: Id,
    val name: String,
    val role: String? = null,
    val state: ProcessState,
    val priority: Int,
    val budget: Budget,
    val used: Budget,
    val parentPid: Int? = null,
    val createdAt: Timestamp,
    val startedAt: Timestamp? = null,
    val terminatedAt: Timestamp? = null,
    val exitCode: String? = null,
    val exitNote: String? = null,
    /** SQL 行的乐观并发版本；参考 `ProcessRecord` 不暴露它，但执行端口需要自增。 */
    val version: Int = 0,
)

/** 参考实现 `ProcessPatch`。Kotlin 用 null 表示未提供；这些字段从不需要显式清空。 */
data class ProcessPatch(
    val startedAt: Timestamp? = null,
    val terminatedAt: Timestamp? = null,
    val exitCode: String? = null,
    val exitNote: String? = null,
)

/** `{tokens:0, ms:0, calls:0, interrupts:0, money:0}`。 */
fun emptyUsage(): Budget = Budget(tokens = 0, ms = 0, calls = 0, interrupts = 0, money = 0.0)

/** `StepStatus`：一次 step 的提交结果。 */
enum class KernelStepStatus(val wire: String) {
    YIELD("yield"),
    SLEEP("sleep"),
    BLOCK("block"),
    DONE("done"),
    FAIL("fail"),
    ;
}

/** 与 receipt 同事务追加的哈希链对象。 */
data class KernelStepObject(
    val kind: String,
    val id: Id,
    val body: JsonValue,
)

/** 由 event sink 在同一事务里写入的副作用。 */
data class KernelStepEvent(
    val topic: String,
    val payload: JsonValue,
)

/** 参考实现的 `StepEventSink`；数据库句柄由宿主闭包捕获，这里只传事件与时间。 */
fun interface KernelStepEventSink {
    fun emit(event: KernelStepEvent, at: Timestamp)
}

/**
 * `StepOutcome` 的密封层次：每个子类只带自己合法的字段（sleep 必有 wakeAt，block 必有
 * blockedOn），公共字段 `usage`/`note`/`objects`/`events` 与参考的 `StepBase` 一致。
 */
sealed class KernelStepOutcome(
    val usage: Budget? = null,
    val note: String? = null,
    val objects: List<KernelStepObject> = emptyList(),
    val events: List<KernelStepEvent> = emptyList(),
) {

    abstract val status: KernelStepStatus

    class Yield(
        usage: Budget? = null,
        note: String? = null,
        objects: List<KernelStepObject> = emptyList(),
        events: List<KernelStepEvent> = emptyList(),
    ) : KernelStepOutcome(usage, note, objects, events) {
        override val status: KernelStepStatus = KernelStepStatus.YIELD
    }

    class Sleep(
        val wakeAt: Timestamp,
        usage: Budget? = null,
        note: String? = null,
        objects: List<KernelStepObject> = emptyList(),
        events: List<KernelStepEvent> = emptyList(),
    ) : KernelStepOutcome(usage, note, objects, events) {
        override val status: KernelStepStatus = KernelStepStatus.SLEEP
    }

    class Block(
        val blockedOn: String,
        usage: Budget? = null,
        note: String? = null,
        objects: List<KernelStepObject> = emptyList(),
        events: List<KernelStepEvent> = emptyList(),
    ) : KernelStepOutcome(usage, note, objects, events) {
        override val status: KernelStepStatus = KernelStepStatus.BLOCK
    }

    class Done(
        usage: Budget? = null,
        note: String? = null,
        objects: List<KernelStepObject> = emptyList(),
        events: List<KernelStepEvent> = emptyList(),
    ) : KernelStepOutcome(usage, note, objects, events) {
        override val status: KernelStepStatus = KernelStepStatus.DONE
    }

    class Fail(
        usage: Budget? = null,
        note: String? = null,
        objects: List<KernelStepObject> = emptyList(),
        events: List<KernelStepEvent> = emptyList(),
    ) : KernelStepOutcome(usage, note, objects, events) {
        override val status: KernelStepStatus = KernelStepStatus.FAIL
    }
}

/** 一次 step 可见的上下文。 */
data class KernelStepContext(
    /** 参考实现的 `db` 在执行端口里的对应物；事件/对象仍由提交阶段统一写入。 */
    val execution: KernelExecutionStore,
    /** 执行本任务的智能体（进程）。 */
    val agent: ProcessRecord,
    /** 被执行的任务（线程）。 */
    val task: ThreadRecord,
    val now: Timestamp,
    /** 每任务上下文窗口；内容持久化在窗口页表里。 */
    val window: ContextWindow,
    /** 若宿主接了 agent reader，则在 step 前组装好的智能体上下文。 */
    val agentContext: AgentContext? = null,
    /** 若宿主接了 attention policy，则本 step 的进程/线程注意力计划。 */
    val attention: AttentionPlan? = null,
)

/** 参考实现的 `StepHandler`。 */
fun interface KernelStepHandler {
    fun handle(context: KernelStepContext): KernelStepOutcome
}

/** 参考实现的 `StepReport`。状态以 wire 字符串给出，便于直接写日志/回执。 */
data class KernelStepReport(
    val pid: Int,
    val tid: Int,
    val status: KernelStepStatus,
    val usage: Budget,
    val receiptId: Id,
    val processState: String,
    val threadState: String,
    val note: String? = null,
)

/** step 行，对应 SQL `steps` 表的插入。 */
data class KernelStepRecord(
    val pid: Int,
    val tid: Int,
    val at: Timestamp,
    val status: String,
    val usage: Budget,
    val receiptId: Id,
    val note: String? = null,
)

/** 参考实现 `KernelOptions`。 */
data class KernelSchedulerOptions(
    /** 注入时钟以便回放确定；缺省为与 JS `toISOString()` 同形的 UTC 毫秒时间。 */
    val now: (() -> Timestamp)? = null,
    val quantumMs: Long = 1000,
    /** 两个共享同一 store 的内核必须传入不同 id。 */
    val workerId: String? = null,
    /** 租约到期后其他 worker 可以回收本 worker 的线程。 */
    val leaseMs: Long = 15_000,
    /** 每个任务上下文窗口的 token 容量；参考默认 4000。 */
    val windowTokens: Int = 4000,
    /** 提交事务内的事件 sink。 */
    val eventSink: KernelStepEventSink? = null,
    /** 参考实现允许替换执行端口。 */
    val execution: KernelExecutionStore? = null,
    /** 智能体上下文读取器；内核本身不感知存储。 */
    val agentContext: ((Int) -> AgentContext?)? = null,
    /** 进程/线程注意力计划提供者；内核不依赖存储。 */
    val attention: ((Int, ThreadRecord) -> AttentionPlan?)? = null,
)

/** 对象链一行；`body` 是 canonical JSON 文本，与 `database/store/database.ts` 一致。 */
data class KernelObjectRow(
    val seq: Int,
    val kind: String,
    val id: String,
    val body: String,
    val hash: Digest,
    val prev: Digest,
    val at: Timestamp,
)

data class KernelPutObjectResult(
    val seq: Int,
    val hash: Digest,
)

/** `database/store/database.ts#putObject` 的窄端口。 */
interface KernelObjectStore {

    fun putObject(kind: String, id: String, body: JsonValue, at: Timestamp): KernelPutObjectResult

    fun find(kind: String, id: String): KernelObjectRow?
}

/** `WindowRepository` 的窄端口：读取与替换一个线程的页表。 */
interface KernelWindowStore {

    /** 按 `last_used ASC` 返回。 */
    fun pages(tid: Int): List<WindowPageInput>

    fun replacePages(tid: Int, pages: List<WindowPageInput>, at: Timestamp, capacityTokens: Int)
}

/**
 * `src/process/executionStore.ts` 的 `ExecutionStore` 端口。
 *
 * 复用已移植的 `thread/ThreadStore`（任务查询、迁移、claim、charge、唤醒、恢复），
 * 只补上内核另外需要的进程与 step 操作。[InMemoryKernelStore] 实现这一整套。
 */
interface KernelExecutionStore : ThreadStore {

    fun beginAgent(pid: Int, at: Timestamp): Boolean

    fun getAgent(pid: Int): ProcessRecord

    fun transitionAgent(
        pid: Int,
        from: List<ProcessState>,
        to: ProcessState,
        patch: ProcessPatch = ProcessPatch(),
    ): ProcessRecord

    fun chargeAgent(pid: Int, usage: Budget, at: Timestamp): ChargeResult

    fun terminateAgent(pid: Int, note: String, at: Timestamp, code: String = "TERMINATED"): ProcessRecord

    fun countSteps(tid: Int): Int

    fun writeStep(input: KernelStepRecord)

    fun closeWorker(workerId: String)
}

/** 内核依赖的四个存储面 + 提交事务。 */
interface KernelStore {

    val execution: KernelExecutionStore
    val windows: KernelWindowStore
    val objects: KernelObjectStore
    val workers: WorkerStore

    /** 参考实现 `transaction(db, fn)`：全部成功才提交，抛错则回滚。 */
    fun <T> transaction(block: () -> T): T
}

/** 参考实现 `Kernel.recover()` 的返回值。 */
data class KernelRecovery(
    val threads: Int,
    val processes: Int,
)
