package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceServiceTest {

    private fun service(
        fixture: WorkspaceFixture = workspaceFixture(),
        store: ContentStore = fixture.store,
        limits: WorkspaceServiceLimits = WorkspaceServiceLimits(),
    ): WorkspaceAgentService = WorkspaceAgentService(
        WorkspaceServiceOptions(
            agentId = AGENT_ID,
            projectIds = listOf("project.alpha"),
            snapshot = fixture.snapshot,
            store = store,
            limits = limits,
        ),
    )

    private fun success(response: WorkspaceResponse): JsonValue.Obj {
        assertTrue("expected success, got ${response.toJson()}", response is WorkspaceResponse.Success)
        return (response as WorkspaceResponse.Success).data
    }

    private fun failure(response: WorkspaceResponse): WorkspaceErrorCode {
        assertTrue("expected failure, got ${response.toJson()}", response is WorkspaceResponse.Failure)
        return (response as WorkspaceResponse.Failure).code
    }

    private fun JsonValue.Obj.strings(key: String): List<String>? =
        (this[key] as? JsonValue.Arr)?.items?.map { (it as JsonValue.Str).value }

    private fun materializeArgs(frameId: String = "frame.1", budgetTokens: Int = 2000): JsonValue.Obj =
        callArgs(
            "revision" to text("rev.1"),
            "frameId" to text(frameId),
            "budgetTokens" to number(budgetTokens),
        )

    private fun readArgs(
        contextId: String,
        nodeId: String,
        offset: Int? = null,
        maxChars: Int? = null,
    ): JsonValue.Obj = callArgs(
        "revision" to text("rev.1"),
        "contextId" to text(contextId),
        "nodeId" to text(nodeId),
        "offset" to offset?.let { number(it) },
        "maxChars" to maxChars?.let { number(it) },
    )

    private fun materialize(
        service: WorkspaceAgentService,
        budgetTokens: Int = 2000,
        frameId: String = "frame.1",
    ): JsonValue.Obj = success(
        service.call(
            "amp_workspace_materialize",
            materializeArgs(frameId = frameId, budgetTokens = budgetTokens),
        ),
    )

    @Test
    fun capabilitiesDiscoveryNeedsNoRevisionAndGrantsNothing() {
        val service = service()
        val data = success(service.call("amp_workspace_capabilities", jsonObj()))
        val envelope = service.call("amp_workspace_capabilities", jsonObj()).toJson()

        assertEquals("amp.workspace", envelope.string("protocol"))
        assertEquals(1, envelope.int("version"))
        assertEquals("rev.1", envelope.string("revision"))
        assertEquals(true, envelope.bool("ok"))
        assertEquals(AGENT_ID, data.string("agentId"))
        assertEquals(listOf("project.alpha"), data.strings("projectIds"))
        assertEquals(7, data.arr("tools")!!.size)
        assertEquals(0, data.arr("frames")!!.size)
        assertEquals(1000, data.obj("limits")!!.int("maxToolCalls"))
        assertEquals(999, data.int("remainingToolCalls"))
        assertTrue(data.string("tokenAccounting")!!.contains("character-estimate"))
    }

    @Test
    fun revisionMismatchUnknownToolsAndInvalidArgumentsAreReported() {
        val service = service()
        val mismatch = service.call("amp_workspace_tree", callArgs("revision" to text("rev.0")))
        assertEquals(WorkspaceErrorCode.REVISION_MISMATCH, failure(mismatch))
        val envelope = mismatch.toJson()
        assertEquals("amp.workspace", envelope.string("protocol"))
        assertEquals(1, envelope.int("version"))
        assertEquals("rev.1", envelope.string("revision"))
        assertEquals(false, envelope.bool("ok"))
        assertEquals("revision_mismatch", envelope.obj("error")!!.string("code"))
        assertEquals("revision_mismatch", envelope.obj("error")!!.string("message"))
        assertEquals(
            WorkspaceErrorCode.UNKNOWN_TOOL,
            failure(service.call("amp_workspace_nope", jsonObj())),
        )
        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            failure(service.call("amp_workspace_tree", jsonObj())),
        )
        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            failure(
                service.call(
                    "amp_workspace_tree",
                    callArgs("revision" to text("rev.1"), "limit" to number(1000)),
                ),
            ),
        )
        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            failure(
                service.call(
                    "amp_workspace_tree",
                    callArgs("revision" to text("rev.1"), "unexpected" to text("x")),
                ),
            ),
        )
    }

    @Test
    fun materializeReadsBoundedPagesInUtf16Units() {
        val fixture = workspaceFixture()
        val service = service(fixture)
        service.registerFrame(frameJson(), "rev.1")
        val materialized = materialize(service)
        val contextId = materialized.string("contextId")!!
        assertEquals("frame.1", materialized.string("frameId"))
        assertEquals(2000, materialized.int("budgetTokens"))

        val included = materialized.strings("includedIds")!!
        assertTrue(included.containsAll(listOf("project.alpha", "dir.src", "file.main")))
        assertEquals(listOf("file.main"), materialized.strings("readableIds"))
        val text = materialized.string("text")!!
        assertTrue(text.contains("file.main"))
        assertFalse(text.contains(FILE_CONTENT))

        val first = success(service.call("amp_workspace_read", readArgs(contextId, "file.main", offset = 0, maxChars = 2)))
        assertEquals("AB", first.string("text"))
        assertEquals(2, first.int("nextOffset"))
        assertEquals(FILE_CONTENT.length, first.int("totalChars"))
        assertEquals("file:///alpha/src/main.ts", first.obj("source")!!.string("uri"))

        // 偏移量按 UTF-16 码元计：offset=2 落在表情的高代理项上。
        val second = success(service.call("amp_workspace_read", readArgs(contextId, "file.main", offset = 2, maxChars = 1)))
        assertEquals("\uD83D", second.string("text"))
        assertEquals(1, second.string("text")!!.length)
        assertEquals(3, second.int("nextOffset"))

        val third = success(service.call("amp_workspace_read", readArgs(contextId, "file.main", offset = 4, maxChars = 10)))
        assertEquals("CD", third.string("text"))
        assertTrue(third["nextOffset"] is JsonValue.Null)

        val empty = success(service.call("amp_workspace_read", readArgs(contextId, "file.main", offset = FILE_CONTENT.length)))
        assertEquals("", empty.string("text"))
        assertEquals(0, empty.int("tokens"))
        assertTrue(empty["nextOffset"] is JsonValue.Null)

        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            failure(service.call("amp_workspace_read", readArgs(contextId, "file.main", offset = FILE_CONTENT.length + 1))),
        )
    }

    @Test
    fun structuralAncestorsAreSummariesNotDetailAccess() {
        val fixture = workspaceFixture()
        val service = service(fixture)
        service.registerFrame(frameJson(), "rev.1")
        val materialized = materialize(service)
        val contextId = materialized.string("contextId")!!

        // 祖先目录在视图里（摘要可见），也有 detailRef，但 frame 没有把它列为可读目标。
        assertTrue(materialized.strings("includedIds")!!.contains("dir.src"))
        assertEquals(
            WorkspaceErrorCode.NOT_AVAILABLE,
            failure(service.call("amp_workspace_read", readArgs(contextId, "dir.src"))),
        )
        assertEquals(
            WorkspaceErrorCode.NOT_AVAILABLE,
            failure(service.call("amp_workspace_read", readArgs(contextId, "file.util"))),
        )
        assertEquals("AB", success(service.call("amp_workspace_read", readArgs(contextId, "file.main", maxChars = 2))).string("text"))
    }

    @Test
    fun materializeReusesContextForIdenticalRequests() {
        val service = service()
        service.registerFrame(frameJson(), "rev.1")
        val first = materialize(service, budgetTokens = 1500)
        val second = materialize(service, budgetTokens = 1500)
        assertEquals(first.string("contextId"), second.string("contextId"))
        assertEquals(first.int("remainingTokens"), second.int("remainingTokens"))

        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            failure(service.call("amp_workspace_materialize", materializeArgs(budgetTokens = 1000))),
        )
        assertEquals(
            WorkspaceErrorCode.FRAME_UNAVAILABLE,
            failure(service.call("amp_workspace_materialize", materializeArgs(frameId = "frame.gone"))),
        )
    }

    @Test
    fun releaseRetiresTheHandleWithoutRefundingOrSettling() {
        val service = service()
        service.registerFrame(frameJson(), "rev.1")
        val materialized = materialize(service)
        val contextId = materialized.string("contextId")!!

        val release = success(
            service.call(
                "amp_workspace_release",
                callArgs("revision" to text("rev.1"), "contextId" to text(contextId)),
            ),
        )
        assertEquals(true, release.bool("released"))
        assertEquals("frame.1", release.string("frameId"))
        assertEquals(materialized.int("remainingTokens"), release.int("remainingTokens"))

        assertEquals(
            WorkspaceErrorCode.NOT_AVAILABLE,
            failure(service.call("amp_workspace_read", readArgs(contextId, "file.main"))),
        )
        val pending = service.pendingSettlements()
        assertEquals(1, pending.size)
        assertEquals(contextId, pending.single().contextId)
        assertEquals("frame.1", pending.single().frameId)
        assertTrue(pending.single().estimatedTokens >= 0)

        // 释放不等于结算：frame 不能被重新打点，也不会退款。
        assertEquals(
            WorkspaceErrorCode.FRAME_UNAVAILABLE,
            failure(service.call("amp_workspace_materialize", materializeArgs())),
        )
        assertEquals(1, service.pendingSettlements().size)
    }

    @Test
    fun hostSettlementArchivesContextAndClearsPending() {
        val service = service()
        service.registerFrame(frameJson(), "rev.1")
        val materialized = materialize(service)
        val contextId = materialized.string("contextId")!!

        val snapshot = service.settle(contextId, "rcpt.1")
        assertEquals(ContextPhase.ARCHIVED, snapshot.phase)
        assertEquals(listOf("rcpt.1"), snapshot.receiptIds)
        assertTrue(snapshot.view != null)
        assertTrue(service.pendingSettlements().isEmpty())
        assertEquals(
            WorkspaceErrorCode.NOT_AVAILABLE,
            failure(service.call("amp_workspace_read", readArgs(contextId, "file.main"))),
        )
        assertThrows(WorkspaceServiceException::class.java) { service.settle(contextId, "rcpt.2") }
    }

    @Test
    fun revocationDestroysTheLiveHandleButKeepsHostReconciliation() {
        val service = service()
        service.registerFrame(frameJson(id = "frame.2"), "rev.1")
        val materialized = materialize(service, frameId = "frame.2")
        val contextId = materialized.string("contextId")!!

        service.revokeFrame("frame.2")
        assertEquals(
            WorkspaceErrorCode.NOT_AVAILABLE,
            failure(service.call("amp_workspace_read", readArgs(contextId, "file.main"))),
        )
        assertEquals(listOf(contextId), service.pendingSettlements().map { it.contextId })
        assertEquals("frame.2", service.pendingSettlements().single().frameId)

        // 撤销后仍可由宿主结算（不再对模型开放）。
        val snapshot = service.settle(contextId, "rcpt.2")
        assertEquals(ContextPhase.ARCHIVED, snapshot.phase)
        assertTrue(service.pendingSettlements().isEmpty())
    }

    @Test
    fun frameRegistrationValidatesAdmission() {
        val service = service()
        assertEquals(
            WorkspaceErrorCode.REVISION_MISMATCH,
            assertThrows(WorkspaceServiceException::class.java) {
                service.registerFrame(frameJson(), "rev.0")
            }.code,
        )
        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            assertThrows(WorkspaceServiceException::class.java) {
                service.registerFrame(frameJson(agent = "agent.other"), "rev.1")
            }.code,
        )
        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            assertThrows(WorkspaceServiceException::class.java) {
                service.registerFrame(frameJson(targets = listOf("file.gone")), "rev.1")
            }.code,
        )
        assertEquals(
            WorkspaceErrorCode.INVALID_ARGUMENTS,
            assertThrows(WorkspaceServiceException::class.java) {
                service.registerFrame(frameJson(spent = 5000), "rev.1")
            }.code,
        )
        service.registerFrame(frameJson(), "rev.1")
        assertEquals(
            WorkspaceErrorCode.CAPACITY_EXCEEDED,
            assertThrows(WorkspaceServiceException::class.java) {
                service.registerFrame(frameJson(), "rev.1")
            }.code,
        )
    }

    @Test
    fun treeRelationsAndQueryPageWithoutGrantingDetails() {
        val service = service()
        val tree = success(
            service.call(
                "amp_workspace_tree",
                callArgs("revision" to text("rev.1"), "parentId" to text("dir.src"), "offset" to number(0), "limit" to number(1)),
            ),
        )
        assertEquals(2, tree.int("total"))
        assertEquals(1, tree.int("nextOffset"))
        val item = tree.arr("items")!!.obj(0)!!
        assertEquals("file.main", item.string("id"))
        assertEquals(true, item.bool("hasDetail"))
        assertEquals("untracked", item.string("summaryState"))
        assertEquals(1, item.obj("source")!!.int("startLine"))

        val tail = success(
            service.call(
                "amp_workspace_tree",
                callArgs("revision" to text("rev.1"), "parentId" to text("dir.src"), "offset" to number(1)),
            ),
        )
        assertEquals(1, tail.arr("items")!!.size)
        assertEquals("file.util", tail.arr("items")!!.obj(0)!!.string("id"))
        assertTrue(tail["nextOffset"] is JsonValue.Null)

        val relations = success(
            service.call(
                "amp_workspace_relations",
                callArgs("revision" to text("rev.1"), "nodeId" to text("file.main"), "direction" to text("outgoing")),
            ),
        )
        assertEquals(1, relations.int("total"))
        assertEquals("file.util", relations.arr("items")!!.obj(0)!!.string("to"))
        assertEquals("imports", relations.arr("items")!!.obj(0)!!.string("kind"))

        val query = success(
            service.call(
                "amp_workspace_query",
                callArgs("revision" to text("rev.1"), "text" to text("entry point")),
            ),
        )
        assertEquals("rev.1", query.string("revision"))
        assertEquals(2, query.arr("candidates")!!.size)
        assertEquals("file.main", query.arr("candidates")!!.obj(0)!!.string("id"))
        assertEquals(false, query.bool("truncated"))

        assertEquals(
            WorkspaceErrorCode.NOT_AVAILABLE,
            failure(
                service.call(
                    "amp_workspace_relations",
                    callArgs("revision" to text("rev.1"), "nodeId" to text("file.gone"), "direction" to text("incoming")),
                ),
            ),
        )
    }

    @Test
    fun casIntegrityFailuresAreReportedInsteadOfServingBytes() {
        val fixture = workspaceFixture()
        val tampered = object : ContentStore {
            override fun put(content: String): String = "sha256:" + "0".repeat(64)
            override fun get(ref: String): String = "tampered"
        }
        val service = service(fixture, store = tampered)
        service.registerFrame(frameJson(), "rev.1")
        val materialized = materialize(service)
        val contextId = materialized.string("contextId")!!
        assertEquals(
            WorkspaceErrorCode.INTEGRITY_FAILURE,
            failure(service.call("amp_workspace_read", readArgs(contextId, "file.main"))),
        )
    }

    @Test
    fun limitsBoundFramesContextsAndToolCalls() {
        val toolLimited = service(limits = WorkspaceServiceLimits(maxToolCalls = 3))
        val first = success(toolLimited.call("amp_workspace_capabilities", jsonObj()))
        assertEquals(2, first.int("remainingToolCalls"))
        val second = success(toolLimited.call("amp_workspace_capabilities", jsonObj()))
        assertEquals(1, second.int("remainingToolCalls"))
        success(toolLimited.call("amp_workspace_capabilities", jsonObj()))
        assertEquals(
            WorkspaceErrorCode.BUDGET_EXHAUSTED,
            failure(toolLimited.call("amp_workspace_capabilities", jsonObj())),
        )

        val capacityLimited = service(limits = WorkspaceServiceLimits(maxFrames = 2, maxContexts = 1))
        capacityLimited.registerFrame(frameJson(id = "frame.1"), "rev.1")
        capacityLimited.registerFrame(frameJson(id = "frame.2"), "rev.1")
        val third = assertThrows(WorkspaceServiceException::class.java) {
            capacityLimited.registerFrame(frameJson(id = "frame.3"), "rev.1")
        }
        assertEquals(WorkspaceErrorCode.CAPACITY_EXCEEDED, third.code)

        val firstContext = materialize(capacityLimited)
        assertEquals("frame.1", firstContext.string("frameId"))
        assertEquals(
            WorkspaceErrorCode.CAPACITY_EXCEEDED,
            failure(capacityLimited.call("amp_workspace_materialize", materializeArgs(frameId = "frame.2"))),
        )
    }
}
