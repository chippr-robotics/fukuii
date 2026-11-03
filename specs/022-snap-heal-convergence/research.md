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
| V2 | `account/AccountTrieAssembly.scala:212-213, 258-260` commits every account. Abandoned list: `StorageRangeCoordinator.scala:754-784`, cleared at `:776`. Skip: `StorageOrderedApply.scala:158-161`. Root mismatch accepted: `:123-148` (no longer: R-5d). [V] | Both schemes. The account trie is built before storage starts, so geth's insert-time exclusion can't be copied. |
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

## R-5d. Do the download phases write under the invariant? (slice 5 gate, 2026-10-31)

Read-only traces at staging `5c9e3c796`: forge on Hash, beacon on Path. A review call timed out, so beacon's had no second reader, though forge's independent trace reaches the same three holes. The user confirmed H0 and H1 in the code; PR 5a's tests reach all of them.

- **Inside each trie: yes, on both schemes, for one run [V].**
  - Hash: StackTrie emits children first, each flush is one atomic write, nothing deletes, and a seam's real node is never written, so a top-down walk meets it absent. This holds across a crash and a pivot change. It assumes RocksDB's point-in-time WAL recovery (checked, R-3).
  - Path: writes keep present-implies-complete inside each trie. An in-run pivot change does not overwrite descendants: the account trie is one pass over fresh ranges, and storage chunks verify against the task's root.
- **At the account-leaf-to-storage edge: no, with no crash and one pivot [V].** A leaf is present over an absent storage root by design, tracked by the task file, the done markers, the excluded set, the replay filters and the sweep. That bookkeeping had three holes, on both schemes. Today only the full walk hides the first two, and it never covered the third.
  - **H0:** `identifyContractAccounts` made the storage entry and task only for an account with code. Initcode that writes storage and returns empty code leaves a leaf with no code and a real root, and it was never fetched. `storageRecoveryDone` is set at a clean finalisation, so the recovery scan does not help.
  - **H1:** a storage completion with no trie returned the claimed root, so `rootVerified` was true and a done marker staged, with no excluded entry and the root never written. A lone absence reaches it, and no proof is checked: forge's trigger is a peer past its snapshot window; beacon's is a non-geth or hostile peer, since geth sends no proof for a zero-origin empty range [I, from memory of geth]. The other route is a pivot refresh that wipes the trie (`StorageRangeCoordinator`'s `StoragePivotRefreshed`) before an empty sub-range comes back: it keeps `accountSubtaskCounters`, so the last chunk completes the account. A test reaches this one. An empty slot set in a batched response reaches the same branch. A sparse account with a trie was always safe: its commit computes a real root.
  - **H3:** `codeHashBloom` at 3M entries and 1e-4: a false positive is a hash never queued nor written. Its comment said the recovery scan catches that, but `bytecodeRecoveryDone` is set at a clean finalisation, so the scan is skipped. Pre-existing, and no regression from T051: the walk never covered code. Read off the filter: 57.5 Mbit and 13 hashes, a rate of 0.87 at 20M, and about 7 MB, not the 4 its comment said. **This was not a small loss on Sepolia.** Its logs show 13,484,554 unique code hashes at 97.9% of the keyspace through this filter, at a rate of about 0.53 by then. From the ideal-hash formula that implies about 16.3M true unique hashes, so about 2.8M (17%) never queued: an estimate, not a measurement, and import's bulk recovery fetched what it needed. The kept Sepolia datadir has this.
- **Crash class, Path only [V beacon, one reader].** Spec 010's sentence is still true. A wrong child takes a restart that downloads a region again over existing nodes at another root, a state returning to an earlier value, and a second interruption between rewriting a descendant and rewriting the node. Rare, not excluded, and undetected without the walk. Today `resumedStaleCursors` forces a walk for it. The accounts-complete flag set with the file pointer not persisted is such a restart (`DownloadSupervisor`'s two arms). So is a supervisor restart of the account coordinator, which starts again from the launch's cursors and root, and which the planner never sees (found in PR 5a, not in either trace).
- **Candidate rules:** stamping only after download, and ignoring the marker until `SnapSyncDone`, close none of H0, H1, H3. The second as worded voids T051 for every first sync. Beacon's scoped version is what 5a does: withdraw the marker when a Path account phase starts over existing nodes.
- **Storage cursors:** `writeStorageCursor` is write-only. Nothing reads `storageCursors`, so resume restarts an account's storage from slot zero: mid-account resume does not exist, despite the comment at its call site. It matters for mainnet's largest contracts. Accepted as a follow-up (T050a); spec 010's status lists it as out of scope, and that stands.
- **Not checked by the traces:** `MerkleProofVerifier` internals, deferred merkleization (checked in 5a: H1 stays open there; T050a), held nodes beyond the docs, and import's bulk code recovery, which H3 now leans on as its backstop.
- **F4:** download-written storage roots are safe on Hash, since the root is written last. No scoped descent is needed for them, and what remains is outside download (T050a).

**Corrections to the above:**
- R-2's V2 says a root mismatch is accepted. Since T033 it gets no marker and an excluded entry, and since 5a so does a completion with no trie.
- R-5a's task file holds an entry for every account with code and, since 5a, every account with a storage root. Its count and the bytecode file's differ, and nothing assumed otherwise but the one shared counter in `identifyContractAccounts`.
- R-5a's list of what ends the phase cleanly with an account incomplete gains a lone absence, which until 5a looked complete and left no trace at all.

## R-5e. What a run does when it knows it has lost track: why not a hard stop (PR 5b, 2026-10-31)

T050a had several failures "become a hard stop" (a task file that cannot be read, a sweep that times out), so that the walk could go. **User's decision: no.** A hard stop halts a node that could finish. At Sepolia's size, about 16M code hashes and a sweep of about 3.5 h, a false timeout would stop a healthy run. T050a's own rule is "settled, or the walk stays for that case", and there is a cheaper way to make the walk stay: the run withdraws the marker, synced, and T051 skips no walk where the marker does not stand. The walk never reads code, so the same write owes the recovery scan, which withholding the recovery flags brings on at the next start. The node keeps going, and the cost of being wrong is one walk and one scan.

**"The walk covers trie and storage" holds only if a clean walk completes.** A run that loses track usually still has one ahead of it. But a walk over holes that outlasts a re-peg does not finish (T050a), and a lazy hand-off has no walk behind it at all: it leaves trie and storage to the recovery scan, which looks only as far as it can read, and to the audit. That is why the owed key withholds `storageRecoveryDone` too.

**It rests on:** every site knows it has lost something; the write lands before the phase ends over it (a failed write holds the phase's end); and nothing past 5d takes the marker's word from the start of a run, since a run can now lose it mid-run (tasks.md T050a).

**Max restarts, traced at `e903167bc`:** four failures, each within about 5.5 s of the restart before it (`restartWithBackoff`'s `resetBackoffAfter` is the mean of its 1 s and 10 s, read off the Pekko 1.6.0 jar), so a failure that is not going away. The controller had no watch, and `CoordinatorHandles` said so. It kept the dead ref, so its 30 s asks timed out and read as all zeros. Storage: `isTimeoutResponse` restarts the stall clock, for ever. Account: `accountRangeArms` ignores zeros. Bytecode: 10 minutes, then a force-complete to dead letters, each tick. Healing: the 1 s tick goes to dead letters and no fetch happens, though on Hash the controller's own walk can still end it clean. No false claim comes of this, since no phase ends, and a process restart replays from the files. So it was a silent hang, which a red run of `SNAPCoordinatorGoneSpec` shows: 120 s of clock, nothing. Dormancy is the existing way out: no phase flag moves, and the wake's `startSnapSync` replays the task files minus the done markers, so no sweep is needed. Not a restart of the sync, which would clear the phase flags on disk, and not a force-complete, which would need an arm per phase and has nothing to send it to in the account phase.

**The scan that paid the debt, traced and measured (5b review, 2026-10-31).** `CombinedRecoveryScanActor` turned any failure into empty gap sets and the controller wrote the flags, so a hole, or an absent root, settled the debt with nothing scanned. Mordor run U3 on the 5b jar, a clean Hash datadir under default `basic`/64 pruning: `logs3/fukuii.log` lines 111-136 show the next start's scan fail in 6 ms with `MissingRootNodeException` on pivot root `e346a4ce`, report 0 gaps, and the flag go true. The node had stopped about 3 minutes after its hand-off. The pivot sits behind the head, so catch-up had imported 87 blocks past it almost at once (`logs2`: pivot 17302069, last import 17302156), and pruning took the root.

**Why the pivot's root was never needed.** It was kept at a start on purpose (df5cfc456: "it runs right after SNAP, when that trie is fresh, and its storage phase needs the pivot's storage tries"). Neither holds. A same-process hand-off goes straight to regular sync (`SnapSyncFinalized` in `runningSnapSync`), so recovery runs at the next start at the earliest, or on an escalation from import. And the storage phase needs only that scan and download agree, which they do by construction: the one root goes to the scan and both download actors, `StoragePeerDispatch` asks peers at it, each chunk is checked against its task's root, and the storage root in a task came from an account leaf under that same root. `StorageRecoveryActor` already rolls off to a recent root when peers no longer serve this one. A contract changed since the pivot has a new storage root at the best block, and nothing reads the old one again. Bytecodes are fetched by hash. So the best root covers what the pivot's does for this scan, and contracts created since, and peers are likelier to serve it. Right after a hand-off best is the pivot, so it is the same root.

**What keeps the root stable, and what does not.** Regular sync is stopped through the scan and the downloads (`runningCombinedScan`, `runningRecovery`). The Engine API is not: `newPayload` executes on a canonical parent with no look at sync state, and `forkchoiceUpdated` moves the best block, so on a PoS node with a consensus client feeding the very next payload the best root can move under a scan (a node behind just answers SYNCING). That path does not prune (`saveBlockState` is only on the import paths, and `abandonBlockStates` deletes nothing), and nothing else prunes until regular sync's first saved block, so the walk is unharmed. So a scan under way keeps its own root while that root can be read (`recoveryScanRoot`), and a restart mid-scan resumes rather than starting hours of walking again: re-rooting at the best block would have lost that on exactly the nodes with the longest scans (beacon, closure check). A failed scan's progress resumes only until regular sync has pruned its root, under `basic` pruning about 64 blocks, after which the next start scans afresh on the best root; archive and in-memory pruning keep the root, so there a failure that keeps failing is paid at every start up to the node that fails. A bulk code recovery drops any such checkpoint and scans the best root, since it is after code the best state needs.

**Only an absent node is a hole.** `MissingNodeException`, not its parent `MPTException`, which `parseMpt` also throws for a node that does not decode. A hole below the root is counted and walked around; a node that does not decode, or a store that fails, is a failure of the scan. With the root absent there is no trie to split, which is not an empty one, so that is a failure too.

**Since PR 5c (decision 14; R-5f)** a start with the audit on runs no scan: the audit's pass pays the flags. All of the above holds with the audit off, and for a bulk recovery, the backstop that remains.

## R-5f. The background audit: what T053 as written did not fit (PR 5c, 2026-10-31 and 2026-11-01)

Design checked by beacon, forge and vault before it was built; the decisions are in tasks.md T053 and `.local/docs/continuations/s022-slice5c.md`. What they rest on:

**Import's on-demand path cannot be the audit's.** `resolvingMissingNode` holds import until the fetch returns (`BlockImporter.scala:446`). `StateNodeFetcher` has one slot: a fetch for another hash abandons the one in flight. An exhausted reply counts towards `StuckEscapeThreshold`, then `RegularSyncStuck`, then a SNAP re-sync. So a background walk feeding it would block import, starve its fetches, and could trigger the re-sync it is there to make unnecessary.

**Fetch isolation needed one thing the design check did not have.** `PeerRequestHandler` filters a reply by request id only for `HasRequestId`, and the four SNAP messages had the field and not the trait. A handler took the first such message from its peer, whoever it answered. Harmless with one requester after SNAP. With two, a reply to the audit's fetch completes import's handler on the same peer, reads there as a wrong hash, burns import's one fallback root, and blacklists an honest peer in the shared blacklist. Each `PeersClient` has its own handlers, so a private blacklist does not reach that. Only `PeerRequestHandler` matches on the trait, and every peer echoes the id (snap/1), so the four classes now extend it, pinned by `PeerRequestHandlerSpec`.

**`saveNode` is not safe beside import.** It is a blind upsert at one reference, outside `commitLock` (`StateStorage.scala`, `FastSyncNodeStorage.scala`). While import runs that can reset a live node's count, and prune then deletes a node the head needs. Hence `repairNode`, with the check at write time, under the lock, since the node may arrive between the walk's proof and the write. The count is pinned high because a repaired node's real count cannot be known. 2^30 and not `Int.MaxValue`: `incrementReferences` adds unchecked, and a wrapped count is negative.

**The raw read.** `ReferenceCountNodeStorage.multiGet` throws for the whole batch on one entry that does not unwrap. `RocksDbDataSource` had `fill_cache=false` for a single get only. `multiGetOptimized` holds the read lock throughout and a queued writer stalls behind it, hence batches of at most 256.

**A long walk over a pruned store.** Under `basic`/64 a root's changed nodes go 64 blocks on, about 13 minutes on Sepolia; seen live on Mordor, a root gone 3 minutes after hand-off. A pass takes days. So absent under a walk's root is mostly pruned, and coverage has to go by position, rolling forward, with an absent verdict believed only if the head's root held across the resolve. (First built on the block number and a pruning horizon, which a same-height reorg walks through as a false hole. An unchanged root says more: no block saved, so nothing it reaches pruned.) `BackgroundAuditWalkSpec` prunes under a walk with the real reference counts and finds no false hole.

**No dedup by storage root.** A root deduplicated by hash and then rolled forward at one account loses the other's coverage. Code dedups, since code is by hash and not by position.

**A clean pass is not a root recompute** (decision 11). Induction holds per root, and a pass spans many. The exporter's keccak check is trivially true, because it keys by keccak. So T054 keeps a sample at the hand-off, by stratum, and SC-003's evidence is that sample plus import.

**T054's new strata report, and do not stop the hand-off.** Accounts and storage roots throw on a miss, as they always have. The larger strata do not: the path store can hold what another root wrote (the exporter's own doc calls those harmless), and a lazy hand-off can leave a hole. A throw there would escalate to a SNAP restart for a fault the audit is there to find, and put back the gate the spec removes. **Cost not yet measured:** to be recorded here from a Mordor Path run (`[PATH-PUBLISH] done` gives the sample's time apart from the export's). The account stratum went from 1,000 to as many as 4,096, so it no longer throws on a single miss, which would be `PathPublishFailed`, `HealingImpossible` and a SNAP restart over what the storage strata call harmless. It counts, and throws past more than 1% of the sample and at least 16. An absent root still throws. The storage-root stratum re-reads the export's own writes, so its log says present, not verified.

**The audit in place of the scan at a start (user, 2026-11-01, decision 14).** A flag's only reader is that arm, so with the audit on the scan is skipped for every reason a flag is down, and the audit's pass sets them, clean or not. A flag left down over a matching anchor would be paid by nothing, so a flag down also owes a pass. With `do-snap-sync = false`, where no start ever ran the scan, the debt now gets paid. A bulk scan stays the backstop when import hits code that really is missing, since the audit fetches one at a time: a block remains where something is actually missing, and no block where nothing is. T050a's estimate of about 3.5 h no longer applies at a start with the audit on.

**A provably bad node goes after three failed passes (user, 2026-11-01, decision 15).** Forge's case: it turns a silent wrong read or a permanent stall into fetch, exhaust, re-sync. The guards are what make it safe to do unattended. A read at the delete, so a transient fault behind the audit's verdict cannot delete a good node. A look from the current root, so a position it no longer needs stays. Both under `commitLock`, so no commit moves the root part way, and a read again after the look, because that lock covers block commits and `saveNode` takes none. A fetch timer books nothing, since a restarted fetcher would otherwise book three passes with no peer asked. And three separate passes: a pass number, persisted with the record and the cursors so a restart resumes the same pass, and a timestamp, since a restart in a backoff starts the next pass at once. A node that passes keccak is never deleted, decoding or not: its bytes are what its parent committed to, and peers would serve the same. That case is cut at one repair per pass, or serving it would go round for good.

**Two traps in the code the audit landed on.** `SnapSyncProgressStorage.clearAll` decoded every value in its namespace as JSON, and raw cursors there would have made it throw, so it now reads keys alone. And `logging.logs-level` is ERROR with no entry for `blockchain.sync`, so a logger of the actor's own name would have shown nothing: it logs under `SyncController`'s, and a spec reads the shipped `logback.xml` to pin that.

**Looked at in the review round (2026-11-01), and left as they are:**

- *Skipping the scan loses a storage backstop (beacon).* It does not. The scan's storage download builds no trie nodes on either scheme (tasks.md T050a, "Storage gaps"), so it never mended a storage trie, and the walk reaches all storage from the leaves anyway.
- *A pass end writes both flags over a failed bulk recovery's cleared flag.* With the audit on, no start reads that flag, and `MissingCodeException` remains the backstop, once per process.
- *`EvmCodeStorage`'s comment said nothing deletes code while executing.* False since the audit, so the comment changed. A reader that read before `deleteBadCode`'s commit and caches after its eviction keeps bad code cached: accepted as rare, on code already rare.
- *An exhaust with no peer to ask still books a pass towards a delete.* `StateNodeFetcher` exhausts after 30 tries 5 s apart whether or not a peer was there, and its reply does not say. Only the timer's path is closed.
- *A fast batch can jump straight to 256.* The next batch scales the last by 100 ms over its read time, so a run of cached reads on a slow disk is followed by one long batch before it comes back to 16. As decided.

**Measured on Mordor at b4f6b6537:** 5.536M nodes and 2.72M accounts in 1463-2020 s at 25% duty, about 2.7-3.8k nodes/s, and a hole found 1.4 s into a pass and repaired 47 ms later. This does not predict Sepolia: a small trie and a warm cache.

**A stall on Hash that CI caught (not caused by this PR).** One timeout in 7,076 tests. A walk reports a missing node at the end of its level, then still has a level and a queue to finish. An answer and its flush landing in that gap have their completion checks refused, since the walk is flagged running, and `FrontierRebuildComplete` re-asked only under Path. On Hash nothing asked until the dead-pulse watchdog, about 6 to 8 minutes on, in production as well as in the test. e29ddcc08 fixed exactly this on Path and left Hash as it was, with no reason of Hash's own. It now re-asks on either scheme when a walk found something missing. 40 runs on two loaded CPUs did not reproduce it, so the test holds the walk at its last act instead.

**Measured live after the review round (a8e4d9c1e, 2026-11-01).** A node corrupted in a stopped datadir: found and repaired over the bad bytes 39 ms apart, pass not clean, the next clean, the next owed none. A `kill -9` past the first cursor save: the restart resumed at 7,465 saved positions and about 111k nodes, and finished. **The first live Path run**, Mordor on a local jar with `SyncController`'s ETC-requires-Hash guard patched out, never committed: sync to hand-off in about 6 minutes; export of 3.77M account and 1.77M storage nodes in 120.2 s; the stratified sample in 929 ms, 2,917 account leaves with 0 misses; a clean audit pass over the published hash store in about 33 minutes; import on that store across a restart. This shows the Path machinery working end to end. It does not show Path at Sepolia's size, on a PoS chain, or with a live CL, and Mordor's state moves little, so 0 stale leaves says little about the rate on Sepolia: T063 records it.

**T054's measurement needs a patched jar.** `SyncController.loadSnapSyncConfig`'s `require` rejects Path on ETC, so a Mordor Path run needs a local jar with that lifted, made for the measurement and never committed.

## R-5g. The completion switch: what T051 as written did not fit (PR 5d, 2026-11-01)

Design checked by forge and beacon before it was built; the decisions are in `.local/docs/continuations/s022-slice5d.md`. What they rest on, and what building it found.

**"Remove" could not stand.** T050a's rule is "settled, or the walk stays for that case", and five cases stay: a pre-invariant datadir's repair, a run that lost track, a fresh Path account phase over existing nodes, `inmemory` and deferred merkleization. So the walk and everything that decides on it stay, bypassed where the marker stands, and what is dead once nothing starts it is slice 6's.

**T050 was already true (forge, beacon).** `isComplete` has `heldNodes.isEmpty`, and a code-waiting leaf is held. The excluded set cannot move into it: `isComplete` is also read for `walkLocalOnly` and by the force-complete's refusal, and the gate runs only when it is true.

**The withdrawal rule cost nearly every long sync the switch (beacon).** About 23 process starts in one Sepolia first sync, and each resume took the marker. The user's decision: a resume keeps it. Beacon's trace of what that accepts: publish keys every node by its own keccak, so positions are discarded on the way to the store execution reads, and the crash class leaves absence, never a wrong root. So nothing here can change what execution computes, and the audit finds it. A fresh phase over non-empty families still withdraws, since the marker says nothing of those nodes. What counts as a resume is what the launch does, not whether cursors exist (beacon 1, reviewing it as built): with cursors and no task files, rejected or gone, every partial range starts over from its start, so that is a fresh phase and withdraws. A launch whose cursors are all complete and carries no files re-downloads nothing and withdraws anyway, which errs to the walk and is rare.

**A reseed can loop (forge 2, beacon 3).** The orphan drop can leave the root absent with nothing pending, and a reseed rediscovers the same node. Each lap heals one, so stagnation never fires, and a lap takes seconds. Past 3 for a root, the walk. That first version stopped there, and this text called it bounded: it was not (forge and beacon, reviewing it as built). A walk of an absent root finds the root and nothing else, and the lap heals, so the loop went on through the walk. Past 6 tries for a root healing now gives up on it, which re-pegs on Path and hands off lazily on Hash, and claims nothing. `HealNoWalkCompletionSpec` reaches both by the orphan drop itself: a stored child that cannot be read, its parent given up on, the chain above it dropped.

**A no-walk completion repeats unless it is latched (forge 3).** The dead-pulse watchdog looks at `verificationPassComplete`, which a no-walk completion never sets, so the signal would go out every 6 minutes or so. The latch is the record itself and not a flag of its own: whatever takes the record back takes the latch back, so no reset of the controller's can leave a coordinator silent with a controller waiting on it.

**A re-peg can pass the signal (forge 4).** The coordinator decides on one root, and the controller may be on the next when the signal lands. Path has had this gap since it took the signal as a clean walk. One record closes it for both: the controller compares, ignores a mismatch and asks again, and the coordinator has the re-peg by then, since the same actor sent both. The coordinator also clears the record at a re-peg, so A to B to A cannot find the old decision standing.

**The record cannot be the walk's (`healingCleanWalkRoot`).** That one stamps, and a `lostTrack` landing after a no-walk decision would be undone by a stamp. So two records, and a no-walk completion clears the walk's, since a proof from an earlier walk of this root does not stand beside it. A walk that could not follow something sets only the new one, and could keep a marker a finalisation would have taken. It should not arise with a marker standing: only the fallback runs there, it starts with the root absent, so it ends dirty, and the check takes over from there.

**One launcher runs no gate (found building it).** `reverifyCurrentRoot` is what the stale and dirty ends of a walk call, and it starts a walk directly. A re-peg under a fallback walk would have started a walk no marker read decided, so it reads the marker too. It does not simply go through the gate on every path: the gate holds a walk for the excluded set's pass, and this path does not today, so that would change the walk where it stays.

**What giving up on a root leaves (forge and beacon, on the fix round).** On Hash it is the lazy hand-off `HealingRootUnservable` takes, which the default config does not reach, so before this that state had no exit at all. The marker goes and the flags are set. Two limits are on record and not changed. Import's first block needs that exact root from a peer, since `StateNodeFetcher` pins a fetch of a trie root: with no peer holding it, import backs off for about 15 minutes and `RegularSyncStuck` re-SNAPs from a newer pivot, so the floor is a re-SNAP and not a wedge. And what reaches the give-up on Hash is local, a stored child that does not read or decode: peers cannot cause it, on-demand repair keys on a missing node and not on this one, and the audit waits while the head root is absent. The hand-off defers the cause, so T062 looks at what import does when it touches the node. On Path a re-peg that finds no newer root leaves the count where it is, and each later check asks again, which the watchdog paces at about 6 minutes: noisy, not hot. It is not latched because a latch there would need something else to wake it. `pivotUnservable` stays false on that arm: the root was served, and the absence is local. A fallback walk that a held BFS lease keeps from starting still counts as a try, which only a supervisor restart can reach, and narrowly.

**Reopening healing (beacon, forge).** `reopenHealing` is for validation's two failure branches, and neither looks reachable today: every route into `validateState` sets `healingValidatedRoot` first, so the validator does not run. It is there so a later wiring cannot leave a decision standing over a state validation turned down. It re-decides on what the coordinator knows and does not walk again, and the peer tick stays cancelled, as it already did on that path. (Slice 6b: validation's re-walk arms, which give the coordinator work, restart it. The two failure branches still do not; they are not reachable.)

**What the reviews of the build left as they are, and why.** `HealingRootUnservable` repeats at each check without moving-root, which only a non-default config reaches. Import's on-demand counter counts fetches and the Engine counter distinct holes, so the two are not one rate. `snap.heal.nodes.per.repeg` is a distribution summary. If slice 6 rewires the controller walk, `WalkStateChanged(false)` sends no check, which is the wake-up class 5c found: it has to be looked at there. **Looked at in slice 6b, which deleted the walk and `WalkStateChanged` with it:** nothing reads the flag any more, and every walk the coordinator still has ends in `VerificationBFSComplete`, `FrontierRebuildComplete` or `FrontierWalkFailed`, each of which sends a check, so nothing waits on a check that nothing sends (beacon). FR-012's "replaced everywhere" runs ahead of the code until slice 6 deletes the dead wiring. ETC has few snap-serving peers, so on-demand repair is thinnest there: forge would weigh a Mordor Hash soak before an ETC rotation.

**Force-complete goes with the exit (forge 5).** `triggerHealingForMissingNodes` reaches `ScheduledTrieWalk`, and a force-complete's `StateHealingComplete` relied on the controller's walk. Nothing in main sends it, and T047 pins it. It changed one commit ahead of the exit, so no commit has that signal claiming a clean root with the walk gone.

**Version 4, and the constants split.** This is the first reader that skips a walk on the marker's word, and a 5a jar's run could lose track with its marker standing. `snap.heal.datadirCreated` shared the constant, and moving it would have made every datadir count as not empty. Accepted: an unfinished datadir from an earlier jar is `PreInvariant` once. Only pre-release users can hold one.

**`canHold` carries deferred merkleization.** With H1 open a done marker there vouches for flat slots alone. Under it `atSnapStart` now says `CannotHold`, not `PreInvariant`, so a run with it on no longer owes the code scan. `ops/barad-dur/fukuii-conf-1/base.conf:352` is the one config that sets it, and that route skips healing unless cursors were resumed. `SNAPSyncConfig`'s default is true, which is why the fixtures about a marker that stands now say false.

**Observing a hole on the Engine path (beacon 2).** There is no on-demand repair there: a hole under `newPayload` answers SYNCING and only the audit fixes it, in days. This exists today on a lazy hand-off (local task #66, raised with the user, not in this PR). Without a counter of its own SC-004 would read zero. It counts distinct holes, not hits, since the consensus client retries every slot and one hole should read as one: the log says how often it was hit.

**`[HEAL-COMPLETE]` is the coordinator's.** It alone knows whether a walk ran, one that could not follow something included. Its "time from storage-done" runs from its own spawn, which follows the download's end in one process and is only a restart's age after one.

**Left as they are.** A signal for root A, sent before A to B to A, is taken at A: the decision was A's, the window is one message, and it needs a reorg back during it. The sample at hand-off is blind to a region absent where no sampled account goes (beacon, accepted). A resume's seam is mended by discovery from the absent root, as it always was: the walk it used to be followed by looked for what discovery does not reach, which is now the audit's.


## R-5h. Holes under newPayload: measured, and what #1694 became (2026-11-01)

#1694 said a hole under `newPayload` answers SYNCING with no repair, so an Engine-driven node sits behind until the audit finds it. A design followed from that: a repair actor and a way to re-execute blocks, 600-900 lines. It was measured first, on Ephemery 165 and staging's code (`mordor-scarcity/s1694-*.sh`), and the premise did not hold.

| Hole | Who met it | Repaired in |
|---|---|---|
| Storage, at the head (twice) | `newPayload`, then the importer | 12.8 s and 24.5 s |
| Storage, catching up after a restart | The importer | about 0.5 s |
| Account, catching up after a restart | The importer | about 0.5 s |
| Code, full-sync node | The importer | about 0.1 s |
| Code, SNAP-synced node | `newPayload`, then the importer | about 60 s, with a full-state scan |
| Code, SNAP-synced node, with the fix below | `newPayload`, then the importer | 23.9 s, no scan |

The block is stored by hash and answered ACCEPTED, not SYNCING. Nothing on the Engine path runs it again, and the consensus client did not re-send it. Regular sync's importer, which also runs post-merge, reaches the same block, fetches the node, and imports; the next payload then has a validated parent. To make `newPayload` the first to meet a hole, delete a leaf of EIP-4788's ring buffer for a future timestamp: a restart catches up through import, so a hole every block touches is met by import.

**So, the user's decisions of 2026-11-01:**
- No repair actor and no re-execution path.
- **Code holes fetch first.** On a SNAP-synced node the first missing code went straight to the bulk scan, which stops regular sync while it reads the whole state: 98 ms over 360 accounts here, estimated at hours on Sepolia. A fetch alone took 100 ms. Now the eighth distinct miss in a process asks for the scan. This amends 5c's "bulk recovery stays as the backstop" only in when it fires: several misses show there are many to find, and fetching those one by one is what costs more.
- **A storage hole with no position is found, not guessed.** One met writing has none (a delete collapses a branch by reading a sibling no read of the slot passed through). Asking for the storage root drew an honest reply of the wrong node, and blacklisted the peer. `StorageHolePosition` walks what is present for the reference to it, breadth first and up to 4,096 reads: every hole down to depth 3 and most at depth 4, so a trie of some thousands of slots. Where it finds none, as it mostly will not in a token's or an exchange's trie, the importer asks no peer (by hash alone it would ask by `GetNodeData`, which no eth/68+ peer serves) and takes it as an exhausted fetch, with its backoff. Locating a hole where it is met writing would close that, but that is in the trie code (a consensus file) and is left as a follow-up.
- **A hole no peer can serve keeps its re-SNAP** after 3 exhausted fetches. That is traced, and the one case on record is Platåberget's at 320602, not measured. It is the only exit if the state really is gone, and T063 records any occurrence.
- **Only exhausted fetches count towards it (2026-11-02).** Each 30 s retry of the blocked import also counted, since v0.6.0 (#1183) and with no reason given, so the first exhaust was usually the third and the re-SNAP came after about 2.5-3 minutes, not the 15 this section first said (found by forge and beacon in review). The user chose to stop counting retries. A retry re-sends the same fetch, and the fetcher keeps its attempt count across that (`StateNodeFetcher` de-duplicates by hash), so exhausts still arrive. Each takes at least about 160 s of fetching (30 tries 5 s apart, plus each request's own short backoff and the peer rotation), with a 5-minute backoff between, so at least about 17 minutes to the re-SNAP. A storage hole that cannot be located has no fetch to wait out: about 10 minutes, less if another import ends a backoff early.
- **What keeps exhausts from being lost or piling up (forge's closure review, 2026-11-02).** Once retries stopped counting, three paths mattered that the old counting had hidden:
  - An exhaust landing while a retry's import ran reached `running`, which dropped it, and the re-import started the fetch again from its first try. It is now kept, and that import's `ImportDone` ends its wait with it.
  - The count outlived a block imported, so holes the audit filled days apart added up to a re-SNAP. A block imported now clears it.
  - `StateNodeFetcher` is unsupervised, so any failure stops it and every later fetch goes to dead letters. Only the tick counting got the importer out of that, in about 90 s. Now `BlockFetcher` spawns it again and answers the fetch it held as exhausted, so a fetcher that dies on every fetch ends in the re-SNAP like any hole no peer serves. (Stopping `BlockFetcher` instead, the first version of this, goes through `RegularSync`'s re-spawn, which never starts the new importer: forge's closure check. That re-spawn path is older than #1694 and is left as a follow-up.)

**What this does not show.** Ephemery's state is tiny, its blocks nearly empty, and its peers answered in about 40 ms. Re-execution on a large block, few peers (Sepolia's 1-9 state-serving) and a whole missing subtree, where each node costs a fetch and a re-execution, are not measured. The scan's cost at Sepolia's size is an estimate.

## R-5a. Where abandoned storage accounts are recorded (T002; vault, 2026-10-28)

**Verified unless marked inferred. Paths under `blockchain/sync/snap/` unless stated.**

- **The task list.** `<datadir>/snap/fukuii-contract-storage-*.bin` holds 64 bytes per contract account (accountHash ‖ storageRoot), and since PR 5a per account with a storage root and no code (R-5d). It is written by `ContractWorkCarry:172-178` and `AccountRangeCoordinator:280`. Its pointer and count are AppState keys (`db/storage/AppStateStorage.scala:567-568`, written at `controller/DownloadSupervisor:693-697`). It is **not** in CF `g`.
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
- Spec 018 F6 (FR-011) owns flat-state execution reads, on by default at F7. (This line said F3, which is path-aware node resolution.) Spec 013 owns flat-state snapshot files.
- Execute-from-genesis on this host is an unmeasured IO model (2–3× error band): Mordor about 2–5 days, ETC mainnet about 1–3 months, Sepolia about 2–4 months.
- Direction: #1671 first, then 018 flat reads, then 013 Phase 0. A staged mode only for Mordor/ETC, after flat reads exist.
