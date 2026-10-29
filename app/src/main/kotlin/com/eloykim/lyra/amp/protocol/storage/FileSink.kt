package com.eloykim.lyra.amp.protocol.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable JSONL sink for the append-only logs (port of
 * `src/storage/fileAppendSink.ts`).
 *
 * This is a host adapter, not part of the domain: it uses the filesystem and
 * therefore lives in `storage`, behind the [AppendSink] port. Each line is one
 * complete JSON object; `readAll` drops blank lines but never rewrites a line.
 *
 * The sink only promises that `append` returns after the bytes are handed to the
 * OS; call `FileOutputStream.fd.sync()` at a higher level if the durability
 * requirement is stronger. A crash in the middle of a write leaves a partial
 * last line, which [AppendLog.open] rejects rather than skipping — that is the
 * intended fail-closed behaviour.
 *
 * Android 29 safe: `java.io.File` only, no `java.nio.file.Path.of`.
 */
class FileAppendSink(val path: File) : AppendSink {

    constructor(path: String) : this(File(path))

    init {
        require(path.path.trim().isNotEmpty()) { "FileAppendSink requires a non-empty path" }
        // `mkdirSync(dirname(path), {recursive:true})`, without requiring the
        // caller to create the parent first.
        val directory = path.absoluteFile.parentFile
        if (directory != null &&
            !directory.isDirectory &&
            !directory.mkdirs() &&
            !directory.isDirectory
        ) {
            throw IOException("cannot create append-log directory: $directory")
        }
    }

    override fun append(line: String) {
        // One write per line keeps the file append-only and avoids a partial
        // interleaving when two lines are written back to back.
        FileOutputStream(path, true).use { output ->
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    override fun readAll(): List<String> {
        if (!path.exists()) {
            return emptyList()
        }
        return path.readText(Charsets.UTF_8)
            .split(LINE_BREAK)
            .filter { line -> line.isNotEmpty() }
    }

    private companion object {
        /** `/\r?\n/`: CRLF and LF both terminate a line. */
        val LINE_BREAK: Regex = Regex("\\r?\\n")
    }
}
