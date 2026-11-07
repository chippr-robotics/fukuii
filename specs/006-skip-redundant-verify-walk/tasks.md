> Closed 2026-10-30 as Superseded by 022; boxes below are as they were at closure.

---
description: "Task list for Skip the Redundant Second Verification Walk on a Clean Post-SNAP Heal"
---

# Tasks: Skip the Redundant Second Verification Walk

**Input**: Design documents from `/specs/006-skip-redundant-verify-walk/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/internal-interfaces.md, quickstart.md

**Tests**: INCLUDED. The contracts define T-1…T-5 and the constitution (Principle III) mandates deterministic coverage for a consensus-adjacent change.

**Organization**: Grouped by user story (US1/US2/US3 from spec.md). Citations like `~:638` are `staging`-HEAD anchors — locate by symbol, not line.

**⚠️ Consensus-adjacent feature — read before starting**: US1 (the early-completion mechanism) and US2 (the never-false-complete safety) are the **same guarded `if`-block** — they MUST land together, forge-reviewed (T009). Shipping the route without the full guard could declare a complete state with a hidden missing node (state-root mismatch / wedged node). Merge gate = US1 **and** US2 together.

**⚠️ Build/test constraint**: The barad-dûr ETC node is live and mid-walk. **Do NOT run `sbt` (compile/format/test) on this host** — it freezes the host / kills the heal. Compile/test tasks (T013/T015) run on **CI or an idle host**. Source edits are fine. This change is **redeploy-gated** and cannot help the in-flight walk.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (no label on Setup / Foundational / Polish)

## Path Conventions

Single project — the `node` (main) module. The only production file is `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`. Tests under `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/`; ADRs in `docs/adr/consensus/`.

---

## Phase 1: Setup

- [x] T001 [P] Create the consensus ADR `docs/adr/consensus/CON-012-skip-redundant-verify-walk.md` (next id after CON-011): record the clean-rebuild early-completion guard, the recovered Chesterton's-Fence rationale (verification walk = PR #1321 BUG-1 guard for `discoverMissingChildren`'s shallowness; `rebuildFrontierBFS` fully recurses so it's redundant on a clean rebuild), the D2 guard predicate, and the byte-parity-via-single-chokepoint argument. Finalized in T012.

---

## Phase 2: Foundational (Blocking Prerequisite)

**Purpose**: Make the rebuild walk's outcome observable so the early-completion guard can be evaluated. Blocks US1/US2.

- [x] T002 Change `FrontierRebuildComplete` from a payload-free case object to a **case class** `FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString)` in `TrieNodeHealingCoordinator.scala` (C1, D3/FR-004); thread the rebuild's `frontierCount` (~:1563) as `missingEmitted` and the captured walk root as `walkRoot` through the walk's `onComplete` callback at the rebuild launch site (~:614); update the single existing sender to the new shape. No other behavioral change to the walk kernel. (Grep `FrontierRebuildComplete` to confirm the sender set.)

**Checkpoint**: the handler can now see `(missingEmitted, walkRoot)` — US1/US2 can begin.

---

## Phase 3: User Story 1 - One walk, not two, on a clean rebuild (Priority: P1) 🎯 MVP

**Goal**: When the rebuild was genuinely clean, declare `StateHealingComplete` after one walk by routing through the single existing chokepoint (FR-001/FR-002).

**Independent Test**: On a clean-trie resume fixture, the rebuild walk finishing 0-missing/0-healed yields `StateHealingComplete` within 5s — no second walk (quickstart §1).

### Implementation for User Story 1

- [x] T003 [US1] In the `FrontierRebuildComplete` handler (~:638-645) in `TrieNodeHealingCoordinator.scala`, after the existing `markComplete()` (unchanged), add the guarded early-completion block (C2): `if (missingEmitted == 0 && totalNodesHealed == 0 && isComplete && !flushing && walkRoot == stateRoot) { verificationPassComplete = true; self ! HealingCheckCompletion }`. Route via `self ! HealingCheckCompletion` (NOT a direct `StateHealingComplete`) so the marker write (~:908) + send (~:912) flow through the single chokepoint (D1, byte-parity). The `else` arm is unchanged (today's behavior). (The full guard predicate is hardened/verified under US2.)

### Tests for User Story 1

- [x] T004 [P] [US1] Test T-1 (one-walk completion, FR-001/SC-001) in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/CleanRebuildEarlyCompletionSpec.scala`: a clean resume fixture (persistence on, marker absent, complete/childless trie — reuse `HealingTrieFixtures` + the `HealingFrontierResumeSpec` harness) → `StartTrieNodeHealing(root)` → `controller.expectMsg(5.seconds, StateHealingComplete)`; assert `store.isComplete == true` and no second walk. Deterministic TestKit, no `Thread.sleep`.

**Checkpoint**: one-walk completion works on a clean trie — NOT safe to merge until US2.

---

## Phase 4: User Story 2 - Never declare complete with a missing node; byte-parity (Priority: P1)

**Goal**: The early declaration fires only when provably safe (all five conjuncts), is byte-identical to the two-walk outcome, and excludes every false-completion path (FR-002/FR-003/FR-005/FR-008).

**Independent Test**: a rebuild that finds a missing node, and a stale-root completion, each must NOT complete early; a clean early completion is byte-identical to the two-walk path (quickstart §2/§3/§4).

### Implementation for User Story 2

- [x] T005 [US2] Verify/harden the guard predicate from T003 so each conjunct excludes its failure mode (research D2 + adversarial verdict): `missingEmitted==0` (no gap found), `totalNodesHealed==0` (no heal-during-walk; the conservative `==0` per D5), `isComplete` = `pendingTasks.isEmpty && activeRequests.isEmpty` (~:1495), `!flushing` (no async node-write in flight; mirror the ~:890 gate), and **`walkRoot == stateRoot`** (explicit stale-root guard, D4/FR-005 — NOT the incidental pivot-reseed property). Add a code comment tying each conjunct to its excluded attack.
- [x] T006 [US2] Confirm completion still routes ONLY through the single `HealingCheckCompletion` (~:889-912) → `markComplete`(~:908) → `StateHealingComplete`(~:912) chokepoint, with NO new completion site and NO new marker set-point; the walk does no state writes (C3, FR-002). Verify; do not add a parallel completion path.

### Tests for User Story 2

- [x] T007 [P] [US2] Test T-2 (never-false-complete on missing, FR-003/SC-002 — load-bearing) in `CleanRebuildEarlyCompletionSpec.scala`: a rebuild fixture with one un-stored child (Branch/Extension referencing a missing child hash via `TestMptStorage`) → assert `pendingTasks > 0` and NO `StateHealingComplete` within 5s.
- [x] T008 [P] [US2] Test T-3 (stale-root safety, FR-003/FR-005/SC-002 — load-bearing) in `CleanRebuildEarlyCompletionSpec.scala`: while walk-1 (root A) is in flight, send `HealingPivotRefreshed(root B != A)`; when the stale `FrontierRebuildComplete(walkRoot=A)` lands, assert NO early completion (`walkRoot != stateRoot`).
- [x] T009 [P] [US2] Test T-4 (byte-parity, FR-002/SC-003) in `CleanRebuildEarlyCompletionSpec.scala` (mirror `ScopedVerificationParitySpec`): the early-path completion yields an identical `StateHealingComplete` + identical `store.isComplete` marker vs the two-walk path for the same trie.

### Review for User Story 2

- [x] T010 [US2] `forge` adversarial review of the US1+US2 diff (consensus-adjacent gate): re-confirm no false-completion path (the five refuted attacks: bounded-visited eviction, heal-during-walk, stale-root, storage-under-leaf, marker-trust), byte-parity through the single chokepoint, and the explicit walk-root guard. Block merge on any finding.

**Checkpoint**: early completion is fast AND provably safe — this pair (US1+US2) is the mergeable consensus-adjacent unit.

---

## Phase 5: User Story 3 - Verification still guards the non-rebuild paths (Priority: P2)

**Goal**: The fence stays — the verification walk still runs on every completion path that is not a clean full rebuild, and the watchdog remains the fallback (FR-006/FR-007).

**Independent Test**: a non-clean-rebuild completion still runs the verification walk; after an early completion the watchdog cannot fire walk #2 (quickstart §SC-004).

### Implementation for User Story 3

- [x] T011 [US3] Confirm (and comment) that the early-completion path is reachable ONLY from the clean `FrontierRebuildComplete` handler, and that the dead-pulse watchdog (~:1012-1027) and `HealingCheckCompletion` (~:889-912) are otherwise UNTOUCHED — so the verification walk still runs on the inline-discovery and heal>0 paths (C4/C5, FR-006/FR-007). No code change beyond the comment unless a gap is found.

### Tests for User Story 3

- [x] T012 [P] [US3] Test T-5 (watchdog self-suppression, FR-006/SC-004) in `CleanRebuildEarlyCompletionSpec.scala`: after an early completion assert `verificationPassComplete == true` so the dead-pulse branch (~:1015) cannot force-start walk #2; and (no-double-fire) `expectMsg(StateHealingComplete)` then `expectNoMessage(2.seconds)`.

**Checkpoint**: all three stories independently functional; the fence is preserved.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T013 [P] Finalize ADR `CON-012` (T001) with final symbol/line anchors and link it from `specs/006-skip-redundant-verify-walk/plan.md`.
- [ ] T014 `wraith`: resolve any Scala 3.3.8 compile errors from the case object→class change (every `FrontierRebuildComplete` reference) — `sbt compile-all` on **CI or an idle host** (node live → no local sbt). Semantics-preserving only.
- [x] T015 Format: `scalafmt` (3.8.3 native binary, CHANGED files only — do not `formatAll`) + `scalafix`; verify with `sbt formatCheck` on CI.
- [ ] T016 `eye`: run `testEssential` (Tier-1) + `testOnly *CleanRebuildEarlyCompletion* *HealingFrontierResume*` + the snap-actor regression on **CI or an idle host** (NOT while the node is active); report PASS/FAIL with the tier that ran.
- [ ] T017 Run the `quickstart.md` validation scenarios (deterministic T-1…T-5) and confirm SC-001…SC-005 mapping holds.
- [ ] T018 Version PATCH bump + `fix(snap):` (or `perf(snap):`) conventional commit + open PR to `staging`; CI green (`scalafmtCheckAll`, `compile-all`, Tier-1/Tier-2) + forge review before merge. Deploy is build + one redeploy on the next idle window (the next heal benefits, not the in-flight walk).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)**: T001 — independent, start anytime.
- **Foundational (P2)**: T002 — the message-shape change; **blocks US1/US2** (the handler needs `missingEmitted`/`walkRoot`).
- **US1 (P3)**: T003 depends on T002; T004 (test) after T003.
- **US2 (P4)**: hardens US1 — T005/T006 depend on T003; T007/T008/T009 (tests) after T005; T010 (forge) after all US1+US2 impl.
- **US3 (P5)**: T011 after T003 (needs the early path in place); T012 (test) after T011.
- **Polish (P6)**: T013 anytime after T001; T014→T015→T016→T017→T018 roughly sequential (compile → format → test → validate → ship).

### Story independence / merge gate

- **US1 + US2 are the single mergeable consensus-adjacent unit** — the guarded `if`-block is one edit; the route (US1) and the full guard (US2) land together, forge-reviewed (T010), CI-green.
- **US3 (P2)** is a comment/assertion + one test; it confirms no regression and may land in the same PR.

### Within each story

- The never-false-complete (T-2/T007), stale-root (T-3/T008), and byte-parity (T-4/T009) tests are the consensus core and must pass before T010/merge.

---

## Parallel Opportunities

- **Setup**: T001 ∥ everything.
- **US1/US2/US3 tests**: T004, T007, T008, T009, T012 are cases in the same `CleanRebuildEarlyCompletionSpec.scala` — write them together once the impl (T003/T005) lands; they share the fixture (`HealingTrieFixtures`).
- **Polish**: T013 ∥ the code/test work.

### Parallel example — US2 safety tests

```text
Task: "T007 never-false-complete-on-missing in CleanRebuildEarlyCompletionSpec.scala"
Task: "T008 stale-root safety in CleanRebuildEarlyCompletionSpec.scala"
Task: "T009 byte-parity in CleanRebuildEarlyCompletionSpec.scala"
```

---

## Implementation Strategy

### MVP = US1 + US2 together (NOT US1 alone)

1. T001 Setup → T002 Foundational (message shape).
2. T003 US1 (guarded early-completion route) **and** T005/T006 US2 (full guard + byte-parity) — one guarded `if`-block; a consensus-adjacent change cannot ship the route without its full guard.
3. **STOP and VALIDATE**: T007/T008/T009 pass; T010 forge review clean.
4. Build + one redeploy on the next idle window → the next clean restart completes in one walk (SC-001).

### Subagent routing (per CLAUDE.md consensus protocol)

- `forge` — design (done in research.md) and the T010 adversarial review (ETC/Mordor consensus-adjacent).
- `wraith` — T014 compile errors (the case object→class touches every reference).
- `eye` — T016 test-tier validation.
- Build/test only on CI or an idle host while the node is live.

---

## Notes

- D4 (explicit `walkRoot`) and D5 (conservative `totalNodesHealed==0`, unconditional) are LOCKED (user sign-off at specify time) — see research.md.
- Independent of spec 005 / PR #1364: no shared edits beyond the same file region; `markComplete` is idempotent; no merge conflict expected.
- Out of scope (do NOT change): the dead-pulse watchdog itself, `discoverMissingChildren`'s shallowness (the fence stays), deferred-merkleization.
