package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * 直接接入运行时现有 registry/executor 端口
 * （`src/module/tool/workspaceExecutor.ts` 的移植）。
 */

/** 把七个 workspace 工具注册成只读描述；已存在同名工具时整体拒绝。 */
fun registerWorkspaceTools(
    registry: ToolRegistry,
    service: WorkspaceService,
): List<ToolDescriptor> {
    val descriptors: List<ToolDescriptor> = service.tools().map { tool ->
        ToolDescriptor(
            name = tool.name,
            description = tool.description,
            inputSchema = tool.inputSchema,
            risk = ToolRisk.READ,
        )
    }
    if (descriptors.any { registry.get(it.name) != null }) {
        throw IllegalArgumentException("workspace tools already registered")
    }
    descriptors.forEach { registry.register(it) }
    return descriptors
}

/**
 * 非 `amp_workspace_` 前缀的调用交给 fallback（通常是 MCP 执行器）；其余直接
 * 调用工作区服务，并把响应信封原文作为工具结果内容。
 */
class WorkspaceToolExecutor(
    private val service: WorkspaceService,
    private val fallback: ToolExecutor? = null,
) : ToolExecutor {

    override fun execute(invocation: ToolInvocation): ToolResult {
        if (!invocation.tool.startsWith("amp_workspace_") && fallback != null) {
            return fallback.execute(invocation)
        }
        val response: WorkspaceResponse = service.call(invocation.tool, invocation.args)
        return ToolResult(
            ok = response.ok,
            content = CanonicalJson.encode(response.toJson()),
            error = response.error?.code?.wire,
        )
    }
}
