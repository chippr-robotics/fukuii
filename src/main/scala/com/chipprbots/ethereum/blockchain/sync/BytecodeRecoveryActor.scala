package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure}

import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, StateStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.mpt.MptVisitors._
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig

/** Bytecode recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts whose bytecodes are missing from
  * evmCodeStorage (due to the Bug 20 phase handoff timeout). Collects missing codeHashes and downloads them via SNAP
  * protocol using ByteCodeCoordinator.
  *
  * Lifecycle:
  *   1. Walk state trie, collect missing codeHashes (deduplicated) 2. If none missing → mark recovery done, report to
  *      SyncController 3. If missing → download via ByteCodeCoordinator, then mark done
  */
class BytecodeRecoveryActor(
    stateRoot: ByteString,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    appStateStorage: AppStateStorage,
    networkPeerManager: ActorRef,
    syncController: ActorRef,
    pivotBlockNumber: BigInt,
    snapSyncConfig: SNAPSyncConfig,
    // Test hook: when set, the actor skips the real scan and enters `downloading` with
    // the supplied missing list directly. Production callers always leave this as None.
    private[sync] val preloadedMissingForTesting: Option[Seq[ByteString]] = None,
    // Test hook: when set, the downloading state uses this ref instead of spawning a
    // real ByteCodeCoordinator.
    private[sync] val coordinatorForTesting: Option[ActorRef] = None
) extends Actor
    with ActorLogging {

  import BytecodeRecoveryActor._
  import context.dispatcher

  override def preStart(): Unit = preloadedMissingForTesting match {
    case Some(missing) =>
      self ! ScanResult(missing)
    case None =>
      log.info(
        s"BytecodeRecoveryActor starting: scanning state trie for missing bytecodes " +
          s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
      )
      self ! StartScan
  }

  override def receive: Receive = {
    case StartScan =>
      Future {
        scanForMissingBytecodes()
      }.onComplete {
        case Success(result) => self ! ScanResult(result)
        case Failure(ex) =>
          log.error(ex, "Bytecode recovery scan failed")
          self ! ScanResult(Seq.empty)
      }

    case ScanResult(missing) =>
      if (missing.isEmpty) {
        log.info("Bytecode recovery: all contract bytecodes present. Marking recovery complete.")
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
        appStateStorage.bytecodeRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)
      } else {
        log.warning(s"Bytecode recovery: found ${missing.size} missing bytecodes. Starting download...")
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseDownloading)
        implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler
        val coordinator = coordinatorForTesting.getOrElse {
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
        context.watch(coordinator)
        coordinator ! snap.actors.Messages.StartByteCodeSync(missing)
        context.become(downloading(coordinator, missing.size))
      }
  }

  private def downloading(coordinator: ActorRef, expectedCount: Int): Receive = {
    var progressSeq = 0L
    var downloadedCount = 0L
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
      RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
      appStateStorage.bytecodeRecoveryDone().commit()
      syncController ! RecoveryComplete
      context.stop(self)
    }

    {
      case snap.actors.Messages.ByteCodePeerAvailable(peer) =>
        coordinator ! snap.actors.Messages.ByteCodePeerAvailable(peer)

      case snap.SNAPSyncController.ByteCodeSyncComplete =>
        log.info(
          s"[SNAP-PROGRESS] BYTECODE-RECOVERY 100% — $expectedCount / $expectedCount bytecodes recovered — COMPLETE"
        )
        RecoveryMetrics.setBytecodeDownloaded(expectedCount.toLong)
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
        finishRecovery()

      case snap.SNAPSyncController.ProgressBytecodesDownloaded(_) =>
        downloadedCount += 1
        RecoveryMetrics.setBytecodeDownloaded(downloadedCount)
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
            s"[SNAP-PROGRESS] BYTECODE-RECOVERY $m% — $downloadedCount / $expectedCount bytecodes | $rate bytecodes/s"
          )
        }

      case CheckAbandon(progressAtSchedule) =>
        if (progressAtSchedule == progressSeq) {
          log.warning(
            "Bytecode recovery abandoned: no download progress for {}s. " +
              "Regular sync will fetch missing bytecodes on-demand via GetTrieNodes.",
            abandonAfter.toSeconds
          )
          finishRecovery()
        } else {
          abandonTimer = None
        }

      case Terminated(`coordinator`) =>
        log.error("ByteCodeCoordinator crashed unexpectedly. Marking bytecode recovery done to unblock sync.")
        finishRecovery()

      case msg => coordinator.forward(msg)
    }
  }

  /** Walk the state trie and collect codeHashes of contracts missing from evmCodeStorage. */
  private def scanForMissingBytecodes(): Seq[ByteString] = {
    RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseScanning)
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[ByteString]
    val seen = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L

    val onLeaf: LeafNode => Unit = { leafNode =>
      accountCount += 1
      // Publish scan progress to the node-health dashboard every 100K accounts (cheap volatile
      // writes); the per-1M log line stays for log-only visibility.
      if (accountCount % 100_000 == 0) {
        RecoveryMetrics.setBytecodeScanProgress(accountCount, contractCount, missing.size.toLong)
      }
      if (accountCount % 1_000_000 == 0) {
        log.info(
          s"Bytecode recovery scan: $accountCount accounts, $contractCount contracts, ${missing.size} missing"
        )
      }

      Account(leafNode.value) match {
        case Success(account) =>
          if (account.codeHash != Account.EmptyCodeHash && !seen.contains(account.codeHash)) {
            seen += account.codeHash
            contractCount += 1
            if (evmCodeStorage.get(account.codeHash).isEmpty) {
              missing += account.codeHash
            }
          }
        case Failure(_) => // Skip malformed account RLP
      }
    }

    try {
      val visitor = new LeafWalkVisitor(mptStorage, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    } catch {
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          e,
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing bytecodes"
        )
    }

    log.info(
      s"Bytecode recovery scan complete: $accountCount accounts, $contractCount contracts, ${missing.size} missing bytecodes"
    )
    RecoveryMetrics.setBytecodeScanProgress(accountCount, contractCount, missing.size.toLong)
    missing.toSeq
  }
}

object BytecodeRecoveryActor {
  private case object StartScan
  private case class ScanResult(missingCodeHashes: Seq[ByteString])
  private case class CheckAbandon(progressSeq: Long)

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

  def props(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
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
        appStateStorage,
        networkPeerManager,
        syncController,
        pivotBlockNumber,
        snapSyncConfig
      )
    )

  /** Download-only variant: skip the scan and go straight to downloading the supplied missing codeHashes (produced by
    * the combined parallel scan). Used by `SyncController` when `parallel-recovery-scan` is on.
    */
  def propsPreloaded(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[ByteString]
  ): Props =
    Props(
      new BytecodeRecoveryActor(
        stateRoot,
        stateStorage,
        evmCodeStorage,
        appStateStorage,
        networkPeerManager,
        syncController,
        pivotBlockNumber,
        snapSyncConfig,
        preloadedMissingForTesting = Some(missing)
      )
    )
}
