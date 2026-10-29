package com.eloykim.lyra.attention

/**
 * 群聊的组织结构：**董事长（用户）— 总经理 — 成员**。
 *
 * - 董事长（用户本人）拥有全部权力，不受座位限制；
 * - 总经理由**预分配的核心席位**决定，负责**发言控制**与人事（增减成员、分配角色）；
 * - 财务、记录者分别负责预算与历史，互不越权。
 *
 * 判定不再依赖角色名字——席位是建群时定好的，换人只换坐的人。
 */
enum class StaffingAction {
    ADD_MEMBER, REMOVE_MEMBER, ASSIGN_ROLE, CREATE_ROLE;

    companion object {
        fun of(method: String): StaffingAction? = when (method) {
            "add_agent" -> ADD_MEMBER
            "remove_agent" -> REMOVE_MEMBER
            "assign_role", "update_agent" -> ASSIGN_ROLE
            "create_role", "update_role", "delete_role" -> CREATE_ROLE
            else -> null
        }
    }
}

object StaffingPolicy {

    /** 人事请求的关键词。 */
    private val INTENT_HINTS = listOf(
        "加一个", "加个", "新增", "添加", "拉一个", "去掉", "删除", "移除", "踢掉",
        "换角色", "换掉", "换成", "分配", "任命", "设为", "改成", "职责",
    )

    /** 这句话是否在要求变更人员/角色。 */
    fun isStaffingRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return false
        return INTENT_HINTS.any { normalized.contains(it) }
    }

    /** 总经理的成员 id（由预分配席位决定，成员按 id 升序坐席）。 */
    fun generalManager(memberIds: List<Long>): Long? =
        GroupSeats.preset(memberIds).holder(CoreSeat.GENERAL_MANAGER)

    /**
     * 越权检查：只有总经理可以动人事；董事长（`actorId == null`）不受限。
     */
    fun mayStaff(actorId: Long?, memberIds: List<Long>): Boolean {
        if (actorId == null) return true
        return generalManager(memberIds) == actorId
    }

    /** 总经理的职责说明：只描述职责，不写实现。 */
    fun generalManagerBrief(): String = buildString {
        append("你是总经理，负责三件事：①控制谁发言（set_reply_policy）；")
        append("②成员增减与角色分配（add_agent / remove_agent / assign_role / create_role）；")
        append("③向董事长汇报。")
        append("用户提出人事或发言规则要求时，直接调用对应服务完成，不要回复「请手动设置」。")
        append("董事长保留最终否决权。")
    }
}
