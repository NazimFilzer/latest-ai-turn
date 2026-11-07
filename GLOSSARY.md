# Latest AI Turn

An IntelliJ plugin that highlights, in the normal editor, only what the most recent AI agent turn changed — independent of Git.

## Turns

**Turn**:
One unit of AI agent work on the project, bounded by a Turn Start and a Turn End.
_Avoid_: session, prompt, run

**Latest Turn**:
The most recently started Turn; the only Turn whose changes are ever highlighted.
_Avoid_: current turn, last turn

**Turn Start**:
The moment a Turn begins; freezes the Baseline that the Turn is measured against.

**Turn End**:
The moment a Turn stops accepting External Changes; its highlights stay visible until the next Turn Start.

**Turn Signal**:
A Turn Start or Turn End reported automatically by an agent's hook, as opposed to one the user triggers.
_Avoid_: event, notification, trigger

**Agent Session**:
The agent conversation whose Turn Signal started the Latest Turn; only its Turn End Signal ends that Turn.
_Avoid_: thread, chat, conversation

**Manual Mode**:
Operating with user-triggered Turn Start and Turn End only, no Turn Signals.

**Hook Mode**:
Operating on Turn Signals from installed agent hooks; Manual Mode actions keep working alongside it.
_Avoid_: auto mode, automatic detection

## Baseline

**Baseline**:
Per Tracked File, the text the Latest Turn is measured against: the file as it was at Turn Start, plus every Absorbed change since.
_Avoid_: HEAD, original, snapshot

**Turn Change**:
A region where a file's current text differs from its Baseline; the thing that gets highlighted.
_Avoid_: diff, modification, AI change

**Absorb**:
To fold a change into the Baseline so it is never highlighted.
_Avoid_: ignore, accept

**Re-baseline**:
A user action that Absorbs everything, making the current text of every Tracked File its Baseline.
_Avoid_: reset, reindex

**Tracked File**:
A text project file eligible for highlighting; excludes IDE metadata (`.idea`), excluded and ignored paths, binaries and oversized files.

## Change sources

**Own Edit**:
A change typed or applied through the IDE editor; always Absorbed, and takes ownership of any Turn Change it touches.
_Avoid_: manual edit, user change

**External Change**:
A change that arrives from disk rather than through the IDE editor.
_Avoid_: agent change, disk edit

**Foreign Change**:
An External Change not attributed to the Latest Turn — e.g. one arriving while no Turn is open; always Absorbed.
_Avoid_: noise, unrelated change
