package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.AdaptiveResponseBytes
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingRequestTimeout
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes

/** The coordinator state `HealDispatch` reads and writes (spec 016 C-H4, research.md R14b). The core's behaviour arms
  * (`healDispatchArms`, the stagnation pulse, the pivot re-peg), the response processing C-H5 will move, or rule (d)
  * keeps them in the core, so the Impl keeps them as fields. The dispatch writes `pivotRefreshRequested` and
  * `pivotRefreshRequestedAt` (the stagnation escalation in `handleTimeout`), `lastHealedAtMs` (its re-fire guard),
  * `healRate` and `lastThrottleAdjustMs` (the throttle update), and mutates the task queue, the in-flight map, the
  * per-peer cooldown and `responseBytes` maps and the attempt counters; it reads the rest. It does not touch the live
  * heal requests (spec 022 D2): a request stays live while it is in flight, so dispatching or re-queueing it changes
  * nothing there. `lastThrottleAdjustMs` is touched only by the dispatch, but its initializer is not a literal, so rule
  * (d) keeps it in the core; `maxConcurrentRequests`, `rawFlushThreshold` and `healingStagnationTimeoutMs` are
  * immutable vals rule (d) keeps in the core.
  */
private[actors] trait HealDispatchState:
  def stateRoot: ByteString
  def serveRoot: ByteString
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def maxInFlightPerPeer: Int
  def maxConcurrentRequests: Int
  var pivotRefreshRequested: Boolean
  var pivotRefreshRequestedAt: Long
  def statelessPeers: mutable.Set[String]
  def knownAvailablePeers: mutable.Set[Peer]
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def peerResponseBytesTarget: mutable.Map[String, BigInt]
  def healAttempts: HealAttemptMap
  var healRate: Double
  var lastThrottleAdjustMs: Long
  def rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])]
  def rawFlushThreshold: Int
  var lastHealedAtMs: Long
  def healingStagnationTimeoutMs: Long
  def totalNodesHealed: Int

/** What the response processing (C-H5) calls in `HealDispatch`: the spec 004 unservable-attempt surfacing of a task a
  * response left unsatisfied, the per-peer `responseBytes` budget (read for the log line, grown on a served response,
  * halved on an empty one), the cooldown of a peer that answered empty, the pipelined dispatch to the peer that just
  * answered and the throttle update.
  */
private[actors] trait HealDispatchApi:
  def noteUnservableAttempt(hash: ByteString): Unit
  def responseBytesTargetFor(peer: Peer): BigInt
  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit
  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit
  def recordPeerCooldown(peer: Peer, reason: String): Unit
  def dispatchIfPossible(peer: Peer): Unit
  def updateHealThrottle(delivered: Int, elapsedMs: Long): Unit

/** TrieNodeHealingCoordinator's heal dispatch (spec 016 C-H4, coordinator-characterization.md §3.4): the adaptive
  * throttle (`updateHealThrottle`, `effectiveBatchSize`), the per-peer `responseBytes` budget, the per-peer cooldowns
  * and in-flight count, building and sending `GetTrieNodes` (`requestNextBatch`, against the walk, serve or moving
  * root), the pipelined dispatch to one peer (`dispatchIfPossible`) and to every eligible peer with the `[HEAL-FLOOR]`
  * peer-scarce revive (`tryRedispatchPendingTasks`), the request timeout (`handleTimeout`: the cooldown, the halved
  * budget, the re-queue and the stagnation escalation), the spec 004 unservable-attempt surfacing
  * (`noteUnservableAttempt`) and the root gauge label (`shortRootLabel`). The coordinator core keeps the behaviour arms
  * that call it (`healDispatchArms`).
  */
private[actors] trait HealDispatch extends HealDispatchApi:
  this: HealDispatchState & TrieNodeHealingEnv & FrontierGaugesApi =>

  private var healThrottle: Double = 1.0 // divisor (1 = full speed, 4096 = one node at a time)
  private var healPending: Long = 0 // nodes queued for DB write (rawNodeBuffer.size)

  private lazy val ThrottleIncrease = 1.33
  private lazy val ThrottleDecrease = 1.25
  // MaxThrottle caps the divisor on `batchSize` (default 32). With MaxThrottle=4 the floor
  // is 32/4 = 8 paths per GetTrieNodes request, well above the previous floor of 2 that
  // throttled healing to ~6 nodes/sec on Mordor (issue #1159). The cap is high enough to
  // brake hard if the disk-flush thread genuinely can't keep up, but doesn't permanently
  // pin batches at a wire-inefficient size.
  private lazy val MaxThrottle = 4.0
  private lazy val MinThrottle = 1.0
  // Throttle up only when the unflushed buffer is genuinely contended — at 80% of the
  // flush threshold. The previous heuristic (`healPending > 2 * healRate`) compared an
  // absolute buffer size (max ~1000) against a rate-derived target (~10 at 5 nodes/sec),
  // which the buffer almost always exceeds, locking healThrottle at MaxThrottle forever.
  private lazy val ThrottleUpFillRatio = 0.8
  private lazy val RateMeasurementImpact = 0.005 // geometric EMA weight per node

  /** spec 004 T019: encode the leading 6 bytes of a root hash as a numeric "short label" gauge value so an operator can
    * eyeball-correlate the walk-root / serve-root gauges with the `[HEAL]` log lines. This is observation-only — never
    * read by any walk / completeness / fetch decision. Empty ⇒ 0. Six bytes (48 bits) is deliberate: it is always
    * non-negative and is exactly representable in a Prometheus gauge's IEEE-754 double (53-bit mantissa). Eight bytes
    * pushed the leading byte's high bit into the Long sign (rendering a spurious negative ~-3.7e18) AND exceeded the
    * mantissa (the displayed double no longer matched the actual bytes).
    */
  def shortRootLabel(root: ByteString): Long =
    var acc = 0L
    val n = root.length.min(6)
    var i = 0
    while i < n do
      acc = (acc << 8) | (root(i) & 0xffL)
      i += 1
    acc

  /** spec 004 T015/C5/FR-006: record one unsatisfied fetch attempt for a heal task (content-mismatch drop, empty
    * response, or timeout re-queue). When a task crosses `decoupledHealMaxAttemptsNoRefresh` attempts WITHOUT a
    * serve-root advance in between (a serve-root refresh clears the whole map), surface it once at the crossing (WARN
    * log + unservable-count metric). This is OBSERVATION ONLY — it MUST NOT force-complete, abandon, or declare
    * completion: the content-hash check (C4) guarantees a wrong/missing node can never be accepted, so a stuck node
    * simply keeps the walk from finding zero, which is the correct no-false-completion behaviour (SC-002). The map is
    * bounded by the pending-task set (only ever keyed by hashes being re-queued; cleared on every serve-root advance
    * and decayed when a task is satisfied). No-op when decoupling is disabled — the coupled path's behaviour is
    * byte-identical to today (SC-006).
    */
  def noteUnservableAttempt(hash: ByteString): Unit =
    if decoupledHealServeRoot then
      val attempts = healAttempts.getOrElse(hash, 0) + 1
      healAttempts.update(hash, attempts)
      if attempts == decoupledHealMaxAttemptsNoRefresh + 1 then
        // Crossed the threshold for the first time since the last serve-root advance — surface it once.
        val unservable = healAttempts.unservableCount
        log.warn(
          s"[HEAL-SERVE-ROOT] Heal task ${Hex.toHexString(hash.take(4).toArray)} unservable after $attempts " +
            s"attempts with no serve-root advance (threshold=$decoupledHealMaxAttemptsNoRefresh). " +
            s"NOT force-completing (content-hash check keeps completion gated on the walk). " +
            s"unservable-now=$unservable serve=${Hex.toHexString(serveRoot.take(4).toArray)} " +
            s"walk=${Hex.toHexString(stateRoot.take(4).toArray)}"
        )
        SNAPSyncMetrics.setHealingUnservableTasks(unservable.toLong)

  // Per-peer adaptive byte budgeting: the shared window (spec 016 C-X) over this coordinator's map and logger,
  // with healing's fixed initial target (512 KiB) and floor (50 KiB).
  private lazy val responseWindow: AdaptiveResponseBytes =
    new AdaptiveResponseBytes("healing", BigInt(512 * 1024), BigInt(50 * 1024), peerResponseBytesTarget, log)

  def responseBytesTargetFor(peer: Peer): BigInt = responseWindow.targetFor(peer)

  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    responseWindow.onSuccess(peer, requested, received)

  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = responseWindow.onFailure(peer, reason)

  private lazy val peerCooldownDefault = 30.seconds

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  def recordPeerCooldown(peer: Peer, reason: String): Unit =
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeRequests.values.count(_.peer.id == peer.id)

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  def dispatchIfPossible(peer: Peer): Unit =
    if !pivotRefreshRequested && !statelessPeers.contains(peer.id.value) && !isPeerCoolingDown(peer) then
      var inflight = inFlightForPeer(peer)
      var blocked = false
      while !blocked && pendingTasks.nonEmpty && inflight < maxInFlightPerPeer && activeRequests.size < maxConcurrentRequests
      do
        requestNextBatch(peer) match
          case Some(_) => inflight += 1
          case None    => blocked = true

  /** Update healing rate EMA and adjust throttle (geth p2p/msgrate alignment).
    *
    * Called after each healing response. Uses geometric EMA (0.5% weight per node) and adjusts throttle every 1 second:
    * increase if pending > 2×rate, decrease otherwise.
    *
    * @param delivered
    *   number of nodes received in this response
    * @param elapsedMs
    *   time from request send to response receive
    */
  def updateHealThrottle(delivered: Int, elapsedMs: Long): Unit =
    // Update rate (geometric EMA — geth trienodeHealRateMeasurementImpact = 0.005)
    val elapsedSec = elapsedMs.max(1).toDouble / 1000.0
    val measured = delivered.toDouble / elapsedSec
    healRate = math.pow(1 - RateMeasurementImpact, delivered) * (healRate - measured) + measured
    healRate = healRate.max(0.0)

    // Only backpressure on unflushed buffer — pending queue being large is normal after trie walks
    healPending = rawNodeBuffer.size

    // Adjust throttle every 1 second
    val now = System.currentTimeMillis()
    if now - lastThrottleAdjustMs > 1000 then
      val oldThrottle = healThrottle
      // Throttle up only when the disk-flush thread is genuinely behind — i.e. the buffer
      // is filling toward its flush threshold. Comparing buffer fill (an absolute count)
      // against the rate-derived target was a category error: the buffer almost always
      // exceeds 2*rate, which locked healThrottle at MaxThrottle and pinned the batch at
      // batchSize/MaxThrottle paths per request. See issue #1159.
      val flushBackpressure = healPending > rawFlushThreshold * ThrottleUpFillRatio
      if flushBackpressure then healThrottle = (healThrottle * ThrottleIncrease).min(MaxThrottle)
      else healThrottle = (healThrottle / ThrottleDecrease).max(MinThrottle)
      if oldThrottle != healThrottle then
        log.debug(
          f"Healing throttle adjusted: $oldThrottle%.1f -> $healThrottle%.1f " +
            f"(rate=$healRate%.1f nodes/s, bufferFill=$healPending/$rawFlushThreshold, batch=${effectiveBatchSize})"
        )
      lastThrottleAdjustMs = now
    emitHealingFrontierGauges()

  /** Calculate effective batch size after applying throttle divisor. Returns at least 1 node per request.
    */
  private def effectiveBatchSize: Int =
    (batchSize.toDouble / healThrottle).toInt.max(1)

  private def requestNextBatch(peer: Peer): Option[BigInt] =
    if pendingTasks.isEmpty then
      log.debug("No pending healing tasks")
      None
    else if pivotRefreshRequested then None
    else if statelessPeers.contains(peer.id.value) then None
    else
      val effectiveBatch = effectiveBatchSize
      val takeCount = effectiveBatch.min(pendingTasks.size)
      val batch: Seq[HealingEntry] = pendingTasks.iterator.take(takeCount).toSeq
      pendingTasks.dropInPlace(takeCount)

      val requestId = requestTracker.generateRequestId()
      val responseBytes = responseBytesTargetFor(peer)

      // Build the paths list for GetTrieNodes — each entry's pathset is a Seq[ByteString]
      val paths = batch.map(_.pathset)

      // spec 004 T010/C3: the ONLY behavioral fetch change. When decoupling is enabled, fetch missing nodes against
      // the advancing SERVE root (which peers can still serve); otherwise (or before a serve root is obtained,
      // `serveRoot == stateRoot` by the T008 init) fetch against the walk root, byte-identical to the coupled path.
      // GetTrieNodes is path-addressed, so the returned node is content-verified (keccak == task hash) downstream
      // before it is ever stored — see handleResponse (C4). The walk root used for completeness is unchanged.
      val request = GetTrieNodes(
        requestId = requestId,
        // spec 009 T005/C1: under `movingRootDeltaHeal` collapse the fetch root to the completeness root `stateRoot`
        // so the content-hash gate (keccak(node) == requested task hash) matches BY CONSTRUCTION — the spec-004
        // wrong-axis fix. Flag OFF: byte-identical to spec-004 (serve root when decoupled, else the walk root).
        rootHash = if movingRootDeltaHeal then stateRoot else if decoupledHealServeRoot then serveRoot else stateRoot,
        paths = paths,
        responseBytes = responseBytes
      )

      activeRequests(requestId) = ActiveRequest(batch, peer, responseBytes)

      // Send timeout as an actor message so handleTimeout runs on the actor thread,
      // not the scheduler thread. Direct callback would race against receive() handlers
      // that mutate activeRequests/pendingTasks concurrently (BUG-H4).
      requestTracker.trackRequest(requestId, peer, SNAPRequestTracker.RequestType.GetTrieNodes) {
        self ! HealingRequestTimeout(requestId)
      }

      import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
      val messageSerializable: com.chipprbots.ethereum.network.p2p.MessageSerializable = new GetTrieNodesEnc(request)
      networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(messageSerializable, peer.id)

      log.debug(
        s"Requested ${batch.size} trie nodes from peer ${peer.id.value} " +
          s"(reqId=$requestId, responseBytes=$responseBytes, pending=${pendingTasks.size})"
      )

      Some(requestId)

  def handleTimeout(requestId: BigInt, tasks: Seq[HealingEntry], peer: Peer): Unit =
    // go-ethereum reference: timeouts rotate tasks back to queue, peer returns to idle — no stateless marking.
    // (Stateless is only for empty responses.) forkAccepted filter already screens out ETH mainnet peers
    // that advertise snap/1 but have no ETC state, so the original timeout→stateless rationale no longer applies.
    log.warn(s"Healing request timed out: reqId=$requestId, tasks=${tasks.size}, peer=${peer.id.value} — re-queuing")

    activeRequests.remove(requestId)
    recordPeerCooldown(peer, "request timeout")
    adjustResponseBytesOnFailure(peer, "request timeout")

    // Re-queue all timed-out tasks unconditionally — aligned with go-ethereum and Besu pipeline
    // behaviour. Both reference clients never permanently abandon nodes: go-ethereum puts them
    // straight back into trieTasks (no counter); Besu re-queues at the pipeline level after each
    // RetryingGetTrieNodeFromPeerTask attempt. Permanently unservable nodes are handled by the
    // stagnation → pivot-refresh path, not a per-node retry cap.
    var requeued = 0
    tasks.foreach { task =>
      pendingTasks += task
      requeued += 1
      // spec 004 T015/FR-006: a timed-out task is unsatisfied — bump its attempt counter (surfacing only).
      noteUnservableAttempt(task.hash)
    }

    if requeued > 0 then log.info(s"Re-queued $requeued timed-out healing tasks (pending: ${pendingTasks.size})")

    // Check global stagnation: no nodes healed for healingStagnationTimeoutMs.
    // BUG-3 fix: escalate to pivot refresh instead of abandoning. The old behaviour
    // (abandon + StateHealingComplete) raced with the consecutiveStagnations path (6 min)
    // and always won at 5 min, bypassing pivot refresh entirely.
    val stagnantMs = System.currentTimeMillis() - lastHealedAtMs
    if stagnantMs > healingStagnationTimeoutMs && pendingTasks.nonEmpty && !pivotRefreshRequested then
      log.warn(
        s"[HEAL] Stagnation: no nodes healed in ${stagnantMs / 1000}s — requesting pivot refresh"
      )
      lastHealedAtMs = System.currentTimeMillis() // prevent re-firing while refresh in flight
      snapSyncController ! SNAPSyncController.HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
      pivotRefreshRequested = true
      pivotRefreshRequestedAt = System.currentTimeMillis()

    tryRedispatchPendingTasks()
    self ! HealingCheckCompletion

  def tryRedispatchPendingTasks(allowCooldownFloor: Boolean = false): Unit =
    if pendingTasks.nonEmpty && !pivotRefreshRequested then
      var eligiblePeers = knownAvailablePeers.toList
        .filterNot(isPeerCoolingDown)
        .filterNot(p => statelessPeers.contains(p.id.value))
      // Eligible-set floor (peer-retention): if the only thing excluding every non-stateless peer is a cooldown,
      // revive the soonest-to-expire one rather than stalling at zero dispatchable peers. On a residual heal down to
      // 1-2 servable SNAP peers this is the difference between forward progress and a dead stall. We only override
      // cooldown — confirmed-stateless peers stay excluded (the soft-exile threshold + periodic auto-clear handle
      // re-admission). GATED on `allowCooldownFloor` (periodic tick only): on the synchronous timeout/redispatch path
      // the cooldown is load-bearing — a just-timed-out peer must serve its penalty before retry, so the floor must
      // NOT fire there.
      if allowCooldownFloor && eligiblePeers.isEmpty then
        knownAvailablePeers.toList
          .filterNot(p => statelessPeers.contains(p.id.value))
          .filter(isPeerCoolingDown)
          .sortBy(p => peerCooldownUntilMs.getOrElse(p.id.value, 0L))
          .headOption
          .foreach { peer =>
            peerCooldownUntilMs.remove(peer.id.value)
            log.info(
              s"[HEAL-FLOOR] All servable peers were cooling and none eligible — " +
                s"reviving ${peer.id.value.take(8)} to keep the residual heal fed (peer-scarce floor)"
            )
            eligiblePeers = List(peer)
          }
      if eligiblePeers.nonEmpty then for peer <- eligiblePeers if pendingTasks.nonEmpty do dispatchIfPossible(peer)

/** The per-task count of unsatisfied fetch attempts (spec 004 T015/FR-006: content-mismatch drops, empty responses,
  * timeout re-queues), keyed by node hash, that also keeps how many tasks are past the unservable threshold (`attempts
  * > threshold`).
  *
  * The healing gauge pulse ([[FrontierPersistence.emitHealingFrontierGauges]]) and the threshold crossing
  * ([[HealDispatch.noteUnservableAttempt]]) read that number. They used to recount the whole map, which is bounded only
  * by the pending-task set, on every pulse (#1675). Every change to the map goes through `addOne`, `subtractOne` or
  * `clear`, which adjust the count, so it always equals `count { case (_, n) => n > threshold }`.
  *
  * `threshold` is read when an entry changes. It is configuration, constant for the map's life; it is a function only
  * so that the map can be built before the value is initialized.
  */
final class HealAttemptMap(threshold: () => Int) extends mutable.AbstractMap[ByteString, Int]:
  private val underlying = mutable.HashMap.empty[ByteString, Int]
  private var pastThreshold = 0

  /** How many tasks have more than `threshold` attempts. O(1). */
  def unservableCount: Int = pastThreshold

  override def get(key: ByteString): Option[Int] = underlying.get(key)

  override def iterator: Iterator[(ByteString, Int)] = underlying.iterator

  override def size: Int = underlying.size

  override def knownSize: Int = underlying.size

  override def addOne(elem: (ByteString, Int)): this.type =
    val (key, attempts) = elem
    val limit = threshold()
    underlying.put(key, attempts).foreach(previous => if previous > limit then pastThreshold -= 1)
    if attempts > limit then pastThreshold += 1
    this

  override def subtractOne(key: ByteString): this.type =
    underlying.remove(key).foreach(previous => if previous > threshold() then pastThreshold -= 1)
    this

  override def clear(): Unit =
    underlying.clear()
    pastThreshold = 0
