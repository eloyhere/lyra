package com.eloykim.lyra.amp.protocol.concurrency

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import java.util.concurrent.ConcurrentHashMap

/**
 * `src/concurrency/idempotency.ts` 的忠实移植：外部效果幂等。
 *
 * 消息发送或工具调用携带 key；第一次成功尝试保存结果，崩溃后的重试返回该结果而不是再次
 * 执行效果。唯一主键是仲裁者，因此两个 worker 竞争同一 key 时不会都执行。
 *
 * ## 绑定
 *
 * 参考实现用 SQLite 的 `SELECT` + `INSERT`（唯一键冲突即仲裁）。这里定义 [IdempotencyStore]
 * 窄端口，[InMemoryIdempotencyStore] 是 Android 上的参考实现；SQLite 适配器只需实现同样的
 * 两个方法。[once] 保留参考的“先查、执行、抢占、回读”顺序。
 *
 * Kotlin 没有对任意 `T` 的 `JSON.stringify`，所以 [once] 要求显式的编解码器；[onceJson]
 * 是 `JsonValue` 的便利入口。
 */
data class OnceResult<T>(
    val value: T,
    val replayed: Boolean,
)

interface IdempotencyStore {

    /** 已存储的结果文本；不存在时返回 null。 */
    fun read(key: String): String?

    /**
     * 原子地存入结果。返回 true 表示本次调用写入成功；false 表示 key 已存在（另一个
     * worker 赢了竞态）。
     */
    fun putIfAbsent(key: String, result: String, at: String): Boolean
}

class InMemoryIdempotencyStore : IdempotencyStore {

    private val rows = ConcurrentHashMap<String, String>()

    override fun read(key: String): String? = rows[key]

    override fun putIfAbsent(key: String, result: String, at: String): Boolean =
        rows.putIfAbsent(key, result) == null
}

fun <T> once(
    store: IdempotencyStore,
    key: String,
    at: String,
    encode: (T) -> String,
    decode: (String) -> T,
    fn: () -> T,
): OnceResult<T> {
    store.read(key)?.let { stored ->
        return OnceResult(decode(stored), replayed = true)
    }
    val value: T = fn()
    val stored: Boolean = try {
        store.putIfAbsent(key, encode(value), at)
    } catch (_: Exception) {
        false
    }
    if (stored) {
        return OnceResult(value, replayed = false)
    }
    // 另一个 worker 在 SELECT 与 INSERT 之间赢了竞态。
    val row: String = store.read(key)
        ?: throw IllegalStateException("idempotency key $key was not stored")
    return OnceResult(decode(row), replayed = true)
}

/**
 * `JsonValue` 便利入口；持久化用规范 JSON（键有序），回放语义与参考的
 * `JSON.stringify`/`JSON.parse` 一致。
 */
fun onceJson(
    store: IdempotencyStore,
    key: String,
    at: String,
    fn: () -> JsonValue,
): OnceResult<JsonValue> = once(
    store = store,
    key = key,
    at = at,
    encode = { CanonicalJson.encode(it) },
    decode = { JsonParser.parse(it) },
    fn = fn,
)
