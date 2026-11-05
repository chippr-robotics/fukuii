# Recipe: test-greening

Drive a named failing test suite from red to green without introducing regressions
elsewhere or suppressing failures with @Ignore or DisabledTest.

**When to reach for it:** After a major refactor or migration leaves a known suite
red. Instantiate per-suite by replacing `<suite>` before running.

---

```yaml
id: test-greening-<suite>
goal: >
  fukuii-test essential exits 0 with the named suite <suite> showing 0 failures
  and no other previously-green suite newly red, and bin/verify.sh prints
  "LOOP:test-greening-<suite> ALL_GATES:PASS".
maker: wraith
checker: eye
gates: [compile, tests]
refresh_refs: false
constraints:
  - no previously-green test may be newly red after the loop closes
  - SyncTest-tagged tests (RegularSyncSpec, FastSyncSpec, SyncControllerSpec,
    BlockchainHostActorSpec, SyncStateDownloaderStateSpec) must not be un-excluded
    from the test runner; they stall under CI load
  - do not add @Ignore, @Tag("DisabledTest"), or similar tags to make tests pass
  - do not delete failing tests; fix the underlying code or skip with a tracked
    entry in CHASE-QUEUE (type `DEFER`) if genuinely untestable
budget:
  max_iterations: 25
  max_wallclock: 90m
  min_accept_rate: 0.5
stop_on: [gate_pass, budget_exhausted]
```

## LOOP_TEST_TARGET

Early iterations (finding root cause):
```
only *<SuiteName>*
```

Final gate (regression check):
```
essential
```

## DISCOVER Phase

1. Run `LOOP_TEST_TARGET="only *<SuiteName>*" .claude/looping/verify/tests.sh` to
   capture the current failure output.
2. Identify the failure type: assertion error, timeout, missing dependency, type error.
3. Trace the failure to the root cause in source code (not in test setup unless the
   test setup is genuinely wrong).
4. Record the root cause and proposed fix in the ledger.

## PLAN Phase (each iteration)

Pick the single failure or cluster of failures with the same root cause. Fix that,
not symptoms. One commit per root cause.

## EXECUTE Phase

Invoke wraith with the failure output and the identified root cause.

## VERIFY Phase

Run (final gate):
```sh
LOOP_TEST_TARGET=essential .claude/looping/bin/verify.sh test-greening-<suite> <ledger-dir>
```

Expected transcript:
```
GATE:compile RESULT:PASS
GATE:tests RESULT:PASS
LOOP:test-greening-<suite> ALL_GATES:PASS
```

eye issues: `CONFIRM:DONE`

## Continuation

Write `.local/docs/continuations/test-greening-<suite>.md` if budget is exhausted.
