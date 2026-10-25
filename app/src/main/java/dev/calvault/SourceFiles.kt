package dev.calvault

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.IntentSenderRequest
import androidx.core.net.toUri
import dev.calvault.core.SourceLocation
import dev.calvault.core.Vault
import dev.calvault.core.VaultItem

sealed interface Removal {
    data object Done : Removal
    data class Consent(val request: IntentSenderRequest, val retryDelete: Boolean, val mediaUri: Uri? = null) : Removal
    data class Blocked(val reason: String) : Removal
}
data class Destination(val uri: Uri, val media: Boolean = false, val newlyCreated: Boolean = true)

/** Uses only user-selected URI grants. No broad storage access or guessed filesystem writes. */
class SourceFiles(private val context: Context) {
    private val resolver = context.contentResolver
    fun name(uri: Uri): String {
        val raw = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported file"
        return raw.replace('/', '_').replace('\\', '_').replace("\u0000", "").take(240).ifBlank { "Imported file" }
    }
    fun retain(uri: Uri) {
        if (runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isFailure) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
    private fun mediaUri(uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY && uri.pathSegments.none { it == "picker" }) return uri
        return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: runCatching {
            if (uri.authority != "com.android.providers.media.documents") return@runCatching null
            val parts = DocumentsContract.getDocumentId(uri).split(':')
            if (parts.size != 2) return@runCatching null
            val id = parts[1].toLongOrNull() ?: return@runCatching null
            val collection = when (parts[0]) {
                "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> return@runCatching null
            }
            ContentUris.withAppendedId(collection, id)
        }.getOrNull()
    }
    fun location(uri: Uri): SourceLocation {
        retain(uri)
        val media = mediaUri(uri)
        var relative = ""
        var collection = ""
        if (media != null) runCatching {
            resolver.query(media, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) relative = it.getString(0).orEmpty()
            }
            val kind = when { media.pathSegments.contains("images") -> "images"; media.pathSegments.contains("video") -> "video"; media.pathSegments.contains("audio") -> "audio"; else -> "" }
            if (kind.isNotEmpty()) collection = "$kind:${MediaStore.getVolumeName(media)}"
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrDefault("")
        val parent = documentId.substringBeforeLast('/', "")
        if (collection.isBlank() && documentId.startsWith("primary:Download/")) {
            collection = "downloads:external_primary"
            relative = documentId.substringAfter(':').substringBeforeLast('/') + "/"
        }
        val tree = resolver.persistedUriPermissions.firstOrNull { permission ->
            permission.isWritePermission && runCatching {
                val treeId = DocumentsContract.getTreeDocumentId(permission.uri)
                documentId == treeId || documentId.startsWith("$treeId/")
            }.getOrDefault(false)
        }?.uri?.toString().orEmpty()
        return SourceLocation(uri.toString(), relative, collection, tree, parent)
    }

    /** User requested a move; deletion is attempted only after revalidating the durable vault copy and source. */
    fun removeOriginal(vault: Vault, item: VaultItem): Removal {
        if (item.source.uri.isBlank()) return Removal.Blocked("Original location is unavailable for this older import.")
        val uri = item.source.uri.toUri()
        try {
            checkNotNull(resolver.openInputStream(uri)).use { vault.verifyOriginal(item.id, it) }
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val write = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                val canDelete = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                    it.moveToFirst() && (it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                } == true
                if (write && canDelete && DocumentsContract.deleteDocument(resolver, uri)) return Removal.Done
            }
            val media = mediaUri(uri)
            if (media != null) {
                if (Build.VERSION.SDK_INT >= 30) return Removal.Consent(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(resolver, listOf(media)).intentSender).build(), false, media)
                if (resolver.delete(media, null, null) > 0) return Removal.Done
            }
            return Removal.Blocked("This provider does not allow removal here. The original is still visible. Grant access in its source app, then retry.")
        } catch (e: RecoverableSecurityException) {
            return Removal.Consent(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build(), true)
        } catch (_: java.io.FileNotFoundException) {
            return Removal.Blocked("Cannot confirm the original's state. Grant access or retry; it is not marked as hidden.")
        } catch (e: Exception) {
            return Removal.Blocked(e.message?.take(160) ?: "Original removal failed. The verified vault copy is retained.")
        }
    }
    fun combinedDeleteRequest(uris: List<Uri>): IntentSenderRequest {
        if (Build.VERSION.SDK_INT >= 30) return IntentSenderRequest.Builder(MediaStore.createDeleteRequest(resolver, uris).intentSender).build()
        error("Batch removal requires Android 11 or later")
    }

    /** Returns null when Android needs the owner to confirm an original document folder. */
    fun originalDestination(vault: Vault, item: VaultItem): Destination? {
        if (item.source.uri.isNotBlank()) {
            val existing = item.source.uri.toUri()
            val matches = runCatching { checkNotNull(resolver.openInputStream(existing)).use { vault.verifyDestination(item.id, it) } }.isSuccess
            if (matches) return Destination(existing, newlyCreated = false)
        }
        val parts = item.source.collection.split(':')
        if (parts.size == 2 && item.source.relativePath.isNotBlank()) {
            val volume = parts[1]
            val collection = when (parts[0]) {
                "images" -> MediaStore.Images.Media.getContentUri(volume)
                "video" -> MediaStore.Video.Media.getContentUri(volume)
                "audio" -> MediaStore.Audio.Media.getContentUri(volume)
                "downloads" -> MediaStore.Downloads.getContentUri(volume)
                else -> null
            }
            if (collection != null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, item.source.relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                if (uri != null) return Destination(uri, media = true)
            }
        }
        if (item.source.treeUri.isNotBlank()) {
            val tree = item.source.treeUri.toUri()
            return runCatching { documentDestination(tree, item, item.source.parentHint) }.getOrNull()
        }
        return null
    }
    fun documentDestination(tree: Uri, item: VaultItem, originalParent: String = ""): Destination {
        retain(tree)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val parentId = originalParent.takeIf { it == rootId || it.startsWith("$rootId/") } ?: rootId
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val names = mutableSetOf<String>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) names.add(it.getString(0))
        }
        var candidate = item.name
        val dot = item.name.lastIndexOf('.').takeIf { it > 0 } ?: item.name.length
        var suffix = 1
        while (candidate in names) {
            check(suffix <= 10000) { "Too many files with the same name" }
            candidate = item.name.substring(0, dot).take(210) + " ($suffix)" + item.name.substring(dot)
            suffix++
        }
        return Destination(checkNotNull(DocumentsContract.createDocument(resolver, parent, item.mime, candidate)) { "Destination folder is not writable" })
    }
    fun writeUnhidden(vault: Vault, item: VaultItem, destination: Destination) {
        if (!destination.newlyCreated) {
            checkNotNull(resolver.openInputStream(destination.uri)).use { vault.verifyDestination(item.id, it) }
            return
        }
        try {
            checkNotNull(resolver.openOutputStream(destination.uri, "wt")).use { vault.export(item.id, it) }
            checkNotNull(resolver.openInputStream(destination.uri)).use { vault.verifyDestination(item.id, it) }
            if (destination.media) check(resolver.update(destination.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "Android could not publish the restored file. The vault file was retained."
            }
        } catch (e: Exception) {
            // Roll back only the output newly created by this operation, never the original or the vault file.
            runCatching { if (destination.media) resolver.delete(destination.uri, null, null) else DocumentsContract.deleteDocument(resolver, destination.uri) }
            throw e
        }
    }
}
