package com.eloykim.lyra.amp.protocol.execution

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonText
import com.eloykim.lyra.amp.protocol.module.McpTool
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Digest
import com.eloykim.lyra.amp.protocol.types.asObject

/**
 * 逐工具目录钉扎（`src/execution/pin.ts` 的忠实移植）。
 *
 * `ExecProfile` 里的整目录摘要适合发现“列表变了”，但它在 connect/refresh 时才
 * 检查，TOCTOU 窗口依然存在：服务器可能在该检查与真正使用 schema 校验参数之间
 * 改掉一个工具。这些辅助函数把钉扎显式到每个工具，让宿主在调用前立刻重新断言。
 *
 * `digestTool` 覆盖影响模型所见与参数校验的字段：name、title、description、
 * inputSchema 与 annotations。它**不**覆盖 MCP 的 output schema（不用于输入校验）。
 */

/**
 * 工具集钉扎。
 *
 * `catalog` 按名字排序后计算；MCP 只 SHOULD 返回确定性顺序，AMP 不能把签名或
 * 缓存建立在服务器控制的顺序上（架构 §10.6），所以同一批工具重排不算漂移。
 */
data class ToolPins(
    val catalog: Digest,
    /** 工具名 → 单工具摘要。 */
    val tools: Map<String, Digest>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "catalog" to jsonText(catalog),
        "tools" to JsonValue.Obj(
            LinkedHashMap(tools.mapValues { (_, digest) -> jsonText(digest) as JsonValue }),
        ),
    )

    companion object {
        fun fromJson(value: JsonValue): ToolPins {
            val obj = value.asObject("tool pins")
            val tools = obj.requireObj("tools")
            val parsed = LinkedHashMap<String, Digest>()
            tools.fields.forEach { (name, digest) ->
                parsed[name] = (digest as? JsonValue.Str)?.value
                    ?: throw IllegalArgumentException("tool pins.tools 的值必须是字符串")
            }
            return ToolPins(catalog = obj.requireString("catalog"), tools = parsed)
        }
    }
}

/** 钉扎比较结果。 */
data class PinVerdict(
    val ok: Boolean,
    val reasons: List<String>,
    /** 摘要变化的名字，加上新增/移除的名字。 */
    val changed: List<String>,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "ok" to JsonValue.Bool(ok),
        "reasons" to JsonValue.Arr(reasons.map { JsonValue.Str(it) }),
        "changed" to JsonValue.Arr(changed.map { JsonValue.Str(it) }),
    )

    companion object {
        fun fromJson(value: JsonValue): PinVerdict {
            val obj = value.asObject("pin verdict")
            return PinVerdict(
                ok = obj.bool("ok") ?: throw IllegalArgumentException("缺少布尔字段 ok"),
                reasons = obj.requireArr("reasons").items.map { item ->
                    (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("pin verdict.reasons 必须是字符串数组")
                },
                changed = obj.requireArr("changed").items.map { item ->
                    (item as? JsonValue.Str)?.value ?: throw IllegalArgumentException("pin verdict.changed 必须是字符串数组")
                },
            )
        }
    }
}

/**
 * 单个工具的稳定摘要，包含 description 与 input schema。
 *
 * 注意：参考实现的 `canonical()` 不省略值为 `undefined` 的键，而是把它写成
 * `null`。工具对象字面量始终有 title/description/annotations 三个键，因此缺失时
 * 必须显式写入 [JsonValue.Null]（annotations 缺失时写 `{}`），否则摘要与 Node
 * 侧不一致。
 */
fun digestTool(tool: McpTool): Digest {
    val body = JsonValue.Obj(
        linkedMapOf(
            "name" to JsonValue.Str(tool.name),
            "title" to (tool.title?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "description" to (tool.description?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "inputSchema" to tool.inputSchema,
            "annotations" to (tool.annotations ?: JsonValue.Obj()),
        ),
    )
    return CanonicalJson.digest(body)
}

/**
 * 从 `tools/list` 结果构建钉扎集。重复名字 fail closed。
 */
fun pinTools(tools: List<McpTool>): ToolPins {
    val pinned = LinkedHashMap<String, Digest>()
    tools.forEach { tool ->
        if (pinned.containsKey(tool.name)) {
            throw IllegalArgumentException("duplicate tool name in catalog: ${tool.name}")
        }
        pinned[tool.name] = digestTool(tool)
    }
    return ToolPins(catalog = catalogDigest(tools), tools = pinned)
}

/**
 * 工具目录的顺序无关摘要。
 *
 * 使用解析后的工具正文（[McpTool.toJson]），与参考实现对 `tools/list` 原始对象
 * 做 canonical 摘要一致：JSON 中缺席的可选键不会凭空出现。
 */
private fun catalogDigest(tools: List<McpTool>): Digest {
    val sorted = tools.sortedBy { it.name }
    return CanonicalJson.digest(JsonValue.Arr(sorted.map { it.toJson() }))
}

/**
 * 把新工具列表与旧钉扎集比较。
 *
 * 目录摘要捕捉重排/移除；逐工具比较捕捉列表形状不变时的 description 或 schema
 * 变化。
 */
fun verifyToolPins(pins: ToolPins, tools: List<McpTool>): PinVerdict {
    val reasons = ArrayList<String>()
    val changed = LinkedHashSet<String>()
    val current = LinkedHashMap<String, McpTool>()

    tools.forEach { tool ->
        if (current.containsKey(tool.name)) {
            reasons += "duplicate tool name: ${tool.name}"
            changed += tool.name
            return@forEach
        }
        current[tool.name] = tool
    }

    pins.tools.forEach { (name, digest) ->
        val tool = current[name]
        if (tool == null) {
            reasons += "tool removed: $name"
            changed += name
            return@forEach
        }
        val next = digestTool(tool)
        if (next != digest) {
            reasons += "tool changed: $name $digest -> $next"
            changed += name
        }
    }

    current.keys.forEach { name ->
        if (!pins.tools.containsKey(name)) {
            reasons += "tool added: $name"
            changed += name
        }
    }

    if (catalogDigest(tools) != pins.catalog) {
        reasons += "catalog digest changed"
    }

    return PinVerdict(ok = reasons.isEmpty(), reasons = reasons, changed = changed.sorted())
}

/**
 * 断言请求的工具仍然精确等于被钉扎的工具。
 *
 * 宿主可以用它校验即将用于参数校验的 schema；缺失钉扎是失败，绝不是隐式的信任
 * 决定。
 */
fun assertToolPinned(pins: ToolPins, tool: McpTool): PinVerdict {
    val expected = pins.tools[tool.name]
        ?: return PinVerdict(ok = false, reasons = listOf("tool not pinned: ${tool.name}"), changed = listOf(tool.name))
    val actual = digestTool(tool)
    if (actual != expected) {
        return PinVerdict(
            ok = false,
            reasons = listOf("tool changed: ${tool.name} $expected -> $actual"),
            changed = listOf(tool.name),
        )
    }
    return PinVerdict(ok = true, reasons = emptyList(), changed = emptyList())
}
