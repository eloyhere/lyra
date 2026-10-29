package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Signature
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence
import java.security.PublicKey

/**
 * AMP v0.1 — Manifest (心智清单), the durable, signable self-declaration.
 *
 * Mirrors `manifest.ts` and `docs/schemas/manifest.schema.json`. The signed
 * [Manifest] is the wire object; [UnsignedManifest] is the `Omit<Manifest,
 * "digest" | "signatures">` shape used by `sign.ts` for signing.
 */

enum class CapabilityKind(val wire: String) {
    TOOL("tool"),
    SKILL("skill"),
    KNOWLEDGE("knowledge"),
    PERCEPTION("perception"),
    ACTION("action");

    companion object {
        fun fromWire(value: String): CapabilityKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 capability.kind: $value")
    }
}

/** Hard constraints are never traded off; soft ones may be. */
enum class ConstraintEnforcement(val wire: String) {
    HARD("hard"),
    SOFT("soft");

    companion object {
        fun fromWire(value: String): ConstraintEnforcement =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 constraint.enforcement: $value")
    }
}

enum class CommitmentStatus(val wire: String) {
    OPEN("open"),
    KEPT("kept"),
    BROKEN("broken"),
    RELEASED("released");

    companion object {
        fun fromWire(value: String): CommitmentStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 commitment.status: $value")
    }
}

enum class SelfMode(val wire: String) {
    EXPLORE("explore"),
    EXPLOIT("exploit"),
    TRIAGE("triage"),
    REFLECT("reflect"),
    REST("rest");

    companion object {
        fun fromWire(value: String): SelfMode =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 self.state.mode: $value")
    }
}

data class AgentIdentity(
    val id: Id,
    val name: String? = null,
    val version: String? = null,
    val role: String? = null,
) {

    init {
        ProtocolIds.require(id, "agent.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "name" to name?.let { JsonValue.Str(it) },
        "version" to version?.let { JsonValue.Str(it) },
        "role" to role?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): AgentIdentity {
            val obj = value.asManifestObject("agent")
            return AgentIdentity(
                id = obj.requireString("id"),
                name = obj.string("name"),
                version = obj.string("version"),
                role = obj.string("role"),
            )
        }
    }
}

data class ValueStatement(
    val id: Id,
    val statement: String,
    val weight: Confidence? = null,
) {

    init {
        ProtocolIds.require(id, "value.id")
        require(statement.isNotBlank()) { "value.statement 不能为空" }
        weight?.let { requireConfidence(it, "value.weight") }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "statement" to JsonValue.Str(statement),
        "weight" to weight?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ValueStatement {
            val obj = value.asManifestObject("value")
            return ValueStatement(
                id = obj.requireString("id"),
                statement = obj.requireString("statement"),
                weight = obj.double("weight"),
            )
        }
    }
}

data class Constraint(
    val id: Id,
    val statement: String,
    val enforcement: ConstraintEnforcement? = null,
) {

    init {
        ProtocolIds.require(id, "constraint.id")
        require(statement.isNotBlank()) { "constraint.statement 不能为空" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "statement" to JsonValue.Str(statement),
        "enforcement" to enforcement?.let { JsonValue.Str(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): Constraint {
            val obj = value.asManifestObject("constraint")
            return Constraint(
                id = obj.requireString("id"),
                statement = obj.requireString("statement"),
                enforcement = obj.string("enforcement")?.let(ConstraintEnforcement::fromWire),
            )
        }
    }
}

data class Identity(
    val values: List<ValueStatement>? = null,
    val constraints: List<Constraint>? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "values" to values?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
        "constraints" to constraints?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
    )

    companion object {
        fun fromJson(value: JsonValue): Identity {
            val obj = value.asManifestObject("identity")
            return Identity(
                values = obj.arr("values")?.items?.map(ValueStatement::fromJson),
                constraints = obj.arr("constraints")?.items?.map(Constraint::fromJson),
            )
        }
    }
}

data class Capability(
    val id: Id,
    val kind: CapabilityKind,
    val confidence: Confidence,
    val tier: Tier,
    val label: String? = null,
    val cost: Budget? = null,
    val limits: List<String>? = null,
    /** Evidence refs must derive from receipts (M-1), never self-assessment. */
    val evidence: List<Ref>? = null,
) {

    init {
        ProtocolIds.require(id, "capability.id")
        requireConfidence(confidence, "capability.confidence")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "label" to label?.let { JsonValue.Str(it) },
        "kind" to JsonValue.Str(kind.wire),
        "confidence" to jsonNumber(confidence),
        "tier" to JsonValue.Str(tier.wire),
        "cost" to cost?.toJson(),
        "limits" to limits?.let(::jsonStrings),
        "evidence" to evidence?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
    )

    companion object {
        fun fromJson(value: JsonValue): Capability {
            val obj = value.asManifestObject("capability")
            return Capability(
                id = obj.requireString("id"),
                kind = CapabilityKind.fromWire(obj.requireString("kind")),
                confidence = obj.double("confidence") ?: throw IllegalArgumentException("缺少数字字段 confidence"),
                tier = Tier.fromWire(obj.requireString("tier")),
                label = obj.string("label"),
                cost = obj.obj("cost")?.let(Budget::fromJson),
                limits = obj.readStrings("limits"),
                evidence = obj.arr("evidence")?.items?.map(Ref::fromJson),
            )
        }
    }
}

data class Commitment(
    val id: Id,
    val to: Ref,
    val promise: String,
    val status: CommitmentStatus,
    val due: Timestamp? = null,
    val created: Timestamp? = null,
) {

    init {
        ProtocolIds.require(id, "commitment.id")
        require(promise.isNotBlank()) { "commitment.promise 不能为空" }
        require(due == null || isTimestamp(due)) { "commitment.due 不是合法时间" }
        require(created == null || isTimestamp(created)) { "commitment.created 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "to" to to.toJson(),
        "promise" to JsonValue.Str(promise),
        "due" to due?.let { JsonValue.Str(it) },
        "status" to JsonValue.Str(status.wire),
        "created" to created?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Commitment {
            val obj = value.asManifestObject("commitment")
            return Commitment(
                id = obj.requireString("id"),
                to = Ref.fromJson(obj.requireObj("to")),
                promise = obj.requireString("promise"),
                status = CommitmentStatus.fromWire(obj.requireString("status")),
                due = obj.string("due"),
                created = obj.string("created"),
            )
        }
    }
}

data class ManifestUnknown(
    val id: Id,
    val question: String,
    val importance: Confidence,
    val blocking: Boolean,
    val since: Timestamp? = null,
) {

    init {
        ProtocolIds.require(id, "unknown.id")
        require(question.isNotBlank()) { "unknown.question 不能为空" }
        requireConfidence(importance, "unknown.importance")
        require(since == null || isTimestamp(since)) { "unknown.since 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "question" to JsonValue.Str(question),
        "importance" to jsonNumber(importance),
        "blocking" to JsonValue.Bool(blocking),
        "since" to since?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ManifestUnknown {
            val obj = value.asManifestObject("unknown")
            return ManifestUnknown(
                id = obj.requireString("id"),
                question = obj.requireString("question"),
                importance = obj.double("importance") ?: throw IllegalArgumentException("缺少数字字段 importance"),
                blocking = obj.bool("blocking") ?: throw IllegalArgumentException("缺少布尔字段 blocking"),
                since = obj.string("since"),
            )
        }
    }
}

data class AttentionConfig(
    val policyDigest: Digest,
    val budget: Budget,
    val frame: Ref? = null,
) {

    init {
        require(Sha256.isValidDigest(policyDigest)) { "attention.policy_digest 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "policy_digest" to JsonValue.Str(policyDigest),
        "frame" to frame?.toJson(),
        "budget" to budget.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): AttentionConfig {
            val obj = value.asManifestObject("attention")
            return AttentionConfig(
                policyDigest = obj.requireString("policy_digest"),
                budget = Budget.fromJson(obj.requireObj("budget")),
                frame = obj.obj("frame")?.let(Ref::fromJson),
            )
        }
    }
}

data class CalibrationBucket(
    val lo: Double,
    val hi: Double,
    val predicted: Double,
    val observed: Double,
    val n: Int,
) {

    init {
        require(lo.isFinite() && hi.isFinite() && predicted.isFinite() && observed.isFinite()) {
            "calibration bucket 数值必须有限"
        }
        require(n >= 0) { "calibration bucket.n 必须 >= 0" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "lo" to jsonNumber(lo),
        "hi" to jsonNumber(hi),
        "predicted" to jsonNumber(predicted),
        "observed" to jsonNumber(observed),
        "n" to jsonNumber(n),
    )

    companion object {
        fun fromJson(value: JsonValue): CalibrationBucket {
            val obj = value.asManifestObject("calibration bucket")
            return CalibrationBucket(
                lo = obj.double("lo") ?: throw IllegalArgumentException("缺少数字字段 lo"),
                hi = obj.double("hi") ?: throw IllegalArgumentException("缺少数字字段 hi"),
                predicted = obj.double("predicted") ?: throw IllegalArgumentException("缺少数字字段 predicted"),
                observed = obj.double("observed") ?: throw IllegalArgumentException("缺少数字字段 observed"),
                n = obj.requireInt("n"),
            )
        }
    }
}

/** MEASURED block: every number here is computed from receipts, never self-reported. */
data class Calibration(
    val ece: Confidence,
    val buckets: List<CalibrationBucket>? = null,
) {

    init {
        requireConfidence(ece, "calibration.ece")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "ece" to jsonNumber(ece),
        "buckets" to buckets?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
    )

    companion object {
        fun fromJson(value: JsonValue): Calibration {
            val obj = value.asManifestObject("calibration")
            return Calibration(
                ece = obj.double("ece") ?: throw IllegalArgumentException("缺少数字字段 ece"),
                buckets = obj.arr("buckets")?.items?.map(CalibrationBucket::fromJson),
            )
        }
    }
}

data class SelfState(
    val load: Confidence? = null,
    val mode: SelfMode? = null,
) {

    init {
        load?.let { requireConfidence(it, "self.state.load") }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "load" to load?.let { jsonNumber(it) },
        "mode" to mode?.let { JsonValue.Str(it.wire) },
    )

    companion object {
        fun fromJson(value: JsonValue): SelfState {
            val obj = value.asManifestObject("self.state")
            return SelfState(
                load = obj.double("load"),
                mode = obj.string("mode")?.let(SelfMode::fromWire),
            )
        }
    }
}

data class SelfModel(
    val calibration: Calibration,
    val limits: List<String>? = null,
    val state: SelfState? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "calibration" to calibration.toJson(),
        "limits" to limits?.let(::jsonStrings),
        "state" to state?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): SelfModel {
            val obj = value.asManifestObject("self")
            return SelfModel(
                calibration = Calibration.fromJson(obj.requireObj("calibration")),
                limits = obj.readStrings("limits"),
                state = obj.obj("state")?.let(SelfState::fromJson),
            )
        }
    }
}

/**
 * The manifest before `digest` and `signatures` are attached.
 *
 * This is the Kotlin spelling of `Omit<Manifest, "digest" | "signatures">`;
 * [toJson] never writes either field, so [contentDigest] hashes exactly the
 * bytes the reference hashes.
 */
data class UnsignedManifest(
    val agent: AgentIdentity,
    val issued: Timestamp,
    val expires: Timestamp,
    val capabilities: List<Capability>,
    val attention: AttentionConfig,
    val self: SelfModel,
    val identity: Identity? = null,
    val concepts: List<Ref>? = null,
    val commitments: List<Commitment>? = null,
    val unknowns: List<ManifestUnknown>? = null,
    val amp: String = AmpWire.VERSION,
    val kind: String = "manifest",
) {

    init {
        require(amp == AmpWire.VERSION) { "manifest.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == "manifest") { "manifest.kind 必须是 manifest" }
        require(isTimestamp(issued)) { "manifest.issued 不是合法时间" }
        require(isTimestamp(expires)) { "manifest.expires 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "agent" to agent.toJson(),
        "issued" to JsonValue.Str(issued),
        "expires" to JsonValue.Str(expires),
        "identity" to identity?.toJson(),
        "capabilities" to jsonArr(*capabilities.map { it.toJson() }.toTypedArray()),
        "concepts" to concepts?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
        "commitments" to commitments?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
        "unknowns" to unknowns?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
        "attention" to attention.toJson(),
        "self" to self.toJson(),
    )

    fun toManifest(digest: Digest, signatures: List<Signature>): Manifest = Manifest(
        agent = agent,
        issued = issued,
        expires = expires,
        digest = digest,
        capabilities = capabilities,
        attention = attention,
        self = self,
        signatures = signatures,
        identity = identity,
        concepts = concepts,
        commitments = commitments,
        unknowns = unknowns,
        amp = amp,
        kind = kind,
    )

    companion object {
        fun fromJson(value: JsonValue): UnsignedManifest {
            val obj = value.asManifestObject("manifest")
            return UnsignedManifest(
                agent = AgentIdentity.fromJson(obj.requireObj("agent")),
                issued = obj.requireString("issued"),
                expires = obj.requireString("expires"),
                capabilities = obj.requireArr("capabilities").items.map(Capability::fromJson),
                attention = AttentionConfig.fromJson(obj.requireObj("attention")),
                self = SelfModel.fromJson(obj.requireObj("self")),
                identity = obj.obj("identity")?.let(Identity::fromJson),
                concepts = obj.arr("concepts")?.items?.map(Ref::fromJson),
                commitments = obj.arr("commitments")?.items?.map(Commitment::fromJson),
                unknowns = obj.arr("unknowns")?.items?.map(ManifestUnknown::fromJson),
                amp = obj.string("amp") ?: AmpWire.VERSION,
                kind = obj.string("kind") ?: "manifest",
            )
        }
    }
}

data class Manifest(
    val agent: AgentIdentity,
    val issued: Timestamp,
    val expires: Timestamp,
    val digest: Digest,
    val capabilities: List<Capability>,
    val attention: AttentionConfig,
    val self: SelfModel,
    val signatures: List<Signature>,
    val identity: Identity? = null,
    val concepts: List<Ref>? = null,
    val commitments: List<Commitment>? = null,
    val unknowns: List<ManifestUnknown>? = null,
    val amp: String = AmpWire.VERSION,
    val kind: String = "manifest",
) {

    init {
        require(amp == AmpWire.VERSION) { "manifest.amp 必须是 ${AmpWire.VERSION}" }
        require(kind == "manifest") { "manifest.kind 必须是 manifest" }
        require(isTimestamp(issued)) { "manifest.issued 不是合法时间" }
        require(isTimestamp(expires)) { "manifest.expires 不是合法时间" }
        require(Sha256.isValidDigest(digest)) { "manifest.digest 格式非法" }
    }

    fun unsigned(): UnsignedManifest = UnsignedManifest(
        agent = agent,
        issued = issued,
        expires = expires,
        capabilities = capabilities,
        attention = attention,
        self = self,
        identity = identity,
        concepts = concepts,
        commitments = commitments,
        unknowns = unknowns,
        amp = amp,
        kind = kind,
    )

    fun toJson(): JsonValue.Obj = unsigned().toJson()
        .copyWith("digest", JsonValue.Str(digest))
        .copyWith("signatures", jsonArr(*signatures.map { it.toJson() }.toTypedArray()))

    /** The bytes covered by the signature: everything except `signatures`. */
    fun bodyWithDigestJson(): JsonValue.Obj = unsigned().toJson().copyWith("digest", JsonValue.Str(digest))

    /** The bytes covered by the content digest: no `digest`, no `signatures`. */
    fun unsignedJson(): JsonValue.Obj = unsigned().toJson()

    companion object {

        fun fromJson(value: JsonValue): Manifest {
            val obj = value.asManifestObject("manifest")
            val unsigned = UnsignedManifest.fromJson(obj)
            val digest = obj.requireString("digest")
            val signatures = obj.requireArr("signatures").items.map(Signature::fromJson)
            return unsigned.toManifest(digest, signatures)
        }

        /** Verifies the `self` signature; see [verifyManifest]. */
        fun verify(manifest: Manifest, publicKey: PublicKey): VerifyResult =
            verifyManifest(manifest, publicKey)

        /** Content digest of the unsigned manifest body. */
        fun contentDigest(manifest: UnsignedManifest): Digest = contentDigestOf(manifest)
    }
}

private fun JsonValue.asManifestObject(what: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw IllegalArgumentException("$what 必须是 JSON 对象")

private fun JsonValue.Obj.readStrings(key: String): List<String>? =
    arr(key)?.items?.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 的元素必须是字符串")
    }
