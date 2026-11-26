package com.kodraliu.localrock.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberPhotoExporter(): PhotoExporter = object : PhotoExporter {
    override fun save(bytes: ByteArray, name: String) = false
    override fun share(bytes: ByteArray, name: String) = false
}
