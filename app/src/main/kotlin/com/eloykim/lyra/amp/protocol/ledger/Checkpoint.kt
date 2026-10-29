package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.crypto.AmpSigner
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.Base64Url
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.AmpWire
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.nowTimestamp
import java.security.PublicKey

/**
 * 混入每个 checkpoint 签名的域分隔符。
 *
 * 它让一个 checkpoint body 上的 Ed25519 签名无法被重放到任何其他恰好含有
 * 相同字段的 AMP 对象上。
 */
const val CHECKPOINT_DOMAIN: String = "amp:ledger-checkpoint:0.1"

/**
 * 对只追加账本链尖的签名承诺（`checkpoint.ts`）。
 *
 * 签名覆盖除 `sig` 以外的全部字段。`prev` 是上一个 checkpoint 的
 * [checkpointDigest]——即它 canonical 签名 body 的 digest，而不是某条账本
 * 条目的哈希——于是 checkpoint 自身也形成一条独立于条目链的链。
 *
 * 这里不在构造器里做校验：验证器必须能在字段损坏时给出逐条理由，而不是
 * 在构造阶段就抛出。
 */
data class LedgerCheckpoint(
    val amp: String = AmpWire.VERSION,
    val kind: String = KIND,
    val tenant: String,
    val agent: String,
    /** checkpoint 链中的位置；第一条为 0，之后每次加一。 */
    val seq: Long,
    /** `tip` 处快照的条目数。 */
    val count: Long,
    /** 被见证链的链尖条目哈希。 */
    val tip: Digest,
    /** 上一个 checkpoint 签名 body 的 digest；第一条没有。 */
    val prev: Digest? = null,
    val issued: Timestamp,
    val kid: String,
    /** domain 分隔后的 canonical body 的 base64url Ed25519 签名。 */
    val sig: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "tenant" to JsonValue.Str(tenant),
        "agent" to JsonValue.Str(agent),
        "seq" to jsonNumber(seq),
        "count" to jsonNumber(count),
        "tip" to JsonValue.Str(tip),
        "prev" to prev?.let { JsonValue.Str(it) },
        "issued" to JsonValue.Str(issued),
        "kid" to JsonValue.Str(kid),
        "sig" to JsonValue.Str(sig),
    )

    companion object {

        const val KIND: String = "ledger-checkpoint"

        fun fromJson(value: JsonValue): LedgerCheckpoint {
            val obj = value.asObject("ledger-checkpoint")
            return LedgerCheckpoint(
                amp = obj.requireString("amp"),
                kind = obj.requireString("kind"),
                tenant = obj.requireString("tenant"),
                agent = obj.requireString("agent"),
                seq = obj.long("seq") ?: throw IllegalArgumentException("缺少整数字段 seq"),
                count = obj.long("count") ?: throw IllegalArgumentException("缺少整数字段 count"),
                tip = obj.requireString("tip"),
                prev = obj.string("prev"),
                issued = obj.requireString("issued"),
                kid = obj.requireString("kid"),
                sig = obj.requireString("sig"),
            )
        }
    }
}

/** [sealCheckpoint] 的输入。 */
data class CheckpointOptions(
    val tenant: String,
    /** 默认使用当前时间。 */
    val issued: Timestamp? = null,
    /** 本 checkpoint 延续的上一个 checkpoint；其 `seq` 与 digest 会被承诺。 */
    val previous: LedgerCheckpoint? = null,
)

/** fail-closed 的 checkpoint 校验结论。 */
data class CheckpointVerdict(
    val ok: Boolean,
    val reasons: List<String>,
) {
    companion object {
        fun of(reasons: List<String>): CheckpointVerdict = CheckpointVerdict(reasons.isEmpty(), reasons)
    }
}

/**
 * 精确选出被签名的字段，丢掉 `sig` 与任何运行时附加字段。
 *
 * 条件式包含让第一条 checkpoint 的 `prev` 缺席（而不是 null），从而让它的
 * canonical 形式跨实现保持稳定。
 */
private fun checkpointFields(checkpoint: LedgerCheckpoint): JsonValue.Obj = jsonObj(
    "amp" to JsonValue.Str(checkpoint.amp),
    "kind" to JsonValue.Str(checkpoint.kind),
    "tenant" to JsonValue.Str(checkpoint.tenant),
    "agent" to JsonValue.Str(checkpoint.agent),
    "seq" to jsonNumber(checkpoint.seq),
    "count" to jsonNumber(checkpoint.count),
    "tip" to JsonValue.Str(checkpoint.tip),
    "prev" to checkpoint.prev?.let { JsonValue.Str(it) },
    "issued" to JsonValue.Str(checkpoint.issued),
    "kid" to JsonValue.Str(checkpoint.kid),
)

/**
 * checkpoint body 的 canonical JSON：全部签名字段，排除 `sig`，键由
 * [CanonicalJson] 排序。这是其他实现必须逐字节复现的签名输入部分。
 *
 * 签名本身覆盖 `CHECKPOINT_DOMAIN + '\n' + checkpointBody(...)`，因此一个
 * 合法 checkpoint 签名不能被搬到另一种对象上。
 */
fun checkpointBody(checkpoint: LedgerCheckpoint): String =
    CanonicalJson.encode(checkpointFields(checkpoint))

/**
 * 签名 body（除 `sig` 外全部字段）的 digest。
 *
 * 用作 checkpoint 之间的 `prev` 链接，可安全记录或索引；它不是账本条目
 * 哈希，不能与 `snapshot.tip` 比较。
 */
fun checkpointDigest(checkpoint: LedgerCheckpoint): Digest =
    CanonicalJson.digest(checkpointFields(checkpoint))

private fun signingBytes(body: String): ByteArray =
    "$CHECKPOINT_DOMAIN\n$body".toByteArray(Charsets.UTF_8)

/**
 * 把快照的链尖签成新的 checkpoint。
 *
 * `seq` 为 `(previous?.seq ?? -1) + 1`；提供上一个 checkpoint 时，`prev` 为
 * [checkpointDigest]。签名前不校验快照——调用方应传入已校验的快照。
 * 签名是 Ed25519，覆盖 domain 分隔后的 canonical body，绝不覆盖 JS 属性
 * 顺序。
 */
fun sealCheckpoint(
    snapshot: LedgerSnapshot,
    key: AmpSigner,
    options: CheckpointOptions,
): LedgerCheckpoint {
    val previous = options.previous
    val unsigned = LedgerCheckpoint(
        amp = AmpWire.VERSION,
        kind = LedgerCheckpoint.KIND,
        tenant = options.tenant,
        agent = snapshot.agent,
        seq = (previous?.seq ?: -1L) + 1L,
        count = snapshot.count.toLong(),
        tip = snapshot.tip,
        prev = previous?.let(::checkpointDigest),
        issued = options.issued ?: nowTimestamp(),
        kid = key.kid,
        sig = "",
    )
    val signature = key.sign(signingBytes(checkpointBody(unsigned)))
    return unsigned.copy(sig = Base64Url.encode(signature))
}

private fun isEmpty(value: String): Boolean = value.isBlank()

/**
 * 不信任任何字段地校验 checkpoint（`verifyCheckpoint`）。
 *
 * 先检查信封（`amp`/`kind`）、非空 tenant/agent/kid/issued、非负整数
 * `seq`/`count`、`sha256:<64 hex>` 的 tip 与（若存在）prev 格式，再重算
 * canonical body 并验证 Ed25519 签名。若提供 `previous`，checkpoint 必须与
 * 它相连：`seq` 恰为 `previous.seq + 1`，`prev` 等于
 * `checkpointDigest(previous)`，tenant/agent 保持不变。上一个 checkpoint
 * 自身的签名由调用方负责。
 */
fun verifyCheckpoint(
    checkpoint: LedgerCheckpoint,
    publicKey: PublicKey,
    previous: LedgerCheckpoint? = null,
): CheckpointVerdict {
    val reasons = ArrayList<String>()

    if (checkpoint.amp != AmpWire.VERSION) {
        reasons += "unsupported checkpoint amp version"
    }
    if (checkpoint.kind != LedgerCheckpoint.KIND) {
        reasons += "not a ledger checkpoint"
    }
    if (isEmpty(checkpoint.tenant)) {
        reasons += "tenant is empty"
    }
    if (isEmpty(checkpoint.agent)) {
        reasons += "agent is empty"
    }
    if (isEmpty(checkpoint.kid)) {
        reasons += "kid is empty"
    }
    if (isEmpty(checkpoint.issued)) {
        reasons += "issued is empty"
    }
    if (checkpoint.seq < 0) {
        reasons += "seq is not a non-negative integer"
    }
    if (checkpoint.count < 0) {
        reasons += "count is not a non-negative integer"
    }
    if (!Sha256.isValidDigest(checkpoint.tip)) {
        reasons += "tip is not a sha256 digest"
    }
    if (checkpoint.prev != null && !Sha256.isValidDigest(checkpoint.prev)) {
        reasons += "prev is not a sha256 digest"
    }

    if (previous != null) {
        if (previous.seq < 0) {
            reasons += "previous checkpoint seq is not a non-negative integer"
        } else if (checkpoint.seq != previous.seq + 1L) {
            reasons += "seq ${checkpoint.seq} does not continue from ${previous.seq}"
        }
        if (checkpoint.agent != previous.agent) {
            reasons += "agent does not match the previous checkpoint"
        }
        if (checkpoint.tenant != previous.tenant) {
            reasons += "tenant does not match the previous checkpoint"
        }

        val prev = checkpoint.prev
        if (prev == null) {
            reasons += "missing prev link to the previous checkpoint"
        } else {
            try {
                if (prev != checkpointDigest(previous)) {
                    reasons += "prev does not match the previous checkpoint digest"
                }
            } catch (error: Exception) {
                reasons += "previous checkpoint cannot be canonicalized"
            }
        }
    }

    var body: String? = null
    try {
        body = checkpointBody(checkpoint)
    } catch (error: Exception) {
        reasons += "checkpoint body cannot be canonicalized"
    }

    if (isEmpty(checkpoint.sig)) {
        reasons += "signature is empty"
    } else if (!Base64Url.isValid(checkpoint.sig)) {
        reasons += "signature is not base64url"
    } else if (body != null) {
        try {
            val signatureBytes = Base64Url.decode(checkpoint.sig)
            if (!Ed25519.verify(publicKey, signingBytes(body), signatureBytes)) {
                reasons += "signature does not verify"
            }
        } catch (error: Exception) {
            reasons += "signature does not verify"
        }
    }

    return CheckpointVerdict.of(reasons)
}

/**
 * 检查快照是否就是 checkpoint 签名的那条链（`verifySnapshotAgainstCheckpoint`）。
 *
 * 先比较 agent/count/tip，再运行 [verifySnapshot]，这样被改写的中段条目
 * 无法藏在相符的 tip 声明背后。本函数不校验 checkpoint 签名；端到端保证
 * 需要同时调用 [verifyCheckpoint]。
 */
fun verifySnapshotAgainstCheckpoint(
    snapshot: LedgerSnapshot,
    checkpoint: LedgerCheckpoint,
): CheckpointVerdict {
    val reasons = ArrayList<String>()

    if (snapshot.agent != checkpoint.agent) {
        reasons += "snapshot agent ${snapshot.agent} does not match checkpoint agent ${checkpoint.agent}"
    }
    if (snapshot.count.toLong() != checkpoint.count) {
        reasons += "snapshot count ${snapshot.count} does not match checkpoint count ${checkpoint.count}"
    }
    if (snapshot.tip != checkpoint.tip) {
        reasons += "snapshot tip does not match checkpoint tip"
    }

    reasons += verifySnapshot(snapshot).reasons
    return CheckpointVerdict.of(reasons)
}
