package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.interaction.InteractionPlan

/**
 * `src/runtime/safety.ts` 的移植：交互输出的安全闸门。
 *
 * 这些是保守的、基于模式的守卫，不是分类器。它们拦下明确的危机/诊断/操纵案例，
 * 把最终判断留给人类升级路径。不能被当作医疗建议。
 */
enum class SafetyAction(val wire: String) {
    ALLOW("allow"),
    REFUSE("refuse"),
    ESCALATE("escalate"),
}

data class SafetyDecision(
    val action: SafetyAction,
    val reasons: List<String>,
)

private val CrisisPatterns: List<Regex> = listOf(
    Regex(
        "\\b(suicide|suicidal|kill myself|end my life|self[- ]?harm|hurt myself|overdose)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex("\\b(immediate danger|emergency|someone is going to hurt me)\\b", RegexOption.IGNORE_CASE),
)

private val DiagnosisPatterns: List<Regex> = listOf(
    Regex(
        "\\byou (have|are diagnosed with|suffer from) (depression|anxiety disorder|bipolar|ptsd|adhd|autism)\\b",
        RegexOption.IGNORE_CASE,
    ),
    Regex("\\byour diagnosis is\\b", RegexOption.IGNORE_CASE),
)

private val ManipulationPatterns: List<Regex> = listOf(
    Regex("\\byou (only )?need me\\b", RegexOption.IGNORE_CASE),
    Regex("\\bdon't (leave|trust anyone else)\\b", RegexOption.IGNORE_CASE),
    Regex("\\bonly I (understand|can help) you\\b", RegexOption.IGNORE_CASE),
)

/**
 * 检查一轮对话。
 *
 * 顺序与参考实现一致：先看用户文本是否含危机信号（直接升级），再看答案是否在
 * 交互禁止诊断/操纵时仍然越界（拒绝）。
 */
fun checkSafety(
    userText: String,
    interaction: InteractionPlan,
    answerText: String = "",
): SafetyDecision {
    val reasons = ArrayList<String>()
    if (CrisisPatterns.any { it.containsMatchIn(userText) }) {
        reasons.add("user text contains a crisis/self-harm signal")
        return SafetyDecision(SafetyAction.ESCALATE, reasons)
    }
    if (interaction.safety.noDiagnosis && DiagnosisPatterns.any { it.containsMatchIn(answerText) }) {
        reasons.add("answer makes a diagnosis while the interaction forbids it")
        return SafetyDecision(SafetyAction.REFUSE, reasons)
    }
    if (interaction.safety.noManipulation && ManipulationPatterns.any { it.containsMatchIn(answerText) }) {
        reasons.add("answer uses dependency/manipulation language")
        return SafetyDecision(SafetyAction.REFUSE, reasons)
    }
    return SafetyDecision(SafetyAction.ALLOW, reasons)
}

/** 被安全闸门拦下时返回的替代文本。 */
fun safetyFallback(action: SafetyAction): String {
    if (action == SafetyAction.ESCALATE) {
        return "This sounds like an immediate safety concern. Please contact local emergency services " +
            "or a crisis line now, and reach out to a trusted person who can stay with you. " +
            "I can stay with you here, but I cannot replace emergency help."
    }
    return "I cannot help with that framing. I can offer a safer, evidence-based alternative if you want to continue."
}
