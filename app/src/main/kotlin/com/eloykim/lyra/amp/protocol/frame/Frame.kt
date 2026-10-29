package com.eloykim.lyra.amp.protocol.frame

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/** `frame.ts`: 注意力分配的运行模式。 */
enum class FrameMode(val wire: String) {
    EXPLORE("explore"),
    EXPLOIT("exploit"),
    TRIAGE("triage"),
    REFLECT("reflect"),
    REST("rest");

    companion object {
        fun fromWire(value: String): FrameMode =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 frame.mode: $value")
    }
}

/** 一个 slot 在注意帧里扮演的角色。 */
enum class SlotRole(val wire: String) {
    GOAL("goal"),
    QUESTION("question"),
    EVIDENCE("evidence"),
    CONCEPT("concept"),
    PROCESS("process"),
    TOOL("tool"),
    MEMORY("memory"),
    SELF("self"),
    OTHER("other"),
    THREAT("threat"),
    ARTIFACT("artifact");

    companion object {
        fun fromWire(value: String): SlotRole =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 slot.role: $value")
    }
}

/** 刻意排除一个候选的原因。 */
enum class IgnoredReason(val wire: String) {
    LOW_VOI("low_voi"),
    OUT_OF_SCOPE("out_of_scope"),
    UNTRUSTED("untrusted"),
    BUDGET("budget"),
    BLOCKED("blocked"),
    HABITUATED("habituated"),
    DEFERRED("deferred");

    companion object {
        fun fromWire(value: String): IgnoredReason =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 ignored.reason: $value")
    }
}

/** 卡住类型；与参考实现一样使用大写线格式。 */
enum class ImpasseState(val wire: String) {
    TIE("TIE"),
    STUCK("STUCK"),
    STALLED("STALLED"),
    CONFLICT("CONFLICT"),
    INFEASIBLE("INFEASIBLE"),
    UNKNOWN("UNKNOWN"),
    UNVERIFIED("UNVERIFIED"),
    DRIFT("DRIFT"),
    BUDGET("BUDGET");

    companion object {
        fun fromWire(value: String): ImpasseState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 impasse.state: $value")
    }
}

/** slot 生命周期状态。 */
enum class SlotState(val wire: String) {
    ACTIVE("active"),
    RELEASED("released"),
    EVICTED("evicted"),
    DONE("done");

    companion object {
        fun fromWire(value: String): SlotState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 slot.state: $value")
    }
}

/**
 * 为什么正在注意它——必需字段，不是装饰（A-1）。
 *
 * 数据类只强制线格式的底线（`why` 至少一个字符、置信度区间）；更严格的
 * “`why` 不能只有空白”由 `frameFrom` 负责，这样边界可以一次汇总全部问题。
 */
data class Slot(
    val target: Ref,
    val role: SlotRole,
    val tier: Tier? = null,
    val salience: Confidence,
    val voi: Confidence? = null,
    val cost: Budget,
    val budget: Budget? = null,
    val why: String,
    val ttlTurns: Int? = null,
    val state: SlotState,
) {

    init {
        requireConfidence(salience, "slot.salience")
        voi?.let { requireConfidence(it, "slot.voi") }
        require(why.isNotEmpty()) { "slot.why 不能为空" }
        require(ttlTurns == null || ttlTurns >= 0) { "slot.ttl_turns 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "role" to JsonValue.Str(role.wire),
        "tier" to tier?.let { JsonValue.Str(it.wire) },
        "salience" to jsonNumber(salience),
        "voi" to voi?.let { jsonNumber(it) },
        "cost" to cost.toJson(),
        "budget" to budget?.toJson(),
        "why" to JsonValue.Str(why),
        "ttl_turns" to ttlTurns?.let { jsonNumber(it) },
        "state" to JsonValue.Str(state.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): Slot {
            val obj = value.asObject("slot")
            return Slot(
                target = Ref.fromJson(obj.requireObj("target")),
                role = SlotRole.fromWire(obj.requireString("role")),
                tier = obj.string("tier")?.let(Tier::fromWire),
                salience = obj.double("salience")
                    ?: throw IllegalArgumentException("缺少数字字段 salience"),
                voi = obj.double("voi"),
                cost = Budget.fromJson(obj.requireObj("cost")),
                budget = obj.requireOptionalObj("budget", "slot.budget")?.let(Budget::fromJson),
                why = obj.requireString("why"),
                ttlTurns = obj.int("ttl_turns"),
                state = SlotState.fromWire(obj.requireString("state")),
            )
        }
    }
}

/**
 * 刻意的排除。最独特的字段：经典架构不会记录它。
 */
data class Ignored(
    val target: Ref,
    val reason: IgnoredReason,
    val revisitIf: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "reason" to JsonValue.Str(reason.wire),
        "revisit_if" to JsonValue.Str(revisitIf),
    )

    companion object {
        fun fromJson(value: JsonValue): Ignored {
            val obj = value.asObject("ignored")
            return Ignored(
                target = Ref.fromJson(obj.requireObj("target")),
                reason = IgnoredReason.fromWire(obj.requireString("reason")),
                revisitIf = obj.requireString("revisit_if"),
            )
        }
    }
}

/** 帧级预算：分配额与已用量。 */
data class FrameBudget(
    val allocated: Budget,
    val spent: Budget,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "allocated" to allocated.toJson(),
        "spent" to spent.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): FrameBudget {
            val obj = value.asObject("frame.budget")
            return FrameBudget(
                allocated = Budget.fromJson(obj.requireObj("allocated")),
                spent = Budget.fromJson(obj.requireObj("spent")),
            )
        }
    }
}

/** 卡住状态的显式声明；`detail` 可选。 */
data class Impasse(
    val state: ImpasseState,
    val detail: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "state" to JsonValue.Str(state.wire),
        "detail" to detail?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Impasse {
            val obj = value.asObject("impasse")
            return Impasse(
                state = ImpasseState.fromWire(obj.requireString("state")),
                detail = obj.string("detail"),
            )
        }
    }
}

/**
 * AMP v0.1 注意帧：一次不可变的分配快照（`frame.ts`，对应
 * `frame.schema.json`）。
 *
 * `digest` 覆盖除自身以外的全部字段，由 `frameFrom` 计算；`fromJson` 只
 * 校验线格式，不重算 digest（示例帧里的 digest 是占位值，重算会拒绝掉
 * 合法示例）。
 */
data class Frame(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val id: Id,
    val agent: Id,
    val cycle: Int,
    val ts: Timestamp,
    val parent: Ref? = null,
    val digest: Digest,
    val mode: FrameMode,
    val goal: Ref? = null,
    /** 最多 9 个 slot。容量是设计，不是限制。 */
    val slots: List<Slot>,
    val ignored: List<Ignored>? = null,
    val budget: FrameBudget,
    val openQuestions: List<Ref>? = null,
    val impasse: Impasse? = null,
    val note: String? = null,
) {

    init {
        require(amp == AmpWire.VERSION) { "frame.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == KIND) { "frame.kind 必须是 $KIND" }
        ProtocolIds.require(id, "frame.id")
        ProtocolIds.require(agent, "frame.agent")
        require(cycle >= 0) { "frame.cycle 必须 >= 0" }
        require(isTimestamp(ts)) { "frame.ts 不是合法时间" }
        require(slots.size <= MAX_SLOTS) { "frame.slots 最多 $MAX_SLOTS 项" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "agent" to JsonValue.Str(agent),
        "cycle" to jsonNumber(cycle),
        "ts" to JsonValue.Str(ts),
        "parent" to parent?.toJson(),
        "digest" to JsonValue.Str(digest),
        "mode" to JsonValue.Str(mode.wire),
        "goal" to goal?.toJson(),
        "slots" to JsonValue.Arr(slots.map { it.toJson() }),
        "ignored" to ignored?.let { list -> JsonValue.Arr(list.map { it.toJson() }) },
        "budget" to budget.toJson(),
        "open_questions" to openQuestions?.let { list -> JsonValue.Arr(list.map { it.toJson() }) },
        "impasse" to impasse?.toJson(),
        "note" to note?.let { JsonValue.Str(it) },
    )

    companion object {

        const val KIND: String = "frame"

        /** `frame.schema.json` 的硬上限；`frameFrom` 只能收紧，不能放宽。 */
        const val MAX_SLOTS: Int = 9

        fun fromJson(value: JsonValue): Frame {
            val obj = value.asObject("frame")
            val digest = obj.requireString("digest")
            require(Sha256.isValidDigest(digest)) { "frame.digest 格式非法" }
            return Frame(
                amp = obj.requireString("amp"),
                kind = obj.requireString("kind"),
                id = obj.requireString("id"),
                agent = obj.requireString("agent"),
                cycle = obj.requireInt("cycle"),
                ts = obj.requireString("ts"),
                parent = obj.requireOptionalObj("parent", "frame.parent")?.let(Ref::fromJson),
                digest = digest,
                mode = FrameMode.fromWire(obj.requireString("mode")),
                goal = obj.requireOptionalObj("goal", "frame.goal")?.let(Ref::fromJson),
                slots = obj.requireArr("slots").items.map(Slot::fromJson),
                ignored = obj.optionalArray("ignored", "frame.ignored")?.items?.map(Ignored::fromJson),
                budget = FrameBudget.fromJson(obj.requireObj("budget")),
                openQuestions = obj.optionalArray("open_questions", "frame.open_questions")
                    ?.items
                    ?.map(Ref::fromJson),
                impasse = obj.requireOptionalObj("impasse", "frame.impasse")?.let(Impasse::fromJson),
                note = obj.optionalString("note", "frame.note"),
            )
        }
    }
}

/** 取可选对象字段；字段存在但类型错误时拒绝，而不是静默丢弃。 */
private fun JsonValue.Obj.requireOptionalObj(key: String, what: String): JsonValue.Obj? {
    val field = fields[key] ?: return null
    if (field is JsonValue.Null) {
        return null
    }
    return field as? JsonValue.Obj ?: throw IllegalArgumentException("$what 必须是 JSON 对象")
}

/** 取可选数组字段；字段存在但类型错误时拒绝。 */
private fun JsonValue.Obj.optionalArray(key: String, what: String): JsonValue.Arr? {
    val field = fields[key] ?: return null
    if (field is JsonValue.Null) {
        return null
    }
    return field as? JsonValue.Arr ?: throw IllegalArgumentException("$what 必须是 JSON 数组")
}

/** 取可选字符串字段；字段存在但类型错误时拒绝。 */
private fun JsonValue.Obj.optionalString(key: String, what: String): String? {
    val field = fields[key] ?: return null
    if (field is JsonValue.Null) {
        return null
    }
    return (field as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$what 必须是字符串")
}
