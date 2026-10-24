package com.eloykim.lyra.service.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.eloykim.lyra.accessibility.AutomationClient
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optLongOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqInt
import com.eloykim.lyra.service.reqString
import com.eloykim.lyra.vision.OcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文本服务：读屏、OCR、文字定位、输入框写入、剪贴板。
 *
 * 读屏走无障碍观察，OCR 走 ML Kit（中文 + 拉丁字母），
 * 二者都只处理当前屏幕或 app 缓存里的截图。
 */
class TextService(private val context: Context) : BasePhoneService() {

    override val id: String = "text"
    override val title: String = "文本"
    override val description: String =
        "读取屏幕文本、OCR 截图、用文字定位坐标、写入输入框、读写剪贴板。"

    private val ocr = OcrEngine()
    private val shotsDir: File get() = File(context.cacheDir, "shots")

    init {
        register(
            ServiceMethod(
                name = "readScreen",
                summary = "读取当前屏幕的元素与文本（带 obsId）",
                parameters = Schema.params(
                    "limit" to Schema.integer("最多返回的元素数", 1, 500),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> readScreen(args.optIntOr("limit", 200)) }

        register(
            ServiceMethod(
                name = "screenText",
                summary = "只返回当前屏幕的文本行",
                parameters = Schema.params(
                    "limit" to Schema.integer("最多文本行数", 1, 500),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> screenText(args.optIntOr("limit", 200)) }

        register(
            ServiceMethod(
                name = "ocr",
                summary = "识别截图文字；不传 path 时先截屏",
                parameters = Schema.params(
                    "path" to Schema.string("截图路径；省略则现场截屏"),
                    "maxLines" to Schema.integer("最多返回行数", 1, 300),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> ocr(args.optStringOr("path"), args.optIntOr("maxLines", 100)) }

        register(
            ServiceMethod(
                name = "findText",
                summary = "在截图里查找文字，返回匹配行的中心坐标",
                parameters = Schema.params(
                    "keyword" to Schema.string("要查找的文字"),
                    "path" to Schema.string("截图路径；省略则现场截屏"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> findText(args.reqString("keyword"), args.optStringOr("path")) }

        register(
            ServiceMethod(
                name = "input",
                summary = "向输入框写入文本；省略 nodeId 时选当前第一个输入框",
                parameters = Schema.params(
                    "text" to Schema.string("要写入的文本"),
                    "nodeId" to Schema.integer("输入框节点编号"),
                    "obsId" to Schema.integer("观察版本号"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            input(
                text = args.reqString("text").take(20_000),
                nodeId = args.optIntOr("nodeId", -1),
                obsId = args.optLongOr("obsId", 0L),
            )
        }

        register(
            ServiceMethod(
                name = "clear",
                summary = "清空指定输入框",
                parameters = Schema.params(
                    "nodeId" to Schema.integer("输入框节点编号"),
                    "obsId" to Schema.integer("观察版本号；0 表示不校验"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            setText(args.reqInt("nodeId"), args.optLongOr("obsId", 0L), "")
        }

        register(
            ServiceMethod(
                name = "clipboardRead",
                summary = "读取剪贴板文本",
                parameters = Schema.params(),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { clipboardRead() }

        register(
            ServiceMethod(
                name = "clipboardWrite",
                summary = "写入剪贴板",
                parameters = Schema.params(
                    "text" to Schema.string("要写入的文本"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args -> clipboardWrite(args.reqString("text").take(20_000)) }

        register(
            ServiceMethod(
                name = "status",
                summary = "读屏/OCR/剪贴板可用性",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private suspend fun readScreen(limit: Int): ServiceResult {
        val observed = observe() ?: return ServiceResult.fail(
            "automation_unavailable",
            "自动化服务未连接：请先开启 Lyra 的无障碍服务",
        )
        val observation = observed.data.optJSONObject("observation")
            ?: return ServiceResult.fail("bad_response", "观察结果缺少 observation")
        val elements = observation.optJSONArray("elements") ?: JSONArray()
        val lines = ArrayList<String>()
        val output = JSONArray()
        var count = 0
        for (index in 0 until elements.length()) {
            if (count >= limit) break
            val element = elements.optJSONObject(index) ?: continue
            val label = element.optString("text").ifBlank { element.optString("desc") }
            if (label.isBlank()) continue
            lines += label
            output.put(element)
            count++
        }
        val data = JSONObject()
            .put("obsId", observation.optLong("obsId"))
            .put("package", observation.optString("package"))
            .put("counts", observation.optJSONObject("counts"))
            .put("elements", output)
            .put("text", lines.joinToString("\n"))
            .put("count", count)
        return ServiceResult.ok("$count elements", data)
    }

    private suspend fun screenText(limit: Int): ServiceResult {
        val screen = readScreen(limit)
        if (!screen.ok) return screen
        return ServiceResult.ok(
            "screen text",
            JSONObject()
                .put("package", screen.data.optString("package"))
                .put("obsId", screen.data.optLong("obsId"))
                .put("text", screen.data.optString("text"))
                .put("count", screen.data.optInt("count")),
        )
    }

    private suspend fun ocr(path: String?, maxLines: Int): ServiceResult {
        val shot = resolveShot(path)
        if (!shot.ok) return shot
        val actualPath = shot.data.optString("path")
        val lines = withContext(Dispatchers.IO) {
            runCatching { ocr.recognize(actualPath) }.getOrNull()
        } ?: return ServiceResult.fail("ocr_failed", "OCR 识别失败：$actualPath")

        val array = JSONArray()
        lines.take(maxLines.coerceIn(1, 300)).forEach { line ->
            array.put(
                JSONObject()
                    .put("text", line.text)
                    .put("left", line.left)
                    .put("top", line.top)
                    .put("right", line.right)
                    .put("bottom", line.bottom)
                    .put("centerX", line.centerX)
                    .put("centerY", line.centerY),
            )
        }
        return ServiceResult.ok(
            "${lines.size} lines",
            JSONObject()
                .put("path", actualPath)
                .put("lines", array)
                .put("count", lines.size),
        )
    }

    private suspend fun findText(keyword: String, path: String?): ServiceResult {
        val shot = resolveShot(path)
        if (!shot.ok) return shot
        val actualPath = shot.data.optString("path")
        val lines = withContext(Dispatchers.IO) {
            runCatching { ocr.recognize(actualPath) }.getOrNull()
        } ?: return ServiceResult.fail("ocr_failed", "OCR 识别失败：$actualPath")

        val needle = keyword.replace(" ", "").lowercase()
        val matches = JSONArray()
        lines.forEach { line ->
            val normalized = line.text.replace(" ", "").lowercase()
            if (normalized.contains(needle)) {
                matches.put(
                    JSONObject()
                        .put("text", line.text)
                        .put("centerX", line.centerX)
                        .put("centerY", line.centerY)
                        .put("bounds", JSONArray().put(line.left).put(line.top).put(line.right).put(line.bottom)),
                )
            }
        }
        if (matches.length() == 0) {
            return ServiceResult.fail(
                "text_not_found",
                "截图里没有找到「$keyword」",
                JSONObject().put("path", actualPath),
            )
        }
        return ServiceResult.ok(
            "${matches.length()} matches",
            JSONObject()
                .put("path", actualPath)
                .put("keyword", keyword)
                .put("matches", matches),
        )
    }

    private suspend fun input(text: String, nodeId: Int, obsId: Long): ServiceResult {
        if (nodeId >= 0) {
            return setText(nodeId, obsId, text)
        }
        val observed = observe() ?: return ServiceResult.fail(
            "automation_unavailable",
            "自动化服务未连接：请先开启 Lyra 的无障碍服务",
        )
        val observation = observed.data.optJSONObject("observation")
            ?: return ServiceResult.fail("bad_response", "观察结果缺少 observation")
        val elements = observation.optJSONArray("elements") ?: JSONArray()
        var target: JSONObject? = null
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            if (element.optBoolean("editable")) {
                target = element
                break
            }
        }
        val editable = target ?: return ServiceResult.fail(
            "not_editable",
            "当前屏幕没有可输入的节点",
            JSONObject().put("package", observation.optString("package")),
        )
        return setText(
            editable.optInt("id"),
            observation.optLong("obsId"),
            text,
        )
    }

    private suspend fun setText(nodeId: Int, obsId: Long, text: String): ServiceResult {
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail("automation_unavailable", "自动化服务未连接")
        }
        val result = runCatching {
            ServiceResult.fromAutomation(AutomationClient.setText(nodeId, obsId, text))
        }.getOrElse { ServiceResult.fail("automation_error", it.message ?: "写入失败") }
        if (result.ok) {
            result.data.put("nodeId", nodeId).put("obsId", obsId).put("text", text)
        }
        return result
    }

    private suspend fun observe(): ServiceResult? {
        if (!AutomationClient.connected.value) return null
        return runCatching { ServiceResult.fromAutomation(AutomationClient.observe()) }
            .getOrElse { ServiceResult.fail("automation_error", it.message ?: "观察失败") }
    }

    /**
     * 解析截图路径：只允许 app 缓存 shots 目录，避免模型读取任意文件。
     */
    private suspend fun resolveShot(path: String?): ServiceResult {
        val provided = path?.trim().orEmpty()
        if (provided.isNotEmpty()) {
            val file = File(provided).canonicalFile
            val dir = shotsDir.canonicalFile
            if (!file.isFile || !file.path.startsWith(dir.path + File.separator)) {
                return ServiceResult.fail(
                    "invalid_path",
                    "只允许读取 Lyra 截图缓存里的文件：${dir.absolutePath}",
                )
            }
            return ServiceResult.ok("path", JSONObject().put("path", file.absolutePath))
        }
        if (!AutomationClient.connected.value) {
            return ServiceResult.fail("automation_unavailable", "自动化服务未连接")
        }
        val shot = runCatching { ServiceResult.fromAutomation(AutomationClient.screenshot()) }
            .getOrElse { ServiceResult.fail("automation_error", it.message ?: "截屏失败") }
        if (!shot.ok) return shot
        val actual = shot.data.optString("path")
        if (actual.isBlank()) return ServiceResult.fail("bad_response", "截屏没有返回路径")
        return ServiceResult.ok("path", JSONObject().put("path", actual))
    }

    private fun clipboardRead(): ServiceResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return ServiceResult.fail("clipboard_unavailable", "设备没有剪贴板服务")
        val clip = manager.primaryClip
        val text = if (clip == null || clip.itemCount == 0) {
            ""
        } else {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        }
        return ServiceResult.ok(
            if (text.isEmpty()) "clipboard empty" else "clipboard",
            JSONObject().put("text", text).put("length", text.length),
        )
    }

    private fun clipboardWrite(text: String): ServiceResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return ServiceResult.fail("clipboard_unavailable", "设备没有剪贴板服务")
        manager.setPrimaryClip(ClipData.newPlainText("Lyra", text))
        return ServiceResult.ok(
            "clipboard written",
            JSONObject().put("length", text.length),
        )
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("automationConnected", AutomationClient.connected.value)
            .put("ocrReady", true)
            .put("cachedShots", shotsDir.listFiles()?.count { it.isFile } ?: 0),
    )

    override fun close() {
        runCatching { ocr.close() }
    }
}
