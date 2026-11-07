package dev.latestaiturn.ide

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import dev.latestaiturn.core.DiskBlobStore
import dev.latestaiturn.core.TrackerSnapshot
import dev.latestaiturn.core.TurnTracker
import dev.latestaiturn.core.sha256
import dev.latestaiturn.signals.SignalKind
import dev.latestaiturn.signals.TurnSignal
import dev.latestaiturn.signals.applyTo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-project glue between the IDE and the pure [TurnTracker]: feeds it own edits (document events) and external
 * changes (VFS refresh events), applies Turn Signals, keeps it persisted, and repaints highlights.
 */
@Service(Service.Level.PROJECT)
class TurnService(val project: Project) : Disposable {
    private val log = logger<TurnService>()
    private val basePath: String = project.basePath?.trimEnd('/') ?: ""
    private val dir: Path = Path.of(PathManager.getSystemPath(), "latest-ai-turn", sha256(basePath).take(16))
    private val blobs = DiskBlobStore(dir.resolve("blobs"))
    val tracker = TurnTracker(blobs)
    val stateFile: Path get() = dir.resolve("state.json")

    private val started = AtomicBoolean(false)
    @Volatile var scanning = true
        private set
    private val reloading: MutableSet<Document> = Collections.newSetFromMap(IdentityHashMap())
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val notifyAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile private var dirty = false
    @Volatile private var lastEndMillis = 0L
    private var foreignCount = 0

    var highlightsEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PREF_HIGHLIGHTS, true)
        set(v) { PropertiesComponent.getInstance().setValue(PREF_HIGHLIGHTS, v, true); scheduleRefresh() }

    var backgroundEnabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PREF_BACKGROUND, true)
        set(v) { PropertiesComponent.getInstance().setValue(PREF_BACKGROUND, v, true); scheduleRefresh() }

    // ---------- lifecycle ----------

    fun start() {
        if (!started.compareAndSet(false, true) || basePath.isEmpty()) return
        loadState()
        val bus = project.messageBus.connect(this)
        bus.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onVfsEvents(events)
        })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
                override fun beforeFileContentReload(file: VirtualFile, document: Document) { reloading.add(document) }
                override fun fileContentReloaded(file: VirtualFile, document: Document) { reloading.remove(document) }
            })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = onDocumentChanged(event)
        }, this)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) { if (event.editor.project == project) paintEditor(event.editor) }
        }, this)
        SpoolWatcher.getInstance().ensureStarted()
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ persistIfDirty() }, 5, 5, TimeUnit.SECONDS)
        ApplicationManager.getApplication().executeOnPooledThread { reconcile() }
        scheduleRefresh()
    }

    override fun dispose() {
        persistNow()
    }

    // ---------- user actions ----------

    /** Manual Turn Start: saves editors first so disk, editor and baseline agree. */
    fun manualStart() {
        FileDocumentManager.getInstance().saveAllDocuments()
        tracker.startTurn(sessionId = null, atMillis = System.currentTimeMillis())
        turnStateChanged()
    }

    /** Turn End: refresh the project from disk first so the agent's last writes are still counted. */
    fun manualEnd() = endAfterRefresh(null)

    fun rebaseline() {
        tracker.rebaseline()
        turnStateChanged()
        notify("Re-baselined: current file contents are now the baseline.")
    }

    fun onSignal(signal: TurnSignal) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            when (signal.kind) {
                SignalKind.END -> endAfterRefresh(signal.sessionId)
                else -> {
                    val startsTurn = signal.kind == SignalKind.START || tracker.activeTurn()?.sessionId != signal.sessionId
                    // Start first: the tracker is thread-safe and the turn must not wait on anything else.
                    signal.applyTo(tracker)
                    turnStateChanged()
                    // Saving changes the model, which is not allowed under ModalityState.any(); do it separately.
                    // It runs just after the turn starts, so it narrows but does not close the window for an agent's first write.
                    if (startsTurn) ApplicationManager.getApplication().invokeLater(
                        { FileDocumentManager.getInstance().saveAllDocuments() }, ModalityState.nonModal(),
                    )
                }
            }
        }, ModalityState.any())
    }

    private fun endAfterRefresh(sessionId: String?) {
        val ending = tracker.activeTurn() ?: return
        val roots = ProjectRootManager.getInstance(project).contentRoots
        val finish = Runnable {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                // The refresh takes a moment; if the next prompt already started a new turn, leave that one alone.
                if (tracker.activeTurn() == ending && tracker.endTurn(sessionId)) lastEndMillis = System.currentTimeMillis()
                turnStateChanged()
            }, ModalityState.any())
        }
        LocalFileSystem.getInstance().refreshFiles(roots.asList(), true, true, finish)
    }

    private fun turnStateChanged() {
        dirty = true
        scheduleRefresh()
    }

    // ---------- own edits ----------

    private fun onDocumentChanged(event: DocumentEvent) {
        val doc = event.document
        if (doc in reloading) return
        val file = FileDocumentManager.getInstance().getFile(doc) ?: return
        val path = file.path
        if (!tracker.isTracked(path)) {
            if (!path.startsWith("$basePath/") || !TrackedFiles.isEligible(project, file)) return
            // The edit beat the scan to this file: reconstruct the text from before the edit as its baseline.
            val after = doc.immutableCharSequence
            val before = StringBuilder(after).replace(event.offset, event.offset + event.newLength, event.oldFragment.toString())
            tracker.seedIfAbsent(path, before.toString())
        }
        tracker.onOwnEdit(path, doc.immutableCharSequence.toString())
        dirty = true
        scheduleRefresh()
    }

    // ---------- external changes ----------

    private fun onVfsEvents(events: List<VFileEvent>) {
        var touched = false
        for (e in events) {
            try {
                touched = handleEvent(e) || touched
            } catch (ex: Exception) {
                log.warn("Latest AI Turn: failed to handle $e", ex)
            }
        }
        if (touched) { dirty = true; scheduleRefresh() }
    }

    /**
     * External = noticed by a refresh of the disk: attributed to the turn. Anything else was done by the IDE itself
     * (rename, delete, create from a template, refactoring writes): that is the user's own doing and never counts.
     */
    private fun handleEvent(e: VFileEvent): Boolean = when (e) {
        is VFileContentChangeEvent -> when {
            e.isFromRefresh -> external(e.file, false)
            e.isFromSave -> false
            else -> ideWrote(e.file)
        }
        is VFileCreateEvent -> e.file?.let { if (e.isFromRefresh) external(it, true) else ideCreated(it) } ?: false
        is VFileCopyEvent -> e.findCreatedFile()?.let { if (e.isFromRefresh) external(it, true) else ideCreated(it) } ?: false
        is VFileDeleteEvent -> if (e.isFromRefresh) deleted(e.path) else ideDeleted(e.path)
        is VFileMoveEvent -> if (e.isFromRefresh) moved(e.oldPath, e.file) else ideMoved(e.oldPath, e.file.path)
        is VFilePropertyChangeEvent ->
            if (!e.isRename) false else if (e.isFromRefresh) moved(e.oldPath, e.file) else ideMoved(e.oldPath, e.file.path)
        else -> false
    }

    /** The IDE wrote a file's content without a refresh and without saving an editor: absorb it as an own edit. */
    private fun ideWrote(file: VirtualFile): Boolean {
        if (file.isDirectory || FileDocumentManager.getInstance().getCachedDocument(file) != null) return false // editors report their own edits
        return absorbFromIde(file)
    }

    private fun ideCreated(file: VirtualFile): Boolean {
        if (file.isDirectory) {
            var any = false
            com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(file, object : com.intellij.openapi.vfs.VirtualFileVisitor<Unit>() {
                override fun visitFile(f: VirtualFile): Boolean { if (!f.isDirectory) any = absorbFromIde(f) || any; return true }
            })
            return any
        }
        return absorbFromIde(file)
    }

    private fun absorbFromIde(file: VirtualFile): Boolean {
        val path = file.path
        if (!path.startsWith("$basePath/")) return false
        if (!tracker.isTracked(path) && !TrackedFiles.isEligible(project, file)) return false
        val text = TrackedFiles.readText(file) ?: return false
        if (tracker.isTracked(path)) tracker.onOwnEdit(path, text) else tracker.seedIfAbsent(path, text)
        return true
    }

    private fun ideDeleted(path: String): Boolean {
        val gone = tracker.trackedPaths().filter { it == path || it.startsWith("$path/") }
        gone.forEach(tracker::forget)
        return gone.isNotEmpty()
    }

    private fun ideMoved(oldPath: String, newPath: String): Boolean {
        if (tracker.trackedPaths().none { it == oldPath || it.startsWith("$oldPath/") }) return false
        tracker.move(oldPath, newPath)
        return true
    }

    private fun external(file: VirtualFile, created: Boolean): Boolean {
        if (file.isDirectory) {
            var any = false
            com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(file, object : com.intellij.openapi.vfs.VirtualFileVisitor<Unit>() {
                override fun visitFile(f: VirtualFile): Boolean { if (!f.isDirectory) any = external(f, true) || any; return true }
            })
            return any
        }
        val path = file.path
        if (!path.startsWith("$basePath/")) return false
        if (!tracker.isTracked(path) && !TrackedFiles.isEligible(project, file)) return false
        val text = TrackedFiles.readText(file) ?: return false
        val mtime = TrackedFiles.changeTime(file)
        if (!tracker.isTracked(path) && !created) { tracker.seedIfAbsent(path, text); return true }
        countForeign(mtime)
        tracker.onExternalChange(path, text, mtime)
        return true
    }

    private fun deleted(path: String): Boolean {
        val gone = tracker.trackedPaths().filter { it == path || it.startsWith("$path/") }
        gone.forEach {
            countForeign(System.currentTimeMillis())
            tracker.onExternalChange(it, null, System.currentTimeMillis())
        }
        return gone.isNotEmpty()
    }

    private fun moved(oldPath: String, newFile: VirtualFile): Boolean {
        val a = deleted(oldPath)
        val b = external(newFile, true)
        return a || b
    }

    private fun countForeign(mtime: Long) {
        val turn = tracker.activeTurn()
        if (turn != null && mtime >= turn.startedAtMillis) return
        if (System.currentTimeMillis() - lastEndMillis < 15_000) return // the agent's own trailing writes
        foreignCount++
        notifyAlarm.cancelAllRequests()
        notifyAlarm.addRequest({
            val n = foreignCount; foreignCount = 0
            if (n > 0) {
                val hint = if (anyHooksInstalled()) " Hooks are installed but no turn signal arrived: if an agent was working, its hook may not be firing. Use Start Turn." else ""
                notify("$n file${if (n == 1) "" else "s"} changed outside a turn and ${if (n == 1) "was" else "were"} absorbed into the baseline.$hint")
            }
        }, 4_000)
    }

    // ---------- scan, persistence ----------

    /** Restores saved state, then makes the tracker agree with the disk: absorb offline changes, seed new files. */
    private fun reconcile() {
        try {
            val candidates = ReadAction.compute<List<VirtualFile>, RuntimeException> {
                val out = ArrayList<VirtualFile>()
                ProjectFileIndex.getInstance(project).iterateContent { f ->
                    if (!f.isDirectory && TrackedFiles.isEligible(project, f)) out.add(f)
                    true
                }
                out
            }
            var offline = 0
            var scanned = 0
            val seen = HashSet<String>(candidates.size * 2)
            for (f in candidates) {
                if (project.isDisposed) return
                val path = f.path
                seen.add(path)
                if (++scanned % 300 == 0) blobs.flush()
                val text = TrackedFiles.readText(f) ?: continue
                if (tracker.isTracked(path)) {
                    if (tracker.currentHash(path) != sha256(text)) { tracker.onOwnEdit(path, text); offline++ }
                } else {
                    tracker.seedIfAbsent(path, text)
                }
            }
            for (p in tracker.trackedPaths() - seen) if (!Files.exists(Path.of(p))) { tracker.forget(p); offline++ }
            blobs.flush()
            scanning = false
            dirty = true
            ApplicationManager.getApplication().invokeLater({
                if (offline > 0) notify("$offline file${if (offline == 1) "" else "s"} changed while the IDE was closed and ${if (offline == 1) "was" else "were"} absorbed into the baseline.")
                turnStateChanged()
            }, ModalityState.any())
            val snapshot = tracker.snapshot()
            blobs.gc(snapshot.files.flatMapTo(HashSet()) { listOfNotNull(it.baseline, it.current) })
        } catch (e: Exception) {
            log.warn("Latest AI Turn: scan failed", e)
            scanning = false
        }
    }

    private fun loadState() {
        try {
            val f = dir.resolve("state.json")
            if (Files.exists(f)) {
                Gson().fromJson(Files.readString(f), TrackerSnapshot::class.java)?.let { tracker.restore(it) }
            }
        } catch (e: Exception) {
            log.warn("Latest AI Turn: ignoring unreadable saved state", e)
        }
    }

    private fun persistIfDirty() { if (dirty && !scanning) persistNow() }

    fun persistNow() {
        dirty = false
        try {
            val mark = blobs.mark()
            val snapshot = tracker.snapshot()
            blobs.flush(snapshot.files.flatMapTo(HashSet()) { listOfNotNull(it.baseline, it.current) }, mark)
            Files.createDirectories(dir)
            val tmp = dir.resolve("state.json.tmp")
            Files.writeString(tmp, Gson().toJson(snapshot))
            Files.move(tmp, dir.resolve("state.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            log.warn("Latest AI Turn: could not save state", e)
        }
    }

    // ---------- painting ----------

    fun scheduleRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refreshAll() }, 120)
    }

    private fun refreshAll() {
        if (project.isDisposed) return
        for (editor in EditorFactory.getInstance().allEditors) if (editor.project == project) paintEditor(editor)
        WindowManager.getInstance().getStatusBar(project)?.updateWidget(StatusWidgetFactory.ID)
    }

    private fun paintEditor(editor: Editor) {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (file == null || !highlightsEnabled || scanning && !tracker.isTracked(file.path)) { TurnPaint.clear(editor); return }
        TurnPaint.paint(editor, tracker.turnChanges(file.path), backgroundEnabled)
    }

    fun notify(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Latest AI Turn")
            .createNotification(message, NotificationType.INFORMATION).notify(project)
    }

    companion object {
        const val PREF_HIGHLIGHTS = "latestaiturn.highlights"
        const val PREF_BACKGROUND = "latestaiturn.background"
        fun getInstance(project: Project): TurnService = project.service()
    }
}
