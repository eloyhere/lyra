package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.bayes.BetaBelief
import com.eloykim.lyra.amp.protocol.bayes.betaMean
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.asObject
import com.eloykim.lyra.amp.protocol.types.nowTimestamp

/**
 * Multi-agent cooperation (spec section 13), a faithful port of
 * `src/governance/cooperation.ts`.
 *
 * Perspectives become independent agents. What crosses an agent boundary is a
 * structured message: a manifest summary, refs, a credential reference, a
 * receipt summary and scenario/mode labels. Private reasoning, raw context and
 * full payloads stay inside the sender; the receiver validates the credential
 * before it reads anything.
 *
 * The bus also prunes the communication graph. A candidate is scored by its
 * posterior reliability times the information value of talking to it, so a
 * flaky agent is not consulted just because it is reachable.
 *
 * 本文件与本包内的 `AttentionManifest.kt`、`Credential.kt`、`Legal.kt` 直接
 * 协作：清单校验走 `verifyAttentionManifest`，凭据校验走 `verifyCredential`，
 * 冻结与熔断走 `LegalGate`。同包意味着没有适配层，也没有第二套同义类型。
 */

/** A registered agent: perspective, manifest, ledger and reliability prior. */
data class RegisteredAgent(
    val agentId: Id,
    val perspective: PerspectiveId,
    val manifest: AttentionManifest,
    /** The agent's own ledger, usually `scope: "agent"`. */
    val budget: BudgetAccount,
    val reliability: BetaBelief,
)

/** The projection of a manifest that may cross an agent boundary. */
data class ManifestSummary(
    val id: Id,
    val version: Int,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val evidenceFloor: EvidenceFloor,
    val focus: Int,
    val digest: Digest,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "version" to jsonNumber(version),
        "scenario" to jsonText(scenario.wire),
        "modes" to modes.toJson(),
        "evidenceFloor" to jsonText(evidenceFloor.wire),
        "focus" to jsonNumber(focus),
        "digest" to jsonText(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): ManifestSummary {
            val obj = value.asObject("manifest summary")
            return ManifestSummary(
                id = obj.requireString("id"),
                version = obj.requireInt("version"),
                scenario = ScenarioId.fromWire(obj.requireString("scenario")),
                modes = ModeWeights.fromJson(obj.requireObj("modes")),
                evidenceFloor = EvidenceFloor.fromWire(obj.requireString("evidenceFloor")),
                focus = obj.requireInt("focus"),
                digest = obj.requireString("digest"),
            )
        }
    }
}

/** The five structured message kinds. */
enum class MessageKind(val wire: String) {
    MANIFEST("manifest"),
    TASK("task"),
    RECEIPT("receipt"),
    ESCALATION("escalation"),
    AUDIT_REQUEST("audit-request");

    companion object {
        fun fromWire(value: String): MessageKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("未知 cooperation message kind: $value")
    }
}

/** A receipt reduced to what may leave the sender. */
data class ReceiptSummary(
    val conclusion: String,
    val confidence: Double,
    val evidence: List<Ref>,
    val manifest: ManifestRef,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "conclusion" to jsonText(conclusion),
        "confidence" to jsonNumber(confidence),
        "evidence" to jsonArr(*evidence.map { it.toJson() }.toTypedArray()),
        "manifest" to jsonObj("id" to jsonText(manifest.id), "version" to jsonNumber(manifest.version)),
    )

    companion object {
        fun fromJson(value: JsonValue): ReceiptSummary {
            val obj = value.asObject("receipt summary")
            return ReceiptSummary(
                conclusion = obj.requireString("conclusion"),
                confidence = obj.double("confidence")
                    ?: throw IllegalArgumentException("缺少数字字段 confidence"),
                evidence = obj.requireArr("evidence").items.map { Ref.fromJson(it) },
                manifest = obj.requireObj("manifest").let {
                    ManifestRef(id = it.requireString("id"), version = it.requireInt("version"))
                },
            )
        }
    }
}

/** An escalation request carried by a message. */
data class EscalationAsk(
    val reason: String,
    val evidence: List<Ref>,
    val target: PerspectiveId,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "reason" to jsonText(reason),
        "evidence" to jsonArr(*evidence.map { it.toJson() }.toTypedArray()),
        "target" to jsonText(target.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): EscalationAsk {
            val obj = value.asObject("escalation ask")
            return EscalationAsk(
                reason = obj.requireString("reason"),
                evidence = obj.requireArr("evidence").items.map { Ref.fromJson(it) },
                target = PerspectiveId.fromWire(obj.requireString("target")),
            )
        }
    }
}

/** The caller's structured message before the bus stamps it. */
data class AgentMessageInput(
    val from: Id,
    val to: Id,
    val kind: MessageKind,
    val at: Timestamp,
    /** One or two sentences. No private reasoning, no raw context. */
    val summary: String? = null,
    val refs: List<Ref>? = null,
    val credential: SwitchCredential? = null,
    val receipt: ReceiptSummary? = null,
    val escalation: EscalationAsk? = null,
)

/** A stamped, digest-sealed message. */
data class AgentMessage(
    val id: Id,
    val from: Id,
    val to: Id,
    val kind: MessageKind,
    val at: Timestamp,
    val manifest: ManifestSummary,
    val summary: String? = null,
    val refs: List<Ref>? = null,
    val credential: SwitchCredential? = null,
    val receipt: ReceiptSummary? = null,
    val escalation: EscalationAsk? = null,
    val openQuestions: List<String>? = null,
    val digest: Digest,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to jsonText(id),
        "from" to jsonText(from),
        "to" to jsonText(to),
        "kind" to jsonText(kind.wire),
        "at" to jsonText(at),
        "summary" to summary?.let { jsonText(it) },
        "refs" to refs?.let { list -> jsonArr(*list.map { it.toJson() }.toTypedArray()) },
        "credential" to credential?.toJson(),
        "receipt" to receipt?.toJson(),
        "escalation" to escalation?.toJson(),
        "manifest" to manifest.toJson(),
        "openQuestions" to openQuestions?.let { list ->
            jsonArr(*list.map { jsonText(it) }.toTypedArray())
        },
        "digest" to jsonText(digest),
    )

    companion object {
        fun fromJson(value: JsonValue): AgentMessage {
            val obj = value.asObject("agent message")
            return AgentMessage(
                id = obj.requireString("id"),
                from = obj.requireString("from"),
                to = obj.requireString("to"),
                kind = MessageKind.fromWire(obj.requireString("kind")),
                at = obj.requireString("at"),
                manifest = ManifestSummary.fromJson(obj.requireObj("manifest")),
                summary = obj.string("summary"),
                refs = obj.arr("refs")?.items?.map { Ref.fromJson(it) },
                credential = obj.obj("credential")?.let { SwitchCredential.fromJson(it) },
                receipt = obj.obj("receipt")?.let { ReceiptSummary.fromJson(it) },
                escalation = obj.obj("escalation")?.let { EscalationAsk.fromJson(it) },
                openQuestions = obj.arr("openQuestions")?.items?.map { (it as JsonValue.Str).value },
                digest = obj.requireString("digest"),
            )
        }
    }
}

/** Why a delivery failed; the codes are stable across transports. */
enum class DeliveryCode(val wire: String) {
    DELIVERED("delivered"),
    UNKNOWN_SENDER("unknown-sender"),
    UNKNOWN_RECIPIENT("unknown-recipient"),
    SELF_SEND("self-send"),
    MANIFEST_INVALID("manifest-invalid"),
    CREDENTIAL_MISSING("credential-missing"),
    CREDENTIAL_INVALID("credential-invalid"),
    SCOPE_ESCAPE("scope-escape"),
    PAYLOAD_TOO_LARGE("payload-too-large"),
    RECEIPT_INVALID("receipt-invalid"),
    LEGAL_BLOCKED("legal-blocked"),
    TRANSPORT_ERROR("transport-error");

    companion object {
        fun fromWireOrNull(value: String): DeliveryCode? = entries.firstOrNull { it.wire == value }
    }
}

/** The delivery verdict for one message. */
data class Delivery(
    val ok: Boolean,
    val code: DeliveryCode,
    val detail: String,
    val message: AgentMessage? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "ok" to jsonBool(ok),
        "code" to jsonText(code.wire),
        "detail" to jsonText(detail),
        "message" to message?.toJson(),
    )

    companion object {

        fun delivered(detail: String, message: AgentMessage? = null): Delivery =
            Delivery(ok = true, code = DeliveryCode.DELIVERED, detail = detail, message = message)

        fun denied(code: DeliveryCode, detail: String): Delivery =
            Delivery(ok = false, code = code, detail = detail)

        /** The shape check the HTTP transport applies before trusting a body. */
        fun fromWireOrNull(value: JsonValue): Delivery? {
            if (value !is JsonValue.Obj) {
                return null
            }
            val ok = value.bool("ok") ?: return null
            val code = value.string("code")?.let { DeliveryCode.fromWireOrNull(it) } ?: return null
            val detail = value.string("detail") ?: return null
            val message = value.obj("message")?.let {
                runCatching { AgentMessage.fromJson(it) }.getOrNull()
            }
            return Delivery(ok = ok, code = code, detail = detail, message = message)
        }
    }
}

/** Routing options: per-candidate information value and a fan-out limit. */
data class RouteOptions(
    val voi: Map<Id, Double>? = null,
    val limit: Int = 3,
)

data class ScoredCandidate(
    val id: Id,
    val score: Double,
)

/** Bus options. */
data class CooperationOptions(
    val now: () -> Timestamp = { nowTimestamp() },
    val legal: LegalGate? = null,
    /** Maximum refs per message; a structured receipt replaces a payload. */
    val maxRefs: Int = DEFAULT_MAX_REFS,
    /** Maximum characters in `summary`. */
    val maxSummary: Int = DEFAULT_MAX_SUMMARY,
)

const val DEFAULT_MAX_REFS: Int = 16
const val DEFAULT_MAX_SUMMARY: Int = 280

/** Project a manifest onto the boundary-safe summary. */
fun manifestSummaryOf(manifest: AttentionManifest): ManifestSummary = ManifestSummary(
    id = manifest.id,
    version = manifest.version,
    scenario = manifest.scenario,
    modes = manifest.modes,
    evidenceFloor = manifest.evidenceFloor,
    focus = manifest.focus.size,
    digest = manifest.digest,
)

/** Digest of the message body, excluding `digest` itself. */
private fun messageDigest(message: AgentMessage): Digest =
    CanonicalJson.digest(message.toJson().without("digest"))

/** Verify a message digest after transport. */
fun verifyAgentMessage(message: AgentMessage): Boolean = messageDigest(message) == message.digest

/**
 * The cooperation bus: registration, structured messages and graph pruning.
 */
class CooperationBus(options: CooperationOptions = CooperationOptions()) {

    private val now: () -> Timestamp = options.now
    private val legal: LegalGate? = options.legal
    private val maxRefs: Int = options.maxRefs
    private val maxSummary: Int = options.maxSummary

    private val agents = LinkedHashMap<Id, RegisteredAgent>()
    private val inboxes = LinkedHashMap<Id, MutableList<AgentMessage>>()
    private val seen = LinkedHashSet<Id>()
    private val board = ArrayList<AgentMessage>()
    private var counter = 0

    fun register(agent: RegisteredAgent) {
        if (agents.containsKey(agent.agentId)) {
            throw GovernanceError(GovernanceCode.REGISTRY_INVALID, "${agent.agentId} is already registered")
        }
        agents[agent.agentId] = agent.copy(reliability = agent.reliability.copy())
        inboxes[agent.agentId] = ArrayList()
    }

    /** Replace an agent's manifest after a legitimate re-derivation. */
    fun refresh(agentId: Id, manifest: AttentionManifest) {
        val agent = agents[agentId]
            ?: throw GovernanceError(GovernanceCode.REGISTRY_INVALID, "$agentId is not registered")
        agents[agentId] = agent.copy(manifest = manifest)
    }

    fun unregister(agentId: Id): Boolean {
        inboxes.remove(agentId)
        return agents.remove(agentId) != null
    }

    fun agent(agentId: Id): RegisteredAgent? = agents[agentId]?.let {
        it.copy(reliability = it.reliability.copy())
    }

    fun agentsOf(perspective: PerspectiveId): List<RegisteredAgent> =
        agents.values.filter { it.perspective == perspective }.map { it.copy() }

    /**
     * Broadcast a manifest summary. This is the only message that needs no
     * credential: it contains no payload and no private context.
     */
    fun announce(from: Id, at: Timestamp): List<Delivery> {
        val deliveries = ArrayList<Delivery>()
        for (to in agents.keys.toList()) {
            if (to == from) {
                continue
            }
            deliveries += send(AgentMessageInput(from = from, to = to, kind = MessageKind.MANIFEST, at = at))
        }
        return deliveries
    }

    /**
     * Send one structured message. The sender's manifest and, for task or
     * escalation messages, the credential are validated before delivery.
     */
    fun send(input: AgentMessageInput): Delivery {
        val sender = agents[input.from]
            ?: return Delivery.denied(DeliveryCode.UNKNOWN_SENDER, "${input.from} is not registered")
        val recipient = agents[input.to]
            ?: return Delivery.denied(DeliveryCode.UNKNOWN_RECIPIENT, "${input.to} is not registered")
        if (input.from == input.to) {
            return Delivery.denied(DeliveryCode.SELF_SEND, "an agent cannot message itself")
        }
        if (legal != null && (legal.isBlocked() || legal.isFrozen(sender.perspective))) {
            return Delivery.denied(
                DeliveryCode.LEGAL_BLOCKED,
                "the sender is frozen or the breaker is active",
            )
        }
        val manifestVerdict = verifyAttentionManifest(sender.manifest, input.at)
        if (!manifestVerdict.ok) {
            return Delivery.denied(
                DeliveryCode.MANIFEST_INVALID,
                manifestVerdict.detail ?: "sender manifest is not valid",
            )
        }
        if (input.summary != null && input.summary.length > maxSummary) {
            return Delivery.denied(
                DeliveryCode.PAYLOAD_TOO_LARGE,
                "summary exceeds $maxSummary characters",
            )
        }
        val refs = input.refs ?: emptyList()
        if (refs.size > maxRefs) {
            return Delivery.denied(DeliveryCode.PAYLOAD_TOO_LARGE, "more than $maxRefs refs")
        }
        if (input.kind == MessageKind.TASK || input.kind == MessageKind.ESCALATION) {
            val credential = input.credential
                ?: return Delivery.denied(
                    DeliveryCode.CREDENTIAL_MISSING,
                    "${input.kind.wire} messages need a switch credential",
                )
            val checked = verifyCredential(
                credential,
                VerifyCredentialRequest(
                    at = input.at,
                    to = recipient.perspective,
                    manifest = ManifestRef(recipient.manifest.id, recipient.manifest.version),
                    readRefs = refs,
                ),
            )
            if (!checked.valid) {
                return Delivery.denied(
                    if (checked.code == GovernanceCode.CREDENTIAL_SCOPE.wire) {
                        DeliveryCode.SCOPE_ESCAPE
                    } else {
                        DeliveryCode.CREDENTIAL_INVALID
                    },
                    "${checked.code}: ${checked.detail}",
                )
            }
        }
        if (input.kind == MessageKind.RECEIPT) {
            val receipt = input.receipt
            if (receipt == null ||
                receipt.conclusion.trim().isEmpty() ||
                receipt.confidence < 0.0 ||
                receipt.confidence > 1.0
            ) {
                return Delivery.denied(
                    DeliveryCode.RECEIPT_INVALID,
                    "a receipt needs a conclusion and confidence in [0, 1]",
                )
            }
            if (receipt.manifest.id != sender.manifest.id ||
                receipt.manifest.version != sender.manifest.version
            ) {
                return Delivery.denied(
                    DeliveryCode.RECEIPT_INVALID,
                    "the receipt cites a different manifest version",
                )
            }
        }
        if (input.kind == MessageKind.AUDIT_REQUEST && recipient.perspective != PerspectiveId.SUPERVISORY) {
            return Delivery.denied(
                DeliveryCode.CREDENTIAL_INVALID,
                "audit requests go to the supervisory perspective",
            )
        }
        counter += 1
        val body = AgentMessage(
            id = "msg.$counter",
            from = input.from,
            to = input.to,
            kind = input.kind,
            at = input.at,
            manifest = manifestSummaryOf(sender.manifest),
            summary = input.summary,
            refs = input.refs?.map { it.copy() },
            credential = input.credential,
            receipt = input.receipt?.let { receipt ->
                receipt.copy(evidence = receipt.evidence.map { it.copy() })
            },
            escalation = input.escalation?.let { escalation ->
                escalation.copy(evidence = escalation.evidence.map { it.copy() })
            },
            digest = "",
        )
        val message = body.copy(digest = messageDigest(body))
        inboxes.getOrPut(input.to) { ArrayList() } += message
        seen += message.id
        board += message
        return Delivery.delivered("${message.id} delivered", message)
    }

    /**
     * Admit a message that arrived over a transport. The receiver re-verifies
     * the digest, the sender's current manifest, the credential and the receipt
     * version before the message enters the inbox.
     */
    fun receive(message: AgentMessage): Delivery {
        val sender = agents[message.from]
            ?: return Delivery.denied(DeliveryCode.UNKNOWN_SENDER, "${message.from} is not registered")
        val recipient = agents[message.to]
            ?: return Delivery.denied(DeliveryCode.UNKNOWN_RECIPIENT, "${message.to} is not registered")
        if (message.from == message.to) {
            return Delivery.denied(DeliveryCode.SELF_SEND, "an agent cannot message itself")
        }
        if (!verifyAgentMessage(message)) {
            return Delivery.denied(DeliveryCode.MANIFEST_INVALID, "message digest does not match")
        }
        if (message.manifest.id != sender.manifest.id ||
            message.manifest.version != sender.manifest.version
        ) {
            return Delivery.denied(
                DeliveryCode.MANIFEST_INVALID,
                "the message cites a stale manifest version",
            )
        }
        if (legal != null && (legal.isBlocked() || legal.isFrozen(sender.perspective))) {
            return Delivery.denied(
                DeliveryCode.LEGAL_BLOCKED,
                "the sender is frozen or the breaker is active",
            )
        }
        val manifestVerdict = verifyAttentionManifest(sender.manifest, message.at)
        if (!manifestVerdict.ok) {
            return Delivery.denied(
                DeliveryCode.MANIFEST_INVALID,
                manifestVerdict.detail ?: "sender manifest is not valid",
            )
        }
        if (message.summary != null && message.summary.length > maxSummary) {
            return Delivery.denied(
                DeliveryCode.PAYLOAD_TOO_LARGE,
                "summary exceeds $maxSummary characters",
            )
        }
        val refs = message.refs ?: emptyList()
        if (refs.size > maxRefs) {
            return Delivery.denied(DeliveryCode.PAYLOAD_TOO_LARGE, "more than $maxRefs refs")
        }
        if (message.kind == MessageKind.TASK || message.kind == MessageKind.ESCALATION) {
            val credential = message.credential
                ?: return Delivery.denied(
                    DeliveryCode.CREDENTIAL_MISSING,
                    "${message.kind.wire} messages need a switch credential",
                )
            val checked = verifyCredential(
                credential,
                VerifyCredentialRequest(
                    at = message.at,
                    to = recipient.perspective,
                    manifest = ManifestRef(recipient.manifest.id, recipient.manifest.version),
                    readRefs = refs,
                ),
            )
            if (!checked.valid) {
                return Delivery.denied(
                    if (checked.code == GovernanceCode.CREDENTIAL_SCOPE.wire) {
                        DeliveryCode.SCOPE_ESCAPE
                    } else {
                        DeliveryCode.CREDENTIAL_INVALID
                    },
                    "${checked.code}: ${checked.detail}",
                )
            }
        }
        if (message.kind == MessageKind.RECEIPT) {
            val receipt = message.receipt
            if (receipt == null ||
                receipt.conclusion.trim().isEmpty() ||
                receipt.confidence < 0.0 ||
                receipt.confidence > 1.0
            ) {
                return Delivery.denied(
                    DeliveryCode.RECEIPT_INVALID,
                    "a receipt needs a conclusion and confidence in [0, 1]",
                )
            }
            if (receipt.manifest.id != sender.manifest.id ||
                receipt.manifest.version != sender.manifest.version
            ) {
                return Delivery.denied(
                    DeliveryCode.RECEIPT_INVALID,
                    "the receipt cites a different manifest version",
                )
            }
        }
        if (message.kind == MessageKind.AUDIT_REQUEST && recipient.perspective != PerspectiveId.SUPERVISORY) {
            return Delivery.denied(
                DeliveryCode.CREDENTIAL_INVALID,
                "audit requests go to the supervisory perspective",
            )
        }
        if (seen.contains(message.id)) {
            return Delivery.delivered("${message.id} duplicate ignored", message)
        }
        seen += message.id
        inboxes.getOrPut(message.to) { ArrayList() } += message
        board += message
        return Delivery.delivered("${message.id} received", message)
    }

    fun inbox(agentId: Id, at: Timestamp): List<Delivery> {
        val agent = agents[agentId]
            ?: throw GovernanceError(GovernanceCode.REGISTRY_INVALID, "$agentId is not registered")
        val messages = inboxes[agentId] ?: emptyList()
        return messages.map { message ->
            if (!verifyAgentMessage(message)) {
                return@map Delivery.denied(DeliveryCode.MANIFEST_INVALID, "message digest does not match")
            }
            val credential = message.credential
            if (credential != null) {
                val checked = verifyCredential(
                    credential,
                    VerifyCredentialRequest(
                        at = at,
                        to = agent.perspective,
                        manifest = ManifestRef(agent.manifest.id, agent.manifest.version),
                    ),
                )
                if (!checked.valid) {
                    return@map Delivery.denied(
                        DeliveryCode.CREDENTIAL_INVALID,
                        "${checked.code}: ${checked.detail}",
                    )
                }
            }
            Delivery.delivered(message.id, message)
        }
    }

    /**
     * Prune the communication graph: reliability x information value.
     */
    fun route(from: Id, candidates: List<Id>, options: RouteOptions = RouteOptions()): List<Id> {
        val limit = options.limit.coerceAtLeast(0)
        val scored = ArrayList<ScoredCandidate>()
        for (id in candidates) {
            if (id == from) {
                continue
            }
            val agent = agents[id] ?: continue
            val value = (options.voi?.get(id) ?: 0.0).coerceAtLeast(0.0)
            scored += ScoredCandidate(id = id, score = betaMean(agent.reliability) * (1.0 + value))
        }
        scored.sortWith(
            compareByDescending<ScoredCandidate> { it.score }.thenBy { it.id },
        )
        return scored.take(limit).map { it.id }
    }

    /**
     * Update a recipient's reliability from an observed outcome.
     */
    fun observe(agentId: Id, success: Boolean, weight: Double = 1.0): BetaBelief {
        val agent = agents[agentId]
            ?: throw GovernanceError(GovernanceCode.REGISTRY_INVALID, "$agentId is not registered")
        val delta = weight.coerceAtLeast(0.0)
        val next = agent.reliability.copy(
            alpha = agent.reliability.alpha + if (success) delta else 0.0,
            beta = agent.reliability.beta + if (success) 0.0 else delta,
        )
        agents[agentId] = agent.copy(reliability = next)
        return next.copy()
    }

    /**
     * The shared, structured board. It contains message summaries, not private
     * reasoning, so supervisory audit can read it without inheriting execution
     * context.
     */
    fun boardEntries(): List<AgentMessage> = board.toList()

    /**
     * An independent audit request: evidence refs only, no receipt body and no
     * private context. The supervisory agent can trace by reference.
     */
    fun auditRequest(from: Id, evidence: List<Ref>, at: Timestamp): Delivery {
        val supervisory = agents.values.firstOrNull { it.perspective == PerspectiveId.SUPERVISORY }
            ?: return Delivery.denied(DeliveryCode.UNKNOWN_RECIPIENT, "no supervisory agent is registered")
        return send(
            AgentMessageInput(
                from = from,
                to = supervisory.agentId,
                kind = MessageKind.AUDIT_REQUEST,
                at = at,
                refs = evidence.toList(),
                summary = "independent evidence audit",
            ),
        )
    }

    /**
     * Convenience: hand a task to a target with a credential.
     */
    fun handoff(
        from: Id,
        to: Id,
        credential: SwitchCredential,
        refs: List<Ref>,
        at: Timestamp,
        summary: String? = null,
    ): Delivery = send(
        AgentMessageInput(
            from = from,
            to = to,
            kind = MessageKind.TASK,
            at = at,
            credential = credential,
            refs = refs.toList(),
            summary = summary,
        ),
    )

    fun nowValue(): Timestamp = now()
}
