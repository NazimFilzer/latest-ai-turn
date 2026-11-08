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
import org.junit.Assert.assertFalse
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
    private lateinit var other: Path

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
        other = base.resolve("Other.txt")
        Files.writeString(other, "o1\n")
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
        // payloads hold prompt text: the spool folder must be private to this user
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(spool)))
    }

    @Test
    fun `a tracked file that grows past the size limit is forgotten instead of highlighted forever`() {
        runInEdtAndWait { svc.manualStart() }
        assertTrue(svc.tracker.isTracked(other.toString()))

        agentWrites(other, "x".repeat(TrackedFiles.MAX_BYTES.toInt() + 10) + "\n")

        waitFor("oversized file forgotten") { !svc.tracker.isTracked(other.toString()) }
        assertFalse(svc.tracker.changedPaths().contains(other.toString()))
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

    @Test
    fun `renames, creates and deletes done through the IDE during a turn are never highlighted`() {
        runInEdtAndWait { svc.manualStart() }
        val project = fixture.project

        WriteCommandAction.runWriteCommandAction(project) { vfile(main).rename(this, "Renamed.txt") }
        WriteCommandAction.runWriteCommandAction(project) {
            val created = vfile(base).createChildData(this, "New.txt")
            VfsUtil.saveText(created, "hello\n")
        }
        WriteCommandAction.runWriteCommandAction(project) { vfile(other).delete(this) }

        assertEquals(emptyList<String>(), svc.tracker.changedPaths())
        assertTrue(svc.tracker.isTracked(base.resolve("Renamed.txt").toString()))
        assertTrue(!svc.tracker.isTracked(main.toString()))
        assertTrue(svc.tracker.isTracked(base.resolve("New.txt").toString()))
        assertTrue(!svc.tracker.isTracked(other.toString()))
    }

    @Test
    fun `an agent moving an old file into place during a turn is shown as the turn's change`() {
        runInEdtAndWait { svc.manualStart() }
        val moved = base.resolve("Moved.txt")

        Files.move(main, moved) // keeps the old modification time; only the change time is new
        runInEdtAndWait { VfsUtil.markDirtyAndRefresh(false, true, true, vfile(base)) }

        assertEquals(listOf(LineChange(ChangeKind.ADDED, 0, 3)), svc.tracker.turnChanges(moved.toString()))
        assertTrue(svc.tracker.changedPaths().contains(main.toString())) // the old path counts as deleted
    }

    @Test
    fun `the violet gutter bar is really painted inside the visible gutter`() {
        runInEdtAndWait { svc.manualStart() }
        val vf = vfile(main)
        lateinit var editor: com.intellij.openapi.editor.ex.EditorEx
        runInEdtAndWait {
            editor = EditorFactory.getInstance().createEditor(FileDocumentManager.getInstance().getDocument(vf)!!, fixture.project) as com.intellij.openapi.editor.ex.EditorEx
        }
        try {
            agentWrites(main, "l1\nL2\nl3\n")
            waitFor("bar highlighter") { gutterBars(editor) == 1 }
            var violet = 0
            var minX = Int.MAX_VALUE
            var maxX = -1
            var width = 0
            runInEdtAndWait {
                val gutter = editor.gutterComponentEx
                width = gutter.preferredSize.width.coerceAtLeast(60)
                gutter.setSize(width, 200)
                val img = java.awt.image.BufferedImage(width, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                gutter.paint(g)
                g.dispose()
                val target = TurnPaint.GUTTER_COLOR.rgb and 0xFFFFFF
                fun close(a: Int, b: Int) = Math.abs((a shr 16 and 255) - (b shr 16 and 255)) + Math.abs((a shr 8 and 255) - (b shr 8 and 255)) + Math.abs((a and 255) - (b and 255)) < 40
                for (x in 0 until width) for (y in 0 until 200) {
                    if (close(img.getRGB(x, y) and 0xFFFFFF, target)) { violet++; minX = minOf(minX, x); maxX = maxOf(maxX, x) }
                }
            }
            println("GUTTER_PIXELS violet=$violet x=$minX..$maxX gutterWidth=$width")
            assertTrue("no violet pixels were painted in the gutter (violet=$violet, width=$width)", violet > 0)
            assertTrue("bar painted outside the gutter: x=$minX..$maxX width=$width", minX >= 0 && maxX < width)
        } finally {
            runInEdtAndWait { EditorFactory.getInstance().releaseEditor(editor) }
        }
    }

    @Test
    fun `only the tracked folders take part in tracking`() {
        val src = Files.createDirectories(base.resolve("src")).resolve("A.txt")
        val dep = Files.createDirectories(base.resolve("dep")).resolve("B.txt")
        Files.writeString(src, "a\n")
        Files.writeString(dep, "b\n")
        runInEdtAndWait { VfsUtil.markDirtyAndRefresh(false, true, true, vfile(base)) }

        runInEdtAndWait { svc.setTrackedFolders(listOf("src")) }

        waitFor("src file tracked") { svc.tracker.isTracked(src.toString()) }
        assertFalse(svc.tracker.isTracked(dep.toString()))
        assertFalse(svc.tracker.isTracked(main.toString()))
    }

    @Test
    fun `typing in a very large file does not pile up unsaved copies of it and loses nothing`() {
        val big = base.resolve("Big.txt")
        Files.writeString(big, (1..20_000).joinToString("\n", postfix = "\n") { "line $it of the big file, padding padding" })
        runInEdtAndWait { VfsUtil.markDirtyAndRefresh(false, false, false, vfile(big)) }
        waitFor("big file tracked") { svc.tracker.isTracked(big.toString()) }
        runInEdtAndWait { svc.manualStart() }
        lateinit var doc: com.intellij.openapi.editor.Document
        runInEdtAndWait { doc = FileDocumentManager.getInstance().getDocument(vfile(big))!! }

        val blobs = TurnService::class.java.getDeclaredField("blobs").also { it.isAccessible = true }.get(svc) as dev.latestaiturn.core.DiskBlobStore
        var peak = 0L
        repeat(100) {
            runInEdtAndWait { WriteCommandAction.runWriteCommandAction(fixture.project) { doc.insertString(0, "k") } }
            peak = maxOf(peak, blobs.pendingChars())
        }

        // Without the early save, 100 keystrokes would leave about 170 million characters waiting (two copies each)
        // until the 5-second timer; with it the pile-up stays near the 16 million limit.
        assertTrue("unsaved text piled up: peak $peak characters", peak < 60_000_000)
        waitFor("tracker text equals the document") { svc.tracker.currentText(big.toString()) == doc.text } // a pruned-away blob would read as null
    }
}
