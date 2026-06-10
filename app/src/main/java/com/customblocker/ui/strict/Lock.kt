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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LockUi(
    val locked: Boolean = false,
    val config: StrictModeConfig = StrictModeConfig(),
    /** For a TIMER commitment: ms left until it lifts (0 otherwise). */
    val remainingMillis: Long = 0L,
)

/** Format a millisecond remaining-duration as "2d 03:05:09" / "3:05:09" / "5:09". */
fun formatCountdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val d = total / 86_400
    val h = total % 86_400 / 3_600
    val m = total % 3_600 / 60
    val s = total % 60
    return when {
        d > 0 -> "%dd %02d:%02d:%02d".format(d, h, m, s)
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%d:%02d".format(m, s)
    }
}

/** Shared lock state for any screen with Strict-Mode-protected actions. */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val store: StrictModeStore,
    private val unlock: UnlockManager,
) : ViewModel() {

    // 1 Hz clock so a TIMER commitment counts down live and the lock lifts the moment it expires.
    private val ticker = flow {
        while (true) { emit(System.currentTimeMillis()); delay(1_000) }
    }

    val state: StateFlow<LockUi> =
        combine(store.config, unlock.unlocked, ticker) { cfg, unlocked, now ->
            val locked =
                if (cfg.gate == UnlockGate.TIMER) cfg.timedLockActive(now)
                else cfg.enabled && !unlocked
            val remaining =
                if (cfg.enabled && cfg.gate == UnlockGate.TIMER) (cfg.lockedUntilMillis - now).coerceAtLeast(0L)
                else 0L
            LockUi(locked = locked, config = cfg, remainingMillis = remaining)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockUi())

    /**
     * Passing the unlock gate turns Strict Mode genuinely OFF (not a temporary session unlock), so
     * there's no confusing "enabled but unlocked" half-state. [unlock.relock] resets the in-memory
     * flag so that simply re-enabling Strict Mode later locks again as expected.
     */
    fun markUnlocked() {
        unlock.relock()
        viewModelScope.launch {
            store.setEnabled(false)        // fully disable (also clears any TIMER deadline / PIN wait)
            store.clearPinEmergency()
        }
    }
    fun isCharging() = unlock.isCharging()
    suspend fun verifyPin(pin: String) = unlock.verifyPin(pin)

    /** Begin the forgot-PIN escape-hatch wait (persisted; survives relaunch). */
    fun startPinEmergency() = viewModelScope.launch { store.startPinEmergency(System.currentTimeMillis()) }
}

/** Whether our device-admin (uninstall protection) is active, refreshed on every ON_RESUME. */
@Composable
fun rememberDeviceAdminActive(): androidx.compose.runtime.State<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val state = remember {
        mutableStateOf(com.customblocker.service.admin.BlockerDeviceAdminReceiver.isActive(context))
    }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                state.value = com.customblocker.service.admin.BlockerDeviceAdminReceiver.isActive(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * Returns a lambda that opens the Earn-unlock window, but only when it applies: a TIMER commitment
 * with earned-unlock enabled. Null otherwise — callers pass it straight to [LockedBanner].
 */
@Composable
fun rememberEarnUnlockLauncher(config: StrictModeConfig): (() -> Unit)? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return if (config.earnedUnlockEnabled && config.gate == UnlockGate.TIMER) {
        { context.startActivity(android.content.Intent(context, EarnUnlockActivity::class.java)) }
    } else null
}

/**
 * A banner shown on protected screens while locked. For a TIMER commitment it shows the live
 * countdown and no early-unlock button (you committed to it); otherwise it offers to unlock.
 */
@Composable
fun LockedBanner(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    remainingMillis: Long = 0L,
    onEarnUnlock: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Locked by Strict Mode", style = MaterialTheme.typography.titleMedium)
            if (remainingMillis > 0L) {
                Text("Unlocks in ${formatCountdown(remainingMillis)}.", style = MaterialTheme.typography.bodySmall)
                if (onEarnUnlock != null) {
                    TextButton(onClick = onEarnUnlock) { Text("Earn unlock time") }
                }
            } else {
                Text("Unlock to change blocking settings.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onUnlock) { Text("Unlock") }
            }
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
        UnlockGate.PIN -> PinUnlock(config, lockViewModel, onUnlocked, onDismiss)
        UnlockGate.CHARGER -> ChargerUnlock(lockViewModel, onUnlocked, onDismiss)
        UnlockGate.COUNTDOWN -> CountdownUnlock(config.timerMinutes, lockViewModel, onUnlocked, onDismiss)
        UnlockGate.TIMER -> TimerCommitmentInfo(config.lockedUntilMillis, onDismiss)
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

/**
 * PIN gate. Enter the PIN to unlock instantly. If the PIN is forgotten, the escape hatch lets the
 * user start a long, persisted wait (config.pinEmergencyHours) after which they can unlock without
 * it — so a forgotten PIN never means being permanently stuck, while staying strict.
 */
@Composable
private fun PinUnlock(config: StrictModeConfig, vm: LockViewModel, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(config.pinEmergencyUntilMillis) {
        while (config.pinEmergencyPending(now)) { delay(1_000); now = System.currentTimeMillis() }
    }
    val pending = config.pinEmergencyPending(now)
    val ready = config.pinEmergencyReady(now)
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

                when {
                    ready -> Text(
                        "Emergency unlock is ready — you can unlock without the PIN.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    pending -> Text(
                        "Forgot your PIN? Emergency unlock available in " +
                            "${formatCountdown(config.pinEmergencyUntilMillis - now)}. Keep Strict Mode on until then.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> TextButton(
                        onClick = { vm.startPinEmergency() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("Forgot PIN?") }
                }
            }
        },
        confirmButton = {
            if (ready) {
                TextButton(onClick = { vm.markUnlocked(); onUnlocked() }) { Text("Unlock") }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        if (vm.verifyPin(pin)) { vm.markUnlocked(); onUnlocked() } else error = true
                    }
                }, enabled = pin.length in 4..8) { Text("Unlock") }
            }
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

/**
 * COUNTDOWN gate: wait out a countdown that must stay on-screen; when it reaches zero we unlock for
 * this session. Closing it early cancels (re-locks). Friction-to-unlock, not a fixed commitment.
 */
@Composable
private fun CountdownUnlock(minutes: Int, vm: LockViewModel, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var remaining by remember { mutableStateOf(minutes * 60) }
    LaunchedEffect(Unit) {
        while (remaining > 0) { delay(1_000); remaining-- }
        vm.markUnlocked(); onUnlocked()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wait to unlock") },
        text = { Text("Unlocks in ${formatCountdown(remaining * 1000L)}. Keep this open.") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * TIMER gate is a commitment, not a wait-out-now timer: it can't be unlocked early. This dialog just
 * shows the live countdown to the deadline; the lock lifts on its own when it elapses.
 */
@Composable
private fun TimerCommitmentInfo(lockedUntilMillis: Long, onDismiss: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntilMillis) {
        while (now < lockedUntilMillis) { delay(1_000); now = System.currentTimeMillis() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Locked until the timer ends") },
        text = {
            Text("Strict Mode is committed and can't be unlocked early. It unlocks in " +
                "${formatCountdown(lockedUntilMillis - now)}.")
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
