package com.eloykim.lyra.amp.protocol.registry

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.storage.GENESIS
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Timestamp

/**
 * 对象链的窄端口，对应 `src/database/store/database.ts` 中 registry 用到的三个函数：
 * `putObject`、`getObject`、`verifyObjectChain`。
 *
 * 参考实现把对象追加进 SQLite 的 `objects` 表；Kotlin 协议模块不依赖数据库，因此这里
 * 定义端口，[InMemoryRegistryObjectChain] 是 Android 参考实现。SQLite 适配器只需按
 * 参考 SQL 实现同一端口。
 *
 * 注意这里**不能**复用 `storage/AppendLog`：账本链的 hash 覆盖
 * `{seq, kind, payload, prev}`，而数据库对象链的 hash 覆盖 `{prev, kind, id, body}`，
 * 且 registry 还需要 `concept`/`module`/`evidence` 这些不在 `EntryKind` 里的 kind。
 * 两者都是 canonical 摘要，但不是同一条链，混用会破坏可验证性。
 */
interface RegistryObjectChain {

    /** `putObject(db, kind, id, body, at)`：追加一行并返回 seq/hash。 */
    fun put(kind: String, id: String, body: JsonValue, at: Timestamp): PutObjectResult

    /** `getObject(db, kind, id)`。 */
    fun get(kind: String, id: String): RegistryObject?

    /** 按 `seq` 读一行，供 verifyRegistry 检查声明对象是否存在。 */
    fun bySeq(seq: Int): RegistryObject?

    /** `verifyObjectChain(db)`：重算整条链，返回全部原因。 */
    fun verify(): VerifyReport

    /** 最新一行的 hash；空链返回 [GENESIS]。 */
    fun lastHash(): Digest
}

/**
 * 内存对象链：语义与 `database/store/database.ts` 一致（seq 从 1 开始、UNIQUE(kind,id)、
 * hash = digest({prev, kind, id, body: canonical(body)})、verify 逐行重算）。
 */
class InMemoryRegistryObjectChain : RegistryObjectChain {

    private val rows: MutableList<RegistryObject> = ArrayList()
    private var nextSeq: Int = 1

    @Synchronized
    override fun put(kind: String, id: String, body: JsonValue, at: Timestamp): PutObjectResult {
        require(rows.none { it.kind == kind && it.id == id }) {
            "object $kind/$id already exists"
        }
        val prev: Digest = lastHash()
        val bodyText: String = CanonicalJson.encode(body)
        val hash: Digest = hashOf(prev = prev, kind = kind, id = id, body = bodyText)
        val row = RegistryObject(
            seq = nextSeq++,
            kind = kind,
            id = id,
            body = bodyText,
            hash = hash,
            prev = prev,
            at = at,
        )
        rows += row
        return PutObjectResult(seq = row.seq, hash = row.hash)
    }

    @Synchronized
    override fun get(kind: String, id: String): RegistryObject? =
        rows.firstOrNull { it.kind == kind && it.id == id }

    @Synchronized
    override fun bySeq(seq: Int): RegistryObject? =
        rows.firstOrNull { it.seq == seq }

    @Synchronized
    override fun verify(): VerifyReport {
        val reasons = ArrayList<String>()
        var prev: Digest = GENESIS
        rows.forEach { row ->
            if (row.prev != prev) {
                reasons += "seq ${row.seq}: prev does not continue the chain"
            }
            val expected: Digest = hashOf(prev = row.prev, kind = row.kind, id = row.id, body = row.body)
            if (row.hash != expected) {
                reasons += "seq ${row.seq}: hash does not cover its body"
            }
            prev = row.hash
        }
        return VerifyReport.of(reasons)
    }

    @Synchronized
    override fun lastHash(): Digest = rows.lastOrNull()?.hash ?: GENESIS

    /** 只读快照，供测试与调试；行内 `body` 可变，便于模拟直接改库。 */
    @Synchronized
    fun entries(): List<RegistryObject> = ArrayList(rows)
}

/** `hash = digestOf({prev, kind, id, body})`，与参考 `putObject` 完全一致。 */
private fun hashOf(prev: Digest, kind: String, id: String, body: String): Digest =
    CanonicalJson.digest(
        jsonObj(
            "prev" to JsonValue.Str(prev),
            "kind" to JsonValue.Str(kind),
            "id" to JsonValue.Str(id),
            "body" to JsonValue.Str(body),
        ),
    )

/**
 * registry 的存储窄端口：参考实现直接执行 SQL，这里只保留语义操作。
 *
 * 每个方法对应 `src/registry/registry.ts` 里出现的一条或一组语句；行类型就是
 * `src/database/entity/registry.ts` 的实体。[InMemoryRegistryDatabase] 保留参考的
 * 自增主键、UNIQUE(id)、INSERT OR IGNORE 与 created_at 保持首次值等行为。
 */
interface RegistryDatabase {

    val objects: RegistryObjectChain

    fun concept(id: Id): ConceptEntity?

    fun module(id: Id): ModuleEntity?

    fun concepts(): List<ConceptEntity>

    fun modules(): List<ModuleEntity>

    /** `INSERT ... ON CONFLICT(id) DO UPDATE`；新行分配 cid。 */
    fun saveConcept(entity: ConceptEntity): ConceptEntity

    /** `INSERT ... ON CONFLICT(id) DO UPDATE`；新行分配 mid。 */
    fun saveModule(entity: ModuleEntity): ModuleEntity

    fun moduleConcepts(): List<ModuleConceptLink>

    /** `INSERT OR IGNORE`；插入成功返回 true。 */
    fun insertModuleConcept(mid: Int, cid: Int, role: ConceptLinkRole): Boolean

    fun moduleDependencies(): List<ModuleDependencyLink>

    /** `INSERT OR IGNORE`；插入成功返回 true。 */
    fun insertModuleDependency(mid: Int, dependsOnMid: Int, reason: String): Boolean

    fun deleteModuleDependency(mid: Int, dependsOnMid: Int)

    fun countAttestations(subjectKind: RegistrySubjectKind, subjectId: Id): Int

    fun insertAttestation(
        subjectKind: RegistrySubjectKind,
        subjectId: Id,
        claimId: Id,
        objectSeq: Int?,
        at: Timestamp,
    ): Int

    fun attestations(): List<AttestationRecord>
}

/** Android 参考实现：内存行 + 与参考 SQL 相同的唯一键/自增/insert-or-ignore 语义。 */
class InMemoryRegistryDatabase : RegistryDatabase {

    val chain = InMemoryRegistryObjectChain()

    override val objects: RegistryObjectChain get() = chain

    private val conceptRows: MutableMap<Id, ConceptEntity> = LinkedHashMap()
    private val moduleRows: MutableMap<Id, ModuleEntity> = LinkedHashMap()
    private val conceptLinks: MutableList<ModuleConceptLink> = ArrayList()
    private val dependencyLinks: MutableList<ModuleDependencyLink> = ArrayList()
    private val attestationRows: MutableList<AttestationRecord> = ArrayList()

    private var nextCid: Int = 1
    private var nextMid: Int = 1
    private var nextAid: Int = 1

    @Synchronized
    override fun concept(id: Id): ConceptEntity? = conceptRows[id]

    @Synchronized
    override fun module(id: Id): ModuleEntity? = moduleRows[id]

    @Synchronized
    override fun concepts(): List<ConceptEntity> = ArrayList(conceptRows.values)

    @Synchronized
    override fun modules(): List<ModuleEntity> = ArrayList(moduleRows.values)

    @Synchronized
    override fun saveConcept(entity: ConceptEntity): ConceptEntity {
        val existing: ConceptEntity? = conceptRows[entity.id]
        val stored: ConceptEntity = entity.copy(cid = existing?.cid ?: nextCid++)
        conceptRows[entity.id] = stored
        return stored
    }

    @Synchronized
    override fun saveModule(entity: ModuleEntity): ModuleEntity {
        val existing: ModuleEntity? = moduleRows[entity.id]
        val stored: ModuleEntity = entity.copy(mid = existing?.mid ?: nextMid++)
        moduleRows[entity.id] = stored
        return stored
    }

    @Synchronized
    override fun moduleConcepts(): List<ModuleConceptLink> = ArrayList(conceptLinks)

    @Synchronized
    override fun insertModuleConcept(mid: Int, cid: Int, role: ConceptLinkRole): Boolean {
        if (conceptLinks.any { it.mid == mid && it.cid == cid && it.role == role }) {
            return false
        }
        conceptLinks += ModuleConceptLink(mid = mid, cid = cid, role = role)
        return true
    }

    @Synchronized
    override fun moduleDependencies(): List<ModuleDependencyLink> = ArrayList(dependencyLinks)

    @Synchronized
    override fun insertModuleDependency(mid: Int, dependsOnMid: Int, reason: String): Boolean {
        if (dependencyLinks.any { it.mid == mid && it.dependsOnMid == dependsOnMid }) {
            return false
        }
        dependencyLinks += ModuleDependencyLink(mid = mid, dependsOnMid = dependsOnMid, reason = reason)
        return true
    }

    @Synchronized
    override fun deleteModuleDependency(mid: Int, dependsOnMid: Int) {
        dependencyLinks.removeAll { it.mid == mid && it.dependsOnMid == dependsOnMid }
    }

    @Synchronized
    override fun countAttestations(subjectKind: RegistrySubjectKind, subjectId: Id): Int =
        attestationRows.count { it.subjectKind == subjectKind && it.subjectId == subjectId }

    @Synchronized
    override fun insertAttestation(
        subjectKind: RegistrySubjectKind,
        subjectId: Id,
        claimId: Id,
        objectSeq: Int?,
        at: Timestamp,
    ): Int {
        val aid: Int = nextAid++
        attestationRows += AttestationRecord(
            aid = aid,
            subjectKind = subjectKind,
            subjectId = subjectId,
            claimId = claimId,
            objectSeq = objectSeq,
            at = at,
        )
        return aid
    }

    @Synchronized
    override fun attestations(): List<AttestationRecord> = ArrayList(attestationRows)
}
