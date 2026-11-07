package dev.latestaiturn.ide

import java.nio.file.Path

/** Where the plugin keeps its files outside the project. */
object PluginPaths {
    fun home(): Path = Path.of(System.getProperty("user.home"))
    fun dataDir(): Path = home().resolve(".latest-ai-turn")
    /** Same override the hook script honors, so tests (and unusual setups) can redirect the spool. */
    fun spoolDir(): Path =
        (System.getProperty("latestaiturn.spoolDir") ?: System.getenv("LATEST_AI_TURN_SPOOL"))?.let { Path.of(it) }
            ?: dataDir().resolve("spool")
    fun script(): Path = dataDir().resolve("hook.sh")
}
