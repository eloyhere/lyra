package com.eloykim.lyra.service.file

import android.content.Context
import android.os.Environment
import com.eloykim.lyra.permission.PermissionBroker
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文件服务。
 *
 * 只在应用的私有目录 `filesDir/files` 下读写，路径会 canonicalize 阻止 `..` 越界；
 * 不支持任意系统路径或外部存储，避免绕过 Android 分区存储。
 */
class FileService(private val context: Context) : BasePhoneService() {

    override val id: String = "file"
    override val title: String = "文件"
    override val description: String =
        "在 Lyra 私有文件区读写文本文件；external/ 前缀写公共下载目录，缺少权限会先申请。"

    private val root: File = File(context.filesDir, "files").apply { mkdirs() }
    private val externalRoot: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "Lyra",
    )
    private val externalStorageRoot: File = Environment.getExternalStorageDirectory()

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出目录内容",
                parameters = Schema.params(
                    "path" to Schema.string("相对路径，默认根目录"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> list(args.optStringOr("path")) }

        register(
            ServiceMethod(
                name = "read",
                summary = "读取文本文件",
                parameters = Schema.params(
                    "path" to Schema.string("相对路径"),
                    "maxChars" to Schema.integer("最多字符数", 100, 100_000),
                    required = listOf("path"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> read(args.reqString("path"), args.optIntOr("maxChars", 20_000)) }

        register(
            ServiceMethod(
                name = "write",
                summary = "写入文本文件（自动建父目录）",
                parameters = Schema.params(
                    "path" to Schema.string("相对路径"),
                    "content" to Schema.string("文本内容"),
                    "append" to Schema.boolean("追加而不是覆盖"),
                    required = listOf("path", "content"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            write(
                path = args.reqString("path"),
                content = args.reqString("content"),
                append = args.optBoolOr("append", false),
            )
        }

        register(
            ServiceMethod(
                name = "mkdir",
                summary = "创建目录",
                parameters = Schema.params(
                    "path" to Schema.string("相对路径"),
                    required = listOf("path"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> mkdir(args.reqString("path")) }

        register(
            ServiceMethod(
                name = "delete",
                summary = "删除文件或目录",
                parameters = Schema.params(
                    "path" to Schema.string("相对路径"),
                    "recursive" to Schema.boolean("目录是否递归删除"),
                    required = listOf("path"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> delete(args.reqString("path"), args.optBoolOr("recursive", false)) }

        register(
            ServiceMethod(
                name = "info",
                summary = "查看文件/目录信息",
                parameters = Schema.params(
                    "path" to Schema.string("相对路径，默认根目录"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> info(args.optStringOr("path")) }

        register(
            ServiceMethod(
                name = "search",
                summary = "按文件名搜索私有文件区",
                parameters = Schema.params(
                    "query" to Schema.string("文件名关键词"),
                    "limit" to Schema.integer("最多返回", 1, 100),
                    required = listOf("query"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> search(args.reqString("query"), args.optIntOr("limit", 20)) }
    }

    private suspend fun list(path: String): ServiceResult = withContext(Dispatchers.IO) {
        val dir = try { resolveFor(path) } catch (t: Throwable) { return@withContext fail(t) }
        if (!dir.exists()) return@withContext ServiceResult.fail("not_found", "没有路径 $path")
        if (!dir.isDirectory) return@withContext ServiceResult.fail("not_directory", "$path 不是目录")
        val children = dir.listFiles().orEmpty()
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(200)
        val array = JSONArray()
        children.forEach { child -> array.put(child.toJson()) }
        ServiceResult.ok(
            "${children.size} 项",
            JSONObject()
                .put("path", relative(dir))
                .put("entries", array)
                .put("count", children.size),
        )
    }

    private suspend fun read(path: String, maxChars: Int): ServiceResult =
        withContext(Dispatchers.IO) {
            val file = try { resolveFor(path) } catch (t: Throwable) { return@withContext fail(t) }
            if (!file.exists()) return@withContext ServiceResult.fail("not_found", "没有文件 $path")
            if (!file.isFile) return@withContext ServiceResult.fail("not_file", "$path 不是文件")
            if (file.length() > MAX_READ_BYTES) {
                return@withContext ServiceResult.fail(
                    "file_too_large",
                    "文件 ${file.length()} 字节，超过读取上限",
                )
            }
            val text = runCatching { file.readText() }
                .getOrElse { return@withContext fail(it) }
            if ('\u0000' in text) {
                return@withContext ServiceResult.fail("binary_file", "这是二进制文件，不能按文本读取")
            }
            val safeMax = maxChars.coerceIn(100, 100_000)
            val truncated = text.length > safeMax
            ServiceResult.ok(
                "读取 ${relative(file)}",
                JSONObject()
                    .put("path", relative(file))
                    .put("content", text.take(safeMax))
                    .put("truncated", truncated)
                    .put("chars", text.length)
                    .put("bytes", file.length()),
            )
        }

    private suspend fun write(path: String, content: String, append: Boolean): ServiceResult =
        withContext(Dispatchers.IO) {
            val bytes = content.toByteArray()
            if (bytes.size > MAX_WRITE_BYTES) {
                return@withContext ServiceResult.fail(
                    "content_too_large",
                    "内容 ${bytes.size} 字节，超过写入上限",
                )
            }
            val file = try { resolveFor(path) } catch (t: Throwable) { return@withContext fail(t) }
            if (file.isDirectory) {
                return@withContext ServiceResult.fail("is_directory", "$path 是目录")
            }
            file.parentFile?.mkdirs()
            runCatching {
                if (append) file.appendText(content) else file.writeText(content)
            }.getOrElse { return@withContext fail(it) }
            ServiceResult.ok(
                if (append) "已追加 ${relative(file)}" else "已写入 ${relative(file)}",
                file.toJson(),
            )
        }

    private suspend fun mkdir(path: String): ServiceResult = withContext(Dispatchers.IO) {
        val dir = try { resolveFor(path) } catch (t: Throwable) { return@withContext fail(t) }
        if (dir == root) return@withContext ServiceResult.ok("根目录已存在", dir.toJson())
        if (dir.exists()) {
            return@withContext if (dir.isDirectory) {
                ServiceResult.ok("目录已存在", dir.toJson())
            } else {
                ServiceResult.fail("not_directory", "$path 是文件")
            }
        }
        if (!dir.mkdirs()) return@withContext ServiceResult.fail("mkdir_failed", "创建目录失败")
        ServiceResult.ok("已创建 ${relative(dir)}", dir.toJson())
    }

    private suspend fun delete(path: String, recursive: Boolean): ServiceResult =
        withContext(Dispatchers.IO) {
            val target = try { resolveFor(path) } catch (t: Throwable) { return@withContext fail(t) }
            if (target == root) {
                return@withContext ServiceResult.fail("forbidden", "不能删除私有文件区根目录")
            }
            if (!target.exists()) return@withContext ServiceResult.fail("not_found", "没有路径 $path")
            if (target.isDirectory && !recursive && target.list()?.isNotEmpty() == true) {
                return@withContext ServiceResult.fail("directory_not_empty", "目录非空，需要 recursive")
            }
            val ok = runCatching { target.deleteRecursively() }.getOrDefault(false)
            if (ok) {
                ServiceResult.ok("已删除 ${relative(target)}", JSONObject().put("path", path))
            } else {
                ServiceResult.fail("delete_failed", "删除失败")
            }
        }

    private suspend fun info(path: String): ServiceResult = withContext(Dispatchers.IO) {
        val target = try { resolveFor(path) } catch (t: Throwable) { return@withContext fail(t) }
        ServiceResult.ok("文件信息", target.toJson())
    }

    private suspend fun search(query: String, limit: Int): ServiceResult =
        withContext(Dispatchers.IO) {
            val needle = query.trim().lowercase()
            if (needle.isBlank()) return@withContext ServiceResult.fail("bad_args", "query 为空")
            val result = ArrayList<File>()
            root.walkTopDown()
                .onEnter { it == root || !it.name.startsWith(".") }
                .filter { it.isFile && it.name.lowercase().contains(needle) }
                .take(limit.coerceIn(1, 100))
                .forEach { result += it }
            val array = JSONArray()
            result.forEach { array.put(it.toJson()) }
            ServiceResult.ok(
                "找到 ${result.size} 个文件",
                JSONObject().put("query", query).put("files", array).put("count", result.size),
            )
        }

    private suspend fun resolveFor(path: String): File {
        if (isExternal(path) && !PermissionBroker.ensureExternalStorage(context)) {
            throw ExternalPermissionRequired()
        }
        return resolve(path)
    }

    private fun isExternal(path: String): Boolean =
        path.trim().startsWith("external/", ignoreCase = true) || File(path.trim()).isAbsolute

    private fun resolve(path: String): File {
        val raw = path.trim()
        if (raw.startsWith("external/", ignoreCase = true)) {
            val relative = raw.substringAfter('/').trimStart('/')
            val candidate = File(externalRoot, relative).canonicalFile
            ensureWithin(candidate, externalRoot)
            return candidate
        }
        if (File(raw).isAbsolute) {
            val candidate = File(raw).canonicalFile
            ensureWithin(candidate, externalStorageRoot)
            return candidate
        }
        val candidate = File(root, raw.trimStart('/')).canonicalFile
        ensureWithin(candidate, root)
        return candidate
    }

    private fun ensureWithin(candidate: File, base: File) {
        val rootCanonical = base.canonicalFile
        val prefix = rootCanonical.path + File.separator
        if (candidate != rootCanonical && !candidate.path.startsWith(prefix)) {
            throw SecurityException("路径越界")
        }
    }

    private fun relative(file: File): String {
        val path = runCatching {
            val canonical = file.canonicalPath
            when {
                canonical.startsWith(root.canonicalPath + File.separator) ->
                    file.relativeTo(root).path
                canonical.startsWith(externalRoot.canonicalPath + File.separator) ->
                    "external/" + file.relativeTo(externalRoot).path
                else -> file.absolutePath
            }
        }.getOrDefault(file.name)
        return path.ifBlank { "." }
    }

    private class ExternalPermissionRequired : SecurityException("需要外部存储权限")

    private fun File.toJson(): JSONObject = JSONObject()
        .put("path", relative(this))
        .put("name", name)
        .put("directory", isDirectory)
        .put("exists", exists())
        .put("size", if (isFile) length() else 0L)
        .put("modifiedAt", if (exists()) lastModified() else 0L)

    private fun fail(t: Throwable): ServiceResult = ServiceResult.fail(
        when (t) {
            is ExternalPermissionRequired -> "permission_required"
            is SecurityException -> "path_forbidden"
            else -> "file_error"
        },
        t.message ?: "文件操作失败",
    )

    private companion object {
        const val MAX_READ_BYTES = 512 * 1024L
        const val MAX_WRITE_BYTES = 512 * 1024
    }
}
