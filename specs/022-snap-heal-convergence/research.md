# Research: Spec 022, SNAP Heal Convergence

**Sources:**
- impact analyses by `beacon` (ETH/Path), `forge` (ETC/Hash) and `vault` (storage), all made on 2026-10-28 against staging `5a6b5b840` (`/home/dontpanic/work/staging-ro`);
- the #1671 code audit;
- upstream source checks of go-ethereum, Nethermind and Besu.

**Tags:** [V] = verified at file:line or from upstream source; [I] = inferred.

Paths are relative to `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/` unless stated otherwise.

## R-1. How the reference clients heal

- **go-ethereum [V].**
  - `Syncer.Sync` ends when there are no tasks, nothing in the heal scheduler, and nothing in the runner.
  - `trie/sync.go`:
    - `nodeReqs` is keyed by path;
    - `ProcessNode` adds one dependency per missing child;
    - `commitNodeRequest` commits a parent when its dependency count reaches 0, then recurses upward;
    - `Commit` writes the membatch in one batch;
    - dispatch priority is `len(path)<<56`, deepest first;
    - `hasNode` on Path compares the hash at the position and deletes a mismatch;
    - `children()` deletes dangling intermediate positions.
  - `core/state/sync.go` adds the storage trie (`AddSubTrie`) and the code (`AddCodeEntry`) as dependencies of the account leaf.
  - A pivot move starts a new heal from the new root and keeps disk progress.
  - Path `Enable(root)` only checks the root, then regenerates the snapshot in the background.
- **go-ethereum account download [V]** (`eth/protocols/snap/sync.go`, `forwardAccountTask`):
  - it does not advance past accounts with `needCode` or `needState`;
  - for `needHeal` (chunked storage) it calls `genTrie.delete(hash)`, "to ensure that all nodes along the path to the incomplete storage trie are cleaned up";
  - `gentrie.go:214-241` deletes every prefix, including the leaf's own position.
- **Nethermind [V]** (`TreeSync.cs`): done when the root exists; children are saved before parents through `_dependencies`; a pivot move calls `ResetStateRoot`.
- **Besu [V]** (`SnapWorldDownloadState.checkCompletion`): done when all queues are empty; a pivot move calls `reloadTrieHeal()`.
- **No reference client runs a full-trie verification walk as a completion gate.**
- **Rejected, the user's original idea of re-verifying only what changed since an older root:**
  - on Path, the old root's nodes are overwritten, so the old trie cannot be reconstructed;
  - working out the changes needs per-block touched keys: BAL, which exists only from ETH Amsterdam on (geth snap/2), or re-execution;
  - walking top-down from the new root with a hash check at each position computes that difference implicitly.

## R-2. Where fukuii breaks "present ⇒ complete"

| ID | Evidence | Notes |
|----|----------|-------|
| V1 | `healing/HealResponseProcessing.scala:151-167` writes a node first; children are discovered at `:193`. A re-peg (`TrieNodeHealingCoordinator.scala:608-630`) flushes parents, then clears `pendingTasks`, `pathAliases`, `activeRequests` and the frontier. `HealingForceComplete` (`:567-578`) does the same. [V] | Both schemes. The main cause. |
| V2 | `account/AccountTrieAssembly.scala:212-213, 258-260` commits every account. Abandoned list: `StorageRangeCoordinator.scala:754-784`, cleared at `:776`. Skip: `StorageOrderedApply.scala:158-161`. Root mismatch accepted: `:123-148`. [V] | Both schemes. The account trie is built before storage starts, so geth's insert-time exclusion can't be copied. |
| V3 | `pathAliases` (`HealResponseProcessing.scala:98-104`) is cleared on a re-peg. `discoverMissingChildren` (`:347, :365, :382`) deduplicates by hash without recording an alias. The storage-root seed at `:381` deduplicates by hash, **so two contracts with an identical storage root lose the second owner's whole storage trie** on Path. [V] | Path. |
| (cleared) | Storage force-complete on Path: `SnapPathTrie.reset()` deletes the right-boundary ancestors, including the root position (`SnapPathTrie.scala:87-89, 147-152`). [V] | The "mosaic" comment (RUN10, 888157b2, 0.7.0) predates the Path presence check (`a9abc4e78`). |
| Code | `SNAPSyncController.scala:836-846` `HealedCodeWaitTimeout` finalises with code missing. It is safe-fail only because missing code throws (`InMemoryWorldStateProxy.scala:252`). [V] | Both schemes. |

## R-3. Storage write paths and atomicity (vault)

- **Path heal [V]:** one `dataSource.update` per node (`PathNodeStorage.scala:55, 87`).
- **Hash heal [V]:** `rawNodeBuffer` → `storeRawNodes` → `ArchiveNodeStorage.update`, CF `n`. Flushes hold at least 1000 nodes (`rawFlushThreshold`, `TrieNodeHealingCoordinator.scala:338`) and are not aligned to chains.
- **Atomicity [V]:** `RocksDbDataSource.scala:257-276` puts every `DataSourceUpdateOptimized` passed to one `update()` into a single WriteBatch, across CFs. WAL is on everywhere. The default is `setSync(false)`; `updateSync` fsyncs. `max-total-wal-size` is 512 MiB.
- **#1549 is live on the heal path [V].** `flushRawNodesAsync` (`FrontierPersistence.scala:185-206`) clears the buffer before the Future writes. If the write fails, `FlushComplete` is never sent, `flushing` stays true, and the nodes are lost. The earlier fix covered storage download only (`StorageOrderedApply:448`).
- **WAL recovery [V, slice 4, vault V10]:** `RocksDbDataSource` sets no `walRecoveryMode`, so RocksDB's default `kPointInTimeRecovery` applies: replay stops at the first inconsistent record, which is the prefix-consistent tail the bullets below assume, and a Path batch spanning two column families is one WAL record. No code change.
- **Crash windows after D3 [I]:**
  - a kill between chain batches is harmless;
  - a SIGKILL keeps WAL records already handed to the OS;
  - power loss drops a prefix-consistent tail of the WAL;
  - write the completion flag with `updateSync`.
- **Prefix delete [V]:** `deleteAccountNodesByPrefix` and `deleteStorageNodesByPrefix` have no callers. They scan the whole CF, and HP-encoded keys don't preserve prefixes. A per-account storage wipe can use `deleteRange(acct, acct+1)`, but `deleteRange` is not part of the batched update API.
- **Held-node size [I]:** about 450 B each. A 128 MiB cap is about 250k nodes, about 3% of the 4 GB Sepolia heap. The RocksDB budget from spec 015 is native memory and separate.
- **Expected held set with deepest-first dispatch [V config, I arithmetic]:** `healing-batch-size=128` × `healing-concurrency=16` = 2,048 in flight. Times 21 levels, that is about 43k nodes (20–45 MB). Under FIFO dispatch it approaches the size of the trie, hence D1.

## R-4. ETC safety argument (forge)

- Every healed node is checked against its keccak (`HealResponseProcessing:140-150`), and Hash storage is content-addressed, so a node can be missing but never wrong [V].
- A missing node throws `MissingRootNodeException` (`MptStorage.get`) and is never read as "absent account" [V].
- Block import checks the state root on every block, and `finalizeSnapSync` checks the root against the pivot header [V].
- So a wrong "complete" produces an on-demand fetch or a stall: a liveness fault, not a chain split.
- **Must stay green with no assertion changes:**
  - `testEthereum` (ETC-filtered), `testVM`, `*ECIP1017*`, `*OlympiaOpCodes*`;
  - `BlockExecution*`, `BlockValidation*`, `StxLedger*`;
  - `ETCForkComplianceSpec`, `ChainConfigMatrixSpec`, `Ethash*`, `MESScorerSpec`, `ForkIdSpec`, `ConsensusImplSpec`;
  - `SnapHashTrieSpec`, `MerkleProofVerifier*`, `StateIngestDurabilitySpec`, the checkpoint specs.
  - *The real suite names (checked 2026-10-29, slice-2 review forge N10 / eye F11):* no suite is named `*OlympiaOpCodes*`; the ETC opcode set is covered by `*Olympia*` (14 suites, `PreOlympiaForkComplianceSpec` and `PicklerOlympiaSpec` included) and `EvmConfigEtcForkSelectionSpec` (which picks `OlympiaOpCodes` by block). `MESScorerSpec` is a file whose suites are `MESSConfigSpec` and `ArtificialFinalitySpec`, run with `MESSConfigParsingSpec` and `MESSIntegrationSpec`. `*ECIP1017*` is `ECIP1017EmissionScheduleSpec` (unit) and `ECIP1017Test` (IntegrationTest module). `MerkleProofVerifier*` is `MerkleProofVerifierSpec` and `MerkleProofVerifierPhase3Spec`; the checkpoint specs are `CheckpointArchiveSpec`, `CheckpointDownloaderSpec`, `CheckpointExporterSpec`, `CheckpointImporterSpec`. `testEthereum` needs the test corpora; without them it does not run, and is recorded as not run.
- **Hash-scheme hazards found [V]:**
  - `HealingForceComplete` on Hash (`:584`) sends `StateHealingComplete` and relies on the controller walk. Once the walk is removed, that would be a false completion.
  - The checkpoint exporter writes parent-first (`CheckpointExporter:296-302`). The importer writes as it streams and checks the CRC only at the end (`CheckpointImporter:148-156`). On failure, sync falls through to SNAP on the same datadir (`SyncController:1510`). `clearDoneOnStart` (`:1516`) has the same issue.
- **Pruning [V]:**
  - basic: re-emitted nodes inflate reference counts, which leaks space but never deletes a live node;
  - in-memory: `persistCache` writes one batch, and nothing is durable before `forcePersist`, so fault tests must run under both modes.
- **Three walks on Hash today [V]:**
  - the coordinator's BFS (`:1093-1160`);
  - the controller walk (`SNAPSyncController:853-870` → `HealingOrchestrator:203-315`);
  - `validateState` (`StateValidationModule:195-230`), which is skipped when `healingValidatedRoot` is set.

## R-5. Restart, marker and frontier

- Path never calls `unpersistFrontier` (only Hash does, `FrontierPersistence:176, 203`), so Path frontier entries are never removed [V].
- CF `g` holds three kinds of record [V]:
  - frontier entries (32 B);
  - `CompleteMarkerKey`;
  - spec 005 subtree-complete records (33 B).
- **Marker placement:** AppState `s`, next to `SnapStorageDoneStorage`, written with `updateSync`. "Marker present" must not mean "complete".
- **Kept datadir:**
  - it holds V1/V3 holes with no record anywhere;
  - abandoned accounts can perhaps be recovered from the contract-storage task file plus the absence of a `SnapStorageDone` marker. Markers are deliberately kept on force-complete (`DownloadSupervisor:757-759`), but forge reports that they are cleared at `DownloadSupervisor:760` [conflict: check in Slice 0];
  - the datadir therefore needs one full repair pass.

## R-5c. What the walk costs once the records are gone (slice 4)

**Verified in the code, not measured at scale. The Mordor dev loop will give the first number.**

- **The walk is a full descent [V].** `FrontierRebuildBfs` enqueues every hash-referenced child of a present branch or extension, and the storage root of every account leaf; inline children cannot hold a hash reference; the bounded visited set evicts, so it re-walks and never truncates. Once the subtree records are gone nothing stops it. **It does not read code [V].**
- **The Hash controller's trie walk is not one [V].** `StateValidator.walkAccountTrieDFS` reads a present `HashNode` and stops ("proven subtree"). `findMissingNodesStreaming` and `findMissingNodesWithPaths` are depth 1 under the root. Hence the stamp's proof is the coordinator's walk.
- **The pruned oracle saved little [V].** Records were written only for heal-closed subtrees, and only where every child was recorded too. Nothing in the download phase wrote one, so on a heal after a download nearly all present nodes were descended anyway: it saved the healed part, and only on Hash.
- **The scoped verification and the resume saved more, but only after a restart [V].** A completeness marker was set by a rebuild walk, or at a completion. A fresh sync's first gate had none, so it walked in full then too. After a restart, a marker let the resume skip the rebuild and let the gate walk only the healed subtrees.
- **So:** a fresh sync's walks cost what they did, less the healed-subtree saving. A restart during healing costs what a fresh start's walk does, where a persisted frontier and a marker used to spare it, and the verification after it walks the whole root too: up to two full walks where a restart used to cost one scoped one. Each dirty pass is a full walk. Scale, from this spec's own figures: ETC mainnet 16-20 h a walk; Sepolia 787 M nodes, about 6 times that many (T057 measures it). All of this ends at slice 5 for marked datadirs. A release must not ship slice 4 without slice 5 on a chain where a walk takes hours and a node may restart.

## R-5a. Where abandoned storage accounts are recorded (T002; vault, 2026-10-28)

**Verified unless marked inferred. Paths under `blockchain/sync/snap/` unless stated.**

- **The task list.** `<datadir>/snap/fukuii-contract-storage-*.bin` holds 64 bytes per contract account (accountHash ‖ storageRoot). It is written by `ContractWorkCarry:172-178` and `AccountRangeCoordinator:280`. Its pointer and count are AppState keys (`db/storage/AppStateStorage.scala:567-568`, written at `controller/DownloadSupervisor:693-697`). It is **not** in CF `g`.
- **It survives force-complete and healing.**
  - Neither the force-complete path (`StorageRangeCoordinator:754-785`, `DownloadSupervisor:765-780`) nor the finalisation sweep touches it.
  - The pointer is blanked only by `restartSnapSync` (`SyncLifecycle:342`) and by unusable-file recovery (`SnapResumePlanner:325`).
- **The `:757`/`:760` "conflict" is not real.** `:760` is in the clean-completion arm, `case StorageRangeSyncComplete` (`:754-763`).
  - Force-complete (`:765-780`) clears no `SnapStorageDone` markers.
  - Clean completion **does** wipe them (`:760`), and so does an account phase that starts with no carried task files (`SnapResumePlanner:742`).
  - The markers live in AppState `s` (`SnapStorageDoneStorage.scala:9-16`), so clearing `g` doesn't touch them.
- **The gap this exposes for D5:**
  - Give-up, max-empty skip and root mismatch all end the storage phase *cleanly* (`StorageResponseProcessing:75-98,303-316`, `StorageOrderedApply:433-439`). Then `:760` wipes the only durable trace of which accounts finished.
  - `abandonedAccounts` (`StorageRangeCoordinator:297`) is in memory only, and doesn't include max-empty skips.
  - Missing code can't be derived from the markers at all.
  - **So the excluded set must be persisted at each decision point, as it happens.** Deriving it later from the task file minus the markers doesn't work.
- **Where to store it [I].** A sibling key prefix in AppState `s`, e.g. `SnapExcluded/<accountHash>` → reason byte, written with `updateSync` and with its own clear policy. Not in `g` (cleared by D6), and not reusing the markers.

## R-5b. Tests that assume the persisted heal frontier (T003; vault, 2026-10-28)

`src/it/scala` has none. In production the frontier is on (`sync.conf:152`), and the store also exists whenever `pruned-heal-verification` is on. Paths below are under `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/`.

- **As done in slice 4** (the mechanism left then, not at T056):
  - deleted: `PrunedHealVerificationSpec`, `PrunedHealFallbackSpec`, `PrunedHealCrashSafetySpec`, `PrunedHealParitySpec`, `SubtreeCompleteSeedingSpec`, `ScopedVerificationParitySpec`, `ScopedVerificationFallbackSpec`, `ScopedVerificationObservabilitySpec`, `TrieNodeHealingScopedVerificationSpec`, `TrieNodeHealingScopeCaptureSpec`;
  - rewritten: `HealingFrontierResumeSpec` ("a restart walks from the current root, and CF `g` makes no difference"), `FrontierRebuildBfsSpec` (a recorded subtree is descended), `HealWalkSingleFlightSpec`, `DecoupledHealSafetySpec`, `CleanRebuildEarlyCompletionSpec`, `HeldNodeSetSpec`, `HealVerificationSpec`, `FrontierPersistenceSpec`, `FrontierPersistenceOrderSpec`, `HealResponseProcessingSpec`, `HealFlushFailureSpec`, `CodeDependencySpec`, `TrieNodeHealingDispatchTableSpec` (`RestartResumeVerification` went). A completeness assertion became the coordinator's clean walk root where one still applied.
- **Keep:** the format pins `db/storage/HealingFrontierStorage*Spec` and `SnapFrozenFormatsGoldenSpec:234,366-389`. The storage class is still needed to clear `g`.
- **Wiring:** `SNAPSyncControllerPinSpec:100-160,320-335` and `ChildFactoriesSpec` pin the spawn's four settings (`scopedHealVerification`, `scopedHealMaxPaths`, `prunedHealVerification`, `frontierPersistenceEnabled`) and `stateIngest.healingFrontier`. Slice 4 keeps them, unread, so those pins hold; they go together, in a `# pins:` change. It edits one pinned assertion: the spawn no longer clears a marker under in-memory pruning (T044).
- **Other writers to `g` beyond `persistFrontier`/`unpersistFrontier`:**
  - `markComplete` (`TrieNodeHealingCoordinator:843,1124`);
  - `clearComplete` (`FrontierPersistence:105`, the spawn under in-memory pruning, and a restart's unreadable-frontier branch);
  - `markSubtreeComplete` (`FrontierPersistence:162`, `TrieNodeHealingCoordinator:1119`).

  All of them must stop for T040 to hold. Scoped verification gates on `isComplete` (`:1142`), so it goes dark with them.
- **The `g` column family stays listed** in `Namespaces.nsSeq` (`db/storage/Namespaces.scala:48`); RocksDB won't open a database with an unlisted column family. Clear it with `deleteRange`; never delist it.

## R-6. FR-020: what the Hash scheme still does (forge)

**Finding [V]:** in fukuii, the scheme only changes how SNAP writes state during sync. After sync, every component reads the hash-keyed store on both schemes:
- block execution (`StateStorage.getBackingStorage`);
- all three pruning modes;
- recovery actors (`BytecodeRecoveryActor:314`, `StorageRecoveryActor:222`);
- `eth_getProof`;
- checkpoint export (`CheckpointExporter:274`) and import (`CheckpointImporter:85`).

`PathToHashExporter` copies Path data into that store.

**Hash-only code [V]:**
- v1 archive (`CheckpointArchive:25, 43`);
- SNAP branches: `HealResponseProcessing:153, 353, 370`; `FrontierRebuildBfs:179, 472-509`; `StorageOrderedApply:104`; `StorageRangeCoordinator:1324`; `AccountTrieAssembly:302`; `ContractWorkCarry:118`; `TrieNodeHealingCoordinator:286, 584`; `SnapResumePlanner:104-115`;
- enforcement: `SyncController:466-470` requires ETC to run Hash.

**Criteria, moving ETC SNAP to Path today:**

| Criterion | Path vs Hash |
|-----------|--------------|
| Sync time | Worse: extra export scan |
| Disk during sync | 2–3× (path + flat + hash) |
| Import read cost | Identical |
| Pruning | Identical |
| Migration of existing nodes | None |
| Archives | Unaffected |

**Recommendation (a):** keep Hash for 0.11.0, behind `StorageScheme`. Retiring both the scheme split and the separate Path layer is spec 018 DC-9 (user decision 2026-10-17: ETC re-syncs from a checkpoint onto the unified layout). It needs a v2 archive or a root walk to assign paths.

**Re-evaluate** only if Mordor measurements show the export cost is small **and** Hash V2 seeding turns out much larger than estimated.

## R-7. Tests

- **V1:**
  - a re-peg before the children arrive: assert nothing held is on disk, and that completion waits;
  - kill points (after a node is held, mid-chain, mid-re-peg), followed by an audit that finds no present node with an absent child;
  - #1549: a throw inside the write leaves the buffer intact and `flushing` reset;
  - the held bound and pause: assert no deadlock, and that children still dispatch when the cap is reached.
- **V2:** abandoned, skipped, root-mismatch and missing-code accounts are each reached from the current root, on both schemes.
- **V3:** two owners with an identical storage root; an alias lost across a re-peg.
- **FR-008:** a dangling delete under an extension node; a position whose stored hash mismatches.
- **Convergence:** a simulated chain re-pegging every N responses still completes. Each re-peg costs about (diff × depth).
- **Handoff:** the exported hash-store root equals the pivot root.
- **Harness:**
  - `EphemDataSource` with a failing or recording fake for batching and ordering;
  - a real-RocksDB reopen test;
  - a child-JVM `kill -9` test for true crash safety;
  - fault tests under both basic and in-memory pruning.

## R-8. Sync-direction context (not in scope; feeds the ADR)

- Staged-sync research: execution reads only through the trie today (`BlockExecution.scala` ~375; `InMemoryWorldStateProxy.getAccount` ~206; `flatSlotStorage` is `None`) [V].
- Spec 018 F3 owns flat-state execution reads. Spec 013 owns flat-state snapshot files.
- Execute-from-genesis on this host is an unmeasured IO model (2–3× error band): Mordor about 2–5 days, ETC mainnet about 1–3 months, Sepolia about 2–4 months.
- Direction: #1671 first, then 018 flat reads, then 013 Phase 0. A staged mode only for Mordor/ETC, after flat reads exist.
