package com.eloykim.lyra.amp.protocol.structure

import java.util.IdentityHashMap

/**
 * 高度平衡的 AVL 搜索树，移植自 `src/structure/avlTree.ts`。
 *
 * 每个节点存储其子树高度，每次插入或删除都沿受影响路径回溯，施加
 * LL/RR/LR/RL 旋转，直到所有平衡因子落在 `[-1, 1]`。因此即使按有序序列插入，
 * 树高也保持 O(log n)，`insert`、`has`、`delete`、`min`、`max`、`floor`、`ceil`
 * 都是最坏 O(log n)。
 */

/** 一个 AVL 节点；`height` 是其子树的节点数。 */
private class AVLNode<T>(var value: T) {
    var left: AVLNode<T>? = null
    var right: AVLNode<T>? = null
    var height: Int = 1
}

/**
 * 自平衡的 AVL 二叉搜索树。
 *
 * 重复值（比较结果为 0）被拒绝。等价的插入不会替换已存储的代表；用等价值删除
 * 会移除已存储的代表。
 */
class AVLTree<T>(compare: Comparator<T>? = null) : Iterable<T> {

    private var rootNode: AVLNode<T>? = null
    private var count = 0
    private val compareFn: Comparator<T> = compare ?: naturalComparator()

    /** 存储的值数量。 */
    val size: Int get() = count

    /** 以节点计的高度：空树 0，叶子 1。 */
    val height: Int get() = rootNode?.height ?: 0

    /** 树是否不含值。 */
    val isEmpty: Boolean get() = count == 0

    /** 当没有等价值已存储时插入。 */
    fun insert(value: T): Boolean {
        val previous = count
        rootNode = insertNode(rootNode, value)
        return count > previous
    }

    /** 是否存在等价的值。 */
    fun has(value: T): Boolean {
        var node = rootNode
        while (node != null) {
            val order = compareFn.compare(value, node.value)
            if (order == 0) {
                return true
            }
            node = if (order < 0) node.left else node.right
        }
        return false
    }

    /**
     * 删除与 `value` 等价的值，并把路径重新平衡到根。
     *
     * 双子节点由中序后继替换。每个平衡因子离开 `[-1, 1]` 的祖先都会被旋转修复。
     */
    fun delete(value: T): Boolean {
        val previous = count
        rootNode = deleteNode(rootNode, value)
        return count < previous
    }

    /** 比较器顺序下的最小值；空树返回 `null`。 */
    fun min(): T? = rootNode?.let { minimumNode(it).value }

    /** 比较器顺序下的最大值；空树返回 `null`。 */
    fun max(): T? {
        var node = rootNode ?: return null
        while (node.right != null) {
            node = node.right!!
        }
        return node.value
    }

    /** 比较器顺序下小于等于 `value` 的最大已存储值；没有则返回 `null`。 */
    fun floor(value: T): T? {
        var node = rootNode
        var result: T? = null
        while (node != null) {
            val order = compareFn.compare(node.value, value)
            if (order == 0) {
                return node.value
            }
            if (order < 0) {
                result = node.value
                node = node.right
            } else {
                node = node.left
            }
        }
        return result
    }

    /** 比较器顺序下大于等于 `value` 的最小已存储值；没有则返回 `null`。 */
    fun ceil(value: T): T? {
        var node = rootNode
        var result: T? = null
        while (node != null) {
            val order = compareFn.compare(node.value, value)
            if (order == 0) {
                return node.value
            }
            if (order < 0) {
                node = node.right
            } else {
                result = node.value
                node = node.left
            }
        }
        return result
    }

    /** 移除全部值并把高度重置为 0。 */
    fun clear() {
        rootNode = null
        count = 0
    }

    /** 按比较器升序收集值。 */
    fun toArray(): List<T> = inorderValues().toList()

    /** 按比较器升序迭代。迭代期间修改树不受支持。 */
    override fun iterator(): Iterator<T> = inorderValues().iterator()

    /**
     * 检查树的不变式。
     *
     * 验证严格的中序有序性、维护的 size、每个节点记录的子树高度与实际子树高度
     * 一致，以及 AVL 平衡条件 `|balanceFactor| <= 1`。空列表表示树合法。
     */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        val start = rootNode
        if (start == null) {
            if (count != 0) {
                errors.add("size mismatch: expected $count, found an empty tree")
            }
            return errors
        }

        val values = toArray()
        if (values.size != count) {
            errors.add("size mismatch: expected $count, traversed ${values.size}")
        }
        for (index in 1 until values.size) {
            if (compareFn.compare(values[index - 1], values[index]) >= 0) {
                errors.add("ordering violation between in-order positions ${index - 1} and $index")
            }
        }

        val actualHeights = IdentityHashMap<AVLNode<T>, Int>()
        val stack = ArrayList<Pair<AVLNode<T>, Boolean>>()
        stack.add(start to false)
        var seen = 0
        while (stack.isNotEmpty()) {
            val (node, visited) = stack.removeAt(stack.size - 1)
            if (!visited) {
                stack.add(node to true)
                node.right?.let { stack.add(it to false) }
                node.left?.let { stack.add(it to false) }
                continue
            }

            seen += 1
            val leftHeight = node.left?.let { actualHeights[it] ?: -1 } ?: 0
            val rightHeight = node.right?.let { actualHeights[it] ?: -1 } ?: 0
            if (leftHeight < 0 || rightHeight < 0) {
                errors.add("height metadata is missing for a child subtree")
                actualHeights[node] = 1
                continue
            }
            val actualHeight = 1 + maxOf(leftHeight, rightHeight)
            actualHeights[node] = actualHeight
            if (node.height != actualHeight) {
                errors.add("height mismatch: stored ${node.height}, actual $actualHeight")
            }
            val balance = leftHeight - rightHeight
            if (balance > 1 || balance < -1) {
                errors.add("balance violation: balance factor $balance")
            }
        }
        if (seen != count) {
            errors.add("size mismatch: expected $count, reached $seen nodes")
        }
        return errors
    }

    private fun insertNode(node: AVLNode<T>?, value: T): AVLNode<T> {
        if (node == null) {
            count += 1
            return AVLNode(value)
        }
        val order = compareFn.compare(value, node.value)
        if (order == 0) {
            return node
        }
        if (order < 0) {
            node.left = insertNode(node.left, value)
        } else {
            node.right = insertNode(node.right, value)
        }
        return rebalance(node)
    }

    private fun deleteNode(node: AVLNode<T>?, value: T): AVLNode<T>? {
        if (node == null) {
            return null
        }
        val order = compareFn.compare(value, node.value)
        if (order < 0) {
            node.left = deleteNode(node.left, value)
        } else if (order > 0) {
            node.right = deleteNode(node.right, value)
        } else {
            val left = node.left
            if (left == null) {
                count -= 1
                return node.right
            }
            val right = node.right
            if (right == null) {
                count -= 1
                return left
            }
            val successor = minimumNode(right)
            node.value = successor.value
            node.right = deleteNode(right, successor.value)
        }
        return rebalance(node)
    }

    /** 重算本节点高度，并用旋转修复其子树。 */
    private fun rebalance(node: AVLNode<T>): AVLNode<T> {
        updateHeight(node)
        val balance = balanceFactor(node)
        if (balance > 1) {
            val left = node.left
            if (left != null && balanceFactor(left) < 0) {
                node.left = rotateLeft(left)
            }
            return rotateRight(node)
        }
        if (balance < -1) {
            val right = node.right
            if (right != null && balanceFactor(right) > 0) {
                node.right = rotateRight(right)
            }
            return rotateLeft(node)
        }
        return node
    }

    /** 右旋；返回新的子树根。 */
    private fun rotateRight(node: AVLNode<T>): AVLNode<T> {
        val pivot = node.left ?: return node
        node.left = pivot.right
        pivot.right = node
        updateHeight(node)
        updateHeight(pivot)
        return pivot
    }

    /** 左旋；返回新的子树根。 */
    private fun rotateLeft(node: AVLNode<T>): AVLNode<T> {
        val pivot = node.right ?: return node
        node.right = pivot.left
        pivot.left = node
        updateHeight(node)
        updateHeight(pivot)
        return pivot
    }

    private fun updateHeight(node: AVLNode<T>) {
        node.height = 1 + maxOf(heightOf(node.left), heightOf(node.right))
    }

    private fun balanceFactor(node: AVLNode<T>): Int = heightOf(node.left) - heightOf(node.right)

    private fun heightOf(node: AVLNode<T>?): Int = node?.height ?: 0

    private fun minimumNode(node: AVLNode<T>): AVLNode<T> {
        var current = node
        while (current.left != null) {
            current = current.left!!
        }
        return current
    }

    private fun inorderValues(): Sequence<T> = sequence {
        val stack = ArrayList<AVLNode<T>>()
        var node = rootNode
        while (node != null || stack.isNotEmpty()) {
            while (node != null) {
                stack.add(node)
                node = node.left
            }
            val current = stack.removeAt(stack.size - 1)
            yield(current.value)
            node = current.right
        }
    }
}
