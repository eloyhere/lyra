package com.eloykim.lyra.amp.protocol.concept

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import java.time.Instant
import java.util.Locale
import kotlin.math.max

/**
 * Concept economy：约束一个概念的内容，也约束它保留多少版本。
 *
 * 规则只回答一个问题：删掉什么不会让概念变得更不像概念？重复的例子、重复的
 * 关系、解析不到的关系目标、超出阅读预算的例子可以走；定义、intension、
 * 反例，以及最后一个让概念保持接地的例子永远不走。
 *
 * 不变量（对应 `docs/compaction.md` §6 的 C-1..C-4）：
 *
 * - **C-1** 视图不是修订：[pruneConcept] 从不修改 `version`、`definition`、
 *   `intension`、`counterexamples` 或 `grounded_in`。
 * - **C-2** 接地保底：`examples` 可以收缩到 `maxExamples`，但不会低于
 *   `minExamples`，也不会被删空。
 * - **C-3** 每次删除都留下 `reason` 与 `revisit_if`；计划里的 `version` 等于被
 *   裁剪概念的版本。
 * - **C-4** 压缩只在谱系连续、且保留窗口之外还有版本时发生；`checkpoint` 的
 *   `root` / `tip` 提交被删版本，`head` 必须从 `to + 1` 继续。
 *
 * 对应参考实现 `src/concept/conceptBudget.ts`。
 */
val DefaultConceptBudget: ConceptBudget = ConceptBudget(
    maxExamples = 8,
    minExamples = 1,
    maxRelations = 12,
    keepVersions = 1,
)

/** [pruneConcept] 的预算覆盖项；未给出的字段取 [DefaultConceptBudget]。 */
data class ConceptBudgetOverrides(
    val maxExamples: Int? = null,
    val minExamples: Int? = null,
    val maxRelations: Int? = null,
    val keepVersions: Int? = null,
)

/**
 * 关系目标的解析器。
 *
 * 不提供时无法检测 dangling 关系，其余规则不受影响。
 */
data class ConceptPruneOptions(
    val known: ((kind: String, id: String) -> Boolean)? = null,
)

data class ConceptPruneResult(
    val concept: Concept,
    val plan: ConceptPrunePlan,
)

/** JavaScript `\s` 对应的空白类（含 NBSP、全角空格）。 */
private val ConceptWhitespace = Regex("[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

private fun normaliseText(text: String): String =
    text.trim().lowercase(Locale.ROOT).replace(ConceptWhitespace, " ")

/** 参考实现用 `new TextEncoder().encode(JSON.stringify(value)).length` 计量回收字节。 */
private fun utf8Bytes(value: JsonValue): Int =
    CanonicalJson.encode(value).toByteArray(Charsets.UTF_8).size

private fun typicalityOf(example: ConceptExample): Double = example.typicality ?: 0.5

private fun clip(text: String, limit: Int = 60): String {
    val flat = text.trim().replace(ConceptWhitespace, " ")
    return if (flat.length <= limit) flat else flat.take(limit - 1) + "…"
}

private fun resolveBudget(overrides: ConceptBudgetOverrides): ConceptBudget {
    val minExamples = max(0, overrides.minExamples ?: DefaultConceptBudget.minExamples)
    return ConceptBudget(
        maxExamples = max(minExamples, overrides.maxExamples ?: DefaultConceptBudget.maxExamples),
        minExamples = minExamples,
        maxRelations = max(0, overrides.maxRelations ?: DefaultConceptBudget.maxRelations),
        keepVersions = max(1, overrides.keepVersions ?: DefaultConceptBudget.keepVersions),
    )
}

/**
 * 一个概念的有界视图。
 *
 * 确定性：输入不被修改，同样的输入给出同样的计划。`version` 原样带过去——视图
 * 不是修订，在这里 bump version 等于伪造谱系。
 */
fun pruneConcept(
    concept: Concept,
    overrides: ConceptBudgetOverrides = ConceptBudgetOverrides(),
    options: ConceptPruneOptions = ConceptPruneOptions(),
): ConceptPruneResult {
    val budget = resolveBudget(overrides)
    val dropped = ArrayList<ConceptDrop>()
    var reclaimed = 0

    fun drop(
        slot: ConceptDropSlot,
        index: Int,
        summary: String,
        reason: ConceptDropReason,
        revisitIf: String,
        raw: JsonValue,
    ) {
        dropped += ConceptDrop(slot = slot, index = index, summary = summary, reason = reason, revisitIf = revisitIf)
        reclaimed += utf8Bytes(raw)
    }

    // 例子：先折叠重复，再按上限裁剪，且绝不越过下限。
    var viewExamples: List<ConceptExample>? = null
    concept.examples?.let { examples ->
        val unique = LinkedHashMap<String, Pair<ConceptExample, Int>>()
        examples.forEachIndexed { index, example ->
            val key = normaliseText(example.description)
            val existing = unique[key]
            if (existing == null) {
                unique[key] = example to index
                return@forEachIndexed
            }
            if (typicalityOf(example) > typicalityOf(existing.first)) {
                // 新来的更能代表这个概念，走的是更早的那条。
                drop(
                    ConceptDropSlot.EXAMPLE,
                    existing.second,
                    clip(existing.first.description),
                    ConceptDropReason.DUPLICATE,
                    "if the kept example is removed as atypical",
                    existing.first.toJson(),
                )
                unique[key] = example to index
                return@forEachIndexed
            }
            drop(
                ConceptDropSlot.EXAMPLE,
                index,
                clip(example.description),
                ConceptDropReason.DUPLICATE,
                "if this example is rewritten with a detail the kept one lacks",
                example.toJson(),
            )
        }

        val kept = unique.values.sortedBy { it.second }
        viewExamples = if (kept.size > budget.maxExamples) {
            val ranked = kept.sortedWith(
                compareByDescending<Pair<ConceptExample, Int>> { typicalityOf(it.first) }
                    .thenBy { it.second },
            )
            val keep = ranked.take(budget.maxExamples).map { it.second }.toSet()
            ranked.forEach { item ->
                if (keep.contains(item.second)) return@forEach
                drop(
                    ConceptDropSlot.EXAMPLE,
                    item.second,
                    clip(item.first.description),
                    ConceptDropReason.OVER_BUDGET,
                    "if the concept needs a broader extension again",
                    item.first.toJson(),
                )
            }
            kept.filter { keep.contains(it.second) }.map { it.first }
        } else {
            kept.map { it.first }
        }
    }

    // 反例是证伪物，永不裁剪（W-2）。

    // 关系：先重复，再 dangling，最后超预算。
    var viewRelations: List<ConceptRelation>? = null
    concept.relations?.let { relations ->
        val seen = LinkedHashSet<String>()
        val surviving = ArrayList<Pair<ConceptRelation, Int>>()
        relations.forEachIndexed { index, relation ->
            val key = "${relation.type.wire}|${relation.target.kind.wire}|${relation.target.id}"
            val summary = "${relation.type.wire} → ${relation.target.kind.wire}:${relation.target.id}"
            if (!seen.add(key)) {
                drop(
                    ConceptDropSlot.RELATION,
                    index,
                    summary,
                    ConceptDropReason.DUPLICATE,
                    "if the duplicate gains a distinct provenance or weight",
                    relation.toJson(),
                )
                return@forEachIndexed
            }
            val known = options.known
            if (known != null && !known(relation.target.kind.wire, relation.target.id)) {
                drop(
                    ConceptDropSlot.RELATION,
                    index,
                    summary,
                    ConceptDropReason.DANGLING,
                    "if ${relation.target.kind.wire}:${relation.target.id} is imported again",
                    relation.toJson(),
                )
                return@forEachIndexed
            }
            surviving += relation to index
        }

        val keep = surviving.take(budget.maxRelations)
        surviving.drop(budget.maxRelations).forEach { item ->
            drop(
                ConceptDropSlot.RELATION,
                item.second,
                "${item.first.type.wire} → ${item.first.target.kind.wire}:${item.first.target.id}",
                ConceptDropReason.OVER_BUDGET,
                "if the relation becomes load-bearing for a query",
                item.first.toJson(),
            )
        }
        viewRelations = keep.map { it.first }
    }

    // Extension：只去重。成员引用可能来自外部，因此不做 dangling 检查。
    var viewExtension: List<Ref>? = null
    concept.extension?.let { extension ->
        val seen = LinkedHashSet<String>()
        val kept = ArrayList<Ref>()
        extension.forEachIndexed { index, ref ->
            val key = "${ref.kind.wire}:${ref.id}"
            if (seen.add(key)) {
                kept += ref
            } else {
                drop(
                    ConceptDropSlot.EXTENSION,
                    index,
                    key,
                    ConceptDropReason.DUPLICATE,
                    "if the duplicate comes from a different source",
                    ref.toJson(),
                )
            }
        }
        viewExtension = kept
    }

    val view = concept.copy(
        examples = viewExamples,
        relations = viewRelations,
        extension = viewExtension,
    )
    return ConceptPruneResult(
        concept = view,
        plan = ConceptPrunePlan(
            concept = concept.id,
            version = concept.version,
            dropped = dropped,
            reclaimedBytes = reclaimed,
            budget = budget,
        ),
    )
}

/** 压缩被拒绝：谱系断裂或保留窗口覆盖全部版本时，宁可拒绝也不断言完整性。 */
class ConceptCompactionRefused(message: String) : IllegalArgumentException(message)

data class ConceptHistoryCompaction(
    val checkpoint: ConceptCheckpoint,
    val head: Concept,
    val removed: List<Concept>,
)

/**
 * 用检查点替换除保留尾段之外的每个版本。
 *
 * 版本序列有缺口时拒绝：建立在断裂谱系上的检查点会一边声称没有缺失、一边无法
 * 证明这一点。
 */
fun compactConceptHistory(
    versions: List<Concept>,
    keepVersions: Int = 1,
    now: String? = null,
): ConceptHistoryCompaction {
    if (versions.isEmpty()) {
        throw ConceptCompactionRefused("no versions to compact")
    }

    val ordered = versions.sortedBy { it.version }
    val id = ordered.first().id
    ordered.forEach { version ->
        if (version.id != id) {
            throw ConceptCompactionRefused("versions belong to different concepts: $id and ${version.id}")
        }
    }
    for (index in 1 until ordered.size) {
        if (ordered[index].version != ordered[index - 1].version + 1) {
            throw ConceptCompactionRefused(
                "lineage is broken between version ${ordered[index - 1].version} and ${ordered[index].version}",
            )
        }
    }

    val retain = max(1, keepVersions)
    val cut = ordered.size - retain
    if (cut <= 0) {
        throw ConceptCompactionRefused("nothing to compact: every version is inside the retention window")
    }

    val removed = ordered.take(cut)
    val head = ordered.last()
    val last = removed.last()

    return ConceptHistoryCompaction(
        checkpoint = ConceptCheckpoint(
            id = "cp.concept.$id.${last.version}",
            concept = id,
            from = removed.first().version,
            to = last.version,
            count = removed.size,
            tip = CanonicalJson.digest(last.toJson()),
            root = CanonicalJson.digest(JsonValue.Arr(removed.map { it.toJson() })),
            created = now ?: Instant.now().toString(),
            budget = ConceptCheckpointBudget(retain),
        ),
        head = head,
        removed = removed,
    )
}

data class CheckpointVerdict(
    val ok: Boolean,
    val reasons: List<String>,
)

/** 对照检查点声称覆盖的版本，校验 root / count / 区间 / tip 与头版本连续性。 */
fun verifyConceptCheckpoint(
    checkpoint: ConceptCheckpoint,
    removed: List<Concept>,
    head: Concept? = null,
): CheckpointVerdict {
    val reasons = ArrayList<String>()

    if (CanonicalJson.digest(JsonValue.Arr(removed.map { it.toJson() })) != checkpoint.root) {
        reasons += "checkpoint root does not cover the removed versions"
    }
    if (removed.size != checkpoint.count) {
        reasons += "checkpoint count ${checkpoint.count} does not match ${removed.size} removed versions"
    }

    for (index in 1 until removed.size) {
        if (removed[index].version != removed[index - 1].version + 1) {
            reasons += "removed versions are not contiguous at ${removed[index - 1].version} → ${removed[index].version}"
        }
    }

    val first = removed.firstOrNull()
    val last = removed.lastOrNull()
    if (first == null || last == null) {
        reasons += "no versions were removed"
    } else {
        if (first.version != checkpoint.from) {
            reasons += "checkpoint starts at ${checkpoint.from} but the first removed version is ${first.version}"
        }
        if (last.version != checkpoint.to) {
            reasons += "checkpoint ends at ${checkpoint.to} but the last removed version is ${last.version}"
        }
        if (CanonicalJson.digest(last.toJson()) != checkpoint.tip) {
            reasons += "checkpoint tip does not match the last removed version"
        }
        removed.forEach { version ->
            if (version.id != checkpoint.concept) {
                reasons += "removed version ${version.version} belongs to ${version.id}"
            }
        }
    }

    head?.let {
        if (it.id != checkpoint.concept) {
            reasons += "head belongs to ${it.id}, not ${checkpoint.concept}"
        }
        if (it.version != checkpoint.to + 1) {
            reasons += "head version ${it.version} does not continue from ${checkpoint.to}"
        }
    }

    return CheckpointVerdict(ok = reasons.isEmpty(), reasons = reasons)
}
