package com.chipprbots.ethereum.blockchain.sync.snap.controller

/** Pure pivot selection and refresh decisions (spec 016 M1). Re-exported by `object SNAPSyncController`. */
private[snap] object PivotPolicy:

  /** Freshness gate for `refreshPivotInPlace`: reject candidate pivots whose source peer is more than `maxStaleness`
    * blocks behind the CL-driven head.
    *
    * Background: the post-merge SNAP refresh path used to take `max(snapPeer.maxBlockNumber)` as the new pivot with no
    * comparison against the actual chain tip. When the only SNAP-capable peers left in the pool were lagging (e.g. one
    * still reporting block 10_447_000 while sepolia's CL head was at 10_847_xxx — observed May 13 2026), the refresh
    * kept picking the stuck-peer's block, then immediately tripped the same-root fallback and restart. This filter
    * blocks that path: on post-merge chains we know the authoritative tip (via `clPivotHint`), so we require pivot
    * sources to be within `maxStaleness` of it. Pre-merge chains pass through unchanged (clHeadNumber=None).
    *
    * @param networkBest
    *   the best SNAP peer's maxBlockNumber
    * @param clHeadNumber
    *   the consensus-layer head block number, when available
    * @param maxStaleness
    *   configured `maxPivotStalenessBlocks` (default 4096) Yields `Right(())` if the candidate is fresh enough, or
    *   `Left(floor)` with the rejected freshness floor for diagnostic logging at the call site.
    */
  private[snap] def pivotPassesFreshnessFloor(
      networkBest: BigInt,
      clHeadNumber: Option[BigInt],
      maxStaleness: Long
  ): Either[BigInt, Unit] = clHeadNumber match
    case Some(clHead) =>
      val floor = clHead - maxStaleness
      if networkBest < floor then Left(floor) else Right(())
    case None =>
      // Pre-merge / pre-CL-hint state: no authoritative tip to compare against. Preserve the
      // legacy "take whatever peer offers" behavior.
      Right(())

  /** Pivot to move to when the current one is known unservable and `clHead - pivotBlockOffset` is not newer than it:
    * the larger of `clHead - margin` and `min(peerBest, clHead + MaxPeerTipLead) - margin`, if strictly newer than
    * `currentPivot`; None when neither is. `margin` is [[SnapServeWindowMargin]] (roots nearer the tip than that are
    * "not indexed" by peers; offset 0 froze the ETC pivot on 2026-06-01).
    *
    * `peerBest` is one peer's uncorroborated advertised tip. It is capped at `clHead + MaxPeerTipLead` so a peer that
    * lies high cannot drag the pivot (and the bootstrap retry that backtracks from it) arbitrarily far from the
    * CL-designated chain; the header itself is still fetched from peers by the normal bootstrap.
    */
  private[snap] def unservablePivotTarget(
      clHead: BigInt,
      currentPivot: BigInt,
      peerBest: Option[BigInt],
      margin: BigInt,
      maxPeerTipLead: BigInt = MaxPeerTipLead
  ): Option[BigInt] =
    val fromCl = clHead - margin
    val fromPeer = peerBest.map(_.min(clHead + maxPeerTipLead) - margin)
    (Seq(fromCl) ++ fromPeer).filter(_ > currentPivot).maxOption

  /** The most a snap peer's advertised tip may lead the CL head when choosing a replacement pivot (CL lag seen on
    * Platåberget 2026-10-05: ~110 blocks).
    */
  private[snap] val MaxPeerTipLead: BigInt = BigInt(128)

  /** True when a CL-anchored re-peg target (`clHead - pivotBlockOffset`) is not strictly newer than the current pivot —
    * i.e. `refreshPivotInPlace`'s CL-anchored branch would find nothing to do because the CL hasn't produced a fresher
    * head, NOT because anything is unservable.
    *
    * Extracted (BUG-BC3, 2nd follow-up, Platåberget soak 2026-09-28) as the single source of truth for
    * `refreshPivotInPlace`'s inline check, given explicit parameters — rather than reading `isPoSChain`/`clPivotHint`
    * off the enclosing actor — specifically so it can be unit-tested directly: `isPoSChain` is a `private val` fixed at
    * actor-construction time from the global
    * `com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig.terminalTotalDifficulty`, which the "test"
    * network config (used throughout this module's test suite, no `terminal-total-difficulty` entry) always resolves to
    * `false` — so no actor spawned in a test in this module can ever exercise the CL-anchored branch live (see
    * `staleReferenceHead`'s tests for the same constraint). Taking `clHead` as a plain `Option[BigInt]` sidesteps that
    * entirely: a test can simulate "PoS chain, live CL hint" by simply passing `Some(...)`, exactly as the
    * `staleReferenceHead` tests already do for `clHeadNumber`.
    */
  private[snap] def clPivotNotYetAdvanced(clHead: BigInt, pivotBlockOffset: Long, currentPivot: BigInt): Boolean =
    (clHead - pivotBlockOffset) <= currentPivot
