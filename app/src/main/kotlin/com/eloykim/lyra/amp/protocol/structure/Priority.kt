package com.eloykim.lyra.amp.protocol.structure

/**
 * 优先级有序容器，移植自 `src/structure/priority.ts`。
 *
 * 本模块的每个容器先按 [Comparator] 排序，再按到达顺序排序：两个值比较相等时，
 * 先入队的先被服务。因此队列是稳定的，重新插入等价值永远不会改变它们的相对顺序。
 *
 * `order` 默认是 `MAX`，先服务最大的值，与本项目其他地方“数值越大越紧急”的优先级
 * 约定一致；`MIN` 则先服务最小的值。比较器只决定优先级，从不改变存了哪些值。
 *
 * 成本（`n` 个条目、`b` 个活跃整数优先级）：
 *
 * | 容器 | enqueue | dequeue | peek | 重新定优先级 |
 * | --- | --- | --- | --- | --- |
 * | [PriorityQueue] | O(log n) | O(log n) | O(1) | — |
 * | [IndexedPriorityQueue] | O(log n) | O(log n) | O(1) | O(log n) |
 * | [BucketQueue] | O(1) | 摊还 O(1) | O(1) | — |
 * | [TopK] | O(log k) | — | O(1) | — |
 *
 * `toArray`、迭代与 `drain` 暴露**出队顺序**，而不是内部存储顺序：优先队列的契约
 * 就是值被服务的顺序。这是与 [BinaryHeap] 刻意不同的地方（后者快照暴露堆顺序）。
 * `drain` 是 `toArray` 的破坏性版本。
 */

/** 存储条目中排序依赖的部分：值与用于决胜的到达序号。 */
private open class Sequenced<T>(var value: T, val sequence: Long)

/** [IndexedPriorityQueue] 中被键标识的条目。 */
private class KeyedEntry<K, T>(val key: K, value: T, sequence: Long) : Sequenced<T>(value, sequence)

/** [BucketQueue] 里的一个 FIFO 桶。 */
private class Bucket<T>(var values: MutableList<T>, var head: Int)

private fun servingDirection(order: HeapOrder): Int = if (order == HeapOrder.MAX) -1 else 1

private fun qualityDirection(order: HeapOrder): Int = if (order == HeapOrder.MAX) 1 else -1

/**
 * 比较两个存储条目：先比优先级，再比到达顺序。
 *
 * 方向乘子在决胜之前施加，因此反转 order 只会翻转优先级，不会反转等价值。
 * 到达顺序永远更早的序号在前，这正是本模块所有队列稳定性的来源。
 */
private fun <T> entryComparator(compare: Comparator<T>, direction: Int): Comparator<Sequenced<T>> =
    Comparator { left, right ->
        val byPriority = direction * compare.compare(left.value, right.value)
        if (byPriority != 0) {
            byPriority
        } else {
            left.sequence.compareTo(right.sequence)
        }
    }

private fun requireCapacity(capacity: Int) {
    require(capacity > 0) { "capacity must be a positive safe integer" }
}

/**
 * 基于二叉堆的稳定优先队列。
 *
 * 默认先服务 `compare` 下最大的值；`order = HeapOrder.MIN` 先服务最小的值。
 * 平局按到达顺序处理，因此两个同优先级的条目总是按入队顺序离开。
 *
 * `enqueue` 与 `dequeue` 是 O(log n)，`peek` 是 O(1)。`toArray`、迭代与 `drain`
 * 返回**出队顺序**；`drain` 会移除全部内容。
 *
 * 空队列时 [dequeue] 与 [peek] 返回 `null`。由于 `null` 也是合法值，
 * 需要判断存在性时请检查 [size] 或 [isEmpty]。
 */
class PriorityQueue<T>(
    compare: Comparator<T>? = null,
    order: HeapOrder = HeapOrder.MAX,
    values: Iterable<T>? = null,
) : Iterable<T> {

    private val compareFn: Comparator<Sequenced<T>> =
        entryComparator(compare ?: naturalComparator(), servingDirection(order))
    private val heap = BinaryHeap(compare = compareFn)
    private var sequence = 0L

    init {
        values?.forEach { enqueue(it) }
    }

    /** 当前队列中的值数量。 */
    val size: Int get() = heap.size

    /** 队列是否为空。 */
    val isEmpty: Boolean get() = heap.isEmpty

    /** 按优先级加入值，排在任何已入队的等价值之后。 */
    fun enqueue(value: T): PriorityQueue<T> {
        heap.push(Sequenced(value, sequence))
        sequence += 1
        return this
    }

    /** 按迭代顺序入队每个值。 */
    fun enqueueAll(values: Iterable<T>): PriorityQueue<T> {
        values.forEach { enqueue(it) }
        return this
    }

    /** 移除并返回优先级最高的值；空队列返回 `null`。 */
    fun dequeue(): T? = heap.pop()?.value

    /** 返回下一个会被服务的值但不移除；空队列返回 `null`。 */
    fun peek(): T? = heap.peek()?.value

    /** 移除全部值并把到达计数器重置。 */
    fun clear() {
        heap.clear()
        sequence = 0
    }

    /**
     * 不改变队列，按出队顺序快照。
     *
     * 成本 O(n log n)：复制堆后按队列所用的同一比较器排序。
     */
    fun toArray(): List<T> = heap.toArray().sortedWith(compareFn).map { it.value }

    /** 按出队顺序移除并返回所有值。 */
    fun drain(): List<T> {
        val values = ArrayList<T>()
        while (!heap.isEmpty) {
            heap.pop()?.let { values.add(it.value) }
        }
        return values
    }

    /** 按出队顺序遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()

    companion object {
        /** 用 `values` 建队列，无需重复 `values` 选项。 */
        fun <T> from(
            values: Iterable<T>,
            compare: Comparator<T>? = null,
            order: HeapOrder = HeapOrder.MAX,
        ): PriorityQueue<T> = PriorityQueue(compare = compare, order = order, values = values)
    }
}

/**
 * 可用键寻址的优先队列。
 *
 * 普通 [PriorityQueue] 无法重新定优先级或移除已接受的条目，因为查找需要扫描。
 * 这个队列维护“键 → 堆位置”的映射，因此 `set`、`delete`、`get`、`has` 是 O(log n)
 * 或 O(1)——正是 Dijkstra 与任务调度器需要的形状。
 *
 * [set] 插入新键或替换已有键下的值。被替换的条目保留原来的到达序号，因此在优先级
 * 不变时重新 set 不会把它排到更晚到达的条目之后。平局仍按到达顺序服务。
 *
 * [toArray] 与 [keys] 是 O(n log n) 的出队顺序快照；[dequeue]、[peek]、[get]、
 * [delete] 都不扫描。
 *
 * @param keyOf 派生标识值的键；每次 [set] 求值一次。
 * @param compare 决定优先级的排序；默认自然顺序。
 * @param order 先服务哪一端；默认 `MAX`。
 */
class IndexedPriorityQueue<K, T>(
    private val keyOf: (T) -> K,
    compare: Comparator<T>? = null,
    order: HeapOrder = HeapOrder.MAX,
) : Iterable<T> {

    private val entries = ArrayList<KeyedEntry<K, T>>()
    private val positions = HashMap<K, Int>()
    private val compareFn: Comparator<Sequenced<T>> =
        entryComparator(compare ?: naturalComparator(), servingDirection(order))
    private var sequence = 0L

    /** 当前队列中的条目数量。 */
    val size: Int get() = entries.size

    /** 队列是否不含条目。 */
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * 插入一个值，或替换其键下已有的值。
     *
     * 新键排在同优先级的条目之后。已有键保留到达序号，只有优先级变化时才会移动。
     */
    fun set(value: T): IndexedPriorityQueue<K, T> {
        val key = keyOf(value)
        val index = positions[key]
        if (index == null) {
            val entry = KeyedEntry(key, value, sequence)
            sequence += 1
            positions[key] = entries.size
            entries.add(entry)
            siftUp(entries.size - 1)
            return this
        }
        val entry = entries[index]
        entry.value = value
        val moved = siftUp(index)
        siftDown(moved)
        return this
    }

    /** 移除 `key` 下的条目；键不存在返回 `false`。 */
    fun delete(key: K): Boolean {
        val index = positions[key] ?: return false
        removeAt(index)
        return true
    }

    /** 读取 `key` 下的值但不改变队列；键不存在返回 `null`。 */
    fun get(key: K): T? = positions[key]?.let { entries[it].value }

    /** 是否存在 `key` 下的条目。 */
    fun has(key: K): Boolean = positions.containsKey(key)

    /** 移除并返回优先级最高的值；空队列返回 `null`。 */
    fun dequeue(): T? = if (entries.isEmpty()) null else removeAt(0).value

    /** 返回下一个会被服务的值但不移除；空队列返回 `null`。 */
    fun peek(): T? = if (entries.isEmpty()) null else entries[0].value

    /** 返回下一个会被服务的键但不移除；空队列返回 `null`。 */
    fun peekKey(): K? = if (entries.isEmpty()) null else entries[0].key

    /** 移除全部条目。 */
    fun clear() {
        entries.clear()
        positions.clear()
        sequence = 0
    }

    /** 按出队顺序快照值。 */
    fun toArray(): List<T> = entries.toList().sortedWith(compareFn).map { it.value }

    /** 按出队顺序快照键。 */
    fun keys(): List<K> = entries.toList().sortedWith(compareFn).map { it.key }

    /** 按出队顺序遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()

    /**
     * 移除 `index` 处的条目并返回它。
     *
     * 最后一个条目填补空洞，然后按需要上移或下移恢复堆性质；键索引跟随两次移动。
     */
    private fun removeAt(index: Int): KeyedEntry<K, T> {
        val removed = entries[index]
        val lastIndex = entries.size - 1
        val last = entries[lastIndex]
        entries.removeAt(lastIndex)
        positions.remove(removed.key)
        if (index != lastIndex) {
            entries[index] = last
            positions[last.key] = index
            val moved = siftUp(index)
            siftDown(moved)
        }
        return removed
    }

    /** 把条目向根移动，直到父节点有优先级；返回最终下标。 */
    private fun siftUp(index: Int): Int {
        var child = index
        while (child > 0) {
            val parent = (child - 1) / 2
            if (compareFn.compare(entries[child], entries[parent]) >= 0) {
                return child
            }
            swap(child, parent)
            child = parent
        }
        return child
    }

    /** 把条目向叶移动，直到两个子节点优先级都更低。 */
    private fun siftDown(index: Int) {
        var parent = index
        while (true) {
            val left = parent * 2 + 1
            if (left >= entries.size) {
                return
            }
            val right = left + 1
            var first = left
            if (right < entries.size && compareFn.compare(entries[right], entries[left]) < 0) {
                first = right
            }
            if (compareFn.compare(entries[first], entries[parent]) >= 0) {
                return
            }
            swap(parent, first)
            parent = first
        }
    }

    /** 交换两个条目并保持键索引一致。 */
    private fun swap(a: Int, b: Int) {
        val left = entries[a]
        val right = entries[b]
        entries[a] = right
        entries[b] = left
        positions[right.key] = a
        positions[left.key] = b
    }
}

/**
 * 整数优先级队列，每个优先级一个 FIFO 桶。
 *
 * 同优先级的值在同一个桶中等待，因此服务下一个条目是 O(1)，直到活跃桶为空；
 * 切换到下一个活跃桶是一次 O(log b)，`b` 是非空桶数量。当优先级来自一个小范围
 * （调度 tick、T 恤尺寸、本项目 0–100 的行为优先级）时，这是正确的形状，
 * 比较堆的逐条通用性反而是浪费。
 *
 * 优先级必须是整数；桶下标无法表示的值会被拒绝。负数没问题，活跃优先级也无需
 * 连续：只追踪真正持有值的桶。
 */
class BucketQueue<T>(order: HeapOrder = HeapOrder.MAX) : Iterable<T> {

    private val buckets = HashMap<Int, Bucket<T>>()
    private val orderMode: HeapOrder = order
    private val active = BinaryHeap<Int>(order = orderMode)
    private var count = 0

    /** 当前队列中的值数量。 */
    val size: Int get() = count

    /** 队列是否为空。 */
    val isEmpty: Boolean get() = count == 0

    /**
     * 把值追加到 `priority` 的桶。
     *
     * 同优先级的值按入队顺序离开。
     */
    fun enqueue(value: T, priority: Int): BucketQueue<T> {
        val bucket = buckets[priority]
        if (bucket == null) {
            val created = Bucket(ArrayList<T>(), 0)
            buckets[priority] = created
            active.push(priority)
            created.values.add(value)
        } else {
            bucket.values.add(value)
        }
        count += 1
        return this
    }

    /** 移除并返回优先级最高的值；空队列返回 `null`。 */
    fun dequeue(): T? {
        val priority = peekPriority() ?: return null
        val bucket = buckets[priority] ?: return null
        val value = bucket.values[bucket.head]
        bucket.head += 1
        count -= 1
        if (bucket.head == bucket.values.size) {
            buckets.remove(priority)
            active.pop()
        } else if (bucket.head > 32 && bucket.head * 2 >= bucket.values.size) {
            bucket.values = bucket.values.subList(bucket.head, bucket.values.size).toMutableList()
            bucket.head = 0
        }
        return value
    }

    /** 返回下一个会被服务的值但不移除；空队列返回 `null`。 */
    fun peek(): T? {
        val priority = peekPriority() ?: return null
        val bucket = buckets[priority] ?: return null
        return bucket.values[bucket.head]
    }

    /** 下一个条目将被服务的优先级；空队列返回 `null`。 */
    fun peekPriority(): Int? {
        while (!active.isEmpty) {
            val priority = active.peek()
            if (priority != null && buckets.containsKey(priority)) {
                return priority
            }
            active.pop()
        }
        return null
    }

    /** 移除全部值。 */
    fun clear() {
        buckets.clear()
        active.clear()
        count = 0
    }

    /** 不改变队列，按出队顺序快照值。 */
    fun toArray(): List<T> {
        val priorities = ArrayList(buckets.keys)
        priorities.sortWith(Comparator { left, right ->
            if (orderMode == HeapOrder.MAX) right.compareTo(left) else left.compareTo(right)
        })
        val values = ArrayList<T>()
        for (priority in priorities) {
            val bucket = buckets[priority] ?: continue
            for (index in bucket.head until bucket.values.size) {
                values.add(bucket.values[index])
            }
        }
        return values
    }

    /** 按出队顺序遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()
}

/**
 * 目前见过的 `k` 个最优值，放在一个有界缓冲区里。
 *
 * [offer] 在比较器下保留最好的 `capacity` 个值，丢弃其余，成本 O(log k)：保留的值
 * 放在根为当前最差值的堆中，因此只有新值优于该根时才会替换它。与最差值相等的值
 * 会被拒绝而不是交换等价值，这使缓冲区保持稳定，其内容也不受后来者的影响。
 *
 * 当完整排序浪费时这个缓冲区很有用——例如从不结束的流中保留 `k` 个最显著的候选。
 */
class TopK<T>(
    /** 保留的最大值数量。 */
    val capacity: Int,
    compare: Comparator<T>? = null,
    order: HeapOrder = HeapOrder.MAX,
) : Iterable<T> {

    init {
        requireCapacity(capacity)
    }

    private val compareFn: Comparator<T> = compare ?: naturalComparator()
    private val quality: Int = qualityDirection(order)
    private val retained = BinaryHeap<T>(compare = Comparator { left, right -> signedCompare(left, right) })

    /** 当前保留的值数量。 */
    val size: Int get() = retained.size

    /** 缓冲区是否已持有 `capacity` 个值。 */
    val isFull: Boolean get() = retained.size >= capacity

    /** 保留值中最弱的一个：下一个会被挤掉的值；没有值时返回 `null`。 */
    val worst: T? get() = retained.peek()

    /**
     * 向缓冲区提交一个值。
     *
     * 还有空间时值会被保留；满了以后，只有严格优于 [worst] 的值才会被保留并挤掉
     * 最差值。平局保留先到达的值。
     *
     * @return 值最终被保留返回 `true`，否则 `false`。
     */
    fun offer(value: T): Boolean {
        if (!isFull) {
            retained.push(value)
            return true
        }
        val worst = retained.peek() ?: return false
        if (signedCompare(value, worst) <= 0) {
            return false
        }
        retained.replace(value)
        return true
    }

    /** 丢弃全部保留值。 */
    fun clear() = retained.clear()

    /** 快照保留值，最好的在前。 */
    fun toArray(): List<T> =
        retained.toArray().sortedWith(Comparator { left, right -> signedCompare(right, left) })

    /** 最好的在前，遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()

    /** 比较两个值，使正数表示左边更优，无论当前是哪种 order。 */
    private fun signedCompare(left: T, right: T): Int = quality * compareFn.compare(left, right)
}
