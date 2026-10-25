package dev.calvault

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.calvault.core.AttemptLimiter
import dev.calvault.core.Vault
import dev.calvault.core.VaultItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicLong

enum class Screen { WELCOME, SETUP, CALCULATOR, VAULT, SETTINGS, VIEWER, RECOVER, RESTORE }
data class ImportResult(val id: String?, val name: String, val uri: Uri, val detail: String)
data class UiState(
    val exists: Boolean = false, val screen: Screen = Screen.WELCOME,
    val items: List<VaultItem> = emptyList(), val busy: Boolean = false,
    val progress: String = "", val message: String? = null,
    val viewing: VaultItem? = null, val pending: Boolean = false,
    val imports: List<ImportResult> = emptyList(), val revision: Long = 0,
)
private sealed interface Pending {
    data class Import(val uris: List<Uri>) : Pending
    data class Export(val id: String, val uri: Uri) : Pending
    data class Backup(val uri: Uri) : Pending
    data class Restore(val uri: Uri) : Pending
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val app = app
    val vault = Vault(File(app.noBackupFilesDir, "vault"), AndroidDeviceKey())
    private val limiter = AttemptLimiter(File(app.noBackupFilesDir, "attempts.bin"), {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong()
    }, SystemClock::elapsedRealtime)
    private val _ui = MutableStateFlow(UiState(exists = vault.exists, screen = if (vault.exists) Screen.CALCULATOR else Screen.WELCOME))
    val ui = _ui.asStateFlow()
    private val epoch = AtomicLong()
    private var job: Job? = null
    @Volatile private var pending: Pending? = null
    private var pickerExportId: String? = null

    fun message(text: String) { _ui.update { it.copy(message = text) } }
    fun clearMessage() { _ui.update { it.copy(message = null) } }
    fun navigate(screen: Screen) {
        if (screen in setOf(Screen.VAULT, Screen.SETTINGS, Screen.VIEWER) && !vault.unlocked) return
        _ui.update { it.copy(screen = screen, viewing = if (screen == Screen.VIEWER) it.viewing else null) }
    }
    fun lock() {
        epoch.incrementAndGet()
        vault.lock()
        job?.cancel()
        _ui.value = UiState(exists = vault.exists, screen = if (vault.exists) Screen.CALCULATOR else Screen.WELCOME,
            pending = pending != null, revision = _ui.value.revision + 1)
    }
    fun back() {
        when (_ui.value.screen) {
            Screen.VAULT -> lock()
            Screen.VIEWER, Screen.SETTINGS -> navigate(Screen.VAULT)
            else -> { pending = null; lock() }
        }
    }
    private fun task(label: String, failure: String? = null, action: () -> Unit) {
        if (_ui.value.busy) return
        val ticket = epoch.get()
        _ui.update { it.copy(busy = true, progress = label, message = null) }
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                if (ticket == epoch.get()) {
                    val entries = if (vault.unlocked) withContext(Dispatchers.IO) { vault.items() } else emptyList()
                    if (ticket == epoch.get()) _ui.update { it.copy(exists = vault.exists, items = entries, busy = false, progress = "") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == epoch.get()) _ui.update { it.copy(busy = false, progress = "", message = failure ?: safeError(e)) }
            } finally {
                // A lock may race the final atomic commit of setup/restore. Reflect initialized state only.
                if (vault.exists && !_ui.value.exists) _ui.update { it.copy(exists = true, screen = Screen.CALCULATOR, busy = false, progress = "") }
            }
        }
    }
    private fun safeError(e: Exception): String = when (e) {
        is SecurityException -> "Access was not granted. Your vault files are unchanged."
        is java.io.IOException -> "The file could not be read or written. Check available space and try again."
        else -> e.message?.take(180) ?: "The operation could not be completed."
    }
    private fun checkDelay() {
        val remaining = limiter.remainingMillis()
        check(remaining == 0L) { "Try again in ${(remaining + 999) / 1000} seconds." }
    }
    fun create(pin: String, recovery: String) = task("Creating your encrypted vault…") {
        vault.create(pin.toCharArray(), recovery)
        _ui.update { if (vault.unlocked) it.copy(screen = Screen.VAULT) else it }
    }
    fun unlock(pin: String) = task("Unlocking…") {
        checkDelay()
        try { vault.unlock(pin.toCharArray()); limiter.success() }
        catch (e: Exception) {
            if (!vault.unlocked && e.message?.startsWith("Vault locked") != true) limiter.failure()
            error("Unable to unlock. Check your PIN, or use your recovery key.")
        }
        _ui.update { if (vault.unlocked) it.copy(screen = Screen.VAULT) else it }
        processPending()
    }
    fun recover(secret: String, pin: String) = task("Recovering your vault…") {
        checkDelay()
        try { vault.recover(secret, pin.toCharArray()); limiter.success() }
        catch (e: Exception) { limiter.failure(); error("Recovery failed. Check your key and try again.") }
        _ui.update { if (vault.unlocked) it.copy(screen = Screen.VAULT) else it }
        processPending()
    }
    fun changePin(pin: String) = task("Updating your PIN…") {
        vault.changePin(pin.toCharArray())
        message("PIN updated. Your recovery key stays the same.")
    }
    fun view(item: VaultItem) { if (vault.unlocked) _ui.update { it.copy(screen = Screen.VIEWER, viewing = item) } }
    fun rename(item: VaultItem, name: String, folder: String) = task("Updating file…") { vault.rename(item.id, name.trim(), folder.trim()) }
    fun delete(ids: Set<String>) = task("Removing encrypted files…") {
        vault.delete(ids)
        _ui.update { it.copy(imports = it.imports.filterNot { result -> result.id in ids }) }
    }
    fun dismissImports() { _ui.update { it.copy(imports = emptyList()) } }
    fun refresh() = task("Loading vault…") {}
    fun preparePicker(exportId: String? = null) {
        pickerExportId = exportId
        lock()
    }
    fun selectedImports(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending = Pending.Import(uris.take(100))
        _ui.update { it.copy(pending = true, message = "Unlock to import ${uris.size.coerceAtMost(100)} selected file(s).") }
    }
    fun selectedExport(uri: Uri?) {
        val id = pickerExportId
        pickerExportId = null
        if (uri == null || id == null) return
        pending = Pending.Export(id, uri)
        _ui.update { it.copy(pending = true, message = "Unlock to save a decrypted copy to your chosen destination.") }
    }
    fun selectedBackup(uri: Uri?) {
        if (uri == null) return
        pending = Pending.Backup(uri)
        _ui.update { it.copy(pending = true, message = "Unlock to create your encrypted backup.") }
    }
    fun selectedRestore(uri: Uri?) {
        if (uri == null || vault.exists) return
        pending = Pending.Restore(uri)
        _ui.update { it.copy(screen = Screen.RESTORE, pending = true) }
    }
    fun restore(secret: String, pin: String) = task("Restoring and verifying backup…", "Restore failed. Check the recovery key, backup, and available storage.") {
        val operation = pending as? Pending.Restore ?: error("Select a backup first")
        val ticket = epoch.get()
        resolverInput(operation.uri, ticket).use { vault.restoreBackup(it, secret, pin.toCharArray()) }
        pending = null
        _ui.update { if (vault.unlocked) it.copy(screen = Screen.VAULT, pending = false) else it }
    }
    private fun processPending() {
        val operation = pending ?: return
        val ticket = epoch.get()
        check(vault.unlocked)
        when (operation) {
            is Pending.Import -> {
                val outcomes = operation.uris.mapIndexed { index, uri ->
                    check(ticket == epoch.get()) { "Vault locked" }
                    val name = displayName(uri)
                    val outcome = try {
                        _ui.update { it.copy(progress = "Importing ${index + 1} of ${operation.uris.size}…") }
                        val item = resolverInput(uri, ticket).use { vault.importFile(name, app.contentResolver.getType(uri) ?: "application/octet-stream", "", it) }
                        ImportResult(item.id, name, uri, "Encrypted copy verified. Original remains.")
                    } catch (e: Exception) {
                        check(ticket == epoch.get()) { "Vault locked" }
                        ImportResult(null, name, uri, "Import failed. Original remains.")
                    }
                    // Advance the queue after each completed object, including if a lock raced its return.
                    pending = operation.uris.drop(index + 1).takeIf { it.isNotEmpty() }?.let { Pending.Import(it) }
                    outcome
                }
                check(ticket == epoch.get()) { "Vault locked" }
                _ui.update { it.copy(imports = outcomes) }
                message("${outcomes.count { it.id != null }} of ${outcomes.size} files imported and verified.")
            }
            is Pending.Export -> {
                try {
                    checkNotNull(app.contentResolver.openOutputStream(operation.uri, "wt")) { "Destination unavailable" }.use { vault.export(operation.id, it) }
                    message("Decrypted copy saved outside the vault.")
                } catch (e: Exception) { cleanup(operation.uri); pending = null; throw e }
            }
            is Pending.Backup -> {
                try {
                    checkNotNull(app.contentResolver.openOutputStream(operation.uri, "wt")) { "Destination unavailable" }.use(vault::writeBackup)
                    resolverInput(operation.uri, ticket).use(vault::verifyBackup)
                    message("Encrypted backup saved and verified. Keep your recovery key separately.")
                } catch (e: Exception) { cleanup(operation.uri); pending = null; throw e }
            }
            is Pending.Restore -> return
        }
        pending = null
        _ui.update { it.copy(pending = false) }
    }
    fun deleteOriginal(uri: Uri) = task("Removing original…") {
        val resolver = app.contentResolver
        val writable = app.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val supported = if (DocumentsContract.isDocumentUri(app, uri)) {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                it.moveToFirst() && (it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
            } == true
        } else false
        if (writable && supported) {
            val id = _ui.value.imports.firstOrNull { it.uri == uri }?.id ?: error("Import and verify this file again before removing the original.")
            resolverInput(uri, epoch.get()).use { vault.verifyOriginal(id, it) }
            check(vault.unlocked) { "Vault locked" }
            check(DocumentsContract.deleteDocument(resolver, uri)) { "The provider did not confirm removal. Your encrypted copy is safe." }
            _ui.update { state -> state.copy(imports = state.imports.map { if (it.uri == uri) it.copy(detail = "Original removal confirmed by its provider.") else it }) }
            message("Original removed. Other copies, trash, and cloud backups may still exist.")
        } else message("This source does not allow removal here. Remove the original in your gallery or file app. Your encrypted copy is safe.")
    }
    private fun resolverInput(uri: Uri, ticket: Long): java.io.InputStream {
        val input = checkNotNull(app.contentResolver.openInputStream(uri)) { "Source unavailable" }
        return object : FilterInputStream(input) {
            private fun active() { check(ticket == epoch.get()) { "Vault locked" } }
            override fun read(): Int { active(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { active(); return `in`.read(b, off, len) }
        }
    }
    private fun displayName(uri: Uri): String {
        val raw = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported file"
        return raw.replace('/', '_').replace('\\', '_').replace("\u0000", "").take(240).ifBlank { "Imported file" }
    }
    private fun cleanup(uri: Uri) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
    override fun onCleared() { vault.lock(); super.onCleared() }
}
