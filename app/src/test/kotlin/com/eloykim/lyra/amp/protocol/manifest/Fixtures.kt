package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.crypto.JcaEd25519Signer
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Fixtures shared by the manifest/federation tests. Crypto tests are skipped
 * (not failed) on platforms without an Ed25519 provider.
 */
internal object Fixtures {

    const val ISSUED: String = "2026-10-24T07:00:00Z"
    const val EXPIRES: String = "2026-10-25T07:00:00Z"
    const val NOW: String = "2026-10-24T09:00:00Z"
    const val ZERO_DIGEST: String = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

    fun requireSigner(kid: String = "a.gamma#1"): JcaEd25519Signer {
        assumeTrue("当前 JVM 没有 Ed25519 提供者", Ed25519.isAvailable())
        return generateSigningKey(kid)
    }

    fun unsigned(
        issued: String = ISSUED,
        expires: String = EXPIRES,
        capabilities: List<Capability> = listOf(
            Capability(
                id = "logs.query",
                kind = CapabilityKind.TOOL,
                confidence = 0.9,
                tier = Tier.T2,
                label = "查询日志",
                cost = Budget(tokens = 400, ms = 900),
            ),
        ),
    ): UnsignedManifest = UnsignedManifest(
        agent = AgentIdentity(id = "a.gamma", name = "Gamma", version = "0.1.0", role = "analyst"),
        issued = issued,
        expires = expires,
        capabilities = capabilities,
        attention = AttentionConfig(policyDigest = ZERO_DIGEST, budget = Budget(tokens = 24000)),
        self = SelfModel(
            calibration = Calibration(ece = 0.1, buckets = emptyList()),
            limits = emptyList(),
            state = SelfState(load = 0.4, mode = SelfMode.EXPLOIT),
        ),
    )

    data class SignedFixture(
        val manifest: Manifest,
        val signer: JcaEd25519Signer,
    )

    fun signed(
        kid: String = "a.gamma#1",
        unsigned: UnsignedManifest = unsigned(),
    ): SignedFixture {
        val signer = requireSigner(kid)
        return SignedFixture(signManifest(unsigned, signer), signer)
    }

    fun example(name: String): String =
        File("src/test/resources/amp/examples/$name").readText(Charsets.UTF_8)

    fun card(
        name: String = "worker-agent",
        url: String = "https://worker.example/a2a",
        extras: Map<String, JsonValue> = emptyMap(),
    ): AgentCard = AgentCard(
        name = name,
        supportedInterfaces = listOf(AgentInterface(url = url, protocolBinding = "JSONRPC")),
        capabilities = JsonValue.Obj(linkedMapOf("streaming" to JsonValue.Bool(false))),
        skills = listOf(AgentSkill(id = "analyze", name = "分析")),
        extras = extras,
    )
}
