package com.eloykim.lyra.amp.protocol.types

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import java.time.Instant
import java.time.temporal.ChronoUnit

typealias Id = String
typealias Digest = String
typealias Timestamp = String
typealias Confidence = Double
typealias Usage = Budget

enum class Tier(val wire: String) {
    T0("T0"), T1("T1"), T2("T2"), T3("T3"), T4("T4");

    companion object {
        fun fromWire(value: String): Tier =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 tier: $value")
    }
}

enum class Trust(val wire: String) {
    SELF("self"), VERIFIED("verified"), TRUSTED("trusted"), ASSERTED("asserted"), INFERRED("inferred"), EXTERNAL("external");

    companion object {
        fun fromWire(value: String): Trust =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 trust: $value")
    }
}

enum class RefKind(val wire: String) {
    ENTITY("entity"), CONCEPT("concept"), PROCESS("process"), THREAD("thread"), QUESTION("question"),
    HYPOTHESIS("hypothesis"), CLAIM("claim"), EVIDENCE("evidence"), GOAL("goal"), PLAN("plan"),
    TASK("task"), TOOL("tool"), MEMORY("memory"), OTHER("other"), HUMAN("human"), AGENT("agent"),
    EVENT("event"), HANDLE("handle"), FRAME("frame"), SELF("self"), UNKNOWN("unknown"),
    COMMON_GROUND("common-ground"), CAPABILITY("capability"), RECEIPT("receipt"), ORG("org");

    companion object {
        fun fromWire(value: String): RefKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 ref.kind: $value")
    }
}

enum class SignatureRole(val wire: String) {
    SELF("self"), HOST("host"), REGISTRY("registry"), ORG("org"), TENANT("tenant");

    companion object {
        fun fromWire(value: String): SignatureRole =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知签名 role: $value")
    }
}

enum class HandleKind(val wire: String) {
    TABLE("table"), TEXT("text"), TREE("tree"), BLOB("blob"), GRAPH("graph");

    companion object {
        fun fromWire(value: String): HandleKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 handle.kind: $value")
    }
}

enum class HandleScope(val wire: String) {
    TURN("turn"), SESSION("session"), TASK("task");

    companion object {
        fun fromWire(value: String): HandleScope =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 handle.scope: $value")
    }
}

enum class ProvenanceKind(val wire: String) {
    OBSERVATION("observation"), TESTIMONY("testimony"), INFERENCE("inference"), TOOL("tool"),
    MEMORY("memory"), DOCUMENT("document"), SIMULATION("simulation");

    companion object {
        fun fromWire(value: String): ProvenanceKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 provenance.kind: $value")
    }
}

enum class CriterionKind(val wire: String) {
    OBSERVE("observe"), QUERY("query"), ASSERT("assert");

    companion object {
        fun fromWire(value: String): CriterionKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 criterion.kind: $value")
    }
}

data class Ref(val kind: RefKind, val id: Id, val label: String? = null) {

    init {
        ProtocolIds.require(id, "Ref.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind.wire),
        "id" to JsonValue.Str(id),
        "label" to label?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Ref {
            val obj = value.asObject("ref")
            return Ref(
                kind = RefKind.fromWire(obj.requireString("kind")),
                id = obj.requireString("id"),
                label = obj.string("label"),
            )
        }
    }
}

data class Budget(
    val tokens: Int? = null,
    val ms: Long? = null,
    val calls: Int? = null,
    val interrupts: Int? = null,
    val money: Double? = null,
) {

    init {
        require(tokens == null || tokens >= 0) { "budget.tokens 必须 >= 0" }
        require(ms == null || ms >= 0) { "budget.ms 必须 >= 0" }
        require(calls == null || calls >= 0) { "budget.calls 必须 >= 0" }
        require(interrupts == null || interrupts >= 0) { "budget.interrupts 必须 >= 0" }
        require(money == null || (money >= 0.0 && money.isFinite())) { "budget.money 必须是非负有限数" }
    }

    val isEmpty: Boolean
        get() = tokens == null && ms == null && calls == null && interrupts == null && money == null

    fun toJson(): JsonValue.Obj = jsonObj(
        "tokens" to tokens?.let { jsonNumber(it) },
        "ms" to ms?.let { jsonNumber(it) },
        "calls" to calls?.let { jsonNumber(it) },
        "interrupts" to interrupts?.let { jsonNumber(it) },
        "money" to money?.let { jsonNumber(it) },
    )

    fun exceeds(other: Budget): Boolean {
        tokens?.let { value -> other.tokens?.let { if (value > it) return true } }
        ms?.let { value -> other.ms?.let { if (value > it) return true } }
        calls?.let { value -> other.calls?.let { if (value > it) return true } }
        interrupts?.let { value -> other.interrupts?.let { if (value > it) return true } }
        money?.let { value -> other.money?.let { if (value > it) return true } }
        return false
    }

    companion object {
        fun fromJson(value: JsonValue): Budget {
            val obj = value.asObject("budget")
            return Budget(
                tokens = obj.int("tokens"),
                ms = obj.long("ms"),
                calls = obj.int("calls"),
                interrupts = obj.int("interrupts"),
                money = obj.double("money"),
            )
        }
    }
}

data class Signature(
    val role: SignatureRole,
    val alg: String,
    val kid: String,
    val sig: String,
) {

    init {
        require(alg == "ed25519") { "AMP 只支持 ed25519 签名" }
        require(kid.isNotBlank()) { "signature.kid 不能为空" }
        require(sig.isNotBlank()) { "signature.sig 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "role" to JsonValue.Str(role.wire),
        "alg" to JsonValue.Str(alg),
        "kid" to JsonValue.Str(kid),
        "sig" to JsonValue.Str(sig),
    )

    companion object {
        fun fromJson(value: JsonValue): Signature {
            val obj = value.asObject("signature")
            return Signature(
                role = SignatureRole.fromWire(obj.requireString("role")),
                alg = obj.requireString("alg"),
                kid = obj.requireString("kid"),
                sig = obj.requireString("sig"),
            )
        }
    }
}

data class Handle(
    val id: String,
    val kind: HandleKind,
    val scope: HandleScope,
    val tokens: Int? = null,
    val bytes: Long? = null,
    val preview: String? = null,
    val ttlMs: Long? = null,
) {

    init {
        require(id.isNotBlank()) { "handle.id 不能为空" }
        require(tokens == null || tokens >= 0) { "handle.tokens 必须 >= 0" }
        require(bytes == null || bytes >= 0) { "handle.bytes 必须 >= 0" }
        require(ttlMs == null || ttlMs >= 0) { "handle.ttl_ms 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "kind" to JsonValue.Str(kind.wire),
        "scope" to JsonValue.Str(scope.wire),
        "tokens" to tokens?.let { jsonNumber(it) },
        "bytes" to bytes?.let { jsonNumber(it) },
        "preview" to preview?.let { JsonValue.Str(it) },
        "ttl_ms" to ttlMs?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Handle {
            val obj = value.asObject("handle")
            return Handle(
                id = obj.requireString("id"),
                kind = HandleKind.fromWire(obj.requireString("kind")),
                scope = HandleScope.fromWire(obj.requireString("scope")),
                tokens = obj.int("tokens"),
                bytes = obj.long("bytes"),
                preview = obj.string("preview"),
                ttlMs = obj.long("ttl_ms"),
            )
        }
    }
}

data class Provenance(
    val source: String,
    val kind: ProvenanceKind? = null,
    val trust: Trust? = null,
    val at: Timestamp? = null,
    val digest: Digest? = null,
    val note: String? = null,
) {

    init {
        require(source.isNotBlank()) { "provenance.source 不能为空" }
        require(digest == null || Sha256.isValidDigest(digest)) { "provenance.digest 格式非法" }
        require(at == null || isTimestamp(at)) { "provenance.at 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "source" to JsonValue.Str(source),
        "kind" to kind?.let { JsonValue.Str(it.wire) },
        "trust" to trust?.let { JsonValue.Str(it.wire) },
        "at" to at?.let { JsonValue.Str(it) },
        "digest" to digest?.let { JsonValue.Str(it) },
        "note" to note?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Provenance {
            val obj = value.asObject("provenance")
            return Provenance(
                source = obj.requireString("source"),
                kind = obj.string("kind")?.let(ProvenanceKind::fromWire),
                trust = obj.string("trust")?.let(Trust::fromWire),
                at = obj.string("at"),
                digest = obj.string("digest"),
                note = obj.string("note"),
            )
        }
    }
}

data class Criterion(
    val id: Id,
    val statement: String,
    val check: String,
    val kind: CriterionKind? = null,
) {

    init {
        ProtocolIds.require(id, "Criterion.id")
        require(statement.isNotBlank()) { "criterion.statement 不能为空" }
        require(check.isNotBlank()) { "criterion.check 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "statement" to JsonValue.Str(statement),
        "check" to JsonValue.Str(check),
        "kind" to kind?.let { JsonValue.Str(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): Criterion {
            val obj = value.asObject("criterion")
            return Criterion(
                id = obj.requireString("id"),
                statement = obj.requireString("statement"),
                check = obj.requireString("check"),
                kind = obj.string("kind")?.let(CriterionKind::fromWire),
            )
        }
    }
}

fun requireConfidence(value: Double, name: String): Double {
    require(value.isFinite() && value in 0.0..1.0) { "$name 必须在 [0,1]" }
    return value
}

fun isTimestamp(value: String): Boolean =
    runCatching { Instant.parse(value) }.isSuccess

fun nowTimestamp(): Timestamp =
    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

fun JsonValue.asObject(what: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw IllegalArgumentException("$what 必须是 JSON 对象")
