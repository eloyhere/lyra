package com.eloykim.lyra.agent

/**
 * 凭据账本（AMP W-1…W-5 + 剪枝 P-1…P-4）。
 *
 * 只做三件事：记下主张/假设/未知、守三条硬规则、给出一份可剪的视图。
 * 视图剪枝**不修改账本**——账本仍是只追加的。
 */
enum class WarrantTier(val rank: Int) {
    T0(0), T1(1), T2(2), T3(3), T4(4);

    val isVerified: Boolean get() = rank >= T2.rank
}

enum class ClaimStatus { active, superseded, contested }
enum class HypothesisStatus { open, supported, refuted, inconclusive, out_of_scope }

data class Claim(
    val id: String,
    val statement: String,
    val tier: WarrantTier,
    val warrant: String?,
    val supersedes: String? = null,
    val status: ClaimStatus = ClaimStatus.active,
) {
    val isConjecture: Boolean get() = tier == WarrantTier.T0 || warrant.isNullOrBlank()
}

data class Hypothesis(
    val id: String,
    val statement: String,
    val refutesIf: String,
    val questionUnknownId: String? = null,
    val status: HypothesisStatus = HypothesisStatus.open,
)

data class WarrantUnknown(
    val id: String,
    val question: String,
    val importance: Double,
    val blocking: Boolean,
    val resolved: Boolean = false,
)

sealed interface PruneReason {
    data object superseded : PruneReason
    data object closed : PruneReason
    data object resolved : PruneReason
}

data class DroppedEntry(
    val id: String,
    val kind: String,
    val reason: PruneReason,
    val revisitIf: String,
)

data class PruneView(
    val keptClaims: List<Claim>,
    val keptHypotheses: List<Hypothesis>,
    val keptUnknowns: List<WarrantUnknown>,
    val dropped: List<DroppedEntry>,
    val sourceCount: Int,
    val sourceTip: String,
)

class WarrantRejected(message: String) : IllegalArgumentException(message)

class WarrantLedger {

    private val claims = LinkedHashMap<String, Claim>()
    private val hypotheses = LinkedHashMap<String, Hypothesis>()
    private val unknowns = LinkedHashMap<String, WarrantUnknown>()
    private val order = ArrayList<Pair<String, String>>() // (kind, id) 记录出现顺序
    private var tip: String = "genesis"
    private var counter = 0

    /** W-1：驱动行动的主张必须带 warrant 且 tier ≥ T2；否则只能作为 conjecture。 */
    fun noteClaim(
        statement: String,
        tier: WarrantTier,
        warrant: String?,
        supersedes: String? = null,
    ): Claim {
        if (tier.isVerified && warrant.isNullOrBlank()) {
            throw WarrantRejected("W-1：tier=$tier 的主张必须给 warrant，否则只能标为 T0")
        }
        if (supersedes != null) {
            val old = claims[supersedes]
                ?: throw WarrantRejected("W-3：supersedes 指向不存在的主张 $supersedes")
            if (old.status != ClaimStatus.active) {
                throw WarrantRejected("W-3：只能取代仍然有效的主张（当前 ${old.status}）")
            }
            claims[supersedes] = old.copy(status = ClaimStatus.superseded)
        }
        val claim = Claim(id = "cl.${++counter}", statement, tier, warrant, supersedes)
        claims[claim.id] = claim
        order += "claim" to claim.id
        tip = claim.id
        return claim
    }

    /** W-2：假设必须给出反证条件。 */
    fun noteHypothesis(statement: String, refutesIf: String, aboutUnknown: String? = null): Hypothesis {
        if (refutesIf.isBlank()) throw WarrantRejected("W-2：假设必须给 refutes_if")
        if (aboutUnknown != null && aboutUnknown !in unknowns) {
            throw WarrantRejected("假设指向不存在的未知 $aboutUnknown")
        }
        val hypothesis = Hypothesis("h.${++counter}", statement, refutesIf, aboutUnknown)
        hypotheses[hypothesis.id] = hypothesis
        order += "hypothesis" to hypothesis.id
        tip = hypothesis.id
        return hypothesis
    }

    fun closeHypothesis(id: String, status: HypothesisStatus) {
        val current = hypotheses[id] ?: throw WarrantRejected("未知假设 $id")
        hypotheses[id] = current.copy(status = status)
        tip = id
    }

    /** W-4：阻塞的未知必须显式记录。 */
    fun noteUnknown(question: String, importance: Double, blocking: Boolean): WarrantUnknown {
        val unknown = WarrantUnknown("u.${++counter}", question, importance, blocking)
        unknowns[unknown.id] = unknown
        order += "unknown" to unknown.id
        tip = unknown.id
        return unknown
    }

    fun resolveUnknown(id: String) {
        val current = unknowns[id] ?: throw WarrantRejected("未知的未知 $id")
        unknowns[id] = current.copy(resolved = true)
        tip = id
    }

    /** 不可逆动作前的守门：没有 T2 以上的主张就拒绝。 */
    fun guardIrreversible(claimId: String?): Result<Unit> {
        val claim = claimId?.let { claims[it] }
            ?: return Result.failure(WarrantRejected("不可逆动作需要一个带证据的主张"))
        return if (claim.tier.isVerified && !claim.warrant.isNullOrBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(WarrantRejected("W-1：主张 ${claim.id} 是 conjecture，不能驱动不可逆动作"))
        }
    }

    fun unresolvedBlocking(): List<WarrantUnknown> = unknowns.values.filter { it.blocking && !it.resolved }

    /**
     * 剪枝视图（P-1…P-4）：
     * - 最近 keepRecent 条是硬下限；
     * - 只有 superseded 主张、终态假设、已解决且非阻塞的未知可以离开；
     * - 被保留对象引用的未知必须拉回；
     * - 其余一律保留。
     */
    fun pruneView(keepRecent: Int): PruneView {
        require(keepRecent >= 1) { "keepRecent 至少为 1" }
        val protectedIds = order.takeLast(keepRecent).map { it.second }.toSet()

        val dropped = ArrayList<DroppedEntry>()

        claims.values.forEach { claim ->
            if (claim.id !in protectedIds && claim.status == ClaimStatus.superseded) {
                dropped += DroppedEntry(claim.id, "claim", PruneReason.superseded, "被 ${claim.supersedes ?: "?"} 取代的新证据出现时")
                claims[claim.id] = claim
            }
        }
        hypotheses.values.forEach { hypothesis ->
            val closed = hypothesis.status != HypothesisStatus.open
            if (hypothesis.id !in protectedIds && closed) {
                dropped += DroppedEntry(hypothesis.id, "hypothesis", PruneReason.closed, "假设重新打开时")
            }
        }
        unknowns.values.forEach { unknown ->
            if (unknown.id !in protectedIds && unknown.resolved && !unknown.blocking) {
                dropped += DroppedEntry(unknown.id, "unknown", PruneReason.resolved, "该问题重新出现时")
            }
        }

        // P-3：保留对象引用的未知不得悬空
        val droppedIds = dropped.map { it.id }.toSet()
        val keptHypotheses = hypotheses.values.filter { it.id !in droppedIds }
        val referencedUnknowns = keptHypotheses.mapNotNull { it.questionUnknownId }.toSet()
        val rescued = droppedIds.filter { it in referencedUnknowns }
        val finalDropped = dropped.filterNot { it.id in rescued }

        val finalDroppedIds = finalDropped.map { it.id }.toSet()
        return PruneView(
            keptClaims = claims.values.filter { it.id !in finalDroppedIds },
            keptHypotheses = keptHypotheses,
            keptUnknowns = unknowns.values.filter { it.id !in finalDroppedIds },
            dropped = finalDropped,
            sourceCount = order.size,
            sourceTip = tip,
        )
    }
}
