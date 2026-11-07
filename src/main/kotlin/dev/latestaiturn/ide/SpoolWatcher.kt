package dev.latestaiturn.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import dev.latestaiturn.signals.SpoolParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Application-wide: polls the spool directory the agent hooks write into and routes each signal to matching projects. */
@Service(Service.Level.APP)
class SpoolWatcher {
    private val log = logger<SpoolWatcher>()
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ poll() }, 0, 250, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        try {
            val files = Files.newDirectoryStream(Paths.spoolDir(), "*.json").use { it.toList() }
            if (files.isEmpty()) return
            val ordered = files.map { it to Files.getLastModifiedTime(it).toMillis() }.sortedWith(compareBy({ it.second }, { it.first.fileName.toString() }))
            for ((file, mtime) in ordered) {
                handle(file, mtime)
                Files.deleteIfExists(file)
            }
        } catch (e: java.nio.file.NoSuchFileException) {
            // no hook has run yet
        } catch (e: Exception) {
            log.warn("Latest AI Turn: spool poll failed", e)
        }
    }

    private fun handle(file: Path, mtime: Long) {
        if (System.currentTimeMillis() - mtime > STALE_MILLIS) return
        val signal = SpoolParser.parse(file.fileName.toString(), Files.readString(file), mtime) ?: return
        for (project in ProjectManager.getInstance().openProjects) {
            val base = project.basePath ?: continue
            if (!project.isDisposed && signal.appliesTo(base)) TurnService.getInstance(project).onSignal(signal)
        }
    }

    companion object {
        private const val STALE_MILLIS = 10 * 60_000L
        fun getInstance(): SpoolWatcher = service()
    }
}

object Paths {
    fun home(): Path = Path.of(System.getProperty("user.home"))
    fun dataDir(): Path = home().resolve(".latest-ai-turn")
    /** Same override the hook script honors, so tests (and unusual setups) can redirect the spool. */
    fun spoolDir(): Path =
        (System.getProperty("latestaiturn.spoolDir") ?: System.getenv("LATEST_AI_TURN_SPOOL"))?.let { Path.of(it) }
            ?: dataDir().resolve("spool")
    fun script(): Path = dataDir().resolve("hook.sh")
}
