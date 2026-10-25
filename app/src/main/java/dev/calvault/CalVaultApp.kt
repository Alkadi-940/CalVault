@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.calvault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.calvault.core.StorageMode
import dev.calvault.core.FolderPath
import java.time.LocalDate

internal val Ink = Color(0xFF111318)

private class MoveFilePicker : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

@Composable fun CalVaultApp(model: VaultViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var adding by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(MoveFilePicker(), model::selectedImports)
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), model::selectedBackup)
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::selectedRestore)
    val retrieve = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::selectedRetrieve)
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), model::selectedTree)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { model.consentResult(it.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(state.consent?.token) {
        state.consent?.let { event -> model.consentLaunched(); consent.launch(event.request) }
    }
    LaunchedEffect(state.tree?.token) {
        state.tree?.let { event -> model.treeLaunched(); tree.launch(event.hint.takeIf { it.isNotBlank() }?.let(Uri::parse)) }
    }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it, duration = SnackbarDuration.Long); model.clearMessage() } }
    LaunchedEffect(state.screen) { focus.clearFocus(); keyboard?.hide() }
    LaunchedEffect(state.revision) { adding = false; newFolder = false }
    BackHandler(state.screen !in setOf(Screen.CALCULATOR, Screen.WELCOME)) { model.back() }
    val dark = when (settings.theme) { ThemeStyle.SYSTEM -> isSystemInDarkTheme(); ThemeStyle.LIGHT -> false; else -> true }
    val activity = LocalActivity.current
    SideEffect { activity?.window?.let { window -> WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    val background = if (!dark) Color(0xFFF7F6F2) else if (settings.theme == ThemeStyle.AMOLED) Color.Black else Ink
    val palette = remember(settings.accent, dark, background) {
        var accent = settings.accent or 0xFF000000.toInt()
        val backdrop = if (dark) if (settings.theme == ThemeStyle.AMOLED) 0xFF000000.toInt() else 0xFF111318.toInt() else 0xFFF7F6F2.toInt()
        repeat(40) { if (ColorUtils.calculateContrast(accent, backdrop) < 4.5) accent = ColorUtils.blendARGB(accent, if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK, .12f) }
        val onAccent = if (ColorUtils.calculateContrast(android.graphics.Color.WHITE, accent) > ColorUtils.calculateContrast(android.graphics.Color.BLACK, accent)) Color.White else Color.Black
        val surface = if (dark) Color(0xFF1C2027) else Color(0xFFEEEEE8)
        if (dark) darkColorScheme(primary = Color(accent), onPrimary = onAccent, primaryContainer = Color(ColorUtils.blendARGB(backdrop, accent, .28f)), onPrimaryContainer = Color(0xFFF4F2ED), secondary = Color(accent), background = background,
            surface = background, surfaceVariant = surface, surfaceContainer = surface, secondaryContainer = Color(ColorUtils.blendARGB(backdrop, accent, .2f)), onSecondaryContainer = Color(0xFFF4F2ED),
            onBackground = Color(0xFFF4F2ED), onSurface = Color(0xFFF4F2ED), onSurfaceVariant = Color(0xFFADB5C2), outlineVariant = Color(0xFF41454D))
        else lightColorScheme(primary = Color(accent), onPrimary = onAccent, primaryContainer = Color(ColorUtils.blendARGB(backdrop, accent, .18f)), onPrimaryContainer = Color(0xFF17201B), secondary = Color(accent), background = background,
            surface = background, surfaceVariant = surface, surfaceContainer = surface, secondaryContainer = Color(ColorUtils.blendARGB(backdrop, accent, .15f)), onSecondaryContainer = Color(0xFF17201B), outlineVariant = Color(0xFFD3D4CB))
    }
    MaterialTheme(colorScheme = palette) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = background) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).imePadding()) {
                key(state.revision) {
                    when (state.screen) {
                        Screen.WELCOME -> WelcomeScreen({ model.navigate(Screen.SETUP) }, { restore.launch(arrayOf("*/*")) })
                        Screen.SETUP -> SetupScreen(state.busy, model::create, model::back)
                        Screen.CALCULATOR -> CalculatorScreen(state, model)
                        Screen.RECOVER, Screen.RESTORE -> RecoveryScreen(state.screen == Screen.RESTORE, state.busy, model::back,
                            if (state.screen == Screen.RESTORE) model::restore else model::recover)
                        Screen.VAULT -> key(state.folder) { VaultBrowser(state, settings, model, { adding = true }, { newFolder = true }) }
                        Screen.TRASH -> RecycleBinScreen(state, settings, model)
                        Screen.SETTINGS -> SettingsScreen(state, settings, model,
                            onBackup = { model.saveBookmarkNow(); backup.launch("CalVault-${LocalDate.now()}.calvault") },
                            onRetrieve = { model.saveBookmarkNow(); retrieve.launch(arrayOf("*/*")) })
                        Screen.VIEWER -> MediaViewer(state, settings, model)
                        Screen.RETRIEVE -> RetrieveScreen(state.busy, model)
                    }
                }
                if (state.busy) Surface(onClick = {}, modifier = Modifier.fillMaxSize(), color = background.copy(alpha = .96f)) {
                    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(); Spacer(Modifier.height(24.dp)); Text(state.progress)
                        Spacer(Modifier.height(12.dp)); Text("Keep the app open until this finishes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = model::lock) { Text("Cancel and lock") }
                    }
                }
            }
        }
        if (adding && state.screen == Screen.VAULT) {
            var folder by remember { mutableStateOf(state.folder ?: state.folders.firstOrNull().orEmpty()) }
            var mode by remember { mutableStateOf(if (settings.importPolicy == ImportPolicy.HIDDEN) StorageMode.HIDDEN else StorageMode.ENCRYPTED) }
            var folderMenu by remember { mutableStateOf(false) }
            ModalBottomSheet(onDismissRequest = { adding = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Hide files", style = MaterialTheme.typography.headlineSmall)
                    Text("Verified files are moved into this vault. Android may ask you to confirm removal of the originals.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton(onClick = { folderMenu = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text(folder.takeIf { it.isNotBlank() }?.let(FolderPath::display) ?: "Choose folder") }
                        DropdownMenu(folderMenu, { folderMenu = false }) { state.folders.sortedBy(FolderPath::display).forEach { name -> DropdownMenuItem(text = { Text(FolderPath.display(name)) }, onClick = { folder = name; folderMenu = false }) } }
                    }
                    if (state.folders.isEmpty()) TextButton(onClick = { adding = false; newFolder = true }) { Text("Create a destination folder") }
                    if (settings.importPolicy == ImportPolicy.ASK) {
                        Text("Select an encryption mode", style = MaterialTheme.typography.titleMedium)
                        StorageMode.entries.forEach { choice ->
                            Row(Modifier.fillMaxWidth().clickable { mode = choice }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(mode == choice, { mode = choice }); Text(if (choice == StorageMode.ENCRYPTED) "Encryption" else "Hidden")
                            }
                        }
                    } else Text("Mode: ${if (mode == StorageMode.ENCRYPTED) "Encryption" else "Hidden"}", style = MaterialTheme.typography.labelLarge)
                    Text("Both modes keep file encryption. Hidden is a discreet storage label; it does not store readable file contents.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { adding = false; model.preparePicker(folder, mode); picker.launch(arrayOf("image/*", "video/*")) }, modifier = Modifier.fillMaxWidth(), enabled = folder.isNotBlank()) {
                        Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(10.dp)); Text("Choose photos & videos")
                    }
                    OutlinedButton(onClick = { adding = false; model.preparePicker(folder, mode); picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = folder.isNotBlank()) {
                        Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(10.dp)); Text("Choose any files")
                    }
                    Text("Select multiple items in Android's picker. Files whose originals cannot be removed remain clearly marked as unfinished.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (newFolder && vaultScreen(state.screen)) {
            var name by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("Create folder") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Inside: ${state.folder?.let(FolderPath::display) ?: "My vault"}", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    OutlinedTextField(name, { name = it.take(120) }, label = { Text("Folder name") }, singleLine = true)
                } },
                confirmButton = { TextButton(onClick = { newFolder = false; model.createFolder(name) }, enabled = name.isNotBlank()) { Text("Create") } },
                dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } })
        }
    }
}
private fun vaultScreen(screen: Screen) = screen in setOf(Screen.VAULT, Screen.TRASH, Screen.SETTINGS, Screen.VIEWER)
