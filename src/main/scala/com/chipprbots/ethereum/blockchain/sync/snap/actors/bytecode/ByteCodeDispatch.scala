package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ActiveByteCodeRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ByteCodeWorkerFetchTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.WorkerTerminated
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId

/** The coordinator state `ByteCodeDispatch` reads and writes (spec 016 C-B1, research.md R14b: 9). The core's behaviour
  * arms touch every one of them too, so the Impl keeps them as fields.
  */
private[actors] trait ByteCodeDispatchState:
  def activeTasks: mutable.Map[BigInt, ActiveByteCodeRequest]
  def idleWorkers: mutable.LinkedHashSet[WorkerRef]
  def knownAvailablePeers: mutable.Set[Peer]
  def maxInFlightPerPeer: Int
  def peerCooldownUntilMillis: mutable.Map[PeerId, Long]
  def peerFailureCounts: mutable.Map[PeerId, Int]
  def peerResponseBytesTarget: mutable.Map[PeerId, BigInt]
  def pendingTasks: mutable.Queue[ByteCodeTask]
  def workers: mutable.ArrayBuffer[WorkerRef]

/** What `ByteCodeResponseHandling` calls in `ByteCodeDispatch`: the clock, the peer cooldowns, the per-peer response
  * budget and the worker pool's idle set.
  */
private[actors] trait ByteCodeDispatchApi:
  def nowMillis: Long
  def clearPeerFailures(peer: Peer): Unit
  def recordPeerCooldown(peer: Peer, base: FiniteDuration, reason: String): FiniteDuration
  def responseBytesTargetFor(peer: Peer): BigInt
  def adjustResponseBytesTargetOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit
  def adjustResponseBytesTargetOnFailure(peer: Peer, reason: String): Unit
  def markWorkerIdle(worker: WorkerRef): Unit

/** What `ByteCodeDispatch` calls in the coordinator core: the queue's pending work in codeHashes. */
private[actors] trait ByteCodeQueueApi:
  def pendingHashCount: Long

/** Bytecode dispatch (spec 016 C-B1, coordinator-characterization.md §3.1): per-peer cooldowns with exponential
  * backoff, the per-peer `responseBytes` budget (Nethermind-style, grown on a full response and halved on a failure),
  * and the worker pool: assigning a pending task to an idle or new worker, the per-peer in-flight cap, and redispatch
  * to every known peer. The coordinator core keeps the behaviour arms that call it.
  */
private[actors] trait ByteCodeDispatch:
  self: ByteCodeCoordinatorImpl =>

  def nowMillis: Long = System.currentTimeMillis()

  def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMillis.get(peer.id).exists(_ > nowMillis)

  def clearPeerFailures(peer: Peer): Unit =
    peerFailureCounts.remove(peer.id)
    peerCooldownUntilMillis.remove(peer.id)

  def recordPeerCooldown(peer: Peer, base: FiniteDuration, reason: String): FiniteDuration =
    val failures = peerFailureCounts.getOrElse(peer.id, 0) + 1
    peerFailureCounts.update(peer.id, failures)

    // Exponential backoff: base, 2*base, 4*base, ... (capped)
    val exponent = math.min(failures - 1, cooldownConfig.exponentCap)
    val cooldown = (base * (1L << exponent)).min(cooldownConfig.max)
    peerCooldownUntilMillis.update(peer.id, nowMillis + cooldown.toMillis)
    log.debug(s"Cooling down peer ${peer.id.value} for $cooldown ($reason, failures=$failures)")
    cooldown

  // Monotonically increasing counter for unique worker child names. Workers can be removed and
  // re-created; using the set size would produce duplicate names when replacements are spawned.
  private var workerSeq: Int = 0

  // ByteCodes request tuning (Nethermind-style): use a per-peer dynamic byte budget, clamped hard to 2 MiB.
  // We send many hashes and rely on the peer-side `responseBytes` soft limit to bound work.
  private lazy val minResponseBytes: BigInt = 50 * 1024
  private lazy val maxResponseBytes: BigInt = 2 * 1024 * 1024
  private lazy val initialResponseBytes: BigInt = 512 * 1024
  private lazy val increaseFactor: Double = 1.25
  private lazy val decreaseFactor: Double = 0.5

  def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget.getOrElseUpdate(peer.id, initialResponseBytes).max(minResponseBytes).min(maxResponseBytes)

  def adjustResponseBytesTargetOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    // If we appear to be filling the current budget, try increasing (up to clamp).
    // This mimics Nethermind's approach of probing larger budgets on responsive peers.
    if requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes then
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id, BigInt(next).min(maxResponseBytes).max(minResponseBytes))

  def adjustResponseBytesTargetOnFailure(peer: Peer, reason: String): Unit =
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing ByteCodes responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id)} ($reason)"
    )

  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_.peer.id == peer.id)

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

  def dispatchIfPossible(peer: Peer): Unit =
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
  def tryRedispatchPendingTasks(): Unit =
    if pendingTasks.nonEmpty then
      val peersFromActive = activeTasks.values.map(_.peer).toSet
      val allKnown = peersFromActive ++ knownAvailablePeers
      for peer <- allKnown if pendingTasks.nonEmpty && !isPeerCoolingDown(peer) do dispatchIfPossible(peer)

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

  def markWorkerIdle(worker: WorkerRef): Unit =
    // Only track workers we created.
    if workers.contains(worker) then idleWorkers += worker
