package com.eloykim.lyra.amp.protocol.attention

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Timestamp

/** attention 测试共用的样例与构造器。 */

internal const val ATTENTION_TEST_AT: Timestamp = "2026-10-24T01:00:00Z"

internal fun attentionExampleJson(name: String): JsonValue {
    val stream = JsonParser::class.java.getResourceAsStream("/amp/examples/$name")
        ?: throw AssertionError("缺少示例资源 $name")
    return stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parse(it.readText()) }
}

internal fun attentionRef(id: String, kind: RefKind = RefKind.CONCEPT): Ref = Ref(kind, id)

internal fun attentionCandidate(
    id: String = "c.1",
    role: SlotRole = SlotRole.EVIDENCE,
    voi: Double = 0.8,
    goalFit: Double = 0.9,
    uncertainty: Double = 0.5,
    novelty: Double = 0.2,
    costTokens: Int = 100,
    risk: Double = 0.1,
    trustCap: Double = 1.0,
    why: String = "与当前问题相关",
): Candidate = Candidate(
    target = attentionRef(id),
    role = role,
    voi = voi,
    goalFit = goalFit,
    uncertainty = uncertainty,
    novelty = novelty,
    costTokens = costTokens,
    risk = risk,
    trustCap = trustCap,
    why = why,
)

internal fun attentionRequestFixture(
    id: String = "ar.1",
    target: Ref = attentionRef("u.17", RefKind.UNKNOWN),
    interruptClass: InterruptClass = InterruptClass.COMMITMENT,
    urgency: Int = 3,
    fallback: Fallback = Fallback.QUEUE,
    expectedValue: Double? = 0.9,
    expires: Timestamp? = null,
    cost: Budget = Budget(interrupts = 1, ms = 120_000),
): AttentionRequest = AttentionRequest(
    id = id,
    from = attentionRef("a.alpha", RefKind.AGENT),
    to = attentionRef("u.li", RefKind.HUMAN),
    target = target,
    interruptClass = interruptClass,
    urgency = urgency,
    whyNow = "承诺 10:00 前给出结论",
    ifIgnored = "将按 T0 假定继续",
    cost = cost,
    fallback = fallback,
    expectedValue = expectedValue,
    expires = expires,
)

internal fun cognitiveStateFixture(
    load: Double = 0.5,
    vigilance: Double = 0.8,
    arousal: Double = 0.2,
    mastery: Double = 0.6,
    accountability: Double = 0.5,
    cyclesSinceRest: Int = 2,
    capacityEff: Int = 4,
    pLapse: Double = 0.2,
    dominantRole: SlotRole? = SlotRole.EVIDENCE,
    source: CognitiveSource = CognitiveSource.DEFAULT,
    samples: Int = 5,
): CognitiveState = CognitiveState(
    load = load,
    vigilance = vigilance,
    arousal = arousal,
    mastery = mastery,
    accountability = accountability,
    cyclesSinceRest = cyclesSinceRest,
    capacityEff = capacityEff,
    pLapse = pLapse,
    dominantRole = dominantRole,
    source = source,
    samples = samples,
)

internal fun cognitiveCandidateFixture(
    id: String = "c.1",
    role: SlotRole = SlotRole.EVIDENCE,
    voi: Double = 0.8,
    goalFit: Double = 0.9,
    uncertainty: Double = 0.3,
    novelty: Double = 0.2,
    costTokens: Int = 100,
    risk: Double = 0.1,
    trustCap: Double = 1.0,
    why: String = "认知候选",
    hardFloor: Double? = null,
    chunkWeight: Int? = null,
    switchCostTokens: Int? = null,
    history: CandidateHistory? = null,
): CognitiveCandidate = CognitiveCandidate(
    target = attentionRef(id),
    role = role,
    voi = voi,
    goalFit = goalFit,
    uncertainty = uncertainty,
    novelty = novelty,
    costTokens = costTokens,
    risk = risk,
    trustCap = trustCap,
    why = why,
    hardFloor = hardFloor,
    chunkWeight = chunkWeight,
    switchCostTokens = switchCostTokens,
    history = history,
)

internal fun receiptFixture(
    id: String,
    outcome: Outcome,
    at: Timestamp = ATTENTION_TEST_AT,
): AttentionReceipt = AttentionReceipt(
    id = id,
    op = ReceiptOp.ACT,
    at = at,
    usage = Budget(tokens = 10),
    outcome = outcome,
)
