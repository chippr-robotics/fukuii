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

  /** The value `maybeRequestHealingServeRoot` records as `lastHealingServeRootBlock` after a stale-triggered re-peg —
    * i.e. the bookkeeping baseline the NEXT staleness check is compared against.
    *
    * forge review follow-up on b8f0700f6 (BUG-BC3): the fix originally recorded `staleClockNow` (the value
    * `staleReferenceHead` picked for the CURRENT check) unconditionally. That is correct when `staleClockNow` is the CL
    * head (`staleClockNow != networkBest`) — see below. But whenever `staleReferenceHead` fell through to `networkBest`
    * (ETC/pre-merge, or a PoS chain before its first CL hint arrives — `staleClockNow == networkBest` in both), it
    * silently replaced base's `target` (`networkBest − margin`, `recentRootTarget`) with the larger `networkBest`
    * itself. Since `stale` is `(clockNow − lastBlock) > 2×margin`, recording `target` instead of `networkBest` is what
    * makes the EFFECTIVE re-trigger threshold "`networkBest` has advanced by more than 1×margin since the last fire"
    * (the `−margin` already baked into `target` cancels one of the two margins in the comparison) rather than 2×margin
    * — recording `networkBest` instead silently DOUBLES the required advance (and so roughly doubles the wall-clock
    * interval between re-pegs: on ETC mainnet, `moving-root-delta-heal = true` ships as the default with no ETC
    * override, so this was a real, not merely theoretical, behavior change — from ~64 to ~128 blocks between checks,
    * ~14 to ~28 minutes, approaching peers' ~128-block serve window). Recording `target` there instead is
    * BYTE-IDENTICAL to base.
    *
    * Recording the raw CL head (not a `−margin`-shifted value) for the CL-anchored case is intentional, not an
    * oversight to mirror: `target`'s `−margin` shift was calibrated specifically for the peer-reported-best/
    * serve-window cadence (see `maybeRequestHealingServeRoot`'s "Refresh cadence (U1)" comment). The CL-anchored check
    * exists for a different reason — avoiding spurious re-triggers while the CL head is not advancing at all (BUG-BC3)
    * — for which "the CL head has advanced by more than 2×margin since the last check" is already a direct,
    * self-justifying threshold; borrowing the peer-cadence's margin-shift would only make the CL path harder to reason
    * about for no corresponding benefit.
    */
  private[snap] def lastHealingServeRootBlockToRecord(
      staleClockNow: BigInt,
      networkBest: BigInt,
      target: BigInt
  ): BigInt =
    if staleClockNow == networkBest then target else staleClockNow
