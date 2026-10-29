package com.eloykim.lyra.amp.protocol.module

/**
 * 沙箱端口（`src/module/sandbox/sandbox.ts` 的移植）。
 *
 * 参考实现没有安全沙箱，因此默认策略是拒绝；拥有沙箱的宿主自己实现 [Sandbox]。
 * [DeniedSandbox] 让"没有隔离"这件事显式化，而不是假装一次 `ProcessBuilder`
 * 调用就是隔离。**本模块绝不执行任意代码。**
 */
data class SandboxCommand(
    val command: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val timeoutMs: Long? = null,
)

data class SandboxResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

fun interface Sandbox {
    fun run(command: SandboxCommand): SandboxResult
}

class SandboxUnavailableException(
    message: String = "sandbox_unavailable: no secure sandbox is configured",
) : IllegalStateException(message)

class DeniedSandbox : Sandbox {

    override fun run(command: SandboxCommand): SandboxResult =
        throw SandboxUnavailableException()
}
