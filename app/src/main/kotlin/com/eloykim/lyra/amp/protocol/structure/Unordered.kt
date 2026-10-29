package com.eloykim.lyra.amp.protocol.structure

/**
 * 无序哈希集合与映射，移植自 `src/structure/unordered.ts`。
 *
 * 两个容器都是基于 2 的幂次桶数组的分离链哈希表，负载因子 0.75，增长时整体
 * rehash。桶下标取自 [KeyStrategy] 产生（默认 [hashKey]）的 64 位哈希的低位；
 * 碰撞沿显式链行走，并用相等策略（绝不只是哈希相等）判定。容器不维护插入顺序：
 * 迭代顺序是未指定的桶顺序，扩容后可能改变。
 *
 * 迭代是 fail-fast 的：拿到迭代器之后的任何结构性修改（新增值的 `add`、
 * 新增键的 `set`、`delete`、`clear`，或增长触发的 rehash）都会让该迭代器在
 * 下一次 `next()` 时抛出 [java.util.ConcurrentModificationException]。
 * 更新已有 map 键的值不属于结构性修改，不会使迭代器失效。
 *
 * 与 TypeScript 版本一样，Kotlin 侧返回的 [Sequence] 在开始迭代时才创建迭代器
 * 并固定版本号。
 */

private const val InitialCapacity: Int = 16

/** 桶链中的一个条目。 */
private class HashNode<K, V>(
    val key: K,
    var value: V,
    val hash: Long,
) {
    var next: HashNode<K, V>? = null
}

/** 查找命中的节点、它在链中的前驱，以及所在桶下标。 */
private class HashHit<K, V>(
    val node: HashNode<K, V>,
    val previous: HashNode<K, V>?,
    val index: Int,
)

/** 两个公开类共享的 2 的幂次分离链哈希表。 */
private class HashTable<K, V>(private val label: String, strategy: KeyStrategy<K>?) {

    private var buckets: Array<HashNode<K, V>?> = arrayOfNulls(InitialCapacity)
    private var indexMask: Int = InitialCapacity - 1
    private val hashFn: (K) -> Hash = strategy?.hash ?: { key -> hashKey(key) }
    private val equalsFn: (K, K) -> Boolean = strategy?.equals ?: { a, b -> sameKey(a, b) }
    private var count = 0
    private var version = 0

    val size: Int get() = count

    private fun hashOf(key: K): Long = (hashFn(key) and HashMask).toLong()

    /** 查找节点、它在链中的前驱，以及所在桶下标。 */
    private fun find(key: K, hash: Long): HashHit<K, V>? {
        val index = (hash and indexMask.toLong()).toInt()
        var previous: HashNode<K, V>? = null
        var node: HashNode<K, V>? = buckets[index]
        while (node != null) {
            if (equalsFn(key, node.key)) {
                return HashHit(node, previous, index)
            }
            previous = node
            node = node.next
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
        if (count + 1 > buckets.size * 3 / 4) {
            grow()
        }
        val index = (hash and indexMask.toLong()).toInt()
        val node = HashNode(key, value, hash)
        node.next = buckets[index]
        buckets[index] = node
        count += 1
        version += 1
    }

    /** 桶数组翻倍，并把每个存活节点恰好重新挂接一次。 */
    private fun grow() {
        val nextBuckets: Array<HashNode<K, V>?> = arrayOfNulls(buckets.size * 2)
        val nextMask = nextBuckets.size - 1
        for (headIndex in buckets.indices) {
            var node = buckets[headIndex]
            while (node != null) {
                val next = node.next
                val index = (node.hash and nextMask.toLong()).toInt()
                node.next = nextBuckets[index]
                nextBuckets[index] = node
                node = next
            }
        }
        buckets = nextBuckets
        indexMask = nextMask
    }

    fun delete(key: K): Boolean {
        val hit = find(key, hashOf(key)) ?: return false
        if (hit.previous == null) {
            buckets[hit.index] = hit.node.next
        } else {
            hit.previous.next = hit.node.next
        }
        hit.node.next = null
        count -= 1
        version += 1
        return true
    }

    fun clear() {
        if (count == 0) {
            return
        }
        buckets = arrayOfNulls(InitialCapacity)
        indexMask = InitialCapacity - 1
        count = 0
        version += 1
    }

    /**
     * 构造 fail-fast 迭代器。期望版本在请求迭代器时捕获，
     * 而不是在第一次 `next()` 时捕获。
     */
    private fun <T> iterate(select: (HashNode<K, V>) -> T): Iterator<T> {
        val expectedVersion = version
        return object : Iterator<T> {
            private var bucketIndex = 0
            private var cursor: HashNode<K, V>? = buckets.getOrNull(0)

            private fun advance() {
                while (cursor == null && bucketIndex + 1 < buckets.size) {
                    bucketIndex += 1
                    cursor = buckets[bucketIndex]
                }
            }

            override fun hasNext(): Boolean {
                advance()
                return cursor != null
            }

            override fun next(): T {
                if (expectedVersion != version) {
                    throw concurrentModification(label)
                }
                advance()
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
 * 基于分离链哈希表的无序集合。
 *
 * 平均 O(1) 成员判定、显式碰撞链、2 的幂次桶、0.75 负载因子、增长时 rehash。
 * 迭代顺序未指定（桶顺序），扩容后可能改变。公开 API 与 [OrderedSet] 一致，
 * 包括自定义 [KeyStrategy] 支持。
 */
class UnorderedSet<T>(values: Iterable<T>? = null, strategy: KeyStrategy<T>? = null) : Iterable<T> {

    private val table = HashTable<T, Unit>("UnorderedSet", strategy)

    init {
        values?.forEach { table.add(it, Unit) }
    }

    val size: Int get() = table.size

    val isEmpty: Boolean get() = table.size == 0

    fun clear() = table.clear()

    fun add(value: T): UnorderedSet<T> {
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
 * 基于分离链哈希表的无序映射。
 *
 * 平均 O(1) 查找、显式碰撞链、2 的幂次桶、0.75 负载因子、增长时 rehash。
 * 迭代顺序未指定（桶顺序），扩容后可能改变。公开 API 与 [OrderedMap] 一致，
 * 包括自定义 [KeyStrategy] 支持。成员判定由 [has] 决定，因此 `null` 也是合法值。
 */
class UnorderedMap<K, V>(entries: Iterable<Pair<K, V>>? = null, strategy: KeyStrategy<K>? = null) :
    Iterable<Pair<K, V>>, MutableIndex<K, V> {

    private val table = HashTable<K, V>("UnorderedMap", strategy)

    init {
        entries?.forEach { (key, value) -> table.set(key, value) }
    }

    override val size: Int get() = table.size

    val isEmpty: Boolean get() = table.size == 0

    override fun clear() = table.clear()

    fun set(key: K, value: V): UnorderedMap<K, V> {
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
