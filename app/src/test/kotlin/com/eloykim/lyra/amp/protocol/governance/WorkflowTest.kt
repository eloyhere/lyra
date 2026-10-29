package com.eloykim.lyra.amp.protocol.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowTest {

    private fun started(): Workflow =
        Workflow.start(StartInput(id = "wf.1", scenario = ScenarioId.CHAT, at = AT_ISSUE))

    @Test
    fun `开始进入受理阶段并记录创建`() {
        val workflow = started()
        val state = workflow.current()
        assertEquals(PhaseId.INTAKE, state.phase)
        assertEquals(WorkflowStatus.OPEN, state.status)
        assertEquals(ScenarioId.CHAT, state.scenario)
        assertEquals(1, state.history.size)
        assertNull(state.history[0].from)
        assertEquals("created", state.history[0].note)
        assertEquals(PerspectiveId.CUSTOMER_SERVICE, workflow.steps().first().owner)
    }

    @Test
    fun `空 id 无法开始`() {
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            Workflow.start(StartInput(id = "", scenario = ScenarioId.CHAT, at = AT_ISSUE))
        }
    }

    @Test
    fun `步骤只能由所有者完成一次`() {
        val workflow = started()
        assertGovernanceError(GovernanceCode.WORKFLOW_STEP) {
            workflow.complete("intake.requirement", PerspectiveId.MARKETING, AT_ISSUE)
        }
        assertGovernanceError(GovernanceCode.WORKFLOW_STEP) {
            workflow.complete("no.such.step", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
        }
        workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE, "澄清完成")
        assertGovernanceError(GovernanceCode.WORKFLOW_STEP) {
            workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
        }
        val artifact = workflow.current().artifacts.single()
        assertEquals("requirement-summary", artifact.kind)
        assertEquals("art.1", artifact.id)
        assertEquals("澄清完成", artifact.note)
    }

    @Test
    fun `审批不能由申请人自己给出`() {
        val workflow = started()
        assertGovernanceError(GovernanceCode.APPROVAL_SELF) {
            approve(workflow, ApprovalSubject.SCENARIO_JUDGEMENT, PerspectiveId.GENERAL_MANAGER, PerspectiveId.GENERAL_MANAGER)
        }
        assertGovernanceError(GovernanceCode.APPROVAL_NOT_PERMITTED) {
            approve(workflow, ApprovalSubject.SCENARIO_JUDGEMENT, PerspectiveId.MARKETING, PerspectiveId.LEGAL)
        }
        assertFalse(workflow.hasApproval(ApprovalSubject.SCENARIO_JUDGEMENT))
        approve(workflow, ApprovalSubject.SCENARIO_JUDGEMENT, PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL)
        assertTrue(workflow.hasApproval(ApprovalSubject.SCENARIO_JUDGEMENT))
    }

    @Test
    fun `推进要求步骤审批与产物齐备`() {
        val workflow = started()
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.advance(PerspectiveId.LEGAL, AT_ISSUE)
        }
        completeIntake(workflow)
        val state = workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        assertEquals(PhaseId.PLANNING, state.phase)
        assertEquals(PhaseId.INTAKE, state.history.last().from)
        assertEquals(PhaseId.PLANNING, state.history.last().to)
        assertEquals(
            "a confirmed requirement, an approved scenario and complexity, an initial budget and a strategy digest",
            state.history.last().note,
        )
        assertTrue(workflow.verify().isEmpty())
    }

    @Test
    fun `缺少审批时拒绝推进并列出缺失项`() {
        val workflow = started()
        workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
        workflow.complete("intake.complexity", PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        workflow.complete("intake.budget", PerspectiveId.FINANCE, AT_ISSUE)
        workflow.complete("intake.strategy", PerspectiveId.BOARD, AT_ISSUE)
        workflow.complete("intake.log", PerspectiveId.ADMINISTRATION, AT_ISSUE)
        val error = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        }
        assertTrue(error.message!!.contains("requirement.complete"))
        assertEquals(PhaseId.INTAKE, workflow.current().phase)
    }

    @Test
    fun `阻塞状态写入原因并阻止完成与推进`() {
        val workflow = started()
        val blocked = workflow.block("waiting for the user")
        assertEquals(WorkflowStatus.BLOCKED, blocked.status)
        assertEquals("waiting for the user", blocked.blockedReason)
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
        }
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        }
        val reopened = workflow.unblock()
        assertEquals(WorkflowStatus.OPEN, reopened.status)
        assertNull(reopened.blockedReason)
    }

    @Test
    fun `伪造凭据产物被拒绝`() {
        val workflow = started()
        completeIntake(workflow)
        workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        completePlanning(workflow)
        workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        assertGovernanceError(GovernanceCode.WORKFLOW_ARTIFACT) {
            workflow.attach(
                "preparation.credential",
                PerspectiveId.GENERAL_MANAGER,
                ArtifactDraft(kind = "switch-credential", at = AT_ISSUE),
            )
        }
        assertFalse(workflow.hasArtifact("switch-credential"))
    }

    @Test
    fun `凭据只能在准备阶段挂载并绑定三方签署`() {
        val workflow = started()
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.attachCredential(testCredential(), AT_ISSUE)
        }
        completeIntake(workflow)
        workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        completePlanning(workflow)
        workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        val state = workflow.attachCredential(testCredential(), AT_ISSUE)
        assertNotNull(state.credential)
        assertEquals(
            listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE),
            state.credential!!.signoffs,
        )
        assertEquals("switch-credential", state.artifacts.last().kind)
    }

    @Test
    fun `过期凭据阻止进入执行`() {
        val workflow = started()
        completeIntake(workflow)
        workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        completePlanning(workflow)
        workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        val shortLived = testCredential(ttlMs = 60_000)
        completePreparation(workflow, shortLived)
        val error = assertThrowsGovernance(GovernanceCode.WORKFLOW_ARTIFACT) {
            workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        }
        assertTrue(error.message!!.contains("triple-signed and unexpired"))
        assertEquals(PhaseId.PREPARATION, workflow.current().phase)
    }

    @Test
    fun `执行阶段绑定凭据且审计可见`() {
        val workflow = workflowAtExecution()
        val state = workflow.current()
        assertEquals(PhaseId.EXECUTION, state.phase)
        assertNotNull(state.credential)
        assertTrue(workflow.verify().none { it.contains("credential") })
        workflow.complete("execution.tools", PerspectiveId.TECHNOLOGY, AT_TEN_MIN)
        assertTrue(workflow.hasArtifact("execution-receipt"))
    }

    @Test
    fun `升级分支带证据并可返回原阶段`() {
        val workflow = workflowAtExecution()
        assertGovernanceError(GovernanceCode.WORKFLOW_ARTIFACT) {
            workflow.escalate(
                EscalateInput(
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_TEN_MIN,
                    type = EscalationType.AUDIT,
                    reason = "evidence conflict",
                    evidenceRefs = 0,
                ),
            )
        }
        val escalated = workflow.escalate(
            EscalateInput(
                by = PerspectiveId.GENERAL_MANAGER,
                at = AT_TEN_MIN,
                type = EscalationType.AUDIT,
                reason = "evidence conflict",
                evidenceRefs = 2,
            ),
        )
        assertEquals(PhaseId.ESCALATION, escalated.phase)
        assertEquals(PerspectiveId.SUPERVISORY, escalated.escalation!!.target)
        assertEquals(PhaseId.EXECUTION, escalated.escalation!!.returnPhase)
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.escalate(
                EscalateInput(
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_TEN_MIN,
                    type = EscalationType.FINAL,
                    reason = "again",
                    evidenceRefs = 1,
                ),
            )
        }
        workflow.complete("escalation.initiate", PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        workflow.complete("escalation.reserve", PerspectiveId.FINANCE, AT_TEN_MIN)
        workflow.complete("escalation.readiness", PerspectiveId.HUMAN_RESOURCES, AT_TEN_MIN)
        workflow.complete("escalation.authorize", PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        workflow.complete("escalation.arbitrate", PerspectiveId.SUPERVISORY, AT_TEN_MIN)
        workflow.complete("escalation.apply", PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        approve(workflow, ApprovalSubject.ESCALATION_INITIATE, PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL, AT_TEN_MIN)
        approve(workflow, ApprovalSubject.BUDGET_RESERVE, PerspectiveId.FINANCE, PerspectiveId.LEGAL, AT_TEN_MIN)
        approve(workflow, ApprovalSubject.PERSPECTIVE_READINESS, PerspectiveId.HUMAN_RESOURCES, PerspectiveId.LEGAL, AT_TEN_MIN)
        val missingTypeApproval = assertThrowsGovernance(GovernanceCode.APPROVAL_REQUIRED) {
            workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        }
        assertTrue(missingTypeApproval.message!!.contains("escalation.audit"))
        val resumed = workflow.resume(PerspectiveId.GENERAL_MANAGER, AT_TEN_MIN)
        assertEquals(PhaseId.EXECUTION, resumed.phase)
        assertEquals("escalation resolved", resumed.history.last().note)
    }

    @Test
    fun `只有总经理可以恢复升级分支`() {
        val workflow = workflowAtExecution()
        workflow.escalate(
            EscalateInput(
                by = PerspectiveId.LEGAL,
                at = AT_TEN_MIN,
                type = EscalationType.COMPLIANCE,
                reason = "policy check",
                evidenceRefs = 1,
            ),
        )
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.resume(PerspectiveId.LEGAL, AT_TEN_MIN)
        }
        assertEquals(PhaseId.ESCALATION, workflow.current().phase)
    }

    @Test
    fun `终止后不能再推进或完成`() {
        val workflow = started()
        val terminated = workflow.terminate(AT_ISSUE, "user cancelled")
        assertEquals(WorkflowStatus.TERMINATED, terminated.status)
        assertEquals("terminated: user cancelled", terminated.history.last().note)
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
        }
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.advance(PerspectiveId.GENERAL_MANAGER, AT_ISSUE)
        }
    }

    @Test
    fun `只有归档阶段可以结束`() {
        val workflow = workflowAtExecution()
        assertGovernanceError(GovernanceCode.WORKFLOW_PHASE) {
            workflow.finish(AT_TEN_MIN)
        }
    }

    @Test
    fun `校验报告标记自我审批与失效凭据`() {
        val state = WorkflowState(
            id = "wf.bad",
            scenario = ScenarioId.CHAT,
            modes = ModeWeights(0.5, 0.2, 0.2, 0.1),
            phase = PhaseId.EXECUTION,
            status = WorkflowStatus.OPEN,
            completedSteps = emptyList(),
            artifacts = emptyList(),
            approvals = listOf(
                WorkflowApproval(
                    id = "apv.1",
                    subject = ApprovalSubject.SCENARIO_JUDGEMENT,
                    by = PerspectiveId.GENERAL_MANAGER,
                    at = AT_ISSUE,
                    granted = true,
                    requestedBy = PerspectiveId.GENERAL_MANAGER,
                ),
            ),
            credential = BoundCredential(
                id = "crd.1",
                signoffs = listOf(SignoffRole.ISSUER),
                expires = AT_MANUAL_FUTURE,
                digest = "",
            ),
        )
        val problems = Workflow.of(state).verify()
        assertTrue(problems.any { it.contains("self-approved") })
        assertTrue(problems.any { it.contains("no bound credential") || it.contains("no digest") })
        assertTrue(problems.any { it.contains("not triple-signed") })
    }

    @Test
    fun `结束状态副本不共享可变结构`() {
        val workflow = started()
        val first = workflow.current()
        workflow.complete("intake.requirement", PerspectiveId.CUSTOMER_SERVICE, AT_ISSUE)
        assertEquals(0, first.artifacts.size)
        assertEquals(1, workflow.current().artifacts.size)
    }
}
