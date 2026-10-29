package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.warrant.Claim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareTest {

    private fun readExample(name: String): JsonValue =
        JsonParser.parse(File("src/test/resources/amp/examples/$name").readText())

    private fun ledger(): Ledger {
        val ledger = Ledger()
        ledger.appendFrame(Frame.fromJson((readExample("frame-chain.json") as JsonValue.Arr).items.first()))
        ledger.appendReceipt(Receipt.fromJson(readExample("receipt.json")))
        ledger.appendClaim(Claim.fromJson(readExample("claim.json")))
        return ledger
    }

    private fun base(mode: String, snapshot: LedgerSnapshot): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str("0.1"),
        "kind" to JsonValue.Str("shared-ledger"),
        "mode" to JsonValue.Str(mode),
        "agent" to JsonValue.Str("a.gamma"),
        "tip" to JsonValue.Str(snapshot.tip),
        "count" to jsonNumber(snapshot.count),
    )

    @Test
    fun `full 共享包含全部条目且 digest 覆盖它们`() {
        val ledger = ledger()
        val snapshot = exportLedger(ledger, "a.gamma")
        val view = shareLedger(ledger, "a.gamma", ShareMode.FULL)

        assertEquals(ShareMode.FULL, view.mode)
        assertEquals("shared-ledger", view.kind)
        assertEquals(snapshot.tip, view.tip)
        assertEquals(snapshot.count, view.count)
        assertEquals(snapshot.entries, view.entries)
        assertNull(view.summary)

        val expected = CanonicalJson.digest(
            base("full", snapshot).copyWith(
                "entries",
                JsonValue.Arr(snapshot.entries.map { it.toJson() }),
            ),
        )
        assertEquals(expected, view.digest)
        assertEquals(3, view.toJson().arr("entries")?.size)
    }

    @Test
    fun `summary 共享只有计数与 claim id`() {
        val ledger = ledger()
        val snapshot = exportLedger(ledger, "a.gamma")
        val view = shareLedger(ledger, "a.gamma", ShareMode.SUMMARY)

        assertEquals(ShareMode.SUMMARY, view.mode)
        assertNull(view.entries)
        val summary = view.summary!!
        assertEquals(mapOf("frame" to 1, "receipt" to 1, "claim" to 1), summary.byKind)
        assertEquals(listOf("cl.91"), summary.claims)

        val byKind = jsonObj(
            "frame" to jsonNumber(1),
            "receipt" to jsonNumber(1),
            "claim" to jsonNumber(1),
        )
        val expected = CanonicalJson.digest(
            base("summary", snapshot)
                .copyWith("byKind", byKind)
                .copyWith("claims", jsonStrings(listOf("cl.91"))),
        )
        assertEquals(expected, view.digest)
        assertTrue(!view.toJson().has("entries"))
        assertEquals(3, view.toJson().obj("summary")?.obj("byKind")?.fields?.size)
    }

    @Test
    fun `reference 共享只有 tip、count 与 digest`() {
        val ledger = ledger()
        val snapshot = exportLedger(ledger, "a.gamma")
        val view = shareLedger(ledger, "a.gamma", ShareMode.REFERENCE)

        assertEquals(ShareMode.REFERENCE, view.mode)
        assertNull(view.entries)
        assertNull(view.summary)
        assertEquals(snapshot.tip, view.tip)
        assertEquals(snapshot.count, view.count)
        assertEquals(CanonicalJson.digest(base("reference", snapshot)), view.digest)
        assertTrue(!view.toJson().has("entries"))
        assertTrue(!view.toJson().has("summary"))
    }
}
