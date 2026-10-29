package com.eloykim.lyra.amp.protocol.structure

/**
 * 结合律幺半群上的迭代式线段树，移植自 `src/structure/segmentTree.ts`。
 *
 * 类内部持有一棵 2 的幂次补齐的树：每个真实叶子存一个值，补齐叶子存单位元，
 * 每个内部节点合并两个子节点。单点更新以 O(log n) 重建根路径；半开区间查询以
 * O(log n) 合并若干规范节点，并保持从左到右的顺序，因此字符串拼接这类
 * 非交换操作同样正确。
 *
 * 刻意不支持惰性区间更新。空区间返回单位元，`size = 0` 合法：树没有叶子，
 * 但仍能用单位元回答空查询与 [queryAll]。
 */

/**
 * 一棵可查询区间结合的线段树。
 *
 * 值按引用存储，树不会克隆它们。`operation` 必须满足结合律，`identity` 必须是
 * 它的单位元。不要求交换律：区间查询按索引顺序合并规范节点。
 *
 * @param size 叶子数量，必须是非负整数。
 * @param operation 从左到右应用的结合运算。
 * @param identity [operation] 的中立元素。
 * @param values 可选的初始叶子值，按索引顺序给出；若提供，必须恰好包含 `size` 个值。
 */
class SegmentTree<T>(
    val size: Int,
    private val operation: (T, T) -> T,
    private val identity: T,
    values: Iterable<T>? = null,
) {

    private val count: Int = size
    private val base: Int
    private val tree: MutableList<T>

    init {
        require(size >= 0) { "size must be a non-negative safe integer, received $size" }
        var computedBase = 1
        while (computedBase < size) {
            computedBase *= 2
        }
        base = computedBase
        tree = MutableList(base * 2) { identity }
        if (values != null) {
            var index = 0
            for (value in values) {
                if (index == count) {
                    throw IllegalArgumentException("values must contain exactly $count values")
                }
                tree[base + index] = value
                index += 1
            }
            if (index != count) {
                throw IllegalArgumentException("values must contain exactly $count values, received $index")
            }
        }
        var node = base - 1
        while (node >= 1) {
            tree[node] = operation(tree[node * 2], tree[node * 2 + 1])
            node -= 1
        }
    }

    /**
     * 读取 `index` 处的叶子。
     *
     * @throws IndexOutOfBoundsException 当 `index` 不在 `[0, size)` 内。
     */
    fun get(index: Int): T {
        assertIndex(index)
        return tree[base + index]
    }

    /**
     * 替换 `index` 处的叶子。
     *
     * @throws IndexOutOfBoundsException 当 `index` 不在 `[0, size)` 内。
     */
    fun set(index: Int, value: T) {
        assertIndex(index)
        val leaf = base + index
        tree[leaf] = value
        rebuild(leaf)
    }

    /**
     * 基于当前值变换 `index` 处的叶子。
     *
     * `update` 会被调用一次并收到旧的叶子值。若它抛出异常，树保持原样。
     *
     * @throws IndexOutOfBoundsException 当 `index` 不在 `[0, size)` 内。
     */
    fun update(index: Int, update: (T) -> T) {
        assertIndex(index)
        val leaf = base + index
        val next = update(tree[leaf])
        tree[leaf] = next
        rebuild(leaf)
    }

    /**
     * 查询半开区间 `[left, right)`。
     *
     * 规范节点按索引顺序合并，因此非交换运算的结果也正确。空区间返回 [identity]。
     *
     * @throws IndexOutOfBoundsException 除非 `0 <= left <= right <= size`。
     */
    fun query(left: Int, right: Int): T {
        assertRange(left, right)
        if (left == right) {
            return identity
        }

        var leftNode = base + left
        var rightNode = base + right
        var leftResult = identity
        var rightResult = identity

        while (leftNode < rightNode) {
            if (leftNode % 2 == 1) {
                leftResult = operation(leftResult, tree[leftNode])
                leftNode += 1
            }
            if (rightNode % 2 == 1) {
                rightNode -= 1
                rightResult = operation(tree[rightNode], rightResult)
            }
            leftNode /= 2
            rightNode /= 2
        }

        return operation(leftResult, rightResult)
    }

    /**
     * 查询整个存储区间。
     *
     * 这是 O(1)：树根已经按顺序合并了全部叶子。空树返回 [identity]。
     */
    fun queryAll(): T = if (count == 0) identity else tree[1]

    /** 按索引顺序返回叶子值的新数组。 */
    fun toArray(): List<T> = ArrayList(tree.subList(base, base + count))

    /**
     * 把所有叶子重置为 [identity]。
     *
     * 直接覆写全部树节点，因此整体 O(n)，而不是对每个叶子单独走一遍重建路径。
     */
    fun clear() {
        for (index in tree.indices) {
            tree[index] = identity
        }
    }

    /** 叶子写入后重新合并根路径。 */
    private fun rebuild(leaf: Int) {
        var node = leaf / 2
        while (node >= 1) {
            tree[node] = operation(tree[node * 2], tree[node * 2 + 1])
            node /= 2
        }
    }

    /** 校验单个叶子下标。 */
    private fun assertIndex(index: Int) {
        if (index < 0 || index >= count) {
            throw IndexOutOfBoundsException("index must be an integer in [0, $count), received $index")
        }
    }

    /** 校验半开查询区间。 */
    private fun assertRange(left: Int, right: Int) {
        if (left < 0 || right > count || left > right) {
            throw IndexOutOfBoundsException("query range must satisfy 0 <= left <= right <= $count, received [$left, $right)")
        }
    }
}
