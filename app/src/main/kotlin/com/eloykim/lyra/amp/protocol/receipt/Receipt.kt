package com.eloykim.lyra.amp.protocol.receipt

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Handle
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/** 结算动作的动词面。 */
enum class ReceiptOp(val wire: String) {
    ATTEND("attend"),
    ACT("act"),
    ASK("ask"),
    NOTE("note"),
    PLAN("plan"),
    REFLECT("reflect"),
    READ("read");

    companion object {
        fun fromWire(value: String): ReceiptOp =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 receipt.op: $value")
    }
}

/** 动作的实际结果。 */
enum class Outcome(val wire: String) {
    SUCCESS("success"),
    PARTIAL("partial"),
    FAILURE("failure"),
    BLOCKED("blocked");

    companion object {
        fun fromWire(value: String): Outcome =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 receipt.outcome: $value")
    }
}

/**
 * AMP v0.1 结算对象：receipt（`receipt.ts`，对应 `receipt.schema.json`）。
 *
 * receipt 是校准的唯一地面真值；event 是只追加日志。
 */
data class Receipt(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val id: Id,
    val op: ReceiptOp,
    val target: Ref? = null,
    val at: Timestamp,
    val ms: Long? = null,
    val usage: Budget,
    val outcome: Outcome,
    /**
     * 在动作之前记录，与 realized_voi 比较以校准控制器。
     */
    val predictedVoi: Confidence? = null,
    val realizedVoi: Confidence? = null,
    val handle: Handle? = null,
    val note: String? = null,
) : SettlementObject {

    init {
        require(amp == AmpWire.VERSION) { "receipt.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == KIND) { "receipt.kind 必须是 $KIND" }
        ProtocolIds.require(id, "receipt.id")
        require(isTimestamp(at)) { "receipt.at 不是合法时间" }
        require(ms == null || ms >= 0) { "receipt.ms 必须 >= 0" }
        predictedVoi?.let { requireConfidence(it, "receipt.predicted_voi") }
        realizedVoi?.let { requireConfidence(it, "receipt.realized_voi") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "op" to JsonValue.Str(op.wire),
        "target" to target?.toJson(),
        "at" to JsonValue.Str(at),
        "ms" to ms?.let { jsonNumber(it) },
        "usage" to usage.toJson(),
        "outcome" to JsonValue.Str(outcome.wire),
        "predicted_voi" to predictedVoi?.let { jsonNumber(it) },
        "realized_voi" to realizedVoi?.let { jsonNumber(it) },
        "handle" to handle?.toJson(),
        "note" to note?.let { JsonValue.Str(it) },
    )

    companion object {

        const val KIND: String = "receipt"

        fun fromJson(value: JsonValue): Receipt {
            val obj = value.asObject("receipt")
            return Receipt(
                amp = obj.requireString("amp"),
                kind = obj.requireString("kind"),
                id = obj.requireString("id"),
                op = ReceiptOp.fromWire(obj.requireString("op")),
                target = obj.optionalObj("target", "receipt.target")?.let(Ref::fromJson),
                at = obj.requireString("at"),
                ms = obj.long("ms"),
                usage = Budget.fromJson(obj.requireObj("usage")),
                outcome = Outcome.fromWire(obj.requireString("outcome")),
                predictedVoi = obj.double("predicted_voi"),
                realizedVoi = obj.double("realized_voi"),
                handle = obj.optionalObj("handle", "receipt.handle")?.let(Handle::fromJson),
                note = obj.optionalString("note", "receipt.note"),
            )
        }
    }
}

/**
 * 事件（`AmpEvent`，避免与 DOM `Event` 重名）：只追加日志的一条记录。
 */
data class AmpEvent(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val id: Id,
    val at: Timestamp,
    val actor: Ref,
    val type: String,
    val causedBy: Id? = null,
    val refs: List<Ref>? = null,
    val digest: Digest,
    val payload: JsonValue.Obj? = null,
) : SettlementObject {

    init {
        require(amp == AmpWire.VERSION) { "event.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == KIND) { "event.kind 必须是 $KIND" }
        ProtocolIds.require(id, "event.id")
        require(isTimestamp(at)) { "event.at 不是合法时间" }
        require(type.isNotBlank()) { "event.type 不能为空" }
        require(Sha256.isValidDigest(digest)) { "event.digest 格式非法" }
        causedBy?.let { ProtocolIds.require(it, "event.caused_by") }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "at" to JsonValue.Str(at),
        "actor" to actor.toJson(),
        "type" to JsonValue.Str(type),
        "caused_by" to causedBy?.let { JsonValue.Str(it) },
        "refs" to refs?.let { list -> JsonValue.Arr(list.map { it.toJson() }) },
        "digest" to JsonValue.Str(digest),
        "payload" to payload,
    )

    companion object {

        const val KIND: String = "event"

        fun fromJson(value: JsonValue): AmpEvent {
            val obj = value.asObject("event")
            val digest = obj.requireString("digest")
            require(Sha256.isValidDigest(digest)) { "event.digest 格式非法" }
            return AmpEvent(
                amp = obj.requireString("amp"),
                kind = obj.requireString("kind"),
                id = obj.requireString("id"),
                at = obj.requireString("at"),
                actor = Ref.fromJson(obj.requireObj("actor")),
                type = obj.requireString("type"),
                causedBy = obj.optionalString("caused_by", "event.caused_by"),
                refs = obj.optionalArray("refs", "event.refs")?.items?.map(Ref::fromJson),
                digest = digest,
                payload = obj.optionalObj("payload", "event.payload"),
            )
        }
    }
}

/**
 * `receipt.schema.json` 是一个 `oneOf`：receipt 或 event。
 */
sealed interface SettlementObject {

    fun toJson(): JsonValue.Obj

    companion object {
        fun fromJson(value: JsonValue): SettlementObject {
            val obj = value.asObject("settlement")
            return when (obj.requireString("kind")) {
                Receipt.KIND -> Receipt.fromJson(value)
                AmpEvent.KIND -> AmpEvent.fromJson(value)
                else -> throw IllegalArgumentException("未知 settlement kind: ${obj.string("kind")}")
            }
        }
    }
}

private fun JsonValue.Obj.optionalObj(key: String, what: String): JsonValue.Obj? {
    val field = fields[key] ?: return null
    if (field is JsonValue.Null) {
        return null
    }
    return field as? JsonValue.Obj ?: throw IllegalArgumentException("$what 必须是 JSON 对象")
}

private fun JsonValue.Obj.optionalArray(key: String, what: String): JsonValue.Arr? {
    val field = fields[key] ?: return null
    if (field is JsonValue.Null) {
        return null
    }
    return field as? JsonValue.Arr ?: throw IllegalArgumentException("$what 必须是 JSON 数组")
}

private fun JsonValue.Obj.optionalString(key: String, what: String): String? {
    val field = fields[key] ?: return null
    if (field is JsonValue.Null) {
        return null
    }
    return (field as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$what 必须是字符串")
}
