package com.customblocker.ui.strict

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.customblocker.data.prefs.StrictModeStore
import com.customblocker.ui.theme.CustomBlockerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Earn unlock" window for the Strict Mode TIMER gate. The user must keep this screen in the
 * foreground and full-screen: a mandatory wait elapses first (no bank), then a time bank accrues
 * 1:1 up to a max. Tapping "Start unlock time" grants that many seconds of blocking-bypass.
 *
 * Anti-gaming: declared non-resizable (no split-screen) in the manifest; if the system still puts us
 * in multi-window we stop accruing; and leaving the screen (it stops being RESUMED) resets progress
 * to zero, so the time must be spent actively staring at this window.
 */
@AndroidEntryPoint
class EarnUnlockActivity : ComponentActivity() {

    @Inject lateinit var store: StrictModeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CustomBlockerTheme {
                EarnUnlockScreen(
                    waitSecondsProvider = { store.config.first().earnWaitMinutes * 60 },
                    maxSecondsProvider = { store.config.first().earnMaxMinutes * 60 },
                    isMultiWindow = { isInMultiWindowMode },
                    onStart = { bankSeconds ->
                        lifecycleScope.launch {
                            store.startEarnedUnlock(bankSeconds, System.currentTimeMillis())
                            finish()
                        }
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
private fun EarnUnlockScreen(
    waitSecondsProvider: suspend () -> Int,
    maxSecondsProvider: suspend () -> Int,
    isMultiWindow: () -> Boolean,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    var waitSec by remember { mutableStateOf(300) }
    var maxSec by remember { mutableStateOf(300) }
    var elapsed by remember { mutableStateOf(0) }
    var split by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    // Run only while RESUMED. Re-entering RESUMED resets elapsed → leaving the window loses progress.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        waitSec = waitSecondsProvider()
        maxSec = maxSecondsProvider()
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            elapsed = 0
            while (true) {
                delay(1_000)
                split = isMultiWindow()
                if (split) elapsed = 0 else elapsed++   // split-screen earns nothing
            }
        }
    }

    val bank = (elapsed - waitSec).coerceIn(0, maxSec)
    val waiting = elapsed < waitSec

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Earn unlock time", style = MaterialTheme.typography.headlineSmall)
            when {
                split -> Text(
                    "Full screen only — split screen pauses and resets your progress. " +
                        "Return to full screen to keep earning.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error,
                )
                waiting -> {
                    Text("Keep this screen open.", textAlign = TextAlign.Center)
                    Text(formatCountdown((waitSec - elapsed) * 1000L),
                        style = MaterialTheme.typography.displaySmall)
                    Text("until you start earning unlock time.", textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Text("Unlock time earned", textAlign = TextAlign.Center)
                    Text(formatCountdown(bank * 1000L), style = MaterialTheme.typography.displaySmall)
                    Text(
                        if (bank >= maxSec) "Maxed out — start whenever you like."
                        else "Keep waiting to earn more (max ${formatCountdown(maxSec * 1000L)}).",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(onClick = { onStart(bank) }, enabled = bank > 0 && !split) {
                Text("Start unlock time (${formatCountdown(bank * 1000L)})")
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
