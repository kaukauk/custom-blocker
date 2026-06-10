package com.customblocker.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** (accessibilityEnabled, overlayGranted), refreshed on every ON_RESUME. */
@Composable
fun rememberPermissionState(): State<Pair<Boolean, Boolean>> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember {
        mutableStateOf(
            Permissions.isAccessibilityEnabled(context) to Permissions.canDrawOverlays(context),
        )
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = Permissions.isAccessibilityEnabled(context) to
                    Permissions.canDrawOverlays(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Notification-listener access state, refreshed on every ON_RESUME. */
@Composable
fun rememberNotificationAccessState(): State<Boolean> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(Permissions.isNotificationAccessGranted(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = Permissions.isNotificationAccessGranted(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Battery-optimization exemption state, refreshed on every ON_RESUME. */
@Composable
fun rememberBatteryExemptState(): State<Boolean> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = Permissions.isIgnoringBatteryOptimizations(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * A soft reliability nudge (not a hard requirement): exempting the app from battery optimization
 * keeps geofences, counters, and the detector alive under Doze. Hidden once granted.
 */
@Composable
fun ReliabilityBanner(modifier: Modifier = Modifier) {
    val batteryExempt by rememberBatteryExemptState()
    if (batteryExempt) return
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Improve reliability", style = MaterialTheme.typography.titleMedium)
            Text(
                "Allow the app to run without battery restrictions so blocking keeps working in the " +
                    "background (location, limits, and detection won't be paused by Doze).",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { Permissions.requestIgnoreBatteryOptimizations(context) }) {
                Text("Allow background activity")
            }
        }
    }
}

/**
 * A persuasive amber nudge (not a hard requirement): without notification access, blocked apps can
 * still buzz and badge. Hidden once granted. Distinct from the red [PermissionBanner] (which means
 * blocking can't run at all).
 */
@Composable
fun NotificationNudgeBanner(modifier: Modifier = Modifier) {
    val granted by rememberNotificationAccessState()
    if (granted) return
    val context = LocalContext.current
    val orange = Color(0xFFFB8C00)
    val onOrange = Color(0xFF2A1400)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = orange, contentColor = onOrange),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications are still getting through", style = MaterialTheme.typography.titleMedium)
            Text(
                "While a profile is actively blocking an app, its notifications can still buzz and pull " +
                    "you back in. Turn on notification access and we'll hold those — only while it's being " +
                    "blocked — then deliver them when the block lifts (like the top of the hour). Any other " +
                    "time, notifications come through as normal. So you're not constantly pinged, but never miss a thing.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { Permissions.openNotificationAccessSettings(context) },
                colors = ButtonDefaults.buttonColors(containerColor = onOrange, contentColor = orange),
            ) { Text("Turn on notification access") }
        }
    }
}

/** Shown on the home screen only while a required permission is missing. */
@Composable
fun PermissionBanner(
    accessibilityOn: Boolean,
    overlayOn: Boolean,
    modifier: Modifier = Modifier,
) {
    if (accessibilityOn && overlayOn) return
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Permissions needed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Blocking can't run until these are granted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!accessibilityOn) {
                    Button(onClick = { Permissions.openAccessibilitySettings(context) }) {
                        Text("Accessibility")
                    }
                }
                if (!overlayOn) {
                    Button(onClick = { Permissions.openOverlaySettings(context) }) {
                        Text("Overlay")
                    }
                }
            }
        }
    }
}
