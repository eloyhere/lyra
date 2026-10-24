package com.eloykim.lyra.amp

/**
 * 极简 JSON 写入器。
 *
 * 不用 org.json（core:amp 要保持纯 Kotlin，可 JVM 单测），
 * 也不用 kotlinx.serialization（AGP 内置 Kotlin 下引入序列化插件会增加构建风险）。
 */
sealed interface JVal {

    data class Obj(val fields: List<Pair<String, JVal>>) : JVal
    data class Arr(val items: List<JVal>) : JVal
    data class Str(val value: String) : JVal
    data class Num(val value: Double) : JVal
    data class Bool(val value: Boolean) : JVal
    data object Null : JVal
}

fun obj(vararg fields: Pair<String, Any?>): JVal.Obj =
    JVal.Obj(fields.mapNotNull { (key, value) -> value?.let { key to jval(it) } })

fun arr(items: List<Any?>): JVal.Arr = JVal.Arr(items.map { jval(it) })

fun jval(value: Any?): JVal = when (value) {
    null -> JVal.Null
    is JVal -> value
    is String -> JVal.Str(value)
    is Boolean -> JVal.Bool(value)
    is Int -> JVal.Num(value.toDouble())
    is Long -> JVal.Num(value.toDouble())
    is Double -> JVal.Num(value)
    is Float -> JVal.Num(value.toDouble())
    is List<*> -> JVal.Arr(value.map { jval(it) })
    else -> JVal.Str(value.toString())
}

/** 稳定输出：字段顺序即声明顺序，便于计算 digest。 */
fun JVal.render(): String = buildString { renderTo(this) }

private fun JVal.renderTo(sb: StringBuilder) {
    when (this) {
        is JVal.Null -> sb.append("null")
        is JVal.Bool -> sb.append(if (value) "true" else "false")
        is JVal.Num -> sb.append(formatNumber(value))
        is JVal.Str -> sb.append(quote(value))
        is JVal.Arr -> {
            sb.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) sb.append(',')
                item.renderTo(sb)
            }
            sb.append(']')
        }

        is JVal.Obj -> {
            sb.append('{')
            fields.forEachIndexed { index, (key, value) ->
                if (index > 0) sb.append(',')
                sb.append(quote(key)).append(':')
                value.renderTo(sb)
            }
            sb.append('}')
        }
    }
}

private fun formatNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    return if (value == Math.floor(value) && Math.abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        // 保留最多 3 位，去掉尾随 0
        String.format(java.util.Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }
}

private fun quote(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') {
                sb.append("\\u").append(String.format(java.util.Locale.US, "%04x", ch.code))
            } else {
                sb.append(ch)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}
