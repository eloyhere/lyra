package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.SignatureRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manifest signing: digest coverage, tamper detection and revocation. */
class SignTest {

    @Test
    fun `签名清单可通过 Manifest_verify 验证`() {
        val fixture = Fixtures.signed()
        val signed = fixture.manifest
        val publicKey = fixture.signer.publicKey()!!

        assertTrue(signed.digest.startsWith("sha256:"))
        assertEquals(contentDigest(signed.unsigned()), signed.digest)
        assertEquals(1, signed.signatures.size)
        assertEquals(SignatureRole.SELF, signed.signatures.first().role)
        assertEquals("ed25519", signed.signatures.first().alg)
        assertEquals(fixture.signer.kid, signed.signatures.first().kid)

        val verdict = Manifest.verify(signed, publicKey)
        assertTrue("验证失败: ${verdict.reasons.joinToString("; ")}", verdict.ok)
        assertTrue(verdict.reasons.isEmpty())
        assertFalse(verdict.unavailable)

        val schemaProblems = AmpSchemas.validateKind("manifest", signed.toJson())
        assertTrue("schema 错误: ${schemaProblems.joinToString("; ")}", schemaProblems.isEmpty())
    }

    @Test
    fun `篡改正文后即使保留旧签名也失败`() {
        val fixture = Fixtures.signed()
        val tampered = fixture.manifest.copy(agent = fixture.manifest.agent.copy(role = "admin"))

        val verdict = Manifest.verify(tampered, fixture.signer.publicKey()!!)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("digest") })
    }

    @Test
    fun `不同公钥无法验证签名`() {
        val fixture = Fixtures.signed()
        val other = Fixtures.requireSigner("a.gamma#2")

        val verdict = Manifest.verify(fixture.manifest, other.publicKey()!!)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("signature") })
    }

    @Test
    fun `被撤销的 kid 不再验证`() {
        val fixture = Fixtures.signed()
        val registry = KeyRegistry()
        registry.add(fixture.signer.kid, fixture.signer.publicKey()!!)
        assertTrue(verifyWithRegistry(fixture.manifest, registry).ok)

        registry.revoke(fixture.signer.kid)
        assertTrue(registry.isRevoked(fixture.signer.kid))
        assertNull(registry.get(fixture.signer.kid))
        val after = verifyWithRegistry(fixture.manifest, registry)
        assertFalse(after.ok)
        assertTrue(after.reasons.any { it.contains("unknown or revoked key") })
    }

    @Test
    fun `缺少 self 签名时失败`() {
        val fixture = Fixtures.signed()
        val publicKey = fixture.signer.publicKey()!!

        val none = fixture.manifest.copy(signatures = emptyList())
        val noneVerdict = Manifest.verify(none, publicKey)
        assertFalse(noneVerdict.ok)
        assertTrue(noneVerdict.reasons.any { it.contains("missing self signature") })

        val hostOnly = fixture.manifest.copy(
            signatures = listOf(fixture.manifest.signatures.first().copy(role = SignatureRole.HOST)),
        )
        val hostVerdict = Manifest.verify(hostOnly, publicKey)
        assertFalse(hostVerdict.ok)
        assertTrue(hostVerdict.reasons.any { it.contains("missing self signature") })
    }

    @Test
    fun `非法 base64url 签名返回失败而不是伪造通过`() {
        val fixture = Fixtures.signed()
        val broken = fixture.manifest.copy(
            signatures = listOf(fixture.manifest.signatures.first().copy(sig = "!!!")),
        )
        val verdict = Manifest.verify(broken, fixture.signer.publicKey()!!)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("base64url") })
    }

    @Test
    fun `重签会计算新的 digest`() {
        val signer = Fixtures.requireSigner()
        val first = signManifest(Fixtures.unsigned(), signer)
        val second = signManifest(Fixtures.unsigned(expires = "2026-10-26T07:00:00Z"), signer)

        assertFalse(first.digest == second.digest)
        assertTrue(Manifest.verify(first, signer.publicKey()!!).ok)
        assertTrue(Manifest.verify(second, signer.publicKey()!!).ok)
    }

    @Test
    fun `unavailable 是带类型的失败`() {
        val unavailable = VerifyResult.unavailable("ed25519")
        assertFalse(unavailable.ok)
        assertTrue(unavailable.unavailable)
        assertTrue(unavailable.reasons.first().startsWith(VerifyResult.UNAVAILABLE_PREFIX))

        val badSignature = VerifyResult.failure("signature does not verify")
        assertFalse(badSignature.unavailable)
    }
}
