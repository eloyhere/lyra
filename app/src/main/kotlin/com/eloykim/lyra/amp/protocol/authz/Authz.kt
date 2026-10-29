package com.eloykim.lyra.amp.protocol.authz

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.isTimestamp

/**
 * 基于能力的授权（`src/authz/authz.ts` 的忠实移植）。
 *
 * 逻辑进程没有 MMU，因此隔离是一个策略决定：默认情况下主体只能触碰自己的资源，
 * 任何跨主体访问都需要一条活的 capability 行。能力是可衰减的（权利与到期只能
 * 收缩）、递归的（吊销父能力会吊销它派生的授权）且可审计的（每次 grant/revoke
 * 都向对象链追加一个事件）。
 *
 * ## 绑定缝
 *
 * 参考实现用 `node:sqlite` 的 `handles` 表与 `objects` 表。Kotlin 端口不能把
 * SQLite 拖进协议模块（Android 29 上也没有 `node:sqlite`），所以这里定义窄接口
 * [CapabilityDatabase]：它只暴露参考实现真正用到的查询原语（按主体/资源、按
 * `subject_pid` 继承、按 hid、计数、递归吊销）与对象链追加 [CapabilityDatabase.appendEvent]。
 * 集成时用 SQLite/对象存储实现这个接口即可；[InMemoryCapabilityDatabase] 是默认
 * 实现，语义与参考 SQL 一一对应（`rowsFor`、`live`、count+1 的 hid、递归
 * `UPDATE ... revoked_at`）。
 */

/** 主体类型。 */
enum class SubjectKind(val wire: String) {
    HOST("host"),
    PROCESS("process"),
    THREAD("thread"),
    ;

    companion object {
        fun fromWire(value: String): SubjectKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 subject.kind: $value")
    }
}

/** 授权的主体。`pid`/`tid` 用于线程继承与所有权判定。 */
data class Subject(
    val kind: SubjectKind,
    val id: Id,
    val pid: Int? = null,
    val tid: Int? = null,
) {

    init {
        ProtocolIds.require(id, "subject.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to jsonText(kind.wire),
        "id" to jsonText(id),
        "pid" to pid?.let { jsonNumber(it) },
        "tid" to tid?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Subject {
            val obj = value.asObject("subject")
            return Subject(
                kind = SubjectKind.fromWire(obj.requireString("kind")),
                id = obj.requireString("id"),
                pid = obj.int("pid"),
                tid = obj.int("tid"),
            )
        }
    }
}

/** 权限动作。`Action` 在参考实现里是它的废弃别名。 */
enum class PermissionAction(val wire: String) {
    READ("read"),
    WRITE("write"),
    APPEND("append"),
    GRANT("grant"),
    REVOKE("revoke"),
    SPAWN("spawn"),
    KILL("kill"),
    SEND("send"),
    RECEIVE("receive"),
    FORK("fork"),
    JOIN("join"),
    ;

    companion object {
        fun fromWire(value: String): PermissionAction =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 permission action: $value")
    }
}

/** 参考实现导出的 `AllActions`。 */
val AllActions: List<PermissionAction> = PermissionAction.entries.toList()

/** 资源类型。 */
enum class ResourceKind(val wire: String) {
    OBJECT("object"),
    PROCESS("process"),
    THREAD("thread"),
    MAILBOX("mailbox"),
    BUDGET("budget"),
    ;

    companion object {
        fun fromWire(value: String): ResourceKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 resource.kind: $value")
    }
}

/** 一个被授权的资源；内核/全局对象省略 `ownerPid`。 */
data class Resource(
    val kind: ResourceKind,
    val id: Id,
    /** 拥有该资源的进程；内核/全局对象省略。 */
    val ownerPid: Int? = null,
) {

    init {
        ProtocolIds.require(id, "resource.id")
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to jsonText(kind.wire),
        "id" to jsonText(id),
        "ownerPid" to ownerPid?.let { jsonNumber(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): Resource {
            val obj = value.asObject("resource")
            return Resource(
                kind = ResourceKind.fromWire(obj.requireString("kind")),
                id = obj.requireString("id"),
                ownerPid = obj.int("ownerPid"),
            )
        }
    }
}

/** capability 的作用域。 */
enum class CapabilityScope(val wire: String) {
    PROCESS("process"),
    THREAD("thread"),
    SHARED("shared"),
    ;

    companion object {
        fun fromWire(value: String): CapabilityScope =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 capability scope: $value")
    }
}

/**
 * 一条能力记录（`handles` 表的一行）。
 *
 * `hid` 是单调句柄；`id` 是 wire 名字（`h.<hid>`）。
 */
data class CapabilityRecord(
    val hid: Long,
    val id: Id,
    val subject: Subject,
    val resource: Resource,
    val rights: List<PermissionAction>,
    val scope: CapabilityScope,
    val parentHid: Long? = null,
    val expiresAt: Timestamp? = null,
    val revokedAt: Timestamp? = null,
    val grantedBy: Id? = null,
    val createdAt: Timestamp,
) {

    init {
        require(hid >= 1L) { "capability.hid 必须 >= 1" }
        ProtocolIds.require(id, "capability.id")
        require(rights.isNotEmpty()) { "capability.rights 不能为空" }
        expiresAt?.let { require(isTimestamp(it)) { "capability.expires_at 不是合法时间" } }
        revokedAt?.let { require(isTimestamp(it)) { "capability.revoked_at 不是合法时间" } }
        require(isTimestamp(createdAt)) { "capability.created_at 不是合法时间" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "hid" to jsonNumber(hid),
        "id" to jsonText(id),
        "subject" to subject.toJson(),
        "resource" to resource.toJson(),
        "rights" to JsonValue.Arr(rights.map { jsonText(it.wire) }),
        "scope" to jsonText(scope.wire),
        "parentHid" to parentHid?.let { jsonNumber(it) },
        "expiresAt" to expiresAt?.let { jsonText(it) },
        "revokedAt" to revokedAt?.let { jsonText(it) },
        "grantedBy" to grantedBy?.let { jsonText(it) },
        "createdAt" to jsonText(createdAt),
    )

    companion object {
        fun fromJson(value: JsonValue): CapabilityRecord {
            val obj = value.asObject("capability")
            return CapabilityRecord(
                hid = obj.long("hid") ?: throw IllegalArgumentException("缺少整数字段 hid"),
                id = obj.requireString("id"),
                subject = Subject.fromJson(obj.requireObj("subject")),
                resource = Resource.fromJson(obj.requireObj("resource")),
                rights = obj.requireArr("rights").items.map {
                    PermissionAction.fromWire(
                        (it as? JsonValue.Str)?.value
                            ?: throw IllegalArgumentException("capability.rights 必须是字符串数组"),
                    )
                },
                scope = CapabilityScope.fromWire(obj.requireString("scope")),
                parentHid = obj.long("parentHid"),
                expiresAt = obj.string("expiresAt"),
                revokedAt = obj.string("revokedAt"),
                grantedBy = obj.string("grantedBy"),
                createdAt = obj.requireString("createdAt"),
            )
        }
    }
}

/** 一次 grant 的输入。 */
data class GrantInput(
    val grantor: Subject,
    val grantee: Subject,
    val resource: Resource,
    val rights: List<PermissionAction>,
    val scope: CapabilityScope? = null,
    val expiresAt: Timestamp? = null,
)

/** 授权错误；与参考实现一样携带稳定的 code 与全部问题。 */
class AuthzError(
    val code: String,
    val problems: List<String>,
) : IllegalArgumentException("$code: ${problems.joinToString("; ")}")

/** 稳定的授权错误码。 */
object AuthzCodes {
    const val PERMISSION_DENIED: String = "permission_denied"
    const val INVALID_GRANT: String = "invalid_grant"
    const val RIGHTS_AMPLIFICATION: String = "rights_amplification"
    const val EXPIRY_AMPLIFICATION: String = "expiry_amplification"
    const val UNKNOWN_CAPABILITY: String = "unknown_capability"
}

/** 对象链的创世摘要，与 `database/store/database.ts` 的 `GENESIS` 一致。 */
val AUTHZ_GENESIS: Digest = "sha256:" + "0".repeat(64)

/**
 * 对象链中的一条事件记录。
 *
 * `body` 是 canonical JSON 文本；`hash = digest({prev, kind, id, body})`，与参考
 * 实现的 `putObject` 相同，因此改任何一行都会破坏后续每个链接。
 */
data class AuthzEventEntry(
    val kind: String,
    val id: Id,
    val body: String,
    val hash: Digest,
    val prev: Digest,
    val at: Timestamp,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to jsonText(kind),
        "id" to jsonText(id),
        "body" to jsonText(body),
        "hash" to jsonText(hash),
        "prev" to jsonText(prev),
        "at" to jsonText(at),
    )

    companion object {
        fun fromJson(value: JsonValue): AuthzEventEntry {
            val obj = value.asObject("authz event")
            return AuthzEventEntry(
                kind = obj.requireString("kind"),
                id = obj.requireString("id"),
                body = obj.requireString("body"),
                hash = obj.requireString("hash"),
                prev = obj.requireString("prev"),
                at = obj.requireString("at"),
            )
        }
    }
}

/**
 * 授权存储的窄接口（绑定缝）。
 *
 * 查询原语刻意贴着参考 SQL：
 *  - `rowsOf` ↔ `WHERE subject_kind = ? AND subject_id = ? AND resource_kind = ? AND resource_id = ? ORDER BY hid ASC`
 *  - `rowsOfProcessPid` ↔ `WHERE subject_kind = 'process' AND subject_pid = ? AND resource_kind = ? AND resource_id = ?`
 *  - `rowsOfSubject` ↔ `WHERE subject_kind = ? AND subject_id = ?`
 *  - `revokeTree` ↔ 递归 CTE `UPDATE handles SET revoked_at = ?`
 *  - `appendEvent` ↔ `putObject(db, "event", ...)`（对象链）
 *
 * 返回值必须按 `hid` 升序；`insert` 的 `hid` 由调用方按 `count() + 1` 计算，
 * 与参考实现先 `COUNT(*)` 再写入的自增身份一致。
 */
interface CapabilityDatabase {

    fun insert(record: CapabilityRecord): CapabilityRecord

    fun row(hid: Long): CapabilityRecord?

    fun rowsOf(
        subjectKind: SubjectKind,
        subjectId: Id,
        resourceKind: ResourceKind,
        resourceId: Id,
    ): List<CapabilityRecord>

    fun rowsOfProcessPid(pid: Int, resourceKind: ResourceKind, resourceId: Id): List<CapabilityRecord>

    fun rowsOfSubject(subjectKind: SubjectKind, subjectId: Id): List<CapabilityRecord>

    fun count(): Long

    /** 递归吊销；返回实际从 `revoked_at IS NULL` 变为已吊销的行数。 */
    fun revokeTree(hid: Long, at: Timestamp): Int

    /** 追加一条 `kind` 对象并返回其链接摘要。 */
    fun appendEvent(kind: String, id: Id, body: JsonValue.Obj, at: Timestamp): Digest

    fun events(): List<AuthzEventEntry>
}

/**
 * 默认的内存实现：`handles` 表 + 对象链。
 *
 * 参考实现的持久化、事务与索引由宿主替换；这里只保证授权判定所依赖的可见语义。
 */
class InMemoryCapabilityDatabase : CapabilityDatabase {

    private val rows = LinkedHashMap<Long, CapabilityRecord>()
    private val chain = ArrayList<AuthzEventEntry>()
    private var previous: Digest = AUTHZ_GENESIS

    override fun insert(record: CapabilityRecord): CapabilityRecord {
        require(!rows.containsKey(record.hid)) { "capability hid 冲突: ${record.hid}" }
        rows[record.hid] = record
        return record
    }

    override fun row(hid: Long): CapabilityRecord? = rows[hid]

    override fun rowsOf(
        subjectKind: SubjectKind,
        subjectId: Id,
        resourceKind: ResourceKind,
        resourceId: Id,
    ): List<CapabilityRecord> = rows.values.filter {
        it.subject.kind == subjectKind &&
            it.subject.id == subjectId &&
            it.resource.kind == resourceKind &&
            it.resource.id == resourceId
    }.sortedBy { it.hid }

    override fun rowsOfProcessPid(pid: Int, resourceKind: ResourceKind, resourceId: Id): List<CapabilityRecord> =
        rows.values.filter {
            it.subject.kind == SubjectKind.PROCESS &&
                it.subject.pid == pid &&
                it.resource.kind == resourceKind &&
                it.resource.id == resourceId
        }.sortedBy { it.hid }

    override fun rowsOfSubject(subjectKind: SubjectKind, subjectId: Id): List<CapabilityRecord> =
        rows.values.filter {
            it.subject.kind == subjectKind && it.subject.id == subjectId
        }.sortedBy { it.hid }

    override fun count(): Long = rows.size.toLong()

    override fun revokeTree(hid: Long, at: Timestamp): Int {
        val tree = LinkedHashSet<Long>()

        fun walk(current: Long) {
            if (!tree.add(current)) {
                return
            }
            rows.values.filter { it.parentHid == current }.forEach { walk(it.hid) }
        }
        walk(hid)

        var changed = 0
        tree.forEach { current ->
            val record = rows[current]
            if (record != null && record.revokedAt == null) {
                rows[current] = record.copy(revokedAt = at)
                changed += 1
            }
        }
        return changed
    }

    override fun appendEvent(kind: String, id: Id, body: JsonValue.Obj, at: Timestamp): Digest {
        val bodyText = CanonicalJson.encode(body)
        val hash = CanonicalJson.digest(
            jsonObj(
                "prev" to jsonText(previous),
                "kind" to jsonText(kind),
                "id" to jsonText(id),
                "body" to jsonText(bodyText),
            ),
        )
        chain += AuthzEventEntry(kind = kind, id = id, body = bodyText, hash = hash, prev = previous, at = at)
        previous = hash
        return hash
    }

    override fun events(): List<AuthzEventEntry> = chain.toList()
}

private fun live(record: CapabilityRecord, at: Timestamp): Boolean =
    record.revokedAt == null && (record.expiresAt == null || record.expiresAt > at)

private fun rowsFor(
    db: CapabilityDatabase,
    subject: Subject,
    resource: Resource,
    at: Timestamp,
): List<CapabilityRecord> {
    val subjects = ArrayList<Subject>()
    subjects += subject
    // 线程也以它所属进程的能力行事（fd 是共享的）。
    if (subject.kind == SubjectKind.THREAD && subject.pid != null) {
        subjects += Subject(kind = SubjectKind.PROCESS, id = "p.${subject.pid}", pid = subject.pid)
    }
    val rows = ArrayList<CapabilityRecord>()
    subjects.forEach { candidate ->
        rows += db.rowsOf(candidate.kind, candidate.id, resource.kind, resource.id)
            .filter { live(it, at) }
    }
    if (subject.kind == SubjectKind.THREAD && subject.pid != null) {
        val inherited = db.rowsOfProcessPid(subject.pid, resource.kind, resource.id)
            .filter { live(it, at) }
        rows += inherited
    }
    return rows
}

private fun isOwner(subject: Subject, resource: Resource): Boolean =
    resource.ownerPid != null && subject.pid == resource.ownerPid

/**
 * 主体在资源上实际持有的权利，包括所有权。
 */
fun effectiveRights(
    db: CapabilityDatabase,
    subject: Subject,
    resource: Resource,
    at: Timestamp,
): List<PermissionAction> {
    if (subject.kind == SubjectKind.HOST || isOwner(subject, resource)) {
        return AllActions.toList()
    }
    val rights = LinkedHashSet<PermissionAction>()
    rowsFor(db, subject, resource, at).forEach { row ->
        rights += row.rights
    }
    return rights.toList()
}

fun can(
    db: CapabilityDatabase,
    subject: Subject,
    action: PermissionAction,
    resource: Resource,
    at: Timestamp,
): Boolean = action in effectiveRights(db, subject, resource, at)

/**
 * 除非主体在资源上持有 `action`，否则抛错。
 */
fun requirePermission(
    db: CapabilityDatabase,
    subject: Subject,
    action: PermissionAction,
    resource: Resource,
    at: Timestamp,
) {
    if (can(db, subject, action, resource, at)) {
        return
    }
    throw AuthzError(
        AuthzCodes.PERMISSION_DENIED,
        listOf("${subject.kind.wire}:${subject.id} may not ${action.wire} ${resource.kind.wire}:${resource.id}"),
    )
}

fun listCapabilities(db: CapabilityDatabase, subject: Subject): List<CapabilityRecord> =
    db.rowsOfSubject(subject.kind, subject.id)

/**
 * 把授予者权利的一个子集授予另一个主体。
 *
 * 放大在两个维度上都被拒绝：不允许出现新权利，新到期时间也不能超过授予者自己的
 * 能力。
 */
fun grant(db: CapabilityDatabase, input: GrantInput, at: Timestamp): CapabilityRecord {
    if (input.rights.isEmpty()) {
        throw AuthzError(AuthzCodes.INVALID_GRANT, listOf("rights must not be empty"))
    }
    if (input.grantee.kind == SubjectKind.HOST) {
        throw AuthzError(AuthzCodes.INVALID_GRANT, listOf("cannot grant to host"))
    }
    requirePermission(db, input.grantor, PermissionAction.GRANT, input.resource, at)

    val available = effectiveRights(db, input.grantor, input.resource, at)
    val amplified = input.rights.filter { it !in available }
    if (amplified.isNotEmpty()) {
        throw AuthzError(
            AuthzCodes.RIGHTS_AMPLIFICATION,
            listOf("grantor does not hold ${amplified.joinToString(", ") { it.wire }}"),
        )
    }

    val source = rowsFor(db, input.grantor, input.resource, at)
        .firstOrNull { PermissionAction.GRANT in it.rights }
    val sourceExpiry = source?.expiresAt
    if (sourceExpiry != null && (input.expiresAt == null || input.expiresAt > sourceExpiry)) {
        throw AuthzError(
            AuthzCodes.EXPIRY_AMPLIFICATION,
            listOf("grant expires after the grantor's capability ($sourceExpiry)"),
        )
    }

    val hid = db.count() + 1
    val id = "h.$hid"
    val scope = input.scope ?: if (input.grantee.kind == SubjectKind.THREAD) {
        CapabilityScope.THREAD
    } else {
        CapabilityScope.PROCESS
    }
    val record = CapabilityRecord(
        hid = hid,
        id = id,
        subject = input.grantee,
        resource = input.resource,
        rights = input.rights.toList(),
        scope = scope,
        parentHid = source?.hid,
        expiresAt = input.expiresAt,
        grantedBy = input.grantor.id,
        createdAt = at,
    )
    val row = db.insert(record)
    val eventId = "ev.authz.grant.${row.hid}"
    db.appendEvent(
        kind = "event",
        id = eventId,
        body = jsonObj(
            "amp" to jsonText(AmpWire.VERSION),
            "kind" to jsonText("event"),
            "id" to jsonText(eventId),
            "at" to jsonText(at),
            "actor" to Ref(
                kind = if (input.grantor.kind == SubjectKind.HOST) RefKind.SELF else RefKind.AGENT,
                id = input.grantor.id,
            ).toJson(),
            "type" to jsonText("authz.granted"),
            "payload" to jsonObj(
                "capability" to jsonText(id),
                "grantee" to jsonText(input.grantee.id),
                "resource" to input.resource.toJson(),
                "rights" to JsonValue.Arr(input.rights.map { jsonText(it.wire) }),
            ),
        ),
        at = at,
    )
    return row
}

/**
 * 吊销一个能力以及由它派生的每一个授权。
 */
fun revoke(db: CapabilityDatabase, subject: Subject, hid: Long, at: Timestamp): Int {
    val row = db.row(hid)
        ?: throw AuthzError(AuthzCodes.UNKNOWN_CAPABILITY, listOf("hid $hid"))
    requirePermission(db, subject, PermissionAction.REVOKE, row.resource, at)
    val count = db.revokeTree(hid, at)
    if (count == 0) {
        return 0
    }
    val eventId = "ev.authz.revoke.$hid.$count"
    db.appendEvent(
        kind = "event",
        id = eventId,
        body = jsonObj(
            "amp" to jsonText(AmpWire.VERSION),
            "kind" to jsonText("event"),
            "id" to jsonText(eventId),
            "at" to jsonText(at),
            "actor" to jsonObj(
                "kind" to jsonText("self"),
                "id" to jsonText(subject.id),
            ),
            "type" to jsonText("authz.revoked"),
            "payload" to jsonObj(
                "capability" to jsonNumber(hid),
                "revoked" to jsonNumber(count),
            ),
        ),
        at = at,
    )
    return count
}
