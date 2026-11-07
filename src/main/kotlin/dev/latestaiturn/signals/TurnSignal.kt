package dev.latestaiturn.signals

import dev.latestaiturn.core.TurnTracker

enum class SignalKind {
    /** A prompt was submitted: always begins a new turn (Claude, Codex `UserPromptSubmit`). */
    START,

    /** The agent is working: begins a turn only if this session has none open (Antigravity `PreInvocation`). */
    CONTINUE,

    /** The agent finished (`Stop`). */
    END,
}

/** One Turn Signal from an agent hook. [atMillis] is when the hook fired (spool file modification time). */
data class TurnSignal(
    val kind: SignalKind,
    val app: String,
    val sessionId: String,
    val cwds: List<String>,
    val atMillis: Long,
) {
    /**
     * True when the agent works in this project: its directory is inside the project, or contains it
     * (a monorepo root). The home directory and `/` never count as "containing", or every project would match.
     */
    fun appliesTo(projectBase: String, homeDir: String = System.getProperty("user.home") ?: ""): Boolean {
        val base = projectBase.trimEnd('/')
        return cwds.map { it.trimEnd('/') }.any { cwd ->
            cwd == base || cwd.startsWith("$base/") ||
                (cwd.isNotEmpty() && cwd != homeDir.trimEnd('/') && base.startsWith("$cwd/"))
        }
    }
}

fun TurnSignal.applyTo(tracker: TurnTracker) {
    when (kind) {
        SignalKind.START -> tracker.startTurn(sessionId, atMillis)
        SignalKind.CONTINUE -> if (tracker.activeTurn()?.sessionId != sessionId) tracker.startTurn(sessionId, atMillis)
        SignalKind.END -> tracker.endTurn(sessionId)
    }
}
