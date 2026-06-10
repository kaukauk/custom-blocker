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
 * A strongly-suggested reliability prompt: without a battery-optimization exemption, Android can
 * pause the app in the background, so blocking quietly stops working. Hidden once granted.
 */
@Composable
fun ReliabilityBanner(modifier: Modifier = Modifier) {
    val batteryExempt by rememberBatteryExemptState()
    if (batteryExempt) return
    val context = LocalContext.current
    val blue = Color(0xFF1565C0)
    val onBlue = Color(0xFFE9F1FF)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = blue, contentColor = onBlue),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Don't let blocking get paused", style = MaterialTheme.typography.titleMedium)
            Text(
                "To save power, Android can pause this app in the background — and when it does, a " +
                    "blocked app can slip through and your limits and schedules stop working. Allowing " +
                    "background activity keeps blocking reliable. It barely touches your battery: the app " +
                    "only checks which app is open, it does no heavy work.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { Permissions.requestIgnoreBatteryOptimizations(context) },
                colors = ButtonDefaults.buttonColors(containerColor = onBlue, contentColor = blue),
            ) { Text("Allow background activity") }
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
                    "blocked — then deliver them once the block lifts. Any other time, notifications come " +
                    "through as normal. So you're not constantly pinged, but never miss a thing.",
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
