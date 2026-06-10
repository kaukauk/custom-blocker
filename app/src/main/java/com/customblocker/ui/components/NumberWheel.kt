package com.customblocker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * A vertical scroll-wheel number picker (like a time-picker reel). Snaps to a value; the centred
 * item is the selection. Driving control: it calls [onValue] when scrolling settles on a new value.
 *
 * When [enabled] is false the wheel is read-only (used while editing is locked).
 */
@Composable
fun NumberWheel(
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val values = remember(range) { range.toList() }
    val itemHeight = 44.dp
    val visibleItems = 3
    val density = LocalDensity.current

    val initialIndex = (value - range.first).coerceIn(0, values.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberSnapFlingBehavior(lazyListState = listState)

    // The centred row = first visible index, nudged by the scroll offset past the half mark.
    val centered by remember {
        derivedStateOf {
            val itemPx = with(density) { itemHeight.toPx() }
            val bump = if (listState.firstVisibleItemScrollOffset > itemPx / 2f) 1 else 0
            (listState.firstVisibleItemIndex + bump).coerceIn(0, values.lastIndex)
        }
    }

    // Report the settled value once scrolling stops.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val v = values[centered]
            if (v != value) onValue(v)
        }
    }

    Box(
        modifier.height(itemHeight * visibleItems).width(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Selection band behind the centre row.
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .align(Alignment.Center)
                .padding(horizontal = 4.dp)
                .background(
                    androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(10.dp),
                ),
        )
        LazyColumn(
            state = listState,
            flingBehavior = fling,
            userScrollEnabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = itemHeight),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(values) { index, n ->
                val selected = index == centered
                Box(
                    Modifier.height(itemHeight).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = n.toString(),
                        style = if (selected) {
                            androidx.compose.material3.MaterialTheme.typography.headlineSmall
                        } else {
                            androidx.compose.material3.MaterialTheme.typography.titleMedium
                        },
                        color = if (selected) {
                            androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    )
                }
            }
        }
    }
}
