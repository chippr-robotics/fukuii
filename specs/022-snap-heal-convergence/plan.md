# Implementation Plan: SNAP State Healing That Converges on a Live Chain

**Branch**: `spec/022-snap-heal-convergence` | **Date**: 2026-10-28 | **Spec**: [spec.md](spec.md)

**Tracking**: #1671. Release 0.11.0 (#1659).

**Input**: Feature specification from `specs/022-snap-heal-convergence/spec.md`, plus the impact analyses from `beacon` (ETH/Path), `forge` (ETC/Hash, FR-020) and `vault` (write order). Those analyses are consolidated in [research.md](research.md).

## Summary

Healing will persist a trie node only when its whole subtree is present.

**How:**
- Healed nodes are held in memory with a count of their outstanding children.
- Complete chains are written as one atomic batch.
- Requests are dispatched **deepest-first**, which keeps the held set small.
- Requests are keyed by **position** (Path) or by **hash with reference counts** (Hash), so aliases and shared subtrees cannot be lost.
- Accounts whose storage or code is incomplete go into a **durable excluded set**:
  - on Path, the account's trie positions are deleted, so healing reaches it from the root;
  - on Hash, its storage root and code are seeded, resolved against the current root on every pivot move.

**Result:**
- Completion is "nothing pending, held or excluded for the current pivot root", on both schemes.
- The full-trie walk stops being a gate and becomes a background audit after handoff.
- The persisted heal frontier stops being a correctness mechanism. A restart re-discovers from the root.

**Scheme decision (FR-020):** keep the Hash scheme for 0.11.0. Retiring it is spec 018 DC-9; see research.md R-6. Confirmed by the user on 2026-10-28.

## Technical Context

- **Language/Version:** Scala 3. Spec 020 moves the compiler from 3.3.8 to 3.9.0 in 0.11.0; see Sequencing. JDK 25 with the C2 workaround flags.
- **Primary dependencies:** Pekko Typed actors; RocksDB through `RocksDbDataSource`, whose multi-CF `update()` is one atomic WriteBatch.
- **Storage:**
  - Path column families `StateTriePath` and `StorageTriePath`;
  - Hash node CF `n`;
  - healing frontier CF `g`;
  - app state `s`.

  After sync, both schemes read the **hash-keyed store**. `PathToHashExporter` copies Path data into it at handoff.
- **Testing:**
  - ScalaTest unit specs with `EphemDataSource`;
  - real-RocksDB reopen tests;
  - a child-JVM `kill -9` harness for crash safety;
  - ETC and ETH consensus regression suites;
  - two live fresh syncs (Mordor, Sepolia) as release gates.
- **Target platform:** the reference host (4 cores, 16 GB RAM, one SATA-class SSD, about 430 IOPS at queue depth 1), with a Sepolia heap of `-Xmx4g`.
- **Project type:** single JVM application (blockchain client).
- **Performance goals:**
  - Sepolia: from "state download done" to handoff in under 12 h (SC-001);
  - each pivot move costs work proportional to what changed, not to the state size.
- **Constraints:**
  - memory for held nodes is at most 128 MiB by default (about 250k nodes), configurable;
  - no build or test run on the host while a sync release gate is running (oomd kills the node).
- **Scale:** Sepolia is 787 M trie nodes across 21 levels. ETC mainnet walks take 16–20 h.

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Consensus determinism | **Gate.** Consensus-adjacent | This changes when the node trusts its state as complete. It does not change execution, roots or gas. Every healed node is checked against its keccak, and a missing node throws (`MissingRootNodeException`); it is never read as an absent account. A wrong "complete" is a liveness fault, not a chain split, because every imported block still checks its state root (forge, research R-4). Review: `beacon` for Path, `forge` for Hash, `vault` for write order. ETH and ETC paths stay separated by `StorageScheme`. |
| II. Spec-driven | Pass | Spec 022 has been approved by the user. This plan, then tasks, then implementation. |
| III. Test discipline | Pass, with additions | New regression and fault-injection tests (research R-7). Specs whose mechanism is removed will change: `StaleVerificationWalkSpec`, `PrunedHeal*`, `ScopedVerification*`, `SubtreeCompleteSeedingSpec`, `ForceCompletedStorageVerificationSpec`, `SNAPHealLoopSpec`. The ETC consensus suites listed in R-4 must pass **with no assertion changes**. No `Thread.sleep`. |
| IV. Scala 3 / formatted | Pass | Code is written against the 3.9 compiler once spec 020 PR 3 has landed (see Sequencing). |
| V. Quality gates | Pass | `sbt pp`-equivalent before each PR. The two fresh syncs are release gates on #1659. |
| VI. Operational safety | Pass | The kept Sepolia datadir is untouched until User Story 4. No `rm -rf` on datadirs. |
| VII. Decision records | **Action** | This **supersedes** spec 006's binding invariant ("byte-for-byte completion parity with the two-walk sequence") and specs 003/005 (scoped and pruned verification). Supersede notes go into those specs (FR-019). An ADR records the completion rule change. |

Re-check after design: the gate holds. All consensus-adjacent PRs are bucket C under the risk-stratified commit protocol, and each needs its chain specialist's sign-off.

## Project Structure

### Documentation (this feature)

```text
specs/022-snap-heal-convergence/
├── spec.md
├── plan.md          # this file
├── research.md      # R-1..R-8: consolidated beacon/forge/vault analysis, V/I tags kept
├── data-model.md    # held node, heal request, excluded account, invariant marker
├── quickstart.md    # validation runbook: fault injection, Mordor, Sepolia, repair
└── checklists/requirements.md
```

There is no `contracts/` directory: the feature exposes no external interface. FR-017 metrics and FR-018 log lines are covered in data-model.md.

### Source Code (affected areas)

All paths are under `src/main/scala/com/chipprbots/ethereum/`.

```text
blockchain/sync/snap/
├── actors/TrieNodeHealingCoordinator.scala
│     completion gate, re-peg (:606-690), HealingForceComplete (:567-584),
│     MaxConsecutiveStaleDiscards, isComplete (:290)
├── actors/healing/
│   ├── HealResponseProcessing.scala   write-before-children (:151-193), aliases (:98-104, :347-382)
│   ├── HealDispatch.scala             FIFO pendingTasks (:233), becomes deepest-first
│   ├── FrontierPersistence.scala      flushRawNodesAsync #1549 (:185-206), unpersist (:176, :203)
│   ├── FrontierRebuildBfs.scala       reused as the background-audit engine
│   └── HealVerification.scala         verification gates, removed
├── actors/account/AccountTrieAssembly.scala   V2 source; ContractWorkCarry (:118-125)
├── actors/storage/StorageOrderedApply.scala   skip (:158-161), root mismatch (:123-148)
├── actors/StorageRangeCoordinator.scala       force-complete (:754-784), abandonedAccounts.clear (:776)
├── SNAPSyncController.scala                   HealedCodeWaitTimeout (:836-846), completion (:853-870)
├── HealingOrchestrator.scala                  Hash controller walk (:203-315), completeHealingWalkClean (:271-286)
└── PathToHashExporter.scala                   handoff copy; sample-only check (:14-29)
blockchain/sync/SyncController.scala           checkpoint fall-through (:1510), clearDoneOnStart (:1516), ETC=Hash (:466)
blockchain/sync/checkpoint/CheckpointImporter.scala   parent-first import (:81-156)
db/storage/PathNodeStorage.scala               add accountNodeUpsert/Removal; dead deleteByPrefix (:172-186)
db/dataSource/RocksDbDataSource.scala          atomic multi-CF update (:257-276)
```

## Design Decisions

Evidence for each decision is in research.md.

- **D1. Deepest-first dispatch is required for correctness** (R-1).
  - Under FIFO dispatch with children-first commit, almost every internal node stays held until the bottom level drains. The held set then approaches the size of the trie, and each pivot move throws it away.
  - `pendingTasks` becomes a priority queue on path length, deepest first, as geth does with `prio = len(path)<<56`.
  - **Pause policy:** when the held-memory cap (FR-002) is reached, stop dispatching requests that open a **new** subtree. Requests for children of held nodes always proceed, because they are what free memory. Without this rule the cap can deadlock.
- **D2. The request model.**
  - Path: requests are keyed by `pathset`, and response matching is hash → Seq[request]. Aliases fall out by construction (fixes V3, including the identical-storage-root bug at `:381`).
  - Hash: requests are keyed by hash, with a reference count per parent edge. A child shared by two parents decrements both.
  - The held entry is `(bytes, outstandingDeps, parents)` (data-model.md).
- **D3. Atomic chain commit** (R-3).
  - When a held node's count reaches 0, it and every ancestor that becomes complete go into one `dataSource.update(...)`. That is one RocksDB WriteBatch, and it includes any FR-008 deletes.
  - Held entries are released **only after the write returns**. This fixes the live #1549 heal-path bug, where `flushRawNodesAsync` clears the buffer before the write and `flushing` sticks on failure.
  - The completion flag is written with `updateSync`.
- **D4. Code is a dependency of its account leaf** (geth `AddCodeEntry`).
  - `ByteCodeCoordinator` acknowledges each code write to the heal coordinator. This is a new message.
  - `HealedCodeWaitTimeout` is **removed**. If code stays unavailable, healing stays incomplete and logs the account every 60 s.
  - **After 30 idle minutes (user decision, 2026-10-30):** an idle minute is one in which no trie fetch could help, and each blocker has its own clock (round 2 review). A code hash ages from when it was first awaited, across re-pegs and dropped leaves, until its code is stored. Another hash's code arriving, or a dormant entry coming and going, changes nothing. Anything other than code has one clock, which restarts when the set's accounts change and ends when nothing but code blocks. The clocks are not durable: a restart starts them again. What the node then does depends on what is blocking:
    - **Only bytecode is blocking, and every hash has waited its 30 minutes:** it records every awaited code hash durably, writes the chains waiting only on that code, verifies the rest of the trie with a walk, and takes a lazy hand-off with `bytecodeRecoveryDone` unset. Healing never reports complete in this case: `StateHealingComplete` and the completeness marker stay off. The node reaches the head and fetches missing code on demand, and the recovery scan runs at next start. On Path the root is present once the chains are written, so the hand-off is possible. If the code arrives before the hand-off, the set empties and it is a normal completion. A re-peg disarms this, and the clocks, which it does not touch, act again at the next idle minute.
    - **"Only code" means:** nothing queued or in flight, no commit or flush failing, every held node would commit if its code waits closed (none waits on an absent child), and a pass over the excluded set has reached its end at the current root with nothing seeded and everything read. A pass not ended yet is not known either way, so the next tick looks again.
    - **Anything else has blocked for its 30 minutes:** today's behaviour. No such commit, and the existing paths: `StateHealingAbandoned`, then a lazy hand-off, or a re-peg on Path while the root is absent.
- **D5. V2 uses a durable excluded set** (R-2; FR-005 wording amended, see the Decisions section).
  - **Sources:** storage abandoned at force-complete (today's list is cleared at `:776` and must instead be persisted), the max-empty skip, root mismatches (today logged and accepted), and missing code.
  - The excluded set is persisted in app state **before** any action is taken on it.
  - **Path:** at heal start, point-delete every account-trie position on each excluded account's 64-nibble key, depth 0 to 64, leaf slot included (geth `pathTrie.delete`). Healing reaches the account from the root, and the invariant holds structurally.
  - **Hash:** branches already flushed cannot be recalled, and content addressing makes deletion meaningless. Instead, on every adopted pivot root, resolve each excluded account against the current root:
    - lookup hits a missing node: seed that node (beacon and forge approved this over skipping it in the slice 3 review). On a datadir that keeps the invariant it is already being healed, so the seed joins that request; on one that does not, nothing else would reach it once slice 5 removes the walk;
    - storage root or code absent: seed a heal task for it, or ask for the code;
    - the root holds no such account: the entry goes dormant and stays, because a later root can hold the account again (A to B to A, or a reorg). It does not count against the root, and each adopted root's first pass looks at it again.
  - **Both schemes:** an account leaves the excluded set only when its storage root and code are present at the current root. Completion (FR-011) requires no entry that counts, and a finished pass at the current root if any entry is dormant. The set is cleared only where the account phase starts from nothing, never at a restart or at finalisation (data-model.md). The Hash controller's clean walk also waits for a pass at its root if any entry is dormant, and a clean completion drops the dormant entries.
- **D6. The persisted frontier is retired as a correctness mechanism** (R-3, R-5).
  - Held nodes are never durable, so a restart re-seeds from the current root.
  - On the first start of the new version, CF `g` is cleared with one `deleteRange`. This drops the frontier and spec 005's subtree-complete records, which are untrusted under the new invariant.
  - This fixes FR-016.
  - **Check first:** the spec 016 resume tests that assume frontier resume (task T0.3).
  - *As built (slice 4):* every writer and every reader goes: the resume, the pruned oracle and the scoped verification, whose precondition was the retired marker. A restart always walks. Until slice 5 that raises what a restart costs: see research R-5c.
- **D7. The invariant marker** (FR-015; R-5).
  - A versioned key in AppState `s`, written with `updateSync`.
  - Stamped only on an **empty** datadir at SNAP start, or after a repair pass finds zero holes.
  - Never stamped when resuming state written by old code.
  - *As built (slice 4 review):* a walk over holes that outlasts a re-peg does not finish: it fills what it found and the run hands off unmarked (tasks.md T050a, T064).
  - **Pre-invariant states that force one repair pass:**
    - no marker;
    - a checkpoint import in progress (a new "import in progress" key, because the importer writes parent-first and a failed import falls through to SNAP on the same datadir: `SyncController:1510`);
    - a `clearDoneOnStart` restart (`:1516`).
  - *As built (slice 4):* while the walk runs at every completion, the repair pass is that walk, the coordinator's clean full walk of the root that completes. Never stamped or trusted on the Hash scheme under `pruning.mode = inmemory`; the Path scheme writes round the node cache, so it holds there. The marker also goes at any finalisation with no such walk behind it, and wherever `SnapSyncDone` is cleared. What it does and does not claim is in data-model.md "Invariant marker".
- **D8. Completion and audit.**
  - `isComplete` = no pending, in-flight or held requests, and an empty excluded set, for the current root.
  - **Removed:**
    - `verificationPassComplete`, the scoped and pruned paths, the stale-discard logic and `MaxConsecutiveStaleDiscards`;
    - the BFS start on a re-pegged root that is already present (`:666-690`): with the marker present, that case is complete immediately;
    - the Hash controller walk as a gate: Hash now routes to `completeHealingWalkClean`.
  - **`HealingForceComplete` on Hash** must send `StateHealingAbandoned` instead of `StateHealingComplete`, because the walk it relied on is gone.
  - **The background audit (FR-013):**
    - It is owned by a component that outlives the healing coordinator (the regular-sync side).
    - It walks the **hash store** at a recent root that is still present, since the pivot root may already be pruned.
    - Gaps go to `BlockImporter.resolvingMissingNode`.
    - The `FrontierRebuildBfs` engine is reused.
- **D9. Handoff check.** `PathToHashExporter` checks only 1,000 account leaves and 16 storage roots. Replace that with a root recompute over the exported hash store, or a much larger stratified sample. Which one is decided in the tasks, measured on Mordor.
- **D10. FR-008 on Path.**
  - Use exact-position deletes in the same batch: the inner prefixes of an extension node's `sharedKey`, plus hash-mismatched bytes found at a position (geth `children()` and `hasNode`).
  - Delete the dead `deleteAccountNodesByPrefix` / `deleteStorageNodesByPrefix`. HP-encoded keys don't preserve prefixes, and those methods scan the whole CF.

## Implementation Constraints: the SNAP-split verifier (measured 2026-10-29)

`snap-split-verify` (`scripts/snap-split/verify.sh`) is a required check on every PR that touches `blockchain/sync/snap/`. The design above was written without it in view. A dry run on a throwaway `fix/` branch measured what it allows. It ran against staging `925b6f693`, with one commit per kind of change.

| Change | Result | What spec 022 does |
|---|---|---|
| A branch named other than `fix/*` (or `refactor\|test/snap-016-*`) that edits a module file | **fail** (kind "other") | Every slice branch is named `fix/022-s<N>-<topic>` |
| A new file in a module directory (`controller/`, `actors/{account,storage,healing,bytecode}/`) | **fail**: a fix edits existing modules only | New components go in a new package `blockchain/sync/snap/heal/` (user decision 2026-10-29). This covers `HealRequest`, `HeldNodeSet` and `ExcludedAccountSeeding`. New files in `snap/`, `snap/actors/` and `snap/heal/` pass. |
| A raw `DataSource`, `DataSourceBatchUpdate`, `WriteBatch` or RocksDB import in a measured file (`snap/controller/**`, `snap/actors/**`, `SNAPSyncController.scala`) | **fail**, with no justification route | Atomic multi-node commits (D3) and position deletes (D10) are methods on the storage classes in `db/storage/` (`PathNodeStorage`, the node storage behind `MptStorage`). Heal code calls them through existing capability members. |
| One more `stateIngest.<handle>` reference in a measured file | +1 to `storage_refs`. Allowed with a `justifications["storage_refs.total"]` line and the per-file count updated in `scripts/snap-split/storage-baseline.json` | Reuse an already-bound handle where possible. Each slice PR budgets its rise and writes the justification, following the #1581 precedent. |
| A new `StateIngest` member | Allowed with a `justifications["capabilities.StateIngest.members"]` line | Prefer methods on an existing member's storage class. For example, the excluded-account entries (D5) go on `SnapSyncProgressStorage`, reached as `stateIngest.snapProgress.<call>`. That is a call, not a new handle. |
| A net deletion of more than 150 lines in module files in one commit | Needs a `# fix: #1671` trailer | Slices 4 and 5 (frontier retirement, gate removal) carry the trailer |
| Member-level rules in module trait files (FR-016d) | No concrete non-lazy `val`, no `var` with a non-literal initializer, no top-level statement | New state goes in the `heal/` classes or the coordinator core |

Each slice runs `scripts/snap-split/verify.sh --branch <branch> gh/staging HEAD` before its PR. The line numbers in this plan and in tasks.md predate #1673 and #1678. Re-grep each anchor before editing.

## Delivery Slices

Each slice is one PR to staging, in order. All consensus-adjacent slices are bucket C.

| # | Slice | Schemes | Review |
|---|-------|---------|--------|
| 0 | Prerequisite checks (tasks): (a) whether the contract-storage list outlives the storage phase; (b) which spec 016 resume tests assume frontier resume; (c) a baseline of the regression suites | – | – |
| 1 | Request model (D2), alias fix (V3, including the identical-storage-root owner), FR-008 deletes (D10), dead prefix-delete removal | both | beacon, forge |
| 2 | Deepest-first dispatch (D1), held set with bound and pause policy, atomic chain commit (D3), #1549 heal-path fix, fault-injection harness and kill points | both | vault, beacon, forge |
| 3 | Durable excluded set (D5): Path ancestor deletes, Hash seeding against the current root, code as a dependency (D4), removal of `HealedCodeWaitTimeout` | both | beacon, forge |
| 4 | Invariant marker (D7), including checkpoint import-in-progress and `clearDoneOnStart`; frontier retirement and CF `g` clear (D6) | both | vault, forge |
| 5 | Completion switch (D8): gates removed, Hash routed to `completeHealingWalkClean`, `HealingForceComplete` fix, background audit, FR-014/017/018 observability, handoff check (D9) | both | beacon, forge, eye |
| 6 | FR-019 cleanup: "spec 009" references corrected, supersede notes in specs 003/005/006, ADR | – | – |

Slice 5 must land **last**. Removing the gate before slices 1–4 brings back task #73, the holes in system-contract tries.

## Sequencing with Spec 020 and the Host Timeline

**Code sequencing.** Spec 020 PR 5's `-rewrite` inserts `using` clauses in 187 files, and the SNAP actors are among them. Running a mechanical rewrite and hand-written 022 code over the same files at the same time would mean conflicts on every merge. So:
1. Spec 020 PR 3 (in progress) and PR 4 land first. They are mechanical and near done.
2. Spec 022 slices 1–5 branch from that point.
3. Spec 020 PR 5's rewrite is regenerated **after** 022 lands. Regenerating it is cheap, because it is mechanical.
4. Spec 020 PR 6, PR 6b (#1656) and PR 7 follow.

**Host timeline.** Every live check is a window in which nothing can be built on this host.

| Window | Work | Estimate |
|--------|------|----------|
| Builds | 020 PR 3/4, then 022 slices 1–5 with tests (sbt lock, one JVM) | days of implementation |
| Gate 1 | Mordor fresh sync, then 24 h following the head | about 1–2 days |
| Gate 2 | Sepolia fresh sync, then 24 h following the head | about 3–4 days (about 2.5 days of download plus healing plus 24 h) |
| Secondary | Kept Sepolia datadir repair (User Story 4) | about 1 day |
| 020 | Q-7 import differential, for each consensus-touching 020 PR | hours each |

That is **about 5–7 days of serialized host time** on the 0.11.0 critical path after the code lands. The user has said the release waits on this.

## Decisions From the User (confirmed 2026-10-28)

1. **FR-020: keep the Hash scheme for 0.11.0 (recommended).**
   - In fukuii, everything after sync reads the hash-keyed store on both schemes: execution, pruning, proofs, checkpoint and recovery. Path is the **additional** layer, exported into that store at handoff.
   - Moving ETC to today's Path scheme makes sync slower (an extra export scan) and uses 2–3× the disk during sync, and gains nothing on reads.
   - The real retirement of both is spec 018 DC-9 (user decision 2026-10-17: ETC re-syncs from a checkpoint onto the unified layout).
   - Hash-specific work in this plan is small and sits behind `StorageScheme`.
2. **FR-005 wording amendment.** "MUST NOT commit an account with incomplete storage" cannot be implemented on Hash: branches already flushed cannot be recalled. Proposed outcome-based wording: "no account with incomplete storage or code may appear complete to healing, and every such account is reached and completed before completion is declared." D5 implements it per scheme. The user confirmed this amendment on 2026-10-28.

## Complexity Tracking

| Item | Why it's needed | Simpler alternative rejected because |
|------|-----------------|--------------------------------------|
| Two V2 mechanisms (Path delete, Hash seed) | The data models differ: path positions can be deleted, but content-addressed nodes that are already flushed cannot | A single seed-based mechanism on Path would leave the invariant violated structurally, so completion would depend on the excluded set alone. Path keeps the stronger guarantee. |
| A new code-acknowledgement message | Code must be a dependency of its leaf (geth parity) | Today's timeout finalises with code missing, which contradicts FR-011 |
| Background audit outliving the coordinator | The gate is removed, but a safety net is still wanted | Running the audit inside the coordinator would block handoff again |
