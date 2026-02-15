package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.{DeferredWriteMptStorage, MptStorage}
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
  * locally. Uses adaptive per-peer tuning for response size, batch size, and stateless peer detection to maximize
  * throughput within snap/1 protocol limits.
  *
  * @param initialStateRoot
  *   State root hash
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param mptStorage
  *   MPT storage
  * @param maxAccountsPerBatch
  *   Max accounts per batch
  * @param maxInFlightRequests
  *   Max concurrent in-flight requests
  * @param requestTimeout
  *   Timeout for individual requests
  * @param snapSyncController
  *   Parent controller
  */
class StorageRangeCoordinator(
    initialStateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    maxAccountsPerBatch: Int,
  maxInFlightRequests: Int,
  requestTimeout: FiniteDuration,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Task management
  private val tasks = mutable.Queue[StorageTask]()
  private val activeTasks = mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]() // requestId -> (peer, tasks, requestedBytes)
  private val completedTasks = mutable.ArrayBuffer[StorageTask]()

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  // This is separate from stateless peer detection — cooldowns are short and per-error-type.
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  // Binary stateless peer detection: peers that cannot serve the current state root.
  // When ALL known peers become stateless, request a pivot refresh from the controller.
  // This replaces the slow counter-based global backoff (was: 10 empties → 2 min pause).
  private val statelessPeers = mutable.Set[String]()
  private var pivotRefreshRequested = false

  private def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id.value)

  private def markPeerStateless(peer: Peer): Unit = {
    statelessPeers.add(peer.id.value)
    log.info(
      s"Peer ${peer.id.value} marked stateless for storage root ${stateRoot.take(4).toHex} " +
        s"(${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
    )
    maybeRequestPivotRefresh()
  }

  private def maybeRequestPivotRefresh(): Unit = {
    if (pivotRefreshRequested) return
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id.value))
    if (allStateless) {
      pivotRefreshRequested = true
      log.warning(
        s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
          "Requesting pivot refresh from controller."
      )
      snapSyncController ! SNAPSyncController.PivotStateUnservable(
        rootHash = stateRoot,
        reason = "all peers stateless for StorageRange root",
        consecutiveEmptyResponses = statelessPeers.size
      )
    }
  }

  // Per-peer adaptive batch size: tracks which peers support multi-account batching.
  // Starts at maxAccountsPerBatch, ratchets to 1 on empty batched response for that specific peer.
  private val peerBatchSize = mutable.Map.empty[String, Int]

  private def batchSizeFor(peer: Peer): Int =
    peerBatchSize.getOrElseUpdate(peer.id.value, maxAccountsPerBatch)

  private def reduceBatchSize(peer: Peer): Unit =
    peerBatchSize.update(peer.id.value, 1)

  // Track last known available peers so we can re-dispatch after task failures
  // without waiting for the next StoragePeerAvailable message.
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Per-task empty-response tracking.
  // Some peers legitimately return empty slotSets+proofs in cases we can't easily distinguish
  // from "can't serve this state". If we keep re-queuing forever, sync can livelock.
  // Track empty responses per (accountHash,next,last) and skip after a small threshold.
  private case class StorageTaskKey(accountHash: ByteString, next: ByteString, last: ByteString)
  private val emptyResponsesByTask = mutable.HashMap.empty[StorageTaskKey, Int]
  private val maxEmptyResponsesPerTask: Int = 3

  // Statistics
  private var slotsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Per-peer adaptive byte budgeting (ported from ByteCodeCoordinator).
  // Geth's snap handler supports up to 2MB responses. Starting at 512KB and probing upward
  // on responsive peers, scaling down on failures.
  private val minResponseBytes: BigInt = 50 * 1024        // 50KB floor
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024   // 2MB ceiling (Geth handler limit)
  private val initialResponseBytes: BigInt = 512 * 1024     // 512KB starting point
  private val increaseFactor: Double = 1.25                  // Scale up when 90%+ fill
  private val decreaseFactor: Double = 0.5                   // Scale down on failure/empty

  private val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  private def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget.getOrElseUpdate(peer.id.value, initialResponseBytes)
      .max(minResponseBytes).min(maxResponseBytes)

  private def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit = {
    if (requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes) {
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))
    }
  }

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = {
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(s"Reducing storage responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)")
  }

  // Deferred-write storage wrapper: makes updateNodesInStorage() a no-op during bulk insertion,
  // keeping all trie nodes in memory. Nodes are flushed to RocksDB in periodic batches.
  private val deferredStorage = new DeferredWriteMptStorage(mptStorage)

  // Periodic flush tracking — flush once per N slots instead of per-response.
  private var slotsSinceLastFlush: Long = 0
  private val flushThreshold: Long = 50000

  // Storage management
  private val proofVerifiers = mutable.Map[ByteString, MerkleProofVerifier]()
  private val storageTrieCache = new StorageTrieCache(10000)

  override def preStart(): Unit = {
    log.info(s"StorageRangeCoordinator starting (concurrency=$maxInFlightRequests, batchSize=$maxAccountsPerBatch)")
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
      knownAvailablePeers += peer
      if (isPeerStateless(peer)) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) - peer is stateless for current root")
      } else if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring StoragePeerAvailable(${peer.id.value}) due to cooldown")
      } else if (!isComplete && tasks.nonEmpty) {
        if (activeTasks.size >= maxInFlightRequests) {
          log.debug(
            s"Storage in-flight limit reached (inFlight=${activeTasks.size}, limit=$maxInFlightRequests); " +
              s"ignoring StoragePeerAvailable(${peer.id.value})"
          )
        } else {
          requestNextRanges(peer)
        }
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
        // Final flush before reporting completion
        deferredStorage.flush()
        log.info("Storage range sync complete!")
        snapSyncController ! SNAPSyncController.StorageRangeSyncComplete
      }

    case StoragePivotRefreshed(newStateRoot) =>
      log.info(s"Storage pivot refreshed: ${stateRoot.take(4).toHex} -> ${newStateRoot.take(4).toHex}")
      stateRoot = newStateRoot

      // Clear all per-peer adaptive state — fresh start with new root
      statelessPeers.clear()
      pivotRefreshRequested = false
      peerCooldownUntilMs.clear()
      peerBatchSize.clear()
      peerResponseBytesTarget.clear()
      emptyResponsesByTask.clear()

      // Resume dispatching with the fresh root
      tryRedispatchPendingTasks()

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

    case StoreStorageSlotChunk(task, remainingSlots, totalCount, storedSoFar, proof, peer, requestedBytes, isLastServedTask) =>
      handleStoreStorageSlotChunk(task, remainingSlots, totalCount, storedSoFar, proof, peer, requestedBytes, isLastServedTask)
  }

  private def requestNextRanges(peer: Peer): Option[BigInt] = {
    if (tasks.isEmpty) {
      log.debug("No more storage tasks available")
      return None
    }

    if (isPeerStateless(peer)) {
      return None
    }

    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    def isInitialRange(t: StorageTask): Boolean = t.next == min && t.last == max

    val peerBatch = batchSizeFor(peer)

    // snap/1 origin/limit semantics apply to the first account only. To avoid incorrect continuation
    // behavior, only batch tasks that request the initial full range.
    val first = tasks.dequeue()
    val batchTasks: Seq[StorageTask] =
      if (!isInitialRange(first) || peerBatch <= 1) {
        Seq(first)
      } else {
        val buf = mutable.ArrayBuffer[StorageTask](first)
        while (buf.size < peerBatch && tasks.nonEmpty && isInitialRange(tasks.front)) {
          buf += tasks.dequeue()
        }
        buf.toSeq
      }

    if (batchTasks.isEmpty) {
      return None
    }

    val requestedBytes = responseBytesTargetFor(peer)
    val requestId = requestTracker.generateRequestId()
    val accountHashes = batchTasks.map(_.accountHash)
    val firstTask = batchTasks.head

    val request = GetStorageRanges(
      requestId = requestId,
      rootHash = stateRoot,
      accountHashes = accountHashes,
      startingHash = firstTask.next,
      limitHash = firstTask.last,
      responseBytes = requestedBytes
    )

    batchTasks.foreach(_.pending = true)
    activeTasks.put(requestId, (peer, batchTasks, requestedBytes))

    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetStorageRanges,
      timeout = requestTimeout
    ) {
      handleTimeout(requestId)
    }

    log.info(
      s"GetStorageRanges: peer=${peer.id.value} accounts=${batchTasks.size} bytes=$requestedBytes requestId=$requestId"
    )

    // Full request details at DEBUG level for troubleshooting
    log.debug(
      s"GetStorageRanges detail: requestId=$requestId root=${stateRoot.toHex} " +
        s"start=${firstTask.next.toHex} limit=${firstTask.last.toHex} " +
        s"accounts=${accountHashes.map(_.take(4).toHex).mkString(",")}"
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

              case Some((peer, batchTasks, requestedBytes)) =>
                processStorageRanges(peer, batchTasks, requestedBytes, validResponse)
            }
        }
    }
  }

  private def processStorageRanges(peer: Peer, tasks: Seq[StorageTask], requestedBytes: BigInt, response: StorageRanges): Unit = {
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
      // Per-peer batch reduction: only reduce for the specific peer that failed
      if (tasks.size > 1 && batchSizeFor(peer) > 1) {
        log.info(
          s"Received empty StorageRanges for a batched request from peer ${peer.id.value} (accounts=${tasks.size}); " +
            s"falling back to single-account requests for this peer"
        )
        reduceBatchSize(peer)
      }

      adjustResponseBytesOnFailure(peer, "empty response")

      // Track empties per task to avoid re-queueing forever.
      // If the same task yields empty responses repeatedly, skip it with a loud warning.
      var skipped = 0
      var requeued = 0
      tasks.foreach { task =>
        val key = StorageTaskKey(task.accountHash, task.next, task.last)
        val attempts = emptyResponsesByTask.getOrElse(key, 0) + 1
        emptyResponsesByTask.update(key, attempts)

        if (attempts >= maxEmptyResponsesPerTask) {
          skipped += 1
          task.done = true
          task.pending = false
          completedTasks += task
          log.warning(
            s"Skipping storage task after $attempts empty StorageRanges replies: " +
              s"account=${task.accountHash.toHex} storageRoot=${task.storageRoot.toHex} range=${task.rangeString}"
          )
        } else {
          requeued += 1
          task.pending = false
          this.tasks.enqueue(task)
          log.debug(
            s"Empty StorageRanges for task (attempt $attempts/$maxEmptyResponsesPerTask); re-queueing: " +
              s"account=${task.accountHash.take(4).toHex} range=${task.rangeString}"
          )
        }
      }

      // If we skipped something, we made forward progress.
      if (skipped > 0) {
        self ! StorageCheckCompletion
        return
      }

      // No task was skipped — mark this peer as potentially stateless for current root.
      markPeerStateless(peer)
      return
    }

    // Non-empty response — clear stateless marking for this peer if present
    statelessPeers.remove(peer.id.value)

    // Clear empty-response counters for tasks that are now being served.
    tasks.foreach { task =>
      emptyResponsesByTask.remove(StorageTaskKey(task.accountHash, task.next, task.last))
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

    // Track total received bytes across all served tasks for adaptive byte budgeting
    var totalReceivedBytes: Long = 0

    servedTasks.zipWithIndex.foreach { case (task, idx) =>
      val accountSlots =
        if (response.slots.nonEmpty && idx < response.slots.size) response.slots(idx)
        else Seq.empty

      // Best-practice: apply proof nodes only to the last served slot-set.
      val proofForThisTask = if (idx == servedCount - 1) response.proof else Seq.empty

      task.slots = accountSlots
      task.proof = proofForThisTask

      val verifier = getOrCreateVerifier(task.storageRoot)
      verifier.verifyStorageRange(accountSlots, proofForThisTask, task.next, task.last) match {
        case Left(error) =>
          log.warning(s"Storage proof verification failed for account ${task.accountString}: $error")
          recordPeerCooldown(peer, s"verification failed: $error")
          adjustResponseBytesOnFailure(peer, s"verification failed: $error")
          task.pending = false
          this.tasks.enqueue(task)

        case Right(_) =>
          val slotBytes = accountSlots.map { case (hash, value) => hash.size + value.size }.sum
          totalReceivedBytes += slotBytes

          val isLastServed = idx == servedCount - 1

          if (accountSlots.nonEmpty) {
            // Start chunked async storage via self-messages
            self ! StoreStorageSlotChunk(
              task = task,
              remainingSlots = accountSlots,
              totalCount = accountSlots.size,
              storedSoFar = 0,
              proof = proofForThisTask,
              peer = peer,
              requestedBytes = requestedBytes,
              isLastServedTask = isLastServed
            )
          } else {
            // No slots to store — mark task done
            task.done = true
            task.pending = false
            completedTasks += task
          }
      }
    }

    // Adjust per-peer byte budget based on total received bytes
    if (totalReceivedBytes > 0) {
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(totalReceivedBytes))
    }

    // Check completion after processing all served tasks
    self ! StorageCheckCompletion
  }

  // How many storage slots to insert per chunk before yielding to the actor mailbox.
  // With DeferredWriteMptStorage, puts are purely in-memory (~1-10us each).
  private val storeChunkSize = 2000

  private def handleStoreStorageSlotChunk(
      task: StorageTask,
      remainingSlots: Seq[(ByteString, ByteString)],
      totalCount: Int,
      storedSoFar: Int,
      proof: Seq[ByteString],
      peer: Peer,
      requestedBytes: BigInt,
      isLastServedTask: Boolean
  ): Unit = {
    val (chunk, rest) = remainingSlots.splitAt(storeChunkSize)
    try {
      import com.chipprbots.ethereum.mpt.byteStringSerializer

      val accountHash = task.accountHash
      val storageTrie = storageTrieCache.getOrElseUpdate(
        accountHash, {
          log.debug(
            s"Creating empty storage trie for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}"
          )
          MerklePatriciaTrie[ByteString, ByteString](deferredStorage)
        }
      )

      var currentTrie = storageTrie
      chunk.foreach { case (slotHash, slotValue) =>
        currentTrie = currentTrie.put(slotHash, slotValue)
      }
      storageTrieCache.put(accountHash, currentTrie)

      val newStored = storedSoFar + chunk.size
      slotsDownloaded += chunk.size
      bytesDownloaded += chunk.map { case (hash, value) => hash.size + value.size }.sum

      if (chunk.nonEmpty) {
        snapSyncController ! SNAPSyncController.ProgressStorageSlotsSynced(chunk.size.toLong)
      }

      // Periodic deferred flush
      slotsSinceLastFlush += chunk.size
      if (slotsSinceLastFlush >= flushThreshold) {
        deferredStorage.flush()
        slotsSinceLastFlush = 0
        log.info(s"Flushed deferred storage writes to disk (${slotsDownloaded} total slots)")
      }

      if (rest.nonEmpty) {
        // More chunks to process — yield to mailbox between chunks
        self ! StoreStorageSlotChunk(task, rest, totalCount, newStored, proof, peer, requestedBytes, isLastServedTask)
      } else {
        // All chunks stored for this task
        log.debug(
          s"Stored $totalCount storage slots for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}"
        )

        // Handle continuation: if the last slot hash is before the task's end, create continuation
        val allSlots = task.slots
        if (allSlots.nonEmpty) {
          val lastSlot = allSlots.last._1
          if (lastSlot.toSeq.compare(task.last.toSeq) < 0) {
            val continuationTask = StorageTask.createContinuation(task, lastSlot)
            this.tasks.enqueue(continuationTask)
            log.debug(s"Created continuation task for account ${task.accountString}")
          }
        }

        task.done = true
        task.pending = false
        completedTasks += task

        self ! StorageCheckCompletion
      }
    } catch {
      case e: Exception =>
        log.error(e, s"Failed to store storage slots chunk: ${e.getMessage}")
        // Re-queue the task on failure
        task.pending = false
        this.tasks.enqueue(task)
    }
  }

  private def getOrCreateVerifier(storageRoot: ByteString): MerkleProofVerifier =
    proofVerifiers.getOrElseUpdate(storageRoot, MerkleProofVerifier(storageRoot))

  private def handleTimeout(requestId: BigInt): Unit = {
    activeTasks.remove(requestId).foreach { case (peer, batchTasks, _) =>
      log.warning(s"Storage range request timeout for ${batchTasks.size} accounts from peer ${peer.id.value}")
      recordPeerCooldown(peer, "request timeout")
      adjustResponseBytesOnFailure(peer, "request timeout")
      batchTasks.foreach { task =>
        task.pending = false
        tasks.enqueue(task)
      }
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless or on cooldown.
    tryRedispatchPendingTasks()
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (tasks.isEmpty) return
    val eligiblePeers = knownAvailablePeers
      .filterNot(p => isPeerStateless(p) || isPeerCoolingDown(p))
      .toList
    if (eligiblePeers.isEmpty) return

    for (peer <- eligiblePeers if tasks.nonEmpty && activeTasks.size < maxInFlightRequests) {
      requestNextRanges(peer)
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
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new StorageRangeCoordinator(
        initialStateRoot = stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        maxAccountsPerBatch,
        maxInFlightRequests,
        requestTimeout,
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
