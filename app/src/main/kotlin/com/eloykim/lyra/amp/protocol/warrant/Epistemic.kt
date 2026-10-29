package com.eloykim.lyra.amp.protocol.warrant

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.Trust
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/**
 * AMP 0.1 warrant ledger objects: claim | hypothesis | unknown.
 *
 * 对应 `docs/schemas/epistemic.schema.json` 与参考实现 `src/warrant/epistemic.ts`。
 * `confidence` 由 Warrant Engine 从 tier + evidence 推导（M-1），这里只作为展示字段保存，
 * 从不据此放宽证据要求。
 */
enum class ClaimScope(val wire: String) {
    ASSOCIATIONAL("associational"),
    INTERVENTIONAL("interventional"),
    COUNTERFACTUAL("counterfactual"),
    DEFINITIONAL("definitional");

    companion object {
        fun fromWire(value: String): ClaimScope =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 claim_scope: $value")
    }
}

enum class ClaimStatus(val wire: String) {
    CONJECTURE("conjecture"),
    HELD("held"),
    DOUBTED("doubted"),
    CONTESTED("contested"),
    RETRACTED("retracted");

    companion object {
        fun fromWire(value: String): ClaimStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 claim.status: $value")
    }
}

enum class EvidenceKind(val wire: String) {
    OBSERVATION("observation"),
    TEST("test"),
    DOCUMENT("document"),
    TESTIMONY("testimony"),
    MEMORY("memory"),
    INFERENCE("inference");

    companion object {
        fun fromWire(value: String): EvidenceKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 evidence.kind: $value")
    }
}

enum class EvidenceRelation(val wire: String) {
    SUPPORTS("supports"),
    UNDERCUTS("undercuts"),
    CONTRADICTS("contradicts");

    companion object {
        fun fromWire(value: String): EvidenceRelation =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 evidence.relation: $value")
    }
}

enum class Inference(val wire: String) {
    OBSERVATION("observation"),
    DEDUCTION("deduction"),
    INDUCTION("induction"),
    ABDUCTION("abduction"),
    TESTIMONY("testimony"),
    SIMULATION("simulation");

    companion object {
        fun fromWire(value: String): Inference =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 warrant.inference: $value")
    }
}

enum class HypothesisStatus(val wire: String) {
    PROPOSED("proposed"),
    TESTING("testing"),
    SUPPORTED("supported"),
    REFUTED("refuted"),
    INCONCLUSIVE("inconclusive"),
    OUT_OF_SCOPE("out_of_scope");

    companion object {
        fun fromWire(value: String): HypothesisStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 hypothesis.status: $value")
    }
}

enum class HypothesisLabel(val wire: String) {
    CONFIRMATORY("confirmatory"),
    EXPLORATORY("exploratory"),
    REPLICATION("replication");

    companion object {
        fun fromWire(value: String): HypothesisLabel =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 hypothesis.labels: $value")
    }
}

enum class UnknownStatus(val wire: String) {
    OPEN("open"),
    PROBING("probing"),
    ASSUMED("assumed"),
    RESOLVED("resolved"),
    ABANDONED("abandoned");

    companion object {
        fun fromWire(value: String): UnknownStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 unknown.status: $value")
    }
}

enum class AssignmentMechanism(val wire: String) {
    OBSERVED("observed"),
    ASSIGNED("assigned"),
    RANDOMIZED("randomized"),
    NATURAL_EXPERIMENT("natural_experiment");

    companion object {
        fun fromWire(value: String): AssignmentMechanism =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 causal.assignment_mechanism: $value")
    }
}

/** 单个证据项。`independence` 为 0（同源）到 1（完全独立），重复叙述不构成相互印证。 */
data class EvidenceItem(
    val id: Id,
    val kind: EvidenceKind,
    val trust: Trust,
    val independence: Confidence,
    val relation: EvidenceRelation? = null,
    val entails: Boolean? = null,
    val origin: String? = null,
    val ref: Ref? = null,
    val excerpt: String? = null,
    val digest: Digest? = null,
    val at: Timestamp,
) {

    init {
        ProtocolIds.require(id, "evidence.id")
        requireConfidence(independence, "evidence.independence")
        require(digest == null || Sha256.isValidDigest(digest)) { "evidence.digest 格式非法" }
        require(isTimestamp(at)) { "evidence.at 不是合法时间: $at" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "kind" to JsonValue.Str(kind.wire),
        "trust" to JsonValue.Str(trust.wire),
        "independence" to jsonNumber(independence),
        "relation" to relation?.let { JsonValue.Str(it.wire) },
        "entails" to entails?.let { jsonBool(it) },
        "origin" to origin?.let { JsonValue.Str(it) },
        "ref" to ref?.toJson(),
        "excerpt" to excerpt?.let { JsonValue.Str(it) },
        "digest" to digest?.let { JsonValue.Str(it) },
        "at" to JsonValue.Str(at),
    )

    companion object {
        fun fromJson(value: JsonValue): EvidenceItem {
            val obj = value.asObject("evidence")
            return EvidenceItem(
                id = obj.requireString("id"),
                kind = EvidenceKind.fromWire(obj.requireString("kind")),
                trust = Trust.fromWire(obj.requireString("trust")),
                independence = obj.requireDouble("independence"),
                relation = obj.string("relation")?.let(EvidenceRelation::fromWire),
                entails = obj.bool("entails"),
                origin = obj.string("origin"),
                ref = obj.obj("ref")?.let(Ref::fromJson),
                excerpt = obj.string("excerpt"),
                digest = obj.string("digest"),
                at = obj.requireString("at"),
            )
        }
    }
}

/** 主张的证据基础。`auxiliary_assumptions` 是 Duhem–Quine 式的辅助假设。 */
data class Warrant(
    val evidence: List<EvidenceItem>,
    val inference: Inference,
    val auxiliaryAssumptions: List<String>? = null,
    val strength: Confidence,
) {

    init {
        requireConfidence(strength, "warrant.strength")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "evidence" to JsonValue.Arr(evidence.map { it.toJson() }),
        "inference" to JsonValue.Str(inference.wire),
        "auxiliary_assumptions" to auxiliaryAssumptions?.let { jsonStrings(it) },
        "strength" to jsonNumber(strength),
    )

    companion object {
        fun fromJson(value: JsonValue): Warrant {
            val obj = value.asObject("warrant")
            return Warrant(
                evidence = obj.requireArr("evidence").items.map { EvidenceItem.fromJson(it) },
                inference = Inference.fromWire(obj.requireString("inference")),
                auxiliaryAssumptions = obj.optionalStrings("auxiliary_assumptions"),
                strength = obj.requireDouble("strength"),
            )
        }
    }
}

/** 因果主张的识别信息；`observed` 不能支撑 interventional 主张（W-5）。 */
data class CausalClaim(
    val assignmentMechanism: AssignmentMechanism,
    val identificationStrategy: String? = null,
    val identifyingAssumption: String? = null,
    val adjustmentSet: List<String>? = null,
    val dagDigest: Digest? = null,
) {

    init {
        require(dagDigest == null || Sha256.isValidDigest(dagDigest)) { "causal.dag_digest 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "assignment_mechanism" to JsonValue.Str(assignmentMechanism.wire),
        "identification_strategy" to identificationStrategy?.let { JsonValue.Str(it) },
        "identifying_assumption" to identifyingAssumption?.let { JsonValue.Str(it) },
        "adjustment_set" to adjustmentSet?.let { jsonStrings(it) },
        "dag_digest" to dagDigest?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): CausalClaim {
            val obj = value.asObject("causal")
            return CausalClaim(
                assignmentMechanism = AssignmentMechanism.fromWire(obj.requireString("assignment_mechanism")),
                identificationStrategy = obj.string("identification_strategy"),
                identifyingAssumption = obj.string("identifying_assumption"),
                adjustmentSet = obj.optionalStrings("adjustment_set"),
                dagDigest = obj.string("dag_digest"),
            )
        }
    }
}

/** 被拒绝的备择解释。 */
data class Alternative(
    val statement: String,
    val confidence: Confidence,
    val reasonRejected: String? = null,
) {

    init {
        requireConfidence(confidence, "alternative.confidence")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "statement" to JsonValue.Str(statement),
        "confidence" to jsonNumber(confidence),
        "reason_rejected" to reasonRejected?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Alternative {
            val obj = value.asObject("alternative")
            return Alternative(
                statement = obj.requireString("statement"),
                confidence = obj.requireDouble("confidence"),
                reasonRejected = obj.string("reason_rejected"),
            )
        }
    }
}

/** 先验基础率。 */
data class BaseRate(
    val value: Confidence,
    val source: String? = null,
) {

    init {
        requireConfidence(value, "base_rate.value")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "value" to jsonNumber(value),
        "source" to source?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): BaseRate {
            val obj = value.asObject("base_rate")
            return BaseRate(
                value = obj.requireDouble("value"),
                source = obj.string("source"),
            )
        }
    }
}

/** 主张的结构化形式：主语 / 关系 / 值。`value` 是任意 JSON（schema 未约束）。 */
data class Structured(
    val subject: Ref? = null,
    val relation: String? = null,
    val value: JsonValue? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "subject" to subject?.toJson(),
        "relation" to relation?.let { JsonValue.Str(it) },
        "value" to value,
    )

    companion object {
        fun fromJson(value: JsonValue): Structured {
            val obj = value.asObject("structured")
            return Structured(
                subject = obj.obj("subject")?.let(Ref::fromJson),
                relation = obj.string("relation"),
                value = obj["value"],
            )
        }
    }
}

/** 修订记录：旧主张保留，新主张通过 `supersedes` 指向它（W-3）。 */
data class ClaimRevision(
    val reason: String,
    val at: Timestamp,
) {

    init {
        require(reason.isNotBlank()) { "revision.reason 不能为空" }
        require(isTimestamp(at)) { "revision.at 不是合法时间: $at" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "reason" to JsonValue.Str(reason),
        "at" to JsonValue.Str(at),
    )

    companion object {
        fun fromJson(value: JsonValue): ClaimRevision {
            val obj = value.asObject("revision")
            return ClaimRevision(
                reason = obj.requireString("reason"),
                at = obj.requireString("at"),
            )
        }
    }
}

/**
 * 一条被断言的命题，带 tier、status 与 warrant。
 *
 * tier 表示证据等级，只能由证据支撑、不能靠断言提升（W-5）；status 为 `held` 之外时
 * 不得授权任何副作用（W-1）。
 */
data class Claim(
    override val id: Id,
    val statement: String,
    val tier: Tier,
    val status: ClaimStatus,
    val warrant: Warrant,
    val provenance: Provenance,
    val created: Timestamp,
    val structured: Structured? = null,
    val claimScope: ClaimScope? = null,
    val confidence: Confidence? = null,
    val wouldBeFalseIf: String? = null,
    val checkRunId: String? = null,
    val causal: CausalClaim? = null,
    val alternatives: List<Alternative>? = null,
    val disconfirming: List<Ref>? = null,
    val baseRate: BaseRate? = null,
    val looks: Int? = null,
    val scope: String? = null,
    val expires: Timestamp? = null,
    val supersedes: Ref? = null,
    val revision: ClaimRevision? = null,
) : EpistemicObject {

    init {
        ProtocolIds.require(id, "claim.id")
        require(statement.isNotBlank()) { "claim.statement 不能为空" }
        confidence?.let { requireConfidence(it, "claim.confidence") }
        require(looks == null || looks >= 0) { "claim.looks 必须 >= 0" }
        require(isTimestamp(created)) { "claim.created 不是合法时间: $created" }
        require(expires == null || isTimestamp(expires)) { "claim.expires 不是合法时间" }
    }

    /** 未被撤回的主张仍可被引用。 */
    val isActive: Boolean
        get() = status != ClaimStatus.RETRACTED

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("claim"),
        "id" to JsonValue.Str(id),
        "statement" to JsonValue.Str(statement),
        "structured" to structured?.toJson(),
        "claim_scope" to claimScope?.let { JsonValue.Str(it.wire) },
        "tier" to JsonValue.Str(tier.wire),
        "confidence" to confidence?.let { jsonNumber(it) },
        "status" to JsonValue.Str(status.wire),
        "would_be_false_if" to wouldBeFalseIf?.let { JsonValue.Str(it) },
        "check_run_id" to checkRunId?.let { JsonValue.Str(it) },
        "causal" to causal?.toJson(),
        "warrant" to warrant.toJson(),
        "alternatives" to alternatives?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "disconfirming" to disconfirming?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "base_rate" to baseRate?.toJson(),
        "looks" to looks?.let { jsonNumber(it) },
        "scope" to scope?.let { JsonValue.Str(it) },
        "provenance" to provenance.toJson(),
        "created" to JsonValue.Str(created),
        "expires" to expires?.let { JsonValue.Str(it) },
        "supersedes" to supersedes?.toJson(),
        "revision" to revision?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): Claim {
            val obj = value.asObject("claim")
            obj.requireAmpEnvelope("claim", "claim")
            return Claim(
                id = obj.requireString("id"),
                statement = obj.requireString("statement"),
                tier = Tier.fromWire(obj.requireString("tier")),
                status = ClaimStatus.fromWire(obj.requireString("status")),
                warrant = Warrant.fromJson(obj.requireObj("warrant")),
                provenance = Provenance.fromJson(obj.requireObj("provenance")),
                created = obj.requireString("created"),
                structured = obj.obj("structured")?.let(Structured::fromJson),
                claimScope = obj.string("claim_scope")?.let(ClaimScope::fromWire),
                confidence = obj.double("confidence"),
                wouldBeFalseIf = obj.string("would_be_false_if"),
                checkRunId = obj.string("check_run_id"),
                causal = obj.obj("causal")?.let(CausalClaim::fromJson),
                alternatives = obj.arr("alternatives")?.items?.map { Alternative.fromJson(it) },
                disconfirming = obj.arr("disconfirming")?.items?.map { Ref.fromJson(it) },
                baseRate = obj.obj("base_rate")?.let(BaseRate::fromJson),
                looks = obj.int("looks"),
                scope = obj.string("scope"),
                expires = obj.string("expires"),
                supersedes = obj.obj("supersedes")?.let(Ref::fromJson),
                revision = obj.obj("revision")?.let(ClaimRevision::fromJson),
            )
        }
    }
}

/** 预注册：先登记再观察，防止 HARKing。 */
data class Prereg(
    val hash: Digest,
    val registeredAt: Timestamp,
    val metric: String,
    val stopping: String,
    val exclusions: List<String>? = null,
    val looks: Int? = null,
) {

    init {
        require(Sha256.isValidDigest(hash)) { "prereg.hash 格式非法" }
        require(isTimestamp(registeredAt)) { "prereg.registered_at 不是合法时间" }
        require(looks == null || looks >= 0) { "prereg.looks 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "hash" to JsonValue.Str(hash),
        "registered_at" to JsonValue.Str(registeredAt),
        "metric" to JsonValue.Str(metric),
        "stopping" to JsonValue.Str(stopping),
        "exclusions" to exclusions?.let { jsonStrings(it) },
        "looks" to looks?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Prereg {
            val obj = value.asObject("prereg")
            return Prereg(
                hash = obj.requireString("hash"),
                registeredAt = obj.requireString("registered_at"),
                metric = obj.requireString("metric"),
                stopping = obj.requireString("stopping"),
                exclusions = obj.optionalStrings("exclusions"),
                looks = obj.int("looks"),
            )
        }
    }
}

/** 检验规格：怎么观察，以及观察什么。 */
data class TestSpec(
    val method: String,
    val observation: String,
    val cost: Budget? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "method" to JsonValue.Str(method),
        "observation" to JsonValue.Str(observation),
        "cost" to cost?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): TestSpec {
            val obj = value.asObject("test")
            return TestSpec(
                method = obj.requireString("method"),
                observation = obj.requireString("observation"),
                cost = obj.obj("cost")?.let(Budget::fromJson),
            )
        }
    }
}

/**
 * 可证伪的假设。
 *
 * `refutes_if` 为空时它只是立场而不是假设（W-2）：构造函数不做拦截，
 * 由 [validateHypothesis] 在入账前拒绝，与参考实现一致。
 */
data class Hypothesis(
    override val id: Id,
    val question: Ref,
    val statement: String,
    val prediction: String,
    val refutesIf: String,
    val status: HypothesisStatus,
    val created: Timestamp,
    val auxiliaryAssumptions: List<String>? = null,
    val prereg: Prereg? = null,
    val test: TestSpec? = null,
    val labels: List<HypothesisLabel>? = null,
    val evidence: List<Ref>? = null,
    val closed: Timestamp? = null,
) : EpistemicObject {

    init {
        ProtocolIds.require(id, "hypothesis.id")
        require(isTimestamp(created)) { "hypothesis.created 不是合法时间: $created" }
        require(closed == null || isTimestamp(closed)) { "hypothesis.closed 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("hypothesis"),
        "id" to JsonValue.Str(id),
        "question" to question.toJson(),
        "statement" to JsonValue.Str(statement),
        "prediction" to JsonValue.Str(prediction),
        "refutes_if" to JsonValue.Str(refutesIf),
        "auxiliary_assumptions" to auxiliaryAssumptions?.let { jsonStrings(it) },
        "prereg" to prereg?.toJson(),
        "test" to test?.toJson(),
        "labels" to labels?.let { items -> jsonStrings(items.map { it.wire }) },
        "status" to JsonValue.Str(status.wire),
        "evidence" to evidence?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "created" to JsonValue.Str(created),
        "closed" to closed?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Hypothesis {
            val obj = value.asObject("hypothesis")
            obj.requireAmpEnvelope("hypothesis", "hypothesis")
            return Hypothesis(
                id = obj.requireString("id"),
                question = Ref.fromJson(obj.requireObj("question")),
                statement = obj.requireString("statement"),
                prediction = obj.requireString("prediction"),
                refutesIf = obj.requireString("refutes_if"),
                status = HypothesisStatus.fromWire(obj.requireString("status")),
                created = obj.requireString("created"),
                auxiliaryAssumptions = obj.optionalStrings("auxiliary_assumptions"),
                prereg = obj.obj("prereg")?.let(Prereg::fromJson),
                test = obj.obj("test")?.let(TestSpec::fromJson),
                labels = obj.arr("labels")?.items?.map {
                    HypothesisLabel.fromWire((it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("hypothesis.labels 必须是字符串数组"))
                },
                evidence = obj.arr("evidence")?.items?.map { Ref.fromJson(it) },
                closed = obj.string("closed"),
            )
        }
    }
}

/**
 * 阻塞性未知：重要且阻塞的问题必须落账，不允许模型沉默（W-4）。
 */
data class UnknownItem(
    override val id: Id,
    val question: String,
    val importance: Confidence,
    val blocking: Boolean,
    val since: Timestamp,
    val resolveCost: Budget? = null,
    val attempts: List<Ref>? = null,
    val status: UnknownStatus? = null,
) : EpistemicObject {

    init {
        ProtocolIds.require(id, "unknown.id")
        require(question.isNotBlank()) { "unknown.question 不能为空" }
        requireConfidence(importance, "unknown.importance")
        require(isTimestamp(since)) { "unknown.since 不是合法时间: $since" }
    }

    /** 仍会阻塞后续决策的未知。 */
    val isBlockingOpen: Boolean
        get() = blocking && status != UnknownStatus.RESOLVED && status != UnknownStatus.ABANDONED

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("unknown"),
        "id" to JsonValue.Str(id),
        "question" to JsonValue.Str(question),
        "importance" to jsonNumber(importance),
        "blocking" to jsonBool(blocking),
        "since" to JsonValue.Str(since),
        "resolve_cost" to resolveCost?.toJson(),
        "attempts" to attempts?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "status" to status?.let { JsonValue.Str(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): UnknownItem {
            val obj = value.asObject("unknown")
            obj.requireAmpEnvelope("unknown", "unknown")
            return UnknownItem(
                id = obj.requireString("id"),
                question = obj.requireString("question"),
                importance = obj.requireDouble("importance"),
                blocking = obj.requireBool("blocking"),
                since = obj.requireString("since"),
                resolveCost = obj.obj("resolve_cost")?.let(Budget::fromJson),
                attempts = obj.arr("attempts")?.items?.map { Ref.fromJson(it) },
                status = obj.string("status")?.let(UnknownStatus::fromWire),
            )
        }
    }
}

/** claim | hypothesis | unknown 的联合，按 `kind` 分派。 */
sealed interface EpistemicObject {

    val id: Id

    fun toJson(): JsonValue.Obj

    companion object {
        fun fromJson(value: JsonValue): EpistemicObject {
            val obj = value.asObject("epistemic")
            return when (val kind = obj.requireString("kind")) {
                "claim" -> Claim.fromJson(obj)
                "hypothesis" -> Hypothesis.fromJson(obj)
                "unknown" -> UnknownItem.fromJson(obj)
                else -> throw IllegalArgumentException("未知 epistemic kind: $kind")
            }
        }
    }
}

private fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")

private fun JsonValue.Obj.requireBool(key: String): Boolean =
    bool(key) ?: throw IllegalArgumentException("缺少布尔字段 $key")

private fun JsonValue.Obj.optionalStrings(key: String): List<String>? =
    arr(key)?.items?.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }

private fun JsonValue.Obj.requireAmpEnvelope(expectedKind: String, what: String) {
    val amp = requireString("amp")
    require(amp == AmpWire.VERSION) { "$what.amp 必须是 ${AmpWire.VERSION}" }
    val kind = requireString("kind")
    require(kind == expectedKind) { "$what.kind 必须是 $expectedKind" }
}
