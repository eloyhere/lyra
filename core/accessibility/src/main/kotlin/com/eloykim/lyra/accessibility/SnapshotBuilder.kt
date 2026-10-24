package com.eloykim.lyra.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.eloykim.lyra.model.IntRect
import com.eloykim.lyra.model.ScreenSnapshot
import com.eloykim.lyra.model.UiNode
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次抓取的「结果」：不可变快照 + 与之逐项对应的活节点。
 *
 * 活节点只在本次动作的瞬间有效，不跨调用保存。
 */
internal class LiveScreen(
    val snapshot: ScreenSnapshot,
    val nodes: List<AccessibilityNodeInfo>,
)

/**
 * 把 AccessibilityNodeInfo 树拷贝成不可变快照。
 *
 * 原则：
 * - 不持有 node（会失效），只拷贝字段；
 * - 深度 40 / 节点 2500 上限，超出标记 truncated；
 * - 编号按 BFS 顺序分配，父节点编号一定小于子节点；
 * - snapshot.nodes[i] 与 nodes[i] 一一对应。
 */
internal object SnapshotBuilder {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 2500

    private val sequence = AtomicLong(0)

    fun captureWithNodes(service: AccessibilityService): LiveScreen? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null

        val nodes = ArrayList<UiNode>(256)
        val liveNodes = ArrayList<AccessibilityNodeInfo>(256)
        val queue = ArrayDeque<Entry>()
        queue.addLast(Entry(root, null, 0))

        var nextId = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                break
            }
            val entry = queue.removeFirst()
            val node = entry.node
            val id = nextId++
            val copied = readNode(node, id, entry.parentId, entry.depth) ?: continue
            nodes += copied
            liveNodes += node

            if (entry.depth >= MAX_DEPTH) {
                truncated = true
                continue
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.addLast(Entry(child, id, entry.depth + 1))
            }
        }

        val packageName = runCatching { root.packageName?.toString() }.getOrNull().orEmpty()
        val snapshot = ScreenSnapshot(
            id = sequence.incrementAndGet(),
            packageName = packageName,
            capturedAt = System.currentTimeMillis(),
            nodes = nodes,
            truncated = truncated,
        )
        return LiveScreen(snapshot, liveNodes)
    }

    fun capture(service: AccessibilityService): ScreenSnapshot? =
        captureWithNodes(service)?.snapshot

    private fun readNode(
        node: AccessibilityNodeInfo,
        id: Int,
        parentId: Int?,
        depth: Int,
    ): UiNode? = runCatching {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        UiNode(
            id = id,
            parentId = parentId,
            packageName = node.packageName?.toString().orEmpty(),
            className = node.className?.toString().orEmpty(),
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            depth = depth,
        )
    }.getOrNull()

    private class Entry(
        val node: AccessibilityNodeInfo,
        val parentId: Int?,
        val depth: Int,
    )
}
