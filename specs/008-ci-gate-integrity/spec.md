# Feature Specification: CI Gate Integrity — Honest Signal, Enforced Constitution, One Public Truth

**Feature Branch**: `008-ci-gate-integrity`

**Created**: 2026-09-20

**Status**: Draft

**Input**: Issues #1402 (Make Hive + comprehensive a hard release gate), #1403 (One public truth), #1404 (Enforce the constitution on merge)

## Context & Motivation

Constitution Principle V states "`main` is always releasable" and "a red build is never merged
by lowering a gate". v0.8.0 shipped anyway with the Hive sync-server direction red and
`consume-rlp` failing ~67% of its cases. Nothing in CI objected. That is not a process lapse —
it is a **design defect in the gate mechanism**, and it is the common root of all three issues.

### Measured state (at `9ad5f70`, v0.8.0)

**The badge wall is decorative.** `README.md` claims "a failing suite is immediately visible in
the badge wall below". Thirteen Hive badges are rendered. Of the twelve caller workflows that
invoke `_hive-sim.yml`, **eleven pass `pass_threshold: 0` and no `gate_pattern`** — the reusable
workflow's own defaults — so their jobs end with `exit 0` regardless of results. A simulator at
0% pass renders the same green badge as one at 100%. One badge (`hive-rpc.yml`) points at a
workflow file that does not exist.

**The one real gate excludes the thing it was built to catch.** `hive-sync.yml` is the sole
caller with `gate_pattern: 'fukuii'`. It then sets
`gate_exclude: 'sync go-ethereum from fukuii|sync fukuii from nethermind'` — which removes
fukuii-as-sync-server, precisely the failure #1402 names. The exclusion carries no owner, no
tracking issue, and no expiry, so it is permanent by default.

**Zero tests counts as a pass.** In `_hive-sim.yml`, when the simulator produces no JSON results
and no parsable log, `passed=0 failed=0 total=0`. The threshold gate is skipped
(`if: inputs.pass_threshold > 0`), the subset gate finds zero failures, and the job succeeds.
A simulator that never started is indistinguishable from one that passed. The `hive` process
exit code is captured into `steps.sim.outputs.hive_exit`, printed in the summary — and never
enforced.

**Required checks do not include any of this.** `.github/BRANCH_PROTECTION.md` requires exactly
two contexts: `Test and Build` and `Build Docker Images`. No Hive suite, no ethereum/tests, no
comprehensive tier, and no constitution check is required to merge.

**The constitution is unenforced by construction.** Seven principles, each written as MUST, and
no mapping from any principle to any check. Principle I's ETC/ETH isolation rule
(`OlympiaOpCodes`/`forBlock()` vs `OsakaOpCodes`/`forTimestamp()`) has no test asserting it —
and the two code paths meet in a single `EvmConfig.forBlock(blockNumber, timestamp, config)`
overload where ETH timestamp forks are applied to *any* config that carries them. The invariant
that keeps ETC safe today is that ETC chain configs leave `forkTimestamps` unset. Nothing
asserts that. It is a convention, one config edit away from a chain split.

**Public claims exceed enforced reality.** `docs/index.md` advertises "**Hive-verified
compliance** — Full Ethereum Foundation Hive simulator suite ... runs per-simulator in CI".
"Runs" is true; "verified" is not, because eleven of twelve runs cannot fail.

### The through-line

All three issues are the same failure: **claims are not bound to evidence.** A badge claims
green without a gate behind it; a doc claims compliance without a gate behind it; a constitution
claims MUST without a gate behind it. The fix is not more gates bolted on — it is a single
declared, machine-checked binding between every claim and the check that earns it, plus a
meta-check that fails when a claim outruns its evidence.

This feature is **CI, governance, and documentation only**. It changes no consensus code. The one
Scala artifact it adds is a read-only assertion spec that observes chain configuration; it alters
no consensus semantics and no production code path.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A green check means tests actually ran and actually passed (Priority: P1)

A maintainer looks at a Hive workflow badge or a PR check. Today green means "the job reached its
last step", which includes the case where the simulator crashed before running a single test.
After this change, a Hive job can only be green if the simulator produced results, produced at
least the declared minimum number of test cases, exited cleanly, and had zero failures in its
declared gated subset. Any other outcome is red.

**Why this priority**: This is the load-bearing repair. Every other story in this spec depends on
the signal being trustworthy; a matrix of required checks is worthless if "required and green" can
be reached by a crash. It is independently valuable: even with no other change, it converts eleven
decorative badges into honest ones.

**Independent Test**: Run `_hive-sim.yml` against a simulator forced to produce zero results and
assert the job fails; run it against a simulator with a known failure inside the gated subset and
assert the job fails; run it against a fully passing simulator and assert the job succeeds.

**Acceptance Scenarios**:

1. **Given** a Hive simulator that crashes before emitting any result, **When** the job tabulates,
   **Then** the job FAILS with a message naming zero-results as the cause — it does not report a
   pass.
2. **Given** a Hive simulator whose declared `min_tests` is 10 and which emits 3 results,
   **When** the job tabulates, **Then** the job FAILS naming the shortfall (a truncated run is
   not a pass).
3. **Given** the `hive` binary exits non-zero while the simulator emitted no results, **When** the
   job tabulates, **Then** the job FAILS and surfaces the exit code.
4. **Given** a simulator with failures **outside** any declared gate and a suite declared
   `informational`, **When** the job completes, **Then** the job reports the counts and is NOT
   green — it is neutral/red per its declared tier, and never claims to have verified anything.
5. **Given** an earlier step in the job fails, **When** the workflow finishes, **Then** the gate
   steps still evaluate (they are not silently skipped) and the job is red.

---

### User Story 2 - Every check is declared required or informational, in one place (Priority: P1)

A maintainer or an outside reader asks "what must be green to merge, and what is merely observed?"
Today the answer is split between branch-protection settings they cannot see, workflow files, and
prose. After this change, a single committed file declares every check, its tier, its owner, and —
for anything not yet required — the tracking issue and the date by which it must become required
or be formally dropped.

**Why this priority**: #1402 asks for a "required vs informational matrix". Without a declared
matrix there is no way to detect drift, and the P1 gate repairs above have no schedule to be
applied against. Equal priority to Story 1 because the two are useless apart: honest gates nobody
declared, or a matrix over gates that can be reached by crashing.

**Independent Test**: The matrix file parses; every workflow that invokes `_hive-sim.yml` appears
in it; every branch-protection required context appears in it; a check present in CI but absent
from the matrix fails the meta-check.

**Acceptance Scenarios**:

1. **Given** the gate matrix, **When** a new Hive caller workflow is added without a matrix entry,
   **Then** the meta-check FAILS naming the undeclared workflow.
2. **Given** a matrix entry declared `required`, **When** its workflow has no enforcing gate
   configured (no threshold, no subset gate, no minimum), **Then** the meta-check FAILS — a suite
   cannot be declared required while being unable to fail.
3. **Given** the matrix, **When** a reader opens it, **Then** each entry states tier, owner,
   what it covers, and for non-required entries the tracking issue and promotion-or-drop date.

---

### User Story 3 - Waivers expire; they do not accumulate (Priority: P1)

An engineer needs to land work while one known test is red. They add a waiver. Today a
`gate_exclude` string is permanent and anonymous. After this change, every waiver names an owner,
a tracking issue, a one-line reason, and an expiry date; the meta-check fails the build once the
expiry passes, forcing an explicit decision — fix it, or re-authorize it in the open with a new
date.

**Why this priority**: The v0.8.0 failure was not caused by the absence of gates; it was caused by
an unexpiring exclusion of the exact test that mattered. A gate system without waiver expiry
regresses to the current state within a quarter. This is the mechanism that makes Stories 1 and 2
hold over time rather than at a single commit.

**Independent Test**: Add a waiver with a past expiry and assert the meta-check fails; with a
future expiry and complete metadata, assert it passes; with a missing issue or owner, assert it
fails.

**Acceptance Scenarios**:

1. **Given** a waiver whose `expires` date is in the past, **When** the meta-check runs, **Then**
   it FAILS naming the waiver, its owner, and its tracking issue.
2. **Given** a waiver missing `owner`, `issue`, `reason`, or `expires`, **When** the meta-check
   runs, **Then** it FAILS — incomplete waivers are rejected at authoring time, not discovered
   later.
3. **Given** a `gate_exclude` pattern in a workflow, **When** the meta-check runs, **Then** every
   alternation branch of that pattern MUST correspond to a declared waiver; an undeclared
   exclusion FAILS.
4. **Given** a waiver approaching expiry (within 14 days), **When** the meta-check runs, **Then**
   it WARNS in the job summary without failing, so the decision is scheduled rather than ambushed.

---

### User Story 4 - The constitution maps to checks, or admits it is aspirational (Priority: P2)

A contributor reads a principle stated as MUST and asks "what enforces this?". After this change,
every principle and every numbered rule beneath it carries one of two labels: `enforced` with the
name of the check that enforces it, or `aspirational` with a one-line statement of why it is not
machine-checkable and what review step substitutes. No rule is left implying enforcement it does
not have.

**Why this priority**: #1404's core ask. It depends on Stories 1–3 (a principle can only map to a
check that can actually fail), so it follows them. Independently valuable: the map alone tells a
reviewer which MUSTs they personally have to verify.

**Independent Test**: Every principle section in the constitution appears in the enforcement map;
every `enforced` row names a check that exists in the gate matrix; a principle added without a
mapping fails the meta-check.

**Acceptance Scenarios**:

1. **Given** the enforcement map, **When** a principle is labelled `enforced`, **Then** it names a
   check that exists in the gate matrix and that check is not itself unable-to-fail.
2. **Given** a principle labelled `aspirational`, **When** a reader looks, **Then** it states the
   human review step that substitutes, so the gap is visible rather than implied.
3. **Given** a new or renamed constitution principle, **When** the meta-check runs, **Then** it
   FAILS until the enforcement map covers it.

---

### User Story 5 - ETC and ETH fork dispatch cannot silently merge (Priority: P2)

A contributor edits a chain config or a fork-dispatch table. Today the only thing preventing an
ETH timestamp fork from activating on an ETC chain is that ETC configs happen to leave fork
timestamps unset — an unasserted convention in a shared code path. After this change, an
executable spec asserts the isolation invariant for every shipped chain config, so a config edit
that would let Osaka semantics reach an ETC block fails CI instead of reaching a node.

**Why this priority**: #1404 explicitly asks for this, and Principle I ranks above everything.
Placed at P2 rather than P1 only because it is a narrow, well-understood assertion with no
dependency on the gate machinery — it can land any time after the matrix exists to declare it.

**Independent Test**: The spec passes against every chain config in `blockchains.conf` today;
mutating an ETC config to carry an ETH fork timestamp makes it fail.

**Acceptance Scenarios**:

1. **Given** every chain whose `network-type` is ETC, **When** the isolation spec runs, **Then**
   all ETH fork timestamps are absent and `terminalTotalDifficulty` is absent — ETC does not merge.
2. **Given** an ETC chain config and any timestamp value, **When** the timestamp-aware
   `forBlock` overload is invoked, **Then** it returns a config identical to the block-number-only
   overload — timestamp dispatch is inert on ETC.
3. **Given** every chain whose `network-type` is ETH, **When** the isolation spec runs, **Then**
   ETC-only emission (ECIP-1017 era) and ETC-only fork heights are not configured to activate.
4. **Given** a config change that sets an ETH fork timestamp on an ETC chain, **When** CI runs,
   **Then** the isolation spec FAILS with a message naming the chain and the offending fork.

---

### User Story 6 - One public claim, generated from the gates (Priority: P3)

A prospective operator reads fukuii.com, the README, or the docs and wants to know what is
actually verified, on which networks, by how many tests. Today the README's badge wall implies
per-suite verification it does not perform, `docs/index.md` says "Hive-verified compliance", and
the site renders zeroes. After this change, a single status document is generated from the gate
matrix, so a public claim cannot exceed what a check enforces — and the canonical binary name,
registry, Mantis lineage, and the `fukuii-cli` relationship are each stated in exactly one place.

**Why this priority**: #1403. It is P3 not because it matters least — it is the most externally
visible of the three — but because it is *downstream by construction*: the status document is
generated from the matrix, so it cannot be built before the matrix is true. Doing it earlier would
produce another hand-maintained claim, which is the defect being fixed.

**Independent Test**: Regenerating the status document from the matrix produces no diff; editing
the matrix and not regenerating fails the meta-check; a doc asserting "verified" for a suite the
matrix calls informational fails the claim check.

**Acceptance Scenarios**:

1. **Given** the gate matrix, **When** the status document is generated, **Then** it lists every
   suite with its tier and what it does and does not prove, and committing a stale copy FAILS the
   meta-check.
2. **Given** documentation prose, **When** it claims a suite is "verified", "compliant", or
   "passing", **Then** the matrix MUST declare that suite `required`; otherwise the claim check
   FAILS naming file and line.
3. **Given** the README badge wall, **When** a reader looks at it, **Then** each badge is
   annotated with its tier, and no badge points at a non-existent workflow.
4. **Given** any of the canonical facts (binary name, container registry path, Mantis lineage,
   `fukuii-cli` relationship per #1399), **When** a reader searches the repo, **Then** each is
   stated once in a named location and referenced elsewhere rather than restated.

---

## Requirements *(mandatory)*

### Functional Requirements

**Gate honesty (US1)**

- **FR-001**: `_hive-sim.yml` MUST fail the job when the simulator produced zero test results,
  regardless of `pass_threshold`. Absence of evidence MUST NOT be reported as success.
- **FR-002**: `_hive-sim.yml` MUST accept a `min_tests` input and fail the job when the observed
  total is below it. Callers declared `required` MUST set `min_tests` > 0.
- **FR-003**: `_hive-sim.yml` MUST fail the job when the `hive` binary exited non-zero AND no
  results were produced. A non-zero exit with complete results is reported but not itself fatal,
  because hive's exit code conflates harness and client faults.
- **FR-004**: The enforcement steps MUST run under `if: always()` so a failure earlier in the job
  cannot cause the gate to be skipped and the job to be reported green.
- **FR-005**: Enforcement MUST be a single terminal step whose failure message names the suite,
  the reason, and the counts, so the cause is legible from the check page without opening logs.

**Declared matrix (US2)**

- **FR-006**: A committed, machine-readable gate matrix MUST declare every CI check with:
  `tier` (`required` | `informational` | `quarantined`), `owner`, `covers`, and the workflow it
  maps to.
- **FR-007**: Every workflow invoking `_hive-sim.yml` MUST have a matrix entry; an undeclared
  caller MUST fail the meta-check.
- **FR-008**: A matrix entry with `tier: required` MUST have at least one enforcing mechanism
  configured on its workflow (`pass_threshold` > 0, non-empty `gate_pattern`, or `min_tests` > 0);
  a required-but-unfailable check MUST fail the meta-check.
- **FR-009**: Every entry not `required` MUST carry `promote_by` (a date) and `issue`, so the
  informational tier is a staging area with a deadline and not a permanent parking lot.
- **FR-010**: The matrix MUST declare the first required slice per #1402: geth↔fukuii sync in
  **both** directions, invalid-payload rejection, and ETC-fork `consume-rlp`. Suites not yet able
  to pass MUST be declared `informational` with a `promote_by` date — declared honestly, never
  declared required while excluded.

**Waiver discipline (US3)**

- **FR-011**: Every waiver MUST carry `owner`, `issue`, `reason`, and `expires` (ISO date). A
  waiver missing any field MUST fail the meta-check.
- **FR-012**: The meta-check MUST FAIL when any waiver's `expires` is in the past.
- **FR-013**: The meta-check MUST WARN (without failing) when a waiver expires within 14 days.
- **FR-014**: Every alternation branch of every `gate_exclude` pattern in every workflow MUST
  correspond to a declared waiver; an undeclared exclusion MUST fail the meta-check.
- **FR-015**: Waiver expiry MUST be extendable only by editing the matrix in a reviewed PR — there
  MUST be no environment variable, label, or commit-message escape that suppresses the check.

**Constitution enforcement (US4)**

- **FR-016**: An enforcement map MUST label every constitution principle and numbered rule as
  `enforced` (naming the check) or `aspirational` (naming the substituting review step).
- **FR-017**: Every `enforced` label MUST name a check that exists in the gate matrix.
- **FR-018**: The meta-check MUST FAIL when a constitution principle has no enforcement-map entry,
  so amending the constitution forces the enforcement question to be answered in the same PR.
- **FR-019**: The versioning scheme MUST be stated once. `version.sbt` is the single source of
  truth; the meta-check MUST FAIL if a second independent version scheme is introduced.
- **FR-020**: Outstanding scalafmt/scalafix ratchets MUST be either enforced in CI or recorded in
  the enforcement map as explicitly deferred with an owner and a date — not left ambiguous.

**Chain isolation (US5)**

- **FR-021**: An executable spec MUST assert, for every ETC-typed chain config: no ETH fork
  timestamps are set, and `terminalTotalDifficulty` is absent.
- **FR-022**: The spec MUST assert that for ETC configs the timestamp-aware `forBlock` overload
  returns a result identical to the block-number-only overload for a range of timestamps spanning
  the ETH fork schedule.
- **FR-023**: The spec MUST assert, for every ETH-typed chain config, that ETC-only emission and
  fork parameters are not configured to activate.
- **FR-024**: The spec MUST be in the Tier-1 essential test set so it gates every PR, and MUST
  iterate all configs in `blockchains.conf` rather than a hardcoded subset, so a new chain is
  covered on the day it is added.

**Public truth (US6)**

- **FR-025**: A status document MUST be generated from the gate matrix; a stale committed copy
  MUST fail the meta-check.
- **FR-026**: A claim check MUST scan documentation for verification language ("verified",
  "compliant", "passing") applied to a suite the matrix does not declare `required`, and FAIL
  naming file and line.
- **FR-027**: Every README badge MUST point at an existing workflow file and MUST be annotated
  with its matrix tier; a badge for a missing workflow MUST fail the meta-check.
- **FR-028**: The canonical binary name, container registry path, Mantis lineage statement, and
  the `fukuii-cli` relationship (pointing at #1399 as a sister project, not this release train)
  MUST each be stated in exactly one canonical location.

**Meta (all stories)**

- **FR-029**: The meta-check itself MUST be a required status check. A gate system whose own
  consistency check is optional provides no guarantee.
- **FR-030**: The meta-check MUST run offline — no network, no Docker, no `sbt` — so it completes
  in seconds and can be required without lengthening the merge path.

### Key Entities

- **Gate**: a named CI check. Attributes: `id`, `workflow`, `tier`, `owner`, `covers`,
  `min_tests`, `promote_by`, `issue`.
- **Waiver**: a scoped, dated suppression of one failing test within a gated subset. Attributes:
  `id`, `gate`, `pattern`, `owner`, `issue`, `reason`, `expires`.
- **Enforcement mapping**: a constitution principle or rule bound to either a Gate id or an
  explicit aspirational note.
- **Tier**: `required` (must be green to merge), `informational` (observed, reported, not
  blocking, carries a promotion deadline), `quarantined` (known-broken, tracked, excluded from
  claims entirely).

## Success Criteria *(mandatory)*

- **SC-001**: A Hive job in which the simulator produces zero results FAILS. Verified by forcing
  the condition and observing a red job.
- **SC-002**: Zero workflows invoking `_hive-sim.yml` lack a gate matrix entry.
- **SC-003**: Zero checks declared `required` are structurally incapable of failing.
- **SC-004**: Zero waivers exist without owner, issue, reason, and expiry; zero expired waivers
  exist on `main`.
- **SC-005**: 100% of constitution principles carry an enforcement label.
- **SC-006**: The chain isolation spec passes against all shipped chain configs and fails against
  a deliberately mutated one (both directions demonstrated).
- **SC-007**: Zero documentation claims of verification exist for suites not declared `required`.
- **SC-008**: Zero README badges point at non-existent workflows.
- **SC-009**: The meta-check completes in under 30 seconds with no network access.
- **SC-010**: Re-running the status generator on a clean tree produces no diff.

## Out of Scope

- **Fixing the underlying Hive failures.** Making `consume-rlp` pass, or making fukuii serve sync
  to geth correctly, is separate engineering tracked by its own issues. This spec makes those
  failures *visible and dated*; it does not repair them. Declaring them honestly as
  `informational` with a promotion deadline is the correct output of this work, and resisting the
  temptation to declare them `required` before they pass is the point.
- **Changing branch-protection settings.** Those live in GitHub, not in the repo. This spec
  produces the declared matrix and the documented `gh api` invocation; a maintainer with admin
  rights applies it. The matrix is the source of truth either way, and the meta-check verifies
  the repo side.
- **The fukuii.com site itself.** It is not in this repository. This spec produces the generated
  status document the site should consume, and fixes the in-repo claims.
- **Any consensus behavior change.** US5 adds assertions over existing configuration. If an
  assertion fails on a shipped config, that is a finding to route through `forge`/`beacon` — not
  something this spec's implementation may "fix" by weakening the assertion.

## Assumptions

- The reusable `_hive-sim.yml` remains the single path by which Hive simulators run; the two
  legacy standalone callers (`hive-prague.yml`, `hive-osaka.yml`) are declared in the matrix and
  migrated or quarantined rather than left as a parallel unchecked path.
- ETC chain configs leave `forkTimestamps` unset today (verified at `9ad5f70`); US5 converts that
  observation into an enforced invariant rather than introducing a new requirement.
- Python 3 with PyYAML is available on CI runners (verified: 3.x / PyYAML 6.0.1), so the
  meta-check needs no new toolchain.
