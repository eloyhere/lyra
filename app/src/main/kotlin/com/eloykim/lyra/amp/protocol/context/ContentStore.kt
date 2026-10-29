package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.semantic.Sha256
import java.io.File

/**
 * 内容寻址的细节存储（对应 `src/context/contentStore.ts`）。
 *
 * ref 是内容自身的 sha256，因此相同细节天然去重，读取时可以校验而不是信任。
 */
interface ContentStore {

    fun put(content: String): String

    fun get(ref: String): String?
}

/** 仅匹配 `sha256:<64 位小写十六进制>`。 */
internal val CONTENT_REF_PATTERN = Regex("^sha256:[0-9a-f]{64}$")

class MemoryContentStore : ContentStore {

    private val contents: MutableMap<String, String> = LinkedHashMap()

    override fun put(content: String): String {
        val ref: String = "sha256:" + Sha256.hex(content)
        contents[ref] = content
        return ref
    }

    override fun get(ref: String): String? = contents[ref]

    fun size(): Int = contents.size
}

class FileContentStore(dir: File) : ContentStore {

    private val dir: File = canonicalStoreDirectory(dir)

    override fun put(content: String): String {
        val hash: String = Sha256.hex(content)
        val ref: String = "sha256:$hash"
        val path: File = File(this.dir, "$hash.txt")
        // 对应 `writeFileSync(path, content, { flag: "wx" })`：已存在时保留原文件。
        if (!path.exists() && path.createNewFile()) {
            path.writeText(content, Charsets.UTF_8)
        }
        if (get(ref) == null) {
            throw IllegalStateException("invalid content store file: $ref")
        }
        return ref
    }

    override fun get(ref: String): String? {
        if (!CONTENT_REF_PATTERN.matches(ref)) {
            return null
        }
        val path: File = File(dir, ref.substring("sha256:".length) + ".txt")
        if (!path.exists()) {
            return null
        }
        val stored: File = storedFile(dir, path) ?: return null
        val content: String = stored.readText(Charsets.UTF_8)
        if ("sha256:" + Sha256.hex(content) != ref) {
            throw IllegalStateException("content store integrity mismatch: $ref")
        }
        return content
    }
}
