package dev.latestaiturn.ide

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginStateListener
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The baseline copies and saved state kept in the IDE system directory (all projects). They are removed when the
 * plugin is uninstalled. Closing a project does not remove them: the turn must survive an IDE restart.
 * `~/.latest-ai-turn` (hook script, spool) is left alone, because the agents' hook entries still point at it;
 * "Uninstall Agent Hooks…" is the way to remove those entries first.
 */
object StoredData {
    private const val PLUGIN_ID = "dev.latestaiturn"
    /** File in each project folder holding the project path, so a folder whose project is gone can be recognised. */
    const val MARKER = "project.txt"
    const val ABANDONED_AFTER_MILLIS = 30L * 24 * 60 * 60_000
    private val log = logger<StoredData>()
    private val watching = AtomicBoolean(false)

    /** Set once the plugin is uninstalled: nothing may write the data again (a closing project saves on dispose). */
    @Volatile var uninstalled = false
        private set

    fun root(): Path = Path.of(PathManager.getSystemPath(), "latest-ai-turn")

    private val pruned = AtomicBoolean(false)

    /** Once per IDE run, off the UI thread. */
    fun pruneAbandonedOnce() {
        if (pruned.compareAndSet(false, true)) com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { pruneAbandoned(root()) }
    }

    /** Registers (once) the uninstall hook. Called at project startup, while the plugin is alive. */
    fun watchUninstall() {
        if (!watching.compareAndSet(false, true)) return
        PluginInstaller.addStateListener(object : PluginStateListener {
            override fun install(descriptor: IdeaPluginDescriptor) {}

            override fun uninstall(descriptor: IdeaPluginDescriptor) {
                if (descriptor.pluginId.idString != PLUGIN_ID) return
                uninstalled = true
                deleteTree(root())
            }
        })
    }

    /**
     * Deletes project folders whose project directory no longer exists and which have not been saved for
     * [olderThanMillis] (a project on an unplugged drive keeps its state for that long). Folders without a
     * [MARKER] (written by older versions) are left alone until their project is opened again.
     */
    fun pruneAbandoned(root: Path, olderThanMillis: Long = ABANDONED_AFTER_MILLIS) {
        try {
            if (!Files.isDirectory(root)) return
            val cutoff = System.currentTimeMillis() - olderThanMillis
            Files.newDirectoryStream(root).use { stream ->
                for (folder in stream) {
                    val marker = folder.resolve(MARKER)
                    if (!Files.isRegularFile(marker)) continue
                    val projectPath = Files.readString(marker).trim()
                    if (projectPath.isEmpty() || Files.exists(Path.of(projectPath))) continue
                    val state = folder.resolve("state.json")
                    val saved = Files.getLastModifiedTime(if (Files.exists(state)) state else folder).toMillis()
                    if (saved < cutoff) deleteTree(folder)
                }
            }
        } catch (e: Exception) {
            log.warn("Latest AI Turn: could not prune abandoned project folders", e)
        }
    }

    /** Best effort: a file that cannot be deleted is skipped and logged. A missing [root] is fine. */
    fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    try { Files.deleteIfExists(file) } catch (e: IOException) { log.warn("Latest AI Turn: could not delete $file", e) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    try { Files.deleteIfExists(dir) } catch (e: IOException) { log.warn("Latest AI Turn: could not delete $dir", e) }
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (e: IOException) {
            log.warn("Latest AI Turn: could not clear stored data in $root", e)
        }
    }
}
