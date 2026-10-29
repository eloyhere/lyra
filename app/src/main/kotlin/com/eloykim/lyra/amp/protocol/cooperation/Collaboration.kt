package com.eloykim.lyra.amp.protocol.cooperation

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.protocol.DelegationDoc
import com.eloykim.lyra.amp.protocol.protocol.LeaseDoc
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * AMP 0.1 协作对象（`src/cooperation/collaboration.ts` 的忠实移植；对应
 * `collaboration.schema.json`）。
 *
 * 委托是 wire 对象，租约是本地执行权。两者都不把授予者已有的权限之外的东西转移
 * 出去。被委托方的回答只有一种被接受的形状：claims + evidence + unknowns。
 *
 * ## 绑定
 *
 * - `delegation` / `lease` 的 wire 文档已经在 `protocol/Wire.kt` 落地（兄弟移植，
 *   同时承担 `src/delegate/team.ts` 的可见运行时形状）。本文件不重复定义，
 *   只用 [CollaborationObject] 绑定三者的 `oneOf` 分派。
 * - 团队清单与共同承诺记录（`src/delegate/teamTypes.ts`，`team.schema.json`）
 *   已由 `delegate/TeamTypes.kt` 移植，同样不在此重复。
 */

/** 结果契约的状态。 */
enum class ResultStatus(val wire: String) {
    OK("ok"),
    PARTIAL("partial"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String): ResultStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 result status: $value")
    }
}

/** 结果产物：内容寻址，不在结果契约里内联正文。 */
data class ResultArtifact(
    val id: Id,
    val digest: Digest,
) {

    init {
        ProtocolIds.require(id, "result artifact.id")
        require(Sha256.isValidDigest(digest)) { "result artifact.digest 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "digest" to jsonText(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): ResultArtifact {
            val obj = value.asObject("result artifact")
            return ResultArtifact(
                id = obj.requireString("id"),
                digest = obj.requireString("digest"),
            )
        }
    }
}

/**
 * 被委托方回答唯一被接受的形状：claims + evidence + unknowns。
 *
 * 没有自由文本总结：结论必须落成可引用的主张，未知必须显式列出。
 */
data class ResultContract(
    val id: Id,
    val lease: Id,
    val goalDigest: Digest,
    val claims: List<Ref>,
    val evidence: List<Ref>,
    val unknowns: List<String>,
    val artifacts: List<ResultArtifact>,
    val status: ResultStatus,
) {

    init {
        ProtocolIds.require(id, "result contract.id")
        ProtocolIds.require(lease, "result contract.lease")
        require(Sha256.isValidDigest(goalDigest)) { "result contract.goal_digest 格式非法" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to jsonText(AmpWire.VERSION),
        "kind" to jsonText(KIND),
        "id" to jsonText(id),
        "lease" to jsonText(lease),
        "goal_digest" to jsonText(goalDigest),
        "claims" to JsonValue.Arr(claims.map { it.toJson() }),
        "evidence" to JsonValue.Arr(evidence.map { it.toJson() }),
        "unknowns" to jsonStrings(unknowns),
        "artifacts" to JsonValue.Arr(artifacts.map { it.toJson() }),
        "status" to jsonText(status.wire),
    )

    companion object {

        const val KIND: String = "result-contract"

        fun fromJson(value: JsonValue): ResultContract {
            val obj = value.asObject("result contract")
            require(obj.requireString("amp") == AmpWire.VERSION) {
                "result-contract.amp 必须是 ${AmpWire.VERSION}"
            }
            require(obj.requireString("kind") == KIND) { "result-contract.kind 必须是 $KIND" }
            return ResultContract(
                id = obj.requireString("id"),
                lease = obj.requireString("lease"),
                goalDigest = obj.requireString("goal_digest"),
                claims = obj.requireArr("claims").items.map { Ref.fromJson(it) },
                evidence = obj.requireArr("evidence").items.map { Ref.fromJson(it) },
                unknowns = obj.requireArr("unknowns").items.map {
                    (it as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("result-contract.unknowns 必须是字符串数组")
                },
                artifacts = obj.requireArr("artifacts").items.map { ResultArtifact.fromJson(it) },
                status = ResultStatus.fromWire(obj.requireString("status")),
            )
        }
    }
}

/**
 * `collaboration.schema.json` 的三个分支。
 *
 * delegation/lease 的文档类型来自 `protocol/Wire.kt`；result-contract 在本文件。
 */
sealed interface CollaborationObject {

    fun toJson(): JsonValue.Obj

    data class Delegation(val doc: DelegationDoc) : CollaborationObject {
        override fun toJson(): JsonValue.Obj = doc.toJson()
    }

    data class Lease(val doc: LeaseDoc) : CollaborationObject {
        override fun toJson(): JsonValue.Obj = doc.toJson()
    }

    data class Result(val contract: ResultContract) : CollaborationObject {
        override fun toJson(): JsonValue.Obj = contract.toJson()
    }

    companion object {
        fun fromJson(value: JsonValue): CollaborationObject {
            val obj = value.asObject("collaboration")
            return when (val kind = obj.requireString("kind")) {
                "delegation" -> Delegation(DelegationDoc.fromJson(obj))
                "lease" -> Lease(LeaseDoc.fromJson(obj))
                ResultContract.KIND -> Result(ResultContract.fromJson(obj))
                else -> throw IllegalArgumentException("未知 collaboration kind: $kind")
            }
        }
    }
}
