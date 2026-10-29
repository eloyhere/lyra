package com.eloykim.lyra.amp.protocol.structure

import java.util.IdentityHashMap

/**
 * 无序二叉树，移植自 `src/structure/binaryTree.ts`。
 *
 * 这棵树是形状导向而非搜索导向：值没有顺序，`insert` 按层序填补第一个缺失的
 * 子位，并提供四种遍历顺序供观察。节点对象通过 [BinaryTree.root] 刻意暴露；
 * 手工组装的树应传给构造函数，以便按现有节点计算 `size`。
 *
 * `size` 由 [BinaryTree.insert] 与 [BinaryTree.clear] 维护。直接修改暴露的
 * 节点不会被 `size` 追踪；手工重构后请重新构造一棵树。
 */

/**
 * 二叉树的节点。缺失的子节点用 `null` 表示。
 */
class BinaryTreeNode<T>(
    /** 存储的值。 */
    var value: T,
    /** 左子节点；缺失为 `null`。 */
    var left: BinaryTreeNode<T>? = null,
    /** 右子节点；缺失为 `null`。 */
    var right: BinaryTreeNode<T>? = null,
)

/**
 * [BinaryTree.traverse] 支持的遍历顺序。
 */
enum class BinaryTreeOrder(val wire: String) {
    PREORDER("preorder"),
    INORDER("inorder"),
    POSTORDER("postorder"),
    LEVELORDER("levelorder"),
    ;

    companion object {
        fun fromWire(value: String): BinaryTreeOrder =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown binary tree traversal order: $value")
    }
}

/**
 * 支持层序构造、插入与遍历的二叉树。
 *
 * 空树 `size` 为 0、`height` 为 0；单节点高度为 1。用现有根构造的树在构造函数里
 * 计算一次 size。所有遍历都是迭代式的，异常深的手工树不会消耗调用栈。
 */
class BinaryTree<T>(root: BinaryTreeNode<T>? = null) : Iterable<T> {

    private var rootNode: BinaryTreeNode<T>? = root
    private var count: Int = countNodes(root)

    /** 根节点；空树为 `null`。 */
    val root: BinaryTreeNode<T>? get() = rootNode

    /**
     * 可达节点数量。
     *
     * 由 [insert] 与 [clear] 维护。用构造函数建立的树从一次遍历计数开始；
     * 直接修改暴露节点不会反映到这里。
     */
    val size: Int get() = count

    /**
     * 最长根到叶路径上的节点数。
     *
     * 空树高度 0，叶子高度 1。按需以 O(n) 计算。
     */
    val height: Int
        get() {
            val start = rootNode ?: return 0
            var maximum = 0
            val stack = ArrayList<Pair<BinaryTreeNode<T>, Int>>()
            stack.add(start to 1)
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeAt(stack.size - 1)
                if (depth > maximum) {
                    maximum = depth
                }
                node.left?.let { stack.add(it to depth + 1) }
                node.right?.let { stack.add(it to depth + 1) }
            }
            return maximum
        }

    /** 树是否不含节点。 */
    val isEmpty: Boolean get() = count == 0

    /**
     * 按层序把值插入第一个缺失的子位。
     *
     * 因此从完整形状开始的树会保持完整；对手工构建的树，搜索仍是广度优先的，
     * 最浅的可用槽位先被填补。`size` 随之递增。
     *
     * @return 新创建的节点。
     */
    fun insert(value: T): BinaryTreeNode<T> {
        val node = BinaryTreeNode(value)
        val start = rootNode
        if (start == null) {
            rootNode = node
            count = 1
            return node
        }
        val queue = ArrayList<BinaryTreeNode<T>>()
        queue.add(start)
        var index = 0
        while (index < queue.size) {
            val current = queue[index]
            index += 1
            if (current.left == null) {
                current.left = node
                count += 1
                return node
            }
            queue.add(current.left!!)
            if (current.right == null) {
                current.right = node
                count += 1
                return node
            }
            queue.add(current.right!!)
        }
        throw IllegalStateException("BinaryTree: no insertion point found")
    }

    /** 移除所有节点并把 size 重置为 0。 */
    fun clear() {
        rootNode = null
        count = 0
    }

    /**
     * 按请求的顺序遍历值。`null` 空洞被跳过。遍历是迭代式的，因此不消耗调用栈
     * 深度。迭代期间修改树不受支持。
     *
     * @param order 遍历顺序；默认为 [BinaryTreeOrder.LEVELORDER]。
     */
    fun traverse(order: BinaryTreeOrder = BinaryTreeOrder.LEVELORDER): Sequence<T> = when (order) {
        BinaryTreeOrder.PREORDER -> preorderValues()
        BinaryTreeOrder.INORDER -> inorderValues()
        BinaryTreeOrder.POSTORDER -> postorderValues()
        BinaryTreeOrder.LEVELORDER -> levelOrderValues()
    }

    /** 按请求的遍历顺序收集值。 */
    fun toArray(order: BinaryTreeOrder = BinaryTreeOrder.LEVELORDER): List<T> = traverse(order).toList()

    /**
     * 交换每个左右子节点来镜像整棵树。
     *
     * 形状不变，因此 [size] 与 [height] 保持不变。返回自身以便链式调用。
     */
    fun invert(): BinaryTree<T> {
        val start = rootNode ?: return this
        val stack = ArrayList<BinaryTreeNode<T>>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val left = node.left
            node.left = node.right
            node.right = left
            node.left?.let { stack.add(it) }
            node.right?.let { stack.add(it) }
        }
        return this
    }

    /**
     * 除最后一层外是否全满，且最后一层从左往右填充。空树视为完整。
     */
    fun isComplete(): Boolean {
        val start = rootNode ?: return true
        val queue = ArrayList<BinaryTreeNode<T>?>()
        queue.add(start)
        var sawHole = false
        var index = 0
        while (index < queue.size) {
            val node = queue[index]
            index += 1
            if (node == null) {
                sawHole = true
                continue
            }
            if (sawHole) {
                return false
            }
            queue.add(node.left)
            queue.add(node.right)
        }
        return true
    }

    /** 每个节点是否有零个或两个子节点。空树视为满。 */
    fun isFull(): Boolean {
        val start = rootNode ?: return true
        val stack = ArrayList<BinaryTreeNode<T>>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val hasLeft = node.left != null
            val hasRight = node.right != null
            if (hasLeft != hasRight) {
                return false
            }
            node.left?.let { stack.add(it) }
            node.right?.let { stack.add(it) }
        }
        return true
    }

    /**
     * 深拷贝整棵树。
     *
     * 每个节点都重新创建，副本与原树不共享节点。副本的 size 由克隆后的形状计算。
     */
    fun clone(): BinaryTree<T> {
        val start = rootNode ?: return BinaryTree()
        val copies = IdentityHashMap<BinaryTreeNode<T>, BinaryTreeNode<T>>()
        val originals = ArrayList<BinaryTreeNode<T>>()
        val stack = ArrayList<BinaryTreeNode<T>>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            copies[node] = BinaryTreeNode(node.value)
            originals.add(node)
            node.left?.let { stack.add(it) }
            node.right?.let { stack.add(it) }
        }
        for (original in originals) {
            val copy = copies[original] ?: continue
            original.left?.let { copy.left = copies[it] }
            original.right?.let { copy.right = copies[it] }
        }
        return BinaryTree(copies[start])
    }

    /** 先序（节点、左、右）遍历值。等价于 `traverse(PREORDER)`。 */
    override fun iterator(): Iterator<T> = preorderValues().iterator()

    private fun preorderValues(): Sequence<T> = sequence {
        val start = rootNode ?: return@sequence
        val stack = ArrayList<BinaryTreeNode<T>>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            yield(node.value)
            node.right?.let { stack.add(it) }
            node.left?.let { stack.add(it) }
        }
    }

    private fun inorderValues(): Sequence<T> = sequence {
        val stack = ArrayList<BinaryTreeNode<T>>()
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

    private fun postorderValues(): Sequence<T> = sequence {
        val start = rootNode ?: return@sequence
        val stack = ArrayList<Pair<BinaryTreeNode<T>, Boolean>>()
        stack.add(start to false)
        while (stack.isNotEmpty()) {
            val (node, visited) = stack.removeAt(stack.size - 1)
            if (!visited) {
                stack.add(node to true)
                node.right?.let { stack.add(it to false) }
                node.left?.let { stack.add(it to false) }
            } else {
                yield(node.value)
            }
        }
    }

    private fun levelOrderValues(): Sequence<T> = sequence {
        val start = rootNode ?: return@sequence
        val queue = ArrayList<BinaryTreeNode<T>>()
        queue.add(start)
        var index = 0
        while (index < queue.size) {
            val node = queue[index]
            index += 1
            yield(node.value)
            node.left?.let { queue.add(it) }
            node.right?.let { queue.add(it) }
        }
    }

    companion object {

        /**
         * 用层序数组建树。
         *
         * 下标 0 是根；下标 `i` 的子节点在 `2i + 1` 与 `2i + 2`。`null` 项是空洞。
         * 末尾的 `null` 被忽略；位于空洞之下的项不可达，因此被省略。
         */
        fun <T> fromLevelOrder(values: List<T?>): BinaryTree<T> {
            var last = values.size - 1
            while (last >= 0 && values[last] == null) {
                last -= 1
            }
            if (last < 0) {
                return BinaryTree()
            }

            val nodes = ArrayList<BinaryTreeNode<T>?>()
            var root: BinaryTreeNode<T>? = null
            for (index in 0..last) {
                val item = values[index]
                if (item == null) {
                    nodes.add(null)
                    continue
                }
                val value: T = item
                if (index == 0) {
                    val rootNode = BinaryTreeNode<T>(value)
                    root = rootNode
                    nodes.add(rootNode)
                    continue
                }
                val parent = nodes[(index - 1) / 2]
                if (parent == null) {
                    nodes.add(null)
                    continue
                }
                val node = BinaryTreeNode<T>(value)
                if (index % 2 == 1) {
                    parent.left = node
                } else {
                    parent.right = node
                }
                nodes.add(node)
            }
            return BinaryTree(root)
        }

        /**
         * 用层序的值建立完整树。
         *
         * 每个位置都被填满，因此结果总是完整的。空迭代器产生空树。
         */
        fun <T> fromArray(values: Iterable<T>): BinaryTree<T> {
            val items = values.toList()
            if (items.isEmpty()) {
                return BinaryTree()
            }
            val nodes = ArrayList<BinaryTreeNode<T>>(items.size)
            for (item in items) {
                nodes.add(BinaryTreeNode(item))
            }
            for (index in nodes.indices) {
                val leftIndex = 2 * index + 1
                val rightIndex = 2 * index + 2
                if (leftIndex < nodes.size) {
                    nodes[index].left = nodes[leftIndex]
                }
                if (rightIndex < nodes.size) {
                    nodes[index].right = nodes[rightIndex]
                }
            }
            return BinaryTree(nodes[0])
        }

        /** 不递归地统计可达节点。 */
        private fun <T> countNodes(root: BinaryTreeNode<T>?): Int {
            if (root == null) {
                return 0
            }
            var count = 0
            val stack = ArrayList<BinaryTreeNode<T>>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.size - 1)
                count += 1
                node.left?.let { stack.add(it) }
                node.right?.let { stack.add(it) }
            }
            return count
        }
    }
}
