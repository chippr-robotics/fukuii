# Feature Specification: Scoped Post-Heal Verification

**Feature Branch**: `003-scoped-heal-verification`

**Created**: 2026-06-15

**Status**: Superseded by 022 (2026-10-30). Scoped verification walks are no longer a completion gate; spec 022 FR-012/FR-019 replace them with the present-means-complete invariant and a background audit.
**Input**: User description: "Scope post-heal verification to only the healed subtrees instead of a full-root re-walk."

**Closed** (2026-10-30):
- Reason: spec 022 FR-019 names this spec superseded; the verify-walk gate is removed by 022 slice 5.
- Lives on in: the background audit of 022 FR-013.

## Overview

After post-SNAP state healing repairs a set of missing trie nodes, the node must prove the state trie is complete before declaring `StateHealingComplete` and transitioning to regular sync. Today that proof is a **verification BFS that re-seeds the state root and re-walks the entire trie** (~90M+ nodes on ETC mainnet, ~16-20h on slow storage) — even though only a small set of nodes (typically ~118 deep-storage gaps) was actually healed. The whole-trie re-traversal is almost entirely wasted work: it re-reads regions the immediately-preceding rebuild walk already proved complete (0 missing), solely to re-confirm a handful of healed subtrees.

This feature makes the post-heal verification re-traverse **only the subtrees rooted at the nodes that were healed this round**, while preserving the exact completeness guarantee — turning a multi-hour pass into seconds, with a mandatory fall-back to full-root verification whenever the "rest of the trie is already proven complete" precondition does not hold.

This is a **consensus-adjacent** change (it changes what the completeness gate verifies before the node trusts its state). It MUST follow the `forge` protocol and preserve byte-for-byte state correctness.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Heal completes in seconds, not a second full-day walk (Priority: P1)

A node operator runs an ETC node that finished SNAP-downloading state and is in the post-SNAP healing phase. The node finds and heals the small set of genuinely-missing trie nodes (the deep-storage gaps). Today, after those heal, the node embarks on another ~16-20h whole-trie verification walk before it can start following the chain tip. With this feature, the post-heal verification re-checks only the healed subtrees and completes in seconds, so the node reaches regular sync far sooner.

**Why this priority**: This is the entire value of the feature — eliminating the dominant remaining cost (the post-heal full re-walk) on the critical path to a synced, chain-following node. Without it, every heal round (and every restart that re-triggers verification) pays the full-trie cost.

**Independent Test**: On a node whose rebuild walk completed with full coverage and a known small set of healed nodes, trigger the completion gate and confirm the verification walk visits only the healed subtrees and reaches `StateHealingComplete` in a fraction of the time a full-root walk would take — while the resulting state is identical.

**Acceptance Scenarios**:

1. **Given** a rebuild walk that fully traversed the trie with zero missing nodes outside the healed regions, **and** a set of N healed nodes, **When** the completion gate runs, **Then** the verification re-traverses only the subtrees rooted at those N healed paths and declares completion without re-reading the rest of the trie.
2. **Given** a healed node whose subtree still contains a deeper missing descendant, **When** the scoped verification runs, **Then** it discovers that descendant, emits it for healing, and does not declare completion until the healed subtrees verify clean.
3. **Given** zero nodes were healed this round, **When** the completion gate runs, **Then** no verification walk is performed and the trie is treated as already complete (unchanged from today's idle-arm behavior).

### User Story 2 - Never declare complete with an unverified gap (Priority: P1)

A node operator must be able to trust that when the node says healing is complete, the state trie is genuinely whole — otherwise block execution will later hit a missing node, fail with a state-root/`MissingRootNode` error, and the node wedges or forks. The scoped verification must never narrow its scope in a way that silently skips an un-walked region.

**Why this priority**: Equal-highest with US1. The optimization is only acceptable if it is provably as safe as the full walk. A single false "complete" corrupts the node's view of state. This is the consensus-critical guardrail that gates the whole feature.

**Independent Test**: On a node whose preceding rebuild walk was interrupted/partial (no full-coverage guarantee), trigger the completion gate and confirm the system performs the **full-root** verification (not scoped), and does not declare completion based on the healed subtrees alone.

**Acceptance Scenarios**:

1. **Given** the rebuild walk did not establish full-trie coverage (interrupted, restarted without the coverage marker, or otherwise unproven), **When** the completion gate runs, **Then** the system falls back to the full-root verification and does NOT use the scoped path.
2. **Given** a scoped verification that completes clean, **When** the node subsequently executes blocks that touch the previously-healed and the never-healed regions, **Then** no missing-node error occurs (the completeness guarantee held).
3. **Given** the same healed state, **When** completion is reached via the scoped path vs the full-root path, **Then** the resulting state root and on-disk completeness marker are byte-for-byte identical.

### User Story 3 - Operators can see and control the scoped path (Priority: P2)

A node operator diagnosing or auditing a heal wants to confirm whether the scoped verification engaged, how much work it saved, and to be able to force the conservative full-root verification if needed.

**Why this priority**: Operability and trust. Useful for validating the feature in production and for incident response, but the feature delivers its core value without it.

**Independent Test**: Run a heal to completion with scoping enabled and confirm a log/metric reports that the scoped path was taken, the count of healed subtrees verified, and the time taken; then disable via config and confirm the node uses the full-root verification.

**Acceptance Scenarios**:

1. **Given** scoped verification engaged, **When** healing completes, **Then** the node emits an observable signal indicating the scoped path was used, the number of healed subtrees re-verified, and the elapsed time.
2. **Given** the scoped-verification feature is disabled by configuration, **When** the completion gate runs, **Then** the node performs the full-root verification (today's behavior) and logs that scoping was disabled.

### Edge Cases

- **Healed node with deeper missing descendants**: the scoped walk MUST recurse fully into each healed subtree, not just confirm the healed node itself, so a gap below a healed node is still found.
- **Pivot root changed during the heal round**: if the healing pivot rolled (genuine all-peers-stateless) so the healed paths were recorded against a now-superseded root, the system MUST either re-validate the healed paths against the current root or fall back to full-root verification — never verify against a stale root.
- **Restart mid-verification**: if the node restarts before completion, the healed-paths set (held in memory) is lost; the system MUST safely fall back to whatever the persisted state supports (full-root verification or resume) and never declare completion on an unproven basis.
- **Rebuild walk found missing nodes spread across many subtrees**: the scoped set may be large; the verification work scales with the healed set, which must remain bounded and not degrade to a full walk silently.
- **A healed path that is itself no longer present** (e.g., superseded by a pivot delta): the scoped walk must treat it as a gap to re-discover, not crash.
- **Zero-coverage / fresh node**: if no rebuild walk has run, there is no "rest is complete" basis; full-root verification is required.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST record, for each node healed during a heal round, the trie path that locates it (the "healed-path"), associated with the state root the heal targeted, forming a bounded "healed-paths set" for the round.
- **FR-002**: When the completion gate would otherwise start a full-root verification walk (work was done this round and a clean verification has not yet passed), the system MUST seed the verification traversal from the healed-paths set, re-traversing only the subtrees rooted at those paths.
- **FR-003**: The scoped verification MUST traverse each healed subtree to completion — recursing into every descendant — so that any still-missing node anywhere beneath a healed node is discovered.
- **FR-004**: The scoped verification MAY declare the trie complete ONLY when BOTH (a) the preceding rebuild walk established that the entire trie was traversed with zero missing nodes outside the healed regions (the full-coverage precondition), AND (b) the scoped traversal of all healed subtrees finds zero missing nodes.
- **FR-005**: When the full-coverage precondition (FR-004a) cannot be established (the rebuild walk was partial, interrupted, not yet complete, or its coverage is otherwise unproven), the system MUST fall back to the full-root verification and MUST NOT declare completion via the scoped path.
- **FR-006**: A scoped verification that discovers any missing node MUST emit it for healing and remain incomplete; once those heal, verification MUST re-run over the (possibly extended) healed-paths set until it finds zero missing — it MUST NOT declare completion while any healed subtree has an outstanding gap.
- **FR-007**: The completeness outcome reached via the scoped path MUST be identical to the outcome the full-root verification would produce for the same healed state: the same `StateHealingComplete` decision, the same persisted completeness marker, and a byte-for-byte identical state root. The scoped path is strictly an optimization with no semantic divergence.
- **FR-008**: The system MUST provide a configuration switch to enable or disable scoped verification; when disabled, the node MUST perform the existing full-root verification. The switch MUST be safe to flip without data migration.
- **FR-009**: If the healing pivot root changed during the heal round, the system MUST NOT verify healed paths against a superseded root; it MUST re-anchor the healed-paths set to the current root or fall back to full-root verification.
- **FR-010**: The system MUST emit an observable signal (log and/or metric) when the scoped path is taken, reporting at minimum: that scoping engaged, the number of healed subtrees verified, and the elapsed verification time, so operators can confirm engagement and savings.
- **FR-011**: The healed-paths set MUST be bounded in memory; if the number of healed nodes in a round exceeds a safe bound, the system MUST fall back to full-root verification rather than risk unbounded memory growth.

### Key Entities *(include if feature involves data)*

- **Healed-path**: the trie path (location) of a single node that was healed in the current round, plus the state root it was healed against. The verification seed unit.
- **Healed-paths set**: the bounded collection of healed-paths accumulated during a heal round; the scope of the scoped verification.
- **Full-coverage precondition**: the established fact that the preceding rebuild walk traversed the full trie and found zero missing nodes outside the healed regions — the basis that lets the rest of the trie be trusted as complete without re-walking.
- **Verification scope**: the mode of the post-heal verification — `scoped` (seeded from the healed-paths set) or `full-root` (seeded from the state root, today's behavior and the fallback).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a node whose rebuild walk completed with full coverage and that healed a typical small gap set (~100-200 nodes), the post-heal verification completes in under 1 minute, versus the multi-hour full-root re-walk it replaces.
- **SC-002**: Zero false completions: across all completions reached via the scoped path, subsequent block execution never encounters a missing state node attributable to an unverified region (no state-root/`MissingRootNode` failures caused by scoping).
- **SC-003**: 100% safe fallback: in every case where the full-coverage precondition is not established, the node uses full-root verification — no scoped completion is ever declared without the precondition.
- **SC-004**: Byte-for-byte parity: for the same healed state, the state root and persisted completeness marker after a scoped-verified completion are identical to those after a full-root-verified completion.
- **SC-005**: The total wall-clock time from "last gap healed" to `StateHealingComplete` is reduced by at least 95% on a large-state node, compared to the full-root verification baseline.

## Assumptions

- The preceding rebuild walk already traverses the full trie and emits zero frontier for subtrees that are already present; therefore "the rest of the trie is complete" is established by the rebuild, and only the healed regions require re-verification. (This is the load-bearing assumption that makes scoping sound; FR-004/FR-005 enforce it.)
- Trie nodes are content-addressed and the healed subtrees can be walked from local storage without network access, so scoped verification is a local read operation.
- This feature builds on the hold-pivot livelock fix (`#1357`, staging `0.7.13`), which keeps the healing pivot stable across stagnations so the healed-paths set generally stays valid against one root for the duration of a heal round.
- The typical healed set is small (tens to low hundreds of nodes) and bounded; FR-011 guards the pathological case.
- This is consensus-adjacent and MUST be reviewed and validated under the `forge` protocol; the completeness guarantee (FR-007) is the binding correctness invariant, consistent with the constitution's requirement that consensus-critical behavior be byte-for-byte deterministic and never declare success on incomplete state.
- Default enablement of the configuration switch (FR-008) is deferred to planning; a safe default (enabled with mandatory fallback, or opt-in until validated in production) will be chosen during `/speckit-plan`, since both are reasonable and the fallback (FR-005) keeps either choice correct.
- This feature is the next increment in the post-SNAP BFS heal performance line of work (prior: `specs/002-bfs-heal-performance`); it removes the residual full-trie verification cost that remained after that work and after the livelock fix.
