package com.eloykim.lyra.amp.protocol.warrant

import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.Trust

/**
 * Warrant Engine：置信度推导与凭据不变量校验。
 *
 * 对应参考实现 `src/warrant/warrant.ts`。置信度是**推导值**（M-1），
 * 层级（tier）只设基准，证据质量移动它，最弱来源封顶。
 */
private val TIER_BASE: Map<Tier, Double> = mapOf(
    Tier.T0 to 0.35,
    Tier.T1 to 0.55,
    Tier.T2 to 0.72,
    Tier.T3 to 0.85,
    Tier.T4 to 0.92,
)

/** 一条链的强度等于最弱环节，因此取最小值。 */
private val TRUST_CAP: Map<Trust, Double> = mapOf(
    Trust.SELF to 0.9,
    Trust.VERIFIED to 0.95,
    Trust.TRUSTED to 0.85,
    Trust.ASSERTED to 0.7,
    Trust.INFERRED to 0.6,
    Trust.EXTERNAL to 0.5,
)

private val TIER_ORDER: List<Tier> = listOf(Tier.T0, Tier.T1, Tier.T2, Tier.T3, Tier.T4)

/** 与参考实现一致的千分位取整。 */
private fun round3(value: Double): Double = Math.round(value * 1000.0).toDouble() / 1000.0

/**
 * 不同来源（origin）的数量，而不是不同 URL 的数量。
 * 同一来源的十次转述仍然只是一个来源。
 */
fun effectiveOrigins(evidence: List<EvidenceItem>): Int =
    evidence.map { item -> item.origin ?: item.id }.toSet().size

/** 从 tier + warrant 推导置信度（M-1），模型不能自行断言。 */
fun deriveConfidence(claim: Claim): Double = deriveConfidence(claim.tier, claim.warrant)

fun deriveConfidence(tier: Tier, warrant: Warrant): Double {
    val base = TIER_BASE.getValue(tier)
    val evidence = warrant.evidence
    if (evidence.isEmpty()) {
        return round3(minOf(base, 0.6))
    }

    val origins = effectiveOrigins(evidence)
    val averageIndependence = evidence.sumOf { it.independence } / evidence.size
    val corroboration = minOf(1.0, (origins - 1) / 2.0)
    val support = evidence.sumOf { item ->
        when (item.relation) {
            EvidenceRelation.CONTRADICTS -> -item.independence
            EvidenceRelation.UNDERCUTS -> -0.5 * item.independence
            else -> item.independence
        }
    } / evidence.size
    val cap = evidence.minOf { TRUST_CAP.getValue(it.trust) }

    val raw = base + 0.12 * corroboration + 0.1 * averageIndependence + 0.08 * support
    return round3(maxOf(0.0, minOf(raw, cap)))
}

/**
 * 层级虚报是校验失败，不是风格问题（W-5）。
 * 返回人类可读的违规项；空列表表示可接受。
 */
fun validateClaim(claim: Claim): List<String> {
    val problems = ArrayList<String>()
    val evidence = claim.warrant.evidence

    if (claim.tier != Tier.T0 && evidence.isEmpty()) {
        problems += "${claim.tier.wire} requires at least one evidence item"
    }
    if (claim.tier == Tier.T1 && evidence.none { it.entails == true }) {
        problems += "T1 requires a source that entails the claim (entails: true)"
    }
    if (claim.tier == Tier.T2 && evidence.none { it.trust == Trust.SELF || it.trust == Trust.VERIFIED }) {
        problems += "T2 requires an observation recorded by this agent"
    }
    if (claim.tier == Tier.T3) {
        val causal = claim.causal
        if (causal == null || causal.assignmentMechanism == AssignmentMechanism.OBSERVED) {
            problems += "T3 requires an intervention, randomization, or named identification strategy"
        }
    }
    if (claim.tier == Tier.T4 && effectiveOrigins(evidence) < 2) {
        problems += "T4 requires an independent replication (at least two origins)"
    }
    if (claim.causal?.assignmentMechanism == AssignmentMechanism.OBSERVED &&
        claim.claimScope == ClaimScope.INTERVENTIONAL
    ) {
        problems += "observational data cannot support an interventional claim without an identification strategy"
    }

    return problems
}

/** 没有可证伪条件的假设只是立场，不是假设（W-2）。 */
fun validateHypothesis(hypothesis: Hypothesis): List<String> {
    val problems = ArrayList<String>()
    if (hypothesis.refutesIf.trim().isEmpty()) {
        problems += "refutes_if is required"
    }
    if (hypothesis.prediction.trim().isEmpty()) {
        problems += "prediction is required"
    }
    return problems
}

/** 先注册再取数 → confirmatory；否则 exploratory（禁止 HARKing）。 */
enum class HypothesisClass(val wire: String) {
    CONFIRMATORY("confirmatory"),
    EXPLORATORY("exploratory"),
}

fun classifyHypothesis(hypothesis: Hypothesis): HypothesisClass =
    if (hypothesis.prereg != null) HypothesisClass.CONFIRMATORY else HypothesisClass.EXPLORATORY

/**
 * 有争议或存疑的主张可以进入 Frame，但不得授权任何副作用。
 * 只要没有达到「排除合理怀疑」，就返回 false。
 */
fun canAuthorizeEffect(claim: Claim, minTier: Tier): Boolean {
    if (claim.status != ClaimStatus.HELD) {
        return false
    }
    if (TIER_ORDER.indexOf(claim.tier) < TIER_ORDER.indexOf(minTier)) {
        return false
    }
    val confidence = claim.confidence ?: deriveConfidence(claim)
    return confidence >= 0.6
}

/**
 * 最小变更式修订：旧主张保留并被引用，绝不覆盖（W-3）。
 *
 * 与参考实现相同，先应用 [patch]（等价于 `Partial<Claim>` 展开），再强制覆写
 * `id` / `supersedes` / `revision` / `created`。
 */
fun reviseClaim(
    previous: Claim,
    reason: String,
    at: Timestamp,
    patch: (Claim) -> Claim = { it },
): Claim {
    val patched = patch(previous)
    val revisionCount = if (previous.revision == null) 0 else 1
    return patched.copy(
        id = "${previous.id}.r${revisionCount + 1}",
        supersedes = Ref(RefKind.CLAIM, previous.id),
        revision = ClaimRevision(reason, at),
        created = previous.created,
    )
}

/** 仅用于提示的证据建议层级；绝不用来自动升级主张。 */
fun suggestTier(evidence: List<EvidenceItem>): Tier {
    if (evidence.isEmpty()) {
        return Tier.T0
    }
    if (effectiveOrigins(evidence) >= 2 && evidence.any { it.trust == Trust.VERIFIED }) {
        return Tier.T4
    }
    if (evidence.any { it.trust == Trust.SELF || it.trust == Trust.VERIFIED }) {
        return Tier.T2
    }
    if (evidence.any { it.entails == true }) {
        return Tier.T1
    }
    return Tier.T0
}

/**
 * 没有新证据的社会压力不得改变置信度（W-5）。
 * runtime 在追加主张之前调用它。
 */
fun pressureBlocked(existing: Claim?, evidence: List<EvidenceItem>, underPressure: Boolean): Boolean =
    underPressure && existing != null && evidence.isEmpty()

/**
 * W-3：`supersedes` 必须指向一条仍然活跃的主张。
 *
 * [previous] 是账本里查到的被修订主张；为 null 表示指向了不存在的主张。
 */
fun validateSupersession(previous: Claim?, next: Claim): List<String> {
    val problems = ArrayList<String>()
    val target = next.supersedes
    if (target == null) {
        problems += "a revision must reference the claim it supersedes (W-3)"
        return problems
    }
    if (target.kind != RefKind.CLAIM) {
        problems += "supersedes must reference a claim"
    }
    if (previous == null) {
        problems += "supersedes points to an unknown claim: ${target.id}"
        return problems
    }
    if (target.id != previous.id) {
        problems += "supersedes must point to the claim being revised"
    }
    if (!previous.isActive) {
        problems += "supersedes must point to an active claim"
    }
    if (next.id == previous.id) {
        problems += "a revision must not reuse the previous id"
    }
    if (next.revision == null) {
        problems += "a revision requires a revision record"
    }
    return problems
}

/**
 * W-4：重要且阻塞的未知必须落账。
 *
 * [atImpasse] 为 true 表示 runtime 报告了 UNKNOWN 僵局，此时必须至少有一条
 * 未解除的阻塞性 unknown；否则就是模型沉默。
 */
fun validateUnknowns(unknowns: List<UnknownItem>, atImpasse: Boolean = false): List<String> {
    val problems = ArrayList<String>()
    val seen = HashSet<String>()
    unknowns.forEach { unknown ->
        if (!seen.add(unknown.id)) {
            problems += "unknown ${unknown.id} is recorded twice"
        }
    }
    if (atImpasse && unknowns.none { it.isBlockingOpen }) {
        problems += "an impasse must be recorded as a blocking unknown (W-4)"
    }
    return problems
}
