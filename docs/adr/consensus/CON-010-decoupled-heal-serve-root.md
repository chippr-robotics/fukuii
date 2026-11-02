# CON-010: Decoupled heal serve-root — fixed walk root, advancing fetch root

**Status**: Accepted; amended by [[CON-014]] (2026-11-02). The decoupled serve root stays. Completion is no longer judged by the walk alone: where the invariant marker stands it takes no walk.

**Date**: 2026-06-17

**Spec**: `specs/004-decoupled-heal-serve-root/`

**Related**: [[CON-009]] (healing completeness marker), PR #1357 (hold-pivot livelock fix), `specs/003-scoped-heal-verification/`

## Context

After SNAP sync, `TrieNodeHealingCoordinator` proves the state trie is complete with a **completeness walk**: a local read-only BFS over the trie that finds referenced-but-missing nodes, **heals** each by fetching it from a peer via SNAP `GetTrieNodes`, and declares `StateHealingComplete` only when a walk finds zero missing.

Today the walk and the fetch are pinned to the **same single state root** (`stateRoot`). That coupling deadlocks a slow or peer-scarce node:

- The completeness walk takes **hours** (~16-20h for the full ETC-mainnet trie on slow storage — see `specs/002`/`specs/003`).
- ETC peers serve `GetTrieNodes` for a given state root only for roughly the most recent ~128 blocks (~28 minutes at ~13s/block).
- By the time the slow walk reaches the deep storage gaps, the pinned root has **aged out of every (non-archive) peer's serve window**, so the fetch times out and the deep gaps can never be filled. Live evidence: heal stuck at ~99% (27,346 nodes healed), with all-peers-stateless rolls clustering at 26-27 minute intervals — the serve window.

The hold-pivot fix (PR #1357) holds the root across *stagnation* but cannot help here: holding an aged, unservable root just trades roll-churn for a hold-stall, and a genuine all-peers-stateless roll re-seeds the multi-hour walk. Neither converges.

This is **consensus-adjacent**: it changes how the node *sources* the nodes it must possess before it trusts its post-SNAP state. A wrong node accepted, or completion declared with a gap, leads to a post-state-root mismatch at block execution (a node-local liveness failure on chain 61, requiring re-sync — not a chain split).

## Key insight

Trie nodes are **content-addressed** (identified by their keccak256 hash, independent of which state root references them). A newer, currently-servable root shares the vast majority (~99.9%) of the *deep* trie nodes with the older root, because deep state changes little block-to-block. So a node missing under the old, no-longer-servable root can be fetched **by its path from a currently-servable root** and, once stored content-addressed, it satisfies the old root too.

## Decision

Split the single root into two roles:

> **The WALK root (`stateRoot`) stays fixed for the duration of a walk and is the sole basis of the completeness decision. The SERVE root (`serveRoot`) — used only to fetch missing nodes from peers — advances independently to track the current serve window. Completeness is judged exclusively by the walk finding zero missing against the fixed walk root.**

1. **Fixed walk root.** `stateRoot` keeps its existing semantics; it is read by the walk seeds, `isComplete`, the `verificationPassComplete` chokepoint, `markComplete`, and the scoped-verification `healedPathsRoot` tag. The new code never mutates it. The walk is a pure local read needing no peers, so it runs to completion regardless of serve windows. This is the durable generalization of hold-pivot (PR #1357): the walk root is held even across genuine serve-window aging, because healing no longer depends on it being servable.

2. **Advancing serve root.** A new `serveRoot` var (initialized to `stateRoot`) is read only at the `GetTrieNodes` build site, and only when enabled: `rootHash = if (decoupledHealServeRoot) serveRoot else stateRoot`. It advances only via a new, side-effect-free `HealingServeRootRefresh(newServeRoot)` message — which sets `serveRoot` and clears the FR-006 attempt counters and **nothing else** (it must not touch the walk root, frontier, `verificationPassComplete`, or re-seed). It must NOT be confused with `HealingPivotRefreshed`, which deliberately mutates the walk root and resets completeness state.

3. **Serve-root source (D4 = newest-servable).** `SNAPSyncController` requests a newest-servable root (`networkBest − 64`) from `SyncController` via a dedicated `RequestHealingServeRoot`/`HealingServeRoot` bootstrap slot (distinct from `StorageRecoveryActor`'s recent-root requester and from the walk-root-mutating pivot-refresh path), and pushes it to the coordinator as `HealingServeRootRefresh`. The refresh fires on the existing ~1s healing tick, gated so it only requests when the serve root has drifted > 2×margin behind head (never per-block). On a None/failed reply the controller **keeps the current serve root** (degrades to coupled rather than adopting an invalid root).

4. **The content-hash check is the load-bearing safety guardrail (FR-004).** SNAP `GetTrieNodes` is *path-addressed*, so a serve root may resolve a requested path to a **different-content node** than the walk root expects. The existing check in `handleResponse` — accept a returned node **iff** `keccak256(node)` equals a requested task hash (the walk root's expectation) — makes this safe: a mismatching node fails the check and is **dropped** (never stored, never counted), and the task stays pending. This check is preserved byte-for-byte and **must never be weakened or bypassed on any decoupled path**. It is the single guardrail that converts a path-mismatch into a safe drop-and-retry rather than a corrupting accept.

5. **No false completion, no silent infinite retry (D5 = surface-only, FR-006).** A node no servable root can supply keeps being retried as the serve root advances. A per-task attempt counter (`healAttempts`, bounded by the pending set, cleared on every serve-root advance) surfaces a stuck task via log + metric after a bounded threshold (`decoupled-heal-max-attempts-no-refresh`, default 12) — **observation only**. `HealingForceComplete` is neutralized under decoupling: it may not declare `StateHealingComplete` while any task is unsatisfied. Completion is impossible to declare falsely because it requires the walk to find zero missing against the walk root.

6. **Config + fallback (FR-008).** `sync.snap-sync.decoupled-heal-serve-root` (default `true`). When off, `serveRoot` is unused and the fetch reads `stateRoot` — byte-identical to pre-spec-004. In-memory only; safe to flip without migration; a restart re-initializes `serveRoot` to the walk root (coupled until the first refresh).

## Byte-for-byte completion parity (FR-007)

Parity holds by construction: `coverage(complete) = the walk finds zero missing against stateRoot`. The serve root supplies only node *bytes*, each verified to equal the walk root's expected hash before storage. Therefore the same final set of stored, content-verified nodes is reached regardless of which root sourced them ⇒ the same walk result ⇒ the same `StateHealingComplete` decision, the same persisted CF `g` completeness marker, and the same state root as the coupled single-root path. No new completion site and no new marker set-point are introduced.

## Consequences

- A completeness walk that takes many multiples of the serve window now completes (the deep gaps heal from whatever root is currently servable), unblocking post-SNAP healing on slow / peer-scarce ETC nodes.
- No EVM / gas / RLP / block-validation / Ethash / ECIP-1017 code is touched; ETC PoW and block-number fork dispatch are unaffected; the mechanism is chain-agnostic sync-layer.
- A residual *liveness* risk remains for shallow account-trie nodes that change nearly every block (a path that differs in every servable root). The gap set after SNAP is dominated by *deep* storage nodes (high cross-root sharing), so this is rare; FR-006 surfaces it rather than hiding or force-completing it. If shallow-gap liveness is ever observed, the serve-root selection can be switched from newest-servable to oldest-still-servable (maximal sharing) — a documented tuning lever (research D4).
- Requires a build + one redeploy; the next walk after deploy runs to completion. It does not rescue an already-running stuck walk.

## Alternatives considered

- **Raw-hash `GetTrieNodes`** (root-independent fetch): immune to the path-mismatch entirely, but the ETC SNAP protocol is path-addressed (matching core-geth/Besu) — changing the wire format is out of scope and an interop risk.
- **Pin an archive snap peer that serves the held root indefinitely**: a valid operational unblock, but depends on having such a peer; this change fixes the client-side deadlock regardless of peer archive depth.
- **Force-complete after N failed attempts**: rejected — it would declare success with a real gap (consensus-unsafe). Superseded by the surface-only D5 behavior.
- **Mutate `stateRoot` for the fetch and restore it**: rejected — races the walk and breaks the parity argument.
