# CON-011: Subtree-complete heal verification — descend-and-stop, O(missing) completeness proof

**Status**: Superseded by [[CON-014]] (2026-11-02). Subtree completeness is now a write-order invariant, not a verification pass (spec 022 FR-001 to FR-004).

**Date**: 2026-06-19

**Spec**: `specs/005-subtree-complete-verification/`

**Related**: [[CON-009]] (healing completeness marker), [[CON-010]] (decoupled heal serve-root), `specs/003-scoped-heal-verification/`, `specs/002-bfs-heal-performance/`

## Context

After SNAP sync, `TrieNodeHealingCoordinator` proves the state trie is complete with a **completeness verification walk**: a local read-only BFS (`rebuildFrontierBFS`) over the trie that reads and RLP-decodes **every present node** to confirm none is missing, declaring `StateHealingComplete` only when a walk finds zero missing. On the ~90M-node ETC-mainnet state trie this is **~16-20 hours** (~1,000-1,300 nodes/s, CPU/GC-bound). It is fukuii's *sole* completeness proof, and a fresh node is forced to do it at least once — the spec-003 scoped verification cannot help, because it engages only after a prior full walk has already set the CF `'g'` completeness marker (`HealingFrontierStorage.markComplete`).

A 2026-06-19 research comparison (geth, Nethermind, Besu, Erigon, Reth) established that **fukuii is the only major MPT client that reads the entire trie to verify completeness.** geth (`trie/sync.go` `children()`/`hasNode()` prune), Nethermind (`TreeSync` `AlreadySaved`), and Besu-Forest (`LoadLocalDataStep.getExistingData`) all use **descend-and-stop**: at any reference whose node is already present on disk and known subtree-complete, they treat it as a verified frontier leaf and do not descend its subtree — so completeness verification costs **O(missing-frontier + ancestors)** (minutes), not O(90M) (hours). Erigon/Reth sidestep this with a flat-key-value state architecture that is not portable to fukuii's hash-keyed MPT-over-RocksDB (a DB-schema one-way door; out of scope).

This is **consensus-adjacent**: it changes what the node trusts as proof of a complete state before it follows the chain. A false "complete" (a missing node hidden under a present parent) leads to a state-root mismatch at block execution — a node-local liveness failure on chain 61 requiring re-sync, not a chain split.

## Key insight

**Subtree-completeness is root-independent.** Trie nodes are content-addressed (keyed by keccak256), so a node's children are fixed by its content. Once the entire subtree rooted at hash X is proven present on disk, that fact is **eternal** and valid across pivot rolls — it never needs invalidation or clearing (unlike the global `isComplete` marker, which is cleared on a differing-root pivot refresh). So a durable, additive, content-addressed **per-subtree-complete record** can safely upgrade a present node from "must descend to verify" to "verified frontier leaf — prune."

The reason descend-and-stop is *unsound in fukuii today* is that the heal write path is **top-down** (root seeded first, children discovered from the decoded parent), so a present branch routinely sits above not-yet-fetched children; plus range boundaries between the 16 independent SNAP `StackTrie` fragments, incomplete-storage accounts, and pivot-delta. The record is precisely the missing durable invariant that makes "present ⇒ skippable" sound.

## Decision

Add a durable **per-subtree-complete record** and make the verification **descend-and-stop**:

1. **The record (root-independent, in the existing CF `'g'`).** `HealingFrontierStorage` gains `markSubtreeComplete(hash)` / `isSubtreeComplete(hash)` / `multiIsSubtreeComplete(hashes)`. The record keys on the **bare keccak hash** with a non-32-byte prefix (`0x01 ++ hash`, 33 bytes, value `0x01`) that cannot collide with a 32-byte node hash or the existing 21-byte `CompleteMarkerKey`. Records are **additive, monotone, never cleared**, and require **no new column family and no migration**. The `loadAll` frontier filter is extended to exclude the new prefix.

2. **Descend-and-stop pruning oracle.** In `rebuildFrontierBFS`, before enqueuing a present child X to descend, the walk prunes iff `prunedHealVerification && storageScheme == Hash && isSubtreeComplete(X)` — treating X as a verified leaf and not enqueuing its children. A present-but-not-recorded node, and any missing node, is **descended exactly as today**. The set of `FrontierRebuilt` (missing-node) emissions is unchanged; pruning changes only *which present nodes are read*.

3. **Seed the invariant on the heal write path (so the FIRST verification is O(missing)).** Records are written (a) for each heal subtree as `discoverMissingChildren` confirms a node has zero still-missing **descendants** — i.e. no missing/pending direct child AND every present child (including an account leaf's `storageRoot`) is itself recorded subtree-complete (the inductive step) — written only after the subtree's bytes are durable; and (b) for the state root when the verification walk confirms a global zero-missing closure. This pre-populates the records so a fresh node's first verification descends only the residual gaps.

   > **Rejected (unsound): seeding SNAP account-`StackTrie` fragment roots at commit.** An earlier draft also recorded each account-trie fragment root subtree-complete when its `StackTrie` committed. This is unsound: the verification treats an account leaf's `storageRoot` as part of that account node's subtree, but SNAP downloads all accounts *before* any storage, so at account-fragment-commit time the storage tries beneath the fragment's leaves are definitionally not present. Because records are content-addressed and never cleared, such a record would later match a real node of the full pivot trie, the verification would prune it, and a still-missing storage node beneath it would go undetected → false `StateHealingComplete` → state-root mismatch. The inductive heal-side closure (a) never produces such a record, because it requires the `storageRoot` to be recorded-complete first. Seeding sound *storage*-fragment roots is a possible future optimization but is out of scope here.

4. **Crash-safety: record strictly after persist; terminal marker fsynced.** A record for X is written **only after** every node in X's subtree is in a committed RocksDB `WriteBatch` (mirroring `unpersistFrontier`'s post-`persist` rule): children node-bytes (CF `'n'`) committed → then the parent record (CF `'g'`) via ordinary `update` (WAL-ordered) → and only at terminal completion, the global `markComplete()` via `updateSync` (fsync). A crash between any two stages leaves the later record absent ⇒ on restart the node **descends** (the safe direction). The dangerous order "record before subtree durable" is structurally forbidden. A record is never seeded from an **abandoned** `StackTrie` fragment (reset on pivot roll) or a **truncated** (LRU-evicted) descent — only from a genuine committed, zero-missing closure.

5. **Byte-for-byte parity via the single existing chokepoint.** The pruned walk still emits `FrontierRebuilt` for any missing node and routes completion through the *same* `verificationPassComplete` → `HealingCheckCompletion` → `markComplete` / `StateHealingComplete` chokepoint. The walk does no state writes. Given the never-false-prune invariant, the pruned walk emits exactly the same set of missing nodes (zero, for completion) as the full walk ⇒ identical decision, identical marker bytes, identical state root. **No new completion site, no new marker set-point.**

6. **Hash-scheme only; config default-on; full-walk fallback.** Gated on `storageScheme == Hash` (the ETC default) AND `pruned-heal-verification` (default `true`). Under Path scheme, flag off, or with no records present, the node falls back to the unchanged full-trie verification — safe to flip without migration. The spec-004 fetched-node content-hash guardrail (`keccak256(node) == requested hash` before store) is **preserved verbatim**: this feature changes how *present* nodes are verified for subtree presence, not how *fetched* nodes are trusted for content.

## Consequences

- **Benefit**: a fresh post-SNAP node verifies completeness in **minutes** instead of ~16-20h — a ≥99% reduction on a large-state node; verification cost scales with the missing-node count, not the trie size.
- **Load-bearing risk** (the single one): pruning a present node whose subtree is not *durably proven complete* ⇒ an undetected missing descendant ⇒ false `StateHealingComplete` ⇒ state-root mismatch at block execution. Guarded by: record-after-persist ordering (4); descend-on-missing-record fallback (2); never-seed-from-abandoned/truncated (4). Validated under the `forge` protocol with the never-false-prune (FR-002), crash-safety (FR-006), and byte-parity (FR-005) tests as the consensus-aligned core.
- **No consensus-critical surface touched**: no EVM/gas/RLP/Ethash/ECIP-1017/block-validation code; the verification is a pure local read that never recomputes or mutates the state root.

## Alternatives rejected

- **(A) Bottom-up children-before-parent write ordering** (present-parent always ⇒ complete-subtree): incompatible with the inherently top-down heal path; would require unbounded in-memory subtree buffering on a 90M-node trie; cannot retrofit already-synced on-disk state; and non-fsync writes mean write-order ≠ durability-order without per-subtree fsync barriers.
- **Fine-grained always-on 90M-entry index**: heavy; the coarse on-demand record (only for subtrees actually proven) suffices.
- **Global in-DB-hash bloom filter**: a false positive marks a never-present node "present", drops its subtree → silent incomplete heal → state-root mismatch. Already rejected in spec 002 and removed by geth itself.
- **Erigon/Reth flat-KV state-root**: a DB-schema one-way door; out of scope.
