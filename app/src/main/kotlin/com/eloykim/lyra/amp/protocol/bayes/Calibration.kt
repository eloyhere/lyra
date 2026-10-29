package com.eloykim.lyra.amp.protocol.bayes

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Post-hoc calibration utilities.
 *
 * 后验不能直接当成概率：这里度量 ECE / Brier，并只在评估时拟合单调
 * isotonic 映射或温度标量。小样本必须交叉验证，否则“改进”只是样本内过拟合。
 * 对应参考实现 `src/bayes/calibration.ts`。
 */
data class CalibrationPoint(
    val predicted: Double,
    val actual: Boolean,
)

/** isotonic 映射：每个分块的上界与观测频率。 */
data class IsotonicModel(
    val thresholds: List<Double>,
    val values: List<Double>,
)

/** 温度标量：T<1 更尖锐，T>1 更平缓。 */
data class TemperatureModel(
    val temperature: Double,
)

/** 一组预测的校准统计量。 */
data class CalibrationReport(
    val ece: Double,
    val brier: Double,
    val rawEce: Double,
    val rawBrier: Double,
)

/** 拟合温度时使用的网格。 */
val DefaultGrid: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0)

private data class Block(val sum: Double, val n: Int, val max: Double)

private data class Bucket(var n: Int, var confidence: Double, var accuracy: Double)

fun brier(points: List<CalibrationPoint>): Double {
    if (points.isEmpty()) return 0.0
    var sum = 0.0
    points.forEach { point ->
        val target = if (point.actual) 1.0 else 0.0
        val delta = point.predicted - target
        sum += delta * delta
    }
    return sum / points.size
}

/**
 * 期望校准误差（ECE）。
 *
 * 分桶用夹取后的预测值，但桶内 confidence 累计的是原始预测值——与参考实现一致。
 */
fun ece(points: List<CalibrationPoint>, bins: Int = 10): Double {
    if (points.isEmpty()) return 0.0
    val buckets = List(bins) { Bucket(0, 0.0, 0.0) }
    points.forEach { point ->
        val index = min(bins - 1, max(0, floor(clamp01(point.predicted) * bins).toInt()))
        buckets[index].n += 1
        buckets[index].confidence += point.predicted
        buckets[index].accuracy += if (point.actual) 1.0 else 0.0
    }
    var sum = 0.0
    buckets.forEach { bucket ->
        if (bucket.n == 0) return@forEach
        sum += (bucket.n.toDouble() / points.size) *
            abs(bucket.accuracy / bucket.n - bucket.confidence / bucket.n)
    }
    return sum
}

/**
 * Pool-adjacent-violators isotonic 回归。
 *
 * 相邻分块逆序时合并，使输出随预测值单调不减。
 */
fun fitIsotonic(points: List<CalibrationPoint>): IsotonicModel {
    val sorted = points.sortedBy { it.predicted }
    val blocks = ArrayList<Block>()
    sorted.forEach { point ->
        blocks += Block(sum = if (point.actual) 1.0 else 0.0, n = 1, max = point.predicted)
        while (blocks.size >= 2) {
            val current = blocks[blocks.size - 1]
            val previous = blocks[blocks.size - 2]
            if (previous.sum / previous.n <= current.sum / current.n) break
            blocks.removeAt(blocks.size - 1)
            blocks.removeAt(blocks.size - 1)
            blocks += Block(
                sum = previous.sum + current.sum,
                n = previous.n + current.n,
                max = current.max,
            )
        }
    }
    return IsotonicModel(
        thresholds = blocks.map { it.max },
        values = blocks.map { it.sum / it.n },
    )
}

fun applyIsotonic(model: IsotonicModel, predicted: Double): Double {
    if (model.thresholds.isEmpty()) return clamp01(predicted)
    val index = model.thresholds.indexOfFirst { predicted <= it }
    return model.values[if (index == -1) model.values.size - 1 else index]
}

private fun logit(value: Double): Double {
    val bounded = max(1e-6, min(1 - 1e-6, value))
    return ln(bounded / (1 - bounded))
}

private fun sigmoid(value: Double): Double = 1 / (1 + exp(-value))

/** 按 NLL 网格拟合温度。 */
fun fitTemperature(points: List<CalibrationPoint>, grid: List<Double> = DefaultGrid): TemperatureModel {
    if (points.isEmpty()) return TemperatureModel(1.0)
    var best = 1.0
    var bestLoss = Double.POSITIVE_INFINITY
    grid.forEach { temperature ->
        var loss = 0.0
        points.forEach { point ->
            val calibrated = sigmoid(logit(point.predicted) / temperature)
            val target = if (point.actual) 1.0 else 0.0
            loss -= target * ln(max(1e-9, calibrated)) + (1 - target) * ln(max(1e-9, 1 - calibrated))
        }
        if (loss < bestLoss) {
            bestLoss = loss
            best = temperature
        }
    }
    return TemperatureModel(best)
}

fun applyTemperature(model: TemperatureModel, predicted: Double): Double =
    sigmoid(logit(predicted) / model.temperature)

/** 交叉验证后的校准报告：ECE/Brier 在折外预测上计算，raw 用全体点。 */
fun crossValidateCalibration(points: List<CalibrationPoint>, folds: Int = 5): CalibrationReport {
    if (points.isEmpty()) return CalibrationReport(0.0, 0.0, 0.0, 0.0)
    val predictions = ArrayList<CalibrationPoint>()
    for (fold in 0 until folds) {
        val train = points.filterIndexed { index, _ -> index % folds != fold }
        val test = points.filterIndexed { index, _ -> index % folds == fold }
        if (train.isEmpty() || test.isEmpty()) continue
        val model = fitIsotonic(train)
        test.forEach { point ->
            predictions += CalibrationPoint(applyIsotonic(model, point.predicted), point.actual)
        }
    }
    return CalibrationReport(
        ece = ece(predictions),
        brier = brier(predictions),
        rawEce = ece(points),
        rawBrier = brier(points),
    )
}
