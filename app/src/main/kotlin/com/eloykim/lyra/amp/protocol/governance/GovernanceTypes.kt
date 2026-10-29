package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * Shared vocabulary for the attention-governance layer, mirroring
 * `src/governance/types.ts`.
 *
 * This file is the acyclic base: every other governance module may depend on it.
 */

enum class ScenarioId(val wire: String) {
    CHAT("chat"), WRITING("writing"), OFFICE("office"), RESEARCH("research"), ENGINEERING("engineering");

    companion object {
        val ids: List<ScenarioId> = entries
        fun fromWire(value: String): ScenarioId =
            entries.firstOrNull { it.wire == value } ?: throw GovernanceError(GovernanceCode.INVALID_SCENARIO, value)
    }
}

enum class ModeId(val wire: String) {
    AFFECT("affect"), THINK("think"), DIVERGE("diverge"), CONVERGE("converge");

    companion object {
        val ids: List<ModeId> = entries
        fun fromWire(value: String): ModeId =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 mode: $value")
    }
}

/**
 * A normalized weight vector. The reference uses a `Record<ModeId, number>`;
 * this typed value keeps the same four keys and makes normalization explicit.
 */
data class ModeWeights(
    val affect: Double,
    val think: Double,
    val diverge: Double,
    val converge: Double,
) {

    init {
        listOf(affect, think, diverge, converge).forEach {
            require(it.isFinite() && it >= 0.0) { "mode weight 必须是非负有限数" }
        }
    }

    operator fun get(mode: ModeId): Double = when (mode) {
        ModeId.AFFECT -> affect
        ModeId.THINK -> think
        ModeId.DIVERGE -> diverge
        ModeId.CONVERGE -> converge
    }

    fun with(mode: ModeId, value: Double): ModeWeights = when (mode) {
        ModeId.AFFECT -> copy(affect = value)
        ModeId.THINK -> copy(think = value)
        ModeId.DIVERGE -> copy(diverge = value)
        ModeId.CONVERGE -> copy(converge = value)
    }

    fun plus(mode: ModeId, delta: Double): ModeWeights = with(mode, get(mode) + delta)

    val total: Double
        get() = affect + think + diverge + converge

    fun normalized(): ModeWeights {
        val sum = total
        if (sum <= 0.0 || !sum.isFinite()) {
            return ModeWeights(0.25, 0.25, 0.25, 0.25)
        }
        return ModeWeights(affect / sum, think / sum, diverge / sum, converge / sum)
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "affect" to jsonNumber(affect),
        "think" to jsonNumber(think),
        "diverge" to jsonNumber(diverge),
        "converge" to jsonNumber(converge),
    )

    companion object {
        val Balanced: ModeWeights = ModeWeights(0.25, 0.25, 0.25, 0.25)

        fun fromJson(value: JsonValue): ModeWeights {
            val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("mode weights 必须是对象")
            return ModeWeights(
                affect = obj.double("affect") ?: 0.0,
                think = obj.double("think") ?: 0.0,
                diverge = obj.double("diverge") ?: 0.0,
                converge = obj.double("converge") ?: 0.0,
            )
        }
    }
}

enum class PerspectiveId(val wire: String) {
    SHAREHOLDERS("shareholders"), BOARD("board"), SUPERVISORY("supervisory"),
    GENERAL_MANAGER("general-manager"), CUSTOMER_SERVICE("customer-service"), WRITING("writing"),
    STRATEGY("strategy"), HUMAN_RESOURCES("human-resources"), MARKETING("marketing"),
    TECHNOLOGY("technology"), LEGAL("legal"), FINANCE("finance"), ADMINISTRATION("administration"),
    OPERATIONS("operations");

    companion object {
        val ids: List<PerspectiveId> = entries
        fun fromWire(value: String): PerspectiveId =
            entries.firstOrNull { it.wire == value }
                ?: throw GovernanceError(GovernanceCode.UNKNOWN_PERSPECTIVE, value)
    }
}

enum class PerspectiveLayer(val wire: String) {
    STRATEGIC("strategic"), MANAGEMENT("management"), EXECUTION("execution"), RULE("rule");
}

enum class Activation(val wire: String) {
    DEFAULT("default"), ON_DEMAND("on-demand"), DORMANT("dormant");
}

enum class Elasticity(val wire: String) {
    LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun fromWire(value: String): Elasticity =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 elasticity: $value")
    }
}

enum class Sensitivity(val wire: String) {
    VERY_LOW("very-low"), LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        val rank: Map<Sensitivity, Int> = mapOf(VERY_LOW to 0, LOW to 1, MEDIUM to 2, HIGH to 3)
        fun fromWire(value: String): Sensitivity =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 sensitivity: $value")
    }
}

enum class GateStrictness(val wire: String) {
    LOOSE("loose"), MEDIUM("medium"), MEDIUM_STRICT("medium-strict"), STRICT("strict");
}

enum class LedgerScope(val wire: String) {
    GLOBAL("global"), PERSPECTIVE("perspective"), AGENT("agent"), RESERVE("reserve");
}

enum class EvidenceFloor(val wire: String) {
    F1("F1"), F2("F2"), F3("F3");

    companion object {
        val ids: List<EvidenceFloor> = entries
        fun fromWire(value: String): EvidenceFloor =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 evidence floor: $value")
    }
}

data class ElasticityLimit(
    val autoRatio: Double,
    val hardRatio: Double,
    val requiresApproval: Boolean,
    val postAudit: Boolean,
)

object GovernanceLimits {

    val elasticity: Map<Elasticity, ElasticityLimit> = mapOf(
        Elasticity.LOW to ElasticityLimit(0.2, 0.2, false, false),
        Elasticity.MEDIUM to ElasticityLimit(0.0, 0.35, true, true),
        Elasticity.HIGH to ElasticityLimit(0.5, 0.5, false, true),
    )

    val floorTier: Map<EvidenceFloor, Tier> = mapOf(
        EvidenceFloor.F1 to Tier.T3,
        EvidenceFloor.F2 to Tier.T2,
        EvidenceFloor.F3 to Tier.T1,
    )

    val tierRank: Map<Tier, Int> = mapOf(Tier.T0 to 0, Tier.T1 to 1, Tier.T2 to 2, Tier.T3 to 3, Tier.T4 to 4)

    val floorRank: Map<EvidenceFloor, Int> = mapOf(EvidenceFloor.F1 to 0, EvidenceFloor.F2 to 1, EvidenceFloor.F3 to 2)
}

interface BehaviorConstraints {
    val allowed: List<String>
    val forbidden: List<String>
    val obligations: List<String>
    val escalation: List<String>
    val evidence: EvidenceFloor
}

data class BehaviorConstraintSet(
    override val allowed: List<String>,
    override val forbidden: List<String>,
    override val obligations: List<String>,
    override val escalation: List<String>,
    override val evidence: EvidenceFloor,
) : BehaviorConstraints

enum class SignoffRole(val wire: String) {
    ISSUER("issuer"), LEGAL("legal"), FINANCE("finance"), BOARD("board"), HUMAN("human");

    companion object {
        fun fromWire(value: String): SignoffRole =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 signoff role: $value")
    }
}

data class Signoff(
    val role: SignoffRole,
    val by: PerspectiveId,
    val at: Timestamp,
    val digest: Digest,
)

data class SignerInput(
    val role: SignoffRole,
    val by: PerspectiveId,
    val at: Timestamp,
)

data class ManifestRef(val id: Id, val version: Int)

data class EscalationRef(val to: PerspectiveId, val reason: String)

data class VerificationVerdict(
    val ok: Boolean,
    val code: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun success(): VerificationVerdict = VerificationVerdict(true)
        fun failure(code: String, detail: String? = null): VerificationVerdict = VerificationVerdict(false, code, detail)
    }
}

data class CredentialBudget(val quota: Budget, val elasticity: Elasticity)

enum class ApprovalSubject(val wire: String) {
    BUDGET_INITIAL("budget.initial"),
    BUDGET_ALLOCATION("budget.allocation"),
    BUDGET_FREEZE("budget.freeze"),
    BUDGET_ELASTICITY("budget.elasticity"),
    BUDGET_TOP_UP("budget.top-up"),
    BUDGET_SETTLEMENT("budget.settlement"),
    BUDGET_RESERVE("budget.reserve"),
    BUDGET_MAJOR_TOP_UP("budget.major-top-up"),
    CREDENTIAL_ISSUE("credential.issue"),
    CREDENTIAL_COMPLIANCE("credential.compliance"),
    MANIFEST_DERIVE("manifest.derive"),
    MANIFEST_CONSTRAINTS("manifest.constraints"),
    MANIFEST_EVIDENCE_FLOOR("manifest.evidence-floor"),
    MANIFEST_FOCUS("manifest.focus"),
    MANIFEST_WAIVER("manifest.waiver"),
    MODE_WEIGHTS("mode.weights"),
    SCENARIO_JUDGEMENT("scenario.judgement"),
    COMPLEXITY_GRADE("complexity.grade"),
    PERSPECTIVE_ASSIGNMENT("perspective.assignment"),
    PERSPECTIVE_ROUTING("perspective.routing"),
    PERSPECTIVE_READINESS("perspective.readiness"),
    ESCALATION_INITIATE("escalation.initiate"),
    ESCALATION_AUDIT("escalation.audit"),
    ESCALATION_STRATEGIC("escalation.strategic"),
    ESCALATION_FINAL("escalation.final"),
    STRATEGY_VERSION("strategy.version"),
    CAPABILITY_CHANGE("capability.change"),
    ARBITRATION_MAJOR("arbitration.major"),
    AUDIT_EVIDENCE("audit.evidence"),
    AUDIT_BEHAVIOR("audit.behavior"),
    AUDIT_BUDGET("audit.budget"),
    AUDIT_ARCHIVE("audit.archive"),
    REQUIREMENT_COMPLETE("requirement.complete"),
    REQUIREMENT_CHANGE("requirement.change"),
    REQUIREMENT_SATISFACTION("requirement.satisfaction"),
    ACCEPTANCE_CRITERIA("acceptance.criteria"),
    GOAL_CHANGE("goal.change"),
    LIABILITY_ATTRIBUTION("liability.attribution"),
    FINAL_RULING("final.ruling"),
    TOOL_HIGH_RISK("tool.high-risk"),
    BOARD_WRITE("board.write"),
    FREEZE_APPEAL("freeze.appeal");

    companion object {
        fun fromWire(value: String): ApprovalSubject =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 approval subject: $value")
    }
}

data class ApprovalGrant(
    val id: Id,
    val subject: ApprovalSubject,
    val by: PerspectiveId,
    val at: Timestamp,
    val granted: Boolean,
    val note: String? = null,
)

enum class BudgetDimension(val wire: String) {
    TOKENS("tokens"), MS("ms"), CALLS("calls"), INTERRUPTS("interrupts"), MONEY("money")
}

enum class GovernanceCode(val wire: String) {
    INVALID_SCENARIO("invalid-scenario"),
    INVALID_MODE_WEIGHTS("invalid-mode-weights"),
    UNKNOWN_PERSPECTIVE("unknown-perspective"),
    MANIFEST_EXPIRED("manifest-expired"),
    MANIFEST_SCOPE("manifest-scope"),
    MANIFEST_UNSIGNED("manifest-unsigned"),
    MANIFEST_SELF_SIGNED("manifest-self-signed"),
    MANIFEST_VERSION_CONFLICT("manifest-version-conflict"),
    MANIFEST_REDERIVE_REQUIRED("manifest-rederive-required"),
    APPROVAL_REQUIRED("approval-required"),
    APPROVAL_NOT_PERMITTED("approval-not-permitted"),
    APPROVAL_SELF("approval-self"),
    CREDENTIAL_INCOMPLETE("credential-incomplete"),
    CREDENTIAL_INVALID("credential-invalid"),
    CREDENTIAL_EXPIRED("credential-expired"),
    CREDENTIAL_NOT_YET_VALID("credential-not-yet-valid"),
    CREDENTIAL_TAMPERED("credential-tampered"),
    CREDENTIAL_SCOPE("credential-scope"),
    CREDENTIAL_TARGET("credential-target"),
    CREDENTIAL_REVOKED("credential-revoked"),
    CREDENTIAL_MANIFEST("credential-manifest"),
    BUDGET_FROZEN("budget-frozen"),
    BUDGET_INVALID("budget-invalid"),
    BUDGET_EXHAUSTED("budget-exhausted"),
    BUDGET_RESERVE("budget-reserve"),
    LEGAL_FROZEN("legal-frozen"),
    LEGAL_BREAKER("legal-breaker"),
    WORKFLOW_PHASE("workflow-phase"),
    WORKFLOW_STEP("workflow-step"),
    WORKFLOW_ARTIFACT("workflow-artifact"),
    CONTEXT_DENIED("context-denied"),
    REGISTRY_INVALID("registry-invalid"),
    PROBABILITY_INVALID("probability-invalid"),
}

class GovernanceError(
    val code: GovernanceCode,
    detail: String,
) : IllegalArgumentException("${code.wire}: $detail")

fun refKey(ref: Ref): String = "${ref.kind.wire}:${ref.id}"

data class BudgetTotals(
    val tokens: Int,
    val ms: Long,
    val calls: Int,
    val interrupts: Int,
    val money: Double,
)

fun budgetTotals(budget: Budget): BudgetTotals = BudgetTotals(
    tokens = budget.tokens ?: 0,
    ms = budget.ms ?: 0,
    calls = budget.calls ?: 0,
    interrupts = budget.interrupts ?: 0,
    money = budget.money ?: 0.0,
)

fun auditLine(parts: List<String>): String = parts.joinToString(" | ")
