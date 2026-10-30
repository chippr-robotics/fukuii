> Closed 2026-10-30 as Superseded by 022; boxes below are as they were at closure.

---
description: "Task list for Subtree-Complete Heal Verification (descend-and-stop, O(missing) completeness proof)"
---

# Tasks: Subtree-Complete Heal Verification

**Input**: Design documents from `/specs/005-subtree-complete-verification/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/internal-interfaces.md, quickstart.md

**Tests**: INCLUDED. The contracts define T-1…T-8 and the constitution (Principle III) mandates deterministic coverage for a consensus-adjacent change. Test tasks are first-class here, not optional.

**Organization**: Grouped by user story (US1/US2/US3 from spec.md). Citations like `~:1602` are `staging`-HEAD anchors — locate by symbol, not line.

**⚠️ Consensus-adjacent feature — read before starting**: Unlike a typical MVP, **US1 MUST NOT ship without US2**. US1 delivers the *pruning mechanism* (fast); US2 delivers the *never-false-prune safety* (correct). Shipping US1 alone could declare a complete state with a hidden missing node — a state-root mismatch / wedged node. The merge gate is US1 **and** US2 together, forge-reviewed (T022). US3 (observability) may follow.

**⚠️ Build/test constraint**: The barad-dûr ETC node is live. **Do NOT run `sbt` (compile/assembly/scalafmt/test) on this host while the node is active** — it over-subscribes the 15 GB host and freezes it / kills the heal. Compile/format/test tasks (T028–T031) run on **CI or an idle host** (per CLAUDE.md + memory). Source edits are fine.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: parallelizable (different file, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (no label on Setup / Foundational / Polish)

## Path Conventions

Single project — the `node` (main) module. Source under `src/main/scala/com/chipprbots/ethereum/…`; tests under `src/test/scala/com/chipprbots/ethereum/…`; config in `src/main/resources/conf/base/`; ADRs in `docs/adr/consensus/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Capture the design and add the config surface before touching the hot path.

- [x] T001 [P] Create the consensus ADR `docs/adr/consensus/CON-011-subtree-complete-verification.md` (sibling of CON-009/CON-010): record the root-INDEPENDENT content-addressed subtree-complete record, the descend-and-stop oracle, and the crash-safe record-after-persist ordering as the load-bearing safety mechanism (forge-reviewed in T022; finalized in T027).
- [x] T002 [P] Add the `pruned-heal-verification = true` key + explanatory comment to `src/main/resources/conf/base/sync.conf`, in the snap-sync block alongside `scoped-heal-verification` (~:165) and `decoupled-heal-serve-root` (~:179). Document: Hash-scheme-only, full-walk fallback when off / Path scheme / no records (FR-007).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The durable record API + config plumbing + scheme gate that BOTH US1 (reads records to prune) and US2 (writes them crash-safely) depend on.

**⚠️ CRITICAL**: No user-story work begins until this phase is complete.

- [x] T003 Add the subtree-complete record API to `src/main/scala/com/chipprbots/ethereum/db/storage/HealingFrontierStorage.scala` (C1, D1): `markSubtreeComplete(hash)`, `isSubtreeComplete(hash)`, `multiIsSubtreeComplete(hashes)` keyed `0x01 ++ keccak(X)` (33-byte, value `0x01`) in CF `'g'` — a prefix that cannot collide with a 32-byte node hash or the 21-byte `CompleteMarkerKey` (~:64-69). Extend the `loadAll` frontier filter (~:48) to exclude the new prefix. Records additive, monotone, never cleared.
- [x] T004 Thread the config flag end-to-end (C7): add `prunedHealVerification: Boolean` to the `SnapSyncConfig` case class in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`; parse `pruned-heal-verification` in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`; pass it through `TrieNodeHealingCoordinator.props`/constructor (add the field to the relevant message in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/Messages.scala` if the props go through it, mirroring `scopedHealVerification`).
- [x] T005 Add the storage-scheme gate (D5) in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`: a single predicate `prunedEnabled = prunedHealVerification && storageScheme == Hash` consulted at the verification entry; Path scheme OR flag off ⇒ unchanged full walk (`startVerificationBFS` ~:1873). No behavior change yet — just the gate plumbing.

**Checkpoint**: record API callable, flag wired, scheme gate in place — US1/US2 can begin.

---

## Phase 3: User Story 1 - Fresh node proves completeness in minutes (Priority: P1) 🎯 MVP

**Goal**: Make the completeness verification descend-and-stop — prune any present, recorded-complete subtree — and seed records during the SNAP/heal write path so the FIRST fresh-node verification is already O(missing) (FR-001, FR-003).

**Independent Test**: On a node whose trie is complete with seeded records, run the verification and confirm it does not descend recorded subtrees, visits work ∝ missing count, and reaches the same completion decision in a small fraction of the full-walk time (quickstart §Validation 1 & 5).

### Implementation for User Story 1

- [x] T006 [US1] Implement the descend-and-stop pruning oracle in `rebuildFrontierBFS` per-child descent (~:1602) in `TrieNodeHealingCoordinator.scala` (C2, FR-001): before enqueuing a present child X to descend, if `prunedEnabled && store.isSubtreeComplete(X)` treat X as a verified leaf — do NOT enqueue its children — and increment a pruned-subtree counter; else descend X exactly as today. Emitted `FrontierRebuilt` set for missing nodes is unchanged.
- [x] T007 [US1] Record `markSubtreeComplete(X)` on a GENUINE zero-missing closure of X's descent inside the walk (C2): only when X's subtree was fully descended and produced zero missing descendants — never from a `visitedLru` (~:1549)-faked "queue drained". (Crash-ordering hardened in T012.)
- [x] ~~T008 [P] [US1] Seed `markSubtreeComplete(fragmentRoot)` on a durable StackTrie fragment commit in `AccountRangeCoordinator`~~ **DROPPED as unsound** (forge adversarial review 2026-06-19): an account-fragment root's subtree includes its leaves' storage tries, which aren't downloaded until after accounts in SNAP — recording it could prune a still-missing storage node and falsely complete (FR-002 violation). Seeding is heal-side only (T009). `AccountRangeCoordinator` seeding + threading removed entirely. Sound storage-fragment seeding deferred to a follow-up.
- [x] T009 [P] [US1] Seed `markSubtreeComplete(X)` when `discoverMissingChildren` (~:1949) confirms node X has zero still-missing children, in `TrieNodeHealingCoordinator.scala` (C3b, D2). (Crash-ordering enforced in T012.)

### Tests for User Story 1

- [x] T010 [P] [US1] Test T-1 (prune-on-record, FR-001/SC-001/SC-006) in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/PrunedHealVerificationSpec.scala`: with `isSubtreeComplete(X)` recorded, assert the verification does not read X's children, visits O(missing), and reaches `StateHealingComplete`. Deterministic TestKit, no `Thread.sleep`.
- [x] T011 [P] [US1] Test T-5 (fresh-node seeding, FR-003/SC-001) in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/SubtreeCompleteSeedingSpec.scala`: after seeding records from SNAP fragment commits + heal subtree closures, the FIRST verification prunes the recorded subtrees with no prior full walk.

**Checkpoint**: pruning + seeding land; the first verification is fast — but NOT safe to merge until US2.

---

## Phase 4: User Story 2 - Never declare complete with a hidden missing node (Priority: P1)

**Goal**: Make the pruning provably as safe as the full walk — crash-safe record-after-persist ordering, descend-on-missing-record fallback, never-seed-from-abandoned/truncated, and byte-for-byte completion parity through the single existing chokepoint (FR-002, FR-004, FR-005, FR-006, FR-008).

**Independent Test**: Construct a present parent above a missing descendant with no record → the verification descends it, finds the missing node, does NOT complete (quickstart §Validation 2); and a pruned completion yields a byte-identical state root + marker vs the full walk (§Validation 4).

### Implementation for User Story 2

- [x] T012 [US2] Enforce crash-safe ordering at every record site (T007/T008/T009): write `markSubtreeComplete(X)` ONLY after X's subtree node-bytes are in a committed RocksDB `WriteBatch` (mirror `unpersistFrontier`'s post-`persist` rule, ~:489-490/:510-511) — children bytes (CF `'n'`) → THEN record (CF `'g'`) via ordinary `update` (C4, D3). The order "record before subtree durable" must be structurally impossible.
- [x] T013 [US2] Upgrade the terminal `markComplete()` at the completion site (~:908/:643) in `TrieNodeHealingCoordinator.scala` from `update` to `updateSync` (fsync, `RocksDbDataSource.scala:128`) so the completeness marker is durable (C4, D3). Per-subtree records stay WAL-ordered `update` (loss ⇒ safe descend).
- [x] T014 [US2] Verify the oracle (T006) descends every present-but-not-recorded node and every missing node — prune iff present AND recorded AND `prunedEnabled` (FR-002/FR-004, C2). Absent record ⇒ descend, never prune.
- [x] T015 [US2] Guard seeding (T009) against unsound records: the heal-side closure records a node only when it genuinely closed with zero missing **descendants** (no missing/pending direct child AND every present child — incl. `storageRoot` — is itself recorded-complete, via `multiIsSubtreeComplete`), never from a truncated / LRU-evicted descent (D2 guard). (The SNAP abandoned-fragment arm is moot — T008 dropped.)
- [x] T016 [US2] Confirm completion still routes through the SINGLE `verificationPassComplete` (~:953) → `HealingCheckCompletion` (~:889-912) → `markComplete`/`StateHealingComplete` chokepoint, with NO new completion site and NO new marker set-point; the walk still does no state writes (~:1526-1527) (C5, FR-005 byte-parity).
- [x] T017 [US2] Preserve the spec-004 fetched-node content-hash guardrail at `handleResponse` (~:1290) VERBATIM — `keccak256(returnedNode) == requested task hash` before store (C6, FR-008). This feature changes how PRESENT nodes are verified, not how FETCHED nodes are trusted.

### Tests for User Story 2

- [x] T018 [P] [US2] Test T-2 (never-false-prune — load-bearing, FR-002/FR-004/SC-002) in `PrunedHealVerificationSpec.scala`: present X, no record, missing descendant → assert the walk descends X, emits the missing node, does NOT complete.
- [x] T019 [P] [US2] Test T-3 + T-8 (crash safety, FR-006/SC-004) in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/PrunedHealCrashSafetySpec.scala`: children committed but record absent ⇒ restart descends; record never written before its subtree's bytes are durable; never recorded from an abandoned/reset fragment or an LRU-evicted descent.
- [x] T020 [P] [US2] Test T-4 (byte-parity, FR-005/SC-003) in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/PrunedHealParitySpec.scala`: one fixed healed state, reach completion pruned vs full (flag flip) → identical state root + identical CF `'g'` marker bytes + identical `StateHealingComplete`.
- [x] T021 [P] [US2] Test T-7 guardrail portion (FR-008) in `PrunedHealVerificationSpec.scala`: a fetched node with `keccak256 != requested hash` is still dropped (guardrail intact under pruning).

### Review for User Story 2

- [x] T022 [US2] `forge` adversarial review of the US1+US2 diff (consensus-adjacent gate): validate the never-false-prune invariant (no prune path without a durable record), the crash-safe record-after-persist ordering, byte-parity through the single chokepoint, and the preserved fetch guardrail. Block merge on any finding.

**Checkpoint**: pruning is fast AND provably safe — this pair (US1+US2) is the mergeable consensus-adjacent unit.

---

## Phase 5: User Story 3 - Operators can see and control the pruning (Priority: P2)

**Goal**: Observable engagement + savings, and a verified disable/fallback path (FR-007, FR-009).

**Independent Test**: Run a verification with pruning on and confirm an observable signal reports engagement, pruned-vs-visited counts, and elapsed time; disable via config and confirm the full-trie verification runs (quickstart §Validation 6 + live §).

### Implementation for User Story 3

- [x] T023 [P] [US3] Add `app_`-prefixed gauges to `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncMetrics.scala` (C8, FR-009), reusing the spec-003 scoped pattern (~:962): pruned-subtree count, nodes-visited count, pruned-verification elapsed ms, and an engagement flag.
- [x] T024 [US3] Emit an engagement log on the pruned path and a "pruning disabled — full-trie verification" log on the fallback path, at the verification entry in `TrieNodeHealingCoordinator.scala` (FR-009/FR-007), mirroring the `[HEAL-VERIFY-SCOPED]` style.

### Tests for User Story 3

- [x] T025 [P] [US3] Test T-6 (fallback, FR-007/SC-005) in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/PrunedHealFallbackSpec.scala`: flag off OR `storageScheme == Path` OR no records ⇒ full-trie verification, byte-identical to today.
- [x] T026 [P] [US3] Test the metrics-emission portion of T-7 (FR-009) in `PrunedHealVerificationSpec.scala`: engagement flag + pruned/visited gauges are emitted when pruning is taken.

**Checkpoint**: all three stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T027 [P] Finalize ADR `CON-011` (T001) with final symbol/line anchors and link it from `specs/005-subtree-complete-verification/plan.md` (Constitution Check II).
- [ ] T028 `wraith`: resolve any Scala 3.3.8 compile errors from the change — `sbt compile-all` on **CI or an idle host** (node live → no local sbt). Semantics-preserving only.
- [x] T029 Format: `scalafmt` (3.8.3 native binary, CHANGED files only — `/tmp/scalafmt-native --config .scalafmt.conf <files>`; do not `formatAll`) + `scalafix`; verify with `sbt formatCheck` on CI.
- [ ] T030 `eye`: run `testEssential` (Tier-1) + the new specs + the snap-actor regression on **CI or an idle host** (NOT while the node is active); report PASS/FAIL with the tier that ran.
- [ ] T031 Run the `quickstart.md` validation scenarios (deterministic T-1…T-8) and confirm SC-001…SC-006 mapping holds.
- [ ] T032 Version PATCH bump + `feat(snap):` conventional commit + open PR to `staging`; CI green (`scalafmtCheckAll`, `compile-all`, Tier-1/Tier-2) + forge review before merge. Deploy is build + one redeploy (the next fresh heal verifies in minutes).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (P1)**: T001, T002 — independent, start immediately.
- **Foundational (P2)**: T003–T005 — depend on Setup; **block all user stories**. T003 (record API) and T004 (config) are independent of each other; T005 depends on T004.
- **US1 (P3)**: depends on Foundational. T006 depends on T003+T005; T007 depends on T006; T008/T009 depend on T003.
- **US2 (P4)**: hardens US1 — T012 depends on T007/T008/T009; T013/T016 depend on T006; T014 depends on T006; T015 depends on T008/T009; T022 depends on all US1+US2 impl.
- **US3 (P5)**: depends on Foundational + US1 (needs the counters from T006). T023/T024 independent of each other.
- **Polish (P6)**: T027–T032 after the desired stories; T028→T029→T030→T031→T032 roughly sequential (compile → format → test → validate → ship).

### Story independence / merge gate

- **US1 (P1)** + **US2 (P1)** are the **single mergeable consensus-adjacent unit** — US1 must not ship without US2 (see the warning at top). Together they are forge-reviewed (T022) and CI-green before merge.
- **US3 (P2)** is independently testable and may land in the same PR or a follow-up; it does not change the completion decision.

### Within each story

- Implementation before its tests can pass; the never-false-prune (T018), crash-safety (T019), and parity (T020) tests are the consensus-aligned core and must pass before T022/merge.

---

## Parallel Opportunities

- **Setup**: T001 ∥ T002.
- **Foundational**: T003 ∥ T004 (different files); T005 after T004.
- **US1**: T008 ∥ T009 (different files, both after T003); T010 ∥ T011 (tests, after impl).
- **US2 tests**: T018 ∥ T019 ∥ T020 ∥ T021 (distinct spec files / cases).
- **US3**: T023 ∥ T024 impl; T025 ∥ T026 tests.

### Parallel example — US2 safety tests

```text
Task: "T018 never-false-prune in PrunedHealVerificationSpec.scala"
Task: "T019 crash safety in PrunedHealCrashSafetySpec.scala"
Task: "T020 byte-parity in PrunedHealParitySpec.scala"
Task: "T021 guardrail-intact in PrunedHealVerificationSpec.scala"
```

---

## Implementation Strategy

### MVP = US1 + US2 together (NOT US1 alone)

1. Phase 1 Setup → Phase 2 Foundational (record API + config + gate).
2. Phase 3 US1 (pruning + seeding) **and** Phase 4 US2 (safety + parity) — implement as one unit; a consensus-adjacent change cannot ship the optimization without its safety proof.
3. **STOP and VALIDATE**: T018/T019/T020 pass; T022 forge review clean.
4. Build + one redeploy → the next fresh heal verifies in minutes (SC-001).

### Incremental delivery

1. Setup + Foundational → record API exists, flag wired (no behavior change yet — safe to land).
2. US1 + US2 → pruned, fast, and safe → forge-review → MVP ship.
3. US3 → observability/control → same or follow-up PR.

### Subagent routing (per CLAUDE.md consensus protocol)

- `forge` — impact analysis (done in research.md) and the T022 adversarial review (ETC/Mordor consensus-adjacent).
- `wraith` — T028 compile errors.
- `eye` — T030 test-tier validation.
- Build/test only on CI or an idle host while the node is live.

---

## Notes

- D2 (full seeding) and D6 (default-on) are LOCKED (user sign-off 2026-06-19) — see research.md.
- The record is root-INDEPENDENT and eternal (content-addressed); never cleared, never migrated.
- Commit after each task or logical group; keep the diff additive.
- Out of scope (follow-up specs): persist BFS walk progress (#2), SNAP range-boundary seeding (#3), healing parallelism (#4), flat-KV rearchitecture, in-DB bloom filter.
