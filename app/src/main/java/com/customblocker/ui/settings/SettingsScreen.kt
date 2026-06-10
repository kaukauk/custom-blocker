package com.customblocker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.data.prefs.ThemeMode
import com.customblocker.service.admin.BlockerDeviceAdminReceiver
import com.customblocker.ui.onboarding.Permissions
import com.customblocker.ui.onboarding.rememberBatteryExemptState
import com.customblocker.ui.onboarding.rememberNotificationAccessState
import com.customblocker.ui.onboarding.rememberPermissionState
import com.customblocker.ui.strict.rememberDeviceAdminActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // --- Start of day ---
            Section("Start of day") {
                Text(
                    "When per-day usage and launch limits reset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.setDayStartHour((settings.dayStartHour + 23) % 24) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Earlier")
                    }
                    Text(formatHour(settings.dayStartHour), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { viewModel.setDayStartHour((settings.dayStartHour + 1) % 24) }) {
                        Icon(Icons.Default.Add, contentDescription = "Later")
                    }
                }
            }

            HorizontalDivider()

            // --- Appearance ---
            Section("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label()) },
                        )
                    }
                }
            }

            HorizontalDivider()

            // --- Permissions ---
            val (accessibilityOn, overlayOn) = rememberPermissionState().value
            val notificationOn by rememberNotificationAccessState()
            val batteryExempt by rememberBatteryExemptState()
            val deviceAdminActive by rememberDeviceAdminActive()
            Section("Permissions") {
                Text(
                    "Tap any permission to change it in system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionRow(
                    label = "Accessibility",
                    granted = accessibilityOn,
                    onClick = { Permissions.openAccessibilitySettings(context) },
                )
                PermissionRow(
                    label = "Display over other apps",
                    granted = overlayOn,
                    onClick = { Permissions.openOverlaySettings(context) },
                )
                PermissionRow(
                    label = "Notification access",
                    granted = notificationOn,
                    onClick = { Permissions.openNotificationAccessSettings(context) },
                )
                PermissionRow(
                    label = "Run without battery limits",
                    granted = batteryExempt,
                    onClick = { Permissions.openBatteryOptimizationSettings(context) },
                )
                PermissionRow(
                    label = "Device admin",
                    subtitle = "Uninstall protection · Strict Mode level 2 and up",
                    granted = deviceAdminActive,
                    onClick = { context.startActivity(BlockerDeviceAdminReceiver.activationIntent(context)) },
                )
            }
        }
    }
}

/**
 * One permission, always tappable (so the user can turn it on OR off in system settings). Green
 * check when granted, red outline when not. [subtitle] is optional extra context.
 */
@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

private fun ThemeMode.label() = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun formatHour(hour: Int): String {
    val period = if (hour < 12) "AM" else "PM"
    val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
    return "$h12:00 $period"
}
