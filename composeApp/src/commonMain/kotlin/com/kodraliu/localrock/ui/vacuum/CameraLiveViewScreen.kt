package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kodraliu.localrock.shared.vacuum.camera.CameraLiveSession
import com.kodraliu.localrock.shared.webrtc.RtcVideoView
import com.kodraliu.localrock.shared.webrtc.liveViewSupported
import com.kodraliu.localrock.ui.AppColors
import com.kodraliu.localrock.ui.LocalAppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The tabs under the live video, as the official remote view has them. */
enum class RemoteTab(val label: String) { DRIVE("Remote control"), PET("Search for pet") }

private val PanelColor = Color(0xFF16191D)
private val ControlColor = Color(0xFF262A30)

/**
 * Remote view: the robot's live camera on top, and underneath it the controls that are used while
 * watching: hold-to-drive arrows, or the pet search. Driving works without video too, so the screen
 * is useful while the camera can't stream.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraLiveViewScreen(
    viewModel: VacuumViewModel,
    onBack: () -> Unit,
    initialTab: RemoteTab = RemoteTab.DRIVE,
) {
    val container = LocalAppContainer.current
    val duid = viewModel.repository.duid
    val repository = viewModel.repository
    val scope = rememberCoroutineScope()
    val liveSession = remember { CameraLiveSession(viewModel.repository.session) }
    val state by liveSession.state.collectAsState()
    val peer by liveSession.peer.collectAsState()
    val status by repository.status.collectAsState()
    var tab by remember { mutableStateOf(initialTab) }
    var pin by remember { mutableStateOf(container.appSettings.cameraPin(duid) ?: "") }

    DisposableEffect(Unit) {
        onDispose {
            liveSession.dispose()
            scope.launch { runCatching { repository.stopRemoteControl() } }
        }
    }
    LaunchedEffect(Unit) {
        // With a saved PIN the video starts straight away, as the official app's does.
        if (liveViewSupported && pin.isNotBlank()) liveSession.start(pin)
    }

    Scaffold(
        containerColor = PanelColor,
        topBar = {
            TopAppBar(
                title = { Text(viewModel.deviceName, color = Color.White, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PanelColor),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The live feed, 4:3 like the robot's camera.
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                VideoArea(
                    state = state,
                    peer = peer,
                    pin = pin,
                    onPinChange = { pin = it },
                    onConnect = {
                        container.appSettings.setCameraPin(duid, pin)
                        liveSession.start(pin)
                    },
                    onSetPinOnRobot = {
                        container.appSettings.setCameraPin(duid, pin)
                        liveSession.setPinAndStart(pin)
                    },
                    onRetry = { liveSession.start(pin) },
                    onEnable = { liveSession.enableAndStart(pin) },
                    onChangePin = {
                        container.appSettings.setCameraPin(duid, null)
                        pin = ""
                        liveSession.stop()
                    },
                )
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteTab.entries.forEach { t ->
                    val selected = t == tab
                    Surface(
                        onClick = { tab = t },
                        shape = CircleShape,
                        color = if (selected) AppColors.Brand else ControlColor,
                        contentColor = Color.White,
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(t.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    RemoteTab.DRIVE -> DrivePad(
                        onStart = { runCatching { repository.startRemoteControl() } },
                        onMove = { v, o -> runCatching { repository.moveRemote(v, o, 700) } },
                        onEnd = { runCatching { repository.stopRemoteControl() } },
                    )
                    RemoteTab.PET -> PetSearchPanel(
                        stateLabel = status.state?.let { com.kodraliu.localrock.shared.vacuum.VacuumStateCodes.describe(it) } ?: "—",
                        onStart = { viewModel.run("Looking for your pet") { it.startPetSearch() } },
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.run("Returning to dock") { it.dock() } },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.Home, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Return to dock")
                }
            }
        }
    }
}

@Composable
private fun VideoArea(
    state: CameraLiveSession.State,
    peer: com.kodraliu.localrock.shared.webrtc.RtcPeer?,
    pin: String,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSetPinOnRobot: () -> Unit,
    onRetry: () -> Unit,
    onEnable: () -> Unit,
    onChangePin: () -> Unit,
) {
    if (!liveViewSupported) {
        CameraMessage("Live video isn't available on this platform yet. You can still drive below.")
        return
    }
    when (state) {
        is CameraLiveSession.State.Idle -> {
            // The pattern pad needs more room than the video frame, so it opens over the screen.
            var unlocking by remember { mutableStateOf(false) }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("The live video is locked with the camera pattern.", color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { unlocking = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = Color.White),
                ) { Text("Unlock live video") }
            }
            if (unlocking) {
                Dialog(onDismissRequest = { unlocking = false }) {
                    Surface(shape = RoundedCornerShape(28.dp), color = PanelColor) {
                        PinEntry(
                            pin = pin,
                            onPinChange = onPinChange,
                            onConnect = { unlocking = false; onConnect() },
                            onSetPinOnRobot = { unlocking = false; onSetPinOnRobot() },
                        )
                    }
                }
            }
        }
        is CameraLiveSession.State.Connecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(state.stage, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        is CameraLiveSession.State.Streaming -> Box(Modifier.fillMaxSize()) {
            peer?.let { RtcVideoView(it, Modifier.fillMaxSize()) }
            Text(
                "LIVE",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color(0xFFE53935), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        is CameraLiveSession.State.Failed -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            Icon(Icons.Default.VideocamOff, null, Modifier.size(36.dp), tint = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(state.message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            if (state.canEnable) {
                Button(
                    onClick = onEnable,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = Color.White),
                ) { Text("Try to enable remote viewing") }
            } else {
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = Color.White)) { Text("Retry") }
            }
            TextButton(onClick = onChangePin) { Text("Change PIN", color = Color.White) }
        }
    }
}

/**
 * Hold-to-drive arrows: forward and back, and turning on the spot. While an arrow is held the robot
 * is sent short moves back to back, so it keeps going until the finger lifts.
 */
@Composable
private fun DrivePad(
    onStart: suspend () -> Unit,
    onMove: suspend (velocity: Double, omega: Double) -> Unit,
    onEnd: suspend () -> Unit,
) {
    LaunchedEffect(Unit) { onStart() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Press and hold to drive", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        HoldButton(Icons.Default.KeyboardArrowUp, "Forward", onHold = { onMove(0.3, 0.0) }, onRelease = onEnd)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            HoldButton(Icons.AutoMirrored.Filled.RotateLeft, "Turn left", onHold = { onMove(0.0, 0.6) }, onRelease = onEnd)
            Spacer(Modifier.size(76.dp))
            HoldButton(Icons.AutoMirrored.Filled.RotateRight, "Turn right", onHold = { onMove(0.0, -0.6) }, onRelease = onEnd)
        }
        HoldButton(Icons.Default.KeyboardArrowDown, "Backward", onHold = { onMove(-0.2, 0.0) }, onRelease = onEnd)
    }
}

@Composable
private fun HoldButton(icon: ImageVector, label: String, onHold: suspend () -> Unit, onRelease: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val hold by rememberUpdatedState(onHold)
    val release by rememberUpdatedState(onRelease)
    var pressed by remember { mutableIntStateOf(0) }
    Surface(
        shape = CircleShape,
        color = if (pressed > 0) AppColors.Brand else ControlColor,
        contentColor = Color.White,
        modifier = Modifier.size(76.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed++
                val job = scope.launch {
                    while (isActive) {
                        hold()
                        delay(450)
                    }
                }
                tryAwaitRelease()
                job.cancel()
                pressed--
                scope.launch { release() }
            })
        },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp)) }
    }
}

@Composable
private fun PetSearchPanel(stateLabel: String, onStart: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Pets, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(
            "The robot leaves the dock and turns until it finds your pet, while you watch above. It starts only from the dock.",
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Now: $stateLabel", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape,
        ) { Text("Search for pet", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PinEntry(
    pin: String,
    onPinChange: (String) -> Unit,
    onConnect: () -> Unit,
    onSetPinOnRobot: () -> Unit,
) {
    // Newer models draw the camera PIN as a pattern; older ones type it.
    var typed by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text(
            if (typed) "Enter the camera PIN, or pick a new one and set it on the robot."
            else "Draw the camera pattern, or draw a new one and set it on the robot.",
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
        if (typed) {
            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                label = { Text("Camera PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        } else {
            PatternPinInput(onPattern = onPinChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConnect,
                enabled = pin.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Brand, contentColor = Color.White),
            ) { Text("Start video") }
            TextButton(onClick = onSetPinOnRobot, enabled = pin.isNotBlank()) { Text("Set on robot", color = Color.White) }
        }
        TextButton(onClick = { typed = !typed; onPinChange("") }) {
            Text(if (typed) "Draw a pattern instead" else "Type a PIN instead", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun CameraMessage(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(Icons.Default.VideocamOff, null, Modifier.size(36.dp), tint = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}
