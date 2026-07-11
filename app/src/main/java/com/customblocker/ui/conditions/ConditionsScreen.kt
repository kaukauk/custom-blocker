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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.data.db.GeoAddressEntity
import com.customblocker.domain.engine.ConditionTypes
import com.customblocker.domain.engine.DayFlags
import com.customblocker.ui.map.Geocoding

private val DAYS = listOf(
    "Mon" to DayFlags.MON, "Tue" to DayFlags.TUE, "Wed" to DayFlags.WED,
    "Thu" to DayFlags.THU, "Fri" to DayFlags.FRI, "Sat" to DayFlags.SAT, "Sun" to DayFlags.SUN,
)

private fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConditionsScreen(
    onBack: () -> Unit,
    onOpenMap: (Long?) -> Unit,
    initialExpanded: Int? = null,
    viewModel: ConditionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked by viewModel.editLocked.collectAsStateWithLifecycle()
    val mask = state.conditionsMask
    // Which conditions are currently on — used to allow only restrictiveness-increasing edits while
    // locked: a condition's enable Switch may only turn it OFF (removing an AND-gate is more
    // restrictive), never back ON.
    val timeOn = mask and ConditionTypes.TIME != 0
    val locationOn = mask and ConditionTypes.LOCATION != 0
    val usageOn = mask and ConditionTypes.USAGE_LIMIT != 0
    val launchOn = mask and ConditionTypes.LAUNCH_COUNT != 0

    // Accordion: at most one condition expanded at a time (its bit, or null). Pre-expands the
    // condition the user tapped on the profile screen.
    var expanded by remember { mutableStateOf(initialExpanded) }
    // Time-window picker: 0 = closed, 1 = picking start, 2 = picking end.
    var pickerStage by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableIntStateOf(0) }
    // Id of the window being edited, or null when adding a new one.
    var editingIntervalId by remember { mutableStateOf<Long?>(null) }

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
                    "This profile is active only when ALL switched-on conditions are met. Leave them all " +
                        "off to apply all the time.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (locked) {
                item {
                    Text(
                        "Strict Mode is on, so you can only make this profile stricter: add days, widen or " +
                            "add time windows, add places/Wi-Fi, and lower a usage/launch limit. You can't " +
                            "loosen it — no removing a condition, deleting a window/place, or raising a limit. " +
                            "Charging is fixed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Schedule,
                    title = "Schedule",
                    description = "This profile is active only on the chosen days and time windows.",
                    enabled = mask and ConditionTypes.TIME != 0,
                    onToggle = { on -> viewModel.setConditionEnabled(ConditionTypes.TIME, on); if (on) expanded = ConditionTypes.TIME },
                    // Locked: may turn the schedule OFF (more restrictive), not back on.
                    toggleEnabled = !locked || timeOn,
                    expanded = expanded == ConditionTypes.TIME,
                    onExpand = { toggleExpand(ConditionTypes.TIME) },
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DAYS.forEach { (label, bit) ->
                            val dayOn = state.dayFlags and bit != 0
                            FilterChip(
                                selected = dayOn,
                                onClick = { viewModel.toggleDay(bit) },
                                label = { Text(label) },
                                // Locked: may switch a day ON (widen), not off.
                                enabled = !locked || !dayOn,
                            )
                        }
                    }
                    state.intervals.forEach { interval ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tap the time to edit this window; pre-fills the picker with its values.
                            // Editing stays available while locked — the ViewModel permits only edits
                            // that extend the window.
                            Text(
                                "${hhmm(interval.fromMinuteOfDay)} – ${hhmm(interval.toMinuteOfDay)}",
                                Modifier
                                    .weight(1f)
                                    .clickable {
                                        editingIntervalId = interval.id
                                        pendingStart = interval.fromMinuteOfDay
                                        pickerStage = 1
                                    }
                                    .padding(vertical = 8.dp),
                            )
                            // Removing a window frees blocked time → disabled while locked.
                            IconButton(onClick = { viewModel.removeInterval(interval.id) }, enabled = !locked) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove window")
                            }
                        }
                    }
                    // Adding a window only adds blocked time → always allowed.
                    OutlinedButton(
                        onClick = { editingIntervalId = null; pickerStage = 1 },
                    ) { Text("Add time window") }
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Place,
                    title = "Location",
                    description = "This profile is active only at (or away from) chosen places.",
                    enabled = mask and ConditionTypes.LOCATION != 0,
                    onToggle = { on -> viewModel.setConditionEnabled(ConditionTypes.LOCATION, on); if (on) expanded = ConditionTypes.LOCATION },
                    // Locked: may turn Location OFF (more restrictive), not back on.
                    toggleEnabled = !locked || locationOn,
                    expanded = expanded == ConditionTypes.LOCATION,
                    onExpand = { toggleExpand(ConditionTypes.LOCATION) },
                ) {
                    state.geofences.forEach { geo ->
                        GeofenceRow(
                            geo = geo,
                            locked = locked,
                            onEdit = { onOpenMap(geo.id) },
                            onRemove = { viewModel.removeGeo(geo.id) },
                        )
                    }
                    // Adding a place widens where the profile applies → allowed. (This editor only
                    // shows once Location is on, so adding here never turns the condition on itself.)
                    OutlinedButton(onClick = { onOpenMap(null) }) { Text("Add location") }
                }
            }

            item {
                ConditionCard(
                    icon = Icons.Default.Timer,
                    title = "Usage time limit",
                    description = "Allow a set amount of use per hour or day — once it's used up this profile blocks until it resets.",
                    enabled = mask and ConditionTypes.USAGE_LIMIT != 0,
                    onToggle = { on -> viewModel.toggleUsage(on); if (on) expanded = ConditionTypes.USAGE_LIMIT },
                    // Locked: may turn the limit OFF (blocks unconditionally = stricter), not back on.
                    toggleEnabled = !locked || usageOn,
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
                    description = "Allow a set number of opens per hour or day — after that this profile blocks until it resets.",
                    enabled = mask and ConditionTypes.LAUNCH_COUNT != 0,
                    onToggle = { on -> viewModel.toggleLaunch(on); if (on) expanded = ConditionTypes.LAUNCH_COUNT },
                    // Locked: may turn the limit OFF (blocks unconditionally = stricter), not back on.
                    toggleEnabled = !locked || launchOn,
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
                    description = when {
                        // Off: explain it can go either way so the user knows the choice exists.
                        mask and ConditionTypes.CHARGER == 0 ->
                            "This profile can be active only while the device is charging, or only " +
                                "when it is not charging."
                        // On: match the actual chosen behaviour, no vagueness.
                        state.chargerInverted ->
                            "This profile is active only when the device is not charging."
                        else ->
                            "This profile is active only while the device is charging."
                    },
                    enabled = mask and ConditionTypes.CHARGER != 0,
                    onToggle = { on -> viewModel.setConditionEnabled(ConditionTypes.CHARGER, on); if (on) expanded = ConditionTypes.CHARGER },
                    toggleEnabled = !locked,
                    expanded = expanded == ConditionTypes.CHARGER,
                    onExpand = { toggleExpand(ConditionTypes.CHARGER) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !state.chargerInverted,
                            onClick = { viewModel.setChargerInverted(false) },
                            label = { Text("While charging") },
                            enabled = !locked,
                        )
                        FilterChip(
                            selected = state.chargerInverted,
                            onClick = { viewModel.setChargerInverted(true) },
                            label = { Text("While not charging") },
                            enabled = !locked,
                        )
                    }
                }
            }
        }
    }

    if (pickerStage > 0) {
        fun closePicker() { pickerStage = 0; editingIntervalId = null }
        val editing = state.intervals.find { it.id == editingIntervalId }
        // Pre-fill: start from the window's existing start, end from its existing end (or, for a fresh
        // second stage, just after the chosen start).
        val initMinute = if (pickerStage == 1) (editing?.fromMinuteOfDay ?: 0) else (editing?.toMinuteOfDay ?: pendingStart)
        // Re-create the picker state per stage/target so it adopts the right initial time.
        key(pickerStage, editingIntervalId) {
            val timeState = rememberTimePickerState(
                initialHour = initMinute / 60,
                initialMinute = initMinute % 60,
                is24Hour = true,
            )
            AlertDialog(
                onDismissRequest = { closePicker() },
                title = { Text(if (pickerStage == 1) "Start time" else "End time") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        val minute = timeState.hour * 60 + timeState.minute
                        if (pickerStage == 1) {
                            pendingStart = minute
                            pickerStage = 2
                        } else {
                            val id = editingIntervalId
                            if (id != null) viewModel.updateTimeWindow(id, pendingStart, minute)
                            else viewModel.addTimeWindow(pendingStart, minute)
                            closePicker()
                        }
                    }) { Text(if (pickerStage == 1) "Next" else if (editingIntervalId != null) "Save" else "Add") }
                },
                dismissButton = { TextButton(onClick = { closePicker() }) { Text("Cancel") } },
            )
        }
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
    // Locked: only accept a decrease (block sooner), never an increase. The wheels stay interactive
    // so the decrease is reachable; commit() drops any increase.
    fun commit(n: Int) { if (n > 0 && (!locked || n < value)) onValue(n) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (isTime && daily) {
                WheelColumn("hours", value / 60, 0..23) { h -> commit(h * 60 + value % 60) }
                WheelColumn("minutes", value % 60, 0..59) { m -> commit((value / 60) * 60 + m) }
            } else if (isTime) {
                WheelColumn("minutes", value, 1..60) { commit(it) }
            } else {
                WheelColumn(unitLabel, value, 1..100) { commit(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = daily, onClick = { onPeriod(true) }, label = { Text("Per day") }, enabled = !locked)
            FilterChip(selected = !daily, onClick = { onPeriod(false) }, label = { Text("Per hour") }, enabled = !locked)
        }
    }
}

@Composable
private fun WheelColumn(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.customblocker.ui.components.NumberWheel(value = value, range = range, onValue = onValue, enabled = true)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A saved location: reverse-geocoded name + radius + which side is blocked. Tap to edit on the map. */
@Composable
private fun GeofenceRow(geo: GeoAddressEntity, locked: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    // Prefer the stored label; otherwise reverse-geocode on the fly for display.
    val name by produceState(
        initialValue = geo.label?.takeIf { it.isNotBlank() },
        geo.id, geo.latitude, geo.longitude,
    ) {
        if (value.isNullOrBlank()) value = Geocoding.describe(context, geo.latitude, geo.longitude)
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !locked) { onEdit() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(
                name ?: "%.4f, %.4f".format(geo.latitude, geo.longitude),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${geo.radiusMeters.toInt()} m · blocked ${if (geo.inverted) "outside" else "inside"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, enabled = !locked) {
            Icon(Icons.Default.Delete, contentDescription = "Remove location")
        }
    }
}
