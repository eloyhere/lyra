package com.eloykim.lyra.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.eloykim.lyra.model.ScreenSnapshot
import com.eloykim.lyra.model.UiNode
import com.eloykim.lyra.policy.ActionKind
import com.eloykim.lyra.policy.PolicyDecision
import com.eloykim.lyra.policy.PolicyGuard
import com.eloykim.lyra.policy.RiskScope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 自动化引擎：观察 + 五个基础动作，外加一层不可绕过的策略闸门。
 *
 * 线程模型：
 * - 所有 AccessibilityService 调用都封送到主线程执行（binder 线程阻塞等待，最多 8s）；
 * - 每次动作前重新抓一次树，用「编号 + 指纹」校验调用方看到的是不是同一屏，避免点错；
 * - 手势（dispatchGesture）是异步的，不阻塞等待，交给随后的观察验证。
 *
 * 安全模型：
 * - 操作 Lyra 自身、系统设置、密码/验证码、支付流程 → 直接拒绝（Deny 不可解锁）；
 * - 发消息 / 改文件 / 改账号 → 必须由用户当场授权（ConsentManager），Agent 无法自我授权；
 * - 有待授权请求未处理时，所有动作一律阻塞，防止「先做别的、再回来点允许」。
 */
internal class AutomationEngine(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastSnapshot: ScreenSnapshot? = null

    /**
     * 用于校验的「上一次观察」。
     *
     * 服务可能重连（引擎重建），此时 lastSnapshot 为空，但 UI/协议侧仍持有 snapshot；
     * 退化到 AccessibilityState 里的公开快照，避免动作因为一次重连全部失效。
     */
    private fun previousSnapshot(): ScreenSnapshot? =
        lastSnapshot ?: AccessibilityState.snapshot.value

    fun observe(ignorePackage: String? = null): ScreenSnapshot? = runCatching {
        onMain {
            val live = SnapshotBuilder.captureWithNodes(service) ?: return@onMain null
            if (ignorePackage != null && live.snapshot.packageName == ignorePackage) return@onMain null
            lastSnapshot = live.snapshot
            AccessibilityState.publish(live.snapshot)
            live.snapshot
        }
    }.getOrNull()

    fun tap(nodeId: Int, obsId: Long): ActionOutcome = runCatching {
        onMain {
            when (val r = resolve(nodeId, obsId)) {
                is Resolution.Failed -> r.outcome
                is Resolution.Found -> {
                    val target = r.current.snapshot.nodes[nodeId]
                    gate(ActionKind.TAP, r.current, target.text ?: target.contentDescription)
                        ?.let { return@onMain it }

                    val clicked = r.node.isClickable &&
                        r.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        ActionOutcome.ok("clicked")
                    } else {
                        val rect = Rect()
                        r.node.getBoundsInScreen(rect)
                        if (gestureTap(rect.centerX(), rect.centerY())) {
                            ActionOutcome.ok("tap_dispatched")
                        } else {
                            ActionOutcome.error("action_failed", "点击未生效：元素不可点击或被遮挡")
                        }
                    }
                }
            }
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

/**
     * 按坐标点击：给无障碍树残缺的界面（游戏、Canvas、图片按钮）兜底。
     *
     * 坐标来自截图/OCR，属于设备像素。策略门禁与 node 点击一致；
     * Lyra 自身界面即便在调试模式也要求显式开关。
     */
    /**
     * 按 View Id 点击。支持完整 resource id（`pkg:id/name`）或只给末尾的 `name`。
     *
     * 不依赖 observe 的元素编号，适合已知控件 id 的稳定页面。
     */
    fun tapByViewId(viewId: String, obsId: Long): ActionOutcome = runCatching {
        onMain {
            if (ConsentManager.isPending) {
                return@onMain ActionOutcome.error(
                    "authorization_pending",
                    "有授权请求等待用户处理，所有动作暂停",
                )
            }
            val current = SnapshotBuilder.captureWithNodes(service)
                ?: return@onMain ActionOutcome.error("no_window", "当前没有可访问的窗口")
            if (obsId != 0L && current.snapshot.id != obsId && previousSnapshot()?.id != obsId) {
                return@onMain ActionOutcome.error(
                    "stale_observation",
                    "屏幕已变化（当前 obsId=${current.snapshot.id}）",
                )
            }
            if (current.snapshot.packageName == PolicyGuard.SELF_PACKAGE &&
                !PolicyGuard.isSelfOperationAllowed()
            ) {
                return@onMain ActionOutcome.blocked(RiskScope.SELF, "不允许在 Lyra 自身界面上操作")
            }
            val candidates = current.snapshot.nodes.withIndex().filter { (_, node) ->
                node.visible && matchesViewId(node.viewId, viewId)
            }
            if (candidates.isEmpty()) {
                return@onMain ActionOutcome.error(
                    "view_id_not_found",
                    "当前屏幕没有 View Id：$viewId",
                )
            }
            val selected = candidates.firstOrNull { it.value.clickable } ?: candidates.first()
            val target = current.nodes.getOrNull(selected.index)
                ?: return@onMain ActionOutcome.error("action_failed", "控件已失效，请重新尝试")
            val label = selected.value.text ?: selected.value.contentDescription
            gate(ActionKind.TAP, current, label)?.let { return@onMain it }

            val clicked = target.isClickable &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                ActionOutcome.ok("clicked_by_view_id")
            } else {
                val rect = Rect()
                target.getBoundsInScreen(rect)
                if (!rect.isEmpty && gestureTap(rect.centerX(), rect.centerY())) {
                    ActionOutcome.ok("tap_dispatched")
                } else {
                    ActionOutcome.error("action_failed", "View Id 点击未生效")
                }
            }
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    private fun matchesViewId(nodeViewId: String?, query: String): Boolean {
        if (nodeViewId.isNullOrBlank() || query.isBlank()) return false
        if (nodeViewId.equals(query, ignoreCase = true)) return true
        if (nodeViewId.endsWith(":id/$query", ignoreCase = true)) return true
        return nodeViewId.substringAfterLast('/').equals(query, ignoreCase = true)
    }

    fun tapAt(x: Int, y: Int): ActionOutcome = runCatching {
        onMain {
            val live = SnapshotBuilder.captureWithNodes(service)
                ?: return@onMain ActionOutcome.error("no_window", "当前没有可访问的窗口")
            if (live.snapshot.packageName == PolicyGuard.SELF_PACKAGE &&
                !PolicyGuard.isSelfOperationAllowed()
            ) {
                return@onMain ActionOutcome.blocked(RiskScope.SELF, "不允许在 Lyra 自身界面上按坐标点击")
            }
            gate(ActionKind.TAP, live, null)?.let { return@onMain it }
            if (gestureTap(x, y)) ActionOutcome.ok("tap_dispatched")
            else ActionOutcome.error("action_failed", "坐标点击未生效")
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    /**
     * 坐标长按：与坐标点击共用同一套策略闸门。
     */
    fun longPress(x: Int, y: Int, durationMs: Int): ActionOutcome = runCatching {
        onMain {
            val live = SnapshotBuilder.captureWithNodes(service)
                ?: return@onMain ActionOutcome.error("no_window", "当前没有可访问的窗口")
            if (live.snapshot.packageName == PolicyGuard.SELF_PACKAGE &&
                !PolicyGuard.isSelfOperationAllowed()
            ) {
                return@onMain ActionOutcome.blocked(RiskScope.SELF, "不允许在 Lyra 自身界面上长按")
            }
            gate(ActionKind.TAP, live, null)?.let { return@onMain it }
            if (gestureHold(x, y, durationMs)) {
                ActionOutcome.ok("long_press_dispatched")
            } else {
                ActionOutcome.error("action_failed", "长按未生效")
            }
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    /**
     * 坐标滑动：用于信息流、轮播图等节点级滚动失败的区域。
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ActionOutcome = runCatching {
        onMain {
            val live = SnapshotBuilder.captureWithNodes(service)
                ?: return@onMain ActionOutcome.error("no_window", "当前没有可访问的窗口")
            if (live.snapshot.packageName == PolicyGuard.SELF_PACKAGE &&
                !PolicyGuard.isSelfOperationAllowed()
            ) {
                return@onMain ActionOutcome.blocked(RiskScope.SELF, "不允许在 Lyra 自身界面上滑动")
            }
            gate(ActionKind.SCROLL, live, null)?.let { return@onMain it }
            if (gestureSwipe(x1, y1, x2, y2, durationMs)) {
                ActionOutcome.ok("swipe_dispatched")
            } else {
                ActionOutcome.error("action_failed", "滑动未生效")
            }
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    fun setText(nodeId: Int, obsId: Long, text: String): ActionOutcome = runCatching {
        onMain {
            when (val r = resolve(nodeId, obsId)) {
                is Resolution.Failed -> r.outcome
                is Resolution.Found -> {
                    if (!r.node.isEditable) {
                        return@onMain ActionOutcome.error("not_editable", "编号 $nodeId 不是输入框")
                    }
                    val target = r.current.snapshot.nodes[nodeId]
                    gate(ActionKind.TEXT, r.current, target.text ?: target.contentDescription)
                        ?.let { return@onMain it }

                    r.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    val ok = r.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (ok) ActionOutcome.ok("text_set")
                    else ActionOutcome.error("action_failed", "写入文本失败")
                }
            }
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    fun scroll(direction: Int, obsId: Long): ActionOutcome = runCatching {
        onMain {
            if (ConsentManager.isPending) {
                return@onMain ActionOutcome.error("authorization_pending", "有授权请求等待用户处理，所有动作暂停")
            }
            val current = SnapshotBuilder.captureWithNodes(service)
                ?: return@onMain ActionOutcome.error("no_window", "当前没有可访问的窗口")
            if (obsId != 0L && current.snapshot.id != obsId && previousSnapshot()?.id != obsId) {
                return@onMain ActionOutcome.error(
                    "stale_observation",
                    "屏幕已变化（当前 obsId=${current.snapshot.id}）",
                )
            }
            if (current.snapshot.packageName == PolicyGuard.SELF_PACKAGE && !PolicyGuard.isSelfOperationAllowed()) {
                return@onMain ActionOutcome.blocked(RiskScope.SELF, "不允许在 Lyra 自身界面里滚动")
            }
            val target = current.nodes.firstOrNull { it.isScrollable }
                ?: return@onMain ActionOutcome.error("no_scrollable", "当前屏幕没有可滚动区域")
            val action = when (direction) {
                0 -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                1 -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                2 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                3 -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> return@onMain ActionOutcome.error("invalid_direction", "direction 取值 0..3")
            }
            val ok = target.performAction(action)
            if (ok) ActionOutcome.ok("scrolled")
            else ActionOutcome.error("action_failed", "已经到头了，或者该区域不可滚动")
        }
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    fun globalAction(action: String): ActionOutcome = runCatching {
        val foreground = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (foreground == PolicyGuard.SELF_PACKAGE && !PolicyGuard.isSelfOperationAllowed()) {
            return@runCatching ActionOutcome.blocked(RiskScope.SELF, "不允许对 Lyra 自身执行全局动作")
        }
        val globalAction = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return@runCatching ActionOutcome.error("invalid_action", "只支持 back/home/recents/notifications")
        }
        val ok = onMain { service.performGlobalAction(globalAction) }
        if (ok) ActionOutcome.ok(action) else ActionOutcome.error("action_failed", "全局动作执行失败")
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "执行失败") }

    fun launch(packageName: String): ActionOutcome = runCatching {
        if (ConsentManager.isPending) {
            return@runCatching ActionOutcome.error("authorization_pending", "有授权请求等待用户处理，所有动作暂停")
        }
        val decision = PolicyGuard.evaluate(ActionKind.LAUNCH, packageName)
        if (decision is PolicyDecision.Deny) {
            return@runCatching ActionOutcome.blocked(decision.scope, decision.reason)
        }
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@runCatching ActionOutcome.error("app_not_found", "设备上没有安装 $packageName")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        ActionOutcome.ok("launched")
    }.getOrElse { ActionOutcome.error("internal_error", it.message ?: "启动失败") }

    fun back(): ActionOutcome = globalAction("back")

    fun recents(): ActionOutcome = globalAction("recents")

    /**
     * 截屏（API 30+）。拒绝截取 Lyra 自身界面 —— 设置页和授权弹窗不能被 Agent 看到。
     */
    fun screenshot(): ShotOutcome = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return@runCatching ShotOutcome(false, "unsupported", "需要 Android 11 及以上")
        }
        val pkg = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (pkg == PolicyGuard.SELF_PACKAGE && !PolicyGuard.isSelfOperationAllowed()) {
            return@runCatching ShotOutcome(false, "blocked", "不允许截取 Lyra 自身界面")
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var result: android.accessibilityservice.AccessibilityService.ScreenshotResult? = null
        var errorCode = -1

        service.takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            androidx.core.content.ContextCompat.getMainExecutor(service),
            object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                    result = screenshot
                    latch.countDown()
                }

                override fun onFailure(error: Int) {
                    errorCode = error
                    latch.countDown()
                }
            },
        )

        if (!latch.await(3, TimeUnit.SECONDS)) {
            return@runCatching ShotOutcome(false, "timeout", "截屏超时")
        }
        val shot = result ?: return@runCatching ShotOutcome(false, "failed", "截屏失败（$errorCode）")

        val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
            ?: return@runCatching ShotOutcome(false, "failed", "无法解码截图")
        val dir = java.io.File(service.cacheDir, "shots").apply { mkdirs() }
        val file = java.io.File(dir, "shot-${System.currentTimeMillis()}.png")
        java.io.FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        shot.hardwareBuffer.close()

        ShotOutcome(true, "ok", "ok", file.absolutePath, width, height)
    }.getOrElse { ShotOutcome(false, "internal_error", it.message ?: "截屏失败") }

    // ---------------------------------------------------------------- 策略闸门

    /** 返回 null 表示放行；非 null 表示拒绝或需要授权。 */
    private fun gate(kind: ActionKind, screen: LiveScreen, label: String?): ActionOutcome? {
        if (ConsentManager.isPending) {
            return ActionOutcome.error("authorization_pending", "有授权请求等待用户处理，所有动作暂停")
        }

        val screenText = screen.snapshot.nodes.asSequence()
            .mapNotNull { it.text ?: it.contentDescription }
            .filter { it.isNotBlank() }
            .take(120)
            .toList()

        return when (val decision = PolicyGuard.evaluate(kind, screen.snapshot.packageName, label, screenText)) {
            is PolicyDecision.Allow -> null

            is PolicyDecision.Deny -> ActionOutcome.blocked(decision.scope, decision.reason)

            is PolicyDecision.RequireAuth -> {
                val fingerprint = fingerprint(kind, screen.snapshot.packageName, label)
                if (ConsentManager.consume(fingerprint)) {
                    null
                } else {
                    val challenge = ConsentManager.request(
                        scope = decision.scope,
                        summary = decision.summary,
                        fingerprint = fingerprint,
                    )
                    ActionOutcome.authorizationRequired(challenge.id, decision.scope, decision.summary)
                }
            }
        }
    }

    private fun fingerprint(kind: ActionKind, packageName: String, label: String?): String =
        "$kind|$packageName|${label.orEmpty()}"

    // ---------------------------------------------------------------- 内部

    private sealed class Resolution {
        class Found(
            val current: LiveScreen,
            val node: AccessibilityNodeInfo,
        ) : Resolution()

        class Failed(val outcome: ActionOutcome) : Resolution()
    }

    private fun resolve(nodeId: Int, obsId: Long): Resolution {
        val previous = previousSnapshot()
            ?: return Resolution.Failed(ActionOutcome.error("no_observation", "还没有观察，先调用 observe()"))
        if (obsId != 0L && previous.id != obsId) {
            return Resolution.Failed(
                ActionOutcome.error(
                    "stale_observation",
                    "屏幕已变化（当前 obsId=${previous.id}），请基于新观察重试",
                ),
            )
        }

        val current = SnapshotBuilder.captureWithNodes(service)
            ?: return Resolution.Failed(ActionOutcome.error("no_window", "当前没有可访问的窗口"))

        val node = current.nodes.getOrNull(nodeId)
            ?: return Resolution.Failed(
                ActionOutcome.error("invalid_id", "编号 $nodeId 不存在（当前 ${current.nodes.size} 个节点）"),
            )

        val expected = previous.nodes.getOrNull(nodeId)
        val actual = current.snapshot.nodes.getOrNull(nodeId)
        if (expected == null || actual == null || !sameElement(expected, actual)) {
            return Resolution.Failed(
                ActionOutcome.error("stale_observation", "编号 $nodeId 对应的元素已变化，请重新 observe()"),
            )
        }
        return Resolution.Found(current, node)
    }

    private fun sameElement(a: UiNode, b: UiNode): Boolean =
        a.viewId == b.viewId &&
            a.className == b.className &&
            a.text == b.text &&
            a.bounds == b.bounds &&
            a.clickable == b.clickable

    private fun gestureTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun gestureHold(x: Int, y: Int, durationMs: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(100, 5_000).toLong(),
                ),
            )
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun gestureSwipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(100, 3_000).toLong(),
                ),
            )
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        mainHandler.post {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(8, TimeUnit.SECONDS)
    }
}

internal data class ShotOutcome(
    val ok: Boolean,
    val code: String,
    val message: String,
    val path: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

internal data class ActionOutcome(
    val ok: Boolean,
    val code: String,
    val message: String,
    val challengeId: String? = null,
    val scope: String? = null,
) {
    companion object {
        fun ok(message: String) = ActionOutcome(true, "ok", message)

        fun error(code: String, message: String) = ActionOutcome(false, code, message)

        fun blocked(scope: RiskScope, message: String) =
            ActionOutcome(false, "blocked", message, scope = scope.name.lowercase())

        fun authorizationRequired(challengeId: String, scope: RiskScope, message: String) =
            ActionOutcome(
                ok = false,
                code = "authorization_required",
                message = message,
                challengeId = challengeId,
                scope = scope.name.lowercase(),
            )
    }

}