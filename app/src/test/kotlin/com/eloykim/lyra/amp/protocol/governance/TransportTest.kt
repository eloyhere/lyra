package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.protocol.HttpRequest
import com.eloykim.lyra.amp.protocol.protocol.HttpResponse
import com.eloykim.lyra.amp.protocol.protocol.HttpTransport
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class TransportTest {

    private val fixedMs: Long = Instant.parse("2026-10-24T07:05:00Z").toEpochMilli()

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

    private fun bus(): CooperationBus {
        val bus = CooperationBus(CooperationOptions(now = testClock()))
        bus.register(
            RegisteredAgent(
                agentId = "agent.marketing",
                perspective = PerspectiveId.MARKETING,
                manifest = marketingManifest,
                budget = BudgetAccount(BudgetAccountScope.AGENT, "ledger.marketing"),
                reliability = com.eloykim.lyra.amp.protocol.bayes.BetaBelief(1.0, 1.0),
            ),
        )
        bus.register(
            RegisteredAgent(
                agentId = "agent.service",
                perspective = PerspectiveId.CUSTOMER_SERVICE,
                manifest = serviceManifest,
                budget = BudgetAccount(BudgetAccountScope.AGENT, "ledger.service"),
                reliability = com.eloykim.lyra.amp.protocol.bayes.BetaBelief(1.0, 1.0),
            ),
        )
        return bus
    }

    private fun message(bus: CooperationBus): AgentMessage = bus.send(
        AgentMessageInput(
            from = "agent.marketing",
            to = "agent.service",
            kind = MessageKind.MANIFEST,
            at = AT_ISSUE,
        ),
    ).message!!

    private fun key(): JcaEd25519Signer {
        assumeTrue("需要 Ed25519 提供者", Ed25519.isAvailable())
        return Ed25519.generate("gov.marketing")!!
    }

    private fun keyring(key: JcaEd25519Signer): GovernanceKeyring =
        GovernanceKeyring().apply {
            add("marketing", key.publicKey() ?: error("no public key"), kid = key.kid, at = fixedMs)
        }

    private fun envelope(
        message: AgentMessage,
        signerName: String,
        timestamp: String,
        nonce: String,
        signature: String,
        kid: String,
    ): JsonValue.Obj = jsonObj(
        "message" to message.toJson(),
        "auth" to WireAuth(
            signer = signerName,
            kid = kid,
            timestamp = timestamp,
            nonce = nonce,
            signature = signature,
        ).toJson(),
    )

    private fun signedEnvelope(message: AgentMessage, key: JcaEd25519Signer): JsonValue.Obj {
        val timestamp = Instant.ofEpochMilli(fixedMs).toString()
        val nonce = "nonce-1"
        val signed = jsonObj(
            "message" to message.toJson(),
            "signer" to jsonText("marketing"),
            "timestamp" to jsonText(timestamp),
            "nonce" to jsonText(nonce),
        )
        return envelope(message, "marketing", timestamp, nonce, signCanonicalValue(signed, key), key.kid)
    }

    @Test
    fun `环回传输直接经过总线`() {
        val bus = bus()
        val delivery = LoopbackTransport(bus).deliver(message(bus))
        assertTrue(delivery.ok)
        assertEquals(DeliveryCode.DELIVERED, delivery.code)
    }

    @Test
    fun `无鉴权的处理器拒绝畸形信封并投递合法消息`() {
        val bus = CooperationBus(CooperationOptions(now = testClock()))
        val handler = createCooperationHandler(bus)
        val malformed = handler.handle(JsonValue.Str("nope"))
        assertEquals(400, malformed.status)
        assertEquals(DeliveryCode.MANIFEST_INVALID, malformed.body.code)

        val clientBus = bus()
        val unknown = handler.handle(jsonObj("message" to message(clientBus).toJson()))
        assertEquals(422, unknown.status)
        assertEquals(DeliveryCode.UNKNOWN_SENDER, unknown.body.code)
    }

    @Test
    fun `缺少鉴权返回 401`() {
        val bus = bus()
        val handler = createCooperationHandler(
            bus,
            CooperationHandlerOptions(keyring = keyring(key()), now = { fixedMs }),
        )
        val response = handler.handle(jsonObj("message" to message(bus).toJson()))
        assertEquals(401, response.status)
        assertTrue(response.body.detail.contains("missing transport authentication"))
    }

    @Test
    fun `时钟偏移返回 409`() {
        val key = key()
        val bus = bus()
        val handler = createCooperationHandler(
            bus,
            CooperationHandlerOptions(keyring = keyring(key), now = { fixedMs }),
        )
        val skewed = envelope(
            message = message(bus),
            signerName = "marketing",
            timestamp = Instant.ofEpochMilli(fixedMs + 3_600_000).toString(),
            nonce = "nonce-skew",
            signature = "AAAA",
            kid = key.kid,
        )
        val response = handler.handle(skewed)
        assertEquals(409, response.status)
        assertTrue(response.body.detail.contains("skew"))
    }

    @Test
    fun `重放返回 409`() {
        val key = key()
        val bus = bus()
        val handler = createCooperationHandler(
            bus,
            CooperationHandlerOptions(keyring = keyring(key), now = { fixedMs }),
        )
        val envelope = signedEnvelope(message(bus), key)
        val first = handler.handle(envelope)
        assertEquals(200, first.status)
        val second = handler.handle(envelope)
        assertEquals(409, second.status)
        assertTrue(second.body.detail.contains("nonce"))
    }

    @Test
    fun `签名无效返回 401`() {
        val key = key()
        val bus = bus()
        val handler = createCooperationHandler(
            bus,
            CooperationHandlerOptions(keyring = keyring(key), now = { fixedMs }),
        )
        val envelope = signedEnvelope(message(bus), key)
        val auth = envelope.requireObj("auth")
        val tampered = envelope.copyWith(
            "auth",
            auth.copyWith("signature", jsonText("A".repeat(86))),
        )
        val response = handler.handle(tampered)
        assertEquals(401, response.status)
        assertTrue(response.body.detail.contains("does not verify"))
    }

    @Test
    fun `未知签名者返回 401`() {
        val key = key()
        val bus = bus()
        val handler = createCooperationHandler(
            bus,
            CooperationHandlerOptions(keyring = GovernanceKeyring(), now = { fixedMs }),
        )
        val response = handler.handle(signedEnvelope(message(bus), key))
        assertEquals(401, response.status)
        assertTrue(response.body.detail.contains("unknown or revoked"))
    }

    @Test
    fun `客户端送达并解析 Delivery`() {
        val key = key()
        val serverBus = CooperationBus(CooperationOptions(now = testClock()))
        serverBus.register(
            RegisteredAgent(
                agentId = "agent.marketing",
                perspective = PerspectiveId.MARKETING,
                manifest = marketingManifest,
                budget = BudgetAccount(BudgetAccountScope.AGENT, "ledger.marketing"),
                reliability = com.eloykim.lyra.amp.protocol.bayes.BetaBelief(1.0, 1.0),
            ),
        )
        serverBus.register(
            RegisteredAgent(
                agentId = "agent.service",
                perspective = PerspectiveId.CUSTOMER_SERVICE,
                manifest = serviceManifest,
                budget = BudgetAccount(BudgetAccountScope.AGENT, "ledger.service"),
                reliability = com.eloykim.lyra.amp.protocol.bayes.BetaBelief(1.0, 1.0),
            ),
        )
        val handler = createCooperationHandler(
            serverBus,
            CooperationHandlerOptions(keyring = keyring(key), now = { fixedMs }),
        )
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse {
                val parsed = JsonParser.parse(request.body.toString(Charsets.UTF_8))
                val response = handler.handle(parsed)
                return HttpResponse(
                    status = response.status,
                    headers = mapOf("content-type" to "application/json"),
                    body = CanonicalJson.encode(response.body.toJson()).toByteArray(Charsets.UTF_8),
                )
            }
        }
        val client = HttpCooperationTransport(
            HttpCooperationOptions(
                endpoint = "http://127.0.0.1/cooperation",
                auth = HttpAuth("marketing", key),
                transport = transport,
                nowMs = { fixedMs },
            ),
        )
        val delivery = client.deliver(message(serverBus))
        assertTrue("签名请求应被接受：${delivery.detail}", delivery.ok)
        assertEquals(DeliveryCode.DELIVERED, delivery.code)

        val nonDelivery = HttpCooperationTransport(
            HttpCooperationOptions(
                endpoint = "http://127.0.0.1/cooperation",
                transport = object : HttpTransport {
                    override fun execute(request: HttpRequest): HttpResponse =
                        HttpResponse(200, emptyMap(), "not json".toByteArray(Charsets.UTF_8))
                },
            ),
        ).deliver(message(serverBus))
        assertFalse(nonDelivery.ok)
        assertEquals(DeliveryCode.TRANSPORT_ERROR, nonDelivery.code)
    }

    @Test
    fun `服务器端到端投递并拒绝错误路径`() {
        val clientBus = bus()
        val clientMessage = message(clientBus)
        val serverBus = CooperationBus(CooperationOptions(now = testClock()))
        val running = startCooperationServer(serverBus, CooperationServerOptions(now = { fixedMs }))
        try {
            assertTrue(running.port > 0)
            assertTrue(running.url.endsWith("/cooperation"))
            val client = HttpCooperationTransport(
                HttpCooperationOptions(endpoint = running.url, timeoutMs = 5_000),
            )
            val delivery = client.deliver(clientMessage)
            // 服务端没有注册这个发件人，因此是 422 + unknown-sender，而不是传输错误。
            assertFalse(delivery.ok)
            assertEquals(DeliveryCode.UNKNOWN_SENDER, delivery.code)

            val wrongPath = URL(running.url.replace("/cooperation", "/nope"))
                .openConnection() as HttpURLConnection
            try {
                wrongPath.requestMethod = "POST"
                wrongPath.doOutput = true
                wrongPath.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                assertEquals(404, wrongPath.responseCode)
            } finally {
                wrongPath.disconnect()
            }
        } finally {
            running.close()
        }
    }
}
