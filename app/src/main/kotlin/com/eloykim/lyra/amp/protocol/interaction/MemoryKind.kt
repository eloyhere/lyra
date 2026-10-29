package com.eloykim.lyra.amp.protocol.interaction

/**
 * 窄局部模型：`src/process/agent.ts` 的 `MemoryKind`。
 *
 * `src/process/agent.ts`（agent facets）尚未移植，而 `interaction.ts` 与
 * `scenario.ts` 都依赖这五个记忆类别。参考实现只有一个定义，因此这里定义一次：
 * interaction 包拥有它，scenario 包导入复用；将来 `process` 包移植后，把它换成
 * 那边的枚举即可（其余代码只读 [wire]）。
 */
enum class MemoryKind(val wire: String) {
    EPISODIC("episodic"),
    SEMANTIC("semantic"),
    PROCEDURAL("procedural"),
    SOCIAL("social"),
    AFFECTIVE("affective"),
    ;

    companion object {
        fun fromWire(value: String): MemoryKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("未知 memory kind: $value")
    }
}
