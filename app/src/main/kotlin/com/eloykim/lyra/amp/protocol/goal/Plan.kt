package com.eloykim.lyra.amp.protocol.goal

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Confidence
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp
import com.eloykim.lyra.amp.protocol.types.requireConfidence

/**
 * 计划对象：process | plan。
 *
 * 对应参考实现 `src/goal/plan.ts`。Process 回答「世界如何运转」，Plan 回答「我将做什么」；
 * 把两者混为一谈是最常见的计划失败。
 */
enum class ProcessActorKind(val wire: String) {
    HUMAN("human"),
    AGENT("agent"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): ProcessActorKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 process actor kind: $value")
    }
}

data class ProcessActor(
    val kind: ProcessActorKind,
    val id: Id,
    val permissions: List<String>? = null,
) {

    init {
        ProtocolIds.require(id, "process.actor.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind.wire),
        "id" to JsonValue.Str(id),
        "permissions" to permissions?.let { jsonStrings(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ProcessActor {
            val obj = value.asObject("process.actor")
            return ProcessActor(
                kind = ProcessActorKind.fromWire(obj.requireString("kind")),
                id = obj.requireString("id"),
                permissions = obj.optionalStrings("permissions"),
            )
        }
    }
}

data class ProcessStage(
    val id: Id,
    val actor: Id,
    val action: String,
    val consumes: String? = null,
    val produces: String? = null,
) {

    init {
        ProtocolIds.require(id, "process.stage.id")
        ProtocolIds.require(actor, "process.stage.actor")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "actor" to JsonValue.Str(actor),
        "action" to JsonValue.Str(action),
        "consumes" to consumes?.let { JsonValue.Str(it) },
        "produces" to produces?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): ProcessStage {
            val obj = value.asObject("process.stage")
            return ProcessStage(
                id = obj.requireString("id"),
                actor = obj.requireString("actor"),
                action = obj.requireString("action"),
                consumes = obj.string("consumes"),
                produces = obj.string("produces"),
            )
        }
    }
}

data class ObservationPoint(
    val stage: Id,
    val signal: String,
    val where: String,
) {

    init {
        ProtocolIds.require(stage, "process.observability.stage")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "stage" to JsonValue.Str(stage),
        "signal" to JsonValue.Str(signal),
        "where" to JsonValue.Str(where),
    )

    companion object {
        fun fromJson(value: JsonValue): ObservationPoint {
            val obj = value.asObject("process.observability")
            return ObservationPoint(
                stage = obj.requireString("stage"),
                signal = obj.requireString("signal"),
                where = obj.requireString("where"),
            )
        }
    }
}

/** 失效模式；`discriminating_test` 是把它与其他原因区分开的检查。 */
data class FailureMode(
    val mode: String,
    val signal: String,
    val discriminatingTest: String,
    val mitigation: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "mode" to JsonValue.Str(mode),
        "signal" to JsonValue.Str(signal),
        "discriminating_test" to JsonValue.Str(discriminatingTest),
        "mitigation" to JsonValue.Str(mitigation),
    )

    companion object {
        fun fromJson(value: JsonValue): FailureMode {
            val obj = value.asObject("process.failure_mode")
            return FailureMode(
                mode = obj.requireString("mode"),
                signal = obj.requireString("signal"),
                discriminatingTest = obj.requireString("discriminating_test"),
                mitigation = obj.requireString("mitigation"),
            )
        }
    }
}

/**
 * 世界的运转方式。亲自跑通这个流程，是 agent 赢得该流程 T3 因果主张的方式。
 */
data class Process(
    val id: Id,
    val label: String,
    val purpose: String,
    val actors: List<ProcessActor>,
    val stages: List<ProcessStage>,
    val invariants: List<String>,
    val confidence: Confidence,
    val provenance: Provenance,
    val observability: List<ObservationPoint>? = null,
    val failureModes: List<FailureMode>? = null,
    val causalEdges: List<List<String>>? = null,
    val unknowns: List<String>? = null,
) {

    init {
        ProtocolIds.require(id, "process.id")
        require(label.isNotBlank()) { "process.label 不能为空" }
        require(purpose.isNotBlank()) { "process.purpose 不能为空" }
        require(actors.isNotEmpty()) { "process.actors 至少要有一个参与者" }
        require(stages.isNotEmpty()) { "process.stages 至少要有一个阶段" }
        requireConfidence(confidence, "process.confidence")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("process"),
        "id" to JsonValue.Str(id),
        "label" to JsonValue.Str(label),
        "purpose" to JsonValue.Str(purpose),
        "actors" to JsonValue.Arr(actors.map { it.toJson() }),
        "stages" to JsonValue.Arr(stages.map { it.toJson() }),
        "invariants" to jsonStrings(invariants),
        "observability" to observability?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "failure_modes" to failureModes?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
        "causal_edges" to causalEdges?.let { edges -> edgesToJson(edges) },
        "unknowns" to unknowns?.let { jsonStrings(it) },
        "confidence" to jsonNumber(confidence),
        "provenance" to provenance.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): Process {
            val obj = value.asObject("process")
            obj.requireAmpEnvelope("process")
            return Process(
                id = obj.requireString("id"),
                label = obj.requireString("label"),
                purpose = obj.requireString("purpose"),
                actors = obj.requireArr("actors").items.map { ProcessActor.fromJson(it) },
                stages = obj.requireArr("stages").items.map { ProcessStage.fromJson(it) },
                invariants = obj.requireStrings("invariants"),
                confidence = obj.requireDouble("confidence"),
                provenance = Provenance.fromJson(obj.requireObj("provenance")),
                observability = obj.arr("observability")?.items?.map { ObservationPoint.fromJson(it) },
                failureModes = obj.arr("failure_modes")?.items?.map { FailureMode.fromJson(it) },
                causalEdges = obj.edgeList("causal_edges"),
                unknowns = obj.optionalStrings("unknowns"),
            )
        }
    }
}

data class Expectation(
    val observation: String,
    val check: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "observation" to JsonValue.Str(observation),
        "check" to JsonValue.Str(check),
    )

    companion object {
        fun fromJson(value: JsonValue): Expectation {
            val obj = value.asObject("plan.expect")
            return Expectation(
                observation = obj.requireString("observation"),
                check = obj.requireString("check"),
            )
        }
    }
}

enum class TaskActor(val wire: String) {
    SELF("self"),
    TOOL("tool"),
    OTHER("other");

    companion object {
        fun fromWire(value: String): TaskActor =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 plan task actor: $value")
    }
}

enum class TaskStatus(val wire: String) {
    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    SKIPPED("skipped");

    companion object {
        fun fromWire(value: String): TaskStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 plan task status: $value")
    }
}

/**
 * 计划步骤。`expect` 是强制的：没有预期观察的任务无法被监控。
 * `irreversibility` 0 = 可自由撤销，3 = 不可逆，决定所需的最低 tier。
 */
data class PlanTask(
    val id: Id,
    val intent: String,
    val actor: TaskActor,
    val expect: Expectation,
    val irreversibility: Int,
    val status: TaskStatus,
    val ref: Ref? = null,
    val pre: List<String> = emptyList(),
    val budget: Budget? = null,
) {

    init {
        ProtocolIds.require(id, "plan.task.id")
        require(intent.isNotBlank()) { "plan.task.intent 不能为空" }
        require(irreversibility in 0..3) { "plan.task.irreversibility 必须在 0..3" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "intent" to JsonValue.Str(intent),
        "actor" to JsonValue.Str(actor.wire),
        "ref" to ref?.toJson(),
        "pre" to jsonStrings(pre),
        "expect" to expect.toJson(),
        "irreversibility" to jsonNumber(irreversibility),
        "budget" to budget?.toJson(),
        "status" to JsonValue.Str(status.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): PlanTask {
            val obj = value.asObject("plan.task")
            return PlanTask(
                id = obj.requireString("id"),
                intent = obj.requireString("intent"),
                actor = TaskActor.fromWire(obj.requireString("actor")),
                expect = Expectation.fromJson(obj.requireObj("expect")),
                irreversibility = obj.requireInt("irreversibility"),
                status = TaskStatus.fromWire(obj.requireString("status")),
                ref = obj.obj("ref")?.let(Ref::fromJson),
                pre = obj.requireStrings("pre"),
                budget = obj.obj("budget")?.let(Budget::fromJson),
            )
        }
    }
}

enum class MonitorAction(val wire: String) {
    RETRY("retry"),
    ADJUST("adjust"),
    RECOMPOSE("recompose"),
    REPLAN("replan"),
    ABANDON("abandon");

    companion object {
        fun fromWire(value: String): MonitorAction =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 monitor action: $value")
    }
}

/** 监控触发器。JSON 字段名是 `when`，Kotlin 里叫 [whenCondition]。 */
data class MonitorTrigger(
    val whenCondition: String,
    val action: MonitorAction,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "when" to JsonValue.Str(whenCondition),
        "action" to JsonValue.Str(action.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): MonitorTrigger {
            val obj = value.asObject("plan.monitor.trigger")
            return MonitorTrigger(
                whenCondition = obj.requireString("when"),
                action = MonitorAction.fromWire(obj.requireString("action")),
            )
        }
    }
}

data class PlanMonitor(val triggers: List<MonitorTrigger>? = null) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "triggers" to triggers?.let { items -> JsonValue.Arr(items.map { it.toJson() }) },
    )

    companion object {
        fun fromJson(value: JsonValue): PlanMonitor {
            val obj = value.asObject("plan.monitor")
            return PlanMonitor(triggers = obj.arr("triggers")?.items?.map { MonitorTrigger.fromJson(it) })
        }
    }
}

enum class PlanStatus(val wire: String) {
    DRAFT("draft"),
    COMMITTED("committed"),
    EXECUTING("executing"),
    DONE("done"),
    ABANDONED("abandoned");

    companion object {
        fun fromWire(value: String): PlanStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 plan.status: $value")
    }
}

/**
 * 计划：任务、依赖边、承诺窗口与放弃判据。
 *
 * `edges` 是二元组数组 `[[from, to], ...]`，与 schema 的 tuple 形状一致。
 */
data class Plan(
    val id: Id,
    val goal: Ref,
    val tasks: List<PlanTask>,
    val edges: List<List<String>>,
    val commitmentWindow: Int,
    val budget: Budget,
    val abandonIf: List<Criterion>,
    val status: PlanStatus,
    val created: Timestamp,
    val process: Ref? = null,
    val monitor: PlanMonitor? = null,
    val rollback: String? = null,
    val warrant: Ref? = null,
) {

    init {
        ProtocolIds.require(id, "plan.id")
        require(tasks.isNotEmpty()) { "plan.tasks 不能为空" }
        require(tasks.size <= 12) { "plan.tasks 最多 12 项" }
        require(abandonIf.isNotEmpty()) { "plan.abandon_if 不能为空" }
        require(commitmentWindow in 1..5) { "plan.commitment_window 必须在 1..5" }
        require(isTimestamp(created)) { "plan.created 不是合法时间: $created" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str("plan"),
        "id" to JsonValue.Str(id),
        "goal" to goal.toJson(),
        "process" to process?.toJson(),
        "tasks" to JsonValue.Arr(tasks.map { it.toJson() }),
        "edges" to edgesToJson(edges),
        "commitment_window" to jsonNumber(commitmentWindow),
        "budget" to budget.toJson(),
        "abandon_if" to JsonValue.Arr(abandonIf.map { it.toJson() }),
        "monitor" to monitor?.toJson(),
        "rollback" to rollback?.let { JsonValue.Str(it) },
        "warrant" to warrant?.toJson(),
        "status" to JsonValue.Str(status.wire),
        "created" to JsonValue.Str(created),
    )

    companion object {
        fun fromJson(value: JsonValue): Plan {
            val obj = value.asObject("plan")
            obj.requireAmpEnvelope("plan")
            return Plan(
                id = obj.requireString("id"),
                goal = Ref.fromJson(obj.requireObj("goal")),
                tasks = obj.requireArr("tasks").items.map { PlanTask.fromJson(it) },
                edges = obj.requireEdgeList("edges"),
                commitmentWindow = obj.requireInt("commitment_window"),
                budget = Budget.fromJson(obj.requireObj("budget")),
                abandonIf = obj.requireArr("abandon_if").items.map { Criterion.fromJson(it) },
                status = PlanStatus.fromWire(obj.requireString("status")),
                created = obj.requireString("created"),
                process = obj.obj("process")?.let(Ref::fromJson),
                monitor = obj.obj("monitor")?.let(PlanMonitor::fromJson),
                rollback = obj.string("rollback"),
                warrant = obj.obj("warrant")?.let(Ref::fromJson),
            )
        }
    }
}

/** 计划的纯前置条件入参；runtime / adapter 在存储计划之前调用 [validatePlanInput]。 */
class PlanValidationInput(
    val tasks: List<PlanTask>,
    val abandonIf: List<Criterion>,
    val edges: List<List<String>> = emptyList(),
)

/**
 * 计划的前置校验，行为与参考实现 `validatePlanInput` 一致：
 * 任务非空、必须有放弃判据、每个任务都要有预期观察、任务图无环。
 * 指向未知任务的悬挂边在环检测中被忽略（与参考实现一致）；需要显式报告时用 [danglingEdges]。
 */
fun validatePlanInput(input: PlanValidationInput): List<String> {
    val problems = ArrayList<String>()
    if (input.tasks.isEmpty()) {
        problems += "plan requires at least one task"
    }
    if (input.abandonIf.isEmpty()) {
        problems += "plan requires abandon_if"
    }
    input.tasks.forEach { task ->
        if (task.expect.observation.isEmpty()) {
            problems += "task ${task.id} lacks an expected observation"
        }
    }
    if (planHasCycle(input.tasks, input.edges)) {
        problems += "task graph contains a cycle"
    }
    return problems
}

/** 端点不在任务集合里的依赖边（map 之外的引用）。 */
fun danglingEdges(tasks: List<PlanTask>, edges: List<List<String>>): List<List<String>> {
    val ids = tasks.map { it.id }.toSet()
    return edges.filter { edge ->
        edge.size != 2 || edge[0] !in ids || edge[1] !in ids
    }
}

private fun planHasCycle(tasks: List<PlanTask>, edges: List<List<String>>): Boolean {
    val ids = tasks.map { it.id }.toSet()
    val graph = LinkedHashMap<String, MutableList<String>>()
    ids.forEach { id -> graph[id] = ArrayList() }
    edges.forEach { edge ->
        val from = edge.getOrNull(0)
        val to = edge.getOrNull(1)
        if (from == null || to == null || from !in ids || to !in ids) {
            return@forEach
        }
        graph[from]?.add(to)
    }

    val visiting = HashSet<String>()
    val visited = HashSet<String>()
    fun visit(id: String): Boolean {
        if (id in visiting) {
            return true
        }
        if (id in visited) {
            return false
        }
        visiting.add(id)
        graph[id].orEmpty().forEach { next ->
            if (visit(next)) {
                return true
            }
        }
        visiting.remove(id)
        visited.add(id)
        return false
    }
    return ids.any { id -> visit(id) }
}

private fun edgesToJson(edges: List<List<String>>): JsonValue.Arr =
    JsonValue.Arr(edges.map { edge -> jsonStrings(edge) })

private fun JsonValue.Obj.requireAmpEnvelope(expectedKind: String) {
    val amp = requireString("amp")
    require(amp == AmpWire.VERSION) { "$expectedKind.amp 必须是 ${AmpWire.VERSION}" }
    val kind = requireString("kind")
    require(kind == expectedKind) { "$expectedKind.kind 必须是 $expectedKind" }
}

private fun JsonValue.Obj.requireDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("缺少数字字段 $key")

private fun JsonValue.Obj.requireStrings(key: String): List<String> =
    requireArr(key).items.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }

private fun JsonValue.Obj.optionalStrings(key: String): List<String>? =
    arr(key)?.items?.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }

private fun JsonValue.Obj.requireEdgeList(key: String): List<List<String>> =
    edgeList(key) ?: throw IllegalArgumentException("缺少数组字段 $key")

private fun JsonValue.Obj.edgeList(key: String): List<List<String>>? =
    arr(key)?.items?.map { item ->
        val edge = item as? JsonValue.Arr
            ?: throw IllegalArgumentException("$key 的每一项必须是两个字符串的数组")
        if (edge.size != 2) {
            throw IllegalArgumentException("$key 的每一项必须有两个字符串")
        }
        edge.items.map { element ->
            (element as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 的每一项必须是字符串")
        }
    }
