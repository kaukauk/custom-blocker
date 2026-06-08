package com.customblocker.ui.conditions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
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
    viewModel: ConditionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mask = state.conditionsMask

    // 0 = closed, 1 = picking start, 2 = picking end
    var pickerStage by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableIntStateOf(0) }
    var newSsid by remember { mutableStateOf("") }

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
                    "Blocking applies only when ALL enabled conditions are met. " +
                        "Leave all off to block all the time.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // --- TIME ---
            item {
                SectionHeader(
                    title = "Time",
                    enabled = mask and ConditionTypes.TIME != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.TIME, it) },
                )
            }
            if (mask and ConditionTypes.TIME != 0) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DAYS.forEach { (label, bit) ->
                            FilterChip(
                                selected = state.dayFlags and bit != 0,
                                onClick = { viewModel.toggleDay(bit) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                items(state.intervals, key = { it.id }) { interval ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${hhmm(interval.fromMinuteOfDay)} – ${hhmm(interval.toMinuteOfDay)}")
                        IconButton(onClick = { viewModel.removeInterval(interval.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove window")
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { pickerStage = 1 }) { Text("Add time window") }
                }
            }

            // --- WIFI ---
            item {
                SectionHeader(
                    title = "Wi-Fi network",
                    enabled = mask and ConditionTypes.WIFI != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.WIFI, it) },
                )
            }
            if (mask and ConditionTypes.WIFI != 0) {
                items(state.wifi, key = { it.id }) { net ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(net.ssid)
                        IconButton(onClick = { viewModel.removeWifi(net.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove network")
                        }
                    }
                }
                item {
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
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { viewModel.addWifi(newSsid); newSsid = "" }) { Text("Add") }
                    }
                }
            }

            // --- LOCATION ---
            item {
                SectionHeader(
                    title = "Location",
                    enabled = mask and ConditionTypes.LOCATION != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.LOCATION, it) },
                )
            }
            if (mask and ConditionTypes.LOCATION != 0) {
                items(state.geofences, key = { it.id }) { geo ->
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
                        IconButton(onClick = { viewModel.removeGeo(geo.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove location")
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onAddLocation) { Text("Add location") }
                }
            }

            // --- USAGE LIMIT ---
            item {
                SectionHeader(
                    title = "Usage time limit",
                    enabled = mask and ConditionTypes.USAGE_LIMIT != 0,
                    onToggle = { viewModel.toggleUsage(it) },
                )
            }
            if (mask and ConditionTypes.USAGE_LIMIT != 0) {
                item {
                    LimitEditor(
                        value = state.usageMinutes ?: 15,
                        unitLabel = "minutes",
                        daily = state.usageDaily,
                        onValue = { viewModel.setUsageLimit(it, state.usageDaily) },
                        onPeriod = { viewModel.setUsageLimit(state.usageMinutes ?: 15, it) },
                    )
                }
            }

            // --- LAUNCH COUNT ---
            item {
                SectionHeader(
                    title = "Launch count limit",
                    enabled = mask and ConditionTypes.LAUNCH_COUNT != 0,
                    onToggle = { viewModel.toggleLaunch(it) },
                )
            }
            if (mask and ConditionTypes.LAUNCH_COUNT != 0) {
                item {
                    LimitEditor(
                        value = state.launchCount ?: 5,
                        unitLabel = "launches",
                        daily = state.launchDaily,
                        onValue = { viewModel.setLaunchLimit(it, state.launchDaily) },
                        onPeriod = { viewModel.setLaunchLimit(state.launchCount ?: 5, it) },
                    )
                }
            }

            // --- CHARGER ---
            item {
                SectionHeader(
                    title = "Only when charging",
                    enabled = mask and ConditionTypes.CHARGER != 0,
                    onToggle = { viewModel.setConditionEnabled(ConditionTypes.CHARGER, it) },
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

@Composable
private fun LimitEditor(
    value: Int,
    unitLabel: String,
    daily: Boolean,
    onValue: (Int) -> Unit,
    onPeriod: (Boolean) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.filter(Char::isDigit).take(4)
                    text.toIntOrNull()?.let { n -> if (n > 0) onValue(n) }
                },
                label = { Text(unitLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = daily, onClick = { onPeriod(true) }, label = { Text("Per day") })
            FilterChip(selected = !daily, onClick = { onPeriod(false) }, label = { Text("Per hour") })
        }
    }
}

@Composable
private fun SectionHeader(title: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
