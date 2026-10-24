package com.eloykim.lyra.service.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 截图服务。
 *
 * 截图由无障碍服务的 `takeScreenshot` 完成，因此需要：
 * - 无障碍服务已连接；
 * - Android 11+；
 * - 当前不是 Lyra 自身界面（策略拒绝）。
 *
 * `capture` 只返回 app 私有缓存里的 PNG 路径；`captureBase64` 为多模态模型
 * 提供缩放后的 JPEG/PNG base64，避免把几 MB 的原图直接塞进上下文。
 */
class ScreenshotService(private val context: Context) : BasePhoneService() {

    override val id: String = "screenshot"
    override val title: String = "截图"
    override val description: String =
        "截取当前屏幕；返回 PNG 路径，或缩放/压缩后的 base64 供视觉模型使用。"

    private val shotsDir: File get() = File(context.cacheDir, "shots")

    init {
        register(
            ServiceMethod(
                name = "capture",
                summary = "截屏并保存为 PNG，返回 path / width / height / bytes",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { capture() }

        register(
            ServiceMethod(
                name = "captureBase64",
                summary = "截屏并返回 base64；默认 JPEG、最长边 1024",
                parameters = Schema.params(
                    "maxDimension" to Schema.integer("最长边像素上限", 128, 4_096),
                    "quality" to Schema.integer("JPEG 质量", 1, 100),
                    "format" to Schema.string("输出格式", listOf("jpeg", "png")),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            captureBase64(
                maxDimension = args.optIntOr("maxDimension", 1_024),
                quality = args.optIntOr("quality", 82),
                format = args.optStringOr("format", "jpeg").lowercase(),
            )
        }

        register(
            ServiceMethod(
                name = "latest",
                summary = "最近一张缓存截图",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { latest() }

        register(
            ServiceMethod(
                name = "list",
                summary = "列出缓存截图（最新的在前）",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 50),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> list(args.optIntOr("limit", 10)) }

        register(
            ServiceMethod(
                name = "status",
                summary = "截图可用性与缓存数量",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun capture(): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail(
                "automation_unavailable",
                "自动化服务未连接：请先开启 Lyra 的无障碍服务",
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ServiceResult.fail("unsupported", "截屏需要 Android 11 及以上")
        }
        val result = runCatching { ServiceResult.fromAutomation(AutomationClient.screenshot()) }
            .getOrElse { ServiceResult.fail("automation_error", it.message ?: "截屏调用失败") }
        if (!result.ok) return result

        val path = result.data.optString("path")
        if (path.isBlank()) {
            return ServiceResult.fail("bad_response", "截屏没有返回文件路径")
        }
        val file = File(path)
        val data = JSONObject()
            .put("path", path)
            .put("width", result.data.optInt("width"))
            .put("height", result.data.optInt("height"))
            .put("bytes", file.length())
            .put("capturedAt", file.lastModified())
        return ServiceResult.ok("captured", data)
    }

    private suspend fun captureBase64(
        maxDimension: Int,
        quality: Int,
        format: String,
    ): ServiceResult {
        val shot = capture()
        if (!shot.ok) return shot
        val path = shot.data.optString("path")
        val encoded = withContext(Dispatchers.IO) {
            encode(path, maxDimension.coerceIn(128, 4_096), quality.coerceIn(1, 100), format)
        } ?: return ServiceResult.fail("decode_failed", "无法解码截图：$path")

        return ServiceResult.ok(
            "captured",
            JSONObject()
                .put("base64", encoded.base64)
                .put("mime", encoded.mime)
                .put("width", encoded.width)
                .put("height", encoded.height)
                .put("bytes", encoded.bytes)
                .put("path", path),
        )
    }

    private fun encode(path: String, maxDimension: Int, quality: Int, format: String): Encoded? {
        val original = BitmapFactory.decodeFile(path) ?: return null
        val scaled = scaleDown(original, maxDimension)
        val png = format == "png"
        val output = ByteArrayOutputStream()
        val ok = scaled.compress(
            if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            if (png) 100 else quality,
            output,
        )
        val bytes = output.toByteArray()
        val width = scaled.width
        val height = scaled.height
        if (scaled !== original) scaled.recycle()
        original.recycle()
        if (!ok) return null
        return Encoded(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mime = if (png) "image/png" else "image/jpeg",
            width = width,
            height = height,
            bytes = bytes.size,
        )
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private suspend fun latest(): ServiceResult {
        val files = shotFiles()
        val file = files.firstOrNull()
            ?: return ServiceResult.fail("no_screenshot", "还没有截图，先调用 screenshot.capture")
        return ServiceResult.ok("latest", fileJson(file))
    }

    private fun list(limit: Int): ServiceResult {
        val files = shotFiles().take(limit.coerceIn(1, 50))
        val array = JSONArray()
        files.forEach { array.put(fileJson(it)) }
        return ServiceResult.ok(
            "${files.size} screenshots",
            JSONObject().put("screenshots", array).put("count", files.size),
        )
    }

    private fun status(): ServiceResult {
        val files = shotFiles()
        return ServiceResult.ok(
            "ok",
            JSONObject()
                .put("automationConnected", AutomationClient.connected.value)
                .put("supported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("cached", files.size)
                .put("latest", files.firstOrNull()?.absolutePath),
        )
    }

    private fun shotFiles(): List<File> {
        val dir = shotsDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.extension.equals("png", true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    private fun fileJson(file: File): JSONObject {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return JSONObject()
            .put("path", file.absolutePath)
            .put("name", file.name)
            .put("bytes", file.length())
            .put("width", options.outWidth)
            .put("height", options.outHeight)
            .put("capturedAt", file.lastModified())
    }

    private data class Encoded(
        val base64: String,
        val mime: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
    )
}
