package com.customblocker.ui.strict

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.data.prefs.UnlockGate
import com.customblocker.service.admin.BlockerDeviceAdminReceiver

private val LEVELS = listOf(
    1 to ("Lock configuration" to "Can't edit/delete profiles or turn the app off without unlocking."),
    2 to ("Block uninstall" to "Adds a device-admin lock so the app can't be uninstalled."),
    3 to ("Block device Settings" to "Also blocks the Settings app (Pixel/AOSP) to stop tampering."),
)

private val GATES = listOf(
    UnlockGate.NONE to ("No gate" to "Can be turned off freely (not recommended)."),
    UnlockGate.PIN to ("PIN" to "Enter a PIN to unlock."),
    UnlockGate.TIMER to ("Timer" to "Wait out a countdown to unlock."),
    UnlockGate.CHARGER to ("Charger" to "Must be plugged in to unlock."),
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
                item { LockedBanner(onUnlock = { showUnlock = true }) }
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
                val (gate, text) = GATES[i]
                val (title, desc) = text
                OptionRow(
                    selected = config.gate == gate,
                    title = title,
                    description = desc,
                    enabled = !locked,
                    onClick = { viewModel.setGate(gate) },
                )
            }

            if (config.level >= 2) {
                item {
                    Text("Uninstall protection", style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider()
                }
                item { DeviceAdminCard(locked = locked, onUnlockNeeded = { showUnlock = true }) }
            }

            if (!locked && config.gate == UnlockGate.PIN) {
                item { PinSetter(hasPin = config.hasPin, onSet = viewModel::setPin) }
            }
            if (!locked && config.gate == UnlockGate.TIMER) {
                item { TimerSetter(minutes = config.timerMinutes, onSet = viewModel::setTimerMinutes) }
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

@Composable
private fun PinSetter(hasPin: Boolean, onSet: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (hasPin) "PIN is set — enter a new one to change it" else "Set a PIN",
                style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                    label = { Text("PIN (4–8 digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSet(pin); pin = "" }, enabled = pin.length in 4..8) {
                    Text("Save")
                }
            }
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

@Composable
private fun TimerSetter(minutes: Int, onSet: (Int) -> Unit) {
    var days by remember(minutes) { mutableStateOf((minutes / 1440).toString()) }
    var hours by remember(minutes) { mutableStateOf((minutes % 1440 / 60).toString()) }
    var mins by remember(minutes) { mutableStateOf((minutes % 60).toString()) }
    val total = (days.toIntOrNull() ?: 0) * 1440 + (hours.toIntOrNull() ?: 0) * 60 + (mins.toIntOrNull() ?: 0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Countdown length", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = days,
                    onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) days = it },
                    label = { Text("Days") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = hours,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) hours = it },
                    label = { Text("Hours") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = mins,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) mins = it },
                    label = { Text("Minutes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = { onSet(total) }, enabled = total > 0) {
                Text("Save")
            }
        }
    }
}
