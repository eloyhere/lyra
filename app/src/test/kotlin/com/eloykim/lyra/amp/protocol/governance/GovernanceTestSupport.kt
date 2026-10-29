package com.eloykim.lyra.amp.protocol.governance

import com.eloykim.lyra.amp.protocol.semantic.Sha256
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/** Shared helpers for the governance test suite. */

/** Issue instant used by the manifest and credential fixtures. */
internal const val AT_ISSUE: String = "2026-10-24T07:00:00Z"

/** Ten minutes after [AT_ISSUE]. */
internal const val AT_TEN_MIN: String = "2026-10-24T07:10:00Z"

/** Twelve hours after [AT_ISSUE], the default manifest expiry. */
internal const val AT_MANIFEST_EXPIRY: String = "2026-10-24T19:00:00Z"

internal fun testClock(timestamp: String = AT_ISSUE): () -> Timestamp = { timestamp }

internal fun assertGovernanceError(expected: GovernanceCode, block: () -> Unit) {
    var caught: GovernanceError? = null
    try {
        block()
    } catch (error: GovernanceError) {
        caught = error
    }
    assertNotNull("期望 GovernanceError(${expected.wire})", caught)
    assertEquals(expected, caught!!.code)
}

internal fun refOf(kind: RefKind, id: String): Ref = Ref(kind = kind, id = id)

internal fun digestOfText(seed: String): String = "sha256:" + Sha256.hex(seed)
