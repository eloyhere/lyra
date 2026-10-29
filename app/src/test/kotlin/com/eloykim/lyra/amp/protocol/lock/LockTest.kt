package com.eloykim.lyra.amp.protocol.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTest {

    private val name = "db.migrate"
    private val at = "2026-10-24T10:00:00Z"

    @Test
    fun `首次获取 fencing 为 1 且输出 JS ISO 过期时间`() {
        val store = InMemoryLockStore()
        val lock = acquireLock(store, name, "worker-a", at, ttlMs = 1000)!!
        assertEquals(name, lock.name)
        assertEquals("worker-a", lock.holder)
        assertEquals(1, lock.fencing)
        assertEquals(at, lock.acquiredAt)
        assertEquals("2026-10-24T10:00:01.000Z", lock.expiresAt)
        assertEquals(lock, getLock(store, name))
    }

    @Test
    fun `活租约拒绝其他持有者但允许同一持有者重入并自增 fencing`() {
        val store = InMemoryLockStore()
        acquireLock(store, name, "worker-a", at, ttlMs = 1000)
        assertNull(acquireLock(store, name, "worker-b", "2026-10-24T10:00:00.500Z", ttlMs = 1000))

        val again = acquireLock(store, name, "worker-a", "2026-10-24T10:00:00.500Z", ttlMs = 1000)!!
        assertEquals(2, again.fencing)
        assertEquals("2026-10-24T10:00:01.500Z", again.expiresAt)
    }

    @Test
    fun `过期租约可以被接管且 fencing 递增`() {
        val store = InMemoryLockStore()
        acquireLock(store, name, "worker-a", at, ttlMs = 1000)
        val taken = acquireLock(store, name, "worker-b", "2026-10-24T10:00:02Z", ttlMs = 5000)!!
        assertEquals("worker-b", taken.holder)
        assertEquals(2, taken.fencing)
        assertEquals("2026-10-24T10:00:07.000Z", taken.expiresAt)
    }

    @Test
    fun `续租与释放要求 holder 与 fencing 同时匹配`() {
        val store = InMemoryLockStore()
        acquireLock(store, name, "worker-a", at, ttlMs = 1000)
        // 当前持有者用自己的 fencing 续租成功；续租不改 fencing。
        assertTrue(renewLock(store, name, "worker-a", 1, at, ttlMs = 1000))
        val second = acquireLock(store, name, "worker-a", at, ttlMs = 1000)!!
        assertEquals(2, second.fencing)

        assertFalse(renewLock(store, name, "worker-b", 2, at, ttlMs = 1000))
        assertFalse(renewLock(store, name, "worker-a", 1, at, ttlMs = 1000))
        assertTrue(renewLock(store, name, "worker-a", 2, "2026-10-24T10:00:03Z", ttlMs = 5000))
        assertEquals("2026-10-24T10:00:08.000Z", getLock(store, name)!!.expiresAt)

        assertFalse(releaseLock(store, name, "worker-b", 2))
        assertFalse(releaseLock(store, name, "worker-a", 1))
        assertTrue(releaseLock(store, name, "worker-a", 2))
        assertNull(getLock(store, name))
        assertFalse(releaseLock(store, name, "worker-a", 2))
        assertFalse(renewLock(store, "missing", "worker-a", 1, at))
        assertNull(getLock(store, "missing"))
    }

    @Test
    fun `lockExpiry 与 worker expiry 使用同一 ISO 形状`() {
        assertEquals("2026-10-24T10:00:00.250Z", lockExpiry(at, 250))
        assertEquals("2026-10-24T09:59:59.000Z", lockExpiry(at, -1000))
    }
}
