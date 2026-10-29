package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj

internal const val AGENT_ID: String = "agent.lyra"

/** "AB😀CD"：6 个 UTF-16 码元，表情占 2 个（必须能按码元切页）。 */
internal const val FILE_CONTENT: String = "AB\uD83D\uDE00CD"
internal const val DIR_CONTENT: String = "DIRECTORY-DETAIL-BODY"
internal const val UTIL_CONTENT: String = "UTIL-DETAIL-BODY"

internal fun source(
    uri: String,
    revision: String,
    startLine: Int? = null,
    endLine: Int? = null,
): WorkspaceSource = WorkspaceSource(uri = uri, revision = revision, startLine = startLine, endLine = endLine)

internal data class WorkspaceFixture(
    val store: MemoryContentStore,
    val snapshot: WorkspaceSnapshot,
)

internal fun workspaceFixture(): WorkspaceFixture {
    val store = MemoryContentStore()
    val fileRef: String = store.put(FILE_CONTENT)
    val dirRef: String = store.put(DIR_CONTENT)
    val utilRef: String = store.put(UTIL_CONTENT)
    val entities: List<WorkspaceEntity> = listOf(
        WorkspaceEntity(
            id = "project.alpha",
            kind = WorkspaceEntityKind.PROJECT,
            projectId = "project.alpha",
            label = "Alpha",
            summary = "Alpha project",
            source = source("file:///alpha", "r1"),
        ),
        WorkspaceEntity(
            id = "dir.src",
            kind = WorkspaceEntityKind.DIRECTORY,
            projectId = "project.alpha",
            parentId = "project.alpha",
            label = "src",
            summary = "Source directory",
            source = source("file:///alpha/src", "r1"),
            detailRef = dirRef,
        ),
        WorkspaceEntity(
            id = "file.main",
            kind = WorkspaceEntityKind.FILE,
            projectId = "project.alpha",
            parentId = "dir.src",
            label = "main.ts",
            summary = "Entry point of the tool",
            source = source("file:///alpha/src/main.ts", "r1", startLine = 1, endLine = 4),
            detailRef = fileRef,
        ),
        WorkspaceEntity(
            id = "file.util",
            kind = WorkspaceEntityKind.FILE,
            projectId = "project.alpha",
            parentId = "dir.src",
            label = "util.ts",
            summary = "Utility helpers",
            source = source("file:///alpha/src/util.ts", "r1"),
            detailRef = utilRef,
        ),
    )
    val relations: List<WorkspaceRelation> = listOf(
        WorkspaceRelation(
            from = "file.main",
            to = "file.util",
            kind = WorkspaceRelationKind.IMPORTS,
            source = source("file:///alpha/src/main.ts", "r1"),
            method = WorkspaceExtractionMethod.COMPILER,
        ),
        // 描述性依赖允许成环。
        WorkspaceRelation(
            from = "file.util",
            to = "file.main",
            kind = WorkspaceRelationKind.IMPORTS,
            source = source("file:///alpha/src/util.ts", "r1"),
            method = WorkspaceExtractionMethod.COMPILER,
        ),
    )
    return WorkspaceFixture(
        store = store,
        snapshot = WorkspaceSnapshot(
            revision = "rev.1",
            entities = entities,
            relations = relations,
            diagnostics = emptyList(),
        ),
    )
}

internal fun frameJson(
    id: String = "frame.1",
    agent: String = AGENT_ID,
    targets: List<String> = listOf("file.main"),
    allocated: Int = 4000,
    spent: Int = 0,
): JsonValue.Obj = jsonObj(
    "amp" to JsonValue.Str("0.1"),
    "kind" to JsonValue.Str("frame"),
    "id" to JsonValue.Str(id),
    "agent" to JsonValue.Str(agent),
    "cycle" to jsonNumber(1),
    "ts" to JsonValue.Str("2026-10-29T00:00:00.000Z"),
    "digest" to JsonValue.Str("sha256:" + "0".repeat(64)),
    "mode" to JsonValue.Str("explore"),
    "slots" to jsonArr(
        *targets.map { target ->
            jsonObj(
                "target" to jsonObj("kind" to JsonValue.Str("entity"), "id" to JsonValue.Str(target)),
                "role" to JsonValue.Str("artifact"),
                "salience" to jsonNumber(0.9),
                "cost" to jsonObj("tokens" to jsonNumber(1)),
                "why" to JsonValue.Str("inspect $target"),
                "state" to JsonValue.Str("active"),
            )
        }.toTypedArray(),
    ),
    "budget" to jsonObj(
        "allocated" to jsonObj("tokens" to jsonNumber(allocated)),
        "spent" to jsonObj("tokens" to jsonNumber(spent)),
    ),
)

internal fun callArgs(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj = jsonObj(*pairs)

internal fun text(value: String): JsonValue = JsonValue.Str(value)

internal fun number(value: Int): JsonValue = jsonNumber(value)
