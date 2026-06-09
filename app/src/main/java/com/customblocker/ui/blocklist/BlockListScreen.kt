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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.layout.Column
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.ui.onboarding.PermissionBanner
import com.customblocker.ui.onboarding.rememberPermissionState
import com.customblocker.ui.strict.LockViewModel
import com.customblocker.ui.strict.LockedBanner
import com.customblocker.ui.strict.UnlockDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockListScreen(
    onAddApps: () -> Unit,
    onOpenConditions: () -> Unit,
    onOpenWebsites: () -> Unit,
    onBack: () -> Unit,
    viewModel: BlockListViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val perms by rememberPermissionState()
    val (accessibilityOn, overlayOn) = perms
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    val locked = lock.locked
    // editLocked = strict lock OR this profile is currently active; gates restrictiveness-lowering edits.
    val editLocked by viewModel.editLocked.collectAsStateWithLifecycle()
    val allowlist = state.mode == BlockingMode.ALLOWLIST
    var showUnlock by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val earnUnlock = com.customblocker.ui.strict.rememberEarnUnlockLauncher(lock.config)
    val notificationAccess by com.customblocker.ui.onboarding.rememberNotificationAccessState()

    if (showUnlock) {
        UnlockDialog(
            config = lock.config,
            lockViewModel = lockViewModel,
            onUnlocked = { showUnlock = false },
            onDismiss = { showUnlock = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { "Profile" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            // Adding apps raises restrictiveness for a blocklist (ok even when locked) but lowers it
            // for an allowlist (blocked when locked).
            if (!editLocked || !allowlist) {
                FloatingActionButton(onClick = onAddApps) {
                    Icon(Icons.Default.Add, contentDescription = "Add apps")
                }
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
                if (locked) {
                    item {
                        LockedBanner(
                            onUnlock = { showUnlock = true },
                            remainingMillis = lock.remainingMillis,
                            onEarnUnlock = earnUnlock,
                        )
                    }
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
                        Switch(
                            checked = state.enabled,
                            // Can switch a profile on while locked, but not off.
                            onCheckedChange = { viewModel.setEnabled(it) },
                            enabled = !(editLocked && state.enabled),
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.mode == BlockingMode.BLOCKLIST,
                            onClick = { viewModel.setMode(BlockingMode.BLOCKLIST) },
                            label = { Text("Block list") },
                            enabled = !editLocked,
                        )
                        FilterChip(
                            selected = state.mode == BlockingMode.ALLOWLIST,
                            onClick = { viewModel.setMode(BlockingMode.ALLOWLIST) },
                            label = { Text("Allow list") },
                            enabled = !editLocked,
                        )
                    }
                }
                item {
                    OutlinedButton(onClick = onOpenConditions, modifier = Modifier.fillMaxWidth()) {
                        Text("Conditions (when to block)")
                    }
                }
                item {
                    OutlinedButton(onClick = onOpenWebsites, modifier = Modifier.fillMaxWidth()) {
                        Text("Websites & keywords")
                    }
                }
                item {
                    Text("Behavior", style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }
                item {
                    ToggleRow(
                        title = "Block launching",
                        subtitle = "Eject when a blocked app is opened.",
                        checked = state.blockLaunch,
                        enabled = !(editLocked && state.blockLaunch),
                        onCheckedChange = viewModel::setBlockLaunch,
                    )
                }
                item {
                    ToggleRow(
                        title = "Block notifications",
                        subtitle = "Silently dismiss notifications from blocked apps (needs notification access).",
                        checked = state.blockNotifications,
                        enabled = !(editLocked && state.blockNotifications),
                        onCheckedChange = viewModel::setBlockNotifications,
                    )
                }
                if (state.blockNotifications && !notificationAccess) {
                    item {
                        OutlinedButton(
                            onClick = { com.customblocker.ui.onboarding.Permissions.openNotificationAccessSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Grant notification access") }
                    }
                }
                item {
                    ToggleRow(
                        title = "Auto-block new apps",
                        subtitle = "Add newly-installed apps to this profile automatically.",
                        checked = state.addNewApps,
                        enabled = !(editLocked && state.addNewApps),
                        onCheckedChange = viewModel::setAddNewApps,
                    )
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
                            // Removing lowers a blocklist's restrictiveness (blocked when locked) but
                            // raises an allowlist's (always allowed).
                            IconButton(
                                onClick = { viewModel.removeApp(app.packageName) },
                                enabled = !editLocked || allowlist,
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
