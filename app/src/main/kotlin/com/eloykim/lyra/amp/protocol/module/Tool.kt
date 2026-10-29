package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue

/**
 * 工具端口与注册表（`src/module/tool/tool.ts` 的移植）。
 *
 * MCP 只是 [ToolExecutor] 的一种实现；本地函数或沙箱命令可以换进来而不影响内核。
 */
enum class ToolRisk(val wire: String) {
    READ("read"),
    WRITE("write"),
    DESTRUCTIVE("destructive");

    companion object {
        fun fromWire(value: String): ToolRisk =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 tool risk: $value")
    }
}

data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonValue.Obj,
    val risk: ToolRisk? = null,
)

data class ToolInvocation(
    val tool: String,
    val args: JsonValue.Obj,
)

data class ToolResult(
    val ok: Boolean,
    val content: String,
    val error: String? = null,
)

fun interface ToolExecutor {
    fun execute(invocation: ToolInvocation): ToolResult
}

/**
 * 按注册顺序保存工具描述；重复注册与参考实现一样立即失败。
 */
class ToolRegistry {

    private val tools: LinkedHashMap<String, ToolDescriptor> = LinkedHashMap()

    fun register(descriptor: ToolDescriptor): ToolRegistry {
        require(!tools.containsKey(descriptor.name)) { "duplicate tool ${descriptor.name}" }
        tools[descriptor.name] = descriptor
        return this
    }

    fun get(name: String): ToolDescriptor? = tools[name]

    fun list(): List<ToolDescriptor> = tools.values.toList()

    val size: Int
        get() = tools.size
}
