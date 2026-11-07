# Feature Specification: Hot-Path Allocation Reduction for SNAP Sync

**Feature Branch**: `007-hotpath-alloc-reduction`

**Created**: 2026-06-21

**Status**: Parked (2026-10-30). The StackTrie allocation work landed (`mpt/StackTrie.scala` cites this spec); the remaining 7 tasks are unscheduled. Reopens if a SNAP CPU profile on the reference host is merkleization-bound again, or folds into spec 018 Track F / spec 021.
**Input**: User description: "Reduce hot-path CPU allocations in the SNAP-sync merkleization and keccak-256 path to improve sync throughput and cut GC/allocation pressure, especially on CPU-constrained hosts. Pure performance — byte-for-byte identical behavior, no consensus change."

**Closed** (2026-10-30):
- Reason: partially shipped, remainder not scheduled; spec 021 (fixed-width UInt256) and 018 Track F now own the hot paths it targeted.
- Reopen condition: in the sentence above.

## Context & Motivation

Live profiling of the barad-dûr ETC mainnet node (4-core i5-4430, CPU-saturated at load ~20, `us=85–97%`, running a fresh `deferred-merkleization=false` inline storage-MPT SNAP build) showed the node is **CPU-ceilinged in userspace**. Two independent adversarial analyses (a host-bottleneck workflow and a GPU-offload-feasibility workflow) ruled out offloading the work to the host's idle GPU (Amdahl-capped to ~1.0–1.2×, wrong workload shape, no precedent) and converged on **reducing per-operation allocations on the keccak-256 + trie-merkleization hot path** as the real, portable lever — a win that benefits **every** host, not just the constrained one.

The single hottest operation, keccak-256, currently allocates a fresh digest object on **every** call. The inline merkleization path allocates scratch buffers per trie node. On a 4-core host these allocations inflate GC time and steal cycles from useful sync work; on any host they are pure waste on a deterministic path.

This feature is **performance-only**: it must change *how fast* the node hashes and merkleizes, never *what* it produces. The keccak and merkleization paths are consensus-critical, so byte-for-byte output parity is a hard, non-negotiable requirement.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Eliminate per-call digest allocation in keccak-256 (Priority: P1)

A node operator runs a SNAP sync (or any sync/healing/import that hashes state). Today, every keccak-256 invocation allocates a new digest object, and keccak is invoked once per trie node across millions of nodes — generating sustained allocation and garbage-collection pressure that competes with sync work for the same scarce CPU. After this change, keccak-256 reuses a per-thread digest, so steady-state hashing allocates effectively nothing for the digest itself, lowering GC pressure and returning CPU to the sync — while producing identical hashes.

**Why this priority**: keccak-256 is the highest-frequency operation on the hot path and the change is the lowest-risk, highest-leverage win (a localized change to one hashing helper). It delivers measurable value on its own and is the foundation other stories build on.

**Independent Test**: Run a hashing micro-benchmark (and a SNAP account-range replay) before and after, measuring per-hash allocation rate and GC time; assert the keccak-256 output for a fixed corpus of inputs is byte-for-byte identical to the baseline. Shippable and valuable even if Stories 2–3 are never done.

**Acceptance Scenarios**:

1. **Given** a stream of inputs hashed on a single thread, **When** keccak-256 is invoked repeatedly, **Then** each result is byte-for-byte identical to the current implementation and no per-call digest object is allocated in steady state.
2. **Given** keccak-256 invoked concurrently from many Pekko dispatcher threads, **When** each thread hashes independent inputs, **Then** every result is correct (no cross-thread state bleed) and identical to baseline.
3. **Given** a hash operation that throws or is interrupted mid-computation, **When** the same thread next invokes keccak-256, **Then** the digest is in a clean state and the next result is correct (no residual state from the aborted call).

---

### User Story 2 - Reduce scratch allocations in inline merkleization (Priority: P2)

An operator syncing with inline merkleization (`deferred-merkleization=false`, the mode that builds the storage MPT during download) experiences high CPU/GC cost during the storage phase. The merkleization path allocates temporary/scratch buffers per trie node as it folds sorted leaves into branch/extension/leaf nodes. Reducing or pooling these per-node allocations lowers allocation rate during the storage build while producing identical node encodings and identical state roots.

**Why this priority**: This is the second-largest allocation source on the hot path and directly targets the `deferred=false` storage-build cost, but it is a more involved change than Story 1 (touches the trie-construction code) and carries more parity surface, so it follows Story 1.

**Independent Test**: Replay a fixed set of accounts/storage ranges through merkleization before and after, measuring allocation rate/GC time during the build; assert every computed node hash and resulting state root is byte-for-byte identical to baseline. Testable and valuable independently of Stories 1 and 3.

**Acceptance Scenarios**:

1. **Given** a fixed, sorted set of storage slots for a contract, **When** the storage trie is built inline, **Then** the resulting storage root and every intermediate node hash are byte-for-byte identical to baseline and per-node scratch allocation is measurably reduced.
2. **Given** the account trie built from a fixed account range, **When** merkleization runs, **Then** the account state root is identical to baseline.

---

### User Story 3 - Opportunistic hot-path allocation hotspots (Priority: P3)

During design and profiling, additional hot-path allocation hotspots may surface (for example RLP-encode buffers or reference-count node-storage wrapping). Each safe, high-value, low-risk hotspot is addressed only when it can be shown to preserve byte-for-byte output and meaningfully reduce allocation. Risky or low-value hotspots are explicitly deferred.

**Why this priority**: Opportunistic and bounded — value depends on what profiling reveals, and each item must clear the same parity bar. Lowest priority because individual gains are smaller and the set is discovered, not known upfront.

**Independent Test**: For each identified hotspot, a targeted benchmark shows reduced allocation and a parity check shows identical output; any hotspot that cannot prove both is dropped from scope.

**Acceptance Scenarios**:

1. **Given** an identified additional hotspot, **When** the optimization is applied, **Then** output is byte-for-byte identical to baseline and allocation for that operation is measurably reduced; otherwise the change is not made.

---

### Edge Cases

- **Thread reuse across hashing contexts**: A pooled/dispatcher thread hashes for unrelated subsystems over its lifetime. The reused digest MUST be reset to a clean state before each use so no state carries between unrelated hashes.
- **Aborted hash (exception mid-computation)**: If a hash call throws after partially updating the digest, the next call on that thread MUST still produce a correct result (reset-on-entry or equivalent guarantees clean state).
- **Cross-thread safety**: A digest instance MUST never be observed or mutated by more than one thread at a time (thread-confinement); concurrent hashing on different threads MUST be independent.
- **Thread/digest lifetime & memory**: Per-thread retained digests MUST be bounded by the live thread count and MUST NOT leak or grow unboundedly across large or churning dispatcher pools (and MUST behave acceptably under any virtual-thread usage).
- **Empty/edge inputs**: Empty input, single-byte input, and maximum-size node inputs MUST hash identically to baseline.
- **Concurrent merkleization**: Multiple contract storage tries built concurrently MUST each produce identical roots with no shared-buffer interference, with the StackTrie concurrent-build invariant verified (each per-task trie's root matches its single-thread oracle).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: keccak-256 MUST reuse a per-thread digest instance instead of allocating a new digest on every call, while producing byte-for-byte identical output for all inputs.
- **FR-002**: The reused digest MUST be returned to a clean, empty state before each hash so that no state leaks between calls — including after a call that throws or is otherwise aborted.
- **FR-003**: Digest instances MUST be thread-confined: never shared, observed, or mutated across threads concurrently.
- **FR-004**: Inline merkleization MUST reduce per-node scratch/temporary allocations while producing byte-for-byte identical node encodings, node hashes, and state roots.
- **FR-005**: All consensus-visible outputs — state roots, trie node hashes, account/storage roots, and any hashed wire/serialization output — MUST be byte-for-byte identical to the current implementation across the project's correctness corpus (ethereum/tests, MPT, crypto, RLP) and a dedicated parity check.
- **FR-006**: The change MUST be pure performance: no alteration to consensus semantics, gas, fork dispatch, signing, or wire behavior. ETC and ETH code paths MUST remain unaffected in output.
- **FR-007**: The optimizations MUST be correct and safe under Pekko's multi-threaded dispatchers (and acceptable under any virtual-thread usage), with no data races on shared hashing or buffer state.
- **FR-008**: Each optimization MUST be accompanied by a repeatable measurement (benchmark and/or replay) that quantifies the allocation/GC reduction it delivers, so the win is evidenced rather than assumed.
- **FR-009**: Per-thread retained state introduced by these optimizations MUST be bounded by live thread count and MUST NOT cause unbounded memory growth or leaks across dispatcher pool churn.
- **FR-010**: Any hotspot under Story 3 that cannot demonstrate BOTH byte-for-byte parity AND a measurable allocation reduction MUST be excluded from scope (no speculative changes on the consensus-critical path). Any P3 hotspot DROPPED for failing the parity-or-measured-win bar MUST be RECORDED together with the measurement that justified dropping it, so the deferral is auditable.

### Key Entities

- **Hash digest state**: The mutable internal state used to compute a keccak-256 hash. Currently allocated per call; the feature makes it a reusable, thread-confined, reset-before-use resource. Key attribute: must be indistinguishable in output from a freshly allocated digest.
- **Trie node (merkleization unit)**: A branch/extension/leaf node produced while folding sorted leaves into a Merkle Patricia Trie. Its RLP encoding and keccak-256 hash are consensus-visible and MUST be unchanged; only the transient buffers used to build it may change.

## Success Criteria *(mandatory)*

### Measurable Outcomes

> **Note**: SC-002 and SC-003 are REPORT-AND-RECORD outcomes — they have NO fixed numeric pass/fail threshold (their targets are grounded by the T002 baseline benchmark during planning). The ONLY hard pass/fail gates are the parity criteria SC-004 and SC-005.

- **SC-001**: Steady-state keccak-256 hashing allocates effectively no per-call digest object (per-hash digest allocation reduced to ~zero), verified by an allocation-profiling benchmark.
- **SC-002**: Measurable reduction in bytes allocated and GC time attributable to the hot path during a fixed SNAP account+storage replay (target: a clear, repeatable reduction in hot-path allocation rate versus baseline; exact percentage established by the baseline benchmark in planning).
- **SC-003**: SNAP account+storage processing throughput on the constrained 4-core host improves by a measurable, repeatable margin versus baseline (honest expectation: low single-digit to low-double-digit percent — this is allocation/GC relief, not an order-of-magnitude change).
- **SC-004**: 100% byte-for-byte output parity: every state root, node hash, and hashed output across the correctness corpus and the dedicated parity check is identical to baseline (zero diffs).
- **SC-005**: Zero regressions in the existing correctness suites (crypto, RLP, MPT, VM, ethereum/tests) at the relevant test tier.
- **SC-006**: No increase in steady-state resident memory attributable to the change beyond the bounded per-thread digest footprint (no leak across a long-running sync).

## Assumptions

- **Inline merkleization is in-scope and relevant**: the `deferred-merkleization=false` mode (storage MPT built during download) is an active, supported operating profile, so its hot path is worth optimizing; the keccak-256 win (Story 1) additionally benefits healing, block import, and regular sync wherever state is hashed.
- **Consensus-critical protocol applies (BOTH chains)**: keccak and merkleization are consensus-critical, and the `kec256` helper is **chain-shared** — consumed by ETC PoW (Ethash/ommers) AND ETH/Sepolia PoS (`EngineApiService` payload-id/block-hash). So per Constitution Principle I + Workflow step 7, design and review require BOTH the `forge` protocol (ETC) AND `beacon` review (ETH/Sepolia), with byte-for-byte parity proven across both chains' post-state-root corpora. This is what makes FR-006 ("ETC AND ETH code paths unaffected") enforceable. This spec defines WHAT; the plan defines HOW under that protocol.
- **Measurement tooling exists**: a benchmarking facility is available to quantify allocation/GC/throughput before and after; baseline numbers are captured during planning so SC-002/SC-003 targets are grounded.
- **Thread-confinement is acceptable**: per-thread (thread-local or equivalent) reuse is a sound model under the current dispatcher/threading design; virtual-thread usage, if any, does not invalidate the bounded-footprint assumption.
- **Scope boundary**: this feature does NOT introduce GPU/native offload, does NOT change `deferred-merkleization` semantics, and does NOT alter any consensus rule, gas cost, fork schedule, or wire format. It is confined to reducing allocations on existing deterministic hashing/merkleization code.
- **Portability**: gains are host-independent (reduced allocation helps every node); the constrained 4-core host is the measurement vehicle, not the only beneficiary.
