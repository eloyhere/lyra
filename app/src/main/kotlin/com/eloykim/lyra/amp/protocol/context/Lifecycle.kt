package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.types.Ref
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 唯一的 Context 生命周期对象（对应 `src/context/lifecycle.ts`）。
 *
 * Frame 分配；Context 物化；Receipt 结算。Context 从不选择新的目标，也从不修改
 * Frame。Frame 的「视图」只保留 Context 实际会观察到的字段（id / agent / slots /
 * budget），它由 AMP Frame 的线上对象解析而来；需要完整 Frame 对象时由上层的
 * frame 模块适配。
 */
enum class SlotState(val wire: String) {
    ACTIVE("active"),
    RELEASED("released"),
    EVICTED("evicted"),
    DONE("done");

    companion object {
        fun fromWire(value: String): SlotState =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown slot state: $value")
    }
}

data class ContextSlot(
    val target: Ref,
    val state: SlotState,
    val why: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "target" to target.toJson(),
        "state" to JsonValue.Str(state.wire),
        "why" to JsonValue.Str(why),
    )

    companion object {
        fun fromJson(value: JsonValue): ContextSlot {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("frame slot 必须是 JSON 对象")
            val target = obj.obj("target") ?: throw IllegalArgumentException("缺少 frame slot target")
            return ContextSlot(
                target = Ref.fromJson(target),
                state = SlotState.fromWire(obj.requireString("state")),
                why = obj.requireString("why"),
            )
        }
    }
}

/**
 * Context 观察到的 Frame 子集；由 `amp:"0.1"` / `kind:"frame"` 的线上对象解析。
 * 所有字段不可变，因此构造时捕获的就是一份不会被外部改动的分配快照。
 */
data class ContextFrame(
    val id: String,
    val agent: String,
    val slots: List<ContextSlot>,
    val allocatedTokens: Int,
    val spentTokens: Int = 0,
    val amp: String = "0.1",
    val kind: String = "frame",
) {

    val remainingTokens: Int get() = allocatedTokens - spentTokens

    fun toJson(): JsonValue.Obj = jsonObj(
        "amp" to JsonValue.Str(amp),
        "kind" to JsonValue.Str(kind),
        "id" to JsonValue.Str(id),
        "agent" to JsonValue.Str(agent),
        "slots" to jsonArr(*slots.map { it.toJson() }.toTypedArray()),
        "budget" to jsonObj(
            "allocated" to jsonObj("tokens" to jsonNumber(allocatedTokens)),
            "spent" to jsonObj("tokens" to jsonNumber(spentTokens)),
        ),
    )

    companion object {
        fun fromJson(value: JsonValue): ContextFrame {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("frame 必须是 JSON 对象")
            if (obj.string("amp") != "0.1") {
                throw IllegalArgumentException("unsupported amp version")
            }
            if (obj.string("kind") != "frame") {
                throw IllegalArgumentException("not a frame")
            }
            val id: String = obj.requireString("id")
            val agent: String = obj.requireString("agent")
            val slots: List<ContextSlot> = obj.requireArr("slots").items.map(ContextSlot::fromJson)
            val budget = obj.obj("budget") ?: throw IllegalArgumentException("缺少 frame budget")
            val allocated = budget.obj("allocated") ?: throw IllegalArgumentException("缺少 frame budget.allocated")
            val allocatedTokens: Int = allocated.exactInt("tokens")
                ?: throw IllegalArgumentException("frame budget.allocated.tokens 必须是整数")
            val spentTokens: Int = if (budget.obj("spent") == null) 0 else {
                budget.requireObj("spent").exactInt("tokens")
                    ?: throw IllegalArgumentException("frame budget.spent.tokens 必须是整数")
            }
            return ContextFrame(id, agent, slots, allocatedTokens, spentTokens)
        }
    }
}

/** 仅接受整数值的 JSON number；`1000.5` 与缺失一律拒绝。 */
private fun JsonValue.Obj.exactInt(key: String): Int? {
    val number: Double = (fields[key] as? JsonValue.Num)?.value ?: return null
    if (!number.isFinite() || number != Math.floor(number)) {
        return null
    }
    if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
        return null
    }
    return number.toInt()
}

enum class ContextPhase(val wire: String) {
    CREATED("created"),
    MATERIALIZED("materialized"),
    ACTIVE("active"),
    SETTLED("settled"),
    COMPACTED("compacted"),
    ARCHIVED("archived"),
}

data class ContextView(
    val frameId: String,
    val nodes: List<ContextAstNode>,
    val tokens: Int,
    val budgetTokens: Int,
    val missingRefs: List<Ref>,
    val deferred: List<ContextAstNode>,
    val refs: List<String>,
    val relations: List<ContextAstEdge>,
    val unresolvedDependencies: List<ContextAstEdge>,
    val omissions: List<ContextOmission>,
    val text: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "frameId" to JsonValue.Str(frameId),
        "nodes" to jsonArr(*nodes.map { it.toJson() }.toTypedArray()),
        "tokens" to jsonNumber(tokens),
        "budgetTokens" to jsonNumber(budgetTokens),
        "missingRefs" to jsonArr(*missingRefs.map { it.toJson() }.toTypedArray()),
        "deferred" to jsonArr(*deferred.map { it.toJson() }.toTypedArray()),
        "refs" to jsonArr(*refs.map { JsonValue.Str(it) }.toTypedArray()),
        "relations" to jsonArr(*relations.map { it.toJson() }.toTypedArray()),
        "unresolvedDependencies" to jsonArr(*unresolvedDependencies.map { it.toJson() }.toTypedArray()),
        "omissions" to jsonArr(*omissions.map { it.toJson() }.toTypedArray()),
        "text" to JsonValue.Str(text),
    )
}

data class ContextSnapshot(
    val id: String,
    val frameId: String,
    val phase: ContextPhase,
    val createdAt: String,
    val materializedAt: String? = null,
    val settledAt: String? = null,
    val compactedAt: String? = null,
    val archivedAt: String? = null,
    val receiptIds: List<String> = emptyList(),
    val view: ContextView? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "frameId" to JsonValue.Str(frameId),
        "phase" to JsonValue.Str(phase.wire),
        "createdAt" to JsonValue.Str(createdAt),
        "materializedAt" to materializedAt?.let { JsonValue.Str(it) },
        "settledAt" to settledAt?.let { JsonValue.Str(it) },
        "compactedAt" to compactedAt?.let { JsonValue.Str(it) },
        "archivedAt" to archivedAt?.let { JsonValue.Str(it) },
        "receiptIds" to jsonArr(*receiptIds.map { JsonValue.Str(it) }.toTypedArray()),
        "view" to view?.toJson(),
    )
}

/** `new Date().toISOString()` 的等价形式：毫秒精度、UTC、`Z` 结尾。 */
private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

fun contextTimestampNow(): String = TIMESTAMP_FORMAT.format(Instant.now())

class Context(
    val id: String,
    private val allocatedFrame: ContextFrame,
    val graph: ContextAst,
    private val store: ContentStore,
    private val now: () -> String = ::contextTimestampNow,
) {

    init {
        require(allocatedFrame.id.isNotEmpty()) { "context requires a frame id" }
    }

    val createdAt: String = now()
    var phase: ContextPhase = ContextPhase.CREATED
        private set
    var materializedAt: String? = null
        private set
    var settledAt: String? = null
        private set
    var compactedAt: String? = null
        private set
    var archivedAt: String? = null
        private set
    var view: ContextView? = null
        private set

    private val attachedReceipts: MutableList<String> = ArrayList()
    private val expansionRefs: MutableMap<String, String> = LinkedHashMap()

    val receiptIds: List<String> get() = attachedReceipts.toList()

    /** 返回捕获的分配快照；调用方无法改动它（不可变数据类）。 */
    val frame: ContextFrame get() = allocatedFrame

    /** 只物化 active 的 frame 目标及其结构祖先，受预算约束。 */
    fun materialize(budgetTokens: Int): ContextView {
        if (phase != ContextPhase.CREATED) {
            throw IllegalStateException("cannot materialize context in phase ${phase.wire}")
        }
        require(budgetTokens > 0) { "budgetTokens must be positive" }
        val activeSlots: List<ContextSlot> = allocatedFrame.slots.filter { it.state == SlotState.ACTIVE }
        val activeIds: MutableSet<String> = LinkedHashSet(activeSlots.map { it.target.id })
        val raw: ContextAstView = graph.view(budgetTokens, activeIds.toList())
        val missingRefs: List<Ref> = activeSlots.filter { graph.node(it.target.id) == null }.map { it.target }
        val included: Set<String> = raw.nodes.map { it.id }.toHashSet()
        val pending: ArrayDeque<ContextAstNode> = ArrayDeque(raw.nodes.filter { activeIds.contains(it.id) })
        val visited: MutableSet<String> = HashSet()
        raw.nodes.forEach { node ->
            node.detailRef?.let { expansionRefs[node.id] = it }
        }
        while (pending.isNotEmpty()) {
            val node: ContextAstNode = pending.removeLast()
            if (visited.contains(node.id)) {
                continue
            }
            visited.add(node.id)
            node.detailRef?.let { expansionRefs[node.id] = it }
            graph.children(node.id).forEach { child ->
                if (child.kind == ContextNodeKind.DETAIL || included.contains(child.id)) {
                    pending.addLast(child)
                }
            }
        }
        val materialized = ContextView(
            frameId = allocatedFrame.id,
            nodes = raw.nodes,
            tokens = raw.tokens,
            budgetTokens = budgetTokens,
            missingRefs = missingRefs,
            deferred = raw.excluded,
            refs = raw.refs,
            relations = raw.relations,
            unresolvedDependencies = raw.unresolvedDependencies,
            omissions = raw.omissions,
            text = raw.text,
        )
        view = materialized
        phase = ContextPhase.MATERIALIZED
        materializedAt = now()
        return materialized
    }

    /** 标记该视图已被 prompt / 动作使用。 */
    fun activate() {
        if (phase != ContextPhase.MATERIALIZED) {
            throw IllegalStateException("cannot activate context in phase ${phase.wire}")
        }
        phase = ContextPhase.ACTIVE
    }

    /** 展开已捕获的 ref；未捕获的节点显式返回 null。 */
    fun expand(nodeId: String): String? {
        if (phase == ContextPhase.CREATED || phase == ContextPhase.ARCHIVED) {
            throw IllegalStateException("cannot expand context in phase ${phase.wire}")
        }
        val ref: String = expansionRefs[nodeId] ?: return null
        return store.get(ref)
    }

    /** 用描述实际操作/结果的 receipt 结算。 */
    fun settle(receiptId: String) {
        if (attachedReceipts.contains(receiptId)) {
            throw IllegalStateException("receipt $receiptId already attached")
        }
        if (phase != ContextPhase.ACTIVE && phase != ContextPhase.MATERIALIZED) {
            throw IllegalStateException("cannot settle context in phase ${phase.wire}")
        }
        attachedReceipts.add(receiptId)
        phase = ContextPhase.SETTLED
        settledAt = now()
    }

    /** 丢弃仅属于视图的材料；已捕获的内容 ref 在归档前仍可读。 */
    fun compact() {
        if (phase != ContextPhase.SETTLED) {
            throw IllegalStateException("cannot compact context in phase ${phase.wire}")
        }
        phase = ContextPhase.COMPACTED
        compactedAt = now()
    }

    fun archive() {
        if (phase != ContextPhase.COMPACTED) {
            throw IllegalStateException("cannot archive context in phase ${phase.wire}")
        }
        phase = ContextPhase.ARCHIVED
        archivedAt = now()
    }

    fun snapshot(): ContextSnapshot = ContextSnapshot(
        id = id,
        frameId = allocatedFrame.id,
        phase = phase,
        createdAt = createdAt,
        materializedAt = materializedAt,
        settledAt = settledAt,
        compactedAt = compactedAt,
        archivedAt = archivedAt,
        receiptIds = receiptIds,
        view = view,
    )

    companion object {

        fun create(
            frame: ContextFrame,
            graph: ContextAst,
            store: ContentStore,
            now: () -> String = ::contextTimestampNow,
            id: String? = null,
        ): Context = Context(id ?: "ctx.${frame.id}", frame, graph, store, now)
    }
}
