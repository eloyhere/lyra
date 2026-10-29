package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.time.Instant

/**
 * The seven-phase workflow with approvals (spec section 11), a faithful port of
 * `src/governance/workflow.ts`.
 *
 * The workflow is the only place that decides whether a phase may advance. It
 * enforces three rules that the rest of the protocol depends on:
 *   - an approval cannot be issued by the perspective that requested it;
 *   - a phase gate needs its artefacts and its approvals, not just a mood;
 *   - execution cannot start without a triple-signed switch credential.
 *
 * The phase table is data, so a change to a step is reviewable as a diff.
 *
 * 凭据与本文件同包：`attachCredential` 直接接收 `Credential.kt` 的
 * `SwitchCredential`，并用 `verifyCredential` 复核。工作流不复制凭据字段，也
 * 不信任调用方给出的摘要字符串。
 */

// ---------------------------------------------------------------------------
// 阶段与步骤
// ---------------------------------------------------------------------------

/** The seven phases, in order. */
enum class PhaseId(val wire: String) {
    INTAKE("intake"),
    PLANNING("planning"),
    PREPARATION("preparation"),
    EXECUTION("execution"),
    AUDIT("audit"),
    ESCALATION("escalation"),
    ARCHIVE("archive");

    companion object {
        val order: List<PhaseId> = entries

        fun fromWire(value: String): PhaseId =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 workflow phase: $value")
    }
}

/** 阶段推进时的工作流状态。 */
enum class WorkflowStatus(val wire: String) {
    OPEN("open"),
    BLOCKED("blocked"),
    COMPLETE("complete"),
    TERMINATED("terminated");

    companion object {
        fun fromWire(value: String): WorkflowStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 workflow status: $value")
    }
}

/**
 * One step of a phase: who acts, what they do, what they produce.
 */
data class StepSpec(
    val id: String,
    val phase: PhaseId,
    val owner: PerspectiveId,
    val action: String,
    val produces: String,
    val optional: Boolean = false,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "phase" to jsonText(phase.wire),
        "owner" to jsonText(owner.wire),
        "action" to jsonText(action),
        "produces" to jsonText(produces),
        "optional" to if (optional) JsonValue.Bool(true) else null,
    )
}

/** One recorded artefact; `kind` is the step's `produces` value. */
data class Artifact(
    val id: Id,
    val kind: String,
    val by: PerspectiveId,
    val at: Timestamp,
    val signoffs: List<SignoffRole>? = null,
    val digest: Digest? = null,
    val note: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "kind" to jsonText(kind),
        "by" to jsonText(by.wire),
        "at" to jsonText(at),
        "signoffs" to signoffs?.let { roles -> jsonArr(*roles.map { jsonText(it.wire) }.toTypedArray()) },
        "digest" to digest?.let { jsonText(it) },
        "note" to note?.let { jsonText(it) },
    )
}

/** `Omit<Artifact, "id" | "by">`：调用方提供的产物正文。 */
data class ArtifactDraft(
    val kind: String,
    val at: Timestamp,
    val signoffs: List<SignoffRole>? = null,
    val digest: Digest? = null,
    val note: String? = null,
)

/** An approval with the perspective that asked for it. */
data class WorkflowApproval(
    val id: Id,
    val subject: ApprovalSubject,
    val by: PerspectiveId,
    val at: Timestamp,
    val granted: Boolean,
    val requestedBy: PerspectiveId,
    val note: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "subject" to jsonText(subject.wire),
        "by" to jsonText(by.wire),
        "at" to jsonText(at),
        "granted" to jsonBool(granted),
        "requestedBy" to jsonText(requestedBy.wire),
        "note" to note?.let { jsonText(it) },
    )
}

/** One phase transition. `from == null` is the reference's `"none"`. */
data class PhaseTransition(
    val from: PhaseId?,
    val to: PhaseId,
    val by: PerspectiveId,
    val at: Timestamp,
    val note: String,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "from" to jsonText(from?.wire ?: "none"),
        "to" to jsonText(to.wire),
        "by" to jsonText(by.wire),
        "at" to jsonText(at),
        "note" to jsonText(note),
    )
}

/** The escalation branch's record. */
data class EscalationRecord(
    val type: EscalationType,
    val reason: String,
    val evidenceRefs: Int,
    val by: PerspectiveId,
    val at: Timestamp,
    val target: PerspectiveId,
    val returnPhase: PhaseId,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "type" to jsonText(type.wire),
        "reason" to jsonText(reason),
        "evidenceRefs" to jsonNumber(evidenceRefs),
        "by" to jsonText(by.wire),
        "at" to jsonText(at),
        "target" to jsonText(target.wire),
        "returnPhase" to jsonText(returnPhase.wire),
    )
}

/** The credential the workflow binds in the preparation phase. */
data class BoundCredential(
    val id: Id,
    val signoffs: List<SignoffRole>,
    val expires: Timestamp,
    val digest: Digest,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "signoffs" to jsonArr(*signoffs.map { jsonText(it.wire) }.toTypedArray()),
        "expires" to jsonText(expires),
        "digest" to jsonText(digest),
    )
}

/** The whole workflow state; `current()` hands out a defensive copy. */
data class WorkflowState(
    val id: Id,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val phase: PhaseId,
    val status: WorkflowStatus,
    val completedSteps: List<String>,
    val artifacts: List<Artifact>,
    val approvals: List<WorkflowApproval>,
    val manifest: ManifestRef? = null,
    val credential: BoundCredential? = null,
    val escalation: EscalationRecord? = null,
    val history: List<PhaseTransition> = emptyList(),
    val blockedReason: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "scenario" to jsonText(scenario.wire),
        "modes" to modes.toJson(),
        "phase" to jsonText(phase.wire),
        "status" to jsonText(status.wire),
        "completedSteps" to jsonArr(*completedSteps.map { jsonText(it) }.toTypedArray()),
        "artifacts" to jsonArr(*artifacts.map { it.toJson() }.toTypedArray()),
        "approvals" to jsonArr(*approvals.map { it.toJson() }.toTypedArray()),
        "manifest" to manifest?.let { jsonObj("id" to jsonText(it.id), "version" to jsonNumber(it.version)) },
        "credential" to credential?.toJson(),
        "escalation" to escalation?.toJson(),
        "history" to jsonArr(*history.map { it.toJson() }.toTypedArray()),
        "blockedReason" to blockedReason?.let { jsonText(it) },
    )
}

/** `Workflow.start` 的输入。 */
data class StartInput(
    val id: Id,
    val scenario: ScenarioId,
    val modes: Map<ModeId, Double>? = null,
    val at: Timestamp,
)

/** `approve(...)` 的输入。 */
data class ApproveInput(
    val subject: ApprovalSubject,
    val by: PerspectiveId,
    val at: Timestamp,
    val requestedBy: PerspectiveId,
    val granted: Boolean = true,
    val note: String? = null,
)

/** `escalate(...)` 的输入。 */
data class EscalateInput(
    val by: PerspectiveId,
    val at: Timestamp,
    val type: EscalationType,
    val reason: String,
    val evidenceRefs: Int,
)

/**
 * The phase table. Actions are short because the artefacts carry the detail.
 */
val PhaseSteps: Map<PhaseId, List<StepSpec>> = mapOf(
    PhaseId.INTAKE to listOf(
        StepSpec(
            id = "intake.requirement",
            phase = PhaseId.INTAKE,
            owner = PerspectiveId.CUSTOMER_SERVICE,
            action = "clarify the request, judge the scenario, propose modes",
            produces = "requirement-summary",
        ),
        StepSpec(
            id = "intake.research",
            phase = PhaseId.INTAKE,
            owner = PerspectiveId.MARKETING,
            action = "run pre-task research if the scenario needs it",
            produces = "environment-summary",
            optional = true,
        ),
        StepSpec(
            id = "intake.complexity",
            phase = PhaseId.INTAKE,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "accept the summary and grade complexity",
            produces = "complexity-grade",
        ),
        StepSpec(
            id = "intake.budget",
            phase = PhaseId.INTAKE,
            owner = PerspectiveId.FINANCE,
            action = "set the initial budget by scenario",
            produces = "initial-budget",
        ),
        StepSpec(
            id = "intake.strategy",
            phase = PhaseId.INTAKE,
            owner = PerspectiveId.BOARD,
            action = "issue the strategy summary",
            produces = "strategy-summary",
        ),
        StepSpec(
            id = "intake.log",
            phase = PhaseId.INTAKE,
            owner = PerspectiveId.ADMINISTRATION,
            action = "initialise logs, blackboard, evidence store and version chain",
            produces = "log-init",
        ),
    ),
    PhaseId.PLANNING to listOf(
        StepSpec(
            id = "planning.route",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.STRATEGY,
            action = "decompose the task and name risks",
            produces = "execution-route",
        ),
        StepSpec(
            id = "planning.staffing",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.HUMAN_RESOURCES,
            action = "assign perspectives and confirm capabilities",
            produces = "perspective-plan",
        ),
        StepSpec(
            id = "planning.research",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.MARKETING,
            action = "set the research intensity",
            produces = "research-plan",
            optional = true,
        ),
        StepSpec(
            id = "planning.budget",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.FINANCE,
            action = "split the three ledgers and set elasticity",
            produces = "budget-plan",
        ),
        StepSpec(
            id = "planning.constraints",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.LEGAL,
            action = "inject constraints and set evidence floors",
            produces = "behavior-constraints",
        ),
        StepSpec(
            id = "planning.state",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.OPERATIONS,
            action = "design the state machine",
            produces = "state-machine",
        ),
        StepSpec(
            id = "planning.manifest",
            phase = PhaseId.PLANNING,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "derive the initial manifest",
            produces = "attention-manifest",
        ),
    ),
    PhaseId.PREPARATION to listOf(
        StepSpec(
            id = "preparation.credential",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "issue the switch credential and the manifest",
            produces = "switch-credential",
        ),
        StepSpec(
            id = "preparation.compliance",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.LEGAL,
            action = "gate the credential for compliance",
            produces = "compliance-verdict",
        ),
        StepSpec(
            id = "preparation.freeze",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.FINANCE,
            action = "freeze the budget and arm elasticity triggers",
            produces = "budget-freeze",
        ),
        StepSpec(
            id = "preparation.readiness",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.HUMAN_RESOURCES,
            action = "confirm the perspectives are ready",
            produces = "readiness",
        ),
        StepSpec(
            id = "preparation.context",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.ADMINISTRATION,
            action = "prepare context, evidence store and version chain",
            produces = "context-ready",
        ),
        StepSpec(
            id = "preparation.tools",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.TECHNOLOGY,
            action = "confirm the tools are ready",
            produces = "tools-ready",
        ),
        StepSpec(
            id = "preparation.state",
            phase = PhaseId.PREPARATION,
            owner = PerspectiveId.OPERATIONS,
            action = "initialise the state machine",
            produces = "state-ready",
        ),
    ),
    PhaseId.EXECUTION to listOf(
        StepSpec(
            id = "execution.tools",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.TECHNOLOGY,
            action = "execute tool calls under the manifest",
            produces = "execution-receipt",
        ),
        StepSpec(
            id = "execution.research",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.MARKETING,
            action = "research under the scenario and mode",
            produces = "research-receipt",
            optional = true,
        ),
        StepSpec(
            id = "execution.state",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.OPERATIONS,
            action = "advance the state machine",
            produces = "state-advance",
        ),
        StepSpec(
            id = "execution.legal",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.LEGAL,
            action = "gate key nodes; freeze or break the circuit",
            produces = "legal-checkpoint",
        ),
        StepSpec(
            id = "execution.finance",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.FINANCE,
            action = "charge in real time and approve top-ups",
            produces = "budget-charge",
        ),
        StepSpec(
            id = "execution.manager",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "monitor, switch modes and reassess ignored items",
            produces = "mode-decision",
        ),
        StepSpec(
            id = "execution.log",
            phase = PhaseId.EXECUTION,
            owner = PerspectiveId.ADMINISTRATION,
            action = "record receipts, logs and the mode-switch audit chain",
            produces = "audit-log",
        ),
    ),
    PhaseId.AUDIT to listOf(
        StepSpec(
            id = "audit.evidence",
            phase = PhaseId.AUDIT,
            owner = PerspectiveId.SUPERVISORY,
            action = "independently audit the evidence trail",
            produces = "audit-evidence",
        ),
        StepSpec(
            id = "audit.behavior",
            phase = PhaseId.AUDIT,
            owner = PerspectiveId.LEGAL,
            action = "audit behaviour compliance",
            produces = "audit-behavior",
        ),
        StepSpec(
            id = "audit.budget",
            phase = PhaseId.AUDIT,
            owner = PerspectiveId.FINANCE,
            action = "audit the budget against elasticity",
            produces = "audit-budget",
        ),
        StepSpec(
            id = "audit.satisfaction",
            phase = PhaseId.AUDIT,
            owner = PerspectiveId.CUSTOMER_SERVICE,
            action = "confirm requirement satisfaction",
            produces = "satisfaction",
            optional = true,
        ),
        StepSpec(
            id = "audit.decision",
            phase = PhaseId.AUDIT,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "decide pass, rework or escalate with three audit receipts",
            produces = "audit-decision",
        ),
    ),
    PhaseId.ESCALATION to listOf(
        StepSpec(
            id = "escalation.initiate",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "initiate with reasons and evidence refs",
            produces = "escalation-request",
        ),
        StepSpec(
            id = "escalation.reserve",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.FINANCE,
            action = "allocate from the escalation reserve",
            produces = "reserve-approval",
        ),
        StepSpec(
            id = "escalation.readiness",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.HUMAN_RESOURCES,
            action = "confirm the strategic perspective is ready",
            produces = "strategic-readiness",
        ),
        StepSpec(
            id = "escalation.authorize",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "obtain the type-specific authorisation",
            produces = "escalation-authorization",
        ),
        StepSpec(
            id = "escalation.arbitrate",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.SUPERVISORY,
            action = "audit or arbitrate in an independent context",
            produces = "arbitration",
        ),
        StepSpec(
            id = "escalation.requirement",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.CUSTOMER_SERVICE,
            action = "state the demand-side opinion",
            produces = "requirement-opinion",
            optional = true,
        ),
        StepSpec(
            id = "escalation.apply",
            phase = PhaseId.ESCALATION,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "apply the outcome to manifest, scenario or modes",
            produces = "escalation-outcome",
        ),
    ),
    PhaseId.ARCHIVE to listOf(
        StepSpec(
            id = "archive.logs",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.ADMINISTRATION,
            action = "archive logs, receipts, audit chain and version chains",
            produces = "archive-package",
        ),
        StepSpec(
            id = "archive.settlement",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.FINANCE,
            action = "settle the cost",
            produces = "budget-settlement",
        ),
        StepSpec(
            id = "archive.audit",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.SUPERVISORY,
            action = "audit the archive",
            produces = "archive-audit",
        ),
        StepSpec(
            id = "archive.compliance",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.LEGAL,
            action = "archive compliance",
            produces = "compliance-archive",
        ),
        StepSpec(
            id = "archive.feedback",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.CUSTOMER_SERVICE,
            action = "archive user feedback including the emotional trace",
            produces = "user-feedback",
        ),
        StepSpec(
            id = "archive.close",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.GENERAL_MANAGER,
            action = "decide complete, terminate or hand over",
            produces = "closing-decision",
        ),
        StepSpec(
            id = "archive.retro-board",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.BOARD,
            action = "review the strategy and the scenario judgement",
            produces = "strategy-retro",
        ),
        StepSpec(
            id = "archive.capability",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.HUMAN_RESOURCES,
            action = "update capability statistics and matching priors",
            produces = "capability-priors",
        ),
        StepSpec(
            id = "archive.retro-ops",
            phase = PhaseId.ARCHIVE,
            owner = PerspectiveId.STRATEGY,
            action = "review the process and the mode weights",
            produces = "process-retro",
        ),
    ),
)

/** What a phase must have before it can advance. */
private data class PhaseGate(
    val approvals: List<ApprovalSubject>,
    val artifacts: List<String>,
    val note: String,
)

private val PhaseGates: Map<PhaseId, PhaseGate> = mapOf(
    PhaseId.INTAKE to PhaseGate(
        approvals = listOf(
            ApprovalSubject.REQUIREMENT_COMPLETE,
            ApprovalSubject.SCENARIO_JUDGEMENT,
            ApprovalSubject.COMPLEXITY_GRADE,
            ApprovalSubject.BUDGET_INITIAL,
        ),
        artifacts = listOf(
            "requirement-summary",
            "complexity-grade",
            "initial-budget",
            "strategy-summary",
            "log-init",
        ),
        note = "a confirmed requirement, an approved scenario and complexity, an initial budget and a strategy digest",
    ),
    PhaseId.PLANNING to PhaseGate(
        approvals = listOf(
            ApprovalSubject.MANIFEST_DERIVE,
            ApprovalSubject.PERSPECTIVE_ROUTING,
            ApprovalSubject.BUDGET_ALLOCATION,
            ApprovalSubject.MANIFEST_CONSTRAINTS,
        ),
        artifacts = listOf(
            "execution-route",
            "perspective-plan",
            "budget-plan",
            "behavior-constraints",
            "state-machine",
            "attention-manifest",
        ),
        note = "a signed manifest plus the route, staffing, budget, constraints and state machine",
    ),
    PhaseId.PREPARATION to PhaseGate(
        approvals = listOf(
            ApprovalSubject.CREDENTIAL_ISSUE,
            ApprovalSubject.CREDENTIAL_COMPLIANCE,
            ApprovalSubject.BUDGET_FREEZE,
            ApprovalSubject.PERSPECTIVE_READINESS,
        ),
        artifacts = listOf(
            "switch-credential",
            "compliance-verdict",
            "budget-freeze",
            "readiness",
            "context-ready",
            "tools-ready",
            "state-ready",
        ),
        note = "a triple-signed credential, a compliance verdict and a frozen budget",
    ),
    PhaseId.EXECUTION to PhaseGate(
        approvals = emptyList(),
        artifacts = listOf(
            "execution-receipt",
            "legal-checkpoint",
            "budget-charge",
            "mode-decision",
            "audit-log",
        ),
        note = "at least one executed receipt with legal, budget and mode records",
    ),
    PhaseId.AUDIT to PhaseGate(
        approvals = listOf(
            ApprovalSubject.AUDIT_EVIDENCE,
            ApprovalSubject.AUDIT_BEHAVIOR,
            ApprovalSubject.AUDIT_BUDGET,
            ApprovalSubject.REQUIREMENT_SATISFACTION,
        ),
        artifacts = listOf("audit-evidence", "audit-behavior", "audit-budget", "audit-decision"),
        note = "the three independent audit receipts precede the general manager's decision",
    ),
    PhaseId.ESCALATION to PhaseGate(
        approvals = listOf(
            ApprovalSubject.ESCALATION_INITIATE,
            ApprovalSubject.BUDGET_RESERVE,
            ApprovalSubject.PERSPECTIVE_READINESS,
        ),
        artifacts = listOf(
            "escalation-request",
            "reserve-approval",
            "strategic-readiness",
            "escalation-authorization",
            "arbitration",
            "escalation-outcome",
        ),
        note = "a reserve draw, a ready strategic perspective and a type-specific authorisation",
    ),
    PhaseId.ARCHIVE to PhaseGate(
        approvals = listOf(
            ApprovalSubject.BUDGET_SETTLEMENT,
            ApprovalSubject.AUDIT_ARCHIVE,
            ApprovalSubject.REQUIREMENT_SATISFACTION,
        ),
        artifacts = listOf(
            "archive-package",
            "budget-settlement",
            "archive-audit",
            "compliance-archive",
            "closing-decision",
            "strategy-retro",
            "capability-priors",
            "process-retro",
        ),
        note = "a settled budget, an audited archive and the closing decision",
    ),
)

/** Which approval each escalation type still needs at the escalation gate. */
private val EscalationApproval: Map<EscalationType, ApprovalSubject> = mapOf(
    EscalationType.AUDIT to ApprovalSubject.ESCALATION_AUDIT,
    EscalationType.STRATEGY to ApprovalSubject.ESCALATION_STRATEGIC,
    EscalationType.FINAL to ApprovalSubject.ESCALATION_FINAL,
    EscalationType.COMPLIANCE to ApprovalSubject.CREDENTIAL_COMPLIANCE,
)

private val RequiredSignoffs: List<SignoffRole> =
    listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE)

/**
 * The workflow engine. It is deliberately linear: `escalate` side-branches and
 * `resume` returns, and `advance` is the only door to the next phase.
 */
class Workflow private constructor(initialState: WorkflowState) {

    private var state: WorkflowState = cloneState(initialState)
    private var counter: Int = 0

    companion object {

        /**
         * Start a workflow. `modes` is an optional override of the scenario's
         * matrix row; [resolveScenarioModes] normalizes it.
         */
        fun start(input: StartInput): Workflow {
            if (input.id.isEmpty()) {
                throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "a workflow needs an id")
            }
            val plan = resolveScenarioModes(input.scenario, input.modes)
            val state = WorkflowState(
                id = input.id,
                scenario = input.scenario,
                modes = plan.modes,
                phase = PhaseId.INTAKE,
                status = WorkflowStatus.OPEN,
                completedSteps = emptyList(),
                artifacts = emptyList(),
                approvals = emptyList(),
                history = listOf(
                    PhaseTransition(
                        from = null,
                        to = PhaseId.INTAKE,
                        by = PerspectiveId.GENERAL_MANAGER,
                        at = input.at,
                        note = "created",
                    ),
                ),
            )
            return Workflow(state)
        }

        /** Reopen an existing state (store replay, resumption from a receipt). */
        fun of(state: WorkflowState): Workflow = Workflow(state)
    }

    fun current(): WorkflowState = cloneState(state)

    fun steps(): List<StepSpec> = PhaseSteps.getValue(state.phase).map { it.copy() }

    /**
     * Complete one step of the current phase and record its artefact.
     */
    fun complete(stepId: String, by: PerspectiveId, at: Timestamp, note: String? = null): WorkflowState {
        if (state.status == WorkflowStatus.TERMINATED || state.status == WorkflowStatus.COMPLETE) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "workflow is ${state.status.wire}")
        }
        if (state.status == WorkflowStatus.BLOCKED) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_PHASE,
                "workflow is blocked: ${state.blockedReason ?: "no reason"}",
            )
        }
        val step = PhaseSteps.getValue(state.phase).firstOrNull { it.id == stepId }
            ?: throw GovernanceError(GovernanceCode.WORKFLOW_STEP, "$stepId is not a step of ${state.phase.wire}")
        if (step.owner != by) {
            throw GovernanceError(GovernanceCode.WORKFLOW_STEP, "$stepId belongs to ${step.owner.wire}, not ${by.wire}")
        }
        if (state.completedSteps.contains(stepId)) {
            throw GovernanceError(GovernanceCode.WORKFLOW_STEP, "$stepId is already complete")
        }
        advanceCounter()
        state = state.copy(
            completedSteps = state.completedSteps + stepId,
            artifacts = state.artifacts + Artifact(
                id = artifactId(),
                kind = step.produces,
                by = by,
                at = at,
                note = note,
            ),
        )
        return cloneState(state)
    }

    /**
     * Complete a step and record a richer artefact in one operation. Use this
     * when the artefact carries sign-offs or a digest (a credential, an audit
     * receipt); [complete] records the plain artefact.
     */
    fun attach(stepId: String, by: PerspectiveId, artifact: ArtifactDraft): WorkflowState {
        if (artifact.kind == "switch-credential") {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_ARTIFACT,
                "use attachCredential(): a fabricated credential artefact is not evidence",
            )
        }
        return recordArtifact(stepId, by, artifact)
    }

    /**
     * Internal artefact recorder. The public [attach] guards the credential
     * kind; [attachCredential] enters here only after verifying a real one.
     */
    private fun recordArtifact(stepId: String, by: PerspectiveId, artifact: ArtifactDraft): WorkflowState {
        if (state.status != WorkflowStatus.OPEN) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "workflow is ${state.status.wire}")
        }
        val step = PhaseSteps.getValue(state.phase).firstOrNull { it.id == stepId }
        if (step == null || step.owner != by) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_STEP,
                "$stepId is not ${by.wire}'s step in ${state.phase.wire}",
            )
        }
        if (state.completedSteps.contains(stepId)) {
            throw GovernanceError(GovernanceCode.WORKFLOW_STEP, "$stepId is already complete")
        }
        advanceCounter()
        state = state.copy(
            completedSteps = state.completedSteps + stepId,
            artifacts = state.artifacts + Artifact(
                id = artifactId(),
                kind = artifact.kind,
                by = by,
                at = artifact.at,
                signoffs = artifact.signoffs?.toList(),
                digest = artifact.digest,
                note = artifact.note,
            ),
        )
        return cloneState(state)
    }

    /**
     * Attach a real, verified switch credential to the preparation phase and
     * bind it to the state. The workflow never trusts a caller-supplied digest
     * string.
     */
    fun attachCredential(credential: SwitchCredential, at: Timestamp): WorkflowState {
        if (state.phase != PhaseId.PREPARATION) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "a credential is attached in the preparation phase")
        }
        val verdict = verifyCredential(
            credential,
            VerifyCredentialRequest(at = at, to = credential.to, manifest = credential.manifest),
        )
        if (!verdict.valid) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_ARTIFACT,
                "credential rejected: ${verdict.detail}",
            )
        }
        recordArtifact(
            "preparation.credential",
            PerspectiveId.GENERAL_MANAGER,
            ArtifactDraft(
                kind = "switch-credential",
                at = at,
                signoffs = credential.signoffs.map { it.role },
                digest = credential.digest,
            ),
        )
        state = state.copy(
            credential = BoundCredential(
                id = credential.id,
                signoffs = credential.signoffs.map { it.role },
                expires = credential.expires,
                digest = credential.digest,
            ),
        )
        return cloneState(state)
    }

    /**
     * Record an approval. The approver must own the subject and must differ
     * from the requester.
     */
    fun approve(input: ApproveInput): WorkflowState {
        if (!canApprove(input.by, input.subject)) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_NOT_PERMITTED,
                "${input.by.wire} may not approve ${input.subject.wire}",
            )
        }
        if (input.by == input.requestedBy) {
            throw GovernanceError(GovernanceCode.APPROVAL_SELF, "${input.by.wire} cannot approve its own request")
        }
        advanceCounter()
        state = state.copy(
            approvals = state.approvals + WorkflowApproval(
                id = approvalId(),
                subject = input.subject,
                by = input.by,
                at = input.at,
                granted = input.granted,
                requestedBy = input.requestedBy,
                note = input.note,
            ),
        )
        return cloneState(state)
    }

    fun hasApproval(subject: ApprovalSubject): Boolean =
        state.approvals.any { it.subject == subject && it.granted }

    /**
     * Advance to the next phase. Only the general manager may call this, and
     * only when the gate is satisfied.
     */
    fun advance(by: PerspectiveId, at: Timestamp): WorkflowState {
        if (by != PerspectiveId.GENERAL_MANAGER) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "only the general manager may advance a phase")
        }
        if (state.status == WorkflowStatus.TERMINATED || state.status == WorkflowStatus.COMPLETE) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "workflow is ${state.status.wire}")
        }
        if (state.status == WorkflowStatus.BLOCKED) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_PHASE,
                "workflow is blocked: ${state.blockedReason ?: "no reason"}",
            )
        }
        val gate = PhaseGates.getValue(state.phase)
        val missingSteps = PhaseSteps.getValue(state.phase)
            .filter { !it.optional && !state.completedSteps.contains(it.id) }
            .map { it.id }
        if (missingSteps.isNotEmpty()) {
            throw GovernanceError(GovernanceCode.WORKFLOW_STEP, "incomplete steps: ${missingSteps.joinToString(", ")}")
        }
        val missingApprovals = gate.approvals.filter { !hasApproval(it) }
        if (missingApprovals.isNotEmpty()) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "missing approvals: ${missingApprovals.joinToString(", ") { it.wire }}",
            )
        }
        val missingArtifacts = gate.artifacts.filter { !hasArtifact(it) }
        if (missingArtifacts.isNotEmpty()) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_ARTIFACT,
                "missing artifacts: ${missingArtifacts.joinToString(", ")}",
            )
        }
        if (state.phase == PhaseId.PREPARATION) {
            val credential = state.credential
            val expires = credential?.let { parseInstantOrNull(it.expires) }
            val atInstant = parseInstantOrNull(at)
            if (credential == null ||
                credential.digest.isEmpty() ||
                !RequiredSignoffs.all { role -> credential.signoffs.contains(role) } ||
                expires == null ||
                atInstant == null ||
                !expires.isAfter(atInstant)
            ) {
                throw GovernanceError(
                    GovernanceCode.WORKFLOW_ARTIFACT,
                    "the switch credential must be issued, triple-signed and unexpired before execution",
                )
            }
        }
        val escalation = state.escalation
        if (state.phase == PhaseId.ESCALATION && escalation != null) {
            val subject = EscalationApproval.getValue(escalation.type)
            if (!hasApproval(subject)) {
                throw GovernanceError(
                    GovernanceCode.APPROVAL_REQUIRED,
                    "escalation type ${escalation.type.wire} needs ${subject.wire}",
                )
            }
        }
        val from = state.phase
        // 归档是最后一个阶段：参考实现的 `PhaseOrder[index + 1]` 会给出 undefined
        // 并静默损坏状态，这里改为显式失败；退出路径是 finish()。
        val nextIndex = PhaseId.order.indexOf(from) + 1
        if (nextIndex >= PhaseId.order.size) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_PHASE,
                "the archive phase has no next phase; use finish()",
            )
        }
        val next = PhaseId.order[nextIndex]
        state = state.copy(
            phase = next,
            history = state.history + PhaseTransition(from = from, to = next, by = by, at = at, note = gate.note),
        )
        return cloneState(state)
    }

    /**
     * Enter the escalation branch from any phase except archive.
     */
    fun escalate(input: EscalateInput): WorkflowState {
        if (input.by != PerspectiveId.GENERAL_MANAGER && input.by != PerspectiveId.LEGAL) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_PHASE,
                "only the general manager or legal may escalate",
            )
        }
        if (state.phase == PhaseId.ESCALATION || state.phase == PhaseId.ARCHIVE) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "cannot escalate from ${state.phase.wire}")
        }
        if (input.evidenceRefs <= 0) {
            throw GovernanceError(GovernanceCode.WORKFLOW_ARTIFACT, "an escalation must cite evidence")
        }
        val returnPhase = state.phase
        state = state.copy(
            escalation = EscalationRecord(
                type = input.type,
                reason = input.reason,
                evidenceRefs = input.evidenceRefs,
                by = input.by,
                at = input.at,
                target = escalationTarget(input.type),
                returnPhase = returnPhase,
            ),
            phase = PhaseId.ESCALATION,
            history = state.history + PhaseTransition(
                from = returnPhase,
                to = PhaseId.ESCALATION,
                by = input.by,
                at = input.at,
                note = "${input.type.wire}: ${input.reason}",
            ),
        )
        return cloneState(state)
    }

    /**
     * Leave the escalation branch for the phase that asked for it.
     */
    fun resume(by: PerspectiveId, at: Timestamp): WorkflowState {
        val escalation = state.escalation
        if (state.phase != PhaseId.ESCALATION || escalation == null) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "not in the escalation branch")
        }
        if (by != PerspectiveId.GENERAL_MANAGER) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "only the general manager may resume")
        }
        val target = escalation.returnPhase
        state = state.copy(
            phase = target,
            history = state.history + PhaseTransition(
                from = PhaseId.ESCALATION,
                to = target,
                by = by,
                at = at,
                note = "escalation resolved",
            ),
        )
        return cloneState(state)
    }

    /**
     * Attach the manifest reference the execution phase uses. The credential is
     * bound only through [attachCredential].
     */
    fun bind(manifest: ManifestRef?): WorkflowState {
        if (manifest != null) {
            state = state.copy(manifest = manifest)
        }
        return cloneState(state)
    }

    fun block(reason: String): WorkflowState {
        state = state.copy(status = WorkflowStatus.BLOCKED, blockedReason = reason)
        return cloneState(state)
    }

    fun unblock(): WorkflowState {
        state = state.copy(status = WorkflowStatus.OPEN, blockedReason = null)
        return cloneState(state)
    }

    fun terminate(at: Timestamp, reason: String): WorkflowState {
        state = state.copy(
            status = WorkflowStatus.TERMINATED,
            history = state.history + PhaseTransition(
                from = state.phase,
                to = state.phase,
                by = PerspectiveId.GENERAL_MANAGER,
                at = at,
                note = "terminated: $reason",
            ),
        )
        return cloneState(state)
    }

    fun finish(at: Timestamp): WorkflowState {
        if (state.phase != PhaseId.ARCHIVE) {
            throw GovernanceError(GovernanceCode.WORKFLOW_PHASE, "only the archive phase can finish a task")
        }
        val problems = archiveGateProblems()
        if (problems.isNotEmpty()) {
            throw GovernanceError(
                GovernanceCode.WORKFLOW_ARTIFACT,
                "the archive gate is not satisfied: ${problems.joinToString("; ")}",
            )
        }
        state = state.copy(
            status = WorkflowStatus.COMPLETE,
            history = state.history + PhaseTransition(
                from = PhaseId.ARCHIVE,
                to = PhaseId.ARCHIVE,
                by = PerspectiveId.GENERAL_MANAGER,
                at = at,
                note = "complete",
            ),
        )
        return cloneState(state)
    }

    fun hasArtifact(kind: String): Boolean = state.artifacts.any { it.kind == kind }

    /**
     * Invariant report used by checks and by the archive phase.
     */
    fun verify(): List<String> {
        val problems = ArrayList<String>()
        if (state.phase != PhaseId.ESCALATION && state.phase != PhaseId.ARCHIVE && state.status == WorkflowStatus.COMPLETE) {
            problems += "a workflow cannot be complete before archive"
        }
        state.approvals.forEach { approval ->
            if (approval.by == approval.requestedBy) {
                problems += "${approval.id} was self-approved"
            }
            if (!canApprove(approval.by, approval.subject)) {
                problems += "${approval.id} approved by an unauthorised perspective"
            }
        }
        if (state.phase == PhaseId.EXECUTION && state.credential == null) {
            problems += "execution has no bound credential"
        }
        state.credential?.let { credential ->
            if (credential.digest.isEmpty()) {
                problems += "the bound credential has no digest"
            }
            if (!RequiredSignoffs.all { role -> credential.signoffs.contains(role) }) {
                problems += "the switch credential is not triple-signed"
            }
        }
        if (state.phase == PhaseId.ARCHIVE || state.status == WorkflowStatus.COMPLETE) {
            problems += archiveGateProblems()
        }
        return problems
    }

    private fun archiveGateProblems(): List<String> {
        val gate = PhaseGates.getValue(PhaseId.ARCHIVE)
        val problems = ArrayList<String>()
        val missingSteps = PhaseSteps.getValue(PhaseId.ARCHIVE)
            .filter { !it.optional && !state.completedSteps.contains(it.id) }
            .map { it.id }
        if (missingSteps.isNotEmpty()) {
            problems += "incomplete archive steps: ${missingSteps.joinToString(", ")}"
        }
        val missingApprovals = gate.approvals.filter { !hasApproval(it) }
        if (missingApprovals.isNotEmpty()) {
            problems += "missing archive approvals: ${missingApprovals.joinToString(", ") { it.wire }}"
        }
        val missingArtifacts = gate.artifacts.filter { !hasArtifact(it) }
        if (missingArtifacts.isNotEmpty()) {
            problems += "missing archive artefacts: ${missingArtifacts.joinToString(", ")}"
        }
        return problems
    }

    private fun advanceCounter() {
        counter += 1
    }

    private fun artifactId(): Id = "art.$counter"

    private fun approvalId(): Id = "apv.$counter"
}

private fun parseInstantOrNull(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

/** 防御性拷贝：调用方拿到的列表与内部状态不共享。 */
private fun cloneState(state: WorkflowState): WorkflowState = state.copy(
    completedSteps = state.completedSteps.toList(),
    artifacts = state.artifacts.map { artifact ->
        artifact.copy(signoffs = artifact.signoffs?.toList())
    },
    approvals = state.approvals.map { it.copy() },
    manifest = state.manifest?.copy(),
    credential = state.credential?.copy(signoffs = state.credential.signoffs.toList()),
    escalation = state.escalation?.copy(),
    history = state.history.map { it.copy() },
)
