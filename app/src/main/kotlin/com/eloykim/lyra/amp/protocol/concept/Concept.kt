package com.eloykim.lyra.amp.protocol.concept

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/**
 * AMP v0.1 — Concept：可以占用一个注意力槽的压缩模式。
 *
 * 对应 `docs/schemas/concept.schema.json` 与参考实现 `src/concept/concept.ts`。
 * `intension` 是原型式的分级定义特征，不是 OWL 类表达式；`counterexamples` 是
 * 证伪物，任何裁剪都不得删除（见 [ConceptBudget] 的 C-1..C-4）。
 */
enum class ConceptStatus(val wire: String) {
    PROPOSED("proposed"),
    ACTIVE("active"),
    DEPRECATED("deprecated"),
    MERGED("merged"),
    SPLIT("split"),
    ;

    companion object {
        fun fromWire(value: String): ConceptStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept.status: $value")
    }
}

enum class ConceptScale(val wire: String) {
    NOMINAL("nominal"),
    ORDINAL("ordinal"),
    INTERVAL("interval"),
    RATIO("ratio"),
    ;

    companion object {
        fun fromWire(value: String): ConceptScale =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept.dimensions.scale: $value")
    }
}

enum class ConceptRelationType(val wire: String) {
    IS_A("is-a"),
    PART_OF("part-of"),
    CAUSES("causes"),
    ENABLES("enables"),
    CONTRASTS_WITH("contrasts-with"),
    DERIVED_FROM("derived-from"),
    PRECEDES("precedes"),
    ;

    companion object {
        fun fromWire(value: String): ConceptRelationType =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept.relations.type: $value")
    }
}

enum class ConceptRevisionOp(val wire: String) {
    MERGE("merge"),
    SPLIT("split"),
    REFINE("refine"),
    RETIRE("retire"),
    ;

    companion object {
        fun fromWire(value: String): ConceptRevisionOp =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept.revision.op: $value")
    }
}

/** 定义特征（原型式，可带权重）。 */
data class ConceptFeature(
    val feature: String,
    val weight: Confidence? = null,
) {

    init {
        weight?.let { requireConfidence(it, "concept.intension.weight") }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "feature" to JsonValue.Str(feature),
        "weight" to weight?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptFeature {
            val obj = value.asObject("concept.intension")
            return ConceptFeature(
                feature = obj.requireString("feature"),
                weight = obj.double("weight"),
            )
        }
    }
}

/** 概念空间维度；概念是几何的而非特征的时候使用。 */
data class ConceptDimension(
    val name: String,
    val scale: ConceptScale? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "name" to JsonValue.Str(name),
        "scale" to scale?.let { JsonValue.Str(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptDimension {
            val obj = value.asObject("concept.dimensions")
            return ConceptDimension(
                name = obj.requireString("name"),
                scale = obj.string("scale")?.let(ConceptScale::fromWire),
            )
        }
    }
}

data class ConceptRelation(
    val type: ConceptRelationType,
    val target: Ref,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "type" to JsonValue.Str(type.wire),
        "target" to target.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptRelation {
            val obj = value.asObject("concept.relations")
            return ConceptRelation(
                type = ConceptRelationType.fromWire(obj.requireString("type")),
                target = Ref.fromJson(obj.requireObj("target")),
            )
        }
    }
}

/** 例子或反例；`typicality` 表示它对概念有多典型。 */
data class ConceptExample(
    val ref: Ref? = null,
    val description: String,
    val typicality: Confidence? = null,
) {

    init {
        typicality?.let { requireConfidence(it, "concept.example.typicality") }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "ref" to ref?.toJson(),
        "description" to JsonValue.Str(description),
        "typicality" to typicality?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptExample {
            val obj = value.asObject("concept.example")
            return ConceptExample(
                ref = obj.obj("ref")?.let(Ref::fromJson),
                description = obj.requireString("description"),
                typicality = obj.double("typicality"),
            )
        }
    }
}

/** 修订记录；操作只被记录，从不原地施加。 */
data class ConceptRevision(
    val op: ConceptRevisionOp,
    val at: Timestamp,
    val from: List<Ref>? = null,
    val reason: String? = null,
) {

    init {
        require(isTimestamp(at)) { "concept.revision.at 不是合法时间: $at" }
        require(reason == null || reason.isNotBlank()) { "concept.revision.reason 不能为空白" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "op" to JsonValue.Str(op.wire),
        "at" to JsonValue.Str(at),
        "from" to from?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "reason" to reason?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptRevision {
            val obj = value.asObject("concept.revision")
            return ConceptRevision(
                op = ConceptRevisionOp.fromWire(obj.requireString("op")),
                at = obj.requireString("at"),
                from = obj.arr("from")?.items?.map { Ref.fromJson(it) },
                reason = obj.string("reason"),
            )
        }
    }
}

data class Concept(
    val id: Id,
    val label: String,
    val definition: String,
    val intension: List<ConceptFeature>,
    val status: ConceptStatus,
    val confidence: Confidence,
    val provenance: Provenance,
    val version: Int,
    val created: Timestamp,
    val dimensions: List<ConceptDimension>? = null,
    val theoryRefs: List<String>? = null,
    val extension: List<Ref>? = null,
    val prototype: String? = null,
    val examples: List<ConceptExample>? = null,
    val counterexamples: List<ConceptExample>? = null,
    val relations: List<ConceptRelation>? = null,
    val groundedIn: List<Ref>? = null,
    val revision: ConceptRevision? = null,
) {

    init {
        ProtocolIds.require(id, "concept.id")
        require(label.isNotBlank()) { "concept.label 不能为空" }
        require(definition.isNotBlank()) { "concept.definition 不能为空" }
        requireConfidence(confidence, "concept.confidence")
        require(version >= 1) { "concept.version 必须 >= 1" }
        require(isTimestamp(created)) { "concept.created 不是合法时间: $created" }
    }

    /** `revision` 只在参考实现确实有该字段时写入。 */
    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("concept"),
        "id" to JsonValue.Str(id),
        "label" to JsonValue.Str(label),
        "definition" to JsonValue.Str(definition),
        "intension" to JsonValue.Arr(intension.map { it.toJson() }),
        "dimensions" to dimensions?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "theory_refs" to theoryRefs?.let { jsonStrings(it) },
        "extension" to extension?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "prototype" to prototype?.let { JsonValue.Str(it) },
        "examples" to examples?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "counterexamples" to counterexamples?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "relations" to relations?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "grounded_in" to groundedIn?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "status" to JsonValue.Str(status.wire),
        "confidence" to jsonNumber(confidence),
        "provenance" to provenance.toJson(),
        "version" to jsonNumber(version),
        "created" to JsonValue.Str(created),
        "revision" to revision?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): Concept {
            val obj = value.asObject("concept")
            requireAmpEnvelope(obj, "concept", "concept")
            return Concept(
                id = obj.requireString("id"),
                label = obj.requireString("label"),
                definition = obj.requireString("definition"),
                intension = obj.requireArr("intension").items.map { ConceptFeature.fromJson(it) },
                status = ConceptStatus.fromWire(obj.requireString("status")),
                confidence = obj.requireDouble("confidence"),
                provenance = Provenance.fromJson(obj.requireObj("provenance")),
                version = obj.requireInt("version"),
                created = obj.requireString("created"),
                dimensions = obj.arr("dimensions")?.items?.map { ConceptDimension.fromJson(it) },
                theoryRefs = obj.optionalStrings("theory_refs"),
                extension = obj.arr("extension")?.items?.map { Ref.fromJson(it) },
                prototype = obj.string("prototype"),
                examples = obj.arr("examples")?.items?.map { ConceptExample.fromJson(it) },
                counterexamples = obj.arr("counterexamples")?.items?.map { ConceptExample.fromJson(it) },
                relations = obj.arr("relations")?.items?.map { ConceptRelation.fromJson(it) },
                groundedIn = obj.arr("grounded_in")?.items?.map { Ref.fromJson(it) },
                revision = obj.obj("revision")?.let(ConceptRevision::fromJson),
            )
        }
    }
}

internal fun requireAmpEnvelope(obj: JsonValue.Obj, expectedKind: String, what: String) {
    val amp = obj.requireString("amp")
    require(amp == AmpWire.VERSION) { "$what.amp 必须是 ${AmpWire.VERSION}" }
    val kind = obj.requireString("kind")
    require(kind == expectedKind) { "$what.kind 必须是 $expectedKind" }
}

internal fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")

internal fun JsonValue.Obj.optionalStrings(key: String): List<String>? =
    arr(key)?.items?.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }
