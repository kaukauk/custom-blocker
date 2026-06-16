package com.customblocker.ui.notifications

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.customblocker.domain.engine.DayFlags
import com.customblocker.domain.engine.NotifActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App launcher icon for [packageName], decoded off the main thread via [loadIcon] (cached upstream). */
@Composable
fun AppIcon(
    packageName: String,
    loadIcon: (String) -> ImageBitmap?,
    modifier: Modifier = Modifier.size(36.dp),
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { loadIcon(packageName) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val DAY_ITEMS = listOf(
    DayFlags.MON to "M", DayFlags.TUE to "T", DayFlags.WED to "W", DayFlags.THU to "T",
    DayFlags.FRI to "F", DayFlags.SAT to "S", DayFlags.SUN to "S",
)

/** Mon–Sun toggles, evenly spread so all seven fit on one line. */
@Composable
fun DayToggleRow(dayFlags: Int, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DAY_ITEMS.forEach { (bit, label) ->
            val on = dayFlags and bit != 0
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = { onToggle(bit) },
                    shape = CircleShape,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * A midnight→midnight bar visualising the filter's day. The capture window is shaded gray (when
 * notifications are held); blue ticks mark exactly when held notifications are released — every
 * interval boundary for BATCH, each set time for DELAY. DISMISS/CATCH have no release ticks.
 */
@Composable
fun WindowTimeline(
    startMinute: Int,
    endMinute: Int,
    action: NotifActionType,
    batchIntervalMinutes: Int,
    releaseTimes: List<Int>,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val captureColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val releaseColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val w = size.width
            val h = size.height
            fun x(minute: Int): Float = (minute / 1440f) * w
            val radius = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(color = trackColor, cornerRadius = radius)

            // Capture window(s) — one region, or two when it wraps past midnight.
            val windows = if (startMinute <= endMinute) {
                listOf(startMinute to endMinute)
            } else {
                listOf(0 to endMinute, startMinute to 1440)
            }
            windows.forEach { (s, e) ->
                val left = x(s)
                drawRect(color = captureColor, topLeft = Offset(left, 0f), size = Size(x(e) - left, h))
            }

            // Release ticks.
            val releases = when (action) {
                NotifActionType.BATCH -> {
                    val iv = batchIntervalMinutes.coerceAtLeast(1)
                    generateSequence(0) { it + iv }.takeWhile { it < 1440 }.toList()
                }
                NotifActionType.DELAY -> releaseTimes
                else -> emptyList()
            }
            releases.forEach { minute ->
                val xx = x(minute).coerceIn(1.5f, w - 1.5f)
                drawLine(
                    color = releaseColor,
                    start = Offset(xx, 0f),
                    end = Offset(xx, h),
                    strokeWidth = 3f,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("12am", style = labelStyle, color = labelColor)
            Text("12pm", style = labelStyle, color = labelColor)
            Text("12am", style = labelStyle, color = labelColor)
        }
        val caption = when (action) {
            NotifActionType.BATCH -> "Gray = held · blue = released each interval"
            NotifActionType.DELAY -> "Gray = held · blue = your release times"
            NotifActionType.CATCH -> "Gray = caught into the inbox"
            NotifActionType.DISMISS -> "Gray = dismissed in this window"
        }
        Text(caption, style = labelStyle, color = labelColor, modifier = Modifier.padding(top = 2.dp))
    }
}
