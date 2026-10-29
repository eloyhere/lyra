package com.eloykim.lyra.attention

/**
 * 增员策略：**一次决策，而不是三方会签**。
 *
 * 依据（见 `docs/hiring-and-task-flow.md`）：
 * - 逐级会签在 LLM 群聊里成本是 O(人数) 次模型调用，任何一方沉默就永久卡住；
 * - 工业界的内控做法是「授权额度 + 例外上报」（management by exception）：
 *   额度内的增员由制度直接批准，只有超编、超预算、敏感席位才升级给人；
 * - 财务/资源不是审批人，而是**约束**：编制、预算余量由代码算，不需要模型表态。
 *
 * 因此这里只有四种输出：自动批准、升级董事长、拒绝、以及（调用方补的）冷却。
 * 规则是纯函数，可单测；群聊里谁申请、谁执行都不影响结论。
 */
data class HiringAsk(
    /** 目标职位 key，例如 `FRONTEND`。 */
    val positionKey: String,
    /** 所属部门，例如 `工程`。 */
    val department: String,
    /** 为什么缺人：哪件事卡住了。空 = 无依据，直接拒。 */
    val reason: String,
    /** 招进来第一周要交付什么。 */
    val expected: String = "",
)

/** 决策时读到的组织事实（由数据层给出，策略层不查库）。 */
data class HiringFacts(
    val memberCount: Int = 0,
    /** 部门 → 在岗人数。 */
    val departmentCount: Map<String, Int> = emptyMap(),
    /** 当前未决的增员申请数。 */
    val openRequests: Int = 0,
    /** 该职位是否已经有人在岗。 */
    val positionTaken: Boolean = false,
    val budgetTokens: Long = 0,
    val spentTokens: Long = 0,
) {
    val remainingTokens: Long get() = (budgetTokens - spentTokens).coerceAtLeast(0)
}

data class HiringLimits(
    /** 群成员上限：超过就升级给董事长，而不是直接拒（业务可能需要）。 */
    val maxMembers: Int = 12,
    /** 单部门编制上限。 */
    val maxPerDepartment: Int = 3,
    /** 预算余量下限：低于它说明再招人也没钱跑，升级给人判断。 */
    val minRemainingTokens: Long = 120_000,
    /** 同时未决的申请上限，挡住"一口气招十个"。 */
    val maxOpenRequests: Int = 2,
) {
    companion object {
        val Default = HiringLimits()
    }
}

sealed interface HiringDecision {
    val reason: String

    /** 制度内：立即执行，不再找人点头。 */
    data class AutoApprove(override val reason: String) : HiringDecision

    /** 例外：生成一条待批申请，董事长（人）一次点击决定。 */
    data class NeedChairman(override val reason: String) : HiringDecision

    /** 前置条件不成立：连申请都不该建。 */
    data class Reject(override val reason: String) : HiringDecision
}

object HiringPolicy {

    /**
     * 敏感席位：能改预算、角色或合规结论的职位。增员会给持有者这些权力，
     * 所以即使编制宽裕也升级给人确认。
     */
    private val sensitivePositions = setOf(
        Positions.STAFF_MINISTER,
        "LEGAL_MINISTER",
        "HR_MINISTER",
        "FINANCE_MINISTER",
        "RESOURCE_MINISTER",
    )

    fun evaluate(
        ask: HiringAsk,
        facts: HiringFacts,
        limits: HiringLimits = HiringLimits.Default,
    ): HiringDecision {
        val position = Positions.byKey(ask.positionKey)
            ?: return HiringDecision.Reject("没有这个职位：${ask.positionKey}")
        if (position.key == Positions.CHAIRMAN || position.key == Positions.RECORDER) {
            return HiringDecision.Reject("${position.title} 不是可招聘的席位")
        }
        if (ask.reason.isBlank()) {
            return HiringDecision.Reject("没写为什么缺人：先说明哪件事卡住了")
        }
        if (facts.positionTaken) {
            return HiringDecision.Reject("${position.title} 已经有人在岗，先看现有的人能不能做")
        }
        if (facts.openRequests >= limits.maxOpenRequests) {
            return HiringDecision.Reject("已有 ${facts.openRequests} 条未决申请，先把它们处理掉")
        }
        if (position.key in sensitivePositions) {
            return HiringDecision.NeedChairman("${position.title} 是敏感席位，需要董事长确认")
        }
        if (facts.memberCount >= limits.maxMembers) {
            return HiringDecision.NeedChairman("成员已达上限 ${limits.maxMembers}，需要董事长定夺")
        }
        val inDepartment = facts.departmentCount[ask.department].orZero()
        if (inDepartment >= limits.maxPerDepartment) {
            return HiringDecision.NeedChairman(
                "${ask.department}部已有 $inDepartment 人（上限 ${limits.maxPerDepartment}），需要董事长定夺",
            )
        }
        if (facts.budgetTokens > 0 && facts.remainingTokens < limits.minRemainingTokens) {
            return HiringDecision.NeedChairman(
                "预算余量只剩 ${facts.remainingTokens} tokens，招人前需要董事长确认",
            )
        }
        return HiringDecision.AutoApprove("编制与预算内，自动批准")
    }

    /** 一条申请通过后的默认称呼：职位名 + 部门序号，避免同名。 */
    fun defaultNickname(positionKey: String, memberCountInDepartment: Int): String {
        val title = Positions.byKey(positionKey)?.title ?: positionKey
        return if (memberCountInDepartment <= 0) title else "$title${memberCountInDepartment + 1}"
    }

    private fun Int?.orZero(): Int = this ?: 0
}

/**
 * 任务状态机：与 `docs/intake-flow.md` 一致，多一道 `review`（完成要有人验收）。
 *
 * 现有库里的状态字符串保持兼容；`review` 只是新增的合法状态。
 */
object TaskFlow {

    const val PROPOSED = "proposed"
    const val NEEDS_INFO = "needs_info"
    const val READY = "ready"
    const val CLAIMED = "claimed"
    const val DOING = "doing"
    const val REVIEW = "review"
    const val BLOCKED = "blocked"
    const val DONE = "done"
    const val DROPPED = "dropped"

    val states: List<String> = listOf(
        PROPOSED, NEEDS_INFO, READY, CLAIMED, DOING, REVIEW, BLOCKED, DONE, DROPPED,
    )

    private val transitions: Map<String, Set<String>> = mapOf(
        PROPOSED to setOf(NEEDS_INFO, READY, CLAIMED, DROPPED),
        NEEDS_INFO to setOf(READY, DROPPED),
        READY to setOf(CLAIMED, BLOCKED, DROPPED),
        CLAIMED to setOf(DOING, BLOCKED, DROPPED),
        DOING to setOf(REVIEW, BLOCKED, DROPPED),
        REVIEW to setOf(DONE, DOING),
        BLOCKED to setOf(DOING, READY, DROPPED),
        DONE to emptySet(),
        DROPPED to emptySet(),
    )

    fun canMove(from: String, to: String): Boolean = transitions[from]?.contains(to) == true

    fun isOpen(state: String): Boolean = state != DONE && state != DROPPED

    /** 这一步是否需要另一名成员验收（避免自己批自己）。 */
    fun needsReview(from: String, to: String): Boolean = from == DOING && to == REVIEW

    /** 卡住且写明了缺人 —— 招聘申请的合法依据。 */
    fun isStaffingGap(state: String, note: String?): Boolean {
        if (state != BLOCKED) return false
        val text = note.orEmpty()
        return listOf("缺人", "没人", "人手", "招聘", "增员", "staff").any { text.contains(it, ignoreCase = true) }
    }
}
