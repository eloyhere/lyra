package com.eloykim.lyra

import android.content.Context
import com.eloykim.lyra.data.HistoryRepository
import com.eloykim.lyra.attention.FileHandleStore
import com.eloykim.lyra.attention.HandleStore
import com.eloykim.lyra.service.CommandRegistry
import com.eloykim.lyra.service.PhoneServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * 极简依赖容器。
 *
 * networkPool：应用级固定线程池（4 线程），所有「拉取模型列表 / 探测服务」共用，
 * 避免页面重建时反复创建线程，也避免并发打爆服务商。
 */
object AppGraph {

    @Volatile
    private var repository: HistoryRepository? = null

    val history: HistoryRepository
        get() = repository ?: error("AppGraph 未初始化")

    val networkPool: CoroutineDispatcher by lazy {
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "lyra-net").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /** 大结果句柄：内容寻址落在 files/handles，重启可读。 */
    val handles: HandleStore by lazy {
        FileHandleStore(java.io.File(_filesDir ?: error("AppGraph 未初始化"), "handles"))
    }

    /** 手机服务命令目录：终端与 Agent 共用同一个 CommandRegistry。 */
    val commands: CommandRegistry by lazy {
        PhoneServices.createCommands(
            _appContext ?: error("AppGraph 未初始化"),
            repository,
        )
    }

    private var _filesDir: java.io.File? = null
    private var _appContext: Context? = null

    /**
     * 应用级协程作用域：任务不能因为用户离开页面就被取消。
     * 页面级 rememberCoroutineScope 会随 composition 销毁，导致 run 永远停在 running。
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    fun init(context: Context) {
        _filesDir = context.filesDir
        _appContext = context.applicationContext
        if (repository == null) {
            repository = HistoryRepository(context.applicationContext).also { repository ->
                // 启动清理放到 IO，不占主线程
                appScope.launch(Dispatchers.IO) {
                    repository.markInterrupted()
                    // 启动时压缩前缀：超过阈值才做，避免每次启动都读写全表
                    runCatching { com.eloykim.lyra.group.GroupOrchestrator.migrateExisting() }
                    runCatching {
                        val forced = DebugLaunch.compactKeep
                        if (forced > 0) {
                            repository.ledger.compact(forced)
                        } else if (repository.ledger.size() > 1_000) {
                            repository.ledger.compact(500)
                        }
                    }
                }
            }
        }
    }
}
