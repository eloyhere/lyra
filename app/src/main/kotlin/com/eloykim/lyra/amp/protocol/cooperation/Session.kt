package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.attention.AttentionDecision
import com.eloykim.lyra.amp.protocol.attention.AttentionRequest
import com.eloykim.lyra.amp.protocol.attention.CallCost
import com.eloykim.lyra.amp.protocol.delegate.DelegationInput
import com.eloykim.lyra.amp.protocol.delegate.LeaseGrant
import com.eloykim.lyra.amp.protocol.delegate.TeamManifest
import com.eloykim.lyra.amp.protocol.delegate.TeamRuntime
import com.eloykim.lyra.amp.protocol.protocol.Delegation
import com.eloykim.lyra.amp.protocol.protocol.Lease
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * 一次真实的协作会话（`src/cooperation/session.ts` 的忠实移植）。
 *
 * 团队权威、任务板、租约与统一预算账户被接在一起，谁都不能悄悄漂移。
 *
 * ## 绑定
 *
 * 参考实现的 `TeamSession` 依赖 `src/delegate/team.ts` 的 `TeamRuntime`。该文件
 * 已经由兄弟移植落在 `delegate/Team.kt`（委托 → 租约 → 预留 → 结果），本文件
 * 直接复用它的 [TeamRuntime]、[DelegationInput] 与 [LeaseGrant]，不重复实现
 * 预算衰减/围栏/清单约束。需要注入自定义运行时（例如持久化版本）时，向
 * [TeamSession] 传入第二个构造参数即可。
 */

/** 打开会话需要的接线：预算账户、时钟与可选的团队清单。 */
data class TeamSessionOptions(
    val budget: Budget,
    val now: () -> Timestamp,
    val manifest: TeamManifest? = null,
)

/** 委托认领任务板上的工作项。 */
data class StartInput(
    val taskId: Id,
    val holder: Id,
    val goalDigest: Digest,
    val budget: Budget,
    val deadline: Timestamp,
)

/** `start()` 产出的任务与租约。 */
data class StartedWork(
    val task: BoardTask,
    val lease: Lease,
)

/** `finish()` 产出的租约与任务。 */
data class FinishedWork(
    val lease: Lease,
    val task: BoardTask,
)

/** `cancel()` 产出的租约与可选任务。 */
data class CancelledWork(
    val lease: Lease,
    val task: BoardTask? = null,
)

/**
 * 一次真实协作会话：团队权威、任务板、租约与统一预算账户接线在一起。
 *
 * 所有团队运行时操作都通过兄弟端口 `delegate.TeamRuntime` 完成，会话本身只负责
 * 把任务板、租约与统一账户对齐。
 */
class TeamSession(
    options: TeamSessionOptions,
    team: TeamRuntime? = null,
) {

    val team: TeamRuntime = team ?: TeamRuntime(options.now)
    val board: TaskBoard = TaskBoard()
    val ledger: UnifiedLedger = UnifiedLedger(options.budget, options.now)
    private val manifest: TeamManifest? = options.manifest
    private val leaseToTask = LinkedHashMap<Id, Id>()

    fun delegate(input: DelegationInput): Delegation = team.createDelegation(input, manifest)

    /**
     * 先上任务板：重复目标永远不会消耗租约。
     */
    fun start(delegationId: Id, input: StartInput): StartedWork {
        val task = board.add(input.goalDigest)
        try {
            val lease = team.issueLease(
                delegationId,
                LeaseGrant(
                    taskId = input.taskId,
                    holder = input.holder,
                    goalDigest = input.goalDigest,
                    budget = input.budget,
                    deadline = input.deadline,
                ),
            )
            board.claim(task.id, lease)
            leaseToTask[lease.id] = task.id
            return StartedWork(task = board.get(task.id), lease = lease)
        } catch (error: Throwable) {
            board.cancel(task.id)
            throw error
        }
    }

    /**
     * 先在租约上预留，再扣共享账户，最后结算。
     */
    fun <T> spend(leaseId: Id, cost: Budget, fn: () -> T): T {
        team.reserve(leaseId, cost)
        try {
            return ledger.spend(
                fn,
                CallCost(tokens = cost.tokens, calls = cost.calls, ms = cost.ms),
            )
        } finally {
            team.settle(leaseId, cost)
        }
    }

    fun finish(leaseId: Id): FinishedWork {
        val lease = team.complete(leaseId)
        val taskId = leaseToTask[leaseId]
        val task = taskId?.let { board.complete(it) }
            ?: throw IllegalStateException("lease $leaseId has no board task")
        return FinishedWork(lease = lease, task = task)
    }

    fun cancel(leaseId: Id): CancelledWork {
        val lease = team.cancel(leaseId)
        val taskId = leaseToTask[leaseId]
        return CancelledWork(lease = lease, task = taskId?.let { board.cancel(it) })
    }

    fun requestAttention(request: AttentionRequest): AttentionDecision = ledger.requestAttention(request)

    fun snapshot(): UnifiedSnapshot = ledger.snapshot()

    fun metrics(merge: MergeResult? = null): CoordinationMetrics = coordinationMetrics(
        CoordinationInput(board = board, snapshot = snapshot(), merge = merge),
    )
}
