package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.concurrent.duration._

import scala.util.{Success, Failure}

import com.chipprbots.ethereum.db.storage.{AppStateStorage, FlatAccountStorage, FlatSlotStorage, StateStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.mpt.MptVisitors._
import com.chipprbots.ethereum.blockchain.sync.snap.{SNAPSyncConfig, SNAPSyncController, StorageTask}
import com.chipprbots.ethereum.blockchain.sync.snap.actors

/** Storage recovery actor for Bug 20 hardening.
  *
  * Performs a sequential forward scan of FlatAccountStorage (seekFrom) — O(n) sequential
  * RocksDB reads vs the old MPT walk's random reads. Downloads missing storage tries
  * concurrently with the scan via StorageRangeCoordinator.
  *
  * Lifecycle:
  *   1. StartScan: create coordinator eagerly, launch flat scan on recovery-scan-dispatcher
  *   2. ScanBatch events stream tasks to the coordinator as they are found
  *   3. ScanComplete: seal the coordinator with NoMoreStorageTasks, become downloading
  *   4. StorageRangeSyncComplete: mark recovery done, report to SyncController
  */
class StorageRecoveryActor(
    stateRoot: ByteString,
    stateStorage: StateStorage,
    appStateStorage: AppStateStorage,
    flatSlotStorage: FlatSlotStorage,
    flatAccountStorage: FlatAccountStorage,
    networkPeerManager: ActorRef,
    syncController: ActorRef,
    pivotBlockNumber: BigInt,
    snapSyncConfig: SNAPSyncConfig,
    // Test hook: when set, the actor skips the real scan and enters `receive` with a ScanResult.
    preloadedMissingForTesting: Option[Seq[(ByteString, ByteString)]] = None,
    // Test hook: when set, the scanning/downloading states use this ref instead of spawning
    // a real StorageRangeCoordinator.
    coordinatorForTesting: Option[ActorRef] = None
) extends Actor
    with ActorLogging {

  import StorageRecoveryActor._
  import context.dispatcher

  private val scanEc: ExecutionContext =
    context.system.dispatchers.lookup("recovery-scan-dispatcher")

  override def preStart(): Unit = preloadedMissingForTesting match {
    case Some(missing) =>
      self ! ScanResult(missing)
    case None =>
      log.info(
        s"[STORAGE-RECOVERY] starting flat scan " +
          s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}..., pivotBlock=$pivotBlockNumber)"
      )
      self ! StartScan
  }

  override def receive: Receive = {
    case StartScan =>
      val coordinator = makeCoordinator()
      context.watch(coordinator)
      launchFlatScan()
      context.become(scanning(coordinator, sentSoFar = 0))

    // Legacy path for preloadedMissingForTesting — sends all tasks at once and seals.
    case ScanResult(missing) =>
      if (missing.isEmpty) {
        log.info("[STORAGE-RECOVERY] [scan] complete — no missing storage found")
        appStateStorage.storageRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)
      } else {
        log.warning(s"[STORAGE-RECOVERY] found ${missing.size} contracts with missing storage. Starting download...")
        implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler
        val coordinator = makeCoordinator()
        context.watch(coordinator)
        val batchSize = 10000
        var totalSent = 0
        missing.grouped(batchSize).foreach { batch =>
          val tasks = batch.map { case (accountHash, storageRoot) =>
            StorageTask.createStorageTask(accountHash, storageRoot)
          }
          coordinator ! actors.Messages.AddStorageTasks(tasks)
          totalSent += tasks.size
        }
        log.info(
          s"[STORAGE-RECOVERY] sent $totalSent storage tasks to coordinator in ${(totalSent + batchSize - 1) / batchSize} batches"
        )
        coordinator ! actors.Messages.NoMoreStorageTasks
        context.become(downloading(coordinator, missing.size))
      }
  }

  private def makeCoordinator(): ActorRef =
    coordinatorForTesting.getOrElse {
      implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler
      val requestTracker = new snap.SNAPRequestTracker()
      val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
      context.actorOf(
        actors.StorageRangeCoordinator
          .props(
            stateRoot = stateRoot,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            mptStorage = mptStorage,
            flatSlotStorage = flatSlotStorage,
            maxAccountsPerBatch = snapSyncConfig.storageBatchSize,
            maxInFlightRequests = snapSyncConfig.storageConcurrency,
            requestTimeout = snapSyncConfig.timeout,
            snapSyncController = self,
            initialResponseBytes = snapSyncConfig.storageInitialResponseBytes,
            minResponseBytes = snapSyncConfig.storageMinResponseBytes
          )
          .withDispatcher("sync-dispatcher"),
        "storage-recovery-coordinator"
      )
    }

  private def launchFlatScan(batchFlushSize: Int = 10_000): Unit = {
    val selfRef = self
    val stRoot = stateRoot
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val fas = flatAccountStorage
    val approxTotal = fas.approximateKeyCount()
    Future {
      blocking {
        val seenRoots = mutable.HashSet.empty[ByteString]
        val pending = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
        var accounts = 0L
        var contracts = 0L
        var checked = 0L
        var lastNanos = System.nanoTime()
        var lastScanMilestonePct: Int = -1
        var lastHeartbeatNanos = System.nanoTime()
        var lastHeartbeatAccounts = 0L

        fas.seekFrom(ByteString.empty)
          .evalMap {
            case Right((accountHash, rlpBytes)) =>
              IO {
                accounts += 1
                if (accounts % 1_000_000 == 0) {
                  val rate = (1_000_000 / ((System.nanoTime() - lastNanos) / 1e9)).toLong
                  val pctStr = if (approxTotal > 0) s" | ${(accounts * 100.0 / approxTotal).toInt}%" else ""
                  log.info(
                    s"[STORAGE-RECOVERY] [scan] $accounts accounts$pctStr | $contracts contracts | " +
                      s"$checked unique roots checked | ${pending.size} pending | $rate accts/s"
                  )
                  if (approxTotal > 0) {
                    val (newM, crossed) =
                      ProgressMilestones.crossed(accounts, approxTotal, lastScanMilestonePct)
                    lastScanMilestonePct = newM
                    crossed.foreach(m =>
                      log.info(
                        s"[STORAGE-RECOVERY] [scan] $m% — $accounts / ~$approxTotal accounts scanned"
                      )
                    )
                  }
                  lastNanos = System.nanoTime()
                }
                val nowNanos = System.nanoTime()
                if (nowNanos - lastHeartbeatNanos > 30_000_000_000L) {
                  val elapsedSecs = (nowNanos - lastHeartbeatNanos) / 1e9
                  val heartbeatRate = ((accounts - lastHeartbeatAccounts) / elapsedSecs).toLong
                  val pctStr = if (approxTotal > 0) s" | ${(accounts * 100.0 / approxTotal).toInt}%" else ""
                  log.info(
                    s"[STORAGE-RECOVERY] [scan] heartbeat: $accounts scanned$pctStr | $contracts contracts | $heartbeatRate accts/s"
                  )
                  lastHeartbeatNanos = nowNanos
                  lastHeartbeatAccounts = accounts
                }
                Account(rlpBytes) match {
                  case Success(acct) if acct.storageRoot != Account.EmptyStorageRootHash =>
                    contracts += 1
                    if (!seenRoots.contains(acct.storageRoot)) {
                      seenRoots += acct.storageRoot
                      checked += 1
                      try mptStorage.get(acct.storageRoot.toArray)
                      catch {
                        case _: MerklePatriciaTrie.MPTException =>
                          pending += ((accountHash, acct.storageRoot))
                          if (pending.size >= batchFlushSize) {
                            selfRef ! ScanBatch(pending.toSeq)
                            pending.clear()
                          }
                      }
                    }
                  case _ =>
                }
              }
            case Left(err) => IO(log.warning(s"[STORAGE-RECOVERY] [scan] iteration error — skipping: $err"))
          }
          .compile
          .drain
          .unsafeRunSync()

        selfRef ! ScanBatch(pending.toSeq)
      }
    }(scanEc).onComplete {
      case Success(_) => selfRef ! ScanComplete
      case Failure(ex) =>
        log.error(ex, "[STORAGE-RECOVERY] [scan] flat scan failed")
        selfRef ! ScanComplete
    }(context.dispatcher)
  }

  private def scanning(coordinator: ActorRef, sentSoFar: Int): Receive = {
    case ScanBatch(batch) if batch.isEmpty =>
      // no-op

    case ScanBatch(batch) =>
      val tasks = batch.map { case (accountHash, storageRoot) =>
        StorageTask.createStorageTask(accountHash, storageRoot)
      }
      coordinator ! actors.Messages.AddStorageTasks(tasks)
      context.become(scanning(coordinator, sentSoFar + tasks.size))

    case ScanComplete if sentSoFar == 0 =>
      log.info("[STORAGE-RECOVERY] [scan] complete — no missing storage found")
      appStateStorage.storageRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)

    case ScanComplete =>
      coordinator ! actors.Messages.NoMoreStorageTasks
      log.info(s"[STORAGE-RECOVERY] [scan] complete — $sentSoFar missing storage roots queued for download")
      context.become(downloading(coordinator, sentSoFar))

    case actors.Messages.StoragePeerAvailable(peer) =>
      coordinator ! actors.Messages.StoragePeerAvailable(peer)

    case Terminated(`coordinator`) =>
      log.error("[STORAGE-RECOVERY] coordinator crashed during scan — marking done and unblocking sync")
      appStateStorage.storageRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)
  }

  private def downloading(coordinator: ActorRef, expectedCount: Int): Receive = {
    var progressSeq = 0L
    var lastProgressNanos = System.nanoTime()
    var unservableCount = 0
    var abandonTimer: Option[Cancellable] = None
    var recoveredCount = 0L
    var lastStorageRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateRecovered = 0L
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout

    def recordProgress(): Unit = {
      progressSeq += 1
      lastProgressNanos = System.nanoTime()
      unservableCount = 0
      abandonTimer.foreach(_.cancel())
      abandonTimer = None
    }

    def scheduleAbandonCheck(): Unit = {
      abandonTimer.foreach(_.cancel())
      abandonTimer = Some(
        context.system.scheduler.scheduleOnce(abandonAfter, self, CheckAbandon(progressSeq))
      )
    }

    {
      case actors.Messages.StoragePeerAvailable(peer) =>
        coordinator ! actors.Messages.StoragePeerAvailable(peer)

      case SNAPSyncController.StorageRangeSyncComplete =>
        log.info(
          s"[STORAGE-RECOVERY] [download] 100% — $expectedCount / $expectedCount storage roots recovered — COMPLETE"
        )
        abandonTimer.foreach(_.cancel())
        appStateStorage.storageRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)

      case SNAPSyncController.ProgressStorageSlotsSynced(_) =>
        recordProgress()
        recoveredCount += 1
        val (newM, crossed) =
          ProgressMilestones.crossed(recoveredCount, expectedCount.toLong, lastStorageRecoveryMilestone)
        lastStorageRecoveryMilestone = newM
        crossed.foreach { m =>
          val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
          val rate = if (elapsedSecs > 0) ((recoveredCount - lastRateRecovered) / elapsedSecs).toLong else 0L
          if (m % 10 == 0 || m <= 5 || m >= 95) {
            lastRateNanos = System.nanoTime()
            lastRateRecovered = recoveredCount
          }
          log.info(
            s"[STORAGE-RECOVERY] [download] $m% — $recoveredCount / $expectedCount storage roots recovered | $rate roots/s"
          )
        }

      case _: SNAPSyncController.PivotStateUnservable =>
        unservableCount += 1
        if (unservableCount <= 3 || unservableCount % 100 == 0) {
          log.info(
            "[STORAGE-RECOVERY] coordinator reports stored pivot root unservable ({} events, " +
              "no progress for {}s). Will abandon after {}s if this persists.",
            unservableCount,
            (System.nanoTime() - lastProgressNanos) / 1_000_000_000L,
            abandonAfter.toSeconds
          )
        }
        if (abandonTimer.isEmpty) scheduleAbandonCheck()

      case CheckAbandon(progressAtSchedule) =>
        if (progressAtSchedule == progressSeq) {
          log.warning(
            "[STORAGE-RECOVERY] abandoning: no slot progress for {}s, {} unservable events — " +
              "pivot root is unservable; SyncController will re-trigger SNAP sync from a new pivot",
            abandonAfter.toSeconds,
            unservableCount
          )
          // Do NOT write storageRecoveryDone — recovery is incomplete.
          // SyncController handles RecoveryFailed by re-triggering a fresh SNAP sync.
          syncController ! RecoveryFailed
          context.stop(self)
        } else {
          abandonTimer = None
        }

      case Terminated(`coordinator`) =>
        log.error("[STORAGE-RECOVERY] coordinator crashed — marking done and unblocking sync")
        abandonTimer.foreach(_.cancel())
        appStateStorage.storageRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)

      case msg => coordinator.forward(msg)
    }
  }
}

object StorageRecoveryActor {
  private case object StartScan
  private case class ScanResult(missingStorage: Seq[(ByteString, ByteString)])
  private[sync] case class ScanBatch(batch: Seq[(ByteString, ByteString)])
  private[sync] case object ScanComplete
  // Package-private so specs can construct it and assert the fire/cancel logic.
  private[sync] case class CheckAbandon(progressAtSchedule: Long)

  /** Sent to SyncController when recovery completed successfully (or was skipped) */
  case object RecoveryComplete

  /** Sent to SyncController when recovery was abandoned due to an unservable pivot root. The done-flag is NOT written.
    * SyncController must re-trigger a fresh SNAP sync.
    */
  case object RecoveryFailed

  def props(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      flatAccountStorage: FlatAccountStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Props =
    Props(
      new StorageRecoveryActor(
        stateRoot,
        stateStorage,
        appStateStorage,
        flatSlotStorage,
        flatAccountStorage,
        networkPeerManager,
        syncController,
        pivotBlockNumber,
        snapSyncConfig
      )
    )
}
