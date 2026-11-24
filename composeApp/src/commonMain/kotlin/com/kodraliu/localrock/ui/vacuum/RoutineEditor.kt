package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kodraliu.localrock.shared.vacuum.MopRoute
import com.kodraliu.localrock.shared.vacuum.RoutineDraft
import com.kodraliu.localrock.shared.vacuum.RoutineTask
import com.kodraliu.localrock.shared.vacuum.RoutineTaskKind
import com.kodraliu.localrock.shared.vacuum.VacuumFanPower
import com.kodraliu.localrock.shared.vacuum.WaterBoxMode
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap
import com.kodraliu.localrock.shared.vacuum.routineScheduleLabel
import com.kodraliu.localrock.ui.AppColors

private val SUCTION = listOf(
    VacuumFanPower.QUIET to "Quiet", VacuumFanPower.BALANCED to "Balanced", VacuumFanPower.TURBO to "Turbo",
    VacuumFanPower.MAX to "Max", 108 to "Max+",
)
private val ROUTES = listOf(MopRoute.FAST to "Fast", MopRoute.STANDARD to "Standard", MopRoute.DEEP to "Deep", MopRoute.DEEP_PLUS to "Deep+")

/** Water levels: robots with a water slider take 225/235/245, the others 201/202/203. */
private fun waterLevels(slide: Boolean) =
    if (slide) listOf(225 to "Low", WaterBoxMode.SLIDE_MEDIUM to "Medium", 245 to "High")
    else listOf(WaterBoxMode.LOW to "Low", WaterBoxMode.MEDIUM to "Medium", WaterBoxMode.HIGH to "High")

private enum class TaskMode(val label: String) { VAC_MOP("Vac & Mop"), VACUUM("Vacuum"), MOP("Mop") }

private val RoutineTask.mode: TaskMode
    get() = when {
        !mopping -> TaskMode.VACUUM
        !vacuuming -> TaskMode.MOP
        else -> TaskMode.VAC_MOP
    }

/** One line describing a task's settings, e.g. "Vac & Mop · Quiet · Medium water · Deep+ · ×2". */
private fun settingsSummary(task: RoutineTask, slide: Boolean): String = buildList {
    add(task.mode.label)
    if (task.vacuuming) add(SUCTION.firstOrNull { it.first == task.fanPower }?.second ?: "Suction ${task.fanPower}")
    if (task.mopping) {
        add((waterLevels(slide).firstOrNull { it.first == task.waterMode }?.second ?: "Custom") + " water")
        add(ROUTES.firstOrNull { it.first == task.mopRoute }?.second ?: "Route ${task.mopRoute}")
    }
    if (task.passes > 1) add("×${task.passes}")
}.joinToString(" · ")

internal fun taskTitle(task: RoutineTask, roomName: (Int) -> String): String = when (task.kind) {
    RoutineTaskKind.FULL -> "Full clean"
    RoutineTaskKind.ROOMS -> task.roomIds.joinToString(" → ") { roomName(it) }.ifEmpty { "Rooms" }
    RoutineTaskKind.OTHER -> "Zone clean (kept as it is)"
}

/**
 * The routine editor, full screen: a name, the tasks in the order they run, and Add task. Each task
 * is a full clean or rooms picked on the map in order, with its own cleaning settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutineEditorDialog(
    initialName: String,
    initialDraft: RoutineDraft,
    isNew: Boolean,
    map: ParsedMap?,
    currentMapFlag: Int,
    supportsWaterSlide: Boolean,
    roomName: (Int) -> String,
    onSave: (name: String, draft: RoutineDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var tasks by remember { mutableStateOf(initialDraft.tasks) }
    // The task open in the settings sheet, and where it goes: an index to replace, or -1 to append.
    var editing by remember { mutableStateOf<Pair<Int, RoutineTask>?>(null) }
    var choosingKind by remember { mutableStateOf(false) }
    val schedule = remember(initialDraft) { routineScheduleLabel(initialDraft) }
    val canSave = name.isNotBlank() && tasks.isNotEmpty()
    // Back, the X and a tap outside all ask first once something has changed, so a routine isn't lost by accident.
    val dirty = name != initialName || tasks != initialDraft.tasks
    var confirmingDiscard by remember { mutableStateOf(false) }
    val attemptClose = { if (dirty) confirmingDiscard = true else onDismiss() }

    Dialog(onDismissRequest = attemptClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (isNew) "New routine" else "Edit routine", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = attemptClose) { Icon(Icons.Default.Close, contentDescription = "Close") } },
                    actions = {
                        TextButton(onClick = { onSave(name.trim(), initialDraft.copy(tasks = tasks)) }, enabled = canSave) {
                            Text("Save", fontWeight = FontWeight.SemiBold)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Routine name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (schedule != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("Scheduled daily at $schedule. Saving keeps the schedule.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Text("Tasks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                if (tasks.isEmpty()) {
                    Text("Add a task: a full clean, or rooms in the order you pick them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tasks.forEachIndexed { index, task ->
                    TaskCard(
                        number = index + 1,
                        title = taskTitle(task, roomName),
                        summary = if (task.kind == RoutineTaskKind.OTHER) "Edit zones in the official app" else settingsSummary(task, supportsWaterSlide),
                        editable = task.kind != RoutineTaskKind.OTHER,
                        canMoveUp = index > 0,
                        canMoveDown = index < tasks.lastIndex,
                        onEdit = { editing = index to task },
                        onMoveUp = { tasks = tasks.toMutableList().apply { add(index - 1, removeAt(index)) } },
                        onMoveDown = { tasks = tasks.toMutableList().apply { add(index + 1, removeAt(index)) } },
                        onDelete = { tasks = tasks.filterIndexed { i, _ -> i != index } },
                    )
                }
                OutlinedButton(onClick = { choosingKind = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add task")
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }

        if (confirmingDiscard) {
            AlertDialog(
                onDismissRequest = { confirmingDiscard = false },
                title = { Text("Discard changes?") },
                text = { Text(if (isNew) "This routine hasn't been saved." else "Your changes to this routine haven't been saved.") },
                confirmButton = { TextButton(onClick = { confirmingDiscard = false; onDismiss() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text("Keep editing") } },
            )
        }

        if (choosingKind) {
            val defaultWater = if (supportsWaterSlide) WaterBoxMode.SLIDE_MEDIUM else WaterBoxMode.MEDIUM
            AlertDialog(
                onDismissRequest = { choosingKind = false },
                title = { Text("Add a task") },
                text = { Text("Clean the whole home, or choose rooms on the map.") },
                confirmButton = {
                    TextButton(onClick = {
                        choosingKind = false
                        editing = -1 to RoutineTask(RoutineTaskKind.ROOMS, waterMode = defaultWater, mapFlag = currentMapFlag)
                    }) { Text("Rooms") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        choosingKind = false
                        editing = -1 to RoutineTask(RoutineTaskKind.FULL, waterMode = defaultWater)
                    }) { Text("Full clean") }
                },
            )
        }

        editing?.let { (index, task) ->
            TaskSettingsSheet(
                initial = task,
                map = map,
                supportsWaterSlide = supportsWaterSlide,
                roomName = roomName,
                onDone = { updated ->
                    tasks = if (index < 0) tasks + updated else tasks.toMutableList().apply { set(index, updated) }
                    editing = null
                },
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun TaskCard(
    number: Int,
    title: String,
    summary: String,
    editable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onEdit,
        enabled = editable,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = AppColors.Brand, contentColor = Color.White, modifier = Modifier.size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
            }
            Column {
                if (editable) IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Edit, contentDescription = "Edit task", modifier = Modifier.size(20.dp)) }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, contentDescription = "Remove task", modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

/** One task's rooms (picked on the map, in order) and its cleaning settings. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TaskSettingsSheet(
    initial: RoutineTask,
    map: ParsedMap?,
    supportsWaterSlide: Boolean,
    roomName: (Int) -> String,
    onDone: (RoutineTask) -> Unit,
    onDismiss: () -> Unit,
) {
    var task by remember { mutableStateOf(initial) }
    val water = waterLevels(supportsWaterSlide)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (task.kind == RoutineTaskKind.FULL) "Full clean" else "Rooms", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            if (task.kind == RoutineTaskKind.ROOMS) {
                if (map != null) {
                    Box(
                        Modifier.fillMaxWidth().height(300.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp)),
                    ) {
                        VacuumMapImage(
                            map = map,
                            selectedRoomIds = task.roomIds,
                            onRoomTap = { id ->
                                task = task.copy(roomIds = if (id in task.roomIds) task.roomIds - id else task.roomIds + id)
                            },
                            onObstacleTap = {},
                            is3D = false,
                            yawDegrees = 0f,
                            onYawBy = {},
                            tiltDegrees = 0f,
                            onTiltBy = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    if (task.roomIds.isEmpty()) "Tap rooms on the map in the order to clean them."
                    else task.roomIds.mapIndexed { i, id -> "${i + 1}. ${roomName(id)}" }.joinToString("   "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (task.roomIds.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }

            SettingRow("Mode") {
                TaskMode.entries.forEach { mode ->
                    Choice(mode.label, task.mode == mode) {
                        task = when (mode) {
                            TaskMode.VACUUM -> task.copy(waterMode = WaterBoxMode.OFF, fanPower = task.fanPower.takeUnless { it == VacuumFanPower.OFF } ?: VacuumFanPower.BALANCED)
                            TaskMode.MOP -> task.copy(fanPower = VacuumFanPower.OFF, waterMode = task.waterMode.takeUnless { it == WaterBoxMode.OFF } ?: water[1].first)
                            TaskMode.VAC_MOP -> task.copy(
                                fanPower = task.fanPower.takeUnless { it == VacuumFanPower.OFF } ?: VacuumFanPower.BALANCED,
                                waterMode = task.waterMode.takeUnless { it == WaterBoxMode.OFF } ?: water[1].first,
                            )
                        }
                    }
                }
            }
            if (task.vacuuming) {
                SettingRow("Suction") { SUCTION.forEach { (v, l) -> Choice(l, task.fanPower == v) { task = task.copy(fanPower = v) } } }
            }
            if (task.mopping) {
                SettingRow("Water flow") { water.forEach { (v, l) -> Choice(l, task.waterMode == v) { task = task.copy(waterMode = v) } } }
                SettingRow("Mop route") { ROUTES.forEach { (v, l) -> Choice(l, task.mopRoute == v) { task = task.copy(mopRoute = v) } } }
            }
            SettingRow("Cleaning passes") { listOf(1, 2).forEach { n -> Choice("${n}×", task.passes == n) { task = task.copy(passes = n) } } }

            Button(
                onClick = { onDone(task) },
                enabled = task.kind != RoutineTaskKind.ROOMS || task.roomIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppColors.Brand,
            selectedLabelColor = Color.White,
        ),
    )
}
