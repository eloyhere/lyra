package com.eloykim.lyra.amp.protocol.authz

import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * `src/authz/authz.ts` 的行为锁定：所有权、能力表、放大拒绝、线程继承、
 * 递归吊销与对象链事件。
 */
class AuthzTest {

    private val at: String = "2026-10-24T10:00:00Z"

    private fun secondsLater(seconds: Long): String =
        Instant.parse(at).plusSeconds(seconds).toString()

    private val host = Subject(SubjectKind.HOST, "host.1")
    private val owner = Subject(SubjectKind.PROCESS, "p.1", pid = 1)
    private val object1 = Resource(ResourceKind.OBJECT, "obj.1", ownerPid = 1)
    private val object2 = Resource(ResourceKind.OBJECT, "obj.2")

    private fun process(id: String, pid: Int? = null): Subject =
        Subject(SubjectKind.PROCESS, id, pid = pid)

    private fun thread(id: String, pid: Int? = null): Subject =
        Subject(SubjectKind.THREAD, id, pid = pid)

    @Test
    fun `host 与所有者拥有全部权利`() {
        val db = InMemoryCapabilityDatabase()
        assertEquals(AllActions, effectiveRights(db, host, object1, at))
        assertEquals(AllActions, effectiveRights(db, owner, object1, at))
        assertTrue(can(db, owner, PermissionAction.KILL, object1, at))

        val foreign = process("p.2", pid = 2)
        assertTrue(effectiveRights(db, foreign, object1, at).isEmpty())
        assertFalse(can(db, foreign, PermissionAction.READ, object1, at))
    }

    @Test
    fun `grant 只授予可用权利并记录默认 scope`() {
        val db = InMemoryCapabilityDatabase()
        val grantee = process("p.2")
        val record = grant(
            db,
            GrantInput(
                grantor = host,
                grantee = grantee,
                resource = object1,
                rights = listOf(PermissionAction.READ, PermissionAction.WRITE),
            ),
            at,
        )
        assertEquals(1L, record.hid)
        assertEquals("h.1", record.id)
        assertEquals(CapabilityScope.PROCESS, record.scope)
        assertEquals("host.1", record.grantedBy)
        assertNull(record.parentHid)
        assertEquals(at, record.createdAt)
        assertEquals(
            listOf(PermissionAction.READ, PermissionAction.WRITE),
            effectiveRights(db, grantee, object1, at),
        )
        assertTrue(can(db, grantee, PermissionAction.WRITE, object1, at))
        assertFalse(can(db, grantee, PermissionAction.READ, object2, at))
        assertEquals(listOf(record), listCapabilities(db, grantee))
    }

    @Test
    fun `拒绝向 host 授予、空权利与权利放大`() {
        val db = InMemoryCapabilityDatabase()
        expectAuthz(AuthzCodes.INVALID_GRANT) {
            grant(
                db,
                GrantInput(host, host, object1, listOf(PermissionAction.READ)),
                at,
            )
        }
        expectAuthz(AuthzCodes.INVALID_GRANT) {
            grant(db, GrantInput(host, process("p.2"), object1, emptyList()), at)
        }

        val delegate = process("p.2")
        grant(
            db,
            GrantInput(host, delegate, object1, listOf(PermissionAction.READ, PermissionAction.GRANT)),
            at,
        )
        expectAuthz(AuthzCodes.RIGHTS_AMPLIFICATION) {
            grant(db, GrantInput(delegate, process("p.3"), object1, listOf(PermissionAction.WRITE)), at)
        }
        expectAuthz(AuthzCodes.PERMISSION_DENIED) {
            grant(db, GrantInput(process("p.9"), process("p.3"), object1, listOf(PermissionAction.READ)), at)
        }
    }

    @Test
    fun `到期不能超过授予者能力`() {
        val db = InMemoryCapabilityDatabase()
        val delegate = process("p.2")
        grant(
            db,
            GrantInput(
                grantor = host,
                grantee = delegate,
                resource = object1,
                rights = listOf(PermissionAction.READ, PermissionAction.GRANT),
                expiresAt = secondsLater(3600),
            ),
            at,
        )

        expectAuthz(AuthzCodes.EXPIRY_AMPLIFICATION) {
            grant(
                db,
                GrantInput(
                    grantor = delegate,
                    grantee = process("p.3"),
                    resource = object1,
                    rights = listOf(PermissionAction.READ),
                    expiresAt = secondsLater(7200),
                ),
                at,
            )
        }
        expectAuthz(AuthzCodes.EXPIRY_AMPLIFICATION) {
            grant(
                db,
                GrantInput(
                    grantor = delegate,
                    grantee = process("p.3"),
                    resource = object1,
                    rights = listOf(PermissionAction.READ),
                    expiresAt = null,
                ),
                at,
            )
        }

        val child = grant(
            db,
            GrantInput(
                grantor = delegate,
                grantee = process("p.3"),
                resource = object1,
                rights = listOf(PermissionAction.READ),
                expiresAt = secondsLater(1800),
            ),
            at,
        )
        assertEquals(1L, child.parentHid)
        assertEquals(secondsLater(1800), child.expiresAt)
        assertTrue(can(db, process("p.3"), PermissionAction.READ, object1, at))
        assertFalse(can(db, process("p.3"), PermissionAction.READ, object1, secondsLater(1801)))
    }

    @Test
    fun `线程继承所属进程的能力`() {
        val db = InMemoryCapabilityDatabase()
        val worker = process("p.worker", pid = 10)
        grant(db, GrantInput(host, worker, object2, listOf(PermissionAction.READ)), at)

        val child = thread("t.10", pid = 10)
        assertTrue(can(db, child, PermissionAction.READ, object2, at))
        assertFalse(can(db, thread("t.11", pid = 11), PermissionAction.READ, object2, at))

        val direct = grant(
            db,
            GrantInput(host, thread("t.10", pid = 10), object2, listOf(PermissionAction.APPEND)),
            at,
        )
        assertEquals(CapabilityScope.THREAD, direct.scope)
        assertTrue(can(db, child, PermissionAction.APPEND, object2, at))
    }

    @Test
    fun `所有者也按主体 pid 判定`() {
        val db = InMemoryCapabilityDatabase()
        val owned = Resource(ResourceKind.OBJECT, "obj.3", ownerPid = 10)
        assertEquals(AllActions, effectiveRights(db, thread("t.10", pid = 10), owned, at))
        assertTrue(effectiveRights(db, thread("t.11", pid = 11), owned, at).isEmpty())
    }

    @Test
    fun `递归吊销返回实际变更行数并追加链事件`() {
        val db = InMemoryCapabilityDatabase()
        val delegate = process("p.2")
        val parent = grant(
            db,
            GrantInput(
                host,
                delegate,
                object1,
                listOf(PermissionAction.READ, PermissionAction.GRANT),
            ),
            at,
        )
        val child = grant(
            db,
            GrantInput(delegate, process("p.3"), object1, listOf(PermissionAction.READ)),
            at,
        )
        assertEquals(parent.hid, child.parentHid)

        val revoked = revoke(db, host, parent.hid, secondsLater(60))
        assertEquals(2, revoked)
        assertFalse(can(db, delegate, PermissionAction.READ, object1, secondsLater(60)))
        assertFalse(can(db, process("p.3"), PermissionAction.READ, object1, secondsLater(60)))
        assertNotNull(db.row(child.hid)!!.revokedAt)
        assertEquals(0, revoke(db, host, parent.hid, secondsLater(61)))

        val events = db.events()
        assertEquals(3, events.size)
        assertEquals("ev.authz.grant.1", events[0].id)
        assertEquals("ev.authz.grant.2", events[1].id)
        assertEquals("ev.authz.revoke.1.2", events[2].id)
        assertEquals(AUTHZ_GENESIS, events[0].prev)
        assertEquals(events[0].hash, events[1].prev)
        assertEquals(events[1].hash, events[2].prev)
        events.forEach { entry ->
            val expected = CanonicalJson.digest(
                jsonObj(
                    "prev" to jsonText(entry.prev),
                    "kind" to jsonText(entry.kind),
                    "id" to jsonText(entry.id),
                    "body" to jsonText(entry.body),
                ),
            )
            assertEquals(expected, entry.hash)
        }

        val grantBody = events[0].body
        assertTrue(grantBody.contains("\"capability\":\"h.1\""))
        assertTrue(grantBody.contains("\"grantee\":\"p.2\""))
        assertTrue(grantBody.contains("\"rights\":[\"read\",\"grant\"]"))
        assertTrue(events[0].body.contains("\"kind\":\"self\""))
        assertTrue(events[1].body.contains("\"kind\":\"agent\""))
        assertTrue(events[2].body.contains("\"revoked\":2"))
    }

    @Test
    fun `未知能力与非授权吊销被拒绝`() {
        val db = InMemoryCapabilityDatabase()
        expectAuthz(AuthzCodes.UNKNOWN_CAPABILITY) { revoke(db, host, 99L, at) }

        val record = grant(db, GrantInput(host, process("p.2"), object1, listOf(PermissionAction.READ)), at)
        expectAuthz(AuthzCodes.PERMISSION_DENIED) {
            revoke(db, process("p.2"), record.hid, at)
        }
    }

    @Test
    fun `能力记录 JSON 往返`() {
        val db = InMemoryCapabilityDatabase()
        val record = grant(
            db,
            GrantInput(
                grantor = host,
                grantee = thread("t.2", pid = 2),
                resource = Resource(ResourceKind.MAILBOX, "mbox.1", ownerPid = 1),
                rights = listOf(PermissionAction.SEND, PermissionAction.RECEIVE),
                expiresAt = secondsLater(60),
            ),
            at,
        )
        assertEquals(record, CapabilityRecord.fromJson(record.toJson()))
        assertEquals(host, Subject.fromJson(host.toJson()))
        assertEquals(object1, Resource.fromJson(object1.toJson()))
        assertEquals(
            SubjectKind.THREAD,
            Subject.fromJson(record.subject.toJson()).kind,
        )
    }

    private fun expectAuthz(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected AuthzError $code")
        } catch (error: AuthzError) {
            assertEquals(code, error.code)
        }
    }
}
