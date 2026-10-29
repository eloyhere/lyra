package com.eloykim.lyra.amp.protocol.runtime

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * `src/runtime/behaviorTuning.ts` 的移植：从冲突统计里给出保守的优先级建议。
 *
 * 它从不自动改动注册表：只产出一份供人复核的提案。硬护栏永不降低。
 */
data class PrioritySuggestion(
    val behaviorId: String,
    val current: Int,
    val suggested: Int,
    val reason: String,
)

data class TuningOptions(
    val threshold: Int? = null,
    val maxDelta: Int? = null,
)

/** 累积到某个行为的优先级变化及其理由。 */
private data class DeltaEntry(
    var delta: Int = 0,
    val reason: MutableList<String> = ArrayList(),
)

/**
 * 只读的调优建议器。
 *
 * `conflicts` 的键是 `"winner<-loser"`（[BehaviorConflict] 的计数形式）。
 */
fun suggestPriorityTuning(
    behaviors: List<Behavior>,
    conflicts: Map<String, Int>,
    options: TuningOptions = TuningOptions(),
): List<PrioritySuggestion> {
    val threshold = options.threshold ?: 3
    val maxDelta = options.maxDelta ?: 5
    val byId = behaviors.associateBy { it.id }
    val deltas = LinkedHashMap<String, DeltaEntry>()

    fun add(id: String, delta: Int, reason: String) {
        val entry = deltas.getOrPut(id) { DeltaEntry() }
        entry.delta += delta
        entry.reason.add(reason)
    }

    for ((key, count) in conflicts) {
        if (count < threshold) {
            continue
        }
        val parts = key.split("<-")
        val winnerId = parts.getOrNull(0) ?: continue
        val loserId = parts.getOrNull(1) ?: continue
        val winner = byId[winnerId] ?: continue
        val loser = byId[loserId] ?: continue
        add(winnerId, 1, "$winnerId beat $loserId ${count}x")
        if (loser.policy.hardGuardrail != true) {
            add(loserId, -1, "$loserId lost to $winnerId ${count}x")
        }
    }

    val suggestions = ArrayList<PrioritySuggestion>()
    for ((id, entry) in deltas) {
        val behavior = byId[id] ?: continue
        val delta = max(-maxDelta, min(maxDelta, entry.delta))
        if (delta == 0) {
            continue
        }
        suggestions.add(
            PrioritySuggestion(
                behaviorId = id,
                current = behavior.priority,
                suggested = max(0, min(100, behavior.priority + delta)),
                reason = entry.reason.joinToString("; "),
            ),
        )
    }
    return suggestions.sortedByDescending { abs(it.suggested - it.current) }
}

/** 返回调优后的副本；从不改动注册表里的行为列表。 */
fun applyPrioritySuggestions(
    behaviors: List<Behavior>,
    suggestions: List<PrioritySuggestion>,
): List<Behavior> {
    val byId = suggestions.associateBy { it.behaviorId }
    return behaviors.map { behavior ->
        val suggestion = byId[behavior.id]
        if (suggestion == null) behavior else behavior.copy(priority = suggestion.suggested)
    }
}

/** 恢复原始优先级；不改动传入的调优列表。 */
fun rollbackPrioritySuggestions(
    tuned: List<Behavior>,
    original: List<Behavior>,
): List<Behavior> {
    val byId = original.associateBy { it.id }
    return tuned.map { behavior ->
        val source = byId[behavior.id]
        if (source == null) behavior else behavior.copy(priority = source.priority)
    }
}

data class AutoTuneOptions(
    val enabled: Boolean,
    val threshold: Int? = null,
)

data class AutoTuneResult(
    val suggestions: List<PrioritySuggestion>,
    val applied: Int,
    val version: Int,
)

/**
 * 需显式开启的控制器：默认只建议；启用后才应用有界变更。
 *
 * 相同的冲突统计只应用一次（参考实现用 `JSON.stringify(conflicts)` 做签名，
 * 这里用按键排序的确定性签名）。
 */
class BehaviorTuningController(private val registry: BehaviorRegistry) {

    private var lastSignature: String = ""

    fun autoApply(conflicts: Map<String, Int>, options: AutoTuneOptions): AutoTuneResult {
        val suggestions = suggestPriorityTuning(
            registry.list(),
            conflicts,
            TuningOptions(threshold = options.threshold),
        )
        val signature = conflicts.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }
        if (!options.enabled || suggestions.isEmpty() || signature == lastSignature) {
            return AutoTuneResult(suggestions, 0, registry.tuning())
        }
        val changes = LinkedHashMap<String, Int>()
        for (suggestion in suggestions) {
            changes[suggestion.behaviorId] = suggestion.suggested
        }
        val result = registry.applyPriorities(changes)
        lastSignature = signature
        return AutoTuneResult(suggestions, result.applied.size, result.version)
    }

    fun rollback(version: Int): Boolean = registry.rollbackPriorities(version)
}
