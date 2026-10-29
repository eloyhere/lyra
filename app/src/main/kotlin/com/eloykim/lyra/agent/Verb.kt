package com.eloykim.lyra.agent

/**
 * AMP 的 M-surface 动词（模型可提议的 7 个动作）。
 *
 * 协议原话：verbs are proposals —— 宿主可以拒绝，并且必须记录原因。
 * `Done` 不是协议动词，是宿主侧的终止信号（任务完成或无法继续）。
 */
sealed interface Verb {

    /** 把注意力放到某个对象上（通常意味着再观察一次）。 */
    data class Attend(val target: String, val why: String) : Verb

    /** 执行一个动作。nodeId 必须来自当前观察。 */
    data class Act(
        val action: String,
        val nodeId: Int? = null,
        val text: String? = null,
        val packageName: String? = null,
        val expect: String? = null,
        val why: String? = null,
    ) : Verb

    /** 向人提问 / 请求授权。 */
    data class Ask(
        val question: String,
        val kind: AskClass = AskClass.commitment,
    ) : Verb

    /** 记一条凭据（主张 / 假设 / 未知 / 概念）。 */
    data class Note(
        val assertion: String,
        val asType: NoteType = NoteType.claim,
        val refutesIf: String? = null,
    ) : Verb

    data class Plan(val goal: String) : Verb

    /** 查询本机聊天记录（宿主扩展动词，由 ChatMessageService 提供数据）。 */
    data class SearchMessages(
        val query: String,
        val limit: Int = 5,
    ) : Verb

    /** 对指定聊天消息点赞、点踩或用单个 emoji 评论。 */
    data class ReactMessage(
        val messageId: Long,
        val reaction: String,
    ) : Verb

    /** 通过 ChatMessageService 生成一条消息的引用文本。 */
    data class QuoteMessage(val messageId: Long) : Verb

    /** 应用目录（宿主扩展动词，由 AppCatalogService 提供数据）。 */
    data class Application(
        val action: String,
        val query: String? = null,
    ) : Verb

    /** 语音播报（宿主扩展动词，由 VoiceService 提供 TTS）。 */
    data class Voice(
        val action: String,
        val text: String? = null,
    ) : Verb

    /** 读取大结果句柄的一页（AMP 降级阶梯的最后一格）。 */
    data class Read(
        val handle: String,
        val cursor: String? = null,
        val limit: Int = 2,
    ) : Verb

    data object Reflect : Verb

    data object Done : Verb
}

enum class AskClass { safety, commitment, opportunity, curiosity }

enum class NoteType { claim, hypothesis, unknown, concept }
