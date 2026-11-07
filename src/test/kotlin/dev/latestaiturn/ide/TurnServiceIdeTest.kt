package dev.latestaiturn.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import dev.latestaiturn.core.ChangeKind
import dev.latestaiturn.core.LineChange
import dev.latestaiturn.hooks.HookScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Runs the real plugin in a headless IDE with a real on-disk project: VFS events, editor events, hook script, markup. */
class TurnServiceIdeTest {
    private lateinit var fixture: IdeaProjectTestFixture
    private lateinit var root: Path
    private lateinit var spool: Path
    private lateinit var svc: TurnService
    private lateinit var main: Path
    private lateinit var base: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("latest-ai-turn-ide").toRealPath()
        spool = Files.createTempDirectory("latest-ai-turn-spool").toRealPath()
        System.setProperty("latestaiturn.spoolDir", spool.toString())
        val builder = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder("lat", root, true)
        builder.addModule(EmptyModuleFixtureBuilder::class.java).addContentRoot(root.toString())
        fixture = builder.fixture
        fixture.setUp()
        // the fixture places the project one directory below `root`; files must live under the real base path
        base = Path.of(fixture.project.basePath!!)
        Files.createDirectories(base)
        main = base.resolve("Main.txt")
        Files.writeString(main, "l1\nl2\nl3\n")
        runInEdtAndWait { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(main) }
        svc = TurnService.getInstance(fixture.project)
        svc.start()
        waitFor("initial scan") { !svc.scanning }
    }

    @After
    fun tearDown() {
        System.clearProperty("latestaiturn.spoolDir")
        runInEdtAndWait { fixture.tearDown() }
        root.toFile().deleteRecursively(); spool.toFile().deleteRecursively()
    }

    private fun waitFor(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            runInEdtAndWait { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); ok = cond() }
            if (ok) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /** What an agent does: rewrite the file on disk, then the IDE notices on refresh. */
    private fun agentWrites(file: Path, text: String) {
        Files.writeString(file, text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5))
        runInEdtAndWait {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
            VfsUtil.markDirtyAndRefresh(false, false, false, vf)
        }
    }

    private fun vfile(p: Path): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p)!!
    private fun changes(p: Path) = svc.tracker.turnChanges(p.toString())
    private fun gutterBars(editor: Editor) = editor.markupModel.allHighlighters.count { it.lineMarkerRenderer != null }

    @Test
    fun `agent edit is highlighted, own typing is not, reload is not mistaken for typing`() {
        runInEdtAndWait { svc.manualStart() }
        val vf = vfile(main)
        lateinit var editor: Editor

        runInEdtAndWait {
            val doc = FileDocumentManager.getInstance().getDocument(vf)!!
            editor = EditorFactory.getInstance().createEditor(doc, fixture.project)
        }
        try {
            // 1. the agent edits line 2 on disk
            agentWrites(main, "l1\nL2\nl3\n")
            assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 1, 1)), changes(main))
            waitFor("document reloaded") { editor.document.text == "l1\nL2\nl3\n" }
            waitFor("gutter bar painted") { gutterBars(editor) == 1 }

            // 2. the user types a line at the top: absorbed, highlight just moves down
            runInEdtAndWait { WriteCommandAction.runWriteCommandAction(fixture.project) { editor.document.insertString(0, "top\n") } }
            assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 2, 1)), changes(main))
            assertTrue(svc.tracker.baselineText(main.toString())!!.startsWith("top\n"))

            // 3. the agent edits again after the user saved: the reload must not count as the user's typing
            runInEdtAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
            // saving reaches the disk asynchronously; an agent write before that would be overwritten by the IDE
            waitFor("save reached disk") { Files.readString(main) == "top\nl1\nL2\nl3\n" }
            agentWrites(main, "top\nl1\nL2\nl3\nagent-added\n")
            try {
                waitFor("second reload", 8_000) { editor.document.text.contains("agent-added") }
            } catch (e: AssertionError) {
                var info = ""
                runInEdtAndWait {
                    val d = editor.document
                    info = "doc=${d.text.replace("\n", "|")} disk=${Files.readString(main).replace("\n", "|")} " +
                        "vfsLen=${vf.length} diskLen=${Files.size(main)} unsaved=${FileDocumentManager.getInstance().isDocumentUnsaved(d)} " +
                        "vfsTs=${vf.timeStamp} diskTs=${Files.getLastModifiedTime(main).toMillis()}"
                }
                throw AssertionError("second reload failed: $info", e)
            }
            assertEquals(
                listOf(LineChange(ChangeKind.MODIFIED, 2, 1), LineChange(ChangeKind.ADDED, 4, 1)),
                changes(main),
            )
            assertEquals("top\nl1\nl2\nl3\n", svc.tracker.baselineText(main.toString()))

            // 4. ending the turn keeps highlights; starting a new one clears them
            runInEdtAndWait { svc.manualEnd() }
            waitFor("turn ended") { svc.tracker.activeTurn() == null }
            assertEquals(2, changes(main).size)
            runInEdtAndWait { svc.manualStart() }
            waitFor("highlights cleared") { gutterBars(editor) == 0 }
            assertEquals(emptyList<LineChange>(), changes(main))
        } finally {
            runInEdtAndWait { EditorFactory.getInstance().releaseEditor(editor) }
        }
    }

    @Test
    fun `a real hook script run starts and ends the turn through the spool`() {
        val script = Files.createTempFile("hook", ".sh").also { Files.writeString(it, HookScript.TEXT) }
        fun fire(app: String, event: String, payload: String) {
            val p = ProcessBuilder("sh", script.toString(), app, event).redirectErrorStream(true)
            p.environment()["LATEST_AI_TURN_SPOOL"] = spool.toString()
            val proc = p.start()
            proc.outputStream.use { it.write(payload.toByteArray()) }
            proc.waitFor()
        }
        val cwd = base.toString()
        fire("claude", "start", """{"session_id":"sess-1","cwd":"$cwd","hook_event_name":"UserPromptSubmit"}""")
        waitFor("turn started from hook") { svc.tracker.activeTurn()?.sessionId == "sess-1" }

        agentWrites(main, "l1\nl2\nl3\nmore\n")
        assertEquals(listOf(LineChange(ChangeKind.ADDED, 3, 1)), changes(main))

        // another session's Stop is ignored; a Stop for a different project directory is ignored
        fire("codex", "end", """{"session_id":"other","cwd":"$cwd","hook_event_name":"Stop"}""")
        fire("claude", "end", """{"session_id":"sess-1","cwd":"/somewhere/else","hook_event_name":"Stop"}""")
        Thread.sleep(1_200)
        assertNotNull(svc.tracker.activeTurn())

        fire("claude", "end", """{"session_id":"sess-1","cwd":"$cwd","hook_event_name":"Stop"}""")
        waitFor("turn ended from hook") { svc.tracker.activeTurn() == null }
        assertEquals(1, changes(main).size)
        assertEquals(0, Files.list(spool).use { it.filter { f -> f.fileName.toString().endsWith(".json") }.count() }.toInt())
    }

    @Test
    fun `persisted state on disk contains the open turn and the changed file`() {
        runInEdtAndWait { svc.manualStart() }
        agentWrites(main, "l1\nl2\nl3\nmore\n")
        svc.persistNow()

        val saved = com.google.gson.Gson().fromJson(Files.readString(svc.stateFile), dev.latestaiturn.core.TrackerSnapshot::class.java)
        assertNotNull(saved.turn)
        val entry = saved.files.single { it.path == main.toString() }
        assertTrue(entry.baseline != entry.current)
        // blobs for both versions were flushed to disk, so a restart can read them back
        assertTrue(Files.list(svc.stateFile.parent.resolve("blobs")).use { it.count() } >= 2)
    }
}
