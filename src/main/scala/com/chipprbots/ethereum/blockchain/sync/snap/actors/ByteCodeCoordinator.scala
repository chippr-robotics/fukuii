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
    snapSyncController: ActorRef,
    // Back-pressure watermarks — mirror the storage coordinator pattern. ByteCodeTask carries the
    // returned bytecode blob payload on completion, so an unthrottled queue is even more dangerous
    // here than for storage (a single 24 KB EOF contract × millions of queued tasks would dwarf
    // the heap). Defaults are lower than storage's 100K/50K because each task batches `batchSize`
    // (~85) hashes, so a 50K-task queue is effectively ~4M pending hashes.
    backpressureHighWatermark: Int = 50000,
    backpressureLowWatermark: Int = 25000
) extends Actor
    with ActorLogging {

  import Messages._

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  // Shadows cooldownConfig.maxInFlightPerPeer so budget updates don't require config mutation.
  private var maxInFlightPerPeer: Int = cooldownConfig.maxInFlightPerPeer

  private val peerFailureCounts = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Int]
  private val peerCooldownUntilMillis = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Long]

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

  // Track known available peers for redispatch when activeTasks is empty.
  // tryRedispatchPendingTasks() previously derived peers from activeTasks.values, leaving it
  // unable to dispatch when activeTasks was empty (all peers in cooldown). This caused an
  // unnecessary 2-minute stall window after simultaneous peer failures.
  // Deduped by remoteAddress — same pattern as TrieNodeHealingCoordinator.
  private val knownAvailablePeers = mutable.Set[Peer]()

  // Consecutive task failure guard: if all peers repeatedly fail with no progress,
  // force-complete bytecode sync rather than hanging indefinitely. Threshold 100 matches
  // april-confluence production tuning. Missing bytecodes are recovered per-block during import.
  private var consecutiveTaskFailures: Int = 0
  private val maxConsecutiveTaskFailures: Int = 100

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

  // Completion bookkeeping (#1233). Previously `completedTasks: mutable.ArrayBuffer[ByteCodeTask]`
  // retained the full task object — including `task.bytecodes: Seq[ByteString]`, the actual
  // downloaded bytecode blobs — forever, just to read `.size` for progress. At ~3M sepolia
  // codehashes and ~5-10 KB per blob that's ~2-5 GB of retention; on ETH mainnet (~73 M unique
  // hashes per memory) the same pattern would leak 20-40+ GB. Replaced with a plain Long counter;
  // blobs are no longer reachable after the per-hash write commits.
  private var completedTaskCount: Long = 0L

  // Back-pressure state. Set true on the high-water transition; cleared on low-water. Workers
  // already in flight always continue to completion; only AccountRangeCoordinator dispatch is
  // gated via the forwarded `ByteCodeQueuePressure` signal.
  private[actors] var backpressureActive: Boolean = false

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
  // #1164: number of bytecode hashes abandoned via ForceCompleteByteCodes. Surfaced in postStop()
  // diagnostics so operators can correlate force-completes with later BytecodeRecoveryActor activity.
  private var bytecodesAbandoned: Long = 0

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
    log.info(
      s"ByteCodeCoordinator stopped. Downloaded $bytecodesDownloaded bytecodes" +
        (if (bytecodesAbandoned > 0) s", abandoned $bytecodesAbandoned via force-complete" else "")
    )

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
        // Account-range download is the only path that grows the queue faster than dispatch can
        // drain it. Mirrors the storage coordinator's pattern (#1233).
        notifyBackpressureIfChanged()
      }

    case NoMoreByteCodeTasks =>
      noMoreTasksExpected = true
      log.info(s"No more bytecode tasks expected. Pending: ${pendingTasks.size}, active: ${activeTasks.size}")
      checkCompletion()

    case ByteCodePivotRefreshed =>
      // BUG-S1: Do NOT clear knownAvailablePeers — bytecodes are content-addressed (hash-keyed),
      // not state-root-dependent, so existing peers can serve them after a pivot refresh.
      // Clearing the set would force a cold-start re-registration delay.
      log.info("Pivot refreshed — clearing bytecode peer cooldowns (keeping peer set)")
      peerFailureCounts.clear()
      peerCooldownUntilMillis.clear()
      peerResponseBytesTarget.clear()
      consecutiveTaskFailures = 0
      // Mirror the storage-side fix (sepolia 2026-05-14 deadlock): if back-pressure was
      // engaged for the old root, the new root is the right time to release it. If the
      // queue is still above the high-water mark, the next AddByteCodeTasks will re-engage.
      if (backpressureActive) {
        backpressureActive = false
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeBackpressure(false)
        log.info(
          s"ByteCode queue back-pressure RELEASED on pivot refresh (queue depth=${pendingTasks.size}). " +
            s"Will re-engage if queue crosses high-water=$backpressureHighWatermark again."
        )
        snapSyncController ! SNAPSyncController.ByteCodeBackpressureChanged(paused = false)
      }

    case PeerAvailable(peer) =>
      knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
      knownAvailablePeers += peer
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) due to cooldown")
      } else {
        dispatchIfPossible(peer)
      }

    case ByteCodePeerAvailable(peer) =>
      knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
      knownAvailablePeers += peer
      if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring ByteCodePeerAvailable(${peer.id.value}) due to cooldown")
      } else {
        dispatchIfPossible(peer)
      }

    case ByteCodePeerUnavailable(peerId) =>
      // Peer disconnected — remove from available set and immediately release its in-flight
      // workers so they can be reassigned to other peers without waiting for 30s timeout.
      // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
      knownAvailablePeers.filterInPlace(_.id.value != peerId)
      peerCooldownUntilMillis.remove(com.chipprbots.ethereum.network.PeerId(peerId))
      val inFlight = activeTasks.collect {
        case (reqId, active) if active.peer.id.value == peerId => (reqId, active.worker, active.task)
      }.toSeq
      if (inFlight.nonEmpty) {
        log.debug(s"Peer $peerId disconnected — re-queuing ${inFlight.size} in-flight bytecode request(s)")
        inFlight.foreach { case (reqId, worker, task) =>
          activeTasks.remove(reqId)
          task.pending = false
          pendingTasks.enqueue(task)
          worker ! ByteCodeWorkerRelease(reqId)
        }
      }
      tryRedispatchPendingTasks()

    case UpdateMaxInFlightPerPeer(newLimit) =>
      log.info(s"ByteCode per-peer budget: $maxInFlightPerPeer -> $newLimit")
      maxInFlightPerPeer = newLimit
      if (newLimit > 0) tryRedispatchPendingTasks()

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
          consecutiveTaskFailures = 0
          log.info(s"Bytecode task completed: $count codes")
          checkCompletion()
        case Left(error) =>
          log.warning(s"Bytecode task failed: $error")
          checkCompletion()
      }

    case ByteCodeTaskFailed(requestId, error) =>
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
        consecutiveTaskFailures += 1
        if (consecutiveTaskFailures >= maxConsecutiveTaskFailures) {
          log.warning(
            s"Force-completing bytecode coordinator after $consecutiveTaskFailures consecutive " +
              s"task failures — SNAP peers not serving data. " +
              s"Missing bytecodes deferred to import-time recovery."
          )
          noMoreTasksExpected = true
        }
      }
      checkCompletion()

    case ByteCodeCheckCompletion =>
      checkCompletion()
      // Drain side of the back-pressure watermark — release the AccountRangeCoordinator pause
      // once dispatches + completions have shrunk the queue below the low-water mark.
      notifyBackpressureIfChanged()

    case ForceCompleteByteCodes =>
      // #1164: When bytecode sync stagnates (e.g., a small set of code hashes no peer can serve),
      // the existing completion check is unreachable because pendingTasks doesn't drain. Mirrors
      // StorageRangeCoordinator.ForceCompleteStorage. Missing bytecodes can be recovered post-SNAP
      // via BytecodeRecoveryActor.
      val abandonedPending = pendingTasks.size
      val abandonedActive = activeTasks.size
      val abandonedTotal = abandonedPending + abandonedActive
      log.warning(
        s"Force-completing bytecode sync: $bytecodesDownloaded bytecodes downloaded, " +
          s"abandoning $abandonedTotal remaining tasks ($abandonedPending pending, $abandonedActive active). " +
          "Missing bytecodes will be recovered post-SNAP via BytecodeRecoveryActor if needed."
      )
      bytecodesAbandoned += abandonedTotal
      pendingTasks.clear()
      // Mark workers idle so they don't keep dispatching; drop active task tracking.
      activeTasks.values.foreach(active => markWorkerIdle(active.worker))
      activeTasks.clear()
      // Set the sentinel so any late `checkCompletion()` calls also pass cleanly.
      noMoreTasksExpected = true
      log.info("Bytecode sync force-completed (promoting to healing/recovery phase)")
      snapSyncController ! SNAPSyncController.ByteCodeSyncComplete

    case ByteCodeGetProgress =>
      val total = completedTaskCount + activeTasks.size.toLong + pendingTasks.size.toLong
      val progress = if (total == 0) 1.0 else completedTaskCount.toDouble / total
      sender() ! ByteCodeProgress(progress, bytecodesDownloaded, bytesDownloaded)
  }

  /** Emit a ByteCodeBackpressureChanged transition when the pending-task queue depth crosses a watermark. Forwarded by
    * SNAPSyncController to AccountRangeCoordinator as `ByteCodeQueuePressure` so account workers stop producing new
    * bytecode tasks during back-pressure. Mirrors `StorageRangeCoordinator.notifyBackpressureIfChanged` (#1233).
    */
  private def notifyBackpressureIfChanged(): Unit = {
    val pending = pendingTasks.size
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeQueueDepth(pending.toLong)
    if (!backpressureActive && pending >= backpressureHighWatermark) {
      backpressureActive = true
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeBackpressure(true)
      log.info(
        s"ByteCode queue back-pressure ENGAGED at $pending pending tasks (high-water=$backpressureHighWatermark). " +
          s"Signalling AccountRangeCoordinator to pause dispatch."
      )
      snapSyncController ! SNAPSyncController.ByteCodeBackpressureChanged(paused = true)
    } else if (backpressureActive && pending <= backpressureLowWatermark) {
      backpressureActive = false
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeBackpressure(false)
      log.info(
        s"ByteCode queue back-pressure RELEASED at $pending pending tasks (low-water=$backpressureLowWatermark). " +
          s"Signalling AccountRangeCoordinator to resume dispatch."
      )
      snapSyncController ! SNAPSyncController.ByteCodeBackpressureChanged(paused = false)
    }
  }

  private def assignTaskToWorker(worker: ActorRef, peer: Peer): Unit = {
    if (pendingTasks.isEmpty) return

    // Mark worker busy.
    idleWorkers -= worker

    val task = pendingTasks.dequeue()
    val requestId = requestTracker.generateRequestId()

    val requestedBytes = responseBytesTargetFor(peer)

    task.pending = true
    activeTasks.put(
      requestId,
      ActiveByteCodeRequest(task, worker, peer, requestedBytes = requestedBytes, startedAtMillis = nowMillis)
    )

    log.debug(s"Assigning bytecode task (${task.codeHashes.size} hashes) to worker for peer ${peer.id}")
    worker ! ByteCodeWorkerFetchTask(task, peer, requestId, requestedBytes)
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

  /** Re-dispatch pending tasks to all known peers. Includes both peers with active tasks and known available peers from
    * PeerAvailable events — handles the activeTasks=empty case that previously blocked dispatch after simultaneous peer
    * cooldowns.
    */
  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    val peersFromActive = activeTasks.values.map(_.peer).toSet
    val allKnown = peersFromActive ++ knownAvailablePeers
    for (peer <- allKnown if pendingTasks.nonEmpty && !isPeerCoolingDown(peer))
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
            worker ! ByteCodeWorkerRelease(response.requestId)
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
                worker ! ByteCodeWorkerRelease(response.requestId)
                markWorkerIdle(worker)
                checkCompletion()

              case Right(_) =>
                val remainingHashes = task.codeHashes.filterNot(validated.matchedHashes.contains)

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
                consecutiveTaskFailures = 0

                // Only mark the task completed if nothing remains; large batches may be partially served due to bytes.
                task.pending = false
                if (remainingHashes.isEmpty) {
                  task.done = true
                  // task.bytecodes was assigned to the in-flight task purely so the old buffer
                  // could retain it; now that we only track a count, we no longer need to attach
                  // the blob to the task struct — the bytes have already been written via
                  // evmCodeStorage upstream. Skip the assignment to drop the only retention path
                  // for the downloaded code blobs (#1233 follow-up).
                  completedTaskCount += 1L
                }

                activeTasks.remove(response.requestId)
                worker ! ByteCodeWorkerRelease(response.requestId)
                markWorkerIdle(worker)

                log.info(
                  s"Successfully processed $bytecodeCount bytecodes (receivedBytes=$receivedBytes requestedBytes=$requestedBytes)"
                )
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
      log.info(s"Successfully persisted ${codesByHash.size} bytecodes to storage")
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

    val filtered = codeHashes.filter { codeHash =>
      if (codeHash.length != 32) {
        invalidCount += 1
        false
      } else if (codeHash == Account.EmptyCodeHash) {
        false
      } else if (seen.contains(codeHash)) {
        dupeCount += 1
        false
      } else {
        seen += codeHash
        true
      }
    }

    if (invalidCount > 0) {
      log.warning(s"Dropped $invalidCount codeHashes with non-32-byte length")
    }
    if (dupeCount > 0) {
      log.info(s"Deduplicated $dupeCount codeHashes (Bloom filter false positives)")
    }

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
      exponentCap: Int
  )

  object ByteCodePeerCooldownConfig {
    val default: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig(
      baseEmpty = 2.seconds,
      baseTimeout = 10.seconds,
      baseInvalid = 15.seconds,
      // Increase per-peer concurrency (Besu caps peer-wide outstanding at 5; this keeps us competitive).
      maxInFlightPerPeer = 5,
      max = 2.minutes,
      exponentCap = 10
    )
  }

  def props(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: ActorRef,
      cooldownConfig: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig.default,
      backpressureHighWatermark: Int = 50000,
      backpressureLowWatermark: Int = 25000
  ): Props =
    Props(
      new ByteCodeCoordinator(
        evmCodeStorage,
        networkPeerManager,
        requestTracker,
        batchSize,
        cooldownConfig,
        snapSyncController,
        backpressureHighWatermark = backpressureHighWatermark,
        backpressureLowWatermark = backpressureLowWatermark
      )
    )
}
