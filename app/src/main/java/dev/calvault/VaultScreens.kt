@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.calvault

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.calvault.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable internal fun VaultBrowser(state: UiState, settings: AppSettings, model: VaultViewModel, onAdd: () -> Unit, onCreateFolder: () -> Unit) {
    var renameFolder by remember { mutableStateOf<String?>(null) }
    var deleteFolder by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<VaultItem?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var unhideIds by remember { mutableStateOf<Set<String>?>(null) }
    var details by remember { mutableStateOf<VaultItem?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val folderItems = state.items.filter { it.folder == state.folder }
    val visible = model.sortedItems(folderItems.filter { item -> item.name.contains(state.search, true) && when (state.category) {
        "Photos" -> item.mime.startsWith("image/")
        "Videos" -> item.mime.startsWith("video/")
        "Files" -> !item.mime.startsWith("image/") && !item.mime.startsWith("video/")
        "Pending" -> item.sourceState == SourceState.PENDING_REMOVAL
        else -> true
    } })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.folder != null) IconButton(onClick = model::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to folders") }
            Text(if (state.selected.isNotEmpty()) "${state.selected.size} selected" else state.folder ?: "CALVAULT", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (state.selected.isNotEmpty()) {
                IconButton(onClick = { model.selectAll(visible.map { it.id }) }) { Icon(Icons.Outlined.SelectAll, "Select all") }
                IconButton(onClick = { unhideIds = state.selected }) { Icon(Icons.Outlined.Visibility, "Unhide selected") }
                IconButton(onClick = { deleteIds = state.selected }) { Icon(Icons.Outlined.DeleteOutline, "Move selected to recycle bin") }
                IconButton(onClick = model::clearSelection) { Icon(Icons.Outlined.Close, "Cancel selection") }
            } else {
                IconButton(onClick = { model.openTrashFolder(null) }) { Icon(Icons.Outlined.DeleteOutline, "Recycle bin") }
                IconButton(onClick = { model.openSettings() }) { Icon(Icons.Outlined.Settings, "Settings") }
                IconButton(onClick = model::lock) { Icon(Icons.Outlined.Lock, "Lock vault") }
            }
        }
        if (state.folder == null) {
            Box(Modifier.weight(1f)) {
                val grid = rememberLazyGridState(state.scrollIndex.coerceAtMost((state.folders.size - 1).coerceAtLeast(0)), state.scrollOffset)
                LaunchedEffect(grid) { snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { model.saveScroll(it.first, it.second) } }
                LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("My vault", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                            Text("${state.folders.size} folders · ${state.items.size} files · ${formatSize(state.items.sumOf { it.size })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(state.search, model::setSearch, Modifier.fillMaxWidth(), placeholder = { Text("Search folders") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                            val pending = state.items.filter { it.sourceState == SourceState.PENDING_REMOVAL }
                            if (pending.isNotEmpty()) Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer, onClick = { model.openFolder(pending.first().folder); model.setCategory("Pending") }) {
                                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Info, null); Text("${pending.size} move(s) need attention. Originals may still be visible.") }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("YOUR FOLDERS", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = onCreateFolder) { Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(6.dp)); Text("New folder") }
                            }
                        }
                    }
                    items(state.folders.filter { it.contains(state.search, true) }.sorted(), key = { it }) { name ->
                        var menu by remember { mutableStateOf(false) }
                        val contents = state.items.filter { it.folder == name }
                        Surface(onClick = { model.openFolder(name) }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.heightIn(min = 162.dp).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Folder, null, Modifier.size(36.dp).weight(1f, false), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.weight(1f))
                                    Box {
                                        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "Actions for folder $name") }
                                        DropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text("Rename folder") }, onClick = { menu = false; renameFolder = name })
                                            DropdownMenuItem(text = { Text("Move folder to recycle bin") }, onClick = { menu = false; deleteFolder = name })
                                        }
                                    }
                                }
                                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${contents.size} files · ${formatSize(contents.sumOf { it.size })}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.folders.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyFiles("Create your first folder", "Keep photos, videos, and documents together in folders.") }
                }
                ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Hide files") }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
            }
        } else {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${folderItems.size} files · ${formatSize(folderItems.sumOf { it.size })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(state.search, model::setSearch, Modifier.fillMaxWidth(), placeholder = { Text("Search this folder") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        (listOf("All", "Photos", "Videos", "Files") + if (folderItems.any { it.sourceState == SourceState.PENDING_REMOVAL }) listOf("Pending") else emptyList()).forEach { category ->
                            FilterChip(state.category == category, { model.setCategory(category) }, label = { Text(category) })
                        }
                    }
                    IconButton(onClick = { model.updateSettings(settings.copy(grid = !settings.grid)) }) { Icon(if (settings.grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Toggle grid or list") }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort files") }
                        DropdownMenu(sortMenu, { sortMenu = false }) { FileSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.name.lowercase().replaceFirstChar { it.uppercase() }) }, onClick = { sortMenu = false; model.updateSettings(settings.copy(sort = sort)) }) } }
                    }
                }
                if (state.category == "Pending" && visible.isNotEmpty()) TextButton(onClick = { model.retryRemoval(visible.map { it.id }.toSet()) }) { Text("Retry original removal for these files") }
            }
            Box(Modifier.weight(1f)) {
                if (visible.isEmpty()) EmptyFiles("Nothing here yet", if (state.search.isNotBlank()) "Try a different search or filter." else "Add files to this folder.")
                else if (settings.grid) {
                    val grid = rememberLazyGridState(state.scrollIndex.coerceAtMost(visible.lastIndex.coerceAtLeast(0)), state.scrollOffset)
                    LaunchedEffect(grid) { snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { model.saveScroll(it.first, it.second) } }
                    LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible, key = { it.id }) { item -> FileTile(item, item.id in state.selected, true, model, onClick = { if (state.selected.isEmpty()) model.view(item) else model.select(item.id) }, onRename = { edit = item }, onDelete = { deleteIds = setOf(item.id) }, onUnhide = { unhideIds = setOf(item.id) }, onDetails = { details = item }) }
                    }
                } else {
                    val list = androidx.compose.foundation.lazy.rememberLazyListState(state.scrollIndex.coerceAtMost(visible.lastIndex.coerceAtLeast(0)), state.scrollOffset)
                    LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.collect { model.saveScroll(it.first, it.second) } }
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible, key = { it.id }) { item -> FileTile(item, item.id in state.selected, false, model, onClick = { if (state.selected.isEmpty()) model.view(item) else model.select(item.id) }, onRename = { edit = item }, onDelete = { deleteIds = setOf(item.id) }, onUnhide = { unhideIds = setOf(item.id) }, onDetails = { details = item }) }
                    }
                }
                ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Hide files") }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
            }
        }
    }
    renameFolder?.let { old -> NameDialog("Rename folder", old, { renameFolder = null }) { model.renameFolder(old, it); renameFolder = null } }
    deleteFolder?.let { name -> ConfirmDialog("Move folder to recycle bin?", "The folder and its contents can be restored from the recycle bin.", "Move to bin", { deleteFolder = null }) { model.deleteFolder(name); deleteFolder = null } }
    deleteIds?.let { ids -> ConfirmDialog("Move ${ids.size} file(s) to recycle bin?", "You can restore them until you permanently delete them from the bin.", "Move to bin", { deleteIds = null }) { model.delete(ids); deleteIds = null } }
    unhideIds?.let { ids -> UnhideDialog(ids.size, { unhideIds = null }) { original -> unhideIds = null; model.requestUnhide(ids, original) } }
    edit?.let { item ->
        var name by remember(item.id) { mutableStateOf(item.name) }
        var folder by remember(item.id) { mutableStateOf(item.folder) }
        var menu by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Rename or move") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(240) }, label = { Text("Filename") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(folder) }
                    DropdownMenu(menu, { menu = false }) { state.folders.forEach { destination -> DropdownMenuItem(text = { Text(destination) }, onClick = { folder = destination; menu = false }) } }
                }
            }
        }, confirmButton = { TextButton(onClick = { edit = null; model.rename(item, name, folder) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    details?.let { item -> AlertDialog(onDismissRequest = { details = null }, title = { Text(item.name) }, text = {
        Text("${formatSize(item.size)}\n${item.mime}\nFolder: ${item.folder}\nMode: ${if (item.mode == StorageMode.ENCRYPTED) "Encryption" else "Hidden (encrypted)"}\n" +
            when (item.sourceState) { SourceState.REMOVED -> "Original removed after verification."; SourceState.PENDING_REMOVAL -> "Original removal incomplete; source may still be visible."; SourceState.UNKNOWN -> "Older import: original location/removal status unavailable." } +
            if (item.missing) "\nFile is missing. Try Settings → Retrieval." else "")
    }, confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } }) }
}

@Composable private fun FileTile(item: VaultItem, selected: Boolean, grid: Boolean, model: VaultViewModel, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onUnhide: () -> Unit, onDetails: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onClick, onLongClick = { model.select(item.id) }, onLongClickLabel = "Select file")) {
        val options: @Composable () -> Unit = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${item.name}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; model.select(item.id) })
                    DropdownMenuItem(text = { Text("Rename / move") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Unhide") }, onClick = { menu = false; onUnhide() }, enabled = !item.missing)
                    if (item.sourceState == SourceState.PENDING_REMOVAL) DropdownMenuItem(text = { Text("Retry original removal") }, onClick = { menu = false; model.retryRemoval(setOf(item.id)) })
                    DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; onDetails() })
                    DropdownMenuItem(text = { Text("Move to recycle bin") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        val label: @Composable (Modifier) -> Unit = { modifier -> Column(modifier) {
            Text(item.name, maxLines = if (grid) 2 else 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(formatSize(item.size) + if (item.mode == StorageMode.HIDDEN) " · Hidden" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.sourceState == SourceState.PENDING_REMOVAL) Text("Original still visible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            if (item.missing) Text("Missing file", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        } }
        if (grid) Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.primary.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Outlined.CheckCircle, "Selected", Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                else VaultThumbnail(item, model, Modifier.fillMaxSize())
            }
            Row(Modifier.padding(start = 12.dp, bottom = 10.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) { label(Modifier.weight(1f)); options() }
        } else Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary) else VaultThumbnail(item, model, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp)); label(Modifier.weight(1f)); options()
        }
    }
}

@Composable private fun VaultThumbnail(item: VaultItem, model: VaultViewModel, modifier: Modifier) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        if (item.mime.startsWith("image/") && !item.missing) try { bitmap = withContext(Dispatchers.IO) { decodeVaultPhoto(model.vault, item.id, 240) } }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }
    DisposableEffect(item.id) { onDispose { bitmap = null } }
    val loaded = bitmap
    if (loaded != null) Image(loaded.asImageBitmap(), null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier, contentAlignment = Alignment.Center) { Icon(when { item.missing -> Icons.Outlined.BrokenImage; item.mime.startsWith("video/") -> Icons.Outlined.PlayCircle; item.mime.startsWith("image/") -> Icons.Outlined.Image; else -> Icons.AutoMirrored.Outlined.InsertDriveFile }, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }
}

@Composable internal fun RecycleBinScreen(state: UiState, settings: AppSettings, model: VaultViewModel) {
    var deleting by remember { mutableStateOf<Set<String>?>(null) }
    val parent = state.trashFolders.firstOrNull { it.id == state.trashParent }
    val folders = if (parent == null) state.trashFolders else emptyList()
    val files = state.trash.filter { item -> if (parent != null) item.trashFolderId == parent.id else state.trashFolders.none { it.id == item.trashFolderId } }.sortedByDescending { it.deletedAt }
    val visibleIds = files.map { it.id } + folders.map { "folder:${it.id}" }
    val allIds = (state.trash.map { it.id } + state.trashFolders.map { "folder:${it.id}" }).toSet()
    Column(Modifier.fillMaxSize()) {
        Header(parent?.name ?: "Recycle bin", model::back)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${state.trash.size} files · ${formatSize(state.trash.sumOf { it.size })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Files stay here until you restore or explicitly permanently delete them.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.selectAll(visibleIds) }, enabled = visibleIds.isNotEmpty()) { Text("Select all") }
                if (state.selected.isNotEmpty()) {
                    OutlinedButton(onClick = { model.restoreTrash(state.selected) }) { Text("Restore selected") }
                    OutlinedButton(onClick = { deleting = state.selected }) { Text("Delete selected") }
                    TextButton(onClick = model::clearSelection) { Text("Cancel") }
                } else {
                    OutlinedButton(onClick = { model.restoreTrash(allIds) }, enabled = allIds.isNotEmpty()) { Text("Restore all") }
                    OutlinedButton(onClick = { deleting = allIds }, enabled = allIds.isNotEmpty()) { Text("Empty bin") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(folders, key = { "folder:${it.id}" }) { folder ->
                val id = "folder:${folder.id}"
                Surface(shape = RoundedCornerShape(20.dp), color = if (id in state.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = { if (state.selected.isEmpty()) model.openTrashFolder(folder.id) else model.select(id) }, onLongClick = { model.select(id) })) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (id in state.selected) Icons.Outlined.CheckCircle else Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(folder.name, fontWeight = FontWeight.Medium); Text("Folder · ${state.trash.count { it.trashFolderId == folder.id }} files", style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { model.restoreTrash(setOf(id)) }) { Icon(Icons.Outlined.Restore, "Restore folder ${folder.name}") }
                        IconButton(onClick = { deleting = setOf(id) }) { Icon(Icons.Outlined.DeleteForever, "Permanently delete folder ${folder.name}") }
                    }
                }
            }
            items(files, key = { it.id }) { item ->
                Surface(shape = RoundedCornerShape(20.dp), color = if (item.id in state.selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = { model.select(item.id) }, onLongClick = { model.select(item.id) })) {
                    Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (item.id in state.selected) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatSize(item.size)} · ${item.folder}", style = MaterialTheme.typography.bodySmall)
                            Text("Deleted ${DateFormat.getDateInstance().format(Date(item.deletedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { model.restoreTrash(setOf(item.id)) }) { Icon(Icons.Outlined.Restore, "Restore ${item.name}") }
                        IconButton(onClick = { deleting = setOf(item.id) }) { Icon(Icons.Outlined.DeleteForever, "Permanently delete ${item.name}") }
                    }
                }
            }
            if (visibleIds.isEmpty()) item { EmptyFiles("Recycle bin is empty", "Deleted items will appear here.") }
        }
    }
    deleting?.let { ids -> ConfirmDialog("Permanently delete?", "Selected items will be removed from this device's vault, including its recovery copies. This cannot be undone. Existing backups and copies outside CalVault are unaffected.", "Delete permanently", { deleting = null }) { deleting = null; model.permanentlyDelete(ids) } }
}

@Composable internal fun UnhideDialog(count: Int, dismiss: () -> Unit, confirm: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Unhide $count file(s)") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Files leave the vault only after the destination has been written and verified.")
            Button(onClick = { confirm(true) }, Modifier.fillMaxWidth()) { Text("Original location") }
            Text("Android may ask you to confirm access to the original folder. Older imports may require choosing that folder again.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirm(false) }, Modifier.fillMaxWidth()) { Text("Choose a new folder") }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable internal fun ConfirmDialog(title: String, text: String, action: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = confirm) { Text(action) } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it.take(120) }, label = { Text("Folder name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { save(name) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun EmptyFiles(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(subtitle, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
