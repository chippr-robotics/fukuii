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

/** TrieNodeHealingCoordinator manages the healing phase of SNAP sync.
  *
  * State healing downloads missing trie nodes (intermediate branch/extension nodes that snap sync
  * skips) by requesting them from peers via GetTrieNodes. Nodes are identified by their trie path
  * (HP-encoded) and stored directly by hash in the node storage.
  *
  * The coordinator receives missing node descriptions from SNAPSyncController (which discovers them
  * via trie walks) and dispatches GetTrieNodes requests to available peers.
  */
class TrieNodeHealingCoordinator(
    stateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    batchSize: Int,
    snapSyncController: ActorRef,
    concurrency: Int
) extends Actor
    with ActorLogging {

  import Messages._

  // Task management — each task has a pathset (for GetTrieNodes) and a hash (for verification)
  private case class HealingEntry(pathset: Seq[ByteString], hash: ByteString)
  private var pendingTasks: Seq[HealingEntry] = Seq.empty
  private var completedTaskCount: Int = 0

  // Active request tracking: maps requestId -> (tasks, peer, requestedBytes)
  private case class ActiveRequest(tasks: Seq[HealingEntry], peer: Peer, requestedBytes: BigInt)
  private val activeRequests = mutable.Map[BigInt, ActiveRequest]()

  // Worker management (unused for direct dispatch but kept for concurrency tracking)
  private val maxConcurrentRequests = concurrency

  // Statistics
  private var totalNodesHealed: Int = 0
  private var totalBytesReceived: Long = 0
  private val startTime = System.currentTimeMillis()

  // Track last known available peers for re-dispatch after failures
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Per-peer adaptive byte budgeting
  private val minResponseBytes: BigInt = 50 * 1024
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024
  private val initialResponseBytes: BigInt = 512 * 1024
  private val increaseFactor: Double = 1.25
  private val decreaseFactor: Double = 0.5

  private val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  private def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget
      .getOrElseUpdate(peer.id.value, initialResponseBytes)
      .max(minResponseBytes)
      .min(maxResponseBytes)

  private def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    if (requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes) {
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))
    }

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = {
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing healing responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )
  }

  // Peer cooldown
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
  }

  // Batched raw node storage: accumulate nodes and persist periodically
  private var rawNodeBuffer = mutable.ArrayBuffer[(ByteString, Array[Byte])]()
  private val rawFlushThreshold = 1000

  private def flushRawNodes(): Unit =
    if (rawNodeBuffer.nonEmpty) {
      mptStorage.storeRawNodes(rawNodeBuffer.toSeq)
      mptStorage.persist()
      val count = rawNodeBuffer.size
      rawNodeBuffer.clear()
      log.info(s"Flushed $count healed nodes to disk (total: $totalNodesHealed)")
    }

  override def preStart(): Unit =
    log.info(s"TrieNodeHealingCoordinator starting (concurrency=$concurrency)")

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
      // Immediately dispatch to any known available peers
      tryRedispatchPendingTasks()

    case HealingPeerAvailable(peer) =>
      knownAvailablePeers += peer
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Peer ${peer.id.value} is cooling down, skipping dispatch")
      } else if (pendingTasks.nonEmpty && activeRequests.size < maxConcurrentRequests) {
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
        flushRawNodes()
        log.info(s"Healing round complete: $totalNodesHealed total nodes healed. Notifying controller.")
        snapSyncController ! SNAPSyncController.StateHealingComplete
      }

    case HealingGetProgress =>
      val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
      val stats = HealingStatistics(
        totalNodes = totalNodesHealed,
        totalBytes = totalBytesReceived,
        pendingTasks = pendingTasks.size,
        activeTasks = activeTaskCount,
        completedTasks = completedTaskCount,
        nodesPerSecond = calculateNodesPerSecond(),
        kilobytesPerSecond = calculateKilobytesPerSecond(),
        progress = calculateProgress()
      )
      sender() ! stats
  }

  private def queueNodes(pathsAndHashes: Seq[(Seq[ByteString], ByteString)]): Unit = {
    val entries = pathsAndHashes.map { case (pathset, hash) =>
      HealingEntry(pathset = pathset, hash = hash)
    }
    pendingTasks = pendingTasks ++ entries
    log.info(s"Queued ${entries.size} nodes for healing. Total pending: ${pendingTasks.size}")
  }

  private def requestNextBatch(peer: Peer): Option[BigInt] = {
    if (pendingTasks.isEmpty) {
      log.debug("No pending healing tasks")
      return None
    }

    val batch = pendingTasks.take(batchSize)
    pendingTasks = pendingTasks.drop(batchSize)

    val requestId = requestTracker.generateRequestId()
    val responseBytes = responseBytesTargetFor(peer)

    // Build the paths list for GetTrieNodes — each entry's pathset is a Seq[ByteString]
    val paths = batch.map(_.pathset)

    val request = GetTrieNodes(
      requestId = requestId,
      rootHash = stateRoot,
      paths = paths,
      responseBytes = responseBytes
    )

    activeRequests(requestId) = ActiveRequest(batch, peer, responseBytes)

    requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes) {
      handleTimeout(requestId, batch, peer)
    }

    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
    val messageSerializable: com.chipprbots.ethereum.network.p2p.MessageSerializable = new GetTrieNodesEnc(request)
    networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    log.debug(
      s"Requested ${batch.size} trie nodes from peer ${peer.id.value} " +
        s"(reqId=$requestId, responseBytes=$responseBytes, pending=${pendingTasks.size})"
    )

    Some(requestId)
  }

  private def handleResponse(response: TrieNodes): Unit = {
    val requestId = response.requestId
    val nodes = response.nodes

    log.debug(s"Received TrieNodes response: reqId=$requestId, nodes=${nodes.size}")

    val activeReq = activeRequests.get(requestId) match {
      case Some(req) => req
      case None =>
        log.warning(s"No active healing request found for requestId=$requestId")
        return
    }

    val tasksForRequest = activeReq.tasks
    val peer = activeReq.peer
    val requestedBytes = activeReq.requestedBytes

    var healedCount = 0
    var receivedBytes: Long = 0

    // Match response nodes to request tasks positionally
    for ((nodeData, task) <- nodes.zip(tasksForRequest)) {
      val nodeHash = ByteString(org.bouncycastle.jcajce.provider.digest.Keccak.Digest256().digest(nodeData.toArray))
      if (nodeHash == task.hash) {
        // Valid node — store directly by hash
        rawNodeBuffer += ((nodeHash, nodeData.toArray))
        healedCount += 1
        totalNodesHealed += 1
        receivedBytes += nodeData.length
        totalBytesReceived += nodeData.length
      } else {
        log.warning(
          s"Node hash mismatch: expected ${Hex.toHexString(task.hash.take(4).toArray)}, " +
            s"got ${Hex.toHexString(nodeHash.take(4).toArray)}"
        )
        // Re-queue this task for retry
        pendingTasks = pendingTasks :+ task
      }
    }

    // Re-queue unmatched tasks (server returned fewer nodes than requested)
    val unmatchedTasks = tasksForRequest.drop(nodes.size)
    unmatchedTasks.foreach { task =>
      pendingTasks = pendingTasks :+ task
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId)

    // Adaptive byte budget
    if (healedCount > 0) {
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(receivedBytes))
    } else {
      adjustResponseBytesOnFailure(peer, "empty healing response")
      recordPeerCooldown(peer, "empty healing response")
    }

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes from peer ${peer.id.value} " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeRequests.size} reqs, " +
        s"responseBytes=${responseBytesTargetFor(peer)})"
    )

    if (healedCount > 0) {
      snapSyncController ! SNAPSyncController.ProgressNodesHealed(healedCount.toLong)

      // Periodic flush of raw node buffer
      if (rawNodeBuffer.size >= rawFlushThreshold) {
        flushRawNodes()
      }
    }

    // Dispatch more work to this peer if available
    if (pendingTasks.nonEmpty && !isPeerCoolingDown(peer) && activeRequests.size < maxConcurrentRequests) {
      requestNextBatch(peer)
    }

    self ! HealingCheckCompletion
  }

  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingEntry], peer: Peer): Unit = {
    log.warning(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}, peer=${peer.id.value}")

    pendingTasks = pendingTasks ++ tasks
    activeRequests.remove(requestId)

    recordPeerCooldown(peer, "request timeout")
    adjustResponseBytesOnFailure(peer, "request timeout")

    log.info(s"Re-queued ${tasks.size} timed-out healing tasks (pending: ${pendingTasks.size})")
    tryRedispatchPendingTasks()
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    val eligiblePeers = knownAvailablePeers.toList.filterNot(isPeerCoolingDown)
    if (eligiblePeers.isEmpty) return

    for (peer <- eligiblePeers if pendingTasks.nonEmpty && activeRequests.size < maxConcurrentRequests) {
      requestNextBatch(peer)
    }
  }

  private def calculateProgress(): Double = {
    val activeTaskCount = activeRequests.values.map(_.tasks.size).sum
    val total = pendingTasks.size + activeTaskCount + completedTaskCount
    if (total == 0) 1.0
    else completedTaskCount.toDouble / total
  }

  private def calculateNodesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) totalNodesHealed / elapsedSec else 0.0
  }

  private def calculateKilobytesPerSecond(): Double = {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    if (elapsedSec > 0) (totalBytesReceived / 1024.0) / elapsedSec else 0.0
  }

  private def isComplete: Boolean =
    pendingTasks.isEmpty && activeRequests.isEmpty
}

object TrieNodeHealingCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef,
      concurrency: Int = 16
  ): Props =
    Props(
      new TrieNodeHealingCoordinator(
        stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        batchSize,
        snapSyncController,
        concurrency
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
