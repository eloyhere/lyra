package com.eloykim.lyra.agent

import com.eloykim.lyra.amp.protocol.frame.Frame

/**
 * Frame 落库出口（P1 帧链）。
 *
 * Frame 是不可变快照：一次注意周期一条，带 digest 与 parent 语义，
 * 由宿主（而非模型）写入，审计时按 run 回放。
 *
 * X-1：这里没有 update。僵局由 `EventSink` 追加事件表达，不能回写旧帧。
 */
interface FrameRecorder {

    suspend fun record(runId: Long, frame: Frame)

    /** 兼容旧调用点；实现应写入事件而不是修改既有帧。 */
    suspend fun markImpasse(runId: Long, state: String) = Unit

    companion object {
        val Noop = object : FrameRecorder {
            override suspend fun record(runId: Long, frame: Frame) = Unit
        }
    }
}
