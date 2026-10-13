package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ActiveByteCodeRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ByteCodeWorkerRelease
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ValidatedByteCodes
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes

/** The coordinator state `ByteCodeResponseHandling` reads and writes (spec 016 C-B1, research.md R14b: 7). All but
  * `hashFailureCounts` are shared with the core's behaviour arms; `hashFailureCounts` is the module's alone, but it is
  * a concrete collection `val`, so rule (d) keeps it in the core.
  */
private[actors] trait ByteCodeResponseHandlingState:
  def activeTasks: mutable.Map[BigInt, ActiveByteCodeRequest]
  var bytecodesDownloaded: Long
  var bytesDownloaded: Long
  var completedTaskCount: Long
  var consecutiveTaskFailures: Int
  def hashFailureCounts: mutable.Map[ByteString, Int]
  def pendingTasks: mutable.Queue[ByteCodeTask]

/** What `ByteCodeResponseHandling` calls in the coordinator core: the completion check. */
private[actors] trait ByteCodeCompletionApi:
  def checkCompletion(): Unit

/** Bytecode response handling (spec 016 C-B1, coordinator-characterization.md §3.1): a `ByteCodes` response's
  * verification (returned codes are a subsequence of the requested hashes, in order, no duplicates), the batched write
  * of the verified codes, per-hash failure counting with the skip after `maxFailuresPerHash`, re-queuing of the
  * unserved remainder, progress reporting; and the intake filters of the core's `StartByteCodeSync` /
  * `AddByteCodeTasks` arms: the #1501 `skipPresent` check and the dedup pass.
  */
private[actors] trait ByteCodeResponseHandling:
  self: ByteCodeResponseHandlingState & ByteCodeEnv & ByteCodeDispatchApi & ByteCodeCompletionApi =>

  private lazy val maxFailuresPerHash: Int = 50

  def handleByteCodesResponse(response: ByteCodes): Unit =
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
      val updates = codesByHash.foldLeft(stateIngest.bytecode.emptyBatchUpdate) { case (batchUpdate, (codeHash, code)) =>
        batchUpdate.and(stateIngest.bytecode.put(codeHash, code))
      }

      updates.commit()
      log.info(s"Successfully persisted ${codesByHash.size} bytecodes to storage")
      Right(())
    catch
      case e: Exception =>
        log.error(s"Failed to store bytecodes: ${e.getMessage}", e)
        Left(s"Storage error: ${e.getMessage}")

  /** The #1501 `skipPresent` check of `AddByteCodeTasks` (lifted into a method, spec 016 C-B1). */
  def skipPresentCodeHashes(received: Seq[ByteString], skipPresent: Boolean): Seq[ByteString] =
    // Replayed (carried / recovered) codeHashes were identified before a restart and many were already fetched.
    // Bytecode is content-addressed and hash-checked on write, so one already in EvmCodeStorage is done: skip it.
    // This lookup used to run on the controller thread (2.5M RocksDB reads per Sepolia resume — the mailbox lag
    // that delayed the back-pressure signal by minutes, spec 014); here it costs this coordinator, not the controller.
    if skipPresent then received.filter(h => stateIngest.bytecode.get(h).isEmpty) else received

  /** Filter and deduplicate codeHashes. Input is already Bloom-filtered by AccountRangeCoordinator (~0.01% FPR), so
    * this is a final dedup pass over ~2M entries (not 73.5M). Bug 20 fix.
    */
  def filterAndDedupeCodeHashes(
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
