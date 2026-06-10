package com.customblocker.ui.blocklist

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.domain.engine.BlockingMode
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.engine.UrlMatchType
import com.customblocker.ui.components.AppIcon
import com.customblocker.ui.onboarding.PermissionBanner
import com.customblocker.ui.onboarding.rememberPermissionState
import com.customblocker.ui.strict.LockViewModel
import com.customblocker.ui.strict.LockedBanner
import com.customblocker.ui.strict.UnlockDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockListScreen(
    onAddApps: () -> Unit,
    onOpenConditions: (Int?) -> Unit,
    onBack: () -> Unit,
    viewModel: BlockListViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val perms by rememberPermissionState()
    val (accessibilityOn, overlayOn) = perms
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    val locked = lock.locked
    val editLocked by viewModel.editLocked.collectAsStateWithLifecycle()
    val allowlist = state.mode == BlockingMode.ALLOWLIST
    var showUnlock by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val earnUnlock = com.customblocker.ui.strict.rememberEarnUnlockLauncher(lock.config)
    val notificationAccess by com.customblocker.ui.onboarding.rememberNotificationAccessState()

    var blockedTab by remember { mutableIntStateOf(0) } // 0 = Apps, 1 = Websites, 2 = Keywords
    var showRename by remember { mutableStateOf(false) }

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
                title = {
                    Text(
                        state.title.ifBlank { "Profile" },
                        modifier = Modifier.clickable { showRename = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Whole-profile on/off — self-explanatory, lives on the bar. Can't switch off while locked.
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { viewModel.setEnabled(it) },
                        enabled = !(editLocked && state.enabled),
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PermissionBanner(accessibilityOn = accessibilityOn, overlayOn = overlayOn) }
            if (locked) {
                item {
                    LockedBanner(
                        onUnlock = { showUnlock = true },
                        remainingMillis = lock.remainingMillis,
                        onEarnUnlock = earnUnlock,
                    )
                }
            }

            // Conditions get their own emphasised card — they're what actually controls the blocking.
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Conditions", style = MaterialTheme.typography.titleMedium)
                        }
                        if (state.conditions.isEmpty()) {
                            Text(
                                "No conditions set — this profile is always active.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            state.conditions.forEach { cond ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable { onOpenConditions(cond.bit) }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(conditionIcon(cond.bit), contentDescription = null, modifier = Modifier.size(24.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(cond.title, style = MaterialTheme.typography.bodyLarge)
                                        Text(cond.detail, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        FilledTonalButton(onClick = { onOpenConditions(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit conditions")
                        }
                    }
                }
            }

            item {
                Text("Notifications", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
            item {
                ToggleRow(
                    title = "Block notifications",
                    subtitle = "Hold back notifications from blocked apps while this profile is restricting (needs notification access).",
                    checked = state.blockNotifications,
                    enabled = !(editLocked && state.blockNotifications),
                    onCheckedChange = viewModel::setBlockNotifications,
                )
            }
            if (state.blockNotifications) {
                item {
                    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !state.batchNotifications,
                                onClick = { viewModel.setBatchNotifications(false) },
                                label = { Text("Hide them") },
                            )
                            FilterChip(
                                selected = state.batchNotifications,
                                onClick = { viewModel.setBatchNotifications(true) },
                                label = { Text("Batch until active") },
                            )
                        }
                        Text(
                            if (state.batchNotifications)
                                "Held while this profile is blocking these apps, and delivered once they're usable again."
                            else "Notifications are dismissed and never shown.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!notificationAccess) {
                    item {
                        OutlinedButton(
                            onClick = { com.customblocker.ui.onboarding.Permissions.openNotificationAccessSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Grant notification access") }
                    }
                }
            }

            // Mode selector
            item {
                Text("Blocking list", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(top = 4.dp))
            }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !allowlist,
                        onClick = { viewModel.setMode(BlockingMode.BLOCKLIST) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        enabled = !editLocked,
                    ) { Text("Block list") }
                    SegmentedButton(
                        selected = allowlist,
                        onClick = { viewModel.setMode(BlockingMode.ALLOWLIST) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        enabled = !editLocked,
                    ) { Text("Allow list") }
                }
            }
            item {
                Text(
                    if (allowlist) "Only these apps are allowed; everything else is blocked when active."
                    else "These apps, sites and keywords are blocked when active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Blocked content tabs — counts in the label; no check tick (it implied "only this is blocked").
            item {
                val tabs = listOf(
                    "Apps (${state.apps.size})",
                    "Websites (${state.websites.size})",
                    "Keywords (${state.keywords.size})",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = blockedTab == i,
                            onClick = { blockedTab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 3),
                            icon = {},
                        ) { Text(label) }
                    }
                }
            }

            when (blockedTab) {
                0 -> appsTab(state, editLocked, allowlist, onAddApps, viewModel)
                1 -> websitesTab(state, editLocked, viewModel)
                else -> keywordsTab(state, editLocked, viewModel)
            }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(state.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename profile") },
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
                    viewModel.renameProfile(name)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appsTab(
    state: BlockListUiState,
    editLocked: Boolean,
    allowlist: Boolean,
    onAddApps: () -> Unit,
    viewModel: BlockListViewModel,
) {
    if (state.apps.isEmpty()) {
        item { Text("No apps yet.", style = MaterialTheme.typography.bodyMedium) }
    } else {
        items(state.apps, key = { "app:${it.packageName}" }) { app ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName, Modifier.size(40.dp).clip(MaterialTheme.shapes.small))
                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Removing lowers a blocklist's restrictiveness (blocked when locked) but raises an allowlist's.
                IconButton(
                    onClick = { viewModel.removeApp(app.packageName) },
                    enabled = !editLocked || allowlist,
                ) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
            }
        }
    }
    // Adding apps raises restrictiveness for a blocklist (ok when locked) but lowers it for an allowlist.
    if (!editLocked || !allowlist) {
        item {
            OutlinedButton(onClick = onAddApps, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Add apps")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.websitesTab(
    state: BlockListUiState,
    editLocked: Boolean,
    viewModel: BlockListViewModel,
) {
    item {
        AddWebsiteRow(
            placeholder = "e.g. youtube.com",
            extraChips = true,
            onAdd = { value, exact -> viewModel.addWebsite(value, if (exact) UrlMatchType.EXACT else UrlMatchType.DOMAIN) },
        )
    }
    item {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Block unsupported browsers", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "If a browser's address can't be read, block the whole browser.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = state.blockUnsupportedBrowsers,
                onCheckedChange = viewModel::setBlockUnsupportedBrowsers,
                enabled = !(editLocked && state.blockUnsupportedBrowsers),
            )
        }
    }
    if (state.websites.isEmpty()) {
        item { Text("No websites yet.", style = MaterialTheme.typography.bodyMedium) }
    } else {
        items(state.websites, key = { "web:${it.id}" }) { site ->
            RuleRow(
                value = site.value,
                subtitle = if (UrlMatchType.entries.getOrNull(site.matchType) == UrlMatchType.EXACT) "Exact URL" else "Domain (and subdomains)",
                enabled = !editLocked,
                onRemove = { viewModel.removeWebsite(site.id) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.keywordsTab(
    state: BlockListUiState,
    editLocked: Boolean,
    viewModel: BlockListViewModel,
) {
    item {
        AddWebsiteRow(
            placeholder = "e.g. casino",
            extraChips = false,
            onAdd = { value, _ -> viewModel.addWebsite(value, UrlMatchType.KEYWORD) },
        )
    }
    if (state.keywords.isEmpty()) {
        item { Text("No keywords yet.", style = MaterialTheme.typography.bodyMedium) }
    } else {
        items(state.keywords, key = { "kw:${it.id}" }) { kw ->
            RuleRow(
                value = kw.value,
                subtitle = "Keyword in URL",
                enabled = !editLocked,
                onRemove = { viewModel.removeWebsite(kw.id) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWebsiteRow(placeholder: String, extraChips: Boolean, onAdd: (String, Boolean) -> Unit) {
    var input by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (extraChips) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !exact, onClick = { exact = false }, label = { Text("Domain") })
                FilterChip(selected = exact, onClick = { exact = true }, label = { Text("Exact") })
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onAdd(input, exact); input = "" },
                enabled = input.isNotBlank(),
            ) { Text("Add") }
        }
    }
}

@Composable
private fun RuleRow(value: String, subtitle: String, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Default.Delete, contentDescription = "Remove")
        }
    }
}

private fun conditionIcon(bit: Int): ImageVector = when (bit) {
    ConditionTypes.TIME -> Icons.Default.Schedule
    ConditionTypes.WIFI -> Icons.Default.Wifi
    ConditionTypes.LOCATION -> Icons.Default.Place
    ConditionTypes.USAGE_LIMIT -> Icons.Default.Timer
    ConditionTypes.LAUNCH_COUNT -> Icons.Default.PhoneAndroid
    else -> Icons.Default.BatteryChargingFull
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
