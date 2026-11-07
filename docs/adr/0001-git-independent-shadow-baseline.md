# Baseline is a Git-independent shadow copy, kept continuously

The Baseline is the plugin's own content-addressed copy of every Tracked File, stored in the IDE system directory (never in the project), seeded when the project opens and updated whenever a change is Absorbed. Turn Start only freezes it. We rejected diffing against Git HEAD (mixes in uncommitted and earlier-turn work — the problem this plugin exists to solve), IntelliJ Local History (can be disabled and is purged), and snapshotting at Turn Start (slow on large repos and races the agent's first write).

## Consequences

- Seeding costs one full read of the Tracked Files on first open; later opens only re-read files whose size/mtime changed.
- Files that change while the IDE is closed cannot be attributed; they are Absorbed with a warning.
