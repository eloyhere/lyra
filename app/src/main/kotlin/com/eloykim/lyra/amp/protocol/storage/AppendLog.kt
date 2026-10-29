package com.eloykim.lyra.amp.protocol.storage

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.ledger.ChainEntry
import com.eloykim.lyra.amp.protocol.ledger.CheckpointOptions
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import com.eloykim.lyra.amp.protocol.ledger.LedgerCheckpoint
import com.eloykim.lyra.amp.protocol.ledger.LedgerSnapshot
import com.eloykim.lyra.amp.protocol.ledger.SnapshotVerdict
import com.eloykim.lyra.amp.protocol.ledger.sealCheckpoint
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * Append-only JSONL log and hash chain (port of
 * `src/storage/appendLog.ts` and `src/storage/fileAppendSink.ts`).
 *
 * Every line is one canonical entry serialized as JSON:
 * `{seq, kind, payload, prev, hash}` plus optional advisory `at`. The hash is
 * exactly `digestOf({seq, kind, payload, prev})` from `ledger/persistence.ts`,
 * so a log can be exported as a [LedgerSnapshot] and checked by existing
 * tooling.
 *
 * ## Reopen and crash safety
 *
 * [AppendLog.open] parses and verifies every line before returning. A malformed
 * line, a gap, a bad link or a hash mismatch throws [AppendLogError] with every
 * reason found, so a half-written tail is a crash rather than something the log
 * silently skips. A clean truncation of the tail still produces a shorter,
 * internally valid prefix; detecting it requires an external commitment such as
 * a signed checkpoint.
 *
 * ## Binding seams and differences
 *
 * - `payload` is a [JsonValue], not `unknown`. The reference normalizes through
 *   `JSON.stringify`/`JSON.parse` before hashing; [normalizePayload] performs the
 *   same JSON-level rewrite (deep copy, non-finite numbers become `null`).
 * - A line with no `payload` field is rejected. The reference can only produce
 *   such a line by appending `undefined`, which its JSON serialization drops;
 *   the resulting entry cannot be represented faithfully by a typed Kotlin
 *   entry, so the port fails closed with an explicit reason.
 * - The reference serializes entries with a promise queue. Kotlin calls are
 *   synchronous; a host that shares one log across threads must serialize calls
 *   itself. A single [AppendLog] instance still keeps `count`/`tip` unchanged
 *   when the sink throws, so a failed append can be retried with the same seq.
 */

/** The genesis digest: no entries yet. */
val GENESIS: Digest = "sha256:" + "0".repeat(64)

/**
 * Pluggable destination for JSONL lines.
 *
 * `append` must not return until the line is accepted by the storage medium;
 * `readAll` returns the lines in write order. Each element is one complete JSON
 * object; blank lines are treated as corruption by [AppendLog.open]. A host can
 * wrap a file, a database table, or a remote log behind this interface.
 */
interface AppendSink {

    fun append(line: String)

    fun readAll(): List<String>
}

/**
 * In-memory sink for tests, demos, and deterministic replays.
 *
 * Nothing is durable: the lines live in a list and disappear with the process.
 * `readAll` returns a copy; [lines] is a live view for harnesses.
 */
class MemoryAppendSink(lines: List<String> = emptyList()) : AppendSink {

    private val items: MutableList<String> = ArrayList(lines)

    override fun append(line: String) {
        items += line
    }

    override fun readAll(): List<String> = ArrayList(items)

    /** Live view of the stored lines for test harnesses; `readAll()` returns the copy. */
    val lines: MutableList<String> get() = items
}

/**
 * One entry in the append-only log.
 *
 * `payload` is mutable, matching the reference's plain object entries: mutating
 * an entry obtained from [AppendLog.entries] makes [AppendLog.verify] fail
 * closed. `at` is advisory metadata and is deliberately not covered by `hash`.
 */
data class LogEntry(
    val seq: Int,
    val kind: EntryKind,
    var payload: JsonValue,
    val prev: Digest,
    val hash: Digest,
    val at: Timestamp? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "seq" to jsonNumber(seq),
        "kind" to JsonValue.Str(kind.wire),
        "payload" to payload,
        "prev" to JsonValue.Str(prev),
        "hash" to JsonValue.Str(hash),
        "at" to at?.let { JsonValue.Str(it) },
    )

    /** The projection `ledger/persistence.ts` uses; payloads must be JSON objects. */
    fun toChainEntry(): ChainEntry = ChainEntry(
        seq = seq,
        kind = kind,
        payload = payload.asObject("log entry payload"),
        prev = prev,
        hash = hash,
    )
}

/** Thrown when a sink contains data that fails structural or hash verification. */
class AppendLogError(
    message: String,
    reasons: List<String> = emptyList(),
) : IllegalStateException(message) {

    /** Individual failures, in the order they were found. */
    val reasons: List<String> = ArrayList(reasons)
}

/** Options for [AppendLog.seal], mirroring `CheckpointOptions & {agent?}`. */
data class SealOptions(
    val tenant: String,
    /** Defaults to now inside `sealCheckpoint`. */
    val issued: Timestamp? = null,
    /** The previous checkpoint this one continues; its seq and digest are committed. */
    val previous: LedgerCheckpoint? = null,
    /** Overrides the log's agent for this checkpoint only. */
    val agent: String? = null,
)

/** Must match `ledger/persistence.ts` exactly so logs and snapshots verify alike. */
private fun hashOf(seq: Int, kind: EntryKind, payload: JsonValue, prev: Digest): Digest =
    CanonicalJson.digest(
        jsonObj(
            "seq" to jsonNumber(seq),
            "kind" to JsonValue.Str(kind.wire),
            "payload" to payload,
            "prev" to JsonValue.Str(prev),
        ),
    )

/**
 * JSONL stores JSON, not JavaScript values. Normalizing before hashing keeps the
 * line self-consistent for `undefined` properties, `Date`, `-0`, `NaN` and other
 * values `JSON.stringify` would otherwise rewrite or drop.
 *
 * [JsonValue] already is the JSON model, so the only rewrites left are the ones
 * a JSON encoder applies to numbers: non-finite values become `null`, and `-0`
 * becomes `0`.
 */
internal fun normalizePayload(value: JsonValue): JsonValue = when (value) {
    is JsonValue.Obj -> JsonValue.Obj(
        LinkedHashMap(value.fields.mapValues { (_, child) -> normalizePayload(child) }),
    )

    is JsonValue.Arr -> JsonValue.Arr(value.items.map { normalizePayload(it) })
    is JsonValue.Num -> {
        val number = value.value
        when {
            !number.isFinite() -> JsonValue.Null
            number == 0.0 -> JsonValue.Num(0.0)
            else -> JsonValue.Num(number)
        }
    }

    is JsonValue.Str, is JsonValue.Bool, JsonValue.Null -> value
}

private fun verifyEntries(entries: List<LogEntry>): List<String> {
    val reasons = ArrayList<String>()
    var prev: Digest = GENESIS

    entries.forEachIndexed { index, entry ->
        if (entry.seq != index) {
            reasons += "entry $index: seq ${entry.seq} is out of order"
        }
        if (entry.prev != prev) {
            reasons += "entry $index: prev does not match the previous hash"
        }
        val expected = hashOf(entry.seq, entry.kind, entry.payload, entry.prev)
        if (entry.hash != expected) {
            reasons += "entry $index: hash does not cover its canonical fields"
        }
        prev = entry.hash
    }

    return reasons
}

/**
 * Parse a JSON number that JavaScript would accept as a safe integer.
 *
 * Kotlin stores `seq` as [Int], so values outside the Int range are rejected
 * even though JavaScript's safe-integer range is wider; such a line could never
 * verify against an Int-indexed chain anyway.
 */
private fun safeInt(value: JsonValue?): Int? {
    val number = (value as? JsonValue.Num)?.value ?: return null
    if (!number.isFinite() || number != Math.floor(number)) {
        return null
    }
    if (number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()) {
        return null
    }
    return number.toInt()
}

/** Parses one JSONL line without trusting its `seq`, `prev`, or `hash` fields. */
private fun entryFromLine(line: String, index: Int, reasons: MutableList<String>): LogEntry? {
    if (line.trim().isEmpty()) {
        reasons += "line $index: empty line"
        return null
    }

    val value = try {
        JsonParser.parse(line)
    } catch (error: IllegalArgumentException) {
        reasons += "line $index: not valid JSON"
        return null
    }
    if (value !is JsonValue.Obj) {
        reasons += "line $index: expected a JSON object"
        return null
    }

    val seq = safeInt(value["seq"])
    if (seq == null) {
        reasons += "line $index: seq is not an integer"
        return null
    }

    val kindWire = value.string("kind")
    val kind = kindWire?.let { wire -> EntryKind.entries.firstOrNull { it.wire == wire } }
    if (kind == null) {
        reasons += "line $index: unknown entry kind $kindWire"
        return null
    }

    val prev = value.string("prev")
    if (prev == null || !Sha256.isValidDigest(prev)) {
        reasons += "line $index: prev is not a sha256 digest"
        return null
    }

    val hash = value.string("hash")
    if (hash == null || !Sha256.isValidDigest(hash)) {
        reasons += "line $index: hash is not a sha256 digest"
        return null
    }

    if (value.has("at") && value["at"] !is JsonValue.Str) {
        reasons += "line $index: at is not a timestamp string"
        return null
    }

    if (!value.has("payload")) {
        // See the file KDoc: a reference line only lacks `payload` when its
        // payload was `undefined`, a value this typed port cannot represent.
        reasons += "line $index: payload is missing"
        return null
    }

    return LogEntry(
        seq = seq,
        kind = kind,
        payload = value["payload"] ?: JsonValue.Null,
        prev = prev,
        hash = hash,
        at = value.string("at"),
    )
}

/**
 * A verified, append-only hash chain over a pluggable [AppendSink].
 *
 * `open` never returns a partially trusted log. `append` writes exactly one line
 * and only advances the in-memory tip after the sink returns. Durability and
 * atomicity are properties of the sink, not of this class; there is no guarantee
 * beyond "the sink returned".
 */
class AppendLog private constructor(
    private val sink: AppendSink,
    private val items: MutableList<LogEntry>,
    agent: String,
) {

    /**
     * Owner of the ledger. Needed when sealing a checkpoint, because a checkpoint
     * binds the agent identity as well as the tip.
     */
    var agent: String = agent

    companion object {

        /**
         * Read every line, parse it, and verify the chain before returning.
         *
         * An empty sink opens as an empty log with the genesis tip. Any malformed
         * line, gap, bad link, or hash mismatch throws [AppendLogError]; the log
         * is never returned in a partially trusted state.
         */
        fun open(sink: AppendSink, agent: String = ""): AppendLog {
            val lines = sink.readAll()
            val reasons = ArrayList<String>()
            val entries = ArrayList<LogEntry>()

            // The reference stops at the first unparseable line: a later line
            // cannot be checked against a missing predecessor.
            for (index in lines.indices) {
                val entry = entryFromLine(lines[index], index, reasons)
                if (entry == null) {
                    break
                }
                entries += entry
            }
            if (reasons.isEmpty()) {
                reasons += verifyEntries(entries)
            }
            if (reasons.isNotEmpty()) {
                throw AppendLogError(
                    "append log failed verification: ${reasons.joinToString("; ")}",
                    reasons,
                )
            }
            return AppendLog(sink, entries, agent)
        }
    }

    /** Number of entries currently committed. */
    val count: Int get() = items.size

    /** Hash of the last entry, or the genesis hash for an empty log. */
    val tip: Digest get() = items.lastOrNull()?.hash ?: GENESIS

    /** A shallow copy of the entries, in sequence order. */
    fun entries(): List<LogEntry> = ArrayList(items)

    /** Recheck the in-memory chain; a mutation since `open`/`append` fails closed. */
    fun verify(): SnapshotVerdict = SnapshotVerdict.of(verifyEntries(items))

    /**
     * Append one entry and one JSONL line.
     *
     * In-memory `count`/`tip` advance only after `sink.append` returns; if it
     * throws, this instance is unchanged and the same sequence number can be
     * retried. The payload is normalized and copied before hashing, so later
     * caller mutations cannot change what was stored.
     */
    fun append(kind: EntryKind, payload: JsonValue, at: Timestamp? = null): LogEntry {
        val seq = items.size
        val prev = tip
        val stored = normalizePayload(payload)
        val hash = hashOf(seq, kind, stored, prev)
        val entry = LogEntry(seq = seq, kind = kind, payload = stored, prev = prev, hash = hash, at = at)

        sink.append(CanonicalJson.encode(entry.toJson()))
        items += entry
        return entry
    }

    /**
     * Sign the current tip through `sealCheckpoint`.
     *
     * Refuses to sign a log whose in-memory chain no longer verifies. The agent
     * comes from `options.agent` if present, otherwise from this log; sealing
     * without an agent is an error rather than an anonymous checkpoint.
     */
    fun seal(key: AmpSigner, options: SealOptions): LedgerCheckpoint {
        val verdict = verify()
        if (!verdict.ok) {
            throw AppendLogError("refusing to seal a broken append log", verdict.reasons)
        }

        val agent = options.agent ?: this.agent
        if (agent.isBlank()) {
            throw AppendLogError(
                "cannot seal a checkpoint without an agent: pass one to AppendLog.open or in the seal options",
                listOf("agent is empty"),
            )
        }

        val snapshot = LedgerSnapshot(
            amp = AmpWire.VERSION,
            kind = LedgerSnapshot.KIND,
            agent = agent,
            tip = tip,
            count = count,
            entries = items.map { it.toChainEntry() },
        )
        return sealCheckpoint(
            snapshot,
            key,
            CheckpointOptions(
                tenant = options.tenant,
                issued = options.issued,
                previous = options.previous,
            ),
        )
    }
}
