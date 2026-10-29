package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

class CheckpointTest {

    private fun exampleSnapshot(): LedgerSnapshot = LedgerSnapshot.fromJson(
        JsonParser.parse(
            File("src/test/resources/amp/examples/ledger-snapshot.json").readText(),
        ),
    )

    private fun newSigner(): JcaEd25519Signer {
        Assume.assumeTrue("当前 JVM 没有 Ed25519 provider", Ed25519.isAvailable())
        return Ed25519.generate("k.test") ?: error("Ed25519 密钥生成失败")
    }

    private fun seal(
        snapshot: LedgerSnapshot,
        signer: JcaEd25519Signer,
        issued: String = "2026-10-24T02:10:00Z",
        previous: LedgerCheckpoint? = null,
    ): LedgerCheckpoint = sealCheckpoint(
        snapshot,
        signer,
        CheckpointOptions(tenant = "t.lyra", issued = issued, previous = previous),
    )

    @Test
    fun `checkpoint body 是排除 sig 的 canonical JSON`() {
        val checkpoint = LedgerCheckpoint(
            tenant = "t.lyra",
            agent = "a.gamma",
            seq = 0,
            count = 3,
            tip = "sha256:14d774949ecf4c71eb442334db7e8cfca44dd93562e37991279e27f4751fd2f4",
            issued = "2026-10-24T02:10:00Z",
            kid = "k.test",
            sig = "sig-is-excluded",
        )
        assertEquals(
            """
            {"agent":"a.gamma","amp":"0.1","count":3,"issued":"2026-10-24T02:10:00Z","kid":"k.test","kind":"ledger-checkpoint","seq":0,"tenant":"t.lyra","tip":"sha256:14d774949ecf4c71eb442334db7e8cfca44dd93562e37991279e27f4751fd2f4"}
            """.trimIndent(),
            checkpointBody(checkpoint),
        )
        assertEquals(
            "sha256:b8e5a8f853a25c8ebf3dd7874ec73eeb93128ee5d7de507e733a2d44f9363b7e",
            checkpointDigest(checkpoint),
        )
        assertTrue(checkpointBody(checkpoint).contains("\"prev\"") == false)
    }

    @Test
    fun `sealCheckpoint 生成可验证的 base64url 签名`() {
        val signer = newSigner()
        val snapshot = exampleSnapshot()
        val checkpoint = seal(snapshot, signer)

        assertEquals("0.1", checkpoint.amp)
        assertEquals("ledger-checkpoint", checkpoint.kind)
        assertEquals(0L, checkpoint.seq)
        assertEquals(3L, checkpoint.count)
        assertEquals(snapshot.tip, checkpoint.tip)
        assertEquals("k.test", checkpoint.kid)
        assertNull(checkpoint.prev)
        assertEquals(64, Base64Url.decode(checkpoint.sig).size)
        assertTrue(verifyCheckpoint(checkpoint, signer.publicKey()).ok)

        val roundTripped = LedgerCheckpoint.fromJson(checkpoint.toJson())
        assertEquals(
            CanonicalJson.encode(checkpoint.toJson()),
            CanonicalJson.encode(roundTripped.toJson()),
        )
        assertTrue(verifyCheckpoint(roundTripped, signer.publicKey()).ok)
    }

    @Test
    fun `verifyCheckpoint 拒绝被篡改的字段`() {
        val signer = newSigner()
        val checkpoint = seal(exampleSnapshot(), signer)

        val tamperedTip = verifyCheckpoint(
            checkpoint.copy(tip = "sha256:" + "f".repeat(64)),
            signer.publicKey(),
        )
        assertFalse(tamperedTip.ok)
        assertTrue(tamperedTip.reasons.contains("signature does not verify"))

        assertTrue(
            verifyCheckpoint(checkpoint.copy(tenant = ""), signer.publicKey())
                .reasons.contains("tenant is empty"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(amp = "0.2"), signer.publicKey())
                .reasons.contains("unsupported checkpoint amp version"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(kind = "frame"), signer.publicKey())
                .reasons.contains("not a ledger checkpoint"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(seq = -1L), signer.publicKey())
                .reasons.contains("seq is not a non-negative integer"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(tip = "not-a-digest"), signer.publicKey())
                .reasons.contains("tip is not a sha256 digest"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(prev = "nope"), signer.publicKey())
                .reasons.contains("prev is not a sha256 digest"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(sig = ""), signer.publicKey())
                .reasons.contains("signature is empty"),
        )
        assertTrue(
            verifyCheckpoint(checkpoint.copy(sig = "not base64!"), signer.publicKey())
                .reasons.contains("signature is not base64url"),
        )

        val other = Ed25519.generate("k.other") ?: error("Ed25519 密钥生成失败")
        val wrongKey = verifyCheckpoint(checkpoint, other.publicKey())
        assertFalse(wrongKey.ok)
        assertTrue(wrongKey.reasons.contains("signature does not verify"))
    }

    @Test
    fun `checkpoint 链承诺前一个 checkpoint 的 digest`() {
        val signer = newSigner()
        val snapshot = exampleSnapshot()
        val first = seal(snapshot, signer, issued = "2026-10-24T02:10:00Z")
        val second = seal(snapshot, signer, issued = "2026-10-24T02:11:00Z", previous = first)

        assertEquals(1L, second.seq)
        assertEquals(checkpointDigest(first), second.prev)
        assertTrue(verifyCheckpoint(second, signer.publicKey(), previous = first).ok)
        assertTrue(verifyCheckpoint(second, signer.publicKey()).ok)

        val backwards = verifyCheckpoint(first, signer.publicKey(), previous = second)
        assertFalse(backwards.ok)
        assertTrue(backwards.reasons.contains("seq 0 does not continue from 1"))

        val missing = verifyCheckpoint(second.copy(prev = null), signer.publicKey(), previous = first)
        assertTrue(missing.reasons.contains("missing prev link to the previous checkpoint"))

        val wrong = verifyCheckpoint(
            second.copy(prev = "sha256:" + "d".repeat(64)),
            signer.publicKey(),
            previous = first,
        )
        assertTrue(wrong.reasons.contains("prev does not match the previous checkpoint digest"))
    }

    @Test
    fun `verifySnapshotAgainstCheckpoint 同时检查声明与链`() {
        val signer = newSigner()
        val snapshot = exampleSnapshot()
        val checkpoint = seal(snapshot, signer)

        assertTrue(verifySnapshotAgainstCheckpoint(snapshot, checkpoint).ok)

        val wrongCount = verifySnapshotAgainstCheckpoint(snapshot.copy(count = 2), checkpoint)
        assertFalse(wrongCount.ok)
        assertTrue(
            wrongCount.reasons.contains("snapshot count 2 does not match checkpoint count 3"),
        )

        val wrongAgent = verifySnapshotAgainstCheckpoint(snapshot.copy(agent = "a.other"), checkpoint)
        assertFalse(wrongAgent.ok)
        assertTrue(
            wrongAgent.reasons.contains("snapshot agent a.other does not match checkpoint agent a.gamma"),
        )
    }
}
