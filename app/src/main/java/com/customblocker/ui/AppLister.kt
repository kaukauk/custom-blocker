package com.customblocker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
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

    /**
     * Every installed app (not just the launchable ones), so a name search can surface apps with no
     * launcher icon — services, system apps, etc. — that [launchableApps] leaves out. Sorted by label,
     * self excluded.
     */
    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** Whether [packageName] is currently installed (so the UI can group uninstalled-but-targeted apps). */
    fun isInstalled(packageName: String): Boolean =
        try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }

    fun label(packageName: String): String {
        labelCache[packageName]?.let { return it }
        return try {
            val pm = context.packageManager
            val resolved = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            labelCache[packageName] = resolved   // cache only real labels
            resolved
        } catch (e: Exception) {
            // Don't cache the package-name fallback: if the app is later reinstalled, the next call
            // should resolve its real label (so it moves out of the "uninstalled" section cleanly).
            packageName
        }
    }

    /**
     * Decoded, downscaled launcher icon for [packageName], or null if it has none. Cached so repeat
     * calls are free. The first decode is heavy (adaptive-icon rasterisation) — call off the main
     * thread (callers resolve icons inside a background flow, never during scroll).
     */
    fun iconBitmap(packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { return it.bitmap }
        val bmp = runCatching {
            context.packageManager.getApplicationIcon(packageName).toImageBitmap()
        }.getOrNull()
        // Cache only a real icon; a null almost always means the app isn't installed, so don't cache
        // it — a later reinstall should resolve the icon instead of staying blank.
        if (bmp != null) iconCache[packageName] = Holder(bmp)
        return bmp
    }

    /** Encode [packageName]'s (downscaled) icon to PNG bytes for persisting, or null if it has none. */
    fun iconPng(packageName: String): ByteArray? {
        val bmp = iconBitmap(packageName) ?: return null
        return runCatching {
            ByteArrayOutputStream().use { out ->
                bmp.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    /** Decode previously-persisted PNG icon bytes back to an [ImageBitmap]. */
    fun decodeIcon(bytes: ByteArray?): ImageBitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
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
