package com.eloykim.lyra.amp.protocol.storage

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import com.eloykim.lyra.amp.protocol.ledger.checkpointDigest
import com.eloykim.lyra.amp.protocol.ledger.verifyCheckpoint
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class AppendLogTest {

    private val agent = "a.checkpoint"
    private val tenant = "tenant.alpha"
    private val issued = "2026-10-26T00:00:00Z"
    private val genesis = "sha256:" + "0".repeat(64)

    private fun seeded(): Pair<MemoryAppendSink, AppendLog> {
        val sink = MemoryAppendSink()
        val log = AppendLog.open(sink, agent)
        log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.1"), "at" to jsonText(issued)))
        log.append(
            EntryKind.CLAIM,
            jsonObj("id" to jsonText("c.1"), "statement" to jsonText("p"), "tier" to jsonText("T2")),
        )
        log.append(EntryKind.RECEIPT, jsonObj("id" to jsonText("r.1"), "outcome" to jsonText("ok")))
        return sink to log
    }

    private fun expectedHash(entry: LogEntry): String = CanonicalJson.digest(
        jsonObj(
            "seq" to jsonNumber(entry.seq),
            "kind" to jsonText(entry.kind.wire),
            "payload" to entry.payload,
            "prev" to jsonText(entry.prev),
        ),
    )

    private fun mutateLine(
        lines: List<String>,
        index: Int,
        mutate: (JsonValue.Obj) -> JsonValue.Obj,
    ): List<String> {
        val copy = ArrayList(lines)
        val record = JsonParser.parse(copy[index]) as JsonValue.Obj
        copy[index] = CanonicalJson.encode(mutate(record))
        return copy
    }

    private fun rejects(lines: List<String>): AppendLogError {
        val error = assertThrows(AppendLogError::class.java) {
            AppendLog.open(MemoryAppendSink(lines), agent)
        }
        assertTrue("expected at least one reason", error.reasons.isNotEmpty())
        return error
    }

    private fun key(): JcaEd25519Signer {
        Assume.assumeTrue("当前 JVM 没有 Ed25519 provider", Ed25519.isAvailable())
        return Ed25519.generate("a.checkpoint#1") ?: error("Ed25519 密钥生成失败")
    }

    @Test
    fun `append builds a chain that matches persistence hashing`() {
        val (_, log) = seeded()

        assertEquals(3, log.count)
        assertTrue(log.verify().ok)
        val entries = log.entries()
        assertEquals(listOf(0, 1, 2), entries.map { it.seq })
        assertEquals(genesis, entries[0].prev)
        assertEquals(entries[0].hash, entries[1].prev)
        assertEquals(entries[1].hash, entries[2].prev)
        entries.forEach { entry -> assertEquals(expectedHash(entry), entry.hash) }
        assertEquals(entries[2].hash, log.tip)
        assertEquals(genesis, AppendLog.open(MemoryAppendSink()).tip)
    }

    @Test
    fun `at metadata round-trips but stays outside the hash`() {
        val sink = MemoryAppendSink()
        val log = AppendLog.open(sink, agent)
        val entry = log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.1")), at = issued)
        assertEquals(issued, entry.at)

        val reopened = AppendLog.open(sink, agent)
        assertEquals(issued, reopened.entries()[0].at)

        val lines = mutateLine(sink.readAll(), 0) { record ->
            record.copyWith("at", jsonText("1999-01-01T00:00:00Z"))
        }
        val tampered = AppendLog.open(MemoryAppendSink(lines), agent)
        assertTrue(tampered.verify().ok)
        assertEquals(entry.hash, tampered.tip)
    }

    @Test
    fun `payloads are normalized and copied before hashing`() {
        val sink = MemoryAppendSink()
        val log = AppendLog.open(sink, agent)
        val source = jsonObj("id" to jsonText("ev.1"))
        val entry = log.append(
            EntryKind.EVENT,
            jsonObj(
                "id" to jsonText("ev.1"),
                "nan" to JsonValue.Num(Double.NaN),
                "infinite" to JsonValue.Num(Double.POSITIVE_INFINITY),
                "negativeZero" to JsonValue.Num(-0.0),
            ),
        )

        val payload = entry.payload as JsonValue.Obj
        assertEquals(JsonValue.Null, payload["nan"])
        assertEquals(JsonValue.Null, payload["infinite"])
        assertEquals(0.0, (payload["negativeZero"] as JsonValue.Num).value, 0.0)

        source.fields["id"] = jsonText("forged")
        assertEquals("ev.1", (entry.payload as JsonValue.Obj).string("id"))

        val reopened = AppendLog.open(sink, agent)
        assertTrue(reopened.verify().ok)
        assertEquals(entry.hash, reopened.tip)
        assertEquals(JsonValue.Null, (reopened.entries()[0].payload as JsonValue.Obj)["nan"])
    }

    @Test
    fun `reopening the same sink restores count and tip`() {
        val (sink, log) = seeded()
        val reopened = AppendLog.open(sink, agent)

        assertEquals(log.count, reopened.count)
        assertEquals(log.tip, reopened.tip)
        assertEquals(log.entries(), reopened.entries())
        assertTrue(reopened.verify().ok)
    }

    @Test
    fun `appending after reopen continues the sequence`() {
        val (sink, log) = seeded()
        val reopened = AppendLog.open(sink, agent)
        val next = reopened.append(EntryKind.FRAME, jsonObj("id" to jsonText("f.1")))

        assertEquals(3, next.seq)
        assertEquals(log.tip, next.prev)
        val again = AppendLog.open(sink, agent)
        assertEquals(4, again.count)
        assertEquals(next.hash, again.tip)
        assertTrue(again.verify().ok)
    }

    @Test
    fun `a failed sink write leaves count and tip unchanged`() {
        val sink = FlakySink()
        val log = AppendLog.open(sink, agent)
        val first = log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.0")))
        val beforeTip = log.tip

        sink.failNext = true
        val error = assertThrows(IllegalStateException::class.java) {
            log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.1")))
        }
        assertTrue(error.message!!.contains("sink write failed"))
        assertEquals(1, log.count)
        assertEquals(beforeTip, log.tip)
        assertEquals(1, sink.readAll().size)

        val retried = log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.1")))
        assertEquals(1, retried.seq)
        assertEquals(first.hash, retried.prev)
        assertEquals(2, log.count)
        assertTrue(log.verify().ok)
    }

    @Test
    fun `tampered payloads, hashes, seqs and prevs fail open`() {
        val sink = seeded().first
        val lines = sink.readAll()

        val payloadTamper = mutateLine(lines, 1) { record ->
            record.copyWith(
                "payload",
                jsonObj("id" to jsonText("c.1"), "statement" to jsonText("forged"), "tier" to jsonText("T2")),
            )
        }
        assertTrue(rejects(payloadTamper).reasons.any { it.contains("hash") })

        val hashTamper = mutateLine(lines, 1) { record ->
            record.copyWith("hash", jsonText("sha256:" + "f".repeat(64)))
        }
        assertTrue(rejects(hashTamper).reasons.any { it.contains("hash") })

        val seqTamper = mutateLine(lines, 1) { record -> record.copyWith("seq", jsonNumber(7)) }
        assertTrue(rejects(seqTamper).reasons.any { it.contains("seq") })

        val prevTamper = mutateLine(lines, 1) { record ->
            record.copyWith("prev", jsonText(genesis))
        }
        assertTrue(rejects(prevTamper).reasons.any { it.contains("prev") })
    }

    @Test
    fun `reordered, truncated and malformed lines fail open`() {
        val sink = seeded().first
        val lines = sink.readAll()

        val reordered = listOf(lines[1], lines[0], lines[2])
        assertTrue(rejects(reordered).reasons.any { it.contains("seq") || it.contains("prev") })

        val dropped = lines.filterIndexed { index, _ -> index != 1 }
        assertTrue(rejects(dropped).reasons.any { it.contains("seq") || it.contains("prev") })

        val invalidJson = ArrayList(lines).also { it[0] = "{not json" }
        assertTrue(rejects(invalidJson).reasons.any { it.contains("JSON") })

        val emptyLine = lines + ""
        assertTrue(rejects(emptyLine).reasons.any { it.contains("empty") })

        val missingKind = mutateLine(lines, 0) { record -> record.without("kind") }
        assertTrue(rejects(missingKind).reasons.any { it.contains("kind") })

        val missingPayload = mutateLine(lines, 0) { record -> record.without("payload") }
        assertTrue(rejects(missingPayload).reasons.any { it.contains("payload") })

        val badAt = mutateLine(lines, 0) { record -> record.copyWith("at", JsonValue.Null) }
        assertTrue(rejects(badAt).reasons.any { it.contains("at") })
    }

    @Test
    fun `verify fails closed after an in-memory mutation`() {
        val (_, log) = seeded()
        log.entries()[0].payload = jsonObj("id" to jsonText("ev.1"), "forged" to JsonValue.Bool(true))

        val verdict = log.verify()
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("hash") })
    }

    @Test
    fun `an empty log opens at genesis`() {
        val log = AppendLog.open(MemoryAppendSink(), agent)
        assertEquals(0, log.count)
        assertEquals(genesis, log.tip)
        assertEquals(emptyList<LogEntry>(), log.entries())
        assertTrue(log.verify().ok)
    }

    @Test
    fun `sealing binds the tip, count and agent`() {
        val signer = key()
        val (_, log) = seeded()
        val checkpoint = log.seal(signer, SealOptions(tenant = tenant, issued = issued))

        assertEquals("0.1", checkpoint.amp)
        assertEquals("ledger-checkpoint", checkpoint.kind)
        assertEquals(tenant, checkpoint.tenant)
        assertEquals(agent, checkpoint.agent)
        assertEquals(0L, checkpoint.seq)
        assertEquals(log.count.toLong(), checkpoint.count)
        assertEquals(log.tip, checkpoint.tip)
        assertEquals(issued, checkpoint.issued)
        assertEquals(signer.kid, checkpoint.kid)
        assertNull(checkpoint.prev)
        assertTrue(verifyCheckpoint(checkpoint, signer.publicKey()).ok)
    }

    @Test
    fun `sealing without an agent fails closed but an explicit agent works`() {
        val signer = key()
        val log = AppendLog.open(MemoryAppendSink())
        val error = assertThrows(AppendLogError::class.java) {
            log.seal(signer, SealOptions(tenant = tenant))
        }
        assertTrue(error.message!!.contains("without an agent"))

        val explicit = log.seal(
            signer,
            SealOptions(tenant = tenant, issued = issued, agent = "a.explicit"),
        )
        assertEquals("a.explicit", explicit.agent)
        assertEquals(0L, explicit.count)
        assertEquals(genesis, explicit.tip)
        assertTrue(verifyCheckpoint(explicit, signer.publicKey()).ok)
    }

    @Test
    fun `checkpoint chaining commits seq and the previous body digest`() {
        val signer = key()
        val (_, log) = seeded()
        val first = log.seal(signer, SealOptions(tenant = tenant, issued = issued))
        log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.2")))
        val second = log.seal(
            signer,
            SealOptions(tenant = tenant, issued = "2026-10-26T00:00:01Z", previous = first),
        )

        assertEquals(first.seq + 1L, second.seq)
        assertEquals(checkpointDigest(first), second.prev)
        assertTrue(verifyCheckpoint(second, signer.publicKey(), first).ok)
        assertNotEquals(first.tip, second.tip)
    }

    @Test
    fun `sealing refuses a log mutated after open`() {
        val signer = key()
        val (_, log) = seeded()
        log.entries()[0].payload = jsonObj("id" to jsonText("ev.1"), "forged" to JsonValue.Bool(true))

        val error = assertThrows(AppendLogError::class.java) {
            log.seal(signer, SealOptions(tenant = tenant))
        }
        assertTrue(error.message!!.contains("broken append log"))
    }

    @Test
    fun `append log errors carry their reasons`() {
        val reason = "one reason"
        val error = AppendLogError("broken", listOf(reason))
        assertEquals(listOf(reason), error.reasons)
        assertEquals("broken", error.message)
    }

    @Test
    fun `sealed chain entries round-trip through a ledger snapshot`() {
        val signer = key()
        val (_, log) = seeded()
        val checkpoint = log.seal(signer, SealOptions(tenant = tenant, issued = issued))
        val snapshot = com.eloykim.lyra.amp.protocol.ledger.LedgerSnapshot(
            agent = agent,
            tip = checkpoint.tip,
            count = log.count,
            entries = log.entries().map { it.toChainEntry() },
        )
        assertTrue(com.eloykim.lyra.amp.protocol.ledger.verifySnapshot(snapshot).ok)
        assertEquals(snapshot.agent, checkpoint.agent)
    }

    private class FlakySink : AppendSink {

        private val delegate = MemoryAppendSink()
        var failNext: Boolean = false

        override fun append(line: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("sink write failed")
            }
            delegate.append(line)
        }

        override fun readAll(): List<String> = delegate.readAll()
    }
}
