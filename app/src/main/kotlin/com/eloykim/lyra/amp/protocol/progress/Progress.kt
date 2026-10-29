package com.eloykim.lyra.amp.protocol.progress

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj

/**
 * `src/progress/progress.ts` 的忠实移植：进度是进程线程与步骤的只读投影。
 *
 * 进度从存储算出，绝不来自自我报告。`checkpoint` 追加一个 `progress` 对象，让 supervisor
 * 能把之后的视图与已提交版本比较，而不是相信内存计数器。
 *
 * ## 绑定
 *
 * 参考实现直接用 `node:sqlite` 查询 `processes` / `threads` / `steps` 表，并用
 * `putObject` 写对象链。Android 上没有 `node:sqlite`，这里定义窄端口：
 * [ProgressStore] 对应那三条查询，[ProgressObjectSink] 对应 `putObject`。
 * 具体 SQLite/对象链适配器由调用方提供；本文件只保留参考实现的聚合与舍入行为。
 */

data class ProgressSnapshot(
    val pid: Int,
    val state: String,
    val totalThreads: Int,
    val doneThreads: Int,
    val failedThreads: Int,
    val steps: Int,
    val tokens: Int,
    val percent: Double,
    val checkpoint: String? = null,
)

/** `SELECT state, used FROM processes WHERE pid = ?`；`used` 是 JSON 文本。 */
data class ProcessProgressRow(
    val state: String,
    val used: String,
)

/** `SELECT state, COUNT(*) AS n FROM threads WHERE pid = ? GROUP BY state`。 */
data class ThreadStateCount(
    val state: String,
    val n: Int,
)

interface ProgressStore {

    fun process(pid: Int): ProcessProgressRow?

    fun threadStates(pid: Int): List<ThreadStateCount>

    fun stepCount(pid: Int): Int
}

/** 对象链的窄写入端口：`putObject(db, kind, id, value, at)`。 */
interface ProgressObjectSink {

    fun putObject(kind: String, id: String, value: JsonValue.Obj, at: String)
}

fun computeProgress(store: ProgressStore, pid: Int): ProgressSnapshot {
    val process: ProcessProgressRow = store.process(pid)
        ?: throw IllegalStateException("unknown process $pid")
    val threads: List<ThreadStateCount> = store.threadStates(pid)
    val steps: Int = store.stepCount(pid)
    var total = 0
    var done = 0
    var failed = 0
    for (row in threads) {
        total += row.n
        if (row.state == "done") {
            done += row.n
        }
        if (row.state == "failed") {
            failed += row.n
        }
    }
    val used: JsonValue? = JsonParser.parse(process.used)
    val tokens: Int = (used as? JsonValue.Obj)?.int("tokens") ?: 0
    return ProgressSnapshot(
        pid = pid,
        state = process.state,
        totalThreads = total,
        doneThreads = done,
        failedThreads = failed,
        steps = steps,
        tokens = tokens,
        percent = if (total == 0) 0.0 else Math.round((done.toDouble() / total) * 1000).toDouble() / 10.0,
    )
}

/** 把当前视图追加为不可变的 `progress` 对象。 */
fun checkpointProgress(
    store: ProgressStore,
    sink: ProgressObjectSink,
    pid: Int,
    at: String,
): ProgressSnapshot {
    val snapshot: ProgressSnapshot = computeProgress(store, pid)
    val id = "pg.$pid.${snapshot.steps}"
    sink.putObject(
        "progress",
        id,
        jsonObj(
            "amp" to JsonValue.Str("0.1"),
            "kind" to JsonValue.Str("progress"),
            "id" to JsonValue.Str(id),
            "at" to JsonValue.Str(at),
            "pid" to jsonNumber(snapshot.pid),
            "state" to JsonValue.Str(snapshot.state),
            "totalThreads" to jsonNumber(snapshot.totalThreads),
            "doneThreads" to jsonNumber(snapshot.doneThreads),
            "failedThreads" to jsonNumber(snapshot.failedThreads),
            "steps" to jsonNumber(snapshot.steps),
            "tokens" to jsonNumber(snapshot.tokens),
            "percent" to jsonNumber(snapshot.percent),
        ),
        at,
    )
    return snapshot.copy(checkpoint = id)
}
