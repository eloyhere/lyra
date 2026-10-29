package com.eloykim.lyra.amp.protocol.delegate

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Criterion
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * `src/delegate/teamTypes.ts` 的忠实移植：team manifest 与 commitment record。
 *
 * 对应 `amp/schemas/team.schema.json`（一个 `oneOf`：team-manifest 或
 * commitment-record）。参考文件只有类型定义；这里按 PORTING.md 补上
 * `toJson` / `fromJson`，字段名与 schema 一致（snake_case）。
 */

enum class TeamTermination(val wire: String) {
    VERIFIED("verified"),
    BUDGET("budget"),
    STALL("stall"),
    AUTHORITY("authority"),
    ;

    companion object {
        fun fromWire(value: String): TeamTermination =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 team.termination: $value")
    }
}

data class TeamMember(
    val id: Id,
    val role: String,
    /**
     * 显式、可枚举的权限。成员永远不能超出它。
     */
    val authority: List<String>,
    val capabilities: List<String>? = null,
) {

    init {
        ProtocolIds.require(id, "team.member.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "role" to JsonValue.Str(role),
        "capabilities" to capabilities?.let { jsonStrings(it) },
        "authority" to jsonStrings(authority),
    )

    companion object {
        fun fromJson(value: JsonValue): TeamMember {
            val obj = value.asObject("team.member")
            return TeamMember(
                id = obj.requireString("id"),
                role = obj.requireString("role"),
                authority = obj.requireStrings("authority"),
                capabilities = obj.optionalStrings("capabilities"),
            )
        }
    }
}

data class TeamManifest(
    val id: Id,
    val version: Int,
    val members: List<TeamMember>,
    val maxDepth: Int,
    val maxFanout: Int,
    val budget: Budget,
    val termination: TeamTermination,
    val attestedAt: Timestamp,
) : TeamObject {

    init {
        ProtocolIds.require(id, "team-manifest.id")
        require(version >= 1) { "team-manifest.version 必须 >= 1" }
        require(members.isNotEmpty()) { "team-manifest.members 至少要有一个成员" }
        require(maxDepth in 0..2) { "team-manifest.max_depth 必须在 0..2" }
        require(maxFanout in 1..10) { "team-manifest.max_fanout 必须在 1..10" }
        require(isTimestamp(attestedAt)) { "team-manifest.attested_at 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str(KIND),
        "id" to JsonValue.Str(id),
        "version" to jsonNumber(version),
        "members" to JsonValue.Arr(members.map { it.toJson() }),
        "max_depth" to jsonNumber(maxDepth),
        "max_fanout" to jsonNumber(maxFanout),
        "budget" to budget.toJson(),
        "termination" to JsonValue.Str(termination.wire),
        "attested_at" to JsonValue.Str(attestedAt),
    )

    companion object {

        const val KIND: String = "team-manifest"

        fun fromJson(value: JsonValue): TeamManifest {
            val obj = value.asObject("team-manifest")
            require(obj.requireString("amp") == AmpWire.VERSION) { "team-manifest.amp 必须是 ${AmpWire.VERSION}" }
            require(obj.requireString("kind") == KIND) { "team-manifest.kind 必须是 $KIND" }
            return TeamManifest(
                id = obj.requireString("id"),
                version = obj.requireInt("version"),
                members = obj.requireArr("members").items.map(TeamMember::fromJson),
                maxDepth = obj.requireInt("max_depth"),
                maxFanout = obj.requireInt("max_fanout"),
                budget = Budget.fromJson(obj.requireObj("budget")),
                termination = TeamTermination.fromWire(obj.requireString("termination")),
                attestedAt = obj.requireString("attested_at"),
            )
        }
    }
}

enum class CommitmentStatus(val wire: String) {
    PROPOSED("proposed"),
    ACTIVE("active"),
    DISCHARGED("discharged"),
    CANCELLED("cancelled"),
    BREACHED("breached"),
    ;

    companion object {
        fun fromWire(value: String): CommitmentStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 commitment.status: $value")
    }
}

/**
 * 共同承诺：只有各方都接受后才生效，且不能被单方撤销（Gilbert）。违约走
 * notify-and-repair 路径。
 */
data class CommitmentRecord(
    val id: Id,
    val parties: List<Id>,
    val proposition: String,
    val satisfaction: List<Criterion>,
    val status: CommitmentStatus,
    val proposedBy: Id,
    val created: Timestamp,
    val acceptedBy: List<Id>? = null,
) : TeamObject {

    init {
        ProtocolIds.require(id, "commitment-record.id")
        require(parties.size >= 2) { "commitment-record.parties 至少要两方" }
        parties.forEach { ProtocolIds.require(it, "commitment-record.parties") }
        require(proposition.isNotEmpty()) { "commitment-record.proposition 不能为空" }
        require(satisfaction.isNotEmpty()) { "commitment-record.satisfaction 不能为空" }
        ProtocolIds.require(proposedBy, "commitment-record.proposed_by")
        acceptedBy?.forEach { ProtocolIds.require(it, "commitment-record.accepted_by") }
        require(isTimestamp(created)) { "commitment-record.created 不是合法时间" }
    }

    override fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AmpWire.VERSION),
        "kind" to JsonValue.Str(KIND),
        "id" to JsonValue.Str(id),
        "parties" to jsonStrings(parties),
        "proposition" to JsonValue.Str(proposition),
        "satisfaction" to JsonValue.Arr(satisfaction.map { it.toJson() }),
        "status" to JsonValue.Str(status.wire),
        "proposed_by" to JsonValue.Str(proposedBy),
        "accepted_by" to acceptedBy?.let { jsonStrings(it) },
        "created" to JsonValue.Str(created),
    )

    companion object {

        const val KIND: String = "commitment-record"

        fun fromJson(value: JsonValue): CommitmentRecord {
            val obj = value.asObject("commitment-record")
            require(obj.requireString("amp") == AmpWire.VERSION) {
                "commitment-record.amp 必须是 ${AmpWire.VERSION}"
            }
            require(obj.requireString("kind") == KIND) { "commitment-record.kind 必须是 $KIND" }
            return CommitmentRecord(
                id = obj.requireString("id"),
                parties = obj.requireStrings("parties"),
                proposition = obj.requireString("proposition"),
                satisfaction = obj.requireArr("satisfaction").items.map(Criterion::fromJson),
                status = CommitmentStatus.fromWire(obj.requireString("status")),
                proposedBy = obj.requireString("proposed_by"),
                created = obj.requireString("created"),
                acceptedBy = obj.optionalStrings("accepted_by"),
            )
        }
    }
}

/** `team.schema.json` 的两个分支。 */
sealed interface TeamObject {

    fun toJson(): JsonValue.Obj

    companion object {
        fun fromJson(value: JsonValue): TeamObject {
            val obj = value.asObject("team")
            return when (obj.requireString("kind")) {
                TeamManifest.KIND -> TeamManifest.fromJson(value)
                CommitmentRecord.KIND -> CommitmentRecord.fromJson(value)
                else -> throw IllegalArgumentException("未知 team kind: ${obj.string("kind")}")
            }
        }
    }
}

private fun JsonValue.Obj.requireStrings(key: String): List<String> =
    requireArr(key).items.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }

private fun JsonValue.Obj.optionalStrings(key: String): List<String>? =
    arr(key)?.items?.map { item ->
        (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 必须是字符串数组")
    }
