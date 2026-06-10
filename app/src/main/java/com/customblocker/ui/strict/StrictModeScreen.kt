package com.customblocker.ui.strict

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import com.customblocker.service.admin.BlockerDeviceAdminReceiver
import com.customblocker.ui.components.NumberWheel

private val LEVELS = listOf(
    1 to ("Lock configuration" to "Can't edit/delete profiles or turn the app off without unlocking."),
    2 to ("Block uninstall" to "Adds a device-admin lock so the app can't be uninstalled."),
    3 to ("Block device Settings" to "Also blocks the Settings app (Pixel/AOSP) to stop tampering."),
)

/** Selectable unlock methods (No-gate and Charger were intentionally removed — too easy to bypass). */
private data class GateInfo(val gate: UnlockGate, val icon: ImageVector, val title: String, val description: String)

private val GATES = listOf(
    GateInfo(
        UnlockGate.PIN, Icons.Default.Pin, "PIN",
        "Enter a PIN to unlock. If you ever forget it you won't be stuck — you can still unlock " +
            "after a long wait you set below.",
    ),
    GateInfo(
        UnlockGate.COUNTDOWN, Icons.Default.HourglassEmpty, "Countdown",
        "Each time you unlock, wait out a countdown with the dialog kept open. Closing it cancels, " +
            "so unlocking always costs you the wait.",
    ),
    GateInfo(
        UnlockGate.TIMER, Icons.Default.Lock, "Timer",
        "Commit for a fixed time — Strict Mode can't be switched off until it elapses. The strictest " +
            "option; optionally allow short earned-unlock breaks.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrictModeScreen(
    onBack: () -> Unit,
    viewModel: StrictModeViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val lock by lockViewModel.state.collectAsStateWithLifecycle()
    val locked = lock.locked
    var showUnlock by remember { mutableStateOf(false) }
    val earnUnlock = rememberEarnUnlockLauncher(lock.config)
    // Accordion: at most one unlock-method card expanded at a time (open the selected one by default).
    var expandedGate by remember { mutableStateOf<UnlockGate?>(config.gate) }

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
                title = { Text("Strict Mode") },
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
                    Column(Modifier.weight(1f)) {
                        Text("Strict Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Makes blocking harder to bypass. Choose how strict and how to unlock.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = viewModel::setEnabled,
                        enabled = !locked,
                    )
                }
            }

            item {
                Text("Level", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider()
            }
            items(LEVELS.size) { i ->
                val (lvl, text) = LEVELS[i]
                val (title, desc) = text
                OptionRow(
                    selected = config.level == lvl,
                    title = "L$lvl — $title",
                    description = desc,
                    enabled = !locked,
                    onClick = { viewModel.setLevel(lvl) },
                )
            }

            item {
                Text("Unlock method", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider()
            }
            items(GATES.size) { i ->
                val info = GATES[i]
                GateCard(
                    info = info,
                    selected = config.gate == info.gate,
                    expanded = expandedGate == info.gate,
                    enabled = !locked,
                    onExpand = { expandedGate = if (expandedGate == info.gate) null else info.gate },
                    onSelect = { viewModel.setGate(info.gate); expandedGate = info.gate },
                ) {
                    when (info.gate) {
                        UnlockGate.PIN -> PinEditor(config = config, locked = locked, viewModel = viewModel)
                        UnlockGate.COUNTDOWN -> DurationEditor(
                            title = "Countdown length",
                            description = "Each time you unlock, wait this long with the dialog kept open.",
                            minutes = config.timerMinutes,
                            showDays = false,
                            locked = locked,
                            onSet = viewModel::setTimerMinutes,
                        )
                        UnlockGate.TIMER -> {
                            DurationEditor(
                                title = "Stay locked for",
                                description = "Strict Mode turns on for this long and can't be switched off until it ends.",
                                minutes = config.timerMinutes,
                                showDays = true,
                                locked = locked,
                                onSet = viewModel::setTimerMinutes,
                            )
                            EarnUnlockSetter(
                                enabled = config.earnedUnlockEnabled,
                                waitMinutes = config.earnWaitMinutes,
                                maxMinutes = config.earnMaxMinutes,
                                locked = locked,
                                onToggle = viewModel::setEarnedUnlockEnabled,
                                onSetWait = viewModel::setEarnWaitMinutes,
                                onSetMax = viewModel::setEarnMaxMinutes,
                            )
                        }
                        else -> {}
                    }
                }
            }

            if (config.level >= 2) {
                item {
                    Text("Uninstall protection", style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider()
                }
                item { DeviceAdminCard(locked = locked, onUnlockNeeded = { showUnlock = true }) }
            }
        }
    }
}

/**
 * One unlock method as an expandable card (accordion). Tapping the header expands its description;
 * a "Use this method" button selects it and reveals its variable [editor] (scroll wheels etc.).
 */
@Composable
private fun GateCard(
    info: GateInfo,
    selected: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onSelect: () -> Unit,
    editor: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onExpand() },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    info.icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(info.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (selected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        info.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected) {
                        editor()
                    } else {
                        FilledTonalButton(onClick = onSelect, enabled = enabled) { Text("Use this method") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Editor for the PIN gate: set/change the PIN (keypad) + the forgot-PIN escape-hatch wait. */
@Composable
private fun PinEditor(config: com.customblocker.data.prefs.StrictModeConfig, locked: Boolean, viewModel: StrictModeViewModel) {
    var pin by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (config.hasPin) "PIN is set — enter a new one to change it." else "Set a PIN.",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                label = { Text("PIN (4–8 digits)") },
                singleLine = true,
                enabled = !locked,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.setPin(pin); pin = "" }, enabled = !locked && pin.length in 4..8) {
                Text("Save")
            }
        }
        HorizontalDivider()
        Text("If you forget your PIN", style = MaterialTheme.typography.titleSmall)
        Text(
            "You can still unlock without the PIN — but only after waiting this long once you start " +
                "the emergency unlock. Keeps you from being permanently locked out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WheelCol("hours", config.pinEmergencyHours, StrictModeStore.PIN_EMERGENCY_FLOOR..StrictModeStore.PIN_EMERGENCY_MAX, locked) {
                viewModel.setPinEmergencyHours(it)
            }
            Text(
                "Wait ${config.pinEmergencyHours}h to unlock if the PIN is lost.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DeviceAdminCard(locked: Boolean, onUnlockNeeded: () -> Unit) {
    val context = LocalContext.current
    val active by rememberDeviceAdminActive()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (active) "Uninstall protection is ON" else "Uninstall protection is OFF",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "When on, Android won't let Custom Blocker be uninstalled. Turning it off requires unlocking.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!active) {
                TextButton(onClick = { context.startActivity(BlockerDeviceAdminReceiver.activationIntent(context)) }) {
                    Text("Turn on")
                }
            } else {
                TextButton(onClick = {
                    if (locked) onUnlockNeeded() else BlockerDeviceAdminReceiver.deactivate(context)
                }) { Text("Turn off") }
            }
        }
    }
}

/** Duration picker built from scroll wheels (days?/hours/minutes); writes the total minutes live. */
@Composable
private fun DurationEditor(
    title: String,
    description: String,
    minutes: Int,
    showDays: Boolean,
    locked: Boolean,
    onSet: (Int) -> Unit,
) {
    val d = minutes / 1440
    val h = minutes % 1440 / 60
    val m = minutes % 60
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (showDays) WheelCol("days", d, 0..30, locked) { onSet(it * 1440 + h * 60 + m) }
            WheelCol("hours", h, 0..23, locked) { onSet(d * 1440 + it * 60 + m) }
            WheelCol("min", m, 0..59, locked) { onSet(d * 1440 + h * 60 + it) }
        }
    }
}

@Composable
private fun WheelCol(label: String, value: Int, range: IntRange, locked: Boolean, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberWheel(value = value.coerceIn(range.first, range.last), range = range, onValue = onValue, enabled = !locked)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EarnUnlockSetter(
    enabled: Boolean,
    waitMinutes: Int,
    maxMinutes: Int,
    locked: Boolean,
    onToggle: (Boolean) -> Unit,
    onSetWait: (Int) -> Unit,
    onSetMax: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Allow earned unlock", style = MaterialTheme.typography.titleSmall)
                Text(
                    "While committed, you can sit in a full-screen window to bank temporary unlock time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = !locked)
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                WheelCol("wait (min)", waitMinutes, StrictModeStore.EARN_WAIT_MIN..StrictModeStore.EARN_WAIT_MAX, locked, onSetWait)
                WheelCol("max (min)", maxMinutes, StrictModeStore.EARN_MAX_MIN..StrictModeStore.EARN_MAX_CAP, locked, onSetMax)
            }
        }
    }
}
