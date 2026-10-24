package com.eloykim.lyra.service

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 参数读取工具。
 *
 * 模型经常把数字写成字符串（"limit": "10"），这里统一做容错；
 * 缺参/类型错误抛 [IllegalArgumentException]，由 BasePhoneService 转成 `bad_args`。
 */

private fun JSONObject.raw(name: String): Any? =
    if (!has(name) || isNull(name)) null else opt(name)

private fun Any?.asText(): String = when (this) {
    null -> ""
    is String -> this
    else -> toString()
}

internal fun JSONObject.reqString(name: String): String {
    val text = raw(name).asText().trim()
    if (text.isEmpty()) throw IllegalArgumentException("缺少参数 $name")
    return text
}

internal fun JSONObject.optStringOr(name: String, default: String = ""): String {
    val text = raw(name).asText().trim()
    return text.ifEmpty { default }
}

internal fun JSONObject.reqInt(name: String): Int =
    raw(name).toIntOrNull(name) ?: throw IllegalArgumentException("缺少参数 $name")

internal fun JSONObject.optIntOr(name: String, default: Int): Int =
    raw(name)?.toIntOrNull(name) ?: default

internal fun JSONObject.reqLong(name: String): Long =
    raw(name).toLongOrNull(name) ?: throw IllegalArgumentException("缺少参数 $name")

internal fun JSONObject.optLongOr(name: String, default: Long): Long =
    raw(name)?.toLongOrNull(name) ?: default

internal fun JSONObject.optBoolOr(name: String, default: Boolean): Boolean {
    val value = raw(name) ?: return default
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
        else -> default
    }
}

internal fun JSONObject.reqStringList(name: String): List<String> {
    val value = raw(name) ?: throw IllegalArgumentException("缺少参数 $name")
    val items = when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it).asText() }
        is String -> value.split('\n', '|')
        else -> listOf(value.asText())
    }
    return items.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun Any?.toIntOrNull(name: String): Int? = when (this) {
    is Number -> toInt()
    is String -> trim().toIntOrNull()
    else -> null
}

private fun Any?.toLongOrNull(name: String): Long? = when (this) {
    is Number -> toLong()
    is String -> trim().toLongOrNull()
    else -> null
}
