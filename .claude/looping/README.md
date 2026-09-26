# Fukuii Looping Subsystem

A harness for driving recurring engineering jobs to completion over multiple
iterations with scripted verification gates and a maker/checker split that
prevents self-grading.

---

## What This Is

A loop is a goal Claude keeps working toward until a real gate passes:

```
DISCOVER -> PLAN -> EXECUTE -> VERIFY -> ITERATE
```

The maker does the work. The checker confirms it is done. The maker never
grades its own homework (Ralph guard). Gates are shell scripts that print
canonical sentinel lines — not the model's narrative claim about what happened.

---

## Files

```
looping/
  README.md       this file
  DISCOVERY.md    confirmed build commands, paths, open assumptions
  ELIGIBILITY.md  four-box test for qualifying a job as a loop
  LOOP_SPEC.md    loop contract, sentinel protocol, state ledger schema
  registry.yaml   agent role map + reference repo list
  bin/
    eligible.sh         eligibility check before any loop run
    refresh-refs.sh     pull upstream on all reference repos (fast-forward only)
    run-loop.sh         orchestrator entry point: init ledger, check budget
    verify.sh           run required gates for a recipe; emit sentinel lines
    budget-check.sh     enforce iteration/wallclock/accept-rate caps
  verify/
    compile.sh          sbt compile-all
    warnings.sh         no new @nowarn/@SuppressWarnings vs HEAD
    tests.sh            fukuii-test tier or targeted suite
    format.sh           sbt scalafmtCheck
    conformance.sh      diff vs spec repos + upstream client branches
  recipes/
    warning-ratchet.loop.md
    spec-conformance.loop.md
    test-greening.loop.md
    actor-migration.loop.md
    ref-parity-audit.loop.md
  state/
    .gitkeep            (per-run ledger dirs are gitignored)
```

---

## Quick Start

### Finish-line recipe via /goal

```
/goal The verify runner has printed "LOOP:test-greening-SnapHealingTaskSpec ALL_GATES:PASS"
in this session. Prove it by running .claude/looping/bin/verify.sh
test-greening-SnapHealingTaskSpec and letting its output appear here.
Constraints: no previously-green test may be newly red. Stop after 25 turns if not met.
```

### Finish-line recipe via run-loop.sh

```sh
.claude/looping/bin/run-loop.sh test-greening-SnapHealingTaskSpec
```

The script initializes the ledger, checks eligibility and budget, then prints
instructions for the orchestrating session. The DISCOVER->EXECUTE->VERIFY cycle
is driven by the Claude session reading the ledger and invoking agents.

### Poll recipe via /loop

```
/loop 1w .claude/looping/bin/run-loop.sh ref-parity-audit
```

### Headless via claude -p

```sh
claude -p "/goal LOOP:test-greening-SnapHealingTaskSpec ALL_GATES:PASS -- prove by running .claude/looping/bin/verify.sh test-greening-SnapHealingTaskSpec <ledger-dir>" \
  --max-turns 30 \
  --model sonnet
```

---

## Sentinel Line Protocol

Gate scripts print exactly one of:
```
GATE:<name> RESULT:PASS
GATE:<name> RESULT:FAIL detail=<reason>
```

`verify.sh` aggregates and prints:
```
LOOP:<id> ALL_GATES:PASS
LOOP:<id> ALL_GATES:FAIL failed=[<name>,...]
```

The checker reads these lines from the transcript. The checker issues:
```
CONFIRM:DONE
CONFIRM:ITERATE reason=<what failed>
```

---

## Agent Role Map

| Agent | Role | Gate | Proactive? |
|-------|------|------|-----------|
| wraith | maker | — | no |
| mithril | maker | — | no |
| loom | maker | — | no |
| eye | checker | compile, tests | no |
| prism | checker | quality | no |
| forge | checker | conformance-etc | YES (consult in DISCOVER) |
| beacon | checker | conformance-eth | YES (consult in DISCOVER) |
| herald | checker | conformance-wire | no |
| vault | checker | conformance-storage | no |
| conduit | checker | conformance-rpc | no |
| flow | checker | conformance-streams | no |

**Proactive** means the checker must be consulted in the DISCOVER phase, before
the maker executes. This is mandatory for forge (ETC consensus) and beacon (ETH
consensus) per `consensus-change-protocol.md`.

---

## Recipe Selection Guide

| Job | Recipe | Map to |
|-----|--------|--------|
| Promote next warning tier to build error | warning-ratchet | /goal |
| Align implementation with EIP/ECIP/upstream client | spec-conformance | /goal |
| Drive a failing test suite to green | test-greening | /goal |
| Migrate one Pekko actor Classic->Typed | actor-migration | /goal |
| Weekly upstream drift check | ref-parity-audit | /loop |

---

## Order of Operations

Follow this order. Do not skip steps.

1. Run the recipe manually once to confirm it closes cleanly.
2. Confirm gate scripts produce valid sentinel lines.
3. Confirm the ledger and attempts.json are written.
4. Confirm the checker issues CONFIRM:DONE (not the maker).
5. Only then schedule with /loop or automate with claude -p.

---

## Reference Repo Discipline

`refresh-refs.sh` pulls ONLY the `upstream` branch for each reference client.
The `main` branch (where ETC overlays exist: besu, core-geth, nethermind) is
never touched. Conformance gates always diff against `upstream`, never `main`.

Clients with ETC overlays on main: **besu, core-geth, nethermind**
Upstream-only (no overlay yet): go-ethereum, reth, erigon

---

## Existing Protocols This Subsystem Extends

- `warning-ratchet.md` — 4-step ratchet; the warning-ratchet recipe automates step 3
- `migration-handoff.md` — continuation files for interrupted sessions
- `testing-protocol.md` — compile-per-file cadence honored in EXECUTE phase
- `risk-stratified-commit.md` — bucket A/B/C commit discipline in EXECUTE
- `pre-migration-checklist.md` — 13-point audit; mandatory in actor-migration DISCOVER
- `consensus-change-protocol.md` — hard stop; forge/beacon proactive in DISCOVER
- `loop-handoff.md` — maker->checker handoff contract (new, in agent-protocols/)

---

## Build Report (Self-Verification)

**Date:** 2026-06-24
**Self-verify recipe:** self-verify (compile + format gates; tests gate excluded — 24 min)

### Verified

```
eligible.sh warning-ratchet   -> ELIGIBLE:YES
eligible.sh ref-parity-audit  -> ELIGIBLE:YES
eligible.sh test-greening     -> ELIGIBLE:YES
eligible.sh actor-migration   -> ELIGIBLE:YES
eligible.sh test-greening-baseline -> ELIGIBLE:NO reason=recipe-not-found  (correct)

run-loop.sh self-verify       -> BUDGET:OK iterations=0 wallclock_remaining=300s
                                 ledger dir created, start_time + attempts.json written

verify.sh self-verify <ledger>:
  GATE:compile RESULT:PASS
  GATE:format RESULT:PASS
  LOOP:self-verify ALL_GATES:PASS

eye checker: CONFIRM:DONE
```

### Build commands confirmed against build.sbt

- `sbt compile-all` — aggregate compile (confirmed; exits 0 on clean tree)
- `sbt testEssential` — Tier 1 test task (3,595+ tests; 24 min warm JVM)
- `sbt scalafmtCheck` — format verify (confirmed; scalafmt plugin task)
- `./scripts/fukuii-test` — wrapper script (verified 2026-09-22; the old `.local/scripts/` path does not exist)

### Agent role map

All 11 agents in `.claude/agents/` have loop-metadata blocks. Makers: wraith,
mithril, loom (never_self_check: true). Checkers: eye, prism, forge, beacon,
herald, vault, conduit, flow. Proactive checkers (consulted in DISCOVER): forge,
beacon.

### Fixes applied during bootstrap

- `bin/budget-check.sh` line 51: POSIX sh `grep -c ... || printf '0'` inside `$()`
  produced `0\n0` (grep exits 1 on 0 matches but still prints "0"; the `||` then
  added a second "0"). Fixed: `_count=$(grep -c ...) || _count=0` with `||` outside.
- `bin/run-loop.sh` line 57: same bug. Fixed identically.

### Open assumptions from DISCOVERY.md

1. `conformance.sh` produces structural diff only; semantic verdict requires checker
   agent review. Documented in the gate script.
2. `tests.sh` SyncTest guard: RegularSyncSpec/FastSyncSpec/SyncControllerSpec/
   BlockchainHostActorSpec excluded explicitly. Verified against DEFERRED-BACKLOG.
