package com.eloykim.lyra.amp.protocol

import com.eloykim.lyra.amp.protocol.boundary.FrameDraft
import com.eloykim.lyra.amp.protocol.boundary.RefScope
import com.eloykim.lyra.amp.protocol.boundary.RefTable
import com.eloykim.lyra.amp.protocol.boundary.frameFrom
import com.eloykim.lyra.amp.protocol.crypto.Ed25519
import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.frame.FrameBudget
import com.eloykim.lyra.amp.protocol.frame.FrameMode
import com.eloykim.lyra.amp.protocol.frame.Slot
import com.eloykim.lyra.amp.protocol.frame.SlotRole
import com.eloykim.lyra.amp.protocol.frame.SlotState
import com.eloykim.lyra.amp.protocol.ledger.Ledger
import com.eloykim.lyra.amp.protocol.ledger.exportLedger
import com.eloykim.lyra.amp.protocol.manifest.AgentIdentity
import com.eloykim.lyra.amp.protocol.manifest.AttentionConfig
import com.eloykim.lyra.amp.protocol.manifest.Calibration
import com.eloykim.lyra.amp.protocol.manifest.Capability
import com.eloykim.lyra.amp.protocol.manifest.CapabilityKind
import com.eloykim.lyra.amp.protocol.manifest.SelfModel
import com.eloykim.lyra.amp.protocol.manifest.UnsignedManifest
import com.eloykim.lyra.amp.protocol.manifest.signManifest
import com.eloykim.lyra.amp.protocol.receipt.Outcome
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.receipt.ReceiptOp
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Exports real protocol objects for the external Node harness.
 *
 * The Node side validates the schema, recomputes the canonical digests and
 * verifies the manifest signature with the exported SPKI key.
 */
class WireExportTest {

    @Test
    fun `导出 frame receipt manifest 与 ledger snapshot`() {
        val out = File("build/amp-wire").apply { mkdirs() }
        val frame = frame()
        out.resolve("frame.json").writeText(CanonicalJson.encode(frame.toJson()))

        val receipt = receipt()
        out.resolve("receipt.json").writeText(CanonicalJson.encode(receipt.toJson()))

        val signer = Ed25519.generate("a.lyra#1") ?: error("JVM 缺少 Ed25519")
        val manifest = signManifest(unsignedManifest(), signer)
        out.resolve("manifest.json").writeText(CanonicalJson.encode(manifest.toJson()))
        out.resolve("manifest-public-key.b64").writeText(
            Base64.getEncoder().encodeToString(signer.publicKey().encoded),
        )

        val ledger = Ledger().apply {
            appendFrame(frame)
            appendReceipt(receipt)
        }
        val snapshot = exportLedger(ledger, "a.lyra")
        out.resolve("ledger-snapshot.json").writeText(CanonicalJson.encode(snapshot.toJson()))

        assertEquals(emptyList<String>(), AmpSchemas.validateKind("frame", frame.toJson()))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("receipt", receipt.toJson()))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("manifest", manifest.toJson()))
        assertEquals(emptyList<String>(), AmpSchemas.validateKind("ledger-snapshot", snapshot.toJson()))
        assertTrue(out.resolve("manifest-public-key.b64").length() > 0)
    }

    private fun frame(): Frame {
        val goal = Ref(RefKind.GOAL, "g.task", "打开设置并找到无障碍")
        val refs = RefTable(RefScope.RUN, "a.lyra")
            .add(RefKind.GOAL, goal.id, null)
            .add(RefKind.TOOL, "automation.observe", null)
        return frameFrom(
            FrameDraft(
                id = "f.1",
                agent = "a.lyra",
                cycle = 1,
                ts = "2026-10-29T00:00:00Z",
                mode = FrameMode.EXPLOIT,
                goal = goal,
                slots = listOf(
                    Slot(
                        target = Ref(RefKind.ENTITY, "ui.8", "设置"),
                        role = SlotRole.EVIDENCE,
                        tier = Tier.T1,
                        salience = 0.82,
                        voi = 0.6,
                        cost = Budget(tokens = 120, calls = 1),
                        why = "设置入口是当前任务的第一步",
                        state = SlotState.ACTIVE,
                    ),
                ),
                budget = FrameBudget(
                    allocated = Budget(tokens = 4_000, calls = 6),
                    spent = Budget(tokens = 320, calls = 1),
                ),
            ),
            refs,
        ).frame
    }

    private fun receipt(): Receipt = Receipt(
        id = "rc.1",
        op = ReceiptOp.ACT,
        target = Ref(RefKind.ENTITY, "ui.8", "设置"),
        at = "2026-10-29T00:00:01Z",
        ms = 180,
        usage = Budget(tokens = 320, ms = 180, calls = 1),
        outcome = Outcome.SUCCESS,
        predictedVoi = 0.6,
        realizedVoi = 0.7,
    )

    private fun unsignedManifest(): UnsignedManifest = UnsignedManifest(
        agent = AgentIdentity(
            id = "a.lyra",
            name = "Lyra",
            version = "0.1.0",
            role = "on-device phone operator",
        ),
        issued = "2026-10-29T00:00:00Z",
        expires = "2026-10-30T00:00:00Z",
        capabilities = listOf(
            Capability(
                id = "automation.observe",
                kind = CapabilityKind.PERCEPTION,
                confidence = 0.95,
                tier = Tier.T1,
                label = "读取当前屏幕",
                cost = Budget(ms = 30, calls = 1),
                limits = listOf("仅当前前台窗口"),
            ),
            Capability(
                id = "automation.tap",
                kind = CapabilityKind.ACTION,
                confidence = 0.9,
                tier = Tier.T2,
                label = "点击元素",
                cost = Budget(ms = 150, calls = 1),
                limits = listOf("需要新鲜的 obsId", "资金/凭证界面被拒绝"),
            ),
        ),
        attention = AttentionConfig(
            policyDigest = CanonicalJson.digest(
                com.eloykim.lyra.amp.protocol.json.JsonValue.Str(
                    "deny:payment\ndeny:credentials\ndeny:self\ndeny:system",
                ),
            ),
            budget = Budget(tokens = 60_000, ms = 480_000, calls = 40, interrupts = 2),
        ),
        self = SelfModel(
            calibration = Calibration(ece = 0.0),
            limits = listOf("只知道当前屏幕", "未做在线校准，ece 暂记为 0"),
        ),
    )
}
