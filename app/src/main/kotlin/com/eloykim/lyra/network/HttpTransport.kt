package com.eloykim.lyra.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极薄的 HTTP 传输层。
 *
 * 不引 OkHttp：目前只有「少量、非高频」的模型调用与目录刷新，
 * HttpURLConnection 足够，也少一个依赖。
 */
interface HttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): String
    suspend fun get(url: String, headers: Map<String, String>): String

    /** 逐行读取响应（SSE）；默认实现退化为整段读取后按行回调。 */
    suspend fun postStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit,
    ) {
        post(url, headers, body).lineSequence().forEach(onLine)
    }
}

class UrlConnectionTransport : HttpTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
        withContext(Dispatchers.IO) {
            request("POST", url, headers, body)
        }

    override suspend fun get(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            request("GET", url, headers, null)
        }

    override suspend fun postStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 300_000
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw LlmHttpException(code, connection.errorStream?.bufferedReader()?.readText().orEmpty().take(500))
            }
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.forEach(onLine)
            }
        } catch (e: IOException) {
            throw LlmHttpException(-1, e.message ?: "网络错误")
        } finally {
            connection.disconnect()
        }
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw LlmHttpException(code, text.take(500))
            }
            return text
        } catch (e: IOException) {
            throw LlmHttpException(-1, e.message ?: "网络错误")
        } finally {
            connection.disconnect()
        }
    }
}

class LlmHttpException(val statusCode: Int, val detail: String) :
    IOException("HTTP $statusCode: $detail") {

    /** 常见错误的「人话」解释，UI 直接展示。 */
    fun friendly(): String = when (statusCode) {
        401, 403 -> "Key 无效或没有权限"
        404 -> "接口地址或模型名不存在"
        429 -> "触发限流，稍后再试"
        in 500..599 -> "服务商暂时不可用（$statusCode）"
        -1 -> detail.ifBlank { "网络不可达" }
        else -> "请求失败（$statusCode）：${detail.take(120)}"
    }
}
