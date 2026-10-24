package com.eloykim.lyra.agent

import com.eloykim.lyra.attention.FrameDraft
import com.eloykim.lyra.attention.digest

/**
 * Frame 落库出口（P1 帧链）。
 *
 * Frame 是不可变快照：一次注意周期一条，带 digest 与 parent 语义，
 * 由宿主（而非模型）写入，审计时按 run 回放。
 */
interface FrameRecorder {

    suspend fun record(runId: Long, frame: FrameDraft)

    /** 给该 run 最近一帧打上僵局标记（动作之后才有结论）。 */
    suspend fun markImpasse(runId: Long, state: String) = Unit
    companion object {
        val Noop = object : FrameRecorder {
            override suspend fun record(runId: Long, frame: FrameDraft) = Unit
        }
    }
}

/** 供落库层渲染 payload，避免 data 模块依赖 attention。 */
fun FrameDraft.toPayload(): String = buildString {
    append("""{"cycle":$cycle,"mode":"$mode","goal":${quote(goal)},"digest":"${digest()}",""")
    append(""""slots":[""")
    append(slots.joinToString(",") { """{"id":"${it.id}","salience":${it.salience},"cost":${it.costTokens},"why":${quote(it.why)}}""" })
    append(""","ignored":[""")
    append(ignored.joinToString(",") { """{"id":"${it.id}","reason":"${it.reason}","revisit_if":${quote(it.revisitIf)}}""" })
    append("""],"allocated":$allocatedTokens,"spent":$spentTokens}""")
}

private fun quote(raw: String): String = buildString {
    append('"')
    raw.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }
    append('"')
}
