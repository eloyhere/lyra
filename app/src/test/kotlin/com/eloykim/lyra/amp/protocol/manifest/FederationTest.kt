package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Federation bundles: export/import, tamper, revocation, expiry, endorsement. */
class FederationTest {

    private class Fixture {
        val worker: JcaEd25519Signer = Fixtures.requireSigner("a.worker#1")
        val org: JcaEd25519Signer = Fixtures.requireSigner("org.acme#1")
        val manifest: Manifest = signManifest(Fixtures.unsigned(), worker)
        val issued: String = "2026-10-24T07:00:00Z"
        val expires: String = "2026-10-26T07:00:00Z"
        val issuerKeys: KeyRegistry = KeyRegistry().apply { add(org.kid, org.publicKey()!!) }

        fun bundle(
            revoked: List<String>? = null,
            attestations: List<Attestation>? = null,
        ): ManifestBundle = exportBundle(
            manifest,
            worker,
            BundleOptions(issued = issued, expires = expires, revoked = revoked, attestations = attestations),
        )

        fun attestation(
            id: String = "at.1",
            subjectDigest: String = manifest.digest,
            signer: JcaEd25519Signer = org,
            scope: List<String> = listOf("reviewed"),
        ): Attestation = attest(
            id = id,
            issuer = Ref(RefKind.ORG, "org.acme"),
            manifest = manifest.copy(digest = subjectDigest),
            scope = scope,
            issued = "2026-10-24T07:05:00Z",
            expires = "2027-01-24T07:05:00Z",
            signer = signer,
        )
    }

    @Test
    fun `仓库中的 federation 示例可被导入`() {
        val examples = JsonParser.parse(Fixtures.example("federation.json")) as JsonValue.Arr
        val bundle = ManifestBundle.fromJson(examples.obj(0)!!)
        val attestation = Attestation.fromJson(examples.obj(1)!!)

        val result = importBundle(bundle, ImportOptions(now = "2026-10-24T09:00:00Z"))
        assertTrue("示例导入失败: ${result.reasons.joinToString("; ")}", result.ok)
        assertEquals(bundle.manifest.digest, attestation.subject.digest)
        assertEquals(listOf(attestation.id), bundle.attestations?.map { it.id })
        assertTrue(AmpSchemas.validateKind("federation", attestation.toJson()).isEmpty())
    }

    @Test
    fun `导出后可导入且 bundle 与 attestation 通过 schema`() {
        val fixture = Fixture()
        val attestation = fixture.attestation()

        // 共享 schema validator 目前不能解析 `#/oneOf/1` 这类数组指针，因此
        // bundle 用空 attestations 验证外形，内嵌 attestation 单独作为根对象验证。
        val bundleProblems = AmpSchemas.validateKind(
            "federation",
            fixture.bundle(attestations = emptyList()).toJson(),
        )
        assertTrue("bundle schema 错误: ${bundleProblems.joinToString("; ")}", bundleProblems.isEmpty())
        val attestationProblems = AmpSchemas.validateKind("federation", attestation.toJson())
        assertTrue("attestation schema 错误: ${attestationProblems.joinToString("; ")}", attestationProblems.isEmpty())
        val nested = fixture.bundle(attestations = listOf(attestation))
        assertEquals(listOf(attestation.id), nested.attestations?.map { it.id })

        val plain = importBundle(fixture.bundle(), ImportOptions(now = Fixtures.NOW))
        assertTrue("导入失败: ${plain.reasons.joinToString("; ")}", plain.ok)
        assertEquals(fixture.manifest.digest, plain.manifest?.digest)
        assertFalse(plain.attested)
        assertTrue(plain.attestations.isEmpty())
    }

    @Test
    fun `篡改 keys 会破坏 bundle digest`() {
        val fixture = Fixture()
        val bundle = fixture.bundle()
        val tampered = bundle.copy(keys = listOf(bundle.keys.first().copy(publicKey = "AAAA")))

        val result = importBundle(tampered, ImportOptions(now = Fixtures.NOW))
        assertFalse(result.ok)
        assertTrue(result.reasons.any { it.contains("digest") })
    }

    @Test
    fun `篡改 manifest 并重建 digest 仍失败签名`() {
        val fixture = Fixture()
        val bundle = fixture.bundle()
        val tamperedManifest = bundle.manifest.copy(agent = bundle.manifest.agent.copy(role = "admin"))
        val forgedDigest = bundleDigest(
            tamperedManifest,
            bundle.keys,
            bundle.revoked.orEmpty(),
            bundle.issued,
            bundle.expires,
        )
        val forged = bundle.copy(manifest = tamperedManifest, digest = forgedDigest)

        val result = importBundle(forged, ImportOptions(now = Fixtures.NOW))
        assertFalse(result.ok)
        assertTrue(result.reasons.any { it.contains("signature") || it.contains("digest") })
    }

    @Test
    fun `被撤销的键即使签名有效也被拒绝`() {
        val fixture = Fixture()
        val fromBundle = importBundle(
            fixture.bundle(revoked = listOf(fixture.worker.kid)),
            ImportOptions(now = Fixtures.NOW),
        )
        assertFalse(fromBundle.ok)
        assertTrue(fromBundle.reasons.any { it.contains("revoked") })

        val locallyRevoked = importBundle(
            fixture.bundle(),
            ImportOptions(now = Fixtures.NOW, revoked = listOf(fixture.worker.kid)),
        )
        assertFalse(locallyRevoked.ok)
        assertTrue(locallyRevoked.reasons.any { it.contains("revoked") })
    }

    @Test
    fun `过期 bundle 与未来签发的 bundle 失败`() {
        val fixture = Fixture()
        val expired = exportBundle(
            fixture.manifest,
            fixture.worker,
            BundleOptions(issued = fixture.issued, expires = "2026-10-24T08:00:00Z"),
        )
        val expiredResult = importBundle(expired, ImportOptions(now = Fixtures.NOW))
        assertFalse(expiredResult.ok)
        assertTrue(expiredResult.reasons.any { it.contains("expired") })

        val future = exportBundle(
            fixture.manifest,
            fixture.worker,
            BundleOptions(issued = "2026-10-24T10:00:00Z", expires = fixture.expires),
        )
        val futureResult = importBundle(future, ImportOptions(now = Fixtures.NOW))
        assertFalse(futureResult.ok)
        assertTrue(futureResult.reasons.any { it.contains("future") })
    }

    @Test
    fun `attestation 必须绑定 digest 且由可信 issuer 键签名`() {
        val fixture = Fixture()
        val good = fixture.attestation()
        assertTrue(verifyAttestationWithKey(good, fixture.org.publicKey()!!).ok)

        val accepts = importBundle(
            fixture.bundle(attestations = listOf(good)),
            ImportOptions(now = Fixtures.NOW, issuerKeys = fixture.issuerKeys),
        )
        assertTrue("导入失败: ${accepts.reasons.joinToString("; ")}", accepts.ok)
        assertTrue(accepts.attested)
        assertEquals(listOf("at.1"), accepts.attestations)

        val noTrustRoot = importBundle(
            fixture.bundle(attestations = listOf(good)),
            ImportOptions(now = Fixtures.NOW),
        )
        assertTrue(noTrustRoot.ok)
        assertFalse("bundle 内容不是 issuer 的信任根", noTrustRoot.attested)

        val wrongSubject = fixture.attestation(subjectDigest = "sha256:${"1".repeat(64)}")
        val rejects = importBundle(
            fixture.bundle(attestations = listOf(wrongSubject)),
            ImportOptions(now = Fixtures.NOW, issuerKeys = fixture.issuerKeys),
        )
        assertTrue(rejects.ok)
        assertFalse(rejects.attested)

        val impostorKid = Fixtures.requireSigner("org.acme#1")
        val wrongKey = fixture.attestation(signer = impostorKid)
        val forged = importBundle(
            fixture.bundle(attestations = listOf(wrongKey)),
            ImportOptions(now = Fixtures.NOW, issuerKeys = fixture.issuerKeys),
        )
        assertTrue(forged.ok)
        assertFalse("错误密钥签的 attestation 不得背书", forged.attested)
    }

    @Test
    fun `缺失签名键被具体报告`() {
        val fixture = Fixture()
        val stripped = fixture.bundle().copy(keys = emptyList())
        val result = importBundle(stripped, ImportOptions(now = Fixtures.NOW))
        assertFalse(result.ok)
        assertTrue(result.reasons.any { it.contains("missing key") })
    }

    @Test
    fun `同一 bundle 重复导入幂等`() {
        val fixture = Fixture()
        val bundle = fixture.bundle()
        val first = importBundle(bundle, ImportOptions(now = Fixtures.NOW))
        val second = importBundle(bundle, ImportOptions(now = Fixtures.NOW))
        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(first.manifest?.digest, second.manifest?.digest)
    }

    @Test
    fun `registryFromBundle 导入键并保留撤销`() {
        val fixture = Fixture()
        val registry = registryFromBundle(fixture.bundle())
        assertNotNull(registry.get(fixture.worker.kid))
        assertFalse(registry.isRevoked(fixture.worker.kid))

        val revokedRegistry = registryFromBundle(fixture.bundle(revoked = listOf(fixture.worker.kid)))
        assertNull(revokedRegistry.get(fixture.worker.kid))
        assertTrue(revokedRegistry.isRevoked(fixture.worker.kid))
    }
}
