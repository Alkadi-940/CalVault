package dev.calvault.core

enum class StorageMode { ENCRYPTED, HIDDEN }
enum class SourceState { UNKNOWN, PENDING_REMOVAL, REMOVED }

data class SourceLocation(
    val uri: String = "", val relativePath: String = "", val collection: String = "",
    val treeUri: String = "", val parentHint: String = "",
)

data class VaultItem(
    val id: String, val name: String, val mime: String, val folder: String,
    val size: Long, val created: Long,
    val mode: StorageMode = StorageMode.ENCRYPTED,
    val deletedAt: Long = 0,
    val source: SourceLocation = SourceLocation(),
    val sourceState: SourceState = SourceState.UNKNOWN,
    val trashFolderId: String = "",
    val missing: Boolean = false,
)

data class RepairReport(val recovered: Int = 0, val missing: Int = 0, val damaged: Int = 0)
data class TrashedFolder(val id: String, val name: String, val deletedAt: Long)

/** A shuffled cycle starts with the current item and visits every other item once. */
object PlaylistOrder {
    fun cycle(ids: List<String>, current: String, shuffle: Boolean, seed: Long): List<String> {
        val unique = ids.distinct()
        if (unique.isEmpty()) return emptyList()
        if (!shuffle) return unique
        val first = current.takeIf { it in unique } ?: unique.first()
        return listOf(first) + unique.filterNot { it == first }.shuffled(kotlin.random.Random(seed))
    }
}
