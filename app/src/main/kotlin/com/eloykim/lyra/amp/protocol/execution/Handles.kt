package com.eloykim.lyra.amp.protocol.execution

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.Handle
import com.eloykim.lyra.amp.protocol.types.HandleKind
import com.eloykim.lyra.amp.protocol.types.HandleScope
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * Handle 分页（`src/execution/handles.ts` 的忠实移植）。
 *
 * 超过 token 预算的工具结果不会被截断：它变成一个 handle，模型通过
 * `read(handle, cursor)` 分页读取。分页必须是确定性的——同一游标永远返回同一窗口。
 */
data class HandlePage(
    val text: String,
    /** 下一窗口的游标；没有下一页时为 null。 */
    val next: Int? = null,
    val totalPages: Int,
    val tokens: Int,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "text" to jsonText(text),
        "next" to next?.let { jsonNumber(it) },
        "totalPages" to jsonNumber(totalPages),
        "tokens" to jsonNumber(tokens),
    )

    companion object {
        fun fromJson(value: JsonValue): HandlePage {
            val obj = value.asObject("handle page")
            return HandlePage(
                text = obj.requireString("text"),
                next = obj.int("next"),
                totalPages = obj.requireInt("totalPages"),
                tokens = obj.requireInt("tokens"),
            )
        }
    }
}

private const val CHARS_PER_TOKEN: Int = 4

private data class StoredHandle(
    val handle: Handle,
    val content: String,
    val createdAt: Long,
)

/**
 * 内容寻址的 handle 存储。
 *
 * handle 是不透明名字，不是能力：真实宿主里“拥有 id”不应足以读取它（scope 与
 * session 绑定在本参考实现之外，见架构 §11.1 T5）。
 *
 * 与参考实现一致，id 由 `kind:seq:content.length` 的 sha256 前 10 位派生；
 * `createdAt` 只用于宿主侧记账，不进入 wire 对象。
 */
class HandleStore {

    private val records = LinkedHashMap<String, StoredHandle>()
    private var seq = 0

    fun put(
        content: String,
        kind: HandleKind,
        tokens: Int,
        scope: HandleScope = HandleScope.SESSION,
        ttlMs: Long? = 900_000L,
    ): Handle {
        seq += 1
        val id = "h.${Sha256.hex("${kind.wire}:$seq:${content.length}").take(10)}"
        val handle = Handle(
            id = id,
            kind = kind,
            scope = scope,
            tokens = tokens,
            ttlMs = ttlMs,
        )
        records[id] = StoredHandle(handle = handle, content = content, createdAt = System.currentTimeMillis())
        return handle
    }

    fun has(id: String): Boolean = records.containsKey(id)

    fun get(id: String): Handle? = records[id]?.handle

    /**
     * 确定性分页：同一游标总是返回同一窗口。
     */
    fun read(id: String, cursor: Int, limitTokens: Int = 240): HandlePage {
        val record = records[id] ?: throw IllegalStateException("unknown handle: $id")
        val window = maxOf(1, limitTokens * CHARS_PER_TOKEN)
        val start = maxOf(0, minOf(cursor, record.content.length))
        val end = minOf(record.content.length, start + window)
        val text = record.content.substring(start, end)
        return HandlePage(
            text = text,
            next = if (end < record.content.length) end else null,
            totalPages = (record.content.length + window - 1) / window,
            tokens = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN,
        )
    }
}
