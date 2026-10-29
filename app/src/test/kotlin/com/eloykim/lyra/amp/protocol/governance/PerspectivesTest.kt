package com.eloykim.lyra.amp.protocol.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PerspectivesTest {

    @Test
    fun `注册表覆盖全部视角且自检通过`() {
        assertEquals(PerspectiveId.entries.size, PerspectiveRegistry.size)
        assertEquals(emptyList<String>(), validatePerspectiveRegistry())
        for (id in PerspectiveId.entries) {
            val definition = PerspectiveRegistry.getValue(id)
            assertEquals(id, definition.id)
            assertTrue(definition.labelEn.isNotBlank())
            assertTrue(definition.labelZh.isNotBlank())
            assertFalse(definition.frequency.isBlank())
        }
    }

    @Test
    fun `未知视角失败关闭`() {
        assertGovernanceError(GovernanceCode.UNKNOWN_PERSPECTIVE) { perspective("nope") }
        assertTrue(isKnownPerspective("legal"))
        assertFalse(isKnownPerspective("nope"))
    }

    @Test
    fun `批准主体按责任表归属`() {
        assertTrue(canApprove(PerspectiveId.LEGAL, ApprovalSubject.TOOL_HIGH_RISK))
        assertTrue(canApprove(PerspectiveId.LEGAL, ApprovalSubject.MANIFEST_EVIDENCE_FLOOR))
        assertTrue(canApprove(PerspectiveId.FINANCE, ApprovalSubject.BUDGET_RESERVE))
        assertTrue(canApprove(PerspectiveId.GENERAL_MANAGER, ApprovalSubject.MODE_WEIGHTS))
        assertTrue(canApprove(PerspectiveId.BOARD, ApprovalSubject.BOARD_WRITE))
        assertFalse(canApprove(PerspectiveId.GENERAL_MANAGER, ApprovalSubject.BUDGET_FREEZE))
        assertFalse(canApprove(PerspectiveId.OPERATIONS, ApprovalSubject.FINAL_RULING))

        assertEquals(listOf(PerspectiveId.FINANCE), approversOf(ApprovalSubject.BUDGET_RESERVE))
        assertEquals(
            listOf(PerspectiveId.BOARD, PerspectiveId.LEGAL),
            approversOf(ApprovalSubject.BOARD_WRITE),
        )
        assertEquals(
            listOf(PerspectiveId.SHAREHOLDERS),
            approversOf(ApprovalSubject.FINAL_RULING),
        )
    }

    @Test
    fun `层级判断`() {
        assertTrue(requiresEscalation(PerspectiveId.SHAREHOLDERS))
        assertTrue(requiresEscalation(PerspectiveId.BOARD))
        assertTrue(requiresEscalation(PerspectiveId.SUPERVISORY))
        assertFalse(requiresEscalation(PerspectiveId.LEGAL))
        assertTrue(isRuleModule(PerspectiveId.FINANCE))
        assertTrue(isRuleModule(PerspectiveId.HUMAN_RESOURCES))
        assertTrue(isRuleModule(PerspectiveId.ADMINISTRATION))
        assertFalse(isRuleModule(PerspectiveId.GENERAL_MANAGER))
    }

    @Test
    fun `同级切换凭证只能由总经理发给执行层`() {
        assertTrue(canIssueCredential(PerspectiveId.GENERAL_MANAGER, PerspectiveId.MARKETING))
        assertTrue(canIssueCredential(PerspectiveId.GENERAL_MANAGER, PerspectiveId.LEGAL))
        assertFalse(canIssueCredential(PerspectiveId.GENERAL_MANAGER, PerspectiveId.GENERAL_MANAGER))
        assertFalse(canIssueCredential(PerspectiveId.GENERAL_MANAGER, PerspectiveId.BOARD))
        assertFalse(canIssueCredential(PerspectiveId.GENERAL_MANAGER, PerspectiveId.FINANCE))
        assertFalse(canIssueCredential(PerspectiveId.LEGAL, PerspectiveId.MARKETING))
    }

    @Test
    fun `升级路径映射`() {
        assertEquals(PerspectiveId.SUPERVISORY, escalationTarget(EscalationType.AUDIT))
        assertEquals(PerspectiveId.BOARD, escalationTarget(EscalationType.STRATEGY))
        assertEquals(PerspectiveId.SHAREHOLDERS, escalationTarget(EscalationType.FINAL))
        assertEquals(PerspectiveId.LEGAL, escalationTarget(EscalationType.COMPLIANCE))
    }

    @Test
    fun `场景激活表`() {
        assertEquals(Activation.DEFAULT, activationFor(PerspectiveId.CUSTOMER_SERVICE, ScenarioId.CHAT))
        assertEquals(Activation.ON_DEMAND, activationFor(PerspectiveId.MARKETING, ScenarioId.CHAT))
        assertEquals(Activation.DORMANT, activationFor(PerspectiveId.TECHNOLOGY, ScenarioId.CHAT))

        val engineering = perspectivesForScenario(ScenarioId.ENGINEERING)
        assertEquals(
            listOf(PerspectiveId.CUSTOMER_SERVICE, PerspectiveId.TECHNOLOGY, PerspectiveId.OPERATIONS),
            engineering.default,
        )
        assertEquals(
            listOf(PerspectiveId.LEGAL, PerspectiveId.SUPERVISORY, PerspectiveId.STRATEGY),
            engineering.onDemand,
        )
        assertEquals(listOf(PerspectiveId.MARKETING), engineering.dormant)
        assertEquals(
            "Marketing is dormant but can be pulled in for user-facing impact review.",
            engineering.note,
        )
        assertFalse(perspectivesForScenario(ScenarioId.CHAT).note.isNullOrEmpty())

        // 计划里的列表是副本，调用方改不动表。
        assertNotSame(
            PerspectiveActivationTable.getValue(ScenarioId.CHAT).default,
            perspectivesForScenario(ScenarioId.CHAT).default,
        )
    }

    @Test
    fun `标准常驻视角清单`() {
        assertEquals(9, StandingPerspectives.size)
        assertTrue(StandingPerspectives.contains(PerspectiveId.GENERAL_MANAGER))
        assertFalse(StandingPerspectives.contains(PerspectiveId.WRITING))
    }
}
