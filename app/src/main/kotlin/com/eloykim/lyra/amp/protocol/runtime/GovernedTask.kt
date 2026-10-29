package com.eloykim.lyra.amp.protocol.runtime

import com.eloykim.lyra.amp.protocol.bayes.BetaBelief
import com.eloykim.lyra.amp.protocol.governance.AttentionManifest
import com.eloykim.lyra.amp.protocol.governance.BoardReceipt
import com.eloykim.lyra.amp.protocol.governance.BoardReceiptInput
import com.eloykim.lyra.amp.protocol.governance.ChargeRequest
import com.eloykim.lyra.amp.protocol.governance.ChargeResult
import com.eloykim.lyra.amp.protocol.governance.ContextLayers
import com.eloykim.lyra.amp.protocol.governance.ControlAction
import com.eloykim.lyra.amp.protocol.governance.ControlDecision
import com.eloykim.lyra.amp.protocol.governance.ControlObservation
import com.eloykim.lyra.amp.protocol.governance.CredentialVerdict
import com.eloykim.lyra.amp.protocol.governance.EffectClass
import com.eloykim.lyra.amp.protocol.governance.GovernanceBudget
import com.eloykim.lyra.amp.protocol.governance.GovernanceCode
import com.eloykim.lyra.amp.protocol.governance.GovernanceError
import com.eloykim.lyra.amp.protocol.governance.LedgerScope
import com.eloykim.lyra.amp.protocol.governance.LegalGate
import com.eloykim.lyra.amp.protocol.governance.LegalVerdict
import com.eloykim.lyra.amp.protocol.governance.LoadManifestRequest
import com.eloykim.lyra.amp.protocol.governance.LoadedManifest
import com.eloykim.lyra.amp.protocol.governance.ManifestRef
import com.eloykim.lyra.amp.protocol.governance.PerspectiveId
import com.eloykim.lyra.amp.protocol.governance.SwitchCredential
import com.eloykim.lyra.amp.protocol.governance.ToolCheckInput
import com.eloykim.lyra.amp.protocol.governance.VerificationVerdict
import com.eloykim.lyra.amp.protocol.governance.VerifyCredentialRequest
import com.eloykim.lyra.amp.protocol.governance.decideControl
import com.eloykim.lyra.amp.protocol.governance.loadAttentionManifest
import com.eloykim.lyra.amp.protocol.governance.verifyAttentionManifest
import com.eloykim.lyra.amp.protocol.governance.verifyCredential
import com.eloykim.lyra.amp.protocol.module.ToolRisk
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * `src/runtime/governedTask.ts` 的移植：治理层与认知运行时之间的桥。
 *
 * 答案仍然由运行时产生；这个适配器只决定任务**能不能跑**（manifest 与 credential
 * 有效、法律未冻结、预算未冻结），审计它调用的工具，记账，写 board receipt，
 * 再问 Bayesian 控制层下一步做什么。
 *
 * 它是适配器，不是第二个运行时：这里不拼提示、不打分、不调用模型。
 *
 * ## 窄端口：audit
 *
 * 参考实现从 `governance/index.ts` 导入 `GovernanceAuditLog`。治理审计包在本波次
 * 由另一路并行移植（`governance/Audit.kt`），字段形状仍在变动，因此这里只声明
 * `recordTurn` 的窄端口 [TurnAuditSink]：[GovernedTaskOptions.audit] 接收它，
 * 宿主可以用一个薄适配器把 `GovernanceAuditLog` 接进来，本文件不与其字段耦合。
 */

/** 审计里的 manifest 引用（参考的 `{id, version, digest}`）。 */
data class TurnAuditManifest(
    val id: Id,
    val version: Int,
    val digest: Digest,
)

/** 审计里的控制摘要（参考的 `{action, stop, escalate, approvalRequired}`）。 */
data class TurnAuditControl(
    val action: ControlAction,
    val stop: Boolean,
    val escalate: Boolean,
    val approvalRequired: Boolean,
)

/** 一次已结算轮次的审计输入。 */
data class TurnAuditRecord(
    val turnId: String,
    val perspective: PerspectiveId,
    val manifest: TurnAuditManifest,
    val tokens: Int,
    val charge: ChargeResult,
    val control: TurnAuditControl,
    val receiptId: Id,
    val legalCodes: List<String> = emptyList(),
)

data class TurnAuditEntry(val id: Id)

fun interface TurnAuditSink {
    fun recordTurn(input: TurnAuditRecord): TurnAuditEntry
}

data class GovernedTaskOptions(
    val runtime: CognitiveRuntime,
    val manifest: AttentionManifest,
    val budget: GovernanceBudget,
    val legal: LegalGate,
    val context: ContextLayers,
    val credential: SwitchCredential? = null,
    val audit: TurnAuditSink? = null,
    /**
     * 时钟。参考实现在这里做 `typeof now === "function"` 的运行时检查；Kotlin 的
     * 非空函数类型让该检查在类型层面恒真，因此不再重复。
     */
    val now: () -> Timestamp,
)

data class CredentialCheck(
    val id: String,
    val verdict: String,
)

data class GovernedTurnResult(
    val turn: RuntimeTurn,
    val manifest: LoadedManifest,
    val credential: CredentialCheck? = null,
    val legal: List<LegalVerdict>,
    val charge: ChargeResult,
    val control: ControlDecision,
    val receipt: BoardReceipt,
    val audit: TurnAuditEntry? = null,
)

/** 工具风险到法律效果类的映射（参考实现的 `effectOf`）。 */
fun effectOf(risk: ToolRisk?): EffectClass = when (risk) {
    ToolRisk.DESTRUCTIVE -> EffectClass.WRITE_IRREVERSIBLE
    ToolRisk.WRITE -> EffectClass.WRITE_REVERSIBLE
    else -> EffectClass.READ
}

private fun clamp01(value: Double): Double =
    if (!value.isFinite()) 0.0 else if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value

/**
 * 环绕运行时的受治任务。
 *
 * 无法启动的任务在模型被调用**之前**就被拒绝：冻结的视角、跳闸的断路器、过期的
 * manifest 或失效的 credential 永远到不了生成阶段。
 *
 * 工具调用在运行时返回之后才被审计。真正的调用前闸门属于运行时的 [ToolPolicy]；
 * 这个适配器记录法律判决，并可以让下一个轮次冻结该视角。
 */
class GovernedTask(private val options: GovernedTaskOptions) {

    fun manifest(): AttentionManifest = options.manifest

    /**
     * 在 manifest 下跑一轮，然后结算它。
     */
    fun turn(input: String, override: TurnOverride = TurnOverride()): GovernedTurnResult {
        val at = options.now()
        if (options.legal.isBlocked()) {
            throw GovernanceError(GovernanceCode.LEGAL_BREAKER, "the legal breaker is active")
        }
        if (options.legal.isFrozen(options.manifest.perspective)) {
            throw GovernanceError(
                GovernanceCode.LEGAL_FROZEN,
                "${options.manifest.perspective.wire} is frozen",
            )
        }
        val verdict: VerificationVerdict = verifyAttentionManifest(options.manifest, at)
        if (!verdict.ok) {
            throw GovernanceError(
                if (verdict.code == GovernanceCode.MANIFEST_EXPIRED.wire) {
                    GovernanceCode.MANIFEST_EXPIRED
                } else {
                    GovernanceCode.MANIFEST_UNSIGNED
                },
                verdict.detail ?: "manifest is not valid",
            )
        }
        val loaded = loadAttentionManifest(
            options.manifest,
            LoadManifestRequest(perspective = options.manifest.perspective, at = at),
        )
        val binding = options.manifest.budget
        val ledger = if (binding.scope == LedgerScope.AGENT) {
            options.budget.snapshot(LedgerScope.AGENT, binding.agent ?: "")
        } else {
            options.budget.snapshot(
                LedgerScope.PERSPECTIVE,
                (binding.perspective ?: options.manifest.perspective).wire,
            )
        }
        if (ledger.frozen) {
            throw GovernanceError(
                GovernanceCode.BUDGET_FROZEN,
                "${binding.scope.wire} ledger is frozen",
            )
        }
        val credential = options.credential
        var credentialVerdict: CredentialCheck? = null
        if (credential != null) {
            val checked: CredentialVerdict = verifyCredential(
                credential,
                VerifyCredentialRequest(
                    at = at,
                    to = options.manifest.perspective,
                    manifest = ManifestRef(options.manifest.id, options.manifest.version),
                ),
            )
            if (!checked.valid) {
                throw GovernanceError(GovernanceCode.CREDENTIAL_SCOPE, "${checked.code}: ${checked.detail}")
            }
            credentialVerdict = CredentialCheck(credential.id, checked.code)
        }
        options.context.openPerspective(
            options.manifest.perspective,
            ManifestRef(options.manifest.id, options.manifest.version),
        )
        val systemNote = "Governance: evidence floor ${options.manifest.evidenceFloor.wire}; " +
            "forbidden ${options.manifest.constraints.forbidden.take(3).joinToString(", ")}."
        val governedOverride = override.copy(
            scenarioMix = override.scenarioMix ?: legacyScenarioMix(options.manifest.scenario),
            interactionMix = override.interactionMix ?: legacyInteractionMix(options.manifest.modes),
            systemNote = override.systemNote ?: systemNote,
        )
        val turn = options.runtime.respond(input, governedOverride)
        val legal = ArrayList<LegalVerdict>()
        for (call in turn.toolCalls ?: emptyList()) {
            legal.add(
                options.legal.enforceTool(
                    ToolCheckInput(
                        perspective = options.manifest.perspective,
                        scenario = options.manifest.scenario,
                        action = call.name,
                        effect = effectOf(call.risk).wire,
                    ),
                ),
            )
        }
        val usage = turn.usage
        val tokens = (usage?.inputTokens ?: 0) + (usage?.outputTokens ?: 0)
        val chargeRequest = if (binding.scope == LedgerScope.AGENT) {
            ChargeRequest(
                scope = LedgerScope.AGENT,
                agent = binding.agent ?: "",
                amount = Budget(tokens = tokens),
                reason = "turn ${turn.turnId}",
                requestedBy = options.manifest.perspective,
            )
        } else {
            ChargeRequest(
                scope = LedgerScope.PERSPECTIVE,
                perspective = binding.perspective ?: options.manifest.perspective,
                amount = Budget(tokens = tokens),
                reason = "turn ${turn.turnId}",
                requestedBy = options.manifest.perspective,
            )
        }
        val charge: ChargeResult = options.budget.charge(chargeRequest)
        val overall = turn.answerScore?.overall ?: 0.5
        val posterior = BetaBelief(alpha = 1 + overall * 4, beta = 1 + (1 - overall) * 4)
        val control: ControlDecision = decideControl(
            ControlObservation(
                scenario = options.manifest.scenario,
                modes = options.manifest.modes,
                posterior = posterior,
                conflict = turn.toolErrors > 0,
                voi = 1 - overall,
                openQuestions = 0,
            ),
        )
        val receipt: BoardReceipt = options.context.writeReceipt(
            options.manifest.perspective,
            BoardReceiptInput(
                conclusion = turn.answer.take(180).ifEmpty { "turn completed" },
                evidence = loaded.focus.take(3).map { it.target },
                confidence = clamp01(overall),
                budget = Budget(tokens = tokens),
                manifest = ManifestRef(options.manifest.id, options.manifest.version),
                scenario = options.manifest.scenario,
                modes = options.manifest.modes,
                openQuestions = if (control.stop) emptyList() else listOf("review the residual uncertainty"),
            ),
        )
        val legalCodes = legal.flatMap { entry -> entry.violations.map { it.code.wire } }
        val auditSink = options.audit
        val auditEntry = auditSink?.recordTurn(
            TurnAuditRecord(
                turnId = turn.turnId,
                perspective = options.manifest.perspective,
                manifest = TurnAuditManifest(
                    id = options.manifest.id,
                    version = options.manifest.version,
                    digest = options.manifest.digest,
                ),
                tokens = tokens,
                charge = charge,
                control = TurnAuditControl(
                    action = control.action,
                    stop = control.stop,
                    escalate = control.escalate,
                    approvalRequired = control.approvalRequired,
                ),
                receiptId = receipt.id,
                legalCodes = legalCodes,
            ),
        )
        return GovernedTurnResult(
            turn = turn,
            manifest = loaded,
            credential = credentialVerdict,
            legal = legal,
            charge = charge,
            control = control,
            receipt = receipt,
            audit = auditEntry,
        )
    }
}
