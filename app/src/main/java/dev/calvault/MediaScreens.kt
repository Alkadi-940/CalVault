package dev.calvault

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import dev.calvault.core.VaultItem
import dev.calvault.core.FolderPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable internal fun MediaViewer(state: UiState, settings: AppSettings, model: VaultViewModel) {
    val item = state.viewing ?: return
    val video = item.mime.startsWith("video/")
    val orientation = LocalConfiguration.current.orientation
    var fullscreen by rememberSaveable(item.folder) { mutableStateOf(false) }
    var unhide by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    LaunchedEffect(orientation) { if (video) fullscreen = orientation == Configuration.ORIENTATION_LANDSCAPE }
    DisposableEffect(fullscreen) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) { controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; controller?.hide(WindowInsetsCompat.Type.systemBars()) }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) Header(item.name, model::back, trailing = {
            if (video || item.mime.startsWith("image/")) IconButton(onClick = { model.openSettings(if (video) "Video" else "Images") }) { Icon(Icons.Outlined.Settings, "Viewer settings") }
            IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock vault") }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                video -> VideoPane(state, settings, model, fullscreen, { fullscreen = !fullscreen })
                item.mime.startsWith("image/") -> PhotoPane(state, settings, model)
                else -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp)); Text("Stored in your vault", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp)); Text("Unhide this file to open it with another app.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (fullscreen) IconButton(onClick = model::back, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(24.dp))) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to folder", tint = Color.White) }
        }
        if (!fullscreen) Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${formatSize(item.size)} · ${FolderPath.display(item.folder)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { unhide = true }, Modifier.weight(1f)) { Icon(Icons.Outlined.Visibility, null); Spacer(Modifier.width(8.dp)); Text("Unhide") }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.DeleteOutline, "Move to recycle bin") }
            }
        }
    }
    if (unhide) UnhideDialog(1, { unhide = false }) { original -> unhide = false; model.requestUnhide(setOf(item.id), original) }
    if (deleting) ConfirmDialog("Move to recycle bin?", "You can restore this file from the recycle bin.", "Move to bin", { deleting = false }) { deleting = false; model.delete(setOf(item.id)); model.navigate(Screen.VAULT) }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoPane(state: UiState, settings: AppSettings, model: VaultViewModel, fullscreen: Boolean, toggleFullscreen: () -> Unit) {
    val item = state.viewing ?: return
    val controller = remember(model, item.folder) { model.videoController() }
    LaunchedEffect(item.id) { model.videoController() }
    val playback by controller.flow.collectAsStateWithLifecycle()
    val videoAccent = remember(settings.accent) {
        var color = settings.accent or 0xFF000000.toInt()
        repeat(40) { if (ColorUtils.calculateContrast(color, android.graphics.Color.BLACK) < 4.5) color = ColorUtils.blendARGB(color, android.graphics.Color.WHITE, .12f) }
        Color(color)
    }
    var controls by remember { mutableStateOf(true) }
    var seekHint by remember { mutableStateOf("") }
    var seekTick by remember { mutableLongStateOf(0) }
    var seekRight by remember { mutableStateOf(true) }
    LaunchedEffect(controls, playback.playing) { if (controls && playback.playing) { delay(3500); controls = false } }
    LaunchedEffect(seekTick) { if (seekTick > 0) { delay(850); seekHint = "" } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> PlayerView(context).apply { player = controller.player; useController = false; setKeepContentOnPlayerReset(false) } },
            update = { it.player = controller.player }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
        Box(Modifier.matchParentSize().semantics { contentDescription = "Video surface" }.pointerInput(controller, settings.seekForwardSeconds, settings.seekBackSeconds) {
            detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { point ->
                seekRight = point.x >= size.width / 2
                val seconds = controller.skip(seekRight)
                seekHint = (if (seekRight) "+" else "−") + "$seconds s"; seekTick++
            })
        })
        if (seekHint.isNotEmpty()) Text(seekHint, Modifier.align(if (seekRight) Alignment.CenterEnd else Alignment.CenterStart).padding(36.dp).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(20.dp)).padding(20.dp), color = Color.White, style = MaterialTheme.typography.headlineSmall)
        if (playback.error != null) Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(playback.error!!, color = Color.White, textAlign = TextAlign.Center) }
        if (controls || !playback.playing) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Black.copy(alpha = .68f)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (fullscreen) Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Slider(playback.position.toFloat().coerceIn(0f, playback.duration.coerceAtLeast(1).toFloat()), { controller.seek(it.toLong()) }, valueRange = 0f..playback.duration.coerceAtLeast(1).toFloat(), enabled = playback.duration > 0,
                    colors = SliderDefaults.colors(thumbColor = videoAccent, activeTrackColor = videoAccent, inactiveTrackColor = Color.White.copy(alpha = .3f)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(mediaTime(playback.position), color = Color.White, style = MaterialTheme.typography.labelSmall); Text(mediaTime(playback.duration), color = Color.White, style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::previous) { Icon(Icons.Outlined.SkipPrevious, "Previous video", tint = Color.White) }
                    IconButton(onClick = controller::toggle) { Icon(if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playback.playing) "Pause video" else "Play video", tint = Color.White) }
                    IconButton(onClick = controller::next) { Icon(Icons.Outlined.SkipNext, "Next video", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    IconToggleButton(settings.videoShuffle, { model.updateSettings(settings.copy(videoShuffle = it)) }) { Icon(Icons.Outlined.Shuffle, "Shuffle playlist", tint = if (settings.videoShuffle) videoAccent else Color.White) }
                    IconToggleButton(settings.videoReplay, { model.updateSettings(settings.copy(videoReplay = it)) }) { Icon(Icons.Outlined.RepeatOne, "Replay current video", tint = if (settings.videoReplay) videoAccent else Color.White) }
                    IconButton(onClick = toggleFullscreen) { Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Toggle fullscreen", tint = Color.White) }
                }
            }
        }
    }
}

@Composable private fun PhotoPane(state: UiState, settings: AppSettings, model: VaultViewModel) {
    val item = state.viewing ?: return
    val currentItemId by rememberUpdatedState(item.id)
    var ready by remember { mutableStateOf("") }
    LaunchedEffect(item.id, ready, state.photoSlideshow, state.foreground, settings.slideshowSeconds) {
        if (state.photoSlideshow && state.foreground && ready == item.id) { delay(settings.slideshowSeconds * 1000L); model.nextPhoto() }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))) {
            AnimatedContent(item.id, transitionSpec = {
                when (settings.slideTransition) {
                    SlideTransition.FADE -> fadeIn(tween(350)) togetherWith fadeOut(tween(350))
                    SlideTransition.SLIDE -> (slideInHorizontally(tween(350)) { it } + fadeIn()) togetherWith (slideOutHorizontally(tween(350)) { -it } + fadeOut())
                    SlideTransition.NONE -> EnterTransition.None togetherWith ExitTransition.None
                }
            }, label = "Photo transition", modifier = Modifier.fillMaxSize()) { id ->
                val picture = state.items.firstOrNull { it.id == id } ?: item
                PhotoCanvas(picture, state, model) { if (id == currentItemId) ready = id }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { model.nextPhoto(true) }) { Icon(Icons.Outlined.SkipPrevious, "Previous image") }
            IconButton(onClick = model::toggleSlideshow) { Icon(if (state.photoSlideshow) Icons.Outlined.Pause else Icons.Outlined.Slideshow, if (state.photoSlideshow) "Pause slideshow" else "Start slideshow") }
            IconButton(onClick = { model.nextPhoto() }) { Icon(Icons.Outlined.SkipNext, "Next image") }
            Text("${state.photoIndex + 1} / ${state.photoOrder.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelSmall)
            IconToggleButton(settings.slideshowShuffle, { model.updateSettings(settings.copy(slideshowShuffle = it)) }) { Icon(Icons.Outlined.Shuffle, "Shuffle slideshow", tint = if (settings.slideshowShuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun PhotoCanvas(item: VaultItem, state: UiState, model: VaultViewModel, onReady: () -> Unit) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(item.id) { mutableStateOf(false) }
    var scale by rememberSaveable(item.id) { mutableFloatStateOf(state.imageScale) }
    var x by rememberSaveable(item.id) { mutableFloatStateOf(state.imagePanX) }
    var y by rememberSaveable(item.id) { mutableFloatStateOf(state.imagePanY) }
    LaunchedEffect(item.id) {
        try { bitmap = withContext(Dispatchers.IO) { decodeVaultPhoto(model.vault, item.id) }; onReady() }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = true; onReady() }
    }
    DisposableEffect(item.id) { onDispose { bitmap = null } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val loaded = bitmap
        when {
            error -> Text("This image cannot be previewed. Unhide it to open it with another app.", Modifier.padding(24.dp), textAlign = TextAlign.Center)
            loaded != null -> Image(loaded.asImageBitmap(), item.name, Modifier.fillMaxSize()
                .pointerInput(item.id) { detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    val horizontal = size.width * (scale - 1) / 2
                    val vertical = size.height * (scale - 1) / 2
                    x = if (horizontal > 0) (x + pan.x / horizontal).coerceIn(-1f, 1f) else 0f
                    y = if (vertical > 0) (y + pan.y / vertical).coerceIn(-1f, 1f) else 0f
                    model.imageTransform(item.id, scale, x, y)
                } }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = x * size.width * (scale - 1) / 2; translationY = y * size.height * (scale - 1) / 2 })
            else -> CircularProgressIndicator()
        }
    }
}
private fun mediaTime(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) }
