package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.semantic.Sha256
import java.util.UUID

/**
 * 每个服务实例绑定一个受信宿主作用域（对应 `src/context/workspaceService.ts`）。
 *
 * Agent 调用不能注册 Frame、不能自带 snapshot、不能读取任意 hash、也不能扩大
 * 项目范围。工具名、参数与返回结构由 [WorkspaceProtocol] 定义。
 */
data class WorkspaceServiceLimits(
    val maxFrames: Int = 128,
    val maxContexts: Int = 32,
    val maxViewTokens: Int = 8000,
    val maxReadChars: Int = 8000,
    val maxResponseChars: Int = 131072,
    val maxToolCalls: Int = 1000,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "maxFrames" to jsonNumber(maxFrames),
        "maxContexts" to jsonNumber(maxContexts),
        "maxViewTokens" to jsonNumber(maxViewTokens),
        "maxReadChars" to jsonNumber(maxReadChars),
        "maxResponseChars" to jsonNumber(maxResponseChars),
        "maxToolCalls" to jsonNumber(maxToolCalls),
    )

    companion object {

        /** 只允许调低：每个上限必须在 `1..默认值` 内。 */
        fun validated(limits: WorkspaceServiceLimits): WorkspaceServiceLimits {
            val defaults = WorkspaceServiceLimits()
            require(limits.maxFrames in 1..defaults.maxFrames) { "invalid workspace service limit maxFrames" }
            require(limits.maxContexts in 1..defaults.maxContexts) { "invalid workspace service limit maxContexts" }
            require(limits.maxViewTokens in 1..defaults.maxViewTokens) { "invalid workspace service limit maxViewTokens" }
            require(limits.maxReadChars in 1..defaults.maxReadChars) { "invalid workspace service limit maxReadChars" }
            require(limits.maxResponseChars in 1..defaults.maxResponseChars) { "invalid workspace service limit maxResponseChars" }
            require(limits.maxToolCalls in 1..defaults.maxToolCalls) { "invalid workspace service limit maxToolCalls" }
            return limits
        }
    }
}

data class WorkspaceServiceOptions(
    val agentId: String,
    val projectIds: List<String>,
    val snapshot: WorkspaceSnapshot,
    val store: ContentStore,
    val limits: WorkspaceServiceLimits = WorkspaceServiceLimits(),
)

data class PendingSettlement(
    val contextId: String,
    val frameId: String,
    val estimatedTokens: Int,
)

private enum class AllocationState(val wire: String) {
    AVAILABLE("available"),
    MATERIALIZED("materialized"),
    RELEASED("released"),
    REVOKED("revoked"),
}

private class Allocation(
    val frame: ContextFrame,
    var state: AllocationState,
    var contextId: String?,
    var remainingTokens: Int,
)

private class Materialization(
    val context: Context,
    val allocation: Allocation,
    val readable: List<String>,
    val response: WorkspaceResponse.Success,
    val requestedTokens: Int,
)

/** 宿主 API（注册/撤销/结算）失败时抛出；工具调用则被 `call` 映射成响应错误。 */
class WorkspaceServiceException(val code: WorkspaceErrorCode) :
    IllegalStateException(code.wire)

private fun fail(code: WorkspaceErrorCode): Nothing = throw WorkspaceServiceException(code)

class WorkspaceAgentService(options: WorkspaceServiceOptions) {

    private val agentId: String
    private val index: WorkspaceIndex
    private val limits: WorkspaceServiceLimits
    private val frames: MutableMap<String, Allocation> = LinkedHashMap()
    private val contexts: MutableMap<String, Materialization> = LinkedHashMap()
    private val retired: MutableMap<String, Materialization> = LinkedHashMap()
    private val roots: List<WorkspaceEntity>
    private val definitions: List<WorkspaceToolDefinition> = workspaceToolDefinitions()
    private var calls: Int = 0

    init {
        require(options.agentId.isNotEmpty()) {
            "workspace service requires a host agent and explicit project scope"
        }
        agentId = options.agentId
        limits = WorkspaceServiceLimits.validated(options.limits)
        val projects: Set<String> = options.projectIds.toSet()
        projects.forEach { id ->
            val known: Boolean = options.snapshot.entities.any {
                it.id == id && it.kind == WorkspaceEntityKind.PROJECT
            }
            require(known) { "unknown authorized project" }
        }
        val entities: List<WorkspaceEntity> =
            options.snapshot.entities.filter { projects.contains(it.projectId) }
        val ids: Set<String> = entities.map { it.id }.toSet()
        val rawStore: ContentStore = options.store
        val verifiedStore: ContentStore = object : ContentStore {

            override fun put(content: String): String = fail(WorkspaceErrorCode.INTERNAL_ERROR)

            override fun get(ref: String): String? {
                try {
                    if (!CONTENT_REF_PATTERN.matches(ref)) {
                        return fail(WorkspaceErrorCode.INTEGRITY_FAILURE)
                    }
                    val content: String? = rawStore.get(ref)
                    if (content != null && "sha256:" + Sha256.hex(content) != ref) {
                        return fail(WorkspaceErrorCode.INTEGRITY_FAILURE)
                    }
                    return content
                } catch (error: WorkspaceServiceException) {
                    throw error
                } catch (error: Exception) {
                    return fail(WorkspaceErrorCode.INTEGRITY_FAILURE)
                }
            }
        }
        index = WorkspaceIndex(
            WorkspaceSnapshot(
                revision = options.snapshot.revision,
                entities = entities,
                relations = options.snapshot.relations.filter { ids.contains(it.from) && ids.contains(it.to) },
                diagnostics = emptyList(),
                version = options.snapshot.version,
            ),
            verifiedStore,
        )
        roots = index.snapshot().entities.filter { it.kind == WorkspaceEntityKind.PROJECT }
    }

    /**
     * 仅宿主可注册。Frame 显式绑定到本 snapshot 的 revision；其 digest 仍是
     * AMP Frame 的 digest，而不是 workspace 的 revision 字段。
     */
    fun registerFrame(frame: JsonValue, revision: String) {
        if (revision != index.revision) {
            fail(WorkspaceErrorCode.REVISION_MISMATCH)
        }
        val parsed: ContextFrame = try {
            ContextFrame.fromJson(frame)
        } catch (error: IllegalArgumentException) {
            fail(WorkspaceErrorCode.INVALID_ARGUMENTS)
        }
        if (parsed.id.isEmpty() ||
            parsed.agent != agentId ||
            parsed.slots.size > 9 ||
            parsed.allocatedTokens < 1 ||
            parsed.spentTokens < 0 ||
            parsed.spentTokens > parsed.allocatedTokens ||
            parsed.slots.any { slot -> slot.why.isBlank() || index.entity(slot.target.id) == null }
        ) {
            fail(WorkspaceErrorCode.INVALID_ARGUMENTS)
        }
        if (frames.containsKey(parsed.id) || frames.size >= limits.maxFrames) {
            fail(WorkspaceErrorCode.CAPACITY_EXCEEDED)
        }
        frames[parsed.id] = Allocation(
            frame = parsed,
            state = AllocationState.AVAILABLE,
            contextId = null,
            remainingTokens = parsed.allocatedTokens - parsed.spentTokens,
        )
    }

    /** 撤销会销毁任何活动 handle，Agent 无法反向恢复。 */
    fun revokeFrame(frameId: String) {
        val allocation: Allocation = frames[frameId] ?: fail(WorkspaceErrorCode.FRAME_UNAVAILABLE)
        allocation.contextId?.let { contextId ->
            contexts[contextId]?.let { retired[contextId] = it }
            contexts.remove(contextId)
        }
        allocation.state = AllocationState.REVOKED
    }

    /** 只有宿主能证明真实结果并结算 action Receipt；结算会移除模型的细节句柄。 */
    fun settle(contextId: String, receiptId: String): ContextSnapshot {
        val item: Materialization = contexts[contextId] ?: retired[contextId]
            ?: fail(WorkspaceErrorCode.NOT_AVAILABLE)
        item.context.settle(receiptId)
        item.context.compact()
        item.context.archive()
        val snapshot: ContextSnapshot = item.context.snapshot()
        if (item.allocation.state != AllocationState.REVOKED) {
            item.allocation.state = AllocationState.RELEASED
        }
        contexts.remove(contextId)
        retired.remove(contextId)
        return snapshot
    }

    /** 宿主对账在进程内跨过 Agent 的 release/revocation 仍可见。 */
    fun pendingSettlements(): List<PendingSettlement> {
        val result: MutableList<PendingSettlement> = ArrayList()
        (contexts.entries + retired.entries).forEach { (contextId, item) ->
            val frame: ContextFrame = item.allocation.frame
            result.add(
                PendingSettlement(
                    contextId = contextId,
                    frameId = frame.id,
                    estimatedTokens = frame.allocatedTokens - frame.spentTokens - item.allocation.remainingTokens,
                ),
            )
        }
        return result
    }

    fun tools(): List<WorkspaceToolDefinition> = definitions

    fun call(name: String, raw: JsonValue?): WorkspaceResponse {
        try {
            if (calls >= limits.maxToolCalls) {
                return fail(WorkspaceErrorCode.BUDGET_EXHAUSTED)
            }
            calls += 1
            val tool: WorkspaceToolDefinition = definitions.firstOrNull { it.name == name }
                ?: return fail(WorkspaceErrorCode.UNKNOWN_TOOL)
            val args: JsonValue.Obj = parseWorkspaceToolCall(tool, raw)
            if (name != "amp_workspace_capabilities" && args.string("revision") != index.revision) {
                return fail(WorkspaceErrorCode.REVISION_MISMATCH)
            }
            return dispatch(name, args)
        } catch (error: WorkspaceServiceException) {
            return WorkspaceResponse.Failure(index.revision, error.code)
        } catch (error: WorkspaceArgumentException) {
            return WorkspaceResponse.Failure(index.revision, WorkspaceErrorCode.INVALID_ARGUMENTS)
        } catch (error: Exception) {
            return WorkspaceResponse.Failure(index.revision, WorkspaceErrorCode.INTERNAL_ERROR)
        }
    }

    private fun reply(data: JsonValue.Obj): WorkspaceResponse.Success {
        val result = WorkspaceResponse.Success(index.revision, data)
        if (CanonicalJson.encode(result.toJson()).length > limits.maxResponseChars) {
            fail(WorkspaceErrorCode.RESPONSE_TOO_LARGE)
        }
        return result
    }

    private fun requireEntity(id: String): WorkspaceEntity =
        index.entity(id) ?: fail(WorkspaceErrorCode.NOT_AVAILABLE)

    private fun dispatch(name: String, args: JsonValue.Obj): WorkspaceResponse {
        if (name == "amp_workspace_capabilities") {
            val framesJson: List<JsonValue> = frames.values
                .filter { it.state == AllocationState.AVAILABLE || it.state == AllocationState.MATERIALIZED }
                .map { item ->
                    jsonObj(
                        "frameId" to JsonValue.Str(item.frame.id),
                        "state" to JsonValue.Str(item.state.wire),
                        "remainingTokens" to jsonNumber(item.remainingTokens),
                    )
                }
            return reply(
                jsonObj(
                    "agentId" to JsonValue.Str(agentId),
                    "projectIds" to jsonArr(*roots.map { JsonValue.Str(it.id) }.toTypedArray()),
                    "frames" to jsonArr(*framesJson.toTypedArray()),
                    "limits" to limits.toJson(),
                    "remainingToolCalls" to jsonNumber(limits.maxToolCalls - calls),
                    "tools" to jsonArr(*definitions.map { JsonValue.Str(it.name) }.toTypedArray()),
                    "tokenAccounting" to JsonValue.Str(
                        "character-estimate; summary text and detail pages only; discovery and JSON envelope excluded",
                    ),
                ),
            )
        }
        if (name == "amp_workspace_tree") {
            val parentId: String? = args.string("parentId")
            if (parentId != null) {
                requireEntity(parentId)
            }
            val all: List<WorkspaceEntity> = if (parentId == null) roots else index.children(parentId)
            val page: JsonValue.Obj = pageJson(
                all = all,
                offset = args.int("offset") ?: 0,
                limit = args.int("limit") ?: 32,
                render = { entity ->
                    jsonObj(
                        "id" to JsonValue.Str(entity.id),
                        "kind" to JsonValue.Str(entity.kind.wire),
                        "label" to JsonValue.Str(entity.label),
                        "parentId" to entity.parentId?.let { JsonValue.Str(it) },
                        "structureKind" to entity.structureKind?.let { JsonValue.Str(it.wire) },
                        "source" to entity.source.toJson(),
                        "hasDetail" to JsonValue.Bool(entity.detailRef != null),
                        "summaryState" to JsonValue.Str(index.summaryState(entity.id).wire),
                    )
                },
            )
            return reply(page)
        }
        if (name == "amp_workspace_query") {
            val query = WorkspaceQuery(
                seedIds = args.strings("seedIds"),
                text = args.string("text"),
                projectIds = args.strings("projectIds"),
                relationKinds = args.strings("relationKinds")?.map { WorkspaceRelationKind.fromWire(it) },
                direction = args.string("direction")?.let { QueryDirection.fromWire(it) } ?: QueryDirection.BOTH,
                maxDepth = args.int("maxDepth"),
                limit = args.int("limit"),
            )
            return reply(index.query(query).toJson())
        }
        if (name == "amp_workspace_relations") {
            val nodeId: String = args.requireString("nodeId")
            requireEntity(nodeId)
            val all: List<WorkspaceRelation> = index.relations(
                nodeId,
                EdgeDirection.fromWire(args.requireString("direction")),
                args.string("relationKind")?.let { WorkspaceRelationKind.fromWire(it) },
            )
            return reply(
                pageJson(
                    all = all,
                    offset = args.int("offset") ?: 0,
                    limit = args.int("limit") ?: 32,
                    render = { edge -> edge.toJson() },
                ),
            )
        }
        if (name == "amp_workspace_materialize") {
            return materialize(
                frameId = args.requireString("frameId"),
                budgetTokens = args.requireInt("budgetTokens"),
            )
        }
        val contextId: String = args.requireString("contextId")
        val item: Materialization = contexts[contextId] ?: fail(WorkspaceErrorCode.NOT_AVAILABLE)
        if (name == "amp_workspace_release") {
            val response: WorkspaceResponse = reply(
                jsonObj(
                    "released" to JsonValue.Bool(true),
                    "frameId" to JsonValue.Str(item.allocation.frame.id),
                    "remainingTokens" to jsonNumber(item.allocation.remainingTokens),
                ),
            )
            contexts.remove(contextId)
            retired[contextId] = item
            item.allocation.state = AllocationState.RELEASED
            return response
        }
        return read(item, args)
    }

    /** 有界分页；`nextOffset` 为 null 表示没有下一页。 */
    private fun <T> pageJson(
        all: List<T>,
        offset: Int,
        limit: Int,
        render: (T) -> JsonValue,
    ): JsonValue.Obj {
        val total: Int = all.size
        val start: Long = offset.toLong().coerceAtLeast(0L)
        val end: Long = minOf(total.toLong(), start + limit.toLong().coerceAtLeast(0L))
        val items: List<JsonValue> = if (start >= total) {
            emptyList()
        } else {
            all.subList(start.toInt(), end.toInt()).map(render)
        }
        return jsonObj(
            "items" to jsonArr(*items.toTypedArray()),
            "total" to jsonNumber(total),
            "nextOffset" to if (end < total) jsonNumber(end) else JsonValue.Null,
        )
    }

    private fun materialize(frameId: String, budgetTokens: Int): WorkspaceResponse {
        val allocation: Allocation = frames[frameId] ?: fail(WorkspaceErrorCode.FRAME_UNAVAILABLE)
        if (allocation.state == AllocationState.RELEASED || allocation.state == AllocationState.REVOKED) {
            return fail(WorkspaceErrorCode.FRAME_UNAVAILABLE)
        }
        val existingId: String? = allocation.contextId
        if (existingId != null) {
            val existing: Materialization = contexts[existingId] ?: fail(WorkspaceErrorCode.NOT_AVAILABLE)
            if (existing.requestedTokens != budgetTokens) {
                return fail(WorkspaceErrorCode.INVALID_ARGUMENTS)
            }
            val data: JsonValue.Obj = existing.response.data.copyWith(
                "remainingTokens",
                jsonNumber(allocation.remainingTokens),
            )
            return WorkspaceResponse.Success(index.revision, data)
        }
        if (contexts.size >= limits.maxContexts) {
            return fail(WorkspaceErrorCode.CAPACITY_EXCEEDED)
        }
        val cap: Int = minOf(budgetTokens, limits.maxViewTokens, allocation.remainingTokens)
        if (cap <= 0) {
            return fail(WorkspaceErrorCode.BUDGET_EXHAUSTED)
        }
        val context: Context = index.context(allocation.frame, cap)
        val view: ContextView = context.view ?: fail(WorkspaceErrorCode.INTERNAL_ERROR)
        val included: Set<String> = view.nodes.map { it.id }.toHashSet()
        val readable: List<String> = allocation.frame.slots
            .filter { it.state == SlotState.ACTIVE && included.contains(it.target.id) }
            .map { it.target.id }
        val contextId: String = "ctx." + UUID.randomUUID()
        val response: WorkspaceResponse.Success = reply(
            jsonObj(
                "contextId" to JsonValue.Str(contextId),
                "frameId" to JsonValue.Str(frameId),
                "text" to JsonValue.Str(view.text),
                "tokens" to jsonNumber(view.tokens),
                "budgetTokens" to jsonNumber(cap),
                "remainingTokens" to jsonNumber(allocation.remainingTokens - view.tokens),
                "includedIds" to jsonArr(*included.map { JsonValue.Str(it) }.toTypedArray()),
                "readableIds" to jsonArr(*readable.map { JsonValue.Str(it) }.toTypedArray()),
                "deferredIds" to jsonArr(*view.deferred.map { JsonValue.Str(it.id) }.toTypedArray()),
                "missingRefs" to jsonArr(*view.missingRefs.map { it.toJson() }.toTypedArray()),
                "relations" to jsonArr(*view.relations.map { it.toJson() }.toTypedArray()),
                "unresolvedDependencies" to jsonArr(*view.unresolvedDependencies.map { it.toJson() }.toTypedArray()),
                "omissions" to jsonArr(*view.omissions.map { it.toJson() }.toTypedArray()),
            ),
        )
        allocation.remainingTokens -= view.tokens
        allocation.state = AllocationState.MATERIALIZED
        allocation.contextId = contextId
        contexts[contextId] = Materialization(
            context = context,
            allocation = allocation,
            readable = readable,
            response = response,
            requestedTokens = budgetTokens,
        )
        return response
    }

    private fun read(item: Materialization, args: JsonValue.Obj): WorkspaceResponse {
        val nodeId: String = args.requireString("nodeId")
        if (!item.readable.contains(nodeId)) {
            return fail(WorkspaceErrorCode.NOT_AVAILABLE)
        }
        val remaining: Int = item.allocation.remainingTokens
        if (remaining <= 0) {
            return fail(WorkspaceErrorCode.BUDGET_EXHAUSTED)
        }
        val content: String = item.context.expand(nodeId) ?: fail(WorkspaceErrorCode.NOT_AVAILABLE)
        val offset: Int = args.int("offset") ?: 0
        if (offset < 0 || offset > content.length) {
            return fail(WorkspaceErrorCode.INVALID_ARGUMENTS)
        }
        val size: Int = minOf(
            args.int("maxChars") ?: limits.maxReadChars,
            limits.maxReadChars,
            remaining * 4,
        )
        val end: Int = minOf(content.length, offset + size)
        val text: String = content.substring(offset, end)
        val tokens: Int = if (text.isEmpty()) 0 else estimateTokens(text)
        val response: WorkspaceResponse = reply(
            jsonObj(
                "nodeId" to JsonValue.Str(nodeId),
                "source" to requireEntity(nodeId).source.toJson(),
                "offset" to jsonNumber(offset),
                "text" to JsonValue.Str(text),
                "nextOffset" to if (offset + text.length < content.length) {
                    jsonNumber(offset + text.length)
                } else {
                    JsonValue.Null
                },
                "totalChars" to jsonNumber(content.length),
                "tokens" to jsonNumber(tokens),
                "remainingTokens" to jsonNumber(remaining - tokens),
            ),
        )
        item.allocation.remainingTokens -= tokens
        return response
    }
}

/** `string[]` 参数；缺失返回 null（而不是空列表），以保留「未提供」语义。 */
private fun JsonValue.Obj.strings(key: String): List<String>? {
    val array: JsonValue.Arr = this[key] as? JsonValue.Arr ?: return null
    return array.items.map { item ->
        (item as? JsonValue.Str)?.value
            ?: throw WorkspaceArgumentException("$key must contain strings")
    }
}
