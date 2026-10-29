package com.eloykim.lyra.amp.protocol.goal

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.CriterionKind
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划线格式、前置校验（任务 / 放弃判据 / 预期观察）以及任务图的环与悬挂边。
 */
class PlanTest {

    @Test
    fun `plan 示例通过 schema 且可往返`() {
        val parsed = example("plan.json")
        assertEquals(emptyList<String>(), AmpSchemas.validate("plan.schema.json", parsed))

        val plan = Plan.fromJson(parsed)
        assertEquals("pl.3", plan.id)
        assertEquals(2, plan.tasks.size)
        assertEquals("logs.query", plan.tasks[0].expect.check)
        assertEquals("metrics.query", plan.tasks[1].expect.check)
        assertEquals(0, plan.tasks[0].irreversibility)
        assertEquals(2, plan.tasks[1].irreversibility)
        assertEquals("metrics.query", plan.abandonIf.single().check)
        assertEquals(CriterionKind.QUERY, plan.abandonIf.single().kind)
        assertEquals(listOf(listOf("t1", "t2")), plan.edges)
        assertEquals(1, plan.commitmentWindow)
        assertEquals(1800000L, plan.budget.ms)
        assertEquals(3, plan.monitor?.triggers?.size)
        assertEquals(MonitorAction.RETRY, plan.monitor?.triggers?.get(0)?.action)
        assertEquals(MonitorAction.ABANDON, plan.monitor?.triggers?.get(2)?.action)
        assertEquals(PlanStatus.COMMITTED, plan.status)
        assertEquals("p.deploy_pipeline", plan.process?.id)
        assertNull(plan.warrant)
        assertEquals(CanonicalJson.encode(parsed), CanonicalJson.encode(plan.toJson()))
    }

    @Test
    fun `可选字段缺失时不会写成 null`() {
        val json = plan().toJson()
        assertFalse(json.has("process"))
        assertFalse(json.has("monitor"))
        assertFalse(json.has("rollback"))
        assertFalse(json.has("warrant"))
        assertEquals(emptyList<String>(), AmpSchemas.validate("plan.schema.json", json))
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(Plan.fromJson(json).toJson()))
    }

    @Test
    fun `计划至少需要一个任务且必须有放弃判据`() {
        val noTasks = validatePlanInput(PlanValidationInput(tasks = emptyList(), abandonIf = listOf(criterion("a1"))))
        assertTrue(noTasks.contains("plan requires at least one task"))

        val noAbandon = validatePlanInput(PlanValidationInput(tasks = listOf(task("t1")), abandonIf = emptyList()))
        assertTrue(noAbandon.contains("plan requires abandon_if"))
    }

    @Test
    fun `任务缺少预期观察会被拒绝`() {
        val problems = validatePlanInput(
            PlanValidationInput(tasks = listOf(task("t1", observation = "")), abandonIf = listOf(criterion("a1"))),
        )
        assertTrue(problems.contains("task t1 lacks an expected observation"))
    }

    @Test
    fun `任务图环被检测出来`() {
        val problems = validatePlanInput(
            PlanValidationInput(
                tasks = listOf(task("t1"), task("t2")),
                abandonIf = listOf(criterion("a1")),
                edges = listOf(listOf("t1", "t2"), listOf("t2", "t1")),
            ),
        )
        assertTrue(problems.contains("task graph contains a cycle"))

        val selfLoop = validatePlanInput(
            PlanValidationInput(
                tasks = listOf(task("t1")),
                abandonIf = listOf(criterion("a1")),
                edges = listOf(listOf("t1", "t1")),
            ),
        )
        assertTrue(selfLoop.contains("task graph contains a cycle"))
    }

    @Test
    fun `无环的任务图通过前置校验`() {
        val problems = validatePlanInput(
            PlanValidationInput(
                tasks = listOf(task("t1"), task("t2")),
                abandonIf = listOf(criterion("a1")),
                edges = listOf(listOf("t1", "t2")),
            ),
        )
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `悬挂边在环检测中被忽略但可被显式报告`() {
        val tasks = listOf(task("t1"))
        val edges = listOf(listOf("t1", "tX"))

        assertEquals(
            emptyList<String>(),
            validatePlanInput(PlanValidationInput(tasks = tasks, abandonIf = listOf(criterion("a1")), edges = edges)),
        )
        assertEquals(listOf(listOf("t1", "tX")), danglingEdges(tasks, edges))
        assertEquals(emptyList<List<String>>(), danglingEdges(tasks, listOf(listOf("t1", "t1"))))
    }

    @Test
    fun `process 是 plan schema 的另一分支且可往返`() {
        val process = Process(
            id = "p.deploy_pipeline",
            label = "发布流水线",
            purpose = "把版本推到生产",
            actors = listOf(ProcessActor(ProcessActorKind.AGENT, "a.gamma")),
            stages = listOf(ProcessStage("s1", "a.gamma", "apply manifest", produces = "deployment")),
            invariants = listOf("回滚可用"),
            confidence = 0.6,
            provenance = Provenance(source = "a.gamma"),
            observability = listOf(ObservationPoint("s1", "pod restarts", "kubectl")),
            failureModes = listOf(FailureMode("crashloop", "restart count", "kubectl describe", "rollback")),
            causalEdges = listOf(listOf("s1", "causes")),
        )
        val json = process.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validate("plan.schema.json", json))
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(Process.fromJson(json).toJson()))
    }

    @Test
    fun `不可逆等级与承诺窗口有界`() {
        assertThrows(IllegalArgumentException::class.java) {
            task("t1", irreversibility = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(commitmentWindow = 6)
        }
    }

    private fun task(
        id: String,
        observation: String = "错误率回到基线",
        irreversibility: Int = 0,
    ): PlanTask = PlanTask(
        id = id,
        intent = "对比发布前后两个窗口的错误分布",
        actor = TaskActor.TOOL,
        expect = Expectation(observation = observation, check = "metrics.query"),
        irreversibility = irreversibility,
        status = TaskStatus.PENDING,
    )

    private fun criterion(id: String): Criterion =
        Criterion(id = id, statement = "回滚 10 分钟后错误率仍高于基线 3 倍", check = "metrics.query", kind = CriterionKind.QUERY)

    private fun plan(
        tasks: List<PlanTask> = listOf(task("t1")),
        edges: List<List<String>> = emptyList(),
        abandonIf: List<Criterion> = listOf(criterion("a1")),
        commitmentWindow: Int = 1,
    ): Plan = Plan(
        id = "pl.1",
        goal = Ref(RefKind.GOAL, "g.1"),
        tasks = tasks,
        edges = edges,
        commitmentWindow = commitmentWindow,
        budget = Budget(calls = 2),
        abandonIf = abandonIf,
        status = PlanStatus.DRAFT,
        created = AT,
    )

    private fun example(name: String): JsonValue {
        val text = javaClass.getResource("/amp/examples/$name")?.readText()
            ?: error("缺少测试资源 $name")
        return JsonParser.parse(text)
    }

    private companion object {
        const val AT = "2026-10-24T01:00:00Z"
    }
}
