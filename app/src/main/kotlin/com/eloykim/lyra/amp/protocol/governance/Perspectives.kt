package com.eloykim.lyra.amp.protocol.governance

/**
 * The perspective registry (spec section 4, section 12.1), a faithful port of
 * `src/governance/perspectives.ts`.
 *
 * A perspective is a governed attention stance with a manifest, a budget and an
 * approval role. The registry is deliberately data-only: the manifest, credential
 * and workflow modules read it instead of hard-coding a role name.
 *
 * Finance, human resources and administration are rule modules or infrastructure,
 * not switchable perspectives; they remain addressable so their approval and
 * accounting duties can be expressed.
 */

/**
 * One registry row.
 */
data class PerspectiveDefinition(
    val id: PerspectiveId,
    val labelZh: String,
    val labelEn: String,
    val layer: PerspectiveLayer,
    /** What this perspective attends to when its manifest is loaded. */
    val focus: List<String>,
    /** Activation cadence in prose; the scenario table decides per task. */
    val frequency: String,
    val switchable: Boolean,
    /**
     * May this perspective be the target of a same-level switch credential?
     * Strategic perspectives require the escalation path instead.
     */
    val directSwitch: Boolean,
    /** May this perspective counter-sign a switch credential? */
    val signsCredentials: Boolean,
    /** Approval subjects this perspective owns. */
    val approve: List<ApprovalSubject>,
    /** True for the independent evidence audit path. */
    val independentAudit: Boolean,
)

/**
 * The canonical registry. Order follows the governance template.
 */
val PerspectiveRegistry: Map<PerspectiveId, PerspectiveDefinition> = listOf(
    PerspectiveDefinition(
        id = PerspectiveId.SHAREHOLDERS,
        labelZh = "股东大会",
        labelEn = "Shareholders",
        layer = PerspectiveLayer.STRATEGIC,
        focus = listOf("goal authority", "final arbitration", "liability"),
        frequency = "final arbitration only",
        switchable = false,
        directSwitch = false,
        signsCredentials = false,
        approve = listOf(
            ApprovalSubject.GOAL_CHANGE,
            ApprovalSubject.LIABILITY_ATTRIBUTION,
            ApprovalSubject.FINAL_RULING,
            ApprovalSubject.ESCALATION_FINAL,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.BOARD,
        labelZh = "董事会",
        labelEn = "Board",
        layer = PerspectiveLayer.STRATEGIC,
        focus = listOf("strategic goals", "risk appetite", "capability declarations"),
        frequency = "low frequency or on demand",
        switchable = false,
        directSwitch = false,
        signsCredentials = false,
        approve = listOf(
            ApprovalSubject.STRATEGY_VERSION,
            ApprovalSubject.CAPABILITY_CHANGE,
            ApprovalSubject.BUDGET_MAJOR_TOP_UP,
            ApprovalSubject.ARBITRATION_MAJOR,
            ApprovalSubject.ESCALATION_STRATEGIC,
            ApprovalSubject.BOARD_WRITE,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.SUPERVISORY,
        labelZh = "监事会",
        labelEn = "Supervisory board",
        layer = PerspectiveLayer.STRATEGIC,
        focus = listOf("evidence traceability", "receipt self-consistency"),
        frequency = "sampled per scenario",
        switchable = false,
        directSwitch = false,
        signsCredentials = false,
        approve = listOf(ApprovalSubject.AUDIT_EVIDENCE, ApprovalSubject.AUDIT_ARCHIVE),
        independentAudit = true,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.GENERAL_MANAGER,
        labelZh = "总经理",
        labelEn = "General manager",
        layer = PerspectiveLayer.MANAGEMENT,
        focus = listOf(
            "switch triggers",
            "budget consumption",
            "conflict detection",
            "scenario and mode approvals",
        ),
        frequency = "resident, high frequency",
        switchable = true,
        directSwitch = true,
        signsCredentials = true,
        approve = listOf(
            ApprovalSubject.CREDENTIAL_ISSUE,
            ApprovalSubject.MANIFEST_DERIVE,
            ApprovalSubject.MANIFEST_FOCUS,
            ApprovalSubject.SCENARIO_JUDGEMENT,
            ApprovalSubject.COMPLEXITY_GRADE,
            ApprovalSubject.MODE_WEIGHTS,
            ApprovalSubject.ESCALATION_INITIATE,
            ApprovalSubject.ESCALATION_AUDIT,
            ApprovalSubject.ARBITRATION_MAJOR,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.CUSTOMER_SERVICE,
        labelZh = "客服部",
        labelEn = "Customer service",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf(
            "user intent",
            "ambiguities",
            "constraints",
            "acceptance criteria",
            "emotional signals",
        ),
        frequency = "task entry and closing",
        switchable = true,
        directSwitch = true,
        signsCredentials = false,
        approve = listOf(
            ApprovalSubject.REQUIREMENT_COMPLETE,
            ApprovalSubject.REQUIREMENT_CHANGE,
            ApprovalSubject.REQUIREMENT_SATISFACTION,
            ApprovalSubject.ACCEPTANCE_CRITERIA,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.WRITING,
        labelZh = "写作视角",
        labelEn = "Writing desk",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf("structure", "voice", "material selection", "revision"),
        frequency = "activated by the writing scenario",
        switchable = true,
        directSwitch = true,
        signsCredentials = false,
        approve = emptyList(),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.STRATEGY,
        labelZh = "战略部",
        labelEn = "Strategy",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf("task decomposition", "dependency structure", "risk points"),
        frequency = "complex tasks",
        switchable = true,
        directSwitch = true,
        signsCredentials = false,
        approve = emptyList(),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.HUMAN_RESOURCES,
        labelZh = "人力资源部",
        labelEn = "Human resources",
        layer = PerspectiveLayer.RULE,
        focus = listOf("capability fit", "perspective availability", "reliability priors"),
        frequency = "planning and preparation",
        switchable = false,
        directSwitch = false,
        signsCredentials = false,
        approve = listOf(
            ApprovalSubject.PERSPECTIVE_ASSIGNMENT,
            ApprovalSubject.PERSPECTIVE_ROUTING,
            ApprovalSubject.PERSPECTIVE_READINESS,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.MARKETING,
        labelZh = "市场部",
        labelEn = "Market research",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf(
            "external information needs",
            "environment assumptions",
            "competitor, user and policy signals",
        ),
        frequency = "per scenario and mode",
        switchable = true,
        directSwitch = true,
        signsCredentials = false,
        approve = emptyList(),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.TECHNOLOGY,
        labelZh = "技术部",
        labelEn = "Technology",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf("current subgoal", "tool returns", "error logs"),
        frequency = "on demand",
        switchable = true,
        directSwitch = true,
        signsCredentials = false,
        approve = emptyList(),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.LEGAL,
        labelZh = "法务部",
        labelEn = "Legal",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf("behaviour-code compliance", "boundary signals", "injection traces"),
        frequency = "pre-rules plus on demand",
        switchable = true,
        directSwitch = true,
        signsCredentials = true,
        approve = listOf(
            ApprovalSubject.CREDENTIAL_COMPLIANCE,
            ApprovalSubject.MANIFEST_CONSTRAINTS,
            ApprovalSubject.MANIFEST_EVIDENCE_FLOOR,
            ApprovalSubject.MANIFEST_WAIVER,
            ApprovalSubject.TOOL_HIGH_RISK,
            ApprovalSubject.BOARD_WRITE,
            ApprovalSubject.AUDIT_BEHAVIOR,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.FINANCE,
        labelZh = "财务部",
        labelEn = "Finance",
        layer = PerspectiveLayer.RULE,
        focus = listOf("three-ledger balances", "overrun signals", "elasticity use"),
        frequency = "rule module",
        switchable = false,
        directSwitch = false,
        signsCredentials = true,
        approve = listOf(
            ApprovalSubject.BUDGET_INITIAL,
            ApprovalSubject.BUDGET_ALLOCATION,
            ApprovalSubject.BUDGET_FREEZE,
            ApprovalSubject.BUDGET_ELASTICITY,
            ApprovalSubject.BUDGET_TOP_UP,
            ApprovalSubject.BUDGET_SETTLEMENT,
            ApprovalSubject.BUDGET_RESERVE,
            ApprovalSubject.BUDGET_MAJOR_TOP_UP,
            ApprovalSubject.AUDIT_BUDGET,
        ),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.ADMINISTRATION,
        labelZh = "行政部",
        labelEn = "Administration",
        layer = PerspectiveLayer.RULE,
        focus = listOf("log integrity", "archive state", "version chains"),
        frequency = "infrastructure",
        switchable = false,
        directSwitch = false,
        signsCredentials = false,
        approve = listOf(ApprovalSubject.AUDIT_ARCHIVE),
        independentAudit = false,
    ),
    PerspectiveDefinition(
        id = PerspectiveId.OPERATIONS,
        labelZh = "运营部",
        labelEn = "Operations",
        layer = PerspectiveLayer.EXECUTION,
        focus = listOf("phase transitions", "rollback triggers", "timeouts"),
        frequency = "resident during execution",
        switchable = true,
        directSwitch = true,
        signsCredentials = false,
        approve = emptyList(),
        independentAudit = false,
    ),
).associateBy { it.id }

/**
 * The standing perspectives of section 12.1 (the writing desk is a
 * scenario-specific view and is excluded from the standing count).
 */
val StandingPerspectives: List<PerspectiveId> = listOf(
    PerspectiveId.BOARD,
    PerspectiveId.SUPERVISORY,
    PerspectiveId.GENERAL_MANAGER,
    PerspectiveId.CUSTOMER_SERVICE,
    PerspectiveId.STRATEGY,
    PerspectiveId.MARKETING,
    PerspectiveId.TECHNOLOGY,
    PerspectiveId.LEGAL,
    PerspectiveId.OPERATIONS,
)

/**
 * Look up a perspective, failing closed for unknown ids.
 */
fun perspective(id: String): PerspectiveDefinition {
    val known = PerspectiveId.entries.firstOrNull { it.wire == id }
        ?: throw GovernanceError(GovernanceCode.UNKNOWN_PERSPECTIVE, "$id is not a registered perspective")
    return PerspectiveRegistry.getValue(known)
}

fun perspective(id: PerspectiveId): PerspectiveDefinition = perspective(id.wire)

fun isKnownPerspective(id: String): Boolean = PerspectiveId.entries.any { it.wire == id }

/**
 * Rule modules and infrastructure are not switchable perspectives.
 */
fun isRuleModule(id: PerspectiveId): Boolean = PerspectiveRegistry.getValue(id).layer == PerspectiveLayer.RULE

/**
 * Strategic perspectives are reached through escalation, never a same-level switch
 * credential.
 */
fun requiresEscalation(id: PerspectiveId): Boolean =
    PerspectiveRegistry.getValue(id).layer == PerspectiveLayer.STRATEGIC

fun canApprove(id: PerspectiveId, subject: ApprovalSubject): Boolean =
    PerspectiveRegistry.getValue(id).approve.contains(subject)

/**
 * Who may approve a subject, in registry order (used for routing refusals).
 */
fun approversOf(subject: ApprovalSubject): List<PerspectiveId> =
    PerspectiveId.entries.filter { canApprove(it, subject) }

/**
 * A same-level switch credential is issued by the general manager to a
 * non-strategic, directly switchable perspective.
 */
fun canIssueCredential(from: PerspectiveId, to: PerspectiveId): Boolean {
    if (from == to) {
        return false
    }
    if (from != PerspectiveId.GENERAL_MANAGER) {
        return false
    }
    val target = PerspectiveRegistry.getValue(to)
    return target.directSwitch && target.switchable && target.layer == PerspectiveLayer.EXECUTION
}

/** Escalation types (spec section 10); `compliance` goes to legal, not a board. */
enum class EscalationType(val wire: String) {
    AUDIT("audit"),
    STRATEGY("strategy"),
    FINAL("final"),
    COMPLIANCE("compliance"),
}

/**
 * Which strategic target an escalation type may reach.
 */
fun escalationTarget(type: EscalationType): PerspectiveId = when (type) {
    EscalationType.AUDIT -> PerspectiveId.SUPERVISORY
    EscalationType.STRATEGY -> PerspectiveId.BOARD
    EscalationType.FINAL -> PerspectiveId.SHAREHOLDERS
    EscalationType.COMPLIANCE -> PerspectiveId.LEGAL
}

/**
 * Scenario activation for one perspective (spec section 9.8).
 */
fun activationFor(id: PerspectiveId, scenario: ScenarioId): Activation {
    val plan = PerspectiveActivationTable.getValue(scenario)
    if (plan.default.contains(id)) {
        return Activation.DEFAULT
    }
    if (plan.onDemand.contains(id)) {
        return Activation.ON_DEMAND
    }
    return Activation.DORMANT
}

/**
 * The activation plan for a scenario, with copies so callers cannot mutate the
 * table.
 */
fun perspectivesForScenario(scenario: ScenarioId): ScenarioPerspectives {
    val plan = PerspectiveActivationTable.getValue(scenario)
    return ScenarioPerspectives(
        default = plan.default.toList(),
        onDemand = plan.onDemand.toList(),
        dormant = plan.dormant.toList(),
        note = plan.note,
    )
}

/**
 * Registry self-check; used by checks and on first import in development.
 */
fun validatePerspectiveRegistry(): List<String> {
    val problems = ArrayList<String>()
    val seen = LinkedHashSet<PerspectiveId>()
    for (id in PerspectiveId.entries) {
        if (!seen.add(id)) {
            problems += "duplicate perspective id: ${id.wire}"
        }
        val definition = PerspectiveRegistry[id]
        if (definition == null) {
            problems += "missing registry row: ${id.wire}"
            continue
        }
        if (definition.id != id) {
            problems += "registry key ${id.wire} disagrees with row id ${definition.id.wire}"
        }
        if (definition.layer == PerspectiveLayer.RULE && definition.switchable) {
            problems += "rule module ${id.wire} must not be switchable"
        }
        if (definition.layer == PerspectiveLayer.STRATEGIC && definition.directSwitch) {
            problems += "strategic perspective ${id.wire} must not be a direct switch target"
        }
        if (definition.signsCredentials &&
            id != PerspectiveId.GENERAL_MANAGER &&
            id != PerspectiveId.LEGAL &&
            id != PerspectiveId.FINANCE
        ) {
            problems += "${id.wire} must not counter-sign credentials"
        }
    }
    if (!PerspectiveId.entries.contains(PerspectiveId.GENERAL_MANAGER)) {
        problems += "the registry must contain the general manager"
    }
    return problems
}
