package com.eloykim.lyra.agent

import com.eloykim.lyra.amp.protocol.boundary.FrameDraft as ProtocolFrameDraft
import com.eloykim.lyra.amp.protocol.boundary.RefScope
import com.eloykim.lyra.amp.protocol.boundary.RefTable
import com.eloykim.lyra.amp.protocol.boundary.frameFrom
import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.frame.FrameBudget
import com.eloykim.lyra.amp.protocol.frame.FrameMode
import com.eloykim.lyra.amp.protocol.frame.Ignored
import com.eloykim.lyra.amp.protocol.frame.IgnoredReason
import com.eloykim.lyra.amp.protocol.frame.Slot
import com.eloykim.lyra.amp.protocol.frame.SlotRole
import com.eloykim.lyra.amp.protocol.frame.SlotState
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.ProtocolIds
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.nowTimestamp
import com.eloykim.lyra.attention.FrameDraft
import com.eloykim.lyra.attention.IgnoreReason as AttentionIgnoreReason
import com.eloykim.lyra.attention.Role as AttentionRole

/**
 * Binds Lyra's runtime focus selection to the canonical AMP Frame.
 *
 * `core:attention` deliberately chooses a compact draft for the agent loop;
 * this adapter is the only place where that draft becomes a protocol object.
 * The frame boundary computes the digest and rejects dangling refs, so the
 * resulting object can be written to the append-only ledger and verified by
 * the Node conformance harness.
 */
fun FrameDraft.toProtocolFrame(
    agentId: String = "a.lyra",
    ts: Timestamp = nowTimestamp(),
): Frame {
    val goalText = goal.ifBlank { "当前任务" }
    val goalRef = Ref(RefKind.GOAL, ProtocolIds.slug(goalText, "g"), goalText.take(80))
    val refs = RefTable(RefScope.RUN, agentId)
        .add(RefKind.GOAL, goalRef.id, null)
        .add(RefKind.TOOL, "automation.observe", null)

    val protocolSlots = slots.map { slot ->
        val target = when (slot.role) {
            AttentionRole.GOAL -> goalRef
            AttentionRole.TOOL -> Ref(RefKind.TOOL, "automation.observe", "读屏")
            AttentionRole.OCR, AttentionRole.ELEMENT -> Ref(
                RefKind.ENTITY,
                ProtocolIds.slug("ui.${slot.id}", "e"),
                slot.label.take(40),
            )
        }
        Slot(
            target = target,
            role = when (slot.role) {
                AttentionRole.GOAL -> SlotRole.GOAL
                AttentionRole.TOOL -> SlotRole.TOOL
                AttentionRole.OCR, AttentionRole.ELEMENT -> SlotRole.EVIDENCE
            },
            tier = Tier.T1,
            salience = slot.salience.toDouble(),
            voi = slot.voi.toDouble(),
            cost = Budget(tokens = slot.costTokens),
            why = slot.why,
            state = SlotState.ACTIVE,
        )
    }

    val protocolIgnored = ignored.map { entry ->
        Ignored(
            target = Ref(
                RefKind.ENTITY,
                ProtocolIds.slug("ui.${entry.id}", "e"),
                entry.label.take(40),
            ),
            reason = IgnoredReason.fromWire(entry.reason.name.lowercase()),
            revisitIf = entry.revisitIf,
        )
    }

    val draft = ProtocolFrameDraft(
        id = ProtocolIds.slug("${cycle}.${slots.size}.${spentTokens}", "f"),
        agent = agentId,
        cycle = cycle,
        ts = ts,
        mode = FrameMode.fromWire(mode),
        goal = goalRef,
        slots = protocolSlots,
        ignored = protocolIgnored,
        budget = FrameBudget(
            allocated = Budget(tokens = allocatedTokens),
            spent = Budget(tokens = spentTokens),
        ),
        note = "lyra.attention selector",
    )
    return frameFrom(draft, refs).frame
}

/** Maps the attention-local ignore enum onto the protocol enum. */
fun AttentionIgnoreReason.toProtocolReason(): IgnoredReason =
    IgnoredReason.fromWire(name.lowercase())
