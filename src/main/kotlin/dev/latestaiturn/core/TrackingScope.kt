package dev.latestaiturn.core

/** Which part of a project takes part in tracking. An empty folder list means the whole project. */
object TrackingScope {
    /** Accepts "src", "/src/", "./src" or "src, doc". Blank entries are dropped. */
    fun parse(text: String): List<String> =
        text.split(',').map { it.trim().removePrefix("./").trim('/') }.filter { it.isNotEmpty() }

    fun format(folders: List<String>): String = folders.joinToString(", ")

    /** [path] is absolute, [base] is the project root without a trailing slash. */
    fun contains(path: String, base: String, folders: List<String>): Boolean {
        if (!path.startsWith("$base/")) return false
        if (folders.isEmpty()) return true
        val rel = path.removePrefix("$base/")
        return folders.any { rel.startsWith("$it/") }
    }
}
