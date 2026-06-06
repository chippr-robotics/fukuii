package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

import scala.util.{Success, Failure}

import com.chipprbots.ethereum.db.storage.{AppStateStorage, FlatSlotStorage, StateStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.mpt.MptVisitors._
import com.chipprbots.ethereum.blockchain.sync.snap.{SNAPSyncConfig, SNAPSyncController, StorageTask}
import com.chipprbots.ethereum.blockchain.sync.snap.actors
import com.chipprbots.ethereum.blockchain.sync.ProgressMilestones

/** Storage recovery actor for Bug 20 hardening.
  *
  * On startup after SNAP sync, walks the state trie to find contract accounts whose storage tries are missing from
  * MptStorage (due to the Bug 20 phase handoff timeout). Collects missing (accountHash, storageRoot) pairs and
  * downloads them via SNAP protocol using StorageRangeCoordinator.
  *
  * Runs concurrently with BytecodeRecoveryActor — they target different storage backends (MptStorage vs EvmCodeStorage)
  * with no data dependency.
  *
  * Lifecycle:
  *   1. Walk state trie, find contracts with missing storage tries 2. If none missing → mark recovery done, report to
  *      SyncController 3. If missing → download via StorageRangeCoordinator, then mark done
  */
class StorageRecoveryActor(
    stateRoot: ByteString,
    stateStorage: StateStorage,
    appStateStorage: AppStateStorage,
    flatSlotStorage: FlatSlotStorage,
    networkPeerManager: ActorRef,
    syncController: ActorRef,
    pivotBlockNumber: BigInt,
    snapSyncConfig: SNAPSyncConfig,
    // Test hook: when set, the actor skips the real scan and enters `downloading` with
    // the supplied missing list directly. Production callers always leave this as None
    // and the factory method doesn't expose it.
    preloadedMissingForTesting: Option[Seq[(ByteString, ByteString)]] = None,
    // Test hook: when set, the downloading state uses this ref instead of spawning a
    // real StorageRangeCoordinator (which needs network wiring / StateStorage /
    // FlatSlotStorage that a pure unit test doesn't want to simulate).
    coordinatorForTesting: Option[ActorRef] = None
) extends Actor
    with ActorLogging {

  import StorageRecoveryActor._
  import context.dispatcher

  override def preStart(): Unit = preloadedMissingForTesting match {
    case Some(missing) =>
      self ! ScanResult(missing)
    case None =>
      log.info(
        s"StorageRecoveryActor starting: scanning state trie for missing contract storage " +
          s"(stateRoot=${stateRoot.take(4).toArray.map("%02x".format(_)).mkString}...)"
      )
      self ! StartScan
  }

  override def receive: Receive = {
    case StartScan =>
      Future {
        scanForMissingStorage()
      }.onComplete {
        case Success(result) => self ! ScanResult(result)
        case Failure(ex) =>
          log.error(ex, "Storage recovery scan failed")
          self ! ScanResult(Seq.empty)
      }

    case ScanResult(missing) =>
      if (missing.isEmpty) {
        log.info("Storage recovery: all contract storage tries present. Marking recovery complete.")
        RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
        appStateStorage.storageRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)
      } else {
        log.warning(s"Storage recovery: found ${missing.size} contracts with missing storage. Starting download...")
        RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseDownloading)
        implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler

        val coordinator = coordinatorForTesting.getOrElse {
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

        context.watch(coordinator)

        // Send tasks in batches of 10K (same as SNAPSyncController)
        val batchSize = 10000
        var totalSent = 0
        missing.grouped(batchSize).foreach { batch =>
          val tasks = batch.map { case (accountHash, storageRoot) =>
            StorageTask.createStorageTask(accountHash, storageRoot)
          }
          coordinator ! actors.Messages.AddStorageTasks(tasks)
          totalSent += tasks.size
        }
        log.info(s"Sent $totalSent storage tasks to coordinator in ${(totalSent + batchSize - 1) / batchSize} batches")

        context.become(downloading(coordinator, missing))
      }
  }

  /** Re-check, against on-disk state, how many of the originally-missing storage-root nodes are still absent. Cold
    * contracts (storage unchanged since the pivot) fill on a recent-root roll because nodes are content-addressed;
    * genuinely-changed (hot) contracts or never-served roots remain and are reported so the residue is visible rather
    * than silently marked done — regular sync's on-demand GetTrieNodes fetches them when block execution needs them.
    */
  private def logResidualGaps(missing: Seq[(ByteString, ByteString)]): Unit = {
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val residual = missing.count { case (_, storageRoot) =>
      try {
        mptStorage.get(storageRoot.toArray)
        false
      } catch {
        case _: MerklePatriciaTrie.MPTException => true
      }
    }
    if (residual == 0)
      log.info(s"Storage recovery: all ${missing.size} contract storage tries present on disk.")
    else
      log.warning(
        s"Storage recovery finishing: ${missing.size - residual} of ${missing.size} storage gaps filled, " +
          s"$residual residual (hot contracts changed since pivot, or never served). Regular sync will fetch " +
          s"these on-demand via GetTrieNodes when block execution reaches them."
      )
  }

  private def downloading(coordinator: ActorRef, missing: Seq[(ByteString, ByteString)]): Receive = {
    val expectedCount = missing.size
    // A strictly-increasing counter is more robust than a wall-clock timestamp:
    // System.currentTimeMillis() can collide if two progress updates land in the same
    // ms (then the CheckAbandon equality check would false-fire), and wall-clock jumps
    // would make stalledForSeconds misleading. Counter + nanoTime covers both axes.
    var progressSeq = 0L
    var downloadedCount = 0L
    var lastProgressNanos = System.nanoTime()
    var unservableCount = 0
    var abandonTimer: Option[Cancellable] = None
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout
    var recoveredCount = 0L
    var lastStorageRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateRecovered = 0L

    // Recent-root roll state. `currentRoot` is the root the coordinator is currently downloading
    // against (starts at the saved pivot root). When peers can no longer serve it (the pivot has
    // aged out of their snapshot window), we roll onto a recent canonical root instead of only
    // counting down to abandon. `awaitingRoot` debounces the request (one in flight at a time);
    // `rollsAttempted` bounds it so a hot-only/peerless residue still terminates.
    var currentRoot: ByteString = stateRoot
    var rollsAttempted: Int = 0
    var awaitingRoot: Boolean = false
    val maxRolls: Int = snapSyncConfig.storageRecoveryMaxRootRolls

    def recordProgress(): Unit = {
      progressSeq += 1
      lastProgressNanos = System.nanoTime()
      unservableCount = 0
      abandonTimer.foreach(_.cancel())
      abandonTimer = None
      // Slots are flowing against the current root → that root works; reset the roll budget so a
      // later stall on a freshly-aged root gets its own full set of rolls, and clear any pending
      // root request so a stale reply mid-progress is ignored rather than disrupting the download.
      rollsAttempted = 0
      awaitingRoot = false
    }

    def scheduleAbandonCheck(): Unit = {
      abandonTimer.foreach(_.cancel())
      abandonTimer = Some(
        context.system.scheduler.scheduleOnce(abandonAfter, self, CheckAbandon(progressSeq))
      )
    }

    def finishRecovery(reason: String): Unit = {
      abandonTimer.foreach(_.cancel())
      logResidualGaps(missing)
      RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseComplete)
      appStateStorage.storageRecoveryDone().commit()
      log.info(s"Storage recovery finished ($reason).")
      syncController ! RecoveryComplete
      context.stop(self)
    }

    {
      case actors.Messages.StoragePeerAvailable(peer) =>
        coordinator ! actors.Messages.StoragePeerAvailable(peer)

      case SNAPSyncController.StorageRangeSyncComplete =>
        log.info(
          s"[SNAP-PROGRESS] STORAGE-RECOVERY 100% — $expectedCount / $expectedCount storage roots recovered — COMPLETE"
        )
        RecoveryMetrics.setStorageDownloaded(expectedCount.toLong)
        finishRecovery(s"downloaded storage for $expectedCount contracts")

      case SNAPSyncController.ProgressStorageSlotsSynced(_) =>
        downloadedCount += 1
        RecoveryMetrics.setStorageDownloaded(downloadedCount)
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
            s"[SNAP-PROGRESS] STORAGE-RECOVERY $m% — $recoveredCount / $expectedCount storage roots | $rate roots/s"
          )
        }

      // The coordinator reports every peer stateless for the current download root: the pivot has
      // aged out of peers' snapshot serve window. Rather than only counting down to abandon (Bug
      // 30b), try to roll onto a RECENT root peers can still serve — cold contracts fill identically
      // because trie nodes are content-addressed. The abandon timer stays armed as a safety net for
      // when no recent root is available (no peers / bootstrap fails) or the residue is all-hot.
      case _: SNAPSyncController.PivotStateUnservable =>
        unservableCount += 1
        if (unservableCount <= 3 || unservableCount % 100 == 0) {
          log.info(
            "Storage recovery: coordinator reports root {} unservable ({} events, no progress for {}s).",
            currentRoot.take(4).toArray.map("%02x".format(_)).mkString,
            unservableCount,
            (System.nanoTime() - lastProgressNanos) / 1_000_000_000L
          )
        }
        if (abandonTimer.isEmpty) scheduleAbandonCheck()
        // Ask SyncController for a recent servable root (one request in flight; bounded total).
        if (!awaitingRoot && rollsAttempted < maxRolls) {
          awaitingRoot = true
          log.info(
            "Storage recovery: requesting a recent root to roll off the aged pivot (roll {} of {}).",
            rollsAttempted + 1,
            maxRolls
          )
          syncController ! RequestRecentRoot
        } else if (rollsAttempted >= maxRolls) {
          log.info(
            "Storage recovery: exhausted {} recent-root rolls; letting the abandon timer run for the residue.",
            maxRolls
          )
        }

      case RecentRoot(_, _) if !awaitingRoot =>
        // A reply that arrived after slot progress resumed (which cleared the pending request).
        // Ignore it — rolling now would needlessly cancel in-flight requests on a healthy download.
        log.debug("Storage recovery: ignoring stale recent-root reply (no roll pending).")

      case RecentRoot(blockNumber, rootOpt) =>
        awaitingRoot = false
        rootOpt match {
          case Some(root) if root != currentRoot =>
            val oldRoot = currentRoot
            rollsAttempted += 1
            currentRoot = root
            // A fresh, servable root deserves a clean attempt window — cancel the pending abandon.
            abandonTimer.foreach(_.cancel())
            abandonTimer = None
            unservableCount = 0
            val oldHex = oldRoot.take(4).toArray.map("%02x".format(_)).mkString
            val newHex = root.take(4).toArray.map("%02x".format(_)).mkString
            log.warning(
              s"Storage recovery: rolling download root $oldHex -> $newHex (block $blockNumber, " +
                s"roll $rollsAttempted/$maxRolls). Re-queuing $expectedCount tasks."
            )
            coordinator ! actors.Messages.StoragePivotRefreshed(root)
          case Some(_) =>
            log.info(
              "Storage recovery: recent root equals current download root — no newer servable pivot. " +
                "Letting the abandon timer run."
            )
          case None =>
            log.info("Storage recovery: no recent root available to roll to. Letting the abandon timer run.")
        }

      case CheckAbandon(progressAtSchedule) =>
        if (progressAtSchedule == progressSeq) {
          log.warning(
            "Storage recovery abandoning download: no slot progress for {}s after {} unservable events and {} " +
              "root roll(s). Remaining contract storage will be fetched on-demand via GetTrieNodes during regular sync.",
            abandonAfter.toSeconds,
            unservableCount,
            rollsAttempted
          )
          finishRecovery("abandoned: download stalled")
        } else {
          // Progress happened between scheduling and firing — reset.
          abandonTimer = None
        }

      case Terminated(`coordinator`) =>
        log.error("StorageRangeCoordinator crashed unexpectedly. Marking storage recovery done to unblock sync.")
        finishRecovery("coordinator crashed")

      case msg => coordinator.forward(msg) // Forward SNAP protocol responses to coordinator
    }
  }

  /** Walk the state trie and collect (accountHash, storageRoot) for contracts whose storage tries are missing from
    * MptStorage.
    */
  private def scanForMissingStorage(): Seq[(ByteString, ByteString)] = {
    RecoveryMetrics.setStoragePhase(RecoveryMetrics.PhaseScanning)
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    val seenRoots = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L
    var checkedCount = 0L

    val onLeaf: (ByteString, LeafNode) => Unit = { (accountHash, leafNode) =>
      accountCount += 1
      // Publish scan progress to the node-health dashboard every 100K accounts (cheap volatile
      // writes); the per-1M log line stays for log-only visibility.
      if (accountCount % 100_000 == 0) {
        RecoveryMetrics.setStorageScanProgress(accountCount, contractCount, missing.size.toLong)
      }
      if (accountCount % 1_000_000 == 0) {
        log.info(
          s"Storage recovery scan: $accountCount accounts, $contractCount contracts, " +
            s"$checkedCount checked, ${missing.size} missing"
        )
      }

      Account(leafNode.value) match {
        case Success(account) =>
          if (account.storageRoot != Account.EmptyStorageRootHash) {
            contractCount += 1
            if (!seenRoots.contains(account.storageRoot)) {
              seenRoots += account.storageRoot
              checkedCount += 1
              // Check if the storage root node exists in MptStorage
              try
                mptStorage.get(account.storageRoot.toArray)
              // Storage root exists — may be incomplete deeper, but the root is present
              catch {
                case _: MerklePatriciaTrie.MPTException =>
                  missing += ((accountHash, account.storageRoot))
              }
            }
          }
        case Failure(_) => // Skip malformed account RLP
      }
    }

    try {
      val visitor = new PathTrackingLeafWalkVisitor(mptStorage, ByteString.empty, onLeaf)
      MptTraversals.dispatch(rootNode, visitor)
    } catch {
      case e: MerklePatriciaTrie.MPTException =>
        log.error(
          e,
          s"Trie walk failed at account $accountCount — partial results: ${missing.size} missing storage tries"
        )
    }

    log.info(
      s"Storage recovery scan complete: $accountCount accounts, $contractCount contracts, " +
        s"$checkedCount unique storage roots checked, ${missing.size} missing"
    )
    RecoveryMetrics.setStorageScanProgress(accountCount, contractCount, missing.size.toLong)
    missing.toSeq
  }
}

object StorageRecoveryActor {
  private case object StartScan
  private case class ScanResult(missingStorage: Seq[(ByteString, ByteString)])
  // Delayed self-ping used by Bug 30b abandon path. Carries the progress counter that was
  // current when the timer was armed; if the actor's current progressSeq still matches on
  // fire, nothing has moved in the interim and we give up. Package-private so the spec
  // can construct it directly and assert the fire/cancel logic.
  private[sync] case class CheckAbandon(progressAtSchedule: Long)

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

  /** Recovery → SyncController: the saved pivot root has aged out of every peer's snapshot serve window, so storage
    * downloads are returning empty. Please fetch a RECENT canonical header from a peer and reply with [[RecentRoot]] so
    * the download can roll onto a root peers can still serve. SyncController brokers the header bootstrap (wired
    * separately); if this goes unanswered the actor falls back to the abandon path, so shipping this ahead of the
    * SyncController handler simply preserves today's behaviour.
    */
  case object RequestRecentRoot

  /** SyncController → recovery: a recent canonical `(blockNumber, stateRoot)`, or `stateRoot = None` if none could be
    * fetched (no peers / bootstrap failed). A root equal to the current download root means there is no newer servable
    * pivot — the actor then lets the abandon timer run rather than rolling in place.
    */
  final case class RecentRoot(blockNumber: BigInt, stateRoot: Option[ByteString])

  def props(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
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
        networkPeerManager,
        syncController,
        pivotBlockNumber,
        snapSyncConfig
      )
    )

  /** Download-only variant: skip the scan and go straight to downloading the supplied missing storage tries (produced
    * by the combined parallel scan). Used by `SyncController` when `parallel-recovery-scan` is on.
    */
  def propsPreloaded(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      appStateStorage: AppStateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: ActorRef,
      syncController: ActorRef,
      pivotBlockNumber: BigInt,
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[(ByteString, ByteString)]
  ): Props =
    Props(
      new StorageRecoveryActor(
        stateRoot,
        stateStorage,
        appStateStorage,
        flatSlotStorage,
        networkPeerManager,
        syncController,
        pivotBlockNumber,
        snapSyncConfig,
        preloadedMissingForTesting = Some(missing)
      )
    )
}
