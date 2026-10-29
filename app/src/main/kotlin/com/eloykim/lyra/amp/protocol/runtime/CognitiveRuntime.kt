package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.attention.AttentionPlan
import com.eloykim.lyra.amp.protocol.attention.CognitiveSource
import com.eloykim.lyra.amp.protocol.attention.CognitiveState
import com.eloykim.lyra.amp.protocol.attention.ThreadAttentionCandidate
import com.eloykim.lyra.amp.protocol.attention.planAttention
import com.eloykim.lyra.amp.protocol.attention.processAttention
import com.eloykim.lyra.amp.protocol.bayes.AnswerMeta
import com.eloykim.lyra.amp.protocol.bayes.AnswerScore
import com.eloykim.lyra.amp.protocol.bayes.BayesianEngine
import com.eloykim.lyra.amp.protocol.bayes.Dimension
import com.eloykim.lyra.amp.protocol.bayes.UserScore
import com.eloykim.lyra.amp.protocol.context.AgentContext
import com.eloykim.lyra.amp.protocol.context.AgentContextOptions
import com.eloykim.lyra.amp.protocol.context.AgentReader
import com.eloykim.lyra.amp.protocol.context.SocialReader
import com.eloykim.lyra.amp.protocol.context.agentContextToAst
import com.eloykim.lyra.amp.protocol.context.assembleAgentContext
import com.eloykim.lyra.amp.protocol.diagnosis.ActionPlan as DiagnosisActionPlan
import com.eloykim.lyra.amp.protocol.diagnosis.DiagnosisContext as DiagnosisAstContext
import com.eloykim.lyra.amp.protocol.diagnosis.DiagnosticFrame
import com.eloykim.lyra.amp.protocol.diagnosis.IncidentInput
import com.eloykim.lyra.amp.protocol.diagnosis.buildDiagnosisFrame
import com.eloykim.lyra.amp.protocol.diagnosis.toActionPlan as diagnosisToActionPlan
import com.eloykim.lyra.amp.protocol.diagnosis.toContextAst as diagnosisToContextAst
import com.eloykim.lyra.amp.protocol.interaction.InteractionKind
import com.eloykim.lyra.amp.protocol.interaction.InteractionMix
import com.eloykim.lyra.amp.protocol.interaction.InteractionPlan
import com.eloykim.lyra.amp.protocol.interaction.resolveInteraction
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.module.LlmClient
import com.eloykim.lyra.amp.protocol.module.LlmFinishReason
import com.eloykim.lyra.amp.protocol.module.LlmMessage
import com.eloykim.lyra.amp.protocol.module.LlmRequest
import com.eloykim.lyra.amp.protocol.module.LlmResponse
import com.eloykim.lyra.amp.protocol.module.LlmRole
import com.eloykim.lyra.amp.protocol.module.LlmToolCall
import com.eloykim.lyra.amp.protocol.module.LlmToolDefinition
import com.eloykim.lyra.amp.protocol.module.LlmUsage
import com.eloykim.lyra.amp.protocol.module.ToolDecision
import com.eloykim.lyra.amp.protocol.module.ToolDescriptor as Descriptor
import com.eloykim.lyra.amp.protocol.module.ToolExecutor
import com.eloykim.lyra.amp.protocol.module.ToolInvocation
import com.eloykim.lyra.amp.protocol.module.ToolPolicy
import com.eloykim.lyra.amp.protocol.module.ToolRegistry
import com.eloykim.lyra.amp.protocol.module.ToolResult
import com.eloykim.lyra.amp.protocol.module.ToolRisk
import com.eloykim.lyra.amp.protocol.module.redactSecrets
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind
import com.eloykim.lyra.amp.protocol.scenario.ScenarioMix
import com.eloykim.lyra.amp.protocol.scenario.ScenarioPlan
import com.eloykim.lyra.amp.protocol.scenario.resolveScenario
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `src/runtime/cognitiveRuntime.ts` 的移植：评分、计划、上下文、模型与 receipt
 * 之间缺失的那个循环。
 *
 * 循环只依赖端口与纯规划器：
 * `scoreUser -> scenario/interaction -> attention -> context -> LLM
 *  -> safety -> scoreAnswer -> receipt`。
 *
 * 没有 SQLite，也没有数据库导入；用 ScriptedLlm 可离线测试，用真实 LlmClient
 * 可跑一轮线上对话。
 */

enum class ReceiptOutcome(val wire: String) {
    SUCCESS("success"),
    BLOCKED("blocked"),
    FAILURE("failure"),
}

/** 运行时自己的 receipt：与协议层的 `receipt/Receipt.kt` 不同，它只记录一次调用。 */
data class RuntimeReceipt(
    val id: String,
    val at: String,
    val usage: LlmUsage,
    val outcome: ReceiptOutcome,
    val note: String,
)

fun interface ReceiptSink {
    fun write(receipt: RuntimeReceipt)
}

class MemoryReceiptSink : ReceiptSink {

    val receipts: MutableList<RuntimeReceipt> = ArrayList()

    override fun write(receipt: RuntimeReceipt) {
        receipts.add(receipt)
    }
}

/** 一次工具调用的完整留痕；`redacted` 说明内容是否被 [redactSecrets] 改过。 */
data class RuntimeToolCall(
    val id: String,
    val actionId: String? = null,
    val operationId: String? = null,
    val name: String,
    val arguments: String,
    val ok: Boolean,
    val content: String,
    val error: String? = null,
    val risk: ToolRisk? = null,
    val decision: String? = null,
    val redacted: Boolean = false,
)

private data class ToolExecution(
    val result: ToolResult,
    val risk: ToolRisk? = null,
    val decision: String? = null,
)

/**
 * 一轮的完整结果。
 *
 * 可选字段与参考实现的可选字段一一对应；`toolErrors` 在参考实现里可选，这里缺省 0
 * （会话统计直接使用它）。
 */
data class RuntimeTurn(
    val turnId: String,
    val sessionId: String? = null,
    val scope: String? = null,
    val pid: Int,
    val input: String,
    val at: String,
    val user: UserScore,
    val scenario: ScenarioPlan,
    val interaction: InteractionPlan,
    val attention: AttentionPlan,
    val context: AgentContext? = null,
    val answer: String,
    val answerScore: AnswerScore? = null,
    val usage: LlmUsage? = null,
    val finishReason: LlmFinishReason? = null,
    val toolCalls: List<RuntimeToolCall>? = null,
    val restSuggested: Boolean = false,
    val latencyMs: Long? = null,
    val toolErrors: Int = 0,
    val telemetry: TelemetrySample? = null,
    val escalationId: String? = null,
    val behavior: BehaviorPlan? = null,
    val actions: List<ActionRecord>? = null,
    val operations: List<OperationRecord>? = null,
    val approvals: List<ApprovalRecord>? = null,
    val diagnosis: DiagnosticFrame? = null,
    val safety: SafetyDecision,
    val receiptId: String,
    val trace: List<String>,
)

/** 本轮覆盖项；`scope`/`systemNote` 是治理适配器接入的两个钩子。 */
data class TurnOverride(
    val scenarioMix: ScenarioMix? = null,
    val interactionMix: InteractionMix? = null,
    val attentionState: CognitiveState? = null,
    val useContext: Boolean? = null,
    val correct: Boolean? = null,
    val safe: Boolean? = null,
    val helpful: Boolean? = null,
    /** 显式的先验 scope；否则由 session + 主 scenario 推导。 */
    val scope: String? = null,
    /**
     * 可选的治理说明（manifest ref、证据下限、约束）。它只被追加到系统提示，
     * 从不携带上下文正文。
     */
    val systemNote: String? = null,
)

// ---------------------------------------------------------------------------
// options
// ---------------------------------------------------------------------------

data class CognitiveRuntimeOptions(
    val pid: Int,
    val agent: AgentReader,
    val social: SocialReader,
    val llm: LlmClient,
    val bayes: BayesianEngine? = null,
    val budgetTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val model: String? = null,
    val scenarioMix: ScenarioMix? = null,
    val interactionMix: InteractionMix? = null,
    val attentionState: CognitiveState? = null,
    val receiptSink: ReceiptSink? = null,
    val now: (() -> String)? = null,
    val useContext: Boolean = true,
    /** 需显式开启：把 AgentContext 渲染成 AST 摘要视图。 */
    val contextAst: Boolean = false,
    val session: RuntimeSession? = null,
    val sessionStore: SessionStore? = null,
    val turnStore: TurnStore? = null,
    val telemetry: TelemetrySource? = null,
    val escalationSink: EscalationSink? = null,
    val behaviors: BehaviorRegistry? = null,
    /** 需显式开启：给 engineering 轮次附加一个只读诊断帧。 */
    val diagnosis: Boolean = false,
    /** 需显式开启：每轮之后应用有界的优先级建议。默认 false。 */
    val autoTuneBehavior: Boolean = false,
    val actionPlanner: ActionPlanner? = null,
    /** 截断的答案最多续写多少次；默认 1。 */
    val maxContinuations: Int? = null,
    val tools: ToolRegistry? = null,
    val toolExecutor: ToolExecutor? = null,
    val toolPolicy: ToolPolicy? = null,
    /** 每轮最大工具轮数；默认 4。 */
    val maxToolRounds: Int? = null,
    /** 序列化后工具消息的最大字符数；默认 1 MiB。 */
    val maxToolResultChars: Int? = null,
)

private const val DefaultModel: String = "deepseek-flash"

private const val MaxToolResultLimit: Int = 1024 * 1024

private data class SystemPromptInput(
    val context: AgentContext?,
    val scenario: ScenarioPlan,
    val interaction: InteractionPlan,
    val attention: AttentionPlan,
    val behavior: BehaviorPlan,
    val useContext: Boolean,
    val diagnosisText: String?,
    val contextAstText: String?,
    val systemNote: String? = null,
)

private val StableSystemPrefix: String =
    "You are an AI agent modeled as a person. Do not claim human feelings, a diagnosis, or a therapeutic relationship."

private val IsoTimestamp: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

private fun isoNow(): String = IsoTimestamp.format(Instant.now())

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun fixed6(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

/** 参考实现的 `mergeUsage`：逐项相加，可选字段按 0 起算。 */
private fun mergeUsage(left: LlmUsage, right: LlmUsage): LlmUsage = LlmUsage(
    inputTokens = left.inputTokens + right.inputTokens,
    outputTokens = left.outputTokens + right.outputTokens,
    cacheHitTokens = (left.cacheHitTokens ?: 0) + (right.cacheHitTokens ?: 0),
    cacheMissTokens = (left.cacheMissTokens ?: 0) + (right.cacheMissTokens ?: 0),
    costUsd = (left.costUsd ?: 0.0) + (right.costUsd ?: 0.0),
)

private fun defaultCognitiveState(): CognitiveState = CognitiveState(
    load = 0.3,
    vigilance = 0.8,
    arousal = 0.2,
    mastery = 0.6,
    accountability = 0.5,
    cyclesSinceRest = 0,
    capacityEff = 4,
    pLapse = 0.2,
    source = CognitiveSource.DEFAULT,
    samples = 0,
)

private fun systemPrompt(input: SystemPromptInput): String {
    val contextText: String = when {
        !input.contextAstText.isNullOrEmpty() -> "Relevant self-context (AST):\n${input.contextAstText}"
        input.useContext && input.context != null && input.context.segments.isNotEmpty() ->
            "Relevant self-context:\n" +
                input.context.segments.joinToString("\n") { "[${it.kind.wire}] ${it.text}" }
        else -> ""
    }
    val lines = ArrayList<String>()
    lines.add(StableSystemPrefix)
    lines.add("Scenario: ${input.scenario.rationale}")
    lines.add("Interaction: ${input.interaction.rationale}")
    lines.add(
        "Attention: ${input.attention.process.effectiveSlots} slots; " +
            "foreground ${input.attention.foreground?.slots ?: 0}; " +
            "reserve ${input.attention.process.reserveSlots}; rest=${input.attention.rest}.",
    )
    input.systemNote?.takeIf { it.isNotEmpty() }?.let { lines.add(it) }
    if (contextText.isNotEmpty()) {
        lines.add(contextText)
    }
    input.diagnosisText?.takeIf { it.isNotEmpty() }?.let { lines.add("Diagnostic context (summaries only):\n$it") }
    lines.addAll(input.interaction.guardrails)
    lines.addAll(input.behavior.guardrails)
    lines.add(
        "Answer the user's request directly. Mark uncertainty where it exists. " +
            "Do not manipulate or induce dependency.",
    )
    return lines.joinToString("\n")
}

/** `JSON.stringify` 等价物：保留插入顺序，紧凑输出。 */
private fun encodeJson(value: JsonValue): String = buildString { writeJson(this, value) }

private fun writeJson(out: StringBuilder, value: JsonValue) {
    when (value) {
        is JsonValue.Obj -> {
            out.append('{')
            var first = true
            for ((key, item) in value.fields) {
                if (!first) {
                    out.append(',')
                }
                first = false
                writeJsonString(out, key)
                out.append(':')
                writeJson(out, item)
            }
            out.append('}')
        }
        is JsonValue.Arr -> {
            out.append('[')
            value.items.forEachIndexed { index, item ->
                if (index > 0) {
                    out.append(',')
                }
                writeJson(out, item)
            }
            out.append(']')
        }
        is JsonValue.Str -> writeJsonString(out, value.value)
        is JsonValue.Num -> out.append(CanonicalJson.formatNumber(value.value))
        is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
        JsonValue.Null -> out.append("null")
    }
}

private fun writeJsonString(out: StringBuilder, text: String) {
    out.append('"')
    for (ch in text) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (ch < ' ') {
                out.append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
            } else {
                out.append(ch)
            }
        }
    }
    out.append('"')
}

private fun usageJson(usage: LlmUsage): JsonValue.Obj = jsonObj(
    "inputTokens" to jsonNumber(usage.inputTokens),
    "outputTokens" to jsonNumber(usage.outputTokens),
    "cacheHitTokens" to usage.cacheHitTokens?.let { jsonNumber(it) },
    "cacheMissTokens" to usage.cacheMissTokens?.let { jsonNumber(it) },
    "costUsd" to usage.costUsd?.let { jsonNumber(it) },
)

/** 诊断投影：包一层是为了把 `diagnosis` 包的同名函数与本文件的窄依赖隔开。 */
private fun toDiagnosisContextAst(frame: DiagnosticFrame, budgetTokens: Int): DiagnosisAstContext =
    diagnosisToContextAst(frame, budgetTokens)

private fun toDiagnosisActionPlan(frame: DiagnosticFrame): DiagnosisActionPlan? = diagnosisToActionPlan(frame)

/**
 * 把诊断层的动作草案转成运行时的动作草案。
 *
 * 参考实现里二者是同一个 `ActionPlan`；两个移植包各自定义了同名类型，且诊断侧把
 * `risk` 保留为字符串（为了对未知效果 fail-closed），因此在这里显式转换：未知风险
 * 记为 null，由运行时按缺省 `read` 处理。
 */
private fun toRuntimeActionPlan(plan: DiagnosisActionPlan): ActionPlan = ActionPlan(
    goal = plan.goal,
    intent = plan.intent,
    expectedEffect = plan.expectedEffect,
    preconditions = plan.preconditions,
    successCriteria = plan.successCriteria,
    risk = ActionRisk.entries.firstOrNull { it.wire == plan.risk },
)

/**
 * 认知运行时。
 *
 * 构造时解析会话：显式 [CognitiveRuntimeOptions.session] 优先，其次是
 * sessionStore 里持久化的快照，最后（只有提供了 store 时）新建一个
 * `session.<pid>`，与参考实现一致。
 */
class CognitiveRuntime(options: CognitiveRuntimeOptions) {

    private val options: CognitiveRuntimeOptions = options
    private val bayes: BayesianEngine
    private val sink: ReceiptSink
    private val now: () -> String
    private val baseState: CognitiveState
    private val behaviors: BehaviorRegistry
    private val behaviorTuning: BehaviorTuningController
    private val actionPlanner: ActionPlanner
    private val session: RuntimeSession?
    private val sessionStore: SessionStore?
    private val turnStore: TurnStore?
    private val maxToolResultChars: Int
    private var counter: Int = 0
    private var turnCounter: Int = 0

    init {
        val resultLimit = options.maxToolResultChars ?: MaxToolResultLimit
        if (resultLimit < 128 || resultLimit > MaxToolResultLimit) {
            throw IllegalArgumentException("maxToolResultChars must be an integer in 128..1048576")
        }
        maxToolResultChars = resultLimit
        sessionStore = options.sessionStore
        turnStore = options.turnStore
        val stored = options.sessionStore?.load(options.pid)
        session = options.session ?: when {
            stored != null -> RuntimeSession.restore(stored)
            options.sessionStore != null -> RuntimeSession(RuntimeSessionOptions(id = "session.${options.pid}"))
            else -> null
        }
        bayes = options.bayes ?: session?.bayes ?: BayesianEngine()
        sink = options.receiptSink ?: MemoryReceiptSink()
        now = options.now ?: { isoNow() }
        baseState = options.attentionState ?: session?.state?.toCognitiveState() ?: defaultCognitiveState()
        behaviors = options.behaviors ?: BehaviorRegistry()
        behaviorTuning = BehaviorTuningController(behaviors)
        actionPlanner = options.actionPlanner ?: SingleActionPlanner()
    }

    /** 跑完一轮并返回完整轨迹。 */
    fun respond(input: String, override: TurnOverride = TurnOverride()): RuntimeTurn {
        val at = now()
        val startedAt = System.nanoTime()
        val telemetry = options.telemetry?.sample()
        val turnId = session?.nextTurnId() ?: "rt.${++turnCounter}"
        val trace = ArrayList<String>()
        val sessionScope = session?.scope
        val userEngine = if (sessionScope == null) bayes else session?.engineFor(sessionScope) ?: bayes
        val user = userEngine.scoreUser(input)
        val scenario = resolveScenario(
            override.scenarioMix ?: options.scenarioMix ?: inferScenarioMix(input),
        )
        val scope = override.scope
            ?: session?.let { "${it.scope}:${scenario.active.firstOrNull()?.wire ?: "mixed"}" }
        val engine = if (scope == null || session == null) bayes else session.engineFor(scope)
        if (scope != null) {
            trace.add("scope=$scope")
        }
        val interaction = resolveInteraction(
            override.interactionMix ?: options.interactionMix ?: inferInteractionMix(user),
        )
        val state = override.attentionState ?: session?.state?.toCognitiveState() ?: baseState
        val attention = planAttention(
            processAttention(options.pid, state),
            listOf(
                ThreadAttentionCandidate(
                    tid = 1,
                    pid = options.pid,
                    priority = 1,
                    preferredSlots = 2,
                    foreground = true,
                    role = interaction.primary.wire,
                ),
            ),
        )
        trace.add(
            "user quality=${fixed2(user.quality)} " +
                "emotion=${fixed2(user.state.emotionalIntensity)} " +
                "urgency=${fixed2(user.state.urgency)}",
        )
        trace.add("scenario=${scenario.rationale}")
        trace.add("interaction=${interaction.rationale}")
        trace.add(
            "attention utilization=${fixed2(attention.utilization)} " +
                "pressure=${fixed2(attention.pressure)} rest=${attention.rest}",
        )
        val behavior = behaviors.select(
            BehaviorContext(
                turnId = turnId,
                text = input,
                user = user,
                scenario = scenario,
                interaction = interaction,
                attention = attention,
            ),
        )
        if (behavior.activations.isNotEmpty()) {
            trace.add(
                "behaviors=${behavior.activations.joinToString(",") { "${it.behaviorId}@${fixed2(it.confidence)}" }}",
            )
        }

        val diagnosis = if (options.diagnosis && scenario.active.contains(ScenarioKind.ENGINEERING)) {
            buildDiagnosisFrame(IncidentInput(id = turnId, summary = input, symptoms = emptyList()))
        } else {
            null
        }
        val diagnosisContext = diagnosis?.let { toDiagnosisContextAst(it, 800) }
        if (diagnosis != null) {
            trace.add("diagnosis=${diagnosis.id} scope=${diagnosisContext?.top?.hypothesis?.layer?.wire ?: "open"}")
        }
        val diagnosisAction = diagnosis?.let { toDiagnosisActionPlan(it) }?.let { toRuntimeActionPlan(it) }
        val planned = diagnosisAction?.let { listOf(it) }
            ?: actionPlanner.plan(
                ActionPlannerInput(
                    turnId = turnId,
                    text = input,
                    behavior = behavior,
                    scenario = scenario,
                    interaction = interaction,
                ),
            )
        val actions = planned.take(behavior.maxActionsPerTurn).mapIndexed { index, action ->
            ActionRecord(
                id = "act.$turnId.${index + 1}",
                turnId = turnId,
                goal = action.goal,
                intent = action.intent,
                expectedEffect = action.expectedEffect,
                preconditions = action.preconditions,
                successCriteria = action.successCriteria,
                risk = action.risk ?: ActionRisk.READ,
                status = ActionStatus.PLANNED,
                operationIds = ArrayList(),
            )
        }
        if (actions.isNotEmpty()) {
            trace.add("actions=${actions.joinToString(",") { it.intent }}")
        }
        val actionId = actions.firstOrNull()?.id
        val operations = ArrayList<OperationRecord>()
        val approvals = ArrayList<ApprovalRecord>()
        val restSuggested = session?.shouldRest() ?: attention.rest
        if (restSuggested) {
            trace.add("rest suggested by attention/session policy")
        }

        val preSafety = checkSafety(input, interaction)
        if (preSafety.action == SafetyAction.ESCALATE) {
            val answer = safetyFallback(SafetyAction.ESCALATE)
            val runtimeReceipt = receipt(at, LlmUsage(0, 0), ReceiptOutcome.BLOCKED, "pre-safety escalation")
            val escalationId = options.escalationSink?.escalate(
                EscalationRequest(
                    turnId = turnId,
                    at = at,
                    reason = preSafety.reasons.joinToString("; "),
                    resources = CrisisResources,
                ),
            )
            trace.add(
                "safety=escalate; no LLM call" + (escalationId?.let { "; escalation=$it" } ?: ""),
            )
            for (action in actions) {
                action.status = ActionStatus.ABANDONED
            }
            val turn = RuntimeTurn(
                turnId = turnId,
                sessionId = session?.id,
                scope = scope,
                pid = options.pid,
                input = input,
                at = at,
                user = user,
                scenario = scenario,
                interaction = interaction,
                attention = attention,
                answer = answer,
                restSuggested = restSuggested,
                latencyMs = elapsedMs(startedAt),
                telemetry = telemetry,
                escalationId = escalationId,
                behavior = behavior,
                actions = actions,
                operations = operations,
                approvals = approvals,
                safety = preSafety,
                receiptId = runtimeReceipt.id,
                trace = trace,
            )
            turnStore?.save(turn)
            return turn
        }
        for (action in actions) {
            action.status = ActionStatus.RUNNING
        }

        val useContext = override.useContext ?: options.useContext
        val context = assembleAgentContext(
            options.agent,
            options.social,
            options.pid.toString(),
            Budget(tokens = options.budgetTokens ?: 600),
            AgentContextOptions(),
        )
        trace.add("context tokens=${context.tokens} mode=${scenario.mode.wire}")
        val contextAstView = if (options.contextAst && useContext) {
            agentContextToAst(context).ast.view(800)
        } else {
            null
        }
        if (contextAstView != null) {
            trace.add("context ast tokens=${contextAstView.tokens} nodes=${contextAstView.nodes.size}")
        }
        val tools: List<LlmToolDefinition>? = options.tools?.list()?.map {
            LlmToolDefinition(name = it.name, description = it.description, inputSchema = it.inputSchema)
        }
        val messages = ArrayList<LlmMessage>()
        messages.add(
            LlmMessage(
                role = LlmRole.SYSTEM,
                content = systemPrompt(
                    SystemPromptInput(
                        context = context,
                        scenario = scenario,
                        interaction = interaction,
                        attention = attention,
                        behavior = behavior,
                        useContext = useContext,
                        diagnosisText = diagnosisContext?.view?.text,
                        contextAstText = contextAstView?.text,
                        systemNote = override.systemNote,
                    ),
                ),
            ),
        )
        messages.add(LlmMessage(role = LlmRole.USER, content = input))
        val baseRequest = LlmRequest(
            model = options.model ?: DefaultModel,
            messages = messages,
            maxTokens = options.maxOutputTokens ?: 512,
            temperature = 0.0,
            tools = tools,
        )
        var response: LlmResponse = options.llm.complete(baseRequest)
        var usage: LlmUsage = response.usage
        val toolCalls = ArrayList<RuntimeToolCall>()
        val maxToolRounds = maxOf(0, options.maxToolRounds ?: 4)
        var toolRounds = 0
        while (!response.toolCalls.isNullOrEmpty() && toolRounds < maxToolRounds) {
            toolRounds += 1
            messages.add(
                LlmMessage(
                    role = LlmRole.ASSISTANT,
                    content = response.text,
                    toolCalls = response.toolCalls,
                ),
            )
            val toolCallList = response.toolCalls ?: emptyList()
            for (call in toolCallList) {
                val execution = runTool(call)
                val content = redactSecrets(execution.result.content)
                val error = execution.result.error?.let { redactSecrets(it) }
                var safeResult = ToolResult(
                    ok = execution.result.ok,
                    content = content.text,
                    error = error?.text,
                )
                var toolMessage = encodeJson(
                    jsonObj(
                        "ok" to JsonValue.Bool(safeResult.ok),
                        "content" to JsonValue.Str(safeResult.content),
                        "error" to safeResult.error?.let { JsonValue.Str(it) },
                    ),
                )
                if (toolMessage.length > maxToolResultChars) {
                    safeResult = ToolResult(
                        ok = false,
                        content = "",
                        error = "tool_result_too_large: request a smaller page or view",
                    )
                    toolMessage = encodeJson(
                        jsonObj(
                            "ok" to JsonValue.Bool(false),
                            "content" to JsonValue.Str(""),
                            "error" to JsonValue.Str(safeResult.error ?: ""),
                        ),
                    )
                }
                val risk = execution.risk?.let { SideEffect.fromWire(it.wire) } ?: SideEffect.READ
                val operation = OperationRecord(
                    id = "op.$turnId.tool.${operations.size + 1}",
                    actionId = actionId ?: "act.none",
                    kind = OperationKind.TOOL_CALL,
                    input = jsonObj("arguments" to JsonValue.Str(call.arguments)),
                    executor = call.name,
                    sideEffect = risk,
                    idempotencyKey = call.id,
                    status = if (safeResult.ok) OperationStatus.OK else OperationStatus.ERROR,
                    result = jsonObj(
                        "content" to JsonValue.Str(safeResult.content.take(4000)),
                        "error" to safeResult.error?.let { JsonValue.Str(it) },
                    ),
                )
                operations.add(operation)
                if (risk == SideEffect.DESTRUCTIVE || (execution.decision != null && execution.decision != "allowed")) {
                    approvals.add(
                        ApprovalRecord(
                            operationId = operation.id,
                            tool = call.name,
                            risk = risk,
                            decision = execution.decision ?: "not_required",
                            status = if (safeResult.ok) ApprovalStatus.ALLOWED else ApprovalStatus.DENIED,
                        ),
                    )
                }
                toolCalls.add(
                    RuntimeToolCall(
                        id = call.id,
                        actionId = actionId,
                        operationId = operation.id,
                        name = call.name,
                        arguments = call.arguments,
                        ok = safeResult.ok,
                        content = safeResult.content.take(4000),
                        error = safeResult.error,
                        risk = execution.risk,
                        decision = execution.decision,
                        redacted = content.redacted || (error?.redacted ?: false),
                    ),
                )
                if (actionId != null) {
                    actions.first().operationIds.add(operation.id)
                }
                messages.add(
                    LlmMessage(
                        role = LlmRole.TOOL,
                        content = toolMessage,
                        toolCallId = call.id,
                        name = call.name,
                    ),
                )
            }
            response = options.llm.complete(baseRequest.copy(messages = ArrayList(messages)))
            usage = mergeUsage(usage, response.usage)
        }
        if (toolCalls.isNotEmpty()) {
            trace.add("tools=${toolCalls.joinToString(",") { "${it.name}:${if (it.ok) "ok" else "error"}" }}")
        }

        var text = response.text
        var finishReason = response.finishReason
        var continuations = 0
        val maxContinuations = maxOf(0, options.maxContinuations ?: 1)
        var continuationMessages: List<LlmMessage> = messages
        while (finishReason == LlmFinishReason.LENGTH && continuations < maxContinuations) {
            val next = options.llm.complete(
                baseRequest.copy(
                    messages = continuationMessages +
                        listOf(
                            LlmMessage(role = LlmRole.ASSISTANT, content = text),
                            LlmMessage(
                                role = LlmRole.USER,
                                content = "Continue exactly where you stopped. Do not repeat.",
                            ),
                        ),
                ),
            )
            if (next.text.isEmpty()) {
                finishReason = next.finishReason
                break
            }
            text += next.text
            usage = mergeUsage(usage, next.usage)
            finishReason = next.finishReason
            continuations += 1
            continuationMessages = continuationMessages +
                listOf(
                    LlmMessage(role = LlmRole.ASSISTANT, content = text),
                    LlmMessage(role = LlmRole.USER, content = "Continue exactly where you stopped. Do not repeat."),
                )
        }
        trace.add(
            "llm input=${usage.inputTokens} output=${usage.outputTokens} " +
                "cacheHit=${usage.cacheHitTokens ?: 0} cacheMiss=${usage.cacheMissTokens ?: 0} " +
                "cost=\$${fixed6(usage.costUsd ?: 0.0)} finish=${finishReason?.wire} " +
                "toolRounds=$toolRounds continuations=$continuations",
        )
        val llmOperation = OperationRecord(
            id = "op.$turnId.llm",
            actionId = actionId ?: "act.none",
            kind = OperationKind.LLM_COMPLETE,
            input = jsonObj(
                "model" to JsonValue.Str(baseRequest.model),
                "messages" to jsonNumber(baseRequest.messages.size),
                "maxTokens" to baseRequest.maxTokens?.let { jsonNumber(it) },
            ),
            executor = "llm",
            sideEffect = SideEffect.NONE,
            status = OperationStatus.OK,
            result = jsonObj(
                "finishReason" to finishReason?.let { JsonValue.Str(it.wire) },
                "toolRounds" to jsonNumber(toolRounds),
                "continuations" to jsonNumber(continuations),
                "usage" to usageJson(usage),
            ),
        )
        operations.add(llmOperation)
        if (actionId != null) {
            actions.first().operationIds.add(llmOperation.id)
        }
        trace.add("operations=${operations.joinToString(",") { "${it.kind.wire}:${it.status.wire}" }}")

        val postSafety = checkSafety(input, interaction, text)
        val answer = if (postSafety.action == SafetyAction.ALLOW) text else safetyFallback(postSafety.action)
        val answerScore = engine.scoreAnswer(
            answer,
            AnswerMeta(
                correct = override.correct,
                safe = override.safe,
                helpful = override.helpful,
                targetTokens = (options.maxOutputTokens ?: 512) * (1 + continuations),
            ),
        )
        trace.add(
            "answer overall=${fixed2(answerScore.overall)} " +
                "correctness=${fixed2(answerScore.dimensions.getValue(Dimension.CORRECTNESS).mean)}",
        )
        val latencyMs = elapsedMs(startedAt)
        val toolErrors = toolCalls.count { !it.ok }
        for (action in actions) {
            action.status = ActionStatus.DONE
        }

        if (session != null) {
            session.record(
                SessionTurnRecord(
                    turnId = turnId,
                    at = at,
                    scope = scope,
                    user = user,
                    answerScore = answerScore,
                    attention = attention,
                    latencyMs = latencyMs,
                    tokens = usage.inputTokens + usage.outputTokens,
                    toolErrors = toolErrors,
                    telemetry = telemetry,
                    behavior = behavior,
                    actions = actions,
                    operations = operations,
                    approvals = approvals,
                    diagnosis = diagnosis,
                ),
            )
            sessionStore?.save(options.pid, session.serialize(), at)
            if (options.autoTuneBehavior) {
                val tuned = behaviorTuning.autoApply(session.behaviorConflicts, AutoTuneOptions(enabled = true))
                if (tuned.applied > 0) {
                    trace.add("behavior tuning v${tuned.version}: ${tuned.applied} priorities")
                }
            }
            trace.add(
                "session turns=${session.history.size} recoveryDebt=${fixed2(session.state.recoveryDebt)}",
            )
        }
        val outcome = if (postSafety.action == SafetyAction.ALLOW) ReceiptOutcome.SUCCESS else ReceiptOutcome.BLOCKED
        val note = when {
            postSafety.action != SafetyAction.ALLOW -> "post-safety ${postSafety.action.wire}"
            finishReason == LlmFinishReason.LENGTH -> "truncated answer (length)"
            continuations > 0 -> "runtime turn (continued ${continuations}x)"
            else -> "runtime turn"
        }
        val runtimeReceipt = receipt(at, usage, outcome, note)
        val turn = RuntimeTurn(
            turnId = turnId,
            sessionId = session?.id,
            scope = scope,
            pid = options.pid,
            input = input,
            at = at,
            user = user,
            scenario = scenario,
            interaction = interaction,
            attention = attention,
            context = context,
            answer = answer,
            answerScore = answerScore,
            usage = usage,
            finishReason = finishReason,
            toolCalls = toolCalls.takeIf { it.isNotEmpty() },
            restSuggested = restSuggested,
            latencyMs = latencyMs,
            toolErrors = toolErrors,
            telemetry = telemetry,
            behavior = behavior,
            actions = actions.takeIf { it.isNotEmpty() },
            operations = operations.takeIf { it.isNotEmpty() },
            approvals = approvals.takeIf { it.isNotEmpty() },
            diagnosis = diagnosis,
            safety = postSafety,
            receiptId = runtimeReceipt.id,
            trace = trace,
        )
        turnStore?.save(turn)
        return turn
    }

    /** 保留持久化的会话状态，但标记为已归档。 */
    fun archiveSession(at: String): Boolean = sessionStore?.archive(options.pid, at) ?: false

    /** 智能体被删除/归档时移除持久化状态。 */
    fun deleteSession(): Boolean = sessionStore?.delete(options.pid) ?: false

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    private fun runTool(call: LlmToolCall): ToolExecution {
        val executor = options.toolExecutor
            ?: return ToolExecution(ToolResult(false, "", "no tool executor configured"))
        val args: JsonValue.Obj = try {
            JsonParser.parse(call.arguments) as? JsonValue.Obj
                ?: return ToolExecution(
                    ToolResult(false, "", "invalid tool arguments: expected a JSON object"),
                )
        } catch (error: Exception) {
            return ToolExecution(
                ToolResult(false, "", "invalid tool arguments: ${error.message ?: error.toString()}"),
            )
        }
        val descriptor: Descriptor = options.tools?.get(call.name)
            ?: return ToolExecution(ToolResult(false, "", "unknown tool ${call.name}"))
        var decision = "allowed"
        val policy = options.toolPolicy
        if (policy != null) {
            val verdict: ToolDecision = policy.check(ToolInvocation(call.name, args), descriptor)
            decision = if (verdict.allowed) "allowed" else (verdict.reason ?: "tool ${call.name} denied")
            if (!verdict.allowed) {
                return ToolExecution(
                    result = ToolResult(false, "", decision),
                    risk = descriptor.risk,
                    decision = decision,
                )
            }
        }
        val result: ToolResult = executor.execute(ToolInvocation(call.name, args))
        return ToolExecution(result, descriptor.risk, decision)
    }

    private fun receipt(at: String, usage: LlmUsage, outcome: ReceiptOutcome, note: String): RuntimeReceipt {
        counter += 1
        val receipt = RuntimeReceipt("rc.runtime.$counter", at, usage, outcome, note)
        sink.write(receipt)
        return receipt
    }
}
