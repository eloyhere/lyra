package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.goal.Goal
import com.eloykim.lyra.amp.protocol.goal.Plan
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.receipt.AmpEvent
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.Hypothesis
import com.eloykim.lyra.amp.protocol.warrant.UnknownItem

/** 链上条目的八种 kind；`order` 是与参考实现一致的 kind 排序权重。 */
enum class EntryKind(val wire: String, val order: Int) {
    EVENT("event", 0),
    FRAME("frame", 1),
    CLAIM("claim", 2),
    HYPOTHESIS("hypothesis", 3),
    UNKNOWN("unknown", 4),
    GOAL("goal", 5),
    PLAN("plan", 6),
    RECEIPT("receipt", 7);

    companion object {
        fun fromWire(value: String): EntryKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 ledger entry kind: $value")
    }
}

/** 单条链记录；`hash` 覆盖 `{seq,kind,payload,prev}`。 */
data class ChainEntry(
    val seq: Int,
    val kind: EntryKind,
    val payload: JsonValue.Obj,
    val prev: Digest,
    val hash: Digest,
) {

    init {
        require(seq >= 0) { "chain entry seq 必须 >= 0" }
        require(Sha256.isValidDigest(prev)) { "chain entry prev 格式非法" }
        require(Sha256.isValidDigest(hash)) { "chain entry hash 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "seq" to jsonNumber(seq),
        "kind" to JsonValue.Str(kind.wire),
        "payload" to payload,
        "prev" to JsonValue.Str(prev),
        "hash" to JsonValue.Str(hash),
    )

    companion object {
        fun fromJson(value: JsonValue): ChainEntry {
            val obj = value.asObject("chain entry")
            return ChainEntry(
                seq = obj.requireInt("seq"),
                kind = EntryKind.fromWire(obj.requireString("kind")),
                payload = obj.requireObj("payload"),
                prev = obj.requireString("prev"),
                hash = obj.requireString("hash"),
            )
        }
    }
}

/**
 * 覆盖整个账本的单一时间链快照。
 *
 * 链是“只追加”在事后可检查的原因：改动、重排或删除任何一条，都会让它
 * 之后的所有哈希失效。
 */
data class LedgerSnapshot(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val agent: Id,
    val tip: Digest,
    val count: Int,
    val entries: List<ChainEntry>,
) {

    init {
        require(amp == AmpWire.VERSION) { "ledger-snapshot.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == KIND) { "ledger-snapshot.kind 必须是 $KIND" }
        ProtocolIds.require(agent, "ledger-snapshot.agent")
        require(Sha256.isValidDigest(tip)) { "ledger-snapshot.tip 格式非法" }
        require(count >= 0) { "ledger-snapshot.count 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "agent" to JsonValue.Str(agent),
        "tip" to JsonValue.Str(tip),
        "count" to jsonNumber(count),
        "entries" to JsonValue.Arr(entries.map { it.toJson() }),
    )

    companion object {

        const val KIND: String = "ledger-snapshot"

        fun fromJson(value: JsonValue): LedgerSnapshot {
            val obj = value.asObject("ledger-snapshot")
            return LedgerSnapshot(
                amp = obj.requireString("amp"),
                kind = obj.requireString("kind"),
                agent = obj.requireString("agent"),
                tip = obj.requireString("tip"),
                count = obj.requireInt("count"),
                entries = obj.requireArr("entries").items.map(ChainEntry::fromJson),
            )
        }
    }
}

/** 快照校验结论：不信任快照自报的 `count` 或 `tip`。 */
data class SnapshotVerdict(
    val ok: Boolean,
    val reasons: List<String>,
) {
    companion object {
        fun of(reasons: List<String>): SnapshotVerdict = SnapshotVerdict(reasons.isEmpty(), reasons)
    }
}

private val GENESIS: Digest = "sha256:" + "0".repeat(64)

private val TIMESTAMP_FIELDS: List<String> = listOf("at", "ts", "created", "since")

private data class LedgerItem(
    val kind: EntryKind,
    val at: String,
    val payload: JsonValue.Obj,
)

private fun hashOf(seq: Int, kind: EntryKind, payload: JsonValue.Obj, prev: Digest): Digest =
    CanonicalJson.digest(
        jsonObj(
            "seq" to jsonNumber(seq),
            "kind" to JsonValue.Str(kind.wire),
            "payload" to payload,
            "prev" to JsonValue.Str(prev),
        ),
    )

/**
 * 参考实现的 `timestampOf`：按 `at ?? ts ?? created ?? since` 取第一个存在的
 * 字段；若它不是字符串则返回空串，而不是继续向后找。
 */
private fun timestampOf(item: JsonValue.Obj): String {
    TIMESTAMP_FIELDS.forEach { field ->
        val candidate = item[field] ?: return@forEach
        if (candidate is JsonValue.Null) {
            return@forEach
        }
        return (candidate as? JsonValue.Str)?.value ?: ""
    }
    return ""
}

/**
 * 把账本导出为单一时间链（`exportLedger`）。
 *
 * 条目按 `at/ts/created/since` 排序；时间相同则按 [EntryKind.order]
 * （event, frame, claim, hypothesis, unknown, goal, plan, receipt）。
 * 哈希通过 canonical JSON 计算，不依赖属性顺序。
 */
fun exportLedger(ledger: Ledger, agent: Id): LedgerSnapshot {
    val items = ArrayList<LedgerItem>()

    fun add(kind: EntryKind, payloads: List<JsonValue.Obj>) {
        payloads.forEach { payload ->
            items += LedgerItem(kind = kind, at = timestampOf(payload), payload = payload)
        }
    }

    add(EntryKind.EVENT, ledger.events.map { it.toJson() })
    add(EntryKind.FRAME, ledger.frames.map { it.toJson() })
    add(EntryKind.CLAIM, ledger.claims.map { it.toJson() })
    add(EntryKind.HYPOTHESIS, ledger.hypotheses.map { it.toJson() })
    add(EntryKind.UNKNOWN, ledger.unknowns.map { it.toJson() })
    add(EntryKind.GOAL, ledger.goals.map { it.toJson() })
    add(EntryKind.PLAN, ledger.plans.map { it.toJson() })
    add(EntryKind.RECEIPT, ledger.receipts.map { it.toJson() })

    items.sortWith(compareBy({ it.at }, { it.kind.order }))

    val entries = ArrayList<ChainEntry>(items.size)
    var prev: Digest = GENESIS
    items.forEachIndexed { index, item ->
        val hash = hashOf(index, item.kind, item.payload, prev)
        entries += ChainEntry(seq = index, kind = item.kind, payload = item.payload, prev = prev, hash = hash)
        prev = hash
    }
    return LedgerSnapshot(agent = agent, tip = prev, count = entries.size, entries = entries)
}

/**
 * 结构 + 密码学校验，不信任快照的 `count` 或 `tip`（`verifySnapshot`）。
 */
fun verifySnapshot(snapshot: LedgerSnapshot): SnapshotVerdict {
    val reasons = ArrayList<String>()
    if (snapshot.amp != AmpWire.VERSION || snapshot.kind != LedgerSnapshot.KIND) {
        reasons += "not a ledger snapshot"
    }

    var prev: Digest = GENESIS
    snapshot.entries.forEachIndexed { index, entry ->
        if (entry.seq != index) {
            reasons += "entry $index: seq ${entry.seq} is out of order"
        }
        if (entry.prev != prev) {
            reasons += "entry $index: prev does not match the previous hash"
        }
        val expected = hashOf(entry.seq, entry.kind, entry.payload, entry.prev)
        if (entry.hash != expected) {
            reasons += "entry $index: hash does not cover its payload"
        }
        prev = entry.hash
    }

    if (snapshot.count != snapshot.entries.size) {
        reasons += "count ${snapshot.count} does not match ${snapshot.entries.size} entries"
    }
    if (snapshot.tip != prev) {
        reasons += "tip does not match the last entry"
    }
    return SnapshotVerdict.of(reasons)
}

/**
 * 已验证快照还原出的八个桶。
 *
 * 与参考实现一样，恢复只依赖快照内容；区别是 Kotlin 侧直接把 payload
 * 解析成对应类型，因此损坏的 payload 会在这里显式失败，而不是留到调用方。
 */
data class RestoredLedger(
    val events: List<AmpEvent>,
    val frames: List<Frame>,
    val receipts: List<Receipt>,
    val claims: List<Claim>,
    val hypotheses: List<Hypothesis>,
    val unknowns: List<UnknownItem>,
    val goals: List<Goal>,
    val plans: List<Plan>,
)

/**
 * 只恢复通过校验的快照；被篡改的链直接抛错，而不是加载。
 */
fun restoreLedger(snapshot: LedgerSnapshot): RestoredLedger {
    val verdict = verifySnapshot(snapshot)
    if (!verdict.ok) {
        throw IllegalStateException(
            "refusing to restore a broken snapshot: ${verdict.reasons.joinToString("; ")}",
        )
    }

    val events = ArrayList<AmpEvent>()
    val frames = ArrayList<Frame>()
    val receipts = ArrayList<Receipt>()
    val claims = ArrayList<Claim>()
    val hypotheses = ArrayList<Hypothesis>()
    val unknowns = ArrayList<UnknownItem>()
    val goals = ArrayList<Goal>()
    val plans = ArrayList<Plan>()

    snapshot.entries.forEach { entry ->
        when (entry.kind) {
            EntryKind.EVENT -> events += AmpEvent.fromJson(entry.payload)
            EntryKind.FRAME -> frames += Frame.fromJson(entry.payload)
            EntryKind.RECEIPT -> receipts += Receipt.fromJson(entry.payload)
            EntryKind.CLAIM -> claims += Claim.fromJson(entry.payload)
            EntryKind.HYPOTHESIS -> hypotheses += Hypothesis.fromJson(entry.payload)
            EntryKind.UNKNOWN -> unknowns += UnknownItem.fromJson(entry.payload)
            EntryKind.GOAL -> goals += Goal.fromJson(entry.payload)
            EntryKind.PLAN -> plans += Plan.fromJson(entry.payload)
        }
    }
    return RestoredLedger(
        events = events,
        frames = frames,
        receipts = receipts,
        claims = claims,
        hypotheses = hypotheses,
        unknowns = unknowns,
        goals = goals,
        plans = plans,
    )
}
