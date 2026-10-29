package com.eloykim.lyra.amp.protocol.registry

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.asObject
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Content digest for a module directory (port of `src/registry/content.ts`).
 *
 * A declaration that only records a path proves almost nothing. This module
 * hashes every file under the directory into a sorted manifest, then hashes the
 * manifest, so a declaration can bind the actual code. `verifyModuleContent`
 * (in `src/registry/registry.ts`, not ported here) recomputes it and reports
 * drift.
 *
 * ## Byte and UTF-8 semantics
 *
 * The reference reads each file as UTF-8 *text* (`readFile(..., "utf8")`) and
 * hashes the text's UTF-8 re-encoding, not the raw file bytes:
 *
 * - valid UTF-8 files therefore hash exactly like their bytes;
 * - malformed byte sequences become U+FFFD, matching Node's decoder;
 * - a leading BOM is kept as U+FEFF (neither implementation strips it).
 *
 * `File.readText(Charsets.UTF_8)` uses the JVM decoder's REPLACE action, which
 * is the same observable behaviour for the sequences this port tests.
 *
 * ## Blocking IO
 *
 * The reference returns promises because Node's `fs/promises` is asynchronous.
 * This port uses `java.io.File` synchronously: call [collectDirectory],
 * [digestDirectory] and [diffDirectory] from a background dispatcher on
 * Android, never from the main thread.
 *
 * ## Ordering
 *
 * The reference sorts names with `localeCompare`. Locale collation is
 * environment-dependent; this port uses Kotlin's `String.compareTo` (UTF-16
 * code-unit order), which agrees for the ASCII source trees the registry walks
 * and is deterministic across devices. Paths always use `/` as the separator.
 *
 * ## CAS refs
 *
 * Per-file `digest` values are bare lowercase SHA-256 hex, exactly as in the
 * reference. Directory digests are canonical `sha256:<64 hex>` CAS refs, which
 * is why [contentRef] and [isContentRef] exist: they describe the ref format the
 * context content store and registry declarations share, without changing what
 * [ContentEntry.digest] contains.
 */

/** One file under a module directory: POSIX-style relative path and bare hex digest. */
data class ContentEntry(
    val path: String,
    val digest: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "path" to JsonValue.Str(path),
        "digest" to JsonValue.Str(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): ContentEntry {
            val obj = value.asObject("content entry")
            return ContentEntry(
                path = obj.requireString("path"),
                digest = obj.requireString("digest"),
            )
        }
    }
}

/** Directory names skipped at every depth, matching `Skip` in the reference. */
private val SKIP_DIRECTORIES: Set<String> = setOf("node_modules", ".git", ".tscheck", "dist")

private val CONTENT_REF_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")

/**
 * Walk a module directory and return its sorted content manifest.
 *
 * Names are sorted per directory and directories are entered in that order, so
 * the result is deterministic. Symbolic links are skipped: Node's
 * `Dirent.isFile()`/`isDirectory()` are false for links, and following them
 * would let a module digest depend on something outside its tree.
 */
fun collectDirectory(root: File): List<ContentEntry> {
    val entries = ArrayList<ContentEntry>()
    walk(current = root, relative = "", out = entries)
    return entries
}

private fun walk(current: File, relative: String, out: MutableList<ContentEntry>) {
    val children = current.listFiles() ?: throw IOException("cannot read directory: $current")
    children.sortedBy { it.name }.forEach { child ->
        if (SKIP_DIRECTORIES.contains(child.name)) {
            return@forEach
        }
        // `Files.isSymbolicLink` is available on Android API 26+, and does not
        // follow the link (unlike `File.isDirectory`/`isFile`).
        if (runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)) {
            return@forEach
        }
        val path = if (relative.isEmpty()) child.name else "$relative/${child.name}"
        if (child.isDirectory) {
            walk(current = child, relative = path, out = out)
            return@forEach
        }
        if (!child.isFile) {
            return@forEach
        }
        out += ContentEntry(path = path, digest = Sha256.hex(readUtf8(child)))
    }
}

/**
 * Deterministic digest of a directory's current content.
 *
 * This is the canonical digest of the `ContentEntry` array, so it binds paths
 * and per-file digests in one value suitable for `module.contentDigest`.
 */
fun digestDirectory(root: File): String =
    CanonicalJson.digest(JsonValue.Arr(collectDirectory(root).map { it.toJson() }))

/**
 * Compare a directory against an expected manifest.
 *
 * Returns entries that differ in either direction: changed or added files carry
 * their actual digest, missing files carry the expected digest. Order is walk
 * order for actual files, then remaining expected entries in expected order.
 */
fun diffDirectory(root: File, expected: List<ContentEntry>): List<ContentEntry> {
    val actual = collectDirectory(root)
    val expectedByPath = LinkedHashMap<String, String>()
    expected.forEach { expectedByPath[it.path] = it.digest }

    val changed = ArrayList<ContentEntry>()
    actual.forEach { entry ->
        if (expectedByPath[entry.path] != entry.digest) {
            changed += entry
        }
        expectedByPath.remove(entry.path)
    }
    expectedByPath.forEach { (path, digest) -> changed += ContentEntry(path, digest) }
    return changed
}

/** UTF-8 text of a file, with malformed bytes replaced by U+FFFD. */
private fun readUtf8(file: File): String = file.readText(Charsets.UTF_8)

/** CAS ref for a UTF-8 text body: `sha256:<64 lowercase hex>`. */
fun contentRef(text: String): String = "sha256:" + Sha256.hex(text)

/** CAS ref for a byte body: `sha256:<64 lowercase hex>`. */
fun contentRef(bytes: ByteArray): String = "sha256:" + Sha256.hex(bytes)

/** Whether a string is a well-formed `sha256:<64 hex>` CAS ref. */
fun isContentRef(ref: String): Boolean = CONTENT_REF_PATTERN.matches(ref)

/** Recompute a text body's CAS ref and compare it without trusting the caller. */
fun verifyContentRef(ref: String, text: String): Boolean =
    isContentRef(ref) && ref == contentRef(text)

/** Recompute a byte body's CAS ref and compare it without trusting the caller. */
fun verifyContentRef(ref: String, bytes: ByteArray): Boolean =
    isContentRef(ref) && ref == contentRef(bytes)
