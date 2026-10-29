package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.module.ToolDecision
import com.eloykim.lyra.amp.protocol.module.ToolDescriptor
import com.eloykim.lyra.amp.protocol.module.ToolInvocation
import com.eloykim.lyra.amp.protocol.module.ToolPolicy
import com.eloykim.lyra.amp.protocol.module.ToolRisk
import com.eloykim.lyra.amp.protocol.types.Tier

/**
 * Pre-execution governance gate for tools, a faithful port of
 * `src/governance/toolPolicy.ts`.
 *
 * `CognitiveRuntime` already asks a [ToolPolicy] before it calls an executor.
 * This adapter answers that question with the legal gate, the manifest and the
 * evidence floor, so a destructive tool is stopped before the call instead of
 * being audited after it.
 *
 * 风险等级到效果类的映射与参考实现一致：`destructive` →
 * `write-irreversible`，`write` → `write-reversible`，其余（含未声明）→
 * `read`。冻结与熔断先于法律核查：一旦断路器跳闸或视角被冻结，工具在到达
 * 执行器之前就被拒绝。
 */
data class GovernedToolPolicyOptions(
    val legal: LegalGate,
    val manifest: AttentionManifest,
    /**
     * Evidence tier attached to this invocation. When present, the manifest's
     * floor is enforced; omit it when no evidence has been gathered yet.
     */
    val evidenceTier: Tier? = null,
    /**
     * Optional per-tool tier resolver, e.g. read-only tools can claim T2.
     */
    val tierFor: ((ToolInvocation, ToolDescriptor) -> Tier?)? = null,
    /**
     * A pre-issued legal `tool.high-risk` approval. Without it, high-risk tools
     * are denied before the executor sees them.
     */
    val approval: ApprovalGrant? = null,
)

/** Maps a tool's declared risk onto the legal effect taxonomy. */
fun effectOf(risk: ToolRisk?): EffectClass = when (risk) {
    ToolRisk.DESTRUCTIVE -> EffectClass.WRITE_IRREVERSIBLE
    ToolRisk.WRITE -> EffectClass.WRITE_REVERSIBLE
    else -> EffectClass.READ
}

/**
 * The governed tool policy: freeze/breaker, then the legal gate with the
 * manifest's evidence floor, then the pre-execution decision.
 */
class GovernedToolPolicy(private val options: GovernedToolPolicyOptions) : ToolPolicy {

    override fun check(invocation: ToolInvocation, descriptor: ToolDescriptor): ToolDecision {
        val legal = options.legal
        if (legal.isBlocked()) {
            return ToolDecision(false, "legal breaker is active")
        }
        val manifest = options.manifest
        if (legal.isFrozen(manifest.perspective)) {
            return ToolDecision(false, "${manifest.perspective.wire} is frozen")
        }
        val evidenceTier = options.tierFor?.invoke(invocation, descriptor) ?: options.evidenceTier
        val verdict = legal.checkTool(
            ToolCheckInput(
                perspective = manifest.perspective,
                scenario = manifest.scenario,
                action = descriptor.name,
                effect = effectOf(descriptor.risk).wire,
                evidenceTier = evidenceTier,
                evidenceFloor = if (evidenceTier == null) null else manifest.evidenceFloor,
                approval = options.approval,
            ),
        )
        val blocking = verdict.violations.filter { it.level != ViolationLevel.MINOR }
        if (blocking.isNotEmpty()) {
            return ToolDecision(false, blocking.joinToString(", ") { it.code.wire })
        }
        if (verdict.violations.isNotEmpty()) {
            return ToolDecision(true, verdict.violations.joinToString(", ") { it.code.wire })
        }
        return ToolDecision(true)
    }
}
