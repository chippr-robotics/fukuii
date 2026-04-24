package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props}
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
    snapSyncConfig: SNAPSyncConfig
) extends Actor
    with ActorLogging {

  import StorageRecoveryActor._
  import context.dispatcher

  override def preStart(): Unit = {
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
        appStateStorage.storageRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)
      } else {
        log.warning(s"Storage recovery: found ${missing.size} contracts with missing storage. Starting download...")
        implicit val scheduler: org.apache.pekko.actor.Scheduler = context.system.scheduler
        val requestTracker = new snap.SNAPRequestTracker()
        val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)

        val coordinator = context.actorOf(
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

        context.become(downloading(coordinator, missing.size))
      }
  }

  /** Seconds without any storage-slot progress after which we declare the recovery dead-in-the-water and abandon to
    * regular sync. The SNAP serve window is ~128 blocks (~28 min on ETC / ~25 min on ETH); once exceeded, every peer
    * rejects our saved pivot root and `PivotStateUnservable` fires in a loop. Pivot-refresh isn't wired into the
    * recovery path (unlike SNAP), so the only way out is to stop trying and let regular sync's on-demand `GetTrieNodes`
    * fetch the missing subtrees.
    */
  private val RecoveryAbandonAfter: FiniteDuration = 10.minutes

  private def downloading(coordinator: ActorRef, expectedCount: Int): Receive = {
    var lastProgressMs = System.currentTimeMillis()
    var unservableCount = 0
    var abandonTimer: Option[Cancellable] = None

    def recordProgress(): Unit = {
      lastProgressMs = System.currentTimeMillis()
      unservableCount = 0
      abandonTimer.foreach(_.cancel())
      abandonTimer = None
    }

    def scheduleAbandonCheck(): Unit = {
      abandonTimer.foreach(_.cancel())
      abandonTimer = Some(
        context.system.scheduler.scheduleOnce(RecoveryAbandonAfter, self, CheckAbandon(lastProgressMs))
      )
    }

    {
      case actors.Messages.StoragePeerAvailable(peer) =>
        coordinator ! actors.Messages.StoragePeerAvailable(peer)

      case SNAPSyncController.StorageRangeSyncComplete =>
        log.info(s"Storage recovery complete: downloaded storage for $expectedCount contracts")
        abandonTimer.foreach(_.cancel())
        appStateStorage.storageRecoveryDone().commit()
        syncController ! RecoveryComplete
        context.stop(self)

      case SNAPSyncController.ProgressStorageSlotsSynced(_) =>
        recordProgress()

      // Bug 30b: coordinator reports every peer stateless for the stored pivot root. In SNAP
      // sync this would trigger `refreshPivotInPlace` on the controller; the recovery path has
      // no equivalent. Instead of looping forever, count the events and bail out after
      // `RecoveryAbandonAfter` of zero slot progress. Regular sync will subsequently fetch
      // missing trie subtrees on-demand via GetTrieNodes when block execution reaches them.
      case _: SNAPSyncController.PivotStateUnservable =>
        unservableCount += 1
        log.info(
          "Storage recovery: coordinator reports stored pivot root unservable ({} events, " +
            "no progress for {}s). Will abandon after {}s if this persists.",
          unservableCount,
          (System.currentTimeMillis() - lastProgressMs) / 1000,
          RecoveryAbandonAfter.toSeconds
        )
        if (abandonTimer.isEmpty) scheduleAbandonCheck()

      case CheckAbandon(progressAtSchedule) =>
        if (progressAtSchedule == lastProgressMs) {
          log.warning(
            "Storage recovery abandoned: no slot progress for {}s against stored pivot root after {} " +
              "unservable events. Regular sync will fetch remaining contract storage on-demand via " +
              "GetTrieNodes when block execution needs it.",
            RecoveryAbandonAfter.toSeconds,
            unservableCount
          )
          appStateStorage.storageRecoveryDone().commit()
          syncController ! RecoveryComplete
          context.stop(self)
        } else {
          // Progress happened between scheduling and firing — reset.
          abandonTimer = None
        }

      case msg => coordinator.forward(msg) // Forward SNAP protocol responses to coordinator
    }
  }

  /** Walk the state trie and collect (accountHash, storageRoot) for contracts whose storage tries are missing from
    * MptStorage.
    */
  private def scanForMissingStorage(): Seq[(ByteString, ByteString)] = {
    val mptStorage = stateStorage.getBackingStorage(pivotBlockNumber)
    val rootNode = mptStorage.get(stateRoot.toArray)

    val missing = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    val seenRoots = mutable.HashSet.empty[ByteString]
    var accountCount = 0L
    var contractCount = 0L
    var checkedCount = 0L

    val onLeaf: (ByteString, LeafNode) => Unit = { (accountHash, leafNode) =>
      accountCount += 1
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
    missing.toSeq
  }
}

object StorageRecoveryActor {
  private case object StartScan
  private case class ScanResult(missingStorage: Seq[(ByteString, ByteString)])
  // Delayed self-ping used by Bug 30b abandon path. Carries the progress timestamp that was
  // current when the timer was armed; if the actor's current lastProgressMs still matches on
  // fire, nothing has moved in the interim and we give up.
  private case class CheckAbandon(progressAtSchedule: Long)

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete

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
}
