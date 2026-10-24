package com.eloykim.lyra.agent

import com.eloykim.lyra.attention.Admission
import com.eloykim.lyra.attention.BudgetDimension
import com.eloykim.lyra.attention.BudgetScheduler
import com.eloykim.lyra.attention.HandleStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * 一次任务执行的循环。
 *
 * 现在只有「执行」这一半：决策来自 [AgentBackend]。
 * 真实模型接进来时，换一个 backend 即可，循环本身不变。
 *
 * 每一步都会写入 [RunRecorder]（生产环境是 SQLite），用于回放与用户画像。
 */
class AgentSession(
    private val gateway: AutomationGateway,
    private val backend: AgentBackend,
    private val recorder: RunRecorder = RunRecorder.Noop,
    private val maxSteps: Int = 24,
    /** AMP 事件出口；Noop 时行为与旧版一致。 */
    private val events: EventSink = EventSink.Noop,
    /** AMP A-3 的唯一计费点；为空时只受 maxSteps 约束。 */
    private val budget: BudgetScheduler? = null,
    /** 大结果句柄；为空时 read 动词返回不可用。 */
    private val handles: HandleStore? = null,
    /** 聊天记录检索；为空时 search_messages 返回不可用。 */
    private val searchChatHistory: (suspend (String, Int) -> String)? = null,
    /** 对具体消息设置表情；返回错误文本，成功返回 null。 */
    private val reactToMessage: (suspend (Long, String) -> String?)? = null,
    /** 生成消息引用；返回引用 Markdown，失败返回错误文本。 */
    private val quoteMessage: (suspend (Long) -> String?)? = null,
    /** 应用目录服务；为空时 application 返回不可用。 */
    private val apps: AppCatalogService? = null,
    /** 语音播报服务；为空时 voice 返回不可用。 */
    private val voice: VoiceService? = null,
    /** Frame 落库；Noop 时只跑不记。 */
    private val frames: FrameRecorder = FrameRecorder.Noop,
    /** 僵局检测（AMP §3.6）。 */
    private val impasse: ImpasseDetector = ImpasseDetector(),
) {

    fun run(task: String): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started(task))
        backend.reset(task)

        val runId = runCatching { recorder.startRun(task) }.getOrDefault(0L)
        impasse.reset()
        events.record("run.started", """{"task":"${jsonEscape(task)}"}""", emptyList())

        val first = runCatching { gateway.observe() }.getOrNull()
        if (first == null) {
            emit(AgentEvent.Failed("无法读取屏幕：无障碍服务未连接？"))
            recorder.finishRun(runId, "failed", "无可用观察")
            return@flow
        }
        var observation: Observation = first
        emit(AgentEvent.Observed(observation))
        recordObserve(runId, 0, observation)

        var steps = 0
        val notes = mutableListOf<String>()

        while (steps < maxSteps) {
            steps++
            chargeOrReason(Charge.STEP)?.let { reason ->
                failOnBudget(this, runId, reason)
                return@flow
            }
            val modelStart = System.currentTimeMillis()
            val verb = runCatching { backend.next(observation, steps) }.getOrElse { error ->
                // 模型/网络失败不应该让整个协程崩掉，要落库并给用户一个可读原因
                val reason = error.message ?: error::class.java.simpleName
                recorder.finishRun(runId, "failed", "决策失败：$reason")
                emit(AgentEvent.Failed("模型调用失败：$reason"))
                return@flow
            }
            chargeOrReason(Charge.MS, System.currentTimeMillis() - modelStart)?.let { reason ->
                failOnBudget(this, runId, reason)
                return@flow
            }
            backend.lastCostMicros?.let { cost ->
                if (cost > 0) {
                    chargeOrReason(Charge.MONEY, cost)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                }
            }
            backend.lastUsage?.let { usage ->
                chargeOrReason(Charge.TOKENS, usage.total.toLong())?.let { reason ->
                    failOnBudget(this, runId, reason)
                    return@flow
                }
            }
            backend.lastFrame?.let { frame -> runCatching { frames.record(runId, frame) } }
            events.record(
                "verb.proposed",
                """{"seq":$steps,"verb":"${verbName(verb)}"}""",
                emptyList(),
            )
            emit(AgentEvent.Decided(verb))

            when (verb) {
                is Verb.Done -> {
                    events.record("run.finished", """{"status":"succeeded","steps":$steps}""", emptyList())
                    val summary = if (notes.isEmpty()) "完成" else notes.joinToString("；")
                    recorder.finishRun(runId, "succeeded", summary)
                    emit(AgentEvent.Finished(summary, steps))
                    return@flow
                }

                is Verb.Attend -> {
                    val next = runCatching { gateway.observe() }.getOrNull()
                    if (next == null) {
                        recorder.finishRun(runId, "failed", "观察失败")
                        emit(AgentEvent.Failed("观察失败"))
                        return@flow
                    }
                    observation = next
                    emit(AgentEvent.Observed(observation))
                    recordObserve(runId, steps, observation)
                }

                is Verb.Act -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val started = System.currentTimeMillis()
                    val result = execute(verb, observation)
                    recorder.recordStep(
                        runId,
                        StepRecord(
                            seq = steps,
                            kind = "act",
                            action = verb.action,
                            packageName = observation.packageName,
                            targetPackage = if (verb.action.lowercase() in setOf("launch", "open")) verb.packageName else null,
                            targetLabel = observation.element(verb.nodeId ?: -1)?.label?.take(80),
                            nodeId = verb.nodeId,
                            ok = result is ActionResult.Ok,
                            code = when (result) {
                                is ActionResult.Blocked -> result.code
                                is ActionResult.NeedsConsent -> "authorization_required"
                                is ActionResult.Failed -> result.code
                                is ActionResult.Ok -> "ok"
                            },
                            message = when (result) {
                                is ActionResult.Blocked -> result.message
                                is ActionResult.NeedsConsent -> result.message
                                is ActionResult.Failed -> result.message
                                is ActionResult.Ok -> null
                            },
                            startedAt = started,
                            durationMs = System.currentTimeMillis() - started,
                        ),
                    )
                    events.record(
                        "receipt",
                        """{"seq":$steps,"op":"act","action":"${jsonEscape(verb.action)}",""" +
                            """"ok":${result is ActionResult.Ok},"ms":${System.currentTimeMillis() - started},""" +
                            """"tokens_in":${backend.lastUsage?.inputTokens ?: 0},""" +
                            """tokens_out":${backend.lastUsage?.outputTokens ?: 0},""" +
                            """"cost_micros":${backend.lastCostMicros ?: 0},""" +
                            """"predicted_voi":${backend.lastPredictedVoi ?: 0.0},""" +
                            """"realized_voi":${if (result is ActionResult.Ok) 1.0 else 0.0}}""",
                        emptyList(),
                    )
                    chargeOrReason(Charge.MS, System.currentTimeMillis() - started)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    backend.onResult(
                        verb,
                        ok = result is ActionResult.Ok,
                        code = when (result) {
                            is ActionResult.Blocked -> result.code
                            is ActionResult.NeedsConsent -> "authorization_required"
                            is ActionResult.Failed -> result.code
                            is ActionResult.Ok -> null
                        },
                        message = when (result) {
                            is ActionResult.Blocked -> result.message
                            is ActionResult.NeedsConsent -> result.message
                            is ActionResult.Failed -> result.message
                            is ActionResult.Ok -> null
                        },
                    )
                    emit(AgentEvent.Acted(verb, result))

                    checkImpasse(this, runId, steps, verb, result, observation)?.let { return@flow }
                    when (result) {
                        is ActionResult.Ok -> result.observation?.let {
                            observation = it
                            emit(AgentEvent.Observed(it))
                            recordObserve(runId, steps, it)
                        }

                        is ActionResult.Blocked -> {
                            recorder.finishRun(runId, "blocked", result.message)
                            emit(AgentEvent.Failed("被安全策略拒绝：${result.message}"))
                            return@flow
                        }

                        is ActionResult.NeedsConsent -> {
                            chargeOrReason(Charge.INTERRUPT)?.let { reason ->
                                failOnBudget(this, runId, reason)
                                return@flow
                            }
                            events.record(
                                "interrupt",
                                """{"class":"permission","question":"${jsonEscape(result.message.take(120))}"}""",
                                emptyList(),
                            )
                            recorder.finishRun(runId, "needs_consent", result.message)
                            emit(AgentEvent.NeedsUser(result.message))
                            return@flow
                        }

                        is ActionResult.Failed -> {
                            notes += "动作失败(${result.code})"
                            result.observation?.let {
                                observation = it
                                emit(AgentEvent.Observed(it))
                            }
                        }
                    }
                }

                is Verb.Ask -> {
                    chargeOrReason(Charge.INTERRUPT)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    events.record(
                        "interrupt",
                        """{"class":"${verb.kind}","question":"${jsonEscape(verb.question.take(120))}"}""",
                        emptyList(),
                    )
                    recorder.recordStep(
                        runId,
                        StepRecord(seq = steps, kind = "ask", message = verb.question),
                    )
                    recorder.finishRun(runId, "needs_user", verb.question)
                    emit(AgentEvent.NeedsUser(verb.question))
                    return@flow
                }

                is Verb.Note -> {
                    notes += verb.assertion
                    recorder.recordStep(
                        runId,
                        StepRecord(seq = steps, kind = "note", message = verb.assertion.take(200)),
                    )
                    emit(AgentEvent.Noted(verb.assertion))
                }

                is Verb.Plan -> {
                    recorder.recordStep(runId, StepRecord(seq = steps, kind = "plan", message = verb.goal))
                    emit(AgentEvent.Noted("计划：${verb.goal}"))
                }

                is Verb.SearchMessages -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val content = searchChatHistory?.invoke(verb.query, verb.limit)
                    backend.onResult(
                        verb,
                        ok = content != null,
                        code = if (content == null) "message_service_unavailable" else null,
                        message = content.orEmpty(),
                    )
                    events.record(
                        "message.search",
                        """{"query":"${jsonEscape(verb.query)}","limit":${verb.limit},""" +
                            """"chars":${content?.length ?: 0}}""",
                        emptyList(),
                    )
                    emit(AgentEvent.Noted("检索聊天记录「${verb.query}」：${content?.length ?: 0} 字"))
                }

                is Verb.ReactMessage -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val error = if (reactToMessage == null) {
                        "message_service_unavailable"
                    } else {
                        reactToMessage.invoke(verb.messageId, verb.reaction)
                    }
                    val ok = error == null
                    backend.onResult(
                        verb,
                        ok = ok,
                        code = if (ok) null else "reaction_failed",
                        message = error,
                    )
                    events.record(
                        "message.react",
                        """{"messageId":${verb.messageId},"reaction":"${jsonEscape(verb.reaction)}","ok":$ok}""",
                        emptyList(),
                    )
                    emit(
                        AgentEvent.Noted(
                            if (ok) {
                                "已评论消息 ${verb.messageId}：${verb.reaction}"
                            } else {
                                "评论消息 ${verb.messageId} 失败：$error"
                            },
                        ),
                    )
                }

                is Verb.QuoteMessage -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val quote = quoteMessage?.invoke(verb.messageId)
                    val ok = quote != null
                    backend.onResult(
                        verb,
                        ok = ok,
                        code = if (ok) null else "quote_failed",
                        message = quote,
                    )
                    events.record(
                        "message.quote",
                        """{"messageId":${verb.messageId},"ok":$ok}""",
                        emptyList(),
                    )
                    emit(
                        AgentEvent.Noted(
                            if (ok) "已引用消息 ${verb.messageId}" else "引用消息失败：$quote",
                        ),
                    )
                }

                is Verb.Application -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val started = System.currentTimeMillis()
                    val result = if (apps == null) {
                        ServiceResult(false, "application_service_unavailable", "应用目录服务未接入")
                    } else {
                        runCatching { apps.handle(verb.action, verb.query) }
                            .getOrElse { ServiceResult(false, "service_error", it.message ?: "应用目录调用失败") }
                    }
                    backend.onResult(verb, result.ok, result.code, result.message)
                    recorder.recordStep(
                        runId,
                        StepRecord(
                            seq = steps,
                            kind = "application",
                            action = verb.action,
                            ok = result.ok,
                            code = result.code,
                            message = result.message.take(200),
                            startedAt = started,
                            durationMs = System.currentTimeMillis() - started,
                        ),
                    )
                    events.record(
                        "service.application",
                        """{"action":"${jsonEscape(verb.action)}",""" +
                            """"query":"${jsonEscape(verb.query.orEmpty())}",""" +
                            """"ok":${result.ok},"chars":${result.message.length}}""",
                        emptyList(),
                    )
                    emit(
                        AgentEvent.Noted(
                            if (result.ok) {
                                "应用目录：" + result.message.lineSequence().firstOrNull().orEmpty().take(80)
                            } else {
                                "应用目录失败：" + result.message.take(80)
                            },
                        ),
                    )
                }

                is Verb.Voice -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val started = System.currentTimeMillis()
                    val result = if (voice == null) {
                        ServiceResult(false, "voice_service_unavailable", "语音服务未接入")
                    } else {
                        runCatching { voice.handle(verb.action, verb.text) }
                            .getOrElse { ServiceResult(false, "service_error", it.message ?: "语音调用失败") }
                    }
                    backend.onResult(verb, result.ok, result.code, result.message)
                    recorder.recordStep(
                        runId,
                        StepRecord(
                            seq = steps,
                            kind = "voice",
                            action = verb.action,
                            ok = result.ok,
                            code = result.code,
                            message = result.message.take(200),
                            startedAt = started,
                            durationMs = System.currentTimeMillis() - started,
                        ),
                    )
                    events.record(
                        "service.voice",
                        """{"action":"${jsonEscape(verb.action)}",""" +
                            """"chars":${verb.text?.length ?: 0},"ok":${result.ok}}""",
                        emptyList(),
                    )
                    emit(
                        AgentEvent.Noted(
                            if (result.ok) "语音：${result.message.take(80)}"
                            else "语音失败：${result.message.take(80)}",
                        ),
                    )
                }

                is Verb.Read -> {
                    chargeOrReason(Charge.ACTION)?.let { reason ->
                        failOnBudget(this, runId, reason)
                        return@flow
                    }
                    val page = handles?.read(verb.handle, verb.cursor, verb.limit)
                    val content = page?.content.orEmpty()
                    backend.onResult(
                        verb,
                        ok = page != null,
                        code = if (page == null) "handle_not_found" else null,
                        message = content,
                    )
                    events.record(
                        "read",
                        """{"handle":"${jsonEscape(verb.handle)}","cursor":"${jsonEscape(verb.cursor.orEmpty())}","chars":${content.length}}""",
                        emptyList(),
                    )
                    emit(AgentEvent.Noted("读取句柄 ${verb.handle}：${content.length} 字"))
                }

                Verb.Reflect -> emit(AgentEvent.Noted("反思"))
            }
        }

        recorder.finishRun(runId, "failed", "超过最大步数 $maxSteps")
        emit(AgentEvent.Failed("超过最大步数 $maxSteps"))
    }


    /** 每次动作后评估僵局；返回非空表示本次运行已被终止。 */
    private suspend fun checkImpasse(
        collector: FlowCollector<AgentEvent>,
        runId: Long,
        step: Int,
        verb: Verb.Act,
        result: ActionResult,
        before: Observation,
    ): Unit? {
        val after = (result as? ActionResult.Ok)?.observation ?: before
        val digest = "${after.packageName}|${after.elements.size}|" +
            after.elements.take(3).joinToString(",") { it.label.take(10) }
        val detected = impasse.evaluate(
            signature = ActionSignature.of(verb),
            screenDigest = digest,
            ok = result is ActionResult.Ok,
            foregroundPackage = after.packageName,
        ) ?: return null

        runCatching { frames.markImpasse(runId, detected.code) }
        events.record(
            "impasse",
            """{"state":"${detected.code}","step":$step,"repeats":${impasse.repeatCount()}}""",
            emptyList(),
        )
        if (detected == Impasse.STALLED || detected == Impasse.DRIFT) {
            val reason = "僵局：${detected.code}"
            recorder.finishRun(runId, "blocked", reason)
            collector.emit(AgentEvent.Failed("$reason（重复动作或目标漂移，已中止）"))
            return Unit
        }
        return null
    }
    private enum class Charge { STEP, ACTION, TOKENS, INTERRUPT, MS, MONEY }

    /** 扣费；被拒返回原因，放行返回 null（A-3 的唯一计费点）。 */
    private fun chargeOrReason(charge: Charge, amount: Long = 1): String? {
        val scheduler = budget ?: return null
        val admission = when (charge) {
            Charge.STEP, Charge.ACTION -> scheduler.charge(BudgetDimension.CALLS, 1)
            Charge.TOKENS -> scheduler.charge(BudgetDimension.TOKENS, amount)
            Charge.INTERRUPT -> scheduler.charge(BudgetDimension.INTERRUPTS, 1)
            Charge.MS -> scheduler.charge(BudgetDimension.MS, amount)
            Charge.MONEY -> scheduler.charge(BudgetDimension.MONEY, amount)
        }
        return if (admission is Admission.Deny) {
            "预算耗尽（${admission.dimension}）：${admission.detail}"
        } else {
            null
        }
    }

    private suspend fun failOnBudget(
        collector: FlowCollector<AgentEvent>,
        runId: Long,
        reason: String,
    ) {
        events.record(
            "budget.exhausted",
            """{"detail":"${jsonEscape(reason)}"}""",
            emptyList(),
        )
        recorder.finishRun(runId, "failed", reason)
        collector.emit(AgentEvent.Failed(reason))
    }

    private fun verbName(verb: Verb): String = when (verb) {
        is Verb.Act -> "act"
        is Verb.Ask -> "ask"
        is Verb.Note -> "note"
        is Verb.Plan -> "plan"
        is Verb.SearchMessages -> "search_messages"
        is Verb.ReactMessage -> "react_message"
        is Verb.QuoteMessage -> "quote_message"
        is Verb.Application -> "application"
        is Verb.Voice -> "voice"
        is Verb.Read -> "read"
        is Verb.Attend -> "attend"
        is Verb.Reflect -> "reflect"
        is Verb.Done -> "done"
    }

    private suspend fun recordObserve(runId: Long, seq: Int, observation: Observation) {
        recorder.recordStep(
            runId,
            StepRecord(
                seq = seq,
                kind = "observe",
                packageName = observation.packageName,
                message = "${observation.elements.size} elements",
            ),
        )
    }

    private suspend fun execute(verb: Verb.Act, observation: Observation): ActionResult {
        val obsId = observation.obsId
        return when (verb.action.lowercase()) {
            "tap" -> verb.nodeId?.let { gateway.tap(it, obsId) }
                ?: ActionResult.Failed("bad_verb", "tap 缺少 nodeId")

            "input", "settext", "type" -> {
                val nodeId = verb.nodeId
                    ?: observation.elements.firstOrNull { it.editable }?.id
                    ?: return ActionResult.Failed("bad_verb", "没有可输入的节点")
                gateway.setText(nodeId, obsId, verb.text.orEmpty())
            }

            "scroll" -> gateway.scroll(scrollDirection(verb.text), obsId)

            "launch", "open" -> verb.packageName?.let { gateway.launch(it) }
                ?: ActionResult.Failed("bad_verb", "launch 缺少 packageName")

            "back" -> gateway.back()

            else -> ActionResult.Failed("unknown_action", "不认识的 action：${verb.action}")
        }
    }

    private fun scrollDirection(raw: String?): Int = when (raw?.lowercase()) {
        "up" -> 1
        "left" -> 2
        "right" -> 3
        else -> 0
    }
}

sealed interface AgentEvent {
    data class Started(val task: String) : AgentEvent
    data class Observed(val observation: Observation) : AgentEvent
    data class Decided(val verb: Verb) : AgentEvent
    data class Acted(val verb: Verb, val result: ActionResult) : AgentEvent
    data class Noted(val text: String) : AgentEvent
    data class NeedsUser(val message: String) : AgentEvent
    data class Finished(val summary: String, val steps: Int) : AgentEvent
    data class Failed(val reason: String) : AgentEvent
}