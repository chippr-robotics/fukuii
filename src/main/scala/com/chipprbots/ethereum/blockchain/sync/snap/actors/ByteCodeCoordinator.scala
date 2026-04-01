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
import com.chipprbots.ethereum.domain.Account

/** ByteCodeCoordinator manages bytecode download workers.
  *
  * Now contains ALL business logic previously in ByteCodeDownloader. This is the sole implementation - no synchronized
  * fallback.
  *
  * Responsibilities:
  *   - Maintain queue of pending bytecode download tasks
  *   - Distribute tasks to worker actors
  *   - Verify bytecode hashes match requested hashes
  *   - Store bytecodes to EvmCodeStorage
  *   - Report progress to SNAPSyncController
  *   - Handle worker failures with supervision
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
    cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig,
    snapSyncController: ActorRef
) extends Actor
    with ActorLogging {

  import Messages._

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  // Shadows cooldownConfig.maxInFlightPerPeer so budget updates don't require config mutation.
  private var maxInFlightPerPeer: Int = cooldownConfig.maxInFlightPerPeer

  private val peerFailureCounts = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Int]
  private val peerCooldownUntilMillis = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Long]
  private val knownAvailablePeers = mutable.Set[Peer]()

  private def nowMillis: Long = System.currentTimeMillis()

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMillis.get(peer.id).exists(_ > nowMillis)

  private def clearPeerFailures(peer: Peer): Unit = {
    peerFailureCounts.remove(peer.id)
    peerCooldownUntilMillis.remove(peer.id)
  }

  private def recordPeerCooldown(peer: Peer, base: FiniteDuration, reason: String): FiniteDuration = {
    val failures = peerFailureCounts.getOrElse(peer.id, 0) + 1
    peerFailureCounts.update(peer.id, failures)

    // Exponential backoff: base, 2*base, 4*base, ... (capped)
    val exponent = math.min(failures - 1, cooldownConfig.exponentCap)
    val cooldown = (base * (1L << exponent)).min(cooldownConfig.max)
    peerCooldownUntilMillis.update(peer.id, nowMillis + cooldown.toMillis)
    log.debug(s"Cooling down peer ${peer.id.value} for $cooldown ($reason, failures=$failures)")
    cooldown
  }

  // Per-hash failure tracking: prevents infinite re-queuing of hashes no peer can serve.
  // After maxFailuresPerHash attempts across all peers, the hash is skipped with a warning.
  private val hashFailureCounts = mutable.Map.empty[ByteString, Int]
  private val maxFailuresPerHash: Int = 10

  // Task management
  private val pendingTasks = mutable.Queue[ByteCodeTask]()
  final private case class ActiveByteCodeRequest(
      task: ByteCodeTask,
      worker: ActorRef,
      peer: Peer,
      requestedBytes: BigInt,
      startedAtMillis: Long
  )

  private val activeTasks = mutable.Map.empty[BigInt, ActiveByteCodeRequest] // requestId -> active request
  private val completedTasks = mutable.ArrayBuffer[ByteCodeTask]()

  // R4: Track all codeHashes that have been successfully downloaded (across all batches).
  // Eliminates ~7,350 redundant bytecode requests from cross-batch Bloom filter false positives.
  private val downloadedCodeHashes = mutable.HashSet.empty[ByteString]

  // Worker pool
  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val maxWorkers = 32
  private val idleWorkers = mutable.LinkedHashSet.empty[ActorRef]

  // Sentinel: when true, no more AddByteCodeTasks will arrive (all accounts downloaded).
  // Completion is only reported after this is set AND pending+active tasks drain.
  // Geth-aligned: coordinators run from start, tasks arrive inline during account download.
  private var noMoreTasksExpected: Boolean = false

  // Statistics
  private var bytecodesDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()
  private var lastProgressLogAt: Long = 0
  private val ProgressLogInterval: Long = 500

  // ByteCodes request tuning (Nethermind-style): use a per-peer dynamic byte budget, clamped hard to 2 MiB.
  // We send many hashes and rely on the peer-side `responseBytes` soft limit to bound work.
  private val minResponseBytes: BigInt = 50 * 1024
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024
  private val initialResponseBytes: BigInt = 512 * 1024
  private val increaseFactor: Double = 1.25
  private val decreaseFactor: Double = 0.5

  private val peerResponseBytesTarget = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, BigInt]

  private def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget.getOrElseUpdate(peer.id, initialResponseBytes).max(minResponseBytes).min(maxResponseBytes)

  private def adjustResponseBytesTargetOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    // If we appear to be filling the current budget, try increasing (up to clamp).
    // This mimics Nethermind's approach of probing larger budgets on responsive peers.
    if (requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes) {
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id, BigInt(next).min(maxResponseBytes).max(minResponseBytes))
    }

  private def adjustResponseBytesTargetOnFailure(peer: Peer, reason: String): Unit = {
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing ByteCodes responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id)} ($reason)"
    )
  }

  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_.peer.id == peer.id)

  override def preStart(): Unit =
    log.info("ByteCodeCoordinator starting")

  override def postStop(): Unit =
    log.info(s"ByteCodeCoordinator stopped. Downloaded $bytecodesDownloaded bytecodes")

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) { case _: Exception =>
      log.warning("ByteCode worker failed, restarting")
      Restart
    }

  override def receive: Receive = {
    case StartByteCodeSync(codeHashes) =>
      log.info(s"Starting bytecode sync for ${codeHashes.size} unique codeHashes")

      val filteredHashes = filterAndDedupeCodeHashes(codeHashes)
      val newTasks = ByteCodeTask.createBatchedTasks(filteredHashes, batchSize)
      pendingTasks.enqueueAll(newTasks)
      log.info(s"Queued ${newTasks.size} bytecode tasks from ${filteredHashes.size} unique hashes")

    case AddByteCodeTasks(codeHashes) =>
      val filtered = filterAndDedupeCodeHashes(codeHashes)
      if (filtered.nonEmpty) {
        val newTasks = ByteCodeTask.createBatchedTasks(filtered, batchSize)
        pendingTasks.enqueueAll(newTasks)
        log.debug(
          s"Added ${newTasks.size} bytecode tasks from ${filtered.size} incremental hashes (pending: ${pendingTasks.size})"
        )
      }

    case NoMoreByteCodeTasks =>
      noMoreTasksExpected = true
      log.info(s"No more bytecode tasks expected. Pending: ${pendingTasks.size}, active: ${activeTasks.size}")
      checkCompletion()

    case ByteCodePivotRefreshed =>
      log.info("Pivot refreshed — clearing bytecode peer cooldowns")
      peerFailureCounts.clear()
      peerCooldownUntilMillis.clear()
      peerResponseBytesTarget.clear()
      knownAvailablePeers.clear()

    case PeerAvailable(peer) =>
      val evicted0 = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
      knownAvailablePeers --= evicted0
      knownAvailablePeers += peer
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) due to cooldown")
      } else {
        dispatchIfPossible(peer)
      }

    case ByteCodePeerAvailable(peer) =>
      val evicted1 = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
      knownAvailablePeers --= evicted1
      knownAvailablePeers += peer
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring ByteCodePeerAvailable(${peer.id.value}) due to cooldown")
      } else {
        dispatchIfPossible(peer)
      }

    case UpdateMaxInFlightPerPeer(newLimit) =>
      if (newLimit != maxInFlightPerPeer) {
        log.info(s"ByteCode per-peer budget: $maxInFlightPerPeer -> $newLimit")
        maxInFlightPerPeer = newLimit
        if (newLimit > 0) tryRedispatchPendingTasks()
      }

    case ByteCodesResponseMsg(response) =>
      handleByteCodesResponse(response)

    case ByteCodeTaskComplete(requestId, result) =>
      activeTasks.remove(requestId).foreach { active =>
        markWorkerIdle(active.worker)
        // A peer that successfully serves any portion of a task gets its failures cleared.
        result.foreach(_ => clearPeerFailures(active.peer))
      }
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
      activeTasks.remove(requestId).foreach { active =>
        val task = active.task
        val worker = active.worker
        val peer = active.peer
        log.warning(s"Re-queuing bytecode task after failure: $error")
        task.pending = false
        pendingTasks.enqueue(task)
        recordPeerCooldown(peer, cooldownConfig.baseTimeout, s"request failed: $error")
        adjustResponseBytesTargetOnFailure(peer, s"request failed: $error")
        markWorkerIdle(worker)
      }
      checkCompletion()

    case ByteCodeCheckCompletion =>
      checkCompletion()

    case ByteCodeGetProgress =>
      val total = completedTasks.size + activeTasks.size + pendingTasks.size
      val progress = if (total == 0) 1.0 else completedTasks.size.toDouble / total
      sender() ! ByteCodeProgress(progress, bytecodesDownloaded, bytesDownloaded)
  }

  private def assignTaskToWorker(worker: ActorRef, peer: Peer): Unit = {
    if (pendingTasks.isEmpty) return

    // Mark worker busy.
    idleWorkers -= worker

    // Geth-aligned: size batch to peer's measured capacity via PeerRateTracker.
    // New/slow peers get smaller batches that complete faster, building rate measurements.
    // Fast peers get full batches (up to batchSize, default 85).
    val targetRTT = requestTracker.rateTracker.targetRoundTrip()
    // Floor of 10: don't split tiny batches (avoids degenerate 1-hash requests when no measurements exist).
    // New peers: capacity()=1 → floored to 10. Measured fast peers: capacity()=50-85 → full batches.
    val peerCap = requestTracker.rateTracker
      .capacity(peer.id.value, PeerRateTracker.MsgGetByteCodes, targetRTT)
      .max(10) // Floor: never go below 10 hashes per request
      .min(batchSize) // Ceiling: never exceed configured max

    val task = pendingTasks.dequeue()
    // If peer capacity < task size, split: send peerCap hashes, re-queue remainder
    val toSend = if (task.codeHashes.size > peerCap && peerCap > 0) {
      val (send, keep) = task.codeHashes.splitAt(peerCap)
      pendingTasks.enqueue(ByteCodeTask(keep))
      ByteCodeTask(send)
    } else {
      task
    }

    val requestId = requestTracker.generateRequestId()
    val requestedBytes = responseBytesTargetFor(peer)

    toSend.pending = true
    activeTasks.put(
      requestId,
      ActiveByteCodeRequest(toSend, worker, peer, requestedBytes = requestedBytes, startedAtMillis = nowMillis)
    )

    log.debug(
      s"Assigning bytecode task (${toSend.codeHashes.size} hashes, peerCap=$peerCap) to worker for peer ${peer.id}"
    )
    worker ! ByteCodeWorkerFetchTask(toSend, peer, requestId, requestedBytes, cooldownConfig.requestTimeout)
  }

  private def dispatchIfPossible(peer: Peer): Unit = {
    if (pendingTasks.isEmpty) return

    // Keep a small bounded number of inflight requests per peer to maximize throughput
    // without overloading any single neighbor.
    var inflight = inFlightForPeer(peer)
    while (pendingTasks.nonEmpty && inflight < maxInFlightPerPeer) {
      val workerOpt: Option[ActorRef] =
        idleWorkers.headOption.orElse {
          if (workers.size < maxWorkers) Some(createWorker()) else None
        }

      workerOpt match {
        case Some(worker) =>
          assignTaskToWorker(worker, peer)
          inflight += 1
        case None =>
          // No worker capacity available right now.
          return
      }
    }
  }

  /** Re-dispatch pending tasks to known available peers not on cooldown.
    * Uses knownAvailablePeers (populated on every PeerAvailable tick) rather than
    * activeTasks — ensures redispatch works even when all active tasks are cooling down.
    */
  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    val eligiblePeers = knownAvailablePeers.filterNot(isPeerCoolingDown).toList
    if (eligiblePeers.isEmpty) return
    for (peer <- eligiblePeers if pendingTasks.nonEmpty)
      dispatchIfPossible(peer)
  }

  private def handleByteCodesResponse(response: ByteCodes): Unit = {
    activeTasks.get(response.requestId) match {
      case None =>
        log.debug(s"Received ByteCodes response for unknown or completed request ${response.requestId}")

      case Some(active) =>
        val task = active.task
        val worker = active.worker
        val peer = active.peer
        val requestedBytes = active.requestedBytes
        val elapsedMillis = (nowMillis - active.startedAtMillis).max(0L)
        log.debug(s"Processing ByteCodes response: ${response.codes.size} codes for request ${response.requestId}")

        // SNAP spec: returned codes are in request order, may have gaps (unavailable codes skipped)
        // and may omit a suffix due to response byte limits.
        validateReturnedCodes(task.codeHashes, response.codes) match {
          case Left(error) =>
            log.warning(s"Bytecode verification failed: $error")
            activeTasks.remove(response.requestId)
            task.pending = false
            pendingTasks.enqueue(task)

            // Spec violation or malicious peer - back off harder than empty responses.
            recordPeerCooldown(peer, cooldownConfig.baseInvalid, s"invalid ByteCodes: $error")
            adjustResponseBytesTargetOnFailure(peer, s"invalid response: $error")
            markWorkerIdle(worker)
            checkCompletion()

          case Right(validated) =>
            // Store only the returned codes
            storeBytecodesWithHashes(validated.codesByHashInOrder) match {
              case Left(error) =>
                log.warning(s"Failed to store bytecodes: $error")
                activeTasks.remove(response.requestId)
                task.pending = false
                pendingTasks.enqueue(task)

                // Storage failure isn't necessarily the peer's fault, but to be a good neighbor
                // (and avoid tight loops), briefly cool down this peer.
                recordPeerCooldown(peer, cooldownConfig.baseTimeout, s"local store failed: $error")
                adjustResponseBytesTargetOnFailure(peer, s"local store failed: $error")
                markWorkerIdle(worker)
                checkCompletion()

              case Right(_) =>
                // R4: Track successfully downloaded hashes for cross-batch dedup
                downloadedCodeHashes ++= validated.matchedHashes
                val remainingHashes = task.codeHashes
                  .filterNot(validated.matchedHashes.contains)
                  .filterNot(downloadedCodeHashes.contains)

                val receivedBytes: BigInt = BigInt(response.codes.map(_.size.toLong).sum)
                // Update per-peer budget based on observed response size.
                adjustResponseBytesTargetOnSuccess(peer, requested = requestedBytes, received = receivedBytes)
                log.debug(
                  s"ByteCodes tuning: peer=${peer.id.value} requestedBytes=$requestedBytes receivedBytes=$receivedBytes " +
                    s"elapsedMs=$elapsedMillis newTarget=${responseBytesTargetFor(peer)}"
                )

                // Backoff behavior:
                // - empty response: peer had none of the requested codes; cool down briefly
                // - non-empty response: peer is useful; clear failures
                if (validated.matchedHashes.isEmpty) {
                  recordPeerCooldown(peer, cooldownConfig.baseEmpty, "empty ByteCodes response")
                  // Increment per-hash failure counters for all hashes in the task
                  task.codeHashes.foreach { hash =>
                    hashFailureCounts.update(hash, hashFailureCounts.getOrElse(hash, 0) + 1)
                  }
                } else {
                  clearPeerFailures(peer)
                  // Clear failure counters for successfully received hashes
                  validated.matchedHashes.foreach(hashFailureCounts.remove)
                }

                if (remainingHashes.nonEmpty) {
                  // Filter out hashes that have exceeded max retries (no peer can serve them)
                  val (exhausted, retryable) = remainingHashes.partition { hash =>
                    hashFailureCounts.getOrElse(hash, 0) >= maxFailuresPerHash
                  }
                  if (exhausted.nonEmpty) {
                    log.warning(
                      s"Skipping ${exhausted.size} bytecode hashes after $maxFailuresPerHash failures each " +
                        s"(no peer could serve them). Sample: ${exhausted.take(3).map(_.take(4).toArray.map("%02x".format(_)).mkString).mkString(", ")}"
                    )
                    exhausted.foreach(hashFailureCounts.remove)
                  }
                  if (retryable.nonEmpty) {
                    log.info(
                      s"ByteCodes response contained ${validated.matchedHashes.size}/${task.codeHashes.size} requested codes; " +
                        s"re-queuing remaining ${retryable.size} hashes"
                    )
                    pendingTasks.enqueue(ByteCodeTask(retryable))
                  }
                }

                val bytecodeCount = response.codes.size
                bytecodesDownloaded += bytecodeCount
                snapSyncController ! SNAPSyncController.ProgressBytecodesDownloaded(bytecodeCount.toLong)
                bytesDownloaded += response.codes.map(_.size.toLong).sum

                // Only mark the task completed if nothing remains; large batches may be partially served due to bytes.
                task.pending = false
                if (remainingHashes.isEmpty) {
                  task.done = true
                  task.bytecodes = response.codes
                  completedTasks += task
                }

                activeTasks.remove(response.requestId)
                markWorkerIdle(worker)

                log.debug(
                  s"Successfully processed $bytecodeCount bytecodes (receivedBytes=$receivedBytes requestedBytes=$requestedBytes)"
                )

                // Periodic progress summary (every 500 bytecodes)
                if (bytecodesDownloaded - lastProgressLogAt >= ProgressLogInterval) {
                  val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                  val rate = if (elapsed > 0) (bytecodesDownloaded / elapsed).toLong else 0L
                  log.info(
                    s"Bytecode progress: $bytecodesDownloaded codes (${"%.1f".format(bytesDownloaded / 1048576.0)} MB) " +
                      s"(${completedTasks.size} done, ${pendingTasks.size} pending, ${activeTasks.size} active, " +
                      s"$rate codes/sec)"
                  )
                  lastProgressLogAt = bytecodesDownloaded
                }

                checkCompletion()
            }
        }
    }
  }

  final private case class ValidatedByteCodes(
      matchedHashes: Set[ByteString],
      codesByHashInOrder: Vector[(ByteString, ByteString)]
  )

  private def validateReturnedCodes(
      requestedHashes: Seq[ByteString],
      returnedCodes: Seq[ByteString]
  ): Either[String, ValidatedByteCodes] =
    if (returnedCodes.isEmpty) {
      Right(ValidatedByteCodes(Set.empty, Vector.empty))
    } else {
      val matched = mutable.HashSet.empty[ByteString]
      val seenReturned = mutable.HashSet.empty[ByteString]
      val pairs = Vector.newBuilder[(ByteString, ByteString)]
      var reqIdx = 0

      var error: String | Null = null
      val it = returnedCodes.iterator

      while (it.hasNext && error == null) {
        val code = it.next()
        val rh = kec256(code)

        if (seenReturned.contains(rh)) {
          val sample = rh.take(4).toArray.map("%02x".format(_)).mkString
          error = s"Received duplicate bytecode for hash sample=$sample"
        } else {
          seenReturned += rh

          // Verify returned hashes form a subsequence of the requested hashes, preserving order.
          while (reqIdx < requestedHashes.size && requestedHashes(reqIdx) != rh)
            reqIdx += 1
          if (reqIdx >= requestedHashes.size) {
            val sample = rh.take(4).toArray.map("%02x".format(_)).mkString
            error = s"Received bytecode hash not present in requested list or out of order (sample=$sample)"
          } else {
            matched += rh
            pairs += ((rh, code))
            reqIdx += 1
          }
        }
      }

      if (error != null) Left(error)
      else Right(ValidatedByteCodes(matched.toSet, pairs.result()))
    }

  private def storeBytecodesWithHashes(codesByHash: Seq[(ByteString, ByteString)]): Either[String, Unit] =
    try {
      val updates = codesByHash.foldLeft(evmCodeStorage.emptyBatchUpdate) { case (batchUpdate, (codeHash, code)) =>
        batchUpdate.and(evmCodeStorage.put(codeHash, code))
      }

      updates.commit()
      log.debug(s"Successfully persisted ${codesByHash.size} bytecodes to storage")
      Right(())
    } catch {
      case e: Exception =>
        log.error(s"Failed to store bytecodes: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")
    }

  private def checkCompletion(): Unit =
    if (noMoreTasksExpected && pendingTasks.isEmpty && activeTasks.isEmpty) {
      log.info("Bytecode sync complete!")
      snapSyncController ! SNAPSyncController.ByteCodeSyncComplete
    }

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      ByteCodeWorker
        .props(
          coordinator = self,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker
        )
        .withDispatcher("sync-dispatcher")
    )
    workers += worker
    idleWorkers += worker
    log.debug(s"Created bytecode worker, total: ${workers.size}")
    worker
  }

  private def markWorkerIdle(worker: ActorRef): Unit =
    // Only track workers we created.
    if (workers.contains(worker)) {
      idleWorkers += worker
    }

  /** Filter and deduplicate codeHashes. Input is already Bloom-filtered by AccountRangeCoordinator (~0.01% FPR), so
    * this is a final dedup pass over ~2M entries (not 73.5M). Bug 20 fix.
    */
  private def filterAndDedupeCodeHashes(
      codeHashes: Seq[ByteString]
  ): Seq[ByteString] = {
    val seen = mutable.HashSet.empty[ByteString]
    var invalidCount = 0
    var dupeCount = 0
    var alreadyDownloaded = 0

    val filtered = codeHashes.filter { codeHash =>
      if (codeHash.length != 32) {
        invalidCount += 1
        false
      } else if (codeHash == Account.EmptyCodeHash) {
        false
      } else if (downloadedCodeHashes.contains(codeHash)) {
        alreadyDownloaded += 1
        false
      } else if (seen.contains(codeHash)) {
        dupeCount += 1
        false
      } else {
        seen += codeHash
        true
      }
    }

    if (invalidCount > 0)
      log.warning(s"Dropped $invalidCount codeHashes with non-32-byte length")
    if (dupeCount > 0)
      log.info(s"Deduplicated $dupeCount codeHashes (Bloom filter false positives)")
    if (alreadyDownloaded > 0)
      log.info(s"Skipped $alreadyDownloaded codeHashes already downloaded (cross-batch dedup)")

    filtered
  }
}

object ByteCodeCoordinator {

  final case class ByteCodePeerCooldownConfig(
      baseEmpty: FiniteDuration,
      baseTimeout: FiniteDuration,
      baseInvalid: FiniteDuration,
      maxInFlightPerPeer: Int,
      max: FiniteDuration,
      exponentCap: Int,
      requestTimeout: FiniteDuration = Duration.Zero // Zero = adaptive (PeerRateTracker)
  )

  object ByteCodePeerCooldownConfig {
    val default: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig(
      baseEmpty = 2.seconds,
      baseTimeout = 10.seconds,
      baseInvalid = 15.seconds,
      // Increase per-peer concurrency (Besu caps peer-wide outstanding at 5; this keeps us competitive).
      maxInFlightPerPeer = 5,
      max = 2.minutes,
      exponentCap = 10,
      requestTimeout = Duration.Zero // Adaptive: min(60s, 3 × medianRTT / confidence)
    )
  }

  def props(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: ActorRef,
      cooldownConfig: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig.default
  ): Props =
    Props(
      new ByteCodeCoordinator(
        evmCodeStorage,
        networkPeerManager,
        requestTracker,
        batchSize,
        cooldownConfig,
        snapSyncController
      )
    )
}
