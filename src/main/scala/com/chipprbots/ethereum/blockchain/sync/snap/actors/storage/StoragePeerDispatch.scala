package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapPeerHealth
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ForceCompleteStorage
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PendingResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StorageCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StorageRequestTimedOut
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** The coordinator state `StoragePeerDispatch` reads and writes (spec 016 C-S1, research.md R14b: 23, plus
  * `peerHealth`). The core's behaviour arms, or the response processing, ordered apply and done markers that C-S2…S4
  * will move, touch every one of them too, so the Impl keeps them as fields.
  */
private[actors] trait StoragePeerDispatchState:
  def stateRoot: ByteString
  def maxInFlightPerPeer: Int
  def tasks: mutable.Queue[StorageTask]
  def pendingTaskKeys: mutable.Set[(ByteString, ByteString)]
  def activeTasks: mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]
  def completedTaskCount: Long
  var consecutiveTaskFailures: Int
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def peerHealth: SnapPeerHealth
  def statelessPeers: mutable.Set[String]
  def emptyResponseStrikes: mutable.Map[String, Int]
  var pivotRefreshRequested: Boolean
  var consecutiveUnproductiveRefreshes: Int
  var lastDispatchOrResponseMs: Long
  def postRefreshCooldownUntilMs: Long
  def peerBatchSize: mutable.Map[String, Int]
  def peerBatchSuccessStreak: mutable.Map[String, Int]
  def knownAvailablePeers: mutable.Set[Peer]
  def noMoreTasksExpected: Boolean
  def peerResponseBytesTarget: mutable.Map[String, BigInt]
  def pendingAccountTries: mutable.Map[ByteString, SnapTrie]
  def pendingResponses: mutable.Queue[PendingResponse]
  def pendingResponseBytes: Long
  def runningProcessingJobs: Int

/** What `StoragePeerDispatch` calls in the coordinator core: the task-completion fraction its periodic
  * `[STORAGE-STATE]` line reports.
  */
private[actors] trait StorageRangeCoreApi:
  def progress: Double

/** Storage-range peer dispatch (spec 016 C-S1, coordinator-characterization.md §3.3): stateless-peer tracking with its
  * empty-response strikes and the pivot-refresh escalation it drives (all-stateless, low-eligible and stalled-dispatch
  * triggers, with backoff); the post-refresh dispatch cooldown; the per-peer adaptive batch size and `responseBytes`
  * budget; the per-peer cooldowns and the in-flight cap (one slot for a peer on probation); building and sending
  * `GetStorageRanges` (`requestNextRanges`); the redispatch of pending tasks to every eligible peer, with the
  * peer-scarce floor (`selectFloorPeer`) and the idle-check escape; and the request timeout (`handleTimeout`: the
  * cooldown, the `SnapPeerHealth` demotion, the re-queue and the force-complete threshold). The coordinator core keeps
  * the behaviour arms that call it.
  */
private[actors] trait StoragePeerDispatch:
  this: StorageRangeCoordinatorImpl =>

  private lazy val maxConsecutiveTaskFailures: Int = 100
  private lazy val peerCooldownDefault = 5.seconds
  private var lastPenalisedFloorLogMs: Long = 0L
  private lazy val EmptyResponseStrikeThreshold: Int = 5
  private var lastPivotRefreshTimeMs: Long = 0
  private lazy val minRefreshIntervalMs: Long = 60000L // 1 minute minimum between refreshes
  private lazy val maxRefreshIntervalMs: Long = 300000L // 5 minutes maximum backoff
  private lazy val noActivityTimeoutMs: Long = 120000L // 2 minutes

  // Consecutive idle dispatch checks: counts tryRedispatchPendingTasks() calls where tasks are
  // pending but zero eligible peers and zero active requests exist. At threshold, requests a
  // pivot refresh as an escape valve — same pattern as TrieNodeHealingCoordinator BUG-M3 fix.
  private var storageIdleChecks: Int = 0
  private lazy val storageIdleEscapeThreshold: Int = 5

  def isPostRefreshCooldownActive: Boolean =
    System.currentTimeMillis() < postRefreshCooldownUntilMs

  def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id.value)

  def markPeerStateless(peer: Peer): Unit =
    val id = peer.id.value
    // Already-confirmed peers stay confirmed; extra strikes are noise.
    if !statelessPeers.contains(id) then
      val priorStrikes = emptyResponseStrikes.getOrElse(id, 0)
      val strikes = priorStrikes + 1
      emptyResponseStrikes(id) = strikes

      if strikes < EmptyResponseStrikeThreshold then
        log.info(
          s"Peer $id empty-storage strike $strikes/$EmptyResponseStrikeThreshold for root " +
            s"${stateRoot.take(4).toHex}. Still eligible for dispatch."
        )
      else
        val wasStateless = statelessPeers.contains(id)
        statelessPeers.add(id)
        if !wasStateless then
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementStatelessPeerConfirmed()
        log.info(
          s"Peer $id marked stateless after $strikes consecutive empty storage responses for root " +
            s"${stateRoot.take(4).toHex} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
        )
        maybeRequestPivotRefresh()

  /** Reset strike counter when peer produces a useful response. Cheap to over-invoke. */
  def recordPeerSuccess(peerId: String): Unit =
    emptyResponseStrikes.remove(peerId)
    recordPeerAnswered(peerId)

  /** The peer answered a request with usable data: feed the liveness score and log a recovery from demotion. */
  def recordPeerAnswered(peerId: String): Unit =
    // A served answer ends the failure run, as the counter's contract says. Before this the only reset outside a pivot
    // refresh was the StorageTaskComplete handler, which nothing sends: on Sepolia 2026-10-09 the 100th timeout since
    // startup (most of them replies dropped while this actor was busy) force-completed storage at 13:02, discarding the
    // in-flight requests, 42 per-account tries and the ordering-gate state to healing, although ~1,800 responses had
    // been processed in the same span.
    consecutiveTaskFailures = 0
    peerHealth.recordSuccess(peerId).foreach { level =>
      log.info(
        s"[STORAGE-PEER-HEALTH] recovered peer=${peerId.take(8)} level=$level->${peerHealth.level(peerId)} " +
          s"penalised=${peerHealth.penalisedCount(System.currentTimeMillis())}"
      )
    }

  // Low-eligible recovery: trigger pivot refresh when almost all peers are stateless but
  // one peer remains, preventing allStateless from ever becoming true. With the parallel
  // subtask dispatch added in spec 005 (storageConcurrency=16), ETH-genesis peers (connecting
  // due to shared networkId=1) can accumulate 5 strikes each within one pivot cycle. If 11 of
  // 12 peers become stateless the one remaining peer serves all tasks at 1/12th throughput
  // with no automatic recovery until the next natural pivot roll (≈4 min observed in RUN02).
  // Threshold: trigger when ≤1 peer eligible AND pool has ≥4 total peers.
  // Backoff: uses the existing minRefreshIntervalMs/maxRefreshIntervalMs schedule, so it
  // cannot churn faster than allStateless recovery.
  private lazy val lowEligibleMaxCount: Int = 1
  private lazy val lowEligibleMinPoolSize: Int = 4

  def maybeRequestPivotRefresh(): Unit = if !pivotRefreshRequested then
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id.value))

    val eligibleCount = (knownAvailablePeers.size - statelessPeers.size).max(0)
    val lowEligible = !allStateless &&
      knownAvailablePeers.size >= lowEligibleMinPoolSize &&
      eligibleCount <= lowEligibleMaxCount &&
      tasks.nonEmpty

    // Secondary trigger: tasks pending but no dispatch/response activity for 2 minutes.
    // Catches "ghost" peers that remain in knownAvailablePeers after disconnecting
    // without being marked stateless (preventing allStateless from ever being true).
    // Note: activeTasks may be non-empty if requests to ghost peers never time out
    // (SNAPRequestTracker timeouts are poll-based, not scheduled), so we check
    // activity time regardless of in-flight count.
    val now = System.currentTimeMillis()
    val dispatchStalled = !allStateless && !lowEligible && tasks.nonEmpty && maxInFlightPerPeer > 0 &&
      (now - lastDispatchOrResponseMs) > noActivityTimeoutMs

    if dispatchStalled then
      log.warn(
        s"Storage dispatch stalled: ${tasks.size} pending, ${activeTasks.size} active, " +
          s"no activity for ${(now - lastDispatchOrResponseMs) / 1000}s. " +
          s"Peers: ${knownAvailablePeers.size} known, ${statelessPeers.size} stateless. " +
          s"Marking remaining peers as stateless (likely disconnected)."
      )
      knownAvailablePeers.foreach(p => statelessPeers.add(p.id.value))
      // Re-queue any stale in-flight tasks from ghost peers
      if activeTasks.nonEmpty then
        val staleCount = activeTasks.size
        activeTasks.values.foreach { case (_, batchTasks, _) =>
          batchTasks.foreach { task =>
            tasks.enqueue(task.copy(pending = false))
          }
        }
        // With ownerDecidesTimeout the tracker keeps a request until it is completed, expired or cancelled.
        activeTasks.keys.foreach(requestTracker.cancelRequest)
        activeTasks.clear()
        log.info(s"Re-queued $staleCount stale in-flight requests from ghost peers")

    if allStateless || lowEligible || dispatchStalled then
      val backoffMs = math.min(
        maxRefreshIntervalMs,
        minRefreshIntervalMs * (1L << math.min(consecutiveUnproductiveRefreshes, 3))
      )
      val elapsed = now - lastPivotRefreshTimeMs
      if lastPivotRefreshTimeMs > 0 && elapsed < backoffMs then
        val remainingMs = backoffMs - elapsed
        log.info(
          s"All peers stateless but backing off pivot refresh " +
            s"(${elapsed / 1000}s / ${backoffMs / 1000}s, attempt ${consecutiveUnproductiveRefreshes + 1}). " +
            s"Retrying in ${remainingMs / 1000}s."
        )
        // Schedule a retry after the backoff period (one-shot self-send; triggers re-evaluation)
        context.scheduleOnce(remainingMs.millis, self, StorageCheckCompletion)
      else
        pivotRefreshRequested = true
        consecutiveUnproductiveRefreshes += 1
        lastPivotRefreshTimeMs = now
        if lowEligible then
          log.warn(
            s"Low-eligible storage peers: ${eligibleCount}/${knownAvailablePeers.size} eligible, " +
              s"${statelessPeers.size} stateless for root ${stateRoot.take(4).toHex}. " +
              s"Requesting pivot refresh to restore peer pool (attempt $consecutiveUnproductiveRefreshes)."
          )
          snapSyncController ! SNAPSyncController.PivotStateUnservable(
            rootHash = stateRoot,
            reason = "low-eligible peers for StorageRange root",
            consecutiveEmptyResponses = statelessPeers.size
          )
        else
          log.warn(
            s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
              s"Requesting pivot refresh from controller (attempt $consecutiveUnproductiveRefreshes)."
          )
          snapSyncController ! SNAPSyncController.PivotStateUnservable(
            rootHash = stateRoot,
            reason = "all peers stateless for StorageRange root",
            consecutiveEmptyResponses = statelessPeers.size
          )

  private lazy val batchRecoveryStreak = 3 // Consecutive successes before scaling up

  def batchSizeFor(peer: Peer): Int =
    peerBatchSize.getOrElseUpdate(peer.id.value, maxAccountsPerBatch)

  def reduceBatchSize(peer: Peer): Unit =
    peerBatchSize.update(peer.id.value, 1)
    peerBatchSuccessStreak.remove(peer.id.value)

  /** Scale batch size back up after consecutive successful packed responses. Doubles the batch size per peer, capped at
    * maxAccountsPerBatch.
    */
  def maybeIncreaseBatchSize(peer: Peer, servedCount: Int, requestedCount: Int): Unit =
    // Only count as "packed" if the response served most of the requested accounts
    if requestedCount > 1 && servedCount >= requestedCount / 2 then
      val streak = peerBatchSuccessStreak.getOrElse(peer.id.value, 0) + 1
      peerBatchSuccessStreak.update(peer.id.value, streak)
      if streak >= batchRecoveryStreak then
        val current = batchSizeFor(peer)
        val next = math.min(current * 2, maxAccountsPerBatch)
        if next > current then
          peerBatchSize.update(peer.id.value, next)
          peerBatchSuccessStreak.update(peer.id.value, 0)
          log.info(
            s"Peer ${peer.id.value} batch size increased: $current -> $next (after $streak consecutive successes)"
          )

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_._1.id == peer.id)

  private var storageMilestonePct: Int = -1

  // Per-peer adaptive byte budgeting (ported from ByteCodeCoordinator).
  // Geth's snap handler supports up to 2MB responses. Starting at 512KB and probing upward
  // on responsive peers, scaling down on failures.
  private lazy val minResponseBytes: BigInt =
    configMinResponseBytes // Configurable floor (avoid excessive small requests)
  private lazy val maxResponseBytes: BigInt = 2 * 1024 * 1024 // 2MB ceiling (Geth handler limit)
  private lazy val initialResponseBytes: BigInt = configInitialResponseBytes // Configurable starting point
  private lazy val increaseFactor: Double = 1.25 // Scale up when 90%+ fill
  private lazy val decreaseFactor: Double = 0.5 // Scale down on failure/empty

  private def responseBytesTargetFor(peer: Peer): BigInt =
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
      s"Reducing storage responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )

  private def requestNextRanges(peer: Peer): Option[BigInt] =
    val min = ByteString(Array.fill(32)(0.toByte))
    val max = ByteString(Array.fill(32)(0xff.toByte))
    def isInitialRange(t: StorageTask): Boolean = t.next == min && t.last == max

    // Streaming-trie memory cap: a new account's trie costs up to ~8 MiB worst-case.
    // Continuations for accounts already in `pendingAccountTries` are free — they reuse
    // the existing trie. Reject only the dispatch of brand-new accounts when at the cap.
    // This puts a hard ceiling on storage-processing memory regardless of chain size.
    def acceptsNewAccount(t: StorageTask): Boolean =
      deferredMerkleization ||
        pendingAccountTries.contains(t.accountHash) ||
        pendingAccountTries.size < maxConcurrentStorageAccounts

    // Pre-dispatch guards. Each blocks dispatch (returns None) without mutating queue state.
    // Peek-ahead at the front of the queue: if the head task would force a new account
    // open beyond the cap, leave it queued and skip this dispatch cycle. Once an in-flight
    // trie commits (or aborts), the cap relaxes and the next dispatch will pick it up.
    val blocked: Boolean =
      if tasks.isEmpty then
        log.debug("No more storage tasks available")
        true
      else if isPostRefreshCooldownActive || pivotRefreshRequested || isPeerStateless(peer) then true
      else if !acceptsNewAccount(tasks.front) then
        log.debug(
          s"Storage dispatch gated by max-concurrent-storage-accounts=$maxConcurrentStorageAccounts " +
            s"(in-flight tries=${pendingAccountTries.size}); deferring new-account dispatch"
        )
        true
      else false

    if blocked then None
    else
      val peerBatch = batchSizeFor(peer)

      // snap/1 origin/limit semantics apply to the first account only. To avoid incorrect continuation
      // behavior, only batch tasks that request the initial full range.
      val first = tasks.dequeue()
      pendingTaskKeys -= ((first.accountHash, first.next))
      val batchTasks: Seq[StorageTask] =
        if !isInitialRange(first) || peerBatch <= 1 then Seq(first)
        else
          val buf = mutable.ArrayBuffer[StorageTask](first)
          while buf.size < peerBatch && tasks.nonEmpty && isInitialRange(tasks.front) && acceptsNewAccount(tasks.front)
          do
            val t = tasks.dequeue()
            pendingTaskKeys -= ((t.accountHash, t.next))
            buf += t
          buf.toSeq
      // Dispatch drains the queue: let a waiting producer see the room right away.
      intakeBudget.foreach(_.storageQueueDepth(tasks.size.toLong))

      if batchTasks.isEmpty then None
      else
        val requestedBytes = responseBytesTargetFor(peer)
        val requestId = requestTracker.generateRequestId()
        val accountHashes = batchTasks.map(_.accountHash)
        val firstTask = batchTasks.head

        val request = GetStorageRanges(
          requestId = requestId,
          rootHash = stateRoot,
          accountHashes = accountHashes,
          startingHash = firstTask.next,
          limitHash = firstTask.last,
          responseBytes = requestedBytes
        )

        val activeBatchTasks = batchTasks.map(_.copy(pending = true))
        activeTasks.put(requestId, (peer, activeBatchTasks, requestedBytes))

        requestTracker.trackRequest(
          requestId,
          peer,
          SNAPRequestTracker.RequestType.GetStorageRanges,
          timeout = requestTimeout,
          ownerDecidesTimeout = true
        ) {
          // Runs on the scheduler's thread, not this actor's: hop onto the mailbox. Calling handleTimeout here mutated
          // actor state from a foreign thread and, because the tracker timer outlives the actor, kept re-firing
          // [STORAGE-FORCE-COMPLETE] for a coordinator that was already stopped (devnet-8, 2026-10-03).
          self ! StorageRequestTimedOut(requestId)
        }

        log.info(
          s"GetStorageRanges: peer=${peer.id.value} accounts=${batchTasks.size} bytes=$requestedBytes requestId=$requestId"
        )

        // Full request details at DEBUG level for troubleshooting
        log.debug(
          s"GetStorageRanges detail: requestId=$requestId root=${stateRoot.toHex} " +
            s"start=${firstTask.next.toHex} limit=${firstTask.last.toHex} " +
            s"accounts=${accountHashes.map(_.take(4).toHex).mkString(",")}"
        )

        import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
        val messageSerializable: MessageSerializable = new GetStorageRangesEnc(request)
        networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(messageSerializable, peer.id)
        lastDispatchOrResponseMs = System.currentTimeMillis()

        Some(requestId)

  def handleTimeout(requestId: BigInt): Unit =
    activeTasks.remove(requestId).foreach { case (peer, batchTasks, _) =>
      log.warn(s"Storage range request timeout for ${batchTasks.size} accounts from peer ${peer.id.value}")
      recordPeerCooldown(peer, "request timeout")
      peerHealth.recordTimeout(peer.id.value, System.currentTimeMillis(), knownAvailablePeers.size).foreach { penalty =>
        // At most once per penalty window per peer: a penalised peer is not dispatched to, so it cannot time out again
        // until the penalty lapses.
        log.info(
          s"[STORAGE-PEER-HEALTH] demoted peer=${peer.id.value.take(8)} " +
            s"consecutiveTimeouts=${peerHealth.consecutiveTimeouts(peer.id.value)} " +
            s"level=${peerHealth.level(peer.id.value)} penalty=${penalty.toSeconds}s " +
            s"penalised=${peerHealth.penalisedCount(System.currentTimeMillis())}/${knownAvailablePeers.size}"
        )
      }
      adjustResponseBytesOnFailure(peer, "request timeout")

      batchTasks.foreach { task =>
        val key = (task.accountHash, task.next)
        if !pendingTaskKeys.contains(key) then
          pendingTaskKeys += key
          tasks.enqueue(task.copy(pending = false))
      }

      consecutiveTaskFailures += 1
      log.debug(s"Storage consecutive task failures: $consecutiveTaskFailures/$maxConsecutiveTaskFailures")
      if consecutiveTaskFailures >= maxConsecutiveTaskFailures then
        log.warn(
          s"[STORAGE-FORCE-COMPLETE] $consecutiveTaskFailures consecutive task failures — " +
            s"SNAP peers not serving storage data. Sending ForceCompleteStorage. " +
            s"Missing storage deferred to healing phase."
        )
        self ! ForceCompleteStorage
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless or on cooldown.
    tryRedispatchPendingTasks()

  /** Dispatch up to maxInFlightPerPeer requests to a single peer (pipelining). */
  def dispatchIfPossible(peer: Peer): Unit =
    var inflight = inFlightForPeer(peer)
    var continue = true
    val peerLimit = perPeerInFlightLimit(peer)
    // A response received but not yet applied keeps its request's slot (#1533): bounds the responses held in memory.
    while continue && tasks.nonEmpty && inflight < peerLimit &&
      activeTasks.size + pendingResponses.size < maxInFlightRequests &&
      pendingResponseBytes < processing.maxPendingBytes
    do
      requestNextRanges(peer) match
        case Some(_) => inflight += 1
        case None    => continue = false

  // Periodic state-dump cadence — at most one INFO snapshot every 30 seconds. tryRedispatchPendingTasks
  // can be called many times per second under heavy storage flow (each AddStorageTasks call
  // chains into it), so modulo-based throttling produced log floods that overflowed the live
  // monitor pipe; time-based throttling is robust against call-rate spikes.
  private var lastStateLogMs: Long = 0L
  private lazy val StateLogIntervalMs: Long = 30_000L

  def tryRedispatchPendingTasks(): Unit =
    if tasks.nonEmpty && !isPostRefreshCooldownActive && !pivotRefreshRequested then redispatchEligible()

  private def redispatchEligible(): Unit =
    val now = System.currentTimeMillis()
    var eligiblePeers = knownAvailablePeers
      .filterNot(p => isPeerStateless(p) || isPeerCoolingDown(p) || peerHealth.isPenalised(p.id.value, now))
      .toList
    if eligiblePeers.isEmpty then
      StorageRangeCoordinator.selectFloorPeer(
        knownAvailablePeers.filterNot(isPeerStateless).toList,
        isCooling = isPeerCoolingDown,
        isPenalised = p => peerHealth.isPenalised(p.id.value, now),
        cooldownUntilMs = p => peerCooldownUntilMs.getOrElse(p.id.value, 0L),
        penaltyUntilMs = p => peerHealth.penaltyUntilMs(p.id.value)
      ) match
        case Some(StorageRangeCoordinator.FloorPick.Revive(peer)) =>
          peerCooldownUntilMs.remove(peer.id.value)
          log.info(
            s"[STORAGE-FLOOR] All servable peers were cooling and none eligible — " +
              s"reviving ${peer.id.value.take(8)} to keep the pipe fed (peer-scarce floor)"
          )
          eligiblePeers = List(peer)
        case Some(StorageRangeCoordinator.FloorPick.LastResortProbe(peer)) =>
          // Every servable peer is penalised for repeated timeouts. Probe the one whose penalty lapses first, without
          // lifting its penalty and with a single request slot (perPeerInFlightLimit), so a dead peer costs at most one
          // timeout slot at a time instead of the full per-peer budget.
          if now - lastPenalisedFloorLogMs >= StateLogIntervalMs then
            lastPenalisedFloorLogMs = now
            log.info(
              s"[STORAGE-FLOOR] Only penalised peers remain (${peerHealth.penalisedCount(now)}) — " +
                s"probing ${peer.id.value.take(8)} with a single request slot"
            )
          eligiblePeers = List(peer)
        case None => ()
    val shouldLog = now - lastStateLogMs >= StateLogIntervalMs
    if shouldLog then
      lastStateLogMs = now
      val pctInt = (progress * 100).toInt
      log.info(
        s"[STORAGE-STATE] $pctInt% | pending=${tasks.size} active=${activeTasks.size} " +
          s"completed=$completedTaskCount " +
          s"workers-known=${knownAvailablePeers.size} stateless=${statelessPeers.size} " +
          s"cooling=${knownAvailablePeers.count(isPeerCoolingDown)} eligible=${eligiblePeers.size} " +
          s"strikes=${emptyResponseStrikes.size} penalised=${peerHealth.penalisedCount(now)} " +
          s"processing=${pendingResponses.size}/$runningProcessingJobs " +
          s"root=${stateRoot.take(4).toHex}"
      )
      if noMoreTasksExpected then
        val activeCount = activeTasks.values.map(_._2.size).sum
        val total = completedTaskCount + activeCount + tasks.size
        val (newM, crossed) =
          com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
            .crossed(completedTaskCount, total, storageMilestonePct)
        storageMilestonePct = newM
        crossed.foreach { m =>
          log.info(s"[SNAP-PROGRESS] STORAGE-COORD MILESTONE $m% — $completedTaskCount / $total tasks complete")
        }
    if eligiblePeers.isEmpty then
      if shouldLog then
        log.info(
          s"[STORAGE-REDISPATCH] No eligible peers — ${knownAvailablePeers.size} known, " +
            s"${statelessPeers.size} stateless, " +
            s"${knownAvailablePeers.count(isPeerCoolingDown)} cooling. pending: ${tasks.size}"
        )
      if tasks.nonEmpty && activeTasks.isEmpty then
        storageIdleChecks += 1
        if storageIdleChecks >= storageIdleEscapeThreshold then
          log.warn(
            s"[STORAGE] No eligible peers for $storageIdleChecks consecutive redispatch checks with " +
              s"${tasks.size} pending tasks and no active requests — requesting pivot refresh"
          )
          storageIdleChecks = 0
          maybeRequestPivotRefresh()
    else
      storageIdleChecks = 0
      for peer <- eligiblePeers if tasks.nonEmpty do dispatchIfPossible(peer)

  def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  /** A peer demoted for repeated timeouts gets one request slot until it answers again; everyone else gets the budget.
    */
  private def perPeerInFlightLimit(peer: Peer): Int =
    if peerHealth.isOnProbation(peer.id.value) then maxInFlightPerPeer.min(1) else maxInFlightPerPeer

  def recordPeerCooldown(peer: Peer, reason: String): Unit =
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
