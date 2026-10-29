package com.eloykim.lyra.amp.protocol.concept

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * AMP v0.1 — concept economy 的 wire 对象。
 *
 * 概念是占用注意力槽的单位，因此它的大小在每次使用时都要付费。放着不管，
 * 概念会膨胀：例子堆积、关系重复、旧版本留在账本里。这一层同时约束两者，
 * 又不允许把定义或证伪物裁掉：
 *
 * - [ConceptPrunePlan] 是一个概念的有界*视图*。存储不动、`version` 不动：
 *   视图不是修订。
 * - [ConceptCheckpoint] 把除头版本外的每个版本替换成提交其摘要的检查点，
 *   使谱系仍然可证。
 *
 * 对应 `docs/schemas/concept-compaction.schema.json` 与
 * `src/concept/conceptBudgetTypes.ts`。
 */
data class ConceptBudget(
    val maxExamples: Int,
    val minExamples: Int,
    val maxRelations: Int,
    val keepVersions: Int,
) {

    init {
        require(maxExamples >= 0) { "concept-budget.maxExamples 必须 >= 0" }
        require(minExamples >= 0) { "concept-budget.minExamples 必须 >= 0" }
        require(maxRelations >= 0) { "concept-budget.maxRelations 必须 >= 0" }
        require(keepVersions >= 1) { "concept-budget.keepVersions 必须 >= 1" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("concept-budget"),
        "maxExamples" to jsonNumber(maxExamples),
        "minExamples" to jsonNumber(minExamples),
        "maxRelations" to jsonNumber(maxRelations),
        "keepVersions" to jsonNumber(keepVersions),
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptBudget {
            val obj = value.asObject("concept-budget")
            requireAmpEnvelope(obj, "concept-budget", "concept-budget")
            return ConceptBudget(
                maxExamples = obj.requireInt("maxExamples"),
                minExamples = obj.requireInt("minExamples"),
                maxRelations = obj.requireInt("maxRelations"),
                keepVersions = obj.requireInt("keepVersions"),
            )
        }
    }
}

enum class ConceptDropSlot(val wire: String) {
    EXAMPLE("example"),
    RELATION("relation"),
    EXTENSION("extension"),
    ;

    companion object {
        fun fromWire(value: String): ConceptDropSlot =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept-drop.slot: $value")
    }
}

enum class ConceptDropReason(val wire: String) {
    DUPLICATE("duplicate"),
    OVER_BUDGET("over-budget"),
    DANGLING("dangling"),
    ;

    companion object {
        fun fromWire(value: String): ConceptDropReason =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept-drop.reason: $value")
    }
}

/** 离开视图的一项；`summary` 是人类可读的身份，`revisit_if` 是回来条件。 */
data class ConceptDrop(
    val slot: ConceptDropSlot,
    val index: Int,
    val summary: String,
    val reason: ConceptDropReason,
    val revisitIf: String,
) {

    init {
        require(index >= 0) { "concept-drop.index 必须 >= 0" }
        require(summary.isNotBlank()) { "concept-drop.summary 不能为空" }
        require(revisitIf.isNotBlank()) { "concept-drop.revisit_if 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "slot" to JsonValue.Str(slot.wire),
        "index" to jsonNumber(index),
        "summary" to JsonValue.Str(summary),
        "reason" to JsonValue.Str(reason.wire),
        "revisit_if" to JsonValue.Str(revisitIf),
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptDrop {
            val obj = value.asObject("concept-drop")
            return ConceptDrop(
                slot = ConceptDropSlot.fromWire(obj.requireString("slot")),
                index = obj.requireInt("index"),
                summary = obj.requireString("summary"),
                reason = ConceptDropReason.fromWire(obj.requireString("reason")),
                revisitIf = obj.requireString("revisit_if"),
            )
        }
    }
}

data class ConceptPrunePlan(
    val concept: Id,
    val version: Int,
    val dropped: List<ConceptDrop>,
    val reclaimedBytes: Int,
    val budget: ConceptBudget,
) {

    init {
        ProtocolIds.require(concept, "concept-prune-plan.concept")
        require(version >= 1) { "concept-prune-plan.version 必须 >= 1" }
        require(reclaimedBytes >= 0) { "concept-prune-plan.reclaimedBytes 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("concept-prune-plan"),
        "concept" to JsonValue.Str(concept),
        "version" to jsonNumber(version),
        "dropped" to JsonValue.Arr(dropped.map { it.toJson() }),
        "reclaimedBytes" to jsonNumber(reclaimedBytes),
        "budget" to budget.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptPrunePlan {
            val obj = value.asObject("concept-prune-plan")
            requireAmpEnvelope(obj, "concept-prune-plan", "concept-prune-plan")
            return ConceptPrunePlan(
                concept = obj.requireString("concept"),
                version = obj.requireInt("version"),
                dropped = obj.requireArr("dropped").items.map { ConceptDrop.fromJson(it) },
                reclaimedBytes = obj.requireInt("reclaimedBytes"),
                budget = ConceptBudget.fromJson(obj.requireObj("budget")),
            )
        }
    }
}

/** 检查点内嵌的保留策略，schema 只允许 `keepVersions`。 */
data class ConceptCheckpointBudget(
    val keepVersions: Int,
) {

    init {
        require(keepVersions >= 1) { "concept-checkpoint.budget.keepVersions 必须 >= 1" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "keepVersions" to jsonNumber(keepVersions),
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptCheckpointBudget {
            val obj = value.asObject("concept-checkpoint.budget")
            return ConceptCheckpointBudget(keepVersions = obj.requireInt("keepVersions"))
        }
    }
}

/**
 * 用一个检查点替换被删掉的版本区间。
 *
 * `tip` 是被删的最后一个版本的摘要（便于持有者发现替换），`root` 是按版本顺序
 * 对被删版本整体求的摘要。
 */
data class ConceptCheckpoint(
    val id: Id,
    val concept: Id,
    val from: Int,
    val to: Int,
    val count: Int,
    val tip: Digest,
    val root: Digest,
    val created: Timestamp,
    val budget: ConceptCheckpointBudget,
) {

    init {
        ProtocolIds.require(id, "concept-checkpoint.id")
        ProtocolIds.require(concept, "concept-checkpoint.concept")
        require(from >= 1) { "concept-checkpoint.from 必须 >= 1" }
        require(to >= 1) { "concept-checkpoint.to 必须 >= 1" }
        require(count >= 1) { "concept-checkpoint.count 必须 >= 1" }
        require(Sha256.isValidDigest(tip)) { "concept-checkpoint.tip 格式非法" }
        require(Sha256.isValidDigest(root)) { "concept-checkpoint.root 格式非法" }
        require(isTimestamp(created)) { "concept-checkpoint.created 不是合法时间: $created" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("concept-checkpoint"),
        "id" to JsonValue.Str(id),
        "concept" to JsonValue.Str(concept),
        "from" to jsonNumber(from),
        "to" to jsonNumber(to),
        "count" to jsonNumber(count),
        "tip" to JsonValue.Str(tip),
        "root" to JsonValue.Str(root),
        "created" to JsonValue.Str(created),
        "budget" to budget.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): ConceptCheckpoint {
            val obj = value.asObject("concept-checkpoint")
            requireAmpEnvelope(obj, "concept-checkpoint", "concept-checkpoint")
            return ConceptCheckpoint(
                id = obj.requireString("id"),
                concept = obj.requireString("concept"),
                from = obj.requireInt("from"),
                to = obj.requireInt("to"),
                count = obj.requireInt("count"),
                tip = obj.requireString("tip"),
                root = obj.requireString("root"),
                created = obj.requireString("created"),
                budget = ConceptCheckpointBudget.fromJson(obj.requireObj("budget")),
            )
        }
    }
}
