package com.eloykim.lyra.service.photo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqLong
import com.eloykim.lyra.service.reqString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 照片服务：读取系统相册的照片列表/详情，并可打开或分享。
 *
 * Android 13+ 需要 READ_MEDIA_IMAGES（或用户选择的照片权限）；没有权限时
 * 返回 permission_required，由用户去设置页授权。
 */
class PhotoService(private val context: Context) : BasePhoneService() {

    override val id: String = "photo"
    override val title: String = "照片"
    override val description: String =
        "列出系统相册照片；用 Canvas 画图并保存到应用目录；按需读取图片 base64。"

    private val imageDir = File(context.filesDir, "images").apply { mkdirs() }

    override fun requiredPermissions(method: String): List<String> = when (method) {
        "list", "info", "open", "share" -> if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        else -> emptyList()
    }

    init {
        register(
            ServiceMethod(
                name = "list",
                summary = "列出最近的照片",
                parameters = Schema.params(
                    "limit" to Schema.integer("数量", 1, 100),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> list(args.optIntOr("limit", 30)) }

        register(
            ServiceMethod(
                name = "info",
                summary = "查看单张照片信息",
                parameters = Schema.params(
                    "id" to Schema.integer("照片 MediaStore id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args -> info(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "open",
                summary = "用系统图库打开照片",
                parameters = Schema.params(
                    "id" to Schema.integer("照片 MediaStore id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args -> open(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "share",
                summary = "分享照片",
                parameters = Schema.params(
                    "id" to Schema.integer("照片 MediaStore id"),
                    required = listOf("id"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args -> share(args.reqLong("id")) }

        register(
            ServiceMethod(
                name = "draw",
                summary = "用 Canvas 按 JSON 指令画图并保存到应用目录",
                parameters = Schema.params(
                    "spec" to Schema.string(
                        "JSON：{width,height,background,elements:[{type:rect|circle|ellipse|line|text|polygon|path|bezier,...}]}；" +
                            "path.commands 支持 moveTo/lineTo/quadTo/cubicTo/close",
                    ),
                    "includeBase64" to Schema.boolean("显式要求时才返回图片 base64"),
                    required = listOf("spec"),
                ),
                risk = ServiceRisk.INPUT,
            ),
        ) { args ->
            draw(
                spec = args.reqString("spec"),
                includeBase64 = args.optBoolOr("includeBase64", false),
            )
        }

        register(
            ServiceMethod(
                name = "read",
                summary = "读取应用目录图片；includeBase64=true 才把内容放入上下文",
                parameters = Schema.params(
                    "path" to Schema.string("图片相对/绝对路径"),
                    "includeBase64" to Schema.boolean("显式要求时才返回 base64"),
                    required = listOf("path"),
                ),
                risk = ServiceRisk.SENSITIVE,
            ),
        ) { args ->
            readImage(
                path = args.optStringOr("path"),
                includeBase64 = args.optBoolOr("includeBase64", false),
            )
        }
    }

    private suspend fun draw(spec: String, includeBase64: Boolean): ServiceResult =
        withContext(Dispatchers.IO) {
            val root = runCatching { JSONObject(spec) }.getOrElse {
                return@withContext ServiceResult.fail("bad_spec", "spec 不是合法 JSON")
            }
            val width = root.optInt("width", 1024).coerceIn(16, 4096)
            val height = root.optInt("height", 1024).coerceIn(16, 4096)
            if (width.toLong() * height > 8_000_000L) {
                return@withContext ServiceResult.fail("image_too_large", "画布超过 800 万像素")
            }
            val elements = root.optJSONArray("elements") ?: JSONArray()
            if (elements.length() > 500) {
                return@withContext ServiceResult.fail("too_many_elements", "元素超过 500 个")
            }

            // 只执行我们生成的固定 canvas 脚本；spec 以 base64 注入，避免 </script> 逃逸。
            val html = buildCanvasHtml(root.toString(), width, height)
            val png = renderHtmlCanvas(html)
                ?: return@withContext ServiceResult.fail(
                    "canvas_render_failed",
                    "HTML canvas 渲染失败",
                )
            val stamp = System.currentTimeMillis()
            val file = File(imageDir, "draw_$stamp.png")
            val htmlFile = File(imageDir, "draw_$stamp.html")
            val saved = runCatching {
                file.writeBytes(png)
                htmlFile.writeText(html)
            }.isSuccess
            if (!saved) return@withContext ServiceResult.fail("save_failed", "图片写入失败")
            imageResult(
                file = file,
                width = width,
                height = height,
                includeBase64 = includeBase64,
                drawn = elements.length(),
                htmlFile = htmlFile,
                canvasSpec = root.toString(),
            )
        }

    /**
     * 用离屏 WebView 执行 HTML canvas 绘制，取回 PNG。
     *
     * 页面里没有网络、文件访问和用户 HTML；脚本只读固定 spec 并调用 canvas API。
     */
    private suspend fun renderHtmlCanvas(html: String): ByteArray? =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<String?>()
            val view = runCatching { WebView(context.applicationContext) }.getOrNull()
                ?: return@withContext null
            try {
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkLoads = true
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.domStorageEnabled = false
                view.settings.loadsImagesAutomatically = false
                view.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun postMessage(dataUrl: String) {
                            deferred.complete(dataUrl)
                        }
                    },
                    "LyraCanvas",
                )
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                val dataUrl = withTimeoutOrNull(6_000) { deferred.await() }
                    ?: return@withContext null
                val comma = dataUrl.indexOf(',')
                if (comma < 0) return@withContext null
                Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            } finally {
                view.removeJavascriptInterface("LyraCanvas")
                view.destroy()
            }
        }

    private fun buildCanvasHtml(specJson: String, width: Int, height: Int): String {
        val encoded = Base64.encodeToString(specJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <style>html,body{margin:0;padding:0;background:#fff}canvas{display:block}</style>
            </head><body>
            <canvas id="c" width="$width" height="$height"></canvas>
            <script>
            (function(){
              "use strict";
              var spec = JSON.parse(atob("$encoded"));
              var canvas = document.getElementById("c");
              var ctx = canvas.getContext("2d");
              var W = canvas.width, H = canvas.height;
              function n(o, keys, d) {
                for (var i = 0; i < keys.length; i++) {
                  var k = keys[i];
                  if (o[k] !== undefined && o[k] !== null) return Number(o[k]) || 0;
                }
                return d || 0;
              }
              function color(v, fallback) {
                return (typeof v === "string" && v.trim()) ? v : fallback;
              }
              function apply(el) {
                var stroke = el.stroke || el.strokeColor || "";
                if (stroke) {
                  ctx.strokeStyle = stroke;
                  ctx.lineWidth = n(el, ["strokeWidth", "lineWidth"], 3);
                  return "stroke";
                }
                ctx.fillStyle = color(el.fill || el.color || el.background, "#000");
                return "fill";
              }
              function pathFromCommands(commands) {
                var p = new Path2D();
                for (var i = 0; i < commands.length; i++) {
                  var c = commands[i] || {};
                  var op = String(c.op || "").toLowerCase();
                  if (op === "moveto" || op === "m") {
                    p.moveTo(n(c, ["x"]), n(c, ["y"]));
                  } else if (op === "lineto" || op === "l") {
                    p.lineTo(n(c, ["x"]), n(c, ["y"]));
                  } else if (op === "quadto" || op === "q" || op === "quadratic") {
                    p.quadraticCurveTo(
                      n(c, ["cx", "x1", "controlX"]), n(c, ["cy", "y1", "controlY"]),
                      n(c, ["x", "x2", "endX"]), n(c, ["y", "y2", "endY"])
                    );
                  } else if (op === "cubicto" || op === "c" || op === "curve" || op === "bezier") {
                    p.bezierCurveTo(
                      n(c, ["x1", "control1X"]), n(c, ["y1", "control1Y"]),
                      n(c, ["x2", "control2X"]), n(c, ["y2", "control2Y"]),
                      n(c, ["x", "x3", "endX"]), n(c, ["y", "y3", "endY"])
                    );
                  } else if (op === "close" || op === "z") {
                    p.closePath();
                  }
                }
                return p;
              }
              try {
                ctx.fillStyle = color(spec.background, "#fff");
                ctx.fillRect(0, 0, W, H);
                var elements = spec.elements || [];
                for (var i = 0; i < elements.length; i++) {
                  var el = elements[i] || {};
                  var type = String(el.type || "").toLowerCase();
                  ctx.save();
                  if (type === "rect" || type === "rectangle" || type === "square") {
                    var x = n(el, ["x", "left"]), y = n(el, ["y", "top"]);
                    var w = n(el, ["w", "width"]), h = n(el, ["h", "height"]);
                    var how = apply(el);
                    ctx.beginPath();
                    if (ctx.roundRect) ctx.roundRect(x, y, w, h, n(el, ["radius", "cornerRadius", "rx"]));
                    else ctx.rect(x, y, w, h);
                    if (how === "stroke") ctx.stroke(); else ctx.fill();
                  } else if (type === "circle" || type === "dot") {
                    var cx = n(el, ["cx", "x", "centerX"]);
                    var cy = n(el, ["cy", "y", "centerY"]);
                    var r = Math.max(1, n(el, ["r", "radius"]));
                    var howC = apply(el);
                    ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2);
                    if (howC === "stroke") ctx.stroke(); else ctx.fill();
                  } else if (type === "ellipse" || type === "oval") {
                    var ex = n(el, ["cx", "x", "centerX"]);
                    var ey = n(el, ["cy", "y", "centerY"]);
                    var rx = Math.max(1, n(el, ["rx", "radiusX", "radius"]));
                    var ry = Math.max(1, n(el, ["ry", "radiusY", "radius"]));
                    var howE = apply(el);
                    ctx.beginPath(); ctx.ellipse(ex, ey, rx, ry, 0, 0, Math.PI * 2);
                    if (howE === "stroke") ctx.stroke(); else ctx.fill();
                  } else if (type === "line") {
                    ctx.strokeStyle = color(el.color || el.stroke, "#000");
                    ctx.lineWidth = n(el, ["strokeWidth", "width"], 3);
                    var s = el.start || [], e = el.end || [];
                    ctx.beginPath();
                    ctx.moveTo(n(el, ["x1", "startX"]) || Number(s[0]) || 0, n(el, ["y1", "startY"]) || Number(s[1]) || 0);
                    ctx.lineTo(n(el, ["x2", "endX"]) || Number(e[0]) || 0, n(el, ["y2", "endY"]) || Number(e[1]) || 0);
                    ctx.stroke();
                  } else if (type === "text" || type === "label") {
                    var size = Math.max(8, Math.min(512, n(el, ["size", "textSize", "fontSize"], 48)));
                    ctx.fillStyle = color(el.color || el.fill, "#000");
                    ctx.font = (el.bold ? "bold " : "") + size + "px sans-serif";
                    var align = String(el.align || "left").toLowerCase();
                    ctx.textAlign = align === "center" ? "center" : (align === "right" ? "right" : "left");
                    var text = el.text || el.content || "";
                    if (text) ctx.fillText(text, n(el, ["x", "left"]), n(el, ["y", "baseline"]));
                  } else if (type === "polygon") {
                    var points = el.points || [];
                    if (points.length >= 3) {
                      var howP = apply(el);
                      ctx.beginPath();
                      for (var j = 0; j < points.length; j++) {
                        var pt = points[j] || [];
                        if (j === 0) ctx.moveTo(Number(pt[0]) || 0, Number(pt[1]) || 0);
                        else ctx.lineTo(Number(pt[0]) || 0, Number(pt[1]) || 0);
                      }
                      ctx.closePath();
                      if (howP === "stroke") ctx.stroke(); else ctx.fill();
                    }
                  } else if (type === "path") {
                    if ((el.commands || []).length) {
                      var howPath = apply(el);
                      var p = pathFromCommands(el.commands || []);
                      if (howPath === "stroke") ctx.stroke(p); else ctx.fill(p);
                    }
                  } else if (type === "bezier" || type === "curve") {
                    var st = el.start || [], c1 = el.control1 || el.c1 || [];
                    var c2 = el.control2 || el.c2 || [], en = el.end || [];
                    var howB = apply(el);
                    ctx.beginPath();
                    ctx.moveTo(Number(st[0]) || 0, Number(st[1]) || 0);
                    ctx.bezierCurveTo(
                      Number(c1[0]) || 0, Number(c1[1]) || 0,
                      Number(c2[0]) || 0, Number(c2[1]) || 0,
                      Number(en[0]) || 0, Number(en[1]) || 0
                    );
                    if (howB === "stroke") ctx.stroke(); else ctx.fill();
                  }
                  ctx.restore();
                }
              } catch (e) {
                // 绘制失败也返回当前画布，至少避免聊天里出现空白占位。
              }
              window.LyraCanvas.postMessage(canvas.toDataURL("image/png"));
            })();
            </script></body></html>
        """.trimIndent()
    }

    private fun drawElement(canvas: Canvas, paint: Paint, element: JSONObject): Boolean {
        val type = element.optString("type").lowercase()
        paint.reset()
        paint.isAntiAlias = true
        return when (type) {
            "rect", "rectangle", "square" -> {
                applyStyle(paint, element, Color.BLACK)
                val x = number(element, "x", "left")
                val y = number(element, "y", "top")
                val w = number(element, "w", "width")
                val h = number(element, "h", "height")
                val rect = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
                val radius = number(element, "radius", "cornerRadius", "rx").toFloat()
                if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint)
                else canvas.drawRect(rect, paint)
                true
            }

            "circle", "dot" -> {
                applyStyle(paint, element, Color.BLACK)
                val cx = number(element, "cx", "x", "centerX").toFloat()
                val cy = number(element, "cy", "y", "centerY").toFloat()
                val radius = number(element, "r", "radius").toFloat().coerceAtLeast(1f)
                canvas.drawCircle(cx.toFloat(), cy.toFloat(), radius, paint)
                true
            }

            "ellipse", "oval" -> {
                applyStyle(paint, element, Color.BLACK)
                val cx = number(element, "cx", "x", "centerX").toFloat()
                val cy = number(element, "cy", "y", "centerY").toFloat()
                val rx = number(element, "rx", "radiusX", "radius").toFloat().coerceAtLeast(1f)
                val ry = number(element, "ry", "radiusY", "radius").toFloat().coerceAtLeast(1f)
                canvas.drawOval(
                    RectF(
                        (cx - rx).toFloat(),
                        (cy - ry).toFloat(),
                        (cx + rx).toFloat(),
                        (cy + ry).toFloat(),
                    ),
                    paint,
                )
                true
            }

            "line" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = number(element, "strokeWidth", "width", default = 3.0).toFloat()
                paint.color = parseColor(element.optString("color").ifBlank {
                    element.optString("stroke")
                }, Color.BLACK)
                val start = element.optJSONArray("start")
                val end = element.optJSONArray("end")
                val x1 = start?.optDouble(0, 0.0) ?: number(element, "x1", "startX")
                val y1 = start?.optDouble(1, 0.0) ?: number(element, "y1", "startY")
                val x2 = end?.optDouble(0, 0.0) ?: number(element, "x2", "endX")
                val y2 = end?.optDouble(1, 0.0) ?: number(element, "y2", "endY")
                canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), paint)
                true
            }

            "text", "label" -> {
                paint.style = Paint.Style.FILL
                paint.color = parseColor(element.optString("color").ifBlank {
                    element.optString("fill")
                }, Color.BLACK)
                paint.textSize = number(element, "size", "textSize", "fontSize", default = 48.0)
                    .toFloat()
                    .coerceIn(8f, 512f)
                paint.typeface = if (element.optBoolean("bold", false)) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
                paint.textAlign = when (element.optString("align").lowercase()) {
                    "center" -> Paint.Align.CENTER
                    "right" -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                val text = element.optString("text").ifBlank { element.optString("content") }
                if (text.isBlank()) return false
                canvas.drawText(
                    text,
                    number(element, "x", "left").toFloat(),
                    number(element, "y", "baseline").toFloat(),
                    paint,
                )
                true
            }

            "polygon" -> {
                applyStyle(paint, element, Color.BLACK)
                val points = element.optJSONArray("points") ?: return false
                if (points.length() < 3) return false
                val path = Path()
                for (index in 0 until points.length()) {
                    val point = points.optJSONArray(index) ?: continue
                    val x = point.optDouble(0, 0.0).toFloat()
                    val y = point.optDouble(1, 0.0).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                canvas.drawPath(path, paint)
                true
            }

            "path" -> {
                val commands = element.optJSONArray("commands") ?: return false
                if (commands.length() == 0) return false
                applyStyle(paint, element, Color.BLACK)
                val path = Path()
                for (index in 0 until commands.length()) {
                    val command = commands.optJSONObject(index) ?: continue
                    when (command.optString("op").lowercase()) {
                        "moveto", "m" -> path.moveTo(
                            number(command, "x").toFloat(),
                            number(command, "y").toFloat(),
                        )

                        "lineto", "l" -> path.lineTo(
                            number(command, "x").toFloat(),
                            number(command, "y").toFloat(),
                        )

                        "quadto", "q", "quadratic" -> path.quadTo(
                            number(command, "cx", "x1", "controlX").toFloat(),
                            number(command, "cy", "y1", "controlY").toFloat(),
                            number(command, "x", "x2", "endX").toFloat(),
                            number(command, "y", "y2", "endY").toFloat(),
                        )

                        "cubicto", "c", "curve", "bezier" -> path.cubicTo(
                            number(command, "x1", "control1X").toFloat(),
                            number(command, "y1", "control1Y").toFloat(),
                            number(command, "x2", "control2X").toFloat(),
                            number(command, "y2", "control2Y").toFloat(),
                            number(command, "x", "x3", "endX").toFloat(),
                            number(command, "y", "y3", "endY").toFloat(),
                        )

                        "close", "z" -> path.close()
                    }
                }
                canvas.drawPath(path, paint)
                true
            }

            "bezier", "curve" -> {
                val start = element.optJSONArray("start") ?: return false
                val control1 = element.optJSONArray("control1")
                    ?: element.optJSONArray("c1") ?: return false
                val control2 = element.optJSONArray("control2")
                    ?: element.optJSONArray("c2") ?: return false
                val end = element.optJSONArray("end") ?: return false
                applyStyle(paint, element, Color.BLACK)
                val path = Path()
                path.moveTo(start.optDouble(0, 0.0).toFloat(), start.optDouble(1, 0.0).toFloat())
                path.cubicTo(
                    control1.optDouble(0, 0.0).toFloat(),
                    control1.optDouble(1, 0.0).toFloat(),
                    control2.optDouble(0, 0.0).toFloat(),
                    control2.optDouble(1, 0.0).toFloat(),
                    end.optDouble(0, 0.0).toFloat(),
                    end.optDouble(1, 0.0).toFloat(),
                )
                canvas.drawPath(path, paint)
                true
            }

            else -> false
        }
    }

    private fun number(element: JSONObject, vararg keys: String, default: Double = 0.0): Double {
        keys.forEach { key ->
            if (element.has(key) && !element.isNull(key)) {
                return element.optDouble(key, default)
            }
        }
        return default
    }

    private fun applyStyle(paint: Paint, element: JSONObject, fallback: Int) {
        val stroke = element.optString("stroke").ifBlank { element.optString("strokeColor") }
        if (stroke.isNotBlank()) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = number(element, "strokeWidth", "lineWidth", default = 3.0).toFloat()
            paint.color = parseColor(stroke, fallback)
        } else {
            paint.style = Paint.Style.FILL
            val fill = element.optString("fill").ifBlank {
                element.optString("color").ifBlank {
                    element.optString("background")
                }
            }
            paint.color = parseColor(fill, fallback)
        }
    }

    private fun parseColor(value: String, fallback: Int): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(fallback)

    private fun imageResult(
        file: File,
        width: Int,
        height: Int,
        includeBase64: Boolean,
        drawn: Int,
        htmlFile: File? = null,
        canvasSpec: String,
    ): ServiceResult {
        val canvasMarkdown = "```canvas\n$canvasSpec\n```"
        val data = JSONObject()
            .put("path", file.absolutePath)
            .put("url", imageUrl(file))
            .put("markdown", canvasMarkdown)
            .put("canvas", canvasSpec)
            .put("width", width)
            .put("height", height)
            .put("elementsDrawn", drawn)
            .put("renderer", "html-canvas")
            .put("bytes", file.length())
        htmlFile?.let {
            data.put("htmlPath", it.absolutePath)
            data.put("htmlUrl", "https://lyra.local/canvas?path=${Uri.encode(it.absolutePath)}")
        }
        if (includeBase64) {
            if (file.length() <= MAX_BASE64_BYTES) {
                data.put("mimeType", "image/png")
                data.put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            } else {
                data.put("base64Omitted", true)
            }
        }
        return ServiceResult.ok("图片已保存到应用目录", data)
    }

    private suspend fun readImage(path: String, includeBase64: Boolean): ServiceResult =
        withContext(Dispatchers.IO) {
            val file = runCatching { resolveImage(path) }.getOrElse {
                return@withContext ServiceResult.fail("bad_path", it.message ?: "路径不合法")
            }
            if (!file.isFile) return@withContext ServiceResult.fail("not_found", "没有图片 $path")
            if (file.length() > MAX_BASE64_BYTES) {
                return@withContext ServiceResult.fail(
                    "image_too_large",
                    "图片超过 ${MAX_BASE64_BYTES / 1024}KB，不能放入上下文",
                )
            }
            val data = JSONObject()
                .put("path", file.absolutePath)
                .put("url", imageUrl(file))
                .put("size", file.length())
                .put("mimeType", mimeOf(file))
            if (includeBase64) {
                data.put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            }
            ServiceResult.ok(
                if (includeBase64) "图片已转换为 base64" else "只返回图片路径（未读取内容）",
                data,
            )
        }

    private fun resolveImage(path: String): File {
        val clean = path.trim().removePrefix("file://")
        val base = if (File(clean).isAbsolute) File(clean) else File(context.filesDir, clean)
        val file = base.canonicalFile
        val root = context.filesDir.canonicalFile
        val prefix = root.path + File.separator
        if (file != root && !file.path.startsWith(prefix)) {
            throw SecurityException("图片不在应用目录")
        }
        return file
    }

    private fun imageUrl(file: File): String =
        "https://lyra.local/image?path=${Uri.encode(file.absolutePath)}"

    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private fun list(limit: Int): ServiceResult {
        val permission = missingPermission()
        if (permission != null) return permission
        val projection = PROJECTION
        val items = JSONArray()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val max = limit.coerceIn(1, 100)
            while (cursor.moveToNext() && items.length() < max) {
                items.put(cursor.toJson())
            }
        } ?: return ServiceResult.fail("query_failed", "相册查询失败")
        return ServiceResult.ok(
            "${items.length()} 张照片",
            JSONObject().put("photos", items).put("count", items.length()),
        )
    }

    private fun info(id: Long): ServiceResult {
        val permission = missingPermission()
        if (permission != null) return permission
        val item = find(id) ?: return ServiceResult.fail("not_found", "没有照片 $id")
        return ServiceResult.ok("照片信息", item)
    }

    private fun open(id: Long): ServiceResult {
        val permission = missingPermission()
        if (permission != null) return permission
        val item = find(id) ?: return ServiceResult.fail("not_found", "没有照片 $id")
        val uri = Uri.parse(item.optString("uri"))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.optString("mimeType", "image/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ServiceResult.ok("已打开照片", item)
        }.getOrElse {
            ServiceResult.fail("open_failed", it.message ?: "打开照片失败")
        }
    }

    private fun share(id: Long): ServiceResult {
        val permission = missingPermission()
        if (permission != null) return permission
        val item = find(id) ?: return ServiceResult.fail("not_found", "没有照片 $id")
        val uri = Uri.parse(item.optString("uri"))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.optString("mimeType", "image/*")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享照片").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            ServiceResult.ok("已打开分享面板", item)
        }.getOrElse {
            ServiceResult.fail("share_failed", it.message ?: "分享照片失败")
        }
    }

    private fun find(id: Long): JSONObject? {
        if (missingPermission() != null) return null
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            PROJECTION,
            "${MediaStore.Images.Media._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.toJson()
        }
        return null
    }

    private fun android.database.Cursor.toJson(): JSONObject {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Images.Media._ID))
        val mime = getString(getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE))
            ?: "image/*"
        return JSONObject()
            .put("id", id)
            .put("name", getString(getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)))
            .put("uri", ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id,
            ).toString())
            .put("mimeType", mime)
            .put("size", getLong(getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)))
            .put("width", getInt(getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)))
            .put("height", getInt(getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)))
            .put(
                "dateAdded",
                getLong(getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000L,
            )
            .put(
                "album",
                getString(getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)),
            )
    }

    private fun missingPermission(): ServiceResult? {
        val names = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val granted = names.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted && Build.VERSION.SDK_INT >= 34) {
            if (context.checkSelfPermission(
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
        }
        if (granted) return null
        return ServiceResult.fail(
            "permission_required",
            "没有照片读取权限，请到系统设置授予 Lyra 照片访问权限",
        )
    }

    private companion object {
        const val MAX_BASE64_BYTES = 512 * 1024L

        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
    }
}
