---
description: "Task list for spec 022: SNAP state healing that converges on a live chain"
---

# Tasks: SNAP State Healing That Converges on a Live Chain

**Input**: [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md), [quickstart.md](quickstart.md)

**Tracking**: #1671. Release 0.11.0 (#1659).

**Prerequisite**: **#1672 is merged to staging first.** It makes the SNAP range checks linear (branch `fix/snap-range-checks-linear`). Without it, account download is CPU-bound and no timing in this spec means anything.

## How this list is organised

Phases follow plan.md "Delivery Slices" 0–6 in order. Each slice is one PR to staging. **Slice 5 (gate removal) lands last.** Removing the gate before slices 1–4 brings back task #73, the holes in system-contract tries.

Tasks are tagged with the user story they serve:

| Story | Meaning |
|-------|---------|
| US1 | A fresh ETH/Path node completes SNAP sync |
| US2 | A fresh ETC/Hash node completes SNAP sync |
| US3 | Crash safety |
| US4 | Repair of a pre-invariant datadir |
| US5 | Scheme decision (resolved 2026-10-28: keep Hash; FR-020, research R-6) |

Within each slice, **tests come before implementation**, and the tests must fail before the code is written.

**Reviewer tags** mark consensus-adjacent tasks: **[beacon]** for Path/ETH, **[forge]** for Hash/ETC, **[vault]** for write order and atomicity. A slice PR merges only after its tagged reviewers sign off.

**Builds:**
- Every sbt run goes through the flock `/home/dontpanic/work/.sbt.lock`. Only one sbt JVM runs on the host at a time.
- **Build in a full clone, not a linked worktree.** sbt-git's jgit fails in linked worktrees with `NoWorkTreeException`. Precedent: `/home/dontpanic/work/snap-linear-build`.
- No build runs while a live sync gate is running.

**SNAP-split verifier (amendment 2026-10-29, plan.md "Implementation Constraints"):**
- Slice branches are `fix/022-s<N>-<topic>`.
- New components go in `blockchain/sync/snap/heal/`, never in a module directory.
- No raw `DataSource`/`WriteBatch` imports in `snap/controller`, `snap/actors` or `SNAPSyncController.scala`: batch writes are `db/storage` methods.
- A storage-reference or `StateIngest` member rise needs a justification line in `scripts/snap-split/storage-baseline.json`.
- Commits that delete more than 150 net lines of module code carry `# fix: #1671`.
- Run `scripts/snap-split/verify.sh --branch <branch> gh/staging HEAD` before every PR.
- Line numbers below predate #1673/#1678: re-grep each anchor.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task).
- Paths are relative to `src/main/scala/com/chipprbots/ethereum/` (main) or `src/test/scala/com/chipprbots/ethereum/` (test) unless written in full. The SNAP package is `blockchain/sync/snap/`.

---

## Phase 1: Setup (Slice 0, prerequisite checks)

**Purpose**: settle the open facts that the design depends on before any code changes.

- [x] T001 Confirm #1672 (`fix/snap-range-checks-linear`) is merged to staging. Branch `fix/022-s1-request-model` from that commit in a full clone (not a linked worktree). **Done 2026-10-29:** #1673 is in staging `925b6f693`; full clone `/home/dontpanic/work/s022-impl`.
- [x] T002 [P] Find out whether the contract-storage task list outlives the storage phase. **Done: research.md R-5a.** The list survives, and markers survive force-complete but are wiped on clean completion, so the excluded set must be persisted at each decision point. Read `blockchain/sync/snap/DownloadSupervisor.scala:694,757-760`, `SyncLifecycle.scala:342` and `SnapResumePlanner.scala:325`. Record which file or key holds abandoned accounts, and whether `SnapStorageDone` markers survive force-complete. This resolves the conflict noted in research R-5. Write the answer into research.md as R-5a.
- [x] T003 [P] List the spec 016 resume tests that assume the persisted heal frontier is restored on restart. **Done: research.md R-5b.** Grep `src/test/scala` for `persistFrontier`, `loadAll`, `CompleteMarkerKey`, `FrontierPersistence` and `healingFrontier`. Write the list into research.md as R-5b. These tests change in slice 4.
- [x] T004 [P] **Done 2026-10-29** on staging `925b6f693` in an isolated clone: testEssential root 6,378/0 (611 suites), rlp 46/0, bytes 11/0, crypto 80/0; targeted 331/0. Record the regression baseline on the slice-1 base commit. Run `./scripts/fukuii-test essential` once, plus `sbt "testOnly *SnapHashTrieSpec *MerkleProofVerifier* *StateIngestDurabilitySpec *Checkpoint*"`. Save the counts to `.local/docs/continuations/s022-baseline.md`.
- [x] T005 [P] **Done 2026-10-29 (quickstart.md §0).** Write the dev-iteration runbook into quickstart.md under a new "§0 Dev loop" section:
  - **Local snap server**: `coregeth-mordor.service` (core-geth v1.12.23; enode `9e5d0ea7…602a@127.0.0.1:30313`).
  - **Run config template**: `/home/dontpanic/mordor-scarcity/B/conf/mordor.conf`. It sets discovery off, `dns-discovery-domains = []`, and the local peer as both bootstrap node and `snap-server-peers`.
  - **Profiling**: JFR. Run `jcmd <pid> JFR.start name=heal settings=profile filename=<dir>/heal.jfr` when healing starts, then `jcmd <pid> JFR.stop name=heal`, then summarise with `jfr print --events jdk.ExecutionSample`.
  - **Measured baseline** (2026-10-28, staging plus #1672, Hash):
    - total Mordor SNAP 25.6 min: accounts 8 min, storage 8 min, bytecode 3.5 min;
    - healing: inline fetched 19 nodes;
    - three verification walks of 4.78 M nodes each (48 s, 50 s, 96 s) at 80–340% CPU, then about 2.5 min before handoff.
  - **Release gates stay on public peers.**

**Checkpoint**: facts R-5a and R-5b are recorded and the baseline is saved.

---

## Phase 2: Foundational (Slice 1: request model, aliases, Path dangling deletes)

**Purpose**: key heal requests so that aliases and shared subtrees can't be lost. All later slices build on this model. Serves US1 and US2.

### Tests (write first; they must fail)

- [x] T006 [P] [US1] Test: two accounts with an identical storage root on Path both get their full storage trie healed. Today the second owner's trie is dropped by the dedup at `HealResponseProcessing.scala:381`. File: `test/.../blockchain/sync/snap/actors/healing/HealAliasSpec.scala`. **[beacon]**
- [x] T007 [P] [US1] Test: an aliased path pending when a re-peg happens is re-discovered from the new root, and both positions are filled. Same file as T006. **[beacon]**
- [x] T008 [P] [US1] Test: writing a shorter node at path P on Path removes the deeper inner prefixes of an extension node's `sharedKey` from the same batch. A position whose stored bytes have the wrong hash is deleted. File: `test/.../blockchain/sync/snap/actors/healing/PathDanglingDeleteSpec.scala`. **[beacon] [vault]**
- [x] T009 [P] [US2] Test: on Hash, a child shared by two parents is requested once, and both parents' outstanding counts decrement when it arrives. File: `test/.../blockchain/sync/snap/actors/healing/HealRequestModelSpec.scala`. **[forge]**

### Implementation

- [x] T010 [US1] Introduce the request key type in `blockchain/sync/snap/heal/HealRequest.scala` (new file; not under `actors/healing/`, see the verifier amendment), following data-model.md "Heal request".
  - The key is a single `ByteString`: the HP-encoded path for Path, the node hash for Hash.
  - **Never key a map by `Seq[ByteString]`.** `MurmurHash3.seqHash` showed up in the Mordor walk profile.
  - Include `depth`, `parent` and `opensNewSubtree`.
  - **[beacon] [forge]**
- [x] T011 [US1] Replace `pendingHashSet` / `pathAliases` with maps from position key to request. Response matching becomes hash → `Seq[request]`. Files: `actors/healing/HealResponseProcessing.scala` (`:98-104`, `:347`, `:365`, `:381-382`) and `actors/TrieNodeHealingCoordinator.scala`. **[beacon]**
- [x] T012 [US2] On Hash, make the request model reference-counted per parent edge, in `actors/healing/HealResponseProcessing.scala` and `actors/TrieNodeHealingCoordinator.scala`. **[forge]**
- [x] T013 [US1] Add `accountNodeUpsert` / `accountNodeRemoval` helpers that return `DataSourceUpdateOptimized`, in `db/storage/PathNodeStorage.scala`, mirroring the existing storage-node helpers. **[vault]**
- [x] T014 [US1] Implement the FR-008 exact-position deletes, in the same batch as the write that makes them stale, in `actors/healing/HealResponseProcessing.scala` (extension-node arm `:361-375`) and in the path-verified presence check (`actors/healing/HealVerification.scala:169-178`). **[beacon] [vault]**
- [x] T015 [P] [US1] Delete the dead `deleteAccountNodesByPrefix` / `deleteStorageNodesByPrefix` from `db/storage/PathNodeStorage.scala:172-186`. They have no callers, scan the whole CF, and HP-encoded keys don't preserve prefixes. Apply the dead-code-review protocol and record the verdict in the commit message. **[vault]**
- [x] T016 **Done 2026-10-29:** beacon, forge and vault approved after one round of fixes. Run T006–T009 plus `sbt "testOnly *Heal* *SnapPathTrie* *SnapHashTrie*"` and confirm they pass. Open the slice-1 PR to staging and request reviews from beacon and forge.

**Checkpoint**: aliases and shared subtrees are tracked by position (Path) or by reference (Hash). Behaviour is otherwise unchanged.

---

## Phase 3: Slice 2: deepest-first dispatch, held set, atomic chain commit, crash safety

**Purpose**: a node reaches disk only after its whole subtree is present (FR-001 to FR-004). Serves US1, US2 and US3.

### Tests (write first; they must fail)

- [x] T017 [P] [US3] Test: when a re-peg happens while nodes are held, none of the held nodes is on disk, and completion waits until their children arrive. File: `test/.../blockchain/sync/snap/actors/healing/HeldNodeSetSpec.scala`. **[vault]**
- [x] T018 [P] [US3] Test the #1549 regression on the heal path: a throw inside the node write leaves the held or buffered nodes intact and resets `flushing`. File: `test/.../blockchain/sync/snap/actors/healing/HealFlushFailureSpec.scala`. Today, `FrontierPersistence.flushRawNodesAsync:185-206` clears the buffer before the write. **[vault]**
- [x] T019 [P] [US3] Test the bound and pause policy: at the held-memory cap, requests for children of held nodes are still dispatched, and requests that open a new subtree are not. There is no deadlock on a trie deeper than the bound allows. File: `HeldNodeSetSpec.scala`. **[vault]**
- [x] T020 [P] [US3] Test that dispatch order is deepest first: with mixed-depth pending requests, the dispatched order is non-increasing in depth. File: `test/.../blockchain/sync/snap/actors/healing/HealDispatchOrderSpec.scala`. **[beacon]**
- [x] T021 [US3] Build the kill-point harness in `src/it/scala/com/chipprbots/ethereum/sync/HealCrashSafetyItSpec.scala` (the IntegrationTest module).
  - It heals a synthetic trie in a child JVM on real RocksDB.
  - It sends `kill -9` at four points: after a node is held, mid-chain commit, mid-re-peg, and mid-excluded-set write.
  - It reopens the database and runs an audit asserting that no present node has an absent child.
  - Parameterise it over Path and Hash, and over `pruning.mode=basic` and `inmemory`.
  - *Scope (slice 2 review, vault V3/V4, eye F10):* the invariant is claimed for archive and basic pruning only (spec.md, after FR-004). Under `inmemory` the Hash cells run with a node cache small enough to evict and audit what eviction wrote, without asserting the invariant; the Path cells ignore the pruning mode (the path store is written directly), so they run once.
  - **[vault]**
  - **Done in slice 2 for three of the four points** (held, mid-chain before and after the write, mid-re-peg with nodes held and with the old trie healed), 20 runs. The fourth, mid-excluded-set write, landed with the excluded-account store in slice 3: `excluded-write` and `excluded-written` (inside the fsynced write of an entry, before and after it applies; the audit also reads the set: an entry whole or absent), and on Path `excluded-delete` (right after a heal start's position deletes). Slice 3 also added the `twin` fixture (two accounts with one storage trie) that slice 2 deferred.
  - *What the cell count does and does not show (slice 3 review, vault):* the suite has 71 cells, and the total is not slice-3 coverage. Of the 63 it had at the review, vault counted 3 that exercise slice-3 production logic, 12 that carry no slice-3 information and 6 that are weak; the rest re-run slice 2's points on the new fixture. The review fix round added 8 cells on both schemes, of which 6 exercise it: `excluded-removed` (the resolver takes an entry out, with the storage on disk first), `code-recorded` and `code-committed` (the code-only hand-off). The other 2, `excluded-resolved`, show a state, not the resolver's seed: with seeding mutated off, the rebuild walk still asks for the node.
  - *Widened after the slice-2 review (vault V4, eye F10), 32 runs:* two fixtures (the account trie, and an account leaf over a storage trie, so a Path chain commit and a re-peg delete span both trie column families and the audit checks a storage root); the re-peg points count only writes that put a node, `repeg-full` must find the new root on disk, and Path adds `repeg-delete` (the first, delete-only write after a re-peg); the Path cells run under basic pruning only (12), the Hash cells under basic (10, invariant asserted) and inmemory (10, a two-entry node cache that evicts to disk; the audit is reported, not asserted: 0 violations in the run of 2026-10-29); the writer pool has two threads. Not done: the restart-after-kill step (SC-006, slice 4), the twin-storage alias fixture (slice 3), `repeg-k` points (beacon N5, slice 4); see `.local/docs/continuations/s022-slice3-inputs.md`.

### Implementation

- [x] T022 [US1] Make `pendingTasks` a priority queue on depth, deepest first, in `actors/healing/HealDispatch.scala:233-234` (today a FIFO `ArrayDeque`; children are appended at `HealResponseProcessing:409`). **[beacon] [forge]**
- [x] T023 [US3] Implement the held-node set in `blockchain/sync/snap/heal/HeldNodeSet.scala` (new file), following data-model.md "Held node".
  - Each entry holds the node bytes, its outstanding count and its parent edges.
  - Measure the bound in bytes, configured by `fukuii.sync.snap-sync.healing.held-memory-limit` (default 128 MiB) in `src/main/resources/conf/base/sync.conf`.
  - Implement the pause policy from plan D1.
  - **[vault]**
- [x] T024 [US3] Change `actors/healing/HealResponseProcessing.scala:151-193` so that a healed node is **held**, not written, until its outstanding count is 0.
  - A node whose children are all present is committed together with every ancestor whose count reaches 0, in one atomic write. That write is a new `db/storage` method (on `PathNodeStorage`, and on the node storage behind `MptStorage` for Hash) that builds and applies the batch, because heal code may not import `DataSource`. Include any T014 deletes in that batch.
  - Release held entries only after the write returns.
  - **[vault] [beacon] [forge]**
- [x] T025 [US3] On a re-peg (`TrieNodeHealingCoordinator.scala:606-690`) and on `HealingForceComplete` (`:567-584`), drop the held set and pending requests, flush **only** complete nodes, and re-seed from the new root. **[beacon] [forge]**
- [x] T026 [US3] Remove the buffer-before-write ordering in `actors/healing/FrontierPersistence.scala:167-206`, so the #1549 heal-path bug is gone. If the Hash `rawNodeBuffer` path survives T024, it must clear only after a successful write and reset `flushing` on failure. **[vault]**
- [x] T027 [US3] Write the completion flag with `updateSync`, following research R-3, wherever `StateHealingComplete` is persisted. **[vault]**
- [x] T028 **Dev-loop run done 2026-10-29** (`/home/dontpanic/mordor-scarcity/S2`, jar `9b71bcd02`, sha256 `c69a841d…`).
  - **Correctness:**
    - SNAP completed and handed off at pivot 17,289,450.
    - The second verification walk found **0 missing nodes**. The 2026-10-28 baseline's second walk found 1, which forced a third walk.
    - Regular sync then imported 168 blocks in 10 min, with no missing-node or root errors.
    - No `[HEAL-KEY-CONFLICT]`, `[HEAL-HELD-CONFLICT]` or bookkeeping-fault lines.
  - **Timing, confounded by host IO:**
    - Bootstrap → handoff: 28.9 min (baseline 25.6).
    - Accounts + storage: 7.3 min (baseline about 16).
    - Bytecode: 11.4 min (baseline 3.5).
    - Healing → handoff: 10.3 min (baseline 5.7).
    - JFR shows the slow stretch (19:10–19:17) was disk-bound: healing threads sat in native `RocksDB.multiGet` / iterator `next`, with almost no Java CPU samples. GC paused 7 s in total over 610 s, the longest pause 226 ms.
    - Over the same window core-geth imported empty blocks at 15.5 s each on average (57 s at worst), so the SATA SSD was saturated for both processes. The slow bytecode phase has the same cause.
  - **Held set:** Mordor healed only 649 nodes. The cap was never reached (no `[HEAL-HELD]` pause), and the 2-minute pulse sampled after the drain (held=0), so the peak was **not captured**. The expected peak of about 43k nodes (20–45 MB) needs a larger heal. Sepolia gate T063 measures it.
  - Run the measured comparison again on a quiet host before slice 5's T057 timing.
  - **Re-run on the reviewed head `c6768e799`** (2026-10-30, `/home/dontpanic/mordor-scarcity/S3`, jar sha256 `96cc4901…`):
    - SNAP completed and handed off at pivot 17,292,570.
    - Walk 1's result was **discarded as stale**, because a re-peg happened mid-walk; this is the round-4 current-root guard.
    - Walk 2 found 41 missing nodes, which were healed. Walk 3 was clean, and healing declared 698 nodes healed.
    - Regular sync followed the head to 17,292,725.
    - No `[HEAL-KEY-CONFLICT]`, `[HEAL-HELD-CONFLICT]` or fault lines.
    - Timings: bootstrap → handoff 27 min, storage-done → handoff 19 min, healing → handoff 10 min.
    - About 80 s after handoff there was a burst of 16 `Could not get weight for parent` import errors that blacklisted the only peer. It also occurs on the pre-spec-022 run B, so it was filed as #1683 and is not slice-2.
  - **Host disk finding (2026-10-30), which confounds both runs' timings:**
    - TRIM never reaches the root SSD: `/etc/crypttab` has `luks` without `discard`, and `lsblk --discard` shows 0 B at `dm_crypt-0`.
    - Under load the BX500 showed r_await 330–445 ms and w_await 235–320 ms at 3–7 IOPS.
    - Redo the timing comparison after TRIM is enabled.
  - **Timing after the disk fix (2026-10-30):** `crypttab` now has `luks,discard`, the host was rebooted, and the first `fstrim` released 376.7 GiB. Runs are from scratch against the local core-geth.
    - Pre-spec-022 jar (R1): 5.1 min, 4 walks (507, 42, 1, 0 missing).
    - Slice-2 jar (S4, R2): 4.9 and 4.5 min, 3 walks each (507, 1, 0 and 507, 116, 0).
    - **The roughly 5× gain comes from the disk fix, not from slice 2.** The sample is too small to claim a slice-2 speed change on Mordor.
    - Under load: reads median 0.8 ms, max 17 ms; writes median 9 ms, max 44 ms (were 330–445 and 235–320 ms).
    - Measurements from before 2026-10-30 are not comparable with later ones. Mordor writes only about 1.2 GB, so these runs do not show how the drive handles sustained Sepolia-scale writes.

  Original task: Run T017–T021 and the slice-1 tests, then do one dev-loop Mordor run against the local core-geth (quickstart §0). Record the held-set size and bytes at peak (expected about 43k nodes, 20–45 MB). Open the slice-2 PR and request reviews from vault, beacon and forge.

**Checkpoint**: the invariant holds for everything healing writes, and is proven under kill points.

---

## Phase 4: Slice 3: durable excluded-account set, code as a dependency

**Purpose**: accounts with incomplete storage or code never appear complete to healing (amended FR-005, FR-006). Serves US1 and US2.

### Tests (write first; they must fail)

- [x] T029 [P] [US1] Test on Path: accounts whose storage was abandoned at force-complete, skipped after max-empty, rejected for a root mismatch, or missing code are each reached from the current root and completed before completion is declared. File: `test/.../blockchain/sync/snap/ExcludedAccountsSpec.scala`. **[beacon]**
- [x] T030 [P] [US2] Test the same four cases on Hash, through seeding resolved against the current root. Include the case where a re-peg happens between the exclusion and the seed, so the seed must resolve against the new root and must not stall. File: `ExcludedAccountsSpec.scala`. **[forge]**
- [x] T031 [P] [US1] Test that an account leaf whose code is missing is held until `ByteCodeCoordinator` acknowledges the code write. File: `test/.../blockchain/sync/snap/actors/healing/CodeDependencySpec.scala`. **[beacon]**

### Implementation

- [x] T032 [US1] Add the durable excluded-account store, following data-model.md "Excluded account", as methods on `db/storage/SnapSyncProgressStorage` (AppState namespace `s`, versioned key), reached from coordinators as `stateIngest.snapProgress.<call>`, which adds no new capability member. It is written before any action is taken on an entry. **[vault]**
- [x] T033 [US1] Record excluded accounts at every source:
  - force-complete: `actors/StorageRangeCoordinator.scala:754-784`, replacing `abandonedAccounts.clear()` at `:776` with a persist;
  - max-empty skip: `actors/storage/StorageOrderedApply.scala:158-161`;
  - root mismatch: `StorageOrderedApply.scala:123-148`, which today logs and accepts;
  - missing code.

  Persist each entry **at the moment the decision is made**: give-up, max-empty skip and root mismatch end the phase cleanly, and `DownloadSupervisor:760` then wipes the `SnapStorageDone` markers (R-5a). Store entries as AppState `SnapExcluded/<accountHash>` → reason, written with `updateSync`.

  **[beacon] [forge]**
- [x] T034 [US1] On Path, at heal start, point-delete every account-trie position along each excluded account's 64-nibble key, depth 0 to 64, leaf slot included, in one batch (geth `pathTrie.delete`). Run this after the account range has committed. File: `actors/TrieNodeHealingCoordinator.scala`, or a new `blockchain/sync/snap/heal/ExcludedAccountSeeding.scala`. **[beacon] [vault]**
- [x] T035 [US2] On Hash, on every adopted pivot root, resolve each excluded account against the current root. If the lookup hits a missing node, seed that node (amended: the implementation seeds where this task said skip, and beacon and forge approved it in the slice 3 review; see plan D5). If its storage root or code is absent, seed a heal task. If the root holds no such account, the entry goes dormant and stays (review hunter W2). Same file as T034. **[forge]**
- [x] T036 [US1] Add the code-acknowledgement message from `ByteCodeCoordinator` to the heal coordinator, and make code an outstanding dependency of its leaf (D4). Files: `actors/ByteCodeCoordinator.scala`, `heal/HeldNodeSet.scala`, `actors/TrieNodeHealingCoordinator.scala`. **[beacon] [forge]**
- [x] T037 [US1] Remove `HealedCodeWaitTimeout` (`SNAPSyncController.scala:836-846`). If code is unavailable, log the account every 60 s. After 30 idle minutes, each blocker on its own clock (round 2 review A, plan D4), act on what is blocking. **[beacon] [forge]**
  - **Amended 2026-10-30 (user decision, plan D4, FR-006).** If only bytecode is blocking: record the awaited hashes, write the chains waiting only on code, verify the rest with a walk, and take a lazy hand-off with `bytecodeRecoveryDone` unset. Healing never reports complete in this case. If anything else is blocking: today's behaviour, `StateHealingAbandoned` with no such commit, then a lazy hand-off or a re-peg.
- [x] T038a [US3] **(slice-2 sign-off condition: hunter, forge, vault, beacon)** Make the frontier walk exclusive across SNAP controller instances, not just across coordinator incarnations.
  - Slice 2's `WalkLease` belongs to one `BfsQueueStorage` wrapper instance, but the shared resource is the BFS column family. A walk left running by a stopped controller (after a `HealingImpossible` restart, for example) can still cut short the next controller's walk, which then reports clean (P1b).
  - Key the lease to the column family, process-wide, and **cancel** orphaned walks rather than waiting them out. Today the next walk waits out the orphan's remaining runtime, which on ETH mainnet Path can be hours.
  - Fix the orphan's backpressure, which reads the replaced incarnation's frozen gauge.
  - Close the refused-launch observability gap: run flags and gauges stay set, and 'engaged' is logged for a walk that never ran.
  - The stale-root end of a rebuild walk must start the completion check.
  - **[vault] [forge] [beacon]**
- [x] T038b [US3] **(slice-2 sign-off condition, beacon N3-r3 / W2-r4)** A supervisor-restarted heal coordinator must get the controller's **current** root. Today it walks its spawn-time root, because no `StartTrieNodeHealing` or re-peg is replayed. **[beacon] [forge]**
- [x] T038c Work through the remaining slice-2 review notes. They are recorded in the git-ignored `.local/docs/continuations/s022-slice3-inputs.md`. Highlights:
  - the A→B→A re-peg marker ABA;
  - a scoped gate walk replacing a dropped RestartResumeVerification;
  - the four-fault loadAll fallback;
  - `readFailures` stays unbounded and keyed per hash;
  - the give-up latch has no watchdog;
  - CF `g` entries survive a re-peg until slice 4.
  - **Triaged 2026-10-30:** what this slice fixed, and the target slice of each other note, are recorded at the end of the inputs file.
- [ ] T038 Run T029–T031 and all earlier slice tests, then do one dev-loop Mordor run. Open the slice-3 PR and request reviews from beacon and forge.
  - **Dev-loop runs, 2026-10-30** (Mordor from scratch against the local core-geth, on the healthy disk, in `/home/dontpanic/mordor-scarcity/`):

    | Run | Build | Bootstrap to hand-off | Excluded |
    |-----|-------|-----------------------|----------|
    | S5 | first pass, `74ee2319a` | 5 min | none |
    | S6 | round 1, `521e4aa16` | 6 min | none |
    | S7 | round 2, `75012d9f8` | 4 min | none |
    | F1 | round 1, core-geth stopped 22 min at storage 30%+ | 27 min | none |
    | F2 | round 2, core-geth stopped 40 min at storage 76% | 45 min | none |

    Every run handed off and followed head, with no ERROR lines in F1 or F2. The two outages show recovery, not exclusions: **with no peers nothing is asked, so no response comes back empty and nothing force-completes.** The excluded path has therefore not run live; T061a tracks that.

**Checkpoint**: no excluded account can make healing declare completion early.

---

## Phase 5: Slice 4: invariant marker, frontier retirement (US4)

**Purpose**: datadirs written before the invariant get exactly one repair pass (FR-015), and the persisted frontier stops growing (FR-016).

### Tests (write first; they must fail)

- [x] T039 [P] [US4] Test: a datadir with no marker but with heal progress runs one audit-and-repair pass, then stamps the marker. A datadir with a checkpoint import in progress, or a `clearDoneOnStart` restart, is treated the same way. File: `test/.../blockchain/sync/snap/InvariantMarkerSpec.scala`. **[vault] [forge]**
- [x] T040 [P] [US4] Test: on the first start of the new version, CF `g` is cleared with one `deleteRange`, and nothing writes to `g` after that, including under `pruning.mode = inmemory`. File: `InvariantMarkerSpec.scala`. **[vault]**
- [x] T041 [P] [US2] Test: an interrupted checkpoint import that falls through to SNAP on the same datadir gets the repair pass and does not trust its partially written root. File: `test/.../blockchain/checkpoint/CheckpointImportInterruptSpec.scala`. **[forge]**

### Implementation

- [x] T042 [US4] Add the invariant marker `snap.heal.invariant=2` and the `checkpoint.import.inProgress` key to AppState `s`, both written with `updateSync`, following data-model.md "Invariant marker". On the Hash scheme under `pruning.mode = inmemory` the marker is never stamped, and one found is not trusted and is cleared: healed nodes reach disk only as the node cache evicts them, so "present ⇒ complete" does not hold on disk there (spec 022 review vault R1/V3, V-N2). The Path scheme writes round the cache, so the rule is scheme-aware (V-N2 (4)). An import on a marked datadir takes the marker, in the write that sets the key; a success does not bring it back. Files: `db/storage/AppStateStorage.scala` and `blockchain/checkpoint/CheckpointImporter.scala` (set the key before streaming, clear it on CRC success). **[vault] [forge]**
- [x] T043 [US4] Detect a pre-invariant datadir at SNAP start, run one repair pass, then stamp the marker. **While the walk still runs on every completion (until T051), the repair pass is that walk**, not the background-audit engine, which is T053: stamped at the clean exits from healing, on the coordinator's clean full walk of the root that completes (the Hash controller's own walk stops at present hash nodes, so it is no proof). On an empty datadir, stamped at SNAP start. Never at a lazy hand-off or abandon; a finalisation with no such walk behind it removes a marker found. Where the marker cannot hold, the walk stays at every start and that walk is the repair (V-N2 (3)). Files: `blockchain/sync/SyncController.scala`, `SNAPSyncController.scala`, `controller/HealingOrchestrator.scala`, `controller/SnapFinalization.scala`, `heal/HealInvariant.scala`. **[beacon] [forge]**
- [x] T044 [US4] Retire the persisted frontier:
  - stop **every** writer to CF `g`, by method name: `persistFrontier`, `unpersistFrontier`, `clearPersistedFrontier`, `markComplete`, `clearComplete` (including the spawn-time clear under in-memory pruning) and `markSubtreeComplete`; and every reader: the resume's `loadAll` and `isComplete`, the scoped verification's precondition, and the pruned oracle's and the response path's `isSubtreeComplete`;
  - clear `g` once on upgrade with `deleteRange`, and keep `g` listed in `Namespaces.nsSeq`;
  - update the tests in research R-5b, including `CleanRebuildEarlyCompletionSpec` and `DecoupledHealSafetySpec`. Files: `actors/healing/FrontierPersistence.scala` and `actors/healing/HealResponseProcessing.scala`. **[vault]**
- [ ] T045 Run T039–T041 and all earlier slice tests. Open the slice-4 PR and request reviews from vault and forge.

**Checkpoint**: old datadirs are repaired once, new ones are never audited on the critical path, and CF `g` no longer grows.

---

## Phase 6: Slice 5: completion switch and background audit (US1, US2). LANDS LAST.

**Purpose**: completion means "nothing pending, held or excluded for the current root" on both schemes (FR-009 to FR-014). The full-trie walk is no longer a gate.

### Tests (write first; they must fail)

- [ ] T046 [P] [US1] Test: a simulated live chain that re-pegs every N responses still converges, each re-peg costs work proportional to the diff times the depth, and no full-trie walk runs before completion. File: `test/.../blockchain/sync/snap/HealConvergenceSpec.scala`. **[beacon]**
- [ ] T047 [P] [US2] Test: on Hash, completion routes to `completeHealingWalkClean` with no controller walk, and `HealingForceComplete` sends `StateHealingAbandoned`, not `StateHealingComplete`. File: `HealConvergenceSpec.scala`. **[forge]**
- [ ] T048 [P] [US1] Test: at handoff, the root recomputed from the exported hash store equals the pivot header's `stateRoot` (D9). File: `test/.../blockchain/sync/snap/PathToHashExportCheckSpec.scala`. **[beacon]**
- [ ] T049 [P] [US1] Test: the background audit, run after handoff, finds an injected hole, routes it to `BlockImporter.resolvingMissingNode`, logs it with its position, and never blocks import. File: `test/.../blockchain/sync/snap/BackgroundAuditSpec.scala`. **[beacon] [forge]**

### Implementation

- [ ] T050 [US1] Change `isComplete` (`TrieNodeHealingCoordinator.scala:290-291`) so that it also requires no held nodes, no outstanding code dependencies, and an empty excluded set. **[beacon] [forge]**
- [ ] T051a [US2] (forge, slice-1 review) Two Hash changes, both needed **before** the gate goes:
  - Drop the `storageScheme == Path &&` condition from the stale-walk filter in the `FrontierRebuilt` arm, so stale-root batches are dropped on Hash as well. Slice 1 added the filter for Path only. *Slice 4 raises the exposure:* a restart now always runs the rebuild walk, so a re-peg during it is the usual case on Hash, and it queues what only the old root holds; no peer at the new root serves that, and it holds completion until a later re-peg clears it. The one line alone was tried: the T030 case passes without its workaround, and 141 tests across `ExcludedAccountsSpec`, `StaleWalkEntrySpec`, `HealWalkSingleFlightSpec`, `TrieNodeHealingCoordinatorSpec`, `StaleVerificationWalkSpec`, `CleanRebuildEarlyCompletionSpec` and `InvariantMarkerSpec` stay green.
  - Make the "dispatched task not in the book" branch in `HealResponseProcessing` fail closed on Hash: re-add and re-queue the request (content-addressed, so this is safe), or block `isComplete` while a book fault is recorded.
  **[forge]**
- [ ] T050a [US1] **Prerequisites of T051 and T052 from the slice 3 review.** Each is safe today only because the walk catches it, so each is settled, or the walk stays for that case, before the walk goes:
  - [ ] **forge F4:** on Hash an excluded account is "complete" when its storage root is present, but a present root can sit over holes. Resolve those entries by a scoped descent, or keep the walk on Hash.
  - [ ] **beacon N-5:** the walk may not go before slice 4's repair pass runs.
  - [ ] **hunter N3:** T038b's restarted coordinator has the current root but does not re-seed it. Without the walk that is a false completion.
  - [ ] **vault F5:** under `pruning.mode = inmemory` a node read as present may be only in the node cache, so the resolver can take an entry out over a node a crash takes back. This is one more reason the walk stays for inmemory.
  - [ ] **A task file that cannot be read** (`ExcludedTaskFile.Outcome.Unreadable`, a wrong size or a hung read included): the coordinator goes on with what is in memory, so what a supervisor restart lost has no entry. This must become a hard stop before the walk goes (forge).
  - [ ] **No task-file sweep without done markers** (Hash with `pruning.mode = inmemory`, `recordStorageDone` false), since the file would name every account: what a restart loses there has no entry either (hunter W1.3).
  - [ ] **Max restarts exhausted** leaves no coordinator, so no sweep and nothing watching for one. The controller should own the sweep (hunter N4).
  - [ ] **An `Other` abandon on Hash can leave the root absent:** there is no analogue of the Path guard (forge).
  - [ ] **The synchronous T034 position deletes scale with what a sweep can record:** chunk them before mainnet (beacon N-9).
  - [ ] **Dormant accounts keep their positions at the next start** (beacon N-8).
  - [ ] **The armed hand-off can mean two full walks on Hash**, the coordinator's and the controller's (hunter N5).
  - [ ] **A restarted bytecode coordinator with no code-hash file** (none persisted yet, or the wait for its pointer ran out) goes on with what is in memory, said at WARN. Its hashes then have no entry, and without the walk healing could complete over them.
  - [ ] **A supervisor-restarted heal coordinator is not kicked:** it waits for a check (the dead-pulse watchdog, about 6 minutes) before it does anything.
  - [ ] **A supervisor restart after the code-only hand-off is armed:** the new incarnation is not armed. Its clean rebuild can set the frontier marker, and it waits a fresh 30 minutes before arming again. The entries still hold completion, so nothing false is claimed today, but the marker would be trusted once the walk is gone.
  - [ ] **A sweep that cannot be read or times out at a clean end goes on (fail-open), and for code the walk is no backstop:** a restarted bytecode coordinator whose sweep times out can let `bytecodeRecoveryDone` be set. The hard stop above has to cover the code-hash file as well as the storage task file (vault W2).
  - [ ] **The 30-minute sweep cap is wall clock, and the code sweep is unmeasured:** one `code.get` per hash, so 2.5M hashes at 0.8 ms is about 33 minutes. Use "no chunk finished in N minutes", or a budget scaled by entries (vault W2).
  - [ ] **The pointer waits count calls, not time** (`MaxTaskFilePointerWaits` has two call sites, so a burst can use them up early). Wait by the clock (vault, forge N-c).
  - [ ] **`restartSnapSync` leaves the code-hash path behind.** `codeFile` ignores a path with no count, so the sweep is safe. Blanking the path as well is one line, but `SnapResumePlanner.scala:478` reads the path without filtering an empty one: settle that first (vault W3).
  - [ ] **A restarted coordinator can over-record:** a healed-code bytecode coordinator sweeps the whole account-phase file (vault), and a storage sweep names accounts the Hash replay skipped for a present root (forge N-e). Both resolve at the first look, at a cost. At millions of code entries the asked set and `BlockerClock` each hold every hash in memory, and every tick reads each from the code store: measure before mainnet. The clock's eviction goes by when a hash was first seen, so over 4,096 true orphans (blocked once, unstored, never back) can still restart a stuck hash not yet visited: evict by last seen if that is ever observed (hunter BC-1 fix).
  - [ ] **A set too big for one root epoch:** at 100k+ entries a pass may not end before the next re-peg, which starts it over, so the kind stays `Unknown` and neither clock fires. Measure the pass, then carry the cursor across a re-peg or add a time budget (beacon N-3).
  - [ ] **The `Other` clock's signature at 256+ entries:** a demoted entry is read again later in the same pass under its dormant key, so it counts twice (the count rises by one and the XOR cancels), and the clock restarts on each flip. The obvious fix, skipping `toDormant` in the fold, is wrong: an entry on the pass's last page, or whose dormant key sorts before the cursor, is not read again, and would count no times. Skip it only when its dormant key sorts after the page's last entry and the pass has more pages, comparing bytes as unsigned (RocksDB's order), and test with `pageSize = 1`. It needs a multi-page pass that demotes an entry, and an `Other` blocker lasting 30 idle minutes, so it is rare (beacon N-1, hunter).
  - [ ] **A hash that leaves and returns keeps its age,** so one back after 30 idle minutes arms at once, with its code asked for only a moment before. By design and rare; a reset for hashes absent over a tick is optional (beacon N-2, forge N-a).
  - [ ] **A `StoreFailed` at a clean end retries forever:** fail loud, and bounded only by the store being broken (forge N-d).
  - [ ] **Nothing tests that the dormant drop comes after `persistHealedAnchorSync()`:** the other order could heal an older anchored root with the entries gone (vault).
  - [ ] **The marker's proof comes from the walk (slice 4):** `healingCleanWalkRoot` is set by the coordinator as a clean full walk completes, and a finalisation without it for this root removes the marker. When T051 takes the walk away for a marked datadir, that completion has to set it, or every clean hand-off unmarks the datadir.
  - [ ] **The repair pass does not look at code (slice 4):** on a pre-invariant datadir a leaf's code can be missing with nothing saying so, and the walk never was a backstop for it. Decide: withhold `bytecodeRecoveryDone` on a repair run, so the recovery scan runs at the next start (a scan once per old datadir), or check code in the walk (one read per distinct code hash), or leave it to the import's bulk recovery.
  - [ ] **The marker proves what the walk reached (slice 4):** a present node from an older binary that no walked root reaches can sit over a hole, and a later root can reach it. The background audit (T053) covers this; say so in its tests.
  - [ ] **A downgrade (slice 4):** an older binary run on a marked datadir writes under no invariant and leaves the marker. An upgrade after that trusts it. Decide whether that needs more than a release note.
  - [ ] **Dead wiring to take with a `# pins:` change (slice 4):** `scopedHealVerification`, `scopedHealMaxPaths`, `prunedHealVerification`, `frontierPersistenceEnabled` at the spawn, in `ChildFactories` and in `TrieNodeHealingCoordinator.apply`, `SNAPSyncConfig`'s fields for them, `StateIngest.healingFrontier`, `healingFrontierStorageOpt`, the multi-seed `startFrontierBFS` and the scoped and pruned gauges in `SNAPSyncMetrics`. Renaming `FrontierPersistence` (a fix cannot) goes on the same list.

- [ ] T051 [US1] Remove the completion gates (slice 2 review, vault V3 and R1: **not** under `pruning.mode = inmemory`, where the trie store is the node cache and FR-001 to FR-004 do not hold on disk; there the coordinator's full verification walk, unscoped and unpruned, with no frontier resume, stays the completion gate on every completion and every restart, and the pruned and scoped verifications and the frontier persistence stay off, as slice 2 set them):
  - `verificationPassComplete` and the full, scoped and pruned BFS (`TrieNodeHealingCoordinator.scala:1093-1160`);
  - the `VerificationBFSComplete` arms and `MaxConsecutiveStaleDiscards` (`:1164-1250`);
  - the BFS start on a re-pegged root that is already present (`:666-690`): with the marker present, that case is complete immediately.

  **[beacon] [forge]**
- [ ] T052 [US2] On Hash, route completion to `completeHealingWalkClean` (`HealingOrchestrator.scala:271-286`), and remove the controller trie walk (`SNAPSyncController.scala:853-870` → `HealingOrchestrator.scala:203-315`) as a gate. Fix `HealingForceComplete` (`:584`). *Carve-out (slice 2 review, vault R1):* under `pruning.mode = inmemory` the route to `completeHealingWalkClean` must still require the coordinator's clean full verification walk (T051's carve-out); the controller trie walk is no substitute to keep instead, since it does not descend into present hash nodes. **[forge]**
- [ ] T053 [US1] Move the full-trie walk into a background audit owned by the regular-sync side, reusing the `FrontierRebuildBfs` engine.
  - It walks the hash store at a recent root that is still present.
  - It throttles itself so it doesn't compete with import.
  - It can be disabled with `fukuii.sync.snap-sync.background-audit.enabled`.
  - Gaps go to `BlockImporter.resolvingMissingNode` (`blockchain/sync/regular/BlockImporter.scala:446`).
  - **[beacon] [forge]**
- [ ] T054 [US1] Replace the sample check in `PathToHashExporter` (`:14-29`; 1,000 leaves and 16 storage roots) with a root recompute over the exported hash store, or a much larger stratified sample. Choose by measured Mordor cost and record the choice in research.md. **[beacon] [vault]**
- [ ] T055 [P] [US1] Add the FR-014, FR-017 and FR-018 metrics and the `[HEAL-COMPLETE]` log line, following data-model.md "Metrics and logs", in `SNAPSyncMetrics` and `TrieNodeHealingCoordinator.scala`.
- [ ] T056 Delete or rewrite the specs whose mechanism is removed: `StaleVerificationWalkSpec` (this also retires flaky task #85), `PrunedHeal*`, `ScopedVerification*`, `SubtreeCompleteSeedingSpec`, `ForceCompletedStorageVerificationSpec`, `SNAPHealLoopSpec`. Confirm the ETC suites in research R-4 pass with **no assertion changes**. **[forge]**
- [ ] T057 Run all slice tests, then run `./scripts/fukuii-test essential` once. Do a dev-loop Mordor run against the local core-geth with JFR capture during healing, and record healing time and CPU against the T005 baseline. Open the slice-5 PR and request reviews from beacon, forge and eye.

**Checkpoint**: both schemes complete on "drained on the current root". The walk runs only in the background.

---

## Phase 7: Slice 6: cleanup and decision records (US5)

- [ ] T058 [P] Correct every "spec 009 moving-root delta heal" reference in code comments and logs (spec 009 is Amsterdam). Search with `grep -rn "spec 009" src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap`. (FR-019)
- [ ] T059 [P] Add supersede notes at the top of `specs/003-scoped-heal-verification/spec.md`, `specs/005-subtree-complete-verification/spec.md` and `specs/006-skip-redundant-verify-walk/spec.md`, pointing to spec 022. (FR-019)
- [ ] T060 [P] [US5] Write the ADR in `docs/adr/` recording the completion-rule change (it supersedes spec 006's byte-for-byte completion-parity invariant). In the same ADR, record the FR-020 decision: keep Hash for 0.11.0, with retirement under spec 018 DC-9.
- [ ] T061 [P] Write the short sync-direction ADR, the R-8 direction: #1671, then spec 018 flat execution reads, then spec 013 Phase 0. Include a staged mode for Mordor/ETC only, the measured Mordor data from 2026-10-28, and the unmeasured execute-from-genesis IO model with its 2–3× error band.

---

## Phase 8: Release gates (validation; no builds during these windows)

**Purpose**: the 0.11.0 gates on #1659, run from quickstart.md. All of slices 1–5 must be merged.

- [ ] T061a **Exercise the excluded path live, before any release or any Sepolia or Platåberget jar rotation (forge and beacon, slice 3 round 3).** A core-geth outage cannot do it (T038): it takes a peer that answers but cannot serve what is asked. Needed: on Mordor (Hash) one exclusion and one unservable code hash, reaching the real hand-off; on a Path chain the code wait, and an induced force-complete if it can be had. Some way to induce this has to be built for the run.
- [ ] T062 [US2] **Gate 1, Mordor fresh sync on public peers.**
  - Stop `coregeth-mordor.service` first, so it can't be discovered.
  - Use a fresh datadir; never `/home/dontpanic/mordor-data`.
  - JFR during healing.
  - Root at handoff equals the pivot `stateRoot`.
  - 24 h following the head, with `state_ondemand_fetch_total{cause=hole}` = 0 or each case explained.
  - Record SC-002 (time from storage-done to handoff).
- [ ] T063 [US1] **Gate 2, Sepolia fresh sync on public peers.**
  - Restart `sepolia-lighthouse`; it checkpoint-syncs.
  - Fresh datadir; never `/home/dontpanic/sepolia-data`.
  - JFR during healing.
  - SC-001: storage-done to handoff in under 12 h.
  - SC-003/004: root match, then 24 h following the head with no holes.
  - SC-005: no walk before handoff.
- [ ] T064 [US4] **Secondary: repair of the kept datadir.** Start the fixed build on `/home/dontpanic/sepolia-data` (no marker). Expect one repair pass, the marker stamped, then handoff. Record holes found and duration.
- [ ] T065 Get sign-off from beacon (Path), forge (Hash and the ETC suites), vault (write order) and eye (gates). Tick #1671 and the gates on #1659.

---

## Dependencies

- **#1672 → T001.** Timing is meaningless without it.
- **Slice 0 → slice 1 → slice 2 → slice 3 → slice 4 → slice 5 → slice 6.** Each slice is one PR merged before the next one starts.
  - Slice 6 tasks T058–T061 are independent and can be written at any time. They merge with or after slice 5.
- **Slice 5 needs slices 1–4.** It removes the safety net those slices replace.
- **Gates (T062–T064) need slice 5 merged.** T062 runs before T063: Mordor is shorter and exercises Hash.
- **Spec 020 sequencing (plan.md):**
  - 020 PR 3 and PR 4 land before slice 1 branches.
  - 020 PR 5's `-rewrite` is regenerated after slice 5 lands.

## Parallel opportunities

- **Slice 0:** T002–T005 are independent reads and writes.
- **Within each slice:** all test-writing tasks marked [P] can be drafted in parallel by different agents. Implementation is sequential, because the tasks share `TrieNodeHealingCoordinator` and `HealResponseProcessing`.
- **Slice 6 (T058–T061)** can be written while slices 1–5 are in review.
- **Builds are serial:** one sbt JVM at a time, through the flock.

## Implementation strategy

- **MVP = slices 1 + 2.** The invariant holds for everything healing writes, under crash. Even before the gate is removed, this shrinks the holes the verification walk finds after each re-peg to about zero.
- **Slices 3 + 4** close the remaining sources of holes and the upgrade path.
- **Slice 5** delivers the user-visible change: completion in hours, not never.
- **After each slice:** one dev-loop Mordor run against the local core-geth (quickstart §0), to catch regressions in minutes, not days.
