# Feature Specification: Scala 3.9 LTS Toolchain Migration

**Feature Branch**: `020-scala-3-9-lts-migration` (stub written on `claude/fukuii-database-consolidation-h3bqt9`)

**Created**: 2026-10-17

**Status**: Active (2026-10-30). PR 3 landed (Scala 3.9.0, sbt 1.13.0, `-source:3.6` with DC-3 sites applied); PR 5's 187-file rewrite is regenerated after spec 022 lands, then PR 6–7.
(`docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md`, recommendation R-1). To be fleshed out with `/speckit-specify` and
`/speckit-clarify` once the inventory spike (GT-1) has reported, then `/speckit-plan` and `/speckit-tasks`. The Gates and
Design constraints sections are binding on that later work.

**In flight**: PR 5 regeneration waits on spec 022 slices 4–6

**Sequence**: spec 016 (SNAP split) → **this spec** → spec 017 (`StateAccess`, witnesses) → spec 018 (storage) → spec
019 (zkVM guest). The spec number records creation order; the sequence is the user's decision of 2026-10-17: the
toolchain moves **after the last spec 016 slice merges and before any spec 017 code lands**, so that 017, 018 and 019
are written once against one compiler, and spec 019's TASTy-reading translator (its Approach B, OQ-1d) targets 3.9's
TASTy from the start.

**Depends on**: spec 016 complete (its remaining slices are byte-moves verified by `scripts/snap-split/verify.py` and a
compiler change must not straddle them). Nothing else.

**Input**: User request, 2026-10-17: "we should also audit our current dependency chain to ensure we are using the
latest LTS versions of scala, sbt, and jvm", then "stub the scala 3.9 migration as spec 020". The audit found JDK 25
current (the LTS), sbt 1.10.7 five minors behind on the 1.x line, and Scala 3.3.8 the latest patch of a line that
stopped being the recommended LTS on 2026-09-03.

## Clarifications

### Session 2026-10-17 (after the GT-1 inventory, `gt1-inventory.md`)

GT-1 ran on Scala 3.9.0, the only 3.9 patch on Maven Central, on chipprbots-server with fukuii-mordor stopped (user decision).
That host runs JDK 21.0.12, so GT-2 re-measures on JDK 25.

- Q: Which `-source` level does the build use on 3.9 (OQ-2, DC-6)? → A: **`-source:3.9`**, plus a **scalafix guard** that keeps
  `import x.*` enforced. User decision, 2026-10-17.
  - On 3.9, `-source:future` also switches on the new control syntax and makes every remaining `implicit` an error. That is about
    1,541 sites after a 63-file syntax rewrite, which would turn the toolchain move into a full given/using rewrite.
  - No `-source:3.N` level enforces `import x.*`, so the guard carries that property. The tree has 0 `import x._` and 0 import
    renames today.
  - The future-deprecation surfacing that `future` gave is not carried over. `scala3-style.md` records the mapping (DC-6).
- Q: Does staging's formatting get fixed inside 020? → A: **No.** It is a separate PR that lands before any 020 PR (user decision).
  That PR is #1625: scalafmt 3.8.3 over bytes, crypto, rlp, scalanet and scalanetDiscovery, and the last two added to
  `formatAll`/`formatCheck`. Root's `scalafixAll --check` lint debt (about 6,500 lines) is pre-existing and out of scope.
- Q: `-Ykind-projector` (OQ-1)? → A: it is deprecated on 3.9 (`use -Xkind-projector`). The tree has **0** kind-projector sites
  and compiles without it, so the compiler step drops the flag.
- Q: Does `-Wconf:id=E198:error` survive (OQ-3)? → A: yes. E198 is still `UnusedSymbolID` on 3.9. 3.9 finds 13 new unused
  imports, which the 3.4 rewrite deletes.
- Q: Cross-build during the migration (OQ-7)? → A: no; replace the pin. GT-1 found **five** published modules, not three:
  bytes, crypto, rlp, **scalanet** and **scalanetDiscovery**. DC-7 applies to all five.
- Q: OQ-4 and OQ-5? → A: both are moot. Spec 016 completed with R1 (#1622) on 2026-10-17, so GT-0 holds.

### Session 2026-10-17 (plan): user decisions on the `/speckit-plan` open items

- Q: scalafmt for 020 (plan R-1, F-1)? → A: **stay on 3.8.3 for all of 020.** 3.11.x is follow-up **FU-1**, before spec
  017's first PR. FU-1 tries a config-only fix first; otherwise each hand-edited file must be proved whitespace-only.
  G-3, FR-003 and SC-005 are amended accordingly.
- Q: how is SC-005 read, given that `formatAll`/`formatCheck` run scalafix, which is red on staging (F-2)? → A:
  `scalafmtCheckAll` green on every module, using the per-module form, and scalafix with **no new findings** outside
  `bytes` and `rlp` (which stay green).
- Q: sbt 1.12.15 or 1.13.0 (plan R-3)? → A: **1.13.0**.
- Q: one constitution amendment for 018, 019 and 020, or separate ones (OQ-6)? → A: **separate.** 020 lands
  constitution 1.1.1 and ADR INF-009 now.
- Q: the 2 new E029 sites and the 2 `trait App` sites (plan R-6)? → A: **defer** with dated `@nowarn`, recorded in
  `dc4-ledger.md` and CHASE-QUEUE.

Facts from GT-1 that the plan must take as given:
- **The rewrite chain runs on the 3.9 compiler.** 3.3.8 rejects `-source:3.4-migration`. The first compiler PR is therefore
  "3.9.0 + `-source:3.4-migration -rewrite`" (DC-2 step 1).
  - That PR also bumps sbt-scapegoat from 3.3.6 to 3.4.1, because 3.3.6 has no `_3.9.0` artifact and dependency
    resolution fails before any compile.
- **Eight hand fixes are needed before the chain compiles.** Each is its own commit, named for the construct:
  - 5 × E172: an `Ordering` passed positionally to a context bound. Two of these are in SNAP main code.
  - 3 × E171: `McpService` test arguments.
- **Given prioritisation shows up at two steps, and DC-3's review covers both.**
  - At 3.6, E205 warns at 371 sites, 364 of them RLP encoder/decoder choices.
  - At 3.7, `using` is inserted at 514 sites in 187 files.
- **scalafmt beyond 3.8.3 fails on one test construct.** 3.8.3 parses the migrated tree. Every scalafmt release from 3.8.4 to
  3.11.5 fails to parse `taggedAs (\n…\n) in new Fixture:`, which occurs in up to 34 test files. The tooling step either fixes
  that construct by hand first or stays on 3.8.3; the plan decides.
- **sbt-scalafix moves from 0.13.0 to 0.14.9.** 0.13.0 cannot load its rules against 3.9 sources.
- **Warning reference for DC-4, under `-source:3.9`:**

  | Point | Warnings |
  |---|---|
  | 3.3.8 today | 365 |
  | After the 3.4 step | 605 |
  | After the full 3.4→3.9 chain, ratchets at error | 91 |

## Problem

Scala 3.9.0 LTS (2026-09-03) opened the new Long Term Support line, with at least three years of maintenance. Scala
3.3 LTS, which fukuii pins at 3.3.8, "remains actively maintained for one year after Scala 3.9.0" and "future backports
are expected to be fewer". Staying on 3.3 is safe until about 2027-09 and then is not; every month after 3.9.0, the gap
in compiler fixes, tooling support (scalafmt, scalafix, Metals, sbt 2) and library builds widens.

The move is not a patch bump. Between 3.3 and 3.9 the language changed in ways the compiler rewrites for you
(`-source:3.4-migration` through `-source:3.9-migration`, each with `-rewrite`, one step at a time) and in ways it does
not: since 3.7 given search picks the **most general** matching instance, which intersects fukuii's own half-finished
`given`/`using` migration (`.claude/agent-protocols/scala3-given-migration.md`, P3a done, P3b pending); implicits from
inaccessible companions warn in 3.9 and stop resolving in 3.10; artifacts built with 3.9 cannot be consumed by 3.3
projects, which matters for the published `bytes`, `crypto` and `rlp` modules. Several flags the build relies on
(`-source:future`, `-Ykind-projector`, `-Wconf:id=E198:error`, `-Xmax-inlines:64`) must be re-verified against the
new compiler, and the analysis toolchain (sbt-scalafix 0.13.0, sbt-scoverage 2.2.2) must move with it. scalafmt 3.8.3 parses
the migrated tree (GT-1), but not syntax introduced after 3.3 that 020 does not write, such as the 3.6 `given` syntax.

Done late, the migration is paid three times: once for the code that exists, once more for everything 017 and 018 add
in the meantime, and a third time for spec 019's translator, which is tied to the TASTy version it was built against.
Done now, between 016 and 017, it is paid once.

## Goals

- G-1 fukuii compiles, formats, lints and tests on the latest Scala 3.9.x patch, under `-source:3.9`, with `import x.*`
  enforced by the import guard (Clarifications), zero compile errors and no regression of the warning ratchet.
- G-2 sbt on the latest 1.x line (1.13.0; 1.12.15 at audit time) and the plugin set current on that line, including the
  organisation move of sbt-javaagent to `com.github.sbt`.
- G-3 scalafmt stays 3.8.3 (3.11.x is follow-up FU-1, before spec 017); sbt-scalafix 0.14.x and sbt-scoverage 2.4.x;
  `scalafmtCheckAll` green on every module and scalafix with no new findings (SC-005).
- G-4 The pending P3b `given`/`using` migration completed as part of this spec, so the 3.7 prioritisation change is
  reviewed once.
- G-5 Consensus outputs byte-for-byte unchanged on both chains, and import performance within the constitution's ~10%
  budget, demonstrated against a pre-migration baseline.
- G-6 The constitution, CLAUDE.md, the agent protocols and the audit updated to name the new toolchain; published
  modules bumped per `.github/VERSIONING.md`.

## Non-goals

- **NG-1 sbt 2.** A separate migration (audit R-3), blocked today on sbt-kanela-runner and sbt-scapegoat having no sbt 2
  build and sbt-git's being an RC. This spec stays on sbt 1.x.
- **NG-2 Scala Next (3.10+).** The target is the LTS line only.
- **NG-3 Library major upgrades.** The audit's major-version rows (netty 4.2, jline 4, prometheus 1.10, kanela 2,
  logstash 9, rocksdbjni 11) are separate decisions. Only bumps the compiler move requires ride along.
- **NG-4 Language modernisation beyond the rewrites.** No opaque-type retrofit, no enum sweep, no new `inline`; those are
  `mithril`'s on-demand work under `scala3-style.md`. The compiler's own `-rewrite` output is the only source change
  this spec makes to code, plus the P3b `given` work (G-4) and whatever a rewrite cannot fix by hand.
- **NG-5 Behaviour change of any kind.** This is a toolchain move. A test that changes expectation is a stop.
- **NG-6 JDK change.** JDK 25 is the current LTS and stays; 3.9 needs 17+.

## Gates *(mandatory; nothing past them proceeds until they pass)*

**GT-0 — Branch point.** Spec 016's last slice is merged and no spec 017 implementation PR is open. The migration
branch is cut from `staging` at that point. A spec 016 hotfix that lands during the migration is rebased onto the
migrated tree by its author, not hand-merged (the 016 FR-037 rule, applied in reverse).

**GT-1 — Inventory spike (throwaway branch, before planning).** Bump `scala-3` to the latest 3.9.x with no other change
and run `sbt compile-all`. Record: the error count and the warning count by category (the warning ratchet's step 1,
`warning-ratchet.md`); every flag the compiler rejects or deprecates; whether `-Ykind-projector` and `-source:future`
are accepted; whether scalafmt 3.8.3 fails on any file and which scalafmt 3.11.x version parses the tree; whether
sbt-scalafix 0.14.x's semantic rules run. Then repeat with `-source:3.4-migration -rewrite` applied alone, to measure
how much of the inventory the first rewrite step clears. The report sizes the plan. Days, not weeks; off the node host.

**GT-2 — Baseline.** Before any migration PR merges: `testEssential` count and result (last known 3,621 / 0),
`testStandard` coverage figure, ethereum/tests on both chains, hive rpc-compat and graphql (counts re-measured at GT-2; rpc-compat is 273/273 since #1616), and the
`[IMPORT-TIMING]` baseline on a fixed Mordor range and a fixed Platåberget range, all recorded in `research.md`. Every
migration PR reports the same figures.

**GT-3 — Constitution PATCH.** The constitution names scalafmt 3.8.3 in principle IV and "sbt 1.10.7+" in the technology
constraints. Before the toolchain PRs merge, `/speckit-constitution` bumps those strings (PATCH; no principle changes)
and ADR **INF-009** records the LTS adoption and the sequencing decision. Coordinated with spec 018's GT-2 and spec
019's CG-1 so the technology-constraints section is edited once per release train, not three times.

## Design constraints *(mandatory)*

**DC-1 — Build tool first, compiler second, tooling third, libraries last.** The order the 3.9 announcement gives, and
the order that keeps each PR attributable: (1) `sbt.version` 1.12.x and the sbt-1 plugin bumps, `sbt pp` green, no
source change; (2) the compiler, in rewrite steps; (3) scalafmt / scalafix / scoverage, `formatAll` green; (4) only
the library bumps the compiler forced. Dependencies are never bumped first.

**DC-2 — One `-source` step per commit, in order, never skipped.** `-source:3.4-migration -rewrite`, then 3.5, 3.6, 3.7,
3.8, 3.9, each as its own commit (or PR where the diff is large), each compiling and passing `testEssential` before the
next. Later flags do not replay earlier patches, so a skipped step is a silent miss. The rewrites' output is reviewed
but not hand-edited inside the same commit; hand fixes for what the rewrites cannot do are their own commits, named for
the construct they fix.

**DC-3 — Given prioritisation is reviewed, not trusted.** At the 3.7 step, every site `-source:3.7-migration` warns on is
listed in the PR, with the instance the old rules chose and the instance the new rules choose. Where they differ, the
code is made explicit (a named `given` or an explicit argument) so the choice no longer depends on the rule. P3b of the
repo's `given` migration (`scala3-given-migration.md`, G1 to G3 pitfalls) is completed in the same step, so the
prioritisation change and the `implicit` to `given` conversion are reviewed together, once.

**DC-4 — The warning ratchet only tightens.** `-Wconf:id=E198:error` and `-Wconf:cat=unchecked:error` stay errors. New
warning categories 3.9 introduces (the inaccessible-companion deprecation was expected first among them; GT-1 found 0 at 3.9.0) enter the ratchet at step 2
(risk-stratified commit, `risk-stratified-commit.md`) and are promoted to errors before this spec closes, or deferred
with a dated `@nowarn` and a CHASE-QUEUE entry. The total warning count after the migration is at or below the GT-1
inventory's post-3.4 count, and the per-category table is in the final PR.

**DC-5 — Consensus and performance are byte-identical and within budget.** A compiler upgrade can change code
generation and inlining; it must not change results. ethereum/tests on both chains, the rpc-compat and graphql
inertness oracles (spec 009's rule: they must not move in either direction), and a two-tree import differential on the
GT-2 ranges (state roots and receipt roots equal, 3.3 tree vs 3.9 tree) gate the compiler step. `[IMPORT-TIMING]` on
the same ranges stays within the constitution's ~10% budget; a regression is investigated (the 3.9 standard library is
"optimized" per the announcement, so an improvement is the expectation, and a regression is a signal). `forge` signs
off ETC and `beacon` ETH on the differential, as a formality that costs a run and buys a record.

**DC-6 — `-source:future` semantics are preserved.** The build relies on `-source:future` to require `import x.*` and
to surface future-deprecations early. If 3.9 changes what `future` means or rejects it, the plan chooses the flag that
keeps those two properties and documents the mapping in `scala3-style.md`; it does not drop the enforcement.

**DC-7 — Published modules move together.** `bytes`, `crypto`, `rlp`, `scalanet` and `scalanetDiscovery` are published (`publishSettings`). A 3.9
artifact cannot be consumed by a 3.3 project, so all modules switch in the same release, the release notes flag it as a
breaking change for downstream consumers of those artifacts, and the version bump follows `.github/VERSIONING.md`
(MINOR at least).

**DC-8 — No behaviour change rides along.** A rewrite that changes a test's expectation, a log line, a config default
or an RPC response is a stop, not a fix-up. The migration PRs contain only: the build and tooling bumps, the compiler's
rewrite output, hand fixes for compile errors the rewrites cannot clear, P3b `given` conversions, and `@nowarn`
deferrals with dated reasons.

**DC-9 — The agents' protocols move with the compiler.** `scala3-style.md` (S1 to S11 ratchets), `scala3-given-migration.md`,
`warning-ratchet.md` and CLAUDE.md's build table are updated in the final PR so that the greps and commands they name
are true on 3.9. `wraith` owns compile fallout; `mithril` reviews the P3b conversions; `eye` validates every step;
`prism` reviews nothing here (no behaviour to review) except the final protocol edits.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — A maintainer builds and tests on 3.9 with no behaviour change (Priority: P1)

A maintainer checks out `staging` after the migration, runs `sbt pp`, and gets a green build with the same test counts,
the same coverage and the same ethereum/tests results as before, on the latest Scala 3.9 patch.

**Why this priority**: it is the whole spec.

**Independent Test**: `sbt pp`; `testEssential` 3,621 / 0 (or the GT-2 count); `testStandard` coverage at or above the
GT-2 figure; ethereum/tests green on both chains; the two-tree import differential equal.

**Acceptance Scenarios**:

1. **Given** the migrated tree, **When** `sbt compile-all` runs, **Then** it reports 0 errors and a warning table at or
   below the DC-4 bound.
2. **Given** the GT-2 ranges, **When** imported on the 3.3 tree and the 3.9 tree, **Then** every state root and
   receipt root is equal and `[IMPORT-TIMING]` is within 10%.

### User Story 2 — An operator notices nothing (Priority: P1)

An operator upgrades a Mordor and a Platåberget node across the release. Sync, mining (ETC), the Engine API (ETH), RPC
and metrics behave as before; the release notes say only that the build moved to Scala 3.9 LTS and that downstream
consumers of the published modules must rebuild.

**Independent Test**: the SNAP `SyncTest` CI job, hive rpc-compat and graphql unchanged, a 24-hour soak on each
validation node.

### User Story 3 — Spec 017 starts on one toolchain (Priority: P1)

The first spec 017 implementation PR is opened against a `staging` that is already on 3.9, with scalafix 0.14 (and
scalafmt 3.11 via FU-1); nothing in 017, 018 or 019 is written against 3.3 and later rewritten.

**Independent Test**: GT-0's ordering holds; spec 019's OQ-1d resolves to "3.9 TASTy".

### User Story 4 — The given migration finishes (Priority: P2)

`mithril` completes P3b under DC-3, and every site the 3.7 prioritisation change touched is explicit.

**Independent Test**: `-source:3.7-migration` emits no prioritisation warnings on the final tree; the
`scala3-given-migration.md` ratchet greps report zero remaining `implicit` definitions in the P3b scope.

### Edge Cases

- A rewrite step produces a diff in a consensus file (`vm/`, `mpt/`, `crypto/`, `domain/`): the commit is reviewed by
  `forge` or `beacon` even though it is mechanical, and the import differential is re-run after that step alone.
- A flag the build uses is removed in 3.9 (`-Ykind-projector` is the suspect): the plan names the replacement or the
  construct rewrite; kind-projector syntax in the tree is inventoried at GT-1.
- scalafmt 3.11 reformats files untouched by the rewrites: the format-only commit is separate and is the last one
  before `formatCheck` is re-enabled as a gate.
- A test depends on a 3.3 compiler behaviour (an implicit resolution, an erasure detail, a `BigInt` `inline` edge): a
  stop under DC-8; the test and the behaviour are analysed before anything changes.
- A spec 016 hotfix lands mid-migration (GT-0).
- Metals, IntelliJ and the CI cache images need the new compiler: documented in the final PR; the CI image is rebuilt
  before the compiler PR merges.

## Requirements *(mandatory)*

### Functional Requirements (stub level; refined by /speckit-specify)

- **FR-001** The build MUST compile on the latest Scala 3.9.x patch with 0 errors under `-source:3.9`, with `import x.*`
  enforced by the import guard (DC-6, Clarifications), through the ordered rewrite steps of DC-2.
- **FR-002** `sbt.version` MUST be on the latest 1.x patch and every sbt-1 plugin on its latest version, including
  sbt-javaagent at `com.github.sbt`; sbt 2 MUST NOT be adopted here (NG-1).
- **FR-003** scalafmt MUST stay on 3.8.3 within this spec (3.11.x is FU-1, before spec 017); sbt-scalafix on 0.14.x;
  sbt-scoverage on 2.4.x; `scalafmtCheckAll` MUST be green on every module, and scalafix MUST report no new findings
  (SC-005).
- **FR-004** The 3.7 given-prioritisation sites MUST be listed and made explicit, and P3b of the `given` migration MUST
  be complete (DC-3).
- **FR-005** The warning ratchet MUST not regress: the final warning count is at or below the GT-1 post-3.4 count, new
  categories are promoted to errors or deferred with dated `@nowarn` and a CHASE-QUEUE entry (DC-4).
- **FR-006** ethereum/tests on both chains, hive rpc-compat and graphql (counts re-measured at GT-2; rpc-compat is 273/273 since #1616), and the two-tree import differential
  MUST pass unchanged; `[IMPORT-TIMING]` MUST stay within 10% of the GT-2 baseline (DC-5).
- **FR-007** No PR in this spec MAY change a test expectation, a log line, a config default or an RPC response (DC-8).
- **FR-008** All modules MUST switch compiler in one release; the published modules' version MUST be bumped per
  VERSIONING and the release notes MUST flag the TASTy incompatibility for downstream consumers (DC-7).
- **FR-009** The constitution's scalafmt and sbt strings MUST be updated (GT-3, PATCH) with ADR INF-009; CLAUDE.md's build
  table, `scala3-style.md`, `scala3-given-migration.md` and `warning-ratchet.md` MUST be true on the new toolchain
  (DC-9); the audit report MUST be updated to show the new pins.
- **FR-010** The migration MUST start after spec 016's last slice merges and MUST finish before any spec 017
  implementation PR is opened (GT-0).

### Key Entities

- **Rewrite step**: one `-source:3.N-migration -rewrite` application, its commit, and its compile/test record.
- **Inventory**: the GT-1 table of errors, warnings by category and rejected flags on the unmodified tree at 3.9.
- **Baseline**: the GT-2 figures the migration must hold.
- **Prioritisation site**: a `given`/`implicit` resolution the 3.7 rule change would alter, made explicit under DC-3.

## Success Criteria *(mandatory)*

- **SC-001** `sbt compile-all` on the latest 3.9.x: 0 errors; warning count at or below the GT-1 post-3.4 figure, with
  the per-category table published.
- **SC-002** `testEssential` at the GT-2 count with 0 failures; `testStandard` coverage at or above the GT-2 figure.
- **SC-003** ethereum/tests green on both chains; hive rpc-compat at the GT-2 count (273/273 since #1616) and graphql at
  the GT-2 count, unchanged in either direction.
- **SC-004** The two-tree import differential on the GT-2 Mordor and Platåberget ranges: every state root and receipt
  root equal; `[IMPORT-TIMING]` p50 and p99 within 10% of baseline.
- **SC-005** On scalafmt 3.8.3 and sbt-scalafix 0.14.x: `scalafmtCheckAll` green on every module (per-module form),
  `scalafixAll --check` green on `bytes` and `rlp`, and no new scalafix findings elsewhere against the GT-2 count.
- **SC-006** `-source:3.7-migration` emits zero prioritisation warnings on the final tree, and the P3b ratchet greps
  report zero remaining targets.
- **SC-007** The first spec 017 implementation PR is opened against a 3.9 `staging` (GT-0 held).
- **SC-008** Constitution PATCH merged, INF-009 accepted, CLAUDE.md and the three agent protocols updated, the audit's
  §1 table shows the new pins, and the release notes carry the downstream-consumer flag.

## Assumptions

- The latest 3.9.x patch at execution time is taken, not 3.9.0 specifically; 3.3.9 (expected soon after 3.9.0) is not a
  target.
- JDK 25 stays; the CI runner images and the Docker builder images need only the sbt launcher and compiler fetch, no
  JDK change.
- The audit's version figures (sbt 1.12.15, scalafmt 3.11.5, sbt-scalafix 0.14.9, sbt-scoverage 2.4.4, sbt plugins)
  are re-read from Maven Central at GT-1; newer patches supersede them.
- `wraith` owns compile fallout, `mithril` the P3b conversions, `eye` validation, `forge` and `beacon` the differential
  sign-off; `prism` reviews the protocol edits only.
- The 3.9 announcement's facts (LTS window, migration order, given prioritisation, TASTy incompatibility, JDK 17+) were
  read on 2026-10-17 and are re-verified at `/speckit-plan`.

## Open Questions

- **OQ-1** *Resolved 2026-10-17, see Clarifications.* Does 3.9 accept `-Ykind-projector`, or has it moved or been removed? How many kind-projector sites exist
  (`-Ykind-projector` was added for "complex boopickle/circe derivations")? Answered by GT-1.
- **OQ-2** *Resolved 2026-10-17: `-source:3.9` + scalafix import guard.* Does `-source:future` on 3.9 still enforce `import x.*` and the same future-deprecations, or does it now mean
  something broader that introduces new errors? Answered by GT-1.
- **OQ-3** *Resolved 2026-10-17: id kept.* Does `-Wconf:id=E198` keep its id across minors, or does the ratchet need a category-based filter instead?
- **OQ-4** *Moot: 016 complete.* May the sbt and plugin bumps (DC-1 step 1, audit R-2) land before spec 016 completes, as a build-only PR
  with no source change? Recommended: yes, with the user's approval, since it removes one variable from GT-1.
- **OQ-5** *Moot: 016 complete (R1 #1622).* Which spec 016 slices remain at GT-0, and is there a natural pause (after R1 renames, T080/T090) to cut
  from?
- **OQ-6** *Resolved 2026-10-17: separate amendments; 020 lands 1.1.1 + INF-009 now.* Whether ADR INF-009 and spec 018's INF-007 and spec 019's CG-1 amend the constitution's technology section
  in one PR or three (GT-3).
- **OQ-7** *Resolved 2026-10-17: replace the pin; five published modules.* Whether the CI matrix should build on both 3.3.8 and 3.9.x during the migration (a cross-build the
  constitution forbids, so no) or the migration branch simply replaces the pin (recommended).

## Related specs

- **Spec 016** (SNAP split): must complete first (GT-0).
- **Spec 017** (`StateAccess`, witnesses): must not start before this spec finishes (GT-0, SC-007).
- **Spec 018** (storage architecture): its plan's Technical Context names this migration; its bake-off harness targets
  the compiler this spec lands.
- **Spec 019** (zkVM guest): its Approach B translator reads TASTy; OQ-1d there resolves to 3.9's TASTy once this
  spec lands.
- **Audit**: `docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md`, R-1 (this spec), R-2 (DC-1 step 1), R-3 (sbt 2, NG-1).

## Known tasks (seed for /speckit-tasks)

- **T-000 (GT-1)** Inventory spike on a throwaway branch: bump to the latest 3.9.x, `compile-all`, record the table;
  repeat with `-source:3.4-migration -rewrite`.
- **T-001 (DC-1 step 1, audit R-2)** `sbt.version` 1.12.x and the sbt-1 plugin bumps including sbt-javaagent's move;
  `sbt pp` green; no source change. May land early under OQ-4.
- **T-002 (GT-2)** Baselines recorded in `research.md`: test counts, coverage, ethereum/tests, rpc-compat, graphql,
  `[IMPORT-TIMING]` on the fixed ranges.
- **T-003 (GT-3)** Constitution PATCH and ADR INF-009.
- **T-004 (DC-2)** Six rewrite steps, 3.4 through 3.9, one commit or PR each, `testEssential` after each.
- **T-005 (DC-3)** The 3.7 prioritisation site list and P3b completion (`mithril`).
- **T-006 (DC-1 step 3)** scalafmt 3.11.x, sbt-scalafix 0.14.x, sbt-scoverage 2.4.x; the format-only commit;
  `formatCheck` re-enabled.
- **T-007 (DC-5)** The two-tree import differential and `[IMPORT-TIMING]` comparison; `forge` and `beacon` sign-off.
- **T-008 (DC-4)** Warning ratchet pass: promote or defer every new category; final per-category table.
- **T-009 (DC-9, FR-009)** CLAUDE.md, `scala3-style.md`, `scala3-given-migration.md`, `warning-ratchet.md`, the audit
  report, release notes.
