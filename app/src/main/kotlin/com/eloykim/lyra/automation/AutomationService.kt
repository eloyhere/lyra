package com.eloykim.lyra.automation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.eloykim.lyra.accessibility.ActionOutcome
import com.eloykim.lyra.accessibility.AmpRecorder
import com.eloykim.lyra.accessibility.AutomationEngine
import com.eloykim.lyra.accessibility.LyraAccessibilityService
import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.receipt.ReceiptOp
import com.eloykim.lyra.model.ScreenSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对外暴露的自动化服务。
 *
 * 它不自己抓屏，而是驱动 LyraAccessibilityService 持有的引擎 ——
 * 保证「UI 看到的」和「协议层操作的」是同一个快照源。
 *
 * 每个动作执行后等待 260ms 让界面稳定，再返回一份新观察，
 * 调用方不需要额外的 observe 往返。
 *
 * 同时把每次观察/动作写进 AMP 账本（Frame / Receipt），供审计与回放。
 */
class AutomationService : Service() {

    private val engine: AutomationEngine?
        get() = LyraAccessibilityService.instance?.engine

    private val binder = object : IAutomation.Stub() {

        override fun isReady(): Boolean = engine != null

        override fun observe(): String {
            if (com.eloykim.lyra.policy.PolicyGuard.isSystemGrantWindowActive()) {
                return errorJson("grant_window", "系统授权窗口期间禁止无障碍读取")
            }
            val current = engine ?: return errorJson("service_unavailable", "无障碍服务未连接")
            val snapshot = current.observe()
                ?: return errorJson("no_window", "当前没有可访问的窗口")
            if (com.eloykim.lyra.policy.PolicyGuard.isSystemGrantPackage(snapshot.packageName)) {
                return errorJson("grant_window", "系统授权/设置窗口不对 Agent 开放")
            }
            // Lyra 自身界面永不外发：Agent 不能读设置页、不能读授权弹窗
            if (snapshot.packageName == com.eloykim.lyra.policy.PolicyGuard.SELF_PACKAGE && !com.eloykim.lyra.policy.PolicyGuard.isSelfOperationAllowed()) {
                return errorJson("self_screen", "Lyra 自身界面不对 Agent 开放")
            }
            AmpRecorder.onObserved(snapshot)
            return JSONObject()
                .put("ok", true)
                .put("obsId", snapshot.id)
                .put("observation", observationJson(snapshot))
                .toString()
        }

        override fun tap(nodeId: Int, obsId: Long): String =
            act(ReceiptOp.ACT, "tap#$nodeId") { it.tap(nodeId, obsId) }

        override fun tapByViewId(viewId: String, obsId: Long): String =
            act(ReceiptOp.ACT, "tapByViewId#$viewId") { it.tapByViewId(viewId, obsId) }

        override fun tapAt(x: Int, y: Int): String =
            act(ReceiptOp.ACT, "tapAt#$x,$y") { it.tapAt(x, y) }

        override fun longPress(x: Int, y: Int, durationMs: Int): String =
            act(ReceiptOp.ACT, "longPress#$x,$y") { it.longPress(x, y, durationMs) }

        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
            act(ReceiptOp.ACT, "swipe#$x1,$y1-$x2,$y2") {
                it.swipe(x1, y1, x2, y2, durationMs)
            }

        override fun setText(nodeId: Int, obsId: Long, text: String): String =
            act(ReceiptOp.ACT, "text#$nodeId") { it.setText(nodeId, obsId, text) }

        override fun scroll(direction: Int, obsId: Long): String =
            act(ReceiptOp.ACT, "scroll#$direction") { it.scroll(direction, obsId) }

        override fun globalAction(action: String): String =
            act(ReceiptOp.ACT, "global#$action") { it.globalAction(action) }

        override fun back(): String = act(ReceiptOp.ACT, "back") { it.back() }

        override fun recents(): String = act(ReceiptOp.ACT, "recents") { it.recents() }

        override fun screenshot(): String {
            val current = engine ?: return errorJson("service_unavailable", "无障碍服务未连接")
            val shot = current.screenshot()
            AmpRecorder.onAction(
                op = ReceiptOp.ACT,
                label = "screenshot",
                startedAtMs = System.currentTimeMillis(),
                outcome = if (shot.ok) Outcome.SUCCESS else Outcome.BLOCKED,
                note = shot.code,
            )
            return JSONObject()
                .put("ok", shot.ok)
                .put("code", shot.code)
                .apply {
                    if (shot.ok) {
                        put("path", shot.path)
                        put("width", shot.width)
                        put("height", shot.height)
                    } else {
                        put("message", shot.message)
                    }
                }
                .toString()
        }

        override fun listFiles(limit: Int): String = listRecentFiles(limit.coerceIn(1, 100))

        override fun launch(packageName: String): String =
            act(ReceiptOp.ACT, "launch#$packageName") { it.launch(packageName) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private inline fun act(
        op: ReceiptOp,
        label: String,
        block: (AutomationEngine) -> ActionOutcome,
    ): String {
        if (com.eloykim.lyra.policy.PolicyGuard.isSystemGrantWindowActive()) {
            return errorJson("grant_window", "系统授权窗口期间禁止无障碍操作")
        }
        val current = engine ?: return errorJson("service_unavailable", "无障碍服务未连接")
        val started = System.currentTimeMillis()
        val outcome = block(current)

        if (!outcome.ok) {
            AmpRecorder.onAction(
                op = op,
                label = label,
                startedAtMs = started,
                outcome = if (outcome.code.startsWith("blocked") ||
                    outcome.code.startsWith("authorization")
                ) Outcome.BLOCKED else Outcome.FAILURE,
                note = outcome.code,
            )
            val snapshot = runCatching { current.observe() }.getOrNull()
            return JSONObject()
                .put("ok", false)
                .put("code", outcome.code)
                .put("message", outcome.message)
                .apply {
                    outcome.challengeId?.let { put("challenge", it) }
                    outcome.scope?.let { put("scope", it) }
                    snapshot?.let { put("obsId", it.id); put("observation", observationJson(it)) }
                }
                .toString()
        }

        Thread.sleep(260)

        // 冷启动一个应用时，260ms 往往只抓到启动画面（有节点但没有可读元素）。
        // 等到目标应用真正渲染出内容，再返回观察；最多等 4.5s。
        var snapshot = runCatching { current.observe() }.getOrNull()
        if (label.startsWith("launch#")) {
            var attempts = 0
            while (attempts < 15 && !hasContent(snapshot)) {
                Thread.sleep(300)
                snapshot = runCatching { current.observe() }.getOrNull()
                attempts++
            }
        }
        snapshot?.let { AmpRecorder.onObserved(it) }
        AmpRecorder.onAction(
            op = op,
            label = label,
            startedAtMs = started,
            outcome = Outcome.SUCCESS,
            note = outcome.message,
        )
        return JSONObject()
            .put("ok", true)
            .put("message", outcome.message)
            .apply { snapshot?.let { put("obsId", it.id); put("observation", observationJson(it)) } }
            .toString()
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    /**
     * 只列出「媒体文件」的名称/大小/类型/时间，不含内容。
     * Android 13+ 改走 READ_MEDIA_*，这里先明确返回 permission_required，不静默降级。
     */
    private fun listRecentFiles(limit: Int): String {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return errorJson("permission_required", "Android 13+ 需要单独的媒体权限，暂未接入")
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return errorJson("permission_required", "需要「读取文件」权限，请在设置页授予")
        }

        val projection = arrayOf(
            android.provider.MediaStore.Files.FileColumns._ID,
            android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
            android.provider.MediaStore.Files.FileColumns.SIZE,
            android.provider.MediaStore.Files.FileColumns.MIME_TYPE,
            android.provider.MediaStore.Files.FileColumns.DATE_ADDED,
        )
        val files = JSONArray()
        runCatching {
            contentResolver.query(
                android.provider.MediaStore.Files.getContentUri("external"),
                projection,
                null,
                null,
                "${android.provider.MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(projection[0])
                val nameIndex = cursor.getColumnIndexOrThrow(projection[1])
                val sizeIndex = cursor.getColumnIndexOrThrow(projection[2])
                val mimeIndex = cursor.getColumnIndexOrThrow(projection[3])
                val dateIndex = cursor.getColumnIndexOrThrow(projection[4])
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    files.put(
                        JSONObject()
                            .put("id", cursor.getLong(idIndex))
                            .put("name", cursor.getString(nameIndex))
                            .put("size", cursor.getLong(sizeIndex))
                            .put("mime", cursor.getString(mimeIndex))
                            .put("addedAt", cursor.getLong(dateIndex) * 1000L),
                    )
                    count++
                }
            }
        }.onFailure { return errorJson("query_failed", it.message ?: "查询失败") }

        return JSONObject().put("ok", true).put("files", files).toString()
    }

    /** 屏幕上是否有足够的可读元素（启动画面通常只有布局节点）。 */
    private fun hasContent(snapshot: ScreenSnapshot?): Boolean {
        if (snapshot == null) return false
        var count = 0
        for (node in snapshot.nodes) {
            if (!node.visible) continue
            if (node.hasLabel || node.clickable || node.editable || node.scrollable) {
                count++
                if (count >= 3) return true
            }
        }
        return false
    }

    companion object {

        /**
         * 压缩后的观察：只保留「有文字 / 可点击 / 可输入 / 可滚动」的可见节点。
         * 这是给模型看的最小信息集，不是完整控件树（完整树用调试页看）。
         */
        fun observationJson(snapshot: ScreenSnapshot): JSONObject {
            val elements = JSONArray()
            var sent = 0
            for (node in snapshot.nodes) {
                if (!node.visible) continue
                val interesting = node.hasLabel || node.clickable || node.editable || node.scrollable
                if (!interesting) continue
                elements.put(
                    JSONObject().apply {
                        put("id", node.id)
                        put("role", node.shortClassName.lowercase())
                        node.text?.take(80)?.let { put("text", it) }
                        node.contentDescription?.take(80)?.let { put("desc", it) }
                        put(
                            "bounds",
                            JSONArray()
                                .put(node.bounds.left)
                                .put(node.bounds.top)
                                .put(node.bounds.right)
                                .put(node.bounds.bottom),
                        )
                        if (node.clickable) put("clickable", true)
                        if (node.editable) put("editable", true)
                        if (node.scrollable) put("scrollable", true)
                    },
                )
                sent++
            }
            return JSONObject().apply {
                put("obsId", snapshot.id)
                put("package", snapshot.packageName)
                put("capturedAt", snapshot.capturedAt)
                put("truncated", snapshot.truncated)
                put(
                    "counts",
                    JSONObject()
                        .put("nodes", snapshot.nodes.size)
                        .put("interactive", snapshot.interactiveCount)
                        .put("sent", sent),
                )
                put("elements", elements)
            }
        }
    }
}
