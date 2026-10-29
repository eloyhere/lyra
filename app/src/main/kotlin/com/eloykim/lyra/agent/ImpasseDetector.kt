package com.eloykim.lyra.agent

/**
 * 僵局检测（AMP §3.6）。
 *
 * 经典认知架构里没有「反复做同一件事却毫无进展」这个状态，而 LLM Agent 最常卡在这里。
 * 检测必须落日志——一个从不记录卡住的系统，无法知道自己卡住了多少次。
 */
enum class Impasse(val code: String) {
    /** 连续相同动作且屏幕未变。 */
    STALLED("stalled"),

    /** 动作成功但屏幕没有变化，无法确认效果。 */
    UNVERIFIED("unverified"),

    /** 目标漂移到 Lyra 自身或系统设置。 */
    DRIFT("drift"),

    /** 策略全部拒绝，无路可走。 */
    INFEASIBLE("infeasible"),
}

/** 一次动作的可比较签名：动作 + 目标 + 期望。 */
data class ActionSignature(
    val action: String,
    val nodeId: Int?,
    val text: String?,
    val packageName: String?,
) {
    companion object {
        fun of(verb: Verb.Act): ActionSignature = ActionSignature(
            action = verb.action.lowercase(),
            nodeId = verb.nodeId,
            text = verb.text?.take(40),
            packageName = verb.packageName,
        )
    }
}

/**
 * 有状态的检测器：喂给它「动作签名 + 屏幕指纹 + 动作是否成功」。
 *
 * 阈值刻意保守：连续两次同样的失败动作才判 STALLED，
 * 避免把「点两次同一个按钮」（例如加减数量）误判为卡死。
 */
class ImpasseDetector(
    private val stallThreshold: Int = 2,
    private val selfPackage: String = "com.eloykim.lyra",
    private val selfOperationAllowed: () -> Boolean = { false },
) {

    private var lastSignature: ActionSignature? = null
    private var lastScreen: String? = null
    private var repeats = 0

    fun reset() {
        lastSignature = null
        lastScreen = null
        repeats = 0
    }

    /**
     * @param screenDigest 当前屏幕的指纹（可用元素数量 + 包名 + 首屏文本）
     * @param ok 动作是否执行成功
     */
    fun evaluate(
        signature: ActionSignature,
        screenDigest: String,
        ok: Boolean,
        foregroundPackage: String?,
    ): Impasse? {
        if (foregroundPackage == selfPackage && !selfOperationAllowed()) return Impasse.DRIFT

        val sameAction = signature == lastSignature
        val sameScreen = screenDigest == lastScreen
        repeats = if (sameAction && sameScreen) repeats + 1 else 0
        lastSignature = signature
        lastScreen = screenDigest

        return when {
            repeats >= stallThreshold -> Impasse.STALLED
            // 只有「换了动作但屏幕没变」才值得怀疑；同样的动作重复归 STALLED 管
            ok && sameScreen && repeats == 0 -> Impasse.UNVERIFIED
            else -> null
        }
    }

    /** 读当前重复次数，供指标统计。 */
    fun repeatCount(): Int = repeats
}
