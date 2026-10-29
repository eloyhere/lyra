package com.eloykim.lyra.amp.protocol.error

/** `src/error/mailboxError.ts` 的移植：进程间邮箱的三种稳定失败。 */
enum class MailboxCode(override val wire: String) : AmpErrorCode {
    MAILBOX_FULL("mailbox_full"),
    UNKNOWN_MESSAGE("unknown_message"),
    UNKNOWN_RECIPIENT("unknown_recipient"),
    ;
}

class MailboxError(
    val typedCode: MailboxCode,
    problems: List<String>,
) : AmpError("MailboxError", typedCode.wire, problems)
