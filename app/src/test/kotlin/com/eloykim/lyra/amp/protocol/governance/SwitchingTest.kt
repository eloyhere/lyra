package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchingTest {

    private val marketingManifest = testManifest(
        id = "amn.marketing",
        perspective = PerspectiveId.MARKETING,
        scenario = ScenarioId.OFFICE,
    )

    private fun approvals(): SwitchApprovals = SwitchApprovals(
        generalManager = grant(ApprovalSubject.CREDENTIAL_ISSUE, PerspectiveId.GENERAL_MANAGER),
        legal = grant(ApprovalSubject.CREDENTIAL_COMPLIANCE, PerspectiveId.LEGAL),
        finance = grant(ApprovalSubject.BUDGET_ELASTICITY, PerspectiveId.FINANCE),
        humanResources = grant(ApprovalSubject.PERSPECTIVE_ASSIGNMENT, PerspectiveId.HUMAN_RESOURCES),
    )

    private fun peerInput(approvals: SwitchApprovals = approvals()): PeerSwitchInput = PeerSwitchInput(
        requester = PerspectiveId.CUSTOMER_SERVICE,
        to = PerspectiveId.MARKETING,
        reason = "need a market view",
        scenario = ScenarioId.OFFICE,
        targetManifest = marketingManifest,
        visibleScope = listOf(refOf(RefKind.ENTITY, "e.1"), refOf(RefKind.CLAIM, "c.1")),
        payloadRefs = listOf(refOf(RefKind.CLAIM, "c.1")),
        quota = Budget(tokens = 50),
        elasticity = Elasticity.MEDIUM,
        constraints = constraintsFor(ScenarioId.OFFICE),
        issuedAt = AT_ISSUE,
        at = AT_ISSUE,
        approvals = approvals,
        signers = TEST_SIGNERS,
    )

    @Test
    fun `同行切换按顺序走完三方审批并签发有效凭据`() {
        val result = planPeerSwitch(peerInput())
        assertTrue(result.verdict.valid)
        assertEquals(PerspectiveId.MARKETING, result.credential.to)
        assertEquals(PerspectiveId.GENERAL_MANAGER, result.credential.from)
        assertEquals(marketingManifest.id, result.credential.manifest.id)
        assertEquals(marketingManifest.version, result.credential.manifest.version)
        assertEquals(
            listOf(
                "human-resources confirms the target perspective",
                "legal clears the credential scope",
                "finance freezes the quota and elasticity",
                "general manager issues the credential",
                "credential verifies; previous perspective's private context seals",
                "target loads its manifest; previous budget settles",
            ),
            result.steps,
        )
        assertEquals(marketingManifest, result.target)
    }

    @Test
    fun `切换审批必须由对应部门给出`() {
        val wrongDepartment = approvals().copy(
            finance = grant(ApprovalSubject.BUDGET_ELASTICITY, PerspectiveId.LEGAL),
        )
        assertGovernanceError(GovernanceCode.CREDENTIAL_INVALID) {
            planPeerSwitch(peerInput(wrongDepartment))
        }
        val notGranted = approvals().copy(
            legal = grant(ApprovalSubject.CREDENTIAL_COMPLIANCE, PerspectiveId.LEGAL, granted = false),
        )
        assertGovernanceError(GovernanceCode.CREDENTIAL_INVALID) {
            planPeerSwitch(peerInput(notGranted))
        }
        val wrongSubject = approvals().copy(
            humanResources = grant(ApprovalSubject.PERSPECTIVE_READINESS, PerspectiveId.HUMAN_RESOURCES),
        )
        assertGovernanceError(GovernanceCode.CREDENTIAL_INVALID) {
            planPeerSwitch(peerInput(wrongSubject))
        }
    }

    @Test
    fun `申请人不能批准自己的切换`() {
        val selfApproval = SwitchApprovals(
            generalManager = grant(ApprovalSubject.CREDENTIAL_ISSUE, PerspectiveId.GENERAL_MANAGER),
            legal = grant(ApprovalSubject.CREDENTIAL_COMPLIANCE, PerspectiveId.LEGAL),
            finance = grant(ApprovalSubject.BUDGET_ELASTICITY, PerspectiveId.FINANCE),
            humanResources = grant(
                ApprovalSubject.PERSPECTIVE_ASSIGNMENT,
                PerspectiveId.HUMAN_RESOURCES,
                requestedBy = PerspectiveId.HUMAN_RESOURCES,
            ),
        )
        assertGovernanceError(GovernanceCode.APPROVAL_SELF) {
            planPeerSwitch(peerInput(selfApproval).copy(requester = PerspectiveId.HUMAN_RESOURCES))
        }
    }

    @Test
    fun `战略视角不能作为同行切换目标`() {
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            planPeerSwitch(
                peerInput().copy(
                    to = PerspectiveId.BOARD,
                    requester = PerspectiveId.GENERAL_MANAGER,
                ),
            )
        }
        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            planPeerSwitch(peerInput().copy(requester = PerspectiveId.MARKETING, to = PerspectiveId.MARKETING))
        }
        assertGovernanceError(GovernanceCode.MANIFEST_SCOPE) {
            planPeerSwitch(peerInput().copy(scenario = ScenarioId.CHAT))
        }
    }

    @Test
    fun `降级从战略层交回执行层`() {
        val result = planDowngrade(
            DowngradeInput(
                requester = PerspectiveId.CUSTOMER_SERVICE,
                from = PerspectiveId.SUPERVISORY,
                to = PerspectiveId.MARKETING,
                reason = "audit finished",
                targetManifest = marketingManifest,
                visibleScope = listOf(refOf(RefKind.ENTITY, "e.1")),
                quota = Budget(tokens = 40),
                elasticity = Elasticity.LOW,
                constraints = constraintsFor(ScenarioId.OFFICE),
                issuedAt = AT_ISSUE,
                at = AT_ISSUE,
                approvals = SwitchApprovals(
                    generalManager = grant(ApprovalSubject.CREDENTIAL_ISSUE, PerspectiveId.GENERAL_MANAGER),
                    legal = grant(ApprovalSubject.CREDENTIAL_COMPLIANCE, PerspectiveId.LEGAL),
                    finance = grant(ApprovalSubject.BUDGET_SETTLEMENT, PerspectiveId.FINANCE),
                    humanResources = grant(ApprovalSubject.PERSPECTIVE_READINESS, PerspectiveId.HUMAN_RESOURCES),
                ),
                signers = TEST_SIGNERS,
            ),
        )
        assertTrue(result.verdict.valid)
        assertEquals(PerspectiveId.MARKETING, result.credential.to)
        assertTrue(result.credential.payloadRefs.isEmpty())
        assertEquals(5, result.steps.size)
        assertTrue(result.steps.first() == "downgrade conditions judged")

        assertGovernanceError(GovernanceCode.CREDENTIAL_TARGET) {
            planDowngrade(
                DowngradeInput(
                    requester = PerspectiveId.CUSTOMER_SERVICE,
                    from = PerspectiveId.MARKETING,
                    to = PerspectiveId.MARKETING,
                    reason = "x",
                    targetManifest = marketingManifest,
                    visibleScope = emptyList(),
                    quota = Budget(tokens = 1),
                    elasticity = Elasticity.LOW,
                    constraints = constraintsFor(ScenarioId.OFFICE),
                    issuedAt = AT_ISSUE,
                    at = AT_ISSUE,
                    approvals = approvals(),
                    signers = TEST_SIGNERS,
                ),
            )
        }
    }

    @Test
    fun `模式漂移超过阈值需要总经理审批`() {
        val manifest = testManifest()
        val modes = mapOf(ModeId.DIVERGE to 0.8)
        val signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        val error = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            switchMode(
                ModeSwitchInput(
                    manifest = manifest,
                    modes = modes,
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_TEN_MIN,
                    signoff = signoff,
                ),
            )
        }
        assertTrue(error.message!!.contains("mode drift"))

        val updated = switchMode(
            ModeSwitchInput(
                manifest = manifest,
                modes = modes,
                by = PerspectiveId.GENERAL_MANAGER,
                at = AT_TEN_MIN,
                signoff = signoff,
                approvals = ModeSwitchApprovals(
                    modeWeights = grant(
                        ApprovalSubject.MODE_WEIGHTS,
                        PerspectiveId.GENERAL_MANAGER,
                        requestedBy = manifest.perspective,
                    ),
                ),
            ),
        )
        assertEquals(2, updated.manifest.version)
        assertTrue(updated.approvalRequired)
        assertTrue(updated.drift > 0.15)
        assertEquals(Elasticity.LOW, updated.manifest.elasticity)
        assertTrue(updated.steps.any { it.contains("general manager approves the mode drift") })
    }

    @Test
    fun `弹性与证据下限变更各自需要审批`() {
        val manifest = testManifest()
        val modes = mapOf(
            ModeId.AFFECT to 0.0,
            ModeId.THINK to 0.0,
            ModeId.DIVERGE to 1.0,
            ModeId.CONVERGE to 0.0,
        )
        val driftApproval = grant(
            ApprovalSubject.MODE_WEIGHTS,
            PerspectiveId.GENERAL_MANAGER,
            requestedBy = manifest.perspective,
        )
        val withoutFinance = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            switchMode(
                ModeSwitchInput(
                    manifest = manifest,
                    modes = modes,
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_TEN_MIN,
                    signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN),
                    approvals = ModeSwitchApprovals(modeWeights = driftApproval),
                ),
            )
        }
        assertTrue(withoutFinance.message!!.contains("elasticity"))

        val finance = grant(
            ApprovalSubject.BUDGET_ELASTICITY,
            PerspectiveId.FINANCE,
            requestedBy = manifest.perspective,
        )
        val updated = switchMode(
            ModeSwitchInput(
                manifest = manifest,
                modes = modes,
                by = PerspectiveId.GENERAL_MANAGER,
                at = AT_TEN_MIN,
                signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN),
                approvals = ModeSwitchApprovals(modeWeights = driftApproval, finance = finance),
            ),
        )
        assertEquals(2, updated.manifest.version)
        assertEquals(manifest.digest, updated.manifest.parent)
        assertEquals(Elasticity.MEDIUM, updated.manifest.elasticity)
        assertTrue(updated.steps.any { it.contains("finance approves the elasticity change") })

        val withoutLegal = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            switchMode(
                ModeSwitchInput(
                    manifest = manifest,
                    modes = modes,
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_TEN_MIN,
                    signoff = SignerInput(SignoffRole.ISSUER, PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN),
                    approvals = ModeSwitchApprovals(modeWeights = driftApproval, finance = finance),
                    evidenceFloor = EvidenceFloor.F1,
                ),
            )
        }
        assertTrue(withoutLegal.message!!.contains("evidence-floor"))
    }

    @Test
    fun `场景切换重新推导清单并记录父摘要`() {
        val manifest = testManifest()
        val scenarioApproval = grant(
            ApprovalSubject.SCENARIO_JUDGEMENT,
            PerspectiveId.GENERAL_MANAGER,
            requestedBy = manifest.perspective,
        )
        val crossError = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            switchScenario(
                ScenarioSwitchInput(
                    manifest = manifest,
                    scenario = ScenarioId.ENGINEERING,
                    at = AT_TEN_MIN,
                    signers = TEST_SIGNERS,
                    approvals = ScenarioSwitchApprovals(scenario = scenarioApproval),
                    crossScenarioEscalation = true,
                ),
            )
        }
        assertTrue(crossError.message!!.contains("board"))

        val switched = switchScenario(
            ScenarioSwitchInput(
                manifest = manifest,
                scenario = ScenarioId.ENGINEERING,
                at = AT_TEN_MIN,
                signers = TEST_SIGNERS,
                approvals = ScenarioSwitchApprovals(
                    scenario = scenarioApproval,
                    board = grant(ApprovalSubject.STRATEGY_VERSION, PerspectiveId.BOARD),
                ),
                // 工程场景的证据下限是 F1；从 CHAT 的 F3 切过去必须随补丁抬高，
                // 否则参考实现的矩阵校验会拒绝这次推导。
                patch = RederivePatch(evidenceFloor = EvidenceFloor.F1),
                crossScenarioEscalation = true,
            ),
        )
        assertEquals(ScenarioId.ENGINEERING, switched.manifest.scenario)
        assertEquals(2, switched.manifest.version)
        assertEquals(manifest.digest, switched.manifest.parent)
        assertEquals(ScenarioId.ENGINEERING, switched.plan.scenario)
        assertEquals(EvidenceFloor.F1, switched.manifest.evidenceFloor)
        assertTrue(switched.steps.last().contains("re-derived"))

        val budgetPatch = RederivePatch(budget = manifest.budget)
        val budgetError = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            switchScenario(
                ScenarioSwitchInput(
                    manifest = manifest,
                    scenario = ScenarioId.ENGINEERING,
                    at = AT_TEN_MIN,
                    signers = TEST_SIGNERS,
                    approvals = ScenarioSwitchApprovals(scenario = scenarioApproval),
                    patch = budgetPatch,
                ),
            )
        }
        assertTrue(budgetError.message!!.contains("finance"))
    }

    @Test
    fun `升级计划要求各类型对应的授权人`() {
        val approvals = EscalationApprovals(
            initiate = grant(ApprovalSubject.ESCALATION_INITIATE, PerspectiveId.GENERAL_MANAGER),
            reserve = grant(ApprovalSubject.BUDGET_RESERVE, PerspectiveId.FINANCE),
            readiness = grant(ApprovalSubject.PERSPECTIVE_READINESS, PerspectiveId.HUMAN_RESOURCES),
            authorization = grant(ApprovalSubject.ESCALATION_STRATEGIC, PerspectiveId.BOARD),
        )
        val plan = planEscalation(
            EscalationPlanInput(
                from = PerspectiveId.GENERAL_MANAGER,
                kind = EscalationType.STRATEGY,
                reason = "strategy conflict",
                evidence = listOf(refOf(RefKind.EVIDENCE, "e.1")),
                at = AT_TEN_MIN,
                goal = "ship v1",
                strategySummary = "hold the line",
                approvals = approvals,
            ),
        )
        assertEquals(PerspectiveId.BOARD, plan.target)
        assertFalse(plan.independent.inheritsPrivateReasoning)
        assertEquals("strategy conflict", plan.independent.request)
        assertEquals(approvals.authorization.id, plan.authorization)
        assertEquals(4, plan.approvals.size)
        assertTrue(plan.steps.any { it.contains("execution chain stays sealed") })

        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            planEscalation(
                EscalationPlanInput(
                    from = PerspectiveId.GENERAL_MANAGER,
                    kind = EscalationType.STRATEGY,
                    reason = "strategy conflict",
                    evidence = emptyList(),
                    at = AT_TEN_MIN,
                    goal = "ship v1",
                    strategySummary = "hold the line",
                    approvals = approvals,
                ),
            )
        }
        assertGovernanceError(GovernanceCode.APPROVAL_REQUIRED) {
            planEscalation(
                EscalationPlanInput(
                    from = PerspectiveId.GENERAL_MANAGER,
                    kind = EscalationType.FINAL,
                    reason = "final ruling",
                    evidence = listOf(refOf(RefKind.EVIDENCE, "e.1")),
                    at = AT_TEN_MIN,
                    goal = "ship v1",
                    strategySummary = "hold the line",
                    approvals = approvals.copy(
                        authorization = grant(ApprovalSubject.ESCALATION_FINAL, PerspectiveId.GENERAL_MANAGER),
                    ),
                ),
            )
        }
        assertNull(plan.independent.evidence.firstOrNull { it.id.isEmpty() })
    }
}
