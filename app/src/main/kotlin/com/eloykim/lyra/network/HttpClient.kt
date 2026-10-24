package com.eloykim.lyra.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * App 层的通用 HTTP 访问。
 *
 * 和 core:network 的模型客户端分开：这里只负责「发一个 HTTP 请求并返回状态/
 * 头/正文」，不关心 OpenAI 协议；NetworkService 与 UI 都用同一份实现。
 */
data class HttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Int = 20_000,
    val maxBytes: Int = 64 * 1024,
    /** 默认禁止 loopback/内网/链路本地地址，避免 Agent 探测本机服务。 */
    val allowPrivate: Boolean = false,
)

data class HttpResponse(
    val status: Int,
    val url: String,
    val contentType: String?,
    val headers: Map<String, String>,
    val body: String,
    val bytes: Int,
    val truncated: Boolean,
)

class HttpAccessException(
    val code: String,
    override val message: String,
) : IOException(message)

class HttpClient {

    private val allowedMethods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

    suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val method = request.method.trim().uppercase()
        if (method !in allowedMethods) {
            throw HttpAccessException("method_not_supported", "不支持的方法：$method")
        }
        val url = validateUrl(request.url, request.allowPrivate)
        val timeout = request.timeoutMs.coerceIn(1_000, 60_000)
        val maxBytes = request.maxBytes.coerceIn(1_024, 256 * 1024)
        if (method in setOf("GET", "HEAD") && !request.body.isNullOrEmpty()) {
            throw HttpAccessException("bad_request", "$method 不允许请求体")
        }

        val connection = runCatching {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                doInput = true
                request.headers.forEach { (key, value) ->
                    if (key.isNotBlank()) setRequestProperty(key, value)
                }
                if (getRequestProperty("User-Agent").isNullOrBlank()) {
                    setRequestProperty("User-Agent", "Lyra/0.1 (network service)")
                }
                if (!request.body.isNullOrEmpty()) {
                    doOutput = true
                    if (getRequestProperty("Content-Type").isNullOrBlank()) {
                        setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    }
                }
            }
        }.getOrElse {
            throw HttpAccessException("invalid_url", it.message ?: "无法创建连接")
        }

        try {
            if (!request.body.isNullOrEmpty()) {
                connection.outputStream.use { output ->
                    output.write(request.body.toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val contentType = connection.contentType
            val headerMap = LinkedHashMap<String, String>()
            connection.headerFields.orEmpty().forEach { (key, values) ->
                if (key != null) headerMap[key] = values.joinToString(", ")
            }

            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val buffer = ByteArrayOutputStream()
            var truncated = false
            if (stream != null) {
                stream.use { input ->
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        val remaining = maxBytes - buffer.size()
                        if (remaining <= 0) {
                            truncated = true
                            break
                        }
                        if (read > remaining) {
                            buffer.write(chunk, 0, remaining)
                            truncated = true
                            break
                        }
                        buffer.write(chunk, 0, read)
                    }
                }
            }
            val bytes = buffer.toByteArray()
            HttpResponse(
                status = status,
                url = connection.url.toString(),
                contentType = contentType,
                headers = headerMap,
                body = bytes.toString(charsetOf(contentType)),
                bytes = bytes.size,
                truncated = truncated,
            )
        } catch (e: HttpAccessException) {
            throw e
        } catch (e: IOException) {
            throw HttpAccessException("network_error", e.message ?: "网络不可达")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 用 InetAddress 做一次轻量可达性探测；不做 ICMP 特权操作。 */
    suspend fun ping(host: String, timeoutMs: Int = 3_000): Boolean = withContext(Dispatchers.IO) {
        val clean = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
        if (clean.isBlank()) throw HttpAccessException("bad_request", "host 为空")
        if (!looksPrivate(clean)) {
            val address = InetAddress.getByName(clean)
            address.isReachable(timeoutMs.coerceIn(500, 10_000))
        } else {
            throw HttpAccessException("private_address_blocked", "默认不允许探测内网/本机地址")
        }
    }

    private fun validateUrl(raw: String, allowPrivate: Boolean): URL {
        val url = runCatching { URL(raw.trim()) }.getOrElse {
            throw HttpAccessException("invalid_url", "URL 不合法：$raw")
        }
        if (url.protocol?.lowercase() !in setOf("http", "https")) {
            throw HttpAccessException("invalid_url", "只支持 http/https")
        }
        if (url.host.isNullOrBlank()) {
            throw HttpAccessException("invalid_url", "URL 缺少 host")
        }
        if (url.userInfo != null) {
            throw HttpAccessException("invalid_url", "URL 不允许携带用户名/密码")
        }
        if (!allowPrivate) {
            val addresses = runCatching { InetAddress.getAllByName(url.host) }.getOrElse {
                throw HttpAccessException("dns_failed", "无法解析 ${url.host}")
            }
            if (addresses.any(::isPrivate)) {
                throw HttpAccessException(
                    "private_address_blocked",
                    "默认禁止访问内网/本机地址（allowPrivate=true 可显式放开）",
                )
            }
        }
        return url
    }

    private fun looksPrivate(host: String): Boolean =
        runCatching { InetAddress.getAllByName(host).any(::isPrivate) }.getOrDefault(false)

    private fun isPrivate(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress

    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        val match = Regex("charset=([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())
            ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(match.groupValues[1]) }
            .getOrDefault(Charsets.UTF_8)
    }
}
