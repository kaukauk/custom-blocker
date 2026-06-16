package com.customblocker.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.domain.engine.NotifActionType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onAddFilter: () -> Unit,
    onEditFilter: (Long) -> Unit,
    onCreateFromNotification: (pkg: String, seed: String) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    var tab by remember { mutableIntStateOf(0) }
    val history by viewModel.history.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (tab == 0 && history.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearHistory() }) { Text("Clear") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 1) {
                FloatingActionButton(onClick = onAddFilter) {
                    Icon(Icons.Default.Add, contentDescription = "New filter")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("History") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Filters") })
            }
            when (tab) {
                0 -> HistoryList(
                    history = history,
                    loadIcon = viewModel::iconBitmap,
                    onTap = { onCreateFromNotification(it.packageName, it.text.ifBlank { it.title }) },
                    onDelete = { viewModel.deleteCaptured(it) },
                )
                else -> FilterList(
                    filters = filters,
                    onEdit = onEditFilter,
                    onToggle = { id, on -> viewModel.setFilterEnabled(id, on) },
                    onDelete = { viewModel.deleteFilter(it) },
                )
            }
        }
    }
}

@Composable
private fun HistoryList(
    history: List<CapturedRow>,
    loadIcon: (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onTap: (CapturedRow) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (history.isEmpty()) {
        EmptyHint("No notifications captured yet.\nThey'll appear here once apps post them.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Tap a notification to create a filter from it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(history, key = { it.id }) { row ->
            Card(Modifier.fillMaxWidth().clickable { onTap(row) }) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(row.packageName, loadIcon, Modifier.size(36.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.appLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            ActionTag(row.actionApplied)
                            Text(
                                clock(row.postedAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (row.title.isNotBlank()) {
                            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.text.isNotBlank()) {
                            Text(row.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = { onDelete(row.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTag(actionApplied: Int) {
    val label = when (actionApplied) {
        NotifActionType.DISMISS.ordinal -> "dismissed"
        NotifActionType.CATCH.ordinal -> "caught"
        NotifActionType.BATCH.ordinal -> "batched"
        NotifActionType.DELAY.ordinal -> "delayed"
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 6.dp),
    )
}

@Composable
private fun FilterList(
    filters: List<FilterRow>,
    onEdit: (Long) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (filters.isEmpty()) {
        EmptyHint("No filters yet.\nTap + to match notifications and dismiss, catch, batch or delay them.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(filters, key = { it.id }) { f ->
            Card(Modifier.fillMaxWidth().clickable { onEdit(f.id) }) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.title.ifBlank { f.appsLabel },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${f.appsLabel} · ${f.actionLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            f.patternLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(checked = f.enabled, onCheckedChange = { onToggle(f.id, it) })
                    IconButton(onClick = { onDelete(f.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm")

private fun clock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(timeFmt)
