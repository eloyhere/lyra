package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * 多智能体结果合并与仲裁（`src/cooperation/merge.ts` 的忠实移植）。
 *
 * 冲突按证据层级优先、其次按权威度解决，**绝不按多数**。没有一方占优时升级到
 * 具名权威。共识不是正确性。
 */

/** 待合并的一条主张。 */
data class MergeClaim(
    val id: Id,
    /** 主张关于什么，例如 `deploy.cause`。冲突按 subject 检测。 */
    val subject: String,
    val value: String,
    val tier: Tier,
    val agent: Id,
    /** 这条主张背后的不同来源根（不是 URL）。 */
    val origins: List<String>,
    /** 接收方能否自己重跑该观察？只有能，层级才在转手中保留。 */
    val replayable: Boolean,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "subject" to jsonText(subject),
        "value" to jsonText(value),
        "tier" to jsonText(tier.wire),
        "agent" to jsonText(agent),
        "origins" to jsonStrings(origins),
        "replayable" to JsonValue.Bool(replayable),
    )

    companion object {
        fun fromJson(value: JsonValue): MergeClaim {
            val obj = value.asObject("merge claim")
            return MergeClaim(
                id = obj.requireString("id"),
                subject = obj.requireString("subject"),
                value = obj.requireString("value"),
                tier = Tier.fromWire(obj.requireString("tier")),
                agent = obj.requireString("agent"),
                origins = obj.requireArr("origins").items.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("merge claim.origins 必须是字符串数组")
                },
                replayable = obj.bool("replayable")
                    ?: throw IllegalArgumentException("缺少布尔字段 replayable"),
            )
        }
    }
}

/** 合并后的主张状态。 */
enum class MergedStatus(val wire: String) {
    HELD("held"),
    CONTESTED("contested"),
    SINGLE_AGENT("single_agent"),
    ;

    companion object {
        fun fromWire(value: String): MergedStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 merged status: $value")
    }
}

/** 按 subject 合并后的一个值。 */
data class MergedClaim(
    val subject: String,
    val value: String,
    val tier: Tier,
    val supporters: List<Id>,
    val origins: List<String>,
    val status: MergedStatus,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "subject" to jsonText(subject),
        "value" to jsonText(value),
        "tier" to jsonText(tier.wire),
        "supporters" to jsonStrings(supporters),
        "origins" to jsonStrings(origins),
        "status" to jsonText(status.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): MergedClaim {
            val obj = value.asObject("merged claim")
            return MergedClaim(
                subject = obj.requireString("subject"),
                value = obj.requireString("value"),
                tier = Tier.fromWire(obj.requireString("tier")),
                supporters = obj.requireArr("supporters").items.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("merged claim.supporters 必须是字符串数组")
                },
                origins = obj.requireArr("origins").items.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("merged claim.origins 必须是字符串数组")
                },
                status = MergedStatus.fromWire(obj.requireString("status")),
            )
        }
    }
}

/** 合并结果。 */
data class MergeResult(
    val merged: List<MergedClaim>,
    val conflicts: List<String>,
    val warnings: List<String>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "merged" to JsonValue.Arr(merged.map { it.toJson() }),
        "conflicts" to jsonStrings(conflicts),
        "warnings" to jsonStrings(warnings),
    )

    companion object {
        fun fromJson(value: JsonValue): MergeResult {
            val obj = value.asObject("merge result")
            return MergeResult(
                merged = obj.requireArr("merged").items.map { MergedClaim.fromJson(it) },
                conflicts = obj.requireArr("conflicts").items.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("merge result.conflicts 必须是字符串数组")
                },
                warnings = obj.requireArr("warnings").items.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("merge result.warnings 必须是字符串数组")
                },
            )
        }
    }
}

/** 仲裁决定。 */
enum class AdjudicationDecision(val wire: String) {
    ACCEPT("accept"),
    ESCALATE("escalate"),
    ;

    companion object {
        fun fromWire(value: String): AdjudicationDecision =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 adjudication decision: $value")
    }
}

/** 对争议主张的裁决。 */
data class Adjudication(
    val decision: AdjudicationDecision,
    val value: String? = null,
    val reason: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "decision" to jsonText(decision.wire),
        "value" to value?.let { jsonText(it) },
        "reason" to jsonText(reason),
    )

    companion object {
        fun fromJson(value: JsonValue): Adjudication {
            val obj = value.asObject("adjudication")
            return Adjudication(
                decision = AdjudicationDecision.fromWire(obj.requireString("decision")),
                value = obj.string("value"),
                reason = obj.requireString("reason"),
            )
        }
    }
}

/** 合并选项。 */
data class MergeOptions(
    /** 接收合并结果的智能体；决定层级是否在转手中衰减。 */
    val forAgent: Id,
)

private val ORDER: List<Tier> = Tier.entries.toList()

private data class ScoredClaim(val claim: MergedClaim, val score: Double)

/**
 * 按证据层级、再按权威度裁决争议主张。
 *
 * 只有一个候选时直接接受；否则按层级降序、权威分降序排列，层级更高者胜，其次
 * 权威分更高者胜，都不占优则升级。
 */
fun adjudicate(contested: List<MergedClaim>, authority: Map<Id, Double>): Adjudication {
    if (contested.size <= 1) {
        return Adjudication(
            decision = AdjudicationDecision.ACCEPT,
            value = contested.firstOrNull()?.value,
            reason = "single value",
        )
    }

    val scored = contested
        .map { claim ->
            val best = claim.supporters.maxOfOrNull { authority[it] ?: 0.0 } ?: 0.0
            ScoredClaim(claim = claim, score = maxOf(0.0, best))
        }
        .sortedWith(
            compareByDescending<ScoredClaim> { ORDER.indexOf(it.claim.tier) }
                .thenByDescending { it.score },
        )

    val first = scored[0]
    val second = scored[1]
    if (ORDER.indexOf(first.claim.tier) > ORDER.indexOf(second.claim.tier)) {
        return Adjudication(
            decision = AdjudicationDecision.ACCEPT,
            value = first.claim.value,
            reason = "higher evidence tier",
        )
    }
    if (first.score > second.score) {
        return Adjudication(
            decision = AdjudicationDecision.ACCEPT,
            value = first.claim.value,
            reason = "higher authority",
        )
    }
    return Adjudication(
        decision = AdjudicationDecision.ESCALATE,
        value = null,
        reason = "no side dominates; escalate to a named authority",
    )
}

private fun degrade(tier: Tier): Tier {
    val index = ORDER.indexOf(tier)
    return ORDER[maxOf(0, index - 1)]
}

/**
 * 主张换了手就不再保留原层级。除非接收方自己能重放观察，否则转述弱一级。
 */
fun transferTier(claim: MergeClaim, receiver: Id): Tier {
    if (claim.agent == receiver) {
        return claim.tier
    }
    return if (claim.replayable) claim.tier else degrade(claim.tier)
}

private fun capByIndependence(agents: List<Id>, origins: List<String>): Tier {
    val distinctAgents = agents.toSet().size
    val distinctOrigins = origins.toSet().size
    if (distinctAgents >= 2 && distinctOrigins >= 2) {
        return Tier.T4
    }
    if (distinctAgents >= 2) {
        return Tier.T3
    }
    return Tier.T2
}

/**
 * 合并来自多个智能体的结果契约。
 *
 * 冲突从不取平均：两个智能体意见相左会产生 `contested`，保留两个值，并记一条
 * conflict。共识不是正确性。
 */
fun mergeResults(claims: List<MergeClaim>, options: MergeOptions): MergeResult {
    val warnings = ArrayList<String>()
    val bySubject = LinkedHashMap<String, MutableList<MergeClaim>>()

    claims.forEach { claim ->
        val transferred = transferTier(claim, options.forAgent)
        if (transferred != claim.tier) {
            warnings += "${claim.id}: tier degraded ${claim.tier.wire} → ${transferred.wire} on transfer"
        }
        val normalised = claim.copy(tier = transferred)
        bySubject.getOrPut(claim.subject) { ArrayList() }.add(normalised)
    }

    val merged = ArrayList<MergedClaim>()
    val conflicts = ArrayList<String>()

    bySubject.forEach { (subject, group) ->
        val values = group.map { it.value }.distinct()
        val agents = group.map { it.agent }.distinct()
        val origins = group.flatMap { it.origins }.distinct()

        if (values.size > 1) {
            conflicts += subject
            values.forEach { value ->
                val supporting = group.filter { it.value == value }
                val supporters = supporting.map { it.agent }
                val supportingOrigins = supporting.flatMap { it.origins }.distinct()
                merged += MergedClaim(
                    subject = subject,
                    value = value,
                    tier = capByIndependence(supporters, supportingOrigins),
                    supporters = supporters.distinct(),
                    origins = supportingOrigins,
                    status = MergedStatus.CONTESTED,
                )
            }
            return@forEach
        }

        val highest = group.map { it.tier }.maxBy { ORDER.indexOf(it) }
        val cap = capByIndependence(agents, origins)
        val tier = if (ORDER.indexOf(highest) <= ORDER.indexOf(cap)) highest else cap
        merged += MergedClaim(
            subject = subject,
            value = values[0],
            tier = tier,
            supporters = agents,
            origins = origins,
            status = if (agents.size == 1) MergedStatus.SINGLE_AGENT else MergedStatus.HELD,
        )
    }

    return MergeResult(merged = merged, conflicts = conflicts, warnings = warnings)
}
