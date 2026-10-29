package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind

/**
 * Fixtures shared by the workflow / audit / store / cooperation / switching
 * suites. They build real manifests and credentials through the manifest and
 * credential ports, so the tests exercise the same digest and sign-off paths as
 * production.
 */

/** The three sign-offs every version-1 manifest and credential needs. */
internal val TEST_SIGNERS: List<SignerInput> = listOf(
    SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_ISSUE),
    SignerInput(SignoffRole.LEGAL, PerspectiveId.LEGAL, AT_ISSUE),
    SignerInput(SignoffRole.FINANCE, PerspectiveId.FINANCE, AT_ISSUE),
)

internal fun assertThrowsGovernance(expected: GovernanceCode, block: () -> Unit): GovernanceError {
    var caught: GovernanceError? = null
    try {
        block()
    } catch (error: GovernanceError) {
        caught = error
    }
    if (caught == null) {
        throw AssertionError("期望 GovernanceError(${expected.wire})，但没有抛出")
    }
    if (caught.code != expected) {
        throw AssertionError("期望 ${expected.wire}，实际 ${caught.code.wire}: ${caught.message}")
    }
    return caught
}

/** One hour after [AT_ISSUE]; a safe "later" instant for expiry checks. */
internal const val AT_MANUAL_FUTURE: String = "2026-10-24T08:00:00Z"

internal fun testManifest(
    id: String = "amn.1",
    perspective: PerspectiveId = PerspectiveId.CUSTOMER_SERVICE,
    scenario: ScenarioId = ScenarioId.CHAT,
    ttlMs: Long? = null,
): AttentionManifest = createAttentionManifest(
    AttentionManifestDraft(
        id = id,
        perspective = perspective,
        scenario = scenario,
        focus = listOf(
            FocusItem(refOf(RefKind.GOAL, "g.1"), FocusCategory.GOAL, 0.9, 3),
            FocusItem(refOf(RefKind.EVIDENCE, "e.1"), FocusCategory.EVIDENCE, 0.7, 2),
        ),
        budget = BudgetBinding(
            scope = LedgerScope.PERSPECTIVE,
            perspective = perspective,
            cap = Budget(tokens = 100),
            elasticity = Elasticity.LOW,
        ),
        constraints = constraintsFor(scenario),
        derivation = ManifestDerivation(7, digestOfText("board-strategy"), AT_ISSUE),
        issuedAt = AT_ISSUE,
        ttlMs = ttlMs,
    ),
    TEST_SIGNERS,
)

internal fun testCredential(
    to: PerspectiveId = PerspectiveId.MARKETING,
    scenario: ScenarioId = ScenarioId.OFFICE,
    manifest: ManifestRef = ManifestRef("amn.1", 1),
    ttlMs: Long = 3_600_000,
): SwitchCredential = issueCredential(
    CredentialDraft(
        from = PerspectiveId.GENERAL_MANAGER,
        to = to,
        reason = "need a market view",
        visibleScope = listOf(refOf(RefKind.ENTITY, "e.1"), refOf(RefKind.CLAIM, "c.1")),
        payloadRefs = listOf(refOf(RefKind.CLAIM, "c.1")),
        budget = CredentialBudget(quota = Budget(tokens = 50), elasticity = Elasticity.MEDIUM),
        constraints = constraintsFor(scenario),
        manifest = manifest,
        scenario = scenario,
        issuedAt = AT_ISSUE,
        ttlMs = ttlMs,
    ),
    TEST_SIGNERS,
)

internal fun grant(
    subject: ApprovalSubject,
    by: PerspectiveId,
    requestedBy: PerspectiveId = PerspectiveId.CUSTOMER_SERVICE,
    at: String = AT_ISSUE,
    granted: Boolean = true,
): ApprovalGrant = ApprovalGrant(
    id = "apv.${subject.wire}.${by.wire}",
    subject = subject,
    by = by,
    at = at,
    granted = granted,
    note = "test grant for ${requestedBy.wire}",
)

internal fun approve(
    workflow: Workflow,
    subject: ApprovalSubject,
    by: PerspectiveId,
    requestedBy: PerspectiveId = PerspectiveId.CUSTOMER_SERVICE,
    at: String = AT_ISSUE,
): WorkflowState = workflow.approve(
    ApproveInput(subject = subject, by = by, at = at, requestedBy = requestedBy),
)

/** Complete the intake phase's mandatory steps and approvals. */
internal fun completeIntake(workflow: Workflow) {
    workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
    workflow.complete("intake.complexity", PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
    workflow.complete("intake.budget", PerspectiveId.FINANCE, AT_ISSUE)
    workflow.complete("intake.strategy", PerspectiveId.BOARD, AT_ISSUE)
    workflow.complete("intake.log", PerspectiveId.ADMINISTRATION, AT_ISSUE)
    approve(workflow, ApprovalSubject.REQUIREMENT_COMPLETE, PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.MARKETING)
    approve(workflow, ApprovalSubject.SCENARIO_JUDGEMENT, PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL)
    approve(workflow, ApprovalSubject.COMPLEXITY_GRADE, PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL)
    approve(workflow, ApprovalSubject.BUDGET_INITIAL, PerspectiveId.FINANCE, PerspectiveId.LEGAL)
}

/** Complete the planning phase's mandatory steps and approvals. */
internal fun completePlanning(workflow: Workflow) {
    workflow.complete("planning.route", PerspectiveId.STRATEGY, AT_ISSUE)
    workflow.complete("planning.staffing", PerspectiveId.HUMAN_RESOURCES, AT_ISSUE)
    workflow.complete("planning.budget", PerspectiveId.FINANCE, AT_ISSUE)
    workflow.complete("planning.constraints", PerspectiveId.LEGAL, AT_ISSUE)
    workflow.complete("planning.state", PerspectiveId.OPERATIONS, AT_ISSUE)
    workflow.complete("planning.manifest", PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
    approve(workflow, ApprovalSubject.MANIFEST_DERIVE, PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL)
    approve(workflow, ApprovalSubject.PERSPECTIVE_ROUTING, PerspectiveId.HUMAN_RESOURCES, PerspectiveId.LEGAL)
    approve(workflow, ApprovalSubject.BUDGET_ALLOCATION, PerspectiveId.FINANCE, PerspectiveId.LEGAL)
    approve(workflow, ApprovalSubject.MANIFEST_CONSTRAINTS, PerspectiveId.LEGAL, PerspectiveId.GENERAL_MANAGER)
}

/** Complete the preparation phase around a verified credential. */
internal fun completePreparation(
    workflow: Workflow,
    credential: SwitchCredential = testCredential(),
    at: String = AT_ISSUE,
) {
    workflow.attachCredential(credential, at)
    workflow.complete("preparation.compliance", PerspectiveId.LEGAL, at)
    workflow.complete("preparation.freeze", PerspectiveId.FINANCE, at)
    workflow.complete("preparation.readiness", PerspectiveId.HUMAN_RESOURCES, at)
    workflow.complete("preparation.context", PerspectiveId.ADMINISTRATION, at)
    workflow.complete("preparation.tools", PerspectiveId.TECHNOLOGY, at)
    workflow.complete("preparation.state", PerspectiveId.OPERATIONS, at)
    approve(workflow, ApprovalSubject.CREDENTIAL_ISSUE, PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL, at)
    approve(workflow, ApprovalSubject.CREDENTIAL_COMPLIANCE, PerspectiveId.LEGAL, PerspectiveId.GENERAL_MANAGER, at)
    approve(workflow, ApprovalSubject.BUDGET_FREEZE, PerspectiveId.FINANCE, PerspectiveId.LEGAL, at)
    approve(workflow, ApprovalSubject.PERSPECTIVE_READINESS, PerspectiveId.HUMAN_RESOURCES, PerspectiveId.LEGAL, at)
}

/** Drive a fresh workflow all the way to the execution phase. */
internal fun workflowAtExecution(credential: SwitchCredential = testCredential()): Workflow {
    val workflow = Workflow.start(StartInput(id = "wf.1", scenario = ScenarioId.CHAT, at = AT_ISSUE))
    completeIntake(workflow)
    workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
    completePlanning(workflow)
    workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
    completePreparation(workflow, credential)
    workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
    return workflow
}
