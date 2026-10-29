package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * AMP workspace 扩展 v1（对应 `src/context/workspaceProtocol.ts`）。
 *
 * 本地实体 ID 是不透明的 workspace 键，既不是路径，也不是全局 AMP 对象 ID。
 * 响应外层固定为 `protocol/version/revision/ok/data|error`；除能力发现外，
 * 每个业务工具都必须携带当前 revision。
 */
const val WORKSPACE_PROTOCOL: String = "amp.workspace"
const val WORKSPACE_PROTOCOL_VERSION: Int = 1

/** JS `Number.MAX_SAFE_INTEGER`。 */
private const val MAX_SAFE_INTEGER: Long = 9007199254740991L

enum class WorkspaceErrorCode(val wire: String) {
    INVALID_ARGUMENTS("invalid_arguments"),
    UNKNOWN_TOOL("unknown_tool"),
    REVISION_MISMATCH("revision_mismatch"),
    NOT_AVAILABLE("not_available"),
    FRAME_UNAVAILABLE("frame_unavailable"),
    BUDGET_EXHAUSTED("budget_exhausted"),
    CAPACITY_EXCEEDED("capacity_exceeded"),
    RESPONSE_TOO_LARGE("response_too_large"),
    INTEGRITY_FAILURE("integrity_failure"),
    INTERNAL_ERROR("internal_error"),
}

/** 参数校验失败；服务层把它映射成 `invalid_arguments`（对应 BoundaryRejection）。 */
class WorkspaceArgumentException(message: String) : IllegalArgumentException(message)

sealed class WorkspaceResponse {

    abstract val revision: String
    abstract val ok: Boolean

    abstract fun toJson(): JsonValue.Obj

    data class Success(
        override val revision: String,
        val data: JsonValue.Obj,
    ) : WorkspaceResponse() {

        override val ok: Boolean get() = true

        override fun toJson(): JsonValue.Obj = jsonObj(
            "protocol" to JsonValue.Str(WORKSPACE_PROTOCOL),
            "version" to jsonNumber(WORKSPACE_PROTOCOL_VERSION),
            "revision" to JsonValue.Str(revision),
            "ok" to JsonValue.Bool(true),
            "data" to data,
        )
    }

    data class Failure(
        override val revision: String,
        val code: WorkspaceErrorCode,
    ) : WorkspaceResponse() {

        override val ok: Boolean get() = false

        override fun toJson(): JsonValue.Obj = jsonObj(
            "protocol" to JsonValue.Str(WORKSPACE_PROTOCOL),
            "version" to jsonNumber(WORKSPACE_PROTOCOL_VERSION),
            "revision" to JsonValue.Str(revision),
            "ok" to JsonValue.Bool(false),
            "error" to jsonObj(
                "code" to JsonValue.Str(code.wire),
                "message" to JsonValue.Str(code.wire),
            ),
        )
    }
}

data class WorkspaceToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonValue.Obj,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "name" to JsonValue.Str(name),
        "description" to JsonValue.Str(description),
        "inputSchema" to inputSchema,
    )
}

private val KEY_SCHEMA: JsonValue.Obj = jsonObj(
    "type" to JsonValue.Str("string"),
    "minLength" to jsonNumber(1),
    "maxLength" to jsonNumber(4096),
)

private val KEYS_SCHEMA: JsonValue.Obj = jsonObj(
    "type" to JsonValue.Str("array"),
    "items" to KEY_SCHEMA,
    "maxItems" to jsonNumber(100),
)

private val RELATION_KIND_WIRES: List<String> = listOf("project_depends_on", "imports", "calls", "references", "documents")

private val RELATION_KINDS_SCHEMA: JsonValue.Arr = jsonArr(
    *RELATION_KIND_WIRES.map { JsonValue.Str(it) }.toTypedArray(),
)

private val OFFSET_SCHEMA: JsonValue.Obj = jsonObj(
    "type" to JsonValue.Str("integer"),
    "minimum" to jsonNumber(0),
    "maximum" to jsonNumber(MAX_SAFE_INTEGER),
)

private val LIMIT_SCHEMA: JsonValue.Obj = jsonObj(
    "type" to JsonValue.Str("integer"),
    "minimum" to jsonNumber(1),
    "maximum" to jsonNumber(100),
)

private fun definition(
    name: String,
    description: String,
    properties: Map<String, JsonValue>,
    required: List<String> = emptyList(),
): WorkspaceToolDefinition = WorkspaceToolDefinition(
    name = "amp_workspace_$name",
    description = description,
    inputSchema = jsonObj(
        "type" to JsonValue.Str("object"),
        "properties" to JsonValue.Obj(LinkedHashMap(properties)),
        "required" to jsonArr(*required.map { JsonValue.Str(it) }.toTypedArray()),
        "additionalProperties" to JsonValue.Bool(false),
    ),
)

private val DEFINITIONS: List<WorkspaceToolDefinition> = listOf(
    definition(
        "capabilities",
        "Discover authorized workspace projects, revision, registered Frame IDs and service limits. Does not grant new access.",
        linkedMapOf(),
    ),
    definition(
        "tree",
        "List project roots or direct structural children with native source locations; no summary or detail bodies. Use offset for pagination.",
        linkedMapOf(
            "revision" to KEY_SCHEMA,
            "parentId" to KEY_SCHEMA,
            "offset" to OFFSET_SCHEMA,
            "limit" to LIMIT_SCHEMA,
        ),
        listOf("revision"),
    ),
    definition(
        "query",
        "Find candidate IDs using lexical AND search and bounded graph traversal. Candidates need host Frame allocation before content reads.",
        linkedMapOf(
            "revision" to KEY_SCHEMA,
            "text" to jsonObj("type" to JsonValue.Str("string"), "maxLength" to jsonNumber(4096)),
            "seedIds" to KEYS_SCHEMA,
            "projectIds" to KEYS_SCHEMA,
            "relationKinds" to jsonObj(
                "type" to JsonValue.Str("array"),
                "items" to jsonObj("type" to JsonValue.Str("string"), "enum" to RELATION_KINDS_SCHEMA),
                "maxItems" to jsonNumber(5),
            ),
            "direction" to jsonObj(
                "type" to JsonValue.Str("string"),
                "enum" to jsonArr(JsonValue.Str("incoming"), JsonValue.Str("outgoing"), JsonValue.Str("both")),
            ),
            "maxDepth" to jsonObj(
                "type" to JsonValue.Str("integer"),
                "minimum" to jsonNumber(0),
                "maximum" to jsonNumber(10),
            ),
            "limit" to LIMIT_SCHEMA,
        ),
        listOf("revision"),
    ),
    definition(
        "relations",
        "Page descriptive dependency/call/reference edges for one authorized node. Does not materialize either endpoint.",
        linkedMapOf(
            "revision" to KEY_SCHEMA,
            "nodeId" to KEY_SCHEMA,
            "direction" to jsonObj(
                "type" to JsonValue.Str("string"),
                "enum" to jsonArr(JsonValue.Str("incoming"), JsonValue.Str("outgoing")),
            ),
            "relationKind" to jsonObj(
                "type" to JsonValue.Str("string"),
                "enum" to RELATION_KINDS_SCHEMA,
            ),
            "offset" to OFFSET_SCHEMA,
            "limit" to LIMIT_SCHEMA,
        ),
        listOf("revision", "nodeId", "direction"),
    ),
    definition(
        "materialize",
        "Materialize a host-registered Frame once. Repeated calls reuse its Context. Only summaries fitting the requested and host/Frame budgets are returned.",
        linkedMapOf(
            "revision" to KEY_SCHEMA,
            "frameId" to KEY_SCHEMA,
            "budgetTokens" to jsonObj(
                "type" to JsonValue.Str("integer"),
                "minimum" to jsonNumber(1),
                "maximum" to jsonNumber(32000),
            ),
        ),
        listOf("revision", "frameId", "budgetTokens"),
    ),
    definition(
        "read",
        "Read a bounded page of CAS-verified detail from an explicitly allocated, materialized target. Structural ancestors alone do not grant detail access. Offsets count UTF-16 code units.",
        linkedMapOf(
            "revision" to KEY_SCHEMA,
            "contextId" to KEY_SCHEMA,
            "nodeId" to KEY_SCHEMA,
            "offset" to OFFSET_SCHEMA,
            "maxChars" to jsonObj(
                "type" to JsonValue.Str("integer"),
                "minimum" to jsonNumber(1),
                "maximum" to jsonNumber(16000),
            ),
        ),
        listOf("revision", "contextId", "nodeId"),
    ),
    definition(
        "release",
        "Release this service's Context handle. Does not create an action Receipt or refund/reopen its Frame budget.",
        linkedMapOf(
            "revision" to KEY_SCHEMA,
            "contextId" to KEY_SCHEMA,
        ),
        listOf("revision", "contextId"),
    ),
)

fun workspaceToolDefinitions(): List<WorkspaceToolDefinition> = DEFINITIONS.toList()

/**
 * 校验工具参数并返回已接受的参数对象（对应 `boundary/toolCall.ts` 的
 * `parseToolCall`）。只实现 workspace 工具定义实际用到的 JSON Schema 子集：
 * `type` / `properties` / `required` / `additionalProperties` / `items` /
 * `enum` / 长度与数值上下界 / `pattern`。其余关键字一律忽略而不是猜测。
 */
fun parseWorkspaceToolCall(tool: WorkspaceToolDefinition, raw: JsonValue?): JsonValue.Obj {
    if (tool.name.isEmpty()) {
        throw WorkspaceArgumentException("tool.name is empty")
    }
    if (raw !is JsonValue.Obj) {
        throw WorkspaceArgumentException("arguments must be an object")
    }
    val problems: MutableList<String> = ArrayList()
    checkSchema(tool.inputSchema, raw, "$", 0, problems)
    if (problems.isNotEmpty()) {
        throw WorkspaceArgumentException(problems.joinToString("; "))
    }
    return raw
}

private fun checkSchema(
    schema: JsonValue,
    value: JsonValue,
    at: String,
    depth: Int,
    problems: MutableList<String>,
) {
    if (depth > 32) {
        problems.add("$at: schema nesting exceeds 32")
        return
    }
    val obj: JsonValue.Obj = schema as? JsonValue.Obj ?: run {
        problems.add("$at: schema must be an object")
        return
    }
    (obj["enum"] as? JsonValue.Arr)?.let { allowed ->
        if (allowed.items.isNotEmpty() && allowed.items.none { sameJson(it, value) }) {
            problems.add("$at is not one of the allowed values")
        }
    }
    val declared: JsonValue? = obj["type"]
    val types: List<String> = when (declared) {
        is JsonValue.Str -> listOf(declared.value)
        is JsonValue.Arr -> declared.items.mapNotNull { (it as? JsonValue.Str)?.value }
        else -> emptyList()
    }
    if (types.isNotEmpty() && types.none { matchesType(it, value) }) {
        problems.add("$at expected ${types.joinToString("|")}")
        return
    }
    when (value) {
        is JsonValue.Arr -> {
            val items: JsonValue? = obj["items"]
            if (items is JsonValue.Arr) {
                items.items.forEachIndexed { index, item ->
                    if (index < value.size) {
                        checkSchema(item, value.items[index], "$at[$index]", depth + 1, problems)
                    }
                }
            } else if (items != null) {
                value.items.forEachIndexed { index, item ->
                    checkSchema(items, item, "$at[$index]", depth + 1, problems)
                }
            }
            numberOrNull(obj["minItems"])?.let { minimum ->
                if (value.size < minimum) problems.add("$at needs at least ${minimum.toInt()} items")
            }
            numberOrNull(obj["maxItems"])?.let { maximum ->
                if (value.size > maximum) problems.add("$at allows at most ${maximum.toInt()} items")
            }
        }

        is JsonValue.Obj -> {
            val required: List<String> = (obj["required"] as? JsonValue.Arr)?.items?.mapNotNull {
                (it as? JsonValue.Str)?.value
            } ?: emptyList()
            required.forEach { name ->
                if (!value.has(name)) {
                    problems.add("$at.$name is required")
                }
            }
            val properties: JsonValue.Obj? = obj["properties"] as? JsonValue.Obj
            if (properties != null) {
                value.fields.keys.forEach { key ->
                    val child: JsonValue? = properties[key]
                    if (child != null) {
                        checkSchema(child, value.fields.getValue(key), "$at.$key", depth + 1, problems)
                    } else if (obj.bool("additionalProperties") == false) {
                        problems.add("$at.$key is not allowed")
                    }
                }
            } else if (obj.bool("additionalProperties") == false && value.fields.isNotEmpty()) {
                problems.add("$at does not allow properties")
            }
        }

        is JsonValue.Str -> {
            numberOrNull(obj["minLength"])?.let { minimum ->
                if (value.value.length < minimum) problems.add("$at is shorter than ${minimum.toInt()}")
            }
            numberOrNull(obj["maxLength"])?.let { maximum ->
                if (value.value.length > maximum) problems.add("$at is longer than ${maximum.toInt()}")
            }
            (obj["pattern"] as? JsonValue.Str)?.let { pattern ->
                if (!Regex(pattern.value).containsMatchIn(value.value)) {
                    problems.add("$at does not match ${pattern.value}")
                }
            }
        }

        is JsonValue.Num -> {
            numberOrNull(obj["minimum"])?.let { minimum ->
                if (value.value < minimum) problems.add("$at is below ${minimum.toInt()}")
            }
            numberOrNull(obj["maximum"])?.let { maximum ->
                if (value.value > maximum) problems.add("$at is above ${maximum.toInt()}")
            }
        }

        else -> Unit
    }
}

private fun sameJson(left: JsonValue, right: JsonValue): Boolean =
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

private fun numberOrNull(value: JsonValue?): Double? = (value as? JsonValue.Num)?.value
