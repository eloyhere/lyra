package com.eloykim.lyra.amp.protocol.bayes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ECE / Brier / isotonic / 温度 / 交叉验证 / 方法选择 / 注册表激活门槛。
 */
class CalibrationTest {

    private val e9 = 1e-9
    private val e12 = 1e-12

    private fun overconfident(): List<CalibrationPoint> =
        (0 until 10).map { index -> CalibrationPoint(0.9, index < 5) }

    @Test
    fun `完美预测的 ECE 与 Brier 都是零`() {
        val perfect = listOf(CalibrationPoint(0.0, false), CalibrationPoint(1.0, true))
        assertEquals(0.0, ece(perfect), e12)
        assertEquals(0.0, brier(perfect), e12)
        assertEquals(0.0, ece(emptyList()), e12)
        assertEquals(0.0, brier(emptyList()), e12)
    }

    @Test
    fun `过度自信的十个点给出参考实现的 ECE 与 Brier`() {
        // 数值与参考 TS 实现（Node 24 直跑 src/bayes/*.ts）逐位一致。
        assertEquals(0.40000000000000013, ece(overconfident()), 1e-15)
        assertEquals(0.41, brier(overconfident()), 1e-15)
    }

    @Test
    fun `分桶用夹取后的预测值，桶内累计原始预测值`() {
        // 单点 0.1 落在第 1 桶：|accuracy - confidence| = |1 - 0.1|
        assertEquals(0.9, ece(listOf(CalibrationPoint(0.1, true))), e12)
        // 五个桶时 0.9 落在第 4 桶
        assertEquals(0.40000000000000013, ece(overconfident(), 5), 1e-15)
    }

    @Test
    fun `isotonic 单调、可应用，并把逆序块合并`() {
        val model = fitIsotonic(
            listOf(
                CalibrationPoint(0.1, false),
                CalibrationPoint(0.2, false),
                CalibrationPoint(0.8, true),
                CalibrationPoint(0.9, true),
            ),
        )
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0), model.values)
        assertEquals(0.0, applyIsotonic(model, 0.05), e12)
        assertEquals(1.0, applyIsotonic(model, 0.95), e12)
        assertTrue(model.thresholds.zipWithNext().all { (left, right) -> left <= right })

        // (0.1, true) 与 (0.2, false) 逆序，合并成一个块。
        val merged = fitIsotonic(listOf(CalibrationPoint(0.1, true), CalibrationPoint(0.2, false)))
        assertEquals(listOf(0.2), merged.thresholds)
        assertEquals(listOf(0.5), merged.values)
        // 空模型退化为夹取。
        assertEquals(0.3, applyIsotonic(IsotonicModel(emptyList(), emptyList()), 0.3), e12)
    }

    @Test
    fun `温度拟合与应用的边界`() {
        assertEquals(1.0, fitTemperature(emptyList()).temperature, e12)
        // 全部 0.9 预测、一半为真：NLL 在网格上随温度增大而下降，取上界 5。
        assertEquals(5.0, fitTemperature(overconfident()).temperature, e12)
        // T=1 时 sigmoid(logit(p)) 还原 p。
        assertEquals(0.3, applyTemperature(TemperatureModel(1.0), 0.3), e9)
        assertTrue(applyTemperature(TemperatureModel(2.0), 0.9) < 0.9)
        // 全体 0.9、一半为真时 isotonic 收敛到单块 0.5（与参考实现一致）。
        assertEquals(0.5, applyIsotonic(fitIsotonic(overconfident()), 0.9), 1e-15)
    }

    @Test
    fun `交叉验证报告是有限的，raw 指标用全体点`() {
        val points = (0 until 40).map { index -> CalibrationPoint((index % 10) / 10.0, index % 2 == 0) }
        val report = crossValidateCalibration(points, 5)
        assertTrue(report.ece.isFinite())
        assertTrue(report.brier.isFinite())
        assertTrue(report.rawEce >= 0.0)
        assertEquals(ece(points), report.rawEce, e12)
        assertEquals(brier(points), report.rawBrier, e12)
        assertEquals(CalibrationReport(0.0, 0.0, 0.0, 0.0), crossValidateCalibration(emptyList()))
    }

    @Test
    fun `折外预测每个点恰好一条`() {
        val points = (0 until 10).map { index -> CalibrationPoint(index / 10.0, index % 2 == 0) }
        assertEquals(10, crossValidatedPredictions(points, CalibrationMethod.RAW, 5).size)
        assertEquals(10, crossValidatedPredictions(points, CalibrationMethod.ISOTONIC, 5).size)
    }

    @Test
    fun `方法选择偏好交叉验证 ECE 最低者，并列偏好 raw`() {
        val selected = selectCalibration(overconfident())
        assertEquals(CalibrationMethod.ISOTONIC, selected.method)
        assertEquals(0.0, selected.ece, e12)
        assertEquals(0.25, selected.brier, e12)
        assertEquals(0.40000000000000013, selected.rawEce, 1e-15)
        assertEquals(0.41, selected.rawBrier, e12)
        assertTrue(selected.ece < selected.rawEce)
        // 四个候选的方法字段都被保留。
        val observed = setOf(
            CalibrationMethod.RAW,
            CalibrationMethod.ISOTONIC,
            CalibrationMethod.SHRUNK,
            CalibrationMethod.TEMPERATURE,
        )
        assertTrue(observed.contains(selected.method))
    }

    @Test
    fun `配对 bootstrap 给出参考实现的 ECE 差估计`() {
        val interval = bootstrapEceDelta(overconfident(), CalibrationMethod.ISOTONIC)
        // 三个数与参考 TS 实现（seed=42、500 次重采样）逐位一致。
        assertEquals(-0.2696000000000004, interval.mean, 1e-15)
        assertEquals(-0.4000000000000002, interval.lower, 1e-15)
        assertEquals(0.19999999999999996, interval.upper, 1e-15)
        assertTrue(interval.mean < 0.0)
        // n=10 时 95% 上界仍可能包含 0——这正是注册表默认要求 ciUpper < 0 的原因。
        assertTrue(interval.upper > interval.mean)
        // 空点集返回零区间。
        assertEquals(BootstrapInterval(0.0, 0.0, 0.0), bootstrapEceDelta(emptyList(), CalibrationMethod.SHRUNK))
    }

    @Test
    fun `注册表只在样本足够且交叉验证确实改善时激活`() {
        val random = Mulberry32(7)
        val points = (0 until 400).map { index ->
            val truth = 0.05 + (index / 399.0) * 0.9
            CalibrationPoint(
                predicted = Math.min(0.99, truth * 1.25 + 0.1),
                actual = random.next() < truth,
            )
        }

        val registry = CalibrationRegistry()
        val activated = registry.register(
            "scope.calibrated",
            points,
            RegisterOptions(at = "2026-10-24T00:00:00.000Z"),
        )
        assertEquals(RegisterStatus.ACTIVATED, activated.status)
        val model = registry.model("scope.calibrated")
        assertNotNull(model)
        assertTrue((model?.ciUpper ?: 0.0) < 0.0)
        assertTrue(registry.calibrate("scope.calibrated", 0.9) < 0.9)
        assertEquals("2026-10-24T00:00:00.000Z", model?.activatedAt)
        assertEquals(listOf("scope.calibrated"), registry.scopes())
        // 与参考 TS 实现逐位一致（同一 mulberry32 序列、同一激活门槛）。
        assertEquals(CalibrationMethod.ISOTONIC, model?.method)
        assertEquals(0.051247897995825485, model?.cvEce ?: Double.NaN, 1e-15)
        assertEquals(0.21038947368421004, model?.rawEce ?: Double.NaN, 1e-15)
        assertEquals(-0.19512246432781294, model?.ciLower ?: Double.NaN, 1e-15)
        assertEquals(-0.06748476931839364, model?.ciUpper ?: Double.NaN, 1e-15)
        assertEquals(400, model?.samples)
        assertEquals(0.631578947368421, registry.calibrate("scope.calibrated", 0.9), 1e-15)

        // 未注册的 scope 保持 raw。
        assertEquals(0.9, registry.calibrate("scope.unknown", 0.9), e12)

        val small = registry.register("scope.small", points.take(10))
        assertEquals(RegisterStatus.RAW, small.status)
        assertNull(registry.model("scope.small"))
        assertNull(small.model)
        assertEquals(0.9, registry.calibrate("scope.small", 0.9), e12)
        assertEquals(listOf("scope.calibrated"), registry.scopes())

        // 同一 scope 再次注册但未达标时删除旧模型。
        val revoked = registry.register("scope.calibrated", points.take(10))
        assertEquals(RegisterStatus.RAW, revoked.status)
        assertNull(registry.model("scope.calibrated"))
        assertTrue(registry.scopes().isEmpty())
    }

    @Test
    fun `注册表记录可 JSON 往返且省略 temperature`() {
        val model = CalibrationModel(
            scope = "s",
            method = CalibrationMethod.ISOTONIC,
            samples = 40,
            rawEce = 0.4,
            cvEce = 0.1,
            thresholds = listOf(0.2, 0.9),
            values = listOf(0.0, 1.0),
            ciLower = -0.5,
            ciUpper = -0.1,
            activatedAt = "2026-10-24T00:00:00Z",
        )
        val json = model.toJson()
        assertFalse(json.has("temperature"))
        assertEquals(model, CalibrationModel.fromJson(json))

        val temperatureModel = model.copy(method = CalibrationMethod.TEMPERATURE, temperature = 2.0)
        assertEquals(temperatureModel, CalibrationModel.fromJson(temperatureModel.toJson()))
    }

    /** 参考实现里的 mulberry32，逐位复刻用于生成同一批点。 */
    private class Mulberry32(seed: Int) {

        private var state: Int = seed

        fun next(): Double {
            state += 0x6d2b79f5
            var value = (state xor (state ushr 15)) * (1 or state)
            value = value xor (value + (value xor (value ushr 7)) * (61 or value))
            return ((value xor (value ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }
}
