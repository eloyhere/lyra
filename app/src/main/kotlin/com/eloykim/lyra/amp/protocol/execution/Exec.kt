package com.eloykim.lyra.amp.protocol.execution

import com.eloykim.lyra.amp.protocol.attention.BudgetExceeded
import com.eloykim.lyra.amp.protocol.attention.CallCost
import com.eloykim.lyra.amp.protocol.attention.Dispatcher
import com.eloykim.lyra.amp.protocol.boundary.BoundaryRejection
import com.eloykim.lyra.amp.protocol.boundary.ToolSchema
import com.eloykim.lyra.amp.protocol.boundary.parseToolCall
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.module.McpClient
import com.eloykim.lyra.amp.protocol.module.McpTool
import com.eloykim.lyra.amp.protocol.module.textOf
import com.eloykim.lyra.amp.protocol.policy.ActionRequest
import com.eloykim.lyra.amp.protocol.policy.ApproveInput
import com.eloykim.lyra.amp.protocol.policy.Approval
import com.eloykim.lyra.amp.protocol.policy.DefaultApprovalEffects
import com.eloykim.lyra.amp.protocol.policy.EffectClass
import com.eloykim.lyra.amp.protocol.policy.Guard
import com.eloykim.lyra.amp.protocol.policy.PolicyEngine
import com.eloykim.lyra.amp.protocol.policy.PolicyOptions
import com.eloykim.lyra.amp.protocol.policy.PolicyOutcome
import com.eloykim.lyra.amp.protocol.policy.PolicyVerdict
import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.receipt.ReceiptOp
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Handle
import com.eloykim.lyra.amp.protocol.types.HandleKind
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.warrant.Claim
import java.time.Instant

/**
 * 执行配置（`src/execution/exec.ts` 的忠实移植）。
 *
 * MCP 工具走与其它动作相同的预算、塑形与 receipt 规则。Digest pinning 让审批签发
 * 之后的元数据变化成为一个安全事件，而不是惊喜（Deadbugz / MCPoison）。
 *
 * 参考实现是 async 的；Kotlin 侧 [McpClient] 是阻塞式，所以 [ExecProfile] 的
 * `connect`/`refresh`/`call` 都是同步函数。宿主必须把它们放到主线程之外执行。
 */

/** 工具目录/参数在审批之后被改动：这是安全事件，不是普通错误。 */
class DriftDetected(
    message: String,
    val receipt: Receipt? = null,
) : IllegalStateException(message) {

    val code: String = "security_event"
}

/** 高风险工具缺少宿主审批。 */
class ApprovalRequired(
    val tool: String,
    val receipt: Receipt? = null,
    val reason: String = "action requires explicit host approval",
) : IllegalStateException("tool $tool is not read-only and requires explicit approval: $reason") {

    val code: String = "approval_required"
}

/** 策略/边界/护栏拒绝。 */
class PolicyDenied(
    message: String,
    val receipt: Receipt? = null,
) : IllegalStateException(message) {

    val code: String = "denied"
}

/** [ExecProfile] 的接线。 */
data class ExecOptions(
    val budget: Budget,
    val handles: HandleStore,
    val now: () -> Timestamp,
    /** 超过这个 token 数的结果变成 handle。绝不截断。 */
    val maxResultTokens: Int = 1200,
    /** 为 true 时非只读工具需要审批；为 false 时默认策略不要求任何审批。 */
    val requireApproval: Boolean? = null,
    val onReceipt: ((Receipt) -> Unit)? = null,
    /**
     * 宿主策略引擎。省略时创建默认引擎：read 不需要 warrant，其它效果需要在规范
     * 层级上的 held claim，高风险效果需要宿主审批。
     */
    val policy: PolicyEngine? = null,
    /** 可选的限流/熔断/金额护栏，在派发前应用。 */
    val guard: Guard? = null,
    /**
     * 每次调用前重新列目录并比较逐工具摘要。默认 true，让 TTL 窗口内的变化也
     * fail closed。
     */
    val verifyPinsAtCall: Boolean = true,
    /**
     * 在策略与派发之前按工具的 `inputSchema` 校验参数。默认 true：schema 拒绝的
     * 参数集永远到不了服务器，且拒绝会记成 blocked receipt。
     */
    val validateArgs: Boolean = true,
)

/** 一次连接得到的目录快照。 */
data class CatalogSnapshot(
    val tools: List<McpTool>,
    /** 被钉扎工具集的顺序无关摘要。 */
    val digest: String,
    /** 调用时检查用的逐工具钉扎。 */
    val pins: ToolPins,
    val ttlMs: Long,
    val expiresAt: Long,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "tools" to JsonValue.Arr(tools.map { it.toJson() }),
        "digest" to jsonText(digest),
        "pins" to pins.toJson(),
        "ttlMs" to jsonNumber(ttlMs),
        "expiresAt" to jsonNumber(expiresAt),
    )

    companion object {
        fun fromJson(value: JsonValue): CatalogSnapshot {
            val obj = value.asObject("catalog snapshot")
            return CatalogSnapshot(
                tools = obj.requireArr("tools").items.map { McpTool.fromJson(it) },
                digest = obj.requireString("digest"),
                pins = ToolPins.fromJson(obj.requireObj("pins")),
                ttlMs = obj.long("ttlMs") ?: throw IllegalArgumentException("缺少整数字段 ttlMs"),
                expiresAt = obj.long("expiresAt") ?: throw IllegalArgumentException("缺少整数字段 expiresAt"),
            )
        }
    }
}

/** 单次工具调用的选项。 */
data class CallOptions(
    /** 宿主签发的审批，绑定 action/tool/args；绝不是模型给的布尔值。 */
    val approval: Approval? = null,
    /** 以审批 id 引用的同一能力（参考实现 `Approval | string` 的 string 分支）。 */
    val approvalId: Id? = null,
    /** 动作的 warrant。除 read 外都需要。 */
    val claim: Claim? = null,
    /** 覆盖按工具注解推断的效果类别。 */
    val effect: EffectClass? = null,
    /** 可选金额，按护栏上限检查。 */
    val money: Double? = null,
    /** 已废弃、被忽略；请使用 [approval]。保留仅为源码兼容。 */
    @Deprecated("use approval")
    val approved: Boolean? = null,
    val budgetTokens: Int? = null,
    val predictedVoi: Double? = null,
    val realizedVoi: Double? = null,
)

/** 一次调用的结果。 */
data class CallOutcome(
    val receipt: Receipt,
    val inline: String? = null,
    val handle: String? = null,
    val tokens: Int,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "receipt" to receipt.toJson(),
        "inline" to inline?.let { jsonText(it) },
        "handle" to handle?.let { jsonText(it) },
        "tokens" to jsonNumber(tokens),
    )

    companion object {
        fun fromJson(value: JsonValue): CallOutcome {
            val obj = value.asObject("call outcome")
            return CallOutcome(
                receipt = Receipt.fromJson(obj.requireObj("receipt")),
                inline = obj.string("inline"),
                handle = obj.string("handle"),
                tokens = obj.requireInt("tokens"),
            )
        }
    }
}

/** 非抛出的漂移检查。 */
data class VerifyReport(val ok: Boolean, val digest: Digest) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "ok" to JsonValue.Bool(ok),
        "digest" to jsonText(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): VerifyReport {
            val obj = value.asObject("verify report")
            return VerifyReport(
                ok = obj.bool("ok") ?: throw IllegalArgumentException("缺少布尔字段 ok"),
                digest = obj.requireString("digest"),
            )
        }
    }
}

/** 把 MCP annotations 映射到策略分类。 */
private fun effectFor(tool: McpTool): EffectClass {
    if (tool.annotations?.bool("readOnlyHint") == true) {
        return EffectClass.READ
    }
    if (tool.annotations?.bool("destructiveHint") == true) {
        return EffectClass.DEPLOY
    }
    return EffectClass.WRITE_IRREVERSIBLE
}

/**
 * 参考实现里 `` `${error}` `` 的 Kotlin 等价物：`<SimpleClassName>: <message>`。
 * 用于 receipt note 与拒绝消息，避免泄露完整包名。
 */
private fun describe(error: Throwable): String {
    val name = error::class.simpleName ?: "Error"
    val message = error.message
    return if (message.isNullOrBlank()) name else "$name: $message"
}

/**
 * 执行配置：MCP 工具背后的同一套预算、塑形与 receipt 规则。
 *
 * 目录 TTL 与 `expiresAt` 使用注入的 [ExecOptions.now] 解析成毫秒（参考实现直接
 * 调用 `Date.now()`）；这样目录过期、租约与 receipt 共享同一条宿主时间线。
 * 工具名会拼进 `tool` Ref 的 id（`mcp.<name>`），不符合 AMP id 语法的工具名在
 * receipt 构造时 fail closed。
 */
class ExecProfile(
    val client: McpClient,
    options: ExecOptions,
) {

    val dispatcher: Dispatcher = Dispatcher(options.budget)

    /**
     * 宿主策略引擎；`approveTool` / `policy.approve` 是宿主专用 API。
     */
    val policy: PolicyEngine = options.policy ?: PolicyEngine(
        PolicyOptions(
            now = options.now,
            approvalEffects = if (options.requireApproval == false) emptySet() else DefaultApprovalEffects,
        ),
    )

    private val handles: HandleStore = options.handles
    private val now: () -> Timestamp = options.now
    private val maxResultTokens: Int = options.maxResultTokens
    private val onReceipt: ((Receipt) -> Unit)? = options.onReceipt
    private val guard: Guard? = options.guard
    private val verifyPinsAtCall: Boolean = options.verifyPinsAtCall
    private val validateArgs: Boolean = options.validateArgs

    private var catalog: CatalogSnapshot? = null
    private var seq: Int = 0

    fun connect(): CatalogSnapshot {
        client.negotiate()
        val list = client.listTools()
        val pins = pinTools(list.tools)
        val snapshot = CatalogSnapshot(
            tools = list.tools,
            digest = pins.catalog,
            pins = pins,
            ttlMs = list.ttlMs,
            expiresAt = nowMillis() + list.ttlMs,
        )
        catalog = snapshot
        return snapshot
    }

    /**
     * 重新列目录并比较。任何变化、新增或移除的工具都 fail closed。
     */
    fun refresh(): CatalogSnapshot {
        val list = client.listTools()
        val pins = pinTools(list.tools)
        val previous = catalog
        if (previous != null) {
            val verdict = verifyToolPins(previous.pins, list.tools)
            if (!verdict.ok) {
                throw DriftDetected(
                    "tool catalog changed: ${verdict.reasons.joinToString("; ")}",
                    receipt(
                        tool = "catalog.refresh",
                        usage = dispatcher.usage(),
                        outcome = Outcome.BLOCKED,
                        note = verdict.reasons.joinToString("; "),
                    ),
                )
            }
        }
        val snapshot = CatalogSnapshot(
            tools = list.tools,
            digest = pins.catalog,
            pins = pins,
            ttlMs = list.ttlMs,
            expiresAt = nowMillis() + list.ttlMs,
        )
        catalog = snapshot
        return snapshot
    }

    fun ensureFresh() {
        val snapshot = catalog
        if (snapshot == null) {
            connect()
            return
        }
        if (nowMillis() < snapshot.expiresAt) {
            return
        }
        refresh()
    }

    fun tools(): List<McpTool> = catalog?.tools ?: emptyList()

    fun tool(name: String): McpTool? = tools().firstOrNull { it.name == name }

    /**
     * 宿主专用便利方法：签发绑定到当前工具 schema 与精确参数的审批。
     *
     * 绝不能把这个方法交给模型。
     */
    fun approveTool(
        name: String,
        args: JsonValue.Obj,
        approvedBy: String,
        ttlMs: Long? = null,
        scope: List<String>? = null,
    ): Approval {
        val tool = tool(name) ?: throw PolicyDenied("unknown tool: $name")
        return policy.approve(
            ApproveInput(
                action = name,
                effect = effectFor(tool),
                approvedBy = approvedBy,
                ttlMs = ttlMs,
                scope = scope,
                toolDigest = digestTool(tool),
                argsDigest = CanonicalJson.digest(args),
            ),
        )
    }

    /**
     * 非抛出的漂移检查，用于监控。
     */
    fun verify(): VerifyReport {
        val list = client.listTools()
        val pins = pinTools(list.tools)
        val current = catalog
        return VerifyReport(
            ok = current != null && pins.catalog == current.digest,
            digest = pins.catalog,
        )
    }

    fun call(name: String, args: JsonValue.Obj, options: CallOptions = CallOptions()): CallOutcome {
        ensureFresh()
        val tool = tool(name) ?: throw PolicyDenied(
            "unknown tool: $name",
            receipt(name, dispatcher.usage(), Outcome.BLOCKED, note = "unknown_tool:$name"),
        )

        if (verifyPinsAtCall) {
            val fresh = client.listTools()
            val pinned = verifyToolPins(catalog?.pins ?: pinTools(emptyList()), fresh.tools)
            if (!pinned.ok) {
                val receipt = receipt(
                    name,
                    dispatcher.usage(),
                    Outcome.BLOCKED,
                    note = pinned.reasons.joinToString("; "),
                )
                throw DriftDetected(
                    "tool changed before call: ${pinned.reasons.joinToString("; ")}",
                    receipt,
                )
            }
        }

        var callArgs: JsonValue.Obj = args
        var argsDigest: Digest = CanonicalJson.digest(args)
        if (validateArgs) {
            try {
                val parsed = parseToolCall(ToolSchema(tool.name, tool.inputSchema), args)
                callArgs = parsed.args
                argsDigest = parsed.digest
            } catch (error: BoundaryRejection) {
                val receipt = receipt(
                    name,
                    dispatcher.usage(),
                    Outcome.BLOCKED,
                    note = "invalid_tool_args:${error.problems.joinToString("; ")}",
                )
                throw PolicyDenied(
                    "invalid arguments for $name: ${error.problems.joinToString("; ")}",
                    receipt,
                )
            }
        }

        val effect = options.effect ?: effectFor(tool)
        val request = ActionRequest(
            action = name,
            target = Ref(RefKind.TOOL, "mcp.$name"),
            effect = effect,
            claim = options.claim,
            toolDigest = digestTool(tool),
            argsDigest = argsDigest,
        )
        val verdict: PolicyVerdict = when {
            options.approval != null -> policy.decide(request, options.approval)
            options.approvalId != null -> policy.decide(request, options.approvalId)
            else -> policy.decide(request)
        }
        if (verdict.outcome != PolicyOutcome.ALLOW) {
            val receipt = receipt(
                name,
                dispatcher.usage(),
                Outcome.BLOCKED,
                note = "${verdict.outcome.wire}:${verdict.reason}",
            )
            if (verdict.outcome == PolicyOutcome.APPROVAL_REQUIRED) {
                throw ApprovalRequired(name, receipt, verdict.reason)
            }
            throw PolicyDenied("policy denied $name: ${verdict.reason}", receipt)
        }

        try {
            guard?.beforeDispatch(options.money ?: 0.0)
        } catch (error: Throwable) {
            val receipt = receipt(
                name,
                dispatcher.usage(),
                Outcome.BLOCKED,
                note = error.message ?: error.toString(),
            )
            throw PolicyDenied("guard blocked $name: ${describe(error)}", receipt)
        }

        val budgetTokens = options.budgetTokens ?: maxResultTokens

        val text: String
        try {
            val result = dispatcher.run(CallCost(calls = 1, ms = 900)) {
                client.callTool(name, callArgs)
            }
            text = textOf(result)
            guard?.recordSuccess()
        } catch (error: Throwable) {
            guard?.recordFailure()
            val note = if (error is BudgetExceeded) {
                "${error.code}:${error.dimension.wire}"
            } else {
                describe(error)
            }
            receipt(name, dispatcher.usage(), Outcome.BLOCKED, note = note)
            throw error
        }

        val tokens = Math.ceil(text.length / 4.0).toInt()
        if (tokens > budgetTokens) {
            val handle: Handle = handles.put(text, HandleKind.TEXT, tokens)
            val receipt = receipt(
                name,
                dispatcher.usage(),
                Outcome.SUCCESS,
                handle = handle,
                predictedVoi = options.predictedVoi,
                realizedVoi = options.realizedVoi,
                note = "result $tokens tokens > budget $budgetTokens; handle emitted, nothing truncated",
            )
            return CallOutcome(receipt = receipt, handle = handle.id, tokens = tokens)
        }

        val receipt = receipt(
            name,
            dispatcher.usage(),
            Outcome.SUCCESS,
            predictedVoi = options.predictedVoi,
            realizedVoi = options.realizedVoi,
        )
        return CallOutcome(receipt = receipt, inline = text, tokens = tokens)
    }

    private fun receipt(
        tool: String,
        usage: Budget,
        outcome: Outcome,
        handle: Handle? = null,
        predictedVoi: Double? = null,
        realizedVoi: Double? = null,
        note: String? = null,
    ): Receipt {
        seq += 1
        val receipt = Receipt(
            id = "rc.exec.$seq",
            op = ReceiptOp.ACT,
            target = Ref(RefKind.TOOL, "mcp.$tool"),
            at = now(),
            usage = usage,
            outcome = outcome,
            predictedVoi = predictedVoi,
            realizedVoi = realizedVoi,
            handle = handle,
            note = note,
        )
        onReceipt?.invoke(receipt)
        return receipt
    }

    private fun nowMillis(): Long = Instant.parse(now()).toEpochMilli()
}
