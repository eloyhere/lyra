package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.module.ToolDescriptor
import com.eloykim.lyra.amp.protocol.module.ToolInvocation
import com.eloykim.lyra.amp.protocol.module.ToolRisk
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyTest {

    private fun invocation(): ToolInvocation = ToolInvocation("tool.patch", jsonObj())

    private fun descriptor(risk: ToolRisk?): ToolDescriptor = ToolDescriptor(
        name = "tool.patch",
        description = "patch a file",
        inputSchema = jsonObj(),
        risk = risk,
    )

    private fun policy(
        legal: LegalGate,
        manifest: AttentionManifest = testManifest(),
        evidenceTier: Tier? = null,
        approval: ApprovalGrant? = null,
    ): GovernedToolPolicy = GovernedToolPolicy(
        GovernedToolPolicyOptions(
            legal = legal,
            manifest = manifest,
            evidenceTier = evidenceTier,
            approval = approval,
        ),
    )

    @Test
    fun `只读工具默认放行`() {
        val decision = policy(LegalGate(now = testClock())).check(invocation(), descriptor(ToolRisk.READ))
        assertTrue(decision.allowed)
    }

    @Test
    fun `破坏性工具没有审批时在执行前被拒绝`() {
        val decision = policy(LegalGate(now = testClock()))
            .check(invocation(), descriptor(ToolRisk.DESTRUCTIVE))
        assertFalse(decision.allowed)
        assertTrue(decision.reason!!.contains("tool-unapproved"))
    }

    @Test
    fun `预先取得的 legal 审批允许高风险工具`() {
        val approval = ApprovalGrant(
            id = "apv.tool",
            subject = ApprovalSubject.TOOL_HIGH_RISK,
            by = PerspectiveId.LEGAL,
            at = AT_ISSUE,
            granted = true,
        )
        val decision = policy(LegalGate(now = testClock()), approval = approval)
            .check(invocation(), descriptor(ToolRisk.DESTRUCTIVE))
        assertTrue(decision.allowed)
    }

    @Test
    fun `断路器跳闸后所有工具被拒绝`() {
        val legal = LegalGate(now = testClock())
        legal.reportEmergency(
            PerspectiveId.TECHNOLOGY,
            "prompt injection in tool output",
            ViolationCode.INJECTION_DETECTED,
        )
        assertTrue(legal.isBlocked())
        val decision = policy(legal).check(invocation(), descriptor(ToolRisk.READ))
        assertFalse(decision.allowed)
        assertEquals("legal breaker is active", decision.reason)
    }

    @Test
    fun `被冻结的视角不能执行工具`() {
        val legal = LegalGate(now = testClock())
        legal.enforceTool(
            ToolCheckInput(
                perspective = PerspectiveId.CUSTOMER_SERVICE,
                scenario = ScenarioId.CHAT,
                action = "diagnose",
                effect = "read",
            ),
        )
        assertTrue(legal.isFrozen(PerspectiveId.CUSTOMER_SERVICE))
        val decision = policy(legal).check(invocation(), descriptor(ToolRisk.READ))
        assertFalse(decision.allowed)
        assertEquals("customer-service is frozen", decision.reason)
    }

    @Test
    fun `严格场景的证据下限阻止执行`() {
        val legal = LegalGate(now = testClock())
        val engineering = testManifest(scenario = ScenarioId.ENGINEERING)
        // ENGINEERING 的 F1 下限要求 T3；T2 不足。
        val blocked = policy(legal, engineering, evidenceTier = Tier.T2)
            .check(invocation(), descriptor(ToolRisk.READ))
        assertFalse(blocked.allowed)
        assertTrue(blocked.reason!!.contains("evidence-below-floor"))

        val chat = policy(legal, testManifest(scenario = ScenarioId.CHAT), evidenceTier = Tier.T3)
            .check(invocation(), descriptor(ToolRisk.READ))
        assertTrue(chat.allowed)
    }

    @Test
    fun `轻微证据不足放行但记录原因`() {
        val decision = policy(
            LegalGate(now = testClock()),
            testManifest(scenario = ScenarioId.OFFICE),
            evidenceTier = Tier.T1,
        ).check(invocation(), descriptor(ToolRisk.READ))
        assertTrue(decision.allowed)
        assertTrue(decision.reason!!.contains("evidence-below-floor"))
    }

    @Test
    fun `每工具证据等级解析器覆盖默认值`() {
        val legal = LegalGate(now = testClock())
        val governed = GovernedToolPolicy(
            GovernedToolPolicyOptions(
                legal = legal,
                manifest = testManifest(scenario = ScenarioId.ENGINEERING),
                evidenceTier = Tier.T2,
                tierFor = { _, _ -> Tier.T3 },
            ),
        )
        val decision = governed.check(invocation(), descriptor(ToolRisk.READ))
        assertTrue("T3 满足 ENGINEERING 的 F1 下限", decision.allowed)
    }
}
