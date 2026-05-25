package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.concurrent.duration._
import scala.util.{Success, Failure}

import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, FlatAccountStorage, StateStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.blockchain.sync.snap.{SNAPSyncConfig, SNAPSyncController}

/** Bytecode recovery actor for Bug 20 hardening.
  *
  * Performs a sequential forward scan of FlatAccountStorage (seekFrom) — O(n) sequential
  * RocksDB reads vs the old MPT walk's random reads. Downloads missing bytecodes
  * concurrently with the scan via ByteCodeCoordinator.
  *
  * Lifecycle:
  *   1. StartScan: create coordinator eagerly, launch flat scan on recovery-scan-dispatcher
  *   2. ScanBatch events stream tasks to the coordinator as they are found
  *   3. ScanComplete: seal the coordinator with NoMoreByteCodeTasks, become downloading
  *   4. ByteCodeSyncComplete: mark recovery done, report to SyncController
  */
class BytecodeRecoveryActor(
    stateRoot: ByteString,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    flatAccountStorage: FlatAccountStorage,
    appStateStorage: AppStateStorage,
    networkPeerManager: ActorRef,
    syncController: ActorRef,
    pivotBlockNumber: BigInt,
    snapSyncConfig: SNAPSyncConfig,
    // Test hook: when set, the actor skips the real scan and enters `downloading` with
    // the supplied missing list directly. Production callers always leave this as None.
    preloadedMissingForTesting: Option[Seq[ByteString]] = None,
    // Test hook: when set, the scanning/downloading states use this ref instead of spawning
    // a real ByteCodeCoordinator.
    coordinatorForTesting: Option[ActorRef] = None
) extends Actor
    with ActorLogging {

  import BytecodeRecoveryActor._
  import context.dispatcher

  private val scanEc: ExecutionContext =
    context.system.dispatchers.lookup("recovery-scan-dispatcher")

  override def preStart(): Unit = preloadedMissingForTesting match {
    case Some(missing) =>
      self ! ScanResult(missing)
    case None =>
      log.info(
        s"[BYTECODE-RECOVERY] starting flat scan " +
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
        log.info("[BYTECODE-RECOVERY] [scan] complete — no missing bytecodes found")
        appStateStorage.bytecodeRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)
      } else {
        log.warning(s"[BYTECODE-RECOVERY] found ${missing.size} contracts with missing bytecodes. Starting download...")
        val coordinator = makeCoordinator()
        context.watch(coordinator)
        val batchSize = 10000
        var totalSent = 0
        missing.grouped(batchSize).foreach { batch =>
          coordinator ! snap.actors.Messages.AddByteCodeTasks(batch)
          totalSent += batch.size
        }
        log.info(
          s"[BYTECODE-RECOVERY] sent $totalSent bytecode tasks to coordinator in ${(totalSent + batchSize - 1) / batchSize} batches"
        )
        coordinator ! snap.actors.Messages.NoMoreByteCodeTasks
        context.become(downloading(coordinator, missing.size))
      }
  }

  private def makeCoordinator(): ActorRef =
    coordinatorForTesting.getOrElse {
      implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler
      val requestTracker = new snap.SNAPRequestTracker()
      context.actorOf(
        snap.actors.ByteCodeCoordinator
          .props(
            evmCodeStorage = evmCodeStorage,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker,
            batchSize = snap.ByteCodeTask.DEFAULT_BATCH_SIZE,
            snapSyncController = self
          )
          .withDispatcher("sync-dispatcher"),
        "bytecode-recovery-coordinator"
      )
    }

  private def launchFlatScan(batchFlushSize: Int = 10_000): Unit = {
    val selfRef = self
    val fas = flatAccountStorage
    val ecs = evmCodeStorage
    val approxTotal = fas.approximateKeyCount()
    Future {
      blocking {
        val seenHashes = mutable.HashSet.empty[ByteString]
        val pending = mutable.ArrayBuffer.empty[ByteString]
        var accounts = 0L
        var contracts = 0L
        var checked = 0L
        var lastNanos = System.nanoTime()
        var lastScanMilestonePct: Int = -1
        var lastHeartbeatNanos = System.nanoTime()
        var lastHeartbeatAccounts = 0L

        fas.seekFrom(ByteString.empty)
          .evalMap {
            case Right((_, rlpBytes)) =>
              IO {
                accounts += 1
                if (accounts % 1_000_000 == 0) {
                  val rate = (1_000_000 / ((System.nanoTime() - lastNanos) / 1e9)).toLong
                  val pctStr = if (approxTotal > 0) s" | ${(accounts * 100.0 / approxTotal).toInt}%" else ""
                  log.info(
                    s"[BYTECODE-RECOVERY] [scan] $accounts accounts$pctStr | $contracts contracts | " +
                      s"$checked unique hashes checked | ${pending.size} pending | $rate accts/s"
                  )
                  if (approxTotal > 0) {
                    val (newM, crossed) =
                      ProgressMilestones.crossed(accounts, approxTotal, lastScanMilestonePct)
                    lastScanMilestonePct = newM
                    crossed.foreach(m =>
                      log.info(
                        s"[BYTECODE-RECOVERY] [scan] $m% — $accounts / ~$approxTotal accounts scanned"
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
                    s"[BYTECODE-RECOVERY] [scan] heartbeat: $accounts scanned$pctStr | $contracts contracts | $heartbeatRate accts/s"
                  )
                  lastHeartbeatNanos = nowNanos
                  lastHeartbeatAccounts = accounts
                }
                Account(rlpBytes) match {
                  case Success(acct) if acct.codeHash != Account.EmptyCodeHash =>
                    contracts += 1
                    if (!seenHashes.contains(acct.codeHash)) {
                      seenHashes += acct.codeHash
                      checked += 1
                      if (ecs.get(acct.codeHash).isEmpty) {
                        pending += acct.codeHash
                        if (pending.size >= batchFlushSize) {
                          selfRef ! ScanBatch(pending.toSeq)
                          pending.clear()
                        }
                      }
                    }
                  }
                case _ =>
              }
            case Left(err) => IO(log.warning(s"[BYTECODE-RECOVERY] [scan] iteration error — skipping: $err"))
          }
          .compile
          .drain
          .unsafeRunSync()

        selfRef ! ScanBatch(pending.toSeq)
      }
    }(scanEc).onComplete {
      case Success(_) => selfRef ! ScanComplete
      case Failure(ex) =>
        log.error(ex, "[BYTECODE-RECOVERY] [scan] flat scan failed")
        selfRef ! ScanComplete
    }(context.dispatcher)
  }

  private def scanning(coordinator: ActorRef, sentSoFar: Int): Receive = {
    case ScanBatch(batch) if batch.isEmpty =>
      // no-op

    case ScanBatch(batch) =>
      coordinator ! snap.actors.Messages.AddByteCodeTasks(batch)
      context.become(scanning(coordinator, sentSoFar + batch.size))

    case ScanComplete if sentSoFar == 0 =>
      log.info("[BYTECODE-RECOVERY] [scan] complete — no missing bytecodes found")
      appStateStorage.bytecodeRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)

    case ScanComplete =>
      coordinator ! snap.actors.Messages.NoMoreByteCodeTasks
      log.info(s"[BYTECODE-RECOVERY] [scan] complete — $sentSoFar missing bytecodes queued for download")
      context.become(downloading(coordinator, sentSoFar))

    case snap.actors.Messages.ByteCodePeerAvailable(peer) =>
      coordinator ! snap.actors.Messages.ByteCodePeerAvailable(peer)

    case Terminated(`coordinator`) =>
      log.error("[BYTECODE-RECOVERY] coordinator crashed during scan — marking done and unblocking sync")
      appStateStorage.bytecodeRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)
  }

  private def downloading(coordinator: ActorRef, expectedCount: Int): Receive = {
    var progressSeq = 0L
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout
    var abandonTimer: Option[Cancellable] = Some(
      context.system.scheduler.scheduleOnce(abandonAfter, self, CheckAbandon(0L))
    )
    var downloadedCount = 0L
    var lastBytecodeRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateDownloaded = 0L

    def recordProgress(): Unit = {
      progressSeq += 1
      abandonTimer.foreach(_.cancel())
      abandonTimer = None
    }

    def finishRecovery(): Unit = {
      abandonTimer.foreach(_.cancel())
      appStateStorage.bytecodeRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)
    }

    def abandonRecovery(): Unit = {
      abandonTimer.foreach(_.cancel())
      // Do NOT write bytecodeRecoveryDone — recovery is incomplete.
      // SyncController handles RecoveryFailed by re-triggering a fresh SNAP sync.
      syncController ! RecoveryFailed
      context.stop(self)
    }

    {
      case snap.actors.Messages.ByteCodePeerAvailable(peer) =>
        coordinator ! snap.actors.Messages.ByteCodePeerAvailable(peer)

      case SNAPSyncController.ByteCodeSyncComplete =>
        log.info(
          s"[BYTECODE-RECOVERY] [download] 100% — $expectedCount / $expectedCount bytecodes recovered — COMPLETE"
        )
        finishRecovery()

      case SNAPSyncController.ProgressBytecodesDownloaded(_) =>
        recordProgress()
        downloadedCount += 1
        val (newM, crossed) =
          ProgressMilestones.crossed(downloadedCount, expectedCount.toLong, lastBytecodeRecoveryMilestone)
        lastBytecodeRecoveryMilestone = newM
        crossed.foreach { m =>
          val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
          val rate = if (elapsedSecs > 0) ((downloadedCount - lastRateDownloaded) / elapsedSecs).toLong else 0L
          if (m % 10 == 0 || m <= 5 || m >= 95) {
            lastRateNanos = System.nanoTime()
            lastRateDownloaded = downloadedCount
          }
          log.info(
            s"[BYTECODE-RECOVERY] [download] $m% — $downloadedCount / $expectedCount bytecodes recovered | $rate bytecodes/s"
          )
        }

      case CheckAbandon(progressAtSchedule) =>
        if (progressAtSchedule == progressSeq) {
          log.warning(
            "[BYTECODE-RECOVERY] abandoning: no download progress for {}s — " +
              "pivot root is unservable; SyncController will re-trigger SNAP sync from a new pivot",
            abandonAfter.toSeconds
          )
          abandonRecovery()
        } else {
          abandonTimer = None
        }

      case Terminated(`coordinator`) =>
        log.error("[BYTECODE-RECOVERY] coordinator crashed — marking done and unblocking sync")
        finishRecovery()

      case msg => coordinator.forward(msg)
    }
  }
}

object BytecodeRecoveryActor {
  private case object StartScan
  private case class ScanResult(missingCodeHashes: Seq[ByteString])
  private[sync] case class ScanBatch(batch: Seq[ByteString])
  private[sync] case object ScanComplete
  private[sync] case class CheckAbandon(progressSeq: Long)

  /** Sent to SyncController when recovery completed successfully (or was skipped) */
  case object RecoveryComplete

  /** Sent to SyncController when recovery was abandoned due to an unservable pivot root. The done-flag is NOT written.
    * SyncController must re-trigger a fresh SNAP sync.
    */
  case object RecoveryFailed

  def props(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      flatAccountStorage: FlatAccountStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig
  ): Props =
    Props(
      new BytecodeRecoveryActor(
        stateRoot,
        stateStorage,
        evmCodeStorage,
        flatAccountStorage,
        appStateStorage,
        networkPeerManager,
        syncController,
        pivotBlockNumber,
        snapSyncConfig
      )
    )
}
