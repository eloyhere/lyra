package com.eloykim.lyra.amp.protocol.concurrency

import com.eloykim.lyra.amp.protocol.json.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

class ConcurrencyTest {

    @Test
    fun `mutex 串行化并保持先进先出`() {
        val mutex = Mutex()
        val order = Collections.synchronizedList(ArrayList<Int>())
        val firstEntered = CountDownLatch(1)
        val first = Thread {
            mutex.run {
                order.add(1)
                firstEntered.countDown()
                Thread.sleep(150)
            }
        }
        first.start()
        assertTrue(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

        val second = Thread { mutex.run { order.add(2) } }
        second.start()
        Thread.sleep(40)
        val third = Thread { mutex.run { order.add(3) } }
        third.start()

        listOf(first, second, third).forEach { it.join(5000) }
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `mutex 保护自增计数`() {
        val mutex = Mutex()
        var counter = 0
        val threads = (1..4).map {
            Thread {
                repeat(500) {
                    mutex.run { counter += 1 }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals(2000, counter)
    }

    @Test
    fun `命名锁注册表复用同一把锁`() {
        val locks = NamedLocks()
        val a = locks.forName("file:a")
        assertSame(a, locks.forName("file:a"))
        assertNotSame(a, locks.forName("file:b"))
        val result = locks.run("file:a") { 42 }
        assertEquals(42, result)
        assertEquals(2, locks.size)
    }

    @Test
    fun `幂等键回放已存储结果`() {
        val store = InMemoryIdempotencyStore()
        var runs = 0
        val first = onceJson(store, "k1", "2026-10-24T10:00:00Z") {
            runs += 1
            JsonParser.parse("""{"n":1}""")
        }
        val second = onceJson(store, "k1", "2026-10-24T10:00:01Z") {
            runs += 1
            JsonParser.parse("""{"n":2}""")
        }
        assertEquals(1, runs)
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(1, (second.value as com.eloykim.lyra.amp.protocol.json.JsonValue.Obj).int("n"))
    }

    @Test
    fun `并发幂等只有一个写入者且值一致`() {
        val store = InMemoryIdempotencyStore()
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(ArrayList<OnceResult<String>>())
        val threads = (1..2).map { index ->
            Thread {
                start.await()
                results.add(
                    once(
                        store = store,
                        key = "race",
                        at = "2026-10-24T10:00:00Z",
                        encode = { it },
                        decode = { it },
                    ) { "v$index" },
                )
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(5000) }

        assertEquals(2, results.size)
        assertEquals(1, results.map { it.value }.distinct().size)
        assertEquals(1, results.count { !it.replayed })
        assertEquals(1, results.count { it.replayed })
    }

    @Test
    fun `总线重试按指数退避且不吞非重试错误`() {
        var attempts = 0
        val delays = ArrayList<Long>()
        val value = withBusyRetry(
            fn = {
                attempts += 1
                if (attempts < 3) {
                    throw IllegalStateException("database is locked")
                }
                "ok"
            },
            options = RetryOptions(sleep = { delays.add(it) }),
        )
        assertEquals("ok", value)
        assertEquals(3, attempts)
        assertEquals(listOf(25L, 50L), delays)

        var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            withBusyRetry({
                calls += 1
                throw IllegalArgumentException("boom")
            })
        }
        assertEquals(1, calls)
        assertTrue(isBusyError(IllegalStateException("SQLITE_BUSY")))
        assertFalse(isBusyError(IllegalStateException("nope")))
    }

    @Test
    fun `worker 注册心跳与 upsert 保留首次 startedAt`() {
        val store = InMemoryWorkerStore()
        val at = "2026-10-24T10:00:00Z"
        val record = registerWorker(
            store,
            WorkerInput(workerId = "w1", host = "host-a", pid = 7, leaseMs = 20_000),
            at,
        )
        assertEquals("w1", record.workerId)
        assertEquals(at, record.startedAt)
        assertEquals(at, record.heartbeatAt)
        assertEquals("2026-10-24T10:00:20.000Z", record.expiresAt)
        assertEquals("host-a", record.host)
        assertEquals(7, record.pid)

        assertTrue(heartbeatWorker(store, "w1", "2026-10-24T10:00:05Z", leaseMs = 15_000))
        assertEquals(
            "2026-10-24T10:00:20.000Z",
            listWorkers(store).single().expiresAt,
        )
        assertFalse(heartbeatWorker(store, "missing", "2026-10-24T10:00:05Z"))

        val again = registerWorker(store, WorkerInput("w1", leaseMs = 10_000), "2026-10-24T10:00:10Z")
        assertEquals(at, again.startedAt)
        assertEquals("2026-10-24T10:00:10Z", again.heartbeatAt)
        assertEquals("2026-10-24T10:00:20.000Z", again.expiresAt)
        assertEquals(null, again.host)

        unregisterWorker(store, "w1")
        assertTrue(listWorkers(store).isEmpty())
    }

    @Test
    fun `过期 worker 的线程被重排且进程回到 idle`() {
        val store = InMemoryWorkerStore()
        val at = "2026-10-24T10:00:00Z"
        registerWorker(store, WorkerInput("w1", leaseMs = 15_000), at)
        registerWorker(store, WorkerInput("w2", leaseMs = 60_000), at)
        store.addThread(pid = 1, state = "running", ownerWorker = "w1", lease = 3)
        store.addThread(pid = 2, state = "running", ownerWorker = "w2", lease = 1)
        store.addProcess(1, "running")
        store.addProcess(2, "running")

        val report = recoverExpiredWorkers(store, "2026-10-24T10:00:20Z")
        assertEquals(listOf("w1"), report.workers)
        assertEquals(1, report.threads)
        assertEquals(1, report.processes)

        val recovered = store.threads().first { it.pid == 1 }
        assertEquals("ready", recovered.state)
        assertEquals(null, recovered.ownerWorker)
        assertEquals(4, recovered.lease)
        assertEquals(0L, recovered.sliceUsedMs)
        assertEquals("running", store.threads().first { it.pid == 2 }.state)
        assertEquals("idle", store.processState(1))
        assertEquals("running", store.processState(2))
        assertEquals(listOf("w2"), listWorkers(store).map { it.workerId })

        val empty = recoverExpiredWorkers(store, "2026-10-24T10:00:21Z")
        assertTrue(empty.workers.isEmpty())
        assertEquals(0, empty.threads)
    }

    @Test
    fun `expiry 复刻 JS toISOString 的三位毫秒`() {
        assertEquals("2026-10-24T10:00:15.000Z", expiry("2026-10-24T10:00:00Z", 15_000))
        assertEquals("2026-10-24T10:00:00.250Z", expiry("2026-10-24T10:00:00Z", 250))
    }
}
