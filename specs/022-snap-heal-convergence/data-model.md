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
- **Read at finalisation:** `storageRecoveryDone` is set only with no account-kind entry left and no scan owed, `bytecodeRecoveryDone` only with no code entry left and no scan owed (and its other conditions). A flag withheld is written `false`. A set that cannot be read sets neither.
- **Completion** requires no entry that counts, and a finished pass at the current root if any entry is dormant.

**Per-scheme handling (D5):**
- Path: delete the account-trie positions on the account's key, depth 0 to 64, at heal start.
- Hash: on every adopted root, resolve the account against the current root and seed its storage root or code if absent.
- Both: the same resolution runs, a page at a time, whenever healing has drained, and the completion gate stays shut until no entry counts. A node the lookup finds absent is seeded too (T035, as amended): on a datadir that keeps the invariant it is already being healed, so the seed joins that request; on one that does not, nothing else would reach it once slice 5 removes the walk.

## Invariant marker (durable, AppState `s`)

| Key | Value | Semantics |
|-----|-------|-----------|
| `snap.heal.invariant` | version (`4`) | Means "everything present was written under present ⇒ complete". **Does not mean complete.** A value of another version does not stand, and the removals below take it as they take a `3`: they go by the key being there. 4 since PR 5d, the first reader that skips a walk on its word: a run of an earlier jar could lose track with its marker standing, so such a datadir costs one walk. |
| `checkpoint.import.inProgress` | root hex, `:`, start ms | Set before a checkpoint import writes its first node, synced, in one write with the marker's removal. Cleared on CRC success, in the batch that writes the import's done flags, and by a stamp. While it is present the datadir is pre-invariant, whatever else it holds. |
| `snap.heal.datadirCreated` | version (`3`, its own constant) | Written when the genesis is first stored on a datadir that held none. A datadir created by an older binary never has it. The evidence that "empty" rests on. It did not move with the marker's version 4: who created the datadir did not change. |
| `snap.heal.frontierRetired` | `true` | CF `g` has had its one-time clear. |
| `snap.heal.codeScanOwed` | a reason, as text | This datadir's next finalisation may set neither recovery flag. Below. |

**What a standing marker means, since PR 5d.** Healing completes on its word, with no walk, when nothing is pending, in flight or held, the excluded set is clear and the current root is present. It is read at each decision and never cached, since `lostTrack` can take it mid-run and a dormant wake does not run `atSnapStart`. A root absent at that point is seeded, up to 3 times a root, since the orphan drop can leave it so, and then the walk takes over. Nothing else puts a walk back but the marker's own removal. The audit (below) is what looks afterwards, and what a clean pass of it says is the evidence that the marker was true.

**The records behind a completion.** `healingCleanWalkRoot` (a clean full walk covered this root) is all that stamps. `healingDecidedRoot` (the coordinator decided this root is complete, walk or no walk) is set before the signal, on the clean route alone. The controller takes `StateHealingComplete` only for the root it is on. Finalisation keeps a marker on either. A hand-off sets neither, so it takes the marker. Neither is durable: a restart decides again. A walk that could not follow something completes, and sets only the second. With the marker standing no walk runs to a clean end (the fallback's starts with the root absent, and takes the check once it has it), so that does not keep a marker a walk has called into question.

Every write of these keys is one `updateSync` batch. The frontier key goes through the same WAL as the range delete before it, so expect the delete to survive with it, but nothing depends on that: nothing reads `g`, so a lost clear costs only disk space.

**Stamped**, in a batch that also removes the import key, at exactly two points:
- at a SNAP start, on an **empty** datadir. Empty is defined conservatively (`HealInvariant.isEmpty`): `snap.heal.datadirCreated` is there, no import key, best block 0, nothing done, none of the keys a sync writes before its first trie node (pivot, floor, bootstrap target, root, finalized root, progress, resume checkpoint, phase flags, task-file pointers), and both Path column families empty. The hash-keyed store cannot be asked: it holds the genesis trie on every datadir. A datadir that fails any of this costs one walk;
- at a clean walk completion: the exits from healing in `HealingOrchestrator`, each only if the coordinator's clean full walk covered this root (`healingCleanWalkRoot`). That is `completeHealingWalkClean`, on both schemes since T052. `TrieWalkComplete(0)` and `TrieWalkResult(empty)` stamp the same way, and nothing starts the walk they end now. A walk that could not follow something (a node it could not traverse, an account leaf it could not read) says so at WARN and proves nothing, an earlier proof of that root included; healing completes as before. The Hash controller's own trie walk reads a present hash node and stops, so it is not the repair pass, and its `TrieWalkComplete(0)` alone stamps nothing.

**Removed:**
- when a checkpoint import starts writing; a successful import does not bring it back;
- at a finalisation with no clean decision on the finalised root behind it, a walk or the coordinator's with the marker standing, whatever the route: every lazy hand-off, since from there regular sync fetches what is missing a node at a time;
- when regular sync starts with SNAP not done (`SyncController.startRegularSync`), synced: SNAP's `Done` with no finalisation (`PivotSelector`), a finalisation that found no pivot header, and any route added later. Regular sync's on-demand fetches store a node alone, so the claim ends there; On Path regular sync cannot write the Path column families, so there the removal is only conservative: it costs a repair walk at the next SNAP start.
- when validation finds a node missing (`triggerHealingForMissingNodes`), synced: it walked the trie and contradicted a stamp;
- wherever `SnapSyncDone` is cleared, in the same batch: `clearDoneOnStart`, a stuck regular sync, a failed hand-off, a dangling best block. `clearDoneOnStart` removes it when SNAP was not done too;
- at a SNAP start where the marker cannot hold, or where the import key is set.
- when a run knows it has lost track (`HealInvariant.lostTrack`), synced, at WARN with the reason, with the code scan owed in the same write: a task file or code-hash file that cannot be read, is the wrong size or hangs; a restarted coordinator with no file, or whose wait for its pointer ran out; a coordinator out of restarts. The node keeps going. A walk that completes clean covers trie and storage; a lazy hand-off has no such walk behind it, and leaves them to the recovery scan and the audit. The scan at the next start covers code, as far as a pass can read. A clean walk may stamp it again.
- when a **fresh** Path account phase starts over existing nodes, synced, at WARN, before that phase writes (`HealInvariant.withdrawForPathAccountPhase`): a non-empty Path family with no cursors resumed, which includes the accounts-complete flag set with no task-file pointer persisted, and a supervisor restart of a launch that had no cursors, which the planner never sees. Costs one walk on such a run, and that walk stamps it again. Not on Hash, where nodes are content-addressed. Not at a storage-phase restart, and not on a first sync over empty families.
- **Not at a cursor resume (the user's decision, 2026-11-01), through a supervisor restart too.** It used to, and a multi-day sync restarts many times: this host's Sepolia logs show about 23 process starts in one first sync, so the walk would have gone only from account phases never resumed. What that leaves is what this list used to guard against. A position holds one node, so a phase over existing nodes can rewrite a region at another root; with a state returning to an earlier value and a second interruption, a node with the right hash can sit over a wrong child (spec 010; research R-5d). **Beacon's trace of what follows:** publish keys every node by its own keccak, so positions are discarded on the way to the store execution reads. The wrong child goes in under its own hash, and the parent names another. So execution reads the right node or throws: absence, never a wrong root. Import's on-demand path takes that as it takes any hole, the audit finds it, and neither an audit write nor a walk's can change what execution computes.

**`snap.heal.codeScanOwed`:**
- **Set**, in one synced write with the marker's removal, by `lostTrack` and at a SNAP start that finds the datadir `PreInvariant`: the repair run. Not on an empty datadir, under a standing marker, or where the marker cannot hold. Later starts of the same repair run write nothing.
- **Survives a restart,** and a stamp: a repair walk's or a later run's stamp says nothing of code. So a run that loses track and then walks clean has the marker back and still owes the scan.
- **Read and cleared at finalisation,** in the one synced write of the done flags. It is read just before that batch, after the durability barrier and the marker's removal, so a `lostTrack` on another actor's thread has a window of a few instructions; the key has no compare-and-clear, so not none. While it is set `bytecodeRecoveryDone` and `storageRecoveryDone` are both withheld (a failed task-file sweep is exactly where unfinished accounts were never recorded), and withheld is written as `false`: a datadir SNAP has been through before can hold `true` from that cycle, and leaving it out would not withhold anything. It is cleared there rather than when the scan ends because from that write on the flags carry the debt, durably: the next start scans, and resumes a scan cut short, until they are set. A later SNAP cycle is not charged for it.
- **What can leave it set:** a route with no finalisation (SNAP's `Done` from `PivotSelector`, a finalisation that found no pivot header), or a checkpoint import that finishes after a SNAP start saw it unfinished: the import writes `bytecodeRecoveryDone` itself and does not read this. Nothing reads the key but a finalisation, so the most it can cost is one scan after a later cycle.
- **Under the marker's removals above it is the code half:** the marker's own removals (a hand-off, a fresh Path account phase over existing nodes) do not set it, except that a datadir with no marker at the next SNAP start is `PreInvariant`, which does. A cursor resume keeps the marker, so it owes nothing (tasks.md T050a).

**The recovery flags (`bytecodeRecoveryDone`, `storageRecoveryDone`), read at a start with SNAP done and `do-snap-sync = true`:**
- **With the audit on, a flag down means no scan (PR 5c, decision 14).** Regular sync starts at once, and the audit's pass pays it, with `do-snap-sync = false` too. A flag is also what keeps a pass owed, so a flag left down over a matching anchor is paid. A bulk bytecode recovery still scans: it is the backstop when import hits code that really is missing, so a block remains where something is actually missing, and no block where nothing is. What follows about the scan holds with the audit off, and for that backstop.
- **True means a pass ran.** A clean finalisation with nothing withheld, a pass of the recovery scan, or a pass of the audit that ran to its end, clean or not. A stopped pass sets nothing. Where that pass met holes it does not mean code and storage are complete: nothing under a hole was looked at. A WARN gives the session's count, and a pass is not repeated (user, 2026-10-31; tasks.md T050a). `RecoveryProgress` does not record the count, so a resumed pass counts only what it walked. Import fetches what it reaches on demand, and the audit keeps looking.
- **A scan that could not run settles nothing:** a root that is absent, a node that does not decode, a failing store. `CombinedScanComplete.failed`, neither flag written, the progress left, an ERROR, and regular sync starts. The next start tries again.
- **The scan's root is the one a scan under way started with, while it can be read, else the best block's,** falling back to the pivot's. Regular sync is stopped through the scan and the downloads; the Engine API is not, so on a PoS node a consensus client can move the best block under a scan, and keeping the scan's own root is what lets a restart mid-scan resume. That path does not prune, so the walk is unharmed. Its first failure stops the other shards, and each shard's end logs how far the scan is and what it found, holes included, since the hole count itself is this session's only.
- **A gap found is not always a gap filled.** A bytecode gap is fetched and stored. A storage gap is downloaded as flat slots, with no trie built, and the flag goes true with the gap still there (tasks.md T050a, "Storage gaps").

**Pre-invariant states:** no marker and not empty; a checkpoint import in progress; a `clearDoneOnStart` restart. A pre-invariant datadir gets one repair pass: **the repair pass is the walk the gate runs where the marker does not stand**, and the marker records how it ended. No separate engine. **A walk longer than a re-peg cannot finish this over holes:** the first hole lifts `walkLocalOnly`, a re-peg invalidates the walk, on Path its later batches are dropped, and more than 3 stale ends abandon. It fills what it found and hands off lazily, unmarked. Decided 2026-10-31: such a datadir gets no stamp at Sepolia's scale. Each walk fills what it can, and the audit keeps looking (tasks.md T050a, T064).

**Where it cannot hold:** on the Hash scheme under `pruning.mode = inmemory`. The trie store there is the node cache, which writes healed nodes as it evicts them, so a crash can leave a present node over an absent child. And on either scheme under `deferred-merkleization = true` (H1): no trie is built during the download, and a lone absence still gets a done marker. Never stamped, and one found is removed. The verification walk stays at every start and completion, and that walk is the repair: one mechanism (V-N2 (3)), in one place (`HealInvariant.canHold`, which also says what the spawn hands the coordinator as `markerMayStand`). **The rule is scheme-aware (V-N2 (4)):** the Path scheme's account, storage and heal writes all go to the two Path column families through `PathNodeStorage`, in atomic batches, and never through the cache, so the marker holds on Path under any pruning mode.

**What it does not claim:**
- **Code.** The walk reads no code. On a datadir written by older binaries an account's code can be missing with nothing saying so, and a stamp does not change that. Missing code is found as before: by the recovery scan, or at import, where it throws and starts a bulk recovery. A repair run, and a run that lost track, withhold both recovery flags, so the scan runs at the next start (decided 2026-10-31, tasks.md T050a; `snap.heal.codeScanOwed` above), and a pass that met holes does not look under them. On a datadir written under the invariant, code is a dependency of its leaf (D4), once its hash is queued. The code-hash filter can lose one first, with nothing saying so: at 20M entries and 1e-9 the expected loss in a whole sync is 4e-11 at 10M unique hashes and 9e-4 at 20M, which is not zero. Import's bulk recovery is the backstop.
- **That a fault is found before hand-off.** Where the marker stands no walk looks at the trie first, so a fault one would have found is found by the audit or at execution, as an absence. The sample at hand-off (T054) reads a sample: it is blind to a region that is absent where no sampled account goes (beacon, accepted).
- **What the walk did not reach.** A clean walk proves what is reachable from its root. A present node from an older binary that no walked root reaches can sit over a hole, and a later root can reach it (a state that comes back). Covered when a root reaches it: the background audit descends through present nodes with nothing else to go on, and import's on-demand path finds it first if execution gets there first, since absence always throws. A liveness fault (R-4), and no scenario leaves state wrong.
- **What an interrupted import left that the walk did not reach.** A node it wrote for state that changed before the pivot is present over absent, and no walked root reaches it. A stamp covers it anyway and erases the import key, the one record of it. The same: covered when a root reaches it (tasks.md T050a, decision 12). Nothing looks for it before that, since a scan for present over absent cannot tell it from a partly pruned old trie.
- **On Hash, that the bytes are the node.** The walk there proves presence and decoding, not `keccak(bytes) = key`. On Path it reads through `readPathVerifiedRlp`, which hashes them, and a node that fails counts as missing. The audit checks this on Hash, in the audit only (decided 2026-10-31).
- **What a later older binary writes.** An older binary neither reads nor clears the marker. Run on a marked datadir, its writes are not vouched for, and nothing here can tell. A downgrade is a release note: start once with `clearDoneOnStart` (decided 2026-10-31).
- **A flat-only run.** Under `deferred-merkleization = true` no trie is built and a done marker vouches for flat slots alone, so a lone absence still gets one (tasks.md T050a, H1). So the marker cannot hold there, above. Production has it `false`.
- **That a hand-off has its root.** An `Other` abandon on Hash can leave the root absent. An abandon removes the marker, and regular sync fetches the root on demand: a liveness fault (R-4).
- **When healing ends, or what it costs.** The marker speaks to what is on disk. The sweeps, passes, position deletes and clocks listed as accepted under tasks.md T050a cost time or a retry and are not settled at mainnet's scale, and a walk or two more than needed may run. A restart downloads a partly downloaded account's storage again from slot zero, since nothing reads `storageCursors`: mid-account resume does not exist (research R-5d). None of it changes what a stamp says.

## Background audit (T053, FR-013)

The full-trie walk, after hand-off, with import running. `blockchain/sync/BackgroundAudit.scala`, `snap/heal/AuditWalk.scala`.

**What a clean pass means.** Every position of every trie the head reached was read as the state stood when the walk got there, with `keccak(bytes) = key` for every node and for every account's code, and nothing was absent or bad. No hole found, no bad node, no fetch exhausted, nothing dropped at a cap, nothing it could not follow.

**What it does not mean.**
- **That any one root was recomputed.** Induction holds per root, and a pass spans many (decision 11). The state moves under it, and what moved is import's writing, or the next pass's.
- **Anything of a node no current root reaches** (above, "What the walk did not reach").
- **That the node has no fault now.** It holds for what was read, when it was read. A pass that filled holes is not clean even if it filled all of them: another has to look at what the fill let it reach.

**A pass that ended, clean or not, sets both recovery flags**, whose meaning is that a pass ran. Only a clean one moves the anchor.

**Keys:**

| Key | Where | What |
|-----|-------|------|
| `snap.heal.auditCleanAnchor` | AppState `s` | The SNAP pivot a clean pass covered, or `no-pivot` on a checkpoint-synced node, which has done flags and no pivot. Owed when SNAP or a checkpoint is done and this differs, or a recovery flag is down. A never-SNAP node owes nothing. Finalisation does not write it. `clearSnapSyncDone` and `beginCheckpointImport` remove it, and the two keys below with it, in the same write: a re-SNAP can land on the same pivot, and every checkpoint import has the same anchor. |
| `snap.heal.auditCursors` | SNAP progress `p`, raw bytes | Where a pass has got to: the anchor, the pass number, the counts, and positions with no hashes, each resolved again from the current root when it comes up. Written with `commit()` every 3 minutes and at the pass's first fault, never `commitSync()`: the data source holds its lock through an fsync, and a lost cursor repeats a little of a walk. For another anchor, or unreadable, a pass starts over. Cleared at a pass's end. |
| `snap.heal.auditBadEntries` | SNAP progress `p`, raw bytes | The anchor it belongs to, per bad entry how many separate passes could not fetch it, the last of those and when, up to 1,024 entries, and the last pass's number, from which the backoff between unclean passes doubles (1 h up to 7 days). It outlives a pass. A served repair clears an entry. It goes when the anchor changes and at a clean pass, which also takes the pass number back to zero. |

**What the lock covers.** `commitLock` holds off block commits and prunes. `saveNode`, import's on-demand write, takes no lock and is a blind upsert at one reference: it can land either side of anything below, and can set a repaired node's count back to one, as it can any node's.

**What repair writes.** A node goes through `StateStorage.repairNode`: under `commitLock`, only if absent or failing keccak, judged at write time, with references pinned at 2^30, since a repaired node's real count is unknown (a leak of a few nodes, not a loss), never lowering a count it finds, and evicting the decoded node. Code goes to the code store alone, and the execution caches forget what it replaced.

**What it deletes.** Only an entry that does not unwrap, or bytes failing keccak, or code failing keccak, after fetching it exhausted in 3 separate passes with the backoff between, and with a read at the delete still finding it bad, the current root still reaching that hash at that position, and a read after that still finding it bad, all under `commitLock`. A fetch that timed out does not count. Never a node that passes keccak, and that includes an entry that does not unwrap whose raw bytes hash to the key, another pruning mode's format, which stays and is counted as not followable. The position is a hole after that.

**What is logged**, under `...sync.SyncController.BackgroundAudit`, a name the shipped `logback.xml` keeps at INFO, each line starting `[HEAL-AUDIT]`:

| Level | When |
|-------|------|
| DEBUG | Paused and resumed, when the pause lasted 5 minutes or less. |
| INFO | A pass starts or resumes. Progress every minute: nodes, accounts, moved, holes, repaired, unresolved, badNodes, deleted, droppedAtCap, unfollowable, elapsed, stack, queued holes, pauses. A pause past 5 minutes, once, and its end with how long. A repair. Off, once. |
| WARN | Each hole and bad entry, with its position and hash, and the root and block it was resolved under, saying that on a node that never lost state it may be #1692 (FR-013). A fetch that exhausted, or that had no answer in 15 minutes. An absent head root. A pass that ended not clean, with the wait for the next. |
| ERROR | The first bad entry of a pass no peer served. A delete, with what follows for its kind: for a node import's on-demand fetch, for code a bulk recovery first. Something it cannot follow, an entry kept for passing keccak in another format among them. A failed read. |

A pass that ended clean logs at INFO. The private `StateNodeFetcher` logs under its own name: its "signaling BlockImporter to back off" line, from an audit fetch, signals nothing to the importer.

## Persisted frontier (CF `g`): retired

On every node start until it has happened, CF `g` is cleared with one `deleteRange`, then `snap.heal.frontierRetired` is written, synced. That removes frontier entries, `CompleteMarkerKey` and the spec 005 subtree-complete records. A crash before the key clears again at the next start.

**Nothing compacts it, so an upgraded datadir keeps the disk space until something later compacts `g`.** A compaction at this point was built and removed (slice 4 review, vault F1): `compactRange` holds `dbLock`'s read lock across the whole native call, so on every upgraded node's first start the first write would queue for the write lock and every read behind it. A new datadir never has anything in `g`.

Nothing writes to CF `g` afterwards, under any pruning mode, and nothing reads it: not the resume, not the pruned oracle, not the scoped verification's precondition. `g` stays listed in `Namespaces.nsSeq`.

## Metrics and logs (FR-014, FR-017, FR-018)

| Name | Type | Meaning |
|------|------|---------|
| `snap_heal_held_nodes` / `snap_heal_held_bytes` | gauge | held set against its bound; bytes also count the flush buffer and the live requests, as the bound does |
| `snap_heal_pending` / `snap_heal_inflight` | gauge | requests |
| `snap_heal_excluded_accounts` | gauge | excluded accounts the last pass over the set left in it, since a count would read the store |
| `snap_heal_root_age_seconds` | gauge | time since the current pivot root was adopted |
| `snap_heal_nodes_per_repeg` | histogram | nodes healed between pivot moves (SC-005) |
| `state_ondemand_fetch_total{cause,source}` | counter | fetches after handoff. `cause` is `node` or `code`, `source` is `import` or `audit`, since the audit has its own fetcher. Once a fetch, in `StateNodeFetcher`'s new-fetch branch: not the de-dup a 30 s retry refires. A hash fetched again after an exhaust counts again |
| `engine_state_hole_total{kind}` | counter | distinct holes `newPayload` answered SYNCING over. `kind` is `account`, `storage`, `root`, `code`, `node` or `other`. The Engine path has no on-demand fetch, so this is what SC-004 reads on a post-merge node. Bounded memory: a hole forgotten past 1,024 others counts again |
| log `[HEAL-COMPLETE]` | – | root, nodes healed by this incarnation, what the gate walk did (`no full-trie gate walk` only where no walk started in this incarnation; `ran but did not decide this`; `as a fallback: the marker stands`; `the marker did not stand`; or that it could not follow something), and the seconds since this coordinator started. That is not storage-done: a restart in healing starts it over, so SC-001 comes from the controller's phase timestamps |
| log `[HEAL-COMPLETE] The signal is for a root healing has left` | – | the controller ignored a signal for a root it has left, and asked again |
| log `[STATE-ONDEMAND]` | – | source, cause, hash and position of a fetch, at INFO under `...regular.StateNodeFetcher` |
| log `[STATE-HOLE]` | – | block, kind, hash, account and position of a hole, at WARN under `...consensus.engine`; the same hole again once per 10 minutes, since the consensus client retries every slot |
