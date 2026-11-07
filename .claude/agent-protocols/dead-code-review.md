# Dead Code Review Protocol

Every candidate for deletion deserves a question before the `git rm`: **is this
code genuinely dead, or is it code that was never wired and should be?**

Used by: WRAITH, PRISM, MITHRIL, and any agent performing dead-code sweeps
Referenced by: inline-cleanup.md, CHASE-QUEUE.md, CODEBASE-AUDIT.md

---

## Core principle

> Zero call sites does not mean zero value. Before deleting, ask three questions:
> (1) Is the code non-functional or broken?
> (2) Does it add value and should be wired or built out?
> (3) Is it genuinely low-value, redundant, or obsolete?
>
> Deletion is the right answer only when question 3 is yes. If question 2 is yes,
> the code should be wired — or if wiring is out of scope, deferred with a note,
> not silently erased.

---

## The three verdicts

### WIRE — code that should be connected, not deleted

Signals that a candidate should be wired instead of deleted:
- The implementation is complete, correct, and production-quality
- It addresses a real gap: an event not tracked, a strategy not used, a failure
  mode not handled
- The codebase settled on a workaround in the absence of this code
- The class/object/function has a clear, non-overlapping responsibility
- It was introduced alongside related code that IS wired (common: batch commits
  where one piece got forgotten)

**Action:** Identify the wiring point. If wiring is in-scope for the current
task, wire it. If out of scope, log a `DEFER` entry (see "Where deferred items go")
with the candidate, the wiring point, and the gap it would fill.

### DELETE — genuinely dead code

Signals that deletion is correct:
- Zero call sites AND the pattern has been superseded (a different mechanism
  handles the same concern)
- The implementation is a stub, skeleton, or placeholder with no real logic
- Long tenure without adoption (years in the codebase, no follow-up wiring commits)
- The design intent conflicts with a decision the codebase already made
- An exception/error type that is never thrown and the error path is handled differently
- An abstraction built for a use case that never materialized, with evidence
  (no tests, no callers, no ADR pointing at future use)

**Action:** grep-verify 0 callers, delete, compile-all (0 errors), commit with
rationale: what it was, why it was dead, what superseded it.

Before issuing the delete, also check for test-only callers:
```bash
grep -rn "ClassName\|methodName" src/test/ --include="*.scala"
```
If the candidate appears only in test sources: treat as WIRE, not DELETE. The test is either
(a) testing behavior that needs the candidate wired to production code, or (b) itself dead.
For (b), add a CHASE-QUEUE entry (`type: DEAD-TEST`) rather than silently dropping the test.

### DEFER — uncertain, needs context

Signals that the decision should be escalated:
- The code is high-quality and fills a real gap, but wiring it is out of scope
  for the current task
- The design conflicts with other code, suggesting an unresolved architectural
  question
- Git history shows it was part of a larger planned feature that was never finished

**Action:** Do NOT delete. Log a `DEFER` entry (see "Where deferred items go"). Describe:
what the code does, what gap it fills, what the wiring point would be, and why
the decision is deferred.

### Where deferred items go

There is no `DEFERRED-BACKLOG.md`: an earlier version of this protocol named one, and none has ever been tracked in git.
Deferred items are `DEFER` entries in `.claude/agent-protocols/working-docs/CHASE-QUEUE.md` (format in
`inline-cleanup.md`, Rule 1). That file is **local and gitignored** (`.gitignore`: `.claude/agent-protocols/working-docs/`),
and the directory is not in a fresh checkout or worktree: create it on first use. Because it is local, a deferral
another contributor must see goes into a GitHub issue as well, and the code's `@nowarn` or comment cites the issue
number (the convention in `warning-ratchet.md`, Step 3). A sprint with its own ledger, such as spec 020's
`dc4-ledger.md`, records the deferral there with its date and owner.

---

## Assessment questions

For every dead-code candidate, answer these before deciding:

**1. What does it do?**
Read the implementation. What problem does it solve? Is the implementation
complete and correct, or is it a stub/skeleton?

**2. Who are its callers?**
Run the grep. Is it truly zero external callers, or intra-package only?
Intra-package callers can themselves be dead — trace the chain.

**3. Is there a gap without it?**
Does the codebase have to work around its absence? Are events logged but not
metricated? Are fallback paths reactive where they could be proactive?
Are there TODO/FIXME comments pointing at its responsibility?

**4. What does git history say?**
When was it introduced? Was it part of a larger commit that wired related code
but left this piece out? Has it been there for years with no follow-up?

**5. Is the pattern superseded?**
Has the codebase chosen a different mechanism for the same concern? Does that
mechanism cover the gap adequately (even if less elegantly)?

---

## Intra-package dead code

Code that is only called within its own package is NOT automatically live. Trace
the full chain: if the package's external interface is unused, the entire package
(including its internal helpers) is effectively dead.

Example: `DeltaSpikeGauge` was called by `Metrics.deltaSpike()` within the
`metrics/` package — but `deltaSpike()` itself had zero call sites outside the
package. The package's external interface was missing this entry point entirely.

**Rule:** For intra-package-only code, verify that the calling class/method in
the package is itself called from outside. If not, trace to the outermost dead
entry point and assess the full subtree as a unit.

---

## Examples from this codebase

### MetricsAlreadyConfiguredError — DELETE (correct)

**What it was:** `case class MetricsAlreadyConfiguredError(previous: Metrics, current: Metrics)`

**Why delete was correct:** The exception was never thrown. `Metrics.configure()`
handles double-configuration silently: idempotent `putIfAbsent`, WARN log, and
cleanup of the duplicate. The error class described a failure mode the actual
code never used. No gap — the silent path is the design.

### LocalVM — DELETE (correct)

**What it was:** `object LocalVM extends VM[InMemoryWorldStateProxy, ...]` — a stub

**Why delete was correct:** Zero logic, zero callers, zero tests. The underlying
type (`InMemoryWorldStateProxy`) is alive and used directly by `BlockExecution`
and `BlockPreparator`. `LocalVM` was a wrapper that was never instantiated and
never needed.

### AdaptiveSyncStrategy — DELETE defensible, gap real (extraction deferred)

**What it was:** 193-line adaptive sync orchestrator with peer-count and latency
guards, strategy fallback chain (SnapSync → FastSync → FullSync), and per-strategy
attempt tracking.

**Why the gap is real:** `SyncController.start()` selects sync mode from static
config booleans. No peer-count pre-flight: if `doSnapSync=true` but only 1 peer
is available (SNAP requires 3), SNAP attempts and fails N times before reactive
fallback triggers. `AdaptiveSyncStrategy` would have short-circuited this at startup.

**Why delete was defensible:** Never wired in 6+ years. Design conflicts with
`SyncController`'s own reactive fallback ownership. Current approach (reactive
failure handling) eventually converges. The decision logic should be re-extracted
as a lightweight pure function, not a class with mutable state.

**Deferred:** the `SyncStartupStrategy` extraction. (The backlog file this entry once cited no longer exists.)

### DeltaSpikeGauge — DELETE (correct)

**What it was:** A self-resetting spike metric (value→1 on `.trigger()`, resets
to 0 after next scrape). Complete, thread-safe Micrometer implementation.

**Why delete was correct:** Introduced 2020, zero call sites in 6+ years. The
codebase settled on `counter` (cumulative) and `gauge` with int state values
(0/1/2/3 for phases) for discrete events — both cover the same observability
need. The spike pattern was designed for sub-scrape-interval precision that the
current event rate doesn't require. The factory method `Metrics.deltaSpike()` was
the only entry point, and it was never called.

---

## Integration with CHASE-QUEUE

When a dead-code sweep identifies candidates, log each in CHASE-QUEUE.md as:

```
| path/to/File.scala | — | Dead code: <class> — zero callers, <reason> | DEAD | PRISM | YYYY-MM-DD |
```

When an agent assesses a DEAD entry before deletion, document the verdict in the
CHASE-QUEUE entry notes before executing the clearout prompt. The three-question
assessment should take at most 5 minutes — it is not a deep investigation.

If the verdict is WIRE or DEFER, change the entry type from `DEAD` to `DEFER`
(the entry stays in CHASE-QUEUE, see "Where deferred items go"). Remove it from the clearout queue.

---

## Commit message format

```
Part Xf — delete <ClassName> (<brief rationale>)

Example:
Part 8f — delete DeltaSpikeGauge (unused spike metric, no call sites in 6 years; counter/gauge pattern covers the need)
Part 8f — delete LocalVM (stub object, never instantiated; InMemoryWorldStateProxy used directly)
```

Rationale in the commit message is non-negotiable. Future developers reading git
log deserve to know *why* the code was removed, not just that it was.

---

## What NOT to do

- Do not delete code because it has no tests (a live class can have poor test coverage)
- Do not delete code because it "looks experimental" — assess the implementation quality
- Do not delete code that is part of an in-progress migration (may be not-yet-wired
  Typed code, new protocol handler, etc.) — check git blame and the working-docs sprint plan
- Do not delete code marked with `// TODO: wire this` or similar intent markers without
  escalating the deferred item first
- Do not batch a WIRE candidate with DELETE candidates in the same commit
