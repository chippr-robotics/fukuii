package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.Logger

class ByteCodeDownloader(
    evmCodeStorage: EvmCodeStorage,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    batchSize: Int = ByteCodeTask.DEFAULT_BATCH_SIZE
)(implicit scheduler: Scheduler)
    extends Logger {

  import ByteCodeDownloader._

  private val tasks = mutable.Queue[ByteCodeTask]()

  private val activeTasks = mutable.Map[BigInt, ByteCodeTask]()

  private val completedTasks = mutable.ArrayBuffer[ByteCodeTask]()

  /** Statistics */
  private var bytecodesDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  /** Maximum response size in bytes (2 MB - bytecode can be large) */
  private val maxResponseSize: BigInt = 2 * 1024 * 1024

  /** Add contract accounts to download queue
    *
    * Filters for accounts with non-empty code hash and creates batched tasks.
    *
    * @param contractAccounts
    *   Sequence of (accountHash, codeHash) for contract accounts
    */
  def queueContracts(contractAccounts: Seq[(ByteString, ByteString)]): Unit = synchronized {
    if (contractAccounts.isEmpty) {
      log.debug("No contract accounts to queue for bytecode download")
      return
    }

    val newTasks = ByteCodeTask.createBytecodeTasksFromAccounts(contractAccounts, batchSize)
    tasks.enqueueAll(newTasks)

    log.info(s"Queued ${newTasks.size} bytecode tasks for ${contractAccounts.size} contract accounts")
  }

  /** Request next batch of bytecodes from available peer
    *
    * @param peer
    *   Peer to request from
    * @return
    *   Request ID if request was sent, None otherwise
    */
  def requestNextBatch(peer: Peer): Option[BigInt] = synchronized {
    if (tasks.isEmpty) {
      log.debug("No more bytecode tasks available")
      return None
    }

    val task = tasks.dequeue()
    val requestId = requestTracker.generateRequestId()

    val request = GetByteCodes(
      requestId = requestId,
      hashes = task.codeHashes,
      responseBytes = maxResponseSize
    )

    task.pending = true
    activeTasks.put(requestId, task)

    // Track request with timeout
    requestTracker.trackRequest(
      requestId,
      peer,
      SNAPRequestTracker.RequestType.GetByteCodes,
      timeout = 30.seconds
    ) {
      handleTimeout(requestId)
    }

    log.debug(
      s"Requesting ${task.codeHashes.size} bytecodes ${task.taskString} from peer ${peer.id} (request ID: $requestId)"
    )

    // Send request via NetworkPeerManager
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
    val messageSerializable: MessageSerializable = new GetByteCodesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    Some(requestId)
  }

  /** Handle ByteCodes response
    *
    * @param response
    *   The response message
    * @return
    *   Either error message or number of bytecodes processed
    */
  def handleResponse(response: ByteCodes): Either[String, Int] = synchronized {
    // Validate response
    requestTracker.validateByteCodes(response) match {
      case Left(error) =>
        log.warn(s"Invalid ByteCodes response: $error")
        // Find and re-queue the task if it exists
        activeTasks.remove(response.requestId).foreach { task =>
          task.pending = false
          tasks.enqueue(task)
          log.debug(s"Re-queued task ${task.taskString} after validation failure")
        }
        return Left(error)

      case Right(validResponse) =>
        // Complete the request
        requestTracker.completeRequest(response.requestId) match {
          case None =>
            log.warn(s"Received response for unknown request ID ${response.requestId}")
            // Find and re-queue the task if it exists
            activeTasks.remove(response.requestId).foreach { task =>
              task.pending = false
              tasks.enqueue(task)
              log.debug(s"Re-queued task ${task.taskString} after unknown request ID")
            }
            return Left(s"Unknown request ID: ${response.requestId}")

          case Some(pendingReq) =>
            activeTasks.remove(response.requestId) match {
              case None =>
                log.warn(s"No active task for request ID ${response.requestId}")
                return Left(s"No active task for request ID")

              case Some(task) =>
                // Process the response
                processByteCodes(task, validResponse)
            }
        }
    }
  }

  /** Process downloaded bytecodes
    *
    * @param task
    *   The task being filled
    * @param response
    *   The validated response
    * @return
    *   Number of bytecodes processed
    */
  private def processByteCodes(task: ByteCodeTask, response: ByteCodes): Either[String, Int] = {
    val bytecodeCount = response.codes.size

    log.info(s"Processing ${bytecodeCount} bytecodes for task ${task.taskString}")

    // Store bytecodes
    task.bytecodes = response.codes

    // Verify bytecode hashes match requested hashes
    verifyBytecodes(task.codeHashes, response.codes) match {
      case Left(error) =>
        log.warn(s"Bytecode verification failed: $error")
        task.pending = false
        tasks.enqueue(task)
        log.debug(s"Re-queued task ${task.taskString} after verification failure")
        return Left(s"Verification failed: $error")
      case Right(_) =>
        log.debug(s"Bytecode hashes verified successfully for ${bytecodeCount} codes")
    }

    // Store bytecodes to database
    storeBytecodes(response.codes) match {
      case Left(error) =>
        log.warn(s"Failed to store bytecodes: $error")
        task.pending = false
        tasks.enqueue(task)
        log.debug(s"Re-queued task ${task.taskString} after storage failure")
        return Left(s"Storage failed: $error")
      case Right(_) =>
        log.debug(s"Successfully stored ${bytecodeCount} bytecodes")
    }

    // Only mark task as done if all requested bytecodes were received
    if (response.codes.size == task.codeHashes.size) {
      // Update statistics
      bytecodesDownloaded += bytecodeCount
      val totalBytes = response.codes.map(_.size).sum
      bytesDownloaded += totalBytes

      task.done = true
      task.pending = false
      completedTasks += task

      log.debug(s"Completed bytecode task ${task.taskString} with ${bytecodeCount} codes")

      Right(bytecodeCount)
    } else {
      // Partial response - identify missing hashes and requeue them
      val receivedCount = response.codes.size
      val missingHashes = task.codeHashes.drop(receivedCount)
      log.warn(
        s"Received only $receivedCount/${task.codeHashes.size} bytecodes for task ${task.taskString}. Requeuing ${missingHashes.size} missing hashes."
      )

      // Update statistics for what we did receive
      bytecodesDownloaded += bytecodeCount
      val totalBytes = response.codes.map(_.size).sum
      bytesDownloaded += totalBytes

      // Create a new task for the missing hashes
      val missingTask = ByteCodeTask(missingHashes)
      tasks.enqueue(missingTask)

      // Mark original task as done since we processed what we received
      task.done = true
      task.pending = false
      completedTasks += task

      Right(bytecodeCount)
    }
  }

  /** Verify that downloaded bytecodes match their expected hashes
    *
    * @param expectedHashes
    *   Expected code hashes
    * @param bytecodes
    *   Downloaded bytecodes
    * @return
    *   Either error or success
    */
  private def verifyBytecodes(
      expectedHashes: Seq[ByteString],
      bytecodes: Seq[ByteString]
  ): Either[String, Unit] = {
    // For partial responses, we only verify what we received
    // The caller will handle creating continuation tasks for missing bytecodes
    if (bytecodes.isEmpty) {
      return Left(s"Empty response: received 0 bytecodes, expected ${expectedHashes.size}")
    }

    if (bytecodes.size > expectedHashes.size) {
      return Left(s"Received ${bytecodes.size} bytecodes but expected at most ${expectedHashes.size}")
    }

    // Verify each bytecode hash
    val mismatches = bytecodes.zipWithIndex.collect {
      case (code, idx) if idx < expectedHashes.size =>
        val expectedHash = expectedHashes(idx)
        val actualHash = kec256(code)
        if (actualHash != expectedHash) {
          Some(
            s"Index $idx: expected ${expectedHash.take(4).toArray.map("%02x".format(_)).mkString}, " +
              s"got ${actualHash.take(4).toArray.map("%02x".format(_)).mkString}"
          )
        } else {
          None
        }
    }.flatten

    if (mismatches.nonEmpty) {
      Left(s"Hash mismatches: ${mismatches.mkString(", ")}")
    } else {
      Right(())
    }
  }

  /** Store bytecodes to EvmCodeStorage
    *
    * Stores bytecodes in a single batch update for better performance. The commit is called once for all bytecodes in
    * the batch.
    *
    * @param bytecodes
    *   Bytecodes to store
    * @return
    *   Either error or success
    */
  private def storeBytecodes(bytecodes: Seq[ByteString]): Either[String, Unit] =
    try {
      evmCodeStorage.synchronized {
        // Build up batch updates
        val updates = bytecodes.foldLeft(evmCodeStorage.emptyBatchUpdate) { (batchUpdate, code) =>
          val codeHash = kec256(code)
          log.debug(s"Storing bytecode ${codeHash.take(4).toArray.map("%02x".format(_)).mkString} (${code.size} bytes)")

          // Add to batch update
          batchUpdate.and(evmCodeStorage.put(codeHash, code))
        }

        // Commit all updates in one transaction
        updates.commit()
      }

      log.info(s"Successfully persisted ${bytecodes.size} bytecodes to storage")
      Right(())
    } catch {
      case e: Exception =>
        log.error(s"Failed to store bytecodes: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")
    }

  /** Handle request timeout
    *
    * @param requestId
    *   The timed out request ID
    */
  private def handleTimeout(requestId: BigInt): Unit = synchronized {
    activeTasks.remove(requestId).foreach { task =>
      log.warn(s"Bytecode request timeout for task ${task.taskString}")
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
      bytecodesDownloaded = bytecodesDownloaded,
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

object ByteCodeDownloader {

  /** Sync statistics */
  case class SyncStatistics(
      bytecodesDownloaded: Long,
      bytesDownloaded: Long,
      tasksCompleted: Int,
      tasksActive: Int,
      tasksPending: Int,
      elapsedTimeMs: Long,
      progress: Double
  ) {
    def throughputBytecodesPerSec: Double =
      if (elapsedTimeMs > 0) bytecodesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    def throughputBytesPerSec: Double =
      if (elapsedTimeMs > 0) bytesDownloaded.toDouble / (elapsedTimeMs / 1000.0)
      else 0.0

    override def toString: String =
      f"Progress: ${progress * 100}%.1f%%, Bytecodes: $bytecodesDownloaded, " +
        f"Bytes: ${bytesDownloaded / 1024}KB, Tasks: $tasksCompleted done, $tasksActive active, $tasksPending pending, " +
        f"Speed: ${throughputBytecodesPerSec}%.1f codes/s, ${throughputBytesPerSec / 1024}%.1f KB/s"
  }
}
