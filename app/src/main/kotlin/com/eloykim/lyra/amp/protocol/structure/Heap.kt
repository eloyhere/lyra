package com.eloykim.lyra.amp.protocol.structure

/**
 * 数组实现的二叉堆，移植自 `src/structure/heap.ts`。
 *
 * 堆按广度优先的数组顺序存储：根在 0，下标 `i` 的子节点是 `2i + 1` 与 `2i + 2`。
 * push 与 pop 是 O(log n)，peek 是 O(1)，用可迭代对象构造时以 O(n) 原地 heapify
 * 而不是逐个 push。允许重复值；等价性由实际生效的比较器决定。
 */

/**
 * 根节点在比较器下是最小值还是最大值。
 */
enum class HeapOrder(val wire: String) {
    MIN("min"),
    MAX("max"),
    ;

    companion object {
        fun fromWire(value: String): HeapOrder =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 heap order: $value")
    }
}

/**
 * 具有 O(log n) 插入与删除的优先堆。
 *
 * 默认根是 [compareNatural] 下的最小值。设置 `order = HeapOrder.MAX` 会反转
 * 实际比较器，使根成为最大值。`compare` 控制优先级，可用于没有自然顺序的值。
 *
 * [toArray] 与迭代暴露内部广度优先的堆顺序，而不是排序顺序：根在最前，其余
 * 子节点只要满足堆性质即可任意排列。迭代使用创建迭代器时的快照。
 *
 * 空堆时 [pop]、[peek]、[replace] 返回 `null`。由于 `null` 仍是合法存储值，
 * 需要判断存在性时请检查 [size] 或 [isEmpty]。这里刻意不提供 decrease-key。
 */
class BinaryHeap<T>(
    compare: Comparator<T>? = null,
    order: HeapOrder = HeapOrder.MIN,
    values: Iterable<T>? = null,
) : Iterable<T> {

    private val values: ArrayList<T> = ArrayList()
    private val compareFn: Comparator<T>

    init {
        val base = compare ?: naturalComparator()
        compareFn = if (order == HeapOrder.MAX) reverseComparator(base) else base
        values?.let { this.values.addAll(it) }
        heapify()
    }

    /** 当前堆中的值数量。 */
    val size: Int get() = values.size

    /** 堆是否为空。 */
    val isEmpty: Boolean get() = values.isEmpty()

    /** 把值加入堆。 */
    fun push(value: T): BinaryHeap<T> {
        values.add(value)
        siftUp(values.size - 1)
        return this
    }

    /** 移除并返回根值；空堆返回 `null`。 */
    fun pop(): T? {
        if (values.isEmpty()) {
            return null
        }
        val root = values[0]
        val last = values.removeAt(values.size - 1)
        if (values.isNotEmpty()) {
            values[0] = last
            siftDown(0)
        }
        return root
    }

    /** 返回根值但不移除；空堆返回 `null`。 */
    fun peek(): T? = values.firstOrNull()

    /**
     * 以 O(log n) 替换根值并恢复堆性质。
     *
     * 空堆时插入该值并返回 `null`，与“先 push 再返回旧根”的形状一致。
     */
    fun replace(value: T): T? {
        if (values.isEmpty()) {
            values.add(value)
            return null
        }
        val previousRoot = values[0]
        values[0] = value
        siftDown(0)
        return previousRoot
    }

    /** 移除全部值。 */
    fun clear() = values.clear()

    /** 按广度优先堆顺序快照。结果未排序，只保证根是优先级最高的值。 */
    fun toArray(): List<T> = ArrayList(values)

    /** 按广度优先堆顺序遍历创建迭代器时的快照。 */
    override fun iterator(): Iterator<T> = toArray().iterator()

    /** 自底向上以 O(n) 恢复堆性质。 */
    private fun heapify() {
        var index = values.size / 2 - 1
        while (index >= 0) {
            siftDown(index)
            index -= 1
        }
    }

    /** 把值向根移动，直到父节点优先级更高。 */
    private fun siftUp(index: Int) {
        var child = index
        while (child > 0) {
            val parent = (child - 1) / 2
            if (compareFn.compare(values[child], values[parent]) >= 0) {
                return
            }
            swap(child, parent)
            child = parent
        }
    }

    /** 把值向叶移动，直到两个子节点优先级都更低。 */
    private fun siftDown(index: Int) {
        var parent = index
        while (true) {
            val left = parent * 2 + 1
            if (left >= values.size) {
                return
            }
            val right = left + 1
            var first = left
            if (right < values.size && compareFn.compare(values[right], values[left]) < 0) {
                first = right
            }
            if (compareFn.compare(values[first], values[parent]) >= 0) {
                return
            }
            swap(parent, first)
            parent = first
        }
    }

    /** 交换数组中的两个值。 */
    private fun swap(a: Int, b: Int) {
        val temporary = values[a]
        values[a] = values[b]
        values[b] = temporary
    }

    companion object {
        /** 用 `values` 建堆，无需重复 `values` 选项。 */
        fun <T> from(values: Iterable<T>, compare: Comparator<T>? = null, order: HeapOrder = HeapOrder.MIN): BinaryHeap<T> =
            BinaryHeap(compare = compare, order = order, values = values)
    }
}
