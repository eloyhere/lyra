package com.eloykim.lyra.amp.protocol.error

/**
 * `src/error/boundaryRejection.ts` 的移植：`src/boundary` 的构造器唯一允许抛出的错误。
 *
 * 边界层的承诺是绝不返回非法对象：构造整体失败，并携带本次发现的全部问题，调用方
 * 只需记录一个机器可读原因和一张审计 receipt，而不是一个可能被后续检查漏掉的部分
 * 合法对象。
 */
enum class BoundaryCode(override val wire: String) : AmpErrorCode {
    INVALID_FRAME("invalid_frame"),
    INVALID_TOOL_ARGS("invalid_tool_args"),
    UNRESOLVABLE_REF("unresolvable_ref"),
    DUPLICATE_REF("duplicate_ref"),
    INVALID_ID("invalid_id"),
    ;
}

class BoundaryRejection(
    val typedCode: BoundaryCode,
    problems: List<String>,
) : AmpError("BoundaryRejection", typedCode.wire, problems)
