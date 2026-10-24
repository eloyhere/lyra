package com.eloykim.lyra.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线语音模型（SenseVoiceSmall int8）的下载与落盘。
 *
 * 模型约 228 MB，下载到 filesDir/asr/sense-voice/：
 * - model.int8.onnx
 * - tokens.txt
 *
 * 先试 hf-mirror（国内可达），失败再回源 HuggingFace；两者都是直连，没有中转服务。
 */
object OfflineAsrModel {

    enum class State { Missing, Downloading, Ready, Failed }

    data class Status(
        val state: State = State.Missing,
        val bytes: Long = 0L,
        val total: Long = APPROX_BYTES,
        val error: String? = null,
    ) {
        val progress: Float
            get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
    }

    const val APPROX_BYTES = 239_233_841L
    private const val MODEL_NAME = "model.int8.onnx"
    private const val TOKENS_NAME = "tokens.txt"

    private const val REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    private val TOKENS_URLS = arrayOf(
        "https://hf-mirror.com/$REPO/resolve/main/tokens.txt",
        "https://huggingface.co/$REPO/resolve/main/tokens.txt",
    )
    private val MODEL_URLS = arrayOf(
        "https://hf-mirror.com/$REPO/resolve/main/$MODEL_NAME",
        "https://huggingface.co/$REPO/resolve/main/$MODEL_NAME",
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        _status.value = if (isReady(app)) {
            cleanTemp(modelDir(app))
            Status(State.Ready, APPROX_BYTES, APPROX_BYTES)
        } else {
            // 上次下载留下的半截文件没有断点续传能力，直接清掉
            cleanTemp(modelDir(app))
            Status()
        }
    }

    fun modelDir(context: Context): File = File(context.filesDir, "asr/sense-voice")

    fun modelFile(context: Context): File = File(modelDir(context), MODEL_NAME)

    fun tokensFile(context: Context): File = File(modelDir(context), TOKENS_NAME)

    fun isReady(context: Context): Boolean =
        modelFile(context).length() > 100_000_000L && tokensFile(context).length() > 100L

    fun download(context: Context) {
        init(context)
        if (_status.value.state == State.Downloading) return
        if (isReady(context)) {
            _status.value = Status(State.Ready, APPROX_BYTES, APPROX_BYTES)
            return
        }
        val app = context.applicationContext
        _status.value = Status(State.Downloading, 0L, APPROX_BYTES)
        job = scope.launch {
            try {
                val dir = modelDir(app).apply { mkdirs() }
                downloadFile(
                    urls = TOKENS_URLS,
                    target = File(dir, TOKENS_NAME),
                    onProgress = { },
                )
                downloadFile(
                    urls = MODEL_URLS,
                    target = File(dir, MODEL_NAME),
                    onProgress = { done ->
                        _status.value = Status(State.Downloading, done, APPROX_BYTES)
                    },
                )
                if (!isReady(app)) error("模型文件不完整，请重试")
                _status.value = Status(State.Ready, APPROX_BYTES, APPROX_BYTES)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cleanTemp(modelDir(app))
                _status.value = Status()
                throw e
            } catch (e: Exception) {
                cleanTemp(modelDir(app))
                _status.value = Status(
                    state = State.Failed,
                    bytes = _status.value.bytes,
                    total = APPROX_BYTES,
                    error = e.message ?: "下载失败",
                )
            }
        }
    }

    /** 用户取消下载：停掉协程并清掉半截文件，回到「未下载」。 */
    fun cancel(context: Context) {
        job?.cancel()
        job = null
        cleanTemp(modelDir(context))
        _status.value = Status()
    }

    fun delete(context: Context) {
        job?.cancel()
        job = null
        modelDir(context).deleteRecursively()
        _status.value = Status()
    }

    private fun downloadFile(urls: Array<String>, target: File, onProgress: (Long) -> Unit) {
        val temp = File(target.parentFile, "${target.name}.part")
        var lastError: Exception? = null
        for (url in urls) {
            try {
                temp.delete()
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "Lyra/0.1 (offline speech model)")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        error("HTTP ${connection.responseCode}")
                    }
                    val total = connection.contentLengthLong
                    if (total > 0 && target.name == MODEL_NAME) {
                        _status.value = _status.value.copy(total = total, bytes = 0L)
                    }
                    connection.inputStream.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - lastReport >= 512 * 1024) {
                                    lastReport = done
                                    onProgress(done)
                                }
                            }
                            output.flush()
                            onProgress(done)
                        }
                    }
                    if (temp.length() <= 0L) error("下载内容为空")
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    return
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法下载模型")
    }

    private fun cleanTemp(dir: File) {
        dir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
    }
}
