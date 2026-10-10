package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** SNAP pivot selection (spec 016 M8, research.md R6 "Pivot selection + refresh" (a)): the `bootstrapping` behaviour
  * (the pivot-header bootstrap's completion, failure and retry, with the freshness check against the SNAP peers' best
  * block and the post-merge header gate), the CL head hint buffer (`handleCLPivotHint`), the bootstrap retry backoff
  * and give-up (`bootstrapRetryDelay`, `checkBootstrapRetryTimeout`), the SNAP peers' best block
  * (`currentNetworkBestFromSnapPeers`) and the pivot's best-block and chain-weight record (`updateBestBlockForPivot`).
  * The core keeps `startSnapSync` (its accounts-complete recovery branch and coordinator spawns are not pivot
  * selection; CQ-SNAP-016-22) and the dispatch: the `Start` and `MinPivotBlock` arms of `idle`, the `CLPivotHint` arm
  * of `peerEventArms` and the `RetryBootstrapAtBlock` arm of `commonSyncingArms`.
  */
private[snap] trait PivotSelector:
  self: SNAPSyncControllerImpl =>

  def bootstrapping(): Behavior[Command] =
    Behaviors
      .receiveMessage[Command] {
        // C1: inlined peer-list arm with bootstrap reactivity. It differs from the shared copy, so it stays inline
        // ahead of peerEventArms (P3), as does the bootstrap GetProgress arm below.
        case WrappedHandshakedPeers(peers) =>
          handleHandshakedPeersBootstrapReactivity(peers); Behaviors.same

        case BootstrapComplete(pivotHeaderOpt) =>
          ctx.log.info("=" * 80)
          ctx.log.info("✅ Bootstrap phase complete - transitioning to SNAP sync")
          ctx.log.info("=" * 80)

          // Get the bootstrap target that we synced to (this is the pivot we wanted)
          val bootstrapTarget = appStateStorage.getSnapSyncBootstrapTarget()
          val localBestBlock = appStateStorage.getBestBlockNumber()

          ctx.log.info(s"Bootstrap target: ${bootstrapTarget.getOrElse("none")}, Local best block: $localBestBlock")

          // Clear bootstrap target from storage now that we've read it
          appStateStorage.clearSnapSyncBootstrapTarget().commit()

          // Reset retry counter
          bootstrapRetryCount = 0

          // Helper: compute current best height from SNAP-capable peers (subject to bootstrapPivot floor).
          // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise
          // a fresh-startup race returns Some(0) and the caller commits to a genesis pivot before
          // any real peer height is known.
          def currentNetworkBestFromSnapPeers(bootstrapPivot: BigInt): Option[BigInt] =
            // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise a
            // fresh-startup race counts them as "network best=0" → pivot=-64 → genesis fallback.
            val snapPeersForPivot =
              peersToDownloadFrom.values.toList
                .filter(p => p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.forkAccepted)
                .filter(_.peerInfo.maxBlockNumber > 0)
                .filter(p => bootstrapPivot == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivot)

            snapPeersForPivot
              .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
              .headOption
              .map(_.peerInfo.maxBlockNumber)

          def pivotTooStaleAgainstNetworkHead(pivot: BigInt): Boolean =
            val bootstrapPivot = appStateStorage.getBootstrapPivotBlock()
            currentNetworkBestFromSnapPeers(bootstrapPivot) match
              case Some(networkBest) if networkBest > 0 =>
                val delta = networkBest - pivot
                if delta > snapSyncConfig.maxPivotStalenessBlocks then
                  ctx.log.warn(
                    s"Bootstrapped pivot $pivot is now $delta blocks behind current network best $networkBest; " +
                      s"exceeds maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks}. Re-selecting a fresher pivot."
                  )
                  true
                else
                  ctx.log.info(
                    s"Bootstrapped pivot freshness: pivot=$pivot, networkBest=$networkBest, delta=$delta, " +
                      s"maxPivotStaleness=${snapSyncConfig.maxPivotStalenessBlocks}"
                  )
                  false
              case _ =>
                // If we can't see any suitable SNAP peers right now, don't block on freshness.
                false

          // bestSnapPeerTD removed: pivot TD is no longer seeded from peer wire TD (ETH68_BOOTSTRAP).
          // Using peer TD inflated every stored chain weight by (peerHead − pivot) × avgDifficulty.
          // Pivot now uses pivotBlockNumber as a neutral proxy; real TDs are built by block import.

          pivotHeaderOpt match
            case Some(header) =>
              val targetPivot = header.number.value

              if pivotTooStaleAgainstNetworkHead(targetPivot) then
                // Don't commit a pivot that peers are unlikely to serve.
                startSnapSync()
              else
                // Gate on ETH/Sepolia only — ETC pivot headers use StdBlockHeaderValidator (PoW).
                val pivotHeaderValid =
                  if isPoSChain then
                    given bc: BlockchainConfig =
                      com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
                    BlockHeader.validateFieldCount(header, bc) match
                      case Left(msg) =>
                        ctx.log.error(
                          "SNAP bootstrap pivot header field-count mismatch — aborting commit, restarting sync: {}",
                          msg
                        )
                        false
                      case Right(_) =>
                        PoSBlockHeaderValidator.validateHeaderOnly(header) match
                          case Left(err) =>
                            ctx.log.error(
                              "SNAP bootstrap pivot header failed post-merge validation — aborting commit, restarting sync: {}",
                              err
                            )
                            false
                          case Right(_) => true
                  else true

                if !pivotHeaderValid then startSnapSync()
                else
                  pivotBlock = Some(targetPivot)
                  stateRoot = Some(header.stateRoot)
                  appStateStorage
                    .putSnapSyncPivotBlock(targetPivot)
                    .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
                    .commit()
                  updateBestBlockForPivot(header, targetPivot)

                  SNAPSyncMetrics.setPivotBlockNumber(targetPivot)

                  ctx.log.info("=" * 80)
                  ctx.log.info("🎯 SNAP Sync Ready (from bootstrap)")
                  ctx.log.info("=" * 80)
                  ctx.log.info(s"Local best block: $localBestBlock")
                  ctx.log.info(s"Using bootstrapped pivot block: $targetPivot")
                  ctx.log.info(s"State root: ${header.stateRoot.value.toHex.take(16)}...")
                  ctx.log.info("=" * 80)

                  if accountsComplete && storagePhaseComplete && bytecodePhaseComplete then
                    ctx.log.info("All data phases complete — skipping to state healing with fresh pivot")
                    currentPhase = StateHealing
                    startStateHealing()
                  else
                    ctx.log.info(
                      s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers"
                    )
                    currentPhase = AccountRangeSync
                    startAccountRangeSync(header.stateRoot)
                  syncing()

            case None =>
              // Backward-compat / fallback: use the stored bootstrap target and local header.
              bootstrapTarget match
                case Some(targetPivot) =>
                  if pivotTooStaleAgainstNetworkHead(targetPivot) then startSnapSync()
                  else
                    blockchainReader.getBlockHeaderByNumber(targetPivot) match
                      case Some(header) =>
                        pivotBlock = Some(targetPivot)
                        stateRoot = Some(header.stateRoot)
                        appStateStorage
                          .putSnapSyncPivotBlock(targetPivot)
                          .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
                          .commit()
                        updateBestBlockForPivot(header, targetPivot)

                        SNAPSyncMetrics.setPivotBlockNumber(targetPivot)

                        ctx.log.info("=" * 80)
                        ctx.log.info("🎯 SNAP Sync Ready (from bootstrap, local header)")
                        ctx.log.info("=" * 80)
                        ctx.log.info(s"Local best block: $localBestBlock")
                        ctx.log.info(s"Using bootstrapped pivot block: $targetPivot")
                        ctx.log.info(s"State root: ${header.stateRoot.value.toHex.take(16)}...")
                        ctx.log.info("=" * 80)

                        if accountsComplete && storagePhaseComplete && bytecodePhaseComplete then
                          ctx.log.info("All data phases complete — skipping to state healing with fresh pivot")
                          currentPhase = StateHealing
                          startStateHealing()
                        else
                          ctx.log.info(
                            s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers"
                          )
                          currentPhase = AccountRangeSync
                          startAccountRangeSync(header.stateRoot)
                        syncing()

                      case None =>
                        ctx.log.warn(s"Bootstrap complete but pivot header $targetPivot not available yet")
                        ctx.log.warn("Falling back to recalculating pivot from current network state")
                        startSnapSync()
                case None =>
                  ctx.log.info("No bootstrap target stored - calculating pivot from network state")
                  startSnapSync()

        case RetrySnapSyncStart =>
          ctx.log.info("🔄 Retrying SNAP sync start after bootstrap delay...")
          startSnapSync()

        case GetProgress(replyTo) =>
          // During bootstrap, report that we're preparing for SNAP sync
          val currentBlock = appStateStorage.getBestBlockNumber()
          val targetBlock = appStateStorage.getSnapSyncBootstrapTarget().getOrElse(BigInt(0))

          ctx.log.info(s"Bootstrap progress: $currentBlock / $targetBlock blocks")

          // Send a simple progress indicator - we're in bootstrap mode
          replyTo ! SyncProgress(
            phase = Idle,
            accountsSynced = 0,
            bytecodesDownloaded = 0,
            storageSlotsSynced = 0,
            nodesHealed = 0,
            elapsedSeconds = 0,
            phaseElapsedSeconds = 0,
            accountsPerSec = 0,
            bytecodesPerSec = 0,
            slotsPerSec = 0,
            nodesPerSec = 0,
            recentAccountsPerSec = 0,
            recentBytecodesPerSec = 0,
            recentSlotsPerSec = 0,
            recentNodesPerSec = 0,
            phaseProgress =
              if targetBlock == 0 then 0 else ((currentBlock.toDouble / targetBlock.toDouble) * 100).toInt,
            estimatedTotalAccounts = 0,
            estimatedTotalBytecodes = 0,
            estimatedTotalSlots = 0,
            startTime = System.currentTimeMillis(),
            phaseStartTime = System.currentTimeMillis()
          )
          Behaviors.same

        // WrappedPeerDisconnected, FlushPeerDisconnects, PollHandshakedPeers and CLPivotHint: peerEventArms (P3),
        // consulted only after the two bootstrap-specific arms (WrappedHandshakedPeers above, GetProgress here), so its
        // own copies of those two are never reached in this behaviour. CLPivotHint: CL pushed a
        // (potentially newer) head while we were bootstrapping the previous one.
        // Buffer it; we'll re-evaluate on the next `startSnapSync()` if the in-flight bootstrap
        // fails or the caller decides to re-pivot. We don't tear down a healthy in-flight
        // bootstrap mid-stream — the original head is almost always sufficient.
        case msg if peerEventArms.isDefinedAt(msg) =>
          peerEventArms(msg)

        case GetStatus(replyTo) =>
          // During bootstrap, we're syncing via regular sync
          val currentBlock = appStateStorage.getBestBlockNumber()
          val targetBlock = appStateStorage.getSnapSyncBootstrapTarget().getOrElse(BigInt(0))
          val startingBlock = appStateStorage.getSyncStartingBlock()
          replyTo ! SyncProtocol.Status.Syncing(
            startingBlockNumber = startingBlock,
            blocksProgress = SyncProtocol.Status.Progress(currentBlock, targetBlock),
            stateNodesProgress = None
          )
          Behaviors.same

        case PivotBootstrapFailed(reason) =>
          ctx.log.warn(s"Pivot header bootstrap failed during initial startup: $reason")
          bootstrapRetryCount += 1
          checkBootstrapRetryTimeout(s"bootstrap failed: $reason") match
            case Some(b) => b
            case None =>
              val delay = bootstrapRetryDelay
              ctx.log.info(s"Retrying SNAP sync start in $delay (attempt $bootstrapRetryCount)")
              timers.cancel(BootstrapCheckKey)
              timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
              Behaviors.same

        // SNAP peer eviction runs during bootstrap to free slots for SNAP-capable peers
        case EvictNonSnapPeers =>
          evictNonSnapPeers()
          Behaviors.same

        case msg =>
          ctx.log.debug(s"Unhandled message in bootstrapping state: $msg")
          Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        onStop(); Behaviors.same
      }

  /** Buffer a CL-driven head hint and (optionally) react to the change.
    *
    * On post-merge chains: the hint's `headHash` becomes the SNAP pivot when `startSnapSync()` runs next. The hint's
    * `knownHeader`, when present, lets us skip the peer round-trip entirely. When absent, we route through
    * `StartRegularSyncBootstrapByHash` to fetch the header from a peer.
    *
    * On pre-merge chains (TTD = None): we still buffer for diagnostics but don't act — `startSnapSync()` ignores
    * `clPivotHint` when `!isPoSChain`.
    */
  private[snap] def handleCLPivotHint(hint: CLPivotHint, isStarting: Boolean): Unit =
    val isNew = !clPivotHint.exists(_.headHash == hint.headHash)
    clPivotHint = Some(hint)
    if isNew then clHintArrivedAtMs = Some(System.currentTimeMillis())
    if isNew then
      ctx.log.info(
        "[CL-PIVOT] Received CL-driven head {} (knownHeader={}, isPoSChain={})",
        com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(hint.headHash),
        hint.knownHeader.map(_.number).getOrElse("unknown"),
        isPoSChain
      )
    // Forward the CL head number to the network peer manager so it can run lagging-peer
    // eviction. Only valid when we know the actual block number (the by-hash bootstrap
    // variant skips this — `knownHeader` is None — and `NetworkPeerManagerActor` correctly
    // treats "never updated" as "pre-merge or unknown" → no-op).
    hint.knownHeader.foreach { header =>
      networkPeerManager ! com.chipprbots.ethereum.network.NetworkPeerManagerActor.UpdateClHeadCmd(header.number.value)
    }
    // Reactive starts: if we're already at idle and a hint arrives during operator-driven
    // startup, the `Start` handler will pick this up. We don't auto-start here because
    // SyncController's startSnapSync() drives the lifecycle.
    val _ = isStarting

  /** Calculate exponential backoff delay for bootstrap retries. Backoff: 2s → 4s → 8s → 16s → 32s → 60s cap.
    */
  private[snap] def bootstrapRetryDelay: FiniteDuration =
    val exponent = math.min(bootstrapRetryCount, 5)
    val delaySeconds = BootstrapRetryBaseDelay.toSeconds * math.pow(2, exponent).toLong
    math.min(delaySeconds, BootstrapRetryMaxDelay.toSeconds).seconds

  /** Check if bootstrap retry has exceeded the maximum count. If so, enter dormant mode and yield `Some(<dormant
    * behavior>)` for the caller to `break` on; otherwise `None` (caller continues / stays). The dormant wake-up
    * restarts SNAP on a fresh pivot once a snap-capable peer is connected.
    */
  private[snap] def checkBootstrapRetryTimeout(context: String): Option[Behavior[Command]] =
    if bootstrapRetryCount >= MaxBootstrapRetries then
      Some(enterDormantMode(s"no peers found after $bootstrapRetryCount bootstrap retries ($context)"))
    else
      if bootstrapRetryCount > 0 && bootstrapRetryCount % 5 == 0 then
        ctx.log.info(
          s"Bootstrap retry diagnostics ($context): " +
            s"attempt=$bootstrapRetryCount/$MaxBootstrapRetries, " +
            s"handshakedPeers=${handshakedPeers.size}, " +
            s"snapCapable=${handshakedPeers.values.count(_.peerInfo.remoteStatus.supportsSnap)}"
        )
      None

  def currentNetworkBestFromSnapPeers(): Option[BigInt] =
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
    // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise
    // a fresh-startup race returns Some(0) and the caller commits to a genesis pivot before
    // any real peer height is known.
    peersToDownloadFrom.values.toList
      .filter(_.peerInfo.remoteStatus.supportsSnap)
      .filter(_.peerInfo.maxBlockNumber > 0)
      .filter(p => bootstrapPivotBlock == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivotBlock)
      .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
      .headOption
      .map(_.peerInfo.maxBlockNumber)

  /** Update best block info and chain weight so ETH status handshake advertises the correct forkId.
    *
    * Without this, getBestBlockNumber() returns 0 (genesis) during SNAP sync, causing peers to reject us with
    * incompatible forkId (e.g. Frontier vs Spiral). This stores the pivot header, chain weight, and best block info so
    * that createStatusMsg() in EthNodeStatus68ExchangeState can build a valid status message referencing the pivot.
    */
  private[snap] def updateBestBlockForPivot(
      header: BlockHeader,
      pivotBlockNumber: BigInt
  ): Unit =
    val pivotHash = header.hash
    // Priority:
    //   (1) Real cumulative TD already in DB (ChainDownloader has backfilled it) — never overwrite.
    //   (2) Peer-interpolated TD from a connected ETH68 peer — accurate to <0.0005% for typical gaps.
    //       The old ETH68_BOOTSTRAP approach stored the peer's tip TD directly, inflating every
    //       subsequent block by (peerHead − pivot) × avgDifficulty. Interpolation avoids that by
    //       scaling to the pivot height.
    //   (3) Block-number proxy — last resort when no ETH68 peers are connected yet (very early startup).
    val (estimatedTotalDifficulty, tdSource) =
      blockchainReader
        .getChainWeightByHash(pivotHash)
        .map(cw => (cw.totalDifficulty.value, "REAL_PIVOT_TD"))
        .orElse(calibratePivotTD(pivotBlockNumber).map(td => (td, "PEER_INTERPOLATED_TD")))
        .getOrElse {
          val genesisTD: BigInt = blockchainReader
            .getChainWeightByHash(blockchainReader.genesisHeader.hash)
            .map(_.totalDifficulty.value)
            .getOrElse(blockchainReader.genesisHeader.difficulty.value)
          val proxy: BigInt =
            if pivotBlockNumber == BigInt(0) then header.difficulty.value else pivotBlockNumber.max(genesisTD)
          (proxy, "BLOCK_NUMBER_PROXY")
        }
    blockchainWriter.storeBlockHeader(header).commit()
    blockchainWriter
      .storeChainWeight(pivotHash, ChainWeight.totalDifficultyOnly(estimatedTotalDifficulty))
      .commit()
    // Always advance the self-reported best-block pointer so STATUS messages show the correct
    // pivot block number.
    appStateStorage
      .putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(pivotHash.value, pivotBlockNumber))
      .commit()
    ctx.log.info(
      s"Updated best block for ETH status: block=$pivotBlockNumber, hash=${pivotHash.value.toHex.take(16)}..., " +
        s"estimatedTD=$estimatedTotalDifficulty (source=$tdSource)"
    )
