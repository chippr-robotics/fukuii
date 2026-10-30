# Feature Specification: Subtree-Complete Heal Verification (descend-and-stop, O(missing) completeness proof)

**Feature Branch**: `005-subtree-complete-verification`

**Created**: 2026-06-19

**Status**: Superseded by 022 (2026-10-30). Shipped (28 of 32 tasks; CON-011), then replaced: spec 022 FR-001/FR-003 make subtree completeness a write-order invariant instead of a verification pass (022 FR-019).
**Input**: User description: "Make post-SNAP heal verification O(missing-frontier) instead of O(whole trie) via a per-subtree-completeness invariant + descend-and-stop pruning, eliminating the ~16-20h full-trie verification walk."

**Closed** (2026-10-30):
- Reason: spec 022 FR-019 names this spec superseded.
- Lives on in: 022 slice 2's held set and atomic chain commit (#1684).

## Overview

After SNAP state download and the post-SNAP heal, a node must **prove the state trie is complete** before it declares healing done and starts following the chain. Today that proof is a **full-trie verification walk**: a breadth-first traversal that reads and decodes **every present node** in the ~90M-node ETC-mainnet state trie just to confirm none is missing — ~1,000-1,300 nodes/s, CPU/GC-bound, **~16-20 hours**. This whole-trie read is fukuii's *sole* completeness proof, and a fresh node is forced to do it at least once: the scoped verification (spec 003) cannot help, because it only engages after a prior full walk has already set the completeness marker.

A research comparison (geth, Nethermind, Besu, Erigon, Reth) found that **fukuii is the only major MPT client that reads the entire trie to verify completeness.** geth, Nethermind, and Besu (Forest) all use **descend-and-stop**: they walk from the state root and, at any reference whose node is **already present on disk and known subtree-complete**, treat it as a verified leaf and **do not descend into that subtree** — so completeness verification costs **O(missing-frontier + ancestors)** (minutes), not O(90M) (hours). (Erigon/Reth sidestep this with a flat-key-value state architecture that is not portable to fukuii's hash-keyed trie and is out of scope.)

This feature brings descend-and-stop verification to fukuii: establish a **durable per-subtree-completeness invariant** so that "a present node is known subtree-complete" can be trusted, then make the completeness verification **prune** any such subtree instead of re-reading it. The completeness outcome (the decision, the persisted marker, the resulting state root) MUST remain **byte-for-byte identical** to today's full-trie verification — this is strictly a cost optimization. Because this changes what the node trusts as proof of a complete state before following the chain, it is **consensus-adjacent** and MUST follow the `forge` protocol with byte-for-byte parity as the binding invariant.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Fresh node proves completeness in minutes, not a ~20h re-walk (Priority: P1)

A node operator runs a fresh ETC node that has finished SNAP download and post-SNAP healing. Before it can follow the chain tip, it must prove the state trie is complete. Today this is a ~16-20h full-trie verification that re-reads every node. With this feature, the verification prunes every present, proven-complete subtree and visits only the missing frontier and its ancestors, finishing in **minutes** — so the node reaches a chain-following state far sooner.

**Why this priority**: This is the entire value of the feature — it removes the dominant remaining cost on the critical path to a synced node, the one cost the prior heal work (002/003/004) left untouched. It is the difference between a fresh ETC node taking an extra full day to come up versus minutes.

**Independent Test**: On a node whose trie is complete (or near-complete with a small known missing set) and whose subtree-completeness invariant is established, run the completeness verification and confirm it prunes present subtrees, visits only the missing frontier plus ancestors, and reaches the same completion decision in a small fraction of the time the full-trie walk takes.

**Acceptance Scenarios**:

1. **Given** a healed trie whose subtree-completeness invariant holds, **When** the completeness verification runs, **Then** it does not descend into any present, proven-complete subtree, visits work proportional to the missing-node count (not the trie size), and declares completion.
2. **Given** a trie with a small set of still-missing nodes, **When** the pruned verification runs, **Then** it descends into exactly the subtrees containing missing nodes, discovers every missing node, emits them for healing, and does not declare completion until they are filled.
3. **Given** the same node with the feature disabled, **When** verification runs, **Then** it performs the full-trie verification (today's behavior).

### User Story 2 - Never declare complete with a node hidden missing under a present parent (Priority: P1)

A node operator must be able to trust that "healing complete" means the state trie is genuinely whole. Pruning a present subtree is only acceptable if it is provably as safe as reading it. The verification must never skip a present node whose subtree still hides a missing descendant — doing so would declare a complete state with an undetected gap, leading to a state-root mismatch and a wedged or forked node at block execution.

**Why this priority**: Equal-highest with US1. The optimization is acceptable only if it is provably as safe as the full walk. A single false "complete" corrupts the node's view of consensus state. This is the consensus-critical guardrail that gates the whole feature.

**Independent Test**: Construct a trie where a present parent sits above a missing descendant whose subtree-completeness was never established, run the pruned verification, and confirm it descends into that parent (does not prune it), discovers the missing descendant, and does NOT declare completion.

**Acceptance Scenarios**:

1. **Given** a present node whose subtree-completeness has NOT been established, **When** the pruned verification reaches it, **Then** it descends into the subtree (does not prune) and finds any missing descendant.
2. **Given** a persistence interrupted by a crash between writing a subtree and recording it complete, **When** the node restarts and verifies, **Then** no node is treated as subtree-complete unless its subtree is genuinely present, and any partially-written subtree is descended into and reconciled.
3. **Given** the same healed state, **When** completion is reached via the pruned verification vs the full-trie verification, **Then** the `StateHealingComplete` decision, the persisted completeness marker, and the resulting state root are byte-for-byte identical.

### User Story 3 - Operators can see and control the pruning (Priority: P2)

A node operator diagnosing or auditing a heal wants to confirm that pruned verification engaged, how much work it saved (subtrees pruned vs nodes visited, elapsed time), and to be able to force the conservative full-trie verification if needed.

**Why this priority**: Operability and trust. Useful for validating the feature in production and for incident response, but the core value (US1) and safety (US2) are delivered without it.

**Independent Test**: Run a verification to completion with pruning enabled and confirm an observable signal reports that pruning engaged, the count of pruned subtrees vs visited nodes, and the elapsed time; then disable via config and confirm the node performs the full-trie verification.

**Acceptance Scenarios**:

1. **Given** pruned verification engaged, **When** verification completes, **Then** the node emits an observable signal indicating pruning was used, the count of subtrees pruned versus nodes visited, and the elapsed time.
2. **Given** the feature is disabled by configuration, **When** verification runs, **Then** the node performs the full-trie verification and logs that pruning is disabled.

### Edge Cases

- **Present node whose subtree-completeness is not yet established** (first-ever encounter, or never proven): MUST be descended into, not pruned, until its subtree is proven complete.
- **Present parent above a missing descendant** (the invariant does not hold for it): MUST be caught — the verification descends until subtree-completeness is genuinely established, so the missing descendant is found.
- **Crash mid-persistence** (e.g., between writing children and the parent/record): MUST NOT leave a node recorded subtree-complete with a missing descendant; on any uncertainty the system descends (verifies) rather than skips.
- **Restart with the completeness records lost or partial**: MUST fall back to descending unproven subtrees (up to full verification) — never prune an unproven subtree.
- **New/changed root (pivot roll)**: the completeness basis MUST be valid for the root being verified, or the system descends/falls back — never prune based on a superseded root's records.
- **Present-but-corrupt node**: present nodes are trusted as content-correct because they were content-hash-verified when stored (SNAP range reconstruction + the spec-004 fetch guardrail); this feature does not re-verify present-node content, only subtree presence.
- **Feature disabled / node without completeness records**: full-trie verification (today's behavior).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The completeness verification MUST treat a trie node that is present on disk AND known subtree-complete as a verified frontier leaf and MUST NOT descend into its subtree.
- **FR-002**: The system MUST maintain a durable subtree-completeness invariant such that "node present on disk AND recorded subtree-complete" guarantees the node's entire subtree is present and correct.
- **FR-003**: The invariant MUST be established such that it holds for a **fresh node's first verification** (not only after a prior full walk) — i.e., it is established during the SNAP/heal write path (a node is durably present only after its whole subtree is durably present), OR by an equivalent mechanism that makes a present node trustworthy as subtree-complete without first reading the whole trie. A present node whose subtree-completeness is not established MUST be descended into.
- **FR-004**: The verification MUST descend into any node that is missing, or present-but-not-proven-complete, and MUST find every missing node anywhere beneath it — the pruning narrows the walk only where completeness is durably proven, never where it is merely assumed.
- **FR-005**: The completion outcome reached via the pruned verification MUST be byte-for-byte identical to what the full-trie verification produces for the same healed state: the same `StateHealingComplete` decision, the same persisted completeness marker, and the same state root. The pruned verification is strictly a cost optimization with no semantic divergence.
- **FR-006**: The invariant MUST be crash-safe: an interrupted or partial persistence MUST NOT leave a node recorded subtree-complete while a descendant is missing. On any uncertainty after a restart, the system MUST descend (verify) rather than prune.
- **FR-007**: The system MUST provide a configuration switch to enable or disable pruned verification; when disabled, the node MUST perform the existing full-trie verification. The switch MUST be safe to flip without data migration, and a node lacking completeness records MUST fall back to descending unproven subtrees rather than pruning them.
- **FR-008**: The existing content-hash verify-before-store guardrail on **fetched (peer-served)** nodes MUST be preserved unchanged. This feature changes how **present** nodes are verified (subtree presence), not how fetched nodes are trusted (content).
- **FR-009**: The system MUST emit an observable signal when pruned verification is taken, reporting at minimum: that pruning engaged, the count of subtrees pruned versus nodes visited, and the elapsed verification time, so operators can confirm engagement and savings.
- **FR-010**: Pruned verification MUST compose with scoped verification (spec 003) and the decoupled serve root (spec 004): it generalizes scoped verification to the fresh-node case (where no prior full walk set the completeness marker), and it relies on, but does not alter, the decoupled-serve-root fetch path for healing any missing nodes it discovers.

### Key Entities *(include if feature involves data)*

- **Subtree-completeness invariant / record**: the durable fact that a given node's entire subtree is present and correct — the unit that makes "present" upgradable to "skippable". The new basis of the completeness proof.
- **Present-and-complete node**: a node on disk whose subtree-completeness is established; a verified frontier leaf that the verification prunes.
- **Verification frontier**: the set of nodes the pruned verification actually visits — missing nodes, present-but-unproven nodes, and the ancestors leading to them. Sized O(missing), independent of total trie size.
- **Completeness proof**: the basis for declaring `StateHealingComplete` — "the pruned verification finds zero missing", which is equivalent to the full-trie verification finding zero missing.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a fresh post-SNAP node whose trie is complete (or near-complete with a typical small missing set), the completeness verification finishes in **under a few minutes**, versus the ~16-20h full-trie walk it replaces — a ≥99% reduction on a large-state node.
- **SC-002**: Zero false completions: completion is never declared while any node is missing anywhere in the trie, including beneath a present parent.
- **SC-003**: Byte-for-byte parity: for the same healed state, the `StateHealingComplete` decision, the persisted completeness marker, and the state root after a pruned-verified completion are identical to those after a full-trie-verified completion.
- **SC-004**: Crash safety: across induced crashes during persistence, no node is ever recorded subtree-complete while a descendant is missing — no false prune.
- **SC-005**: Safe fallback: with the feature disabled, or on a node lacking completeness records, behavior is identical to today's full-trie verification.
- **SC-006**: Verification cost scales with the missing-node count, not the trie size: a node with few or no missing nodes verifies in time proportional to its missing frontier, independent of the ~90M total nodes.

## Assumptions

- Present trie nodes are content-correct: they were content-hash-verified when stored (SNAP range reconstruction and the spec-004 fetch guardrail `keccak256(node) == requested hash`), so the only open completeness question for a present node is whether its whole subtree is present — not whether the present node's content is correct. This is the load-bearing assumption that makes "skip a proven subtree" sound.
- The subtree-completeness invariant can be established crash-safely (e.g., bottom-up children-before-parent persistence, or an equivalent verified-completeness record) without a database schema migration — reusing the existing healing-frontier durable store or an additive record.
- This descend-and-stop model is proven in production by geth, Nethermind, and Besu (Forest); the feature ports a known-sound technique rather than inventing one.
- This is consensus-adjacent and MUST be reviewed and validated under the `forge` protocol; the byte-for-byte completion parity (FR-005) and the crash-safe never-false-prune invariant (FR-006) are the binding correctness invariants, consistent with the constitution's requirement that the node never declare success on incomplete or incorrect state.
- The validation target is the ETC/Mordor chain (chain-ID 61, PoW, ECIP-1017); the mechanism is a chain-agnostic sync-layer change and touches no consensus-critical EVM/gas/RLP/block-validation code.
- Default enablement of the configuration switch (FR-007) is deferred to planning; a safe default (enabled with mandatory full-trie fallback, or opt-in until validated in production) will be chosen during `/speckit-plan`, since the FR-007 fallback keeps either choice correct.
- This is the next increment in the post-SNAP heal performance/correctness line (prior: `specs/002-bfs-heal-performance`, `specs/003-scoped-heal-verification`, `specs/004-decoupled-heal-serve-root`); it removes the residual full-trie verification cost that those left in place. **Scope is #1 only.** Explicitly deferred to follow-up specs: persisting the BFS walk progress so a restart/pivot-roll resumes rather than re-walks (kills the restart multiplier, not the first-sync cost); seeding the heal frontier from SNAP range-proof boundaries; raising healing parallelism with bounded back-pressure. Out of scope entirely: the Erigon/Reth flat-key-value state-root rearchitecture (a database-schema one-way door) and a global in-DB-hash bloom filter (already rejected for silent-incomplete-heal risk and removed by geth itself).
- Requires a build and one redeploy; the next fresh heal would then verify in minutes instead of ~16-20 hours.
