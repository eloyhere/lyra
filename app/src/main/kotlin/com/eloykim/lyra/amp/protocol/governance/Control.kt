package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.bayes.BetaBelief
import com.eloykim.lyra.amp.protocol.bayes.betaMean
import com.eloykim.lyra.amp.protocol.bayes.betaVariance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Bayesian control layer (spec section 6), a faithful port of
 * `src/governance/control.ts`.
 *
 * The posterior does not generate text. It routes attention: it changes mode
 * weights, shrinks or expands the focus set, assigns budget to the observation with
 * the highest information value, and decides when to stop or escalate.
 *
 * Posterior uncertainty raises divergence; confidence raises convergence; evidence
 * conflict raises thinking and may be forced by legal. Probabilities are clamped to
 * [0.001, 0.999] so no single observation can claim certainty.
 */

data class ControlObservation(
    val scenario: ScenarioId,
    val modes: ModeWeights,
    /** The posterior over "this turn is adequate". Alpha/beta counts. */
    val posterior: BetaBelief,
    /** Set when two evidence items disagree materially. */
    val conflict: Boolean = false,
    /** Legal can force the thinking mode on regardless of the posterior. */
    val legalOverride: Boolean = false,
    /** Estimated value of the next observation, in [0, 1]. */
    val voi: Double? = null,
    val frame: Int? = null,
    val openQuestions: Int? = null,
)

data class ControlBudget(
    val nextObservationShare: Double,
    val reason: String,
)

enum class ControlAction(val wire: String) {
    EXPAND("expand"),
    HOLD("hold"),
    SHRINK("shrink"),
}

data class ControlDecision(
    val modes: ModeWeights,
    val action: ControlAction,
    val stop: Boolean,
    val escalate: Boolean,
    val escalateTo: PerspectiveId? = null,
    val budget: ControlBudget,
    val approvalRequired: Boolean,
    val mean: Double,
    val uncertainty: Double,
    val rationale: List<String>,
)

data class ProbabilityRange(
    val min: Double,
    val max: Double,
)

val ProbabilityClamp: ProbabilityRange = ProbabilityRange(min = 0.001, max = 0.999)

fun clampProbability(value: Double): Double =
    max(ProbabilityClamp.min, min(ProbabilityClamp.max, if (value.isFinite()) value else ProbabilityClamp.min))

/**
 * One control step. Pure: the caller applies the weights through the manifest update
 * path, where approvals are enforced.
 *
 * [BetaBelief] already rejects non-positive or non-finite counts when it is
 * constructed, so the explicit posterior guard below is unreachable for typed
 * callers; it keeps the reference's fail-closed `probability-invalid` code for
 * callers that build a belief from untyped input.
 */
fun decideControl(observation: ControlObservation): ControlDecision {
    val posterior = observation.posterior
    if (!posterior.alpha.isFinite() ||
        !posterior.beta.isFinite() ||
        posterior.alpha <= 0.0 ||
        posterior.beta <= 0.0
    ) {
        throw GovernanceError(
            GovernanceCode.PROBABILITY_INVALID,
            "the posterior alpha and beta must be finite and positive",
        )
    }
    val mean = clampProbability(betaMean(posterior))
    val variance = betaVariance(posterior)
    val uncertainty = min(1.0, max(0.0, sqrt(max(0.0, if (variance.isFinite()) variance else 0.0)) * 2))
    var affect = observation.modes.affect
    var think = observation.modes.think
    var diverge = observation.modes.diverge
    var converge = observation.modes.converge
    val rationale = mutableListOf("posterior mean ${fixed2(mean)}, uncertainty ${fixed2(uncertainty)}")

    if (uncertainty > 0.2) {
        val delta = 0.1 * min(1.0, uncertainty * 2.5)
        diverge += delta
        rationale += "uncertainty ${fixed2(uncertainty)} raises divergence by ${fixed2(delta)}"
    }
    if (mean > 0.8 && uncertainty < 0.2) {
        converge += 0.1
        rationale += "confidence ${fixed2(mean)} raises convergence by 0.10"
    }
    if (observation.conflict) {
        think += 0.15
        rationale += "evidence conflict raises thinking by 0.15; the posterior overrides the prior"
    }
    val forced = observation.legalOverride
    if (forced) {
        think += 0.2
        rationale += "legal forced the thinking mode on"
    }
    val modes = normalizeModeWeights(
        mapOf(
            ModeId.AFFECT to affect,
            ModeId.THINK to think,
            ModeId.DIVERGE to diverge,
            ModeId.CONVERGE to converge,
        ),
    )
    val approvalRequired = driftRequiresApproval(observation.modes, modes)
    if (approvalRequired) {
        rationale += "the adjustment exceeds 0.15 and needs the general manager's approval"
    }

    val action = when {
        uncertainty > 0.2 -> ControlAction.EXPAND
        mean > 0.8 && uncertainty < 0.2 -> ControlAction.SHRINK
        else -> ControlAction.HOLD
    }
    val voi = max(0.0, min(1.0, observation.voi ?: 0.0))
    val share = if (action == ControlAction.EXPAND) max(0.1, min(0.5, 0.1 + voi * 0.4)) else 0.05
    val stop = mean >= 0.9 && uncertainty < 0.1 && (observation.openQuestions ?: 0) == 0 && voi < 0.05
    if (stop) {
        rationale += "high confidence, no open question and low information value: stop"
    }
    val escalate = forced || uncertainty > 0.45 || (observation.conflict && mean < 0.6)
    val escalateTo: PerspectiveId? = when {
        !escalate -> null
        forced -> PerspectiveId.LEGAL
        uncertainty > 0.45 -> PerspectiveId.SUPERVISORY
        else -> PerspectiveId.GENERAL_MANAGER
    }
    if (escalateTo != null) {
        val why = when {
            forced -> "legal override"
            uncertainty > 0.45 -> "residual uncertainty"
            else -> "unresolved evidence conflict"
        }
        rationale += "escalate to ${escalateTo.wire}: $why"
    }
    return ControlDecision(
        modes = modes,
        action = action,
        stop = stop,
        escalate = escalate,
        escalateTo = escalateTo,
        budget = ControlBudget(
            nextObservationShare = share,
            reason = if (action == ControlAction.EXPAND) {
                "information value ${fixed2(voi)} keeps an exploratory share"
            } else {
                "confidence allows a narrow share"
            },
        ),
        approvalRequired = approvalRequired,
        mean = mean,
        uncertainty = uncertainty,
        rationale = rationale.toList(),
    )
}

/**
 * The mode with the largest positive change, for receipts and audit lines.
 */
fun changedMode(from: ModeWeights, to: ModeWeights): ModeId {
    var best = ModeId.entries[0]
    var delta = -1.0
    for (mode in ModeId.entries) {
        val change = to[mode] - from[mode]
        if (change > delta) {
            best = mode
            delta = change
        }
    }
    return best
}
