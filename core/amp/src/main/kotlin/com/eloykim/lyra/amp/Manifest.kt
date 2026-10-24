package com.eloykim.lyra.amp

import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Manifest 的签名器。AMP 要求 `signatures` 至少一条（M-1 自述可验）。
 *
 * 默认用 JCA 的 Ed25519（Android 13+/JDK 15+ 可用）；不可用时返回 null，
 * 调用方必须显式处理「本机无法签名」而不是伪造一条签名。
 */
interface AmpSigner {
    val kid: String
    fun sign(canonical: String): String
}

class Ed25519Signer(private val keyId: String = "lyra#1") : AmpSigner {

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    override val kid: String = keyId

    override fun sign(canonical: String): String {
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(keyPair.private)
        signature.update(canonical.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    companion object {
        fun createOrNull(keyId: String = "lyra#1"): Ed25519Signer? = runCatching {
            Ed25519Signer(keyId)
        }.getOrNull()
    }
}

object ManifestFactory {

    const val AGENT_ID = "a.lyra"

    /**
     * 生成 Lyra 的 Manifest。能力与硬约束是**声明**，真实执行处还有 PolicyGuard 兜底（M-1）。
     */
    fun build(
        signer: AmpSigner,
        issued: Instant = Instant.now(),
        ttl: Duration = Duration.ofHours(24),
        budget: Budget = Budget(tokens = 60_000, ms = 480_000, calls = 40, interrupts = 2),
    ): JVal {
        val issuedTs = Amp.ts(issued)
        val expiresTs = Amp.ts(issued.plus(ttl))

        val body = obj(
            "amp" to Amp.VERSION,
            "kind" to Amp.KIND_MANIFEST,
            "agent" to obj(
                "id" to AGENT_ID,
                "name" to "Lyra",
                "version" to "0.1.0",
                "role" to "on-device phone operator",
            ),
            "issued" to issuedTs,
            "expires" to expiresTs,
            "identity" to obj(
                "values" to arr(
                    listOf(
                        obj(
                            "id" to "v.honesty",
                            "statement" to "不确定时说明不确定，不把猜测包装成事实",
                            "weight" to 0.9,
                        ),
                        obj(
                            "id" to "v.minimal_action",
                            "statement" to "只做任务要求的事，不顺手改变用户的设置",
                            "weight" to 0.85,
                        ),
                    ),
                ),
                "constraints" to arr(
                    listOf(
                        hardConstraint("c.no_payment", "永不执行支付、转账、免密支付等资金操作"),
                        hardConstraint("c.no_credentials", "密码、验证码、指纹/面容必须由用户完成"),
                        hardConstraint("c.no_self", "不操作 Lyra 自身界面（防止自我授权）"),
                        hardConstraint("c.no_system", "不操作系统设置与权限授予界面"),
                        softConstraint("c.consent_messaging", "发送消息前必须获得用户当场授权"),
                        softConstraint("c.consent_files", "删除/移动/上传文件前必须获得用户当场授权"),
                    ),
                ),
            ),
            "capabilities" to arr(
                listOf(
                    capability("automation.observe", "读取当前屏幕", "perception", 0.95, Tier.T1, 30, listOf("仅当前前台窗口")),
                    capability("automation.tap", "点击元素", "action", 0.9, Tier.T2, 150, listOf("需要新鲜的 obsId", "资金/凭证界面被拒绝")),
                    capability("automation.set_text", "写入文本", "action", 0.85, Tier.T2, 150, listOf("仅可编辑节点")),
                    capability("automation.scroll", "滚动", "action", 0.9, Tier.T1, 100, listOf("仅当前可滚动容器")),
                    capability("automation.launch", "启动应用", "tool", 0.95, Tier.T1, 120, listOf("系统设置不可启动")),
                ),
            ),
            "attention" to obj(
                "policy_digest" to Digests.sha256(policyStatement()),
                "budget" to budget.toJson(),
            ),
            "self" to obj(
                "calibration" to obj("ece" to 0.0),
                "limits" to arr(
                    listOf(
                        "只知道当前屏幕，不记得上一个应用里的内容",
                        "无法验证自己是否点对了，只能靠下一步观察",
                        "中文界面理解优于英文，长列表容易漏项",
                        "未做在线校准，ece 暂记为 0",
                    ),
                ),
                "state" to obj("load" to 0.0, "mode" to "exploit"),
            ),
        )

        val digest = Digests.sha256(body.render())
        val signature = signer.sign(digest)

        return JVal.Obj(
            body.fields + listOf(
                "digest" to JVal.Str(digest),
                "signatures" to arr(
                    listOf(
                        obj(
                            "role" to "self",
                            "alg" to "ed25519",
                            "kid" to signer.kid,
                            "sig" to signature,
                        ),
                    ),
                ),
            ),
        )
    }

    /** 策略声明文本：它的摘要进 Manifest，改策略就会改摘要（可审计）。 */
    fun policyStatement(): String = listOf(
        "deny:payment", "deny:credentials", "deny:self", "deny:system",
        "consent:messaging", "consent:files", "consent:account",
    ).joinToString("\n")

    private fun hardConstraint(id: String, statement: String): JVal =
        obj("id" to id, "statement" to statement, "enforcement" to "hard")

    private fun softConstraint(id: String, statement: String): JVal =
        obj("id" to id, "statement" to statement, "enforcement" to "soft")

    private fun capability(
        id: String,
        label: String,
        kind: String,
        confidence: Double,
        tier: Tier,
        ms: Int,
        limits: List<String>,
    ): JVal = obj(
        "id" to id,
        "label" to label,
        "kind" to kind,
        "confidence" to confidence,
        "tier" to tier.name,
        "cost" to obj("ms" to ms, "calls" to 1),
        "limits" to arr(limits),
    )
}
