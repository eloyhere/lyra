package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * 协调指标（`src/cooperation/metrics.ts` 的忠实移植）。
 *
 * 协调不是免费的，所以必须被度量。四个数字全部来自账本与任务板——绝不来自
 * 自我报告。
 */
data class CoordinationMetrics(
    /** 被拒绝的重复任务 / 全部尝试任务。 */
    val duplicateTaskRate: Double,
    /** 人工中断 / （工具调用 + 中断）。对齐开销的代理指标。 */
    val alignmentTokenShare: Double,
    /** 每个已完成任务的 token 成本。 */
    val costPerSuccess: Double,
    /** 有争议的 subject / 全部合并后的 subject。 */
    val contestedRate: Double,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "duplicateTaskRate" to jsonNumber(duplicateTaskRate),
        "alignmentTokenShare" to jsonNumber(alignmentTokenShare),
        "costPerSuccess" to jsonNumber(costPerSuccess),
        "contestedRate" to jsonNumber(contestedRate),
    )

    companion object {
        fun fromJson(value: JsonValue): CoordinationMetrics {
            val obj = value.asObject("coordination metrics")
            return CoordinationMetrics(
                duplicateTaskRate = obj.double("duplicateTaskRate")
                    ?: throw IllegalArgumentException("缺少数字字段 duplicateTaskRate"),
                alignmentTokenShare = obj.double("alignmentTokenShare")
                    ?: throw IllegalArgumentException("缺少数字字段 alignmentTokenShare"),
                costPerSuccess = obj.double("costPerSuccess")
                    ?: throw IllegalArgumentException("缺少数字字段 costPerSuccess"),
                contestedRate = obj.double("contestedRate")
                    ?: throw IllegalArgumentException("缺少数字字段 contestedRate"),
            )
        }
    }
}

/** 指标来源：任务板、统一快照与可选的合并结果。 */
data class CoordinationInput(
    val board: TaskBoard,
    val snapshot: UnifiedSnapshot,
    val merge: MergeResult? = null,
)

/** 与参考实现一致的千分位取整。 */
private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

/**
 * 从任务板、账本与合并结果推导协调指标。
 */
fun coordinationMetrics(input: CoordinationInput): CoordinationMetrics {
    val stats = input.board.stats()
    val done = input.board.list().count { it.state == TaskState.DONE }
    val knowledgeOps = input.snapshot.calls + input.snapshot.interruptsGranted
    val subjects = input.merge?.merged?.map { it.subject }?.toSet()?.size ?: 0

    return CoordinationMetrics(
        duplicateTaskRate = if (stats.added == 0) 0.0 else round3(stats.rejected.toDouble() / stats.added),
        alignmentTokenShare = if (knowledgeOps == 0) {
            0.0
        } else {
            round3(input.snapshot.interruptsGranted.toDouble() / knowledgeOps)
        },
        costPerSuccess = if (done == 0) 0.0 else round3(input.snapshot.tokens.toDouble() / done),
        contestedRate = if (subjects == 0) {
            0.0
        } else {
            round3((input.merge?.conflicts?.size ?: 0).toDouble() / subjects)
        },
    )
}
