package com.eloykim.lyra.amp.protocol.runtime

/**
 * `src/runtime/telemetry.ts` 的移植（含 Android 适配说明）。
 *
 * 遥测是进程级的事实，不是人的疲劳模型：这些字段只用于调度注意力与休息。
 *
 * 绑定说明：参考实现的 `NodeTelemetry` 用 `os.loadavg()` / `monitorEventLoopDelay`
 * / `process.memoryUsage()`，这些在 Android 上没有对应 API。本端口保留
 * [TelemetrySample] 与 [TelemetrySource] 的公共形状，并提供一个 JVM 适配实现
 * [ProcessTelemetry]：
 * - `memoryMb` 用 `Runtime.totalMemory - freeMemory`（等价于 RSS 的近似）；
 * - `systemLoad` 无法在 Android 上取得，留空，由宿主注入自己的采样器；
 * - `eventLoopLagMs` 没有事件循环可言，报 0（参考实现在拿不到直方图均值时同样报 0）。
 */
data class TelemetrySample(
    /** 0–1，按 CPU 数归一。 */
    val systemLoad: Double? = null,
    val eventLoopLagMs: Double? = null,
    val memoryMb: Double? = null,
    val queueDepth: Int? = null,
    val errorRate: Double? = null,
)

fun interface TelemetrySource {
    fun sample(): TelemetrySample
}

/** 固定值的遥测源：测试与回放用。 */
class StaticTelemetry(private val value: TelemetrySample) : TelemetrySource {

    override fun sample(): TelemetrySample = value.copy()
}

/**
 * 进程级遥测（参考 `NodeTelemetry` 的 Android/JVM 适配）。
 *
 * 只采样平台确实能给的字段；`systemLoad` 留给宿主实现，不猜测。
 */
class ProcessTelemetry : TelemetrySource {

    override fun sample(): TelemetrySample {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return TelemetrySample(
            systemLoad = null,
            eventLoopLagMs = 0.0,
            memoryMb = used.toDouble() / (1024.0 * 1024.0),
        )
    }

    /** 与参考实现的 `close()` 对应；本适配器没有需要关闭的直方图。 */
    fun close() = Unit
}
