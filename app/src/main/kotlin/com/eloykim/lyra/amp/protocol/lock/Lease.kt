package com.eloykim.lyra.amp.protocol.lock

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `src/lock/lease.ts` 的忠实移植：跨 worker 互斥的命名租约。
 *
 * capability 回答“这个主体能否碰这个资源”；lease 回答“现在谁在碰它”。fencing 计数器让
 * 陈旧持有者的写入在租约被重新分配之后仍然可以被拒绝。
 *
 * ## 绑定
 *
 * 参考实现直接执行 `resource_locks` 表的 4 条 SQL。这里收敛成 [LockStore] 窄端口：
 * [get]/[acquire]/[renew]/[release] 分别对应 getLock/acquireLock/renewLock/releaseLock，
 * 条件的原子更新留在 [InMemoryLockStore] 的 `acquire` 里。SQLite 适配器应把参考 SQL
 * （含 `ON CONFLICT ... WHERE expires_at <= ? OR holder = ?`）放进同一个 `acquire` 方法。
 */

data class LockRecord(
    val name: String,
    val holder: String,
    val fencing: Int,
    val acquiredAt: String,
    val expiresAt: String,
)

interface LockStore {

    fun get(name: String): LockRecord?

    /**
     * 在空闲、已过期或已由同一 holder 持有时获取租约。另一个 holder 持有活租约时返回 null。
     * 接管时 fencing 自增；首次插入为 1。
     */
    fun acquire(name: String, holder: String, at: String, expiresAt: String): LockRecord?

    fun renew(name: String, holder: String, fencing: Int, expiresAt: String): Boolean

    fun release(name: String, holder: String, fencing: Int): Boolean
}

private val JS_ISO: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

/** `new Date(Date.parse(at) + ttlMs).toISOString()`；与参考一样总是三位毫秒。 */
fun lockExpiry(at: String, ttlMs: Long): String =
    JS_ISO.format(Instant.parse(at).plusMillis(ttlMs))

fun getLock(store: LockStore, name: String): LockRecord? = store.get(name)

fun acquireLock(
    store: LockStore,
    name: String,
    holder: String,
    at: String,
    ttlMs: Long = 15_000L,
): LockRecord? = store.acquire(name, holder, at, lockExpiry(at, ttlMs))

fun renewLock(
    store: LockStore,
    name: String,
    holder: String,
    fencing: Int,
    at: String,
    ttlMs: Long = 15_000L,
): Boolean = store.renew(name, holder, fencing, lockExpiry(at, ttlMs))

fun releaseLock(store: LockStore, name: String, holder: String, fencing: Int): Boolean =
    store.release(name, holder, fencing)

/** Android 参考实现：单表内存版本，`acquire` 的判定与参考 SQL 的 WHERE 一致。 */
class InMemoryLockStore : LockStore {

    private val locks = LinkedHashMap<String, LockRecord>()

    @Synchronized
    override fun get(name: String): LockRecord? = locks[name]

    @Synchronized
    override fun acquire(name: String, holder: String, at: String, expiresAt: String): LockRecord? {
        val existing: LockRecord? = locks[name]
        if (existing == null) {
            val record = LockRecord(
                name = name,
                holder = holder,
                fencing = 1,
                acquiredAt = at,
                expiresAt = expiresAt,
            )
            locks[name] = record
            return record
        }
        if (existing.expiresAt <= at || existing.holder == holder) {
            val record = existing.copy(
                holder = holder,
                fencing = existing.fencing + 1,
                acquiredAt = at,
                expiresAt = expiresAt,
            )
            locks[name] = record
            return record
        }
        return null
    }

    @Synchronized
    override fun renew(name: String, holder: String, fencing: Int, expiresAt: String): Boolean {
        val existing: LockRecord = locks[name] ?: return false
        if (existing.holder != holder || existing.fencing != fencing) {
            return false
        }
        locks[name] = existing.copy(expiresAt = expiresAt)
        return true
    }

    @Synchronized
    override fun release(name: String, holder: String, fencing: Int): Boolean {
        val existing: LockRecord = locks[name] ?: return false
        if (existing.holder != holder || existing.fencing != fencing) {
            return false
        }
        locks.remove(name)
        return true
    }
}
