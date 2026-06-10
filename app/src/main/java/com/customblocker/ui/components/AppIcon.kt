package com.customblocker.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders an installed app's launcher icon. Decoded + downscaled once and cached process-wide, so
 * scrolling a list re-shows icons instantly (cache hit renders synchronously, no flicker). Falls
 * back to an empty box (stable layout) when the package has no icon or is gone.
 */
private object AppIconCache {
    private val cache = ConcurrentHashMap<String, Holder>()
    private class Holder(val bitmap: ImageBitmap?)

    fun get(pkg: String): ImageBitmap? = cache[pkg]?.bitmap
    fun has(pkg: String): Boolean = cache.containsKey(pkg)

    fun load(context: Context, pkg: String): ImageBitmap? {
        cache[pkg]?.let { return it.bitmap }
        val bmp = runCatching { context.packageManager.getApplicationIcon(pkg).toImageBitmap() }.getOrNull()
        cache[pkg] = Holder(bmp)
        return bmp
    }
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(initialValue = AppIconCache.get(packageName), packageName) {
        if (!AppIconCache.has(packageName)) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context, packageName) }
        }
    }
    val bmp = bitmap
    if (bmp != null) Image(bitmap = bmp, contentDescription = null, modifier = modifier) else Box(modifier)
}

private fun Drawable.toImageBitmap(maxPx: Int = 144): ImageBitmap {
    (this as? BitmapDrawable)?.bitmap?.let { src ->
        val scale = maxPx.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1)
        if (scale >= 1f) return src.asImageBitmap()
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true).asImageBitmap()
    }
    val iw = if (intrinsicWidth > 0) intrinsicWidth else maxPx
    val ih = if (intrinsicHeight > 0) intrinsicHeight else maxPx
    val scale = (maxPx.toFloat() / maxOf(iw, ih)).coerceAtMost(1f)
    val bmp = Bitmap.createBitmap((iw * scale).toInt().coerceAtLeast(1), (ih * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}
