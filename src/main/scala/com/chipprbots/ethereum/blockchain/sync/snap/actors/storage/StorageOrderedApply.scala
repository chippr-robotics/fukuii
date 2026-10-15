package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapPathTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ByteStringOrdering
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PathNodeOp
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PrebuiltStorageTrie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ReadyStorageChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.TrieNodeWrite
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** The coordinator state `StorageOrderedApply` reads and writes (spec 016 C-S2, research.md R14b). The core's behaviour
  * arms, the peer dispatch (C-S1) or the response processing and done markers that C-S3/C-S4 will move touch every one
  * of them too (or rule (d) keeps them in the core), so the Impl keeps them as fields. The four `var`s are the ones the
  * module writes.
  */
private[actors] trait StorageOrderedApplyState:
  def stateRoot: ByteString
  def tasks: mutable.Queue[StorageTask]
  def accountSubtaskCounters: mutable.Map[ByteString, (Int, Int)]
  var completedAccountCount: Long
  var slotsDownloaded: Long
  var bytesDownloaded: Long
  def pendingAccountTries: mutable.Map[ByteString, SnapTrie]
  def storageTrieCursor: mutable.Map[ByteString, ByteString]
  def pendingOrderedChunks: mutable.Map[ByteString, mutable.TreeMap[ByteString, ReadyStorageChunk]]
  def zeroSlotHash: ByteString
  def lastAppliedSlot: mutable.Map[ByteString, (ByteString, ByteString)]
  def trieNodeWrites: mutable.ArrayBuffer[TrieNodeWrite]
  var trieWriteNanos: Long

/** What `StorageOrderedApply` calls in the coordinator core: the flat-slot staging and the completion markers (C-S3's
  * until it moves), and the subtask split and its completion count (C-S4's until it moves).
  */
private[actors] trait StorageApplyCoreApi:
  def stageFlatSlotChunk(accountHash: ByteString, slots: Seq[(ByteString, ByteString)]): Unit
  def createStorageSubTasks(task: StorageTask, lastSlotReceived: ByteString): Seq[StorageTask]
  def recordSubtaskCompletion(accountHash: ByteString): Boolean
  def stageDoneMarker(accountHash: ByteString, storageRoot: ByteString): Unit
  def recordCompletedTask(task: StorageTask): Unit

/** What the done markers (C-S3) call in the ordered apply: the storage-trie node flush, which writes an account's trie
  * nodes before a flat batch that may carry its completion marker. A separate trait, so the C-S2 interfaces stay as
  * recorded.
  */
private[actors] trait StorageTrieFlushApi:
  def flushTrieNodeWrites(): Unit

/** Storage-range ordered apply (spec 016 C-S2, coordinator-characterization.md §3.3): the range-ascending ordering gate
  * (buffer an out-of-order chunk, drain the ones now in line), applying one in-order chunk (the boundary-repeat dedup,
  * streaming the slots into the account's trie or writing the trie a worker built whole (#1533), staging the flat-slot
  * mirror, the continuation or subtask split with its persisted storage cursor, and the account's commit and completion
  * marker once every subtask has landed), the per-account trie lifecycle (create, commit, reset) and the batched
  * storage-trie node writes (`flushTrieNodeWrites`). The coordinator core keeps the behaviour arms that call it, and
  * flushes the node buffer after every message.
  */
private[actors] trait StorageOrderedApply extends StorageTrieFlushApi:
  this: StorageOrderedApplyState & StorageRangeEnv & StorageApplyCoreApi & StoragePeerFeedbackApi =>

  /** Discard the ordering-gate state for one account: its cursor, last-applied-slot marker, and any buffered (verified
    * but not-yet-applied) chunks. Called everywhere `pendingAccountTries` is reset/removed for that account, so the
    * three stay consistent. Returns the discarded buffer (possibly empty) so callers can decide whether to re-queue or
    * abandon its contents.
    */
  private[actors] def clearStorageOrderingState(accountHash: ByteString): Seq[StorageTask] =
    storageTrieCursor.remove(accountHash)
    lastAppliedSlot.remove(accountHash)
    pendingOrderedChunks.remove(accountHash) match
      case Some(buffered) => buffered.values.map(_.task).toSeq
      case None           => Seq.empty

  /** Get-or-create the per-account [[SnapTrie]]. Each contract's trie streams emitted nodes to storage.
    *
    * HashScheme (default): nodes flush to `mptStorage` via `storeRawNodes`. PathScheme: nodes written path-keyed to
    * `PathNodeStorage`, scoped by `accountHash` (so multiple storage tries don't collide on path keys).
    */
  private def getOrCreateAccountTrie(accountHash: ByteString): SnapTrie =
    pendingAccountTries.getOrElseUpdate(
      accountHash,
      storageScheme match
        case StorageScheme.Hash =>
          new SnapHashTrie(batch => mptStorage.storeRawNodes(batch))
        case StorageScheme.Path =>
          val pns = stateIngest.pathNodes.getOrElse(
            throw new IllegalStateException("PathScheme requires pathNodeStorage to be set")
          )
          // Nodes go to `trieNodeWrites` and are committed as one batch (flushTrieNodeWrites), not one RocksDB write
          // each: same keys, values and order (#1533).
          new SnapPathTrie(
            owner = accountHash,
            skipLeftBoundary = false, // storage tasks are always fresh (no per-slot resume cursor)
            writePath = (path, _, blob) => trieNodeWrites += pns.storageNodeUpsert(accountHash, path, blob),
            deleteExact = path => trieNodeWrites += pns.storageNodeRemoval(accountHash, path)
          )
    )

  /** Commit a fully-downloaded contract trie. Compares the computed root against the task's claimed `storageRoot`;
    * mismatches log a warning but are otherwise accepted — healing reconciles. Returns the contract's storage root.
    */
  private def commitAccountTrie(accountHash: ByteString, claimedRoot: ByteString): ByteString =
    pendingAccountTries.remove(accountHash) match
      case Some(trie) =>
        val computedRoot = trie.commit()
        if computedRoot != claimedRoot then
          log.warn(
            s"Storage root mismatch for account ${accountHash.take(4).toHex}: " +
              s"computed=${computedRoot.take(4).toHex} claimed=${claimedRoot.take(4).toHex} — healing will reconcile"
          )
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
          .setStoragePendingTries(pendingAccountTries.size.toLong)
        computedRoot
      case None =>
        // First/only response for an account with no slots, or proof-of-absence path —
        // no trie was ever created. Caller still wants a non-null root reference.
        claimedRoot

  /** [[commitAccountTrie]] for a trie a worker built whole (its nodes are already written): same check and log. */
  private def commitPrebuiltRoot(
      accountHash: ByteString,
      claimedRoot: ByteString,
      built: PrebuiltStorageTrie
  ): ByteString =
    if built.root != claimedRoot then
      log.warn(
        s"Storage root mismatch for account ${accountHash.take(4).toHex}: " +
          s"computed=${built.root.take(4).toHex} claimed=${claimedRoot.take(4).toHex} — healing will reconcile"
      )
    com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
      .setStoragePendingTries(pendingAccountTries.size.toLong)
    built.root

  /** Discard a partial trie when the account is aborted (pivot refresh, max-empty skip, force-complete).
    * Already-flushed content-addressed nodes stay on disk; only the in-memory stack-trie state is dropped.
    */
  def resetAccountTrie(accountHash: ByteString): Unit =
    pendingAccountTries.remove(accountHash).foreach { trie =>
      trie.reset()
    }
    // Abandon this account's ordering-gate state too — any buffered (not-yet-applied) sibling
    // chunks are dropped along with the trie, not re-queued: this path is reached only after
    // maxEmptyResponsesPerTask consecutive empty replies for ONE chunk, i.e. a deliberate
    // "give up on this account, healing reconciles" decision, matching the trie-discard above.
    val _ = clearStorageOrderingState(accountHash)

  /** Commit the buffered storage-trie nodes in one RocksDB batch. Called before anything that must not run ahead of
    * them (a flat batch that may carry completion markers, a persisted storage cursor), after each applied response,
    * and at the end of every message.
    */
  def flushTrieNodeWrites(): Unit =
    if trieNodeWrites.nonEmpty then
      val startNs = System.nanoTime()
      val updates = trieNodeWrites.toList
      trieNodeWrites.clear()
      val pns = stateIngest.pathNodes.getOrElse(
        throw new IllegalStateException("PathScheme requires pathNodeStorage to be set")
      )
      pns.dataSource.update(updates)
      trieWriteNanos += System.nanoTime() - startNs

  /** Write the nodes a worker built for an account served whole in one response. */
  private def writePrebuiltTrie(accountHash: ByteString, prebuilt: PrebuiltStorageTrie): Unit =
    prebuilt match
      case PrebuiltStorageTrie.Path(ops, _) =>
        val pns = stateIngest.pathNodes.getOrElse(
          throw new IllegalStateException("PathScheme requires pathNodeStorage to be set")
        )
        ops.foreach {
          case PathNodeOp.Put(path, blob) => trieNodeWrites += pns.storageNodeUpsert(accountHash, path, blob)
          case PathNodeOp.Delete(path)    => trieNodeWrites += pns.storageNodeRemoval(accountHash, path)
        }
      case PrebuiltStorageTrie.Hash(batches, _) =>
        batches.foreach(mptStorage.storeRawNodes)

  /** Ordering gate: apply a verified storage-range chunk to its account's shared trie immediately if it is the next
    * range-ascending chunk expected, otherwise buffer it until earlier sibling chunks have landed. See the
    * "Range-ascending ordering gate" field-block (near `pendingAccountTries`) for the full rationale.
    *
    * A task whose `next` is the account's true starting point (`zeroSlotHash`) is always the very first data ever seen
    * for that account's CURRENT trie generation — subtask splitting only happens AFTER this response is processed (see
    * `applyReadyStorageChunk`), so nothing can race it, and no cursor is tracked for it. Skipping the gate for this
    * case keeps the common (non-subtasked, small-account) path free of any bookkeeping.
    *
    * A `next` for which NO cursor is currently tracked (despite not being `zeroSlotHash`) means this account's ordering
    * state was just wiped (pivot refresh / force-complete / postStop) while this chunk was in flight — those wipes
    * discard ALL prior trie progress for the account, so whichever surviving chunk arrives first really is the first
    * the (fresh) trie will ever see, and applying it immediately is correct. `StoragePivotRefreshed` additionally
    * re-derives an explicit cursor (the minimum `next` among survivors) for chunks it re-queues, so this fallback is
    * only exercised for chunks that out-race that re-derivation.
    */
  private[actors] def applyOrderedStorageChunk(
      peer: Peer,
      task: StorageTask,
      accountSlots: Seq[(ByteString, ByteString)],
      proofForThisTask: Seq[ByteString],
      prebuilt: Option[PrebuiltStorageTrie] = None
  ): Unit =
    val accountHash = task.accountHash
    val outOfOrder =
      task.next != zeroSlotHash && storageTrieCursor.get(accountHash).exists(_ != task.next)
    if outOfOrder then
      val perAccount =
        pendingOrderedChunks.getOrElseUpdate(
          accountHash,
          mutable.TreeMap.empty[ByteString, ReadyStorageChunk](ByteStringOrdering)
        )
      perAccount.update(task.next, ReadyStorageChunk(peer, task, accountSlots, proofForThisTask))
      log.debug(
        s"Storage chunk out of range-order for account ${task.accountString}: " +
          s"got next=${task.next.take(4).toHex} expected=${storageTrieCursor.get(accountHash).map(_.take(4).toHex)}; " +
          s"buffering (${perAccount.size} chunk(s) now waiting for this account)"
      )
    else
      applyReadyStorageChunk(peer, task, accountSlots, proofForThisTask, prebuilt)
      drainOrderedStorageChunks(accountHash)

  /** After applying a chunk (and possibly advancing the account's cursor), release any buffered chunks that are now
    * next-in-line — repeating until either the buffer is empty or the next required position hasn't arrived yet. Each
    * drained chunk carries its OWN originating peer (stored in `ReadyStorageChunk`) — NOT necessarily the peer that
    * answered whichever response triggered this drain — so a duplicate-value rejection discovered here penalises the
    * peer that actually served the bad data.
    */
  private[actors] def drainOrderedStorageChunks(accountHash: ByteString): Unit =
    var draining = true
    while draining do
      val ready = for
        perAccount <- pendingOrderedChunks.get(accountHash)
        expected <- storageTrieCursor.get(accountHash)
        chunk <- perAccount.get(expected)
      yield (expected, chunk)
      ready match
        case Some((chunkStart, chunk)) =>
          val perAccount = pendingOrderedChunks(accountHash)
          perAccount.remove(chunkStart)
          // Prune the now-possibly-empty per-account TreeMap so `pendingOrderedChunks.get(acct)`
          // is `Some` only when a chunk is genuinely buffered — keeps clearStorageOrderingState's
          // "leftover" check and any external inspection (tests, metrics) accurate without relying
          // on every caller to also check `.isEmpty` on the inner map.
          if perAccount.isEmpty then pendingOrderedChunks.remove(accountHash)
          applyReadyStorageChunk(chunk.peer, chunk.task, chunk.accountSlots, chunk.proof)
        case None => draining = false

  /** Apply one verified, in-order storage chunk: insert its slots into the account's shared streaming trie, stage the
    * flat-slot mirror, and either enqueue a continuation/subtask split or — once every subtask for the account has
    * landed — commit the trie. Only ever called (via `applyOrderedStorageChunk`) for a chunk whose `task.next` is
    * exactly the position the trie is currently expecting, so cross-CHUNK raciness can never violate ascending order
    * here (see StackTrie.scala) — but a single chunk's response can still legitimately repeat the boundary slot the
    * trie already has (see `lastAppliedSlot`'s doc), so that case is filtered/validated before any insert happens.
    */
  private[actors] def applyReadyStorageChunk(
      peer: Peer,
      task0: StorageTask,
      accountSlots: Seq[(ByteString, ByteString)],
      proofForThisTask: Seq[ByteString],
      prebuilt: Option[PrebuiltStorageTrie] = None
  ): Unit =
    val accountHash = task0.accountHash
    var task = task0

    // Nodes a worker built for an account served whole in this one response (#1533). They are exactly the nodes this
    // method would emit into a fresh trie, so they are used only when it would build a fresh one: no trie, no
    // ordering-gate or subtask state for the account, the account's first chunk, and no continuation (empty proof).
    val wholeAccountTrie = prebuilt.filter { _ =>
      !deferredMerkleization && task0.next == zeroSlotHash && proofForThisTask.isEmpty &&
      !pendingAccountTries.contains(accountHash) && !lastAppliedSlot.contains(accountHash) &&
      !accountSubtaskCounters.contains(accountHash)
    }

    // Drop an exact repeat of the last-applied (key, value) — SNAP/1's origin is inclusive, so a
    // continuation or sub-range response's first slot legitimately CAN be the boundary slot this
    // account's trie already has. A DIFFERENT value under that same key means the peer's data is
    // inconsistent: reject the WHOLE response (peer penalty, retry) rather than accepting a
    // possibly-wrong value or letting the mismatch reach StackTrie.update's `require`. This check
    // runs before any insertion, so a rejected response never partially applies.
    val dedupedOrRejected: Either[String, Seq[(ByteString, ByteString)]] =
      lastAppliedSlot.get(accountHash) match
        case None => Right(accountSlots)
        case Some((lastKey, lastValue)) =>
          accountSlots.find(_._1 == lastKey) match
            case Some((_, repeatedValue)) if repeatedValue != lastValue =>
              Left(
                s"peer re-served slot ${lastKey.take(4).toHex} with a different value " +
                  s"than already applied (had ${lastValue.take(4).toHex}, got ${repeatedValue.take(4).toHex})"
              )
            case _ => Right(accountSlots.filterNot(_._1 == lastKey))

    dedupedOrRejected match
      case Left(reason) =>
        log.warn(s"Storage chunk rejected for account ${task.accountString}: $reason")
        recordPeerCooldown(peer, s"duplicate-key value mismatch: $reason")
        adjustResponseBytesOnFailure(peer, s"duplicate-key value mismatch: $reason")
        this.tasks.enqueue(task.copy(pending = false))

      case Right(dedupedSlots) =>
        if dedupedSlots.nonEmpty then
          // Stream slots directly into the per-account `SnapHashTrie`. Within one response, ordering
          // is already validated by MerkleProofVerifier before verification succeeds; across
          // responses for the same account, the caller (applyOrderedStorageChunk) guarantees this
          // call only happens in range-ascending order, and the dedup above has already removed any
          // exact repeat of the trie's current boundary key.
          if !deferredMerkleization then
            wholeAccountTrie match
              case Some(built) => writePrebuiltTrie(accountHash, built)
              case None =>
                val trie = getOrCreateAccountTrie(accountHash)
                dedupedSlots.foreach { case (slotHash, slotValue) =>
                  trie.update(slotHash.toArray, slotValue.toArray)
                }
                com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
                  .setStoragePendingTries(pendingAccountTries.size.toLong)

          // Flat-slot mirror — accountHash ++ slotHash → slotValue. Sorted in
          // `stageFlatSlotChunk` and accumulated for an off-actor batched commit.
          stageFlatSlotChunk(accountHash, dedupedSlots)
          lastAppliedSlot(accountHash) = dedupedSlots.last

          slotsDownloaded += dedupedSlots.size
          bytesDownloaded += dedupedSlots.map { case (hash, value) => hash.size + value.size }.sum

          snapSyncController ! SNAPSyncController.ProgressStorageSlotsSynced(dedupedSlots.size.toLong)

        // Whether THIS chunk needs a further continuation. A response with slots and a non-empty
        // proof that stops short of task.last means more remains in (lastSlot, task.last]. An EMPTY
        // response — whether a batched fresh account's genuine "no storage at all", a solo
        // subtask/continuation chunk's proof-of-absence for its OWN sub-range (routed here from
        // handleProofOfAbsence, realistic for a sparse contract's sub-range and NOT rare), or this
        // response's only slot having just been dropped as a boundary repeat — always means this
        // chunk's range is fully served: per SNAP spec, a peer omits the proof only when returning
        // the entire remainder, and includes one to prove absence otherwise, but either way there is
        // nothing left to ask this chunk's [next, last] for. Deliberately uses the ORIGINAL
        // `accountSlots` (not `dedupedSlots`): whether more data remains is a property of what the
        // PEER reported, independent of whether this response's first entry duplicated the boundary.
        val needsContinuation = accountSlots.nonEmpty && proofForThisTask.nonEmpty && {
          val lastSlot = accountSlots.last._1
          java.util.Arrays.compareUnsigned(lastSlot.toArray, task.last.toArray) < 0
        }

        if needsContinuation then
          val lastSlot = accountSlots.last._1
          // Explicitly (not lazily) fix the next-expected position: this is the only moment a new
          // chunk/continuation for this account is created, so it is the only safe place to set the
          // cursor. Inferring it later from "whichever sibling answers first" would reintroduce
          // exactly the race this gate exists to prevent.
          storageTrieCursor(accountHash) = StorageTask.incrementHash32(lastSlot)
          if accountSubtaskCounters.contains(accountHash) then
            // Already split into subtasks — this is a within-subtask continuation.
            val cont = StorageTask.createContinuation(task, lastSlot)
            this.tasks.enqueue(cont)
            log.debug(s"Within-subtask continuation for account ${task.accountString}")
          else
            // First continuation for this account → split into N parallel subtasks.
            val subtasks = createStorageSubTasks(task, lastSlot)
            subtasks.foreach(st => this.tasks.enqueue(st))
            accountSubtaskCounters(accountHash) = (subtasks.size, 0)
            log.debug(
              s"Large-storage account ${task.accountString}: " +
                s"split into ${subtasks.size} parallel subtasks"
            )
          // Persist the advancing storage cursor for crash recovery. Best-effort: concurrent
          // subtask writes for the same account may race, but worst case is a partial
          // re-download on resume, never data corruption. The trie nodes it implies are written first.
          if snapProgressStorage.isDefined then flushTrieNodeWrites()
          snapProgressStorage.foreach(
            _.writeStorageCursor(stateRoot, accountHash, StorageTask.incrementHash32(lastSlot))
          )
        else
          // This CHUNK's own assigned range is exhausted — either its slots reached task.last, or it
          // is legitimately empty (proof-of-absence for its own sub-range, or a fresh account with no
          // storage at all). Advance the cursor to the chunk's own upper bound, NOT
          // incrementHash32(lastSlot): for a non-empty completing response nothing more will ever
          // arrive between lastSlot and task.last, and for an empty one there is no lastSlot at all.
          // Either way the next sibling chunk's `next` is defined as incrementHash32(task.last)
          // (StorageTask.createSubTasks), so that is the only value that safely unblocks it — and the
          // ONLY value, whether this chunk had slots or not: a sparse account with a legitimately-empty
          // middle sub-range must complete like any other, not stall its siblings forever.
          storageTrieCursor(accountHash) = StorageTask.incrementHash32(task.last)

          // Gate the commit on the WHOLE ACCOUNT being done, not just this chunk: with
          // storageConcurrency parallel subtasks, this chunk finishing first does not mean its
          // siblings covering higher sub-ranges have landed yet. Committing unconditionally here (the
          // pre-fix behaviour) would remove/finalise the shared trie while siblings are still in
          // flight, corrupting the on-disk path-keyed nodes for this account.
          val accountFullyDone =
            if accountSubtaskCounters.contains(accountHash) then recordSubtaskCompletion(accountHash)
            else
              completedAccountCount += 1; true

          if accountFullyDone then
            // By construction this chunk was the account's range-highest surviving chunk (the gate
            // only ever lets chunks apply in ascending order), so nothing should still be buffered.
            // Defensive: re-queue anything found anyway rather than silently losing it.
            val leftover = clearStorageOrderingState(accountHash)
            if leftover.nonEmpty then
              log.warn(
                s"Account ${task.accountString} completed with ${leftover.size} orphaned buffered " +
                  s"storage chunk(s) — re-queueing rather than discarding"
              )
              leftover.foreach(t => this.tasks.enqueue(t.copy(pending = false)))

            val rootVerified =
              if deferredMerkleization then
                log.debug(
                  s"Account ${accountHash.take(4).toHex} fully downloaded (deferred merkleization — flat-only)"
                )
                true
              else
                val computedRoot = wholeAccountTrie match
                  case Some(built) => commitPrebuiltRoot(accountHash, task.storageRoot, built)
                  case None        => commitAccountTrie(accountHash, task.storageRoot)
                log.debug(
                  s"Account ${accountHash.take(4).toHex} streaming trie committed: root=${computedRoot.take(4).toHex}"
                )
                computedRoot == task.storageRoot
            // Completion marker — ONLY on this path (every subtask applied in order; when not deferring merkleization,
            // also trie committed with a matching root — deferred mode builds no trie, so the marker then vouches for
            // the flat slots alone, see SnapStorageDoneStorage). The give-up,
            // max-empty skip, abandoned and force-complete paths never mark: a resume downloads those again. A root
            // mismatch is not marked either, so a restart retries it rather than leaving it all to healing.
            if rootVerified then stageDoneMarker(accountHash, task.storageRoot)

        task = task.copy(done = true, pending = false)
        recordCompletedTask(task)
