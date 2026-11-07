# CON-012: Skip the redundant second verification walk on a clean post-SNAP heal

**Status**: Superseded by [[CON-014]] (2026-11-02). Completion no longer rests on a walk where the invariant marker stands, so its byte-for-byte parity with the two-walk outcome no longer holds, by design.

**Date**: 2026-06-19

**Spec**: `specs/006-skip-redundant-verify-walk/`

**Related**: [[CON-009]] (healing completeness marker), [[CON-010]] (decoupled heal serve-root), [[CON-011]] (subtree-complete verification), `specs/003-scoped-heal-verification/`, PR #1321 (commit `961f0c9b5`, the verification walk's origin)

## Context

After SNAP download, `TrieNodeHealingCoordinator` proves the state trie is complete before declaring `StateHealingComplete`. When a node restarts with a persisted healing frontier but **no** completeness marker (the common case for a node that restarted mid-heal), it runs a full-trie **rebuild walk** (`rebuildFrontierBFS`, dispatched via the `case None` resume branch with the `FrontierRebuildComplete` callback) to re-derive the missing-node frontier from on-disk reality.

On a trie that is already complete, that rebuild walk finds **zero missing nodes and heals nothing** — yet the node does not declare completion. The `FrontierRebuildComplete` handler only clears the single-flight flag and writes the completeness marker (`markComplete()`); it does **not** set `verificationPassComplete` and does **not** route to `HealingCheckCompletion`. So the node sits idle with `verificationPassComplete = false`, and ~4-6 minutes later the **dead-pulse watchdog** force-starts a **second** full-trie walk (`startVerificationBFS`) that traverses the identical trie, again finds zero missing, sets `verificationPassComplete = true`, and only then declares `StateHealingComplete`.

The two walks are the **same `rebuildFrontierBFS` traversal**. On the ETC mainnet state trie (~90M+ nodes) each takes ~16-20 hours, so the redundant second walk **doubles** post-SNAP completion (~30-40h instead of ~16-20h) — on the critical path to a usable node, for no completeness benefit. This is consensus-adjacent: it changes *when* the node trusts its state as complete. A false `StateHealingComplete` → state-root mismatch at block execution → wedged node (node-local liveness failure on chain 61).

## Key insight (Chesterton's Fence)

The verification walk was introduced in commit `961f0c9b5` (PR #1321, "StateHeal fixes + review", BUG 1) to patch a gap in **`discoverMissingChildren`** — the *shallow* inline per-healed-node discovery that skips already-present storage roots without recursing into their children (account `888157b2`: StateHeal declared "complete" in 59s while its storage sub-trie was still partial). But **`rebuildFrontierBFS` already fully recurses into account-leaf `storageRoot`s** — it *is* the full traversal BUG-1 demanded. So the fence is real, but it does **not** apply when the deciding walk was itself a clean full rebuild: in that case the verification walk is the same traversal re-run for nothing.

## Decision

In the `FrontierRebuildComplete` handler, when the just-finished rebuild was **genuinely clean**, declare completion after one walk by setting `verificationPassComplete = true` and routing through the **single existing** `HealingCheckCompletion` chokepoint:

> **`if (missingEmitted == 0 && totalNodesHealed == 0 && isComplete && !flushing && walkRoot == stateRoot) { verificationPassComplete = true; self ! HealingCheckCompletion }`**

1. **Observable outcome.** `FrontierRebuildComplete` becomes a case class `FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString)`. `missingEmitted` is the rebuild's `frontierCount` (the number of missing nodes it emitted), threaded out of the walk kernel via the `onComplete` callback; `walkRoot` is the root the walk traversed, captured at the launch site. The walk performs no state writes — this is a pure read of an in-kernel atomic.

2. **The five-conjunct guard**, each excluding one false-completion mode:
   - `missingEmitted == 0` — the deciding walk found nothing missing.
   - `totalNodesHealed == 0` — nothing was healed (conservative; matches the existing clean-idle completion arm; the lifetime cumulative counter gives only conservative false-negatives, never false-positives).
   - `isComplete` (`pendingTasks.isEmpty && activeRequests.isEmpty`) — no outstanding frontier or in-flight request.
   - `!flushing` — no asynchronous node-write in flight (mirrors the existing `HealingCheckCompletion` flush guard).
   - **`walkRoot == stateRoot`** — the finished walk's root is still the node's current root. This is load-bearing: a `HealingPivotRefreshed` to a different root does **not** cancel the in-flight walk (it closed over the old root), so a *stale* completion can arrive against a new `stateRoot`. The guard is **explicit**, not reliant on the incidental "a pivot refresh re-seeds `pendingTasks` so `isComplete` is false" property — a future refactor of the pivot path could silently reintroduce that hole.

3. **Byte-parity by construction.** Routing via `self ! HealingCheckCompletion` (not a direct `StateHealingComplete`) means the marker write and the `StateHealingComplete` send flow through the *same* path the two-walk sequence uses. Given the guard holds, the early path declares the same decision, writes the same marker bytes (`Array[Byte](1)`), against the same state root — no new completion site, no new marker set-point.

4. **Unconditional, with the watchdog as the built-in fallback.** No config flag (the outcome is byte-identical to the two-walk path; a flag would only let an operator re-enable a redundant ~16-20h walk). The dead-pulse watchdog is left intact and unchanged: if early completion does not fire for any reason, it force-starts the verification walk exactly as today, self-suppressing via `!verificationPassComplete` (no double-start). The early completion can only ever *advance* completion when provably sound, never *prevent* it.

5. **The fence is preserved.** The early path is reachable only from the clean `FrontierRebuildComplete` handler. Every completion path that is not a clean full rebuild (inline-discovery completion, any heal > 0, any guard-unmet case) still runs the verification walk exactly as today (FR-007).

## Consequences

- **Benefit**: a restarted node whose trie is complete reaches `StateHealingComplete` after one full-trie walk instead of two — roughly **halving** post-SNAP completion (~16-20h instead of ~30-40h on ETC mainnet). No new per-walk cost (the guard is five cheap local reads at one message handler).
- **Load-bearing risk** (the single one): a false `StateHealingComplete`. Guarded by the five conjuncts, each excluding a specific mode. `forge`'s adversarial pass refuted all five attack vectors: (1) bounded-visited FIFO eviction cannot skip a missing node (detection is unconditional on dequeue; eviction only re-enables enqueues, never refuses insert — both walks share the identical non-blind-spot, so the second walk adds zero detection safety); (2) heal-during-walk excluded by `totalNodesHealed == 0 && isComplete && !flushing`; (3) stale-root by `walkRoot == stateRoot`; (4) storage-under-present-leaf is the BUG-1 gap, but `rebuildFrontierBFS` fully recurses; (5) marker-trust is already staked on the clean rebuild path today (`markComplete()` already fires there before any verification), so the fix changes nothing about marker correctness.
- **No consensus-critical surface touched**: no EVM/gas/RLP/Ethash/ECIP-1017/block-validation code; the verification traversal does no state writes.
- **Redeploy-gated**: cannot help a heal walk already in flight (forfeiting it would cost more than it saves); the next heal benefits. Independent of CON-011 / spec 005 (no shared edit beyond the same file region; `markComplete` is idempotent).

## Alternatives rejected

- **Direct `StateHealingComplete` from the handler** (bypassing `HealingCheckCompletion`): would duplicate the completion/marker logic and risk divergence from the two-walk outcome. Rejected for byte-parity — route through the single chokepoint.
- **Rely on the incidental pivot-reseed property** instead of the explicit `walkRoot == stateRoot` guard: fragile; a future refactor of the pivot path could silently reintroduce a stale-root false-completion. Rejected for an explicit guard.
- **Widen the guard to `missingEmitted == 0` only** (drop `totalNodesHealed == 0`): still byte-parity-safe (a full-state walk emitting zero missing is itself proof of completeness), but deferred until one clean live validation — the conservative `== 0` ships first.
- **A config flag** to toggle the optimization: unnecessary (byte-identical outcome) and would only enable a regression. Rejected; the watchdog is the built-in fallback.
