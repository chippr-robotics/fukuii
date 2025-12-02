package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.mpt.{MerklePatriciaTrie, ByteArraySerializable}

/** LRU cache for storage tries to limit memory usage
  * 
  * LinkedHashMap maintains insertion order. For LRU, we move accessed items to end.
  * When cache is full, we evict the head (oldest/least recently used).
  */
private class StorageTrieCache(maxSize: Int = 10000) {
  private val cache = scala.collection.mutable.LinkedHashMap[ByteString, MerklePatriciaTrie[ByteString, ByteString]]()
  
  def getOrElseUpdate(key: ByteString, default: => MerklePatriciaTrie[ByteString, ByteString]): MerklePatriciaTrie[ByteString, ByteString] = {
    cache.get(key) match {
      case Some(trie) =>
        // Move to end (most recently used)
        cache.remove(key)
        cache.put(key, trie)
        trie
      case None =>
        // Create new entry
        val trie = default
        put(key, trie)
        trie
    }
  }
  
  def get(key: ByteString): Option[MerklePatriciaTrie[ByteString, ByteString]] = {
    cache.get(key).map { trie =>
      cache.remove(key)
      cache.put(key, trie)
      trie
    }
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

/** Storage Range Downloader for SNAP sync
  *
  * Downloads storage ranges for contract accounts in parallel, verifies storage proofs,
  * and stores storage slots locally. Follows core-geth snap sync patterns.
  *
  * Features:
  * - Parallel storage range downloads from multiple peers
  * - Per-account storage trie verification using MerkleProofVerifier
  * - Storage slot persistence using MptStorage
  * - Progress tracking and statistics
  * - Task continuation on timeout/failure
  * - Configurable batch size
  *
  * Unlike AccountRangeDownloader which downloads ranges of accounts, this downloader
  * works on a per-account basis, downloading all storage slots for each account.
  *
  * @param stateRoot State root hash (for context, not directly used)
  * @param etcPeerManager Actor for peer communication
  * @param requestTracker Request/response tracker
  * @param mptStorage Storage for persisting downloaded storage slots
  * @param maxAccountsPerBatch Maximum accounts to request storage for in a single request (default 8)
  */
class StorageRangeDownloader(
    stateRoot: ByteString,
    etcPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    maxAccountsPerBatch: Int = 8
)(implicit scheduler: Scheduler) extends Logger {

  import StorageRangeDownloader._

  /** Queue of storage tasks to download */
  private val tasks = mutable.Queue[StorageTask]()

  /** Tasks currently being downloaded */
  private val activeTasks = mutable.Map[BigInt, Seq[StorageTask]]() // requestId -> tasks

  /** Completed tasks */
  private val completedTasks = mutable.ArrayBuffer[StorageTask]()

  /** Statistics */
  private var slotsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  /** Maximum response size in bytes (512 KB like core-geth) */
  private val maxResponseSize: BigInt = 512 * 1024

  /** Merkle proof verifier (created lazily per account) */
  private val proofVerifiers = mutable.Map[ByteString, MerkleProofVerifier]()

  /** Per-account storage tries - LRU cache to limit memory usage */
  private val storageTrieCache = new StorageTrieCache(10000)

  /** Add storage tasks to the download queue
    *
    * @param storageTasks Tasks to add
    */
  def addTasks(storageTasks: Seq[StorageTask]): Unit = synchronized {
    tasks.enqueueAll(storageTasks)
    log.info(s"Added ${storageTasks.size} storage tasks to queue (total pending: ${tasks.size})")
  }

  /** Add a single storage task to the queue
    *
    * @param task Task to add
    */
  def addTask(task: StorageTask): Unit = synchronized {
    tasks.enqueue(task)
    log.debug(s"Added storage task for account ${task.accountString} to queue")
  }

  /** Request next batch of storage ranges from available peer
    *
    * Batches multiple account storage requests together to reduce message overhead.
    * Follows core-geth pattern of requesting storage for multiple accounts at once.
    *
    * @param peer Peer to request from
    * @return Request ID if request was sent, None otherwise
    */
  def requestNextRanges(peer: Peer): Option[BigInt] = synchronized {
    if (tasks.isEmpty) {
      log.debug("No more storage tasks available")
      return None
    }

    // Dequeue up to maxAccountsPerBatch tasks
    val batchTasks = (0 until maxAccountsPerBatch).flatMap { _ =>
      if (tasks.nonEmpty) Some(tasks.dequeue()) else None
    }

    if (batchTasks.isEmpty) {
      return None
    }

    val requestId = requestTracker.generateRequestId()
    
    // Extract account hashes from tasks
    val accountHashes = batchTasks.map(_.accountHash)
    
    // For simplicity, use the first task's range boundaries
    // In a more sophisticated implementation, each account could have different ranges
    val firstTask = batchTasks.head
    
    val request = GetStorageRanges(
      requestId = requestId,
      rootHash = stateRoot,
      accountHashes = accountHashes,
      startingHash = firstTask.next,
      limitHash = firstTask.last,
      responseBytes = maxResponseSize
    )

    // Mark tasks as pending
    batchTasks.foreach(_.pending = true)
    activeTasks.put(requestId, batchTasks)

    // Track request with timeout
    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetStorageRanges,
      timeout = 30.seconds
    ) {
      handleTimeout(requestId)
    }

    log.debug(s"Requesting storage ranges for ${batchTasks.size} accounts from peer ${peer.id} (request ID: $requestId)")
    
    // Send request via EtcPeerManager
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
    val messageSerializable: MessageSerializable = new GetStorageRangesEnc(request)
    etcPeerManager ! EtcPeerManagerActor.SendMessage(messageSerializable, peer.id)
    
    Some(requestId)
  }

  /** Handle StorageRanges response
    *
    * @param response The response message
    * @return Either error message or number of slots processed
    */
  def handleResponse(response: StorageRanges): Either[String, Int] = synchronized {
    // Validate response
    requestTracker.validateStorageRanges(response) match {
      case Left(error) =>
        log.warn(s"Invalid StorageRanges response: $error")
        return Left(error)
      
      case Right(validResponse) =>
        // Complete the request
        requestTracker.completeRequest(response.requestId) match {
          case None =>
            log.warn(s"Received response for unknown request ID ${response.requestId}")
            return Left(s"Unknown request ID: ${response.requestId}")
          
          case Some(pendingReq) =>
            activeTasks.remove(response.requestId) match {
              case None =>
                log.warn(s"No active tasks for request ID ${response.requestId}")
                return Left(s"No active tasks for request ID")
              
              case Some(batchTasks) =>
                // Process the response
                processStorageRanges(batchTasks, validResponse)
            }
        }
    }
  }

  /** Process downloaded storage ranges
    *
    * StorageRanges response contains a list of slot sets, one per account.
    * Match them up with the requested tasks and verify/store each.
    *
    * @param tasks The tasks being filled
    * @param response The validated response
    * @return Number of slots processed across all accounts
    */
  private def processStorageRanges(
      tasks: Seq[StorageTask],
      response: StorageRanges
  ): Either[String, Int] = {
    
    log.info(s"Processing storage ranges for ${tasks.size} accounts, received ${response.slots.size} slot sets")
    
    // Match tasks with slot sets (they should be in same order as requested)
    if (response.slots.size > tasks.size) {
      log.warn(s"Received more slot sets (${response.slots.size}) than requested accounts (${tasks.size})")
    }
    
    var totalSlots = 0
    var errors = List.empty[String]
    
    // Process each account's storage
    tasks.zipWithIndex.foreach { case (task, idx) =>
      val accountSlots = if (idx < response.slots.size) response.slots(idx) else Seq.empty
      
      log.debug(s"Processing ${accountSlots.size} slots for account ${task.accountString}")
      
      // Store slots in task
      task.slots = accountSlots
      task.proof = response.proof // Shared proof for all accounts
      
      // Verify storage proof for this account
      val verifier = getOrCreateVerifier(task.storageRoot)
      verifier.verifyStorageRange(
        accountSlots,
        response.proof,
        task.next,
        task.last
      ) match {
        case Left(error) =>
          val errMsg = s"Storage proof verification failed for account ${task.accountString}: $error"
          log.warn(errMsg)
          errors = errMsg :: errors
          // Mark task as not pending so it can be retried
          task.pending = false
          this.tasks.enqueue(task) // Re-queue for retry (use class field, not parameter)
        
        case Right(_) =>
          log.debug(s"Storage proof verified successfully for ${accountSlots.size} slots")
          
          // Store storage slots to database
          storeStorageSlots(task.accountHash, accountSlots) match {
            case Left(error) =>
              val errMsg = s"Failed to store storage slots for account ${task.accountString}: $error"
              log.warn(errMsg)
              errors = errMsg :: errors
              task.pending = false
              this.tasks.enqueue(task) // Re-queue for retry (use class field, not parameter)
            
            case Right(_) =>
              log.debug(s"Successfully stored ${accountSlots.size} storage slots")
              
              // Update statistics
              slotsDownloaded += accountSlots.size
              val slotBytes = accountSlots.map { case (hash, value) =>
                hash.size + value.size
              }.sum
              bytesDownloaded += slotBytes
              totalSlots += accountSlots.size
              
              // Check if we need continuation
              if (accountSlots.nonEmpty) {
                val lastSlot = accountSlots.last._1
                
                // If we didn't reach the end of the range, create continuation task
                if (lastSlot.toSeq.compare(task.last.toSeq) < 0) {
                  val continuationTask = StorageTask.createContinuation(task, lastSlot)
                  this.tasks.enqueue(continuationTask) // Use class field, not parameter
                  log.debug(s"Created continuation task for account ${task.accountString} from ${continuationTask.rangeString}")
                }
              }
              
              // Mark task as done
              task.done = true
              task.pending = false
              completedTasks += task
              
              log.debug(s"Completed storage task for account ${task.accountString} with ${accountSlots.size} slots")
          }
      }
    }
    
    if (errors.nonEmpty) {
      Left(s"Errors processing storage: ${errors.mkString(", ")}")
    } else {
      Right(totalSlots)
    }
  }

  /** Store storage slots to MptStorage
    *
    * Inserts storage slots into per-account storage tries using proper MPT structure.
    * Thread-safe storage with synchronized access for concurrent task writes.
    *
    * Implementation approach:
    * - Gets or creates a storage trie for the account using its storage root
    * - Inserts each slot into that account's storage trie using trie.put()
    * - The trie automatically maintains proper MPT structure and node relationships
    * - Verifies the resulting trie root matches the account's storage root
    * - Persists the trie after insertions
    *
    * @param accountHash Hash of the account owning this storage
    * @param slots Storage slots to store (slotHash -> slotValue)
    * @return Either error or success
    */
  private def storeStorageSlots(
      accountHash: ByteString,
      slots: Seq[(ByteString, ByteString)]
  ): Either[String, Unit] = {
    try {
      import com.chipprbots.ethereum.mpt.{byteStringSerializer, MerklePatriciaTrie}
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
      
      // Synchronize on this instance to protect storageTrieCache
      this.synchronized {
        if (slots.nonEmpty) {
          // Get the storage task for this account to obtain storage root
          val storageTask = tasks.find(_.accountHash == accountHash)
            .orElse(activeTasks.values.flatten.find(_.accountHash == accountHash))
            .orElse(completedTasks.find(_.accountHash == accountHash))
            .getOrElse {
              log.warn(s"No storage task found for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
              return Left(s"No storage task found for account")
            }
          
          // Get or create storage trie with exception handling
          val storageTrie = storageTrieCache.getOrElseUpdate(accountHash, {
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
          })
          
          // Insert slots
          var currentTrie = storageTrie
          slots.foreach { case (slotHash, slotValue) =>
            log.debug(s"Storing storage slot ${slotHash.take(4).toArray.map("%02x".format(_)).mkString} = " +
              s"${slotValue.take(4).toArray.map("%02x".format(_)).mkString} for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
            
            currentTrie = currentTrie.put(slotHash, slotValue)
          }
          
          // Update cache
          storageTrieCache.put(accountHash, currentTrie)
          
          log.info(s"Inserted ${slots.size} storage slots for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString} (cache size: ${storageTrieCache.size})")
          
          // Verify storage root
          val computedRoot = ByteString(currentTrie.getRootHash)
          val expectedRoot = storageTask.storageRoot
          if (computedRoot != expectedRoot) {
            log.warn(s"Storage root mismatch for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
            // TODO: Queue for healing in future enhancement
          }
        }
      }
      
      // Persist after releasing this.synchronized to avoid nested locks
      mptStorage.synchronized {
        mptStorage.persist()
      }
      
      log.info(s"Successfully persisted ${slots.size} storage slots for account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString}")
      Right(())
    } catch {
      case e: Exception =>
        log.error(s"Failed to store storage slots: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")
    }
  }

  /** Get or create proof verifier for a storage root
    *
    * @param storageRoot Storage root hash
    * @return Verifier for this storage root
    */
  private def getOrCreateVerifier(storageRoot: ByteString): MerkleProofVerifier = {
    proofVerifiers.getOrElseUpdate(storageRoot, MerkleProofVerifier(storageRoot))
  }

  /** Handle request timeout
    *
    * @param requestId The timed out request ID
    */
  private def handleTimeout(requestId: BigInt): Unit = synchronized {
    activeTasks.remove(requestId).foreach { batchTasks =>
      log.warn(s"Storage range request timeout for ${batchTasks.size} accounts")
      batchTasks.foreach { task =>
        task.pending = false
        // Re-queue the task for retry
        tasks.enqueue(task)
      }
    }
  }

  /** Get sync progress (0.0 to 1.0) */
  def progress: Double = synchronized {
    val total = completedTasks.size + activeTasks.values.flatten.size + tasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  /** Get sync statistics */
  def statistics: SyncStatistics = synchronized {
    val elapsedMs = System.currentTimeMillis() - startTime
    SyncStatistics(
      slotsDownloaded = slotsDownloaded,
      bytesDownloaded = bytesDownloaded,
      tasksCompleted = completedTasks.size,
      tasksActive = activeTasks.values.flatten.size,
      tasksPending = tasks.size,
      elapsedTimeMs = elapsedMs,
      progress = progress
    )
  }

  /** Check if sync is complete */
  def isComplete: Boolean = synchronized {
    tasks.isEmpty && activeTasks.isEmpty
  }
}

object StorageRangeDownloader {

  /** Sync statistics */
  case class SyncStatistics(
      slotsDownloaded: Long,
      bytesDownloaded: Long,
      tasksCompleted: Int,
      tasksActive: Int,
      tasksPending: Int,
      elapsedTimeMs: Long,
      progress: Double
  ) {
    def throughputSlotsPerSec: Double = {
      if (elapsedTimeMs > 0) slotsDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0
    }

    def throughputBytesPerSec: Double = {
      if (elapsedTimeMs > 0) bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0
    }

    override def toString: String = {
      f"Progress: ${progress * 100}%.1f%%, Slots: $slotsDownloaded, " +
      f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
      f"Speed: ${throughputSlotsPerSec}%.1f slots/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
    }
  }
}
