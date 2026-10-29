package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import java.time.Instant
import java.util.Locale

/**
 * Agent context 组装：self -> role -> affect -> memory -> relationships ->
 * memberships -> background，在 token 预算内。
 *
 * 对应 `src/context/agentContext.ts`。这不是记忆转储：每个片段都带 ref，每个
 * 遗漏都记录原因；affect 可以影响检索权重，但不能改变事实。
 *
 * 说明：参考实现的 `scenario` / `interaction` 选项来自 `scenario.*` 与
 * `interaction.*` 模块（本模块未移植），因此这里固定使用「无 scenario 时」的
 * 预算、份额与包含集合 —— 这正是参考实现在未提供这两个选项时的行为。
 */
enum class AgentContextKind(val wire: String) {
    SELF("self"),
    ROLE("role"),
    AFFECT("affect"),
    MEMORY("memory"),
    RELATIONSHIP("relationship"),
    MEMBERSHIP("membership"),
    BACKGROUND("background"),
}

enum class ExcludedReason(val wire: String) {
    BUDGET("budget"),
    LOW_SALIENCE("low-salience"),
    ROLE_MISMATCH("role-mismatch"),
    STALE("stale"),
    PRIVACY("privacy"),
}

/** 与参考实现一致：`Math.max(1, Math.ceil(text.length / 4))`，按 UTF-16 码元计。 */
fun estimateTokens(text: String): Int = maxOf(1, (text.length + 3) / 4)

data class AgentContextSegment(
    val kind: AgentContextKind,
    val ref: Ref? = null,
    val text: String,
    val tokens: Int,
)

data class ExcludedSegment(
    val kind: AgentContextKind,
    val ref: Ref? = null,
    val reason: ExcludedReason,
)

data class AgentObligation(val statement: String)

data class AgentRole(
    val id: String,
    val label: String,
    val obligations: List<AgentObligation>,
    val audiences: List<String>,
)

data class RoleAssignment(
    val role: AgentRole,
    val salience: Double,
)

data class AgentProfile(
    val name: String,
    val lifeChapter: String,
    val values: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
)

data class SelfNarrative(
    val version: Int,
    val narrative: String,
)

data class AffectState(
    val valence: Double,
    val arousal: Double,
    val stress: Double,
    val fatigue: Double,
    val mood: String,
)

data class Memory(
    val id: String,
    val kind: String,
    val content: String,
    val salience: Double,
    val at: String,
    val roleId: String? = null,
)

data class Relationship(
    val id: String,
    val kind: String,
    val fromPid: String,
    val toPid: String,
    val trust: Double,
    val attachment: Double,
    val obligations: List<String> = emptyList(),
)

data class Institution(
    val id: String,
    val name: String,
    val norms: List<String> = emptyList(),
)

data class Membership(
    val institution: Institution,
    val role: String,
    val status: String,
)

data class BackgroundProcess(
    val id: String,
    val kind: String,
    val label: String,
    val intensity: Double,
    val trigger: String? = null,
)

data class AgentContext(
    val profile: AgentProfile,
    val narrative: SelfNarrative,
    val activeRole: RoleAssignment? = null,
    val affect: AffectState? = null,
    val memories: List<Memory> = emptyList(),
    val relationships: List<Relationship> = emptyList(),
    val memberships: List<Membership> = emptyList(),
    val background: List<BackgroundProcess> = emptyList(),
    val segments: List<AgentContextSegment> = emptyList(),
    val tokens: Int = 0,
    val excluded: List<ExcludedSegment> = emptyList(),
)

data class AgentContextOptions(
    val now: String? = null,
    val includeKinds: List<AgentContextKind>? = null,
)

/** 只声明组装器真正会调用的读取面；由宿主实现。 */
interface AgentReader {

    fun profile(pid: String): AgentProfile?

    fun narrative(pid: String): SelfNarrative?

    fun activeRole(pid: String): RoleAssignment?

    fun affect(pid: String): AffectState?

    fun memories(pid: String, limit: Int): List<Memory>

    fun background(pid: String, state: String): List<BackgroundProcess>
}

interface SocialReader {

    fun relationships(pid: String): List<Relationship>

    fun memberships(pid: String): List<Membership>
}

private const val DEFAULT_TOKENS: Int = 2000

/** MEMBERSHIP 份额为 0，但下限仍是 1 token —— 与参考实现一致。 */
private val SHARES: Map<AgentContextKind, Double> = linkedMapOf(
    AgentContextKind.SELF to 0.25,
    AgentContextKind.ROLE to 0.15,
    AgentContextKind.AFFECT to 0.10,
    AgentContextKind.MEMORY to 0.35,
    AgentContextKind.RELATIONSHIP to 0.10,
    AgentContextKind.MEMBERSHIP to 0.0,
    AgentContextKind.BACKGROUND to 0.05,
)

private fun clamp01(value: Double): Double = maxOf(0.0, minOf(1.0, value))

private fun fixed2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun parseEpochMillis(at: String): Double? =
    runCatching { Instant.parse(at).toEpochMilli().toDouble() }.getOrNull()

private fun refFor(kind: AgentContextKind, id: String, label: String? = null): Ref {
    val refKind: RefKind = when (kind) {
        AgentContextKind.SELF -> RefKind.SELF
        AgentContextKind.MEMORY -> RefKind.MEMORY
        AgentContextKind.MEMBERSHIP -> RefKind.ORG
        AgentContextKind.RELATIONSHIP -> RefKind.HUMAN
        else -> RefKind.OTHER
    }
    return Ref(refKind, id, label)
}

private fun selfText(profile: AgentProfile, narrative: SelfNarrative): String {
    val values: String = if (profile.values.isNotEmpty()) profile.values.joinToString(", ") else "none stated"
    val constraints: String = if (profile.constraints.isNotEmpty()) profile.constraints.joinToString(", ") else "none stated"
    return listOf(
        "I am ${profile.name}. Life chapter: ${profile.lifeChapter}.",
        "Values: $values. Constraints: $constraints.",
        "Current self-narrative (v${narrative.version}): ${narrative.narrative}",
    ).joinToString(" ")
}

private fun roleText(assignment: RoleAssignment): String {
    val obligations: List<String> = assignment.role.obligations.map { it.statement }
    return listOf(
        "Active role: ${assignment.role.label} (salience ${fixed2(assignment.salience)}).",
        if (obligations.isNotEmpty()) "Obligations: ${obligations.joinToString("; ")}." else "No explicit obligations.",
        if (assignment.role.audiences.isNotEmpty()) "Audiences: ${assignment.role.audiences.joinToString(", ")}." else "",
    ).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun affectText(affect: AffectState): String =
    "Affect: valence ${fixed2(affect.valence)}, arousal ${fixed2(affect.arousal)}, " +
        "stress ${fixed2(affect.stress)}, fatigue ${fixed2(affect.fatigue)}, mood ${affect.mood}."

private fun memoryText(memory: Memory): String {
    val content: String = if (memory.content.length > 400) memory.content.substring(0, 397) + "..." else memory.content
    return "[${memory.kind}] $content"
}

private fun relationshipText(relationship: Relationship, pid: String): String {
    val other: String = if (relationship.fromPid == pid) relationship.toPid else relationship.fromPid
    val obligations: String =
        if (relationship.obligations.isNotEmpty()) " Obligations: ${relationship.obligations.joinToString("; ")}." else ""
    return "${relationship.kind} with person $other: trust ${fixed2(relationship.trust)}, " +
        "attachment ${fixed2(relationship.attachment)}.$obligations"
}

private fun membershipText(membership: Membership): String {
    val norms: String =
        if (membership.institution.norms.isNotEmpty()) " Norms: ${membership.institution.norms.joinToString("; ")}." else ""
    return "Member of ${membership.institution.name} as ${membership.role} (${membership.status}).$norms"
}

private fun backgroundText(process: BackgroundProcess): String {
    val trigger: String = process.trigger?.let { ", trigger: $it" } ?: ""
    return "${process.kind}: ${process.label} (intensity ${fixed2(process.intensity)}$trigger)."
}

private fun recencyScore(at: String, now: Double?): Double {
    val then: Double = parseEpochMillis(at) ?: return 0.5
    if (now == null) {
        return 0.5
    }
    val days: Double = maxOf(0.0, (now - then) / 86_400_000.0)
    return 1.0 / (1.0 + days / 30.0)
}

private fun memoryScore(memory: Memory, activeRoleId: String?, now: Double?): Double {
    val roleBoost: Double = if (memory.roleId != null && memory.roleId == activeRoleId) 0.2 else 0.0
    return clamp01(memory.salience * 0.7 + recencyScore(memory.at, now) * 0.3 + roleBoost)
}

private class GroupUsage(var value: Int = 0)

private data class RankedMemory(val memory: Memory, val score: Double)

fun assembleAgentContext(
    agent: AgentReader,
    social: SocialReader,
    pid: String,
    budget: Budget,
    options: AgentContextOptions = AgentContextOptions(),
): AgentContext {
    val profile: AgentProfile = agent.profile(pid)
        ?: throw IllegalArgumentException("agent_not_found: pid $pid")
    val narrative: SelfNarrative = agent.narrative(pid)
        ?: throw IllegalArgumentException("agent_not_found: narrative for pid $pid")

    val now: Double? = options.now?.let { parseEpochMillis(it) }
    val total: Int = (budget.tokens ?: 0).takeIf { it > 0 } ?: DEFAULT_TOKENS
    val include: Set<AgentContextKind> = options.includeKinds?.toSet() ?: AgentContextKind.entries.toSet()
    val share: (AgentContextKind) -> Double = { kind -> SHARES.getValue(kind) }

    val activeRole: RoleAssignment? = agent.activeRole(pid)
    val affect: AffectState? = agent.affect(pid)
    val memories: List<Memory> = agent.memories(pid, 32)
    val relationships: List<Relationship> = social.relationships(pid).sortedByDescending { it.trust }
    val memberships: List<Membership> = social.memberships(pid)
    val background: List<BackgroundProcess> = agent.background(pid, "active")

    val segments: MutableList<AgentContextSegment> = ArrayList()
    val excluded: MutableList<ExcludedSegment> = ArrayList()
    var used: Int = 0

    fun add(
        kind: AgentContextKind,
        ref: Ref?,
        text: String,
        cap: Int,
        group: GroupUsage,
    ): Boolean {
        val tokens: Int = estimateTokens(text)
        if (used + tokens > total || group.value + tokens > cap) {
            excluded.add(ExcludedSegment(kind, ref, ExcludedReason.BUDGET))
            return false
        }
        segments.add(AgentContextSegment(kind, ref, text, tokens))
        used += tokens
        group.value += tokens
        return true
    }

    if (include.contains(AgentContextKind.SELF)) {
        val ref: Ref = refFor(AgentContextKind.SELF, "agent.$pid", profile.name)
        add(AgentContextKind.SELF, ref, selfText(profile, narrative), (total * share(AgentContextKind.SELF)).toInt(), GroupUsage())
    }

    if (include.contains(AgentContextKind.ROLE) && activeRole != null) {
        val ref: Ref = refFor(AgentContextKind.ROLE, activeRole.role.id, activeRole.role.label)
        add(AgentContextKind.ROLE, ref, roleText(activeRole), (total * share(AgentContextKind.ROLE)).toInt(), GroupUsage())
    }

    if (include.contains(AgentContextKind.AFFECT) && affect != null) {
        val ref: Ref = refFor(AgentContextKind.AFFECT, "affect.$pid", affect.mood)
        add(AgentContextKind.AFFECT, ref, affectText(affect), (total * share(AgentContextKind.AFFECT)).toInt(), GroupUsage())
    }

    if (include.contains(AgentContextKind.MEMORY)) {
        val cap: Int = (total * share(AgentContextKind.MEMORY)).toInt()
        val group = GroupUsage()
        val ranked: List<RankedMemory> = memories
            .map { RankedMemory(it, memoryScore(it, activeRole?.role?.id, now)) }
            .sortedByDescending { it.score }
        for (entry in ranked) {
            val ref: Ref = refFor(AgentContextKind.MEMORY, entry.memory.id)
            if (!add(AgentContextKind.MEMORY, ref, memoryText(entry.memory), cap, group)) {
                excluded.add(ExcludedSegment(AgentContextKind.MEMORY, ref, ExcludedReason.LOW_SALIENCE))
            }
        }
    }

    if (include.contains(AgentContextKind.RELATIONSHIP)) {
        val cap: Int = (total * share(AgentContextKind.RELATIONSHIP)).toInt()
        val group = GroupUsage()
        for (relationship in relationships) {
            val ref: Ref = refFor(AgentContextKind.RELATIONSHIP, relationship.id, relationship.kind)
            add(AgentContextKind.RELATIONSHIP, ref, relationshipText(relationship, pid), cap, group)
        }
    }

    if (include.contains(AgentContextKind.MEMBERSHIP)) {
        val group = GroupUsage()
        for (membership in memberships) {
            val ref: Ref = refFor(AgentContextKind.MEMBERSHIP, membership.institution.id, membership.institution.name)
            add(
                AgentContextKind.MEMBERSHIP,
                ref,
                membershipText(membership),
                maxOf(1, (total * share(AgentContextKind.MEMBERSHIP)).toInt()),
                group,
            )
        }
    }

    if (include.contains(AgentContextKind.BACKGROUND)) {
        val cap: Int = (total * share(AgentContextKind.BACKGROUND)).toInt()
        val group = GroupUsage()
        for (process in background) {
            val ref: Ref = refFor(AgentContextKind.BACKGROUND, process.id, process.kind)
            add(AgentContextKind.BACKGROUND, ref, backgroundText(process), cap, group)
        }
    }

    return AgentContext(
        profile = profile,
        narrative = narrative,
        activeRole = activeRole,
        affect = affect,
        memories = memories,
        relationships = relationships,
        memberships = memberships,
        background = background,
        segments = segments,
        tokens = used,
        excluded = excluded,
    )
}
