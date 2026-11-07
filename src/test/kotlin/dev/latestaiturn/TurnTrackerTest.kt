package dev.latestaiturn

import dev.latestaiturn.core.ChangeKind
import dev.latestaiturn.core.LineChange
import dev.latestaiturn.core.TurnTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnTrackerTest {
    private val t0 = 1_000L

    @Test
    fun `external change during a turn is highlighted against the pre-turn baseline`() {
        val tracker = TurnTracker()
        tracker.seed("a.kt", "l1\nl2\nl3\n")
        tracker.startTurn(sessionId = "s1", atMillis = t0)

        tracker.onExternalChange("a.kt", "l1\nL2\nl3\nl4\n", modifiedAtMillis = t0 + 10)

        assertEquals(
            listOf(LineChange(ChangeKind.MODIFIED, 1, 1), LineChange(ChangeKind.ADDED, 3, 1)),
            tracker.turnChanges("a.kt"),
        )
        assertEquals("l1\nl2\nl3\n", tracker.baselineText("a.kt"))
        assertEquals(listOf("a.kt"), tracker.changedPaths())
    }

    private fun trackerWithAgentEdit(base: String, agent: String): TurnTracker {
        val tracker = TurnTracker()
        tracker.seed("a.kt", base)
        tracker.startTurn("s1", t0)
        tracker.onExternalChange("a.kt", agent, t0 + 10)
        return tracker
    }

    @Test
    fun `own edit outside the agent's hunk is absorbed and never highlighted`() {
        val tracker = trackerWithAgentEdit("l1\nl2\nl3\n", "l1\nL2\nl3\n")

        tracker.onOwnEdit("a.kt", "top\nl1\nL2\nl3\n")

        assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 2, 1)), tracker.turnChanges("a.kt"))
        assertEquals("top\nl1\nl2\nl3\n", tracker.baselineText("a.kt"))
    }

    @Test
    fun `deleting a line the baseline had is absorbed, not shown as a deletion`() {
        val tracker = trackerWithAgentEdit("l1\nl2\nl3\nl4\n", "l1\nl2\nl3\nL4\n")

        tracker.onOwnEdit("a.kt", "l2\nl3\nL4\n")

        assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 2, 1)), tracker.turnChanges("a.kt"))
    }

    @Test
    fun `editing one line inside an agent hunk only un-highlights that line`() {
        val tracker = trackerWithAgentEdit("l1\nl2\nl3\n", "l1\nL2\nL3\n")

        tracker.onOwnEdit("a.kt", "l1\nX\nL3\n")

        assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 2, 1)), tracker.turnChanges("a.kt"))
        assertEquals("l1\nl2\nl3\n", tracker.baselineText("a.kt"))
    }

    @Test
    fun `deleting agent-added lines removes their highlight entirely`() {
        val tracker = trackerWithAgentEdit("l1\nl2\n", "l1\nnew1\nnew2\nl2\n")

        tracker.onOwnEdit("a.kt", "l1\nl2\n")

        assertEquals(emptyList<LineChange>(), tracker.turnChanges("a.kt"))
        assertEquals(emptyList<String>(), tracker.changedPaths())
    }

    @Test
    fun `ownership follows the line when later edits shift it`() {
        val tracker = trackerWithAgentEdit("l1\nl2\nl3\n", "l1\nL2\nL3\n")
        tracker.onOwnEdit("a.kt", "l1\nX\nL3\n")

        tracker.onOwnEdit("a.kt", "top\nl1\nX\nL3\n")

        assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 3, 1)), tracker.turnChanges("a.kt"))
    }

    @Test
    fun `external change outside any turn is absorbed`() {
        val tracker = TurnTracker()
        tracker.seed("a.kt", "l1\nl2\n")

        tracker.onExternalChange("a.kt", "l1\nl2\nl3\n", modifiedAtMillis = 500)
        tracker.startTurn("s1", 1_000)

        assertEquals(emptyList<String>(), tracker.changedPaths())
    }

    @Test
    fun `a foreign change after Turn End keeps the ended turn's highlights and adds none`() {
        val tracker = trackerWithAgentEdit("l1\nl2\n", "l1\nL2\n")
        tracker.endTurn("s1")

        tracker.onExternalChange("a.kt", "l1\nL2\nl3\n", modifiedAtMillis = t0 + 5_000)

        assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 1, 1)), tracker.turnChanges("a.kt"))
    }

    @Test
    fun `a write that was observed before its Turn Start signal is attributed by time`() {
        val tracker = TurnTracker()
        tracker.seed("early.kt", "x\n")
        tracker.seed("late.kt", "y\n")

        tracker.onExternalChange("early.kt", "x2\n", modifiedAtMillis = 900)
        tracker.onExternalChange("late.kt", "y2\n", modifiedAtMillis = 1_100)
        tracker.startTurn("s1", atMillis = 1_000)

        assertEquals(listOf("late.kt"), tracker.changedPaths())
        assertEquals("y\n", tracker.baselineText("late.kt"))
    }

    @Test
    fun `starting a new turn clears the previous turn's highlights`() {
        val tracker = trackerWithAgentEdit("l1\n", "L1\n")

        tracker.startTurn("s1", t0 + 1_000)

        assertEquals(emptyList<String>(), tracker.changedPaths())
        assertEquals("L1\n", tracker.baselineText("a.kt"))
    }

    @Test
    fun `newest start wins and only the active session can end the turn`() {
        val tracker = TurnTracker()
        tracker.startTurn("claude", 1_000)
        tracker.startTurn("codex", 2_000)

        tracker.endTurn("claude")
        assertEquals("codex", tracker.activeTurn()?.sessionId)

        tracker.endTurn("codex")
        assertNull(tracker.activeTurn())
    }

    @Test
    fun `re-baseline absorbs everything but keeps the turn open`() {
        val tracker = trackerWithAgentEdit("l1\n", "L1\n")

        tracker.rebaseline()
        assertEquals(emptyList<String>(), tracker.changedPaths())

        tracker.onExternalChange("a.kt", "L1\nmore\n", t0 + 100)
        assertEquals(listOf(LineChange(ChangeKind.ADDED, 1, 1)), tracker.turnChanges("a.kt"))
    }

    @Test
    fun `files created and deleted during a turn are reported`() {
        val tracker = TurnTracker()
        tracker.seed("gone.kt", "bye\n")
        tracker.startTurn("s1", t0)

        tracker.onExternalChange("new.kt", "a\nb\n", t0 + 1)
        tracker.onExternalChange("gone.kt", null, t0 + 2)

        assertEquals(listOf(LineChange(ChangeKind.ADDED, 0, 2)), tracker.turnChanges("new.kt"))
        assertEquals(setOf("new.kt", "gone.kt"), tracker.changedPaths().toSet())
    }

    @Test
    fun `state survives a snapshot and restore, including line ownership`() {
        val store = dev.latestaiturn.core.InMemoryBlobStore()
        val before = TurnTracker(store)
        before.seed("a.kt", "l1\nl2\nl3\n")
        before.startTurn("s1", t0)
        before.onExternalChange("a.kt", "l1\nL2\nL3\n", t0 + 1)
        before.onOwnEdit("a.kt", "l1\nX\nL3\n")

        val after = TurnTracker(store)
        after.restore(before.snapshot())

        assertEquals("s1", after.activeTurn()?.sessionId)
        assertEquals(before.turnChanges("a.kt"), after.turnChanges("a.kt"))
        assertEquals(listOf(LineChange(ChangeKind.MODIFIED, 2, 1)), after.turnChanges("a.kt"))
        assertEquals(true, after.isTracked("a.kt"))
        assertEquals(false, after.isTracked("nope.kt"))
    }

    @Test
    fun `a change the IDE only noticed after Turn Start but made before it is not the turn's`() {
        val tracker = TurnTracker()
        tracker.seed("pull.kt", "old\n")
        tracker.startTurn("s1", atMillis = 1_000)

        tracker.onExternalChange("pull.kt", "from git pull\n", modifiedAtMillis = 400)

        assertEquals(emptyList<String>(), tracker.changedPaths())
    }

    @Test
    fun `seeding never overwrites a file that already has state`() {
        val tracker = TurnTracker()
        tracker.seed("a.kt", "1\n")
        tracker.startTurn("s1", t0)
        tracker.onExternalChange("a.kt", "2\n", t0 + 1)

        assertEquals(false, tracker.seedIfAbsent("a.kt", "2\n"))
        assertEquals(true, tracker.seedIfAbsent("b.kt", "x\n"))
        assertEquals(listOf("a.kt"), tracker.changedPaths())
    }
}
