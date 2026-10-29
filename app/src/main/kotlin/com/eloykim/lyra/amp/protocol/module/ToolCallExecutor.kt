package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest

/**
 * 工具调用边界（`src/boundary/toolCall.ts` 的等价实现）。
 *
 * 参考执行器把 `Record<string, unknown>` 直接交给服务器；这里在执行前按工具
 * 的 `inputSchema` 校验参数，并对**被接受的参数**计算摘要，使审批可以绑定到
 * 解析后的调用，而不是一个可变对象。
 *
 * 本文件刻意不依赖 `com.eloykim.lyra.amp.protocol.boundary`：那部分由另一个
 * agent 并行移植。协调者只需把 [ToolArgumentValidator] 的实现换成一行适配器
 * （调用 boundary 的 parseToolCall），其余代码不变。
 *
 * 校验器实现 MCP 工具定义真正使用的 JSON Schema 子集：`type`、`properties`、
 * `required`、`additionalProperties`、`items`、`enum`、`const`、`anyOf` /
 * `oneOf` / `allOf`、长度与数值边界、`pattern`。子集之外的 keyword 被忽略而
 * 不是猜测语义；需要更多约束的 schema 应先收窄再进入这一层。
 */

/** 对应参考实现的 `ToolSchema`。 */
data class ToolCallSchema(
    val name: String,
    val inputSchema: JsonValue,
)

/** 对应参考实现的 `ParsedToolCall`；[digest] 是审批绑定的参数摘要。 */
data class ValidatedToolCall(
    val tool: String,
    val args: JsonValue.Obj,
    val digest: Digest,
)

/** 对应参考实现的 `BoundaryRejection("invalid_tool_args", problems)`。 */
class InvalidToolArgumentsException(val problems: List<String>) :
    IllegalArgumentException("invalid_tool_args: ${problems.joinToString("; ")}")

fun interface ToolArgumentValidator {
    /** @throws InvalidToolArgumentsException 参数不满足 schema 时。 */
    fun parse(schema: ToolCallSchema, raw: JsonValue): ValidatedToolCall
}

object SchemaToolArgumentValidator : ToolArgumentValidator {

    private const val MAX_SCHEMA_DEPTH: Int = 32

    override fun parse(schema: ToolCallSchema, raw: JsonValue): ValidatedToolCall {
        val problems: ArrayList<String> = ArrayList()
        if (schema.name.isEmpty()) {
            problems += "tool.name is empty"
        }
        val args: JsonValue.Obj? = raw as? JsonValue.Obj
        if (args == null) {
            problems += "arguments must be an object"
        } else {
            checkSchema(schema.inputSchema, args, "$", 0, problems)
        }
        if (problems.isNotEmpty()) {
            throw InvalidToolArgumentsException(problems)
        }
        val accepted: JsonValue.Obj = args ?: throw InvalidToolArgumentsException(problems)
        return ValidatedToolCall(schema.name, accepted, CanonicalJson.digest(accepted))
    }

    private fun checkSchema(
        schema: JsonValue?,
        value: JsonValue,
        at: String,
        depth: Int,
        problems: MutableList<String>,
    ) {
        if (depth > MAX_SCHEMA_DEPTH) {
            problems += "$at: schema nesting exceeds 32"
            return
        }
        if (schema !is JsonValue.Obj) {
            problems += "$at: schema must be an object"
            return
        }

        schema["const"]?.let { constant ->
            if (CanonicalJson.encode(constant) != CanonicalJson.encode(value)) {
                problems += "$at must equal the const value"
            }
        }
        (schema["enum"] as? JsonValue.Arr)?.let { allowed ->
            if (allowed.items.none { CanonicalJson.encode(it) == CanonicalJson.encode(value) }) {
                problems += "$at is not one of the allowed values"
            }
        }
        (schema["allOf"] as? JsonValue.Arr)?.items?.forEach { branch ->
            checkSchema(branch, value, at, depth + 1, problems)
        }
        (schema["anyOf"] as? JsonValue.Arr)?.let { branches ->
            if (branches.items.none { schemaMatches(it, value, depth + 1) }) {
                problems += "$at matches none of anyOf"
            }
        }
        (schema["oneOf"] as? JsonValue.Arr)?.let { branches ->
            val matched: Int = branches.items.count { schemaMatches(it, value, depth + 1) }
            if (matched != 1) {
                problems += "$at matches $matched of oneOf, expected exactly 1"
            }
        }

        val declared: JsonValue? = schema["type"]
        val types: List<String> = when (declared) {
            is JsonValue.Arr -> declared.items.filterIsInstance<JsonValue.Str>().map { it.value }
            is JsonValue.Str -> listOf(declared.value)
            else -> emptyList()
        }
        if (types.isNotEmpty() && types.none { matchesType(it, value) }) {
            problems += "$at expected ${types.joinToString("|")}"
            return
        }

        when (value) {
            is JsonValue.Arr -> checkArray(schema, value, at, depth, problems)
            is JsonValue.Obj -> checkObject(schema, value, at, depth, problems)
            is JsonValue.Str -> checkString(schema, value, at, problems)
            is JsonValue.Num -> checkNumber(schema, value, at, problems)
            else -> Unit
        }
    }

    private fun checkArray(
        schema: JsonValue.Obj,
        value: JsonValue.Arr,
        at: String,
        depth: Int,
        problems: MutableList<String>,
    ) {
        val items: JsonValue? = schema["items"]
        when (items) {
            is JsonValue.Arr -> items.items.forEachIndexed { index, itemSchema ->
                if (index < value.items.size) {
                    checkSchema(itemSchema, value.items[index], "$at[$index]", depth + 1, problems)
                }
            }

            null -> Unit

            else -> value.items.forEachIndexed { index, item ->
                checkSchema(items, item, "$at[$index]", depth + 1, problems)
            }
        }
        schema.number("minItems")?.let { minimum ->
            if (value.items.size < minimum) {
                problems += "$at needs at least ${CanonicalJson.formatNumber(minimum)} items"
            }
        }
        schema.number("maxItems")?.let { maximum ->
            if (value.items.size > maximum) {
                problems += "$at allows at most ${CanonicalJson.formatNumber(maximum)} items"
            }
        }
    }

    private fun checkObject(
        schema: JsonValue.Obj,
        value: JsonValue.Obj,
        at: String,
        depth: Int,
        problems: MutableList<String>,
    ) {
        (schema["required"] as? JsonValue.Arr)?.items?.forEach { required ->
            val name: String? = (required as? JsonValue.Str)?.value
            if (name != null && !value.fields.containsKey(name)) {
                problems += "$at.$name is required"
            }
        }
        val properties: JsonValue.Obj? = schema["properties"] as? JsonValue.Obj
        val additionalAllowed: Boolean = (schema["additionalProperties"] as? JsonValue.Bool)?.value != false
        if (properties != null) {
            value.fields.forEach { (key, childValue) ->
                val child: JsonValue? = properties[key]
                if (child != null) {
                    checkSchema(child, childValue, "$at.$key", depth + 1, problems)
                } else if (!additionalAllowed) {
                    problems += "$at.$key is not allowed"
                }
            }
        } else if (!additionalAllowed && value.fields.isNotEmpty()) {
            problems += "$at does not allow properties"
        }
    }

    private fun checkString(schema: JsonValue.Obj, value: JsonValue.Str, at: String, problems: MutableList<String>) {
        schema.number("minLength")?.let { minimum ->
            if (value.value.length < minimum) {
                problems += "$at is shorter than ${CanonicalJson.formatNumber(minimum)}"
            }
        }
        schema.number("maxLength")?.let { maximum ->
            if (value.value.length > maximum) {
                problems += "$at is longer than ${CanonicalJson.formatNumber(maximum)}"
            }
        }
        (schema["pattern"] as? JsonValue.Str)?.value?.let { pattern ->
            val regex: Regex? = runCatching { Regex(pattern) }.getOrNull()
            if (regex == null) {
                problems += "$at has an invalid pattern"
            } else if (!regex.containsMatchIn(value.value)) {
                problems += "$at does not match $pattern"
            }
        }
    }

    private fun checkNumber(schema: JsonValue.Obj, value: JsonValue.Num, at: String, problems: MutableList<String>) {
        schema.number("minimum")?.let { minimum ->
            if (value.value < minimum) {
                problems += "$at is below ${CanonicalJson.formatNumber(minimum)}"
            }
        }
        schema.number("maximum")?.let { maximum ->
            if (value.value > maximum) {
                problems += "$at is above ${CanonicalJson.formatNumber(maximum)}"
            }
        }
    }

    private fun schemaMatches(schema: JsonValue?, value: JsonValue, depth: Int): Boolean {
        val problems: ArrayList<String> = ArrayList()
        checkSchema(schema, value, "$", depth, problems)
        return problems.isEmpty()
    }

    private fun matchesType(type: String, value: JsonValue): Boolean = when (type) {
        "null" -> value is JsonValue.Null
        "array" -> value is JsonValue.Arr
        "object" -> value is JsonValue.Obj
        "string" -> value is JsonValue.Str
        "boolean" -> value is JsonValue.Bool
        "number" -> value is JsonValue.Num && value.value.isFinite()
        "integer" -> value is JsonValue.Num && value.value.isFinite() && value.value == Math.floor(value.value)
        else -> true
    }

    private fun JsonValue.Obj.number(key: String): Double? = (this[key] as? JsonValue.Num)?.value
}

/**
 * 在委托执行器前校验参数、应用策略并把参数摘要钉住。
 *
 * `expectedDigest` 对应参考实现里审批绑定的 `argsDigest`：摘要不一致意味着
 * 调用参数在审批后发生变化，必须拒绝而不是重放审批。
 */
class ToolCallExecutor(
    private val registry: ToolRegistry,
    private val delegate: ToolExecutor,
    private val policy: ToolPolicy? = null,
    private val validator: ToolArgumentValidator = SchemaToolArgumentValidator,
    private val validateArgs: Boolean = true,
) : ToolExecutor {

    /** 供宿主在执行前取摘要、绑定审批；参数非法时抛出 [InvalidToolArgumentsException]。 */
    fun prepare(invocation: ToolInvocation): ValidatedToolCall {
        val descriptor: ToolDescriptor = registry.get(invocation.tool)
            ?: throw IllegalArgumentException("unknown tool ${invocation.tool}")
        return validate(descriptor, invocation.args)
    }

    override fun execute(invocation: ToolInvocation): ToolResult = execute(invocation, null)

    fun execute(invocation: ToolInvocation, expectedDigest: Digest?): ToolResult {
        val descriptor: ToolDescriptor = registry.get(invocation.tool)
            ?: return ToolResult(false, "", "unknown tool: ${invocation.tool}")
        val parsed: ValidatedToolCall = try {
            validate(descriptor, invocation.args)
        } catch (error: IllegalArgumentException) {
            return ToolResult(false, "", error.message ?: "invalid_tool_args")
        }
        if (expectedDigest != null && parsed.digest != expectedDigest) {
            return ToolResult(false, "", "approval_digest_mismatch: expected $expectedDigest, got ${parsed.digest}")
        }
        val decision: ToolDecision? = policy?.check(ToolInvocation(parsed.tool, parsed.args), descriptor)
        if (decision != null && !decision.allowed) {
            return ToolResult(false, "", decision.reason ?: "tool ${descriptor.name} was not approved")
        }
        return delegate.execute(ToolInvocation(parsed.tool, parsed.args))
    }

    private fun validate(descriptor: ToolDescriptor, args: JsonValue.Obj): ValidatedToolCall =
        if (validateArgs) {
            validator.parse(ToolCallSchema(descriptor.name, descriptor.inputSchema), args)
        } else {
            ValidatedToolCall(descriptor.name, args, CanonicalJson.digest(args))
        }
}

/**
 * 把 MCP 工具注册进 [ToolRegistry]（`registerMcpTools` 的移植）。
 *
 * 风险等级来自 MCP annotations：`destructiveHint` → destructive，
 * `readOnlyHint` → read，否则保守地取 write。描述回退顺序是
 * description → title → name。
 */
fun registerMcpTools(registry: ToolRegistry, client: McpClient): List<ToolDescriptor> {
    val listed: ListToolsResult = client.listTools()
    val registered: MutableList<ToolDescriptor> = ArrayList(listed.tools.size)
    listed.tools.forEach { tool ->
        val destructive: Boolean = tool.annotations?.bool("destructiveHint") == true
        val readOnly: Boolean = tool.annotations?.bool("readOnlyHint") == true
        val descriptor = ToolDescriptor(
            name = tool.name,
            description = tool.description ?: tool.title ?: tool.name,
            inputSchema = tool.inputSchema,
            risk = when {
                destructive -> ToolRisk.DESTRUCTIVE
                readOnly -> ToolRisk.READ
                else -> ToolRisk.WRITE
            },
        )
        registry.register(descriptor)
        registered += descriptor
    }
    return registered
}

/**
 * MCP 版 [ToolExecutor]（`McpToolExecutor` 的移植）。
 *
 * 运行时只看到 [ToolExecutor]；MCP 是它背后的一个适配器。协议错误与传输
 * 异常都转换成 `ok = false` 的结果，而不是抛给工具循环。
 */
class McpToolExecutor(private val client: McpClient) : ToolExecutor {

    override fun execute(invocation: ToolInvocation): ToolResult = try {
        val result: CallToolResult = client.callTool(invocation.tool, invocation.args)
        val text: String = textOf(result)
        if (result.isError == true) {
            ToolResult(ok = false, content = text, error = text)
        } else {
            ToolResult(ok = true, content = text)
        }
    } catch (error: Exception) {
        ToolResult(ok = false, content = "", error = error.message ?: error.toString())
    }
}
