package com.eloykim.lyra.voice

import com.eloykim.lyra.network.LlmClient
import com.eloykim.lyra.network.LlmMessage

/**
 * 翻译服务：复用用户已配置的模型（BYOK），不额外引入第三方翻译 API 和 Key。
 *
 * 场景：
 * - 界面文案需要新语种时，由模型产出初稿，人工校对后写进 values-xx；
 * - Agent 操作外语 App 时，把屏幕文字译成用户语言再决策。
 */
class TranslationService(private val client: LlmClient) {

    suspend fun translate(text: String, targetLanguage: String): String {
        if (text.isBlank()) return ""
        val result = client.complete(
            listOf(
                LlmMessage(
                    "system",
                    "你是专业本地化译者。只输出译文，不要解释、不要加引号。" +
                        "保留占位符（如 %s、%1\$s）和专有名词 Lyra / API / OCR / SQLite 原样。",
                ),
                LlmMessage("user", "把下面文本翻译成$targetLanguage：\n$text"),
            ),
        )
        return result.text?.trim().orEmpty()
    }
}
