package com.eloykim.lyra.amp.protocol.registry

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class ContentTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "amp-content-" + UUID.randomUUID()).apply { mkdirs() }

    private fun write(root: File, relative: String, text: String) {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    private fun tree(): File {
        val root = tempDir()
        write(root, "a.txt", "alpha")
        write(root, "b/c.txt", "gamma")
        write(root, "b/d.txt", "delta")
        write(root, "z.txt", "omega")
        write(root, "node_modules/ignored.txt", "no")
        write(root, "dist/ignored.txt", "no")
        write(root, ".git/ignored", "no")
        write(root, ".tscheck/ignored", "no")
        return root
    }

    @Test
    fun `collectDirectory sorts per directory, skips build dirs and uses slash paths`() {
        val root = tree()
        try {
            val entries = collectDirectory(root)
            assertEquals(listOf("a.txt", "b/c.txt", "b/d.txt", "z.txt"), entries.map { it.path })
            assertEquals(Sha256.hex("alpha"), entries[0].digest)
            assertEquals(Sha256.hex("gamma"), entries[1].digest)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `digestDirectory is the canonical digest of the manifest`() {
        val root = tree()
        try {
            val expectedManifest = buildString {
                append('[')
                append("""{"digest":"${Sha256.hex("alpha")}","path":"a.txt"},""")
                append("""{"digest":"${Sha256.hex("gamma")}","path":"b/c.txt"},""")
                append("""{"digest":"${Sha256.hex("delta")}","path":"b/d.txt"},""")
                append("""{"digest":"${Sha256.hex("omega")}","path":"z.txt"}""")
                append(']')
            }
            val digest = digestDirectory(root)
            assertEquals("sha256:" + Sha256.hex(expectedManifest), digest)
            assertTrue(isContentRef(digest))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `UTF-8 text hashes its bytes and malformed bytes become replacement characters`() {
        val root = tempDir()
        try {
            write(root, "unicode.txt", "错误率上升")
            val unicode = File(root, "unicode.txt")
            assertEquals(Sha256.hex("错误率上升"), Sha256.hex(unicode.readText(Charsets.UTF_8)))
            assertEquals(
                "sha256:" + Sha256.hex("错误率上升"),
                contentRef("错误率上升"),
            )

            val malformed = File(root, "bad.bin")
            malformed.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            val decoded = malformed.readText(Charsets.UTF_8)
            assertEquals("\uFFFD(", decoded)
            assertEquals(Sha256.hex("\uFFFD("), collectDirectory(root).first { it.path == "bad.bin" }.digest)

            val bom = File(root, "bom.txt")
            bom.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(Charsets.UTF_8))
            assertEquals("\uFEFFhi", bom.readText(Charsets.UTF_8))
            assertEquals(Sha256.hex("\uFEFFhi"), collectDirectory(root).first { it.path == "bom.txt" }.digest)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an empty file hashes the empty text`() {
        val root = tempDir()
        try {
            File(root, "empty.txt").writeText("")
            assertEquals(
                listOf(ContentEntry("empty.txt", Sha256.hex(""))),
                collectDirectory(root),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `diffDirectory reports changed, added and missing entries`() {
        val root = tempDir()
        try {
            write(root, "a.txt", "changed")
            write(root, "b.txt", "kept")
            write(root, "new.txt", "new")

            val expected = listOf(
                ContentEntry("a.txt", Sha256.hex("old")),
                ContentEntry("b.txt", Sha256.hex("kept")),
                ContentEntry("gone.txt", Sha256.hex("gone")),
            )
            val changed = diffDirectory(root, expected)
            assertEquals(
                listOf("a.txt", "new.txt", "gone.txt"),
                changed.map { it.path },
            )
            assertEquals(Sha256.hex("changed"), changed[0].digest)
            assertEquals(Sha256.hex("new"), changed[1].digest)
            assertEquals(Sha256.hex("gone"), changed[2].digest)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `symlinks are not followed`() {
        val root = tempDir()
        try {
            write(root, "real.txt", "x")
            val link = File(root, "link.txt")
            val created = runCatching {
                Files.createSymbolicLink(link.toPath(), File(root, "real.txt").toPath())
            }.isSuccess
            Assume.assumeTrue("current filesystem cannot create symlinks", created)

            assertEquals(listOf("real.txt"), collectDirectory(root).map { it.path })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `CAS refs are sha256 over UTF-8 text or bytes`() {
        val bytes = "hello".toByteArray(Charsets.UTF_8)
        assertEquals("sha256:" + Sha256.hex("hello"), contentRef("hello"))
        assertEquals("sha256:" + Sha256.hex(bytes), contentRef(bytes))
        assertTrue(isContentRef(contentRef("x")))
        assertFalse(isContentRef("sha256:ABC"))
        assertFalse(isContentRef("sha256:" + "a".repeat(63)))
        assertTrue(verifyContentRef(contentRef("hello"), "hello"))
        assertFalse(verifyContentRef(contentRef("hello"), "other"))
        assertFalse(verifyContentRef("not-a-ref", "hello"))
    }

    @Test
    fun `content entries round-trip and reject missing fields`() {
        val entry = ContentEntry("src/a.kt", Sha256.hex("code"))
        assertEquals(entry, ContentEntry.fromJson(entry.toJson()))
        assertEquals(
            jsonObj("path" to JsonValue.Str(entry.path), "digest" to JsonValue.Str(entry.digest)),
            entry.toJson(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ContentEntry.fromJson(jsonObj("path" to JsonValue.Str("only-path")))
        }
    }

    @Test
    fun `a missing directory fails instead of hashing an empty tree`() {
        val missing = File(tempDir(), "missing")
        assertThrows(IOException::class.java) { collectDirectory(missing) }
    }
}
