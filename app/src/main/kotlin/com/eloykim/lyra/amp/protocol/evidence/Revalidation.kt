package com.eloykim.lyra.amp.protocol.evidence

import com.eloykim.lyra.amp.protocol.goal.Plan
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.ClaimStatus
import java.util.IdentityHashMap

/**
 * 从账本快照重建的、宿主自有的 warrant 依赖投影。
 *
 * 这里没有 wire 字段、不做真理推断、不负责持久化或权限；对应参考实现
 * `src/evidence/revalidation.ts`。
 *
 * 完成复核是宿主 API：`completeReview` 只是断言外部复核已经发生，并不代替复核。
 * token 把“完成”绑定到受影响的 claim generation。
 */
sealed interface WarrantSource {

    data class RefSource(val ref: Ref) : WarrantSource

    data class OriginSource(val id: String) : WarrantSource

    data class EvidenceSource(val claimId: String, val id: String) : WarrantSource
}

data class RevalidationImpact(
    val claims: List<String>,
    val plans: List<String>,
)

/**
 * 复核令牌。
 *
 * 刻意不是 data class：参考实现用 `WeakMap` 的对象身份识别令牌，伪造一个字段
 * 相同的对象不能清掉 pending。这里用默认的身份相等 + [IdentityHashMap] 复刻。
 */
class ReviewToken(
    val claimId: String,
    val generation: Int,
) {

    override fun toString(): String = "ReviewToken(claimId=$claimId, generation=$generation)"
}

/**
 * 依权威账本快照构建，再接收显式变化。
 *
 * 增删改 claim 或 plan 之后要重建；在发布替代投影之前先重放未完成的变化。
 * 未知来源会抛异常。
 */
class WarrantRevalidation(
    claims: List<Claim>,
    plans: List<Plan> = emptyList(),
) {

    private val graph = EvidenceGraph()
    private val claimIds = LinkedHashMap<String, String>()
    private val fingerprints = LinkedHashMap<String, String>()
    private val held = LinkedHashSet<String>()
    private val parents = LinkedHashMap<String, LinkedHashSet<String>>()
    private val planDependents = LinkedHashMap<String, LinkedHashSet<String>>()
    private val generations = LinkedHashMap<String, Int>()
    private val pending = LinkedHashSet<String>()
    private val reviews = IdentityHashMap<ReviewToken, ReviewToken>()

    init {
        claims.forEach { claim ->
            val key = sourceKey(claimSource(claim.id))
            graph.addClaim(key, claim.statement)
            claimIds[key] = claim.id
            fingerprints[claim.id] = CanonicalJson.digest(claim.toJson())
            generations[claim.id] = 0
            if (claim.status == ClaimStatus.HELD) held.add(claim.id)
        }
        val blocked = LinkedHashMap<String, WarrantSource>()
        claims.forEach { claim ->
            val key = sourceKey(claimSource(claim.id))
            val claimParents = LinkedHashSet<String>()
            parents[claim.id] = claimParents
            if (!held.contains(claim.id)) {
                blocked[key] = claimSource(claim.id)
            }
            claim.warrant.evidence.forEach { item ->
                val itemKey = sourceKey(WarrantSource.EvidenceSource(claim.id, item.id))
                graph.addEvidence(itemKey, item.excerpt ?: item.id, key, item.trust)
                item.ref?.let { ref ->
                    val refSource = WarrantSource.RefSource(ref)
                    graph.link(ensureSource(refSource), itemKey)
                    if (ref.kind == RefKind.CLAIM) {
                        claimParents.add(ref.id)
                        if (!held.contains(ref.id)) {
                            blocked[sourceKey(refSource)] = refSource
                        }
                    }
                }
                item.origin?.let { origin ->
                    graph.link(ensureSource(WarrantSource.OriginSource(origin)), itemKey)
                }
            }
        }
        val planIds = LinkedHashSet<String>()
        plans.forEach { plan ->
            if (!planIds.add(plan.id)) {
                throw IllegalArgumentException("duplicate plan ${plan.id}")
            }
            plan.warrant?.let { warrant ->
                val key = ensureSource(WarrantSource.RefSource(warrant))
                planDependents.getOrPut(key) { LinkedHashSet() }.add(plan.id)
            }
        }
        invalidate(blocked.values.toList())
    }

    /**
     * 所有关系（支撑、反驳、削弱）都是决策的依赖；`supersedes` 是历史而不是支撑边。
     *
     * 每次变化（包括重复通知）都会保守地推进受影响 claim 的 generation。
     */
    fun invalidate(sources: List<WarrantSource>): RevalidationImpact {
        val entries = graph.impact(sources.map(::sourceKey))
        val changedClaims = ArrayList<String>()
        val changedPlans = LinkedHashSet<String>()
        entries.forEach { entry ->
            claimIds[entry.id]?.let { claimId ->
                changedClaims += claimId
                pending.add(claimId)
                generations[claimId] = (generations[claimId] ?: 0) + 1
            }
            planDependents[entry.id]?.forEach { planId -> changedPlans.add(planId) }
        }
        return RevalidationImpact(claims = changedClaims, plans = changedPlans.toList())
    }

    /**
     * 既检查依赖是否新鲜，也检查被索引 claim 本体的身份。
     *
     * 未知或被改写的 claim 一律判为不新鲜（fail closed）；这不认证宿主。
     */
    fun isCurrent(claim: Claim): Boolean =
        fingerprints[claim.id] == CanonicalJson.digest(claim.toJson()) && !pending.contains(claim.id)

    fun needsRevalidation(claimId: String): Boolean =
        !fingerprints.containsKey(claimId) || pending.contains(claimId)

    fun beginReview(claimId: String): ReviewToken {
        val generation = generations[claimId]
            ?: throw IllegalArgumentException("unknown claim $claimId")
        val token = ReviewToken(claimId, generation)
        reviews[token] = token
        return token
    }

    /**
     * 宿主在确认剩余支撑之后才调用。
     *
     * 过期 token、未解决/非 held 的前件、伪造 token 都无法清掉 pending；结论变化
     * 需要追加修订并重建索引。
     */
    fun completeReview(token: ReviewToken): Boolean {
        val issued = reviews[token] ?: return false
        if (generations[issued.claimId] != issued.generation) return false
        val claimParents = parents[issued.claimId] ?: emptySet<String>()
        if (claimParents.any { parent -> !held.contains(parent) || pending.contains(parent) }) {
            return false
        }
        reviews.remove(token)
        pending.remove(issued.claimId)
        return true
    }

    private fun ensureSource(source: WarrantSource): String {
        val key = sourceKey(source)
        if (graph.get(key) == null) {
            graph.addSource(key, key)
        }
        return key
    }
}

/** 参考实现用 `JSON.stringify([...])` 组键；这里用规范 JSON 得到同样的字节。 */
private fun sourceKey(source: WarrantSource): String {
    val parts = when (source) {
        is WarrantSource.RefSource -> listOf("ref", source.ref.kind.wire, source.ref.id)
        is WarrantSource.OriginSource -> listOf("origin", source.id)
        is WarrantSource.EvidenceSource -> listOf("evidence", source.claimId, source.id)
    }
    return CanonicalJson.encode(JsonValue.Arr(parts.map { JsonValue.Str(it) }))
}

private fun claimSource(id: String): WarrantSource =
    WarrantSource.RefSource(Ref(kind = RefKind.CLAIM, id = id))
