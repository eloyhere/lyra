package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trust policy gate over a bundle, attestations and a signed agent card. */
class TrustTest {

    private class Fixture(revoked: List<String> = emptyList()) {
        val worker: JcaEd25519Signer = Fixtures.requireSigner("a.worker#1")
        val org: JcaEd25519Signer = Fixtures.requireSigner("org.acme#1")
        val manifest: Manifest = signManifest(Fixtures.unsigned(), worker)
        val card: AgentCard = Fixtures.card()
        val cardSignature: CardSignature = signCard(card, worker)
        val attestation: Attestation = attest(
            id = "at.7",
            issuer = Ref(RefKind.ORG, "org.acme"),
            manifest = manifest,
            scope = listOf("reviewed", "read-only-ok"),
            issued = "2026-10-24T07:05:00Z",
            expires = "2027-01-24T07:05:00Z",
            signer = org,
        )
        val issuerKeys: KeyRegistry = KeyRegistry().apply { add(org.kid, org.publicKey()!!) }
        val bundle: ManifestBundle = exportBundle(
            manifest,
            worker,
            BundleOptions(
                issued = "2026-10-24T07:00:00Z",
                expires = "2026-10-26T07:00:00Z",
                revoked = revoked,
                attestations = listOf(attestation),
            ),
        )
    }

    private fun trust(
        fixture: Fixture,
        scopes: List<String> = listOf("reviewed"),
        issuerKeys: KeyRegistry? = fixture.issuerKeys,
        bundle: ManifestBundle = fixture.bundle,
        now: String? = Fixtures.NOW,
    ): TrustResult = trustCard(
        TrustCardInput(
            card = fixture.card,
            signature = fixture.cardSignature,
            bundle = bundle,
            policy = TrustPolicy(requiredScopes = scopes),
            issuerKeys = issuerKeys,
            now = now,
        ),
    )

    @Test
    fun `trustCard 接受已背书且 scope 满足的卡片`() {
        val fixture = Fixture()
        val verdict = trust(fixture)
        assertTrue("信任失败: ${verdict.reasons.joinToString("; ")}", verdict.ok)
        assertTrue(verdict.scopes.contains("reviewed"))
        assertTrue(verdict.scopes.contains("read-only-ok"))
    }

    @Test
    fun `缺少必需 scope 时拒绝`() {
        val fixture = Fixture()
        val verdict = trust(fixture, scopes = listOf("admin"))
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("missing required scope: admin") })
    }

    @Test
    fun `不受信任的 issuer 键被拒绝而不是静默忽略`() {
        val fixture = Fixture()
        val unknown = trust(fixture, issuerKeys = KeyRegistry())
        assertFalse(unknown.ok)
        assertTrue(unknown.reasons.any { it.contains("unknown issuer key") })

        val absent = trust(fixture, issuerKeys = null)
        assertFalse(absent.ok)
        assertTrue(absent.reasons.any { it.contains("unknown issuer key") })
    }

    @Test
    fun `被撤销的卡片键无法通过信任门`() {
        val fixture = Fixture(revoked = listOf("a.worker#1"))
        val verdict = trust(fixture)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("unknown or revoked key") })
    }

    @Test
    fun `被篡改的 attestation 不再背书`() {
        val fixture = Fixture()
        val tampered = fixture.attestation.copy(scope = listOf("admin"))
        val bundle = fixture.bundle.copy(attestations = listOf(tampered))
        val verdict = trust(fixture, bundle = bundle)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("attestation") && it.contains("does not verify") })
        assertTrue(verdict.reasons.any { it.contains("missing required scope: reviewed") })
    }

    @Test
    fun `非法 now 被拒绝`() {
        val fixture = Fixture()
        val verdict = trust(fixture, now = "not-a-date")
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("now is not a valid timestamp") })
    }

    @Test
    fun `fetchTrustedAgent 结合发现与策略`() {
        val fixture = Fixture()
        val body = CanonicalJson.encode(fixture.card.toSignedJson(listOf(fixture.cardSignature)))
        val fetcher = CardFetcher { _, _, _ -> CardHttpResponse(200, body) }

        val trusted = fetchTrustedAgent(
            "https://worker.example",
            FetchTrustedOptions(
                bundle = fixture.bundle,
                policy = TrustPolicy(listOf("reviewed")),
                issuerKeys = fixture.issuerKeys,
                now = Fixtures.NOW,
            ),
            fetcher,
        )
        assertTrue("信任失败: ${trusted.reasons.joinToString("; ")}", trusted.ok)
        assertEquals("https://worker.example/a2a", trusted.endpoint)
        assertNotNull(trusted.card)
        assertTrue(trusted.scopes.contains("reviewed"))
    }

    @Test
    fun `fetchTrustedAgent 拒绝没有签名的卡片`() {
        val fixture = Fixture()
        val body = CanonicalJson.encode(fixture.card.toJson())
        val fetcher = CardFetcher { _, _, _ -> CardHttpResponse(200, body) }

        val trusted = fetchTrustedAgent(
            "https://worker.example",
            FetchTrustedOptions(
                bundle = fixture.bundle,
                policy = TrustPolicy(listOf("reviewed")),
                issuerKeys = fixture.issuerKeys,
                now = Fixtures.NOW,
            ),
            fetcher,
        )
        assertFalse(trusted.ok)
        assertTrue(trusted.reasons.any { it.contains("card carries no signature") })
    }
}
