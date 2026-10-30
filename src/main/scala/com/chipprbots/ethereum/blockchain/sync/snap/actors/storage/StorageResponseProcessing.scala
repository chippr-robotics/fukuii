package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.*
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** The coordinator state `StorageResponseProcessing` reads and writes (spec 016 C-S4, research.md R14b). The core's
  * behaviour arms (pivot refresh, force-complete, peer loss, the processing result, progress), `onPostStop`, the peer
  * dispatch (C-S1) or the ordered apply (C-S2) touch every one of them too, or rule (d) keeps them in the core (the
  * immutable `processingPool` and `processingEc`), so the Impl keeps them as fields. The `var`s are the ones the module
  * writes.
  */
private[actors] trait StorageResponseProcessingState:
  def stateRoot: ByteString
  def tasks: mutable.Queue[StorageTask]
  def pendingTaskKeys: mutable.Set[(ByteString, ByteString)]
  def activeTasks: mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]
  def accountSubtaskCounters: mutable.Map[ByteString, (Int, Int)]
  var completedAccountCount: Long
  def statelessPeers: mutable.Set[String]
  var consecutiveUnproductiveRefreshes: Int
  var lastDispatchOrResponseMs: Long
  def emptyResponsesByTask: mutable.HashMap[StorageTaskKey, Int]
  def staleRootFailuresByAccount: mutable.Map[ByteString, Vector[StaleRootFailure]]
  def abandonedAccounts: mutable.Set[ByteString]
  var staleRootFailureEvents: Long
  def pendingAccountTries: mutable.Map[ByteString, SnapTrie]
  def pendingResponses: mutable.Queue[PendingResponse]
  var pendingResponseBytes: Long
  var runningProcessingJobs: Int
  var processingGeneration: Long
  var trieWriteNanos: Long
  def processingPool: Option[java.util.concurrent.ThreadPoolExecutor]
  def processingEc: ExecutionContext

/** Storage-range response processing (spec 016 C-S4, coordinator-characterization.md §3.3): accepting a reply
  * (`handleResponse`: the tracker's validation and completion, then the queue), #1533's off-actor pipeline (the
  * arrival-ordered `pendingResponses` queue, the verification jobs on `processingEc`, the head-only drain that applies
  * them on the actor, the `[STORAGE-PERF]` line, the generation bump that drops a superseded result, the pool
  * shutdown), and applying one response: the empty and proof-of-absence branches with their per-task empty strikes, the
  * served branch (proof verification, inline or the worker's result, the stale-local-root give-up to healing, the
  * per-peer feedback, the hand-off to the ordering gate), the subtask split and the per-account subtask count. The
  * coordinator core keeps the behaviour arms that call it (the reply, the processing result, peer loss, pivot refresh,
  * force-complete) and the stop and restart signals.
  */
private[actors] trait StorageResponseProcessing:
  this: StorageResponseProcessingState & StorageRangeEnv & StorageResponsePeerApi & StoragePeerFeedbackApi &
    StorageChunkApplyApi & StorageTrieFlushApi & StorageCompletedTaskApi =>

  // Number of parallel subtasks to create per large-storage contract.
  // Matches go-ethereum storageConcurrency = 16 (sync.go:108).
  private lazy val storageConcurrency: Int = 16

  private lazy val maxEmptyResponsesPerTask: Int = 5

  private lazy val maxStaleRootFailuresPerAccount: Int = 3
  private def isStaleLocalRootSignature(error: String): Boolean =
    error == "complete-range hash mismatch" || error == "root node missing from proof"

  /** Give up on an account's storage after repeated stale-local-root verification failures (see
    * `staleRootFailuresByAccount`'s doc). Reuses EXACTLY the hand-off `handleEmptyResponse` already uses to defer an
    * unrecoverable-by-retry task to healing: mark the task done, discard any partial trie/ordering-gate state
    * (already-flushed content-addressed nodes stay on disk; healing reconciles), and do NOT re-queue. Deliberately
    * mirrors that method rather than inventing a second hand-off convention.
    */
  private def giveUpOnAccountForHealing(
      task: StorageTask,
      failureCount: Int,
      distinctPeers: Int,
      distinctRoots: Int
  ): Unit =
    val accountHash = task.accountHash
    // spec 022 T033: durable first; a clean end of the phase wipes the only other trace (R-5a).
    stateIngest.snapProgress.recordExcluded(
      Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, claimedStorageRoot = task.storageRoot))
    )
    staleRootFailuresByAccount.remove(accountHash)
    // Give up the WHOLE account, not just the task that tripped the cap: a large account may be split into
    // parallel subtasks sharing one stale `storageRoot`; if only this subtask were dropped, each sibling would need K
    // more failures of its own (the failure history is keyed by account and was just cleared). Drop every queued
    // sibling and tombstone the account so in-flight siblings (already dispatched, answering later) and
    // timeout/peer-loss re-queues are ignored instead of rebuilding a partial trie that could never commit correctly.
    val queuedSiblings = tasks.filter(_.accountHash == accountHash)
    if queuedSiblings.nonEmpty then
      val keep = tasks.filterNot(_.accountHash == accountHash)
      tasks.clear()
      tasks.enqueueAll(keep)
      queuedSiblings.foreach(t => pendingTaskKeys -= ((t.accountHash, t.next)))
    abandonedAccounts += accountHash
    accountSubtaskCounters.remove(accountHash)
    val doneTask = task.copy(done = true, pending = false)
    recordCompletedTask(doneTask)
    resetAccountTrie(accountHash)
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
      .setStoragePendingTries(pendingAccountTries.size.toLong)
    log.warn(
      s"Giving up on storage for account ${accountHash.toHex} after $failureCount consecutive " +
        s"stale-local-root verification failures across $distinctPeers peer(s)/$distinctRoots root(s) " +
        s"(storageRoot=${task.storageRoot.toHex}; dropped ${queuedSiblings.size} queued sibling task(s)) — deferring to healing"
    )

  private var nextResponseSeq: Long = 0L

  private var inlineVerifyNanos: Long = 0L

  /** A reply completed its request: queue it for processing and apply whatever is ready. */
  private def enqueueResponse(
      peer: Peer,
      batchTasks: Seq[StorageTask],
      requestedBytes: BigInt,
      response: StorageRanges
  ): Unit =
    val job = new PendingResponse(
      seq = nextResponseSeq,
      generation = processingGeneration,
      peer = peer,
      tasks = batchTasks,
      requestedBytes = requestedBytes,
      response = response,
      bytes = responsePayloadBytes(response),
      receivedAtNs = System.nanoTime()
    )
    nextResponseSeq += 1
    // Inline mode, or nothing to verify (empty / proof-of-absence): ready now, processServedTasks verifies inline.
    job.ready = !processing.offActor || !response.slots.exists(_.nonEmpty)
    pendingResponses.enqueue(job)
    pendingResponseBytes += job.bytes
    submitProcessingJobs()
    drainProcessedResponses()

  /** Start jobs for queued responses, oldest first, up to `processing.maxInFlightJobs` running at once. */
  def submitProcessingJobs(): Unit =
    val it = pendingResponses.iterator
    while runningProcessingJobs < processing.maxInFlightJobs && it.hasNext do
      val job = it.next()
      if !job.ready && !job.submitted then
        job.submitted = true
        runningProcessingJobs += 1
        val seq = job.seq
        val generation = job.generation
        val jobTasks = job.tasks
        val jobResponse = job.response
        // Accounts already given up on are skipped at apply time anyway; don't spend a worker on them.
        val skip = jobTasks.iterator.map(_.accountHash).filter(abandonedAccounts.contains).toSet
        val prebuildScheme = Option.when(!deferredMerkleization)(storageScheme)
        val selfRef = self
        Future(verifyServedSlots(jobTasks, jobResponse, skip, prebuildScheme))(processingEc)
          .onComplete(outcome => selfRef ! StorageResponseProcessed(seq, generation, outcome))(
            ExecutionContext.parasitic
          )

  /** Apply ready responses from the head of the queue, in arrival order. */
  def drainProcessedResponses(): Unit =
    while pendingResponses.headOption.exists(_.ready) do
      val job = pendingResponses.dequeue()
      pendingResponseBytes = (pendingResponseBytes - job.bytes).max(0L)
      val startNs = System.nanoTime()
      trieWriteNanos = 0L
      inlineVerifyNanos = 0L
      processStorageRanges(
        job.peer,
        job.tasks,
        job.requestedBytes,
        job.response,
        job.verified,
        dispatchToPeer = !job.peerGone
      )
      flushTrieNodeWrites()
      logStoragePerf(job, startNs, System.nanoTime() - startNs)

  private def logStoragePerf(job: PendingResponse, startNs: Long, actorNanos: Long): Unit =
    val served = job.response.slots.count(_.nonEmpty)
    if served > 0 then
      def ms(nanos: Long): Long = nanos / 1000000L
      val slotCount = job.response.slots.iterator.map(_.size).sum
      val verify = job.verified match
        case Some(v) => s"${ms(v.workerNanos)}ms(worker)"
        case None    => s"${ms(inlineVerifyNanos)}ms(actor)"
      log.info(
        s"[STORAGE-PERF] accounts=${job.tasks.size} served=$served slots=$slotCount bytes=${job.bytes} " +
          s"verify=$verify actor=${ms(actorNanos)}ms trieWrite=${ms(trieWriteNanos)}ms " +
          s"wait=${ms(startNs - job.receivedAtNs)}ms queued=${pendingResponses.size} jobs=$runningProcessingJobs"
      )

  /** Drop every response not yet applied and start a new generation (late results are then ignored). Returns them. */
  def abandonPendingResponses(): Seq[PendingResponse] =
    val dropped = pendingResponses.toList
    pendingResponses.clear()
    pendingResponseBytes = 0L
    processingGeneration += 1
    dropped

  /** Tests: whether the owned worker pool has been shut down (None when there is none). */
  private[actors] def processingPoolShutDown: Option[Boolean] = processingPool.map(_.isShutdown)

  def shutdownProcessingPool(): Unit =
    processingPool.foreach(_.shutdown()) // running jobs finish (they hold no resources); nothing new is accepted

  def handleResponse(response0: StorageRanges): Unit =
    // Index the response once (O(n)): the ascending-order checks below and in MerkleProofVerifier index by position
    // (#1533). Decoded RLP lists are Vectors now; the decoder's old Queue made every `slots(i)` O(i) (#1672/#1675).
    val response = indexedResponse(response0)
    requestTracker.validateStorageRanges(response) match
      case Left(error) =>
        log.warn(s"Invalid StorageRanges response: $error")

      case Right(validResponse) =>
        val slotCount = validResponse.slots.map(_.size).sum
        requestTracker.completeRequest(response.requestId, slotCount.max(1)) match
          case None =>
            log.warn(s"Received response for unknown request ID ${response.requestId}")

          case Some(_) =>
            activeTasks.remove(response.requestId) match
              case None =>
                log.warn(s"No active tasks for request ID ${response.requestId}")

              case Some((peer, batchTasks, requestedBytes)) =>
                // Answered: the request is complete (no timeout can blame the peer from here on). Processing happens
                // when the response reaches the head of the queue — now, unless earlier responses are still verifying.
                enqueueResponse(peer, batchTasks, requestedBytes, validResponse)

  private def processStorageRanges(
      peer: Peer,
      tasks: Seq[StorageTask],
      requestedBytes: BigInt,
      response: StorageRanges,
      verified: Option[VerifiedStorageResponse],
      dispatchToPeer: Boolean
  ): Unit =
    // Count only responses that actually contain slot data as "served".
    // Proof-only responses (0 slot-sets, non-empty proofs) are NOT counted as served because:
    //  1. After a pivot refresh, peers may return proof-of-absence for stale task roots
    //  2. The proof root may not match the task's storageRoot (undetected by lenient verification)
    //  3. Treating proof-only as served prevents stateless detection, causing indefinite stalls
    // Legitimate empty-storage accounts will be completed via the empty-response skip mechanism
    // after maxEmptyResponsesPerTask attempts.
    val servedCount: Int = response.slots.count(_.nonEmpty)

    log.info(
      s"Processing storage ranges for ${tasks.size} accounts from peer ${peer.id.value}, " +
        s"received ${response.slots.size} slot sets (served=$servedCount, proofs=${response.proof.size})"
    )

    // Proof-of-absence: server returned 0 slots WITH proof nodes. Per the snap/1 protocol,
    // this is a valid cryptographic proof that no slots exist in [startingHash, limitHash]
    // at the current state root. The account's storage is empty or was modified/cleared
    // since the original pivot. Healing will validate the final trie.
    // IMPORTANT: do NOT mark the peer stateless — it served a valid, well-formed response.
    // Only fall through to stateless marking when proofs == 0 (peer gave us nothing at all).
    def handleProofOfAbsence(): Unit =
      // Route through the same ordered-completion/cursor bookkeeping as any other served chunk
      // (applyOrderedStorageChunk / applyReadyStorageChunk): a proof-of-absence response is a
      // complete, valid answer for THIS chunk's [next, last] — like a served chunk whose slots
      // reach task.last, just with zero slots — so it must advance storageTrieCursor and count
      // towards accountSubtaskCounters the same way. Continuation and subtask chunks are always
      // dispatched solo (requestNextRanges/isInitialRange), so this is reached for genuinely
      // mid-range, multi-chunk accounts, not just fresh/whole-account requests: without this, a
      // legitimately-empty sub-range of a sparse account permanently blocks that account's
      // completion — and any higher-range siblings already buffered behind it — until the whole
      // storage phase stalls out to the force-complete fallback.
      val task = tasks.head
      applyOrderedStorageChunk(peer, task, Seq.empty, response.proof)
      log.warn(
        s"Storage proof-of-absence accepted: account=${task.accountString} " +
          s"storageRoot=${task.storageRoot.take(4).toHex} range=${task.rangeString} " +
          s"proofNodes=${response.proof.size} peer=${peer.id.value}. " +
          s"Account storage empty/changed at current pivot — healing will validate."
      )
      // Peer is healthy — clear any penalty state it accumulated.
      statelessPeers.remove(peer.id.value)
      recordPeerAnswered(peer.id.value)
      lastDispatchOrResponseMs = System.currentTimeMillis()
      consecutiveUnproductiveRefreshes = 0
      self ! StorageCheckCompletion
      if dispatchToPeer then dispatchIfPossible(peer)

    // Empty response with no usable proof-of-absence: re-queue/skip tasks and mark peer stateless.
    def handleEmptyResponse(): Unit =
      // Per-peer batch reduction: only reduce for the specific peer that failed
      if tasks.size > 1 && batchSizeFor(peer) > 1 then
        log.info(
          s"Received empty StorageRanges for a batched request from peer ${peer.id.value} (accounts=${tasks.size}); " +
            s"falling back to single-account requests for this peer"
        )
        reduceBatchSize(peer)

      adjustResponseBytesOnFailure(peer, "empty response")

      // Track empties per task to avoid re-queueing forever.
      // If the same task yields empty responses repeatedly, skip it with a loud warning.
      var skipped = 0
      tasks.filterNot(t => abandonedAccounts.contains(t.accountHash)).foreach { task =>
        val key = StorageTaskKey(task.accountHash, task.next, task.last)
        val attempts = emptyResponsesByTask.getOrElse(key, 0) + 1
        emptyResponsesByTask.update(key, attempts)

        if attempts >= maxEmptyResponsesPerTask then
          // spec 022 T033: durable first; a clean end of the phase wipes the only other trace (R-5a).
          stateIngest.snapProgress.recordExcluded(
            Seq(ExcludedAccount(task.accountHash, ExcludedReason.StorageSkipped, claimedStorageRoot = task.storageRoot))
          )
          skipped += 1
          val doneTask = task.copy(done = true, pending = false)
          recordCompletedTask(doneTask)
          // Discard any partial streaming trie for this account — committing now would
          // produce a wrong root (missing slots). Already-flushed content-addressed nodes
          // stay on disk and healing reconciles when the contract is revisited.
          resetAccountTrie(task.accountHash)
          com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
            .setStoragePendingTries(pendingAccountTries.size.toLong)
          log.warn(
            s"Skipping storage task after $attempts empty StorageRanges replies: " +
              s"account=${task.accountHash.toHex} storageRoot=${task.storageRoot.toHex} range=${task.rangeString}"
          )
        else
          this.tasks.enqueue(task.copy(pending = false))
          log.debug(
            s"Empty StorageRanges for task (attempt $attempts/$maxEmptyResponsesPerTask); re-queueing: " +
              s"account=${task.accountHash.take(4).toHex} range=${task.rangeString}"
          )
      }

      // Always mark this peer as stateless for the current root on empty response.
      // Even if some tasks were skipped, the peer still couldn't serve any data.
      // This ensures stateless detection triggers pivot refresh when ALL peers fail,
      // rather than silently draining tasks as "empty" one by one.
      markPeerStateless(peer)

      if skipped > 0 then self ! StorageCheckCompletion

    if servedCount == 0 then
      if response.proof.nonEmpty && tasks.size == 1 then handleProofOfAbsence()
      else handleEmptyResponse()
    else processServedTasks(peer, tasks, requestedBytes, response, servedCount, verified, dispatchToPeer)

  /** Handle the non-empty (served) branch of a StorageRanges response: clear stateless marking, verify proofs, stream
    * slots into per-account tries, and stage flat-slot writes.
    */
  private[actors] def processServedTasks(
      peer: Peer,
      tasks: Seq[StorageTask],
      requestedBytes: BigInt,
      response: StorageRanges,
      servedCount: Int,
      verified: Option[VerifiedStorageResponse] = None,
      dispatchToPeer: Boolean = true
  ): Unit =
    // Non-empty response with actual slot data — clear stateless marking and reset backoff.
    statelessPeers.remove(peer.id.value)
    recordPeerSuccess(peer.id.value)
    consecutiveUnproductiveRefreshes = 0
    lastDispatchOrResponseMs = System.currentTimeMillis()

    // Adaptive batch scaling: track successes for this peer, scale up after consecutive packed responses
    maybeIncreaseBatchSize(peer, servedCount, tasks.size)

    // Clear empty-response counters for tasks that are now being served.
    tasks.foreach { task =>
      emptyResponsesByTask.remove(StorageTaskKey(task.accountHash, task.next, task.last))
    }

    val servedTasks = tasks.take(servedCount)
    val unservedTasks = tasks.drop(servedCount)

    if unservedTasks.nonEmpty then
      log.debug(s"Re-queueing ${unservedTasks.size} unserved storage tasks")
      unservedTasks.filterNot(t => abandonedAccounts.contains(t.accountHash)).foreach { task =>
        this.tasks.enqueue(task.copy(pending = false))
      }

    // Track total received bytes across all served tasks for adaptive byte budgeting
    var totalReceivedBytes: Long = 0

    // Given-up accounts (see giveUpOnAccountForHealing): a late/in-flight sibling response is ignored, not verified,
    // applied or re-queued. Index alignment with `response.slots` is preserved by filtering AFTER zipWithIndex.
    servedTasks.zipWithIndex
      .filter { case (t, _) =>
        val abandoned = abandonedAccounts.contains(t.accountHash)
        if abandoned then recordCompletedTask(t.copy(done = true, pending = false))
        !abandoned
      }
      .foreach { case (task, idx) =>
        val accountSlots = servedSlotsAt(response, idx)

        // Best-practice: apply proof nodes only to the last served slot-set.
        val proofForThisTask = proofAt(response, idx, servedCount)

        // Verified off-actor when the response went through a worker (#1533); otherwise (inline mode, or an account the
        // worker skipped) here, with the same function.
        val verification = verified.flatMap(_.verifications.lift(idx).flatten).getOrElse {
          val startNs = System.nanoTime()
          val result = verifyStorageChunk(task, accountSlots, proofForThisTask)
          inlineVerifyNanos += System.nanoTime() - startNs
          result
        }
        verification match
          case Left(error) if isStaleLocalRootSignature(error) =>
            // Not evidence of peer fault (see staleRootFailuresByAccount's doc) — no peer penalty.
            val failures = staleRootFailuresByAccount.getOrElse(task.accountHash, Vector.empty) :+
              StaleRootFailure(peer.id.value, stateRoot)
            staleRootFailuresByAccount(task.accountHash) = failures
            staleRootFailureEvents += 1
            val distinctPeers = failures.map(_.peerId).distinct.size
            val distinctRoots = failures.map(_.root).distinct.size
            log.warn(
              s"Storage proof verification failed for account ${task.accountString}: $error " +
                s"(stale-local-root attempt ${failures.size}/$maxStaleRootFailuresPerAccount, " +
                s"peers=$distinctPeers, roots=$distinctRoots)"
            )
            if failures.size >= maxStaleRootFailuresPerAccount && (distinctPeers >= 2 || distinctRoots >= 2) then
              giveUpOnAccountForHealing(task, failures.size, distinctPeers, distinctRoots)
              self ! StorageCheckCompletion
            else this.tasks.enqueue(task.copy(pending = false))

          case Left(error) =>
            log.warn(s"Storage proof verification failed for account ${task.accountString}: $error")
            recordPeerCooldown(peer, s"verification failed: $error")
            adjustResponseBytesOnFailure(peer, s"verification failed: $error")
            this.tasks.enqueue(task.copy(pending = false))

          case Right(_) =>
            // A successful verification clears any stale-local-root failure history for this
            // account (requirement: "an account that fails once and then succeeds is not dropped") —
            // whatever caused the earlier mismatch no longer applies.
            staleRootFailuresByAccount.remove(task.accountHash)
            val slotBytes = accountSlots.map { case (hash, value) => hash.size + value.size }.sum
            totalReceivedBytes += slotBytes
            // Cross-chunk range ordering (storageConcurrency parallel subtasks racing on response
            // arrival) is enforced by the gate, not here — see applyOrderedStorageChunk.
            applyOrderedStorageChunk(
              peer,
              task,
              accountSlots,
              proofForThisTask,
              verified.flatMap(_.prebuilt.get(idx))
            )
      }

    // Adjust per-peer byte budget based on total received bytes
    if totalReceivedBytes > 0 then adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(totalReceivedBytes))

    // Check completion after processing all served tasks
    self ! StorageCheckCompletion

    // Immediately pipeline more work to this peer — don't wait for StoragePeerAvailable
    if dispatchToPeer then dispatchIfPossible(peer)

  /** Split a large-storage account's remaining slot range into parallel subtasks.
    *
    * Called on first continuation detection — when a SNAP response has a proof but doesn't cover `task.last`,
    * indicating the contract has more slots than fit in one 512 KB packet. Creates N parallel StorageTask objects
    * covering consecutive disjoint ranges. Mirrors go-ethereum's subtask creation in `assignStorageTasks()`
    * (sync.go:2118-2197) and `newHashRange()` (sync.go:2144-2193).
    *
    * @param task
    *   The original task that triggered the continuation (covers [task.next, task.last])
    * @param lastSlotReceived
    *   The last slot hash in the partial response (split starts at `incrementHash32(lastSlotReceived)`)
    * @return
    *   Sequence of StorageTask objects covering [lastSlotReceived+1, task.last] in equal segments
    */
  def createStorageSubTasks(task: StorageTask, lastSlotReceived: ByteString): Seq[StorageTask] =
    val chunks = storageConcurrency
    StorageTask.createSubTasks(
      accountHash = task.accountHash,
      storageRoot = task.storageRoot,
      from = StorageTask.incrementHash32(lastSlotReceived),
      to = task.last,
      numChunks = chunks
    )

  /** Record completion of one subtask and increment completedAccountCount when all done.
    *
    * Mirrors go-ethereum's `cleanStorageTasks()` (sync.go:982-1015) pend-decrement logic.
    *
    * @param accountHash
    *   Hash of the account whose subtask just completed
    * @return
    *   `true` iff this was the account's LAST outstanding subtask (or the account was never tracked as subtasked at
    *   all) — i.e. the account as a whole is now fully downloaded. Callers use this to gate `commitAccountTrie`:
    *   committing on a single chunk's own completion (instead of the account's) would remove/finalise the shared trie
    *   while sibling chunks covering higher sub-ranges are still in flight, corrupting the on-disk path-keyed nodes for
    *   this account.
    */
  def recordSubtaskCompletion(accountHash: ByteString): Boolean =
    accountSubtaskCounters.get(accountHash) match
      case None =>
        completedAccountCount += 1
        true
      case Some((total, done)) =>
        val newDone = done + 1
        if newDone >= total then
          completedAccountCount += 1
          accountSubtaskCounters.remove(accountHash)
          log.debug(
            s"All $total storage subtasks complete for account ${accountHash.take(4).toHex} — " +
              s"advancing completedAccountCount to $completedAccountCount"
          )
          true
        else
          accountSubtaskCounters(accountHash) = (total, newDone)
          log.debug(
            s"Storage subtask $newDone/$total done for account ${accountHash.take(4).toHex}"
          )
          false
