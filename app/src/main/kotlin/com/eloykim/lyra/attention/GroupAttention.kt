package com.eloykim.lyra.attention

/**
 * 群聊里的「群体注意力」（多 Agent 的发言仲裁与预算切片）。
 *
 * 单 Agent 的注意力是「看什么」；群体的注意力多两个问题：
 * 1. **谁说话**——不能每条消息都全员响应（否则是烧钱机器）；
 * 2. **谁花多少**——群预算要切给成员，且切片要可解释。
 *
 * 这里只做纯计算，调度与持久化由上层负责。
 */
data class GroupMember(
    val id: Long,
    val name: String,
    /** 角色的基础权重（协调者/财务更高）。 */
    val weight: Double = 0.5,
    val spentTokens: Long = 0,
    val budgetTokens: Long = 0,
)

data class Speaker(
    val member: GroupMember,
    val salience: Double,
    val reason: String,
)

object GroupAttention {

    /** 被 @ 的成员获得的最小显著性加成。 */
    private const val MENTION_BONUS = 0.6

    /** 讨论尚未涉及的成员获得的新鲜度加成上限。 */
    private const val NOVELTY_WEIGHT = 0.25

    /** 花掉超过这个比例后开始降权。 */
    private const val BUDGET_PRESSURE = 0.8

    /**
     * 按显著性排序，返回可能发言的成员。
     *
     * @param mentioned 被显式 @ 的成员名字
     * @param participants 本轮已经发过言的成员 id（降低重复发言）
     */
    fun rank(
        members: List<GroupMember>,
        mentioned: Set<String> = emptySet(),
        participants: Set<Long> = emptySet(),
    ): List<Speaker> = members
        .map { member ->
            val mentionedHere = member.name in mentioned
            val novelty = if (member.id in participants) 0.0 else NOVELTY_WEIGHT
            val pressure = if (member.budgetTokens > 0 &&
                member.spentTokens.toDouble() / member.budgetTokens >= BUDGET_PRESSURE
            ) {
                -0.3
            } else {
                0.0
            }
            val salience = (if (mentionedHere) MENTION_BONUS else 0.0) +
                member.weight * 0.4 +
                novelty +
                pressure
            val reason = when {
                mentionedHere -> "被点名"
                member.id in participants -> "已发过言，降权"
                pressure < 0 -> "预算已用掉 80% 以上"
                novelty > 0 -> "尚未发言"
                else -> "常规轮询"
            }
            Speaker(member, salience, reason)
        }
        .sortedByDescending { it.salience }

    /** 只保留显著性达到阈值的发言人，避免全员响应。 */
    fun select(
        members: List<GroupMember>,
        mentioned: Set<String> = emptySet(),
        participants: Set<Long> = emptySet(),
        threshold: Double = 0.35,
        maxSpeakers: Int = 3,
    ): List<Speaker> = rank(members, mentioned, participants)
        .filter { it.salience >= threshold }
        .take(maxSpeakers)

    /**
     * 把群预算均分给成员；余数给权重最高者，保证总和精确等于预算。
     */
    fun budgetShares(totalTokens: Long, members: List<GroupMember>): Map<Long, Long> {
        if (members.isEmpty() || totalTokens <= 0) return members.associate { it.id to 0L }
        val base = totalTokens / members.size
        val remainder = totalTokens - base * members.size
        val shares = members.associate { it.id to base }.toMutableMap()
        if (remainder > 0) {
            val top = members.maxByOrNull { it.weight } ?: members.first()
            shares[top.id] = (shares[top.id] ?: 0L) + remainder
        }
        return shares
    }

    /**
     * 角色可见的能力切片：只暴露该角色被授权的 `service.method`。
     *
     * 未知前缀一律不暴露（失败关闭），避免新服务默认对所有角色开放。
     */
    fun capabilitySlice(
        catalog: List<String>,
        allowedPrefixes: List<String>,
    ): List<String> {
        if (allowedPrefixes.isEmpty()) return emptyList()
        return catalog.filter { entry ->
            allowedPrefixes.any { prefix -> entry == prefix || entry.startsWith("$prefix.") }
        }
    }

    /**
     * 单个成员的可用额度。
     *
     * - 显式额度优先；
     * - 部长拿两倍均分，但不超过「扣除其他人均分后的剩余」；
     * - 其余人拿均分。
     */
    fun memberShare(
        totalTokens: Long,
        memberId: Long,
        members: List<GroupMember>,
        quota: Long,
        isMinister: Boolean,
    ): Long {
        if (totalTokens <= 0 || members.isEmpty()) return 0L
        if (quota > 0) return quota
        val base = totalTokens / members.size
        if (!isMinister) return base
        val reservedForOthers = base * (members.size - 1)
        val remaining = totalTokens - reservedForOthers
        return minOf(base * 2, remaining)
    }
}