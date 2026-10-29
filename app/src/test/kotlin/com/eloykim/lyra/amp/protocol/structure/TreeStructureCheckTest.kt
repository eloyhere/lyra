package com.eloykim.lyra.amp.protocol.structure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tools/tree-structure-check.ts` 的 Kotlin 版本：无序二叉树、不平衡 BST、
 * AVL 树与红黑树，包含针对 `Set` 与有序数组模型的随机差分检查。
 */
class TreeStructureCheckTest {

    private fun assertValid(errors: List<String>) {
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun binaryTreeEmptyTreeOperations() {        val tree = BinaryTree<Int>()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)
        assertTrue(tree.isEmpty)
        assertNull(tree.root)
        assertEquals(emptyList<Int>(), tree.toArray())
        assertEquals(emptyList<Int>(), tree.toArray(BinaryTreeOrder.PREORDER))
        assertEquals(emptyList<Int>(), tree.toList())
        assertFalse(tree.traverse().iterator().hasNext())
        assertTrue(tree.isComplete())
        assertTrue(tree.isFull())

        val copy = tree.clone()
        assertEquals(0, copy.size)
        assertNull(copy.root)
        assertEquals(tree, tree.invert())
        tree.clear()
        assertEquals(0, tree.size)

        assertTrue(BinaryTree.fromLevelOrder<Int>(listOf(null, null)).isEmpty)
        assertTrue(BinaryTree.fromArray(emptyList<Int>()).isEmpty)
    }

    @Test
    fun binaryTreeLevelOrderConstruction() {
        val tree = BinaryTree.fromLevelOrder(listOf<Int?>(1, 2, 3, null, 4, null, 5))
        assertEquals(5, tree.size)
        assertEquals(3, tree.height)
        assertEquals(listOf(1, 2, 3, 4, 5), tree.toArray())
        assertEquals(listOf(1, 2, 4, 3, 5), tree.toArray(BinaryTreeOrder.PREORDER))
        assertEquals(listOf(2, 4, 1, 3, 5), tree.toArray(BinaryTreeOrder.INORDER))
        assertEquals(listOf(4, 2, 5, 3, 1), tree.toArray(BinaryTreeOrder.POSTORDER))
        assertFalse(tree.isComplete())
        assertFalse(tree.isFull())

        val trailing = BinaryTree.fromLevelOrder(listOf<Int?>(1, 2, 3, null, null, null))
        assertEquals(3, trailing.size)
        assertEquals(2, trailing.height)
        assertEquals(listOf(1, 2, 3), trailing.toArray())
        assertTrue(trailing.isComplete())
        assertTrue(trailing.isFull())

        val complete = BinaryTree.fromArray(listOf(1, 2, 3, 4, 5))
        assertEquals(5, complete.size)
        assertEquals(3, complete.height)
        assertEquals(listOf(1, 2, 3, 4, 5), complete.toArray())
        assertTrue(complete.isComplete())
        assertTrue(complete.isFull())

        val almost = BinaryTree.fromArray(listOf(1, 2, 3, 4))
        assertTrue(almost.isComplete())
        assertFalse(almost.isFull())
    }

    @Test
    fun binaryTreeEveryTraversalOrder() {
        val tree = BinaryTree.fromArray(listOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), tree.toArray())
        assertEquals(listOf(1, 2, 4, 5, 3, 6, 7), tree.toArray(BinaryTreeOrder.PREORDER))
        assertEquals(listOf(4, 2, 5, 1, 6, 3, 7), tree.toArray(BinaryTreeOrder.INORDER))
        assertEquals(listOf(4, 5, 2, 6, 7, 3, 1), tree.toArray(BinaryTreeOrder.POSTORDER))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), tree.traverse(BinaryTreeOrder.LEVELORDER).toList())
        assertEquals(listOf(1, 2, 4, 5, 3, 6, 7), tree.toList())
        assertEquals(7, tree.size)
        assertEquals(3, tree.height)
        assertTrue(tree.isComplete())
        assertTrue(tree.isFull())
    }

    @Test
    fun binaryTreeInsertFillsTheFirstMissingLevelOrderSlot() {
        val tree = BinaryTree<Int>()
        val root = tree.insert(1)
        assertEquals(root, tree.root)
        tree.insert(2)
        val third = tree.insert(3)
        tree.insert(4)
        tree.insert(5)
        assertEquals(5, tree.size)
        assertEquals(listOf(1, 2, 3, 4, 5), tree.toArray())
        assertEquals(3, tree.height)
        assertTrue(tree.isComplete())
        assertTrue(tree.isFull())

        val sixth = tree.insert(6)
        assertEquals(sixth, third.left)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), tree.toArray())
        assertTrue(tree.isComplete())
        assertFalse(tree.isFull())

        tree.insert(7)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), tree.toArray())
        assertTrue(tree.isComplete())
        assertTrue(tree.isFull())

        val manualRoot = BinaryTreeNode(1)
        manualRoot.left = BinaryTreeNode(2)
        val manual = BinaryTree(manualRoot)
        assertEquals(2, manual.size)
        val manualThird = manual.insert(3)
        assertEquals(manualThird, manualRoot.right)
        val manualFourth = manual.insert(4)
        assertEquals(manualFourth, manualRoot.left?.left)
        assertEquals(4, manual.size)
        assertEquals(listOf(1, 2, 3, 4), manual.toArray())
    }

    @Test
    fun binaryTreeInvertMirrorsTheShapeInPlace() {
        val tree = BinaryTree.fromArray(listOf(1, 2, 3, 4, 5))
        assertEquals(tree, tree.invert())
        assertEquals(listOf(1, 3, 2, 5, 4), tree.toArray())
        assertEquals(listOf(1, 3, 2, 5, 4), tree.toArray(BinaryTreeOrder.PREORDER))
        assertEquals(5, tree.size)
        assertEquals(3, tree.height)
        assertFalse(tree.isComplete())
        assertTrue(tree.isFull())

        tree.invert()
        assertEquals(listOf(1, 2, 3, 4, 5), tree.toArray())
        assertEquals(listOf(1, 2, 4, 5, 3), tree.toArray(BinaryTreeOrder.PREORDER))
        assertTrue(tree.isComplete())
    }

    @Test
    fun binaryTreeCloneIsADeepIndependentCopy() {
        val original = BinaryTree.fromLevelOrder(listOf<Int?>(1, 2, null, 3))
        val copy = original.clone()
        assertNotEquals(original.root, copy.root)
        assertNotEquals(original.root?.left, copy.root?.left)
        assertEquals(original.toArray(), copy.toArray())
        assertEquals(3, copy.size)

        val copyRoot = copy.root
        val originalRoot = original.root
        assertNotNull(copyRoot)
        assertNotNull(originalRoot)
        copyRoot!!.value = 99
        copyRoot.left?.value = 98
        assertEquals(1, originalRoot!!.value)
        assertEquals(2, originalRoot.left?.value)
        assertEquals(listOf(1, 2, 3), original.toArray(BinaryTreeOrder.PREORDER))

        copy.insert(4)
        assertEquals(4, copy.size)
        assertEquals(3, original.size)
    }

    @Test
    fun binaryTreeConstructorCountsManualNodesAndClearResets() {
        val root = BinaryTreeNode(1, BinaryTreeNode(2), BinaryTreeNode(3))
        val tree = BinaryTree(root)
        assertEquals(3, tree.size)
        assertEquals(2, tree.height)
        assertEquals(listOf(1, 2, 3), tree.toArray())
        assertTrue(tree.isComplete())
        assertTrue(tree.isFull())

        tree.clear()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)
        assertNull(tree.root)

        val leaf = BinaryTree(BinaryTreeNode("only"))
        assertEquals(1, leaf.size)
        assertEquals(1, leaf.height)
        assertTrue(leaf.isComplete())
        assertTrue(leaf.isFull())
    }

    @Test
    fun binarySearchTreeOrderingDuplicatesBoundsAndIteration() {
        val tree = BinarySearchTree<Int>()
        val values = listOf(50, 30, 70, 20, 40, 60, 80, 35, 45, 65)
        for (value in values) {
            assertTrue(tree.insert(value))
        }

        assertEquals(10, tree.size)
        assertEquals(4, tree.height)
        assertFalse(tree.isEmpty)
        assertEquals(listOf(20, 30, 35, 40, 45, 50, 60, 65, 70, 80), tree.toArray())
        assertEquals(tree.toArray(), tree.toList())
        assertTrue(tree.has(35))
        assertFalse(tree.has(36))
        assertEquals(20, tree.min())
        assertEquals(80, tree.max())
        assertFalse(tree.insert(50))
        assertEquals(10, tree.size)
        assertValid(tree.validate())

        assertEquals(50, tree.floor(50))
        assertEquals(50, tree.floor(55))
        assertNull(tree.floor(19))
        assertEquals(80, tree.floor(80))
        assertEquals(80, tree.floor(100))
        assertEquals(50, tree.ceil(50))
        assertEquals(60, tree.ceil(55))
        assertEquals(20, tree.ceil(19))
        assertEquals(80, tree.ceil(80))
        assertNull(tree.ceil(81))
    }

    @Test
    fun binarySearchTreeDeleteHandlesZeroOneAndTwoChildren() {
        val leafTree = BinarySearchTree<Int>()
        for (value in listOf(50, 30, 70, 20)) {
            leafTree.insert(value)
        }
        assertTrue(leafTree.delete(20))
        assertFalse(leafTree.delete(20))
        assertFalse(leafTree.has(20))
        assertEquals(listOf(30, 50, 70), leafTree.toArray())
        assertValid(leafTree.validate())

        val oneChildTree = BinarySearchTree<Int>()
        for (value in listOf(50, 30, 70, 20)) {
            oneChildTree.insert(value)
        }
        assertTrue(oneChildTree.delete(30))
        assertEquals(listOf(20, 50, 70), oneChildTree.toArray())
        assertValid(oneChildTree.validate())

        val twoChildTree = BinarySearchTree<Int>()
        for (value in listOf(50, 30, 70, 20, 40, 60, 80, 35, 45, 65)) {
            twoChildTree.insert(value)
        }
        assertTrue(twoChildTree.delete(50))
        assertFalse(twoChildTree.delete(999))
        assertEquals(listOf(20, 30, 35, 40, 45, 60, 65, 70, 80), twoChildTree.toArray())
        assertEquals(9, twoChildTree.size)
        assertValid(twoChildTree.validate())
    }

    @Test
    fun binarySearchTreeEmptyTreeAndInvalidValues() {
        val tree = BinarySearchTree<Int>()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)
        assertTrue(tree.isEmpty)
        assertEquals(emptyList<Int>(), tree.toArray())
        assertEquals(emptyList<Int>(), tree.toList())
        assertFalse(tree.has(1))
        assertFalse(tree.delete(1))
        assertNull(tree.min())
        assertNull(tree.max())
        assertNull(tree.floor(1))
        assertNull(tree.ceil(1))
        assertValid(tree.validate())

        assertTrue(tree.insert(5))
        assertEquals(1, tree.height)
        assertEquals(5, tree.min())
        assertEquals(5, tree.max())
        assertTrue(tree.delete(5))
        assertTrue(tree.isEmpty)

        val invalid = BinarySearchTree<Int>()
        invalid.insert(1)
        // Kotlin 的 Int 不能表示 NaN，用 Double 树验证比较器拒绝 NaN 的行为。
        val doubles = BinarySearchTree<Double>()
        doubles.insert(1.0)
        assertThrows(IllegalArgumentException::class.java) { doubles.insert(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { doubles.has(Double.NaN) }
        assertEquals(1, doubles.size)

        val objectTree = BinarySearchTree<Any>()
        objectTree.insert(Any())
        assertThrows(IllegalArgumentException::class.java) { objectTree.insert(Any()) }
    }

    @Test
    fun binarySearchTreeRandomizedOperationsAgainstSet() {
        val random = Lcg(0x5157)
        val tree = BinarySearchTree<Int>()
        val expected = HashSet<Int>()
        val sorted = ArrayList<Int>()

        for (step in 0 until 3_000) {
            val value = Math.floor(random.next() * 200).toInt()
            if (random.next() < 0.55) {
                val inserted = !expected.contains(value)
                assertEquals(inserted, tree.insert(value))
                if (inserted) {
                    expected.add(value)
                    insertSortedUnique(sorted, value)
                }
            } else {
                val removed = expected.remove(value)
                assertEquals(removed, tree.delete(value))
                if (removed) {
                    removeSortedUnique(sorted, value)
                }
            }
            assertEquals(expected.size, tree.size)
            if (step % 250 == 0) {
                assertValid(tree.validate())
            }
        }

        assertEquals(sorted, tree.toArray())
        assertValid(tree.validate())
    }

    @Test
    fun avlTreeEmptyTreeAndInvalidValues() {
        val tree = AVLTree<Int>()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)
        assertTrue(tree.isEmpty)
        assertEquals(emptyList<Int>(), tree.toArray())
        assertEquals(emptyList<Int>(), tree.toList())
        assertFalse(tree.has(1))
        assertFalse(tree.delete(1))
        assertNull(tree.min())
        assertNull(tree.max())
        assertNull(tree.floor(1))
        assertNull(tree.ceil(1))
        assertValid(tree.validate())

        assertTrue(tree.insert(5))
        assertEquals(1, tree.height)
        tree.clear()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)

        val invalid = AVLTree<Double>()
        invalid.insert(1.0)
        assertThrows(IllegalArgumentException::class.java) { invalid.insert(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { invalid.has(Double.NaN) }
    }

    @Test
    fun avlTreeBasicOperationsAndRebalancingDelete() {
        val tree = AVLTree<Int>()
        for (value in listOf(10, 20, 30, 40, 50, 25)) {
            tree.insert(value)
        }
        assertEquals(6, tree.size)
        assertEquals(3, tree.height)
        assertEquals(listOf(10, 20, 25, 30, 40, 50), tree.toArray())
        assertEquals(10, tree.min())
        assertEquals(50, tree.max())
        assertTrue(tree.has(25))
        assertFalse(tree.has(26))
        assertFalse(tree.insert(25))
        assertEquals(6, tree.size)
        assertValid(tree.validate())

        assertEquals(25, tree.floor(26))
        assertNull(tree.floor(9))
        assertEquals(30, tree.ceil(26))
        assertNull(tree.ceil(51))

        assertTrue(tree.delete(40))
        assertFalse(tree.delete(40))
        assertEquals(listOf(10, 20, 25, 30, 50), tree.toArray())
        assertValid(tree.validate())
    }

    @Test
    fun avlTreeSortedInsertionStaysLogarithmic() {
        val count = 4_095
        val tree = AVLTree<Int>()
        for (value in 0 until count) {
            tree.insert(value)
        }

        assertEquals(count, tree.size)
        assertValid(tree.validate())
        val limit = 2 * Math.log(count + 1.0) / Math.log(2.0)
        assertTrue("sorted AVL height ${tree.height} exceeds logarithmic limit $limit", tree.height <= limit)
        assertEquals(0, tree.min())
        assertEquals(count - 1, tree.max())
        assertEquals(count, tree.toArray().size)

        var value = 0
        while (value < count) {
            assertTrue(tree.delete(value))
            if (value % 500 == 0) {
                assertValid(tree.validate())
            }
            value += 2
        }
        assertEquals(2_047, tree.size)
        assertValid(tree.validate())
    }

    @Test
    fun avlTree10000RandomizedOperationsAgainstSetAndSortedArray() {
        val random = Lcg(0xa711)
        val tree = AVLTree<Int>()
        val expected = HashSet<Int>()
        val sorted = ArrayList<Int>()

        for (step in 0 until 10_000) {
            val value = Math.floor(random.next() * 2_000).toInt()
            if (random.next() < 0.55) {
                val inserted = !expected.contains(value)
                assertEquals(inserted, tree.insert(value))
                if (inserted) {
                    expected.add(value)
                    insertSortedUnique(sorted, value)
                }
            } else {
                val removed = expected.remove(value)
                assertEquals(removed, tree.delete(value))
                if (removed) {
                    removeSortedUnique(sorted, value)
                }
            }
            assertEquals(expected.size, tree.size)

            if (step % 500 == 0) {
                assertValid(tree.validate())
                assertEquals(sorted, tree.toArray())
                assertEquals(expected.contains(value), tree.has(value))
                val probe = Math.floor(random.next() * 2_100).toInt()
                assertEquals(expectedFloor(sorted, probe), tree.floor(probe))
                assertEquals(expectedCeil(sorted, probe), tree.ceil(probe))
                if (sorted.isNotEmpty()) {
                    assertEquals(sorted[0], tree.min())
                    assertEquals(sorted[sorted.size - 1], tree.max())
                }
            }
        }

        assertValid(tree.validate())
        assertEquals(sorted, tree.toArray())

        val remaining = expected.toList()
        for ((index, value) in remaining.withIndex()) {
            assertTrue(tree.delete(value))
            if (index % 250 == 0) {
                assertValid(tree.validate())
            }
        }
        assertEquals(0, tree.size)
        assertTrue(tree.isEmpty)
        assertEquals(emptyList<Int>(), tree.toArray())
        assertValid(tree.validate())
    }

    @Test
    fun redBlackTreeEmptyTreeAndInvalidValues() {
        val tree = RedBlackTree<Int>()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)
        assertTrue(tree.isEmpty)
        assertEquals(emptyList<Int>(), tree.toArray())
        assertEquals(emptyList<Int>(), tree.toList())
        assertFalse(tree.has(1))
        assertFalse(tree.delete(1))
        assertNull(tree.min())
        assertNull(tree.max())
        assertNull(tree.floor(1))
        assertNull(tree.ceil(1))
        assertValid(tree.validate())

        assertTrue(tree.insert(5))
        assertEquals(1, tree.height)
        tree.clear()
        assertEquals(0, tree.size)
        assertEquals(0, tree.height)

        val invalid = RedBlackTree<Double>()
        invalid.insert(1.0)
        assertThrows(IllegalArgumentException::class.java) { invalid.insert(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { invalid.has(Double.NaN) }
    }

    @Test
    fun redBlackTreeDeleteRootLeafAndOneChildNodes() {
        val tree = RedBlackTree<Int>()
        for (value in listOf(20, 10, 30, 5, 15, 25, 35)) {
            tree.insert(value)
        }
        assertEquals(7, tree.size)
        assertEquals(3, tree.height)
        assertEquals(listOf(5, 10, 15, 20, 25, 30, 35), tree.toArray())
        assertValid(tree.validate())
        assertEquals(5, tree.min())
        assertEquals(35, tree.max())
        assertTrue(tree.has(25))
        assertFalse(tree.insert(20))
        assertEquals(20, tree.floor(22))
        assertEquals(25, tree.ceil(22))

        // 第一个插入的值仍是根：双子节点删除。
        assertTrue(tree.delete(20))
        assertFalse(tree.has(20))
        assertEquals(listOf(5, 10, 15, 25, 30, 35), tree.toArray())
        assertEquals(6, tree.size)
        assertValid(tree.validate())

        // 红色叶子。
        assertTrue(tree.delete(5))
        assertEquals(listOf(10, 15, 25, 30, 35), tree.toArray())
        assertEquals(5, tree.size)
        assertValid(tree.validate())

        val oneChild = RedBlackTree<Int>()
        for (value in listOf(20, 10, 30, 5)) {
            oneChild.insert(value)
        }
        assertTrue(oneChild.delete(10))
        assertEquals(listOf(5, 20, 30), oneChild.toArray())
        assertValid(oneChild.validate())

        val single = RedBlackTree<Int>()
        single.insert(42)
        assertTrue(single.delete(42))
        assertEquals(0, single.size)
        assertEquals(emptyList<Int>(), single.toArray())
        assertValid(single.validate())

        assertFalse(tree.delete(999))
    }

    @Test
    fun redBlackTreeInsertionRotationShapes() {
        val sequences = listOf(
            listOf(30, 20, 10),
            listOf(30, 10, 20),
            listOf(10, 20, 30),
            listOf(10, 30, 20),
        )
        for (sequence in sequences) {
            val tree = RedBlackTree<Int>()
            for (value in sequence) {
                assertTrue(tree.insert(value))
            }
            assertEquals(listOf(10, 20, 30), tree.toArray())
            assertEquals(2, tree.height)
            assertValid(tree.validate())
        }
    }

    @Test
    fun redBlackTreeSortedInsertionAndDeletionStayBalanced() {
        val count = 4_095
        val tree = RedBlackTree<Int>()
        for (value in 0 until count) {
            tree.insert(value)
        }

        assertEquals(count, tree.size)
        assertValid(tree.validate())
        val limit = 2 * Math.log(count + 1.0) / Math.log(2.0)
        assertTrue("sorted red-black height ${tree.height} exceeds logarithmic limit $limit", tree.height <= limit)

        var deleted = 0
        var value = 0
        while (value < count) {
            assertTrue(tree.delete(value))
            deleted += 1
            if (deleted % 250 == 0) {
                assertValid(tree.validate())
            }
            value += 3
        }
        assertEquals(count - deleted, tree.size)
        assertValid(tree.validate())

        val surviving = (0 until count).filter { it % 3 != 0 }
        assertEquals(surviving, tree.toArray())
    }

    @Test
    fun redBlackTree12000RandomizedOperationsAgainstSet() {
        val random = Lcg(0x5b17)
        val tree = RedBlackTree<Int>()
        val expected = HashSet<Int>()
        val sorted = ArrayList<Int>()

        for (step in 0 until 12_000) {
            val value = Math.floor(random.next() * 1_500).toInt()
            if (random.next() < 0.55) {
                val inserted = !expected.contains(value)
                assertEquals(inserted, tree.insert(value))
                if (inserted) {
                    expected.add(value)
                    insertSortedUnique(sorted, value)
                }
            } else {
                val removed = expected.remove(value)
                assertEquals(removed, tree.delete(value))
                if (removed) {
                    removeSortedUnique(sorted, value)
                }
            }
            assertEquals(expected.size, tree.size)
            if (step % 250 == 0) {
                assertValid(tree.validate())
            }
            if (step % 1000 == 0) {
                assertEquals(sorted, tree.toArray())
                assertEquals(expected.contains(value), tree.has(value))
            }
        }

        assertValid(tree.validate())
        assertEquals(sorted, tree.toArray())
        if (tree.size > 0) {
            assertTrue(tree.height <= 2 * Math.log(tree.size + 1.0) / Math.log(2.0))
        }

        val remaining = expected.toList()
        for ((index, value) in remaining.withIndex()) {
            assertTrue(tree.delete(value))
            if (index % 250 == 0) {
                assertValid(tree.validate())
            }
        }
        assertEquals(0, tree.size)
        assertTrue(tree.isEmpty)
        assertEquals(emptyList<Int>(), tree.toArray())
        assertValid(tree.validate())
    }

    /** 搜索树的最小公共表面，供共享比较器检查使用。 */
    private class TreeHarness<T>(
        val insert: (T) -> Boolean,
        val has: (T) -> Boolean,
        val delete: (T) -> Boolean,
        val toArray: () -> List<T>,
        val validate: () -> List<String>,
        val min: () -> T?,
        val max: () -> T?,
    )

    private fun assertSearchTreeContract(tree: TreeHarness<Int>) {
        val values = listOf(1, 2, 3, 4)
        for (value in values) {
            assertTrue(tree.insert(value))
        }
        assertEquals(listOf(4, 3, 2, 1), tree.toArray())
        assertEquals(4, tree.min())
        assertEquals(1, tree.max())
        assertFalse(tree.insert(3))
        assertTrue(tree.has(2))
        assertTrue(tree.delete(3))
        assertEquals(listOf(4, 2, 1), tree.toArray())
        assertValid(tree.validate())
    }

    @Test
    fun searchTreesHonorReverseAndObjectKeyComparators() {
        val reverse = reverseComparator(Comparator<Int> { a, b -> a - b })
        val reverseTrees = listOf(
            BinarySearchTree<Int>(reverse).let { tree ->
                TreeHarness(tree::insert, tree::has, tree::delete, tree::toArray, tree::validate, tree::min, tree::max)
            },
            AVLTree<Int>(reverse).let { tree ->
                TreeHarness(tree::insert, tree::has, tree::delete, tree::toArray, tree::validate, tree::min, tree::max)
            },
            RedBlackTree<Int>(reverse).let { tree ->
                TreeHarness(tree::insert, tree::has, tree::delete, tree::toArray, tree::validate, tree::min, tree::max)
            },
        )
        for (tree in reverseTrees) {
            assertSearchTreeContract(tree)
        }

        data class Task(val id: Int, val title: String)

        val compareTasks = byKey(select = { task: Task -> task.id })
        val taskTrees = listOf(
            BinarySearchTree<Task>(compareTasks).let { tree ->
                TreeHarness(tree::insert, tree::has, tree::delete, tree::toArray, tree::validate, tree::min, tree::max)
            },
            AVLTree<Task>(compareTasks).let { tree ->
                TreeHarness(tree::insert, tree::has, tree::delete, tree::toArray, tree::validate, tree::min, tree::max)
            },
            RedBlackTree<Task>(compareTasks).let { tree ->
                TreeHarness(tree::insert, tree::has, tree::delete, tree::toArray, tree::validate, tree::min, tree::max)
            },
        )
        for (tree in taskTrees) {
            assertTrue(tree.insert(Task(2, "two")))
            assertTrue(tree.insert(Task(1, "one")))
            assertTrue(tree.insert(Task(3, "three")))
            assertEquals(listOf(1, 2, 3), tree.toArray().map { it.id })
            assertFalse(tree.insert(Task(2, "duplicate")))
            assertTrue(tree.has(Task(2, "lookup")))
            assertTrue(tree.delete(Task(2, "removal")))
            assertEquals(listOf(1, 3), tree.toArray().map { it.id })
            assertValid(tree.validate())
        }
    }
}
