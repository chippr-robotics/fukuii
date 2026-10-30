# Feature Specification: Decoupled Heal Serve-Root (Walk-Root / Serve-Root Separation)

**Feature Branch**: `004-decoupled-heal-serve-root`

**Created**: 2026-06-17

**Status**: Done (2026-10-30). Implemented; 13 main-source sites cite it and ADR CON-010 records the decision. One tasks.md box was never ticked.
**Input**: User description: "Decouple the local rebuild/verification walk from the serve-window-bound heal so the post-SNAP heal can complete on a slow / peer-scarce node."

**Closed** (2026-10-30):
- Reason: shipped. Decoupling the heal root from the serve root is in `blockchain/sync/snap/` (see `docs/adr/consensus/CON-010-decoupled-heal-serve-root.md`).
- Note: spec 022 keeps the decoupling and changes only how completion is declared.

## Overview

After SNAP state download, the node must prove the state trie is complete before it can follow the chain. It does this with a **completeness walk** (a local read-only BFS traversal of the trie) that finds any missing nodes, **heals** each missing node by fetching it from a peer, and declares completion only when a walk finds zero missing.

Today the walk and the heal are pinned to the **same single state root**. That coupling is fatal on a slow or peer-scarce node: the walk takes **hours** (a full ETC-mainnet trie traversal is ~16-20h on slow storage, CPU-bound), but peers only serve snap data for a given root for roughly the most recent ~128 blocks (~28 minutes at ~13s/block). By the time the slow walk reaches the deep storage gaps, the pinned root has **aged out of every (non-archive) peer's serve window**, so the heal fetch times out and those gaps can never be filled — the node churns or stalls at ~99% complete and never finishes. This was observed live: heal stuck at ~99% (27,346 nodes healed), with serve-window-aging rolls clustering at 26-27 minute intervals (matching the ~28-minute window).

The key insight that makes the fix sound: **trie nodes are content-addressed** (identified by their content hash, independent of which state root references them), and a newer, currently-servable root **shares the vast majority of the deep trie nodes** with the older root (deep state changes little block-to-block). Therefore a node that is missing under the old, no-longer-servable root can be fetched by its hash/path from a **currently-servable** root and, once stored, it satisfies the old root too.

This feature **separates the two roles of "the root"**: the **walk root** stays fixed for the duration of a walk (a pure local read that needs no peers, so it can run to completion regardless of serve windows), while the **serve root** — the root used to fetch missing nodes from peers — tracks whatever is currently servable and advances with the chain. The walk's wall-clock time no longer has to fit inside a single serve window, so the walk completes, the completeness marker is set, and the node reaches regular sync.

This is **consensus-adjacent** (it changes how the completeness gate sources nodes before the node trusts its state). It MUST follow the `forge` protocol and preserve byte-for-byte completion parity with the existing single-root behavior.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Heal completes on a slow / peer-scarce node (Priority: P1)

A node operator runs an ETC node whose post-SNAP completeness walk takes far longer than peers will serve any single state root. Today the node gets stuck at ~99% forever: the walk outlives the serve window, the deep gaps can't be healed against the aged root, and the node never declares completion. With this feature, the walk stays pinned to one fixed root and runs to completion, while the missing nodes it finds are fetched from whatever root is currently servable — so the gaps fill, the walk reaches zero-missing, and the node declares `StateHealingComplete` and starts following the chain.

**Why this priority**: This is the entire value of the feature — it is the difference between a node that can finish post-SNAP healing on slow/peer-scarce hardware and one that cannot finish at all. It removes the structural serve-window-vs-walk-time deadlock that the prior performance (002) and livelock (hold-pivot) work did not address.

**Independent Test**: On a node whose walk duration exceeds the peer serve window, with a small set of genuinely-missing nodes that are obtainable from a currently-servable root, run the completion gate and confirm the walk root stays fixed for the whole walk, the missing nodes heal from the advancing serve root, and the node reaches `StateHealingComplete` — where the coupled (single-root) behavior would have stalled.

**Acceptance Scenarios**:

1. **Given** a completeness walk that will take longer than peers serve a single root, **and** a set of missing nodes that a currently-servable root can supply, **When** the walk runs, **Then** the walk root does not change due to serve-window aging, the missing nodes are fetched from the (advancing) serve root and stored, and the walk eventually finds zero missing and declares completion.
2. **Given** the serve root ages out mid-walk as the chain advances, **When** the next heal fetch is issued, **Then** it targets the new currently-servable root (not the aged one), and healing continues without re-seeding or abandoning the walk.
3. **Given** the same node with the feature disabled, **When** the walk outlives the serve window, **Then** the node exhibits today's coupled behavior (stall/roll churn) — confirming the feature is the differentiator.

### User Story 2 - Never declare complete with a missing or wrong-content node (Priority: P1)

A node operator must be able to trust that "healing complete" means the state trie is genuinely whole and every healed node is the correct content. Sourcing nodes from a *different* root than the walk root must never let a wrong node be accepted, and must never let completion be declared while a required node is still missing.

**Why this priority**: Equal-highest with US1. The optimization is only acceptable if it is provably as safe as the single-root behavior. Accepting a wrong-content node, or declaring completion with an unfilled gap, corrupts the node's view of state and leads to a state-root mismatch at block execution. This is the consensus-critical guardrail.

**Independent Test**: Feed the heal path a peer response whose returned node does not hash to the requested hash and confirm it is rejected and not counted as healed; and run the completion gate with one required node unobtainable from any current root and confirm completion is NOT declared.

**Acceptance Scenarios**:

1. **Given** a peer returns a node whose content hash does not equal the requested hash, **When** the heal path processes it, **Then** the node is rejected, not stored, and not counted as healed.
2. **Given** a node required by the fixed walk root that no currently-servable root can supply, **When** the completion gate runs, **Then** completion is NOT declared and the node continues to be retried as the serve root advances.
3. **Given** the same final healed state, **When** completion is reached via the decoupled-heal path vs the coupled single-root path, **Then** the `StateHealingComplete` decision, the persisted completeness marker, and the resulting state root are byte-for-byte identical.

### User Story 3 - Operators can see and control the decoupling (Priority: P2)

A node operator diagnosing a heal wants to see which root the walk is anchored to versus which root heal fetches are currently using, how many nodes were healed from a root different than the walk root, and to be able to fall back to the conservative single-root behavior via configuration.

**Why this priority**: Operability and trust. Useful for validating the feature in production and for incident response, but the core value (US1) and safety (US2) are delivered without it.

**Independent Test**: Run a heal with the feature enabled and confirm an observable signal reports the fixed walk root, the current serve root, and the count of nodes healed via a differing serve root; then disable via config and confirm the node uses the single-root behavior and logs that decoupling is disabled.

**Acceptance Scenarios**:

1. **Given** decoupled healing is engaged, **When** healing runs, **Then** the node emits observable signals for the fixed walk root, the current serve root, the count of nodes healed via a serve root different from the walk root, and any node currently unservable by every root.
2. **Given** the feature is disabled by configuration, **When** the completion gate runs, **Then** the node uses the existing single-root (walk-root == serve-root) behavior and logs that decoupling is disabled.

### Edge Cases

- **Serve root advances during a walk**: as the chain moves, the serve root is refreshed to the latest servable root; in-flight heal requests issued against a now-stale serve root are retried against the fresh one. The walk root is unaffected.
- **A node no current root can supply** (pruned everywhere, or genuinely unique to the old root): it MUST keep being retried as the serve root advances, MUST NOT cause a false completion, and MUST NOT loop silently forever — after a bounded number of attempts it is surfaced (logged/escalated) so an operator can act.
- **Large divergence between walk root and serve root** (the walk root is far behind head): healing still works for every node the servable root shares (content-addressed); only a node that genuinely differs remains missing and is handled by the unservable-node path above.
- **Wrong-content peer response**: a returned node that does not hash to the requested hash is rejected, not stored, not counted as healed (no silent acceptance).
- **All peers stateless even for the servable root** (genuine peer outage): the walk root is still held; healing waits and retries when the serve root refreshes or peers return — the walk does not get re-seeded by this.
- **Restart mid-walk**: the in-memory walk state is lost; the node MUST fall back safely to whatever the persisted state supports (re-walk / today's behavior) and never declare completion on an unproven basis.
- **Chain not advancing / serve root cannot be refreshed**: if no newer servable root is available, the heal path falls back to using the best root it has (degrades to today's coupled behavior) rather than failing.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: During a heal round, the system MUST anchor the local completeness walk to a **fixed walk root** for the entire duration of that walk; the walk root MUST NOT be changed by peer serve-window aging.
- **FR-002**: The system MUST fetch the missing nodes the walk discovers from a **serve root** that is currently within peers' serve window, independently of the walk root.
- **FR-003**: The system MUST refresh the serve root to track the currently-servable window as the chain advances, so heal fetches always target a root peers can serve.
- **FR-004**: Before storing any node obtained via the serve root, the system MUST verify the node's content hash equals the requested node's hash; a mismatch MUST be rejected, not stored, and not counted as healed.
- **FR-005**: The completeness decision MUST remain "the local traversal of the fixed walk root finds zero missing nodes." Healing via the serve root supplies node content only; it MUST NOT change the meaning of completeness or the root against which completeness is judged.
- **FR-006**: A node required by the walk root that no currently-servable root can supply MUST continue to be retried as the serve root advances; the system MUST NOT declare completion while it remains missing, and MUST NOT retry silently forever — after a bounded threshold it MUST be surfaced (observable signal) for operator action.
- **FR-007**: The completion outcome reached via the decoupled-heal path MUST be byte-for-byte identical to what the coupled single-root path would produce for the same final healed state: the same `StateHealingComplete` decision, the same persisted completeness marker, and the same state root. Decoupling is strictly a node-sourcing optimization with no semantic divergence.
- **FR-008**: The system MUST provide a configuration switch to enable or disable decoupled healing; when disabled, the node MUST use the existing coupled (walk-root == serve-root) behavior. The switch MUST be safe to flip without data migration.
- **FR-009**: The feature MUST compose with the existing hold-pivot behavior and scoped verification: holding the walk root through serve-window aging is the intended generalization of hold-pivot, valid precisely because healing no longer depends on the walk root being servable.
- **FR-010**: The system MUST emit observable signals reporting at minimum: the fixed walk root, the current serve root, the count of nodes healed via a serve root different from the walk root, and any node currently unservable by every root.
- **FR-011**: The bookkeeping for tracking the serve root and outstanding heal requests MUST be bounded; refreshing the serve root MUST be a constant-time operation with no unbounded memory growth.

### Key Entities *(include if feature involves data)*

- **Walk root**: the fixed state root the local completeness traversal is anchored to for a heal round; the sole basis of the completeness decision.
- **Serve root**: the currently-servable state root (within peers' serve window) used to fetch missing nodes from peers; advances with the chain, decoupled from the walk root.
- **Missing node (content-addressed)**: a trie node identified by its content hash; the unit of healing. Its identity is root-independent, which is what makes cross-root sourcing sound.
- **Serve window**: the bounded range of recent blocks/roots for which peers will serve snap data (≈ the most recent ~128 blocks on ETC).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a node whose completeness walk takes longer than the peer serve window, the post-SNAP heal reaches `StateHealingComplete` and transitions to regular sync — an outcome that was not achievable with the coupled single-root behavior.
- **SC-002**: Zero false completions: completion is never declared while any node required by the walk root is still missing.
- **SC-003**: Byte-for-byte parity: for the same final healed state, the state root and persisted completeness marker after a decoupled-heal completion are identical to those after a coupled single-root completion.
- **SC-004**: Content integrity: 100% of nodes stored via the serve root match their requested content hash; zero wrong-content nodes are stored or counted as healed.
- **SC-005**: Walk duration no longer bounds completion: a completeness walk that takes several multiples of the serve window still completes, with no serve-window-aging event re-seeding or abandoning the walk.
- **SC-006**: Safe fallback: with the feature disabled, the node's behavior is identical to today's coupled single-root behavior.

## Assumptions

- A newer, currently-servable state root shares the vast majority (~99.9%) of the deep trie nodes with the held walk root, because deep state changes little block-to-block; therefore most missing nodes are obtainable from a recent servable root. This is the load-bearing assumption that makes decoupling effective; FR-006 guards the residual minority that genuinely differs.
- Trie nodes are content-addressed, so verifying that a returned node hashes to the requested hash is sufficient to accept it regardless of which root referenced it (FR-004).
- The completeness walk is a pure local read that needs no peers to run; only the healing of discovered gaps needs peers and the serve window — which is precisely why the two can be decoupled.
- This feature builds on and composes with the hold-pivot livelock fix (`#1357`, `heal-hold-pivot-on-stagnation`) and scoped verification (`specs/003-scoped-heal-verification`). The walk-root hold here is the durable generalization of hold-pivot; scoped verification continues to govern the post-heal verification scope.
- The default enablement of the configuration switch (FR-008) is deferred to planning; a safe default (enabled with the conservative single-root fallback intact) will be chosen during `/speckit-plan`, since the FR-008 fallback keeps either choice correct.
- This is consensus-adjacent and MUST be reviewed and validated under the `forge` protocol; the byte-for-byte completion parity (FR-007) and content integrity (FR-004) are the binding correctness invariants, consistent with the constitution's requirement that the node never declare success on incomplete or incorrect state.
- The validation target is the ETC/Mordor chain (chain-ID 61, PoW, ECIP-1017); the mechanism is a chain-agnostic sync-layer change and touches no consensus-critical EVM/gas/RLP/block-validation code.
- This is the next increment in the post-SNAP BFS heal line of work (prior: `specs/002-bfs-heal-performance`, `specs/003-scoped-heal-verification`); it removes the residual serve-window-vs-walk-time deadlock that remained after that work and after the hold-pivot fix. It requires a build and one redeploy; the subsequent walk would then run to completion.
