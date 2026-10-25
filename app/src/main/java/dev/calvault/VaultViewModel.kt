package dev.calvault

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.calvault.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicLong

enum class Screen { WELCOME, SETUP, CALCULATOR, VAULT, TRASH, SETTINGS, VIEWER, RECOVER, RESTORE, RETRIEVE }
data class ImportResult(val id: String?, val name: String, val uri: Uri, val detail: String)
data class ConsentEvent(val token: Long, val request: IntentSenderRequest)
data class TreeEvent(val token: Long, val hint: String = "")
data class UiState(
    val exists: Boolean = false, val screen: Screen = Screen.WELCOME,
    val items: List<VaultItem> = emptyList(), val trash: List<VaultItem> = emptyList(), val folders: List<String> = emptyList(),
    val trashFolders: List<TrashedFolder> = emptyList(), val trashParent: String? = null,
    val folder: String? = null, val category: String = "All", val search: String = "", val selected: Set<String> = emptySet(),
    val scrollIndex: Int = 0, val scrollOffset: Int = 0,
    val busy: Boolean = false, val progress: String = "", val message: String? = null,
    val viewing: VaultItem? = null, val videoPosition: Long = 0, val resumePlaying: Boolean = false, val videoOrder: List<String> = emptyList(),
    val photoSlideshow: Boolean = false, val photoOrder: List<String> = emptyList(), val photoIndex: Int = 0,
    val imageScale: Float = 1f, val imagePanX: Float = 0f, val imagePanY: Float = 0f,
    val settingsSection: String = "Security", val settingsReturn: Screen = Screen.VAULT, val settingsScroll: Int = 0,
    val pending: Boolean = false, val imports: List<ImportResult> = emptyList(),
    val consent: ConsentEvent? = null, val tree: TreeEvent? = null,
    val foreground: Boolean = true, val revision: Long = 0,
)
private sealed interface Pending {
    data class Import(val uris: List<Uri>, val folder: String?, val mode: StorageMode?) : Pending
    data class Unhide(val ids: Set<String>, val original: Boolean, val tree: Uri? = null) : Pending
    data class Backup(val uri: Uri) : Pending
    data class Restore(val uri: Uri) : Pending
    data class Retrieve(val uri: Uri) : Pending
    data class ConsentResult(val ids: List<String>, val retry: Boolean, val approved: Boolean) : Pending
    data class ReceiptResult(val approved: Boolean) : Pending
    data class TreeResult(val uri: Uri) : Pending
}
private data class QueuedConsent(val ids: List<String>, val retry: Boolean, val request: IntentSenderRequest)
private data class VaultContents(val first: List<VaultItem>, val second: List<VaultItem>, val third: List<String>, val fourth: List<TrashedFolder>)

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    val vault = Vault(File(app.noBackupFilesDir, "vault"), AndroidDeviceKey(), ::syncVaultDirectory)
    val settingsStore = SettingsStore(app)
    val settings = settingsStore.flow
    private val sources = SourceFiles(app)
    private val resolver = app.contentResolver
    private val limiter = AttemptLimiter(File(app.noBackupFilesDir, "attempts.bin"), {
        Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0).toLong()
    }, SystemClock::elapsedRealtime)
    private val state = MutableStateFlow(UiState(exists = vault.exists, screen = if (vault.exists) Screen.CALCULATOR else Screen.WELCOME))
    val ui = state.asStateFlow()
    private val epoch = AtomicLong()
    private var job: Job? = null
    private var bookmarkJob: Job? = null
    @Volatile private var pending: Pending? = null
    private var pickerFolder: String? = null
    private var pickerMode = StorageMode.ENCRYPTED
    private var pickerPrepared = false
    private var unhideForPicker: Pending.Unhide? = null
    private val consentQueue = ArrayDeque<QueuedConsent>()
    private var activeConsent: QueuedConsent? = null
    private var restoredReceiptIds: List<String> = emptyList()
    private var restoredReceiptRetry = false
    private var wasPlayingInBackground = false
    private var playbackCreated = false
    val playback by lazy { PlaybackController(app, vault, { settingsStore.current }, ::onVideoChanged, ::message) }

    fun message(text: String) { state.update { it.copy(message = text) } }
    fun clearMessage() { state.update { it.copy(message = null) } }
    fun updateSettings(value: AppSettings) {
        val old = settingsStore.current
        settingsStore.update(value)
        if (playbackCreated) playback.applySettings()
        if (old.slideshowShuffle != value.slideshowShuffle && state.value.viewing?.mime?.startsWith("image/") == true) {
            val current = state.value
            val ids = sortedItems(current.items.filter { it.folder == current.folder && it.mime.startsWith("image/") }).map { it.id }
            val order = PlaylistOrder.cycle(ids, current.viewing!!.id, value.slideshowShuffle, System.nanoTime())
            state.update { it.copy(photoOrder = order, photoIndex = order.indexOf(current.viewing.id).coerceAtLeast(0)) }
        }
        bookmarkSoon()
    }
    fun navigate(screen: Screen) {
        if (screen in setOf(Screen.VAULT, Screen.TRASH, Screen.SETTINGS, Screen.VIEWER, Screen.RETRIEVE) && !vault.unlocked) return
        if (playbackCreated && screen != Screen.VIEWER) playback.pause()
        state.update { it.copy(screen = screen, selected = emptySet(), viewing = if (screen == Screen.VIEWER || screen == Screen.SETTINGS) it.viewing else null) }
        bookmarkSoon()
    }
    fun openTrashFolder(id: String?) { state.update { it.copy(screen = Screen.TRASH, trashParent = id, selected = emptySet(), search = "", scrollIndex = 0, scrollOffset = 0) }; bookmarkSoon() }
    fun openSettings(section: String = "Security") {
        val prior = state.value.screen
        if (playbackCreated) playback.pause()
        state.update { it.copy(screen = Screen.SETTINGS, settingsSection = section, settingsReturn = prior, settingsScroll = 0) }
        bookmarkSoon()
    }
    fun settingsSection(section: String) { state.update { it.copy(settingsSection = section, settingsScroll = 0) }; bookmarkSoon() }
    fun settingsScroll(offset: Int) { state.update { it.copy(settingsScroll = offset) }; bookmarkSoon() }
    fun openFolder(name: String?) {
        if (!vault.unlocked) return
        if (playbackCreated) playback.pause()
        state.update { it.copy(screen = Screen.VAULT, folder = name, category = "All", search = "", selected = emptySet(), viewing = null, scrollIndex = 0, scrollOffset = 0, photoSlideshow = false) }
        bookmarkSoon()
    }
    fun setCategory(category: String) { state.update { it.copy(category = category, selected = emptySet(), scrollIndex = 0, scrollOffset = 0) }; bookmarkSoon() }
    fun setSearch(search: String) { state.update { it.copy(search = search, selected = emptySet()) }; bookmarkSoon() }
    fun select(id: String) { state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }; bookmarkSoon() }
    fun selectAll(ids: List<String>) { state.update { it.copy(selected = ids.toSet()) }; bookmarkSoon() }
    fun clearSelection() { state.update { it.copy(selected = emptySet()) }; bookmarkSoon() }
    fun saveScroll(index: Int, offset: Int) { state.update { it.copy(scrollIndex = index, scrollOffset = offset) }; bookmarkSoon() }
    fun back() {
        val current = state.value
        when {
            current.selected.isNotEmpty() -> clearSelection()
            current.screen == Screen.VIEWER -> { if (playbackCreated) playback.pause(); navigate(Screen.VAULT) }
            current.screen == Screen.SETTINGS -> navigate(if (current.settingsReturn == Screen.VIEWER && current.viewing != null) Screen.VIEWER else Screen.VAULT)
            current.screen == Screen.TRASH && current.trashParent != null -> openTrashFolder(null)
            current.screen == Screen.TRASH || current.screen == Screen.RETRIEVE -> navigate(Screen.VAULT)
            current.screen == Screen.VAULT && current.folder != null -> openFolder(FolderPath.parent(current.folder))
            current.screen == Screen.VAULT -> lock()
            else -> { pending = null; lock() }
        }
    }
    fun onForeground() {
        state.update { it.copy(foreground = true) }
        if (vault.unlocked && wasPlayingInBackground && state.value.screen == Screen.VIEWER && playbackCreated) playback.play()
        wasPlayingInBackground = false
        if (vault.unlocked && !state.value.busy) showNextConsent()
    }
    fun onBackground(changingConfiguration: Boolean, screenOff: Boolean = false) {
        saveBookmarkNow()
        if (changingConfiguration) return
        wasPlayingInBackground = wasPlayingInBackground || (playbackCreated && playback.intendsPlaying)
        if (playbackCreated) playback.pause()
        state.update { it.copy(foreground = false, resumePlaying = wasPlayingInBackground || it.resumePlaying) }
        if (if (screenOff) settingsStore.current.lockOnScreenOff else settingsStore.current.lockOnBackground) lock()
    }
    fun onScreenOff() { onBackground(false, screenOff = true) }
    fun lock() {
        saveBookmarkNow()
        epoch.incrementAndGet()
        vault.lock()
        job?.cancel(); bookmarkJob?.cancel()
        if (playbackCreated) { playback.release(); playbackCreated = false }
        state.value = UiState(exists = vault.exists, screen = if (vault.exists) Screen.CALCULATOR else Screen.WELCOME,
            pending = pending != null, foreground = state.value.foreground, revision = state.value.revision + 1)
    }
    private fun task(label: String, action: () -> Unit) {
        if (state.value.busy) return
        val ticket = epoch.get()
        state.update { it.copy(busy = true, progress = label, message = null) }
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                if (ticket == epoch.get() && vault.unlocked) {
                    val content = withContext(Dispatchers.IO) { VaultContents(vault.items(), vault.trash(), vault.folders(), vault.trashFolders()) }
                    if (ticket == epoch.get()) state.update { old ->
                        val ids = ((content.first + content.second).map { it.id } + content.fourth.map { "folder:${it.id}" }).toSet()
                        old.copy(exists = true, items = content.first, trash = content.second, folders = content.third, trashFolders = content.fourth, busy = false, progress = "", selected = old.selected.intersect(ids), viewing = old.viewing?.let { item -> (content.first + content.second).firstOrNull { it.id == item.id } })
                    }
                    showNextConsent(); bookmarkSoon()
                } else if (ticket == epoch.get()) state.update { it.copy(busy = false, progress = "") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ticket == epoch.get()) {
                    val content = if (vault.unlocked) withContext(Dispatchers.IO) { VaultContents(vault.items(), vault.trash(), vault.folders(), vault.trashFolders()) } else null
                    state.update { it.copy(busy = false, progress = "", items = content?.first ?: it.items, trash = content?.second ?: it.trash, folders = content?.third ?: it.folders, trashFolders = content?.fourth ?: it.trashFolders, message = safeError(e)) }
                }
            } finally {
                if (vault.exists && !state.value.exists) state.update { it.copy(exists = true, screen = Screen.CALCULATOR, busy = false, progress = "") }
            }
        }
    }
    private fun safeError(e: Exception) = when (e) {
        is SecurityException -> "Access was not granted. Vault files have been retained."
        else -> e.message?.take(220) ?: "The operation could not be completed."
    }
    private fun checkDelay() { val delay = limiter.remainingMillis(); check(delay == 0L) { "Try again in ${(delay + 999) / 1000} seconds." } }
    fun create(pin: String, recovery: String) = task("Creating your vault…") {
        vault.create(pin.toCharArray(), recovery); state.update { it.copy(screen = Screen.VAULT) }
    }
    fun unlock(pin: String, forceRepair: Boolean = false) = task("Unlocking and checking your vault…") {
        checkDelay()
        try { vault.unlock(pin.toCharArray(), forceRepair || settingsStore.current.automaticRetrieval); limiter.success() }
        catch (e: Exception) { if (e.message?.startsWith("Vault locked") != true) limiter.failure(); error("Unable to unlock. Check the PIN, use recovery, or try Unlock and repair.") }
        afterUnlock()
    }
    fun recover(secret: String, pin: String) = task("Recovering vault access…") {
        checkDelay()
        try { vault.recover(secret, pin.toCharArray()); limiter.success() }
        catch (e: Exception) { limiter.failure(); error("Recovery failed. Check the key and try again.") }
        afterUnlock()
    }
    private fun afterUnlock() {
        val items = vault.items(); val trash = vault.trash()
        state.update { it.copy(items = items, trash = trash, folders = vault.folders(), trashFolders = vault.trashFolders(), screen = Screen.VAULT) }
        restoreBookmark(settingsStore.current.resumeLastLocation)
        processPending()
        val report = vault.lastRepair
        if (report.recovered > 0 || report.missing > 0) message("Retrieved ${report.recovered} file(s); ${report.missing} still missing. See Settings → Retrieval.")
    }
    fun changePin(pin: String) = task("Updating PIN…") { vault.changePin(pin.toCharArray()); message("PIN updated. Your recovery key stays the same.") }
    fun refresh() = task("Loading vault…") {}
    fun createFolder(name: String) {
        val parent = state.value.folder
        task("Creating folder…") { vault.createFolder(name.trim(), parent) }
    }
    fun renameFolder(old: String, name: String) = task("Renaming folder…") {
        vault.renameFolder(old, name.trim())
        state.update { it.copy(folder = it.folder?.let { path -> FolderPath.renamed(path, old, FolderPath.child(FolderPath.parent(old), name.trim())) }) }
    }
    fun deleteFolder(name: String) = task("Moving folder contents to recycle bin…") {
        vault.deleteFolder(name)
        if (state.value.folder?.let { FolderPath.within(it, name) } == true) state.update { it.copy(folder = FolderPath.parent(name)) }
    }
    fun rename(item: VaultItem, name: String, folder: String) = task("Updating file…") { vault.rename(item.id, name.trim(), folder.trim()) }
    fun delete(ids: Set<String>) = task("Moving to recycle bin…") { vault.delete(ids); state.update { it.copy(selected = emptySet(), imports = it.imports.filterNot { result -> result.id in ids }) } }
    fun restoreTrash(ids: Set<String>) = task("Restoring files…") {
        val files = ids.filterNot { it.startsWith("folder:") }.toSet()
        if (files.isNotEmpty()) vault.restoreTrash(files)
        ids.filter { it.startsWith("folder:") }.forEach { vault.restoreFolder(it.removePrefix("folder:")) }
        state.update { it.copy(selected = emptySet()) }
    }
    fun permanentlyDelete(ids: Set<String>) = task("Permanently removing selected vault files…") {
        val files = ids.filterNot { it.startsWith("folder:") }.toSet()
        if (files.isNotEmpty()) vault.permanentlyDelete(files)
        ids.filter { it.startsWith("folder:") }.forEach { vault.permanentlyDeleteFolder(it.removePrefix("folder:")) }
        state.update { it.copy(selected = emptySet()) }
    }
    fun repair() = task("Scanning recovery records without deleting data…") {
        val report = vault.repairStorage(); message("Retrieved ${report.recovered}; missing ${report.missing}; damaged recovery records ${report.damaged}. Permanently deleted files are not recreated.")
    }
    fun preparePicker(folder: String? = state.value.folder, mode: StorageMode = StorageMode.ENCRYPTED) { pickerFolder = folder; pickerMode = mode; pickerPrepared = true; saveBookmarkNow() }
    fun selectedImports(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pending = Pending.Import(uris.distinct(), pickerFolder, pickerMode.takeIf { pickerPrepared })
        runPendingOrAsk("Unlock to hide ${uris.size} selected files.")
    }
    fun selectedBackup(uri: Uri?) { if (uri != null) { pending = Pending.Backup(uri); runPendingOrAsk("Unlock to create your encrypted backup.") } }
    fun selectedRestore(uri: Uri?) { if (uri != null && !vault.exists) { pending = Pending.Restore(uri); state.update { it.copy(screen = Screen.RESTORE, pending = true) } } }
    fun selectedRetrieve(uri: Uri?) { if (uri != null) { pending = Pending.Retrieve(uri); state.update { it.copy(screen = if (vault.unlocked) Screen.RETRIEVE else Screen.CALCULATOR, pending = true) } } }
    fun restore(secret: String, pin: String) = task("Restoring and verifying backup…") {
        val op = pending as? Pending.Restore ?: error("Select a backup first")
        resolverInput(op.uri).use { vault.restoreBackup(it, secret, pin.toCharArray()) }; pending = null; afterUnlock()
    }
    fun retrieve(secret: String) = task("Retrieving lost files from backup…") {
        val op = pending as? Pending.Retrieve ?: error("Select your backup first")
        val count = resolverInput(op.uri).use { vault.retrieveFromBackup(it, secret) }
        pending = null; state.update { it.copy(screen = Screen.SETTINGS, settingsSection = "Retrieval", pending = false) }; message("Retrieved $count missing or damaged files. Healthy files were kept.")
    }
    fun requestUnhide(ids: Set<String>, original: Boolean) {
        if (ids.isEmpty()) return
        val op = Pending.Unhide(ids, original)
        if (original) { pending = op; task("Restoring original locations…") { processPending() } } else requestTree(op)
    }
    private fun requestTree(op: Pending.Unhide) {
        unhideForPicker = op
        val hint = state.value.items.firstOrNull { it.id in op.ids }?.source?.uri.orEmpty()
        state.update { it.copy(tree = TreeEvent(System.nanoTime(), hint)) }
    }
    fun treeLaunched() { state.update { it.copy(tree = null) }; saveBookmarkNow() }
    fun selectedTree(uri: Uri?) {
        val op = unhideForPicker; unhideForPicker = null
        state.update { it.copy(tree = null) }
        if (uri == null) return
        sources.retain(uri); pending = op?.copy(tree = uri) ?: Pending.TreeResult(uri); runPendingOrAsk("Unlock to unhide your selected files.")
    }
    private fun runPendingOrAsk(prompt: String) {
        state.update { it.copy(pending = true) }
        if (vault.unlocked) task("Completing file operation…") { processPending() } else message(prompt)
    }
    private fun processPending() {
        val op = pending ?: return
        check(vault.unlocked)
        val ticket = epoch.get()
        when (op) {
            is Pending.Import -> {
                val folder = op.folder ?: state.value.folder ?: vault.folders().firstOrNull() ?: error("Create a destination folder first")
                val mode = op.mode ?: pickerMode
                val outcomes = mutableListOf<ImportResult>(); val confirmations = mutableListOf<Pair<String, Removal.Consent>>()
                for ((index, uri) in op.uris.withIndex()) {
                    check(ticket == epoch.get()) { "Vault locked" }
                    var name = "Selected file"
                    try {
                        name = sources.name(uri); state.update { it.copy(progress = "Hiding ${index + 1} of ${op.uris.size}…") }
                        val location = sources.location(uri)
                        val item = resolverInput(uri).use { vault.importFile(name, resolver.getType(uri) ?: "application/octet-stream", folder, it, mode, location) }
                        val detail = when (val removal = sources.removeOriginal(vault, item)) {
                            Removal.Done -> { vault.markOriginalRemoved(item.id); "Hidden. Original removed." }
                            is Removal.Consent -> { confirmations += item.id to removal; "Waiting for Android removal confirmation." }
                            is Removal.Blocked -> "Not fully hidden: ${removal.reason}"
                        }
                        outcomes += ImportResult(item.id, name, uri, detail)
                    } catch (e: Exception) { check(ticket == epoch.get()) { "Vault locked" }; outcomes += ImportResult(null, name, uri, "Failed. Original was not removed: ${safeError(e)}") }
                    pending = op.uris.drop(index + 1).takeIf { it.isNotEmpty() }?.let { Pending.Import(it, folder, mode) }
                }
                enqueueConfirmations(confirmations); check(ticket == epoch.get()) { "Vault locked" }
                state.update { it.copy(imports = outcomes, folder = folder, screen = Screen.VAULT, category = "All", search = "", selected = emptySet(), scrollIndex = 0, scrollOffset = 0) }
                message("Processed ${outcomes.size} files. Any original still visible is marked as needing attention.")
            }
            is Pending.Unhide -> {
                val needsFolder = mutableSetOf<String>(); var count = 0; val failures = mutableListOf<String>()
                for (id in op.ids) {
                    check(ticket == epoch.get()) { "Vault locked" }
                    val item = vault.items().firstOrNull { it.id == id } ?: continue
                    try {
                        val destination = if (op.tree != null) sources.documentDestination(op.tree, item, if (op.original) item.source.parentHint else "") else sources.originalDestination(vault, item)
                        if (destination == null) { needsFolder += id; continue }
                        sources.writeUnhidden(vault, item, destination); vault.finishUnhide(setOf(id)); count++
                    } catch (e: Exception) { failures += "${item.name}: ${safeError(e)}" }
                }
                state.update { it.copy(selected = emptySet(), viewing = if (it.viewing?.id in op.ids) null else it.viewing, screen = Screen.VAULT) }
                if (needsFolder.isNotEmpty()) {
                    unhideForPicker = op.copy(ids = needsFolder)
                    state.update { it.copy(tree = TreeEvent(System.nanoTime(), vault.items().firstOrNull { item -> item.id in needsFolder }?.source?.uri.orEmpty())) }
                    message("Android needs access to the original folder for ${needsFolder.size} files. Confirm that folder, or choose a new location.")
                } else message(if (failures.isEmpty()) "$count file(s) unhidden and verified; removed from the vault." else "$count unhidden. ${failures.first()}")
            }
            is Pending.Backup -> {
                checkNotNull(resolver.openOutputStream(op.uri, "wt")).use(vault::writeBackup); resolverInput(op.uri).use(vault::verifyBackup)
                message("Encrypted backup saved and verified. Keep the recovery key separately.")
            }
            is Pending.ConsentResult -> {
                if (op.approved) for (id in op.ids) {
                    val item = vault.items().firstOrNull { it.id == id } ?: continue
                    if (item.sourceState == SourceState.REMOVED) continue
                    if (!op.retry) vault.markOriginalRemoved(id)
                    else when (sources.removeOriginal(vault, item)) {
                        Removal.Done -> vault.markOriginalRemoved(id)
                        else -> message("Android did not complete removal. Retry this file in Pending imports.")
                    }
                } else message("Original removal was cancelled. Originals are still visible; verified vault copies are retained.")
                activeConsent = null
                restoredReceiptIds = emptyList()
            }
            is Pending.ReceiptResult -> {
                if (restoredReceiptIds.isEmpty()) { pending = null; message("Removal context expired. Retry unfinished moves."); return }
                pending = Pending.ConsentResult(restoredReceiptIds, restoredReceiptRetry, op.approved)
                processPending(); return
            }
            is Pending.TreeResult -> {
                val saved = unhideForPicker
                unhideForPicker = null
                if (saved == null) { pending = null; message("Select the files to unhide again."); return }
                pending = saved.copy(tree = op.uri)
                processPending(); return
            }
            is Pending.Retrieve -> { state.update { it.copy(screen = Screen.RETRIEVE) }; return }
            is Pending.Restore -> return
        }
        pending = null; state.update { it.copy(pending = false) }
    }
    private fun enqueueConfirmations(values: List<Pair<String, Removal.Consent>>) {
        val batch = values.filter { !it.second.retryDelete && it.second.mediaUri != null }
        batch.chunked(500).forEach { group -> consentQueue.addLast(QueuedConsent(group.map { it.first }, false, sources.combinedDeleteRequest(group.map { it.second.mediaUri!! }))) }
        values.filterNot { it in batch }.forEach { consentQueue.addLast(QueuedConsent(listOf(it.first), it.second.retryDelete, it.second.request)) }
    }
    private fun showNextConsent() {
        if (activeConsent != null || consentQueue.isEmpty() || !vault.unlocked || !state.value.foreground) return
        activeConsent = consentQueue.removeFirst(); state.update { it.copy(consent = ConsentEvent(System.nanoTime(), activeConsent!!.request)) }
    }
    fun consentLaunched() { state.update { it.copy(consent = null) }; saveBookmarkNow() }
    fun consentResult(approved: Boolean) {
        val active = activeConsent
        pending = if (active == null) Pending.ReceiptResult(approved) else Pending.ConsentResult(active.ids, active.retry, approved)
        runPendingOrAsk("Unlock to finish the Android removal request.")
    }
    fun retryRemoval(ids: Set<String>) = task("Requesting original removal…") {
        val requests = mutableListOf<Pair<String, Removal.Consent>>()
        for (item in vault.items().filter { it.id in ids }) when (val result = sources.removeOriginal(vault, item)) {
            Removal.Done -> vault.markOriginalRemoved(item.id)
            is Removal.Consent -> requests += item.id to result
            is Removal.Blocked -> message(result.reason)
        }
        enqueueConfirmations(requests)
    }
    fun dismissImports() { state.update { it.copy(imports = emptyList()) } }
    private fun resolverInput(uri: Uri): java.io.InputStream {
        val ticket = epoch.get(); val input = checkNotNull(resolver.openInputStream(uri)) { "Source unavailable" }
        return object : FilterInputStream(input) {
            override fun read(): Int { check(ticket == epoch.get()) { "Vault locked" }; return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { check(ticket == epoch.get()) { "Vault locked" }; return `in`.read(b, off, len) }
        }
    }
    fun view(item: VaultItem) {
        if (!vault.unlocked || item.missing) return
        val images = sortedItems(state.value.items.filter { it.folder == item.folder && it.mime.startsWith("image/") }).map { it.id }
        val order = PlaylistOrder.cycle(images, item.id, settingsStore.current.slideshowShuffle, System.nanoTime())
        state.update { it.copy(screen = Screen.VIEWER, folder = item.folder, viewing = item, videoPosition = 0, resumePlaying = false, videoOrder = emptyList(), photoSlideshow = false, photoOrder = order, photoIndex = order.indexOf(item.id).coerceAtLeast(0), imageScale = 1f, imagePanX = 0f, imagePanY = 0f) }
        bookmarkSoon()
    }
    fun videoController(): PlaybackController {
        playbackCreated = true
        val current = state.value
        val videos = sortedItems(current.items.filter { it.folder == current.folder && it.mime.startsWith("video/") && !it.missing })
        current.viewing?.let { playback.bind(videos, it.id, current.videoPosition, current.resumePlaying, current.videoOrder) }
        return playback
    }
    private fun onVideoChanged(id: String, position: Long, playing: Boolean) {
        if (!vault.unlocked) return
        val current = state.value
        if (current.viewing?.mime?.startsWith("video/") != true || current.screen !in setOf(Screen.VIEWER, Screen.SETTINGS)) return
        state.update { old -> old.copy(viewing = old.items.firstOrNull { it.id == id } ?: old.viewing, videoPosition = position, resumePlaying = playing, videoOrder = playback.order) }; bookmarkSoon()
    }
    fun toggleSlideshow() { state.update { it.copy(photoSlideshow = !it.photoSlideshow) }; bookmarkSoon() }
    fun nextPhoto(back: Boolean = false) {
        val old = state.value
        val available = sortedItems(old.items.filter { it.folder == old.folder && it.mime.startsWith("image/") && !it.missing }).map { it.id }
        if (available.isEmpty()) return
        var order = old.photoOrder.filter { it in available }.ifEmpty { available }
        var index = order.indexOf(old.viewing?.id).coerceAtLeast(0) + if (back) -1 else 1
        if (index >= order.size) {
            order = if (settingsStore.current.slideshowShuffle) PlaylistOrder.cycle(available, old.viewing?.id.orEmpty(), true, System.nanoTime()).let { it.drop(1) + it.take(1) } else available
            index = 0
        }
        if (index < 0) index = order.lastIndex
        val item = old.items.firstOrNull { it.id == order[index] } ?: return
        state.update { it.copy(viewing = item, photoOrder = order, photoIndex = index, imageScale = 1f, imagePanX = 0f, imagePanY = 0f) }; bookmarkSoon()
    }
    fun imageTransform(id: String, scale: Float, x: Float, y: Float) {
        if (state.value.viewing?.id != id) return
        state.update { it.copy(imageScale = scale, imagePanX = x, imagePanY = y) }; bookmarkSoon()
    }
    fun sortedItems(items: List<VaultItem>): List<VaultItem> = when (settingsStore.current.sort) {
        FileSort.NEWEST -> items.sortedByDescending { it.created }; FileSort.OLDEST -> items.sortedBy { it.created }
        FileSort.NAME -> items.sortedBy { it.name.lowercase() }; FileSort.SIZE -> items.sortedByDescending { it.size }
    }
    private fun bookmarkSoon() { bookmarkJob?.cancel(); bookmarkJob = viewModelScope.launch { delay(250); saveBookmarkNow() } }
    fun saveBookmarkNow() {
        if (!vault.unlocked) return
        val current = state.value
        if (current.screen !in setOf(Screen.VAULT, Screen.VIEWER, Screen.TRASH, Screen.SETTINGS, Screen.RETRIEVE)) return
        val position = if (playbackCreated && Looper.myLooper() == Looper.getMainLooper()) playback.position else current.videoPosition
        val json = JSONObject().put("screen", current.screen.name).put("folder", current.folder).put("category", current.category).put("search", current.search)
            .put("selected", JSONArray(current.selected.toList())).put("scroll", current.scrollIndex).put("offset", current.scrollOffset)
            .put("viewing", current.viewing?.id).put("position", position).put("playing", current.resumePlaying)
            .put("videoOrder", JSONArray(current.videoOrder))
            .put("slideshow", current.photoSlideshow).put("photoOrder", JSONArray(current.photoOrder)).put("photoIndex", current.photoIndex)
            .put("imageScale", current.imageScale).put("imageX", current.imagePanX).put("imageY", current.imagePanY)
            .put("settings", current.settingsSection).put("settingsReturn", current.settingsReturn.name).put("settingsScroll", current.settingsScroll)
            .put("trashParent", current.trashParent)
            .put("pickerFolder", pickerFolder).put("pickerMode", pickerMode.name)
            .put("unhideIds", JSONArray(unhideForPicker?.ids?.toList().orEmpty())).put("unhideOriginal", unhideForPicker?.original ?: false)
            .put("removalIds", JSONArray(activeConsent?.ids ?: restoredReceiptIds)).put("removalRetry", activeConsent?.retry ?: restoredReceiptRetry)
        runCatching { vault.saveNavigation(json.toString()) }
    }
    private fun restoreBookmark(restoreUi: Boolean) {
        val raw = runCatching { vault.loadNavigation() }.getOrDefault(""); if (raw.isBlank()) return
        runCatching {
            val json = JSONObject(raw); val current = state.value
            val requested = Screen.entries.firstOrNull { it.name == json.optString("screen") } ?: Screen.VAULT
            val screen = requested.takeIf { it in setOf(Screen.VAULT, Screen.VIEWER, Screen.TRASH, Screen.SETTINGS) } ?: Screen.VAULT
            val item = current.items.firstOrNull { it.id == json.optString("viewing") && !it.missing }
            val folder = json.optString("folder").takeIf { it in current.folders }
            fun ids(key: String): List<String> { val array = json.optJSONArray(key) ?: return emptyList(); return List(array.length()) { array.optString(it) } }
            restoredReceiptIds = ids("removalIds")
            restoredReceiptRetry = json.optBoolean("removalRetry")
            if (!pickerPrepared) {
                pickerFolder = json.optString("pickerFolder").takeIf { it in current.folders }
                pickerMode = StorageMode.entries.firstOrNull { it.name == json.optString("pickerMode") } ?: StorageMode.ENCRYPTED
            }
            if (pending is Pending.Import) {
                val op = pending as Pending.Import
                pending = op.copy(folder = op.folder ?: pickerFolder, mode = op.mode ?: pickerMode)
            }
            if (pending is Pending.TreeResult) {
                val valid = ids("unhideIds").filter { id -> current.items.any { it.id == id } }.toSet()
                if (valid.isNotEmpty()) unhideForPicker = Pending.Unhide(valid, json.optBoolean("unhideOriginal"))
            }
            if (!restoreUi) return@runCatching
            state.update { it.copy(screen = if (screen == Screen.VIEWER && item == null) Screen.VAULT else screen,
                folder = folder, category = json.optString("category", "All"), search = json.optString("search"), selected = ids("selected").filter { id -> (current.items + current.trash).any { entry -> entry.id == id } || current.trashFolders.any { entry -> "folder:${entry.id}" == id } }.toSet(),
                scrollIndex = json.optInt("scroll").coerceAtLeast(0), scrollOffset = json.optInt("offset").coerceAtLeast(0), viewing = item,
                videoPosition = json.optLong("position").coerceAtLeast(0), resumePlaying = json.optBoolean("playing"), videoOrder = ids("videoOrder"), photoSlideshow = json.optBoolean("slideshow"),
                imageScale = json.optDouble("imageScale", 1.0).toFloat().coerceIn(1f, 6f), imagePanX = json.optDouble("imageX", 0.0).toFloat().coerceIn(-1f, 1f), imagePanY = json.optDouble("imageY", 0.0).toFloat().coerceIn(-1f, 1f),
                photoOrder = ids("photoOrder"), photoIndex = json.optInt("photoIndex"), settingsSection = json.optString("settings", "Security"), settingsScroll = json.optInt("settingsScroll").coerceAtLeast(0), trashParent = json.optString("trashParent").takeIf { id -> current.trashFolders.any { it.id == id } },
                settingsReturn = if (json.optString("settingsReturn") == Screen.VIEWER.name && item != null) Screen.VIEWER else Screen.VAULT) }
        }
    }
    override fun onCleared() { if (playbackCreated) playback.release(); vault.lock(); super.onCleared() }
}
