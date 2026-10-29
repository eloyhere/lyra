package com.eloykim.lyra.amp.protocol.delegate

import com.eloykim.lyra.amp.protocol.protocol.Delegation
import com.eloykim.lyra.amp.protocol.protocol.Lease
import com.eloykim.lyra.amp.protocol.protocol.LeaseStatus
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * `src/delegate/team.ts` 的忠实移植：委托 → 租约 → 预留 → 结果。
 *
 * ## 映射与绑定
 *
 * 参考文件的 `Delegation` / `Lease` 形状已经被兄弟移植放在
 * `com.eloykim.lyra.amp.protocol.protocol`（`protocol/Wire.kt`，注释里明确写了它同时
 * 承担 `src/delegate/team.ts` 的可见形状）。这里直接复用那两个类型，避免出现两套互不
 * 兼容的 delegation/lease 模型；wire 序列化仍走 `DelegationDoc` / `LeaseDoc`。
 * 因此本文件只新增参考实现里有、`Wire.kt` 没有的部分：入参、运行期和错误类型。
 *
 * 所有权限检查都在本地完成，从不信任对端的承诺：
 * - 子预算只能收缩，每个维度独立检查（[attenuate]）。
 * - 租约是单写者，fence 不匹配的写入是陈旧写入。
 * - 预留（reserve）发生在派发之前，它让预算成为真实约束（D-3）。
 *
 * 参考实现是单线程的；这些方法加锁只保证并发调用下顺序与状态安全，单线程行为不变。
 */

/** 创建委托的入参（参考的 `Omit<Delegation, "id">`）。 */
data class DelegationInput(
    val granter: Id,
    val delegatee: Id,
    val goalDigest: Digest,
    val scope: List<String>,
    val budget: Budget,
    val deadline: Timestamp,
    val mayRedelegate: Boolean,
    val depth: Int,
    val parent: Id? = null,
)

/** 签发租约的入参（参考的 `LeaseGrant`）。 */
data class LeaseGrant(
    val taskId: Id,
    val holder: Id,
    val goalDigest: Digest,
    val budget: Budget,
    val deadline: Timestamp,
)

/** 一个租约的预算视图：已预留与已花费。 */
data class LeaseUsage(
    val reserved: Budget,
    val spent: Budget,
)

sealed class TeamError(message: String) : IllegalArgumentException(message) {
    abstract val code: String
}

class AttenuationError(message: String) : TeamError(message) {
    override val code: String = "delegation_widened"
}

class FencingError(message: String) : TeamError(message) {
    override val code: String = "stale_writer"
}

class DuplicateTask(message: String) : TeamError(message) {
    override val code: String = "duplicate_goal"
}

class BudgetError(message: String) : TeamError(message) {
    override val code: String = "budget_exceeded"
}

private enum class BudgetDimension(val wire: String) {
    TOKENS("tokens"),
    CALLS("calls"),
    MS("ms"),
    INTERRUPTS("interrupts"),
    MONEY("money"),
    ;
}

private val DIMS: List<BudgetDimension> = listOf(
    BudgetDimension.TOKENS,
    BudgetDimension.CALLS,
    BudgetDimension.MS,
    BudgetDimension.INTERRUPTS,
    BudgetDimension.MONEY,
)

private fun budgetValue(budget: Budget, dimension: BudgetDimension): Double? = when (dimension) {
    BudgetDimension.TOKENS -> budget.tokens?.toDouble()
    BudgetDimension.CALLS -> budget.calls?.toDouble()
    BudgetDimension.MS -> budget.ms?.toDouble()
    BudgetDimension.INTERRUPTS -> budget.interrupts?.toDouble()
    BudgetDimension.MONEY -> budget.money
}

/**
 * 把累加值写回 wire `Budget`。
 *
 * 参考实现的维值是 JS number；wire `Budget` 的 tokens/calls/interrupts 是整数、ms 是
 * 整数毫秒，所以这里截断小数。委托/租约路径的预算都是整数，截断不会丢失可观察信息。
 */
private fun mergeBudget(values: Map<BudgetDimension, Double>): Budget = Budget(
    tokens = values[BudgetDimension.TOKENS]?.toInt(),
    calls = values[BudgetDimension.CALLS]?.toInt(),
    ms = values[BudgetDimension.MS]?.toLong(),
    interrupts = values[BudgetDimension.INTERRUPTS]?.toInt(),
    money = values[BudgetDimension.MONEY],
)

/** 错误消息里的 JS 数字渲染：整数不带小数点。 */
private fun formatNumber(value: Double): String =
    if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        value.toString()
    }

/**
 * 子预算只能收缩；每个维度都独立检查。
 */
private fun attenuate(parent: Budget, child: Budget) {
    for (dimension in DIMS) {
        val cap: Double = budgetValue(parent, dimension) ?: continue
        val request: Double = budgetValue(child, dimension) ?: continue
        if (request > cap) {
            throw AttenuationError(
                "child ${dimension.wire} ${formatNumber(request)} exceeds parent ${formatNumber(cap)}",
            )
        }
    }
}

/**
 * 团队运行期。
 *
 * team manifest 是通讯录：如果提供，成员、scope、fan-out 和预算都会在委托存在之前检查。
 */
class TeamRuntime(
    private val now: () -> Timestamp,
) {

    private val delegations: MutableMap<Id, Delegation> = LinkedHashMap()
    private val leases: MutableMap<Id, Lease> = LinkedHashMap()
    private val activeByGoal: MutableMap<Digest, Id> = LinkedHashMap()
    private val fences: MutableMap<Id, Int> = LinkedHashMap()
    private val reserved: MutableMap<Id, MutableMap<BudgetDimension, Double>> = LinkedHashMap()
    private val spent: MutableMap<Id, MutableMap<BudgetDimension, Double>> = LinkedHashMap()
    private var seq: Int = 0

    /** 注入的时钟；暴露出来让租约与 receipt 共享同一条时间线。 */
    fun nowIso(): Timestamp = now()

    @Synchronized
    fun createDelegation(input: DelegationInput, manifest: TeamManifest? = null): Delegation {
        if (manifest != null) {
            enforceManifest(input, manifest)
        }
        if (input.depth > 2) {
            throw AttenuationError("delegation depth exceeds 2")
        }
        val parentId: Id? = input.parent
        if (parentId != null) {
            val parent: Delegation = delegations[parentId]
                ?: throw AttenuationError("unknown parent delegation $parentId")
            if (!parent.mayRedelegate) {
                throw AttenuationError("parent does not allow re-delegation")
            }
            attenuate(parent.budget, input.budget)
            if (input.deadline > parent.deadline) {
                throw AttenuationError("child deadline exceeds parent")
            }
        }
        seq += 1
        val delegation = Delegation(
            id = "dl.$seq",
            parent = parentId,
            granter = input.granter,
            delegatee = input.delegatee,
            goalDigest = input.goalDigest,
            scope = input.scope,
            budget = input.budget,
            deadline = input.deadline,
            mayRedelegate = input.mayRedelegate,
            depth = input.depth,
        )
        delegations[delegation.id] = delegation
        return delegation
    }

    @Synchronized
    fun issueLease(delegationId: Id, input: LeaseGrant): Lease {
        val delegation: Delegation = delegations[delegationId]
            ?: throw AttenuationError("unknown delegation $delegationId")
        attenuate(delegation.budget, input.budget)
        if (input.deadline > delegation.deadline) {
            throw AttenuationError("lease deadline exceeds delegation")
        }

        val activeId: Id? = activeByGoal[input.goalDigest]
        if (activeId != null && leases[activeId]?.status == LeaseStatus.ACTIVE) {
            throw DuplicateTask("goal already has an active lease: $activeId")
        }

        val fence: Int = (fences[input.taskId] ?: 0) + 1
        fences[input.taskId] = fence
        seq += 1
        val lease = Lease(
            id = "ls.$seq",
            delegation = delegationId,
            taskId = input.taskId,
            holder = input.holder,
            fence = fence,
            goalDigest = input.goalDigest,
            budget = input.budget,
            deadline = input.deadline,
            status = LeaseStatus.ACTIVE,
        )
        leases[lease.id] = lease
        activeByGoal[input.goalDigest] = lease.id
        reserved[lease.id] = LinkedHashMap()
        spent[lease.id] = LinkedHashMap()
        return lease
    }

    /**
     * 派发之前先预留（D-3）：预留才让预算成为真实约束。
     */
    @Synchronized
    fun reserve(leaseId: Id, cost: Budget) {
        val lease: Lease = active(leaseId)
        val reservedDims: MutableMap<BudgetDimension, Double> =
            (reserved[leaseId] ?: LinkedHashMap()).toMutableMap()
        for (dimension in DIMS) {
            val next: Double = (reservedDims[dimension] ?: 0.0) + (budgetValue(cost, dimension) ?: 0.0)
            val limit: Double? = budgetValue(lease.budget, dimension)
            if (limit != null && next > limit) {
                throw BudgetError(
                    "reserved ${dimension.wire} ${formatNumber(next)} exceeds lease ${formatNumber(limit)}",
                )
            }
            if (budgetValue(cost, dimension) != null) {
                reservedDims[dimension] = next
            }
        }
        reserved[leaseId] = reservedDims
    }

    @Synchronized
    fun settle(leaseId: Id, actual: Budget) {
        active(leaseId)
        reserved[leaseId] = LinkedHashMap()
        val spentDims: MutableMap<BudgetDimension, Double> =
            (spent[leaseId] ?: LinkedHashMap()).toMutableMap()
        for (dimension in DIMS) {
            spentDims[dimension] =
                (spentDims[dimension] ?: 0.0) + (budgetValue(actual, dimension) ?: 0.0)
        }
        spent[leaseId] = spentDims
    }

    /**
     * 租约是单写者：fence 必须匹配，否则写入是陈旧的。
     */
    @Synchronized
    fun assertWriter(leaseId: Id, fence: Int) {
        val lease: Lease = active(leaseId)
        if (lease.fence != fence) {
            throw FencingError("stale writer: fence $fence != ${lease.fence}")
        }
    }

    @Synchronized
    fun complete(leaseId: Id): Lease {
        val lease: Lease = active(leaseId)
        val done: Lease = lease.copy(status = LeaseStatus.DONE)
        leases[leaseId] = done
        activeByGoal.remove(lease.goalDigest)
        return done
    }

    @Synchronized
    fun cancel(leaseId: Id): Lease {
        val lease: Lease = active(leaseId)
        val cancelled: Lease = lease.copy(status = LeaseStatus.CANCELLED)
        leases[leaseId] = cancelled
        activeByGoal.remove(lease.goalDigest)
        return cancelled
    }

    @Synchronized
    fun lease(leaseId: Id): Lease? = leases[leaseId]

    @Synchronized
    fun usage(leaseId: Id): LeaseUsage = LeaseUsage(
        reserved = mergeBudget(reserved[leaseId] ?: emptyMap()),
        spent = mergeBudget(spent[leaseId] ?: emptyMap()),
    )

    /**
     * 如果提供了 team manifest：成员、depth、scope、预算与 fan-out 都在委托存在之前检查。
     */
    private fun enforceManifest(input: DelegationInput, manifest: TeamManifest) {
        val granter: TeamMember = manifest.members.firstOrNull { it.id == input.granter }
            ?: throw AttenuationError("granter ${input.granter} is not a team member")
        if (manifest.members.none { it.id == input.delegatee }) {
            throw AttenuationError("delegatee ${input.delegatee} is not a team member")
        }
        if (input.depth > manifest.maxDepth) {
            throw AttenuationError("depth ${input.depth} exceeds team max ${manifest.maxDepth}")
        }
        for (scope in input.scope) {
            if (scope !in granter.authority) {
                throw AttenuationError("scope $scope exceeds ${granter.id} authority")
            }
        }
        attenuate(manifest.budget, input.budget)
        val existing: Int = delegations.values.count { it.granter == input.granter }
        if (existing >= manifest.maxFanout) {
            throw AttenuationError("fan-out limit ${manifest.maxFanout} reached for ${input.granter}")
        }
    }

    private fun active(leaseId: Id): Lease {
        val lease: Lease = leases[leaseId] ?: throw FencingError("unknown lease $leaseId")
        if (lease.status != LeaseStatus.ACTIVE) {
            throw FencingError("lease $leaseId is ${lease.status.wire}")
        }
        return lease
    }
}
