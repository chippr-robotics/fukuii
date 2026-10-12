# Coordinator characterization (spec 016 T060, #1401)

**Status**: complete for the four SNAP coordinators. It finishes research.md R12 ("preliminary, completed in T060"),
and research.md R14b holds the baselines that the C-* PRs compare against (per-slice checklist item (c)). This file
holds the full matrices, the storage-reference breakdown, the tooling gap analysis and the proposed order.

**Baseline**: `origin/staging@7d2edf157` (`chore: bump version to 0.9.69`, after T055 / #1579). Line numbers (`L####`)
refer to the coordinator file at that commit. As in research.md R0, code is located by symbol; line numbers are only for
reading this document against the baseline.

| File (`snap/actors/`) | Lines | Impl class | Mutable fields | `Command` cases | Behaviours (arms) | Storage refs (`storage-baseline.json`) |
|---|---|---|---|---|---|---|
| `ByteCodeCoordinator.scala` (BCC) | 870 | `private class ByteCodeCoordinatorImpl` L33 | 20 | 15 | `active` (16 + catch-all + signal) | **10** |
| `AccountRangeCoordinator.scala` (ARC) | 2,220 | `private class AccountRangeCoordinatorImpl` L68 | 39 | 35 | `receive` (25 + catch-all + signal), `finalizing` (14 + catch-all + signal) | **28** |
| `StorageRangeCoordinator.scala` (SRC) | 2,806 | `private[actors] class StorageRangeCoordinatorImpl` L52 | 56 | 19 | `active` (one arm, delegates to `handleCommand`: 18 + catch-all; 2 signals) | **88** |
| `TrieNodeHealingCoordinator.scala` (TNHC) | 2,900 | `private[actors] class TrieNodeHealingCoordinatorImpl` L45 | 49 | 24 (8 declared inside the Impl) | `activeBehavior` (25 + catch-all) | **84** |

`controller/ChildFactories.scala` (72 refs) and the controller's spawn sites hold the rest of the coordinators' storage
wiring (§4).

## 1. Method

Same method as the controller characterization (research.md R0): static inspection only. No sbt, java or JVM was run,
and no source was edited. Specifics for the coordinators:

- **Scope**: the members of each `*CoordinatorImpl` class (indent 2, after the constructor). The companion object
  (`apply`, `Command` ADT, pure helpers) is listed separately.
- **Attribution**: each occurrence of a mutable field is attributed to the enclosing indent-2 member. Inside a behaviour
  (a `def` whose body is `Behaviors.receiveMessage` / `receive`, or SRC's `handleCommand(msg) = msg match`), it is
  attributed to the **arm** (`case X =>` at the arm indent). Closures count towards their member or arm. Comments are
  removed with `verify.py`'s own `strip_comments`. String literals are blanked except for `${...}` / `$name`
  interpolations, so a field named in a log message counts as a read only when it is interpolated.
- **Mutable field**: a `var`, or a `val` whose initializer is a mutable holder (`mutable.*`, `Atomic*`, a Bloom filter,
  a `java.util` collection).
- **Write (W)**: assignment or compound assignment to a `var`, or a mutating call on a holder (`update`, `put`,
  `remove`, `clear`, `+=`, `enqueue*`, `getOrElseUpdate`, `incrementAndGet`, ...). A `val` holder followed by `=` is a
  named argument, not a write. This is a regex classification and over-counts slightly. Spot checks: BCC
  `ByteCodeTaskFailed`, TNHC `HealingGetProgress` and ARC `finalizing` were checked by hand.
- **Storage references**: counted with `verify.py`'s own `storage_ref_count` (`STORAGE_REF_RE`, `STORAGE_NAME_ALLOW`,
  and the `StateIngest` exemption from `cap_exemption`). The per-file totals reproduce `storage-baseline.json` exactly
  (10 / 28 / 88 / 84 / 72), so the per-slice figures below are what step 8 will measure.
- **Rule (d)**: each mutable field's declaration line is run through `verify.py`'s `member_issue`. A field "may move"
  (as a private `var` of the module trait) only if its initializer is a literal, `None`/`Nil`, an empty collection or a
  companion constant, and only if one module touches it. Every other field stays in the Impl. If a module touches it, it
  becomes an abstract member of that module's `<Module>State`.
- **Interface sizes** (§3, research.md R14b) follow R14/R14a, with one difference. R14 left the `syncing` arms out
  because P4 later moved them into modules. The coordinator behaviours stay in the core (none of the C-* rows moves an
  arm; C-H0 only splits TNHC's), so their arms count as "other code" here. The State figure is therefore the
  compiler-relevant one: fields a module touches that any other unit (another module, a core method or an arm) also
  touches, plus the fields only that module touches that rule (d) keeps in the core. Api is the number of distinct
  `def`s of other modules or of the core that the module's bodies call. Like R14a, it is an upper bound: a name match
  is counted as a call.
- **Not counted in State**: constructor parameters (`context`/`ctx`, `timers`, `networkPeerManager`, `requestTracker`,
  `snapSyncController`, `intakeBudget`, the config ints and the storage handles) and immutable `val`s (`log`, `self`,
  the response-window constants, the ECs). They are listed per module in §3. Unlike the controller, a coordinator has
  no `SnapControllerEnv` equivalent, so without one every constructor parameter a module body names becomes a State
  member. §6 (T060b, item 5) proposes a per-coordinator env.

The full matrices are in Appendix A. Each module's member list is in §3.

## 2. Findings in one place

1. **Boundaries: confirmed, with nine amendments** (§3). No C-* row moves wholesale. The amendments:
   - #1501's `skipPresent` check is inside BCC's `AddByteCodeTasks` arm, not in a method;
   - #1533's off-actor response pipeline (eight members) postdates R12 and is assigned to C-S4;
   - SRC `handleTimeout`, ARC `maybeRequestPivotRefresh`/`drainActiveTasks` and TNHC `isNodeInStorage`/`readPathVerified*`
     were unassigned and are now placed;
   - C-S4 has no storage read (proof verification is pure);
   - the Impl-nested message and helper types must move to the companion before narrowing;
   - several rule-(d) fields stay in the core and become State members.
2. **`StateIngest` does not cover the stores three of the four coordinators take** (§4.3): `MptStorage`, which
   `getOrCreateMptStorage` creates once per pivot and caches; `SnapStorageDoneStorage`; `SnapSyncProgressStorage`;
   `HealingFrontierStorage`; `BfsQueueStorage`. Only BCC (`evmCodeStorage` → `bytecode`) maps one-to-one. "`apply`
   takes a `StateIngest`" is underspecified for ARC, SRC and TNHC until the user decides **D-T060-1** (§4.3). TNHC's
   `evmCodeStorage` is also `Option`-typed with a `None` test default, which `StateIngest.bytecode` cannot express.
3. **Two raw-store callers outside `snap/`**: `blockchain/sync/BytecodeRecoveryActor` calls `ByteCodeCoordinator.apply`
   and `StorageRecoveryActor` calls `StorageRangeCoordinator.apply`, both with raw stores. Neither can see
   `private[snap] StateIngest`, and neither holds all eight handles. This is decision **D-T060-2** (§4.4).
4. **Zero-reference slices** (the tasks.md exception, §5): C-A1, C-A2, C-H4, and the `ByteCodeDispatch` half of C-B1
   hold no storage reference at all. C-S1 and C-S4 hold only false positives: two `ForceCompleteStorage` matches in C-S1,
   and two metric setters plus `requestTracker.validateStorageRanges` in C-S4. So "lowers the total" cannot be their
   done criterion.
5. **`verify.py` cannot verify a C-* narrowing as specified** (§6). Step 6 rejects every line that changes `apply`,
   `ChildFactories`, the `new *Impl(...)` arguments, the spawn sites, the bodies' handle uses and the recovery actors.
   Capability mode cannot carry it either: it rejects a commit that is also a narrowing, and it forbids
   `ChildFactories` signature changes. A plain prerequisite commit would pass, but with no structural check, so the one
   commit that lowers the total would be the least-checked kind. The proposed fix is new task **T060b**; nothing is
   implemented here.
6. **Order** (§7): tasks.md order stands, and BCC first is still the lowest-coupling start. Prerequisites: T060b, then
   D-T060-2 before C-B1, and D-T060-1 before C-A3. Vault reviews and node runs are listed per slice.

## 3. Module boundaries: confirmed or amended

Legend: **State** = R14b `<Module>State` baseline (shared fields + fields rule (d) keeps in the core); **Api** = R14a-style
upper bound of distinct callees outside the module; **refs** = storage references in the moved code as step 8 counts
them, with the genuine handle uses in brackets; **SI** = `StateIngest` members the narrowing would use.

### 3.1 ByteCodeCoordinator (C-B1, T061): confirmed as one PR, two traits; one amendment

| Module | Members (moved) | Lines | State | Api | refs | SI |
|---|---|---|---|---|---|---|
| `ByteCodeDispatch` | `nowMillis`, `isPeerCoolingDown`, `clearPeerFailures`, `recordPeerCooldown`, `responseBytesTargetFor`, `adjustResponseBytesTargetOnSuccess`, `adjustResponseBytesTargetOnFailure`, `inFlightForPeer`, `assignTaskToWorker`, `dispatchIfPossible`, `tryRedispatchPendingTasks`, `createWorker`, `markWorkerIdle` (+ `workerSeq` as a private var) | 128 | **9** | 1 | **0** | none |
| `ByteCodeResponseHandling` | `handleByteCodesResponse`, `ValidatedByteCodes`, `validateReturnedCodes`, `storeBytecodesWithHashes`, `filterAndDedupeCodeHashes` | 192 | **7** | 9 | **3** (`evmCodeStorage` ×2, `.commit()`) | `bytecode` |

- State, `ByteCodeDispatch`: `activeTasks`(W), `idleWorkers`(W), `knownAvailablePeers`, `maxInFlightPerPeer`,
  `peerCooldownUntilMillis`(W), `peerFailureCounts`(W), `peerResponseBytesTarget`(W), `pendingTasks`(W), `workers`(W).
- State, `ByteCodeResponseHandling`: `activeTasks`(W), `bytecodesDownloaded`(W), `bytesDownloaded`(W),
  `completedTaskCount`(W), `consecutiveTaskFailures`(W), `pendingTasks`(W), plus `hashFailureCounts` (only this module
  touches it, but it is a concrete `mutable.Map` val, so rule (d) keeps it in the core).
- Api, `ByteCodeResponseHandling`: 7 calls into `ByteCodeDispatch` and 2 into the core (`checkCompletion`, `active`).
  `ByteCodeDispatch` calls the core's `pendingHashCount`.
- Stay in the core: `start`, `active` (all arms), `pendingHashCount`, `notifyBackpressureIfChanged`, `checkCompletion`,
  and the constants (rule (d); they become private lazy vals in the narrowing, D1).
- **Amendment A-B1 (`skipPresent`).** plan.md says C-B1 moves "the #1501 `skipPresent` check". The check is the first
  line of the `AddByteCodeTasks` arm of `active` (L209: `received.filter(h => evmCodeStorage.get(h).isEmpty)`), not a
  method, so a `# moved:` commit cannot take it. Two options:
  - leave it in the core, where it is the core's only body storage reference (1);
  - carve it into a method in a plain restructure commit ahead of the move (the T053a / M11b "lift" pattern), then
    move that method with `ByteCodeResponseHandling`.

  Either way, the check reads `evmCodeStorage` and needs `bytecode`.
- Impl-nested type: `ActiveByteCodeRequest` (L102), used by both modules and the core. It moves to the companion in its
  own `# moved:` commit; the Impl already does `import ByteCodeCoordinator.*`, so its references are unchanged. The
  private type alias `WorkerRef` (L53) needs the same move.

### 3.2 AccountRangeCoordinator (C-A1…A4, T062–T065): confirmed; four placements

| Module | Members (moved) | Lines | State | Api | refs | SI |
|---|---|---|---|---|---|---|
| C-A1 `AccountPeerDispatch` | intake gate and backpressure (`downstreamBackpressureActive`, `intakeGateClosed`, `armIntakeGateRecheck`, `dispatchDeliberatelyPaused`, `storageBackpressureActive`, `applyBackpressureChange`); stateless/snapless (`isPeerStateless`, `isPeerSnapless`, `markPeerStateless`, `recordPeerSuccess`, **`maybeRequestPivotRefresh`**); cooldown (`effectivePeerCooldown`, `isPeerCoolingDown`, `recordPeerCooldown`); response bytes ×3; `inFlightForPeer`, `activePeerCount`, `maxWorkers`, `createWorker`, `markWorkerIdle`, `dispatchIfPossible`, `dispatchNextTaskToWorker` (+ `workerSeq`, `lastPivotRefreshTimeMs` as private vars) | 301 | **17** | 1 | **0** | none |
| C-A2 `AccountTaskLifecycle` | `handleTaskComplete`, `handleTaskFailed`, `requeueOrEscalate`, `tryRedispatchPendingTasks`, **`drainActiveTasks`**, `updateTaskProgress`, `compareUnsigned32`, `incrementHash32`, `isMaxHash`, `completeEmptyTaskRange` (+ `lastStateLogMs`) | 320 | **18** | 15 | **0** | none |
| C-A3 `AccountTrieAssembly` | `getOrCreateTaskStackTrie`, `handleStoreAccountChunk`, `finalizeTrie`, `finalizing` (a whole behaviour `def`, so it moves as a symbol), `storeChunkSize` (+ `lastFlatMilestonePct`, `lastProgressLogAt`) | 171 | **12** | 3 | **5** [`mptStorage`, `pathNodeStorage` ×2; the 2 `.commit()` are StackTrie root commits] | `pathNodes`; per-pivot `MptStorage` (D-T060-1) |
| C-A4 `ContractWorkCarry` | `identifyContractAccounts`, `syncTaskFiles`, `replayCarriedChunk`, `replayDone`, `readContractFile`, `readUniqueCodeHashes` (+ the four `replay*` private vars) | 192 | **4** | 2 | **6** [`mptStorage` (`multiGetNodes` presence read); 3 `replayQueuedStorage` and 2 `.createStorageTask` are false positives] | per-pivot `MptStorage`, `SnapStorageDoneStorage` (D-T060-1) |

- Placements (amendments to plan.md): `maybeRequestPivotRefresh` → C-A1 (it is driven by the stateless marking and
  reads only peer-side state); `drainActiveTasks` → C-A2. `classifyIdleStall` and `copyPrefix` are companion helpers.
  The first is used only by the `CheckDispatchStalled` arm, which stays in the core. The second is used by
  `syncTaskFiles` and follows it into C-A4 behind an `export` (D2) or stays in the companion.
- Stay in the core: `onStart`, `onStop`, `sendProgressSnapshot`, `receive` (all arms, including the 87-line
  `CheckDispatchStalled` that consults `classifyIdleStall`), `calculateProgress`, `isComplete`,
  `computeKeyspaceEstimate`.
- **C-A4 State is small but the core keeps its I/O.** The task-file streams (`contractAccountsOut`,
  `contractStorageFos`/`Out`, `uniqueCodeHashesFos`/`Out`), the files and the carried counts are concrete `val`s whose
  initializers open files. Rule (d) keeps them in the core, and C-A4's bodies reach them as State members. Counting them
  (11 immutable vals) raises C-A4 from 4 to about 15. That count is recorded at the PR (item (c)).
- `storageDone` (`Option[SnapStorageDoneStorage]`) is a genuine store that step 8 does **not** count: the identifier
  does not match `STORAGE_REF_RE`. Only its type in the constructor and in `apply` counts. Removing it lowers the total
  by its type occurrences only.
- C-A1 is the site of the "stale-pivot empty ranges as scarcity" open item together with C-A2 (unchanged).

### 3.3 StorageRangeCoordinator (C-S1…S4, T066–T069): confirmed with two scope amendments

| Module | Members (moved) | Lines | State | Api | refs | SI |
|---|---|---|---|---|---|---|
| C-S1 `StoragePeerDispatch` | `isPostRefreshCooldownActive`, `isPeerStateless`, `markPeerStateless`, `recordPeerSuccess`, `recordPeerAnswered`, `maybeRequestPivotRefresh`, `batchSizeFor`, `reduceBatchSize`, `maybeIncreaseBatchSize`, `inFlightForPeer`, response bytes ×3, `requestNextRanges` (the `selectFloorPeer` use), `dispatchIfPossible`, `tryRedispatchPendingTasks`, `redispatchEligible`, `isPeerCoolingDown`, `perPeerInFlightLimit`, `recordPeerCooldown`, **`handleTimeout`** (the `SnapPeerHealth` timeout demotion) (+ 5 private vars) | 481 | **23** | 1 | **2** [both are `ForceCompleteStorage`, false positives] | none |
| C-S2 `StorageOrderedApply` | `ReadyStorageChunk`, `clearStorageOrderingState`, `getOrCreateAccountTrie`, `commitAccountTrie`, `commitPrebuiltRoot`, `resetAccountTrie`, `flushTrieNodeWrites`, **`writePrebuiltTrie`** (#1533), `applyOrderedStorageChunk`, `drainOrderedStorageChunks`, `applyReadyStorageChunk` | 359 | **11** | 7 | **16** [`mptStorage` ×2, `pathNodeStorage` ×6, `pns.dataSource` (raw), `snapProgressStorage` ×2 + `.writeStorageCursor`; 4 are metric setters or a trie commit] | `pathNodes`; per-pivot `MptStorage`, `SnapSyncProgressStorage` (D-T060-1) |
| C-S3 `StorageDoneMarkers` | `recordFlatBatchDone`, `stageDoneMarker`, `takeDoneMarkersFor`, `stageFlatSlotChunk`, `flushPendingFlatBatch`, `recordCompletedTask` (+ `flatBatchesDoneThrough`) | 112 | **9** | 1 | **8** [`flatSlotStorage`, `storageDoneStorage` ×3, `doneStorage` ×2, `DataSourceBatchUpdate`, `.commit()`] | `flatSlots`; `SnapStorageDoneStorage` (built in the SRC core from `flatSlotStorage.dataSource`; D-T060-1) |
| C-S4 `StorageResponseProcessing` | `isStaleLocalRootSignature`, `giveUpOnAccountForHealing`, `handleResponse`, `processStorageRanges`, `processServedTasks`, `createStorageSubTasks`, `recordSubtaskCompletion`, **plus #1533's off-actor pipeline**: `PendingResponse`, `enqueueResponse`, `submitProcessingJobs`, `drainProcessedResponses`, `logStoragePerf`, `abandonPendingResponses`, `processingPoolShutDown`, `shutdownProcessingPool` (+ `inlineVerifyNanos`, `nextResponseSeq`) | 476 | **19** | 14 | **3** [none genuine: `.setStoragePendingTries` ×2, `requestTracker.validateStorageRanges`] | none |

- **Amendment A-S4a (#1533 pipeline).** R12 predates #1533, which added off-actor response verification: the members
  listed above, plus `processingPool`/`processingEc` and the `ProcessingSettings` companion. They are response
  processing, so they join C-S4, which goes from about 350 to 476 lines (plan.md estimated about 450). The alternative,
  a separate C-S5, would carry 125 lines and 8 State members of its own. `writePrebuiltTrie` (#1533) applies a prebuilt
  trie, so it joins C-S2.
- **Amendment A-S4b (no store reads in C-S4).** T069 says "Narrowing takes `StateIngest` (proof verification reads)".
  Proof verification is pure: `verifyStorageChunk` and `MerkleProofVerifier(task.storageRoot).verifyStorageRange(...)`,
  in the companion, read only the response's proof nodes. C-S4 reads no store. It is a zero-reference slice (§5).
- **Amendment A-S2 (raw `DataSource` write).** `flushTrieNodeWrites` commits with `pns.dataSource.update(updates)`, and
  `DataSourceBatchUpdate` / `DataUpdate` are SRC's two baselined raw imports. `stateIngest.pathNodes` hands out the
  same `PathNodeStorage`, so the narrowing keeps `pns.dataSource.update` and the raw imports, unless `StateIngest` gains
  a batch-write member. That is a T067 design choice and an SC-010 concern: ADR INF-006 §5 rules out a per-node
  wrapper, but a per-batch member is allowed.
- SRC's `active` flushes the trie-node buffer after **every** message (`flushTrieNodeWrites()` after `handleCommand`).
  That call stays in the core and becomes a C-S2 Api edge.
- Stay in the core: `start`, `active`, `handleCommand` (all arms), `onPostStop` (the final flat-batch and marker flush:
  `flatSlotStorage`, `storageDoneStorage`, `DataSourceBatchUpdate`, `.commit()`, 5 refs), `notifyBackpressureIfChanged`,
  `progress`, `isComplete`, `updateContractProgress`, and the `storageDoneStorage` val (rule (d): concrete val, 4 refs).
- Impl-nested types used across modules: `StorageTaskKey`, `StaleRootFailure`, `ReadyStorageChunk`,
  `PendingResponse`, and the `ByteStringOrdering` object. They move to the companion first (§3.1 note).
- White-box tests: `StorageRangeCoordinatorSpec` and `StorageRangeCoordinatorOffActorSpec` build
  `new StorageRangeCoordinatorImpl(...)` directly. They read 26 Impl members (`tasks`, `accountSubtaskCounters`,
  `applyOrderedStorageChunk`, `pendingDoneMarkers`, `flatBatchesDoneThrough`, ...). Moved members keep their
  `private[actors]` modifiers verbatim, so these accesses still compile through the Impl. A constructor change in the
  storage slices edits these two specs' construction lines (token-free; step 3 admits them).

### 3.4 TrieNodeHealingCoordinator (C-H0…H5, T070–T075): confirmed; three placements and one structural note

| Module | Members (moved) | Lines | State | Api | refs | SI |
|---|---|---|---|---|---|---|
| C-H1 `FrontierPersistence` | `persistFrontier`, `unpersistFrontier`, `clearPersistedFrontier`, `emitHealingFrontierGauges`, `awaitFrontierDrain`, `writeDurableSubtreeRecords`, `flushRawNodesSync`, `flushRawNodesAsync` | 133 | **9** | 0 | **11** [`healingFrontierStorage` ×4 + `.commit()` ×3 (CF `g` writes), `mptStorage` ×4 (`storeRawNodes`)] | per-pivot `MptStorage`, `HealingFrontierStorage` (D-T060-1) |
| C-H2 `FrontierRebuildBfs` | `rebuildFrontierBFS`, `startFrontierBFS` ×2, `locationKey`, `storageOwner`, `seedVisitKey`, `branchChildVisitKey`, `extChildVisitKey`, `storageRootVisitKey` | 437 | **2** | 2 | **10** [`BfsQueueStorage`, `HealingFrontierStorage`, `healingFrontierStorage`, `mptStorage`; 6 `.isStorage` are false positives] | per-pivot `MptStorage`, `HealingFrontierStorage`, `BfsQueueStorage` (D-T060-1) |
| C-H3 `HealVerification` | `reverifyCurrentRoot`, `startVerificationBFS`, `startScopedVerification`, **`readPathVerifiedRlp`, `readPathVerifiedNode`** | 122 | **11** | 2 | **4** [`healingFrontierStorage`, `pathNodeStorageOpt`, `.readStorageNode`, `MptStorage`] | `pathNodes`; `HealingFrontierStorage` (D-T060-1) |
| C-H4 `HealDispatch` | `shortRootLabel`, `noteUnservableAttempt`, response bytes ×3, `isPeerCoolingDown`, `recordPeerCooldown`, `inFlightForPeer`, `dispatchIfPossible`, `updateHealThrottle`, `effectiveBatchSize`, `requestNextBatch`, `handleTimeout`, `tryRedispatchPendingTasks` (+ `healPending`, `healThrottle`) | 241 | **18** | 1 | **0** | none |
| C-H5 `HealResponseProcessing` | `handleResponse`, `processActiveResponse`, `discoverMissingChildren`, `queueNodes`, `recordHealedCodeHash`, `flushHealedCodeHashes`, `isComplete`, `clearHealedPathsSet`, **`isNodeInStorage`** (+ `childrenDiscoveredTotal`) | 440 | **23** | 11 | **12** [`mptStorage` ×3, `pathNodeStorageOpt` + `.writeStorageNode`, `healingFrontierStorage`, `evmCodeStorage`; 5 `isNodeInStorage` are false positives] | `bytecode` (but see the `Option` note), `pathNodes`; per-pivot `MptStorage`, `HealingFrontierStorage` (D-T060-1) |

- `boundedVisitedSet` and `computeEffectiveParallelism` (plan.md's C-H2 row) are companion helpers, like ARC's
  `copyPrefix`: they follow C-H2 behind an `export` (D2) or stay in the companion.
- Placements: `isNodeInStorage` → C-H5. It is the presence check behind child discovery; the `StartTrieNodeHealing`
  and `HealingPivotRefreshed` arms also call it, and that becomes an Api edge. `readPathVerifiedRlp`/`Node` → C-H3,
  the verification reads; C-H2 calls `readPathVerifiedNode` and C-H5 calls `readPathVerifiedRlp`. `prunedEnabled` and
  `bfsQueue` are concrete vals, so rule (d) keeps them in the core, where they are read through State.
- **C-H0 is a plain restructure commit**, like P4a–e and the M5c/T050c/T053a "lift" PRs. It has no `# moved:` trailer,
  so steps 1, 2 and 6 do not run. The guard is the arm-order table and review (plan.md R-3). `verify.py` step 1's
  `added_ok` would reject a new `private def xArms: PartialFunction[...] =` line in a `# moved:` commit, so C-H0 must not
  carry that trailer. In the current file `activeBehavior` (L713–1534, 821 lines) has 26 arms, three of them for
  `VerificationBFSComplete` (two guarded). They are listed in Appendix A, ready for C-H0's arm-order table.
- **Structural note (Impl-nested Commands).** Seven TNHC message classes are declared **inside the Impl** (L571–613:
  `FlushComplete`, `FrontierRebuilt`, `VerificationBFSComplete`, `FrontierRebuildComplete`, `FrontierWalkFailed`,
  `RestartResumeVerification`, `RestartFullRebuild`), together with `HealingStagnationCheck` (L173) and the helper
  classes `HealingEntry`, `ActiveRequest`. C-H1, C-H2, C-H3 and C-H5 construct them. After narrowing a module cannot
  name an Impl member type, so each must move to the companion first, in a `# moved:` commit. A path-dependent
  `case class` that moves to the companion keeps its pattern matching and equality, but forge/prism should confirm that
  no `isInstanceOf` relies on the path. This touches the `Command` ADT's file but not its wire format: the messages are
  actor-local and never leave the process.
- **`evmCodeStorage: Option[EvmCodeStorage] = None`** (TNHC `apply` and constructor). Production always passes
  `Some(stateIngest.bytecode)`. `HealingTrieFixtures` relies on the `None` default, which turns the healed-code check
  off. A non-optional `stateIngest` would turn that check on in every healing test. The narrowing therefore needs
  `Option[StateIngest]`, or a separate `healedCodeCheck: Boolean`. Record that choice in T075.
- `SNAPSyncControllerPinSpec` is protected and asserts on `ChildSpawn.Healing.{mptStorage, healingFrontierStorage,
  bfsQueueStorageOpt, pathNodeStorageOpt, evmCodeStorage}`, building a copy with named arguments (L344–355). When
  TNHC's `apply` changes, `RecordingChildFactories` (a test fixture, editable) must keep `ChildSpawn.Healing`'s fields
  and fill them from the new parameters (`evmCodeStorage = Some(stateIngest.bytecode)`, `pathNodeStorageOpt =
  stateIngest.pathNodes`). That is a fixture edit, not an assertion change. No other pin reads a coordinator's storage
  arguments: `HealingOrchestratorSpec` L245–249 and `DownloadSupervisorSpec` L342–345 and L784 do, and they are module
  stub suites that can be edited.

## 4. Storage references

### 4.1 Per-file counts (`storage-baseline.json` at the baseline; total 678)

| File | Total | Imports | Impl constructor | Impl body | Companion (`apply`, helpers) |
|---|---|---|---|---|---|
| `actors/ByteCodeCoordinator.scala` | 10 | 1 | 2 | 4 | 3 |
| `actors/AccountRangeCoordinator.scala` | 28 | 3 | 5 | 11 | 9 |
| `actors/StorageRangeCoordinator.scala` | 88 | 8 | 8 | 52 | 20 |
| `actors/TrieNodeHealingCoordinator.scala` | 84 | 6 | 10 | 48 | 20 |
| `controller/ChildFactories.scala` | 72 | 11 | — | — | ARC 9, SRC 16, TNHC 20, BCC 4, `chainDownloader` 12 |

Spawn sites, counted with the `StateIngest` exemption: in `DownloadSupervisor`, BCC ×2 at 2 each, SRC ×2 at 8 each and
ARC 4; in `HealingOrchestrator`, TNHC 9 and BCC 2.

### 4.2 What moves, what stays, and what can fall

The ratchet total falls only where storage-typed parameters disappear: the Impl constructor, `apply`, `new *Impl(...)`,
the `ChildFactories` method and the spawn sites. A module body's `evmCodeStorage.put(...)` and
`stateIngest.bytecode.put(...)` both count 1 (`cap_exemption`: a handle reached through the capability counts once,
ADR INF-006). The moved code itself therefore never lowers the total; it only trades between files.

| Slice | refs moved (core file → module file) | Raw parameters the slice's code uses |
|---|---|---|
| C-B1 | 3 | `evmCodeStorage` |
| C-A1 / C-A2 | 0 / 0 | — |
| C-A3 / C-A4 | 5 / 6 | `mptStorage`, `pathNodeStorage` / `mptStorage`, `storageDone` |
| C-S1 / C-S4 | 2 / 3 (all false positives) | — |
| C-S2 / C-S3 | 16 / 8 | `mptStorage`, `pathNodeStorage`, `snapProgressStorage` / `flatSlotStorage` (+ the core's `storageDoneStorage`) |
| C-H1 / C-H2 / C-H3 | 11 / 10 / 4 | `mptStorage`, `healingFrontierStorage` / + `bfsQueue` / `healingFrontierStorage`, `pathNodeStorageOpt` |
| C-H4 / C-H5 | 0 / 12 | — / `mptStorage`, `healingFrontierStorage`, `pathNodeStorageOpt`, `evmCodeStorage` |

**Projection for C-B1** (the only coordinator whose stores all map onto members): `evmCodeStorage: EvmCodeStorage` →
`stateIngest: StateIngest` in the constructor, `apply`, `new ByteCodeCoordinatorImpl(...)` and `ChildFactories`; the
three spawn sites pass `stateIngest = stateIngest`; the import goes. BCC's file pair falls from 10 to 4 (1 in the core's
`AddByteCodeTasks` arm, 3 in `ByteCodeResponseHandling`), `ChildFactories` falls from 72 to 68, `DownloadSupervisor`
from 60 to 56 and `HealingOrchestrator` from 45 to 43. The total goes from **678 to 662**. This was checked by applying
the substitutions to a scratch copy of the tree and running step 8's counter, not by estimate. `BytecodeRecoveryActor` is outside the measured set (D-T060-2).

**Floors the narrowings cannot remove.** These are false positives of `STORAGE_REF_RE`, which stay after every
coordinator has adopted `StateIngest`:

| Coordinator | Floor | Breakdown |
|---|---|---|
| ARC | 7 | `replayQueuedStorage` ×3 (a counter `var`), `.createStorageTask` ×2, StackTrie `.commit()` ×2 |
| SRC | 25 | `.setStorage*` metric setters ×14, `ForceCompleteStorage` ×5 (a `Command` object), `.validateStorageRanges` / `.verifyStorageRange` / `.attachStorageConsumer`, trie `.commit()` ×3 |
| TNHC | 14 | `isNodeInStorage` ×8 (a method name), `.isStorage` ×6 (a node predicate) |

Genuine `.commit()` calls on a `DataSourceBatchUpdate` also stay; BCC has one, SRC two and TNHC three. So SC-009 ("no
file under `snap/actors/` references a storage type except through `StateIngest`") cannot be read off the ratchet
total. T060b item 6 proposes a type-level check for it.

### 4.3 D-T060-1 (user decision): stores that `StateIngest` does not expose

| Coordinator parameter | Built where | `StateIngest` today |
|---|---|---|
| BCC `evmCodeStorage`; TNHC `evmCodeStorage` (`Option`) | env handle | `bytecode` (TNHC needs `Option` semantics, §3.4) |
| ARC/SRC `pathNodeStorage`, TNHC `pathNodeStorageOpt` | core val | `pathNodes` |
| SRC `flatSlotStorage` | env handle | `flatSlots` |
| ARC/SRC/TNHC `mptStorage` | `SnapResumePlanner.getOrCreateMptStorage(pivot)`: `stateTries.getBackingStorage(pivot)`, created **once per pivot and cached** in the core's `mptStorage` var (reset by `wakeFromDormant` / `restartSnapSync`); one instance is shared by every coordinator of that pivot | **none** |
| ARC `storageDone` | core val `storageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)` | **none** |
| SRC `snapProgressStorage` | core val `new SnapSyncProgressStorage(appStateStorage.dataSource)` | **none** |
| SRC `storageDoneStorage` | SRC's own val, `new SnapStorageDoneStorage(flatSlotStorage.dataSource)` | derivable from `flatSlots` |
| TNHC `healingFrontierStorage` | `HealingOrchestrator`'s lazy val: `Some(new HealingFrontierStorage(...))` only if persistence or pruned verification is on | **none** |
| TNHC `bfsQueueStorageOpt` | `HealingOrchestrator`'s lazy val `RocksDbBfsQueueStorage(flatSlots.dataSource, BfsQueueNamespace)`; the TNHC core falls back to `InMemoryBfsQueueStorage` | **none** |

Options. This document does not choose; C-A3 is the first slice that needs the answer:

1. **`apply` takes `stateIngest` plus the derived stores, as today** (`mptStorage: MptStorage`, `storageDone`, ...).
   Only the parameters that are `StateIngest` members are replaced. Smallest change, behaviour-neutral. SC-009 is not
   met for the derived types, and C-A4, C-H2 and C-S3's `storageDoneStorage` lower the total only by what they drop.
2. **`StateIngest` grows members for the derived stores**, through a T054b capability PR. A member such as `storageDone`
   or `snapProgress` would be a `val` constructor parameter of `Stores`, which `capability_structure` allows. The cap of
   9 grows, so it needs a justification line, and ADR INF-006 decision 1 ("members narrow with their callers") needs a
   note. **Per-pivot `MptStorage` cannot be a `val`**: it is created lazily per pivot and reset on restart, so it needs a
   method such as `trieStore(pivot)` with the caching kept in the core, or it stays a parameter (option 1).
   `HealingFrontierStorage` and `BfsQueueStorage` are built lazily today; building them strictly at construction would
   change when their column family / namespace is first touched, so vault must review that.
3. **Coordinators derive the stores themselves from `StateIngest`** (`stateIngest.stateTries.getBackingStorage(pivot)`,
   `new SnapStorageDoneStorage(stateIngest.flatSlots.dataSource)`). This is a **behaviour change**. On the Hash scheme
   (ETC) a second `getBackingStorage` call returns a second `MptStorage` instance, which does not share the core's
   instance and its reference-count bookkeeping, so it needs forge and vault. It is listed only for completeness.

### 4.4 D-T060-2 (user decision): the recovery actors

`SyncController` spawns `BytecodeRecoveryActor` and `StorageRecoveryActor` (package `blockchain.sync`), which call
`snap.actors.ByteCodeCoordinator(evmCodeStorage = ...)` and `actors.StorageRangeCoordinator(mptStorage = ...,
flatSlotStorage = ...)` directly. They hold only `stateStorage`, `appStateStorage` and `flatSlotStorage`, plus
`evmCodeStorage` for bytecode recovery: not the eight handles `StateIngest.apply` takes. `StateIngest` is
`private[snap]`. Options:

- (a) widen `StateIngest` to `private[sync]` and have `SyncController` build one and pass it to both recovery actors
  (`src/main` changes outside `snap/`; both run on ETC at startup, so forge signs off);
- (b) keep a raw-store entry point on each coordinator's companion for the recovery actors, next to the
  `StateIngest` `apply`. Two `apply` overloads cannot both declare defaults, so it needs another name.
  `ChildFactoriesSpec` reads only `apply`'s defaults and is unaffected. It leaves a raw path into `snap/actors/`, so
  SC-009 is not met;
- (c) move the recovery actors' coordinator construction behind `ChildFactories` (a larger change, out of spec 016's
  scope).

C-B1 cannot change `ByteCodeCoordinator.apply` until this is decided.

## 5. Zero-reference slices (the tasks.md exception)

"A slice whose moved code holds no storage reference at all: the PR says so, with the grep, and the total stays
unchanged." By step 8's own counter, at the baseline:

| Slice | Ratchet refs in moved code | Genuine store uses | Verdict |
|---|---|---|---|
| C-B1 `ByteCodeDispatch` half | 0 | 0 | zero-reference (the PR's other half holds 3) |
| C-A1 `AccountPeerDispatch` | 0 | 0 | **exception applies** |
| C-A2 `AccountTaskLifecycle` | 0 | 0 | **exception applies** |
| C-S1 `StoragePeerDispatch` | 2 (`ForceCompleteStorage` in `handleTimeout`) | 0 | exception applies **in substance**; the grep shows 2 non-store matches. T060b item 4 (`STORAGE_NAME_ALLOW += ForceCompleteStorage`) makes it 0 |
| C-S4 `StorageResponseProcessing` | 3 (`.setStoragePendingTries` ×2, `.validateStorageRanges`) | 0 | exception applies in substance; T069's "proof verification reads" wording is amended (§3.3) |
| C-H4 `HealDispatch` | 0 | 0 | **exception applies** |

The grep each of these PRs records. `<module>.scala` is the new module file, and the count must match the table:

```
python3 -I - <<'EOF'
import importlib.util; s=importlib.util.spec_from_file_location("v","scripts/snap-split/verify.py")
v=importlib.util.module_from_spec(s); s.loader.exec_module(v)
t=open("src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/<dir>/<module>.scala").read()
print([m for m in v.STORAGE_REF_RE.findall(v.strip_comments(t)) if m.lstrip(".") not in v.STORAGE_NAME_ALLOW])
EOF
```

Consequence for tasks.md: "Narrowing takes `StateIngest` where it touches storage" and "lowers the baseline total" are
both vacuous for these slices. Each coordinator's signature change must therefore be pinned to a named slice: the
first slice whose code uses that parameter (§4.2). That is C-B1 for BCC; C-A3 for ARC (`mptStorage`,
`pathNodeStorage`) and C-A4 for `storageDone`; C-S2 for SRC (`mptStorage`, `pathNodeStorage`, `snapProgressStorage`)
and C-S3 for `flatSlotStorage`; C-H1 for TNHC (`mptStorage`, `healingFrontierStorage`), C-H2 for `bfsQueueStorageOpt`,
C-H3 for `pathNodeStorageOpt` and C-H5 for `evmCodeStorage`. A parameter that the core also uses is substituted in the
core in the same commit.

## 6. Tooling gap analysis: can `verify.py` verify a C-* slice as specified?

**The expected shape**:
1. a byte-identical move into a module trait (with the M8c temporary-trait pattern where scalafmt or rule (d) needs it);
2. a narrowing whose self-type takes `StateIngest`;
3. the coordinator's `apply` and its `ChildFactories` method changing together, from raw storage parameters to a
   `StateIngest`;
4. `ChildFactoriesSpec` unchanged and green.

### 6.1 What the Pekko Typed shape changes

- **Moves (steps 1–2) are unaffected.** `apply` and the `Command` ADT are in the companion, which no module takes. The
  behaviours are `def`s of the Impl; a whole behaviour such as ARC's `finalizing` moves as a symbol. The temporary
  self-type `self: ByteCodeCoordinatorImpl =>` matches both `IMPL_RE` (`CoordinatorImpl`) and step 1's `added_ok`. The
  Impls are `private` (BCC, ARC) or `private[actors]` (SRC, TNHC), and a module file in `actors/<dir>/` sees them: the
  controller modules in `snap.controller` already compile against the `private` `SNAPSyncControllerImpl` in `snap`.
- **Arms cannot be moved by `# moved:`.** Step 2 extracts bodies by declared symbol, and an arm is not a symbol. A
  C-* slice that wants an arm in a module (A-B1's `skipPresent` is the only such case) lifts it first, in a plain
  restructure commit. C-H0 is that commit for TNHC.
- **Impl-nested types** (§3.1, §3.3, §3.4) need a `# moved:` into the companion before narrowing. That is ordinary
  step 1/2 material and not a gap.
- **Actor-driven tests do not affect the rules.** Rule (b) is textual (`new Stub\w*State`, no
  SyncTest/IntegrationTest/SlowTest/DisabledTest tag), and step 3 checks only changed lines of existing test files.
  The stub test should still pick a body that needs no `ActorContext`: the response-size window, cooldown, or
  `filterAndDedupeCodeHashes`. Otherwise it needs a `BehaviorTestKit`. The `TOKEN_RE` words that actor tests use
  (`expect*`, `receive*`, `within`, `timeout`) matter only on changed lines of existing specs. The construction lines
  that a signature change edits carry none: `requestTimeout = ...` does not match `\btimeout\b`.
- **`ChildFactoriesSpec` holds by construction.** It compares `<method>$default$<n>` getters by position. Replacing a
  parameter in the factory and in `apply` alike, or removing defaulted parameters from both, keeps the two maps equal,
  and `seam should not be empty` still holds because every coordinator keeps defaulted parameters. **No gap.**

### 6.2 Rules that reject a correct C-* slice

| # | Rule (`verify.py`) | What it rejects | Why |
|---|---|---|---|
| G1 | step 6 `check_signature_only` | the `apply` parameter lines in the companion (`evmCodeStorage: EvmCodeStorage,` → `stateIngest: StateIngest,`) and the `new *Impl(...)` argument lines | not in a type-header region (`header_regions` covers class/trait/object headers only) and not an abstract declaration in a `*State`/`*Api`/capability trait; `norm_sig` of the old and new lines differ |
| G2 | step 6 | Impl constructor parameter lines **past the first 12 header lines**: ARC `pathNodeStorage` (L83), `storageDone` (L93); SRC `snapProgressStorage` (L81), `pathNodeStorage` (L83); TNHC `bfsQueueStorageOpt` (L62), `pathNodeStorageOpt` (L64), `evmCodeStorage` (L102) | `header_regions` scans 12 lines, then admits only `extends`/`with` lines up to `HEADER_SCAN_MAX` (80). Parameters within the first 12 lines pass (BCC `evmCodeStorage` L36; `mptStorage` in ARC, SRC, TNHC) |
| G3 | step 6 | the `ChildFactories` method's parameter and forwarding lines | not a header; `ChildFactories` is a plain trait whose methods have bodies |
| G4 | step 6 | the spawn-site argument lines in `DownloadSupervisor` / `HealingOrchestrator` (`evmCodeStorage = stateIngest.bytecode,` → `stateIngest = stateIngest,`) | body lines of controller modules |
| G5 | step 6 | the call-site substitutions in moved and core bodies (`evmCodeStorage.put(...)` → `stateIngest.bytecode.put(...)`) | body lines; step 6 has no substitution allowance (that exists only in capability mode) |
| G6 | step 6 | `BytecodeRecoveryActor` / `StorageRecoveryActor` call-site changes | step 6 checks every changed `src/main` `.scala` file (D-T060-2) |
| G7 | capability mode (`check_capability_commit`, `prepare`) | carrying G1–G5 as a `# capability: StateIngest` commit inside the C-* PR | the trailer is rejected on a narrowing commit, and a capability PR may contain no move or narrowing commit; the substitution check rejects the parameter-type changes (`ChildFactories` "signatures do not change"); `src/main` outside `snap/` may not change; the map covers only the eight env handle names, so `pathNodeStorage` (ARC, SRC), `mptStorage` and the derived stores have no entry, and TNHC's `evmCodeStorage` is `Option`-typed, so `evmCodeStorage` → `stateIngest.bytecode` would change its type |
| G8 | (absence of a rule) | nothing, which is the gap | a **plain prerequisite commit** (no `# moved:`, no narrowing, no `# capability:`) on a `refactor/snap-016-*` branch can carry G1–G6 and pass: only steps 3, 4, 5 and 8 run on it. The commit that lowers the total would be the least-checked commit in the PR. T041a, T044, M5b and M11 used plain prerequisite commits for one-line env/`val` changes, but G1–G6 is about 40 to 120 lines per coordinator |
| G9 | step 6 / `declaration_only_regions`, `counts_report` | a per-coordinator env trait (`ByteCodeEnv`: `context`, `timers`, `log`, `self`, `networkPeerManager`, `requestTracker`, `snapSyncController`, `stateIngest`, ...) added in the narrowing commit | only traits named `*State`/`*Api` or in the hard-coded `CAPABILITY_TRAITS` are declaration-only regions; elsewhere abstract members are "non-signature lines". `counts_report` would also report the env as a module with an empty self-type |
| G10 | step 8 `STORAGE_REF_RE` | not a rejection: it miscounts | false positives (§4.2) make C-S1 and C-S4 non-zero, and a rename in R1 or a new metric setter moves the total without any coupling change. `storageDone` (a real store) is not counted |

Rules that do **not** reject a correct slice: rule (a) (`IMPL_RE` already matches `CoordinatorImpl`), rule (d),
steps 1–2 for the method moves, step 4 (`ChildFactoriesSpec` and `SNAPSyncControllerPinSpec` stay untouched if
`RecordingChildFactories` keeps the `ChildSpawn` fields, §3.4), step 7 (b) and `check_final`.

**Answer.** A narrowing commit may not change `apply` or `ChildFactories` signatures under today's rules (G1, G3), and
for most coordinators it may not change the Impl constructor either (G2). Splitting the change into a separate commit
does not help: capability mode rejects it (G7) and a plain commit is unchecked (G8).

### 6.3 Proposed minimal tooling change: T060b (not implemented here)

Add a **StateIngest adoption** allowance, selected per commit by the trailer `# adopts: StateIngest <Coordinator>` on
a `refactor/snap-016-*` narrowing commit. It reuses capability mode's token machinery (`scala_tokens`,
`substitution_check`, `param_lists`) and adds nothing outside `verify.py`:

1. **Parameter replacement.** In the named coordinator's Impl constructor, its companion `apply`, the
   `new <Coordinator>Impl(...)` call in `apply`, and the matching `ChildFactories` method: storage-typed parameters
   (`storage_typed`) may be removed. Exactly one `stateIngest: StateIngest` (or `Option[StateIngest]`, for TNHC's
   healed-code check) may be added, at the position of the first removed parameter. The parameters that remain keep
   their names, types, defaults and order. This keeps `ChildFactoriesSpec` true by construction, and the check tests
   it: the forwarding lines in `ChildFactories` and in `apply` are `name = name` for every parameter. Optionally, the
   rule may require each removed parameter's default to be `None`, or the parameter to have no default.
2. **Call-site substitution.** In every other changed `src/main` file under `snap/` (core bodies, module bodies, spawn
   sites), `substitution_check` with a per-coordinator map declared in the coordinator file:
   `// adopt-map: evmCodeStorage -> stateIngest.bytecode`, `pathNodeStorage -> stateIngest.pathNodes`, and so on. The
   left side must be a removed parameter; the right side is `stateIngest.<member>` of a declared `StateIngest` member.
   A spawn site may replace `<param> = <expr>` argument lines with one `stateIngest = stateIngest` line, but only for
   removed parameters.
3. **Test files.** `RecordingChildFactories` and direct-construction call sites change under the unchanged test-hunk
   guard (step 3). `ChildFactoriesSpec` and `SNAPSyncControllerPinSpec` stay protected.
4. **`STORAGE_NAME_ALLOW += {"ForceCompleteStorage"}`** (a `Command` object). This is a reviewed allow-list change
   that lowers SRC by 5 at no coupling cost, so `storage-baseline.json` is re-baselined in the same PR. Optionally, also
   exempt the `SNAPSyncMetrics.setStorage*` selectors and the `isNodeInStorage` / `.isStorage` names (another 34),
   which brings the coordinators' floor (§4.2) near zero.
5. **Coordinator env traits.** Treat a trait named `<Coordinator>Env` under `snap/actors/` as a capability trait:
   declaration-only for step 6, and reported on the `[counts] capability traits` line. This removes G9 without another
   prerequisite commit. The alternative is to keep using a prerequisite commit (T041a); in that case only item 1–3's
   rules are needed.
6. **SC-009 check (optional).** A type-level list of the storage type names (`*Storage` types and the raw imports)
   per file under `snap/actors/`, recorded in the baseline, so SC-009 is measured rather than inferred from the total.
7. **Recovery actors (only if D-T060-2 chooses (a)).** Allow `blockchain/sync/{Bytecode,Storage}RecoveryActor.scala`
   and `SyncController.scala` to change only by the same substitution rules.
8. **Self-tests**: a C-B1-shaped positive case, plus negatives (a parameter reordered, a default changed, an extra
   parameter, a substitution to the wrong member, a spawn site dropping a non-storage argument, the trailer on a
   non-narrowing commit, the trailer naming a coordinator that the commit does not touch).

## 7. Slice order and validation runs

Default: tasks.md order. **BCC first (T061) is still the lowest-coupling start**:
- 20 fields and 10 refs;
- its store maps one-to-one onto `bytecode`;
- one PR, with State 9 + 7 and Api 1 + 9;
- its only external dependency is `BytecodeRecoveryActor` (D-T060-2).

That makes it the right probe for the adoption shape. Proposed sequence:

| Step | Slice | Prerequisite | Vault | Node runs |
|---|---|---|---|---|
| 1 | **T060b** tooling (§6.3) | — | — | — |
| 2 | **D-T060-2** decided | — | — | — |
| 3 | C-B1 (T061), with the `ByteCodeCoordinator` adoption | T060b, D-T060-2 | **add vault** (code-write path and recovery wiring) | Mordor SNAP through the bytecode phase, or a bytecode-recovery start, **if** D-T060-2 (a) rewires `BytecodeRecoveryActor` |
| 4 | C-A1, C-A2 (T062–T063): zero-reference | — | — | — |
| 5 | **D-T060-1** decided | before C-A3 | — | — |
| 6 | C-A3 (T064, adopts `mptStorage`/`pathNodeStorage`), C-A4 (T065, adopts `storageDone`) | D-T060-1 | vault (already) | **Mordor restart mid-account after A4** (already T065) |
| 7 | C-S1 (T066): zero-reference in substance | (T060b item 4) | — | — |
| 8 | C-S2 (T067, adopts `mptStorage`/`pathNodeStorage`/`snapProgressStorage`) | D-T060-1 | vault (already) | **SC-010 `[STORAGE-PERF]` parity** (already) |
| 9 | C-S3 (T068, adopts `flatSlotStorage`) | — | vault (already) | **Mordor + Platåberget restart mid-storage** (already) |
| 10 | C-S4 (T069): zero-reference in substance; carries #1533's pipeline | — | — | — |
| 11 | C-H0 (T070), a plain restructure commit; first `# moved:` commit of the Impl-nested messages to the companion | — | — | — |
| 12 | C-H1 (T071, adopts `mptStorage`/`healingFrontierStorage`) | D-T060-1 | vault (already) | **Mordor heal restart** (already) |
| 13 | C-H2 (T072, adopts `bfsQueueStorageOpt`) | — | **add vault** (BFS queue store wiring; #1350/#1349 site) | — |
| 14 | C-H3 (T073, adopts `pathNodeStorageOpt`) | — | vault (already) | — |
| 15 | C-H4 (T074): zero-reference | — | — | — |
| 16 | C-H5 (T075, adopts `evmCodeStorage` as `Option`) | — | **add vault** (healed-code presence check) | Mordor heal restart recommended (the healed-code check is on the ETC path) |
| 17 | C-X0, C-X (T075b, T076) | — | — | — |

Every coordinator runs on ETC (Hash scheme) as well as on ETH, so every C-* PR needs forge's "no ETC behaviour
change" sign-off. Beacon reviews C-S2 and C-H3/C-H5, the Path-scheme node writes and reads.

---

## Appendix A: matrices

Module abbreviations: B1d = `ByteCodeDispatch`, B1r = `ByteCodeResponseHandling`, A1–A4, S1–S4 (S4 includes #1533's
pipeline), H1–H5 as in §3; `core` = everything that stays in the Impl (including every behaviour arm). In "Rule (d)",
"may move" means the field's initializer passes `member_issue`; a field touched by more than one module or by the core
is a State member regardless. In the Command tables, a `Command` case with no explicit arm falls to the behaviour's
catch-all (`case other`/`case _`): BCC, SRC and TNHC log at DEBUG and return `Behaviors.unhandled`; ARC's `receive`
logs a WARN and returns `same`; ARC's `finalizing` returns `same` silently.

### ByteCodeCoordinator: var × method matrix

| Field (decl line) | Rule (d) | Modules (W = writes) | Methods and arms (W = writes) |
|---|---|---|---|
| `maxInFlightPerPeer` (L57) | stays (init not literal) | B1d, core(W) | `active/UpdateMaxInFlightPerPeer`(W), `dispatchIfPossible` |
| `peerFailureCounts` (L59) | stays (concrete val) | B1d(W), core(W) | `clearPeerFailures`(W), `recordPeerCooldown`(W), `active/ByteCodePivotRefreshed`(W) |
| `peerCooldownUntilMillis` (L60) | stays (concrete val) | B1d(W), core(W) | `isPeerCoolingDown`, `clearPeerFailures`(W), `recordPeerCooldown`(W), `active/ByteCodePivotRefreshed`(W), `active/ByteCodePeerUnavailable`(W) |
| `hashFailureCounts` (L84) | stays (concrete val) | B1r(W) | `handleByteCodesResponse`(W) |
| `knownAvailablePeers` (L92) | stays (concrete val) | B1d, core(W) | `active/ByteCodePivotRefreshed`, `active/ByteCodePeerAvailable`(W), `active/ByteCodePeerUnavailable`(W), `notifyBackpressureIfChanged`, `tryRedispatchPendingTasks` |
| `consecutiveTaskFailures` (L97) | may move (literal init) | B1r(W), core(W) | `active/ByteCodePivotRefreshed`(W), `active/ByteCodeTaskComplete`(W), `active/ByteCodeTaskFailed`(W), `handleByteCodesResponse`(W) |
| `pendingTasks` (L101) | stays (concrete val) | B1d(W), B1r(W), core(W) | `active/StartByteCodeSync`(W), `active/AddByteCodeTasks`(W), `active/NoMoreByteCodeTasks`, `active/ByteCodePivotRefreshed`, `active/ByteCodePeerUnavailable`(W), `active/ByteCodeTaskFailed`(W), `active/ForceCompleteByteCodes`(W), `active/WorkerTerminated`(W), `active/ByteCodeGetProgress`, `active/ByteCodeStatusPulse`, `pendingHashCount`, `notifyBackpressureIfChanged`, `assignTaskToWorker`(W), `dispatchIfPossible`, `tryRedispatchPendingTasks`, `handleByteCodesResponse`(W), `checkCompletion` |
| `activeTasks` (L110) | stays (concrete val) | B1d(W), B1r(W), core(W) | `inFlightForPeer`, `active/NoMoreByteCodeTasks`, `active/ByteCodePivotRefreshed`, `active/ByteCodePeerUnavailable`(W), `active/ByteCodeTaskComplete`(W), `active/ByteCodeTaskFailed`(W), `active/ForceCompleteByteCodes`(W), `active/WorkerTerminated`(W), `active/ByteCodeGetProgress`, `active/ByteCodeStatusPulse`, `notifyBackpressureIfChanged`, `assignTaskToWorker`(W), `tryRedispatchPendingTasks`, `handleByteCodesResponse`(W), `checkCompletion` |
| `completedTaskCount` (L118) | may move (literal init) | B1r(W), core | `active/ByteCodeGetProgress`, `active/ByteCodeStatusPulse`, `handleByteCodesResponse`(W) |
| `backpressureActive` (L123) | may move (literal init) | core(W) | `active/ByteCodePivotRefreshed`(W), `notifyBackpressureIfChanged`(W) |
| `workers` (L126) | stays (concrete val) | B1d(W), core(W) | `active/ByteCodePivotRefreshed`, `active/ByteCodePeerUnavailable`, `active/WorkerTerminated`(W), `active/ByteCodeStatusPulse`, `dispatchIfPossible`, `createWorker`(W), `markWorkerIdle` |
| `idleWorkers` (L128) | stays (concrete val) | B1d(W), core(W) | `active/ByteCodePivotRefreshed`, `active/ByteCodePeerUnavailable`, `active/WorkerTerminated`(W), `assignTaskToWorker`(W), `dispatchIfPossible`, `createWorker`(W), `markWorkerIdle`(W) |
| `workerSeq` (L132) | may move (literal init) | B1d(W) | `createWorker`(W) |
| `noMoreTasksExpected` (L137) | may move (literal init) | core(W) | `active/NoMoreByteCodeTasks`(W), `active/ByteCodeTaskFailed`(W), `active/ForceCompleteByteCodes`(W), `active/ByteCodeStatusPulse`, `checkCompletion` |
| `bytecodeMilestonePct` (L138) | may move (literal init) | core(W) | `active/ByteCodeStatusPulse`(W) |
| `bytecodeStartMs` (L139) | may move (literal init) | core(W) | `start`(W), `active/ByteCodeStatusPulse` |
| `bytecodesDownloaded` (L142) | may move (literal init) | B1r(W), core(W) | `active/ByteCodeTaskComplete`(W), `active/ForceCompleteByteCodes`, `active/ByteCodeGetProgress`, `active/ByteCodeStatusPulse`, `active/receiveSignal`, `handleByteCodesResponse`(W) |
| `bytesDownloaded` (L143) | may move (literal init) | B1r(W), core | `active/ByteCodeGetProgress`, `handleByteCodesResponse`(W) |
| `bytecodesAbandoned` (L146) | may move (literal init) | core(W) | `active/ForceCompleteByteCodes`(W), `active/receiveSignal` |
| `peerResponseBytesTarget` (L156) | stays (concrete val) | B1d(W), core(W) | `responseBytesTargetFor`(W), `adjustResponseBytesTargetOnSuccess`(W), `adjustResponseBytesTargetOnFailure`(W), `active/ByteCodePivotRefreshed`(W) |

### ByteCodeCoordinator: Command × behaviour

| Command | Behaviour: arm (line) | Effect: calls (owning module), writes, sends |
|---|---|---|
| `StartByteCodeSync` | `active` L195 | calls `filterAndDedupeCodeHashes`(B1r); writes `pendingTasks` |
| `AddByteCodeTasks` | `active` L204 | calls `filterAndDedupeCodeHashes`(B1r), `notifyBackpressureIfChanged`(core); writes `pendingTasks`; sends to `snapSyncController` |
| `NoMoreByteCodeTasks` | `active` L227 | calls `checkCompletion`(core); writes `noMoreTasksExpected` |
| `ByteCodePivotRefreshed` | `active` L233 | calls `tryRedispatchPendingTasks`(B1d); writes `backpressureActive`, `consecutiveTaskFailures`, `peerCooldownUntilMillis`, `peerFailureCounts`, `peerResponseBytesTarget`; sends to `snapSyncController` |
| `ByteCodePeerAvailable` | `active` L260 | calls `dispatchIfPossible`(B1d), `isPeerCoolingDown`(B1d); writes `knownAvailablePeers` |
| `ByteCodePeerUnavailable` | `active` L267 | calls `markWorkerIdle`(B1d), `tryRedispatchPendingTasks`(B1d); writes `activeTasks`, `knownAvailablePeers`, `peerCooldownUntilMillis`, `pendingTasks`; sends to `worker` |
| `ByteCodeTaskComplete` | `active` L301 | calls `checkCompletion`(core), `clearPeerFailures`(B1d), `markWorkerIdle`(B1d), `tryRedispatchPendingTasks`(B1d); writes `activeTasks`, `bytecodesDownloaded`, `consecutiveTaskFailures` |
| `ByteCodeTaskFailed` | `active` L320 | calls `adjustResponseBytesTargetOnFailure`(B1d), `checkCompletion`(core), `markWorkerIdle`(B1d), `recordPeerCooldown`(B1d), `tryRedispatchPendingTasks`(B1d); writes `activeTasks`, `consecutiveTaskFailures`, `noMoreTasksExpected`, `pendingTasks` |
| `ByteCodeGetProgress` | `active` L388 | sends to `replyTo` |
| `ByteCodeCheckCompletion` | `active` L342 | calls `checkCompletion`(core), `notifyBackpressureIfChanged`(core) |
| `ForceCompleteByteCodes` | `active` L349 | calls `markWorkerIdle`(B1d); writes `activeTasks`, `bytecodesAbandoned`, `noMoreTasksExpected`, `pendingTasks`; sends to `snapSyncController` |
| `UpdateMaxInFlightPerPeer` | `active` L291 | calls `tryRedispatchPendingTasks`(B1d); writes `maxInFlightPerPeer` |
| `ByteCodeStatusPulse` | `active` L394 | writes `bytecodeMilestonePct` |
| `ByteCodesResponseMsg` | `active` L297 | calls `handleByteCodesResponse`(B1r) |
| `WorkerTerminated` | `active` L373 | calls `tryRedispatchPendingTasks`(B1d); writes `activeTasks`, `idleWorkers`, `pendingTasks`, `workers` |
| catch-all / signal `other` | `active` L423 | log / reply only |
| catch-all / signal `receiveSignal` | `active` L427 | log / reply only |

### AccountRangeCoordinator: var × method matrix

| Field (decl line) | Rule (d) | Modules (W = writes) | Methods and arms (W = writes) |
|---|---|---|---|
| `stateRoot` (L113) | stays (init not literal) | A1, A2, A3, core(W) | `markPeerStateless`, `maybeRequestPivotRefresh`, `allInitialTasks`, `receive/PivotRefreshed`(W), `receive/CheckDispatchStalled`, `handleTaskFailed`, `requeueOrEscalate`, `tryRedispatchPendingTasks`, `handleStoreAccountChunk`, `finalizeTrie` |
| `maxInFlightPerPeer` (L117) | stays (init not literal) | A1, A2, core(W) | `dispatchDeliberatelyPaused`, `receive/UpdateMaxInFlightPerPeer`(W), `receive/CheckDispatchStalled`, `maxWorkers`, `dispatchIfPossible`, `tryRedispatchPendingTasks` |
| `backpressureSources` (L125) | stays (concrete val) | A1(W), core | `downstreamBackpressureActive`, `storageBackpressureActive`, `receive/CheckDispatchStalled`, `applyBackpressureChange`(W) |
| `statelessPeers` (L150) | stays (concrete val) | A1(W), A2, core(W) | `isPeerStateless`, `markPeerStateless`(W), `maybeRequestPivotRefresh`, `receive/PivotRefreshed`(W), `receive/PeerAvailable`(W), `receive/CheckDispatchStalled`, `requeueOrEscalate`, `tryRedispatchPendingTasks` |
| `snaplessPeers` (L159) | stays (concrete val) | A1(W), A2, core(W) | `isPeerSnapless`, `markPeerStateless`(W), `maybeRequestPivotRefresh`, `receive/PivotRefreshed`(W), `receive/CheckDispatchStalled`, `requeueOrEscalate`, `tryRedispatchPendingTasks` |
| `emptyResponseStrikes` (L175) | stays (concrete val) | A1(W), A2, core(W) | `markPeerStateless`(W), `recordPeerSuccess`(W), `receive/PivotRefreshed`(W), `receive/PeerAvailable`(W), `receive/PeerUnavailable`(W), `requeueOrEscalate`, `tryRedispatchPendingTasks` |
| `pivotRefreshRequested` (L181) | may move (literal init) | A1(W), A2(W), core(W) | `maybeRequestPivotRefresh`(W), `receive/PivotRefreshed`(W), `receive/CheckDispatchStalled`, `requeueOrEscalate`(W) |
| `pendingTasks` (L346) | stays (concrete val) | A1(W), A2(W), A3(W), core(W) | `drainActiveTasks`(W), `onStart`, `sendProgressSnapshot`, `receive/PivotRefreshed`(W), `receive/PeerAvailable`, `receive/RecheckIntakeGate`, `receive/CheckDispatchStalled`, `dispatchIfPossible`, `applyBackpressureChange`, `dispatchNextTaskToWorker`(W), `requeueOrEscalate`(W), `tryRedispatchPendingTasks`, `handleStoreAccountChunk`(W), `calculateProgress`, `isComplete`, `computeKeyspaceEstimate` |
| `activeTasks` (L350) | stays (concrete val) | A1(W), A2(W), A3, core | `inFlightForPeer`, `drainActiveTasks`(W), `onStart`, `sendProgressSnapshot`, `receive/AccountRangeResponseMsg`, `receive/CheckDispatchStalled`, `applyBackpressureChange`, `dispatchNextTaskToWorker`(W), `handleTaskComplete`(W), `handleTaskFailed`(W), `tryRedispatchPendingTasks`, `handleStoreAccountChunk`, `calculateProgress`, `isComplete`, `computeKeyspaceEstimate` |
| `completedTasks` (L351) | stays (concrete val) | A2(W), A3(W), core | `sendProgressSnapshot`, `completeEmptyTaskRange`(W), `handleStoreAccountChunk`(W), `calculateProgress` |
| `workers` (L354) | stays (concrete val) | A1(W), A3 | `createWorker`(W), `markWorkerIdle`, `dispatchIfPossible`, `handleStoreAccountChunk` |
| `idleWorkers` (L355) | stays (concrete val) | A1(W) | `createWorker`(W), `markWorkerIdle`(W), `dispatchIfPossible`, `dispatchNextTaskToWorker`(W) |
| `workerSeq` (L359) | may move (literal init) | A1(W) | `createWorker`(W) |
| `lastDispatchOrResponseMs` (L367) | stays (init not literal) | A1(W), A2(W), core(W) | `receive/PivotRefreshed`(W), `receive/PeerUnavailable`(W), `receive/RecoverStalledAccountTasks`(W), `receive/CheckDispatchStalled`(W), `dispatchNextTaskToWorker`(W), `handleTaskComplete`(W), `handleTaskFailed`(W) |
| `pendingButIdleTicks` (L378) | may move (literal init) | core(W) | `receive/CheckDispatchStalled`(W) |
| `accountsDownloaded` (L433) | may move (literal init) | A2, A3(W), core(W) | `onStop`, `receive/CheckCompletion`, `completeEmptyTaskRange`, `handleStoreAccountChunk`(W), `calculateProgress`(W), `computeKeyspaceEstimate` |
| `bytesDownloaded` (L434) | may move (literal init) | A2(W), core(W) | `handleTaskComplete`(W), `calculateProgress`(W) |
| `lastProgressLogAt` (L436) | may move (literal init) | A3(W) | `handleStoreAccountChunk`(W) |
| `lastFlatMilestonePct` (L438) | may move (literal init) | A3(W) | `handleStoreAccountChunk`(W) |
| `consumedKeyspace` (L442) | stays (init not literal) | A2(W), A3 | `updateTaskProgress`(W), `completeEmptyTaskRange`(W), `handleStoreAccountChunk` |
| `contractAccountsCount` (L487) | may move (literal init) | A3, A4(W), core | `onStop`, `receive/AccountGetContractAccounts`, `finalizing/AccountGetContractAccounts`, `identifyContractAccounts`(W), `calculateProgress` |
| `contractStorageCount` (L488) | stays (init not literal) | A3, A4(W), core | `onStop`, `sendProgressSnapshot`, `receive/AccountGetContractStorageAccounts`, `receive/AccountGetStorageFileInfo`, `finalizing/AccountGetContractStorageAccounts`, `finalizing/AccountGetStorageFileInfo`, `identifyContractAccounts`(W) |
| `codeHashBloom` (L496) | stays (concrete val) | A4(W), core(W) | `onStart`(W), `identifyContractAccounts`(W) |
| `uniqueCodeHashesCount` (L503) | stays (init not literal) | A3, A4(W), core | `onStop`, `sendProgressSnapshot`, `receive/AccountGetCodeHashesFileInfo`, `finalizing/AccountGetCodeHashesFileInfo`, `identifyContractAccounts`(W), `readUniqueCodeHashes` |
| `replayStorageOffset` (L507) | may move (literal init) | A4(W) | `replayDone`, `replayCarriedChunk`(W) |
| `replayCodeHashesOffset` (L508) | may move (literal init) | A4(W) | `replayDone`, `replayCarriedChunk`(W) |
| `replaySkippedFinished` (L511) | may move (literal init) | A4(W) | `replayCarriedChunk`(W) |
| `replayQueuedStorage` (L512) | may move (literal init) | A4(W) | `replayCarriedChunk`(W) |
| `storingTasks` (L519) | stays (concrete val) | A2(W), A3(W), core | `sendProgressSnapshot`, `handleTaskComplete`(W), `handleStoreAccountChunk`(W), `isComplete` |
| `knownAvailablePeers` (L523) | stays (concrete val) | A1, A2, core(W) | `markPeerStateless`, `maybeRequestPivotRefresh`, `activePeerCount`, `effectivePeerCooldown`, `receive/PivotRefreshed`, `receive/PeerAvailable`(W), `receive/PeerUnavailable`(W), `receive/CheckDispatchStalled`, `maxWorkers`, `applyBackpressureChange`, `tryRedispatchPendingTasks` |
| `lastStateLogMs` (L537) | may move (literal init) | A2(W) | `tryRedispatchPendingTasks`(W) |
| `peerResponseBytesTarget` (L549) | stays (concrete val) | A1(W), core(W) | `responseBytesTargetFor`(W), `adjustResponseBytesOnSuccess`(W), `adjustResponseBytesOnFailure`(W), `receive/PivotRefreshed`(W) |
| `peerCooldownUntilMs` (L571) | stays (concrete val) | A1(W), A2(W), core(W) | `isPeerCoolingDown`, `recordPeerCooldown`(W), `receive/PivotRefreshed`(W), `receive/PeerUnavailable`(W), `receive/CheckDispatchStalled`, `tryRedispatchPendingTasks`(W) |
| `lastDispatchTimeMs` (L591) | stays (concrete val) | A1(W), A2 | `dispatchNextTaskToWorker`(W), `tryRedispatchPendingTasks` |
| `consecutiveUnproductiveRefreshes` (L603) | may move (literal init) | A1(W), A2(W) | `maybeRequestPivotRefresh`(W), `handleTaskComplete`(W) |
| `lastPivotRefreshTimeMs` (L604) | may move (literal init) | A1(W) | `maybeRequestPivotRefresh`(W) |
| `trieFlushGeneration` (L623) | may move (literal init) | core(W) | `receive/CheckCompletion`(W) |
| `taskStackTries` (L632) | stays (concrete val) | A3(W), core(W) | `onStop`(W), `sendProgressSnapshot`, `handleStoreAccountChunk`(W), `getOrCreateTaskStackTrie`(W), `finalizeTrie`(W) |
| `finalizationStarted` (L667) | may move (literal init) | core(W) | `onStop`, `sendProgressSnapshot`, `receive/CheckCompletion`(W) |

### AccountRangeCoordinator: Command × behaviour

| Command | Behaviour: arm (line) | Effect: calls (owning module), writes, sends |
|---|---|---|
| `StartAccountRangeSync` | `receive` L750 | log / reply only |
| `PeerAvailable` | `receive` L815 | calls `isPeerCoolingDown`(A1), `isPeerSnapless`(A1), `isPeerStateless`(A1), `tryRedispatchPendingTasks`(A2); writes `emptyResponseStrikes`, `knownAvailablePeers`, `statelessPeers` |
| `PeerAvailable` | `finalizing` L1159 | log / reply only |
| `TaskFailed` | `receive` L1032 | calls `handleTaskFailed`(A2) |
| `PeerUnavailable` | `receive` L867 | calls `drainActiveTasks`(A2), `tryRedispatchPendingTasks`(A2); writes `emptyResponseStrikes`, `knownAvailablePeers`, `lastDispatchOrResponseMs`, `peerCooldownUntilMs` |
| `GetProgress` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `AccountGetProgress` | `receive` L1036 | calls `calculateProgress`(core); sends to `replyTo` |
| `AccountGetProgress` | `finalizing` L1167 | calls `calculateProgress`(core); sends to `replyTo` |
| `GetContractAccounts` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `ContractAccountsResponse` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `GetContractStorageAccounts` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `ContractStorageAccountsResponse` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `GetUniqueCodeHashes` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `GetStorageFileInfo` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `GetCodeHashesFileInfo` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `CheckCompletion` | `receive` L1070 | calls `computeKeyspaceEstimate`(core), `finalizeTrie`(A3), `finalizing`(A3); writes `finalizationStarted`, `trieFlushGeneration`; sends to `selfRef`, `snapSyncController` |
| `CheckCompletion` | `finalizing` L1197 | log / reply only |
| `AccountRangeProgress` | no explicit arm: falls to the catch-all of `receive`, `finalizing` | — |
| `PivotRefreshed` | `receive` L765 | calls `drainActiveTasks`(A2), `tryRedispatchPendingTasks`(A2); writes `emptyResponseStrikes`, `lastDispatchOrResponseMs`, `peerCooldownUntilMs`, `peerResponseBytesTarget`, `pendingTasks`, `pivotRefreshRequested`, `snaplessPeers`, `stateRoot`, `statelessPeers` |
| `PivotRefreshed` | `finalizing` L1163 | log / reply only |
| `RecoverStalledAccountTasks` | `receive` L914 | calls `drainActiveTasks`(A2), `tryRedispatchPendingTasks`(A2); writes `lastDispatchOrResponseMs` |
| `StorageQueuePressure` | `receive` L859 | calls `applyBackpressureChange`(A1) |
| `ByteCodeQueuePressure` | `receive` L863 | calls `applyBackpressureChange`(A1) |
| `CheckDispatchStalled` | `receive` L933 | calls `armIntakeGateRecheck`(A1), `drainActiveTasks`(A2), `maybeRequestPivotRefresh`(A1), `tryRedispatchPendingTasks`(A2); writes `lastDispatchOrResponseMs`, `pendingButIdleTicks`; sends to `snapSyncController` |
| `CheckpointTick` | `receive` L1020 | calls `sendProgressSnapshot`(core) |
| `ReplayCarriedContracts` | `receive` L1024 | calls `replayCarriedChunk`(A4) |
| `RecheckIntakeGate` | `receive` L923 | calls `armIntakeGateRecheck`(A1), `tryRedispatchPendingTasks`(A2) |
| `UpdateMaxInFlightPerPeer` | `receive` L853 | calls `tryRedispatchPendingTasks`(A2); writes `maxInFlightPerPeer` |
| `AccountRangeResponseMsg` | `receive` L755 | sends to `worker` |
| `TrieFlushComplete` | `finalizing` (guarded) L1130 | log / reply only |
| `TrieFlushComplete` | `finalizing` L1134 | sends to `snapSyncController` |
| `TrieFlushComplete` | `finalizing` L1143 | sends to `snapSyncController` |
| `TrieFlushFailed` | `finalizing` (guarded) L1150 | log / reply only |
| `TrieFlushFailed` | `finalizing` L1154 | sends to `snapSyncController` |
| `TaskComplete` | `receive` L1028 | calls `handleTaskComplete`(A2) |
| `AccountGetContractAccounts` | `receive` L1040 | calls `readContractFile`(A4); sends to `replyTo` |
| `AccountGetContractAccounts` | `finalizing` L1171 | calls `readContractFile`(A4); sends to `replyTo` |
| `AccountGetContractStorageAccounts` | `receive` L1046 | calls `readContractFile`(A4); sends to `replyTo` |
| `AccountGetContractStorageAccounts` | `finalizing` L1177 | calls `readContractFile`(A4); sends to `replyTo` |
| `AccountGetUniqueCodeHashes` | `receive` L1052 | calls `readUniqueCodeHashes`(A4); sends to `replyTo` |
| `AccountGetUniqueCodeHashes` | `finalizing` L1183 | calls `readUniqueCodeHashes`(A4); sends to `replyTo` |
| `AccountGetStorageFileInfo` | `receive` L1056 | sends to `replyTo` |
| `AccountGetStorageFileInfo` | `finalizing` L1187 | sends to `replyTo` |
| `AccountGetCodeHashesFileInfo` | `receive` L1061 | sends to `replyTo` |
| `AccountGetCodeHashesFileInfo` | `finalizing` L1192 | sends to `replyTo` |
| `StoreAccountChunk` | `receive` L1066 | calls `handleStoreAccountChunk`(A3) |
| `WorkerTerminated` | `receive` (guarded) L890 | calls `tryRedispatchPendingTasks`(A2) |
| `WorkerTerminated` | `receive` L910 | log / reply only |
| catch-all / signal `other` | `receive` L1109 | log / reply only |
| catch-all / signal `receiveSignal` | `receive` L1115 | calls `onStop`(core) |
| catch-all / signal `_` | `finalizing` L1201 | log / reply only |
| catch-all / signal `receiveSignal` | `finalizing` L1206 | calls `onStop`(core) |

### StorageRangeCoordinator: var × method matrix

| Field (decl line) | Rule (d) | Modules (W = writes) | Methods and arms (W = writes) |
|---|---|---|---|
| `stateRoot` (L106) | stays (init not literal) | S1, S2, S3, S4, core(W) | `markPeerStateless`, `maybeRequestPivotRefresh`, `flushPendingFlatBatch`, `handleCommand/StoragePivotRefreshed`(W), `handleCommand/FlatBatchFlushComplete`, `requestNextRanges`, `processServedTasks`, `applyReadyStorageChunk`, `redispatchEligible` |
| `maxInFlightPerPeer` (L109) | stays (init not literal) | S1, core(W) | `maybeRequestPivotRefresh`, `handleCommand/UpdateMaxInFlightPerPeer`(W), `perPeerInFlightLimit` |
| `tasks` (L112) | stays (concrete val) | S1(W), S4(W), core(W) | `maybeRequestPivotRefresh`(W), `giveUpOnAccountForHealing`(W), `PendingResponse`, `enqueueResponse`, `notifyBackpressureIfChanged`, `handleCommand/AddStorageTasks`(W), `handleCommand/AddStorageTask`(W), `handleCommand/StoragePeerAvailable`, `handleCommand/StoragePeerUnavailable`(W), `handleCommand/StorageCheckCompletion`, `handleCommand/NoMoreStorageTasks`, `handleCommand/ForceCompleteStorage`, `handleCommand/StoragePivotRefreshed`(W), `handleCommand/StorageGetProgress`, `requestNextRanges`(W), `processStorageRanges`, `processServedTasks`, `handleTimeout`(W), `dispatchIfPossible`, `tryRedispatchPendingTasks`, `redispatchEligible`, `progress`, `isComplete` |
| `pendingTaskKeys` (L115) | stays (concrete val) | S1(W), S4(W), core(W) | `giveUpOnAccountForHealing`(W), `handleCommand/StoragePeerUnavailable`(W), `requestNextRanges`(W), `handleTimeout`(W) |
| `activeTasks` (L116) | stays (concrete val) | S1(W), S4(W), core(W) | `maybeRequestPivotRefresh`(W), `inFlightForPeer`, `notifyBackpressureIfChanged`, `onPostStop`, `handleCommand/StoragePeerUnavailable`(W), `handleCommand/StorageCheckCompletion`, `handleCommand/NoMoreStorageTasks`, `handleCommand/ForceCompleteStorage`(W), `handleCommand/StoragePivotRefreshed`(W), `handleCommand/StorageGetProgress`, `handleCommand/StorageRequestTimedOut`, `requestNextRanges`(W), `handleResponse`(W), `handleTimeout`(W), `dispatchIfPossible`, `redispatchEligible`, `progress`, `isComplete` |
| `completedTaskCount` (L123) | may move (literal init) | S1, S3(W), core | `recordCompletedTask`(W), `handleCommand/StorageGetProgress`, `redispatchEligible`, `progress` |
| `completedAccountCount` (L129) | may move (literal init) | S2(W), S4(W), core | `applyReadyStorageChunk`(W), `updateContractProgress`, `recordSubtaskCompletion`(W) |
| `accountSubtaskCounters` (L140) | stays (concrete val) | S2(W), S4(W) | `giveUpOnAccountForHealing`(W), `applyReadyStorageChunk`(W), `recordSubtaskCompletion`(W) |
| `backpressureActive` (L158) | may move (literal init) | core(W) | `notifyBackpressureIfChanged`(W), `handleCommand/StoragePivotRefreshed`(W) |
| `consecutiveTaskFailures` (L162) | may move (literal init) | S1(W), core(W) | `recordPeerAnswered`(W), `handleCommand/StorageTaskComplete`(W), `handleCommand/StoragePivotRefreshed`(W), `handleTimeout`(W) |
| `forceCompleteExecuted` (L169) | may move (literal init) | core(W) | `handleCommand/ForceCompleteStorage`(W) |
| `peerCooldownUntilMs` (L177) | stays (concrete val) | S1(W), core(W) | `handleCommand/StoragePeerUnavailable`(W), `handleCommand/StoragePivotRefreshed`(W), `redispatchEligible`(W), `isPeerCoolingDown`, `recordPeerCooldown`(W) |
| `lastPenalisedFloorLogMs` (L186) | may move (literal init) | S1(W) | `redispatchEligible`(W) |
| `statelessPeers` (L194) | stays (concrete val) | S1(W), S4(W), core(W) | `isPeerStateless`, `markPeerStateless`(W), `maybeRequestPivotRefresh`(W), `notifyBackpressureIfChanged`, `handleCommand/StoragePeerAvailable`(W), `handleCommand/StoragePivotRefreshed`(W), `processStorageRanges`(W), `processServedTasks`(W), `redispatchEligible` |
| `emptyResponseStrikes` (L206) | stays (concrete val) | S1(W), core(W) | `markPeerStateless`(W), `recordPeerSuccess`(W), `handleCommand/StoragePeerUnavailable`(W), `handleCommand/StoragePivotRefreshed`(W), `redispatchEligible` |
| `pivotRefreshRequested` (L208) | may move (literal init) | S1(W), core(W) | `maybeRequestPivotRefresh`(W), `handleCommand/StoragePeerAvailable`, `handleCommand/StoragePivotRefreshed`(W), `requestNextRanges`, `tryRedispatchPendingTasks` |
| `totalStorageContracts` (L215) | may move (literal init) | core(W) | `handleCommand/AddStorageTasks`(W), `updateContractProgress` |
| `consecutiveUnproductiveRefreshes` (L220) | may move (literal init) | S1(W), S4(W) | `maybeRequestPivotRefresh`(W), `processStorageRanges`(W), `processServedTasks`(W) |
| `lastPivotRefreshTimeMs` (L221) | may move (literal init) | S1(W) | `maybeRequestPivotRefresh`(W) |
| `lastDispatchOrResponseMs` (L229) | stays (init not literal) | S1(W), S4(W), core(W) | `maybeRequestPivotRefresh`, `handleCommand/StoragePivotRefreshed`(W), `requestNextRanges`(W), `processStorageRanges`(W), `processServedTasks`(W) |
| `postRefreshCooldownUntilMs` (L236) | may move (literal init) | S1, core(W) | `isPostRefreshCooldownActive`, `handleCommand/StoragePivotRefreshed`(W) |
| `storageIdleChecks` (L247) | may move (literal init) | S1(W) | `redispatchEligible`(W) |
| `peerBatchSize` (L398) | stays (concrete val) | S1(W), core(W) | `batchSizeFor`(W), `reduceBatchSize`(W), `maybeIncreaseBatchSize`(W), `handleCommand/StoragePivotRefreshed`(W) |
| `peerBatchSuccessStreak` (L399) | stays (concrete val) | S1(W), core(W) | `reduceBatchSize`(W), `maybeIncreaseBatchSize`(W), `handleCommand/StoragePivotRefreshed`(W) |
| `knownAvailablePeers` (L429) | stays (concrete val) | S1, core(W) | `markPeerStateless`, `maybeRequestPivotRefresh`, `notifyBackpressureIfChanged`, `handleCommand/StoragePeerAvailable`(W), `handleCommand/StoragePeerUnavailable`(W), `handleTimeout`, `redispatchEligible` |
| `emptyResponsesByTask` (L440) | stays (concrete val) | S4(W), core(W) | `handleCommand/StoragePivotRefreshed`(W), `processStorageRanges`(W), `processServedTasks`(W) |
| `staleRootFailuresByAccount` (L473) | stays (concrete val) | S4(W), core(W) | `giveUpOnAccountForHealing`(W), `onPostStop`(W), `handleCommand/ForceCompleteStorage`(W), `processServedTasks`(W) |
| `abandonedAccounts` (L519) | stays (concrete val) | S4(W), core(W) | `giveUpOnAccountForHealing`(W), `submitProcessingJobs`, `onPostStop`(W), `handleCommand/ForceCompleteStorage`(W), `processStorageRanges`, `processServedTasks` |
| `staleRootFailureEvents` (L525) | may move (literal init) | S4(W), core(W) | `handleCommand/StorageGetProgress`(W), `processServedTasks`(W) |
| `noMoreTasksExpected` (L530) | may move (literal init) | S1, core(W) | `handleCommand/StorageCheckCompletion`, `handleCommand/NoMoreStorageTasks`(W), `redispatchEligible`, `isComplete` |
| `storageMilestonePct` (L531) | may move (literal init) | S1(W) | `redispatchEligible`(W) |
| `slotsDownloaded` (L534) | may move (literal init) | S2(W), core(W) | `handleCommand/StorageTaskComplete`(W), `handleCommand/ForceCompleteStorage`, `handleCommand/StorageGetProgress`(W), `applyReadyStorageChunk`(W) |
| `bytesDownloaded` (L535) | may move (literal init) | S2(W), core(W) | `handleCommand/StorageGetProgress`(W), `applyReadyStorageChunk`(W) |
| `peerResponseBytesTarget` (L547) | stays (concrete val) | S1(W), core(W) | `responseBytesTargetFor`(W), `adjustResponseBytesOnSuccess`(W), `adjustResponseBytesOnFailure`(W), `handleCommand/StoragePivotRefreshed`(W) |
| `pendingAccountTries` (L590) | stays (concrete val) | S1, S2(W), S4, core(W) | `giveUpOnAccountForHealing`, `getOrCreateAccountTrie`(W), `commitAccountTrie`(W), `commitPrebuiltRoot`, `resetAccountTrie`(W), `onPostStop`(W), `handleCommand/NoMoreStorageTasks`, `handleCommand/ForceCompleteStorage`(W), `handleCommand/StoragePivotRefreshed`(W), `requestNextRanges`, `processStorageRanges`, `applyReadyStorageChunk`, `isComplete` |
| `storageTrieCursor` (L630) | stays (concrete val) | S2(W), core(W) | `clearStorageOrderingState`(W), `onPostStop`(W), `handleCommand/ForceCompleteStorage`(W), `handleCommand/StoragePivotRefreshed`(W), `applyOrderedStorageChunk`, `drainOrderedStorageChunks`, `applyReadyStorageChunk`(W) |
| `pendingOrderedChunks` (L631) | stays (concrete val) | S2(W), core(W) | `clearStorageOrderingState`(W), `onPostStop`(W), `handleCommand/ForceCompleteStorage`(W), `handleCommand/StoragePivotRefreshed`(W), `applyOrderedStorageChunk`(W), `drainOrderedStorageChunks`(W) |
| `lastAppliedSlot` (L644) | stays (concrete val) | S2(W), core(W) | `clearStorageOrderingState`(W), `onPostStop`(W), `handleCommand/ForceCompleteStorage`(W), `handleCommand/StoragePivotRefreshed`(W), `applyReadyStorageChunk`(W) |
| `pendingFlatBatchAccounts` (L747) | stays (concrete val) | S3(W), core(W) | `stageFlatSlotChunk`(W), `flushPendingFlatBatch`(W), `onPostStop`(W), `isComplete` |
| `pendingFlatBatchEntries` (L751) | may move (literal init) | S3(W), core(W) | `stageFlatSlotChunk`(W), `flushPendingFlatBatch`(W), `onPostStop`(W) |
| `inFlightFlatBatches` (L754) | may move (literal init) | S3(W), core(W) | `flushPendingFlatBatch`(W), `handleCommand/FlatBatchFlushComplete`(W), `handleCommand/FlatBatchFlushFailed`(W), `isComplete` |
| `pendingDoneMarkers` (L775) | stays (concrete val) | S3(W), core(W) | `stageDoneMarker`(W), `takeDoneMarkersFor`(W), `onPostStop`(W), `handleCommand/FlushStorageDoneMarkers`, `handleCommand/FlatBatchFlushFailed`(W) |
| `doneMarkersDisabled` (L778) | may move (literal init) | S3, core(W) | `stageDoneMarker`, `takeDoneMarkersFor`, `handleCommand/FlatBatchFlushFailed`(W) |
| `flatBatchSeq` (L781) | may move (literal init) | S3(W), core(W) | `stageDoneMarker`, `flushPendingFlatBatch`(W), `onPostStop`(W) |
| `flatBatchesDoneThrough` (L784) | may move (literal init) | S3(W) | `recordFlatBatchDone`(W), `takeDoneMarkersFor` |
| `flatBatchesDoneAhead` (L785) | stays (concrete val) | S3(W) | `recordFlatBatchDone`(W) |
| `pendingResponses` (L915) | stays (concrete val) | S1, S4(W), core | `enqueueResponse`(W), `submitProcessingJobs`, `drainProcessedResponses`(W), `logStoragePerf`, `abandonPendingResponses`(W), `handleCommand/StoragePeerUnavailable`, `handleCommand/StorageCheckCompletion`, `handleCommand/NoMoreStorageTasks`, `handleCommand/StorageGetProgress`, `handleCommand/StorageResponseProcessed`, `dispatchIfPossible`, `redispatchEligible`, `progress`, `isComplete` |
| `pendingResponseBytes` (L916) | may move (literal init) | S1, S4(W) | `enqueueResponse`(W), `drainProcessedResponses`(W), `abandonPendingResponses`(W), `dispatchIfPossible` |
| `runningProcessingJobs` (L917) | may move (literal init) | S1, S4(W), core(W) | `submitProcessingJobs`(W), `logStoragePerf`, `handleCommand/StorageResponseProcessed`(W), `redispatchEligible` |
| `processingGeneration` (L918) | may move (literal init) | S4(W), core | `enqueueResponse`, `abandonPendingResponses`(W), `handleCommand/StorageResponseProcessed` |
| `nextResponseSeq` (L919) | may move (literal init) | S4(W) | `enqueueResponse`(W) |
| `trieNodeWrites` (L931) | stays (concrete val) | S2(W) | `getOrCreateAccountTrie`(W), `flushTrieNodeWrites`(W), `writePrebuiltTrie`(W) |
| `trieWriteNanos` (L934) | may move (literal init) | S2(W), S4(W) | `flushTrieNodeWrites`(W), `drainProcessedResponses`(W), `logStoragePerf` |
| `inlineVerifyNanos` (L935) | may move (literal init) | S4(W) | `drainProcessedResponses`(W), `logStoragePerf`, `processServedTasks`(W) |
| `lastStateLogMs` (L2248) | may move (literal init) | S1(W) | `redispatchEligible`(W) |
| `lastReportedCompletedAccountCount` (L2344) | may move (literal init) | core(W) | `updateContractProgress`(W) |

### StorageRangeCoordinator: Command × behaviour

| Command | Behaviour: arm (line) | Effect: calls (owning module), writes, sends |
|---|---|---|
| `StartStorageRangeSync` | `handleCommand` L1206 | log / reply only |
| `AddStorageTasks` | `handleCommand` L1211 | calls `notifyBackpressureIfChanged`(core); writes `tasks`, `totalStorageContracts` |
| `AddStorageTask` | `handleCommand` L1224 | writes `tasks` |
| `StoragePeerAvailable` | `handleCommand` L1229 | calls `dispatchIfPossible`(S1), `isPeerCoolingDown`(S1), `isPeerStateless`(S1); writes `knownAvailablePeers`, `statelessPeers` |
| `StoragePeerUnavailable` | `handleCommand` L1256 | calls `tryRedispatchPendingTasks`(S1); writes `activeTasks`, `emptyResponseStrikes`, `knownAvailablePeers`, `peerCooldownUntilMs`, `pendingTaskKeys`, `tasks` |
| `StorageTaskComplete` | `handleCommand` L1292 | writes `consecutiveTaskFailures`, `slotsDownloaded`; sends to `self` |
| `StorageTaskFailed` | no explicit arm: falls to the catch-all of `handleCommand` | — |
| `StorageGetProgress` | `handleCommand` L1511 | writes `bytesDownloaded`, `slotsDownloaded`, `staleRootFailureEvents`; sends to `replyTo` |
| `StorageCheckCompletion` | `handleCommand` L1303 | calls `flushPendingFlatBatch`(S3), `maybeRequestPivotRefresh`(S1), `notifyBackpressureIfChanged`(core), `tryRedispatchPendingTasks`(S1), `updateContractProgress`(core); sends to `snapSyncController` |
| `StoragePivotRefreshed` | `handleCommand` L1368 | calls `abandonPendingResponses`(S4), `flushPendingFlatBatch`(S3); writes `activeTasks`, `backpressureActive`, `consecutiveTaskFailures`, `emptyResponseStrikes`, `emptyResponsesByTask`, `lastAppliedSlot`, `lastDispatchOrResponseMs`, `peerBatchSize`, `peerBatchSuccessStreak`, `peerCooldownUntilMs`, `peerResponseBytesTarget`, `pendingAccountTries`, `pendingOrderedChunks`, `pivotRefreshRequested`, `postRefreshCooldownUntilMs`, `stateRoot`, `statelessPeers`, `storageTrieCursor`, `tasks`; sends to `snapSyncController` |
| `NoMoreStorageTasks` | `handleCommand` L1322 | calls `flushPendingFlatBatch`(S3); writes `noMoreTasksExpected`; sends to `snapSyncController` |
| `ForceCompleteStorage` | `handleCommand` L1335 | calls `abandonPendingResponses`(S4), `flushPendingFlatBatch`(S3); writes `abandonedAccounts`, `activeTasks`, `forceCompleteExecuted`, `lastAppliedSlot`, `pendingAccountTries`, `pendingOrderedChunks`, `staleRootFailuresByAccount`, `storageTrieCursor`; sends to `snapSyncController` |
| `UpdateMaxInFlightPerPeer` | `handleCommand` L1282 | calls `tryRedispatchPendingTasks`(S1); writes `maxInFlightPerPeer` |
| `FlushStorageDoneMarkers` | `handleCommand` L1525 | calls `flushPendingFlatBatch`(S3) |
| `StorageRangesResponseMsg` | `handleCommand` L1288 | calls `handleResponse`(S4) |
| `StorageRequestTimedOut` | `handleCommand` L1562 | calls `handleTimeout`(S1) |
| `FlatBatchFlushComplete` | `handleCommand` L1530 | calls `recordFlatBatchDone`(S3); writes `inFlightFlatBatches`; sends to `self` |
| `FlatBatchFlushFailed` | `handleCommand` L1545 | writes `doneMarkersDisabled`, `inFlightFlatBatches`, `pendingDoneMarkers`; sends to `self` |
| `StorageResponseProcessed` | `handleCommand` L1574 | calls `drainProcessedResponses`(S4), `submitProcessingJobs`(S4); writes `runningProcessingJobs` |
| catch-all / signal `receiveSignal` | `active` L1193 | log / reply only |
| catch-all / signal `signal` | `active` L1194 | calls `onPostStop`(core) |
| catch-all / signal `signal` | `active` L1198 | calls `shutdownProcessingPool`(S4) |
| catch-all / signal `other` | `handleCommand` L1593 | log / reply only |

### TrieNodeHealingCoordinator: var × method matrix

| Field (decl line) | Rule (d) | Modules (W = writes) | Methods and arms (W = writes) |
|---|---|---|---|
| `stateRoot` (L122) | stays (init not literal) | H3, H4, H5, core(W) | `serveRoot`, `noteUnservableAttempt`, `start`, `activeBehavior/FrontierRebuildComplete`, `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingServeRootRefresh`, `activeBehavior/HealingResumeDispatch`, `activeBehavior/HealingCheckCompletion`, `activeBehavior/HealingStagnationCheck`, `requestNextBatch`, `processActiveResponse`, `reverifyCurrentRoot`, `startScopedVerification` |
| `pendingTasks` (L128) | stays (concrete val) | H1, H4(W), H5(W), core(W) | `clearPersistedFrontier`, `emitHealingFrontierGauges`, `dispatchIfPossible`, `activeBehavior/StartTrieNodeHealing`(W), `activeBehavior/HealingPeerUnavailable`(W), `activeBehavior/HealingForceComplete`(W), `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingResumeDispatch`, `activeBehavior/VerificationBFSComplete`, `activeBehavior/HealingStagnationCheck`, `activeBehavior/HealingGetProgress`, `queueNodes`(W), `requestNextBatch`(W), `processActiveResponse`(W), `handleTimeout`(W), `tryRedispatchPendingTasks`, `calculateProgress`, `isComplete`, `discoverMissingChildren`(W) |
| `pendingBackpressure` (L134) | stays (concrete val) | H1(W) | `emitHealingFrontierGauges`(W), `awaitFrontierDrain` |
| `completedTaskCount` (L136) | may move (literal init) | H5(W), core | `activeBehavior/HealingStagnationCheck`, `activeBehavior/HealingGetProgress`, `processActiveResponse`(W), `calculateProgress` |
| `healingMilestonePct` (L137) | may move (literal init) | core(W) | `activeBehavior/HealingStagnationCheck`(W) |
| `healedCodeHashesThisResponse` (L141) | stays (concrete val) | H5(W) | `recordHealedCodeHash`(W), `flushHealedCodeHashes`(W) |
| `lastHealedAtMs` (L164) | stays (init not literal) | H4(W), H5(W), core(W) | `activeBehavior/FrontierRebuilt`(W), `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingResumeDispatch`(W), `processActiveResponse`(W), `handleTimeout`(W) |
| `consecutiveIdleChecks` (L174) | may move (literal init) | core(W) | `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingResumeDispatch`(W), `activeBehavior/HealingStagnationCheck`(W) |
| `lastPulseHealedCount` (L175) | may move (literal init) | core(W) | `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingStagnationCheck`(W) |
| `consecutiveStagnations` (L178) | may move (literal init) | core(W) | `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingResumeDispatch`(W), `activeBehavior/HealingStagnationCheck`(W) |
| `trieWalkInProgress` (L180) | may move (literal init) | H3, core(W) | `activeBehavior/FrontierRebuildComplete`, `activeBehavior/FrontierWalkFailed`(W), `activeBehavior/HealingPivotRefreshed`, `activeBehavior/HealingCheckCompletion`, `activeBehavior/WalkStateChanged`(W), `activeBehavior/HealingStagnationCheck`, `reverifyCurrentRoot` |
| `verificationPassComplete` (L190) | may move (literal init) | H3(W), core(W) | `activeBehavior/FrontierRebuildComplete`(W), `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingCheckCompletion`, `activeBehavior/VerificationBFSComplete`(W), `activeBehavior/HealingStagnationCheck`, `reverifyCurrentRoot`(W) |
| `consecutiveStaleDiscards` (L193) | may move (literal init) | core(W) | `activeBehavior/VerificationBFSComplete`(W) |
| `verificationBFSRunning` (L200) | may move (literal init) | H2(W), H3(W), core(W) | `publishWalkLocality`, `activeBehavior/FrontierRebuildComplete`(W), `activeBehavior/FrontierWalkFailed`(W), `activeBehavior/HealingPivotRefreshed`, `activeBehavior/HealingCheckCompletion`, `activeBehavior/VerificationBFSComplete`(W), `activeBehavior/HealingStagnationCheck`, `startFrontierBFS`(W), `reverifyCurrentRoot`, `startVerificationBFS`(W), `startScopedVerification`(W) |
| `consecutiveDeadPulses` (L202) | may move (literal init) | core(W) | `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingStagnationCheck`(W) |
| `childrenDiscoveredTotal` (L204) | may move (literal init) | H5(W) | `discoverMissingChildren`(W) |
| `activeRequests` (L213) | stays (concrete val) | H1, H4(W), H5(W), core(W) | `clearPersistedFrontier`, `emitHealingFrontierGauges`, `inFlightForPeer`, `dispatchIfPossible`, `activeBehavior/HealingPeerUnavailable`(W), `activeBehavior/HealingForceComplete`(W), `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/VerificationBFSComplete`, `activeBehavior/HealingStagnationCheck`, `activeBehavior/HealingGetProgress`, `activeBehavior/HealingRequestTimeout`, `requestNextBatch`(W), `handleResponse`, `processActiveResponse`(W), `handleTimeout`(W), `calculateProgress`, `isComplete` |
| `maxInFlightPerPeer` (L217) | may move (literal init) | H4, core(W) | `dispatchIfPossible`, `activeBehavior/UpdateMaxInFlightPerPeer`(W) |
| `totalNodesHealed` (L220) | may move (literal init) | H1, H4, H5(W), core(W) | `flushRawNodesSync`, `activeBehavior/FrontierRebuildComplete`, `activeBehavior/HealingForceComplete`, `activeBehavior/HealingPivotRefreshed`, `activeBehavior/FlushComplete`, `activeBehavior/HealingTaskComplete`(W), `activeBehavior/HealingCheckCompletion`, `activeBehavior/VerificationBFSComplete`, `activeBehavior/HealingStagnationCheck`, `activeBehavior/HealingGetProgress`, `processActiveResponse`(W), `handleTimeout`, `calculateNodesPerSecond` |
| `totalBytesReceived` (L221) | may move (literal init) | H5(W), core | `activeBehavior/HealingGetProgress`, `processActiveResponse`(W), `calculateKilobytesPerSecond` |
| `healRate` (L227) | may move (literal init) | H4(W), core | `activeBehavior/HealingStagnationCheck`, `updateHealThrottle`(W) |
| `healThrottle` (L228) | may move (literal init) | H4(W) | `updateHealThrottle`(W), `effectiveBatchSize` |
| `healPending` (L229) | may move (literal init) | H4(W) | `updateHealThrottle`(W) |
| `lastThrottleAdjustMs` (L230) | stays (init not literal) | H4(W) | `updateHealThrottle`(W) |
| `knownAvailablePeers` (L357) | stays (concrete val) | H4, H5(W), core(W) | `activeBehavior/HealingPeerAvailable`(W), `activeBehavior/HealingPeerUnavailable`(W), `activeBehavior/HealingResumeDispatch`, `activeBehavior/HealingStagnationCheck`, `processActiveResponse`(W), `tryRedispatchPendingTasks` |
| `pendingHashSet` (L360) | stays (concrete val) | H4(W), H5(W), core(W) | `activeBehavior/StartTrieNodeHealing`(W), `activeBehavior/HealingPeerUnavailable`(W), `activeBehavior/HealingForceComplete`(W), `activeBehavior/HealingPivotRefreshed`(W), `queueNodes`(W), `requestNextBatch`(W), `processActiveResponse`(W), `handleTimeout`(W), `discoverMissingChildren`(W) |
| `pathAliases` (L366) | stays (concrete val) | H5(W), core(W) | `activeBehavior/HealingForceComplete`(W), `activeBehavior/HealingPivotRefreshed`(W), `queueNodes`(W), `processActiveResponse`(W) |
| `healedPathsThisRound` (L377) | stays (concrete val) | H5(W), core | `clearHealedPathsSet`(W), `activeBehavior/HealingCheckCompletion`, `processActiveResponse`(W) |
| `healedPathsRoot` (L381) | may move (literal init) | H5(W), core | `clearHealedPathsSet`(W), `activeBehavior/HealingCheckCompletion`, `processActiveResponse`(W) |
| `healedPathsOverflowed` (L384) | may move (literal init) | H5(W), core | `clearHealedPathsSet`(W), `activeBehavior/HealingCheckCompletion`, `processActiveResponse`(W) |
| `scopedVerificationStartMs` (L390) | may move (literal init) | H3(W), core | `activeBehavior/VerificationBFSComplete`, `startScopedVerification`(W) |
| `scopedVerificationSeedCount` (L391) | may move (literal init) | H3(W), core | `activeBehavior/VerificationBFSComplete`, `startScopedVerification`(W) |
| `scopedVerificationActive` (L392) | may move (literal init) | H3(W), core(W) | `activeBehavior/VerificationBFSComplete`(W), `reverifyCurrentRoot`(W), `startVerificationBFS`(W), `startScopedVerification`(W) |
| `prunedVerificationActive` (L407) | may move (literal init) | H3(W), core(W) | `activeBehavior/VerificationBFSComplete`(W), `reverifyCurrentRoot`(W), `startVerificationBFS`(W) |
| `prunedVerificationStartMs` (L408) | may move (literal init) | H3(W), core | `activeBehavior/VerificationBFSComplete`, `startVerificationBFS`(W) |
| `prunedSubtreeCount` (L411) | stays (concrete val) | H2(W), H3(W), core | `activeBehavior/VerificationBFSComplete`, `rebuildFrontierBFS`(W), `startVerificationBFS`(W) |
| `serveRoot` (L429) | stays (init not literal) | H4, H5, core(W) | `noteUnservableAttempt`, `start`, `activeBehavior/HealingServeRootRefresh`(W), `requestNextBatch`, `processActiveResponse` |
| `healAttempts` (L436) | stays (concrete val) | H1, H4(W), H5(W), core(W) | `emitHealingFrontierGauges`, `noteUnservableAttempt`(W), `activeBehavior/HealingServeRootRefresh`(W), `processActiveResponse`(W) |
| `crossRootHealCount` (L438) | may move (literal init) | H1, H5(W) | `emitHealingFrontierGauges`, `processActiveResponse`(W) |
| `serveRootRefreshCount` (L440) | may move (literal init) | core(W) | `activeBehavior/HealingServeRootRefresh`(W) |
| `statelessPeers` (L485) | stays (concrete val) | H4, H5(W), core(W) | `dispatchIfPossible`, `activeBehavior/HealingPeerAvailable`, `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingStagnationCheck`(W), `requestNextBatch`, `processActiveResponse`(W), `tryRedispatchPendingTasks` |
| `emptyResponseStrikes` (L493) | stays (concrete val) | H5(W), core(W) | `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingStagnationCheck`(W), `processActiveResponse`(W) |
| `pivotRefreshRequested` (L495) | may move (literal init) | H4(W), H5(W), core(W) | `dispatchIfPossible`, `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingResumeDispatch`(W), `activeBehavior/HealingStagnationCheck`(W), `requestNextBatch`, `processActiveResponse`(W), `handleTimeout`(W), `tryRedispatchPendingTasks` |
| `pivotRefreshRequestedAt` (L496) | may move (literal init) | H4(W), H5(W), core(W) | `activeBehavior/HealingStagnationCheck`(W), `processActiveResponse`(W), `handleTimeout`(W) |
| `peerResponseBytesTarget` (L506) | stays (concrete val) | H4(W), core(W) | `responseBytesTargetFor`(W), `adjustResponseBytesOnSuccess`(W), `adjustResponseBytesOnFailure`(W), `activeBehavior/HealingPivotRefreshed`(W) |
| `peerCooldownUntilMs` (L528) | stays (concrete val) | H4(W), core(W) | `isPeerCoolingDown`, `recordPeerCooldown`(W), `activeBehavior/HealingPivotRefreshed`(W), `activeBehavior/HealingStagnationCheck`(W), `tryRedispatchPendingTasks`(W) |
| `rawNodeBuffer` (L555) | stays (concrete val) | H1(W), H4, H5(W), core | `flushRawNodesSync`(W), `flushRawNodesAsync`(W), `activeBehavior/FlushComplete`, `updateHealThrottle`, `processActiveResponse`(W) |
| `flushing` (L557) | may move (literal init) | H1(W), H3, core(W) | `flushRawNodesAsync`(W), `activeBehavior/FrontierRebuildComplete`, `activeBehavior/FlushComplete`(W), `activeBehavior/HealingCheckCompletion`, `reverifyCurrentRoot` |
| `pendingSubtreeRecords` (L566) | stays (concrete val) | H1(W), H5(W) | `flushRawNodesSync`(W), `flushRawNodesAsync`(W), `discoverMissingChildren`(W) |

### TrieNodeHealingCoordinator: Command × behaviour

| Command | Behaviour: arm (line) | Effect: calls (owning module), writes, sends |
|---|---|---|
| `HealingStagnationCheck` | `activeBehavior` L1357 | calls `emitHealingFrontierGauges`(H1), `startVerificationBFS`(H3), `tryRedispatchPendingTasks`(H4); writes `consecutiveDeadPulses`, `consecutiveIdleChecks`, `consecutiveStagnations`, `emptyResponseStrikes`, `healingMilestonePct`, `lastPulseHealedCount`, `peerCooldownUntilMs`, `pivotRefreshRequested`, `pivotRefreshRequestedAt`, `statelessPeers`; sends to `snapSyncController` |
| `FlushComplete` | `activeBehavior` L1177 | calls `flushRawNodesAsync`(H1); writes `flushing`; sends to `self` |
| `FrontierRebuilt` | `activeBehavior` L846 | calls `queueNodes`(H5), `tryRedispatchPendingTasks`(H4); writes `lastHealedAtMs` |
| `VerificationBFSComplete` | `activeBehavior` (guarded) L1269 | calls `reverifyCurrentRoot`(H3); writes `consecutiveStaleDiscards`, `prunedVerificationActive`, `scopedVerificationActive`, `verificationBFSRunning`, `verificationPassComplete`; sends to `snapSyncController` |
| `VerificationBFSComplete` | `activeBehavior` (guarded) L1292 | calls `reverifyCurrentRoot`(H3), `tryRedispatchPendingTasks`(H4); writes `consecutiveStaleDiscards`, `prunedVerificationActive`, `scopedVerificationActive`, `verificationBFSRunning`, `verificationPassComplete` |
| `VerificationBFSComplete` | `activeBehavior` L1307 | calls `tryRedispatchPendingTasks`(H4); writes `consecutiveStaleDiscards`, `prunedVerificationActive`, `scopedVerificationActive`, `verificationBFSRunning`, `verificationPassComplete`; sends to `self` |
| `FrontierRebuildComplete` | `activeBehavior` L858 | writes `verificationBFSRunning`, `verificationPassComplete`; sends to `self` |
| `FrontierWalkFailed` | `activeBehavior` L913 | writes `trieWalkInProgress`, `verificationBFSRunning` |
| `RestartResumeVerification` | `activeBehavior` L1508 | calls `startVerificationBFS`(H3) |
| `RestartFullRebuild` | `activeBehavior` L1513 | calls `startFrontierBFS`(H2); sends to `self` |
| `StartTrieNodeHealing` | `activeBehavior` L714 | calls `isNodeInStorage`(H5), `persistFrontier`(H1), `tryRedispatchPendingTasks`(H4); writes `pendingHashSet`, `pendingTasks`; sends to `selfRef`, `snapSyncController` |
| `QueueMissingNodes` | `activeBehavior` L923 | calls `queueNodes`(H5), `tryRedispatchPendingTasks`(H4) |
| `HealingPeerAvailable` | `activeBehavior` L931 | calls `dispatchIfPossible`(H4); writes `knownAvailablePeers` |
| `HealingPeerUnavailable` | `activeBehavior` L948 | calls `tryRedispatchPendingTasks`(H4); writes `activeRequests`, `knownAvailablePeers`, `pendingHashSet`, `pendingTasks` |
| `HealingTaskComplete` | `activeBehavior` L1186 | writes `totalNodesHealed`; sends to `self` |
| `HealingTaskFailed` | no explicit arm: falls to the catch-all of `activeBehavior` | — |
| `HealingGetProgress` | `activeBehavior` L1484 | calls `calculateKilobytesPerSecond`(core), `calculateNodesPerSecond`(core), `calculateProgress`(core); sends to `replyTo` |
| `HealingCheckCompletion` | `activeBehavior` L1197 | calls `clearHealedPathsSet`(H5), `flushRawNodesSync`(H1), `startScopedVerification`(H3), `startVerificationBFS`(H3); sends to `snapSyncController` |
| `HealingPivotRefreshed` | `activeBehavior` L1021 | calls `clearHealedPathsSet`(H5), `clearPersistedFrontier`(H1), `flushRawNodesSync`(H1), `isNodeInStorage`(H5), `persistFrontier`(H1), `startVerificationBFS`(H3); writes `activeRequests`, `consecutiveDeadPulses`, `consecutiveIdleChecks`, `consecutiveStagnations`, `emptyResponseStrikes`, `lastHealedAtMs`, `lastPulseHealedCount`, `pathAliases`, `peerCooldownUntilMs`, `peerResponseBytesTarget`, `pendingHashSet`, `pendingTasks`, `pivotRefreshRequested`, `stateRoot`, `statelessPeers`, `verificationPassComplete` |
| `HealingServeRootRefresh` | `activeBehavior` L1104 | calls `shortRootLabel`(H4), `tryRedispatchPendingTasks`(H4); writes `healAttempts`, `serveRoot`, `serveRootRefreshCount` |
| `HealingResumeDispatch` | `activeBehavior` L1149 | calls `tryRedispatchPendingTasks`(H4); writes `consecutiveIdleChecks`, `consecutiveStagnations`, `lastHealedAtMs`, `pivotRefreshRequested` |
| `HealingForceComplete` | `activeBehavior` L985 | calls `clearHealedPathsSet`(H5), `clearPersistedFrontier`(H1); writes `activeRequests`, `pathAliases`, `pendingHashSet`, `pendingTasks`; sends to `snapSyncController` |
| `WalkStateChanged` | `activeBehavior` L1352 | writes `trieWalkInProgress` |
| `UpdateMaxInFlightPerPeer` | `activeBehavior` L970 | calls `tryRedispatchPendingTasks`(H4); writes `maxInFlightPerPeer` |
| `TrieNodesResponseMsg` | `activeBehavior` L1172 | calls `handleResponse`(H5) |
| `HealingRequestTimeout` | `activeBehavior` L1500 | calls `handleTimeout`(H4) |
| catch-all / signal `other` | `activeBehavior` L1529 | log / reply only |
