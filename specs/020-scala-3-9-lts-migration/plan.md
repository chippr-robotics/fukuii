# Implementation Plan: Scala 3.9 LTS Toolchain Migration

**Branch**: `docs/020-plan` (plan); implementation PRs are cut from `staging` at GT-0 | **Date**: 2026-10-17 |
**Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/020-scala-3-9-lts-migration/spec.md`, its Clarifications (Session
2026-10-17, binding) and the GT-1 evidence in [gt1-inventory.md](./gt1-inventory.md).

## Summary

Move fukuii from Scala 3.3.8 to **Scala 3.9.0 LTS** and from sbt 1.10.7 to **sbt 1.13.0**, with no behaviour change.
The order is build tool first, then the compiler in the `-source:3.4-migration` … `3.9-migration` rewrite steps, then
tooling, then libraries (none are forced). GT-1 sized the work:

- The rewrite chain must run on the 3.9 compiler.
- 8 hand fixes are needed first.
- Only two steps change source: 3.4 (16 files) and 3.7 (514 `using` insertions in 187 files).
- Given prioritisation surfaces at 371 sites. 364 of them come from one duplicated-`given` pattern in
  `RLPImplicits`, which is fixed at its 9 definitions (research R-5).
- The final tree has 91 warnings against a DC-4 ceiling of 605.

The build uses `-source:3.9`. A grep plus scalafix guard keeps `import x.*` enforced (R-2). **scalafmt stays on 3.8.3**
for this spec, because DC-8 rules out hand-editing up to 96 test files to satisfy a formatter. The 3.11 move becomes a
follow-up (FU-1) before spec 017. The user accepted this on 2026-10-17 and G-3, FR-003 and SC-005 are amended (research F-1).

Consensus safety comes from an import differential: the canonical headers' state and receipt roots on fixed Mordor
and Ephemery-164 ranges (Ephemery replaces Platåberget, user decision 2026-10-17), with 3.3 and 3.9 jars on the same frozen datadir. The same runs give the `[IMPORT-TIMING]`
p50/p99 against the 10% budget (R-4).

## Technical Context

**Language/Version**: Scala 3.3.8 → **3.9.0** (the latest 3.9.x at PR 3); JDK 25 unchanged (CI and Docker); the
measurement host is on JDK 25 since 2026-10-19 (relative comparisons only). sbt 1.10.7 → **1.13.0** (meta-build stays Scala 2.12).

**Primary Dependencies**: no library bump forced (GT-1). Build plugins per research R-3. Compiler plugin: scapegoat
3.3.6 → 3.4.1 (with the compiler).

**Storage**: N/A. No schema or on-disk change. The datadir is used read-write only on copies, for the differential.

**Testing**: ScalaTest tiers `testEssential` / `testStandard` (coverage) / ethereum/tests; hive rpc-compat and
graphql as inertness oracles; SNAP `SyncTest`; the two-tree import differential (R-4).

**Target Platform**: JVM (Linux x86_64 and aarch64 images); every network (ETC/Mordor, ETH/Sepolia, Ephemery, Platåberget).

**Project Type**: a multi-module sbt build. Root node plus `bytes`, `crypto`, `rlp`, `scalanet`, `scalanetDiscovery`
(published) and `Evm`, `IntegrationTest`, `RpcTest`, `Benchmark` (configs).

**Performance Goals**: `[IMPORT-TIMING]` p50 and p99 for 3.9 ≤ 1.10 × 3.3 on each fixed range (SC-004). An
improvement is expected (3.8 lazy-val codegen, 3.9 stdlib).

**Constraints**: byte-identical consensus (constitution I); no behaviour change (DC-8); one `-source` step per commit
(DC-2); the warning ratchet only tightens (DC-4); the host never runs sbt beside a live node, and the differential never runs beside a live
node or an sbt build.

**Scale/Scope**: 7 PRs plus 1 docs-only PR before them. Source diff: 8 hand fixes, 16 files at 3.4, 187 at 3.7,
about 2 definition-site files for DC-3, P3b (≤ 95 `implicit class` sites, scope per S4), and about 10 ratchet sites.
About 40 minutes of clean compile per full chain on the 4-core host (GT-1 §7).

All Technical Context items are resolved; no NEEDS CLARIFICATION remains. User decisions still open are listed under
**Decisions for the user**. They change spec text, not this plan's structure.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-design | Post-design |
|---|---|---|
| **I. Consensus determinism** | Engaged: compiler codegen changes; rewrites touch `vm/OpCode.scala` (3.4) and 58 consensus files (3.7); DC-3 edits `rlp/RLPImplicits`. | PASS by design. Differential after PR 3, PR 4 and PR 5, and a full one in PR 6 (R-4). forge reviews ETC paths and beacon ETH paths; both review `rlp/`. ETC/ETH dispatch is untouched. |
| **II. Spec-driven** | PASS. Spec, clarifications and GT-1 precede this plan. | PASS. Spec amendments F-1 to F-3 go back through `/speckit-clarify` or a spec edit, not around it. |
| **III. Tests and coverage** | Engaged: no test expectation may change (DC-8); coverage ≥ GT-2 and ≥ 70%. | PASS. Every PR reports the §B rows. The `-98.toByte` → `(-98).toByte` rewrite in two specs is value-identical and is called out. No `Thread.sleep` added. |
| **IV. Formatted Scala 3** | Tension: the spec wants scalafmt 3.11.x; the constitution names 3.8.3. | PASS. Staying on 3.8.3 (R-1) matches the constitution. `scalafmtCheckAll` stays green on every PR. scalafix rules unchanged plus the R-2 regex. |
| **V. Quality gates** | Tension: `formatCheck` is red on staging (scalafix debt, GT-1 §5.2). | PASS with a declared gap. CI's `scalafmtCheckAll`, `compile-all` and Tier 1 stay green. scalafix is measured as "no new findings" (F-2, user to confirm). The new guard is a step inside an existing job, so no new `gates.yml` context. |
| **VI. Security** | sbt server advisories. | PASS. sbt 1.13.0 patches both Tcp-server RCEs. The build does not set `serverConnectionType`. No crypto logic change. The `crypto/` `App` deprecation is deferred, not rewritten. |
| **VII. Versioning and ADRs** | Engaged: TASTy break for five modules. | PASS. MINOR bump to 0.11.0 (R-7; 0.10.0 shipped on 3.3.8, user decision 2026-10-20), breaking-change note, ADR INF-009, constitution PATCH 1.1.1 (R-8). |

**Declared gaps (recorded, not waived)**: (1) SC-005 cannot be met as written (F-2). (2) The `[IMPORT-TIMING]`
comparison runs on JDK 21, not the release JDK 25. The comparison is relative and same-host, which is valid for a
compiler-only delta. (3) Tier 2 coverage does not run on staging PRs in CI, so it is a host run (R-4).

## Gates

| Gate | Condition | Evidence |
|---|---|---|
| GT-0 | 016 complete (R1 #1622, 2026-10-17); no 017 implementation PR open; cut from `staging` | holds today (Clarification) |
| GT-1 | inventory | done: `gt1-inventory.md` |
| GT-2 | research §B filled from the GT-2 commit | **before PR 1 merges** |
| GT-3 | constitution 1.1.1 plus INF-009 merged | **PR 0, before PR 1 merges** |
| Per-PR | `compile-all` 0 errors; `testEssential` at the GT-2 count / 0; `scalafmtCheckAll` green; import guard green; rpc-compat and graphql unchanged in both directions; the PR's rows from §B | quickstart |
| Consensus PRs (3, 4, 5, 6) | differential `imported = N, failed = 0` on both ranges; forge and/or beacon sign-off | quickstart Q-7 |

## Phased PR sequence

DC-2 discipline holds throughout. Each `-source` step is its own commit, run on the 3.9 compiler. Rewrite output is
never hand-edited in the same commit. Hand fixes are their own commits, named for the construct. Format-only commits
are separate. Between PRs, staging carries a stable `-source:3.N`, not a `-migration` flag. The R-2 guard keeps the
import property throughout.

| PR | Content | DC | Size (GT-1) | Review | Validation beyond per-PR |
|---|---|---|---|---|---|
| **0** (docs) | GT-2 baseline in `research.md` §B; constitution 1.1.0 → 1.1.1 (sbt string); ADR INF-009 plus index rows | GT-2, GT-3 | docs | user | §B filled |
| **1** | `build.properties` 1.13.0; plugin bumps (R-3); `com.github.sbt` sbt-javaagent; `_hive-sim.yml` sbt download; sbt-scalafix 0.14.9 if it loads on 3.3.8. **No source change** | DC-1 step 1, audit R-2 | ~3 files | eye | `sbt pp`; `bytes/scalafixAll --check; rlp/scalafixAll --check`; scalafix finding counts against §B row 12; `assembly` and `dist` produce jars; coverage row |
| **2** | Import guard: `.scalafix.conf` `DisableSyntax.regex`; `scripts/ci/check_scala_imports.sh` and its self-test; a step in `ci.yml` | DC-6 | ~4 files | prism | Q-2 proofs (grep exit 1 on `import x._`; scalafix error on a scratch file) |
| **3** | (a) 8 hand-fix commits (5 × E172 `Ordering` → `using`, 2 of them SNAP main; 3 × E171 `McpService`), each compiling on 3.3.8 first; (b) `scala-3` 3.9.0, `scapegoatVersion` 3.4.1, sbt-scalafix 0.14.9 (if not in PR 1), drop `-Ykind-projector`, `-source:future` → `-source:3.4-migration`, with `E198` and `unchecked` demoted to `:w` (as GT-1 did; the 13 unused imports 3.9 finds would otherwise be errors before the rewrite deletes them); (c) the 3.4 rewrite output commit (16 files, +19/−33, incl. 13 unused-import deletions); (d) flag → `-source:3.4` and both ratchets restored to `:error`; (e) format-only commit if needed; `ci.yml` names drop "3.3.8" | DC-2 step 3.4, DC-7 | 24 files | wraith (fallout); **forge** (`vm/OpCode.scala` infix/backticks, `rlp/package.scala`); herald glance (`MessageDecoders` import deletions) | differential (both ranges); a one-off `-source:3.9` compile for the record (≤ 605, GT-1 C2) |
| **4** | (a) 3.5 step commit (no diff; record); (b) 3.6 step with `dc3-sites.md` (371 rows); (c) DC-3 definition-site commit: collapse `RLPImplicits` duplicates (after `git log -S`); (d) DC-3 commit: 7 `JsonRpcController` sites made explicit; flag → `-source:3.6`; a one-off `-source:3.7-migration` compile shows 0 E205 | DC-2, DC-3 | ~3 files | **forge + beacon** (`rlp/`); mithril, conduit (jsonrpc) | differential after (c) alone; rpc-compat unchanged |
| **5** | (a) 3.7 rewrite commit (514 `using`, 187 files; word-diff shows only `using`); (b) P3b commits: `implicit class` → `extension`, S4 scope, split per bucket; flag → `-source:3.7`. May split as 5a/5b if the diff is unreviewable | DC-2, DC-3, G-4 | 187 files + P3b | mithril (P3b); forge/beacon (58 consensus files) | differential; S4 grep = 0 |
| **6** | (a) 3.8 and (b) 3.9 step commits (no diff; check the `@unchecked` probe, R-9); (c) flag → **`-source:3.9`**; (d) ratchet commits per R-6 (bucket A fixes, dated `@nowarn` deferrals, promotions `E205`, `E028`, implicit-`using` msg); `dc4-ledger.md` | DC-2, DC-4, DC-5 | ~10 sites | wraith; forge (`vm/PrecompiledContracts`); eye | **full DC-5 run**: differential and `[IMPORT-TIMING]` both ranges, forge and beacon sign-off; ethereum/tests dispatch; SyncTest; Tier 2 coverage |
| **6b** | **Scalafix and scalafmt ratchet (#1656; user decision 2026-10-20: part of 020's implementation).** (a) the 37 test files scalameta cannot parse (35 × `) in new Fixture:`, 2 × `;` expected): first re-measure on the 3.9 tree with PR 1's scalafix (a newer scalameta may parse them); otherwise rewrite those `in new X:` bodies to the braced or `in new X {}` form, format-only, one commit; (b) the automatic `scalafixAll` rewrite (OrganizeImports and friends; 209 files on 3.3.8), one commit, no hand edits; (c) the `DisableSyntax.return` findings (58; 51 in `src/main`), one commit per file group, behaviour-neutral (`if/else`, `boundary`/`break`); consensus files (`ledger/BlockExecution`, `ledger/BranchResolution`, `consensus/eip1559/BaseFeeCalculator`, `vm/*Tracer`) separately; (d) `formatCheck` (every module's `scalafixAll --check` and `scalafmtCheckAll`) becomes a CI step and a required check | DC-6, DC-8, constitution IV | ~210 files | **forge + beacon** (consensus `return` removals); prism (rest) | per-PR set; `sbt formatCheck` green; differential (both ranges) after (c) |
| **7** | DC-9 and FR-009 text: CLAUDE.md build table; `scala3-style.md` (S1 status stale, S3/S4 status, S6 `future` text, a new `-source` mapping section); `scala3-given-migration.md` (P3b done); `warning-ratchet.md` (new ids, verified grep); audit §1 pins; `docs/development/{scala-build,contributing}.md`; release-note draft | DC-9 | docs | **prism** (protocol edits only) | greps in the protocols run green on the tree |
| release | `auto-version.yml` minor dispatch on staging → 0.11.0; promotion PR to `main` with the R-7 note (the user merges) | DC-7 | — | user | Tier 2 on the promotion PR; 24 h soak on Mordor and Platåberget (US-2), serial, not alongside Sepolia |
| FU-1 (after 020) | scalafmt 3.11.x per R-1 FU-1 acceptance; constitution principle IV PATCH | — | ≥ 34 test files | prism | before spec 017's first PR |

DC-1 step 4 (libraries): none; GT-1 found none forced. Spec 016 hotfixes during the series are rebased onto the
migrated tree by their author (GT-0).

## Project Structure

### Documentation (this feature)

```text
specs/020-scala-3-9-lts-migration/
├── spec.md              # stub + Clarifications 2026-10-17
├── gt1-inventory.md     # GT-1 evidence
├── plan.md              # this file
├── research.md          # Phase 0: R-1…R-9, findings F-1…F-12, §B GT-2 baseline
├── data-model.md        # Phase 1: build-artifact entities
├── quickstart.md        # Phase 1: per-PR validation commands and the differential
├── contracts/
│   ├── published-artifacts.md   # five modules' TASTy/binary contract
│   └── build-command-surface.md # sbt aliases and CI checks that must keep meaning
├── dc3-sites.md         # created in PR 4 (371 E205 rows)
├── dc4-ledger.md        # created in PR 6 (per-category table + deferrals)
└── tasks.md             # /speckit-tasks (not created here)
```

### Source and build (repository root): files each PR touches

```text
project/build.properties                 # PR 1: sbt 1.13.0
project/plugins.sbt                      # PR 1: plugin bumps, sbt-javaagent org move
.github/workflows/_hive-sim.yml          # PR 1: sbt tgz URL
build.sbt                                # PR 3: scala-3, scapegoatVersion, scala3Options (-source, -Ykind-projector)
                                         # PR 4–6: -source:3.N; PR 6: -Wconf promotions
.scalafix.conf                           # PR 2: DisableSyntax.regex
scripts/ci/check_scala_imports.sh        # PR 2 (new) + test_check_scala_imports.sh
.github/workflows/ci.yml                 # PR 2: guard step; PR 3: job/artifact names
src/main/.../snap/actors/AccountRangeCoordinator.scala,
  snap/actors/storage/StorageOrderedApply.scala, 3 Account*Spec, 3 JRC specs   # PR 3 hand fixes
16 files of the 3.4 rewrite (incl. vm/OpCode.scala, rlp/.../package.scala)     # PR 3
rlp/src/main/scala/com/chipprbots/ethereum/rlp/RLPImplicits.scala              # PR 4 (DC-3)
src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcController.scala         # PR 4 (DC-3)
187 files of the 3.7 rewrite + P3b implicit-class files                         # PR 5
vm/PrecompiledContracts.scala, crypto/{EcKeyGen,SignatureValidator}.scala,
  regular/BlockFetcherState.scala, snap/ChainDownloader.scala, 2 @nowarn sites  # PR 6
.specify/memory/constitution.md; docs/adr/infrastructure/INF-009-*.md + READMEs # PR 0
CLAUDE.md; .claude/agent-protocols/{scala3-style,scala3-given-migration,warning-ratchet}.md;
  docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md; docs/development/*.md            # PR 7
```

**Structure Decision**: no new modules or packages. The only new source files are the CI guard scripts. Everything
else is edits to existing build, CI, protocol and source files, as listed above.

## Decisions for the user — resolved 2026-10-17

Recorded in spec.md under Clarifications, "Session 2026-10-17 (plan)".

1. **F-1**: scalafmt 3.8.3 for all of 020; 3.11.x is FU-1 before spec 017 (config-only first, otherwise a
   whitespace-only proof per file). G-3, FR-003 and SC-005 amended. **Resolved.**
2. **F-2**: SC-005 reads: `scalafmtCheckAll` green on every module (per-module form); scalafix no new findings outside
   `bytes`/`rlp`. **Resolved.**
3. **F-8 / OQ-6**: separate constitution amendments; 020 lands 1.1.1 + INF-009 now (PR 0). **Resolved.**
4. **F-3**: SC-003 now uses the GT-2 count (273/273 since #1616). **Resolved** (spec edit).
5. **R-3**: sbt **1.13.0**. **Resolved.**
6. **R-6**: the 2 new E029 and 2 `trait App` sites are deferred with dated `@nowarn` (`dc4-ledger.md` + CHASE-QUEUE).
   **Resolved.**
7. **F-13**: if glamsterdam-devnet-8 is retired, the ETH-family differential range comes from post-Amsterdam Sepolia.
   **Stated contingency** (no further decision needed).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| Import guard in two places (scalafix rule + CI grep) | scalafix is not run in CI and is red on staging; the user chose scalafix | scalafix-only would be unenforced (R-2) |
| `[IMPORT-TIMING]` on the host, not CI | only the host has the frozen Mordor and Ephemery datadirs (JDK 25 since 2026-10-19; the 2026-10-17 text said JDK 21) | a JDK 25 host has no synced datadirs; relative same-host comparison is valid for a compiler delta |
