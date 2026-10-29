package com.eloykim.lyra.amp.protocol.evidence

import com.eloykim.lyra.amp.protocol.types.Trust

/**
 * Evidence graph: claims and the evidence that supports them.
 *
 * 边是显式的（`evidence -> claim`），所以“谁支撑这条主张”和“这条证据直接影响
 * 谁”都是一次查表。传递影响用有界 BFS 覆盖可达分量。`link` 时拒绝成环：循环
 * 论证不是证明。对应参考实现 `src/evidence/evidenceGraph.ts`。
 *
 * 节点与边的迭代顺序在参考实现里是哈希桶顺序（未指定）；这里用插入顺序作为
 * 具体的确定性选择，测试不依赖参考实现未承诺的次序。
 */
enum class EvidenceKind(val wire: String) {
    CLAIM("claim"),
    EVIDENCE("evidence"),
    ;

    companion object {
        fun fromWire(value: String): EvidenceKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 evidence kind: $value")
    }
}

data class EvidenceNode(
    val id: String,
    val kind: EvidenceKind,
    val statement: String,
    val trust: Trust? = null,
)

/** 受影响节点；[via] 是一条最短依赖见证，`null` 表示被显式点名的根。 */
data class EvidenceImpact(
    val id: String,
    val via: String?,
)

class EvidenceGraph {

    private val nodes = LinkedHashMap<String, EvidenceNode>()
    private val forward = LinkedHashMap<String, LinkedHashSet<String>>()
    private val reverse = LinkedHashMap<String, LinkedHashSet<String>>()

    fun addClaim(id: String, statement: String): EvidenceNode =
        addNode(EvidenceNode(id = id, kind = EvidenceKind.CLAIM, statement = statement))

    fun addEvidence(id: String, statement: String, forClaim: String, trust: Trust = Trust.SELF): EvidenceNode {
        if (!nodes.containsKey(forClaim)) {
            throw IllegalArgumentException("unknown evidence node $forClaim")
        }
        addSource(id, statement, trust)
        link(id, forClaim)
        return nodes.getValue(id)
    }

    fun addSource(id: String, statement: String, trust: Trust = Trust.SELF): EvidenceNode =
        addNode(EvidenceNode(id = id, kind = EvidenceKind.EVIDENCE, statement = statement, trust = trust))

    fun link(from: String, to: String) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) {
            throw IllegalArgumentException("unknown evidence node $from -> $to")
        }
        if (from == to) {
            throw IllegalArgumentException("an evidence node cannot support itself")
        }
        if (forward[from]?.contains(to) == true) return
        if (reaches(to, from)) {
            throw IllegalArgumentException("linking $from -> $to would create a cycle")
        }
        edge(forward, from, to)
        edge(reverse, to, from)
    }

    /**
     * 多源 BFS 影响闭包，包含被改动的根。
     *
     * 每个可达节点只访问一次；这是复核集合，不是“依赖的主张为假”的判决。
     */
    fun impact(changedIds: List<String>): List<EvidenceImpact> {
        changedIds.forEach { id ->
            if (!nodes.containsKey(id)) throw IllegalArgumentException("unknown evidence node $id")
        }
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<EvidenceImpact>()
        val result = ArrayList<EvidenceImpact>()
        changedIds.forEach { id ->
            if (visited.add(id)) queue.addLast(EvidenceImpact(id, null))
        }
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            result += entry
            forward[entry.id]?.forEach { id ->
                if (visited.add(id)) queue.addLast(EvidenceImpact(id, entry.id))
            }
        }
        return result
    }

    fun get(id: String): EvidenceNode? = nodes[id]

    fun evidenceFor(claimId: String): List<String> = reverse[claimId]?.toList() ?: emptyList()

    fun claimsFor(evidenceId: String): List<String> = forward[evidenceId]?.toList() ?: emptyList()

    fun hasCycle(): Boolean {
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        nodes.keys.forEach { start ->
            if (visited.contains(start)) return@forEach
            val stack = ArrayDeque<SearchFrame>()
            stack.addLast(SearchFrame(start, 0, forward[start]?.toList() ?: emptyList()))
            visiting.add(start)
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (frame.next >= frame.neighbors.size) {
                    stack.removeLast()
                    visiting.remove(frame.id)
                    visited.add(frame.id)
                    continue
                }
                val next = frame.neighbors[frame.next]
                frame.next += 1
                if (visiting.contains(next)) return true
                if (!visited.contains(next)) {
                    visiting.add(next)
                    stack.addLast(SearchFrame(next, 0, forward[next]?.toList() ?: emptyList()))
                }
            }
        }
        return false
    }

    val size: Int
        get() = nodes.size

    private fun addNode(node: EvidenceNode): EvidenceNode {
        if (nodes.containsKey(node.id)) {
            throw IllegalArgumentException("duplicate evidence node ${node.id}")
        }
        nodes[node.id] = node
        return node
    }

    private fun reaches(start: String, target: String): Boolean {
        val stack = ArrayDeque<String>()
        val visited = HashSet<String>()
        stack.addLast(start)
        visited.add(start)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id == target) return true
            forward[id]?.forEach { next ->
                if (visited.add(next)) stack.addLast(next)
            }
        }
        return false
    }

    private fun edge(map: MutableMap<String, LinkedHashSet<String>>, from: String, to: String) {
        map.getOrPut(from) { LinkedHashSet() }.add(to)
    }

    /** 一次显式栈 DFS 的帧。 */
    private class SearchFrame(
        val id: String,
        var next: Int,
        val neighbors: List<String>,
    )
}
