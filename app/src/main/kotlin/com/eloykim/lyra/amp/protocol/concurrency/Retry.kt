package com.eloykim.lyra.amp.protocol.concurrency

/**
 * `src/concurrency/retry.ts` 的忠实移植：内核遇到的两种争用的重试辅助。
 *
 * - `worker_threads` 之间 SQLite 写争用：`SQLITE_BUSY`。连接设置 `busy_timeout`，异步调用
 *   方还可以用 [withBusyRetry] 退避。
 * - 应用层争用：租约或行版本变了。调用方应重新读取并决策，而不是盲目重试。
 *
 * Kotlin 侧没有 `await`/`setTimeout`；`sleep` 是阻塞回调，默认用 [Thread.sleep]。调用点若
 * 在 UI 线程，应传入非阻塞实现（例如投递到调度器后再调用本函数）。
 */
data class RetryOptions(
    val attempts: Int = 5,
    val baseMs: Long = 25,
    val maxMs: Long = 1000,
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
    val isRetryable: (Throwable) -> Boolean = ::isBusyError,
)

fun isBusyError(error: Throwable): Boolean {
    val message: String = error.message ?: error.toString()
    return message.contains("database is locked") || message.contains("SQLITE_BUSY")
}

/** 指数退避重试，只在 `isRetryable` 命中时继续。 */
fun <T> withBusyRetry(fn: () -> T, options: RetryOptions = RetryOptions()): T {
    val attempts: Int = options.attempts
    val baseMs: Long = options.baseMs
    val maxMs: Long = options.maxMs
    val sleep: (Long) -> Unit = options.sleep
    val retryable: (Throwable) -> Boolean = options.isRetryable
    var lastError: Throwable? = null
    for (attempt in 0 until attempts) {
        try {
            return fn()
        } catch (error: Throwable) {
            lastError = error
            if (!retryable(error) || attempt == attempts - 1) {
                throw error
            }
            val delay: Long = Math.min(
                maxMs.toDouble(),
                baseMs.toDouble() * Math.pow(2.0, attempt.toDouble()),
            ).toLong()
            sleep(delay)
        }
    }
    throw lastError ?: IllegalStateException("retry attempts must be positive")
}
