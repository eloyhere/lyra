package com.eloykim.lyra.amp.protocol.context

/**
 * ContextIndex：ContextAst 之上的热路径。
 *
 * 对应 `src/context/contextIndex.ts`：精确 map 回答「哪个节点」，CAS 回答
 * 「内容是什么」，LRU 回答「什么常驻」。布隆/杜鹃索引刻意缺席 —— 它们是索引，
 * 不是事实来源。
 */
data class ContextIndexStats(
    val nodes: Int,
    val cachedDetails: Int,
    val hits: Int,
    val misses: Int,
)

class ContextIndex(
    val ast: ContextAst = ContextAst(),
    private val store: ContentStore = MemoryContentStore(),
    cacheEntries: Int = 64,
) {

    private val cache: LruCache<String, String> = LruCache(cacheEntries)
    private var hits: Int = 0
    private var misses: Int = 0

    /** 添加节点；给出 detail 时把它存到内容引用之后。 */
    fun add(node: ContextAstNode, detail: String? = null): ContextAstNode {
        if (ast.node(node.id) != null) {
            throw IllegalArgumentException("duplicate indexed node ${node.id}")
        }
        val detailRef: String? = if (detail == null) node.detailRef else store.put(detail)
        val indexed: ContextAstNode = if (detailRef == null) node else node.copy(detailRef = detailRef)
        ast.add(indexed)
        return ast.node(indexed.id)!!
    }

    fun node(id: String): ContextAstNode? = ast.node(id)

    /** LRU -> CAS 读取；未命中是显式的。 */
    fun getDetail(nodeId: String): String? {
        val ref: String = ast.node(nodeId)?.detailRef ?: return null
        val cached: String? = cache.get(ref)
        if (cached != null) {
            hits += 1
            return cached
        }
        misses += 1
        val detail: String? = store.get(ref)
        if (detail != null) {
            cache.set(ref, detail)
        }
        return detail
    }

    fun view(budgetTokens: Int, targetIds: List<String>? = null): ContextAstView =
        ast.view(budgetTokens, targetIds)

    fun stats(): ContextIndexStats =
        ContextIndexStats(nodes = ast.size, cachedDetails = cache.size, hits = hits, misses = misses)
}

/**
 * 有界 LRU：`get` 与已存在键的 `set` 都刷新最近使用时间；
 * 超过容量时淘汰最久未使用的条目。
 */
private class LruCache<K, V>(capacity: Int) {

    private val capacity: Int = capacity
    private val entries: MutableMap<K, V> = LinkedHashMap()

    init {
        require(capacity > 0) { "capacity must be a positive safe integer" }
    }

    val size: Int get() = entries.size

    fun get(key: K): V? {
        val value: V = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        if (entries.size > capacity) {
            val oldest: K = entries.keys.first()
            entries.remove(oldest)
        }
    }
}
