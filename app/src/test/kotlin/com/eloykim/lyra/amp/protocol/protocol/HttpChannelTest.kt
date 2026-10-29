package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class HttpChannelTest {

    @Test
    fun `successful request carries envelope headers and idempotency key`() {
        val fake = FakeTransport { HttpResponse(200, body = """{"accepted":true}""".toByteArray()) }
        val channel = HttpTeamChannel(
            HttpChannelOptions(
                url = "https://peer.test/delegate",
                headers = mapOf("x-tenant" to "t1"),
                transport = fake,
            ),
        )

        val doc = delegateOver(channel, delegation())

        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://peer.test/delegate", request.url)
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("t1", request.headers["x-tenant"])
        assertEquals(CanonicalJson.digest(doc.toJson()), request.headers["idempotency-key"])

        val envelope = JsonParser.parse(request.body.toString(Charsets.UTF_8)) as JsonValue.Obj
        assertEquals("0.1", envelope.string("amp"))
        assertEquals("delegation-envelope", envelope.string("kind"))
        assertEquals("delegation", envelope.obj("delegation")?.string("kind"))
        assertEquals("dl.1", envelope.obj("delegation")?.string("id"))

        assertEquals(1, channel.sent.size)
        assertEquals(CanonicalJson.digest(doc.toJson()), channel.sent.single().key)
        assertEquals(1, channel.attempts)
    }

    @Test
    fun `peer rejection is surfaced as a reason`() {
        val fake = FakeTransport {
            HttpResponse(200, body = """{"accepted":false,"reason":"peer ceiling"}""".toByteArray())
        }
        val channel = HttpTeamChannel(HttpChannelOptions(url = "https://peer.test/delegate", transport = fake))

        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertEquals("peer ceiling", verdict.reason)

        val error = assertThrows(IllegalStateException::class.java) {
            delegateOver(channel, delegation())
        }
        assertTrue(error.message!!.contains("peer ceiling"))
    }

    @Test
    fun `HTTP 5xx is reported and retried when configured`() {
        var calls = 0
        val fake = FakeTransport {
            calls += 1
            if (calls == 1) {
                HttpResponse(503)
            } else {
                HttpResponse(200, body = """{"accepted":true}""".toByteArray())
            }
        }
        val channel = HttpTeamChannel(
            HttpChannelOptions(
                url = "https://peer.test/delegate",
                retries = 1,
                backoffMs = 0,
                transport = fake,
            ),
        )

        val verdict = channel.send(toDelegationDoc(delegation()))
        assertTrue(verdict.accepted)
        assertEquals(2, channel.attempts)
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `HTTP 503 without retries fails closed`() {
        val fake = FakeTransport { HttpResponse(503) }
        val channel = HttpTeamChannel(HttpChannelOptions(url = "https://peer.test/delegate", transport = fake))
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertEquals("HTTP 503", verdict.reason)
        assertEquals(1, channel.attempts)
    }

    @Test
    fun `HTTP 4xx is not retried`() {
        val fake = FakeTransport { HttpResponse(400) }
        val channel = HttpTeamChannel(
            HttpChannelOptions(
                url = "https://peer.test/delegate",
                retries = 3,
                backoffMs = 0,
                transport = fake,
            ),
        )
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertEquals("HTTP 400", verdict.reason)
        assertEquals(1, channel.attempts)
    }

    @Test
    fun `timeout fails closed instead of hanging`() {
        val fake = FakeTransport { throw SocketTimeoutException("read timed out") }
        val channel = HttpTeamChannel(HttpChannelOptions(url = "https://peer.test/slow", transport = fake))
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertEquals("timeout", verdict.reason)
    }

    @Test
    fun `oversized request body fails before the transport`() {
        val fake = FakeTransport { HttpResponse(200, body = """{"accepted":true}""".toByteArray()) }
        val channel = HttpTeamChannel(
            HttpChannelOptions(
                url = "https://peer.test/delegate",
                maxRequestBytes = 16,
                transport = fake,
            ),
        )
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertTrue(verdict.reason!!.contains("request body exceeds 16 bytes"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `oversized response body fails closed`() {
        val fake = FakeTransport {
            HttpResponse(200, body = ("x".repeat(64) + """{"accepted":true}""").toByteArray())
        }
        val channel = HttpTeamChannel(
            HttpChannelOptions(
                url = "https://peer.test/delegate",
                maxResponseBytes = 16,
                transport = fake,
            ),
        )
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertTrue(verdict.reason!!.contains("response body exceeds 16 bytes"))
    }

    @Test
    fun `invalid JSON response is retryable and fails closed`() {
        val fake = FakeTransport { HttpResponse(200, body = "not-json".toByteArray()) }
        val channel = HttpTeamChannel(
            HttpChannelOptions(
                url = "https://peer.test/delegate",
                retries = 1,
                backoffMs = 0,
                transport = fake,
            ),
        )
        val verdict = channel.send(toDelegationDoc(delegation()))
        assertFalse(verdict.accepted)
        assertNotNull(verdict.reason)
        assertEquals(2, channel.attempts)
    }

    @Test
    fun `every attempt is recorded even when the peer rejects it`() {
        val fake = FakeTransport { HttpResponse(200, body = """{"accepted":false,"reason":"nope"}""".toByteArray()) }
        val channel = HttpTeamChannel(HttpChannelOptions(url = "https://peer.test/delegate", transport = fake))
        assertThrows(IllegalStateException::class.java) { delegateOver(channel, delegation()) }
        assertEquals(1, channel.sent.size)
    }

    private fun delegation(): Delegation = Delegation(
        id = "dl.1",
        granter = "a.lead",
        delegatee = "a.worker",
        goalDigest = GOAL,
        scope = listOf("tool.logs.query"),
        budget = Budget(tokens = 100),
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
