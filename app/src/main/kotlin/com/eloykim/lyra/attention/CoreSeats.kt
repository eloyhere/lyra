package com.eloykim.lyra.attention

/**
 * 核心职位（Core Seats）：群的运营权**预先分配**，而不是运行时按名字猜。
 *
 * 一个群在建立时就把几个固定座位分配出去；之后只有坐在位上的人能行使对应权力：
 *
 * | 职位 | 权力 | 对应服务 |
 * |---|---|---|
 * | 总经理 | 发言控制、增减成员、分配角色 | `groupchat.set_reply_policy / send / add_agent / remove_agent` |
 * | 财务 | 预算 | `groupchat.set_budget / budget` |
 * | 记录者 | 清空/导出历史 | `groupchat.clear_history` |
 *
 * 座位是**固定槽位**：换人只换坐的人，不新增权力；空位时权力回收（失败关闭）。
 */
enum class CoreSeat(val title: String) {
    GENERAL_MANAGER("总经理"),
    FINANCE("财务"),
    RECORDER("记录者");   // 程序担任：自动把消息与事件写入数据库

    /** 该职位被允许的服务方法（失败关闭：未列出的不开放）。 */
    val capabilities: List<String>
        get() = when (this) {
            GENERAL_MANAGER -> listOf(
                // 发言控制
                "groupchat.set_reply_policy",
                "groupchat.send",
                // 人事
                "groupchat.add_agent",
                "groupchat.remove_agent",
                "groupchat.assign_role",
                "groupchat.create_role",
                "groupchat.update_role",
                "groupchat.delete_role",
            )

            FINANCE -> listOf(
                "groupchat.set_budget",
                "groupchat.budget",
            )

            RECORDER -> listOf(
                "groupchat.clear_history",
                "groupchat.messages",
                "groupchat.information",
            )
        }
}

/**
 * 一个群当前的座位分配。
 *
 * [assignments] 只记录「座位 → 成员 id」；成员被移除时由上层调用 [vacate] 回收座位，
 * 避免权力落到已不存在的成员手里。
 */
data class GroupSeats(
    val assignments: Map<CoreSeat, Long> = emptyMap(),
) {

    fun holder(seat: CoreSeat): Long? = assignments[seat]

    /** 某成员是否持有某个座位。 */
    fun isHeldBy(seat: CoreSeat, agentId: Long): Boolean = assignments[seat] == agentId

    /** 某成员能行使哪些座位。 */
    fun seatsOf(agentId: Long): List<CoreSeat> =
        assignments.filterValues { it == agentId }.keys.toList()

    /** 该成员能否调用某个服务方法（按座位能力判定）。 */
    fun mayCall(agentId: Long?, method: String): Boolean {
        // 用户本人不受座位限制；成员必须坐在对应座位上
        if (agentId == null) return true
        return seatsOf(agentId).any { seat -> method in seat.capabilities }
    }

    fun vacate(agentId: Long): GroupSeats = GroupSeats(
        assignments.filterValues { it != agentId },
    )

    companion object {

        /**
         * 建群时的预分配：按成员加入顺序（id 升序）依次坐上核心职位。
         * 成员不足时职位空缺——空缺意味着该权力暂时无人可用，而不是随便找人顶。
         */
        const val SYSTEM_RECORDER = -1L

        fun preset(memberIds: List<Long>): GroupSeats {
            val ordered = memberIds.distinct().sorted()
            val assignments = LinkedHashMap<CoreSeat, Long>()
            ordered.getOrNull(0)?.let { assignments[CoreSeat.GENERAL_MANAGER] = it }
            ordered.getOrNull(1)?.let { assignments[CoreSeat.FINANCE] = it }
            assignments[CoreSeat.RECORDER] = SYSTEM_RECORDER
            return GroupSeats(assignments)
        }
    }
}
