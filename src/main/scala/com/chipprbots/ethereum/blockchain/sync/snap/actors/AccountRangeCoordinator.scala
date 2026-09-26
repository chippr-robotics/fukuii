package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnel
import com.google.common.hash.PrimitiveSink

import com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** AccountRangeCoordinator manages account range download workers.
  *
  * Now contains ALL business logic previously in AccountRangeDownloader. This is the sole implementation - no
  * synchronized fallback.
  *
  * Responsibilities:
  *   - Maintain queue of pending account range tasks
  *   - Distribute tasks to worker actors
  *   - Verify Merkle proofs for downloaded accounts
  *   - Store accounts to MPT storage
  *   - Identify contract accounts for bytecode download
  *   - Finalize state trie after completion
  *   - Report progress to SNAPSyncController
  *   - Handle worker failures with supervision
  *
  * @param stateRoot
  *   State root hash for account sync
  * @param networkPeerManager
  *   Actor for sending network messages
  * @param requestTracker
  *   Tracker for requests/responses
  * @param mptStorage
  *   Storage for persisting accounts
  * @param concurrency
  *   Number of worker actors to spawn
  * @param snapSyncController
  *   Parent controller to notify of completion
  */
private class AccountRangeCoordinatorImpl(
    ctx: ActorContext[AccountRangeCoordinator.Command],
    timers: TimerScheduler[AccountRangeCoordinator.Command],
    initialStateRoot: ByteString,
    networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    concurrency: Int,
    snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
    resumeProgress: Map[ByteString, ByteString] = Map.empty,
    initialMaxInFlightPerPeer: Int = 5,
    initialResponseBytesConfig: Int = 524288,
    minResponseBytesConfig: Int = 102400,
    accountTrieEcOverride: Option[ExecutionContext] = None,
    storageScheme: StorageScheme = StorageScheme.Hash,
    pathNodeStorage: Option[PathNodeStorage] = None
):

  import SNAPSyncController.PivotStateUnservable
  import AccountRangeCoordinator.*

  // `ctx.log` is thread-confined and must NOT be captured inside the trie-finalisation Future
  // (see `finalizeTrie`). For the actor-thread code below we use `ctx.log`; the Future uses
  // `futureLog` (a plain SLF4J logger) instead.
  private val log = ctx.log
  private val futureLog = org.slf4j.LoggerFactory.getLogger(classOf[AccountRangeCoordinatorImpl])

  // Typed leaf worker (Group W1). The coordinator is now Typed and spawns Typed children directly;
  // it holds typed refs and sends commands with the typed `!`.
  private type WorkerRef = org.apache.pekko.actor.typed.ActorRef[AccountRangeWorker.Command]

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  // Part of global per-peer request budgeting (Geth-aligned: total 5 per peer across all coordinators).
  private var maxInFlightPerPeer: Int = initialMaxInFlightPerPeer

  // Downstream-queue back-pressure (#1232 follow-up). Either StorageRangeCoordinator OR
  // ByteCodeCoordinator can independently signal that its pending queue has crossed the
  // high-water mark; account-range dispatching must pause whenever ANY downstream is over its
  // mark, and only resume once they have ALL released. We track each source by name so the two
  // signals don't interfere — releasing storage shouldn't accidentally unpause when bytecodes are
  // still over their mark, etc. Package-private for tests.
  private[actors] val backpressureSources: mutable.Set[String] = mutable.Set.empty[String]
  private def downstreamBackpressureActive: Boolean = backpressureSources.nonEmpty
  // Kept for spec compatibility; reflects whether the storage source is currently engaged.
  private[actors] def storageBackpressureActive: Boolean = backpressureSources.contains("storage")

  // Stateless peer tracking: peers CONFIRMED unable to serve the current state root after
  // crossing the strike threshold below. Cleared on PivotRefreshed because the new root may
  // be inside the peer's serve window.
  // `private[actors]` so AccountRangeCoordinatorSpec can drive the state via TestActorRef.
  private[actors] val statelessPeers = mutable.Set[com.chipprbots.ethereum.network.PeerId]()
  // Snapless peer tracking (#1197): peers whose SNAP handler returns
  // `AccountRangePacket{Accounts: nil, Proof: nil}` indicating `chain.Snapshots()` is
  // structurally unavailable. CONFIRMED after EmptyResponseStrikeThreshold consecutive
  // empty-with-empty-proof signals (no intervening successful response). Survives
  // PivotRefreshed (root-independent) — see project_eth68_snap_research memory for the
  // core-geth `--syncmode full` rationale: a peer with no snapshot tree won't grow one.
  // Bytecode + trie-node healing coordinators are unaffected — those code paths use
  // direct DB lookups that don't depend on the snapshot tree.
  private[actors] val snaplessPeers = mutable.Set[com.chipprbots.ethereum.network.PeerId]()
  // Strike counter for empty-with-empty-proof responses (the only signal that drives
  // statelessPeers + snaplessPeers entry today). The previous policy promoted a peer on
  // the FIRST such response, which on sepolia 2026-05-13 carpet-bombed the peer pool
  // (snapPeers=3 advertised → 1 dispatched-to). Reference clients: geth keeps a single
  // global statelessPeers and no explicit strikes (uses a throughput/latency tracker);
  // nethermind uses 5 strikes. We pick 3 — enough to survive a transient empty cycle
  // (peer warming up after restart, network hiccup) without being so generous that genuinely
  // useless peers eat the dispatch budget.
  //
  // Lifecycle:
  //   * empty-with-empty-proof from peer → strike++
  //   * strike >= threshold → enter statelessPeers AND snaplessPeers (confirmed)
  //   * any successful response from peer → recordPeerSuccess clears strikes
  //   * PivotRefreshed → clear strikes (peer deserves a clean slate on the new root)
  //   * PeerUnavailable → clear strikes (and the peer entry from statelessPeers too)
  private[actors] val emptyResponseStrikes = mutable.Map.empty[com.chipprbots.ethereum.network.PeerId, Int]
  // Raised 3 → 5 on 2026-05-14: even with PR #1255 clearing snapless on PivotRefreshed,
  // the 3-strike threshold drained sepolia's small peer pool between pivots, producing
  // eligible=0 stalls within each pivot window. 5 strikes gives peers more rope; mirrors
  // Nethermind's threshold; lines up with the bumped storage threshold.
  private val EmptyResponseStrikeThreshold: Int = 5
  private var pivotRefreshRequested = false

  private def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id)

  private def isPeerSnapless(peer: Peer): Boolean =
    snaplessPeers.contains(peer.id)

  private def markPeerStateless(peer: Peer, reason: String): Unit =
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
  private def recordPeerSuccess(peerId: com.chipprbots.ethereum.network.PeerId): Unit =
    emptyResponseStrikes.remove(peerId)

  private def maybeRequestPivotRefresh(): Unit =
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
          consecutiveEmptyResponses = knownAvailablePeers.size
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
              consecutiveEmptyResponses = statelessPeers.size
            )

  // Task management — resume ranges from saved positions (core-geth parity).
  // On restart, each range resumes from its saved `next` position instead of starting from 0x00.
  private val allInitialTasks = AccountTask.createInitialTasks(stateRoot, concurrency)
  private val (skippedTasks, remainingTasks) = if resumeProgress.nonEmpty then
    val resumed = allInitialTasks.map { task =>
      resumeProgress.get(task.last) match
        case Some(savedNext)
            if BigInt(1, savedNext.toArray.padTo(32, 0.toByte)) >=
              BigInt(1, task.last.toArray.padTo(32, 0.toByte)) =>
          // Range fully traversed — mark as done
          task.copy(next = task.last, done = true)
        case Some(savedNext) =>
          // Partial range: the mid-range cursor is NOT safe to resume on the StackTrie path.
          // The per-task SnapHashTrie is in-memory + write-only and was lost on restart; a fresh
          // StackTrie resuming from `savedNext` would cover only [savedNext, last), orphaning the
          // already-downloaded prefix (whose root/right-spine were never persisted — only the
          // 8MiB-flushed left subtrees survived) and seaming an old-root prefix to a new-root
          // suffix. Re-download the whole range from its pristine start (task.next is left
          // untouched). Cheap — only the few in-flight ranges, not the completed ones. The
          // healing walk from the new pivot reconciles any delta across the COMPLETE ranges.
          if savedNext != task.next then
            log.info(
              s"Re-downloading partial range ${task.rangeString} from start " +
                "(saved mid-range cursor is not StackTrie-safe to resume)"
            )
          task
        case None => task
    }
    val (done, todo) = resumed.partition(_.done)
    (done, todo)
  else (Seq.empty, allInitialTasks)
  // Priority queue: dequeue the task with the SMALLEST remaining keyspace first.
  // This focuses workers on nearly-complete ranges, ensuring at least some ranges
  // finish before peers stop responding (instead of spreading work evenly across all 16).
  private[actors] val pendingTasks = mutable.PriorityQueue[AccountTask](remainingTasks*)(
    Ordering.by[AccountTask, BigInt](_.remainingKeyspace).reverse
  )
  // requestId -> (task, worker, peer)
  private[actors] val activeTasks = mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]()
  private val completedTasks = mutable.ArrayBuffer[AccountTask]()

  // Worker pool
  private[actors] val workers = mutable.ArrayBuffer[WorkerRef]()
  private[actors] val idleWorkers = mutable.LinkedHashSet.empty[WorkerRef]

  // Monotonically increasing counter for unique worker child names. Workers can be removed and
  // re-created; using the set size would produce duplicate names when replacements are spawned.
  private var workerSeq: Int = 0

  // #1184: dispatch-stalled detector — silent peers (no FIN/RST) leave activeTasks slots
  // held forever; the worker→TaskFailed cascade depends on a response that never arrives.
  // Track time-of-last-progress and fire `CheckDispatchStalled` periodically; when
  // `activeTasks.nonEmpty && (now - lastDispatchOrResponseMs) > noActivityTimeoutMs`, drain.
  // 90 s threshold fires before the controller's 180 s `Account stall detected` watchdog,
  // so the coordinator self-heals without burning a pivot-refresh budget slot.
  private[actors] var lastDispatchOrResponseMs: Long = System.currentTimeMillis()
  private val noActivityTimeoutMs: Long = 90_000L
  private val dispatchStallCheckInterval: FiniteDuration = 30.seconds
  // (Was `stallCheckTask: Option[Cancellable]` — now a keyed timer owned by `Behaviors.withTimers`,
  //  auto-cancelled when the behavior stops; no manual field/cancel needed.)
  // Counts consecutive CheckDispatchStalled ticks where pendingTasks.nonEmpty && activeTasks.isEmpty.
  // The standard `lastDispatchOrResponseMs` timer resets on every drain (peer cycling) and response,
  // making it blind to the "tasks pending but no eligible peers" stall. A tick counter is immune to
  // timer resets: if 3 consecutive 30s ticks (90s) pass without any dispatch, we escalate.
  // Reset to 0 whenever a dispatch succeeds (activeTasks.nonEmpty after tryRedispatch) or the
  // queue drains naturally (pendingTasks.isEmpty).
  private var pendingButIdleTicks: Int = 0

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_._3.id == peer.id)

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
  private def drainActiveTasks(reason: String, peerFilter: Option[String] = None): Int =
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

  // Statistics
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()
  private var lastProgressLogAt: Long = 0 // accounts count at last periodic log
  private val ProgressLogInterval: Long = 100_000 // log every 100K accounts
  private var lastFlatMilestonePct: Int = -1
  private val totalKeyspace: BigInt = BigInt(2).pow(256)
  // Cumulative keyspace consumed: incremented each time a task's `next` advances.
  // On restart, derive from restored task positions so progress % and ETA are accurate.
  private var consumedKeyspace: BigInt =
    if resumeProgress.nonEmpty then
      val toBI = (bs: ByteString) => BigInt(1, bs.toArray.padTo(32, 0.toByte))
      // Re-create pristine tasks to recover original range starts (keyed by `last` boundary).
      val originalStarts: Map[ByteString, BigInt] =
        AccountTask.createInitialTasks(initialStateRoot, concurrency).map(t => t.last -> toBI(t.next)).toMap
      (skippedTasks ++ remainingTasks).foldLeft(BigInt(0)) { (acc, task) =>
        val orig = originalStarts.getOrElse(task.last, toBI(task.last))
        acc + (toBI(task.next) - orig).max(BigInt(0))
      }
    else BigInt(0)

  // Contract accounts persisted to temp files to avoid unbounded memory growth.
  // On ETC mainnet ~20% of ~67M accounts are contracts — ~13M entries × 64 bytes each
  // would consume ~1.6GB in memory. Writing to disk keeps memory usage near zero.
  // Each entry is 64 bytes: 32-byte accountHash + 32-byte codeHash (or storageRoot).
  private val contractAccountsFile: Path = Files.createTempFile("fukuii-contract-accounts-", ".bin")
  private val contractStorageFile: Path = Files.createTempFile("fukuii-contract-storage-", ".bin")
  private val contractAccountsOut = new BufferedOutputStream(new FileOutputStream(contractAccountsFile.toFile), 65536)
  private val contractStorageOut = new BufferedOutputStream(new FileOutputStream(contractStorageFile.toFile), 65536)
  private var contractAccountsCount: Long = 0
  private var contractStorageCount: Long = 0
  private val ContractEntrySize = 64 // 32 bytes hash + 32 bytes codeHash/storageRoot

  // Unique codeHashes for bytecode download — Bloom filter (~4MB) for dedup + temp file for storage.
  // At handoff, reads ~64MB (2M × 32 bytes) instead of the 4.7GB contractAccountsFile (73.5M × 64 bytes).
  // Bug 20 fix: the original ask-based handoff timed out (5s) and OOMed when reading the full file.
  implicit private object ByteStringFunnel extends Funnel[ByteString]:
    override def funnel(from: ByteString, into: PrimitiveSink): Unit =
      into.putBytes(from.toArray)
  private val codeHashBloom: BloomFilter[ByteString] = BloomFilter.create[ByteString](
    ByteStringFunnel,
    3_000_000,
    0.0001 // ~4MB for 3M expected entries at 0.01% FPR
  )
  private val uniqueCodeHashesFile: Path = Files.createTempFile("fukuii-unique-codehashes-", ".bin")
  private val uniqueCodeHashesOut = new BufferedOutputStream(new FileOutputStream(uniqueCodeHashesFile.toFile), 65536)
  private var uniqueCodeHashesCount: Long = 0

  // Track last known available peers so we can re-dispatch after task failures
  // without waiting for the next PeerAvailable message.
  private val knownAvailablePeers = mutable.Set[Peer]()

  /** Number of active (non-stateless, non-snapless, non-cooling-down) snap-capable peers. Returns the actual count — no
    * floor — so the progress log truthfully reports 0 when all peers are demoted (the case that drives the account
    * stall). Previously this had `.max(1)` which masked the stall: progress log said "1 peers" while dispatch was
    * starving on zero eligible peers. See PR-1 of `this-is-the-same-fluttering-eagle.md`.
    */
  private def activePeerCount: Int =
    knownAvailablePeers.count(p => !isPeerStateless(p) && !isPeerSnapless(p) && !isPeerCoolingDown(p))

  // Periodic state-dump cadence — at most one INFO snapshot every 30 seconds. Time-based
  // throttling (not modulo) so a call-rate spike doesn't overflow the log pipe. Same pattern
  // as PR #1250's [STORAGE-STATE] (subsequently switched from modulo to time-based for the
  // same reason).
  private var lastStateLogMs: Long = 0L
  private val StateLogIntervalMs: Long = 10_000L

  // Per-peer adaptive byte budgeting (ported from StorageRangeCoordinator).
  // Geth's snap handler supports up to 2MB responses. Starting at 512KB and probing upward
  // on responsive peers, scaling down on failures.
  private val minResponseBytes: BigInt = BigInt(minResponseBytesConfig)
  private val maxResponseBytes: BigInt = 2 * 1024 * 1024 // 2MB ceiling (Geth handler limit)
  private val initialResponseBytes: BigInt = BigInt(initialResponseBytesConfig)
  private val increaseFactor: Double = 1.25 // Scale up when 90%+ fill
  private val decreaseFactor: Double = 0.5 // Scale down on failure/empty

  private val peerResponseBytesTarget = mutable.Map.empty[String, BigInt]

  private def responseBytesTargetFor(peer: Peer): BigInt =
    peerResponseBytesTarget
      .getOrElseUpdate(peer.id.value, initialResponseBytes)
      .max(minResponseBytes)
      .min(maxResponseBytes)

  private def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    if requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes then
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit =
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing account responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds
  // Peer-scarcity threshold and the shorter cooldown applied below it. On a 1-3 peer pool a fixed 30s cooldown after a
  // single timeout benches ~half the usable peers; two near-simultaneous timeouts can zero the eligible set for 30s.
  // Scaling the cooldown down to a few seconds when peers are scarce keeps the pipe fed without giving up the
  // back-off's purpose (briefly resting a peer that just failed). Abundant pools keep the full 30s.
  private val peerScarcityThreshold = 3
  private val peerCooldownScarce = 8.seconds
  private def effectivePeerCooldown: FiniteDuration =
    if knownAvailablePeers.size <= peerScarcityThreshold then peerCooldownScarce else peerCooldownDefault
  // Short cooldown for "empty-without-proof" responses. These mean "I can't serve this
  // state root" — the peer is healthy, just doesn't have a snapshot at our pivot. 30s
  // was over-punitive here: in production we saw `cooling=5 eligible=11` snapshots,
  // i.e. ~30 % of the otherwise-eligible pool parked in cooldown most of the time.
  // A pivot refresh is what unsticks these peers, not waiting.
  private val peerCooldownNoProof = 5.seconds

  // FIFO fairness within same in-flight tier: tracks last dispatch time per peer so that
  // ties in inFlightForPeer sort are broken by least-recently-served order rather than
  // mutable.Set hash order (which is stable/deterministic and permanently starves some peers).
  private val lastDispatchTimeMs = mutable.Map.empty[String, Long]

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String, duration: FiniteDuration): Unit =
    val until = System.currentTimeMillis() + duration.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.info(s"[ACCOUNT-COOLDOWN] peer ${peer.id.value.take(8)} cooling ${duration.toSeconds}s: $reason")

  // Pivot refresh backoff: prevents rapid-fire pivot refresh requests when all peers are stateless.
  // Exponential backoff from 60s to 5min.
  private var consecutiveUnproductiveRefreshes: Int = 0
  private var lastPivotRefreshTimeMs: Long = 0L
  private val minRefreshIntervalMs: Long = 60000L // 60s minimum between refreshes
  private val maxRefreshIntervalMs: Long = 300000L // 5min ceiling

  // Dedicated dispatcher for trie finalisation. Tests inject their own
  // `ExecutionContext`; production looks up `account-trie-dispatcher` from `pekko.conf`.
  // `finalizeTrie` (10+ minutes on mainnet) runs here so it can't squeeze the global pool or
  // sync-dispatcher.
  private val accountTrieEc: ExecutionContext =
    accountTrieEcOverride.getOrElse(
      ctx.system.dispatchers.lookup(
        org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("account-trie-dispatcher")
      )
    )

  // Generation token. Bumped at finalisation spawn. Async result messages carry the generation
  // they were spawned under and are ignored if it no longer matches — defensive guard so a stale
  // completion can't apply against the wrong assumption. Mirrors the validateState() async pattern.
  // Package-private so unit tests can verify generation behaviour.
  private[actors] var trieFlushGeneration: Long = 0L

  // Per-task StackTrie state.
  // Keyed by `task.last` — each AccountTask has a unique end-of-range boundary.
  // The 16 ranges produce 16 fragment roots; the SNAP healing phase reconciles
  // them against the pivot's actual root. This matches go-ethereum's per-task
  // `genTrie *StackTrie` pattern in `eth/protocols/snap/sync.go`.
  //
  // `private[actors]` so the test spec can verify the per-task lifecycle.
  private[actors] val taskStackTries: mutable.Map[ByteString, SnapTrie] = mutable.Map.empty

  /** Equivalent of the Classic `preStart`: invoked once by the behavior factory before the first message. */
  def onStart(): Unit =
    if skippedTasks.nonEmpty then
      log.info(
        s"AccountRangeCoordinator starting with $concurrency workers — " +
          s"skipping ${skippedTasks.size}/${allInitialTasks.size} ranges (already completed in previous attempt)"
      )
    else log.info(s"AccountRangeCoordinator starting with $concurrency workers")
    // #1184: schedule the periodic dispatch-stalled detector. Fires every 30 s; the
    // 90 s `noActivityTimeoutMs` ensures we drain stuck slots before the controller's
    // 180 s `Account stall detected` watchdog escalates to a pivot refresh. Timer lifetime is
    // owned by `Behaviors.withTimers` — no manual cancel needed on stop.
    timers.startTimerWithFixedDelay(CheckDispatchStalled, dispatchStallCheckInterval, dispatchStallCheckInterval)
    // If all tasks were already completed, report completion immediately
    if pendingTasks.isEmpty && activeTasks.isEmpty then ctx.scheduleOnce(100.millis, ctx.self, CheckCompletion)

  /** Equivalent of the Classic `postStop`: invoked from the `PostStop` signal handler. */
  def onStop(): Unit =
    // Send final progress snapshot so controller can resume from saved positions on restart
    sendProgressSnapshot()
    // Close and delete temporary files.
    // Note: contractStorageFile is NOT deleted here — the controller reads it asynchronously
    // during storage sync (Bug 20 fix: streaming from file to avoid OOM). The controller
    // deletes it after streaming completes.
    try contractAccountsOut.close()
    catch
      case _: Exception =>
        try contractStorageOut.close()
        catch
          case _: Exception =>
            try uniqueCodeHashesOut.close()
            catch
              case _: Exception =>
                try Files.deleteIfExists(contractAccountsFile)
                catch
                  case _: Exception =>
                    // contractStorageFile intentionally NOT deleted — controller manages its lifecycle
                    // uniqueCodeHashesFile intentionally NOT deleted — controller manages its lifecycle
                    // (needed for accounts-complete recovery across process restarts)
                    log.info(
                      s"AccountRangeCoordinator stopped. Downloaded $accountsDownloaded accounts, identified $contractAccountsCount contracts ($uniqueCodeHashesCount unique codeHashes)"
                    )

  /** Collect current task positions and send to controller for resume across restarts. */
  private def sendProgressSnapshot(): Unit =
    val allTasks = pendingTasks.iterator ++ activeTasks.values.map(_._1) ++ completedTasks
    val progress: Map[ByteString, ByteString] = allTasks.map(t => t.last -> t.next).toMap
    snapSyncController ! SNAPSyncController.AccountRangeProgressCmd(progress)

  // Typed AccountRangeWorker children (Group W1) STOP on failure by default. The stop is caught by
  // the `WorkerTerminated` command (via `ctx.watchWith`), which removes the dead worker from the
  // pool and re-queues its in-flight task. Stop+re-queue is the intended recovery path; a restart
  // would silently lose the worker's `currentTask` state. (Was a Classic `OneForOneStrategy`.)

  /** Primary behavior — account-range download in progress. Most commands mutate `Impl` state and stay in the same
    * behavior; only the account-complete path transitions to [[finalizing]].
    */
  def receive(): Behavior[Command] = Behaviors
    .receiveMessage[Command] { msg =>
      msg match
        case StartAccountRangeSync(root) =>
          log.info(s"Starting account range sync for state root ${root.take(8).toHex}")
          // Tasks already initialized in constructor
          Behaviors.same

        case AccountRangeResponseMsg(response) =>
          activeTasks.get(response.requestId) match
            case None =>
              log.debug(s"Received AccountRange response for unknown or completed request ${response.requestId}")

            case Some((_, worker, _)) =>
              // Forward to the specific worker that owns this requestId so it can validate/complete the request.
              worker ! AccountRangeResponseMsg(response)
          Behaviors.same

        case PivotRefreshed(newStateRoot) =>
          log.info(s"Pivot refreshed: ${stateRoot.take(4).toHex} -> ${newStateRoot.take(4).toHex}")
          stateRoot = newStateRoot

          // #1184: drain unconditionally instead of relying on the worker → TaskFailed cascade
          // that misses silent peers. Drain BEFORE re-applying the root so all drained tasks
          // land in pendingTasks first, then pendingTasks.foreach re-tags them to the new root.
          drainActiveTasks(s"pivot refresh to ${newStateRoot.take(4).toHex}")
          val updatedForPivot = pendingTasks.dequeueAll.map(_.copy(rootHash = newStateRoot))
          pendingTasks.enqueue(updatedForPivot*)

          // Clear stateless AND snapless tracking — peers get a fresh slate at the new root.
          //
          // Previous policy (PR #1197) intentionally preserved snaplessPeers across pivots:
          // a peer without a snapshot tree won't grow one within a single sync session, so
          // clearing was thought to waste a redispatch cycle re-classifying them. That logic
          // breaks on small peer pools (sepolia's 2-8 SNAP-capable peers): if all peers
          // accumulate confirmed-snapless across a few pivots, eligible=0 and the account
          // coordinator stalls indefinitely. Observed sepolia 2026-05-14 (PR #1254
          // instrumentation): workers-known=3, snapless=2, stateless=2 → eligible=0 → stall.
          //
          // Cost of clearing on pivot: peers genuinely without a snap tree get re-tested for
          // 3 strikes per pivot cycle (≈ 9 dispatched-then-empty responses per peer per pivot).
          // At sepolia's ~3-min pivot cadence that's ~3 wasted requests / min / lying peer.
          // Acceptable trade-off vs total stall.
          //
          // Strike counter IS cleared: the new root is a fresh opportunity and a peer with 1-2
          // strikes deserves another shot.
          statelessPeers.clear()
          snaplessPeers.clear()
          emptyResponseStrikes.clear()
          pivotRefreshRequested = false

          // Clear per-peer adaptive state (new root = new response characteristics)
          peerResponseBytesTarget.clear()
          peerCooldownUntilMs.clear()
          // Note: do NOT reset consecutiveUnproductiveRefreshes here.
          // Only reset when we receive real account data (proof the new root is servable).

          // #1184: always reset the activity timer — we're starting a fresh phase.
          lastDispatchOrResponseMs = System.currentTimeMillis()

          // Resume dispatching with the fresh root. tryRedispatchPendingTasks() guards on
          // pendingTasks.nonEmpty; also fan out explicitly so peers receive work the moment
          // tasks arrive from in-flight root-mismatch re-queues — matching go-ethereum's
          // immediate idle-pool restoration after pivot (sync.go revertAccountRequest).
          tryRedispatchPendingTasks()
          knownAvailablePeers.filterNot(isPeerStateless).foreach(dispatchIfPossible)
          Behaviors.same

        case PeerAvailable(peer) =>
          // Evict stale entry for same physical node (reconnection creates new PeerId).
          // Only clear stateless marking for peers that actually reconnected with a NEW ID.
          // If the same peer is re-reported (same id), preserve its stateless marking —
          // otherwise PeerAvailable from SNAPSyncController clears stateless every ~1s,
          // bypassing the backoff mechanism entirely (Bug 24).
          val wasAlreadyKnown = knownAvailablePeers.exists(_.id == peer.id)
          val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
          knownAvailablePeers --= evicted
          evicted.foreach { p =>
            if p.id != peer.id then statelessPeers -= p.id
          }
          // Genuine reconnect (new PeerId from same address, or first-seen peer) gets a clean
          // slate. Bug 24 protection preserved: re-announced same-id peers have wasAlreadyKnown=true
          // and are not cleared, keeping the ~1s SNAPSyncController re-announce from bypassing backoff.
          if !wasAlreadyKnown then
            statelessPeers -= peer.id
            // snaplessPeers is NOT cleared here: same PeerId = same physical node = same lack-of-snapshot.
            // Clearing on reconnect would allow known-snapless ETH-mainnet peers to consume dispatch
            // slots and hash-failure budget before re-accumulating strikes (#1197).
            emptyResponseStrikes.remove(peer.id)
          knownAvailablePeers += peer
          if isPeerStateless(peer) then
            log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is stateless for current root")
          else if isPeerSnapless(peer) then
            log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is snapless (no snapshot tree)")
          else if isPeerCoolingDown(peer) then
            log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is cooling down")
          else if pendingTasks.isEmpty then log.debug("No pending tasks")
          else
            // Route through the sorted redispatch path so the fairness ordering
            // (least-in-flight first) applies to every dispatch trigger, not just
            // the periodic tryRedispatchPendingTasks calls. Without this, the
            // SNAPSyncController's per-peer PeerAvailable re-announcements drive
            // greedy dispatchIfPossible for a single peer, starving idle peers.
            tryRedispatchPendingTasks()
          Behaviors.same

        case UpdateMaxInFlightPerPeer(newLimit) =>
          log.info(s"AccountRange per-peer budget: $maxInFlightPerPeer -> $newLimit")
          maxInFlightPerPeer = newLimit
          if newLimit > 0 then tryRedispatchPendingTasks()
          Behaviors.same

        case StorageQueuePressure(paused) =>
          applyBackpressureChange(source = "storage", paused = paused)
          Behaviors.same

        case ByteCodeQueuePressure(paused) =>
          applyBackpressureChange(source = "bytecode", paused = paused)
          Behaviors.same

        case PeerUnavailable(peerId) =>
          // Peer disconnected — remove from available set and re-queue in-flight tasks.
          // go-ethereum eth/protocols/snap/sync.go:1621 (revertRequests) and Besu
          // AbstractRetryingPeerTask.java:158 both treat disconnect as transient re-queue, not failure.
          knownAvailablePeers.find(_.id.value == peerId).foreach(knownAvailablePeers -= _)
          peerCooldownUntilMs.remove(peerId)
          // Drop strike counter — a reconnect under the same id should start fresh.
          knownAvailablePeers.find(_.id.value == peerId).foreach(p => emptyResponseStrikes.remove(p.id))
          // Note: snaplessPeers entry is preserved per #1197 — if the peer reconnects under a
          // NEW PeerId, that's handled by PeerAvailable's stale-id-eviction path; same id =
          // same physical node = same lack-of-snapshot.
          // #1184: drain the slots ourselves rather than relying on the worker → TaskFailed
          // cascade. drainActiveTasks sends WorkerRequestCancelled to each affected worker so they
          // leave `working` state cleanly and don't reject redispatch with TaskFailed(0, "Worker
          // busy"). Subsumes the legacy WorkerPeerDisconnected flow.
          val drained = drainActiveTasks(s"peer $peerId unavailable", Some(peerId))
          if drained > 0 then
            lastDispatchOrResponseMs = System.currentTimeMillis()
            tryRedispatchPendingTasks()
          Behaviors.same

        // Typed worker death watch (was Classic `Terminated`): `ctx.watchWith(worker, WorkerTerminated(worker))`
        // in `createWorker` delivers this when a worker stops unexpectedly.
        case WorkerTerminated(worker) if workers.contains(worker) =>
          // Worker actor terminated unexpectedly (e.g., exception in proof verification).
          // Without this handler the task stays in activeTasks forever — the coordinator never
          // gets TaskFailed/TaskComplete so it waits for a response that will never arrive.
          log.warn(
            s"[ACCOUNT-COORD] Worker ${worker.path.name} terminated — removing from pool and re-queuing task. " +
              s"Pool: ${workers.size - 1} total, ${idleWorkers.size} idle, ${activeTasks.size} active tasks"
          )
          workers -= worker
          idleWorkers -= worker
          activeTasks.find { case (_, (_, w, _)) => w == worker }.foreach { case (reqId, (task, _, _)) =>
            log.warn(
              s"[ACCOUNT-COORD] Re-queuing task ${task.rangeString} from terminated worker (reqId=$reqId)"
            )
            activeTasks -= reqId
            pendingTasks.enqueue(task.copy(pending = false))
            tryRedispatchPendingTasks()
          }
          Behaviors.same

        case WorkerTerminated(_) =>
          // Death watch for a worker that was already removed from the pool — nothing to do.
          Behaviors.same

        case RecoverStalledAccountTasks =>
          // #1184: controller-side stall watchdog hook. The controller only sends this when it
          // already thinks we're stuck, so reset the activity timer unconditionally to give us a
          // fresh window before the next 180 s tick.
          drainActiveTasks("controller-side stall recovery")
          lastDispatchOrResponseMs = System.currentTimeMillis()
          tryRedispatchPendingTasks()
          Behaviors.same

        case CheckDispatchStalled =>
          val now = System.currentTimeMillis()
          val stalled = activeTasks.nonEmpty && (now - lastDispatchOrResponseMs) > noActivityTimeoutMs
          if stalled then
            pendingButIdleTicks = 0
            val idleSec = (now - lastDispatchOrResponseMs) / 1000
            log.warn(
              s"Account dispatch stalled: ${activeTasks.size} active, ${pendingTasks.size} pending, " +
                s"no activity for ${idleSec}s. Draining stale slots."
            )
            drainActiveTasks(s"dispatch stalled (no activity ${idleSec}s)")
            // Always reset — give dispatch a clean window so the detector doesn't re-fire on the
            // next 30 s tick. If dispatch can't proceed (no eligible peers) we'll detect that
            // next time anyway.
            lastDispatchOrResponseMs = System.currentTimeMillis()
            tryRedispatchPendingTasks()
          else if pendingTasks.nonEmpty && activeTasks.isEmpty then
            // Tasks are pending but nothing is in-flight — no eligible peers to dispatch to.
            // `lastDispatchOrResponseMs` resets on every drain (peer cycling) so the time-based
            // check above is blind to this condition. Use a tick counter instead.
            pendingButIdleTicks += 1
            val nowMs = System.currentTimeMillis()
            val soonestCooldownSec = peerCooldownUntilMs.values.minOption
              .map(t => math.max(0L, (t - nowMs) / 1000))
              .getOrElse(-1L)
            log.warn(
              s"[ACCOUNT-IDLE] tick $pendingButIdleTicks/3: ${pendingTasks.size} tasks pending, " +
                s"0 active. Pool: ${knownAvailablePeers.size} known, " +
                s"${statelessPeers.size} stateless, ${snaplessPeers.size} snapless, " +
                s"${peerCooldownUntilMs.size} cooling" +
                (if soonestCooldownSec >= 0 then s" (soonest ready in ${soonestCooldownSec}s)" else "") +
                s". root=${stateRoot.take(4).toHex}"
            )
            if pendingButIdleTicks >= 3 then
              // 3 × 30 s = 90 s of consecutive ticks with pending tasks and zero dispatches.
              log.warn(
                s"[ACCOUNT-STALL] ${pendingTasks.size} tasks pending, no active dispatches for " +
                  s"$pendingButIdleTicks watchdog ticks " +
                  s"(${dispatchStallCheckInterval.toSeconds * pendingButIdleTicks}s). " +
                  s"Attempting floor recovery then pivot refresh if needed."
              )
              pendingButIdleTicks = 0
              tryRedispatchPendingTasks()
              // If floor revival also couldn't dispatch anything, escalate.
              if activeTasks.isEmpty && !pivotRefreshRequested then
                pivotRefreshRequested = true
                lastPivotRefreshTimeMs = System.currentTimeMillis()
                consecutiveUnproductiveRefreshes += 1
                log.warn(
                  s"[ACCOUNT-STALL] Floor revival exhausted — ${knownAvailablePeers.size} known peers, " +
                    s"${statelessPeers.size} stateless, ${peerCooldownUntilMs.size} cooling. " +
                    s"Requesting pivot refresh (attempt=$consecutiveUnproductiveRefreshes)."
                )
                snapSyncController ! PivotStateUnservable(
                  rootHash = stateRoot,
                  reason =
                    s"tasks pending but no eligible peers after ${consecutiveUnproductiveRefreshes} stall cycles",
                  consecutiveEmptyResponses = knownAvailablePeers.size
                )
          else pendingButIdleTicks = 0
          Behaviors.same

        case TaskComplete(requestId, result) =>
          handleTaskComplete(requestId, result)
          Behaviors.same

        case TaskFailed(requestId, reason) =>
          handleTaskFailed(requestId, reason)
          Behaviors.same

        case AccountGetProgress(replyTo) =>
          replyTo ! calculateProgress()
          Behaviors.same

        case AccountGetContractAccounts(replyTo) =>
          replyTo ! ContractAccountsResponse(
            readContractFile(contractAccountsFile, contractAccountsOut, contractAccountsCount)
          )
          Behaviors.same

        case AccountGetContractStorageAccounts(replyTo) =>
          replyTo ! ContractStorageAccountsResponse(
            readContractFile(contractStorageFile, contractStorageOut, contractStorageCount)
          )
          Behaviors.same

        case AccountGetUniqueCodeHashes(replyTo) =>
          replyTo ! UniqueCodeHashesResponse(readUniqueCodeHashes())
          Behaviors.same

        case AccountGetStorageFileInfo(replyTo) =>
          contractStorageOut.flush()
          replyTo ! StorageFileInfoResponse(contractStorageFile, contractStorageCount)
          Behaviors.same

        case AccountGetCodeHashesFileInfo(replyTo) =>
          uniqueCodeHashesOut.flush()
          replyTo ! CodeHashesFileInfoResponse(uniqueCodeHashesFile, uniqueCodeHashesCount)
          Behaviors.same

        case StoreAccountChunk(task, remaining, totalCount, storedSoFar, isTaskRangeComplete) =>
          handleStoreAccountChunk(task, remaining, totalCount, storedSoFar, isTaskRangeComplete)
          Behaviors.same

        case CheckCompletion =>
          computeKeyspaceEstimate().foreach { est =>
            snapSyncController ! SNAPSyncController.ProgressAccountEstimate(est)
          }
          if isComplete then
            log.info("Account range sync complete!")
            log.info(
              s"[SNAP-PROGRESS] ACCOUNT-RANGE 100% — $accountsDownloaded accounts downloaded — COMPLETE"
            )

            // Signal controller IMMEDIATELY so storage+bytecode phases can start in parallel
            // with trie finalization. These phases don't need the finalized account trie —
            // they operate on their own state roots. This saves 50s-25min of serial blocking.
            snapSyncController ! SNAPSyncController.AccountRangeSyncComplete

            log.info(s"Starting async trie finalization for $accountsDownloaded accounts...")
            // Notify controller so progress monitor shows finalization status
            snapSyncController ! SNAPSyncController.ProgressAccountsFinalizingTrie

            // Run the expensive flush (O(n*log(n)) trie collapse + RocksDB write) on the
            // dedicated `account-trie-dispatcher` so it can't squeeze the global pool or
            // sync-dispatcher. Generation token added defensively (mirrors PR #1163).
            trieFlushGeneration += 1
            val gen = trieFlushGeneration
            val selfRef = ctx.self
            Future {
              blocking(finalizeTrie())
            }(accountTrieEc)
              .onComplete {
                case Success(result) => selfRef ! TrieFlushComplete(gen, result)
                // `Status.Failure` is Classic ask protocol with no Typed equivalent — route the
                // exception through an explicit internal Command instead.
                case Failure(ex) => selfRef ! TrieFlushFailed(gen, ex.getMessage)
              }(accountTrieEc)
            // Switch to finalizing state so no message can touch the trie during flush.
            finalizing()
          else Behaviors.same

        case other =>
          // Defensive catch-all for the non-sealed `Command` trait (Messages.scala package-boundary
          // constraint — Scala 3 forbids sealing across source files). Surfaces any unexpected message.
          log.warn(s"[ACCOUNT-COORD] Unhandled command in receive: $other")
          Behaviors.same
    }
    .receiveSignal { case (_, org.apache.pekko.actor.typed.PostStop) =>
      // Formerly `postStop`: snapshot progress + close temp files. The recurring stall-check timer
      // auto-cancels with the behavior.
      onStop()
      Behaviors.same
    }

  /** Behavior during async trie finalization. The StackTrie finalize is running on `account-trie-dispatcher`.
    * Package-private so tests can drive the finalisation phase directly.
    */
  private[actors] def finalizing(): Behavior[Command] = Behaviors
    .receiveMessage[Command] {
      // Stale-generation drop. A completion arriving for a generation that's been bumped
      // since spawn (e.g., the actor restarted finalisation) is silently ignored — data
      // is on disk either way, and the in-flight Future can't be cancelled.
      case TrieFlushComplete(gen, _) if gen != trieFlushGeneration =>
        log.debug(s"Dropping stale TrieFlushComplete (gen=$gen, current=$trieFlushGeneration)")
        Behaviors.same

      case TrieFlushComplete(_, Right(finalizedRoot)) =>
        log.info(
          "State trie finalized successfully with root {}",
          finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString
        )
        snapSyncController ! SNAPSyncController.AccountTrieFinalized(finalizedRoot)
        snapSyncController ! SNAPSyncController.ProgressAccountsTrieFinalized
        Behaviors.stopped

      case TrieFlushComplete(_, Left(error)) =>
        log.error(s"Failed to finalize trie: $error")
        snapSyncController ! SNAPSyncController.AccountTrieFinalizationFailed(error)
        Behaviors.stopped

      // Stale-generation drop for the failure path too — a failure for a superseded generation
      // is ignored, mirroring the TrieFlushComplete stale-drop above.
      case TrieFlushFailed(gen, _) if gen != trieFlushGeneration =>
        log.debug(s"Dropping stale TrieFlushFailed (gen=$gen, current=$trieFlushGeneration)")
        Behaviors.same

      case TrieFlushFailed(_, error) =>
        log.error(s"Trie finalization failed with exception: $error")
        snapSyncController ! SNAPSyncController.AccountTrieFinalizationFailed(error)
        Behaviors.stopped

      case _: PeerAvailable =>
        // Ignore — no more tasks to dispatch during finalization
        Behaviors.same

      case _: PivotRefreshed =>
        log.info("Ignoring PivotRefreshed during trie finalization")
        Behaviors.same

      case AccountGetProgress(replyTo) =>
        replyTo ! calculateProgress()
        Behaviors.same

      case AccountGetContractAccounts(replyTo) =>
        replyTo ! ContractAccountsResponse(
          readContractFile(contractAccountsFile, contractAccountsOut, contractAccountsCount)
        )
        Behaviors.same

      case AccountGetContractStorageAccounts(replyTo) =>
        replyTo ! ContractStorageAccountsResponse(
          readContractFile(contractStorageFile, contractStorageOut, contractStorageCount)
        )
        Behaviors.same

      case AccountGetUniqueCodeHashes(replyTo) =>
        replyTo ! UniqueCodeHashesResponse(readUniqueCodeHashes())
        Behaviors.same

      case AccountGetStorageFileInfo(replyTo) =>
        contractStorageOut.flush()
        replyTo ! StorageFileInfoResponse(contractStorageFile, contractStorageCount)
        Behaviors.same

      case AccountGetCodeHashesFileInfo(replyTo) =>
        uniqueCodeHashesOut.flush()
        replyTo ! CodeHashesFileInfoResponse(uniqueCodeHashesFile, uniqueCodeHashesCount)
        Behaviors.same

      case CheckCompletion =>
        // Already finalizing, ignore
        Behaviors.same

      case _ =>
        // Ignore all other commands during finalisation (no tasks to dispatch). Also covers the
        // non-sealed Command trait exhaustiveness gap.
        Behaviors.same
    }
    .receiveSignal { case (_, org.apache.pekko.actor.typed.PostStop) =>
      // The coordinator stops from this behavior after trie finalisation (`Behaviors.stopped`).
      // Run the same teardown as the primary behavior's PostStop.
      onStop()
      Behaviors.same
    }

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

  private def markWorkerIdle(worker: WorkerRef): Unit =
    if workers.contains(worker) then idleWorkers += worker

  /** Dispatch up to maxInFlightPerPeer tasks to the given peer (pipelining). Mirrors
    * ByteCodeCoordinator.dispatchIfPossible — the proven pattern for SNAP sync.
    *
    * No-op while any downstream coordinator (storage OR bytecode) is over its high-water mark. Workers already in
    * flight always run to completion, so existing work continues to drain, but we stop producing new tasks (which would
    * in turn enqueue more storage / bytecode work) until every signalling downstream has released.
    */
  private def dispatchIfPossible(peer: Peer): Unit =
    if pendingTasks.nonEmpty && !downstreamBackpressureActive then
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
  private def applyBackpressureChange(source: String, paused: Boolean): Unit =
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

  // How many accounts to insert per chunk before yielding to the actor mailbox.
  // SnapHashTrie inserts are O(depth) memory + O(1) amortised compute (~2-20ms per 2000 accounts).
  private val storeChunkSize = 2000

  private def handleTaskComplete(
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
        // Cannot advance beyond 0xFF..; this must be the end.
        consumedKeyspace += task.remainingKeyspace
        (true, task)
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
    sendProgressSnapshot()
    ctx.self ! CheckCompletion

  private def handleTaskFailed(requestId: BigInt, reason: String): Unit =
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
        snapSyncController ! PivotStateUnservable(
          rootHash = stateRoot,
          reason = s"task ${task.rangeString} hit MaxRequeuesPerTask: $reason",
          consecutiveEmptyResponses = AccountRangeCoordinator.MaxRequeuesPerTask
        )
    else
      log.info(
        s"[ACCOUNT-REQUEUE] task ${task.rangeString} requeued " +
          s"($newCount/${AccountRangeCoordinator.MaxRequeuesPerTask}): $reason"
      )
      pendingTasks.enqueue(task.copy(requeueCount = newCount))
    tryRedispatchPendingTasks()

  private def tryRedispatchPendingTasks(): Unit =
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

  /** Handle a chunk of account storage, inserting a batch into the per-task SnapHashTrie and yielding back to the actor
    * mailbox between chunks. Nodes batch-flush to RocksDB inside SnapHashTrie at the 8 MiB threshold.
    */
  private def handleStoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ): Unit =
    val (chunk, rest) = remaining.splitAt(storeChunkSize)

    try
      // Route accounts to this task's per-range StackTrie. Inserts are O(depth) memory + O(1)
      // amortised compute; emitted nodes batch-flush to RocksDB inside SnapHashTrie at the 8 MiB
      // threshold, so we never accumulate a multi-GiB in-memory pivot trie.
      val trie = getOrCreateTaskStackTrie(task)
      chunk.foreach { case (accountHash, account) =>
        trie.update(accountHash.toArray, Account.accountSerializer.toBytes(account))
      }

      val newStored = storedSoFar + chunk.size
      // Report incremental progress so the stagnation watchdog sees activity
      accountsDownloaded += chunk.size
      snapSyncController ! SNAPSyncController.ProgressAccountsSynced(chunk.size.toLong)

      // Periodic progress log (every 100K accounts) to show download rate without per-chunk noise
      if accountsDownloaded - lastProgressLogAt >= ProgressLogInterval then
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if elapsed > 0 then (accountsDownloaded / elapsed).toLong else 0L
        val pct = (consumedKeyspace * 10000 / totalKeyspace).toDouble / 100.0
        log.info(
          s"Account download progress: $accountsDownloaded accounts (${"%.1f".format(pct)}% keyspace) " +
            s"(${completedTasks.size}/$concurrency ranges done, " +
            s"${pendingTasks.size} pending, ${activeTasks.size} active, " +
            s"${workers.size} workers/${activePeerCount} peers, " +
            s"${rate} accounts/sec)"
        )
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setAccountActivePeers(activePeerCount)
        lastProgressLogAt = accountsDownloaded
        val pctInt = pct.toInt
        val (newM, crossed) = ProgressMilestones.crossed(pctInt.toLong, 100L, lastFlatMilestonePct)
        lastFlatMilestonePct = newM
        crossed.foreach { m =>
          log.info(
            s"[SNAP-PROGRESS] ACCOUNT-RANGE $m% keyspace covered | $accountsDownloaded accounts | $rate accts/s"
          )
        }

      if rest.nonEmpty then
        log.debug(s"Stored chunk: $newStored/$totalCount accounts (${rest.size} remaining)")
        // Yield to actor mailbox - other messages (PeerAvailable, responses) process before next chunk
        ctx.self ! StoreAccountChunk(task, rest, totalCount, newStored, isTaskRangeComplete)
      else
        // Mark task done / re-enqueue BEFORE potentially spawning async flush — so the
        // task tracking is up to date by the time we re-enter `receive` after flushing.
        if isTaskRangeComplete then
          completedTasks += task.copy(done = true)
          // On the StackTrie path, the task's per-range StackTrie has accumulated all of
          // this range's accounts; commit it to finalise the right boundary and flush the
          // remaining pending batch to RocksDB. The fragment root is logged for diagnostics
          // — it does NOT match the pivot's claimed root (each task produces only a
          // partial trie); healing reconciles fragments against the pivot root.
          taskStackTries.remove(task.last).foreach { trie =>
            val fragmentRoot = trie.commit()
            log.info(
              s"Account range COMPLETE: ${task.rangeString} " +
                s"(${completedTasks.size}/$concurrency ranges done, $accountsDownloaded accounts total, " +
                s"fragment root ${fragmentRoot.take(4).toArray.map("%02x".format(_)).mkString})"
            )
          }
          // Send progress snapshot so controller can resume from saved positions
          sendProgressSnapshot()
        else
          // Need more requests for the same interval; re-queue with updated `next`.
          pendingTasks.enqueue(task)
          // Trigger immediate redispatch so the re-queued task is picked up without
          // waiting up to 1s for the next PeerAvailable message (BUG-DISPATCH-001).
          tryRedispatchPendingTasks()
          // Persist partial range position so a crash mid-range resumes from here,
          // not the beginning of the range (go-ethereum saveSyncStatus() parity).
          sendProgressSnapshot()

        // Each task's SnapHashTrie batches its emissions and flushes to RocksDB at the 8 MiB
        // threshold (or on task-complete commit). No global flush required.
        log.debug(s"Stored all $totalCount accounts via StackTrie ($accountsDownloaded total)")
        ctx.self ! CheckCompletion
    catch
      case e: Exception =>
        log.error(s"Failed to store account chunk: ${e.getMessage}", e)
        // Re-queue task for retry
        pendingTasks.enqueue(task.copy(pending = false, done = false))

  /** Get-or-create the per-task [[SnapTrie]] for the StackTrie path. Each task gets its own streaming trie keyed by
    * `task.last` (the end-of-range boundary, unique per task).
    *
    * HashScheme (default): nodes flush to `mptStorage` via `storeRawNodes`, routing through `FastSyncNodeStorage` with
    * pivot-block-number tagging for pruning. PathScheme: nodes are written path-keyed to `PathNodeStorage`, with
    * left-boundary skip when `resumeProgress` has a cursor for this task.
    */
  private def getOrCreateTaskStackTrie(task: AccountTask): SnapTrie =
    taskStackTries.getOrElseUpdate(
      task.last,
      storageScheme match
        case StorageScheme.Hash =>
          new SnapHashTrie(batch => mptStorage.storeRawNodes(batch))
        case StorageScheme.Path =>
          val pns = pathNodeStorage.getOrElse(
            throw new IllegalStateException("PathScheme requires pathNodeStorage to be set")
          )
          val skipLeft = resumeProgress.contains(task.last)
          new SnapPathTrie(
            owner = ByteString.empty,
            skipLeftBoundary = skipLeft,
            writePath = (path, _, blob) => pns.writeAccountNode(path, blob),
            deleteExact = path => pns.deleteAccountNode(path)
          )
    )

  /** Identify contract accounts (those with non-empty code hash)
    *
    * @param accounts
    *   Accounts to scan for contracts
    */
  private def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit =
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val newCodeHashes = mutable.ArrayBuffer.empty[ByteString]
    val newStorageTasks = mutable.ArrayBuffer.empty[StorageTask]
    var count = 0

    accounts.foreach { case (accountHash, account) =>
      if account.codeHash != Account.EmptyCodeHash then
        // Write 32-byte accountHash + 32-byte codeHash to bytecode file (crash recovery)
        contractAccountsOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
        contractAccountsOut.write(account.codeHash.value.toArray.padTo(32, 0.toByte), 0, 32)
        // Write 32-byte accountHash + 32-byte storageRoot to storage file (crash recovery)
        contractStorageOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
        contractStorageOut.write(account.storageRoot.toArray.padTo(32, 0.toByte), 0, 32)
        count += 1

        // Track unique codeHashes via Bloom filter + temp file (~4MB RAM vs 200MB HashSet).
        // The Bloom filter has 0.01% FPR — ~200 of 2M hashes may be missed but the
        // recovery scan (Bug 20 hardening) catches any gaps.
        if !codeHashBloom.mightContain(account.codeHash.value) then
          codeHashBloom.put(account.codeHash.value)
          uniqueCodeHashesOut.write(account.codeHash.value.toArray.padTo(32, 0.toByte), 0, 32)
          uniqueCodeHashesCount += 1
          newCodeHashes += account.codeHash.value

        // Collect storage task for inline dispatch (skip contracts with empty storage)
        if account.storageRoot.value.nonEmpty && account.storageRoot.value != emptyRoot then
          newStorageTasks += StorageTask.createStorageTask(accountHash, account.storageRoot.value)
    }

    if count > 0 then
      contractAccountsCount += count
      contractStorageCount += count
      // Flush file streams (crash recovery path)
      contractAccountsOut.flush()
      contractStorageOut.flush()
      uniqueCodeHashesOut.flush()
      log.info(
        s"Identified $count contract accounts (total: $contractAccountsCount, unique codeHashes: $uniqueCodeHashesCount)"
      )

    // Geth-aligned: dispatch contract data inline to controller → bytecode/storage coordinators.
    // This eliminates the 6+ minute gap between account completion and first storage/bytecode request.
    if newCodeHashes.nonEmpty || newStorageTasks.nonEmpty then
      snapSyncController ! SNAPSyncController.IncrementalContractData(
        newCodeHashes.toSeq,
        newStorageTasks.toSeq
      )

  /** Finalize the trie and ensure all nodes including the root are persisted to storage.
    *
    * @return
    *   Either error message or success
    */
  private def finalizeTrie(): Either[String, ByteString] =
    // Runs inside a Future on `account-trie-dispatcher` — uses `futureLog` (plain SLF4J), NOT
    // the thread-confined `ctx.log`.
    try
      futureLog.info("Finalizing state trie...")
      // StackTrie path: each task's SnapHashTrie was committed on task-complete in
      // `handleStoreAccountChunk`, flushing its right boundary + remaining batch to
      // RocksDB. Defensively commit any stragglers (should be empty unless a task
      // finished after its `isTaskRangeComplete` branch was missed).
      if taskStackTries.nonEmpty then
        futureLog.warn(s"Finalising ${taskStackTries.size} uncommitted task StackTries (unexpected)")
        taskStackTries.values.foreach { trie =>
          val _ = trie.commit()
        }
        taskStackTries.clear()
      // Use the pivot's claimed root as the "finalized root". With per-task fragments
      // there is no single computed root; healing reconciles the on-disk trie against
      // `stateRoot` regardless.
      futureLog.info(
        s"State trie finalization complete (StackTrie path, 16 fragments). " +
          s"Reported root: ${stateRoot.take(8).toArray.map("%02x".format(_)).mkString}..."
      )
      Right(stateRoot)
    catch
      case e: Exception =>
        futureLog.error(s"Failed to finalize trie: ${e.getMessage}", e)
        Left(s"Trie finalization error: ${e.getMessage}")

  /** Read all contract account entries from a temporary file. Each entry is 64 bytes: 32-byte key + 32-byte value.
    * Flushes the output stream first to ensure all data is written.
    */
  private def readContractFile(
      filePath: Path,
      out: BufferedOutputStream,
      count: Long
  ): Seq[(ByteString, ByteString)] =
    out.flush()
    if count == 0 then Seq.empty
    else
      val raf = new RandomAccessFile(filePath.toFile, "r")
      try
        val result = new mutable.ArrayBuffer[(ByteString, ByteString)](count.toInt)
        val buf = new Array[Byte](ContractEntrySize)
        var i = 0L
        while i < count do
          raf.readFully(buf)
          val key = ByteString(java.util.Arrays.copyOfRange(buf, 0, 32))
          val value = ByteString(java.util.Arrays.copyOfRange(buf, 32, 64))
          result += ((key, value))
          i += 1
        result.toSeq
      finally raf.close()

  /** Read unique codeHashes from the Bloom-filtered temp file. Each entry is 32 bytes. File size is ~64MB for ~2M
    * unique hashes (vs 4.7GB for 73.5M raw entries).
    */
  private def readUniqueCodeHashes(): Seq[ByteString] =
    uniqueCodeHashesOut.flush()
    if uniqueCodeHashesCount == 0 then Seq.empty
    else
      val raf = new RandomAccessFile(uniqueCodeHashesFile.toFile, "r")
      try
        val result = new mutable.ArrayBuffer[ByteString](uniqueCodeHashesCount.toInt)
        val buf = new Array[Byte](32)
        var i = 0L
        while i < uniqueCodeHashesCount do
          raf.readFully(buf)
          result += ByteString(buf.clone())
          i += 1
        result.toSeq
      finally raf.close()

  private def calculateProgress(): AccountRangeStats =
    val total = completedTasks.size + activeTasks.size + pendingTasks.size
    val progress = if total == 0 then 1.0 else completedTasks.size.toDouble / total
    val elapsedMs = System.currentTimeMillis() - startTime

    AccountRangeStats(
      accountsDownloaded = accountsDownloaded,
      bytesDownloaded = bytesDownloaded,
      tasksCompleted = completedTasks.size,
      tasksActive = activeTasks.size,
      tasksPending = pendingTasks.size,
      progress = progress,
      elapsedTimeMs = elapsedMs,
      contractAccountsFound = contractAccountsCount
    )

  private def isComplete: Boolean =
    pendingTasks.isEmpty && activeTasks.isEmpty

  /** Estimate total accounts from keyspace coverage. Uses completed tasks' ranges to compute keyspace density (accounts
    * per unit of keyspace), then extrapolates to the full 2^256 space. Only considers tasks that have actually been
    * explored, avoiding inflation from un-dispatched chunks.
    *
    * Uses BigInt arithmetic throughout to avoid precision loss — 2^256 is far beyond Double's 15-17 significant digits,
    * so `covered.toDouble / keyspaceSize.toDouble` always produces 0.0.
    */
  private def computeKeyspaceEstimate(): Option[Long] =
    if accountsDownloaded < 10000 then None // too early for reliable estimate
    else
      val keyspaceSize = BigInt(2).pow(256)
      val nonCompleteTasks = pendingTasks.toSeq ++ activeTasks.values.map(_._1)
      val remaining =
        if nonCompleteTasks.isEmpty then BigInt(0)
        else
          nonCompleteTasks.foldLeft(BigInt(0)) { case (sum, task) =>
            val taskEnd = BigInt(1, task.last.toArray)
            val taskPos = BigInt(1, task.next.toArray)
            sum + (taskEnd - taskPos).max(0)
          }

      val covered = keyspaceSize - remaining
      if covered <= 0 then None
      else
        // Use BigInt arithmetic: estimated = accountsDownloaded * keyspaceSize / covered
        // This avoids Double precision loss when dividing by 2^256.
        val estimatedBig = BigInt(accountsDownloaded) * keyspaceSize / covered
        // Sanity: reject absurd values (overflow, < downloaded, > 2 billion)
        // ETC mainnet has ~600M addresses per blockscout; cap at 2B for safety margin
        if estimatedBig <= accountsDownloaded || estimatedBig > BigInt(2000000000L) then None
        else Some(estimatedBig.toLong)

object AccountRangeCoordinator:

  sealed trait Command
  case class StartAccountRangeSync(stateRoot: ByteString) extends Command
  case class PeerAvailable(peer: Peer) extends Command
  case class TaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, com.chipprbots.ethereum.domain.Account)], Seq[ByteString])]
  ) extends Command
  case class TaskFailed(requestId: BigInt, reason: String) extends Command
  case class PeerUnavailable(peerId: String) extends Command
  case object GetProgress extends Command
  case class AccountGetProgress(replyTo: org.apache.pekko.actor.typed.ActorRef[AccountRangeStats]) extends Command
  case object GetContractAccounts extends Command
  case class AccountGetContractAccounts(replyTo: org.apache.pekko.actor.typed.ActorRef[ContractAccountsResponse])
      extends Command
  case class ContractAccountsResponse(accounts: Seq[(ByteString, ByteString)]) extends Command
  case object GetContractStorageAccounts extends Command
  case class AccountGetContractStorageAccounts(
      replyTo: org.apache.pekko.actor.typed.ActorRef[ContractStorageAccountsResponse]
  ) extends Command
  case class ContractStorageAccountsResponse(accounts: Seq[(ByteString, ByteString)]) extends Command
  case object GetUniqueCodeHashes extends Command
  case class AccountGetUniqueCodeHashes(replyTo: org.apache.pekko.actor.typed.ActorRef[UniqueCodeHashesResponse])
      extends Command
  case class UniqueCodeHashesResponse(codeHashes: Seq[ByteString])
  case object GetStorageFileInfo extends Command
  case class AccountGetStorageFileInfo(replyTo: org.apache.pekko.actor.typed.ActorRef[StorageFileInfoResponse])
      extends Command
  case class StorageFileInfoResponse(filePath: java.nio.file.Path, count: Long)
  case object GetCodeHashesFileInfo extends Command
  case class AccountGetCodeHashesFileInfo(replyTo: org.apache.pekko.actor.typed.ActorRef[CodeHashesFileInfoResponse])
      extends Command
  case class CodeHashesFileInfoResponse(filePath: java.nio.file.Path, count: Long)
  case object CheckCompletion extends Command
  case class AccountRangeProgress(progress: Map[ByteString, ByteString]) extends Command
  case class PivotRefreshed(newStateRoot: ByteString) extends Command
  case object RecoverStalledAccountTasks extends Command
  case class StorageQueuePressure(paused: Boolean) extends Command
  case class ByteCodeQueuePressure(paused: Boolean) extends Command
  private[actors] case object CheckDispatchStalled extends Command
  private[actors] case class StoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, com.chipprbots.ethereum.domain.Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ) extends Command
  case class UpdateMaxInFlightPerPeer(newLimit: Int) extends Command
  sealed trait WorkerMessage
  case class FetchAccountRange(
      task: AccountTask,
      peer: Peer,
      requestId: BigInt,
      responseBytes: BigInt = BigInt(512 * 1024)
  ) extends WorkerMessage
  case class AccountRangeResponseMsg(response: AccountRange) extends WorkerMessage with Command
  case class RequestTimeout(requestId: BigInt) extends WorkerMessage
  case class WorkerPeerDisconnected(peerId: String) extends WorkerMessage
  case class WorkerRequestCancelled(requestId: BigInt) extends WorkerMessage

  /** Hard cap on consecutive re-queues for a single account task before the coordinator escalates to the controller via
    * `PivotStateUnservable`. On ETC mainnet with 1-5 SNAP peers, serve-window gaps can last 5-10 minutes. At 5s
    * cooldown (empty-without-proof) 20 requeues covers ~100s; at 30s timeout, ~10 minutes. Previously 8 — too tight for
    * peer-scarce networks where transient statelessness is the norm, not the exception.
    */
  val MaxRequeuesPerTask: Int = 20

  /** Async trie-finalisation result. `generation` matches `trieFlushGeneration` at spawn time so stale completions
    * (after a state transition / restart) can be dropped without applying against the wrong assumption.
    */
  private[actors] case class TrieFlushComplete(generation: Long, result: Either[String, ByteString]) extends Command

  /** Internal command: async trie finalisation failed. Replaces the Classic `Status.Failure` self-send (which has no
    * Typed equivalent). Carries the finalisation `generation` for stale-drop, mirroring `TrieFlushComplete`.
    */
  private[actors] case class TrieFlushFailed(generation: Long, error: String) extends Command

  /** Internal command: a Typed `AccountRangeWorker` child stopped (via `ctx.watchWith`). Replaces the Classic
    * `Terminated` signal. The coordinator removes the dead worker from the pool and re-queues its in-flight task.
    */
  private[actors] case class WorkerTerminated(worker: org.apache.pekko.actor.typed.ActorRef[AccountRangeWorker.Command])
      extends Command

  def apply(
      stateRoot: ByteString,
      networkPeerManager: org.apache.pekko.actor.typed.ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      concurrency: Int,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      resumeProgress: Map[ByteString, ByteString] = Map.empty,
      initialMaxInFlightPerPeer: Int = 5,
      initialResponseBytes: Int = 524288,
      minResponseBytes: Int = 102400,
      accountTrieEcOverride: Option[ExecutionContext] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorage: Option[PathNodeStorage] = None
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      Behaviors.setup { ctx =>
        val impl = new AccountRangeCoordinatorImpl(
          ctx = ctx,
          timers = timers,
          initialStateRoot = stateRoot,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker,
          mptStorage = mptStorage,
          concurrency = concurrency,
          snapSyncController = snapSyncController,
          resumeProgress = resumeProgress,
          initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
          initialResponseBytesConfig = initialResponseBytes,
          minResponseBytesConfig = minResponseBytes,
          accountTrieEcOverride = accountTrieEcOverride,
          storageScheme = storageScheme,
          pathNodeStorage = pathNodeStorage
        )
        impl.onStart()
        impl.receive()
      }
    }

case class AccountRangeStats(
    accountsDownloaded: Long,
    bytesDownloaded: Long,
    tasksCompleted: Int,
    tasksActive: Int,
    tasksPending: Int,
    progress: Double,
    elapsedTimeMs: Long,
    contractAccountsFound: Long
)
