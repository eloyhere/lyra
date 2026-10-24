package com.eloykim.lyra.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BaseTypography = Typography()

private val LyraTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
)

private val LyraShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

/**
 * 应用主题。
 *
 * @param colorTheme 内置固定配色；[dynamicColor] 为 true 时由壁纸主色推导的动态配色优先。
 * @param dynamicColor 动态取色（Android 12+）。默认关闭，让 Lyra 自己的配色成为默认外观。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    colorTheme: LyraColorTheme = LyraColorTheme.Default,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = remember(dynamicColor, darkTheme, colorTheme, context) {
        (if (dynamicColor) systemDynamicColorScheme(context, darkTheme) else null)
            ?: colorTheme.colorScheme(darkTheme)
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = LyraShapes,
        typography = LyraTypography,
        content = content,
    )
}
