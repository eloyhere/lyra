package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class EnvelopeTest {

    private lateinit var alice: JcaEd25519Signer
    private lateinit var bob: JcaEd25519Signer

    private val aliceRef = Ref(RefKind.AGENT, "a.alice")
    private val bobRef = Ref(RefKind.AGENT, "a.bob")
    private val carolRef = Ref(RefKind.AGENT, "a.carol")

    @Before
    fun setUp() {
        assumeTrue("host JDK lacks Ed25519", Ed25519.isAvailable())
        alice = Ed25519.generate("a.alice#1")!!
        bob = Ed25519.generate("a.bob#1")!!
    }

    @Test
    fun `seal then verify round trip with defaults`() {
        val envelope = sealEnvelope(
            EnvelopeInput(
                from = aliceRef,
                to = bobRef,
                thread = "th.1",
                type = Performative.REQUEST,
                payload = jsonObj("request" to jsonText("act.1")),
            ),
            alice,
        )

        assertEquals("0.1", envelope.toJson().string("amp"))
        assertEquals("envelope", envelope.toJson().string("kind"))
        assertTrue(envelope.id.matches(Regex("^m\\.[0-9a-f]{32}$")))
        assertTrue(envelope.nonce.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(0L, envelope.seq)
        assertEquals(VersionRange("0.1", "0.1"), envelope.version)
        assertEquals("a.alice#1", envelope.kid)
        assertNull(envelope.expires)
        assertTrue(envelope.sig.matches(Regex("^[A-Za-z0-9_-]+$")))

        val verified = verifyEnvelope(
            envelope,
            alice.publicKey(),
            EnvelopeVerifyOptions(now = envelope.sent, expectedVersion = VersionRange("0.1", "0.1")),
        )
        assertTrue(verified.reasons.joinToString("; "), verified.ok)
        assertEquals(emptyList<String>(), verified.reasons)

        val wire = JsonParser.parse(CanonicalJson.encode(envelope.toJson()))
        val wireVerified = verifyEnvelope(wire, alice.publicKey(), EnvelopeVerifyOptions(now = envelope.sent))
        assertTrue(wireVerified.reasons.joinToString("; "), wireVerified.ok)
    }

    @Test
    fun `fixed input produces a deterministic signature and digest`() {
        val first = seal(
            id = "m.deterministic",
            seq = 3,
            nonce = "n.deterministic",
            sent = "2026-10-24T09:59:00.000Z",
            ttlMs = 60_000,
            payload = jsonObj("request" to jsonText("act.deterministic")),
        )
        val second = seal(
            id = "m.deterministic",
            seq = 3,
            nonce = "n.deterministic",
            sent = "2026-10-24T09:59:00.000Z",
            ttlMs = 60_000,
            payload = jsonObj("request" to jsonText("act.deterministic")),
        )

        assertEquals(first.sig, second.sig)
        assertEquals(envelopeDigest(first), envelopeDigest(second))
        assertNotEquals(
            envelopeDigest(first),
            envelopeDigest(seal(id = "m.deterministic", payload = jsonObj("request" to jsonText("act.changed")))),
        )

        val signatureTampered = first.toJson().copyWith("sig", jsonText(second.sig))
        assertEquals(envelopeDigest(first), envelopeDigest(signatureTampered))
    }

    @Test
    fun `ttl computes expires and rejects a negative ttl`() {
        val envelope = seal(id = "m.ttl", ttlMs = 300_000)
        assertEquals("2026-10-24T10:05:00.000Z", envelope.expires)
        assertTrue(verifyEnvelope(envelope, alice.publicKey(), EnvelopeVerifyOptions(now = NOW)).ok)

        assertThrows(IllegalArgumentException::class.java) {
            seal(id = "m.negative", ttlMs = -1)
        }
    }

    @Test
    fun `tampering with any signed field fails the signature check`() {
        val original = seal(id = "m.tamper", ttlMs = 60_000)
        val tampered = listOf(
            "id" to original.toJson().copyWith("id", jsonText("m.tamper.other")),
            "from" to original.toJson().copyWith(
                "from",
                jsonObj("kind" to jsonText("agent"), "id" to jsonText("a.mallory")),
            ),
            "to" to original.toJson().copyWith(
                "to",
                jsonObj("kind" to jsonText("agent"), "id" to jsonText("a.mallory")),
            ),
            "thread" to original.toJson().copyWith("thread", jsonText("th.other")),
            "seq" to original.toJson().copyWith("seq", jsonText("1")),
            "type" to original.toJson().copyWith("type", jsonText("inform")),
            "sent" to original.toJson().copyWith("sent", jsonText("2026-10-24T10:00:01.000Z")),
            "expires" to original.toJson().copyWith("expires", jsonText("2026-10-24T10:10:00.000Z")),
            "version" to original.toJson().copyWith(
                "version",
                jsonObj("min" to jsonText("0.1"), "max" to jsonText("0.2")),
            ),
            "nonce" to original.toJson().copyWith("nonce", jsonText("n.tampered")),
            "payload" to original.toJson().copyWith("payload", jsonObj("request" to jsonText("act.evil"))),
            "kid" to original.toJson().copyWith("kid", jsonText("a.alice#2")),
            "extra field" to original.toJson().copyWith("injected", jsonBool(true)),
        )

        tampered.forEach { (field, envelope) ->
            val result = verifyEnvelope(
                envelope,
                alice.publicKey(),
                EnvelopeVerifyOptions(now = NOW, expectedVersion = VERSION),
            )
            assertFalse("$field tamper must fail", result.ok)
            assertTrue(
                "$field tamper should report a signature failure: ${result.reasons.joinToString("; ")}",
                result.reasons.any { it.contains("signature") },
            )
        }
    }

    @Test
    fun `missing and malformed required fields are invalid never thrown`() {
        val valid = seal(id = "m.required", ttlMs = 60_000)
        listOf("id", "from", "to", "thread", "seq", "type", "sent", "nonce", "version", "payload", "kid", "sig")
            .forEach { field ->
                val result = verifyEnvelope(valid.toJson().without(field), alice.publicKey(), EnvelopeVerifyOptions(now = NOW))
                assertFalse("missing $field must fail", result.ok)
            }

        assertFalse(verifyEnvelope(JsonValue.Str("not-an-object"), alice.publicKey()).ok)

        val malformed = listOf(
            Triple("amp", valid.toJson().copyWith("amp", jsonText("0.2")), "amp"),
            Triple("kind", valid.toJson().copyWith("kind", jsonText("message")), "kind"),
            Triple("type", valid.toJson().copyWith("type", jsonText("subscribe")), "type"),
            Triple("seq", valid.toJson().copyWith("seq", jsonText("-1")), "seq"),
            Triple("nonce", valid.toJson().copyWith("nonce", jsonText("")), "nonce"),
            Triple(
                "version",
                valid.toJson().copyWith("version", jsonObj("min" to jsonText("0.2"), "max" to jsonText("0.1"))),
                "version",
            ),
            Triple(
                "from",
                valid.toJson().copyWith("from", jsonObj("kind" to jsonText("agent"), "id" to jsonText(""))),
                "from",
            ),
            Triple("signature", valid.toJson().copyWith("sig", jsonText("***not-base64url***")), "signature"),
        )

        malformed.forEach { (field, envelope, word) ->
            val result = verifyEnvelope(envelope, alice.publicKey(), EnvelopeVerifyOptions(now = NOW))
            assertFalse("malformed $field must fail", result.ok)
            assertTrue(
                "malformed $field should mention $word: ${result.reasons.joinToString("; ")}",
                result.reasons.any { it.contains(word) },
            )
        }
    }

    @Test
    fun `version negotiation intersects inclusive numeric ranges`() {
        assertEquals(VERSION, negotiateVersion(VERSION, VersionRange("0.1", "0.1")))
        assertNull(negotiateVersion(VERSION, VersionRange("0.2", "0.2")))
        assertEquals(
            VersionRange("0.2", "0.3"),
            negotiateVersion(VersionRange("0.1", "0.3"), VersionRange("0.2", "0.4")),
        )
        assertEquals(
            VersionRange("0.2", "0.2"),
            negotiateVersion(VersionRange("0.1", "0.2"), VersionRange("0.2", "0.2")),
        )
        assertNull(negotiateVersion(VersionRange("0.2", "0.1"), VERSION))
        assertNull(negotiateVersion(VersionRange("0.10", "0.10"), VersionRange("0.9", "0.9")))

        val newer = seal(id = "m.version.2", versionRange = VersionRange("0.2", "0.2"))
        val mismatch = verifyEnvelope(
            newer,
            alice.publicKey(),
            EnvelopeVerifyOptions(now = NOW, expectedVersion = VERSION),
        )
        assertFalse(mismatch.ok)
        assertTrue(mismatch.reasons.any { it.contains("version") })
        assertTrue(
            verifyEnvelope(
                newer,
                alice.publicKey(),
                EnvelopeVerifyOptions(now = NOW, expectedVersion = VersionRange("0.2", "0.2")),
            ).ok,
        )
    }

    @Test
    fun `expiry honours clock skew and an expired envelope burns no nonce`() {
        val envelope = seal(id = "m.expiry", ttlMs = 60_000)
        assertEquals("2026-10-24T10:01:00.000Z", envelope.expires)

        assertTrue(
            verifyEnvelope(envelope, alice.publicKey(), EnvelopeVerifyOptions(now = envelope.expires)).ok,
        )

        val justAfter = verifyEnvelope(
            envelope,
            alice.publicKey(),
            EnvelopeVerifyOptions(now = "2026-10-24T10:01:00.001Z"),
        )
        assertFalse(justAfter.ok)
        assertTrue(justAfter.reasons.any { it.contains("expired") })

        assertTrue(
            verifyEnvelope(
                envelope,
                alice.publicKey(),
                EnvelopeVerifyOptions(now = "2026-10-24T10:01:30.000Z", clockSkewMs = 60_000),
            ).ok,
        )
        assertFalse(
            verifyEnvelope(
                envelope,
                alice.publicKey(),
                EnvelopeVerifyOptions(now = "2026-10-24T10:02:01.000Z", clockSkewMs = 60_000),
            ).ok,
        )

        val seen = HashSet<String>()
        assertFalse(
            verifyEnvelope(
                envelope,
                alice.publicKey(),
                EnvelopeVerifyOptions(now = "2026-10-24T10:01:00.001Z", seenNonces = seen),
            ).ok,
        )
        assertFalse(seen.contains(envelope.nonce))
    }

    @Test
    fun `replay is rejected while a bad signature burns no nonce`() {
        val envelope = seal(id = "m.nonce")
        val seen = HashSet<String>()

        val first = verifyEnvelope(envelope, alice.publicKey(), EnvelopeVerifyOptions(now = NOW, seenNonces = seen))
        assertTrue(first.reasons.joinToString("; "), first.ok)
        assertTrue(seen.contains(envelope.nonce))

        val replay = verifyEnvelope(envelope, alice.publicKey(), EnvelopeVerifyOptions(now = NOW, seenNonces = seen))
        assertFalse(replay.ok)
        assertTrue(replay.reasons.any { it.contains("nonce") })

        val clean = HashSet<String>()
        val wrongKey = verifyEnvelope(envelope, bob.publicKey(), EnvelopeVerifyOptions(now = NOW, seenNonces = clean))
        assertFalse(wrongKey.ok)
        assertTrue(wrongKey.reasons.any { it.contains("signature") })
        assertFalse(clean.contains(envelope.nonce))
    }

    @Test
    fun `receiver dedupes ids and admits seq zero once`() {
        val runtime = runtimeAt()
        val first = seal(id = "m.runtime.0", seq = 0)

        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(first, alice.publicKey()).status)
        assertEquals(EnvelopeStatus.DUPLICATE, runtime.receive(first, alice.publicKey()).status)

        val sameIdAhead = seal(
            id = "m.runtime.0",
            seq = 1,
            nonce = "n.same-id-ahead",
            payload = jsonObj("request" to jsonText("act.duplicate-id")),
        )
        assertEquals(EnvelopeStatus.DUPLICATE, runtime.receive(sameIdAhead, alice.publicKey()).status)
        assertEquals(listOf("m.runtime.0"), runtime.delivered.map { it.id })

        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.runtime.1", seq = 1), alice.publicKey()).status,
        )
        assertEquals(listOf("m.runtime.0", "m.runtime.1"), runtime.delivered.map { it.id })
    }

    @Test
    fun `receiver queues gaps drains in order and treats stale seq as duplicate`() {
        val runtime = runtimeAt()
        val zero = seal(id = "m.order.0", seq = 0)
        val two = seal(id = "m.order.2", seq = 2, payload = jsonObj("request" to jsonText("act.2")))
        val one = seal(id = "m.order.1", seq = 1, payload = jsonObj("request" to jsonText("act.1")))

        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(zero, alice.publicKey()).status)
        assertEquals(EnvelopeStatus.OUT_OF_ORDER, runtime.receive(two, alice.publicKey()).status)
        assertEquals(listOf("m.order.0"), runtime.delivered.map { it.id })
        assertEquals(listOf("m.order.2"), runtime.pending.map { it.id })

        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(one, alice.publicKey()).status)
        assertEquals(listOf("m.order.0", "m.order.1", "m.order.2"), runtime.delivered.map { it.id })
        assertTrue(runtime.pending.isEmpty())

        val stale = runtime.receive(seal(id = "m.order.stale", seq = 1), alice.publicKey())
        assertEquals(EnvelopeStatus.DUPLICATE, stale.status)
        assertTrue(stale.reasons.any { it.contains("stale") })
    }

    @Test
    fun `receiver rejects nonce replay without advancing the sequence`() {
        val runtime = runtimeAt()
        val first = seal(id = "m.replay.0", seq = 0)
        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(first, alice.publicKey()).status)

        val replayed = runtime.receive(
            seal(id = "m.replay.1", seq = 1, nonce = first.nonce),
            alice.publicKey(),
        )
        assertEquals(EnvelopeStatus.INVALID, replayed.status)
        assertTrue(replayed.reasons.any { it.contains("nonce") })
        assertEquals(listOf("m.replay.0"), runtime.delivered.map { it.id })

        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.replay.1.valid", seq = 1), alice.publicKey()).status,
        )
    }

    @Test
    fun `invalid signature expiry and version leave runtime state untouched`() {
        val runtime = runtimeAt()
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.advance.0", seq = 0), alice.publicKey()).status,
        )

        val legitimateOne = seal(id = "m.advance.1", seq = 1)
        val forgedOne = legitimateOne.toJson().copyWith("payload", jsonObj("request" to jsonText("act.forged")))
        assertEquals(EnvelopeStatus.INVALID, runtime.receive(forgedOne, alice.publicKey()).status)
        assertEquals(listOf("m.advance.0"), runtime.delivered.map { it.id })
        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(legitimateOne, alice.publicKey()).status)

        val expiredTwo = seal(
            id = "m.advance.2.expired",
            seq = 2,
            sent = "2026-10-24T09:00:00.000Z",
            ttlMs = 1_000,
        )
        val expired = runtime.receive(expiredTwo, alice.publicKey())
        assertEquals(EnvelopeStatus.INVALID, expired.status)
        assertTrue(expired.reasons.any { it.contains("expired") })
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.advance.2", seq = 2), alice.publicKey()).status,
        )

        val wrongVersion = seal(id = "m.advance.3.version", seq = 3, versionRange = VersionRange("0.2", "0.2"))
        val versionVerdict = runtime.receive(wrongVersion, alice.publicKey())
        assertEquals(EnvelopeStatus.INVALID, versionVerdict.status)
        assertTrue(versionVerdict.reasons.any { it.contains("version") })
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.advance.3", seq = 3), alice.publicKey()).status,
        )
    }

    @Test
    fun `cancel tombstones suppress the target without delivering it`() {
        val runtime = runtimeAt()
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.cancel.0", seq = 0), alice.publicKey()).status,
        )

        val cancel = seal(
            id = "m.cancel.1",
            seq = 1,
            type = Performative.CANCEL,
            payload = jsonObj("targetId" to jsonText("m.target.1")),
        )
        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(cancel, alice.publicKey()).status)

        val target = seal(id = "m.target.1", seq = 2)
        val cancelled = runtime.receive(target, alice.publicKey())
        assertEquals(EnvelopeStatus.CANCELLED, cancelled.status)
        assertFalse(cancelled.ok)
        assertEquals(listOf("m.cancel.0", "m.cancel.1"), runtime.delivered.map { it.id })
        assertEquals(listOf("m.target.1"), runtime.cancelled.map { it.id })
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.cancel.3", seq = 3), alice.publicKey()).status,
        )
    }

    @Test
    fun `cancel accepts the request alias and rejects a missing target`() {
        val runtime = runtimeAt()
        val missing = runtime.receive(
            seal(
                id = "m.cancel.missing",
                seq = 0,
                type = Performative.CANCEL,
                payload = jsonObj(),
            ),
            alice.publicKey(),
        )
        assertEquals(EnvelopeStatus.INVALID, missing.status)
        assertTrue(missing.reasons.any { it.contains("targetId") })

        val cancel = seal(
            id = "m.cancel.alias",
            seq = 0,
            type = Performative.CANCEL,
            payload = jsonObj("request" to jsonText("m.target.alias")),
        )
        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(cancel, alice.publicKey()).status)

        val target = runtime.receive(seal(id = "m.target.alias", seq = 1), alice.publicKey())
        assertEquals(EnvelopeStatus.CANCELLED, target.status)
        assertEquals(listOf("m.target.alias"), runtime.cancelled.map { it.id })
    }

    @Test
    fun `queued target is cancelled when the gap closing cancel is delivered`() {
        val runtime = runtimeAt()
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.q.0", seq = 0), alice.publicKey()).status,
        )

        val target = seal(id = "m.q.target", seq = 2)
        assertEquals(EnvelopeStatus.OUT_OF_ORDER, runtime.receive(target, alice.publicKey()).status)
        assertEquals(listOf("m.q.target"), runtime.pending.map { it.id })

        val cancel = seal(
            id = "m.q.cancel",
            seq = 1,
            type = Performative.CANCEL,
            payload = jsonObj("targetId" to jsonText("m.q.target")),
        )
        assertEquals(EnvelopeStatus.ACCEPTED, runtime.receive(cancel, alice.publicKey()).status)
        assertTrue(runtime.pending.isEmpty())
        assertEquals(listOf("m.q.0", "m.q.cancel"), runtime.delivered.map { it.id })
        assertEquals(listOf("m.q.target"), runtime.cancelled.map { it.id })
    }

    @Test
    fun `runtime applies clock skew`() {
        val envelope = seal(id = "m.skew", seq = 0, ttlMs = 60_000)
        val skewRuntime = runtimeAt(now = "2026-10-24T10:01:30.000Z", clockSkewMs = 60_000)
        assertEquals(EnvelopeStatus.ACCEPTED, skewRuntime.receive(envelope, alice.publicKey()).status)

        val strictRuntime = runtimeAt(now = "2026-10-24T10:01:30.000Z")
        val rejected = strictRuntime.receive(envelope, alice.publicKey())
        assertEquals(EnvelopeStatus.INVALID, rejected.status)
        assertTrue(rejected.reasons.any { it.contains("expired") })
    }

    @Test
    fun `threads and recipient pairs advance independently`() {
        val runtime = runtimeAt()
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.thread.a0", seq = 0, thread = "th.a"), alice.publicKey()).status,
        )
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.thread.b0", seq = 0, thread = "th.b"), alice.publicKey()).status,
        )
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(seal(id = "m.thread.a1", seq = 1, thread = "th.a"), alice.publicKey()).status,
        )
        assertEquals(
            EnvelopeStatus.ACCEPTED,
            runtime.receive(
                seal(id = "m.recipient.carol", seq = 0, thread = "th.a", to = carolRef),
                alice.publicKey(),
            ).status,
        )
        assertEquals(
            listOf("m.thread.a0", "m.thread.b0", "m.thread.a1", "m.recipient.carol"),
            runtime.delivered.map { it.id },
        )
    }

    private fun seal(
        id: String,
        seq: Long = 0,
        nonce: String = "n.$id",
        sent: String = NOW,
        ttlMs: Long? = null,
        type: Performative = Performative.REQUEST,
        payload: JsonValue = jsonObj("request" to jsonText("act.$id")),
        thread: String = "th.1",
        to: Ref = bobRef,
        versionRange: VersionRange = VERSION,
    ): SealedEnvelope = sealEnvelope(
        EnvelopeInput(
            from = aliceRef,
            to = to,
            thread = thread,
            type = type,
            payload = payload,
            id = id,
            seq = seq,
            sent = sent,
            ttlMs = ttlMs,
            nonce = nonce,
            version = versionRange,
        ),
        alice,
    )

    private fun runtimeAt(
        now: String = NOW,
        clockSkewMs: Long? = null,
    ): EnvelopeRuntime = EnvelopeRuntime(
        EnvelopeRuntimeOptions(
            now = { now },
            version = VERSION,
            clockSkewMs = clockSkewMs,
        ),
    )

    companion object {
        private const val NOW = "2026-10-24T10:00:00.000Z"
        private val VERSION = VersionRange("0.1", "0.1")
    }
}
