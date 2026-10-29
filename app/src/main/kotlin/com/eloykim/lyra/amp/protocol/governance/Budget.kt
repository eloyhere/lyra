package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * The three-ledger attention budget (spec section 8), a faithful port of
 * `src/governance/budget.ts`.
 *
 * Three separate accounts, because they answer different questions and may not
 * silently fund each other:
 *   - global: the whole task's quota;
 *   - perspective or agent: one stance's quota, with elasticity;
 *   - reserve: escalation only, never execution.
 *
 * Multi-agent work adds an agent ledger per agent. Its quota is transferred from a
 * parent perspective or agent; the meter never mints budget.
 *
 * Finance owns freeze and downgrade. A charge that needs an approval fails closed
 * instead of borrowing from another ledger.
 */

/** Stable ledger configuration: cap plus elasticity. */
data class LedgerConfig(
    val cap: Budget,
    val elasticity: Elasticity,
)

data class PerspectiveLedgerConfig(
    val perspective: PerspectiveId,
    val cap: Budget,
    val elasticity: Elasticity,
)

data class AgentLedgerConfig(
    val agent: String,
    val cap: Budget,
    val elasticity: Elasticity,
)

data class GovernanceBudgetOptions(
    val global: LedgerConfig,
    val reserve: LedgerConfig,
    val perspectives: List<PerspectiveLedgerConfig> = emptyList(),
    val agents: List<AgentLedgerConfig> = emptyList(),
    val now: () -> Timestamp,
)

/** A ledger snapshot: caps and usage per dimension, plus the worst-case ratio. */
data class LedgerSnapshot(
    val scope: LedgerScope,
    val id: String,
    val cap: BudgetTotals,
    val used: BudgetTotals,
    val ratio: Double,
    val frozen: Boolean,
    val frozenReason: String? = null,
    val elasticity: Elasticity,
)

data class ChargeRequest(
    val scope: LedgerScope,
    /** Required when scope is `perspective`. */
    val perspective: PerspectiveId? = null,
    /** Required when scope is `agent`. */
    val agent: String? = null,
    val amount: Budget,
    val reason: String,
    /** Reserve charges must declare that they fund an escalation. */
    val escalation: Boolean? = null,
    val approval: ApprovalGrant? = null,
    /** A reserve draw above 50% of the reserve also needs the board. */
    val boardApproval: ApprovalGrant? = null,
    val requestedBy: PerspectiveId? = null,
)

enum class ChargeCode(val wire: String) {
    CHARGED("charged"),
    CHARGED_POST_AUDIT("charged-post-audit"),
    CHARGED_WITH_APPROVAL("charged-with-approval"),
    APPROVAL_REQUIRED("approval-required"),
    FROZEN("frozen"),
    EXHAUSTED("exhausted"),
    RESERVE_RESTRICTED("reserve-restricted"),
}

data class ChargeResult(
    val ok: Boolean,
    val code: ChargeCode,
    val ledger: LedgerSnapshot,
    val ratio: Double,
    val detail: String,
    val approvalId: String? = null,
)

data class ChargeRecord(
    val at: Timestamp,
    val scope: LedgerScope,
    val ledgerId: String,
    val amount: BudgetTotals,
    val reason: String,
    val code: ChargeCode,
    val requestedBy: PerspectiveId? = null,
    val approvalId: String? = null,
)

data class PostAuditEntry(
    val at: Timestamp,
    val scope: LedgerScope,
    val ledgerId: String,
    val amount: BudgetTotals,
    val reason: String,
)

data class TransferRecord(
    val at: Timestamp,
    val fromScope: LedgerScope,
    val fromId: String,
    val toScope: LedgerScope,
    val toId: String,
    val amount: BudgetTotals,
    val reason: String,
    val approvalId: String,
)

/** An account on either side of a budget transfer. */
enum class BudgetAccountScope(val wire: String) {
    PERSPECTIVE("perspective"),
    AGENT("agent"),
}

data class BudgetAccount(
    val scope: BudgetAccountScope,
    val id: String,
)

enum class ReallocationCode(val wire: String) {
    REALLOCATED("reallocated"),
    INSUFFICIENT("insufficient"),
    FROZEN("frozen"),
    APPROVAL_REQUIRED("approval-required"),
    UNKNOWN_ACCOUNT("unknown-account"),
}

data class ReallocationResult(
    val ok: Boolean,
    val code: ReallocationCode,
    val from: LedgerSnapshot,
    val to: LedgerSnapshot,
    val detail: String,
)

data class ReallocationOptions(
    val toElasticity: Elasticity = Elasticity.LOW,
    val requestedBy: PerspectiveId? = null,
)

data class ReallocationRequest(
    val amount: Budget,
    val approval: ApprovalGrant,
    val reason: String,
    val options: ReallocationOptions = ReallocationOptions(),
)

data class BudgetSettlement(
    val global: LedgerSnapshot,
    val reserve: LedgerSnapshot,
    val perspectives: List<LedgerSnapshot>,
    val agents: List<LedgerSnapshot>,
    val transfers: List<TransferRecord>,
    val charges: Int,
    val postAudit: List<PostAuditEntry>,
    val frozen: List<LedgerSnapshot>,
    val balanced: Boolean,
)

private class LedgerState(
    val scope: LedgerScope,
    val id: String,
    var cap: BudgetTotals,
    var used: BudgetTotals,
    val elasticity: Elasticity,
    var frozen: Boolean = false,
    var frozenReason: String? = null,
)

/**
 * The three-ledger meter.
 *
 * Elasticity semantics:
 *   - low: up to +20% is appended automatically; beyond that the ledger freezes;
 *   - medium: anything above the cap needs a finance `budget.top-up` approval, up to
 *     +35%;
 *   - high: up to +50% is appended automatically and settled by a post audit.
 */
class GovernanceBudget(options: GovernanceBudgetOptions) {

    private val now: () -> Timestamp = options.now
    private val global: LedgerState = state(LedgerScope.GLOBAL, "global", options.global)
    private val reserve: LedgerState = state(LedgerScope.RESERVE, "reserve", options.reserve)
    private val perspectives = LinkedHashMap<String, LedgerState>()
    private val agents = LinkedHashMap<String, LedgerState>()
    private val records = ArrayList<ChargeRecord>()
    private val audits = ArrayList<PostAuditEntry>()
    private val transfers = ArrayList<TransferRecord>()

    init {
        for (entry in options.perspectives) {
            perspectives[entry.perspective.wire] = state(
                LedgerScope.PERSPECTIVE,
                entry.perspective.wire,
                LedgerConfig(entry.cap, entry.elasticity),
            )
        }
        for (entry in options.agents) {
            agents[entry.agent] = state(LedgerScope.AGENT, entry.agent, LedgerConfig(entry.cap, entry.elasticity))
        }
    }

    /**
     * Open a perspective ledger. Re-opening with a different cap is rejected: a
     * budget change is an approval, not a convenience call.
     */
    fun openPerspective(
        perspective: PerspectiveId,
        cap: Budget,
        elasticity: Elasticity = Elasticity.LOW,
    ): LedgerSnapshot {
        if (perspectives.containsKey(perspective.wire)) {
            throw GovernanceError(
                GovernanceCode.BUDGET_FROZEN,
                "ledger for ${perspective.wire} already exists",
            )
        }
        val created = state(LedgerScope.PERSPECTIVE, perspective.wire, LedgerConfig(cap, elasticity))
        perspectives[perspective.wire] = created
        return snapshotOf(created)
    }

    /**
     * Open an agent ledger (an agent's own quota).
     */
    fun openAgent(agent: String, cap: Budget, elasticity: Elasticity = Elasticity.LOW): LedgerSnapshot {
        if (agents.containsKey(agent)) {
            throw GovernanceError(GovernanceCode.BUDGET_FROZEN, "ledger for agent $agent already exists")
        }
        val created = state(LedgerScope.AGENT, agent, LedgerConfig(cap, elasticity))
        agents[agent] = created
        return snapshotOf(created)
    }

    /**
     * Transfer allocation between a perspective and an agent. A sub-agent's quota
     * is funded from its parent; the total allocation is conserved. The finance
     * `budget.allocation` approval is mandatory.
     */
    fun reallocate(from: BudgetAccount, to: BudgetAccount, request: ReallocationRequest): ReallocationResult {
        val amount = request.amount
        val approval = request.approval
        val options = request.options
        validateBudget(amount, "reallocation")
        val targetFrom = lookup(from.scope.ledgerScope(), from.id)
        val existingTo: LedgerState? = when (to.scope) {
            BudgetAccountScope.PERSPECTIVE -> perspectives[to.id]
            BudgetAccountScope.AGENT -> agents[to.id]
        }
        val targetTo = existingTo
            ?: state(to.scope.ledgerScope(), to.id, LedgerConfig(Budget(), options.toElasticity))
        if (targetFrom.frozen) {
            return ReallocationResult(
                ok = false,
                code = ReallocationCode.FROZEN,
                from = snapshotOf(targetFrom),
                to = snapshotOf(targetTo),
                detail = "${from.id} is frozen",
            )
        }
        if (targetTo.frozen) {
            return ReallocationResult(
                ok = false,
                code = ReallocationCode.FROZEN,
                from = snapshotOf(targetFrom),
                to = snapshotOf(targetTo),
                detail = "${to.id} is frozen",
            )
        }
        val valid = approval.granted &&
            approval.subject == ApprovalSubject.BUDGET_ALLOCATION &&
            canApprove(approval.by, ApprovalSubject.BUDGET_ALLOCATION) &&
            options.requestedBy != null &&
            options.requestedBy != approval.by
        if (!valid) {
            return ReallocationResult(
                ok = false,
                code = ReallocationCode.APPROVAL_REQUIRED,
                from = snapshotOf(targetFrom),
                to = snapshotOf(targetTo),
                detail = "a finance budget.allocation approval is required",
            )
        }
        val moved = budgetTotals(amount)
        for (dimension in BudgetDimension.entries) {
            if (moved.dimension(dimension) <= 0.0) {
                continue
            }
            val available = targetFrom.cap.dimension(dimension) - targetFrom.used.dimension(dimension)
            if (targetFrom.cap.dimension(dimension) <= 0.0 || moved.dimension(dimension) > available) {
                return ReallocationResult(
                    ok = false,
                    code = ReallocationCode.INSUFFICIENT,
                    from = snapshotOf(targetFrom),
                    to = snapshotOf(targetTo),
                    detail = "${dimension.wire}: ${CanonicalJson.formatNumber(moved.dimension(dimension))} requested, " +
                        "${CanonicalJson.formatNumber(maxOf(0.0, available))} available",
                )
            }
        }
        for (dimension in BudgetDimension.entries) {
            targetFrom.cap = targetFrom.cap.withDimension(
                dimension,
                targetFrom.cap.dimension(dimension) - moved.dimension(dimension),
            )
            targetTo.cap = targetTo.cap.withDimension(
                dimension,
                targetTo.cap.dimension(dimension) + moved.dimension(dimension),
            )
        }
        if (existingTo == null) {
            when (to.scope) {
                BudgetAccountScope.PERSPECTIVE -> perspectives[to.id] = targetTo
                BudgetAccountScope.AGENT -> agents[to.id] = targetTo
            }
        }
        transfers += TransferRecord(
            at = now(),
            fromScope = from.scope.ledgerScope(),
            fromId = from.id,
            toScope = to.scope.ledgerScope(),
            toId = to.id,
            amount = moved,
            reason = request.reason,
            approvalId = approval.id,
        )
        return ReallocationResult(
            ok = true,
            code = ReallocationCode.REALLOCATED,
            from = snapshotOf(targetFrom),
            to = snapshotOf(targetTo),
            detail = "moved ${request.reason}",
        )
    }

    /**
     * Charge one ledger. Denials are values; a frozen ledger refuses before
     * arithmetic so no caller can spend past a freeze.
     */
    fun charge(request: ChargeRequest): ChargeResult {
        validateBudget(request.amount, "charge")
        val target = resolve(request)
        val snapshot = snapshotOf(target)
        if (target.frozen) {
            return reject(ChargeCode.FROZEN, snapshot, snapshot.ratio, target.frozenReason ?: "ledger is frozen")
        }
        if (request.scope == LedgerScope.RESERVE) {
            if (request.escalation != true) {
                return reject(
                    ChargeCode.RESERVE_RESTRICTED,
                    snapshot,
                    snapshot.ratio,
                    "the reserve ledger only funds escalations",
                )
            }
            if (!approved(request, ApprovalSubject.BUDGET_RESERVE)) {
                return reject(
                    ChargeCode.APPROVAL_REQUIRED,
                    snapshot,
                    snapshot.ratio,
                    "a reserve charge needs a finance budget.reserve approval",
                )
            }
            if (projectedRatio(target.used, target.cap, request.amount) > 0.5 &&
                !boardApproved(request.boardApproval)
            ) {
                return reject(
                    ChargeCode.APPROVAL_REQUIRED,
                    snapshot,
                    snapshot.ratio,
                    "a reserve draw above 50% also needs a board approval",
                )
            }
        }
        val ratio = projectedRatio(target.used, target.cap, request.amount)
        val limit = GovernanceLimits.elasticity.getValue(target.elasticity)
        if (ratio <= 1.0) {
            commit(target, request, ChargeCode.CHARGED, ratio)
            return ChargeResult(
                ok = true,
                code = ChargeCode.CHARGED,
                ledger = snapshotOf(target),
                ratio = ratio,
                detail = "within cap",
            )
        }
        if (ratio <= 1 + limit.autoRatio) {
            val code = if (limit.postAudit) ChargeCode.CHARGED_POST_AUDIT else ChargeCode.CHARGED
            commit(target, request, code, ratio)
            if (limit.postAudit) {
                audits += PostAuditEntry(
                    at = now(),
                    scope = target.scope,
                    ledgerId = target.id,
                    amount = budgetTotals(request.amount),
                    reason = request.reason,
                )
            }
            return ChargeResult(
                ok = true,
                code = code,
                ledger = snapshotOf(target),
                ratio = ratio,
                detail = "within ${Math.round(limit.autoRatio * 100)}% elasticity",
            )
        }
        if (limit.requiresApproval && ratio <= 1 + limit.hardRatio) {
            if (!approved(request, ApprovalSubject.BUDGET_TOP_UP)) {
                return reject(
                    ChargeCode.APPROVAL_REQUIRED,
                    snapshot,
                    ratio,
                    "over cap; finance top-up approval required",
                )
            }
            commit(target, request, ChargeCode.CHARGED_WITH_APPROVAL, ratio)
            return ChargeResult(
                ok = true,
                code = ChargeCode.CHARGED_WITH_APPROVAL,
                ledger = snapshotOf(target),
                ratio = ratio,
                detail = "over cap with finance approval",
                approvalId = request.approval?.id,
            )
        }
        target.frozen = true
        val reason = "hard ceiling at ${Math.round((1 + limit.hardRatio) * 100)}% of cap"
        target.frozenReason = reason
        records += ChargeRecord(
            at = now(),
            scope = target.scope,
            ledgerId = target.id,
            amount = budgetTotals(request.amount),
            reason = request.reason,
            code = ChargeCode.EXHAUSTED,
        )
        return reject(ChargeCode.EXHAUSTED, snapshotOf(target), ratio, reason)
    }

    /**
     * Freeze one ledger. Finance owns this right; the reason is mandatory so a
     * later audit can tell a policy freeze from an incident freeze.
     */
    fun freeze(
        scope: LedgerScope,
        id: String,
        reason: String,
        by: PerspectiveId = PerspectiveId.FINANCE,
    ): LedgerSnapshot {
        if (!canApprove(by, ApprovalSubject.BUDGET_FREEZE)) {
            throw GovernanceError(GovernanceCode.APPROVAL_NOT_PERMITTED, "${by.wire} may not freeze a ledger")
        }
        val target = lookup(scope, id)
        target.frozen = true
        target.frozenReason = reason
        return snapshotOf(target)
    }

    /**
     * Unfreeze requires a finance approval on record.
     */
    fun unfreeze(scope: LedgerScope, id: String, approval: ApprovalGrant): LedgerSnapshot {
        if (!approval.granted || !canApprove(approval.by, ApprovalSubject.BUDGET_FREEZE)) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_NOT_PERMITTED,
                "unfreeze requires a granted finance approval",
            )
        }
        val target = lookup(scope, id)
        target.frozen = false
        target.frozenReason = null
        return snapshotOf(target)
    }

    /**
     * Draw from the escalation reserve. Every draw needs a finance approval; a draw
     * above half the reserve also needs a board `budget.major-top-up` approval (spec
     * section 8). The board approval is never inferred from the finance one.
     */
    fun drawReserve(
        amount: Budget,
        approval: ApprovalGrant,
        reason: String,
        boardApproval: ApprovalGrant? = null,
    ): ChargeResult {
        validateBudget(amount, "reserve draw")
        if (!approval.granted ||
            approval.subject != ApprovalSubject.BUDGET_RESERVE ||
            !canApprove(approval.by, ApprovalSubject.BUDGET_RESERVE)
        ) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "reserve draws require a granted finance budget.reserve approval",
            )
        }
        val ratio = projectedRatio(reserve.used, reserve.cap, amount)
        if (ratio > 0.5 && !boardApproved(boardApproval)) {
            throw GovernanceError(
                GovernanceCode.APPROVAL_REQUIRED,
                "a reserve draw above 50% also needs a board budget.major-top-up approval",
            )
        }
        return charge(
            ChargeRequest(
                scope = LedgerScope.RESERVE,
                amount = amount,
                reason = reason,
                escalation = true,
                approval = approval,
                boardApproval = boardApproval,
                requestedBy = PerspectiveId.GENERAL_MANAGER,
            ),
        )
    }

    fun snapshot(scope: LedgerScope, id: String? = null): LedgerSnapshot {
        val ledgerId = id ?: if (scope == LedgerScope.GLOBAL || scope == LedgerScope.RESERVE) scope.wire else ""
        return snapshotOf(lookup(scope, ledgerId))
    }

    /**
     * Remaining allocation on one ledger (cap minus used, per dimension).
     */
    fun remaining(scope: LedgerScope, id: String? = null): BudgetTotals {
        val target = lookup(scope, id ?: if (scope == LedgerScope.GLOBAL || scope == LedgerScope.RESERVE) scope.wire else "")
        var result = budgetTotals(Budget())
        for (dimension in BudgetDimension.entries) {
            result = result.withDimension(
                dimension,
                maxOf(0.0, target.cap.dimension(dimension) - target.used.dimension(dimension)),
            )
        }
        return result
    }

    fun snapshots(): List<LedgerSnapshot> =
        listOf(snapshotOf(global), snapshotOf(reserve)) +
            perspectives.values.map { snapshotOf(it) } +
            agents.values.map { snapshotOf(it) }

    /**
     * End-of-task settlement: every ledger, every transfer, every post-audit entry
     * and the freeze list, so the archive phase can attach one object.
     */
    fun settle(): BudgetSettlement {
        val perspectiveSnapshots = perspectives.values.map { snapshotOf(it) }
        val agentSnapshots = agents.values.map { snapshotOf(it) }
        val frozen = (listOf(snapshotOf(global), snapshotOf(reserve)) + perspectiveSnapshots + agentSnapshots)
            .filter { it.frozen }
        return BudgetSettlement(
            global = snapshotOf(global),
            reserve = snapshotOf(reserve),
            perspectives = perspectiveSnapshots,
            agents = agentSnapshots,
            transfers = transfers.map { it.copy(amount = it.amount.copy()) },
            charges = records.size,
            postAudit = audits.map { it.copy(amount = it.amount.copy()) },
            frozen = frozen,
            balanced = records.none { it.code == ChargeCode.EXHAUSTED },
        )
    }

    fun history(): List<ChargeRecord> = records.map { it.copy(amount = it.amount.copy()) }

    fun transferHistory(): List<TransferRecord> = transfers.map { it.copy(amount = it.amount.copy()) }

    private fun state(scope: LedgerScope, id: String, config: LedgerConfig): LedgerState {
        validateBudget(config.cap, "ledger $id")
        return LedgerState(
            scope = scope,
            id = id,
            cap = budgetTotals(config.cap),
            used = budgetTotals(Budget()),
            elasticity = config.elasticity,
            frozen = false,
        )
    }

    private fun resolvedId(request: ChargeRequest): String {
        if (request.scope == LedgerScope.PERSPECTIVE) {
            return request.perspective?.wire
                ?: throw GovernanceError(GovernanceCode.BUDGET_EXHAUSTED, "a perspective charge needs a perspective id")
        }
        if (request.scope == LedgerScope.AGENT) {
            return request.agent
                ?: throw GovernanceError(GovernanceCode.BUDGET_EXHAUSTED, "an agent charge needs an agent id")
        }
        return request.scope.wire
    }

    private fun resolve(request: ChargeRequest): LedgerState = lookup(request.scope, resolvedId(request))

    private fun lookup(scope: LedgerScope, id: String): LedgerState = when (scope) {
        LedgerScope.GLOBAL -> global
        LedgerScope.RESERVE -> reserve
        LedgerScope.AGENT -> agents[id]
            ?: throw GovernanceError(GovernanceCode.BUDGET_EXHAUSTED, "no ledger for agent $id")
        LedgerScope.PERSPECTIVE -> perspectives[id]
            ?: throw GovernanceError(GovernanceCode.BUDGET_EXHAUSTED, "no ledger for perspective $id")
    }

    private fun approved(request: ChargeRequest, subject: ApprovalSubject): Boolean {
        val approval = request.approval
        if (approval == null || !approval.granted || approval.subject != subject) {
            return false
        }
        if (!canApprove(approval.by, subject)) {
            return false
        }
        return request.requestedBy != null && request.requestedBy != approval.by
    }

    private fun boardApproved(approval: ApprovalGrant?): Boolean =
        approval != null &&
            approval.granted &&
            approval.subject == ApprovalSubject.BUDGET_MAJOR_TOP_UP &&
            canApprove(approval.by, ApprovalSubject.BUDGET_MAJOR_TOP_UP)

    private fun commit(target: LedgerState, request: ChargeRequest, code: ChargeCode, ratio: Double) {
        target.used = sumBudget(target.used, request.amount)
        records += ChargeRecord(
            at = now(),
            scope = target.scope,
            ledgerId = target.id,
            amount = budgetTotals(request.amount),
            reason = request.reason,
            code = code,
            requestedBy = request.requestedBy,
            approvalId = request.approval?.id,
        )
        if (ratio > 1.0) {
            audits += PostAuditEntry(
                at = now(),
                scope = target.scope,
                ledgerId = target.id,
                amount = budgetTotals(request.amount),
                reason = request.reason,
            )
        }
    }

    private fun reject(code: ChargeCode, ledger: LedgerSnapshot, ratio: Double, detail: String): ChargeResult =
        ChargeResult(ok = false, code = code, ledger = ledger, ratio = ratio, detail = detail)
}

private fun BudgetAccountScope.ledgerScope(): LedgerScope = when (this) {
    BudgetAccountScope.PERSPECTIVE -> LedgerScope.PERSPECTIVE
    BudgetAccountScope.AGENT -> LedgerScope.AGENT
}

/**
 * Worst per-dimension utilization after a prospective charge. Dimensions with no
 * cap are not metered, so they cannot deny a charge.
 */
private fun projectedRatio(used: BudgetTotals, cap: BudgetTotals, amount: Budget): Double {
    val added = budgetTotals(amount)
    var ratio = 0.0
    for (dimension in BudgetDimension.entries) {
        if (cap.dimension(dimension) <= 0.0) {
            continue
        }
        ratio = maxOf(
            ratio,
            (used.dimension(dimension) + added.dimension(dimension)) / cap.dimension(dimension),
        )
    }
    return ratio
}

/**
 * Reject negative, NaN or infinite amounts before any arithmetic. A negative charge
 * would otherwise mint quota.
 *
 * [Budget]'s own constructor already enforces these invariants; this check keeps the
 * reference's `budget-invalid` error code for callers that pass a value built through
 * some other path, and it names the offending field.
 */
private fun validateBudget(budget: Budget, label: String) {
    val raw = mapOf(
        BudgetDimension.TOKENS to budget.tokens?.toDouble(),
        BudgetDimension.MS to budget.ms?.toDouble(),
        BudgetDimension.CALLS to budget.calls?.toDouble(),
        BudgetDimension.INTERRUPTS to budget.interrupts?.toDouble(),
        BudgetDimension.MONEY to budget.money,
    )
    for (dimension in BudgetDimension.entries) {
        val value = raw[dimension] ?: continue
        if (!value.isFinite() || value < 0.0) {
            throw GovernanceError(
                GovernanceCode.BUDGET_INVALID,
                "$label.${dimension.wire} must be a finite non-negative number",
            )
        }
    }
}

private fun snapshotOf(state: LedgerState): LedgerSnapshot = LedgerSnapshot(
    scope = state.scope,
    id = state.id,
    cap = state.cap,
    used = state.used,
    ratio = projectedRatio(state.used, state.cap, Budget()),
    frozen = state.frozen,
    frozenReason = state.frozenReason,
    elasticity = state.elasticity,
)

private fun sumBudget(left: BudgetTotals, right: Budget): BudgetTotals {
    val added = budgetTotals(right)
    var result = left
    for (dimension in BudgetDimension.entries) {
        result = result.withDimension(
            dimension,
            result.dimension(dimension) + added.dimension(dimension),
        )
    }
    return result
}

private fun BudgetTotals.dimension(dimension: BudgetDimension): Double = when (dimension) {
    BudgetDimension.TOKENS -> tokens.toDouble()
    BudgetDimension.MS -> ms.toDouble()
    BudgetDimension.CALLS -> calls.toDouble()
    BudgetDimension.INTERRUPTS -> interrupts.toDouble()
    BudgetDimension.MONEY -> money
}

private fun BudgetTotals.withDimension(dimension: BudgetDimension, value: Double): BudgetTotals = when (dimension) {
    BudgetDimension.TOKENS -> copy(tokens = value.toInt())
    BudgetDimension.MS -> copy(ms = value.toLong())
    BudgetDimension.CALLS -> copy(calls = value.toInt())
    BudgetDimension.INTERRUPTS -> copy(interrupts = value.toInt())
    BudgetDimension.MONEY -> copy(money = value)
}
