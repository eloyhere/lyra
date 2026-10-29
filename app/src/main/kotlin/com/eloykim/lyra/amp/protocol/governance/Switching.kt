package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * Perspective and mode switching orchestration (spec section 12), a faithful
 * port of `src/governance/switching.ts`.
 *
 * The primitives already exist: credentials, manifests, context layers and the
 * budget. This module sequences them into the flows the description names:
 * peer switch, downgrade, mode switch and scenario switch. It also builds the
 * escalation plan, including the independent strategic context that must not
 * inherit the execution reasoning chain.
 *
 * 本文件不复制清单或凭据逻辑：签发走 `issueCredential`，复核走
 * `verifyCredential`，清单更新走 `updateAttentionManifest`，场景切换走
 * `rederiveAttentionManifest`（版本递增、`parent` 记录上一版摘要），与参考实现
 * 的调用关系一一对应。
 */

/**
 * The three-party approval set for a switch. HR, legal and finance each own one
 * subject; the general manager issues.
 */
data class SwitchApprovals(
    val generalManager: ApprovalGrant,
    val legal: ApprovalGrant,
    val finance: ApprovalGrant,
    val humanResources: ApprovalGrant,
)

/** Input for a same-level peer switch. */
data class PeerSwitchInput(
    /** The perspective that asked for the switch. */
    val requester: PerspectiveId,
    val to: PerspectiveId,
    val reason: String,
    val scenario: ScenarioId,
    val targetManifest: AttentionManifest,
    val visibleScope: List<Ref>,
    val payloadRefs: List<Ref> = emptyList(),
    val quota: Budget,
    val elasticity: Elasticity,
    val constraints: BehaviorConstraints,
    val issuedAt: Timestamp,
    val ttlMs: Long? = null,
    val at: Timestamp,
    val modes: Map<ModeId, Double>? = null,
    val approvals: SwitchApprovals,
    val signers: List<SignerInput>,
)

data class PeerSwitchResult(
    val credential: SwitchCredential,
    val verdict: CredentialVerdict,
    val target: AttentionManifest,
    val steps: List<String>,
)

/** Input for a downgrade from a strategic layer to an execution perspective. */
data class DowngradeInput(
    val requester: PerspectiveId,
    /** The strategic perspective being left behind (board or supervisory). */
    val from: PerspectiveId,
    val to: PerspectiveId,
    val reason: String,
    val targetManifest: AttentionManifest,
    val visibleScope: List<Ref>,
    val quota: Budget,
    val elasticity: Elasticity,
    val constraints: BehaviorConstraints,
    val issuedAt: Timestamp,
    val ttlMs: Long? = null,
    val at: Timestamp,
    val approvals: SwitchApprovals,
    val signers: List<SignerInput>,
)

private fun requireSlot(
    approval: ApprovalGrant,
    subject: ApprovalSubject,
    department: PerspectiveId,
    requester: PerspectiveId,
    code: GovernanceCode,
) {
    if (!approval.granted) {
        throw GovernanceError(code, "${subject.wire} was not granted")
    }
    if (approval.subject != subject) {
        throw GovernanceError(code, "${subject.wire} approval carries subject ${approval.subject.wire}")
    }
    if (approval.by != department || !canApprove(department, approval.subject)) {
        throw GovernanceError(code, "${subject.wire} must be approved by ${department.wire}")
    }
    if (approval.by == requester) {
        throw GovernanceError(GovernanceCode.APPROVAL_SELF, "${requester.wire} cannot approve its own switch")
    }
}

/**
 * Peer switch: HR confirms the target, legal clears the scope, finance freezes
 * the quota, the general manager issues the credential, and the target loads
 * its own manifest after the credential verifies.
 */
fun planPeerSwitch(input: PeerSwitchInput): PeerSwitchResult {
    val steps = ArrayList<String>()
    if (input.requester == input.to) {
        throw GovernanceError(GovernanceCode.CREDENTIAL_TARGET, "a perspective cannot switch to itself")
    }
    if (perspective(input.to).layer != PerspectiveLayer.EXECUTION || requiresEscalation(input.to)) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "${input.to.wire} is not a same-level execution target",
        )
    }
    if (input.targetManifest.perspective != input.to) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "manifest belongs to ${input.targetManifest.perspective.wire}, not ${input.to.wire}",
        )
    }
    if (input.targetManifest.scenario != input.scenario) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "target manifest scenario is ${input.targetManifest.scenario.wire}",
        )
    }
    requireSlot(
        input.approvals.humanResources,
        ApprovalSubject.PERSPECTIVE_ASSIGNMENT,
        PerspectiveId.HUMAN_RESOURCES,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    steps += "human-resources confirms the target perspective"
    requireSlot(
        input.approvals.legal,
        ApprovalSubject.CREDENTIAL_COMPLIANCE,
        PerspectiveId.LEGAL,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    steps += "legal clears the credential scope"
    requireSlot(
        input.approvals.finance,
        ApprovalSubject.BUDGET_ELASTICITY,
        PerspectiveId.FINANCE,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    steps += "finance freezes the quota and elasticity"
    requireSlot(
        input.approvals.generalManager,
        ApprovalSubject.CREDENTIAL_ISSUE,
        PerspectiveId.GENERAL_MANAGER,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    steps += "general manager issues the credential"
    val targetVerdict = verifyAttentionManifest(input.targetManifest, input.at)
    if (!targetVerdict.ok) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_UNSIGNED,
            targetVerdict.detail ?: "target manifest is not valid",
        )
    }
    val manifestRef = ManifestRef(input.targetManifest.id, input.targetManifest.version)
    val credential = issueCredential(
        CredentialDraft(
            from = PerspectiveId.GENERAL_MANAGER,
            to = input.to,
            reason = input.reason,
            visibleScope = input.visibleScope,
            payloadRefs = input.payloadRefs,
            budget = CredentialBudget(quota = input.quota, elasticity = input.elasticity),
            constraints = input.constraints,
            manifest = manifestRef,
            scenario = input.scenario,
            modes = input.modes,
            issuedAt = input.issuedAt,
            ttlMs = input.ttlMs,
        ),
        input.signers,
    )
    val verdict = verifyCredential(
        credential,
        VerifyCredentialRequest(
            at = input.at,
            to = input.to,
            manifest = manifestRef,
            readRefs = input.payloadRefs,
        ),
    )
    if (!verdict.valid) {
        throw GovernanceError(GovernanceCode.CREDENTIAL_SCOPE, verdict.detail)
    }
    steps += "credential verifies; previous perspective's private context seals"
    loadAttentionManifest(
        input.targetManifest,
        LoadManifestRequest(perspective = input.to, at = input.at, scenario = input.scenario),
    )
    steps += "target loads its manifest; previous budget settles"
    return PeerSwitchResult(
        credential = credential,
        verdict = verdict,
        target = input.targetManifest,
        steps = steps,
    )
}

/**
 * Downgrade: the strategic layer returns authority to an execution perspective.
 * Finance approves the budget recovery, legal approves the post-downgrade
 * behaviour constraints, HR confirms the target is available.
 */
fun planDowngrade(input: DowngradeInput): PeerSwitchResult {
    val steps = ArrayList<String>()
    if (!requiresEscalation(input.from)) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "${input.from.wire} is not a strategic perspective",
        )
    }
    if (perspective(input.to).layer != PerspectiveLayer.EXECUTION) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "${input.to.wire} is not an execution target",
        )
    }
    if (input.targetManifest.perspective != input.to) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "manifest belongs to ${input.targetManifest.perspective.wire}, not ${input.to.wire}",
        )
    }
    requireSlot(
        input.approvals.humanResources,
        ApprovalSubject.PERSPECTIVE_READINESS,
        PerspectiveId.HUMAN_RESOURCES,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    requireSlot(
        input.approvals.legal,
        ApprovalSubject.CREDENTIAL_COMPLIANCE,
        PerspectiveId.LEGAL,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    requireSlot(
        input.approvals.finance,
        ApprovalSubject.BUDGET_SETTLEMENT,
        PerspectiveId.FINANCE,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    requireSlot(
        input.approvals.generalManager,
        ApprovalSubject.CREDENTIAL_ISSUE,
        PerspectiveId.GENERAL_MANAGER,
        input.requester,
        GovernanceCode.CREDENTIAL_INVALID,
    )
    steps += "downgrade conditions judged"
    steps += "human resources approves availability"
    steps += "legal approves post-downgrade constraints"
    steps += "finance reclaims and refreezes the budget"
    val manifestRef = ManifestRef(input.targetManifest.id, input.targetManifest.version)
    val credential = issueCredential(
        CredentialDraft(
            from = PerspectiveId.GENERAL_MANAGER,
            to = input.to,
            reason = input.reason,
            visibleScope = input.visibleScope,
            payloadRefs = emptyList(),
            budget = CredentialBudget(quota = input.quota, elasticity = input.elasticity),
            constraints = input.constraints,
            manifest = manifestRef,
            scenario = input.targetManifest.scenario,
            modes = input.targetManifest.modes.asModeMap(),
            issuedAt = input.issuedAt,
            ttlMs = input.ttlMs,
        ),
        input.signers,
    )
    val verdict = verifyCredential(
        credential,
        VerifyCredentialRequest(at = input.at, to = input.to, manifest = manifestRef),
    )
    if (!verdict.valid) {
        throw GovernanceError(GovernanceCode.CREDENTIAL_SCOPE, verdict.detail)
    }
    steps += "general manager issues the downgrade credential; strategic context seals"
    return PeerSwitchResult(
        credential = credential,
        verdict = verdict,
        target = input.targetManifest,
        steps = steps,
    )
}

/** Optional approvals for a mode switch. */
data class ModeSwitchApprovals(
    val modeWeights: ApprovalGrant? = null,
    val finance: ApprovalGrant? = null,
    val legal: ApprovalGrant? = null,
)

data class ModeSwitchInput(
    val manifest: AttentionManifest,
    val modes: Map<ModeId, Double>,
    val by: PerspectiveId,
    val at: Timestamp,
    val signoff: SignerInput,
    val approvals: ModeSwitchApprovals? = null,
    val evidenceFloor: EvidenceFloor? = null,
    val budget: BudgetBinding? = null,
)

data class ModeSwitchResult(
    val manifest: AttentionManifest,
    val drift: Double,
    val approvalRequired: Boolean,
    val plan: ScenarioModePlan,
    val steps: List<String>,
)

/**
 * Mode switch: update the manifest's weights, floor and elasticity without
 * switching the perspective. Drift above 0.15 needs the general manager; an
 * elasticity change needs finance; a floor change needs legal.
 */
fun switchMode(input: ModeSwitchInput): ModeSwitchResult {
    val steps = ArrayList<String>()
    val next = normalizeModeWeights(input.manifest.modes.asModeMap() + input.modes)
    val drift = modeDrift(input.manifest.modes, next)
    val approvalRequired = driftRequiresApproval(input.manifest.modes, next)
    if (approvalRequired) {
        val approval = input.approvals?.modeWeights
        if (approval == null ||
            !approval.granted ||
            approval.subject != ApprovalSubject.MODE_WEIGHTS ||
            approval.by != PerspectiveId.GENERAL_MANAGER
        ) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "mode drift ${fixed2(drift)} exceeds 0.15",
            )
        }
        steps += "general manager approves the mode drift"
    }
    val plan = resolveScenarioModes(input.manifest.scenario, next.asModeMap())
    val elasticityChanged = plan.elasticity != input.manifest.elasticity
    if (elasticityChanged) {
        val approval = input.approvals?.finance
        if (approval == null ||
            !approval.granted ||
            approval.subject != ApprovalSubject.BUDGET_ELASTICITY ||
            approval.by != PerspectiveId.FINANCE
        ) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "an elasticity change needs a finance approval",
            )
        }
        steps += "finance approves the elasticity change"
    }
    val floorChanged = input.evidenceFloor != null && input.evidenceFloor != input.manifest.evidenceFloor
    if (floorChanged) {
        val approval = input.approvals?.legal
        if (approval == null ||
            !approval.granted ||
            approval.subject != ApprovalSubject.MANIFEST_EVIDENCE_FLOOR ||
            approval.by != PerspectiveId.LEGAL
        ) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "an evidence-floor change needs a legal approval",
            )
        }
        steps += "legal approves the evidence-floor change"
    }
    val budget = input.budget
        ?: if (elasticityChanged) input.manifest.budget.copy(elasticity = plan.elasticity) else null
    val updated = updateAttentionManifest(
        input.manifest,
        ManifestPatch(
            modes = next.asModeMap(),
            evidenceFloor = input.evidenceFloor,
            budget = budget,
        ),
        ManifestUpdateContext(
            by = input.by,
            at = input.at,
            signoff = input.signoff,
            approvals = ManifestUpdateApprovals(
                modeWeights = input.approvals?.modeWeights,
                budget = input.approvals?.finance,
                evidenceFloor = input.approvals?.legal,
            ),
        ),
    )
    steps += "manifest v${updated.version} keeps the perspective; weights updated"
    return ModeSwitchResult(
        manifest = updated,
        drift = drift,
        approvalRequired = approvalRequired,
        plan = plan,
        steps = steps,
    )
}

/** Optional approvals for a scenario switch. */
data class ScenarioSwitchApprovals(
    val scenario: ApprovalGrant,
    val board: ApprovalGrant? = null,
    val finance: ApprovalGrant? = null,
    val legal: ApprovalGrant? = null,
)

data class ScenarioSwitchInput(
    val manifest: AttentionManifest,
    val scenario: ScenarioId,
    val modes: Map<ModeId, Double>? = null,
    val at: Timestamp,
    val signers: List<SignerInput>,
    val approvals: ScenarioSwitchApprovals,
    /**
     * Extra re-derivation fields. `scenario` and `modes` inside the patch are
     * overwritten by the switch inputs, matching the reference's
     * `Omit<RederivePatch, "scenario" | "modes">`.
     */
    val patch: RederivePatch? = null,
    /** Set when the new scenario is entered through the escalation path. */
    val crossScenarioEscalation: Boolean = false,
)

data class ScenarioSwitchResult(
    val manifest: AttentionManifest,
    val plan: ScenarioModePlan,
    val steps: List<String>,
)

/**
 * Scenario switch: re-derive the manifest (version increments, parent digest
 * recorded) instead of patching weights. A cross-scenario escalation also needs
 * the board.
 */
fun switchScenario(input: ScenarioSwitchInput): ScenarioSwitchResult {
    val steps = ArrayList<String>()
    val scenarioApproval = input.approvals.scenario
    if (!scenarioApproval.granted ||
        scenarioApproval.subject != ApprovalSubject.SCENARIO_JUDGEMENT ||
        scenarioApproval.by != PerspectiveId.GENERAL_MANAGER
    ) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_REQUIRED,
            "a scenario switch needs the general manager's scenario.judgement approval",
        )
    }
    steps += "general manager approves the new scenario"
    if (input.crossScenarioEscalation) {
        val board = input.approvals.board
        if (board == null ||
            !board.granted ||
            board.subject != ApprovalSubject.STRATEGY_VERSION ||
            board.by != PerspectiveId.BOARD
        ) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "a cross-scenario escalation also needs the board",
            )
        }
        steps += "board approves the cross-scenario escalation"
    }
    val patch = input.patch
    if (patch?.budget != null && input.approvals.finance == null) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_REQUIRED,
            "a budget change in a scenario switch needs finance",
        )
    }
    if (patch?.constraints != null && input.approvals.legal == null) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_REQUIRED,
            "a constraint change in a scenario switch needs legal",
        )
    }
    val rederive = (patch ?: RederivePatch()).copy(
        scenario = input.scenario,
        modes = input.modes,
    )
    val manifest = rederiveAttentionManifest(input.manifest, rederive, input.signers, input.at)
    val plan = resolveScenarioModes(manifest.scenario, manifest.modes.asModeMap())
    steps += "scenario ${input.manifest.scenario.wire} -> ${input.scenario.wire}; " +
        "manifest v${manifest.version} re-derived"
    return ScenarioSwitchResult(manifest = manifest, plan = plan, steps = steps)
}

// ---------------------------------------------------------------------------
// 升级计划
// ---------------------------------------------------------------------------

/** The approvals an escalation needs before it can run. */
data class EscalationApprovals(
    val initiate: ApprovalGrant,
    val reserve: ApprovalGrant,
    val readiness: ApprovalGrant,
    val authorization: ApprovalGrant,
)

data class EscalationPlanInput(
    val from: PerspectiveId,
    val kind: EscalationType,
    val reason: String,
    val evidence: List<Ref>,
    val at: Timestamp,
    val goal: String,
    val strategySummary: String,
    val approvals: EscalationApprovals,
)

/**
 * What the strategic layer receives: the global core's goal, the strategy
 * summary, evidence refs and the request. Never the execution reasoning chain.
 */
data class IndependentContext(
    val goal: String,
    val strategySummary: String,
    val evidence: List<Ref>,
    val request: String,
    /** Always false: the strategic layer does not inherit the execution chain. */
    val inheritsPrivateReasoning: Boolean = false,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "goal" to jsonText(goal),
        "strategySummary" to jsonText(strategySummary),
        "evidence" to jsonArr(*evidence.map { it.toJson() }.toTypedArray()),
        "request" to jsonText(request),
        "inheritsPrivateReasoning" to JsonValue.Bool(inheritsPrivateReasoning),
    )
}

data class EscalationPlan(
    val target: PerspectiveId,
    val independent: IndependentContext,
    val approvals: List<ApprovalGrant>,
    val authorization: String,
    val steps: List<String>,
)

/**
 * Escalation plan: the finance reserve and HR readiness gates are the same for
 * every type; the type decides who signs the authorisation. The strategic layer
 * receives global core, evidence refs and the request summary only.
 */
fun planEscalation(input: EscalationPlanInput): EscalationPlan {
    val steps = ArrayList<String>()
    val target = escalationTarget(input.kind)
    if (input.evidence.isEmpty()) {
        throw GovernanceError(GovernanceCode.APPROVAL_REQUIRED, "an escalation must cite evidence")
    }
    if (!input.approvals.initiate.granted ||
        input.approvals.initiate.subject != ApprovalSubject.ESCALATION_INITIATE ||
        input.approvals.initiate.by != PerspectiveId.GENERAL_MANAGER
    ) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_REQUIRED,
            "escalation.initiate needs the general manager",
        )
    }
    steps += "general manager initiates the escalation"
    if (!input.approvals.reserve.granted ||
        input.approvals.reserve.subject != ApprovalSubject.BUDGET_RESERVE ||
        input.approvals.reserve.by != PerspectiveId.FINANCE
    ) {
        throw GovernanceError(GovernanceCode.APPROVAL_REQUIRED, "the reserve draw needs finance")
    }
    steps += "finance approves the reserve draw"
    if (!input.approvals.readiness.granted ||
        input.approvals.readiness.subject != ApprovalSubject.PERSPECTIVE_READINESS ||
        input.approvals.readiness.by != PerspectiveId.HUMAN_RESOURCES
    ) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_REQUIRED,
            "strategic readiness needs human resources",
        )
    }
    steps += "human resources confirms the strategic perspective is ready"
    val authorizer = when (input.kind) {
        EscalationType.AUDIT -> PerspectiveId.GENERAL_MANAGER
        EscalationType.STRATEGY -> PerspectiveId.BOARD
        EscalationType.FINAL -> PerspectiveId.SHAREHOLDERS
        EscalationType.COMPLIANCE -> PerspectiveId.LEGAL
    }
    val subject = when (input.kind) {
        EscalationType.AUDIT -> ApprovalSubject.ESCALATION_AUDIT
        EscalationType.STRATEGY -> ApprovalSubject.ESCALATION_STRATEGIC
        EscalationType.FINAL -> ApprovalSubject.ESCALATION_FINAL
        EscalationType.COMPLIANCE -> ApprovalSubject.CREDENTIAL_COMPLIANCE
    }
    val authorization = input.approvals.authorization
    if (!authorization.granted ||
        authorization.by != authorizer ||
        authorization.subject != subject ||
        !canApprove(authorizer, subject)
    ) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_REQUIRED,
            "${input.kind.wire} escalation must be authorised by ${authorizer.wire} via ${subject.wire}",
        )
    }
    steps += "${authorizer.wire} authorises the ${input.kind.wire} escalation"
    val independent = IndependentContext(
        goal = input.goal,
        strategySummary = input.strategySummary,
        evidence = input.evidence.map { it.copy() },
        request = input.reason,
        inheritsPrivateReasoning = false,
    )
    steps += "strategic layer opens an independent context for ${target.wire}; execution chain stays sealed"
    return EscalationPlan(
        target = target,
        independent = independent,
        approvals = listOf(
            input.approvals.initiate,
            input.approvals.reserve,
            input.approvals.readiness,
            input.approvals.authorization,
        ),
        authorization = input.approvals.authorization.id,
        steps = steps,
    )
}
