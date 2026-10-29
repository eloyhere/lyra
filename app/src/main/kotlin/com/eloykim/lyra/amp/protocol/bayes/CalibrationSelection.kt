package com.eloykim.lyra.amp.protocol.bayes

import kotlin.math.floor

/**
 * 校准模型选择：raw / isotonic / 收缩 isotonic / temperature 各自交叉验证，
 * 取 ECE 最低的一个；并列时偏好 raw。对应参考实现
 * `src/bayes/calibrationSelection.ts`。
 */
enum class CalibrationMethod(val wire: String) {
    RAW("raw"),
    ISOTONIC("isotonic"),
    SHRUNK("shrunk"),
    TEMPERATURE("temperature"),
    ;

    companion object {
        fun fromWire(value: String): CalibrationMethod =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 calibration method: $value")
    }
}

data class CalibrationSelection(
    val method: CalibrationMethod,
    val ece: Double,
    val brier: Double,
    val rawEce: Double,
    val rawBrier: Double,
)

/** 一条折外预测。 */
data class CvPrediction(
    val predicted: Double,
    val actual: Boolean,
)

/** 配对 bootstrap 的百分位区间；negative 表示校准后 ECE 更低。 */
data class BootstrapInterval(
    val mean: Double,
    val lower: Double,
    val upper: Double,
)

private data class CalibrationScore(val ece: Double, val brier: Double)

private fun predict(
    method: CalibrationMethod,
    isotonic: IsotonicModel,
    temperature: TemperatureModel,
    value: Double,
): Double {
    if (method == CalibrationMethod.RAW) return clamp01(value)
    if (method == CalibrationMethod.TEMPERATURE) return applyTemperature(temperature, value)
    val calibrated = applyIsotonic(isotonic, value)
    return if (method == CalibrationMethod.ISOTONIC) calibrated else 0.5 * calibrated + 0.5 * value
}

/** 每个校准点一条折外预测。 */
fun crossValidatedPredictions(
    points: List<CalibrationPoint>,
    method: CalibrationMethod,
    folds: Int,
): List<CvPrediction> {
    val predictions = ArrayList<CvPrediction>()
    for (fold in 0 until folds) {
        val train = points.filterIndexed { index, _ -> index % folds != fold }
        val test = points.filterIndexed { index, _ -> index % folds == fold }
        if (train.isEmpty() || test.isEmpty()) continue
        val isotonic = fitIsotonic(train)
        val temperature = fitTemperature(train)
        test.forEach { point ->
            predictions += CvPrediction(predict(method, isotonic, temperature, point.predicted), point.actual)
        }
    }
    return predictions
}

/** 参考实现里 `CvPrediction` 与 `CalibrationPoint` 结构相同，可互换传入。 */
private fun asPoint(prediction: CvPrediction): CalibrationPoint =
    CalibrationPoint(predicted = prediction.predicted, actual = prediction.actual)

private fun crossValidate(
    points: List<CalibrationPoint>,
    method: CalibrationMethod,
    folds: Int,
): CalibrationScore {
    val predictions = crossValidatedPredictions(points, method, folds).map(::asPoint)
    return CalibrationScore(ece(predictions), brier(predictions))
}

/** 参考实现里的 mulberry32：32 位溢出算术，逐位复刻。 */
private class Mulberry32(seed: Int) {

    private var state: Int = seed

    fun next(): Double {
        state += 0x6d2b79f5
        var value = (state xor (state ushr 15)) * (1 or state)
        value = value xor (value + (value xor (value ushr 7)) * (61 or value))
        return ((value xor (value ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

/** ECE(calibrated) - ECE(raw) 的配对 bootstrap；负值表示改进。 */
fun bootstrapEceDelta(
    points: List<CalibrationPoint>,
    method: CalibrationMethod,
    folds: Int = 5,
    samples: Int = 500,
    seed: Int = 42,
): BootstrapInterval {
    val raw = crossValidatedPredictions(points, CalibrationMethod.RAW, folds).map(::asPoint)
    val calibrated = crossValidatedPredictions(points, method, folds).map(::asPoint)
    if (raw.isEmpty() || raw.size != calibrated.size) return BootstrapInterval(0.0, 0.0, 0.0)
    val random = Mulberry32(seed)
    val deltas = ArrayList<Double>(samples)
    repeat(samples) {
        val left = ArrayList<CalibrationPoint>(raw.size)
        val right = ArrayList<CalibrationPoint>(raw.size)
        for (index in raw.indices) {
            val pick = floor(random.next() * raw.size).toInt()
            left += raw[pick]
            right += calibrated[pick]
        }
        deltas += ece(right) - ece(left)
    }
    deltas.sort()
    val mean = deltas.sum() / deltas.size
    return BootstrapInterval(
        mean = mean,
        lower = deltas.getOrNull(floor(deltas.size * 0.025).toInt()) ?: mean,
        upper = deltas.getOrNull(floor(deltas.size * 0.975).toInt()) ?: mean,
    )
}

/**
 * 选出交叉验证 ECE 最低的校准方法。
 *
 * 参考实现的比较器对“两个非 raw 方法 ECE 相同”的情形不是全序；这里用稳定排序
 * 表达同样的意图：ECE 升序，ECE 相同时 raw 在前，其余保持候选顺序。
 */
fun selectCalibration(points: List<CalibrationPoint>, folds: Int = 5): CalibrationSelection {
    val rawEce = ece(points)
    val rawBrier = brier(points)
    val candidates = ArrayList<CalibrationSelection>()
    candidates += CalibrationSelection(CalibrationMethod.RAW, rawEce, rawBrier, rawEce, rawBrier)
    listOf(CalibrationMethod.ISOTONIC, CalibrationMethod.SHRUNK, CalibrationMethod.TEMPERATURE).forEach { method ->
        val score = crossValidate(points, method, folds)
        candidates += CalibrationSelection(method, score.ece, score.brier, rawEce, rawBrier)
    }
    return candidates.sortedWith(
        compareBy<CalibrationSelection> { it.ece }
            .thenBy { if (it.method == CalibrationMethod.RAW) 0 else 1 },
    ).first()
}
