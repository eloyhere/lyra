package com.eloykim.lyra.ui.splash

import android.app.Activity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.eloykim.lyra.R

private val SpaceBottom = Color(0xFF02030A)
private val LogoInk = Color(0xFFF3EEFF)

/**
 * 启动页：全屏螺旋星系 + 四周星场 + 底部品牌文案，约 1.1 秒。
 *
 * 星系按自然比例渲染，不放大；留白由星点和星云填充。图片见
 * drawable-nodpi/ic_splash_galaxy.webp，生成脚本见 .scratch/logo。
 */
@Composable
fun SplashScreen() {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // 启动页是深色背景，临时把系统栏图标切成浅色；离开启动页时恢复。
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
            previousNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
        }
    }

    val textAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 700, delayMillis = 120),
        label = "splash-text-alpha",
    )
    val textRise by animateFloatAsState(
        targetValue = if (entered) 1f else 0.94f,
        animationSpec = tween(durationMillis = 800, delayMillis = 120),
        label = "splash-text-scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceBottom),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_splash_galaxy),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // 底部压暗，保证文案在星场上也清楚
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.58f to Color.Transparent,
                        0.80f to Color(0xB802030A),
                        1f to SpaceBottom,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp)
                .alpha(textAlpha)
                .scale(textRise),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "LYRA",
                style = MaterialTheme.typography.headlineLarge.copy(
                    letterSpacing = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = LogoInk,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "说一句话，我来办",
                style = MaterialTheme.typography.bodyMedium,
                color = LogoInk.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
            )
        }
    }
}
