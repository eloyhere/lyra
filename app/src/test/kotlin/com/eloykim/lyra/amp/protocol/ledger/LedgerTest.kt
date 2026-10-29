package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.goal.Goal
import com.eloykim.lyra.amp.protocol.goal.Plan
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.receipt.AmpEvent
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.UnknownItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LedgerTest {

    private fun readExample(name: String): JsonValue =
        JsonParser.parse(File("src/test/resources/amp/examples/$name").readText())

    private fun exampleSnapshot(): LedgerSnapshot =
        LedgerSnapshot.fromJson(readExample("ledger-snapshot.json"))

    private fun fullLedger(): Ledger {
        val ledger = Ledger()
        ledger.appendFrame(Frame.fromJson((readExample("frame-chain.json") as JsonValue.Arr).items.first()))
        ledger.appendReceipt(Receipt.fromJson(readExample("receipt.json")))
        ledger.appendClaim(Claim.fromJson(readExample("claim.json")))
        val unknown = exampleSnapshot().entries.first { it.kind == EntryKind.UNKNOWN }
        ledger.appendUnknown(UnknownItem.fromJson(unknown.payload))
        ledger.appendGoal(
            Goal(
                id = "g.7",
                statement = "控制错误率",
                success = listOf(Criterion(id = "c.1", statement = "错误率低于基线", check = "metrics.query")),
                value = 0.9,
                priority = 1,
            ),
        )
        ledger.appendPlan(Plan.fromJson(readExample("plan.json")))
        ledger.appendEvent(
            AmpEvent(
                id = "e.1",
                at = "2026-10-24T01:11:00Z",
                actor = Ref(RefKind.AGENT, "a.gamma"),
                type = "frame.committed",
                causedBy = "f.101",
                digest = "sha256:" + "a".repeat(64),
                payload = jsonObj("cycle" to jsonNumber(1)),
            ),
        )
        return ledger
    }

    @Test
    fun `参考快照通过校验、往返与 schema`() {
        val snapshot = exampleSnapshot()
        assertTrue(verifySnapshot(snapshot).ok)
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("ledger-snapshot", snapshot.toJson()))
        assertEquals(
            CanonicalJson.encode(readExample("ledger-snapshot.json")),
            CanonicalJson.encode(snapshot.toJson()),
        )
    }

    @Test
    fun `restoreLedger 按 kind 还原示例快照`() {
        val restored = restoreLedger(exampleSnapshot())
        assertEquals(listOf("f.101"), restored.frames.map { it.id })
        assertEquals(listOf("cl.1"), restored.claims.map { it.id })
        assertEquals(listOf("u.1"), restored.unknowns.map { it.id })
        assertTrue(restored.receipts.isEmpty())
        assertTrue(restored.events.isEmpty())
        assertTrue(restored.hypotheses.isEmpty())
        assertTrue(restored.goals.isEmpty())
        assertTrue(restored.plans.isEmpty())
    }

    @Test
    fun `verifySnapshot 不信任 tip 与 count`() {
        val snapshot = exampleSnapshot()

        val tip = verifySnapshot(snapshot.copy(tip = "sha256:" + "f".repeat(64)))
        assertFalse(tip.ok)
        assertTrue(tip.reasons.contains("tip does not match the last entry"))

        val count = verifySnapshot(snapshot.copy(count = 4))
        assertFalse(count.ok)
        assertTrue(count.reasons.contains("count 4 does not match 3 entries"))
    }

    @Test
    fun `verifySnapshot 拒绝被改写的 payload、prev 与 seq`() {
        val snapshot = exampleSnapshot()

        val rewritten = snapshot.entries.map { entry ->
            if (entry.kind == EntryKind.FRAME) {
                entry.copy(payload = entry.payload.copyWith("cycle", jsonNumber(2)))
            } else {
                entry
            }
        }
        val payloadVerdict = verifySnapshot(snapshot.copy(entries = rewritten))
        assertFalse(payloadVerdict.ok)
        assertTrue(payloadVerdict.reasons.contains("entry 1: hash does not cover its payload"))

        val prevChanged = snapshot.entries.mapIndexed { index, entry ->
            if (index == 1) entry.copy(prev = "sha256:" + "e".repeat(64)) else entry
        }
        val prevVerdict = verifySnapshot(snapshot.copy(entries = prevChanged))
        assertFalse(prevVerdict.ok)
        assertTrue(prevVerdict.reasons.contains("entry 1: prev does not match the previous hash"))

        val seqChanged = snapshot.entries.mapIndexed { index, entry ->
            if (index == 2) entry.copy(seq = 5) else entry
        }
        val seqVerdict = verifySnapshot(snapshot.copy(entries = seqChanged))
        assertFalse(seqVerdict.ok)
        assertTrue(seqVerdict.reasons.contains("entry 2: seq 5 is out of order"))
    }

    @Test
    fun `restoreLedger 拒绝被篡改的快照`() {
        val broken = exampleSnapshot().copy(count = 99)
        val error = assertThrows(IllegalStateException::class.java) { restoreLedger(broken) }
        assertTrue(error.message!!.startsWith("refusing to restore a broken snapshot:"))
    }

    @Test
    fun `exportLedger 按时间排序并以 kind 打破平局`() {
        val snapshot = exportLedger(fullLedger(), "a.gamma")
        assertEquals(
            listOf(
                EntryKind.GOAL,
                EntryKind.UNKNOWN,
                EntryKind.EVENT,
                EntryKind.FRAME,
                EntryKind.RECEIPT,
                EntryKind.CLAIM,
                EntryKind.PLAN,
            ),
            snapshot.entries.map { it.kind },
        )
        assertEquals(snapshot.entries.size, snapshot.count)
        assertTrue(verifySnapshot(snapshot).ok)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), snapshot.entries.map { it.seq })
    }

    @Test
    fun `exportLedger 的快照可还原为类型化账本`() {
        val snapshot = exportLedger(fullLedger(), "a.gamma")
        val restored = restoreLedger(snapshot)
        assertEquals(1, restored.frames.size)
        assertEquals(1, restored.receipts.size)
        assertEquals(1, restored.claims.size)
        assertEquals(1, restored.unknowns.size)
        assertEquals(1, restored.goals.size)
        assertEquals(1, restored.plans.size)
        assertEquals(1, restored.events.size)
        assertEquals("pl.3", restored.plans.single().id)
        assertEquals("cl.91", restored.claims.single().id)
    }

    @Test
    fun `append 方法只追加且按 id 查找`() {
        val ledger = Ledger()
        val claim = Claim.fromJson(readExample("claim.json"))
        ledger.appendClaim(claim)
        assertEquals(claim, ledger.findClaim("cl.91"))
        assertNull(ledger.findClaim("cl.0"))

        val unknown = UnknownItem.fromJson(
            exampleSnapshot().entries.first { it.kind == EntryKind.UNKNOWN }.payload,
        )
        ledger.appendUnknown(unknown)
        assertEquals(unknown, ledger.findUnknown("u.1"))
        assertNull(ledger.findUnknown("u.2"))

        ledger.appendEvent(
            AmpEvent(
                id = "e.2",
                at = "2026-10-24T01:00:00Z",
                actor = Ref(RefKind.AGENT, "a.gamma"),
                type = "note",
                digest = "sha256:" + "b".repeat(64),
            ),
        )
        assertEquals(1, ledger.events.size)
        assertEquals(1, ledger.claims.size)
        assertEquals(0, ledger.frames.size)
    }
}
