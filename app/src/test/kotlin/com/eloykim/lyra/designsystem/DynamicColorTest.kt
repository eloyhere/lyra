package com.eloykim.lyra.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 动态取色算法的回归测试。
 *
 * 重点不是「颜色长得像不像」，而是算法承诺的几条规矩：
 * - tone 决定对比度（换壁纸不会把可读性换没）；
 * - 壁纸只决定色相，彩色壁纸的色相要留在主色上；
 * - 灰阶壁纸退化成单色主题，但 error 仍是红色；
 * - 亮色 / 暗色两套都完整，且不会相同。
 */
class DynamicColorTest {

    private val seeds = listOf(
        0xFF4A7BD0.toInt(), // 蓝
        0xFF4C8C4A.toInt(), // 绿
        0xFFC96A2B.toInt(), // 橙
        0xFF8E5BA6.toInt(), // 紫
        0xFF4A44A8.toInt(), // Lyra 默认种子
        0xFFF5D90A.toInt(), // 高彩度黄
        0xFF7A7A7A.toInt(), // 灰
    )

    @Test
    fun `contrast stays above accessibility floors for every seed`() {
        seeds.forEach { seed ->
            listOf(false, true).forEach { dark ->
                val scheme = lyraDynamicColorScheme(seed, dark)
                assertContrast(scheme, seed, dark)
            }
        }
    }

    private fun assertContrast(scheme: ColorScheme, seed: Int, dark: Boolean) {
        val label = "seed=0x${seed.toUInt().toString(16)} dark=$dark"
        assertAtLeast(4.5, scheme.primary, scheme.onPrimary, "primary/onPrimary $label")
        assertAtLeast(4.5, scheme.primaryContainer, scheme.onPrimaryContainer, "primaryContainer $label")
        assertAtLeast(4.5, scheme.secondaryContainer, scheme.onSecondaryContainer, "secondaryContainer $label")
        assertAtLeast(4.5, scheme.tertiaryContainer, scheme.onTertiaryContainer, "tertiaryContainer $label")
        assertAtLeast(7.0, scheme.surface, scheme.onSurface, "surface/onSurface $label")
        assertAtLeast(4.5, scheme.surfaceVariant, scheme.onSurfaceVariant, "surfaceVariant $label")
        assertAtLeast(4.5, scheme.error, scheme.onError, "error/onError $label")
        assertAtLeast(3.0, scheme.surface, scheme.outline, "outline $label")
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color, label: String) {
        val ratio = contrast(fg, bg)
        assertTrue("$label contrast $ratio < $min", ratio >= min - 0.01)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return ((hi + 0.05) / (lo + 0.05)).toDouble()
    }

    @Test
    fun `light and dark schemes differ but stay complete`() {
        seeds.forEach { seed ->
            val light = lyraDynamicColorScheme(seed, darkTheme = false)
            val dark = lyraDynamicColorScheme(seed, darkTheme = true)
            assertNotEquals("surface must differ", light.surface, dark.surface)
            assertNotEquals("primary must differ", light.primary, dark.primary)
            assertTrue("dark surface should be dark", dark.surface.luminance() < 0.2f)
            assertTrue("light surface should be light", light.surface.luminance() > 0.8f)
        }
    }

    @Test
    fun `colorful wallpaper keeps its hue on primary`() {
        val colorful = listOf(0xFF4A7BD0.toInt(), 0xFF4C8C4A.toInt(), 0xFFC96A2B.toInt(), 0xFF8E5BA6.toInt())
        colorful.forEach { seed ->
            val seedLab = LabColor.fromArgb(seed)
            val scheme = lyraDynamicColorScheme(seed, darkTheme = false)
            val primaryLab = LabColor.fromArgb(scheme.primary.toTestArgb())

            // 主色仍应落在壁纸色相附近；gamut 映射可能带一点点偏移
            val delta = hueDistance(seedLab.hue, primaryLab.hue)
            assertTrue("hue drifted $delta° for seed", delta <= 6.0)
            assertTrue("primary should stay colorful", primaryLab.chroma > 25.0)

            // 三级色按 60° 偏移，继续给界面提供对位色
            val tertiaryLab = LabColor.fromArgb(scheme.tertiary.toTestArgb())
            val tertiaryDelta = hueDistance((seedLab.hue + 60.0) % 360.0, tertiaryLab.hue)
            assertTrue("tertiary hue drifted $tertiaryDelta°", tertiaryDelta <= 10.0)
        }
    }

    @Test
    fun `gray wallpaper collapses to monochrome but keeps red error`() {
        val scheme = lyraDynamicColorScheme(0xFF7A7A7A.toInt(), darkTheme = false)
        assertTrue(LabColor.fromArgb(scheme.primary.toTestArgb()).chroma < 2.0)
        assertTrue(LabColor.fromArgb(scheme.secondary.toTestArgb()).chroma < 2.0)
        assertTrue(LabColor.fromArgb(scheme.tertiary.toTestArgb()).chroma < 2.0)
        assertTrue(LabColor.fromArgb(scheme.surface.toTestArgb()).chroma < 2.0)

        val error = LabColor.fromArgb(scheme.error.toTestArgb())
        assertTrue("error should stay red", error.chroma > 40.0)
        assertTrue("error hue should be red-ish", error.hue in 10.0..55.0)
    }

    @Test
    fun `scheme generation is deterministic`() {
        val a = lyraDynamicColorScheme(0xFF4A7BD0.toInt(), darkTheme = true)
        val b = lyraDynamicColorScheme(0xFF4A7BD0.toInt(), darkTheme = true)
        // ColorScheme 没有结构化 equals，比几个关键角色即可
        assertEquals(a.primary, b.primary)
        assertEquals(a.primaryContainer, b.primaryContainer)
        assertEquals(a.surface, b.surface)
        assertEquals(a.onSurface, b.onSurface)
        assertEquals(a.error, b.error)
    }

    @Test
    fun `lab roundtrip keeps srgb colors`() {
        val argbValues = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF4A7BD0.toInt(), 0xFF7D5700.toInt())
        argbValues.forEach { argb ->
            val roundtrip = LabColor.fromArgb(argb).toArgbOrNull()
            assertEquals("roundtrip failed for 0x${argb.toUInt().toString(16)}", argb, roundtrip)
        }
    }

    @Test
    fun `lab lstar matches known values`() {
        assertEquals(100.0, LabColor.fromArgb(0xFFFFFFFF.toInt()).l, 0.01)
        assertEquals(0.0, LabColor.fromArgb(0xFF000000.toInt()).l, 0.01)
        // 中灰 #808080 的 L* 约为 53.6
        assertEquals(53.6, LabColor.fromArgb(0xFF808080.toInt()).l, 0.5)
    }

    @Test
    fun `color scheme lookup falls back to default`() {
        assertEquals(LyraColorTheme.Default, LyraColorTheme.byId(null))
        assertEquals(LyraColorTheme.Default, LyraColorTheme.byId("no-such-theme"))
        assertEquals(LyraColorTheme.Ocean, LyraColorTheme.byId("ocean"))
        assertEquals(LyraColorTheme.entries.size, LyraColorTheme.entries.map { it.id }.toSet().size)
    }

    /** 不依赖 android.graphics 的 ARGB 打包，保证 JVM 单测能跑。 */
    private fun Color.toTestArgb(): Int {
        fun channel(value: Float) = (value * 255.0f).roundToInt().coerceIn(0, 255)
        return (channel(alpha) shl 24) or (channel(red) shl 16) or
            (channel(green) shl 8) or channel(blue)
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val raw = kotlin.math.abs(a - b) % 360.0
        return if (raw > 180.0) 360.0 - raw else raw
    }
}

