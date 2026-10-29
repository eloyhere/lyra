package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest

/** 三种共享粒度。 */
enum class ShareMode(val wire: String) {
    FULL("full"),
    SUMMARY("summary"),
    REFERENCE("reference");

    companion object {
        fun fromWire(value: String): ShareMode =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 share mode: $value")
    }
}

/** summary 视图：按 kind 计数 + claim id，不含 payload。 */
data class SharedSummary(
    val byKind: Map<String, Int>,
    val claims: List<String>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "byKind" to JsonValue.Obj(
            LinkedHashMap<String, JsonValue>().apply {
                byKind.forEach { (kind, count) -> put(kind, jsonNumber(count)) }
            },
        ),
        "claims" to jsonStrings(claims),
    )
}

/**
 * 共享账本视图；`digest` 与模式相关的载荷叠加在共同的字段之上。
 */
data class SharedView(
    val amp: String = AmpWire.VERSION,
    val kind: String = SHARED_LEDGER_KIND,
    val mode: ShareMode,
    val agent: String,
    val tip: Digest,
    val count: Int,
    val digest: Digest,
    val entries: List<ChainEntry>? = null,
    val summary: SharedSummary? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "mode" to JsonValue.Str(mode.wire),
        "agent" to JsonValue.Str(agent),
        "tip" to JsonValue.Str(tip),
        "count" to jsonNumber(count),
        "digest" to JsonValue.Str(digest),
        "entries" to entries?.let { list -> JsonValue.Arr(list.map { it.toJson() }) },
        "summary" to summary?.toJson(),
    )
}

private const val SHARED_LEDGER_KIND: String = "shared-ledger"

private fun baseFields(
    mode: ShareMode,
    agent: String,
    tip: Digest,
    count: Int,
): JsonValue.Obj = jsonObj(
    "amp" to JsonValue.Str(AmpWire.VERSION),
    "kind" to JsonValue.Str(SHARED_LEDGER_KIND),
    "mode" to JsonValue.Str(mode.wire),
    "agent" to JsonValue.Str(agent),
    "tip" to JsonValue.Str(tip),
    "count" to jsonNumber(count),
)

/**
 * 三种共享粒度，全部可校验完整性（`share.ts`）。
 *
 * - full      —— 全量；只给同域的可信对端
 * - summary   —— 计数 + claim id；没有 payload
 * - reference —— 只有 tip + count + digest；接收方需要主动索取更多
 */
fun shareLedger(ledger: Ledger, agent: String, mode: ShareMode): SharedView {
    val snapshot = exportLedger(ledger, agent)
    val base = baseFields(mode = mode, agent = agent, tip = snapshot.tip, count = snapshot.count)

    return when (mode) {
        ShareMode.FULL -> {
            val entriesJson = JsonValue.Arr(snapshot.entries.map { it.toJson() })
            val digest = CanonicalJson.digest(base.copyWith("entries", entriesJson))
            SharedView(
                mode = mode,
                agent = agent,
                tip = snapshot.tip,
                count = snapshot.count,
                digest = digest,
                entries = snapshot.entries,
            )
        }

        ShareMode.SUMMARY -> {
            val byKind = LinkedHashMap<String, Int>()
            val claims = ArrayList<String>()
            snapshot.entries.forEach { entry ->
                byKind[entry.kind.wire] = (byKind[entry.kind.wire] ?: 0) + 1
                if (entry.kind == EntryKind.CLAIM) {
                    val id = entry.payload.string("id")
                    if (!id.isNullOrEmpty()) {
                        claims += id
                    }
                }
            }
            val byKindJson = JsonValue.Obj(
                LinkedHashMap<String, JsonValue>().apply {
                    byKind.forEach { (kind, count) -> put(kind, jsonNumber(count)) }
                },
            )
            val claimsJson = jsonStrings(claims)
            val digest = CanonicalJson.digest(
                base.copyWith("byKind", byKindJson).copyWith("claims", claimsJson),
            )
            SharedView(
                mode = mode,
                agent = agent,
                tip = snapshot.tip,
                count = snapshot.count,
                digest = digest,
                summary = SharedSummary(byKind = byKind, claims = claims),
            )
        }

        ShareMode.REFERENCE -> SharedView(
            mode = mode,
            agent = agent,
            tip = snapshot.tip,
            count = snapshot.count,
            digest = CanonicalJson.digest(base),
        )
    }
}
