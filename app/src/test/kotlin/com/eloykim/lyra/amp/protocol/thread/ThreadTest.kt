package com.eloykim.lyra.amp.protocol.thread

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTest {

    private class FakeProcesses(private val states: Map<Int, String>) : ThreadProcessPort {

        override fun process(pid: Int): ThreadProcess? =
            states[pid]?.let { ThreadProcess(id = "p.$pid", state = it) }
    }

    private class FakeThreadStore : ThreadStore {

        val tasks = LinkedHashMap<Int, ThreadRecord>()
        var created: CreateThreadInput? = null
        var transitionFrom: List<ThreadState>? = null
        var transitionTo: ThreadState? = null
        var transitionPatch: ThreadPatch? = null
        var transitionLease: Int? = null
        private var nextTid = 1

        override fun getTask(tid: Int): ThreadRecord =
            tasks[tid] ?: throw ThreadError("thread_not_found", listOf("tid $tid"))

        override fun findTaskByExternalId(id: String): ThreadRecord? =
            tasks.values.firstOrNull { it.id == id }

        override fun listTasks(pid: Int?): List<ThreadRecord> =
            tasks.values.filter { pid == null || it.pid == pid }

        override fun findActiveTaskByGoal(pid: Int, goal: String): ThreadRecord? =
            tasks.values.firstOrNull {
                it.pid == pid && it.goal == goal &&
                    (it.state == ThreadState.READY || it.state == ThreadState.RUNNING || it.state == ThreadState.BLOCKED)
            }

        override fun countTasks(pid: Int): Int = tasks.values.count { it.pid == pid }

        override fun createTask(input: CreateThreadInput): ThreadRecord {
            created = input
            val tid = nextTid++
            val record = ThreadRecord(
                tid = tid,
                id = input.id,
                pid = input.pid,
                goal = input.goal,
                statement = input.statement,
                success = input.success,
                state = ThreadState.READY,
                priority = input.priority ?: 0,
                quantumMs = input.quantumMs ?: 0,
                sliceUsedMs = 0,
                budget = input.budget ?: Budget(),
                used = Budget(),
                createdAt = input.at,
                lease = 0,
                version = 1,
            )
            tasks[tid] = record
            return record
        }

        override fun nextReadyTask(): ThreadRecord? =
            tasks.values.firstOrNull { it.state == ThreadState.READY }

        override fun transitionTask(
            tid: Int,
            from: List<ThreadState>,
            to: ThreadState,
            patch: ThreadPatch,
            lease: Int?,
        ): ThreadRecord {
            transitionFrom = from
            transitionTo = to
            transitionPatch = patch
            transitionLease = lease
            val record = getTask(tid)
            val updated = record.copy(state = to, version = record.version + 1)
            tasks[tid] = updated
            return updated
        }

        override fun claimTask(tid: Int, workerId: String): ThreadClaim =
            ThreadClaim(tid = tid, lease = 1, workerId = workerId)

        override fun releaseTask(tid: Int, workerId: String, lease: Int): Boolean = true

        override fun chargeTask(tid: Int, usage: Budget, at: String): ChargeResult =
            ChargeResult(ok = true, exceeded = emptyList())

        override fun notify(condition: String): Int = 2

        override fun wake(at: String): Int = 3

        override fun recover(workerId: String?): ThreadRecovery = ThreadRecovery(1, 1)

        override fun liveTaskCount(pid: Int): Int = tasks.values.count { it.pid == pid }

        override fun readyTaskCount(pid: Int): Int =
            tasks.values.count { it.pid == pid && it.state == ThreadState.READY }

        override fun runningTaskCount(pid: Int): Int =
            tasks.values.count { it.pid == pid && it.state == ThreadState.RUNNING }
    }

    private val criterion = Criterion(id = "c1", statement = "errors stop", check = "metrics.query")
    private val at = "2026-10-24T10:00:00Z"

    private fun spec(
        goal: String = "g.1",
        id: String? = null,
        statement: String = "compare windows",
        success: List<Criterion> = listOf(criterion),
    ): ThreadSpec = ThreadSpec(
        id = id,
        pid = 1,
        goal = goal,
        statement = statement,
        success = success,
    )

    @Test
    fun `生命周期边与终态`() {
        assertTrue(canThreadTransition(ThreadState.READY, ThreadState.RUNNING))
        assertTrue(canThreadTransition(ThreadState.RUNNING, ThreadState.BLOCKED))
        assertTrue(canThreadTransition(ThreadState.BLOCKED, ThreadState.READY))
        assertFalse(canThreadTransition(ThreadState.READY, ThreadState.BLOCKED))
        assertFalse(canThreadTransition(ThreadState.DONE, ThreadState.RUNNING))
        assertFalse(canThreadTransition(ThreadState.FAILED, ThreadState.READY))
        assertTrue(ThreadEdges.getValue(ThreadState.DONE).isEmpty())
    }

    @Test
    fun `spawn 生成默认 id 并写入存储`() {
        val store = FakeThreadStore()
        val record = spawnThread(store, FakeProcesses(mapOf(1 to "idle")), spec(), at)
        assertEquals("t.1.1", record.id)
        assertEquals(ThreadState.READY, record.state)
        assertEquals("g.1", record.goal)
        assertEquals(at, record.createdAt)
        assertEquals("t.1.1", store.created?.id)
        assertEquals(listOf(record), store.listTasks(1))
    }

    @Test
    fun `spawn 校验进程状态目标语句判据与重复任务`() {
        val processes = FakeProcesses(mapOf(1 to "idle", 2 to "terminated"))

        val store = FakeThreadStore()
        assertEquals(
            "unknown_process",
            assertThrows(ThreadError::class.java) {
                spawnThread(store, processes, spec().copy(pid = 9), at)
            }.code,
        )
        assertEquals(
            "invalid_transition",
            assertThrows(ThreadError::class.java) {
                spawnThread(store, processes, spec().copy(pid = 2), at)
            }.code,
        )
        assertEquals(
            "invalid_thread",
            assertThrows(ThreadError::class.java) {
                spawnThread(store, processes, spec(goal = "Bad Goal"), at)
            }.code,
        )
        assertEquals(
            "invalid_thread",
            assertThrows(ThreadError::class.java) {
                spawnThread(store, processes, spec(statement = "  "), at)
            }.code,
        )
        assertEquals(
            "invalid_thread",
            assertThrows(ThreadError::class.java) {
                spawnThread(store, processes, spec(success = emptyList()), at)
            }.code,
        )
        assertEquals(
            "invalid_thread",
            assertThrows(ThreadError::class.java) {
                spawnThread(store, processes, spec(id = "Bad Id"), at)
            }.code,
        )

        spawnThread(store, processes, spec(), at)
        val duplicate = assertThrows(ThreadError::class.java) {
            spawnThread(store, processes, spec(), at)
        }
        assertEquals("duplicate_task", duplicate.code)
    }

    @Test
    fun `terminate 后可以重新 spawn 同一目标`() {
        val store = FakeThreadStore()
        val processes = FakeProcesses(mapOf(1 to "idle"))
        val first = spawnThread(store, processes, spec(), at)
        store.tasks[first.tid] = first.copy(state = ThreadState.DONE)
        val second = spawnThread(store, processes, spec(), at)
        assertEquals("t.1.2", second.id)
        assertEquals(2, store.countTasks(1))
    }

    @Test
    fun `调度与租约操作透传存储`() {
        val store = FakeThreadStore()
        val created = spawnThread(store, FakeProcesses(mapOf(1 to "idle")), spec(), at)
        assertEquals(created, nextReadyThread(store))
        assertEquals(created, findThread(store, created.id))
        assertEquals(created, getThread(store, created.tid))
        assertNull(findThread(store, "missing"))

        val patch = ThreadPatch(
            blockedOn = PatchField.Value("mailbox:1"),
            wakeAt = PatchField.Clear,
            sliceUsedMs = PatchField.Value(250L),
        )
        val transitioned = transitionThread(
            store,
            created.tid,
            ThreadState.READY,
            ThreadState.RUNNING,
            patch,
            lease = 3,
        )
        assertEquals(ThreadState.RUNNING, transitioned.state)
        assertEquals(listOf(ThreadState.READY), store.transitionFrom)
        assertEquals(ThreadState.RUNNING, store.transitionTo)
        assertEquals(3, store.transitionLease)
        assertEquals(PatchField.Value("mailbox:1"), store.transitionPatch?.blockedOn)
        assertEquals(PatchField.Clear, store.transitionPatch?.wakeAt)

        assertEquals(ThreadClaim(created.tid, 1, "w1"), claimThread(store, created.tid, "w1"))
        assertTrue(releaseThread(store, created.tid, "w1", 1))
        assertEquals(ChargeResult(true, emptyList()), chargeThread(store, created.tid, Budget(calls = 1), at))
        assertEquals(2, notifyCondition(store, "mailbox:1"))
        assertEquals(3, wakeSleeping(store, at))
        assertEquals(ThreadRecovery(1, 1), recoverThreads(store))
        assertEquals(1, liveThreadCount(store, 1))
        assertEquals(0, readyThreadCount(store, 1))
        assertEquals(1, runningThreadCount(store, 1))
    }
}
