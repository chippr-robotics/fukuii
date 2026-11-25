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

  /** Pivot for a heal-only recovery that must not resume on `excludedPivot` (#1555, PoS only): the normal CL target
    * `clHead - pivotBlockOffset` when it is strictly newer, else the [[unservablePivotTarget]] fallback (the excluded
    * pivot's root was refused or failed finalization, so it is treated as unservable), with the peers' tip leading the
    * CL head by at most `maxPeerTipLead`. None when neither is newer: the caller waits for the CL head or a snap peer
    * to advance rather than resume on the excluded pivot.
    */
  private[snap] def healPivotAfterExcluded(
      excludedPivot: BigInt,
      clHead: BigInt,
      peerBest: Option[BigInt],
      pivotBlockOffset: Long,
      margin: BigInt,
      maxPeerTipLead: BigInt = MaxPeerTipLead
  ): Option[BigInt] =
    val target = clHead - pivotBlockOffset
    if target > excludedPivot then Some(target)
    else unservablePivotTarget(clHead, excludedPivot, peerBest, margin, maxPeerTipLead)

  /** Pivot for a proof-of-work heal-only recovery that must not resume on `excludedPivot` (B-326, the PoW counterpart
    * of [[healPivotAfterExcluded]]): the snap peers' best minus `pivotBlockOffset`, as the normal pivot selection takes
    * it, raised to the branch's first block while a sync is bound to one (`PivotAnchor.refreshFloor`), if strictly
    * newer than `excludedPivot`. None when no peer height is known, when the best is under the branch's first block (no
    * peer left can serve a pivot on it), or when the target is not newer: the caller waits for a snap peer to advance
    * rather than resume on the excluded pivot.
    */
  private[snap] def powHealPivotAfterExcluded(
      excludedPivot: BigInt,
      peerBest: Option[BigInt],
      pivotBlockOffset: Long,
      branchFloor: Option[BigInt]
  ): Option[BigInt] =
    peerBest
      .filter(_ > 0)
      .filterNot(best => branchFloor.exists(best < _))
      .map(best => branchFloor.fold(best - pivotBlockOffset)((best - pivotBlockOffset).max(_)))
      .filter(_ > excludedPivot)

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

  /** The blocks of state a snap server answers for behind its head: go-ethereum and core-geth keep their snapshot as a
    * disk layer under 128 diff layers (`core.TriesInMemory`; `snaps.Cap(root, 128)`), and answer an older root with an
    * empty range and no proof. A pivot or heal root older than this is unservable.
    */
  private[snap] val SnapServeWindowBlocks: BigInt = BigInt(128)

  /** The age at which the proactive pivot roll moves the download pivot, 3/4 of [[SnapServeWindowBlocks]] (B-031): 96
    * blocks.
    *
    * go-ethereum (eth/downloader/downloader.go, `movePivotIfStale`) returns early while the skeleton (CL) head is at
    * most `pivot + 2 * fsMinFullBlocks - 8` (64 each), so it moves once the pivot is more than 120 blocks old, to `head
    * \- fsMinFullBlocks` (64), about every 56 blocks ("Pivot seemingly stale, moving"), and not while snap holds a
    * frozen pivot. Its new pivot header is already in the skeleton it holds. Ours is not: the new pivot's header is
    * fetched from a peer (T063, 2026-11-05: 30 s waiting for an eligible peer), the roll then probes the new root (up
    * to 5 × 30 s), and the trigger runs on a 30-s tick. That needs about 16 blocks more headroom than go-ethereum's 8.
    * Rolling near the window's edge (an age of 100, and 115 after the first roll; see [[proactiveRollDue]]) left little
    * of that time, and T063 logged 338 ERROR lines over 4 minutes of requests for a pruned root. A quarter of the
    * window, 32 blocks, is about 6.4 minutes on a 12-s chain and 7 minutes on a 13-s one.
    */
  private[snap] val RootRollAgeBlocks: BigInt = SnapServeWindowBlocks * 3 / 4

  /** The age at which moving-root heal re-pegs its root, 7/8 of [[SnapServeWindowBlocks]] (B-033): 112 blocks.
    *
    * Later than [[RootRollAgeBlocks]]: the heal re-peg sends no readiness probe and is checked on the 1-s healing tick,
    * so it needs less headroom than the roll. A re-peg lands 64 blocks below the head, so 112 gives a re-peg about
    * every 48 blocks (about 10.4 minutes on ETC) with 16 blocks of margin to the window's edge, close to go-ethereum's
    * 120 and every 56 (see [[RootRollAgeBlocks]]). 96 would double ETC's re-peg rate against the cadence T062 gated,
    * and each re-peg costs spec 022's walk a restart.
    */
  private[snap] val HealRepegAgeBlocks: BigInt = SnapServeWindowBlocks * 7 / 8

  /** The head a root's age is measured against for the proactive pivot roll (B-031): on a proof-of-stake chain, the
    * consensus layer's head once its header is known, because the roll's target is taken from it there
    * (`refreshPivotInPlace`'s CL-anchored branch); the snap peers' best block otherwise, which is also where a
    * proof-of-work roll takes its target from. The peers' best is unreliable after the merge (Sepolia 2026-05-15: peers
    * advertised a height ~1,146 blocks behind the head for 30 minutes), so measuring the age on it rolled late when it
    * lagged, and asked for a roll the CL head could not give when it led. The heal re-peg's clock
    * ([[HealPolicy.staleReferenceHead]], BUG-BC3) is this one under moving-root heal.
    */
  private[snap] def rollReferenceHead(isPoSChain: Boolean, clHeadNumber: Option[BigInt], networkBest: BigInt): BigInt =
    if isPoSChain then clHeadNumber.getOrElse(networkBest) else networkBest

  /** Whether the proactive pivot roll is due (B-031): the pivot is more than [[RootRollAgeBlocks]] behind `clockNow`,
    * and no roll landed within the minimum spacing.
    *
    * `lastRollClock` is where the last committed roll put the head: its pivot plus `rollTargetDepth` (the larger of
    * `pivot-block-offset` and the serve-window margin), as the roll took its target that far below the head. The
    * spacing keeps a configured depth at or above the roll age from rolling on every tick, whose new pivot is as old as
    * the one it replaces. It was a fixed 50 blocks, which with the default depth of 64 held the next roll back to an
    * age of 115, past the roll age, so every roll but the first came at the window's edge. It is now the distance from
    * the depth to the roll age, so it never holds a roll back past [[RootRollAgeBlocks]], and at least a quarter of the
    * window for a deeper target.
    */
  private[snap] def proactiveRollDue(
      pivotAge: BigInt,
      clockNow: BigInt,
      lastRollClock: Option[BigInt],
      rollTargetDepth: BigInt
  ): Boolean =
    val spacing = (RootRollAgeBlocks - rollTargetDepth).max(SnapServeWindowBlocks / 4)
    pivotAge > RootRollAgeBlocks && !lastRollClock.exists(last => clockNow - last <= spacing)
