package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import java.io.File

/**
 * Context AST：树结构 + 有类型的优先级边。
 *
 * 对应 `src/context/ast.ts`。摘要进入 prompt，细节留在本地 ref 之后；这是
 * context 的一个视图，不是新的运行时。强制的优先级边只有 `before` / `depends`，
 * 描述性关系（`project_depends_on` / `imports` / `calls` / `references` / `documents`）
 * 允许成环。
 */
enum class ContextNodeKind(val wire: String) {
    GOAL("goal"),
    FRAME("frame"),
    TURN("turn"),
    OBSERVATION("observation"),
    EVIDENCE("evidence"),
    HYPOTHESIS("hypothesis"),
    DECISION("decision"),
    DETAIL("detail"),
    PROJECT("project"),
    DIRECTORY("directory"),
    FILE("file"),
    SYMBOL("symbol"),
    PACKAGE("package"),
    KNOWLEDGE("knowledge");

    companion object {
        fun fromWire(value: String): ContextNodeKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown context node kind: $value")
    }
}

enum class ContextEdgeKind(val wire: String) {
    BEFORE("before"),
    AFTER("after"),
    DEPENDS("depends"),
    SUPPORTS("supports"),
    CONTRADICTS("contradicts"),
    PROJECT_DEPENDS_ON("project_depends_on"),
    IMPORTS("imports"),
    CALLS("calls"),
    REFERENCES("references"),
    DOCUMENTS("documents");

    companion object {
        fun fromWire(value: String): ContextEdgeKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown context edge kind: $value")
    }
}

enum class EdgeDirection(val wire: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing");

    companion object {
        fun fromWire(value: String): EdgeDirection =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown edge direction: $value")
    }
}

data class ContextAstNode(
    val id: String,
    val kind: ContextNodeKind,
    val summary: String,
    val order: Int,
    val parentId: String? = null,
    val detailRef: String? = null,
    val detailTokens: Int? = null,
    val salience: Double? = null,
    val createdAt: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "parentId" to parentId?.let { JsonValue.Str(it) },
        "kind" to JsonValue.Str(kind.wire),
        "summary" to JsonValue.Str(summary),
        "detailRef" to detailRef?.let { JsonValue.Str(it) },
        "detailTokens" to detailTokens?.let { jsonNumber(it) },
        "order" to jsonNumber(order),
        "salience" to salience?.let { jsonNumber(it) },
        "createdAt" to createdAt?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ContextAstNode {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("context node 必须是 JSON 对象")
            return ContextAstNode(
                id = obj.requireString("id"),
                kind = ContextNodeKind.fromWire(obj.requireString("kind")),
                summary = obj.requireString("summary"),
                order = obj.requireInt("order"),
                parentId = obj.string("parentId"),
                detailRef = obj.string("detailRef"),
                detailTokens = obj.int("detailTokens"),
                salience = obj.double("salience"),
                createdAt = obj.string("createdAt"),
            )
        }
    }
}

data class ContextAstEdge(
    val from: String,
    val to: String,
    val kind: ContextEdgeKind,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "from" to JsonValue.Str(from),
        "to" to JsonValue.Str(to),
        "kind" to JsonValue.Str(kind.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): ContextAstEdge {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("context edge 必须是 JSON 对象")
            return ContextAstEdge(
                from = obj.requireString("from"),
                to = obj.requireString("to"),
                kind = ContextEdgeKind.fromWire(obj.requireString("kind")),
            )
        }
    }
}

enum class OmissionReason(val wire: String) {
    BUDGET("budget"),
    MISSING_PARENT("missing_parent");

    companion object {
        fun fromWire(value: String): OmissionReason =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown omission reason: $value")
    }
}

data class ContextOmission(
    val id: String,
    val reason: OmissionReason,
    val requiredIds: List<String>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "reason" to JsonValue.Str(reason.wire),
        "requiredIds" to jsonArr(*requiredIds.map { JsonValue.Str(it) }.toTypedArray()),
    )

    companion object {
        fun fromJson(value: JsonValue): ContextOmission {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("context omission 必须是 JSON 对象")
            return ContextOmission(
                id = obj.requireString("id"),
                reason = OmissionReason.fromWire(obj.requireString("reason")),
                requiredIds = obj.requireArr("requiredIds").items.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("omission.requiredIds 必须是字符串")
                },
            )
        }
    }
}

data class ContextAstView(
    val nodes: List<ContextAstNode>,
    val tokens: Int,
    val budgetTokens: Int,
    val excluded: List<ContextAstNode>,
    val refs: List<String>,
    val relations: List<ContextAstEdge>,
    val unresolvedDependencies: List<ContextAstEdge>,
    val omissions: List<ContextOmission>,
    val text: String,
)

data class ContextFlatView(val text: String, val tokens: Int)

data class ContextAstSnapshot(
    val nodes: List<ContextAstNode>,
    val edges: List<ContextAstEdge>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "nodes" to jsonArr(*nodes.map { it.toJson() }.toTypedArray()),
        "edges" to jsonArr(*edges.map { it.toJson() }.toTypedArray()),
    )

    companion object {
        fun fromJson(value: JsonValue): ContextAstSnapshot {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("context ast 必须是 JSON 对象")
            return ContextAstSnapshot(
                nodes = obj.requireArr("nodes").items.map(ContextAstNode::fromJson),
                edges = obj.requireArr("edges").items.map(ContextAstEdge::fromJson),
            )
        }
    }
}

/** 细节存储：`id -> content`，返回可作为 `detailRef` 的引用。 */
interface DetailStore {

    fun put(id: String, content: String): String

    fun get(ref: String): String?
}

class MemoryDetailStore : DetailStore {

    private val details: MutableMap<String, String> = LinkedHashMap()

    override fun put(id: String, content: String): String {
        details[id] = content
        return id
    }

    override fun get(ref: String): String? = details[ref]
}

class FileDetailStore(dir: File) : DetailStore {

    private val dir: File = canonicalStoreDirectory(dir)

    override fun put(id: String, content: String): String {
        val hash: String = Sha256.hex(id)
        val ref: String = File(this.dir, "$hash.txt").path
        if (storedFile(this.dir, ref) == null) {
            val path = File(ref)
            if (path.createNewFile()) {
                path.writeText(content, Charsets.UTF_8)
            }
            return ref
        }
        File(ref).writeText(content, Charsets.UTF_8)
        return ref
    }

    override fun get(ref: String): String? {
        val path: File = storedFile(dir, ref) ?: return null
        return path.readText(Charsets.UTF_8)
    }
}

/** `JSON.stringify(string)` 的等价形式（用于渲染关系行与投影摘要）。 */
internal fun jsonStringLiteral(value: String): String =
    CanonicalJson.encode(JsonValue.Str(value))

/**
 * 树 + 索引后的有类型边。
 *
 * 与参考实现一致：父节点可以后到（包括 restore 期间），但层级永远不能闭合环；
 * `order` 用插入顺序稳定地打破 `before`/`depends` 的平局。
 */
class ContextAst {

    private val nodes: MutableMap<String, ContextAstNode> = LinkedHashMap()
    private val edges: MutableList<ContextAstEdge> = ArrayList()
    private val childrenOf: MutableMap<String, MutableList<String>> = LinkedHashMap()
    private val incoming: MutableMap<String, MutableMap<ContextEdgeKind, MutableList<ContextAstEdge>>> = LinkedHashMap()
    private val outgoing: MutableMap<String, MutableMap<ContextEdgeKind, MutableList<ContextAstEdge>>> = LinkedHashMap()
    private val precedenceOutgoing: MutableMap<String, MutableList<ContextAstEdge>> = LinkedHashMap()
    private val insertionOrder: MutableMap<String, Int> = LinkedHashMap()

    val size: Int get() = nodes.size

    fun add(node: ContextAstNode): ContextAst {
        require(!nodes.containsKey(node.id)) { "duplicate context node ${node.id}" }
        // 父节点可以后到（包括 restore 期间），但不能闭合环。
        var parent: String? = node.parentId
        val seen: MutableSet<String> = HashSet()
        while (parent != null && !seen.contains(parent)) {
            if (parent == node.id) {
                throw IllegalArgumentException("context hierarchy contains a cycle")
            }
            seen.add(parent)
            parent = nodes[parent]?.parentId
        }
        insertionOrder[node.id] = nodes.size
        nodes[node.id] = node
        node.parentId?.let { parentId ->
            childrenOf.getOrPut(parentId) { ArrayList() }.add(node.id)
        }
        return this
    }

    fun link(edge: ContextAstEdge): ContextAst {
        if (!nodes.containsKey(edge.from) || !nodes.containsKey(edge.to)) {
            throw IllegalArgumentException("edge references unknown node: ${edge.from}->${edge.to}")
        }
        edges.add(edge)
        indexEdge(outgoing, edge.from, edge)
        indexEdge(incoming, edge.to, edge)
        if (edge.kind == ContextEdgeKind.BEFORE || edge.kind == ContextEdgeKind.DEPENDS) {
            precedenceOutgoing.getOrPut(edge.from) { ArrayList() }.add(edge)
        }
        return this
    }

    private fun indexEdge(
        index: MutableMap<String, MutableMap<ContextEdgeKind, MutableList<ContextAstEdge>>>,
        id: String,
        edge: ContextAstEdge,
    ) {
        index.getOrPut(id) { LinkedHashMap() }.getOrPut(edge.kind) { ArrayList() }.add(edge)
    }

    /** 一跳关系；返回值无法修改已存储的边。 */
    fun relations(id: String, direction: EdgeDirection, kind: ContextEdgeKind? = null): List<ContextAstEdge> {
        val groups = (if (direction == EdgeDirection.INCOMING) incoming else outgoing)[id]
            ?: return emptyList()
        if (kind == null) {
            return groups.values.flatten()
        }
        return groups[kind]?.toList() ?: emptyList()
    }

    fun node(id: String): ContextAstNode? = nodes[id]

    fun children(id: String): List<ContextAstNode> =
        (childrenOf[id] ?: emptyList()).mapNotNull { nodes[it] }

    fun ancestors(id: String): List<ContextAstNode> {
        val result: MutableList<ContextAstNode> = ArrayList()
        var current: String? = nodes[id]?.parentId
        val seen: MutableSet<String> = HashSet()
        while (current != null && !seen.contains(current)) {
            seen.add(current)
            val node: ContextAstNode = nodes[current] ?: break
            result.add(node)
            current = node.parentId
        }
        result.reverse()
        return result
    }

    fun descendants(id: String): List<ContextAstNode> {
        val result: MutableList<ContextAstNode> = ArrayList()
        val stack: MutableList<String> = ArrayList(childrenOf[id] ?: emptyList())
        val seen: MutableSet<String> = HashSet()
        seen.add(id)
        while (stack.isNotEmpty()) {
            val current: String = stack.removeAt(stack.size - 1)
            if (seen.contains(current)) {
                continue
            }
            seen.add(current)
            val node: ContextAstNode = nodes[current] ?: continue
            result.add(node)
            childrenOf[current]?.let { stack.addAll(it) }
        }
        return result
    }

    /**
     * 优先级顺序；`before` / `depends` 约束 from -> to。
     * 传入 ID 时只取它们的子图：外部依赖不会扩大范围。
     * 初始就绪节点按插入顺序，被释放的节点按 link 顺序。
     */
    fun order(nodeIds: List<String>? = null): List<ContextAstNode> {
        val candidates: List<String> = if (nodeIds == null) {
            nodes.keys.toList()
        } else {
            nodeIds.distinct()
                .filter { nodes.containsKey(it) }
                .sortedBy { insertionOrder.getValue(it) }
        }
        val indegree: MutableMap<String, Int> = LinkedHashMap()
        candidates.forEach { indegree[it] = 0 }
        val precedenceKinds: List<ContextEdgeKind> = listOf(ContextEdgeKind.BEFORE, ContextEdgeKind.DEPENDS)
        for (id in candidates) {
            for (kind in precedenceKinds) {
                val list: List<ContextAstEdge> = incoming[id]?.get(kind) ?: continue
                for (edge in list) {
                    if (!indegree.containsKey(edge.from)) {
                        continue
                    }
                    indegree[edge.to] = indegree.getValue(edge.to) + 1
                }
            }
        }
        val queue: ArrayDeque<String> = ArrayDeque(candidates.filter { indegree.getValue(it) == 0 })
        val result: MutableList<ContextAstNode> = ArrayList()
        while (queue.isNotEmpty()) {
            val id: String = queue.removeFirst()
            nodes[id]?.let { result.add(it) }
            for (edge in precedenceOutgoing[id] ?: emptyList()) {
                val next: String = edge.to
                if (!indegree.containsKey(next)) {
                    continue
                }
                val remaining: Int = indegree.getValue(next) - 1
                indegree[next] = remaining
                if (remaining == 0) {
                    queue.addLast(next)
                }
            }
        }
        if (result.size != candidates.size) {
            throw IllegalStateException("context precedence graph contains a cycle")
        }
        return result
    }

    /**
     * 在 token 预算下选择祖先/依赖包；细节留在 CAS。
     * `targetIds` 限制可选范围；范围外的依赖只被报告，不会被读取。
     * 节点与显式关系行都会在提交一个包之前计费。
     */
    fun view(budgetTokens: Int, targetIds: List<String>? = null): ContextAstView {
        require(budgetTokens > 0) { "budgetTokens must be positive" }
        val eligible: MutableSet<String>? = if (targetIds == null) null else LinkedHashSet()
        if (targetIds != null) {
            val scope: MutableSet<String> = eligible!!
            targetIds.forEach { start ->
                var current: String? = start
                while (current != null && !scope.contains(current)) {
                    scope.add(current)
                    current = nodes[current]?.parentId
                }
            }
        }
        val ordered: List<ContextAstNode> = order(eligible?.toList())
        // 每次视图的缓存：观察当前的 summary/ref，无需长期失效逻辑。
        val depths: MutableMap<String, Int> = HashMap()
        val rendered: MutableMap<String, String> = HashMap()

        fun depthOf(node: ContextAstNode): Int {
            val path: MutableList<ContextAstNode> = ArrayList()
            val seen: MutableSet<String> = HashSet()
            var current: ContextAstNode? = node
            while (current != null && !depths.containsKey(current.id)) {
                if (seen.contains(current.id)) {
                    throw IllegalStateException("context hierarchy contains a cycle")
                }
                seen.add(current.id)
                path.add(current)
                current = current.parentId?.let { nodes[it] }
            }
            var depth: Int = if (current == null) -1 else depths.getValue(current.id)
            while (path.isNotEmpty()) {
                depth += 1
                depths[path.removeAt(path.size - 1).id] = depth
            }
            return depths.getValue(node.id)
        }

        fun render(node: ContextAstNode): String {
            rendered[node.id]?.let { return it }
            val ref: String = node.detailRef?.let { " ref=$it" } ?: ""
            val line: String = "${"  ".repeat(depthOf(node))}- [${node.kind.wire}] ${node.id}: ${node.summary}$ref"
            rendered[node.id] = line
            return line
        }

        // 参考实现的 PriorityQueue 在优先级相同时按入队顺序服务；
        // salience 越大越先出队，平局按插入顺序。
        val queue: ArrayDeque<ContextAstNode> = ArrayDeque(
            ordered.sortedWith(
                compareByDescending<ContextAstNode> { it.salience ?: 0.0 }
                    .thenBy { insertionOrder.getValue(it.id) },
            ),
        )
        val candidates: MutableSet<String> = ordered.map { it.id }.toHashSet()
        val selected: MutableSet<String> = LinkedHashSet()
        val relations: MutableMap<String, ContextAstEdge> = LinkedHashMap()
        val gaps: MutableMap<String, ContextAstEdge> = LinkedHashMap()
        val omissions: MutableMap<String, ContextOmission> = LinkedHashMap()

        fun edgeKey(edge: ContextAstEdge): String = CanonicalJson.encode(
            jsonArr(
                JsonValue.Str(edge.kind.wire),
                JsonValue.Str(edge.from),
                JsonValue.Str(edge.to),
            ),
        )

        fun renderEdge(edge: ContextAstEdge, unresolved: Boolean = false): String =
            "- [relation:${edge.kind.wire}] ${jsonStringLiteral(edge.from)} -> ${jsonStringLiteral(edge.to)}" +
                if (unresolved) " (outside_scope; prerequisite unavailable)" else ""

        var used: Int = 0
        while (queue.isNotEmpty()) {
            val node: ContextAstNode = queue.removeFirst()
            if (selected.contains(node.id)) {
                continue
            }
            val bundle: MutableMap<String, ContextAstNode> = LinkedHashMap()
            val missingParents: MutableSet<String> = LinkedHashSet()
            val pending: MutableList<String> = ArrayList()
            pending.add(node.id)
            while (pending.isNotEmpty()) {
                val id: String = pending.removeAt(pending.size - 1)
                if (selected.contains(id) || bundle.containsKey(id)) {
                    continue
                }
                val member: ContextAstNode? = nodes[id]
                if (member == null) {
                    missingParents.add(id)
                    continue
                }
                bundle[id] = member
                member.parentId?.let { pending.add(it) }
                incoming[id]?.get(ContextEdgeKind.DEPENDS)?.forEach { edge ->
                    if (candidates.contains(edge.from)) {
                        pending.add(edge.from)
                    }
                }
            }
            if (missingParents.isNotEmpty()) {
                omissions[node.id] = ContextOmission(node.id, OmissionReason.MISSING_PARENT, missingParents.toList())
                continue
            }
            val newRelations: MutableMap<String, ContextAstEdge> = LinkedHashMap()
            val newGaps: MutableMap<String, ContextAstEdge> = LinkedHashMap()

            fun included(id: String): Boolean = selected.contains(id) || bundle.containsKey(id)

            var cost: Int = 0
            bundle.values.forEach { member ->
                cost += estimateTokens(render(member) + "\n")
                val adjacent: MutableList<ContextAstEdge> = ArrayList()
                incoming[member.id]?.values?.forEach { adjacent.addAll(it) }
                outgoing[member.id]?.values?.forEach { adjacent.addAll(it) }
                adjacent.forEach { edge ->
                    val key: String = edgeKey(edge)
                    if (included(edge.from) && included(edge.to)) {
                        if (!relations.containsKey(key) && !newRelations.containsKey(key)) {
                            newRelations[key] = edge
                            cost += estimateTokens(renderEdge(edge) + "\n")
                        }
                    } else if (edge.kind == ContextEdgeKind.DEPENDS &&
                        included(edge.to) &&
                        !candidates.contains(edge.from) &&
                        !gaps.containsKey(key) &&
                        !newGaps.containsKey(key)
                    ) {
                        newGaps[key] = edge
                        cost += estimateTokens(renderEdge(edge, true) + "\n")
                    }
                }
            }
            if (used + cost > budgetTokens) {
                omissions[node.id] = ContextOmission(node.id, OmissionReason.BUDGET, bundle.keys.toList())
                continue
            }
            selected.addAll(bundle.keys)
            relations.putAll(newRelations)
            gaps.putAll(newGaps)
            used += cost
        }
        val selectedNodes: List<ContextAstNode> = ordered.filter { selected.contains(it.id) }
        val excluded: List<ContextAstNode> = ordered.filter { !selected.contains(it.id) }
        val lines: MutableList<String> = ArrayList()
        selectedNodes.forEach { lines.add(render(it)) }
        relations.values.forEach { lines.add(renderEdge(it)) }
        gaps.values.forEach { lines.add(renderEdge(it, true)) }
        return ContextAstView(
            nodes = selectedNodes,
            tokens = used,
            budgetTokens = budgetTokens,
            excluded = excluded,
            refs = selectedNodes.mapNotNull { it.detailRef },
            relations = relations.values.toList(),
            unresolvedDependencies = gaps.values.toList(),
            omissions = excluded.map { omissions.getValue(it.id) },
            text = lines.joinToString("\n"),
        )
    }

    /** 基线：摘要 + 全部细节，不做检索。 */
    fun flat(store: DetailStore): ContextFlatView {
        val parts: MutableList<String> = ArrayList()
        for (node in order()) {
            parts.add("[${node.kind.wire}] ${node.id}: ${node.summary}")
            val ref: String? = node.detailRef
            if (ref != null) {
                store.get(ref)?.let { parts.add(it) }
            }
        }
        val text: String = parts.joinToString("\n")
        return ContextFlatView(text, estimateTokens(text))
    }

    fun expand(nodeId: String, store: DetailStore): String? {
        val ref: String = nodes[nodeId]?.detailRef ?: return null
        return store.get(ref)
    }

    fun serialize(): ContextAstSnapshot = ContextAstSnapshot(nodes.values.toList(), edges.toList())

    companion object {

        fun restore(input: ContextAstSnapshot): ContextAst {
            val ast = ContextAst()
            input.nodes.forEach { ast.add(it) }
            input.edges.forEach { ast.link(it) }
            return ast
        }
    }
}
