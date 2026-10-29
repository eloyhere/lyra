package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * The legal behaviour-norms layer (spec section 7), a faithful port of
 * `src/governance/legal.ts`.
 *
 * Legal is not a reviewer at the end of the task: it injects constraints into
 * manifests and credentials, gates tool calls, board writes and escalations, and
 * owns freeze, downgrade and breaker orders. Freeze and breaker orders do not need
 * the general manager's approval, and the general manager can appeal but not revoke
 * them; only the board or a human can lift them.
 *
 * The layer is independent from the supervisory evidence audit: legal checks
 * behaviour compliance, supervisory checks evidence truth.
 */

/**
 * The action taxonomy from `src/policy/policy.ts`.
 *
 * The policy module itself is not part of this port yet, so the nine effect classes
 * it exports live here; legal is their only governance consumer.
 */
enum class EffectClass(val wire: String) {
    READ("read"),
    WRITE_REVERSIBLE("write-reversible"),
    WRITE_IRREVERSIBLE("write-irreversible"),
    SPEND("spend"),
    SEND("send"),
    DELETE("delete"),
    DEPLOY("deploy"),
    PERMISSION("permission"),
    PUBLISH("publish");

    companion object {
        /** Every effect class, ordered from least to most dangerous. */
        val all: List<EffectClass> = entries

        fun fromWireOrNull(value: String): EffectClass? = entries.firstOrNull { it.wire == value }
    }
}

/** Violation severity, ordered from lightest to heaviest. */
enum class ViolationLevel(val wire: String) {
    MINOR("minor"),
    MODERATE("moderate"),
    SEVERE("severe"),
    EMERGENCY("emergency"),
}

/** The disposition attached to a verdict. */
enum class LegalAction(val wire: String) {
    ALLOW("allow"),
    WARN("warn"),
    FREEZE("freeze"),
    DOWNGRADE("downgrade"),
    BREAKER("breaker"),
}

/** Machine-readable violation reasons. */
enum class ViolationCode(val wire: String) {
    BEHAVIOR_FORBIDDEN("behavior-forbidden"),
    TOOL_UNAPPROVED("tool-unapproved"),
    EVIDENCE_BELOW_FLOOR("evidence-below-floor"),
    RECEIPT_INCOMPLETE("receipt-incomplete"),
    BOARD_UNAPPROVED("board-unapproved"),
    ESCALATION_UNSIGNED("escalation-unsigned"),
    CREDENTIAL_SELF_SIGNED("credential-self-signed"),
    CREDENTIAL_MISSING_SIGNOFF("credential-missing-signoff"),
    CONTEXT_UNAUTHORIZED("context-unauthorized"),
    INJECTION_DETECTED("injection-detected"),
    UNKNOWN_EFFECT("unknown-effect"),
    MALICIOUS_TOOL("malicious-tool"),
    REPEAT_VIOLATION("repeat-violation"),
}

data class Violation(
    val code: ViolationCode,
    val level: ViolationLevel,
    val detail: String,
)

/**
 * A behaviour-code row for one scenario (spec 7.2).
 */
data class BehaviorCode(
    val scenario: ScenarioId,
    override val allowed: List<String>,
    override val forbidden: List<String>,
    override val obligations: List<String>,
    override val escalation: List<String>,
    override val evidence: EvidenceFloor,
) : BehaviorConstraints

/**
 * Default codes. They are intentionally short: a code list that nobody can read is
 * not a control.
 */
val DefaultBehaviorCodes: Map<ScenarioId, BehaviorCode> = listOf(
    BehaviorCode(
        scenario = ScenarioId.CHAT,
        allowed = listOf("respond", "clarify", "validate", "support", "label-uncertainty"),
        forbidden = listOf(
            "diagnose",
            "induce-dependency",
            "fabricate-evidence",
            "bypass-gate",
            "self-sign",
            "exfiltrate",
        ),
        obligations = listOf("label-uncertainty", "invite-user-agency"),
        escalation = listOf("self-harm-risk", "acute-crisis", "manipulation-request"),
        evidence = EvidenceFloor.F3,
    ),
    BehaviorCode(
        scenario = ScenarioId.WRITING,
        allowed = listOf("draft", "revise", "structure", "select-material", "cite"),
        forbidden = listOf("fabricate-citation", "plagiarize", "bypass-gate", "self-sign", "exfiltrate"),
        obligations = listOf("cite-sources", "preserve-author-voice", "mark-uncertainty"),
        escalation = listOf("fabricated-source", "rights-violation"),
        evidence = EvidenceFloor.F3,
    ),
    BehaviorCode(
        scenario = ScenarioId.OFFICE,
        allowed = listOf("draft", "schedule", "summarize", "route", "record"),
        forbidden = listOf(
            "send-without-approval",
            "delete-without-approval",
            "bypass-gate",
            "self-sign",
            "exfiltrate",
        ),
        obligations = listOf("record-recipients", "respect-data-scope"),
        escalation = listOf("sensitive-data", "unauthorized-send"),
        evidence = EvidenceFloor.F2,
    ),
    BehaviorCode(
        scenario = ScenarioId.RESEARCH,
        allowed = listOf("search", "read", "compare", "hypothesize", "cite", "record-unknown"),
        forbidden = listOf("fabricate-evidence", "hide-contradiction", "bypass-gate", "self-sign", "exfiltrate"),
        obligations = listOf("cite-sources", "record-unknowns", "surface-contradictions"),
        escalation = listOf("irreproducible-result", "fabricated-source"),
        evidence = EvidenceFloor.F2,
    ),
    BehaviorCode(
        scenario = ScenarioId.ENGINEERING,
        allowed = listOf("read", "patch", "test", "rollback", "deploy-with-approval"),
        forbidden = listOf("unapproved-write", "disable-audit", "bypass-gate", "self-sign", "exfiltrate"),
        obligations = listOf("record-rollback", "run-tests", "preserve-audit-trail"),
        escalation = listOf("irreversible-write", "sensitive-data", "test-bypass"),
        evidence = EvidenceFloor.F2,
    ),
).associateBy { it.scenario }

/**
 * Scenario-scoped code registry.
 */
class BehaviorRegistry(seed: List<BehaviorCode> = DefaultBehaviorCodes.values.toList()) {

    private val codes = LinkedHashMap<ScenarioId, BehaviorCode>()

    init {
        seed.forEach { codes[it.scenario] = it }
    }

    fun register(code: BehaviorCode) {
        codes[code.scenario] = code
    }

    fun forScenario(scenario: ScenarioId): BehaviorCode =
        codes[scenario] ?: throw GovernanceError(GovernanceCode.REGISTRY_INVALID, "no behaviour code for ${scenario.wire}")

    fun scenarios(): List<ScenarioId> = codes.keys.toList()
}

/**
 * The stricter of two floors; legal may raise a floor, never lower it.
 */
fun strictestFloor(left: EvidenceFloor, right: EvidenceFloor): EvidenceFloor =
    if (GovernanceLimits.floorRank.getValue(left) <= GovernanceLimits.floorRank.getValue(right)) left else right

/**
 * Inject a scenario's behaviour code into a manifest or credential.
 */
fun constraintsFor(scenario: ScenarioId, registry: BehaviorRegistry = BehaviorRegistry()): BehaviorConstraintSet {
    val code = registry.forScenario(scenario)
    return BehaviorConstraintSet(
        allowed = code.allowed.toList(),
        forbidden = code.forbidden.toList(),
        obligations = code.obligations.toList(),
        escalation = code.escalation.toList(),
        evidence = code.evidence,
    )
}

data class FreezeOrder(
    val id: String,
    val perspective: PerspectiveId,
    val level: ViolationLevel,
    val reason: String,
    val issuedAt: Timestamp,
    val issuedBy: String = "legal",
    val active: Boolean,
    val liftedAt: Timestamp? = null,
    val liftedBy: FreezeAuthority? = null,
)

/**
 * The general manager's appeal. It is recorded and does not suspend the order.
 */
data class FreezeAppeal(
    val orderId: String,
    val by: PerspectiveId,
    val reason: String,
    val at: Timestamp,
)

/** Only the board or a human may lift a freeze or reset the breaker. */
enum class FreezeAuthority(val wire: String) {
    BOARD("board"),
    HUMAN("human"),
}

data class DowngradeOrder(
    val id: String,
    val perspective: PerspectiveId,
    val to: String = "restricted",
    val reason: String,
    val at: Timestamp,
    val escalatedTo: String = "board",
)

data class BreakerState(
    val active: Boolean,
    val reason: String,
    val at: Timestamp,
    val resetAt: Timestamp? = null,
    val resetBy: FreezeAuthority? = null,
)

/** The orders a verdict applied. Absent entries are omitted, never null on the wire. */
data class LegalApplied(
    val freeze: FreezeOrder? = null,
    val downgrade: DowngradeOrder? = null,
    val breaker: BreakerState? = null,
)

data class LegalVerdict(
    val ok: Boolean,
    val action: LegalAction,
    val violations: List<Violation>,
    val applied: LegalApplied = LegalApplied(),
)

data class ToolCheckInput(
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    val action: String,
    /**
     * The effect class wire name. It stays a string so an unknown value fails
     * closed with an `unknown-effect` violation instead of being rejected by the
     * type system before the gate can see it.
     */
    val effect: String,
    val evidenceTier: Tier? = null,
    val evidenceFloor: EvidenceFloor? = null,
    val approval: ApprovalGrant? = null,
) {
    val effectClass: EffectClass? get() = EffectClass.fromWireOrNull(effect)

    companion object {
        fun of(
            perspective: PerspectiveId,
            scenario: ScenarioId,
            action: String,
            effect: EffectClass,
            evidenceTier: Tier? = null,
            evidenceFloor: EvidenceFloor? = null,
            approval: ApprovalGrant? = null,
        ): ToolCheckInput = ToolCheckInput(
            perspective = perspective,
            scenario = scenario,
            action = action,
            effect = effect.wire,
            evidenceTier = evidenceTier,
            evidenceFloor = evidenceFloor,
            approval = approval,
        )
    }
}

data class BoardWriteInput(
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    val missing: List<String>,
    val globalCore: Boolean,
    val approval: ApprovalGrant? = null,
)

data class EscalationCheckInput(
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    val evidenceRefs: Int,
    val target: PerspectiveId,
    val credentialSigned: Boolean,
)

data class ReceiptCheckInput(
    val perspective: PerspectiveId,
    val scenario: ScenarioId,
    val missing: List<String>,
)

data class CredentialCheckInput(
    val from: PerspectiveId,
    val to: PerspectiveId,
    val signoffs: List<String>,
    val selfSigned: Boolean,
)

data class LegalAuditTrail(
    val orders: List<FreezeOrder>,
    val appeals: List<FreezeAppeal>,
    val downgrades: List<DowngradeOrder>,
    val violations: List<Violation>,
)

private val HighRiskEffects: Set<EffectClass> = setOf(
    EffectClass.WRITE_IRREVERSIBLE,
    EffectClass.SPEND,
    EffectClass.SEND,
    EffectClass.DELETE,
    EffectClass.DEPLOY,
    EffectClass.PERMISSION,
    EffectClass.PUBLISH,
)

private val ActionForLevel: Map<ViolationLevel, LegalAction> = mapOf(
    ViolationLevel.MINOR to LegalAction.WARN,
    ViolationLevel.MODERATE to LegalAction.FREEZE,
    ViolationLevel.SEVERE to LegalAction.DOWNGRADE,
    ViolationLevel.EMERGENCY to LegalAction.BREAKER,
)

/**
 * The gate itself. `check*` methods are pure; `enforce*` methods apply orders.
 */
class LegalGate(
    private val now: () -> Timestamp,
    private val registry: BehaviorRegistry = BehaviorRegistry(),
) {

    private val orders = ArrayList<FreezeOrder>()
    private val appeals = ArrayList<FreezeAppeal>()
    private val downgrades = ArrayList<DowngradeOrder>()
    private val violations = ArrayList<Violation>()
    private val counters = LinkedHashMap<PerspectiveId, Int>()
    private var breaker: BreakerState? = null
    private var counter = 0

    /**
     * Strictness per scenario (spec 7.3).
     */
    fun strictness(scenario: ScenarioId): GateStrictness = gateStrictnessFor(scenario)

    fun checkTool(input: ToolCheckInput): LegalVerdict {
        val violations = ArrayList<Violation>()
        val code = registry.forScenario(input.scenario)
        val effect = input.effectClass
        if (effect == null) {
            violations += Violation(
                code = ViolationCode.UNKNOWN_EFFECT,
                level = ViolationLevel.MODERATE,
                detail = "${input.effect} is not an effect class",
            )
        }
        if (code.forbidden.contains(input.action)) {
            violations += Violation(
                code = ViolationCode.BEHAVIOR_FORBIDDEN,
                level = ViolationLevel.SEVERE,
                detail = "${input.action} is forbidden in ${input.scenario.wire}",
            )
        }
        if (effect != null && HighRiskEffects.contains(effect) &&
            !approved(input.approval, ApprovalSubject.TOOL_HIGH_RISK, PerspectiveId.LEGAL)
        ) {
            violations += Violation(
                code = ViolationCode.TOOL_UNAPPROVED,
                level = ViolationLevel.MODERATE,
                detail = "${input.effect} needs a legal tool.high-risk approval",
            )
        }
        val tier = input.evidenceTier
        val floor = input.evidenceFloor
        if (tier != null && floor != null) {
            val required = GovernanceLimits.floorTier.getValue(floor)
            if (GovernanceLimits.tierRank.getValue(tier) < GovernanceLimits.tierRank.getValue(required)) {
                val level = if (strictness(input.scenario) == GateStrictness.STRICT) {
                    ViolationLevel.MODERATE
                } else {
                    ViolationLevel.MINOR
                }
                violations += Violation(
                    code = ViolationCode.EVIDENCE_BELOW_FLOOR,
                    level = level,
                    detail = "${tier.wire} is below the ${floor.wire} floor (needs ${required.wire})",
                )
            }
        }
        return verdict(LegalAction.ALLOW, violations)
    }

    fun checkBoardWrite(input: BoardWriteInput): LegalVerdict {
        val violations = ArrayList<Violation>()
        if (input.missing.isNotEmpty()) {
            violations += Violation(
                code = ViolationCode.RECEIPT_INCOMPLETE,
                level = ViolationLevel.MINOR,
                detail = "missing fields: ${input.missing.joinToString(", ")}",
            )
        }
        if (input.globalCore && !approved(input.approval, ApprovalSubject.BOARD_WRITE, PerspectiveId.BOARD)) {
            violations += Violation(
                code = ViolationCode.BOARD_UNAPPROVED,
                level = ViolationLevel.SEVERE,
                detail = "writing the global core needs a board approval",
            )
        }
        return verdict(LegalAction.ALLOW, violations)
    }

    fun checkEscalation(input: EscalationCheckInput): LegalVerdict {
        val violations = ArrayList<Violation>()
        if (input.evidenceRefs <= 0) {
            violations += Violation(
                code = ViolationCode.ESCALATION_UNSIGNED,
                level = ViolationLevel.MODERATE,
                detail = "an escalation must cite at least one evidence reference",
            )
        }
        if (!input.credentialSigned &&
            input.target != PerspectiveId.GENERAL_MANAGER &&
            input.target != PerspectiveId.LEGAL
        ) {
            violations += Violation(
                code = ViolationCode.ESCALATION_UNSIGNED,
                level = ViolationLevel.MODERATE,
                detail = "an escalation target needs a signed credential",
            )
        }
        return verdict(LegalAction.ALLOW, violations)
    }

    fun checkReceipt(input: ReceiptCheckInput): LegalVerdict {
        val violations = ArrayList<Violation>()
        if (input.missing.isNotEmpty()) {
            violations += Violation(
                code = ViolationCode.RECEIPT_INCOMPLETE,
                level = ViolationLevel.MINOR,
                detail = "receipt missing: ${input.missing.joinToString(", ")}",
            )
        }
        return verdict(LegalAction.ALLOW, violations)
    }

    fun checkCredential(input: CredentialCheckInput): LegalVerdict {
        val violations = ArrayList<Violation>()
        if (input.selfSigned) {
            violations += Violation(
                code = ViolationCode.CREDENTIAL_SELF_SIGNED,
                level = ViolationLevel.MODERATE,
                detail = "${input.from.wire} signed its own credential",
            )
        }
        val required = listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE)
        for (role in required) {
            if (!input.signoffs.contains(role.wire)) {
                violations += Violation(
                    code = ViolationCode.CREDENTIAL_MISSING_SIGNOFF,
                    level = ViolationLevel.MODERATE,
                    detail = "missing ${role.wire} sign-off",
                )
            }
        }
        return verdict(LegalAction.ALLOW, violations)
    }

    /**
     * Tool checkpoint: check and, when needed, freeze or trip the breaker.
     */
    fun enforceTool(input: ToolCheckInput): LegalVerdict = enforce(input.perspective, checkTool(input))

    fun enforceBoardWrite(input: BoardWriteInput): LegalVerdict = enforce(input.perspective, checkBoardWrite(input))

    fun enforceEscalation(input: EscalationCheckInput): LegalVerdict =
        enforce(input.perspective, checkEscalation(input))

    fun enforceReceipt(input: ReceiptCheckInput): LegalVerdict = enforce(input.perspective, checkReceipt(input))

    fun enforceCredential(input: CredentialCheckInput): LegalVerdict = enforce(input.from, checkCredential(input))

    /**
     * Report an out-of-band signal (injection traces, malicious tool return).
     */
    fun reportEmergency(perspective: PerspectiveId, detail: String, code: ViolationCode): LegalVerdict {
        val violation = Violation(code = code, level = ViolationLevel.EMERGENCY, detail = detail)
        return enforce(perspective, verdict(LegalAction.ALLOW, listOf(violation)))
    }

    /**
     * Moderation counter; three moderate findings escalate to severe (spec 7.2:
     * repeated boundary crossing).
     *
     * The reference iterates the same growing list it appends the
     * `repeat-violation` finding to, so that finding also bumps the counter. The
     * index loop below keeps that observable behaviour.
     */
    private fun enforce(perspective: PerspectiveId, verdict: LegalVerdict): LegalVerdict {
        val appraisals = record(perspective, verdict)
        if (appraisals.violations.isEmpty()) {
            return appraisals
        }
        var worst = ViolationLevel.MINOR
        for (violation in appraisals.violations) {
            if (violation.level.ordinal > worst.ordinal) {
                worst = violation.level
            }
        }
        val action = ActionForLevel.getValue(worst)
        val reason = appraisals.violations.joinToString("; ") { it.detail }
        var freeze: FreezeOrder? = null
        var downgrade: DowngradeOrder? = null
        var tripped: BreakerState? = null
        if (worst.ordinal >= ViolationLevel.MODERATE.ordinal) {
            freeze = freezeInternal(perspective, worst, reason)
        }
        if (worst.ordinal >= ViolationLevel.SEVERE.ordinal) {
            downgrade = downgradeInternal(perspective, worst, "repeated or severe boundary crossing")
        }
        if (worst == ViolationLevel.EMERGENCY) {
            val state = BreakerState(active = true, reason = reason, at = now())
            breaker = state
            tripped = state
        }
        return appraisals.copy(
            ok = false,
            action = action,
            applied = LegalApplied(freeze = freeze, downgrade = downgrade, breaker = tripped),
        )
    }

    private fun record(perspective: PerspectiveId, verdict: LegalVerdict): LegalVerdict {
        val violations = verdict.violations.toMutableList()
        var index = 0
        while (index < violations.size) {
            val violation = violations[index]
            this.violations += violation
            if (violation.level == ViolationLevel.MODERATE || violation.level == ViolationLevel.SEVERE) {
                val next = (counters[perspective] ?: 0) + 1
                counters[perspective] = next
                if (next >= 3 && violation.level == ViolationLevel.MODERATE) {
                    violations[index] = violation.copy(
                        level = ViolationLevel.SEVERE,
                        detail = "${violation.detail} (repeated boundary crossing)",
                    )
                    violations += Violation(
                        code = ViolationCode.REPEAT_VIOLATION,
                        level = ViolationLevel.SEVERE,
                        detail = "${perspective.wire} crossed a boundary $next times",
                    )
                }
            }
            index += 1
        }
        return LegalVerdict(
            ok = violations.isEmpty(),
            action = if (violations.isEmpty()) LegalAction.ALLOW else LegalAction.WARN,
            violations = violations.toList(),
            applied = LegalApplied(),
        )
    }

    private fun freezeInternal(perspective: PerspectiveId, level: ViolationLevel, reason: String): FreezeOrder {
        val active = orders.firstOrNull { it.perspective == perspective && it.active }
        if (active != null) {
            return active
        }
        counter += 1
        val order = FreezeOrder(
            id = "frz.$counter",
            perspective = perspective,
            level = level,
            reason = reason,
            issuedAt = now(),
            issuedBy = "legal",
            active = true,
        )
        orders += order
        return order
    }

    private fun downgradeInternal(perspective: PerspectiveId, level: ViolationLevel, reason: String): DowngradeOrder {
        counter += 1
        val order = DowngradeOrder(
            id = "dwn.$counter",
            perspective = perspective,
            to = "restricted",
            reason = "${level.wire}: $reason",
            at = now(),
            escalatedTo = "board",
        )
        downgrades += order
        return order
    }

    /**
     * The general manager may appeal, never revoke.
     */
    fun appeal(orderId: String, by: PerspectiveId, reason: String): FreezeAppeal {
        if (by != PerspectiveId.GENERAL_MANAGER) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_NOT_PERMITTED,
                "only the general manager may appeal a freeze",
            )
        }
        if (orders.none { it.id == orderId }) {
            throw GovernanceError(GovernanceCode.LEGAL_FROZEN, "unknown freeze order $orderId")
        }
        val appeal = FreezeAppeal(orderId = orderId, by = by, reason = reason, at = now())
        appeals += appeal
        return appeal
    }

    /**
     * Only the board or a human can lift a freeze.
     */
    fun lift(orderId: String, by: FreezeAuthority): FreezeOrder {
        val index = orders.indexOfFirst { it.id == orderId }
        if (index < 0) {
            throw GovernanceError(GovernanceCode.LEGAL_FROZEN, "unknown freeze order $orderId")
        }
        val lifted = orders[index].copy(active = false, liftedAt = now(), liftedBy = by)
        orders[index] = lifted
        return lifted
    }

    fun isFrozen(perspective: PerspectiveId): Boolean =
        orders.any { it.perspective == perspective && it.active }

    fun activeFreezes(): List<FreezeOrder> = orders.filter { it.active }.map { it.copy() }

    /**
     * Trip the global breaker. Only the board or a human can reset it.
     */
    fun tripBreaker(reason: String): BreakerState {
        val current = breaker
        if (current != null && current.active) {
            return current.copy()
        }
        val state = BreakerState(active = true, reason = reason, at = now())
        breaker = state
        return state.copy()
    }

    fun resetBreaker(by: FreezeAuthority): BreakerState {
        val current = breaker ?: throw GovernanceError(GovernanceCode.LEGAL_BREAKER, "the breaker is not tripped")
        val state = current.copy(active = false, resetAt = now(), resetBy = by)
        breaker = state
        return state.copy()
    }

    fun breakerState(): BreakerState? = breaker?.copy()

    fun isBlocked(): Boolean = breaker?.active == true

    fun appealsFor(orderId: String): List<FreezeAppeal> = appeals.filter { it.orderId == orderId }.map { it.copy() }

    fun downgradesFor(perspective: PerspectiveId): List<DowngradeOrder> =
        downgrades.filter { it.perspective == perspective }.map { it.copy() }

    fun auditTrail(): LegalAuditTrail = LegalAuditTrail(
        orders = orders.map { it.copy() },
        appeals = appeals.map { it.copy() },
        downgrades = downgrades.map { it.copy() },
        violations = violations.map { it.copy() },
    )

    private fun approved(approval: ApprovalGrant?, subject: ApprovalSubject, by: PerspectiveId): Boolean =
        approval != null && approval.granted && approval.subject == subject && approval.by == by

    private fun verdict(action: LegalAction, violations: List<Violation>): LegalVerdict = LegalVerdict(
        ok = violations.isEmpty(),
        action = if (violations.isEmpty()) action else LegalAction.WARN,
        violations = violations.toList(),
        applied = LegalApplied(),
    )
}

/** Behaviour-constraint JSON, shared with the manifest and credential modules. */
internal fun behaviorConstraintsToJson(constraints: BehaviorConstraints): JsonValue.Obj = jsonObj(
    "allowed" to jsonStrings(constraints.allowed),
    "forbidden" to jsonStrings(constraints.forbidden),
    "obligations" to jsonStrings(constraints.obligations),
    "escalation" to jsonStrings(constraints.escalation),
    "evidence" to JsonValue.Str(constraints.evidence.wire),
)

internal fun behaviorConstraintsFromJson(value: JsonValue): BehaviorConstraintSet {
    val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("constraints 必须是 JSON 对象")
    fun readStrings(key: String): List<String> = obj.requireArr(key).items.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 的元素必须是字符串")
    }
    return BehaviorConstraintSet(
        allowed = readStrings("allowed"),
        forbidden = readStrings("forbidden"),
        obligations = readStrings("obligations"),
        escalation = readStrings("escalation"),
        evidence = EvidenceFloor.fromWire(obj.requireString("evidence")),
    )
}
