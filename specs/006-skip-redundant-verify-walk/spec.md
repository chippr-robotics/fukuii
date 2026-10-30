# Feature Specification: Skip the Redundant Second Verification Walk on a Clean Post-SNAP Heal

**Feature Branch**: `006-skip-redundant-verify-walk`

**Created**: 2026-06-19

**Status**: Superseded by 022 (2026-10-30). Shipped (14 of 18 tasks; CON-012), then replaced: spec 022 FR-012 removes the verification walk as a completion precondition altogether (022 FR-019).
**Input**: User description: "Eliminate the redundant second full-trie verification walk on a clean post-SNAP heal, so a node that finishes its rebuild walk finding zero missing nodes (and having healed nothing) declares StateHealingComplete after ONE walk instead of two — halving clean-node post-SNAP completion time (~30-40h → ~16-20h on ETC mainnet) with byte-for-byte identical completion semantics."

**Closed** (2026-10-30):
- Reason: spec 022 FR-019 names this spec superseded.
- Lives on in: 022 FR-013's background audit.

## Overview

After SNAP state download and the post-SNAP heal, a node must **prove the state trie is complete** before it declares healing done and starts following the chain. When a node restarts with a persisted healing frontier but no completeness marker (the common case on a node that restarted mid-heal), it runs a full-trie **rebuild walk** to re-derive the missing-node frontier from on-disk reality. On a trie that is already complete, that rebuild walk finds **zero missing nodes and heals nothing** — yet the node does **not** declare completion. Instead, a few minutes later a watchdog force-starts a **second** full-trie walk (a verification pass) that traverses the identical trie, again finds zero missing, and only then declares `StateHealingComplete`.

The two walks are the **same traversal**. On the ETC mainnet state trie (~90M+ nodes) each walk takes ~16-20 hours, so the redundant second walk **doubles** the post-SNAP completion time (~30-40h instead of ~16-20h) — on the critical path to a usable node, for no completeness benefit.

A research investigation recovered the design intent: the verification walk exists to guard against a **shallower** discovery path (the inline per-healed-node child discovery) that can under-report missing storage sub-tries. But the **rebuild walk is itself the full traversal** that the verification was meant to provide — when the rebuild walk is the deciding walk and it found everything present and healed nothing, the verification walk is genuinely redundant.

This feature lets the node declare completion after the rebuild walk **when that walk was genuinely clean** (zero missing found, nothing healed, against the current root), routing the declaration through the **same** completion path so the outcome — the completion decision, the persisted completeness marker, and the resulting state root — is **byte-for-byte identical** to the two-walk sequence. Because this changes when the node trusts its state as complete, it is **consensus-adjacent** and MUST follow the `forge` protocol with byte-for-byte completion parity as the binding invariant.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A clean restarted node finishes its post-SNAP heal in one walk, not two (Priority: P1)

A node operator restarts a node that had nearly finished its post-SNAP heal. The trie on disk is already complete. The node runs one full rebuild walk, finds zero missing nodes, and declares healing complete — transitioning to chain-following in roughly **half** the time it takes today, because it no longer runs a second, identical verification walk.

**Why this priority**: This is the entire value of the feature — it removes a full redundant ~16-20h traversal from the critical path to a synced node, with no change to the completion outcome. On a peer-scarce node that restarts mid-heal (a frequent reality), halving the completion time is the difference between coming up the same day versus the next.

**Independent Test**: On a node whose trie is complete (or restored to completeness), restart so it runs the rebuild walk; confirm that when the rebuild walk finishes with zero missing and zero healed, the node declares completion immediately — without a second full-trie walk — and reaches the same completion decision and persisted marker as the two-walk path.

**Acceptance Scenarios**:

1. **Given** a restarted node whose persisted frontier has no completeness marker and whose trie is fully present, **When** the rebuild walk finishes having found zero missing nodes and healed nothing against the current root, **Then** the node declares `StateHealingComplete` after that single walk and does not start a second verification walk.
2. **Given** the same node, **When** completion is declared, **Then** the transition to regular sync proceeds with no `MissingRootNode` error at subsequent block import.

### User Story 2 - Completion is never declared while a node is missing, and is byte-for-byte identical to the two-walk outcome (Priority: P1)

A node operator must be able to trust that "healing complete" means the state trie is genuinely whole, and that declaring it one walk sooner does not change *what* is declared. The early declaration must only fire when the deciding walk provably found everything present against the current root; in every other case the node must still run the verification walk exactly as today.

**Why this priority**: Equal-highest with User Story 1. The optimization is acceptable only if it is provably as safe as the two-walk sequence. A single false "complete" corrupts the node's view of consensus state (a state-root mismatch at block execution, a wedged node). This is the consensus-critical guardrail that gates the feature.

**Independent Test**: Construct cases where the early declaration must NOT fire — a rebuild that discovers a missing node, a rebuild that healed nodes, and a stale rebuild completion arriving after the root changed — and confirm the node does not declare completion early in any of them; and confirm that for a genuinely clean trie the early-declared completion produces an identical completion decision, marker, and state root to the two-walk path.

**Acceptance Scenarios**:

1. **Given** a rebuild walk that discovers at least one missing node (frontier non-empty), **When** the walk finishes, **Then** the node does NOT declare completion early — it queues/heals the missing node and the verification path still runs.
2. **Given** a rebuild walk during which nodes were healed, **When** the walk finishes, **Then** the node does NOT declare completion early — the post-heal verification still runs.
3. **Given** an in-flight rebuild walk whose root was superseded by a pivot refresh to a different root, **When** the now-stale rebuild completion arrives, **Then** the node does NOT declare completion against the new root.
4. **Given** the same genuinely clean trie, **When** completion is reached via the early single-walk path vs. the two-walk path, **Then** the `StateHealingComplete` decision, the persisted completeness marker, and the resulting state root are byte-for-byte identical.

### User Story 3 - The verification walk still guards the cases it was built for (Priority: P2)

A node operator (and a reviewer auditing consensus safety) must be confident that eliminating the *redundant* walk does not remove the verification walk's real protection: the case where the deciding walk was the shallow inline discovery (which can under-report missing storage sub-tries), not a full rebuild.

**Why this priority**: Preserves the original safety guarantee (Chesterton's Fence). The early declaration applies narrowly — only to a clean full rebuild — so the verification walk continues to run on every path where it actually catches something. Important for correctness assurance, but the core value (US1) and the binding safety (US2) already encode it.

**Independent Test**: Confirm that on a heal that completed via the inline discovery path (not a clean full rebuild) the verification walk still runs before completion, exactly as today; and that the early-completion path is reachable only from a clean full rebuild.

**Acceptance Scenarios**:

1. **Given** a heal that drained its inline tasks without a clean full rebuild establishing completeness, **When** the node checks for completion, **Then** the verification walk still runs before `StateHealingComplete` (unchanged behavior).
2. **Given** the early-completion path is not taken (any guard condition unmet), **When** the node is idle post-walk, **Then** the existing watchdog still force-starts the verification walk exactly as today (no regression, no double-start).

### Edge Cases

- **Rebuild finds missing nodes**: MUST NOT declare completion early — the missing nodes are healed and completeness is re-checked.
- **Rebuild healed nodes during the walk**: MUST NOT declare completion early — a node healed mid-walk may have introduced structure the deciding walk did not cover, so verification still runs.
- **Stale completion after a root change** (pivot refresh to a different root while the walk was in flight): MUST NOT declare completion against the new root — the early declaration is valid only when the finished walk's root matches the node's current root.
- **A guard condition is unmet for any reason**: the node falls back to today's behavior — the watchdog force-starts the verification walk — so the feature can never *prevent* completion, only reach it sooner when provably safe.
- **Restart after early completion**: the persisted completeness marker is set the same way it is today (it is already written on the clean rebuild path before any verification), so future-restart behavior is unchanged.
- **An in-flight asynchronous node-write/flush at rebuild completion**: MUST NOT declare completion until the write path is settled, so the marker is never written ahead of durable state.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: When a full rebuild walk finishes having found **zero missing nodes**, healed **zero nodes**, with no outstanding frontier or in-flight work, and against the node's **current** root, the system MUST declare `StateHealingComplete` after that single walk, without starting a second verification walk.
- **FR-002**: The early completion MUST be reached through the **same single completion path** used today (the same completeness-marker write and the same `StateHealingComplete` signal), so that the completion decision, the persisted completeness marker, and the resulting state root are **byte-for-byte identical** to the two-walk sequence for the same trie state. This is strictly redundant-work elimination with no semantic divergence.
- **FR-003**: The system MUST NOT declare completion early if the rebuild walk found any missing node, healed any node, has any outstanding frontier or in-flight request, or finished against a root that is no longer the node's current root. In any such case, behavior MUST be exactly as today.
- **FR-004**: To evaluate the early-completion condition safely, the system MUST make the rebuild walk's outcome observable at the completion-decision point — specifically the count of missing nodes the walk emitted and the root the walk traversed — rather than inferring them indirectly.
- **FR-005**: The early-completion condition's "current root" guard MUST be explicit (the finished walk's root compared against the node's current root), and MUST NOT rely on an incidental side effect of unrelated code paths to remain safe across future changes.
- **FR-006**: The existing watchdog that force-starts the verification walk MUST remain intact and unchanged as a fallback: if early completion does not fire for any reason, the node MUST still reach completion via the verification walk exactly as today, with no double-start.
- **FR-007**: The verification walk MUST continue to run on every completion path that is **not** a clean full rebuild (e.g., completion reached via the shallow inline discovery), preserving the protection that walk was introduced to provide.
- **FR-008**: The change MUST be safe by construction with respect to the bounded traversal: it MUST NOT introduce any path by which a missing node is treated as present. (The rebuild walk's missing-node detection is independent of the bounded visited set, and the second walk shares the identical detection, so it adds no detection capability the rebuild lacks.)
- **FR-009**: The feature MUST require no data migration and MUST be safe on a node with or without a persisted frontier/marker.

### Key Entities *(include if feature involves data)*

- **Rebuild walk outcome**: the result of the full rebuild traversal — at minimum, whether it found any missing node and which root it traversed — made observable at the completion-decision point so the early-completion condition can be evaluated soundly.
- **Clean-rebuild completion condition**: the conjunction that makes early completion sound — zero missing found, zero healed, no outstanding work, and walk-root equal to the current root.
- **Completeness proof / marker**: the existing basis for `StateHealingComplete`; this feature reaches it one walk sooner without changing what it asserts.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a restarted node whose trie is already complete, post-SNAP heal completion is reached after **one** full-trie walk instead of two — roughly **halving** the post-SNAP completion time (e.g., ~16-20h instead of ~30-40h on a large-state node).
- **SC-002**: Zero false completions: completion is never declared while any node is missing anywhere in the trie, across all guard-unmet cases (missing found, healed > 0, stale root, outstanding work).
- **SC-003**: Byte-for-byte parity: for the same trie state, the `StateHealingComplete` decision, the persisted completeness marker, and the state root after an early-declared completion are identical to those after the two-walk sequence.
- **SC-004**: No regression on non-clean paths: a heal that did not complete via a clean full rebuild still runs the verification walk before completion, exactly as today; and when early completion does not fire, the watchdog still reaches completion with no double-start.
- **SC-005**: The change requires no configuration to obtain the benefit (it is unconditional on the clean path) and no data migration.

## Assumptions

- The rebuild walk and the verification walk are the **same full traversal** over the same on-disk trie and the same root; therefore a clean rebuild result is the same evidence the verification walk would produce. This is the load-bearing assumption that makes skipping the second walk byte-parity-safe, and it is to be confirmed during planning under the `forge` protocol.
- Present trie nodes are content-correct (content-hash-verified when stored during SNAP reconstruction and healing), so the only completeness question is whether the whole trie is present — which a clean full rebuild answers. This feature does not re-verify present-node content.
- The early-completion condition is intentionally **conservative** for the first landing: it requires that the rebuild healed nothing (matching the existing clean-idle completion arm), accepting conservative false-negatives (it may occasionally still run the verification walk) but never false-positives (it never declares completion unsoundly). Widening the condition is deferred until validated.
- The feature is **unconditional** on the clean path (no new configuration flag): it produces a byte-identical completion outcome, so there is nothing to toggle; the existing watchdog is the built-in fallback. A configuration flag may be added only if operability parity with the existing heal-tuning family is required.
- This is **consensus-adjacent** and MUST be reviewed and validated under the `forge` protocol; byte-for-byte completion parity (FR-002) and the never-false-complete guarantee (FR-003/FR-008) are the binding correctness invariants, consistent with the constitution's requirement that the node never declare success on incomplete state.
- The validation target is the ETC/Mordor chain (chain-ID 61); the mechanism is a chain-agnostic sync-layer change and touches no consensus-critical EVM/gas/RLP/block-validation code.
- This composes with the post-SNAP heal performance/correctness line (`specs/002-bfs-heal-performance`, `specs/003-scoped-heal-verification`, `specs/004-decoupled-heal-serve-root`, `specs/005-subtree-complete-verification`). It is **independent of and complementary to** spec 005 (subtree-complete records): it has no dependency on spec 005 and does not conflict with it. **Out of scope**: changing the watchdog itself; fixing the shallow inline-discovery path (the verification walk's protection there is preserved, not removed); the deferred-merkleization architecture.
- Requires a build and one redeploy; it **cannot** help a currently in-flight heal walk (forfeiting that walk would cost more than it saves) — the next heal benefits.
