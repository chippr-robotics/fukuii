package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes

/** ByteCodeCoordinator manages bytecode download workers. Contains ALL business logic previously in ByteCodeDownloader:
  * maintains the pending-task queue, distributes tasks to worker actors, verifies bytecode hashes, stores bytecodes to
  * EvmCodeStorage, reports progress to SNAPSyncController, and handles worker failures.
  *
  * Pekko Typed actor (Group S3). The coordinator spawns Typed `ByteCodeWorker` children and holds typed refs; it
  * receives a sealed `Command` ADT (see Messages.scala — `ByteCodeCoordinatorMessage` extends `Command`).
  * SNAPSyncController is still Classic at S3 time and sends commands via the Classic `!`, routed through a
  * `PropsAdapter` spawn.
  *
  * All mutable state lives on the private `Impl` class; the single behavior `Impl.active()` threads no state through
  * its parameters (the coordinator has one operating phase).
  */
private class ByteCodeCoordinatorImpl(
    context: ActorContext[ByteCodeCoordinator.Command],
    timers: TimerScheduler[ByteCodeCoordinator.Command],
    evmCodeStorage: EvmCodeStorage,
    networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
    requestTracker: SNAPRequestTracker,
    batchSize: Int,
    cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig,
    snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
    backpressureHighWatermark: Int,
    backpressureLowWatermark: Int,
    // Spec 014: shared admission gate (counts codeHashes, not batched tasks). None = not wired (tests).
    intakeBudget: Option[com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget] = None
):

  import ByteCodeCoordinator.*

  private val log = context.log

  // Typed leaf worker (Group W1).
  private type WorkerRef = org.apache.pekko.actor.typed.ActorRef[ByteCodeWorker.Command]

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  // Shadows cooldownConfig.maxInFlightPerPeer so budget updates don't require config mutation.
  private var maxInFlightPerPeer: Int = cooldownConfig.maxInFlightPerPeer

  private val peerFailureCounts = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Int]
  private val peerCooldownUntilMillis = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Long]

  private def nowMillis: Long = System.currentTimeMillis()

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMillis.get(peer.id).exists(_ > nowMillis)

  private def clearPeerFailures(peer: Peer): Unit =
    peerFailureCounts.remove(peer.id)
    peerCooldownUntilMillis.remove(peer.id)

  private def recordPeerCooldown(peer: Peer, base: FiniteDuration, reason: String): FiniteDuration =
    val failures = peerFailureCounts.getOrElse(peer.id, 0) + 1
    peerFailureCounts.update(peer.id, failures)

    // Exponential backoff: base, 2*base, 4*base, ... (capped)
    val exponent = math.min(failures - 1, cooldownConfig.exponentCap)
    val cooldown = (base * (1L << exponent)).min(cooldownConfig.max)
    peerCooldownUntilMillis.update(peer.id, nowMillis + cooldown.toMillis)
    log.debug(s"Cooling down peer ${peer.id.value} for $cooldown ($reason, failures=$failures)")
    cooldown

  // Per-hash failure tracking: prevents infinite re-queuing of hashes no peer can serve.
  // After maxFailuresPerHash attempts across all peers, the hash is skipped with a warning.
  private val hashFailureCounts = mutable.Map.empty[ByteString, Int]
  private val maxFailuresPerHash: Int = 50

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
  private[actors] val pendingTasks = mutable.Queue[ByteCodeTask]()
  final private[actors] case class ActiveByteCodeRequest(
      task: ByteCodeTask,
      worker: WorkerRef,
      peer: Peer,
      requestedBytes: BigInt,
      startedAtMillis: Long
  )

  private[actors] val activeTasks = mutable.Map.empty[BigInt, ActiveByteCodeRequest] // requestId -> active request

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
  private[actors] val workers = mutable.ArrayBuffer[WorkerRef]()
  private val maxWorkers = 32
  private[actors] val idleWorkers = mutable.LinkedHashSet.empty[WorkerRef]

  // Monotonically increasing counter for unique worker child names. Workers can be removed and
  // re-created; using the set size would produce duplicate names when replacements are spawned.
  private var workerSeq: Int = 0

  // Sentinel: when true, no more AddByteCodeTasks will arrive (all accounts downloaded).
  // Completion is only reported after this is set AND pending+active tasks drain.
  // Geth-aligned: coordinators run from start, tasks arrive inline during account download.
  private var noMoreTasksExpected: Boolean = false
  private var bytecodeMilestonePct: Int = -1
  private var bytecodeStartMs: Long = 0L

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
    if requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes then
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id, BigInt(next).min(maxResponseBytes).max(minResponseBytes))

  private def adjustResponseBytesTargetOnFailure(peer: Peer, reason: String): Unit =
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing ByteCodes responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id)} ($reason)"
    )

  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_.peer.id == peer.id)

  // preStart equivalent: record start time, schedule the recurring status pulse, log. Called once by the
  // behavior factory. The Typed `startTimerWithFixedDelay` replaces the Classic Cancellable; it auto-cancels
  // when the behavior stops, so the old `statusTimer` var and `postStop().cancel()` are gone.
  def start(): Behavior[Command] =
    bytecodeStartMs = System.currentTimeMillis()
    timers.startTimerWithFixedDelay(ByteCodeStatusPulse, 30.seconds)
    log.info("ByteCodeCoordinator starting")
    // A new (or supervisor-restarted) instance starts with an empty queue: drop the previous instance's counts.
    intakeBudget.foreach(_.attachByteCodeConsumer())
    active()

  // The single operating behavior. Typed workers STOP on failure (no supervisorStrategy here); that stop is
  // caught by `WorkerTerminated` below via `context.watchWith`, which removes the dead worker and re-queues
  // its in-flight task. PostStop logs final stats (formerly `postStop`).
  def active(): Behavior[Command] = Behaviors
    .receiveMessage[Command] {
      case StartByteCodeSync(codeHashes) =>
        log.info(s"Starting bytecode sync for ${codeHashes.size} unique codeHashes")

        val filteredHashes = filterAndDedupeCodeHashes(codeHashes)
        val newTasks = ByteCodeTask.createBatchedTasks(filteredHashes, batchSize)
        pendingTasks.enqueueAll(newTasks)
        log.info(s"Queued ${newTasks.size} bytecode tasks from ${filteredHashes.size} unique hashes")
        Behaviors.same

      case AddByteCodeTasks(received, skipPresent) =>
        // Replayed (carried / recovered) codeHashes were identified before a restart and many were already fetched.
        // Bytecode is content-addressed and hash-checked on write, so one already in EvmCodeStorage is done: skip it.
        // This lookup used to run on the controller thread (2.5M RocksDB reads per Sepolia resume — the mailbox lag
        // that delayed the back-pressure signal by minutes, spec 014); here it costs this coordinator, not the controller.
        val codeHashes = if skipPresent then received.filter(h => evmCodeStorage.get(h).isEmpty) else received
        val alreadyPresent = received.size - codeHashes.size
        if alreadyPresent > 0 then
          // Count them as done so the progress estimate (which includes every replayed hash) can still reach 100%.
          snapSyncController ! SNAPSyncController.ProgressBytecodesDownloaded(alreadyPresent.toLong)
        val filtered = filterAndDedupeCodeHashes(codeHashes)
        if filtered.nonEmpty then
          val newTasks = ByteCodeTask.createBatchedTasks(filtered, batchSize)
          pendingTasks.enqueueAll(newTasks)
          log.debug(
            s"Added ${newTasks.size} bytecode tasks from ${filtered.size} incremental hashes (pending: ${pendingTasks.size})"
          )
          // Account-range download is the only path that grows the queue faster than dispatch can
          // drain it. Mirrors the storage coordinator's pattern (#1233).
          notifyBackpressureIfChanged()
        intakeBudget.foreach(_.byteCodeReceived(received.size, pendingHashCount))
        Behaviors.same

      case NoMoreByteCodeTasks =>
        noMoreTasksExpected = true
        log.info(s"No more bytecode tasks expected. Pending: ${pendingTasks.size}, active: ${activeTasks.size}")
        checkCompletion()
        Behaviors.same

      case ByteCodePivotRefreshed =>
        // BUG-S1: Do NOT clear knownAvailablePeers — bytecodes are content-addressed (hash-keyed),
        // not state-root-dependent, so existing peers can serve them after a pivot refresh.
        // Clearing the set would force a cold-start re-registration delay.
        log.info(
          s"Pivot refreshed — clearing bytecode peer cooldowns (keeping peer set). " +
            s"Pool state: workers=${workers.size}/$maxWorkers, idle=${idleWorkers.size}, " +
            s"pending=${pendingTasks.size}, active=${activeTasks.size}, knownPeers=${knownAvailablePeers.size}"
        )
        peerFailureCounts.clear()
        peerCooldownUntilMillis.clear()
        peerResponseBytesTarget.clear()
        consecutiveTaskFailures = 0
        // Mirror the storage-side fix (sepolia 2026-05-14 deadlock): if back-pressure was
        // engaged for the old root, the new root is the right time to release it. If the
        // queue is still above the high-water mark, the next AddByteCodeTasks will re-engage.
        if backpressureActive then
          backpressureActive = false
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeBackpressure(false)
          log.info(
            s"ByteCode queue back-pressure RELEASED on pivot refresh (queue depth=${pendingTasks.size}). " +
              s"Will re-engage if queue crosses high-water=$backpressureHighWatermark again."
          )
          snapSyncController ! SNAPSyncController.ByteCodeBackpressureChanged(paused = false)
        tryRedispatchPendingTasks() // cooldowns cleared — attempt dispatch immediately (mirrors Account/Storage/Healing)
        Behaviors.same

      case ByteCodePeerAvailable(peer) =>
        knownAvailablePeers.filterInPlace(_.remoteAddress != peer.remoteAddress)
        knownAvailablePeers += peer
        if isPeerCoolingDown(peer) then log.debug(s"Ignoring ByteCodePeerAvailable(${peer.id.value}) due to cooldown")
        else dispatchIfPossible(peer)
        Behaviors.same

      case ByteCodePeerUnavailable(peerId) =>
        // Peer disconnected — remove from available set and immediately release its in-flight
        // workers so they can be reassigned to other peers without waiting for 30s timeout.
        // Mirrors AccountRangeCoordinator.PeerUnavailable (go-ethereum revertRequests pattern).
        knownAvailablePeers.filterInPlace(_.id.value != peerId)
        peerCooldownUntilMillis.remove(com.chipprbots.ethereum.network.PeerId(peerId))
        val inFlight = activeTasks.collect {
          case (reqId, active) if active.peer.id.value == peerId => (reqId, active.worker, active.task)
        }.toSeq
        if inFlight.nonEmpty then
          val idleBefore = idleWorkers.size
          inFlight.foreach { case (reqId, worker, task) =>
            activeTasks.remove(reqId)
            pendingTasks.enqueue(task.copy(pending = false))
            worker ! ByteCodeWorkerRelease(reqId)
            markWorkerIdle(worker) // restore to idle pool — matches invariant at every other release site
          }
          log.info(
            s"ByteCodePeerUnavailable $peerId: re-queued ${inFlight.size} tasks. " +
              s"Pool: idle $idleBefore → ${idleWorkers.size}, workers=${workers.size}/$maxWorkers"
          )
        tryRedispatchPendingTasks()
        Behaviors.same

      case UpdateMaxInFlightPerPeer(newLimit) =>
        log.info(s"ByteCode per-peer budget: $maxInFlightPerPeer -> $newLimit")
        maxInFlightPerPeer = newLimit
        if newLimit > 0 then tryRedispatchPendingTasks()
        Behaviors.same

      case ByteCodesResponseMsg(response) =>
        handleByteCodesResponse(response)
        Behaviors.same

      case ByteCodeTaskComplete(requestId, result) =>
        activeTasks.remove(requestId).foreach { active =>
          markWorkerIdle(active.worker)
          // A peer that successfully serves any portion of a task gets its failures cleared.
          result.foreach(_ => clearPeerFailures(active.peer))
        }
        result match
          case Right(count) =>
            bytecodesDownloaded += count
            consecutiveTaskFailures = 0
            log.info(s"Bytecode task completed: $count codes")
            checkCompletion()
            tryRedispatchPendingTasks() // worker now idle — dispatch immediately (mirrors Account/Storage/Healing)
          case Left(error) =>
            log.warn(s"Bytecode task failed: $error")
            checkCompletion()
            tryRedispatchPendingTasks()
        Behaviors.same

      case ByteCodeTaskFailed(requestId, error) =>
        activeTasks.remove(requestId).foreach { active =>
          val worker = active.worker
          val peer = active.peer
          log.warn(s"Re-queuing bytecode task after failure: $error")
          pendingTasks.enqueue(active.task.copy(pending = false))
          recordPeerCooldown(peer, cooldownConfig.baseTimeout, s"request failed: $error")
          adjustResponseBytesTargetOnFailure(peer, s"request failed: $error")
          markWorkerIdle(worker)
          consecutiveTaskFailures += 1
          if consecutiveTaskFailures >= maxConsecutiveTaskFailures then
            log.warn(
              s"Force-completing bytecode coordinator after $consecutiveTaskFailures consecutive " +
                s"task failures — SNAP peers not serving data. " +
                s"Missing bytecodes deferred to import-time recovery."
            )
            noMoreTasksExpected = true
        }
        checkCompletion()
        if !noMoreTasksExpected then tryRedispatchPendingTasks() // re-queued task ready — dispatch now
        Behaviors.same

      case ByteCodeCheckCompletion =>
        checkCompletion()
        // Drain side of the back-pressure watermark — release the AccountRangeCoordinator pause
        // once dispatches + completions have shrunk the queue below the low-water mark.
        notifyBackpressureIfChanged()
        Behaviors.same

      case ForceCompleteByteCodes =>
        // #1164: When bytecode sync stagnates (e.g., a small set of code hashes no peer can serve),
        // the existing completion check is unreachable because pendingTasks doesn't drain. Mirrors
        // StorageRangeCoordinator.ForceCompleteStorage. Missing bytecodes can be recovered post-SNAP
        // via BytecodeRecoveryActor.
        val abandonedPending = pendingTasks.size
        val abandonedActive = activeTasks.size
        val abandonedTotal = abandonedPending + abandonedActive
        log.warn(
          s"Force-completing bytecode sync: $bytecodesDownloaded bytecodes downloaded, " +
            s"abandoning $abandonedTotal remaining tasks ($abandonedPending pending, $abandonedActive active). " +
            "Missing bytecodes are fetched on demand at import, and by BytecodeRecoveryActor at the next start (bytecodeRecoveryDone stays unset)."
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
        Behaviors.same

      case WorkerTerminated(worker) =>
        log.warn(
          s"ByteCode worker terminated permanently — removing from pool. " +
            s"Remaining workers: ${workers.size - 1}, idle: ${idleWorkers.size}"
        )
        workers -= worker
        idleWorkers -= worker
        activeTasks.find { case (_, active) => active.worker == worker }.foreach { case (reqId, active) =>
          log.warn(s"Re-queuing bytecode task from terminated worker (${active.task.codeHashes.size} hashes)")
          activeTasks -= reqId
          pendingTasks.enqueue(active.task.copy(pending = false))
        }
        tryRedispatchPendingTasks()
        Behaviors.same

      case ByteCodeGetProgress(replyTo) =>
        val total = completedTaskCount + activeTasks.size.toLong + pendingTasks.size.toLong
        val progress = if total == 0 then 1.0 else completedTaskCount.toDouble / total
        replyTo ! ByteCodeProgress(progress, bytecodesDownloaded, bytesDownloaded)
        Behaviors.same

      case ByteCodeStatusPulse =>
        val total = completedTaskCount + activeTasks.size.toLong + pendingTasks.size.toLong
        val elapsedSecs = (System.currentTimeMillis() - bytecodeStartMs) / 1000.0
        val rate = if elapsedSecs > 0 then (bytecodesDownloaded / elapsedSecs).toLong else 0L
        if noMoreTasksExpected then
          val pct = if total > 0 then ((completedTaskCount.toDouble / total) * 100).toInt else 0
          log.info(
            s"[SNAP-PROGRESS] BYTECODE-COORD $pct% — completed=$completedTaskCount total=$total " +
              s"pending=${pendingTasks.size} active=${activeTasks.size} " +
              s"workers=${workers.size} rate=$rate bytecodes/s"
          )
          val (newM, crossed) =
            com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
              .crossed(completedTaskCount, total, bytecodeMilestonePct)
          bytecodeMilestonePct = newM
          crossed.foreach { m =>
            log.info(
              s"[SNAP-PROGRESS] BYTECODE-COORD MILESTONE $m% — $completedTaskCount / $total bytecodes | $rate bytecodes/s"
            )
          }
        else
          log.info(
            s"[SNAP-PROGRESS] BYTECODE-COORD accumulating — pending=${pendingTasks.size} active=${activeTasks.size} " +
              s"completed=$completedTaskCount workers=${workers.size}"
          )
        Behaviors.same

      // Defensive: `Command` is non-sealed (cross-file constraint), so the compiler cannot prove
      // exhaustiveness. No production sender emits an un-handled Command; treat any as unhandled.
      case other =>
        log.debug(s"ByteCodeCoordinator received unhandled command: $other")
        Behaviors.unhandled
    }
    .receiveSignal { case (_, org.apache.pekko.actor.typed.PostStop) =>
      // Formerly `postStop`: the recurring status timer auto-cancels with the behavior.
      log.info(
        s"ByteCodeCoordinator stopped. Downloaded $bytecodesDownloaded bytecodes" +
          (if bytecodesAbandoned > 0 then s", abandoned $bytecodesAbandoned via force-complete" else "")
      )
      Behaviors.same
    }

  /** Pending work in codeHashes (each queued task batches up to `batchSize`). The queue is bounded by the intake gate,
    * so this walk stays a few thousand tasks long.
    */
  private def pendingHashCount: Long = pendingTasks.iterator.map(_.codeHashes.size.toLong).sum

  /** Emit a ByteCodeBackpressureChanged transition when the pending-task queue depth crosses a watermark. Forwarded by
    * SNAPSyncController to AccountRangeCoordinator as `ByteCodeQueuePressure` so account workers stop producing new
    * bytecode tasks during back-pressure. Mirrors `StorageRangeCoordinator.notifyBackpressureIfChanged` (#1233).
    */
  private def notifyBackpressureIfChanged(): Unit =
    val pending = pendingTasks.size
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeQueueDepth(pending.toLong)
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeInFlightRequests(activeTasks.size)
    intakeBudget.foreach { budget =>
      budget.byteCodeQueueDepth(pendingHashCount)
      budget.publishMetrics()
    }
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeActivePeers(knownAvailablePeers.size)
    if !backpressureActive && pending >= backpressureHighWatermark then
      backpressureActive = true
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeBackpressure(true)
      log.info(
        s"ByteCode queue back-pressure ENGAGED at $pending pending tasks (high-water=$backpressureHighWatermark). " +
          s"Signalling AccountRangeCoordinator to pause dispatch."
      )
      snapSyncController ! SNAPSyncController.ByteCodeBackpressureChanged(paused = true)
    else if backpressureActive && pending <= backpressureLowWatermark then
      backpressureActive = false
      com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setByteCodeBackpressure(false)
      log.info(
        s"ByteCode queue back-pressure RELEASED at $pending pending tasks (low-water=$backpressureLowWatermark). " +
          s"Signalling AccountRangeCoordinator to resume dispatch."
      )
      snapSyncController ! SNAPSyncController.ByteCodeBackpressureChanged(paused = false)

  private def assignTaskToWorker(worker: WorkerRef, peer: Peer): Unit =
    if pendingTasks.nonEmpty then
      // Mark worker busy.
      idleWorkers -= worker

      val task = pendingTasks.dequeue().copy(pending = true)
      // Dispatch drains the queue: let a waiting producer see the room right away.
      intakeBudget.foreach(_.byteCodeQueueDepth(pendingHashCount))
      val requestId = requestTracker.generateRequestId()

      val requestedBytes = responseBytesTargetFor(peer)

      activeTasks.put(
        requestId,
        ActiveByteCodeRequest(task, worker, peer, requestedBytes = requestedBytes, startedAtMillis = nowMillis)
      )

      log.debug(s"Assigning bytecode task (${task.codeHashes.size} hashes) to worker for peer ${peer.id}")
      worker ! ByteCodeWorkerFetchTask(task, peer, requestId, requestedBytes)

  private def dispatchIfPossible(peer: Peer): Unit =
    if pendingTasks.nonEmpty then
      // Keep a small bounded number of inflight requests per peer to maximize throughput
      // without overloading any single neighbor.
      var inflight = inFlightForPeer(peer)
      var blocked = false
      while !blocked && pendingTasks.nonEmpty && inflight < maxInFlightPerPeer do
        val workerOpt: Option[WorkerRef] =
          idleWorkers.headOption.orElse {
            if workers.size < maxWorkers then Some(createWorker()) else None
          }

        workerOpt match
          case Some(worker) =>
            assignTaskToWorker(worker, peer)
            inflight += 1
          case None =>
            log.warn(
              s"ByteCode dispatch blocked: workers=${workers.size}/$maxWorkers, idle=${idleWorkers.size}, " +
                s"pending=${pendingTasks.size}, peer=${peer.id.value}"
            )
            blocked = true

  /** Re-dispatch pending tasks to all known peers. Includes both peers with active tasks and known available peers from
    * PeerAvailable events — handles the activeTasks=empty case that previously blocked dispatch after simultaneous peer
    * cooldowns.
    */
  private def tryRedispatchPendingTasks(): Unit =
    if pendingTasks.nonEmpty then
      val peersFromActive = activeTasks.values.map(_.peer).toSet
      val allKnown = peersFromActive ++ knownAvailablePeers
      for peer <- allKnown if pendingTasks.nonEmpty && !isPeerCoolingDown(peer) do dispatchIfPossible(peer)

  private def handleByteCodesResponse(response: ByteCodes): Unit =
    activeTasks.get(response.requestId) match
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
        validateReturnedCodes(task.codeHashes, response.codes) match
          case Left(error) =>
            log.warn(s"Bytecode verification failed: $error")
            activeTasks.remove(response.requestId)
            pendingTasks.enqueue(task.copy(pending = false))

            // Spec violation or malicious peer - back off harder than empty responses.
            recordPeerCooldown(peer, cooldownConfig.baseInvalid, s"invalid ByteCodes: $error")
            adjustResponseBytesTargetOnFailure(peer, s"invalid response: $error")
            worker ! ByteCodeWorkerRelease(response.requestId)
            markWorkerIdle(worker)
            checkCompletion()

          case Right(validated) =>
            // Store only the returned codes
            storeBytecodesWithHashes(validated.codesByHashInOrder) match
              case Left(error) =>
                log.warn(s"Failed to store bytecodes: $error")
                activeTasks.remove(response.requestId)
                pendingTasks.enqueue(task.copy(pending = false))

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
                if validated.matchedHashes.isEmpty then
                  recordPeerCooldown(peer, cooldownConfig.baseEmpty, "empty ByteCodes response")
                  // Increment per-hash failure counters for all hashes in the task
                  task.codeHashes.foreach { hash =>
                    hashFailureCounts.update(hash, hashFailureCounts.getOrElse(hash, 0) + 1)
                  }
                else
                  clearPeerFailures(peer)
                  // Clear failure counters for successfully received hashes
                  validated.matchedHashes.foreach(hashFailureCounts.remove)

                if remainingHashes.nonEmpty then
                  // Filter out hashes that have exceeded max retries (no peer can serve them)
                  val (exhausted, retryable) = remainingHashes.partition { hash =>
                    hashFailureCounts.getOrElse(hash, 0) >= maxFailuresPerHash
                  }
                  if exhausted.nonEmpty then
                    log.warn(
                      s"Skipping ${exhausted.size} bytecode hashes after $maxFailuresPerHash failures each " +
                        s"(no peer could serve them). Sample: ${exhausted.take(3).map(_.take(4).toArray.map("%02x".format(_)).mkString).mkString(", ")}"
                    )
                    exhausted.foreach(hashFailureCounts.remove)
                  if retryable.nonEmpty then
                    log.info(
                      s"ByteCodes response contained ${validated.matchedHashes.size}/${task.codeHashes.size} requested codes; " +
                        s"re-queuing remaining ${retryable.size} hashes"
                    )
                    pendingTasks.enqueue(ByteCodeTask(retryable))

                val bytecodeCount = response.codes.size
                bytecodesDownloaded += bytecodeCount
                snapSyncController ! SNAPSyncController.ProgressBytecodesDownloaded(bytecodeCount.toLong)
                bytesDownloaded += response.codes.map(_.size.toLong).sum
                consecutiveTaskFailures = 0

                // Only mark the task completed if nothing remains; large batches may be partially served due to bytes.
                if remainingHashes.isEmpty then
                  // task.bytecodes was assigned to the in-flight task purely so the old buffer
                  // could retain it; now that we only track a count, we no longer need to attach
                  // the blob to the task struct — the bytes have already been written via
                  // evmCodeStorage upstream. Skip the assignment to drop the only retention path
                  // for the downloaded code blobs (#1233 follow-up).
                  completedTaskCount += 1L

                activeTasks.remove(response.requestId)
                worker ! ByteCodeWorkerRelease(response.requestId)
                markWorkerIdle(worker)

                log.info(
                  s"Successfully processed $bytecodeCount bytecodes (receivedBytes=$receivedBytes requestedBytes=$requestedBytes)"
                )
                checkCompletion()

  final private case class ValidatedByteCodes(
      matchedHashes: Set[ByteString],
      codesByHashInOrder: Vector[(ByteString, ByteString)]
  )

  private def validateReturnedCodes(
      requestedHashes: Seq[ByteString],
      returnedCodes: Seq[ByteString]
  ): Either[String, ValidatedByteCodes] =
    if returnedCodes.isEmpty then Right(ValidatedByteCodes(Set.empty, Vector.empty))
    else
      val matched = mutable.HashSet.empty[ByteString]
      val seenReturned = mutable.HashSet.empty[ByteString]
      val pairs = Vector.newBuilder[(ByteString, ByteString)]
      var reqIdx = 0

      var error: String | Null = null
      val it = returnedCodes.iterator

      while it.hasNext && error == null do
        val code = it.next()
        val rh = kec256(code)

        if seenReturned.contains(rh) then
          val sample = rh.take(4).toArray.map("%02x".format(_)).mkString
          error = s"Received duplicate bytecode for hash sample=$sample"
        else
          seenReturned += rh

          // Verify returned hashes form a subsequence of the requested hashes, preserving order.
          while reqIdx < requestedHashes.size && requestedHashes(reqIdx) != rh do reqIdx += 1
          if reqIdx >= requestedHashes.size then
            val sample = rh.take(4).toArray.map("%02x".format(_)).mkString
            error = s"Received bytecode hash not present in requested list or out of order (sample=$sample)"
          else
            matched += rh
            pairs += ((rh, code))
            reqIdx += 1

      if error != null then Left(error)
      else Right(ValidatedByteCodes(matched.toSet, pairs.result()))

  private def storeBytecodesWithHashes(codesByHash: Seq[(ByteString, ByteString)]): Either[String, Unit] =
    try
      val updates = codesByHash.foldLeft(evmCodeStorage.emptyBatchUpdate) { case (batchUpdate, (codeHash, code)) =>
        batchUpdate.and(evmCodeStorage.put(codeHash, code))
      }

      updates.commit()
      log.info(s"Successfully persisted ${codesByHash.size} bytecodes to storage")
      Right(())
    catch
      case e: Exception =>
        log.error(s"Failed to store bytecodes: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")

  private def checkCompletion(): Unit =
    if noMoreTasksExpected && pendingTasks.isEmpty && activeTasks.isEmpty then
      log.info("Bytecode sync complete!")
      snapSyncController ! SNAPSyncController.ByteCodeSyncComplete

  private def createWorker(): WorkerRef =
    workerSeq += 1
    val worker: WorkerRef = context.spawn(
      Behaviors
        .supervise(
          ByteCodeWorker(
            coordinator = context.self,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker
          )
        )
        .onFailure[Throwable](SupervisorStrategy.restart.withLimit(5, 1.minute)),
      s"bytecode-worker-$workerSeq",
      org.apache.pekko.actor.typed.Props.empty.withDispatcherFromConfig("sync-dispatcher")
    )
    // Typed watch: on worker stop, deliver WorkerTerminated(worker) instead of a raw Terminated signal.
    context.watchWith(worker, WorkerTerminated(worker))
    workers += worker
    idleWorkers += worker
    log.debug(s"Created bytecode worker, total: ${workers.size}")
    worker

  private def markWorkerIdle(worker: WorkerRef): Unit =
    // Only track workers we created.
    if workers.contains(worker) then idleWorkers += worker

  /** Filter and deduplicate codeHashes. Input is already Bloom-filtered by AccountRangeCoordinator (~0.01% FPR), so
    * this is a final dedup pass over ~2M entries (not 73.5M). Bug 20 fix.
    */
  private def filterAndDedupeCodeHashes(
      codeHashes: Seq[ByteString]
  ): Seq[ByteString] =
    val seen = mutable.HashSet.empty[ByteString]
    var invalidCount = 0
    var dupeCount = 0

    val filtered = codeHashes.filter { codeHash =>
      if codeHash.length != 32 then
        invalidCount += 1
        false
      else if codeHash == Account.EmptyCodeHash.value then false
      else if seen.contains(codeHash) then
        dupeCount += 1
        false
      else
        seen += codeHash
        true
    }

    if invalidCount > 0 then log.warn(s"Dropped $invalidCount codeHashes with non-32-byte length")
    if dupeCount > 0 then log.info(s"Deduplicated $dupeCount codeHashes (Bloom filter false positives)")

    filtered

object ByteCodeCoordinator:

  /** Command protocol for the Typed coordinator (Group S3). All subtypes live in this companion so the trait is sealed
    * — Scala 3 file-scope sealing enables exhaustive match checking at every call site.
    */
  sealed trait Command

  // ── Coordinator Commands (SSC-sent and external) ───────────────────────────

  case class StartByteCodeSync(codeHashes: Seq[ByteString]) extends Command

  /** Incrementally add bytecode download tasks (geth-aligned: inline dispatch from account responses). Coordinator
    * deduplicates and batches these into ByteCodeTasks.
    */
  case class AddByteCodeTasks(codeHashes: Seq[ByteString], skipPresent: Boolean = false) extends Command

  /** Signal that no more bytecode tasks will arrive (all accounts downloaded). Coordinator may now report completion
    * when pending + active tasks drain.
    */
  case object NoMoreByteCodeTasks extends Command

  /** Sent by SNAPSyncController when a fresher pivot has been selected. Bytecodes are content-addressed (hash-keyed) so
    * pivot changes don't invalidate them, but the coordinator should clear stale peer tracking.
    */
  case object ByteCodePivotRefreshed extends Command

  case class ByteCodePeerAvailable(peer: Peer) extends Command
  case class ByteCodePeerUnavailable(peerId: String) extends Command
  case class ByteCodeTaskComplete(requestId: BigInt, result: Either[String, Int]) extends Command
  case class ByteCodeTaskFailed(requestId: BigInt, reason: String) extends Command
  case class ByteCodeGetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[ByteCodeProgress]) extends Command
  case object ByteCodeCheckCompletion extends Command

  /** Sent by SNAPSyncController when bytecode sync has stagnated and must be force-completed (#1164). Missing bytecodes
    * are recovered post-SNAP via BytecodeRecoveryActor.
    */
  case object ForceCompleteByteCodes extends Command

  /** Dynamically adjust per-peer concurrency budget. Sent by SNAPSyncController at phase transitions (Geth-aligned:
    * total 5 requests per peer across all coordinators).
    */
  case class UpdateMaxInFlightPerPeer(newLimit: Int) extends Command

  // ── Internal commands ──────────────────────────────────────────────────────

  // Self-scheduled status pulse (formerly a `case object` self-send on a Classic scheduler).
  private[actors] case object ByteCodeStatusPulse extends Command

  // Delivered by `context.watchWith` when a Typed ByteCodeWorker child stops (replaces the Classic `Terminated`).
  final private[actors] case class WorkerTerminated(
      worker: org.apache.pekko.actor.typed.ActorRef[ByteCodeWorker.Command]
  ) extends Command

  // ── Worker message protocol ────────────────────────────────────────────────

  sealed trait WorkerMessage
  case class FetchByteCodes(task: ByteCodeTask, peer: Peer) extends WorkerMessage
  case class ByteCodeWorkerFetchTask(task: ByteCodeTask, peer: Peer, requestId: BigInt, maxResponseSize: BigInt)
      extends WorkerMessage
  // Sent to BOTH the worker (its own response handling) and the coordinator (SSC forwards it via the Classic `!`),
  // so it is a member of both message sets.
  case class ByteCodesResponseMsg(response: ByteCodes) extends WorkerMessage with Command
  case class ByteCodeRequestTimeout(requestId: BigInt) extends WorkerMessage

  /** Sent by ByteCodeCoordinator to ByteCodeWorker after processing the response. Worker cancels its timeout and
    * transitions from working to idle state without waiting for the 30s timeout.
    */
  case class ByteCodeWorkerRelease(requestId: BigInt) extends WorkerMessage

  // ── Reply / progress types ─────────────────────────────────────────────────

  case class ByteCodeProgress(progress: Double, bytecodesDownloaded: Long, bytesDownloaded: Long)

  final case class ByteCodePeerCooldownConfig(
      baseEmpty: FiniteDuration,
      baseTimeout: FiniteDuration,
      baseInvalid: FiniteDuration,
      maxInFlightPerPeer: Int,
      max: FiniteDuration,
      exponentCap: Int
  )

  object ByteCodePeerCooldownConfig:
    val default: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig(
      baseEmpty = 2.seconds,
      baseTimeout = 10.seconds,
      baseInvalid = 15.seconds,
      // Increase per-peer concurrency (Besu caps peer-wide outstanding at 5; this keeps us competitive).
      maxInFlightPerPeer = 5,
      max = 30.seconds, // aligns with AccountRange (30s) and Healing (30s) — self-heals in 30s not 2min
      exponentCap = 10
    )

  def apply(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      cooldownConfig: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig.default,
      backpressureHighWatermark: Int = 50000,
      backpressureLowWatermark: Int = 25000,
      intakeBudget: Option[com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new ByteCodeCoordinatorImpl(
          context,
          timers,
          evmCodeStorage,
          networkPeerManager,
          requestTracker,
          batchSize,
          cooldownConfig,
          snapSyncController,
          backpressureHighWatermark = backpressureHighWatermark,
          backpressureLowWatermark = backpressureLowWatermark,
          intakeBudget = intakeBudget
        ).start()
      }
    }
