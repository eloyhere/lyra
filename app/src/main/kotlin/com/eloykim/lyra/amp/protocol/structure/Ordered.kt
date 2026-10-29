package com.eloykim.lyra.amp.protocol.structure

/**
 * 插入有序的哈希集合与映射，移植自 `src/structure/ordered.ts`。
 *
 * 两个容器都使用显式桶：`Map<Hash, head>` 把 64 位哈希映射到碰撞链的头节点，
 * 哈希与相等判定来自 [KeyStrategy]（默认 [hashKey] / [sameKey]）。顺序由穿过
 * 同一批节点的侵入式双向链表维护：迭代沿链表前进，查找走桶链。更新已有键保持
 * 位置；删除后重新添加会移到末尾。
 *
 * 迭代是 fail-fast 的：拿到迭代器之后再发生结构性修改（新增值的 `add`、
 * 新增键的 `set`、`delete` 或 `clear`）会让该迭代器在下一次 `next()` 时抛出
 * [java.util.ConcurrentModificationException]，而不是暴露不一致的遍历。
 * 更新已有 map 键的值不属于结构性修改，不会使迭代器失效。
 *
 * Kotlin 侧的差别：TypeScript 的 `Generator` 在创建时就固定版本号，而这里
 * [OrderedMap.keys] 等返回的 [Sequence] 在真正开始迭代时才创建迭代器并固定
 * 版本号（更适合 Kotlin 可重复迭代的 `Sequence` 约定）。
 */

/** 一个条目；同一节点同时位于桶链和顺序链表中。 */
private class OrderedNode<K, V>(
    val key: K,
    var value: V,
    val hash: Long,
) {
    var previous: OrderedNode<K, V>? = null
    var next: OrderedNode<K, V>? = null
    var bucketNext: OrderedNode<K, V>? = null
}

/** 桶链中命中的节点，以及它前面的节点。 */
private class OrderedHit<K, V>(
    val node: OrderedNode<K, V>,
    val bucketPrevious: OrderedNode<K, V>?,
)

/** 两个公开类共享的桶 + 侵入式链表机制。 */
private class OrderedTable<K, V>(private val label: String, strategy: KeyStrategy<K>?) {

    private val buckets = HashMap<Long, OrderedNode<K, V>>()
    private val hashFn: (K) -> Hash = strategy?.hash ?: { key -> hashKey(key) }
    private val equalsFn: (K, K) -> Boolean = strategy?.equals ?: { a, b -> sameKey(a, b) }

    private var head: OrderedNode<K, V>? = null
    private var tail: OrderedNode<K, V>? = null
    private var count = 0
    private var version = 0

    val size: Int get() = count

    private fun hashOf(key: K): Long = (hashFn(key) and HashMask).toLong()

    /** 查找节点及其桶链前驱。 */
    private fun find(key: K, hash: Long): OrderedHit<K, V>? {
        var bucketPrevious: OrderedNode<K, V>? = null
        var node: OrderedNode<K, V>? = buckets[hash]
        while (node != null) {
            if (equalsFn(key, node.key)) {
                return OrderedHit(node, bucketPrevious)
            }
            bucketPrevious = node
            node = node.bucketNext
        }
        return null
    }

    fun has(key: K): Boolean = find(key, hashOf(key)) != null

    fun get(key: K): V? = find(key, hashOf(key))?.node?.value

    /** 添加一个必须尚不存在的键；返回是否创建了节点。 */
    fun add(key: K, value: V): Boolean {
        val hash = hashOf(key)
        if (find(key, hash) != null) {
            return false
        }
        insert(key, value, hash)
        return true
    }

    /** 插入或更新；返回是否创建了节点。 */
    fun set(key: K, value: V): Boolean {
        val hash = hashOf(key)
        val hit = find(key, hash)
        if (hit != null) {
            hit.node.value = value
            return false
        }
        insert(key, value, hash)
        return true
    }

    private fun insert(key: K, value: V, hash: Long) {
        val node = OrderedNode(key, value, hash)
        node.previous = tail
        node.bucketNext = buckets[hash]
        val previousTail = tail
        if (previousTail == null) {
            head = node
        } else {
            previousTail.next = node
        }
        tail = node
        buckets[hash] = node
        count += 1
        version += 1
    }

    fun delete(key: K): Boolean {
        val hash = hashOf(key)
        val hit = find(key, hash) ?: return false
        val node = hit.node

        if (hit.bucketPrevious == null) {
            if (node.bucketNext == null) {
                buckets.remove(hash)
            } else {
                buckets[hash] = node.bucketNext!!
            }
        } else {
            hit.bucketPrevious.bucketNext = node.bucketNext
        }

        val previous = node.previous
        val next = node.next
        if (previous == null) {
            head = next
        } else {
            previous.next = next
        }
        if (next == null) {
            tail = previous
        } else {
            next.previous = previous
        }

        node.previous = null
        node.next = null
        node.bucketNext = null
        count -= 1
        version += 1
        return true
    }

    fun clear() {
        if (count == 0) {
            return
        }
        buckets.clear()
        head = null
        tail = null
        count = 0
        version += 1
    }

    /**
     * 构造 fail-fast 迭代器。期望版本在请求迭代器时捕获，
     * 而不是在第一次 `next()` 时捕获。
     */
    private fun <T> iterate(select: (OrderedNode<K, V>) -> T): Iterator<T> {
        val expectedVersion = version
        return object : Iterator<T> {
            private var cursor: OrderedNode<K, V>? = head

            override fun hasNext(): Boolean = cursor != null

            override fun next(): T {
                if (expectedVersion != version) {
                    throw concurrentModification(label)
                }
                val node = cursor ?: throw NoSuchElementException()
                cursor = node.next
                return select(node)
            }
        }
    }

    fun keys(): Sequence<K> = Sequence { iterate { node -> node.key } }

    fun values(): Sequence<V> = Sequence { iterate { node -> node.value } }

    fun entries(): Sequence<Pair<K, V>> = Sequence { iterate { node -> node.key to node.value } }

    /** 集合的条目，存储值无意义，因此键出现两次。 */
    fun keyPairs(): Sequence<Pair<K, K>> = Sequence { iterate { node -> node.key to node.key } }
}

/**
 * 按插入顺序迭代的集合。
 *
 * 查找通过显式哈希桶与碰撞链平均 O(1)；顺序是一条侵入式双向链表。
 * 重复添加已有值没有效果，删除后重新添加会移到末尾。
 */
class OrderedSet<T>(values: Iterable<T>? = null, strategy: KeyStrategy<T>? = null) : Iterable<T> {

    private val table = OrderedTable<T, Unit>("OrderedSet", strategy)

    init {
        values?.forEach { table.add(it, Unit) }
    }

    val size: Int get() = table.size

    val isEmpty: Boolean get() = table.size == 0

    fun clear() = table.clear()

    fun add(value: T): OrderedSet<T> {
        table.add(value, Unit)
        return this
    }

    fun has(value: T): Boolean = table.has(value)

    fun delete(value: T): Boolean = table.delete(value)

    /** [values] 的别名。 */
    fun keys(): Sequence<T> = table.keys()

    fun values(): Sequence<T> = table.keys()

    fun entries(): Sequence<Pair<T, T>> = table.keyPairs()

    override fun iterator(): Iterator<T> = table.keys().iterator()
}

/**
 * 按插入顺序迭代的映射。
 *
 * 查找通过显式哈希桶与碰撞链平均 O(1)；顺序是侵入式双向链表。给已有键赋值只
 * 更新值、不移动位置；删除后重新添加会移到末尾。成员判定由 [has] 决定，
 * 因此 `null` 也是完全合法的值。
 */
class OrderedMap<K, V>(entries: Iterable<Pair<K, V>>? = null, strategy: KeyStrategy<K>? = null) :
    Iterable<Pair<K, V>>, MutableIndex<K, V> {

    private val table = OrderedTable<K, V>("OrderedMap", strategy)

    init {
        entries?.forEach { (key, value) -> table.set(key, value) }
    }

    override val size: Int get() = table.size

    val isEmpty: Boolean get() = table.size == 0

    override fun clear() = table.clear()

    fun set(key: K, value: V): OrderedMap<K, V> {
        table.set(key, value)
        return this
    }

    override fun put(key: K, value: V) {
        table.set(key, value)
    }

    override fun get(key: K): V? = table.get(key)

    override fun has(key: K): Boolean = table.has(key)

    override fun delete(key: K): Boolean = table.delete(key)

    override fun keys(): Sequence<K> = table.keys()

    override fun values(): Sequence<V> = table.values()

    override fun entries(): Sequence<Pair<K, V>> = table.entries()

    override fun iterator(): Iterator<Pair<K, V>> = table.entries().iterator()
}
