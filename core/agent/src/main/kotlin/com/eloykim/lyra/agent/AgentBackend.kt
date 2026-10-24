package com.eloykim.lyra.agent

/**
 * 决策来源。真实模型接进来时实现这个接口；测试用脚本化实现。
 */
/** 模型一次调用的真实用量；用于回执结算（AMP §16.1 的回填）。 */
data class AgentUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val total: Int get() = inputTokens + outputTokens
}

interface AgentBackend {
    fun reset(task: String)
    suspend fun next(observation: Observation, step: Int): Verb

    /** 最近一次决策的真实用量；无模型调用（脚本 backend）时为 null。 */
    val lastUsage: AgentUsage? get() = null

    /** 最近一次决策所基于的 Frame（P1：模型只看焦点集）。 */
    val lastFrame: com.eloykim.lyra.attention.FrameDraft? get() = null

    /** 最近一次决策对「这一步值不值得」的预测（0–1），用于 M-1 校准。 */
    val lastPredictedVoi: Double? get() = null

    /** 最近一次决策折算的成本（微美元）；无价格信息时为 null。 */
    val lastCostMicros: Long? get() = null

    /** 动作执行结果反馈给 backend，供下一步决策参考（避免重复失败动作）。 */
    fun onResult(verb: Verb, ok: Boolean, code: String?, message: String?) = Unit
}

typealias ScriptStep = (Observation) -> Verb

/**
 * 脚本化 backend：不思考，按预设步骤走。
 *
 * 用途有两个：
 * 1. 设备上的端到端验证（打开设置 → 滚动 → 返回）；
 * 2. 模型不可用时的降级演示（不假装是智能）。
 */
class ScriptedBackend(private val steps: List<ScriptStep>) : AgentBackend {

    private var index = 0

    override fun reset(task: String) {
        index = 0
    }

    override suspend fun next(observation: Observation, step: Int): Verb {
        val scripted = steps.getOrNull(index) ?: return Verb.Done
        index++
        return scripted(observation)
    }
}
