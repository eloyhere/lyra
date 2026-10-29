package com.eloykim.lyra.amp.protocol.ipc

import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * `src/ipc/social.ts` 的忠实移植：社会事实——关系与机构不是消息历史。
 *
 * 关系是智能体之间持久的信任/依恋/义务结构；机构是规范性的成员资格。两者都被 agent
 * context 组装器读取，并且必须在适配层做 capability 作用域。
 *
 * `Pid` 在参考实现里是 `number`，这里用 [Int]。
 */

enum class MembershipStatus(val wire: String) {
    ACTIVE("active"),
    LEFT("left"),
    ;

    companion object {
        fun fromWire(value: String): MembershipStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 membership status: $value")
    }
}

data class Relationship(
    val id: Id,
    val fromPid: Int,
    val toPid: Int,
    val kind: String,
    /** [0, 1] */
    val trust: Double,
    /** [0, 1] */
    val attachment: Double,
    val obligations: List<String>,
    val since: Timestamp,
    val lastContact: Timestamp? = null,
)

data class Institution(
    val id: Id,
    val name: String,
    val norms: List<String>,
)

data class Membership(
    val institution: Institution,
    val role: String,
    val status: MembershipStatus,
    val since: Timestamp,
)

data class NewRelationship(
    val fromPid: Int,
    val toPid: Int,
    val kind: String,
    val since: Timestamp,
    val trust: Double? = null,
    val attachment: Double? = null,
    val obligations: List<String>? = null,
    val lastContact: Timestamp? = null,
)

data class NewInstitution(
    val id: Id,
    val name: String,
    val norms: List<String>? = null,
)

data class JoinInstitutionInput(
    val pid: Int,
    val institutionId: Id,
    val role: String,
    val since: Timestamp,
)

interface SocialReader {

    fun relationships(pid: Int): List<Relationship>

    fun memberships(pid: Int): List<Membership>

    fun institution(id: Id): Institution?
}

interface SocialStore : SocialReader {

    fun upsertRelationship(input: NewRelationship): Relationship

    fun createInstitution(input: NewInstitution): Institution

    fun joinInstitution(input: JoinInstitutionInput): Membership

    fun leaveInstitution(pid: Int, institutionId: Id, at: Timestamp): Membership
}

/** `social.relationship.updated` / `social.membership.changed`。 */
sealed interface SocialEvent {

    val type: String

    data class RelationshipUpdated(
        val relationshipId: Id,
        val at: Timestamp,
    ) : SocialEvent {

        override val type: String get() = "social.relationship.updated"
    }

    data class MembershipChanged(
        val pid: Int,
        val institutionId: Id,
        val at: Timestamp,
    ) : SocialEvent {

        override val type: String get() = "social.membership.changed"
    }
}
