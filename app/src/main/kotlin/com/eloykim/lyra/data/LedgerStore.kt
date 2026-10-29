package com.eloykim.lyra.data

import android.content.ContentValues
import android.database.Cursor
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.receipt.AmpEvent
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 协议事件账本（AMP 0.1 `AmpEvent` + ledger hash chain）。
 *
 * 表只追加：`BEFORE UPDATE` / `BEFORE DELETE` 触发器直接 abort。
 * `event_json` 是完整的 canonical `AmpEvent`，`hash` 覆盖
 * `{seq, kind:"event", payload:event_json, prev}`，与参考实现
 * `ledger/persistence.ts` 的 `hashOf` 同构。
 *
 * UI 侧仍需要 `at`（epoch）、`actor`、`type`、`refs`、`payload` 这些扁平字段，
 * 因此同时保留一份投影；协议对象是事实来源，投影只用于展示。
 */
class LedgerStore internal constructor(private val helper: LyraDatabase) {

    private val lock = Any()

    @Volatile
    private var cached: List<LedgerEvent>? = null

    private fun events(): List<LedgerEvent> = cached ?: loadAll().also { cached = it }

    fun append(
        actor: String,
        type: String,
        payload: String,
        refs: List<String> = emptyList(),
        at: Long = System.currentTimeMillis(),
    ): LedgerEvent = synchronized(lock) {
        val current = events()
        val seq = current.size.toLong() + 1
        val prev = current.lastOrNull()?.hash ?: GENESIS
        val atIso = Instant.ofEpochMilli(at).truncatedTo(ChronoUnit.SECONDS).toString()
        val eventPayload = parsePayload(payload)
        val actorRef = actorRef(actor)
        val refsList = refs.map(::parseRef)
        val body = jsonObj(
            "amp" to JsonValue.Str(AmpWire.VERSION),
            "kind" to JsonValue.Str(AmpEvent.KIND),
            "id" to JsonValue.Str("ev.$seq"),
            "at" to JsonValue.Str(atIso),
            "actor" to actorRef.toJson(),
            "type" to JsonValue.Str(type),
            "refs" to JsonValue.Arr(refsList.map { it.toJson() }),
            "payload" to eventPayload,
        )
        val digest = CanonicalJson.digest(body)
        val eventJson = CanonicalJson.encode(body.copyWith("digest", JsonValue.Str(digest)))
        val hash = CanonicalJson.digest(
            jsonObj(
                "seq" to jsonNumber(seq),
                "kind" to JsonValue.Str("event"),
                "payload" to JsonParser.parse(eventJson),
                "prev" to JsonValue.Str(prev),
            ),
        )
        helper.writableDatabase.insertOrThrow(
            "amp_protocol_events",
            null,
            ContentValues().apply {
                put("seq", seq)
                put("event_id", "ev.$seq")
                put("at", at)
                put("at_iso", atIso)
                put("actor", actor)
                put("type", type)
                put("refs", refs.joinToString(","))
                put("payload", CanonicalJson.encode(eventPayload))
                put("digest", digest)
                put("prev_digest", prev)
                put("hash", hash)
                put("event_json", eventJson)
            },
        )
        cached = null
        LedgerEvent(
            seq = seq,
            at = at,
            atIso = atIso,
            actor = actor,
            type = type,
            refs = refs,
            payload = CanonicalJson.encode(eventPayload),
            digest = digest,
            prevDigest = prev,
            hash = hash,
            protocol = AmpEvent.fromJson(JsonParser.parse(eventJson)),
        )
    }

    fun loadAll(): List<LedgerEvent> {
        val rows = ArrayList<LedgerEvent>()
        helper.readableDatabase.query(
            "amp_protocol_events",
            arrayOf("seq", "at", "at_iso", "actor", "type", "refs", "payload", "digest", "prev_digest", "hash", "event_json"),
            null, null, null, null, "seq ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.toEvent()
            }
        }
        return rows
    }

    /** 返回违规描述；空表示事件 digest 与链哈希都完整。 */
    fun verify(): List<String> {
        val rows = loadAll()
        val problems = ArrayList<String>()
        var expectedSeq = 1L
        var prev = GENESIS
        rows.forEachIndexed { index, row ->
            if (row.seq != expectedSeq) {
                problems += "第 $index 条 seq=${row.seq}，期望 $expectedSeq"
            }
            if (row.prevDigest != prev) {
                problems += "第 $index 条 prev 不匹配"
            }
            val eventJson = row.eventJson
            val parsed = runCatching { JsonParser.parse(eventJson) }.getOrNull()
            if (parsed == null) {
                problems += "第 $index 条 event_json 不是合法 JSON"
            } else {
                val body = (parsed as? JsonValue.Obj)?.without("digest")
                if (body == null) {
                    problems += "第 $index 条 event_json 不是对象"
                } else {
                    if (CanonicalJson.digest(body) != row.digest) {
                        problems += "第 $index 条 event digest 被篡改"
                    }
                    val expectedHash = CanonicalJson.digest(
                        jsonObj(
                            "seq" to jsonNumber(row.seq),
                            "kind" to JsonValue.Str("event"),
                            "payload" to parsed,
                            "prev" to JsonValue.Str(row.prevDigest),
                        ),
                    )
                    if (expectedHash != row.hash) {
                        problems += "第 $index 条链哈希被篡改"
                    }
                }
            }
            prev = row.hash
            expectedSeq = row.seq + 1
        }
        return problems
    }

    fun size(): Int = runCatching { events().size }.getOrDefault(0)

    fun head(): LedgerEvent? = runCatching { events().lastOrNull() }.getOrNull()

    /**
     * 协议账本压缩尚未接入；保留旧调用点，返回 null 表示本次未压缩。
     * 删除旧事件会破坏外部可验证的链，必须先用 signed checkpoint 承诺前缀。
     */
    fun compact(keepRecent: Int = 500): LedgerCheckpoint? = null

    fun checkpoints(): List<LedgerCheckpoint> = emptyList()

    private fun Cursor.toEvent(): LedgerEvent {
        val eventJson = getString(10)
        val event = runCatching { AmpEvent.fromJson(JsonParser.parse(eventJson)) }.getOrNull()
            ?: throw IllegalStateException("amp_protocol_events 中存在无法解析的 event_json")
        return LedgerEvent(
            seq = getLong(0),
            at = getLong(1),
            atIso = getString(2),
            actor = getString(3),
            type = getString(4),
            refs = getString(5)?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            payload = getString(6),
            digest = getString(7),
            prevDigest = getString(8),
            hash = getString(9),
            protocol = event,
            eventJson = eventJson,
        )
    }

    private fun parsePayload(raw: String): JsonValue.Obj {
        val parsed = runCatching { JsonParser.parse(raw) }.getOrNull()
        return when (parsed) {
            is JsonValue.Obj -> parsed
            null -> jsonObj("raw" to JsonValue.Str(raw))
            else -> jsonObj("value" to parsed)
        }
    }

    private fun actorRef(actor: String): Ref = when (actor.lowercase()) {
        "agent", "lyra", "host" -> Ref(RefKind.SELF, "a.lyra")
        "system" -> Ref(RefKind.SELF, "a.lyra")
        else -> Ref(RefKind.OTHER, ProtocolIds.slug(actor, "actor"))
    }

    private fun parseRef(raw: String): Ref {
        val colon = raw.indexOf(':')
        if (colon > 0) {
            val kind = runCatching { RefKind.fromWire(raw.substring(0, colon)) }.getOrNull()
            val id = raw.substring(colon + 1)
            if (kind != null && ProtocolIds.isValid(id)) return Ref(kind, id)
        }
        return Ref(RefKind.OTHER, ProtocolIds.slug(raw, "ref"))
    }

    companion object {
        private const val GENESIS = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    }
}

/**
 * 展示/查询投影；[protocol] 是完整协议对象，其余字段供旧 UI 使用。
 */
data class LedgerEvent(
    val seq: Long,
    val at: Long,
    val atIso: String,
    val actor: String,
    val type: String,
    val refs: List<String>,
    val payload: String,
    val digest: String,
    val prevDigest: String,
    val hash: String,
    val protocol: AmpEvent,
    val eventJson: String = "",
)

/** 旧压缩 API 的返回类型占位；协议压缩由 signed checkpoint 承接后再启用。 */
data class LedgerCheckpoint(
    val from: Long,
    val to: Long,
    val count: Int,
    val root: String,
    val tip: String,
)
