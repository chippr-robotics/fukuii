package com.chipprbots.ethereum.blockchain.sync.snap.controller

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig

/** Pure healing decisions (spec 016 M1). Re-exported by `object SNAPSyncController`. */
private[snap] object HealPolicy:

  /** Whether SNAP may claim that no account's bytecode is missing (`bytecodeRecoveryDone`): nothing healed is still
    * missing, and the bytecode phase was not force-completed with tasks abandoned.
    */
  private[snap] def bytecodeRecoveryComplete(
      healedCodeStillMissing: Int,
      bytecodePhaseForceCompleted: Boolean
  ): Boolean =
    healedCodeStillMissing == 0 && !bytecodePhaseForceCompleted

  private[snap] def shouldSkipHealingAfterDownloads(
      snapSyncConfig: SNAPSyncConfig,
      resumedStaleCursors: Boolean
  ): Boolean =
    // The deferred-merkleization fast path (skip healing, lazy-heal during block execution) is
    // ONLY safe when the trie was built fresh this session. If any account-range cursor was
    // resumed from a prior session (against a possibly drifted root), the delta MUST be walked
    // and re-fetched from the current pivot root before completion — otherwise the state is
    // handed off with silent holes. So a resume forces the full healing walk.
    // #1371 (restored — #1384's stale base re-added the `!storagePhaseForceCompleted` term, which
    // routed force-completed deferred-merkleization back into the full heal walk → the
    // "exactly 1 node, healed=0 forever" stall). Force-completion does not invalidate the
    // freshly-built trie; only a resumed stale cursor does.
    snapSyncConfig.deferredMerkleization && !resumedStaleCursors

  /** True when the proactive heal-root re-peg must not fire: under `movingRootDeltaHeal` a re-peg supersedes the
    * running verification walk, whose completion is then discarded (the walk is restarted), so a walk that takes longer
    * than the re-peg interval would never finish. While the walk is purely local (nothing pending, nothing in flight)
    * serve-window freshness is irrelevant. Once a dirty pass leaves heal work, `walkLocalOnly` clears and the normal
    * re-peg rules apply. The serve-root-only path (`decoupledHealServeRoot`) never invalidates the walk.
    */
  private[snap] def healRepegSuppressedByLocalWalk(movingRootDeltaHeal: Boolean, walkLocalOnly: Boolean): Boolean =
    movingRootDeltaHeal && walkLocalOnly

  /** The reference head `maybeRequestHealingServeRoot` clocks its heal-root staleness check against.
    *
    * Platåberget ePBS-devnet soak, 2026-09-27 (BUG-BC3): under `movingRootDeltaHeal`, `refreshPivotInPlace`'s OWN
    * re-peg decision is CL-anchored on a PoS chain (peer `maxBlockNumber` is unreliable post-merge — see
    * `refreshPivotInPlace`'s own comment). Clocking the STALENESS check against peer-reported `networkBest` instead let
    * a frozen CL head (a Lighthouse CL that cannot advance while its EL reports SYNCING) trigger repeated staleness
    * checks purely because peers kept gossiping a climbing STATUS height, even though the CL head — the only thing that
    * could ever produce a newer pivot on that path — was not moving. Each such check correctly found no newer CL pivot,
    * but (pre-fix) that failure counted against the bounded re-peg budget anyway, exhausting it in ~5 minutes while
    * healing progressed normally on serving peers.
    *
    * Using the CL head as the clock here means a stuck CL simply stops triggering checks in the first place, rather
    * than triggering ever more of them — a root-cause fix layered on top of (and independent from) the
    * `refreshPivotInPlace(reason, countsTowardHealBudget = false)` fix for this same call site, which stops a "no newer
    * pivot yet" outcome from counting against the budget regardless of what triggered the check.
    *
    * Byte-identical for ETC/pre-merge (`isPoSChain = false`) and for the `decoupledHealServeRoot` (non-
    * `movingRootDeltaHeal`) serve-root path, which by design tracks newest-SERVABLE rather than canonical head
    * (CON-010) — both always fall through to `networkBest`.
    */
  private[snap] def staleReferenceHead(
      movingRootDeltaHeal: Boolean,
      isPoSChain: Boolean,
      clHeadNumber: Option[BigInt],
      networkBest: BigInt
  ): BigInt =
    if movingRootDeltaHeal && isPoSChain then clHeadNumber.getOrElse(networkBest) else networkBest

  /** Whether `maybeRequestHealingServeRoot` re-pegs the heal root (moving-root heal) or asks for a newer serve root
    * (the spec-004 serve-root path) on this tick.
    *
    * **Moving-root heal (B-033): on the root's own age.** The heal root is the pivot (`rootBlock`), which a re-peg
    * moves with it. It is re-pegged once it is more than `PivotPolicy.RootRollAgeBlocks` (96, about 3/4 of the peers'
    * 128-block serve window) below `staleClockNow`, the head [[staleReferenceHead]] picks. go-ethereum moves its pivot
    * when it is `2 * fsMinFullBlocks` (128) below the head of its header stream, to `head - fsMinFullBlocks` (64); ours
    * moves a quarter of the window earlier, because its new header is fetched from a peer rather than read from a
    * stream it already has (see `RootRollAgeBlocks`).
    *
    * This replaced a cadence on the clock since the last check, `clock - lastCheck > 2 * margin` (128). A re-peg lands
    * `margin` (64) below the head, so that rule let the root reach 128 blocks before the next re-peg on ETC, where the
    * last check recorded `networkBest - margin`, and 192 on a CL-anchored proof-of-stake chain, where it recorded the
    * CL head itself (T063 on Sepolia, 2026-11-05: a root 138 blocks old when the peers went stateless). The recorded
    * value was what set the threshold at 128 or 192 (forge's review of b8f0700f6, a BUG-BC3 follow-up: recording
    * `networkBest` rather than `networkBest - margin` on ETC silently doubled it). Measured from the root itself, no
    * recorded value changes how old the root may get.
    *
    * BUG-BC3's clock reasoning holds unchanged: the age is measured on the CL head on a proof-of-stake chain with a CL
    * hint, so a frozen CL head (Lighthouse cannot advance while its EL reports SYNCING; Platåberget 2026-09-27) never
    * ages the root by itself, however the peers' advertised heights climb. `lastCheckClock` is the clock at the last
    * re-peg this rule asked for: a check that found no newer root is not made again until the clock has moved, so a
    * frozen clock gives one check, not one per tick, and a moving one at most one per block. No such check counts
    * against the re-peg budget (`refreshPivotInPlace(countsTowardHealBudget = false)`, BUG-BC3).
    *
    * **The serve-root path** (`movingRootDeltaHeal = false`, spec 004 U1): unchanged. The serve root is fetched at
    * `networkBest - margin`, and is refreshed once the head is more than `2 * margin` past the block of the last root
    * pushed (`lastCheckClock`), or at once when none was.
    */
  private[snap] def healRootStale(
      movingRootDeltaHeal: Boolean,
      staleClockNow: BigInt,
      rootBlock: Option[BigInt],
      lastCheckClock: Option[BigInt],
      margin: BigInt
  ): Boolean =
    if movingRootDeltaHeal then
      rootBlock.forall(root => staleClockNow - root > PivotPolicy.RootRollAgeBlocks) &&
      lastCheckClock.forall(staleClockNow > _)
    else lastCheckClock.forall(last => (staleClockNow - last) > margin * 2)
