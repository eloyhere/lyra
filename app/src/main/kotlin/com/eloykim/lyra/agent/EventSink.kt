package com.eloykim.lyra.agent

/**
 * AMP 事件出口（X-1）。
 *
 * 会话只负责「发生了什么」，落库与哈希链由宿主实现（生产环境是 LedgerStore）。
 * 默认 Noop，测试与无账本场景不需要额外分支。
 */
fun interface EventSink {

    suspend fun record(type: String, payload: String, refs: List<String>)

    companion object {
        val Noop = EventSink { _, _, _ -> }
    }
}

/** 最小 JSON 转义：payload 只承载事实字段，不承载用户可见文案。 */
internal fun jsonEscape(raw: String): String = buildString {
    raw.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append(' ') else append(ch)
        }
    }
}
