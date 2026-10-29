package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
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
  * its timestamp, and `lastHealedAtMs`; it reads the walk and serve roots and `knownAvailablePeers` (and evicts from
  * it). `prunedEnabled` is an immutable val rule (d) keeps in the core.
  */
private[actors] trait HealResponseProcessingState:
  def stateRoot: ByteString
  def serveRoot: ByteString
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def healRequests: HealRequestBook
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])]
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
  var lastHealedAtMs: Long
  def prunedEnabled: Boolean
  def pendingSubtreeRecords: mutable.Set[ByteString]

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
    healRequests.add(healRequestFor(entry)) match
      case HealRequestBook.Added.NewFetch =>
        pendingTasks += entry
        true
      case _ => false

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
    var receivedBytes: Long = 0

    // Hash-based matching — NOT positional. Servers (core-geth handler.go:546-547)
    // omit entries for storage pathsets whose account is missing, returning sparse
    // responses. Positional zip would align later nodes against the wrong tasks.
    val keccak = new org.bouncycastle.jcajce.provider.digest.Keccak.Digest256()
    val tasksByHash: Map[ByteString, Seq[HealingEntry]] = tasksForRequest.groupBy(_.hash)
    val healedHashes = mutable.Set[ByteString]()

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
        tasksByHash.get(nodeHash) match
          case Some(tasks) =>
            receivedBytes += nodeData.length
            totalBytesReceived += nodeData.length
            // A second copy of a node already taken from this response (one per requested path) fills nothing new.
            if healedHashes.add(nodeHash) then
              // spec 022 D2: the node fills every request waiting on its hash — under Path every position that needs it
              // (an alias: identical subtrees under two accounts), under Hash the one request for the node.
              val filled = requestsFilledBy(nodeHash, tasks)
              if storageScheme == StorageScheme.Hash then rawNodeBuffer += ((nodeHash, nodeData.toArray))
              filled.foreach(request => fillRequest(request, nodeData))
              healedCount += filled.size
              totalNodesHealed += filled.size
              // spec 004 T019/FR-010: count a heal sourced from a serve root that differs from the walk root.
              // Observation-only; never affects completion. (Equal roots ⇒ coupled-equivalent, not counted.)
              if decoupledHealServeRoot && serveRoot != stateRoot then crossRootHealCount += filled.size
              // T015 / FR-006: this task is satisfied — drop its attempt counter (bounded by pendingTasks).
              healAttempts.remove(nodeHash)
          case None =>
            log.debug(
              s"Healing response node not in request set (unexpected): ${Hex.toHexString(nodeHash.take(4).toArray)}"
            )
    }

    // A healed account leaf brings an account that account-range sync never saw, so nothing else will ever fetch its
    // bytecode (the code-hash list is built from account-range responses). Hand the missing ones to the controller,
    // which fetches them before it finalises SNAP. Sent BEFORE any completion signal this actor emits afterwards
    // (same sender, ordered), so the controller always knows of them by the time it is told healing is done.
    flushHealedCodeHashes()

    // Re-queue tasks not satisfied by this response (server skipped or didn't have them). Their requests stay live in
    // `healRequests` (live = pending or in flight), so nothing else queues a second fetch of them meanwhile (BUG-H1).
    tasksForRequest.foreach { task =>
      if !healedHashes.contains(task.hash) then
        healRequests.add(healRequestFor(task)) // no-op for a live request
        pendingTasks += task
        // spec 004 T015/FR-006: an unsatisfied task (server skipped it / content-mismatch drop) bumps its
        // attempt counter and may surface if it stays unservable across the bounded threshold WITHOUT a
        // serve-root advance (which clears the map). This NEVER force-completes — see noteUnservableAttempt.
        noteUnservableAttempt(task.hash)
    }

    completedTaskCount += healedCount
    activeRequests.remove(requestId)
    requestTracker.completeRequest(requestId, nodes.size.max(1))

    // Update healing throttle (geth msgrate alignment)
    val elapsedMs = System.currentTimeMillis() - activeReq.sentAtMs
    updateHealThrottle(healedCount, elapsedMs)

    // Adaptive byte budget + stateless tracking
    if healedCount > 0 then
      adjustResponseBytesOnSuccess(peer, requestedBytes, BigInt(receivedBytes))
      // Successful response — clear stateless marking + strikes and reset stagnation timer.
      // Any forward progress from this peer wipes its prior strikes (mirrors recordPeerSuccess).
      statelessPeers -= peer.id.value
      emptyResponseStrikes.remove(peer.id.value)
      lastHealedAtMs = System.currentTimeMillis()
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

    if healedCount > 0 then
      snapSyncController ! SNAPSyncController.ProgressNodesHealed(healedCount.toLong)
      // Flush immediately after every response rather than waiting for the 1000-node threshold.
      // Sparse healing runs (small gap counts) would otherwise stall writes in the buffer
      // indefinitely if they never hit the count gate.
      flushRawNodesAsync()

    // Dispatch more work to this peer if available (pipeline multiple requests)
    dispatchIfPossible(peer)

    self ! HealingCheckCompletion

  def isComplete: Boolean =
    pendingTasks.isEmpty && activeRequests.isEmpty

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

  /** The requests the served node `hash` fills (spec 022 D2): every live request waiting on it, plus this response's
    * own tasks for it, each once. In production every dispatched task is a live request, so the second part adds
    * nothing; it keeps a task the book does not hold from being dropped.
    */
  private def requestsFilledBy(hash: ByteString, tasks: Seq[HealingEntry]): Seq[HealRequest] =
    (healRequests.waitingOn(hash) ++ tasks.map(healRequestFor)).distinctBy(_.key)

  /** Fill `request` with the served node: find its missing children, store it (Path: at its position, together with the
    * positions its write leaves dangling, FR-008), satisfy the request, record the children as requests with an edge
    * from it (queueing a fetch for each hash not already fetched), and capture it for scoped verification. Under the
    * Hash scheme the caller has already buffered the node for the flush.
    */
  private def fillRequest(request: HealRequest, nodeData: ByteString): Unit =
    val (children, deletes) = discoverMissingChildren(nodeData, request)
    if storageScheme == StorageScheme.Path then writeAtPosition(request, nodeData, deletes)
    healRequests.satisfy(request.key)
    val newEntries = healRequests.addChildren(request.key, children).map(c => HealingEntry(c.pathset, c.hash))
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
    // a network-served node as healed, so the accumulator is exactly {requests whose node was stored this round}. Tag
    // the round's root on first capture (F5), dedup by request key (a position under Path, so every alias is a seed),
    // and latch overflow at scopedHealMaxPaths (F4/FR-011).
    // spec 004 T017: healedPathsRoot is tagged with the WALK root `stateRoot` (NOT `serveRoot`), so the
    // scoped-verification F5 predicate `healedPathsRoot == stateRoot` is unaffected by decoupling.
    if healedPathsThisRound.isEmpty then healedPathsRoot = stateRoot
    if !healedPathsOverflowed && !healedPathsThisRound.contains(request.key) then
      if healedPathsThisRound.size >= scopedHealMaxPaths then healedPathsOverflowed = true
      else healedPathsThisRound.update(request.key, HealingEntry(request.pathset, request.hash))

  /** Path scheme: write the served node at `request`'s position and delete `deletes` in the same write batch (spec 022
    * FR-008). pathset = [HP-path] for the account trie, [accountHash32, HP-path] for a storage trie.
    */
  private def writeAtPosition(
      request: HealRequest,
      nodeData: ByteString,
      deletes: Seq[(Option[ByteString], Array[Byte])]
  ): Unit =
    stateIngest.pathNodes.foreach { pns =>
      val nibbles = com.chipprbots.ethereum.mpt.HexPrefix.decode(request.pathset.last.toArray)._1
      val owner = if request.pathset.size > 1 then Some(request.pathset.head) else None
      pns.writeNodeWithDeletes(owner, nibbles, nodeData.toArray, deletes)
    }

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node and
    * returns its missing children, one request per child edge, and (Path scheme) the positions its write leaves
    * dangling. Makes healing self-feeding: root → children → grandchildren top-down without a trie walk.
    *
    * A child whose request is already live (queued or in flight) is missing by definition and is returned without a
    * storage read: under Hash it is the same node, so the parent gains an edge to the fetch already out (spec 022 D2: a
    * shared child is fetched once); under Path it is the same position.
    *
    * B3 FIX: under Hash, a branch's other children are checked with a single multiGetNodes call instead of up to 16
    * serial isNodeInStorage calls on the actor thread. The extension child uses the same pattern for consistency.
    *
    * Presence is scheme-dependent. Hash scheme: content-addressed lookup by the child's hash (unchanged). Path scheme:
    * `PathNodeStorage` is keyed by PATH, so each child is looked up at ITS OWN path (the same path it would be queued
    * and fetched with) and counts as present only when the bytes stored there hash to the child's hash — see
    * `pathPresence`. That is what lets a re-peg re-fetch only the delta instead of the whole trie.
    *
    * FR-008 (Path, D10; geth `trie/sync.go` `children()` and `hasNode`), the positions the parent's write leaves
    * dangling, deleted in that write's batch:
    *   - an extension with a hashed child skips the positions strictly inside its key (P ++ key[:i], 0 < i < len(key)):
    *     nothing may stay stored there;
    *   - a child position holding bytes of another hash (a stale node of an older root).
    */
  private def discoverMissingChildren(
      nodeData: ByteString,
      parent: HealRequest
  ): (Seq[HealRequest], Seq[(Option[ByteString], Array[Byte])]) =
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    val pathset = parent.pathset
    val nodeHash = parent.hash
    if pathset.isEmpty then (Seq.empty, Seq.empty)
    else
      try
        val decoded = MptTraversals.decodeNode(nodeData.toArray)
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

        // Path scheme: one child, looked up at its own position. Live: missing, no read. Otherwise present, absent, or
        // stale bytes of another hash, which `danglingAt` (the same position) deletes with the parent's write.
        def pathChild(
            childSet: Seq[ByteString],
            childHash: ByteString,
            danglingAt: (Option[ByteString], Array[Byte])
        ): Unit =
          val request = requestAt(childSet, childHash)
          if healRequests.contains(request.key) then children += request
          else
            pathPresence(childHash, childSet) match
              case PathPresence.Present => ()
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
                // Hash scheme: one batched content-addressed multiGetNodes call for the children not already live.
                val toCheck = hashed.filterNot { case (_, h) => healRequests.contains(h) }
                val present: Map[Int, Boolean] =
                  if toCheck.isEmpty then Map.empty
                  else
                    toCheck
                      .map(_._1)
                      .zip(mptStorage.multiGetNodes(toCheck.map(_._2.toArray)).map(_.isDefined))
                      .toMap
                hashed.foreach { case (i, childHash) =>
                  if !present.getOrElse(i, false) then
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
                    val missing = healRequests.contains(childHash) ||
                      mptStorage.multiGetNodes(Seq(childHash.toArray)).headOption.flatten.isEmpty
                    if missing then children += requestAt(childPathset(childNibbles), childHash)
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
                      if healRequests.contains(request.key) then children += request
                      else if !isNodeInStorage(storageRoot, storageRootSet) then
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
        (children.toSeq, deletes.toSeq)
      catch
        case NonFatal(e) =>
          log.debug(
            s"[HEAL] Cannot decode healed node for child discovery: ${e.getMessage}. " +
              s"Skipping — trie walk will find these nodes."
          )
          (Seq.empty, Seq.empty)

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
