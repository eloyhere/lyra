package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.storage.AppendLog
import com.eloykim.lyra.amp.protocol.storage.AppendSink
import com.eloykim.lyra.amp.protocol.storage.MemoryAppendSink
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.nowTimestamp

/**
 * The governance audit chain, a faithful port of `src/governance/audit.ts`.
 *
 * Every governance act that changes authority or spends budget leaves a
 * record: manifest issue/revision, credential issue/revocation, receipt write,
 * charge/transfer, legal order, workflow transition and a settled turn. The
 * chain is append-only and hash-linked, so deleting, reordering or editing an
 * entry is detectable.
 *
 * The log is deliberately generic: the caller chooses the category and the
 * small payload. Full bodies never enter the audit; ids, digests, codes and
 * amounts are enough to reconstruct authority.
 *
 * 落盘与链校验复用 `storage/AppendLog.kt`（参考实现的同一模块），因此审计链
 * 与账本快照使用同一哈希函数 `digest({seq, kind, payload, prev})`，可以互相
 * 导出与校验。与参考实现唯一的差异在并发：TS 的 `append` 是 Promise 串行队列，
 * Kotlin 端口是同步调用；共享实例的并发由宿主串行化。
 */

/** Audit categories, matching `GovernanceAuditCategory`. */
enum class GovernanceAuditCategory(val wire: String) {
    MANIFEST("manifest"),
    CREDENTIAL("credential"),
    RECEIPT("receipt"),
    BUDGET("budget"),
    LEGAL("legal"),
    WORKFLOW("workflow"),
    SWITCH("switch"),
    COOPERATION("cooperation"),
    CONTROL("control"),
    RUNTIME("runtime");

    companion object {
        fun fromWireOrNull(value: String): GovernanceAuditCategory? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * One audit record body. `subject`, `refs` and `data` are optional and are
 * omitted rather than written as null.
 */
data class GovernanceAuditRecord(
    val category: GovernanceAuditCategory,
    /** Stable action name, e.g. `manifest.issued`, `budget.charged`. */
    val action: String,
    /** Perspective, agent id, or `human`. */
    val actor: String,
    val detail: String,
    val subject: String? = null,
    val refs: List<String>? = null,
    val data: JsonValue.Obj? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "category" to jsonText(category.wire),
        "action" to jsonText(action),
        "actor" to jsonText(actor),
        "subject" to subject?.let { jsonText(it) },
        "detail" to jsonText(detail),
        "refs" to refs?.let { values -> jsonArr(*values.map { jsonText(it) }.toTypedArray()) },
        "data" to data,
    )

    companion object {
        fun fromJson(value: JsonValue): GovernanceAuditRecord {
            val obj = value.asObject("audit record")
            val category = GovernanceAuditCategory.fromWireOrNull(obj.requireString("category"))
                ?: throw IllegalArgumentException("audit category is invalid")
            return GovernanceAuditRecord(
                category = category,
                action = obj.requireString("action"),
                actor = obj.requireString("actor"),
                detail = obj.requireString("detail"),
                subject = obj.string("subject"),
                refs = obj.arr("refs")?.items?.map { (it as? JsonValue.Str)?.value ?: "" },
                data = obj.obj("data"),
            )
        }
    }
}

/** One chained audit entry. */
data class GovernanceAuditEntry(
    val seq: Int,
    val at: Timestamp? = null,
    val record: GovernanceAuditRecord,
    val prev: Digest,
    val digest: Digest,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "seq" to jsonNumber(seq),
        "at" to at?.let { jsonText(it) },
        "record" to record.toJson(),
        "prev" to jsonText(prev),
        "digest" to jsonText(digest),
    )
}

/** The audit chain's verification verdict. */
data class AuditVerdict(
    val ok: Boolean,
    val reasons: List<String>,
) {
    companion object {
        fun success(): AuditVerdict = AuditVerdict(true, emptyList())
    }
}

/** A sealed commitment to the chain's length and tip. */
data class GovernanceAuditCheckpoint(
    val count: Int,
    val tip: Digest,
    val at: Timestamp,
    val digest: Digest,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "count" to jsonNumber(count),
        "tip" to jsonText(tip),
        "at" to jsonText(at),
        "digest" to jsonText(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): GovernanceAuditCheckpoint {
            val obj = value.asObject("audit checkpoint")
            return GovernanceAuditCheckpoint(
                count = obj.requireInt("count"),
                tip = obj.requireString("tip"),
                at = obj.requireString("at"),
                digest = obj.requireString("digest"),
            )
        }
    }
}

/** Maximum refs per record; a long ref list is a caller bug, not an audit. */
const val MAX_AUDIT_REFS: Int = 32

private fun validatePayload(value: JsonValue): List<String> {
    val problems = ArrayList<String>()
    if (value !is JsonValue.Obj) {
        return listOf("audit payload is not an object")
    }
    val category = value.string("category")
    if (category == null || GovernanceAuditCategory.fromWireOrNull(category) == null) {
        problems += "audit category is invalid"
    }
    val action = value.string("action")
    if (action.isNullOrEmpty()) {
        problems += "audit action is empty"
    }
    val actor = value.string("actor")
    if (actor.isNullOrEmpty()) {
        problems += "audit actor is empty"
    }
    val detail = value.string("detail")
    if (detail.isNullOrEmpty()) {
        problems += "audit detail is empty"
    }
    val refs = value["refs"]
    if (refs != null) {
        if (refs !is JsonValue.Arr) {
            problems += "audit refs must be strings"
        } else {
            if (refs.items.any { it !is JsonValue.Str }) {
                problems += "audit refs must be strings"
            }
            if (refs.items.size > MAX_AUDIT_REFS) {
                problems += "audit refs exceed $MAX_AUDIT_REFS"
            }
        }
    }
    val data = value["data"]
    if (data != null && data !is JsonValue.Obj) {
        problems += "audit data must be an object"
    }
    return problems
}

/**
 * The governance audit log.
 */
class GovernanceAuditLog private constructor(
    private val log: AppendLog,
    private val now: () -> Timestamp,
) {

    companion object {

        fun open(
            sink: AppendSink = MemoryAppendSink(),
            now: () -> Timestamp = { nowTimestamp() },
        ): GovernanceAuditLog = GovernanceAuditLog(AppendLog.open(sink), now)
    }

    val count: Int get() = log.count

    fun tip(): Digest = log.tip

    /**
     * Append one record. The record is validated before it reaches the chain;
     * an invalid record is a caller bug, not a policy denial.
     */
    fun record(record: GovernanceAuditRecord): GovernanceAuditEntry {
        val payload = record.toJson()
        val problems = validatePayload(payload)
        if (problems.isNotEmpty()) {
            throw GovernanceError(
                GovernanceCode.REGISTRY_INVALID,
                "invalid audit record: ${problems.joinToString("; ")}",
            )
        }
        val entry = log.append(EntryKind.EVENT, payload, now())
        return GovernanceAuditEntry(
            seq = entry.seq,
            at = entry.at,
            record = GovernanceAuditRecord.fromJson(payload),
            prev = entry.prev,
            digest = entry.hash,
        )
    }

    /** Defensive copies. Callers cannot mutate the chain through this view. */
    fun entries(): List<GovernanceAuditEntry> = log.entries().map { entry ->
        GovernanceAuditEntry(
            seq = entry.seq,
            at = entry.at,
            record = GovernanceAuditRecord.fromJson(entry.payload),
            prev = entry.prev,
            digest = entry.hash,
        )
    }

    /** Chain and payload verification. A mutation after append makes this fail. */
    fun verify(): AuditVerdict {
        val chain = log.verify()
        val reasons = chain.reasons.toMutableList()
        log.entries().forEachIndexed { index, entry ->
            validatePayload(entry.payload).forEach { problem ->
                reasons += "entry $index: $problem"
            }
        }
        return AuditVerdict(ok = reasons.isEmpty(), reasons = reasons)
    }

    /**
     * Seal the current tip. A checkpoint lets a retained segment be verified
     * without replaying entries older than `count`.
     */
    fun checkpoint(at: Timestamp = now()): GovernanceAuditCheckpoint {
        val tip = log.tip
        val count = log.count
        return GovernanceAuditCheckpoint(
            count = count,
            tip = tip,
            at = at,
            digest = checkpointDigest(count, tip, at),
        )
    }

    /**
     * A checkpoint is valid when its digest is intact, its count is not ahead
     * of the chain, and the entry at that position has the recorded tip.
     */
    fun verifyCheckpoint(checkpoint: GovernanceAuditCheckpoint): AuditVerdict {
        val reasons = ArrayList<String>()
        if (checkpoint.digest != checkpointDigest(checkpoint.count, checkpoint.tip, checkpoint.at)) {
            reasons += "checkpoint digest does not verify"
        }
        if (checkpoint.count < 0 || checkpoint.count > log.count) {
            reasons += "checkpoint count is outside the chain"
        }
        if (checkpoint.count in 1..log.count) {
            val entries = log.entries()
            if (entries[checkpoint.count - 1].hash != checkpoint.tip) {
                reasons += "checkpoint tip does not match the chain"
            }
        }
        return AuditVerdict(ok = reasons.isEmpty(), reasons = reasons)
    }

    fun entriesAfter(seq: Int): List<GovernanceAuditEntry> = entries().filter { it.seq >= seq }

    fun byCategory(category: GovernanceAuditCategory): List<GovernanceAuditEntry> =
        entries().filter { it.record.category == category }

    fun bySubject(subject: String): List<GovernanceAuditEntry> =
        entries().filter { it.record.subject == subject }

    fun recordManifest(manifest: AttentionManifest, actor: String): GovernanceAuditEntry = record(
        GovernanceAuditRecord(
            category = GovernanceAuditCategory.MANIFEST,
            action = if (manifest.version == 1) "manifest.issued" else "manifest.revised",
            actor = actor,
            subject = manifest.id,
            detail = "v${manifest.version} ${manifest.scenario.wire} floor=${manifest.evidenceFloor.wire}",
            refs = listOf(manifest.digest),
            data = jsonObj(
                "id" to jsonText(manifest.id),
                "version" to jsonNumber(manifest.version),
                "scenario" to jsonText(manifest.scenario.wire),
                "floor" to jsonText(manifest.evidenceFloor.wire),
                "expires" to jsonText(manifest.expires),
            ),
        ),
    )

    fun recordCredential(
        credential: SwitchCredential,
        action: CredentialAuditAction = CredentialAuditAction.ISSUED,
    ): GovernanceAuditEntry = record(
        GovernanceAuditRecord(
            category = GovernanceAuditCategory.CREDENTIAL,
            action = "credential.${action.wire}",
            actor = PerspectiveId.GENERAL_MANAGER.wire,
            subject = credential.id,
            detail = "${credential.from.wire}->${credential.to.wire} until ${credential.expires}",
            refs = listOf(credential.digest, credential.manifest.id),
            data = jsonObj(
                "id" to jsonText(credential.id),
                "to" to jsonText(credential.to.wire),
                "manifest" to jsonObj(
                    "id" to jsonText(credential.manifest.id),
                    "version" to jsonNumber(credential.manifest.version),
                ),
                "expires" to jsonText(credential.expires),
            ),
        ),
    )

    fun recordReceipt(receipt: BoardReceipt): GovernanceAuditEntry = record(
        GovernanceAuditRecord(
            category = GovernanceAuditCategory.RECEIPT,
            action = "receipt.written",
            actor = receipt.from.wire,
            subject = receipt.id,
            detail = "confidence=${fixed2(receipt.confidence)}",
            refs = listOf(receipt.digest, "${receipt.manifest.id}@${receipt.manifest.version}"),
            data = jsonObj(
                "id" to jsonText(receipt.id),
                "manifest" to jsonObj(
                    "id" to jsonText(receipt.manifest.id),
                    "version" to jsonNumber(receipt.manifest.version),
                ),
                "evidence" to jsonNumber(receipt.evidence.size),
            ),
        ),
    )

    fun recordCharge(charge: ChargeResult, ledgerId: String, actor: String): GovernanceAuditEntry = record(
        GovernanceAuditRecord(
            category = GovernanceAuditCategory.BUDGET,
            action = "budget.${charge.code.wire}",
            actor = actor,
            subject = ledgerId,
            detail = "ratio=${fixed2(charge.ratio)}",
            data = jsonObj(
                "code" to jsonText(charge.code.wire),
                "ratio" to jsonNumber(charge.ratio),
                "used" to budgetTotalsJson(charge.ledger.used),
                "cap" to budgetTotalsJson(charge.ledger.cap),
            ),
        ),
    )

    fun recordLegal(verdict: LegalVerdict, actor: String): GovernanceAuditEntry {
        val freeze = verdict.applied.freeze
        val downgrade = verdict.applied.downgrade
        return record(
            GovernanceAuditRecord(
                category = GovernanceAuditCategory.LEGAL,
                action = "legal.${verdict.action.wire}",
                actor = actor,
                subject = freeze?.id ?: downgrade?.id ?: "legal-gate",
                detail = verdict.violations.map { it.code.wire }.joinToString(", ").ifEmpty { "no violation" },
                data = jsonObj(
                    "action" to jsonText(verdict.action.wire),
                    "violations" to jsonNumber(verdict.violations.size),
                    "freeze" to (freeze?.id?.let { jsonText(it) } ?: JsonValue.Null),
                    "breaker" to jsonBool(verdict.applied.breaker?.active == true),
                ),
            ),
        )
    }

    fun recordWorkflow(state: WorkflowState, actor: String): GovernanceAuditEntry = record(
        GovernanceAuditRecord(
            category = GovernanceAuditCategory.WORKFLOW,
            action = "workflow.${state.status.wire}",
            actor = actor,
            subject = state.id,
            detail = "phase=${state.phase.wire} steps=${state.completedSteps.size}",
            data = jsonObj(
                "id" to jsonText(state.id),
                "phase" to jsonText(state.phase.wire),
                "status" to jsonText(state.status.wire),
                "approvals" to jsonNumber(state.approvals.size),
            ),
        ),
    )

    fun recordSwitch(
        credential: SwitchCredential,
        action: SwitchAuditAction,
        actor: String,
    ): GovernanceAuditEntry = record(
        GovernanceAuditRecord(
            category = GovernanceAuditCategory.SWITCH,
            action = "switch.${action.wire}",
            actor = actor,
            subject = credential.id,
            detail = "${credential.from.wire}->${credential.to.wire}",
            refs = listOf(credential.digest),
            data = jsonObj(
                "to" to jsonText(credential.to.wire),
                "scenario" to jsonText(credential.scenario.wire),
                "manifest" to jsonObj(
                    "id" to jsonText(credential.manifest.id),
                    "version" to jsonNumber(credential.manifest.version),
                ),
            ),
        ),
    )

    /**
     * One settled runtime turn: the manifest version, tokens, charge code,
     * control decision and receipt id. Legal violation codes are appended when
     * a tool call was gated.
     */
    fun recordTurn(input: TurnAuditInput): GovernanceAuditEntry {
        val refs = arrayListOf(input.manifestId, input.manifestDigest)
        input.receiptId?.let { refs += it }
        return record(
            GovernanceAuditRecord(
                category = GovernanceAuditCategory.RUNTIME,
                action = "runtime.turn",
                actor = input.perspective,
                subject = input.turnId,
                detail = "tokens=${input.tokens} charge=${input.charge.code.wire} control=${input.control.action}",
                refs = refs,
                data = jsonObj(
                    "manifest" to jsonObj(
                        "id" to jsonText(input.manifestId),
                        "version" to jsonNumber(input.manifestVersion),
                    ),
                    "tokens" to jsonNumber(input.tokens),
                    "charge" to jsonText(input.charge.code.wire),
                    "control" to input.control.toJson(),
                    "legal" to input.legalCodes?.let { codes ->
                        jsonArr(*codes.map { jsonText(it) }.toTypedArray())
                    },
                ),
            ),
        )
    }
}

/** The credential actions the audit chain distinguishes. */
enum class CredentialAuditAction(val wire: String) {
    ISSUED("issued"),
    REVOKED("revoked"),
}

/** The switch actions the audit chain distinguishes. */
enum class SwitchAuditAction(val wire: String) {
    PEER("peer"),
    DOWNGRADE("downgrade"),
    ESCALATION("escalation"),
}

/** The control decision attached to a settled turn. */
data class ControlSummary(
    val action: String,
    val stop: Boolean,
    val escalate: Boolean,
    val approvalRequired: Boolean,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "action" to jsonText(action),
        "stop" to jsonBool(stop),
        "escalate" to jsonBool(escalate),
        "approvalRequired" to jsonBool(approvalRequired),
    )
}

/** The runtime turn the audit records. */
data class TurnAuditInput(
    val turnId: String,
    val perspective: String,
    val manifestId: String,
    val manifestVersion: Int,
    val manifestDigest: Digest,
    val tokens: Int,
    val charge: ChargeResult,
    val control: ControlSummary,
    val receiptId: String? = null,
    val legalCodes: List<String>? = null,
)

private fun checkpointDigest(count: Int, tip: Digest, at: Timestamp): Digest =
    CanonicalJson.digest(jsonObj("count" to jsonNumber(count), "tip" to jsonText(tip), "at" to jsonText(at)))

private fun budgetTotalsJson(totals: BudgetTotals): JsonValue.Obj = jsonObj(
    "tokens" to jsonNumber(totals.tokens),
    "ms" to jsonNumber(totals.ms),
    "calls" to jsonNumber(totals.calls),
    "interrupts" to jsonNumber(totals.interrupts),
    "money" to jsonNumber(totals.money),
)
