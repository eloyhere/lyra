package com.eloykim.lyra.amp.protocol.ledger

import com.eloykim.lyra.amp.protocol.frame.Frame
import com.eloykim.lyra.amp.protocol.goal.Goal
import com.eloykim.lyra.amp.protocol.goal.Plan
import com.eloykim.lyra.amp.protocol.receipt.AmpEvent
import com.eloykim.lyra.amp.protocol.receipt.Receipt
import com.eloykim.lyra.amp.protocol.warrant.Claim
import com.eloykim.lyra.amp.protocol.warrant.Hypothesis
import com.eloykim.lyra.amp.protocol.warrant.UnknownItem

/**
 * 只追加账本（X-1，`ledger.ts`）。
 *
 * 不删除、不改写；修订通过追加新行表达。参考实现的存储在内存里，宿主把
 * 同样的数组持久化为 JSONL。八个数组的顺序就是写入顺序，[exportLedger]
 * 再按时间与 kind 重新排序形成单一哈希链。
 */
class Ledger {

    val events: MutableList<AmpEvent> = ArrayList()
    val frames: MutableList<Frame> = ArrayList()
    val receipts: MutableList<Receipt> = ArrayList()
    val claims: MutableList<Claim> = ArrayList()
    val hypotheses: MutableList<Hypothesis> = ArrayList()
    val unknowns: MutableList<UnknownItem> = ArrayList()
    val goals: MutableList<Goal> = ArrayList()
    val plans: MutableList<Plan> = ArrayList()

    fun appendEvent(event: AmpEvent) {
        events += event
    }

    fun appendFrame(frame: Frame) {
        frames += frame
    }

    fun appendReceipt(receipt: Receipt) {
        receipts += receipt
    }

    fun appendClaim(claim: Claim) {
        claims += claim
    }

    fun appendHypothesis(hypothesis: Hypothesis) {
        hypotheses += hypothesis
    }

    fun appendUnknown(unknown: UnknownItem) {
        unknowns += unknown
    }

    fun appendGoal(goal: Goal) {
        goals += goal
    }

    fun appendPlan(plan: Plan) {
        plans += plan
    }

    fun findClaim(id: String): Claim? = claims.firstOrNull { it.id == id }

    fun findUnknown(id: String): UnknownItem? = unknowns.firstOrNull { it.id == id }
}
