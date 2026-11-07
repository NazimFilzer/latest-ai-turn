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

./gradlew test            # 35 tests: pure model, parser, installer, plus a headless-IDE integration suite
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
| Show Latest Turn Diff | ⌃⌥⇧D | Diff window over all changed files. Also in the editor right-click menu, for the current file. |
| Show Latest Turn Highlights / Highlight Changed Lines Background | | Toggles (remembered). |
| Install / Uninstall Agent Hooks… | | See below. |

Manual mode alone is complete: press Start, let the agent work, press End.

### Hook mode (turns start and end by themselves)

**Tools → Latest AI Turn → Install Agent Hooks…** shows exactly what will be added to which file, lets you untick agents, makes a backup of each file it touches (`*.latest-ai-turn.bak`), and **only adds** entries — it never rewrites your existing config, and refuses to touch a file that is not valid JSON. Uninstall removes only our entries.

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
* **Outside a turn,** changes arriving from disk (`git checkout`, a generator, another tool) are absorbed silently, with one quiet notice. They never light up, and they never erase the highlights of an ended turn.
* **Late signals are handled.** The hook signal reaches the IDE a moment after the agent starts; a write already seen by then is put back into the turn by its timestamp (modification *or change* time, so an agent's `mv` of an old file counts).
* **New turn replaces old.** Starting a turn clears the previous highlights. A new Start while one is open ends it implicitly. **Newest start wins** when agents overlap; a `Stop` from a different session is ignored.
* **Scope:** text files under the project directory, inside IntelliJ content roots, not excluded or VCS-ignored, ≤ 1 MB, not binary, and not under `.git .idea .gradle .vscode node_modules out build dist target .venv __pycache__ .next`.
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
| `DiffManager.showDiff`, `SimpleDiffRequestChain`, `DiffContentFactory`, `SimpleDiffRequest` | confirmed (source), compile. **The diff window was never opened in a running IDE** (see *What was not verified*) | `diff-api` |
| `AnAction`/`ToggleAction` with `getActionUpdateThread()` | confirmed | [action system](https://plugins.jetbrains.com/docs/intellij/action-system.html) |
| `StatusBarWidgetFactory`, `ProjectActivity` | compile; `ProjectActivity` observed running in a real sandbox IDE (the plugin created its state directory on project open). The widget's on-screen text was **not** observed; not re-read in docs | — |
| `ModalityState.any()` must not be used for model changes (e.g. `saveAllDocuments`) | **found by a failing test**, then fixed | test log |

I did **not** run the JetBrains Plugin Verifier. Internal/impl API use is therefore known only from the table above.

### Agent hooks

| Claim | Status | Source |
|---|---|---|
| Claude Code runs `UserPromptSubmit` / `Stop` hooks from `~/.claude/settings.json` in the **desktop app** too; payload has `session_id`, `cwd` | confirmed in docs; *not run against the real app* | [hooks](https://code.claude.com/docs/en/hooks), [desktop](https://code.claude.com/docs/en/desktop) |
| Claude runs hooks only after the folder's workspace trust is accepted | confirmed in docs | hooks docs |
| Codex hooks from `~/.codex/hooks.json`, same JSON shape; Stop output must be JSON; new hooks need one-time approval; desktop app has Settings → Hooks | confirmed in docs; *not run against the real app* | [Codex hooks](https://learn.chatgpt.com/docs/hooks) |
| Antigravity `~/.gemini/config/hooks.json` with a named block; `PreInvocation`, `Stop.fullyIdle`; Stop requires a `decision` reply | confirmed in docs; forum users report hooks silently not firing in some builds → **best effort** | [Antigravity hooks](https://antigravity.google/docs/hooks) |
| T3 Code's Claude provider loads user/project settings (so hooks fire) and its Codex app-server runs hooks | read from T3 Code source at commit `e455200`; **not run** | `pingdotgg/t3code` |
| Claude `Stop` fires when you **interrupt** the agent | **unknown** — the docs I could read do not say | — |
| Codex `Interrupt` hook payload | event exists in the docs; payload **unverified**, not wired up | — |

What *was* run for real: the hook script itself (real `sh`, real spool, real parser, real watcher, real project service) in the headless-IDE test.

## What was not verified

Be aware before relying on it:

* **No human or automated look at the plugin in a real, on-screen IDE window.** A sandbox IDE (2026.2.1) was started on a demo project and the plugin loaded without errors, but screen capture was not permitted in the build environment and the computer-use sub-agent could not attach to a plain Java process, so nothing was seen on screen. Not observed on screen: the status-bar text, the Tools menu, the diff window, the install-hooks dialog, the tint colour/readability, and how the violet bar sits next to IntelliJ's Git marker.
* What *is* evidence for the gutter: a headless-IDE test paints the real editor gutter into an image and finds the violet bar (3 px wide, one line tall) on the agent-changed line, inside the gutter (`build/gui-shots/headless-gutter.png` is written by that test). That test has no Git, so overlap with Git's marker is untested.
* The hooks were **not** installed into, or fired by, the real Claude / Codex / Antigravity / T3 Code apps. Only their documented payload shapes and config formats were used.
* To check the visuals yourself: `./gradlew runIde -PdemoProject=<a git repo with an uncommitted change>`, open a file, then run `sh ~/.latest-ai-turn/hook.sh claude start` with a payload (see `TurnServiceIdeTest`), edit a file from a shell, and look.

## Known limitations and failure modes

* **Interrupted agents.** If an agent is interrupted and no `Stop` arrives, the turn stays open until the next prompt or a manual **End Turn**; changes made meanwhile (including by you through other tools) count toward it.
* **IDE-side agents look like you.** Anything that edits through the IDE's own editor (JetBrains AI, Junie, Copilot in IntelliJ) is treated as your own typing and is never highlighted. On-disk agents only.
* **Two agents at once:** the newest Start wins; the older agent's later writes are *not* highlighted as a second turn. In manual mode every disk change during the turn counts.
* **Git operations during a turn** (checkout, pull, stash) count toward the turn, because they are indistinguishable from an agent's writes. Use Re-baseline afterwards.
* **Spool is consumed by the first IDE that polls it;** two IDE instances on the same machine race. Signals are matched to projects by the agent's working directory (inside the project, or a parent that is not your home dir or `/`), so T3 / Codex worktrees outside the project directory are ignored.
* **Unsaved editor text vs an agent write.** IntelliJ's own behavior applies: a file with unsaved edits that an agent also writes triggers IntelliJ's conflict handling, and an IDE save landing after the agent's write overwrites it. Hook-started turns save all editors first; relying on this is not a guarantee.
* **Large file events run on the EDT.** The VFS listener reads and diffs changed files on the UI thread (inside IntelliJ's refresh). A branch switch touching many large files can briefly freeze the UI. Moving this off the EDT would change the ordering between reloads and edits, so it was left as is.
* **Whole-file rewrites.** The line diff stops searching for a minimal diff after 1500 edits and reports one big region.
* **Line-based.** A change that only differs in the final newline is not highlighted; line-ending-only changes are normalized away. Highlights while the *first scan* of a brand-new project is running may be missing for files not yet scanned.
* **Not covered:** moving a directory in/out of the project via refresh, content roots outside the project directory, multi-window attribution of one agent to several projects with different `cwd`s.
* **IDE versions.** Only **2026.2+ (build 262+)** is claimed. 2026.1 was not tested. No `until-build`, so 2026.3+ is allowed but unverified. Other JetBrains IDEs are untested.
* **Deviation from the plan:** the gutter is drawn with our own `MarkupModel` painter, **not** `SimpleLineStatusTracker`. Line-level "your edit takes ownership" cannot be expressed as a diff against a base text, and the painter uses only public editor APIs. The cost: no click-to-see-old-text popup on our bar (use the diff window).
* **Next to IntelliJ's Git marker.** Both gutters are shown on purpose (decision for the MVP). Our bar is drawn 4 px left of the right-hand marker area so it should not cover Git's; **this was not seen on screen** — see below.
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
