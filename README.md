# Latest AI Turn — IntelliJ IDEA plugin

Highlights, in the normal editor, **only what the most recent AI coding turn changed**. Not "everything since the last Git commit".
Your uncommitted work and your own typing are never highlighted. No Git operations, no network, no AI calls.

* violet **gutter bar** + faint **line background** on lines the latest turn added or changed (a small wedge where it deleted lines)
* **Show Latest Turn Diff**: IntelliJ's standard diff window over every file the turn changed (baseline left, live file right)
* status-bar text: `AI turn: running · 3 files` / `3 files changed` / `idle`
* works with **Claude Code** (Claude desktop app, CLI, T3 Code), **Codex** (desktop app, CLI, T3 Code) and **Antigravity** (best effort) through hooks, or fully manually

Domain language lives in [`GLOSSARY.md`](GLOSSARY.md); the two hard-to-reverse decisions are in [`docs/adr/`](docs/adr).

## Install and try it

Requires IntelliJ IDEA **2026.2 or newer** (build 262+). The plugin is built against 2026.2.1; see *Limitations*.

```sh
# JDK: use the IDE's own JBR (Java 25). Gradle 9.6 wrapper is included.
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"

./gradlew test            # 41 tests: pure model, parser, installer, plus a headless-IDE integration suite
./gradlew buildPlugin     # -> build/distributions/latest-ai-turn-0.1.0.zip
```

Then **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick the zip.
Build against another IDE install with `-PideaPath="/path/to/IntelliJ IDEA.app"`.

To see it without touching your real IDE: `./gradlew runIde -PdemoProject=/path/to/some/project` opens a sandbox IDE (own config) on any project.

### Use it

**Tools → Latest AI Turn**

| Action | Shortcut | What it does |
|---|---|---|
| Start Turn | ⌃⌥⇧T | Saves editors, freezes the baseline. Everything that changes on disk from now on is the turn's. |
| End Turn | ⌃⌥⇧E | Refreshes the project from disk first (so the agent's last writes count), then closes the turn. Highlights stay. |
| Re-baseline | | Absorbs everything: current contents become the baseline. Escape hatch. |
| Set Tracked Folders… | | Track only these folders, relative to the project root, e.g. `src`. Empty means the whole project. |
| Show Latest Turn Diff | ⌃⌥⇧D | Diff window over all changed files. Also in the editor right-click menu, for the current file. |
| Show Latest Turn Highlights / Highlight Changed Lines Background | | Toggles (remembered). |
| Install / Uninstall Agent Hooks… | | See below. |

Manual mode alone is complete: press Start, let the agent work, press End.

### Hook mode (turns start and end by themselves)

**Tools → Latest AI Turn → Install Agent Hooks…** shows exactly what will be added to which file, lets you untick agents, makes a backup of each file it touches (`*.latest-ai-turn.bak`), and **only adds** entries. It refuses to touch a file that is not valid JSON, or whose `hooks` section has an unexpected shape, instead of replacing it. The file is re-serialized (pretty-printed, key order kept), which is why a backup is made first. Uninstall removes only our entries.

| Agent | File | Turn start | Turn end |
|---|---|---|---|
| Claude Code (desktop, CLI, T3 Code) | `~/.claude/settings.json` | `UserPromptSubmit` | `Stop` |
| Codex (desktop, CLI, T3 Code) | `~/.codex/hooks.json` | `UserPromptSubmit` | `Stop` |
| Antigravity (best effort) | `~/.gemini/config/hooks.json` | `PreInvocation` | `Stop` with `fullyIdle: true` |

Each hook runs `~/.latest-ai-turn/hook.sh`, a small POSIX `sh` script that copies the hook payload into `~/.latest-ai-turn/spool/` and prints the minimal reply the agent expects (`{}`; Antigravity's `Stop` gets `{"decision":"allow"}`). It never changes agent behavior. The plugin polls the spool every 250 ms. **Codex asks you to approve a new hook once** (Settings → Hooks). Restart running agent sessions after installing.

## How turns work (semantics)

* **Baseline, independent of Git.** The plugin keeps its own content-addressed copy of every tracked file in the IDE system directory (never in your project). A turn's highlights are `diff(baseline, current)`. Pre-existing uncommitted work is part of the baseline, so it is never highlighted. ([ADR 0001](docs/adr/0001-git-independent-shadow-baseline.md))
* **What counts as the turn's change:** every change that *arrives on disk* between Turn Start and Turn End — an agent's edit tool, but also a shell command, formatter or code generator it runs. Attribution is by **time**, not by which tool wrote. ([ADR 0002](docs/adr/0002-turn-signals-via-spool-directory.md))
* **Your own typing never contaminates it.** Edits through the IDE are folded into the baseline. If you edit a line the agent wrote, only *that line* stops being highlighted; the agent's other lines stay. Renames, deletes, "new file" and refactoring writes done through the IDE are yours too.
* **Outside a turn,** changes arriving from disk (`git checkout`, a generator, another tool) are absorbed, with one quiet notice. They never *add* highlights. Lines they overwrite stop being highlighted (they are no longer the agent's text), but the rest of an ended turn's highlights stay.
* **Late signals are handled.** The hook signal reaches the IDE a moment after the agent starts; a write already seen by then is put back into the turn by its timestamp (modification *or change* time, so an agent's `mv` of an old file counts).
* **New turn replaces old.** Starting a turn clears the previous highlights. A new Start while one is open ends it implicitly. **Newest start wins** when agents overlap; a `Stop` from any other session is ignored, and **a manual turn is only ended manually** (or by the next Start), never by an agent's Stop.
* **Scope:** by default the whole project. **Set Tracked Folders…** limits tracking to listed folders (for example `src`); files outside them are never copied or highlighted, and changing the list forgets what falls outside it. The setting is stored in IDE application settings, keyed by project path, so nothing is written into the project. Otherwise: text files under the project directory, inside IntelliJ content roots, not excluded by the IDE or ignored by VCS, ≤ 1 MB, not binary, and never under `.git .idea .gradle node_modules __pycache__`. Build-output folders are not special-cased: if your build output is neither IDE-excluded nor git-ignored, it will be tracked.
* **Persistence:** the current turn survives an IDE restart (state in the IDE system dir). Files that changed while the IDE was closed cannot be attributed: they are absorbed with a notice.

## Verified versus assumed

*Confirmed* = read in the current official docs or the `intellij-community` master source (links below). *Exercised* = also run by this repo's tests or a real IDE. Anything else is marked *assumption*.

### IntelliJ Platform

| API / fact | Status | Source |
|---|---|---|
| Gradle plugin `org.jetbrains.intellij.platform` **2.20.0**, Gradle ≥ 9.0, `local(path)` target, `intellijIdea()` for 2025.3+ | confirmed, exercised (build) | [plugin docs](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [dependencies extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html) |
| Build 262 (2026.2) requires **Java 25**; Kotlin 2.x | confirmed, exercised | [build number ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html), [Kotlin](https://plugins.jetbrains.com/docs/intellij/using-kotlin.html) |
| `VirtualFileManager.VFS_CHANGES` + `BulkFileListener.after` | confirmed, exercised | [VFS docs](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html) |
| `VFileEvent.isFromRefresh()` / `isFromSave()` to tell disk changes from IDE writes | confirmed (source), exercised; *assumption:* a JetBrains forum thread reports they are not always clean | `VFileEvent.java` |
| `EditorFactory.getEventMulticaster().addDocumentListener` | confirmed, exercised | [documents docs](https://plugins.jetbrains.com/docs/intellij/documents.html) |
| `FileDocumentManagerListener.TOPIC` with `beforeFileContentReload` / `fileContentReloaded` (to tell a reload from typing) | confirmed (source), exercised | `core-api/.../FileDocumentManagerListener.java` |
| `MarkupModel.addRangeHighlighter`, `HighlighterLayer`, `RangeHighlighter.setLineMarkerRenderer` | confirmed (source), exercised; SDK docs are silent on them | `editor-ui-api/.../markup/` |
| `LineMarkerRendererEx` (position `RIGHT`) | confirmed (source). It lives in the `platform-impl` package though the interface is not marked internal — **stability unknown** | `platform-impl/.../markup/LineMarkerRendererEx.java` |
| `LoadTextUtil.loadText` (`com.intellij.openapi.fileEditor.impl`) | compiles and runs; **impl package, stability unknown** | — |
| `ChangeListManager.isIgnoredFile`, `ProjectFileIndex.isInContent/iterateContent` | compile and run; not checked against docs | — |
| `DiffManager.showDiff`, `SimpleDiffRequestChain`, `DiffContentFactory`, `SimpleDiffRequest` | confirmed (source), compile. exercised in the IntelliJ IDEA 2026.2.1 GUI | `diff-api` |
| `AnAction`/`ToggleAction` with `getActionUpdateThread()` | confirmed | [action system](https://plugins.jetbrains.com/docs/intellij/action-system.html) |
| `StatusBarWidgetFactory`, `ProjectActivity` | exercised in the IntelliJ IDEA 2026.2.1 GUI (startup, idle, running, and changed-file count) | — |
| `ModalityState.any()` must not be used for model changes (e.g. `saveAllDocuments`) | **found by a failing test**, then fixed | test log |

I did **not** run the JetBrains Plugin Verifier. Internal/impl API use is therefore known only from the table above.

### Agent hooks

| Claim | Status | Source |
|---|---|---|
| Claude Code runs `UserPromptSubmit` / `Stop` hooks from `~/.claude/settings.json` in the **desktop app** too; payload has `session_id`, `cwd` | confirmed in docs; exercised with a real Claude Desktop edit | [hooks](https://code.claude.com/docs/en/hooks), [desktop](https://code.claude.com/docs/en/desktop) |
| Claude runs hooks only after the folder's workspace trust is accepted | confirmed in docs | hooks docs |
| Codex hooks from `~/.codex/hooks.json`, same JSON shape; Stop output must be JSON; new hooks need one-time approval; desktop app has Settings → Hooks | confirmed in docs; *not run against the real app* | [Codex hooks](https://learn.chatgpt.com/docs/hooks) |
| Antigravity `~/.gemini/config/hooks.json` with a named block; `PreInvocation`, `Stop.fullyIdle`; Stop requires a `decision` reply | confirmed in docs; exercised with a real Antigravity Desktop edit; remains **best effort** across versions | [Antigravity hooks](https://antigravity.google/docs/hooks) |
| T3 Code's Claude provider loads user/project settings (so hooks fire) and its Codex app-server runs hooks | read from T3 Code source at commit `e455200`; a real edit through the Claude provider was highlighted; the Codex provider edit produced no turn signal before hook approval | `pingdotgg/t3code` |
| Claude `Stop` fires when you **interrupt** the agent | **unknown** — the docs I could read do not say | — |
| Codex `Interrupt` hook payload | event exists in the docs; payload **unverified**, not wired up | — |

What *was* run for real: the hook script itself (real `sh`, real spool, real parser, real watcher, real project service) in the headless-IDE test.

## Installed IDE verification

- In IntelliJ IDEA 2026.2.1, the Tools menu, status bar, hook preview, and before/after diff appeared. The violet editor marker and tint appeared only on the test turn edit. IntelliJ's blue Git marker remained visible on both uncommitted edits.
- The installed plugin opened and closed turns from simulated Claude, Codex, and Antigravity payloads through the real shell relay. Each test wrote a file and sent Stop immediately. The final edit remained highlighted after Stop.
- Real edits from Claude Desktop, Antigravity Desktop, and T3 Code's Claude provider each opened a turn, appeared as a turn change, and left the turn closed. Codex Desktop still requires hook approval. T3 Code's Codex provider did not produce a turn signal in the pre-approval test.

## Known limitations and failure modes

* **No continuous "hook is not firing" detection.** The only warning: when files change outside a turn *and* hooks are installed, the absorb notice adds a hint that no turn signal arrived. If an agent's hook silently never fires, nothing is highlighted until you press Start Turn.
* **Interrupted agents.** If an agent is interrupted and no `Stop` arrives, the turn stays open until the next prompt or a manual **End Turn**; changes made meanwhile (including by you through other tools) count toward it.
* **IDE-side agents look like you.** Anything that edits through the IDE's own editor (JetBrains AI, Junie, Copilot in IntelliJ) is treated as your own typing and is never highlighted. On-disk agents only.
* **Two agents at once:** the newest Start wins; the older agent's later writes are *not* highlighted as a second turn. Antigravity's `PreInvocation` (sent on every model call) also takes over an open turn of a *different* session, so overlapping Antigravity and Claude/Codex work can flip the turn back and forth. In manual mode every disk change during the turn counts.
* **Git operations during a turn** (checkout, pull, stash) count toward the turn, because they are indistinguishable from an agent's writes. Use Re-baseline afterwards.
* **Spool is consumed by the first IDE that polls it;** two IDE instances on the same machine race. Signals are matched to projects by the agent's working directory (inside the project, or a parent that is not your home dir or `/`), so T3 / Codex worktrees outside the project directory are ignored.
* **Unsaved editor text vs an agent write.** IntelliJ's own behavior applies: a file with unsaved edits that an agent also writes triggers IntelliJ's conflict handling, and an IDE save landing after the agent's write overwrites it. Hook-started turns save all editors *right after* the turn starts (saving cannot happen on the signal path itself), which narrows but does not close that window. Manual Start saves first.
* **Large file events run on the EDT.** The VFS listener reads and diffs changed files on the UI thread (inside IntelliJ's refresh). A branch switch touching many large files can briefly freeze the UI. End Turn forces a refresh of the content roots to catch writes that the file watcher has not reported yet. Moving this off the EDT would change the ordering between reloads and edits, so it was left as is.
* **Whole-file rewrites.** The line diff stops searching for a minimal diff after 1500 edits and reports one big region.
* **Line-based.** A change that only differs in the final newline is not highlighted; line-ending-only changes are normalized away. Highlights while the *first scan* of a brand-new project is running may be missing for files not yet scanned.
* **Not covered:** moving a directory in/out of the project via refresh, content roots outside the project directory, multi-window attribution of one agent to several projects with different `cwd`s.
* **IDE versions.** Only **2026.2+ (build 262+)** is claimed. 2026.1 was not tested. No `until-build`, so 2026.3+ is allowed but unverified. Other JetBrains IDEs are untested.
* **Deviation from the plan:** the gutter is drawn with our own `MarkupModel` painter, **not** `SimpleLineStatusTracker`. Line-level "your edit takes ownership" cannot be expressed as a diff against a base text, and the painter uses only public editor APIs. The cost: no click-to-see-old-text popup on our bar (use the diff window).
* **Next to IntelliJ's Git marker.** Both gutters are shown on purpose (decision for the MVP). The two markers appeared side by side in the IntelliJ IDEA 2026.2.1 editor.
* **No telemetry, no network, no AI calls.** The plugin only reads project files and writes to the IDE system directory and `~/.latest-ai-turn/`.

## Where things are stored

* `<IDE system dir>/latest-ai-turn/<project-hash>/` — `state.json` + `blobs/` (baseline copies). Safe to delete; the plugin re-scans.
* `~/.latest-ai-turn/hook.sh`, `~/.latest-ai-turn/spool/` — only if you install hooks (the spool is emptied as signals are read; signals older than 10 minutes are dropped).

## Code map

* `core/` — `TurnTracker` (pure model), `LineDiff` (Myers), `BlobStore`s. No IDE types; unit-tested.
* `signals/` — `SpoolParser` (hook payload → `TurnSignal`), signal → tracker mapping.
* `hooks/` — add-only JSON installer and the relay script.
* `ide/` — `TurnService` (VFS/document/signal glue, persistence), `TurnPaint` (markup), actions, status widget, hook install UI, spool watcher.
* `src/test/` — model/parser/installer tests and `TurnServiceIdeTest`, which runs a real project in a headless IDE and drives the real hook script.
