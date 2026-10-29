package com.eloykim.lyra.amp.protocol.module

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolRuntimeTest {

    private fun obj(text: String): JsonValue.Obj = JsonParser.parse(text) as JsonValue.Obj

    private val windowSchema: JsonValue.Obj = obj(
        """{"type":"object","properties":{"window":{"type":"string","enum":["pre","post"]}},""" +
            """"required":["window"],"additionalProperties":false}""",
    )

    private val windowDescriptor: ToolDescriptor =
        ToolDescriptor("logs.query", "查询日志", windowSchema, ToolRisk.READ)

    private val windowArgs: JsonValue.Obj = obj("""{"window":"pre"}""")

    @Test
    fun `registry 拒绝重复并按注册顺序列出`() {
        val registry = ToolRegistry()
        assertEquals(0, registry.size)
        registry.register(windowDescriptor)
        registry.register(windowDescriptor.copy(name = "metrics.query", risk = ToolRisk.READ))
        assertEquals(2, registry.size)
        assertEquals(listOf("logs.query", "metrics.query"), registry.list().map { it.name })
        assertEquals("查询日志", registry.get("logs.query")?.description)
        assertNull(registry.get("missing"))
        try {
            registry.register(windowDescriptor)
            fail("重复工具应被拒绝")
        } catch (error: IllegalArgumentException) {
            assertEquals("duplicate tool logs.query", error.message)
        }
    }

    @Test
    fun `risk policy 默认只要求 destructive 审批`() {
        val policy = RiskToolPolicy()
        assertTrue(policy.check(ToolInvocation("logs.query", windowArgs), windowDescriptor).allowed)

        val destructive = windowDescriptor.copy(name = "deploy.rollback", risk = ToolRisk.DESTRUCTIVE)
        val denied = policy.check(ToolInvocation("deploy.rollback", windowArgs), destructive)
        assertFalse(denied.allowed)
        assertEquals("tool deploy.rollback requires approval", denied.reason)

        val approving = RiskToolPolicy(
            RiskToolPolicyOptions(approve = { _, _ -> true }),
        )
        assertTrue(approving.check(ToolInvocation("deploy.rollback", windowArgs), destructive).allowed)

        val rejecting = RiskToolPolicy(
            RiskToolPolicyOptions(approve = { _, _ -> false }),
        )
        val rejected = rejecting.check(ToolInvocation("deploy.rollback", windowArgs), destructive)
        assertFalse(rejected.allowed)
        assertEquals("tool deploy.rollback was not approved", rejected.reason)
    }

    @Test
    fun `risk policy allowlist 先于审批`() {
        val policy = RiskToolPolicy(RiskToolPolicyOptions(allowedTools = listOf("logs.query")))
        val blocked = policy.check(
            ToolInvocation("deploy.rollback", windowArgs),
            windowDescriptor.copy(name = "deploy.rollback", risk = ToolRisk.DESTRUCTIVE),
        )
        assertFalse(blocked.allowed)
        assertEquals("tool deploy.rollback is not in the allowlist", blocked.reason)
    }

    @Test
    fun `redact 遮蔽常见密钥形状`() {
        val text = "key sk-abcdefghijklmnop1234 Bearer abcdefghijklmnop " +
            "token: hunter2 AKIAABCDEFGHIJKLMNOP"
        val redaction = redactSecrets(text)
        assertTrue(redaction.redacted)
        assertFalse(redaction.text.contains("sk-abc"))
        assertFalse(redaction.text.contains("bearer"))
        assertFalse(redaction.text.contains("hunter2"))
        assertFalse(redaction.text.contains("AKIAABCDEFGHIJKLMNOP"))
        assertTrue(redaction.text.contains("[REDACTED]"))

        assertFalse(redactSecrets("hello world").redacted)

        val key = redactSecrets("-----BEGIN RSA PRIVATE KEY-----\nMII\n-----END RSA PRIVATE KEY-----")
        assertTrue(key.redacted)
        assertEquals("[REDACTED]", key.text)
    }

    @Test
    fun `schema validator 接受合法参数且摘要与键序无关`() {
        val schema = ToolCallSchema("logs.query", windowSchema)
        val first = SchemaToolArgumentValidator.parse(schema, obj("""{"window":"post"}"""))
        val second = SchemaToolArgumentValidator.parse(schema, obj("""{"window":"post"}"""))
        assertEquals(first.digest, second.digest)
        assertTrue(first.digest.startsWith("sha256:"))
        assertEquals("logs.query", first.tool)
    }

    @Test
    fun `schema validator 收集缺字段与多余字段`() {
        try {
            SchemaToolArgumentValidator.parse(ToolCallSchema("logs.query", windowSchema), obj("""{"extra":true}"""))
            fail("缺少必需字段应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains(".window is required") })
            assertTrue(error.problems.any { it.contains(".extra is not allowed") })
            assertTrue(error.message!!.startsWith("invalid_tool_args"))
        }
    }

    @Test
    fun `schema validator 拒绝错误类型与枚举`() {
        try {
            SchemaToolArgumentValidator.parse(
                ToolCallSchema("logs.query", windowSchema),
                obj("""{"window":123}"""),
            )
            fail("错误类型应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains(".window expected string") })
        }

        try {
            SchemaToolArgumentValidator.parse(
                ToolCallSchema("logs.query", windowSchema),
                obj("""{"window":"mid"}"""),
            )
            fail("非法枚举应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("not one of the allowed values") })
        }
    }

    @Test
    fun `schema validator 支持 anyOf oneOf 数组与字符串约束`() {
        val anyOf = ToolCallSchema(
            "t",
            obj("""{"anyOf":[{"type":"array"},{"type":"string"}]}"""),
        )
        try {
            SchemaToolArgumentValidator.parse(anyOf, obj("{}"))
            fail("anyOf 不匹配应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("matches none of anyOf") })
        }
        assertEquals(
            "t",
            SchemaToolArgumentValidator.parse(
                ToolCallSchema("t", obj("""{"anyOf":[{"type":"object"},{"type":"array"}]}""")),
                obj("{}"),
            ).tool,
        )

        val oneOf = ToolCallSchema("t", obj("""{"oneOf":[{"type":"object"},{"type":"object"}]}"""))
        try {
            SchemaToolArgumentValidator.parse(oneOf, obj("{}"))
            fail("oneOf 命中两个分支应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("matches 2 of oneOf") })
        }

        val arraySchema = ToolCallSchema(
            "t",
            obj(
                """{"type":"object","properties":{"items":{"type":"array","items":{"type":"string"},""" +
                    """"maxItems":1}}}""",
            ),
        )
        try {
            SchemaToolArgumentValidator.parse(arraySchema, obj("""{"items":[1,2]}"""))
            fail("数组元素与长度应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("[0] expected string") })
            assertTrue(error.problems.any { it.contains("allows at most 1 items") })
        }

        val stringSchema = ToolCallSchema(
            "t",
            obj(
                """{"type":"object","properties":{"s":{"type":"string","minLength":3,""" +
                    """"pattern":"^a"}}}""",
            ),
        )
        try {
            SchemaToolArgumentValidator.parse(stringSchema, obj("""{"s":"b"}"""))
            fail("长度与模式应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("is shorter than 3") })
            assertTrue(error.problems.any { it.contains("does not match ^a") })
        }

        val constSchema = ToolCallSchema(
            "t",
            obj("""{"type":"object","properties":{"s":{"const":"post"}}}"""),
        )
        try {
            SchemaToolArgumentValidator.parse(constSchema, obj("""{"s":"pre"}"""))
            fail("const 不匹配应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("must equal the const value") })
        }
    }

    @Test
    fun `schema validator 拒绝非对象参数与空工具名`() {
        try {
            SchemaToolArgumentValidator.parse(ToolCallSchema("t", windowSchema), JsonValue.Str("x"))
            fail("参数必须是对象")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("arguments must be an object") })
        }

        try {
            SchemaToolArgumentValidator.parse(ToolCallSchema("", windowSchema), windowArgs)
            fail("空工具名应被拒绝")
        } catch (error: InvalidToolArgumentsException) {
            assertTrue(error.problems.any { it.contains("tool.name is empty") })
        }
    }

    @Test
    fun `tool call executor 校验参数并绑定摘要`() {
        val registry = ToolRegistry().register(windowDescriptor)
        var received: ToolInvocation? = null
        val executor = ToolCallExecutor(
            registry,
            ToolExecutor { invocation ->
                received = invocation
                ToolResult(true, "ok")
            },
        )
        val prepared = executor.prepare(ToolInvocation("logs.query", windowArgs))
        assertTrue(prepared.digest.startsWith("sha256:"))

        val result = executor.execute(ToolInvocation("logs.query", windowArgs), prepared.digest)
        assertTrue(result.ok)
        assertEquals("ok", result.content)
        assertEquals(prepared.digest, CanonicalJson.digest(received!!.args))
    }

    @Test
    fun `tool call executor 拒绝非法参数且不委托`() {
        val registry = ToolRegistry().register(windowDescriptor)
        var delegated = 0
        val executor = ToolCallExecutor(
            registry,
            ToolExecutor {
                delegated += 1
                ToolResult(true, "ok")
            },
        )
        val result = executor.execute(ToolInvocation("logs.query", obj("""{"window":"mid"}""")))
        assertFalse(result.ok)
        assertTrue(result.error!!.startsWith("invalid_tool_args"))
        assertEquals(0, delegated)
    }

    @Test
    fun `tool call executor 拒绝摘要不一致`() {
        val registry = ToolRegistry().register(windowDescriptor)
        val executor = ToolCallExecutor(registry, ToolExecutor { ToolResult(true, "ok") })
        val other = executor.prepare(ToolInvocation("logs.query", obj("""{"window":"post"}""")))
        val result = executor.execute(ToolInvocation("logs.query", windowArgs), other.digest)
        assertFalse(result.ok)
        assertTrue(result.error!!.startsWith("approval_digest_mismatch"))
    }

    @Test
    fun `tool call executor 应用策略与未知工具`() {
        val destructive = windowDescriptor.copy(risk = ToolRisk.DESTRUCTIVE)
        val registry = ToolRegistry().register(destructive)
        val executor = ToolCallExecutor(
            registry,
            ToolExecutor { ToolResult(true, "should not run") },
            policy = RiskToolPolicy(),
        )
        val denied = executor.execute(ToolInvocation("logs.query", windowArgs))
        assertFalse(denied.ok)
        assertEquals("tool logs.query requires approval", denied.error)

        val missing = executor.execute(ToolInvocation("missing", JsonValue.Obj()))
        assertFalse(missing.ok)
        assertEquals("unknown tool: missing", missing.error)

        try {
            executor.prepare(ToolInvocation("missing", JsonValue.Obj()))
            fail("prepare 未知工具应抛错")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("unknown tool"))
        }
    }

    @Test
    fun `tool call executor 可关闭参数校验`() {
        val registry = ToolRegistry().register(windowDescriptor)
        val executor = ToolCallExecutor(
            registry,
            ToolExecutor { ToolResult(true, "ok") },
            validateArgs = false,
        )
        val result = executor.execute(ToolInvocation("logs.query", obj("""{"window":"mid"}""")))
        assertTrue(result.ok)
    }

    @Test
    fun `mcp executor 注册工具并执行调用`() {
        val server = DemoServer()
        val transport = MemoryTransport { inner ->
            server.handle(inner) ?: JsonRpcResponse.success(inner.id, JsonValue.Null)
        }
        val client = McpClient(transport)
        val registry = ToolRegistry()
        val descriptors = registerMcpTools(registry, client)
        assertEquals(3, descriptors.size)
        assertEquals(ToolRisk.READ, descriptors.first { it.name == "logs.query" }.risk)
        assertEquals(ToolRisk.READ, descriptors.first { it.name == "metrics.query" }.risk)
        assertEquals(ToolRisk.DESTRUCTIVE, descriptors.first { it.name == "deploy.rollback" }.risk)
        assertEquals(3, registry.size)
        assertTrue(descriptors.first { it.name == "logs.query" }.description.startsWith("按时间窗口"))

        val executor = McpToolExecutor(client)
        val result = executor.execute(ToolInvocation("logs.query", obj("""{"window":"pre"}""")))
        assertTrue(result.ok)
        assertTrue(result.content.startsWith("timestamp,status,window,latency_ms"))
        assertNull(result.error)

        val failed = executor.execute(ToolInvocation("nope", JsonValue.Obj()))
        assertFalse(failed.ok)
        assertEquals("unknown tool: nope", failed.error)
        assertEquals("", failed.content)
    }

    @Test
    fun `mcp executor 用 title 回退描述并推导 write`() {
        val transport = MemoryTransport { inner ->
            JsonRpcResponse.success(
                inner.id,
                jsonObj(
                    "resultType" to JsonValue.Str("complete"),
                    "tools" to JsonValue.Arr(
                        listOf(
                            jsonObj(
                                "name" to JsonValue.Str("plain.tool"),
                                "title" to JsonValue.Str("标题"),
                                "inputSchema" to JsonValue.Obj(),
                            ),
                        ),
                    ),
                ),
            )
        }
        val registry = ToolRegistry()
        val descriptors = registerMcpTools(registry, McpClient(transport))
        assertEquals(1, descriptors.size)
        assertEquals("标题", descriptors[0].description)
        assertEquals(ToolRisk.WRITE, descriptors[0].risk)
    }
}
