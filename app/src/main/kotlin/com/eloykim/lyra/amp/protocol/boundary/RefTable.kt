package com.eloykim.lyra.amp.protocol.boundary

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind

/**
 * `src/boundary` 构造器唯一允许抛出的错误。
 *
 * 边界层承诺：绝不返回一个部分合法的对象。构造失败时一次带上全部问题，
 * 调用方只需记录一个机器可读的 code，而不是逐条猜测哪里坏了。
 * code 保持为字符串（`invalid_frame` 等），与参考实现的 `BoundaryCode`
 * 字面量一致。
 */
class BoundaryRejection(
    val code: String,
    val problems: List<String>,
) : IllegalArgumentException("$code: ${problems.joinToString("; ")}")

/** 稳定的边界拒绝原因，调用方可以据此分支。 */
object BoundaryCodes {
    const val INVALID_FRAME: String = "invalid_frame"
    const val INVALID_TOOL_ARGS: String = "invalid_tool_args"
    const val UNRESOLVABLE_REF: String = "unresolvable_ref"
    const val DUPLICATE_REF: String = "duplicate_ref"
    const val INVALID_ID: String = "invalid_id"
}

/** 引用预期在哪个作用域内解析。 */
enum class RefScope(val wire: String) {
    RUN("run"),
    LEDGER("ledger"),
    BUNDLE("bundle");

    companion object {
        fun fromWire(value: String): RefScope =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 ref scope: $value")
    }
}

/**
 * 不在账本内的引用种类：仍然需要合法 id 与 kind，但无法要求在这里解析。
 * 人、组织或外部实体属于宿主掌握的目录，而不是本次 run。
 */
val DEFAULT_OPAQUE_KINDS: List<RefKind> = listOf(
    RefKind.HUMAN,
    RefKind.ORG,
    RefKind.ENTITY,
    RefKind.OTHER,
)

private fun stripToolPrefix(id: String): String = if (id.startsWith("mcp.")) id.substring(4) else id

private fun canonicalId(kind: RefKind, id: String): String =
    "${kind.wire}:${if (kind == RefKind.TOOL) stripToolPrefix(id) else id}"

/**
 * 构造边界的引用解析表（`refTable.ts`）。
 *
 * `Ref` 只是一个指针；在作用域内解析之前，它完全可能指向账本从未拥有过的
 * 对象——这正是 frame 会引用一个不存在的工具、概念或 claim 的原因。引用表
 * 把解析显式化，并给作用域一个名字，于是“无法解析”变成拒绝，而不是静默
 * 悬空。
 *
 * 规范化规则：
 * - `tool` 与 `capability` 共用一个命名空间；`mcp.` 前缀只是传输细节，
 *   不是身份。
 * - `self` 解析到作用域登记过的 agent id。
 */
class RefTable(
    val scope: RefScope,
    agentId: String? = null,
    opaqueKinds: List<RefKind> = DEFAULT_OPAQUE_KINDS,
) {

    /** 仅凭形状即可通过的引用种类。 */
    val opaqueKinds: Set<RefKind> = LinkedHashSet(opaqueKinds)

    private val entries: LinkedHashMap<String, Any?> = LinkedHashMap()

    init {
        if (agentId != null) {
            entries["agent:$agentId"] = jsonObj(
                "kind" to JsonValue.Str(RefKind.SELF.wire),
                "id" to JsonValue.Str(agentId),
            )
        }
    }

    /**
     * 以引用键登记一个对象。
     *
     * @throws BoundaryRejection `invalid_id` 当 id 无法被引用；
     * `duplicate_ref` 当同一个键登记两次。
     */
    fun add(kind: RefKind, id: Id, value: Any?): RefTable {
        if (!ProtocolIds.isValid(id)) {
            throw BoundaryRejection(
                BoundaryCodes.INVALID_ID,
                listOf("${kind.wire}:$id is not a valid AMP id"),
            )
        }
        val key = canonicalId(kind, id)
        if (entries.containsKey(key)) {
            throw BoundaryRejection(
                BoundaryCodes.DUPLICATE_REF,
                listOf("$key is already registered in scope ${scope.wire}"),
            )
        }
        entries[key] = value
        return this
    }

    /**
     * 解析一个引用；无法解析时返回 null。
     *
     * 与参考实现一致，`tool` 会回退到 capability 命名空间，`self` 会回退到
     * agent 登记项。注册值本身为 null 时仍视为“已登记”，由 [has] 区分。
     */
    fun resolve(ref: Ref): Any? = lookup(ref).second

    /**
     * 解析或拒绝；消息带上作用域名，宿主可以区分 run 内对象与联邦对象。
     */
    fun require(ref: Ref): Any? {
        val (found, value) = lookup(ref)
        if (!found) {
            throw BoundaryRejection(
                BoundaryCodes.UNRESOLVABLE_REF,
                listOf("${ref.kind.wire}:${ref.id} is not present in scope ${scope.wire}"),
            )
        }
        return value
    }

    fun has(ref: Ref): Boolean = lookup(ref).first

    /**
     * `frameFrom` 使用的解析裁决。
     *
     * opaque 种类只凭形状通过；其余种类必须存在于作用域内。返回问题字符串
     * 而不是抛异常，让调用方一次报告全部悬空引用。
     */
    fun check(ref: Ref): String? {
        if (opaqueKinds.contains(ref.kind)) {
            return null
        }
        if (lookup(ref).first) {
            return null
        }
        return "${ref.kind.wire}:${ref.id} is not present in scope ${scope.wire}"
    }

    val size: Int get() = entries.size

    /** `(是否登记, 值)`；避免把“登记了 null”误判为缺失。 */
    private fun lookup(ref: Ref): Pair<Boolean, Any?> {
        if (!ProtocolIds.isValid(ref.id)) {
            return false to null
        }
        lookupKey(canonicalId(ref.kind, ref.id))?.let { return it }
        if (ref.kind == RefKind.TOOL) {
            lookupKey("capability:${stripToolPrefix(ref.id)}")?.let { return it }
        }
        if (ref.kind == RefKind.SELF) {
            lookupKey("agent:${ref.id}")?.let { return it }
        }
        return false to null
    }

    private fun lookupKey(key: String): Pair<Boolean, Any?>? {
        if (!entries.containsKey(key)) {
            return null
        }
        return true to entries[key]
    }

    companion object {
        fun forScope(
            scope: RefScope,
            agentId: String? = null,
            opaqueKinds: List<RefKind> = DEFAULT_OPAQUE_KINDS,
        ): RefTable = RefTable(scope, agentId, opaqueKinds)
    }
}
