package com.eloykim.lyra.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON Schema 小工具：让命令声明保持可读，同时与 tool calling 的 parameter schema 对齐。
 */
internal object Schema {

    fun string(description: String, enum: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", "string")
            put("description", description)
            if (!enum.isNullOrEmpty()) put("enum", JSONArray(enum))
        }

    fun integer(description: String, minimum: Int? = null, maximum: Int? = null): JSONObject =
        JSONObject().apply {
            put("type", "integer")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    fun number(description: String, minimum: Double? = null, maximum: Double? = null): JSONObject =
        JSONObject().apply {
            put("type", "number")
            put("description", description)
            minimum?.let { put("minimum", it) }
            maximum?.let { put("maximum", it) }
        }

    fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    fun array(description: String, items: JSONObject): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("description", description)
            .put("items", items)

    /**
     * 构造 object schema。
     *
     * 用法：`Schema.params("x" to Schema.integer("x"), required = listOf("x"))`
     */
    fun params(
        vararg entries: Pair<String, JSONObject>,
        required: List<String> = emptyList(),
    ): JSONObject {
        val properties = JSONObject()
        entries.forEach { (name, schema) -> properties.put(name, schema) }
        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
    }
}
