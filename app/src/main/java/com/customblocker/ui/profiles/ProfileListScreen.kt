package com.customblocker.ui.profiles

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.ui.onboarding.NotificationNudgeBanner
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
    onOpenSettings: () -> Unit,
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
    var pendingDelete by remember { mutableStateOf<ProfileRow?>(null) }
    var customPauseFor by remember { mutableStateOf<Long?>(null) }
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
            item { NotificationNudgeBanner() }
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
                // Active profiles get a primary outline; an exhausted limit makes the whole card
                // turn red (filled + thicker border) so "no time left" really stands out.
                val outline = when {
                    profile.limitExceeded -> MaterialTheme.colorScheme.error
                    profile.active -> MaterialTheme.colorScheme.primary
                    else -> null
                }
                val cardColors = if (profile.limitExceeded) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    CardDefaults.cardColors()
                }
                val cardModifier = Modifier.fillMaxWidth()
                    .then(
                        if (outline != null) {
                            Modifier.border(
                                width = if (profile.limitExceeded) 3.dp else 2.dp,
                                color = outline,
                                shape = CardDefaults.shape,
                            )
                        } else Modifier,
                    )
                    .clickable { onOpenProfile(profile.id) }
                Card(cardModifier, colors = cardColors) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                profile.summary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (profile.paused) {
                                DropdownMenuItem(
                                    text = { Text("Resume now") },
                                    onClick = { menuOpen = false; viewModel.resumeProfile(profile.id) },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Pause 15 minutes") },
                                    enabled = !pLocked,
                                    onClick = { menuOpen = false; viewModel.pauseProfile(profile.id, 15 * 60_000L) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Pause 1 hour") },
                                    enabled = !pLocked,
                                    onClick = { menuOpen = false; viewModel.pauseProfile(profile.id, 60 * 60_000L) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Pause for…") },
                                    enabled = !pLocked,
                                    onClick = { menuOpen = false; customPauseFor = profile.id },
                                )
                                DropdownMenuItem(
                                    text = { Text("Pause indefinitely") },
                                    enabled = !pLocked,
                                    onClick = { menuOpen = false; viewModel.pauseIndefinitely(profile.id) },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete profile", color = MaterialTheme.colorScheme.error) },
                                enabled = !pLocked,
                                onClick = { menuOpen = false; pendingDelete = profile },
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete \"${profile.title}\"?") },
            text = {
                Text(
                    "This permanently deletes the profile and all of its apps, websites and " +
                        "conditions. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(profile.id)
                    pendingDelete = null
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }

    customPauseFor?.let { profileId ->
        var minutes by remember { mutableStateOf(30) }
        AlertDialog(
            onDismissRequest = { customPauseFor = null },
            title = { Text("Pause for…") },
            text = {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.customblocker.ui.components.NumberWheel(
                        value = minutes,
                        range = 1..180,
                        onValue = { minutes = it },
                    )
                    Text("minutes", Modifier.padding(start = 12.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.pauseProfile(profileId, minutes * 60_000L)
                        customPauseFor = null
                    },
                ) { Text("Pause") }
            },
            dismissButton = { TextButton(onClick = { customPauseFor = null }) { Text("Cancel") } },
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
