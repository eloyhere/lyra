package com.eloykim.lyra.amp.protocol.concept

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.schema.AmpSchemas
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concept / concept-budget / prune-plan / checkpoint 的线格式与 schema。
 */
class ConceptTest {

    @Test
    fun `完整 concept 通过 schema 且可原样往返`() {
        val concept = fullConcept()
        val json = concept.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validate("concept.schema.json", json))
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(Concept.fromJson(json).toJson()))
        assertEquals(concept, Concept.fromJson(json))

        assertEquals("0.1", json.string("amp"))
        assertEquals("concept", json.string("kind"))
        assertEquals(ConceptStatus.ACTIVE, concept.status)
        assertEquals(ConceptRelationType.CAUSES, concept.relations?.first()?.type)
        assertEquals(ConceptScale.ORDINAL, concept.dimensions?.first()?.scale)
        assertEquals(ConceptRevisionOp.REFINE, concept.revision?.op)
    }

    @Test
    fun `最小 concept 省略可选字段而不是写 null`() {
        val concept = Concept(
            id = "c.minimal",
            label = "minimal",
            definition = "a concept with only the required fields",
            intension = emptyList(),
            status = ConceptStatus.PROPOSED,
            confidence = 0.4,
            provenance = Provenance(source = "test"),
            version = 1,
            created = AT,
        )
        val json = concept.toJson()
        assertEquals(emptyList<String>(), AmpSchemas.validate("concept.schema.json", json))
        listOf(
            "dimensions", "theory_refs", "extension", "prototype", "examples",
            "counterexamples", "relations", "grounded_in", "revision",
        ).forEach { key -> assertFalse("缺省字段 $key 不应出现", json.has(key)) }
        assertEquals(concept, Concept.fromJson(json))
    }

    @Test
    fun `concept-checkpoint 与 concept-prune-plan 示例通过 schema 且可往返`() {
        val checkpointJson = example("concept-checkpoint.json")
        assertEquals(emptyList<String>(), AmpSchemas.validate("concept-compaction.schema.json", checkpointJson))
        val checkpoint = ConceptCheckpoint.fromJson(checkpointJson)
        assertEquals("cp.concept.c.deploy-drift.2", checkpoint.id)
        assertEquals("c.deploy-drift", checkpoint.concept)
        assertEquals(1, checkpoint.from)
        assertEquals(2, checkpoint.to)
        assertEquals(2, checkpoint.count)
        assertEquals(1, checkpoint.budget.keepVersions)
        assertEquals(
            CanonicalJson.encode(checkpointJson),
            CanonicalJson.encode(checkpoint.toJson()),
        )

        val planJson = example("concept-prune-plan.json")
        assertEquals(emptyList<String>(), AmpSchemas.validate("concept-compaction.schema.json", planJson))
        val plan = ConceptPrunePlan.fromJson(planJson)
        assertEquals("c.deploy-drift", plan.concept)
        assertEquals(3, plan.version)
        assertEquals(4, plan.dropped.size)
        assertEquals(265, plan.reclaimedBytes)
        assertEquals(3, plan.budget.maxExamples)
        assertEquals(2, plan.budget.maxRelations)
        assertEquals(
            listOf(
                ConceptDropReason.DUPLICATE,
                ConceptDropReason.OVER_BUDGET,
                ConceptDropReason.DUPLICATE,
                ConceptDropReason.DUPLICATE,
            ),
            plan.dropped.map { it.reason },
        )
        assertEquals(
            listOf(ConceptDropSlot.EXAMPLE, ConceptDropSlot.EXAMPLE, ConceptDropSlot.RELATION, ConceptDropSlot.EXTENSION),
            plan.dropped.map { it.slot },
        )
        assertEquals(CanonicalJson.encode(planJson), CanonicalJson.encode(plan.toJson()))
    }

    @Test
    fun `concept budget 的 JSON 形状固定`() {
        val budget = DefaultConceptBudget
        val json = budget.toJson()
        assertEquals("0.1", json.string("amp"))
        assertEquals("concept-budget", json.string("kind"))
        assertEquals(8, json.int("maxExamples"))
        assertEquals(1, json.int("minExamples"))
        assertEquals(12, json.int("maxRelations"))
        assertEquals(1, json.int("keepVersions"))
        assertEquals(emptyList<String>(), AmpSchemas.validate("concept-compaction.schema.json", prunePlanOf(budget)))
        assertEquals(budget, ConceptBudget.fromJson(json))
    }

    @Test
    fun `信封与 kind 不匹配被拒绝`() {
        val concept = fullConcept().toJson()
        assertThrows(IllegalArgumentException::class.java) {
            Concept.fromJson(concept.copyWith("kind", JsonValue.Str("concept-view")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Concept.fromJson(concept.copyWith("amp", JsonValue.Str("0.2")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConceptBudget.fromJson(DefaultConceptBudget.toJson().copyWith("kind", JsonValue.Str("budget")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConceptPrunePlan.fromJson(example("concept-prune-plan.json").copyWith("kind", JsonValue.Str("prune-plan")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConceptCheckpoint.fromJson(example("concept-checkpoint.json").copyWith("kind", JsonValue.Str("checkpoint")))
        }
    }

    @Test
    fun `缺省必需字段被拒绝`() {
        val json = fullConcept().toJson().without("intension")
        assertThrows(IllegalArgumentException::class.java) { Concept.fromJson(json) }
    }

    private fun prunePlanOf(budget: ConceptBudget): JsonValue = ConceptPrunePlan(
        concept = "c.deploy-drift",
        version = 1,
        dropped = emptyList(),
        reclaimedBytes = 0,
        budget = budget,
    ).toJson()

    private fun fullConcept(): Concept = Concept(
        id = "c.deploy-drift",
        label = "deploy drift",
        definition = "configuration diverges from the declared state",
        intension = listOf(ConceptFeature("declared differs from observed", 0.9)),
        status = ConceptStatus.ACTIVE,
        confidence = 0.7,
        provenance = Provenance(source = "test", trust = com.eloykim.lyra.amp.protocol.types.Trust.SELF),
        version = 3,
        created = AT,
        dimensions = listOf(ConceptDimension("severity", ConceptScale.ORDINAL)),
        theoryRefs = listOf("theory.config-drift"),
        extension = listOf(Ref(RefKind.CLAIM, "cl.1")),
        prototype = "helm values edited by hand",
        examples = listOf(
            ConceptExample(description = "a config map edited by hand", typicality = 0.8),
            ConceptExample(ref = Ref(RefKind.EVENT, "e.1"), description = "a cron schedule changed outside the pipeline"),
        ),
        counterexamples = listOf(
            ConceptExample(description = "a declared change applied exactly as written"),
        ),
        relations = listOf(ConceptRelation(ConceptRelationType.CAUSES, Ref(RefKind.CLAIM, "cl.1"))),
        groundedIn = listOf(Ref(RefKind.EVENT, "e.1")),
        revision = ConceptRevision(
            op = ConceptRevisionOp.REFINE,
            at = AT,
            from = listOf(Ref(RefKind.CONCEPT, "c.old-drift")),
            reason = "narrowed to configuration state",
        ),
    )

    private fun example(name: String): JsonValue.Obj {
        val text = javaClass.getResource("/amp/examples/$name")?.readText()
            ?: error("缺少测试资源 $name")
        return JsonParser.parse(text) as JsonValue.Obj
    }

    private companion object {
        const val AT: String = "2026-10-24T01:00:00Z"
    }
}
