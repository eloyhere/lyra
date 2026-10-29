package com.eloykim.lyra.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.eloykim.lyra.automation.AutomationService
import com.eloykim.lyra.automation.IAutomation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 自动化服务的客户端。
 *
 * 阶段目标：先把「能力和 App」之间的边界立起来。
 * 现在同进程绑定；以后换成独立进程 / 另一个 App，调用方代码不用改。
 */
object AutomationClient {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var remote: IAutomation? = null

    private var appContext: Context? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IAutomation.Stub.asInterface(service)
            _connected.value = true
            Log.d("LyraAutomation", "connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            _connected.value = false
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Log.d("LyraAutomation", "init: binding")
        bind()
    }

    fun release() {
        val context = appContext ?: return
        if (bound) {
            runCatching { context.unbindService(connection) }
        }
        bound = false
        remote = null
        _connected.value = false
    }

    private fun bind() {
        val context = appContext ?: return
        if (bound) return
        bound = true
        val ok = runCatching {
            context.bindService(
                Intent(context, AutomationService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        Log.d("LyraAutomation", "bindService returned $ok")
        if (!ok) bound = false
    }

    private fun remoteOrThrow(): IAutomation =
        remote ?: throw IllegalStateException("自动化服务尚未连接")

    suspend fun observe(): String = io { remoteOrThrow().observe() }

    suspend fun tap(nodeId: Int, obsId: Long): String = io { remoteOrThrow().tap(nodeId, obsId) }

    /** 按 View Id 点击；viewId 可以是完整 `pkg:id/name` 或只有 `name`。 */
    suspend fun tapByViewId(viewId: String, obsId: Long): String =
        io { remoteOrThrow().tapByViewId(viewId, obsId) }

    suspend fun tapAt(x: Int, y: Int): String = io { remoteOrThrow().tapAt(x, y) }

    /** 坐标长按。 */
    suspend fun longPress(x: Int, y: Int, durationMs: Int): String =
        io { remoteOrThrow().longPress(x, y, durationMs) }

    /** 坐标滑动。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String =
        io { remoteOrThrow().swipe(x1, y1, x2, y2, durationMs) }

    suspend fun setText(nodeId: Int, obsId: Long, text: String): String =
        io { remoteOrThrow().setText(nodeId, obsId, text) }

    suspend fun scroll(direction: Int, obsId: Long): String =
        io { remoteOrThrow().scroll(direction, obsId) }

    suspend fun globalAction(action: String): String =
        io { remoteOrThrow().globalAction(action) }

    suspend fun launch(packageName: String): String =
        io { remoteOrThrow().launch(packageName) }

    /** 截屏；返回 {ok, path, width, height}。Lyra 自身界面会被策略拒绝。 */
    suspend fun screenshot(): String = io { remoteOrThrow().screenshot() }

    /** 最近任务。 */
    suspend fun recents(): String = io { remoteOrThrow().recents() }

    /** 列出最近的媒体文件（需要 READ_EXTERNAL_STORAGE）。 */
    suspend fun listFiles(limit: Int = 20): String = io { remoteOrThrow().listFiles(limit) }

    /** 清屏用的键，方便调试时快速回桌面。 */
    suspend fun back(): String = globalAction("back")

    private suspend fun io(block: () -> String): String = withContext(Dispatchers.IO) { block() }
}
