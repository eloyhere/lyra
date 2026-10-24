package com.eloykim.lyra.vision

import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * 离线 OCR（中文 + 拉丁字母），用 ML Kit 的中文识别模型。
 *
 * 用途：某些界面无障碍树拿不到文字（游戏、Canvas、图片按钮），
 * 先用截图 OCR 读出文字和坐标，再交给 tapAt(x, y) 点击。
 */
class OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(path: String): List<OcrLine> {
        val bitmap = BitmapFactory.decodeFile(path)
            ?: throw IllegalArgumentException("无法解码截图：$path")
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val lines = ArrayList<OcrLine>()
                        result.textBlocks.forEach { block ->
                            block.lines.forEach { line ->
                                val box = line.boundingBox ?: Rect()
                                lines += OcrLine(
                                    text = line.text,
                                    left = box.left,
                                    top = box.top,
                                    right = box.right,
                                    bottom = box.bottom,
                                )
                            }
                        }
                        continuation.resume(lines)
                    }
                    .addOnFailureListener { error ->
                        continuation.resumeWithException(error)
                    }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 找包含指定文字的行，返回其中心点，供坐标点击使用。 */
    fun find(lines: List<OcrLine>, keyword: String): OcrLine? =
        lines.firstOrNull { it.text.replace(" ", "").contains(keyword.replace(" ", "")) }

    fun close() {
        runCatching { recognizer.close() }
    }
}
