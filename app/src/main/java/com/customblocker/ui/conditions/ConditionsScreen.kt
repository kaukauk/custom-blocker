package com.customblocker.ui.conditions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.engine.DayFlags

private val DAYS = listOf(
    "Mon" to DayFlags.MON, "Tue" to DayFlags.TUE, "Wed" to DayFlags.WED,
    "Thu" to DayFlags.THU, "Fri" to DayFlags.FRI, "Sat" to DayFlags.SAT, "Sun" to DayFlags.SUN,
)

private fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConditionsScreen(
    onBack: () -> Unit,
    onAddLocation: () -> Unit,
    initialExpanded: Int? = null,
    viewModel: ConditionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked by viewModel.editLocked.collectAsStateWithLifecycle()
    val mask = state.conditionsMask

    // Accordion: at most one condition expanded at a time (its bit, or null). Pre-expands the
    // condition the user tapped on the profile screen.
    var expanded by remember { mutableStateOf(initialExpanded) }
    // Time-window picker: 0 = closed, 1 = picking start, 2 = picking end.
    var pickerStage by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableIntStateOf(0) }
    var newSsid by remember { mutableStateOf("") }

    fun toggleExpand(bit: Int) { expanded = if (expanded == bit) null else bit }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Conditions") },
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
            item {
                Text(
                    "Blocking applies only when ALL switched-on conditions are met. Leave them all off " +
                        "to block all the time. Tap a condition to see what it does.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (locked) {
                item {
                    Text(
                        "Locked: this profile is active or Strict Mode is on, so conditions are frozen. " +
                            "You can only lower a usage/launch limit (to block sooner).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Schedule,
                    title = "Schedule",
                    description = "Block only on chosen days and time windows.",
                    enabled = mask and ConditionTypes.TIME != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.TIME, it) },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.TIME,
                    onExpand = { toggleExpand(ConditionTypes.TIME) },
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DAYS.forEach { (label, bit) ->
                            FilterChip(
                                selected = state.dayFlags and bit != 0,
                                onClick = { viewModel.toggleDay(bit) },
                                label = { Text(label) },
                                enabled = !locked,
                            )
                        }
                    }
                    state.intervals.forEach { interval ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${hhmm(interval.fromMinuteOfDay)} – ${hhmm(interval.toMinuteOfDay)}")
                            IconButton(onClick = { viewModel.removeInterval(interval.id) }, enabled = !locked) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove window")
                            }
                        }
                    }
                    OutlinedButton(onClick = { pickerStage = 1 }, enabled = !locked) { Text("Add time window") }
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Wifi,
                    title = "Wi-Fi network",
                    description = "Block only while connected to specific Wi-Fi networks.",
                    enabled = mask and ConditionTypes.WIFI != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.WIFI, it) },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.WIFI,
                    onExpand = { toggleExpand(ConditionTypes.WIFI) },
                ) {
                    state.wifi.forEach { net ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(net.ssid)
                            IconButton(onClick = { viewModel.removeWifi(net.id) }, enabled = !locked) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove network")
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newSsid,
                            onValueChange = { newSsid = it },
                            label = { Text("SSID") },
                            singleLine = true,
                            enabled = !locked,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { viewModel.addWifi(newSsid); newSsid = "" }, enabled = !locked) { Text("Add") }
                    }
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Place,
                    title = "Location",
                    description = "Block only at (or away from) chosen places.",
                    enabled = mask and ConditionTypes.LOCATION != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.LOCATION, it) },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.LOCATION,
                    onExpand = { toggleExpand(ConditionTypes.LOCATION) },
                ) {
                    state.geofences.forEach { geo ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "%.4f, %.4f · %dm%s".format(
                                    geo.latitude, geo.longitude, geo.radiusMeters.toInt(),
                                    if (geo.inverted) " (outside)" else "",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            IconButton(onClick = { viewModel.removeGeo(geo.id) }, enabled = !locked) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove location")
                            }
                        }
                    }
                    OutlinedButton(onClick = onAddLocation, enabled = !locked) { Text("Add location") }
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Timer,
                    title = "Usage time limit",
                    description = "Allow a set amount of use per hour or day, then block until it resets.",
                    enabled = mask and ConditionTypes.USAGE_LIMIT != 0,
                    onToggle = { viewModel.toggleUsage(it) },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.USAGE_LIMIT,
                    onExpand = { toggleExpand(ConditionTypes.USAGE_LIMIT) },
                ) {
                    LimitEditor(
                        value = state.usageMinutes ?: 15,
                        unitLabel = "minutes",
                        daily = state.usageDaily,
                        onValue = { viewModel.setUsageLimit(it, state.usageDaily) },
                        onPeriod = { viewModel.setUsageLimit(state.usageMinutes ?: 15, it) },
                        locked = locked,
                        isTime = true,
                    )
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.PhoneAndroid,
                    title = "Launch limit",
                    description = "Allow a set number of opens per hour or day, then block until it resets.",
                    enabled = mask and ConditionTypes.LAUNCH_COUNT != 0,
                    onToggle = { viewModel.toggleLaunch(it) },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.LAUNCH_COUNT,
                    onExpand = { toggleExpand(ConditionTypes.LAUNCH_COUNT) },
                ) {
                    LimitEditor(
                        value = state.launchCount ?: 5,
                        unitLabel = "launches",
                        daily = state.launchDaily,
                        onValue = { viewModel.setLaunchLimit(it, state.launchDaily) },
                        onPeriod = { viewModel.setLaunchLimit(state.launchCount ?: 5, it) },
                        locked = locked,
                    )
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Charging",
                    description = "Block only while the device is plugged in and charging.",
                    enabled = mask and ConditionTypes.CHARGER != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.CHARGER, it) },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.CHARGER,
                    onExpand = { toggleExpand(ConditionTypes.CHARGER) },
                    editor = null,
                )
            }
        }
    }

    if (pickerStage > 0) {
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickerStage = 0 },
            title = { Text(if (pickerStage == 1) "Start time" else "End time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = timeState.hour * 60 + timeState.minute
                    if (pickerStage == 1) {
                        pendingStart = minute
                        pickerStage = 2
                    } else {
                        viewModel.addTimeWindow(pendingStart, minute)
                        pickerStage = 0
                    }
                }) { Text(if (pickerStage == 1) "Next" else "Add") }
            },
            dismissButton = { TextButton(onClick = { pickerStage = 0 }) { Text("Cancel") } },
        )
    }
}

/**
 * One condition as an expandable card: icon + name + on/off switch in the header (tap the header to
 * expand). Expanding shows the description; when the condition is on, its [editor] is shown too.
 */
@Composable
private fun ConditionCard(
    icon: ImageVector,
    title: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    toggleEnabled: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    editor: (@Composable () -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onExpand() },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = toggleEnabled)
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (enabled && editor != null) editor()
                }
            }
        }
    }
}

@Composable
private fun LimitEditor(
    value: Int,
    unitLabel: String,
    daily: Boolean,
    onValue: (Int) -> Unit,
    onPeriod: (Boolean) -> Unit,
    locked: Boolean = false,
    isTime: Boolean = false,
) {
    // Locked: only accept a decrease (block sooner), never an increase.
    fun commit(n: Int) { if (n > 0 && (!locked || n < value)) onValue(n) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (isTime && daily) {
                WheelColumn("hours", value / 60, 0..23, locked) { h -> commit(h * 60 + value % 60) }
                WheelColumn("minutes", value % 60, 0..59, locked) { m -> commit((value / 60) * 60 + m) }
            } else if (isTime) {
                WheelColumn("minutes", value, 1..60, locked) { commit(it) }
            } else {
                WheelColumn(unitLabel, value, 1..100, locked) { commit(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = daily, onClick = { onPeriod(true) }, label = { Text("Per day") }, enabled = !locked)
            FilterChip(selected = !daily, onClick = { onPeriod(false) }, label = { Text("Per hour") }, enabled = !locked)
        }
    }
}

@Composable
private fun WheelColumn(label: String, value: Int, range: IntRange, locked: Boolean, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.customblocker.ui.components.NumberWheel(value = value, range = range, onValue = onValue, enabled = !locked)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
