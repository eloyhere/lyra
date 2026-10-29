package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.bayes.Dimension
import com.eloykim.lyra.amp.protocol.bayes.Posterior
import com.eloykim.lyra.amp.protocol.interaction.InteractionKind
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind

/**
 * `src/runtime/kernelRuntime.ts` 的移植：以内核为后端的认知运行时。
 *
 * 独立的 [CognitiveRuntime] 拥有轮次循环；这个适配器把一轮绑定到内核任务。内核提供
 * 租约、预算记账、receipt、step 行与哈希链上的 `runtime.turn` 对象。运行时自身仍然
 * 不依赖 SQL/数据库。
 *
 * ## 窄端口：kernel
 *
 * `src/kernel/index.ts` 尚未移植。本文件只声明运行时真正调用到的内核面：
 * `step(work, tid)` 与 `StepReport.receiptId`。参考实现的 `stepAsync` 是异步的；
 * 这里按本项目的端口约定改为同步，线程由宿主决定。
 *
 * 绑定方式：把 `kernel/` 的 `Kernel` 适配成 [Kernel] 端口即可（一次 `step`，
 * 返回 receipt id）。`KernelStep` 的 `status`/`usage`/`objects`/`events` 与参考
 * 实现的 step 返回形状一致。
 */
data class KernelTask(val tid: Int)

data class KernelContext(val task: KernelTask)

/** 内核对象：`kind` + `id` + 哈希链上的 `body`。 */
data class KernelObject(
    val kind: String,
    val id: String,
    val body: JsonValue,
)

data class KernelEvent(
    val topic: String,
    val payload: JsonValue.Obj,
)

/** 一次 step 的返回：状态、用量、对象与事件。 */
data class KernelStep(
    val status: String = "done",
    val usageTokens: Int = 0,
    val objects: List<KernelObject> = emptyList(),
    val events: List<KernelEvent> = emptyList(),
)

/** step 提交后的报告；适配器至少要给出 receipt id。 */
data class StepReport(
    val receiptId: String,
    val status: String = "done",
    val usageTokens: Int = 0,
)

fun interface Kernel {
    /**
     * 在一个内核 step 内执行 [work]；租约不可用（例如任务被抢占）时返回 null。
     */
    fun step(tid: Int, work: (KernelContext) -> KernelStep): StepReport?
}

data class KernelRuntimeTurn(
    val report: StepReport,
    val turn: RuntimeTurn,
    val objectId: String,
    val receiptId: String,
)

data class KernelRuntimeOptions(
    val kernel: Kernel,
    val cognitive: CognitiveRuntime,
    val onTurn: ((KernelRuntimeTurn) -> Unit)? = null,
)

class KernelRuntime(options: KernelRuntimeOptions) {

    private val kernel: Kernel = options.kernel
    private val cognitive: CognitiveRuntime = options.cognitive
    private val onTurn: ((KernelRuntimeTurn) -> Unit)? = options.onTurn
    private var counter: Int = 0

    /**
     * 跑一个确切的任务（不是“下一个就绪任务”），经由一次异步模型轮次。
     */
    fun runTask(tid: Int, input: String): KernelRuntimeTurn? {
        var turn: RuntimeTurn? = null
        var objectId: String? = null
        val report = kernel.step(tid) { context ->
            val completed = cognitive.respond(input)
            turn = completed
            counter += 1
            val id = "runtime.turn.${context.task.tid}.$counter"
            objectId = id
            val serialized = serialize(completed)
            val events = ArrayList<KernelEvent>()
            events.add(
                KernelEvent(
                    topic = "runtime.receipt",
                    payload = withTask(context.task.tid, serialized),
                ),
            )
            for (approval in completed.approvals ?: emptyList()) {
                events.add(
                    KernelEvent(
                        topic = "runtime.approval",
                        payload = jsonObj(
                            "task" to jsonNumber(context.task.tid),
                            "pid" to jsonNumber(completed.pid),
                            "turnId" to jsonText(completed.turnId),
                            "operationId" to jsonText(approval.operationId),
                            "tool" to jsonText(approval.tool),
                            "risk" to jsonText(approval.risk.wire),
                            "decision" to jsonText(approval.decision),
                            "status" to jsonText(approval.status.wire),
                        ),
                    ),
                )
            }
            KernelStep(
                status = "done",
                usageTokens = (completed.usage?.inputTokens ?: 0) + (completed.usage?.outputTokens ?: 0),
                objects = listOf(KernelObject(kind = "runtime.turn", id = id, body = serialized)),
                events = events,
            )
        }
        val doneTurn = turn ?: return null
        val doneObjectId = objectId ?: return null
        val stepReport = report ?: return null
        val result = KernelRuntimeTurn(
            report = stepReport,
            turn = doneTurn,
            objectId = doneObjectId,
            receiptId = stepReport.receiptId,
        )
        onTurn?.invoke(result)
        return result
    }

    /** `{task, ...body}`：与参考实现的对象展开顺序一致。 */
    private fun withTask(tid: Int, body: JsonValue.Obj): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        fields["task"] = jsonNumber(tid)
        fields.putAll(body.fields)
        return JsonValue.Obj(fields)
    }

    private fun meanMap(dimensions: Map<Dimension, Posterior>): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        for ((dimension, posterior) in dimensions) {
            fields[dimension.wire] = jsonNumber(posterior.mean)
        }
        return JsonValue.Obj(fields)
    }

    private fun scenarioWeights(weights: Map<ScenarioKind, Double>): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        for ((kind, weight) in weights) {
            fields[kind.wire] = jsonNumber(weight)
        }
        return JsonValue.Obj(fields)
    }

    private fun interactionWeights(weights: Map<InteractionKind, Double>): JsonValue.Obj {
        val fields = LinkedHashMap<String, JsonValue>()
        for ((kind, weight) in weights) {
            fields[kind.wire] = jsonNumber(weight)
        }
        return JsonValue.Obj(fields)
    }

    private fun usageJson(usage: com.eloykim.lyra.amp.protocol.module.LlmUsage): JsonValue.Obj = jsonObj(
        "inputTokens" to jsonNumber(usage.inputTokens),
        "outputTokens" to jsonNumber(usage.outputTokens),
        "cacheHitTokens" to usage.cacheHitTokens?.let { jsonNumber(it) },
        "cacheMissTokens" to usage.cacheMissTokens?.let { jsonNumber(it) },
        "costUsd" to usage.costUsd?.let { jsonNumber(it) },
    )

    /**
     * 轮次的哈希链 body。字段与参考实现的 `serialize` 一一对应：评分用各维度均值，
     * 注意力用过程级预留与恢复债务，最终再带一个 `runtimeReceiptId`。
     */
    private fun serialize(turn: RuntimeTurn): JsonValue.Obj = jsonObj(
        "turnId" to jsonText(turn.turnId),
        "sessionId" to turn.sessionId?.let { jsonText(it) },
        "scope" to turn.scope?.let { jsonText(it) },
        "pid" to jsonNumber(turn.pid),
        "input" to jsonText(turn.input),
        "at" to jsonText(turn.at),
        "user" to jsonObj(
            "quality" to jsonNumber(turn.user.quality),
            "state" to jsonObj(
                "emotionalIntensity" to jsonNumber(turn.user.state.emotionalIntensity),
                "urgency" to jsonNumber(turn.user.state.urgency),
                "openness" to jsonNumber(turn.user.state.openness),
            ),
            "dimensions" to meanMap(turn.user.dimensions),
        ),
        "scenario" to jsonObj(
            "weights" to scenarioWeights(turn.scenario.weights),
            "active" to jsonStrings(turn.scenario.active.map { it.wire }),
            "mode" to jsonText(turn.scenario.mode.wire),
            "tokenBudgetFactor" to jsonNumber(turn.scenario.tokenBudgetFactor),
            "contextDepth" to jsonNumber(turn.scenario.contextDepth),
            "rationale" to jsonText(turn.scenario.rationale),
        ),
        "interaction" to jsonObj(
            "weights" to interactionWeights(turn.interaction.weights),
            "primary" to jsonText(turn.interaction.primary.wire),
            "secondary" to turn.interaction.secondary?.let { jsonText(it.wire) },
            "sequence" to jsonStrings(turn.interaction.sequence.map { it.wire }),
            "moves" to jsonStrings(turn.interaction.moves.map { it.wire }),
            "response" to jsonObj(
                "pace" to jsonText(turn.interaction.response.pace.wire),
                "verbosity" to jsonText(turn.interaction.response.verbosity.wire),
                "questionRatio" to jsonNumber(turn.interaction.response.questionRatio),
                "structure" to jsonText(turn.interaction.response.structure.wire),
            ),
            "safety" to jsonObj(
                "noDiagnosis" to jsonBool(turn.interaction.safety.noDiagnosis),
                "noManipulation" to jsonBool(turn.interaction.safety.noManipulation),
                "inviteDissent" to jsonBool(turn.interaction.safety.inviteDissent),
                "escalateRisk" to jsonBool(turn.interaction.safety.escalateRisk),
                "labelHypotheses" to jsonBool(turn.interaction.safety.labelHypotheses),
            ),
            "rationale" to jsonText(turn.interaction.rationale),
        ),
        "attention" to jsonObj(
            "utilization" to jsonNumber(turn.attention.utilization),
            "pressure" to jsonNumber(turn.attention.pressure),
            "rest" to jsonBool(turn.attention.rest),
            "foregroundSlots" to jsonNumber(turn.attention.foreground?.slots ?: 0),
            "safetySlots" to jsonNumber(turn.attention.process.safetySlots),
            "recoveryDebt" to jsonNumber(turn.attention.process.recoveryDebt),
        ),
        "answer" to jsonText(turn.answer),
        "answerScore" to turn.answerScore?.let { score ->
            jsonObj(
                "overall" to jsonNumber(score.overall),
                "dimensions" to meanMap(score.dimensions),
            )
        },
        "usage" to turn.usage?.let { usageJson(it) },
        "finishReason" to turn.finishReason?.let { jsonText(it.wire) },
        "toolCalls" to turn.toolCalls?.let { calls ->
            JsonValue.Arr(
                calls.map { call ->
                    jsonObj(
                        "id" to jsonText(call.id),
                        "actionId" to call.actionId?.let { jsonText(it) },
                        "operationId" to call.operationId?.let { jsonText(it) },
                        "name" to jsonText(call.name),
                        "arguments" to jsonText(call.arguments),
                        "ok" to jsonBool(call.ok),
                        "content" to jsonText(call.content),
                        "error" to call.error?.let { jsonText(it) },
                        "risk" to call.risk?.let { jsonText(it.wire) },
                        "decision" to call.decision?.let { jsonText(it) },
                        "redacted" to jsonBool(call.redacted),
                    )
                },
            )
        },
        "restSuggested" to jsonBool(turn.restSuggested),
        "latencyMs" to turn.latencyMs?.let { jsonNumber(it) },
        "toolErrors" to jsonNumber(turn.toolErrors),
        "telemetry" to turn.telemetry?.let { sample ->
            jsonObj(
                "systemLoad" to sample.systemLoad?.let { jsonNumber(it) },
                "eventLoopLagMs" to sample.eventLoopLagMs?.let { jsonNumber(it) },
                "memoryMb" to sample.memoryMb?.let { jsonNumber(it) },
                "queueDepth" to sample.queueDepth?.let { jsonNumber(it) },
                "errorRate" to sample.errorRate?.let { jsonNumber(it) },
            )
        },
        "escalationId" to turn.escalationId?.let { jsonText(it) },
        "behavior" to turn.behavior?.let { behaviorJson(it) },
        "actions" to turn.actions?.let { records ->
            JsonValue.Arr(
                records.map { action ->
                    jsonObj(
                        "id" to jsonText(action.id),
                        "turnId" to jsonText(action.turnId),
                        "goal" to jsonText(action.goal),
                        "intent" to jsonText(action.intent),
                        "expectedEffect" to jsonText(action.expectedEffect),
                        "preconditions" to jsonStrings(action.preconditions),
                        "successCriteria" to jsonStrings(action.successCriteria),
                        "risk" to jsonText(action.risk.wire),
                        "status" to jsonText(action.status.wire),
                        "operationIds" to jsonStrings(action.operationIds.toList()),
                    )
                },
            )
        },
        "operations" to turn.operations?.let { records ->
            JsonValue.Arr(
                records.map { operation ->
                    jsonObj(
                        "id" to jsonText(operation.id),
                        "actionId" to jsonText(operation.actionId),
                        "kind" to jsonText(operation.kind.wire),
                        "executor" to jsonText(operation.executor),
                        "sideEffect" to jsonText(operation.sideEffect.wire),
                        "status" to jsonText(operation.status.wire),
                        "idempotencyKey" to operation.idempotencyKey?.let { jsonText(it) },
                        "input" to operation.input,
                        "result" to operation.result,
                    )
                },
            )
        },
        "approvals" to turn.approvals?.let { records ->
            JsonValue.Arr(
                records.map { approval ->
                    jsonObj(
                        "operationId" to jsonText(approval.operationId),
                        "tool" to jsonText(approval.tool),
                        "risk" to jsonText(approval.risk.wire),
                        "decision" to jsonText(approval.decision),
                        "status" to jsonText(approval.status.wire),
                    )
                },
            )
        },
        "diagnosis" to turn.diagnosis?.let { frame ->
            jsonObj(
                "id" to jsonText(frame.id),
                "incident" to jsonObj(
                    "id" to jsonText(frame.incident.id),
                    "summary" to jsonText(frame.incident.summary),
                    "symptoms" to jsonStrings(frame.incident.symptoms),
                ),
                "layers" to jsonStrings(frame.layers.map { it.wire }),
                "phase" to jsonText(frame.phase.wire),
                "selected" to frame.selected?.let { jsonText(it) },
            )
        },
        "safety" to jsonObj(
            "action" to jsonText(turn.safety.action.wire),
            "reasons" to jsonStrings(turn.safety.reasons),
        ),
        "trace" to jsonStrings(turn.trace),
        "runtimeReceiptId" to jsonText(turn.receiptId),
    )

    private fun behaviorJson(plan: BehaviorPlan): JsonValue.Obj = jsonObj(
        "activations" to JsonValue.Arr(
            plan.activations.map { activation ->
                jsonObj(
                    "behaviorId" to jsonText(activation.behaviorId),
                    "turnId" to jsonText(activation.turnId),
                    "trigger" to jsonText(activation.trigger),
                    "confidence" to jsonNumber(activation.confidence),
                    "priority" to jsonNumber(activation.priority),
                )
            },
        ),
        "guardrails" to jsonStrings(plan.guardrails),
        "maxActionsPerTurn" to jsonNumber(plan.maxActionsPerTurn),
        "preferredActions" to jsonStrings(plan.preferredActions),
        "winner" to plan.winner?.let { jsonText(it) },
        "conflicts" to JsonValue.Arr(
            plan.conflicts.map { conflict ->
                jsonObj(
                    "winner" to jsonText(conflict.winner),
                    "loser" to jsonText(conflict.loser),
                    "reason" to jsonText(conflict.reason),
                    "winnerPriority" to jsonNumber(conflict.winnerPriority),
                    "loserPriority" to jsonNumber(conflict.loserPriority),
                )
            },
        ),
    )
}
