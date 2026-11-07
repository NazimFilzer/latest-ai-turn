package dev.latestaiturn.core

enum class ChangeKind { ADDED, MODIFIED, DELETED }

/** A highlighted region in the file's *current* text. DELETED has lineCount 0 and marks the line after the removal. */
data class LineChange(val kind: ChangeKind, val startLine: Int, val lineCount: Int)

data class ActiveTurn(val sessionId: String?, val startedAtMillis: Long)

/** Serializable tracker state; texts live in the [BlobStore], referenced by hash. */
data class FileSnapshot(val path: String, val baseline: String?, val current: String?, val owned: List<Int> = emptyList())
data class TrackerSnapshot(val turn: ActiveTurn?, val files: List<FileSnapshot>)

/**
 * Pure model of "what did the latest AI turn change". No IDE types; all text uses `\n` line endings.
 * Paths are opaque keys. All public methods are synchronized.
 */
class TurnTracker(private val store: BlobStore = InMemoryBlobStore()) {
    private companion object { const val JOURNAL_WINDOW_MILLIS = 30_000L }

    /** `owned` = lines (in current-text coordinates) the user wrote or rewrote inside an agent hunk. */
    private class FileState(var baseline: String?, var current: String?, var owned: Set<Int> = emptySet())

    private val files = LinkedHashMap<String, FileState>()
    private var turn: ActiveTurn? = null

    @Synchronized
    fun seed(path: String, text: String?) {
        val h = text?.let(store::put)
        files[path] = FileState(h, h)
    }

    /** A foreign change absorbed while no turn was open; lets a late Turn Start signal undo the absorb. */
    private class Absorbed(val path: String, val prevCurrent: String?, val newCurrent: String?, val atMillis: Long)

    private val journal = ArrayList<Absorbed>()

    @Synchronized
    fun activeTurn(): ActiveTurn? = turn

    @Synchronized
    fun isTracked(path: String): Boolean = path in files

    @Synchronized
    fun snapshot(): TrackerSnapshot =
        TrackerSnapshot(turn, files.map { (p, st) -> FileSnapshot(p, st.baseline, st.current, st.owned.sorted()) })

    @Synchronized
    fun restore(snapshot: TrackerSnapshot) {
        files.clear()
        journal.clear()
        for (f in snapshot.files) files[f.path] = FileState(f.baseline, f.current, f.owned.toSet())
        turn = snapshot.turn
    }

    @Synchronized
    fun trackedPaths(): Set<String> = files.keys.toSet()

    @Synchronized
    fun currentText(path: String): String? = files[path]?.current?.let(store::get)

    @Synchronized
    fun forget(path: String) { files.remove(path) }

    /** Content hash of the file's current text, used to detect changes made while the IDE was closed. */
    @Synchronized
    fun currentHash(path: String): String? = files[path]?.current

    /**
     * Freezes the Baseline: every file's current text becomes its baseline, and previous highlights clear.
     * [atMillis] is when the agent really started; foreign changes already absorbed with a modification time
     * at or after it are put back into the turn, because the start signal only reached us late.
     */
    /** Seeds only files we have never seen, so a late scan cannot erase a change already recorded. */
    @Synchronized
    fun seedIfAbsent(path: String, text: String?): Boolean {
        if (path in files) return false
        seed(path, text)
        return true
    }

    @Synchronized
    fun startTurn(sessionId: String?, atMillis: Long) {
        val revertTo = LinkedHashMap<String, String?>()
        val latestNew = HashMap<String, String?>()
        for (e in journal.sortedBy { it.atMillis }) {
            if (e.atMillis < atMillis) continue
            if (e.path !in revertTo) revertTo[e.path] = e.prevCurrent
            latestNew[e.path] = e.newCurrent
        }
        for (st in files.values) { st.baseline = st.current; st.owned = emptySet() }
        for ((path, prev) in revertTo) {
            val st = files.getOrPut(path) { FileState(null, null) }
            if (st.current == latestNew[path]) st.baseline = prev
        }
        journal.clear()
        turn = ActiveTurn(sessionId, atMillis)
    }

    /** Ends the open turn; highlights stay. A mismatching [sessionId] (another agent's Stop) is ignored. */
    @Synchronized
    fun endTurn(sessionId: String? = null): Boolean {
        val t = turn ?: return false
        if (sessionId != null && t.sessionId != null && t.sessionId != sessionId) return false
        turn = null
        return true
    }

    /** Absorbs everything: current text becomes the baseline of every file. The turn stays open. */
    @Synchronized
    fun rebaseline() {
        for (st in files.values) { st.baseline = st.current; st.owned = emptySet() }
        journal.clear()
    }

    @Synchronized
    fun onExternalChange(path: String, text: String?, modifiedAtMillis: Long) {
        val state = files.getOrPut(path) { FileState(null, null) }
        val open = turn
        // Not the open turn's change when no turn is open, or when it was written before the turn started
        // (the IDE only noticed it late).
        if (open == null || modifiedAtMillis < open.startedAtMillis) {
            // Foreign change: fold it into the baseline exactly like an own edit, and remember it briefly.
            val prev = state.current
            if (text == null) { state.baseline = null; state.current = null; state.owned = emptySet() }
            else absorbEdit(state, text)
            journal.removeAll { it.atMillis < modifiedAtMillis - JOURNAL_WINDOW_MILLIS }
            journal.add(Absorbed(path, prev, state.current, modifiedAtMillis))
            return
        }
        val old = state.current?.let(store::get) ?: ""
        state.owned = remap(state.owned, LineDiff.hunks(linesOf(old), linesOf(text ?: "")))
        state.current = text?.let(store::put)
    }

    /** The user typed in the IDE editor; [text] is the document's full new text. */
    @Synchronized
    fun onOwnEdit(path: String, text: String) = absorbEdit(files.getOrPut(path) { FileState(null, null) }, text)

    /**
     * Applies a text change that is not the agent's: edits outside agent hunks go into the baseline,
     * lines edited inside an agent hunk become owned (no longer highlighted).
     */
    private fun absorbEdit(state: FileState, text: String) {
        val newHash = store.put(text)
        val oldText = state.current?.let(store::get) ?: ""
        if (state.current == null && state.baseline == null) {
            state.baseline = newHash; state.current = newHash; return
        }
        if (oldText == text) return
        val oldLines = linesOf(oldText)
        val newLines = linesOf(text)
        val ownHunks = LineDiff.hunks(oldLines, newLines)
        val baseLines = linesOf(state.baseline?.let(store::get) ?: "")
        val agentHunks = LineDiff.hunks(baseLines, oldLines)

        var owned = remap(state.owned, ownHunks)
        val newBase = baseLines.toMutableList()
        var shift = 0
        var baseChanged = false
        for (h in ownHunks) {
            if (touchesAgentHunk(h, agentHunks)) {
                owned = owned + (h.bStart until h.bStart + h.bLen)
            } else {
                val pos = mapToBaseline(h.aStart, agentHunks) + shift
                repeat(h.aLen) { newBase.removeAt(pos) }
                newBase.addAll(pos, newLines.subList(h.bStart, h.bStart + h.bLen))
                shift += h.bLen - h.aLen
                baseChanged = true
            }
        }
        state.owned = owned
        if (baseChanged) {
            val trailing = if (text.endsWith("\n") || newBase.isEmpty()) "\n" else ""
            state.baseline = store.put(if (newBase.isEmpty()) "" else newBase.joinToString("\n") + trailing)
        }
        state.current = newHash
    }

    private fun touchesAgentHunk(h: Hunk, agentHunks: List<Hunk>): Boolean =
        if (h.aLen > 0) agentHunks.any { it.bLen > 0 && h.aStart < it.bStart + it.bLen && it.bStart < h.aStart + h.aLen }
        else agentHunks.any { it.bLen > 0 && it.bStart < h.aStart && h.aStart < it.bStart + it.bLen }

    /** Maps a line position in the current text, outside any agent hunk, to the same position in the baseline. */
    private fun mapToBaseline(p: Int, agentHunks: List<Hunk>): Int =
        p + agentHunks.filter { it.bStart + it.bLen <= p && !(it.bLen == 0 && it.bStart == p) }.sumOf { it.aLen - it.bLen }

    /** Carries line indexes across a text change: lines inside a changed region are dropped, others shift. */
    private fun remap(owned: Set<Int>, hunks: List<Hunk>): Set<Int> {
        if (owned.isEmpty() || hunks.isEmpty()) return owned
        val out = HashSet<Int>()
        for (i in owned) {
            if (hunks.any { i >= it.aStart && i < it.aStart + it.aLen }) continue
            out.add(i + hunks.filter { it.aStart + it.aLen <= i }.sumOf { it.bLen - it.aLen })
        }
        return out
    }

    @Synchronized
    fun changedPaths(): List<String> = files.keys.filter { turnChanges(it).isNotEmpty() }

    @Synchronized
    fun baselineText(path: String): String? = files[path]?.baseline?.let(store::get)

    @Synchronized
    fun turnChanges(path: String): List<LineChange> {
        val s = files[path] ?: return emptyList()
        if (s.baseline == s.current) return emptyList()
        val base = linesOf(s.baseline?.let(store::get) ?: "")
        val cur = linesOf(s.current?.let(store::get) ?: "")
        return LineDiff.hunks(base, cur).flatMap { h ->
            if (h.bLen == 0) return@flatMap listOf(LineChange(ChangeKind.DELETED, h.bStart, 0))
            val kind = if (h.aLen == 0) ChangeKind.ADDED else ChangeKind.MODIFIED
            // Split the hunk around lines the user has taken ownership of.
            val out = ArrayList<LineChange>()
            var runStart = -1
            for (line in h.bStart..h.bStart + h.bLen) {
                val open = line < h.bStart + h.bLen && line !in s.owned
                if (open && runStart < 0) runStart = line
                if (!open && runStart >= 0) { out.add(LineChange(kind, runStart, line - runStart)); runStart = -1 }
            }
            out
        }
    }
}
