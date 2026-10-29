package com.eloykim.lyra.amp.protocol.warrant

import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.json.jsonStrings
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import com.eloykim.lyra.amp.protocol.types.Ref
import com.eloykim.lyra.amp.protocol.types.RefKind
import com.eloykim.lyra.amp.protocol.types.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 探究账本：预注册、looks 记录、结论层级与复制判定。
 */
class InquiryTest {

    private var tick = 0L

    private fun now(): String =
        Instant.parse("2026-10-24T04:00:00Z").plusSeconds(tick++).toString()

    private fun newLog(): InquiryLog = InquiryLog { now() }

    @Test
    fun `注册要求预注册的假设`() {
        val log = newLog()
        assertThrows(IllegalArgumentException::class.java) {
            log.register(hypothesis(prereg = null))
        }
    }

    @Test
    fun `语句被篡改时预注册哈希不匹配`() {
        val log = newLog()
        val tampered = hypothesis().copy(statement = "the traffic spike caused the error rate")
        assertThrows(IllegalArgumentException::class.java) {
            log.register(tampered)
        }
    }

    @Test
    fun `looks 被记录并计数`() {
        val log = newLog()
        val experiment = log.register(hypothesis())
        log.observe(experiment.id, 0.061)
        log.observe(experiment.id, 0.052)
        assertEquals(2, log.experiment(experiment.id).looks.size)
        assertEquals(0.061, log.experiment(experiment.id).looks[0].value, 1e-9)
    }

    @Test
    fun `固定 n 设计之外的额外 look 产生警告`() {
        val log = newLog()
        val experiment = log.register(hypothesis())
        log.observe(experiment.id, 0.061)
        log.observe(experiment.id, 0.052)
        assertTrue(log.experiment(experiment.id).warnings.any { it.contains("fixed-n") })
    }

    @Test
    fun `没有一次观察就不能下结论`() {
        val log = newLog()
        val experiment = log.register(hypothesis())
        assertThrows(IllegalArgumentException::class.java) {
            log.conclude(experiment.id, ExperimentVerdict.SUPPORTED)
        }
    }

    @Test
    fun `完成的实验赢得 T3 而已注册的实验是 T2`() {
        val log = newLog()
        val experiment = log.register(hypothesis())
        assertEquals(Tier.T2, log.tierOf(experiment.id))
        log.observe(experiment.id, 0.061)
        log.conclude(experiment.id, ExperimentVerdict.SUPPORTED)
        assertEquals(Tier.T3, log.tierOf(experiment.id))
    }

    @Test
    fun `已完成的实验不能再观察`() {
        val log = newLog()
        val experiment = log.register(hypothesis())
        log.observe(experiment.id, 0.061)
        log.conclude(experiment.id, ExperimentVerdict.REFUTED)
        assertThrows(IllegalArgumentException::class.java) {
            log.observe(experiment.id, 0.02)
        }
    }

    @Test
    fun `未知实验 id 被拒绝`() {
        val log = newLog()
        assertThrows(IllegalArgumentException::class.java) {
            log.experiment("ex.404")
        }
    }

    @Test
    fun `复制要求不同的 run seed 与 origin`() {
        val original = RunContext(runId = "run.1", seed = "s1", origin = "prod")

        assertFalse(replicationCheck(original, original.copy()).ok)
        assertFalse(replicationCheck(original, original.copy(runId = "run.2")).ok)
        assertFalse(replicationCheck(original, original.copy(runId = "run.2", seed = "s2")).ok)

        val verdict = replicationCheck(original, RunContext("run.2", "s2", "staging"))
        assertTrue(verdict.ok)
        assertEquals(Tier.T4, verdict.tier)
    }

    @Test
    fun `相同环境只是警告不是致命失败`() {
        val verdict = replicationCheck(
            RunContext("run.1", "s1", "prod", environment = "cn-east"),
            RunContext("run.2", "s2", "prod-2", environment = "cn-east"),
        )
        assertTrue(verdict.ok)
        assertEquals(Tier.T4, verdict.tier)
        assertTrue(verdict.reasons.any { it.contains("environment") })
    }

    @Test
    fun `预注册哈希是 canonical 且稳定的`() {
        val expected = CanonicalJson.digest(
            jsonObj(
                "statement" to JsonValue.Str("s"),
                "metric" to JsonValue.Str("m"),
                "stopping" to JsonValue.Str("fixed_n"),
                "exclusions" to jsonStrings(listOf("x")),
            ),
        )
        assertEquals(expected, preregHashOf("s", "m", "fixed_n", listOf("x")))
        assertEquals(preregHashOf("s", "m", "fixed_n"), preregHashOf("s", "m", "fixed_n", emptyList()))
    }

    @Test
    fun `实验对象可往返`() {
        val log = newLog()
        val experiment = log.register(hypothesis())
        log.observe(experiment.id, 0.061)
        log.observe(experiment.id, 0.052)
        log.conclude(experiment.id, ExperimentVerdict.INCONCLUSIVE)

        val json = experiment.toJson()
        val restored = Experiment.fromJson(json)
        assertEquals(CanonicalJson.encode(json), CanonicalJson.encode(restored.toJson()))
        assertEquals(ExperimentStatus.COMPLETED, restored.status)
        assertEquals(ExperimentVerdict.INCONCLUSIVE, restored.verdict)
        assertEquals(2, restored.looks.size)
    }

    private fun hypothesis(
        statement: String = STATEMENT,
        prereg: Prereg? = preregFor(statement),
    ): Hypothesis = Hypothesis(
        id = "h.1",
        question = Ref(RefKind.UNKNOWN, "u.1"),
        statement = statement,
        prediction = "rollback restores baseline",
        refutesIf = "error rate stays 3x above baseline",
        status = HypothesisStatus.TESTING,
        created = "2026-10-24T03:00:00Z",
        prereg = prereg,
    )

    private fun preregFor(statement: String): Prereg = Prereg(
        hash = preregHashOf(statement, METRIC, STOPPING, emptyList()),
        registeredAt = "2026-10-24T03:00:00Z",
        metric = METRIC,
        stopping = STOPPING,
        exclusions = emptyList(),
        looks = 0,
    )

    private companion object {
        const val STATEMENT = "the deploy caused the error-rate spike"
        const val METRIC = "5xx_rate"
        const val STOPPING = "fixed_n_10m"
    }
}
