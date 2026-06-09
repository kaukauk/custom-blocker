package com.customblocker.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showIgnored by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsRange.entries.forEach { r ->
                        FilterChip(
                            selected = state.range == r,
                            onClick = { viewModel.setRange(r) },
                            label = { Text(r.label) },
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard("Screen time", formatDuration(state.totalUsageSeconds), Modifier.weight(1f))
                    SummaryCard("Blocks", state.totalBlocks.toString(), Modifier.weight(1f))
                    SummaryCard("Notifs hidden", state.totalNotificationBlocks.toString(), Modifier.weight(1f))
                }
            }
            item {
                Text("By app", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
            if (state.rows.isEmpty()) {
                item { Text("No activity recorded yet.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.rows, key = { it.packageName }) { row ->
                    StatRowItem(
                        row = row,
                        trailing = {
                            IconButton(onClick = { viewModel.ignore(row.packageName) }) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = "Hide from statistics")
                            }
                        },
                    )
                }
            }

            if (state.ignored.isNotEmpty()) {
                item {
                    TextButton(onClick = { showIgnored = !showIgnored }) {
                        Text(if (showIgnored) "Hide ignored (${state.ignored.size})" else "Show ignored (${state.ignored.size})")
                    }
                }
                if (showIgnored) {
                    items(state.ignored, key = { "ignored:${it.packageName}" }) { row ->
                        StatRowItem(
                            row = row,
                            showStats = false,
                            trailing = {
                                IconButton(onClick = { viewModel.restore(row.packageName) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Restore to statistics")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRowItem(row: StatRow, trailing: @Composable () -> Unit, showStats: Boolean = true) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconModifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
        if (row.icon != null) {
            Image(bitmap = row.icon, contentDescription = null, modifier = iconModifier)
        } else {
            Box(iconModifier)
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showStats) {
                Text(
                    buildString {
                        append(formatDuration(row.usageSeconds))
                        if (row.blockCount > 0) append(" • ${row.blockCount} blocks")
                        if (row.notificationBlockCount > 0) append(" • ${row.notificationBlockCount} notifs")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${seconds}s"
    }
}
