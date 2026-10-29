package com.eloykim.lyra.attention

import com.eloykim.lyra.amp.protocol.semantic.Sha256
import java.io.File

/** 事前 token 估算：只用于发租约，不参与结算（结算用厂商 usage 回填）。 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    text.forEach { ch ->
        if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF || ch.code in 0xFF00..0xFFEF) {
            cjk++
        } else {
            other++
        }
    }
    return (cjk / 1.7f + other / 4f).toInt().coerceAtLeast(1)
}

data class HandleRef(
    val id: String,
    val kind: String,
    val tokens: Int,
    val scope: String,
    val digest: String,
    val totalChunks: Int,
    val nextCursor: String?,
)

data class HandlePage(
    val id: String,
    val content: String,
    val cursor: String,
    val nextCursor: String?,
)

/**
 * 大结果句柄（AMP §5.1 降级阶梯的最后一格）。
 *
 * 内容寻址：id 由 sha256 内容派生，同一份内容永远同一个 id；
 * 分页确定性：同一 cursor 永远返回同一页，便于模型重试与审计。
 */
interface HandleStore {
    fun put(kind: String, content: String, scope: String = "task", chunkChars: Int = 800): HandleRef
    fun read(id: String, cursor: String? = null, limit: Int = 2): HandlePage?
}

class InMemoryHandleStore : HandleStore {

    private val chunks = HashMap<String, List<String>>()
    private val kinds = HashMap<String, String>()

    override fun put(kind: String, content: String, scope: String, chunkChars: Int): HandleRef {
        val digest = Sha256.digestString(content)
        val id = "h.${digest.removePrefix("sha256:").take(12)}"
        val parts = content.chunked(chunkChars.coerceAtLeast(1))
        chunks[id] = parts
        kinds[id] = kind
        return HandleRef(
            id = id,
            kind = kind,
            tokens = estimateTokens(content),
            scope = scope,
            digest = digest,
            totalChunks = parts.size,
            nextCursor = if (parts.size > 2) "2" else null,
        )
    }

    override fun read(id: String, cursor: String?, limit: Int): HandlePage? {
        val parts = chunks[id] ?: return null
        val start = (cursor?.toIntOrNull() ?: 0).coerceIn(0, parts.size)
        val end = (start + limit.coerceAtLeast(1)).coerceAtMost(parts.size)
        val next = if (end < parts.size) end.toString() else null
        return HandlePage(id, parts.subList(start, end).joinToString(""), start.toString(), next)
    }
}

/** 文件版：内容落在 `<dir>/<sha256 前 12>.txt`，重启后可读。 */
class FileHandleStore(private val dir: File) : HandleStore {

    private val index = HashMap<String, HandleRef>()
    private val chunkCharsById = HashMap<String, Int>()

    override fun put(kind: String, content: String, scope: String, chunkChars: Int): HandleRef {
        val digest = Sha256.digestString(content)
        val id = "h.${digest.removePrefix("sha256:").take(12)}"
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${id.removePrefix("h.")}.txt")
        if (!file.exists()) file.writeText(content, Charsets.UTF_8)
        // 分页参数必须持久化，否则重启后切块边界变了，拼不回原文
        File(dir, "${id.removePrefix("h.")}.meta").writeText(
            "$kind\n${chunkChars.coerceAtLeast(1)}\n${scope}\n$digest",
            Charsets.UTF_8,
        )

        val total = content.chunked(chunkChars.coerceAtLeast(1)).size
        val ref = HandleRef(
            id = id,
            kind = kind,
            tokens = estimateTokens(content),
            scope = scope,
            digest = digest,
            totalChunks = total,
            nextCursor = if (total > 2) "2" else null,
        )
        index[id] = ref
        chunkCharsById[id] = chunkChars.coerceAtLeast(1)
        return ref
    }

    override fun read(id: String, cursor: String?, limit: Int): HandlePage? {
        val file = File(dir, "${id.removePrefix("h.")}.txt")
        if (!file.exists()) return null
        val meta = File(dir, "${id.removePrefix("h.")}.meta")
        if (!meta.exists()) return null
        val metaLines = meta.readLines()
        val ref = index[id] ?: HandleRef(
            id = id,
            kind = metaLines.getOrElse(0) { "unknown" },
            tokens = estimateTokens(file.readText(Charsets.UTF_8)),
            scope = metaLines.getOrElse(2) { "task" },
            digest = metaLines.getOrElse(3) { "sha256:" + "0".repeat(64) },
            totalChunks = 0,
            nextCursor = null,
        )
        val content = file.readText(Charsets.UTF_8)
        val chunk = chunkCharsById[id] ?: metaLines.getOrNull(1)?.toIntOrNull() ?: 800
        val parts = content.chunked(chunk)
        val start = (cursor?.toIntOrNull() ?: 0).coerceIn(0, parts.size)
        val end = (start + limit.coerceAtLeast(1)).coerceAtMost(parts.size)
        val next = if (end < parts.size) end.toString() else null
        return HandlePage(ref.id, parts.subList(start, end).joinToString(""), start.toString(), next)
    }
}
