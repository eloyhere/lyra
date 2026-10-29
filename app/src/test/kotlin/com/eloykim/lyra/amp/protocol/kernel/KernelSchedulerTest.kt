package com.eloykim.lyra.amp.protocol.kernel

import com.eloykim.lyra.amp.protocol.concurrency.WorkerInput
import com.eloykim.lyra.amp.protocol.concurrency.registerWorker
import com.eloykim.lyra.amp.protocol.error.KernelCode
import com.eloykim.lyra.amp.protocol.error.KernelError
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.thread.CreateThreadInput
import com.eloykim.lyra.amp.protocol.thread.ThreadState
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSchedulerTest {

    private class Clock(var at: String)

    private class Fixture(
        val taskBudget: Budget = Budget(),
        val agentBudget: Budget = Budget(),
        val configure: (KernelSchedulerOptions) -> KernelSchedulerOptions = { it },
    ) {
        val clock = Clock("2026-10-29T00:00:00.000Z")
        val store = InMemoryKernelStore()
        val agent: ProcessRecord
        val kernel: KernelScheduler

        init {
            agent = store.createAgent(
                id = "p.planner",
                name = "Planner",
                at = clock.at,
                budget = agentBudget,
            )
            kernel = KernelScheduler(
                store = store,
                options = configure(
                    KernelSchedulerOptions(
                        now = { clock.at },
                        workerId = "w.test",
                    ),
                ),
            )
        }

        fun createTask(
            id: String = "t.1",
            goal: String = "g.1",
            budget: Budget = taskBudget,
        ) = store.createTask(
            CreateThreadInput(
                id = id,
                pid = agent.pid,
                goal = goal,
                statement = "do the work",
                success = listOf(Criterion(id = "c.$id", statement = "done", check = "check")),
                priority = 0,
                quantumMs = null,
                budget = budget,
                at = clock.at,
            ),
        )
    }

    private fun receiptBody(fixture: Fixture, receiptId: String): JsonValue.Obj {
        val row = fixture.store.find("receipt", receiptId)
        assertNotNull(row)
        return JsonParser.parse(row!!.body) as JsonValue.Obj
    }

    @Test
    fun `done commits a receipt, a step, and settles the agent`() {
        val fixture = Fixture()
        val task = fixture.createTask()

        val report = fixture.kernel.step { KernelStepOutcome.Done(note = "all good") }!!

        assertEquals(KernelStepStatus.DONE, report.status)
        assertEquals("done", report.threadState)
        assertEquals("idle", report.processState)
        assertEquals(1, report.pid)
        assertEquals(task.tid, report.tid)
        assertEquals("rc.1.1", report.receiptId)
        assertEquals(1, report.usage.calls)
        assertEquals("all good", report.note)

        val body = receiptBody(fixture, "rc.1.1")
        assertEquals("receipt", body.string("kind"))
        assertEquals("act", body.string("op"))
        assertEquals("success", body.string("outcome"))
        assertEquals("all good", body.string("note"))
        assertEquals("thread", body.obj("target")?.string("kind"))
        assertEquals("t.1", body.obj("target")?.string("id"))
        assertEquals(1, body.obj("usage")?.int("calls"))

        assertEquals(1, fixture.store.countSteps(task.tid))
        assertEquals("done", fixture.store.stepRecords().single().status)
        assertTrue(fixture.store.verifyObjects().ok)
        assertEquals(ProcessState.IDLE, fixture.store.getAgent(fixture.agent.pid).state)
        assertNull(fixture.kernel.step { error("handler must not run while idle") })
    }

    @Test
    fun `yield puts the task back in the ready queue and numbers receipts per task`() {
        val fixture = Fixture()
        val task = fixture.createTask()

        val first = fixture.kernel.step { KernelStepOutcome.Yield(note = "slice used") }!!
        assertEquals(KernelStepStatus.YIELD, first.status)
        assertEquals("ready", first.threadState)
        assertEquals("idle", first.processState)
        assertEquals("rc.${task.tid}.1", first.receiptId)
        assertEquals("success", receiptBody(fixture, first.receiptId).string("outcome"))

        val second = fixture.kernel.step { KernelStepOutcome.Done() }!!
        assertEquals(KernelStepStatus.DONE, second.status)
        assertEquals(task.tid, second.tid)
        assertEquals("rc.${task.tid}.2", second.receiptId)
        assertEquals(2, fixture.store.countSteps(task.tid))
    }

    @Test
    fun `a throwing handler becomes a failed step with a failure receipt`() {
        val fixture = Fixture()
        val task = fixture.createTask()

        val report = fixture.kernel.step { throw IllegalStateException("boom") }!!

        assertEquals(KernelStepStatus.FAIL, report.status)
        assertEquals("boom", report.note)
        assertEquals("failed", report.threadState)
        assertEquals("idle", report.processState)
        assertEquals("failure", receiptBody(fixture, report.receiptId).string("outcome"))
        assertEquals("boom", receiptBody(fixture, report.receiptId).string("note"))
        assertEquals("failed", fixture.store.getTask(task.tid).state.wire)
        assertEquals("boom", fixture.store.getTask(task.tid).exitNote)
        assertEquals("fail", fixture.store.stepRecords().single().status)
    }

    @Test
    fun `sleep blocks until the injected clock reaches wakeAt`() {
        val fixture = Fixture()
        val task = fixture.createTask()
        val wakeAt = "2026-10-29T00:00:09.000Z"

        val report = fixture.kernel.step {
            KernelStepOutcome.Sleep(wakeAt = wakeAt, note = "waiting")
        }!!
        assertEquals(KernelStepStatus.SLEEP, report.status)
        assertEquals("blocked", report.threadState)
        assertEquals(wakeAt, fixture.store.getTask(task.tid).wakeAt)
        assertEquals("partial", receiptBody(fixture, report.receiptId).string("outcome"))
        assertEquals("waiting", receiptBody(fixture, report.receiptId).string("note"))
        assertEquals("sleep", fixture.store.stepRecords().single().status)

        assertNull(fixture.kernel.step { error("sleeping task is not runnable") })

        fixture.clock.at = wakeAt
        val done = fixture.kernel.step { KernelStepOutcome.Done(note = "woke") }!!
        assertEquals(KernelStepStatus.DONE, done.status)
        assertEquals(2, fixture.store.countSteps(task.tid))
    }

    @Test
    fun `block waits for notify and then runs again`() {
        val fixture = Fixture()
        val task = fixture.createTask()

        val report = fixture.kernel.step {
            KernelStepOutcome.Block(blockedOn = "mailbox:p.1", note = "inbox empty")
        }!!
        assertEquals(KernelStepStatus.BLOCK, report.status)
        assertEquals("blocked", report.threadState)
        assertEquals("mailbox:p.1", fixture.store.getTask(task.tid).blockedOn)
        assertEquals("partial", receiptBody(fixture, report.receiptId).string("outcome"))
        assertEquals("block", fixture.store.stepRecords().single().status)

        assertNull(fixture.kernel.step { error("blocked task is not runnable") })
        assertEquals(1, fixture.store.notify("mailbox:p.1"))

        val done = fixture.kernel.step { KernelStepOutcome.Done() }!!
        assertEquals(KernelStepStatus.DONE, done.status)
        assertNull(fixture.store.getTask(task.tid).blockedOn)
    }

    @Test
    fun `task budget exhaustion fails the task but not the agent`() {
        val fixture = Fixture(taskBudget = Budget(tokens = 5))
        val task = fixture.createTask()

        val report = fixture.kernel.step {
            KernelStepOutcome.Done(usage = Budget(tokens = 10))
        }!!

        assertEquals(KernelStepStatus.FAIL, report.status)
        assertEquals("task budget exceeded", report.note)
        assertEquals("failed", report.threadState)
        assertEquals("idle", report.processState)
        assertEquals("blocked", receiptBody(fixture, report.receiptId).string("outcome"))
        assertEquals(
            "task budget exceeded: tokens",
            receiptBody(fixture, report.receiptId).string("note"),
        )
        assertEquals("budget", fixture.store.stepRecords().single().status)
        assertEquals("task budget exceeded: tokens", fixture.store.getTask(task.tid).exitNote)
        assertEquals(10, fixture.store.getTask(task.tid).used.tokens)
        assertEquals(10, fixture.store.getAgent(fixture.agent.pid).used.tokens)
    }

    @Test
    fun `agent budget exhaustion suspends the agent and fails the task`() {
        val fixture = Fixture(agentBudget = Budget(tokens = 5))
        val task = fixture.createTask()

        val report = fixture.kernel.step {
            KernelStepOutcome.Done(usage = Budget(tokens = 10))
        }!!

        assertEquals(KernelStepStatus.FAIL, report.status)
        assertEquals("agent budget exceeded", report.note)
        assertEquals("failed", report.threadState)
        assertEquals("suspended", report.processState)
        assertEquals(
            "agent budget exceeded: tokens",
            receiptBody(fixture, report.receiptId).string("note"),
        )
        assertEquals("agent budget exceeded", fixture.store.getTask(task.tid).exitNote)
        assertEquals(ProcessState.SUSPENDED, fixture.store.getAgent(fixture.agent.pid).state)
        assertEquals("budget", fixture.store.stepRecords().single().status)
    }

    @Test
    fun `a stale claim causes lease_lost and the claimed task stays recoverable`() {
        val fixture = Fixture()
        val task = fixture.createTask()

        val error = assertThrows(KernelError::class.java) {
            fixture.kernel.step { context ->
                context.execution.releaseTask(
                    context.task.tid,
                    fixture.kernel.workerId,
                    context.task.lease,
                )
                KernelStepOutcome.Done()
            }
        }
        assertEquals(KernelCode.LEASE_LOST, error.typedCode)
        assertEquals("ready", fixture.store.getTask(task.tid).state.wire)
        assertEquals(0, fixture.store.countSteps(task.tid))

        val retry = fixture.kernel.step { KernelStepOutcome.Done(note = "retry") }!!
        assertEquals(KernelStepStatus.DONE, retry.status)
        assertEquals("rc.${task.tid}.1", retry.receiptId)
    }

    @Test
    fun `objects and events commit inside the step and the sink sees the event`() {
        var seen: KernelStepEvent? = null
        var seenAt: String? = null
        val fixture = Fixture(
            configure = { options ->
                options.copy(
                    eventSink = KernelStepEventSink { event, at ->
                        seen = event
                        seenAt = at
                    },
                )
            },
        )
        val task = fixture.createTask()

        val report = fixture.kernel.step {
            KernelStepOutcome.Done(
                objects = listOf(
                    KernelStepObject(
                        kind = "claim",
                        id = "cl.1",
                        body = jsonObj("statement" to jsonText("the build is green")),
                    ),
                ),
                events = listOf(
                    KernelStepEvent(
                        topic = "claim.created",
                        payload = jsonObj("id" to jsonText("cl.1")),
                    ),
                ),
            )
        }!!

        assertNotNull(fixture.store.find("claim", "cl.1"))
        assertEquals("claim.created", seen?.topic)
        assertEquals("cl.1", (seen?.payload as JsonValue.Obj).string("id"))
        assertEquals(report.receiptId, fixture.store.stepRecords().single().receiptId)
        assertEquals(fixture.clock.at, seenAt)
        assertEquals(1, fixture.store.countSteps(task.tid))
    }

    @Test
    fun `context window pages persist across steps`() {
        val fixture = Fixture()
        val task = fixture.createTask()

        fixture.kernel.step { context ->
            context.window.fault("claim", "cl.1", 10)
            KernelStepOutcome.Yield(note = "page loaded")
        }
        assertEquals(1, fixture.store.storedPages(task.tid).size)
        assertEquals("claim", fixture.store.storedPages(task.tid).single().objectKind)

        var resident = false
        fixture.kernel.step { context ->
            resident = context.window.has("claim", "cl.1")
            KernelStepOutcome.Done()
        }
        assertTrue(resident)
    }

    @Test
    fun `agent context and attention providers receive the running ids`() {
        var agentContextPid: Int? = null
        var attentionTid: Int? = null
        val fixture = Fixture(
            configure = { options ->
                options.copy(
                    agentContext = { pid ->
                        agentContextPid = pid
                        null
                    },
                    attention = { _, task ->
                        attentionTid = task.tid
                        null
                    },
                )
            },
        )
        val task = fixture.createTask()

        fixture.kernel.step { KernelStepOutcome.Done() }

        assertEquals(fixture.agent.pid, agentContextPid)
        assertEquals(task.tid, attentionTid)
    }

    @Test
    fun `run drains the ready queue and stepTask targets one task`() {
        val fixture = Fixture()
        val first = fixture.createTask(id = "t.1", goal = "g.1")
        fixture.createTask(id = "t.2", goal = "g.2")

        val targeted = fixture.kernel.stepTask(first.tid + 1) { KernelStepOutcome.Done(note = "target") }!!
        assertEquals(first.tid + 1, targeted.tid)
        assertEquals("ready", fixture.store.getTask(first.tid).state.wire)
        assertNull(fixture.kernel.stepTask(targeted.tid) { error("task is already done") })

        val reports = fixture.kernel.run(handler = { KernelStepOutcome.Done() })
        assertEquals(1, reports.size)
        assertEquals(first.tid, reports.single().tid)
        assertEquals(
            emptyList<KernelStepReport>(),
            fixture.kernel.run(handler = { KernelStepOutcome.Done() }),
        )
    }

    @Test
    fun `recover requeues threads of expired workers and close unregisters this worker`() {
        val fixture = Fixture()
        val task = fixture.createTask()
        val other = "w.other"
        registerWorker(
            store = fixture.store.workers,
            input = WorkerInput(workerId = other, leaseMs = 1_000),
            at = fixture.clock.at,
        )
        fixture.store.claimTask(task.tid, other)
        fixture.store.beginAgent(fixture.agent.pid, fixture.clock.at)

        fixture.clock.at = "2026-10-29T00:00:02.000Z"
        val recovery = fixture.kernel.recover()

        assertTrue(recovery.threads >= 1)
        assertEquals(ThreadState.READY, fixture.store.getTask(task.tid).state)
        assertEquals(ProcessState.IDLE, fixture.store.getAgent(fixture.agent.pid).state)
        assertFalse(fixture.store.all().any { it.workerId == other })

        fixture.kernel.close()
        assertTrue(fixture.store.all().isEmpty())
    }
}
