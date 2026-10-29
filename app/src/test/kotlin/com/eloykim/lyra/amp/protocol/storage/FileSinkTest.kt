package com.eloykim.lyra.amp.protocol.storage

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.ledger.EntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class FileSinkTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "amp-sink-" + UUID.randomUUID()).apply { mkdirs() }

    @Test
    fun `a file sink appends and reopens a durable chain`() {
        val dir = tempDir()
        try {
            val path = File(dir, "nested/log.jsonl")
            val sink = FileAppendSink(path)
            assertTrue(path.parentFile.isDirectory)

            val log = AppendLog.open(sink, "a.file")
            log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.1")))
            log.append(EntryKind.CLAIM, jsonObj("id" to jsonText("c.1")))
            assertEquals(2, sink.readAll().size)

            val reopened = AppendLog.open(FileAppendSink(path), "a.file")
            assertEquals(2, reopened.count)
            assertEquals(log.tip, reopened.tip)
            assertTrue(reopened.verify().ok)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `blank lines are dropped when reading`() {
        val dir = tempDir()
        try {
            val path = File(dir, "log.jsonl")
            path.writeText("\nfirst\n\nsecond\r\n", Charsets.UTF_8)
            val sink = FileAppendSink(path)
            assertEquals(listOf("first", "second"), sink.readAll())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing file reads as an empty sink`() {
        val dir = tempDir()
        try {
            val sink = FileAppendSink(File(dir, "does-not-exist.jsonl"))
            assertEquals(emptyList<String>(), sink.readAll())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a partial final line fails to reopen instead of being skipped`() {
        val dir = tempDir()
        try {
            val path = File(dir, "log.jsonl")
            val log = AppendLog.open(FileAppendSink(path), "a.file")
            log.append(EntryKind.EVENT, jsonObj("id" to jsonText("ev.1")))

            // Simulate a crash midway through the next append: bytes on disk,
            // no terminating newline, not a complete JSON object.
            FileOutputStream(path, true).use { output ->
                output.write("""{"seq":1,"kind":"event"""".toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val error = assertThrows(AppendLogError::class.java) {
                AppendLog.open(FileAppendSink(path), "a.file")
            }
            assertTrue(error.reasons.any { it.contains("JSON") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `UTF-8 payloads survive a file round-trip`() {
        val dir = tempDir()
        try {
            val path = File(dir, "log.jsonl")
            val log = AppendLog.open(FileAppendSink(path), "a.file")
            val entry = log.append(
                EntryKind.EVENT,
                jsonObj("id" to jsonText("ev.中文"), "note" to jsonText("错误率上升")),
            )
            assertTrue(entry.hash.startsWith("sha256:"))

            val reopened = AppendLog.open(FileAppendSink(path), "a.file")
            val payload = reopened.entries().single().payload as JsonValue.Obj
            assertEquals("错误率上升", payload.string("note"))
            assertEquals(entry.hash, reopened.tip)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an empty path is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            FileAppendSink("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileAppendSink(File("   "))
        }
    }
}
