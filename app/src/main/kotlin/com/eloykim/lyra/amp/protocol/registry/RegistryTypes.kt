package com.eloykim.lyra.amp.protocol.registry

import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * `src/registry/registry.ts` 的类型面。
 *
 * 参考实现把行类型放在 `src/database/entity/registry.ts`；数据库层尚未移植，这里把
 * 实体类型与 registry 的输入/结果类型放在一起，字段与参考一一对应（snake_case 的
 * 列名改成 Kotlin 属性名，其余不变）。
 */

/** 概念/模块的生命周期状态。 */
enum class RegistryStatus(val wire: String) {
    PROPOSED("proposed"),
    ACTIVE("active"),
    DEPRECATED("deprecated"),
    ;

    companion object {
        fun fromWire(value: String): RegistryStatus =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 registry status: $value")
    }
}

/** 模块种类；与参考实现的 `ModuleKind` 完全一致。 */
enum class ModuleKind(val wire: String) {
    KERNEL("kernel"),
    DATABASE("database"),
    DOMAIN("domain"),
    PROTOCOL("protocol"),
    MODULE("module"),
    STRUCTURE("structure"),
    TOOL("tool"),
    BOUNDARY("boundary"),
    ;

    companion object {
        fun fromWire(value: String): ModuleKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 module kind: $value")
    }
}

/** 概念到模块的边角色。 */
enum class ConceptLinkRole(val wire: String) {
    OWNS("owns"),
    USES("uses"),
    CONSTRAINS("constrains"),
    IMPLEMENTS("implements"),
    ;

    companion object {
        fun fromWire(value: String): ConceptLinkRole =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 concept link role: $value")
    }
}

/** attestation 指向的主语种类。 */
enum class RegistrySubjectKind(val wire: String) {
    CONCEPT("concept"),
    MODULE("module"),
    ;

    companion object {
        fun fromWire(value: String): RegistrySubjectKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 registry subject kind: $value")
    }
}

/** `declareConcept` 的输入。 */
data class ConceptInput(
    val id: Id,
    val label: String,
    val definition: String,
    val status: RegistryStatus? = null,
)

/** `declareModule` 的输入；`contentDigest` 在声明时绑定目录内容以便漂移检查。 */
data class ModuleInput(
    val id: Id,
    val kind: ModuleKind,
    val entry: String,
    val description: String,
    val status: RegistryStatus? = null,
    val contentDigest: Digest? = null,
)

/** 一次声明写入对象链后的结果。 */
data class DeclarationResult<T>(
    val row: T,
    val objectId: String,
    val hash: Digest,
)

/** 一次 attestation 的结果。 */
data class AttestationResult(
    val aid: Int,
    val objectId: String,
    val hash: Digest,
)

/** 模块依赖边，字段与参考的 `DependencyRow` 一致。 */
data class DependencyRow(
    val moduleId: Id,
    val dependsOn: Id,
    val reason: String? = null,
)

/** registry 检查的共享结果。 */
data class VerifyReport(
    val ok: Boolean,
    val reasons: List<String>,
) {
    companion object {
        fun of(reasons: List<String>): VerifyReport =
            VerifyReport(ok = reasons.isEmpty(), reasons = ArrayList(reasons))
    }
}

/** 概念当前状态；历史在对象链里。 */
data class ConceptEntity(
    val cid: Int,
    val id: Id,
    val label: String,
    val definition: String,
    val status: RegistryStatus,
    val version: Int,
    val objectSeq: Int?,
    val digest: Digest?,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
)

/** 模块当前状态；磁盘目录只是它的物理影子。 */
data class ModuleEntity(
    val mid: Int,
    val id: Id,
    val kind: ModuleKind,
    val entry: String,
    val description: String,
    val status: RegistryStatus,
    val version: Int,
    val objectSeq: Int?,
    val digest: Digest?,
    val contentDigest: Digest?,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
)

/**
 * 对象链中的一行。
 *
 * `body` 是 canonical JSON 文本（参考实现 `objects.body` 列），因此可以在测试里像
 * 直接改数据库一样篡改它，[RegistryObjectChain.verify] 会因此失败。`hash` 覆盖
 * `{prev, kind, id, body}`，与 `database/store/database.ts#putObject` 完全一致。
 */
data class RegistryObject(
    val seq: Int,
    val kind: String,
    val id: String,
    var body: String,
    val hash: Digest,
    val prev: Digest,
    val at: Timestamp,
)

/** `putObject` 的返回值。 */
data class PutObjectResult(
    val seq: Int,
    val hash: Digest,
)

/** 概念/模块与概念/模块之间的连接行。 */
data class ModuleConceptLink(
    val mid: Int,
    val cid: Int,
    val role: ConceptLinkRole,
)

/** 模块到模块的依赖行。 */
data class ModuleDependencyLink(
    val mid: Int,
    val dependsOnMid: Int,
    val reason: String?,
)

/** attestation 当前状态。 */
data class AttestationRecord(
    val aid: Int,
    val subjectKind: RegistrySubjectKind,
    val subjectId: Id,
    val claimId: Id,
    val objectSeq: Int?,
    val at: Timestamp,
)
