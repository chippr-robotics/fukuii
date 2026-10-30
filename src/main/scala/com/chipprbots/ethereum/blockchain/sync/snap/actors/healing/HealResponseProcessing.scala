package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HeldNodeSet
import com.chipprbots.ethereum.blockchain.sync.snap.heal.PathPresence
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes

/** The coordinator state `HealResponseProcessing` reads and writes (spec 016 C-H5, research.md R14b). The core's
  * behaviour arms (the lifecycle arms' reseeds and re-pegs, the completion gate, the stagnation pulse), the other
  * modules, or rule (d) keep them in the core, so the Impl keeps them as fields. The response processing mutates the
  * task queue, the live heal requests (spec 022 D2), the in-flight map, the raw-node buffer, the attempt counters, the
  * spec 003 scoped-verification accumulator (`healedPathsThisRound`, `healedPathsRoot`, `healedPathsOverflowed`), the
  * stateless set and its strikes and the staged spec 005 subtree records; it writes the progress counters
  * (`totalNodesHealed`, `crossRootHealCount`, `totalBytesReceived`, `completedTaskCount`), the pivot-refresh latch and
  * its timestamp, the give-up escalation latch, and `lastHealedAtMs`; it reads the walk and serve roots and
  * `knownAvailablePeers` (and evicts from it). `prunedEnabled` is an immutable val rule (d) keeps in the core. A
  * give-up clears `verificationPassComplete`.
  */
private[actors] trait HealResponseProcessingState:
  def stateRoot: ByteString
  def serveRoot: ByteString
  def pendingTasks: HealTaskQueue[HealingEntry]
  def healRequests: HealRequestBook
  def heldNodes: HeldNodeSet
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def rawNodeBuffer: FlushBuffer
  var totalNodesHealed: Int
  var crossRootHealCount: Long
  def healAttempts: mutable.Map[ByteString, Int]
  def healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry]
  var healedPathsRoot: ByteString
  var healedPathsOverflowed: Boolean
  var totalBytesReceived: Long
  var completedTaskCount: Int
  def statelessPeers: mutable.Set[String]
  def emptyResponseStrikes: mutable.Map[String, Int]
  def knownAvailablePeers: mutable.Set[Peer]
  var pivotRefreshRequested: Boolean
  var pivotRefreshRequestedAt: Long
  var giveUpEscalated: Boolean
  var lastHealedAtMs: Long
  def prunedEnabled: Boolean
  def pendingSubtreeRecords: mutable.Set[ByteString]
  var verificationPassComplete: Boolean

/** What `reverifyCurrentRoot` calls: `isComplete`, nothing pending and nothing in flight. `HealResponseProcessing`
  * provides it (spec 016 C-H5).
  */
private[actors] trait HealCompletionApi:
  def isComplete: Boolean

/** TrieNodeHealingCoordinator's heal response processing (spec 016 C-H5, coordinator-characterization.md §3.4): a
  * served `TrieNodes` response matched to its request by node hash (never by position), each node accepted only when
  * its keccak256 equals a requested task hash (the spec 004 consensus-safety invariant), buffered for the Hash-scheme
  * flush or written by path under the Path scheme (at every position waiting on it, spec 022 D2, with the positions the
  * write leaves dangling deleted in the same batch, FR-008), the spec 003 healed-paths capture, the re-queue of
  * unsatisfied tasks, the throttle and `responseBytes` feedback, the stateless-peer strikes and the all-peers-stateless
  * pivot refresh; the inline child discovery that makes healing self-feeding (with the account-leaf storage-root seed,
  * the spec 005 subtree-complete seed and the healed-code report), the enqueue (`queueNodes`), the presence check
  * (`isNodeInStorage`), `isComplete` and the scoped-verification reset (`clearHealedPathsSet`). The coordinator core
  * keeps the behaviour arms that call it (`healResponseArms`, the lifecycle arms, the completion gate).
  */
private[actors] trait HealResponseProcessing extends HealCompletionApi:
  this: HealResponseProcessingState & TrieNodeHealingEnv & FrontierGaugesApi & FrontierWriteApi & HealDispatchApi &
    PathVerifiedRlpApi & PathPresenceApi =>

  // codeHashes of account leaves healed in the response being processed whose bytecode is not in `stateIngest.bytecode`.
  // Filled by discoverMissingChildren, drained and sent to the controller once per response.
  private lazy val healedCodeHashesThisResponse: mutable.LinkedHashSet[ByteString] = mutable.LinkedHashSet.empty

  // Inline child discovery counter (Besu-aligned scheduler approach)
  private var childrenDiscoveredTotal: Long = 0

  // spec 022 review forge F2: per node hash, how many fills in a row failed to read its children from storage. A node
  // whose children cannot be read is fetched again, up to `MaxConsecutiveReadFailures` fills, then given up on. The
  // entry goes when a fill reads them; it survives a re-peg and the orphan guard, so a stored child that never reads
  // (corrupt bytes under Hash survive a re-peg) is not fetched in a tight loop again.
  private lazy val readFailures: mutable.HashMap[ByteString, Int] = mutable.HashMap.empty

  /** Reset the scoped-verification healed-paths set (spec 003 C1). Called at the round-invalidation / round-close sites
    * — differing-root HealingPivotRefreshed, HealingForceComplete, and after a verified StateHealingComplete — NOT on a
    * same-root refresh (that round is still valid). Idempotent.
    */
  def clearHealedPathsSet(): Unit =
    healedPathsThisRound.clear()
    healedPathsRoot = ByteString.empty
    healedPathsOverflowed = false

  private lazy val EmptyResponseStrikeThreshold: Int = 5

  /** The heal request `entry` stands for (spec 022 D2): its trie position under the Path scheme, its node hash under
    * the Hash scheme.
    */
  def healRequestFor(entry: HealingEntry): HealRequest =
    storageScheme match
      case StorageScheme.Path => HealRequest.atPosition(entry.pathset, entry.hash)
      case StorageScheme.Hash => HealRequest.ofNode(entry.pathset, entry.hash)

  /** Record `entry` as a heal request with no parent (a root, a re-seed, a walk or controller entry) and queue a fetch
    * when its hash has none live. True when a fetch was queued. A request already live is not recorded twice; a Path
    * position whose hash is already being fetched joins that fetch (an alias, filled when the node arrives).
    */
  def enqueueRequest(entry: HealingEntry): Boolean =
    val request = healRequestFor(entry)
    // A node already held (received, waiting on its children) is not fetched again: its children are being fetched.
    if heldNodes.contains(HeldNodeSet.idOf(request)) then false
    // Hash: a node in the flush buffer was committed with its whole subtree, only not flushed yet (forge N2).
    else if storageScheme == StorageScheme.Hash && rawNodeBuffer.contains(request.hash) then false
    else if healRequests.add(request).needsFetch then
      pendingTasks += entry
      true
    else false

  def queueNodes(pathsAndHashes: Seq[(Seq[ByteString], ByteString)]): Unit =
    val entries = pathsAndHashes.flatMap { case (pathset, hash) =>
      val entry = HealingEntry(pathset = pathset, hash = hash)
      if enqueueRequest(entry) then Some(entry) else None
    }
    val deduped = pathsAndHashes.size - entries.size
    persistFrontier(entries) // Layer 2: mirror new frontier entries to the persisted CF
    val dedupStr = if deduped > 0 then s" ($deduped already requested or joined a fetch)" else ""
    log.info(s"Queued ${entries.size} nodes for healing$dedupStr. Total pending: ${pendingTasks.size}")
    emitHealingFrontierGauges()

  def handleResponse(response: TrieNodes): Unit =
    val requestId = response.requestId
    val nodes = response.nodes

    log.debug(s"Received TrieNodes response: reqId=$requestId, nodes=${nodes.size}")

    activeRequests.get(requestId) match
      case None =>
        log.warn(s"No active healing request found for requestId=$requestId")
      case Some(activeReq) =>
        processActiveResponse(requestId, response, activeReq)

  private def processActiveResponse(requestId: BigInt, response: TrieNodes, activeReq: ActiveRequest): Unit =
    val nodes = response.nodes
    val tasksForRequest = activeReq.tasks
    val peer = activeReq.peer
    val requestedBytes = activeReq.requestedBytes

    var healedCount = 0
    // Nodes the peer delivered that filled a request, healed or not (a fill whose children could not be read is the
    // node store's fault, not the peer's): what clears its strikes.
    var deliveredCount = 0
    var receivedBytes: Long = 0

    // Hash-based matching — NOT positional. Servers (core-geth handler.go:546-547)
    // omit entries for storage pathsets whose account is missing, returning sparse
    // responses. Positional zip would align later nodes against the wrong tasks.
    val keccak = new org.bouncycastle.jcajce.provider.digest.Keccak.Digest256()
    val requestedHashes: Set[ByteString] = tasksForRequest.iterator.map(_.hash).toSet
    val healedHashes = mutable.Set[ByteString]()

    // spec 022 review W2: a dispatched task is the fetch of live requests, and only those are filled. A task no live
    // request waits on is a bookkeeping fault: nothing vouches for the positions it would write, so it is neither
    // filled nor queued again.
    // A fetch whose last request was replaced by a conflicting one (review W1, HealRequestBook) is expected to come back
    // empty-handed: not an error.
    val (retiredTasks, unvouched) =
      tasksForRequest
        .filterNot(task => healRequests.isFetching(task.hash))
        .partition(t => healRequests.isRetired(t.hash))
    if retiredTasks.nonEmpty then
      log.debug(s"[HEAL] ${retiredTasks.size} dispatched heal task(s) retired by a conflicting request — dropped")
    // beacon N3: warn, not error. A hash retired by a conflict and then waited on again can come back on a duplicate
    // carrier; that is stale bookkeeping, not a fault.
    if unvouched.nonEmpty then
      log.warn(
        s"[HEAL] ${unvouched.size} dispatched heal task(s) that no live request waits on, first " +
          s"${Hex.toHexString(unvouched.head.hash.take(4).toArray)} — not filled, not queued again"
      )
    // beacon N4: a response to a request whose every task was retired or unvouched cannot fill anything; an empty
    // answer to it is no fault of the peer.
    val nothingVouched = retiredTasks.size + unvouched.size == tasksForRequest.size

    nodes.foreach { nodeData =>
      if nodeData.nonEmpty then
        keccak.reset()
        val nodeHash = ByteString(keccak.digest(nodeData.toArray))
        // ┌─ CONSENSUS-SAFETY INVARIANT (spec 004 T014/C4/FR-004 — DO NOT WEAKEN OR BYPASS) ─────────────────────┐
        // │ A node returned by ANY serve root is accepted IFF its keccak256 equals a requested task hash (the     │
        // │ walk root's expectation). GetTrieNodes is path-addressed, so a serve root may resolve a path to a     │
        // │ DIFFERENT-content node; that node fails this hash match and is dropped (never stored, never counted   │
        // │ as healed). This is the single guardrail that makes decoupled (cross-root) fetching safe — it holds   │
        // │ identically on the coupled and decoupled paths. No decoupled branch may skip it.                      │
        // └──────────────────────────────────────────────────────────────────────────────────────────────────────┘
        if requestedHashes.contains(nodeHash) then
          receivedBytes += nodeData.length
          totalBytesReceived += nodeData.length
          // A second copy of a node already taken from this response (one per requested path) fills nothing new.
          if healedHashes.add(nodeHash) then
            // spec 022 D2: the node fills every request waiting on its hash — under Path every position that needs it
            // (an alias: identical subtrees under two accounts), under Hash the one request for the node — and only
            // those (review W2): each expects exactly this keccak.
            val filled = healRequests.waitingOn(nodeHash)
            val failuresBefore = readFailures.getOrElse(nodeHash, 0)
            val giveUp = failuresBefore + 1 >= TrieNodeHealingCoordinator.MaxConsecutiveReadFailures
            val readFailed = filled.count(request => !fillRequest(request, nodeData, giveUp))
            if readFailed > 0 then readFailures(nodeHash) = failuresBefore + 1 else readFailures.remove(nodeHash): Unit
            // spec 022 review hunter W1-r2, forge W1: every request that waited on the hash was just filled, so one still
            // waiting was put back after a failed read. It needs a fetch of its own: the one that came back is spent, and
            // a Path alias put back while another still waited only joined it. One fetch per hash, whatever the count.
            healRequests.firstWaiter(nodeHash).foreach { waiter =>
              val entry = HealingEntry(waiter.pathset, nodeHash)
              pendingTasks += entry
              persistFrontier(Seq(entry))
            }
            // forge F2, hunter N1: a fill whose children could not be read is not healed (it is fetched again or given
            // up on); counting it would hide a node fetched again and again from the stagnation checks.
            val healed = filled.size - readFailed
            deliveredCount += filled.size
            healedCount += healed
            totalNodesHealed += healed
            // spec 004 T019/FR-010: count a heal sourced from a serve root that differs from the walk root.
            // Observation-only; never affects completion. (Equal roots ⇒ coupled-equivalent, not counted.)
            if decoupledHealServeRoot && serveRoot != stateRoot then crossRootHealCount += healed
            // T015 / FR-006: this task is satisfied, or given up on (hunter-r3 N2) — drop its attempt counter (bounded by
            // the live requests). A fill put back keeps it: that request is still live.
            if readFailed == 0 || giveUp then healAttempts.remove(nodeHash): Unit
        else
          log.debug(
            s"Healing response node not in request set (unexpected): ${Hex.toHexString(nodeHash.take(4).toArray)}"
          )
    }

    // spec 022 D3: write what this response completed — every node whose subtree is now present, with every ancestor
    // it completes, and the stale positions discovery found — in one atomic write.
    commitHeld()

    // A healed account leaf brings an account that account-range sync never saw, so nothing else will ever fetch its
    // bytecode (the code-hash list is built from account-range responses). Hand the missing ones to the controller,
    // which fetches them before it finalises SNAP. Sent BEFORE any completion signal this actor emits afterwards
    // (same sender, ordered), so the controller always knows of them by the time it is told healing is done.
    flushHealedCodeHashes()

    // Re-queue the fetches this response left unsatisfied (server skipped or didn't have them), one per hash. Their
    // requests stay live in `healRequests` (live = pending or in flight), so nothing else queues a second fetch of them
    // meanwhile (BUG-H1). The fetch goes out again from the path of the first request still waiting on the hash, which
    // in the normal case is the task's own; a hash nothing waits on any more is not queued again (review W2).
    // beacon N2 and Q1: the next waiter after the carrier just used, O(1) when one request waits on the hash, so a fetch
    // put back goes out from the next alias position.
    tasksForRequest.distinctBy(_.hash).foreach { task =>
      val hash = task.hash
      if !healedHashes.contains(hash) then
        healRequests.nextWaiter(hash, task.pathset).foreach { live =>
          pendingTasks += HealingEntry(live.pathset, hash)
          // spec 004 T015/FR-006: an unsatisfied task (server skipped it / content-mismatch drop) bumps its
          // attempt counter and may surface if it stays unservable across the bounded threshold WITHOUT a
          // serve-root advance (which clears the map). This NEVER force-completes — see noteUnservableAttempt.
          noteUnservableAttempt(hash)
        }
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId, nodes.size.max(1))

    // Update healing throttle (geth msgrate alignment)
    val elapsedMs = System.currentTimeMillis() - activeReq.sentAtMs
    updateHealThrottle(healedCount, elapsedMs)

    // Adaptive byte budget + stateless tracking
    if deliveredCount > 0 then
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(receivedBytes))
      // Successful response — clear stateless marking + strikes and reset stagnation timer.
      // Any forward progress from this peer wipes its prior strikes (mirrors recordPeerSuccess).
      statelessPeers -= peer.id.value
      emptyResponseStrikes.remove(peer.id.value)
      if healedCount > 0 then lastHealedAtMs = System.currentTimeMillis()
    else if nothingVouched then
      log.debug(s"[HEAL] Empty response from ${peer.id.value} to a request with nothing left to fill — no strike")
    else
      adjustResponseBytesOnFailure(peer, "empty healing response")
      recordPeerCooldown(peer, "empty healing response")
      // Soft-exile (mirrors AccountRangeCoordinator.markPeerStateless): a SINGLE empty response no
      // longer permanently exiles the peer. Strike it; only at EmptyResponseStrikeThreshold consecutive
      // empties (no intervening heal) is it confirmed stateless. A peer that returns empty now may serve
      // after the pivot rolls into its serve window — keeping it eligible across a transient empty cycle
      // is the difference between draining a 2-peer pool to zero and finishing the residual.
      if !statelessPeers.contains(peer.id.value) then
        val strikes = emptyResponseStrikes.getOrElse(peer.id.value, 0) + 1
        emptyResponseStrikes(peer.id.value) = strikes
        if strikes < EmptyResponseStrikeThreshold then
          log.info(
            s"Peer ${peer.id.value} empty-response strike $strikes/$EmptyResponseStrikeThreshold for healing root " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)} — still eligible for dispatch."
          )
        else
          statelessPeers += peer.id.value
          // NB-7: Evict from knownAvailablePeers immediately so the 1s HealingPeerAvailable scheduler
          // tick doesn't re-add and re-dispatch to this peer until the next pivot refresh / auto-clear.
          knownAvailablePeers.filterInPlace(_.id != peer.id)
          log.info(
            s"Peer ${peer.id.value} marked stateless after $strikes consecutive empty responses for healing root " +
              s"${Hex.toHexString(stateRoot.take(4).toArray)} (${statelessPeers.size}/${knownAvailablePeers.size} stateless)"
          )
          // Check if all known peers are stateless — request pivot refresh.
          // Use statelessPeers.nonEmpty (not knownAvailablePeers.nonEmpty): filterInPlace above
          // removes this peer from knownAvailablePeers BEFORE the check, so a single-peer set
          // leaves knownAvailablePeers empty and the old guard silently swallowed the trigger.
          if statelessPeers.size >= knownAvailablePeers.size && statelessPeers.nonEmpty && !pivotRefreshRequested then
            pivotRefreshRequested = true
            pivotRefreshRequestedAt = System.currentTimeMillis()
            log.warn(
              s"All ${statelessPeers.size} peers stateless for healing root " +
                s"${Hex.toHexString(stateRoot.take(4).toArray)}. Requesting pivot refresh."
            )
            snapSyncController ! SNAPSyncController.HealingAllPeersStateless

    log.info(
      s"Healed $healedCount/${nodes.size} trie nodes from peer ${peer.id.value} " +
        s"(total: $totalNodesHealed, pending: ${pendingTasks.size}, active: ${activeRequests.size} reqs, " +
        s"responseBytes=${responseBytesTargetFor(peer)})"
    )

    if healedCount > 0 then snapSyncController ! SNAPSyncController.ProgressNodesHealed(healedCount.toLong)
    // Hash scheme: flush what `commitHeld` buffered after every response rather than waiting for the 1000-node
    // threshold, so a sparse heal never leaves complete nodes sitting in the buffer.
    if rawNodeBuffer.nonEmpty then flushRawNodesAsync()

    // Dispatch more work to this peer if available (pipeline multiple requests)
    dispatchIfPossible(peer)

    self ! HealingCheckCompletion

  /** Nothing pending, nothing in flight, and nothing held (spec 022 T017: a held node waits on a child, so healing is
    * not done while one is).
    */
  def isComplete: Boolean =
    pendingTasks.isEmpty && activeRequests.isEmpty && heldNodes.isEmpty

  /** Commit what is ready (spec 022 D3): every held node whose subtree is present, with every ancestor it completes,
    * and the stale positions found since the last commit, in one atomic write; then release them. Path: written now,
    * one `PathNodeStorage` batch. Hash: moved to the flush buffer, which the flush writes in commit order (any prefix
    * of it is complete). A write that throws loses nothing: the held nodes and deletes stay, and the next commit
    * retries them (#1549); the error is logged.
    */
  def commitHeld(): Unit =
    if heldNodes.hasPendingWrites then
      val plan = heldNodes.planCommit()
      storageScheme match
        case StorageScheme.Path =>
          stateIngest.pathNodes match
            case None =>
              log.error("[HEAL-COMMIT] Path scheme without a path-node store: nothing can be written")
            case Some(pns) =>
              try
                // hunter N4: the positions are read inside the try, so a malformed one is a failed write, not a crash.
                val writes = plan.nodes.map { e =>
                  val nibbles = com.chipprbots.ethereum.mpt.HexPrefix.decode(e.request.pathset.last.toArray)._1
                  val owner = if e.request.pathset.size > 1 then Some(e.request.pathset.head) else None
                  (owner, nibbles, e.node.toArray)
                }
                pns.writeNodesWithDeletes(writes, plan.deletes)
                heldNodes.applyCommit(plan)
                // vault note 2: deletes per batch, to check the tombstone estimate (about 0.07 per extension).
                log.debug(s"[HEAL-COMMIT] ${plan.nodes.size} node(s) and ${plan.deletes.size} delete(s) in one batch")
              catch
                case scala.util.control.NonFatal(e) =>
                  log.error(
                    s"[HEAL-COMMIT] Write of ${plan.nodes.size} node(s) and ${plan.deletes.size} delete(s) failed — " +
                      "kept held, retried at the next commit (or the next pulse)",
                    e
                  )
        case StorageScheme.Hash =>
          rawNodeBuffer ++= plan.nodes.map(e => (e.request.hash, e.node.toArray))
          heldNodes.applyCommit(plan)

  /** Remember `account`'s codeHash for the controller if it names code this node does not hold. */
  private def recordHealedCodeHash(account: Account): Unit =
    if healedCodeCheck then
      val codeHash = account.codeHash
      if codeHash != Account.EmptyCodeHash && stateIngest.bytecode.get(codeHash.value).isEmpty then
        healedCodeHashesThisResponse += codeHash.value

  private def flushHealedCodeHashes(): Unit =
    if healedCodeHashesThisResponse.nonEmpty then
      val hashes = healedCodeHashesThisResponse.toSeq
      healedCodeHashesThisResponse.clear()
      log.info(s"[HEAL-CODE] ${hashes.size} healed account(s) lack bytecode — reporting codeHashes to the controller")
      snapSyncController ! SNAPSyncController.HealedCodeHashes(hashes)

  /** Fill `request` with the served node (spec 022 D3): take its parent edges from the book, find its missing children,
    * and hold it until they are all committed. With no missing child it is ready at once, and the response's commit
    * writes it (with every ancestor it completes). Missing children are recorded as requests with an edge from it (a
    * fetch queued for each hash not already fetched), or, when held already, gain the edge directly. Path: the stale
    * positions discovery finds go to the next commit write, before anything can be written there.
    *
    * False when a storage read failed while its children were looked up: the request is put back with its parents'
    * edges (the caller queues the fetch), or, with `giveUpOnReadFailure`, dropped (forge F2).
    */
  private def fillRequest(request: HealRequest, nodeData: ByteString, giveUpOnReadFailure: Boolean): Boolean =
    val id = HeldNodeSet.idOf(request)
    val parents = healRequests.satisfy(request)
    var childrenRead = true
    if heldNodes.contains(id) then
      // Held already (a walk or controller entry fetched it again): its children are being fetched; it only gains the
      // parents that waited on this request, each counted once per edge it does not have yet (beacon N3, forge N7).
      parents.groupBy(identity).foreach { case (p, edges) => heldNodes.attachParent(id, p, edges.size) }
    else
      heldNodes.heldAt(request.key).foreach { other =>
        // beacon N7: a position filled again with another node. The old node is dropped, never written; edges to it
        // decrement nothing from now on, and its parents wait on it, not on this node.
        log.error(
          s"[HEAL-HELD-CONFLICT] Position ${Hex.toHexString(request.key.take(8).toArray)} held node " +
            s"${Hex.toHexString(other.request.hash.take(4).toArray)} and is now filled with " +
            s"${Hex.toHexString(request.hash.take(4).toArray)} — the held node is dropped"
        )
        heldNodes.drop(other.id)
      }
      // The children are looked up in storage; a read that throws is not the node's fault (forge W1).
      val discovered =
        try
          Right(discoverMissingChildren(nodeData, request).map { case (children, deletes) =>
            (children, ownPositionDelete(request, parents) ++ deletes)
          })
        catch case scala.util.control.NonFatal(e) => Left(e)
      discovered match
        case Left(readFailure) if giveUpOnReadFailure =>
          // spec 022 review forge F2: the children of this node could not be read on its last fills in a row: a stored
          // child that does not decode, or a store that cannot read it. Fetching it again would loop. It is dropped like
          // a node that does not decode: its parents stay held until the orphan guard drops them, and a walk finds them
          // missing again (one more fetch of it per walk, not one per round trip).
          log.error(
            s"[HEAL] Reading the stored children of healed node ${Hex.toHexString(request.hash.take(4).toArray)} " +
              s"failed on ${TrieNodeHealingCoordinator.MaxConsecutiveReadFailures} fills in a row — not fetched again; " +
              s"its ${parents.size} parent(s) stay held until the orphan guard drops them",
            readFailure
          )
          // spec 022 review forge F6: a node known missing was dropped, so no earlier clean pass vouches for completion;
          // the completion check walks again (and finds it, if the current root still needs it).
          verificationPassComplete = false
          // Escalated to the controller's stagnation path (as the pulse does, NB-11): afterwards nothing may be pending,
          // so the pulse's own stagnation count never starts. Once per controller answer (hold: resume dispatch; or a
          // refresh), which clears `giveUpEscalated`; not while the pulse's escalation waits. Dispatch goes on meanwhile
          // (beacon N2-r3, hunter-r3 N1): the pulse's latch, which stops it, is not set here.
          if !pivotRefreshRequested && !giveUpEscalated then
            giveUpEscalated = true
            snapSyncController ! SNAPSyncController.HealingStagnated(totalNodesHealed.toLong, pendingTasks.size.toLong)
          childrenRead = false
        case Left(readFailure) =>
          // spec 022 review forge W1: the node is fine, its children are unknown. Put its request back, with the edges of
          // the parents that wait on it, and fetch it again: dropping it would leave those parents waiting on an edge
          // nothing closes. The caller queues the fetch (one per hash, hunter W1-r2).
          log.warn(
            s"[HEAL] A storage read failed while looking up the children of healed node " +
              s"${Hex.toHexString(request.hash.take(4).toArray)} — its request goes back with ${parents.size} parent " +
              s"edge(s) and it is fetched again: ${readFailure.getMessage}"
          )
          healRequests.restore(request, parents)
          childrenRead = false
        case Right(None) =>
          // vault note 5: a node that hashes right but does not decode is never written: its children are unknown, so
          // it cannot be complete. Every peer serves the same bytes, so it is not fetched again. Its parents' edges to
          // it close nothing: they stay held until the orphan guard drops them (a walk then finds them again).
          ()
        case Right(Some((children, deletes))) =>
          if deletes.nonEmpty then heldNodes.addDeletes(deletes)
          val (heldChildren, newChildren) = children.partition(c => heldNodes.contains(HeldNodeSet.idOf(c)))
          heldChildren.groupBy(_.key).foreach { case (_, refs) =>
            heldNodes.attachParent(HeldNodeSet.idOf(refs.head), id, refs.size)
          }
          val toFetch = healRequests.addChildren(id, newChildren)
          // A child already live gained an edge: its queued fetch, if it opened a new subtree, now frees held memory.
          if toFetch.size < newChildren.size then
            newChildren.iterator.map(_.hash).distinct.foreach(pendingTasks.promote)
          heldNodes.hold(request, nodeData, children.size, parents)
          val newEntries = toFetch.map(c => HealingEntry(c.pathset, c.hash))
          if newEntries.nonEmpty then
            pendingTasks ++= newEntries
            persistFrontier(newEntries) // Layer 2: inline-discovered children are new frontier entries
            childrenDiscoveredTotal += newEntries.size
            if childrenDiscoveredTotal % 100 == 0 || childrenDiscoveredTotal <= 20 then
              log.info(
                s"[HEAL-DISCOVER] Inline children queued: $childrenDiscoveredTotal total " +
                  s"(+${newEntries.size} from this node, pending: ${pendingTasks.size})"
              )
    // spec 003 C1/FR-001: capture this healed request as a scoped-verification seed. This is the ONLY site that counts
    // a network-served node as healed, so the accumulator is exactly {requests whose node was received this round}. Tag
    // the round's root on first capture (F5), dedup by request key (a position under Path, so every alias is a seed),
    // and latch overflow at scopedHealMaxPaths (F4/FR-011).
    // spec 004 T017: healedPathsRoot is tagged with the WALK root `stateRoot` (NOT `serveRoot`), so the
    // scoped-verification F5 predicate `healedPathsRoot == stateRoot` is unaffected by decoupling.
    if healedPathsThisRound.isEmpty then healedPathsRoot = stateRoot
    if !healedPathsOverflowed && !healedPathsThisRound.contains(request.key) then
      if healedPathsThisRound.size >= scopedHealMaxPaths then healedPathsOverflowed = true
      else healedPathsThisRound.update(request.key, HealingEntry(request.pathset, request.hash))
    childrenRead

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node and
    * returns its missing children, one request per child edge, and (Path scheme) the stale positions to delete. Makes
    * healing self-feeding: root → children → grandchildren top-down without a trie walk. `None` when the node does not
    * decode: its children are unknown, so it must never be written (vault note 5).
    *
    * A child whose request is already live (queued or in flight), or that is held (received, waiting on its own
    * children: not on disk), is missing by definition: under Hash it is the same node, so the parent gains an edge to
    * it without a storage read (spec 022 D2: a shared child is fetched once); under Path it is the same position, still
    * read so that stale bytes there are deleted (review item 6). Under Hash a child in the flush buffer is present: it
    * was committed with its whole subtree, only not flushed yet.
    *
    * B3 FIX: under Hash, a branch's other children are checked with a single multiGetNodes call instead of up to 16
    * serial isNodeInStorage calls on the actor thread. The extension child uses the same pattern for consistency.
    *
    * Presence is scheme-dependent. Hash scheme: content-addressed lookup by the child's hash (unchanged). Path scheme:
    * `PathNodeStorage` is keyed by PATH, so each child is looked up at ITS OWN path (the same path it would be queued
    * and fetched with) and counts as present only when the bytes stored there hash to the child's hash — see
    * `pathPresence`. That is what lets a re-peg re-fetch only the delta instead of the whole trie.
    *
    * FR-008 (Path, D10; geth `trie/sync.go` `children()` and `hasNode`), stale positions, deleted in the next commit
    * write (spec 022 slice 2: before the child is written there, as geth's membatch orders a `delNode` before the later
    * write; deferring them to the parent's own commit would delete the child written in between):
    *   - an extension with a hashed child skips the positions strictly inside its key (P ++ key[:i], 0 < i < len(key)):
    *     nothing may stay stored there;
    *   - a child position holding bytes of another hash (a stale node of an older root).
    */
  private def discoverMissingChildren(
      nodeData: ByteString,
      parent: HealRequest
  ): Option[(Seq[HealRequest], Seq[(Option[ByteString], Array[Byte])])] =
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account

    val pathset = parent.pathset
    val nodeHash = parent.hash
    if pathset.isEmpty then Some((Seq.empty, Seq.empty))
    else
      // Only the decode is a node fault (vault note 5): it returns None. A storage read below throws to the caller
      // (forge N1, W1), which puts the request back.
      scala.util.Try(MptTraversals.decodeNode(nodeData.toArray)) match
        case scala.util.Failure(e) =>
          log.error(
            s"[HEAL] Healed node ${Hex.toHexString(nodeHash.take(4).toArray)} hashes right but does not decode — " +
              s"not stored, not fetched again: ${e.getMessage}"
          )
          None
        case scala.util.Success(decoded) =>
          val parentCompact = pathset.last.toArray
          val parentNibbles = HexPrefix.decode(parentCompact)._1
          val isStorageTrie = pathset.size > 1 // Seq(accountHash, path) vs Seq(path)
          val owner: Option[ByteString] = if isStorageTrie then Some(pathset.head) else None

          val children = mutable.ArrayBuffer.empty[HealRequest]
          val deletes = mutable.ArrayBuffer.empty[(Option[ByteString], Array[Byte])]

          // The path a child at `childNibbles` is queued, fetched and (Path scheme) stored under.
          def childPathset(childNibbles: Array[Byte]): Seq[ByteString] =
            val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
            if isStorageTrie then Seq(pathset.head, childCompact) else Seq(childCompact)

          def requestAt(childSet: Seq[ByteString], childHash: ByteString): HealRequest =
            healRequestFor(HealingEntry(childSet, childHash))

          // Path scheme: one child, looked up at its own position (geth `hasNode` on every hash child): present, absent,
          // or stale bytes of another hash, which `danglingAt` (the same position) deletes with the parent's write. A child
          // already requested is missing whatever is stored there (an edge to its request); stale bytes there are still
          // deleted (review item 6).
          def pathChild(
              childSet: Seq[ByteString],
              childHash: ByteString,
              danglingAt: (Option[ByteString], Array[Byte])
          ): Unit =
            val request = requestAt(childSet, childHash)
            val live = healRequests.contains(request.key)
            pathPresence(childHash, childSet) match
              case PathPresence.Present => if live then children += request
              case PathPresence.Absent  => children += request
              case PathPresence.Mismatch =>
                children += request
                deletes += danglingAt

          decoded match
            case branch: BranchNode =>
              val hashed: Seq[(Int, ByteString)] =
                branch.children.zipWithIndex.collect { case (hn: HashNode, i) => (i, ByteString(hn.hashNode)) }.toSeq
              storageScheme match
                case StorageScheme.Hash =>
                  // Hash scheme: one batched content-addressed multiGetNodes call for the children not already live,
                  // held (missing: an edge to them) or buffered for the flush (present: their subtree is committed).
                  val toCheck = hashed.filterNot { case (_, h) => knownLocally(h).isDefined }
                  val present: Map[Int, Boolean] =
                    if toCheck.isEmpty then Map.empty
                    else
                      toCheck
                        .map(_._1)
                        .zip(mptStorage.multiGetNodes(toCheck.map(_._2.toArray)).map(_.isDefined))
                        .toMap
                  hashed.foreach { case (i, childHash) =>
                    if !knownLocally(childHash).getOrElse(present.getOrElse(i, false)) then
                      children += requestAt(childPathset(parentNibbles :+ i.toByte), childHash)
                  }
                case StorageScheme.Path =>
                  hashed.foreach { case (i, childHash) =>
                    val childNibbles = parentNibbles :+ i.toByte
                    pathChild(childPathset(childNibbles), childHash, (owner, childNibbles))
                  }

            case ext: ExtensionNode =>
              ext.next match
                case hash: HashNode =>
                  val childHash = ByteString(hash.hashNode)
                  val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                  storageScheme match
                    case StorageScheme.Hash =>
                      if !hashChildPresent(childHash) then children += requestAt(childPathset(childNibbles), childHash)
                    case StorageScheme.Path =>
                      (1 until ext.sharedKey.length).foreach { i =>
                        deletes += ((owner, parentNibbles ++ ext.sharedKey.take(i).toArray))
                      }
                      pathChild(childPathset(childNibbles), childHash, (owner, childNibbles))
                case _ => // Already inline-encoded — no missing child
            case leaf: LeafNode if !isStorageTrie =>
              // ARCH-LEAF-SEED: Account trie leaf — decode account, seed storage trie if missing.
              // Besu equivalent: getChildRequests() → getStorageTrieNodeRequests() on account leaf values.
              Account(leaf.value).foreach { account =>
                recordHealedCodeHash(account)
                if account.storageRoot != Account.EmptyStorageRootHash then
                  val leafNibbles = leaf.key.toArray
                  val allNibbles = parentNibbles ++ leafNibbles
                  if allNibbles.length == 64 then
                    val accountHashBytes = allNibbles
                      .grouped(2)
                      .map { g =>
                        ((g(0) << 4) | g(1)).toByte
                      }
                      .toArray
                    val accountHash = ByteString(accountHashBytes)
                    val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                    val storageRootSet = Seq(accountHash, emptyStoragePath)
                    val storageRoot = account.storageRoot.value
                    // Presence is checked here, once the account hash — hence the storage root's path — is known. Every
                    // account is its own position, so two accounts with an identical storage root are two requests under
                    // Path (spec 022 FR-007: neither storage trie is lost) and one shared node under Hash.
                    storageScheme match
                      case StorageScheme.Hash =>
                        // Content-addressed lookup by hash, exactly as before (the pathset is ignored).
                        val request = requestAt(storageRootSet, storageRoot)
                        if !hashChildPresent(storageRoot) then
                          children += request
                          log.debug(
                            s"[HEAL-LEAF] Seeded storage trie root ${Hex.toHexString(storageRoot.take(4).toArray)} " +
                              s"for account ${Hex.toHexString(accountHashBytes.take(4))}"
                          )
                      case StorageScheme.Path =>
                        // The storage-trie root lives at (accountHash, empty path) and is checked THERE; stale bytes of
                        // another root there are deleted with this leaf's write (another column family, same batch).
                        pathChild(storageRootSet, storageRoot, (Some(accountHash), Array.empty[Byte]))
              }

            case _ => // storage trie LeafNode, NullNode, HashNode — no children to discover
          // spec 005 C3b/D2/T009/T012 — heal-side subtree-complete SEED (consensus-load-bearing; the single highest
          // chain-split risk of this port). Stage this just-healed node's OWN hash as a subtree-complete candidate IFF
          // its subtree is durably closed by INDUCTION: every hash-referenced child is PRESENT on disk AND itself
          // recorded subtree-complete (isSubtreeComplete). The isSubtreeComplete clause is load-bearing — mere PRESENCE is
          // NEVER enough: a download-mosaic node can be present yet its subtree incomplete, so seeding on presence alone
          // would write a false record → the pruned descent would skip a real hole → false completion → divergent
          // finalized state. "present AND recorded" SUBSUMES "no missing child" and "no pending child": a still-pending /
          // in-flight child is either absent (fails isNodeInStorage) or present-but-unrecorded (fails isSubtreeComplete).
          // For a leaf (no hash children) the closure is VACUOUS — the inductive BASE (PrunedHealCrashSafetySpec T-3).
          // Inline children carry NO hash references (a 32-byte hash makes a node ≥32 bytes, so it is never inlined), so
          // direct HashNode children + the account-leaf storage root are the COMPLETE set of subtree roots under this
          // node. Gated on prunedEnabled (else inert ⇒ OFF-path byte-identical). STAGED only — the durable record is
          // written by writeDurableSubtreeRecords strictly AFTER mptStorage.persist() (D3 record-after-persist); a crash
          // before the flush drops the in-memory candidate ⇒ safe descend. The `children.isEmpty` pre-gate is a cheap
          // necessary short-circuit (a missing or live child means not closed); the forall over actual
          // storage+record state is the AUTHORITATIVE gate and can never false-record regardless of pending/filter state.
          if prunedEnabled && children.isEmpty then
            val subtreeRoots: Seq[ByteString] = decoded match
              case branch: BranchNode =>
                branch.children.collect { case hn: HashNode => ByteString(hn.hashNode) }.toSeq
              case ext: ExtensionNode =>
                ext.next match
                  case hn: HashNode => Seq(ByteString(hn.hashNode))
                  case _            => Seq.empty
              case leaf: LeafNode if !isStorageTrie =>
                Account(leaf.value).toOption match
                  case Some(account) if account.storageRoot != Account.EmptyStorageRootHash =>
                    Seq(account.storageRoot.value)
                  case _ => Seq.empty
              case _ => Seq.empty
            // `prunedEnabled` (this block's guard) requires the Hash scheme, so `isNodeInStorage` here is always the
            // content-addressed lookup and takes no path. If pruning is ever enabled under Path, the empty pathset makes
            // it conservatively false — no record is staged, the walk descends — never a false "closed".
            val subtreeClosed =
              subtreeRoots.forall(c =>
                isNodeInStorage(c, Seq.empty) && stateIngest.healingFrontier.exists(_.isSubtreeComplete(c))
              )
            if subtreeClosed then
              pendingSubtreeRecords += nodeHash
              log.debug(
                s"[HEAL-VERIFY-PRUNED] Staged subtree-complete candidate ${Hex.toHexString(nodeHash.take(4).toArray)} " +
                  s"(${subtreeRoots.size} child subtree(s) present+recorded) — durable record written post-flush (D3)."
              )
          Some((children.toSeq, deletes.toSeq))

  /** Path scheme: a parentless node's own position may hold a stale node nothing has deleted yet (a parent's discovery
    * deletes a stale child's position): it is deleted before anything below it is written. Reads storage, so it may
    * throw (a read failure, handled with the children's lookup).
    */
  private def ownPositionDelete(
      request: HealRequest,
      parents: Seq[ByteString]
  ): Seq[(Option[ByteString], Array[Byte])] =
    if storageScheme == StorageScheme.Path && parents.isEmpty &&
      pathPresence(request.hash, request.pathset) == PathPresence.Mismatch
    then
      val owner = if request.pathset.size > 1 then Some(request.pathset.head) else None
      Seq((owner, com.chipprbots.ethereum.mpt.HexPrefix.decode(request.pathset.last.toArray)._1))
    else Seq.empty

  /** Hash scheme: what is known about `hash` without reading the store. `Some(false)` when it is live or held (missing:
    * the parent gets an edge to it), `Some(true)` when it is in the flush buffer (committed, its subtree complete, only
    * not flushed yet), `None` otherwise (the store decides).
    */
  private def knownLocally(hash: ByteString): Option[Boolean] =
    // forge N2: the buffer first. A buffered node is complete, even if a walk asked for it again meanwhile.
    if rawNodeBuffer.contains(hash) then Some(true)
    else if healRequests.contains(hash) || heldNodes.contains(hash) then Some(false)
    else None

  /** Hash scheme: whether the child `hash` is present (its subtree complete): buffered for the flush, or in the store.
    */
  private def hashChildPresent(hash: ByteString): Boolean =
    knownLocally(hash).getOrElse(mptStorage.multiGetNodes(Seq(hash.toArray)).headOption.flatten.isDefined)

  /** Presence check that gates healing queue/skip decisions.
    *
    * Hash scheme: content-addressed lookup through `mptStorage` — byte-identical to the pre-existing behaviour,
    * `pathset` is ignored. Path scheme: present ONLY when the bytes stored at `pathset`'s exact path hash to `hash`
    * (`readPathVerifiedRlp`); path existence alone is never sufficient. An empty `pathset` means the call site has no
    * path context, which under Path is conservatively absent (healing re-requests it; writes are idempotent).
    */
  def isNodeInStorage(hash: ByteString, pathset: Seq[ByteString]): Boolean =
    storageScheme match
      case StorageScheme.Hash =>
        try
          mptStorage.get(hash.toArray); true
        catch case _: Exception => false
      case StorageScheme.Path =>
        readPathVerifiedRlp(hash, pathset).isDefined
