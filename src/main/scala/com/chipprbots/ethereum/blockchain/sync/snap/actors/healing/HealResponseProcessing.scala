package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes

/** The coordinator state `HealResponseProcessing` reads and writes (spec 016 C-H5, research.md R14b). The core's
  * behaviour arms (the lifecycle arms' reseeds and re-pegs, the completion gate, the stagnation pulse), the other
  * modules, or rule (d) keep them in the core, so the Impl keeps them as fields. The response processing mutates the
  * task queue, its dedup set, the Path-scheme alias map, the in-flight map, the raw-node buffer, the attempt counters,
  * the spec 003 scoped-verification accumulator (`healedPathsThisRound`, `healedPathsRoot`, `healedPathsOverflowed`),
  * the stateless set and its strikes and the staged spec 005 subtree records; it writes the progress counters
  * (`totalNodesHealed`, `crossRootHealCount`, `totalBytesReceived`, `completedTaskCount`), the pivot-refresh latch and
  * its timestamp, and `lastHealedAtMs`; it reads the walk and serve roots and `knownAvailablePeers` (and evicts from
  * it). `prunedEnabled` is an immutable val rule (d) keeps in the core.
  */
private[actors] trait HealResponseProcessingState:
  def stateRoot: ByteString
  def serveRoot: ByteString
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def pendingHashSet: mutable.Set[ByteString]
  def pathAliases: mutable.Map[ByteString, mutable.LinkedHashMap[Seq[ByteString], HealingEntry]]
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

/** What `reverifyCurrentRoot` calls in the core: `isComplete`, nothing pending and nothing in flight (C-H5's member,
  * still in the core).
  */
private[actors] trait HealCompletionApi:
  def isComplete: Boolean

/** TrieNodeHealingCoordinator's heal response processing (spec 016 C-H5, coordinator-characterization.md §3.4): a
  * served `TrieNodes` response matched to its request by node hash (never by position), each node accepted only when
  * its keccak256 equals a requested task hash (the spec 004 consensus-safety invariant), buffered for the Hash-scheme
  * flush or written by path under the Path scheme (and at every alias path), the spec 003 healed-paths capture, the
  * re-queue of unsatisfied tasks, the throttle and `responseBytes` feedback, the stateless-peer strikes and the
  * all-peers-stateless pivot refresh; the inline child discovery that makes healing self-feeding (with the account-leaf
  * storage-root seed, the spec 005 subtree-complete seed and the healed-code report), the enqueue (`queueNodes`), the
  * presence check (`isNodeInStorage`), `isComplete` and the scoped-verification reset (`clearHealedPathsSet`). The
  * coordinator core keeps the behaviour arms that call it (`healResponseArms`, the lifecycle arms, the completion
  * gate).
  */
private[actors] trait HealResponseProcessing:
  this: TrieNodeHealingCoordinatorImpl =>

  // codeHashes of account leaves healed in the response being processed whose bytecode is not in `evmCodeStorage`.
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

  def queueNodes(pathsAndHashes: Seq[(Seq[ByteString], ByteString)]): Unit =
    val entries = pathsAndHashes.collect {
      case (pathset, hash) if !pendingHashSet.contains(hash) =>
        pendingHashSet += hash
        HealingEntry(pathset = pathset, hash = hash)
    }
    if storageScheme == StorageScheme.Path then
      // Only the paths NOT selected above are aliases (the selected path is written as the task itself).
      val selected = entries.map(_.pathset).toSet
      pathsAndHashes.foreach { case (pathset, hash) =>
        if pendingHashSet.contains(hash) && !selected.contains(pathset) then
          pathAliases.getOrElseUpdate(hash, mutable.LinkedHashMap.empty).update(pathset, HealingEntry(pathset, hash))
      }
    val deduped = pathsAndHashes.size - entries.size
    pendingTasks ++= entries
    persistFrontier(entries) // Layer 2: mirror new frontier entries to the persisted CF
    val dedupStr = if deduped > 0 then s" ($deduped duplicates filtered)" else ""
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
    val taskByHash = tasksForRequest.map(t => t.hash -> t).toMap
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
        if taskByHash.contains(nodeHash) then
          storageScheme match
            case StorageScheme.Hash =>
              rawNodeBuffer += ((nodeHash, nodeData.toArray))
            case StorageScheme.Path =>
              // PathScheme: write directly by nibble path (no batching needed — healing is low-volume).
              // pathset = [HP-path] for account trie, [accountHash32, HP-path] for storage trie.
              stateIngest.pathNodes.foreach { pns =>
                def writeAt(task: HealingEntry): Unit =
                  val nibbles = com.chipprbots.ethereum.mpt.HexPrefix.decode(task.pathset.last.toArray)._1
                  if task.pathset.size > 1 then
                    pns.writeStorageNode(ByteString(task.pathset.head), nibbles, nodeData.toArray)
                  else pns.writeAccountNode(nibbles, nodeData.toArray)
                taskByHash.get(nodeHash).foreach(writeAt)
                // Same hash missing at other paths (deduped out of the queue): write at those paths too.
                pathAliases.remove(nodeHash).foreach(_.values.foreach(writeAt))
              }
          healedCount += 1
          totalNodesHealed += 1
          // spec 004 T019/FR-010: count a heal sourced from a serve root that differs from the walk root.
          // Observation-only; never affects completion. (Equal roots ⇒ coupled-equivalent, not counted.)
          if decoupledHealServeRoot && serveRoot != stateRoot then crossRootHealCount += 1
          // T015 / FR-006: this task is satisfied — drop its attempt counter (bounded by pendingTasks).
          healAttempts.remove(nodeHash)
          // spec 003 C1/FR-001: capture this healed node's HealingEntry as a scoped-verification seed.
          // This is the ONLY site that increments totalNodesHealed for network-served nodes, so the
          // accumulator is exactly {nodes whose bytes were written this round}. Tag the round's root on
          // first capture (F5), dedup by hash, and latch overflow at scopedHealMaxPaths (F4/FR-011).
          // spec 004 T017: healedPathsRoot is tagged with the WALK root `stateRoot` (NOT `serveRoot`), so the
          // scoped-verification F5 predicate `healedPathsRoot == stateRoot` is unaffected by decoupling.
          taskByHash.get(nodeHash).foreach { task =>
            if healedPathsThisRound.isEmpty then healedPathsRoot = stateRoot
            if !healedPathsOverflowed && !healedPathsThisRound.contains(task.hash) then
              if healedPathsThisRound.size >= scopedHealMaxPaths then healedPathsOverflowed = true
              else healedPathsThisRound.update(task.hash, task)
          }
          receivedBytes += nodeData.length
          totalBytesReceived += nodeData.length
          healedHashes += nodeHash
          // Inline child discovery — Besu/geth aligned scheduler approach.
          // Each healed node is decoded to find missing children; queue them directly
          // without waiting for a full 3h trie walk. Walk becomes validation-only.
          taskByHash.get(nodeHash).foreach(task => discoverMissingChildren(nodeData, task.pathset, nodeHash))
        else
          log.debug(
            s"Healing response node not in request set (unexpected): ${Hex.toHexString(nodeHash.take(4).toArray)}"
          )
    }

    // A healed account leaf brings an account that account-range sync never saw, so nothing else will ever fetch its
    // bytecode (the code-hash list is built from account-range responses). Hand the missing ones to the controller,
    // which fetches them before it finalises SNAP. Sent BEFORE any completion signal this actor emits afterwards
    // (same sender, ordered), so the controller always knows of them by the time it is told healing is done.
    flushHealedCodeHashes()

    // Re-queue tasks not satisfied by this response (server skipped or didn't have them).
    // Restore to dedup set so QueueMissingNodes doesn't add duplicates (BUG-H1 fix).
    tasksForRequest.foreach { task =>
      if !healedHashes.contains(task.hash) then
        pendingHashSet += task.hash
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
      if codeHash != Account.EmptyCodeHash && evmCodeStorage.get(codeHash.value).isEmpty then
        healedCodeHashesThisResponse += codeHash.value

  private def flushHealedCodeHashes(): Unit =
    if healedCodeHashesThisResponse.nonEmpty then
      val hashes = healedCodeHashesThisResponse.toSeq
      healedCodeHashesThisResponse.clear()
      log.info(s"[HEAL-CODE] ${hashes.size} healed account(s) lack bytecode — reporting codeHashes to the controller")
      snapSyncController ! SNAPSyncController.HealedCodeHashes(hashes)

  /** Inline child discovery after each healed node — Besu/geth scheduler-driven alignment. Decodes the healed node,
    * discovers child hashes, checks storage, queues missing children. Makes healing self-feeding: root → children →
    * grandchildren top-down without trie walk.
    *
    * B3 FIX: branch children are checked with a single multiGetNodes call instead of up to 16 serial isNodeInStorage
    * calls on the actor thread. Extension child uses the same pattern for consistency.
    *
    * Presence is scheme-dependent. Hash scheme: content-addressed lookup by the child's hash (unchanged). Path scheme:
    * `PathNodeStorage` is keyed by PATH, so each child is looked up at ITS OWN path (the same path it would be queued
    * and fetched with) and counts as present only when the bytes stored there hash to the child's hash — see
    * `readPathVerifiedRlp`. That is what lets a re-peg re-fetch only the delta instead of the whole trie.
    */
  private def discoverMissingChildren(nodeData: ByteString, pathset: Seq[ByteString], nodeHash: ByteString): Unit =
    import com.chipprbots.ethereum.mpt.{MptTraversals, BranchNode, ExtensionNode, HashNode, LeafNode}
    import com.chipprbots.ethereum.mpt.HexPrefix
    import com.chipprbots.ethereum.domain.Account
    import scala.util.control.NonFatal

    if pathset.nonEmpty then
      try
        val decoded = MptTraversals.decodeNode(nodeData.toArray)
        val parentCompact = pathset.last.toArray
        val parentNibbles = HexPrefix.decode(parentCompact)._1
        val isStorageTrie = pathset.size > 1 // Seq(accountHash, path) vs Seq(path)

        val newEntries = mutable.Buffer.empty[HealingEntry]

        decoded match
          case branch: BranchNode =>
            // The path a child at branch slot `i` is queued, fetched and (Path scheme) stored under.
            def childPathsetAt(i: Int): Seq[ByteString] =
              val childNibbles = parentNibbles :+ i.toByte
              val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
              if isStorageTrie then Seq(pathset.head, childCompact) else Seq(childCompact)

            // Collect all non-pending HashNode children, then check storage for them.
            val toCheck = branch.children.zipWithIndex
              .collect { case (hn: HashNode, i) =>
                (i, ByteString(hn.hashNode))
              }
              .filterNot { case (_, h) => pendingHashSet.contains(h) }
            if toCheck.nonEmpty then
              // Hash scheme: one batched content-addressed multiGetNodes call (unchanged). Path scheme: each child is
              // looked up at its OWN path — a hash-keyed lookup can never find a Path-scheme node, which made every
              // child look missing and re-queued the whole trie below the walk root on every re-peg.
              val present: Seq[Boolean] = storageScheme match
                case StorageScheme.Hash =>
                  mptStorage.multiGetNodes(toCheck.map(_._2.toArray)).map(_.isDefined)
                case StorageScheme.Path =>
                  toCheck.toSeq.map { case (i, childHash) => isNodeInStorage(childHash, childPathsetAt(i)) }
              toCheck.zip(present).foreach { case ((i, childHash), isPresent) =>
                if !isPresent then newEntries += HealingEntry(childPathsetAt(i), childHash)
              }

          case ext: ExtensionNode =>
            ext.next match
              case hash: HashNode =>
                val childHash = ByteString(hash.hashNode)
                if !pendingHashSet.contains(childHash) then
                  val childNibbles = parentNibbles ++ ext.sharedKey.toArray
                  val childCompact = ByteString(HexPrefix.encode(childNibbles, isLeaf = false))
                  val childPathset = if isStorageTrie then Seq(pathset.head, childCompact) else Seq(childCompact)
                  val present = storageScheme match
                    case StorageScheme.Hash =>
                      mptStorage.multiGetNodes(Seq(childHash.toArray)).headOption.flatten.isDefined
                    case StorageScheme.Path =>
                      isNodeInStorage(childHash, childPathset)
                  if !present then newEntries += HealingEntry(childPathset, childHash)
              case _ => // Already inline-encoded — no missing child
          case leaf: LeafNode if !isStorageTrie =>
            // ARCH-LEAF-SEED: Account trie leaf — decode account, seed storage trie if missing.
            // Besu equivalent: getChildRequests() → getStorageTrieNodeRequests() on account leaf values.
            Account(leaf.value).foreach { account =>
              recordHealedCodeHash(account)
              if account.storageRoot != Account.EmptyStorageRootHash &&
                !pendingHashSet.contains(account.storageRoot.value)
              then
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
                  // Presence is checked here, once the account hash — hence the storage root's path — is known. Hash
                  // scheme: content-addressed lookup by hash, exactly as before (the pathset is ignored). Path scheme:
                  // the storage-trie root lives at (accountHash, empty path) and is checked THERE.
                  if !isNodeInStorage(account.storageRoot.value, Seq(accountHash, emptyStoragePath)) then
                    newEntries += HealingEntry(Seq(accountHash, emptyStoragePath), account.storageRoot.value)
                    log.debug(
                      s"[HEAL-LEAF] Seeded storage trie root ${Hex.toHexString(account.storageRoot.value.take(4).toArray)} " +
                        s"for account ${Hex.toHexString(accountHashBytes.take(4))}"
                    )
            }

          case _ => // storage trie LeafNode, NullNode, HashNode — no children to discover
        if newEntries.nonEmpty then
          newEntries.foreach(e => pendingHashSet += e.hash)
          pendingTasks ++= newEntries
          persistFrontier(newEntries.toSeq) // Layer 2: inline-discovered children are new frontier entries
          childrenDiscoveredTotal += newEntries.size
          if childrenDiscoveredTotal % 100 == 0 || childrenDiscoveredTotal <= 20 then
            log.info(
              s"[HEAL-DISCOVER] Inline children queued: $childrenDiscoveredTotal total " +
                s"(+${newEntries.size} from this node, pending: ${pendingTasks.size})"
            )

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
        // before the flush drops the in-memory candidate ⇒ safe descend. The `newEntries.isEmpty` pre-gate is a cheap
        // necessary short-circuit (a found missing non-pending child means not closed); the forall over actual
        // storage+record state is the AUTHORITATIVE gate and can never false-record regardless of pending/filter state.
        if prunedEnabled && newEntries.isEmpty then
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
      catch
        case NonFatal(e) =>
          log.debug(
            s"[HEAL] Cannot decode healed node for child discovery: ${e.getMessage}. " +
              s"Skipping — trie walk will find these nodes."
          )

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
