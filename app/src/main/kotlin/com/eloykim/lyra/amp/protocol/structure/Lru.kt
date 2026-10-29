package com.eloykim.lyra.amp.protocol.structure

/**
 * 最近最少使用（LRU）容器，移植自 `src/structure/lru.ts`。
 *
 * 本模块所有键控缓存采用同一策略：
 *
 * - `get` 与已有键的 `set` 会把条目标记为最近使用；
 * - `has` 与 `peek` 不改变新鲜度；
 * - 插入超过 `capacity` 时剔除最近最少使用的条目；
 * - 删除后重新添加的键成为最近使用条目。
 *
 * 有序变体按插入顺序迭代（继承 [OrderedMap] / [OrderedSet]）；无序变体按桶顺序
 * 迭代。新鲜度由 [LRUList] 单独追踪，与迭代顺序无关。
 */

/**
 * [MutableIndex] 是 LRU 缓存所需的最小有序/无序映射表面。
 *
 * 由 [OrderedMap] 与 [UnorderedMap] 实现；索引负责成员判定与公开迭代顺序，
 * 链表负责新鲜度。两者分离才能在按新鲜度剔除的同时保留有序变体的插入顺序。
 */
interface MutableIndex<K, V> {

    val size: Int

    fun put(key: K, value: V)

    fun get(key: K): V?

    fun has(key: K): Boolean

    fun delete(key: K): Boolean

    fun clear()

    fun keys(): Sequence<K>

    fun values(): Sequence<V>

    fun entries(): Sequence<Pair<K, V>>
}

/**
 * LRU 链表中的一个节点。`owner` 由 [LRUList] 维护。
 */
class LRUListNode<T>(var value: T) {

    var previous: LRUListNode<T>? = null

    var next: LRUListNode<T>? = null

    /** 当前拥有该节点的链表；没有则为 `null`。 */
    var owner: LRUList<T>? = null
}

/**
 * 双向新鲜度链表。
 *
 * 前端是最近使用条目，后端是最少使用条目。[pushFront]/[pushBack] 返回的节点
 * 之后可以 O(1) 移动或移除。迭代从前到后遍历活链表；迭代期间修改链表不受支持。
 */
class LRUList<T>(values: Iterable<T>? = null) : Iterable<T> {

    private var headNode: LRUListNode<T>? = null
    private var tailNode: LRUListNode<T>? = null
    private var count = 0

    init {
        values?.forEach { pushBack(it) }
    }

    val size: Int get() = count

    val isEmpty: Boolean get() = count == 0

    /** 最近使用的值；没有则 `null`。 */
    val front: T? get() = headNode?.value

    /** 最近最少使用的值；没有则 `null`。 */
    val back: T? get() = tailNode?.value

    /** 插入到前端并返回其所属节点。 */
    fun pushFront(value: T): LRUListNode<T> {
        val node = LRUListNode(value)
        node.owner = this
        node.next = headNode
        val head = headNode
        if (head != null) {
            head.previous = node
        } else {
            tailNode = node
        }
        headNode = node
        count += 1
        return node
    }

    /** 插入到后端并返回其所属节点。 */
    fun pushBack(value: T): LRUListNode<T> {
        val node = LRUListNode(value)
        node.owner = this
        node.previous = tailNode
        val tail = tailNode
        if (tail != null) {
            tail.next = node
        } else {
            headNode = node
        }
        tailNode = node
        count += 1
        return node
    }

    /** 移除本链表拥有的节点。 */
    fun remove(node: LRUListNode<T>): Boolean {
        if (node.owner !== this) {
            return false
        }
        detach(node)
        node.owner = null
        count -= 1
        return true
    }

    /** 把拥有的节点移到前端，不改变大小。 */
    fun moveToFront(node: LRUListNode<T>): Boolean {
        if (node.owner !== this) {
            return false
        }
        if (headNode === node) {
            return true
        }
        detach(node)
        node.next = headNode
        val head = headNode
        if (head != null) {
            head.previous = node
        } else {
            tailNode = node
        }
        headNode = node
        return true
    }

    /** 移除并返回前端值。 */
    fun popFront(): T? {
        val head = headNode ?: return null
        val value = head.value
        remove(head)
        return value
    }

    /** 移除并返回后端值。 */
    fun popBack(): T? {
        val tail = tailNode ?: return null
        val value = tail.value
        remove(tail)
        return value
    }

    /** 移除所有节点并解除 owner 链接。 */
    fun clear() {
        var node = headNode
        while (node != null) {
            val next = node.next
            node.owner = null
            node.previous = null
            node.next = null
            node = next
        }
        headNode = null
        tailNode = null
        count = 0
    }

    /** 从前到后快照。 */
    fun toArray(): List<T> {
        val values = ArrayList<T>(count)
        for (value in this) {
            values.add(value)
        }
        return values
    }

    /** 从前到后遍历活链表。 */
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var node: LRUListNode<T>? = headNode

        override fun hasNext(): Boolean = node != null

        override fun next(): T {
            val current = node ?: throw NoSuchElementException()
            val next = current.next
            node = next
            return current.value
        }
    }

    /** 从链表中摘下节点，不改变所有权与计数。 */
    private fun detach(node: LRUListNode<T>) {
        val previous = node.previous
        val next = node.next
        if (previous != null) {
            previous.next = next
        } else {
            headNode = next
        }
        if (next != null) {
            next.previous = previous
        } else {
            tailNode = previous
        }
        node.previous = null
        node.next = null
    }
}

/**
 * LRU 缓存条目（实现细节，公开仅为构造索引时的类型参数）。
 */
class LruEntry<K, V>(val key: K, var value: V)

/**
 * 两个键控 LRU 映射共享的实现。
 *
 * 索引提供成员判定与公开的迭代顺序；链表提供新鲜度。保持两者分离，才能让有序
 * 变体保留插入顺序的同时仍按新鲜度剔除。
 */
abstract class LruMapBase<K, V> protected constructor(
    val capacity: Int,
    private val index: MutableIndex<K, LRUListNode<LruEntry<K, V>>>,
) : Iterable<Pair<K, V>> {

    init {
        require(capacity > 0) { "capacity must be a positive safe integer" }
    }

    private val order = LRUList<LruEntry<K, V>>()

    val size: Int get() = index.size

    val isFull: Boolean get() = size >= capacity

    fun has(key: K): Boolean = index.has(key)

    /** 读取但不改变新鲜度。 */
    fun peek(key: K): V? = index.get(key)?.value?.value

    /** 读取并把条目标记为最近使用。 */
    fun get(key: K): V? {
        val node = index.get(key) ?: return null
        order.moveToFront(node)
        return node.value.value
    }

    /** 插入或更新；已有键会被标记为最近使用。 */
    fun set(key: K, value: V): LruMapBase<K, V> {
        val existing = index.get(key)
        if (existing != null) {
            existing.value.value = value
            order.moveToFront(existing)
            return this
        }

        val entry = LruEntry(key, value)
        val node = order.pushFront(entry)
        index.put(key, node)

        if (index.size > capacity) {
            val oldest = order.back
            if (oldest != null) {
                order.popBack()
                index.delete(oldest.key)
            }
        }
        return this
    }

    fun delete(key: K): Boolean {
        val node = index.get(key) ?: return false
        order.remove(node)
        index.delete(key)
        return true
    }

    fun clear() {
        index.clear()
        order.clear()
    }

    /** 把已有键标记为最近使用但不读取值。 */
    fun touch(key: K): Boolean {
        val node = index.get(key) ?: return false
        order.moveToFront(node)
        return true
    }

    /** 最近最少使用的键值对，不改变新鲜度。 */
    fun leastRecentlyUsed(): Pair<K, V>? {
        val entry = order.back ?: return null
        return entry.key to entry.value
    }

    /** 最近使用的键值对，不改变新鲜度。 */
    fun mostRecentlyUsed(): Pair<K, V>? {
        val entry = order.front ?: return null
        return entry.key to entry.value
    }

    fun keys(): Sequence<K> = index.keys()

    fun values(): Sequence<V> = index.values().map { node -> node.value.value }

    fun entries(): Sequence<Pair<K, V>> = index.entries().map { (key, node) -> key to node.value.value }

    override fun iterator(): Iterator<Pair<K, V>> = entries().iterator()
}

/**
 * 迭代遵循插入顺序的 LRU 缓存。
 *
 * `get`/`touch` 把条目移到新鲜度前端，但不改变它在插入顺序中的位置。给已有键赋值
 * 保留其插入位置；删除后重新添加会追加到末尾。
 */
class LRUOrderedMap<K, V>(
    capacity: Int,
    entries: Iterable<Pair<K, V>>? = null,
    strategy: KeyStrategy<K>? = null,
) : LruMapBase<K, V>(capacity, OrderedMap<K, LRUListNode<LruEntry<K, V>>>(null, strategy)) {

    init {
        entries?.forEach { (key, value) -> set(key, value) }
    }
}

/**
 * 不保证迭代顺序的 LRU 缓存。
 *
 * 迭代遵循底层 [UnorderedMap] 的桶顺序，扩容后可能改变；新鲜度仍被精确追踪。
 */
class LRUUnorderedMap<K, V>(
    capacity: Int,
    entries: Iterable<Pair<K, V>>? = null,
    strategy: KeyStrategy<K>? = null,
) : LruMapBase<K, V>(capacity, UnorderedMap<K, LRUListNode<LruEntry<K, V>>>(null, strategy)) {

    init {
        entries?.forEach { (key, value) -> set(key, value) }
    }
}

/**
 * 把 [LRUOrderedMap] 当作集合使用的新鲜度视图。
 */
class LRUOrderedSet<T>(
    capacity: Int,
    values: Iterable<T>? = null,
    strategy: KeyStrategy<T>? = null,
) : Iterable<T> {

    private val map = LRUOrderedMap<T, Boolean>(capacity, null, strategy)

    init {
        values?.forEach { add(it) }
    }

    val size: Int get() = map.size

    val capacity: Int get() = map.capacity

    val isFull: Boolean get() = map.isFull

    fun add(value: T): LRUOrderedSet<T> {
        map.set(value, true)
        return this
    }

    fun has(value: T): Boolean = map.has(value)

    /** 不改变新鲜度的成员判定。 */
    fun peek(value: T): Boolean = map.has(value)

    fun get(value: T): Boolean = map.get(value) == true

    fun delete(value: T): Boolean = map.delete(value)

    fun touch(value: T): Boolean = map.touch(value)

    fun clear() = map.clear()

    fun leastRecentlyUsed(): T? = map.leastRecentlyUsed()?.first

    fun mostRecentlyUsed(): T? = map.mostRecentlyUsed()?.first

    fun keys(): Sequence<T> = map.keys()

    fun values(): Sequence<T> = map.keys()

    fun entries(): Sequence<Pair<T, T>> = map.keys().map { it to it }

    override fun iterator(): Iterator<T> = map.keys().iterator()
}

/**
 * 把 [LRUUnorderedMap] 当作集合使用的新鲜度视图。
 */
class LRUUnorderedSet<T>(
    capacity: Int,
    values: Iterable<T>? = null,
    strategy: KeyStrategy<T>? = null,
) : Iterable<T> {

    private val map = LRUUnorderedMap<T, Boolean>(capacity, null, strategy)

    init {
        values?.forEach { add(it) }
    }

    val size: Int get() = map.size

    val capacity: Int get() = map.capacity

    val isFull: Boolean get() = map.isFull

    fun add(value: T): LRUUnorderedSet<T> {
        map.set(value, true)
        return this
    }

    fun has(value: T): Boolean = map.has(value)

    /** 不改变新鲜度的成员判定。 */
    fun peek(value: T): Boolean = map.has(value)

    fun get(value: T): Boolean = map.get(value) == true

    fun delete(value: T): Boolean = map.delete(value)

    fun touch(value: T): Boolean = map.touch(value)

    fun clear() = map.clear()

    fun leastRecentlyUsed(): T? = map.leastRecentlyUsed()?.first

    fun mostRecentlyUsed(): T? = map.mostRecentlyUsed()?.first

    fun keys(): Sequence<T> = map.keys()

    fun values(): Sequence<T> = map.keys()

    fun entries(): Sequence<Pair<T, T>> = map.keys().map { it to it }

    override fun iterator(): Iterator<T> = map.keys().iterator()
}
