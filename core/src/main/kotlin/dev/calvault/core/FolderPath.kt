package dev.calvault.core

/** Logical vault paths, never filesystem paths. The separator was forbidden in legacy folder names. */
object FolderPath {
    private const val SEPARATOR = '\u001F'
    fun name(path: String) = path.substringAfterLast(SEPARATOR)
    fun parent(path: String?): String? = path?.substringBeforeLast(SEPARATOR, "")?.takeIf { it.isNotEmpty() }
    fun display(path: String) = path.replace(SEPARATOR.toString(), " › ")
    fun within(path: String, ancestor: String) = path == ancestor || path.startsWith(ancestor + SEPARATOR)
    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 120 && name.none { it.isISOControl() }) { "Use a folder name of 1–120 characters without control characters" }
    }
    fun validate(path: String) {
        require(path.length <= 4096) { "Folder path is too long" }
        val parts = path.split(SEPARATOR)
        require(parts.size <= 32) { "Folders can be nested up to 32 levels" }
        parts.forEach(::validateName)
    }
    fun child(parent: String?, name: String): String {
        validateName(name)
        return (if (parent == null) name else parent + SEPARATOR + name).also(::validate)
    }
    fun ancestors(path: String): List<String> {
        validate(path)
        val parts = path.split(SEPARATOR)
        return parts.indices.map { parts.take(it + 1).joinToString(SEPARATOR.toString()) }
    }
    fun expand(paths: List<String>): List<String> {
        val result = linkedSetOf<String>()
        paths.forEach { path -> ancestors(path).forEach { result += it; require(result.size <= 10000) { "Vault folder limit reached" } } }
        return result.toList()
    }
    fun renamed(path: String, old: String, replacement: String) = if (within(path, old)) replacement + path.removePrefix(old) else path
}
