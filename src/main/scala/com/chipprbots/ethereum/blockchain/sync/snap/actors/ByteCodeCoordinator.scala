package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes

/** ByteCodeCoordinator manages bytecode download workers.
  *
  * Now contains ALL business logic previously in ByteCodeDownloader.
  * This is the sole implementation - no synchronized fallback.
  *
  * Responsibilities:
  * - Maintain queue of pending bytecode download tasks
  * - Distribute tasks to worker actors
  * - Verify bytecode hashes match requested hashes
  * - Store bytecodes to EvmCodeStorage
  * - Report progress to SNAPSyncController
  * - Handle worker failures with supervision
  *
  * @param evmCodeStorage
  *   Storage for contract bytecodes
  * @param networkPeerManager
  *   Actor for network communication
  * @param requestTracker
  *   Tracker for requests/responses
  * @param batchSize
  *   Number of bytecodes per batch
  * @param snapSyncController
  *   Parent controller to notify of completion
  */
class ByteCodeCoordinator(
    evmCodeStorage: EvmCodeStorage,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    batchSize: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management
  private val pendingTasks = mutable.Queue[ByteCodeTask]()
  private val activeTasks = mutable.Map[BigInt, (ByteCodeTask, ActorRef)]() // requestId -> (task, worker)
  private val completedTasks = mutable.ArrayBuffer[ByteCodeTask]()

  // Worker pool
  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 8

  // Statistics
  private var bytecodesDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()

  // Maximum response size in bytes (2 MB - bytecode can be large)
  private val maxResponseSize: BigInt = 2 * 1024 * 1024

  override def preStart(): Unit = {
    log.info("ByteCodeCoordinator starting")
  }

  override def postStop(): Unit = {
    log.info(s"ByteCodeCoordinator stopped. Downloaded $bytecodesDownloaded bytecodes")
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("ByteCode worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartByteCodeSync(contractAccounts) =>
      log.info(s"Starting bytecode sync for ${contractAccounts.size} contracts")
      val newTasks = ByteCodeTask.createBytecodeTasksFromAccounts(contractAccounts, batchSize)
      pendingTasks.enqueueAll(newTasks)
      log.info(s"Queued ${newTasks.size} bytecode tasks")

    case PeerAvailable(peer) =>
      // Dispatch tasks to available peer
      if (pendingTasks.nonEmpty && workers.size < maxWorkers) {
        val worker = createWorker()
        assignTaskToWorker(worker, peer)
      } else if (pendingTasks.nonEmpty) {
        // Find an idle worker
        workers.headOption.foreach { worker =>
          assignTaskToWorker(worker, peer)
        }
      }

    case ByteCodesResponseMsg(response) =>
      handleByteCodesResponse(response)

    case ByteCodeTaskComplete(requestId, result) =>
      activeTasks.remove(requestId)
      result match {
        case Right(count) =>
          bytecodesDownloaded += count
          log.info(s"Bytecode task completed: $count codes")
          checkCompletion()
        case Left(error) =>
          log.warning(s"Bytecode task failed: $error")
          checkCompletion()
      }

    case ByteCodeTaskFailed(requestId, error) =>
      // Re-queue the task
      activeTasks.remove(requestId).foreach { case (task, _) =>
        log.warning(s"Re-queuing bytecode task after failure: $error")
        task.pending = false
        pendingTasks.enqueue(task)
      }

    case ByteCodeGetProgress =>
      val total = completedTasks.size + activeTasks.size + pendingTasks.size
      val progress = if (total == 0) 1.0 else completedTasks.size.toDouble / total
      sender() ! ByteCodeProgress(progress, bytecodesDownloaded, bytesDownloaded)
  }

  private def assignTaskToWorker(worker: ActorRef, peer: Peer): Unit = {
    if (pendingTasks.isEmpty) return

    val task = pendingTasks.dequeue()
    val requestId = requestTracker.generateRequestId()

    task.pending = true
    activeTasks.put(requestId, (task, worker))

    log.debug(s"Assigning bytecode task (${task.codeHashes.size} hashes) to worker for peer ${peer.id}")
    worker ! ByteCodeWorkerFetchTask(task, peer, requestId, maxResponseSize)
  }

  private def handleByteCodesResponse(response: ByteCodes): Unit = {
    activeTasks.get(response.requestId) match {
      case None =>
        log.debug(s"Received ByteCodes response for unknown or completed request ${response.requestId}")

      case Some((task, worker)) =>
        log.debug(s"Processing ByteCodes response: ${response.codes.size} codes for request ${response.requestId}")

        // Verify bytecode hashes
        verifyBytecodes(task.codeHashes, response.codes) match {
          case Left(error) =>
            log.warning(s"Bytecode verification failed: $error")
            activeTasks.remove(response.requestId)
            task.pending = false
            pendingTasks.enqueue(task)
            checkCompletion()

          case Right(_) =>
            // Store bytecodes
            storeBytecodes(response.codes) match {
              case Left(error) =>
                log.warning(s"Failed to store bytecodes: $error")
                activeTasks.remove(response.requestId)
                task.pending = false
                pendingTasks.enqueue(task)
                checkCompletion()

              case Right(_) =>
                // Success
                val bytecodeCount = response.codes.size
                bytecodesDownloaded += bytecodeCount
                val totalBytes = response.codes.map(_.size).sum
                bytesDownloaded += totalBytes

                task.done = true
                task.pending = false
                task.bytecodes = response.codes
                completedTasks += task
                activeTasks.remove(response.requestId)

                log.info(s"Successfully processed $bytecodeCount bytecodes")
                checkCompletion()
            }
        }
    }
  }

  private def verifyBytecodes(
      expectedHashes: Seq[ByteString],
      bytecodes: Seq[ByteString]
  ): Either[String, Unit] = {
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

  private def storeBytecodes(bytecodes: Seq[ByteString]): Either[String, Unit] =
    try {
      // Build up batch updates
      val updates = bytecodes.foldLeft(evmCodeStorage.emptyBatchUpdate) { (batchUpdate, code) =>
        val codeHash = kec256(code)
        batchUpdate.and(evmCodeStorage.put(codeHash, code))
      }

      // Commit all updates in one transaction
      updates.commit()

      log.info(s"Successfully persisted ${bytecodes.size} bytecodes to storage")
      Right(())
    } catch {
      case e: Exception =>
        log.error(s"Failed to store bytecodes: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")
    }

  private def checkCompletion(): Unit = {
    if (pendingTasks.isEmpty && activeTasks.isEmpty) {
      log.info("Bytecode sync complete!")
      snapSyncController ! SNAPSyncController.ByteCodeSyncComplete
    }
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      ByteCodeWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker
      )
    )
    workers += worker
    log.debug(s"Created bytecode worker, total: ${workers.size}")
    worker
  }
}

object ByteCodeCoordinator {
  def props(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new ByteCodeCoordinator(
        evmCodeStorage,
        networkPeerManager,
        requestTracker,
        batchSize,
        snapSyncController
      )
    )
}
