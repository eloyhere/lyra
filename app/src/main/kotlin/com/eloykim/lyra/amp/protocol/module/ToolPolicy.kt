package com.eloykim.lyra.amp.protocol.module

/**
 * 工具权限策略（`src/module/tool/policy.ts` 的移植）。
 *
 * 运行时在执行工具前询问策略；MCP 的 annotations 可以提供风险等级。
 * 参考实现的 `check` 是异步的；Android 端口使用同步回调，宿主需要异步审批时
 * 在回调内部自行阻塞或预取审批结果。
 */
data class ToolDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

fun interface ToolPolicy {
    fun check(invocation: ToolInvocation, descriptor: ToolDescriptor): ToolDecision
}

data class RiskToolPolicyOptions(
    /** 非空时只允许这些工具。 */
    val allowedTools: List<String>? = null,
    /** 需要显式审批的风险等级；默认只有 destructive。 */
    val requireApproval: List<ToolRisk>? = null,
    val approve: ((ToolInvocation, ToolDescriptor) -> Boolean)? = null,
)

class RiskToolPolicy(options: RiskToolPolicyOptions = RiskToolPolicyOptions()) : ToolPolicy {

    private val allowed: Set<String>? = options.allowedTools?.toSet()
    private val approval: Set<ToolRisk> = options.requireApproval?.toSet() ?: setOf(ToolRisk.DESTRUCTIVE)
    private val approve: ((ToolInvocation, ToolDescriptor) -> Boolean)? = options.approve

    override fun check(invocation: ToolInvocation, descriptor: ToolDescriptor): ToolDecision {
        if (allowed != null && descriptor.name !in allowed) {
            return ToolDecision(false, "tool ${descriptor.name} is not in the allowlist")
        }
        if ((descriptor.risk ?: ToolRisk.READ) !in approval) {
            return ToolDecision(true)
        }
        if (approve == null) {
            return ToolDecision(false, "tool ${descriptor.name} requires approval")
        }
        return if (approve(invocation, descriptor)) {
            ToolDecision(true)
        } else {
            ToolDecision(false, "tool ${descriptor.name} was not approved")
        }
    }
}
