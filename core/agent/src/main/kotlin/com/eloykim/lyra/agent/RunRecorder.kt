package com.eloykim.lyra.agent

/**
 * 操作历史的写入端口。
 *
 * core:agent 不认识 SQLite —— 只依赖这个接口；实现放在 :core:data。
 */
interface RunRecorder {

    suspend fun startRun(task: String): Long

    suspend fun recordStep(runId: Long, step: StepRecord)

    suspend fun finishRun(runId: Long, status: String, summary: String)

    data object Noop : RunRecorder {
        override suspend fun startRun(task: String): Long = 0L

        override suspend fun recordStep(runId: Long, step: StepRecord) = Unit

        override suspend fun finishRun(runId: Long, status: String, summary: String) = Unit
    }
}

/**
 * 一条步骤记录。字段刻意保持扁平，方便直接进 SQLite 和后续做画像聚合。
 */
data class StepRecord(
    val seq: Int,
    val kind: String,
    val action: String? = null,
    val packageName: String? = null,
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val nodeId: Int? = null,
    val ok: Boolean? = null,
    val code: String? = null,
    val message: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
)
