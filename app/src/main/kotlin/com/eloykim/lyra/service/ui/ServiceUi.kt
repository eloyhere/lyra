package com.eloykim.lyra.service.ui

import java.util.UUID

/** 一次 snackbar 请求。 */
data class SnackbarRequest(
    val message: String,
    val actionLabel: String? = null,
    val withDismissAction: Boolean = false,
    val durationMs: Long = 4_000L,
)

enum class SnackbarOutcome { Action, Dismissed, Unavailable }

data class SnackbarResult(
    val outcome: SnackbarOutcome,
    val message: String = "",
)

enum class DialogKind { Info, Confirm, Choose, Input, Select }

/**
 * 一次 dialog 请求。
 *
 * [dismissible] 为 false 时，返回键/点外部都不能关闭；用户必须点按钮。
 * 注意：这不是安全授权通道，不能替代 ConsentManager 的授权弹窗。
 */
data class DialogRequest(
    val id: String = UUID.randomUUID().toString(),
    val kind: DialogKind,
    val title: String,
    val message: String? = null,
    val confirmLabel: String = "确定",
    val dismissLabel: String = "取消",
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val initialValue: String = "",
    val selectedIndex: Int = 0,
    val secret: Boolean = false,
    val dismissible: Boolean = true,
)

enum class DialogChoice { Confirmed, Dismissed, Option, Selected, Input, Unavailable, Busy }

data class DialogResult(
    val choice: DialogChoice,
    val optionIndex: Int? = null,
    val optionLabel: String? = null,
    val text: String? = null,
    val message: String = "",
)

/**
 * UI 宿主：snackbar / dialog 需要真实的前台界面。
 *
 * 没有安装宿主时（后台、测试、无 Compose）返回 `Unavailable`，而不是假装成功。
 */
interface ServiceUiHost {
    suspend fun showSnackbar(request: SnackbarRequest): SnackbarResult
    suspend fun showDialog(request: DialogRequest): DialogResult
}

private object UnavailableServiceUiHost : ServiceUiHost {
    override suspend fun showSnackbar(request: SnackbarRequest): SnackbarResult =
        SnackbarResult(SnackbarOutcome.Unavailable, "UI 宿主未挂载")

    override suspend fun showDialog(request: DialogRequest): DialogResult =
        DialogResult(DialogChoice.Unavailable, message = "UI 宿主未挂载")
}

/**
 * 全局 UI 宿主注册点。
 *
 * Compose 侧由 [ServiceUiOverlay] 在进入 composition 时安装、离开时卸载。
 */
object ServiceUi {

    @Volatile
    private var current: ServiceUiHost = UnavailableServiceUiHost

    val host: ServiceUiHost get() = current

    val available: Boolean get() = current !== UnavailableServiceUiHost

    fun install(host: ServiceUiHost): ServiceUiHost {
        val previous = current
        current = host
        return previous
    }

    fun uninstall(host: ServiceUiHost) {
        if (current === host) current = UnavailableServiceUiHost
    }
}
