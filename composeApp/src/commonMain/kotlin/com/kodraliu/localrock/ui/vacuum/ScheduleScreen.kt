package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kodraliu.localrock.shared.model.Scene
import com.kodraliu.localrock.shared.vacuum.RoutineSchedule
import com.kodraliu.localrock.shared.vacuum.routineSchedules
import com.kodraliu.localrock.ui.AppColors
import com.kodraliu.localrock.ui.adaptive.CenteredContent
import com.kodraliu.localrock.ui.rememberUse24HourClock
import kotlinx.datetime.TimeZone

/** What a schedule runs: one of the robot's routines, or a whole-home clean on the robot's own timer. */
private sealed interface ScheduleTask {
    data class Routine(val scene: Scene) : ScheduleTask
    data object FullClean : ScheduleTask
}

/** A schedule being added or edited. [editing] is the routine schedule it replaces, if any. */
private data class ScheduleDraft(
    val hour: Int = 8,
    val minute: Int = 0,
    val days: Set<Int> = (0..6).toSet(),
    val task: ScheduleTask? = null,
    val editing: Pair<Scene, RoutineSchedule>? = null,
)

/**
 * Schedules, as the official app has them: routine schedules (a time and days on a routine, run by
 * the scheduler on the Pi) and the robot's own whole-home timers. + adds either kind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    viewModel: VacuumViewModel,
    onBack: () -> Unit,
) {
    val repository = viewModel.repository
    val timers by repository.timers.collectAsState()
    val routinesState by viewModel.routines.collectAsState()
    val routines = (routinesState as? RoutinesState.Loaded)?.routines ?: emptyList()
    var draft by remember { mutableStateOf<ScheduleDraft?>(null) }
    val use24h = rememberUse24HourClock()

    LaunchedEffect(Unit) {
        viewModel.loadRoutines()
        runCatching { repository.fetchTimers() }
    }

    // Every routine schedule, with the routine it belongs to, earliest time first.
    val routineRows = routines.flatMap { scene -> routineSchedules(scene.param).map { scene to it } }
        .sortedWith(compareBy({ it.second.hour }, { it.second.minute }))

    fun saveSchedules(scene: Scene, schedules: List<RoutineSchedule>, message: String) =
        viewModel.saveRoutineSchedules(scene, schedules, message)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Schedules") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { draft = ScheduleDraft(task = routines.firstOrNull()?.let { ScheduleTask.Routine(it) } ?: ScheduleTask.FullClean) },
                containerColor = AppColors.Brand,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add schedule") },
            )
        },
    ) { padding ->
        CenteredContent {
            if (routineRows.isEmpty() && timers.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Schedule, null, Modifier.size(48.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No schedules yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add a schedule to run a routine or a full clean at set times.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = padding) {
                    if (routineRows.isNotEmpty()) {
                        item { SectionTitle("Routines") }
                        items(routineRows, key = { "r${it.first.id}-${it.second.id}-${it.second.hour}-${it.second.minute}" }) { (scene, schedule) ->
                            val all = routineSchedules(scene.param)
                            ScheduleRow(
                                time = formatClock(schedule.hour, schedule.minute, use24h),
                                days = formatWeekdays(schedule.days),
                                task = "Routine: ${scene.name}",
                                icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, Modifier.size(22.dp), tint = AppColors.Brand) },
                                enabled = schedule.enabled,
                                onClick = {
                                    draft = ScheduleDraft(schedule.hour, schedule.minute, schedule.days, ScheduleTask.Routine(scene), scene to schedule)
                                },
                                onToggle = { on ->
                                    saveSchedules(scene, all.map { if (it == schedule) it.copy(enabled = on) else it }, if (on) "Schedule on" else "Schedule off")
                                },
                                onDelete = { saveSchedules(scene, all.filter { it != schedule }, "Schedule deleted") },
                            )
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                    if (timers.isNotEmpty()) {
                        item { SectionTitle("Full clean (robot timers)") }
                        items(timers, key = { "t${it.id}" }) { timer ->
                            ScheduleRow(
                                time = formatClock(timer.hour, timer.minute, use24h),
                                days = formatWeekdays(timer.weekdays),
                                task = "Full clean",
                                icon = { Icon(Icons.Default.Home, null, Modifier.size(22.dp), tint = AppColors.Brand) },
                                enabled = timer.enabled,
                                onClick = null,
                                onToggle = { viewModel.run { it.toggleTimer(timer) } },
                                onDelete = { viewModel.run { it.deleteTimer(timer) } },
                            )
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    draft?.let { current ->
        ScheduleDialog(
            initial = current,
            routines = routines,
            onDismiss = { draft = null },
            onSave = { saved ->
                draft = null
                when (val task = saved.task) {
                    is ScheduleTask.Routine -> {
                        // A routine edited into a different routine moves the schedule from the old one.
                        saved.editing?.let { (oldScene, old) ->
                            if (oldScene.id != task.scene.id) {
                                saveSchedules(oldScene, routineSchedules(oldScene.param).filter { it != old }, "Schedule moved")
                            }
                        }
                        val existing = routineSchedules(task.scene.param)
                        val base = saved.editing?.takeIf { it.first.id == task.scene.id }?.second
                        val schedule = RoutineSchedule(
                            id = base?.id ?: com.kodraliu.localrock.shared.http.currentEpochSeconds(),
                            hour = saved.hour,
                            minute = saved.minute,
                            days = saved.days,
                            enabled = base?.enabled ?: true,
                            timeZoneId = base?.timeZoneId ?: TimeZone.currentSystemDefault().id,
                        )
                        val updated = if (base != null) existing.map { if (it == base) schedule else it } else existing + schedule
                        saveSchedules(task.scene, updated, if (base != null) "Schedule saved" else "Schedule added")
                    }
                    ScheduleTask.FullClean -> viewModel.run("Schedule added") { it.createTimer(saved.hour, saved.minute, saved.days) }
                    null -> Unit
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = AppColors.Brand,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ScheduleRow(
    time: String,
    days: String,
    task: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(onClick = { onClick?.invoke() }, enabled = onClick != null, color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppColors.Brand.copy(alpha = if (enabled) 0.14f else 0.06f),
                modifier = Modifier.size(44.dp),
            ) { Box(contentAlignment = Alignment.Center) { icon() } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    time,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("$days · $task", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete schedule", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Brand),
            )
        }
    }
}

/** Time, repeat days and task: a routine (picked from the list) or a full clean. */
@Composable
private fun ScheduleDialog(
    initial: ScheduleDraft,
    routines: List<Scene>,
    onDismiss: () -> Unit,
    onSave: (ScheduleDraft) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var days by remember { mutableStateOf(initial.days) }
    var task by remember { mutableStateOf(initial.task) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.editing != null) "Edit schedule" else "New schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Start", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ClockStepper(hour, minute, onHourChange = { hour = it }, onMinuteChange = { minute = it })

                Text("Repeat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Every day" to (0..6).toSet(), "Weekdays" to setOf(1, 2, 3, 4, 5), "Weekends" to setOf(0, 6)).forEach { (label, set) ->
                        Surface(
                            onClick = { days = set },
                            shape = CircleShape,
                            color = if (days == set) AppColors.Brand else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (days == set) Color.White else MaterialTheme.colorScheme.onSurface,
                        ) { Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEachIndexed { index, label ->
                        val selected = index in days
                        Surface(
                            onClick = { days = if (selected) days - index else days + index },
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            shape = CircleShape,
                            color = if (selected) AppColors.Brand else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Text("Task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                routines.forEach { scene ->
                    TaskOption("Routine: ${scene.name}", (task as? ScheduleTask.Routine)?.scene?.id == scene.id) { task = ScheduleTask.Routine(scene) }
                }
                if (initial.editing == null) {
                    TaskOption("Full clean (robot timer)", task == ScheduleTask.FullClean) { task = ScheduleTask.FullClean }
                }
                if (routines.isEmpty()) {
                    Text(
                        "Make a routine in the Routine tab to schedule rooms, modes and several steps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(hour = hour, minute = minute, days = days, task = task)) },
                enabled = days.isNotEmpty() && task != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TaskOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun NumberStepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StepButton("+") { onChange(steppedWithWrap(value, step, min, max)) }
        Text(value.toString().padStart(2, '0'), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StepButton("−") { onChange(steppedWithWrap(value, -step, min, max)) }
    }
}

/**
 * [value] moved by [delta] within [min]..[max], wrapping around the ends of the range like a clock.
 * Minutes in steps of 15 go 45, 30, 15, 0 and back to 45, not 0 then 59, 44, 29, 14.
 */
internal fun steppedWithWrap(value: Int, delta: Int, min: Int, max: Int): Int {
    val size = max - min + 1
    return ((value - min + delta) % size + size) % size + min
}

private fun formatWeekdays(days: Set<Int>): String {
    if (days.isEmpty() || days.size == 7) return "Every day"
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays"
    if (days == setOf(0, 6)) return "Weekends"
    return days.sorted().joinToString(", ") { names.getOrElse(it) { "$it" } }
}

