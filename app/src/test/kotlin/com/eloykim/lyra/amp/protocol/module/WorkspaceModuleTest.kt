package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkspaceModuleTest {

    private fun obj(text: String): JsonValue.Obj = JsonParser.parse(text) as JsonValue.Obj

    private fun meta(): JsonValue.Obj =
        jsonObj(McpProtocol.META_PROTOCOL_VERSION to JsonValue.Str(McpProtocol.VERSION))

    private fun rpc(
        method: String,
        id: Long = 1,
        params: JsonValue.Obj = JsonValue.Obj(),
    ): JsonValue.Obj = JsonRpcRequest(
        method = method,
        id = JsonRpcId.Num(id),
        params = params.copyWith("_meta", meta()),
    ).toJson()

    private fun callParams(name: String, arguments: JsonValue.Obj): JsonValue.Obj =
        jsonObj(
            "name" to JsonValue.Str(name),
            "arguments" to arguments,
        )

    private fun errorCode(response: JsonValue.Obj): Int? =
        response.obj("error")?.int("code")

    private class FakeWorkspaceService(
        override val revision: String = "rev.1",
        private val definitions: List<WorkspaceToolDefinition> = WorkspaceToolDefinitions.all(),
    ) : WorkspaceService {

        val calls: MutableList<Pair<String, JsonValue>> = ArrayList()

        var reply: (String, JsonValue) -> WorkspaceResponse = { name, _ ->
            WorkspaceResponse.success(revision, jsonObj("tool" to JsonValue.Str(name)))
        }

        override fun tools(): List<WorkspaceToolDefinition> = definitions

        override fun call(name: String, args: JsonValue): WorkspaceResponse {
            calls += name to args
            return reply(name, args)
        }
    }

    @Test
    fun `七个 workspace 工具定义完整`() {
        val tools = WorkspaceToolDefinitions.all()
        assertEquals(7, tools.size)
        assertEquals(
            listOf(
                "amp_workspace_capabilities",
                "amp_workspace_tree",
                "amp_workspace_query",
                "amp_workspace_relations",
                "amp_workspace_materialize",
                "amp_workspace_read",
                "amp_workspace_release",
            ),
            tools.map { it.name },
        )
        tools.forEach { tool ->
            assertEquals("object", tool.inputSchema.string("type"))
            assertEquals(false, tool.inputSchema.bool("additionalProperties"))
            assertTrue(tool.inputSchema.has("required"))
        }
        val tree = tools.first { it.name == "amp_workspace_tree" }
        assertEquals(
            listOf("revision"),
            tree.inputSchema.requireArr("required").items.map { (it as JsonValue.Str).value },
        )
        val query = tools.first { it.name == "amp_workspace_query" }
        val properties = query.inputSchema.requireObj("properties")
        assertEquals(10, properties.requireObj("maxDepth").int("maximum"))
        assertEquals(100, properties.requireObj("limit").int("maximum"))
    }

    @Test
    fun `workspace 响应信封往返且拒绝同时含 data 与 error`() {
        val success = WorkspaceResponse.success("rev.1", jsonObj("x" to jsonNumber(1)))
        val json = success.toJson()
        assertEquals("amp.workspace", json.string("protocol"))
        assertEquals(1, json.int("version"))
        assertEquals("rev.1", json.string("revision"))
        assertEquals(true, json.bool("ok"))
        assertEquals(success, WorkspaceResponse.fromJson(JsonParser.parse(CanonicalJson.encode(json))))

        val failure = WorkspaceResponse.failure("rev.1", WorkspaceErrorCode.REVISION_MISMATCH)
        val round = WorkspaceResponse.fromJson(JsonParser.parse(CanonicalJson.encode(failure.toJson())))
        assertFalse(round.ok)
        assertEquals(WorkspaceErrorCode.REVISION_MISMATCH, round.error!!.code)
        assertEquals("revision_mismatch", round.error!!.message)

        try {
            WorkspaceResponse(
                revision = "rev.1",
                ok = true,
                data = null,
                error = WorkspaceError(WorkspaceErrorCode.INTERNAL_ERROR, "x"),
            )
            fail("ok 与 error 不能同时成立")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("data 或 error"))
        }
    }

    @Test
    fun `workspace 工具列表附带只读注解`() {
        val server = WorkspaceMcpServer(FakeWorkspaceService())
        val response = server.handle(rpc("tools/list"))!!
        val result = response.requireObj("result")
        assertEquals("complete", result.string("resultType"))
        assertEquals(0L, result.long("ttlMs"))
        assertEquals("private", result.string("cacheScope"))
        val tools = result.requireArr("tools")
        assertEquals(7, tools.size)
        val annotations = tools.obj(0)!!.requireObj("annotations")
        assertEquals(true, annotations.bool("readOnlyHint"))
        assertEquals(false, annotations.bool("destructiveHint"))
        assertEquals(false, annotations.bool("openWorldHint"))
    }

    @Test
    fun `capabilities 不需要 revision`() {
        val service = FakeWorkspaceService()
        service.reply = { name, _ ->
            assertEquals("amp_workspace_capabilities", name)
            WorkspaceResponse.success("rev.1", jsonObj("agentId" to JsonValue.Str("agent.1")))
        }
        val server = WorkspaceMcpServer(service)
        val response = server.handle(
            rpc(
                "tools/call",
                params = callParams("amp_workspace_capabilities", JsonValue.Obj()),
            ),
        )!!
        val result = CallToolResult.fromJson(response.requireObj("result"))
        assertEquals(false, result.isError)
        assertTrue(textOf(result).contains("agent.1"))
        assertEquals(1, service.calls.size)
    }

    @Test
    fun `业务工具校验 revision`() {
        val mismatchService = FakeWorkspaceService(revision = "rev.2")
        val mismatchServer = WorkspaceMcpServer(mismatchService)
        val mismatch = CallToolResult.fromJson(
            mismatchServer.handle(
                rpc(
                    "tools/call",
                    params = callParams("amp_workspace_tree", obj("""{"revision":"rev.1"}""")),
                ),
            )!!.requireObj("result"),
        )
        assertEquals(true, mismatch.isError)
        val mismatchEnvelope = WorkspaceResponse.fromJson(JsonParser.parse(textOf(mismatch)))
        assertEquals(WorkspaceErrorCode.REVISION_MISMATCH, mismatchEnvelope.error!!.code)
        assertEquals(0, mismatchService.calls.size)

        val invalid = CallToolResult.fromJson(
            mismatchServer.handle(
                rpc(
                    "tools/call",
                    params = callParams("amp_workspace_tree", JsonValue.Obj()),
                ),
            )!!.requireObj("result"),
        )
        val invalidEnvelope = WorkspaceResponse.fromJson(JsonParser.parse(textOf(invalid)))
        assertEquals(WorkspaceErrorCode.INVALID_ARGUMENTS, invalidEnvelope.error!!.code)
        assertEquals(0, mismatchService.calls.size)

        val service = FakeWorkspaceService(revision = "rev.1")
        val server = WorkspaceMcpServer(service)
        val accepted = CallToolResult.fromJson(
            server.handle(
                rpc(
                    "tools/call",
                    params = callParams("amp_workspace_tree", obj("""{"revision":"rev.1"}""")),
                ),
            )!!.requireObj("result"),
        )
        assertEquals(false, accepted.isError)
        assertEquals(1, service.calls.size)
    }

    @Test
    fun `未知工具映射为 JSON-RPC 错误`() {
        val service = FakeWorkspaceService()
        val server = WorkspaceMcpServer(service)
        val response = server.handle(
            rpc(
                "tools/call",
                params = callParams("amp_workspace_nope", JsonValue.Obj()),
            ),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(response))
        assertEquals("unknown tool", response.requireObj("error").string("message"))

        service.reply = { _, _ -> WorkspaceResponse.failure("rev.1", WorkspaceErrorCode.UNKNOWN_TOOL) }
        val fromService = server.handle(
            rpc(
                "tools/call",
                params = callParams("amp_workspace_tree", obj("""{"revision":"rev.1"}""")),
            ),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(fromService))
    }

    @Test
    fun `workspace 通知不执行工具`() {
        val service = FakeWorkspaceService()
        val server = WorkspaceMcpServer(service)
        val notification = JsonRpcRequest.notification(
            "tools/call",
            callParams(
                "amp_workspace_release",
                obj("""{"revision":"rev.1","contextId":"c.1"}"""),
            ),
        ).toJson()
        assertNull(server.handle(notification))
        assertEquals(0, service.calls.size)
    }

    @Test
    fun `workspace 服务支持 legacy initialize 与无状态发现`() {
        val server = WorkspaceMcpServer(FakeWorkspaceService())
        val init = server.handle(
            JsonRpcRequest(
                method = "initialize",
                id = JsonRpcId.Num(1),
                params = obj("""{"protocolVersion":"2025-11-25"}"""),
            ).toJson(),
        )!!
        assertNull(init["error"])
        assertEquals("2025-11-25", init.requireObj("result").string("protocolVersion"))
        assertEquals(
            "amp-workspace",
            init.requireObj("result").requireObj("serverInfo").string("name"),
        )

        val list = server.handle(
            JsonRpcRequest(method = "tools/list", id = JsonRpcId.Num(2)).toJson(),
        )!!
        assertNull(list["error"])

        val discoverWithout = server.handle(
            JsonRpcRequest(method = "server/discover", id = JsonRpcId.Num(3)).toJson(),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, errorCode(discoverWithout))
        assertEquals(
            "missing supported protocol version",
            discoverWithout.requireObj("error").string("message"),
        )

        val discover = server.handle(rpc("server/discover", id = 4))!!
        val discovery = DiscoveryResult.fromJson(discover.requireObj("result"))
        assertEquals(listOf(McpProtocol.VERSION), discovery.supportedVersions)
        assertEquals(0L, discovery.ttlMs)
        assertEquals("private", discovery.cacheScope)
        assertEquals(
            1,
            discovery.capabilities.obj("extensions")
                ?.obj("io.amp/workspace")
                ?.int("version"),
        )

        val ping = server.handle(rpc("ping", id = 5))!!
        assertEquals(0, ping.requireObj("result").fields.size)

        val unknownMethod = server.handle(rpc("unknown/method", id = 6))!!
        assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, errorCode(unknownMethod))
    }

    @Test
    fun `workspace 服务拒绝非法信封与超大消息`() {
        val server = WorkspaceMcpServer(FakeWorkspaceService())
        val notObject = server.handle(JsonValue.Str("x"))!!
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, errorCode(notObject))

        val badVersion = server.handle(
            obj("""{"jsonrpc":"1.0","id":1,"method":"ping"}"""),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, errorCode(badVersion))

        val badId = server.handle(
            obj("""{"jsonrpc":"2.0","id":1.5,"method":"ping"}"""),
        )!!
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, errorCode(badId))
        assertNull(JsonRpcResponse.fromJson(badId).id)

        assertNull(server.handleLine("  "))
        val parseError = server.handleLine("{")!!
        assertEquals(JsonRpcErrorCode.PARSE_ERROR, errorCode(parseError))
        val oversize = server.handleLine("x".repeat(McpLimits.MAX_MESSAGE_BYTES + 1))!!
        assertEquals(JsonRpcErrorCode.INVALID_REQUEST, errorCode(oversize))
        assertEquals("request exceeds 1 MiB", oversize.requireObj("error").string("message"))
    }

    @Test
    fun `workspace executor 转发回退并编码信封`() {
        val service = FakeWorkspaceService()
        var fallbackCalls = 0
        val executor = WorkspaceToolExecutor(
            service,
            ToolExecutor {
                fallbackCalls += 1
                ToolResult(true, "fallback")
            },
        )
        val fallback = executor.execute(ToolInvocation("logs.query", JsonValue.Obj()))
        assertTrue(fallback.ok)
        assertEquals("fallback", fallback.content)
        assertEquals(1, fallbackCalls)
        assertEquals(0, service.calls.size)

        val workspace = executor.execute(
            ToolInvocation("amp_workspace_capabilities", JsonValue.Obj()),
        )
        assertTrue(workspace.ok)
        assertTrue(workspace.content.contains("amp.workspace"))
        assertNull(workspace.error)
        assertEquals(1, service.calls.size)
    }

    @Test
    fun `workspace 服务异常映射为 internal_error 信封`() {
        val service = FakeWorkspaceService()
        service.reply = { _, _ -> throw IllegalStateException("boom") }
        val server = WorkspaceMcpServer(service)
        val result = CallToolResult.fromJson(
            server.handle(
                rpc(
                    "tools/call",
                    params = callParams("amp_workspace_tree", obj("""{"revision":"rev.1"}""")),
                ),
            )!!.requireObj("result"),
        )
        assertEquals(true, result.isError)
        val envelope = WorkspaceResponse.fromJson(JsonParser.parse(textOf(result)))
        assertEquals(WorkspaceErrorCode.INTERNAL_ERROR, envelope.error!!.code)
    }

    @Test
    fun `注册 workspace 工具拒绝重复`() {
        val registry = ToolRegistry()
        val descriptors = registerWorkspaceTools(registry, FakeWorkspaceService())
        assertEquals(7, descriptors.size)
        assertEquals(ToolRisk.READ, descriptors[0].risk)
        assertEquals(7, registry.size)
        try {
            registerWorkspaceTools(registry, FakeWorkspaceService())
            fail("重复注册应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("workspace tools already registered", error.message)
        }
    }

    @Test
    fun `workspace service lambda 适配器`() {
        val service = WorkspaceService.of(
            revisionProvider = { "rev.9" },
            callHandler = { name, _ ->
                WorkspaceResponse.success("rev.9", jsonObj("name" to JsonValue.Str(name)))
            },
        )
        assertEquals("rev.9", service.revision)
        assertEquals(7, service.tools().size)
        val response = service.call("amp_workspace_tree", obj("""{"revision":"rev.9"}"""))
        assertTrue(response.ok)
        assertEquals("rev.9", response.revision)
    }
}

class SandboxTest {

    @Test
    fun `denied sandbox 明确拒绝执行`() {
        try {
            DeniedSandbox().run(SandboxCommand("rm", listOf("-rf", "/")))
            fail("默认沙箱必须拒绝")
        } catch (error: SandboxUnavailableException) {
            assertEquals(
                "sandbox_unavailable: no secure sandbox is configured",
                error.message,
            )
        }
    }
}
