package com.eloykim.lyra.attention

/**
 * 三层组织结构。
 *
 * 第一层：董事长（用户本人）。
 * 第二层：各部门部长——对上层汇报，对下管理本部门职员。
 * 第三层：职员——工程师、设计师、运营等，负责具体产出。
 *
 * 记录者不属于业务层级：它是程序，负责把消息与事件写入数据库。
 *
 * 职务是数据：`key` 稳定、`title` 可改，建群时全量写入 `group_positions`。
 */
data class Position(
    val key: String,
    val title: String,
    /** 1=董事长 2=部长 3=职员 0=系统（记录者）。 */
    val tier: Int = 3,
    /** 所属部门；董事长与记录者为空。 */
    val department: String = "",
    /** 该职位被允许的服务方法；空表示没有运营权。 */
    val capabilities: List<String> = emptyList(),
    /** 治理层：不可删除。 */
    val system: Boolean = false,
    val order: Int = 0,
) {
    val hasPower: Boolean get() = capabilities.isNotEmpty()
}

object Positions {

    const val CHAIRMAN = "CHAIRMAN"
    const val RECORDER = "RECORDER"

    const val ENGINEERING_MINISTER = "MINISTER_ENGINEERING"
    const val PRODUCT_MINISTER = "MINISTER_PRODUCT"
    const val OPERATIONS_MINISTER = "MINISTER_OPERATIONS"
    const val STAFF_MINISTER = "MINISTER_STAFF"

    private val MINISTER_POWERS = listOf(
        "groupchat.send",              // 召集本部门
        "groupchat.set_reply_policy",  // 决定本部门谁说话
        "groupchat.add_agent",         // 招人
        "groupchat.assign_role",       // 定岗
    )

    /** 第一层：董事长（用户）。 */
    val top: List<Position> = listOf(
        Position(
            key = CHAIRMAN,
            title = "董事长",
            tier = 1,
            system = true,
            order = 0,
        ),
        Position(
            key = "GENERAL_MANAGER",
            title = "总经理",
            tier = 1,
            capabilities = MINISTER_POWERS,
            system = true,
            order = 1,
        ),
    )

    /** 第二层：各部门部长。 */
    val ministers: List<Position> = listOf(
        Position(ENGINEERING_MINISTER, "工程部长", tier = 2, department = "工程", capabilities = MINISTER_POWERS, system = true, order = 10),
        Position(PRODUCT_MINISTER, "产品部长", tier = 2, department = "产品", capabilities = MINISTER_POWERS, system = true, order = 11),
        Position(OPERATIONS_MINISTER, "运营部长", tier = 2, department = "运营", capabilities = MINISTER_POWERS, system = true, order = 12),
        Position(STAFF_MINISTER, "职能部长", tier = 2, department = "职能", capabilities = MINISTER_POWERS, system = true, order = 13),
        Position("LEGAL_MINISTER", "法务部长", tier = 2, department = "法务", capabilities = MINISTER_POWERS, system = true, order = 14),
        Position("HR_MINISTER", "人力部长", tier = 2, department = "人力", capabilities = MINISTER_POWERS, system = true, order = 15),
        Position("FINANCE_MINISTER", "财务部长", tier = 2, department = "财务", capabilities = MINISTER_POWERS, system = true, order = 16),
        Position("RESOURCE_MINISTER", "资源部长", tier = 2, department = "资源", capabilities = MINISTER_POWERS, system = true, order = 17),
    )

    /** 第三层：职员。 */
    val staff: List<Position> = listOf(
        // 工程
        Position("BACKEND", "后端工程师", tier = 3, department = "工程", order = 20),
        Position("FRONTEND", "前端工程师", tier = 3, department = "工程", order = 21),
        Position("MOBILE", "客户端工程师", tier = 3, department = "工程", order = 22),
        Position("QA", "测试工程师", tier = 3, department = "工程", order = 23),
        Position("OPS", "运维工程师", tier = 3, department = "工程", order = 24),
        // 产品
        Position("PRODUCT", "产品经理", tier = 3, department = "产品", order = 30),
        Position("DESIGNER", "设计师", tier = 3, department = "产品", order = 31),
        Position("ANALYST", "数据分析", tier = 3, department = "产品", order = 32),
        Position("RESEARCH", "用户研究", tier = 3, department = "产品", order = 33),
        // 运营
        Position("OPERATIONS", "运营专员", tier = 3, department = "运营", order = 40),
        Position("MARKETING", "市场专员", tier = 3, department = "运营", order = 41),
        Position("SALES", "销售", tier = 3, department = "运营", order = 42),
        Position("SUPPORT", "客服", tier = 3, department = "运营", order = 43),
        Position("WRITER", "文案", tier = 3, department = "运营", order = 44),
        // 职能
        Position("FINANCE", "财务", tier = 3, department = "职能", order = 50),
        Position("LEGAL", "法务", tier = 3, department = "职能", order = 51),
        Position("HR", "人力资源", tier = 3, department = "职能", order = 52),
        Position("ADMIN", "行政", tier = 3, department = "职能", order = 53),
        // 系统
        Position(
            key = RECORDER,
            title = "记录者",
            tier = 0,
            capabilities = listOf(
                "groupchat.clear_history",
                "groupchat.messages",
                "groupchat.information",
            ),
            system = true,
            order = 99,
        ),
    )

    /** 完整预置：建群时全量写入。 */
    val defaults: List<Position> = (top + ministers + staff).sortedBy { it.order }

    fun byKey(key: String): Position? = defaults.firstOrNull { it.key == key }

    fun capabilityOf(key: String): List<String> = byKey(key)?.capabilities.orEmpty()

    /** 三层视图：董事长 / 各部部长 / 职员。 */
    fun hierarchy(): List<List<Position>> = listOf(top, ministers, staff.filter { it.tier == 3 })

    /** 每个职位一个专属图标，用于头像与标签。 */
    fun emojiOf(title: String): String = when {
        title.contains("董事长") -> "👑"
        title.contains("总经理") -> "🎯"
        title.contains("产品") -> "🧭"
        title.contains("工程") || title.contains("技术") -> "⚙️"
        title.contains("运营") -> "📣"
        title.contains("法务") -> "⚖️"
        title.contains("人力") -> "👥"
        title.contains("财务") -> "💰"
        title.contains("资源") -> "📦"
        title.contains("职能") -> "🗂️"
        title.contains("设计") -> "🎨"
        title.contains("数据") -> "📊"
        title.contains("测试") -> "🧪"
        title.contains("记录") -> "📝"
        else -> "🙂"
    }

    fun emojiOfKey(key: String?): String =
        emojiOf(byKey(key.orEmpty())?.title ?: "")
}