package com.kodraliu.localrock.ui

import androidx.compose.runtime.Composable

/** Gets a photo out of the app: into the phone's gallery, or to another app through the share sheet. */
interface PhotoExporter {
    /** Saves [bytes] as a picture; true when it was written. */
    fun save(bytes: ByteArray, name: String): Boolean

    /** Opens the share sheet with [bytes] as a picture; true when the sheet could be shown. */
    fun share(bytes: ByteArray, name: String): Boolean
}

@Composable
expect fun rememberPhotoExporter(): PhotoExporter

/** "image/png" for PNG data, otherwise JPEG, which is what the robot's cameras send. */
internal fun photoMimeType(bytes: ByteArray): String =
    if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"

internal fun photoExtension(bytes: ByteArray): String = if (photoMimeType(bytes) == "image/png") "png" else "jpg"
