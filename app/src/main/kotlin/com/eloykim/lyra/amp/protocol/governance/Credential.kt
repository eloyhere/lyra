package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Budget
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.Id
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.Timestamp
import java.util.concurrent.atomic.AtomicInteger

/**
 * The switch credential (spec section 5), a faithful port of
 * `src/governance/credential.ts`.
 *
 * A credential authorises a target perspective to look at a named scope for a short
 * time. It carries references, not content. It is issued by the general manager and
 * countersigned by legal and finance; all three sign-offs are required before it is
 * valid.
 *
 * A credential never replaces a manifest: the credential says what may be seen, the
 * manifest says what to look at, how deep and at what cost.
 */

const val DefaultCredentialTtlMs: Long = 10L * 60 * 1000
const val MaxCredentialTtlMs: Long = 30L * 60 * 1000

/**
 * A credential before the three sign-offs are attached.
 */
data class CredentialDraft(
    val from: PerspectiveId,
    val to: PerspectiveId,
    val reason: String,
    /** What the target may read. Refs only; no bodies. */
    val visibleScope: List<Ref>,
    /** The payload handed over. Must be a subset of the visible scope. */
    val payloadRefs: List<Ref>,
    val budget: CredentialBudget,
    val constraints: BehaviorConstraints,
    val manifest: ManifestRef,
    val scenario: ScenarioId,
    val modes: Map<ModeId, Double>? = null,
    val issuedAt: Timestamp,
    val id: Id? = null,
    val ttlMs: Long? = null,
    val notes: List<String>? = null,
)

/**
 * The credential body before `digest` and `signoffs` are attached.
 */
data class UnsignedSwitchCredential(
    val id: Id,
    val from: PerspectiveId,
    val to: PerspectiveId,
    val reason: String,
    val visibleScope: List<Ref>,
    val payloadRefs: List<Ref>,
    val budget: CredentialBudget,
    val constraints: BehaviorConstraints,
    val manifest: ManifestRef,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val issuedAt: Timestamp,
    val expires: Timestamp,
    val notes: List<String>? = null,
    val kind: String = "switch-credential",
) {

    init {
        require(kind == "switch-credential") { "credential.kind 必须是 switch-credential" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "from" to JsonValue.Str(from.wire),
        "to" to JsonValue.Str(to.wire),
        "reason" to JsonValue.Str(reason),
        "visibleScope" to jsonArr(*visibleScope.map { it.toJson() }.toTypedArray()),
        "payloadRefs" to jsonArr(*payloadRefs.map { it.toJson() }.toTypedArray()),
        "budget" to budget.toJson(),
        "constraints" to behaviorConstraintsToJson(constraints),
        "manifest" to manifest.toJson(),
        "scenario" to JsonValue.Str(scenario.wire),
        "modes" to modes.toJson(),
        "issuedAt" to JsonValue.Str(issuedAt),
        "expires" to JsonValue.Str(expires),
        "notes" to notes?.let { list -> jsonArr(*list.map { JsonValue.Str(it) }.toTypedArray()) },
    )

    fun toCredential(digest: Digest, signoffs: List<Signoff>): SwitchCredential = SwitchCredential(
        id = id,
        from = from,
        to = to,
        reason = reason,
        visibleScope = visibleScope,
        payloadRefs = payloadRefs,
        budget = budget,
        constraints = constraints,
        manifest = manifest,
        scenario = scenario,
        modes = modes,
        issuedAt = issuedAt,
        expires = expires,
        notes = notes,
        kind = kind,
        digest = digest,
        signoffs = signoffs,
    )

    companion object {
        fun fromJson(value: JsonValue): UnsignedSwitchCredential {
            val obj = value.credentialObject()
            return UnsignedSwitchCredential(
                id = obj.requireString("id"),
                from = PerspectiveId.fromWire(obj.requireString("from")),
                to = PerspectiveId.fromWire(obj.requireString("to")),
                reason = obj.requireString("reason"),
                visibleScope = obj.refList("visibleScope"),
                payloadRefs = obj.refList("payloadRefs"),
                budget = credentialBudgetFromJson(obj.requireObj("budget")),
                constraints = behaviorConstraintsFromJson(obj.requireObj("constraints")),
                manifest = manifestRefFromJson(obj.requireObj("manifest")),
                scenario = ScenarioId.fromWire(obj.requireString("scenario")),
                modes = ModeWeights.fromJson(obj.requireObj("modes")),
                issuedAt = obj.requireString("issuedAt"),
                expires = obj.requireString("expires"),
                notes = obj.stringList("notes"),
                kind = obj.string("kind") ?: "switch-credential",
            )
        }
    }
}

/**
 * The signed credential.
 */
data class SwitchCredential(
    val id: Id,
    val from: PerspectiveId,
    val to: PerspectiveId,
    val reason: String,
    val visibleScope: List<Ref>,
    val payloadRefs: List<Ref>,
    val budget: CredentialBudget,
    val constraints: BehaviorConstraints,
    val manifest: ManifestRef,
    val scenario: ScenarioId,
    val modes: ModeWeights,
    val issuedAt: Timestamp,
    val expires: Timestamp,
    val digest: Digest,
    val signoffs: List<Signoff>,
    val notes: List<String>? = null,
    val kind: String = "switch-credential",
) {

    init {
        require(kind == "switch-credential") { "credential.kind 必须是 switch-credential" }
    }

    fun unsigned(): UnsignedSwitchCredential = UnsignedSwitchCredential(
        id = id,
        from = from,
        to = to,
        reason = reason,
        visibleScope = visibleScope,
        payloadRefs = payloadRefs,
        budget = budget,
        constraints = constraints,
        manifest = manifest,
        scenario = scenario,
        modes = modes,
        issuedAt = issuedAt,
        expires = expires,
        notes = notes,
        kind = kind,
    )

    fun toJson(): JsonValue.Obj = unsigned().toJson()
        .copyWith("digest", JsonValue.Str(digest))
        .copyWith("signoffs", jsonArr(*signoffs.map { it.toJson() }.toTypedArray()))

    companion object {
        fun fromJson(value: JsonValue): SwitchCredential {
            val obj = value.credentialObject()
            val unsigned = UnsignedSwitchCredential.fromJson(obj)
            val digest = obj.requireString("digest")
            val signoffs = obj.requireArr("signoffs").items.map(::signoffFromJson)
            return unsigned.toCredential(digest, signoffs)
        }
    }
}

data class CredentialRevocation(
    val credentialId: Id,
    val by: PerspectiveId,
    val at: Timestamp,
    val reason: String,
)

data class CredentialVerdict(
    val valid: Boolean,
    val code: String,
    val detail: String,
)

data class VerifyCredentialRequest(
    val at: Timestamp,
    val to: PerspectiveId,
    val manifest: ManifestRef? = null,
    val readRefs: List<Ref> = emptyList(),
    val revoked: List<CredentialRevocation> = emptyList(),
)

/**
 * Which department owns each sign-off role.
 */
val SignoffOwner: Map<SignoffRole, PerspectiveId> = mapOf(
    SignoffRole.ISSUER to PerspectiveId.GENERAL_MANAGER,
    SignoffRole.LEGAL to PerspectiveId.LEGAL,
    SignoffRole.FINANCE to PerspectiveId.FINANCE,
    SignoffRole.BOARD to PerspectiveId.BOARD,
    SignoffRole.HUMAN to PerspectiveId.SHAREHOLDERS,
)

private val credentialCounter = AtomicInteger(0)

/** Digest of the credential body: everything except `digest` and `signoffs`. */
fun credentialDigest(credential: SwitchCredential): Digest = CanonicalJson.digest(credential.unsigned().toJson())

fun credentialDigest(credential: UnsignedSwitchCredential): Digest = CanonicalJson.digest(credential.toJson())

/**
 * Issue a credential. Throws when the direction, the time window or the three-party
 * signature set is wrong; a credential that needs a fourth signature (board
 * authorisation for a strategic target) is issued with it.
 *
 * `from` and `to` are enum values, so the reference's "unknown perspective" branches
 * are enforced earlier by [PerspectiveId] parsing rather than here.
 */
fun issueCredential(draft: CredentialDraft, signers: List<SignerInput>): SwitchCredential {
    if (draft.from == draft.to) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "a credential cannot switch a perspective to itself",
        )
    }
    if (requiresEscalation(draft.from)) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "strategic perspectives hand off through the escalation path, not a switch credential",
        )
    }
    if (requiresEscalation(draft.to)) {
        val authorised = signers.any { it.role == SignoffRole.BOARD || it.role == SignoffRole.HUMAN }
        if (!authorised) {
            throw GovernanceError(
                GovernanceCode.CREDENTIAL_TARGET,
                "${draft.to.wire} needs a board or human authorisation sign-off",
            )
        }
    } else if (!canIssueCredential(draft.from, draft.to)) {
        throw GovernanceError(
            GovernanceCode.CREDENTIAL_TARGET,
            "${draft.from.wire} cannot hand off to ${draft.to.wire} with a same-level credential",
        )
    }
    if (draft.reason.trim().isEmpty()) {
        throw GovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE, "a credential needs a reason")
    }
    val visibleScope = dedupeRefs(draft.visibleScope)
    if (visibleScope.isEmpty()) {
        throw GovernanceError(GovernanceCode.CREDENTIAL_SCOPE, "a credential needs a visible scope")
    }
    val payloadRefs = dedupeRefs(draft.payloadRefs)
    val visibleKeys = visibleScope.map { refKey(it) }.toSet()
    for (ref in payloadRefs) {
        if (!visibleKeys.contains(refKey(ref))) {
            throw GovernanceError(
                GovernanceCode.CREDENTIAL_SCOPE,
                "payload ref ${refKey(ref)} is outside the visible scope",
            )
        }
    }
    val issuedMs = governanceMillis(draft.issuedAt)
        ?: throw GovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE, "issuedAt must be a parseable timestamp")
    val ttlMs = minOf(MaxCredentialTtlMs, maxOf(1_000L, draft.ttlMs ?: DefaultCredentialTtlMs))
    val plan = resolveScenarioModes(draft.scenario, draft.modes)
    val modes = draft.modes?.let { normalizeModeWeights(it) } ?: plan.modes
    val unsigned = UnsignedSwitchCredential(
        id = draft.id ?: "crd.${credentialCounter.incrementAndGet()}",
        from = draft.from,
        to = draft.to,
        reason = draft.reason,
        visibleScope = visibleScope,
        payloadRefs = payloadRefs,
        budget = CredentialBudget(quota = draft.budget.quota, elasticity = draft.budget.elasticity),
        constraints = copyConstraints(draft.constraints),
        manifest = draft.manifest.copy(),
        scenario = draft.scenario,
        modes = modes,
        issuedAt = draft.issuedAt,
        expires = isoMillisTimestamp(issuedMs + ttlMs),
        notes = draft.notes?.toList(),
    )
    val digest = credentialDigest(unsigned)
    val signoffs = checkSignoffs(digest, signers, draft.to)
    return unsigned.toCredential(digest, signoffs)
}

/**
 * Verify a credential against a request. Codes are stable so callers can map them to
 * receipts without parsing prose.
 *
 * Two reference checks are enforced earlier by the type system here: the credential's
 * perspectives and scenario are enum values, so an unknown name already fails closed in
 * [SwitchCredential.fromJson] instead of reaching the verifier.
 */
fun verifyCredential(credential: SwitchCredential, request: VerifyCredentialRequest): CredentialVerdict {
    val digest = credentialDigest(credential)
    if (digest != credential.digest) {
        return CredentialVerdict(false, "credential-tampered", "digest does not match the body")
    }
    val revoked = request.revoked.firstOrNull { it.credentialId == credential.id }
    if (revoked != null) {
        return CredentialVerdict(
            false,
            "credential-revoked",
            "revoked by ${revoked.by.wire}: ${revoked.reason}",
        )
    }
    if (credential.from == credential.to) {
        return CredentialVerdict(
            false,
            "credential-target",
            "a credential cannot switch a perspective to itself",
        )
    }
    if (credential.visibleScope.isEmpty()) {
        return CredentialVerdict(false, "credential-scope", "a credential needs a visible scope")
    }
    if (credential.manifest.id.isEmpty() || credential.manifest.version < 1) {
        return CredentialVerdict(
            false,
            "credential-manifest",
            "the credential cites an invalid manifest reference",
        )
    }
    val visible = credential.visibleScope.map { refKey(it) }.toSet()
    for (ref in credential.payloadRefs) {
        if (!visible.contains(refKey(ref))) {
            return CredentialVerdict(false, "credential-scope", "a payload ref is outside the visible scope")
        }
    }
    if (credential.to != request.to) {
        return CredentialVerdict(
            false,
            "credential-target",
            "credential is for ${credential.to.wire}, not ${request.to.wire}",
        )
    }
    if (requiresEscalation(credential.to) &&
        credential.signoffs.none { it.role == SignoffRole.BOARD || it.role == SignoffRole.HUMAN }
    ) {
        return CredentialVerdict(
            false,
            "credential-target",
            "${credential.to.wire} needs a board or human authorisation",
        )
    }
    val issued = governanceMillis(credential.issuedAt)
    val expires = governanceMillis(credential.expires)
    val at = governanceMillis(request.at)
    if (issued == null || expires == null || at == null) {
        return CredentialVerdict(false, "credential-incomplete", "unparseable time window")
    }
    if (at < issued) {
        return CredentialVerdict(
            false,
            "credential-not-yet-valid",
            "issued at ${credential.issuedAt}",
        )
    }
    if (at >= expires) {
        return CredentialVerdict(false, "credential-expired", "expired at ${credential.expires}")
    }
    request.manifest?.let { expected ->
        if (expected.id != credential.manifest.id || expected.version != credential.manifest.version) {
            return CredentialVerdict(
                false,
                "credential-manifest",
                "manifest ${credential.manifest.id} v${credential.manifest.version} expected",
            )
        }
    }
    val scope = credential.visibleScope.map { refKey(it) }.toSet()
    for (ref in request.readRefs) {
        if (!scope.contains(refKey(ref))) {
            return CredentialVerdict(false, "credential-scope", "${refKey(ref)} is outside the visible scope")
        }
    }
    val seen = LinkedHashSet<PerspectiveId>()
    val roles = credential.signoffs.map { it.role }.toSet()
    for (signoff in credential.signoffs) {
        if (signoff.digest != digest) {
            return CredentialVerdict(
                false,
                "credential-tampered",
                "${signoff.role.wire} signature covers another digest",
            )
        }
        if (signoff.by == credential.to) {
            return CredentialVerdict(false, "credential-target", "the target signed its own credential")
        }
        val owner = SignoffOwner.getValue(signoff.role)
        if (signoff.by != owner) {
            return CredentialVerdict(
                false,
                "credential-incomplete",
                "${signoff.role.wire} was signed by ${signoff.by.wire}",
            )
        }
        if (!seen.add(signoff.by)) {
            return CredentialVerdict(false, "credential-incomplete", "${signoff.by.wire} signed twice")
        }
    }
    for (role in listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE)) {
        if (!roles.contains(role)) {
            return CredentialVerdict(false, "credential-incomplete", "the triple sign-off is incomplete")
        }
    }
    return CredentialVerdict(
        true,
        "valid",
        "${credential.id} authorises ${credential.to.wire} until ${credential.expires}",
    )
}

/**
 * Minimal read check: the ref must be inside the visible scope.
 */
fun canRead(credential: SwitchCredential, ref: Ref): Boolean =
    credential.visibleScope.any { refKey(it) == refKey(ref) }

/**
 * Revocation is an event, not an edit: the credential stays byte-identical and the
 * verifier consults the revocation list.
 */
fun revokeCredential(
    credential: SwitchCredential,
    by: PerspectiveId,
    reason: String,
    at: Timestamp,
): CredentialRevocation {
    if (by != PerspectiveId.LEGAL && by != PerspectiveId.GENERAL_MANAGER && by != PerspectiveId.BOARD) {
        throw GovernanceError(
            GovernanceCode.APPROVAL_NOT_PERMITTED,
            "${by.wire} may not revoke a credential",
        )
    }
    return CredentialRevocation(credentialId = credential.id, by = by, at = at, reason = reason)
}

/**
 * One-line audit text for a frame or receipt.
 */
fun credentialSummary(credential: SwitchCredential): String =
    "${credential.id} ${credential.from.wire}->${credential.to.wire} " +
        "scope=${credential.visibleScope.size} expires=${credential.expires}"

/**
 * The target perspective's layer, exposed so callers do not import the registry just to
 * log a handoff.
 */
fun credentialTargetLayer(credential: SwitchCredential): PerspectiveLayer = perspective(credential.to).layer

/**
 * The expected cryptographic binding for a credential.
 */
fun switchCredentialExpectation(credential: SwitchCredential): GovernanceExpectation = GovernanceExpectation(
    subject = GovernanceSubject.SWITCH_CREDENTIAL,
    subjectId = credential.id,
    subjectVersion = null,
    digest = credential.digest,
)

/**
 * Cryptographic signature hook: sign the credential digest with every supplied
 * governance signer.
 */
fun signSwitchCredential(credential: SwitchCredential, signers: List<GovernanceSigner>): GovernanceSignatureBundle =
    signGovernanceObject(
        subject = GovernanceSubject.SWITCH_CREDENTIAL,
        subjectId = credential.id,
        subjectVersion = null,
        digest = credential.digest,
        signers = signers,
    )

/**
 * Cryptographic verification hook: verify a bundle against this credential's subject and
 * digest.
 */
fun verifySwitchCredentialSignatures(
    credential: SwitchCredential,
    bundle: GovernanceSignatureBundle,
    keyring: GovernanceKeyring,
    requiredSigners: List<String>? = null,
): GovernanceVerification = verifyGovernanceBundle(
    bundle = bundle,
    expected = switchCredentialExpectation(credential),
    keyring = keyring,
    requiredSigners = requiredSigners,
)

private fun checkSignoffs(digest: Digest, signers: List<SignerInput>, target: PerspectiveId): List<Signoff> {
    val required = listOf(SignoffRole.ISSUER, SignoffRole.LEGAL, SignoffRole.FINANCE)
    val seen = LinkedHashSet<PerspectiveId>()
    val accepted = ArrayList<Signoff>()
    for (signer in signers) {
        if (signer.by == target) {
            throw GovernanceError(
                GovernanceCode.CREDENTIAL_TARGET,
                "${target.wire} cannot sign its own credential",
            )
        }
        if (!seen.add(signer.by)) {
            throw GovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE, "${signer.by.wire} signed twice")
        }
        val owner = SignoffOwner.getValue(signer.role)
        if (signer.by != owner) {
            throw GovernanceError(
                GovernanceCode.CREDENTIAL_INCOMPLETE,
                "${signer.role.wire} must be signed by ${owner.wire}",
            )
        }
        accepted += Signoff(role = signer.role, by = signer.by, at = signer.at, digest = digest)
    }
    for (role in required) {
        if (accepted.none { it.role == role }) {
            throw GovernanceError(GovernanceCode.CREDENTIAL_INCOMPLETE, "missing ${role.wire} sign-off")
        }
    }
    return accepted
}

private fun dedupeRefs(refs: List<Ref>): List<Ref> {
    val seen = LinkedHashSet<String>()
    val result = ArrayList<Ref>()
    for (ref in refs) {
        if (ref.id.isEmpty()) {
            // `Ref`'s constructor already enforces the AMP id grammar; kept for parity.
            throw GovernanceError(GovernanceCode.CREDENTIAL_SCOPE, "a credential ref needs a non-empty id")
        }
        if (!seen.add(refKey(ref))) {
            continue
        }
        result += ref
    }
    return result
}

private fun copyConstraints(constraints: BehaviorConstraints): BehaviorConstraintSet = BehaviorConstraintSet(
    allowed = constraints.allowed.toList(),
    forbidden = constraints.forbidden.toList(),
    obligations = constraints.obligations.toList(),
    escalation = constraints.escalation.toList(),
    evidence = constraints.evidence,
)

/** Manifest reference JSON, shared with the manifest module's lineage records. */
internal fun ManifestRef.toJson(): JsonValue.Obj = jsonObj(
    "id" to JsonValue.Str(id),
    "version" to jsonNumber(version),
)

internal fun manifestRefFromJson(value: JsonValue): ManifestRef {
    val obj = value.credentialObject()
    return ManifestRef(id = obj.requireString("id"), version = obj.requireInt("version"))
}

/** Credential budget JSON: quota plus elasticity. */
internal fun CredentialBudget.toJson(): JsonValue.Obj = jsonObj(
    "quota" to quota.toJson(),
    "elasticity" to JsonValue.Str(elasticity.wire),
)

internal fun credentialBudgetFromJson(value: JsonValue): CredentialBudget {
    val obj = value.credentialObject()
    return CredentialBudget(
        quota = Budget.fromJson(obj.requireObj("quota")),
        elasticity = Elasticity.fromWire(obj.requireString("elasticity")),
    )
}

private fun JsonValue.credentialObject(): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw IllegalArgumentException("credential 必须是 JSON 对象")

private fun JsonValue.Obj.refList(key: String): List<Ref> = requireArr(key).items.map(Ref::fromJson)

private fun JsonValue.Obj.stringList(key: String): List<String>? = arr(key)?.items?.map { item ->
    (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("$key 的元素必须是字符串")
}
