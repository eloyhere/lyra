package com.eloykim.lyra.amp.protocol.error

/**
 * `src/error/authzError.ts` 的移植：被拒绝的能力，或会放大权利/寿命的 grant。
 *
 * 与参考实现一样共享 [AmpError] 形状，内核可把任意一个转成 blocked receipt。
 */
enum class AuthzCode(override val wire: String) : AmpErrorCode {
    PERMISSION_DENIED("permission_denied"),
    INVALID_GRANT("invalid_grant"),
    RIGHTS_AMPLIFICATION("rights_amplification"),
    EXPIRY_AMPLIFICATION("expiry_amplification"),
    UNKNOWN_CAPABILITY("unknown_capability"),
    ;
}

class AuthzError(
    val typedCode: AuthzCode,
    problems: List<String>,
) : AmpError("AuthzError", typedCode.wire, problems)
