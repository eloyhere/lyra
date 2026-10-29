package com.eloykim.lyra.amp.protocol.warrant

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * 探究账本：预注册、观察次数与复制判定。
 *
 * 对应参考实现 `src/warrant/inquiry.ts`。注册发生在观察之前；重复查看被记为
 * 又一次 look，而不是一次全新的干净实验。
 */
data class ExperimentLook(val value: Double, val at: Timestamp) {

    init {
        require(value.isFinite()) { "look.value 必须是有限数" }
        require(isTimestamp(at)) { "look.at 不是合法时间: $at" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "value" to jsonNumber(value),
        "at" to JsonValue.Str(at),
    )

    companion object {
        fun fromJson(value: JsonValue): ExperimentLook {
            val obj = value.asObject("look")
            return ExperimentLook(
                value = obj.double("value") ?: throw IllegalArgumentException("缺少数字字段 value"),
                at = obj.requireString("at"),
            )
        }
    }
}

enum class ExperimentStatus(val wire: String) {
    REGISTERED("registered"),
    RUNNING("running"),
    COMPLETED("completed"),
    ABANDONED("abandoned");

    companion object {
        fun fromWire(value: String): ExperimentStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 experiment.status: $value")
    }
}

enum class ExperimentVerdict(val wire: String) {
    SUPPORTED("supported"),
    REFUTED("refuted"),
    INCONCLUSIVE("inconclusive");

    companion object {
        fun fromWire(value: String): ExperimentVerdict =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 experiment.verdict: $value")
    }
}

/**
 * 一次已注册的实验。looks 与 warnings 是可变的，与参考实现的可变对象一致。
 */
class Experiment(
    val id: Id,
    val hypothesis: Id,
    val preregHash: Digest,
    val metric: String,
    val stopping: String,
    val exclusions: List<String>,
    val registeredAt: Timestamp,
    var status: ExperimentStatus = ExperimentStatus.RUNNING,
) {

    init {
        ProtocolIds.require(id, "experiment.id")
        ProtocolIds.require(hypothesis, "experiment.hypothesis")
        require(Sha256.isValidDigest(preregHash)) { "experiment.prereg_hash 格式非法" }
        require(isTimestamp(registeredAt)) { "experiment.registered_at 不是合法时间" }
    }

    val looks: MutableList<ExperimentLook> = ArrayList()
    var verdict: ExperimentVerdict? = null
    val warnings: MutableList<String> = ArrayList()

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "hypothesis" to JsonValue.Str(hypothesis),
        "prereg_hash" to JsonValue.Str(preregHash),
        "metric" to JsonValue.Str(metric),
        "stopping" to JsonValue.Str(stopping),
        "exclusions" to jsonStrings(exclusions),
        "registered_at" to JsonValue.Str(registeredAt),
        "status" to JsonValue.Str(status.wire),
        "looks" to JsonValue.Arr(looks.map { it.toJson() }),
        "verdict" to verdict?.let { JsonValue.Str(it.wire) },
        "warnings" to jsonStrings(warnings),
    )

    companion object {
        fun fromJson(value: JsonValue): Experiment {
            val obj = value.asObject("experiment")
            val experiment = Experiment(
                id = obj.requireString("id"),
                hypothesis = obj.requireString("hypothesis"),
                preregHash = obj.requireString("prereg_hash"),
                metric = obj.requireString("metric"),
                stopping = obj.requireString("stopping"),
                exclusions = obj.strings("exclusions"),
                registeredAt = obj.requireString("registered_at"),
                status = ExperimentStatus.fromWire(obj.requireString("status")),
            )
            obj.arr("looks")?.items?.forEach { experiment.looks += ExperimentLook.fromJson(it) }
            experiment.verdict = obj.string("verdict")?.let(ExperimentVerdict::fromWire)
            obj.arr("warnings")?.items?.forEach { item ->
                experiment.warnings += (item as? JsonValue.Str)?.value
                    ?: throw IllegalArgumentException("experiment.warnings 必须是字符串数组")
            }
            return experiment
        }
    }
}

/** 一次运行的身份。复制判定关心的是 run / seed / origin 是否真的不同。 */
data class RunContext(
    val runId: String,
    val seed: String,
    val origin: String,
    val environment: String? = null,
)

/** T4 需要独立重跑；只有致命原因全部消失才升到 T4。 */
data class ReplicationVerdict(
    val ok: Boolean,
    val reasons: List<String>,
    val tier: Tier,
) {

    init {
        require(tier == Tier.T3 || tier == Tier.T4) { "replication tier 只能是 T3 或 T4" }
    }
}

/**
 * 预注册哈希覆盖的内容。顺序由 canonical() 固定，形状不固定。
 */
fun preregHashOf(
    statement: String,
    metric: String,
    stopping: String,
    exclusions: List<String> = emptyList(),
): Digest = CanonicalJson.digest(
    jsonObj(
        "statement" to JsonValue.Str(statement),
        "metric" to JsonValue.Str(metric),
        "stopping" to JsonValue.Str(stopping),
        "exclusions" to jsonStrings(exclusions),
    ),
)

/** 探究日志：注册、观察、结论与层级。 */
class InquiryLog(private val now: () -> Timestamp) {

    private val experiments = LinkedHashMap<Id, Experiment>()
    private var seq = 0

    fun register(hypothesis: Hypothesis, exclusions: List<String>? = null): Experiment {
        val prereg = hypothesis.prereg
            ?: throw IllegalArgumentException("hypothesis must be pre-registered before an experiment can start")
        val expected = preregHashOf(
            statement = hypothesis.statement,
            metric = prereg.metric,
            stopping = prereg.stopping,
            exclusions = prereg.exclusions ?: exclusions ?: emptyList(),
        )
        if (expected != prereg.hash) {
            throw IllegalArgumentException("prereg hash does not match its content: the hypothesis changed after registration")
        }

        seq += 1
        val experiment = Experiment(
            id = "ex.$seq",
            hypothesis = hypothesis.id,
            preregHash = prereg.hash,
            metric = prereg.metric,
            stopping = prereg.stopping,
            exclusions = prereg.exclusions ?: exclusions ?: emptyList(),
            registeredAt = prereg.registeredAt,
            status = ExperimentStatus.RUNNING,
        )
        experiments[experiment.id] = experiment
        return experiment
    }

    /** 任何一次对结果的观察都必须记录，不是可选项。 */
    fun observe(experimentId: Id, value: Double): Experiment {
        val experiment = experiment(experimentId)
        if (experiment.status == ExperimentStatus.COMPLETED) {
            throw IllegalArgumentException("experiment already completed")
        }
        if (experiment.status == ExperimentStatus.ABANDONED) {
            throw IllegalArgumentException("experiment abandoned")
        }

        if (experiment.stopping == "fixed_n_10m" && experiment.looks.size >= 1) {
            experiment.warnings += "extra look beyond the pre-registered fixed-n design"
        }
        experiment.looks += ExperimentLook(value, now())
        return experiment
    }

    fun conclude(experimentId: Id, verdict: ExperimentVerdict): Experiment {
        val experiment = experiment(experimentId)
        if (experiment.looks.isEmpty()) {
            throw IllegalArgumentException("cannot conclude without a single look")
        }
        experiment.verdict = verdict
        experiment.status = ExperimentStatus.COMPLETED
        return experiment
    }

    /** 实验本身赢得的层级：只有跑完且预注册的实验才是 T3。 */
    fun tierOf(experimentId: Id): Tier {
        val experiment = experiment(experimentId)
        return if (experiment.status == ExperimentStatus.COMPLETED) Tier.T3 else Tier.T2
    }

    fun experiment(id: Id): Experiment =
        experiments[id] ?: throw IllegalArgumentException("unknown experiment: $id")

    fun list(): List<Experiment> = experiments.values.toList()
}

/**
 * T4 需要独立重跑：不同的 run、不同的 seed、不同的 origin。
 */
fun replicationCheck(original: RunContext, replica: RunContext): ReplicationVerdict {
    val reasons = ArrayList<String>()
    if (original.runId.isEmpty() || replica.runId.isEmpty()) {
        reasons += "run ids are required"
    }
    if (original.runId == replica.runId) {
        reasons += "same run: re-asking is not replication"
    }
    if (original.seed == replica.seed) {
        reasons += "same seed: not an independent sample"
    }
    if (original.origin == replica.origin) {
        reasons += "same origin: not independent testimony"
    }
    if (replica.environment != null && original.environment == replica.environment) {
        reasons += "same environment: weaker replication (allowed, but not sufficient alone for T4)"
    }

    val fatal = reasons.filter { !it.startsWith("same environment") }
    return ReplicationVerdict(
        ok = fatal.isEmpty(),
        reasons = reasons,
        tier = if (fatal.isEmpty()) Tier.T4 else Tier.T3,
    )
}

private fun JsonValue.Obj.strings(key: String): List<String> =
    requireArr(key).items.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }
