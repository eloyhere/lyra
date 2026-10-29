package com.eloykim.lyra.agent

/**
 * Agent 与手机之间的唯一通道。
 *
 * 实现方是 AIDL 的 AutomationClient；测试里换成 Fake，循环逻辑就不需要设备。
 */
interface AutomationGateway {

    suspend fun observe(): Observation

    suspend fun tap(nodeId: Int, obsId: Long): ActionResult

    suspend fun setText(nodeId: Int, obsId: Long, text: String): ActionResult

    suspend fun scroll(direction: Int, obsId: Long): ActionResult

    suspend fun launch(packageName: String): ActionResult

    suspend fun back(): ActionResult
}

data class Observation(
    val obsId: Long,
    val packageName: String,
    val elements: List<ObservedElement>,
) {
    val interactiveCount: Int get() = elements.count { it.clickable || it.editable }

    fun element(nodeId: Int): ObservedElement? = elements.firstOrNull { it.id == nodeId }

    fun findByText(fragment: String): ObservedElement? =
        elements.firstOrNull { it.label.contains(fragment, ignoreCase = true) }
}

data class ObservedElement(
    val id: Int,
    val label: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
)

sealed interface ActionResult {

    /**
     * 动作之后（或失败时）服务端的最新观察。
     *
     * 失败时也带观察很重要：服务端每次动作都会重新抓屏、推进 obsId，
     * 调用方如果不跟着更新，下一次动作就会因为「基于旧观察」被拒绝。
     */
    val observation: Observation?

    data class Ok(override val observation: Observation?) : ActionResult

    /** 命中硬边界（支付/凭证/自身/系统），不可重试。 */
    data class Blocked(
        val code: String,
        val message: String,
        val scope: String?,
        override val observation: Observation? = null,
    ) : ActionResult

    /** 需要用户当场授权。 */
    data class NeedsConsent(
        val challenge: String?,
        val scope: String?,
        val message: String,
        override val observation: Observation? = null,
    ) : ActionResult

    data class Failed(
        val code: String,
        val message: String,
        override val observation: Observation? = null,
    ) : ActionResult
}
