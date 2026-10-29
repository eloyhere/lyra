package com.eloykim.lyra.amp.protocol.registry

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RegistryTest {

    private val at0 = "2026-10-29T00:00:00.000Z"
    private val at1 = "2026-10-29T00:00:01.000Z"

    private fun db(): InMemoryRegistryDatabase = InMemoryRegistryDatabase()

    private fun concept(
        id: String = "c.auth",
        label: String = "Capability auth",
        definition: String = "Every cross-subject access needs a live capability row.",
    ): ConceptInput = ConceptInput(id = id, label = label, definition = definition)

    private fun module(
        id: String = "m.registry",
        kind: ModuleKind = ModuleKind.DOMAIN,
        entry: String = "core/registry",
        description: String = "Concepts and modules as first-class rows.",
        contentDigest: String? = null,
    ): ModuleInput = ModuleInput(
        id = id,
        kind = kind,
        entry = entry,
        description = description,
        contentDigest = contentDigest,
    )

    private fun bodyOf(db: InMemoryRegistryDatabase, objectId: String): JsonValue.Obj {
        val row = db.objects.get(kind = if (objectId.startsWith("concept.")) "concept" else "module", id = objectId)
        assertNotNull(row)
        return JsonParser.parse(row!!.body) as JsonValue.Obj
    }

    private fun declareModulePair(db: InMemoryRegistryDatabase): Pair<ModuleEntity, ModuleEntity> {
        val a = declareModule(db, module(id = "m.a", entry = "a"), at0).row
        val b = declareModule(db, module(id = "m.b", entry = "b"), at0).row
        return a to b
    }

    @Test
    fun `declareConcept versions the row and writes a canonical declaration object`() {
        val db = db()
        val first = declareConcept(db, concept(), at0)

        assertEquals(1, first.row.cid)
        assertEquals(1, first.row.version)
        assertEquals("concept.c.auth.v1", first.objectId)
        assertEquals(first.hash, first.row.digest)
        assertTrue(first.hash.startsWith("sha256:"))
        assertEquals(at0, first.row.createdAt)
        assertEquals(at0, first.row.updatedAt)

        val body = bodyOf(db, first.objectId)
        assertEquals("0.1", body.string("amp"))
        assertEquals("concept", body.string("kind"))
        assertEquals("c.auth", body.string("id"))
        assertEquals("Capability auth", body.string("label"))
        assertEquals("active", body.string("status"))
        assertEquals(1, body.int("version"))
        assertEquals(at0, body.string("at"))

        val second = declareConcept(db, concept(label = "Capability auth v2"), at1)
        assertEquals(2, second.row.version)
        assertEquals(1, second.row.cid)
        assertEquals("concept.c.auth.v2", second.objectId)
        assertEquals(at0, second.row.createdAt)
        assertEquals(at1, second.row.updatedAt)
        assertEquals("Capability auth v2", db.concept("c.auth")?.label)
    }

    @Test
    fun `declareConcept rejects malformed input before writing`() {
        val db = db()
        val badId = assertThrows(IllegalArgumentException::class.java) {
            declareConcept(db, concept(id = "Bad.Id"), at0)
        }
        assertEquals("concept id Bad.Id is not a valid AMP id", badId.message)

        val emptyLabel = assertThrows(IllegalArgumentException::class.java) {
            declareConcept(db, concept(label = "   "), at0)
        }
        assertEquals("label must not be empty", emptyLabel.message)

        val emptyDefinition = assertThrows(IllegalArgumentException::class.java) {
            declareConcept(db, concept(definition = ""), at0)
        }
        assertEquals("definition must not be empty", emptyDefinition.message)
        assertEquals(0, db.chain.entries().size)
    }

    @Test
    fun `declareModule keeps an explicit null content digest in the body`() {
        val db = db()
        val first = declareModule(
            db,
            module(kind = ModuleKind.KERNEL, entry = "core/amp-protocol"),
            at0,
        )

        assertEquals(1, first.row.mid)
        assertEquals(ModuleKind.KERNEL, first.row.kind)
        assertNull(first.row.contentDigest)
        val body = bodyOf(db, first.objectId)
        assertEquals("kernel", body.string("moduleKind"))
        assertEquals("core/amp-protocol", body.string("entry"))
        assertEquals(JsonValue.Null, body["contentDigest"])

        val digest = "sha256:" + "a".repeat(64)
        val second = declareModule(
            db,
            module(entry = "core/amp-protocol", contentDigest = digest),
            at1,
        )
        assertEquals(2, second.row.version)
        assertEquals(1, second.row.mid)
        assertEquals(digest, second.row.contentDigest)
        assertEquals(
            JsonValue.Str(digest),
            bodyOf(db, second.objectId)["contentDigest"],
        )
    }

    @Test
    fun `declareModule rejects invalid ids and empty text fields`() {
        val db = db()
        assertEquals(
            "module id m.Bad is not a valid AMP id",
            assertThrows(IllegalArgumentException::class.java) {
                declareModule(db, module(id = "m.Bad"), at0)
            }.message,
        )
        assertEquals(
            "entry must not be empty",
            assertThrows(IllegalArgumentException::class.java) {
                declareModule(db, module(entry = " "), at0)
            }.message,
        )
        assertEquals(
            "description must not be empty",
            assertThrows(IllegalArgumentException::class.java) {
                declareModule(db, module(description = ""), at0)
            }.message,
        )
    }

    @Test
    fun `linkConcept is idempotent per role and rejects unknown rows`() {
        val db = db()
        declareConcept(db, concept(), at0)
        declareModule(db, module(id = "m.core", entry = "core"), at0)

        assertTrue(linkConcept(db, "m.core", "c.auth", ConceptLinkRole.OWNS))
        assertFalse(linkConcept(db, "m.core", "c.auth", ConceptLinkRole.OWNS))
        assertTrue(linkConcept(db, "m.core", "c.auth", ConceptLinkRole.USES))
        assertEquals(2, db.moduleConcepts().size)

        assertEquals(
            "unknown module m.missing",
            assertThrows(IllegalArgumentException::class.java) {
                linkConcept(db, "m.missing", "c.auth", ConceptLinkRole.OWNS)
            }.message,
        )
        assertEquals(
            "unknown concept c.missing",
            assertThrows(IllegalArgumentException::class.java) {
                linkConcept(db, "m.core", "c.missing", ConceptLinkRole.OWNS)
            }.message,
        )
    }

    @Test
    fun `dependencies are sorted by target and duplicate edges are ignored`() {
        val db = db()
        declareModule(db, module(id = "m.a", entry = "a"), at0)
        declareModule(db, module(id = "m.b", entry = "b"), at0)
        declareModule(db, module(id = "m.c", entry = "c"), at0)

        assertTrue(linkDependency(db, "m.a", "m.c", "later"))
        assertTrue(linkDependency(db, "m.a", "m.b", "earlier"))
        assertFalse(linkDependency(db, "m.a", "m.b", "duplicate"))

        val rows = dependencies(db, "m.a")
        assertEquals(listOf("m.b", "m.c"), rows.map { it.dependsOn })
        assertEquals(listOf("earlier", "later"), rows.map { it.reason })
        assertTrue(dependencies(db, "m.unknown").isEmpty())
    }

    @Test
    fun `linkDependency rejects self edges and rolls back a cycle`() {
        val db = db()
        val (a, b) = declareModulePair(db)

        assertEquals(
            "module m.a cannot depend on itself",
            assertThrows(IllegalArgumentException::class.java) {
                linkDependency(db, "m.a", "m.a")
            }.message,
        )

        assertTrue(linkDependency(db, "m.a", "m.b", "uses"))
        assertFalse(hasDependencyCycle(db))
        val cycle = assertThrows(IllegalArgumentException::class.java) {
            linkDependency(db, "m.b", "m.a", "back edge")
        }
        assertEquals(
            "linking m.b -> m.a would create a dependency cycle",
            cycle.message,
        )
        assertTrue(dependencies(db, "m.b").isEmpty())
        assertFalse(hasDependencyCycle(db))
        assertEquals(listOf("m.b"), dependencies(db, "m.a").map { it.dependsOn })
        assertEquals(a.mid, db.module("m.a")?.mid)
        assertEquals(b.mid, db.module("m.b")?.mid)
    }

    @Test
    fun `hasDependencyCycle detects direct cycles in rows inserted without the guard`() {
        val db = db()
        val (a, b) = declareModulePair(db)
        assertTrue(db.insertModuleDependency(a.mid, b.mid, ""))
        assertTrue(db.insertModuleDependency(b.mid, a.mid, ""))
        assertTrue(hasDependencyCycle(db))
        assertTrue(
            verifyRegistry(db).reasons.contains("module dependency graph contains a cycle"),
        )
    }

    @Test
    fun `verifyModuleContent reuses digestDirectory and reports drift`() {
        val dir = Files.createTempDirectory("lyra-registry-content").toFile()
        try {
            File(dir, "a.txt").writeText("alpha", Charsets.UTF_8)
            File(dir, "sub").mkdirs()
            File(dir, "sub/b.txt").writeText("beta", Charsets.UTF_8)
            val digest = digestDirectory(dir)

            val db = db()
            declareModule(
                db,
                module(id = "m.core", entry = dir.absolutePath, contentDigest = digest),
                at0,
            )

            val healthy = verifyModuleContent(db) { path -> digestDirectory(File(path)) }
            assertTrue(healthy.ok)
            assertTrue(healthy.reasons.isEmpty())

            File(dir, "a.txt").writeText("alpha changed", Charsets.UTF_8)
            val drifted = verifyModuleContent(db) { path -> digestDirectory(File(path)) }
            assertFalse(drifted.ok)
            assertEquals(
                listOf("module m.core: content changed since declaration"),
                drifted.reasons,
            )

            val nullDigest = db()
            declareModule(nullDigest, module(id = "m.unbound", entry = dir.absolutePath), at0)
            assertTrue(
                verifyModuleContent(nullDigest) { error("must not compute an unbound module") }.ok,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `attest writes one evidence object per subject attestation`() {
        val db = db()
        declareModule(db, module(id = "m.core", entry = "core"), at0)
        db.chain.put(
            "claim",
            "cl.one",
            jsonObj("amp" to jsonText("0.1"), "id" to jsonText("cl.one")),
            at0,
        )

        val first = attest(db, RegistrySubjectKind.MODULE, "m.core", "cl.one", at0)
        assertEquals(1, first.aid)
        assertEquals("ev.registry.module.m.core.1", first.objectId)
        assertEquals(first.hash, db.objects.get("evidence", first.objectId)?.hash)

        val body = JsonParser.parse(db.objects.get("evidence", first.objectId)!!.body) as JsonValue.Obj
        assertEquals("evidence", body.string("kind"))
        assertEquals("cl.one", body.string("claim"))
        assertEquals("module", body.obj("subject")?.string("kind"))
        assertEquals("m.core", body.obj("subject")?.string("id"))
        assertEquals(
            "registry attestation for module m.core",
            body.string("note"),
        )

        val second = attest(db, RegistrySubjectKind.MODULE, "m.core", "cl.one", at1)
        assertEquals(2, second.aid)
        assertEquals("ev.registry.module.m.core.2", second.objectId)
        assertEquals(2, db.countAttestations(RegistrySubjectKind.MODULE, "m.core"))
        assertTrue(verifyRegistry(db).ok)
    }

    @Test
    fun `attest rejects unknown subjects and claims`() {
        val db = db()
        declareConcept(db, concept(), at0)
        assertEquals(
            "unknown concept c.missing",
            assertThrows(IllegalArgumentException::class.java) {
                attest(db, RegistrySubjectKind.CONCEPT, "c.missing", "cl.one", at0)
            }.message,
        )
        assertEquals(
            "unknown claim cl.missing",
            assertThrows(IllegalArgumentException::class.java) {
                attest(db, RegistrySubjectKind.CONCEPT, "c.auth", "cl.missing", at0)
            }.message,
        )
    }

    @Test
    fun `verifyRegistry reports a healthy registry and a broken object chain`() {
        val db = db()
        declareConcept(db, concept(), at0)
        declareModule(db, module(id = "m.core", entry = "core"), at0)
        assertTrue(verifyRegistry(db).ok)
        assertTrue(db.chain.verify().ok)

        db.chain.entries().first().body =
            """{"amp":"0.1","kind":"concept","id":"c.auth","label":"A","definition":"D","status":"active","version":9,"at":"$at0"}"""

        val report = verifyRegistry(db)
        assertFalse(report.ok)
        assertTrue(report.reasons.any { it.contains("hash does not cover its body") })
        assertTrue(report.reasons.any { it.contains("object version 9 != row 1") })
    }

    @Test
    fun `verifyRegistry reports a missing declaration object`() {
        val db = db()
        declareConcept(db, concept(), at0)
        val row = db.concept("c.auth")!!
        db.saveConcept(row.copy(objectSeq = null))

        val report = verifyRegistry(db)
        assertFalse(report.ok)
        assertEquals(
            listOf("concept c.auth: no declaration object"),
            report.reasons,
        )
    }

    @Test
    fun `verifyRegistry reports dangling links, cycles and missing attestation evidence`() {
        val db = db()
        val (a, b) = declareModulePair(db)
        assertTrue(db.insertModuleDependency(a.mid, b.mid, ""))
        assertTrue(db.insertModuleDependency(b.mid, a.mid, ""))
        assertTrue(db.insertModuleDependency(999, a.mid, "dangling"))

        assertTrue(db.insertModuleConcept(999, 999, ConceptLinkRole.OWNS))
        val aid = db.insertAttestation(
            subjectKind = RegistrySubjectKind.MODULE,
            subjectId = "m.a",
            claimId = "cl.missing",
            objectSeq = null,
            at = at0,
        )
        assertEquals(1, aid)

        val report = verifyRegistry(db)
        assertFalse(report.ok)
        assertTrue(
            report.reasons.contains("1 module_concepts link(s) point at missing rows"),
        )
        assertTrue(
            report.reasons.contains("1 module_dependencies link(s) point at missing rows"),
        )
        assertTrue(report.reasons.contains("module dependency graph contains a cycle"))
        assertTrue(
            report.reasons.contains("attestation 1: evidence object is missing"),
        )
        assertTrue(
            report.reasons.contains("attestation 1: claim cl.missing is missing"),
        )
    }
}
