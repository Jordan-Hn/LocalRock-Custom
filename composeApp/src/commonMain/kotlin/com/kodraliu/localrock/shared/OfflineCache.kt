package com.kodraliu.localrock.shared

import com.kodraliu.localrock.shared.protocol.appDataDir
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * What the app last saw, kept on the phone so it can still show the robots, their map, status and
 * history when the local server can't be reached (away from home). Nothing here is ever sent; it is
 * only read back to fill the screens until live data arrives.
 *
 * Entries are plain files under the app's private folder: `home.json` for the device list and
 * `<duid>/<name>` for each robot.
 */
class OfflineCache(dirPath: String? = appDataDir()) {
    private val fs = FileSystem.SYSTEM
    private val dir: Path? = dirPath?.let { "$it/offline-cache".toPath() }

    fun readText(key: String): String? = runCatching { path(key)?.takeIf { fs.exists(it) }?.let { fs.read(it) { readUtf8() } } }.getOrNull()

    fun readBytes(key: String): ByteArray? = runCatching { path(key)?.takeIf { fs.exists(it) }?.let { fs.read(it) { readByteArray() } } }.getOrNull()

    fun writeText(key: String, text: String) = write(key) { writeUtf8(text) }

    fun writeBytes(key: String, bytes: ByteArray) = write(key) { write(bytes) }

    private fun write(key: String, body: okio.BufferedSink.() -> Unit) {
        val target = path(key) ?: return
        runCatching {
            target.parent?.let { fs.createDirectories(it) }
            // Written beside the target and moved over it, so a crash never leaves half a file to read back.
            val tmp = "$target.tmp".toPath()
            fs.write(tmp) { body() }
            fs.atomicMove(tmp, target)
        }
    }

    /** Keys are made by the app ("home.json", "<duid>/map.bin"); each part is kept to safe file-name characters. */
    private fun path(key: String): Path? {
        val d = dir ?: return null
        return key.split('/').fold(d) { p, part -> p / part.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("") }
    }
}
