package com.customblocker.ui.blocklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.ui.onboarding.PermissionBanner
import com.customblocker.ui.onboarding.rememberPermissionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockListScreen(
    onAddApps: () -> Unit,
    viewModel: BlockListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val perms by rememberPermissionState()
    val (accessibilityOn, overlayOn) = perms

    Scaffold(
        topBar = { TopAppBar(title = { Text("Custom Blocker") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddApps) {
                Icon(Icons.Default.Add, contentDescription = "Add apps")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PermissionBanner(accessibilityOn = accessibilityOn, overlayOn = overlayOn)
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Blocking", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.enabled) "On" else "Off",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.mode == BlockingMode.BLOCKLIST,
                            onClick = { viewModel.setMode(BlockingMode.BLOCKLIST) },
                            label = { Text("Block list") },
                        )
                        FilterChip(
                            selected = state.mode == BlockingMode.ALLOWLIST,
                            onClick = { viewModel.setMode(BlockingMode.ALLOWLIST) },
                            label = { Text("Allow list") },
                        )
                    }
                }
                item {
                    Text(
                        if (state.mode == BlockingMode.BLOCKLIST) "Blocked apps (${state.apps.size})"
                        else "Allowed apps (${state.apps.size})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
                if (state.apps.isEmpty()) {
                    item {
                        Text(
                            "No apps yet — tap + to add some.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(state.apps, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { viewModel.removeApp(app.packageName) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}
