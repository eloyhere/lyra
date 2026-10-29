package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * `src/runtime/operation.ts` 的移植：操作是原子执行原语。
 *
 * 只有操作会触碰外部状态；动作（[ActionRecord]）描述意图，操作描述实际发生的事。
 * 两者分开，审计才能回答“谁想做、谁做了、做了什么”。
 */

typealias OperationId = String

enum class OperationKind(val wire: String) {
    LLM_COMPLETE("llm.complete"),
    TOOL_CALL("tool.call"),
    MEMORY_WRITE("memory.write"),
    POLICY_CHECK("policy.check"),
    HUMAN_HANDOFF("human.handoff"),
}

enum class SideEffect(val wire: String) {
    NONE("none"),
    READ("read"),
    WRITE("write"),
    DESTRUCTIVE("destructive"),
    ;

    companion object {
        /** `ToolRisk.wire` 是 `read`/`write`/`destructive`，与这里的三个值同名。 */
        fun fromWire(value: String): SideEffect =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 side effect: $value")
    }
}

enum class OperationStatus(val wire: String) {
    RUNNING("running"),
    OK("ok"),
    ERROR("error"),
    DENIED("denied"),
}

/**
 * 一条操作记录。
 *
 * `input`/`result` 在参考实现里是 `unknown`；这里用 [JsonValue]，因为运行时的输入
 * 与结果本来就是 JSON（工具参数、工具结果、usage 信封），这样序列化与摘要都走
 * 同一套规范编码。
 */
data class OperationRecord(
    val id: OperationId,
    val actionId: String,
    val kind: OperationKind,
    val input: JsonValue,
    val executor: String,
    val sideEffect: SideEffect,
    val idempotencyKey: String? = null,
    val timeoutMs: Long? = null,
    val status: OperationStatus,
    val result: JsonValue? = null,
    val evidenceId: String? = null,
)

/** 审批结论的三种状态；参考实现把它写成字面量联合。 */
enum class ApprovalStatus(val wire: String) {
    ALLOWED("allowed"),
    DENIED("denied"),
    NOT_REQUIRED("not_required"),
}

/** 工具审批/拒绝的独立审计记录。 */
data class ApprovalRecord(
    val operationId: String,
    val tool: String,
    val risk: SideEffect,
    val decision: String,
    val status: ApprovalStatus,
)
