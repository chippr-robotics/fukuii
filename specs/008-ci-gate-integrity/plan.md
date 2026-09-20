# Implementation Plan: CI Gate Integrity

**Feature**: `008-ci-gate-integrity` | **Spec**: [spec.md](./spec.md)
**Issues**: #1402, #1403, #1404 | **Created**: 2026-09-20

## Approach

The three issues are one defect: **claims are not bound to evidence**. So the design is a single
binding — a declared matrix — plus one meta-check that fails when a claim outruns its evidence.

```
.github/gates.yml                 <- the one declaration: tiers, owners, waivers, canonical facts
   |
   +-- _hive-sim.yml              <- the mechanism: a suite that cannot fail is impossible
   +-- check_gate_integrity.py    <- the meta-check: 13 checks, required, offline, self-tested
   +-- generate_status.py --> docs/STATUS.md   <- the public claim, generated not written
   +-- constitution-enforcement.md             <- principle -> gate, or labelled aspirational
   +-- ChainIsolationSpec.scala                <- Principle I.3-I.5 as an executable invariant
```

Everything derives from the matrix. Nothing is hand-maintained in two places.

## Constitution Check

| Principle | Impact |
|---|---|
| I — Consensus determinism | **Strengthened.** US5 converts the unasserted ETC/ETH isolation convention into an enforced invariant. No consensus code changes; the spec asserts over existing configuration. |
| II — Spec-driven | Followed: this spec precedes the implementation. |
| III — Test discipline | `ChainIsolationSpec` is Tier-1, deterministic, no `Thread.sleep`. The Python meta-check carries its own self-test suite. |
| IV — Scala 3 style | One new test file; `scalafmt` applies. |
| V — Quality gates | **The point of the feature.** V.5 gains a mechanism for the first time. |
| VI — Security | No key, crypto, or network surface touched. |
| VII — Versioning | FR-019 adds a check that `version.sbt` stays the single scheme. |

No violations. No Complexity Tracking entries.

## Key design decisions

**D1 — Declare nothing required without evidence.** The first draft of the matrix promoted
`hive-sync`, `hive-smoke-genesis`, and `hive-smoke-network` to `required`. Checking the run history
killed that: the last green `main`-branch smoke-genesis run was 2026-04-30 (run 25184051794), and
every PR run since has been red. Declaring a suite required that nobody can pass produces a waiver
within a week — the v0.8.0 defect with the sign flipped. So **no Hive suite is required at
landing**. The mechanism lands now; promotion is scheduled in `first_required_slice` with dates,
and check C4 requires an `evidence:` URL before any Hive gate may be promoted.

**D2 — One terminal gate step, always evaluated.** The old design had two conditional gate steps.
Between them they let three distinct failures report green: zero results (threshold step skipped
when `pass_threshold: 0`, subset step finds no failures among no tests), truncated runs, and
skipped gates after an earlier step failed. One `if: always()` step that owns the verdict closes
all three. Reporting stays in the tabulate step, so a parse hiccup degrades the summary, never the
verdict.

**D3 — Waivers expire.** The v0.8.0 failure was not missing gates; it was an unexpiring, anonymous
exclusion of the one test that mattered (`sync go-ethereum from fukuii`). Expiry with no bypass is
what stops the system regressing. Both existing exclusions are now declared waivers with owner,
issue, reason, and date; C7 fails on any exclusion not declared, in either direction.

**D4 — The meta-check is itself required and self-tested.** A linter never shown to fail is another
unbacked claim. `test_gate_integrity.py` mutates a sandboxed copy of the repo and asserts the check
goes red for each of the 13 conditions — 16 cases including a clean-tree baseline and two
time-travel cases for expiry.

**D5 — Generated public truth.** `docs/STATUS.md` is generated from the matrix and C13 fails if the
committed copy is stale. A claim that is a projection of the matrix cannot drift from it. C12
additionally scans prose for verification language applied to non-required suites.

**D6 — Negation-aware claim checking.** C12's first run flagged the sentence "we do not claim a
suite is verified unless a check can fail on it" — punishing exactly the honest phrasing the check
exists to encourage. Fixed with a bounded negation window, with its limits documented in the source
rather than hidden: it is a heuristic, not a parser, and a determined author could phrase around
it. Accepted — the check targets drift by well-meaning authors, not adversaries.

**D7 — The isolation invariant is unreachability, not absence.** The spec's first run failed on
the `test` fixture chain, which declares ETH fork timestamps at year-2286 sentinels so the Engine
API specs can exercise fork activation. The finding was in the assertion: what protects ETC is that
no block it imports can carry a timestamp tripping those predicates — absence is one sufficient
route to that, not the property itself. Restated in two tiers: no *reachable* ETH fork timestamp on
any ETC chain (2200-01-01 horizon), plus strict absence on production ETC chains. The production
rule keeps its original strength and the fixture allowance is bounded and named, so this is a
correction rather than the weakening this plan warns against. The negative control confirms it:
a reachable timestamp injected into `mordor-chain.conf` fires three assertions, including the
behavioural `forBlock` equality check.

## Expected consequence, stated plainly

**Hive badges will go red.** Suites that previously could not fail now fail when they produce no
results or fewer than `min_tests`. That is the feature working: the badges were green because
nothing could turn them red, not because the client passed. The matrix declares every one of them
`informational`, so none of them blocks a merge, and `docs/STATUS.md` says so publicly.

## Out of scope

Fixing the underlying Hive failures; changing GitHub branch-protection settings (outside the
repo — the matrix declares them, a maintainer applies them); the fukuii.com site itself. See
spec.md → Out of Scope.

## Validation

| What | How | Result |
|---|---|---|
| Gate shell logic | 7 cases against extracted step (`bash -n` + execution) | 7/7 behaved |
| Meta-check | `scripts/ci/test_gate_integrity.py` | 16/16 behaved |
| Meta-check on this tree | `python3 scripts/ci/check_gate_integrity.py` | 0 failures, 0 warnings |
| All workflow YAML | `yaml.safe_load` per file | parses |
| `ChainIsolationSpec` | `sbt "testOnly *ChainIsolationSpec*"` on JDK 25 | **10/10 pass** |
| `ChainIsolationSpec` negative control | injected a reachable ETH fork timestamp into `mordor-chain.conf` | **3 assertions fired**, config restored |
