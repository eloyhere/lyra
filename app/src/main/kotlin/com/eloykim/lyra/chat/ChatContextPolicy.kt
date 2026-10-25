package com.eloykim.lyra.chat

/**
 * 聊天上下文的唯一策略来源。
 *
 * 压缩与剪枝在「模型边界」（ChatStore）和「服务边界」（ChatMessageService）各触发一次，
 * 但阈值只能有一份——否则迟早出现一边压缩、一边没压缩的错位。
 */
object ChatContextPolicy {

    /** 尾部硬保留条数。 */
    const val KEEP_RECENT = 12

    /** 不超过这个条数就不压缩。 */
    const val MAX_TURNS = 40

    /** 不超过这个字符数就不压缩。 */
    const val MAX_CHARS = 6_000
}
