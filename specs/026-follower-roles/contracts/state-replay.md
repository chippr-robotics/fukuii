# Contract: `StateReplay` capability

**Owner**: `vault` (018 amendment row 7). **Consumers**: `blockchain/follow/FollowController` (026), later 018 Track F
(v2 implementation). **Reviewers before the v1 apply path lands**: `forge` (ETC/Hash), `beacon` (ETH/Path).
**Status**: Planned with spec 026 (2026-10-31); binding once the S1 PR merges.

Signatures: [data-model.md §2](../data-model.md). This file states the behaviour every implementation must have.

---

## 1. Purpose

A third kind of state writer beside SNAP ingest (`StateIngest`, spec 016) and EVM execution (`StateAccess`, spec 017):
it applies a **semantic state diff** (accounts, slots, code by hashed key, previous and new value) forward or backward
onto a state identified by its root, recomputes the commitment root with the same `mpt/` code import uses, and commits
only when the root equals the expected one. No execution, no network, no layout named in the contract.

## 2. Preconditions

| # | Condition | On violation |
|---|---|---|
| P1 | The pre-state root (`parentRoot` forward, `currentRoot` backward) is resolvable in the store | `MissingPreState`; nothing written |
| P2 | `diff` passes `StateDiff.validate` (sorted unique keys; slot owners present; code for every non-empty `codeHash`) | `InvalidDiff`; nothing written |
| P3 | For every account whose storage changed and whose `next` is present, the recomputed storage root equals `next.storageRoot` | `InvalidDiff`; nothing written |
| P4 | The chain's `eth-compatible-storage` is `true` (slot key = `keccak(pad32(slot))`) | construction fails at start |
| P5 | The caller holds the role `replica` or `archive` | construction refused in `NodeBuilder` |

## 3. Postconditions

| # | `applyForward` | `applyBackward` |
|---|---|---|
| Q1 | Computed account-trie root `== expectedRoot` (header.stateRoot) **and** `receiptsRootOf(receipts) == expectedReceiptsRoot`, else `RootMismatch` / `ReceiptsRootMismatch` and **nothing** (state, flat, code, `extra`) is committed | Computed root `== expectedParentRoot`, else `RootMismatch` and nothing committed |
| Q2 | On success the state at `expectedRoot` is readable through `StateStorage.getBackingStorage(blockNumber)` and `getReadOnlyStorage` | On success the state at `expectedParentRoot` is readable the same way |
| Q3 | Flat stores reflect every `next` value (deleted where `None`); code store holds every code entry | Flat stores reflect every `previous` value; code store unchanged |
| Q4 | `extra` is committed in the **same** atomic write as the state (one `DataSourceBatchUpdate`, one RocksDB `WriteBatch`) | same |
| Q5 | The returned `IO` completes only after the data source's write call has returned | same |
| Q6 | Idempotent at the record level: calling again with the same inputs after success is a no-op for the trie (content-addressed on v1-Hash) and the flat stores; reference counts MAY differ and the caller MUST NOT re-apply a committed block (the follower's queue guarantees this, research R-5) | same |

## 4. Algebraic laws (property-tested on `EphemDataSource`, every implementation)

- **L1 Round trip.** For any valid `diff` over pre-state `S` with root `r0`: `applyBackward(applyForward(S, diff)) == S`
  byte-for-byte in CF `n` (keys, values and, on the Hash scheme, reference counts via `RefCountInspector`), in the flat
  stores, and in the code store (code may be a superset: Q3). Root `r0` is recomputed exactly.
- **L2 Import equivalence.** For any pre-state and diff, `applyForward`'s root equals the root `InMemoryWorldStateProxy`
  produces when the same accounts/slots/code are written through `saveAccount`/`saveStorage`/`saveCode` and
  `persistState` (`ledger/InMemoryWorldStateProxy.scala:84-113`) over the *un-hashed* keys. This is the DC-7 check:
  identity encoder over hashed keys ≡ hashing encoder over raw keys.
- **L3 Hash-key equivalence.** `MerklePatriciaTrie[ByteString, V](root, s)(identityEncoder).put(keccak(k), v)` and
  `MerklePatriciaTrie[K, V](root, s)(hashingEncoder).put(k, v)` yield the same node set and root.
- **L4 Determinism.** Two implementations (v1 today; v2 under 018) given the same pre-state and diff return the same
  root. 018's differential harness (018 FR-009 / SC-001) adds `StateReplay` to its compared paths when v2 exists.
- **L5 Atomicity.** Injecting a failure (a `DataSource` that throws on `update`) between any two internal steps leaves
  the store byte-identical to the pre-state.

## 5. Durability policy

The capability commits with `DataSourceBatchUpdate.commit()` (WAL appended). It exposes no `sync` flag; the caller
chooses cadence by passing `extra` built with `commitSync` semantics where required (`FollowController` does so every
`fukuii.follow.sync-every` blocks, on halt, drain and stop; research R-12). On `pruning.mode = "inmemory"` the
implementation MUST call `StateStorage.forcePersist` before any synced commit, as `CheckpointImporter` does
(`blockchain/checkpoint/CheckpointImporter.scala:166`). Followers do not run `inmemory` pruning in the first release;
the check is defensive.

## 6. Observability

Per call: duration, accounts changed, slots changed, code bytes, nodes written, batch bytes; counters for each
`ReplayError`. Exposed as `fukuii_state_replay_*` metrics and at DEBUG with the block number and 8-byte root prefix,
following `logging-standards.md`.

## 7. Non-goals

Proof generation (`eth_getProof` reads the trie through `BlockchainReader.getAccountProof`, unchanged); pruning policy
(the `StateStorage` mode decides); record decoding (025); choosing what to apply (the follower's queue).

## 8. Versioning

`StateReplay.v1` is today's layout. v2 (018 Track F, F6 flat reads, F11 parallel root) replaces the constructor behind
the same trait; `FollowController` has no layout-specific code (026 FR-002). A change to this contract is an 018
`data-model.md` change reviewed by `vault`.
