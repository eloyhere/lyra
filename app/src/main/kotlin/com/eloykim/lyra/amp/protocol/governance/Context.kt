package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.nowTimestamp

/**
 * The three-layer context (spec section 3), a faithful port of
 * `src/governance/context.ts`.
 *
 *   - global core: goal, signed capability statement, hard constraints,
 *     priorities, budget, task state, versioned board strategy, scenario and
 *     mode labels. Every perspective reads it; only the board or a human
 *     writes it.
 *   - perspective-private context: the manifest, local reasoning, tool results
 *     and private budget. Only that perspective may read it, and switching
 *     seals it.
 *   - shared receipt blackboard: conclusions, evidence refs, confidence,
 *     budget spent, manifest version, scenario/mode labels, open questions and
 *     escalation requests. Each perspective writes only its own receipts.
 *
 * The default inheritance is the board strategy summary, not the whole board
 * context; [ContextLayers.expandGlobal] opens the full record on demand.
 */

/** The versioned board strategy, split into inheritable summary and detail. */
data class StrategyView(
    val version: Int,
    val digest: Digest,
    /** What a perspective inherits by default. */
    val summary: String,
    /** The full board record, expanded only on demand. */
    val detail: String,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "version" to jsonNumber(version),
        "digest" to jsonText(digest),
        "summary" to jsonText(summary),
        "detail" to jsonText(detail),
    )

    companion object {
        fun fromJson(value: JsonValue): StrategyView {
            val obj = value.asObject("strategy-view")
            return StrategyView(
                version = obj.requireInt("version"),
                digest = obj.requireString("digest"),
                summary = obj.requireString("summary"),
                detail = obj.requireString("detail"),
            )
        }
    }
}

/** The writable global core. */
data class GlobalCore(
    val goal: String,
    val strategy: StrategyView,
    val constraints: List<String>,
    val priorities: List<String>,
    val taskState: String,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val budget: Budget,
    val version: Int,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "goal" to jsonText(goal),
        "strategy" to strategy.toJson(),
        "constraints" to jsonArr(*constraints.map { jsonText(it) }.toTypedArray()),
        "priorities" to jsonArr(*priorities.map { jsonText(it) }.toTypedArray()),
        "taskState" to jsonText(taskState),
        "scenario" to jsonText(scenario.wire),
        "modes" to modes.toJson(),
        "budget" to budget.toJson(),
        "version" to jsonNumber(version),
    )

    companion object {
        fun fromJson(value: JsonValue): GlobalCore {
            val obj = value.asObject("global-core")
            return GlobalCore(
                goal = obj.requireString("goal"),
                strategy = StrategyView.fromJson(obj.requireObj("strategy")),
                constraints = strings(obj.requireArr("constraints")),
                priorities = strings(obj.requireArr("priorities")),
                taskState = obj.requireString("taskState"),
                scenario = ScenarioId.fromWire(obj.requireString("scenario")),
                modes = ModeWeights.fromJson(obj.requireObj("modes")),
                budget = Budget.fromJson(obj.requireObj("budget")),
                version = obj.requireInt("version"),
            )
        }
    }
}

/**
 * The read-only projection a perspective receives by default.
 */
data class GlobalCoreView(
    val goal: String,
    val strategyVersion: Int,
    val strategyDigest: Digest,
    val strategySummary: String,
    val constraints: List<String>,
    val priorities: List<String>,
    val taskState: String,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val budget: Budget,
    val version: Int,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "goal" to jsonText(goal),
        "strategyVersion" to jsonNumber(strategyVersion),
        "strategyDigest" to jsonText(strategyDigest),
        "strategySummary" to jsonText(strategySummary),
        "constraints" to jsonArr(*constraints.map { jsonText(it) }.toTypedArray()),
        "priorities" to jsonArr(*priorities.map { jsonText(it) }.toTypedArray()),
        "taskState" to jsonText(taskState),
        "scenario" to jsonText(scenario.wire),
        "modes" to modes.toJson(),
        "budget" to budget.toJson(),
        "version" to jsonNumber(version),
    )
}

/**
 * The patch the board or a human may apply to the global core. Absent fields
 * keep the current value, mirroring `Partial<GlobalCore>`.
 */
data class GlobalCorePatch(
    val goal: String? = null,
    val strategy: StrategyView? = null,
    val constraints: List<String>? = null,
    val priorities: List<String>? = null,
    val taskState: String? = null,
    val scenario: ScenarioId? = null,
    val modes: ModeWeights? = null,
    val budget: Budget? = null,
)

/** A private note's kind; the wire spells `tool-result` with a hyphen. */
enum class PrivateNoteKind(val wire: String) {
    REASONING("reasoning"),
    TOOL_RESULT("tool-result"),
    OBSERVATION("observation");

    companion object {
        fun fromWire(value: String): PrivateNoteKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 private note kind: $value")
    }
}

data class PrivateNote(
    val at: Timestamp,
    val kind: PrivateNoteKind,
    val text: String,
    val refs: List<Ref>? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "at" to jsonText(at),
        "kind" to jsonText(kind.wire),
        "text" to jsonText(text),
        "refs" to refs?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
    )

    companion object {
        fun fromJson(value: JsonValue): PrivateNote {
            val obj = value.asObject("private-note")
            return PrivateNote(
                at = obj.requireString("at"),
                kind = PrivateNoteKind.fromWire(obj.requireString("kind")),
                text = obj.requireString("text"),
                refs = obj.arr("refs")?.items?.map { ref -> Ref.fromJson(ref) },
            )
        }
    }
}

data class PrivateContext(
    val perspective: PerspectiveId,
    val manifest: ManifestRef,
    val notes: List<PrivateNote>,
    val openedAt: Timestamp,
    val sealed: Boolean,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "perspective" to jsonText(perspective.wire),
        "manifest" to jsonObj(
            "id" to jsonText(manifest.id),
            "version" to jsonNumber(manifest.version),
        ),
        "notes" to jsonArr(*notes.map { it.toJson() }.toTypedArray()),
        "openedAt" to jsonText(openedAt),
        "sealed" to jsonBool(sealed),
    )
}

/** A receipt before the context writes its id, timestamp and digest. */
data class BoardReceiptInput(
    val conclusion: String,
    val evidence: List<Ref>,
    val confidence: Double,
    val budget: Budget,
    val manifest: ManifestRef,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val openQuestions: List<String>? = null,
    val escalation: EscalationRef? = null,
    val audience: List<PerspectiveId>? = null,
)

/**
 * One blackboard receipt. `openQuestions` is always a list after write, as in
 * the reference's `BoardReceipt extends BoardReceiptInput` with a required
 * `openQuestions`.
 */
data class BoardReceipt(
    val id: Id,
    val from: PerspectiveId,
    val at: Timestamp,
    val conclusion: String,
    val evidence: List<Ref>,
    val confidence: Double,
    val budget: Budget,
    val manifest: ManifestRef,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val openQuestions: List<String> = emptyList(),
    val escalation: EscalationRef? = null,
    val audience: List<PerspectiveId>? = null,
    val digest: Digest,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "from" to jsonText(from.wire),
        "at" to jsonText(at),
        "conclusion" to jsonText(conclusion),
        "evidence" to jsonArr(*evidence.map { it.toJson() }.toTypedArray()),
        "confidence" to jsonNumber(confidence),
        "budget" to budget.toJson(),
        "manifest" to jsonObj("id" to jsonText(manifest.id), "version" to jsonNumber(manifest.version)),
        "scenario" to jsonText(scenario.wire),
        "modes" to modes.toJson(),
        "openQuestions" to jsonArr(*openQuestions.map { jsonText(it) }.toTypedArray()),
        "escalation" to escalation?.let {
            jsonObj("to" to jsonText(it.to.wire), "reason" to jsonText(it.reason))
        },
        "audience" to audience?.let { list -> jsonArr(*list.map { jsonText(it.wire) }.toTypedArray()) },
        "digest" to jsonText(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): BoardReceipt {
            val obj = value.asObject("board-receipt")
            return BoardReceipt(
                id = obj.requireString("id"),
                from = PerspectiveId.fromWire(obj.requireString("from")),
                at = obj.requireString("at"),
                conclusion = obj.requireString("conclusion"),
                evidence = obj.requireArr("evidence").items.map { ref -> Ref.fromJson(ref) },
                confidence = obj.double("confidence")
                    ?: throw IllegalArgumentException("缺少数字字段 confidence"),
                budget = Budget.fromJson(obj.requireObj("budget")),
                manifest = obj.requireObj("manifest").let {
                    ManifestRef(id = it.requireString("id"), version = it.requireInt("version"))
                },
                scenario = ScenarioId.fromWire(obj.requireString("scenario")),
                modes = ModeWeights.fromJson(obj.requireObj("modes")),
                openQuestions = obj.arr("openQuestions")?.let { strings(it) } ?: emptyList(),
                escalation = obj.obj("escalation")?.let {
                    EscalationRef(
                        to = PerspectiveId.fromWire(it.requireString("to")),
                        reason = it.requireString("reason"),
                    )
                },
                audience = obj.arr("audience")?.items?.map {
                    PerspectiveId.fromWire((it as JsonValue.Str).value)
                },
                digest = obj.requireString("digest"),
            )
        }
    }
}

/** One row of [ContextSnapshot], stripped of private note bodies. */
data class PrivateContextSummary(
    val perspective: PerspectiveId,
    val notes: Int,
    val sealed: Boolean,
)

data class ContextSnapshot(
    val core: GlobalCore,
    val privateContexts: List<PrivateContextSummary>,
    val receipts: List<BoardReceipt>,
    val version: Int,
)

/** Perspectives that may read every receipt. */
private val ReadersOfEverything: List<PerspectiveId> = listOf(
    PerspectiveId.GENERAL_MANAGER,
    PerspectiveId.BOARD,
    PerspectiveId.SUPERVISORY,
    PerspectiveId.LEGAL,
)

/** The human writer is not a perspective; it is named explicitly. */
const val HUMAN_WRITER: String = "human"

/**
 * The three context layers.
 */
class ContextLayers(
    core: GlobalCore,
    private val now: () -> Timestamp = { nowTimestamp() },
) {

    private var core: GlobalCore = copyCore(core)
    private val privateContexts = LinkedHashMap<PerspectiveId, PrivateContext>()
    private val receipts = ArrayList<BoardReceipt>()
    private var active: PerspectiveId? = null
    private var counter = 0

    /**
     * The default view: summary only, no full board record.
     */
    fun global(viewer: PerspectiveId): GlobalCoreView {
        perspective(viewer)
        return GlobalCoreView(
            goal = core.goal,
            strategyVersion = core.strategy.version,
            strategyDigest = core.strategy.digest,
            strategySummary = core.strategy.summary,
            constraints = core.constraints.toList(),
            priorities = core.priorities.toList(),
            taskState = core.taskState,
            scenario = core.scenario,
            modes = core.modes,
            budget = core.budget,
            version = core.version,
        )
    }

    /**
     * Expand the full board record on demand. The access is not hidden: the
     * caller records it in its own receipt.
     */
    fun expandGlobal(viewer: PerspectiveId): GlobalCore {
        perspective(viewer)
        return copyCore(core)
    }

    /**
     * Only the board or a human may write the global core. The change bumps
     * the version so every perspective can detect that its assumption is stale.
     */
    fun writeGlobal(by: String, patch: GlobalCorePatch, approval: ApprovalGrant? = null): GlobalCore {
        val isHuman = by == HUMAN_WRITER
        if (!isHuman && by != PerspectiveId.BOARD.wire) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "$by may not write the global core")
        }
        if (!isHuman && approval != null &&
            (approval.granted != true ||
                approval.subject != ApprovalSubject.BOARD_WRITE ||
                approval.by != PerspectiveId.BOARD)
        ) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "the board write approval is not valid")
        }
        core = copyCore(
            GlobalCore(
                goal = patch.goal ?: core.goal,
                strategy = patch.strategy ?: core.strategy,
                constraints = patch.constraints ?: core.constraints,
                priorities = patch.priorities ?: core.priorities,
                taskState = patch.taskState ?: core.taskState,
                scenario = patch.scenario ?: core.scenario,
                modes = patch.modes ?: core.modes,
                budget = patch.budget ?: core.budget,
                version = core.version + 1,
            ),
        )
        return copyCore(core)
    }

    /**
     * Open a perspective's private context and seal the previously active one.
     * The caller cannot keep reading the old view through this object.
     */
    fun openPerspective(id: PerspectiveId, manifest: ManifestRef): PrivateContext {
        val previous = active
        if (previous != null && previous != id) {
            seal(previous)
        }
        active = id
        val existing = privateContexts[id]
        if (existing != null) {
            val reopened = existing.copy(manifest = manifest.copy(), sealed = false)
            privateContexts[id] = reopened
            return copyPrivate(reopened)
        }
        val context = PrivateContext(
            perspective = id,
            manifest = manifest.copy(),
            notes = emptyList(),
            openedAt = now(),
            sealed = false,
        )
        privateContexts[id] = context
        return copyPrivate(context)
    }

    fun seal(id: PerspectiveId? = null) {
        if (id == null) {
            return
        }
        val context = privateContexts[id]
        if (context != null) {
            privateContexts[id] = context.copy(sealed = true)
        }
        if (active == id) {
            active = null
        }
    }

    /**
     * Append a private note. Only the owner of an open context may write it.
     */
    fun writePrivate(id: PerspectiveId, note: PrivateNote): PrivateContext {
        val context = privateContexts[id]
        if (context == null || context.sealed) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "${id.wire} has no open private context")
        }
        if (active != id) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "${id.wire} is not the active perspective")
        }
        val next = context.copy(notes = context.notes + note.copy(refs = note.refs?.map { it.copy() }))
        privateContexts[id] = next
        return copyPrivate(next)
    }

    /**
     * Read a private context. Only the owner may read it, sealed or not.
     */
    fun readPrivate(id: PerspectiveId, viewer: PerspectiveId): PrivateContext? {
        if (id != viewer) {
            throw GovernanceError(
                GovernanceCode.CONTEXT_DENIED,
                "${viewer.wire} may not read ${id.wire}'s private context",
            )
        }
        return privateContexts[id]?.let { copyPrivate(it) }
    }

    /**
     * Write one receipt to the shared blackboard. The writer must be the active
     * perspective and the receipt must match its manifest version.
     */
    fun writeReceipt(from: PerspectiveId, input: BoardReceiptInput): BoardReceipt {
        if (active != from) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "${from.wire} is not the active perspective")
        }
        if (input.conclusion.trim().isEmpty()) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "a receipt needs a conclusion")
        }
        if (!input.confidence.isFinite() || input.confidence < 0.0 || input.confidence > 1.0) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "confidence must be in [0, 1]")
        }
        val open = privateContexts[from]
        if (open != null &&
            (open.manifest.id != input.manifest.id || open.manifest.version != input.manifest.version)
        ) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_VERSION_CONFLICT,
                "receipt cites ${input.manifest.id} v${input.manifest.version}",
            )
        }
        counter += 1
        val unsigned = BoardReceipt(
            id = "rcp.$counter",
            from = from,
            at = now(),
            conclusion = input.conclusion,
            evidence = input.evidence.map { it.copy() },
            confidence = input.confidence,
            budget = input.budget,
            manifest = input.manifest.copy(),
            scenario = input.scenario,
            modes = input.modes,
            openQuestions = input.openQuestions?.toList() ?: emptyList(),
            escalation = input.escalation?.copy(),
            audience = input.audience?.toList(),
            digest = "",
        )
        val receipt = unsigned.copy(digest = CanonicalJson.digest(unsigned.toJson().without("digest")))
        receipts += receipt
        return copyReceipt(receipt)
    }

    /**
     * The blackboard is not public: the management and audit perspectives see
     * everything, execution perspectives see their own receipts and receipts
     * addressed to them.
     */
    fun readReceipts(viewer: PerspectiveId): List<BoardReceipt> {
        val all = ReadersOfEverything.contains(viewer)
        return receipts
            .filter { receipt ->
                all || receipt.from == viewer || audienceOf(receipt).contains(viewer)
            }
            .map { copyReceipt(it) }
    }

    fun readReceipt(id: Id, viewer: PerspectiveId): BoardReceipt? {
        val receipt = receipts.firstOrNull { it.id == id } ?: return null
        val all = ReadersOfEverything.contains(viewer)
        if (!all && receipt.from != viewer && !audienceOf(receipt).contains(viewer)) {
            throw GovernanceError(GovernanceCode.CONTEXT_DENIED, "${viewer.wire} may not read $id")
        }
        return copyReceipt(receipt)
    }

    /**
     * Receipts that requested escalation, used by the workflow's escalation
     * phase.
     */
    fun escalations(): List<BoardReceipt> = receipts.filter { it.escalation != null }.map { copyReceipt(it) }

    fun snapshot(): ContextSnapshot = ContextSnapshot(
        core = copyCore(core),
        privateContexts = privateContexts.values.map {
            PrivateContextSummary(perspective = it.perspective, notes = it.notes.size, sealed = it.sealed)
        },
        receipts = receipts.map { copyReceipt(it) },
        version = core.version,
    )

    private fun audienceOf(receipt: BoardReceipt): List<PerspectiveId> = receipt.audience ?: emptyList()
}

private fun copyCore(core: GlobalCore): GlobalCore = core.copy(
    strategy = core.strategy.copy(),
    constraints = core.constraints.toList(),
    priorities = core.priorities.toList(),
)

private fun copyReceipt(receipt: BoardReceipt): BoardReceipt = receipt.copy(
    evidence = receipt.evidence.map { it.copy() },
    openQuestions = receipt.openQuestions.toList(),
    escalation = receipt.escalation?.copy(),
    audience = receipt.audience?.toList(),
)

private fun copyPrivate(context: PrivateContext): PrivateContext = context.copy(
    manifest = context.manifest.copy(),
    notes = context.notes.map { note -> note.copy(refs = note.refs?.map { it.copy() }) },
)

private fun strings(array: JsonValue.Arr): List<String> = array.items.map { value ->
    (value as? JsonValue.Str)?.value ?: throw IllegalArgumentException("期望字符串数组")
}
