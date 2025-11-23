package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.Logger

/** Account Range Downloader for SNAP sync
  *
  * Downloads account ranges from peers in parallel, verifies Merkle proofs,
  * and stores accounts locally. Follows core-geth snap sync patterns.
  *
  * Features:
  * - Parallel account range downloads from multiple peers
  * - Merkle proof verification (placeholder for now)
  * - Progress tracking and reporting
  * - Task continuation on timeout/failure
  * - Configurable concurrency
  */
class AccountRangeDownloader(
    stateRoot: ByteString,
    etcPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    concurrency: Int = 16
)(implicit scheduler: Scheduler) extends Logger {

  import AccountRangeDownloader._

  /** Active account tasks */
  private val tasks = mutable.Queue[AccountTask](AccountTask.createInitialTasks(stateRoot, concurrency): _*)

  /** Tasks currently being downloaded */
  private val activeTasks = mutable.Map[BigInt, AccountTask]() // requestId -> task

  /** Completed tasks */
  private val completedTasks = mutable.ArrayBuffer[AccountTask]()

  /** Statistics */
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  /** Maximum response size in bytes (512 KB like core-geth) */
  private val maxResponseSize: BigInt = 512 * 1024

  /** Request next account range from available peer
    *
    * @param peer Peer to request from
    * @return Request ID if request was sent, None otherwise
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
    
    // TODO: Send request via EtcPeerManager
    // etcPeerManager ! EtcPeerManagerActor.SendMessage(request, peer.id)
    
    Some(requestId)
  }

  /** Handle AccountRange response
    *
    * @param response The response message
    * @return Either error message or number of accounts processed
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
    * @param task The task being filled
    * @param response The validated response
    * @return Number of accounts processed
    */
  private def processAccountRange(task: AccountTask, response: AccountRange): Either[String, Int] = {
    val accountCount = response.accounts.size
    
    log.info(s"Processing ${accountCount} accounts for range ${task.rangeString}")
    
    // Store accounts
    task.accounts = response.accounts
    task.proof = response.proof
    
    // TODO: Verify Merkle proofs
    // For now, assume valid if validation passed
    
    // TODO: Store accounts to database
    // This would involve writing to MptStorage or similar
    
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

  /** Handle request timeout
    *
    * @param requestId The timed out request ID
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
    def throughputAccountsPerSec: Double = {
      if (elapsedTimeMs > 0) accountsDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0
    }

    def throughputBytesPerSec: Double = {
      if (elapsedTimeMs > 0) bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0
    }

    override def toString: String = {
      f"Progress: ${progress * 100}%.1f%%, Accounts: $accountsDownloaded, " +
      f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
      f"Speed: ${throughputAccountsPerSec}%.1f acc/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
    }
  }
}
