package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.SocketTimeoutException

class A2ATest {

    @Test
    fun `request is a real A2A message send envelope`() {
        val doc = toDelegationDoc(delegation())
        val request = toA2ARequest(doc)

        assertEquals("2.0", request.jsonrpc)
        assertEquals("message/send", request.method)
        assertEquals("amp-dl.1", request.id)

        val message = request.params.message
        assertEquals("message", message.toJson().string("kind"))
        assertEquals("dl.1", message.messageId)
        assertEquals(A2ARole.USER, message.role)
        assertEquals(2, message.parts.size)

        val text = message.parts[0]
        assertEquals(A2APartKind.TEXT, text.kind)
        assertEquals("AMP delegation dl.1: a.lead -> a.worker (depth 0)", text.text)

        val data = message.parts[1]
        assertEquals(A2APartKind.DATA, data.kind)
        assertEquals("dl.1", data.data!!.obj("io.amp/delegation")!!.string("id"))
        assertEquals(
            CanonicalJson.digest(doc.toJson()),
            message.metadata!!.string("io.amp/delegation_digest"),
        )
        assertEquals("0.1", message.metadata!!.string("io.amp/version"))

        assertEquals(request, A2ARequest.fromJson(request.toJson()))
    }

    @Test
    fun `task result means accepted with the A2A state surfaced`() {
        val fake = FakeTransport {
            HttpResponse(
                200,
                body = """{"jsonrpc":"2.0","id":"amp-dl.1","result":{"kind":"task","id":"task-1","status":{"state":"submitted"}}}"""
                    .toByteArray(),
            )
        }
        val channel = A2AChannel(A2AChannelOptions(url = "https://worker.test/a2a", transport = fake))

        val verdict = channel.send(toDelegationDoc(delegation()))
        assertTrue(verdict.accepted)
        assertEquals("task task-1 submitted", verdict.reason)
        assertEquals(1, channel.requests.size)

        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("1.0", request.headers["a2a-version"])
        val body = JsonParser.parse(request.body.toString(Charsets.UTF_8)) as JsonValue.Obj
        assertEquals("message/send", body.string("method"))
    }

    @Test
    fun `agent message result is also accepted`() {
        val fake = FakeTransport {
            HttpResponse(
                200,
                body = """{"jsonrpc":"2.0","id":"amp-dl.1","result":{"kind":"message","messageId":"m-1","role":"agent","parts":[]}}"""
                    .toByteArray(),
            )
        }
        val channel = A2AChannel(A2AChannelOptions(url = "https://worker.test/a2a", transport = fake))
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertTrue(verdict.accepted)
        assertEquals("agent replied with a message", verdict.reason)
    }

    @Test
    fun `JSON-RPC error fails closed with the code`() {
        val fake = FakeTransport {
            HttpResponse(
                200,
                body = """{"jsonrpc":"2.0","id":"amp-dl.1","error":{"code":-32001,"message":"Task not found"}}"""
                    .toByteArray(),
            )
        }
        val channel = A2AChannel(A2AChannelOptions(url = "https://worker.test/a2a", transport = fake))
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertEquals("A2A -32001: Task not found", verdict.reason)

        val error = assertThrows(IllegalStateException::class.java) {
            delegateOver(channel, delegation())
        }
        assertTrue(error.message!!.contains("A2A -32001"))
    }

    @Test
    fun `unrecognised result and HTTP failure fail closed`() {
        val unrecognised = FakeTransport {
            HttpResponse(200, body = """{"jsonrpc":"2.0","id":"amp-dl.1","result":{"kind":"status"}}""".toByteArray())
        }
        val first = A2AChannel(A2AChannelOptions(url = "https://worker.test/a2a", transport = unrecognised))
        assertEquals("unrecognised A2A result", first.send(toDelegationDoc(delegation())).reason)

        val failing = FakeTransport { HttpResponse(500) }
        val second = A2AChannel(A2AChannelOptions(url = "https://worker.test/a2a", transport = failing))
        val verdict = second.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertEquals("HTTP 500", verdict.reason)
    }

    @Test
    fun `A2A timeout and oversized response fail closed`() {
        val timeout = FakeTransport { throw SocketTimeoutException("read timed out") }
        val first = A2AChannel(A2AChannelOptions(url = "https://worker.test/a2a", transport = timeout))
        assertEquals("timeout", first.send(toDelegationDoc(delegation())).reason)

        val oversized = FakeTransport { HttpResponse(200, body = "x".repeat(256).toByteArray()) }
        val second = A2AChannel(
            A2AChannelOptions(url = "https://worker.test/a2a", maxResponseBytes = 32, transport = oversized),
        )
        val verdict = second.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertTrue(verdict.reason!!.contains("response body exceeds 32 bytes"))
    }

    @Test
    fun `agent card is discovered at the well known path`() {
        val card = """
            {"name":"worker-agent","version":"1.0.0",
             "supportedInterfaces":[{"url":"https://worker.test/a2a","protocolBinding":"JSONRPC"}],
             "capabilities":{"streaming":false},
             "skills":[{"id":"analyze","name":"Analyze"}]}
        """.trimIndent()
        val fake = FakeTransport { HttpResponse(200, body = card.toByteArray()) }

        val result = fetchAgentCard("https://worker.test/", transport = fake)
        assertTrue(result.reasons.joinToString("; "), result.ok)
        assertEquals("worker-agent", result.card?.name)
        assertEquals("https://worker.test/a2a", result.endpoint)
        assertEquals("JSONRPC", result.card?.supportedInterfaces?.single()?.protocolBinding)
        assertEquals(false, result.card?.capabilities?.bool("streaming"))
        assertEquals("analyze", result.card?.skills?.single()?.id)

        val request = fake.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://worker.test/.well-known/agent-card.json", request.url)
        assertEquals("application/json", request.headers["accept"])
        assertEquals("1.0", request.headers["a2a-version"])
    }

    @Test
    fun `agent card prefers its explicit url and rejects a nameless card`() {
        val explicit = """
            {"name":"demo","url":"https://demo.test/a2a",
             "supportedInterfaces":[{"url":"https://demo.test/other"}]}
        """.trimIndent()
        val first = fetchAgentCard(
            "https://demo.test",
            transport = FakeTransport { HttpResponse(200, body = explicit.toByteArray()) },
        )
        assertEquals("https://demo.test/a2a", first.endpoint)

        val nameless = fetchAgentCard(
            "https://demo.test",
            transport = FakeTransport { HttpResponse(200, body = """{"description":"nameless"}""".toByteArray()) },
        )
        assertFalse(nameless.ok)
        assertTrue(nameless.reasons.any { it.contains("name") })
        assertNotNull(nameless.card)
    }

    @Test
    fun `missing card reports the HTTP status`() {
        val result = fetchAgentCard(
            "https://missing.test",
            transport = FakeTransport { HttpResponse(404) },
        )
        assertFalse(result.ok)
        assertTrue(result.reasons.single().startsWith("HTTP 404 from "))
    }

    @Test
    fun `channelFromCard uses the advertised endpoint and rejects a bad card`() {
        val endpoint = "https://worker.test/a2a"
        val card = CardResult(
            ok = true,
            card = AgentCard.of(
                name = "worker",
                supportedInterfaces = listOf(AgentInterface(endpoint, "JSONRPC")),
            ),
            endpoint = endpoint,
        )
        assertEquals(endpoint, channelFromCard(card).url)

        val error = assertThrows(IllegalStateException::class.java) {
            channelFromCard(CardResult(false, reasons = listOf("no card")))
        }
        assertTrue(error.message!!.contains("no usable endpoint"))
    }

    @Test
    fun `signed agent card verifies and signatures are excluded from the payload`() {
        val signer = alice()
        val card = AgentCard.of(
            name = "worker-agent",
            description = "does work",
            version = "1.0.0",
            supportedInterfaces = listOf(AgentInterface("https://worker.test/a2a", "JSONRPC", "1.0")),
            capabilities = jsonObj("streaming" to jsonBool(false)),
            skills = listOf(AgentSkill("analyze", "Analyze")),
            extra = mapOf("io.amp/vendor" to jsonText("lyra")),
        )

        val signature = signCard(card, signer)
        assertTrue(Base64Url.isValid(signature.protectedHeader))
        assertTrue(Base64Url.isValid(signature.signature))

        val verified = verifyCardSignature(card, signature, signer.publicKey())
        assertTrue(verified.reasons.joinToString("; "), verified.ok)

        val signedCard = card.withSignatures(listOf(signature))
        assertTrue(verifyCardSignature(signedCard, signature, signer.publicKey()).ok)
        assertEquals(signature, signCard(signedCard, signer))

        val tampered = AgentCard(card.raw.copyWith("name", jsonText("mallory")))
        val failed = verifyCardSignature(tampered, signature, signer.publicKey())
        assertFalse(failed.ok)
        assertTrue(failed.reasons.any { it.contains("card signature") })
    }

    @Test
    fun `card signature resolves keys through a registry`() {
        val signer = alice()
        val card = AgentCard.of(name = "worker-agent", url = "https://worker.test/a2a")
        val signature = signCard(card, signer)

        val registry = CardKeyRegistry { kid -> if (kid == "a.alice#1") signer.publicKey() else null }
        assertTrue(verifyCardWithRegistry(card, signature, registry).ok)

        val unknown = verifyCardWithRegistry(card, signature) { null }
        assertFalse(unknown.ok)
        assertTrue(unknown.reasons.single().startsWith("unknown or revoked key:"))

        val malformed = verifyCardWithRegistry(card, CardSignature("not-base64", "not-base64"), registry)
        assertFalse(malformed.ok)
        assertEquals(listOf("protected header is not base64url JSON"), malformed.reasons)
    }

    private fun alice(): JcaEd25519Signer {
        assumeTrue("host JDK lacks Ed25519", Ed25519.isAvailable())
        return Ed25519.generate("a.alice#1")!!
    }

    private fun delegation(): Delegation = Delegation(
        id = "dl.1",
        granter = "a.lead",
        delegatee = "a.worker",
        goalDigest = GOAL,
        scope = listOf("tool.logs.query"),
        budget = Budget(tokens = 10),
        deadline = "2026-10-24T12:00:00Z",
        mayRedelegate = false,
        depth = 0,
    )

    private class FakeTransport(private val handler: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    companion object {
        private val GOAL = "sha256:" + "a".repeat(64)
    }
}
