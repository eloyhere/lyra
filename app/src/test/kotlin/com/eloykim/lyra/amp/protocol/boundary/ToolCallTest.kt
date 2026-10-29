package com.eloykim.lyra.amp.protocol.boundary

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolCallTest {

    private val schema: ToolSchema = ToolSchema(
        name = "logs.query",
        inputSchema = JsonParser.parse(
            """
            {
              "type": "object",
              "required": ["path"],
              "additionalProperties": false,
              "properties": {
                "path": { "type": "string", "minLength": 1, "pattern": "^/" },
                "mode": { "type": "string", "enum": ["ro", "rw"] },
                "limit": { "type": "integer", "minimum": 1, "maximum": 100 },
                "tags": {
                  "type": "array",
                  "items": { "type": "string" },
                  "maxItems": 2
                },
                "nested": {
                  "type": "object",
                  "required": ["x"],
                  "properties": { "x": { "const": 7 } },
                  "additionalProperties": false
                }
              }
            }
            """.trimIndent(),
        ),
    )

    private fun args(text: String): JsonValue = JsonParser.parse(text.trimIndent())

    private fun reject(text: String): BoundaryRejection =
        assertThrows(BoundaryRejection::class.java) {
            parseToolCall(schema, args(text))
        }

    @Test
    fun `接受的参数产生 canonical digest`() {
        val raw = args(
            """
            {
              "path": "/var/log/app.log",
              "mode": "ro",
              "limit": 10,
              "tags": ["error"],
              "nested": { "x": 7 }
            }
            """,
        )
        val parsed = parseToolCall(schema, raw)
        assertEquals("logs.query", parsed.tool)
        assertEquals(CanonicalJson.encode(raw), CanonicalJson.encode(parsed.args))
        assertEquals(CanonicalJson.digest(raw), parsed.digest)
    }

    @Test
    fun `必填字段与额外字段被拒绝`() {
        val missing = reject("""{"mode":"ro"}""")
        assertEquals("invalid_tool_args", missing.code)
        assertEquals("$.path is required", missing.problems.single())
        assertEquals("$.oops is not allowed", reject("""{"path":"/x","oops":true}""").problems.single())
    }

    @Test
    fun `类型、pattern 与 enum 被拒绝`() {
        val wrongType = reject("""{"path":5}""")
        assertEquals("invalid_tool_args", wrongType.code)
        assertEquals("$.path expected string", wrongType.problems.single())

        assertEquals("$.path does not match ^/", reject("""{"path":"tmp"}""").problems.single())
        assertEquals("$.mode is not one of the allowed values", reject("""{"path":"/x","mode":"rwx"}""").problems.single())
    }

    @Test
    fun `数值与数组边界被拒绝`() {
        assertEquals("$.limit is below 1", reject("""{"path":"/x","limit":0}""").problems.single())
        assertEquals("$.limit is above 100", reject("""{"path":"/x","limit":101}""").problems.single())
        assertEquals("$.tags allows at most 2 items", reject("""{"path":"/x","tags":["a","b","c"]}""").problems.single())
        assertEquals("$.tags[0] expected string", reject("""{"path":"/x","tags":[1]}""").problems.single())
    }

    @Test
    fun `嵌套对象仍校验 required、const 与 additionalProperties`() {
        assertEquals("$.nested.x is required", reject("""{"path":"/x","nested":{}}""").problems.single())
        assertEquals("$.nested.x must equal the const value", reject("""{"path":"/x","nested":{"x":8}}""").problems.single())
        assertEquals("$.nested.y is not allowed", reject("""{"path":"/x","nested":{"x":7,"y":1}}""").problems.single())
    }

    @Test
    fun `oneOf 必须恰好命中一个分支`() {
        val oneOf = ToolSchema(
            "one",
            args("""{"type":"object","properties":{"v":{"oneOf":[{"type":"number"},{"type":"integer"}]}}}"""),
        )
        val error = assertThrows(BoundaryRejection::class.java) {
            parseToolCall(oneOf, args("""{"v":7}"""))
        }
        assertEquals("$.v matches 2 of oneOf, expected exactly 1", error.problems.single())
    }

    @Test
    fun `anyOf 与 allOf 与 const`() {
        val anyOf = ToolSchema(
            "any",
            args("""{"type":"object","properties":{"v":{"anyOf":[{"type":"string"},{"type":"boolean"}]}}}"""),
        )
        assertEquals(
            "$.v matches none of anyOf",
            assertThrows(BoundaryRejection::class.java) {
                parseToolCall(anyOf, args("""{"v":7}"""))
            }.problems.single(),
        )

        val allOf = ToolSchema(
            "all",
            args("""{"type":"object","properties":{"v":{"allOf":[{"minimum":5},{"maximum":10}]}}}"""),
        )
        assertEquals(
            "$.v is below 5",
            assertThrows(BoundaryRejection::class.java) {
                parseToolCall(allOf, args("""{"v":3}"""))
            }.problems.single(),
        )

        val constant = ToolSchema(
            "const",
            args("""{"type":"object","properties":{"v":{"const":7}}}"""),
        )
        assertEquals(
            "$.v must equal the const value",
            assertThrows(BoundaryRejection::class.java) {
                parseToolCall(constant, args("""{"v":8}"""))
            }.problems.single(),
        )
    }

    @Test
    fun `工具名与参数根对象先被校验`() {
        val emptyName = assertThrows(BoundaryRejection::class.java) {
            parseToolCall(ToolSchema("", schema.inputSchema), args("""{"path":"/x"}"""))
        }
        assertEquals("tool.name is empty", emptyName.problems.single())

        val notObject = assertThrows(BoundaryRejection::class.java) {
            parseToolCall(schema, args("[]"))
        }
        assertEquals("arguments must be an object", notObject.problems.single())

        val badSchema = assertThrows(BoundaryRejection::class.java) {
            parseToolCall(ToolSchema("bad", JsonValue.Str("not-a-schema")), args("{}"))
        }
        assertEquals("$: schema must be an object", badSchema.problems.single())
    }
}
