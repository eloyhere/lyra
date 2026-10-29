package com.eloykim.lyra.attention

/**
 * 指标（AMP §13）：把回执变成可比较的三个数字。
 *
 * - `voiMae`：预测价值与实际价值的平均绝对误差，衡量「说不准自己有没有用」；
 * - `wastedAttentionRatio`：花在低价值步骤上的 token 占比；
 * - `impasseRate`：每个任务平均卡住几次。
 *
 * 纯计算，不依赖存储；采样由调用方从回执里提取。
 */
data class ReceiptSample(
    val tokensIn: Int,
    val tokensOut: Int,
    val costMicros: Long,
    val predictedVoi: Double,
    val realizedVoi: Double,
) {
    val tokens: Int get() = tokensIn + tokensOut
}

data class Metrics(
    val receipts: Int,
    val runs: Int,
    val impasses: Int,
    val voiMae: Double,
    val wastedAttentionRatio: Double,
    val impasseRate: Double,
    val totalTokens: Int,
    val totalCostMicros: Long,
    /** 校准是否可信：样本太少时指标仅供参考。 */
    val reliable: Boolean,
)

object MetricsCalculator {

    /** 少于这个样本数时 `reliable=false`，避免用 3 条数据下结论。 */
    const val MIN_SAMPLES = 10

    /** 低于这个实现价值的步骤算「浪费」。 */
    private const val WASTE_THRESHOLD = 0.5

    fun compute(
        receipts: List<ReceiptSample>,
        runs: Int,
        impasses: Int,
    ): Metrics {
        // 老回执没有 VOI 字段（predicted/realized 都为 0）：不参与校准，否则会长期污染看板
        val usable = receipts.filterNot { it.predictedVoi == 0.0 && it.realizedVoi == 0.0 }
        val withPrediction = usable.filter { it.predictedVoi > 0.0 }
        val voiMae = if (withPrediction.isEmpty()) {
            0.0
        } else {
            withPrediction.sumOf { kotlin.math.abs(it.predictedVoi - it.realizedVoi) } /
                withPrediction.size
        }

        val totalTokens = usable.sumOf { it.tokens }
        val wastedTokens = usable
            .filter { it.realizedVoi < WASTE_THRESHOLD }
            .sumOf { it.tokens }
        val wastedRatio = if (totalTokens == 0) 0.0 else wastedTokens.toDouble() / totalTokens

        return Metrics(
            receipts = usable.size,
            runs = runs,
            impasses = impasses,
            voiMae = voiMae,
            wastedAttentionRatio = wastedRatio,
            impasseRate = if (runs == 0) 0.0 else impasses.toDouble() / runs,
            totalTokens = totalTokens,
            totalCostMicros = usable.sumOf { it.costMicros },
            reliable = usable.size >= MIN_SAMPLES,
        )
    }
}
