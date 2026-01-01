package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** LRU cache for storage tries to limit memory usage */
private class StorageTrieCache(maxSize: Int = 10000) {
  private val cache = scala.collection.mutable.LinkedHashMap[ByteString, MerklePatriciaTrie[ByteString, ByteString]]()

  def getOrElseUpdate(
      key: ByteString,
      default: => MerklePatriciaTrie[ByteString, ByteString]
  ): MerklePatriciaTrie[ByteString, ByteString] =
    cache.get(key) match {
      case Some(trie) =>
        cache.remove(key)
        cache.put(key, trie)
        trie
      case None =>
        val trie = default
        put(key, trie)
        trie
    }

  def get(key: ByteString): Option[MerklePatriciaTrie[ByteString, ByteString]] =
    cache.get(key).map { trie =>
      cache.remove(key)
      cache.put(key, trie)
      trie
    }

  def put(key: ByteString, trie: MerklePatriciaTrie[ByteString, ByteString]): Unit = {
    cache.remove(key)
    if (cache.size >= maxSize) {
      cache.remove(cache.head._1)
    }
    cache.put(key, trie)
  }

  def size: Int = cache.size
}

/** StorageRangeCoordinator manages storage range download workers and orchestrates the storage sync phase.
  *
  * Downloads storage ranges for contract accounts in parallel, verifies storage proofs, and stores storage slots
  * locally. This coordinator contains all the logic previously in StorageRangeDownloader.
  *
  * @param stateRoot
  *   State root hash
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param mptStorage
  *   MPT storage
  * @param maxAccountsPerBatch
  *   Max accounts per batch
  * @param snapSyncController
  *   Parent controller
  */
class StorageRangeCoordinator(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    maxAccountsPerBatch: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management
  private val tasks = mutable.Queue[StorageTask]()
  private val activeTasks = mutable.Map[BigInt, (Peer, Seq[StorageTask])]()
  private val completedTasks = mutable.ArrayBuffer[StorageTask]()

  // Peer cooldown (best-effort): used to avoid hammering peers that likely can't serve the pivot state.
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  // Worker management
  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 8

  // Statistics
  private var slotsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Storage management
  private val maxResponseSize: BigInt = 512 * 1024
  private val proofVerifiers = mutable.Map[ByteString, MerkleProofVerifier]()
  private val storageTrieCache = new StorageTrieCache(10000)

  override def preStart(): Unit = {
    log.info("StorageRangeCoordinator starting")
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("Storage worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartStorageRangeSync(root) =>
      log.info(s"Starting storage range sync for state root ${root.take(8).toHex}")

    case AddStorageTasks(storageTasks) =>
      tasks.enqueueAll(storageTasks)
      log.info(s"Added ${storageTasks.size} storage tasks to queue (total pending: ${tasks.size})")

    case AddStorageTask(task) =>
      tasks.enqueue(task)
      log.debug(s"Added storage task for account ${task.accountString} to queue")

    case StoragePeerAvailable(peer) =>
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) due to cooldown")
      } else if (!isComplete && workers.size < maxWorkers) {
        val worker = createWorker()
        worker ! FetchStorageRanges(null, peer)
      } else if (!isComplete && tasks.nonEmpty) {
        // Request from existing worker
        requestNextRanges(peer)
      }

    case StorageRangesResponseMsg(response) =>
      handleResponse(response)

    case StorageTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          slotsDownloaded += count
          log.info(s"Storage task completed: $count slots")
          self ! StorageCheckCompletion
        case Left(error) =>
          log.warning(s"Storage task failed: $error")
      }

    case StorageCheckCompletion =>
      if (isComplete) {
        log.info("Storage range sync complete!")
        snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
      }

    case StorageGetProgress =>
      val stats = StorageRangeCoordinator.SyncStatistics(
        slotsDownloaded = slotsDownloaded,
        bytesDownloaded = bytesDownloaded,
        tasksCompleted = completedTasks.size,
        tasksActive = activeTasks.values.map(_._2.size).sum,
        tasksPending = tasks.size,
        elapsedTimeMs = System.currentTimeMillis() - startTime,
        progress = progress
      )
      sender() ! stats
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      StorageRangeWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker
      )
    )
    workers += worker
    log.debug(s"Created storage worker, total: ${workers.size}")
    worker
  }

  private def requestNextRanges(peer: Peer): Option[BigInt] = {
    if (tasks.isEmpty) {
      log.debug("No more storage tasks available")
      return None
    }

    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    def isInitialRange(t: StorageTask): Boolean = t.next == min && t.last == max

    // snap/1 origin/limit semantics apply to the first account only. To avoid incorrect continuation
    // behavior, only batch tasks that request the initial full range.
    val first = tasks.dequeue()
    val batchTasks: Seq[StorageTask] =
      if (!isInitialRange(first) || maxAccountsPerBatch <= 1) {
        Seq(first)
      } else {
        val buf = mutable.ArrayBuffer[StorageTask](first)
        while (buf.size < maxAccountsPerBatch && tasks.nonEmpty && isInitialRange(tasks.front)) {
          buf += tasks.dequeue()
        }
        buf.toSeq
      }

    if (batchTasks.isEmpty) {
      return None
    }

    val requestId = requestTracker.generateRequestId()
    val accountHashes = batchTasks.map(_.accountHash)
    val firstTask = batchTasks.head

    val request = GetStorageRanges(
      requestId = requestId,
      rootHash = stateRoot,
      accountHashes = accountHashes,
      startingHash = firstTask.next,
      limitHash = firstTask.last,
      responseBytes = maxResponseSize
    )

    batchTasks.foreach(_.pending = true)
    activeTasks.put(requestId, (peer, batchTasks))

    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetStorageRanges,
      timeout = 30.seconds
    ) {
      handleTimeout(requestId)
    }

    val rootPrefix = stateRoot.take(4).toHex
    val startPrefix = firstTask.next.take(4).toHex
    val limitPrefix = firstTask.last.take(4).toHex
    val accountsPreview = accountHashes.take(3).map(_.take(4).toHex).mkString(",")

    log.debug(
      s"Requesting storage ranges from peer ${peer.id} " +
        s"(requestId=$requestId, accounts=${batchTasks.size}, root=$rootPrefix, start=$startPrefix, limit=$limitPrefix, " +
        s"bytes=$maxResponseSize, accountsPreview=$accountsPreview)"
    )

    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
    val messageSerializable: MessageSerializable = new GetStorageRangesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    Some(requestId)
  }

  private def handleResponse(response: StorageRanges): Unit = {
    requestTracker.validateStorageRanges(response) match {
      case Left(error) =>
        log.warning(s"Invalid StorageRanges response: $error")

      case Right(validResponse) =>
        requestTracker.completeRequest(response.requestId) match {
          case None =>
            log.warning(s"Received response for unknown request ID ${response.requestId}")

          case Some(pendingReq) =>
            activeTasks.remove(response.requestId) match {
              case None =>
                log.warning(s"No active tasks for request ID ${response.requestId}")

              case Some((peer, batchTasks)) =>
                processStorageRanges(peer, batchTasks, validResponse)
            }
        }
    }
  }

  private def processStorageRanges(peer: Peer, tasks: Seq[StorageTask], response: StorageRanges): Unit = {
    // A response may legitimately return fewer slot-sets than requested accounts.
    // Some clients may also return proofs with zero slot-sets to indicate proof-of-absence.
    val servedCount: Int =
      if (response.slots.nonEmpty) response.slots.size
      else if (response.proof.nonEmpty) math.min(1, tasks.size)
      else 0

    log.info(
      s"Processing storage ranges for ${tasks.size} accounts from peer ${peer.id.value}, " +
        s"received ${response.slots.size} slot sets (served=$servedCount, proofs=${response.proof.size})"
    )

    if (servedCount == 0) {
      recordPeerCooldown(peer, "empty slots + empty proofs")
      tasks.foreach { task =>
        task.pending = false
        this.tasks.enqueue(task)
      }
      return
    }

    val servedTasks = tasks.take(servedCount)
    val unservedTasks = tasks.drop(servedCount)

    if (unservedTasks.nonEmpty) {
      log.debug(s"Re-queueing ${unservedTasks.size} unserved storage tasks")
      unservedTasks.foreach { task =>
        task.pending = false
        this.tasks.enqueue(task)
      }
    }

    servedTasks.zipWithIndex.foreach { case (task, idx) =>
      val accountSlots =
        if (response.slots.nonEmpty && idx < response.slots.size) response.slots(idx)
        else Seq.empty

      // Best-practice: apply proof nodes only to the last served slot-set.
      val proofForThisTask = if (idx == servedCount - 1) response.proof else Seq.empty

      log.debug(s"Processing ${accountSlots.size} slots for account ${task.accountString}")

      task.slots = accountSlots
      task.proof = proofForThisTask

      val verifier = getOrCreateVerifier(task.storageRoot)
      verifier.verifyStorageRange(accountSlots, proofForThisTask, task.next, task.last) match {
        case Left(error) =>
          log.warning(s"Storage proof verification failed for account ${task.accountString}: $error")
          recordPeerCooldown(peer, s"verification failed: $error")
          task.pending = false
          this.tasks.enqueue(task)

        case Right(_) =>
          log.debug(s"Storage proof verified successfully for ${accountSlots.size} slots")

          storeStorageSlots(task, accountSlots) match {
            case Left(error) =>
              log.warning(s"Failed to store storage slots for account ${task.accountString}: $error")
              task.pending = false
              this.tasks.enqueue(task)

            case Right(_) =>
              log.debug(s"Successfully stored ${accountSlots.size} storage slots")

              slotsDownloaded += accountSlots.size
              if (accountSlots.nonEmpty) {
                snapSyncController ! SNAPSyncController.ProgressStorageSlotsSynced(accountSlots.size.toLong)
              }
              val slotBytes = accountSlots.map { case (hash, value) => hash.size + value.size }.sum
              bytesDownloaded += slotBytes

              if (accountSlots.nonEmpty) {
                val lastSlot = accountSlots.last._1
                if (lastSlot.toSeq.compare(task.last.toSeq) < 0) {
                  val continuationTask = StorageTask.createContinuation(task, lastSlot)
                  this.tasks.enqueue(continuationTask)
                  log.debug(s"Created continuation task for account ${task.accountString}")
                }
              }

              task.done = true
              task.pending = false
              completedTasks += task

              log.debug(s"Completed storage task for account ${task.accountString} with ${accountSlots.size} slots")
          }
      }
    }

    // The coordinator previously relied on StorageTaskComplete messages that are not emitted by
    // the current actor-based storage pipeline. Explicitly check completion after each response.
    self ! StorageCheckCompletion
  }

  private def storeStorageSlots(
      task: StorageTask,
      slots: Seq[(ByteString, ByteString)]
  ): Either[String, Unit] =
    try {
      import com.chipprbots.ethereum.mpt.{byteStringSerializer, MerklePatriciaTrie}

      if (slots.nonEmpty) {
        val accountHash = task.accountHash

        val storageTrie = storageTrieCache.getOrElseUpdate(
          accountHash, {
            // Important: the storageRoot in account data refers to the *remote* (pivot) trie.
            // We generally do not have its nodes locally yet, so constructing a trie at that
            // root and then calling put() will immediately fail with MissingRootNodeException.
            // Start from an empty trie and grow it as ranges arrive.
            log.debug(
              s"Creating empty storage trie for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}"
            )
            MerklePatriciaTrie[ByteString, ByteString](mptStorage)
          }
        )

        var currentTrie = storageTrie
        slots.foreach { case (slotHash, slotValue) =>
          log.debug(
            s"Storing storage slot ${slotHash.take(4).toArray.map("%02x".format(_)).mkString} = " +
              s"${slotValue.take(4).toArray.map("%02x".format(_)).mkString} for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}"
          )
          currentTrie = currentTrie.put(slotHash, slotValue)
        }

        storageTrieCache.put(accountHash, currentTrie)

        log.info(
          s"Inserted ${slots.size} storage slots for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString} (cache size: ${storageTrieCache.size})"
        )

        val computedRoot = ByteString(currentTrie.getRootHash)
        val expectedRoot = task.storageRoot
        if (computedRoot != expectedRoot) {
          log.debug(s"Storage root mismatch for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
        }

        mptStorage.synchronized {
          mptStorage.persist()
        }

        log.info(
          s"Successfully persisted ${slots.size} storage slots for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}"
        )
        Right(())
      } else {
        Right(())
      }
    } catch {
      case e: Exception =>
        log.error(s"Failed to store storage slots: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")
    }

  private def getOrCreateVerifier(storageRoot: ByteString): MerkleProofVerifier =
    proofVerifiers.getOrElseUpdate(storageRoot, MerkleProofVerifier(storageRoot))

  private def handleTimeout(requestId: BigInt): Unit = {
    activeTasks.remove(requestId).foreach { case (peer, batchTasks) =>
      log.warning(s"Storage range request timeout for ${batchTasks.size} accounts")
      recordPeerCooldown(peer, "request timeout")
      batchTasks.foreach { task =>
        task.pending = false
        tasks.enqueue(task)
      }
    }
  }

  private def progress: Double = {
    val activeCount = activeTasks.values.map(_._2.size).sum
    val total = completedTasks.size + activeCount + tasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  private def isComplete: Boolean = {
    tasks.isEmpty && activeTasks.isEmpty
  }

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
  }
}

object StorageRangeCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      maxAccountsPerBatch: Int,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new StorageRangeCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        maxAccountsPerBatch,
        snapSyncController
      )
    )

  /** Sync statistics for storage range download */
  case class SyncStatistics(
      slotsDownloaded: Long,
      bytesDownloaded: Long,
      tasksCompleted: Int,
      tasksActive: Int,
      tasksPending: Int,
      elapsedTimeMs: Long,
      progress: Double
  ) {
    def throughputSlotsPerSec: Double =
      if (elapsedTimeMs > 0) slotsDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    def throughputBytesPerSec: Double =
      if (elapsedTimeMs > 0) bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    override def toString: String =
      f"Progress: ${progress * 100}%.1f%%, Slots: $slotsDownloaded, " +
        f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
        f"Speed: ${throughputSlotsPerSec}%.1f slots/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
  }
}
