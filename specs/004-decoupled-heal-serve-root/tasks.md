> Closed 2026-10-30 as Done; boxes below are as they were at closure.

---
description: "Task list for Decoupled Heal Serve-Root (spec 004)"
---

# Tasks: Decoupled Heal Serve-Root

**Input**: Design documents from `/specs/004-decoupled-heal-serve-root/`

**Prerequisites**: plan.md, spec.md, research.md (D1–D6; **D4 = newest-servable**, **D5 = surface-only** per user sign-off), data-model.md, contracts/internal-interfaces.md

**Tests**: INCLUDED — required by the constitution (Principle III) and the contract test list (T-1…T-7). Write each story's tests first and confirm they FAIL before implementing.

**Decisions locked**: D4 serve-root selection = **newest-servable** (`networkBest−64`, reuse `RecentRoot` plumbing; content-hash check keeps it safe, FR-006 catches the rare non-shared node). D5 = unservable-node handling is **surface-only** (log + metric); `HealingForceComplete`/stagnation-abandon **neutralized** under decoupling so completion is never declared with a known-missing node.

**Format**: `[ID] [P?] [Story] Description` — `[P]` = different file, no dependency on an incomplete task.

**⚠️ Operational guard**: do NOT run `sbt` test/assembly/scalafmt while the barad-dûr node is active (freezes the host). Run T024 on CI or an idle host.

---

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 Create feature branch `004-decoupled-heal-serve-root` from `staging` (work branch; do not commit to `staging`/`develop` directly).
- [X] T002 [P] Create consensus ADR skeleton at `docs/adr/consensus/CON-010-decoupled-heal-serve-root.md` capturing the walk-root/serve-root split, the path-addressed-fetch risk, and the content-hash check as the load-bearing safety guardrail (final content filled in T021).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: these are shared scaffolding all user stories depend on — complete before Phase 3.

- [X] T003 Add config keys to `src/main/resources/conf/base/sync.conf` (snap-sync block): `decoupled-heal-serve-root = true` (FR-008) and `decoupled-heal-max-attempts-no-refresh = 12` (FR-006 surfacing threshold).
- [X] T004 Add `decoupledHealServeRoot: Boolean` and `decoupledHealMaxAttemptsNoRefresh: Int` to `SnapSyncConfig` and its `fromConfig` parsing in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` (alongside `healHoldPivotOnStagnation`, ~`:4923`).
- [X] T005 Thread the two config values through `TrieNodeHealingCoordinator.props` at both call sites (~`:3364`, ~`:3429`) and the coordinator constructor (~`:39-61`) in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`.
- [X] T006 Define `final case class HealingServeRootRefresh(newServeRoot: ByteString)` in the `TrieNodeHealingCoordinator` message set in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/Messages.scala` (~`:250`).

**Checkpoint**: config + message scaffolding exists; user-story work can begin.

---

## Phase 3: User Story 1 - Heal completes on a slow / peer-scarce node (Priority: P1) 🎯 MVP

**Goal**: keep the completeness walk pinned to a fixed walk root while fetching missing nodes from an advancing newest-servable serve root, so a walk that outlives the serve window still completes.

**Independent Test**: with the feature on and `serveRoot ≠ stateRoot`, the `GetTrieNodes` fetch carries `serveRoot` while the BFS seeds carry `stateRoot`; a `HealingServeRootRefresh` advances the serve root without disturbing the walk; the walk reaches completion across multiple serve-window advances.

### Tests for User Story 1 (write first; must FAIL before implementation)

- [X] T007 [P] [US1] Create `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/DecoupledHealServeRootSpec.scala` with deterministic Pekko-TestKit tests: **T-1** (feature on + `serveRoot≠stateRoot` ⇒ `GetTrieNodes.rootHash==serveRoot`, walk seeds use `stateRoot`), **T-2** (`HealingServeRootRefresh` updates `serveRoot` only — `stateRoot`/frontier/`verificationPassComplete`/`pendingTasks` unchanged), **T-6** (feature off ⇒ `GetTrieNodes.rootHash==stateRoot`, byte-identical to coupled). No `Thread.sleep`.

### Implementation for User Story 1

- [X] T008 [US1] Add `private var serveRoot: ByteString = stateRoot` to `TrieNodeHealingCoordinator` (init to walk root for fallback parity) in `TrieNodeHealingCoordinator.scala` (~`:40`).
- [X] T009 [US1] Add the `HealingServeRootRefresh` handler in `TrieNodeHealingCoordinator.scala`: set `serveRoot = newServeRoot` and **nothing else** (MUST NOT touch `stateRoot`, frontier, `verificationPassComplete`, `pendingTasks`, or re-seed). No-op when `decoupledHealServeRoot` is false. (Counter reset added in T015.)
- [X] T010 [US1] Change the fetch-site root in `requestNextBatch` (~`:1080`) to `rootHash = if (decoupledHealServeRoot) serveRoot else stateRoot` in `TrieNodeHealingCoordinator.scala`. (Only behavioral fetch change.)
- [X] T011 [US1] In `SNAPSyncController.scala`, acquire the **newest-servable** root (`networkBest−64`, reuse the `RecentRoot`/`PivotHeaderBootstrap` bootstrap) on chain advance / serve-window aging and push `HealingServeRootRefresh(serveRoot)` to `trieNodeHealingCoordinator` — **without** routing through `HealingPivotRefreshed` (which mutates the walk root).
- [X] T012 [US1] In `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`, add a distinct `RecentRoot` requester slot/tag (~`:1482-1565`) so the healing serve-root request does not contend with `StorageRecoveryActor`'s single `recentRootRequester`.

**Checkpoint**: the walk root stays fixed while the serve root advances; the fetch targets a servable root. US1 testable via T007.

---

## Phase 4: User Story 2 - Never declare complete with a missing or wrong-content node (Priority: P1)

**Goal**: preserve the content-hash guardrail on the decoupled path and ensure an unservable node never causes false completion or silent infinite retry.

**Independent Test**: a wrong-content peer response is dropped (not stored/counted); a node no serve root can supply never completes, increments the attempt counter, resets on refresh, surfaces past threshold, and never triggers `StateHealingComplete`/`HealingForceComplete`; decoupled vs coupled completion is byte-identical.

> Same-file note: US2 implementation edits `TrieNodeHealingCoordinator.scala` (and is therefore sequenced after US1's edits to that file), but US2 is independently *testable* via T013.

### Tests for User Story 2 (write first; must FAIL before implementation)

- [X] T013 [P] [US2] Create `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/DecoupledHealSafetySpec.scala` with deterministic tests: **T-3** (returned node with `keccak256 ≠ requested hash` ⇒ dropped, not stored, not counted, task stays pending), **T-4** (task no serve root satisfies ⇒ never completes; attempt counter increments; resets on `HealingServeRootRefresh`; surfaces past `decoupled-heal-max-attempts-no-refresh`; no `StateHealingComplete`/`HealingForceComplete`), **T-5** (parity: same final healed state ⇒ identical state root + identical CF `g` completeness-marker bytes via decoupled vs coupled flag flip). No `Thread.sleep`.

### Implementation for User Story 2

- [X] T014 [US2] In `handleResponse` (~`:1129-1137`) of `TrieNodeHealingCoordinator.scala`, confirm the content-hash check (`keccak256(node) == requested task hash`) runs unchanged on the decoupled path and add a guard comment marking it the consensus-safety invariant; ensure no decoupled branch bypasses it (FR-004).
- [X] T015 [US2] Add the FR-006 attempt counter `private val healAttempts = mutable.Map.empty[ByteString, Int]` in `TrieNodeHealingCoordinator.scala`: increment on each unsatisfied re-queue (~`:1184-1189`); clear in the `HealingServeRootRefresh` handler (T009); when a task exceeds `decoupledHealMaxAttemptsNoRefresh` with no refresh between, emit a surfacing log + metric. Bounded by `pendingTasks` (FR-011). **Never** force-complete.
- [X] T016 [US2] Neutralize false-completion paths under decoupling in `TrieNodeHealingCoordinator.scala`/`SNAPSyncController.scala`: ensure `HealingForceComplete` (~`:614`) and stagnation-abandon cannot send `StateHealingComplete` while `decoupledHealServeRoot` is on and any task is unsatisfied (SC-002).
- [X] T017 [US2] Verify/ensure the scoped-verification capture (spec 003) tags `healedPathsRoot` with the **walk root** `stateRoot` (~`:1161`), not `serveRoot`, so the F5 predicate `healedPathsRoot == stateRoot` (~`:787`) is unaffected by decoupling; add a regression assertion in `DecoupledHealSafetySpec.scala`.

**Checkpoint**: decoupling is provably safe — wrong content dropped, no false completion, parity with coupled path.

---

## Phase 5: User Story 3 - Operators can see and control the decoupling (Priority: P2)

**Goal**: observability of walk root vs serve root and cross-root heals, plus the config switch fallback.

**Independent Test**: with the feature on, signals report the fixed walk root, current serve root, count of nodes healed via a differing serve root, and currently-unservable count; with it off, a "decoupling disabled" log and the single-root path.

### Tests for User Story 3 (write first; must FAIL before implementation)

- [X] T018 [P] [US3] Create `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/DecoupledHealObservabilitySpec.scala` with **T-7**: assert engagement + observability signals (walk root, serve root, cross-root heal count, unservable count) are emitted when on, and a "decoupling disabled" signal + single-root fetch when off.

### Implementation for User Story 3

- [X] T019 [P] [US3] Add additive `app_`-prefixed gauges in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncMetrics.scala` (FR-010): walk root (short hash/label), current serve root, count healed via differing serve root, currently-unservable count; wire the `set*` calls from the coordinator.
- [X] T020 [US3] Add engagement/fallback logs in `TrieNodeHealingCoordinator.scala`: serve-root refresh (old→new, blocks-behind-head), and "decoupling disabled — using single-root heal" when the flag is off.

**Checkpoint**: operators can confirm engagement and savings; fallback is observable.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T021 [P] Complete the consensus ADR `docs/adr/consensus/CON-010-decoupled-heal-serve-root.md` with the final design, the FR-007 byte-parity argument, the FR-004 content-guardrail rationale, and the D4/D5 decisions.
- [X] T022 `forge` review of the diff: byte-for-byte completion parity (FR-007), the content-hash guardrail (FR-004) is preserved on every decoupled path, walk root never mutated by serve-root refresh (FR-001), and no EVM/consensus-critical file touched.
- [X] T023 [P] Run `sbt scalafmtAll` + `scalafix` + `sbt compile-all`; resolve all findings (Principle IV). (On CI or an idle host — not while the node is active.)
- [X] T024 Run `sbt testEssential` then `sbt testStandard` (Tier-1/Tier-2) on CI or an idle host; confirm T-1…T-7 pass and statement coverage ≥ 70%.
- [X] T025 Validate `specs/004-decoupled-heal-serve-root/quickstart.md` unit scenarios (T-1…T-7 green); record results.
- [ ] T026 PATCH version bump in `version.sbt`; conventional `feat(snap):` commit referencing spec 004; open PR to `staging`; require CI green + `forge` review before merge. (Deploy = build + one redeploy; fixes the next walk, not the currently-stuck one.)

---

## Dependencies & Execution Order

### Phase dependencies
- **Setup (P1)**: no deps.
- **Foundational (P2)**: depends on Setup; **blocks all user stories** (config + `serveRoot` + message must exist first).
- **US1 (P3)**: depends on Foundational. The MVP core.
- **US2 (P4)**: depends on Foundational; its implementation edits the same file as US1 (`TrieNodeHealingCoordinator.scala`), so sequence US2 impl after US1 impl. Independently *testable* via T013.
- **US3 (P5)**: depends on Foundational; light, can follow US1/US2.
- **Polish (P6)**: depends on US1+US2 (and US3 if shipping it) complete.

### Within-file sequencing (same file ⇒ not parallel)
- `TrieNodeHealingCoordinator.scala` is touched by T005, T008, T009, T010, T014, T015, T016, T017, T020 — execute these sequentially in that order.
- `SNAPSyncController.scala`: T004, T011, T016 — sequential.
- Test spec files (T007, T013, T018) are separate new files ⇒ `[P]` across stories.

### MVP scope
- **Safe MVP = US1 + US2** (both P1). US1 delivers the decoupling that lets the walk finish; US2 is the consensus-safety guarantee that must land before any deploy. US3 (P2, observability) can follow.

---

## Parallel Opportunities

- T002 (ADR) ∥ T001.
- The three test-spec files T007 ∥ T013 ∥ T018 (different new files) — but each must be written before its story's implementation.
- T019 (metrics file) ∥ T020 only if different files (they are: `SNAPSyncMetrics.scala` vs `TrieNodeHealingCoordinator.scala`).
- T023 (format) ∥ T021 (ADR).

## Parallel Example

```bash
# Foundational done → write all three story test suites in parallel (different new files):
Task: "T007 DecoupledHealServeRootSpec.scala (T-1, T-2, T-6)"
Task: "T013 DecoupledHealSafetySpec.scala (T-3, T-4, T-5)"
Task: "T018 DecoupledHealObservabilitySpec.scala (T-7)"
```

## Notes
- `[P]` = different files, no dependency. Most coordinator edits share one file ⇒ sequential.
- Locked decisions: **D4 newest-servable**, **D5 surface-only / no force-complete**.
- The content-hash check (`:1137`) is the single consensus-safety guardrail — never weaken or bypass it on a decoupled path.
- Build + one redeploy required; this fixes the *next* walk, not the currently-stuck one.
