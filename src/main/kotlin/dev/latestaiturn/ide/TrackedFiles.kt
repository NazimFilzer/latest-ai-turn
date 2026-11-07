package dev.latestaiturn.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.VirtualFile

/** Decides which files take part in turn tracking, and reads their text the way the editor sees it. */
object TrackedFiles {
    const val MAX_BYTES = 1_048_576L

    /**
     * Never tracked, wherever they appear: IDE metadata, VCS data and dependency caches. Build output (`build`, `out`,
     * `dist`, `target`) is deliberately not listed: a source package may legitimately use those names, and real
     * output folders are already excluded by the IDE or ignored by VCS, which are checked below.
     */
    private val EXCLUDED_DIRS = setOf(".git", ".idea", ".gradle", "node_modules", "__pycache__")

    fun isEligible(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory || !file.isInLocalFileSystem) return false
        val base = project.basePath ?: return false
        val path = file.path
        if (!path.startsWith("$base/")) return false
        if (path.removePrefix("$base/").split('/').any { it in EXCLUDED_DIRS }) return false
        if (file.length > MAX_BYTES || file.fileType.isBinary) return false
        if (!ProjectFileIndex.getInstance(project).isInContent(file)) return false
        return try {
            !ChangeListManager.getInstance(project).isIgnoredFile(file)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * When the file last changed on disk, as late as we can tell: modification time or inode change time. A rename
     * keeps the modification time but bumps the change time, so a file an agent moves into place is not mistaken
     * for one that has been sitting there since before the turn.
     */
    fun changeTime(file: VirtualFile): Long = try {
        val ctime = (java.nio.file.Files.getAttribute(java.nio.file.Path.of(file.path), "unix:ctime") as java.nio.file.attribute.FileTime).toMillis()
        maxOf(file.timeStamp, ctime)
    } catch (e: Exception) {
        file.timeStamp
    }

    /** Text with `\n` line endings and no BOM, as the editor's Document would hold it; null if unreadable. */
    fun readText(file: VirtualFile): String? = try {
        LoadTextUtil.loadText(file).toString()
    } catch (e: Exception) {
        null
    }
}
