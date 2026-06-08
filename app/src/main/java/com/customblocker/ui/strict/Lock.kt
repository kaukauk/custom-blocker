package com.customblocker.ui.strict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.customblocker.data.prefs.StrictModeConfig
import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.data.prefs.UnlockGate
import com.customblocker.domain.strict.UnlockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LockUi(val locked: Boolean = false, val config: StrictModeConfig = StrictModeConfig())

/** Shared lock state for any screen with Strict-Mode-protected actions. */
@HiltViewModel
class LockViewModel @Inject constructor(
    store: StrictModeStore,
    private val unlock: UnlockManager,
) : ViewModel() {

    val state: StateFlow<LockUi> =
        combine(store.config, unlock.unlocked) { cfg, unlocked ->
            LockUi(locked = cfg.enabled && !unlocked, config = cfg)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockUi())

    fun markUnlocked() = unlock.markUnlocked()
    fun isCharging() = unlock.isCharging()
    suspend fun verifyPin(pin: String) = unlock.verifyPin(pin)
}

/** A banner shown on protected screens while locked, offering to unlock. */
@Composable
fun LockedBanner(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Locked by Strict Mode", style = MaterialTheme.typography.titleMedium)
            Text("Unlock to change blocking settings.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

/** Drives the configured unlock gate; calls [onUnlocked] on success. */
@Composable
fun UnlockDialog(
    config: StrictModeConfig,
    lockViewModel: LockViewModel,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Guard against lock-out: PIN gate with no PIN set could never be satisfied, so fall back to a
    // plain confirm.
    val gate = if (config.gate == UnlockGate.PIN && !config.hasPin) UnlockGate.NONE else config.gate
    when (gate) {
        UnlockGate.NONE -> ConfirmUnlock(
            text = "Strict Mode has no unlock gate set. Unlock now?",
            onConfirm = { lockViewModel.markUnlocked(); onUnlocked() },
            onDismiss = onDismiss,
        )
        UnlockGate.PIN -> PinUnlock(lockViewModel, onUnlocked, onDismiss)
        UnlockGate.CHARGER -> ChargerUnlock(lockViewModel, onUnlocked, onDismiss)
        UnlockGate.TIMER -> TimerUnlock(config.timerMinutes, lockViewModel, onUnlocked, onDismiss)
    }
}

@Composable
private fun ConfirmUnlock(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PinUnlock(vm: LockViewModel, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; error = false } },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (error) Text("Incorrect PIN", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (vm.verifyPin(pin)) { vm.markUnlocked(); onUnlocked() } else error = true
                }
            }, enabled = pin.length in 4..8) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChargerUnlock(vm: LockViewModel, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val charging = vm.isCharging()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plug in to unlock") },
        text = {
            Text(if (charging) "Charger detected. You can unlock now."
                 else "Connect a charger to unlock Strict Mode.")
        },
        confirmButton = {
            TextButton(onClick = { vm.markUnlocked(); onUnlocked() }, enabled = charging) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimerUnlock(minutes: Int, vm: LockViewModel, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var remaining by remember { mutableStateOf(minutes * 60) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1_000); remaining-- }
        vm.markUnlocked(); onUnlocked()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wait to unlock") },
        text = {
            val m = remaining / 60
            val s = remaining % 60
            Text("Strict Mode unlocks in %d:%02d. Keep this open.".format(m, s))
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
