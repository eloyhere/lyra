package com.eloykim.lyra.designsystem

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 动态取色。
 *
 * 不再直接采用系统 Material You 生成好的整套颜色，而是只取「壁纸主色」，
 * 再按 Lyra 自己的规则重新推导一遍：
 *
 * 1. 把 seed 从 sRGB 转到 CIELAB，得到色相 h 与彩度 C；
 * 2. 用 L*（也就是 M3 的 tone，0 黑 → 100 白）建 6 条色调阶梯：
 *    primary / secondary / tertiary / neutral / neutralVariant / error，
 *    色相分别是 h / h / h+60° / h / h / 32°；
 * 3. 彩度按角色缩放：primary 1.05×、secondary 0.34×、tertiary 0.55×，
 *    并夹在「够鲜艳但不刺眼」的区间；灰度壁纸（C < 4）退化成单色主题，
 *    只保留红色 error；
 * 4. 每个 tone 在 sRGB 色域内二分收缩彩度，保证 L* 与色相不被破坏，
 *    因此对比度仍然由 tone 决定（tone 差 50 ≈ 对比度 4.5）；
 * 5. 最后把 tone 映射到标准的 colorPrimary / colorOnPrimary /
 *    colorSurfaceContainer… 角色上，和 [LyraColorTheme] 走同一套映射。
 *
 * 这样动态配色和内置主题长得像一家人，而不是系统给什么就用什么；
 * 壁纸只贡献「色相」，明度、饱和度与角色关系由 Lyra 决定。
 */

/** 低于这个彩度就当成灰阶壁纸，生成单色主题。 */
private const val GRAY_WALLPAPER_CHROMA = 4.0

/** primary / secondary / tertiary 的彩度系数。 */
private const val PRIMARY_CHROMA_FACTOR = 1.05
private const val SECONDARY_CHROMA_FACTOR = 0.34
private const val TERTIARY_CHROMA_FACTOR = 0.55

/** error 不跟壁纸走，保持红色语义。 */
private const val ERROR_HUE = 32.0
private const val ERROR_CHROMA = 80.0

/** 中性色的彩度：表面带一点壁纸色相，但不足以干扰阅读。 */
private const val NEUTRAL_CHROMA = 6.0
private const val NEUTRAL_VARIANT_CHROMA = 10.0

/**
 * 返回动态取色是否可用。只有 API 31+ 才暴露壁纸调色板资源；
 * 更低版本没有公开的取色入口，配色方案里会直接隐藏「动态取色」。
 */
fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 读取系统壁纸主色并生成动态配色。
 *
 * 系统不提供壁纸调色板（Android 12 以下、资源缺失或读取失败）时返回 null，
 * 调用方应回退到用户选中的固定主题。
 */
fun systemDynamicColorScheme(context: Context, darkTheme: Boolean): ColorScheme? {
    if (!isDynamicColorAvailable()) return null
    val seed = runCatching {
        context.getColor(android.R.color.system_accent1_500)
    }.getOrNull() ?: return null
    return lyraDynamicColorScheme(seed, darkTheme)
}

/**
 * 纯函数版本的动态配色：给定一个 seed（ARGB）与明暗模式，生成整套 [ColorScheme]。
 *
 * 抽成纯函数是为了能在 JVM 单测里直接验证色调映射与对比度，不依赖 Android Context。
 */
fun lyraDynamicColorScheme(seedArgb: Int, darkTheme: Boolean): ColorScheme {
    val seed = LabColor.fromArgb(seedArgb)
    val gray = seed.chroma < GRAY_WALLPAPER_CHROMA
    val hue = seed.hue

    val primaryChroma = if (gray) 0.0 else (seed.chroma * PRIMARY_CHROMA_FACTOR).coerceIn(32.0, 56.0)
    val secondaryChroma = if (gray) 0.0 else (seed.chroma * SECONDARY_CHROMA_FACTOR).coerceIn(8.0, 16.0)
    val tertiaryChroma = if (gray) 0.0 else (seed.chroma * TERTIARY_CHROMA_FACTOR).coerceIn(14.0, 28.0)

    val primary = TonalRamp(hue, primaryChroma)
    val secondary = TonalRamp(hue, secondaryChroma)
    val tertiary = TonalRamp((hue + 60.0) % 360.0, tertiaryChroma)
    val neutral = TonalRamp(hue, if (gray) 0.0 else NEUTRAL_CHROMA)
    val neutralVariant = TonalRamp(hue, if (gray) 0.0 else NEUTRAL_VARIANT_CHROMA)
    val error = TonalRamp(ERROR_HUE, ERROR_CHROMA)

    return buildScheme(
        darkTheme = darkTheme,
        primary = primary,
        secondary = secondary,
        tertiary = tertiary,
        neutral = neutral,
        neutralVariant = neutralVariant,
        error = error,
    )
}

/** 一条 tone → 颜色 的阶梯，同一条阶梯里 tone 只会算一次。 */
private class TonalRamp(private val hue: Double, private val chroma: Double) {
    private val cache = HashMap<Int, Color>()

    fun tone(tone: Int): Color = cache.getOrPut(tone) {
        Color(toneArgb(tone.toDouble(), hue, chroma).toLong() and 0xFFFFFFFFL)
    }
}

/**
 * 在 sRGB 色域内生成指定 tone / 色相 / 彩度的颜色。
 *
 * tone 用 L*，色相在 AB 平面里保持不变，彩度超出色域就二分收缩：
 * 这样深色/浅色两套的明度关系是锁死的，换壁纸也不会把对比度换没。
 */
private fun toneArgb(tone: Double, hue: Double, chroma: Double): Int {
    if (tone <= 0.0) return 0xFF000000.toInt()
    if (tone >= 100.0) return 0xFFFFFFFF.toInt()

    val radians = Math.toRadians(hue)
    val fitted = min(chroma, maxChromaInGamut(tone, radians))
    val lab = LabColor(
        l = tone,
        a = fitted * cos(radians),
        b = fitted * sin(radians),
    )
    return lab.toArgbOrNull() ?: 0xFF808080.toInt()
}

/** 二分找到当前 tone / 色相下 sRGB 能容纳的最大彩度。 */
private fun maxChromaInGamut(tone: Double, radians: Double): Double {
    var low = 0.0
    var high = 180.0
    repeat(18) {
        val mid = (low + high) / 2.0
        val fits = LabColor(tone, mid * cos(radians), mid * sin(radians)).toArgbOrNull() != null
        if (fits) low = mid else high = mid
    }
    return low
}

/**
 * CIELAB（D65）与 sRGB 的互转。
 *
 * 只保留动态取色需要的部分：sRGB → Lab、Lab → sRGB（带色域判断），
 * 不引入额外依赖，也方便单测。
 */
internal data class LabColor(val l: Double, val a: Double, val b: Double) {

    val chroma: Double get() = hypot(a, b)

    val hue: Double get() = (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0

    /** 超出 sRGB 色域时返回 null。 */
    fun toArgbOrNull(): Int? {
        val fy = (l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0

        val x = WHITE_X * labFInverse(fx)
        val y = WHITE_Y * labFInverse(fy)
        val z = WHITE_Z * labFInverse(fz)

        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z

        if (r < -GAMUT_EPSILON || r > 1.0 + GAMUT_EPSILON ||
            g < -GAMUT_EPSILON || g > 1.0 + GAMUT_EPSILON ||
            bl < -GAMUT_EPSILON || bl > 1.0 + GAMUT_EPSILON
        ) {
            return null
        }

        val red = (delinearize(r) * 255.0).roundToInt().coerceIn(0, 255)
        val green = (delinearize(g) * 255.0).roundToInt().coerceIn(0, 255)
        val blue = (delinearize(bl) * 255.0).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    companion object {
        fun fromArgb(argb: Int): LabColor {
            val r = linearize((argb shr 16) and 0xFF)
            val g = linearize((argb shr 8) and 0xFF)
            val b = linearize(argb and 0xFF)

            val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X
            val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / WHITE_Y
            val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z

            val fx = labF(x)
            val fy = labF(y)
            val fz = labF(z)
            return LabColor(
                l = 116.0 * fy - 16.0,
                a = 500.0 * (fx - fy),
                b = 200.0 * (fy - fz),
            )
        }
    }
}

private const val WHITE_X = 0.95047
private const val WHITE_Y = 1.0
private const val WHITE_Z = 1.08883
private const val DELTA = 6.0 / 29.0

/** 浮点往返容差：允许 0.0001 的色域越界，约等于 RGB 的 0.03/255。 */
private const val GAMUT_EPSILON = 1e-4

private fun labF(value: Double): Double =
    if (value > DELTA * DELTA * DELTA) cbrt(value) else value / (3.0 * DELTA * DELTA) + 4.0 / 29.0

private fun labFInverse(value: Double): Double =
    if (value > DELTA) value * value * value else 3.0 * DELTA * DELTA * (value - 4.0 / 29.0)

private fun linearize(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun delinearize(channel: Double): Double =
    if (channel <= 0.0031308) channel * 12.92 else 1.055 * channel.pow(1.0 / 2.4) - 0.055

/**
 * 把六条色调阶梯映射到标准 M3 角色。
 *
 * tone 取值与 [LyraColorTheme] 一致：浅色 40/90/10、深色 80/30/90，
 * surface 浅色 98、深色 6，容器色阶 4/10/12/17/22 等。
 */
private fun buildScheme(
    darkTheme: Boolean,
    primary: TonalRamp,
    secondary: TonalRamp,
    tertiary: TonalRamp,
    neutral: TonalRamp,
    neutralVariant: TonalRamp,
    error: TonalRamp,
): ColorScheme = if (darkTheme) {
    darkColorScheme(
        primary = primary.tone(80),
        onPrimary = primary.tone(20),
        primaryContainer = primary.tone(30),
        onPrimaryContainer = primary.tone(90),
        inversePrimary = primary.tone(40),
        secondary = secondary.tone(80),
        onSecondary = secondary.tone(20),
        secondaryContainer = secondary.tone(30),
        onSecondaryContainer = secondary.tone(90),
        tertiary = tertiary.tone(80),
        onTertiary = tertiary.tone(20),
        tertiaryContainer = tertiary.tone(30),
        onTertiaryContainer = tertiary.tone(90),
        background = neutral.tone(6),
        onBackground = neutral.tone(90),
        surface = neutral.tone(6),
        onSurface = neutral.tone(90),
        surfaceVariant = neutralVariant.tone(30),
        onSurfaceVariant = neutralVariant.tone(80),
        inverseSurface = neutral.tone(90),
        inverseOnSurface = neutral.tone(20),
        error = error.tone(80),
        onError = error.tone(20),
        errorContainer = error.tone(30),
        onErrorContainer = error.tone(90),
        outline = neutralVariant.tone(60),
        outlineVariant = neutralVariant.tone(30),
        surfaceBright = neutral.tone(24),
        surfaceContainer = neutral.tone(12),
        surfaceContainerHigh = neutral.tone(17),
        surfaceContainerHighest = neutral.tone(22),
        surfaceContainerLow = neutral.tone(10),
        surfaceContainerLowest = neutral.tone(4),
        surfaceDim = neutral.tone(6),
        primaryFixed = primary.tone(90),
        primaryFixedDim = primary.tone(80),
        onPrimaryFixed = primary.tone(10),
        onPrimaryFixedVariant = primary.tone(30),
        secondaryFixed = secondary.tone(90),
        secondaryFixedDim = secondary.tone(80),
        onSecondaryFixed = secondary.tone(10),
        onSecondaryFixedVariant = secondary.tone(30),
        tertiaryFixed = tertiary.tone(90),
        tertiaryFixedDim = tertiary.tone(80),
        onTertiaryFixed = tertiary.tone(10),
        onTertiaryFixedVariant = tertiary.tone(30),
    )
} else {
    lightColorScheme(
        primary = primary.tone(40),
        onPrimary = primary.tone(100),
        primaryContainer = primary.tone(90),
        onPrimaryContainer = primary.tone(10),
        inversePrimary = primary.tone(80),
        secondary = secondary.tone(40),
        onSecondary = secondary.tone(100),
        secondaryContainer = secondary.tone(90),
        onSecondaryContainer = secondary.tone(10),
        tertiary = tertiary.tone(40),
        onTertiary = tertiary.tone(100),
        tertiaryContainer = tertiary.tone(90),
        onTertiaryContainer = tertiary.tone(10),
        background = neutral.tone(98),
        onBackground = neutral.tone(10),
        surface = neutral.tone(98),
        onSurface = neutral.tone(10),
        surfaceVariant = neutralVariant.tone(90),
        onSurfaceVariant = neutralVariant.tone(30),
        inverseSurface = neutral.tone(20),
        inverseOnSurface = neutral.tone(95),
        error = error.tone(40),
        onError = error.tone(100),
        errorContainer = error.tone(90),
        onErrorContainer = error.tone(10),
        outline = neutralVariant.tone(50),
        outlineVariant = neutralVariant.tone(80),
        surfaceBright = neutral.tone(98),
        surfaceContainer = neutral.tone(94),
        surfaceContainerHigh = neutral.tone(92),
        surfaceContainerHighest = neutral.tone(90),
        surfaceContainerLow = neutral.tone(96),
        surfaceContainerLowest = neutral.tone(100),
        surfaceDim = neutral.tone(87),
        primaryFixed = primary.tone(90),
        primaryFixedDim = primary.tone(80),
        onPrimaryFixed = primary.tone(10),
        onPrimaryFixedVariant = primary.tone(30),
        secondaryFixed = secondary.tone(90),
        secondaryFixedDim = secondary.tone(80),
        onSecondaryFixed = secondary.tone(10),
        onSecondaryFixedVariant = secondary.tone(30),
        tertiaryFixed = tertiary.tone(90),
        tertiaryFixedDim = tertiary.tone(80),
        onTertiaryFixed = tertiary.tone(10),
        onTertiaryFixedVariant = tertiary.tone(30),
    )
}
