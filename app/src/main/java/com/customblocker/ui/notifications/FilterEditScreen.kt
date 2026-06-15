package com.customblocker.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.customblocker.domain.engine.DayFlags
import com.customblocker.domain.engine.NotifActionType
import com.customblocker.domain.engine.NotifMatchTarget
import com.customblocker.domain.engine.NotifSample
import com.customblocker.domain.engine.NotificationPatternSuggester
import com.customblocker.ui.components.NumberWheel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterEditScreen(
    onBack: () -> Unit,
    viewModel: FilterEditViewModel = hiltViewModel(),
) {
    val loaded by viewModel.form.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val samples by viewModel.samples.collectAsStateWithLifecycle()

    val initial = loaded
    if (initial == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
        return
    }

    // Editable state, seeded once the form loads.
    var title by remember(initial) { mutableStateOf(initial.title) }
    var packages by remember(initial) { mutableStateOf(initial.packages) }
    var pattern by remember(initial) { mutableStateOf(initial.regexPattern) }
    var target by remember(initial) { mutableStateOf(NotifMatchTarget.entries.getOrElse(initial.regexTarget) { NotifMatchTarget.EITHER }) }
    var secondary by remember(initial) { mutableStateOf(initial.secondaryPattern) }
    var action by remember(initial) { mutableStateOf(NotifActionType.entries.getOrElse(initial.action) { NotifActionType.DISMISS }) }
    var batchMinutes by remember(initial) { mutableStateOf(initial.batchIntervalMinutes) }
    var releaseTimes by remember(initial) { mutableStateOf(initial.releaseTimes.sorted()) }
    var startMinute by remember(initial) { mutableStateOf(initial.startMinute) }
    var endMinute by remember(initial) { mutableStateOf(initial.endMinute) }
    var dayFlags by remember(initial) { mutableStateOf(initial.dayFlags) }

    var showAppPicker by remember { mutableStateOf(false) }
    var timePickerFor by remember { mutableStateOf<TimeTarget?>(null) }

    fun currentForm() = initial.copy(
        title = title,
        packages = packages,
        regexPattern = pattern,
        regexTarget = target.ordinal,
        secondaryPattern = secondary,
        action = action.ordinal,
        batchIntervalMinutes = batchMinutes,
        releaseTimes = releaseTimes,
        startMinute = startMinute,
        endMinute = endMinute,
        dayFlags = dayFlags,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New filter" else "Edit filter") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!viewModel.isNew) {
                        TextButton(onClick = { viewModel.delete(onBack) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { viewModel.save(currentForm(), onBack) },
                        enabled = packages.isNotEmpty() || true, // any-app allowed (empty set)
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // --- Apps ---
            SectionLabel("Apps")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (packages.isEmpty()) {
                    AssistChip(onClick = { showAppPicker = true }, label = { Text("Any app") })
                } else {
                    packages.forEach { pkg ->
                        InputChip(
                            selected = true,
                            onClick = { packages = packages - pkg },
                            label = { Text(viewModel.label(pkg)) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove") },
                        )
                    }
                }
                AssistChip(onClick = { showAppPicker = true }, label = { Text("Choose apps") })
            }
            Text(
                "Leave empty to match notifications from any app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Suggestions (mined from this app's recent notifications) ---
            val suggestions = remember(packages, samples, viewModel.focus) {
                packages.flatMap { pkg ->
                    NotificationPatternSuggester.suggest(
                        samples = samples,
                        packageName = pkg,
                        focus = viewModel.focus?.takeIf { it.packageName == pkg },
                    )
                }.distinctBy { it.target to it.phrase }
                    .sortedByDescending { it.matchCount }
                    .take(6)
            }
            if (suggestions.isNotEmpty()) {
                SectionLabel("Suggested patterns")
                Text(
                    "Tap one to block similar notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        AssistChip(
                            onClick = { pattern = s.regex; target = s.target },
                            label = { Text("\"${s.phrase}\" (${s.matchCount}×)") },
                        )
                    }
                }
            }

            // --- Matching ---
            SectionLabel("Match by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotifMatchTarget.entries.forEach { t ->
                    FilterChip(
                        selected = target == t,
                        onClick = { target = t },
                        label = { Text(targetLabel(t)) },
                    )
                }
            }
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text(if (target == NotifMatchTarget.BOTH) "Title pattern (regex)" else "Pattern (regex)") },
                placeholder = { Text("blank = all notifications") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (target == NotifMatchTarget.BOTH) {
                OutlinedTextField(
                    value = secondary,
                    onValueChange = { secondary = it },
                    label = { Text("Content pattern (regex)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PatternPreview(pattern, secondary, target, packages, samples)

            // --- Action ---
            SectionLabel("Action")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotifActionType.entries.forEach { a ->
                    FilterChip(
                        selected = action == a,
                        onClick = { action = a },
                        label = { Text(actionLabel(a)) },
                    )
                }
            }
            Text(
                actionHint(action),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (action) {
                NotifActionType.BATCH -> BatchInterval(batchMinutes) { batchMinutes = it }
                NotifActionType.DELAY -> ReleaseTimes(
                    times = releaseTimes,
                    onAdd = { timePickerFor = TimeTarget.RELEASE },
                    onRemove = { releaseTimes = releaseTimes - it },
                )
                else -> {}
            }

            // --- Schedule ---
            SectionLabel("Active window")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { timePickerFor = TimeTarget.START }, label = { Text("From ${hhmm(startMinute)}") })
                AssistChip(onClick = { timePickerFor = TimeTarget.END }, label = { Text("To ${hhmm(endMinute)}") })
            }
            Text(
                "Default is all day. The filter only acts on notifications posted inside this window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DAYS.forEach { (bit, label) ->
                    FilterChip(
                        selected = dayFlags and bit != 0,
                        onClick = { dayFlags = dayFlags xor bit },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = apps,
            selected = packages,
            onToggle = { pkg, add -> packages = if (add) packages + pkg else packages - pkg },
            onClose = { showAppPicker = false },
        )
    }

    timePickerFor?.let { which ->
        val seed = when (which) {
            TimeTarget.START -> startMinute
            TimeTarget.END -> endMinute
            TimeTarget.RELEASE -> 9 * 60
        }
        TimePickerDialog(
            initialMinute = seed,
            onConfirm = { minute ->
                when (which) {
                    TimeTarget.START -> startMinute = minute
                    TimeTarget.END -> endMinute = minute
                    TimeTarget.RELEASE -> if (minute !in releaseTimes) releaseTimes = (releaseTimes + minute).sorted()
                }
                timePickerFor = null
            },
            onDismiss = { timePickerFor = null },
        )
    }
}

private enum class TimeTarget { START, END, RELEASE }

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BatchInterval(minutes: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Every")
        NumberWheel(value = minutes / 60, range = 0..23, onValue = { onChange((it * 60 + minutes % 60).coerceAtLeast(1)) })
        Text("h")
        NumberWheel(value = minutes % 60, range = 0..59, onValue = { onChange(((minutes / 60) * 60 + it).coerceAtLeast(1)) })
        Text("m")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseTimes(times: List<Int>, onAdd: () -> Unit, onRemove: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Release captured notifications at:", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            times.forEach { t ->
                InputChip(
                    selected = true,
                    onClick = { onRemove(t) },
                    label = { Text(hhmm(t)) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove") },
                )
            }
            AssistChip(onClick = onAdd, label = { Text("Add time") })
        }
        if (times.isEmpty()) {
            Text(
                "No times set — notifications are held in the inbox only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternPreview(
    pattern: String,
    secondary: String,
    target: NotifMatchTarget,
    packages: Set<String>,
    samples: List<NotifSample>,
) {
    val pool = remember(packages, samples) {
        if (packages.isEmpty()) samples else samples.filter { it.packageName in packages }
    }
    val count = remember(pattern, secondary, target, pool) {
        if (pattern.isBlank()) pool.size
        else {
            val rx = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            val rx2 = runCatching { Regex(secondary, RegexOption.IGNORE_CASE) }.getOrNull()
            if (rx == null) -1
            else pool.count { s ->
                when (target) {
                    NotifMatchTarget.TITLE -> rx.containsMatchIn(s.title)
                    NotifMatchTarget.CONTENT -> rx.containsMatchIn(s.text)
                    NotifMatchTarget.EITHER -> rx.containsMatchIn(s.title) || rx.containsMatchIn(s.text)
                    NotifMatchTarget.BOTH -> rx2 != null && rx.containsMatchIn(s.title) && rx2.containsMatchIn(s.text)
                }
            }
        }
    }
    val text = when {
        count < 0 -> "⚠ Invalid regex"
        pool.isEmpty() -> "No recent samples to preview against"
        else -> "Matches $count of ${pool.size} recent notification(s)"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (count < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppPickerDialog(
    apps: List<com.customblocker.ui.InstalledApp>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Choose apps") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    items(visible.size) { i ->
                        val app = visible[i]
                        val isSel = app.packageName in selected
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(app.packageName, !isSel) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = isSel, onCheckedChange = { onToggle(app.packageName, it) })
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

private fun targetLabel(t: NotifMatchTarget) = when (t) {
    NotifMatchTarget.TITLE -> "Title"
    NotifMatchTarget.CONTENT -> "Content"
    NotifMatchTarget.EITHER -> "Either"
    NotifMatchTarget.BOTH -> "Both"
}

private fun actionLabel(a: NotifActionType) = when (a) {
    NotifActionType.DISMISS -> "Dismiss"
    NotifActionType.CATCH -> "Catch"
    NotifActionType.BATCH -> "Batch"
    NotifActionType.DELAY -> "Delay"
}

private fun actionHint(a: NotifActionType) = when (a) {
    NotifActionType.DISMISS -> "Remove the notification entirely."
    NotifActionType.CATCH -> "Hide it from the shade but keep it in the in-app inbox."
    NotifActionType.BATCH -> "Hold it and re-deliver it at fixed intervals from midnight."
    NotifActionType.DELAY -> "Hold it and re-deliver it at the times you set."
}

private val DAYS = listOf(
    DayFlags.MON to "M", DayFlags.TUE to "T", DayFlags.WED to "W", DayFlags.THU to "T",
    DayFlags.FRI to "F", DayFlags.SAT to "S", DayFlags.SUN to "S",
)

private fun hhmm(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
