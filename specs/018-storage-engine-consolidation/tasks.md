---
description: "Task list for spec 018, Track C: the unified path-live layout (copy removal, items 8, 6 and 10)"
---

# Tasks: Storage Architecture for Gigablock-Scale Execution, Track C (the unified path-live layout)

**Scope: Track C only**: slices C0, C1, C2a, C2b, C3a, C3b, C3c, C4a, C4b, C4d, C4e, C5a, C5b, C5c, C6, C7, C8a, C8b of
[plan.md](plan.md) "Phase 1b" (final after S0 rounds 1 and 2). **Tracks E, F, H and R get their tasks later**, when Track C's
gate has reported. Slice C4c is withdrawn (the `inmemory` mode is retired) and has no phase; its id is not reused.

**Input**: [spec.md](spec.md) (Clarifications, session 2026-11-06), [plan.md](plan.md) Phase 1b, [data-model.md](data-model.md)
(cited below as "dm" plus a section number), [research.md](research.md) §17, `.specify/memory/constitution.md`.

**Prerequisites before C1**: spec 022 has landed on staging (Track C starts after 0.11.0), and C0 below is complete.

## How this list is organised

One phase per slice, **in merge order**. Each slice is one PR to staging. Every phase states its goal, its "S0 prerequisites"
line (copied from plan.md "S0 prerequisites per slice"), its reviewers (**before code** where S0 requires it), and an
independent test or exit criterion. **C8a is the first destructive slice and merges only after C7 and EG-12 report; C8b is
last.**

User stories (spec.md) served by Track C. The label is omitted where no story fits.

| Story | Meaning in Track C |
|-------|--------------------|
| US2 | State, receipt and gas roots are identical to the pre-track build (the pinned jar is the oracle) |
| US3 | SNAP syncs a fresh node on the unified layout, with no copy and a durable hand-off |
| US6 | ETC (Mordor, mainnet) runs on the unified layout, with a checkpoint-archive resync path |

**Rules for every task**

- **Tests before implementation, and they must fail first.** The failing run is pasted into the PR description. Tests are
  deterministic (constitution III): **no `Thread.sleep`**; use TestKit `expectMsg`/`awaitAssert`, injected schedulers, latches and
  the controllable scheduler of C3b. Statement coverage stays at or above 70%.
- **Guard.** `fukuii.state.path-live` (default `false`) selects the unified layout while slices land. Every slice merges with it
  `false` (staging stays green) and its new tests run with it `true`. The flag is deleted in C8b. It is not an operator option.
- **Cadence** (CLAUDE.md): `sbt compile-all` after every file change; `sbt scalafmtAll` before each commit;
  `sbt "testOnly *Foo*"` after each logic task; `./scripts/fukuii-test essential` **once**, at the end of the slice, before the PR.
  `sbt pp` before the PR on a clean tree. Core domain type sweeps use `sbt compile` between files (testing-protocol.md).
- **Consensus-adjacent slices** (C1, C3b, C3c, C4b, C4d, C4e, C5c, C6, and the readers in C2a) follow the Consensus-Critical
  Change Protocol: `forge` (ETC) and `beacon` (ETH) are consulted **before code** where marked, `wraith` fixes compile fallout
  without changing semantics, `eye` validates. Do not hand-edit consensus code reactively.
- **Reviewer tags**: **[forge]** ETC, **[beacon]** ETH, **[vault]** write order, atomicity and the layout, **[conduit]** RPC,
  **[herald]** SNAP serving and peers, **[prism]** non-consensus quality, **[eye]** validation, **[user]** a decision or
  confirmation only the user can give.
- **Every slice PR** carries the DC-14 figures (EG-8): p50 and p99 `[IMPORT-TIMING]`, longest read wait, bytes written per
  block, database size, RSS, `[STORAGE-PERF]`, against the pinned jar. The last task of each phase says so.
- **Builds**: sbt runs in the WSL clone (the user's WSL test-environment note); one sbt JVM at a time. Slice branches are
  proposed as `feat/018-<slice>-<topic>`.
- **No Python on the host**: scripts are `bash` and `node`, or Scala. Large recorded outputs are kept beside the pinned jar,
  with their SHA-256 recorded in `specs/018-storage-engine-consolidation/baselines/`.

## Format: `- [ ] T### [P?] [USn?] Description with file path`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task).
- Path prefixes: `main/` = `src/main/scala/com/chipprbots/ethereum/`, `test/` = `src/test/scala/com/chipprbots/ethereum/`,
  `it/` = `src/it/scala/com/chipprbots/ethereum/` (the IntegrationTest module), `spec/` = `specs/018-storage-engine-consolidation/`.
  `db/` means `main/db/`; `pathstate/` means `main/db/storage/pathstate/` (new package).
- "dm 3.6" is data-model.md section 3.6; "Part 2 D" is its design-record section D; "EG-n" and "Mn" are plan.md's exit-gate
  items and measurements.

---

## Phase 1: C0, S0 close-out, oracle pinning and measurements (docs and measurements only; merges no code)

**Goal**: everything the later slices compare against exists before the first line of code, and the two open C0 deliverables
(the EIP-8246 text and the ETC block source) are closed or listed open.

**S0 prerequisites**: none. Rounds 1 and 2 are done; there is no round 3.

**Reviewers**: `forge` and `beacon` (golden vectors are already verified by both; they read the frozen files), **[user]**
(open items 1 to 4).

**Independent test / exit**: `spec/baselines/` holds the pinned-jar record, the recorded roots, the hive and EEST pass lists, the
frozen golden vectors, the EIP-8246 check and M1 and M14; plan.md's open items are updated; C1 can start.

- [x] T001 Create `spec/baselines/README.md` listing every baseline file this phase produces, who owns it and where any file too large to commit is kept (beside the pinned jar, with its SHA-256 recorded in the README). Precedent: `specs/009-amsterdam-fork-support/baselines/eest-v21-amsterdam-2026-09-26.txt`.
- [ ] T002 [US2] Pin the last pre-track release as the differential oracle: record the staging commit SHA it is built from, build the jar, copy it to an out-of-tree directory the user names (plan open item 2), and record path and SHA-256 in `spec/baselines/pinned-jar.md`. State the retention rule there: kept until EG-12 passes, the first ETC v2 archive has been re-imported on a second datadir, and one release past C8b. **[user]** names the directory and the keeper.
- [ ] T003 [US2] Choose and record the fixed ranges in `spec/baselines/ranges.md`: Mordor from genesis to 2,600,000 (Agharta 301,243, Phoenix 999,983, ECIP-1099 2,520,000); a Sepolia range and a Platåberget range that **include Prague and Amsterdam blocks with request and block-access-list activity**; and an ETH mainnet range only if a mainnet state exists. **[beacon] [forge]** confirm the ranges before T004.
- [ ] T004 [US2] Run the pinned jar over each range (`main/blockchain/data/ChainImporter.scala` `importChainFileStrict` for Mordor from a `chain.rlp`; SNAP-then-follow for Sepolia and Platåberget) and record per block: state root, receipts root, gas used, `requestsHash`, `blockAccessListHash`. Files: `spec/baselines/roots-mordor-0-2600000.txt`, `roots-sepolia-<from>-<to>.txt`, `roots-plataberget-<from>-<to>.txt` (large ones beside the jar, SHA-256 in the README).
- [ ] T005 [P] [US2] Record the hive pass lists of the pinned jar, one file per suite: `ethereum/engine` (paris, shanghai, cancun, prague, osaka and amsterdam variants), `ethereum/rpc-compat`, `ethereum/graphql`, `ethereum/consensus`. Files: `spec/baselines/hive-<suite>-<date>.txt`. Scripts: `scripts/hive/`.
- [ ] T006 [P] [US2] Record the EEST pass lists of the pinned jar for `consume-engine` and `consume-rlp`, Cancun through Amsterdam (eip7928, eip6780, eip7610, eip8246 and the 7002, 7251 and 8282 system contracts). Files: `spec/baselines/eest-<date>.txt`. Scripts: `scripts/eest/`.
- [ ] T007 [P] [US2] Record the pinned jar's results for the ETC and ETH ethereum/tests jobs and for `it/ethtest/ForkChoiceBlockchainTestsSpec.scala` under `basic` (and note that `archive` is new). File: `spec/baselines/ethtests-<date>.txt`.
- [ ] T008 Read the **EIP-8246** text from an EIPs clone (neither reviewer had one; the user supplies it) and write `spec/baselines/eip-8246-check.md`: quote the clearing rules, and confirm in one paragraph that dm 3.6 rule 6 (trigger on the parent's non-empty storage root; shape "storage cleared, account kept") matches. A mismatch reopens S0 item 5. **[beacon]** reads it.
- [ ] T009 Record the pass count of the **658** `amsterdam/eip8246_selfdestruct_no_burn` EEST fixtures on the pinned jar's harness (baseline before #1422: 1 passing, `specs/009-amsterdam-fork-support/baselines/eest-v21-amsterdam-2026-09-26.txt:24`). File: `spec/baselines/eip8246-fixtures.txt`. The same count is recorded again on the ported harness in C3c.
- [x] T010 *(Decided 2026-11-07: fukuii-fc's own ETC mainnet sync with chain download, then `fukuii etc export` of
  0..5,100,000 pushed to the user's NFS directory; plan open item 1.)* **[user]** Decide the **source of ETC blocks 0 to 5,100,000** for EG-1's ETC half (plan open item 1): (a) a `chain.rlp` from core-geth `export` of a synced ETC node (forge's recommended primary; is the history complete from genesis?) or (b) fukuii's own full sync from genesis with SNAP off (acceptable; T012 decides its cost). **This gates C7, not code.** Record the decision, or "still open", in `spec/plan.md` "Remaining open items" item 1.
- [ ] T011 [P] **[user]** Close the remaining open items that gate later phases, and record each in `spec/plan.md` "Remaining open items": item 3 (may a declared local core-geth peer be used if fewer than 2 snap-capable ETC peers are visible; gates EG-12, needed before C6) and item 4 (the machines for EG-6, EG-14, EG-15 and the Ethash DAG on the miners; needed before C7).
- [ ] T012 **M14**: measure the cost of importing ETC blocks 0 to 1M and 1M to 2M from genesis on the dev host with the pinned jar (SNAP off), and the size of a `chain.rlp` export of that range. Record blocks per second per 100,000 blocks, disk and heap in `spec/baselines/m14-etc-import-cost.md`. It says whether source (b) of T010 is feasible and what it costs.
- [ ] T013 **M1** (stand-in): run a throwaway instrumented build of the pinned commit (not merged, not committed) that logs per block the number and bytes of trie nodes written and replaced by the persist in `main/ledger/InMemoryWorldStateProxy.scala`. Run it on Sepolia, Mordor and Platåberget. Record p50, p99 and max in `spec/baselines/m1-diff-size.md`, **the largest diff against `state.changeset.max-record-bytes` (256 MiB)** and the vanished-node count. The final M1 is taken on the real code in C3b.
- [x] T014 [P] Freeze the three golden vectors: copy the hex strings of dm 4.3 into `spec/baselines/blockdiff-golden-v1.hex`, `-v2.hex`, `-v3.hex` and re-derive each CRC32C with `java.util.zip.CRC32C` (a `jshell` snippet checked in beside them). Expected: 153 bytes `8d0afd3e`, 219 bytes `707894bf`, 144 bytes `ae4e831f`. Any difference reopens S0 (one-way door).
- [x] T015 [P] Freeze the oracle list C1 must pass, in `spec/baselines/oracle-tests.md`, copied from dm 8: incremental versus full diff, `StackTrie` rebuild, single and multi-block reverse exactness, several persist rounds per block, the empty trie, genesis emits no vanished position, EEST Amsterdam fixtures, the pre-Atlantis intermediate-root networks (ethereum/tests Frontier, Homestead, EIP-150).
- [ ] T016 Re-size Track C from M1 and M14: update the sizes in `spec/plan.md` "Slices" (the intro says they are set again at the end of C0), and re-check every "S0 prerequisites per slice" line: all must read "Satisfied" except the ETC block source. Reply to the coordinator with the new total.

- [ ] T017 **[user]** Accept plan.md and apply its list "What the rest of spec 018 must change when this plan is accepted" to `spec/spec.md` (Scope items 6, 8 and 10, DC-3, DC-9, DC-11, FR-010, FR-016, OQ-5, Edge Cases), `spec/plan.md` Phase 3 (item 10 is one gate; F2, F3 and the hand-off part of F4 move to Track C) and `spec/research.md` §17 (the Option Y cost row, set again from T016). Set the lifecycle header per `docs/development/spec-lifecycle.md` and regenerate `specs/README.md` with `python3 -I scripts/specs/index.py` in WSL.

**Checkpoint**: the oracle, its baselines and the frozen vectors exist. C1, C2a, C2b and C3a can start at once.

---

## Phase 2: C1, the trie reports positions (item 8a)

**Goal**: `MerklePatriciaTrie` emits, for every mutation, the position `(owner, path)` of each node it replaces, absorbs, creates or
vanishes, with consensus byte-identical on both chains and inert until C2a wires a reader. Serves US2.

**S0 prerequisites**: none (S0 item 1 is signed by both reviewers in round 2).

**Reviewers**: **[forge] [beacon] before code** (consensus-adjacent rule inside the trie that both chains execute) and both
review the PR; wraith; eye.

**Independent test / exit**: the oracle list of T015 passes; roots and node sets equal the pinned jar's on random sequences and on
the recorded Mordor and Sepolia ranges; ethereum/tests unchanged; **M7 on Mordor within the spec 007 allocation budget is a merge
gate**.

### Tests (write first; they must fail)

- [x] T018 [P] [US2] Test `NibblePath` (one nibble per byte, 0x00 to 0x0f, hex-prefix conversion, append, prefix, ordering) in `test/mpt/NibblePathSpec.scala`. dm 3 and 2.
- [x] T019 [P] [US2] Oracle: for random insert, update and delete sequences, the positions the trie emits equal the difference between two full position walks (prior as of block start, next as of block end). File: `test/mpt/PositionEmissionSpec.scala`. dm 8 oracle 1. **[forge] [beacon]**
- [x] T020 [P] [US2] Oracle: a `StackTrie` rebuild of the final node set equals the emitted position map, including nodes shorter than 32 bytes (inline rule) and the root rows (stored always). File: `test/mpt/PositionStackTrieOracleSpec.scala`. dm 2, 8. *(As built: for fixed-length keys only, since `StackTrie` takes no others. Keys that are prefixes of other keys, with branch values, are held to the raw-RLP walk of T019 instead.)*
- [x] T021 [P] [US2] Oracle: **single and multi-block reverse exactness**, and **several persist rounds per block** (a node created in transaction 1 and replaced in transaction 2 has prior None, dm 3 rule 1). File: `test/mpt/PositionReverseSpec.scala`.
- [x] T022 [P] [US2] Test the report rules: only **replaced or absorbed** nodes are reported, and the branch `fix` resolves and returns unchanged (`MerklePatriciaTrie.scala` around `:591` to `:599`) is not; shrink and grow across 32 bytes (dm 3 rules 4 and 5). File: `test/mpt/PositionFixSpec.scala`. **[forge]**
- [x] T023 [P] [US2] Test the empty trie (no rows; the root row of a trie that becomes empty is a vanished position) and that building genesis emits no vanished position (dm 2, 3 rule 7). File: `test/mpt/PositionEmptyTrieSpec.scala`.
- [x] T024 [P] [US2] Test decorator completeness: reflection over every member of `MptStorage` proves `MptStorageDecorator` forwards it (dm 9). File: `test/db/storage/MptStorageDecoratorSpec.scala`.
- [ ] T025 [P] [US2] Test that roots and node sets equal the pinned jar's on random sequences and on the recorded Mordor and Sepolia ranges of T004 (the fixtures are read from `spec/baselines/`). File: `it/mpt/PositionRootsDifferentialSpec.scala`. *(Open after the C1 PR: it needs T002 and T004. The half that needs no jar is in the PR: every random sequence of the C1 specs runs on a position store and on a hash-keyed store at once, and the roots are equal after each change.)*
- [ ] T026 [P] [US2] Run the pre-Atlantis intermediate-root networks (ethereum/tests Frontier, Homestead, EIP-150) and the EEST Amsterdam fixtures with position emission enabled and a recording sink, and assert the results equal `spec/baselines/ethtests-<date>.txt`. File: `it/ethtest/PositionEmissionEthTestsSpec.scala`. **[beacon] [forge]** *(Moved to C2a, after T046: until the owner is threaded, the account trie and every storage trie reach one storage with no owner, so a recording sink cannot tell their positions apart. **C2a part 1, as built:** the sink is a `PositionMirror` (`test/mpt/PositionTestKit.scala`) in front of the storage of real block execution, behind a `StorageObserver` hook in `test/ledger/EestBlockchainReplay.scala`. It applies every reported change under its owner and position, checks every read against the rows it holds (a read is right only for a node the block wrote at that owner and position, or the row there when the block began), and after each accepted block compares its rows with a raw-RLP walk of the state. Run: the vendored vectors in `test/ledger/EestPositionReplaySpec.scala` (41 fixtures, Paris to Amsterdam); the whole tests@v21.0.0 Amsterdam corpus with `EEST_POSITIONS=1` (`EestFixtureCorpusSpec`, now a second step of `eest-amsterdam.yml`): 26,503 fixtures, 2,882,422 trie updates, 13,997,435 reads, no finding; the Paris and Shanghai sets: 8,835 fixtures, no finding. Figures and the two mutation runs are in `baselines/t026-position-mirror.md`. **Still open:** the ethereum/tests half (Frontier, Homestead, EIP-150: a state root per transaction). Its runner is `it/ethtest/LegacyPreByzantiumBlockchainTestsSpec.scala` over `it/ethtest/EthereumTestHelper.scala` (the pre-state world at `:82-125`, `BlockExecution` at `:173,187`): give the helper the same `StorageObserver`, and run it where the `ets/tests` checkout exists. And the comparison with `spec/baselines/ethtests-<date>.txt`, which is a C0 file (T002 to T004). Both are B-207.)*

### Implementation

- [x] T027 [US2] Add the opaque type `NibblePath` in `main/mpt/NibblePath.scala` (new; in `mpt/` so the stateless closure gains no `db/` import, spec 018 DC-8). dm 3.
- [x] T028 [US2] Thread positions through `MerklePatriciaTrie.put`, `remove` and `fix` in `main/mpt/MerklePatriciaTrie.scala`: report replaced and absorbed nodes only, not the resolved-and-kept branch; apply the **first-event prior** rule and the 32-byte stored rule with an always-stored root (dm 3 rules 1 to 5, 7; dm 8 items 1 to 5). **[forge] [beacon]** *(As built: absorbed nodes are reported in their own list, never to a hash-keyed store (dm 8 item 2). The first-event prior and the stored rule belong to whoever keeps the block's change set, which is C3b's `PathStagedState`; C1 has them in its test oracle, `PositionTestKit.blockDiff`.)*
- [x] T029 [US2] Add position-aware collapse in `main/mpt/MptTraversals.scala` (`collapseTrieAt`) and the stored-node rule in `main/mpt/MptVisitors/RlpHashingVisitor.scala` (dm 8).
- [x] T030 [US2] Add the additive `MptStorage` members `getAt`, `forOwner`, `resetOwner`, `updateNodesAt`, `rawNodesAt`, with defaults that keep every implementor compiling and behaving, in `main/db/storage/MptStorage.scala` (dm 9). Hash-keyed `get` keeps its behaviour for list tries.
- [x] T031 [US2] Create `main/db/storage/MptStorageDecorator.scala` forwarding every `MptStorage` member; make the wrapping implementors derive from it (their move to the base lands in C2a, T041). *(As built: the base only. Reads go through one `read` hook and updates through one `update` hook, dm 9.)*
- [ ] T032 Fix compile fallout with `sbt compile-all` (wraith if it spreads); run `sbt "testOnly *Position* *NibblePath* *MptStorageDecorator*"`, the ethereum/tests job and `sbt scalafmtAll`. *(In #1788: `compile-all`, the `testOnly` run, `formatAll`, `formatCheck` and `testEssential` are done. Open: the ethereum/tests job, which the cloud container could not run (no `ets/tests` submodule); CI runs it on the PR.)*
- [ ] T033 **M7 merge gate**: import the recorded Mordor range with the pinned jar and with this branch; compare `[IMPORT-TIMING]` and allocation rate (JFR) and record both in the PR description and `spec/baselines/m7-c1.md`. Within the spec 007 allocation budget (`specs/007-hotpath-alloc-reduction/plan.md`) or the PR does not merge. **[eye]**
- [ ] T034 Record the DC-14 figures in `spec/baselines/dc14-C1.md` (EG-8), then open the C1 PR to staging and request `forge` and `beacon`; behaviour is unchanged until C2a wires a reader.

**Checkpoint**: positions are emitted and tested; nothing reads them yet.

---

## Phase 3: C2a, the position store's read side (item 8b)

**Goal**: a position-keyed, hash-verifying, owner-scoped read path exists, typed faults exist outside `MPTException`, and execution,
prefetch and genesis can be handed it, all behind the guard. Serves US2.

**S0 prerequisites**: none.

**Reviewers**: **[beacon]** (execution read path, BAL prefetch), **[forge]** (the shared `InMemoryWorldStateProxy`, genesis),
prism. beacon and forge are consulted **before code** on the owner-threading tasks (execution code both chains share).

**Independent test / exit**: a SNAP-built position store read through the trie equals the pinned jar's reads for the same root;
the fault hierarchy is pinned; prefetch is correct on a sibling parent; M4 recorded.
**Two PRs (2026-11-11).** C2a lands in two parts, in this order. **Part 1** is everything that changes code both chains
already run and must be neutral on the hash-keyed store: T041 and T046, with their tests T039 and T040, and T026 (moved here
from C1). **Part 2** is the new `pathstate` package behind the guard: T035 to T038, T042 to T045, T047 to T050. The split keeps
the consensus-reviewed diff small, and gives part 2 a base on which every trie already names its owner.


### Tests (write first; they must fail)

- [x] T035 [P] [US2] Test `PathMptStorage`: a position store built by SNAP's path-keyed writer (`main/db/storage/PathNodeStorage.scala`) read through the trie returns the same nodes and root as the pinned jar's reads for that root; classification of a mismatch as **stale**, **corrupt** or **absent** (dm 6.3.1, 9). File: `test/db/storage/pathstate/PathMptStorageSpec.scala`. **[beacon]** *(Done, C2a part 2, on `EphemDataSource` and RocksDB. The reference is the same state on today's hash-keyed store, built by the production world: the pinned jar is a C0 deliverable, and its reads join the differential of T025 when it exists (B-203). Also there: the throwaway layer (below, T043), the row read with the key the SNAP writer wrote, and a closed RocksDB failing as closed, never as a missing node (vault).)*
- [x] T036 [P] Test the fault hierarchy: `StaleStateViewException`, `StateUnavailable`, `ReorgTooDeep(reason)` and `BranchTooLarge` extend `StateFault`, which is **not** an `MPTException`, and a `CorruptNodeException` stays a `MissingNodeException` (dm 6.3.2). File: `test/db/storage/pathstate/StateExceptionsSpec.scala`. *(Done, C2a part 2.)*
- [x] T037 [P] [US2] Test the anchor-only `StateView`: the view of the anchor is decided at read time; a view older than the move log throws stale (dm 6.3, 6.2). File: `test/db/storage/pathstate/StateViewSpec.scala`. *(Done, C2a part 2. The writer's three steps (publish, write, settle) are driven by hand in every order a reader can meet them, including a batch that lands between the read of the row and the read of the epoch (dm 6.3.3). File also covers the anchor string and `StateMoveLog`.)*
- [x] T038 [P] [US2] Test `PathPrefetchReader`: prefetch for a **sibling parent** reads through `view(parent)`, stops on a fault, never feeds execution an unverified node, and `DecodedNodeCache.putIfCurrent` holds (dm 6.5, 9). File: `test/db/storage/pathstate/PathPrefetchReaderSpec.scala`. **[beacon]** *(Done, C2a part 2, for the anchor as parent. A **sibling** parent has no view until C4a, so `PathStateStorage.prefetchReader` gives no reader for it and the block executes without a prefetch: that is what the test pins. The stopped state is shared by every trie's view of the reader (beacon). `BalPrefetcherSpec` gains the case of a view going stale under a run: the fault is not an `MPTException`, and only the task's own catch sees it.)*
- [x] T039 [P] [US2] Test owner threading: a storage trie built through `forOwner(Some(keccak(address)))` reads back at `owner ++ HP(path)`, and two contracts with an identical storage root get **separate** rows. File: `test/ledger/InMemoryWorldStateProxyOwnerSpec.scala`. **[forge]** *(Done, C2a part 1. Six tests over an `OwnerPositionStore`; also SELFDESTRUCT then a creation at the same address leaves only the new trie's rows, and a removed account's rows are dropped.)*
- [x] T040 [P] [US2] Test genesis owner threading: `GenesisDataLoader` builds every storage trie owner-scoped (it builds into one shared storage with no owner today, `GenesisDataLoader.scala:159-160,172-176`), and the empty-alloc root writes no row (dm 5.4). File: `test/blockchain/data/GenesisDataLoaderOwnerSpec.scala`. **[forge] [beacon]** *(Done, C2a part 1. The store takes upserts: the loader reports no node as replaced (`AppendOnlyMptStorage`), so each new node must land on the row of the one it replaces, and the rows are held to a walk of the finished tries.)*

### Implementation

- [x] T041 [P] Move the seven `MptStorage` implementors onto `MptStorageDecorator`: `main/db/storage/DecodedNodeCache.scala`, `main/db/storage/PrefetchNodeReader.scala`, `main/jsonrpc/EthSimulateService.scala`, `main/ledger/StxLedger.scala`, `main/blockchain/data/GenesisDataLoader.scala` and the implementors inside `main/db/storage/MptStorage.scala` (find them with `grep -rn "extends MptStorage"`). Run the decorator-completeness test again. **Each wrapper keeps what it does today through the hooks of the base (C1 review, forge and beacon):** `CachingMptStorage`, `PrefetchNodeReader` and the `contextStorage` wrapper override `read`, so a trie's `getAt` still meets the cache, the budget and the exception translation; `AppendOnlyMptStorage`, `ReplayMptStorage` and `RetainingMptStorage` override `update` with `removals.none`, or genesis load, `eth_call` replay and `eth_simulateV1` start deleting nodes. One test per wrapper: a positioned read and a positioned update go through its override (C1 pins today's behaviour of the three that drop removals: `AppendOnlyMptStorageSpec`, `ReplayMptStorageSpec`, `RetainingMptStorageSpec`). **The batch reads (`multiGetNodes`, `rawNodes`, `rawNodesAt`) do not go through the `read` hook** (beacon, C1 review): before the spec 017 recorder lands, either route them through a hook or record in dm 9 that a batch read is never a parent-state read and put them under the C2b ratchet. *(Done, C2a part 1. Six of the seven: `SerializingMptStorage` wraps a `NodesKeyValueStorage`, not another `MptStorage`, and stays. `test/db/storage/MptStorageWrappersSpec.scala`, and one more test in each of the three C1 wrapper specs. The batch reads: recorded in dm 9 as never an execution read, with their four callers; the ratchet entry is T062. `PrefetchNodeReader.forOwner` shares one budget and one count across the tries of a block, and its `resetOwner` throws (beacon). Four specs that build a world over `mock[MptStorage]` stub `forOwner` (beacon, post-code).)*
- [x] T042 [P] Create `main/db/storage/pathstate/StateExceptions.scala` with the `StateFault` hierarchy (dm 6.3.2); `ReorgTooDeep` carries `Deep`, `Incomplete` or `Pivot`. *(Done, C2a part 2.)*
- [x] T043 Create `main/db/storage/pathstate/StateView.scala` (anchor-only in this slice; the full overlay view is C4a) and `main/db/storage/pathstate/PathMptStorage.scala`: `getAt(owner, path)` with keccak verification against the parent's expected hash, the stale/corrupt/absent classification, and `get(hash)` answering the account root and overlay nodes only (dm 6.5, 9). *(Done, C2a part 2. `StateView` is the view of the anchor, over a `StateClock` whose in-memory form is `StateMoveLog`; `PositionKey` landed here (dm 3). **Added to the task (beacon, before code):** `PathMptStorage.throwaway(view)`, the by-hash layer of dm 6.2, because the readers of T057 persist after every transaction and read back what they wrote. `readOnly(view)` refuses every update. `multiGetNodes` and `rawNodes` throw (dm 9).)*
- [x] T044 Create `main/db/storage/pathstate/PathStateStorage.scala` with the read members (`view(block)`, `stateAvailable`, an `ownerRows` placeholder bound to C3b) and wire it in `main/db/components/Storages.scala` behind `fukuii.state.path-live`. **[vault]** *(Done, C2a part 2. `Storages.pathStateStorage: Option[PathStateStorage]`, built only with the guard on, after `appStateStorage`; `AppStateStorage.getStateAnchor`/`putStateAnchor`, with an unreadable anchor an error and never an absent one. `ownerRows` throws until T090. No reader of it exists before C2b.)*
- [ ] T045 [P] Create `main/db/storage/pathstate/PathPrefetchReader.scala` (a reader over `view(parent)` that records nothing and stops on a fault) and switch the BAL prefetch to it behind the guard (dm 9). **[beacon]** *(Partly done, C2a part 2: the reader exists and is tested (`PathPrefetchReader`, which is `PrefetchNodeReader` over a read-only view, stopping on a fault). **The switch is not made here:** the prefetch gets its reader from `StagedBlockState.prefetchReader`, and the path-keyed staged state is C3b's. T093 owns the switch (beacon).)*
- [x] T046 Thread the owner through `main/ledger/InMemoryWorldStateProxy.scala` (`forOwner(Some(keccak(address)))` for each storage trie, dm 3 rule 6 and 9) and through the storage tries of `main/blockchain/data/GenesisDataLoader.scala` (dm 5.4). `resetOwner` is called from `deleteAccount` but does nothing until C3b. **[forge] [beacon]** *(Done, C2a part 1, with the BAL prefetch's tries (`main/ledger/BalPrefetcher.scala`). The owner is `InMemoryWorldStateProxy.storageOwner(address)`. Not threaded here, by design, and listed for C2b and C3c: `Blockchain.scala:94,113`, `BlockchainReader.scala:207,333`, `PendingTransactionsManager.scala:499`, `TestService.scala:248`, `TestModeWorldStateProxy.scala:106` (and its `deleteAccount`, which skips the reset).)*
- [x] T047 Create `src/main/resources/conf/base/state.conf` with the single key `fukuii.state.path-live = false` and its comment (dm 10: in-development guard only, deleted in C8b). The remaining keys arrive in C3a. *(Done, C2a part 2. Included from `conf/base/fukuii.conf`, read by `utils/StateLayoutConfig`, so that a unit test of a storage class does not load the whole node configuration (vault).)*
- [x] T048 Run `sbt compile-all`, then `sbt "testOnly *PathMptStorage* *StateExceptions* *StateView* *PathPrefetch* *OwnerSpec* *MptStorageDecorator*"` on `EphemDataSource` and on RocksDB (parameterised), then `sbt scalafmtAll`. The specs are the files named in this phase's tests, under `src/test/scala/` and `src/it/scala/`. *(Done, C2a part 2: the six specs under `test/db/storage/pathstate/`, 89 tests, on `EphemDataSource` and on RocksDB. Nine rules were then broken on purpose, one at a time: plan.md, "C2a part 2 record".)*
- [ ] T049 **M4**: measure the keccak-verify cost per block with and without `DecodedNodeCache` on a Sepolia replay (the recorded range of T004). Record it in `spec/baselines/m4-verify-cost.md`; it decides whether verify-on-read needs a cheaper form (dm 13). *(Open: it needs the recorded Sepolia range of T004, a C0 deliverable.)*
- [ ] T050 Record the DC-14 figures in `spec/baselines/dc14-C2a.md` (EG-8), then open the C2a PR; request `beacon`, `forge`, `prism`. *(Open for the DC-14 figures, which need C0's pinned jar and ranges. The two C2a PRs are open or merged without them, as C1's was (B-203).)*

**Checkpoint**: the read side exists and is covered; execution still runs on the Hash store with the guard off.

---

## Phase 4: C2b, every reader can be handed the position store (item 8c)

**Goal**: all state readers outside execution (RPC, simulate, SNAP serving, checkpoint export, recovery walkers, hash-only trie
walkers) go through owner-aware, position-aware members, guarded by an allow-list ratchet. Serves US2.

**S0 prerequisites**: none.

**Reviewers**: **[conduit]** (RPC signatures, harness), **[herald]** (SNAP serving, the served-root window clamp, the `GetNodeData`
answer), **[beacon]**, eye.

**Independent test / exit**: rpc-compat (40) and graphql (2) unchanged; `eth_getProof` golden bytes; the SNAP serve differential is
byte-identical to the pinned jar's; the grep ratchet fails on an unlisted caller.

### Tests (write first; they must fail)

- [x] T051 [P] [US2] Test `eth_getProof` and `eth_getStorageAt` on a position store: golden bytes for a seeded account and slot, owner-scoped (dm 8, Part 2 E). Files: `test/jsonrpc/EthProofServiceSpec.scala`, `test/jsonrpc/EthUserServiceSpec.scala`. **[conduit]** **Added by the ratchet (T062):** delete the unreachable arm of `MerklePatriciaTrie.getProof` (`main/mpt/MerklePatriciaTrie.scala` `:194-196`) and the two-argument `getFromHash` it alone calls (`:290`): the last read by hash alone inside the trie. *(Done, C2b's second PR. `test/jsonrpc/EthProofPositionStoreSpec.scala`: one seeded state held two ways, as rows of the position store and as nodes of the hash store; `eth_getProof` through `EthProofService` gives the same answer from both, and five digests of everything it returns are frozen. `alice` and `bob` hold the same storage trie, so a storage proof has to be read under the account asked about. `eth_getStorageAt` on a position store is pinned at the `Blockchain` level in `test/domain/BlockchainReaderSeamSpec.scala`, and both methods again on the execution-apis test chain in `test/jsonrpc/conformance/RpcReadersOnPositionsSpec.scala`. The unreachable arm of `getProof` is deleted; a hash node that ever reached it now throws (beacon).)*
- [x] T052 [P] [US2] SNAP serve differential: account ranges, storage ranges, trie nodes and proofs served from a position store are **byte-identical** to the pinned jar's for the same root, and a request for a root outside the served-root window is clamped. File: `it/network/snapserver/SnapServePositionDifferentialSpec.scala`. **[herald]** *(Done in part, C2b's third PR; the half against the pinned jar waits for C0 (B-212). `test/network/snapserver/SnapServePositionStoreSpec.scala`: one state held two ways, each request served from both, the two responses compared whole. Two states: the one the production world builds, where `alice` and `bob` hold the same storage trie; and one whose keys are chosen, with extensions 4 and 62 nibbles long in the account trie and in a storage trie, since hashed keys almost never give one and the path through an extension is where a position is miscounted. Ranges from every origin (keys of the trie, and keys that part from it inside, before and after an extension) at four budgets; every stored node by its path; paths that end inside an extension. The window: `BlockchainReaderSeamSpec`, a root one block past it is not served. It is a unit spec, not under `it/`: nothing in it needs a node.)*
- [x] T053 [P] [US2] Test the stale-view retry: an RPC read that meets `StaleStateViewException` retries transparently with a fresh view of the same block up to 3 times while the block is in reach, and `StateUnavailable` becomes the existing "state unavailable" error (dm 6.3.2). File: `test/jsonrpc/StateReadRetrySpec.scala`. **[conduit]** *(Done, C2b's second PR. `jsonrpc/StateRead`: `at` and `scratchAt` run a read again, all of it, on a fresh state, up to three times; `request` does the same for a whole request that holds a world (`eth_call`, a simulation, a trace), which 18 routes use through `handleRead`. A state that is not held, and a read that stays stale, are `JsonRpcError.NodeNotFound`, which is also the controller's backstop for a `StateFault` that escapes a method. A `MissingNodeException` is not handled there: each method keeps the answer it gives for one today. `RpcRetryFreshStateSpec` drives `eth_call`, `eth_estimateGas` and `eth_createAccessList` through the controller over a reader whose scratch state goes stale, and counts the states taken: every run takes its own.)*
- [x] T054 [P] Test the **allow-list grep ratchet**: it fails when a caller outside the list reads the four state accessors (`getBackingMptStorage`, `getReadOnlyMptStorage`, `getBackingStorage`, `getReadOnlyStorage`) or a hash-only walker. File: `test/scripts/StateAccessorRatchetSpec.scala` (drives `scripts/check-state-accessors.sh`). *(Done, C2b's first PR. The script is driven on made-up trees, one per way it can fail, and on the repository. It passed when first run, so the script was then broken one rule at a time; the table is in plan.md, "C2b ratchet record".)*
- [x] T055 [P] [US2] Test that the four recovery walkers and the hash-only trie walkers return the pinned jar's verdicts on a seeded position store. Files: `test/blockchain/sync/RecoveryScannersPositionSpec.scala`, `test/mpt/WalkersPositionSpec.scala`. **[beacon]** *(Done, C2b's fourth PR; "the pinned jar's verdicts" are the code's own from before it read by position, since C0's jar is not built: the half against the jar is B-212's. **Frozen first**: `test/resources/spec018/walker-verdicts-hash-store.txt` holds what ten walks answered on a hash store for three states (hashed keys; chosen keys with extensions 4 and 62 nibbles long and nodes inside their parents; one account), seven sets of holes and two code stores, taken before any walker was touched, and `RecoveryScannersPositionSpec` holds the converted walks to it, in order. The same spec runs each walk on a position store with the same nodes lost; has two accounts with one storage root lose it under one of them only, which only a position store can do (spec 022 V3); has the parallel scan merge two shards that each hold one of the two; has one subtree sit at two positions of a single trie; and moves the state after every read count of every walk. `test/mpt/WalkersPositionSpec.scala` holds the leaf walk and the shard enumerator: every leaf with its key through extensions and embedded nodes, a hole reported by the very position that was read, a shard walked from its own prefix. The checkpoint export's cases are in `CheckpointExporterSpec`. The mutation table is in plan.md, "C2b walkers record".)*

### Implementation

- [x] T056 [US2] Add the owner-aware, position-aware state reads to `main/domain/Blockchain.scala` and `main/domain/BlockchainReader.scala`; change the `eth_getStorageAt`, `eth_getProof` and GraphQL signatures (the one RPC-visible code change) in `main/jsonrpc/EthUserService.scala`, `main/jsonrpc/EthProofService.scala`, `main/jsonrpc/TestService.scala`, `main/jsonrpc/graphql/GraphQLSchema.scala`; and convert the four readers the first audit omitted (dm Part 2 E table). **[conduit] [beacon]** **A reader that wants to know whether a state is held asks `PathStateStorage.stateAvailable`, never `get(root)`** (beacon, C2a part 2; dm 9): a root that is not the view's own reads as a missing node, and must not reach `stateHoles.report`. **Added by the ratchet (T062):** `main/transactions/PendingTransactionsManager.scala` (`:498`) reads senders' accounts at the best block from a root alone over `getReadOnlyStorage`; no task named it, and it moves to `stateAt(header)` here (beacon: it is off the consensus path and falls back to known senders on any failure). *(Done, C2b's second PR. The seam is on `BlockchainReader`: `stateAt(header)` and `scratchStateAt(header)`, which with the guard off are the read-only store. Account reads take the header: `accountAt`, and `accountIn` / `accountProofIn`, which take the state as well and keep the old rule that a block above the best block has no account. Storage reads take the state and the address; the forms that took a storage root alone are deleted. Moved: `EthUserService`, `EthProofService`, `EthTxService`, `PersonalService`, `McpTools`, `McpResources`, `GraphQLSchema`, `TestService`, and the pool. **One RPC-visible change:** a request by hash for a side block is answered from that block's state, not from the canonical block at its height. `stateAvailable(header)` was not added: nothing asks it until `ChainImporter` moves (C3c, T116; beacon agrees).)*
- [x] T057 [P] [US2] Convert `main/ledger/StxLedger.scala` and `main/jsonrpc/EthSimulateService.scala` to read through `view(block)` (they sit on the decorator after T041); a stale view retries. **They run on `PathStateStorage.throwaway(block)`** (C2a part 2): a view with a by-hash layer of its own that takes what the execution persists and is dropped with it. *(Done, C2b's second PR. Every world of `StxLedger` is on `scratchStateAt`; `advanceWorldToTx` takes the parent's header; `eth_simulateV1` takes one scratch state of its base block for the whole request. **Found by the differential on the execution-apis chain, and fixed:** a message call does not run under its block's header (`CallTxBuilder` zeroes the base fee for a call with no gas price, and block overrides replace what they name), and the ledger asked for the state by the header it was given. On a position store every plain `eth_call` was answered "state unavailable". `StxLedger.callWorld(stateOf, executing)` takes the state from the block's own header, and `simulateCall` / `estimateCallGas` now take the world.)*
- [x] T058 [P] [US2] Serve SNAP by root view and clamp the served-root window in `main/network/snapserver/SnapServer.scala` and `main/network/NetworkPeerManagerActor.scala`; confirm `GetNodeData` is moot under eth/68 and eth/69 and record the answer in the PR. **[herald]** **With the guard on and until the overlay (C4a) serves the window, the node does not advertise `snap1`** (user decision, 2026-11-11: do not lose peers to empty answers). The prefix is threaded through `resolve`, `proofFor`, `collectNodeAtPath` and `resolveLeafAccount`, and the two raw reads of `NetworkPeerManagerActor` (:1159, :1171) move too; `GetNodeData` is unreachable under eth/68 to eth/72 and is deleted or allow-listed (herald). *(Done, C2b's third PR. `SnapServer` reads every node at its position: the account trie under no owner, a storage trie under its account's hash, the path counted through `walkRangeVisit`, `proofFor`, `resolveLeafAccount` and `collectNodeAtPath`; the handler's own copy of the account lookup is gone (`SnapServer.storageRootOf`). The handlers ask `BlockchainReader.stateForRoot(root)` for each request: guard off, the read-only store whatever the root, as before; guard on, the anchor's view when the root is the anchor's and the anchor is within 128 blocks of the best block, and nothing otherwise. **A serve that meets a `StateFault` answers with the empty response**: the server took every failed read as an absent node, which for a view the disk moved away from is a hole in a range, sent with proofs that cannot close over it. **`snap/1` and `snap/2` are not advertised while the guard is on** (`NetworkProtocolConfig.capabilities(snapServed)`, `InstanceConfig`), with a WARN at start; the capability is mutual, so such a node does not sync by SNAP either. **`GetNodeData`**: every decoder this node has (eth/68 to eth/72) rejects codes `0x0d` and `0x0e` (EIP-4938), so `BlockchainHostActor`'s handler could not be reached; it is deleted with its subscription and its two tests, and one test pins that the host does not subscribe to the code. **One source for the guard, and no SNAP sync under it** (herald): the production storages take the guard from their instance's configuration, as the capabilities do, and a configuration with the guard on and `do-snap-sync = true` is refused at start (`ConfigValidator.stateGuardErrors`), since nobody offers SNAP to a node that does not offer it. **Left for C4a** (noted on T130): the window becomes `min(128, the retention floor)` and covers every block the overlay can give a view of, SNAP is advertised again, and the refusal at start goes.)*
- [x] T059 [P] Make checkpoint export (still format v1 in this slice) read by position in `main/blockchain/checkpoint/CheckpointExporter.scala`; v2 is C5c. *(Done, C2b's fourth PR. The export takes the state of its block from `BlockchainReader.stateAt(header)` (the `StateStorage` parameter is gone) and reads every node at its position: a stack of hash and path, one owner for each trie, the storage roots queued with their accounts, the path carried through embedded children. The archive is v1 still, nodes by hash: its bytes for the chosen-key state are frozen from before the change and are the same from a hash store and from a position store, where the storage trie two accounts hold is read under the first. A state the position store does not hold is refused before the file is opened, and a state that moves under the export is `StateNotHeld`, not `MissingTrieNode`. An export that survives import is C5c's.)*
- [x] T060 [P] Convert the four recovery walkers to position reads: `main/blockchain/sync/StorageRecoveryActor.scala`, `main/blockchain/sync/BytecodeRecoveryActor.scala`, `main/blockchain/sync/CombinedRecoveryScanner.scala` (and the combined recovery actor beside it), `main/blockchain/sync/snap/StateValidator.scala`. *(Done, C2b's fourth PR. The combined scan, the two legacy scans and `StateValidator` read the account trie under no owner and every storage root, and every storage trie, under its account, at the path the walk has come down; the validator's recursive walks carry the path now, and `collectAccounts` the account's hash. **`MptStorage.nodesSharedAcrossTries`** (true of a hash store, false of the position store): where each account has a trie of its own a scan tells a storage trie by account and root, since the second account's can be missing while the first is whole; on a hash store by root, as before, so the result and its task count do not change. The validator's skip of a node it has seen is by hash only where a node is its hash. **A state fault is not a hole**: the validator answers `Left("State fault, not a hole")`, a scan ends. **Fixed here** (beacon): a legacy scan that could not run was taken as one that found nothing missing, and the recovery flag was written; it now ends recovery for the run and writes nothing, as the combined scan does, and tells the controller so (`RecoveryNotRun`), which does not count a scan that never ran against its cap on bulk recovery (forge). On a position store an account under a key that is not 32 bytes is a corrupt account trie, not a storage trie to heal (beacon). **Where the store comes from is unchanged**: the scans and the validator are handed SNAP's own store, before there is an anchor to take a view of; that is C5a's, and the allow-list says so. Not changed: a legacy scan that stops at a hole still settles on what it saw (B-217); what the controller does with a validation that ended in a state fault (B-216).)*
- [x] T061 [P] Convert or allow-list every hash-only walker: `main/mpt/MptVisitors/LeafWalkVisitor.scala`, `PathTrackingLeafWalkVisitor.scala`, `MptConstructionVisitor.scala`, `main/mpt/ShardEnumerator.scala` (dm 9, 6.5). *(Done, C2b's fourth PR. `PathTrackingLeafWalkVisitor` reads each node at the path it tracks, and `ShardEnumerator` at the prefix it carries. `LeafWalkVisitor` is deleted: its one caller, the legacy bytecode scan, uses the tracking visitor and ignores the key, and its tests now hold that one. `MptConstructionVisitor` and `MptTraversals.parseTrieIntoMemory` had no caller in main: they are test support now, under `test/mpt/MptVisitors` (dead-code review: defer to test). Nothing in main resolves a node of the state by hash alone in a walk any more; what is left of the allow-list's walking section is the store each walk is handed, and one `visitHash` that reads at its own path.)*
- [x] T062 Add the ratchet: `scripts/check-state-accessors.sh` and its allow-list `scripts/state-accessor-allowlist.txt`, wired into `scripts/ci/` so EG-7 can run it. Every remaining caller has a justification line. **The three batch reads (`multiGetNodes`, `rawNodes`, `rawNodesAt`) are on the ratchet too** (C2a, dm 9): their callers today are `ContractWorkCarry`, `FrontierRebuildBfs`, `HealResponseProcessing` and `BackgroundAudit`, none of them execution, and a wrapper in front of execution does not see a batch read. *(Done, C2b's first PR. **Eighteen entry points, not eight** (beacon, before code and again on the list): the four accessors, `getBulkLoadStorage`, `stageBlock`/`stageBlockState`, `prefetchReader`, `getNode`/`getMptNodeByHash`, a store's `get` by the names a store usually has, the three batch reads, the writes by hash (`storeRawNodes`, `saveNode`, `repairNode`, `deleteBadNode`), SNAP's two names for the store of a pivot (`trieStore`, `getOrCreateMptStorage`), every method whose result is a store, `storageMpt(`, `def visitHash`, a trie construction, and a hash store built by hand. 142 lines and 279 uses in 69 files, each line with its reason and the slice that removes it; 18 are marked `WRITER`. The count is exact in both directions, so the list only shrinks. It runs in the Test and Build job before the compile (`.github/workflows/ci.yml`) and again from T054's spec. It is text, not types: a store under another name gets past `get-by-hash`; the proof is C8b, which deletes the read. **Found while listing:** `PendingTransactionsManager` (`:498`) reads senders at the best block from a root alone, which no task named: it moves with the RPC readers. `MerklePatriciaTrie.getProof` keeps an unreachable arm that reads by hash alone (`:196`): T051 deletes it.)*
- [ ] T063 Run `sbt compile-all`, `sbt "testOnly *EthProof* *EthUser* *StateReadRetry* *RecoveryScanners* *Walkers*"`, the local rpc-compat (40) and graphql (2) runs through `test/jsonrpc/conformance/RpcCompatNode.scala`, `sbt scalafmtAll`, and `scripts/snap-split/verify.sh --branch <branch> origin/staging HEAD`.
- [ ] T064 Record the DC-14 figures in `spec/baselines/dc14-C2b.md` (EG-8), then open the C2b PR; request `conduit`, `herald`, `beacon`, `eye`.

**Checkpoint**: nothing in the tree reads state through a hash-only call without an allow-list line.

---

## Phase 5: C3a, the changeset record and CF `v` (item 6a)

**Goal**: the `BlockDiff` record, its codec, CF `v` with the height index, and the `fukuii.state.*` keys exist and are pinned byte
for byte. Nothing reads them until C3b. **The format is a one-way door.**

**S0 prerequisites**: none. The golden vectors are verified by both reviewers; T014 froze them in C0.

**Reviewers**: **[forge] [beacon] sign the golden vectors before merge**; prism.

**Independent test / exit**: golden vectors 1 to 3 (153 B `8d0afd3e`, 219 B `707894bf`, 144 B `ae4e831f`) are reproduced by the codec
and re-derived with `java.util.zip.CRC32C`; every structural rejection is tested; CF `v` appears on an existing database.

### Tests (write first; they must fail)

- [x] T065 [P] Golden-vector test: decode each hex file of T014, compare field by field with the dm 4.3 construction, re-encode to the identical bytes, and verify the CRC32C with `java.util.zip.CRC32C` (vector 2's prior is the valid 37-byte extension node; vector 3 is the incomplete tombstone, flag bit 1, entry count 0). File: `test/db/storage/pathstate/BlockDiffCodecGoldenSpec.scala`. **[forge] [beacon]** *(Done, C3a. The three files are decoded, compared with the construction of dm 4.3 and encoded back to the same bytes. A third implementation, in Python, verified the files and produced the further pinned values: an empty record, the three widths of a length, a 200-entry record over both tries.)*
- [x] T066 [P] Test the codec's **structural** rejections only (version, kind, flags, sort order, uniqueness, presence flags, CRC, trailing bytes) and that the inline rule and prior-not-equal-next are **not** checked by the codec (dm 3 intro, 4.2). File: `test/db/storage/pathstate/BlockDiffCodecSpec.scala`. *(Done, C3a. Each record is put together byte by byte with a right checksum, so a case shows one defect. The reader refuses four things more than dm 4.2 listed, so that what it accepts re-encodes to its own bytes: dm 4.2, "C3a as built".)*
- [x] T067 [P] Test `ChangesetStorage`: key `blockHash(32) ++ kind(1)` (kind 0x01), the height index `0x00 ++ number(8)`, kind 0x02 left unwritten, prune idempotence below a floor, and a record round trip on `EphemDataSource` and RocksDB (dm 4.1, 4.5). File: `test/db/storage/pathstate/ChangesetStorageSpec.scala`. *(Done, C3a. Pruning is by point reads of the heights it is given, never by a scan: a record key whose hash begins with zero bytes sorts among the height keys, which on ETC is every block (dm 4.1, "C3a as built"). The caller keeps the height it has pruned up to: T095.)*
- [x] T068 [P] Test that CF `v` is created on an **existing** RocksDB database at its next start (`setCreateMissingColumnFamilies(true)`), with nothing migrated. File: `test/db/dataSource/ChangesetColumnFamilySpec.scala`. *(Done, C3a.)*
- [x] T069 [P] Test the config: every `fukuii.state.*` key of dm 10 loads with its default, `reorg.max-depth = floor` resolves to the retention floor, and an unknown `pruning.mode = "inmemory"` is not accepted by the state config. File: `test/db/storage/pathstate/StateConfigSpec.scala`. *(Done, C3a. Also pinned: the set of keys under `fukuii.state`, so a key the code does not read cannot ship.)*

### Implementation

- [x] T070 Create `main/db/storage/pathstate/BlockDiff.scala` (the in-memory unit of dm 3: `PositionKey`, `Change`, `BlockDiff`, with the writer-side `validate` for rules 2, 4 and 5) and `main/db/storage/pathstate/BlockDiffCodec.scala` (the record of dm 4.2, CRC32C, uvarint entry count, the incomplete-tombstone flag). **`PositionKey` already exists** (`pathstate/PositionKey.scala`, C2a part 2: `(owner, hp)` with equality by content); this task adds `Change` and `BlockDiff` on it. *(Done, C3a. `BlockDiff.byHash` is derived from the `next` nodes and is not a field; values are `ByteString`, so two diffs compare by content.)*
- [x] T071 Create `main/db/storage/pathstate/ChangesetStorage.scala` (CF `v`: record, height index, retention deletes) and add `ChangesetNamespace` (`'v'`) to `main/db/storage/Namespaces.scala` and to `nsSeq` (the existing comment explains why an existing database gains the empty CF on its next start). *(Done, C3a. Every write is offered as updates for the caller's batch (`recordUpdates`, `pruneUpdates`): the record belongs in the block's own atomic write, which is C3b's.)*
- [x] T072 Add the remaining `fukuii.state.*` keys to `src/main/resources/conf/base/state.conf` exactly as dm 10 (`changeset`, `retention`, `overlay`, `move`, `archive`, `recovery` including `escalation-refusals = 64` and `escalation-cooldown = 6h`, and `reorg.max-depth = floor`) and load them in `main/db/storage/pathstate/StateConfig.scala`. *(Done, C3a. `recovery.max-blocks` is `${fukuii.pruning.history}`: a substitution is resolved from the root.)*
- [x] T073 Run `sbt compile-all`, `sbt "testOnly *BlockDiff* *Changeset* *StateConfig*"`, `sbt scalafmtAll`. The specs are the files named in this phase's tests, under `src/test/scala/` and `src/it/scala/`. *(Done, C3a: 49 tests, on `EphemDataSource` and RocksDB where a data source is involved. Five rules were then broken on purpose and each was caught: plan.md, "C3a record".)*
- [ ] T074 Record the DC-14 figures in `spec/baselines/dc14-C3a.md` (EG-8), then open the C3a PR; request `forge` and `beacon` to sign the vectors, and `prism`. Any later change to the record is a layout-marker bump (dm 4.4). *(Open for the DC-14 figures, which need C0's pinned jar and ranges (B-203). forge and beacon signed the record: plan.md, "C3a record".)* *(C3a merged as #1793 on the user's word, 2026-11-11.)*

**Checkpoint**: the record format is frozen and read by nothing.

---

## Phase 6: C3b, the staged commit, the state lock and the canonical in-place commit (item 6b)

**Goal**: with the guard on, a block that extends the anchor commits state, changeset record, anchor and best pointer in ONE
batch (dm 7.1), under one process-wide state lock with an anchor compare-and-set (dm 7.0); the trie's positions are staged per
block with a by-hash in-block layer; replaced storage tries are removed or tombstoned and never fail a block. Serves US2.

**S0 prerequisites** (Satisfied, verified at this PR): beacon item 3 (iii), an absent anchor refuses every in-place writer (dm 7.0);
forge item 5, a reset owner's priors are the scanned parent rows (dm 3.6).

**Reviewers**: **[beacon] [forge] before code**; eye. **Consensus-critical**: follow the Consensus-Critical Change Protocol.

**Independent test / exit**: the strict import of the fixed Platåberget, Sepolia and Mordor ranges with the guard on gives **every
root equal to the pinned jar's**; the deterministic interleaving test passes; the tombstone never fails a block.

### Tests (write first; they must fail)

- [ ] T075 [P] [US2] Differential: import the fixed ranges of T004 through `ChainImporter.importChainFileStrict` with the guard on and assert every state root, receipts root and gas used equals the recorded value. (Harness-level EG-1 and EG-2 claims wait for C3c.) File: `it/db/pathstate/CanonicalCommitRootsSpec.scala`. **[beacon] [forge]** *(Substitute done, C3b part 2; the task stays open for the recorded values. C0's fixed ranges and pinned jar do not exist (B-203), so the differential is in process: the execution-apis chain (60 blocks), Mordor's first 256 blocks under its own validators, and the execution-spec-tests corpora (Paris and Shanghai: 8,835 fixtures; Amsterdam: 26,503), each imported through `executeAndValidateBlocks` onto the unified layout, with the disk held to each block's state and each record to its reverse. plan.md, "C3b wiring record". The half against recorded values waits for C0.)*
- [x] T076 [P] [US2] Test the **by-hash in-block layer**: an Amsterdam block's BAL `before` and `after` reads, lenient retries and `StateBeforeFailure` all see every node version written in the block (dm 6.1, 6.2). File: `test/db/storage/pathstate/StagedLayerByHashSpec.scala`. **[beacon]** *(Done. At the layer in part 1 (`StagedChangesSpec`, "an older world's reads"). Through real blocks in part 2: the Amsterdam blocks of the execution-apis chain and the Amsterdam corpus build their access lists on the staged layer, reading the world before a transaction after the next was persisted, and validate against their headers' access-list hashes. A retry starts from a fresh layer (`PathStagedBlockSpec`); `StateBeforeFailure` is a builder's, on a throwaway layer.)*
- [x] T077 [P] Test the **first-event prior** and coalescing across several persist rounds per block (dm 3 rule 1). File: `test/db/storage/pathstate/StagedFirstEventPriorSpec.scala`. *(Done, C3b part 1, in `test/db/storage/pathstate/StagedChangesSpec.scala`: hot rewrites across persist rounds, a position created then rewritten (prior none), vanished positions, nodes inside their parent, a root of any size, 40 seeded random blocks.)*
- [x] T078 [P] Test atomicity: apply a canonical commit then reverse it from its record and every row of `t`, `u` and `v` is byte-identical to before; an injected failure mid-batch leaves nothing (dm 7.1). File: `test/db/storage/pathstate/CanonicalCommitAtomicitySpec.scala`. *(Done, C3b part 1, in `CanonicalCommitSpec`, on the in-memory data source and RocksDB: forward and back from the records over three blocks; a write that throws leaves rows, anchor, best pointer and prune count as they were. A crash inside a RocksDB WriteBatch is RocksDB's contract and is not injected.)*
- [x] T079 [P] Test replaced storage tries (dm 3.6): tx1 writes A's storage, tx2 SELFDESTRUCTs A, tx3 CREATE2s A; EIP-161 removal of an account with storage; Amsterdam's clear with the account kept; the owner's rows equal the recreated trie. File: `test/db/storage/pathstate/ReplacedStorageTrieSpec.scala`. **[beacon] [forge]** *(Done. On the layer and through the commit in part 1. Through the EVM in part 2 (`EestImportOnPositionsSpec`): `selfdestruct-stored-storage.json`, and the corpora, where 336 (Paris and Shanghai) and 848 (to Amsterdam) storage tries are removed whole, EIP-161 clearing and Amsterdam's clear among them, each with its rows exact and its reverse exact.)*
- [x] T080 [P] Test the **reset-owner prior** (forge item 5): tx1 writes, tx2 destroys, tx3 recreates and writes over a position the dead trie occupied, then reverse, and every row of `u` equals the parent's (a prior of None there would delete it). File: `test/db/storage/pathstate/ResetOwnerPriorSpec.scala`. **[forge]** *(Done, C3b part 1: `StagedChangesSpec` and `CanonicalCommitSpec`, "put them all back on a reverse".)*
- [x] T081 [P] Test the bound: above `max-entries-per-owner-delete` or `max-record-bytes` the deletion is skipped, the record is an incomplete tombstone (flag bit 1, no entries), **the block commits**, and a reverse across it is refused with `ReorgTooDeep(Incomplete)`; a hole in a dead trie does not fail the block (prefix scan, dm 3.6). File: `test/db/storage/pathstate/DeadTrieBoundSpec.scala`. **[forge]** *(Done, C3b part 1, in `CanonicalCommitSpec`: over the bound the dead rows stay, every other row is written, the record is a tombstone and the block commits; exactly at the bound the trie is listed; a record over `max-record-bytes` is a tombstone with every row written; a dead trie with a hole commits. The refusal of a reverse across a tombstone is the head move's, C4b: nothing reverses yet.)*
- [x] T082 [P] Test the **no-anchor rule**: with the anchor deleted, the canonical commit, a head move and a repair are refused (`StateUnavailable` or declined) and every row of `t` and `u` is byte-identical before and after (dm 7.0). File: `test/db/storage/pathstate/NoAnchorRefusalSpec.scala`. **[beacon]** *(Done for the canonical commit, C3b part 1: `CanonicalCommitSpec`, "with no state anchor". A head move and a repair join it when they exist, C4b and C5b.)*
- [ ] T083 [P] Test the three "not extending the anchor" cases of dm 7.1: an already-applied block drops the state half and commits `extra`; a forward-layer block is promoted in the same batch; a parent that is not the anchor degrades to an overlay commit with `buildExtra(false)` and returns `NotExtendingAnchor`. File: `test/db/storage/pathstate/CommitCasesSpec.scala`. *(Partly done, C3b part 1: the already-applied case, with a second layer and with nothing staged. **A block whose parent is not the anchor is refused with `StateUnavailable` and nothing written**, where dm 7.1 has it degrade to an overlay commit: there is no overlay before C4a, which also owns the promotion of a forward layer.)*
- [x] T084 [P] Test that `executeBlock(staged = None)` throws on a path-live store (dm 7.6). File: `test/ledger/BlockExecutionUnstagedSpec.scala`. *(Done, C3b part 2, in `ImportOnPositionsSpec`: `BlockchainImpl.getBackingMptStorage` throws on a path-live chain, which is what an execution with nothing staged asks for.)*
- [x] T085 [P] Test the retention modes: `basic` deletes records below the floor inside the next block's batch; `archive` keeps them (dm 4.5, 4.6); the rejection of `pruning.mode = "inmemory"` at start lands in C5a. File: `test/db/storage/pathstate/RetentionModesSpec.scala`. *(Done, C3b part 1: `CanonicalCommitSpec` (records below the floor go in the batch of the block that puts them there; archive keeps all; a datadir switched from archive catches up in one batch; the record of the block being committed is never reached) and `RetentionPolicySpec`.)*
- [ ] T086 [US2] **Deterministic interleaving test** with a controllable scheduler (no `Thread.sleep`): the importer's canonical commit, an overlay commit, a repair write and a builder view in every order the scheduler can produce; the final position map equals a from-scratch replay; the commit that loses the compare-and-set degrades or re-plans as dm 7.0 says. File: `test/db/storage/pathstate/StateLockInterleavingSpec.scala`. `moveHead` and `newPayload` on `view(head)` join it in C4b and C4d. **[beacon]** *(Partly done, C3b part 1: two commits on one anchor in both orders, two commits of one block, and a reader asking for a view during the write, each held at the write by a latch and released when the other is known to wait for the lock (`CanonicalCommitSpec`, no sleep). The overlay commit and the repair write join when they exist.)*

### Implementation

- [x] T087 Create `main/db/storage/pathstate/StateLock.scala`: one process-wide fair mutex (not `ChainInsertLock`, not `BlockImportGate`) held from "check" to "epoch even" by every in-place batch; the holder name is exposed for `app.db.state.lock.wait.seconds` (dm 7.0, 11). **[vault]** **It is what makes a `StateLockHeld`** (C3a): the value `ChangesetStorage` asks for, so that the read-modify-write of a height's index cannot be built outside the lock. *(Done, C3b part 1. Fair, **not reentrant** (vault: an in-place write inside another is a defect, and is refused), innermost: nothing under it takes an import lock.)*
- [x] T088 Create `main/db/storage/pathstate/PathStagedState.scala`: the `StagedLayer` with `byHash` for every in-block node version (asked first), the position map with the first-event prior and coalescing, and the reset set (dm 3 rules 1 to 5, 6.1, 6.2). **[beacon] [forge]** *(Done, C3b part 1, as `StagedChanges` (the position map and the reset set), the staged mode of `PathMptStorage` (the by-hash layer it already had, which now also records) and `PathStagedState` (one execution's layer). **A layer takes one line of worlds**: a world that does not follow the last one persisted on it is refused (beacon, forge).)*
- [x] T089 Wire reset tracking: `deleteAccount` in `main/ledger/InMemoryWorldStateProxy.scala` (around `:219-224`) calls `MptStorage.resetOwner(owner)`; at the persist after the deleting transaction (`main/ledger/BlockPreparator.scala` `:685-693`) earlier positions of that owner are removed from the staged layer and later recreations survive (dm 3.6). **[forge]** **From the C2a reviews (forge, beacon):** (a) `deleteAccount` already calls `resetOwner` (T046), on the world's storage and at once, so the reset must land in the layer of the execution that made it: the block's `StagedBlockState`, dropped with a rejected block (`BlockExecution.scala:64-76`). (b) A reset never drops `byHash`: the access-list builder reads the trie as it was before the transaction after the reset (`BlockPreparator.scala:935`, `BlockExecution.scala:318`), and the rows at block start stay readable. (c) A reset of an owner that holds no row is the common case (695 of the 717 resets in the Paris and Shanghai fixture sets, all 737 in the Amsterdam corpus) and must cost nothing. (d) The oracle: `selfdestruct-stored-storage.json` under `EestPositionReplaySpec`, where a missing reset leaves rows behind. *(Done, C3b part 1, differently (forge, a BLOCK on the design): **the world carries the owners it has reset**, and tells its storage at `persistState`, before the contract storage is persisted. A second place replaces a storage trie whole, `initialiseAccount` over an account that has storage (a create collision, reachable on ETC), inside a frame that can revert: a reset applied at once to the shared layer could not be undone. `WorldStorageResetSpec`. Follow-up for C3c (T108): `TestModeWorldStateProxy` has its own `deleteAccount` and needs the same.)*
- [x] T090 Implement `StateView.ownerRows` (prefix scan of CF `u`, merging forward layers and the reverse index for a non-anchor view; bypasses the BAL builder, `ImportProfile` and the spec 017 recorder) in `main/db/storage/pathstate/StateView.scala`, and the diff-finalisation step: for a reset owner every scanned row is a vanished position whose **prior is that parent row's bytes**, override of rule 1; above `max-entries-per-owner-delete` or `max-record-bytes` skip the deletion and emit an incomplete tombstone; count `app.db.state.dead-trie.skipped` and `app.db.state.record.tombstones` (dm 3.6, 11). **[forge] [beacon]** *(Done, C3b part 1, as `PathNodeStorage.storageRows(owner, limit)` and `StagedChanges.finalise`. **The scan runs under the state lock**, after the anchor check, on the disk's rows (vault), where dm 7.0 has it outside the lock on a view: the priors must be the rows the batch is written over, and before the overlay there is one view to scan. `StateView.ownerRows`, merging layers for a view that is not the anchor's, is C4a's. The placeholder on `PathStateStorage` is gone. The scan is made in chunks of 8,192 rows and is bounded in bytes too, 64 MiB (vault): a trie over either bound is not listed.)*
- [x] T091 Add the canonical commit to `main/db/storage/pathstate/PathStateStorage.scala` (dm 7.1): one batch of block data (`buildExtra(true)`), `t`/`u` upserts and deletes for the diff's positions only, the CF `v` record, the height index, the retention deletes, and `state.anchor` **last**; compare-and-set the anchor under the lock; treat "no anchor" as a refusal; publish the `StateMove` with pre-batch values and bump the epoch (dm 6.3.1, 7.0). Nothing is written to CF `n`. **The clock is already there** (C2a part 2): the batch is written inside `StateMoveLog.inFlight(move) { … }`, under the state lock, which publishes the move before it and settles after it, as not committed when the batch throws. Three rules from vault's review of it: the anchor written in the batch is `move.newAnchor` (the clock takes its anchor from the move and does not read AppState again); `PathStateStorage` is the only caller of `putStateAnchor`; and there is one clock per data source (a second `Storages` over the same data source would have a second clock). `AppStateStorage.putStateAnchor` is `private[storage]`, and `AppStateAnchorRatchetSpec` allows `PathStateStorage.scala` as its only caller (beacon). The record and the height index are built with `ChangesetStorage.recordUpdates(diff)(using StateLockHeld)`; a height index that is not a list of 32-byte hashes throws there, and the commit must report it as storage corruption, never as a fault of the block (beacon, C3a). *(Done, C3b part 1: `PathStateStorage.stage(parent)` and `commitCanonical(staged, block, extra)`. The caller hands a finished `extra`, not `buildExtra(adopted)`: with no overlay to degrade to, a commit is in place or refused, so there is nothing for the flag to choose. C4a brings it back with the overlay commit.)*
- [ ] T092 Implement the three non-extending cases of dm 7.1 (already applied; forward-layer promotion, applied in place if its parent is the anchor; degrade to an overlay commit and return `NotExtendingAnchor`) and the overlay-commit entry point stub that C4a completes, in `main/db/storage/pathstate/PathStateStorage.scala`. *(Partly done, C3b part 1: already applied. Promotion and the overlay commit are C4a's; see T083.)*
- [x] T093 Change `main/ledger/BlockExecution.scala`: commit mode, `buildExtra(adopted: Boolean)`, `stageBlock(parent, bn)`, and the `staged = None` guard that throws on a path-live store (dm 7.1, 7.6); plumb the staged layer through `main/domain/Blockchain.scala`. **[beacon] [forge]** **Also owns the prefetch switch (from T045):** the path-keyed `StagedBlockState.prefetchReader` returns `PathStateStorage.prefetchReader(parent, …)`, with a test that the same block executes identically with and without the path prefetch (beacon, C2a part 2). **Release note (C3a reviews):** building `StateConfig` makes `pruning.mode = "inmemory"` fail at start, and a datadir this track has started cannot be opened by an older build. **One downgrade barrier** (user decision, 2026-11-11): the release that first ships column family `v` ships every other such change of the track; the note is written once. *(Done, C3b part 2: `PathStagedBlock`, `stageBlockState(header)`, the refusal of a block that is not adopted, the `staged = None` guard, the prefetch switch with its test. `buildExtra(adopted)` is not there: with no overlay a commit is in place or refused, and the flag returns with C4a. The release note is still to write, once, with the release that ships column family `v`.)*
- [ ] T094 Add `isBlockApplied` and `stateAvailable(hash)` (a forward layer, the anchor, or an anchor ancestor with a complete record) to `main/db/storage/StateStorage.scala` and use `stateAvailable` where `parentValidated` used "receipts stored" (`main/consensus/engine/EngineApiService.scala` `:588-591`, changed in C4d). *(Partly done, C3b part 2: `isBlockApplied` is the staged block's, from the anchor; `BlockchainReader.holdsStateOf(header)` is what the strict importer asks. `stateAvailable(hash)` over layers and records, and its use in `EngineApiService`, are C4a's and C4d's.)*
- [x] T095 [P] Implement the `basic` and `archive` modes in `main/db/storage/pathstate/RetentionPolicy.scala` (retained = clamp(max(history, anchor - finalized + margin), history, max-blocks); `archive` keeps every record) and the `[IMPORT-COMMIT]` timing line (dm 4.5, 4.6). **The policy keeps the height it has pruned up to** (C3a): `ChangesetStorage.pruneUpdates(from, floor, maxHeights)` reads heights by their own keys and returns the next `from`. It must not look for the lowest height with a range scan (dm 4.1). `StateConfig.retained` is the floor's arithmetic. **The cursor is persisted** (forge, C3a): without it a restart, or a switch from archive to basic, would point-read every height from 0. **Where there is no finalized block the floor is `state.retention.without-finality` = 512** (user decision, 2026-11-11); `StateConfig.retained` already says so. *(Done, C3b part 1: `RetentionPolicy`, and the count of pruned heights in AppState, `state.changeset.pruned-to`: the first commit on a datadir starts it at itself, it only goes up, and it moves in the batch that deletes. `finalized` is wired as none until a consensus client drives this layout (C4d). The `[IMPORT-COMMIT]` line is `BlockExecution.timedCommit`'s, which part 2 puts around the commit.)*
- [ ] T096 [P] Add the metrics of dm 11 that belong to this slice (`anchor.number`, `anchor.lag.blocks`, `commit.seconds`, `commit.positions`, `commit.bytes`, `changeset.records`, `lock.wait.seconds`, `commit.degraded`, `record.tombstones`, `dead-trie.skipped`) in `main/db/storage/pathstate/StateMetrics.scala`. *(Partly done, C3b part 1: `StateMetrics`, an observer of the commits. `anchor.lag.blocks` needs the best block and comes with the wiring (part 2); `commit.degraded` with the overlay (C4a).)*
- [ ] T097 Run `sbt compile-all` (wraith for fallout), `sbt "testOnly *CanonicalCommit* *StagedLayer* *StagedFirstEvent* *ReplacedStorageTrie* *ResetOwnerPrior* *DeadTrieBound* *NoAnchor* *CommitCases* *Unstaged* *RetentionModes* *StateLockInterleaving*"`, then `sbt scalafmtAll`. The specs are the files named in this phase's tests, under `src/test/scala/` and `src/it/scala/`.
- [ ] T098 **M1 (final)**: on the real code, report changed positions, diff bytes and vanished positions per block (p50, p99, max) for the fixed ranges, and the largest diff against `max-record-bytes`. Record in `spec/baselines/m1-final.md`; it sets the overlay budget, `max-layer-bytes` and dm 12.
- [ ] T099 **M8**: compare the in-place commit with today's commit per block and total bytes written on Platåberget and Sepolia (the write-amplification claim). Record in `spec/baselines/m8-write-amp.md`.
- [ ] T100 **M15 (part 1)**: record rows per deleted storage owner (p50, p99, max) and the number of tombstones written over the Mordor from-genesis import; `max-entries-per-owner-delete` must sit well above the observed maximum. Record in `spec/baselines/m15-mordor.md`. The ETC 0 to 5.1M half runs in C7.
- [ ] T101 Record the DC-14 figures in `spec/baselines/dc14-C3b.md` (EG-8), then open the C3b PR with the failing-first test run in its description; request `beacon` and `forge` (verifying the two Satisfied prerequisites) and `eye`.

**Checkpoint**: with the guard on, strict imports commit in place and equal the pinned jar's roots; proposer, bypass and harness paths still run on the old store, which is exactly C3c.

---

## Phase 7: C3c, proposer view, bypass writers, genesis commit and the ported harnesses (item 6b')

**Goal**: no path outside the staged commit can write a state row. Every proposer path builds over a throwaway view, the lenient
importer and test mode go through the same commit, genesis is a commit, and the five test harnesses run on the position store so
that every later roots-equal claim (EG-1, EG-2) measures the right store. Serves US2.

**S0 prerequisites** (Satisfied, verified at this PR): forge item 12, `MockedMiner` waits for the import of the mined block (dm E
table, block generation row).

**Reviewers**: **[beacon] [forge] before code** (proposer, genesis); **[conduit]** (RPC harness); eye.

**Independent test / exit**: the proposer never writes a row on an Amsterdam build; a mined block with ommers has the root its import
computes; the ported harnesses pass and a canary fails any harness that does not touch the position store; the 658-fixture eip8246
count is recorded again.

### Tests (write first; they must fail)

- [x] T102 [P] [US2] Test **the proposer never writes a row**: on an Amsterdam build with a BAL, withdrawals, all five system calls and the empty-payload fallback, the row set of `t`, `u`, `n` and `v` is identical before and after a build, and no forward layer is registered (dm E table "Block generation and mining"). File: `test/ledger/ProposerNeverWritesSpec.scala`. **[beacon]** *(Done, C3c part 2, in `test/jsonrpc/conformance/ProposerOnPositionsSpec.scala`: every post-merge block of the execution-apis chain to Amsterdam is built on the unified layout, strict and lenient, to the fixture's hash, and every key of the datadir but contract code is compared around each build. Code is the one thing a build writes, by its hash, on both layouts (plan.md, "C3c proposer view record"). There is no forward layer to register before C4a.)*
- [x] T103 [P] [US2] Test that a mined PoW block with ommers has exactly the state root its import computes (the proposer's throwaway layer versus the import's commit). File: `test/consensus/pow/MinedBlockRootSpec.scala`. **[forge]** *(Done, C3c part 2, in `test/consensus/blocks/MinedBlockOnPositionsSpec.scala`, as a differential with the hash store: the block prepared on each layout is the same block, with ommers, and import computes its root.)*
- [x] T104 [P] Test **`MockedMiner` waits for the import** (forge item 12): two blocks scheduled one second apart with a deliberately slow import of the first; the second is built from `view(minedBlock)` after the import finishes, never `MiningFailed`; deterministic scheduler, no sleep. File: `test/consensus/pow/MockedMinerImportWaitSpec.scala`. **[forge]** *(Done, C3c part 2, in `test/consensus/pow/miners/MockedMinerOnPositionsSpec.scala`, on manual time. The miner cannot run in a node on the guard while the mining refusal stands (no route for a mined block before C4b): plan.md.)*
- [x] T105 [P] Test the cached-world rule: a `PendingBlockAndState` world in `PoWBlockGenerator.cache` is never committed (a mined block is re-executed through `MinedBlockImport`) and never read once stale. File: `test/consensus/pow/blocks/PoWBlockGeneratorCacheSpec.scala`. *(Done, C3c part 2, in `MinedBlockOnPositionsSpec`: a block that is prepared writes nothing and its cached world is not the disk's; `BlockParam.Pending` is the head on the unified layout, so the cached world is not read at all there (forge: readers by the pending header would be refused).)*
- [x] T106 [P] Test the builder: a stale view during an Engine API build retries the build and does not fall to the empty-payload fallback; each lenient attempt gets a fresh view; `testing_commitBlockV1` commits through the import path (dm E table). File: `test/consensus/engine/BuilderStaleViewRetrySpec.scala`. **[beacon]** *(Done, C3c part 2, in `ProposerOnPositionsSpec`. `testing_commitBlockV1` commits through the adopting import, not `newPayload`, which adopts nothing before the overlay (beacon).)*
- [x] T107 [P] Test the **genesis commit** (dm 5.4): owner-scoped, insert-only (the `AppendOnlyMptStorage` drops removals), no `BlockDiff`, an empty alloc leaves no `t` row and the root `EmptyRootHash`, the `state.layout = path-live-1` stamp is in the same batch as the anchor, and non-empty storage with `ethCompatibleStorage = false` is refused. File: `test/blockchain/data/GenesisCommitSpec.scala`. **[forge] [beacon]** *(Done, C3c part 1, in `test/blockchain/data/GenesisCommitSpec.scala`: the shipped ETC, Mordor, Platåberget and Sepolia genesis files and hand-made ones, each loaded on both layouts. **The build honours the tries' removals** where dm 5.4 has it insert-only through `AppendOnlyMptStorage` (forge agreed: plan.md, "C3c genesis record"); the rows are the same, and no position vanishes.)*
- [x] T108 [P] Test test-mode genesis: `testmode` skips `GenesisDataLoader` (`main/nodebuilder/StdNode.scala` `:138-139`) and `TestService.storeGenesisAccountStorageData` builds storage tries through the same genesis commit with owner threading. File: `test/jsonrpc/TestServiceGenesisSpec.scala`. *(Done, C3c part 3c, in `test/jsonrpc/TestServiceOnPositionsSpec.scala`, and differently: on the unified layout `test_setChainParams` commits its genesis through the node's own loader, after destroying the state of the chain before it (`PathStateStorage.destroyStateForNewGenesis`, test mode only), and `storeGenesisAccountStorageData` is not called there: the storage tries are rows of the genesis commit. plan.md, "C3c part 3c".)*
- [ ] T109 [P] [US2] Test `ChainImporter`: the lenient path (`importLenient`, hive and ethereum/tests) and the strict path both commit through the staged commit, **post-merge side blocks included**, and a hive startup import on the unified store reaches the expected head. File: `it/blockchain/data/ChainImporterPositionSpec.scala`. **[beacon]** *(Part done, C3c part 3a, in `test/blockchain/data/ChainExportImportSpec.scala`: the lenient path commits a block built on the head through the node's import of one block onto the head, and the strict path did since C3b. **Side blocks, post-merge ones included, are refused on the unified layout until the overlay (C4a) and the head move (C4b)**, by the user's decision of 2026-11-12 (plan.md, "C3c part 3"); the task stays open for them, and for the hive start-up import under `it/`.)*
- [x] T110 [P] [US2] **Harness canary**: a harness run that never touches the position store (still on the Hash store) fails. File: `it/ethtest/HarnessCanarySpec.scala`. EG-2 is valid only while this is green. *(Done, C3c part 3b, in `test/ledger/HarnessCanarySpec.scala` (under `test/`, where the fixture replays are, and not `it/`): a replay on positions asks its datadir what wrote it, and a run on the hash store is seen by that question.)*

### Implementation

- [x] T111 Give every proposer path a **proposer view**: `view(parent)` plus a throwaway `StagedLayer` that absorbs `persist()`, never commits, never writes a row, never registers a forward layer, is content-addressed and fresh per lenient attempt. Files: `main/ledger/BlockExecution.scala` (`executeForProposer`, `:241-244`, `:358-388`), `main/ledger/BlockPreparator.scala` (`prepareBlock`, `:1043-1076`). **[beacon] [forge]** **Must land before C3b's reset does anything (forge, C2a review):** the PoW miner prepares blocks over `getReadOnlyMptStorage()` (`BlockPreparator.scala:1021`, from `PoWBlockGenerator.scala:90` and `RestrictedPoWBlockGeneratorImpl.scala:57`), `eth_simulateV1` forwards `resetOwner` to that same store (`EthSimulateService.scala:315`), and the lenient builder re-executes from one parent: each of these issues resets on a storage nobody commits. Every attempt gets its own throwaway layer that holds the resets and the position writes, or a position-keyed read-only view buffers them and drops them at `persist`. A mirrored builder run (the `StorageObserver` of T026 on `EestBuilderReplay`) is the test. *(Done, C3c part 2: `BlockchainReader.proposerStateOn`, a throwaway layer over a view of the parent, in `executeForProposer` and `prepareBlock`; the pool's nonce filter reads through the seam. `eth_simulateV1` took `scratchStateAt` in C2b.)*
- [x] T112 [P] Apply it to the PoW generators and callers: `main/consensus/pow/blocks/PoWBlockGenerator.scala` (including the cached-world rule), `main/consensus/pow/blocks/RestrictedPoWBlockGeneratorImpl.scala`, `main/consensus/blocks/NoOmmersBlockGenerator.scala`, `main/consensus/pow/PoWBlockCreator.scala`, `main/jsonrpc/EthMiningService.scala` (`eth_getWork`, one generation per call), `main/testmode/TestmodeMining.scala`, `main/jsonrpc/TestService.scala` (`test_mineBlocks`). **[forge]** *(Done, C3c part 2, for the PoW generators, `PoWBlockCreator` and `eth_getWork` (which, with `eth_submitWork`, refuses on the guard: no route for a mined block before C4b). `TestmodeMining` and `test_mineBlocks` are test mode: part 3.)*
- [x] T113 [P] Make `main/consensus/pow/miners/MockedMiner.scala` rebuild from `view(minedBlock)` after each import and **wait for that import** (or retry) instead of failing; `MiningFailed` must not drop the run to `waiting()` for a slow import (forge item 12). **[forge]** *(Done, C3c part 2. Ten ticks of wait; a block lost to a sibling ends the run at once.)*
- [x] T114 Apply it to the Engine API builder in `main/consensus/engine/EngineApiService.scala` (`executeForProposer`, fresh view per attempt, a stale view retries the build, no empty-payload fallback) and route `testing_commitBlockV1` (`main/jsonrpc/TestingJsonMethodsImplicits.scala`, `main/jsonrpc/JsonRpcController.scala`) through the import path. **[beacon]** *(Done, C3c part 2. A fault of the node's state in the first build answers -38003 with no payload id. A node on the guard cannot yet import the payload it built through `newPayload`: that is the overlay's (C4a, C4d).)*
- [x] T115 Implement the **genesis commit** in `main/blockchain/data/GenesisDataLoader.scala`: the canonical-commit path of dm 7.1 with no parent, `anchor = genesis`, the `state.layout` stamp in the same batch, owner-scoped insert-only tries, no `BlockDiff`, the `ethCompatibleStorage = false` refusal; route test-mode genesis (`main/jsonrpc/TestService.scala`, `main/nodebuilder/StdNode.scala`) through it. **[forge] [beacon]** *(Done for the node's genesis, C3c part 1: `GenesisLayer`, `PathStateStorage.commitGenesis`, `GenesisDataLoader.over`. The marks of a created datadir are in the batch. **The layout rule of dm 5.3 is in the loader for now**, both ways, since this is the first writer of the stamp; C5a moves it to the node's guard at start. Test-mode genesis is part 3, with test mode (T108).)*
- [x] T116 Convert `main/blockchain/data/ChainImporter.scala` (`importChainFile` lenient, `importChainFileStrict`) and any remaining writer of the dm Part 2 A table to the staged commit; a writer reached with `staged = None` must throw (dm 7.6). **[beacon]** *(Done for the linear subset. C3c part 3a: `importChainFile` (lenient) on the unified layout; `importChainFileStrict` did since C3b. C3c part 3c: test mode (`TestService`, `TestModeBlockExecution`, `TestModeWorldStateProxy`). What needs the state of a block that is not the head (side blocks, reorganisations, rewinds) is refused until C4a and C4b: B-220.)*
- [ ] T117 Port the five harnesses to the position store, using the genesis commit to seed: `it/ethtest/EthereumTestHelper.scala`, `it/ethtest/EthereumTestExecutor.scala`, `it/ethtest/ForkChoiceBlockchainTestsSpec.scala`, `test/ledger/EestBlockchainReplay.scala`, `test/jsonrpc/conformance/RpcCompatNode.scala`. **[conduit]** for the RPC harness. *(Part done, C3c part 3b: `EestBlockchainReplay.replayOnPositions` and `EestBuilderReplay` start from the node's genesis commit under the fixture's own header (`loadFixtureGenesis`); `RpcCompatNode` runs on the unified layout in three specs by an override of its storages. **The three harnesses under `it/ethtest` wait for the overlay and the head move** (side blocks, `reorganise`): plan.md, "C3c part 3b".)*
- [ ] T118 Run the ported harnesses with the guard on and compare with the pinned-jar baselines of C0 in `spec/baselines/`: the ethereum/tests job, EEST, `ForkChoiceBlockchainTestsSpec` under `basic` and `archive`; record the **658-fixture `eip8246` pass count again** next to `spec/baselines/eip8246-fixtures.txt`. **[eye]**
- [ ] T119 Run `sbt compile-all`, `sbt "testOnly *Proposer* *MinedBlock* *MockedMiner* *PoWBlockGenerator* *BuilderStaleView* *GenesisCommit* *TestServiceGenesis*"`, `sbt "IntegrationTest / testOnly *ChainImporterPosition* *HarnessCanary*"`, `sbt scalafmtAll`.
- [ ] T120 Record the DC-14 figures in `spec/baselines/dc14-C3c.md` (EG-8), then open the C3c PR; request `beacon`, `forge`, `conduit`, `eye`.

**Checkpoint**: nothing writes a state row except the staged commit; the guard-on harnesses are the oracle for everything after.

---

## Phase 8: C4a, the overlay (item 6c)

**Goal**: forward layers, the reverse index, the full epoch-validated `StateView`, the move log, budgets, eviction and pinned layers
exist, so a non-head root (a sibling, a side payload, a block one back) can be read without touching the in-place rows.

**S0 prerequisites**: none.

**Reviewers**: **[beacon]** (isolation), **[forge]** reviews, prism.

**Independent test / exit**: the view of the block k back equals the state replayed from scratch; reader-versus-move interleavings
are deterministic and never produce a `CorruptNodeException` or a delete; M3 recorded.

### Tests (write first; they must fail)

- [x] T121 [P] Test: the view of the block k back (k = 1, 2, 8, `retained`) equals the state replayed from scratch, position by position, from the anchor through the reverse index (dm 6.1, 6.2). File: `test/db/storage/pathstate/StateOverlayViewSpec.scala`. *(Done, C4a part 2, in `test/db/storage/pathstate/ReverseIndexSpec.scala`: k = 1, 2, 8 and 9 on a chain of nine, against the hash store; and a fork from a block behind the head.)*
- [ ] T122 [P] Test epoch-validated reads (dm 6.3.1): a reader that starts before a move and finishes after it, for a position the move touched, one it did not, and a view older than `max-view-age-moves`; an audit read that meets a moved position is counted as moved and never deleted. File: `test/db/storage/pathstate/EpochValidatedReadSpec.scala`. **[beacon]** *(Part done. C2a pinned the read-then-epoch order for the anchor's view; C4a parts 1 and 2 hold a view over layers and over the reverse index across a move, for a position the move touched, one it did not and one the index answers, and stale past the log (`ForwardLayerSpec`, `ReverseIndexSpec`). The audit read is C5b's.)*
- [ ] T123 [P] Test `StateMove.pre` for a coalesced reverse-plus-forward batch: the pre-batch value of every position in the batch is published (dm 6.3.1). File: `test/db/storage/pathstate/StateMovePreValuesSpec.scala`. *(Open: a coalesced reverse-plus-forward batch is the head move's (C4b). What exists is held: every in-place commit and promotion lists each position it writes, and a view corrected by it reads its state.)*
- [x] T124 [P] Test the budgets (dm 6.4): 32 forward layers and 128 MiB, 16 resident reverse diffs, the 8-move log, one layer above `max-layer-bytes` (WARN, committed in place if it extends the anchor), eviction that spares pinned layers and ancestors of a head candidate, and pinned layers exempt from the count cap up to `pinned-max-bytes`. File: `test/db/storage/pathstate/OverlayBudgetSpec.scala`. *(Done, C4a part 3: `OverlayBudgetSpec`, 11 cases. As built (plan.md, "C4a part 3"): the forward layers no branch pins are 32 and half of `max-bytes`; the reverse pool is whole indexes, bounded in bytes by the other half, and `resident-reverse-blocks` is not read; a layer above `max-layer-bytes` is not kept and is not committed by the store; eviction spares pinned layers and the layers the new one stands on, the head candidates being part 4's to pin.)*
- [x] T125 [P] Test the memory-model ordering of dm 6.3.3 with a deterministic interleaving (the writer's epoch store precedes the native write; a reader that sees new bytes then reads the epoch), and that the reader-writer-lock fallback is a one-class switch. File: `test/db/storage/pathstate/EpochOrderingSpec.scala`. *(Done, C4a part 3c: `EpochOrderingSpec`. The writer's order and what readers of every kind read at each step of a move, by a data source that stops before and after its write; the fallback shown as another `StateClock` under the same view. The memory model's gap stays documented, as dm 6.3.3 has it.)*
- [ ] T126 [P] Test that an in-place overwrite or delete **evicts** the decoded-node cache entry, and that a hit by hash cannot hide a positional hole from the audit's direct read (dm 6.5). File: `test/db/storage/DecodedNodeCachePositionSpec.scala`. *(Not applicable as the code stands, C4a part 3: the position store is given no decoded-node cache. Open with T131.)*
- [x] T127 Concurrency stress (seeded, deterministic scheduler, no sleep): readers, canonical commits and overlay commits for 10,000 steps; every successful read is correct and every failure is a typed `StateFault`. File: `test/db/storage/pathstate/OverlayStressSpec.scala`. *(Done, C4a part 3c: `OverlayStressSpec`, one seed, 10,000 steps, one thread; the reads inside a write are made by the data source. Reads of nodes and of rows, two invariants, and the disk held to its anchor's state at the end (plan.md, "C4a part 3c").)*

### Implementation

- [x] T128 [P] Create `main/db/storage/pathstate/ForwardLayer.scala` (an executed, unpromoted block's frozen diff keyed by hash, idempotent) and `main/db/storage/pathstate/ReverseIndex.scala` (position to prior across the retained blocks, filled lazily from CF `v`) (dm 6.1). *(Done. C4a part 1: `ForwardLayer`, which holds what the block left, the hash of what it found and the owners of the tries it replaced, and is settled against the disk when it is promoted: it does not grow with a storage trie its block destroys (forge; plan.md, "C4a part 1"). C4a part 2: `ReverseIndex`, built by a walk of the anchor's records for each (anchor, fork), with `AncestorWindow` beside it; "filled lazily" with a cache of records is part 3's.)*
- [ ] T129 Create `main/db/storage/pathstate/StateOverlay.scala`: forward layers, resident reverse diffs, the move log, budgets and eviction, **pinned layers** exempt from the count cap (dm 6.4), and the overlay commit (dm 7.2) that C3b stubbed. **[beacon]** *(Part done, C4a part 1: `StateOverlay` holds the forward layers, capped at 32 as a stopgap; `PathStateStorage.commitOverlay` and `promote` (plan.md, "C4a part 1"). Nothing in the node calls them. The reverse diffs are part 2; the callers part 4.)* *(C4a part 3: the budgets, the eviction of the least recently used tip, and pinned branches (`withPinnedBranch`, `BranchTooLarge`). What is left is the callers and the degrade of a staged commit, part 4's.)*
- [ ] T130 Complete `main/db/storage/pathstate/StateView.scala`: lookup by hash then by position (dm 6.2), epoch validation with the stale/corrupt/absent classification, `StateMove` log reads, and view age. **[beacon]** *(Part done, C4a part 1: the forward chain in front of the read, and a fourth class beside stale, corrupt and absent: `LayerInconsistent`. **A view does not list a trie's rows**: a replaced trie is listed at the commit, from the disk, under the lock, in place (T090) and at a promotion alike. C4a part 2: the reverse index behind the layers, three sources of an answer, and `viewOf` for a block behind the anchor or on a fork. What was left was the served-root window, SNAP advertised again and the callers.)* *(C4a part 4: the served-root window is built: `stateForRoot` hands out the state of the canonical block of the window that has the root, through `PathStateStorage.readOnlyBehind`, one rule for the three requests under the guard (plan.md, "C4a part 4"). **Re-scoped (herald): `snap/1` is advertised again, and the spec that a syncing peer is served every root of the window against a live head, in the slice in which the node follows the head (C4d, C4e)**, since until then every root a peer asks about is outside the node's window. `ConfigValidator.stateGuardErrors` keeps its refusal of a SNAP sync, with the reason it now has (the hand-off is C5a), and goes with C5a. The callers of a block that is executed and not adopted are C4b, C4d and C4e.)* *(C2a part 2 built the view of the anchor, the `StateClock` seam and the stale, corrupt and absent classification in `PathMptStorage`; this task adds the forward chain and the reverse index behind the same `read`.)* **Added by C2b's serving PR (T058):** with views of the blocks behind the anchor, `BlockchainReader.stateForRoot(root)` hands SNAP serving the view of the canonical block in the last `min(128, the retention floor)` whose state root is asked for (data-model 6.4; today it serves the anchor's root alone and the window is a flat 128), and `InstanceConfig.supportedCapabilities` advertises `snap/1` again under the guard (`snapServed`), with the spec that a syncing peer is served every root of the window; `ConfigValidator.stateGuardErrors` (no SNAP sync under the guard) is deleted with it. The window and `isStateRootFresh` (canonical roots, `GetAccountRange` only) become one rule for the three requests. **[herald]**
- [ ] T131 [P] Update `main/db/storage/DecodedNodeCache.scala` so an in-place overwrite or delete evicts its entry and `putIfCurrent` keeps its rule (dm 6.5). `PathPrefetchReaderSpec` already holds the reader to it: an insert that raced an eviction is dropped. The in-place commit must evict **after** it writes (beacon, C2a part 2). *(Not applicable as the code stands, C4a part 3: `PathStagedBlock.prefetchReader` passes no cache and `PathMptStorage` has none. When one is put in front of the position store, the in-place commit evicts the hashes of the nodes it replaces or removes, after its write.)*
- [x] T132 [P] Add the overlay metrics of dm 11 (`overlay.forward.layers`, `.bytes`, `overlay.reverse.resident`, `.bytes`, `overlay.evictions`, `overlay.pinned.layers`, `.bytes`, `view.stale`, `view.retries`, `move.seconds`) in `main/db/storage/pathstate/StateMetrics.scala`. *(Done, C4a part 3, as `db.state.overlay.forward.layers` and `.bytes`, `.pinned.layers` and `.bytes`, `.reverse.resident` and `.bytes`, `.evictions`, `db.state.view.retries` and `.stale` (the views that were made again or could not be made). `move.seconds` comes with the head move (C4b), and the stale reads of a view that was made with their callers (part 4).)*
- [x] T133 Run `sbt compile-all`, `sbt "testOnly *StateOverlay* *EpochValidated* *StateMovePre* *OverlayBudget* *EpochOrdering* *DecodedNodeCachePosition* *OverlayStress*"`, `sbt scalafmtAll`. The specs are the files named in this phase's tests, under `src/test/scala/` and `src/it/scala/`. *(Done with C4a parts 1 to 3c, under the names the specs have: `ForwardLayerSpec`, `ReverseIndexSpec`, `OverlayBudgetSpec`, `EpochOrderingSpec`, `OverlayStressSpec`; `StateViewSpec` holds the moves' priors; T126 does not apply.)*
- [x] T134 **M3**: measure JVM bytes per diff entry with JOL on a populated overlay, converting M1's bytes to heap. Record in `spec/baselines/m3-jol.md` and set the overlay defaults of dm 10 from it (the budget stays a guess until M1, M2). *(Done in the small, C4a part 3c: `baselines/m3-layer-heap.md` (not `spec/baselines/m3-jol.md`: a heap difference, JOL not being a dependency). The count is no less than the heap; the defaults stay until M1 and M2.)*
- [ ] T135 Record the DC-14 figures in `spec/baselines/dc14-C4a.md` (EG-8), then open the C4a PR; request `beacon`, `forge`, `prism`. *(C4a went up in parts, each its own PR with its own reads: #1806, #1807, #1808, #1809, #1811 (part 4, SNAP), #1813 (part 4b-1, what a reader may cost) and part 4b-2 (the JSON-RPC and GraphQL behind the head; plan.md, "C4a part 4b-2"). What is left of this task is the DC-14 note.)*

**Checkpoint**: non-head state is readable and bounded; nothing moves the head yet.

---

## Phase 9: C4b, the move core (item 6d)

**Goal**: a head move is planned from the anchor's own records, never the canonical index, and applied in one atomic batch that also
carries the index and the best pointer; the pointer writers are audited; reverse depth, not branch length, is what a refusal
measures. Consensus-critical.

**S0 prerequisites** (Satisfied, verified at this PR): forge item 3 (a) `saveLastBlock` and stop at the first non-extending commit
(dm 7.1) and (b), with beacon item 3 (i), the reverse-depth refusal and the `reorg.max-depth` default (dm 6.4, 10).

**Reviewers**: **[beacon] [forge] before code**; eye.

**Independent test / exit**: the reorg matrix cases of plan "Test matrices" for C4b pass; I1 rules 1 to 4 and I6 hold after every
injected crash; best always equals the anchor after a degraded commit.

### Tests (write first; they must fail)

- [ ] T136 [P] Test planning from the anchor: a plan is correct when the canonical index is ahead of the anchor (`newPayload` wrote above best) and when it is behind (a side-branch `saveBatch` wrote below best); the reverse set is the chain of the anchor's records following `parentHash`, the forward set is the target's headers, the meeting point is found by hash (dm 7.0, 7.3). File: `test/db/storage/pathstate/HeadMovePlanSpec.scala`. **[forge] [beacon]**
- [ ] T137 [P] Test atomicity: one batch contains the state rows, the record changes, the canonical index, the best pointer and the anchor (`HeadMovePrepared` is `.and`ed into `promoteToCanonicalHead` and `rewriteCanonicalIndex` before the commit); an injected failure leaves none of them. File: `test/domain/BlockchainWriterHeadMoveSpec.scala`.
- [ ] T138 [P] Test chunked deep moves: a move of more than `state.move.max-blocks-per-batch` blocks runs as a chain of batches each leaving `anchor == best` on an ancestor of the target; kill after each chunk recovers by I1 rules 2 and 3 (dm 4.6, 5.1, 7.3). File: `test/db/storage/pathstate/ChunkedMoveSpec.scala`.
- [ ] T139 [P] Test startup reconciliation I1 rules 1 to 4 (equal; best an ancestor within the retained blocks with complete records, reverse-apply; best a descendant, forward-apply; otherwise rewind the pointer and the index to the anchor with the `[STATE-ANCHOR]` WARN) and I6 (the canonical index from best down to the retained floor equals best's header ancestry). Rule 4 is a startup rule only. File: `test/db/storage/pathstate/StateReconciliationSpec.scala`.
- [ ] T140 [P] Turn the **writer table** (dm Part 2 A, at least ten pointer writers) into a test: each writer carries a state move in its batch, or is a pointer-only hint that cannot move best past the anchor, or falls under the startup rules. File: `test/domain/BestPointerWritersSpec.scala`.
- [ ] T141 [P] Test the sampled post-move check: an injected interior fault (a wrong row in the middle of a reversed range) is caught and reported as `[STATE-MOVE]` ERROR. File: `test/db/storage/pathstate/PostMoveCheckSpec.scala`.
- [ ] T142 [P] Test forge item 3 (a): in a 5-block `importToTop` batch a competing head move between blocks 2 and 3 leaves best **equal to the anchor**, commits blocks 1 and 2, reports `ConsensusStateFault` for 3 to 5 (retryable), and `saveLastBlock` writes nothing on a path-live store (dm 7.1, `ConsensusImpl.scala:286-291`). File: `test/consensus/ConsensusImportToTopDegradeSpec.scala`. **[forge]**
- [ ] T143 [P] Test the **reverse-depth refusal** (forge 3 (b), beacon 3 (i)): a 300-block branch forking at `best - 2` is planned; a 3-block branch forking at `best - retained - 1` is refused `ReorgTooDeep(Deep)` with the pointer unmoved; a reorg at the finalized lag (about 90 behind a consensus client) is **not** refused; `reorg.max-depth = floor` follows the retention floor; a fork below the SNAP pivot is `Pivot`; a tombstoned block in the reverse set is `Incomplete`. File: `test/db/storage/pathstate/ReorgDepthRefusalSpec.scala`. **[forge] [beacon]**
- [ ] T144 [P] Test the result mapping: `ReorgTooDeep`, `StateUnavailable` and `BranchTooLarge` become `ConsensusStateFault`, recognised by `Consensus.isLocalStateFault`, so the serving peer is not blacklisted and `reportIfProvenInvalid` is never reached. File: `test/consensus/ConsensusStateFaultSpec.scala`. **[forge]**
- [ ] T145 Extend the deterministic interleaving test of C3b with `moveHead` as a participant (the importer commit, `moveHead`, `newPayload` on `view(head)`, a builder view), every order the scheduler can produce, final position map equal to a from-scratch replay. File: `test/db/storage/pathstate/StateLockInterleavingSpec.scala`. **[beacon]**

### Implementation

- [ ] T146 Create `main/db/storage/pathstate/HeadMove.scala`: `moveHead(target)` planned from the anchor's records, the compare-and-set and up to 3 re-plans, chunking (`max-blocks-per-batch`), coalesced batches with `StateMove.pre` values, `HeadMovePrepared`, and the refusals `Deep`, `Pivot`, `Incomplete` on **reverse depth** (dm 6.4, 7.0, 7.3). **[forge] [beacon]**
- [ ] T147 Change `main/domain/BlockchainWriter.scala` so `promoteToCanonicalHead` (the fork walk at `:255-273` is replaced by the plan), `rewriteCanonicalIndex` and `setCanonicalChainHead` (`:185-190`) take the `HeadMovePrepared` and put its batch into the same commit as the index and pointer writes (dm 7.3). **[vault]**
- [ ] T148 Add the `ConsensusStateFault(fault)` results in `main/consensus/Consensus.scala`, route them through `main/consensus/ReorgStateHandler.scala` and `main/consensus/ConsensusImpl.scala`, and extend `Consensus.isLocalStateFault` use at `main/blockchain/sync/regular/BlockImporter.scala` (`:875,944,1062`); never produce `ConsensusErrorDueToMissingNode` or `BranchExecutionFailure` for a `StateFault` (dm 6.3.2, C.1). **[forge]**
- [ ] T149 Make `saveLastBlock` (`main/consensus/ConsensusImpl.scala:286-291`, called at `:248,255,265`) write nothing on a path-live store, and make `executeAndValidateBlocks` stop at the first commit that no longer extends the anchor (dm 7.1). **[forge]**
- [ ] T150 Create `main/db/storage/pathstate/StateReconciliation.scala` (I1 rules 1 to 4, I6 index check) and call it from `main/nodebuilder/NodeBuilder.scala` before any actor starts; rule 4 never runs at runtime (dm 5.1). **[vault]**
- [ ] T151 [P] Add the sampled post-move check and the move metrics (`reorg.refused`, `move.seconds`, `anchor.lag.blocks`) in `main/db/storage/pathstate/HeadMove.scala` and `StateMetrics.scala`.
- [ ] T152 Run `sbt compile` between files (core domain sweep: `Block`, `BlockchainWriter`), `sbt compile-all` once at the end, `sbt "testOnly *HeadMove* *BlockchainWriterHeadMove* *ChunkedMove* *StateReconciliation* *BestPointerWriters* *PostMoveCheck* *ImportToTopDegrade* *ReorgDepthRefusal* *ConsensusStateFault* *StateLockInterleaving*"`, `sbt scalafmtAll`. The specs are the files named in this phase's tests, under `src/test/scala/` and `src/it/scala/`.
- [ ] T153 **M6**: on a local devnet with forced reorgs measure `moveHead` latency for 1, 2, 8 and 32 blocks and the `forkchoiceUpdated` p99; record in `spec/baselines/m6-movehead.md` (the promotion latency budget; reported again in EG-15 on Platåberget).
- [ ] T154 Record the DC-14 figures in `spec/baselines/dc14-C4b.md` (EG-8), then open the C4b PR; request `beacon`, `forge`, `eye`. The PoS and PoW arms that use it are C4d and C4e.

**Checkpoint**: heads move atomically from the anchor's records; nothing yet drives a move from the Engine API or a PoW reorg.

---

## Phase 10: C4d, the PoS arms (item 6f)

**Goal**: the Engine API claims exactly what is true after a crash and otherwise; `forkchoiceUpdated` moves the head in the right
order; a block executed but not promoted is recovered locally with no peer; retention follows the finality lag; and a refused move
escalates, once, to a re-SNAP without bricking the datadir. Consensus-critical.

**S0 prerequisites** (Satisfied, verified at this PR): beacon item 3 (i) and the escalation (a) to (c): the direct
`StateEscalationRequested` message, hysteresis, the ordered clear and `state.reset-pending` (dm Part 2 D); forge R2.3 loop guard and
cool-down.

**Reviewers**: **[beacon] before code**; **[forge]** reviews the shared `ConsensusImpl`; eye.

**Independent test / exit**: kill -9 between `newPayload` and `forkchoiceUpdated`, restart with no peers, and the consensus client
reaches VALID with no resync; each escalation trigger is induced and recovers unattended; the fault-injection runs never produce
INVALID.

### Tests (write first; they must fail)

- [ ] T155 [P] Test the **Engine API claims table** (dm Part 2 D): header and receipts already stored gives VALID; the parent stored with its state unavailable gives ACCEPTED and schedules local re-execution; a head with state and a successful move gives VALID; a stored head without state gives SYNCING and schedules re-execution; a refused move gives SYNCING; INVALID only when `InvalidChainReporter.provesConsensusInvalid` holds and never for a `StateFault`. File: `test/consensus/engine/EngineApiClaimsSpec.scala`. **[beacon]**
- [ ] T156 [P] Crash test in a child JVM on real RocksDB (as spec 022's `HealCrashSafetyItSpec`): `kill -9` between `newPayload(B)` and `forkchoiceUpdated(B)`, restart with **no peers**; `newPayload(B+1)` answers ACCEPTED (not VALID, not INVALID), local re-execution runs, and the consensus client reaches VALID with no resync. File: `it/consensus/engine/CrashBetweenNewPayloadAndFcuItSpec.scala`. **[beacon]**
- [ ] T157 [P] Test FCU ordering: a refused move leaves `currentState`, safe and finalized state, the payload id and `txGossipGate` unchanged; `currentState` is set only after a successful move; FCU with attributes racing a move (dm 7.3). File: `test/consensus/engine/ForkChoiceRefusedSpec.scala`. **[beacon]**
- [ ] T158 [P] Test the INVALID path: an `INVALID_REQUESTS` payload that executed then failed drops its layer (`StateStorage.dropLayer`) and its `BlockImportGate` memo (`BlockImportGate.forget`), so a re-import does not reuse it (dm 7.2). File: `test/consensus/engine/InvalidPayloadDropLayerSpec.scala`.
- [ ] T159 [P] Test the designated-head arm (`!selectedByWeight`) as reverse-to-fork then a canonical commit per block with no N-layer stack, for a branch **longer than `max-forward-layers`**; and a side-payload chain longer than the budget followed by `forkchoiceUpdated` (dm 6.4). File: `test/consensus/DesignatedHeadArmSpec.scala`. **[beacon]**
- [ ] T160 [P] Test finality-aware retention (dm 4.5): retained = clamp(max(history, head - finalized + 16), history, 512); a reorg at the finalized lag (about 90) on a consensus-driven chain is not refused; `archive` is unbounded and chunked. File: `test/db/storage/pathstate/RetentionFloorSpec.scala`.
- [ ] T161 [P] Test `StateRecovery`: re-execution of stored blocks oldest first on a **bounded dispatcher, never inline**, one task per head, as overlay commits; a chain longer than `state.recovery.max-blocks` or a missing body escalates; the caller answers ACCEPTED or SYNCING meanwhile (dm Part 2 D). File: `test/db/storage/pathstate/StateRecoverySpec.scala`.
- [ ] T162 [P] Fault-injection matrix: inject `StaleStateViewException`, `StateUnavailable`, `ReorgTooDeep`, `BranchTooLarge` and a `CorruptNodeException` into `newPayload`, `forkchoiceUpdated`, the importer, the PoW consensus path and the builder; assert never INVALID, never `markInvalidRecursive`, never `reportInvalid`, never a delete, never a provider blacklisted (dm 6.3.2). File: `test/consensus/StateFaultInjectionSpec.scala`.

- [ ] T163 [P] Test the escalation **trigger and hysteresis**: 63 consecutive `forkchoiceUpdated` refusals of one kind do not escalate and the 64th does; any successful move resets the count; a flip-flopping consensus client costs nothing (dm Part 2 D, part 1). File: `test/blockchain/sync/EscalationTriggerSpec.scala`. **[beacon]**
- [ ] T164 [P] Test the escalation **guards**: a second trigger inside `escalation-cooldown` does not escalate and counts `escalations.suppressed`, and the cool-down survives a restart (`state.escalation.last`); a branch that caused an escalation and is offered again after the re-SNAP (`ReorgTooDeep(Pivot)`, pivot not taken from it) is dropped at ERROR and never escalates again. File: `test/blockchain/sync/EscalationGuardSpec.scala`. **[forge]**
- [ ] T165 [P] Test the **ordered clear** with `kill -9` at every step in a child JVM: the one synced batch (`clearSnapSyncDone`, anchor delete, `state.escalation.last`, `state.reset-pending`), then `deleteRange` of CF `v`, `t`, `u`, then removal of `state.reset-pending`; every kill point recovers to a clean SNAP start; the anchor is never deleted before `SnapSyncDone` is cleared (a recovered "`SnapSyncDone` and no anchor" is refused as corruption); a plain mid-SNAP restart without the key keeps its rows. File: `it/blockchain/sync/EscalationCrashItSpec.scala`. **[beacon] [vault]**
- [ ] T166 [P] Test that healing after an escalation over a tombstone-skipped dead trie meets a node with the right hash over a changed child and ends in an absence the audit repairs, never a wrong root (forge R2.3 note 3). File: `test/blockchain/sync/snap/HealOverTombstoneSpec.scala`. **[forge]**

### Implementation

- [ ] T167 Change `main/consensus/engine/EngineApiService.scala`: `parentValidated` becomes `stateAvailable(parent)` (was "receipts stored", `:588-591`); the claims table; the existing not-executed ACCEPTED arm (`:706-708,727-743`); `dropLayer` and `BlockImportGate.forget` on a post-execution INVALID (`:618-631`); a stale view during a build retries and never reaches the empty-payload fallback; send `StateEscalationRequested(reason)` after `escalation-refusals` consecutive refusals. **[beacon]**
- [ ] T168 Change `main/consensus/engine/ForkChoiceManager.scala` and the FCU path: call `moveHead` first, set `currentState` only after success, leave `txGossipGate` and the payload id untouched on refusal, return SYNCING with the escalation counter (dm 7.3). **[beacon]**
- [ ] T169 Create `main/db/storage/pathstate/StateRecovery.scala`: triggered by `newPayload` or `forkchoiceUpdated` meeting a stored block whose state is unavailable (and by `reorganise` meeting a lost `fork.parentSideChain` layer, C4e); bounded dispatcher; one task per head; overlay commits only (dm Part 2 D).
- [ ] T170 Change the designated-head arm of `main/consensus/ConsensusImpl.scala` (`:453-460`) to reverse-to-fork then a canonical commit per block (dm 6.4). **[beacon] [forge]**
- [ ] T171 Make retention finality-aware in `main/db/storage/pathstate/RetentionPolicy.scala`: read the finalized block from `ForkChoiceManager`, apply the clamp of dm 4.5 and 10.
- [ ] T172 Add to `main/db/storage/AppStateStorage.scala` the keys `state.escalation.last` (time and causing head hash) and `state.reset-pending`, and one method that does `clearSnapSyncDone()` (also removing `SnapHealInvariant` and `SnapAuditCleanAnchor`, `:124-125`), deletes `state.anchor` and writes both keys in a single `updateSync` batch. **[vault]**
- [ ] T173 Create `main/db/storage/pathstate/StateEscalation.scala` (under the state lock: clear forward and pinned layers, the move log, the `BlockImportGate` memo and pending `StateRecovery` tasks; run the single batch above; `deleteRange` CF `v`, `t`, `u`; remove `state.reset-pending`; a start that finds the key repeats the `deleteRange` calls) and handle `StateEscalationRequested` in `main/blockchain/sync/SyncController.scala` beside `RegularSyncStuck` (`:904-920`), applying the cool-down and the loop guard and logging `[STATE-REORG-TOO-DEEP]`. **[beacon] [vault]**
- [ ] T174 [P] Add the metrics `app.db.state.recovery.reexecuted.blocks`, `recovery.escalations` and `recovery.escalations.suppressed` in `main/db/storage/pathstate/StateMetrics.scala`.
- [ ] T175 Run `sbt compile-all`, `sbt "testOnly *EngineApiClaims* *ForkChoiceRefused* *InvalidPayloadDropLayer* *DesignatedHeadArm* *RetentionFloor* *StateRecovery* *StateFaultInjection* *Escalation* *HealOverTombstone*"`, `sbt "IntegrationTest / testOnly *CrashBetweenNewPayloadAndFcu* *EscalationCrash*"`, `sbt scalafmtAll`, and the hive Engine API suites against the baselines of C0.
- [ ] T176 Record the DC-14 figures in `spec/baselines/dc14-C4d.md` (EG-8), then open the C4d PR; request `beacon` (before code and at review), `forge`, `eye`.

**Checkpoint**: the PoS node recovers from a crash with no peers and from a refused move with one bounded re-SNAP.

---

## Phase 11: C4e, the PoW arms (item 6g)

**Goal**: ETC's `reorganise` runs on pinned branch layers and is refused on reverse depth, not length; the `SYNC-FORK` rewind is
clamped; lost side-branch layers are re-executed locally; PoW refusals map to retryable results and escalate through
`RegularSyncStuck` with the cool-down and loop guard; `archive` moves are unbounded and chunked while block generation waits.
Serves US6. Consensus-critical.

**S0 prerequisites** (Satisfied, verified at this PR): forge item 3 (b), (c) and (d): reverse-depth refusal, loop guard and
cool-down, generation pause (dm 6.4, 7.3, Part 2 D).

**Reviewers**: **[forge] before code**; **[beacon]** reviews the shared `ConsensusImpl`; eye.

**Independent test / exit**: a heavier branch of 33, 50, 64 and 300 blocks is adopted; REORG-PARTIAL keeps the old head and re-uses
layers; the `SYNC-FORK` rewind of 128 against a reach of 64 is clamped and escalates; archive moves of thousands of blocks run in
chunks, are interrupted and recover.

### Tests (write first; they must fail)

- [ ] T177 [P] [US6] Test `reorganise` on pinned layers: a heavier branch of 33, 50 and 64 blocks is adopted (all layers live until `settleHead`, then released), and a **300-block branch forking at `best - 2`** is adopted (a shallow fork is never refused on length); a branch above `state.overlay.pinned-max-bytes` raises the retryable `BranchTooLarge`, never an invalid block (dm 6.4). File: `test/consensus/ReorganisePinnedBranchSpec.scala`. **[forge]**
- [ ] T178 [P] [US6] Test the reverse-depth refusal on the PoW arm: a fork deeper than the retained depth is refused `Deep` with the pointer unmoved and the abandoned branch's blocks still stored (dm 6.4). File: `test/consensus/ReorganiseDepthRefusalSpec.scala`. **[forge]**
- [ ] T179 [P] [US6] Test `REORG-PARTIAL` (`ConsensusImpl.scala:477-499`): an executed prefix that does not outweigh the head leaves the old head and its state untouched, the side blocks are re-enqueued (`ConsensusAdapter.scala:171-173`) with their layers kept for re-use and not the first evicted, and `abandonBlockStates(sideBlocks)` (`:499`) is a no-op. File: `test/consensus/ReorgPartialLayersSpec.scala`. **[forge]**
- [ ] T180 [P] [US6] Test `fork.parentSideChain` with a lost layer (evicted or lost at a restart): the stored side blocks are re-executed by `StateRecovery` before the move is planned (`locateFork`, `:509-525`). File: `test/consensus/ParentSideChainRecoverySpec.scala`. **[forge]**
- [ ] T181 [P] [US6] Test the `SYNC-FORK` rewind: the blind rewind of `MaxForkAncestryDepth = 128` against a reach of 64 is clamped to the reach; an LCA beyond the reach raises `RegularSyncStuck`, logs `[STATE-REORG-TOO-DEEP]` and never applies I1 rule 4 at runtime (`BlockImporter.scala:84,127-140,1270-1333`). File: `test/blockchain/sync/regular/SyncForkRewindClampSpec.scala`. **[forge]**
- [ ] T182 [P] [US6] Test the PoW result mapping and the escalation guards: a second stale failure and every `StateFault` map to a retryable `ConsensusStateFault`, never `reportIfProvenInvalid`; the PoW escalation through `RegularSyncStuck` honours the cool-down and the loop guard of C4d. File: `test/consensus/PowStateFaultMappingSpec.scala`. **[forge]**
- [ ] T183 [P] [US6] Test chunked `archive` moves: a reorg of several thousand blocks (ETC's 2019 and 2020 attacks reorganised about 3,700 to 7,000) runs in chunks of `max-blocks-per-batch`, is killed after a chunk and recovers by I1 rules 2 and 3; only views are bounded by `state.archive.view-reach`. File: `test/db/storage/pathstate/ArchiveDeepMoveSpec.scala`. **[forge]**
- [ ] T184 [P] [US6] Test the **generation pause** (forge item 3 (d)): during a chunked move `MockedMiner`, the Ethash miner coordinator and the Engine API builder do not build on an intermediate ancestor; a build that starts inside the pause waits and does not fail (deterministic scheduler). File: `test/consensus/MoveGenerationPauseSpec.scala`. **[forge] [beacon]**
- [ ] T185 [P] [US6] Test the routine case: a depth-1 and a depth-2 ETC fork is one head move of one or two blocks, one batch, with no re-execution because the new branch was executed first. File: `test/consensus/RoutineEtcForkSpec.scala`.

### Implementation

- [ ] T186 [US6] Change `reorganise` in `main/consensus/ConsensusImpl.scala` (`:298-362`): `adoptEachBlock = false` blocks become overlay commits whose layers are **pinned** until `settleHead` or the first failure; the heavier arm (`:465-471`) passes the `HeadMovePrepared` to `rewriteCanonicalIndex`; REORG-PARTIAL reuses layers; `abandonBlockStates` at `:499` becomes a no-op; refuse up front only on reverse depth (dm 6.4, C.1). **[forge]**
- [ ] T187 Change `locateFork`/`fork.parentSideChain` handling (`main/consensus/ConsensusImpl.scala:509-525`) to call `StateRecovery` for a side block with neither layer nor record, and keep re-enqueued side blocks' layers (`main/consensus/ConsensusAdapter.scala:171-173,203-206`). **[forge]**
- [ ] T188 Clamp the `SYNC-FORK` blind rewind to the current reach in `main/blockchain/sync/regular/BlockImporter.scala` (`rewindCanonicalChain`, `MaxForkAncestryDepth`, `:84,127-140,1313`) and escalate through `RegularSyncStuck` (`:1330`) when the LCA is beyond the reach; handle the queue-side admission in `main/ledger/BlockQueue.scala` (branches rooted at any block with a stored chain weight, `:66-73`). **[forge]**
- [ ] T189 Complete pinning in `main/db/storage/pathstate/StateOverlay.scala` (exempt from the count cap up to `pinned-max-bytes`, release at `settleHead` or first failure, `BranchTooLarge` above the bytes) and record the recorded-not-default reverse-then-commit fallback as a documented switch in `ConsensusImpl.scala` (dm 6.4).
- [ ] T190 Add the generation-pause hook: `main/db/storage/pathstate/StateLock.scala` exposes "a chunked move is running"; `main/consensus/pow/miners/MockedMiner.scala`, the Ethash miner coordinator, `main/consensus/pow/blocks/PoWBlockGenerator.scala` and the builder in `main/consensus/engine/EngineApiService.scala` wait on it (dm 7.3). **[forge]**
- [ ] T191 [P] Instrument M13: log and count the length (in blocks) and layer bytes of every branch `reorganise` settles, and the longest BlockQueue branch seen, in `main/consensus/ConsensusImpl.scala` (read in C6 and C7).
- [ ] T192 Run `sbt compile-all`, `sbt "testOnly *ReorganisePinned* *ReorganiseDepth* *ReorgPartial* *ParentSideChain* *SyncForkRewind* *PowStateFault* *ArchiveDeepMove* *MoveGenerationPause* *RoutineEtcFork*"`, `sbt scalafmtAll`, and `./scripts/fukuii-test essential` once.
- [ ] T193 Record the DC-14 figures in `spec/baselines/dc14-C4e.md` (EG-8), then open the C4e PR; request `forge` (before code and at review), `beacon`, `eye`. Rollback if a heavier branch cannot be adopted: switch the PoW arm to reverse-then-commit with rollback (dm 6.4).

**Checkpoint**: ETC's reorganisation runs on the overlay and the move core; the rest of the ETC work is C6.

---

## Phase 12: C5a, hand-off without a copy, the layout guard and the ancestor window (item 10, hand-off)

**Goal**: SNAP finishes on either chain by one synced finalization commit that carries the anchor, with no `PathToHashExporter`
publish; the layout guard refuses every datadir that is not empty or marked; and the 256 headers and 6 bodies below the pivot are a
hand-off precondition (BLOCKHASH and ommers work from the first block). Serves US3.

**S0 prerequisites** (Satisfied, verified at this PR): forge item 7, the symmetric flag-off refusal (dm 5.3).

**Reviewers**: **[beacon]**; **[forge]** (ETC SNAP wiring, ancestor window); eye; the spec 016 ratchet stays green; prism.

**Independent test / exit**: a SNAP run on Mordor or a seeded store hands off with no copy; a kill -9 right after the synced
finalization commit leaves the rows, the anchor and the pointer; the guard table passes; `scripts/snap-split/verify.sh` is green.

### Tests (write first; they must fail)

- [ ] T194 [P] [US3] Test the **guard table** (dm 5.3): an empty datadir is stamped with `state.layout = path-live-1` and proceeds; a marked datadir proceeds; any other marker value is refused; any non-empty unmarked datadir (finished SNAP, mid-SNAP, mid-copy, full sync, either chain) is refused with the resync message; **a marked datadir with the flag off and an unmarked one with the flag on are both refused**; "empty" means no best-block info and no header at height 0. File: `test/db/storage/StateLayoutGuardSpec.scala`. **[forge] [beacon]**
- [ ] T195 [P] [US3] Finalization crash matrix in a child JVM on real RocksDB: kill -9 before and after the synced finalization commit; after it the rows, the anchor, `SnapSyncDone` and the best pointer are all present, before it none of `SnapSyncDone` and the anchor is (dm 7.4). File: `it/blockchain/sync/snap/FinalizationCrashItSpec.scala`. **[vault]**
- [ ] T196 [P] [US3] Port `test/blockchain/sync/snap/PathToHashExportCheckSpec.scala` to the **hand-off sample** on a seeded position store: a sampled walk finds an injected wrong row (spec 022 D9). File: `test/blockchain/sync/snap/PathHandoffSampleSpec.scala`.
- [ ] T197 [P] [US3] Test the genesis clear (dm 5.4): at SNAP start with `anchor == genesis`, `t` and `u` are cleared, the anchor is deleted and `HealInvariant.isEmpty` then holds; the step is idempotent and a kill in the middle repeats it. File: `test/blockchain/sync/snap/GenesisClearAtSnapStartSpec.scala`.
- [ ] T198 [P] [US3] Test the **ancestor window**: after a hand-off at pivot P, BLOCKHASH answers for the 256 blocks below P and an ommer whose parent is below P validates (ETC variant with a 6-deep ommer); `ensure` holds the hand-off until the window is present and times out with a clear log, never publishes without it. File: `test/ledger/AncestorWindowSpec.scala`. **[forge] [beacon]**
- [ ] T199 [P] [US3] Extend the durability test for both pruning modes: `StateIngestDurabilitySpec` asserts the write order under `basic` and `archive`. File: `test/blockchain/sync/snap/controller/StateIngestDurabilitySpec.scala`. **[vault]**
- [ ] T200 [P] Test that `pruning.mode = "inmemory"` is **rejected at start on both chains** with a message naming the retired mode and the two that remain, with no deprecation window. File: `test/nodebuilder/PruningModeRejectionSpec.scala`. **Landed early by #1692** (`PruningConfigSpec`, at the config read): this test only adds the start-up check through `NodeBuilder`, on both chains.

### Implementation

- [ ] T201 Change `main/blockchain/sync/snap/controller/SnapFinalization.scala`: hand-off without a publish on both chains (guard on): one synced commit with the rows already in place, `state.anchor = (pivotHash, number, stateRoot)`, `SnapSyncDone` and the best pointer; no `BlockDiff` for the pivot; no `PathPublish*`. **[vault] [beacon] [forge]**
- [ ] T202 [P] Create `main/db/storage/PathHandoffSample.scala` (the spec 022 D9 sample, ported from `main/blockchain/sync/snap/PathToHashExporter.scala`, which stays until C8a).
- [ ] T203 Create `main/db/storage/StateLayoutGuard.scala` (one marker, one rule, both flag directions) and call it in `main/nodebuilder/NodeBuilder.scala` where `storagesInstance` is built, **before any actor starts and before `GenesisDataLoader`** (dm 5.3). The existing narrower scheme guards (`SyncController.scala:476-480`, `SnapResumePlanner.scala:105-115`) are deleted in C8a/C8b, not here. **[vault]**
- [ ] T204 Create `main/db/storage/AncestorWindow.scala` (`ensure`: 256 headers below the pivot and the bodies of the 6 below it, taken from the chain downloader's coverage or fetched) and make it a precondition of the finalization commit in `SnapFinalization.scala`. **[forge]**
- [ ] T205 Change `main/blockchain/sync/snap/heal/HealInvariant.scala` (`atSnapStart`, `isEmpty` at `:76-95`): when `anchor == genesis`, clear `t` and `u` (two `deleteRange`), delete the anchor, then evaluate `isEmpty` (dm 5.4).
- [ ] T206 [P] Reject `pruning.mode = "inmemory"` at start on both chains in `main/nodebuilder/NodeBuilder.scala` (config check at the pruning mode read, `main/db/storage/pruning/package.scala`) with the dm 4.6 message. **Landed early by #1692** in `PruningConfig.apply` (`main/utils/Config.scala`), which `NodeBuilder` calls at the pruning mode read: nothing left but the dm 4.6 wording, if it differs.
- [ ] T207 **M12**: measure the cost of the ancestor window (256 headers, 6 bodies) at hand-off, with and without the chain downloader's coverage, on Mordor and Sepolia (ETC mainnet joins in C7). Record in `spec/baselines/m12-ancestor-window.md`; it bounds the hold time of `AncestorWindow.ensure`.
- [ ] T208 Run `sbt compile-all`, `sbt "testOnly *StateLayoutGuard* *PathHandoffSample* *GenesisClearAtSnapStart* *AncestorWindow* *StateIngestDurability* *PruningModeRejection*"`, `sbt "IntegrationTest / testOnly *FinalizationCrash*"`, `sbt scalafmtAll`, and `scripts/snap-split/verify.sh --branch <branch> origin/staging HEAD`.
- [ ] T209 Record the DC-14 figures in `spec/baselines/dc14-C5a.md` (EG-8), then open the C5a PR; request `beacon`, `forge`, `eye`, `prism`.

**Checkpoint**: a SNAP-synced node hands off without a copy and opens only on a marked layout.

---

## Phase 13: C5b, the audit, on-demand fetch and recovery scans by position (item 10, audit and recovery)

**Goal**: every repair of the position store is position-scoped, verified and taken under the state lock, so a stale read never
deletes a good node; the importer's missing-node machinery fills holes by position; the recovery scans give the pinned jar's
verdicts. Serves US3.

**S0 prerequisites**: none.

**Reviewers**: **[beacon]**; **[forge]** (the PoW importer path); eye.

**Independent test / exit**: the audit finds injected absent, bad-keccak and moved positions and deletes none of the good ones; a hole
mid-import is filled by a position fetch on both the ETH and the PoW importer; both `saveNode` sites are `repairNodeAt`.

### Tests (write first; they must fail)

- [ ] T210 [P] [US3] Test the audit over a store with injected **absent**, **bad-keccak** and **moved** positions: each is classified, a moved position is counted and never deleted, and the first pass over a clean store records its time. File: `test/blockchain/sync/snap/heal/AuditWalkPositionSpec.scala`. **[beacon]**
- [ ] T211 [P] [US3] Test that a **stale read never deletes** and that a decodable node which differs from the expected one is **never deleted** (only a node whose hash mismatches and which is still reached at its position is deleted, via a real `stillReached`) (dm 7.5). File: `test/db/storage/pathstate/DeleteBadNodeAtSpec.scala`. **[beacon] [vault]**
- [ ] T212 [P] [US3] Test on-demand fetch by position: a hole in `t`/`u` met mid-import is fetched and written by `repairNodeAt` under the state lock and the anchor compare-and-set, on the ETH importer path and on the PoW importer path; a repair declines when the anchor moved (dm 7.0, 7.5). File: `it/blockchain/sync/regular/OnDemandFetchByPositionSpec.scala`. **[beacon] [forge]**
- [ ] T213 [P] Test that both `stateStorage.saveNode` call sites of the importer (`BlockImporter.scala:485` and `:552`) now call `repairNodeAt` and that nothing else calls `saveNode`. File: `test/blockchain/sync/regular/BlockImporterRepairSitesSpec.scala`. **[forge]** After #1692 both sites call `repairNode` (`RegularSyncSpec` "save fetched node" asserts it) and `saveNode` has no importer caller, so this test is the rename to `repairNodeAt`.
- [ ] T214 [P] [US3] Test that the recovery scans (`StorageRecoveryActor`, `BytecodeRecoveryActor`, `CombinedRecoveryScanner`) return the pinned jar's verdicts on a seeded store with injected gaps. File: `test/blockchain/sync/RecoveryScansVerdictSpec.scala`.
- [ ] T215 [P] Test the positional-hole caveat (dm 6.5): a cached node hides a hole from execution but the audit's direct read still finds it. File: `test/blockchain/sync/snap/heal/PositionalHoleAuditSpec.scala`.

### Implementation

- [ ] T216 Add `repairNodeAt` and `deleteBadNodeAt` (with a real `stillReached` against the anchor's root, under the state lock with the compare-and-set; declined when the anchor moved) to `main/db/storage/pathstate/PathStateStorage.scala` (dm 7.5). **[vault]**
- [ ] T217 Convert the audit: `main/blockchain/sync/BackgroundAudit.scala` and `main/blockchain/sync/snap/heal/AuditWalk.scala` walk the anchor's tries by position, read rows directly (bypassing the cache), classify and repair through T215's members.
- [ ] T218 Replace both `stateStorage.saveNode(...)` calls in `main/blockchain/sync/regular/BlockImporter.scala` (`:485`, `:552`) with `repairNodeAt`, and make the missing-node fetch request nodes by position. This is a prerequisite for deleting `FastSyncNodeStorage` in C8b. **[forge] [beacon]** #1692 already swapped `saveNode` for `repairNode` at both sites on today's store, so what is left here is the rename to `repairNodeAt` and the fetch by position; `saveNode` can leave the `StateStorage` trait with it.
- [ ] T219 [P] Convert the recovery scans to position scans: `main/blockchain/sync/StorageRecoveryActor.scala`, `main/blockchain/sync/BytecodeRecoveryActor.scala`, `main/blockchain/sync/CombinedRecoveryScanner.scala` and the actor beside it.
- [ ] T220 Run `sbt compile-all`, `sbt "testOnly *AuditWalkPosition* *DeleteBadNodeAt* *BlockImporterRepairSites* *RecoveryScansVerdict* *PositionalHoleAudit*"`, `sbt "IntegrationTest / testOnly *OnDemandFetchByPosition*"`, `sbt scalafmtAll`, `scripts/snap-split/verify.sh --branch <branch> origin/staging HEAD`.
- [ ] T221 Record the DC-14 figures in `spec/baselines/dc14-C5b.md` (EG-8), then open the C5b PR; request `beacon`, `forge`, `eye`.

**Checkpoint**: repairs are safe by construction; spec 022's T064 intent (EG-5) becomes runnable.

---

## Phase 14: C5c, checkpoint format version 2 and the position-assigning importer (item 10, checkpoint)

**Goal**: ETC's resync path exists. A checkpoint archive of format v2 is exported with every occurrence of every node in pre-order,
the ancestor window chained by hash, and a trusted-hash import that assigns positions with a stack machine and no hash lookup.
Serves US6. **The format is a one-way door.**

**S0 prerequisites** (Satisfied, verified at this PR): forge item 8 (a) and (b): the window chained by hash and the genesis-anchor
clear (dm E.1). **Both reviewers sign the format again at the C5c PR before merge.**

**Reviewers**: **[forge] [beacon]**, sign the format; eye.

**Independent test / exit**: export then import gives an equal root on seeded tries, Mordor and Sepolia; a window that does not chain
is refused; the golden stream is reproduced; version 1 archives are refused.

### Tests (write first; they must fail)

- [ ] T222 [P] [US6] Test the round trip: export then import gives every root equal, on seeded tries, Mordor and Sepolia; a leaf holding slot 0 shared across many contracts' storage tries and **identical storage roots across owners** both get a row at **every** occurrence (v1's global `visited` skipped them). File: `test/blockchain/checkpoint/CheckpointV2RoundTripSpec.scala`. **[forge] [beacon]**
- [ ] T223 [P] [US6] Test the refusals: a corrupt entry, a hash mismatch, a CRC32C failure, truncation, each of the **four importer assertions** (an account leaf is always stored; an inline node holds no hash; a storage root below 32 bytes is still a root entry; `EmptyStorageRootHash` pushes no frame), a wrong `genesisHash`, a wrong operator-supplied expected block hash, and **version 1 refused** with a message naming the format. File: `test/blockchain/checkpoint/CheckpointV2RefusalSpec.scala`. **[forge]**
- [ ] T224 [P] [US6] Test the **ancestor window** (forge item 8 (a)): the `0x03` headers chain by `parentHash` from the checkpoint header and each `0x04` body matches its header (`transactionsRoot`, `ommersHash`), else `BadFormat`; `min(256, N)` headers and `min(6, N)` bodies are accepted when N is below 256. File: `test/blockchain/checkpoint/CheckpointV2AncestorWindowSpec.scala`. **[forge]**
- [ ] T225 [P] [US6] Test that a **genesis anchor is cleared at import start** (forge item 8 (b)): after a genesis commit, `beginCheckpointImport` clears `t`/`u` and the anchor before the first node, idempotently, and a kill mid-import leaves partial rows and **no anchor** (it falls through to SNAP as today, spec 022 T042). File: `it/blockchain/checkpoint/CheckpointImportGenesisAnchorItSpec.scala`. **[vault]**
- [ ] T226 [P] [US6] Test code completeness: every non-empty `codeHash` met has a bytecode entry; gaps are counted, logged for on-demand fetch, and fail the import above a threshold. File: `test/blockchain/checkpoint/CheckpointV2CodeCompletenessSpec.scala`.
- [ ] T227 [P] [US6] Pin the **golden stream** byte for byte: two accounts sharing one storage root, one inline storage leaf, one code entry, one header and one body of the ancestor window; and a second stream whose window does not chain, which must be refused `BadFormat` (dm E.1). Files: `test/blockchain/checkpoint/CheckpointV2GoldenStreamSpec.scala`, `src/test/resources/checkpoint/v2-golden.bin`. **[forge] [beacon]**

### Implementation

- [ ] T228 [US6] Define the version 2 format in `main/blockchain/checkpoint/CheckpointArchive.scala` (`Version = 2`, CRC32C, header binding `chainId` and `genesisHash(32)`, entry tags `0x03` header and `0x04` body, bytecodes after nodes, window before the CRC; the reader rejects version 1 with a message naming the format) (dm E.1 items 1 to 5). **[forge] [beacon]**
- [ ] T229 [US6] Rewrite the exporter walk in `main/blockchain/checkpoint/CheckpointExporter.scala` (replacing the global `visited` DFS of `:157` and `walkTrie` `:274-306`): pre-order DFS of the whole state with **every occurrence** emitted, branch children 0 to 15 ascending, the storage trie directly after the account leaf, only hashed children and roots as entries, then deduplicated bytecodes and the ancestor window. **[forge]**
- [ ] T230 [US6] Implement the **stack-machine importer** in `main/blockchain/checkpoint/CheckpointImporter.scala`: frames `(owner, path, expectedHash)`, no hash lookup and no scratch store, the four assertions each failing `BadFormat`, the operator-supplied expected block hash (and a pinned list for public chains), code completeness, window chaining and body matching, `min(256, N)`. **[forge] [beacon]** **Added by C2b's walkers PR (beacon):** a v1 archive is nodes by hash, so a storage trie that two accounts share is in it once (the export reads it under the first account). An importer that places each node by owner and path with no lookup by hash cannot rebuild the second account's trie from v1: either v2 emits each owner's trie, or importing v1 keeps a lookup by hash.
- [ ] T231 [US6] In `main/blockchain/checkpoint/CheckpointImporter.scala` make the import atomic and safe (dm E.1 "Atomicity and failure"): `beginCheckpointImport` clears a genesis anchor first (`t`, `u`, anchor, idempotent), is committed synced before the first node, rows go in batches of at most 10,000, and one synced batch under the state lock carries the header, the ancestor window, the chain weight, the best pointer, `state.anchor`, the done flags and `endCheckpointImport`; no `BlockDiff` for the checkpoint block. **[vault]**
- [ ] T232 [P] Document the trust statement (total difficulty is trusted from the archive and not re-derived) and the operator-supplied expected block hash in the checkpoint section of `docs/` (find it with `grep -ril checkpoint docs/`); the full operator-doc rewrite is C8b.
- [ ] T233 **M9**: report archive v1 versus v2 size and node counts, how many hashes occur at more than one position (the sharing the old dedup hid), per chain on Mordor and Sepolia, and archive-mode diff growth per day. Record in `spec/baselines/m9-archive-size.md`.
- [ ] T234 **[user]** After M1 and M9, decide the archive view reach (`state.archive.view-reach`, default 1024; plan open item 6) and record it in `src/main/resources/conf/base/state.conf` and `spec/plan.md`.
- [ ] T235 Run `sbt compile-all`, `sbt "testOnly *CheckpointV2*"`, `sbt "IntegrationTest / testOnly *CheckpointImportGenesisAnchor*"`, `sbt scalafmtAll`.
- [ ] T236 Record the DC-14 figures in `spec/baselines/dc14-C5c.md` (EG-8), then open the C5c PR; request `forge` and `beacon` to **sign the format again** (one-way door) and `eye`. No v2 archive exists until a node on this layout exports one (Mordor and Sepolia in EG-9, ETC mainnet from EG-12).

**Checkpoint**: a checkpoint archive of the new format round-trips on every chain that has a state.

---

## Phase 15: C6, ETC enablement and ETC mainnet readiness

**Goal**: everything ETC needs to run on the unified layout and to attempt its first mainnet sync is in place: the ETC SNAP guard,
the peer precondition and its logging, the PoW-specific hand-off checks, a private ETC-config PoW net with Olympia at a low block,
the ETC reorg and rewards matrices, and the EG-12 run harness. Serves US6. Consensus-critical.

**S0 prerequisites**: none beyond C5a.

**Reviewers**: **[forge] signs the ETC side before code**; **[beacon]** reviews shared code; eye.

**Independent test / exit**: the ETC PoW matrix passes on Mordor and on the private net; mined root equals import root with ommers;
ECIP-1017 rewards are unchanged across era boundaries; the EG-12 harness runs end to end on Mordor.

### Tests (write first; they must fail)

- [ ] T237 [P] [US6] ETC PoW reorg matrix on Mordor and on the private ETC-config PoW net (plan "Test matrices"): a heavier fork of depth 1 and 2; of depth `history`; `REORG-PARTIAL` with its side blocks re-enqueued; a fork whose blocks carry ommers referencing the abandoned branch; the `SYNC-FORK` rewind; an import batch of many blocks each adopted in turn; a mined block imported through `MinedBlockImport` **with ommers**; a proposer run on a stale parent; `MockedMiner` across several head moves. File: `it/consensus/pow/EtcReorgMatrixItSpec.scala`. **[forge]**
- [ ] T238 [P] [US6] Rewards and era-boundary differential: a range spanning an ECIP-1017 era boundary (5,000,000) and Mordor's two, with every miner and ommer balance compared with the pinned jar's recorded values. File: `it/consensus/pow/EtcRewardsEraBoundaryItSpec.scala`. **[forge]**
- [ ] T239 [P] [US6] ETC storage-deletion vectors: SELFDESTRUCT on every ETC fork (no EIP-6780 on any), EIP-161 touch-deletion from Atlantis (block 8,772,000) including an "empty" account with non-empty storage, and the CREATE2 recreate at the same address (tx1 writes A's storage, tx2 destroys A, tx3 recreates A with S'; the owner's rows equal S''s trie and a reverse restores the parent). File: `test/ledger/EtcSelfDestructForksSpec.scala`. **[forge]**
- [ ] T240 [P] [US6] Private-net test with Olympia at a low block: the treasury credit every block, the base-fee floor and genesis `HefPostOlympia` (`GenesisDataLoader.scala:237-238`) hold on the unified layout; the `ethCompatibleStorage = false` position test is scoped to **private nets** (no shipped chain sets it false). File: `it/consensus/pow/EtcOlympiaPrivateNetItSpec.scala`. **[forge]**
- [ ] T241 [P] [US6] Test the ETC SNAP guard and the peer precondition: with the guard on, ETC SNAP uses the unified layout and bypasses the Hash wiring (`SyncController.scala:476-480`); the count of snap-capable ETC peers is logged at start, and with fewer than 2 the node behaves as the user decided in T011. File: `test/blockchain/sync/EtcSnapPeerPreconditionSpec.scala`. **[forge] [herald]**

### Implementation

- [ ] T242 [US6] Under the guard, route ETC SNAP onto the unified layout in `main/blockchain/sync/SyncController.scala` (the ETC guard at `:476-480` and the Hash wiring are bypassed), and add the **peer precondition and its logging**: count snap-capable ETC peers at start, log the count, and apply the rule the user fixed in T011. **[forge] [herald]**
- [ ] T243 [US6] Add the PoW-specific hand-off checks named in S0 item 15 in `main/blockchain/sync/snap/controller/SnapFinalization.scala` and `main/db/storage/AncestorWindow.scala`: the 6 ancestor bodies present, the pivot's chain weight stored, no hand-off before them. Change `main/consensus/pow/*` only if C3c and C4e left a gap. **[forge]**
- [ ] T244 [P] [US6] Build the **private ETC-config PoW net**: `scripts/etc-private-net/` (two or three fukuii miners plus a core-geth, Olympia at a low block, the Ethash DAG on the miners) producing routine depth 1 to 3 forks; the hosts are the ones the user named in T011 (plan open item 4). **[forge] [eye]**
- [ ] T245 [P] [US6] Build the **EG-12 run harness** `scripts/etc-mainnet-sync/run-eg12.sh`: records the snap-capable peer count at start; time, disk, heap, RSS and CPU; counts rejected canonical blocks, `REORG-EXEC-FAIL` lines and fork moves of depth 1 to 2; runs the ancestor-window check (BLOCKHASH in the first 256 blocks, an ommer whose parent is below the pivot) over RPC; then exports the first ETC v2 archive and re-imports it on a second datadir (EG-9). **[forge]**
- [ ] T246 [P] [US6] Build the rewards and era-boundary comparison helper `scripts/etc-differential/compare-balances.sh` (every miner and ommer balance across the 5,000,000 era boundary and Mordor's two, against the pinned jar's recorded values).
- [ ] T247 **M10**: on Mordor with a local miner record the ETC fork rate and depth distribution, `moveHead` cost at depth 1 and 2, and the proposer-view cost per generated block. Record in `spec/baselines/m10-etc-forks.md`. **M13**: read the C4e branch-length instrumentation (blocks per `settleHead`, layer bytes, the longest BlockQueue branch) and record in `spec/baselines/m13-pow-branches.md`; if real branches exceed the pinned cap, raise it and say so in dm 6.4.
- [ ] T248 Run `sbt compile-all`, `sbt "testOnly *EtcSelfDestructForks* *EtcSnapPeerPrecondition*"`, `sbt "IntegrationTest / testOnly *EtcReorgMatrix* *EtcRewardsEraBoundary* *EtcOlympiaPrivateNet*"`, `sbt scalafmtAll`, and run the EG-12 harness once end to end on Mordor.
- [ ] T249 Record the DC-14 figures in `spec/baselines/dc14-C6.md` (EG-8), then open the C6 PR; request `forge` (signs the ETC side), `herald`, `beacon`, `eye`.

**Checkpoint**: ETC can be synced, mined and reorganised on the unified layout; the exit gate can run.

---

## Phase 16: C7, soak and exit gate (no code)

**Goal**: every exit-gate item EG-1 to EG-16 reports against the pinned jar's baselines, with the guard on. **Nothing in C8a or C8b
merges before this phase reports, and C8a also waits for EG-12.** The task order is the order to run them; the long soaks (EG-4, EG-6,
EG-12, EG-14, EG-15) run in parallel on the hosts of plan open item 4, with no sbt build on a host that is running a live sync gate.

**S0 prerequisites**: beacon item 5 (i) and the escalation (d) are in the gate (EG-4, EG-15, EG-14; Satisfied). **Not satisfied until
T010 is made:** the ETC 0 to 5.1M block source, for EG-1's ETC half.

**Reviewers**: **[eye]** runs and records; **[beacon]** owns EG-1 (ETH), EG-4, EG-13, EG-14, EG-15; **[forge]** owns EG-1 (ETC),
EG-2's `ForkChoiceBlockchainTestsSpec`, EG-6, EG-9 (ETC), EG-12; **[conduit]** EG-16; **[user]** accepts the report.

**Independent test / exit**: `spec/baselines/c7-gate-report.md` lists every EG item with its numbers and a pass or fail, and the user
accepts it.

- [ ] T250 Cut the soak build from staging after C6 has merged: record the commit and jar SHA-256 in `spec/baselines/c7-soak-build.md`; confirm every earlier slice's DC-14 figures are in its PR (EG-8 audit); confirm the guard `fukuii.state.path-live` is `true` in the soak config. **[eye]**
- [ ] T251 [US2] **EG-1 (ETH)**: import the fixed Platåberget and Sepolia ranges of T004, **including Prague and Amsterdam ranges with request and block-access-list activity** (and an ETH mainnet range only if a mainnet state exists); every state root, receipt root and gas-used equals the recorded value, and `requestsHash` and `blockAccessListHash` are compared explicitly. At Platåberget and Mordor scale the position store walked from the anchor equals a `StackTrie` rebuild. **[beacon]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T252 [US2] **EG-1 (ETC), Mordor**: import Mordor from genesis to 2,600,000 (era 0 to 1, Agharta 301,243, Phoenix 999,983, ECIP-1099 2,520,000); every block validates against its canonical header (state root, receipts root including the pre-Atlantis intermediate roots, gas used); node-set equality with the pinned jar every 100,000 blocks; every miner and ommer balance equal across Mordor's two era boundaries. Record `app.db.state.record.tombstones` and rows per deleted owner (M15). **[forge]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T253 [US6] **EG-1 (ETC), blocks 0 to 5,100,000 (gated by T010)**: with the source the user chose, run `ChainImporter.importChainFileStrict` from genesis; every block validates against its canonical header, node-set equality with the pinned jar every 100,000 blocks, and every miner and ommer balance equal across the 5,000,000 era boundary. This is the only coverage of the pre-Atlantis ETC rules (ECIP-1010, ECIP-1017 era 0 to 1, no EIP-161) on real data. Record M14 (final) and M15 (ETC half; any non-zero tombstone count explained before `max-entries-per-owner-delete` is frozen). **[forge]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T254 [US2] **EG-2**: run ethereum/tests and EEST **on the ported harnesses of C3c**: the ETH job and the ETC job green; `it/ethtest/ForkChoiceBlockchainTestsSpec.scala` (Istanbul and Berlin, i.e. Phoenix and Magneto, through `reorganise`) green under `basic` **and** `archive`; the pre-Byzantium and EIP-150 networks green. The harness canary (T110) must be green. **[forge] [beacon]**
- [ ] T255 **EG-3**: run the matrices of C3b to C6 (reorg PoS and PoW, ETC PoW, crash, isolation and concurrency, fault injection) and assert I1 to I6; the deterministic interleaving test over the importer commit, `moveHead`, `newPayload` and a builder view gives a position map equal to a from-scratch replay; the fault-injection runs never produce INVALID, `markInvalidRecursive`, `reportInvalid` or a delete; the no-anchor refusal and the escalation tests (hysteresis, cool-down, loop guard, kill at every step) pass. **[eye]** Result row in `spec/baselines/c7-gate-report.md`.

- [ ] T256 [US3] **EG-4: fresh Sepolia sync with 24 h of head following (the spec 022 T063 rerun)** under a real consensus client on the soak build: no `[PATH-PUBLISH]` line; hand-off time; disk (expect about 150 GB, below spec 022's T063 figure of 300 to 340 GB); import p50 and p99 and `[IMPORT-COMMIT]` against the pinned jar (DC-14); heap and RSS; overlay gauges; `app.db.state.view.stale` and `.retries`; the audit's first pass clean; and **`app.db.state.record.tombstones == 0`**. Also record **M2** (unpromoted payloads at once and the promotion lag from `newPayload` to `forkchoiceUpdated`, over the 24 h) in `spec/baselines/m2-promotion-lag.md`. **[beacon] [eye]**
- [ ] T257 [US3] **EG-5: spec 022's T064 intent on a FRESH datadir**: on a fresh Mordor datadir and then on EG-4's datadir once its measurements are taken, delete rows, corrupt bytes and move positions; the audit finds and fills them; the hand-off completes with no copy; record the audit's first-pass time and findings. The held spec 022 T063 datadir is not used. **[vault] [eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T258 [US6] **EG-6: ETC end to end**: a fresh Mordor SNAP sync and 24 h of head following with a local miner, **and** the private ETC-config PoW net of T244 producing depth 1 to 3 forks with Olympia at a low block (treasury credit every block, the base-fee floor, genesis `HefPostOlympia`): mined blocks valid, each mined block's root equal to its import root (with ommers), ECIP-1017 rewards unchanged; record M7 and M10. **[forge] [eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T259 **EG-7: repository gates**: `sbt pp`; the spec 016 ratchet (`scripts/snap-split/verify.sh`); the spec 017 and stateless-closure dependency checks (DC-8): `NibblePath` is in `main/mpt/` and no new `db/` import reaches the closure; the grep ratchets of `scripts/check-state-accessors.sh` and the hash-only walkers. **[eye]**
- [ ] T260 **EG-8: DC-14 figures in every slice's PR**: p50 and p99 `[IMPORT-TIMING]`, longest read wait, bytes written per block, database size, RSS, `[STORAGE-PERF]`; collect them into `spec/baselines/c7-dc14-table.md` and re-run **M7** at the gate against the pinned jar. **[eye]**
- [ ] T261 [US6] **EG-9: checkpoint v2 round trip, both chains**: export from the Mordor and Sepolia nodes of EG-4 and EG-6, import into fresh datadirs, every root equal; record the ETC resync time (SC-013). The ETC mainnet half is EG-12's export: import it into a fresh datadir, check root equality and follow the head for 1 h. **[forge] [beacon]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T262 **EG-10: both pruning modes, and the retired one**: `archive` passes EG-1's Mordor and Platåberget runs; a test shows `pruning.mode = "inmemory"` is rejected at start on both chains with the message naming the retired mode and the two that remain; the finalization durability test (kill -9 right after the synced commit) passes under `basic` and `archive`. **[vault] [eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T263 **EG-11 is scheduled after C8b** (it needs the final tree): this phase's report lists it as pending and the run is the C8b task T286. **[eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T264 [US6] **EG-12: first successful ETC mainnet sync** (user decision, 2026-11-06; fukuii has never completed one): a fresh ETC mainnet **SNAP sync on the unified layout with public ETC peers** reaches the head with no copy and the root equal to the head's `stateRoot`, then follows the head for 24 h. Passes only with: zero rejected canonical blocks; zero `REORG-EXEC-FAIL`; the number of routine fork moves (depth 1 to 2) recorded; **the ancestor-window check** (BLOCKHASH in the first 256 blocks, an ommer whose parent is below the pivot) passing; the number of snap-capable ETC peers recorded at start (**fewer than 2: the user decides whether a declared local core-geth peer is allowed in the run**); time, disk, heap, RSS and CPU recorded against spec 007's finding. The node then exports the first ETC v2 archive, which is re-imported (EG-9) and followed for 1 h. Use the harness of C6. A checkpoint is impossible as a route (no archive exists) and a sync from genesis takes one to three months. **C8a does not merge before this passes.** **[forge] [eye] [user]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T265 **EG-13: Hive and EEST**: each suite at least equals the pinned jar's recorded baseline: `ethereum/engine` (paris, shanghai, cancun, prague, osaka and amsterdam variants; the reorg, sidechain, invalid-ancestor, "Re-org to Previously Validated Sidechain" and "Transaction Re-Org" families), `ethereum/rpc-compat`, `ethereum/graphql`, `ethereum/consensus`, and EEST `consume-engine` and `consume-rlp` for Cancun through Amsterdam (eip7928, eip6780, eip7610, eip8246 and the 7002, 7251 and 8282 system contracts); the 658 `eip8246` fixtures at or above the count of T009. **[beacon] [eye]**
- [ ] T266 **EG-14: a consensus-client devnet** (kurtosis `ethereum-package`: Lighthouse or Prysm, fukuii and a second execution client): induced reorgs (a late block, a partition) are followed; the crash matrix under a real consensus client, **kill -9 between `newPayload` and `forkchoiceUpdated`, restart with no peers, the consensus client reaches VALID with no resync**; and **an induced escalation**: lower `state.retention.max-blocks` on the devnet and force a deeper reorg, then assert SYNCING (`forkchoiceUpdated`) and ACCEPTED (`newPayload`) during the re-SNAP, then VALID, with no manual step, one escalation only (cool-down), and `t`, `u` and CF `v` cleared at its start. **[beacon] [eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T267 **EG-15: Platåberget live head-follow, at least 6 h**, beside EG-4: it has the largest blocks, where `max-layer-bytes`, the tombstone guard and the `forkchoiceUpdated` latency (M6 against an 8 s consensus-client timeout) bind; **`app.db.state.record.tombstones == 0`** over the whole run, and the largest diff reported against `max-record-bytes` (M1). **[beacon] [eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T268 [US2] **EG-16: RPC under catch-up**: rpc-compat and `debug_traceBlock` during a regular-sync burst; the stale-view rate is measured (**M5** extended: 8 moves pass in under a second, and 8 concurrent `debug_traceBlockByNumber`), no request returns a stale-view error, and import latency stays within the DC-14 bound. **[conduit] [eye]** Result row in `spec/baselines/c7-gate-report.md`.
- [ ] T269 Write `spec/baselines/c7-gate-report.md`: one row per EG-1 to EG-16 with the numbers, pass or fail, and what is pending (EG-11 until C8b); list any item the reviewers must re-open. **[user]** accepts the report; a failing item stops C8a and C8b (fix forward, no revert of the earlier slices needed while the flag is off).

**Checkpoint**: the unified layout has passed the gate on both chains. C8a may start once T263 passes; C8b once C8a has merged.

---

## Phase 17: C8a, retirement 1: the copy and the publish plumbing (DESTRUCTIVE)

**Goal**: delete `PathToHashExporter`, the publish plumbing, `StorageScheme` and every Hash branch in `snap/`, once the unified layout
has passed the gate on both chains. **Merges only after C7 reports and EG-12 passes.**

**S0 prerequisites**: EG-12 passes (T263) and the user has accepted the C7 report (T268).

**Reviewers**: **[vault]**; **[beacon]**; **[forge]** (ETC SNAP); prism; the spec 016 ratchet. **[user] confirms before any delete.**

**Independent test / exit**: `sbt pp` is green; the spec 016 ratchet is green; the EG-1 subset (Mordor and Platåberget ranges, short
head-follow) reproduces the C7 roots on the tree without the exporter.

The vault protocol requires this block before the deletion (copied from plan.md "Destructive change"):

```
DELETION REQUIRED: PathToHashExporter (321 lines), startPathPublish and onPathPublishDone (about 75 lines in
SnapFinalization), the PathPublish* messages, getBulkLoadStorage, and three specs (about 560 lines)
Rationale: once execution, commit and serving read the position store (C2 to C5) the copy has no reader, and keeping it would
  keep a second writer of CF n, which the one-layout rule forbids.
Chesterton's Fence: it bridged SNAP's path-keyed output to execution's hash-keyed reads; it carried the FR-044 durability
  property (StateIngest.scala:71-92) and the hand-off sample (spec 022 D9). The sample is ported to PathHandoffSample (C5a);
  the durability property is met by the synced anchor commit (C5a); the copy's read-bound cost is what spec 022 T063 measured
  (35.7k down to 9.2k nodes/s, 10.8 h, stopped at 40-50%).
Alternative considered: Option X (frozen base read through, about 1,100 LOC), a per-chunk multiGet, keeping the exporter behind
  a flag. The user chose Option Y on 2026-11-06 and ruled out flags that outlive development.
Recommend: DELETE in C8a, after the C7 gate and EG-12.
```

- [ ] T270 **[user]** Confirm in writing, in the C8a PR description, that C7's report is accepted and EG-12 passed, and that the deletion block above is approved. **No file is deleted before this confirmation.** Record it in `spec/baselines/c8a-confirmation.md`.
- [ ] T271 Apply the dead-code-review protocol (`.claude/agent-protocols/dead-code-review.md`: wire it, delete it, or defer) to each deletion target and record the verdict in the commit message: `PathToHashExporter`, the publish plumbing, `getBulkLoadStorage`, `StorageScheme`, `SnapHashTrie`. **[vault]**
- [ ] T272 Verify before deleting (record each in the PR): `PathHandoffSample` covers everything `PathToHashExportCheckSpec` checked; the synced anchor commit meets FR-044 (`StateIngest.scala:71-92`); nothing but the exporter writes CF `n` during or after SNAP. **[vault]**
- [ ] T273 Delete `main/blockchain/sync/snap/PathToHashExporter.scala`, `startPathPublish` and `onPathPublishDone` and the `PathPublish*` messages in `main/blockchain/sync/snap/controller/SnapFinalization.scala`, `main/blockchain/sync/snap/controller/StateIngest.scala`, `main/blockchain/sync/snap/SNAPSyncController.scala` and `main/db/storage/ReferenceCountNodeStorage.scala` (the `getBulkLoadStorage` reference). Commit with `# fix: #1671` if the commit deletes more than 150 net lines of module code (the SNAP-split verifier rule).
- [ ] T274 Delete its specs: `test/blockchain/sync/snap/PathToHashExporterSpec.scala`, `test/blockchain/sync/snap/PathToHashExportCheckSpec.scala`, `test/db/storage/PathPublishRefCountSpec.scala`, and edit the two dispatch specs `test/blockchain/sync/snap/SNAPIdleTotalitySpec.scala` and `test/blockchain/sync/snap/SNAPSyncingDispatchTableSpec.scala` (and `test/blockchain/sync/snap/controller/SnapFinalizationSpec.scala`, `StateIngestDurabilitySpec.scala`) to drop publish cases.
- [ ] T275 Delete `StorageScheme` (`main/blockchain/sync/snap/StorageScheme.scala`), `SnapHashTrie` and every `StorageScheme.Hash` arm (about 64 references in 18 main files: `snap/actors/account/*`, `snap/actors/AccountRangeCoordinator.scala`, `snap/actors/healing/*`, `snap/actors/storage/*`, `snap/actors/StorageRangeCoordinator.scala`, `snap/actors/TrieNodeHealingCoordinator.scala`, `snap/controller/ChildFactories.scala`, `snap/controller/SnapResumePlanner.scala`, `snap/heal/HealInvariant.scala`, `snap/SNAPSyncController.scala`, `main/blockchain/sync/StorageRecoveryActor.scala`, `main/blockchain/sync/SyncController.scala`) and the spec 022 Hash-only mechanisms (the excluded-account seed, per-edge reference counts); delete the older scheme guards (`SyncController.scala:476-480`, `SnapResumePlanner.scala:105-115`). **[forge] [beacon]**
- [ ] T276 Run `sbt compile-all` (wraith for fallout), `sbt pp`, `scripts/snap-split/verify.sh --branch <branch> origin/staging HEAD`, then the **EG-1 subset** (Mordor and Platåberget ranges plus a short head-follow) and compare with T004. **[eye]**
- [ ] T277 Record the DC-14 figures in `spec/baselines/dc14-C8a.md` (EG-8), then open the C8a PR (net about -2,000 main and -1,000 test lines); request `vault`, `beacon`, `forge`, `prism`. Rollback: revert the PR on staging.

**Checkpoint**: no copy and no Hash branch in SNAP; the Hash storages in `db/` remain for C8b.

---

## Phase 18: C8b, retirement 2: the Hash scheme and the guard flag (DESTRUCTIVE, the last slice)

**Goal**: delete the Hash-scheme storages in `db/`, the pruning caches and their config, and the in-development flag, so that the
unified layout is the only layout. **The last slice; it merges only after C8a.**

**S0 prerequisites**: none new (the symmetric flag-off refusal goes with the flag).

**Reviewers**: **[vault]**; **[beacon]**; **[forge]**; prism; eye. **[user] confirms before any delete.**

**Independent test / exit**: `grep` for every removed symbol is empty; `sbt pp` is green; EG-11 (an EG-1 subset and a short
head-follow on each chain, with the flag gone) passes on the final tree.

The vault protocol requires this block before the deletion (copied from plan.md "Destructive change"):

```
DELETION REQUIRED: the Hash scheme as live state: ReferenceCountNodeStorage (520), CachedReferenceCountedStorage (292),
FastSyncNodeStorage (30), the Archive, ReferenceCounted and CachedReferenceCounted classes of StateStorage.scala (about 350),
StorageScheme and its 64 references in 18 main files, spec 022's Hash-only mechanisms (excluded-account seed, per-edge reference
counts), the `inmemory` pruning mode (CachedReferenceCountedStorage, its LRU and the inmemory-pruning-node-caching block), the
#1692 pruning stamp (RefCountTrust, PruningMetrics, the `nodesMayBeDeleted` parameter of ReferenceCountedStateStorage, the
`SnapFinalization.withdrawRefCountStampForPublish` call, and the AppState keys `state.refcounts.exact` and
`snap.heal.auditForcedOnce`), and about 3,500 lines of their tests
Rationale: ETC moves onto the unified layout in this track; with no Hash-layout datadir allowed to start, none of it has a caller.
Chesterton's Fence: it is today's production storage for ETC and the whole of ETC's SNAP and pruning; it keeps a node alive by
  reference count where an in-place store needs none. Its properties are replaced as follows: reorg undo by BlockDiff (7.3),
  pruning by retention (4.5), the duplicate-commit guard by isBlockApplied, the `inmemory` mode is retired, not replaced (4.6).
  RETAINED: SerializingMptStorage over ArchiveNodeStorage over EphemDataSource, for list tries and tests.
Alternative considered: keep the Hash scheme for ETC until a later gate (the earlier 10b), and run two layouts in one binary.
  The standing rule rejects it: one infrastructure, no staged transition.
Recommend: DELETE in C8b, the last slice, after the C7 gate; KEEP-AND-GUARD (the in-development flag) until then.
```

- [ ] T278 **[user]** Confirm in writing, in the C8b PR description, that C8a has merged, EG-12 and the C7 report stand, and the deletion block above is approved. **No file is deleted before this confirmation.** Record it in `spec/baselines/c8b-confirmation.md`.
- [ ] T279 Apply the dead-code-review protocol to each target and record the verdict in the commit message: `ReferenceCountNodeStorage`, `CachedReferenceCountedStorage`, `FastSyncNodeStorage`, the three Hash classes of `StateStorage.scala`, the pruning caches, `getBackingStorage` and `getReadOnlyStorage` as state accessors. **[vault]**
- [ ] T280 Delete `main/db/storage/ReferenceCountNodeStorage.scala` (520 lines), `main/db/storage/CachedReferenceCountedStorage.scala` (292), `main/db/storage/FastSyncNodeStorage.scala` (30; C5b removes only the importer's `saveNode` calls, which #1692 already moved to `repairNode`. `ReferenceCountedStateStorage.getReadOnlyStorage` and `.getNode` and the genesis load (`GenesisDataLoader.loadGenesisData`, through `ReadOnlyNodeStorage`) still write or read through it, so it goes with T281 and C3c's genesis commit, not before), and the Archive, ReferenceCounted and CachedReferenceCounted classes of `main/db/storage/StateStorage.scala` (about 350 lines). Also delete `main/db/storage/RefCountTrust.scala` and `PruningMetrics.scala` (#1692), and their AppState keys from `AppStateStorage.scala` (`state.refcounts.exact`, `snap.heal.auditForcedOnce`; dead keys in an old AppState are harmless, and the layout guard refuses an unmarked datadir anyway). **Retain** `SerializingMptStorage` over `ArchiveNodeStorage` over `EphemDataSource` for list tries and tests. **[vault] [forge]**
- [ ] T281 Remove `getBackingStorage` and `getReadOnlyStorage` as state accessors from `main/db/components/Storages.scala` and every caller (the ratchet of `scripts/check-state-accessors.sh` must show no allow-list entry for them); delete the pruning caches in `main/db/storage/pruning/package.scala` and `main/db/storage/DecodedNodeCache.scala` paths that only served the Hash scheme.
- [ ] T282 Delete `src/main/resources/conf/base/inmemory-pruning-node-caching.conf`, its include in `src/main/resources/conf/base/pruning.conf`, and the `sync.snap-sync.storage-scheme` key; make a config that still sets either **fail at start** with a message naming the deleted key, no deprecation window (dm 10). Test: `test/nodebuilder/DeletedConfigKeysSpec.scala` (write first, it must fail).
- [ ] T283 **Remove the guard flag**: delete `fukuii.state.path-live` from `src/main/resources/conf/base/state.conf`, make the unified layout unconditional in `main/db/components/Storages.scala` and `main/nodebuilder/NodeBuilder.scala`, and remove the flag-off marked-datadir refusal from `main/db/storage/StateLayoutGuard.scala` (dm 5.3). **[vault]**
- [ ] T284 Migrate the remaining tests that use removed symbols (about 700 lines migrate, about 3,500 lines of Hash-scheme tests go, e.g. `test/db/storage/ReferenceCountedStatePruningSpec.scala` and the Hash cases in SNAP specs) or delete them with a verdict each. **[eye]**
- [ ] T285 Rewrite the operator docs, skills and `.claude/agent-protocols/storage-rocksdb.md` for the unified layout: the anchor, the diff, the overlay, the rule "never write `t`/`u` outside a batch that carries the anchor", the layout marker and resync message, `basic` and `archive` only, the escalation runbook (a re-SNAP stops mining and state RPC for hours on ETC mainnet), and the checkpoint v2 trust statement. Search with `grep -rli "storage-scheme\|inmemory\|PathToHash" docs/ .claude/`. **[prism]**
- [ ] T286 **EG-11**: on the final tree, `grep -rn` for every removed symbol (`ReferenceCountNodeStorage`, `CachedReferenceCountedStorage`, `FastSyncNodeStorage`, `StorageScheme`, `PathToHashExporter`, `PathPublish`, `getBulkLoadStorage`, `path-live`) is empty; `sbt pp` is green; an EG-1 subset and a short head-follow run on each chain with the flag gone, reproducing the C7 roots. **[eye]** Result in `spec/baselines/eg11-final-tree.md`.
- [ ] T287 Close the track in the spec documents: set `**Status**` lines and the `**In flight**` field in `spec/spec.md` and `spec/plan.md` per `docs/development/spec-lifecycle.md`, regenerate `specs/README.md` (`python3 -I scripts/specs/index.py`, in WSL), and note in `spec/baselines/pinned-jar.md` that the oracle jar is kept one release past this merge.
- [ ] T288 Record the DC-14 figures in `spec/baselines/dc14-C8b.md` (EG-8), then open the C8b PR (net about -1,300 main and -2,500 test lines, plus 700 migrated); request `vault`, `beacon`, `forge`, `prism`, `eye`. Rollback: revert the PR on staging; there is no operator-facing rollback after this slice (a unified-layout datadir is re-synced to go anywhere else).

**Checkpoint**: one layout, one infrastructure, both chains. Tracks E, F, H and R may now be tasked.

---

## Dependencies and execution order

**Phase order is merge order.** Each arrow means "the right-hand slice cannot start its implementation tasks until the left-hand
slice has merged to staging" (its tests may be drafted earlier):

```text
C0 --> C1 --> C2a --> C2b ----------------------------+
                 \--> C3a --> C3b --> C3c --> C4a --> C4b --> C4d --> C4e --+
                                                                  \-> C5a --> C5b --> C5c --> C6 --> C7 --> C8a --> C8b
```

- **C0** blocks everything that compares with the pinned jar (C1 onwards), except that C1, C2a, C2b and C3a need only the frozen
  golden vectors and the oracle list.
- **C2b and C3a are independent** of each other (different files, no shared type beyond C2a's `StateView`); both need C2a. C3b needs
  both.
- **C3b needs C3a** (the record) **and C2a/C2b** (the read side and the readers). **C3c needs C3b.** The harness port in C3c is before
  every slice that claims a roots-equal check at harness level (EG-1, EG-2).
- **C4a needs C3b; C4b needs C4a.** C4d (PoS) and C4e (PoW) both need C4b and both edit `ConsensusImpl.scala`: they may be developed
  in parallel by different reviewers but **merge in the order C4d, C4e** (C4e rebases on C4d). C4c is withdrawn.
- **C5a needs C3b and the ancestor-window plumbing; C5b needs C5a and C3b's state lock; C5c needs C2b (exporter reads by position),
  C3c (genesis commit) and C4b (the state lock/anchor)**; C5b and C5c are independent of each other and may run in parallel.
- **C6 needs C4e, C5a and C5c** (it exports and re-imports the first ETC archive). **C7 needs all of C0 to C6** and the ETC block
  source decision (T010) for the ETC half of EG-1.
- **C8a needs C7 and EG-12** (T264). **C8b needs C8a.** EG-11 runs after C8b.

### Parallel opportunities

- Inside a slice: every task marked **[P]** (tests in different files; independent implementation files).
- Across slices: C0's pinned-jar, baselines and golden-vector tasks run together; C2b with C3a; C5b with C5c; C4d and C4e
  development (not merge).
- During C7: EG-4 (Sepolia), EG-6 (Mordor and the private net), EG-12 (ETC mainnet), EG-14 (devnet) and EG-15 (Platåberget) run in
  parallel on the hosts of plan open item 4. **No sbt build runs on a host while a live sync gate runs on it.** EG-1, EG-2, EG-3, EG-7,
  EG-8, EG-10 and EG-13 are CI-type runs and share one build host, one sbt JVM at a time.
- Reviewer lanes: `beacon` and `forge` review in parallel on every consensus-adjacent slice; `conduit` and `herald` on C2b; `prism`
  on the non-consensus slices.

## Implementation strategy

**MVP: C1 to C3b behind the guard.** With `fukuii.state.path-live = true`, a node can strict-import a block range committing state in
place, with one atomic batch per block and the changeset record, and every root equals the pinned jar's. That is the smallest increment
that proves the trie change (item 8), the record format (6a) and the in-place commit (6b), and it is where the design's riskiest
claims (positions, priors, the lock, the tombstone) are tested on real ranges. It is **not** usable on a live chain (no proposer view,
no overlay, no hand-off): C3c to C5a make it so. Stop and review after C3b: the measurements M1, M8 and M15 (part 1) and the DC-14
figures decide whether the sizes of the later slices stand.

1. **C0 first**, in parallel where [P]; C1 starts on the golden vectors and the oracle list while the roots are still being recorded.
2. **Tests before code, every slice**, and they must fail first; the failing run goes in the PR. Deterministic tests only.
3. **One slice, one PR, one guard.** The flag stays `false` on staging until C7; every slice's new tests run with it `true`.
4. **Consensus-adjacent slices are gated by `forge` and `beacon` before code** (C1, C3b, C3c, C4b, C4d, C4e, C5c, C6); the format
   slices (C3a, C5c) are one-way doors signed by both.
5. **Measure when the slice can**: M4 in C2a, M3 in C4a, M1 (final), M8 and M15 in C3b, M6 in C4b, M12 in C5a, M9 in C5c, M10 and M13
   in C6, M2, M5 and M7 in C7. Nothing is frozen on a guess (DC-14).
6. **The destructive slices are last and need the user.** C8a and C8b each carry the vault block, a dead-code-review verdict per
   target, and a written user confirmation before any delete. Keep the oracle jar one release past C8b.
7. **Stop conditions**: M7 over budget in C1; any root differing from the pinned jar in C3b; a tombstone on ETH in EG-4 or EG-15;
   EG-12 failing. Each stops the track at that slice; fix forward, and re-run the gate.

## What these tasks cannot yet specify

- **The ETC 0 to 5.1M block source** (T010): the task for EG-1's ETC half names both candidates and waits for the
  user.
- **Hosts, the pinned jar's directory, the snap-peer rule for EG-12 and the archive view reach**: user decisions
  (T011, the pinned-jar task, T234 in C5c). The tasks name where each is recorded.
- **Paths of new scripts and test files** under `scripts/`, `test/` and `it/` are proposals; the implementer adjusts to the package
  layout of the day and records the actual path in the PR.
- **Numbers marked as guesses** (overlay budget, pinned cap, `max-entries-per-owner-delete`) are set by M1, M2, M3, M13 and M15, not
  by these tasks.
- **The EIP-8246 text** has not been read by anyone; its task is C0's, and a mismatch reopens S0 item 5.

## Summary: tasks per slice

| Phase | Slice | Tasks | Tests-first | [P] | Story labels |
| --- | --- | ---: | ---: | ---: | --- |
| 1 | C0 | 17 | 0 | 6 | US2 6 |
| 2 | C1 | 17 | 9 | 9 | US2 14 |
| 3 | C2a | 16 | 6 | 9 | US2 5 |
| 4 | C2b | 14 | 5 | 10 | US2 7 |
| 5 | C3a | 10 | 5 | 5 | none |
| 6 | C3b | 27 | 12 | 13 | US2 3 |
| 7 | C3c | 19 | 9 | 11 | US2 4 |
| 8 | C4a | 15 | 7 | 9 | none |
| 9 | C4b | 19 | 10 | 10 | none |
| 10 | C4d | 22 | 12 | 13 | none |
| 11 | C4e | 17 | 9 | 10 | US6 10 |
| 12 | C5a | 16 | 7 | 9 | US3 6 |
| 13 | C5b | 12 | 6 | 7 | US3 4 |
| 14 | C5c | 15 | 6 | 7 | US6 10 |
| 15 | C6 | 13 | 5 | 8 | US6 10 |
| 16 | C7 | 20 | 0 | 0 | US2 4, US6 4, US3 2 |
| 17 | C8a | 8 | 0 | 0 | none |
| 18 | C8b | 11 | 0 | 0 | none |
| | **Total** | **288** | **108** | **136** | |
