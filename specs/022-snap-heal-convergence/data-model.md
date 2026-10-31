# Data Model: Spec 022

These are the logical entities. Field names are indicative, and the final names are set in tasks.

## Heal request (in memory)

| Field | Path | Hash | Notes |
|-------|------|------|-------|
| key | `pathset` (account path, or owner + storage path) | node hash | Path: aliases fall out because each position is its own request. |
| expected hash | yes | (= key) | Responses are matched by hash: hash → Seq[request]. |
| depth | path length | depth from the root of its trie | Sets dispatch priority: deepest first (D1). |
| parent | the parent request's key, or none for a root | the parent edge(s) | Hash: a child shared by several parents has one entry with several edges. |
| kind | account node, storage node, or code | same | Code requests come from account leaves (D4). |
| opens new subtree | flag | flag | Used by the pause policy: when the cap is reached, only requests without this flag are dispatched. |

**Lifecycle:**
- Created by discovery, from the root or from a held node's children.
- Dispatched deepest-first.
- On response, the node becomes a **held node**, or is committed directly if it has no missing children.
- A pivot move drops all requests and held nodes. Discovery restarts from the new root.

## Held node (in memory, never durable)

| Field | Notes |
|-------|-------|
| key | as for a heal request |
| bytes | the node RLP, already checked against its keccak |
| outstanding | count of missing children, plus a code dependency for account leaves with code missing |
| parents | the edges to decrement when this node is committed |

**Invariant:** a held node is never on disk.

**Commit:** when `outstanding` reaches 0, the node and every ancestor whose count reaches 0 go into one atomic write. They are released from memory only after that write returns (D3).

**Bound:** total held bytes ≤ `healing.held-memory-limit`, default 128 MiB. At the bound, only requests for children of held nodes are dispatched.

*The bound is soft (slice 2 review, hunter W3, a Note):* every fetch below a held node frees memory, so it is never paused, and the pause only holds back new subtrees (a root seed, walk and controller entries). On a heal seeded from the root almost everything is below a held node, so the limit does not cap that path: memory there is bounded by deepest-first dispatch (in flight × depth, plus the queued siblings in the request book), about 150 MB at worst with the defaults, the same order as the limit. A per-depth cap on queued children would make it hard; that is a later design change, not this slice. Held nodes that nothing can complete any more (orphans) do not pause new subtrees either: the orphan guard drops them.

## Excluded account (durable, AppState `s`)

| Field | Notes |
|-------|-------|
| subject | 32 bytes: the account hash; under "code missing" the code hash |
| reason | storage abandoned, storage skipped, storage root mismatch, or code missing. A completion with no trie on a non-empty claimed root is a mismatch: that root was never written (T050a, H1) |
| claimed storage root | what the decision saw; not read back: the entry's needs are read from the account leaf at the current root |
| claimed code hash | as above |

*Keys (slice 3):* `SnapExcluded/1/` + a kind byte + the subject, in AppState `s`, read and written by `SnapSyncProgressStorage`. A "code missing" entry is keyed by the code hash because the bytecode download, where that decision is made, knows the hash and not the accounts that use it; it leaves the set when the code is stored, with no root in play, and on Path it deletes no positions.

- **Written:** before any action is taken on it: before the Path ancestor deletes, or before Hash seeding. The decisions of one coordinator message share one write, at most 5,000 keys a batch with `updateSync` on the last. A failed write stays staged and is retried by the next message, and the phase cannot end cleanly over it. Only the SNAP controller's spawns write; the recovery actors do not. A restarted coordinator also reads its durable list back before the phase ends, clean or forced, for what the restart lost: the storage task file minus the done markers, the code-hash file minus the code store. A force-complete on a healthy coordinator does so only while tasks are still to come from the recovery stream. Before the code-only hand-off writes its chains, it records every awaited code hash here.
- **Removed:** when, at the current root, the account's storage root and code are both present. A "code missing" entry: when the code is stored.
- **Dormant:** when the current root holds no such account, the entry moves to kind `d` instead of leaving: the decision still stands for that account's storage, and a later root can hold it again (A to B to A, or a reorg). A dormant entry does not count against a root, gets no position delete, and is looked at again by the first pass at each adopted root, so completion waits for that one pass. If that root holds the account incomplete it counts again, with its seeds; complete, it is removed.
- **Cleared:** only where the account phase starts with no carried task files, no held checkpoint and no resumed cursors, because a later cycle may not read an earlier one's decisions. Not at `restartSnapSync`: a restart may resume cursors, and the accounts behind them are not downloaded again, so the planner decides. Never at finalisation: after a lazy hand-off the set is the only record of those accounts.
- **Dormant entries at a clean completion:** dropped when healing completes clean, because a pass has just looked at each from the root it completed at. Not at a lazy hand-off.
- **Read at finalisation:** `storageRecoveryDone` is set only with no account-kind entry left, `bytecodeRecoveryDone` only with no code entry left (and its other conditions). A set that cannot be read sets neither.
- **Completion** requires no entry that counts, and a finished pass at the current root if any entry is dormant.

**Per-scheme handling (D5):**
- Path: delete the account-trie positions on the account's key, depth 0 to 64, at heal start.
- Hash: on every adopted root, resolve the account against the current root and seed its storage root or code if absent.
- Both: the same resolution runs, a page at a time, whenever healing has drained, and the completion gate stays shut until no entry counts. A node the lookup finds absent is seeded too (T035, as amended): on a datadir that keeps the invariant it is already being healed, so the seed joins that request; on one that does not, nothing else would reach it once slice 5 removes the walk.

## Invariant marker (durable, AppState `s`)

| Key | Value | Semantics |
|-----|-------|-----------|
| `snap.heal.invariant` | version (`2`) | Means "everything present was written under present ⇒ complete". **Does not mean complete.** A value of another version does not stand, and the removals below take it as they take a `2`: they go by the key being there. |
| `checkpoint.import.inProgress` | root hex, `:`, start ms | Set before a checkpoint import writes its first node, synced, in one write with the marker's removal. Cleared on CRC success, in the batch that writes the import's done flags, and by a stamp. While it is present the datadir is pre-invariant, whatever else it holds. |
| `snap.heal.datadirCreated` | version (`2`) | Written when the genesis is first stored on a datadir that held none. A datadir created by an older binary never has it. The evidence that "empty" rests on. |
| `snap.heal.frontierRetired` | `true` | CF `g` has had its one-time clear. |

Every write of these keys is one `updateSync` batch. The frontier key goes through the same WAL as the range delete before it, so expect the delete to survive with it, but nothing depends on that: nothing reads `g`, so a lost clear costs only disk space.

**Stamped**, in a batch that also removes the import key, at exactly two points:
- at a SNAP start, on an **empty** datadir. Empty is defined conservatively (`HealInvariant.isEmpty`): `snap.heal.datadirCreated` is there, no import key, best block 0, nothing done, none of the keys a sync writes before its first trie node (pivot, floor, bootstrap target, root, finalized root, progress, resume checkpoint, phase flags, task-file pointers), and both Path column families empty. The hash-keyed store cannot be asked: it holds the genesis trie on every datadir. A datadir that fails any of this costs one walk;
- at a clean walk completion: the three exits from healing in `HealingOrchestrator` (`TrieWalkComplete(0)`, `TrieWalkResult(empty)`, `completeHealingWalkClean`), each only if the coordinator's clean full walk covered this root (`healingCleanWalkRoot`). A walk that could not follow something (a node it could not traverse, an account leaf it could not read) says so at WARN and proves nothing, an earlier proof of that root included; healing completes as before. The Hash controller's own trie walk reads a present hash node and stops, so it is not the repair pass, and its `TrieWalkComplete(0)` alone stamps nothing.

**Removed:**
- when a checkpoint import starts writing; a successful import does not bring it back;
- at a finalisation with no clean full walk of the finalised root behind it, whatever the route: every lazy hand-off, since from there regular sync fetches what is missing a node at a time;
- when regular sync starts with SNAP not done (`SyncController.startRegularSync`), synced: SNAP's `Done` with no finalisation (`PivotSelector`), a finalisation that found no pivot header, and any route added later. Regular sync's on-demand fetches store a node alone, so the claim ends there; On Path regular sync cannot write the Path column families, so there the removal is only conservative: it costs a repair walk at the next SNAP start.
- when validation finds a node missing (`triggerHealingForMissingNodes`), synced: it walked the trie and contradicted a stamp;
- wherever `SnapSyncDone` is cleared, in the same batch: `clearDoneOnStart`, a stuck regular sync, a failed hand-off, a dangling best block. `clearDoneOnStart` removes it when SNAP was not done too;
- at a SNAP start where the marker cannot hold, or where the import key is set.
- when a Path account phase starts over existing nodes, synced, at WARN, before that phase writes (`HealInvariant.withdrawForPathAccountPhase`): cursors resumed; a fresh phase on a non-empty Path family, which includes the accounts-complete flag set with no task-file pointer persisted; or a supervisor restart of the account coordinator, which the planner never sees. A position holds one node, so a phase over existing nodes can rewrite a region at another root; with a state returning to an earlier value and a second interruption, a node with the right hash can sit over a wrong child (spec 010; research R-5d). Costs one walk on such a run, and that walk stamps it again. Not on Hash, where nodes are content-addressed. Not at a storage-phase restart, and not on a first sync over empty families.

**Pre-invariant states:** no marker and not empty; a checkpoint import in progress; a `clearDoneOnStart` restart. A pre-invariant datadir gets one repair pass: **while the walk still runs at every completion (until slice 5), the repair pass is that walk**, and the marker records how it ended. No separate engine. **A walk longer than a re-peg cannot finish this over holes:** the first hole lifts `walkLocalOnly`, a re-peg invalidates the walk, on Path its later batches are dropped, and more than 3 stale ends abandon. It fills what it found and hands off lazily, unmarked. Decided 2026-10-31: such a datadir gets no stamp at Sepolia's scale. Each walk fills what it can, and the audit keeps looking (tasks.md T050a, T064).

**Where it cannot hold:** on the Hash scheme under `pruning.mode = inmemory`. The trie store there is the node cache, which writes healed nodes as it evicts them, so a crash can leave a present node over an absent child. Never stamped, and one found is removed. The verification walk stays at every start and completion, and that walk is the repair: one mechanism (V-N2 (3)). **The rule is scheme-aware (V-N2 (4)):** the Path scheme's account, storage and heal writes all go to the two Path column families through `PathNodeStorage`, in atomic batches, and never through the cache, so the marker holds on Path under any pruning mode.

**What it does not claim:**
- **Code.** The walk reads no code. On a datadir written by older binaries an account's code can be missing with nothing saying so, and a stamp does not change that. Missing code is found as before: by the recovery scan, or at import, where it throws and starts a bulk recovery. A repair run withholds `bytecodeRecoveryDone`, so the scan runs at the next start (decided 2026-10-31, tasks.md T050a). On a datadir written under the invariant, code is a dependency of its leaf (D4), once its hash is queued. The code-hash filter can lose one first, with nothing saying so: at 20M entries and 1e-9 the expected loss in a whole sync is 4e-11 at 10M unique hashes and 9e-4 at 20M, which is not zero. Import's bulk recovery is the backstop.
- **What the walk did not reach.** A clean walk proves what is reachable from its root. A present node from an older binary that no walked root reaches can sit over a hole, and a later root can reach it (a state that comes back). Under slice 5 that is what the background audit and the on-demand path are for: a liveness fault (R-4).
- **What an interrupted import left that the walk did not reach.** A node it wrote for state that changed before the pivot is present over absent, and no walked root reaches it. A stamp covers it anyway and erases the import key, the one record of it. Slice 5's audit must cover this (tasks.md T050a).
- **On Hash, that the bytes are the node.** The walk there proves presence and decoding, not `keccak(bytes) = key`. On Path it reads through `readPathVerifiedRlp`, which hashes them, and a node that fails counts as missing. The audit checks this on Hash, in the audit only (decided 2026-10-31).
- **What a later older binary writes.** An older binary neither reads nor clears the marker. Run on a marked datadir, its writes are not vouched for, and nothing here can tell. A downgrade is a release note: start once with `clearDoneOnStart` (decided 2026-10-31).
- **A flat-only run.** Under `deferred-merkleization = true` no trie is built and a done marker vouches for flat slots alone, so a lone absence still gets one (tasks.md T050a, H1). A finalisation with no clean walk behind it removes the marker, so it claims nothing there. Production has it `false`.
- **That a hand-off has its root.** An `Other` abandon on Hash can leave the root absent. An abandon removes the marker, and regular sync fetches the root on demand: a liveness fault (R-4).
- **When healing ends, or what it costs.** The marker speaks to what is on disk. The sweeps, passes, position deletes and clocks listed as accepted under tasks.md T050a cost time or a retry and are not settled at mainnet's scale, and a walk or two more than needed may run. A restart downloads a partly downloaded account's storage again from slot zero, since nothing reads `storageCursors`: mid-account resume does not exist (research R-5d). None of it changes what a stamp says.

## Persisted frontier (CF `g`): retired

On every node start until it has happened, CF `g` is cleared with one `deleteRange`, then `snap.heal.frontierRetired` is written, synced. That removes frontier entries, `CompleteMarkerKey` and the spec 005 subtree-complete records. A crash before the key clears again at the next start.

**Nothing compacts it, so an upgraded datadir keeps the disk space until something later compacts `g`.** A compaction at this point was built and removed (slice 4 review, vault F1): `compactRange` holds `dbLock`'s read lock across the whole native call, so on every upgraded node's first start the first write would queue for the write lock and every read behind it. A new datadir never has anything in `g`.

Nothing writes to CF `g` afterwards, under any pruning mode, and nothing reads it: not the resume, not the pruned oracle, not the scoped verification's precondition. `g` stays listed in `Namespaces.nsSeq`.

## Metrics and logs (FR-014, FR-017, FR-018)

| Name | Type | Meaning |
|------|------|---------|
| `snap_heal_held_nodes` / `_bytes` | gauge | held set against its bound |
| `snap_heal_pending` / `_inflight` | gauge | requests |
| `snap_heal_excluded_accounts` | gauge | excluded accounts still outstanding |
| `snap_heal_root_age_seconds` | gauge | time since the current pivot root was adopted |
| `snap_heal_nodes_per_repeg` | histogram | nodes healed between pivot moves (SC-005) |
| `state_ondemand_fetch_total{cause}` | counter | fetches after handoff, with position |
| log `[HEAL-COMPLETE]` | – | root, nodes healed, "no full-trie gate walk", time from storage-done |
