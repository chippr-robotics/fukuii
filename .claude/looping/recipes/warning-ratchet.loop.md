# Recipe: warning-ratchet

Promote the next eligible Scalac warning category from "reported" to "build error",
fix every site that now fails, and close with a clean compile and no new suppressions.

**When to reach for it:** When CHASE-QUEUE (`DEFER` entries) shows a warning category is no longer
externally gated. The deprecation tier (68 sites) is gated on json4s upgrade — do not
start this recipe until that upgrade is complete. Check `working-docs/CHASE-QUEUE.md`
before invoking.

**Do not run mid-sprint.** This recipe commits bucket-A (mechanical) changes. It must
not be interleaved with feature or consensus changes.

---

```yaml
id: warning-ratchet-tier5
goal: >
  sbt compile-all exits 0 with -Wconf:cat=deprecation:error added to scalacOptions,
  no new @nowarn or @SuppressWarnings annotations exist anywhere in the diff, and
  bin/verify.sh prints "LOOP:warning-ratchet-tier5 ALL_GATES:PASS".
maker: wraith
checker: eye
gates: [compile, warnings, tests]
refresh_refs: false
constraints:
  - no new @nowarn or @SuppressWarnings at any scope (blanket or site-level)
  - -Wconf:cat=deprecation:error must remain in scalacOptions after the loop closes
  - follow risk-stratified-commit protocol: bucket-A sites only per commit, no mixing
  - do not touch consensus/, vm/, crypto/, or domain/ paths (route to forge/beacon first)
  - do not remove or weaken any existing ratcheted warning (E198, unchecked)
budget:
  max_iterations: 25
  max_wallclock: 90m
  min_accept_rate: 0.5
stop_on: [gate_pass, budget_exhausted]
```

## LOOP_TEST_TARGET

```
essential
```

## DISCOVER Phase

1. Run `sbt compile-all 2>&1 | grep -E 'deprecation|warning'` to inventory all sites.
2. Cluster by package: `com.chipprbots.ethereum.blockchain`, `network`, `jsonrpc`, etc.
3. Check CHASE-QUEUE for any sites already tagged with a deferral reason that is
   still active. Do not try to fix gated sites.
4. Estimate: how many sites are in each package? Can wraith fix a whole package per iteration?

## PLAN Phase (each iteration)

State the single highest-impact cluster to fix: package + approximate site count.

## EXECUTE Phase

Invoke wraith with the compile output and the target package.
Wraith must not add any @nowarn. If a site cannot be fixed without a @nowarn,
record it in CHASE-QUEUE (type `DEFER`) with a reason and skip it.

## VERIFY Phase

Run:
```sh
LOOP_TEST_TARGET=essential .claude/looping/bin/verify.sh warning-ratchet-tier5 <ledger-dir>
```

The transcript must show:
```
GATE:compile RESULT:PASS
GATE:warnings RESULT:PASS
GATE:tests RESULT:PASS
LOOP:warning-ratchet-tier5 ALL_GATES:PASS
```

eye must then issue: `CONFIRM:DONE`

## Continuation

If budget is exhausted mid-run, write `.local/docs/continuations/warning-ratchet-tier5.md`
with the current iteration count, remaining clusters, and the last ledger entry.
