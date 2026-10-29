package com.eloykim.lyra.amp.protocol.boundary

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest

/**
 * 工具的声明面：名字 + 调用参数 schema。
 */
data class ToolSchema(
    val name: String,
    val inputSchema: JsonValue,
)

/**
 * 已解析的工具调用。
 *
 * `args` 是**被接受**的参数；审批应绑定到 [digest]，而不是绑定到一个之后
 * 还可能变化的对象。
 */
data class ParsedToolCall(
    val tool: String,
    val args: JsonValue.Obj,
    val digest: Digest,
)

private const val MAX_SCHEMA_DEPTH: Int = 32

private fun canonicalEquals(left: JsonValue, right: JsonValue): Boolean =
    CanonicalJson.encode(left) == CanonicalJson.encode(right)

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

private fun schemaMatches(schema: JsonValue, value: JsonValue, depth: Int): Boolean {
    val problems = ArrayList<String>()
    checkSchema(schema, value, "$", depth, problems)
    return problems.isEmpty()
}

private fun checkSchema(
    schema: JsonValue,
    value: JsonValue,
    at: String,
    depth: Int,
    problems: MutableList<String>,
) {
    if (depth > MAX_SCHEMA_DEPTH) {
        problems += "$at: schema nesting exceeds $MAX_SCHEMA_DEPTH"
        return
    }
    if (schema !is JsonValue.Obj) {
        problems += "$at: schema must be an object"
        return
    }

    if (schema.has("const") && !canonicalEquals(schema.fields.getValue("const"), value)) {
        problems += "$at must equal the const value"
    }
    schema.arr("enum")?.let { allowed ->
        if (allowed.items.none { canonicalEquals(it, value) }) {
            problems += "$at is not one of the allowed values"
        }
    }
    schema.arr("allOf")?.items?.forEach { branch ->
        checkSchema(branch, value, at, depth + 1, problems)
    }
    schema.arr("anyOf")?.let { branches ->
        if (branches.items.none { schemaMatches(it, value, depth + 1) }) {
            problems += "$at matches none of anyOf"
        }
    }
    schema.arr("oneOf")?.let { branches ->
        val matched = branches.items.count { schemaMatches(it, value, depth + 1) }
        if (matched != 1) {
            problems += "$at matches $matched of oneOf, expected exactly 1"
        }
    }

    val types: List<String> = when (val declared = schema["type"]) {
        is JsonValue.Str -> listOf(declared.value)
        is JsonValue.Arr -> declared.items.mapNotNull { (it as? JsonValue.Str)?.value }
        else -> emptyList()
    }
    if (types.isNotEmpty() && types.none { matchesType(it, value) }) {
        problems += "$at expected ${types.joinToString("|")}"
        return
    }

    when (value) {
        is JsonValue.Arr -> {
            val itemsSchema = schema["items"]
            if (itemsSchema is JsonValue.Arr) {
                itemsSchema.items.forEachIndexed { index, itemSchema ->
                    if (index < value.items.size) {
                        checkSchema(itemSchema, value.items[index], "$at[$index]", depth + 1, problems)
                    }
                }
            } else if (itemsSchema != null) {
                value.items.forEachIndexed { index, item ->
                    checkSchema(itemsSchema, item, "$at[$index]", depth + 1, problems)
                }
            }
            schema.int("minItems")?.let { minimum ->
                if (value.items.size < minimum) problems += "$at needs at least $minimum items"
            }
            schema.int("maxItems")?.let { maximum ->
                if (value.items.size > maximum) problems += "$at allows at most $maximum items"
            }
            return
        }

        is JsonValue.Obj -> {
            schema.arr("required")?.items?.forEach { required ->
                val name = (required as? JsonValue.Str)?.value
                if (name != null && !value.has(name)) {
                    problems += "$at.$name is required"
                }
            }
            val properties = schema.obj("properties")
            if (properties != null) {
                value.fields.forEach { (key, childValue) ->
                    val child = properties[key]
                    if (child != null) {
                        checkSchema(child, childValue, "$at.$key", depth + 1, problems)
                    } else if (schema.bool("additionalProperties") == false) {
                        problems += "$at.$key is not allowed"
                    }
                }
            } else if (schema.bool("additionalProperties") == false && value.fields.isNotEmpty()) {
                problems += "$at does not allow properties"
            }
            return
        }

        is JsonValue.Str -> {
            schema.int("minLength")?.let { minimum ->
                if (value.value.length < minimum) problems += "$at is shorter than $minimum"
            }
            schema.int("maxLength")?.let { maximum ->
                if (value.value.length > maximum) problems += "$at is longer than $maximum"
            }
            schema.string("pattern")?.let { pattern ->
                val regex = runCatching { Regex(pattern) }.getOrNull()
                if (regex == null) {
                    problems += "$at has an invalid pattern"
                } else if (!regex.containsMatchIn(value.value)) {
                    problems += "$at does not match $pattern"
                }
            }
        }

        is JsonValue.Num -> {
            schema.double("minimum")?.let { minimum ->
                if (value.value < minimum) {
                    problems += "$at is below ${CanonicalJson.formatNumber(minimum)}"
                }
            }
            schema.double("maximum")?.let { maximum ->
                if (value.value > maximum) {
                    problems += "$at is above ${CanonicalJson.formatNumber(maximum)}"
                }
            }
        }

        else -> Unit
    }
}

/**
 * 在世界被触碰之前解析工具调用参数（`src/boundary/toolCall.ts`）。
 *
 * 校验 MCP 工具声明实际用到的 JSON Schema 子集：`type`、`properties`、
 * `required`、`additionalProperties`、`items`、`enum`、`const`、
 * `anyOf`/`oneOf`/`allOf`、长度与数值边界、`pattern`。子集之外的关
 * 键字被忽略而不是猜测；需要更多表达力的 schema 应先收窄再进入本层。
 *
 * @throws BoundaryRejection code 为 `invalid_tool_args`，附带全部问题。
 */
fun parseToolCall(tool: ToolSchema, raw: JsonValue): ParsedToolCall {
    val problems = ArrayList<String>()
    if (tool.name.isEmpty()) {
        problems += "tool.name is empty"
    }
    if (raw !is JsonValue.Obj) {
        problems += "arguments must be an object"
    } else {
        checkSchema(tool.inputSchema, raw, "$", 0, problems)
    }
    if (problems.isNotEmpty()) {
        throw BoundaryRejection(BoundaryCodes.INVALID_TOOL_ARGS, problems)
    }
    val args = raw as JsonValue.Obj
    return ParsedToolCall(tool = tool.name, args = args, digest = CanonicalJson.digest(args))
}
