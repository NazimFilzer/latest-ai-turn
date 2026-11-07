package dev.latestaiturn.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.VirtualFile

/** Decides which files take part in turn tracking, and reads their text the way the editor sees it. */
object TrackedFiles {
    const val MAX_BYTES = 1_048_576L

    /** Never tracked, wherever they appear in the project: IDE metadata, VCS data, dependency and build output. */
    private val EXCLUDED_DIRS = setOf(
        ".git", ".idea", ".gradle", ".vscode", "node_modules", "out", "build", "dist", "target", ".venv", "__pycache__", ".next",
    )

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

    /** Text with `\n` line endings and no BOM, as the editor's Document would hold it; null if unreadable. */
    fun readText(file: VirtualFile): String? = try {
        LoadTextUtil.loadText(file).toString()
    } catch (e: Exception) {
        null
    }
}
