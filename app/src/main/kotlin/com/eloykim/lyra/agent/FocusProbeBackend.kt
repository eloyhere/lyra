package com.eloykim.lyra.agent

import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.attention.AttentionSelector
import com.eloykim.lyra.attention.Candidate

/**
 * 仅调试构建使用的「只生成一帧」后端。
 *
 * 它不调用模型，只把当前观察交给同一个 [AttentionSelector]，然后结束。
 * 用途是在模拟器上验证 Frame → 协议 JSON → SQLite 的真实落库链路，
 * 不参与发布路径。
 */
class FocusProbeBackend(private val task: String) : AgentBackend {

    private val selector = AttentionSelector()

    override var lastFrame: Frame? = null
        private set

    override fun reset(task: String) = Unit

    override suspend fun next(observation: Observation, step: Int): Verb {
        val candidates = observation.elements.map { element ->
            Candidate(
                id = element.id.toString(),
                label = element.label.ifBlank { "元素${element.id}" },
                goalFit = 0.8f,
                clickable = element.clickable,
                editable = element.editable,
                costTokens = (element.label.length / 2).coerceIn(12, 120),
            )
        }
        val draft = selector.select(cycle = step, goal = task, candidates = candidates)
        lastFrame = draft.toProtocolFrame()
        return Verb.Done
    }
}
