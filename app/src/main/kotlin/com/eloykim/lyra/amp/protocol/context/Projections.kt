package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * 投影进 context AST（对应 `src/context/projections.ts`）。
 *
 * 这些适配器刻意是单向的：AgentContext 与 ContextWindow 保持原样，AST 成为共同
 * 视图。摘要进入 prompt；记忆正文留在 detail ref 之后。
 */
data class AgentContextProjection(
    val ast: ContextAst,
    val details: DetailStore,
)

private fun truncate(text: String, limit: Int = 180): String =
    if (text.length <= limit) text else text.substring(0, limit - 3) + "..."

private data class GroupSpec(
    val id: String,
    val kind: ContextNodeKind,
    val summary: String,
    val salience: Double,
)

fun agentContextToAst(
    context: AgentContext,
    details: DetailStore = MemoryDetailStore(),
): AgentContextProjection {
    val ast = ContextAst()
    var order = 0
    ast.add(
        ContextAstNode(
            id = "agent.context",
            kind = ContextNodeKind.GOAL,
            summary = "${context.profile.name} | ${context.profile.lifeChapter} | " +
                "role=${context.activeRole?.role?.label ?: "none"} | memories=${context.memories.size}",
            order = order++,
            salience = 1.0,
        ),
    )

    val groups: List<GroupSpec> = listOf(
        GroupSpec("agent.self", ContextNodeKind.FRAME, "self narrative and constraints", 0.9),
        GroupSpec("agent.role", ContextNodeKind.FRAME, context.activeRole?.role?.label ?: "no active role", 0.8),
        GroupSpec("agent.affect", ContextNodeKind.OBSERVATION, context.affect?.mood ?: "affect unknown", 0.5),
        GroupSpec("agent.memory", ContextNodeKind.FRAME, "${context.memories.size} memories", 0.9),
        GroupSpec("agent.relationship", ContextNodeKind.FRAME, "${context.relationships.size} relationships", 0.4),
        GroupSpec("agent.membership", ContextNodeKind.FRAME, "${context.memberships.size} memberships", 0.3),
        GroupSpec("agent.background", ContextNodeKind.OBSERVATION, "${context.background.size} background processes", 0.3),
    )
    for (group in groups) {
        ast.add(
            ContextAstNode(
                id = group.id,
                kind = group.kind,
                summary = group.summary,
                order = order++,
                parentId = "agent.context",
                salience = group.salience,
            ),
        )
    }

    for (segment in context.segments) {
        val groupId: String = when (segment.kind) {
            AgentContextKind.SELF -> "agent.self"
            AgentContextKind.ROLE -> "agent.role"
            AgentContextKind.AFFECT -> "agent.affect"
            AgentContextKind.MEMORY -> "agent.memory"
            AgentContextKind.RELATIONSHIP -> "agent.relationship"
            AgentContextKind.MEMBERSHIP -> "agent.membership"
            AgentContextKind.BACKGROUND -> "agent.background"
        }
        ast.add(
            ContextAstNode(
                id = "$groupId.segment.$order",
                kind = if (segment.kind == AgentContextKind.MEMORY) {
                    ContextNodeKind.OBSERVATION
                } else {
                    ContextNodeKind.FRAME
                },
                summary = truncate(segment.text),
                order = order++,
                parentId = groupId,
                salience = 0.6,
            ),
        )
    }

    for (memory in context.memories) {
        val ref: String = memory.id
        details.put(ref, memory.content)
        ast.add(
            ContextAstNode(
                id = "agent.memory.${memory.id}",
                kind = ContextNodeKind.OBSERVATION,
                summary = "[${memory.kind}] ${truncate(memory.content, 120)}",
                order = order++,
                parentId = "agent.memory",
                detailRef = ref,
                detailTokens = (memory.content.length + 3) / 4,
                salience = minOf(1.0, memory.salience + 0.2),
            ),
        )
    }

    for (relationship in context.relationships) {
        ast.add(
            ContextAstNode(
                id = "agent.relationship.${relationship.id}",
                kind = ContextNodeKind.OBSERVATION,
                summary = "${relationship.kind}: trust ${fixedTwo(relationship.trust)} " +
                    "attachment ${fixedTwo(relationship.attachment)}",
                order = order++,
                parentId = "agent.relationship",
                salience = relationship.trust,
            ),
        )
    }
    for (membership in context.memberships) {
        ast.add(
            ContextAstNode(
                id = "agent.membership.${membership.institution.id}",
                kind = ContextNodeKind.OBSERVATION,
                summary = "${membership.institution.name}: ${membership.role} (${membership.status})",
                order = order++,
                parentId = "agent.membership",
                salience = 0.4,
            ),
        )
    }
    for (process in context.background) {
        ast.add(
            ContextAstNode(
                id = "agent.background.${process.id}",
                kind = ContextNodeKind.OBSERVATION,
                summary = "${process.kind}: ${process.label} intensity=${fixedTwo(process.intensity)}",
                order = order++,
                parentId = "agent.background",
                salience = process.intensity,
            ),
        )
    }

    // Root -> groups；role -> memory 是 support 边，不是包含关系。
    for (group in groups.drop(1)) {
        ast.link(ContextAstEdge("agent.context", group.id, ContextEdgeKind.BEFORE))
    }
    ast.link(ContextAstEdge("agent.role", "agent.memory", ContextEdgeKind.SUPPORTS))
    return AgentContextProjection(ast, details)
}

/**
 * 分页窗口的投影：页内容就是摘要，页 ID 就是 ref。
 *
 * 说明：参考实现的 `ContextWindow` 属于 `window.*` 模块（未移植），这里用
 * `WindowView` 表达投影实际读取的同一组字段。
 */
data class WindowPage(
    val id: String,
    val objectKind: String,
    val objectId: String,
    val tokens: Int,
    val pinned: Int = 0,
    val lastUsed: Long = 0,
    val content: JsonValue? = null,
)

data class WindowView(
    val pages: List<WindowPage>,
    val capacityTokens: Int,
) {

    fun entries(): List<WindowPage> = pages

    val usedTokens: Int get() = pages.sumOf { it.tokens }
}

fun windowToAst(window: WindowView): ContextAst {
    val ast = ContextAst()
    ast.add(
        ContextAstNode(
            id = "window",
            kind = ContextNodeKind.GOAL,
            summary = "${window.entries().size} resident pages, ${window.usedTokens}/${window.capacityTokens} tokens",
            order = 0,
            salience = 1.0,
        ),
    )
    var order = 1
    for (page in window.entries()) {
        val summary: String = page.content?.let { truncate(CanonicalJson.encode(it), 160) }
            ?: "${page.objectKind}:${page.objectId}"
        ast.add(
            ContextAstNode(
                id = "window.page.${page.id}",
                kind = ContextNodeKind.OBSERVATION,
                summary = summary,
                order = order++,
                parentId = "window",
                detailRef = page.id,
                detailTokens = page.tokens,
                salience = if (page.pinned > 0) 1.0 else 0.5,
            ),
        )
    }
    return ast
}

/** `toFixed(2)` 的等价形式（与 AgentContext 文本渲染共用）。 */
internal fun fixedTwo(value: Double): String =
    String.format(java.util.Locale.ROOT, "%.2f", value)
