package com.eloykim.lyra.amp.protocol.context

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * 文件存储只接受「规范目录内、直接位于该目录下」的普通文件。
 *
 * 对应参考实现 `src/context/fileStorePath.ts`：
 * 1. 先把引用解析成绝对路径，再判断父目录是否就是存储目录 —— 因此 `../x`
 *    或指向别处的绝对路径都会被拒绝；
 * 2. 路径必须存在且是普通文件；
 * 3. 符号链接一律拒绝（Node 用 `lstatSync().isFile()`，Java 的 `isFile` 会跟随
 *    链接，所以这里额外用 `Files.isSymbolicLink`）。
 *
 * Android 29 可用：`java.io.File` + `java.nio.file.Files.isSymbolicLink`
 * （API 26 起提供），不使用 `Path.of` / `Files.readString`。
 */
fun storedFile(dir: File, ref: String): File? = storedFile(dir, File(ref))

fun storedFile(dir: File, ref: File): File? {
    val canonicalDir: File = runCatching { dir.canonicalFile }.getOrNull() ?: return null
    val path: File = runCatching { ref.canonicalFile }.getOrNull() ?: return null
    if (path.parentFile?.path != canonicalDir.path) {
        return null
    }
    if (!path.isFile) {
        return null
    }
    if (runCatching { Files.isSymbolicLink(path.toPath()) }.getOrDefault(false)) {
        return null
    }
    return path
}

/**
 * 创建（如需要）并规范化存储目录；对应 `mkdirSync(dir, {recursive:true})`
 * 之后的 `realpathSync(dir)`。
 */
fun canonicalStoreDirectory(dir: File): File {
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
        throw IOException("cannot create file store directory: $dir")
    }
    return dir.canonicalFile
}
