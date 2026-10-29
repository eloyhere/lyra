package com.eloykim.lyra.amp.protocol.protocol

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonBool
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * Transport seam for one delegation.
 *
 * The reference is asynchronous (`Promise<ChannelVerdict>`); this port is
 * synchronous because the Android runtime has no coroutine dependency and every
 * production transport is a blocking socket or HTTP call.
 */
data class ChannelVerdict(
    val accepted: Boolean,
    val reason: String? = null,
) {
    fun toJson(): JsonValue.Obj = jsonObj(
        "accepted" to jsonBool(accepted),
        "reason" to reason?.let { jsonText(it) },
    )

    companion object {
        /**
         * Parses `{ accepted, reason }`. A missing `accepted` is false, matching
         * the reference's `body.accepted === true` check.
         */
        fun fromJson(value: JsonValue): ChannelVerdict {
            val obj = value.asObject("channel verdict")
            return ChannelVerdict(
                accepted = obj.bool("accepted") == true,
                reason = obj.string("reason"),
            )
        }
    }
}

/** One hop from a granter to a peer. A peer's promise is never trusted. */
interface Channel {
    fun send(doc: DelegationDoc): ChannelVerdict
}

/**
 * Loopback channel: the peer is a policy function, not a promise.
 *
 * It exists so the transport seam is testable before any network code lands,
 * and it records every attempt, accepted or rejected.
 */
class LoopbackChannel(
    private val policy: (DelegationDoc) -> ChannelVerdict = { ChannelVerdict(accepted = true) },
) : Channel {

    private val attempts: MutableList<DelegationDoc> = ArrayList()

    /** Every document handed to this channel, in send order. */
    val sent: List<DelegationDoc> get() = attempts

    override fun send(doc: DelegationDoc): ChannelVerdict {
        attempts += doc
        return policy(doc)
    }
}

/**
 * Sends one delegation and fails closed if the peer declines it.
 *
 * The exception type is [IllegalStateException] because the reference throws a
 * plain `Error`; the message text is preserved.
 */
fun delegateOver(channel: Channel, delegation: Delegation): DelegationDoc {
    val doc = toDelegationDoc(delegation)
    val verdict = channel.send(doc)
    if (!verdict.accepted) {
        throw IllegalStateException("delegation rejected: ${verdict.reason ?: "no reason given"}")
    }
    return doc
}
