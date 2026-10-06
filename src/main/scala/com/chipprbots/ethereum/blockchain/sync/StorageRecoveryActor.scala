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

import com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MptVisitors.*
import com.chipprbots.ethereum.network.Peer

/** Storage recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts whose storage tries are missing from
  * MptStorage (due to the Bug 20 phase handoff timeout). Collects missing (accountHash, storageRoot) pairs and
  * downloads them via SNAP protocol using StorageRangeCoordinator.
  *
  * Runs concurrently with BytecodeRecoveryActor — they target different storage backends (MptStorage vs EvmCodeStorage)
  * with no data dependency.
  *
  * Pekko Typed actor (`Behavior[Command]`, narrowed S5): SyncController sends `StoragePeerAvailable`, `RecentRoot`, and
  * coordinator messages; all are members of the sealed `Command` ADT. SyncController holds a Classic-visible ref via
  * the CAPSTONE co-existence bridge; `Behavior[Any]` is no longer needed — all Typed machinery active.
  *
  * Lifecycle:
  *   1. Walk state trie, find contracts with missing storage tries 2. If none missing → mark recovery done, report to
  *      SyncController 3. If missing → download via StorageRangeCoordinator, then mark done
  */
object StorageRecoveryActor:

  sealed trait Command
  private case class ScanResult(missingStorage: Seq[(ByteString, ByteString)]) extends Command
  // Delayed self-ping for Bug 30b abandon path. Carries the progress counter current at arm time;
  // if progressSeq still matches on fire, nothing moved and we give up.
  private[sync] case class CheckAbandon(progressAtSchedule: Long) extends Command
  private case object CoordinatorTerminated extends Command
  // SyncController forwards peer-available events here; SRA re-forwards to SRC
  case class StoragePeerAvailable(peer: Peer) extends Command
  // Adapter-mapped SSC replies — private[sync] so tests can inject them directly
  private[sync] case object StorageRangeDone extends Command
  private[sync] case class StorageSlotProgress(n: Long) extends Command
  private[sync] case class PivotUnservable(rootHash: ByteString, reason: String, emptyResponses: Int) extends Command
  // Catch-all for unexpected SSC messages arriving via the adapter
  private case object DroppedSrcMsg extends Command
  // SyncController relays SNAP peer responses here so StorageRangeCoordinator receives them
  // (no SNAPSyncController exists during recovery — SyncController acts as the routing relay).
  private[sync] case class ForwardStorageRangesResponse(msg: StorageRanges) extends Command

  /** SyncController → recovery: a recent canonical `(blockNumber, stateRoot)`, or `stateRoot = None` if none could be
    * fetched (no peers / bootstrap failed).
    */
  final case class RecentRoot(blockNumber: BigInt, stateRoot: Option[ByteString]) extends Command

  /** Sealed umbrella for the two message types sent from StorageRecoveryActor to SyncController. Enables a narrow
    * `TypedActorRef[SyncControllerMsg]` adapter instead of `TypedActorRef[Any]`.
    */
  sealed trait SyncControllerMsg

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete extends SyncControllerMsg

  /** Recovery → SyncController: the saved pivot root has aged out of every peer's snapshot serve window, so storage
    * downloads are returning empty. Please fetch a RECENT canonical header from a peer and reply with [[RecentRoot]] so
    * the download can roll onto a root peers can still serve. Carries `replyTo` so SyncController does not need
    * `sender()` to reply.
    */
  case class RequestRecentRoot(replyTo: TypedActorRef[StorageRecoveryActor.Command]) extends SyncControllerMsg

  def apply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    appStateStorage,
    flatSlotStorage,
    networkPeerManager,
    syncController,
    pivotBlockNumber,
    snapSyncConfig,
    preloaded = None,
    coordinatorForTesting = None
  )

  /** Download-only variant: skip the scan and go straight to downloading the supplied missing storage tries (produced
    * by the combined parallel scan). Used by `SyncController` when `parallel-recovery-scan` is on.
    */
  def applyPreloaded(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[(ByteString, ByteString)]
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    appStateStorage,
    flatSlotStorage,
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
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[(ByteString, ByteString)]] = None,
      coordinatorForTesting: Option[ActorRef] = None
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    appStateStorage,
    flatSlotStorage,
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
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      preloaded: Option[Seq[(ByteString, ByteString)]],
      coordinatorForTesting: Option[ActorRef]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val asyncLog = LoggerFactory.getLogger(getClass)
      preloaded match
        case Some(missing) =>
          ctx.self ! ScanResult(missing)
        case None =>
          ctx.log.info(
            s"StorageRecoveryActor starting: scanning state trie for missing contract storage " +
              s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
          )
          ctx.pipeToSelf(
            Future(scanForMissingStorage(stateRoot, stateStorage, pivotBlockNumber, ctx.log))(ctx.executionContext)
          ) {
            case Success(result) => ScanResult(result)
            case Failure(ex) =>
              asyncLog.error("Storage recovery scan failed", ex)
              ScanResult(Seq.empty)
          }
      Behaviors.receiveMessage {
        case ScanResult(missing) =>
          if missing.isEmpty then
            ctx.log.info("Storage recovery: all contract storage tries present. Marking recovery complete.")
            RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
            appStateStorage.storageRecoveryDone().commit()
            syncController ! RecoveryComplete
            Behaviors.stopped
          else
            ctx.log.warn(
              s"Storage recovery: found ${missing.size} contracts with missing storage. Starting download..."
            )
            RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseDownloading)

            val srcAdapter: org.apache.pekko.actor.typed.ActorRef[snap.SNAPSyncController.Command] =
              ctx.messageAdapter {
                case snap.SNAPSyncController.StorageRangeSyncComplete                => StorageRangeDone
                case snap.SNAPSyncController.ProgressStorageSlotsSynced(n)           => StorageSlotProgress(n)
                case snap.SNAPSyncController.PivotStateUnservable(r, reason, cnt, _) => PivotUnservable(r, reason, cnt)
                case _                                                               => DroppedSrcMsg
              }
            val coordinator: org.apache.pekko.actor.typed.ActorRef[actors.StorageRangeCoordinator.Command] =
              coordinatorForTesting match
                case Some(testRef) => testRef.toTyped[actors.StorageRangeCoordinator.Command]
                case None =>
                  val requestTracker = new snap.SNAPRequestTracker()(ctx.system.classicSystem.scheduler)
                  val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
                  ctx.spawn(
                    Behaviors
                      .supervise(
                        actors.StorageRangeCoordinator(
                          stateRoot = stateRoot,
                          networkPeerManager = networkPeerManager,
                          requestTracker = requestTracker,
                          mptStorage = mptStorage,
                          flatSlotStorage = flatSlotStorage,
                          maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
                          maxInFlightRequests = snapSyncConfig.storageConcurrency,
                          requestTimeout = snapSyncConfig.timeout,
                          snapSyncController = srcAdapter,
                          initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
                          minResponseBytes = snapSyncConfig.storageMinResponseBytes
                        )
                      )
                      .onFailure[Throwable](
                        SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
                      ),
                    "storage-recovery-coordinator",
                    org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
                  )

            ctx.watchWith(coordinator, CoordinatorTerminated)

            val batchSize = 10000
            var totalSent = 0
            missing.grouped(batchSize).foreach { batch =>
              val tasks = batch.map { case (accountHash, storageRoot) =>
                StorageTask.createStorageTask(accountHash, storageRoot)
              }
              coordinator ! actors.StorageRangeCoordinator.AddStorageTasks(tasks)
              totalSent += tasks.size
            }
            ctx.log.info(
              s"Sent $totalSent storage tasks to coordinator in ${(totalSent + batchSize - 1) / batchSize} batches"
            )

            downloading(
              ctx,
              coordinator,
              missing,
              stateRoot,
              stateStorage,
              pivotBlockNumber,
              syncController,
              appStateStorage,
              snapSyncConfig
            )

        case _ => Behaviors.unhandled
      }
    }

  private def downloading(
      ctx: ActorContext[Command],
      coordinator: org.apache.pekko.actor.typed.ActorRef[actors.StorageRangeCoordinator.Command],
      missing: Seq[(ByteString, ByteString)],
      stateRoot: ByteString,
      stateStorage: StateStorage,
      pivotBlockNumber: BigInt,
      syncController: TypedActorRef[SyncControllerMsg],
      appStateStorage: AppStateStorage,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Command] =
    val expectedCount = missing.size
    var progressSeq = 0L
    var downloadedCount = 0L
    var lastProgressNanos = System.nanoTime()
    var unservableCount = 0
    var recoveredCount = 0L
    var lastStorageRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateRecovered = 0L

    var currentRoot: ByteString = stateRoot
    var rollsAttempted: Int = 0
    var awaitingRoot: Boolean = false
    val maxRolls: Int = snapSyncConfig.storageRecoveryMaxRootRolls
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout

    Behaviors.withTimers { timers =>

      def recordProgress(): Unit =
        progressSeq += 1
        lastProgressNanos = System.nanoTime()
        unservableCount = 0
        timers.cancel("abandon")
        rollsAttempted = 0
        awaitingRoot = false

      def scheduleAbandonCheck(): Unit =
        timers.cancel("abandon")
        timers.startSingleTimer("abandon", CheckAbandon(progressSeq), abandonAfter)

      def finishRecovery(reason: String): Behavior[Command] =
        timers.cancel("abandon")
        logResidualGaps(missing, stateStorage, pivotBlockNumber, ctx.log)
        RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
        appStateStorage.storageRecoveryDone().commit()
        ctx.log.info(s"Storage recovery finished ($reason).")
        syncController ! RecoveryComplete
        Behaviors.stopped

      Behaviors.receiveMessage {
        case StoragePeerAvailable(peer) =>
          coordinator ! actors.StorageRangeCoordinator.StoragePeerAvailable(peer)
          Behaviors.same

        case StorageRangeDone =>
          ctx.log.info(
            s"[SNAP-PROGRESS] STORAGE-RECOVERY 100% — $expectedCount / $expectedCount storage roots recovered — COMPLETE"
          )
          RecoveryMetrics.setStorageDownloaded(expectedCount.toLong)
          finishRecovery(s"downloaded storage for $expectedCount contracts")

        case StorageSlotProgress(_) =>
          downloadedCount += 1
          RecoveryMetrics.setStorageDownloaded(downloadedCount)
          recordProgress()
          recoveredCount += 1
          val (newM, crossed) =
            ProgressMilestones.crossed(recoveredCount, expectedCount.toLong, lastStorageRecoveryMilestone)
          lastStorageRecoveryMilestone = newM
          crossed.foreach { m =>
            val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
            val rate = if elapsedSecs > 0 then ((recoveredCount - lastRateRecovered) / elapsedSecs).toLong else 0L
            if m % 10 == 0 || m <= 5 || m >= 95 then
              lastRateNanos = System.nanoTime()
              lastRateRecovered = recoveredCount
            ctx.log.info(
              s"[SNAP-PROGRESS] STORAGE-RECOVERY $m% — $recoveredCount / $expectedCount storage roots | $rate roots/s"
            )
          }
          Behaviors.same

        case PivotUnservable(_, _, _) =>
          unservableCount += 1
          if unservableCount <= 3 || unservableCount % 100 == 0 then
            ctx.log.info(
              "Storage recovery: coordinator reports root {} unservable ({} events, no progress for {}s).",
              currentRoot.take(4).toArray.map("%02x".format(_)).mkString,
              unservableCount,
              (System.nanoTime() - lastProgressNanos) / 1_000_000_000L
            )
          if !timers.isTimerActive("abandon") then scheduleAbandonCheck()
          if !awaitingRoot && rollsAttempted < maxRolls then
            awaitingRoot = true
            ctx.log.info(
              "Storage recovery: requesting a recent root to roll off the aged pivot (roll {} of {}).",
              rollsAttempted + 1,
              maxRolls
            )
            syncController ! RequestRecentRoot(ctx.self)
          else if rollsAttempted >= maxRolls then
            ctx.log.info(
              "Storage recovery: exhausted {} recent-root rolls; letting the abandon timer run for the residue.",
              maxRolls
            )
          Behaviors.same

        case RecentRoot(_, _) if !awaitingRoot =>
          ctx.log.debug("Storage recovery: ignoring stale recent-root reply (no roll pending).")
          Behaviors.same

        case RecentRoot(blockNumber, rootOpt) =>
          awaitingRoot = false
          rootOpt match
            case Some(root) if root != currentRoot =>
              val oldRoot = currentRoot
              rollsAttempted += 1
              currentRoot = root
              timers.cancel("abandon")
              unservableCount = 0
              val oldHex = oldRoot.take(4).toArray.map("%02x".format(_)).mkString
              val newHex = root.take(4).toArray.map("%02x".format(_)).mkString
              ctx.log.warn(
                s"Storage recovery: rolling download root $oldHex -> $newHex (block $blockNumber, " +
                  s"roll $rollsAttempted/$maxRolls). Re-queuing $expectedCount tasks."
              )
              coordinator ! actors.StorageRangeCoordinator.StoragePivotRefreshed(root)
            case Some(_) =>
              ctx.log.info(
                "Storage recovery: recent root equals current download root — no newer servable pivot. " +
                  "Letting the abandon timer run."
              )
            case None =>
              ctx.log.info("Storage recovery: no recent root available to roll to. Letting the abandon timer run.")
          Behaviors.same

        case CheckAbandon(progressAtSchedule) =>
          if progressAtSchedule == progressSeq then
            ctx.log.warn(
              "Storage recovery abandoning download: no slot progress for {}s after {} unservable events and {} " +
                "root roll(s). Remaining contract storage will be fetched on-demand via GetTrieNodes during regular sync.",
              abandonAfter.toSeconds,
              unservableCount,
              rollsAttempted
            )
            finishRecovery("abandoned: download stalled")
          else Behaviors.same

        case CoordinatorTerminated =>
          ctx.log.error(
            "StorageRangeCoordinator crashed unexpectedly. Marking storage recovery done to unblock sync."
          )
          finishRecovery("coordinator crashed")

        case DroppedSrcMsg => Behaviors.same

        case ForwardStorageRangesResponse(msg) =>
          coordinator ! actors.StorageRangeCoordinator.StorageRangesResponseMsg(msg)
          Behaviors.same

        case ScanResult(_) => Behaviors.unhandled
      }
    }

  private def logResidualGaps(
      missing: Seq[(ByteString, ByteString)],
      stateStorage: StateStorage,
      pivotBlockNumber: BigInt,
      log: Logger
  ): Unit =
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val residual = missing.count { case (_, storageRoot) =>
      try
        mptStorage.get(storageRoot.toArray)
        false
      catch case _: MerklePatriciaTrie.MPTException => true
    }
    if residual == 0 then log.info(s"Storage recovery: all ${missing.size} contract storage tries present on disk.")
    else
      log.warn(
        s"Storage recovery finishing: ${missing.size - residual} of ${missing.size} storage gaps filled, " +
          s"$residual residual (hot contracts changed since pivot, or never served). Regular sync will fetch " +
          s"these on-demand via GetTrieNodes when block execution reaches them."
      )

  private def scanForMissingStorage(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      pivotBlockNumber: BigInt,
      log: Logger
  ): Seq[(ByteString, ByteString)] =
    RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseScanning)
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    val seenRoots = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L
    var checkedCount = 0L

    val onLeaf: (ByteString, LeafNode) => Unit = (accountHash, leafNode) =>
      accountCount += 1
      if accountCount % 100_000 == 0 then
        RecoveryMetrics.setStorageScanProgress(accountCount, contractCount, missing.size.toLong)
      if accountCount % 1_000_000 == 0 then
        log.info(
          s"Storage recovery scan: $accountCount accounts, $contractCount contracts, " +
            s"$checkedCount checked, ${missing.size} missing"
        )

      Account(leafNode.value) match
        case Success(account) =>
          if account.storageRoot != Account.EmptyStorageRootHash then
            contractCount += 1
            if !seenRoots.contains(account.storageRoot.value) then
              seenRoots += account.storageRoot.value
              checkedCount += 1
              try mptStorage.get(account.storageRoot.toArray)
              catch
                case _: MerklePatriciaTrie.MPTException =>
                  missing += ((accountHash, account.storageRoot.value))
        case Failure(_) => // Skip malformed account RLP
    try
      val visitor = new PathTrackingLeafWalkVisitor(mptStorage, ByteString.empty, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    catch
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing storage tries",
          e
        )

    log.info(
      s"Storage recovery scan complete: $accountCount accounts, $contractCount contracts, " +
        s"$checkedCount unique storage roots checked, ${missing.size} missing"
    )
    RecoveryMetrics.setStorageScanProgress(accountCount, contractCount, missing.size.toLong)
    missing.toSeq
