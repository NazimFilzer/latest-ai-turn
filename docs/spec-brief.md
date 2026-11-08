# Spec: IntelliJ IDEA plugin "Latest AI Turn" (original brief + decisions settled with the user)

## Original brief (user)
Plugin that highlights, inside the normal editor, ONLY the changes of the most recent AI coding turn — not everything since last Git commit.
Core semantics:
1. Baseline independent of Git: internal per-file snapshot = "state at start of the current turn"; pre-existing uncommitted changes absorbed into the baseline and NEVER highlighted.
2. Turn boundaries: explicit Start/End actions (+shortcuts); automatic detection as an enhancement; research real hooks of the user's tools; plugin must be useful with explicit mode alone.
3. User's own typing must not contaminate highlights: IDE edits absorbed into baseline; distinguish IDE edits from external disk changes; failure modes documented; manual "Re-baseline" escape hatch.
4. Parallel agents sharing a checkout: external changes not part of the current turn must not silently merge into the turn's highlight; document behavior and limits.
5. Editor UX: gutter markers + optional inline background for lines added/modified by the latest turn in the normal editor; action to open the latest turn as before/after diff per file; toggle to show/hide highlights; Re-baseline action; new turn clears previous highlights.
Verification duties: verify every IntelliJ API and external-tool hook claim against current official docs, label confirmed vs assumed; list MVP vs aspirational; don't silently downgrade semantics.
MVP: manual Start/End, snapshot-based gutter highlight, baseline absorbing manual edits, Re-baseline, works on a verified current IntelliJ IDEA. Stretch: auto detection, per-prompt history, multi-agent attribution, other JetBrains IDEs.
Non-goals: NO git commits/staging/VCS mutation; NO AI/model calls; NO telemetry or network.
Deliverable: complete buildable project with README covering verified APIs + sources, chosen turn-boundary semantics, known limitations/failure modes, install & test instructions.

## Decisions settled in the design interview (user said: take the recommendations)
- Live baseline: gutter = diff(baseline, current text); IDE edits applied to baseline. Editing a line the agent wrote un-highlights ONLY that line (line-level ownership).
- Changes on disk while no turn is open are absorbed (quiet notice). In manual mode every disk change during an open turn belongs to the turn.
- Turn Start saves all documents first. Tracked files: project files, NOT .idea or other IDE/ignored/excluded paths, size limit 1 MB, no binaries; files created in a turn highlighted fully; deleted files appear only in the diff/file list.
- Agents used: Codex desktop, Claude desktop, Antigravity, T3 Code (no CLIs) — all write to disk. IDE-side agents (JetBrains AI etc.) treated as own edits — documented limitation.
- Persist current turn across IDE restart in IDE system dir (not project); files changed while closed are absorbed with warning.
- Target current stable IntelliJ IDEA (+ previous major).
- Baseline = always-current shadow copy (content-addressed) in IDE system dir; Turn Start just freezes it (ADR 0001).
- Gutter via a small wrapper (SimpleLineStatusTracker recommended; fallback MarkupModel allowed).
- Hook mode IN MVP for Claude and Codex, Antigravity best-effort: Claude/Codex UserPromptSubmit=start, Stop=end; Antigravity PreInvocation=start, Stop with fullyIdle=end. Signals via spool directory written by a tiny sh script, polled by the plugin, attributed by TIME (ADR 0002). Events whose cwd is outside the project are ignored. "Install agent hooks…" explicit action: preview, backup, add-only, with Uninstall. No fallback tailing of agent transcripts; instead warn when no signal.
- Newest turn start wins when agents overlap; only the active session's Stop ends the turn; Start while a turn is open = implicit End+Start. Turn End first refreshes the project from disk then closes (late writes still counted).
- Both IntelliJ's Git gutter and ours stay visible in the MVP (user's FINAL decision; earlier "hide Git markers" was withdrawn). Our colour: violet; optional line-background toggle on by default.
- "Show Latest Turn Diff": IntelliJ standard diff window over all changed files (+ per-file action in the editor menu); no tool window in MVP.
- ADRs written for baseline decision and signal transport.
- User's priority: "when I use one agent at a time, I want to see all changes that agent made in my IntelliJ" — robustness of that main path.
