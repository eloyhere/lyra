package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The attention manifest (spec section 2), a faithful port of
 * `src/governance/attentionManifest.ts`.
 *
 * The signed declaration of where attention goes, how deep, how much it may cost and
 * when to stop. Lifecycle: derive -> issue -> load -> reassess ignored items -> update
 * or re-derive -> expire. The manifest never carries content, only refs, and it never
 * replaces a switch credential: the manifest says what to look at, the credential says
 * what may be looked at.
 *
 * Governance sign-offs ([Signoff]) are department approvals of a digest, not
 * cryptographic signatures; the optional cryptographic layer lives in `Signing.kt` and
 * binds a signature to `{signer, subject, subjectId, subjectVersion, digest}` through
 * [signAttentionManifest] / [verifyAttentionManifestSignatures].
 */

/** What a focus item points at. */
enum class FocusCategory(val wire: String) {
    GOAL("goal"),
    SUBGOAL("subgoal"),
    EVIDENCE("evidence"),
    TOOL("tool"),
    MEMORY("memory");

    companion object {
        fun fromWire(value: String): FocusCategory =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 focus category: $value")
    }
}

/**
 * What a manifest focuses on. `depth` is 1 (summary), 2 (standard) or 3 (detail); a
 * focus item may expire before the manifest does.
 *
 * The reference validates priority and depth when the focus list is de-duplicated, not
 * when an item is constructed, so [deriveAttentionManifest] and the revision path keep
 * the same validation points.
 */
data class FocusItem(
    val target: Ref,
    val category: FocusCategory,
    val priority: Double,
    val depth: Int,
    val ttlFrames: Int? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "category" to JsonValue.Str(category.wire),
        "priority" to jsonNumber(priority),
        "depth" to jsonNumber(depth),
        "ttlFrames" to ttlFrames?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): FocusItem {
            val obj = value.asGovernanceObject("focus item")
            return FocusItem(
                target = Ref.fromJson(obj.requireObj("target")),
                category = FocusCategory.fromWire(obj.requireString("category")),
                priority = obj.double("priority") ?: throw IllegalArgumentException("缺少数字字段 priority"),
                depth = obj.requireInt("depth"),
                ttlFrames = obj.int("ttlFrames"),
            )
        }
    }
}

/**
 * Deliberate exclusion with an explicit revisit condition and cadence.
 */
data class IgnoreItem(
    val target: Ref,
    val reason: String,
    /** A named signal; when it fires and the cadence is due, the item returns. */
    val revisitIf: String,
    val everyFrames: Int,
    val lastFrame: Int? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "reason" to JsonValue.Str(reason),
        "revisitIf" to JsonValue.Str(revisitIf),
        "everyFrames" to jsonNumber(everyFrames),
        "lastFrame" to lastFrame?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): IgnoreItem {
            val obj = value.asGovernanceObject("ignore item")
            return IgnoreItem(
                target = Ref.fromJson(obj.requireObj("target")),
                reason = obj.requireString("reason"),
                revisitIf = obj.requireString("revisitIf"),
                everyFrames = obj.requireInt("everyFrames"),
                lastFrame = obj.int("lastFrame"),
            )
        }
    }
}

/**
 * Which ledger funds the manifest, and how far it may flex.
 */
data class BudgetBinding(
    val scope: LedgerScope,
    val perspective: PerspectiveId? = null,
    /** Required when scope is `agent`; the agent's own ledger. */
    val agent: String? = null,
    val cap: Budget,
    val elasticity: Elasticity,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "scope" to JsonValue.Str(scope.wire),
        "perspective" to perspective?.let { JsonValue.Str(it.wire) },
        "agent" to agent?.let { JsonValue.Str(it) },
        "cap" to cap.toJson(),
        "elasticity" to JsonValue.Str(elasticity.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): BudgetBinding {
            val obj = value.asGovernanceObject("budget binding")
            return BudgetBinding(
                scope = ledgerScopeFromWire(obj.requireString("scope")),
                perspective = obj.string("perspective")?.let(PerspectiveId::fromWire),
                agent = obj.string("agent"),
                cap = Budget.fromJson(obj.requireObj("cap")),
                elasticity = Elasticity.fromWire(obj.requireString("elasticity")),
            )
        }
    }
}

/** A condition that must escalate to another perspective or to a human. */
sealed interface EscalationTarget {

    fun toWire(): String

    data class ToPerspective(val perspective: PerspectiveId) : EscalationTarget {
        override fun toWire(): String = perspective.wire
    }

    data object Human : EscalationTarget {
        override fun toWire(): String = "human"
    }

    companion object {
        fun fromWire(value: String): EscalationTarget =
            if (value == "human") Human else ToPerspective(PerspectiveId.fromWire(value))
    }
}

data class EscalationTrigger(
    val condition: String,
    val sensitivity: Sensitivity,
    val to: EscalationTarget,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "condition" to JsonValue.Str(condition),
        "sensitivity" to JsonValue.Str(sensitivity.wire),
        "to" to JsonValue.Str(to.toWire()),
    )

    companion object {
        fun fromJson(value: JsonValue): EscalationTrigger {
            val obj = value.asGovernanceObject("escalation trigger")
            return EscalationTrigger(
                condition = obj.requireString("condition"),
                sensitivity = Sensitivity.fromWire(obj.requireString("sensitivity")),
                to = EscalationTarget.fromWire(obj.requireString("to")),
            )
        }
    }
}

/**
 * Where the manifest came from. The board strategy digest is mandatory so a stale
 * manifest is detectable.
 */
data class ManifestDerivation(
    val strategyVersion: Int,
    val strategyDigest: Digest,
    val derivedAt: Timestamp,
    val note: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "strategyVersion" to jsonNumber(strategyVersion),
        "strategyDigest" to JsonValue.Str(strategyDigest),
        "derivedAt" to JsonValue.Str(derivedAt),
        "note" to note?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ManifestDerivation {
            val obj = value.asGovernanceObject("manifest derivation")
            return ManifestDerivation(
                strategyVersion = obj.requireInt("strategyVersion"),
                strategyDigest = obj.requireString("strategyDigest"),
                derivedAt = obj.requireString("derivedAt"),
                note = obj.string("note"),
            )
        }
    }
}

/**
 * A manifest before derivation. It is not a wire object; [deriveAttentionManifest]
 * resolves the matrix row and [issueAttentionManifest] seals the result.
 */
data class AttentionManifestDraft(
    val id: Id,
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    /** Optional override; defaults to the scenario's matrix row. */
    val modes: Map<ModeId, Double>? = null,
    val focus: List<FocusItem>,
    val ignore: List<IgnoreItem> = emptyList(),
    val priorityWeights: Map<String, Double> = emptyMap(),
    val budget: BudgetBinding,
    /** A caller may raise the matrix floor, never lower it. */
    val evidenceFloor: EvidenceFloor? = null,
    val escalations: List<EscalationTrigger>? = null,
    val constraints: BehaviorConstraints,
    val derivation: ManifestDerivation,
    val issuedAt: Timestamp,
    val ttlMs: Long? = null,
    val note: String? = null,
)

/**
 * The derived, unsigned manifest. Issuing it adds version, expiry, digest and
 * counter-signatures.
 */
data class DerivedAttentionManifest(
    val draft: AttentionManifestDraft,
    val plan: ScenarioModePlan,
    val modes: ModeWeights,
    val focus: List<FocusItem>,
    val ignore: List<IgnoreItem>,
    val escalations: List<EscalationTrigger>,
    val evidenceFloor: EvidenceFloor,
    val requiredTier: Tier,
    val reassessEvery: Int,
    val elasticity: Elasticity,
    val sensitivity: Sensitivity,
    val rationale: List<String>,
)

/**
 * The manifest body without its seal; the digest covers exactly this shape.
 */
data class UnsignedAttentionManifest(
    val id: Id,
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val focus: List<FocusItem>,
    val ignore: List<IgnoreItem>,
    val priorityWeights: Map<String, Double>,
    val budget: BudgetBinding,
    val evidenceFloor: EvidenceFloor,
    val requiredTier: Tier,
    val reassessEvery: Int,
    val gateStrictness: GateStrictness,
    val elasticity: Elasticity,
    val sensitivity: Sensitivity,
    val escalations: List<EscalationTrigger>,
    val constraints: BehaviorConstraints,
    val derivation: ManifestDerivation,
    val issuedAt: Timestamp,
    val expires: Timestamp,
    val version: Int,
    val parent: Digest? = null,
    val note: String? = null,
    val kind: String = "attention-manifest",
) {

    init {
        require(kind == "attention-manifest") { "attention manifest.kind 必须是 attention-manifest" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "perspective" to JsonValue.Str(perspective.wire),
        "scenario" to JsonValue.Str(scenario.wire),
        "modes" to modes.toJson(),
        "focus" to jsonArr(*focus.map { it.toJson() }.toTypedArray()),
        "ignore" to jsonArr(*ignore.map { it.toJson() }.toTypedArray()),
        "priorityWeights" to JsonValue.Obj.fromMap(
            priorityWeights.mapValues { (_, value) -> jsonNumber(value) },
        ),
        "budget" to budget.toJson(),
        "evidenceFloor" to JsonValue.Str(evidenceFloor.wire),
        "requiredTier" to JsonValue.Str(requiredTier.wire),
        "reassessEvery" to jsonNumber(reassessEvery),
        "gateStrictness" to JsonValue.Str(gateStrictness.wire),
        "elasticity" to JsonValue.Str(elasticity.wire),
        "sensitivity" to JsonValue.Str(sensitivity.wire),
        "escalations" to jsonArr(*escalations.map { it.toJson() }.toTypedArray()),
        "constraints" to behaviorConstraintsToJson(constraints),
        "derivation" to derivation.toJson(),
        "issuedAt" to JsonValue.Str(issuedAt),
        "expires" to JsonValue.Str(expires),
        "version" to jsonNumber(version),
        "parent" to parent?.let { JsonValue.Str(it) },
        "note" to note?.let { JsonValue.Str(it) },
    )

    fun toManifest(digest: Digest, signatures: List<Signoff>): AttentionManifest = AttentionManifest(
        id = id,
        perspective = perspective,
        scenario = scenario,
        modes = modes,
        focus = focus,
        ignore = ignore,
        priorityWeights = priorityWeights,
        budget = budget,
        evidenceFloor = evidenceFloor,
        requiredTier = requiredTier,
        reassessEvery = reassessEvery,
        gateStrictness = gateStrictness,
        elasticity = elasticity,
        sensitivity = sensitivity,
        escalations = escalations,
        constraints = constraints,
        derivation = derivation,
        issuedAt = issuedAt,
        expires = expires,
        version = version,
        parent = parent,
        note = note,
        kind = kind,
        digest = digest,
        signatures = signatures,
    )

    companion object {
        fun fromJson(value: JsonValue): UnsignedAttentionManifest {
            val obj = value.asGovernanceObject("attention manifest")
            return UnsignedAttentionManifest(
                id = obj.requireString("id"),
                perspective = PerspectiveId.fromWire(obj.requireString("perspective")),
                scenario = ScenarioId.fromWire(obj.requireString("scenario")),
                modes = ModeWeights.fromJson(obj.requireObj("modes")),
                focus = obj.requireArr("focus").items.map(FocusItem::fromJson),
                ignore = obj.arr("ignore")?.items?.map(IgnoreItem::fromJson) ?: emptyList(),
                priorityWeights = parsePriorityWeights(obj),
                budget = BudgetBinding.fromJson(obj.requireObj("budget")),
                evidenceFloor = EvidenceFloor.fromWire(obj.requireString("evidenceFloor")),
                requiredTier = Tier.fromWire(obj.requireString("requiredTier")),
                reassessEvery = obj.requireInt("reassessEvery"),
                gateStrictness = gateStrictnessFromWire(obj.requireString("gateStrictness")),
                elasticity = Elasticity.fromWire(obj.requireString("elasticity")),
                sensitivity = Sensitivity.fromWire(obj.requireString("sensitivity")),
                escalations = obj.requireArr("escalations").items.map(EscalationTrigger::fromJson),
                constraints = behaviorConstraintsFromJson(obj.requireObj("constraints")),
                derivation = ManifestDerivation.fromJson(obj.requireObj("derivation")),
                issuedAt = obj.requireString("issuedAt"),
                expires = obj.requireString("expires"),
                version = obj.requireInt("version"),
                parent = obj.string("parent"),
                note = obj.string("note"),
                kind = obj.string("kind") ?: "attention-manifest",
            )
        }
    }
}

/**
 * The issued attention manifest: the body plus its digest and department sign-offs.
 */
data class AttentionManifest(
    val id: Id,
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val focus: List<FocusItem>,
    val ignore: List<IgnoreItem>,
    val priorityWeights: Map<String, Double>,
    val budget: BudgetBinding,
    val evidenceFloor: EvidenceFloor,
    val requiredTier: Tier,
    val reassessEvery: Int,
    val gateStrictness: GateStrictness,
    val elasticity: Elasticity,
    val sensitivity: Sensitivity,
    val escalations: List<EscalationTrigger>,
    val constraints: BehaviorConstraints,
    val derivation: ManifestDerivation,
    val issuedAt: Timestamp,
    val expires: Timestamp,
    val version: Int,
    val digest: Digest,
    val signatures: List<Signoff>,
    val parent: Digest? = null,
    val note: String? = null,
    val kind: String = "attention-manifest",
) {

    init {
        require(kind == "attention-manifest") { "attention manifest.kind 必须是 attention-manifest" }
        require(digest.isNotBlank()) { "attention manifest.digest 不能为空" }
    }

    fun unsigned(): UnsignedAttentionManifest = UnsignedAttentionManifest(
        id = id,
        perspective = perspective,
        scenario = scenario,
        modes = modes,
        focus = focus,
        ignore = ignore,
        priorityWeights = priorityWeights,
        budget = budget,
        evidenceFloor = evidenceFloor,
        requiredTier = requiredTier,
        reassessEvery = reassessEvery,
        gateStrictness = gateStrictness,
        elasticity = elasticity,
        sensitivity = sensitivity,
        escalations = escalations,
        constraints = constraints,
        derivation = derivation,
        issuedAt = issuedAt,
        expires = expires,
        version = version,
        parent = parent,
        note = note,
        kind = kind,
    )

    fun toJson(): JsonValue.Obj = unsigned().toJson()
        .copyWith("digest", JsonValue.Str(digest))
        .copyWith("signatures", jsonArr(*signatures.map { it.toJson() }.toTypedArray()))

    companion object {
        fun fromJson(value: JsonValue): AttentionManifest {
            val obj = value.asGovernanceObject("attention manifest")
            val unsigned = UnsignedAttentionManifest.fromJson(obj)
            val digest = obj.requireString("digest")
            val signatures = obj.requireArr("signatures").items.map(::signoffFromJson)
            return unsigned.toManifest(digest, signatures)
        }
    }
}

/** A versioned manifest reference, safe to copy between layers. */
data class ManifestLineage(
    val version: Int,
    val parent: Digest,
)

data class LoadManifestRequest(
    val perspective: PerspectiveId,
    val at: Timestamp,
    val scenario: ScenarioId? = null,
)

data class LoadedManifest(
    val manifest: AttentionManifest,
    val focus: List<FocusItem>,
    val ignore: List<IgnoreItem>,
    val reason: String,
)

data class Reassessment(
    val due: List<IgnoreItem>,
    val promoted: List<IgnoreItem>,
    val retained: List<IgnoreItem>,
    /**
     * A cadence-only working copy. Its digest is stale until the owner signs a
     * revision through [promoteIgnored]; callers must not verify it.
     */
    val updated: AttentionManifest,
)

/** A re-derivation patch: a new scenario or a structural change, never a patch. */
data class RederivePatch(
    val scenario: ScenarioId? = null,
    val modes: Map<ModeId, Double>? = null,
    val focus: List<FocusItem>? = null,
    val ignore: List<IgnoreItem>? = null,
    val priorityWeights: Map<String, Double>? = null,
    val budget: BudgetBinding? = null,
    val evidenceFloor: EvidenceFloor? = null,
    val constraints: BehaviorConstraints? = null,
    val escalation: List<EscalationTrigger>? = null,
    val ttlMs: Long? = null,
    val note: String? = null,
)

/** An in-place manifest revision; a scenario change is not permitted here. */
data class ManifestPatch(
    val focus: List<FocusItem>? = null,
    val ignore: List<IgnoreItem>? = null,
    val priorityWeights: Map<String, Double>? = null,
    val modes: Map<ModeId, Double>? = null,
    val scenario: ScenarioId? = null,
    val budget: BudgetBinding? = null,
    val evidenceFloor: EvidenceFloor? = null,
    val constraints: BehaviorConstraints? = null,
)

data class ManifestUpdateApprovals(
    val modeWeights: ApprovalGrant? = null,
    val evidenceFloor: ApprovalGrant? = null,
    val budget: ApprovalGrant? = null,
    val constraints: ApprovalGrant? = null,
)

data class ManifestUpdateContext(
    val by: PerspectiveId,
    val at: Timestamp,
    val signoff: SignerInput,
    val approvals: ManifestUpdateApprovals? = null,
)

const val DefaultManifestTtlMs: Long = 12L * 60 * 60 * 1000
const val MaxManifestTtlMs: Long = 7L * 24 * 60 * 60 * 1000

private const val IgnoreCadenceCap: Int = 60

private val RequiresRole: Map<SignoffRole, PerspectiveId?> = mapOf(
    SignoffRole.ISSUER to PerspectiveId.GENERAL_MANAGER,
    SignoffRole.LEGAL to PerspectiveId.LEGAL,
    SignoffRole.FINANCE to PerspectiveId.FINANCE,
    SignoffRole.BOARD to PerspectiveId.BOARD,
    SignoffRole.HUMAN to null,
)

/**
 * Body used for digests: everything except the digest and the signatures.
 */
fun manifestDigest(manifest: UnsignedAttentionManifest): Digest = CanonicalJson.digest(manifest.toJson())

fun manifestDigest(manifest: AttentionManifest): Digest = CanonicalJson.digest(manifest.unsigned().toJson())

/**
 * Derive the matrix-consistent fields. This is the only place that decides the
 * evidence floor, revisit cadence, gate strictness and elasticity cap.
 */
fun deriveAttentionManifest(draft: AttentionManifestDraft): DerivedAttentionManifest {
    if (draft.id.isEmpty()) {
        throw GovernanceError(GovernanceCode.MANIFEST_SCOPE, "a manifest needs an id")
    }
    perspective(draft.perspective)
    if (draft.focus.isEmpty()) {
        throw GovernanceError(GovernanceCode.MANIFEST_SCOPE, "a manifest needs at least one focus item")
    }
    if (draft.budget.scope == LedgerScope.RESERVE || draft.budget.scope == LedgerScope.GLOBAL) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "a manifest binds a perspective or agent ledger, not the reserve or global ledger",
        )
    }
    if (draft.budget.scope == LedgerScope.PERSPECTIVE && draft.budget.perspective == null) {
        throw GovernanceError(GovernanceCode.MANIFEST_SCOPE, "a perspective budget binding needs a perspective")
    }
    if (draft.budget.scope == LedgerScope.PERSPECTIVE && draft.budget.perspective != draft.perspective) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "a perspective budget binding must match the manifest owner",
        )
    }
    if (draft.budget.scope == LedgerScope.AGENT && draft.budget.agent == null) {
        throw GovernanceError(GovernanceCode.MANIFEST_SCOPE, "an agent budget binding needs an agent id")
    }
    val plan = resolveScenarioModes(draft.scenario, draft.modes)
    val modes = plan.modes
    var evidenceFloor = strictestFloor(plan.evidenceFloor, draft.constraints.evidence)
    val rationale = ArrayList<String>()
    rationale += plan.rationale
    draft.evidenceFloor?.let { callerFloor ->
        if (GovernanceLimits.floorRank.getValue(callerFloor) > GovernanceLimits.floorRank.getValue(evidenceFloor)) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "the ${draft.scenario.wire} matrix requires at least ${evidenceFloor.wire}; " +
                    "a lower floor needs a legal waiver",
            )
        }
        evidenceFloor = strictestFloor(evidenceFloor, callerFloor)
        rationale += "caller floor ${callerFloor.wire} merged to ${evidenceFloor.wire}"
    }
    val elasticity = draft.budget.elasticity
    val capRank = elasticityRank(elasticity)
    val planRank = elasticityRank(plan.elasticity)
    val effectiveElasticity = if (capRank > planRank) plan.elasticity else elasticity
    if (capRank > planRank) {
        rationale += "elasticity ${elasticity.wire} clamped to ${plan.elasticity.wire} by the matrix"
    }
    val focus = dedupeFocus(draft.focus)
        .sortedByDescending { it.priority }
        .take(plan.focusLimit)
    if (draft.focus.size > focus.size) {
        rationale += "focus trimmed from ${draft.focus.size} to ${focus.size} by the ${plan.dominant.wire} mode"
    }
    val ignore = dedupeIgnore(draft.ignore, plan.revisitEvery)
    val triggers = draft.escalations ?: defaultEscalations(plan.sensitivity)
    val escalations = triggers.map { trigger ->
        EscalationTrigger(
            condition = trigger.condition,
            sensitivity = clampSensitivity(trigger.sensitivity, plan.sensitivity),
            to = trigger.to,
        )
    }
    val priorityWeights = LinkedHashMap<String, Double>()
    for ((key, value) in draft.priorityWeights) {
        if (!value.isFinite() || value < 0.0) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_SCOPE,
                "priority weight $key must be a non-negative number",
            )
        }
        priorityWeights[key] = value
    }
    return DerivedAttentionManifest(
        draft = draft,
        plan = plan,
        modes = modes,
        focus = focus,
        ignore = ignore,
        escalations = escalations,
        evidenceFloor = evidenceFloor,
        requiredTier = GovernanceLimits.floorTier.getValue(evidenceFloor),
        reassessEvery = plan.revisitEvery,
        elasticity = effectiveElasticity,
        sensitivity = plan.sensitivity,
        rationale = rationale,
    )
}

/**
 * Issue a manifest. The three sign-offs are the issuer (general manager), legal and
 * finance; all three must sign the same digest, and no perspective may sign its own
 * manifest except the general manager's own.
 */
fun issueAttentionManifest(
    derived: DerivedAttentionManifest,
    signers: List<SignerInput>,
    lineage: ManifestLineage? = null,
): AttentionManifest {
    val draft = derived.draft
    val ttlMs = minOf(MaxManifestTtlMs, maxOf(1_000L, draft.ttlMs ?: DefaultManifestTtlMs))
    val issuedMs = governanceMillis(draft.issuedAt)
        ?: throw GovernanceError(GovernanceCode.MANIFEST_SCOPE, "issuedAt must be a parseable timestamp")
    val unsigned = UnsignedAttentionManifest(
        id = draft.id,
        perspective = draft.perspective,
        scenario = draft.scenario,
        modes = derived.modes,
        focus = derived.focus,
        ignore = derived.ignore,
        priorityWeights = LinkedHashMap(draft.priorityWeights),
        budget = draft.budget,
        evidenceFloor = derived.evidenceFloor,
        requiredTier = derived.requiredTier,
        reassessEvery = derived.reassessEvery,
        gateStrictness = derived.plan.gateStrictness,
        elasticity = derived.elasticity,
        sensitivity = derived.sensitivity,
        escalations = derived.escalations.map { it.copy() },
        constraints = copyConstraints(draft.constraints),
        derivation = draft.derivation.copy(),
        issuedAt = draft.issuedAt,
        expires = isoMillisTimestamp(issuedMs + ttlMs),
        version = lineage?.version ?: 1,
        parent = lineage?.parent,
        note = draft.note,
    )
    val digest = manifestDigest(unsigned)
    val signatures = checkSignoffs(unsigned, digest, signers, draft.perspective)
    return unsigned.toManifest(digest, signatures)
}

/**
 * Convenience builder used by tests and examples: derive then issue.
 */
fun createAttentionManifest(draft: AttentionManifestDraft, signers: List<SignerInput>): AttentionManifest =
    issueAttentionManifest(deriveAttentionManifest(draft), signers)

/**
 * Re-derive a manifest for a new scenario or a structural change. This is not a patch:
 * the version increments, the previous digest becomes `parent`, and the three sign-offs
 * must be renewed for the new body (spec 2.3, 12.7).
 */
fun rederiveAttentionManifest(
    manifest: AttentionManifest,
    patch: RederivePatch,
    signers: List<SignerInput>,
    at: Timestamp,
): AttentionManifest {
    if (isExpired(manifest, at)) {
        throw GovernanceError(GovernanceCode.MANIFEST_EXPIRED, "${manifest.id} expired at ${manifest.expires}")
    }
    val draft = AttentionManifestDraft(
        id = manifest.id,
        perspective = manifest.perspective,
        scenario = patch.scenario ?: manifest.scenario,
        modes = patch.modes ?: manifest.modes.asModeMap(),
        focus = patch.focus ?: manifest.focus,
        ignore = patch.ignore ?: manifest.ignore,
        priorityWeights = patch.priorityWeights ?: manifest.priorityWeights,
        budget = patch.budget ?: manifest.budget,
        evidenceFloor = patch.evidenceFloor ?: manifest.evidenceFloor,
        escalations = patch.escalation ?: manifest.escalations,
        constraints = patch.constraints ?: manifest.constraints,
        derivation = manifest.derivation.copy(
            derivedAt = at,
            note = "re-derived from v${manifest.version}",
        ),
        issuedAt = at,
        ttlMs = patch.ttlMs,
        note = patch.note,
    )
    val derived = deriveAttentionManifest(draft)
    return issueAttentionManifest(derived, signers, ManifestLineage(manifest.version + 1, manifest.digest))
}

/**
 * Verify that the manifest was not edited after issue.
 */
fun verifyAttentionManifest(manifest: AttentionManifest, at: Timestamp? = null): VerificationVerdict {
    val digest = manifestDigest(manifest)
    if (digest != manifest.digest) {
        return VerificationVerdict.failure("manifest-tampered", "digest does not match the body")
    }
    val seen = LinkedHashSet<PerspectiveId>()
    for (signoff in manifest.signatures) {
        if (signoff.digest != digest) {
            return VerificationVerdict.failure(
                GovernanceCode.MANIFEST_UNSIGNED.wire,
                "${signoff.role.wire} signed a different digest",
            )
        }
        val required = RequiresRole[signoff.role]
        if (manifest.version == 1) {
            if (required != null && signoff.by != required) {
                return VerificationVerdict.failure(
                    GovernanceCode.MANIFEST_UNSIGNED.wire,
                    "${signoff.role.wire} was signed by ${signoff.by.wire}, not ${required.wire}",
                )
            }
        } else if (signoff.role == SignoffRole.ISSUER) {
            if (signoff.by != manifest.perspective && signoff.by != PerspectiveId.GENERAL_MANAGER) {
                return VerificationVerdict.failure(
                    GovernanceCode.MANIFEST_UNSIGNED.wire,
                    "a revision issuer must be the owner or the general manager",
                )
            }
        } else if (required != null && signoff.by != required) {
            return VerificationVerdict.failure(
                GovernanceCode.MANIFEST_UNSIGNED.wire,
                "${signoff.role.wire} was signed by ${signoff.by.wire}, not ${required.wire}",
            )
        }
        if (!seen.add(signoff.by)) {
            return VerificationVerdict.failure(
                GovernanceCode.MANIFEST_UNSIGNED.wire,
                "${signoff.by.wire} signed twice",
            )
        }
        if (manifest.version == 1 &&
            signoff.by == manifest.perspective &&
            manifest.perspective != PerspectiveId.GENERAL_MANAGER
        ) {
            return VerificationVerdict.failure(
                GovernanceCode.MANIFEST_SELF_SIGNED.wire,
                "${manifest.perspective.wire} signed its own manifest",
            )
        }
    }
    val roles = if (manifest.version == 1) {
        listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE)
    } else {
        listOf(SignoffRole.ISSUER)
    }
    for (role in roles) {
        if (manifest.signatures.none { it.role == role && it.digest == digest }) {
            return VerificationVerdict.failure(
                GovernanceCode.MANIFEST_UNSIGNED.wire,
                "missing ${role.wire} sign-off",
            )
        }
    }
    if (manifest.version > 1) {
        val issuer = manifest.signatures.firstOrNull { it.role == SignoffRole.ISSUER && it.digest == digest }
        if (issuer == null ||
            (issuer.by != manifest.perspective && issuer.by != PerspectiveId.GENERAL_MANAGER)
        ) {
            return VerificationVerdict.failure(
                GovernanceCode.MANIFEST_UNSIGNED.wire,
                "the revision issuer is not the owner or the general manager",
            )
        }
    }
    if (at != null && isExpired(manifest, at)) {
        return VerificationVerdict.failure(GovernanceCode.MANIFEST_EXPIRED.wire, "expired at ${manifest.expires}")
    }
    return VerificationVerdict.success()
}

fun isExpired(manifest: AttentionManifest, at: Timestamp): Boolean {
    val expires = governanceMillis(manifest.expires) ?: return true
    val now = governanceMillis(at) ?: return true
    return now >= expires
}

/**
 * Load a manifest into a perspective. Mismatched owner, scenario or expiry fails
 * closed.
 */
fun loadAttentionManifest(manifest: AttentionManifest, request: LoadManifestRequest): LoadedManifest {
    if (manifest.perspective != request.perspective) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "${manifest.id} belongs to ${manifest.perspective.wire}, not ${request.perspective.wire}",
        )
    }
    if (request.scenario != null && manifest.scenario != request.scenario) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "manifest scenario ${manifest.scenario.wire} does not match ${request.scenario.wire}",
        )
    }
    if (isExpired(manifest, request.at)) {
        throw GovernanceError(GovernanceCode.MANIFEST_EXPIRED, "${manifest.id} expired at ${manifest.expires}")
    }
    return LoadedManifest(
        manifest = manifest,
        focus = manifest.focus.map { it.copy() },
        ignore = manifest.ignore.map { it.copy() },
        reason = "loaded ${manifest.id} v${manifest.version} for ${manifest.perspective.wire}: " +
            "${manifest.focus.size} focus, ${manifest.ignore.size} ignored",
    )
}

/**
 * Frame-settlement reassessment: an ignored item returns to the candidate pool when
 * its cadence is due and its named revisit signal fired.
 */
fun reassessIgnored(manifest: AttentionManifest, frame: Int, signals: List<String>): Reassessment {
    val due = ArrayList<IgnoreItem>()
    val promoted = ArrayList<IgnoreItem>()
    val retained = ArrayList<IgnoreItem>()
    val remaining = ArrayList<IgnoreItem>()
    for (item in manifest.ignore) {
        val last = item.lastFrame ?: 0
        val isDue = frame - last >= item.everyFrames
        if (!isDue) {
            remaining += item
            continue
        }
        due += item
        if (signals.contains(item.revisitIf)) {
            promoted += item
            continue
        }
        retained += item
        remaining += item.copy(lastFrame = frame)
    }
    return Reassessment(
        due = due,
        promoted = promoted,
        retained = retained,
        updated = manifest.copy(ignore = remaining),
    )
}

/**
 * Apply promotions to a manifest. This is an update, so the version and digest change
 * and the owner re-signs.
 */
fun promoteIgnored(
    manifest: AttentionManifest,
    promoted: List<IgnoreItem>,
    signer: SignerInput,
    at: Timestamp,
): AttentionManifest {
    if (promoted.isEmpty()) {
        return manifest
    }
    if (signer.by != manifest.perspective && signer.by != PerspectiveId.GENERAL_MANAGER) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_UNSIGNED,
            "${signer.by.wire} may not re-sign ${manifest.perspective.wire}'s manifest",
        )
    }
    if (signer.role != SignoffRole.ISSUER) {
        throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "a revision is signed with the issuer role")
    }
    val kept = promoted.map { refKey(it.target) }.toSet()
    val focus = manifest.focus.toMutableList()
    for (item in promoted) {
        focus += FocusItem(
            target = item.target,
            category = FocusCategory.EVIDENCE,
            priority = 0.6,
            depth = 2,
        )
    }
    val trimmed = focus
        .sortedByDescending { it.priority }
        .take(maxOf(1, manifest.focus.size + promoted.size))
    return signRevision(
        manifest,
        ManifestPatch(
            focus = trimmed,
            ignore = manifest.ignore.filter { !kept.contains(refKey(it.target)) },
        ),
        signer,
        at,
    )
}

/**
 * Update a manifest. An execution perspective may change only its focus and ignore
 * lists; everything else needs the general manager plus the owning department's
 * approval. A scenario change is rejected: it must re-derive.
 */
fun updateAttentionManifest(
    manifest: AttentionManifest,
    patch: ManifestPatch,
    context: ManifestUpdateContext,
): AttentionManifest {
    if (context.by != manifest.perspective && context.by != PerspectiveId.GENERAL_MANAGER) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "${context.by.wire} may not update ${manifest.perspective.wire}'s manifest",
        )
    }
    val ownerOnly = context.by == manifest.perspective && context.by != PerspectiveId.GENERAL_MANAGER
    if (ownerOnly &&
        (patch.scenario != null ||
            patch.modes != null ||
            patch.budget != null ||
            patch.evidenceFloor != null ||
            patch.constraints != null ||
            patch.priorityWeights != null)
    ) {
        throw GovernanceError(
            GovernanceCode.MANIFEST_SCOPE,
            "an execution perspective may update only focus and ignore",
        )
    }
    if (patch.scenario != null && patch.scenario != manifest.scenario) {
        throw GovernanceError(GovernanceCode.MANIFEST_REDERIVE_REQUIRED, "a scenario change requires a fresh derivation")
    }
    var modes = manifest.modes
    patch.modes?.let { next ->
        val normalized = normalizeModeWeights(manifest.modes.asModeMap() + next)
        if (driftRequiresApproval(manifest.modes, normalized) &&
            !granted(context.approvals?.modeWeights, ApprovalSubject.MODE_WEIGHTS, manifest.perspective)
        ) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "mode drift ${fixed2(modeDrift(manifest.modes, normalized))} exceeds 0.15",
            )
        }
        modes = normalized
    }
    var evidenceFloor = manifest.evidenceFloor
    patch.evidenceFloor?.let { floor ->
        if (floor != manifest.evidenceFloor) {
            if (GovernanceLimits.floorRank.getValue(floor) > GovernanceLimits.floorRank.getValue(manifest.evidenceFloor)) {
                if (!granted(
                        context.approvals?.evidenceFloor,
                        ApprovalSubject.MANIFEST_EVIDENCE_FLOOR,
                        manifest.perspective,
                    )
                ) {
                    throw GovernanceError(
                        GovernanceCode.APPROVAL_REQUIRED,
                        "lowering the evidence floor needs a legal approval",
                    )
                }
            }
            evidenceFloor = floor
        }
    }
    if (patch.budget != null &&
        !granted(context.approvals?.budget, ApprovalSubject.BUDGET_ELASTICITY, manifest.perspective)
    ) {
        throw GovernanceError(GovernanceCode.APPROVAL_REQUIRED, "a budget binding change needs a finance approval")
    }
    if (patch.constraints != null &&
        !granted(context.approvals?.constraints, ApprovalSubject.MANIFEST_CONSTRAINTS, manifest.perspective)
    ) {
        throw GovernanceError(GovernanceCode.APPROVAL_REQUIRED, "a behaviour-constraint change needs a legal approval")
    }
    if (context.signoff.by != context.by) {
        throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "the revision signer must be the acting perspective")
    }
    if (context.signoff.role != SignoffRole.ISSUER) {
        throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "a revision is signed with the issuer role")
    }
    val revision = patch.copy(
        modes = patch.modes?.let { modes.asModeMap() },
        evidenceFloor = patch.evidenceFloor?.let { evidenceFloor },
    )
    return signRevision(manifest, revision, context.signoff, context.at)
}

/**
 * Contract the manifest when the task phase ends: budget is released and the manifest
 * stops authorising attention.
 */
fun expireAttentionManifest(manifest: AttentionManifest, at: Timestamp): AttentionManifest =
    manifest.copy(expires = at)

/**
 * Human-readable summary for a receipt or board note.
 */
fun manifestSummary(manifest: AttentionManifest): String = buildString {
    append("${manifest.id} v${manifest.version} ${manifest.scenario.wire} ")
    append("[${fixed2(manifest.modes.affect)}/${fixed2(manifest.modes.think)}/")
    append("${fixed2(manifest.modes.diverge)}/${fixed2(manifest.modes.converge)}] ")
    append("floor=${manifest.evidenceFloor.wire} ")
    append("focus=${manifest.focus.size} ignore=${manifest.ignore.size}")
}

/**
 * The expected cryptographic binding for a manifest: subject, id, version and digest.
 */
fun attentionManifestExpectation(manifest: AttentionManifest): GovernanceExpectation = GovernanceExpectation(
    subject = GovernanceSubject.ATTENTION_MANIFEST,
    subjectId = manifest.id,
    subjectVersion = manifest.version,
    digest = manifest.digest,
)

/**
 * Cryptographic signature hook: sign the manifest digest with every supplied
 * governance signer. Sign-offs remain the authority for "which department approved";
 * this proves key possession.
 */
fun signAttentionManifest(manifest: AttentionManifest, signers: List<GovernanceSigner>): GovernanceSignatureBundle =
    signGovernanceObject(
        subject = GovernanceSubject.ATTENTION_MANIFEST,
        subjectId = manifest.id,
        subjectVersion = manifest.version,
        digest = manifest.digest,
        signers = signers,
    )

/**
 * Cryptographic verification hook: verify a bundle against this manifest's subject and
 * digest. The content integrity check is [verifyAttentionManifest].
 */
fun verifyAttentionManifestSignatures(
    manifest: AttentionManifest,
    bundle: GovernanceSignatureBundle,
    keyring: GovernanceKeyring,
    requiredSigners: List<String>? = null,
): GovernanceVerification = verifyGovernanceBundle(
    bundle = bundle,
    expected = attentionManifestExpectation(manifest),
    keyring = keyring,
    requiredSigners = requiredSigners,
)

private fun signRevision(
    manifest: AttentionManifest,
    patch: ManifestPatch,
    signer: SignerInput,
    at: Timestamp,
): AttentionManifest {
    val nextModes = patch.modes?.let { normalizeModeWeights(it) } ?: manifest.modes
    val nextPlan = patch.modes?.let { resolveScenarioModes(manifest.scenario, nextModes.asModeMap()) }
    val body = manifest.unsigned()
    val unsigned = UnsignedAttentionManifest(
        id = body.id,
        perspective = body.perspective,
        scenario = body.scenario,
        modes = nextModes,
        focus = patch.focus?.let { dedupeFocus(it) } ?: body.focus,
        ignore = patch.ignore?.let { dedupeIgnore(it, manifest.reassessEvery) } ?: body.ignore,
        priorityWeights = patch.priorityWeights?.let { LinkedHashMap(it) } ?: body.priorityWeights,
        budget = patch.budget ?: body.budget,
        evidenceFloor = patch.evidenceFloor ?: body.evidenceFloor,
        requiredTier = patch.evidenceFloor?.let { GovernanceLimits.floorTier.getValue(it) } ?: body.requiredTier,
        reassessEvery = nextPlan?.revisitEvery ?: body.reassessEvery,
        gateStrictness = nextPlan?.gateStrictness ?: body.gateStrictness,
        elasticity = nextPlan?.elasticity ?: body.elasticity,
        sensitivity = body.sensitivity,
        escalations = body.escalations,
        constraints = patch.constraints?.let { copyConstraints(it) } ?: body.constraints,
        derivation = body.derivation,
        issuedAt = at,
        expires = body.expires,
        version = manifest.version + 1,
        parent = manifest.digest,
        note = body.note,
    )
    val digest = manifestDigest(unsigned)
    val signoff = Signoff(role = signer.role, by = signer.by, at = signer.at, digest = digest)
    return unsigned.toManifest(digest, listOf(signoff))
}

private fun checkSignoffs(
    unsigned: UnsignedAttentionManifest,
    digest: Digest,
    signers: List<SignerInput>,
    owner: PerspectiveId,
): List<Signoff> {
    val roles = listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE)
    val seen = LinkedHashSet<PerspectiveId>()
    val accepted = ArrayList<Signoff>()
    for (signer in signers) {
        if (!seen.add(signer.by)) {
            throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "${signer.by.wire} signed twice")
        }
        val required = RequiresRole[signer.role]
        if (required != null && signer.by != required) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_UNSIGNED,
                "${signer.role.wire} must be signed by ${required.wire}",
            )
        }
        if (signer.by == owner && signer.role != SignoffRole.ISSUER) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_SELF_SIGNED,
                "${owner.wire} cannot counter-sign its own manifest",
            )
        }
        if (signer.by == owner && owner != PerspectiveId.GENERAL_MANAGER) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_SELF_SIGNED,
                "${owner.wire} cannot issue its own manifest",
            )
        }
        accepted += Signoff(role = signer.role, by = signer.by, at = signer.at, digest = digest)
    }
    for (role in roles) {
        if (accepted.none { it.role == role }) {
            throw GovernanceError(GovernanceCode.MANIFEST_UNSIGNED, "missing ${role.wire} sign-off")
        }
    }
    if (unsigned.version < 1) {
        throw GovernanceError(GovernanceCode.MANIFEST_VERSION_CONFLICT, "manifest version must be positive")
    }
    return accepted
}

private fun granted(approval: ApprovalGrant?, subject: ApprovalSubject, requester: PerspectiveId): Boolean {
    if (approval == null || !approval.granted || approval.subject != subject) {
        return false
    }
    if (approval.by == requester) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_SELF,
            "${requester.wire} cannot approve its own ${subject.wire}",
        )
    }
    return canApprove(approval.by, approval.subject)
}

private fun copyConstraints(constraints: BehaviorConstraints): BehaviorConstraintSet = BehaviorConstraintSet(
    allowed = constraints.allowed.toList(),
    forbidden = constraints.forbidden.toList(),
    obligations = constraints.obligations.toList(),
    escalation = constraints.escalation.toList(),
    evidence = constraints.evidence,
)

private fun dedupeFocus(items: List<FocusItem>): List<FocusItem> {
    val seen = LinkedHashSet<String>()
    val result = ArrayList<FocusItem>()
    for (item in items) {
        // `Ref`'s own constructor already enforces the AMP id grammar, which excludes
        // the empty id; the check stays for parity with the reference's validation.
        if (item.target.id.isEmpty()) {
            throw GovernanceError(GovernanceCode.MANIFEST_SCOPE, "a focus item needs a non-empty target id")
        }
        if (!item.priority.isFinite() || item.priority < 0.0 || item.priority > 1.0) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_SCOPE,
                "priority for ${item.target.id} must be in [0, 1]",
            )
        }
        if (item.depth != 1 && item.depth != 2 && item.depth != 3) {
            throw GovernanceError(
                GovernanceCode.MANIFEST_SCOPE,
                "depth for ${item.target.id} must be 1, 2 or 3",
            )
        }
        if (!seen.add(refKey(item.target))) {
            continue
        }
        result += item
    }
    return result
}

private fun dedupeIgnore(items: List<IgnoreItem>, cadence: Int): List<IgnoreItem> {
    val seen = LinkedHashSet<String>()
    val result = ArrayList<IgnoreItem>()
    for (item in items) {
        if (!seen.add(refKey(item.target))) {
            continue
        }
        val everyFrames = if (item.everyFrames > 0) minOf(IgnoreCadenceCap, item.everyFrames) else cadence
        result += item.copy(everyFrames = everyFrames)
    }
    return result
}

private fun clampSensitivity(value: Sensitivity, floor: Sensitivity): Sensitivity =
    if (Sensitivity.rank.getValue(value) < Sensitivity.rank.getValue(floor)) floor else value

private fun defaultEscalations(sensitivity: Sensitivity): List<EscalationTrigger> = listOf(
    EscalationTrigger(
        condition = "evidence below the manifest floor",
        sensitivity = sensitivity,
        to = EscalationTarget.ToPerspective(PerspectiveId.LEGAL),
    ),
    EscalationTrigger(
        condition = "budget hard ceiling reached",
        sensitivity = sensitivity,
        to = EscalationTarget.ToPerspective(PerspectiveId.FINANCE),
    ),
    EscalationTrigger(
        condition = "posterior uncertainty above 0.45",
        sensitivity = sensitivity,
        to = EscalationTarget.ToPerspective(PerspectiveId.SUPERVISORY),
    ),
)

private fun parsePriorityWeights(obj: JsonValue.Obj): LinkedHashMap<String, Double> {
    val result = LinkedHashMap<String, Double>()
    val weights = obj.obj("priorityWeights") ?: return result
    for ((key, value) in weights.fields) {
        val number = (value as? JsonValue.Num)?.value
            ?: throw IllegalArgumentException("priorityWeights.$key 必须是数字")
        result[key] = number
    }
    return result
}

private fun ledgerScopeFromWire(value: String): LedgerScope =
    LedgerScope.entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 ledger scope: $value")

private fun gateStrictnessFromWire(value: String): GateStrictness =
    GateStrictness.entries.firstOrNull { it.wire == value }
        ?: throw IllegalArgumentException("未知 gate strictness: $value")

private fun JsonValue.asGovernanceObject(what: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw IllegalArgumentException("$what 必须是 JSON 对象")

/** Governance sign-off JSON, shared with the credential module. */
internal fun Signoff.toJson(): JsonValue.Obj = jsonObj(
    "role" to JsonValue.Str(role.wire),
    "by" to JsonValue.Str(by.wire),
    "at" to JsonValue.Str(at),
    "digest" to JsonValue.Str(digest),
)

internal fun signoffFromJson(value: JsonValue): Signoff {
    val obj = value.asGovernanceObject("signoff")
    return Signoff(
        role = SignoffRole.fromWire(obj.requireString("role")),
        by = PerspectiveId.fromWire(obj.requireString("by")),
        at = obj.requireString("at"),
        digest = obj.requireString("digest"),
    )
}

/**
 * Epoch milliseconds for a timestamp, or `null` when it is not parseable.
 *
 * The reference calls `Date.parse`, which accepts RFC 3339 instants and offsets; this
 * helper accepts the same two spellings the governance layer can produce or receive.
 */
internal fun governanceMillis(value: Timestamp): Long? {
    runCatching { Instant.parse(value) }.getOrNull()?.let { return it.toEpochMilli() }
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
}

/** JavaScript `Date#toISOString()` spelling, used for manifest and credential expiry. */
internal fun isoMillisTimestamp(ms: Long): Timestamp = ISO_MILLIS.format(Instant.ofEpochMilli(ms))

private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter
    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
    .withZone(ZoneOffset.UTC)
