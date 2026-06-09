package com.customblocker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(val packageName: String, val label: String)

/** Enumerates launchable installed apps and resolves labels + icons via PackageManager. */
@Singleton
class AppLister @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val labelCache = ConcurrentHashMap<String, String>()
    // Value is wrapped so a resolved-to-null (no icon) result is still cached and not re-decoded.
    private val iconCache = ConcurrentHashMap<String, Holder>()
    private class Holder(val bitmap: ImageBitmap?)

    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun label(packageName: String): String = labelCache.getOrPut(packageName) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * Decoded, downscaled launcher icon for [packageName], or null if it has none. Cached so repeat
     * calls are free. The first decode is heavy (adaptive-icon rasterisation) — call off the main
     * thread (callers resolve icons inside a background flow, never during scroll).
     */
    fun iconBitmap(packageName: String): ImageBitmap? =
        iconCache.getOrPut(packageName) {
            Holder(
                runCatching {
                    context.packageManager.getApplicationIcon(packageName).toImageBitmap()
                }.getOrNull(),
            )
        }.bitmap
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
