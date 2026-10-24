package com.eloykim.lyra.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 目标应用自己的 launcher icon。
 *
 * 从 PackageManager 取图标，按需在 IO 线程解码；取不到（应用已卸载 / 无图标）
 * 时退回「首字母 + 圆角块」，保证列表不出现空洞。
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) { loadIcon(context, packageName) }
    }
    val shape = RoundedCornerShape(size * 0.3f)
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun loadIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    context.packageManager
        .getApplicationIcon(packageName)
        .toBitmap(width = 144, height = 144)
        .asImageBitmap()
}.getOrNull()
