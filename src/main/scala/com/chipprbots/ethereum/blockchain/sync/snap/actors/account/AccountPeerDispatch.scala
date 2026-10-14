package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.PivotStateUnservable
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.CheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.FetchAccountRange
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.RecheckIntakeGate
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerTerminated
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeWorker
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** The coordinator state `AccountPeerDispatch` reads and writes (spec 016 C-A1, research.md R14b: 17). The core's
  * behaviour arms, or the account-task lifecycle that C-A2 will move, touch every one of them too, so the Impl keeps
  * them as fields.
  */
private[actors] trait AccountPeerDispatchState:
  def stateRoot: ByteString
  def maxInFlightPerPeer: Int
  def backpressureSources: mutable.Set[String]
  def statelessPeers: mutable.Set[PeerId]
  def snaplessPeers: mutable.Set[PeerId]
  def emptyResponseStrikes: mutable.Map[PeerId, Int]
  var pivotRefreshRequested: Boolean
  def pendingTasks: mutable.PriorityQueue[AccountTask]
  def activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]
  def workers: mutable.ArrayBuffer[WorkerRef]
  def idleWorkers: mutable.LinkedHashSet[WorkerRef]
  var lastDispatchOrResponseMs: Long
  def knownAvailablePeers: mutable.Set[Peer]
  def peerResponseBytesTarget: mutable.Map[String, BigInt]
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def lastDispatchTimeMs: mutable.Map[String, Long]
  var consecutiveUnproductiveRefreshes: Int

/** What `AccountPeerDispatch` calls outside itself: the redispatch of pending tasks to every eligible peer, which
  * re-engages dispatch when the last back-pressure source releases. The coordinator core provides it until C-A2 moves
  * it into `AccountTaskLifecycle`.
  */
private[actors] trait AccountTaskLifecycleApi:
  def tryRedispatchPendingTasks(): Unit

/** What `AccountTaskLifecycle` (C-A2) calls in `AccountPeerDispatch`: the stateless strikes and their reset, the
  * cooldowns, the per-peer response budget, and the worker pool's idle set, in-flight count and dispatch.
  */
private[actors] trait AccountPeerDispatchApi:
  def isPeerStateless(peer: Peer): Boolean
  def isPeerSnapless(peer: Peer): Boolean
  def markPeerStateless(peer: Peer, reason: String): Unit
  def recordPeerSuccess(peerId: PeerId): Unit
  def effectivePeerCooldown: FiniteDuration
  def isPeerCoolingDown(peer: Peer): Boolean
  def recordPeerCooldown(peer: Peer, reason: String, duration: FiniteDuration): Unit
  def responseBytesTargetFor(peer: Peer): BigInt
  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit
  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit
  def inFlightForPeer(peer: Peer): Int
  def markWorkerIdle(worker: WorkerRef): Unit
  def dispatchIfPossible(peer: Peer): Unit

/** What `AccountTrieAssembly` (C-A3) calls in `AccountPeerDispatch`: the active peer count its progress log reports. */
private[actors] trait AccountActivePeersApi:
  def activePeerCount: Int

/** What `ContractWorkCarry` (C-A4) calls in `AccountPeerDispatch`: the downstream back-pressure and the spec 014 intake
  * gate, read synchronously before the carried replay pushes contract work downstream.
  */
private[actors] trait AccountIntakeGateApi:
  def downstreamBackpressureActive: Boolean
  def intakeGateClosed: Boolean

/** Account-range peer dispatch (spec 016 C-A1, coordinator-characterization.md §3.2): the #1501 intake gate and the
  * downstream back-pressure that pause new dispatch; stateless/snapless peer tracking with its empty-proof strikes and
  * the pivot-refresh escalation it drives; the per-peer cooldowns (scaled down on a scarce pool); the per-peer
  * `responseBytes` budget; and the worker pool: the per-peer in-flight cap, worker creation and dispatch of the next
  * pending task. The coordinator core keeps the behaviour arms that call it.
  */
private[actors] trait AccountPeerDispatch
    extends AccountPeerDispatchApi
    with AccountActivePeersApi
    with AccountIntakeGateApi:
  self: AccountPeerDispatchState & AccountRangeEnv & AccountTaskLifecycleApi =>

  def downstreamBackpressureActive: Boolean = backpressureSources.nonEmpty
  // The intake gate (spec 014): pending storage/bytecode work at its ceiling, or heap pressure. Read synchronously, so
  // unlike `backpressureSources` it never lags behind a busy mailbox.
  def intakeGateClosed: Boolean = intakeBudget.exists(!_.intakeAllowed)

  /** The intake gate has no release message (unlike the storage/bytecode watermarks, which send
    * `*QueuePressure(false)`): the bytecode ceiling and the heap watchdog close it silently and it re-opens when the
    * downstream queues drain. Poll it while it holds dispatch so the account side resumes on its own, not only when a
    * peer is re-announced or a response lands.
    */
  def armIntakeGateRecheck(): Unit =
    if !timers.isTimerActive(RecheckIntakeGate) then
      timers.startSingleTimer(RecheckIntakeGate, RecheckIntakeGate, AccountRangeCoordinator.IntakeGateRecheckInterval)
  // Dispatch is paused on purpose: a downstream queue is over its high-water mark, or the controller set the per-peer
  // budget to 0. Neither is a stall, so neither may feed the stall watchdog or the pivot-refresh escalation.
  def dispatchDeliberatelyPaused: Boolean =
    downstreamBackpressureActive || intakeGateClosed || maxInFlightPerPeer <= 0
  // Kept for spec compatibility; reflects whether the storage source is currently engaged.
  private[actors] def storageBackpressureActive: Boolean = backpressureSources.contains("storage")

  // Raised 3 → 5 on 2026-05-14: even with PR #1255 clearing snapless on PivotRefreshed,
  // the 3-strike threshold drained sepolia's small peer pool between pivots, producing
  // eligible=0 stalls within each pivot window. 5 strikes gives peers more rope; mirrors
  // Nethermind's threshold; lines up with the bumped storage threshold.
  private lazy val EmptyResponseStrikeThreshold: Int = 5

  def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id)

  def isPeerSnapless(peer: Peer): Boolean =
    snaplessPeers.contains(peer.id)

  def markPeerStateless(peer: Peer, reason: String): Unit =
    val isEmptyProofSignal = reason.contains("Missing proof for empty account range")
    // Only empty-proof signals drive strikes; already-confirmed peers stay confirmed
    // (further strikes are noise).
    if isEmptyProofSignal && !snaplessPeers.contains(peer.id) then
      val priorStrikes = emptyResponseStrikes.getOrElse(peer.id, 0)
      val strikes = priorStrikes + 1
      emptyResponseStrikes(peer.id) = strikes

      if strikes < EmptyResponseStrikeThreshold then
        log.info(
          s"Peer ${peer.id.value} empty-proof strike $strikes/$EmptyResponseStrikeThreshold for root " +
            s"${stateRoot.take(4).toHex} (reason: $reason). Still eligible for dispatch."
        )
      else
        // Threshold reached — promote to confirmed snapless + stateless. Snapless is sticky
        // (root-independent, survives PivotRefreshed per #1197); stateless clears on the next
        // PivotRefreshed.
        val wasSnapless = snaplessPeers.contains(peer.id)
        val wasStateless = statelessPeers.contains(peer.id)
        snaplessPeers.add(peer.id)
        statelessPeers.add(peer.id)
        if !wasSnapless then
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementSnaplessPeerConfirmed()
        if !wasStateless then
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementStatelessPeerConfirmed()
        log.info(
          s"Peer ${peer.id.value} marked SNAPLESS after $strikes consecutive empty-proof responses " +
            s"— will skip for GetAccountRange this session. Bytecode/healing remain available. " +
            s"(${statelessPeers.size}/${knownAvailablePeers.size} peers stateless for root ${stateRoot.take(4).toHex})"
        )
        maybeRequestPivotRefresh()

  /** Reset the strike counter for a peer that has just produced a useful response (real accounts OR a boundary proof).
    * Cheap to over-invoke; the goal is "any forward progress from this peer wipes prior strikes."
    */
  def recordPeerSuccess(peerId: com.chipprbots.ethereum.network.PeerId): Unit =
    emptyResponseStrikes.remove(peerId)

  def maybeRequestPivotRefresh(): Unit =
    if !pivotRefreshRequested then
      // Snapless peers (no snapshot tree at all) cannot be rescued by a pivot refresh — the
      // refresh would just yield another empty response from the same peer at the new root.
      // Compute "all stateless" against the *non-snapless* subset only (#1197).
      val nonSnapless = knownAvailablePeers.filterNot(p => snaplessPeers.contains(p.id))
      if nonSnapless.isEmpty && knownAvailablePeers.nonEmpty then
        // Every peer in the pool is snapless. A pivot refresh won't recover the SAME peers
        // (they have no snapshot tree regardless of root), but escalating PivotStateUnservable
        // lets the controller take action — e.g. disconnect the snapless peer and wait for a
        // peer with a snapshot tree. Without escalation the coordinator is permanently stuck.
        pivotRefreshRequested = true
        lastPivotRefreshTimeMs = System.currentTimeMillis()
        consecutiveUnproductiveRefreshes += 1
        log.warn(
          s"All ${knownAvailablePeers.size} known peers are SNAPLESS (no snapshot tree). " +
            "SNAP-range download cannot make progress on this peer pool. " +
            s"Requesting pivot refresh from controller (attempt=$consecutiveUnproductiveRefreshes). " +
            "Bytecode and trie-node healing remain functional."
        )
        snapSyncController ! PivotStateUnservable(
          rootHash = stateRoot,
          reason = "all peers snapless (no snapshot tree) for AccountRange root",
          consecutiveEmptyResponses = knownAvailablePeers.size,
          cause = SNAPSyncController.UnservableCause.Snapless
        )
      else
        // If all NON-snapless peers are stateless, the current root has aged out of the
        // serve window — pivot refresh might rescue them.
        val allStateless = nonSnapless.nonEmpty &&
          nonSnapless.forall(p => statelessPeers.contains(p.id))
        if allStateless then
          // Exponential backoff: don't hammer the controller with rapid refresh requests
          val now = System.currentTimeMillis()
          val backoffMs = math.min(
            minRefreshIntervalMs * (1L << math.min(consecutiveUnproductiveRefreshes, 3)),
            maxRefreshIntervalMs
          )
          val elapsed = now - lastPivotRefreshTimeMs
          if lastPivotRefreshTimeMs > 0 && elapsed < backoffMs then
            log.info(
              s"All ${statelessPeers.size} peers stateless but backing off pivot refresh " +
                s"(${elapsed / 1000}s / ${backoffMs / 1000}s elapsed, attempt=${consecutiveUnproductiveRefreshes + 1}). " +
                "Will retry after backoff."
            )
            // Schedule a re-check after the remaining backoff period
            ctx.scheduleOnce((backoffMs - elapsed).millis, ctx.self, CheckCompletion)
          else
            pivotRefreshRequested = true
            lastPivotRefreshTimeMs = now
            consecutiveUnproductiveRefreshes += 1
            log.warn(
              s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
                s"Requesting pivot refresh from controller (attempt=$consecutiveUnproductiveRefreshes, backoff=${backoffMs / 1000}s)."
            )
            snapSyncController ! PivotStateUnservable(
              rootHash = stateRoot,
              reason = "all peers stateless for AccountRange root",
              consecutiveEmptyResponses = statelessPeers.size,
              cause = SNAPSyncController.UnservableCause.Stateless
            )

  // Monotonically increasing counter for unique worker child names. Workers can be removed and
  // re-created; using the set size would produce duplicate names when replacements are spawned.
  private var workerSeq: Int = 0

  /** Count in-flight requests for a given peer (pipelining support). */
  def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_._3.id == peer.id)

  /** Number of active (non-stateless, non-snapless, non-cooling-down) snap-capable peers. Returns the actual count — no
    * floor — so the progress log truthfully reports 0 when all peers are demoted (the case that drives the account
    * stall). Previously this had `.max(1)` which masked the stall: progress log said "1 peers" while dispatch was
    * starving on zero eligible peers. See PR-1 of `this-is-the-same-fluttering-eagle.md`.
    */
  def activePeerCount: Int =
    knownAvailablePeers.count(p => !isPeerStateless(p) && !isPeerSnapless(p) && !isPeerCoolingDown(p))

  // Per-peer adaptive byte budgeting (ported from StorageRangeCoordinator).
  // Geth's snap handler supports up to 2MB responses. Starting at 512KB and probing upward
  // on responsive peers, scaling down on failures.
  private lazy val minResponseBytes: BigInt = BigInt(minResponseBytesConfig)
  private lazy val maxResponseBytes: BigInt = 2 * 1024 * 1024 // 2MB ceiling (Geth handler limit)
  private lazy val initialResponseBytes: BigInt = BigInt(initialResponseBytesConfig)
  private lazy val increaseFactor: Double = 1.25 // Scale up when 90%+ fill
  private lazy val decreaseFactor: Double = 0.5 // Scale down on failure/empty

  def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget
      .getOrElseUpdate(peer.id.value, initialResponseBytes)
      .max(minResponseBytes)
      .min(maxResponseBytes)

  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    if requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes then
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))

  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit =
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing account responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )

  private lazy val peerCooldownDefault = 30.seconds
  // Peer-scarcity threshold and the shorter cooldown applied below it. On a 1-3 peer pool a fixed 30s cooldown after a
  // single timeout benches ~half the usable peers; two near-simultaneous timeouts can zero the eligible set for 30s.
  // Scaling the cooldown down to a few seconds when peers are scarce keeps the pipe fed without giving up the
  // back-off's purpose (briefly resting a peer that just failed). Abundant pools keep the full 30s.
  private lazy val peerScarcityThreshold = 3
  private lazy val peerCooldownScarce = 8.seconds
  def effectivePeerCooldown: FiniteDuration =
    if knownAvailablePeers.size <= peerScarcityThreshold then peerCooldownScarce else peerCooldownDefault

  def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  def recordPeerCooldown(peer: Peer, reason: String, duration: FiniteDuration): Unit =
    val until = System.currentTimeMillis() + duration.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.info(s"[ACCOUNT-COOLDOWN] peer ${peer.id.value.take(8)} cooling ${duration.toSeconds}s: $reason")

  private var lastPivotRefreshTimeMs: Long = 0L
  private lazy val minRefreshIntervalMs: Long = 60000L // 60s minimum between refreshes
  private lazy val maxRefreshIntervalMs: Long = 300000L // 5min ceiling

  // Cap total workers to activePeerCount * maxInFlightPerPeer — enough to saturate all peers.
  // Dynamic: use current SNAP peer count instead of the creation-time concurrency value, so
  // coordinators started with 1 peer can scale up to 30 workers when 6 peers arrive post-pivot.
  // go-ethereum assigns to ALL idle peers simultaneously with no coordinator-level cap.
  private def maxWorkers: Int =
    math.max(concurrency, knownAvailablePeers.count(!isPeerStateless(_))) * maxInFlightPerPeer

  private def createWorker(): WorkerRef =
    workerSeq += 1
    val worker: WorkerRef = ctx.spawn(
      Behaviors
        .supervise(
          AccountRangeWorker(
            coordinator = ctx.self,
            networkPeerManager = networkPeerManager,
            requestTracker = requestTracker
          )
        )
        .onFailure[Throwable](SupervisorStrategy.restart.withLimit(5, 1.minute)),
      s"account-range-worker-$workerSeq",
      org.apache.pekko.actor.typed.Props.empty.withDispatcherFromConfig("sync-dispatcher")
    )
    // Typed death watch — delivers WorkerTerminated(worker) to our mailbox if the worker stops.
    ctx.watchWith(worker, WorkerTerminated(worker))
    workers += worker
    idleWorkers += worker
    log.debug(s"Created worker ${worker.path.name}, total workers: ${workers.size}")
    worker

  def markWorkerIdle(worker: WorkerRef): Unit =
    if workers.contains(worker) then idleWorkers += worker

  /** Dispatch up to maxInFlightPerPeer tasks to the given peer (pipelining). Mirrors
    * ByteCodeCoordinator.dispatchIfPossible — the proven pattern for SNAP sync.
    *
    * No-op while any downstream coordinator (storage OR bytecode) is over its high-water mark. Workers already in
    * flight always run to completion, so existing work continues to drain, but we stop producing new tasks (which would
    * in turn enqueue more storage / bytecode work) until every signalling downstream has released.
    */
  def dispatchIfPossible(peer: Peer): Unit =
    if pendingTasks.nonEmpty && intakeGateClosed then armIntakeGateRecheck()
    if pendingTasks.nonEmpty && !downstreamBackpressureActive && !intakeGateClosed then
      var inflight = inFlightForPeer(peer)
      var noWorkerAvailable = false
      while !noWorkerAvailable && pendingTasks.nonEmpty && inflight < maxInFlightPerPeer do
        val workerOpt: Option[WorkerRef] =
          idleWorkers.headOption.orElse {
            if workers.size < maxWorkers then Some(createWorker()) else None
          }

        workerOpt match
          case Some(worker) =>
            dispatchNextTaskToWorker(worker, peer)
            inflight += 1
          case None =>
            noWorkerAvailable = true

  /** Internal: record a back-pressure transition from one named downstream and re-engage dispatch once every signalling
    * source has released. ANY-OF semantics: pause while at least one source is engaged; resume only when the set is
    * fully empty.
    */
  def applyBackpressureChange(source: String, paused: Boolean): Unit =
    val wasActive = downstreamBackpressureActive
    if paused then backpressureSources += source else backpressureSources -= source
    val nowActive = downstreamBackpressureActive
    if wasActive == nowActive then
      // Either a duplicate transition for the same source or a partial release that left another
      // source still engaged. No state change worth logging at INFO.
      log.debug(
        s"Back-pressure source '$source' set paused=$paused; active sources now: ${backpressureSources.mkString(",")}"
      )
    else if nowActive then
      log.info(
        s"Downstream back-pressure ENGAGED (source=$source) — pausing new account-range dispatches. " +
          s"${pendingTasks.size} pending, ${activeTasks.size} in flight (will complete normally)."
      )
    else
      log.info(
        s"Downstream back-pressure RELEASED (source=$source was the last engaged source) — resuming. " +
          s"${pendingTasks.size} pending."
      )
      tryRedispatchPendingTasks()
      knownAvailablePeers.filterNot(isPeerStateless).foreach(dispatchIfPossible)

  private def dispatchNextTaskToWorker(worker: WorkerRef, peer: Peer): Unit =
    if pendingTasks.nonEmpty then
      // Mark worker busy
      idleWorkers -= worker

      val task = pendingTasks.dequeue().copy(pending = true)

      val requestId = requestTracker.generateRequestId()
      activeTasks.put(requestId, (task, worker, peer))
      val responseBytes = responseBytesTargetFor(peer)

      worker ! FetchAccountRange(task, peer, requestId, responseBytes)
      // #1184: progress signal — used by CheckDispatchStalled.
      lastDispatchOrResponseMs = System.currentTimeMillis()
      lastDispatchTimeMs.update(peer.id.value, lastDispatchOrResponseMs)
