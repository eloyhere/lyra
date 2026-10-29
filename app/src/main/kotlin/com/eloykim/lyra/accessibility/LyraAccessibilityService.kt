package com.eloykim.lyra.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.eloykim.lyra.policy.PolicyGuard

/**
 * Lyra 的无障碍服务：把「屏幕事件」变成「快照」，并持有自动化引擎。
 */
class LyraAccessibilityService : AccessibilityService() {

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val captureRunnable = Runnable { captureNow() }

    internal val engine: AutomationEngine by lazy { AutomationEngine(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityState.setConnected(true)
        scheduleCapture(60)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 系统权限/设置窗口：不允许无障碍读取或操作。
        if (PolicyGuard.isSystemGrantWindowActive()) return
        if (event.packageName?.toString()?.let(PolicyGuard::isSystemGrantPackage) == true) return
        // 忽略自己界面产生的事件，避免自激
        if (event.packageName?.toString() == packageName) return
        // 事件风暴：180ms 内的连续事件合并成一次快照
        scheduleCapture(180)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(captureRunnable)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityState.setConnected(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        AccessibilityState.setConnected(false)
        super.onDestroy()
    }

    private fun scheduleCapture(delayMs: Long) {
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, delayMs)
    }

    private fun captureNow(force: Boolean = false) {
        if (PolicyGuard.isSystemGrantWindowActive()) return
        // 不把自己界面的快照当成「目标应用」，否则会覆盖掉上一个 App 的观察
        engine.observe(ignorePackage = if (force) null else packageName)
    }

    companion object {
        @Volatile
        internal var instance: LyraAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** UI 主动要求重新抓取（检查器里的「刷新」）。 */
        fun requestCapture() {
            val service = instance ?: return
            service.handler.post { service.captureNow(force = true) }
        }
    }
}
