package com.eloykim.lyra.amp.protocol.error

/**
 * `src/error/ampError.ts` 的移植：带机器可读 code、并一次携带全部问题的共享基类。
 *
 * Kotlin 禁止 `Throwable` 子类带类型参数，因此基类不泛型化：`code` 是参考实现里的
 * 字符串字面量，各子类另用枚举把 code 收窄成稳定分类，并通过 `typedCode` 暴露。
 *
 * 消息格式严格为 `<code>: <problems joined by "; ">`；`problems` 在构造时复制，
 * 与参考实现 `[...problems]` 一致，调用方之后修改原数组不会改变错误。
 */
interface AmpErrorCode {

    /** 线上/日志里出现的稳定小写 code。 */
    val wire: String
}

open class AmpError(
    /** 参考实现 `Error.name`；子类传入自己的类名。 */
    val name: String,
    val code: String,
    problems: List<String>,
) : RuntimeException("$code: ${problems.joinToString("; ")}") {

    /** 本次尝试发现的全部问题，按发现顺序。 */
    val problems: List<String> = ArrayList(problems)

    /** 用枚举 code 构造；枚举的 `wire` 就是参考实现的字符串字面量。 */
    constructor(name: String, code: AmpErrorCode, problems: List<String>) :
        this(name = name, code = code.wire, problems = problems)

    /** `Error.prototype.toString` 的输出：`<name>: <message>`。 */
    override fun toString(): String = "$name: $message"
}
