package com.eloykim.lyra.amp.protocol.policy

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.ClaimStatus
import com.eloykim.lyra.amp.protocol.warrant.canAuthorizeEffect
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * AMP v0.1 动作策略：W-1 与 A-5 的执行点（`src/policy/policy.ts` 的忠实移植）。
 *
 * 架构 §6.1 把最低证据层级绑定到动作的可逆程度；§3.8 要求驱动动作的主张必须是
 * `held`（W-1），且由宿主而不是模型决定什么可以继续（A-5）。本文件把这两条规则
 * 变成同步闸门，并签发解锁高风险效果类别的宿主审批。
 *
 * 安全模型：`approve` / `revoke` 是宿主 API，绝不能暴露给被治理的模型。审批是
 * 引擎本地状态里的 bearer capability；存储记录才是权威，修改返回副本无法延长或
 * 放宽审批。跨进程传递审批需要签名信封，不在本 P0 模块内。
 *
 * 与 TypeScript 的差异只有一个，且是收紧而非放宽：参考实现对未知运行时
 * `effect` 返回 `deny("invalid_effect", "T4")`；Kotlin 用枚举在解析期拒绝未知
 * 效果（[EffectClass.fromWire] 抛错），因此 [PolicyEngine.decide] 永远收到合法
 * 效果，fail-closed 边界前移到 wire 解析。
 */

/** 动作分类。风险与所需 warrant 沿列表向下递增。 */
enum class EffectClass(val wire: String) {
    READ("read"),
    WRITE_REVERSIBLE("write-reversible"),
    WRITE_IRREVERSIBLE("write-irreversible"),
    SPEND("spend"),
    SEND("send"),
    DELETE("delete"),
    DEPLOY("deploy"),
    PERMISSION("permission"),
    PUBLISH("publish"),
    ;

    companion object {
        fun fromWire(value: String): EffectClass =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 effect class: $value")

        fun fromWireOrNull(value: String): EffectClass? = entries.firstOrNull { it.wire == value }
    }
}

/** 全部效果类别，按危险程度从低到高。 */
val EffectClasses: List<EffectClass> = EffectClass.entries.toList()

/**
 * 默认需要宿主审批的效果。
 *
 * 可逆写入被有意排除：对可回滚的变更，一条 held 的 T1 主张就足够。任何可能泄露
 * 数据、花钱、销毁状态、改变权限或作公开声明的事情，都需要单独的人/宿主决定。
 */
val DefaultApprovalEffects: Set<EffectClass> = linkedSetOf(
    EffectClass.WRITE_IRREVERSIBLE,
    EffectClass.SPEND,
    EffectClass.SEND,
    EffectClass.DELETE,
    EffectClass.DEPLOY,
    EffectClass.PERMISSION,
    EffectClass.PUBLISH,
)

/**
 * 一个被提议的动作。请求由模型撰写，策略不是。
 *
 * 这不是 schema 对象（参考实现只把它当参数）：字段名沿用 `policy.ts` 的
 * camelCase，避免在 `toJson` 里发明第二套名字。
 */
data class ActionRequest(
    /** 可选的相关性 id，供调用方写 receipt 时使用。 */
    val id: Id? = null,
    /** 稳定的动作名，例如 `deploy.rollback` 或 `mcp.github.create_issue`。 */
    val action: String,
    /** 动作触及的对象；只用于审计，绑定使用 action + digest。 */
    val target: Ref? = null,
    val effect: EffectClass,
    /** 动作的 warrant；除非命中 read 豁免，否则必需。 */
    val claim: Claim? = null,
    /**
     * 调用方请求的下限。它可以**抬高**规范下限（例如跨任务复用已发布事实时的
     * T4），但永远不能降低它。
     */
    val minTier: Tier? = null,
    /** 审批签发时对应的工具定义摘要。 */
    val toolDigest: Digest? = null,
    /** 审批签发时对应的精确参数摘要。 */
    val argsDigest: Digest? = null,
    /** 谁请求了动作；只用于审计，不作为授权依据。 */
    val requestedBy: Ref? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to id?.let { jsonText(it) },
        "action" to jsonText(action),
        "target" to target?.toJson(),
        "effect" to jsonText(effect.wire),
        "claim" to claim?.toJson(),
        "minTier" to minTier?.let { jsonText(it.wire) },
        "toolDigest" to toolDigest?.let { jsonText(it) },
        "argsDigest" to argsDigest?.let { jsonText(it) },
        "requestedBy" to requestedBy?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): ActionRequest {
            val obj = value.asObject("action request")
            return ActionRequest(
                id = obj.string("id"),
                action = obj.requireString("action"),
                target = obj.obj("target")?.let(Ref::fromJson),
                effect = EffectClass.fromWire(obj.requireString("effect")),
                claim = obj.obj("claim")?.let(Claim::fromJson),
                minTier = obj.string("minTier")?.let(Tier::fromWire),
                toolDigest = obj.string("toolDigest"),
                argsDigest = obj.string("argsDigest"),
                requestedBy = obj.obj("requestedBy")?.let(Ref::fromJson),
            )
        }
    }
}

/**
 * 宿主签发的、绑定到单一 action/effect 的审批。
 *
 * `scope` 是可审计的授权标识列表（默认就是动作名）。授权由 `action`、`effect`、
 * 可选的两个 digest、到期、吊销与一次性消费共同约束；`scope` 从不参与闸门判定，
 * 因此宿主可以记录更丰富的授权描述而不悄悄改变绑定。
 */
data class Approval(
    val id: Id,
    val action: String,
    val effect: EffectClass,
    val scope: List<String>,
    val approvedBy: Id,
    val issued: Timestamp,
    val expires: Timestamp,
    val nonce: String,
    val toolDigest: Digest? = null,
    val argsDigest: Digest? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "action" to jsonText(action),
        "effect" to jsonText(effect.wire),
        "scope" to jsonStrings(scope),
        "toolDigest" to toolDigest?.let { jsonText(it) },
        "argsDigest" to argsDigest?.let { jsonText(it) },
        "approvedBy" to jsonText(approvedBy),
        "issued" to jsonText(issued),
        "expires" to jsonText(expires),
        "nonce" to jsonText(nonce),
    )

    companion object {
        fun fromJson(value: JsonValue): Approval {
            val obj = value.asObject("approval")
            return Approval(
                id = obj.requireString("id"),
                action = obj.requireString("action"),
                effect = EffectClass.fromWire(obj.requireString("effect")),
                scope = obj.requireArr("scope").items.map { item ->
                    (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("approval.scope 必须是字符串数组")
                },
                approvedBy = obj.requireString("approvedBy"),
                issued = obj.requireString("issued"),
                expires = obj.requireString("expires"),
                nonce = obj.requireString("nonce"),
                toolDigest = obj.string("toolDigest"),
                argsDigest = obj.string("argsDigest"),
            )
        }
    }
}

/**
 * 闸门结果。`approval_required` 被刻意与 `deny` 分开：动作尚未获授权，但宿主演发
 * 审批即可解锁。调用方不得把这两种状态合并。
 */
enum class PolicyOutcome(val wire: String) {
    ALLOW("allow"),
    DENY("deny"),
    APPROVAL_REQUIRED("approval_required"),
    ;

    companion object {
        fun fromWire(value: String): PolicyOutcome =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 policy outcome: $value")
    }
}

/** 闸门的答案。拒绝是值，不是异常（A-5）。 */
data class PolicyVerdict(
    val outcome: PolicyOutcome,
    /** 机器可读原因，例如 `missing_claim` 或 `approval_expired`。 */
    val reason: String,
    /** 应用 `request.minTier` 后本次请求的有效最低层级。 */
    val minTier: Tier,
    /** 判定引用了已知审批时给出。 */
    val approvalId: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "outcome" to jsonText(outcome.wire),
        "reason" to jsonText(reason),
        "minTier" to jsonText(minTier.wire),
        "approvalId" to approvalId?.let { jsonText(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): PolicyVerdict {
            val obj = value.asObject("policy verdict")
            return PolicyVerdict(
                outcome = PolicyOutcome.fromWire(obj.requireString("outcome")),
                reason = obj.requireString("reason"),
                minTier = Tier.fromWire(obj.requireString("minTier")),
                approvalId = obj.string("approvalId"),
            )
        }
    }
}

/** 策略引擎的接线。 */
data class PolicyOptions(
    /** 注入时钟。策略代码从不直接调用系统时间。 */
    val now: () -> Timestamp,
    /** `approve()` 未收到 ttlMs 时使用的 TTL，默认五分钟。 */
    val defaultTtlMs: Long = DEFAULT_TTL_MS,
    /** 所有审批 TTL 的硬上限，默认十五分钟。 */
    val maxTtlMs: Long = DEFAULT_MAX_TTL_MS,
    /** 需要审批的效果，默认 [DefaultApprovalEffects]。 */
    val approvalEffects: Set<EffectClass> = DefaultApprovalEffects,
    /** read 是否可以在没有 claim 的情况下继续，默认 true。 */
    val allowReadWithoutClaim: Boolean = true,
    /** 可选的宿主投影：claim 是否仍然新鲜。只有 true 通过，抛错则直接拒绝。 */
    val isClaimCurrent: ((Claim) -> Boolean)? = null,
)

/** 宿主传给 [PolicyEngine.approve] 的输入。 */
data class ApproveInput(
    val action: String,
    val effect: EffectClass,
    val approvedBy: Id,
    /** 请求的生存期；会被夹到 maxTtlMs。 */
    val ttlMs: Long? = null,
    /** 可审计的授权描述，默认 `[action]`。 */
    val scope: List<String>? = null,
    val toolDigest: Digest? = null,
    val argsDigest: Digest? = null,
)

private class ApprovalRecord(
    val approval: Approval,
    var consumed: Boolean = false,
    var revoked: Boolean = false,
)

private val TIER_ORDER: List<Tier> = Tier.entries.toList()

const val DEFAULT_TTL_MS: Long = 5 * 60_000L
const val DEFAULT_MAX_TTL_MS: Long = 15 * 60_000L

private const val ID_BYTES: Int = 16
private const val NONCE_BYTES: Int = 24

private val EXPIRY_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

private fun positiveMs(value: Long, name: String): Long {
    if (value <= 0L) {
        throw IllegalArgumentException("$name must be a positive finite number")
    }
    return value
}

private fun isoMillis(epochMillis: Long): String = EXPIRY_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private fun deny(reason: String, minTier: Tier, approvalId: String? = null): PolicyVerdict =
    PolicyVerdict(outcome = PolicyOutcome.DENY, reason = reason, minTier = minTier, approvalId = approvalId)

private fun cloneApproval(approval: Approval): Approval = approval.copy(scope = approval.scope.toList())

/**
 * 判定动作能否继续，并管理解锁高风险效果类别的审批。
 *
 * Claim 下限（架构 §6.1）：read=T0、可逆写=T1、不可逆写/花费/发送=T2、
 * delete/deploy/permission=T3、publish=T3。publish 可被 `request.minTier` 抬到
 * T4 以支持跨任务复用；请求不能降低自己的下限。
 *
 * 同步实现：参考实现的单次 JS turn 原子性在这里由单线程调用方保证（或由宿主加锁）。
 */
class PolicyEngine(options: PolicyOptions) {

    private val now: () -> Timestamp = options.now
    private val defaultTtlMs: Long = positiveMs(options.defaultTtlMs, "defaultTtlMs")
    private val maxTtlMs: Long = positiveMs(options.maxTtlMs, "maxTtlMs")
    private val approvalEffects: Set<EffectClass> = LinkedHashSet(options.approvalEffects)
    private val allowReadWithoutClaim: Boolean = options.allowReadWithoutClaim
    private val isClaimCurrent: ((Claim) -> Boolean)? = options.isClaimCurrent
    private val approvals = LinkedHashMap<Id, ApprovalRecord>()
    private val random: SecureRandom = SecureRandom()

    /**
     * 某效果类别的规范最低层级（架构 §6.1）。
     *
     * 首次声明的 publish 是 T3；“对外声明为事实、跨任务复用”可能需要 T4，由调用方
     * 通过 `ActionRequest.minTier` 抬高，从不降低。
     */
    fun minTierFor(effect: EffectClass): Tier = when (effect) {
        EffectClass.READ -> Tier.T0
        EffectClass.WRITE_REVERSIBLE -> Tier.T1
        EffectClass.WRITE_IRREVERSIBLE, EffectClass.SPEND, EffectClass.SEND -> Tier.T2
        EffectClass.DELETE, EffectClass.DEPLOY, EffectClass.PERMISSION -> Tier.T3
        EffectClass.PUBLISH -> Tier.T3
    }

    /**
     * 评估一个动作请求。
     *
     * 顺序：claim 闸门 → 审批闸门。除 read（且 allowReadWithoutClaim 为真）之外都
     * 需要 claim；存在的 claim 必须 `held`、达到有效层级并通过
     * [canAuthorizeEffect]（置信度）。对需要审批的效果：缺审批返回
     * `approval_required`；提供了审批则先校验再原子消费，因此重放或第二次调用会
     * 被拒绝。校验失败不消耗审批。
     *
     * 对不需要审批的效果传入审批会被忽略且不消费；需要显式兑换时用 [consume]。
     */
    fun decide(request: ActionRequest, approval: Approval? = null): PolicyVerdict {
        val minTier = effectiveMinTier(request)
        gateClaim(request, minTier)?.let { return it }

        if (request.effect !in approvalEffects) {
            val reason = if (request.claim == null && request.effect == EffectClass.READ) {
                "read_without_claim"
            } else {
                "allowed"
            }
            return PolicyVerdict(outcome = PolicyOutcome.ALLOW, reason = reason, minTier = minTier)
        }

        if (approval == null) {
            return PolicyVerdict(
                outcome = PolicyOutcome.APPROVAL_REQUIRED,
                reason = "approval_required",
                minTier = minTier,
            )
        }
        return redeem(approval.id, request, minTier, approval)
    }

    /** 以审批 id 评估请求（参考实现 `Approval | string` 的 string 分支）。 */
    fun decide(request: ActionRequest, approvalId: String): PolicyVerdict {
        val minTier = effectiveMinTier(request)
        gateClaim(request, minTier)?.let { return it }
        if (request.effect !in approvalEffects) {
            val reason = if (request.claim == null && request.effect == EffectClass.READ) {
                "read_without_claim"
            } else {
                "allowed"
            }
            return PolicyVerdict(outcome = PolicyOutcome.ALLOW, reason = reason, minTier = minTier)
        }
        return redeem(approvalId, request, minTier, null)
    }

    /**
     * 宿主专用：签发审批。
     *
     * id/nonce 来自 [SecureRandom]（128/192 位）。TTL 默认 defaultTtlMs，并夹到
     * maxTtlMs。返回的是副本；引擎内记录保持权威。空 action/approvedBy、未知效果
     * 与非正 TTL 抛异常：非法宿主输入是编程错误，不是策略拒绝。
     */
    fun approve(input: ApproveInput): Approval {
        if (input.action.trim().isEmpty()) {
            throw IllegalArgumentException("approval action must be a non-empty string")
        }
        if (input.approvedBy.trim().isEmpty()) {
            throw IllegalArgumentException("approvedBy must be a non-empty id")
        }
        if (input.toolDigest != null && input.toolDigest.trim().isEmpty()) {
            throw IllegalArgumentException("toolDigest must be non-empty when supplied")
        }
        if (input.argsDigest != null && input.argsDigest.trim().isEmpty()) {
            throw IllegalArgumentException("argsDigest must be non-empty when supplied")
        }

        val scope = input.scope ?: listOf(input.action)
        if (scope.any { it.isEmpty() }) {
            throw IllegalArgumentException("scope must be an array of non-empty strings")
        }

        val issued = now()
        val issuedMs = runCatching { Instant.parse(issued).toEpochMilli() }.getOrNull()
            ?: throw IllegalArgumentException("now() must return a parseable timestamp")
        val ttlMs = ttlFor(input.ttlMs)
        if (issuedMs > Long.MAX_VALUE - ttlMs) {
            throw IllegalArgumentException("approval expiry is outside the representable range")
        }

        val approval = Approval(
            id = freshId(),
            action = input.action,
            effect = input.effect,
            scope = scope.toList(),
            approvedBy = input.approvedBy,
            issued = issued,
            expires = isoMillis(issuedMs + ttlMs),
            nonce = randomHex(NONCE_BYTES),
            toolDigest = input.toolDigest,
            argsDigest = input.argsDigest,
        )
        approvals[approval.id] = ApprovalRecord(approval)
        return cloneApproval(approval)
    }

    /**
     * 显式兑换一个审批。
     *
     * 与 [decide] 不同，即使该效果通常不需要审批也会发生消费：这是“现在就烧掉
     * 这个能力”的操作。claim 闸门仍然适用。校验与消费在同一个同步调用内完成。
     */
    fun consume(approvalId: Id, request: ActionRequest): PolicyVerdict {
        val minTier = effectiveMinTier(request)
        gateClaim(request, minTier)?.let { return it }
        return redeem(approvalId, request, minTier, null)
    }

    /**
     * 吊销审批。未知 id 返回 false；已知 id 幂等（第二次仍返回 true，因为审批已
     * 处于吊销状态）。
     */
    fun revoke(approvalId: Id): Boolean {
        val record = approvals[approvalId] ?: return false
        record.revoked = true
        return true
    }

    /** 已知审批的快照（副本）。内部状态从不暴露。 */
    fun list(): List<Approval> = approvals.values.map { cloneApproval(it.approval) }

    /**
     * 校验审批与请求的绑定，但不消费。
     *
     * 存储记录才是权威：传入副本上的字段不能延长到期时间或改变 action/effect。
     * 审批必须由本引擎签发；未知 id 拒绝。本方法只检查审批，不检查请求的 claim。
     */
    fun checkApproval(approval: Approval, request: ActionRequest, at: Timestamp? = null): PolicyVerdict {
        val minTier = effectiveMinTier(request)
        val record = approvals[approval.id] ?: return deny("approval_unknown", minTier, approval.id)
        if (approval.nonce != record.approval.nonce) {
            return deny("approval_invalid", minTier, approval.id)
        }
        return validateApproval(record, request, at ?: now())
    }

    private fun effectiveMinTier(request: ActionRequest): Tier {
        val floor = minTierFor(request.effect)
        val requested = request.minTier ?: return floor
        return if (TIER_ORDER.indexOf(requested) > TIER_ORDER.indexOf(floor)) requested else floor
    }

    private fun gateClaim(request: ActionRequest, minTier: Tier): PolicyVerdict? {
        val claim = request.claim
        if (claim == null) {
            if (request.effect == EffectClass.READ && allowReadWithoutClaim) {
                return null
            }
            return deny("missing_claim", minTier)
        }
        if (isClaimCurrent != null) {
            val current = try {
                isClaimCurrent.invoke(claim)
            } catch (_: Throwable) {
                return deny("claim_freshness_unavailable", minTier)
            }
            if (!current) {
                return deny("claim_requires_revalidation", minTier)
            }
        }
        if (claim.status != ClaimStatus.HELD) {
            return deny("claim_not_held", minTier)
        }
        if (TIER_ORDER.indexOf(claim.tier) < TIER_ORDER.indexOf(minTier)) {
            return deny("tier_below_minimum:${minTier.wire}", minTier)
        }
        if (!canAuthorizeEffect(claim, minTier)) {
            return deny("claim_low_confidence", minTier)
        }
        return null
    }

    private fun redeem(approvalId: Id, request: ActionRequest, minTier: Tier, supplied: Approval?): PolicyVerdict {
        val record = approvals[approvalId] ?: return deny("approval_unknown", minTier, approvalId)
        if (supplied != null && supplied.nonce != record.approval.nonce) {
            return deny("approval_invalid", minTier, approvalId)
        }
        val verdict = validateApproval(record, request, now())
        if (verdict.outcome != PolicyOutcome.ALLOW) {
            return verdict
        }
        record.consumed = true
        return PolicyVerdict(
            outcome = PolicyOutcome.ALLOW,
            reason = "approved",
            minTier = minTier,
            approvalId = approvalId,
        )
    }

    private fun validateApproval(record: ApprovalRecord, request: ActionRequest, at: Timestamp): PolicyVerdict {
        val approval = record.approval
        val minTier = effectiveMinTier(request)

        if (record.revoked) {
            return deny("approval_revoked", minTier, approval.id)
        }
        if (record.consumed) {
            return deny("approval_consumed", minTier, approval.id)
        }
        if (approval.id.isEmpty() || approval.nonce.isEmpty() || approval.approvedBy.trim().isEmpty()) {
            return deny("approval_invalid", minTier, approval.id)
        }

        val issued = runCatching { Instant.parse(approval.issued).toEpochMilli() }.getOrNull()
        val expires = runCatching { Instant.parse(approval.expires).toEpochMilli() }.getOrNull()
        val current = runCatching { Instant.parse(at).toEpochMilli() }.getOrNull()
        if (issued == null || expires == null || current == null) {
            return deny("approval_invalid", minTier, approval.id)
        }
        if (current >= expires) {
            return deny("approval_expired", minTier, approval.id)
        }

        if (approval.action != request.action) {
            return deny("approval_action_mismatch", minTier, approval.id)
        }
        if (approval.effect != request.effect) {
            return deny("approval_effect_mismatch", minTier, approval.id)
        }
        if (approval.toolDigest != null && request.toolDigest != approval.toolDigest) {
            return deny("approval_digest_mismatch", minTier, approval.id)
        }
        if (approval.argsDigest != null && request.argsDigest != approval.argsDigest) {
            return deny("approval_digest_mismatch", minTier, approval.id)
        }

        return PolicyVerdict(
            outcome = PolicyOutcome.ALLOW,
            reason = "approval_valid",
            minTier = minTier,
            approvalId = approval.id,
        )
    }

    private fun ttlFor(requested: Long?): Long {
        val value = positiveMs(requested ?: defaultTtlMs, "ttlMs")
        return minOf(value, maxTtlMs)
    }

    private fun freshId(): Id {
        var id: String
        do {
            id = "apr.${randomHex(ID_BYTES)}"
        } while (approvals.containsKey(id))
        return id
    }

    private fun randomHex(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return buildString(bytes * 2) {
            buffer.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private companion object {
        val HEX: CharArray = "0123456789abcdef".toCharArray()
    }
}
