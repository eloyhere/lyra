package com.eloykim.lyra.amp.protocol.registry

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.util.ArrayDeque

/**
 * `src/registry/registry.ts` 的忠实移植：概念与模块是一等数据行。
 *
 * 磁盘上存在一个目录，并不证明该模块属于架构。声明会向哈希链写入不可变的
 * `module.<id>.v<n>` 对象，再更新当前行；当前行指向该对象，于是“这个模块存在、
 * 版本为 n、描述如下”是可检查的。
 *
 * ## 绑定缝
 *
 * 参考实现直接执行 SQL。这里把所有语句收敛成 [RegistryDatabase]（行、连接、依赖、
 * attestation）与 [RegistryObjectChain]（`putObject`/`getObject`/`verifyObjectChain`）
 * 两个窄端口；[InMemoryRegistryDatabase] 是 Android 参考实现。SQLite 适配器按参考
 * SQL 实现同一端口即可。
 *
 * 目录内容摘要沿用已移植的 [digestDirectory]：参考 `verifyModuleContent` 把计算函数
 * 作为参数注入，这里保持同一形状，测试或宿主传 `{ path -> digestDirectory(File(root, path)) }`。
 *
 * 参考实现在这些函数里抛裸 `Error`（不是 [com.eloykim.lyra.amp.protocol.error.AmpError]），
 * 本移植用 [IllegalArgumentException]，消息保持原样。
 */

private const val AMP_VERSION: String = "0.1"

private fun requireText(value: String, field: String) {
    if (value.trim().isEmpty()) {
        throw IllegalArgumentException("$field must not be empty")
    }
}

/**
 * 声明一个概念，版本号在已有行上递增。
 *
 * 对象 id 为 `concept.<id>.v<version>`，body 字段顺序与参考实现的字面量一致；
 * digest 由 canonical JSON 计算，没有任何自造摘要。
 */
fun declareConcept(
    db: RegistryDatabase,
    input: ConceptInput,
    at: Timestamp,
): DeclarationResult<ConceptEntity> {
    if (!ProtocolIds.isValid(input.id)) {
        throw IllegalArgumentException("concept id ${input.id} is not a valid AMP id")
    }
    requireText(input.label, "label")
    requireText(input.definition, "definition")

    val existing: ConceptEntity? = db.concept(input.id)
    val version: Int = (existing?.version ?: 0) + 1
    val objectId: String = "concept.${input.id}.v$version"
    val status: RegistryStatus = input.status ?: RegistryStatus.ACTIVE
    val body: JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AMP_VERSION),
        "kind" to JsonValue.Str("concept"),
        "id" to JsonValue.Str(input.id),
        "label" to JsonValue.Str(input.label),
        "definition" to JsonValue.Str(input.definition),
        "status" to JsonValue.Str(status.wire),
        "version" to jsonNumber(version),
        "at" to JsonValue.Str(at),
    )
    val objectRow: PutObjectResult = db.objects.put("concept", objectId, body, at)
    val row: ConceptEntity = db.saveConcept(
        ConceptEntity(
            cid = existing?.cid ?: 0,
            id = input.id,
            label = input.label,
            definition = input.definition,
            status = status,
            version = version,
            objectSeq = objectRow.seq,
            digest = objectRow.hash,
            createdAt = existing?.createdAt ?: at,
            updatedAt = at,
        ),
    )
    return DeclarationResult(row = row, objectId = objectId, hash = objectRow.hash)
}

/**
 * 声明一个模块；`contentDigest` 为 null 时仍写进 body（参考实现的
 * `contentDigest: input.contentDigest ?? null`），因此摘要位置是显式 null 而不是缺失。
 */
fun declareModule(
    db: RegistryDatabase,
    input: ModuleInput,
    at: Timestamp,
): DeclarationResult<ModuleEntity> {
    if (!ProtocolIds.isValid(input.id)) {
        throw IllegalArgumentException("module id ${input.id} is not a valid AMP id")
    }
    requireText(input.entry, "entry")
    requireText(input.description, "description")

    val existing: ModuleEntity? = db.module(input.id)
    val version: Int = (existing?.version ?: 0) + 1
    val objectId: String = "module.${input.id}.v$version"
    val status: RegistryStatus = input.status ?: RegistryStatus.ACTIVE
    val body: JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AMP_VERSION),
        "kind" to JsonValue.Str("module"),
        "id" to JsonValue.Str(input.id),
        "moduleKind" to JsonValue.Str(input.kind.wire),
        "entry" to JsonValue.Str(input.entry),
        "description" to JsonValue.Str(input.description),
        "status" to JsonValue.Str(status.wire),
        "contentDigest" to (input.contentDigest?.let { JsonValue.Str(it) } ?: JsonValue.Null),
        "version" to jsonNumber(version),
        "at" to JsonValue.Str(at),
    )
    val objectRow: PutObjectResult = db.objects.put("module", objectId, body, at)
    val row: ModuleEntity = db.saveModule(
        ModuleEntity(
            mid = existing?.mid ?: 0,
            id = input.id,
            kind = input.kind,
            entry = input.entry,
            description = input.description,
            status = status,
            version = version,
            objectSeq = objectRow.seq,
            digest = objectRow.hash,
            contentDigest = input.contentDigest,
            createdAt = existing?.createdAt ?: at,
            updatedAt = at,
        ),
    )
    return DeclarationResult(row = row, objectId = objectId, hash = objectRow.hash)
}

/**
 * 连接模块与概念；`INSERT OR IGNORE` 的返回值就是是否真的新增了一条边。
 */
fun linkConcept(
    db: RegistryDatabase,
    moduleId: Id,
    conceptId: Id,
    role: ConceptLinkRole,
): Boolean {
    val module: ModuleEntity = db.module(moduleId)
        ?: throw IllegalArgumentException("unknown module $moduleId")
    val concept: ConceptEntity = db.concept(conceptId)
        ?: throw IllegalArgumentException("unknown concept $conceptId")
    return db.insertModuleConcept(module.mid, concept.cid, role)
}

/**
 * 读取一个模块的直接依赖，按依赖方 id 升序。
 *
 * 与参考 SQL 一样，模块不存在时返回空列表而不是抛错。
 */
fun dependencies(db: RegistryDatabase, moduleId: Id): List<DependencyRow> {
    val idByMid: Map<Int, Id> = db.modules().associate { it.mid to it.id }
    if (!db.modules().any { it.id == moduleId }) {
        return emptyList()
    }
    return db.moduleDependencies()
        .mapNotNull { link ->
            val from: Id = idByMid[link.mid] ?: return@mapNotNull null
            val to: Id = idByMid[link.dependsOnMid] ?: return@mapNotNull null
            if (from != moduleId) {
                null
            } else {
                DependencyRow(moduleId = from, dependsOn = to, reason = link.reason)
            }
        }
        .sortedBy { it.dependsOn }
}

/**
 * 连接模块依赖；自依赖直接拒绝。插入后成环则删除刚插入的边并抛错，与参考实现
 * 先插入、`hasDependencyCycle`、再 `DELETE` 的失败语义一致。
 */
fun linkDependency(
    db: RegistryDatabase,
    moduleId: Id,
    dependencyId: Id,
    reason: String = "",
): Boolean {
    if (moduleId == dependencyId) {
        throw IllegalArgumentException("module $moduleId cannot depend on itself")
    }
    val module: ModuleEntity = db.module(moduleId)
        ?: throw IllegalArgumentException("unknown module $moduleId")
    val dependency: ModuleEntity = db.module(dependencyId)
        ?: throw IllegalArgumentException("unknown module $dependencyId")
    if (!db.insertModuleDependency(module.mid, dependency.mid, reason)) {
        return false
    }
    if (hasDependencyCycle(db)) {
        db.deleteModuleDependency(module.mid, dependency.mid)
        throw IllegalArgumentException("linking $moduleId -> $dependencyId would create a dependency cycle")
    }
    return true
}

/** 依赖图的一帧；`next` 可变，等价于参考实现的 depth-first 游标。 */
private data class CycleFrame(
    val id: Id,
    var next: Int,
    val neighbors: List<Id>,
)

/**
 * 对依赖图做迭代 DFS。用显式栈而不是递归，与参考实现一样不受图深度限制。
 */
fun hasDependencyCycle(db: RegistryDatabase): Boolean {
    val modules: List<ModuleEntity> = db.modules()
    val idByMid: Map<Int, Id> = modules.associate { it.mid to it.id }
    val adjacency: MutableMap<Id, MutableList<Id>> = LinkedHashMap()
    db.moduleDependencies().forEach { edge ->
        val from: Id = idByMid[edge.mid] ?: return@forEach
        val to: Id = idByMid[edge.dependsOnMid] ?: return@forEach
        adjacency.getOrPut(from) { ArrayList() }.add(to)
    }

    val visiting: MutableSet<Id> = HashSet()
    val visited: MutableSet<Id> = HashSet()
    for (module in modules) {
        if (visited.contains(module.id)) {
            continue
        }
        val stack = ArrayDeque<CycleFrame>()
        stack.addLast(
            CycleFrame(
                id = module.id,
                next = 0,
                neighbors = adjacency[module.id]?.toList() ?: emptyList(),
            ),
        )
        visiting.add(module.id)
        while (stack.isNotEmpty()) {
            val frame: CycleFrame = stack.peekLast()
            if (frame.next >= frame.neighbors.size) {
                stack.removeLast()
                visiting.remove(frame.id)
                visited.add(frame.id)
                continue
            }
            val next: Id = frame.neighbors[frame.next]
            frame.next += 1
            if (visiting.contains(next)) {
                return true
            }
            if (!visited.contains(next)) {
                visiting.add(next)
                stack.addLast(
                    CycleFrame(
                        id = next,
                        next = 0,
                        neighbors = adjacency[next]?.toList() ?: emptyList(),
                    ),
                )
            }
        }
    }
    return false
}

/**
 * 重新计算每个声明了 content digest 的模块目录，并与声明值比较。
 *
 * `compute` 接收 `modules.entry`（参考实现原样传入），返回目录摘要；本模块不绑定
 * 文件系统，调用方通常传 `{ path -> digestDirectory(File(root, path)) }`。
 */
fun verifyModuleContent(
    db: RegistryDatabase,
    compute: (String) -> String,
): VerifyReport {
    val reasons = ArrayList<String>()
    db.modules()
        .filter { it.contentDigest != null }
        .sortedBy { it.id }
        .forEach { row ->
            val actual: String = compute(row.entry)
            if (actual != row.contentDigest) {
                reasons += "module ${row.id}: content changed since declaration"
            }
        }
    return VerifyReport.of(reasons)
}

/**
 * 用一条 claim 为 registry 行背书。
 *
 * attestation 是对象链里的 `evidence` 对象加一行指向它的记录，所以一个模块可以被
 * 比“目录存在”更强的东西证明。
 */
fun attest(
    db: RegistryDatabase,
    subjectKind: RegistrySubjectKind,
    subjectId: Id,
    claimId: Id,
    at: Timestamp,
): AttestationResult {
    val subjectExists: Boolean = when (subjectKind) {
        RegistrySubjectKind.MODULE -> db.module(subjectId) != null
        RegistrySubjectKind.CONCEPT -> db.concept(subjectId) != null
    }
    if (!subjectExists) {
        throw IllegalArgumentException("unknown ${subjectKind.wire} $subjectId")
    }
    if (db.objects.get("claim", claimId) == null) {
        throw IllegalArgumentException("unknown claim $claimId")
    }
    val count: Int = db.countAttestations(subjectKind, subjectId)
    val objectId: String = "ev.registry.${subjectKind.wire}.$subjectId.${count + 1}"
    val body: JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(AMP_VERSION),
        "kind" to JsonValue.Str("evidence"),
        "id" to JsonValue.Str(objectId),
        "at" to JsonValue.Str(at),
        "subject" to jsonObj(
            "kind" to JsonValue.Str(subjectKind.wire),
            "id" to JsonValue.Str(subjectId),
        ),
        "claim" to JsonValue.Str(claimId),
        "note" to JsonValue.Str("registry attestation for ${subjectKind.wire} $subjectId"),
    )
    val objectRow: PutObjectResult = db.objects.put("evidence", objectId, body, at)
    val aid: Int = db.insertAttestation(
        subjectKind = subjectKind,
        subjectId = subjectId,
        claimId = claimId,
        objectSeq = objectRow.seq,
        at = at,
    )
    return AttestationResult(aid = aid, objectId = objectId, hash = objectRow.hash)
}

/** `verifyRegistry` 遍历的行：概念与模块的公共投影。 */
private data class RegistryRow(
    val kind: String,
    val id: Id,
    val version: Int,
    val objectSeq: Int?,
    val digest: Digest?,
    val contentDigest: Digest?,
)

/**
 * 检查对象链、声明行、连接、依赖图与 attestation。
 *
 * 返回全部原因，而不是遇到第一个问题就停止：一次 verify 应给出完整的修复清单。
 * 原因文本与参考实现逐字一致。
 */
fun verifyRegistry(db: RegistryDatabase): VerifyReport {
    val reasons = ArrayList<String>(db.objects.verify().reasons)

    val rows: List<RegistryRow> = db.concepts().map { row ->
        RegistryRow(
            kind = "concept",
            id = row.id,
            version = row.version,
            objectSeq = row.objectSeq,
            digest = row.digest,
            contentDigest = null,
        )
    } + db.modules().map { row ->
        RegistryRow(
            kind = "module",
            id = row.id,
            version = row.version,
            objectSeq = row.objectSeq,
            digest = row.digest,
            contentDigest = row.contentDigest,
        )
    }

    for (row in rows) {
        val objectSeq: Int? = row.objectSeq
        if (objectSeq == null) {
            reasons += "${row.kind} ${row.id}: no declaration object"
            continue
        }
        val objectRow: RegistryObject? = db.objects.bySeq(objectSeq)
        if (objectRow == null) {
            reasons += "${row.kind} ${row.id}: declaration object is missing"
            continue
        }
        val parsed: JsonValue = JsonParser.parse(objectRow.body)
        val body: JsonValue.Obj = parsed as? JsonValue.Obj
            ?: throw IllegalArgumentException("object ${objectRow.seq} body must be a JSON object")

        if (body.string("id") != row.id) {
            reasons += "${row.kind} ${row.id}: object id is ${render(body["id"])}"
        }
        val bodyVersion: Double? = (body["version"] as? JsonValue.Num)?.value
        if (bodyVersion == null || bodyVersion != row.version.toDouble()) {
            reasons += "${row.kind} ${row.id}: object version ${render(body["version"])} != row ${row.version}"
        }
        if (row.digest != null && row.digest != objectRow.hash) {
            reasons += "${row.kind} ${row.id}: digest does not match the object"
        }
        if (declaredContentDigest(body) != row.contentDigest) {
            reasons += "${row.kind} ${row.id}: content digest does not match the declaration object"
        }
    }

    val mids: Set<Int> = db.modules().map { it.mid }.toSet()
    val cids: Set<Int> = db.concepts().map { it.cid }.toSet()
    val danglingLinks: Int = db.moduleConcepts().count { link ->
        !mids.contains(link.mid) || !cids.contains(link.cid)
    }
    if (danglingLinks > 0) {
        reasons += "$danglingLinks module_concepts link(s) point at missing rows"
    }

    val danglingDependencies: Int = db.moduleDependencies().count { link ->
        !mids.contains(link.mid) || !mids.contains(link.dependsOnMid)
    }
    if (danglingDependencies > 0) {
        reasons += "$danglingDependencies module_dependencies link(s) point at missing rows"
    }
    if (hasDependencyCycle(db)) {
        reasons += "module dependency graph contains a cycle"
    }

    for (attestation in db.attestations()) {
        val seq: Int? = attestation.objectSeq
        val objectMissing: Boolean = seq == null || db.objects.bySeq(seq) == null
        if (objectMissing) {
            reasons += "attestation ${attestation.aid}: evidence object is missing"
        }
        if (db.objects.get("claim", attestation.claimId) == null) {
            reasons += "attestation ${attestation.aid}: claim ${attestation.claimId} is missing"
        }
    }

    return VerifyReport.of(reasons)
}

/** 参考模板字面量里的 `${value}`：字符串原样，数字按 JS 格式，缺失为 undefined。 */
private fun render(value: JsonValue?): String = when (value) {
    null -> "undefined"
    is JsonValue.Null -> "null"
    is JsonValue.Str -> value.value
    is JsonValue.Num -> CanonicalJson.formatNumber(value.value)
    is JsonValue.Bool -> value.value.toString()
    is JsonValue.Arr, is JsonValue.Obj -> CanonicalJson.encode(value)
}

/** `body.contentDigest ?? null`，但保留非字符串值以便比较失败而不是被静默当成 null。 */
private fun declaredContentDigest(body: JsonValue.Obj): Any? {
    val value: JsonValue = body["contentDigest"] ?: return null
    return when (value) {
        is JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> CanonicalJson.encode(value)
    }
}
