package com.eloykim.lyra.amp.protocol.manifest

import com.eloykim.lyra.amp.protocol.types.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.PublicKey

/** Key lifecycle, window checks and revocation merging, ported from keys.ts. */
class KeysTest {

    private fun publicKey(kid: String): PublicKey = Fixtures.requireSigner(kid).publicKey()!!

    private fun record(
        kid: String,
        key: PublicKey,
        status: KeyStatus = KeyStatus.ACTIVE,
        validFrom: Timestamp = "2026-10-24T10:00:00.000Z",
        validTo: Timestamp? = null,
        note: String? = null,
    ): KeyRecord = KeyRecord(kid, key, status, validFrom, validTo, note = note)

    @Test
    fun `get 把 validFrom 与 validTo 当作闭区间`() {
        val key = publicKey("agent#1")
        val ring = Keyring(KeyringOptions(now = { "2026-10-24T10:30:00.000Z" }))
        ring.add(record("agent#1", key, validFrom = "2026-10-24T10:00:00.000Z", validTo = "2026-10-24T11:00:00.000Z"))

        assertEquals(key, ring.get("agent#1"))
        assertEquals(key, ring.get("agent#1", "2026-10-24T10:00:00.000Z"))
        assertEquals(key, ring.get("agent#1", "2026-10-24T11:00:00.000Z"))
        assertNull(ring.get("agent#1", "2026-10-24T09:59:59.999Z"))
        assertNull(ring.get("agent#1", "2026-10-24T11:00:00.001Z"))
        assertNull(ring.get("missing"))
        assertNull(ring.record("missing"))
    }

    @Test
    fun `clockSkewMs 只在配置后放宽窗口`() {
        val strict = Keyring(clock = { "2026-10-24T10:30:00.000Z" })
        val tolerant = Keyring(clock = { "2026-10-24T10:30:00.000Z" }, clockSkewMs = 1_000)
        val strictKey = publicKey("strict#1")
        val tolerantKey = publicKey("tolerant#1")
        strict.add(record("strict#1", strictKey, validFrom = "2026-10-24T10:00:00.000Z", validTo = "2026-10-24T11:00:00.000Z"))
        tolerant.add(record("tolerant#1", tolerantKey, validFrom = "2026-10-24T10:00:00.000Z", validTo = "2026-10-24T11:00:00.000Z"))

        assertNull(strict.get("strict#1", "2026-10-24T09:59:59.500Z"))
        assertEquals(tolerantKey, tolerant.get("tolerant#1", "2026-10-24T09:59:59.500Z"))
        assertEquals(tolerantKey, tolerant.get("tolerant#1", "2026-10-24T11:00:00.500Z"))
        assertNull(tolerant.get("tolerant#1", "2026-10-24T11:00:01.001Z"))
    }

    @Test
    fun `add 拒绝重复 kid 与倒置或非法窗口`() {
        val ring = Keyring()
        val key = publicKey("agent#1")
        ring.add(record("agent#1", key))
        assertTrue(runCatching { ring.add(record("agent#1", key)) }.isFailure)

        val inverted = runCatching {
            ring.add(record("bad#1", key, validFrom = "2026-10-24T11:00:00.000Z", validTo = "2026-10-24T10:00:00.000Z"))
        }.exceptionOrNull()
        assertTrue(inverted?.message?.contains("validTo") == true)
        assertNull(ring.record("bad#1"))

        val equal = runCatching {
            ring.add(record("bad#2", key, validFrom = "2026-10-24T11:00:00.000Z", validTo = "2026-10-24T11:00:00.000Z"))
        }
        assertTrue(equal.isFailure)
        assertTrue(
            runCatching { ring.add(record("bad#3", key, validFrom = "not-a-date")) }
                .exceptionOrNull()?.message?.contains("validFrom") == true,
        )
        assertTrue(
            runCatching { ring.add(record("bad#4", key, validFrom = "2026-10-24T10:00:00.000Z", validTo = "not-a-date")) }
                .exceptionOrNull()?.message?.contains("validTo") == true,
        )
    }

    @Test
    fun `rotate 让旧键退休并保留重叠窗口`() {
        var now = "2026-10-24T12:00:00.000Z"
        val ring = Keyring(clock = { now })
        val oldKey = publicKey("agent#1")
        val nextKey = publicKey("agent#2")
        ring.add(record("agent#1", oldKey, validFrom = "2026-10-20T00:00:00.000Z"))

        val successor = ring.rotate("agent#1", KeyRotation("agent#2", nextKey, overlapMs = 60_000))
        assertEquals("agent#2", successor.kid)
        assertEquals(KeyStatus.ACTIVE, successor.status)
        assertEquals("agent#1", successor.supersedes)
        assertEquals(now, successor.validFrom)

        val retired = ring.record("agent#1")
        assertEquals(KeyStatus.RETIRED, retired?.status)
        assertEquals("2026-10-24T12:01:00.000Z", retired?.validTo)
        assertEquals(listOf("agent#2"), ring.active().map { it.kid })

        assertEquals(oldKey, ring.get("agent#1", "2026-10-24T12:00:30.000Z"))
        assertEquals(oldKey, ring.get("agent#1", "2026-10-24T12:01:00.000Z"))
        assertNull(ring.get("agent#1", "2026-10-24T12:01:00.001Z"))
        assertEquals(oldKey, ring.get("agent#1", "2026-10-21T00:00:00.000Z"))
        assertNull(ring.get("agent#2", "2026-10-24T11:59:59.999Z"))
        assertEquals(nextKey, ring.get("agent#2"))

        now = "2026-10-24T12:01:00.001Z"
        assertNull(ring.get("agent#1"))
        assertEquals(KeyStatus.RETIRED, ring.record("agent#1")?.status)
    }

    @Test
    fun `轮换重叠不会延长已有 validTo`() {
        val ring = Keyring(clock = { "2026-10-24T12:00:00.000Z" })
        ring.add(
            record(
                "agent#1",
                publicKey("agent#1"),
                validFrom = "2026-10-24T11:00:00.000Z",
                validTo = "2026-10-24T12:00:30.000Z",
            ),
        )
        ring.rotate("agent#1", KeyRotation("agent#2", publicKey("agent#2"), overlapMs = 3_600_000))

        assertEquals("2026-10-24T12:00:30.000Z", ring.record("agent#1")?.validTo)
        assertNotNull(ring.get("agent#1", "2026-10-24T12:00:30.000Z"))
        assertNull(ring.get("agent#1", "2026-10-24T12:00:30.001Z"))
    }

    @Test
    fun `rotate 拒绝负数重叠、重复与未知或已退休或已撤销的键`() {
        val ring = Keyring(clock = { "2026-10-24T12:00:00.000Z" })
        ring.add(record("agent#1", publicKey("agent#1"), validFrom = "2026-10-20T00:00:00.000Z"))

        assertTrue(
            runCatching { ring.rotate("agent#1", KeyRotation("agent#2", publicKey("agent#2"), overlapMs = -1)) }
                .exceptionOrNull()?.message?.contains("overlapMs") == true,
        )
        assertTrue(
            runCatching { ring.rotate("agent#1", KeyRotation("agent#1", publicKey("agent#2"))) }
                .exceptionOrNull()?.message?.contains("duplicate kid") == true,
        )
        assertTrue(
            runCatching { ring.rotate("missing", KeyRotation("agent#2", publicKey("agent#2"))) }
                .exceptionOrNull()?.message?.contains("unknown key") == true,
        )
        assertEquals(KeyStatus.ACTIVE, ring.record("agent#1")?.status)

        ring.rotate("agent#1", KeyRotation("agent#2", publicKey("agent#2")))
        assertTrue(
            runCatching { ring.rotate("agent#1", KeyRotation("agent#3", publicKey("agent#3"))) }
                .exceptionOrNull()?.message?.contains("retired") == true,
        )
        ring.revoke("agent#2")
        assertTrue(
            runCatching { ring.rotate("agent#2", KeyRotation("agent#3", publicKey("agent#3"))) }
                .exceptionOrNull()?.message?.contains("revoked") == true,
        )
    }

    @Test
    fun `撤销永久优先于历史和重叠`() {
        val now = "2026-10-24T12:00:00.000Z"
        val ring = Keyring(clock = { now })
        ring.add(record("agent#1", publicKey("agent#1"), validFrom = "2026-10-20T00:00:00.000Z"))
        ring.rotate("agent#1", KeyRotation("agent#2", publicKey("agent#2"), overlapMs = 3_600_000))

        assertNotNull(ring.get("agent#1", "2026-10-24T12:30:00.000Z"))
        assertTrue(ring.revoke("agent#1", "2026-10-24T12:10:00.000Z", "key compromise"))
        assertTrue(ring.isRevoked("agent#1"))
        assertNull(ring.get("agent#1", "2026-10-24T12:30:00.000Z"))
        assertNull(ring.get("agent#1", "2026-10-21T00:00:00.000Z"))
        assertNotNull(ring.get("agent#2", "2026-10-24T12:30:00.000Z"))
        assertEquals(KeyStatus.REVOKED, ring.record("agent#1")?.status)
        assertFalse(ring.revoke("agent#1"))
        assertFalse(ring.revoke("missing"))
        assertFalse(ring.retire("agent#1"))

        val revocation = ring.revocations().single()
        assertEquals("agent#1", revocation.kid)
        assertEquals("key compromise", revocation.reason)
        assertEquals("2026-10-24T12:10:00.000Z", revocation.revokedAt)
    }

    @Test
    fun `retire 把可用窗口截到退休时刻`() {
        var now = "2026-10-24T09:00:00.000Z"
        val ring = Keyring(clock = { now })
        ring.add(
            record(
                "agent#1",
                publicKey("agent#1"),
                validFrom = "2026-10-24T08:00:00.000Z",
                validTo = "2026-10-24T12:00:00.000Z",
            ),
        )
        assertTrue(ring.retire("agent#1", "2026-10-24T10:00:00.000Z"))
        assertEquals(KeyStatus.RETIRED, ring.record("agent#1")?.status)
        assertEquals("2026-10-24T10:00:00.000Z", ring.record("agent#1")?.validTo)
        assertNotNull(ring.get("agent#1", "2026-10-24T09:59:59.999Z"))
        assertNotNull(ring.get("agent#1", "2026-10-24T10:00:00.000Z"))
        assertNull(ring.get("agent#1", "2026-10-24T10:00:00.001Z"))
        assertEquals(0, ring.active("2026-10-24T09:00:00.000Z").size)

        now = "2026-10-24T10:30:00.000Z"
        assertNull(ring.get("agent#1"))
        assertFalse(ring.retire("agent#1"))
        assertFalse(ring.retire("missing"))

        val early = Keyring()
        early.add(record("agent#2", publicKey("agent#2"), validFrom = "2026-10-24T10:00:00.000Z"))
        assertTrue(
            runCatching { early.retire("agent#2", "2026-10-24T10:00:00.000Z") }
                .exceptionOrNull()?.message?.contains("after validFrom") == true,
        )
    }

    @Test
    fun `active 只报告当前窗口内且活跃的键`() {
        val ring = Keyring()
        ring.add(
            record(
                "agent#1",
                publicKey("agent#1"),
                validFrom = "2026-10-24T10:00:00.000Z",
                validTo = "2026-10-24T11:00:00.000Z",
            ),
        )
        ring.add(
            record(
                "agent#2",
                publicKey("agent#2"),
                validFrom = "2026-10-24T11:00:00.000Z",
                validTo = "2026-10-24T12:00:00.000Z",
            ),
        )
        assertEquals(listOf("agent#1"), ring.active("2026-10-24T10:30:00.000Z").map { it.kid })
        assertEquals(listOf("agent#1", "agent#2"), ring.active("2026-10-24T11:00:00.000Z").map { it.kid })
        assertEquals(0, ring.active("2026-10-24T12:00:00.001Z").size)

        ring.retire("agent#1", "2026-10-24T10:30:00.000Z")
        ring.revoke("agent#2", "2026-10-24T11:30:00.000Z")
        assertEquals(0, ring.active("2026-10-24T10:45:00.000Z").size)
    }

    @Test
    fun `verifyWindow 解释缺失、过早、过期和撤销`() {
        val ring = Keyring(clock = { "2026-10-24T10:30:00.000Z" })
        ring.add(
            record(
                "agent#1",
                publicKey("agent#1"),
                validFrom = "2026-10-24T10:00:00.000Z",
                validTo = "2026-10-24T11:00:00.000Z",
            ),
        )
        assertTrue(ring.verifyWindow("missing", "2026-10-24T10:30:00.000Z").reasons.any { it.contains("missing") })
        assertTrue(ring.verifyWindow("agent#1", "2026-10-24T09:59:59.999Z").reasons.any { it.contains("not yet valid") })
        assertTrue(ring.verifyWindow("agent#1", "2026-10-24T10:30:00.000Z").ok)
        assertTrue(ring.verifyWindow("agent#1", "2026-10-24T11:00:00.001Z").reasons.any { it.contains("expired") })
        ring.revoke("agent#1", "2026-10-24T10:30:00.000Z")
        assertTrue(ring.verifyWindow("agent#1", "2026-10-24T10:30:00.000Z").reasons.any { it.contains("revoked") })
    }

    @Test
    fun `以 revoked 状态加入的键保留审计但永不可用`() {
        val ring = Keyring(clock = { "2026-10-24T11:00:00.000Z" })
        ring.add(
            record(
                "agent#9",
                publicKey("agent#9"),
                status = KeyStatus.REVOKED,
                validFrom = "2026-10-24T10:00:00.000Z",
                note = "imported revocation",
            ),
        )
        assertNull(ring.get("agent#9"))
        assertTrue(ring.isRevoked("agent#9"))
        assertEquals(KeyStatus.REVOKED, ring.record("agent#9")?.status)
        val revocation = ring.revocations().single()
        assertEquals("2026-10-24T10:00:00.000Z", revocation.revokedAt)
        assertEquals("imported revocation", revocation.reason)
    }

    @Test
    fun `撤销列表新鲜度接受当前列表并拒绝过期或未来签发`() {
        val fresh = freshList()
        assertTrue(isRevocationFresh(fresh, "2026-10-24T11:00:00.000Z", 0.0))
        assertTrue(isRevocationFresh(fresh.copy(nextUpdate = "2026-10-24T11:00:00.000Z"), "2026-10-24T11:00:00.000Z", 0.0))
        assertTrue(isRevocationFresh(fresh, "2026-10-24T12:00:01.000Z", 1_000.0))

        assertFalse(isRevocationFresh(fresh, "2026-10-24T12:00:00.001Z", 0.0))
        assertFalse(isRevocationFresh(fresh, "2026-10-24T12:00:01.001Z", 1_000.0))
        assertFalse(isRevocationFresh(fresh.copy(issued = "2026-10-24T11:00:00.001Z"), "2026-10-24T11:00:00.000Z", 0.0))
        assertFalse(
            isRevocationFresh(
                fresh.copy(issued = "2026-10-24T12:00:00.000Z", nextUpdate = "2026-10-24T11:00:00.000Z"),
                "2026-10-24T10:00:00.000Z",
                0.0,
            ),
        )
    }

    @Test
    fun `新鲜度对非法时间与非法参数 fail closed`() {
        val fresh = freshList()
        val now = "2026-10-24T11:00:00.000Z"
        assertFalse(isRevocationFresh(fresh.copy(issued = "not-a-date"), now, 0.0))
        assertFalse(isRevocationFresh(fresh.copy(nextUpdate = "not-a-date"), now, 0.0))
        assertFalse(
            isRevocationFresh(fresh.copy(revoked = listOf(RevocationEntry("agent#0", "not-a-date"))), now, 0.0),
        )
        assertFalse(isRevocationFresh(fresh, "not-a-date", 0.0))
        assertFalse(isRevocationFresh(fresh, now, Double.NaN))
        assertFalse(isRevocationFresh(fresh, now, -1.0))
    }

    @Test
    fun `requireFresh 返回新鲜列表否则抛 RevocationError`() {
        val fresh = freshList()
        assertSame(fresh, requireFresh(fresh, "2026-10-24T11:00:00.000Z", 0.0))
        val error = runCatching { requireFresh(fresh, "2026-10-24T13:00:00.000Z", 0.0) }.exceptionOrNull()
        assertTrue(error is RevocationError)
        assertTrue((error as RevocationError).reasons.any { it.contains("stale") })
    }

    @Test
    fun `mergeRevocations 取并集并保留最早撤销时间`() {
        val merged = mergeRevocations(localRevocations(), remoteRevocations())
        assertEquals("org.acme", merged.issuer)
        assertEquals("2026-10-24T11:00:00.000Z", merged.issued)
        assertEquals("2026-10-24T13:00:00.000Z", merged.nextUpdate)
        assertEquals(listOf("agent#1", "agent#2", "agent#3"), merged.revoked.map { it.kid })
        val second = merged.revoked.first { it.kid == "agent#2" }
        assertEquals("2026-10-24T08:30:00.000Z", second.revokedAt)
        assertEquals("remote", second.reason)
        assertEquals(2, localRevocations().revoked.size)

        val forward = mergeRevocations(localRevocations(), remoteRevocations())
        val reverse = mergeRevocations(remoteRevocations(), localRevocations())
        assertEquals(forward, reverse)
        assertEquals(forward, mergeRevocations(forward, remoteRevocations()))
        assertEquals(forward, mergeRevocations(forward, localRevocations()))
    }

    @Test
    fun `mergeRevocations 对并列条目稳定并拒绝不同 issuer`() {
        val tiedLocal = freshList(revoked = listOf(RevocationEntry("agent#5", "2026-10-24T09:00:00.000Z")))
        val tiedRemote = freshList(
            revoked = listOf(RevocationEntry("agent#5", "2026-10-24T09:00:00.000Z", "evidence")),
        )
        assertEquals(mergeRevocations(tiedLocal, tiedRemote), mergeRevocations(tiedRemote, tiedLocal))
        assertEquals("evidence", mergeRevocations(tiedLocal, tiedRemote).revoked.single().reason)

        assertTrue(
            runCatching { mergeRevocations(localRevocations(), remoteRevocations().copy(issuer = "org.evil")) }
                .exceptionOrNull()?.message?.contains("different issuers") == true,
        )
        assertTrue(
            runCatching { mergeRevocations(localRevocations().copy(issued = "not-a-date"), remoteRevocations()) }
                .isFailure,
        )
        assertTrue(
            runCatching {
                mergeRevocations(
                    localRevocations().copy(revoked = listOf(RevocationEntry("bad", "not-a-date"))),
                    remoteRevocations(),
                )
            }.isFailure,
        )
    }

    @Test
    fun `checkManifestWindow 接受有效窗口并拒绝过期与未来签发`() {
        assertTrue(
            checkManifestWindow(
                issued = "2026-10-24T10:00:00.000Z",
                expires = "2026-10-24T12:00:00.000Z",
                now = "2026-10-24T11:00:00.000Z",
                skewMs = 0,
            ).ok,
        )
        assertTrue(
            checkManifestWindow(
                issued = "2026-10-24T10:00:00.000Z",
                expires = "2026-10-24T12:00:00.000Z",
                now = "2026-10-24T12:00:00.001Z",
                skewMs = 0,
            ).reasons.any { it.contains("expired") },
        )
        assertTrue(
            checkManifestWindow(
                issued = "2026-10-24T10:00:00.000Z",
                expires = "2026-10-24T12:00:00.000Z",
                now = "2026-10-24T09:00:00.000Z",
                skewMs = 0,
            ).reasons.any { it.contains("future") },
        )
        val fixture = Fixtures.signed()
        assertTrue(checkManifestWindow(fixture.manifest, Fixtures.NOW).ok)
    }

    @Test
    fun `checkManifestWindow 支持显式与默认时钟偏移`() {
        val issued = "2026-10-24T10:00:00.000Z"
        val expires = "2026-10-24T12:00:00.000Z"
        assertTrue(checkManifestWindow(issued, expires, "2026-10-24T12:00:30.000Z", 60_000).ok)
        assertFalse(checkManifestWindow(issued, expires, "2026-10-24T12:01:01.000Z", 60_000).ok)
        assertTrue(checkManifestWindow(issued, expires, "2026-10-24T09:59:30.000Z", 60_000).ok)
        assertFalse(checkManifestWindow(issued, expires, "2026-10-24T09:58:59.000Z", 60_000).ok)
        assertTrue(checkManifestWindow(issued, expires, "2026-10-24T12:04:00.000Z").ok)
        assertFalse(checkManifestWindow(issued, expires, "2026-10-24T12:05:00.001Z").ok)
    }

    @Test
    fun `checkManifestWindow 拒绝倒置与非法时间`() {
        assertTrue(
            checkManifestWindow(
                issued = "2026-10-24T12:00:00.000Z",
                expires = "2026-10-24T12:00:00.000Z",
                now = "2026-10-24T11:00:00.000Z",
                skewMs = 0,
            ).reasons.any { it.contains("after issued") },
        )
        assertTrue(
            checkManifestWindow(
                issued = "not-a-date",
                expires = "2026-10-24T12:00:00.000Z",
                now = "2026-10-24T11:00:00.000Z",
                skewMs = 0,
            ).reasons.any { it.contains("issued") },
        )
        assertTrue(
            checkManifestWindow(
                issued = "2026-10-24T10:00:00.000Z",
                expires = "not-a-date",
                now = "2026-10-24T11:00:00.000Z",
                skewMs = 0,
            ).reasons.any { it.contains("expires") },
        )
        assertFalse(
            checkManifestWindow(
                issued = "2026-10-24T10:00:00.000Z",
                expires = "2026-10-24T12:00:00.000Z",
                now = "not-a-date",
                skewMs = 0,
            ).ok,
        )
        assertFalse(
            checkManifestWindow(
                issued = "2026-10-24T10:00:00.000Z",
                expires = "2026-10-24T12:00:00.000Z",
                now = "2026-10-24T11:00:00.000Z",
                skewMs = -1,
            ).ok,
        )
    }

    private fun freshList(
        revoked: List<RevocationEntry> = listOf(
            RevocationEntry("agent#0", "2026-10-24T09:00:00.000Z", "retired"),
        ),
    ): RevocationList = RevocationList(
        issuer = "org.acme",
        issued = "2026-10-24T10:00:00.000Z",
        nextUpdate = "2026-10-24T12:00:00.000Z",
        revoked = revoked,
    )

    private fun localRevocations(): RevocationList = RevocationList(
        issuer = "org.acme",
        issued = "2026-10-24T10:00:00.000Z",
        nextUpdate = "2026-10-24T12:00:00.000Z",
        revoked = listOf(
            RevocationEntry("agent#2", "2026-10-24T09:00:00.000Z", "local"),
            RevocationEntry("agent#1", "2026-10-24T08:00:00.000Z"),
        ),
    )

    private fun remoteRevocations(): RevocationList = RevocationList(
        issuer = "org.acme",
        issued = "2026-10-24T11:00:00.000Z",
        nextUpdate = "2026-10-24T13:00:00.000Z",
        revoked = listOf(
            RevocationEntry("agent#2", "2026-10-24T08:30:00.000Z", "remote"),
            RevocationEntry("agent#3", "2026-10-24T09:30:00.000Z", "remote"),
        ),
    )
}
