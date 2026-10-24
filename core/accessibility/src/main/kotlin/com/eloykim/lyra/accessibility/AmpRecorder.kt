package com.eloykim.lyra.accessibility

import com.eloykim.lyra.amp.AmpIds
import com.eloykim.lyra.amp.AmpLedger
import com.eloykim.lyra.amp.Budget
import com.eloykim.lyra.amp.Frame
import com.eloykim.lyra.amp.FrameBuilder
import com.eloykim.lyra.amp.Mode
import com.eloykim.lyra.amp.ObservedElement
import com.eloykim.lyra.amp.Observation
import com.eloykim.lyra.amp.Outcome
import com.eloykim.lyra.amp.Receipt
import com.eloykim.lyra.amp.ReceiptOp
import com.eloykim.lyra.amp.Ref
import com.eloykim.lyra.amp.Usage
import com.eloykim.lyra.model.ScreenSnapshot

/**
 * 把「观察 / 动作」写进 AMP 账本。
 *
 * v1 先挂在服务层：每次 observe 生成一帧，每次动作生成一张回执。
 * Agent 循环落地后，这个职责上移到 core:agent —— 服务只负责执行。
 *
 * 注意：这不影响执行，只是记录；但违反不变量的 Frame 会直接抛异常（开发期暴露问题）。
 */
object AmpRecorder {

    private val builder = FrameBuilder()
    private val lock = Any()

    private var cycle = 0
    private var lastFrame: Frame? = null
    private var spentTokens = 0
    private var spentCalls = 0

    private val allocated = Budget(
        tokens = 60_000,
        ms = 480_000,
        calls = 40,
        interrupts = 2,
    )

    fun onObserved(snapshot: ScreenSnapshot): Frame? = runCatching {
        synchronized(lock) {
            val sent = ArrayList<ObservedElement>()
            val dropped = ArrayList<ObservedElement>()
            for (node in snapshot.nodes) {
                if (!node.visible) continue
                val interesting = node.hasLabel || node.clickable || node.editable || node.scrollable
                val element = ObservedElement(node.id, node.shortLabel, node.clickable)
                if (interesting) sent += element else dropped += element
            }

            val tokens = sent.sumOf { it.label.length } / 2 + sent.size * 8
            spentTokens += tokens
            spentCalls += 1

            cycle += 1
            val frame = builder.build(
                cycle = cycle,
                mode = Mode.exploit,
                observation = Observation(
                    obsId = snapshot.id,
                    packageName = snapshot.packageName,
                    totalNodes = snapshot.nodes.size,
                    interactiveNodes = snapshot.interactiveCount,
                    sent = sent.take(12),
                    dropped = dropped,
                    tokensEstimate = tokens,
                ),
                allocated = allocated,
                spent = Usage(tokens = spentTokens, calls = spentCalls),
                parent = lastFrame?.let { Ref("frame", it.id) },
            )
            lastFrame = AmpLedger.append(frame)
            AmpLedger.append(
                receipt(
                    op = ReceiptOp.attend,
                    label = snapshot.packageName,
                    startedAtMs = System.currentTimeMillis(),
                    outcome = Outcome.success,
                    note = "obs=${snapshot.id}",
                ),
            )
            frame
        }
    }.getOrNull()

    fun onAction(
        op: ReceiptOp,
        label: String?,
        startedAtMs: Long,
        outcome: Outcome,
        note: String? = null,
    ) {
        runCatching {
            synchronized(lock) {
                spentCalls += 1
                AmpLedger.append(receipt(op, label, startedAtMs, outcome, note))
            }
        }
    }

    private fun receipt(
        op: ReceiptOp,
        label: String?,
        startedAtMs: Long,
        outcome: Outcome,
        note: String?,
    ): Receipt {
        val now = System.currentTimeMillis()
        return Receipt(
            id = AmpIds.slug("rc.$now-${spentCalls}", "rc"),
            op = op,
            usage = Usage(calls = 1, ms = (now - startedAtMs).toInt().coerceAtLeast(0)),
            outcome = outcome,
            target = label?.let {
                Ref("entity", AmpIds.slug(it, "ui"), it.take(40))
            },
            note = note,
        )
    }
}
