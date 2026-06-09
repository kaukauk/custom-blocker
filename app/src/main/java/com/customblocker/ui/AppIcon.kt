package com.customblocker.ui

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
 * Process-wide cache of decoded, downscaled app icons keyed by package. Decoding an (often adaptive)
 * launcher drawable and rasterising it is expensive, so without this a LazyColumn re-decodes every
 * icon each time a row recycles back into view — the cause of scroll jank. Cached entries render
 * synchronously, so a re-scrolled row shows its icon instantly with no flicker or re-decode.
 */
private object IconCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun get(packageName: String): ImageBitmap? = cache[packageName]

    fun load(context: Context, packageName: String): ImageBitmap? {
        cache[packageName]?.let { return it }
        val bmp = runCatching {
            context.packageManager.getApplicationIcon(packageName).toImageBitmap()
        }.getOrNull()
        if (bmp != null) cache[packageName] = bmp
        return bmp
    }
}

/**
 * Renders an installed app's launcher icon, served from [IconCache] (instant on cache hit) and
 * decoded off the main thread on a miss. Falls back to an empty box (keeping layout stable) when
 * the package has no icon or is no longer installed.
 */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(initialValue = IconCache.get(packageName), packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { IconCache.load(context, packageName) }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier)
    }
}

/** Rasterise a drawable to a bitmap capped at [maxPx] so large adaptive icons don't bloat memory. */
private fun Drawable.toImageBitmap(maxPx: Int = 144): ImageBitmap {
    (this as? BitmapDrawable)?.bitmap?.let { src ->
        val scale = maxPx.toFloat() / maxOf(src.width, src.height).coerceAtLeast(1)
        if (scale >= 1f) return src.asImageBitmap()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
    }
    val iw = if (intrinsicWidth > 0) intrinsicWidth else maxPx
    val ih = if (intrinsicHeight > 0) intrinsicHeight else maxPx
    val scale = (maxPx.toFloat() / maxOf(iw, ih)).coerceAtMost(1f)
    val w = (iw * scale).toInt().coerceAtLeast(1)
    val h = (ih * scale).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}
