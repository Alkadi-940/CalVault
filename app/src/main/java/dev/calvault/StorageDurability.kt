package dev.calvault

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Flush directory-entry commits before an original outside the vault can be removed. */
internal fun syncVaultDirectory(directory: File) {
    check(directory.isDirectory) { "Vault storage directory is unavailable" }
    val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
