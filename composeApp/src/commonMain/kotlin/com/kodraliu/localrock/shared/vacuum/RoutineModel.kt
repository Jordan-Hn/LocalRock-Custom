package com.kodraliu.localrock.shared.vacuum

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A routine as the editor sees it: an ordered list of tasks. It is read from and written back to
 * the official app's scene JSON, so the server runs it exactly like a routine made in that app.
 */
data class RoutineDraft(
    val tasks: List<RoutineTask>,
    /** The scene's own time triggers (its schedule), kept as they were so an edit never drops them. */
    val triggers: JsonArray = JsonArray(emptyList()),
    val tagId: String = "1002.0",
)

enum class RoutineTaskKind { FULL, ROOMS, OTHER }

data class RoutineTask(
    val kind: RoutineTaskKind,
    /** Room (segment) ids in cleaning order; empty for a full clean. */
    val roomIds: List<Int> = emptyList(),
    val fanPower: Int = VacuumFanPower.BALANCED,
    val waterMode: Int = WaterBoxMode.OFF,
    val mopRoute: Int = MopRoute.STANDARD,
    val passes: Int = 1,
    /** The map the rooms belong to. */
    val mapFlag: Int = 0,
    /** A step this editor can't change (a zone clean, say), carried through untouched. */
    val raw: JsonObject? = null,
) {
    val mopping: Boolean get() = waterMode != WaterBoxMode.OFF
    val vacuuming: Boolean get() = fanPower != VacuumFanPower.OFF
}

private val SceneJson = Json { ignoreUnknownKeys = true }

/** A param value may arrive as an object or as the JSON string the official app stores. */
private fun JsonElement?.asObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> contentOrNull?.let { runCatching { SceneJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    else -> null
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Reads a scene's param into a draft. Anything it doesn't understand is kept as an OTHER task. */
fun parseRoutine(param: JsonElement?): RoutineDraft {
    val root = param.asObject() ?: return RoutineDraft(emptyList())
    val triggers = root["triggers"] as? JsonArray ?: JsonArray(emptyList())
    val tagId = (root["tagId"] as? JsonPrimitive)?.contentOrNull ?: "1002.0"
    val items = root["action"]?.jsonObject?.get("items") as? JsonArray ?: JsonArray(emptyList())
    val tasks = items.mapNotNull { item ->
        val itemObj = item as? JsonObject ?: return@mapNotNull null
        val inner = itemObj["param"].asObject()
        val method = (inner?.get("method") as? JsonPrimitive)?.contentOrNull
        when (method) {
            "do_scenes_app_start" -> {
                val entry = (inner["params"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return@mapNotNull RoutineTask(RoutineTaskKind.OTHER, raw = itemObj)
                RoutineTask(
                    kind = RoutineTaskKind.FULL,
                    fanPower = entry.int("fan_power") ?: VacuumFanPower.BALANCED,
                    waterMode = entry.int("water_box_mode") ?: WaterBoxMode.OFF,
                    mopRoute = entry.int("mop_mode") ?: MopRoute.STANDARD,
                    passes = entry.int("repeat") ?: 1,
                )
            }
            "do_scenes_segments" -> {
                val entry = ((inner["params"] as? JsonObject)?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return@mapNotNull RoutineTask(RoutineTaskKind.OTHER, raw = itemObj)
                val rooms = (entry["segs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.int("sid") } ?: emptyList()
                RoutineTask(
                    kind = RoutineTaskKind.ROOMS,
                    roomIds = rooms,
                    fanPower = entry.int("fan_power") ?: VacuumFanPower.BALANCED,
                    waterMode = entry.int("water_box_mode") ?: WaterBoxMode.OFF,
                    mopRoute = entry.int("mop_mode") ?: MopRoute.STANDARD,
                    passes = entry.int("repeat") ?: 1,
                    mapFlag = entry.int("map_flag") ?: 0,
                )
            }
            else -> RoutineTask(RoutineTaskKind.OTHER, raw = itemObj)
        }
    }
    return RoutineDraft(tasks, triggers, tagId)
}

/**
 * Writes a draft as scene param JSON. [duid] is the robot each step runs on; [roomName] names a
 * step after its rooms, as the official app does. [nowMs] seeds each room step's task id.
 */
fun buildRoutineParam(draft: RoutineDraft, duid: String, roomName: (Int) -> String, nowMs: Long): JsonObject {
    val items = buildJsonArray {
        draft.tasks.forEachIndexed { index, task ->
            val id = index + 1
            if (task.kind == RoutineTaskKind.OTHER && task.raw != null) {
                // Renumbered so the steps stay in order, otherwise exactly as it was.
                add(JsonObject(task.raw + ("id" to JsonPrimitive(id))))
                return@forEachIndexed
            }
            val settings = buildJsonObject {
                put("fan_power", task.fanPower)
                put("water_box_mode", task.waterMode)
                put("mop_mode", task.mopRoute)
                put("mop_template_id", task.mopRoute)
                put("repeat", task.passes)
            }
            val inner = if (task.kind == RoutineTaskKind.FULL) {
                buildJsonObject {
                    put("id", id)
                    put("method", "do_scenes_app_start")
                    putJsonArray("params") {
                        add(JsonObject(settings + mapOf(
                            "auto_dustCollection" to JsonPrimitive(1),
                            "source" to JsonPrimitive(101),
                            "seq_type" to JsonPrimitive(0),
                        )))
                    }
                }
            } else {
                buildJsonObject {
                    put("id", id)
                    put("method", "do_scenes_segments")
                    put("params", buildJsonObject {
                        putJsonArray("data") {
                            add(JsonObject(settings + mapOf(
                                "tid" to JsonPrimitive((nowMs + id).toString()),
                                "segs" to JsonArray(task.roomIds.map { JsonObject(mapOf("sid" to JsonPrimitive(it))) }),
                                "map_flag" to JsonPrimitive(task.mapFlag),
                                "clean_order_mode" to JsonPrimitive(1),
                                "auto_dry" to JsonPrimitive(1),
                                "auto_dustCollection" to JsonPrimitive(1),
                                "region_num" to JsonPrimitive(0),
                                "seq_type" to JsonPrimitive(0),
                            )))
                        }
                        put("source", 101)
                    })
                }
            }
            add(buildJsonObject {
                put("id", id)
                put("type", "CMD")
                put("name", if (task.kind == RoutineTaskKind.ROOMS) task.roomIds.joinToString(" ") { roomName(it) } else "")
                put("entityId", duid)
                put("param", inner.toString())
                putJsonArray("finishDpIds") { add(JsonPrimitive(130)) }
            })
        }
    }
    return buildJsonObject {
        put("triggers", draft.triggers)
        put("matchType", "NONE")
        put("action", buildJsonObject {
            put("type", "S")
            put("items", items)
        })
        put("tagId", draft.tagId)
    }
}

/** The time of a routine's first enabled daily trigger, as "03:00", or null when it has none. */
fun routineScheduleLabel(draft: RoutineDraft): String? {
    for (trigger in draft.triggers) {
        val t = trigger as? JsonObject ?: continue
        if ((t["type"] as? JsonPrimitive)?.contentOrNull != "TIMER") continue
        val p = t["param"].asObject() ?: continue
        if ((p["enabled"] as? JsonPrimitive)?.contentOrNull == "false") continue
        val cron = (p["cron"] as? JsonPrimitive)?.contentOrNull?.split(" ") ?: continue
        val minute = cron.getOrNull(0)?.toIntOrNull() ?: continue
        val hour = cron.getOrNull(1)?.toIntOrNull() ?: continue
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }
    return null
}

/** One time trigger of a routine: when it runs, on which days (0 = Sunday), and whether it's on. */
data class RoutineSchedule(
    val id: Long,
    val hour: Int,
    val minute: Int,
    /** Days it runs, 0 = Sunday ... 6 = Saturday. */
    val days: Set<Int>,
    val enabled: Boolean,
    val timeZoneId: String,
)

/** The routine's TIMER triggers. Other trigger types are left alone and not listed. */
fun routineSchedules(param: JsonElement?): List<RoutineSchedule> {
    val root = param.asObject() ?: return emptyList()
    val triggers = root["triggers"] as? JsonArray ?: return emptyList()
    return triggers.mapNotNull { t ->
        val obj = t as? JsonObject ?: return@mapNotNull null
        if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "TIMER") return@mapNotNull null
        val p = obj["param"].asObject() ?: return@mapNotNull null
        val cron = (p["cron"] as? JsonPrimitive)?.contentOrNull?.split(" ") ?: return@mapNotNull null
        val minute = cron.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val hour = cron.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val dow = cron.getOrNull(4) ?: "*"
        val days = if (dow == "*" || dow == "?") (0..6).toSet()
        else dow.split(",").mapNotNull { it.trim().toIntOrNull()?.rem(7) }.toSet()
        RoutineSchedule(
            id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            hour = hour,
            minute = minute,
            days = days,
            enabled = (p["enabled"] as? JsonPrimitive)?.contentOrNull != "false",
            timeZoneId = (p["timeZoneId"] as? JsonPrimitive)?.contentOrNull ?: "UTC",
        )
    }
}

private fun RoutineSchedule.toTrigger(): JsonObject {
    val cron = "$minute $hour * * ${days.sorted().joinToString(",")}"
    // The trigger's own param is a JSON string, as the official app stores it.
    val param = buildJsonObject {
        put("cron", cron)
        put("type", "NORMAL")
        put("enabled", enabled)
        put("repeated", true)
        put("timeZoneId", timeZoneId)
    }
    return buildJsonObject {
        put("id", id)
        put("type", "TIMER")
        put("entityId", "")
        put("param", param.toString())
    }
}

/**
 * The scene param with its TIMER triggers replaced by [schedules]. The steps and any other trigger
 * stay exactly as they were, so changing a schedule never touches what the routine cleans.
 */
fun withRoutineSchedules(param: JsonElement?, schedules: List<RoutineSchedule>): JsonObject {
    val root = param.asObject() ?: JsonObject(emptyMap())
    val others = (root["triggers"] as? JsonArray)?.filter {
        ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull != "TIMER"
    } ?: emptyList()
    return JsonObject(root + ("triggers" to JsonArray(others + schedules.map { it.toTrigger() })))
}
