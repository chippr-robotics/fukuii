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
  private val activeTasks = mutable.Map[BigInt, Seq[StorageTask]]()
  private val completedTasks = mutable.ArrayBuffer[StorageTask]()

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
      if (!isComplete && workers.size < maxWorkers) {
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
      val stats = StorageRangeDownloader.SyncStatistics(
        slotsDownloaded = slotsDownloaded,
        bytesDownloaded = bytesDownloaded,
        tasksCompleted = completedTasks.size,
        tasksActive = activeTasks.values.flatten.size,
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

    val batchTasks = (0 until maxAccountsPerBatch).flatMap { _ =>
      if (tasks.nonEmpty) Some(tasks.dequeue()) else None
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
    activeTasks.put(requestId, batchTasks)

    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetStorageRanges,
      timeout = 30.seconds
    ) {
      handleTimeout(requestId)
    }

    log.debug(
      s"Requesting storage ranges for ${batchTasks.size} accounts from peer ${peer.id} (request ID: $requestId)"
    )

    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
    val messageSerializable: MessageSerializable = new GetStorageRangesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    Some(requestId)
  }

  private def handleResponse(response: StorageRanges): Unit = {
    requestTracker.validateStorageRanges(response) match {
      case Left(error) =>
        log.warn(s"Invalid StorageRanges response: $error")

      case Right(validResponse) =>
        requestTracker.completeRequest(response.requestId) match {
          case None =>
            log.warn(s"Received response for unknown request ID ${response.requestId}")

          case Some(pendingReq) =>
            activeTasks.remove(response.requestId) match {
              case None =>
                log.warn(s"No active tasks for request ID ${response.requestId}")

              case Some(batchTasks) =>
                processStorageRanges(batchTasks, validResponse)
            }
        }
    }
  }

  private def processStorageRanges(tasks: Seq[StorageTask], response: StorageRanges): Unit = {
    log.info(s"Processing storage ranges for ${tasks.size} accounts, received ${response.slots.size} slot sets")

    if (response.slots.size > tasks.size) {
      log.warn(s"Received more slot sets (${response.slots.size}) than requested accounts (${tasks.size})")
    }

    tasks.zipWithIndex.foreach { case (task, idx) =>
      val accountSlots = if (idx < response.slots.size) response.slots(idx) else Seq.empty

      log.debug(s"Processing ${accountSlots.size} slots for account ${task.accountString}")

      task.slots = accountSlots
      task.proof = response.proof

      val verifier = getOrCreateVerifier(task.storageRoot)
      verifier.verifyStorageRange(accountSlots, response.proof, task.next, task.last) match {
        case Left(error) =>
          log.warn(s"Storage proof verification failed for account ${task.accountString}: $error")
          task.pending = false
          this.tasks.enqueue(task)

        case Right(_) =>
          log.debug(s"Storage proof verified successfully for ${accountSlots.size} slots")

          storeStorageSlots(task.accountHash, accountSlots) match {
            case Left(error) =>
              log.warn(s"Failed to store storage slots for account ${task.accountString}: $error")
              task.pending = false
              this.tasks.enqueue(task)

            case Right(_) =>
              log.debug(s"Successfully stored ${accountSlots.size} storage slots")

              slotsDownloaded += accountSlots.size
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
  }

  private def storeStorageSlots(
      accountHash: ByteString,
      slots: Seq[(ByteString, ByteString)]
  ): Either[String, Unit] =
    try {
      import com.chipprbots.ethereum.mpt.{byteStringSerializer, MerklePatriciaTrie}
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException

      if (slots.nonEmpty) {
        val storageTaskOpt = tasks
          .find(_.accountHash == accountHash)
          .orElse(activeTasks.values.flatten.find(_.accountHash == accountHash))
          .orElse(completedTasks.find(_.accountHash == accountHash))

        storageTaskOpt match {
          case None =>
            log.warn(s"No storage task found for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
            Left(s"No storage task found for account")
            
          case Some(storageTask) =>
            val storageTrie = storageTrieCache.getOrElseUpdate(
              accountHash, {
                val storageRoot = storageTask.storageRoot
                if (storageRoot.isEmpty || storageRoot == ByteString(MerklePatriciaTrie.EmptyRootHash)) {
                  log.debug(s"Creating empty storage trie for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
                  MerklePatriciaTrie[ByteString, ByteString](mptStorage)
                } else {
                  try {
                    log.debug(s"Loading storage trie for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
                    MerklePatriciaTrie[ByteString, ByteString](storageRoot.toArray, mptStorage)
                  } catch {
                    case e: MissingRootNodeException =>
                      log.warn(s"Storage root not found for account, creating new trie")
                      MerklePatriciaTrie[ByteString, ByteString](mptStorage)
                  }
                }
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
            val expectedRoot = storageTask.storageRoot
            if (computedRoot != expectedRoot) {
              log.warn(s"Storage root mismatch for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
            }

            mptStorage.synchronized {
              mptStorage.persist()
            }

            log.info(
              s"Successfully persisted ${slots.size} storage slots for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}"
            )
            Right(())
        }
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
    activeTasks.remove(requestId).foreach { batchTasks =>
      log.warn(s"Storage range request timeout for ${batchTasks.size} accounts")
      batchTasks.foreach { task =>
        task.pending = false
        tasks.enqueue(task)
      }
    }
  }

  private def progress: Double = {
    val total = completedTasks.size + activeTasks.values.flatten.size + tasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  private def isComplete: Boolean = {
    tasks.isEmpty && activeTasks.isEmpty
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
}
