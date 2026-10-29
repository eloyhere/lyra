package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.bayes.BetaBelief
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CooperationTest {

    private val marketingManifest = testManifest(
        id = "amn.1",
        perspective = PerspectiveId.MARKETING,
        scenario = ScenarioId.OFFICE,
    )
    private val serviceManifest = testManifest(
        id = "amn.2",
        perspective = PerspectiveId.CUSTOMER_SERVICE,
        scenario = ScenarioId.OFFICE,
    )
    private val supervisoryManifest = testManifest(
        id = "amn.3",
        perspective = PerspectiveId.SUPERVISORY,
        scenario = ScenarioId.OFFICE,
    )

    private fun agent(
        id: String,
        perspective: PerspectiveId,
        manifest: AttentionManifest,
        reliability: BetaBelief = BetaBelief(1.0, 1.0),
    ): RegisteredAgent = RegisteredAgent(
        agentId = id,
        perspective = perspective,
        manifest = manifest,
        budget = BudgetAccount(BudgetAccountScope.AGENT, "ledger.$id"),
        reliability = reliability,
    )

    private fun bus(): CooperationBus {
        val bus = CooperationBus(CooperationOptions(now = testClock()))
        bus.register(agent("agent.marketing", PerspectiveId.MARKETING, marketingManifest))
        bus.register(agent("agent.service", PerspectiveId.CUSTOMER_SERVICE, serviceManifest))
        bus.register(agent("agent.supervisory", PerspectiveId.SUPERVISORY, supervisoryManifest))
        return bus
    }

    private fun taskCredential() = testCredential(
        to = PerspectiveId.CUSTOMER_SERVICE,
        scenario = ScenarioId.OFFICE,
        manifest = ManifestRef(serviceManifest.id, serviceManifest.version),
    )

    @Test
    fun `注册与广播`() {
        val bus = bus()
        assertGovernanceError(GovernanceCode.REGISTRY_INVALID) {
            bus.register(agent("agent.marketing", PerspectiveId.MARKETING, marketingManifest))
        }
        assertEquals(1, bus.agentsOf(PerspectiveId.MARKETING).size)
        val deliveries = bus.announce("agent.marketing", AT_ISSUE)
        assertEquals(2, deliveries.size)
        assertTrue(deliveries.all { it.ok })
        assertTrue(deliveries.all { it.message!!.kind == MessageKind.MANIFEST })
        assertEquals(2, bus.boardEntries().size)
        assertTrue(bus.unregister("agent.supervisory"))
        assertFalse(bus.unregister("agent.supervisory"))
        assertGovernanceError(GovernanceCode.REGISTRY_INVALID) {
            bus.refresh("agent.none", marketingManifest)
        }
    }

    @Test
    fun `任务消息需要凭据`() {
        val bus = bus()
        val missing = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.service",
                kind = MessageKind.TASK,
                at = AT_ISSUE,
                refs = listOf(refOf(RefKind.CLAIM, "c.1")),
            ),
        )
        assertFalse(missing.ok)
        assertEquals(DeliveryCode.CREDENTIAL_MISSING, missing.code)

        val delivered = bus.handoff(
            from = "agent.marketing",
            to = "agent.service",
            credential = taskCredential(),
            refs = listOf(refOf(RefKind.CLAIM, "c.1")),
            at = AT_ISSUE,
            summary = "please confirm the requirement",
        )
        assertTrue(delivered.ok)
        assertEquals(MessageKind.TASK, delivered.message!!.kind)
        assertEquals("amn.1", delivered.message!!.manifest.id)
        assertEquals(2, delivered.message!!.manifest.focus)
        val inbox = bus.inbox("agent.service", AT_ISSUE)
        assertEquals(1, inbox.size)
        assertTrue(inbox[0].ok)
    }

    @Test
    fun `接收端复核摘要与清单版本`() {
        val bus = bus()
        val delivered = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.service",
                kind = MessageKind.MANIFEST,
                at = AT_ISSUE,
            ),
        )
        val message = delivered.message!!
        assertTrue(verifyAgentMessage(message))

        val tampered = message.copy(summary = "rewritten after signing")
        assertFalse(verifyAgentMessage(tampered))
        val rejected = bus.receive(tampered)
        assertEquals(DeliveryCode.MANIFEST_INVALID, rejected.code)
        assertEquals("message digest does not match", rejected.detail)

        // 摘要一致的重复投递被幂等接受。
        val duplicate = bus.receive(message)
        assertTrue(duplicate.ok)
        assertTrue(duplicate.detail.contains("duplicate ignored"))

        // 发送方重新推导清单后，旧消息引用的版本过期。
        bus.refresh(
            "agent.marketing",
            rederiveAttentionManifest(marketingManifest, RederivePatch(note = "v2"), TEST_SIGNERS, AT_TEN_MIN),
        )
        val stale = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.service",
                kind = MessageKind.MANIFEST,
                at = AT_ISSUE,
            ),
        )
        assertTrue(stale.ok)
        val staleMessage = stale.message!!
        bus.refresh("agent.marketing", marketingManifest)
        val versionRejected = bus.receive(staleMessage)
        assertEquals(DeliveryCode.MANIFEST_INVALID, versionRejected.code)
        assertTrue(versionRejected.detail.contains("stale manifest version"))
    }

    @Test
    fun `回执消息校验结论与清单版本`() {
        val bus = bus()
        val badConclusion = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.service",
                kind = MessageKind.RECEIPT,
                at = AT_ISSUE,
                receipt = ReceiptSummary(
                    conclusion = "   ",
                    confidence = 0.5,
                    evidence = emptyList(),
                    manifest = ManifestRef(marketingManifest.id, marketingManifest.version),
                ),
            ),
        )
        assertEquals(DeliveryCode.RECEIPT_INVALID, badConclusion.code)

        val wrongVersion = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.service",
                kind = MessageKind.RECEIPT,
                at = AT_ISSUE,
                receipt = ReceiptSummary(
                    conclusion = "market looks good",
                    confidence = 0.8,
                    evidence = listOf(refOf(RefKind.EVIDENCE, "e.1")),
                    manifest = ManifestRef(marketingManifest.id, marketingManifest.version + 1),
                ),
            ),
        )
        assertEquals(DeliveryCode.RECEIPT_INVALID, wrongVersion.code)

        val accepted = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.service",
                kind = MessageKind.RECEIPT,
                at = AT_ISSUE,
                receipt = ReceiptSummary(
                    conclusion = "market looks good",
                    confidence = 0.8,
                    evidence = listOf(refOf(RefKind.EVIDENCE, "e.1")),
                    manifest = ManifestRef(marketingManifest.id, marketingManifest.version),
                ),
            ),
        )
        assertTrue(accepted.ok)
    }

    @Test
    fun `审计请求只发给监事会`() {
        val bus = bus()
        val delivered = bus.auditRequest(
            from = "agent.marketing",
            evidence = listOf(refOf(RefKind.EVIDENCE, "e.1")),
            at = AT_ISSUE,
        )
        assertTrue(delivered.ok)
        assertEquals(MessageKind.AUDIT_REQUEST, delivered.message!!.kind)
        assertEquals("agent.supervisory", delivered.message!!.to)

        val withoutSupervisory = CooperationBus(CooperationOptions(now = testClock()))
        withoutSupervisory.register(agent("agent.marketing", PerspectiveId.MARKETING, marketingManifest))
        val refused = withoutSupervisory.auditRequest(
            from = "agent.marketing",
            evidence = listOf(refOf(RefKind.EVIDENCE, "e.1")),
            at = AT_ISSUE,
        )
        assertEquals(DeliveryCode.UNKNOWN_RECIPIENT, refused.code)
    }

    @Test
    fun `按可靠性乘信息价值裁剪`() {
        val bus = bus()
        bus.unregister("agent.supervisory")
        bus.register(agent("agent.a", PerspectiveId.STRATEGY, marketingManifest, BetaBelief(9.0, 1.0)))
        bus.register(agent("agent.b", PerspectiveId.WRITING, marketingManifest, BetaBelief(1.0, 1.0)))
        bus.register(agent("agent.c", PerspectiveId.TECHNOLOGY, marketingManifest, BetaBelief(1.0, 9.0)))

        val ranked = bus.route(
            from = "agent.marketing",
            candidates = listOf("agent.marketing", "agent.a", "agent.b", "agent.c"),
            options = RouteOptions(limit = 2),
        )
        assertEquals(listOf("agent.a", "agent.b"), ranked)

        val withVoi = bus.route(
            from = "agent.marketing",
            candidates = listOf("agent.a", "agent.b", "agent.c"),
            options = RouteOptions(voi = mapOf("agent.c" to 30.0), limit = 2),
        )
        assertEquals(listOf("agent.c", "agent.a"), withVoi)
        assertTrue(bus.route("agent.marketing", listOf("agent.a"), RouteOptions(limit = 0)).isEmpty())
    }

    @Test
    fun `观察结果更新 Beta 后验`() {
        val bus = bus()
        val updated = bus.observe("agent.marketing", success = true, weight = 2.0)
        assertEquals(3.0, updated.alpha, 0.0)
        assertEquals(1.0, updated.beta, 0.0)
        val failed = bus.observe("agent.marketing", success = false, weight = 3.0)
        assertEquals(3.0, failed.alpha, 0.0)
        assertEquals(4.0, failed.beta, 0.0)
        assertEquals(3.0 / 7.0, bus.agent("agent.marketing")!!.reliability.mean, 1e-9)
        assertGovernanceError(GovernanceCode.REGISTRY_INVALID) {
            bus.observe("agent.none", success = true)
        }
    }

    @Test
    fun `自发送与未知收发件人被拒绝`() {
        val bus = bus()
        val self = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.marketing",
                kind = MessageKind.MANIFEST,
                at = AT_ISSUE,
            ),
        )
        assertEquals(DeliveryCode.SELF_SEND, self.code)
        val unknownSender = bus.send(
            AgentMessageInput(
                from = "agent.ghost",
                to = "agent.service",
                kind = MessageKind.MANIFEST,
                at = AT_ISSUE,
            ),
        )
        assertEquals(DeliveryCode.UNKNOWN_SENDER, unknownSender.code)
        val unknownRecipient = bus.send(
            AgentMessageInput(
                from = "agent.marketing",
                to = "agent.ghost",
                kind = MessageKind.MANIFEST,
                at = AT_ISSUE,
            ),
        )
        assertEquals(DeliveryCode.UNKNOWN_RECIPIENT, unknownRecipient.code)
        assertNull(bus.agent("agent.ghost"))
    }
}
