package com.kodraliu.localrock.ui.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kodraliu.localrock.shared.device.Reachability
import com.kodraliu.localrock.shared.model.Device
import com.kodraliu.localrock.shared.model.Scene
import com.kodraliu.localrock.shared.onboarding.vacuumPairingSupported
import com.kodraliu.localrock.shared.vacuum.VacuumStateCodes
import com.kodraliu.localrock.shared.vacuum.productDisplayName
import com.kodraliu.localrock.ui.AppColors
import com.kodraliu.localrock.ui.icons.RobotVacuum

/**
 * The home screen: one large card per vacuum with its picture, state and an Enter button, and the
 * vacuum's routines as quick-start tiles underneath. Devices and Profile sit in the bottom bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel,
    onSettings: () -> Unit,
    onDeviceClick: (Device) -> Unit,
    onAddVacuum: () -> Unit,
    onPairDevice: (String) -> Unit = {},
) {
    val devices by viewModel.devices.collectAsState()
    val unpaired by viewModel.unpaired.collectAsState()
    val products by viewModel.productsByDuid.collectAsState()
    val reachability by viewModel.reachability.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val awayFromHome by viewModel.awayFromHome.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val message by viewModel.message.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                actions = {
                    if (vacuumPairingSupported) {
                        IconButton(onClick = onAddVacuum) {
                            Icon(Icons.Default.Add, contentDescription = "Add vacuum")
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    showLogoutDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Devices") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onSettings,
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Profile") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                refreshing && devices.isEmpty() && error == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                devices.isEmpty() -> {
                    EmptyState(
                        error = error,
                        canPair = vacuumPairingSupported,
                        onAddVacuum = onAddVacuum,
                        onRetry = viewModel::refresh,
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        error?.let { msg ->
                            item { ErrorCard(message = msg, onRetry = viewModel::refresh) }
                        }
                        if (awayFromHome) item { AwayFromHomeCard(onRetry = viewModel::refresh) }
                        items(devices, key = { it.duid }) { device ->
                            val needsPairing = device.duid in unpaired && vacuumPairingSupported
                            HomeDeviceCard(
                                device = device,
                                model = productDisplayName(products[device.duid]).orEmpty(),
                                reachability = reachability[device.duid],
                                info = cards[device.duid] ?: HomeCardInfo(),
                                unpaired = needsPairing,
                                onEnter = { if (needsPairing) onPairDevice(device.duid) else onDeviceClick(device) },
                                onRoutine = viewModel::runRoutine,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need to log in again with an email code to control your vacuums.") },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; viewModel.logout() }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** One empty screen that also carries the error case, so the list is never blank without a reason. */
@Composable
private fun EmptyState(
    error: String?,
    canPair: Boolean,
    onAddVacuum: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (error != null) Icons.Default.ErrorOutline else RobotVacuum,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = if (error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (error != null) "Can't reach your server" else "No robots yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                error ?: "Add your first vacuum, or pull down to check the server again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            if (error != null) {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            } else if (canPair) {
                Button(onClick = onAddVacuum) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add vacuum")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Shown while the server can't be reached: the list, status and history are the phone's saved copy. */
@Composable
private fun AwayFromHomeCard(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Away from home: showing what was saved on this phone. Controls work again on your home network.",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun HomeDeviceCard(
    device: Device,
    model: String,
    reachability: Reachability?,
    info: HomeCardInfo,
    unpaired: Boolean,
    onEnter: () -> Unit,
    onRoutine: (Scene) -> Unit,
) {
    // Without a local key there is nothing to talk to, so say that instead of silently ignoring taps.
    val notPaired = device.localKey == null
    // The robot's own answer wins; the server's flag is only used when the robot could not be asked.
    val online = when (reachability) {
        Reachability.ONLINE -> true
        Reachability.OFFLINE, Reachability.CHECKING -> false
        null -> device.online
    }
    val statusLine = when {
        unpaired -> "Not paired yet. Tap Enter to pair it."
        notPaired -> "Setup incomplete. Re-run pairing on your server."
        reachability == Reachability.CHECKING -> "Checking…"
        !online -> "Offline"
        else -> listOfNotNull(
            info.status?.state?.let { VacuumStateCodes.describe(it) },
            info.status?.battery?.let { "$it%" },
        ).joinToString(" · ").ifEmpty { "Online" }
    }
    val statusColor = when {
        unpaired -> MaterialTheme.colorScheme.tertiary
        notPaired -> MaterialTheme.colorScheme.error
        online -> AppColors.Good
        else -> MaterialTheme.colorScheme.outline
    }
    val canEnter = !notPaired || unpaired

    Surface(
        onClick = onEnter,
        enabled = canEnter,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                device.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.isNotEmpty()) {
                Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                RobotPortrait(dimmed = !online || notPaired, modifier = Modifier.size(150.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = statusColor, modifier = Modifier.size(8.dp)) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    statusLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
                Surface(
                    onClick = onEnter,
                    enabled = canEnter,
                    shape = CircleShape,
                    color = if (canEnter) AppColors.Brand else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = Color.White,
                ) {
                    Text(
                        if (unpaired) "Pair" else "Enter",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                    )
                }
            }

            if (canEnter && !unpaired) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Routines", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onEnter) { Text("More") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (info.routines.isEmpty()) {
                        item { RoutineTile(name = "Add a routine", add = true, onClick = onEnter) }
                    } else {
                        items(info.routines, key = { it.id }) { scene ->
                            RoutineTile(name = scene.name, add = false, onClick = { onRoutine(scene) })
                        }
                    }
                }
            }
        }
    }
}

/** A routine as a quick-start tile: tapping it runs the routine straight away. */
@Composable
private fun RoutineTile(name: String, add: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(132.dp).height(76.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(
                shape = CircleShape,
                color = if (add) MaterialTheme.colorScheme.surfaceContainerHighest else AppColors.Brand.copy(alpha = 0.14f),
                modifier = Modifier.size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (add) Icons.Default.Add else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (add) MaterialTheme.colorScheme.onSurfaceVariant else AppColors.Brand,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The robot seen from above, drawn rather than shipped as a picture: a pale shell with a shading
 * gradient, the laser turret toward the front, the bumper seam and a status light.
 */
@Composable
internal fun RobotPortrait(dimmed: Boolean, modifier: Modifier = Modifier) {
    val shellTop = Color(0xFFFFFFFF)
    val shellEdge = Color(0xFFD5DAE1)
    val ink = Color(0xFF8A94A3)
    val light = if (dimmed) Color(0xFFB0B7C1) else AppColors.Brand
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * 0.92f
        // Soft floor shadow, then the shell.
        drawCircle(Color.Black.copy(alpha = 0.10f), radius = r * 1.02f, center = c + Offset(0f, r * 0.06f))
        drawCircle(
            Brush.radialGradient(listOf(shellTop, shellEdge), center = c - Offset(r * 0.25f, r * 0.3f), radius = r * 1.4f),
            radius = r,
            center = c,
        )
        // Bumper seam across the front third.
        drawArc(
            color = ink.copy(alpha = 0.45f),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = c - Offset(r * 0.86f, r * 0.86f),
            size = Size(r * 1.72f, r * 1.72f),
            style = Stroke(width = r * 0.025f),
        )
        // Laser turret.
        val turret = c - Offset(0f, r * 0.38f)
        drawCircle(Color(0xFFEEF1F5), radius = r * 0.24f, center = turret)
        drawCircle(ink.copy(alpha = 0.55f), radius = r * 0.24f, center = turret, style = Stroke(width = r * 0.025f))
        drawCircle(ink.copy(alpha = 0.25f), radius = r * 0.13f, center = turret)
        // Status light and the lid's button.
        drawCircle(light, radius = r * 0.045f, center = c + Offset(0f, r * 0.08f))
        drawCircle(ink.copy(alpha = 0.35f), radius = r * 0.09f, center = c + Offset(0f, r * 0.42f), style = Stroke(width = r * 0.02f))
    }
}
