package com.eloykim.lyra.amp.protocol.bayes

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.asObject
import kotlin.math.max

/**
 * Scope 级 Beta 先验。
 *
 * `priorsFor(scope)` = 全局先验 + 该 scope 的本地计数。这是一个简单的层级模型：
 * 任务/场景级反馈可以推动后验而不丢弃全局先验，未见过的 scope 回落到全局。
 * 对应参考实现 `src/bayes/scopedPriors.ts`。
 */
data class BetaCounts(
    val alpha: Double,
    val beta: Double,
) {

    init {
        require(alpha.isFinite() && alpha >= 0.0) { "beta counts alpha 必须是非负有限数" }
        require(beta.isFinite() && beta >= 0.0) { "beta counts beta 必须是非负有限数" }
    }

    fun plus(other: BetaCounts): BetaCounts = BetaCounts(alpha + other.alpha, beta + other.beta)

    fun scaled(factor: Double): BetaCounts = BetaCounts(alpha * factor, beta * factor)

    fun toJson(): JsonValue.Obj = jsonObj(
        "alpha" to jsonNumber(alpha),
        "beta" to jsonNumber(beta),
    )

    companion object {
        val ZERO: BetaCounts = BetaCounts(0.0, 0.0)

        fun fromJson(value: JsonValue): BetaCounts {
            val obj = value.asObject("beta-counts")
            return BetaCounts(
                alpha = obj.requireDouble("alpha"),
                beta = obj.requireDouble("beta"),
            )
        }
    }
}

data class ScopedPriorsOptions(
    val global: Map<Dimension, BetaBelief>? = null,
    /**
     * 0 = 不向全局池化；1 = 每条本地观察也同量更新全局。
     */
    val globalWeight: Double = 0.0,
)

/**
 * 层级先验快照。
 *
 * 字段形状与参考实现的 `snapshot()` / `load()` 对象一致：`global` 与 `scopes`
 * 都以维度名称为键，值为 `{alpha, beta}`。两个字段在 `load` 中都可缺省。
 */
data class ScopedPriorsSnapshot(
    val global: Map<Dimension, BetaCounts>? = null,
    val scopes: Map<String, Map<Dimension, BetaCounts>>? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "global" to global?.let { countsToJson(it) },
        "scopes" to scopes?.let { scopeMap ->
            JsonValue.Obj(
                LinkedHashMap<String, JsonValue>().apply {
                    scopeMap.forEach { (scope, counts) -> put(scope, countsToJson(counts)) }
                },
            )
        },
    )

    companion object {
        fun fromJson(value: JsonValue): ScopedPriorsSnapshot {
            val obj = value.asObject("scoped-priors-snapshot")
            val global = obj.obj("global")?.let { countsFromJson(it) }
            val scopes = obj.obj("scopes")?.fields?.let { fields ->
                val parsed = LinkedHashMap<String, Map<Dimension, BetaCounts>>()
                fields.forEach { (scope, node) ->
                    parsed[scope] = countsFromJson(node.asObject("scopes.$scope"))
                }
                parsed
            }
            return ScopedPriorsSnapshot(global = global, scopes = scopes)
        }

        private fun countsToJson(counts: Map<Dimension, BetaCounts>): JsonValue.Obj =
            JsonValue.Obj(
                LinkedHashMap<String, JsonValue>().apply {
                    counts.forEach { (dimension, value) -> put(dimension.wire, value.toJson()) }
                },
            )

        private fun countsFromJson(value: JsonValue.Obj): Map<Dimension, BetaCounts> {
            val result = LinkedHashMap<Dimension, BetaCounts>()
            value.fields.forEach { (key, node) ->
                result[Dimension.fromWire(key)] = BetaCounts.fromJson(node)
            }
            return result
        }
    }
}

class ScopedPriors(options: ScopedPriorsOptions = ScopedPriorsOptions()) {

    private val global: MutableMap<Dimension, BetaCounts>
    private val scopes = LinkedHashMap<String, MutableMap<Dimension, BetaCounts>>()
    private val globalWeight: Double = clamp01(options.globalWeight)

    init {
        global = LinkedHashMap()
        DefaultPriors.forEach { (dimension, belief) ->
            global[dimension] = BetaCounts(belief.alpha, belief.beta)
        }
        options.global?.forEach { (dimension, belief) ->
            global[dimension] = BetaCounts(belief.alpha, belief.beta)
        }
    }

    /**
     * 记录一个 scope 的证据。
     *
     * 本地计数按 `max(0, reliability)` 加权；`globalWeight > 0` 时同一份增量也会
     * 按该比例并入全局先验。
     */
    fun observe(scope: String, evidence: List<Evidence>) {
        val local = scopes.getOrPut(scope) { emptyCounts() }
        evidence.forEach { item ->
            val amount = max(0.0, item.reliability)
            val increment = BetaCounts(
                alpha = max(0.0, item.positive) * amount,
                beta = max(0.0, item.negative) * amount,
            )
            local[item.dimension] = (local[item.dimension] ?: BetaCounts.ZERO).plus(increment)
            if (globalWeight > 0.0) {
                global[item.dimension] = (global[item.dimension] ?: BetaCounts.ZERO).plus(increment.scaled(globalWeight))
            }
        }
    }

    fun priorsFor(scope: String): Map<Dimension, BetaBelief> {
        val local = scopes[scope]
        val result = LinkedHashMap<Dimension, BetaBelief>()
        Dimension.entries.forEach { dimension ->
            val shared = global.getValue(dimension)
            val own = local?.get(dimension) ?: BetaCounts.ZERO
            result[dimension] = BetaBelief(shared.alpha + own.alpha, shared.beta + own.beta)
        }
        return result
    }

    fun engineFor(scope: String): BayesianEngine = BayesianEngine(priorsFor(scope))

    fun scopesKnown(): List<String> = scopes.keys.toList()

    fun snapshot(): ScopedPriorsSnapshot = ScopedPriorsSnapshot(
        global = LinkedHashMap(global),
        scopes = scopes.mapValues { LinkedHashMap(it.value) },
    )

    fun load(snapshot: ScopedPriorsSnapshot) {
        snapshot.global?.forEach { (dimension, counts) -> global[dimension] = counts }
        scopes.clear()
        snapshot.scopes?.forEach { (scope, counts) -> scopes[scope] = LinkedHashMap(counts) }
    }

    private fun emptyCounts(): MutableMap<Dimension, BetaCounts> {
        val result = LinkedHashMap<Dimension, BetaCounts>()
        Dimension.entries.forEach { result[it] = BetaCounts.ZERO }
        return result
    }
}

private fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")
