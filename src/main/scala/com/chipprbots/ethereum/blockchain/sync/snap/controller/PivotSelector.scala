package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

private[snap] trait PivotSelectionApi:
  def currentNetworkBestFromSnapPeers(): Option[BigInt]

private[snap] trait SnapStartApi:
  def startSnapSync(): Behavior[Command]

private[snap] trait SyncingApi:
  def syncing(): Behavior[Command]

private[snap] trait AccountRangeStartApi:
  def startAccountRangeSync(rootHash: TrieRoot): Unit

private[snap] trait PivotSelectorState:
  var clPivotHint: Option[CLPivotHint]
  var clHintArrivedAtMs: Option[Long]
  var bootstrapRetryCount: Int
  def isPoSChain: Boolean
  def bigIntReverseOrdering: Ordering[BigInt]
  def BootstrapCheckKey: String
  def peerEventArms: PartialFunction[Command, Behavior[Command]]

/** SNAP pivot selection (spec 016 M8, research.md R6 "Pivot selection + refresh" (a)): the `bootstrapping` behaviour
  * (the pivot-header bootstrap's completion, failure and retry, with the freshness check against the SNAP peers' best
  * block and the post-merge header gate), the CL head hint buffer (`handleCLPivotHint`), the bootstrap retry backoff
  * and give-up (`bootstrapRetryDelay`, `checkBootstrapRetryTimeout`), the SNAP peers' best block
  * (`currentNetworkBestFromSnapPeers`) and the pivot's best-block and chain-weight record (`updateBestBlockForPivot`).
  * The core keeps `startSnapSync` (its accounts-complete recovery branch and coordinator spawns are not pivot
  * selection; CQ-SNAP-016-22) and the dispatch: the `Start` and `MinPivotBlock` arms of `idle`, the `CLPivotHint` arm
  * of `peerEventArms` and the `RetryBootstrapAtBlock` arm of `commonSyncingArms`.
  */
private[snap] trait PivotSelector extends PivotSelectionApi:
  self: PivotSelectorState & SnapSharedState & SnapControllerEnv & PhaseFlags & PeerPoolApi & HandshakedPeersApi &
    BootstrapPeersApi & HealingApi & LifecycleApi & ShutdownApi & SnapStartApi & SyncingApi & AccountRangeStartApi =>

  private lazy val BootstrapRetryBaseDelay = 2.seconds
  private lazy val BootstrapRetryMaxDelay = 60.seconds
  private lazy val MaxBootstrapRetries = 10

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
  private[snap] def handleCLPivotHint(hint: CLPivotHint): Unit =
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

private[snap] trait PivotSelectorStart:
  self: SNAPSyncControllerImpl =>

  /** A `commonSyncingArms` arm lifted into a class member (spec 016 restructure, CQ-SNAP-016-18/19), body unchanged:
    * `RetryBootstrapAtBlock`, the backtracked pivot-header bootstrap retry armed by `PivotBootstrapFailed`.
    * `commonSyncingArms` delegates to it at the arm's original position, so the arm order is unchanged.
    */
  private[snap] lazy val retryBootstrapAtBlockArms: PartialFunction[Command, Behavior[Command]] = {
    case RetryBootstrapAtBlock(blockNumber) =>
      if currentPhase == AccountRangeSync || currentPhase == ByteCodeAndStorageSync || currentPhase == StateHealing
      then
        ctx.log.info(s"Retrying bootstrap at backtracked block $blockNumber...")
        blockchainReader.getBlockHeaderByNumber(blockNumber) match
          case Some(header) =>
            completePivotRefreshWithStateRoot(blockNumber, header, "backtracked pivot (local header)")
          case None =>
            chainDownloader.tell(ChainDownloader.Pause)
            pendingPivotRefresh = Some((blockNumber, "backtracked pivot"))
            syncController ! StartRegularSyncBootstrap(blockNumber)
            resetStagnationClocks(StagnationWatchdog.ResetReason.BootstrapRetryAtBlock)
      else ctx.log.info(s"Skipping backtracked bootstrap — phase=$currentPhase no longer needs it")
      Behaviors.same
  }

  /** `startSnapSync`, step 2 (spec 016 restructure, CQ-SNAP-016-22): resume an interrupted header bootstrap (and break
    * out with `bootstrapping()`), or clear a target that was already reached and fall through. Extracted verbatim.
    */
  private[snap] def resumeInterruptedBootstrap()(using scala.util.boundary.Label[Behavior[Command]]): Unit =
    import scala.util.boundary.break
    // Check if there's an interrupted bootstrap to resume
    appStateStorage.getSnapSyncBootstrapTarget() match
      case Some(bootstrapTarget) =>
        val bestBlockNumber = appStateStorage.getBestBlockNumber()

        // Header-only bootstrap may not advance bestBlockNumber; allow resume if pivot header exists.
        val hasPivotHeader = blockchainReader.getBlockHeaderByNumber(bootstrapTarget).isDefined

        if bestBlockNumber >= bootstrapTarget || hasPivotHeader then
          // Bootstrap already complete - clear the target and proceed with SNAP sync
          ctx.log.info(s"Bootstrap target $bootstrapTarget already reached (current block: $bestBlockNumber)")
          appStateStorage.clearSnapSyncBootstrapTarget().commit()
          // Continue to normal SNAP sync logic below
        else
          // Resume interrupted bootstrap
          ctx.log.info(s"Resuming interrupted bootstrap: current block $bestBlockNumber / target $bootstrapTarget")
          syncController ! StartRegularSyncBootstrap(bootstrapTarget)
          break(bootstrapping())
      case None =>
      // No bootstrap in progress - continue with normal logic

  /** `startSnapSync`, step 3 (spec 016 restructure, CQ-SNAP-016-22): pivot selection. The CL-wait gate and the CL-hint
    * pivot on post-merge chains, then the network / local / genesis pivot, each ending in a `break` with the next
    * behaviour (account sync, a header bootstrap, a retry, regular sync or dormancy). Extracted verbatim; spec 016 M8c
    * moves it into `PivotSelector`.
    */
  private[snap] def selectPivotAndStart()(using scala.util.boundary.Label[Behavior[Command]]): Unit =
    import scala.util.boundary.break
    // CL-wait gate (post-merge chains only). When TTD is configured but no CL hint has
    // arrived yet, either wait indefinitely (`engine-api-required = true`, default) or
    // fall back to peer-best-by-block-number after `cl-wait-timeout` elapsed since the
    // first start attempt. Either way, we must NOT walk into TD-based selection here —
    // TD is frozen at TTD on post-merge chains and pivot selection produces useless
    // targets. Closes #1207.
    if isPoSChain && clPivotHint.isEmpty then
      val firstAttemptMs =
        clHintArrivedAtMs.getOrElse {
          // Reuse the same timestamp pattern as the hint to keep the wait window stable
          // across retry calls. We initialise on the first wait attempt.
          val now = System.currentTimeMillis()
          if clHintArrivedAtMs.isEmpty then clHintArrivedAtMs = Some(now)
          now
        }
      val waitedMs = System.currentTimeMillis() - firstAttemptMs
      if syncConfig.engineApiRequired || waitedMs < syncConfig.clWaitTimeout.toMillis then
        if bootstrapRetryCount % 10 == 0 then
          ctx.log.info(
            s"[CL-PIVOT] Post-merge chain (TTD configured), waiting for engine_forkchoiceUpdated " +
              s"from CL (engineApiRequired=${syncConfig.engineApiRequired}, waited=${waitedMs / 1000}s)"
          )
        bootstrapRetryCount += 1
        val delay = 5.seconds
        timers.cancel(BootstrapCheckKey)
        timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
        break(bootstrapping())
      else
        ctx.log.warn(
          s"[CL-PIVOT] cl-wait-timeout elapsed (${syncConfig.clWaitTimeout}); engineApiRequired=false. " +
            "Falling back to peer-best-by-block-number pivot selection."
        )
      // Fallthrough: engineApiRequired=false and timeout elapsed → continue to TD path below.
      clHintArrivedAtMs = None // reset so the wait window restarts on next retry

    // CL-driven pivot path (post-merge chains only). When the consensus layer has pushed a
    // forkchoiceUpdated, prefer its head over TD-based peer selection. TD on post-merge
    // chains is frozen at TerminalTotalDifficulty so peer-best-by-TD is unreliable. This
    // is geth's "BeaconSync" pattern, plumbed via SyncController's BeaconHead listener.
    // Closes #1207.
    if isPoSChain && clPivotHint.isDefined then
      val hint = clPivotHint.get
      hint.knownHeader match
        case Some(header) =>
          ctx.log.info("=" * 80)
          ctx.log.info("🛰  SNAP pivot from CL forkchoiceUpdated")
          ctx.log.info("=" * 80)
          ctx.log.info(
            s"CL head: ${header.number} (${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(header.hash.value)})"
          )
          ctx.log.info(s"State root: ${header.stateRoot.value.toHex.take(16)}...")
          ctx.log.info(s"Beginning fast state sync with ${snapSyncConfig.accountConcurrency} concurrent workers")
          ctx.log.info("=" * 80)

          pivotBlock = Some(header.number.value)
          stateRoot = Some(header.stateRoot)
          appStateStorage
            .putSnapSyncPivotBlock(header.number.value)
            .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
            .commit()
          updateBestBlockForPivot(header, header.number.value)

          SNAPSyncMetrics.setPivotBlockNumber(header.number.value)
          bootstrapRetryCount = 0

          currentPhase = AccountRangeSync
          startAccountRangeSync(header.stateRoot)
          break(syncing())

        case None =>
          // CL gave us a head hash but we don't have its header yet. Route through
          // SyncController to fetch by hash (PivotHeaderBootstrap by-hash mode). The
          // by-hash bootstrap reply re-enters via `BootstrapComplete(Some(header))`,
          // and `bootstrapping`'s pivot-staleness check will allow it through (the CL
          // head is by definition the freshest possible target).
          ctx.log.info(
            s"[CL-PIVOT] Head ${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(hint.headHash)} known by hash only — requesting by-hash bootstrap"
          )
          syncController ! StartRegularSyncBootstrapByHash(hint.headHash)
          break(bootstrapping())

    // Get local and network state for pivot selection
    val localBestBlock = appStateStorage.getBestBlockNumber()

    // If bootstrap checkpoints are configured, we should not start SNAP from a peer that is behind
    // the highest trusted checkpoint. SNAP servers only guarantee serving very recent state; picking
    // an ancient pivot (e.g. millions of blocks behind the network head) will cause peers to emit
    // empty/no-proof AccountRange responses.
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()

    // Query SNAP-capable peers to find the highest block in the network.
    // SNAP must NOT start until we have a SNAP-capable peer that is at/above the bootstrap pivot
    // (when configured). Falling back to non-SNAP peers here would select an unreachable state root.
    // Peers whose STATUS hasn't arrived yet have maxBlockNumber=0 — exclude them, otherwise a
    // fresh-startup race counts them as "network best=0" → pivot=-64 → genesis fallback.
    val snapPeersForPivot =
      peersToDownloadFrom.values.toList
        .filter(p => p.peerInfo.remoteStatus.supportsSnap && p.peerInfo.forkAccepted)
        .filter(_.peerInfo.maxBlockNumber > 0)
        .filter(p => bootstrapPivotBlock == 0 || p.peerInfo.maxBlockNumber >= bootstrapPivotBlock)

    val networkBestBlockOpt =
      snapPeersForPivot
        .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
        .headOption
        .map(_.peerInfo.maxBlockNumber)

    // Diagnostics: show what heights we can actually see from connected peers.
    // If this list tops out near the Core-Geth "start height", it means we simply don't have any peer
    // advertising the real network head yet (or those peers are excluded/blacklisted/disconnected).
    val topPeerHeights =
      peersToDownloadFrom.values.toList
        .sortBy(_.peerInfo.maxBlockNumber)(bigIntReverseOrdering)
        .take(5)
        .map(p => s"${p.peer.remoteAddress}=${p.peerInfo.maxBlockNumber}")
        .mkString(", ")
    ctx.log.info(
      s"SNAP pivot selection: bootstrapPivot=$bootstrapPivotBlock, visible peer heights (top 5) = [$topPeerHeights]"
    )

    // Core-geth approach: Calculate pivot from network height
    // pivot = networkHeight - pivotBlockOffset (e.g., 23M - 64 = ~23M)
    val (baseBlockForPivot, pivotSelectionSource) = networkBestBlockOpt match
      case Some(networkBestBlock) =>
        if networkBestBlock <= localBestBlock + snapSyncConfig.pivotBlockOffset then
          ctx.log.warn(
            s"Network best block ($networkBestBlock) is not significantly ahead of local ($localBestBlock)"
          )
          ctx.log.warn("This may indicate limited peer connectivity or already synced state")
        // Always use network block when available, regardless of gap size
        // This ensures we sync to the actual chain tip, not just our local view
        // Reset bootstrap retry state since we have peers
        bootstrapRetryCount = 0
        (networkBestBlock, NetworkPivot)
      case None =>
        // No peers available yet - fall back to local best block
        ctx.log.warn("No peers available for pivot selection, using local best block")
        ctx.log.warn("SNAP sync may select a suboptimal pivot - will retry if peers become available")
        (localBestBlock, LocalPivot)

    val rawPivot = baseBlockForPivot - snapSyncConfig.pivotBlockOffset
    val pivotBlockNumber = rawPivot.max(minPivotHint)
    if pivotBlockNumber > rawPivot then
      ctx.log.warn(
        s"SNAP pivot raised from $rawPivot to $pivotBlockNumber to satisfy MinPivotBlock constraint " +
          s"(regular sync was stuck near that block)"
      )

    ctx.log.info(
      s"SNAP pivot selection: localBest=$localBestBlock, networkBest=${networkBestBlockOpt.getOrElse("none")}, " +
        s"base=$baseBlockForPivot (source=${pivotSelectionSource.name}), offset=${snapSyncConfig.pivotBlockOffset}, " +
        s"pivot=$pivotBlockNumber"
    )

    // Core-geth behavior: If chain height <= pivot offset, use genesis as pivot
    // This allows SNAP sync to start immediately from any height
    if baseBlockForPivot <= snapSyncConfig.pivotBlockOffset then
      // IMPORTANT: Only apply the "start from genesis" behavior when we actually know
      // the network height (i.e., we have peers). If we have no peers, the network height
      // is unknown and treating it as 0 will cause us to request SNAP data for the genesis
      // state root, which most peers won't serve.
      pivotSelectionSource match
        case CLDrivenPivot =>
          // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
          ctx.log.warn("Unexpected CLDrivenPivot reaching TD-based pivot path; ignoring")
          break(bootstrapping())
        case LocalPivot =>
          bootstrapRetryCount += 1
          checkBootstrapRetryTimeout("no peers, network height unknown") match
            case Some(b) => break(b); case None =>
          val delay = bootstrapRetryDelay
          ctx.log.warn(
            s"No peers available yet; network height is unknown. Retrying in $delay (attempt $bootstrapRetryCount)"
          )

          timers.cancel(BootstrapCheckKey)
          timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
          break(bootstrapping())

        case NetworkPivot =>
          // Guard: if ALL snap peers have maxBlockNumber=0, heights haven't been populated yet.
          // ETH/63-68 peers initialize to 0 and only update on BlockHeaders/NewBlockHashes.
          // ETH/69 peers may also show 0 if the remote peer is also initializing.
          // Treat this as "heights unknown" — retry rather than committing to genesis.
          // go-ethereum: findBestPeer() requires bestPeer.head > 0 before pivot selection.
          if snapPeersForPivot.forall(_.peerInfo.maxBlockNumber == 0) then
            bootstrapRetryCount += 1
            checkBootstrapRetryTimeout("snap peers present but all heights unknown") match
              case Some(b) => break(b); case None =>
            val delay = 2.seconds
            ctx.log.info(
              s"[SNAP] ${snapPeersForPivot.size} snap peer(s) connected but all heights unknown — " +
                s"waiting for ETH status exchange. Retrying in $delay (attempt $bootstrapRetryCount)."
            )
            timers.cancel(BootstrapCheckKey)
            timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
            break(bootstrapping())
        // else: ETH/69 peers genuinely confirmed low network height — proceed to genesis sync

      ctx.log.info("=" * 80)
      ctx.log.info("🚀 SNAP Sync Starting from Genesis")
      ctx.log.info("=" * 80)
      ctx.log.info(s"Network height: $baseBlockForPivot blocks")
      ctx.log.info(s"Height below minimum threshold (${snapSyncConfig.pivotBlockOffset} blocks)")
      ctx.log.info(s"Using genesis (block 0) as pivot for early chain bootstrap")
      ctx.log.info("SNAP sync will effectively perform full sync for initial blocks")
      ctx.log.info("=" * 80)

      // Use genesis block as pivot (like core-geth does)
      blockchainReader.getBlockHeaderByNumber(0) match
        case Some(genesisHeader) =>
          pivotBlock = Some(BigInt(0))
          stateRoot = Some(genesisHeader.stateRoot)
          appStateStorage
            .putSnapSyncPivotBlock(0)
            .and(appStateStorage.putSnapSyncStateRoot(genesisHeader.stateRoot.value))
            .commit()
          updateBestBlockForPivot(genesisHeader, BigInt(0))

          SNAPSyncMetrics.setPivotBlockNumber(0)

          // Reset bootstrap retry state
          bootstrapRetryCount = 0

          // Start account range sync with genesis state root
          currentPhase = AccountRangeSync
          startAccountRangeSync(genesisHeader.stateRoot)
          break(syncing())

        case None =>
          ctx.log.error("Genesis block header not available - cannot start SNAP sync")
          break(enterDormantMode("genesis block header not available"))

    // With network-based pivot and 64-block offset, pivotBlockNumber should always be > 0
    // The genesis special case (above) handles when baseBlockForPivot <= 64
    // This remaining code handles the normal case where we have a valid pivot > localBestBlock

    if pivotBlockNumber <= localBestBlock then
      // Sanity check: pivot must be ahead of local state
      ctx.log.warn("=" * 80)
      ctx.log.warn("⚠️  SNAP Sync Pivot Issue Detected")
      ctx.log.warn("=" * 80)
      ctx.log.warn(s"Calculated pivot ($pivotBlockNumber) is not ahead of local state ($localBestBlock)")
      ctx.log.warn(
        s"Pivot source: ${pivotSelectionSource.name}, base block: $baseBlockForPivot, offset: ${snapSyncConfig.pivotBlockOffset}"
      )

      // LocalPivot is only used when no peers are available (see match expression above)
      // This check determines whether to retry for peers or transition to regular sync
      pivotSelectionSource match
        case CLDrivenPivot =>
          // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
          ctx.log.warn("Unexpected CLDrivenPivot in TD-based pivot-staleness check; ignoring")
          break(bootstrapping())
        case LocalPivot =>
          // No peers available - schedule retry with backoff
          bootstrapRetryCount += 1
          checkBootstrapRetryTimeout("no peers for pivot selection") match
            case Some(b) => break(b); case None =>
          val delay = bootstrapRetryDelay
          ctx.log.warn(s"No peers available for pivot selection. Retrying in $delay (attempt $bootstrapRetryCount)")

          timers.cancel(BootstrapCheckKey)
          timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)
          break(bootstrapping())

        case NetworkPivot =>
          // Pivot is at or behind local state - this means we're already synced or very close
          // Fall back to regular sync to catch up the remaining blocks
          ctx.log.warn("Pivot block is not ahead of local state - likely already synced")
          ctx.log.warn("Transitioning to regular sync for final block catch-up")
          syncController ! Done // Signal completion, which will transition to regular sync
          break(bootstrapping())
    else
      // Pivot is valid and ahead of local state

      // Even if we have a local header for this block number, fetch the pivot header from the network
      // to avoid starting SNAP from a stale/reorged or otherwise non-canonical header.
      // This is a header-only bootstrap.
      pivotSelectionSource match
        case CLDrivenPivot =>
          // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
          ctx.log.warn("Unexpected CLDrivenPivot in TD pivot-bootstrap branch; ignoring")
          break(bootstrapping())
        case NetworkPivot =>
          ctx.log.info(s"Fetching pivot header from network for block $pivotBlockNumber before starting SNAP")
          appStateStorage.putSnapSyncBootstrapTarget(pivotBlockNumber).commit()
          syncController ! StartRegularSyncBootstrap(pivotBlockNumber)
          break(bootstrapping())
        case LocalPivot =>
        // Fall through to local-header availability checks below

      // Check if we have the pivot block header locally
      blockchainReader.getBlockHeaderByNumber(pivotBlockNumber) match
        case Some(header) =>
          // Pivot header is available - proceed with SNAP sync
          pivotBlock = Some(pivotBlockNumber)
          stateRoot = Some(header.stateRoot)
          appStateStorage
            .putSnapSyncPivotBlock(pivotBlockNumber)
            .and(appStateStorage.putSnapSyncStateRoot(header.stateRoot.value))
            .commit()
          updateBestBlockForPivot(header, pivotBlockNumber)

          // Update metrics - pivot block
          SNAPSyncMetrics.setPivotBlockNumber(pivotBlockNumber)

          // Reset bootstrap retry state
          bootstrapRetryCount = 0

          ctx.log.info(s"Local pivot header available for block $pivotBlockNumber, starting SNAP sync")

          // Start account range sync
          currentPhase = AccountRangeSync
          startAccountRangeSync(header.stateRoot)
          break(syncing())

        case None =>
          // Pivot block header not available locally
          // This happens when we select a pivot based on network best block
          // but haven't synced that far yet

          pivotSelectionSource match
            case CLDrivenPivot =>
              // Unreachable: CLDrivenPivot returns earlier in startSnapSync. Defensive no-op.
              ctx.log.warn("Unexpected CLDrivenPivot in pivot-header-fetch branch; ignoring")
              break(bootstrapping())
            case NetworkPivot =>
              // We selected a network-based pivot but don't have the header yet
              // Need to bootstrap/sync to get closer to the pivot
              val targetForBootstrap = pivotBlockNumber

              ctx.log.info("=" * 80)
              ctx.log.info("🔄 SNAP Sync Pivot Header Not Available")
              ctx.log.info("=" * 80)
              ctx.log.info(s"Selected pivot: $pivotBlockNumber (based on network best block)")
              ctx.log.info(s"Local best block: $localBestBlock")
              ctx.log.info(s"Gap: ${pivotBlockNumber - localBestBlock} blocks")
              ctx.log.info("Need to sync headers/blocks to reach pivot point")
              ctx.log.info(s"Continuing regular sync to block $targetForBootstrap")
              ctx.log.info("Will automatically transition to SNAP sync once pivot is reached")
              ctx.log.info("=" * 80)

              // Store the bootstrap target
              appStateStorage.putSnapSyncBootstrapTarget(targetForBootstrap).commit()

              // Request regular sync to continue to the pivot point
              syncController ! StartRegularSyncBootstrap(targetForBootstrap)
              break(bootstrapping())

            case LocalPivot =>
              // Local pivot selected but header still not available
              bootstrapRetryCount += 1
              checkBootstrapRetryTimeout("pivot header not available") match
                case Some(b) => break(b); case None =>
              val delay = bootstrapRetryDelay
              if bootstrapRetryCount == 1 then ctx.log.info("Waiting for pivot block header to become available...")
              ctx.log.info(
                s"   Pivot block $pivotBlockNumber not ready yet (attempt $bootstrapRetryCount), retrying in $delay"
              )

              // Schedule a retry and store the cancellable for proper cleanup
              timers.cancel(BootstrapCheckKey)
              timers.startSingleTimer(BootstrapCheckKey, RetrySnapSyncStart, delay)

              break(bootstrapping())
