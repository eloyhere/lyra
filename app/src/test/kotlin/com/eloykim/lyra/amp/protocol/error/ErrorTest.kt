package com.eloykim.lyra.amp.protocol.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTest {

    @Test
    fun `AmpError keeps the code and copies every problem`() {
        val problems = arrayListOf("first problem", "second problem")
        val error = AmpError(
            name = "TestError",
            code = "test_code",
            problems = problems,
        )
        problems += "after construction"

        assertEquals("TestError", error.name)
        assertEquals("test_code", error.code)
        assertEquals(listOf("first problem", "second problem"), error.problems)
        assertEquals("test_code: first problem; second problem", error.message)
        assertEquals("TestError: test_code: first problem; second problem", error.toString())
    }

    @Test
    fun `AmpError can be built from an AmpErrorCode enum`() {
        val error = AmpError("EnumError", AuthzCode.INVALID_GRANT, listOf("grant expired"))
        assertEquals("invalid_grant", error.code)
        assertEquals("invalid_grant: grant expired", error.message)
    }

    @Test
    fun `empty problems keep the reference message shape`() {
        val error = AmpError("EmptyError", "empty_code", emptyList())
        assertEquals("empty_code: ", error.message)
        assertEquals(0, error.problems.size)
    }

    @Test
    fun `typed subclasses expose both wire code and enum`() {
        val authz = AuthzError(AuthzCode.PERMISSION_DENIED, listOf("no capability"))
        assertEquals("permission_denied", authz.code)
        assertEquals(AuthzCode.PERMISSION_DENIED, authz.typedCode)
        assertEquals("permission_denied: no capability", authz.message)

        val boundary = BoundaryRejection(
            BoundaryCode.INVALID_FRAME,
            listOf("slot 0 is missing", "budget is negative"),
        )
        assertEquals("invalid_frame", boundary.code)
        assertEquals(BoundaryCode.INVALID_FRAME, boundary.typedCode)
        assertEquals("invalid_frame: slot 0 is missing; budget is negative", boundary.message)
    }

    @Test
    fun `agent error omits the separator when details are empty`() {
        val quiet = AgentError(AgentErrorCode.AGENT_NOT_FOUND)
        assertEquals("AgentError", quiet.name)
        assertEquals("agent_not_found", quiet.code.wire)
        assertEquals("agent_not_found", quiet.message)
        assertEquals(emptyList<String>(), quiet.details)

        val noisy = AgentError(
            AgentErrorCode.INVALID_MEMORY,
            listOf("kind is unknown", "content is empty"),
        )
        assertEquals("invalid_memory: kind is unknown; content is empty", noisy.message)
        assertEquals(2, noisy.details.size)
    }

    @Test
    fun `the error taxonomy is stable`() {
        assertEquals(
            listOf(
                "permission_denied",
                "invalid_grant",
                "rights_amplification",
                "expiry_amplification",
                "unknown_capability",
            ),
            AuthzCode.entries.map { it.wire },
        )
        assertEquals(
            listOf(
                "invalid_frame",
                "invalid_tool_args",
                "unresolvable_ref",
                "duplicate_ref",
                "invalid_id",
            ),
            BoundaryCode.entries.map { it.wire },
        )
        assertEquals(
            listOf(
                "invalid_process",
                "invalid_thread",
                "invalid_transition",
                "unknown_process",
                "unknown_thread",
                "budget_exceeded",
                "missing_receipt",
                "agent_busy",
                "claim_failed",
                "lease_lost",
                "unknown_object",
                "duplicate_task",
            ),
            KernelCode.entries.map { it.wire },
        )
        assertEquals(
            listOf("mailbox_full", "unknown_message", "unknown_recipient"),
            MailboxCode.entries.map { it.wire },
        )
        assertEquals(
            listOf("window_full", "unknown_page"),
            WindowCode.entries.map { it.wire },
        )
        assertEquals(
            listOf(
                "agent_not_found",
                "profile_exists",
                "role_not_found",
                "role_not_assigned",
                "memory_not_found",
                "invalid_memory",
                "invalid_affect",
                "relationship_not_found",
                "institution_not_found",
            ),
            AgentErrorCode.entries.map { it.wire },
        )
    }

    @Test
    fun `mailbox and window errors use the shared format`() {
        val mailbox = MailboxError(MailboxCode.MAILBOX_FULL, listOf("capacity 4"))
        assertEquals("mailbox_full: capacity 4", mailbox.message)
        assertTrue(mailbox is AmpError)

        val window = WindowError(WindowCode.UNKNOWN_PAGE, listOf("claim:cl.9"))
        assertEquals("unknown_page: claim:cl.9", window.message)
        assertEquals("unknown_page", window.code)
        assertEquals(WindowCode.UNKNOWN_PAGE, window.typedCode)
    }
}
