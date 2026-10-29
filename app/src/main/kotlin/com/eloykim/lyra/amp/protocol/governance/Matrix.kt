package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Tier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The scenario x interaction-mode matrix (spec sections 9.1-9.8), a faithful port
 * of `src/governance/matrix.ts`.
 *
 * This file is the single source of truth for the five canonical scenarios, their
 * default mode weights, and the four derived tables the rest of the governance
 * layer reads: evidence floor, ignored-item revisit cadence, budget elasticity and
 * escalation sensitivity. It also owns the perspective activation table and the
 * adapters to the legacy weighted scenario and interaction modules, so no caller
 * has to duplicate a table.
 */

/**
 * The legacy eight-kind scenario vocabulary (`src/scenario/scenario.ts`).
 *
 * Three of these kinds have no canonical peer and collapse onto one through
 * [scenarioOfLegacy]; the declaration order is the reference's scan order for
 * [canonicalScenarioOf].
 */
enum class LegacyScenarioKind(val wire: String) {
    CHAT("chat"),
    WRITING("writing"),
    OFFICE("office"),
    COLLABORATION("collaboration"),
    INVESTIGATION("investigation"),
    RESEARCH("research"),
    ENGINEERING("engineering"),
    ITERATION("iteration");

    companion object {
        val order: List<LegacyScenarioKind> = entries

        fun fromWire(value: String): LegacyScenarioKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 legacy scenario: $value")
    }
}

/** The legacy interaction vocabulary; `inspire` is the old name for `diverge`. */
enum class LegacyInteractionKind(val wire: String) {
    AFFECT("affect"),
    THINK("think"),
    INSPIRE("inspire"),
    CONVERGE("converge"),
    ORCHESTRATE("orchestrate");

    companion object {
        fun fromWire(value: String): LegacyInteractionKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 legacy interaction: $value")
    }
}

/**
 * Activation plan for one scenario (spec section 9.8).
 *
 * Field names keep the reference's `default` / `onDemand` spelling so a reader can
 * diff this table against the TypeScript row by row.
 */
data class ScenarioPerspectives(
    val default: List<PerspectiveId>,
    val onDemand: List<PerspectiveId>,
    val dormant: List<PerspectiveId>,
    val note: String? = null,
)

/**
 * How one mode bends a manifest (spec section 9.2).
 */
data class ModeAdjustment(
    val focusBias: FocusBias,
    val focusDelta: Int,
    val revisitMultiplier: Double,
    val elasticityBias: Int,
    val gateBias: Int,
    val note: String,
)

enum class FocusBias(val wire: String) {
    NARROW("narrow"),
    NEUTRAL("neutral"),
    BROAD("broad"),
}

/**
 * The resolved matrix row for one task.
 */
data class ScenarioModePlan(
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val dominant: ModeId,
    val secondary: ModeId,
    val active: List<ModeId>,
    val evidenceFloor: EvidenceFloor,
    val requiredTier: Tier,
    val revisitEvery: Int,
    val elasticity: Elasticity,
    val sensitivity: Sensitivity,
    val gateStrictness: GateStrictness,
    val focusLimit: Int,
    val rationale: String,
)

/** One active mode plus the adjustment it asks for; used by the manifest deriver. */
data class ModeAdjustmentEntry(
    val mode: ModeId,
    val weight: Double,
    val adjustment: ModeAdjustment,
)

/**
 * Default weights, copied exactly from spec section 9.1. Each row sums to 1.
 */
val DefaultModeWeights: Map<ScenarioId, ModeWeights> = mapOf(
    ScenarioId.CHAT to ModeWeights(affect = 0.5, think = 0.2, diverge = 0.2, converge = 0.1),
    ScenarioId.WRITING to ModeWeights(affect = 0.1, think = 0.2, diverge = 0.5, converge = 0.2),
    ScenarioId.OFFICE to ModeWeights(affect = 0.1, think = 0.3, diverge = 0.1, converge = 0.5),
    ScenarioId.RESEARCH to ModeWeights(affect = 0.05, think = 0.4, diverge = 0.4, converge = 0.15),
    ScenarioId.ENGINEERING to ModeWeights(affect = 0.05, think = 0.35, diverge = 0.1, converge = 0.5),
)

/**
 * Evidence floors per scenario and mode (spec section 9.4), translated from the
 * description's T1/T2/T3 wording into the governance F1/F2/F3 order.
 */
val EvidenceFloorTable: Map<ScenarioId, Map<ModeId, EvidenceFloor>> = mapOf(
    ScenarioId.CHAT to modeRow(EvidenceFloor.F3, EvidenceFloor.F3, EvidenceFloor.F3, EvidenceFloor.F3),
    ScenarioId.WRITING to modeRow(EvidenceFloor.F3, EvidenceFloor.F2, EvidenceFloor.F3, EvidenceFloor.F2),
    ScenarioId.OFFICE to modeRow(EvidenceFloor.F2, EvidenceFloor.F2, EvidenceFloor.F2, EvidenceFloor.F2),
    ScenarioId.RESEARCH to modeRow(EvidenceFloor.F2, EvidenceFloor.F1, EvidenceFloor.F2, EvidenceFloor.F2),
    ScenarioId.ENGINEERING to modeRow(EvidenceFloor.F2, EvidenceFloor.F1, EvidenceFloor.F2, EvidenceFloor.F1),
)

/**
 * Frame cadence for re-evaluating ignored items (spec section 9.5).
 */
val RevisitIntervalTable: Map<ScenarioId, Map<ModeId, Int>> = mapOf(
    ScenarioId.CHAT to modeRow(10, 5, 8, 10),
    ScenarioId.WRITING to modeRow(8, 5, 2, 4),
    ScenarioId.OFFICE to modeRow(8, 4, 6, 4),
    ScenarioId.RESEARCH to modeRow(10, 3, 2, 4),
    ScenarioId.ENGINEERING to modeRow(10, 3, 4, 2),
)

/**
 * Budget elasticity per scenario and mode (spec section 9.6).
 */
val ElasticityTable: Map<ScenarioId, Map<ModeId, Elasticity>> = mapOf(
    ScenarioId.CHAT to modeRow(Elasticity.LOW, Elasticity.LOW, Elasticity.MEDIUM, Elasticity.LOW),
    ScenarioId.WRITING to modeRow(Elasticity.LOW, Elasticity.MEDIUM, Elasticity.HIGH, Elasticity.MEDIUM),
    ScenarioId.OFFICE to modeRow(Elasticity.LOW, Elasticity.MEDIUM, Elasticity.LOW, Elasticity.MEDIUM),
    ScenarioId.RESEARCH to modeRow(Elasticity.LOW, Elasticity.HIGH, Elasticity.HIGH, Elasticity.MEDIUM),
    ScenarioId.ENGINEERING to modeRow(Elasticity.LOW, Elasticity.HIGH, Elasticity.MEDIUM, Elasticity.HIGH),
)

/**
 * Escalation-trigger sensitivity (spec section 9.7).
 */
val SensitivityTable: Map<ScenarioId, Map<ModeId, Sensitivity>> = mapOf(
    ScenarioId.CHAT to modeRow(Sensitivity.VERY_LOW, Sensitivity.LOW, Sensitivity.LOW, Sensitivity.LOW),
    ScenarioId.WRITING to modeRow(Sensitivity.LOW, Sensitivity.MEDIUM, Sensitivity.LOW, Sensitivity.MEDIUM),
    ScenarioId.OFFICE to modeRow(Sensitivity.LOW, Sensitivity.MEDIUM, Sensitivity.MEDIUM, Sensitivity.MEDIUM),
    ScenarioId.RESEARCH to modeRow(Sensitivity.MEDIUM, Sensitivity.HIGH, Sensitivity.MEDIUM, Sensitivity.HIGH),
    ScenarioId.ENGINEERING to modeRow(Sensitivity.MEDIUM, Sensitivity.HIGH, Sensitivity.MEDIUM, Sensitivity.HIGH),
)

/**
 * Which perspectives a scenario activates by default.
 */
val PerspectiveActivationTable: Map<ScenarioId, ScenarioPerspectives> = mapOf(
    ScenarioId.CHAT to ScenarioPerspectives(
        default = listOf(PerspectiveId.CUSTOMER_SERVICE),
        onDemand = listOf(PerspectiveId.MARKETING),
        dormant = listOf(
            PerspectiveId.STRATEGY,
            PerspectiveId.TECHNOLOGY,
            PerspectiveId.SUPERVISORY,
            PerspectiveId.BOARD,
        ),
        note = "Market research is rare in chat and stays dormant most of the time.",
    ),
    ScenarioId.WRITING to ScenarioPerspectives(
        default = listOf(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.WRITING),
        onDemand = listOf(PerspectiveId.MARKETING, PerspectiveId.STRATEGY),
        dormant = listOf(PerspectiveId.TECHNOLOGY, PerspectiveId.SUPERVISORY),
    ),
    ScenarioId.OFFICE to ScenarioPerspectives(
        default = listOf(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.OPERATIONS),
        onDemand = listOf(PerspectiveId.FINANCE, PerspectiveId.LEGAL),
        dormant = listOf(PerspectiveId.STRATEGY, PerspectiveId.TECHNOLOGY, PerspectiveId.MARKETING),
    ),
    ScenarioId.RESEARCH to ScenarioPerspectives(
        default = listOf(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.STRATEGY, PerspectiveId.MARKETING),
        onDemand = listOf(PerspectiveId.TECHNOLOGY, PerspectiveId.SUPERVISORY),
        dormant = listOf(PerspectiveId.OPERATIONS),
    ),
    ScenarioId.ENGINEERING to ScenarioPerspectives(
        default = listOf(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.TECHNOLOGY, PerspectiveId.OPERATIONS),
        onDemand = listOf(PerspectiveId.LEGAL, PerspectiveId.SUPERVISORY, PerspectiveId.STRATEGY),
        dormant = listOf(PerspectiveId.MARKETING),
        note = "Marketing is dormant but can be pulled in for user-facing impact review.",
    ),
)

/**
 * Mode adjustments (spec section 9.2).
 */
val ModeAdjustments: Map<ModeId, ModeAdjustment> = mapOf(
    ModeId.AFFECT to ModeAdjustment(
        focusBias = FocusBias.NARROW,
        focusDelta = 0,
        revisitMultiplier = 1.0,
        elasticityBias = 0,
        gateBias = -1,
        note = "Focus on emotional signals and relationship maintenance; loosen the gate, keep behaviour conservative.",
    ),
    ModeId.THINK to ModeAdjustment(
        focusBias = FocusBias.NEUTRAL,
        focusDelta = 0,
        revisitMultiplier = 1.0,
        elasticityBias = 0,
        gateBias = 1,
        note = "Focus on the evidence chain and causal structure; raise the check frequency.",
    ),
    ModeId.DIVERGE to ModeAdjustment(
        focusBias = FocusBias.BROAD,
        focusDelta = 2,
        revisitMultiplier = 0.5,
        elasticityBias = 1,
        gateBias = 0,
        note = "Widen the focus set, revisit ignored items twice as often, allow more budget slack.",
    ),
    ModeId.CONVERGE to ModeAdjustment(
        focusBias = FocusBias.NARROW,
        focusDelta = -2,
        revisitMultiplier = 1.5,
        elasticityBias = -1,
        gateBias = 0,
        note = "Shrink the focus to the candidate set, tighten the budget, make escalation triggers more sensitive.",
    ),
)

/** Modes receiving at least 15% of attention. */
const val ActiveModeThreshold: Double = 0.15

private val ElasticityRank: Map<Elasticity, Int> = mapOf(
    Elasticity.LOW to 0,
    Elasticity.MEDIUM to 1,
    Elasticity.HIGH to 2,
)

/** Elasticity ordering shared with the manifest deriver. */
internal fun elasticityRank(elasticity: Elasticity): Int = ElasticityRank.getValue(elasticity)

private val GateStrictnessByScenario: Map<ScenarioId, GateStrictness> = mapOf(
    ScenarioId.CHAT to GateStrictness.LOOSE,
    ScenarioId.WRITING to GateStrictness.MEDIUM,
    ScenarioId.OFFICE to GateStrictness.MEDIUM,
    ScenarioId.RESEARCH to GateStrictness.MEDIUM_STRICT,
    ScenarioId.ENGINEERING to GateStrictness.STRICT,
)

/**
 * Validate a scenario id at runtime; unknown values fail closed.
 */
fun requireScenario(value: String): ScenarioId =
    ScenarioId.entries.firstOrNull { it.wire == value }
        ?: throw GovernanceError(GovernanceCode.INVALID_SCENARIO, "$value is not a canonical scenario")

/**
 * Normalize a partial weight vector onto the mode simplex.
 *
 * Negative, non-finite and all-zero vectors are rejected: a manifest without a
 * mode is not a valid manifest.
 */
fun normalizeModeWeights(input: Map<ModeId, Double>): ModeWeights {
    val raw = DoubleArray(ModeId.entries.size)
    var total = 0.0
    ModeId.entries.forEachIndexed { index, mode ->
        val value = input[mode] ?: 0.0
        if (!value.isFinite() || value < 0.0) {
            throw GovernanceError(
                GovernanceCode.INVALID_MODE_WEIGHTS,
                "${mode.wire} must be a finite non-negative number",
            )
        }
        raw[index] = value
        total += value
    }
    if (total <= 0.0) {
        throw GovernanceError(GovernanceCode.INVALID_MODE_WEIGHTS, "at least one mode weight must be positive")
    }
    return ModeWeights(
        affect = raw[0] / total,
        think = raw[1] / total,
        diverge = raw[2] / total,
        converge = raw[3] / total,
    )
}

/**
 * Modes sorted by weight, descending; ties keep the canonical order.
 */
fun rankModes(weights: ModeWeights): List<ModeId> = ModeId.entries
    .sortedWith(compareByDescending<ModeId> { weights[it] }.thenBy { it.ordinal })

fun dominantMode(weights: ModeWeights): ModeId = rankModes(weights).first()

/**
 * Modes receiving at least [threshold] of attention. One mode always qualifies
 * because the vector sums to 1.
 */
fun activeModes(weights: ModeWeights, threshold: Double = ActiveModeThreshold): List<ModeId> {
    val active = ModeId.entries.filter { weights[it] >= threshold }
    return if (active.isEmpty()) listOf(dominantMode(weights)) else active
}

/**
 * Strictest floor across the active modes: a mixed task inherits the hardest
 * evidence demand, never the easiest.
 */
fun evidenceFloorFor(scenario: ScenarioId, weights: ModeWeights): EvidenceFloor {
    val table = EvidenceFloorTable.getValue(scenario)
    var strictest = EvidenceFloor.F3
    for (mode in activeModes(weights)) {
        val floor = table.getValue(mode)
        if (GovernanceLimits.floorRank.getValue(floor) < GovernanceLimits.floorRank.getValue(strictest)) {
            strictest = floor
        }
    }
    return strictest
}

/**
 * Revisit cadence for ignored items: the most frequent cadence among active modes,
 * so a divergence-heavy mix does not let ignored items go stale.
 */
fun revisitIntervalFor(scenario: ScenarioId, weights: ModeWeights): Int {
    val table = RevisitIntervalTable.getValue(scenario)
    var interval = Double.POSITIVE_INFINITY
    for (mode in activeModes(weights)) {
        interval = min(interval, table.getValue(mode).toDouble())
    }
    return max(2, if (interval.isFinite()) interval.toInt() else 10)
}

/**
 * Budget elasticity: the most restrictive active mode wins, unless a mode
 * explicitly raises it and no other mode forbids it. Safety-first by design.
 */
fun elasticityFor(scenario: ScenarioId, weights: ModeWeights): Elasticity {
    val table = ElasticityTable.getValue(scenario)
    var elasticity = Elasticity.HIGH
    for (mode in activeModes(weights)) {
        val candidate = table.getValue(mode)
        if (ElasticityRank.getValue(candidate) < ElasticityRank.getValue(elasticity)) {
            elasticity = candidate
        }
    }
    return elasticity
}

/**
 * Escalation sensitivity: the most sensitive active mode wins.
 */
fun sensitivityFor(scenario: ScenarioId, weights: ModeWeights): Sensitivity {
    val table = SensitivityTable.getValue(scenario)
    var sensitivity = Sensitivity.VERY_LOW
    for (mode in activeModes(weights)) {
        val candidate = table.getValue(mode)
        if (Sensitivity.rank.getValue(candidate) > Sensitivity.rank.getValue(sensitivity)) {
            sensitivity = candidate
        }
    }
    return sensitivity
}

/**
 * Focus-set size: divergence widens it, convergence shrinks it (spec 9.2).
 */
fun focusLimitFor(weights: ModeWeights): Int {
    val raw = 3 + Math.round(4 * weights.diverge).toInt() - Math.round(2 * weights.converge).toInt()
    return max(1, min(9, raw))
}

fun gateStrictnessFor(scenario: ScenarioId): GateStrictness = GateStrictnessByScenario.getValue(scenario)

/**
 * Resolve a scenario and optional mode override into the full matrix row.
 */
fun resolveScenarioModes(scenario: ScenarioId, override: Map<ModeId, Double>? = null): ScenarioModePlan {
    val checked = requireScenario(scenario.wire)
    val modes = if (override == null) DefaultModeWeights.getValue(checked) else normalizeModeWeights(override)
    val ranked = rankModes(modes)
    val floor = evidenceFloorFor(checked, modes)
    val revisitEvery = revisitIntervalFor(checked, modes)
    val elasticity = elasticityFor(checked, modes)
    val requiredTier = GovernanceLimits.floorTier.getValue(floor)
    return ScenarioModePlan(
        scenario = checked,
        modes = modes,
        dominant = ranked[0],
        secondary = ranked[1],
        active = activeModes(modes),
        evidenceFloor = floor,
        requiredTier = requiredTier,
        revisitEvery = revisitEvery,
        elasticity = elasticity,
        sensitivity = sensitivityFor(checked, modes),
        gateStrictness = gateStrictnessFor(checked),
        focusLimit = focusLimitFor(modes),
        rationale = buildString {
            append("${checked.wire} -> ")
            append(
                ranked.take(2).joinToString(" + ") { mode -> "${mode.wire} ${fixed2(modes[mode])}" },
            )
            append("; floor ${floor.wire} (${requiredTier.wire}), ")
            append("revisit every $revisitEvery frames, elasticity ${elasticity.wire}")
        },
    )
}

/**
 * Active-mode adjustments, used by the manifest deriver to explain itself.
 */
fun modeAdjustments(weights: ModeWeights): List<ModeAdjustmentEntry> = activeModes(weights).map { mode ->
    ModeAdjustmentEntry(mode = mode, weight = weights[mode], adjustment = ModeAdjustments.getValue(mode))
}

/**
 * Largest single-dimension drift between two weight vectors.
 */
fun modeDrift(from: ModeWeights, to: ModeWeights): Double {
    var drift = 0.0
    for (mode in ModeId.entries) {
        drift = max(drift, abs(from[mode] - to[mode]))
    }
    return drift
}

/**
 * Mode changes above 0.15 need the general manager's approval (spec 9.3).
 */
fun driftRequiresApproval(from: ModeWeights, to: ModeWeights): Boolean = modeDrift(from, to) > 0.15 + 1e-9

/**
 * A scenario change always re-derives the manifest; it is never a patch.
 */
fun scenarioChangeRequiresRederive(from: ScenarioId, to: ScenarioId): Boolean = from != to

/**
 * Bridge: the five canonical scenarios all exist as legacy scenario kinds.
 */
fun legacyScenarioMix(scenario: ScenarioId): Map<LegacyScenarioKind, Double> =
    mapOf(LegacyScenarioKind.fromWire(scenario.wire) to 1.0)

/**
 * Bridge: map a legacy eight-kind mix onto its dominant canonical scenario.
 */
fun canonicalScenarioOf(mix: Map<LegacyScenarioKind, Double>): ScenarioId {
    var best = LegacyScenarioKind.CHAT
    var bestWeight = 0.0
    for (kind in LegacyScenarioKind.order) {
        val weight = mix[kind] ?: 0.0
        if (weight > bestWeight) {
            best = kind
            bestWeight = weight
        }
    }
    if (bestWeight <= 0.0) {
        throw GovernanceError(GovernanceCode.INVALID_SCENARIO, "a scenario mix must contain at least one positive weight")
    }
    return scenarioOfLegacy(best)
}

/**
 * Bridge: collapse the three scenario kinds that have no canonical peer.
 */
fun scenarioOfLegacy(kind: LegacyScenarioKind): ScenarioId = when (kind) {
    LegacyScenarioKind.COLLABORATION -> ScenarioId.OFFICE
    LegacyScenarioKind.INVESTIGATION -> ScenarioId.RESEARCH
    LegacyScenarioKind.ITERATION -> ScenarioId.ENGINEERING
    else -> ScenarioId.fromWire(kind.wire)
}

/**
 * Bridge: the legacy interaction vocabulary uses `inspire` where governance says
 * `diverge`.
 */
fun legacyInteractionMix(weights: ModeWeights): Map<LegacyInteractionKind, Double> = mapOf(
    LegacyInteractionKind.AFFECT to weights.affect,
    LegacyInteractionKind.THINK to weights.think,
    LegacyInteractionKind.INSPIRE to weights.diverge,
    LegacyInteractionKind.CONVERGE to weights.converge,
)

/**
 * Bridge: derive governance modes from a legacy interaction mix. Missing
 * `orchestrate` weight is folded into `converge`, the planning mode.
 */
fun modesFromLegacyInteraction(mix: Map<LegacyInteractionKind, Double>): ModeWeights {
    val orchestrate = mix[LegacyInteractionKind.ORCHESTRATE] ?: 0.0
    return normalizeModeWeights(
        mapOf(
            ModeId.AFFECT to (mix[LegacyInteractionKind.AFFECT] ?: 0.0),
            ModeId.THINK to (mix[LegacyInteractionKind.THINK] ?: 0.0),
            ModeId.DIVERGE to (mix[LegacyInteractionKind.INSPIRE] ?: 0.0),
            ModeId.CONVERGE to ((mix[LegacyInteractionKind.CONVERGE] ?: 0.0) + orchestrate),
        ),
    )
}

/** The four weights as a `ModeId` map, for callers that work with partial vectors. */
internal fun ModeWeights.asModeMap(): Map<ModeId, Double> = mapOf(
    ModeId.AFFECT to affect,
    ModeId.THINK to think,
    ModeId.DIVERGE to diverge,
    ModeId.CONVERGE to converge,
)

/** JavaScript `Number#toFixed(2)` spelling used by the reference's rationale text. */
internal fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun <V> modeRow(affect: V, think: V, diverge: V, converge: V): Map<ModeId, V> = mapOf(
    ModeId.AFFECT to affect,
    ModeId.THINK to think,
    ModeId.DIVERGE to diverge,
    ModeId.CONVERGE to converge,
)
