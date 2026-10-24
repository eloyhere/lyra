package com.eloykim.lyra.service.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import org.json.JSONObject

/**
 * Intent 服务：用标准 Android Intent 完成「打开链接 / 拨号盘 / 邮件 / 地图 / 分享 / 应用市场」。
 *
 * 只做拉起界面或系统分享，不直接拨号、不发邮件、不自动点击；
 * 具体发送动作仍由用户在目标应用里完成。
 */
class IntentService(private val context: Context) : BasePhoneService() {

    override val id: String = "intent"
    override val title: String = "意图"
    override val description: String =
        "打开网址、拨号盘、邮件、地图、系统分享和应用市场。"

    init {
        register(
            ServiceMethod(
                name = "openUrl",
                summary = "用浏览器打开 http/https 链接",
                parameters = Schema.params(
                    "url" to Schema.string("http 或 https 链接"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val url = args.reqString("url")
            val uri = Uri.parse(url)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                return@register ServiceResult.fail("bad_url", "只支持 http/https 链接")
            }
            start(Intent(Intent.ACTION_VIEW, uri), "browser")
        }

        register(
            ServiceMethod(
                name = "dial",
                summary = "打开拨号盘并填入号码（不直接拨出）",
                parameters = Schema.params(
                    "number" to Schema.string("电话号码"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val number = args.reqString("number")
            start(
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)),
                "dialer",
            )
        }

        register(
            ServiceMethod(
                name = "email",
                summary = "打开邮件应用并填好收件人/主题/正文",
                parameters = Schema.params(
                    "to" to Schema.string("收件人邮箱"),
                    "subject" to Schema.string("主题"),
                    "body" to Schema.string("正文"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val to = args.reqString("to")
            val subject = args.optStringOr("subject")
            val body = args.optStringOr("body")
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:${Uri.encode(to)}"),
            )
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
            start(intent, "email")
        }

        register(
            ServiceMethod(
                name = "map",
                summary = "用地图应用搜索一个地点",
                parameters = Schema.params(
                    "query" to Schema.string("地点/关键词"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val query = args.reqString("query")
            start(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=${Uri.encode(query)}"),
                ),
                "map",
            )
        }

        register(
            ServiceMethod(
                name = "share",
                summary = "拉起系统分享面板分享一段文字",
                parameters = Schema.params(
                    "text" to Schema.string("要分享的文字"),
                    "title" to Schema.string("分享面板标题"),
                ),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, args.reqString("text").take(10_000))
            }
            val chooser = Intent.createChooser(
                send,
                args.optStringOr("title", "分享"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(chooser)
                ServiceResult.ok("shared")
            }.getOrElse {
                ServiceResult.fail("no_app", it.message ?: "没有可用的分享应用")
            }
        }

        register(
            ServiceMethod(
                name = "market",
                summary = "打开应用市场详情页；没有市场时回退浏览器",
                parameters = Schema.params(
                    "package" to Schema.string("应用包名"),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val packageName = args.reqString("package")
            val market = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName"),
            )
            val opened = runCatching {
                context.startActivity(market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (opened) {
                ServiceResult.ok("market_opened", JSONObject().put("package", packageName))
            } else {
                start(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
                    ),
                    "browser",
                )
            }
        }

        register(
            ServiceMethod(
                name = "status",
                summary = "常用 Intent 是否有接收方",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) { status() }
    }

    private fun start(intent: Intent, target: String): ServiceResult {
        val packageManager = context.packageManager
        val resolved = if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        if (resolved == null) {
            return ServiceResult.fail("no_app", "设备上没有可处理该 Intent 的应用（$target）")
        }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ServiceResult.ok(
                "started",
                JSONObject().put("target", target).put("resolved", resolved.activityInfo?.packageName),
            )
        } catch (notFound: ActivityNotFoundException) {
            ServiceResult.fail("no_app", "设备上没有可处理该 Intent 的应用（$target）")
        } catch (t: Throwable) {
            ServiceResult.fail("start_failed", t.message ?: "无法启动目标应用")
        }
    }

    private fun status(): ServiceResult = ServiceResult.ok(
        "ok",
        JSONObject()
            .put("browser", resolvable(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))))
            .put("dialer", resolvable(Intent(Intent.ACTION_DIAL, Uri.parse("tel:10086"))))
            .put("email", resolvable(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:test@example.com"))))
            .put("map", resolvable(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test")))),
    )

    private fun resolvable(intent: Intent): Boolean {
        val packageManager = context.packageManager
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0)) != null
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0) != null
        }
    }
}
