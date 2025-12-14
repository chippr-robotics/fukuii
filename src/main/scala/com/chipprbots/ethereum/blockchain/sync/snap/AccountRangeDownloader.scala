package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie

class AccountRangeDownloader(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    concurrency: Int = 16
) extends Logger {

  import AccountRangeDownloader._

  private val tasks = mutable.Queue[AccountTask](AccountTask.createInitialTasks(stateRoot, concurrency): _*)

  private val activeTasks = mutable.Map[BigInt, AccountTask]()

  private val completedTasks = mutable.ArrayBuffer[AccountTask]()

  /** Statistics */
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  /** Contract accounts (accountHash, codeHash) for bytecode download */
  private val contractAccounts = mutable.ArrayBuffer[(ByteString, ByteString)]()

  /** Maximum response size in bytes (512 KB like core-geth) */
  private val maxResponseSize: BigInt = 512 * 1024

  /** Merkle proof verifier */
  private val proofVerifier = MerkleProofVerifier(stateRoot)

  /** State trie for storing accounts - initialized with existing state root or new trie */
  private var stateTrie: MerklePatriciaTrie[ByteString, Account] = {
    import com.chipprbots.ethereum.mpt.{byteStringSerializer, MerklePatriciaTrie}
    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException

    // Initialize trie with the state root if it exists, otherwise create empty trie
    if (stateRoot.isEmpty || stateRoot == ByteString(MerklePatriciaTrie.EmptyRootHash)) {
      log.info("Initializing new empty state trie")
      MerklePatriciaTrie[ByteString, Account](mptStorage)
    } else {
      try {
        log.info(
          s"Loading existing state trie with root ${stateRoot.take(8).toArray.map("%02x".format(_)).mkString}..."
        )
        MerklePatriciaTrie[ByteString, Account](stateRoot.toArray, mptStorage)
      } catch {
        case e: MissingRootNodeException =>
          log.warn(s"State root not found in storage, creating new trie")
          MerklePatriciaTrie[ByteString, Account](mptStorage)
      }
    }
  }

  /** Request next account range from available peer
    *
    * @param peer
    *   Peer to request from
    * @return
    *   Request ID if request was sent, None otherwise
    */
  def requestNextRange(peer: Peer): Option[BigInt] = synchronized {
    if (tasks.isEmpty) {
      log.debug("No more account tasks available")
      return None
    }

    val task = tasks.dequeue()
    val requestId = requestTracker.generateRequestId()

    val request = GetAccountRange(
      requestId = requestId,
      rootHash = stateRoot,
      startingHash = task.next,
      limitHash = task.last,
      responseBytes = maxResponseSize
    )

    task.pending = true
    activeTasks.put(requestId, task)

    // Track request with timeout
    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetAccountRange,
      timeout = 30.seconds
    ) {
      handleTimeout(requestId)
    }

    log.debug(s"Requesting account range ${task.rangeString} from peer ${peer.id} (request ID: $requestId)")

    // Send request via NetworkPeerManagerActor
    // Convert to MessageSerializable using the implicit class
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
    val messageSerializable: MessageSerializable = new GetAccountRangeEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    Some(requestId)
  }

  /** Handle AccountRange response
    *
    * @param response
    *   The response message
    * @return
    *   Either error message or number of accounts processed
    */
  def handleResponse(response: AccountRange): Either[String, Int] = synchronized {
    // Validate response
    requestTracker.validateAccountRange(response) match {
      case Left(error) =>
        log.warn(s"Invalid AccountRange response: $error")
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
                log.warn(s"No active task for request ID ${response.requestId}")
                return Left(s"No active task for request ID")

              case Some(task) =>
                // Process the response
                processAccountRange(task, validResponse)
            }
        }
    }
  }

  /** Process downloaded account range
    *
    * @param task
    *   The task being filled
    * @param response
    *   The validated response
    * @return
    *   Number of accounts processed
    */
  private def processAccountRange(task: AccountTask, response: AccountRange): Either[String, Int] = {
    val accountCount = response.accounts.size

    log.info(s"Processing ${accountCount} accounts for range ${task.rangeString}")

    // Store accounts
    task.accounts = response.accounts
    task.proof = response.proof

    // Verify Merkle proofs
    proofVerifier.verifyAccountRange(
      response.accounts,
      response.proof,
      task.next,
      task.last
    ) match {
      case Left(error) =>
        log.warn(s"Merkle proof verification failed: $error")
        return Left(s"Proof verification failed: $error")
      case Right(_) =>
        log.debug(s"Merkle proof verified successfully for ${accountCount} accounts")
    }

    // Identify contract accounts (those with non-empty code hash)
    identifyContractAccounts(response.accounts)

    // Store accounts to database
    storeAccounts(response.accounts) match {
      case Left(error) =>
        log.warn(s"Failed to store accounts: $error")
        return Left(s"Storage failed: $error")
      case Right(_) =>
        log.debug(s"Successfully stored ${accountCount} accounts")
    }

    // Update statistics
    accountsDownloaded += accountCount
    val accountBytes = response.accounts.map { case (hash, account) =>
      hash.size + 32 // Rough estimate: hash + account data
    }.sum
    bytesDownloaded += accountBytes

    // Check if we need continuation
    if (response.accounts.nonEmpty) {
      val lastAccount = response.accounts.last._1

      // If we didn't reach the end of the range, create continuation task
      if (lastAccount.toSeq.compare(task.last.toSeq) < 0) {
        val continuationTask = AccountTask(
          next = lastAccount,
          last = task.last,
          rootHash = stateRoot
        )
        tasks.enqueue(continuationTask)
        log.debug(s"Created continuation task from ${continuationTask.rangeString}")
      }
    }

    // Mark task as done
    task.done = true
    task.pending = false
    completedTasks += task

    log.debug(s"Completed account task ${task.rangeString} with ${accountCount} accounts")

    Right(accountCount)
  }

  /** Store accounts to MptStorage
    *
    * Inserts verified accounts into the state trie using proper MPT structure. Thread-safe storage with synchronized
    * access for concurrent task writes.
    *
    * Implementation approach:
    *   - Inserts each account into the state trie using trie.put()
    *   - The trie automatically maintains proper MPT structure and node relationships
    *   - Persists the trie after insertions
    *   - Handles empty storage and bytecode correctly
    *
    * @param accounts
    *   Accounts to store (accountHash -> account)
    * @return
    *   Either error or success
    */
  private def storeAccounts(accounts: Seq[(ByteString, Account)]): Either[String, Unit] =
    try {

      // Synchronize on this instance to protect stateTrie variable
      this.synchronized {
        if (accounts.nonEmpty) {
          // Build new trie by folding over accounts
          var currentTrie = stateTrie
          accounts.foreach { case (accountHash, account) =>
            log.debug(
              s"Storing account ${accountHash.take(4).toArray.map("%02x".format(_)).mkString} " +
                s"(balance: ${account.balance}, nonce: ${account.nonce}, " +
                s"storageRoot: ${account.storageRoot.take(4).toArray.map("%02x".format(_)).mkString}, " +
                s"codeHash: ${account.codeHash.take(4).toArray.map("%02x".format(_)).mkString})"
            )

            // Create new trie version - MPT is immutable
            currentTrie = currentTrie.put(accountHash, account)
          }

          // Update stateTrie atomically within synchronized block
          stateTrie = currentTrie

          log.info(s"Inserted ${accounts.size} accounts into state trie")
        }
      }

      // Persist after releasing this.synchronized to avoid nested locks
      mptStorage.synchronized {
        mptStorage.persist()
      }

      log.info(s"Successfully persisted ${accounts.size} accounts to storage")
      Right(())
    } catch {
      case e: Exception =>
        log.error(s"Failed to store accounts: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")
    }

  /** Handle request timeout
    *
    * @param requestId
    *   The timed out request ID
    */
  private def handleTimeout(requestId: BigInt): Unit = synchronized {
    activeTasks.remove(requestId).foreach { task =>
      log.warn(s"Account range request timeout for range ${task.rangeString}")
      task.pending = false
      // Re-queue the task for retry
      tasks.enqueue(task)
    }
  }

  /** Get sync progress (0.0 to 1.0) */
  def progress: Double = synchronized {
    val total = completedTasks.size + activeTasks.size + tasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  /** Get sync statistics */
  def statistics: SyncStatistics = synchronized {
    val elapsedMs = System.currentTimeMillis() - startTime
    SyncStatistics(
      accountsDownloaded = accountsDownloaded,
      bytesDownloaded = bytesDownloaded,
      tasksCompleted = completedTasks.size,
      tasksActive = activeTasks.size,
      tasksPending = tasks.size,
      elapsedTimeMs = elapsedMs,
      progress = progress
    )
  }

  /** Check if sync is complete */
  def isComplete: Boolean = synchronized {
    tasks.isEmpty && activeTasks.isEmpty
  }

  /** Get the current state root hash from the trie
    *
    * @return
    *   Current state root hash
    */
  def getStateRoot: ByteString = synchronized {
    ByteString(stateTrie.getRootHash)
  }

  /** Identify contract accounts (those with non-empty code hash)
    *
    * Contract accounts are identified by codeHash != Account.EmptyCodeHash. These accounts need their bytecode
    * downloaded separately.
    *
    * @param accounts
    *   Accounts to scan for contracts
    */
  private def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit = synchronized {
    val contracts = accounts.collect {
      case (accountHash, account) if account.codeHash != Account.EmptyCodeHash =>
        (accountHash, account.codeHash)
    }

    if (contracts.nonEmpty) {
      contractAccounts.appendAll(contracts)
      log.info(s"Identified ${contracts.size} contract accounts (total: ${contractAccounts.size})")
    }
  }

  /** Get all collected contract accounts for bytecode download
    *
    * Returns the list of (accountHash, codeHash) pairs for contract accounts discovered during account range sync. This
    * list should be used to queue bytecode download tasks.
    *
    * @return
    *   Sequence of (accountHash, codeHash) for contract accounts
    */
  def getContractAccounts: Seq[(ByteString, ByteString)] = synchronized {
    contractAccounts.toSeq
  }

  /** Get count of contract accounts discovered
    *
    * @return
    *   Number of contract accounts
    */
  def getContractAccountCount: Int = synchronized {
    contractAccounts.size
  }

  /** Finalize the trie and ensure all nodes including the root are persisted to storage.
    *
    * This method should be called after all account ranges have been downloaded and before state validation begins. It
    * ensures that the complete trie structure, including the root node, is flushed to storage. Note that the trie nodes
    * are already written to storage through put() operations, but this method ensures a final flush to disk.
    *
    * @return
    *   Either error message or success
    */
  def finalizeTrie(): Either[String, Unit] =
    try {
      synchronized {
        log.info("Finalizing state trie and ensuring all nodes are persisted...")
        
        // Get the current root hash for logging
        val currentRootHash = ByteString(stateTrie.getRootHash)
        log.info(s"Current state root: ${currentRootHash.take(8).toArray.map("%02x".format(_)).mkString}")
        
        // Check if we have a non-empty trie
        if (currentRootHash.length == 0 || currentRootHash == ByteString(MerklePatriciaTrie.EmptyRootHash)) {
          log.warn("State trie is empty, nothing to finalize")
        } else {
          log.info("State trie has content, proceeding with finalization")
        }
      }
      
      // Flush all pending writes to disk outside synchronized block to avoid deadlock
      // Note: The trie nodes have already been written to storage through put() operations
      // which call updateNodesInStorage(). This persist() ensures they are flushed to disk.
      mptStorage.persist()
      log.info("Flushed all trie nodes to disk")
      
      log.info("State trie finalization complete")
      Right(())
    } catch {
      case e: Exception =>
        log.error(s"Failed to finalize trie: ${e.getMessage}", e)
        Left(s"Trie finalization error: ${e.getMessage}")
    }
}

object AccountRangeDownloader {

  /** Sync statistics */
  case class SyncStatistics(
      accountsDownloaded: Long,
      bytesDownloaded: Long,
      tasksCompleted: Int,
      tasksActive: Int,
      tasksPending: Int,
      elapsedTimeMs: Long,
      progress: Double
  ) {
    def throughputAccountsPerSec: Double =
      if (elapsedTimeMs > 0) accountsDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    def throughputBytesPerSec: Double =
      if (elapsedTimeMs > 0) bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    override def toString: String =
      f"Progress: ${progress * 100}%.1f%%, Accounts: $accountsDownloaded, " +
        f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
        f"Speed: ${throughputAccountsPerSec}%.1f acc/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
  }
}
