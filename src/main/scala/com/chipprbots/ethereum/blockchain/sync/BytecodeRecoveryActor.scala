package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MptVisitors.*
import com.chipprbots.ethereum.network.Peer

/** Bytecode recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts whose bytecodes are missing from
  * evmCodeStorage (due to the Bug 20 phase handoff timeout). Collects missing codeHashes and downloads them via SNAP
  * protocol using ByteCodeCoordinator.
  *
  * Pekko Typed actor (`Behavior[Command]`, narrowed S5): SyncController sends both `ByteCodePeerAvailable` and
  * `ByteCodeSyncComplete` / `ProgressBytecodesDownloaded` to this actor. SyncController holds a Classic-visible ref via
  * the CAPSTONE co-existence bridge; all inbound messages are members of the sealed `Command` ADT, so `Behavior[Any]`
  * is no longer needed — all Typed machinery (named behavior functions, `Behaviors.withTimers`, `watchWith`) is active.
  *
  * Lifecycle:
  *   1. Walk state trie, collect missing codeHashes (deduplicated) 2. If none missing → mark recovery done, report to
  *      SyncController 3. If missing → download via ByteCodeCoordinator, then mark done
  */
object BytecodeRecoveryActor:

  sealed trait Command
  private case class ScanResult(missingCodeHashes: Seq[ByteString]) extends Command
  private case class CheckAbandon(progressSeq: Long) extends Command
  private case object CoordinatorTerminated extends Command
  // SyncController forwards peer-available events here; BCA re-forwards to BCC
  case class ByteCodePeerAvailable(peer: Peer) extends Command
  // Adapter-mapped SSC replies — private[sync] so tests can inject them directly
  private[sync] case object ByteCodeDownloadComplete extends Command
  private[sync] case class ByteCodeDownloadProgress(n: Long) extends Command
  // Catch-all for unexpected SSC messages arriving via the adapter
  private case object DroppedBccMsg extends Command
  // SyncController relays SNAP peer responses here so ByteCodeCoordinator receives them
  // (no SNAPSyncController exists during recovery — SyncController acts as the routing relay).
  private[sync] case class ForwardByteCodesResponse(msg: ByteCodes) extends Command

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

  def apply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[RecoveryComplete.type],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded = None,
    coordinatorForTesting = None
  )

  /** Download-only variant: skip the scan and go straight to downloading the supplied missing codeHashes (produced by
    * the combined parallel scan). Used by `SyncController` when `parallel-recovery-scan` is on.
    */
  def applyPreloaded(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[RecoveryComplete.type],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[ByteString]
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded = Some(missing),
    coordinatorForTesting = None
  )

  /** Test entry point: exposes the preloaded-missing and coordinator-injection hooks. */
  private[sync] def testApply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[RecoveryComplete.type],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[ByteString]] = None,
      coordinatorForTesting: Option[ActorRef] = None
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded,
    coordinatorForTesting
  )

  private def scanning(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[RecoveryComplete.type],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[ByteString]],
      coordinatorForTesting: Option[ActorRef]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val asyncLog = LoggerFactory.getLogger(getClass)
      preloaded match
        case Some(missing) =>
          ctx.self ! ScanResult(missing)
        case None =>
          ctx.log.info(
            s"BytecodeRecoveryActor starting: scanning state trie for missing bytecodes " +
              s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
          )
          ctx.pipeToSelf(
            Future(scanForMissingBytecodes(stateRoot, stateStorage, evmCodeStorage, pivotBlockNumber, ctx.log))(
              ctx.executionContext
            )
          ) {
            case Success(result) => ScanResult(result)
            case Failure(ex) =>
              asyncLog.error("Bytecode recovery scan failed", ex)
              ScanResult(Seq.empty)
          }
      Behaviors.receiveMessage {
        case ScanResult(missing) =>
          if missing.isEmpty then
            ctx.log.info("Bytecode recovery: all contract bytecodes present. Marking recovery complete.")
            RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
            appStateStorage.bytecodeRecoveryDone().commit()
            syncController ! RecoveryComplete
            Behaviors.stopped
          else
            ctx.log.warn(
              s"Bytecode recovery: found ${missing.size} missing bytecodes. Starting download..."
            )
            RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseDownloading)
            val bccAdapter: org.apache.pekko.actor.typed.ActorRef[snap.SNAPSyncController.Command] =
              ctx.messageAdapter {
                case snap.SNAPSyncController.ByteCodeSyncComplete           => ByteCodeDownloadComplete
                case snap.SNAPSyncController.ProgressBytecodesDownloaded(n) => ByteCodeDownloadProgress(n)
                case _                                                      => DroppedBccMsg
              }
            val coordinator: org.apache.pekko.actor.typed.ActorRef[snap.actors.ByteCodeCoordinator.Command] =
              coordinatorForTesting match
                case Some(testRef) => testRef.toTyped[snap.actors.ByteCodeCoordinator.Command]
                case None =>
                  val requestTracker = new snap.SNAPRequestTracker()(ctx.system.classicSystem.scheduler)
                  ctx.spawn(
                    Behaviors
                      .supervise(
                        snap.actors.ByteCodeCoordinator(
                          evmCodeStorage = evmCodeStorage,
                          networkPeerManager = networkPeerManager,
                          requestTracker = requestTracker,
                          batchSize = snap.ByteCodeTask.DEFAULT_BATCH_SIZE,
                          snapSyncController = bccAdapter
                        )
                      )
                      .onFailure[Throwable](
                        SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
                      ),
                    "bytecode-recovery-coordinator",
                    org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
                  )
            ctx.watchWith(coordinator, CoordinatorTerminated)
            coordinator ! snap.actors.ByteCodeCoordinator.StartByteCodeSync(missing)
            // The whole gap list is known up front. Without this the coordinator waits for more tasks that never come
            // ("accumulating") and never announces completion, so recovery only ended when the no-progress abandon timer
            // fired, however quickly the bytecodes had arrived.
            coordinator ! snap.actors.ByteCodeCoordinator.NoMoreByteCodeTasks
            downloading(ctx, coordinator, missing.size, syncController, appStateStorage, snapSyncConfig)

        case _ => Behaviors.unhandled
      }
    }

  private def downloading(
      ctx: ActorContext[Command],
      coordinator: org.apache.pekko.actor.typed.ActorRef[snap.actors.ByteCodeCoordinator.Command],
      expectedCount: Int,
      syncController: TypedActorRef[RecoveryComplete.type],
      appStateStorage: AppStateStorage,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Command] =
    var progressSeq = 0L
    var downloadedCount = 0L
    var lastBytecodeRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateDownloaded = 0L
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout

    Behaviors.withTimers { timers =>
      timers.startSingleTimer("abandon", CheckAbandon(0L), abandonAfter)

      def recordProgress(): Unit =
        progressSeq += 1
        timers.cancel("abandon")

      def finishRecovery(): Behavior[Command] =
        timers.cancel("abandon")
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
        appStateStorage.bytecodeRecoveryDone().commit()
        syncController ! RecoveryComplete
        Behaviors.stopped

      Behaviors.receiveMessage {
        case ByteCodePeerAvailable(peer) =>
          coordinator ! snap.actors.ByteCodeCoordinator.ByteCodePeerAvailable(peer)
          Behaviors.same

        case ByteCodeDownloadComplete =>
          ctx.log.info(
            s"[SNAP-PROGRESS] BYTECODE-RECOVERY 100% — $expectedCount / $expectedCount bytecodes recovered — COMPLETE"
          )
          RecoveryMetrics.setBytecodeDownloaded(expectedCount.toLong)
          RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
          finishRecovery()

        case ByteCodeDownloadProgress(_) =>
          downloadedCount += 1
          RecoveryMetrics.setBytecodeDownloaded(downloadedCount)
          recordProgress()
          downloadedCount += 1
          val (newM, crossed) =
            ProgressMilestones.crossed(downloadedCount, expectedCount.toLong, lastBytecodeRecoveryMilestone)
          lastBytecodeRecoveryMilestone = newM
          crossed.foreach { m =>
            val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
            val rate = if elapsedSecs > 0 then ((downloadedCount - lastRateDownloaded) / elapsedSecs).toLong else 0L
            if m % 10 == 0 || m <= 5 || m >= 95 then
              lastRateNanos = System.nanoTime()
              lastRateDownloaded = downloadedCount
            ctx.log.info(
              s"[SNAP-PROGRESS] BYTECODE-RECOVERY $m% — $downloadedCount / $expectedCount bytecodes | $rate bytecodes/s"
            )
          }
          Behaviors.same

        case CheckAbandon(progressAtSchedule) =>
          if progressAtSchedule == progressSeq then
            ctx.log.warn(
              "Bytecode recovery abandoned: no download progress for {}s. " +
                "Regular sync will fetch missing bytecodes on-demand via GetTrieNodes.",
              abandonAfter.toSeconds
            )
            finishRecovery()
          else Behaviors.same

        case CoordinatorTerminated =>
          ctx.log.error(
            "ByteCodeCoordinator crashed unexpectedly. Marking bytecode recovery done to unblock sync."
          )
          finishRecovery()

        case DroppedBccMsg => Behaviors.same

        case ForwardByteCodesResponse(msg) =>
          coordinator ! snap.actors.ByteCodeCoordinator.ByteCodesResponseMsg(msg)
          Behaviors.same

        case ScanResult(_) => Behaviors.unhandled
      }
    }

  private def scanForMissingBytecodes(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      pivotBlockNumber: BigInt,
      log: Logger
  ): Seq[ByteString] =
    RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseScanning)
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[ByteString]
    val seen = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L

    val onLeaf: LeafNode => Unit = leafNode =>
      accountCount += 1
      if accountCount % 100_000 == 0 then
        RecoveryMetrics.setBytecodeScanProgress(accountCount, contractCount, missing.size.toLong)
      if accountCount % 1_000_000 == 0 then
        log.info(
          s"Bytecode recovery scan: $accountCount accounts, $contractCount contracts, ${missing.size} missing"
        )

      Account(leafNode.value) match
        case Success(account) =>
          if account.codeHash != Account.EmptyCodeHash && !seen.contains(account.codeHash.value) then
            seen += account.codeHash.value
            contractCount += 1
            if evmCodeStorage.get(account.codeHash.value).isEmpty then missing += account.codeHash.value
        case Failure(_) => // Skip malformed account RLP
    try
      val visitor = new LeafWalkVisitor(mptStorage, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    catch
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing bytecodes",
          e
        )

    log.info(
      s"Bytecode recovery scan complete: $accountCount accounts, $contractCount contracts, ${missing.size} missing bytecodes"
    )
    RecoveryMetrics.setBytecodeScanProgress(accountCount, contractCount, missing.size.toLong)
    missing.toSeq
