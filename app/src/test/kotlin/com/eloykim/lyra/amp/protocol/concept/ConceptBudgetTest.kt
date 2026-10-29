package com.eloykim.lyra.amp.protocol.concept

import com.eloykim.lyra.amp.protocol.types.Provenance
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concept economy：内容裁剪、接地保底、关系/扩展去重、谱系压缩与检查点校验。
 *
 * 不变量 C-1..C-4 见 [ConceptBudget] 的 KDoc。
 */
class ConceptBudgetTest {

    @Test
    fun `预算内的概念原样返回，视图不 bump version`() {
        val original = concept()
        val before = original.toJson()
        val result = pruneConcept(original)
        assertTrue(result.plan.dropped.isEmpty())
        assertEquals(0, result.plan.reclaimedBytes)
        assertEquals(original, result.concept)
        assertEquals(before, original.toJson())
        assertEquals(original.version, result.concept.version) // C-1
        assertEquals("c.deploy-drift", result.plan.concept)
        assertEquals(1, result.plan.version)
        assertEquals(DefaultConceptBudget, result.plan.budget)
    }

    @Test
    fun `重复例子折叠，更典型的那个留下`() {
        val original = concept(
            examples = listOf(
                ConceptExample(description = "config map edited by hand", typicality = 0.3),
                ConceptExample(description = "Config  map edited by hand", typicality = 0.9),
            ),
        )
        val result = pruneConcept(original)
        assertEquals(
            listOf("Config  map edited by hand"),
            result.concept.examples?.map { it.description },
        )
        assertEquals(1, result.plan.dropped.size)
        assertEquals(ConceptDropReason.DUPLICATE, result.plan.dropped[0].reason)
        assertEquals(ConceptDropSlot.EXAMPLE, result.plan.dropped[0].slot)
        assertEquals(0, result.plan.dropped[0].index)
        assertTrue(result.plan.reclaimedBytes > 0)
    }

    @Test
    fun `超出上限的例子从最不典型的一端裁掉`() {
        val original = concept(
            examples = listOf(
                ConceptExample(description = "a", typicality = 0.9),
                ConceptExample(description = "b", typicality = 0.2),
                ConceptExample(description = "c", typicality = 0.7),
            ),
        )
        val result = pruneConcept(original, ConceptBudgetOverrides(maxExamples = 2))
        assertEquals(listOf("a", "c"), result.concept.examples?.map { it.description })
        assertEquals(listOf(ConceptDropReason.OVER_BUDGET), result.plan.dropped.map { it.reason })
        assertEquals("b", result.plan.dropped[0].summary)
        assertEquals(1, result.plan.dropped[0].index)
    }

    @Test
    fun `接地下限优先于上限（C-2）`() {
        val original = concept(
            examples = listOf(ConceptExample(description = "a"), ConceptExample(description = "b")),
        )
        val result = pruneConcept(original, ConceptBudgetOverrides(maxExamples = 0, minExamples = 1))
        assertEquals(1, result.concept.examples?.size)
        assertEquals(1, result.plan.budget.maxExamples)
        assertEquals(1, result.plan.budget.minExamples)
        assertEquals(ConceptDropReason.OVER_BUDGET, result.plan.dropped.single().reason)
    }

    @Test
    fun `定义、intension、反例与 grounded_in 永不被裁剪（C-1）`() {
        val counterexample = ConceptExample(description = "a declared change that was applied as written")
        val grounded = Ref(RefKind.EVENT, "e.grounded")
        val original = concept(
            counterexamples = listOf(counterexample),
            groundedIn = listOf(grounded),
            examples = listOf(
                ConceptExample(description = "one"),
                ConceptExample(description = "two"),
                ConceptExample(description = "three"),
            ),
        )
        val result = pruneConcept(original, ConceptBudgetOverrides(maxExamples = 1))
        assertEquals(original.intension, result.concept.intension)
        assertEquals(original.definition, result.concept.definition)
        assertEquals(original.counterexamples, result.concept.counterexamples)
        assertEquals(original.groundedIn, result.concept.groundedIn)
        assertEquals(1, result.concept.examples?.size)
        assertEquals(original.version, result.concept.version)
        assertEquals(original.label, result.concept.label)
        assertEquals(original.confidence, result.concept.confidence, 0.0)
        assertEquals(original.provenance, result.concept.provenance)
        assertEquals(original.created, result.concept.created)
    }

    @Test
    fun `关系去重，dangling 目标在提供解析器时离开视图`() {
        val original = concept(
            relations = listOf(
                ConceptRelation(ConceptRelationType.CAUSES, Ref(RefKind.CLAIM, "cl.1")),
                ConceptRelation(ConceptRelationType.CAUSES, Ref(RefKind.CLAIM, "cl.1")),
                ConceptRelation(ConceptRelationType.CONTRASTS_WITH, Ref(RefKind.CONCEPT, "c.declared-state")),
            ),
        )
        val result = pruneConcept(
            original,
            options = ConceptPruneOptions(known = { kind, id -> kind == "claim" && id == "cl.1" }),
        )
        assertEquals(
            listOf("causes:cl.1"),
            result.concept.relations?.map { "${it.type.wire}:${it.target.id}" },
        )
        assertEquals(
            listOf(ConceptDropReason.DANGLING, ConceptDropReason.DUPLICATE),
            result.plan.dropped.map { it.reason }.sortedBy { it.wire },
        )
        val dangling = result.plan.dropped.first { it.reason == ConceptDropReason.DANGLING }
        assertEquals("contrasts-with → concept:c.declared-state", dangling.summary)
        assertTrue(dangling.revisitIf.contains("c.declared-state"))
    }

    @Test
    fun `超出关系上限的部分被裁掉，计划给出回收字节`() {
        val original = concept(
            relations = listOf(
                ConceptRelation(ConceptRelationType.IS_A, Ref(RefKind.CONCEPT, "c.drift")),
                ConceptRelation(ConceptRelationType.CAUSES, Ref(RefKind.CLAIM, "cl.1")),
            ),
        )
        val result = pruneConcept(original, ConceptBudgetOverrides(maxRelations = 1))
        assertEquals(1, result.concept.relations?.size)
        assertEquals(ConceptDropReason.OVER_BUDGET, result.plan.dropped[0].reason)
        assertTrue(result.plan.reclaimedBytes > 0)
        assertEquals(
            "if the relation becomes load-bearing for a query",
            result.plan.dropped[0].revisitIf,
        )
    }

    @Test
    fun `extension 只做精确去重`() {
        val original = concept(
            extension = listOf(
                Ref(RefKind.CLAIM, "cl.1"),
                Ref(RefKind.CLAIM, "cl.1"),
                Ref(RefKind.CONCEPT, "c.other"),
            ),
        )
        val result = pruneConcept(original)
        assertEquals(
            listOf("claim:cl.1", "concept:c.other"),
            result.concept.extension?.map { "${it.kind.wire}:${it.id}" },
        )
        assertEquals(ConceptDropSlot.EXTENSION, result.plan.dropped.single().slot)
        assertEquals("claim:cl.1", result.plan.dropped.single().summary)
        assertEquals("if the duplicate comes from a different source", result.plan.dropped.single().revisitIf)
    }

    @Test
    fun `每次删除都带 reason、slot 与非空 revisit_if（C-3）`() {
        val original = concept(
            examples = listOf(
                ConceptExample(description = "a"),
                ConceptExample(description = "a"),
                ConceptExample(description = "b"),
            ),
            extension = listOf(Ref(RefKind.CLAIM, "cl.1"), Ref(RefKind.CLAIM, "cl.1")),
        )
        val result = pruneConcept(original, ConceptBudgetOverrides(maxExamples = 1))
        assertTrue(result.plan.dropped.size >= 3)
        result.plan.dropped.forEach { drop ->
            assertTrue(drop.reason in ConceptDropReason.entries)
            assertTrue(drop.slot in ConceptDropSlot.entries)
            assertTrue(drop.revisitIf.length > 10)
            assertTrue(drop.summary.isNotBlank())
            assertTrue(drop.index >= 0)
        }
        assertEquals("c.deploy-drift", result.plan.concept)
        assertEquals(original.version, result.plan.version)
        // 回收字节数与参考实现一致（JSON/UTF-8 计量）。
        assertEquals(66, result.plan.reclaimedBytes)
    }

    @Test
    fun `裁剪是确定性的且不修改输入`() {
        val original = concept(
            examples = listOf(
                ConceptExample(description = "a"),
                ConceptExample(description = "a"),
                ConceptExample(description = "b"),
            ),
        )
        val first = pruneConcept(original, ConceptBudgetOverrides(maxExamples = 1))
        val second = pruneConcept(original, ConceptBudgetOverrides(maxExamples = 1))
        assertEquals(first.plan, second.plan)
        assertEquals(first.concept, second.concept)
        assertEquals(3, original.examples?.size)
    }

    @Test
    fun `历史压缩保留头版本并提交其余版本（C-4）`() {
        val versions = (1..3).map { version ->
            concept(version = version, definition = "definition v$version")
        }
        val compaction = compactConceptHistory(versions, keepVersions = 1, now = AT)
        assertEquals(2, compaction.removed.size)
        assertEquals(3, compaction.head.version)
        assertEquals(1, compaction.checkpoint.from)
        assertEquals(2, compaction.checkpoint.to)
        assertEquals(2, compaction.checkpoint.count)
        assertEquals("cp.concept.c.deploy-drift.2", compaction.checkpoint.id)
        assertEquals(1, compaction.checkpoint.budget.keepVersions)
        assertEquals(AT, compaction.checkpoint.created)
        // 摘要与参考 TS 实现（Node 24 直跑 src/concept/conceptBudget.ts）逐位一致。
        assertEquals(
            "sha256:696371b69230e68a1a58a386476795dbb96e0ad4690dc48e947865808db0dc8b",
            compaction.checkpoint.tip,
        )
        assertEquals(
            "sha256:2a5a18102478de64b6b5232d6aaf10c9de003500bda818efd1acebcf4b0bae29",
            compaction.checkpoint.root,
        )
        assertEquals(
            CheckpointVerdict(ok = true, reasons = emptyList()),
            verifyConceptCheckpoint(compaction.checkpoint, compaction.removed, compaction.head),
        )
    }

    @Test
    fun `保留多版本时窗口外的前缀被压缩`() {
        val versions = (1..5).map { version -> concept(version = version) }
        val compaction = compactConceptHistory(versions, keepVersions = 2)
        assertEquals(3, compaction.removed.size)
        assertEquals(5, compaction.head.version)
        assertEquals(3, compaction.checkpoint.to)
        // 检查点的 head 指紧接其后的版本（v4），不是最终头版本（v5）。
        assertTrue(verifyConceptCheckpoint(compaction.checkpoint, compaction.removed, versions[3]).ok)
        assertTrue(
            verifyConceptCheckpoint(compaction.checkpoint, compaction.removed, compaction.head)
                .reasons.any { it.contains("head version") },
        )
    }

    @Test
    fun `被改写 root 或 tip 的检查点被拒绝`() {
        val versions = (1..3).map { version -> concept(version = version) }
        val compaction = compactConceptHistory(versions, keepVersions = 1)
        val rewrittenRoot = compaction.checkpoint.copy(root = "sha256:" + "0".repeat(64))
        val rootVerdict = verifyConceptCheckpoint(rewrittenRoot, compaction.removed, compaction.head)
        assertFalse(rootVerdict.ok)
        assertTrue(rootVerdict.reasons.any { it.contains("root") })

        val rewrittenTip = compaction.checkpoint.copy(tip = "sha256:" + "1".repeat(64))
        val tipVerdict = verifyConceptCheckpoint(rewrittenTip, compaction.removed, compaction.head)
        assertFalse(tipVerdict.ok)
        assertTrue(tipVerdict.reasons.any { it.contains("tip") })

        val wrongCount = compaction.checkpoint.copy(count = 5)
        assertTrue(
            verifyConceptCheckpoint(wrongCount, compaction.removed, compaction.head)
                .reasons.any { it.contains("count") },
        )
    }

    @Test
    fun `头版本不连续时检查点被拒绝`() {
        val versions = (1..3).map { version -> concept(version = version) }
        val compaction = compactConceptHistory(versions, keepVersions = 1)
        val verdict = verifyConceptCheckpoint(compaction.checkpoint, compaction.removed, concept(version = 9))
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("head version") })

        val foreignHead = concept(version = 3, id = "c.other")
        assertTrue(
            verifyConceptCheckpoint(compaction.checkpoint, compaction.removed, foreignHead)
                .reasons.any { it.contains("head belongs") },
        )
    }

    @Test
    fun `空 removed 被报告为没有版本`() {
        val versions = (1..3).map { version -> concept(version = version) }
        val checkpoint = compactConceptHistory(versions, keepVersions = 1).checkpoint
        val verdict = verifyConceptCheckpoint(checkpoint, emptyList())
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.any { it.contains("no versions were removed") })
        assertTrue(verdict.reasons.any { it.contains("root") })
    }

    @Test
    fun `谱系断裂、窗口覆盖全部版本或身份混合时拒绝压缩`() {
        assertThrows(ConceptCompactionRefused::class.java) {
            compactConceptHistory(listOf(concept(version = 1), concept(version = 3)), keepVersions = 1)
        }
        assertThrows(ConceptCompactionRefused::class.java) {
            compactConceptHistory(listOf(concept(version = 1), concept(version = 2)), keepVersions = 2)
        }
        assertThrows(ConceptCompactionRefused::class.java) {
            compactConceptHistory(emptyList())
        }
        assertThrows(ConceptCompactionRefused::class.java) {
            compactConceptHistory(listOf(concept(version = 1), concept(version = 2, id = "c.other")))
        }
    }

    @Test
    fun `拒绝异常是 IllegalArgumentException 的子类，便于上层捕获`() {
        val error = assertThrows(ConceptCompactionRefused::class.java) {
            compactConceptHistory(emptyList())
        }
        assertTrue(error is IllegalArgumentException)
        assertTrue(error.message!!.contains("no versions"))
    }

    private fun concept(
        id: String = "c.deploy-drift",
        version: Int = 1,
        definition: String = "configuration diverges from the declared state",
        examples: List<ConceptExample>? = listOf(ConceptExample(description = "a config map edited by hand")),
        counterexamples: List<ConceptExample>? = null,
        relations: List<ConceptRelation>? =
            listOf(ConceptRelation(ConceptRelationType.CAUSES, Ref(RefKind.CLAIM, "cl.1"))),
        extension: List<Ref>? = null,
        groundedIn: List<Ref>? = null,
    ): Concept = Concept(
        id = id,
        label = "deploy drift",
        definition = definition,
        intension = listOf(ConceptFeature("declared differs from observed", 0.9)),
        status = ConceptStatus.ACTIVE,
        confidence = 0.7,
        provenance = Provenance(source = "test"),
        version = version,
        created = AT,
        examples = examples,
        counterexamples = counterexamples,
        relations = relations,
        extension = extension,
        groundedIn = groundedIn,
    )

    private companion object {
        const val AT: String = "2026-10-24T01:00:00Z"
    }
}
