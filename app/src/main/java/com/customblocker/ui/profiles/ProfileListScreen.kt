package com.customblocker.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.ui.onboarding.PermissionBanner
import com.customblocker.ui.onboarding.ReliabilityBanner
import com.customblocker.ui.onboarding.rememberPermissionState
import com.customblocker.ui.strict.LockViewModel
import com.customblocker.ui.strict.LockedBanner
import com.customblocker.ui.strict.UnlockDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileListScreen(
    onOpenProfile: (Long) -> Unit,
    onOpenStrictMode: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: ProfileListViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val perms by rememberPermissionState()
    val (accessibilityOn, overlayOn) = perms
    var showCreate by remember { mutableStateOf(false) }
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    val locked = lock.locked
    val lockInfo by viewModel.lockInfo.collectAsStateWithLifecycle()
    var showUnlock by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.customblocker.data.db.ProfileEntity?>(null) }
    val earnUnlock = com.customblocker.ui.strict.rememberEarnUnlockLauncher(lock.config)

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
                title = { Text("Custom Blocker") },
                actions = {
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Default.BarChart, contentDescription = "Statistics")
                    }
                    IconButton(onClick = onOpenStrictMode) {
                        Icon(Icons.Default.Lock, contentDescription = "Strict Mode")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "New profile")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PermissionBanner(accessibilityOn = accessibilityOn, overlayOn = overlayOn) }
            item { ReliabilityBanner() }
            if (locked) {
                item {
                    LockedBanner(
                        onUnlock = { showUnlock = true },
                        remainingMillis = lock.remainingMillis,
                        onEarnUnlock = earnUnlock,
                    )
                }
            }
            if (profiles.isEmpty()) {
                item { Text("No profiles yet — tap + to create one.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(profiles, key = { it.id }) { profile ->
                val pLocked = lockInfo.locked(profile.id)
                Card(Modifier.fillMaxWidth().clickable { onOpenProfile(profile.id) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                buildString {
                                    append(if (profile.blockingMode == 1) "Allow list" else "Block list")
                                    if (profile.conditionsMask != 0) append(" • conditional")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = profile.enabled,
                            // Can enable a profile while locked, but not disable it.
                            onCheckedChange = { viewModel.setEnabled(profile.id, it) },
                            enabled = !(pLocked && profile.enabled),
                        )
                        IconButton(
                            onClick = { pendingDelete = profile },
                            enabled = !pLocked,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete profile")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete profile?") },
            text = { Text("\"${profile.title}\" and its apps, websites and conditions will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(profile.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New profile") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    viewModel.createProfile(name.ifBlank { "Profile" }) { id -> onOpenProfile(id) }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } },
        )
    }
}
