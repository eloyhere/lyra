package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * 带版本的项目/代码/知识事实与可重建索引（对应 `src/context/workspace.ts`）。
 *
 * 发现只返回候选；只有调用方自己的 Frame 才授予 prompt/细节可见性。代码依赖边
 * 是描述性的，允许成环；`depends` 才单独表示前置条件。
 */
enum class WorkspaceEntityKind(val wire: String) {
    PROJECT("project"),
    DIRECTORY("directory"),
    FILE("file"),
    SYMBOL("symbol"),
    PACKAGE("package"),
    KNOWLEDGE("knowledge");

    companion object {
        fun fromWire(value: String): WorkspaceEntityKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown workspace entity kind: $value")
    }
}

enum class WorkspaceRelationKind(val wire: String) {
    PROJECT_DEPENDS_ON("project_depends_on"),
    IMPORTS("imports"),
    CALLS("calls"),
    REFERENCES("references"),
    DOCUMENTS("documents");

    companion object {
        fun fromWire(value: String): WorkspaceRelationKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown workspace relation kind: $value")
    }
}

enum class WorkspaceExtractionMethod(val wire: String) {
    MANIFEST("manifest"),
    COMPILER("compiler"),
    EXPLICIT("explicit");

    companion object {
        fun fromWire(value: String): WorkspaceExtractionMethod =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown workspace extraction method: $value")
    }
}

enum class WorkspaceStructureKind(val wire: String) {
    DOCUMENT("document"),
    SECTION("section"),
    PARAGRAPH("paragraph"),
    TABLE("table"),
    SHEET("sheet"),
    SLIDE("slide"),
    ENVIRONMENT("environment");

    companion object {
        fun fromWire(value: String): WorkspaceStructureKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown workspace structure kind: $value")
    }
}

enum class SummaryState(val wire: String) {
    UNTRACKED("untracked"),
    FRESH("fresh"),
    STALE("stale"),
}

enum class QueryDirection(val wire: String) {
    INCOMING("incoming"),
    OUTGOING("outgoing"),
    BOTH("both");

    companion object {
        fun fromWire(value: String): QueryDirection =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("unknown query direction: $value")
    }
}

data class WorkspaceSource(
    val uri: String,
    val revision: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val locator: DocumentLocator? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "uri" to JsonValue.Str(uri),
        "revision" to JsonValue.Str(revision),
        "startLine" to startLine?.let { jsonNumber(it) },
        "endLine" to endLine?.let { jsonNumber(it) },
        "locator" to locator?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): WorkspaceSource {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("workspace source 必须是 JSON 对象")
            return WorkspaceSource(
                uri = obj.requireString("uri"),
                revision = obj.requireString("revision"),
                startLine = obj.exactIntOrNull("startLine"),
                endLine = obj.exactIntOrNull("endLine"),
                locator = obj.obj("locator")?.let { DocumentLocator.fromJson(it) },
            )
        }
    }
}

private fun JsonValue.Obj.exactIntOrNull(key: String): Int? {
    val number: Double = (fields[key] as? JsonValue.Num)?.value ?: return null
    if (!number.isFinite() || number != Math.floor(number)) {
        return null
    }
    if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
        return null
    }
    return number.toInt()
}

data class WorkspaceEntity(
    val id: String,
    val kind: WorkspaceEntityKind,
    val projectId: String,
    val label: String,
    val summary: String,
    val source: WorkspaceSource,
    val parentId: String? = null,
    val detailRef: String? = null,
    val tags: List<String>? = null,
    val searchTerms: List<String>? = null,
    val structureKind: WorkspaceStructureKind? = null,
    val summaryProvenance: SummaryProvenance? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "kind" to JsonValue.Str(kind.wire),
        "projectId" to JsonValue.Str(projectId),
        "parentId" to parentId?.let { JsonValue.Str(it) },
        "label" to JsonValue.Str(label),
        "summary" to JsonValue.Str(summary),
        "source" to source.toJson(),
        "detailRef" to detailRef?.let { JsonValue.Str(it) },
        "tags" to tags?.let { values -> jsonArr(*values.map { JsonValue.Str(it) }.toTypedArray()) },
        "searchTerms" to searchTerms?.let { values -> jsonArr(*values.map { JsonValue.Str(it) }.toTypedArray()) },
        "structureKind" to structureKind?.let { JsonValue.Str(it.wire) },
        "summaryProvenance" to summaryProvenance?.toJson(),
    )

    companion object {
        fun fromJson(value: JsonValue): WorkspaceEntity {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("workspace entity 必须是 JSON 对象")
            return WorkspaceEntity(
                id = obj.requireString("id"),
                kind = WorkspaceEntityKind.fromWire(obj.requireString("kind")),
                projectId = obj.requireString("projectId"),
                label = obj.requireString("label"),
                summary = obj.requireString("summary"),
                source = WorkspaceSource.fromJson(obj.requireObj("source")),
                parentId = obj.string("parentId"),
                detailRef = obj.string("detailRef"),
                tags = obj.arr("tags")?.items?.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("workspace entity tags 必须是字符串")
                },
                searchTerms = obj.arr("searchTerms")?.items?.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("workspace entity searchTerms 必须是字符串")
                },
                structureKind = obj.string("structureKind")?.let { WorkspaceStructureKind.fromWire(it) },
                summaryProvenance = obj.obj("summaryProvenance")?.let { SummaryProvenance.fromJson(it) },
            )
        }
    }
}

/**
 * from 是使用方/调用方/文档，to 是被依赖方/被调用方/主题。
 * method 描述抽取方式，不代表置信度或授权。
 */
data class WorkspaceRelation(
    val from: String,
    val to: String,
    val kind: WorkspaceRelationKind,
    val source: WorkspaceSource,
    val method: WorkspaceExtractionMethod,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "from" to JsonValue.Str(from),
        "to" to JsonValue.Str(to),
        "kind" to JsonValue.Str(kind.wire),
        "source" to source.toJson(),
        "method" to JsonValue.Str(method.wire),
    )

    companion object {
        fun fromJson(value: JsonValue): WorkspaceRelation {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("workspace relation 必须是 JSON 对象")
            return WorkspaceRelation(
                from = obj.requireString("from"),
                to = obj.requireString("to"),
                kind = WorkspaceRelationKind.fromWire(obj.requireString("kind")),
                source = WorkspaceSource.fromJson(obj.requireObj("source")),
                method = WorkspaceExtractionMethod.fromWire(obj.requireString("method")),
            )
        }
    }
}

data class WorkspaceDiagnostic(
    val uri: String,
    val code: String,
    val message: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "uri" to JsonValue.Str(uri),
        "code" to JsonValue.Str(code),
        "message" to JsonValue.Str(message),
    )

    companion object {
        fun fromJson(value: JsonValue): WorkspaceDiagnostic {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("workspace diagnostic 必须是 JSON 对象")
            return WorkspaceDiagnostic(
                obj.requireString("uri"),
                obj.requireString("code"),
                obj.requireString("message"),
            )
        }
    }
}

data class WorkspaceSnapshot(
    val revision: String,
    val entities: List<WorkspaceEntity>,
    val relations: List<WorkspaceRelation>,
    val diagnostics: List<WorkspaceDiagnostic>,
    val version: Int = 1,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "version" to jsonNumber(version),
        "revision" to JsonValue.Str(revision),
        "entities" to jsonArr(*entities.map { it.toJson() }.toTypedArray()),
        "relations" to jsonArr(*relations.map { it.toJson() }.toTypedArray()),
        "diagnostics" to jsonArr(*diagnostics.map { it.toJson() }.toTypedArray()),
    )

    companion object {
        fun fromJson(value: JsonValue): WorkspaceSnapshot {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("workspace snapshot 必须是 JSON 对象")
            return WorkspaceSnapshot(
                revision = obj.requireString("revision"),
                entities = obj.requireArr("entities").items.map(WorkspaceEntity::fromJson),
                relations = obj.requireArr("relations").items.map(WorkspaceRelation::fromJson),
                diagnostics = obj.requireArr("diagnostics").items.map(WorkspaceDiagnostic::fromJson),
                version = obj.requireInt("version"),
            )
        }
    }
}

data class WorkspaceQuery(
    val seedIds: List<String>? = null,
    val text: String? = null,
    val projectIds: List<String>? = null,
    val relationKinds: List<WorkspaceRelationKind>? = null,
    val direction: QueryDirection = QueryDirection.BOTH,
    val maxDepth: Int? = null,
    val limit: Int? = null,
)

sealed interface CandidateWhy {

    val wire: String

    data object Seed : CandidateWhy {
        override val wire: String get() = "seed"
    }

    data object Text : CandidateWhy {
        override val wire: String get() = "text"
    }

    data class Relation(val kind: WorkspaceRelationKind) : CandidateWhy {
        override val wire: String get() = kind.wire
    }
}

data class WorkspaceCandidate(
    val id: String,
    val why: CandidateWhy,
    val hops: Int,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "why" to JsonValue.Str(why.wire),
        "hops" to jsonNumber(hops),
    )
}

data class WorkspaceQueryResult(
    val revision: String,
    val candidates: List<WorkspaceCandidate>,
    val unknownIds: List<String>,
    val truncated: Boolean,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "revision" to JsonValue.Str(revision),
        "candidates" to jsonArr(*candidates.map { it.toJson() }.toTypedArray()),
        "unknownIds" to jsonArr(*unknownIds.map { JsonValue.Str(it) }.toTypedArray()),
        "truncated" to JsonValue.Bool(truncated),
    )
}

/**
 * 词项化：驼峰先拆开、转小写，再取 Unicode 字母/数字串，按首次出现顺序去重。
 * 等价于 `text.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase().match(/[\p{L}\p{N}]+/gu)`。
 */
fun workspaceTerms(text: String): List<String> {
    val lowered: String = CAMEL_BOUNDARY.replace(text, "$1 $2").lowercase()
    val seen: MutableSet<String> = LinkedHashSet()
    TERM_PATTERN.findAll(lowered).forEach { match -> seen.add(match.value) }
    return seen.toList()
}

private val CAMEL_BOUNDARY: Regex = Regex("([a-z])([A-Z])")
private val TERM_PATTERN: Regex = Regex("[\\p{L}\\p{N}]+")

private val ENTITY_KINDS: Set<WorkspaceEntityKind> = WorkspaceEntityKind.entries.toSet()
private val RELATION_KINDS: Set<WorkspaceRelationKind> = WorkspaceRelationKind.entries.toSet()
private val EXTRACTION_METHODS: Set<WorkspaceExtractionMethod> = WorkspaceExtractionMethod.entries.toSet()
private val CELL_RANGE_PATTERN: Regex = Regex("^[A-Z]+[1-9]\\d*(?::[A-Z]+[1-9]\\d*)?$")

private fun checkSource(source: WorkspaceSource) {
    if (source.uri.isEmpty() || source.revision.isEmpty()) {
        throw IllegalArgumentException("workspace facts require source URI and revision")
    }
    val startLine: Int? = source.startLine
    val endLine: Int? = source.endLine
    if ((startLine != null && startLine < 1) || (endLine != null && endLine < (startLine ?: 1))) {
        throw IllegalArgumentException("invalid workspace source range")
    }
    val locator: DocumentLocator? = source.locator
    if (locator != null) {
        val valid: Boolean = when (locator) {
            is PartLocator -> locator.part.isNotEmpty()
            is CellLocator -> locator.part.isNotEmpty() &&
                locator.sheet.isNotEmpty() &&
                CELL_RANGE_PATTERN.matches(locator.range)

            is SlideLocator -> locator.part.isNotEmpty() && locator.slide >= 1
        }
        if (!valid) {
            throw IllegalArgumentException("invalid document source locator")
        }
    }
}

private fun cloneEntity(entity: WorkspaceEntity): WorkspaceEntity = entity.copy(
    source = entity.source.copy(locator = entity.source.locator),
    tags = entity.tags?.toList(),
    searchTerms = entity.searchTerms?.toList(),
    summaryProvenance = entity.summaryProvenance?.copy(
        sources = entity.summaryProvenance.sources.map { it.copy() },
        children = entity.summaryProvenance.children.toList(),
    ),
)

private fun cloneRelation(edge: WorkspaceRelation): WorkspaceRelation =
    edge.copy(source = edge.source.copy(locator = edge.source.locator))

class WorkspaceIndex(
    snapshot: WorkspaceSnapshot,
    private val store: ContentStore,
) {

    val revision: String
    private val entities: MutableMap<String, WorkspaceEntity> = LinkedHashMap()
    private val edges: MutableList<WorkspaceRelation> = ArrayList()
    private val childrenById: MutableMap<String, MutableList<String>> = LinkedHashMap()
    private val outgoing: MutableMap<String, MutableList<WorkspaceRelation>> = LinkedHashMap()
    private val incoming: MutableMap<String, MutableList<WorkspaceRelation>> = LinkedHashMap()
    private val postings: MutableMap<String, MutableSet<String>> = LinkedHashMap()
    private val sourceRevisions: MutableMap<String, MutableSet<String>> = LinkedHashMap()
    private val summaryStates: MutableMap<String, SummaryState> = LinkedHashMap()
    private val diagnostics: List<WorkspaceDiagnostic>

    init {
        if (snapshot.version != 1 || snapshot.revision.isEmpty()) {
            throw IllegalArgumentException("unsupported or unversioned workspace snapshot")
        }
        revision = snapshot.revision
        diagnostics = snapshot.diagnostics.map { it.copy() }
        snapshot.entities.forEach { entity ->
            if (entity.id.isEmpty() || !ENTITY_KINDS.contains(entity.kind) || entities.containsKey(entity.id)) {
                throw IllegalArgumentException("invalid or duplicate workspace entity ${entity.id}")
            }
            checkSource(entity.source)
            val provenance: SummaryProvenance? = entity.summaryProvenance
            if (provenance != null) {
                if (provenance.sources.isEmpty() ||
                    provenance.sources.any { it.uri.isEmpty() || it.revision.isEmpty() }
                ) {
                    throw IllegalArgumentException("invalid summary provenance")
                }
            }
            entities[entity.id] = cloneEntity(entity)
            sourceRevisions.getOrPut(entity.source.uri) { LinkedHashSet() }.add(entity.source.revision)
        }
        entities.forEach { (_, entity) ->
            val project: WorkspaceEntity? = entities[entity.projectId]
            val parent: WorkspaceEntity? = entity.parentId?.let { entities[it] }
            if (project?.kind != WorkspaceEntityKind.PROJECT ||
                (entity.kind == WorkspaceEntityKind.PROJECT &&
                    (entity.projectId != entity.id || entity.parentId != null))
            ) {
                throw IllegalArgumentException("invalid project owner for ${entity.id}")
            }
            if (entity.kind != WorkspaceEntityKind.PROJECT &&
                (parent == null || parent.projectId != entity.projectId)
            ) {
                throw IllegalArgumentException("invalid structural parent for ${entity.id}")
            }
            if (parent != null) {
                childrenById.getOrPut(parent.id) { ArrayList() }.add(entity.id)
            }
            val provenanceChildren: List<String>? = entity.summaryProvenance?.children
            if (provenanceChildren != null && provenanceChildren.any { entities[it]?.parentId != entity.id }) {
                throw IllegalArgumentException("summary dependencies must be direct structural children")
            }
        }
        val complete: MutableSet<String> = HashSet()
        entities.forEach { (_, entity) ->
            val path: MutableSet<String> = LinkedHashSet()
            var current: WorkspaceEntity? = entity
            while (current != null && !complete.contains(current.id)) {
                if (path.contains(current.id)) {
                    throw IllegalArgumentException("workspace hierarchy contains a cycle")
                }
                path.add(current.id)
                current = current.parentId?.let { entities[it] }
            }
            complete.addAll(path)
        }
        entities.forEach { (_, entity) ->
            val terms: String = "${entity.label} " +
                (if (summaryState(entity.id) == SummaryState.STALE) "" else entity.summary) +
                " ${entity.source.uri} " +
                (entity.tags?.joinToString(" ") ?: "") + " " +
                (entity.searchTerms?.joinToString(" ") ?: "")
            workspaceTerms(terms).forEach { term ->
                postings.getOrPut(term) { LinkedHashSet() }.add(entity.id)
            }
        }
        val seen: MutableSet<String> = LinkedHashSet()
        snapshot.relations.forEach { edge ->
            if (!entities.containsKey(edge.from) || !entities.containsKey(edge.to) || !RELATION_KINDS.contains(edge.kind)) {
                throw IllegalArgumentException("invalid workspace relation ${edge.from}->${edge.to}")
            }
            checkSource(edge.source)
            if (!EXTRACTION_METHODS.contains(edge.method)) {
                throw IllegalArgumentException("unknown workspace extraction method")
            }
            val key: String = CanonicalJson.encode(edge.toJson())
            if (seen.contains(key)) {
                return@forEach
            }
            seen.add(key)
            val stored: WorkspaceRelation = cloneRelation(edge)
            edges.add(stored)
            outgoing.getOrPut(stored.from) { ArrayList() }.add(stored)
            incoming.getOrPut(stored.to) { ArrayList() }.add(stored)
        }
    }

    fun entity(id: String): WorkspaceEntity? = entities[id]?.let { cloneEntity(it) }

    /**
     * 缺失或已变化的源 revision 会让依赖它的摘要失效。已验证的层级无环，
     * 后序遍历会缓存每个后代，因此构建倒排时每条摘要依赖只走一次。
     */
    fun summaryState(id: String, currentSources: List<DocumentSourceRevision>? = null): SummaryState {
        if (entities[id]?.summaryProvenance == null) {
            return SummaryState.UNTRACKED
        }
        if (currentSources == null) {
            summaryStates[id]?.let { return it }
        }
        val sources: MutableMap<String, MutableSet<String>> =
            if (currentSources == null) sourceRevisions else LinkedHashMap()
        if (currentSources != null) {
            currentSources.forEach { source ->
                sources.getOrPut(source.uri) { LinkedHashSet() }.add(source.revision)
            }
        }
        val states: MutableMap<String, SummaryState> =
            if (currentSources == null) summaryStates else LinkedHashMap()
        val pending: MutableList<Pair<String, Boolean>> = ArrayList()
        pending.add(id to false)
        while (pending.isNotEmpty()) {
            val next: Pair<String, Boolean> = pending.removeAt(pending.size - 1)
            val nextId: String = next.first
            val visited: Boolean = next.second
            if (states.containsKey(nextId)) {
                continue
            }
            val provenance: SummaryProvenance? = entities[nextId]?.summaryProvenance
            val sourcesStale: Boolean = provenance == null || provenance.sources.any { source ->
                val revisions: Set<String>? = sources[source.uri]
                revisions == null || revisions.size != 1 || !revisions.contains(source.revision)
            }
            if (sourcesStale) {
                states[nextId] = SummaryState.STALE
                continue
            }
            if (visited) {
                val stale: Boolean = provenance!!.children.any { states[it] != SummaryState.FRESH }
                states[nextId] = if (stale) SummaryState.STALE else SummaryState.FRESH
                continue
            }
            pending.add(nextId to true)
            provenance!!.children.forEach { child ->
                if (!states.containsKey(child)) {
                    pending.add(child to false)
                }
            }
        }
        return states.getValue(id)
    }

    fun children(id: String): List<WorkspaceEntity> =
        (childrenById[id] ?: emptyList()).mapNotNull { entities[it]?.let(::cloneEntity) }

    fun relations(
        id: String,
        direction: EdgeDirection,
        kind: WorkspaceRelationKind? = null,
    ): List<WorkspaceRelation> {
        val list: List<WorkspaceRelation> = (if (direction == EdgeDirection.INCOMING) incoming else outgoing)[id]
            ?: emptyList()
        return list.filter { kind == null || it.kind == kind }.map(::cloneRelation)
    }

    /**
     * 精确词法 AND 检索 + 有界图导航。项目范围在遍历前检查；未知/范围外的种子
     * 永远不会扩大它。
     */
    fun query(query: WorkspaceQuery): WorkspaceQueryResult {
        val limit: Int = query.limit ?: 32
        val depth: Int = query.maxDepth ?: 1
        if (limit < 1 || limit > 1000 || depth < 0 || depth > 100) {
            throw IllegalArgumentException("workspace query requires limit 1..1000 and depth 0..100")
        }
        val projects: Set<String>? = query.projectIds?.toSet()
        fun allowed(id: String): Boolean {
            val entity: WorkspaceEntity = entities[id] ?: return false
            return projects == null || projects.contains(entity.projectId)
        }

        val candidates: MutableList<WorkspaceCandidate> = ArrayList()
        val visited: MutableSet<String> = LinkedHashSet()
        var truncated: Boolean = false

        fun admit(id: String, why: CandidateWhy, hops: Int) {
            if (visited.contains(id) || !allowed(id)) {
                return
            }
            visited.add(id)
            if (candidates.size >= limit) {
                truncated = true
                return
            }
            candidates.add(WorkspaceCandidate(id, why, hops))
        }

        query.seedIds?.forEach { admit(it, CandidateWhy.Seed, 0) }
        val words: List<String> = workspaceTerms(query.text ?: "")
        if (words.isNotEmpty()) {
            val lists: List<Set<String>> = words.map { postings[it] ?: emptySet() }
                .sortedBy { it.size }
            lists.first()
                .filter { id -> lists.all { it.contains(id) } }
                .sorted()
                .forEach { admit(it, CandidateWhy.Text, 0) }
        }
        val kinds: Set<WorkspaceRelationKind>? = query.relationKinds?.toSet()
        val direction: QueryDirection = query.direction
        var index: Int = 0
        while (index < candidates.size) {
            val candidate: WorkspaceCandidate = candidates[index]
            index += 1
            if (candidate.hops >= depth) {
                continue
            }
            val adjacent: MutableList<WorkspaceRelation> = ArrayList()
            if (direction != QueryDirection.INCOMING) {
                adjacent.addAll(outgoing[candidate.id] ?: emptyList())
            }
            if (direction != QueryDirection.OUTGOING) {
                adjacent.addAll(incoming[candidate.id] ?: emptyList())
            }
            adjacent.forEach { edge ->
                if (kinds == null || kinds.contains(edge.kind)) {
                    admit(
                        if (edge.from == candidate.id) edge.to else edge.from,
                        CandidateWhy.Relation(edge.kind),
                        candidate.hops + 1,
                    )
                }
            }
        }
        return WorkspaceQueryResult(
            revision = revision,
            candidates = candidates,
            unknownIds = (query.seedIds ?: emptyList()).distinct().filter { !entities.containsKey(it) },
            truncated = truncated,
        )
    }

    /**
     * 一次性的 AST 投影；源事实与索引和活的 AST 变更隔离。
     * 描述性依赖环仍然合法。
     */
    fun project(): ContextAst {
        val graph = ContextAst()
        entities.values.forEach { entity ->
            val location: String = when {
                entity.source.locator != null ->
                    entity.source.uri + "#" + CanonicalJson.encode(entity.source.locator.toJson())

                entity.source.startLine != null -> "${entity.source.uri}:${entity.source.startLine}"
                else -> entity.source.uri
            }
            val summary: String = (if (summaryState(entity.id) == SummaryState.STALE) {
                "${entity.label} [summary stale; read detail]"
            } else {
                entity.summary
            }) + " | source=" + jsonStringLiteral(location) +
                " revision=" + jsonStringLiteral(entity.source.revision)
            graph.add(
                ContextAstNode(
                    id = entity.id,
                    kind = ContextNodeKind.fromWire(entity.kind.wire),
                    summary = summary,
                    order = graph.size,
                    parentId = entity.parentId,
                    detailRef = entity.detailRef,
                    salience = if (entity.kind == WorkspaceEntityKind.PROJECT ||
                        entity.kind == WorkspaceEntityKind.DIRECTORY
                    ) {
                        0.0
                    } else {
                        1.0
                    },
                ),
            )
        }
        edges.forEach { edge ->
            graph.link(
                ContextAstEdge(
                    from = edge.from,
                    to = edge.to,
                    kind = ContextEdgeKind.fromWire(edge.kind.wire),
                ),
            )
        }
        return graph
    }

    fun context(frame: ContextFrame, budgetTokens: Int): Context {
        val context: Context = Context.create(frame, project(), store)
        context.materialize(budgetTokens)
        return context
    }

    fun snapshot(): WorkspaceSnapshot = WorkspaceSnapshot(
        revision = revision,
        entities = entities.values.map(::cloneEntity),
        relations = edges.map(::cloneRelation),
        diagnostics = diagnostics.map { it.copy() },
        version = 1,
    )
}
