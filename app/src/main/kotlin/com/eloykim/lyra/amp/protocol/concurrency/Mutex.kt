package com.eloykim.lyra.amp.protocol.concurrency

import java.util.concurrent.Semaphore

/**
 * `src/concurrency/mutex.ts` 的忠实移植：进程内临界区的互斥锁。
 *
 * 内核自己的写入是同步的，并由 SQLite 串行化；这个锁是给会在 `await` 点交错的处理器用的：
 * 一次工具调用、一次 LLM 请求、一次文件写入。它不是跨 worker 锁——跨 worker 用数据库
 * 租约（`claimThread` + fencing token），因为 promise 无法在 `worker_threads` 之间共享。
 *
 * ## 绑定
 *
 * Kotlin 侧没有 promise 队列，这里用公平（FIFO）二值信号量：`run` 阻塞调用线程直到前一个
 * 临界区释放，语义与参考的 `tail` 链一致——先进先出、**不可重入**（同一线程内嵌套 `run`
 * 会自锁，和参考实现的嵌套 `await` 一样）。
 */
class Mutex {

    private val permits = Semaphore(1, true)

    fun <T> run(fn: () -> T): T {
        permits.acquire()
        try {
            return fn()
        } finally {
            permits.release()
        }
    }
}
