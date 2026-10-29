package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import com.eloykim.lyra.amp.protocol.storage.AppendLog
import com.eloykim.lyra.amp.protocol.storage.AppendLogError
import com.eloykim.lyra.amp.protocol.storage.MemoryAppendSink
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {

    private fun receipt(id: String = "rcp.1"): BoardReceipt = BoardReceipt(
        id = id,
        from = PerspectiveId.CUSTOMER_SERVICE,
        at = AT_ISSUE,
        conclusion = "requirement satisfied",
        evidence = listOf(refOf(RefKind.CLAIM, "c.1")),
        confidence = 0.75,
        budget = Budget(tokens = 10),
        manifest = ManifestRef("amn.1", 1),
        scenario = ScenarioId.CHAT,
        modes = ModeWeights(0.5, 0.2, 0.2, 0.1),
        digest = digestOfText(id),
    )

    @Test
    fun `重放恢复最新清单凭据与回执`() {
        val sink = MemoryAppendSink()
        val store = GovernanceStore.open(sink)
        val v1 = testManifest()
        val v2 = rederiveAttentionManifest(v1, RederivePatch(note = "v2"), TEST_SIGNERS, AT_TEN_MIN)
        val credential = testCredential()
        val receipt = receipt()

        store.saveManifest(v1)
        store.saveManifest(v2)
        store.saveCredential(credential)
        store.saveReceipt(receipt)

        assertEquals(4, store.count)
        assertEquals(2, store.getManifest("amn.1")!!.version)
        assertEquals(v2.digest, store.getManifest("amn.1")!!.digest)
        assertEquals(credential.id, store.getCredential(credential.id)!!.id)
        assertEquals(receipt.conclusion, store.getReceipt("rcp.1")!!.conclusion)
        assertTrue(store.verify().ok)

        val reopened = GovernanceStore.open(sink)
        assertEquals(4, reopened.count)
        assertEquals(v2, reopened.getManifest("amn.1"))
        assertEquals(credential, reopened.getCredential(credential.id))
        assertEquals(receipt, reopened.getReceipt("rcp.1"))
        assertTrue(reopened.verify().ok)
        assertEquals(store.tip(), reopened.tip())
    }

    @Test
    fun `旧版本不覆盖新版本`() {
        val store = GovernanceStore.open(MemoryAppendSink())
        val v1 = testManifest()
        val v2 = rederiveAttentionManifest(v1, RederivePatch(note = "v2"), TEST_SIGNERS, AT_TEN_MIN)
        store.saveManifest(v2)
        store.saveManifest(v1)
        assertEquals(2, store.getManifest("amn.1")!!.version)
    }

    @Test
    fun `快照给出三类对象与链长度`() {
        val store = GovernanceStore.open(MemoryAppendSink())
        store.saveManifest(testManifest())
        store.saveCredential(testCredential())
        store.saveReceipt(receipt())
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.manifests.size)
        assertEquals(1, snapshot.credentials.size)
        assertEquals(1, snapshot.receipts.size)
        assertEquals(3, snapshot.count)
    }

    @Test
    fun `损坏的行让重开崩溃`() {
        val sink = MemoryAppendSink()
        val store = GovernanceStore.open(sink)
        store.saveManifest(testManifest())
        sink.lines.add("{broken")
        var caught: AppendLogError? = null
        try {
            GovernanceStore.open(sink)
        } catch (error: AppendLogError) {
            caught = error
        }
        assertTrue("截断或损坏的尾部必须抛 AppendLogError", caught != null)
        assertTrue(caught!!.reasons.any { it.contains("not valid JSON") })
    }

    @Test
    fun `非治理记录被拒绝重放`() {
        val sink = MemoryAppendSink()
        GovernanceStore.open(sink).saveManifest(testManifest())
        AppendLog.open(sink).append(EntryKind.EVENT, jsonObj("type" to jsonText("bogus")))
        assertThrowsGovernance(GovernanceCode.REGISTRY_INVALID) {
            GovernanceStore.open(sink)
        }
    }

    @Test
    fun `未知对象为空`() {
        val store = GovernanceStore.open(MemoryAppendSink())
        assertNull(store.getManifest("amn.none"))
        assertNull(store.getCredential("crd.none"))
        assertNull(store.getReceipt("rcp.none"))
    }
}
