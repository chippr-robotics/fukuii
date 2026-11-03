# CON-009: Post-SNAP healing completeness marker — set/clear semantics

**Status**: Superseded by [[CON-014]] (2026-11-02). The CF `g` completeness marker is retired: CF `g` is cleared once and nothing writes to it (spec 022 T040, T044). The marker that stays, `snap.heal.invariant`, says everything present was written under the invariant, not that the state is complete.

**Date**: 2026-06-13

**Spec**: `specs/002-bfs-heal-performance/` (User Story 1)

**Related**: PR #1335 (skip the rebuild walk on restart when complete), [[CON-008]] (checkpoint distribution)

## Context

After SNAP sync, `TrieNodeHealingCoordinator` runs a full-state walk over the local trie to rebuild the
healing frontier and detect referenced-but-missing nodes. A persisted completeness marker
(`HealingFrontierStorage.markComplete()` / `isComplete`) lets a restart **skip** that walk when the trie
is already fully healed. On ETC mainnet the walk takes many hours, so skipping it when genuinely complete
is the dominant performance lever.

The marker is **consensus-adjacent**: if it is set when the trie is *not* actually complete, a restart
skips healing, leaves a referenced node missing, and the node later fails to compute a post-state root —
a hard block-import stop on chain 61 (a node-local liveness failure, not a chain split, but it requires a
re-sync).

Investigation (2026-06-13) found two defects:

1. **The marker was almost never set.** `markComplete()` had exactly one caller — the `FrontierRebuildComplete`
   handler, which fires only on a *fresh full-state rebuild*. A node that healed on its first run completed
   via the verification path (`VerificationDFSComplete` → `HealingCheckCompletion` → `StateHealingComplete`)
   and never wrote the marker, so every restart re-ran the multi-hour walk.
2. **A no-op pivot refresh wiped a valid marker.** `clearPersistedFrontier()` unconditionally cleared the
   marker, and it ran on every `HealingPivotRefreshed`, including a refresh to the *same* state root —
   silently defeating fix (1).

## Decision

Define the marker invariant explicitly and enforce it conservatively:

> **The completeness marker is SET if and only if a walk has classified every locally-held node
> reachable from the state root AND no missing node remains (`isComplete`: `pendingTasks ∪ activeRequests`
> empty).**

1. **Set on the verified-complete path.** `markComplete()` is called in `HealingCheckCompletion`, gated on
   `verificationPassComplete` (a verification DFS walked the trie and found zero missing). It is **not**
   called on the `totalNodesHealed == 0` idle arm — that arm declares completion without ever walking, so
   marking it could record an untraversed trie as complete (the consensus hazard). The existing
   `FrontierRebuildComplete` set-site (fresh full-state rebuild) is retained.
2. **Same-root pivot refresh is a no-op.** `HealingPivotRefreshed(newStateRoot)` early-returns when
   `newStateRoot == stateRoot` (full 32-byte value equality), preserving the marker and the persisted
   frontier. A genuinely different root still clears (it may invalidate already-healed subtries —
   conservative), and abandonment (`HealingForceComplete`) still clears unconditionally.

## Why this is conservative (never trades completeness for speed)

A missing referenced node is recorded as a frontier entry (`multiGetNodes` → `None` →
`HealingEntry`), which keeps `isComplete == false`. Therefore "marked complete" provably means "walk
covered the trie AND found nothing missing," and the restart skip can never fire while a node could be
missing. The change touches only *when* the marker is set/cleared — never which nodes the walk
enqueues, visits, or declares missing.

## Alternatives considered

- **Set the marker in `SNAPSyncController.StateHealingComplete`** — rejected: that message also arrives from
  the abandonment and idle paths, which would mark an unwalked/abandoned trie complete.
- **Reuse the `appStateStorage` `healingValidatedRoot` flag** — rejected: different completion route, gates
  the controller's phase transition rather than the coordinator's resume gate; couples two state machines.
- **Per-root completeness key** (so a new root naturally invalidates without an explicit clear) — cleaner
  long-term, but a larger change to the sentinel key layout. Recorded as a future refinement; the same-root
  guard achieves identical safety now.
- **Bloom-filter visited set** (a separate proposal) — **rejected**: a false positive reports a
  never-visited node as visited, skips its subtree, and can hide a missing node — the exact silent
  incomplete-heal this marker work guards against.

## Consequences

- A node that heals on its first run records completeness and skips the multi-hour walk on the next
  restart (the dominant win; see SC-001).
- Frequent same-root pivot refreshes on peer-scarce mainnet no longer discard a valid marker.
- Validated by `HealingFrontierResumeSpec` (marker set after a completing heal; preserved on same-root
  refresh; cleared on different-root refresh) plus the existing resume-skip tests, and reviewed under the
  `forge` consensus-critical protocol.
