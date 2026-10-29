package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {

    private fun contextFor(store: ContentStore, content: String, nodeId: String = "file.doc"): Context {
        val ast = ContextAst()
        ast.add(ContextAstNode("project.alpha", ContextNodeKind.PROJECT, "alpha", 0))
        ast.add(
            ContextAstNode(
                id = nodeId,
                kind = ContextNodeKind.FILE,
                summary = "document",
                order = 1,
                parentId = "project.alpha",
                detailRef = store.put(content),
                salience = 1.0,
            ),
        )
        val frame = ContextFrame(
            id = "frame.1",
            agent = AGENT_ID,
            slots = listOf(ContextSlot(Ref(RefKind.ENTITY, nodeId), SlotState.ACTIVE, "read document")),
            allocatedTokens = 1000,
        )
        val context = Context.create(frame, ast, store, now = { "2026-10-29T00:00:00.000Z" })
        context.materialize(500)
        return context
    }

    @Test
    fun encodesAndReadsTypedDetailsThroughTheContentStore() {
        val detail = SheetDetail(
            name = "Sheet1",
            cells = listOf(DocumentCell("A1", "string", value = "total")),
            merges = listOf("A1:B1"),
            xml = "<sheet/>",
        )
        val store = MemoryContentStore()
        val context = contextFor(store, encodeDocumentDetail(detail))
        assertEquals(detail, readDocumentDetail(context, "file.doc"))
    }

    @Test
    fun legacyAndUnknownDetailsFallBackToPlainText() {
        val store = MemoryContentStore()
        val legacy = contextFor(store, "just text")
        assertEquals(TextDetail("just text"), readDocumentDetail(legacy, "file.doc"))
        assertNull(readDocumentDetail(legacy, "file.none"))

        val unknown = "{\"ampDocument\":1,\"detail\":{\"kind\":\"nope\"}}"
        val malformed = contextFor(store, unknown)
        assertEquals(TextDetail(unknown), readDocumentDetail(malformed, "file.doc"))
    }

    private fun decode(encoded: String): DocumentDetail? {
        val envelope = JsonParser.parse(encoded) as com.eloykim.lyra.amp.protocol.json.JsonValue.Obj
        return DocumentDetail.fromJson(envelope.requireObj("detail"))
    }

    @Test
    fun locatorsAndOriginalDetailsRoundTrip() {
        val cell = CellLocator("xl/worksheets/sheet1.xml", "Sheet1", "A1:B2")
        assertEquals(cell, DocumentLocator.fromJson(cell.toJson()))
        assertEquals(cell, DocumentLocator.fromJson(JsonParser.parse(CanonicalJson.encode(cell.toJson()))))

        val slide = SlideLocator("ppt/slides/slide1.xml", 1, "shape.2")
        assertEquals(slide, DocumentLocator.fromJson(slide.toJson()))
        assertEquals(PartLocator("word/document.xml", "body"), DocumentLocator.fromJson(PartLocator("word/document.xml", "body").toJson()))

        val original = OriginalDetail("application/pdf", "AAAA")
        val encoded = encodeDocumentDetail(original)
        assertTrue(encoded.contains("base64"))
        assertEquals(original, decode(encoded))
    }

    @Test
    fun texProjectDetailsKeepNativeDependencies() {
        val detail = TexProjectDetail(
            root = "main.tex",
            files = listOf("main.tex", "chapter.tex"),
            dependencies = listOf(TexDependency("main.tex", "chapter.tex", "include")),
        )
        assertEquals(detail, decode(encodeDocumentDetail(detail)))
    }
}
