package com.eloykim.lyra.amp.protocol.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SocialStoreTest {

    private val since = "2026-10-24T10:00:00Z"
    private val later = "2026-10-24T11:00:00Z"

    @Test
    fun `关系按 from to kind 去重并夹取信任与依恋`() {
        val store = InMemorySocialStore()
        val first = store.upsertRelationship(
            NewRelationship(fromPid = 1, toPid = 2, kind = "colleague", since = since),
        )
        assertEquals("rel.1", first.id)
        assertEquals(0.5, first.trust, 0.0)
        assertEquals(0.5, first.attachment, 0.0)
        assertEquals(emptyList<String>(), first.obligations)

        val updated = store.upsertRelationship(
            NewRelationship(
                fromPid = 1,
                toPid = 2,
                kind = "colleague",
                since = later,
                trust = 1.5,
                attachment = -1.0,
                obligations = listOf("share notes"),
                lastContact = later,
            ),
        )
        assertEquals("rel.1", updated.id)
        assertEquals(1.0, updated.trust, 0.0)
        assertEquals(0.0, updated.attachment, 0.0)
        assertEquals(later, updated.since)
        assertEquals(listOf("share notes"), updated.obligations)

        val secondKind = store.upsertRelationship(
            NewRelationship(fromPid = 1, toPid = 2, kind = "mentor", since = since),
        )
        assertEquals("rel.2", secondKind.id)
        // 关系对两端都可见。
        assertEquals(2, store.relationships(1).size)
        assertEquals(2, store.relationships(2).size)
        assertEquals(0, store.relationships(3).size)
    }

    @Test
    fun `机构创建拒绝重复并保留规范`() {
        val store = InMemorySocialStore()
        val institution = store.createInstitution(
            NewInstitution(id = "inst.ops", name = "Ops", norms = listOf("be on call")),
        )
        assertEquals(listOf("be on call"), institution.norms)
        assertEquals(institution, store.institution("inst.ops"))
        assertNull(store.institution("inst.missing"))

        val error = assertThrows(SocialStoreError::class.java) {
            store.createInstitution(NewInstitution(id = "inst.ops", name = "Ops again"))
        }
        assertEquals(SocialStoreError.INSTITUTION_NOT_FOUND, error.code)
    }

    @Test
    fun `加入与离开机构改变成员状态`() {
        val store = InMemorySocialStore()
        store.createInstitution(NewInstitution(id = "inst.ops", name = "Ops", norms = listOf("n1")))
        val membership = store.joinInstitution(
            JoinInstitutionInput(pid = 1, institutionId = "inst.ops", role = "member", since = since),
        )
        assertEquals(MembershipStatus.ACTIVE, membership.status)
        assertEquals("inst.ops", membership.institution.id)
        assertEquals(1, store.memberships(1).size)

        val left = store.leaveInstitution(1, "inst.ops", later)
        assertEquals(MembershipStatus.LEFT, left.status)
        assertEquals(later, left.since)
        assertEquals(MembershipStatus.LEFT, store.memberships(1).single().status)

        assertEquals(
            SocialStoreError.INSTITUTION_NOT_FOUND,
            assertThrows(SocialStoreError::class.java) {
                store.joinInstitution(
                    JoinInstitutionInput(pid = 2, institutionId = "inst.missing", role = "member", since = since),
                )
            }.code,
        )
        assertEquals(
            SocialStoreError.INSTITUTION_NOT_FOUND,
            assertThrows(SocialStoreError::class.java) {
                store.leaveInstitution(2, "inst.ops", later)
            }.code,
        )
    }

    @Test
    fun `社交事件类型名与参考一致`() {
        assertEquals(
            "social.relationship.updated",
            SocialEvent.RelationshipUpdated("rel.1", since).type,
        )
        assertEquals(
            "social.membership.changed",
            SocialEvent.MembershipChanged(1, "inst.ops", later).type,
        )
        assertNotNull(InMemorySocialStore())
    }
}
