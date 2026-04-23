package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, SupervisorStrategy, OneForOneStrategy, Status}
import org.apache.pekko.actor.SupervisorStrategy._
import org.apache.pekko.util.ByteString

import java.io.{BufferedOutputStream, FileOutputStream, RandomAccessFile}
import java.nio.file.{Files, Path}

import scala.collection.mutable
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration._
import scala.util.{Success, Failure}

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.db.storage.{DeferredWriteMptStorage, MptStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.google.common.hash.{BloomFilter, Funnel, PrimitiveSink}

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
class AccountRangeCoordinator(
    initialStateRoot: ByteString,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    mptStorage: MptStorage,
    concurrency: Int,
    snapSyncController: ActorRef,
    resumeProgress: Map[ByteString, ByteString] = Map.empty,
    initialMaxInFlightPerPeer: Int = 5,
    trieFlushThreshold: Int = 50000,
    initialResponseBytesConfig: Int = 524288,
    minResponseBytesConfig: Int = 102400
) extends Actor
    with ActorLogging {

  import Messages._
  import SNAPSyncController.PivotStateUnservable

  // Mutable state root — updated in-place when the controller refreshes the pivot.
  private var stateRoot: ByteString = initialStateRoot

  // Per-peer concurrency budget — dynamically adjusted by SNAPSyncController via UpdateMaxInFlightPerPeer.
  // Part of global per-peer request budgeting (Geth-aligned: total 5 per peer across all coordinators).
  private var maxInFlightPerPeer: Int = initialMaxInFlightPerPeer

  // Stateless peer tracking: peers that return "Missing proof for empty account range"
  // are unable to serve the current state root. Unlike the previous exponential cooldown,
  // this is a binary classification: either a peer can serve the root or it can't.
  // When ALL known peers become stateless, we request a pivot refresh from the controller.
  private val statelessPeers = mutable.Set[com.chipprbots.ethereum.network.PeerId]()
  private var pivotRefreshRequested = false
  private var pivotWasRefreshed = false

  private def isPeerStateless(peer: Peer): Boolean =
    statelessPeers.contains(peer.id)

  private def markPeerStateless(peer: Peer, reason: String): Unit = {
    val shouldMark = if (reason.contains("Missing proof for empty account range")) {
      // Peer explicitly returned empty response — immediately stateless
      true
    } else if (reason.contains("Request timeout")) {
      // Peer timed out — track consecutive timeouts.
      // On ETC mainnet, peers silently stop responding when the snap serve window expires
      // rather than returning explicit empty responses. After N consecutive timeouts,
      // we treat the peer as stateless so pivot refresh can trigger.
      val count = peerConsecutiveTimeouts.getOrElse(peer.id.value, 0) + 1
      peerConsecutiveTimeouts.update(peer.id.value, count)
      if (count >= consecutiveTimeoutThreshold) {
        log.info(s"Peer ${peer.id.value} hit $count consecutive timeouts — treating as stateless")
        true
      } else {
        false
      }
    } else {
      false
    }

    if (shouldMark) {
      statelessPeers.add(peer.id)
      log.info(
        s"Peer ${peer.id.value} marked stateless for root ${stateRoot.take(4).toHex} " +
          s"(${statelessPeers.size}/${knownAvailablePeers.size} peers stateless)"
      )
      maybeRequestPivotRefresh()
    }
  }

  private def maybeRequestPivotRefresh(): Unit = {
    if (pivotRefreshRequested) return
    // If all known peers are stateless, the root has aged out of the serve window
    val allStateless = knownAvailablePeers.nonEmpty &&
      knownAvailablePeers.forall(p => statelessPeers.contains(p.id))
    if (!allStateless) return

    // Exponential backoff: don't hammer the controller with rapid refresh requests
    val now = System.currentTimeMillis()
    val backoffMs = math.min(
      minRefreshIntervalMs * (1L << math.min(consecutiveUnproductiveRefreshes, 3)),
      maxRefreshIntervalMs
    )
    val elapsed = now - lastPivotRefreshTimeMs
    if (lastPivotRefreshTimeMs > 0 && elapsed < backoffMs) {
      log.info(
        s"All ${statelessPeers.size} peers stateless but backing off pivot refresh " +
          s"(${elapsed / 1000}s / ${backoffMs / 1000}s elapsed, attempt=${consecutiveUnproductiveRefreshes + 1}). " +
          "Will retry after backoff."
      )
      // Schedule a re-check after the remaining backoff period
      import context.dispatcher
      context.system.scheduler.scheduleOnce((backoffMs - elapsed).millis, self, CheckCompletion)
      return
    }

    pivotRefreshRequested = true
    lastPivotRefreshTimeMs = now
    consecutiveUnproductiveRefreshes += 1
    log.warning(
      s"All ${statelessPeers.size} known peers are stateless for root ${stateRoot.take(4).toHex}. " +
        s"Requesting pivot refresh from controller (attempt=$consecutiveUnproductiveRefreshes, backoff=${backoffMs / 1000}s)."
    )
    snapSyncController ! PivotStateUnservable(
      rootHash = stateRoot,
      reason = "all peers stateless for AccountRange root",
      consecutiveEmptyResponses = statelessPeers.size
    )
  }

  // Task management — resume ranges from saved positions (core-geth parity).
  // On restart, each range resumes from its saved `next` position instead of starting from 0x00.
  private val allInitialTasks = AccountTask.createInitialTasks(stateRoot, concurrency)
  private val (skippedTasks, remainingTasks) = if (resumeProgress.nonEmpty) {
    val resumed = allInitialTasks.map { task =>
      resumeProgress.get(task.last) match {
        case Some(savedNext)
            if BigInt(1, savedNext.toArray.padTo(32, 0.toByte)) >=
              BigInt(1, task.last.toArray.padTo(32, 0.toByte)) =>
          // Range fully traversed — mark as done
          task.next = task.last
          task.done = true
          task
        case Some(savedNext) if savedNext != task.next =>
          log.info(
            s"Resuming range ${task.rangeString} from saved position ${savedNext.take(4).toArray.map("%02x".format(_)).mkString}"
          )
          task.next = savedNext
          task
        case _ => task
      }
    }
    val (done, todo) = resumed.partition(_.done)
    (done, todo)
  } else {
    (Seq.empty, allInitialTasks)
  }
  // Priority queue: dequeue the task with the SMALLEST remaining keyspace first.
  // This focuses workers on nearly-complete ranges, ensuring at least some ranges
  // finish before peers stop responding (instead of spreading work evenly across all 16).
  private val pendingTasks = mutable.PriorityQueue[AccountTask](remainingTasks: _*)(
    Ordering.by[AccountTask, BigInt](_.remainingKeyspace).reverse
  )
  private val activeTasks = mutable.Map[BigInt, (AccountTask, ActorRef, Peer)]() // requestId -> (task, worker, peer)
  private val completedTasks = mutable.ArrayBuffer[AccountTask]()

  // Worker pool
  private val workers = mutable.ArrayBuffer[ActorRef]()
  private val idleWorkers = mutable.LinkedHashSet.empty[ActorRef]

  /** Count in-flight requests for a given peer (pipelining support). */
  private def inFlightForPeer(peer: Peer): Int =
    activeTasks.values.count(_._3.id == peer.id)

  // Statistics
  private var accountsDownloaded: Long = 0
  private var bytesDownloaded: Long = 0
  private val startTime = System.currentTimeMillis()
  private var lastProgressLogAt: Long = 0 // accounts count at last periodic log
  private val ProgressLogInterval: Long = 100_000 // log every 100K accounts
  private val totalKeyspace: BigInt = BigInt(2).pow(256)
  // Cumulative keyspace consumed: incremented each time a task's `next` advances.
  // This avoids the jitter from snapshotting in-flight task positions.
  private var consumedKeyspace: BigInt = BigInt(0)

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
  implicit private object ByteStringFunnel extends Funnel[ByteString] {
    override def funnel(from: ByteString, into: PrimitiveSink): Unit =
      into.putBytes(from.toArray)
  }
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

  /** Number of active (non-stateless, non-cooling-down) snap-capable peers. Used to cap worker count — one worker per
    * peer avoids peer flooding.
    */
  private def activePeerCount: Int =
    knownAvailablePeers.count(p => !isPeerStateless(p) && !isPeerCoolingDown(p)).max(1)

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
    if (requested > 0 && received * 10 >= requested * 9 && requested < maxResponseBytes) {
      val next = (requested.toDouble * increaseFactor).toLong
      peerResponseBytesTarget.update(peer.id.value, BigInt(next).min(maxResponseBytes))
    }

  private def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = {
    val cur = responseBytesTargetFor(peer)
    val next = (cur.toDouble * decreaseFactor).toLong
    peerResponseBytesTarget.update(peer.id.value, BigInt(next).max(minResponseBytes))
    log.debug(
      s"Reducing account responseBytes target for peer ${peer.id.value}: $cur -> ${peerResponseBytesTarget(peer.id.value)} ($reason)"
    )
  }

  // Track consecutive timeouts per peer. When a peer hits the threshold, it's marked stateless.
  // This handles the case where ETC mainnet peers silently stop responding (timeout) when
  // their snap serve window expires, rather than returning empty responses with proofs.
  private val peerConsecutiveTimeouts = mutable.Map[String, Int]()
  private val consecutiveTimeoutThreshold = 3 // Mark stateless after 3 consecutive timeouts

  // Peer cooldown (best-effort): used for transient errors (timeouts, verification failures).
  private val peerCooldownUntilMs = mutable.Map[String, Long]()
  private val peerCooldownDefault = 30.seconds

  private def isPeerCoolingDown(peer: Peer): Boolean =
    peerCooldownUntilMs.get(peer.id.value).exists(_ > System.currentTimeMillis())

  private def recordPeerCooldown(peer: Peer, reason: String): Unit = {
    val until = System.currentTimeMillis() + peerCooldownDefault.toMillis
    peerCooldownUntilMs.put(peer.id.value, until)
    log.debug(s"Cooling down peer ${peer.id.value} for ${peerCooldownDefault.toSeconds}s: $reason")
  }

  // Pivot refresh backoff: prevents rapid-fire pivot refresh requests when all peers are stateless.
  // Exponential backoff from 60s to 5min.
  private var consecutiveUnproductiveRefreshes: Int = 0
  private var lastPivotRefreshTimeMs: Long = 0L
  private val minRefreshIntervalMs: Long = 60000L // 60s minimum between refreshes
  private val maxRefreshIntervalMs: Long = 300000L // 5min ceiling

  // Threshold-based trie flush: accumulate nodes in memory and only flush to RocksDB
  // when the threshold is reached. With pipelining, per-response flushing becomes a
  // bottleneck (50-200ms per flush × 5 concurrent responses = constant blocking).
  private var accountsSinceLastFlush: Long = 0

  // Internal message for async trie finalization result — carries the finalized root hash
  private case class TrieFlushComplete(result: Either[String, ByteString])

  // Deferred-write storage wrapper: makes updateNodesInStorage() a no-op during bulk insertion,
  // keeping all trie nodes in memory. This avoids the per-put collapse (RLP encode + Keccak-256 hash)
  // and database write that was causing insertion to degrade from ~300/s to ~20/s.
  // Nodes are flushed to RocksDB after each response batch via flushTrieToStorage().
  private val deferredStorage = new DeferredWriteMptStorage(mptStorage)

  // State trie for storing accounts.
  // In SNAP, we start with an empty local DB and rebuild the state trie from downloaded ranges.
  private var stateTrie: MerklePatriciaTrie[ByteString, Account] = {
    import com.chipprbots.ethereum.mpt.byteStringSerializer
    MerklePatriciaTrie[ByteString, Account](deferredStorage)
  }

  override def preStart(): Unit = {
    if (skippedTasks.nonEmpty) {
      log.info(
        s"AccountRangeCoordinator starting with $concurrency workers — " +
          s"skipping ${skippedTasks.size}/${allInitialTasks.size} ranges (already completed in previous attempt)"
      )
    } else {
      log.info(s"AccountRangeCoordinator starting with $concurrency workers")
    }
    // If all tasks were already completed, report completion immediately
    if (pendingTasks.isEmpty && activeTasks.isEmpty) {
      import context.dispatcher
      context.system.scheduler.scheduleOnce(100.millis, self, CheckCompletion)
    }
  }

  override def postStop(): Unit = {
    // Send final progress snapshot so controller can resume from saved positions on restart
    sendProgressSnapshot()
    // Close and delete temporary files.
    // Note: contractStorageFile is NOT deleted here — the controller reads it asynchronously
    // during storage sync (Bug 20 fix: streaming from file to avoid OOM). The controller
    // deletes it after streaming completes.
    try contractAccountsOut.close()
    catch { case _: Exception => }
    try contractStorageOut.close()
    catch { case _: Exception => }
    try uniqueCodeHashesOut.close()
    catch { case _: Exception => }
    try Files.deleteIfExists(contractAccountsFile)
    catch { case _: Exception => }
    // contractStorageFile intentionally NOT deleted — controller manages its lifecycle
    // uniqueCodeHashesFile intentionally NOT deleted — controller manages its lifecycle
    // (needed for accounts-complete recovery across process restarts)
    log.info(
      s"AccountRangeCoordinator stopped. Downloaded $accountsDownloaded accounts, identified $contractAccountsCount contracts ($uniqueCodeHashesCount unique codeHashes)"
    )
  }

  /** Collect current task positions and send to controller for resume across restarts. */
  private def sendProgressSnapshot(): Unit = {
    val allTasks = pendingTasks.iterator ++ activeTasks.values.map(_._1) ++ completedTasks
    val progress: Map[ByteString, ByteString] = allTasks.map(t => t.last -> t.next).toMap
    snapSyncController ! AccountRangeProgress(progress)
  }

  // Supervision strategy: Restart worker on failure
  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.minute) { case _: Exception =>
      log.warning("Worker failed, restarting")
      Restart
    }

  override def receive: Receive = {
    case StartAccountRangeSync(root) =>
      log.info(s"Starting account range sync for state root ${root.take(8).toHex}")
    // Tasks already initialized in constructor

    case AccountRangeResponseMsg(response) =>
      activeTasks.get(response.requestId) match {
        case None =>
          log.debug(s"Received AccountRange response for unknown or completed request ${response.requestId}")

        case Some((_, worker, _)) =>
          // Forward to the specific worker that owns this requestId so it can validate/complete the request.
          worker ! AccountRangeResponseMsg(response)
      }

    case PivotRefreshed(newStateRoot) =>
      log.info(s"Pivot refreshed: ${stateRoot.take(4).toHex} -> ${newStateRoot.take(4).toHex}")
      stateRoot = newStateRoot
      pivotWasRefreshed = true

      // Update all pending tasks to use the new root
      pendingTasks.foreach(_.rootHash = newStateRoot)
      // Active tasks will fail with root mismatch and get re-queued;
      // handleTaskFailed will re-enqueue them with the old root, but they'll be
      // dispatched with the new root on their next attempt.

      // Clear stateless tracking — peers can serve the new root
      statelessPeers.clear()
      pivotRefreshRequested = false

      // Clear per-peer adaptive state (new root = new response characteristics)
      peerResponseBytesTarget.clear()
      peerCooldownUntilMs.clear()
      peerConsecutiveTimeouts.clear()
      // Note: do NOT reset consecutiveUnproductiveRefreshes here.
      // Only reset when we receive real account data (proof the new root is servable).

      // Resume dispatching with the fresh root
      tryRedispatchPendingTasks()

    case PeerAvailable(peer) =>
      // Evict stale entry for same physical node (reconnection creates new PeerId).
      // Only clear stateless marking for peers that actually reconnected with a NEW ID.
      // If the same peer is re-reported (same id), preserve its stateless marking —
      // otherwise PeerAvailable from SNAPSyncController clears stateless every ~1s,
      // bypassing the backoff mechanism entirely (Bug 24).
      val evicted = knownAvailablePeers.filter(_.remoteAddress == peer.remoteAddress)
      knownAvailablePeers --= evicted
      evicted.foreach { p =>
        if (p.id != peer.id) {
          statelessPeers -= p.id
        }
      }
      knownAvailablePeers += peer
      if (isPeerStateless(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is stateless for current root")
      } else if (isPeerCoolingDown(peer)) {
        log.debug(s"Ignoring PeerAvailable(${peer.id.value}) - peer is cooling down")
      } else if (pendingTasks.isEmpty) {
        log.debug("No pending tasks")
      } else {
        // Pipeline multiple requests per peer (core-geth parity).
        // ByteCodeCoordinator already uses this pattern with maxInFlightPerPeer = 5.
        dispatchIfPossible(peer)
      }

    case UpdateMaxInFlightPerPeer(newLimit) =>
      log.info(s"AccountRange per-peer budget: $maxInFlightPerPeer -> $newLimit")
      maxInFlightPerPeer = newLimit
      if (newLimit > 0) tryRedispatchPendingTasks()

    case TaskComplete(requestId, result) =>
      handleTaskComplete(requestId, result)

    case TaskFailed(requestId, reason) =>
      handleTaskFailed(requestId, reason)

    case GetProgress =>
      val progress = calculateProgress()
      sender() ! progress

    case GetContractAccounts =>
      sender() ! ContractAccountsResponse(
        readContractFile(contractAccountsFile, contractAccountsOut, contractAccountsCount)
      )

    case GetContractStorageAccounts =>
      sender() ! ContractStorageAccountsResponse(
        readContractFile(contractStorageFile, contractStorageOut, contractStorageCount)
      )

    case GetUniqueCodeHashes =>
      sender() ! UniqueCodeHashesResponse(readUniqueCodeHashes())

    case GetStorageFileInfo =>
      contractStorageOut.flush()
      sender() ! StorageFileInfoResponse(contractStorageFile, contractStorageCount)

    case StoreAccountChunk(task, remaining, totalCount, storedSoFar, isTaskRangeComplete) =>
      handleStoreAccountChunk(task, remaining, totalCount, storedSoFar, isTaskRangeComplete)

    case CheckCompletion =>
      computeKeyspaceEstimate().foreach { est =>
        snapSyncController ! SNAPSyncController.ProgressAccountEstimate(est)
      }
      if (isComplete) {
        log.info("Account range sync complete!")

        // Signal controller IMMEDIATELY so storage+bytecode phases can start in parallel
        // with trie finalization. These phases don't need the finalized account trie —
        // they operate on their own state roots. This saves 50s-25min of serial blocking.
        snapSyncController ! SNAPSyncController.AccountRangeSyncComplete(uniqueCodeHashesCount)

        log.info(s"Starting async trie finalization for $accountsDownloaded accounts...")
        // Notify controller so progress monitor shows finalization status
        snapSyncController ! SNAPSyncController.ProgressAccountsFinalizingTrie
        // Switch to finalizing state so no message can touch the trie during flush
        context.become(finalizing)

        // Run the expensive flush (O(n*log(n)) trie collapse + RocksDB write) on a
        // separate thread to avoid blocking the Pekko dispatcher for 10+ minutes.
        val selfRef = self
        Future {
          blocking(finalizeTrie())
        }(scala.concurrent.ExecutionContext.global)
          .onComplete {
            case Success(result) => selfRef ! TrieFlushComplete(result)
            case Failure(ex)     => selfRef ! Status.Failure(ex)
          }(context.dispatcher)
      }
  }

  /** Receive state during async trie finalization. The in-memory trie is being collapsed and written to RocksDB on a
    * background thread. No message should touch stateTrie or deferredStorage during this phase.
    */
  private def finalizing: Receive = {
    case TrieFlushComplete(Right(finalizedRoot)) =>
      log.info(
        "State trie finalized successfully with root {}",
        finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString
      )
      snapSyncController ! SNAPSyncController.AccountTrieFinalized(finalizedRoot)
      snapSyncController ! SNAPSyncController.ProgressAccountsTrieFinalized
      context.stop(self)

    case TrieFlushComplete(Left(error)) =>
      log.error(s"Failed to finalize trie: $error")
      snapSyncController ! SNAPSyncController.ProgressAccountsTrieFinalized
      context.stop(self)

    case Status.Failure(ex) =>
      log.error(ex, s"Trie finalization failed with exception: ${ex.getMessage}")
      snapSyncController ! SNAPSyncController.ProgressAccountsTrieFinalized
      context.stop(self)

    case _: PeerAvailable =>
    // Ignore — no more tasks to dispatch during finalization

    case _: PivotRefreshed =>
      log.info("Ignoring PivotRefreshed during trie finalization")

    case GetProgress =>
      sender() ! calculateProgress()

    case GetContractAccounts =>
      sender() ! ContractAccountsResponse(
        readContractFile(contractAccountsFile, contractAccountsOut, contractAccountsCount)
      )

    case GetContractStorageAccounts =>
      sender() ! ContractStorageAccountsResponse(
        readContractFile(contractStorageFile, contractStorageOut, contractStorageCount)
      )

    case GetUniqueCodeHashes =>
      sender() ! UniqueCodeHashesResponse(readUniqueCodeHashes())

    case GetStorageFileInfo =>
      contractStorageOut.flush()
      sender() ! StorageFileInfoResponse(contractStorageFile, contractStorageCount)

    case CheckCompletion =>
    // Already finalizing, ignore
  }

  // Cap total workers to activePeerCount * maxInFlightPerPeer — enough to saturate all peers.
  private def maxWorkers: Int = concurrency * maxInFlightPerPeer

  private def createWorker(): ActorRef = {
    val worker = context.actorOf(
      AccountRangeWorker
        .props(
          coordinator = self,
          networkPeerManager = networkPeerManager,
          requestTracker = requestTracker
        )
        .withDispatcher("sync-dispatcher")
    )
    workers += worker
    idleWorkers += worker
    log.debug(s"Created worker ${worker.path.name}, total workers: ${workers.size}")
    worker
  }

  private def markWorkerIdle(worker: ActorRef): Unit =
    if (workers.contains(worker)) {
      idleWorkers += worker
    }

  /** Dispatch up to maxInFlightPerPeer tasks to the given peer (pipelining). Mirrors
    * ByteCodeCoordinator.dispatchIfPossible — the proven pattern for SNAP sync.
    */
  private def dispatchIfPossible(peer: Peer): Unit = {
    if (pendingTasks.isEmpty) return

    var inflight = inFlightForPeer(peer)
    while (pendingTasks.nonEmpty && inflight < maxInFlightPerPeer) {
      val workerOpt: Option[ActorRef] =
        idleWorkers.headOption.orElse {
          if (workers.size < maxWorkers) Some(createWorker()) else None
        }

      workerOpt match {
        case Some(worker) =>
          dispatchNextTaskToWorker(worker, peer)
          inflight += 1
        case None =>
          return
      }
    }
  }

  private def dispatchNextTaskToWorker(worker: ActorRef, peer: Peer): Unit = {
    if (pendingTasks.isEmpty) {
      return
    }

    // Mark worker busy
    idleWorkers -= worker

    val task = pendingTasks.dequeue()
    task.pending = true

    val requestId = requestTracker.generateRequestId()
    activeTasks.put(requestId, (task, worker, peer))
    val responseBytes = responseBytesTargetFor(peer)

    worker ! FetchAccountRange(task, peer, requestId, responseBytes)
  }

  // How many accounts to insert per chunk before yielding to the actor mailbox.
  // With DeferredWriteMptStorage, puts are purely in-memory (~1-10μs each), so we can
  // process much larger chunks. 2000 accounts takes ~2-20ms in-memory.
  private val storeChunkSize = 2000

  // Accounts are inserted in-memory via DeferredWriteMptStorage, then flushed to RocksDB
  // after each response batch (~32K accounts). This bounds memory to ~one batch (~13MB)
  // instead of accumulating all accounts in the trie (~420 bytes/account, OOM at ~9.5M/4GB).
  // Each flush collapses the current in-memory nodes and rebuilds from persisted root.

  private def handleTaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, Account)], Seq[ByteString])]
  ): Unit =
    activeTasks.remove(requestId).foreach { case (task, worker, peer) =>
      markWorkerIdle(worker)
      result match {
        case Right((accountCount, accounts, _)) =>
          log.info(
            s"Task completed successfully: $accountCount accounts (responseBytes=${responseBytesTargetFor(peer)})"
          )

          // Adjust adaptive byte budget — estimate received bytes from account count
          val estimatedBytes = BigInt(accountCount * 100) // ~100 bytes per account (hash + RLP)
          adjustResponseBytesOnSuccess(peer, responseBytesTargetFor(peer), estimatedBytes)

          // Reset consecutive timeout counter — peer is responsive
          peerConsecutiveTimeouts.remove(peer.id.value)

          // Reset pivot refresh backoff on receiving real account data
          if (accountCount > 0) {
            consecutiveUnproductiveRefreshes = 0
          }

          // Identify contract accounts
          identifyContractAccounts(accounts)

          // Update task progress before starting async storage.
          // This sets task.next so re-queuing (if needed) uses the correct start.
          val isTaskDone = updateTaskProgress(task, accounts)
          task.pending = false

          // Update statistics
          val accountBytes = accounts.map { case (hash, _) =>
            hash.size + 32 // Rough estimate
          }.sum
          bytesDownloaded += accountBytes

          // Start chunked async storage - this yields back to the actor mailbox between chunks
          // so the coordinator can still process PeerAvailable, AccountRangeResponseMsg, etc.
          self ! StoreAccountChunk(task, accounts, accountCount, storedSoFar = 0, isTaskRangeComplete = isTaskDone)

        case Left(error) =>
          log.warning(s"Task completed with error: $error")
          // Re-queue task for retry
          task.pending = false
          pendingTasks.enqueue(task)
      }
    }

  private def updateTaskProgress(task: AccountTask, accounts: Seq[(ByteString, Account)]): Boolean = {
    // If the peer returns no accounts for this segment, we assume the remainder of the interval is empty.
    if (accounts.isEmpty) {
      consumedKeyspace += task.remainingKeyspace
      return true
    }

    val lastHash = accounts.last._1
    if (isMaxHash(lastHash)) {
      // Cannot advance beyond 0xFF..; this must be the end.
      consumedKeyspace += task.remainingKeyspace
      return true
    }

    val nextStart = incrementHash32(lastHash)
    // Track keyspace consumed: distance from old next to new next
    val oldNext = BigInt(1, task.next.toArray.padTo(32, 0.toByte))
    val newNext = BigInt(1, nextStart.toArray.padTo(32, 0.toByte))
    val advanced = (newNext - oldNext).max(BigInt(0))
    consumedKeyspace += advanced
    task.next = nextStart

    // If this task has no upper bound, keep going until peer returns empty.
    if (task.last.isEmpty) {
      false
    } else {
      // Treat `last` as an exclusive upper bound.
      compareUnsigned32(nextStart, task.last) >= 0
    }
  }

  private def compareUnsigned32(a: ByteString, b: ByteString): Int = {
    // Empty is treated as unbounded; callers should handle this before comparing.
    val aa = a.toArray
    val bb = b.toArray
    val maxLen = math.max(aa.length, bb.length)
    val ap = if (aa.length == maxLen) aa else Array.fill(maxLen - aa.length)(0.toByte) ++ aa
    val bp = if (bb.length == maxLen) bb else Array.fill(maxLen - bb.length)(0.toByte) ++ bb
    var i = 0
    while (i < maxLen) {
      val ai = ap(i) & 0xff
      val bi = bp(i) & 0xff
      if (ai != bi) return ai - bi
      i += 1
    }
    0
  }

  private def incrementHash32(hash: ByteString): ByteString = {
    require(hash.length == 32, s"Expected 32-byte hash, got ${hash.length}")
    val bytes = hash.toArray
    var i = bytes.length - 1
    var carry = 1
    while (i >= 0 && carry != 0) {
      val sum = (bytes(i) & 0xff) + carry
      bytes(i) = (sum & 0xff).toByte
      carry = if (sum > 0xff) 1 else 0
      i -= 1
    }
    ByteString(bytes)
  }

  private def isMaxHash(hash: ByteString): Boolean =
    hash.length == 32 && hash.forall(b => (b & 0xff) == 0xff)

  private def handleTaskFailed(requestId: BigInt, reason: String): Unit = {
    activeTasks.remove(requestId).foreach { case (task, worker, peer) =>
      markWorkerIdle(worker)
      // Only mark peer stateless if the task was using the CURRENT root.
      // After pivot refresh, in-flight requests with the OLD root will fail
      // with "Missing proof" — but this doesn't mean the peer can't serve the NEW root.
      if (task.rootHash == stateRoot) {
        markPeerStateless(peer, reason)
      } else {
        log.info(
          s"Ignoring failure from stale-root request " +
            s"(task root ${task.rootHash.take(4).toHex} != current ${stateRoot.take(4).toHex})"
        )
      }
      log.warning(s"Task failed: $reason")
      task.pending = false
      task.rootHash = stateRoot
      pendingTasks.enqueue(task)

      // Apply cooldown and reduce byte budget for failing peers (unless stateless-marked,
      // which already blocks them from dispatch)
      if (!reason.contains("Missing proof for empty account range")) {
        recordPeerCooldown(peer, reason)
        adjustResponseBytesOnFailure(peer, reason)
      }
    }
    // Re-dispatch re-queued tasks to any known available peer that isn't stateless.
    // Without this, tasks sit in the queue until the next PeerAvailable message arrives.
    tryRedispatchPendingTasks()
  }

  private def tryRedispatchPendingTasks(): Unit = {
    if (pendingTasks.isEmpty) return
    val eligiblePeers = knownAvailablePeers
      .filterNot(isPeerStateless)
      .filterNot(isPeerCoolingDown)
      .toList
    if (eligiblePeers.isEmpty) return

    for (peer <- eligiblePeers if pendingTasks.nonEmpty)
      dispatchIfPossible(peer)
  }

  /** Handle a chunk of account storage, inserting a batch into the in-memory trie and yielding back to the actor
    * mailbox between chunks. With DeferredWriteMptStorage, puts are purely in-memory (no collapse, no encoding, no
    * hashing, no DB writes). The trie is flushed to RocksDB in a single batch when all chunks for a response are done.
    */
  private def handleStoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ): Unit = {
    val (chunk, rest) = remaining.splitAt(storeChunkSize)

    try {
      var currentTrie = stateTrie
      chunk.foreach { case (accountHash, account) =>
        currentTrie = currentTrie.put(accountHash, account)
      }
      stateTrie = currentTrie
      // No persist per chunk — DeferredWriteMptStorage buffers everything in memory

      val newStored = storedSoFar + chunk.size
      // Report incremental progress so the stagnation watchdog sees activity
      accountsDownloaded += chunk.size
      snapSyncController ! SNAPSyncController.ProgressAccountsSynced(chunk.size.toLong)

      // Periodic progress log (every 100K accounts) to show download rate without per-chunk noise
      if (accountsDownloaded - lastProgressLogAt >= ProgressLogInterval) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if (elapsed > 0) (accountsDownloaded / elapsed).toLong else 0L
        val pct = (consumedKeyspace * 10000 / totalKeyspace).toDouble / 100.0
        log.info(
          s"Account download progress: $accountsDownloaded accounts (${"%.1f".format(pct)}% keyspace) " +
            s"(${completedTasks.size}/$concurrency ranges done, " +
            s"${pendingTasks.size} pending, ${activeTasks.size} active, " +
            s"${workers.size} workers/${activePeerCount} peers, " +
            s"${rate} accounts/sec)"
        )
        lastProgressLogAt = accountsDownloaded
      }

      if (rest.nonEmpty) {
        log.debug(s"Stored chunk: $newStored/$totalCount accounts (${rest.size} remaining)")
        // Yield to actor mailbox - other messages (PeerAvailable, responses) process before next chunk
        self ! StoreAccountChunk(task, rest, totalCount, newStored, isTaskRangeComplete)
      } else {
        // Threshold-based flush: only flush to RocksDB when accumulated nodes exceed threshold.
        // With pipelining, per-response flushing (50-200ms each) blocks the coordinator
        // and becomes the throughput bottleneck. Threshold-based flushing amortizes the cost.
        accountsSinceLastFlush += totalCount
        if (accountsSinceLastFlush >= trieFlushThreshold) {
          flushTrieToStorage()
          accountsSinceLastFlush = 0
        }
        log.debug(
          s"Stored all $totalCount accounts in-memory ($accountsDownloaded total, ${accountsSinceLastFlush} since last flush)"
        )

        if (isTaskRangeComplete) {
          task.done = true
          completedTasks += task
          log.info(
            s"Account range COMPLETE: ${task.rangeString} " +
              s"(${completedTasks.size}/$concurrency ranges done, $accountsDownloaded accounts total)"
          )
          // Send progress snapshot so controller can resume from saved positions
          sendProgressSnapshot()
        } else {
          // Need more requests for the same interval; re-queue with updated `next`.
          pendingTasks.enqueue(task)
        }

        // Check if all tasks are complete
        self ! CheckCompletion
      }
    } catch {
      case e: Exception =>
        log.error(e, s"Failed to store account chunk: ${e.getMessage}")
        // Re-queue task for retry
        task.pending = false
        task.done = false
        pendingTasks.enqueue(task)
    }
  }

  /** Flush all in-memory trie nodes to RocksDB in a single batch. This collapses the entire in-memory trie (RLP-encode
    * + Keccak-256 hash all nodes), then writes everything to RocksDB via one WriteBatch. After flush, the trie is
    * rebuilt from the persisted root hash so old in-memory nodes can be garbage collected.
    */
  private def flushTrieToStorage(): Unit =
    deferredStorage.flush().foreach { rootHash =>
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      stateTrie = MerklePatriciaTrie[ByteString, Account](rootHash, deferredStorage)
      log.info(s"Flushed trie to storage, root=${rootHash.take(8).map("%02x".format(_)).mkString}...")
    }

  /** Identify contract accounts (those with non-empty code hash)
    *
    * @param accounts
    *   Accounts to scan for contracts
    */
  private def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit = {
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val newCodeHashes = mutable.ArrayBuffer.empty[ByteString]
    val newStorageTasks = mutable.ArrayBuffer.empty[StorageTask]
    var count = 0

    accounts.foreach { case (accountHash, account) =>
      if (account.codeHash != Account.EmptyCodeHash) {
        // Write 32-byte accountHash + 32-byte codeHash to bytecode file (crash recovery)
        contractAccountsOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
        contractAccountsOut.write(account.codeHash.toArray.padTo(32, 0.toByte), 0, 32)
        // Write 32-byte accountHash + 32-byte storageRoot to storage file (crash recovery)
        contractStorageOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
        contractStorageOut.write(account.storageRoot.toArray.padTo(32, 0.toByte), 0, 32)
        count += 1

        // Track unique codeHashes via Bloom filter + temp file (~4MB RAM vs 200MB HashSet).
        // The Bloom filter has 0.01% FPR — ~200 of 2M hashes may be missed but the
        // recovery scan (Bug 20 hardening) catches any gaps.
        if (!codeHashBloom.mightContain(account.codeHash)) {
          codeHashBloom.put(account.codeHash)
          uniqueCodeHashesOut.write(account.codeHash.toArray.padTo(32, 0.toByte), 0, 32)
          uniqueCodeHashesCount += 1
          newCodeHashes += account.codeHash
        }

        // Collect storage task for inline dispatch (skip contracts with empty storage)
        if (account.storageRoot.nonEmpty && account.storageRoot != emptyRoot) {
          newStorageTasks += StorageTask.createStorageTask(accountHash, account.storageRoot)
        }
      }
    }

    if (count > 0) {
      contractAccountsCount += count
      contractStorageCount += count
      // Flush file streams (crash recovery path)
      contractAccountsOut.flush()
      contractStorageOut.flush()
      uniqueCodeHashesOut.flush()
      log.info(
        s"Identified $count contract accounts (total: $contractAccountsCount, unique codeHashes: $uniqueCodeHashesCount)"
      )
    }

    // Geth-aligned: dispatch contract data inline to controller → bytecode/storage coordinators.
    // This eliminates the 6+ minute gap between account completion and first storage/bytecode request.
    if (newCodeHashes.nonEmpty || newStorageTasks.nonEmpty) {
      snapSyncController ! SNAPSyncController.IncrementalContractData(
        newCodeHashes.toSeq,
        newStorageTasks.toSeq
      )
    }
  }

  /** Finalize the trie and ensure all nodes including the root are persisted to storage.
    *
    * @return
    *   Either error message or success
    */
  private def finalizeTrie(): Either[String, ByteString] =
    try {
      log.info("Finalizing state trie...")

      // Flush any remaining deferred writes to RocksDB
      flushTrieToStorage()

      val currentRootHash = ByteString(stateTrie.getRootHash)
      log.info(s"Final state root: ${currentRootHash.take(8).toArray.map("%02x".format(_)).mkString}")

      // After pivot refresh(es), root mismatch is expected — the healing phase
      // will reconcile. Only fail on root mismatch if NO pivot refresh occurred.
      if (
        !pivotWasRefreshed &&
        stateRoot.nonEmpty &&
        stateRoot != ByteString(MerklePatriciaTrie.EmptyRootHash) &&
        currentRootHash != stateRoot
      ) {
        val expected = stateRoot.take(8).toArray.map("%02x".format(_)).mkString
        val actual = currentRootHash.take(8).toArray.map("%02x".format(_)).mkString
        Left(s"Root mismatch: expected=$expected..., actual=$actual...")
      } else {
        if (pivotWasRefreshed && currentRootHash != stateRoot) {
          log.info("Root mismatch expected after pivot refresh - healing phase will reconcile")
        }
        log.info("State trie finalization complete")
        Right(currentRootHash)
      }
    } catch {
      case e: Exception =>
        log.error(e, s"Failed to finalize trie: ${e.getMessage}")
        Left(s"Trie finalization error: ${e.getMessage}")
    }

  /** Get the current state root hash from the trie
    *
    * @return
    *   Current state root hash
    */
  def getStateRoot: ByteString =
    ByteString(stateTrie.getRootHash)

  /** Read all contract account entries from a temporary file. Each entry is 64 bytes: 32-byte key + 32-byte value.
    * Flushes the output stream first to ensure all data is written.
    */
  private def readContractFile(
      filePath: Path,
      out: BufferedOutputStream,
      count: Long
  ): Seq[(ByteString, ByteString)] = {
    out.flush()
    if (count == 0) return Seq.empty
    val raf = new RandomAccessFile(filePath.toFile, "r")
    try {
      val result = new mutable.ArrayBuffer[(ByteString, ByteString)](count.toInt)
      val buf = new Array[Byte](ContractEntrySize)
      var i = 0L
      while (i < count) {
        raf.readFully(buf)
        val key = ByteString(java.util.Arrays.copyOfRange(buf, 0, 32))
        val value = ByteString(java.util.Arrays.copyOfRange(buf, 32, 64))
        result += ((key, value))
        i += 1
      }
      result.toSeq
    } finally raf.close()
  }

  /** Read unique codeHashes from the Bloom-filtered temp file. Each entry is 32 bytes. File size is ~64MB for ~2M
    * unique hashes (vs 4.7GB for 73.5M raw entries).
    */
  private def readUniqueCodeHashes(): Seq[ByteString] = {
    uniqueCodeHashesOut.flush()
    if (uniqueCodeHashesCount == 0) return Seq.empty
    val raf = new RandomAccessFile(uniqueCodeHashesFile.toFile, "r")
    try {
      val result = new mutable.ArrayBuffer[ByteString](uniqueCodeHashesCount.toInt)
      val buf = new Array[Byte](32)
      var i = 0L
      while (i < uniqueCodeHashesCount) {
        raf.readFully(buf)
        result += ByteString(buf.clone())
        i += 1
      }
      result.toSeq
    } finally raf.close()
  }

  private def calculateProgress(): AccountRangeStats = {
    val total = completedTasks.size + activeTasks.size + pendingTasks.size
    val progress = if (total == 0) 1.0 else completedTasks.size.toDouble / total
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
  }

  private def isComplete: Boolean =
    pendingTasks.isEmpty && activeTasks.isEmpty

  /** Estimate total accounts from keyspace coverage. Uses completed tasks' ranges to compute keyspace density (accounts
    * per unit of keyspace), then extrapolates to the full 2^256 space. Only considers tasks that have actually been
    * explored, avoiding inflation from un-dispatched chunks.
    *
    * Uses BigInt arithmetic throughout to avoid precision loss — 2^256 is far beyond Double's 15-17 significant digits,
    * so `covered.toDouble / keyspaceSize.toDouble` always produces 0.0.
    */
  private def computeKeyspaceEstimate(): Option[Long] = {
    if (accountsDownloaded < 10000) return None // too early for reliable estimate

    val keyspaceSize = BigInt(2).pow(256)
    val nonCompleteTasks = pendingTasks.toSeq ++ activeTasks.values.map(_._1)
    val remaining = if (nonCompleteTasks.isEmpty) {
      BigInt(0)
    } else {
      nonCompleteTasks.foldLeft(BigInt(0)) { case (sum, task) =>
        val taskEnd = BigInt(1, task.last.toArray)
        val taskPos = BigInt(1, task.next.toArray)
        sum + (taskEnd - taskPos).max(0)
      }
    }

    val covered = keyspaceSize - remaining
    if (covered <= 0) return None

    // Use BigInt arithmetic: estimated = accountsDownloaded * keyspaceSize / covered
    // This avoids Double precision loss when dividing by 2^256.
    val estimatedBig = BigInt(accountsDownloaded) * keyspaceSize / covered
    // Sanity: reject absurd values (overflow, < downloaded, > 2 billion)
    // ETC mainnet has ~600M addresses per blockscout; cap at 2B for safety margin
    if (estimatedBig <= accountsDownloaded || estimatedBig > BigInt(2000000000L)) return None

    Some(estimatedBig.toLong)
  }
}

object AccountRangeCoordinator {
  def props(
      stateRoot: ByteString,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      concurrency: Int,
      snapSyncController: ActorRef,
      resumeProgress: Map[ByteString, ByteString] = Map.empty,
      initialMaxInFlightPerPeer: Int = 5,
      trieFlushThreshold: Int = 50000,
      initialResponseBytes: Int = 524288,
      minResponseBytes: Int = 102400
  ): Props =
    Props(
      new AccountRangeCoordinator(
        initialStateRoot = stateRoot,
        networkPeerManager,
        requestTracker,
        mptStorage,
        concurrency,
        snapSyncController,
        resumeProgress,
        initialMaxInFlightPerPeer,
        trieFlushThreshold,
        initialResponseBytes,
        minResponseBytes
      )
    )
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
