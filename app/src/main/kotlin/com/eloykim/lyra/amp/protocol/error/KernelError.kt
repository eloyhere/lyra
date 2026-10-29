package com.eloykim.lyra.amp.protocol.error

/**
 * `src/error/kernelError.ts` 的移植：非法的进程/线程迁移与耗尽资源。
 *
 * 与参考实现一样共享 [AmpError] 形状（`code` + 全部问题），调用方无需特判就能把任意
 * 一个转成 blocked receipt。
 */
enum class KernelCode(override val wire: String) : AmpErrorCode {
    INVALID_PROCESS("invalid_process"),
    INVALID_THREAD("invalid_thread"),
    INVALID_TRANSITION("invalid_transition"),
    UNKNOWN_PROCESS("unknown_process"),
    UNKNOWN_THREAD("unknown_thread"),
    BUDGET_EXCEEDED("budget_exceeded"),
    MISSING_RECEIPT("missing_receipt"),
    AGENT_BUSY("agent_busy"),
    CLAIM_FAILED("claim_failed"),
    LEASE_LOST("lease_lost"),
    UNKNOWN_OBJECT("unknown_object"),
    DUPLICATE_TASK("duplicate_task"),
    ;
}

class KernelError(
    val typedCode: KernelCode,
    problems: List<String>,
) : AmpError("KernelError", typedCode.wire, problems)
