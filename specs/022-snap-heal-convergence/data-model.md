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
| reason | storage abandoned, storage skipped, storage root mismatch, or code missing |
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
| `snap.heal.invariant` | version (`2`) | Set only on an empty datadir at SNAP start, or after a repair pass finds zero holes. Means "everything present was written under present ⇒ complete". **Does not mean complete.** Never set (or trusted) under `pruning.mode = inmemory`, where healed nodes reach disk only as the node cache evicts them; the repair pass runs at every start there. |
| `checkpoint.import.inProgress` | root plus start time | Set before a checkpoint import starts writing, and cleared on CRC success. If it is present at SNAP start, the datadir counts as pre-invariant. |

**Pre-invariant states:**
- no marker but heal progress exists;
- a checkpoint import in progress;
- a `clearDoneOnStart` restart.

Each of these leads to one repair pass (audit, then heal), and then the marker is stamped.

## Persisted frontier (CF `g`): retired

On the first start with marker support, CF `g` is cleared with one `deleteRange`. That removes frontier entries, `CompleteMarkerKey` and the spec 005 subtree-complete records. After that, CF `g` gets no new writes.

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
