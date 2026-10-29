package com.eloykim.lyra.amp.protocol.ipc

import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * `src/ipc/memorySocialStore.ts` 的忠实移植：`SocialStore` 的内存参考实现。
 *
 * 关系是持久纽带；机构是规范性成员资格。存储适配器实现同一个端口。
 *
 * 参考实现抛 `AgentError`（`src/error/agentError.ts`，尚未移植，属于 agent facets）。这里
 * 定义窄局部错误 [SocialStoreError] 并保留参考的错误码字符串
 * （`institution_not_found`，参考连“机构已存在”也复用它）。
 */

class SocialStoreError(
    val code: String,
    details: List<String> = emptyList(),
) : IllegalArgumentException("$code: ${details.joinToString(", ")}") {

    companion object {
        const val INSTITUTION_NOT_FOUND: String = "institution_not_found"
    }
}

private fun clamp01(value: Double): Double = Math.max(0.0, Math.min(1.0, value))

class InMemorySocialStore : SocialStore {

    private val relationshipRows = LinkedHashMap<String, Relationship>()
    private val institutions = LinkedHashMap<String, Institution>()
    private val membershipRows = LinkedHashMap<Int, LinkedHashMap<String, Membership>>()
    private var nextRelationship = 1

    @Synchronized
    override fun relationships(pid: Int): List<Relationship> = relationshipRows.values
        .filter { it.fromPid == pid || it.toPid == pid }
        .map { it.copy(obligations = it.obligations.toList()) }

    @Synchronized
    override fun memberships(pid: Int): List<Membership> =
        (membershipRows[pid]?.values ?: emptyList()).map { membership ->
            membership.copy(
                institution = membership.institution.copy(norms = membership.institution.norms.toList()),
            )
        }

    @Synchronized
    override fun institution(id: Id): Institution? {
        val institution: Institution = institutions[id] ?: return null
        return institution.copy(norms = institution.norms.toList())
    }

    @Synchronized
    override fun upsertRelationship(input: NewRelationship): Relationship {
        val key = "${input.fromPid}:${input.toPid}:${input.kind}"
        val existing: Relationship? = relationshipRows[key]
        val relationship = Relationship(
            id = existing?.id ?: "rel.$nextRelationship",
            fromPid = input.fromPid,
            toPid = input.toPid,
            kind = input.kind,
            trust = clamp01(input.trust ?: 0.5),
            attachment = clamp01(input.attachment ?: 0.5),
            obligations = (input.obligations ?: emptyList()).toList(),
            since = input.since,
            lastContact = input.lastContact,
        )
        if (existing == null) {
            nextRelationship += 1
        }
        relationshipRows[key] = relationship
        return relationship.copy(obligations = relationship.obligations.toList())
    }

    @Synchronized
    override fun createInstitution(input: NewInstitution): Institution {
        if (institutions.containsKey(input.id)) {
            throw SocialStoreError(
                SocialStoreError.INSTITUTION_NOT_FOUND,
                listOf("institution ${input.id} already exists"),
            )
        }
        val institution = Institution(
            id = input.id,
            name = input.name,
            norms = (input.norms ?: emptyList()).toList(),
        )
        institutions[institution.id] = institution
        return institution.copy(norms = institution.norms.toList())
    }

    @Synchronized
    override fun joinInstitution(input: JoinInstitutionInput): Membership {
        val institution: Institution = institutions[input.institutionId]
            ?: throw SocialStoreError(
                SocialStoreError.INSTITUTION_NOT_FOUND,
                listOf("institution ${input.institutionId}"),
            )
        val membership = Membership(
            institution = institution.copy(norms = institution.norms.toList()),
            role = input.role,
            status = MembershipStatus.ACTIVE,
            since = input.since,
        )
        val rows: LinkedHashMap<String, Membership> =
            membershipRows[input.pid] ?: LinkedHashMap()
        rows[input.institutionId] = membership
        membershipRows[input.pid] = rows
        return membership
    }

    @Synchronized
    override fun leaveInstitution(pid: Int, institutionId: Id, at: Timestamp): Membership {
        val rows: LinkedHashMap<String, Membership>? = membershipRows[pid]
        val membership: Membership? = rows?.get(institutionId)
        if (rows == null || membership == null) {
            throw SocialStoreError(
                SocialStoreError.INSTITUTION_NOT_FOUND,
                listOf("membership $pid/$institutionId"),
            )
        }
        // 参考实现原地修改并返回同一个对象；Kotlin 数据类以替换表达同一观察结果。
        val left: Membership = membership.copy(status = MembershipStatus.LEFT, since = at)
        rows[institutionId] = left
        return left
    }
}
