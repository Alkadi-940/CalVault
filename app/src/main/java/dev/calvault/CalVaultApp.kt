@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.calvault

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import dev.calvault.core.Calculator
import dev.calvault.core.Crypto
import dev.calvault.core.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.time.LocalDate

private val Amber = Color(0xFFF5B976)
private val Mint = Color(0xFFA9D9C0)
private val Ink = Color(0xFF111318)
private val Slate = Color(0xFF1C2027)
private val Muted = Color(0xFF9DA5B3)
private val darkPalette = darkColorScheme(
    primary = Amber, onPrimary = Ink, secondary = Mint, background = Ink,
    surface = Ink, surfaceContainer = Slate, surfaceVariant = Slate,
    secondaryContainer = Color(0xFF263D33), onSecondaryContainer = Mint,
    outlineVariant = Color(0xFF41454D),
    onBackground = Color(0xFFF4F2ED), onSurface = Color(0xFFF4F2ED), onSurfaceVariant = Muted,
)
private val lightPalette = lightColorScheme(
    primary = Color(0xFF85521C), secondary = Color(0xFF32624C), background = Color(0xFFF7F6F2),
    surface = Color(0xFFF7F6F2), surfaceContainer = Color(0xFFEEEEE8), surfaceVariant = Color(0xFFEEEEE8),
    secondaryContainer = Color(0xFFDDE9DF), onSecondaryContainer = Color(0xFF214B36),
    outlineVariant = Color(0xFFD3D4CB),
)

@Composable
fun CalVaultApp(model: VaultViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    var addMenu by remember { mutableStateOf(false) }
    var exportItem by remember { mutableStateOf<VaultItem?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::selectedImports)
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100), model::selectedImports)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), model::selectedExport)
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), model::selectedBackup)
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::selectedRestore)
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); model.clearMessage() }
    }
    LaunchedEffect(state.revision) { addMenu = false; exportItem = null }
    LaunchedEffect(state.screen) { focus.clearFocus(); keyboard?.hide() }
    BackHandler(enabled = state.screen !in setOf(Screen.CALCULATOR, Screen.WELCOME)) { model.back() }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkPalette else lightPalette) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).imePadding()) {
                // Secrets are never passed through rememberSaveable or navigation SavedStateHandle.
                key(state.revision) {
                    when (state.screen) {
                        Screen.WELCOME -> WelcomeScreen(
                            onCreate = { model.navigate(Screen.SETUP) },
                            onRestore = { model.preparePicker(); restore.launch(arrayOf("*/*")) },
                        )
                        Screen.SETUP -> SetupScreen(state.busy, model::create, model::back)
                        Screen.CALCULATOR -> CalculatorScreen(state, model)
                        Screen.RECOVER, Screen.RESTORE -> RecoveryScreen(
                            restoring = state.screen == Screen.RESTORE, busy = state.busy,
                            onBack = model::back,
                            onRecover = if (state.screen == Screen.RESTORE) model::restore else model::recover,
                        )
                        Screen.VAULT -> VaultScreen(state, model, onAdd = { addMenu = true }, onExport = { exportItem = it })
                        Screen.SETTINGS -> SettingsScreen(state.busy, model, onBackup = {
                            model.preparePicker()
                            backup.launch("CalVault-${LocalDate.now()}.calvault")
                        })
                        Screen.VIEWER -> state.viewing?.let { ViewerScreen(it, model, onExport = { exportItem = it }) }
                    }
                }
                if (state.busy) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background.copy(alpha = .94f)) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(24.dp))
                        Text(state.progress, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text("Keep CalVault open until this finishes.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = model::lock) { Text("Cancel and lock") }
                    }
                }
            }
        }
        if (addMenu && state.screen == Screen.VAULT) ModalBottomSheet(onDismissRequest = { addMenu = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add to your vault", style = MaterialTheme.typography.headlineSmall)
                Text("Files are copied and encrypted. Originals stay where they are until you remove them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { addMenu = false; model.preparePicker(); media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(12.dp)); Text("Choose photos & videos")
                }
                OutlinedButton(onClick = { addMenu = false; model.preparePicker(); files.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(12.dp)); Text("Choose files")
                }
            }
        }
        exportItem?.let { item ->
            AlertDialog(onDismissRequest = { exportItem = null }, title = { Text("Save a decrypted copy?") },
                text = { Text("${item.name} will be readable outside CalVault. Choose a destination you trust.") },
                confirmButton = { TextButton(onClick = { exportItem = null; model.preparePicker(item.id); export.launch(item.name) }) { Text("Choose destination") } },
                dismissButton = { TextButton(onClick = { exportItem = null }) { Text("Cancel") } })
        }
    }
}

@Composable private fun WelcomeScreen(onCreate: () -> Unit, onRestore: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("CALVAULT", letterSpacing = 4.sp, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.size(100.dp).clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Calculate, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text("A little more\nthan a calculator.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Text("Your photos, videos, and files. Encrypted on your phone, behind a calculator that really calculates.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfoCard("Private by design", "No account. No cloud sync. Your vault stays on this device.", Icons.Outlined.Shield)
        InfoCard("A way back in", "Save your separate recovery key and make encrypted backups. Uninstalling removes the local vault.", Icons.Outlined.Key)
        Button(onClick = onCreate, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Create my vault") }
        TextButton(onClick = onRestore, Modifier.fillMaxWidth()) { Text("Restore an encrypted backup") }
        Text("The calculator appearance is discreet. CalVault is still visible in Android settings. Importing leaves originals in place.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SetupScreen(busy: Boolean, onCreate: (String, String) -> Unit, onBack: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val recovery = remember { Crypto.recoverySecret() }
    var group3 by remember { mutableStateOf("") }
    var group8 by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val groups = recovery.split('-')
    Column(Modifier.fillMaxSize()) {
        Header("Set up CalVault", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("STEP ${step + 1} OF 2", style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
            if (step == 0) {
                Text("Your private entrance.", style = MaterialTheme.typography.headlineLarge)
                Text("Choose 6–12 digits. To unlock, enter your PIN in the calculator and hold the equals button.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PinField("Create PIN", pin) { pin = it }
                PinField("Confirm PIN", confirmation) { confirmation = it }
                Text("A normal tap on equals always calculates. An accessible Unlock action is also available in the menu.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { step = 1 }, enabled = pin.length in 6..12 && pin == confirmation, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            } else {
                Text("Keep a way back in.", style = MaterialTheme.typography.headlineLarge)
                Text("Write this recovery key down or save it in a trusted password manager. Keep it separate from your backups. It is shown only during setup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(groups.chunked(3).joinToString("\n") { it.joinToString("  ") }, Modifier.fillMaxWidth().padding(20.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 28.sp, textAlign = TextAlign.Center)
                }
                Text("Anyone with this key and your encrypted data can open it. The key cannot recreate lost files without a backup.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(group3, { group3 = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(8) }, label = { Text("Confirm group 3") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(group8, { group8 = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(8) }, label = { Text("Confirm group 8") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(saved, { saved = it }); Text("I saved my recovery key somewhere safe.", Modifier.weight(1f))
                }
                Button(onClick = { onCreate(pin, recovery) }, enabled = saved && group3 == groups[2] && group8 == groups[7] && !busy, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted vault") }
                TextButton(onClick = { step = 0 }) { Text("Back to PIN") }
            }
        }
    }
}

@Composable private fun CalculatorScreen(state: UiState, model: VaultViewModel) {
    var expression by remember { mutableStateOf("") }
    var history by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    fun input(key: String) {
        if (error) { expression = ""; error = false }
        when (key) {
            "AC" -> { expression = ""; history = "" }
            "⌫" -> expression = expression.dropLast(1)
            "=" -> if (expression.isNotBlank()) {
                val previous = expression
                runCatching { Calculator.evaluate(expression) }.onSuccess { expression = it; history = "$previous =" }.onFailure { error = true }
            }
            "±" -> {
                val match = Regex("[0-9.]+(?:[Ee][+-]?[0-9]+)?%?$").find(expression)
                if (match != null) {
                    val start = match.range.first
                    expression = if (start > 0 && expression[start - 1] == '-' && (start == 1 || expression[start - 2] in "+−×÷*/"))
                        expression.removeRange(start - 1, start) else expression.substring(0, start) + "-" + expression.substring(start)
                }
            }
            else -> if (expression.length < 128) {
                if (key in listOf("+", "−", "×", "÷") && expression.lastOrNull() in listOf('+', '−', '×', '÷')) {
                    expression = expression.dropLast(1) + key
                } else expression += key
            }
        }
    }
    fun unlock() {
        if (expression.length in 6..12 && expression.all { it in '0'..'9' }) {
            model.unlock(expression); expression = ""; history = ""; error = false
        } else model.message("Enter your 6–12 digit PIN, then hold equals.")
    }
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Calculate, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp)); Text("Calculator", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Calculator menu") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Unlock with entered PIN") }, onClick = { menu = false; unlock() })
                    DropdownMenuItem(text = { Text("Recover vault access") }, onClick = { menu = false; expression = ""; model.navigate(Screen.RECOVER) })
                    DropdownMenuItem(text = { Text("How to unlock") }, onClick = { menu = false; model.message("Enter your PIN and hold =. Percent means divide the preceding number by 100.") })
                }
            }
        }
    }
    val display: @Composable (Modifier, Boolean) -> Unit = { modifier, compact ->
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
            Text(if (state.pending) "Unlock to continue your file operation" else "STANDARD", style = MaterialTheme.typography.labelSmall, letterSpacing = if (state.pending) 0.sp else 3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Text(history, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text(if (error) "Cannot calculate" else expression.ifBlank { "0" }, fontSize = if (expression.length > 12) 30.sp else 52.sp,
                fontWeight = FontWeight.Light, textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(if (compact) 12.dp else 32.dp))
        }
    }
    val keypad: @Composable (Modifier) -> Unit = { modifier ->
        val rows = listOf(listOf("AC", "⌫", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("±", "0", ".", "="))
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        val operator = key in listOf("÷", "×", "−", "+", "=")
                        val bg = if (key == "=") Amber else if (operator) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceVariant
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(bg)
                            .combinedClickable(onClick = { input(key) }, onLongClick = if (key == "=") ({ unlock() }) else null,
                                onLongClickLabel = if (key == "=") "Unlock vault using entered PIN" else null)
                            .semantics { contentDescription = when (key) { "⌫" -> "Backspace"; "±" -> "Change sign"; "AC" -> "Clear"; "=" -> "Equals"; else -> key } },
                            contentAlignment = Alignment.Center) {
                            if (key == "⌫") Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text(key, fontSize = if (key == "AC") 20.sp else 28.sp,
                                color = if (key == "=") Ink else if (operator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (key == "=") FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) { header(); display(Modifier.weight(1f), true) }
                keypad(Modifier.weight(1.2f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                display(Modifier.weight(1f), false)
                keypad(Modifier.heightIn(max = 420.dp).weight(1.5f))
            }
        }
    }
}

@Composable private fun VaultScreen(state: UiState, model: VaultViewModel, onAdd: () -> Unit, onExport: (VaultItem) -> Unit) {
    var category by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<VaultItem?>(null) }
    var delete by remember { mutableStateOf<VaultItem?>(null) }
    var removeSource by remember { mutableStateOf<ImportResult?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleteSelection by remember { mutableStateOf(false) }
    val filtered = state.items.filter {
        (folder == null || it.folder == folder) && it.name.contains(search, true) && when (category) {
            "Photos" -> it.mime.startsWith("image/")
            "Videos" -> it.mime.startsWith("video/")
            "Files" -> !it.mime.startsWith("image/") && !it.mime.startsWith("video/")
            else -> true
        }
    }.sortedByDescending { it.created }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected.isEmpty()) Text("CALVAULT", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 3.sp, style = MaterialTheme.typography.labelMedium)
                    else {
                        TextButton(onClick = { selected = emptySet() }) { Text("Cancel") }
                        Text("${selected.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { deleteSelection = true }) { Icon(Icons.Outlined.Delete, "Delete selected files") }
                    }
                    IconButton(onClick = { model.navigate(Screen.SETTINGS) }) { Icon(Icons.Outlined.Settings, "Security and backup") }
                    IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock vault") }
                }
                Spacer(Modifier.height(18.dp))
                Text("My vault", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("A private space for what matters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondary.copy(alpha = .10f)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) { Text("Encrypted. On your device.", fontWeight = FontWeight.Medium); Text("${state.items.size} files · ${formatSize(state.items.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search your files") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(18.dp), singleLine = true) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Photos", "Videos", "Files").forEach { value -> FilterChip(category == value, { category = value }, label = { Text(value) }) }
                }
            }
            val folders = state.items.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
            if (folders.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(folder == null, { folder = null }, label = { Text("All folders") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp)) })
                    folders.forEach { value -> FilterChip(folder == value, { folder = value }, label = { Text(value) }) }
                }
            }
            if (state.imports.isNotEmpty()) item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Import results", Modifier.weight(1f), fontWeight = FontWeight.Medium); TextButton(onClick = model::dismissImports) { Text("Dismiss") } }
                        state.imports.forEach { result ->
                            Text(result.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (result.id != null && result.detail.contains("Original remains")) TextButton(onClick = { removeSource = result }) { Text("Remove original…") }
                        }
                    }
                }
            }
            item { Text(if (search.isBlank()) "YOUR FILES" else "SEARCH RESULTS", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.items.isEmpty()) "Your private space is ready." else "No matching files.", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.items.isEmpty()) "Add photos, videos, or files to get started." else "Try another search or category.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(filtered, key = { it.id }) { item ->
                var menu by remember { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(20.dp), color = if (item.id in selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(
                        onClick = { if (selected.isEmpty()) model.view(item) else selected = if (item.id in selected) selected - item.id else selected + item.id },
                        onLongClick = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                        onLongClickLabel = "Select file",
                    )) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                            Icon(when { item.id in selected -> Icons.Outlined.Check; item.mime.startsWith("image/") -> Icons.Outlined.Image; item.mime.startsWith("video/") -> Icons.Outlined.PlayCircle; else -> Icons.AutoMirrored.Outlined.InsertDriveFile }, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Spacer(Modifier.height(4.dp)); Text(listOf(formatSize(item.size), item.folder).filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${item.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Select file") }, onClick = { menu = false; selected = selected + item.id })
                                DropdownMenuItem(text = { Text("Rename / move to folder") }, onClick = { menu = false; rename = item })
                                DropdownMenuItem(text = { Text("Export a copy") }, onClick = { menu = false; onExport(item) })
                                DropdownMenuItem(text = { Text("Delete from vault") }, onClick = { menu = false; delete = item })
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add files") }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp), containerColor = MaterialTheme.colorScheme.primary)
    }
    rename?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        var target by remember(item.id) { mutableStateOf(item.folder) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename or move") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(240) }, label = { Text("Filename") }, singleLine = true)
                OutlinedTextField(target, { target = it.take(120) }, label = { Text("Folder (optional)") }, supportingText = { Text("Enter a folder name to create it or move this file.") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { rename = null; model.rename(item, name, target) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    delete?.let { item -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete from vault?") }, text = { Text("${item.name} will be removed from this vault. Existing backups and exported copies are unaffected. This cannot be undone here.") },
        confirmButton = { TextButton(onClick = { delete = null; model.delete(setOf(item.id)) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    if (deleteSelection) AlertDialog(onDismissRequest = { deleteSelection = false }, title = { Text("Delete ${selected.size} files?") },
        text = { Text("Selected files will be removed from this vault. Existing backups and exported copies are unaffected. This cannot be undone here.") },
        confirmButton = { TextButton(onClick = { val ids = selected; selected = emptySet(); deleteSelection = false; model.delete(ids) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteSelection = false }) { Text("Cancel") } })
    removeSource?.let { result -> AlertDialog(onDismissRequest = { removeSource = null }, title = { Text("Remove the original?") }, text = { Text("Your encrypted copy of ${result.name} has been verified. If its provider permits, remove the original. Other copies, gallery trash, and cloud backups may remain.") },
        confirmButton = { TextButton(onClick = { removeSource = null; model.deleteOriginal(result.uri) }) { Text("Remove original") } }, dismissButton = { TextButton(onClick = { removeSource = null }) { Text("Keep original") } }) }
}

@Composable private fun RecoveryScreen(restoring: Boolean, busy: Boolean, onBack: () -> Unit, onRecover: (String, String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header(if (restoring) "Restore backup" else "Recover access", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Outlined.Key, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (restoring) "Bring your files home." else "A fresh start for your PIN.", style = MaterialTheme.typography.headlineLarge)
            Text(if (restoring) "Use the recovery key matching this backup. Your original device and old PIN are not needed." else "Your recovery key unlocks the encrypted files still on this device. Choose a new PIN below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(key, { key = it.take(100) }, label = { Text("Recovery key") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
            PinField("New PIN", pin) { pin = it }
            PinField("Confirm new PIN", confirm) { confirm = it }
            Button(onClick = { onRecover(key, pin) }, enabled = pin.length in 6..12 && pin == confirm && key.filter { it.isLetterOrDigit() }.length == 72 && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (restoring) "Restore and verify" else "Recover vault") }
            Text("Without the recovery key or a working PIN, encrypted files cannot be recovered. Clearing app data removes your local vault.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun SettingsScreen(busy: Boolean, model: VaultViewModel, onBackup: () -> Unit) {
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("Security & backup", model::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            InfoCard("Only on this device", "No accounts, network access, or automatic cloud backups. CalVault locks when you leave the app.", Icons.Outlined.Shield)
            Text("Your safety net", style = MaterialTheme.typography.titleLarge)
            Text("Create a backup before changing devices or uninstalling. Your saved recovery key is required to restore it. Store the key separately.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onBackup, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(12.dp)); Text("Create encrypted backup") }
            Text("Restore is available on the welcome screen of a new installation. This version never replaces a populated vault.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Change your PIN", style = MaterialTheme.typography.titleLarge)
            PinField("New PIN", newPin) { newPin = it }
            PinField("Confirm new PIN", confirm) { confirm = it }
            OutlinedButton(onClick = { model.changePin(newPin); newPin = ""; confirm = "" }, enabled = newPin.length in 6..12 && newPin == confirm && !busy, modifier = Modifier.fillMaxWidth()) { Text("Update PIN") }
            HorizontalDivider()
            Text("About your privacy", style = MaterialTheme.typography.titleLarge)
            Text("Files and names are encrypted in private app storage. Imported originals remain outside CalVault until removed. Exported files, old backups, gallery trash, and other copies have their own protection. A compromised phone can bypass app-level protections.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("CalVault ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ViewerScreen(item: VaultItem, model: VaultViewModel, onExport: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header(item.name, model::back, trailing = { IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock vault") } })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                item.mime.startsWith("image/") -> PhotoPreview(item, model)
                item.mime.startsWith("video/") -> VideoPreview(item, model)
                else -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Stored securely", style = MaterialTheme.typography.headlineSmall)
                    Text("This file type has no in-app preview. Export a copy to open it in another app.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${formatSize(item.size)} · ${item.mime}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onExport, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(12.dp)); Text("Export a copy") }
        }
    }
}

@Composable private fun PhotoPreview(item: VaultItem, model: VaultViewModel) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(item.id) {
        try {
            val loaded = withContext(Dispatchers.IO) { decodeVaultPhoto(model.vault, item.id) }
            bitmap = loaded
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = "This image cannot be previewed. You can still export it." }
    }
    DisposableEffect(item.id) { onDispose { bitmap?.recycle(); bitmap = null } }
    when {
        error != null -> Text(error!!, Modifier.padding(24.dp), textAlign = TextAlign.Center)
        bitmap != null -> Image(bitmap!!.asImageBitmap(), item.name, Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))
            .pointerInput(Unit) { detectTransformGestures { _, _, scale, _ -> zoom = (zoom * scale).coerceIn(1f, 5f) } }
            .graphicsLayer { scaleX = zoom; scaleY = zoom })
        else -> CircularProgressIndicator()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoPreview(item: VaultItem, model: VaultViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var playbackError by remember(item.id) { mutableStateOf(false) }
    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(VaultDataSource.Factory(model.vault, item.id))
                .createMediaSource(MediaItem.fromUri("vault://${item.id}".toUri())))
            prepare()
            playWhenReady = false
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackError = true }
            })
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
            if (event == Lifecycle.Event.ON_STOP) player.stop()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.stop(); player.clearMediaItems(); player.release() }
    }
    if (playbackError) Text("This video cannot be played. You can still export it.", Modifier.padding(24.dp), textAlign = TextAlign.Center)
    else AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
        modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
}

@Composable private fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter { c -> c in '0'..'9' }.take(12)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false))
}
@Composable private fun Header(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}
@Composable private fun InfoCard(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
}
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "${DecimalFormat("0.#").format(value)} ${units[unit]}"
}
