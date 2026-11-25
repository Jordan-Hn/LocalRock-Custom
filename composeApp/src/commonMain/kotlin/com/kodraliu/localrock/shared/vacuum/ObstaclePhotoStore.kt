package com.kodraliu.localrock.shared.vacuum

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Obstacle photos kept on the phone, so a cleaning history entry still shows them after the robot
 * has replaced them with newer ones (it only keeps the latest few).
 *
 * Each photo is `<id>.img`, with its box, when the robot sent one, in `<id>.box`. `runs.txt` says
 * which photos belong to which run, one line per run: `beginEpoch<TAB>id:type,id:type`. [prune]
 * keeps the newest runs and deletes the photos only older runs used.
 */
class ObstaclePhotoStore(dirPath: String?) {
    private val fs = FileSystem.SYSTEM
    private val dir: Path? = dirPath?.let { "$it/obstacle-photos".toPath() }?.also { runCatching { fs.createDirectories(it) } }

    /** A photo kept for a run, with the obstacle type the run's map gave it. */
    data class Entry(val photoId: String, val type: Int?)

    fun get(photoId: String): ObstaclePhotoData? {
        val d = dir ?: return null
        val img = d / fileName(photoId, "img")
        return runCatching {
            if (!fs.exists(img)) return null
            val bytes = fs.read(img) { readByteArray() }
            val boxFile = d / fileName(photoId, "box")
            val box = if (fs.exists(boxFile)) {
                fs.read(boxFile) { readUtf8() }.trim().split(",").mapNotNull { it.toIntOrNull() }
                    .takeIf { it.size == 4 }?.let { PhotoBox(it[0], it[1], it[2], it[3]) }
            } else null
            ObstaclePhotoData(bytes, box)
        }.getOrNull()
    }

    fun put(photoId: String, data: ObstaclePhotoData) {
        val d = dir ?: return
        runCatching {
            fs.write(d / fileName(photoId, "img")) { write(data.image) }
            data.box?.let { b -> fs.write(d / fileName(photoId, "box")) { writeUtf8("${b.left},${b.top},${b.right},${b.bottom}") } }
        }
    }

    /** The photos saved for the run that began at [beginEpoch], or null when that run was never saved. */
    fun runPhotos(beginEpoch: Long): List<Entry>? = readRuns()[beginEpoch]

    fun hasRun(beginEpoch: Long): Boolean = beginEpoch in readRuns()

    /** Records which photos belong to a run, replacing what was recorded for it before. */
    fun saveRun(beginEpoch: Long, entries: List<Entry>) {
        writeRuns(readRuns() + (beginEpoch to entries))
    }

    /** Keeps the [keep] newest runs; deletes the other runs' records and any photo no kept run uses. */
    fun prune(keep: Int) {
        val d = dir ?: return
        val runs = readRuns()
        val kept = runs.entries.sortedByDescending { it.key }.take(keep).associate { it.key to it.value }
        if (kept.size != runs.size) writeRuns(kept)
        val used = kept.values.flatten().map { safe(it.photoId) }.toSet()
        runCatching {
            fs.list(d).filter { it.name != RUNS_FILE }.forEach { f ->
                if (f.name.substringBeforeLast('.') !in used) runCatching { fs.delete(f) }
            }
        }
    }

    private fun readRuns(): Map<Long, List<Entry>> {
        val f = dir?.div(RUNS_FILE) ?: return emptyMap()
        return runCatching {
            if (!fs.exists(f)) return emptyMap()
            fs.read(f) { readUtf8() }.lines().mapNotNull { line ->
                val epoch = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                val entries = line.substringAfter('\t', "").split(',').filter { it.isNotBlank() }.map {
                    Entry(it.substringBeforeLast(':'), it.substringAfterLast(':', "").toIntOrNull())
                }
                epoch to entries
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeRuns(runs: Map<Long, List<Entry>>) {
        val f = dir?.div(RUNS_FILE) ?: return
        runCatching {
            fs.write(f) {
                runs.entries.sortedByDescending { it.key }.forEach { (epoch, entries) ->
                    writeUtf8("$epoch\t${entries.joinToString(",") { "${it.photoId}:${it.type ?: ""}" }}\n")
                }
            }
        }
    }

    private fun fileName(photoId: String, ext: String) = "${safe(photoId)}.$ext"

    /** Photo ids are robot-made; anything but letters, digits, '-' and '_' is replaced so the id is a safe file name. */
    private fun safe(photoId: String) = photoId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")

    private companion object {
        const val RUNS_FILE = "runs.txt"
    }
}
