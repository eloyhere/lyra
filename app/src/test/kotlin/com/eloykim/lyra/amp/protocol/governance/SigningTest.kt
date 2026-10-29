package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.types.Id
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey

class SigningTest {

    private fun publicKey(): PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public

    private fun signedBundle(signers: List<GovernanceSigner> = generateGovernanceSigners(listOf("legal", "finance"))): Pair<GovernanceSignatureBundle, GovernanceKeyring> {
        val digest = digestOfText("manifest-body")
        val bundle = signGovernanceObject(
            subject = GovernanceSubject.ATTENTION_MANIFEST,
            subjectId = "amn.1",
            subjectVersion = 3,
            digest = digest,
            signers = signers,
        )
        val keyring = GovernanceKeyring()
        signers.forEach { keyring.add(it.signer, it.publicKey()!!, kid = it.kid) }
        return bundle to keyring
    }

    private fun expectation(
        subject: GovernanceSubject = GovernanceSubject.ATTENTION_MANIFEST,
        subjectId: String = "amn.1",
        version: Int? = 3,
        digest: String = digestOfText("manifest-body"),
    ): GovernanceExpectation = GovernanceExpectation(subject, subjectId, version, digest)

    @Test
    fun `签名绑定主体版本与摘要`() {
        assumeTrue(Ed25519.isAvailable())
        val (bundle, keyring) = signedBundle()
        assertEquals("governance-signatures", bundle.kind)
        assertEquals(GovernanceSubject.ATTENTION_MANIFEST, bundle.subject)
        assertEquals(2, bundle.signatures.size)
        assertEquals("gov.legal", bundle.signatures[0].kid)
        assertEquals("ed25519", bundle.signatures[0].alg)
        assertEquals(bundle.subjectDigest, bundle.signatures[0].digest)
        assertTrue(
            verifyGovernanceBundle(
                bundle,
                expectation(),
                keyring,
                requiredSigners = listOf("legal", "finance"),
            ).ok,
        )

        val parsed = GovernanceSignatureBundle.fromJson(bundle.toJson())
        assertEquals(bundle, parsed)
        assertTrue(verifyGovernanceBundle(parsed, expectation(), keyring).ok)
    }

    @Test
    fun `签名不可移植到别的主体`() {
        assumeTrue(Ed25519.isAvailable())
        val (bundle, keyring) = signedBundle()

        val otherSubject = verifyGovernanceBundle(
            bundle,
            expectation(subject = GovernanceSubject.SWITCH_CREDENTIAL),
            keyring,
        )
        assertFalse(otherSubject.ok)
        assertTrue(otherSubject.reasons.contains("subject mismatch: attention-manifest"))

        val otherId = verifyGovernanceBundle(bundle, expectation(subjectId = "amn.2"), keyring)
        assertTrue(otherId.reasons.contains("subject id mismatch: amn.1"))

        val otherVersion = verifyGovernanceBundle(bundle, expectation(version = 4), keyring)
        assertTrue(otherVersion.reasons.contains("subject version mismatch"))

        val otherDigest = verifyGovernanceBundle(bundle, expectation(digest = digestOfText("other")), keyring)
        assertTrue(otherDigest.reasons.contains("bundle digest does not match the subject"))
        assertTrue(otherDigest.reasons.contains("signature covers a different digest"))

        val noVersion = verifyGovernanceBundle(bundle, expectation(version = null), keyring)
        assertTrue(noVersion.reasons.contains("subject version mismatch"))
    }

    @Test
    fun `重复签署与缺失签署者被拒绝`() {
        assumeTrue(Ed25519.isAvailable())
        val signers = generateGovernanceSigners(listOf("legal", "finance"))
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            signGovernanceObject(
                GovernanceSubject.ATTENTION_MANIFEST,
                "amn.1",
                1,
                digestOfText("x"),
                emptyList(),
            )
        }
        assertGovernanceError(GovernanceCode.MANIFEST_UNSIGNED) {
            signGovernanceObject(
                GovernanceSubject.ATTENTION_MANIFEST,
                "amn.1",
                1,
                digestOfText("x"),
                listOf(signers[0], signers[1], signers[0]),
            )
        }

        val (bundle, keyring) = signedBundle(signers)
        val duplicated = bundle.copy(signatures = listOf(bundle.signatures[0], bundle.signatures[0]))
        val verdict = verifyGovernanceBundle(duplicated, expectation(), keyring)
        assertTrue(verdict.reasons.contains("legal signed twice"))

        val missing = verifyGovernanceBundle(
            bundle,
            expectation(),
            keyring,
            requiredSigners = listOf("legal", "board"),
        )
        assertTrue(missing.reasons.contains("missing required signer board"))
    }

    @Test
    fun `算法与签名被篡改时验证失败`() {
        assumeTrue(Ed25519.isAvailable())
        val (bundle, keyring) = signedBundle()

        val wrongAlg = bundle.copy(
            signatures = listOf(bundle.signatures[0].copy(alg = "rsa")),
        )
        assertTrue(
            verifyGovernanceBundle(wrongAlg, expectation(), keyring).reasons.contains("unsupported algorithm rsa"),
        )

        val zeroed = bundle.copy(
            signatures = listOf(bundle.signatures[0].copy(sig = Base64Url.encode(ByteArray(64)))),
        )
        assertTrue(
            verifyGovernanceBundle(zeroed, expectation(), keyring).reasons.contains("signature does not verify"),
        )

        val badBase64 = bundle.copy(
            signatures = listOf(bundle.signatures[0].copy(sig = "not base64!")),
        )
        assertTrue(
            verifyGovernanceBundle(badBase64, expectation(), keyring)
                .reasons.contains("signature is not valid base64url"),
        )
    }

    @Test
    fun `撤销的签署者失败关闭`() {
        assumeTrue(Ed25519.isAvailable())
        val (bundle, keyring) = signedBundle()
        keyring.revoke("legal")
        val revoked = verifyGovernanceBundle(bundle, expectation(), keyring)
        assertFalse(revoked.ok)
        assertTrue(revoked.reasons.contains("unknown or revoked signer legal"))

        val (otherBundle, otherKeyring) = signedBundle()
        val legal = otherBundle.signatures.first { it.signer == "legal" }
        otherKeyring.revokeKid("legal", legal.kid)
        assertTrue(
            verifyGovernanceBundle(otherBundle, expectation(), otherKeyring)
                .reasons.contains("unknown or revoked signer legal"),
        )
        assertEquals(emptyList<PublicKey>(), otherKeyring.candidates("legal", legal.kid))
    }

    @Test
    fun `密钥轮换保留重叠窗口`() {
        var now = 1_000L
        val events = ArrayList<KeyringEvent>()
        val keyring = GovernanceKeyring(onEvent = { events += it }, clock = { now })
        val first = publicKey()
        val second = publicKey()

        keyring.add("legal", first, kid = "gov.legal", at = 0L)
        assertEquals(listOf(first), keyring.candidates("legal"))
        assertEquals(first, keyring.get("legal", "gov.legal"))

        keyring.rotate("legal", second, kid = "gov.legal.2", at = 100L, overlapMs = 50L)
        assertEquals(listOf(first, second), keyring.candidates("legal", at = 120L))
        assertEquals(listOf(first), keyring.candidates("legal", kid = "gov.legal", at = 120L))
        assertEquals(listOf(second), keyring.candidates("legal", at = 151L))
        // 指定 kid 已退役时回退到全部候选。
        assertEquals(listOf(second), keyring.candidates("legal", kid = "gov.legal", at = 151L))

        keyring.prune(200L)
        assertEquals(listOf(second), keyring.candidates("legal", at = 200L))
        assertEquals(
            listOf(KeyringAction.ADD, KeyringAction.ROTATE, KeyringAction.PRUNE),
            events.map { it.action },
        )
        assertEquals("", events[2].signer)
        assertEquals(200L, events[2].at)
    }

    @Test
    fun `撤销按签署者或 kid 生效`() {
        var now = 5_000L
        val events = ArrayList<KeyringEvent>()
        val keyring = GovernanceKeyring(onEvent = { events += it }, clock = { now })
        keyring.add("legal", publicKey(), kid = "gov.legal", at = 0L)
        keyring.add("legal", publicKey(), kid = "gov.legal.2", at = 0L)

        keyring.revokeKid("legal", "gov.legal")
        assertEquals(1, keyring.candidates("legal").size)
        // 指定 kid 已撤销时回退到剩余候选。
        assertEquals(1, keyring.candidates("legal", kid = "gov.legal").size)

        keyring.revoke("legal")
        assertTrue(keyring.candidates("legal").isEmpty())
        assertNull(keyring.get("legal"))
        assertEquals(
            listOf(KeyringAction.ADD, KeyringAction.ADD, KeyringAction.REVOKE_KID, KeyringAction.REVOKE),
            events.map { it.action },
        )
        assertEquals(5_000L, events[3].at)
    }

    @Test
    fun `生成签署者使用 kid 前缀`() {
        assumeTrue(Ed25519.isAvailable())
        val signers = generateGovernanceSigners(listOf("legal", "finance"), kidPrefix = "org")
        assertEquals(listOf("org.legal", "org.finance"), signers.map { it.kid })
        assertEquals(listOf("legal", "finance"), signers.map { it.signer })
        assertEquals(signers[0].kid, signers[0].key.kid)
        assertNotNull(signers[0].publicKey())
    }

    @Test
    fun `任意规范值签名可验证`() {
        assumeTrue(Ed25519.isAvailable())
        val key = Ed25519.generate("gov.audit")!!
        val value = JsonParser.parse("""{"kind":"audit-checkpoint","sequence":7}""")
        val signature = signCanonicalValue(value, key)
        assertTrue(verifyCanonicalValue(value, signature, key.publicKey()!!))
        assertFalse(verifyCanonicalValue(JsonParser.parse("""{"kind":"other"}"""), signature, key.publicKey()!!))
        assertFalse(verifyCanonicalValue(value, "not base64!", key.publicKey()!!))
    }

    @Test
    fun `单签名对象带 kid 与摘要`() {
        assumeTrue(Ed25519.isAvailable())
        val signer = generateGovernanceSigners(listOf("legal")).first()
        val digest = digestOfText("credential-body")
        val signature = signGovernanceDigest(
            signer = signer,
            subject = GovernanceSubject.SWITCH_CREDENTIAL,
            subjectId = "crd.1",
            digest = digest,
        )
        assertEquals("legal", signature.signer)
        assertEquals("gov.legal", signature.kid)
        assertEquals(digest, signature.digest)
        assertEquals(64, Base64Url.decode(signature.sig).size)

        val keyring = GovernanceKeyring()
        keyring.add("legal", signer.publicKey()!!, kid = signer.kid)
        assertTrue(
            verifyGovernanceSignature(
                signature,
                GovernanceSubject.SWITCH_CREDENTIAL,
                "crd.1",
                null,
                digest,
                keyring,
            ).ok,
        )
        assertFalse(
            verifyGovernanceSignature(
                signature,
                GovernanceSubject.SWITCH_CREDENTIAL,
                "crd.2",
                null,
                digest,
                keyring,
            ).ok,
        )
    }

    @Test
    fun `无 kid 的公钥仍可作为候选`() {
        val keyring = GovernanceKeyring(clock = { 0L })
        val key = publicKey()
        keyring.add("legal", key)
        assertEquals(listOf(key), keyring.candidates("legal"))
        assertEquals(key, keyring.get("legal"))
        // 指定不存在的 kid 时回退到全部候选。
        assertEquals(listOf(key), keyring.candidates("legal", kid = "gov.unknown"))
        assertTrue(keyring.candidates("finance").isEmpty())
    }
}
