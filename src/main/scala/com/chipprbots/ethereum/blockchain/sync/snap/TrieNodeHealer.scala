package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.{GetTrieNodes, TrieNodes}
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** Coordinator for state trie healing operations.
  *
  * State healing is the final phase of SNAP sync that detects and downloads missing
  * trie nodes that were not included in account/storage range downloads. This healer
  * manages the healing process by:
  *
  * 1. Detecting missing nodes during account/storage verification
  * 2. Batching GetTrieNodes requests for efficiency
  * 3. Validating and storing received trie nodes
  * 4. Iteratively healing until the trie is complete
  *
  * @param stateRoot Root hash of the state trie to heal
  * @param etcPeerManager Actor for sending requests to peers
  * @param requestTracker Tracker for request/response lifecycle management
  * @param mptStorage Storage layer for persisting healed trie nodes
  * @param batchSize Maximum number of node paths to request in a single message (default 16)
  */
class TrieNodeHealer(
    stateRoot: ByteString,
    etcPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    batchSize: Int = HealingTask.DEFAULT_BATCH_SIZE
) extends Logger {

  /** Maximum response size for trie node requests (1 MB) */
  private val MAX_RESPONSE_SIZE = BigInt(1024 * 1024)

  /** Pending healing tasks (not yet requested) */
  private var pendingTasks: Seq[HealingTask] = Seq.empty

  /** Active healing tasks (currently being healed) */
  private var activeTasks: Seq[HealingTask] = Seq.empty

  /** Completed healing tasks */
  private var completedTasks: Seq[HealingTask] = Seq.empty

  /** Total number of nodes healed */
  private var totalNodesHealed: Int = 0

  /** Total bytes of trie node data received */
  private var totalBytesReceived: Long = 0

  /** Start time for statistics */
  private val startTime = System.currentTimeMillis()

  /** Adds missing nodes to be healed.
    *
    * @param missingNodes List of (path, hash) pairs for missing trie nodes
    */
  def addMissingNodes(missingNodes: Seq[(Seq[ByteString], ByteString)]): Unit = synchronized {
    val newTasks = HealingTask.createTasksFromMissingNodes(missingNodes, stateRoot)
    pendingTasks = pendingTasks ++ newTasks
    log.info(s"Added ${newTasks.size} missing nodes to healing queue. Total pending: ${pendingTasks.size}")
  }
  
  /** Queues a single node by hash for healing.
    * Since we don't have the path, we use the hash as the path (direct hash lookup).
    *
    * @param nodeHash The hash of the missing node
    */
  def queueNode(nodeHash: ByteString): Unit = synchronized {
    // Create a healing task with empty path (direct hash lookup)
    val task = HealingTask(
      path = Seq.empty,
      hash = nodeHash,
      rootHash = stateRoot,
      pending = true,
      done = false,
      nodeData = None
    )
    pendingTasks = pendingTasks :+ task
    log.debug(s"Queued node for healing: hash=${nodeHash.take(4).toHex}")
  }
  
  /** Queues multiple nodes by hash for healing.
    *
    * @param nodeHashes The hashes of missing nodes
    */
  def queueNodes(nodeHashes: Seq[ByteString]): Unit = synchronized {
    nodeHashes.foreach(queueNode)
    log.info(s"Queued ${nodeHashes.size} nodes for healing. Total pending: ${pendingTasks.size}")
  }

  /** Requests the next batch of trie nodes from a peer.
    *
    * @param peer The peer to request from
    * @return Some(requestId) if a request was sent, None if no pending tasks
    */
  def requestNextBatch(peer: Peer): Option[BigInt] = synchronized {
    if (pendingTasks.isEmpty) {
      log.debug("No pending healing tasks")
      return None
    }

    // Take up to batchSize pending tasks
    val batch = pendingTasks.take(batchSize)
    pendingTasks = pendingTasks.drop(batchSize)

    // Mark tasks as active
    batch.foreach(_.pending = false)
    activeTasks = activeTasks ++ batch

    // Create GetTrieNodes request
    val requestId = requestTracker.generateRequestId()
    val paths = batch.map(_.path)

    val request = GetTrieNodes(
      requestId = requestId,
      rootHash = stateRoot,
      paths = paths,
      responseBytes = MAX_RESPONSE_SIZE
    )

    // Track request with timeout
    requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes) {
      handleTimeout(requestId, batch)
    }

    // Send request to peer
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
    val messageSerializable: com.chipprbots.ethereum.network.p2p.MessageSerializable = new GetTrieNodesEnc(request)
    etcPeerManager ! EtcPeerManagerActor.SendMessage(messageSerializable, peer.id)

    log.debug(
      s"Requested ${batch.size} trie nodes from peer $peer " +
        s"(reqId=$requestId, pending=${pendingTasks.size})"
    )

    Some(requestId)
  }

  /** Handles a TrieNodes response from a peer.
    *
    * @param response The TrieNodes message received
    * @return Right(count) with number of nodes healed, or Left(error) if validation failed
    */
  def handleResponse(response: TrieNodes): Either[String, Int] = synchronized {
    val requestId = response.requestId
    val nodes = response.nodes

    log.debug(s"Received TrieNodes response: reqId=$requestId, nodes=${nodes.size}")

    // Find active tasks for this request
    val tasksForRequest = activeTasks.filter(task =>
      requestTracker.getPendingRequest(requestId).exists(_.requestType == SNAPRequestTracker.RequestType.GetTrieNodes)
    )

    if (tasksForRequest.isEmpty) {
      log.warn(s"No active healing tasks found for request $requestId")
      return Left(s"No active tasks for request $requestId")
    }

    // Validate and store received nodes
    var healedCount = 0
    for ((nodeData, task) <- nodes.zip(tasksForRequest)) {
      validateAndStoreNode(nodeData, task) match {
        case Right(_) =>
          task.nodeData = Some(nodeData)
          task.done = true
          healedCount += 1
          totalNodesHealed += 1
          totalBytesReceived += nodeData.length
          log.debug(s"Healed node: ${task.toShortString}")

        case Left(error) =>
          log.warn(s"Failed to heal node: ${task.toShortString} - $error")
          // Re-queue failed task
          task.pending = true
          pendingTasks = pendingTasks :+ task
      }
    }

    // Move completed tasks
    val (completed, stillActive) = tasksForRequest.partition(_.done)
    completedTasks = completedTasks ++ completed
    activeTasks = activeTasks.filterNot(completed.contains)

    // Complete request
    requestTracker.completeRequest(requestId)

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeTasks.size})"
    )

    Right(healedCount)
  }

  /** Validates and stores a healed trie node.
    *
    * @param nodeData RLP-encoded trie node data
    * @param task The healing task for this node
    * @return Right(()) if successful, Left(error) otherwise
    */
  private def validateAndStoreNode(nodeData: ByteString, task: HealingTask): Either[String, Unit] = {
    try {
      // Validate node hash matches expected
      val nodeHash = ByteString(org.bouncycastle.jcajce.provider.digest.Keccak.Digest256().digest(nodeData.toArray))
      if (nodeHash != task.hash) {
        return Left(s"Node hash mismatch: expected ${task.hash.take(4).toHex}, got ${nodeHash.take(4).toHex}")
      }

      // Store node in MptStorage
      // TODO: Properly integrate healed node into state/storage tries
      // Currently stores as individual node - should rebuild trie path
      // This requires coordination with AccountRangeDownloader/StorageRangeDownloader
      storeTrieNode(nodeData, nodeHash)

      log.debug(s"Stored healed trie node: hash=${nodeHash.take(4).toHex}, size=${nodeData.length} bytes")
      Right(())
    } catch {
      case e: Exception =>
        Left(s"Failed to store healed node: ${e.getMessage}")
    }
  }

  /** Stores a healed trie node in MptStorage.
    *
    * @param nodeData RLP-encoded trie node data
    * @param nodeHash Hash of the trie node
    */
  private def storeTrieNode(nodeData: ByteString, nodeHash: ByteString): Unit = synchronized {
    // TODO: Parse node type and create appropriate MptNode
    // For now, store raw node data indexed by hash
    // In a complete implementation, we would:
    // 1. Decode the RLP to determine node type (leaf, branch, extension)
    // 2. Create the appropriate MptNode subclass
    // 3. Store with proper structure

    // Simplified storage: create a leaf node with hash as key
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

    // Persist to disk
    mptStorage.persist()

    log.debug(s"Persisted healed trie node: hash=${nodeHash.take(4).toHex}")
  }

  /** Handles timeout for a healing request.
    *
    * @param requestId The request ID that timed out
    * @param tasks The tasks that were part of the request
    */
  private def handleTimeout(requestId: BigInt, tasks: Seq[HealingTask]): Unit = synchronized {
    log.warn(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}")

    // Re-queue timed out tasks
    tasks.foreach { task =>
      task.pending = true
      task.done = false
    }
    pendingTasks = pendingTasks ++ tasks
    activeTasks = activeTasks.filterNot(tasks.contains)

    log.info(s"Re-queued ${tasks.size} timed-out healing tasks (pending: ${pendingTasks.size})")
  }

  /** Returns current healing statistics.
    *
    * @return A statistics object with healing progress and performance metrics
    */
  def statistics: HealingStatistics = synchronized {
    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
    val nodesPerSec = if (elapsedSec > 0) totalNodesHealed / elapsedSec else 0.0
    val kbPerSec = if (elapsedSec > 0) (totalBytesReceived / 1024.0) / elapsedSec else 0.0

    HealingStatistics(
      totalNodes = totalNodesHealed,
      totalBytes = totalBytesReceived,
      pendingTasks = pendingTasks.size,
      activeTasks = activeTasks.size,
      completedTasks = completedTasks.size,
      nodesPerSecond = nodesPerSec,
      kilobytesPerSecond = kbPerSec,
      progress = calculateProgress()
    )
  }

  /** Calculates overall healing progress (0.0 to 1.0).
    *
    * @return Progress value between 0.0 and 1.0
    */
  private def calculateProgress(): Double = {
    val total = pendingTasks.size + activeTasks.size + completedTasks.size
    if (total == 0) 1.0
    else completedTasks.size.toDouble / total
  }

  /** Returns the number of pending healing tasks.
    *
    * @return Number of tasks waiting to be requested
    */
  def pendingCount: Int = synchronized {
    pendingTasks.size
  }

  /** Returns whether healing is complete (no pending or active tasks).
    *
    * @return True if all healing tasks are done
    */
  def isComplete: Boolean = synchronized {
    pendingTasks.isEmpty && activeTasks.isEmpty
  }

  /** Clears all healing tasks and resets statistics.
    */
  def clear(): Unit = synchronized {
    pendingTasks = Seq.empty
    activeTasks = Seq.empty
    completedTasks = Seq.empty
    totalNodesHealed = 0
    totalBytesReceived = 0
  }
}

/** Statistics for state healing progress and performance. */
case class HealingStatistics(
    totalNodes: Int,
    totalBytes: Long,
    pendingTasks: Int,
    activeTasks: Int,
    completedTasks: Int,
    nodesPerSecond: Double,
    kilobytesPerSecond: Double,
    progress: Double
) {
  override def toString: String = {
    f"HealingStats(Progress: ${progress * 100}%.1f%%, " +
      f"Nodes: $totalNodes (${nodesPerSecond}%.1f nodes/sec), " +
      f"Tasks: $completedTasks done, $activeTasks active, $pendingTasks pending, " +
      f"Throughput: ${kilobytesPerSecond}%.1f KB/sec)"
  }
}
