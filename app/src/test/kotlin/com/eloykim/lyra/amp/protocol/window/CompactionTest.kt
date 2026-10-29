package com.eloykim.lyra.amp.protocol.window

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.ledger.ChainEntry
import com.eloykim.lyra.amp.protocol.ledger.Ledger
import com.eloykim.lyra.amp.protocol.ledger.LedgerSnapshot
import com.eloykim.lyra.amp.protocol.ledger.SnapshotVerdict
import com.eloykim.lyra.amp.protocol.ledger.exportLedger
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.ClaimStatus
import com.eloykim.lyra.amp.protocol.warrant.Hypothesis
import com.eloykim.lyra.amp.protocol.warrant.HypothesisStatus
import com.eloykim.lyra.amp.protocol.warrant.Inference
import com.eloykim.lyra.amp.protocol.warrant.UnknownItem
import com.eloykim.lyra.amp.protocol.warrant.UnknownStatus
import com.eloykim.lyra.amp.protocol.warrant.Warrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompactionTest {

    private fun at(second: Int): String = "2026-10-24T01:00:${second.toString().padStart(2, '0')}Z"

    private fun provenance(): Provenance = Provenance(source = "test")

    private fun claim(
        id: String,
        created: String = at(0),
        status: ClaimStatus = ClaimStatus.HELD,
        supersedes: Ref? = null,
    ): Claim = Claim(
        id = id,
        statement = id,
        tier = Tier.T2,
        status = status,
        warrant = Warrant(evidence = emptyList(), inference = Inference.TESTIMONY, strength = 0.5),
        provenance = provenance(),
        created = created,
        supersedes = supersedes,
    )

    private fun hypothesis(
        id: String,
        created: String = at(0),
        status: HypothesisStatus = HypothesisStatus.TESTING,
        question: Ref = Ref(RefKind.UNKNOWN, "u.0"),
    ): Hypothesis = Hypothesis(
        id = id,
        question = question,
        statement = id,
        prediction = "p",
        refutesIf = "r",
        status = status,
        created = created,
    )

    private fun unknown(
        id: String,
        since: String = at(0),
        blocking: Boolean = false,
        status: UnknownStatus? = null,
    ): UnknownItem = UnknownItem(
        id = id,
        question = "what is $id",
        importance = 0.5,
        blocking = blocking,
        since = since,
        status = status,
    )

    private fun snapshotOf(
        claims: List<Claim> = emptyList(),
        hypotheses: List<Hypothesis> = emptyList(),
        unknowns: List<UnknownItem> = emptyList(),
    ): LedgerSnapshot {
        val ledger = Ledger()
        claims.forEach(ledger::appendClaim)
        hypotheses.forEach(ledger::appendHypothesis)
        unknowns.forEach(ledger::appendUnknown)
        return exportLedger(ledger, "a.test")
    }

    private fun idsOf(entries: List<ChainEntry>): List<String> =
        entries.map { it.payload.string("id") ?: "?" }

    private fun droppedIds(plan: PrunePlan): List<String> = plan.dropped.map { it.id }

    @Test
    fun `a superseded claim leaves the view and its successor stays`() {
        val snapshot = snapshotOf(
            claims = listOf(
                claim("cl.old", created = at(1)),
                claim("cl.new", created = at(2), supersedes = Ref(RefKind.CLAIM, "cl.old")),
            ),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))

        assertEquals(listOf("cl.old"), droppedIds(result.plan))
        assertEquals(PruneReason.SUPERSEDED, result.plan.dropped.single().reason)
        assertTrue(result.plan.dropped.single().revisitIf.contains("cl.new"))
        assertEquals(listOf("cl.new"), idsOf(result.keep))
    }

    @Test
    fun `a contested claim is retained even when superseded`() {
        val snapshot = snapshotOf(
            claims = listOf(
                claim("cl.old", created = at(1), status = ClaimStatus.CONTESTED),
                claim("cl.new", created = at(2), supersedes = Ref(RefKind.CLAIM, "cl.old")),
            ),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))
        assertEquals(emptyList<String>(), droppedIds(result.plan))
    }

    @Test
    fun `a citation pins the cited object even when its own rule would drop it`() {
        val snapshot = snapshotOf(
            unknowns = listOf(
                unknown("u.used", since = at(1), status = UnknownStatus.RESOLVED),
                unknown("u.unused", since = at(2), status = UnknownStatus.RESOLVED),
            ),
            hypotheses = listOf(
                hypothesis("h.open", created = at(3), question = Ref(RefKind.UNKNOWN, "u.used")),
            ),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))

        assertEquals(listOf("u.unused"), droppedIds(result.plan))
        assertTrue(idsOf(result.keep).contains("u.used"))
    }

    @Test
    fun `a blocking unknown is never pruned`() {
        val snapshot = snapshotOf(
            unknowns = listOf(
                unknown("u.block", status = UnknownStatus.RESOLVED, blocking = true),
            ),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))
        assertEquals(emptyList<String>(), droppedIds(result.plan))
    }

    @Test
    fun `closed hypotheses leave the view while testing ones stay`() {
        val snapshot = snapshotOf(
            hypotheses = listOf(
                hypothesis("h.done", created = at(1), status = HypothesisStatus.REFUTED),
                hypothesis("h.open", created = at(2), status = HypothesisStatus.TESTING),
            ),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))

        assertEquals(listOf("h.done"), droppedIds(result.plan))
        assertEquals(PruneReason.CLOSED, result.plan.dropped.single().reason)
        assertEquals(listOf("h.open"), idsOf(result.keep))
    }

    @Test
    fun `the retention floor wins over every per-kind rule`() {
        val snapshot = snapshotOf(
            claims = listOf(
                claim("cl.old", created = at(1)),
                claim("cl.new", created = at(2), supersedes = Ref(RefKind.CLAIM, "cl.old")),
            ),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 2))

        assertEquals(emptyList<String>(), droppedIds(result.plan))
        assertEquals(2, result.plan.kept.size)
    }

    @Test
    fun `a plan partitions the chain and reports its size honestly`() {
        val snapshot = snapshotOf(
            claims = listOf(
                claim("cl.old", created = at(1)),
                claim("cl.new", created = at(2), supersedes = Ref(RefKind.CLAIM, "cl.old")),
            ),
            unknowns = listOf(unknown("u.1", since = at(3), status = UnknownStatus.RESOLVED)),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))
        val plan = result.plan

        assertEquals(plan.sourceCount, plan.kept.size + plan.dropped.size)
        assertEquals(snapshot.count, plan.sourceCount)
        assertEquals(snapshot.tip, plan.sourceTip)
        assertEquals(plan.dropped.sumOf { it.bytes }, plan.reclaimedBytes)
        assertEquals((plan.reclaimedBytes + 3) / 4, plan.estimatedTokens)
        assertEquals(plan.kept.sorted(), plan.kept)
        assertEquals(plan.policy, PrunePolicy(keepRecent = 0))
    }

    @Test
    fun `every dropped object says why and when to reconsider`() {
        val snapshot = snapshotOf(
            unknowns = listOf(unknown("u.1", since = at(1), status = UnknownStatus.RESOLVED)),
            hypotheses = listOf(hypothesis("h.1", created = at(2), status = HypothesisStatus.SUPPORTED)),
        )
        val result = planPrune(snapshot, PruneOverrides(keepRecent = 0))

        assertEquals(2, result.plan.dropped.size)
        result.plan.dropped.forEach { decision ->
            assertTrue(
                decision.reason == PruneReason.SUPERSEDED ||
                    decision.reason == PruneReason.CLOSED ||
                    decision.reason == PruneReason.RESOLVED,
            )
            assertTrue(decision.revisitIf.length > 10)
            assertTrue(decision.bytes > 0)
        }
    }

    @Test
    fun `pruning is deterministic`() {
        val snapshot = snapshotOf(
            claims = listOf(
                claim("cl.old", created = at(1)),
                claim("cl.new", created = at(2), supersedes = Ref(RefKind.CLAIM, "cl.old")),
            ),
        )
        assertEquals(
            planPrune(snapshot, PruneOverrides(keepRecent = 0)).plan,
            planPrune(snapshot, PruneOverrides(keepRecent = 0)).plan,
        )
    }

    @Test
    fun `pruning refuses a ledger whose chain does not verify`() {
        val snapshot = snapshotOf(claims = listOf(claim("cl.1")))
        val broken = snapshot.copy(
            entries = snapshot.entries.map { it.copy(hash = "sha256:" + "2".repeat(64)) },
        )
        val error = assertThrows(IllegalStateException::class.java) {
            planPrune(broken)
        }
        assertTrue(error.message!!.contains("unverified"))
    }

    @Test
    fun `compaction replaces a cold prefix with a verifiable checkpoint`() {
        val snapshot = snapshotOf(
            claims = listOf(claim("cl.1", created = at(1)), claim("cl.2", created = at(2))),
            unknowns = listOf(unknown("u.1", since = at(3)), unknown("u.2", since = at(4))),
        )
        val compaction = compactLedger(snapshot, CompactionOptions(keepRecent = 2, now = at(0)))

        assertEquals(2, compaction.removed.size)
        assertEquals(2, compaction.checkpoint.count)
        assertEquals(compaction.removed[1].seq, compaction.checkpoint.to)
        assertEquals(2, compaction.tail.entries.size)
        assertEquals(compaction.checkpoint.tip, compaction.tail.entries[0].prev)
        assertEquals("cp.0-1", compaction.checkpoint.id)
        assertEquals(at(0), compaction.checkpoint.created)
        assertEquals(
            CanonicalJson.digest(JsonValue.Arr(compaction.removed.map { it.toJson() })),
            compaction.checkpoint.root,
        )
        assertEquals(SnapshotVerdict.of(emptyList()), verifyCompaction(compaction))
        assertEquals(emptyList<String>(), verifyCompaction(compaction).reasons)
    }

    @Test
    fun `a checkpoint that does not cover its entries is rejected`() {
        val snapshot = snapshotOf(
            claims = listOf(claim("cl.1"), claim("cl.2", created = at(1))),
        )
        val compaction = compactLedger(snapshot, CompactionOptions(keepRecent = 1))
        val rewritten = compaction.copy(
            checkpoint = compaction.checkpoint.copy(root = "sha256:" + "0".repeat(64)),
        )
        val verdict = verifyCompaction(rewritten)

        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("root") })
    }

    @Test
    fun `a tail that no longer continues the chain is rejected`() {
        val snapshot = snapshotOf(
            claims = listOf(claim("cl.1"), claim("cl.2", created = at(1))),
        )
        val compaction = compactLedger(snapshot, CompactionOptions(keepRecent = 1))
        val disconnected = compaction.copy(
            tail = compaction.tail.copy(
                entries = compaction.tail.entries.map {
                    it.copy(prev = "sha256:" + "1".repeat(64))
                },
            ),
        )
        val verdict = verifyCompaction(disconnected)

        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("prev") })
    }

    @Test
    fun `compaction refuses to bury an entry the retained tail still cites`() {
        val snapshot = snapshotOf(
            unknowns = listOf(unknown("u.1", since = at(1), status = UnknownStatus.RESOLVED)),
            hypotheses = listOf(
                hypothesis("h.1", created = at(2), question = Ref(RefKind.UNKNOWN, "u.1")),
            ),
        )
        val error = assertThrows(CompactionRefused::class.java) {
            compactLedger(snapshot, CompactionOptions(keepRecent = 1))
        }
        assertTrue(error.pinned.contains("unknown:u.1"))
        assertTrue(error.message!!.contains("still references"))
    }

    @Test
    fun `compacting the whole chain leaves a valid empty tail`() {
        val snapshot = snapshotOf(
            claims = listOf(claim("cl.1"), claim("cl.2", created = at(1))),
        )
        val compaction = compactLedger(snapshot, CompactionOptions(keepRecent = 0))

        assertEquals(0, compaction.tail.count)
        assertEquals(compaction.checkpoint.tip, compaction.tail.tip)
        assertEquals(SnapshotVerdict.of(emptyList()), verifyCompaction(compaction))
    }

    @Test
    fun `compaction refuses a chain inside the retention window`() {
        val snapshot = snapshotOf(claims = listOf(claim("cl.1")))
        val error = assertThrows(CompactionRefused::class.java) {
            compactLedger(snapshot, CompactionOptions(keepRecent = 1))
        }
        assertTrue(error.message!!.contains("nothing to compact"))
    }

    @Test
    fun `worked examples parse, round-trip and satisfy the schema`() {
        val checkpointJson = JsonParser.parse(
            File("src/test/resources/amp/examples/checkpoint.json").readText(),
        )
        val checkpoint = Checkpoint.fromJson(checkpointJson)
        assertEquals("cp.0-3", checkpoint.id)
        assertEquals(4, checkpoint.count)
        assertEquals(checkpointJson, checkpoint.toJson())
        assertEquals(emptyList<String>(), AmpSchemas.validate("compaction.schema.json", checkpoint.toJson()))

        val planJson = JsonParser.parse(
            File("src/test/resources/amp/examples/prune-plan.json").readText(),
        )
        val plan = PrunePlan.fromJson(planJson)
        assertEquals(3, plan.dropped.size)
        assertEquals(1222, plan.reclaimedBytes)
        assertEquals(306, plan.estimatedTokens)
        assertEquals(planJson, plan.toJson())
        assertEquals(emptyList<String>(), AmpSchemas.validate("compaction.schema.json", plan.toJson()))
    }

    @Test
    fun `malformed plans fail instead of defaulting fields`() {
        val planJson = JsonParser.parse(
            File("src/test/resources/amp/examples/prune-plan.json").readText(),
        ) as JsonValue.Obj
        val missingPolicy = planJson.without("policy")
        assertThrows(IllegalArgumentException::class.java) { PrunePlan.fromJson(missingPolicy) }

        val badReason = planJson.copyWith(
            "dropped",
            JsonValue.Arr(
                listOf(
                    (planJson.requireArr("dropped").items.first() as JsonValue.Obj)
                        .copyWith("reason", JsonValue.Str("forgotten")),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { PrunePlan.fromJson(badReason) }
    }

    @Test
    fun `prune decisions and policy round-trip through JSON`() {
        val snapshot = snapshotOf(
            unknowns = listOf(unknown("u.1", since = at(1), status = UnknownStatus.RESOLVED)),
        )
        val plan = planPrune(snapshot, PruneOverrides(keepRecent = 0, dropResolvedUnknowns = false)).plan
        assertEquals(emptyList<PruneDecision>(), plan.dropped)
        assertEquals(PrunePolicy(keepRecent = 0, dropResolvedUnknowns = false), plan.policy)
        assertNotNull(PrunePlan.fromJson(plan.toJson()).policy)

        val dropped = planPrune(snapshot, PruneOverrides(keepRecent = 0)).plan.dropped.single()
        assertEquals(dropped, PruneDecision.fromJson(dropped.toJson()))
    }
}
