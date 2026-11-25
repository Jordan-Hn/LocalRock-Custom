package com.kodraliu.localrock.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberMicrophonePermissionRequest(onResult: (Boolean) -> Unit): () -> Unit = { onResult(false) }
