package com.eloykim.lyra.amp.protocol.context

import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonArr
import com.eloykim.lyra.amp.protocol.json.jsonNumber
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson

/**
 * 可移植的文档事实（对应 `src/context/document.ts`）。
 *
 * 解析器留在 runtime 之外；带类型的读取复用同样的、被捕获且受 Frame 作用域约束的
 * 内容引用。读者绝不跟随内嵌 ref、图边或后代 ID。
 */
sealed interface DocumentLocator {

    val kind: String

    fun toJson(): JsonValue.Obj

    companion object {
        fun fromJson(value: JsonValue): DocumentLocator {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("document locator 必须是 JSON 对象")
            return when (val kind = obj.requireString("kind")) {
                "part" -> PartLocator(
                    part = obj.requireString("part"),
                    element = obj.string("element"),
                )

                "cells" -> CellLocator(
                    part = obj.requireString("part"),
                    sheet = obj.requireString("sheet"),
                    range = obj.requireString("range"),
                )

                "slide" -> SlideLocator(
                    part = obj.requireString("part"),
                    slide = obj.requireInt("slide"),
                    objectId = obj.string("objectId"),
                )

                else -> throw IllegalArgumentException("unknown document locator kind: $kind")
            }
        }
    }
}

data class PartLocator(
    val part: String,
    val element: String? = null,
) : DocumentLocator {

    override val kind: String get() = "part"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "part" to JsonValue.Str(part),
        "element" to element?.let { JsonValue.Str(it) },
    )
}

data class CellLocator(
    val part: String,
    val sheet: String,
    val range: String,
) : DocumentLocator {

    override val kind: String get() = "cells"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "part" to JsonValue.Str(part),
        "sheet" to JsonValue.Str(sheet),
        "range" to JsonValue.Str(range),
    )
}

data class SlideLocator(
    val part: String,
    val slide: Int,
    val objectId: String? = null,
) : DocumentLocator {

    override val kind: String get() = "slide"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "part" to JsonValue.Str(part),
        "slide" to jsonNumber(slide),
        "objectId" to objectId?.let { JsonValue.Str(it) },
    )
}

data class DocumentSourceRevision(
    val uri: String,
    val revision: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "uri" to JsonValue.Str(uri),
        "revision" to JsonValue.Str(revision),
    )

    companion object {
        fun fromJson(value: JsonValue): DocumentSourceRevision {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("document source revision 必须是 JSON 对象")
            return DocumentSourceRevision(obj.requireString("uri"), obj.requireString("revision"))
        }
    }
}

data class SummaryProvenance(
    val sources: List<DocumentSourceRevision>,
    val children: List<String>,
    val method: String = "extractive-v1",
) {

    init {
        require(method == "extractive-v1") { "未知摘要方法: $method" }
    }

    fun toJson(): JsonValue.Obj = jsonObj(
        "method" to JsonValue.Str(method),
        "sources" to jsonArr(*sources.map { it.toJson() }.toTypedArray()),
        "children" to jsonArr(*children.map { JsonValue.Str(it) }.toTypedArray()),
    )

    companion object {
        fun fromJson(value: JsonValue): SummaryProvenance {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("summary provenance 必须是 JSON 对象")
            return SummaryProvenance(
                method = obj.requireString("method"),
                sources = obj.requireArr("sources").items.map(DocumentSourceRevision::fromJson),
                children = obj.requireArr("children").items.map { item ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("summary provenance children 必须是字符串")
                },
            )
        }
    }
}

data class DocumentCell(
    val address: String,
    val type: String,
    val value: String? = null,
    val formula: String? = null,
    val formulaAttributes: Map<String, String>? = null,
    val style: String? = null,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "address" to JsonValue.Str(address),
        "type" to JsonValue.Str(type),
        "value" to value?.let { JsonValue.Str(it) },
        "formula" to formula?.let { JsonValue.Str(it) },
        "formulaAttributes" to formulaAttributes?.let { attributes ->
            jsonObj(*attributes.map { (key, item) -> key to JsonValue.Str(item) }.toTypedArray())
        },
        "style" to style?.let { JsonValue.Str(it) },
    )

    companion object {
        fun fromJson(value: JsonValue): DocumentCell {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("document cell 必须是 JSON 对象")
            return DocumentCell(
                address = obj.requireString("address"),
                type = obj.requireString("type"),
                value = obj.string("value"),
                formula = obj.string("formula"),
                formulaAttributes = obj.obj("formulaAttributes")?.fields?.mapValues { (_, item) ->
                    (item as? JsonValue.Str)?.value
                        ?: throw IllegalArgumentException("formulaAttributes 必须是字符串映射")
                },
                style = obj.string("style"),
            )
        }
    }
}

data class SlideObject(
    val id: String,
    val kind: String,
    val text: String,
    val locator: SlideLocator,
    /** 原生 XML 保留布局以及 chart/image 关系。 */
    val xml: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "id" to JsonValue.Str(id),
        "kind" to JsonValue.Str(kind),
        "text" to JsonValue.Str(text),
        "locator" to locator.toJson(),
        "xml" to JsonValue.Str(xml),
    )

    companion object {
        fun fromJson(value: JsonValue): SlideObject {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("slide object 必须是 JSON 对象")
            val locator = DocumentLocator.fromJson(
                obj.obj("locator") ?: throw IllegalArgumentException("缺少 slide object locator"),
            )
            return SlideObject(
                id = obj.requireString("id"),
                kind = obj.requireString("kind"),
                text = obj.requireString("text"),
                locator = locator as? SlideLocator
                    ?: throw IllegalArgumentException("slide object locator 必须是 slide"),
                xml = obj.requireString("xml"),
            )
        }
    }
}

data class TexDependency(
    val from: String,
    val to: String,
    val command: String,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "from" to JsonValue.Str(from),
        "to" to JsonValue.Str(to),
        "command" to JsonValue.Str(command),
    )

    companion object {
        fun fromJson(value: JsonValue): TexDependency {
            val obj = value as? JsonValue.Obj
                ?: throw IllegalArgumentException("tex dependency 必须是 JSON 对象")
            return TexDependency(obj.requireString("from"), obj.requireString("to"), obj.requireString("command"))
        }
    }
}

sealed interface DocumentDetail {

    val kind: String

    fun toJson(): JsonValue.Obj

    companion object {
        private val KINDS: Set<String> = setOf("text", "table", "sheet", "slide", "original", "tex-project")

        fun fromJson(value: JsonValue): DocumentDetail? {
            val obj = value as? JsonValue.Obj ?: return null
            val kind: String = obj.string("kind") ?: return null
            if (!KINDS.contains(kind)) {
                return null
            }
            return runCatching {
                when (kind) {
                    "text" -> TextDetail(
                        text = obj.requireString("text"),
                        xml = obj.string("xml"),
                    )

                    "table" -> TableDetail(
                        rows = obj.requireArr("rows").items.map { row ->
                            (row as? JsonValue.Arr)?.items?.map { cell ->
                                (cell as? JsonValue.Str)?.value
                                    ?: throw IllegalArgumentException("table cell 必须是字符串")
                            } ?: throw IllegalArgumentException("table row 必须是数组")
                        },
                        xml = obj.requireString("xml"),
                    )

                    "sheet" -> SheetDetail(
                        name = obj.requireString("name"),
                        cells = obj.requireArr("cells").items.map(DocumentCell::fromJson),
                        merges = obj.requireArr("merges").items.map { item ->
                            (item as? JsonValue.Str)?.value
                                ?: throw IllegalArgumentException("sheet merges 必须是字符串")
                        },
                        xml = obj.requireString("xml"),
                    )

                    "slide" -> SlideDetail(
                        objects = obj.requireArr("objects").items.map(SlideObject::fromJson),
                        notes = obj.requireString("notes"),
                        xml = obj.requireString("xml"),
                    )

                    "original" -> OriginalDetail(
                        mediaType = obj.requireString("mediaType"),
                        data = obj.requireString("data"),
                    )

                    else -> TexProjectDetail(
                        root = obj.requireString("root"),
                        files = obj.requireArr("files").items.map { item ->
                            (item as? JsonValue.Str)?.value
                                ?: throw IllegalArgumentException("tex files 必须是字符串")
                        },
                        dependencies = obj.requireArr("dependencies").items.map(TexDependency::fromJson),
                    )
                }
            }.getOrNull()
        }
    }
}

data class TextDetail(
    val text: String,
    val xml: String? = null,
) : DocumentDetail {

    override val kind: String get() = "text"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "text" to JsonValue.Str(text),
        "xml" to xml?.let { JsonValue.Str(it) },
    )
}

data class TableDetail(
    val rows: List<List<String>>,
    val xml: String,
) : DocumentDetail {

    override val kind: String get() = "table"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "rows" to jsonArr(*rows.map { row -> jsonArr(*row.map { JsonValue.Str(it) }.toTypedArray()) }.toTypedArray()),
        "xml" to JsonValue.Str(xml),
    )
}

data class SheetDetail(
    val name: String,
    val cells: List<DocumentCell>,
    val merges: List<String>,
    val xml: String,
) : DocumentDetail {

    override val kind: String get() = "sheet"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "name" to JsonValue.Str(name),
        "cells" to jsonArr(*cells.map { it.toJson() }.toTypedArray()),
        "merges" to jsonArr(*merges.map { JsonValue.Str(it) }.toTypedArray()),
        "xml" to JsonValue.Str(xml),
    )
}

data class SlideDetail(
    val objects: List<SlideObject>,
    val notes: String,
    val xml: String,
) : DocumentDetail {

    override val kind: String get() = "slide"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "objects" to jsonArr(*objects.map { it.toJson() }.toTypedArray()),
        "notes" to JsonValue.Str(notes),
        "xml" to JsonValue.Str(xml),
    )
}

data class OriginalDetail(
    val mediaType: String,
    val data: String,
) : DocumentDetail {

    override val kind: String get() = "original"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "mediaType" to JsonValue.Str(mediaType),
        "encoding" to JsonValue.Str("base64"),
        "data" to JsonValue.Str(data),
    )
}

data class TexProjectDetail(
    val root: String,
    val files: List<String>,
    val dependencies: List<TexDependency>,
) : DocumentDetail {

    override val kind: String get() = "tex-project"

    override fun toJson(): JsonValue.Obj = jsonObj(
        "kind" to JsonValue.Str(kind),
        "root" to JsonValue.Str(root),
        "files" to jsonArr(*files.map { JsonValue.Str(it) }.toTypedArray()),
        "dependencies" to jsonArr(*dependencies.map { it.toJson() }.toTypedArray()),
    )
}

data class DocumentEnvelope(
    val detail: DocumentDetail,
    val ampDocument: Int = 1,
) {

    fun toJson(): JsonValue.Obj = jsonObj(
        "ampDocument" to jsonNumber(ampDocument),
        "detail" to detail.toJson(),
    )
}

/**
 * 参考实现的 `JSON.stringify({ampDocument: 1, detail})`；这里用规范 JSON
 * 编码（键排序不同，解析语义相同，细节本身也不参与摘要计算）。
 */
fun encodeDocumentDetail(detail: DocumentDetail): String =
    CanonicalJson.encode(DocumentEnvelope(detail).toJson())

/**
 * 读者绝不跟随内嵌 ref、图边或后代 ID：只读这一条已捕获的内容引用。
 * 旧版纯文本细节无需迁移即可继续读取。
 */
fun readDocumentDetail(context: Context, nodeId: String): DocumentDetail? {
    val content: String = context.expand(nodeId) ?: return null
    try {
        val value: JsonValue = JsonParser.parse(content)
        if (value is JsonValue.Obj && value.int("ampDocument") == 1) {
            val detail = value.obj("detail")
            if (detail != null) {
                DocumentDetail.fromJson(detail)?.let { return it }
            }
        }
    } catch (_: Exception) {
        // 落到纯文本回退。
    }
    return TextDetail(content)
}
