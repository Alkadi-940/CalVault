package dev.calvault

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.calvault.core.FolderPath

@Composable internal fun SettingsScreen(state: UiState, value: AppSettings, model: VaultViewModel, onBackup: () -> Unit, onRetrieve: () -> Unit) {
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    val sections = listOf("Security", "Storage", "Video", "Images", "Appearance", "Retrieval", "Migration")
    Column(Modifier.fillMaxSize()) {
        Header("Settings", model::back)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sections.forEach { section ->
                val requester = remember { BringIntoViewRequester() }
                LaunchedEffect(state.settingsSection) { if (state.settingsSection == section) requester.bringIntoView() }
                FilterChip(state.settingsSection == section, { model.settingsSection(section) }, label = { Text(section) }, modifier = Modifier.bringIntoViewRequester(requester))
            }
        }
        key(state.settingsSection) {
            val scroll = rememberScrollState(state.settingsScroll)
            LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { model.settingsScroll(it) } }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (state.settingsSection) {
                    "Security" -> {
                        SectionTitle("Your private entrance", "Choose when the vault locks and how you unlock it.")
                        SettingSwitch("Lock when leaving the app", "Turn off to keep the vault open while switching apps. A killed or restarted process still requires authentication.", value.lockOnBackground) { model.updateSettings(value.copy(lockOnBackground = it)) }
                        SettingSwitch("Lock when the screen turns off", "Protect the vault when you lock your phone.", value.lockOnScreenOff) { model.updateSettings(value.copy(lockOnScreenOff = it)) }
                        SettingSwitch("Resume my last location", "After unlocking, restore the folder, filter, selection, viewer, and media position.", value.resumeLastLocation) { model.updateSettings(value.copy(resumeLastLocation = it)) }
                        HorizontalDivider(); Text("Change PIN", style = MaterialTheme.typography.titleLarge)
                        PinField("New PIN", newPin) { newPin = it }; PinField("Confirm new PIN", confirmPin) { confirmPin = it }
                        OutlinedButton(onClick = { model.changePin(newPin); newPin = ""; confirmPin = "" }, enabled = newPin.length in 6..12 && newPin == confirmPin, modifier = Modifier.fillMaxWidth()) { Text("Update PIN") }
                        Text("PIN and recovery-key entry are never restored after closing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "Storage" -> {
                        SectionTitle("Folders and file moves", "New imports request removal of the originals automatically after full verification.")
                        Text("Default encryption mode", style = MaterialTheme.typography.titleMedium)
                        ImportPolicy.entries.forEach { policy -> ChoiceRow(when (policy) { ImportPolicy.ASK -> "Always ask"; ImportPolicy.ENCRYPTED -> "Always Encryption"; ImportPolicy.HIDDEN -> "Always Hidden" }, value.importPolicy == policy) { model.updateSettings(value.copy(importPolicy = policy)) } }
                        Text("Both modes encrypt file contents. Hidden is a separate label and keeps the same encryption protection.", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        val pending = state.items.filter { it.sourceState == dev.calvault.core.SourceState.PENDING_REMOVAL }
                        Text("Unfinished moves: ${pending.size}", style = MaterialTheme.typography.titleMedium)
                        Text("Android or a source provider can require permission or deny deletion. Such files are not reported as hidden; their verified vault copies remain safe until you complete the move.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pending.isNotEmpty()) {
                            Button(onClick = { model.retryRemoval(pending.map { it.id }.toSet()) }, Modifier.fillMaxWidth()) { Text("Retry original removal") }
                            pending.take(10).forEach { Text("${FolderPath.display(it.folder)} / ${it.name}", style = MaterialTheme.typography.bodySmall) }
                        }
                        HorizontalDivider(); Text("Recycle bin", style = MaterialTheme.typography.titleLarge)
                        Text("${state.trash.size} files · ${formatSize(state.trash.sumOf { it.size })}")
                        OutlinedButton(onClick = { model.openTrashFolder(null) }, Modifier.fillMaxWidth()) { Text("Open recycle bin") }
                        Text("Items stay in the bin until you restore them or explicitly choose permanent deletion. Empty bin removes all binned items from this device's vault; existing backups and outside copies are unaffected.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Video" -> {
                        SectionTitle("Video player", "Videos in the current folder form a playlist.")
                        SecondsSlider("Double-tap right: forward", value.seekForwardSeconds, 1..30) { model.updateSettings(value.copy(seekForwardSeconds = it)) }
                        SecondsSlider("Double-tap left: back", value.seekBackSeconds, 1..30) { model.updateSettings(value.copy(seekBackSeconds = it)) }
                        SettingSwitch("Replay current video", "Repeat this video instead of advancing to the next one.", value.videoReplay) { model.updateSettings(value.copy(videoReplay = it)) }
                        SettingSwitch("Shuffle playlist", "Visit every video once before the playlist repeats. Opening a video starts a new cycle from that video.", value.videoShuffle) { model.updateSettings(value.copy(videoShuffle = it)) }
                        Text("Double taps perform timed seeking. Track controls switch videos. Playback position survives rotation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "Images" -> {
                        SectionTitle("Image viewer", "Start a slideshow from any picture to show the images in that folder.")
                        SecondsSlider("Slideshow interval", value.slideshowSeconds, 1..60) { model.updateSettings(value.copy(slideshowSeconds = it)) }
                        SettingSwitch("Shuffle slideshow", "Show every image once before repeating. The current picture starts a new cycle.", value.slideshowShuffle) { model.updateSettings(value.copy(slideshowShuffle = it)) }
                        Text("Slideshow transition", style = MaterialTheme.typography.titleMedium)
                        SlideTransition.entries.forEach { transition -> ChoiceRow(transition.name.lowercase().replaceFirstChar { it.uppercase() }, value.slideTransition == transition) { model.updateSettings(value.copy(slideTransition = transition)) } }
                        Text("Pinch to zoom, drag to pan, and use previous/next to browse. Slideshow context resumes after unlocking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    "Appearance" -> {
                        SectionTitle("Make it yours", "Match your calculator and vault with a shared accent color.")
                        ThemeStyle.entries.forEach { theme -> ChoiceRow(if (theme == ThemeStyle.AMOLED) "AMOLED" else theme.name.lowercase().replaceFirstChar { it.uppercase() }, value.theme == theme) { model.updateSettings(value.copy(theme = theme)) } }
                        Text("Accent color", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            listOf(0xFFF5B976, 0xFFA9D9C0, 0xFF80BFFF, 0xFFD0AEFF, 0xFFFF9EB5, 0xFF65D6C7, 0xFFFFAF75, 0xFFB7DB77, 0xFFFFD875, 0xFFB8BCC9).forEach { color ->
                                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(color.toInt())).border(if (value.accent == color.toInt()) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { model.updateSettings(value.copy(accent = color.toInt())) })
                            }
                        }
                        var hex by remember { mutableStateOf("%06X".format(value.accent and 0xFFFFFF)) }
                        OutlinedTextField(hex, { hex = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(6) }, label = { Text("Custom color RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { model.updateSettings(value.copy(accent = 0xFF000000.toInt() or hex.toInt(16))) }, enabled = hex.length == 6, modifier = Modifier.fillMaxWidth()) { Text("Apply custom color") }
                        Text("Text accents are adjusted for contrast. AMOLED uses a true black background.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Retrieval" -> {
                        SectionTitle("Retrieve lost vault files", "Recovery records reconnect intact encrypted files to a missing or damaged catalog.")
                        SettingSwitch("Automatic retrieval", "Check for verified recoverable files when unlocking. Repair preserves orphaned files and old recovery records.", value.automaticRetrieval) { model.updateSettings(value.copy(automaticRetrieval = it)) }
                        Button(onClick = model::repair, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(10.dp)); Text("Scan and repair now") }
                        Text("Last scan: ${model.vault.lastRepair.recovered} retrieved, ${model.vault.lastRepair.missing} missing, ${model.vault.lastRepair.damaged} damaged records.")
                        OutlinedButton(onClick = onRetrieve, Modifier.fillMaxWidth()) { Text("Retrieve missing files from backup") }
                        Text("Choose a backup from this vault and enter its recovery key. Healthy files are kept, missing/damaged files can be restored, and permanently deleted items are not automatically recreated.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Overwritten or permanently deleted bytes require a backup. If the phone or app data is lost, follow Migration to restore a verified backup.", style = MaterialTheme.typography.bodySmall)
                    }
                    "Migration" -> {
                        SectionTitle("Move to a new device", "Use an encrypted backup and your separately saved recovery key.")
                        MigrationStep(1, "On the old phone", "Open this vault and create an encrypted backup below. Keep CalVault open until the backup is saved and verified.")
                        Button(onClick = onBackup, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.SaveAlt, null); Spacer(Modifier.width(10.dp)); Text("Create encrypted backup") }
                        MigrationStep(2, "Save the recovery key separately", "Use the recovery key saved during setup. It cannot be recreated from your PIN. You need both the backup and its matching key.")
                        MigrationStep(3, "Transfer the .calvault file", "Copy the verified backup to the new phone using USB, an SD card, or a destination you choose. Keep the old phone's vault intact.")
                        MigrationStep(4, "Install CalVault on the new phone", "Use a fresh installation. Tap Restore an encrypted backup on the welcome screen, choose the file, enter its recovery key, and create a new PIN.")
                        MigrationStep(5, "Check your files", "Open photos, play videos, and inspect folders and recycle-bin contents.")
                        MigrationStep(6, "Finish after verification", "Keep a spare verified backup. Remove the old vault only after checking the new copy. Unhide to original locations may need new folder permissions on the new phone.")
                        HorizontalDivider(); Text("Migration preserves contents, folders, storage labels, recycle-bin metadata, and saved browsing/media context. Android URI grants cannot transfer to another device.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp)); Text("CalVault ${BuildConfig.VERSION_NAME} · on-device storage · no Internet permission", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun RetrieveScreen(busy: Boolean, model: VaultViewModel) {
    var secret by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header("Retrieve from backup", model::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Restore missing or damaged files", style = MaterialTheme.typography.headlineSmall)
            Text("Enter this backup's recovery key. The current vault remains in place; healthy files are kept.")
            OutlinedTextField(secret, { secret = it.take(100) }, label = { Text("Recovery key") }, modifier = Modifier.fillMaxWidth(), minLines = 3, visualTransformation = PasswordVisualTransformation())
            Button(onClick = { model.retrieve(secret); secret = "" }, enabled = secret.filter { it.isLetterOrDigit() }.length == 72 && !busy, modifier = Modifier.fillMaxWidth()) { Text("Verify backup and retrieve") }
        }
    }
}
@Composable private fun SectionTitle(title: String, description: String) { Text(title, style = MaterialTheme.typography.headlineSmall); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun SettingSwitch(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, change, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable private fun ChoiceRow(title: String, selected: Boolean, choose: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, choose); Text(title) } }
@Composable private fun SecondsSlider(title: String, seconds: Int, range: IntRange, change: (Int) -> Unit) {
    Text("$title: $seconds s", style = MaterialTheme.typography.titleMedium)
    Slider(seconds.toFloat(), { change(it.toInt().coerceIn(range.first, range.last)) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
}
@Composable private fun MigrationStep(number: Int, title: String, text: String) { Text("$number. $title", style = MaterialTheme.typography.titleMedium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
