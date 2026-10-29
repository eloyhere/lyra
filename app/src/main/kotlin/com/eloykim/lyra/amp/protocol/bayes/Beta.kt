package com.eloykim.lyra.amp.protocol.bayes

/**
 * Minimal Beta belief primitives used by governance control.
 *
 * The full reference `src/bayes/engine.ts` is ported separately; these are the
 * stable numeric kernels the governance layer depends on.
 */
data class BetaBelief(
    val alpha: Double,
    val beta: Double,
) {

    init {
        require(alpha.isFinite() && alpha > 0.0) { "beta.alpha 必须是有限正数" }
        require(beta.isFinite() && beta > 0.0) { "beta.beta 必须是有限正数" }
    }

    val mean: Double
        get() = alpha / (alpha + beta)

    val variance: Double
        get() = (alpha * beta) / ((alpha + beta) * (alpha + beta) * (alpha + beta + 1.0))
}

fun betaMean(belief: BetaBelief): Double = belief.mean

fun betaVariance(belief: BetaBelief): Double = belief.variance
