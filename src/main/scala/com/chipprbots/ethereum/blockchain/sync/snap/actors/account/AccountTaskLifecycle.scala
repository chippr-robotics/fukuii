package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.PivotStateUnservable
import com.chipprbots.ethereum.blockchain.sync.snap.actors.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.CheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.StoreAccountChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRequestCancelled
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** The coordinator state `AccountTaskLifecycle` reads and writes (spec 016 C-A2, research.md R14b: 18). The core's
  * behaviour arms, the peer dispatch or the trie assembly that C-A3 will move touch every one of them too, so the Impl
  * keeps them as fields.
  */
private[actors] trait AccountTaskLifecycleState:
  def stateRoot: ByteString
  def maxInFlightPerPeer: Int
  def statelessPeers: mutable.Set[PeerId]
  def snaplessPeers: mutable.Set[PeerId]
  def emptyResponseStrikes: mutable.Map[PeerId, Int]
  var pivotRefreshRequested: Boolean
  def pendingTasks: mutable.PriorityQueue[AccountTask]
  def activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]
  def completedTasks: mutable.ArrayBuffer[AccountTask]
  var lastDispatchOrResponseMs: Long
  def accountsDownloaded: Long
  var bytesDownloaded: Long
  var consumedKeyspace: BigInt
  def storingTasks: mutable.Map[ByteString, AccountTask]
  def knownAvailablePeers: mutable.Set[Peer]
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def lastDispatchTimeMs: mutable.Map[String, Long]
  var consecutiveUnproductiveRefreshes: Int

/** What `AccountTaskLifecycle` calls in the coordinator core: the progress snapshot sent to the controller (made
  * durable first when requested), which a range completed by an empty proof of absence triggers.
  */
private[actors] trait AccountProgressApi:
  def sendProgressSnapshot(durableRequested: Boolean = false): Unit

/** What `AccountTaskLifecycle` calls in the contract carry: identifying the contract accounts of a response for the
  * storage and bytecode task files. The coordinator core provides it until C-A4 moves it into `ContractWorkCarry`.
  */
private[actors] trait ContractWorkCarryApi:
  def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit

/** The account-task lifecycle (spec 016 C-A2, coordinator-characterization.md §3.2): a worker's completed or failed
  * task (the cursor advance over the 32-byte hash space, a range completed by an empty proof of absence, the stale-root
  * guard, cooldown and response-budget feedback), the requeue with its `MaxRequeuesPerTask` escalation, the drain of
  * stale in-flight requests back to the queue, and the redispatch of pending tasks to every eligible peer with the
  * cooldown floor. The coordinator core keeps the behaviour arms that call it.
  */
private[actors] trait AccountTaskLifecycle:
  self: AccountRangeCoordinatorImpl =>

  /** Drain stale in-flight requests back to the pending queue (#1184). Sends `WorkerRequestCancelled` to each affected
    * worker — the worker is responsible for cancelling its own `SNAPRequestTracker` entry and resetting `currentTask`,
    * matching the existing contract on the `RequestTimeout` / `WorkerPeerDisconnected` paths.
    *
    *   - `PeerUnavailable` → `peerFilter = Some(peerId)`
    *   - dispatch-stalled / pivot-refresh / `RecoverStalledAccountTasks` → `peerFilter = None`
    *
    * Mirrors `StorageRangeCoordinator.maybeRequestPivotRefresh` (~lines 167-187), extended with
    * `WorkerRequestCancelled` notification because `AccountRangeWorker` (unlike storage workers) keeps per-request
    * `currentTask` state. Without the cancel, the drained worker stays in `working` and would reject the next
    * `FetchAccountRange` with `TaskFailed(0, "Worker busy")`.
    *
    * Idempotent: `activeTasks.remove` returns `None` for already-removed slots, so a late `TaskFailed` / `TaskComplete`
    * arriving after a drain is a safe no-op via the existing `.foreach` pattern in `handleTaskComplete` /
    * `handleTaskFailed`.
    *
    * Caller is responsible for resetting `lastDispatchOrResponseMs` if recovery should also reset the activity timer
    * regardless of whether any slots were drained (e.g. `PivotRefreshed` and `RecoverStalledAccountTasks` always reset;
    * `PeerUnavailable` only resets when something was drained).
    *
    * @return
    *   number of slots drained
    */
  def drainActiveTasks(reason: String, peerFilter: Option[String] = None): Int =
    val toDrain: Seq[(BigInt, AccountTask, WorkerRef, Peer)] = activeTasks.toSeq.collect {
      case (reqId, (task, worker, peer)) if peerFilter.forall(_ == peer.id.value) =>
        (reqId, task, worker, peer)
    }
    if toDrain.isEmpty then 0
    else
      toDrain.foreach { case (reqId, task, worker, _) =>
        // 1. Cancel the worker's local state FIRST so it leaves `working` and accepts the
        //    next FetchAccountRange. The worker calls requestTracker.completeRequest itself,
        //    matching the existing contract. Pekko preserves coordinator → worker message
        //    ordering, so any subsequent FetchAccountRange to the same worker arrives strictly
        //    after this cancellation has been processed.
        worker ! WorkerRequestCancelled(reqId)
        // 2. Re-queue the task. Do NOT increment requeueCount — drain is recovery, not a
        //    per-task failure; bumping would prematurely trip MaxRequeuesPerTask.
        pendingTasks.enqueue(task.copy(pending = false))
        // 3. Mark the worker idle in the coordinator's pool (idempotent).
        markWorkerIdle(worker)
        // 4. Remove the slot.
        activeTasks.remove(reqId)
      }
      log.info(s"Re-queued ${toDrain.size} stale in-flight account requests ($reason)")
      toDrain.size

  // Periodic state-dump cadence — at most one INFO snapshot every 30 seconds. Time-based
  // throttling (not modulo) so a call-rate spike doesn't overflow the log pipe. Same pattern
  // as PR #1250's [STORAGE-STATE] (subsequently switched from modulo to time-based for the
  // same reason).
  private var lastStateLogMs: Long = 0L
  private lazy val StateLogIntervalMs: Long = 10_000L

  // Short cooldown for "empty-without-proof" responses. These mean "I can't serve this
  // state root" — the peer is healthy, just doesn't have a snapshot at our pivot. 30s
  // was over-punitive here: in production we saw `cooling=5 eligible=11` snapshots,
  // i.e. ~30 % of the otherwise-eligible pool parked in cooldown most of the time.
  // A pivot refresh is what unsticks these peers, not waiting.
  private lazy val peerCooldownNoProof = 5.seconds

  def handleTaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, Account)], Seq[ByteString])]
  ): Unit =
    activeTasks.remove(requestId) match
      case None =>
        // Task was drained (PeerUnavailable, pivot refresh) before the worker's response arrived.
        // The response is discarded and the task is already in pendingTasks via drainActiveTasks.
        // Log at WARNING so Run-N monitoring can distinguish this from a worker crash.
        log.warn(
          s"[ACCOUNT-COORD] TaskComplete for unknown reqId=$requestId — task was already drained. Ignored."
        )
      case Some((task0, worker, peer)) =>
        // #1184: progress signal — used by CheckDispatchStalled.
        lastDispatchOrResponseMs = System.currentTimeMillis()
        markWorkerIdle(worker)
        result match
          case Right((accountCount, accounts, proof)) =>
            log.info(
              s"Task completed successfully: $accountCount accounts (responseBytes=${responseBytesTargetFor(peer)})"
            )

            // Adjust adaptive byte budget — estimate received bytes from account count
            val estimatedBytes = BigInt(accountCount * 100) // ~100 bytes per account (hash + RLP)
            adjustResponseBytesOnSuccess(peer, responseBytesTargetFor(peer), estimatedBytes)

            // Reset pivot refresh backoff on servable-root evidence: real account data or a boundary proof.
            if accountCount > 0 || proof.nonEmpty then
              consecutiveUnproductiveRefreshes = 0
              // The peer served us a useful response. Wipe any prior empty-proof strikes so
              // a future transient empty doesn't push a known-good peer over the threshold.
              recordPeerSuccess(peer.id)

            var task = task0.copy(pending = false)

            if accountCount == 0 then
              if proof.nonEmpty || task.rootHash == ByteString(MerklePatriciaTrie.EmptyRootHash) then
                completeEmptyTaskRange(task, proofNodes = proof.size)
                tryRedispatchPendingTasks()
              else
                // Defensive fallback: the verifier should reject this as a stateless-peer signal.
                // Use the short proof-less cooldown — the peer is healthy, just doesn't hold a
                // snapshot at our current pivot. Parking it for 30s gains nothing; a pivot
                // refresh is what unsticks it.
                recordPeerCooldown(
                  peer,
                  "empty account range without proof — peer snapshot may not cover this root",
                  peerCooldownNoProof
                )
                requeueOrEscalate(task, "empty range without proof")
            else
              // Real account data → reset requeue budget (transient failures earlier are now resolved).
              task = task.copy(requeueCount = 0)

              // Identify contract accounts
              identifyContractAccounts(accounts)

              // Update task progress before starting async storage.
              // This sets task.next so re-queuing (if needed) uses the correct start.
              val (isTaskDone, updatedTask) = updateTaskProgress(task, accounts)
              task = updatedTask

              // Update statistics
              val accountBytes = accounts.map { case (hash, _) =>
                hash.size + 32 // Rough estimate
              }.sum
              bytesDownloaded += accountBytes

              // Start chunked async storage - this yields back to the actor mailbox between chunks
              // so the coordinator can still process PeerAvailable, AccountRangeResponseMsg, etc.
              // Tracked in storingTasks meanwhile so a snapshot never drops this range's (advanced) cursor.
              storingTasks.update(task.last, task)
              ctx.self ! StoreAccountChunk(
                task,
                accounts,
                accountCount,
                storedSoFar = 0,
                isTaskRangeComplete = isTaskDone
              )

          case Left(error) =>
            log.warn(s"Task completed with error: $error")
            // Re-queue task for retry
            requeueOrEscalate(task0.copy(pending = false), s"task completed with error: $error")

  private def updateTaskProgress(task: AccountTask, accounts: Seq[(ByteString, Account)]): (Boolean, AccountTask) =
    // Empty responses are handled before this method. A no-proof empty response is a peer refusal; a proof-only empty
    // response is a valid proof that the requested tail is exhausted.
    if accounts.isEmpty then (false, task)
    else
      val lastHash = accounts.last._1
      if isMaxHash(lastHash) then
        // Cannot advance beyond 0xFF..; this must be the end. Move the cursor to `last` so any snapshot taken while
        // this response is being stored already reports the range complete (its contracts are already identified).
        consumedKeyspace += task.remainingKeyspace
        (true, task.copy(next = task.last))
      else
        val nextStart = incrementHash32(lastHash)
        // Track keyspace consumed: distance from old next to new next
        val oldNext = BigInt(1, task.next.toArray.padTo(32, 0.toByte))
        val newNext = BigInt(1, nextStart.toArray.padTo(32, 0.toByte))
        val advanced = (newNext - oldNext).max(BigInt(0))
        consumedKeyspace += advanced
        val updatedTask = task.copy(next = nextStart)

        // If this task has no upper bound, keep going until peer returns empty.
        if updatedTask.last.isEmpty then (false, updatedTask)
        else
          // Treat `last` as an exclusive upper bound.
          (compareUnsigned32(nextStart, updatedTask.last) >= 0, updatedTask)

  private def compareUnsigned32(a: ByteString, b: ByteString): Int =
    // Empty is treated as unbounded; callers should handle this before comparing.
    val aa = a.toArray
    val bb = b.toArray
    val maxLen = math.max(aa.length, bb.length)
    val ap = if aa.length == maxLen then aa else Array.fill(maxLen - aa.length)(0.toByte) ++ aa
    val bp = if bb.length == maxLen then bb else Array.fill(maxLen - bb.length)(0.toByte) ++ bb
    var i = 0
    var result = 0
    while result == 0 && i < maxLen do
      val ai = ap(i) & 0xff
      val bi = bp(i) & 0xff
      if ai != bi then result = ai - bi
      i += 1
    result

  private def incrementHash32(hash: ByteString): ByteString =
    require(hash.length == 32, s"Expected 32-byte hash, got ${hash.length}")
    val bytes = hash.toArray
    var i = bytes.length - 1
    var carry = 1
    while i >= 0 && carry != 0 do
      val sum = (bytes(i) & 0xff) + carry
      bytes(i) = (sum & 0xff).toByte
      carry = if sum > 0xff then 1 else 0
      i -= 1
    ByteString(bytes)

  private def isMaxHash(hash: ByteString): Boolean =
    hash.length == 32 && hash.forall(b => (b & 0xff) == 0xff)

  private def completeEmptyTaskRange(task: AccountTask, proofNodes: Int): Unit =
    val range = task.rangeString
    consumedKeyspace += task.remainingKeyspace
    completedTasks += task.copy(next = task.last, done = true)
    log.info(
      s"Account range COMPLETE: $range by empty proof-of-absence " +
        s"(proofNodes=$proofNodes, ${completedTasks.size}/$concurrency ranges done, $accountsDownloaded accounts total)"
    )
    sendProgressSnapshot(durableRequested = true)
    ctx.self ! CheckCompletion

  def handleTaskFailed(requestId: BigInt, reason: String): Unit =
    activeTasks.remove(requestId) match
      case None =>
        log.warn(
          s"[ACCOUNT-COORD] TaskFailed for unknown reqId=$requestId (reason: $reason) — task already drained. Ignored."
        )
      case Some((task, worker, peer)) =>
        // #1184: progress signal — used by CheckDispatchStalled.
        lastDispatchOrResponseMs = System.currentTimeMillis()
        markWorkerIdle(worker)
        // Only mark peer stateless if the task was using the CURRENT root.
        // After pivot refresh, in-flight requests with the OLD root will fail
        // with "Missing proof" — but this doesn't mean the peer can't serve the NEW root.
        if task.rootHash == stateRoot then markPeerStateless(peer, reason)
        else
          log.info(
            s"Ignoring failure from stale-root request " +
              s"(task root ${task.rootHash.take(4).toHex} != current ${stateRoot.take(4).toHex})"
          )
        log.warn(s"Task failed: $reason")
        val failedTask = task.copy(pending = false, rootHash = stateRoot)

        // Apply cooldown and reduce byte budget for protocol failures; skip for network-level
        // disconnects since the peer is already gone and will reconnect fresh.
        if !reason.contains("Missing proof for empty account range") && !reason.contains("Peer disconnected") then
          recordPeerCooldown(peer, reason, effectivePeerCooldown)
          adjustResponseBytesOnFailure(peer, reason)

        requeueOrEscalate(failedTask, reason)

  /** Re-queue a task or escalate to the controller after too many consecutive requeues.
    *
    * The hard cap is the safety net for cases the proximate fixes miss — a task that keeps failing because every peer
    * is intermittently stateless, or returns malformed responses, or any other yet-unseen mode that would otherwise
    * loop forever. Escalation surfaces the problem as PivotStateUnservable, which the controller already escalates to
    * recordCriticalFailure -> enterDormantMode after enough refreshes without progress.
    */
  private def requeueOrEscalate(task: AccountTask, reason: String): Unit =
    val newCount = task.requeueCount + 1
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.incrementRequestRetry()
    if newCount > AccountRangeCoordinator.MaxRequeuesPerTask then
      log.error(
        s"Account task ${task.rangeString} exhausted requeue budget " +
          s"($newCount > ${AccountRangeCoordinator.MaxRequeuesPerTask}, last reason: $reason). " +
          s"Escalating PivotStateUnservable to controller; task will be retried on the next root."
      )
      // Reset the counter so the next pivot has a fresh budget; preserve task position.
      pendingTasks.enqueue(task.copy(requeueCount = 0))
      // Gate the escalation on pivotRefreshRequested so only the FIRST task to exhaust its
      // budget escalates per pivot cycle. Without this, all ~16 ranges escalate at once when a
      // serve window expires, producing a PivotStateUnservable burst (observed ~50 in one log).
      // pivotRefreshRequested is cleared in the PivotRefreshed handler, so each fresh pivot
      // gets a fresh escalation.
      if !pivotRefreshRequested then
        pivotRefreshRequested = true
        // Requeues come from timeouts (scarcity) as well as empty-without-proof answers (stateless). Count toward
        // restart only when the pool holds actual stateless/snapless evidence.
        val cause =
          if statelessPeers.nonEmpty || snaplessPeers.nonEmpty || emptyResponseStrikes.nonEmpty then
            SNAPSyncController.UnservableCause.Stateless
          else SNAPSyncController.UnservableCause.PeerScarcity
        snapSyncController ! PivotStateUnservable(
          rootHash = stateRoot,
          reason = s"task ${task.rangeString} hit MaxRequeuesPerTask: $reason",
          consecutiveEmptyResponses = AccountRangeCoordinator.MaxRequeuesPerTask,
          cause = cause
        )
    else
      log.info(
        s"[ACCOUNT-REQUEUE] task ${task.rangeString} requeued " +
          s"($newCount/${AccountRangeCoordinator.MaxRequeuesPerTask}): $reason"
      )
      pendingTasks.enqueue(task.copy(requeueCount = newCount))
    tryRedispatchPendingTasks()

  def tryRedispatchPendingTasks(): Unit =
    if pendingTasks.nonEmpty then
      var eligiblePeers = knownAvailablePeers
        .filterNot(isPeerStateless)
        .filterNot(isPeerSnapless)
        .filterNot(isPeerCoolingDown)
        .toList
      // Eligible-set floor (peer-retention): whenever at least one peer is neither stateless nor snapless but the only
      // thing excluding it is a cooldown, never let the download stall at zero dispatchable peers — revive the
      // soonest-to-expire cooling peer so the pipe keeps moving. On abundant pools this never fires (eligiblePeers is
      // non-empty); on a 1-2 snap-peer pool it is the difference between forward progress and a 30s dead stall. We only
      // override cooldown — confirmed-stateless / snapless peers stay excluded, so we never re-dispatch to a peer that
      // genuinely cannot serve the current root.
      if eligiblePeers.isEmpty then
        val cooldownOnlyPeers = knownAvailablePeers
          .filterNot(isPeerStateless)
          .filterNot(isPeerSnapless)
          .filter(isPeerCoolingDown)
          .toList
        cooldownOnlyPeers
          .sortBy(p => peerCooldownUntilMs.getOrElse(p.id.value, 0L))
          .headOption
          .foreach { peer =>
            peerCooldownUntilMs.remove(peer.id.value)
            log.info(
              s"[ACCOUNT-FLOOR] All ${cooldownOnlyPeers.size} servable peers were cooling and none eligible — " +
                s"reviving ${peer.id.value.take(8)} to keep the pipe fed (peer-scarce floor)"
            )
            eligiblePeers = List(peer)
          }
      val now = System.currentTimeMillis()
      val shouldLog = now - lastStateLogMs >= StateLogIntervalMs
      if shouldLog then
        lastStateLogMs = now
        log.info(
          s"[ACCOUNT-STATE] pending=${pendingTasks.size} active=${activeTasks.size} " +
            s"workers-known=${knownAvailablePeers.size} stateless=${statelessPeers.size} " +
            s"snapless=${snaplessPeers.size} cooling=${peerCooldownUntilMs.size} " +
            s"eligible=${eligiblePeers.size} strikes=${emptyResponseStrikes.size} " +
            s"maxInflight=$maxInFlightPerPeer root=${stateRoot.take(4).toHex}"
        )
      if eligiblePeers.isEmpty then
        // Promoted from silent return to INFO so the first occurrence per 30s window
        // is visible. Sharing `shouldLog` with the STATE snapshot above keeps total
        // log volume from this method ≤ 2 lines / 30 s — robust against call-rate spikes.
        if shouldLog then
          val nowMs2 = System.currentTimeMillis()
          val soonestReadySec = peerCooldownUntilMs.values.minOption
            .map(t => math.max(0L, (t - nowMs2) / 1000))
          val coolingSuffix = soonestReadySec match
            case Some(s) => s" (soonest cooling peer ready in ${s}s)"
            case None    => ""
          log.info(
            s"[ACCOUNT-REDISPATCH] No eligible peers — ${knownAvailablePeers.size} known, " +
              s"${statelessPeers.size} stateless, ${snaplessPeers.size} snapless, " +
              s"${peerCooldownUntilMs.size} cooling${coolingSuffix}. pending: ${pendingTasks.size}"
          )
      else
        for
          peer <- eligiblePeers.sortBy(p => (inFlightForPeer(p), lastDispatchTimeMs.getOrElse(p.id.value, 0L)))
          if pendingTasks.nonEmpty
        do dispatchIfPossible(peer)
