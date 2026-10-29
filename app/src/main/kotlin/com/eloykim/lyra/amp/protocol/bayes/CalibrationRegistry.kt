package com.eloykim.lyra.amp.protocol.bayes

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.asObject
import java.time.Instant

/**
 * 校准注册表。
 *
 * 只有样本量足够、且交叉验证 ECE 比 raw 至少改善 `minImprovement` 的映射才会
 * 被激活；否则该 scope 保持 raw。默认还要求 bootstrap 95% 上界小于 0，避免
 * 把“isotonic 反而更差”的模型发出去。对应参考实现
 * `src/bayes/calibrationRegistry.ts`。
 */
data class CalibrationModel(
    val scope: String,
    val method: CalibrationMethod,
    val samples: Int,
    val rawEce: Double,
    val cvEce: Double,
    val thresholds: List<Double>,
    val values: List<Double>,
    val temperature: Double? = null,
    val ciLower: Double,
    val ciUpper: Double,
    val activatedAt: String,
) {

    /**
     * 注册表记录投影。
     *
     * 字段名沿用参考实现里被持久化的对象键（camelCase），因为这里不是协议 wire
     * 对象，没有对应 schema；`temperature` 只在 method 为 temperature 时写入，
     * 与参考实现的展开条件一致。
     */
    fun toJson(): JsonValue.Obj = jsonObj(
        "scope" to JsonValue.Str(scope),
        "method" to JsonValue.Str(method.wire),
        "samples" to jsonNumber(samples),
        "rawEce" to jsonNumber(rawEce),
        "cvEce" to jsonNumber(cvEce),
        "thresholds" to JsonValue.Arr(thresholds.map { jsonNumber(it) }),
        "values" to JsonValue.Arr(values.map { jsonNumber(it) }),
        "temperature" to temperature?.let { jsonNumber(it) },
        "ciLower" to jsonNumber(ciLower),
        "ciUpper" to jsonNumber(ciUpper),
        "activatedAt" to JsonValue.Str(activatedAt),
    )

    companion object {
        fun fromJson(value: JsonValue): CalibrationModel {
            val obj = value.asObject("calibration-model")
            return CalibrationModel(
                scope = obj.requireString("scope"),
                method = CalibrationMethod.fromWire(obj.requireString("method")),
                samples = obj.requireInt("samples"),
                rawEce = obj.requireDouble("rawEce"),
                cvEce = obj.requireDouble("cvEce"),
                thresholds = obj.requireNumbers("thresholds"),
                values = obj.requireNumbers("values"),
                temperature = obj.double("temperature"),
                ciLower = obj.requireDouble("ciLower"),
                ciUpper = obj.requireDouble("ciUpper"),
                activatedAt = obj.requireString("activatedAt"),
            )
        }
    }
}

data class RegisterOptions(
    val minSamples: Int = 30,
    val minImprovement: Double = 0.01,
    val folds: Int = 5,
    /**
     * 要求 bootstrap 95% 上界小于 0；显式传 false 才跳过该门槛。
     */
    val requireCi: Boolean = true,
    val bootstrapSamples: Int = 500,
    val seed: Int = 42,
    val at: String? = null,
)

enum class RegisterStatus(val wire: String) {
    RAW("raw"),
    ACTIVATED("activated"),
}

data class RegisterResult(
    val status: RegisterStatus,
    val selection: CalibrationSelection,
    val model: CalibrationModel? = null,
)

class CalibrationRegistry {

    private val models = LinkedHashMap<String, CalibrationModel>()

    fun register(
        scope: String,
        points: List<CalibrationPoint>,
        options: RegisterOptions = RegisterOptions(),
    ): RegisterResult {
        val selection = selectCalibration(points, options.folds)
        val candidate = points.size >= options.minSamples &&
            selection.method != CalibrationMethod.RAW &&
            selection.rawEce - selection.ece >= options.minImprovement
        val ci = if (candidate) {
            bootstrapEceDelta(points, selection.method, options.folds, options.bootstrapSamples, options.seed)
        } else {
            BootstrapInterval(0.0, 0.0, 0.0)
        }
        val improves = candidate && (!options.requireCi || ci.upper < 0)
        if (!improves) {
            models.remove(scope)
            return RegisterResult(RegisterStatus.RAW, selection)
        }
        val isotonic = fitIsotonic(points)
        val temperature = fitTemperature(points)
        val record = CalibrationModel(
            scope = scope,
            method = selection.method,
            samples = points.size,
            rawEce = selection.rawEce,
            cvEce = selection.ece,
            thresholds = isotonic.thresholds,
            values = isotonic.values,
            temperature = if (selection.method == CalibrationMethod.TEMPERATURE) temperature.temperature else null,
            ciLower = ci.lower,
            ciUpper = ci.upper,
            activatedAt = options.at ?: Instant.now().toString(),
        )
        models[scope] = record
        return RegisterResult(RegisterStatus.ACTIVATED, selection, record)
    }

    fun calibrate(scope: String, predicted: Double): Double {
        val model = models[scope] ?: return clamp01(predicted)
        if (model.method == CalibrationMethod.TEMPERATURE) {
            return applyTemperature(TemperatureModel(model.temperature ?: 1.0), predicted)
        }
        val calibrated = applyIsotonic(IsotonicModel(model.thresholds, model.values), predicted)
        return if (model.method == CalibrationMethod.ISOTONIC) calibrated else 0.5 * calibrated + 0.5 * clamp01(predicted)
    }

    fun model(scope: String): CalibrationModel? = models[scope]

    fun scopes(): List<String> = models.keys.toList()
}

private fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")

private fun JsonValue.Obj.requireNumbers(key: String): List<Double> =
    arr(key)?.items?.map { item ->
        (item as? JsonValue.Num)?.value ?: throw IllegalArgumentException("$key 必须是数字数组")
    } ?: throw IllegalArgumentException("缺少数组字段 $key")
