# Migration Handoff Protocol

When any agent session ends with work still in progress — turns running low,
context compressing, or a logical phase complete with more remaining — write a
continuation file before the session ends.

Used by: ALL agents
Referenced by: `fukuii/CLAUDE.md`

---

## Trigger conditions

Write a continuation file when:
- Turns are running low mid-task (agent judgment — before the last 2-3 turns)
- A logical phase completed but the next phase has not started
- A compile error is open that the session cannot resolve in remaining turns
- Context compression has started (earlier work may be lost)

## File location and naming

```
<project>/.local/docs/continuations/<AgentName>-<Topic>.md
```

Examples:
- `fukuii/.local/docs/continuations/loom-AccountRangeCoordinator.md`
- `fukuii/.local/docs/continuations/wraith-E003-self-types.md`
- `fukuii/.local/docs/continuations/mithril-implicit-to-given.md`

## Required sections

```markdown
# <AgentName> continuation — <Topic>

## Status
What phase just completed. What phase is next. One paragraph max.

## Files modified
| File | Status | Last action taken |
|------|--------|-------------------|
| path/to/File.scala | COMPLETE | migrated to Typed |
| path/to/Other.scala | IN PROGRESS | returns removed, migration not started |

## Open compile errors
Paste exact `sbt compile-all` error output. If clean: "sbt compile-all — 0 errors as of <phase>".

## Next action
One specific instruction. Not a plan — a single first move:
"Open X.scala line N and replace Y with Z, then run sbt compile-all."

## Test baseline
Last verified result: "7,219 / 0 — verified after Phase 0 commit <sha>"
If not run yet: "Not run — compile-only phases so far."

## Known hazards
Anything the continuation agent must know before touching code:
- Which files are still Classic and must not be adapted
- Which callers have been partially updated
- Any open questions flagged for human review
```

## What NOT to include

- Speculation about what might work
- Summaries of prior sessions (those live in `summaries/`)
- Re-research of established patterns (reference `summaries/` instead)

## Continuation thread startup

The first action in any continuation thread:
1. Read this file
2. Read relevant entries in `summaries/` for established patterns
3. Run `sbt compile-all` to confirm current state matches "Open compile errors" above
4. Execute "Next action" — do not plan, do not re-triage

## After the task completes

Delete the continuation file. Its purpose is point-in-time recovery, not permanent record.
Permanent record goes in `summaries/<GroupName>.md`.
