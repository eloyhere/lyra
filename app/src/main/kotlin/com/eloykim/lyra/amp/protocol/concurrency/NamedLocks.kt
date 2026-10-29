package com.eloykim.lyra.amp.protocol.concurrency

import java.util.concurrent.ConcurrentHashMap

/**
 * `src/concurrency/namedLocks.ts` 的忠实移植：单个 worker 内的命名互斥锁。
 *
 * 一个进程可能需要串行化对某个资源的访问（文件、浏览器标签页、MCP 会话），又不阻塞无关
 * 工作。注册表为每个名字保留一个 [Mutex]；名字不透明，锁本身不携带顺序。
 *
 * 仅限 worker 内。跨 worker 的互斥是数据库租约（`claimThread`），不是信号量。
 */
class NamedLocks {

    private val locks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

    fun forName(name: String): Mutex = locks.computeIfAbsent(name) { Mutex() }

    fun <T> run(name: String, fn: () -> T): T = forName(name).run(fn)

    val size: Int get() = locks.size
}
