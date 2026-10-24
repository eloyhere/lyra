package com.eloykim.lyra.attention

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * 本地模型（embedding / 轻量推理）的调度器。
 *
 * 为什么是「独立单线程」而不是独立线程池：
 * 1. ONNX Runtime 的 session 在并发 run 时会争抢 intra-op 线程，反而更慢；
 * 2. 模型常驻内存，多线程只会放大峰值内存；
 * 3. 单线程天然串行化，配合「只保留最新一帧」的合并策略，旧请求可以直接丢弃。
 *
 * 线程优先级降到后台，避免和 UI 抢 CPU。
 */
object LocalModelPool {

    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lyra-embed").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }.asCoroutineDispatcher()
    }
}
