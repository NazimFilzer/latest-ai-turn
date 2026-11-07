package dev.latestaiturn

import dev.latestaiturn.core.TurnTracker
import dev.latestaiturn.signals.SignalKind
import dev.latestaiturn.signals.SpoolParser
import dev.latestaiturn.signals.TurnSignal
import dev.latestaiturn.signals.applyTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolParserTest {
    @Test
    fun `claude UserPromptSubmit becomes a START signal`() {
        val json = """{"session_id":"abc","cwd":"/work/p","hook_event_name":"UserPromptSubmit","prompt":"hi"}"""
        assertEquals(
            TurnSignal(SignalKind.START, "claude", "abc", listOf("/work/p"), 5_000),
            SpoolParser.parse("claude-start-1-77.json", json, 5_000),
        )
    }

    @Test
    fun `codex Stop becomes an END signal`() {
        val json = """{"session_id":"thr_1","turn_id":"t9","cwd":"/work/p","hook_event_name":"Stop","model":"m"}"""
        assertEquals(
            TurnSignal(SignalKind.END, "codex", "thr_1", listOf("/work/p"), 7_000),
            SpoolParser.parse("codex-end-1-77.json", json, 7_000),
        )
    }

    @Test
    fun `antigravity PreInvocation is a CONTINUE signal and only a fully idle Stop ends the turn`() {
        val pre = """{"conversationId":"c1","workspacePaths":["/work/p","/other"],"invocationNum":0}"""
        assertEquals(
            TurnSignal(SignalKind.CONTINUE, "antigravity", "c1", listOf("/work/p", "/other"), 1),
            SpoolParser.parse("antigravity-start-1-2.json", pre, 1),
        )
        val busy = """{"conversationId":"c1","workspacePaths":["/work/p"],"terminationReason":"model_stop","fullyIdle":false}"""
        val idle = """{"conversationId":"c1","workspacePaths":["/work/p"],"terminationReason":"model_stop","fullyIdle":true}"""
        assertNull(SpoolParser.parse("antigravity-end-1-2.json", busy, 2))
        assertEquals(SignalKind.END, SpoolParser.parse("antigravity-end-1-2.json", idle, 2)?.kind)
    }

    @Test
    fun `garbage and unknown files are ignored`() {
        assertNull(SpoolParser.parse("claude-start-1-1.json", "not json", 1))
        assertNull(SpoolParser.parse("claude-start-1-1.json", """{"cwd":"/x"}""", 1))
        assertNull(SpoolParser.parse("mystery-start-1-1.json", """{"session_id":"s","cwd":"/x"}""", 1))
        assertNull(SpoolParser.parse("readme.txt", "{}", 1))
    }

    @Test
    fun `a signal applies to a project when the agent cwd is inside it or contains it`() {
        fun sig(vararg cwd: String) = TurnSignal(SignalKind.START, "claude", "s", cwd.toList(), 0)
        assertTrue(sig("/work/p").appliesTo("/work/p"))
        assertTrue(sig("/work/p/sub/dir").appliesTo("/work/p"))
        assertTrue(sig("/work").appliesTo("/work/p"))
        assertTrue(sig("/elsewhere", "/work/p").appliesTo("/work/p"))
        assertFalse(sig("/work/p2").appliesTo("/work/p"))
        assertFalse(sig("/worktrees/p-copy").appliesTo("/work/p"))
    }

    @Test
    fun `signals drive the tracker - CONTINUE does not restart a turn of the same session`() {
        val tracker = TurnTracker()
        tracker.seed("a", "1\n")
        TurnSignal(SignalKind.CONTINUE, "antigravity", "c1", emptyList(), 100).applyTo(tracker)
        tracker.onExternalChange("a", "2\n", 150)
        TurnSignal(SignalKind.CONTINUE, "antigravity", "c1", emptyList(), 200).applyTo(tracker)
        assertEquals(listOf("a"), tracker.changedPaths())

        TurnSignal(SignalKind.START, "claude", "s2", emptyList(), 300).applyTo(tracker)
        assertEquals(emptyList<String>(), tracker.changedPaths())

        TurnSignal(SignalKind.END, "antigravity", "c1", emptyList(), 400).applyTo(tracker)
        assertEquals("s2", tracker.activeTurn()?.sessionId)
    }
}
