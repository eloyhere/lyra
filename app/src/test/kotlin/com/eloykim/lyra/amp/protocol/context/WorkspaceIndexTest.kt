package com.eloykim.lyra.amp.protocol.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceIndexTest {

    @Test
    fun summaryProvenanceTracksSourceRevisions() {
        val entities = listOf(
            WorkspaceEntity(
                id = "project.alpha",
                kind = WorkspaceEntityKind.PROJECT,
                projectId = "project.alpha",
                label = "Alpha",
                summary = "Alpha",
                source = source("doc://a", "r1"),
            ),
            WorkspaceEntity(
                id = "dir.docs",
                kind = WorkspaceEntityKind.DIRECTORY,
                projectId = "project.alpha",
                parentId = "project.alpha",
                label = "docs",
                summary = "Docs",
                source = source("doc://a", "r1"),
                summaryProvenance = SummaryProvenance(
                    sources = listOf(DocumentSourceRevision("doc://a", "r1")),
                    children = listOf("file.child"),
                ),
            ),
            WorkspaceEntity(
                id = "file.child",
                kind = WorkspaceEntityKind.FILE,
                projectId = "project.alpha",
                parentId = "dir.docs",
                label = "child.adoc",
                summary = "Child",
                source = source("doc://a", "r1"),
                summaryProvenance = SummaryProvenance(
                    sources = listOf(DocumentSourceRevision("doc://a", "r1")),
                    children = emptyList(),
                ),
            ),
        )
        val index = WorkspaceIndex(
            WorkspaceSnapshot("rev.1", entities, emptyList(), emptyList()),
            MemoryContentStore(),
        )
        assertEquals(SummaryState.UNTRACKED, index.summaryState("project.alpha"))
        assertEquals(SummaryState.FRESH, index.summaryState("file.child"))
        assertEquals(SummaryState.FRESH, index.summaryState("dir.docs"))
        // 显式传入的当前源 revision 不写入缓存；任何变化都会让依赖它的摘要失效。
        assertEquals(
            SummaryState.STALE,
            index.summaryState("dir.docs", listOf(DocumentSourceRevision("doc://a", "r2"))),
        )
        assertEquals(SummaryState.FRESH, index.summaryState("dir.docs"))
    }

    @Test
    fun projectionCarriesNativeLocationsAndKeepsRelationsDescriptive() {
        val fixture = workspaceFixture()
        val index = WorkspaceIndex(fixture.snapshot, fixture.store)
        val ast = index.project()

        val file = ast.node("file.main")
        assertNotNull(file)
        assertEquals(ContextNodeKind.FILE, file!!.kind)
        assertEquals("dir.src", file.parentId)
        assertTrue(file.summary.contains("source=\"file:///alpha/src/main.ts:1\""))
        assertTrue(file.summary.contains("revision=\"r1\""))
        assertEquals(1.0, file.salience!!, 0.0)
        assertEquals(0.0, ast.node("project.alpha")!!.salience!!, 0.0)
        assertNotNull(file.detailRef)

        // imports 成环是合法的描述性关系；只有 before/depends 会约束顺序。
        assertEquals(4, ast.order().size)
        assertEquals(1, ast.relations("file.main", EdgeDirection.OUTGOING, ContextEdgeKind.IMPORTS).size)
        assertEquals(1, ast.relations("file.main", EdgeDirection.INCOMING, ContextEdgeKind.IMPORTS).size)
        assertEquals(listOf("project.alpha", "dir.src"), ast.ancestors("file.main").map { it.id })
        assertEquals(listOf("file.main", "file.util"), ast.children("dir.src").map { it.id })
    }

    @Test
    fun queryPerformsLexicalAndBoundedTraversalWithinProjectScope() {
        val fixture = workspaceFixture()
        val index = WorkspaceIndex(fixture.snapshot, fixture.store)

        val result = index.query(WorkspaceQuery(text = "entry point"))
        assertEquals(listOf("file.main", "file.util"), result.candidates.map { it.id })
        assertEquals(CandidateWhy.Text, result.candidates.first().why)
        assertEquals(CandidateWhy.Relation(WorkspaceRelationKind.IMPORTS), result.candidates[1].why)
        assertEquals(1, result.candidates[1].hops)
        assertTrue(result.unknownIds.isEmpty())

        val bounded = index.query(WorkspaceQuery(text = "entry point", limit = 1))
        assertEquals(listOf("file.main"), bounded.candidates.map { it.id })
        assertTrue(bounded.truncated)

        val unknown = index.query(WorkspaceQuery(seedIds = listOf("file.gone", "file.main")))
        assertEquals(listOf("file.gone"), unknown.unknownIds)
        assertEquals(listOf("file.main", "file.util"), unknown.candidates.map { it.id })

        val outside = index.query(WorkspaceQuery(seedIds = listOf("file.main"), projectIds = listOf("project.other")))
        assertTrue(outside.candidates.isEmpty())
    }

    @Test
    fun rejectsUnversionedInvalidAndCyclicSnapshots() {
        val fixture = workspaceFixture()
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIndex(
                fixture.snapshot.copy(version = 2),
                fixture.store,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIndex(
                fixture.snapshot.copy(entities = fixture.snapshot.entities + fixture.snapshot.entities.first()),
                fixture.store,
            )
        }
        val orphan = WorkspaceEntity(
            id = "file.orphan",
            kind = WorkspaceEntityKind.FILE,
            projectId = "project.alpha",
            label = "orphan",
            summary = "no parent",
            source = source("file:///alpha/orphan", "r1"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIndex(fixture.snapshot.copy(entities = fixture.snapshot.entities + orphan), fixture.store)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIndex(
                fixture.snapshot.copy(
                    relations = fixture.snapshot.relations + WorkspaceRelation(
                        from = "file.main",
                        to = "file.gone",
                        kind = WorkspaceRelationKind.CALLS,
                        source = source("file:///alpha/src/main.ts", "r1"),
                        method = WorkspaceExtractionMethod.EXPLICIT,
                    ),
                ),
                fixture.store,
            )
        }

        val cycleA = WorkspaceEntity(
            id = "file.a",
            kind = WorkspaceEntityKind.FILE,
            projectId = "project.alpha",
            parentId = "file.b",
            label = "a",
            summary = "a",
            source = source("file:///alpha/a", "r1"),
        )
        val cycleB = WorkspaceEntity(
            id = "file.b",
            kind = WorkspaceEntityKind.FILE,
            projectId = "project.alpha",
            parentId = "file.a",
            label = "b",
            summary = "b",
            source = source("file:///alpha/b", "r1"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceIndex(
                fixture.snapshot.copy(entities = fixture.snapshot.entities + listOf(cycleA, cycleB)),
                fixture.store,
            )
        }
    }

    @Test
    fun termsSplitCamelCaseAndDeduplicate() {
        assertEquals(
            listOf("src", "main", "file", "handler", "v2"),
            workspaceTerms("srcMain FileHandler v2 srcMain"),
        )
        assertTrue(workspaceTerms("").isEmpty())
        assertFalse(workspaceTerms("A").isEmpty())
    }
}
