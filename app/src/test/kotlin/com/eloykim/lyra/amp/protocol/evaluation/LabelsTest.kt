package com.eloykim.lyra.amp.protocol.evaluation

import com.eloykim.lyra.amp.protocol.json.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {

    private val annotations = listOf(
        Annotation("c1", "a1", "X"),
        Annotation("c1", "a2", "Y"),
        Annotation("c2", "a1", "X"),
        Annotation("c2", "a2", "X"),
        Annotation("c2", "a1", "X", note = "重复标注"),
        Annotation("c3", "a1", "Z"),
    )

    @Test
    fun `CSV 支持 BOM 引号逗号与引号内换行`() {
        val text = "\uFEFFcaseId,annotator,label,note\r\n" +
            "c1,a1,X,\"first, note\"\r\n" +
            "c2,a2,Y,\"line1\nline2\"\r\n"
        val parsed = importAnnotationsCsv(text)
        assertEquals(2, parsed.size)
        assertEquals(Annotation("c1", "a1", "X", "first, note"), parsed[0])
        assertEquals("line1\nline2", parsed[1].note)

        val noNote = importAnnotationsCsv("caseId,annotator,label\nc1,a1,X\n")
        assertEquals(listOf(Annotation("c1", "a1", "X", null)), noNote)
    }

    @Test
    fun `CSV 表头与残缺行被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            importAnnotationsCsv("caseId,label\nc1,X")
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            importAnnotationsCsv("caseId,annotator,label\nc1")
        }
        assertEquals("CSV row 2 is incomplete", error.message)
        assertTrue(importAnnotationsCsv("").isEmpty())
    }

    @Test
    fun `主动学习优先 05 附近与高不确定性`() {
        val candidates = listOf(
            LabelCandidate("near-uncertain", predicted = 0.5, uncertainty = 0.9),
            LabelCandidate("near-certain", predicted = 0.5, uncertainty = 0.1),
            LabelCandidate("far", predicted = 0.9, uncertainty = 0.9),
        )
        val selected = selectLabelCandidates(candidates, budget = 1)
        assertEquals("near-uncertain", selected.single().caseId)
        assertEquals(3, selectLabelCandidates(candidates, budget = 10).size)
        assertTrue(selectLabelCandidates(candidates, budget = -1).isEmpty())
    }

    @Test
    fun `审计覆盖重复分歧裁决与两两 kappa`() {
        val audit = auditLabels(listOf("c1", "c2", "c3"), annotations)

        assertEquals(3, audit.cases)
        assertEquals(6, audit.annotations)
        assertEquals(3, audit.covered)
        assertEquals(1.0, audit.coverage, 0.0)
        assertTrue(audit.missing.isEmpty())
        assertEquals(listOf("a1", "a2"), audit.annotators)

        val duplicate = audit.duplicates.single()
        assertEquals("c2", duplicate.caseId)
        assertEquals("a1", duplicate.annotator)
        assertEquals(2, duplicate.count)

        val disagreement = audit.disagreements.single()
        assertEquals("c1", disagreement.caseId)
        assertEquals(mapOf("X" to 1, "Y" to 1), disagreement.labels)

        assertNull(audit.adjudicated["c1"])
        assertEquals("X", audit.adjudicated["c2"])
        assertEquals("Z", audit.adjudicated["c3"])

        val pairwise = audit.pairwiseKappa.single()
        assertEquals("a1", pairwise.left)
        assertEquals("a2", pairwise.right)
        assertEquals(2, pairwise.common)
        assertEquals(0.0, pairwise.kappa, 1e-12)
    }

    @Test
    fun `覆盖门槛与重复上限`() {
        val audit = auditLabels(listOf("c1", "c2", "c3"), annotations)
        assertThrows(IllegalArgumentException::class.java) { requireLabelCoverage(audit) }
        requireLabelCoverage(audit, maxDuplicates = 1)

        val partial = auditLabels(listOf("c1", "c9"), annotations)
        assertEquals(1, partial.missing.size)
        assertEquals(0.5, partial.coverage, 0.0)
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireLabelCoverage(partial, minCoverage = 0.9)
        }
        assertEquals("label coverage 0.500 < 0.9", error.message)
    }

    @Test
    fun `裁决只填补未解决的平局`() {
        val audit = auditLabels(listOf("c1", "c2", "c3"), annotations)
        val resolved = resolveUnresolved(audit, mapOf("c1" to "X", "c2" to "Y"))
        assertEquals("X", resolved["c1"])
        assertEquals("X", resolved["c2"])
        assertEquals("Z", resolved["c3"])
    }

    @Test
    fun `JSON 数组与 JSONL 导入`() {
        val array = JsonParser.parse(
            """[{"caseId":"c1","annotator":"a1","label":"X","note":"n"},{"caseId":"c2","annotator":"a2","label":"Y"}]""",
        )
        val fromArray = importAnnotations(array)
        assertEquals(2, fromArray.size)
        assertEquals("n", fromArray[0].note)
        assertNull(fromArray[1].note)

        val jsonl = importAnnotationsJsonl(
            "{\"caseId\":\"c1\",\"annotator\":\"a1\",\"label\":\"X\"}\n\n" +
                "{\"caseId\":\"c2\",\"annotator\":\"a2\",\"label\":\"Y\"}",
        )
        assertEquals(2, jsonl.size)

        val broken = JsonParser.parse("""[{"caseId":"c1","annotator":"a1"}]""")
        assertThrows(IllegalArgumentException::class.java) { importAnnotations(broken) }
    }
}
