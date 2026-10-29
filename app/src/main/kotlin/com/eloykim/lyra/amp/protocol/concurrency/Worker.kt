package com.eloykim.lyra.amp.protocol.concurrency

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `src/concurrency/worker.ts` 的忠实移植：worker 注册表——现在谁有权运行线程。
 *
 * worker 用租约注册，并在每一步心跳。只有 owner 租约已过期的线程才能被其他 worker 恢复；
 * 陈旧 owner 的写入由 fencing token（`threads.lease`）拒绝，而不是处理器内部的墙钟比较。
 *
 * ## 绑定
 *
 * 参考实现直接执行 6 条 SQL（upsert / update / delete / select / 过期恢复事务）。这里把这些
 * 语句收敛成 [WorkerStore] 窄端口；[InMemoryWorkerStore] 是 Android 参考实现，保留相同的
 * `expires_at <= at`、fencing 自增与 process 回 idle 语义。SQLite 适配器应按参考 SQL 原样
 * 实现同一端口。
 */
data class WorkerInput(
    val workerId: String,
    val host: String? = null,
    val pid: Int? = null,
    val leaseMs: Long? = null,
)

data class WorkerRecord(
    val workerId: String,
    val startedAt: String,
    val heartbeatAt: String,
    val expiresAt: String,
    val host: String? = null,
    val pid: Int? = null,
)

data class RecoveryReport(
    val workers: List<String>,
    val threads: Int,
    val processes: Int,
)

interface WorkerStore {

    /**
     * `INSERT ... ON CONFLICT(worker_id) DO UPDATE`：冲突时只更新 host/pid/heartbeat/
     * expires，`started_at` 保持首次注册值。
     */
    fun upsert(
        input: WorkerInput,
        startedAt: String,
        heartbeatAt: String,
        expiresAt: String,
    ): WorkerRecord

    /** `UPDATE workers ... WHERE worker_id = ?`；恰好更新一行时为 true。 */
    fun updateHeartbeat(workerId: String, at: String, expiresAt: String): Boolean

    fun delete(workerId: String)

    /** 按 `worker_id ASC` 排序。 */
    fun all(): List<WorkerRecord>

    /**
     * 在一个事务里：把过期 worker 名下的 running 线程改回 ready（owner 清空、lease+1、
     * slice 清零），删除过期 worker，再把“有 ready、无 running”的 running process 改回
     * idle。返回的 worker 列表保持过期查询的行顺序。
     */
    fun recoverExpired(at: String): RecoveryReport
}

private val JS_ISO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

/**
 * `new Date(Date.parse(at) + leaseMs).toISOString()`。
 *
 * JS 的 `toISOString` 总是输出三位毫秒，这里保持一致，避免字符串比较时与参考结果分叉。
 */
fun expiry(at: String, leaseMs: Long): String =
    JS_ISO.format(Instant.parse(at).plusMillis(leaseMs))

fun registerWorker(store: WorkerStore, input: WorkerInput, at: String): WorkerRecord {
    val leaseMs: Long = input.leaseMs ?: 15_000L
    return store.upsert(
        input = input,
        startedAt = at,
        heartbeatAt = at,
        expiresAt = expiry(at, leaseMs),
    )
}

fun heartbeatWorker(
    store: WorkerStore,
    workerId: String,
    at: String,
    leaseMs: Long = 15_000L,
): Boolean = store.updateHeartbeat(workerId, at, expiry(at, leaseMs))

fun unregisterWorker(store: WorkerStore, workerId: String) {
    store.delete(workerId)
}

fun listWorkers(store: WorkerStore): List<WorkerRecord> = store.all()

fun recoverExpiredWorkers(store: WorkerStore, at: String): RecoveryReport = store.recoverExpired(at)

/**
 * Android 参考实现：内存表 + 与参考 SQL 相同的过期恢复行为。
 *
 * `threads`/`processes` 只保留参考 SQL 实际读写的列；kernel 适配器接管这两张表后可以不再
 * 使用这里的 seed 方法。
 */
class InMemoryWorkerStore : WorkerStore {

    class StoredThread(
        val pid: Int,
        var state: String,
        var ownerWorker: String?,
        var lease: Int,
        var sliceUsedMs: Long = 0,
    )

    private val workers = LinkedHashMap<String, WorkerRecord>()
    private val threads = ArrayList<StoredThread>()
    private val processes = LinkedHashMap<Int, String>()

    @Synchronized
    override fun upsert(
        input: WorkerInput,
        startedAt: String,
        heartbeatAt: String,
        expiresAt: String,
    ): WorkerRecord {
        val existing: WorkerRecord? = workers[input.workerId]
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
        workers[input.workerId] = record
        return record
    }

    @Synchronized
    override fun updateHeartbeat(workerId: String, at: String, expiresAt: String): Boolean {
        val existing: WorkerRecord = workers[workerId] ?: return false
        workers[workerId] = existing.copy(heartbeatAt = at, expiresAt = expiresAt)
        return true
    }

    @Synchronized
    override fun delete(workerId: String) {
        workers.remove(workerId)
    }

    @Synchronized
    override fun all(): List<WorkerRecord> = workers.values.sortedBy { it.workerId }

    @Synchronized
    override fun recoverExpired(at: String): RecoveryReport {
        val expired: List<String> = workers.values
            .filter { it.expiresAt <= at }
            .map { it.workerId }
        var threadsChanged = 0
        for (workerId in expired) {
            for (thread in threads) {
                if (thread.state == "running" && thread.ownerWorker == workerId) {
                    thread.state = "ready"
                    thread.ownerWorker = null
                    thread.lease += 1
                    thread.sliceUsedMs = 0
                    threadsChanged += 1
                }
            }
        }
        for (workerId in expired) {
            workers.remove(workerId)
        }
        var processesChanged = 0
        for ((pid, state) in processes) {
            if (state != "running") {
                continue
            }
            val hasReady: Boolean = threads.any { it.pid == pid && it.state == "ready" }
            val hasRunning: Boolean = threads.any { it.pid == pid && it.state == "running" }
            if (hasReady && !hasRunning) {
                processes[pid] = "idle"
                processesChanged += 1
            }
        }
        return RecoveryReport(workers = expired, threads = threadsChanged, processes = processesChanged)
    }

    // ------------------------------------------------------------------
    // kernel 表的最小 seed / 读取入口（供测试与适配器使用）
    // ------------------------------------------------------------------

    @Synchronized
    fun addThread(pid: Int, state: String, ownerWorker: String?, lease: Int): StoredThread {
        val thread = StoredThread(pid = pid, state = state, ownerWorker = ownerWorker, lease = lease)
        threads.add(thread)
        return thread
    }

    @Synchronized
    fun addProcess(pid: Int, state: String) {
        processes[pid] = state
    }

    @Synchronized
    fun threads(): List<StoredThread> = threads.toList()

    @Synchronized
    fun processState(pid: Int): String? = processes[pid]
}
