package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.GcPressureSampler
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FrontierRebuilt
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FrontierWalkFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.heal.WalkCancelled
import com.chipprbots.ethereum.blockchain.sync.snap.heal.WalkLease
import com.chipprbots.ethereum.db.storage.BfsEntry
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage

/** The coordinator state `FrontierRebuildBfs` reads and writes (spec 016 C-H2, research.md R14b). The core's behaviour
  * arms and the code C-H3…C-H5 will move touch them too, or rule (d) keeps them in the core, so the Impl keeps them as
  * fields. `verificationBFSRunning` and `currentWalk` are the `var`s the module writes (the walk launcher sets them on
  * the actor thread when a walk starts; the core's completion and failure arms clear the flag, and take a walk's end
  * only when it carries `currentWalk`, the token of the last walk this incarnation launched, spec 022 review hunter W1,
  * beacon W1-r4); `stateRoot`, the walk root, is read by the multi-seed launcher on the actor thread to tag the walk's
  * batches; `prunedSubtreeCount` is the `AtomicLong` the walk's sub-range threads bump and the verification entry and
  * completion read. The rest are immutable vals rule (d) keeps in the core: `bfsQueue` (the walk's level queue,
  * `stateIngest.bfsQueue` or an in-memory fallback, built once with the coordinator), the two dispatchers,
  * `prunedEnabled`, the emission batch size and the walk's caps.
  */
private[actors] trait FrontierRebuildBfsState:
  def stateRoot: ByteString
  var verificationBFSRunning: Boolean
  var currentWalk: Option[Long]
  def prunedSubtreeCount: AtomicLong
  def bfsQueue: BfsQueueStorage
  def healingWriterEc: ExecutionContext
  def healingReaderEc: ExecutionContext
  def prunedEnabled: Boolean
  def FrontierBatchSize: Int
  def HealingVisitedCap: Int
  def HealingTraversalParallelism: Int
  def HealingMinParallelism: Int
  def HealingReservedCores: Int

/** What the verification launchers (C-H3, `HealVerification`) call in `FrontierRebuildBfs`: the walk's two launchers,
  * one seed or many. A separate trait, so the C-H2 interfaces stay as recorded. `onComplete` gets the walk's token (its
  * [[WalkLease]] token, to tag the end the caller sends) and the number of missing nodes it found. Each returns whether
  * the walk started: a caller says or sets nothing about a walk until it has (spec 022 T038a).
  */
private[actors] trait FrontierRebuildApi:
  def startFrontierBFS(
      root: ByteString,
      rootPath: ByteString,
      isStor: Boolean,
      onComplete: (Long, Long) => Unit
  ): Boolean
  def startFrontierBFS(seeds: Seq[(ByteString, Seq[ByteString], Boolean)], onComplete: (Long, Long) => Unit): Boolean

/** TrieNodeHealingCoordinator's frontier-rebuild walk (spec 016 C-H2, coordinator-characterization.md §3.4): the
  * multi-seed level-order BFS over the locally stored trie (`rebuildFrontierBFS`), which reads each level from the BFS
  * queue in chunks, reads the nodes (a batched content-addressed `multiGetNodes` under Hash, a path-verified read under
  * Path), emits every missing node as `FrontierRebuilt` behind the backpressure wait, and enqueues present children for
  * the next level; its two launchers (`startFrontierBFS`, one seed or many), which run it on the healing writer
  * dispatcher and route completion or failure; and the per-scheme de-dup keys of its bounded visited set. It performs
  * no state writes besides the scratch BFS queue. The coordinator core keeps the behaviour arms that launch it and
  * handle its output (`frontierRebuildArms`) and the verification launchers that reuse it.
  */
private[actors] trait FrontierRebuildBfs extends FrontierRebuildApi:
  this: FrontierRebuildBfsState & TrieNodeHealingEnv & PathVerifiedReadApi & FrontierDrainApi =>

  /** Rebuild the healing frontier from locally-stored trie nodes using level-order BFS.
    *
    * Issues one `multiGetNodes` per chunk (BfsChunkSize = 50K) instead of one get per node. Level queue is persisted to
    * the `BfsQueueNamespace` CF so memory is O(chunk_size) ≈ 4 MB, eliminating the OOM that the previous ArrayBuffer
    * caused at L7 (~73M entries × 175 B/tuple).
    *
    * For large levels (> BfsChunkSize) the level is split into N sub-ranges processed in parallel on `healingWriterEc`
    * (N = min(HealingTraversalParallelism, available processors - 2)). Each sub-range enqueues its children directly
    * into the shared CF queue (AtomicLong counter assigns unique keys). Runs entirely on healingWriterEc.
    */

  /** Multi-seed frontier-rebuild BFS kernel (spec 003 C2/FR-002/FR-003). Seeds the level-0 frontier with EVERY
    * `(startHash, startPathset, isStorage)` in `seeds` instead of a single root, then runs the identical level-order
    * traversal: each seed's HP-encoded `startPathset` re-anchors the walk at that node and the unchanged per-child
    * nibble arithmetic extends it into that subtree. For a single-element `seeds` this is byte-identical to the prior
    * single-seed walk (same visited set, level expansion, child-path arithmetic, FrontierRebuilt emission,
    * backpressure). The walk performs NO state writes — it is a pure local read (`multiGetNodes`) that emits
    * `FrontierRebuilt` for missing nodes.
    */
  private def rebuildFrontierBFS(
      seeds: Seq[(ByteString, Seq[ByteString], Boolean)],
      walkRoot: ByteString,
      selfRef: org.apache.pekko.actor.typed.ActorRef[Command],
      queue: BfsQueueStorage,
      effectiveParallelism: Int,
      cancelled: () => Boolean
  ): Long =
    import com.chipprbots.ethereum.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    // spec 022 T038a: this walk's incarnation has ended, so nobody reads its result. It stops here, at a level, a chunk or
    // a batch; it holds the lease until it has stopped, so nothing runs beside it, and the next walk clears the queue.
    def stopIfCancelled(): Unit = if cancelled() then throw new WalkCancelled

    // FIFO/insertion-order bounded visited set (companion boundedVisitedSet): at the cap the
    // earliest-INSERTED entry is evicted (NOT an LRU — recent access does not protect an entry)
    // instead of refusing new entries. Refusing (the previous ConcurrentHashMap gate)
    // silently TRUNCATED the traversal on tries larger than the cap — children past the cap were
    // never enqueued, the queue drained early, and the walk reported "Complete" (and set the
    // Layer-2 completeness marker) having covered only `cap` of the trie. Eviction trades that
    // correctness hole for bounded re-walks of shared subtries (de-duplicated downstream by
    // the live heal requests). Access is synchronized: worker threads only touch it via markIfNew, and
    // per-check lock cost is negligible against the 50K-node multiGet I/O per chunk.
    // What the set is keyed by depends on the storage scheme — see the `*VisitKey` helpers: the node hash under Hash,
    // the node's location under Path.
    val visitedLru = TrieNodeHealingCoordinator.boundedVisitedSet(HealingVisitedCap)
    def markIfNew(h: ByteString): Boolean = visitedLru.synchronized {
      if visitedLru.contains(h) then false
      else
        visitedLru += h
        true
    }
    // spec 003 C2: mark every seed hash as visited (level 0). For one seed this is the prior
    // markIfNew(startHash); for many it pre-loads the shared visited set so cross-seed shared
    // subtries are de-duplicated exactly as within a single walk.
    seeds.foreach { case (h, ps, _) => markIfNew(seedVisitKey(h, ps)) }

    val visitedCount = new java.util.concurrent.atomic.AtomicLong(0L)
    val frontierCount = new java.util.concurrent.atomic.AtomicLong(0L)

    // --- spec 005 (Pruned descend-and-stop oracle, C2/T006/T014) ---
    // The store is consulted ONLY when `prunedEnabled` (config on AND Hash scheme AND store present). When pruning is
    // disabled, `prunedStore` is None and the oracle below is inert — the walk descends EVERY present child exactly as
    // today (byte-identical full walk). The oracle's contract (FR-001/FR-004, never-false-prune): a present child X is
    // pruned (treated as a verified leaf, NOT enqueued, so its whole subtree is skipped) IFF `prunedEnabled` AND X has
    // a durable subtree-complete record. A present-but-not-recorded child and any missing child are ALWAYS descended /
    // emitted — pruning narrows the walk only where completeness is durably proven, never where it is merely assumed.
    // Returns true when the child was pruned (caller must NOT enqueue it). Increments the pruned-subtree counter on a
    // hit. `markIfNew` has already de-duplicated the child, so each pruned subtree is counted once per walk.
    val prunedStore: Option[HealingFrontierStorage] = if prunedEnabled then stateIngest.healingFrontier else None
    def pruneIfSubtreeComplete(childHash: ByteString): Boolean =
      prunedStore.exists { store =>
        val recorded = store.isSubtreeComplete(childHash)
        if recorded then prunedSubtreeCount.incrementAndGet()
        recorded
      }

    // --- spec 002 US2 observability (observation-only, FR-006/FR-007/FR-008) ---
    // Per-level coarse-phase timers (nanos) accumulated across chunks/sub-ranges, and re-walk inflation
    // counters. These are read and reset at each level boundary. They are pure instrumentation: they never
    // gate or change which nodes the walk enqueues/visits/declares missing (the markIfNew gate and enqueue
    // logic below are untouched). queueReadNanos/trieReadNanos/queueWriteNanos are summed over all chunks of
    // the level (aggregate CPU when sub-ranges run in parallel); childRefsSeen counts every HashNode child
    // reference observed BEFORE the markIfNew de-dup gate, distinctEnqueued counts only the references that
    // pass the gate, so childRefsSeen / max(1, distinctEnqueued) is the faithful re-walk inflation ratio.
    val queueReadNanos = new java.util.concurrent.atomic.AtomicLong(0L)
    val trieReadNanos = new java.util.concurrent.atomic.AtomicLong(0L)
    val queueWriteNanos = new java.util.concurrent.atomic.AtomicLong(0L)
    val childRefsSeen = new java.util.concurrent.atomic.AtomicLong(0L)
    val distinctEnqueued = new java.util.concurrent.atomic.AtomicLong(0L)
    // Windowed GC-pressure sampler: baseline captured at walk start; sampled per level.
    val gcSampler = new GcPressureSampler()

    // Process a sub-range [subFrom, subTo) of the current level; returns frontier entries.
    def processSubRange(subFrom: Long, subTo: Long, levelIndex: Int): Seq[HealingEntry] =
      val subFrontier = mutable.Buffer.empty[HealingEntry]

      // Time the queue-read (chunk fetch) per chunk WITHOUT changing iteration semantics: drive the lazy
      // iterator explicitly and time only the production of each chunk (one nanoTime pair per chunk fetch,
      // not per node). The body that follows is byte-identical to the prior `.foreach { chunk => ... }`.
      val chunkIterator = queue.iterateRange(subFrom, subTo)
      var moreChunks = true
      while moreChunks do
        stopIfCancelled()
        val readStart = System.nanoTime()
        val hasNext = chunkIterator.hasNext
        val chunk = if hasNext then chunkIterator.next() else Seq.empty[BfsEntry]
        queueReadNanos.addAndGet(System.nanoTime() - readStart)
        if !hasNext then moreChunks = false
        else
          val trieReadStart = System.nanoTime()
          // Hash scheme: the original batched, content-addressed multiGetNodes (unchanged). Path scheme: PathNodeStorage
          // is keyed by PATH, not hash, so a hash-keyed batch lookup can never find a Path-scheme node. Each entry
          // carries its own pathset (it was enqueued with it), so read it at THAT path and count it present only if the
          // stored bytes hash to the entry's hash (`readPathVerifiedNode`). Without this the walk read an empty
          // hash-keyed store, reported the very first node missing, and never descended into a present trie.
          val results: Seq[Option[com.chipprbots.ethereum.mpt.MptNode]] = storageScheme match
            case StorageScheme.Hash =>
              mptStorage.multiGetNodes(chunk.map(_.hash))
            case StorageScheme.Path =>
              chunk.map(e => readPathVerifiedNode(ByteString(e.hash), e.pathset.map(ByteString(_))))
          trieReadNanos.addAndGet(System.nanoTime() - trieReadStart)
          val nextBuf = mutable.ArrayBuffer[(Array[Byte], Seq[Array[Byte]], Boolean)]()

          chunk.zip(results).foreach { case (entry, nodeOpt) =>
            val v = visitedCount.incrementAndGet()
            if v % 100_000 == 0 then
              asyncLog.info(
                s"[HEAL-BFS] Level $levelIndex: $v nodes visited, ${frontierCount.get()} frontier found, " +
                  s"${queue.counter - subTo} L${levelIndex + 1} queued"
              )
              SNAPSyncMetrics.setHealingRebuildVisited(v)

            val pathset = entry.pathset.map(ByteString(_))
            val nibbles = HexPrefix.decode(pathset.last.toArray)._1

            nodeOpt match
              case None =>
                subFrontier += HealingEntry(pathset, ByteString(entry.hash))
                frontierCount.incrementAndGet()

              case Some(node) =>
                try
                  node match
                    case branch: BranchNode =>
                      for i <- 0 until 16 do
                        branch.children(i) match
                          case hashChild: HashNode =>
                            val childHash = ByteString(hashChild.hashNode)
                            // Observation-only (FR-008/FR-023): count this child reference before the de-dup gate.
                            childRefsSeen.incrementAndGet()
                            if markIfNew(branchChildVisitKey(childHash, entry, nibbles, i)) then
                              // spec 005 C2/T006: prune a present, recorded-complete child — do NOT enqueue it, so its
                              // whole subtree is skipped (descend-and-stop). Else descend exactly as today.
                              if !pruneIfSubtreeComplete(childHash) then
                                distinctEnqueued.incrementAndGet()
                                val childNibbles = nibbles :+ i.toByte
                                val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                                val childPathset =
                                  if entry.isStorage then Seq(pathset.head.toArray, childCompact.toArray)
                                  else Seq(childCompact.toArray)
                                nextBuf += ((hashChild.hashNode, childPathset, entry.isStorage))
                          case _ =>

                    case ext: ExtensionNode =>
                      ext.next match
                        case hashChild: HashNode =>
                          val childHash = ByteString(hashChild.hashNode)
                          // Observation-only (FR-008/FR-023): count this child reference before the de-dup gate.
                          childRefsSeen.incrementAndGet()
                          if markIfNew(extChildVisitKey(childHash, entry, nibbles, ext.sharedKey)) then
                            // spec 005 C2/T006: prune a present, recorded-complete child (descend-and-stop); else descend.
                            if !pruneIfSubtreeComplete(childHash) then
                              distinctEnqueued.incrementAndGet()
                              val childNibbles = nibbles ++ ext.sharedKey.toArray
                              val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                              val childPathset =
                                if entry.isStorage then Seq(pathset.head.toArray, childCompact.toArray)
                                else Seq(childCompact.toArray)
                              nextBuf += ((hashChild.hashNode, childPathset, entry.isStorage))
                        case _ =>

                    case leaf: LeafNode if !entry.isStorage =>
                      Account(leaf.value).foreach { account =>
                        // Observation-only (FR-008/FR-023): count the account-leaf storageRoot reference
                        // before the de-dup gate, when there is a non-empty storage root to follow.
                        if account.storageRoot != Account.EmptyStorageRootHash then childRefsSeen.incrementAndGet()
                        if account.storageRoot != Account.EmptyStorageRootHash &&
                          markIfNew(storageRootVisitKey(account.storageRoot.value, nibbles, leaf.key))
                        then
                          // spec 005 C2/T006: prune a present, recorded-complete storage-trie root (descend-and-stop) —
                          // do NOT enqueue the storage subtree. Else descend it exactly as today.
                          if !pruneIfSubtreeComplete(account.storageRoot.value) then
                            distinctEnqueued.incrementAndGet()
                            val allNibbles = nibbles ++ leaf.key.toArray
                            if allNibbles.length == 64 then
                              val accountHashBytes =
                                allNibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray
                              val accountHash = ByteString(accountHashBytes)
                              val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                              nextBuf += (
                                (
                                  account.storageRoot.toArray,
                                  Seq(accountHash.toArray, emptyStoragePath.toArray),
                                  true
                                )
                              )
                      }

                    case _ => // storage trie leaf, NullNode, inline HashNode
                catch
                  case NonFatal(e) =>
                    asyncLog.debug(
                      s"[HEAL-BFS] Cannot traverse ${Hex.toHexString(entry.hash.take(4))}: ${e.getMessage} — skipping"
                    )
          }
          if nextBuf.nonEmpty then
            val writeStart = System.nanoTime()
            queue.enqueueBatch(nextBuf.toSeq)
            queueWriteNanos.addAndGet(System.nanoTime() - writeStart)
        // end else (non-empty chunk)
      // end while (moreChunks)
      subFrontier.toSeq

    stopIfCancelled()
    queue.clear()
    // spec 003 C2: enqueue ALL seeds as level 0. For one seed this is the prior single-entry enqueue;
    // queue.counter (levelEnd) now reflects seeds.size as level 0.
    queue.enqueueBatch(seeds.map { case (h, ps, s) => (h.toArray, ps.map(_.toArray), s) })
    var levelStart = 0L
    var levelEnd = queue.counter // = seeds.size after the level-0 enqueue (1 for the single-seed wrapper)
    var levelIndex = 0

    while levelStart < levelEnd do
      val levelSize = levelEnd - levelStart

      val allFrontier: Seq[HealingEntry] =
        if effectiveParallelism <= 1 || levelSize <= TrieNodeHealingCoordinator.BfsChunkSize.toLong then
          processSubRange(levelStart, levelEnd, levelIndex)
        else
          val rangeSize = math.ceil(levelSize.toDouble / effectiveParallelism).toLong
          val subRanges = (0 until effectiveParallelism)
            .map { i =>
              val from = levelStart + i * rangeSize
              val to = math.min(levelEnd, from + rangeSize)
              (from, to)
            }
            .filter { case (from, to) => from < to }

          // Sub-range readers run on healingReaderEc (NOT healingWriterEc): the parent walk Future already
          // holds a healingWriterEc thread and parks it on the Await below. Sharing the pool would let the
          // parked parent wait on sub-range Futures queued behind it on the same pool once parallelism nears
          // the pool size (thread-starvation deadlock). The dedicated reader pool removes that dependency
          // (spec 002 R3 §2, T033). `scala.concurrent.blocking` marks the park as a managed-blocking section
          // so a fork-join-backed EC (e.g. a test EC) compensates; on the production thread-pool dispatcher it
          // is a harmless no-op but documents intent.
          val futures = subRanges.map { case (from, to) =>
            Future(processSubRange(from, to, levelIndex))(healingReaderEc)
          }
          scala.concurrent.blocking(TrieNodeHealingCoordinator.awaitSubRanges(futures)).flatten

      allFrontier.grouped(FrontierBatchSize).foreach { batch =>
        awaitFrontierDrain(cancelled) // pause the walk if the healing backlog is over the high-water mark
        stopIfCancelled()
        selfRef ! FrontierRebuilt(batch, walkRoot)
      }

      val fc = frontierCount.get()
      val queued = queue.counter - levelEnd

      // --- spec 002 US2 observability: derive + push the per-level signals (observation-only) ---
      val queueReadMs = queueReadNanos.get() / 1_000_000L
      val trieReadMs = trieReadNanos.get() / 1_000_000L
      val queueWriteMs = queueWriteNanos.get() / 1_000_000L
      val refsSeen = childRefsSeen.get()
      val enqueued = distinctEnqueued.get()
      val inflationRatio = refsSeen.toDouble / math.max(1L, enqueued).toDouble
      val (gcPauseMs, gcFraction) = gcSampler.sample()

      asyncLog.info(
        s"[HEAL-BFS] Level $levelIndex complete: $levelSize processed, $fc frontier total, $queued queued for L${levelIndex + 1}" +
          f" | phase(ms) queueRead=$queueReadMs trieRead=$trieReadMs queueWrite=$queueWriteMs" +
          f" | inflation childRefsSeen=$refsSeen distinctEnqueued=$enqueued ratio=$inflationRatio%.2f" +
          f" | gc pauseMs=$gcPauseMs fraction=$gcFraction%.4f"
      )
      SNAPSyncMetrics.setHealingRebuildVisited(visitedCount.get())
      SNAPSyncMetrics.setHealingPhaseQueueReadMs(queueReadMs)
      SNAPSyncMetrics.setHealingPhaseTrieReadMs(trieReadMs)
      SNAPSyncMetrics.setHealingPhaseQueueWriteMs(queueWriteMs)
      SNAPSyncMetrics.setHealingInflationRatio(inflationRatio)
      SNAPSyncMetrics.setHealingGcPauseMs(gcPauseMs)
      SNAPSyncMetrics.setHealingGcFraction(gcFraction)

      // Reset the per-level accumulators so the next level's gauges/log reflect only that level.
      queueReadNanos.set(0L)
      trieReadNanos.set(0L)
      queueWriteNanos.set(0L)
      childRefsSeen.set(0L)
      distinctEnqueued.set(0L)

      // Free the consumed level immediately (one range tombstone). Levels are processed exactly
      // once and never re-read, and on ETC mainnet the live queue otherwise accumulates the whole
      // walk (~140M entries, >10 GB) until the end-of-walk clear().
      queue.deleteRange(levelStart, levelEnd)

      levelStart = levelEnd
      levelEnd = queue.counter
      levelIndex += 1

    queue.clear()

    val totalMissing = frontierCount.get()
    asyncLog.info(
      s"[HEAL-BFS] Complete: ${visitedCount.get()} nodes across $levelIndex levels, $totalMissing missing nodes identified"
    )
    // spec 006 C1/D3/FR-004: return the count of missing nodes this walk emitted so the launcher
    // can pass it to onComplete (→ FrontierRebuildComplete.missingEmitted). The walk does NO state
    // writes; this is a pure read of the in-kernel AtomicLong.
    totalMissing

  /** Launch a frontier rebuild or verification BFS on the healing writer executor.
    *
    * Replaces startParallelFrontierDFS. A single Future on healingWriterEc is sufficient — BFS naturally maximises I/O
    * batching per level without needing keyspace splitting.
    */
  def startFrontierBFS(
      root: ByteString,
      rootPath: ByteString,
      isStor: Boolean,
      onComplete: (Long, Long) => Unit
  ): Boolean =
    // spec 003 C2/C3: byte-identical thin wrapper over the multi-seed launcher for a single seed. The
    // full-root / crash-recovery / pivot-reseed callers reach the SAME walk (the kernel collapses one
    // seed to the prior single-entry enqueue). The seed is a trie root, so it is the walk root its batches carry.
    launchFrontierBFS(Seq((root, Seq(rootPath), isStor)), root, onComplete)

  /** Multi-seed frontier-rebuild / verification launcher (spec 003 C3). Launches the multi-seed `rebuildFrontierBFS`
    * kernel (C2) on `healingWriterEc`, reusing `verificationBFSRunning`, the shared `bfsQueue`, and the same
    * `computeEffectiveParallelism` clamp. Routes success to `onComplete()` and any walk exception to
    * `FrontierWalkFailed` exactly as the single-seed launcher did. For a single-element `seeds` this is byte-identical
    * to the prior launcher.
    */
  def startFrontierBFS(
      seeds: Seq[(ByteString, Seq[ByteString], Boolean)],
      onComplete: (Long, Long) => Unit
  ): Boolean =
    // The seeds are subtrees of the current walk root (spec 003 scoped verification): its batches carry `stateRoot`,
    // read here on the actor thread, never from the walk's own thread (spec 022 review W1).
    launchFrontierBFS(seeds, stateRoot, onComplete)

  /** The BFS column family's lease (see [[WalkLease]]): every coordinator incarnation of every controller over it sees
    * the walk any of them runs.
    */
  private lazy val walkLease: WalkLease = WalkLease.of(bfsQueue)

  /** What a refusal leaves with the lease: this incarnation's completion check, when the walk that refused it ends. One
    * function per incarnation, so however often it was refused it is woken once. A check is safe whatever the state.
    */
  private lazy val wakeOnLeaseFree: () => Unit = () => self ! HealingCheckCompletion

  /** This incarnation is ending: its walk, if it still runs, stops at its next check (spec 022 T038a). */
  def cancelRunningWalk(): Unit = currentWalk.foreach(walkLease.cancel)

  /** Both launchers: run the walk over `seeds` and tag its `FrontierRebuilt` batches with `walkRoot`. One walk at a
    * time on the BFS column family (spec 022 T038a; review hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): no walk
    * starts while this incarnation's own walk runs, or while the column family's lease is held, which is then by a walk
    * another incarnation launched: one that has ended is being cancelled, and holds the lease until it has stopped. A
    * walk not started changes nothing but the lease's list of whom to wake: the end of the walk that holds the lease
    * sends this incarnation a completion check, which starts one, since its `verificationPassComplete` is false until a
    * walk of its own ends clean. Returns whether the walk started.
    */
  private def launchFrontierBFS(
      seeds: Seq[(ByteString, Seq[ByteString], Boolean)],
      walkRoot: ByteString,
      onComplete: (Long, Long) => Unit
  ): Boolean =
    if verificationBFSRunning then
      log.warn("[HEAL-BFS] Frontier walk not started: this coordinator's own walk is still running on the BFS queue")
      false
    else
      walkLease.tryAcquire(Some(wakeOnLeaseFree)) match
        case None =>
          log.warn(
            "[HEAL-BFS] Frontier walk not started: a walk another coordinator incarnation launched still holds the BFS " +
              "column family. A completion check walks once it has ended."
          )
          false
        case Some(walk) =>
          runFrontierBFS(seeds, walkRoot, walk, onComplete)
          true

  /** Run walk `walk`, which holds the lease, on the healing writer executor. The lease is given back as the walk ends,
    * before its end is reported: the end then carries `walk`, and the core takes it only from the walk it launched.
    */
  private def runFrontierBFS(
      seeds: Seq[(ByteString, Seq[ByteString], Boolean)],
      walkRoot: ByteString,
      walk: Long,
      onComplete: (Long, Long) => Unit
  ): Unit =
    val selfRef = self
    // Effective parallelism floor (spec 002 R3 §1, T034): min(cfg, max(minParallelism, nproc − reservedCores)).
    // Never exceeds the operator ceiling (HealingTraversalParallelism) or the CPU count — `min` with `cfg`
    // clamps first, and `cfg <= 1` forces the serial branch (`effectiveParallelism <= 1`) below, preserving
    // the serial baseline (FR-012). The minParallelism floor lifts the previous `max(1, …)` so a host with
    // few spare cores still splits large levels; reservedCores keeps headroom for the live node + GC. On the
    // 4-core reference host the default (min 2, reserved 2) still yields max(2, 4−2) = 2.
    val nproc = Runtime.getRuntime.availableProcessors()
    // Extracted to the pure companion `computeEffectiveParallelism` (spec 002 T026) so the clamp is
    // unit-testable without instantiating the actor. byte-identical to the prior inline expression.
    val effectiveParallelism =
      TrieNodeHealingCoordinator.computeEffectiveParallelism(
        HealingTraversalParallelism,
        nproc,
        HealingMinParallelism,
        HealingReservedCores
      )
    // Guard BOTH walk kinds (rebuild + verification): the watchdog, HealingCheckCompletion and the
    // pivot-refresh path gate on this flag. Cleared by FrontierRebuildComplete /
    // VerificationBFSComplete / FrontierWalkFailed of this walk.
    verificationBFSRunning = true
    currentWalk = Some(walk)
    Future {
      val missingEmitted =
        try
          Some(
            rebuildFrontierBFS(
              seeds,
              walkRoot,
              selfRef,
              bfsQueue,
              effectiveParallelism,
              () => walkLease.isCancelled(walk)
            )
          )
        catch
          case _: WalkCancelled =>
            asyncLog.info(
              s"[HEAL-BFS] Frontier walk $walk cancelled: the coordinator incarnation that launched it ended"
            )
            None
          case scala.util.control.NonFatal(e) =>
            // Never call onComplete() on failure — for the crash-recovery rebuild that would set the
            // Layer-2 completeness marker on a walk that did NOT cover the full state. Reset flags
            // through the actor instead so a fresh walk can be started.
            asyncLog.error("[HEAL-BFS] Frontier walk FAILED before completion — sending FrontierWalkFailed", e)
            None
        finally walkLease.release(walk)
      missingEmitted match
        case Some(missing) => onComplete(walk, missing)
        case None          => selfRef ! FrontierWalkFailed(walk)
    }(healingWriterEc)

  // ---- frontier-walk de-dup keys -----------------------------------------------------------------------------------
  // `rebuildFrontierBFS` de-dups the nodes it enqueues in a bounded `visited` set, so a subtrie reachable by two
  // references is walked once. What counts as "the same node" depends on the storage scheme:
  //   - Hash: the node HASH. Storage is content-addressed — one copy per hash — so a second reference to a node already
  //     walked needs no second visit. Unchanged.
  //   - Path: the node's LOCATION (trie kind + account hash + compact path). `PathNodeStorage` holds one physical copy
  //     per location, so two locations that happen to carry the same hash — e.g. the identical storage tries of two
  //     accounts — are DISTINCT stored nodes and each must be verified. De-duping them by hash would let a hole at the
  //     second location go undetected: the walk would report "complete" over missing state.
  // The helpers take cheap references (no precomputed paths), so under Hash they return their hash argument at once —
  // no allocation and no behavioural change on the ETC path.

  /** Location identity of a node: trie kind (account / storage), account hash for a storage node, and the HP-encoded
    * nibble path. Prefix-free (the account hash is a fixed 32 bytes), so distinct locations never collide.
    */
  private def locationKey(accountHash: Option[Array[Byte]], nibbles: Array[Byte]): ByteString =
    val builder = ByteString.newBuilder
    accountHash match
      case Some(hash) => builder.putByte(1).putBytes(hash)
      case None       => builder.putByte(0)
    builder.putBytes(com.chipprbots.ethereum.mpt.HexPrefix.encode(nibbles, isLeaf = false)).result()

  /** The account hash scoping `parent`'s storage trie, or `None` for an account-trie entry. */
  private def storageOwner(parent: BfsEntry): Option[Array[Byte]] =
    if parent.isStorage then Some(parent.pathset.head) else None

  /** De-dup key for a walk seed (`seeds` carry HP-encoded pathsets, as `HealingEntry` does). */
  private def seedVisitKey(hash: ByteString, pathset: Seq[ByteString]): ByteString =
    storageScheme match
      case StorageScheme.Hash => hash
      case StorageScheme.Path =>
        val owner = if pathset.size > 1 then Some(pathset.head.toArray) else None
        locationKey(owner, com.chipprbots.ethereum.mpt.HexPrefix.decode(pathset.last.toArray)._1)

  /** De-dup key for the child in branch slot `slot` of `parent`, whose own nibble path is `parentNibbles`. */
  private def branchChildVisitKey(
      childHash: ByteString,
      parent: BfsEntry,
      parentNibbles: Array[Byte],
      slot: Int
  ): ByteString =
    storageScheme match
      case StorageScheme.Hash => childHash
      case StorageScheme.Path => locationKey(storageOwner(parent), parentNibbles :+ slot.toByte)

  /** De-dup key for the child behind an extension node of `parent` (path `parentNibbles` ++ `sharedKey`). */
  private def extChildVisitKey(
      childHash: ByteString,
      parent: BfsEntry,
      parentNibbles: Array[Byte],
      sharedKey: ByteString
  ): ByteString =
    storageScheme match
      case StorageScheme.Hash => childHash
      case StorageScheme.Path => locationKey(storageOwner(parent), parentNibbles ++ sharedKey.toArray)

  /** De-dup key for the storage-trie root an account leaf references. Under Path it lives at (accountHash, empty path),
    * the account hash being the leaf's full 64-nibble path packed into 32 bytes. A path of any other length is not a
    * real account position; the enqueue below skips it, so its key is immaterial and falls back to the hash.
    */
  private def storageRootVisitKey(
      storageRoot: ByteString,
      parentNibbles: Array[Byte],
      leafKey: ByteString
  ): ByteString =
    storageScheme match
      case StorageScheme.Hash => storageRoot
      case StorageScheme.Path =>
        val accountNibbles = parentNibbles ++ leafKey.toArray
        if accountNibbles.length == 64 then
          val accountHash = accountNibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray
          locationKey(Some(accountHash), Array.empty[Byte])
        else storageRoot
