package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

import scala.collection.mutable
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.{GetTrieNodes, TrieNodes}
import com.chipprbots.ethereum.mpt.LeafNode

/** TrieNodeHealingCoordinator manages trie node healing workers and orchestrates the healing phase.
  *
  * State healing is the final phase of SNAP sync that detects and downloads missing trie nodes that were not included
  * in account/storage range downloads. This coordinator manages the healing process by detecting missing nodes,
  * batching requests, validating and storing received nodes, and iteratively healing until complete.
  *
  * @param stateRoot
  *   State root hash
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param mptStorage
  *   MPT storage
  * @param batchSize
  *   Batch size for healing requests
  * @param snapSyncController
  *   Parent controller
  */
class TrieNodeHealingCoordinator(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    batchSize: Int,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management
  private var pendingTasks: Seq[HealingTask] = Seq.empty
  private var activeTasks: Seq[HealingTask] = Seq.empty
  private var completedTasks: Seq[HealingTask] = Seq.empty

  // Worker management
  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 8

  // Statistics
  private var totalNodesHealed: Int = 0
  private var totalBytesReceived: Long = 0
  private val startTime = System.currentTimeMillis()

  // Configuration
  private val MAX_RESPONSE_SIZE = BigInt(1024 * 1024)

  override def preStart(): Unit = {
    log.info("TrieNodeHealingCoordinator starting")
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) {
      case _: Exception =>
        log.warning("Healing worker failed, restarting")
        Restart
    }

  override def receive: Receive = {
    case StartTrieNodeHealing(root) =>
      log.info(s"Starting trie node healing for state root ${Hex.toHexString(root.take(8).toArray)}")

    case QueueMissingNodes(nodes) =>
      log.info(s"Queuing ${nodes.size} missing nodes for healing")
      queueNodes(nodes)

    case HealingPeerAvailable(peer) =>
      if (!isComplete && workers.size < maxWorkers) {
        val worker = createWorker()
        worker ! FetchTrieNodes(null, peer)
      } else if (!isComplete && pendingTasks.nonEmpty) {
        requestNextBatch(peer)
      }

    case TrieNodesResponseMsg(response) =>
      handleResponse(response)

    case HealingTaskComplete(requestId, result) =>
      result match {
        case Right(count) =>
          totalNodesHealed += count
          log.info(s"Healing task completed: $count nodes")
          self ! HealingCheckCompletion
        case Left(error) =>
          log.warning(s"Healing task failed: $error")
      }

    case HealingCheckCompletion =>
      if (isComplete) {
        log.info("Trie node healing complete!")
        snapSyncController ! SNAPSyncController.StateHealingComplete
      }

    case HealingGetProgress =>
      val stats = HealingStatistics(
        totalNodes = totalNodesHealed,
        totalBytes = totalBytesReceived,
        pendingTasks = pendingTasks.size,
        activeTasks = activeTasks.size,
        completedTasks = completedTasks.size,
        nodesPerSecond = calculateNodesPerSecond(),
        kilobytesPerSecond = calculateKilobytesPerSecond(),
        progress = calculateProgress()
      )
      sender() ! stats
  }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      TrieNodeHealingWorker.props(
        coordinator = self,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker
      )
    )
    workers += worker
    log.debug(s"Created healing worker, total: ${workers.size}")
    worker
  }

  private def queueNode(nodeHash: ByteString): Unit = {
    val task = HealingTask(
      path = Seq.empty,
      hash = nodeHash,
      rootHash = stateRoot,
      pending = true,
      done = false,
      nodeData = None
    )
    pendingTasks = pendingTasks :+ task
    log.debug(s"Queued node for healing: hash=${Hex.toHexString(nodeHash.take(4).toArray)}")
  }

  private def queueNodes(nodeHashes: Seq[ByteString]): Unit = {
    val tasks = nodeHashes.map { nodeHash =>
      HealingTask(
        path = Seq.empty,
        hash = nodeHash,
        rootHash = stateRoot,
        pending = true,
        done = false,
        nodeData = None
      )
    }
    pendingTasks = pendingTasks ++ tasks
    log.info(s"Queued ${nodeHashes.size} nodes for healing. Total pending: ${pendingTasks.size}")
  }

  private def requestNextBatch(peer: Peer): Option[BigInt] = {
    if (pendingTasks.isEmpty) {
      log.debug("No pending healing tasks")
      return None
    }

    val batch = pendingTasks.take(batchSize)
    pendingTasks = pendingTasks.drop(batchSize)

    batch.foreach(_.pending = false)
    activeTasks = activeTasks ++ batch

    val requestId = requestTracker.generateRequestId()
    val paths = batch.map(_.path)

    val request = GetTrieNodes(
      requestId = requestId,
      rootHash = stateRoot,
      paths = paths,
      responseBytes = MAX_RESPONSE_SIZE
    )

    requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes) {
      handleTimeout(requestId, batch)
    }

    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
    val messageSerializable: com.chipprbots.ethereum.network.p2p.MessageSerializable = new GetTrieNodesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    log.debug(
      s"Requested ${batch.size} trie nodes from peer $peer " +
        s"(reqId=$requestId, pending=${pendingTasks.size})"
    )

    Some(requestId)
  }

  private def handleResponse(response: TrieNodes): Unit = {
    val requestId = response.requestId
    val nodes = response.nodes

    log.debug(s"Received TrieNodes response: reqId=$requestId, nodes=${nodes.size}")

    val tasksForRequest = activeTasks.filter(task =>
      requestTracker.getPendingRequest(requestId).exists(_.requestType == SNAPRequestTracker.RequestType.GetTrieNodes)
    )

    if (tasksForRequest.isEmpty) {
      log.warning(s"No active healing tasks found for request $requestId")
      return
    }

    var healedCount = 0
    for ((nodeData, task) <- nodes.zip(tasksForRequest))
      validateAndStoreNode(nodeData, task) match {
        case Right(_) =>
          task.nodeData = Some(nodeData)
          task.done = true
          healedCount += 1
          totalNodesHealed += 1
          totalBytesReceived += nodeData.length
          log.debug(s"Healed node: ${task.toShortString}")

        case Left(error) =>
          log.warning(s"Failed to heal node: ${task.toShortString} - $error")
          task.pending = true
          pendingTasks = pendingTasks :+ task
      }

    val (completed, stillActive) = tasksForRequest.partition(_.done)
    completedTasks = completedTasks ++ completed
    activeTasks = activeTasks.filterNot(completed.contains)

    requestTracker.completeRequest(requestId)

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeTasks.size})"
    )
  }

  private def validateAndStoreNode(nodeData: ByteString, task: HealingTask): Either[String, Unit] =
    try {
      val nodeHash = ByteString(org.bouncycastle.jcajce.provider.digest.Keccak.Digest256().digest(nodeData.toArray))
      if (nodeHash != task.hash) {
        return Left(s"Node hash mismatch: expected ${Hex.toHexString(task.hash.take(4).toArray)}, got ${Hex.toHexString(nodeHash.take(4).toArray)}")
      }

      storeTrieNode(nodeData, nodeHash)

      log.debug(s"Stored healed trie node: hash=${Hex.toHexString(nodeHash.take(4).toArray)}, size=${nodeData.length} bytes")
      Right(())
    } catch {
      case e: Exception =>
        Left(s"Failed to store healed node: ${e.getMessage}")
    }

  private def storeTrieNode(nodeData: ByteString, nodeHash: ByteString): Unit = {
    val leafNode = LeafNode(
      key = nodeHash,
      value = nodeData,
      cachedHash = Some(nodeHash.toArray),
      cachedRlpEncoded = Some(nodeData.toArray),
      parsedRlp = None
    )

    mptStorage.updateNodesInStorage(
      newRoot = Some(leafNode),
      toRemove = Seq.empty
    )

    mptStorage.persist()

    log.debug(s"Persisted healed trie node: hash=${Hex.toHexString(nodeHash.take(4).toArray)}")
  }

  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingTask]): Unit = {
    log.warning(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}")

    tasks.foreach { task =>
      task.pending = true
      task.done = false
    }
    pendingTasks = pendingTasks ++ tasks
    activeTasks = activeTasks.filterNot(tasks.contains)

    log.info(s"Re-queued ${tasks.size} timed-out healing tasks (pending: ${pendingTasks.size})")
  }

  private def calculateProgress(): Double = {
    val total = pendingTasks.size + activeTasks.size + completedTasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  private def calculateNodesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) totalNodesHealed / elapsedSec else 0.0
  }

  private def calculateKilobytesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) (totalBytesReceived / 1024.0) / elapsedSec else 0.0
  }

  private def isComplete: Boolean = {
    pendingTasks.isEmpty && activeTasks.isEmpty
  }
}

object TrieNodeHealingCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef
  ): Props =
    Props(
      new TrieNodeHealingCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        batchSize,
        snapSyncController
      )
    )
}

case class HealingStatistics(
    totalNodes: Int,
    totalBytes: Long,
    pendingTasks: Int,
    activeTasks: Int,
    completedTasks: Int,
    nodesPerSecond: Double,
    kilobytesPerSecond: Double,
    progress: Double
)
