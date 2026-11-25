package com.kodraliu.localrock.ui

import androidx.compose.runtime.Composable

/** Returns a function that asks for the microphone, then calls [onResult] with whether it may be used. */
@Composable
expect fun rememberMicrophonePermissionRequest(onResult: (Boolean) -> Unit): () -> Unit
