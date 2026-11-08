package dev.latestaiturn.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import dev.latestaiturn.core.TrackingScope
import dev.latestaiturn.core.sha256

/**
 * The folders to track for one project, relative to its root. Kept in the IDE's application settings, keyed by the
 * project path, so the project's own files (for example `.idea/`) are never written.
 */
object TrackedFolders {
    private fun key(project: Project): String =
        "latestaiturn.trackedFolders." + sha256((project.basePath ?: "").trimEnd('/')).take(16)

    fun get(project: Project): List<String> =
        TrackingScope.parse(PropertiesComponent.getInstance().getValue(key(project), ""))

    fun set(project: Project, folders: List<String>) {
        PropertiesComponent.getInstance().setValue(key(project), TrackingScope.format(folders))
    }
}
