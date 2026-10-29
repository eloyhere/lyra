package com.eloykim.lyra.amp.protocol.bayes

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.asObject
import kotlin.math.max

/**
 * 从带标签反馈学到的来源可靠度。
 *
 * 引擎里的静态 `Evidence.reliability` 说的是“这类特征本身很弱”；这张表说的是
 * “这个具体来源历史上对过/错过”。未见过的来源默认权重 1，因此在有标签之前行为
 * 不变。对应参考实现 `src/bayes/reliability.ts`。
 */
data class ReliabilityBelief(
    val alpha: Double,
    val beta: Double,
    val observations: Int,
) {

    init {
        require(alpha.isFinite() && alpha >= 0.0) { "reliability.alpha 必须是非负有限数" }
        require(beta.isFinite() && beta >= 0.0) { "reliability.beta 必须是非负有限数" }
        require(observations >= 0) { "reliability.observations 必须 >= 0" }
    }

    /** 持久化投影，字段与参考实现的 `ReliabilityBelief` 一致。 */
    fun toJson(): JsonValue.Obj = jsonObj(
        "alpha" to jsonNumber(alpha),
        "beta" to jsonNumber(beta),
        "observations" to jsonNumber(observations),
    )

    companion object {
        fun fromJson(value: JsonValue): ReliabilityBelief {
            val obj = value.asObject("reliability-belief")
            return ReliabilityBelief(
                alpha = obj.requireDouble("alpha"),
                beta = obj.requireDouble("beta"),
                observations = obj.requireInt("observations"),
            )
        }
    }
}

/** 学习先验形状，默认 Beta(2, 2)。 */
data class ReliabilityPrior(
    val alpha: Double = 2.0,
    val beta: Double = 2.0,
) {

    init {
        require(alpha.isFinite() && alpha >= 0.0) { "reliability 先验 alpha 必须是非负有限数" }
        require(beta.isFinite() && beta >= 0.0) { "reliability 先验 beta 必须是非负有限数" }
    }
}

class SourceReliability(private val prior: ReliabilityPrior = ReliabilityPrior()) {

    private val beliefs = LinkedHashMap<String, ReliabilityBelief>()

    /** 记录一次观察；`weight` 按 0 下限截断，`correct` 决定落到 alpha 还是 beta。 */
    fun observe(source: String, correct: Boolean, weight: Double = 1.0): ReliabilityBelief {
        val current = beliefs[source] ?: ReliabilityBelief(prior.alpha, prior.beta, 0)
        val amount = max(0.0, weight)
        val next = current.copy(
            alpha = current.alpha + if (correct) amount else 0.0,
            beta = current.beta + if (correct) 0.0 else amount,
            observations = current.observations + 1,
        )
        beliefs[source] = next
        return next
    }

    /** 未见过的来源返回 1，保持既有行为不变。 */
    fun weight(source: String): Double {
        val belief = beliefs[source] ?: return 1.0
        val total = belief.alpha + belief.beta
        return if (total == 0.0) 1.0 else belief.alpha / total
    }

    fun weights(): Map<String, Double> {
        val result = LinkedHashMap<String, Double>()
        beliefs.keys.forEach { result[it] = weight(it) }
        return result
    }

    fun snapshot(): Map<String, ReliabilityBelief> = LinkedHashMap(beliefs)

    fun load(snapshot: Map<String, ReliabilityBelief>) {
        snapshot.forEach { (source, belief) -> beliefs[source] = belief }
    }
}

private fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")
