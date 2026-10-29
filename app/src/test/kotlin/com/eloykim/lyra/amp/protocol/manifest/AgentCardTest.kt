package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** Agent card discovery, signing and registry-backed verification. */
class AgentCardTest {

    @Test
    fun `签名卡片可验证且 extras 留在签名载荷里`() {
        val signer = Fixtures.requireSigner("worker#1")
        val card = Fixtures.card(extras = mapOf("x-default-model" to JsonValue.Str("m1")))
        val signature = signCard(card, signer)

        val wire = CanonicalJson.encode(card.toSignedJson(listOf(signature)))
        val parsed = AgentCard.fromJson(JsonParser.parse(wire))
        assertEquals(1, parsed.signatures().size)
        assertEquals("m1", (parsed.extras["x-default-model"] as JsonValue.Str).value)
        assertTrue(parsed.toJson().has("signatures"))

        val verdict = verifyCardSignature(parsed, signature, signer.publicKey()!!)
        assertTrue("验签失败: ${verdict.reasons.joinToString("; ")}", verdict.ok)
    }

    @Test
    fun `篡改 endpoint 后验签失败`() {
        val signer = Fixtures.requireSigner("worker#1")
        val signature = signCard(Fixtures.card(), signer)
        val tampered = Fixtures.card(url = "https://evil.example/a2a")

        val verdict = verifyCardSignature(tampered, signature, signer.publicKey()!!)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("signature") })
    }

    @Test
    fun `不同密钥无法验证卡片`() {
        val signer = Fixtures.requireSigner("worker#1")
        val other = Fixtures.requireSigner("worker#2")
        val signature = signCard(Fixtures.card(), signer)

        assertFalse(verifyCardSignature(Fixtures.card(), signature, other.publicKey()!!).ok)
    }

    @Test
    fun `损坏的 protected header 被拒绝`() {
        val signer = Fixtures.requireSigner("worker#1")
        val verdict = verifyCardSignature(
            Fixtures.card(),
            CardSignature(protectedHeader = "not-base64-json", signature = "AA"),
            signer.publicKey()!!,
        )
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("protected") })
    }

    @Test
    fun `注册表解析 kid 并拒绝未知或已撤销的键`() {
        val signer = Fixtures.requireSigner("worker#1")
        val card = Fixtures.card()
        val signature = signCard(card, signer)

        val registry = KeyRegistry()
        registry.add(signer.kid, signer.publicKey()!!)
        assertTrue(verifyCardWithRegistry(card, signature, registry).ok)

        registry.revoke(signer.kid)
        val revoked = verifyCardWithRegistry(card, signature, registry)
        assertFalse(revoked.ok)
        assertTrue(revoked.reasons.any { it.contains("unknown or revoked key") })

        val unknown = verifyCardWithRegistry(card, signature, KeyRegistry())
        assertFalse(unknown.ok)
        assertTrue(unknown.reasons.any { it.contains("unknown or revoked key") })
    }

    @Test
    fun `fetchAgentCard 使用 well-known 路径并返回 endpoint`() {
        val signer = Fixtures.requireSigner("worker#1")
        val signature = signCard(Fixtures.card(), signer)
        val body = CanonicalJson.encode(Fixtures.card().toSignedJson(listOf(signature)))
        var requestedUrl = ""
        var requestedHeaders: Map<String, String> = emptyMap()
        val fetcher = CardFetcher { url, headers, _ ->
            requestedUrl = url
            requestedHeaders = headers
            CardHttpResponse(status = 200, body = body)
        }

        val result = fetchAgentCard("https://worker.example/", fetcher = fetcher)
        assertTrue("发现失败: ${result.reasons.joinToString("; ")}", result.ok)
        assertEquals("https://worker.example/.well-known/agent-card.json", requestedUrl)
        assertEquals("application/json", requestedHeaders["accept"])
        assertEquals("1.0", requestedHeaders["a2a-version"])
        assertEquals("https://worker.example/a2a", result.endpoint)
        assertEquals("worker-agent", result.card?.name)
    }

    @Test
    fun `fetchAgentCard 拒绝无名字或无接口的卡片`() {
        val fetcher = CardFetcher { _, _, _ -> CardHttpResponse(200, """{"description":"nameless"}""") }
        val result = fetchAgentCard("https://worker.example", fetcher = fetcher)

        assertFalse(result.ok)
        assertNotNull(result.card)
        assertTrue(result.reasons.any { it.contains("name") })
        assertTrue(result.reasons.any { it.contains("interface url") })
    }

    @Test
    fun `fetchAgentCard 把 HTTP 错误与超时变成 reasons`() {
        val notFound = fetchAgentCard(
            "https://worker.example",
            fetcher = CardFetcher { _, _, _ -> CardHttpResponse(404, "") },
        )
        assertFalse(notFound.ok)
        assertTrue(notFound.reasons.first().contains("HTTP 404"))

        val timeout = fetchAgentCard(
            "https://worker.example",
            fetcher = CardFetcher { _, _, _ -> throw SocketTimeoutException("timed out") },
        )
        assertFalse(timeout.ok)
        assertEquals(listOf("timeout"), timeout.reasons)
    }
}
