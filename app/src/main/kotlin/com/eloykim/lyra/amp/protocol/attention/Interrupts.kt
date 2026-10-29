package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Handle
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence
import java.time.Instant

/**
 * `src/attention/interrupts.ts` 的忠实移植：Attention Ledger 缺失的对象 `Interrupt`。
 *
 * [AttentionBroker] 给出 grant/defer/deny，但决定本身不是可审计的账本行，且延迟请求
 * 不会自己过期。本文件把每个决定变成 [InterruptRecord]，发出对应 receipt，并清扫
 * 超过响应窗口的延迟请求。它刻意不取代 broker：策略、安静时段、冷却与债务仍只有一处。
 *
 * 绑定说明：`receipt.Receipt` 尚未在并行波次中落盘，因此 [AttentionReceipt] 是 receipt
 * 线格式的结构等价模型（字段名与 `receipt.schema.json` 一致）。集成时用
 * `com.eloykim.lyra.amp.protocol.receipt.Receipt` 替换或做纯转换即可。
 */

enum class InterruptStatus(val wire: String) {
    GRANTED("granted"),
    DEFERRED("deferred"),
    DENIED("denied"),
    ANSWERED("answered"),
    EXPIRED("expired"),
    WITHDRAWN("withdrawn"),
    ;

    companion object {
        fun fromWire(value: String): InterruptStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 interrupt status: $value")
    }
}

enum class ReceiptOp(val wire: String) {
    ATTEND("attend"),
    ACT("act"),
    ASK("ask"),
    NOTE("note"),
    PLAN("plan"),
    REFLECT("reflect"),
    READ("read"),
    ;

    companion object {
        fun fromWire(value: String): ReceiptOp =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 receipt.op: $value")
    }
}

enum class Outcome(val wire: String) {
    SUCCESS("success"),
    PARTIAL("partial"),
    FAILURE("failure"),
    BLOCKED("blocked"),
    ;

    companion object {
        fun fromWire(value: String): Outcome =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 receipt.outcome: $value")
    }
}

// ---------------------------------------------------------------------------
// receipt 的结构等价模型
// ---------------------------------------------------------------------------

data class AttentionReceipt(
    val id: Id,
    val op: ReceiptOp,
    val at: Timestamp,
    val usage: Budget,
    val outcome: Outcome,
    val target: Ref? = null,
    val ms: Long? = null,
    /** 动作前记录，用于和 realized_voi 对比校准控制器。 */
    val predictedVoi: Confidence? = null,
    val realizedVoi: Confidence? = null,
    val handle: Handle? = null,
    val note: String? = null,
) {

    init {
        ProtocolIds.require(id, "receipt.id")
        require(isTimestamp(at)) { "receipt.at 不是合法时间" }
        require(ms == null || ms >= 0) { "receipt.ms 必须 >= 0" }
        predictedVoi?.let { requireConfidence(it, "receipt.predicted_voi") }
        realizedVoi?.let { requireConfidence(it, "receipt.realized_voi") }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str(KIND_RECEIPT),
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
        fun fromJson(value: JsonValue): AttentionReceipt {
            val obj = value.requireAmpAndKind("receipt", KIND_RECEIPT)
            return AttentionReceipt(
                id = obj.requireString("id"),
                op = ReceiptOp.fromWire(obj.requireString("op")),
                at = obj.requireString("at"),
                usage = Budget.fromJson(obj.requireObj("usage")),
                outcome = Outcome.fromWire(obj.requireString("outcome")),
                target = obj.obj("target")?.let(Ref::fromJson),
                ms = obj.long("ms"),
                predictedVoi = obj.double("predicted_voi"),
                realizedVoi = obj.double("realized_voi"),
                handle = obj.obj("handle")?.let(Handle::fromJson),
                note = obj.string("note"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Interrupt
// ---------------------------------------------------------------------------

data class InterruptRecord(
    val id: Id,
    val request: Id,
    val interruptClass: InterruptClass,
    val target: Ref,
    val urgency: Int,
    val granted: Boolean,
    val status: InterruptStatus,
    val cost: Budget,
    val at: Timestamp,
    val debtWeight: Double,
    val reason: String,
    val receipt: Id,
    val expires: Timestamp? = null,
) {

    init {
        ProtocolIds.require(id, "interrupt.id")
        ProtocolIds.require(request, "interrupt.request")
        ProtocolIds.require(receipt, "interrupt.receipt")
        require(urgency in 1..5) { "interrupt.urgency 必须在 1..5" }
        require(debtWeight.isFinite()) { "interrupt.debt_weight 必须有限" }
        require(isTimestamp(at)) { "interrupt.at 不是合法时间" }
        require(expires == null || isTimestamp(expires)) { "interrupt.expires 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str(KIND_INTERRUPT),
        "id" to JsonValue.Str(id),
        "request" to JsonValue.Str(request),
        "class" to JsonValue.Str(interruptClass.wire),
        "target" to target.toJson(),
        "urgency" to jsonNumber(urgency),
        "granted" to jsonBool(granted),
        "status" to JsonValue.Str(status.wire),
        "cost" to cost.toJson(),
        "at" to JsonValue.Str(at),
        "expires" to expires?.let { JsonValue.Str(it) },
        "debt_weight" to jsonNumber(debtWeight),
        "reason" to JsonValue.Str(reason),
        "receipt" to JsonValue.Str(receipt),
    )

    companion object {
        fun fromJson(value: JsonValue): InterruptRecord {
            val obj = value.requireAmpAndKind("interrupt", KIND_INTERRUPT)
            return InterruptRecord(
                id = obj.requireString("id"),
                request = obj.requireString("request"),
                interruptClass = InterruptClass.fromWire(obj.requireString("class")),
                target = Ref.fromJson(obj.requireObj("target")),
                urgency = obj.requireInt("urgency"),
                granted = obj.requireBool("granted"),
                status = InterruptStatus.fromWire(obj.requireString("status")),
                cost = Budget.fromJson(obj.requireObj("cost")),
                at = obj.requireString("at"),
                debtWeight = obj.requireDouble("debt_weight"),
                reason = obj.requireString("reason"),
                receipt = obj.requireString("receipt"),
                expires = obj.string("expires"),
            )
        }
    }
}

data class InterruptSubmission(
    val interrupt: InterruptRecord,
    val decision: AttentionDecision,
    val receipt: AttentionReceipt,
)

data class AttentionLedgerOptions(
    val now: () -> Timestamp,
    val policy: AttentionPolicy = AttentionPolicy(),
    /** 延迟请求未携带 `expires` 时的响应窗口。 */
    val defaultExpiryMs: Long = 60_000,
    val onReceipt: ((AttentionReceipt) -> Unit)? = null,
)

/**
 * 产生 Interrupt 的 broker 门面。
 *
 * 每个提交的请求恰好产生一条 [InterruptRecord] 和一个 Receipt（op `ask`）。延迟记录
 * 在结清或过期前持有债务；[sweep] 关闭超时窗口的记账。
 */
class AttentionLedger(options: AttentionLedgerOptions) {

    val broker: AttentionBroker = AttentionBroker(options.now, options.policy)
    private val now: () -> Timestamp = options.now
    private val defaultExpiryMs: Long = options.defaultExpiryMs
    private val onReceipt: ((AttentionReceipt) -> Unit)? = options.onReceipt
    private val records = ArrayList<InterruptRecord>()
    private val byRequest = LinkedHashMap<Id, InterruptRecord>()
    private var seq: Int = 0

    fun submit(request: AttentionRequest): InterruptSubmission {
        check(!byRequest.containsKey(request.id)) { "duplicate interrupt request: ${request.id}" }
        val decision: AttentionDecision = broker.submit(request)
        val status: InterruptStatus = statusOf(decision)
        seq += 1
        val id: Id = "int.$seq"
        val expires: Timestamp? = if (status == InterruptStatus.DEFERRED) {
            request.expires ?: addTimestamps(decision.at, defaultExpiryMs)
        } else {
            request.expires
        }
        val interrupt = InterruptRecord(
            id = id,
            request = request.id,
            interruptClass = request.interruptClass,
            target = request.target,
            urgency = request.urgency,
            granted = status == InterruptStatus.GRANTED,
            status = status,
            cost = request.cost,
            at = decision.at,
            expires = expires,
            debtWeight = request.urgency.toDouble(),
            reason = decision.reason,
            receipt = "rc.ask.$seq",
        )

        // 参考实现先写 derived interrupts，再展开 request.cost；因此请求自带的
        // interrupts 计数优先。
        val derivedInterrupts: Int = if (status == InterruptStatus.GRANTED) 1 else 0
        val usage: Budget = if (request.cost.interrupts != null) {
            request.cost
        } else {
            request.cost.copy(interrupts = derivedInterrupts)
        }
        val outcome: Outcome = when (status) {
            InterruptStatus.DENIED -> Outcome.BLOCKED
            InterruptStatus.DEFERRED -> Outcome.PARTIAL
            else -> Outcome.SUCCESS
        }
        val receipt = AttentionReceipt(
            id = interrupt.receipt,
            op = ReceiptOp.ASK,
            target = request.target,
            at = decision.at,
            usage = usage,
            outcome = outcome,
            note = "${request.interruptClass.wire}:${status.wire}:${decision.reason}",
        )

        records.add(interrupt)
        byRequest[request.id] = interrupt
        onReceipt?.invoke(receipt)
        return InterruptSubmission(interrupt, decision, receipt)
    }

    /**
     * 结清一笔延迟请求，返回更新后的记录；请求不存在或不是 deferred 时返回 null。
     */
    fun resolve(requestId: Id, outcome: InterruptStatus = InterruptStatus.ANSWERED): InterruptRecord? {
        require(
            outcome == InterruptStatus.ANSWERED ||
                outcome == InterruptStatus.EXPIRED ||
                outcome == InterruptStatus.WITHDRAWN,
        ) { "resolve outcome 必须是 answered/expired/withdrawn" }
        val record: InterruptRecord = byRequest[requestId] ?: return null
        if (record.status != InterruptStatus.DEFERRED) {
            return null
        }
        runCatching { broker.resolve(requestId, outcome.toDebtResolution()) }
        val updated: InterruptRecord = record.copy(status = outcome)
        replace(updated)
        return updated
    }

    /**
     * 让响应窗口已过的延迟请求过期。幂等：已结清/已过期的记录会被跳过。
     */
    fun sweep(at: Timestamp = now()): List<InterruptRecord> {
        val nowMs: Long = timestampMillis(at)
        val expired = ArrayList<InterruptRecord>()
        for (record in records.toList()) {
            if (record.status != InterruptStatus.DEFERRED || record.expires == null) {
                continue
            }
            if (timestampMillis(record.expires) > nowMs) {
                continue
            }
            val updated: InterruptRecord = resolve(record.request, InterruptStatus.EXPIRED) ?: record
            expired.add(updated)
        }
        return expired
    }

    fun all(): List<InterruptRecord> = records.toList()

    fun get(requestId: Id): InterruptRecord? = byRequest[requestId]

    fun open(): List<InterruptRecord> = records.filter { it.status == InterruptStatus.DEFERRED }

    fun grantedCount(): Int = records.count { it.status == InterruptStatus.GRANTED }

    fun debtScore(at: Timestamp = now()): Long = broker.debtScore(at)

    private fun replace(record: InterruptRecord) {
        val index: Int = records.indexOfFirst { it.request == record.request }
        if (index >= 0) {
            records[index] = record
        }
        byRequest[record.request] = record
    }
}

// ---------------------------------------------------------------------------
// 外部内容准入（A-4）
// ---------------------------------------------------------------------------

const val MaxExternalShare: Double = 0.15

data class AdmissionVerdict(val ok: Boolean, val reason: String? = null)

data class ExternalAdmissionInput(
    val role: String,
    val share: Double,
    val goalSlot: Boolean? = null,
    val maxShare: Double? = null,
)

/**
 * A-4：外部内容可以存在，但不能给自己买显著性，也不能占据 goal 槽位。`share` 是组装
 * 上下文中外部内容所占的比例。
 */
fun admitExternal(input: ExternalAdmissionInput): AdmissionVerdict {
    if (input.goalSlot == true) {
        return AdmissionVerdict(false, "external content cannot occupy the goal slot")
    }
    if (!input.share.isFinite() || input.share < 0 || input.share > 1) {
        return AdmissionVerdict(false, "invalid external share: ${input.share}")
    }
    val limit: Double = input.maxShare ?: MaxExternalShare
    require(limit.isFinite() && limit in 0.0..1.0) { "maxShare must be in [0, 1]" }
    if (input.share > limit) {
        return AdmissionVerdict(false, "external share ${input.share} exceeds $limit")
    }
    return AdmissionVerdict(true)
}

// ---------------------------------------------------------------------------
// 内部辅助
// ---------------------------------------------------------------------------

private const val KIND_INTERRUPT: String = "interrupt"
private const val KIND_RECEIPT: String = "receipt"

internal fun statusOf(decision: AttentionDecision): InterruptStatus = when (decision.decision) {
    Decision.GRANT -> InterruptStatus.GRANTED
    Decision.DEFER -> InterruptStatus.DEFERRED
    Decision.DENY -> InterruptStatus.DENIED
}

private fun InterruptStatus.toDebtResolution(): DebtResolution = when (this) {
    InterruptStatus.EXPIRED -> DebtResolution.EXPIRED
    InterruptStatus.WITHDRAWN -> DebtResolution.WITHDRAWN
    else -> DebtResolution.ANSWERED
}

internal fun addTimestamps(at: Timestamp, ms: Long): Timestamp =
    isoMillis(Instant.parse(at).plusMillis(ms))
