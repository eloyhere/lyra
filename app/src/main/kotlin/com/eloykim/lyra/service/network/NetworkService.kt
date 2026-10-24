package com.eloykim.lyra.service.network

import android.content.Context
import android.net.wifi.WifiManager
import com.eloykim.lyra.network.HttpAccessException
import com.eloykim.lyra.network.HttpClient
import com.eloykim.lyra.network.HttpRequest
import com.eloykim.lyra.network.HttpResponse
import com.eloykim.lyra.network.NetworkStateReader
import com.eloykim.lyra.service.BasePhoneService
import com.eloykim.lyra.service.Schema
import com.eloykim.lyra.service.ServiceMethod
import com.eloykim.lyra.service.ServiceResult
import com.eloykim.lyra.service.ServiceRisk
import com.eloykim.lyra.service.optBoolOr
import com.eloykim.lyra.service.optIntOr
import com.eloykim.lyra.service.optStringOr
import com.eloykim.lyra.service.reqString
import org.json.JSONObject

/**
 * 网络服务：只看连接状态、发起 HTTP 请求；Wi-Fi/数据开关受系统限制，
 * 不能直接改的设置会打开系统面板让用户操作。
 *
 * 职责边界：网络状态与请求都在这里；设备音量/电池等归 DeviceService。
 */
class NetworkService(private val context: Context) : BasePhoneService() {

    override val id: String = "network"
    override val title: String = "网络"
    override val description: String =
        "查看网络/Wi-Fi/移动数据状态，发起 HTTP 请求，打开系统网络面板（不能绕过系统直接开关）。"

    private val http = HttpClient()
    private val stateReader = NetworkStateReader(context)

    init {
        register(
            ServiceMethod(
                name = "status",
                summary = "当前网络连接快照（类型/IP/DNS/计费）",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            ServiceResult.ok("ok", stateReader.read().toJson())
        }

        register(
            ServiceMethod(
                name = "wifi",
                summary = "Wi-Fi 开关与连接状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            val state = stateReader.read()
            ServiceResult.ok(
                "ok",
                JSONObject()
                    .put("enabled", state.wifiEnabled)
                    .put("connected", state.wifiConnected)
                    .put("transports", state.transports.joinToString(",")),
            )
        }

        register(
            ServiceMethod(
                name = "mobile",
                summary = "移动数据连接状态",
                parameters = Schema.params(),
                risk = ServiceRisk.READ,
            ),
        ) {
            val state = stateReader.read()
            ServiceResult.ok(
                "ok",
                JSONObject()
                    .put("connected", state.cellularConnected)
                    .put("metered", state.metered)
                    .put("transports", state.transports.joinToString(",")),
            )
        }

        register(
            ServiceMethod(
                name = "enableWifi",
                summary = "尝试打开 Wi-Fi；系统禁止时打开 Wi-Fi 面板",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { setWifi(true) }

        register(
            ServiceMethod(
                name = "disableWifi",
                summary = "尝试关闭 Wi-Fi；系统禁止时打开 Wi-Fi 面板",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { setWifi(false) }

        register(
            ServiceMethod(
                name = "enableData",
                summary = "打开移动数据设置面板（系统不允许应用直接开关）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { openDataPanel() }

        register(
            ServiceMethod(
                name = "disableData",
                summary = "打开移动数据设置面板（系统不允许应用直接开关）",
                parameters = Schema.params(),
                risk = ServiceRisk.CONTROL,
            ),
        ) { openDataPanel() }

        register(
            ServiceMethod(
                name = "request",
                summary = "发起 HTTP 请求（默认禁止内网地址）",
                parameters = requestSchema(),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args -> execute(args) }

        register(
            ServiceMethod(
                name = "get",
                summary = "HTTP GET",
                parameters = requestSchema(includeMethod = false),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args -> execute(args, forceMethod = "GET") }

        register(
            ServiceMethod(
                name = "post",
                summary = "HTTP POST",
                parameters = requestSchema(includeMethod = false),
                risk = ServiceRisk.COMMUNICATION,
            ),
        ) { args -> execute(args, forceMethod = "POST") }

        register(
            ServiceMethod(
                name = "ping",
                summary = "探测域名/IP 是否可达",
                parameters = Schema.params(
                    "host" to Schema.string("域名或 IP"),
                    "timeoutMs" to Schema.integer("超时", 500, 10_000),
                ),
                risk = ServiceRisk.READ,
            ),
        ) { args ->
            val host = args.reqString("host")
            val reachable = runCatching {
                http.ping(host, args.optIntOr("timeoutMs", 3_000))
            }.getOrElse {
                return@register ServiceResult.fail(
                    (it as? HttpAccessException)?.code ?: "ping_failed",
                    it.message ?: "探测失败",
                )
            }
            ServiceResult.ok(
                if (reachable) "reachable" else "unreachable",
                JSONObject().put("host", host).put("reachable", reachable),
            )
        }

        register(
            ServiceMethod(
                name = "openSettings",
                summary = "打开系统网络面板",
                parameters = Schema.params(
                    "panel" to Schema.string("面板", listOf("internet", "wifi")),
                ),
                risk = ServiceRisk.CONTROL,
            ),
        ) { args ->
            val panel = args.optStringOr("panel", "internet").lowercase()
            val opened = when (panel) {
                "wifi" -> stateReader.openWifiPanel()
                else -> stateReader.openInternetPanel()
            }
            if (opened) ServiceResult.ok("settings_opened", JSONObject().put("panel", panel))
            else ServiceResult.fail("open_failed", "无法打开系统网络设置")
        }
    }

    private fun requestSchema(includeMethod: Boolean = true): JSONObject {
        val entries = mutableListOf<Pair<String, JSONObject>>()
        if (includeMethod) {
            entries += "method" to Schema.string(
                "HTTP 方法",
                listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"),
            )
        }
        entries += "url" to Schema.string("http/https URL")
        entries += "headers" to Schema.string("请求头 JSON 对象")
        entries += "body" to Schema.string("请求体")
        entries += "timeoutMs" to Schema.integer("超时", 1_000, 60_000)
        entries += "maxBytes" to Schema.integer("响应体上限", 1_024, 262_144)
        entries += "allowPrivate" to Schema.boolean("是否允许内网/本机地址")
        return Schema.params(*entries.toTypedArray())
    }

    private suspend fun execute(args: JSONObject, forceMethod: String? = null): ServiceResult {
        val method = forceMethod ?: args.optStringOr("method", "GET").uppercase()
        val url = args.reqString("url")
        val headers = parseHeaders(args)
        val body = args.optStringOr("body").takeIf { it.isNotEmpty() }
        val request = HttpRequest(
            method = method,
            url = url,
            headers = headers,
            body = body,
            timeoutMs = args.optIntOr("timeoutMs", 20_000),
            maxBytes = args.optIntOr("maxBytes", 64 * 1024),
            allowPrivate = args.optBoolOr("allowPrivate", false),
        )
        return try {
            val response = http.execute(request)
            val data = response.toJson()
            if (response.status in 200..299) {
                ServiceResult.ok("HTTP ${response.status}", data)
            } else {
                ServiceResult.fail("http_${response.status}", "HTTP ${response.status}", data)
            }
        } catch (e: HttpAccessException) {
            ServiceResult.fail(e.code, e.message)
        }
    }

    @Suppress("DEPRECATION")
    private fun setWifi(enabled: Boolean): ServiceResult {
        val wifi = context.getSystemService(WifiManager::class.java)
        val changed = runCatching {
            wifi?.setWifiEnabled(enabled) == true
        }.getOrDefault(false)
        if (changed) {
            return ServiceResult.ok(
                if (enabled) "wifi_enabling" else "wifi_disabling",
                JSONObject().put("changed", true),
            )
        }
        val opened = stateReader.openWifiPanel()
        return if (opened) {
            ServiceResult.ok(
                "user_action_opened",
                JSONObject()
                    .put("requested", if (enabled) "on" else "off")
                    .put("reason", "Android 10+ 不允许普通应用直接开关 Wi-Fi"),
            )
        } else {
            ServiceResult.fail("wifi_toggle_unavailable", "无法打开 Wi-Fi 面板")
        }
    }

    private fun openDataPanel(): ServiceResult {
        val opened = stateReader.openInternetPanel()
        return if (opened) {
            ServiceResult.ok(
                "user_action_opened",
                JSONObject().put("reason", "系统不允许应用直接开关移动数据"),
            )
        } else {
            ServiceResult.fail("open_failed", "无法打开网络设置")
        }
    }

    private fun parseHeaders(args: JSONObject): Map<String, String> {
        val headers = args.optJSONObject("headers")
            ?: args.optString("headers").takeIf { it.isNotBlank() }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
            ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = headers.optString(key)
        }
        return result
    }

    private fun HttpResponse.toJson(): JSONObject = JSONObject()
        .put("status", status)
        .put("url", url)
        .put("contentType", contentType)
        .put("headers", JSONObject(headers))
        .put("body", body)
        .put("bytes", bytes)
        .put("truncated", truncated)
}
