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

@Composable internal fun WelcomeScreen(onCreate: () -> Unit, onRestore: () -> Unit) {
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
        Text("The calculator appearance is discreet. CalVault is still visible in Android settings. Adding files requests removal of their originals after verification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun SetupScreen(busy: Boolean, onCreate: (String, String) -> Unit, onBack: () -> Unit) {
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
                Text("Choose 3–9 digits. To unlock, enter your PIN in the calculator and hold the equals button.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PinField("Create PIN", pin) { pin = it }
                PinField("Confirm PIN", confirmation) { confirmation = it }
                Text("A normal tap on equals always calculates. An accessible Unlock action is also available in the menu.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { step = 1 }, enabled = pin.length in 3..9 && pin == confirmation, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
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

@Composable internal fun CalculatorScreen(state: UiState, model: VaultViewModel) {
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
    fun unlock(forceRepair: Boolean = false) {
        if (expression.length in 3..12 && expression.all { it in '0'..'9' }) {
            model.unlock(expression, forceRepair); expression = ""; history = ""; error = false
        } else model.message("Enter your PIN, then hold equals. New PINs use 3–9 digits.")
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
                    DropdownMenuItem(text = { Text("Unlock and repair") }, onClick = { menu = false; unlock(true) })
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
                        val bg = if (key == "=") MaterialTheme.colorScheme.primary else if (operator) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceVariant
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(bg)
                            .combinedClickable(onClick = { input(key) }, onLongClick = if (key == "=") ({ unlock() }) else null,
                                onLongClickLabel = if (key == "=") "Unlock vault using entered PIN" else null)
                            .semantics { contentDescription = when (key) { "⌫" -> "Backspace"; "±" -> "Change sign"; "AC" -> "Clear"; "=" -> "Equals"; else -> key } },
                            contentAlignment = Alignment.Center) {
                            if (key == "⌫") Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text(key, fontSize = if (key == "AC") 20.sp else 28.sp,
                                color = if (key == "=") MaterialTheme.colorScheme.onPrimary else if (operator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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

@Composable internal fun RecoveryScreen(restoring: Boolean, busy: Boolean, onBack: () -> Unit, onRecover: (String, String) -> Unit) {
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
            Button(onClick = { onRecover(key, pin) }, enabled = pin.length in 3..9 && pin == confirm && key.filter { it.isLetterOrDigit() }.length == 72 && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (restoring) "Restore and verify" else "Recover vault") }
            Text("Without the recovery key or a working PIN, encrypted files cannot be recovered. Clearing app data removes your local vault.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter { c -> c in '0'..'9' }.take(9)) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false))
}
@Composable internal fun Header(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}
@Composable internal fun InfoCard(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
}
internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return "${DecimalFormat("0.#").format(value)} ${units[unit]}"
}
