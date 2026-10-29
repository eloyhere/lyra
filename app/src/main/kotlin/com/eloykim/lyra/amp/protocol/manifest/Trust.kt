package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.types.Timestamp
import com.eloykim.lyra.amp.protocol.types.nowTimestamp

/**
 * The policy gate, mirroring `trust.ts`: keys come from the bundle,
 * endorsement comes from attestations bound to the manifest digest, and
 * required scopes must be signed by an issuer key the caller already trusts.
 */

data class TrustPolicy(
    /** Scopes an attestation must carry before the card is usable. */
    val requiredScopes: List<String>,
)

data class TrustResult(
    val ok: Boolean,
    val reasons: List<String>,
    val scopes: List<String>,
    val endpoint: String? = null,
)

data class TrustCardInput(
    val card: AgentCard,
    val signature: CardSignature,
    val bundle: ManifestBundle,
    val policy: TrustPolicy,
    val issuerKeys: KeyRegistry? = null,
    /** Verification time. Defaults to the real clock. */
    val now: Timestamp? = null,
)

data class FetchTrustedOptions(
    val bundle: ManifestBundle,
    val policy: TrustPolicy,
    val issuerKeys: KeyRegistry? = null,
    val timeoutMs: Long = 5_000L,
    /** Verification time. Defaults to the real clock. */
    val now: Timestamp? = null,
)

data class TrustedAgentResult(
    val ok: Boolean,
    val reasons: List<String>,
    val scopes: List<String>,
    val endpoint: String? = null,
    val card: AgentCard? = null,
)

/** Applies the trust policy to a card, a signature and a bundle. */
fun trustCard(input: TrustCardInput): TrustResult {
    val reasons = ArrayList<String>()
    val registry = registryFromBundle(input.bundle)
    reasons += verifyCardWithRegistry(input.card, input.signature, registry).reasons

    val now = instantOf(input.now ?: nowTimestamp())?.toEpochMilli()
    if (now == null) {
        reasons += "now is not a valid timestamp"
    }
    val skew = DEFAULT_CLOCK_SKEW_MS

    val scopes = LinkedHashSet<String>()
    input.bundle.attestations.orEmpty().forEach { attestation ->
        if (attestation.subject.digest != input.bundle.manifest.digest) {
            reasons += "attestation ${attestation.id} does not bind the manifest digest"
            return@forEach
        }
        val expires = instantOf(attestation.expires)?.toEpochMilli()
        val issued = instantOf(attestation.issued)?.toEpochMilli()
        if (expires == null || issued == null) {
            reasons += "attestation ${attestation.id}: invalid validity window"
            return@forEach
        }
        if (now != null && expires + skew < now) {
            reasons += "attestation ${attestation.id}: expired"
            return@forEach
        }
        if (now != null && issued - skew > now) {
            reasons += "attestation ${attestation.id}: issued in the future"
            return@forEach
        }
        val kid = attestation.signatures.firstOrNull()?.kid
        val issuerKey = kid?.let { input.issuerKeys?.get(it) }
        if (issuerKey == null) {
            reasons += "attestation ${attestation.id}: unknown issuer key ${kid ?: "undefined"}"
            return@forEach
        }
        val verdict = verifyAttestationWithKey(attestation, issuerKey)
        if (!verdict.ok) {
            verdict.reasons.forEach { reason -> reasons += "${attestation.id}: $reason" }
            return@forEach
        }
        scopes += attestation.scope
    }

    input.policy.requiredScopes.forEach { required ->
        if (!scopes.contains(required)) {
            reasons += "missing required scope: $required"
        }
    }

    return TrustResult(ok = reasons.isEmpty(), reasons = reasons, scopes = scopes.toList())
}

/** Discovery + trust in one call: fetch the card, then apply the policy. */
fun fetchTrustedAgent(
    baseUrl: String,
    options: FetchTrustedOptions,
    fetcher: CardFetcher = HttpCardFetcher,
): TrustedAgentResult {
    val discovered = fetchAgentCard(baseUrl, CardOptions(timeoutMs = options.timeoutMs), fetcher)
    val card = discovered.card
    if (!discovered.ok || card == null) {
        return TrustedAgentResult(ok = false, reasons = discovered.reasons, scopes = emptyList())
    }
    val signatures = card.signatures()
    if (signatures.isEmpty()) {
        return TrustedAgentResult(
            ok = false,
            reasons = listOf("card carries no signature"),
            scopes = emptyList(),
            card = card,
        )
    }
    val verdict = trustCard(
        TrustCardInput(
            card = card,
            signature = signatures.first(),
            bundle = options.bundle,
            policy = options.policy,
            issuerKeys = options.issuerKeys,
            now = options.now,
        ),
    )
    return TrustedAgentResult(
        ok = verdict.ok,
        reasons = verdict.reasons,
        scopes = verdict.scopes,
        endpoint = discovered.endpoint,
        card = card,
    )
}
