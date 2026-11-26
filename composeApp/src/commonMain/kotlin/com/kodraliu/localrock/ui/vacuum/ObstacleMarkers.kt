package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import com.kodraliu.localrock.ui.rememberPhotoExporter
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kodraliu.localrock.shared.vacuum.ObstaclePhotoData
import com.kodraliu.localrock.shared.vacuum.PhotoBox
import com.kodraliu.localrock.shared.vacuum.map.MapObstacle
import com.kodraliu.localrock.shared.vacuum.map.ObstacleKind
import com.kodraliu.localrock.shared.vacuum.map.confidencePercent
import com.kodraliu.localrock.shared.vacuum.map.obstacleType
import com.kodraliu.localrock.shared.vacuum.map.isErasedMm
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * Where an obstacle sits on screen: its cell, scaled to the bitmap the map view draws ([cellScale]
 * bitmap pixels per cell), through the same [MapView] as the floor, so it follows any heading, zoom or tilt.
 */
internal fun ParsedMap.obstacleOnScreen(obstacle: MapObstacle, view: MapView, cellScale: Float): Offset {
    val cell = mmToCell(obstacle.x, obstacle.y)
    return view.toScreen(cell.x * cellScale, cell.y * cellScale)
}

/** The obstacle closest to [tap] within [reachPx], or null; drawn last means on top, so it wins a tie. */
internal fun ParsedMap.obstacleAt(tap: Offset, view: MapView, cellScale: Float, reachPx: Float): MapObstacle? =
    obstacles.withIndex()
        .filter { !isErasedMm(it.value.x, it.value.y) }
        .map { (i, o) -> Triple(i, o, (obstacleOnScreen(o, view, cellScale) - tap).getDistance()) }
        .filter { it.third <= reachPx }
        .minWithOrNull(compareBy<Triple<Int, MapObstacle, Float>> { it.third }.thenByDescending { it.first })
        ?.second

/**
 * Obstacles as small round badges with an icon for the kind of thing the robot saw, as the Roborock app
 * draws them, of a fixed size like the room labels. A dark halo keeps one visible on light and dark maps;
 * one with a photo is a little larger with a white ring, so it reads as something to open.
 */
internal fun DrawScope.drawObstacles(map: ParsedMap, view: MapView, cellScale: Float, icons: Map<ObstacleKind, Painter>) {
    for (o in map.obstacles) {
        if (map.isErasedMm(o.x, o.y)) continue
        val c = map.obstacleOnScreen(o, view, cellScale)
        if (c.x < 0f || c.x > size.width || c.y < 0f || c.y > size.height) continue
        val hasPhoto = o.photoId != null
        val r = (if (hasPhoto) 8 else 7).dp.toPx()
        drawCircle(color = OBSTACLE_HALO, radius = r + 1.5.dp.toPx(), center = c)
        drawCircle(color = if (hasPhoto) Color.White else OBSTACLE_RING, radius = r, center = c)
        drawCircle(color = OBSTACLE_FILL, radius = r - 1.3.dp.toPx(), center = c)
        val icon = icons[obstacleType(o.type).kind] ?: continue
        val side = r * 1.2f
        translate(c.x - side / 2, c.y - side / 2) {
            // Every symbol in white, so the markers read as one set whatever colour each icon was drawn in.
            with(icon) { draw(Size(side, side), colorFilter = ColorFilter.tint(Color.White)) }
        }
    }
}

private val OBSTACLE_HALO = Color.Black.copy(alpha = 0.35f)
private val OBSTACLE_RING = Color(0xFFA7E3DB)
private val OBSTACLE_FILL = Color(0xFF2A7F86)

/** What a tapped obstacle is, and its photo when the robot kept one, with the box the robot drew around it. */
@Composable
internal fun ObstacleSheetContent(obstacle: MapObstacle, loadPhoto: suspend (String) -> ObstaclePhotoData) {
    var photo by remember(obstacle) { mutableStateOf<Pair<ImageBitmap, PhotoBox?>?>(null) }
    var failure by remember(obstacle) { mutableStateOf<String?>(null) }
    var fullScreen by remember(obstacle) { mutableStateOf(false) }
    var photoBytes by remember(obstacle) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(obstacle) {
        val id = obstacle.photoId ?: return@LaunchedEffect
        runCatching { loadPhoto(id).let { photoBytes = it.image; it.image.decodeToImageBitmap() to it.box } }
            .onSuccess { photo = it }
            .onFailure { failure = it.message ?: "The robot didn't send it" }
    }
    val type = obstacleType(obstacle.type)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
    ) {
        Text(type.name, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "AI | ${if (obstacle.ignored) "Ignored" else "Avoided"}",
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            obstacle.confidencePercent()?.let {
                Text("Confidence:$it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(type.description, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        val current = photo
        // The full frame is 4:3; capped so it fits a half-open sheet, and the sheet scrolls for the rest.
        val frame = Modifier.fillMaxWidth().heightIn(max = 360.dp)
        when {
            obstacle.photoId == null -> Text(
                "The robot kept no photo of this obstacle.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            current != null -> {
                val (bitmap, box) = current
                val boxColor = MaterialTheme.colorScheme.primary
                Image(
                    bitmap = bitmap,
                    contentDescription = "Photo of the obstacle",
                    modifier = frame.aspectRatio(bitmap.width.toFloat() / bitmap.height, matchHeightConstraintsFirst = true)
                        .align(Alignment.CenterHorizontally)
                        .clip(MaterialTheme.shapes.large)
                        .clickable { fullScreen = true }
                        .drawWithContent {
                            drawContent()
                            if (box != null) drawBoxCorners(box, bitmap.width, bitmap.height, boxColor)
                        },
                    contentScale = ContentScale.Fit,
                )
                if (fullScreen) FullScreenPhoto(bitmap, photoBytes, "obstacle-${obstacle.photoId}") { fullScreen = false }
            }
            failure != null -> Text(
                "Couldn't load the photo: $failure",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Box(frame.aspectRatio(4f / 3f).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/** The photo on its own, edge to edge with no box over it: pinch to zoom, double tap to reset, tap or X to close. */
@Composable
internal fun FullScreenPhoto(bitmap: ImageBitmap, bytes: ByteArray?, name: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val zoom = rememberTransformableState { z, pan, _ ->
            scale = (scale * z).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale == 1f) onClose() },
                        onDoubleTap = { scale = 1f; offset = Offset.Zero },
                    )
                }
                .transformable(zoom),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Obstacle photo, full screen",
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            )
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                if (bytes != null) {
                    val exporter = rememberPhotoExporter()
                    val messages = com.kodraliu.localrock.ui.LocalAppContainer.current.messages
                    IconButton(onClick = {
                        if (exporter.save(bytes, name)) messages.success("Saved to Pictures/LocalRock")
                        else messages.error("Couldn't save the photo")
                    }) {
                        Icon(Icons.Filled.Download, contentDescription = "Save to gallery", tint = Color.White)
                    }
                    IconButton(onClick = { if (!exporter.share(bytes, name)) messages.error("Couldn't share the photo") }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

/** The official corner brackets around the boxed obstacle, scaled from image pixels to the drawn photo. */
private fun DrawScope.drawBoxCorners(box: PhotoBox, imageWidth: Int, imageHeight: Int, color: Color) {
    val sx = size.width / imageWidth
    val sy = size.height / imageHeight
    val l = box.left * sx
    val t = box.top * sy
    val r = box.right * sx
    val b = box.bottom * sy
    val arm = minOf(r - l, b - t) * 0.22f
    val stroke = 2.dp.toPx()
    drawRect(color.copy(alpha = 0.12f), topLeft = Offset(l, t), size = Size(r - l, b - t))
    drawRect(color, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(stroke / 2))
    for ((x, y, dx, dy) in listOf(
        listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f),
    )) {
        drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke * 1.5f)
        drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke * 1.5f)
    }
}

/**
 * A run's obstacle photos in rows of three, each with its obstacle name; a tap opens it full screen.
 * A photo neither the phone nor the robot still has shows as a grey tile.
 */
@Composable
internal fun RunPhotoGrid(photos: List<com.kodraliu.localrock.shared.vacuum.VacuumRepository.RunPhoto>) {
    var open by remember { mutableStateOf<Pair<ImageBitmap, com.kodraliu.localrock.shared.vacuum.VacuumRepository.RunPhoto>?>(null) }
    val decoded = remember(photos) {
        photos.map { p -> p to p.photo?.let { runCatching { it.image.decodeToImageBitmap() }.getOrNull() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        decoded.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (p, bitmap) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .then(if (bitmap != null) Modifier.clickable { open = bitmap to p } else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) {
                                Image(bitmap, contentDescription = obstacleType(p.type).name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else if (p.pending) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Not kept", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(obstacleType(p.type).name, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    open?.let { (bitmap, p) -> FullScreenPhoto(bitmap, p.photo?.image, "obstacle-${p.photoId}") { open = null } }
}
