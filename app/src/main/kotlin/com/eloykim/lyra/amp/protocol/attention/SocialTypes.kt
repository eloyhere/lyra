package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/**
 * AMP 0.1 社会对象（Profile `Social`）：other-model | common-ground | attention-request。
 *
 * 忠实移植 `src/attention/socialTypes.ts`，镜像 `docs/schemas/social.schema.json`。
 * 关于他者的一切都是带置信度的假设，不是事实（W-1、INV-11）。
 */

private const val AMP_VERSION: String = AmpWire.VERSION

private const val KIND_OTHER_MODEL: String = "other-model"
private const val KIND_COMMON_GROUND: String = "common-ground"
private const val KIND_ATTENTION_REQUEST: String = "attention-request"

// ---------------------------------------------------------------------------
// 共享 wire 读取辅助（attention 包内部使用；其它包绑定时可替换成对应类型的 fromJson）
// ---------------------------------------------------------------------------

internal fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")

internal fun JsonValue.Obj.requireBool(key: String): Boolean =
    bool(key) ?: throw IllegalArgumentException("缺少布尔字段 $key")

/** 可选数组：字段存在时必须是数组，缺席返回 null（不静默容忍错误类型）。 */
internal fun JsonValue.Obj.optionalArray(key: String): List<JsonValue>? {
    val value = fields[key] ?: return null
    val array = value as? JsonValue.Arr ?: throw IllegalArgumentException("$key 必须是数组")
    return array.items
}

internal fun JsonValue.Obj.optionalObj(key: String): JsonValue.Obj? {
    val value = fields[key] ?: return null
    return value as? JsonValue.Obj ?: throw IllegalArgumentException("$key 必须是对象")
}

internal fun JsonValue.Obj.optionalStrings(key: String): List<String>? =
    optionalArray(key)?.map { (it as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 的元素必须是字符串") }

internal fun JsonValue.Obj.optionalRefs(key: String): List<Ref>? =
    optionalArray(key)?.map { Ref.fromJson(it) }

internal fun refsJson(refs: List<Ref>): JsonValue.Arr =
    JsonValue.Arr(refs.map { it.toJson() })

internal fun JsonValue.requireAmpAndKind(what: String, expectedKind: String): JsonValue.Obj {
    val obj = asObject(what)
    require(obj.requireString("amp") == AMP_VERSION) { "$what.amp 必须是 $AMP_VERSION" }
    require(obj.requireString("kind") == expectedKind) { "$what.kind 必须是 $expectedKind" }
    return obj
}

// ---------------------------------------------------------------------------
// 枚举
// ---------------------------------------------------------------------------

enum class OtherSubjectKind(val wire: String) {
    HUMAN("human"),
    AGENT("agent"),
    ;

    companion object {
        fun fromWire(value: String): OtherSubjectKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 subject.kind: $value")
    }
}

enum class Volatility(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        fun fromWire(value: String): Volatility =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 volatility: $value")
    }
}

enum class AttentionStateKind(val wire: String) {
    IDLE("idle"),
    BUSY("busy"),
    INTERRUPTIBLE("interruptible"),
    ;

    companion object {
        fun fromWire(value: String): AttentionStateKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 attention.state: $value")
    }
}

enum class PrivacyScope(val wire: String) {
    TURN("turn"),
    SESSION("session"),
    TASK("task"),
    PERSISTENT("persistent"),
    ;

    companion object {
        fun fromWire(value: String): PrivacyScope =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 privacy.scope: $value")
    }
}

enum class EstablishedBy(val wire: String) {
    ASSERTION("assertion"),
    CONFIRMATION("confirmation"),
    REPAIR("repair"),
    SHARED_OBSERVATION("shared_observation"),
    ASSUMPTION("assumption"),
    ;

    companion object {
        fun fromWire(value: String): EstablishedBy =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 established_by: $value")
    }

    /** 只有前四种真正建立共同基础；`assumption` 不是。 */
    val establishesCommonGround: Boolean get() = this != ASSUMPTION
}

enum class InterruptClass(val wire: String) {
    SAFETY("safety"),
    COMMITMENT("commitment"),
    OPPORTUNITY("opportunity"),
    CURIOSITY("curiosity"),
    ;

    companion object {
        fun fromWire(value: String): InterruptClass =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 interrupt class: $value")
    }
}

enum class Fallback(val wire: String) {
    QUEUE("queue"),
    DROP("drop"),
    ESCALATE("escalate"),
    ;

    companion object {
        fun fromWire(value: String): Fallback =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 fallback: $value")
    }
}

// ---------------------------------------------------------------------------
// other-model
// ---------------------------------------------------------------------------

data class OtherSubject(
    val kind: OtherSubjectKind,
    val id: Id,
    val label: String? = null,
) {

    init {
        ProtocolIds.require(id, "OtherSubject.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind.wire),
        "id" to JsonValue.Str(id),
        "label" to label?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): OtherSubject {
            val obj = value.asObject("other-model.subject")
            return OtherSubject(
                kind = OtherSubjectKind.fromWire(obj.requireString("kind")),
                id = obj.requireString("id"),
                label = obj.string("label"),
            )
        }
    }
}

data class BeliefAscription(
    val slot: Id,
    /**
     * 1 = “A 相信 P” · 2 = “A 相信 B 相信 P”。三阶以上由记录推算，而不是直接询问。
     */
    val order: Int,
    val proposition: String,
    val confidence: Confidence,
    val source: String,
    val volatility: Volatility,
    val ttl: Int,
) {

    init {
        ProtocolIds.require(slot, "belief.slot")
        require(order in 1..3) { "belief.order 必须在 1..3" }
        require(proposition.isNotBlank()) { "belief.proposition 不能为空" }
        requireConfidence(confidence, "belief.confidence")
        require(ttl >= 0) { "belief.ttl 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "slot" to JsonValue.Str(slot),
        "order" to jsonNumber(order),
        "proposition" to JsonValue.Str(proposition),
        "confidence" to jsonNumber(confidence),
        "source" to JsonValue.Str(source),
        "volatility" to JsonValue.Str(volatility.wire),
        "ttl" to jsonNumber(ttl),
    )

    companion object {
        fun fromJson(value: JsonValue): BeliefAscription {
            val obj = value.asObject("belief")
            return BeliefAscription(
                slot = obj.requireString("slot"),
                order = obj.requireInt("order"),
                proposition = obj.requireString("proposition"),
                confidence = obj.requireDouble("confidence"),
                source = obj.requireString("source"),
                volatility = Volatility.fromWire(obj.requireString("volatility")),
                ttl = obj.requireInt("ttl"),
            )
        }
    }
}

data class GoalAscription(
    val goal: String,
    val priority: Int,
    val confidence: Confidence,
) {

    init {
        require(priority >= 0) { "goal.priority 必须 >= 0" }
        requireConfidence(confidence, "goal.confidence")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "goal" to JsonValue.Str(goal),
        "priority" to jsonNumber(priority),
        "confidence" to jsonNumber(confidence),
    )

    companion object {
        fun fromJson(value: JsonValue): GoalAscription {
            val obj = value.asObject("goal-ascription")
            return GoalAscription(
                goal = obj.requireString("goal"),
                priority = obj.requireInt("priority"),
                confidence = obj.requireDouble("confidence"),
            )
        }
    }
}

data class KnowledgeState(
    val known: List<Ref>? = null,
    val unknown: List<Ref>? = null,
    val uncertain: List<Ref>? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "known" to known?.let { refsJson(it) },
        "unknown" to unknown?.let { refsJson(it) },
        "uncertain" to uncertain?.let { refsJson(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): KnowledgeState {
            val obj = value.asObject("knowledge")
            return KnowledgeState(
                known = obj.optionalRefs("known"),
                unknown = obj.optionalRefs("unknown"),
                uncertain = obj.optionalRefs("uncertain"),
            )
        }
    }
}

data class Preference(
    val key: String,
    val value: String,
    val confidence: Confidence,
    val source: String? = null,
) {

    init {
        requireConfidence(confidence, "preference.confidence")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "key" to JsonValue.Str(key),
        "value" to JsonValue.Str(value),
        "confidence" to jsonNumber(confidence),
        "source" to source?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Preference {
            val obj = value.asObject("preference")
            return Preference(
                key = obj.requireString("key"),
                value = obj.requireString("value"),
                confidence = obj.requireDouble("confidence"),
                source = obj.string("source"),
            )
        }
    }
}

data class AttentionState(
    val state: AttentionStateKind,
    val load: Confidence? = null,
    val lastInteraction: Timestamp? = null,
) {

    init {
        load?.let { requireConfidence(it, "attention.load") }
        require(lastInteraction == null || isTimestamp(lastInteraction)) { "attention.last_interaction 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "state" to JsonValue.Str(state.wire),
        "load" to load?.let { jsonNumber(it) },
        "last_interaction" to lastInteraction?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): AttentionState {
            val obj = value.asObject("attention-state")
            return AttentionState(
                state = AttentionStateKind.fromWire(obj.requireString("state")),
                load = obj.double("load"),
                lastInteraction = obj.string("last_interaction"),
            )
        }
    }
}

data class TrustAssessment(
    val domain: String? = null,
    val level: Confidence,
    val basis: String? = null,
) {

    init {
        requireConfidence(level, "trust.level")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "domain" to domain?.let { JsonValue.Str(it) },
        "level" to jsonNumber(level),
        "basis" to basis?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): TrustAssessment {
            val obj = value.asObject("trust-assessment")
            return TrustAssessment(
                domain = obj.string("domain"),
                level = obj.requireDouble("level"),
                basis = obj.string("basis"),
            )
        }
    }
}

data class Privacy(
    val scope: PrivacyScope,
    val retentionDays: Int? = null,
    val redact: List<String>? = null,
) {

    init {
        require(retentionDays == null || retentionDays >= 0) { "privacy.retention_days 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "scope" to JsonValue.Str(scope.wire),
        "retention_days" to retentionDays?.let { jsonNumber(it) },
        "redact" to redact?.let { JsonValue.Arr(it.map { value -> JsonValue.Str(value) }) },
    )

    companion object {
        fun fromJson(value: JsonValue): Privacy {
            val obj = value.asObject("privacy")
            return Privacy(
                scope = PrivacyScope.fromWire(obj.requireString("scope")),
                retentionDays = obj.int("retention_days"),
                redact = obj.optionalStrings("redact"),
            )
        }
    }
}

data class OtherModel(
    val id: Id,
    val subject: OtherSubject,
    val beliefs: List<BeliefAscription>,
    val privacy: Privacy,
    val confidence: Confidence,
    val updated: Timestamp,
    val goals: List<GoalAscription>? = null,
    val knowledge: KnowledgeState? = null,
    val preferences: List<Preference>? = null,
    val attention: AttentionState? = null,
    val trust: TrustAssessment? = null,
    val commonGround: List<Ref>? = null,
) : SocialObject {

    init {
        ProtocolIds.require(id, "other-model.id")
        requireConfidence(confidence, "other-model.confidence")
        require(isTimestamp(updated)) { "other-model.updated 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AMP_VERSION),
        "kind" to JsonValue.Str(KIND_OTHER_MODEL),
        "id" to JsonValue.Str(id),
        "subject" to subject.toJson(),
        "beliefs" to JsonValue.Arr(beliefs.map { it.toJson() }),
        "goals" to goals?.let { list -> JsonValue.Arr(list.map { it.toJson() }) },
        "knowledge" to knowledge?.toJson(),
        "preferences" to preferences?.let { list -> JsonValue.Arr(list.map { it.toJson() }) },
        "attention" to attention?.toJson(),
        "trust" to trust?.toJson(),
        "common_ground" to commonGround?.let { refsJson(it) },
        "privacy" to privacy.toJson(),
        "confidence" to jsonNumber(confidence),
        "updated" to JsonValue.Str(updated),
    )

    companion object {
        fun fromJson(value: JsonValue): OtherModel {
            val obj = value.requireAmpAndKind("other-model", KIND_OTHER_MODEL)
            return OtherModel(
                id = obj.requireString("id"),
                subject = OtherSubject.fromJson(obj.requireObj("subject")),
                beliefs = obj.requireArr("beliefs").items.map { BeliefAscription.fromJson(it) },
                privacy = Privacy.fromJson(obj.requireObj("privacy")),
                confidence = obj.requireDouble("confidence"),
                updated = obj.requireString("updated"),
                goals = obj.optionalArray("goals")?.map { GoalAscription.fromJson(it) },
                knowledge = obj.optionalObj("knowledge")?.let { KnowledgeState.fromJson(it) },
                preferences = obj.optionalArray("preferences")?.map { Preference.fromJson(it) },
                attention = obj.optionalObj("attention")?.let { AttentionState.fromJson(it) },
                trust = obj.optionalObj("trust")?.let { TrustAssessment.fromJson(it) },
                commonGround = obj.optionalRefs("common_ground"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// common-ground
// ---------------------------------------------------------------------------

data class CommonGround(
    val id: Id,
    val proposition: String,
    val establishedBy: EstablishedBy,
    val participants: List<Ref>,
    val confidence: Confidence,
    val establishedAt: Timestamp,
) : SocialObject {

    init {
        ProtocolIds.require(id, "common-ground.id")
        require(proposition.isNotBlank()) { "common-ground.proposition 不能为空" }
        require(participants.size >= 2) { "common-ground.participants 至少需要 2 个参与者" }
        requireConfidence(confidence, "common-ground.confidence")
        require(isTimestamp(establishedAt)) { "common-ground.established_at 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AMP_VERSION),
        "kind" to JsonValue.Str(KIND_COMMON_GROUND),
        "id" to JsonValue.Str(id),
        "proposition" to JsonValue.Str(proposition),
        "established_by" to JsonValue.Str(establishedBy.wire),
        "participants" to refsJson(participants),
        "confidence" to jsonNumber(confidence),
        "established_at" to JsonValue.Str(establishedAt),
    )

    companion object {
        fun fromJson(value: JsonValue): CommonGround {
            val obj = value.requireAmpAndKind("common-ground", KIND_COMMON_GROUND)
            return CommonGround(
                id = obj.requireString("id"),
                proposition = obj.requireString("proposition"),
                establishedBy = EstablishedBy.fromWire(obj.requireString("established_by")),
                participants = obj.requireArr("participants").items.map { Ref.fromJson(it) },
                confidence = obj.requireDouble("confidence"),
                establishedAt = obj.requireString("established_at"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// attention-request
// ---------------------------------------------------------------------------

data class AttentionRequest(
    val id: Id,
    val from: Ref,
    val to: Ref,
    val target: Ref,
    val interruptClass: InterruptClass,
    val urgency: Int,
    val whyNow: String,
    val ifIgnored: String,
    val cost: Budget,
    val fallback: Fallback,
    val expectedValue: Confidence? = null,
    val expires: Timestamp? = null,
) : SocialObject {

    init {
        ProtocolIds.require(id, "attention-request.id")
        require(urgency in 1..5) { "attention-request.urgency 必须在 1..5" }
        require(whyNow.isNotBlank()) { "attention-request.why_now 不能为空" }
        require(ifIgnored.isNotBlank()) { "attention-request.if_ignored 不能为空" }
        expectedValue?.let { requireConfidence(it, "attention-request.expected_value") }
        require(expires == null || isTimestamp(expires)) { "attention-request.expires 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AMP_VERSION),
        "kind" to JsonValue.Str(KIND_ATTENTION_REQUEST),
        "id" to JsonValue.Str(id),
        "from" to from.toJson(),
        "to" to to.toJson(),
        "target" to target.toJson(),
        "class" to JsonValue.Str(interruptClass.wire),
        "urgency" to jsonNumber(urgency),
        "why_now" to JsonValue.Str(whyNow),
        "if_ignored" to JsonValue.Str(ifIgnored),
        "cost" to cost.toJson(),
        "expected_value" to expectedValue?.let { jsonNumber(it) },
        "expires" to expires?.let { JsonValue.Str(it) },
        "fallback" to JsonValue.Str(fallback.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): AttentionRequest {
            val obj = value.requireAmpAndKind("attention-request", KIND_ATTENTION_REQUEST)
            return AttentionRequest(
                id = obj.requireString("id"),
                from = Ref.fromJson(obj.requireObj("from")),
                to = Ref.fromJson(obj.requireObj("to")),
                target = Ref.fromJson(obj.requireObj("target")),
                interruptClass = InterruptClass.fromWire(obj.requireString("class")),
                urgency = obj.requireInt("urgency"),
                whyNow = obj.requireString("why_now"),
                ifIgnored = obj.requireString("if_ignored"),
                cost = Budget.fromJson(obj.requireObj("cost")),
                fallback = Fallback.fromWire(obj.requireString("fallback")),
                expectedValue = obj.double("expected_value"),
                expires = obj.string("expires"),
            )
        }
    }
}

/** `socialTypes.ts` 的联合类型。`kind` 决定具体分支。 */
sealed interface SocialObject {

    fun toJson(): JsonValue.Obj

    companion object {
        fun fromJson(value: JsonValue): SocialObject {
            val obj = value.asObject("social")
            return when (val kind = obj.requireString("kind")) {
                KIND_OTHER_MODEL -> OtherModel.fromJson(obj)
                KIND_COMMON_GROUND -> CommonGround.fromJson(obj)
                KIND_ATTENTION_REQUEST -> AttentionRequest.fromJson(obj)
                else -> throw IllegalArgumentException("未知 social kind: $kind")
            }
        }
    }
}
