package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode.*
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
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
private[actors] class ByteCodeCoordinatorImpl(
    val context: ActorContext[ByteCodeCoordinator.Command],
    timers: TimerScheduler[ByteCodeCoordinator.Command],
    val stateIngest: StateIngest,
    val networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    batchSize: Int,
    val cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig,
    val snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
    backpressureHighWatermark: Int,
    backpressureLowWatermark: Int,
    // Spec 014: shared admission gate (counts codeHashes, not batched tasks). None = not wired (tests).
    val intakeBudget: Option[com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget] = None
) extends ByteCodeDispatch
    with ByteCodeResponseHandling
    with ByteCodeEnv
    with ByteCodeDispatchState
    with ByteCodeResponseHandlingState
    with ByteCodeQueueApi
    with ByteCodeCompletionApi:

  import ByteCodeCoordinator.*

  val log = context.log

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  // Shadows cooldownConfig.maxInFlightPerPeer so budget updates don't require config mutation.
  var maxInFlightPerPeer: Int = cooldownConfig.maxInFlightPerPeer

  val peerFailureCounts = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Int]
  val peerCooldownUntilMillis = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Long]

  // Per-hash failure tracking: prevents infinite re-queuing of hashes no peer can serve.
  // After maxFailuresPerHash attempts across all peers, the hash is skipped with a warning.
  val hashFailureCounts = mutable.Map.empty[ByteString, Int]

  // Track known available peers for redispatch when activeTasks is empty.
  // tryRedispatchPendingTasks() previously derived peers from activeTasks.values, leaving it
  // unable to dispatch when activeTasks was empty (all peers in cooldown). This caused an
  // unnecessary 2-minute stall window after simultaneous peer failures.
  // Deduped by remoteAddress — same pattern as TrieNodeHealingCoordinator.
  val knownAvailablePeers = mutable.Set[Peer]()

  // Consecutive task failure guard: if all peers repeatedly fail with no progress,
  // force-complete bytecode sync rather than hanging indefinitely. Threshold 100 matches
  // april-confluence production tuning. Missing bytecodes are recovered per-block during import.
  var consecutiveTaskFailures: Int = 0
  private val maxConsecutiveTaskFailures: Int = 100

  // Task management
  val pendingTasks = mutable.Queue[ByteCodeTask]()

  val activeTasks = mutable.Map.empty[BigInt, ActiveByteCodeRequest] // requestId -> active request

  // Completion bookkeeping (#1233). Previously `completedTasks: mutable.ArrayBuffer[ByteCodeTask]`
  // retained the full task object — including `task.bytecodes: Seq[ByteString]`, the actual
  // downloaded bytecode blobs — forever, just to read `.size` for progress. At ~3M sepolia
  // codehashes and ~5-10 KB per blob that's ~2-5 GB of retention; on ETH mainnet (~73 M unique
  // hashes per memory) the same pattern would leak 20-40+ GB. Replaced with a plain Long counter;
  // blobs are no longer reachable after the per-hash write commits.
  var completedTaskCount: Long = 0L

  // Back-pressure state. Set true on the high-water transition; cleared on low-water. Workers
  // already in flight always continue to completion; only AccountRangeCoordinator dispatch is
  // gated via the forwarded `ByteCodeQueuePressure` signal.
  private[actors] var backpressureActive: Boolean = false

  // Worker pool
  val workers = mutable.ArrayBuffer[WorkerRef]()
  val maxWorkers = 32
  val idleWorkers = mutable.LinkedHashSet.empty[WorkerRef]

  // Sentinel: when true, no more AddByteCodeTasks will arrive (all accounts downloaded).
  // Completion is only reported after this is set AND pending+active tasks drain.
  // Geth-aligned: coordinators run from start, tasks arrive inline during account download.
  private var noMoreTasksExpected: Boolean = false
  private var bytecodeMilestonePct: Int = -1
  private var bytecodeStartMs: Long = 0L

  // Statistics
  var bytecodesDownloaded: Long = 0
  var bytesDownloaded: Long = 0
  // #1164: number of bytecode hashes abandoned via ForceCompleteByteCodes. Surfaced in postStop()
  // diagnostics so operators can correlate force-completes with later BytecodeRecoveryActor activity.
  private var bytecodesAbandoned: Long = 0

  val peerResponseBytesTarget = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, BigInt]

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
        val codeHashes = skipPresentCodeHashes(received, skipPresent)
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
  def pendingHashCount: Long = pendingTasks.iterator.map(_.codeHashes.size.toLong).sum

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

  def checkCompletion(): Unit =
    if noMoreTasksExpected && pendingTasks.isEmpty && activeTasks.isEmpty then
      log.info("Bytecode sync complete!")
      snapSyncController ! SNAPSyncController.ByteCodeSyncComplete

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

  // Typed leaf worker (Group W1).
  private[actors] type WorkerRef = org.apache.pekko.actor.typed.ActorRef[ByteCodeWorker.Command]

  final private[actors] case class ActiveByteCodeRequest(
      task: ByteCodeTask,
      worker: WorkerRef,
      peer: Peer,
      requestedBytes: BigInt,
      startedAtMillis: Long
  )

  final private[actors] case class ValidatedByteCodes(
      matchedHashes: Set[ByteString],
      codesByHashInOrder: Vector[(ByteString, ByteString)]
  )

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
      stateIngest: StateIngest,
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
          stateIngest,
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
