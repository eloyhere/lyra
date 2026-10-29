package com.eloykim.lyra.amp.protocol.window

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.ledger.ChainEntry
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import com.eloykim.lyra.amp.protocol.ledger.LedgerSnapshot
import com.eloykim.lyra.amp.protocol.ledger.SnapshotVerdict
import com.eloykim.lyra.amp.protocol.ledger.verifySnapshot
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * AMP v0.1 context economy: pruning and compaction (port of
 * `src/window/compactionTypes.ts` and `src/window/compaction.ts`).
 *
 * Two operations that are often confused, and must not be:
 *
 * - **Pruning** produces a *view* of a verified ledger. Nothing is deleted; the
 *   log keeps every entry. A prune plan states what was left out, why, and when
 *   to reconsider it.
 * - **Compaction** reduces *storage*. A contiguous cold prefix is replaced by a
 *   checkpoint that commits to the digest of exactly what was removed, so the
 *   chain remains verifiable from the checkpoint's tip.
 *
 * Neither operation may lose warrant silently.
 *
 * ## Invariants (the reference's P-1 … P-7)
 *
 * P-1 `kept` and `dropped` partition the source chain: `kept.size + dropped.size
 *     == sourceCount`, and every `kept` seq exists in the source.
 * P-2 `kept` stays in chain order and never includes an entry at or after the
 *     retention floor's cutoff.
 * P-3 every dropped decision names its `seq`, `kind`, `id`, a closed
 *     [PruneReason], a non-empty `revisit_if`, and the serialised `bytes`.
 * P-4 no retained payload still cites a dropped `{kind, id}`; the reference
 *     closure runs last and wins. `supersedes` is a revision link, not a
 *     warrant dependency, so it is deliberately not followed.
 * P-5 `checkpoint.root` is the canonical digest of exactly the removed entries,
 *     in chain order.
 * P-6 `checkpoint.from`/`to`/`tip` agree with the first/last removed entry, and
 *     `count` equals the number removed.
 * P-7 the tail continues the chain (`seq == checkpoint.to + 1 + index`,
 *     `prev` links from `checkpoint.tip`), and `tail.tip` matches the last tail
 *     entry. Together with P-6 the checkpoint and tail reconstruct the source.
 */

/** Retention policy for a prune plan; `keepRecent` is a hard floor. */
data class PrunePolicy(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val keepRecent: Int = DEFAULT_KEEP_RECENT,
    val dropSupersededClaims: Boolean = true,
    val dropClosedHypotheses: Boolean = true,
    val dropResolvedUnknowns: Boolean = true,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "keepRecent" to jsonNumber(keepRecent),
        "dropSupersededClaims" to jsonBool(dropSupersededClaims),
        "dropClosedHypotheses" to jsonBool(dropClosedHypotheses),
        "dropResolvedUnknowns" to jsonBool(dropResolvedUnknowns),
    )

    companion object {

        const val KIND: String = "prune-policy"
        const val DEFAULT_KEEP_RECENT: Int = 50

        fun fromJson(value: JsonValue): PrunePolicy {
            val obj = value.asObject("prune-policy")
            val amp = obj.requireString("amp")
            require(amp == AmpWire.VERSION) { "prune-policy.amp 必须是 ${AmpWire.VERSION}" }
            val kind = obj.requireString("kind")
            require(kind == KIND) { "prune-policy.kind 必须是 $KIND" }
            return PrunePolicy(
                amp = amp,
                kind = kind,
                keepRecent = obj.requireInt("keepRecent"),
                dropSupersededClaims = obj.bool("dropSupersededClaims")
                    ?: throw IllegalArgumentException("缺少布尔字段 dropSupersededClaims"),
                dropClosedHypotheses = obj.bool("dropClosedHypotheses")
                    ?: throw IllegalArgumentException("缺少布尔字段 dropClosedHypotheses"),
                dropResolvedUnknowns = obj.bool("dropResolvedUnknowns")
                    ?: throw IllegalArgumentException("缺少布尔字段 dropResolvedUnknowns"),
            )
        }
    }
}

/**
 * `Partial<PrunePolicy>`: only the fields a caller wants to override.
 *
 * `keepRecent` is nullable so that an explicit `0` is distinguishable from
 * "use the default".
 */
data class PruneOverrides(
    val keepRecent: Int? = null,
    val dropSupersededClaims: Boolean? = null,
    val dropClosedHypotheses: Boolean? = null,
    val dropResolvedUnknowns: Boolean? = null,
)

/** Why an entry left the view. Closed set, so a reader can audit the decision. */
enum class PruneReason(val wire: String) {
    SUPERSEDED("superseded"),
    CLOSED("closed"),
    RESOLVED("resolved");

    companion object {
        fun fromWire(value: String): PruneReason =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 prune reason: $value")
    }
}

/** One entry omitted from a view, with the condition that would bring it back. */
data class PruneDecision(
    val seq: Int,
    val kind: EntryKind,
    val id: Id,
    val reason: PruneReason,
    /** What would have to change for this object to be worth loading again. */
    val revisitIf: String,
    /** Serialised size of the omitted entry, so the saving is not hand-waved. */
    val bytes: Int,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "seq" to jsonNumber(seq),
        "kind" to JsonValue.Str(kind.wire),
        "id" to JsonValue.Str(id),
        "reason" to JsonValue.Str(reason.wire),
        "revisit_if" to JsonValue.Str(revisitIf),
        "bytes" to jsonNumber(bytes),
    )

    companion object {
        fun fromJson(value: JsonValue): PruneDecision {
            val obj = value.asObject("prune decision")
            return PruneDecision(
                seq = obj.requireInt("seq"),
                kind = EntryKind.fromWire(obj.requireString("kind")),
                id = obj.requireString("id"),
                reason = PruneReason.fromWire(obj.requireString("reason")),
                revisitIf = obj.requireString("revisit_if"),
                bytes = obj.requireInt("bytes"),
            )
        }
    }
}

/**
 * A view of a verified snapshot.
 *
 * `kept` and `dropped` partition the source chain. Keeping the partition
 * explicit is what lets a reader re-derive the view without trusting it.
 */
data class PrunePlan(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val agent: Id,
    val sourceTip: Digest,
    val sourceCount: Int,
    val kept: List<Int>,
    val dropped: List<PruneDecision>,
    val reclaimedBytes: Int,
    /** Bytes / 4. An estimate, never used for billing. */
    val estimatedTokens: Int,
    val policy: PrunePolicy,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "agent" to JsonValue.Str(agent),
        "sourceTip" to JsonValue.Str(sourceTip),
        "sourceCount" to jsonNumber(sourceCount),
        "kept" to JsonValue.Arr(kept.map { jsonNumber(it) }),
        "dropped" to JsonValue.Arr(dropped.map { it.toJson() }),
        "reclaimedBytes" to jsonNumber(reclaimedBytes),
        "estimatedTokens" to jsonNumber(estimatedTokens),
        "policy" to policy.toJson(),
    )

    companion object {

        const val KIND: String = "prune-plan"

        fun fromJson(value: JsonValue): PrunePlan {
            val obj = value.asObject("prune-plan")
            val amp = obj.requireString("amp")
            require(amp == AmpWire.VERSION) { "prune-plan.amp 必须是 ${AmpWire.VERSION}" }
            val kind = obj.requireString("kind")
            require(kind == KIND) { "prune-plan.kind 必须是 $KIND" }
            return PrunePlan(
                amp = amp,
                kind = kind,
                agent = obj.requireString("agent"),
                sourceTip = obj.requireString("sourceTip"),
                sourceCount = obj.requireInt("sourceCount"),
                kept = obj.requireArr("kept").items.map { item ->
                    (item as? JsonValue.Num)?.value?.toInt()
                        ?: throw IllegalArgumentException("prune-plan.kept 必须是整数数组")
                },
                dropped = obj.requireArr("dropped").items.map(PruneDecision::fromJson),
                reclaimedBytes = obj.requireInt("reclaimedBytes"),
                estimatedTokens = obj.requireInt("estimatedTokens"),
                policy = PrunePolicy.fromJson(obj.requireObj("policy")),
            )
        }
    }
}

/**
 * A cold prefix, reduced to a commitment.
 *
 * `root` covers the removed entries in chain order. Anyone holding the original
 * entries can prove the checkpoint was not rewritten; anyone holding only the
 * checkpoint can prove *what* was removed, without being able to reconstruct it.
 *
 * Like `LedgerCheckpoint`, this type performs no constructor validation:
 * [verifyCompaction] must be able to report corrupted fields as reasons instead
 * of throwing before it can inspect them.
 */
data class Checkpoint(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val id: Id,
    val agent: Id,
    /** Sequence number of the first covered entry. */
    val from: Int,
    /** Sequence number of the last covered entry. */
    val to: Int,
    val count: Int,
    /** Hash of the last covered entry; the continuation point for the tail. */
    val tip: Digest,
    /** Digest over the covered entries, in order. */
    val root: Digest,
    val created: Timestamp,
    /** Retention policy the compaction was run with. */
    val policy: CheckpointPolicy,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "agent" to JsonValue.Str(agent),
        "from" to jsonNumber(from),
        "to" to jsonNumber(to),
        "count" to jsonNumber(count),
        "tip" to JsonValue.Str(tip),
        "root" to JsonValue.Str(root),
        "created" to JsonValue.Str(created),
        "policy" to policy.toJson(),
    )

    companion object {

        const val KIND: String = "checkpoint"

        fun fromJson(value: JsonValue): Checkpoint {
            val obj = value.asObject("checkpoint")
            val amp = obj.requireString("amp")
            require(amp == AmpWire.VERSION) { "checkpoint.amp 必须是 ${AmpWire.VERSION}" }
            val kind = obj.requireString("kind")
            require(kind == KIND) { "checkpoint.kind 必须是 $KIND" }
            return Checkpoint(
                amp = amp,
                kind = kind,
                id = obj.requireString("id"),
                agent = obj.requireString("agent"),
                from = obj.requireInt("from"),
                to = obj.requireInt("to"),
                count = obj.requireInt("count"),
                tip = obj.requireString("tip"),
                root = obj.requireString("root"),
                created = obj.requireString("created"),
                policy = CheckpointPolicy.fromJson(obj.requireObj("policy")),
            )
        }
    }
}

/** The `policy` object nested in a [Checkpoint]; the schema forbids extra fields. */
data class CheckpointPolicy(val keepRecent: Int) {

    fun toJson(): JsonValue.Obj = jsonObj("keepRecent" to jsonNumber(keepRecent))

    companion object {
        fun fromJson(value: JsonValue): CheckpointPolicy {
            val obj = value.asObject("checkpoint.policy")
            return CheckpointPolicy(keepRecent = obj.requireInt("keepRecent"))
        }
    }
}

data class PruneResult(
    val plan: PrunePlan,
    /** The view itself: retained entries in chain order. */
    val keep: List<ChainEntry>,
)

data class Compaction(
    val checkpoint: Checkpoint,
    /** The part of the chain that remains in storage, continuing from `checkpoint.tip`. */
    val tail: LedgerSnapshot,
    /** The entries the checkpoint covers. Kept here so the commitment is checkable. */
    val removed: List<ChainEntry>,
)

class CompactionRefused(
    message: String,
    val pinned: List<String> = emptyList(),
) : IllegalStateException(message)

data class CompactionOptions(
    val keepRecent: Int? = null,
    val now: Timestamp? = null,
)

private val LEDGER_KINDS: Set<String> = EntryKind.entries.map { it.wire }.toSet()

private val CLOSED_HYPOTHESES: Set<String> =
    setOf("supported", "refuted", "inconclusive", "out_of_scope")

private val ISO_MILLIS: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

private fun isoNow(): Timestamp = ISO_MILLIS.format(Instant.now())

private fun idOf(payload: JsonValue.Obj): String? = payload.string("id")

private fun keyOf(kind: String, id: String): String = "$kind:$id"

/**
 * Every `{kind, id}` reference inside a payload, at any depth.
 *
 * `supersedes` is deliberately not followed: it is a revision link, not a
 * warrant dependency. The successor states that the old claim is no longer
 * active, so the old claim may leave the view while the successor stays.
 */
private fun collectRefs(value: JsonValue, out: MutableSet<String>, depth: Int = 0) {
    if (depth > 16) {
        return
    }
    when (value) {
        is JsonValue.Arr -> value.items.forEach { collectRefs(it, out, depth + 1) }
        is JsonValue.Obj -> {
            val kind = value.string("kind")
            val id = value.string("id")
            if (kind != null && id != null && LEDGER_KINDS.contains(kind)) {
                out += keyOf(kind, id)
            }
            value.fields.forEach { (key, child) ->
                if (key != "amp" && key != "supersedes") {
                    collectRefs(child, out, depth + 1)
                }
            }
        }

        is JsonValue.Str, is JsonValue.Num, is JsonValue.Bool, JsonValue.Null -> Unit
    }
}

/**
 * UTF-8 length of the entry's compact JSON.
 *
 * The reference measures `JSON.stringify(entry)`. Canonical JSON has the same
 * string escaping and number formatting and the same length regardless of key
 * order, so this is the same byte count without depending on property order.
 */
private fun utf8Bytes(entry: ChainEntry): Int =
    CanonicalJson.encode(entry.toJson()).toByteArray(Charsets.UTF_8).size

/** Must match `ledger/persistence.ts` exactly so logs and snapshots verify alike. */
private fun hashOfEntry(entry: ChainEntry): Digest = CanonicalJson.digest(
    jsonObj(
        "seq" to jsonNumber(entry.seq),
        "kind" to JsonValue.Str(entry.kind.wire),
        "payload" to entry.payload,
        "prev" to JsonValue.Str(entry.prev),
    ),
)

private data class Candidate(
    val entry: ChainEntry,
    val id: String,
    val reason: PruneReason,
    val revisitIf: String,
)

/**
 * Why an entry may leave the view, if it may. Conservative by design: a kind
 * with no rule is never pruned, and an object without an id is never pruned
 * because the plan could not say what it dropped.
 */
private fun classify(
    entry: ChainEntry,
    id: String,
    policy: PrunePolicy,
    supersededBy: Map<String, String>,
): Candidate? {
    val payload = entry.payload

    when (entry.kind) {
        EntryKind.CLAIM -> {
            val newer = supersededBy[id]
            if (!policy.dropSupersededClaims || newer == null) {
                return null
            }
            // A contested claim is the subject of adjudication; it stays until resolved.
            if (payload.string("status") == "contested") {
                return null
            }
            return Candidate(
                entry,
                id,
                PruneReason.SUPERSEDED,
                "if claim $newer is retracted or contested",
            )
        }

        EntryKind.HYPOTHESIS -> {
            if (!policy.dropClosedHypotheses) {
                return null
            }
            val status = payload.string("status") ?: ""
            if (!CLOSED_HYPOTHESES.contains(status)) {
                return null
            }
            return Candidate(
                entry,
                id,
                PruneReason.CLOSED,
                "if new evidence contradicts hypothesis $id",
            )
        }

        EntryKind.UNKNOWN -> {
            if (!policy.dropResolvedUnknowns) {
                return null
            }
            if (payload.bool("blocking") == true) {
                return null
            }
            if (payload.string("status") != "resolved") {
                return null
            }
            return Candidate(entry, id, PruneReason.RESOLVED, "if unknown $id reopens")
        }

        EntryKind.EVENT, EntryKind.FRAME, EntryKind.RECEIPT, EntryKind.GOAL, EntryKind.PLAN -> return null
    }
    return null
}

/**
 * Build a view of a verified snapshot.
 *
 * Order of work: the retention floor, then per-kind eligibility, then the
 * reference closure. The closure runs last and wins: a superseded claim that a
 * retained plan still cites is kept, because dropping it would leave the
 * citation dangling.
 */
fun planPrune(snapshot: LedgerSnapshot, overrides: PruneOverrides = PruneOverrides()): PruneResult {
    val verdict = verifySnapshot(snapshot)
    if (!verdict.ok) {
        throw IllegalStateException(
            "refusing to prune an unverified ledger: ${verdict.reasons.joinToString("; ")}",
        )
    }

    val policy = PrunePolicy(
        keepRecent = overrides.keepRecent ?: PrunePolicy.DEFAULT_KEEP_RECENT,
        dropSupersededClaims = overrides.dropSupersededClaims ?: true,
        dropClosedHypotheses = overrides.dropClosedHypotheses ?: true,
        dropResolvedUnknowns = overrides.dropResolvedUnknowns ?: true,
    )

    val entries = snapshot.entries
    val floor = maxOf(0, policy.keepRecent)
    val cutoff = entries.size - floor

    val supersededBy = LinkedHashMap<String, String>()
    entries.forEach { entry ->
        if (entry.kind != EntryKind.CLAIM) {
            return@forEach
        }
        val supersededId = entry.payload.obj("supersedes")?.string("id") ?: return@forEach
        // The reference interpolates the payload id directly; a claim without
        // one therefore records the literal "undefined" rather than skipping.
        supersededBy[supersededId] = entry.payload.string("id") ?: "undefined"
    }

    val candidates = ArrayList<Candidate>()
    entries.forEachIndexed { index, entry ->
        if (index >= cutoff) {
            return@forEachIndexed
        }
        val id = idOf(entry.payload) ?: return@forEachIndexed
        val decision = classify(entry, id, policy, supersededBy) ?: return@forEachIndexed
        candidates += decision
    }

    val candidateSeqs = candidates.mapTo(HashSet()) { it.entry.seq }
    val retained = entries.filter { it.seq !in candidateSeqs }

    // Reference closure: retained objects pull in whatever they cite.
    val queue = ArrayDeque<ChainEntry>()
    queue.addAll(retained)
    while (queue.isNotEmpty()) {
        val entry = queue.removeLastOrNull() ?: break
        val refs = HashSet<String>()
        collectRefs(entry.payload, refs)
        candidates.forEach { candidate ->
            if (!candidateSeqs.contains(candidate.entry.seq)) {
                return@forEach
            }
            if (!refs.contains(keyOf(candidate.entry.kind.wire, candidate.id))) {
                return@forEach
            }
            candidateSeqs.remove(candidate.entry.seq)
            queue.add(candidate.entry)
        }
    }

    val dropped = candidates.filter { candidateSeqs.contains(it.entry.seq) }.map { candidate ->
        PruneDecision(
            seq = candidate.entry.seq,
            kind = candidate.entry.kind,
            id = candidate.id,
            reason = candidate.reason,
            revisitIf = candidate.revisitIf,
            bytes = utf8Bytes(candidate.entry),
        )
    }

    val reclaimedBytes = dropped.sumOf { it.bytes }
    val kept = entries.filter { it.seq !in candidateSeqs }

    return PruneResult(
        plan = PrunePlan(
            agent = snapshot.agent,
            sourceTip = snapshot.tip,
            sourceCount = snapshot.count,
            kept = kept.map { it.seq },
            dropped = dropped,
            reclaimedBytes = reclaimedBytes,
            estimatedTokens = (reclaimedBytes + 3) / 4,
            policy = policy,
        ),
        keep = kept,
    )
}

/**
 * Replace the cold prefix with a checkpoint.
 *
 * Refuses when the retained tail still references an entry in the prefix: the
 * checkpoint would commit to data the agent can no longer read, which is how a
 * ledger becomes unusable while claiming to be intact.
 */
fun compactLedger(snapshot: LedgerSnapshot, options: CompactionOptions = CompactionOptions()): Compaction {
    val verdict = verifySnapshot(snapshot)
    if (!verdict.ok) {
        throw CompactionRefused(
            "refusing to compact an unverified ledger: ${verdict.reasons.joinToString("; ")}",
        )
    }

    val keepRecent = maxOf(0, options.keepRecent ?: 0)
    val cut = snapshot.entries.size - keepRecent
    if (cut <= 0) {
        throw CompactionRefused("nothing to compact: the ledger is inside the retention window")
    }

    val removed = snapshot.entries.subList(0, cut).toList()
    val tail = snapshot.entries.subList(cut, snapshot.entries.size).toList()

    val tailRefs = HashSet<String>()
    tail.forEach { entry -> collectRefs(entry.payload, tailRefs) }

    val pinned = ArrayList<String>()
    removed.forEach { entry ->
        val id = idOf(entry.payload)
        if (id != null && tailRefs.contains(keyOf(entry.kind.wire, id))) {
            pinned += keyOf(entry.kind.wire, id)
        }
    }
    if (pinned.isNotEmpty()) {
        throw CompactionRefused(
            "refusing to compact entries the retained tail still references",
            pinned,
        )
    }

    val first = removed.first()
    val last = removed.last()
    val root = CanonicalJson.digest(JsonValue.Arr(removed.map { it.toJson() }))
    val checkpoint = Checkpoint(
        id = "cp.${first.seq}-${last.seq}",
        agent = snapshot.agent,
        from = first.seq,
        to = last.seq,
        count = removed.size,
        tip = last.hash,
        root = root,
        created = options.now ?: isoNow(),
        policy = CheckpointPolicy(keepRecent = keepRecent),
    )

    val tailTip = tail.lastOrNull()?.hash ?: last.hash
    return Compaction(
        checkpoint = checkpoint,
        tail = LedgerSnapshot(
            agent = snapshot.agent,
            tip = tailTip,
            count = tail.size,
            entries = tail,
        ),
        removed = removed,
    )
}

/**
 * Check a compaction without trusting any of it.
 *
 * The root cannot be recomputed from the tail alone — that is the point of
 * compaction — so the caller must supply the removed entries. What this proves
 * is that *these* entries are the ones the checkpoint committed to, and that
 * the tail continues the same chain.
 */
fun verifyCompaction(compaction: Compaction): SnapshotVerdict {
    val reasons = ArrayList<String>()
    val checkpoint = compaction.checkpoint
    val tail = compaction.tail
    val removed = compaction.removed

    if (CanonicalJson.digest(JsonValue.Arr(removed.map { it.toJson() })) != checkpoint.root) {
        reasons += "checkpoint root does not cover the removed entries"
    }
    if (removed.size != checkpoint.count) {
        reasons += "checkpoint count ${checkpoint.count} does not match ${removed.size} removed entries"
    }

    val first = removed.firstOrNull()
    val last = removed.lastOrNull()
    if (first == null || last == null) {
        reasons += "no entries were removed"
    } else {
        if (first.seq != checkpoint.from) {
            reasons += "checkpoint starts at ${checkpoint.from} but the first removed entry is ${first.seq}"
        }
        if (last.seq != checkpoint.to) {
            reasons += "checkpoint ends at ${checkpoint.to} but the last removed entry is ${last.seq}"
        }
        if (last.hash != checkpoint.tip) {
            reasons += "checkpoint tip does not match the last removed entry"
        }
    }

    if (tail.count != tail.entries.size) {
        reasons += "tail count ${tail.count} does not match ${tail.entries.size} entries"
    }

    var prev = checkpoint.tip
    tail.entries.forEachIndexed { index, entry ->
        val expectedSeq = checkpoint.to + 1 + index
        if (entry.seq != expectedSeq) {
            reasons += "tail entry $index: seq ${entry.seq} does not continue from ${checkpoint.to}"
        }
        if (entry.prev != prev) {
            reasons += "tail entry $index: prev does not continue the chain"
        }
        if (hashOfEntry(entry) != entry.hash) {
            reasons += "tail entry $index: hash does not cover its payload"
        }
        prev = entry.hash
    }

    if (tail.tip != prev) {
        reasons += "tail tip does not match the chain it contains"
    }

    return SnapshotVerdict.of(reasons)
}
