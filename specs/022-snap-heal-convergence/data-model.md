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
| account hash | 32 bytes |
| reason | storage abandoned, storage skipped, storage root mismatch, or code missing |
| claimed storage root | from the account leaf |
| claimed code hash | from the account leaf |

- **Written:** before any action is taken on it: before the Path ancestor deletes, or before Hash seeding.
- **Removed:** when, at the current root, the account's storage root and code are both present.
- **Completion** requires the set to be empty.

**Per-scheme handling (D5):**
- Path: delete the account-trie positions on the account's key, depth 0 to 64.
- Hash: on every adopted root, resolve the account against the current root and seed its storage root or code if absent.

## Invariant marker (durable, AppState `s`)

| Key | Value | Semantics |
|-----|-------|-----------|
| `snap.heal.invariant` | version (`2`) | Set only on an empty datadir at SNAP start, or after a repair pass finds zero holes. Means "everything present was written under present ⇒ complete". **Does not mean complete.** |
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
