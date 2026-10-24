package com.eloykim.lyra.model

/**
 * 与平台无关的矩形，避免 core:model 依赖 android.graphics.Rect。
 */
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * 某一时刻界面节点树中的一个节点（不可变拷贝）。
 *
 * 不持有 AccessibilityNodeInfo —— 节点会失效，快照必须自包含。
 */
data class UiNode(
    val id: Int,
    val parentId: Int?,
    val packageName: String,
    val className: String,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val bounds: IntRect,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val depth: Int = 0,
) {
    /** 简短类名：`android.widget.Button` -> `Button` */
    val shortClassName: String
        get() = className.substringAfterLast('.').removeSuffix("View").ifEmpty { "View" }

    val hasLabel: Boolean
        get() = !text.isNullOrBlank() || !contentDescription.isNullOrBlank()

    /** 给模型看的一行摘要：`button "去结算"` */
    val shortLabel: String
        get() {
            val kind = shortClassName.lowercase()
            val label = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }
            if (label == null) return kind
            val trimmed = if (label.length > 40) label.take(40) + "…" else label
            return "$kind \"$trimmed\""
        }
}

/**
 * 一次屏幕快照。id 单调递增，用于判断「观察是否新鲜」。
 */
data class ScreenSnapshot(
    val id: Long,
    val packageName: String,
    val capturedAt: Long,
    val nodes: List<UiNode>,
    val truncated: Boolean = false,
) {
    val interactiveCount: Int get() = nodes.count { it.clickable || it.editable }
    val isEmpty: Boolean get() = nodes.isEmpty()

    fun findById(nodeId: Int): UiNode? = nodes.firstOrNull { it.id == nodeId }
}
