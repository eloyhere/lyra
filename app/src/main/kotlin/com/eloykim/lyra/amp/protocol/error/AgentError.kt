package com.eloykim.lyra.amp.protocol.error

/**
 * `src/error/agentError.ts` 的移植：智能体 facets 的领域错误，绝不是数据库错误。
 *
 * 参考实现的 `AgentErrorCode` 定义在 `src/process/agent.ts`；进程/人物模块尚未移植，
 * 这里按参考文件原样抄录该 union 的九个字面量。
 */
enum class AgentErrorCode(override val wire: String) : AmpErrorCode {
    AGENT_NOT_FOUND("agent_not_found"),
    PROFILE_EXISTS("profile_exists"),
    ROLE_NOT_FOUND("role_not_found"),
    ROLE_NOT_ASSIGNED("role_not_assigned"),
    MEMORY_NOT_FOUND("memory_not_found"),
    INVALID_MEMORY("invalid_memory"),
    INVALID_AFFECT("invalid_affect"),
    RELATIONSHIP_NOT_FOUND("relationship_not_found"),
    INSTITUTION_NOT_FOUND("institution_not_found"),
    ;
}

/**
 * `code` 与 `details` 形状与参考一致：没有 details 时消息就是 code 本身，有 details 时
 * 是 `<code>: <details joined by "; ">`。注意它不继承 [AmpError]（参考实现同样不继承），
 * 但 code 仍实现 [AmpErrorCode]，便于统一记录。
 */
class AgentError(
    val code: AgentErrorCode,
    details: List<String> = emptyList(),
) : RuntimeException() {

    /** 参考实现 `Error.name`。 */
    val name: String = "AgentError"

    val details: List<String> = ArrayList(details)

    override val message: String = if (this.details.isEmpty()) {
        code.wire
    } else {
        "${code.wire}: ${this.details.joinToString("; ")}"
    }

    override fun toString(): String = "AgentError: $message"
}
