package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.bayes.Dimension
import com.eloykim.lyra.amp.protocol.bayes.UserScore
import com.eloykim.lyra.amp.protocol.governance.ModeWeights
import com.eloykim.lyra.amp.protocol.governance.ScenarioId
import com.eloykim.lyra.amp.protocol.interaction.InteractionKind
import com.eloykim.lyra.amp.protocol.interaction.InteractionMix
import com.eloykim.lyra.amp.protocol.scenario.ScenarioKind
import com.eloykim.lyra.amp.protocol.scenario.ScenarioMix

/**
 * `src/runtime/routing.ts` 的移植：从文本/用户状态推出 scenario 与 interaction
 * 的权重混合。
 *
 * 这是规则先验，不是分类器。它存在只是为了让循环闭合；之后应由 Bayesian 引擎与
 * 真实 receipt 替换/校准。
 *
 * 依赖说明：`ScenarioMix` / `InteractionMix` 来自已移植的 `scenario`、`interaction`
 * 包（与参考实现 `src/scenario/scenario.ts`、`src/interaction/interaction.ts` 绑定），
 * 因此本文件不再自带这两个 plan 的窄类型。
 *
 * 另外提供两个**治理桥**（参考实现在 `governance/index.ts`）：把 manifest 的
 * scenario 与 mode weights 接进运行时。它们留在本文件是因为 runtime 是治理层与
 * 认知层之间唯一会调用它们的层。
 */

private fun countMatches(text: String, pattern: Regex): Int = pattern.findAll(text).count()

/** 规则先验：从文本推 scenario 混合。 */
fun inferScenarioMix(text: String): ScenarioMix {
    val engineering = countMatches(
        text,
        Regex("\\b(code|bug|typescript|javascript|api|sql|deploy|test|build|refactor|stack|error)\\b", RegexOption.IGNORE_CASE),
    )
    val research = countMatches(
        text,
        Regex("\\b(paper|study|literature|hypothesis|evidence|theory|experiment|survey)\\b", RegexOption.IGNORE_CASE),
    )
    val investigation = countMatches(
        text,
        Regex("\\b(incident|root cause|audit|forensic|postmortem|timeline|outage)\\b", RegexOption.IGNORE_CASE),
    )
    val office = countMatches(
        text,
        Regex("\\b(email|schedule|meeting|document|calendar|report|deadline|stakeholder)\\b", RegexOption.IGNORE_CASE),
    )
    val writing = countMatches(
        text,
        Regex("\\b(write|draft|rewrite|edit|style|tone|copy|article)\\b", RegexOption.IGNORE_CASE),
    )
    val collaboration = countMatches(
        text,
        Regex("\\b(team|coordinate|handoff|review|pair|collaborate|sync|owner)\\b", RegexOption.IGNORE_CASE),
    )
    val iteration = countMatches(
        text,
        Regex("\\b(iterate|version|prototype|quick|try|loop|experiment)\\b", RegexOption.IGNORE_CASE),
    )
    val chat = countMatches(
        text,
        Regex("\\b(hi|hello|thanks|feel|feeling|tired|overwhelmed|anxious|happy|sad)\\b", RegexOption.IGNORE_CASE),
    )

    val scores = listOf(
        ScenarioKind.ENGINEERING to engineering,
        ScenarioKind.RESEARCH to research,
        ScenarioKind.INVESTIGATION to investigation,
        ScenarioKind.OFFICE to office,
        ScenarioKind.WRITING to writing,
        ScenarioKind.COLLABORATION to collaboration,
        ScenarioKind.ITERATION to iteration,
        ScenarioKind.CHAT to chat,
    ).sortedByDescending { it.second }
    if (scores[0].second == 0) {
        return linkedMapOf(
            ScenarioKind.OFFICE to 0.2,
            ScenarioKind.COLLABORATION to 0.2,
            ScenarioKind.ENGINEERING to 0.2,
            ScenarioKind.CHAT to 0.2,
            ScenarioKind.WRITING to 0.2,
        )
    }
    val top = scores.filter { it.second > 0 }.take(3)
    val total = top.sumOf { it.second }
    val mix = LinkedHashMap<ScenarioKind, Double>()
    for ((kind, score) in top) {
        mix[kind] = score.toDouble() / total
    }
    return mix
}

/** 规则先验：从用户状态推交互混合。 */
fun inferInteractionMix(user: UserScore): InteractionMix {
    if (user.state.emotionalIntensity > 0.6) {
        return linkedMapOf(
            InteractionKind.AFFECT to 0.55,
            InteractionKind.THINK to 0.2,
            InteractionKind.CONVERGE to 0.2,
            InteractionKind.INSPIRE to 0.05,
        )
    }
    if (user.state.urgency > 0.6) {
        return linkedMapOf(
            InteractionKind.CONVERGE to 0.55,
            InteractionKind.THINK to 0.25,
            InteractionKind.AFFECT to 0.2,
        )
    }
    if (user.dimensions.getValue(Dimension.CLARITY).mean < 0.4) {
        return linkedMapOf(
            InteractionKind.INSPIRE to 0.45,
            InteractionKind.AFFECT to 0.25,
            InteractionKind.THINK to 0.3,
        )
    }
    return linkedMapOf(
        InteractionKind.THINK to 0.55,
        InteractionKind.CONVERGE to 0.25,
        InteractionKind.ORCHESTRATE to 0.2,
    )
}

/**
 * 治理桥：把一个 canonical scenario 变成单点混合。
 *
 * 参考实现在 `governance/index.ts`；本模块需要它把 manifest 的 scenario 接入运行时。
 */
fun legacyScenarioMix(scenario: ScenarioId): ScenarioMix =
    linkedMapOf(ScenarioKind.fromWire(scenario.wire) to 1.0)

/**
 * 治理桥：把治理模式权重映射回交互混合。
 *
 * `diverge` 映射到 `inspire`；`orchestrate` 没有对应模式，保持缺省（参考实现同样
 * 只写被映射的四个键）。
 */
fun legacyInteractionMix(weights: ModeWeights): InteractionMix = linkedMapOf(
    InteractionKind.AFFECT to weights.affect,
    InteractionKind.THINK to weights.think,
    InteractionKind.INSPIRE to weights.diverge,
    InteractionKind.CONVERGE to weights.converge,
)
