package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Account.*
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

/** Server-side SNAP/1 helpers — Merkle Patricia Trie range traversal and Merkle proof generation for incoming
  * `GetAccountRange`, `GetStorageRanges`, and `GetTrieNodes` requests from peers.
  *
  * The traversal walks the trie in nibble (key) order and yields `(keyHash, valueRLP)` pairs strictly between the
  * requested `[origin, limit]` bounds, stopping once the accumulated response size exceeds `responseBytes` (per SNAP/1
  * spec, the server may truncate the response prefix early — but must always emit at least one item if any exists in
  * the range).
  *
  * For each non-empty range we also emit a proof: the path from the root to the first returned key, and (if more than
  * one item is emitted) the path from the root to the last returned key. These let the requester rebuild a partial trie
  * and verify the range is contiguous against the claimed root hash.
  */
object SnapServer extends Logger:

  /** Convert a 32-byte hash into its 64-nibble key path. */
  def hashToNibbles(hash: ByteString): Array[Byte] =
    val out = new Array[Byte](hash.size * 2)
    var i = 0
    while i < hash.size do
      val b = hash(i) & 0xff
      out(i * 2) = ((b >>> 4) & 0x0f).toByte
      out(i * 2 + 1) = (b & 0x0f).toByte
      i += 1
    out

  /** Convert a 64-nibble key path back to a 32-byte hash. Returns None if length is odd. */
  def nibblesToHash(nibbles: Array[Byte]): Option[ByteString] =
    if nibbles.length % 2 != 0 then None
    else
      val out = new Array[Byte](nibbles.length / 2)
      var i = 0
      while i < out.length do
        out(i) = (((nibbles(i * 2) & 0x0f) << 4) | (nibbles(i * 2 + 1) & 0x0f)).toByte
        i += 1
      Some(ByteString(out))

  /** Compare two nibble arrays lexicographically (treating each nibble as a digit 0..15). */
  private def cmpNibbles(a: Array[Byte], b: Array[Byte]): Int =
    val len = math.min(a.length, b.length)
    var i = 0
    var diff = 0
    while i < len && diff == 0 do
      diff = (a(i) & 0xff) - (b(i) & 0xff)
      i += 1
    if diff != 0 then diff else a.length - b.length

  private def isEmptyRoot(root: ByteString): Boolean =
    root.toArray.sameElements(MerklePatriciaTrie.EmptyRootHash)

  // ─── Where a read is (spec 018 Track C) ──────────────────────────────────────────────────────────────────────────
  //
  // Every read here is reached by a descent from a root, so every read can say where it is: which trie, and the path
  // from that trie's root. `nodes` below is always the storage of ONE trie (`storage.forOwner`): the account trie
  // under no owner, a storage trie under its account's hash. A store keyed by hash ignores both; a store keyed by
  // position cannot find a node without them.

  /** Resolve a node (which may be a HashNode pointer) into its concrete form. `at` is the node's path from the root of
    * the trie `nodes` is the storage of.
    */
  private def resolve(node: MptNode, nodes: MptStorage, at: => Array[Byte]): MptNode = node match
    case HashNode(hash) => orAbsent(nodes.getAt(NibblePath.fromNibbles(at), hash))
    case other          => other

  /** Read the root node of the trie `nodes` is the storage of, by its hash. A root that cannot be read is
    * [[Unservable]], as in go-ethereum, where a missing root answers the whole request with nothing.
    */
  private def fetchRootNode(rootHash: ByteString, nodes: MptStorage): MptNode =
    orAbsent(nodes.getAt(NibblePath.Empty, rootHash.toArray))

  /** A node of a trie this server serves that cannot be read: missing (pruned, or never synced), corrupt (stored bytes
    * that are not the node their key names), or a store that failed. Served as a hole in a range, it would leave out a
    * subtree its proofs cannot close over, and the peer finds the response invalid (B-213). go-ethereum answers a range
    * request it cannot serve whole with an empty response (`ServiceGetAccountRangeQuery` and
    * `ServiceGetStorageRangesQuery` return `nil, nil` when a proof cannot be built; `ServiceGetTrieNodesQuery` does so
    * for a missing root and skips only the single node it cannot get). [[notServed]] makes the same answer here;
    * `serveTrieNodes` skips per node.
    */
  final class Unservable(why: String, cause: Throwable = null) extends RuntimeException(why, cause, false, false)

  /** A node that cannot be read is [[Unservable]]. A fault of the state itself is not taken for one: it is thrown on,
    * and [[notServed]] answers the request all the same. A corrupt node is said once (its hash, as the store says it);
    * a missing one is not, since pruned state is normal.
    */
  private def orAbsent(read: => MptNode): MptNode =
    try read
    catch
      case fault: StateFault                                => throw fault
      case corrupt: MerklePatriciaTrie.CorruptNodeException =>
        // The store says a corrupt node itself once, when it is the one that reads it; the path store does not.
        if SerializingMptStorage.firstReport(corrupt.hash) then
          log.error(
            "[STATE-CORRUPT] SNAP serve: stored trie node {} is not that node ({}); every request that needs it is " +
              "answered with nothing until it is deleted or replaced",
            Hex.toHexString(corrupt.hash.toArray),
            corrupt.detail
          )
        throw new Unservable(s"corrupt node ${Hex.toHexString(corrupt.hash.toArray)}", corrupt)
      case missing: MerklePatriciaTrie.MissingNodeException =>
        throw new Unservable(s"missing node ${Hex.toHexString(missing.hash.toArray)}", missing)
      case NonFatal(other) =>
        log.warn("SNAP serve: a node could not be read, the request is answered with nothing: {}", other.toString)
        throw new Unservable(other.toString, other)

  /** `serve`, or `nothing` when the state it reads stopped being this node's to serve while it was read, or a node of
    * it cannot be read: the empty response, which says "this state is not here", and never the part that was read
    * before.
    */
  private def notServed[A](request: String, nothing: => A)(serve: => A): A =
    try serve
    catch
      case fault: StateFault =>
        log.debug("SNAP {}: answered with nothing, the state is no longer served: {}", request, fault.getMessage)
        nothing
      case unservable: Unservable =>
        log.debug("SNAP {}: answered with nothing, {}", request, unservable.getMessage)
        nothing

  /** One node of a `GetTrieNodes` request that cannot be read is left out of the answer, as go-ethereum does (`GetNode`
    * fails, the node is not appended); the request goes on.
    */
  private def orSkip[A](find: => Option[A]): Option[A] =
    try find
    catch case _: Unservable => None

  /** The storage root of an account of the state at `rootHash`, by the account's hash: `None` when that state is not
    * here, or has no such account. What `serveStorageRanges` is given as `accountRoot`.
    *
    * The lookup throws a [[StateFault]] when the disk has moved away from the state it reads. A caller that wraps it
    * must let that through: "no such account" would be sent as an account with no storage.
    */
  def storageRootOf(rootHash: ByteString, storage: MptStorage): ByteString => Option[ByteString] =
    val accounts = storage.forOwner(None)
    lazy val rootNode = if isEmptyRoot(rootHash) then NullNode else fetchRootNode(rootHash, accounts)
    accountHash =>
      if rootNode == NullNode then None
      else resolveLeafAccount(rootNode, accounts, hashToNibbles(accountHash)).map(_.storageRoot.value)

  /** Slim-account RLP element per geth's snap protocol convention: the storageRoot and codeHash fields are encoded as
    * empty bytes when they equal the canonical empty-trie root and empty-code hash respectively. Saves ~64 bytes per
    * EOA — critical for the SNAP byte-budget which counts the leaf body length toward the soft response limit.
    *
    * Our `AccountImplicits.toAccount` decoder already normalises empty bytes back to the canonical defaults, so
    * round-trips are lossless across our own and geth's clients.
    *
    * Returns the parsed RLP element so callers can either embed it (in `AccountRange`'s RLP envelope) or serialise it
    * (`rlp.encode(toSlimAccountRlp(...))`) for byte-budget accounting.
    */
  def toSlimAccountRlp(account: Account): RLPList =
    val nonceRlp: RLPEncodeable = RLPValue(ByteUtils.bigIntToUnsignedByteArray(account.nonce))
    val balanceRlp: RLPEncodeable = RLPValue(ByteUtils.bigIntToUnsignedByteArray(account.balance))
    val srRlp: RLPEncodeable =
      if account.storageRoot == Account.EmptyStorageRootHash then RLPValue(Array.emptyByteArray)
      else RLPValue(account.storageRoot.toArray)
    val chRlp: RLPEncodeable =
      if account.codeHash == Account.EmptyCodeHash then RLPValue(Array.emptyByteArray)
      else RLPValue(account.codeHash.value.toArray)
    RLPList(nonceRlp, balanceRlp, srRlp, chRlp)

  /** Range walker — yields (keyHash, leafValue) pairs whose key falls in [originNibbles, limitNibbles] (inclusive on
    * both ends), traversing the trie in key order. Caller stops consuming when their byte budget is exceeded.
    *
    * Pruning: for a partial nibble prefix `p` of length L, the minimum reachable full key is `p ++ 0×(N-L)` and the
    * maximum is `p ++ F×(N-L)` (where N is the full key length in nibbles, 64 for an account hash). We skip a subtree
    * only when its max < origin OR its min > limit.
    */
  private val FullKeyNibbles = 64

  private def maxKeyWith(prefix: Array[Byte]): Array[Byte] =
    prefix ++ Array.fill(math.max(0, FullKeyNibbles - prefix.length))(15.toByte)

  /** Subtree-prune predicate. We only skip subtrees that lie strictly BELOW `origin` (i.e. their max key is < origin).
    * Subtrees ABOVE `limit` are intentionally not pruned — the visitor's stop-on-`>=limit` rule needs to see the first
    * leaf past the limit (geth's serveAccountRange does the same: it iterates past the limit, `break`s after emitting
    * one). Limit-side pruning would cut off that extra leaf and undercount in any range whose first available key
    * extends past `limit`.
    */
  private def subtreeIntersectsRange(
      prefix: Array[Byte],
      originNibbles: Array[Byte],
      @scala.annotation.unused limitNibbles: Array[Byte]
  ): Boolean =
    cmpNibbles(maxKeyWith(prefix), originNibbles) >= 0

  /** Visit-style range walker — calls `visit(keyHash, valueRLP)` for every leaf whose key falls in `[origin, limit]`,
    * traversing the trie in key order. The visitor returns `true` to continue or `false` to stop the walk early (used
    * by the byte-budget gate).
    *
    * Iterative-style traversal via direct recursion (no Iterator.flatMap chain) — much cheaper than the previous lazy
    * iterator construction when the chain depth is 64.
    */
  private def walkRangeVisit(
      root: MptNode,
      storage: MptStorage,
      originNibbles: Array[Byte],
      limitNibbles: Array[Byte]
  )(visit: (ByteString, ByteString) => Boolean): Unit =
    var stop = false

    def descend(node: MptNode, prefix: Array[Byte]): Unit =
      if !stop && subtreeIntersectsRange(prefix, originNibbles, limitNibbles) then
        resolve(node, storage, prefix) match
          case NullNode                      => ()
          case LeafNode(key, value, _, _, _) =>
            // Geth semantics (eth/protocols/snap/handler.go:304-322): emit any leaf
            // whose key is `>= origin`, then `break` after emitting one with key
            // `>= limit`. This naturally handles the single-key (start == limit) case
            // (returns the matching leaf, then stops) and the "first at-or-after"
            // semantics for keys that don't exist.
            val fullKey = prefix ++ key.toArray
            if cmpNibbles(fullKey, originNibbles) >= 0 then
              nibblesToHash(fullKey).foreach { h =>
                val keep = visit(h, value)
                val pastLimit = cmpNibbles(fullKey, limitNibbles) >= 0
                if !keep || pastLimit then stop = true
              }
          case ExtensionNode(sharedKey, next, _, _, _) =>
            descend(next, prefix ++ sharedKey.toArray)
          case BranchNode(children, terminator, _, _, _) =>
            terminator.foreach { value =>
              if cmpNibbles(prefix, originNibbles) >= 0 then
                nibblesToHash(prefix).foreach { h =>
                  val keep = visit(h, value)
                  val pastLimit = cmpNibbles(prefix, limitNibbles) >= 0
                  if !keep || pastLimit then stop = true
                }
            }
            var nibble = 0
            while nibble < 16 && !stop do
              val child = children(nibble)
              if child != NullNode then descend(child, prefix :+ nibble.toByte)
              nibble += 1
          case _: HashNode => () // resolve() above prevents reaching here
    // if !stop && subtreeIntersectsRange

    descend(root, Array.emptyByteArray)

  /** Collect the proof path for a given key — the nodes traversed from root to the leaf (or the deepest reachable node
    * along the path if the key is absent). The proof is returned as a sequence of RLP-encoded MPT nodes (with each
    * node's keccak hash available to the caller for de-duplication).
    */
  private def proofFor(
      root: MptNode,
      storage: MptStorage,
      keyNibbles: Array[Byte]
  ): Seq[ByteString] =
    val acc = scala.collection.mutable.ArrayBuffer.empty[ByteString]

    // `walked` is how many nibbles of the key lead to `node`: its path is the key's first `walked` nibbles.
    def descend(node: MptNode, walked: Int): Unit =
      val remaining = keyNibbles.drop(walked)
      val resolved = resolve(node, storage, keyNibbles.take(walked))
      resolved match
        case NullNode => ()
        case _ =>
          acc += ByteString(MptTraversals.encodeNode(resolved))
          resolved match
            case LeafNode(_, _, _, _, _) => ()
            case ExtensionNode(sharedKey, next, _, _, _) =>
              val sk = sharedKey.toArray
              if remaining.length >= sk.length && remaining.take(sk.length).sameElements(sk) then
                descend(next, walked + sk.length)
            case BranchNode(children, _, _, _, _) =>
              if remaining.nonEmpty then
                val nibble = remaining(0) & 0x0f
                val child = children(nibble)
                if child != NullNode then descend(child, walked + 1)
            case _ => ()

    descend(root, 0)
    acc.toSeq

  /** Build an `AccountRange` response by walking the state trie at `rootHash` between `startingHash` and `limitHash`,
    * emitting accounts whose RLP totals up to (but generally not exceeding) `responseBytes`. Always emits at least one
    * account if any fall in the range.
    */
  def serveAccountRange(
      requestId: BigInt,
      rootHash: ByteString,
      startingHash: ByteString,
      limitHash: ByteString,
      responseBytes: BigInt,
      storage: MptStorage
  ): AccountRange =
    if isEmptyRoot(rootHash) then AccountRange(requestId, Seq.empty, Seq.empty)
    else
      notServed("GetAccountRange", AccountRange(requestId, Seq.empty, Seq.empty)):
        val accounts = storage.forOwner(None)
        val rootNode = fetchRootNode(rootHash, accounts)
        if rootNode == NullNode then
          log.debug("SNAP serveAccountRange: root {} not in storage", rootHash.take(4))
          AccountRange(requestId, Seq.empty, Seq.empty)
        else

          // SNAP "wrong-order" handling: when startingHash > limitHash hive's tests expect us
          // to return the FIRST available key at/after `startingHash`. With the geth-style
          // emit-on->=origin / stop-on->=limit semantics now in place, widening `limit` to
          // FF…FF gets us "first key at-or-after start" naturally — the walker emits one then
          // stops because the next key is >= origin >= limit.
          val isReversed =
            val s = startingHash.toArray
            val l = limitHash.toArray
            var i = 0
            var ord = 0
            while i < s.length && i < l.length && ord == 0 do
              ord = (s(i) & 0xff) - (l(i) & 0xff)
              i += 1
            ord > 0
          val effectiveLimit = if isReversed then ByteString(Array.fill[Byte](32)(0xff.toByte)) else limitHash
          val originNibbles = hashToNibbles(startingHash)
          val limitNibbles = hashToNibbles(effectiveLimit)
          val maxBytes = responseBytes.min(BigInt(2 * 1024 * 1024)).max(BigInt(0)).toInt
          val deadline = System.currentTimeMillis() + 4000

          val collected =
            scala.collection.mutable.ArrayBuffer.empty[(ByteString, com.chipprbots.ethereum.domain.Account)]
          var accumulated = 0
          // Visit-style walk: visitor returns false to stop traversal as soon as the byte
          // budget or time budget is hit. Match go-ethereum's accounting: only (hash + slim-leaf bytes) count
          // toward the budget — slim format is what we'll emit on the wire (see
          // `toSlimAccountRlp`). Proofs aren't counted (they're a separate response header).
          walkRangeVisit(rootNode, accounts, originNibbles, limitNibbles) { (keyHash, accountRlp) =>
            val account = accountRlp.toArray.toAccount
            val slimSize = rlp.encode(toSlimAccountRlp(account)).length
            collected += ((keyHash, account))
            accumulated += keyHash.size + slimSize
            // Wrong-order requests: stop after a single item. Otherwise continue while under
            // budget; the first item is always emitted (the visitor only sees this branch
            // after we add to `collected`).
            if isReversed then false
            else accumulated < maxBytes && System.currentTimeMillis() < deadline
          }

          // Build proof per SNAP/1 spec (geth eth/protocols/snap/handler.go:336-356):
          //   - Left bound proof: path to startingHash (regardless of whether a leaf exists at that
          //     key). This proves the gap between startingHash and the first emitted leaf — the
          //     client uses it to verify the response is the contiguous left edge.
          //   - Right bound proof: path to the last emitted key, but only when at least one account
          //     was emitted AND the response was truncated (otherwise the right edge is implicit).
          //   - When zero accounts emitted, only the left-bound proof is sent.
          val proof: Seq[ByteString] =
            val leftProof = proofFor(rootNode, accounts, hashToNibbles(startingHash))
            collected.lastOption match
              case None => leftProof // empty range — left proof alone proves absence
              case Some((lastKey, _)) =>
                val lastNibbles = hashToNibbles(lastKey)
                val startNibbles = hashToNibbles(startingHash)
                if lastNibbles.sameElements(startNibbles) then leftProof
                else leftProof ++ proofFor(rootNode, accounts, lastNibbles)
          // De-duplicate proof nodes (some appear on both paths).
          val dedupedProof = proof.distinct

          AccountRange(requestId, collected.toSeq, dedupedProof)
        // else rootNode != NullNode
  // else !isEmptyRoot

  /** Build a `StorageRanges` response — geth's `ServiceGetStorageRangesQuery` (eth/protocols/snap/handlers.go):
    *   - the request's origin and limit apply to the FIRST account only; later accounts are walked in full;
    *   - a proof is attached only when the served range does not cover the whole trie: a non-zero origin, or a range
    *     capped by the byte budget, the deadline or the limit hash. It proves the ORIGIN (left edge) and the last slot
    *     returned (right edge);
    *   - the proved account ENDS the reply. A client verifies the proof against the last slot-set it receives (geth
    *     sync.go, and fukuii's own StorageResponseProcessing), so a proof followed by more accounts makes the client
    *     check one account's slots against another account's proof. Before B-566 the first account was always proved
    *     and later accounts were still appended: go-ethereum and nethermind rejected those replies ("invalid proof,
    *     want hash …, got …") and dropped the peer;
    *   - an account whose storage root is unknown here, or whose root node is missing, ends the reply (geth refuses the
    *     request): an empty slot-set would tell the client that the account has no storage.
    */
  def serveStorageRanges(
      requestId: BigInt,
      rootHash: ByteString,
      accountHashes: Seq[ByteString],
      startingHash: ByteString,
      limitHash: ByteString,
      responseBytes: BigInt,
      storage: MptStorage,
      accountRoot: ByteString => Option[ByteString]
  ): StorageRanges =
    if isEmptyRoot(rootHash) then StorageRanges(requestId, Seq.empty, Seq.empty)
    else
      notServed("GetStorageRanges", StorageRanges(requestId, Seq.empty, Seq.empty)):
        val maxBytes = responseBytes.min(BigInt(2 * 1024 * 1024)).max(BigInt(0)).toInt
        val deadline = System.currentTimeMillis() + 4000
        val zeroHash = ByteString(new Array[Byte](32))
        val maxHash = ByteString(Array.fill[Byte](32)(0xff.toByte))
        var accumulated = 0
        val perAccount = scala.collection.mutable.ArrayBuffer.empty[Seq[(ByteString, ByteString)]]
        var proof: Seq[ByteString] = Seq.empty
        var done = false
        var isFirst = true
        val it = accountHashes.iterator
        // Like geth, do not open a new storage range once the budget is spent.
        while it.hasNext && !done && accumulated < maxBytes && System.currentTimeMillis() < deadline do
          val accountHash = it.next()
          val origin = if isFirst then startingHash else zeroHash
          val limit = if isFirst then limitHash else maxHash
          isFirst = false
          accountRoot(accountHash) match
            case None                                          => done = true
            case Some(storageRoot) if isEmptyRoot(storageRoot) => perAccount += Seq.empty
            case Some(storageRoot)                             =>
              // this account's storage trie: two accounts with the same storage hold two tries
              val slots = storage.forOwner(Some(accountHash))
              val rootNode = fetchRootNode(storageRoot, slots)
              if rootNode == NullNode then done = true
              else
                val limitN = hashToNibbles(limit)
                val collected = scala.collection.mutable.ArrayBuffer.empty[(ByteString, ByteString)]
                var capped = false
                // Match geth byte accounting (handlers.go): only `HashLength + len(slot)` counts toward the budget.
                walkRangeVisit(rootNode, slots, hashToNibbles(origin), limitN) { (k, v) =>
                  collected += ((k, v))
                  accumulated += k.size + v.size
                  if cmpNibbles(hashToNibbles(k), limitN) >= 0 then capped = true
                  val keep = accumulated < maxBytes && System.currentTimeMillis() < deadline
                  if !keep then capped = true
                  keep
                }
                if collected.nonEmpty then perAccount += collected.toSeq
                if origin != zeroHash || (capped && collected.nonEmpty) then
                  val left = proofFor(rootNode, slots, hashToNibbles(origin))
                  val right = collected.lastOption match
                    case Some((last, _)) => proofFor(rootNode, slots, hashToNibbles(last))
                    case None            => Seq.empty
                  proof = (left ++ right).distinct
                  done = true

        StorageRanges(requestId, perAccount.toSeq, proof)
  // else !isEmptyRoot

  /** Build a `TrieNodes` response — look up each requested HP-encoded path from `rootHash` and return the raw
    * RLP-encoded node found at that path. Missing nodes yield empty bytes per SNAP/1 spec.
    */
  def serveTrieNodes(
      requestId: BigInt,
      rootHash: ByteString,
      paths: Seq[Seq[ByteString]],
      responseBytes: BigInt,
      storage: MptStorage
  ): TrieNodes =
    val maxBytes = responseBytes.min(BigInt(2 * 1024 * 1024)).max(BigInt(0)).toInt
    val deadline = System.currentTimeMillis() + 4000
    var accumulated: Int = 0

    // Per geth (handler.go:522-525), a zero-item pathset anywhere in the request
    // is a protocol-level bad request — the whole response is empty.
    if paths.exists(_.isEmpty) then TrieNodes(requestId, Seq.empty)
    else
      notServed("GetTrieNodes", TrieNodes(requestId, Seq.empty)):
        val accounts = storage.forOwner(None)
        val rootNode = fetchRootNode(rootHash, accounts)
        val collected = scala.collection.mutable.ArrayBuffer.empty[ByteString]

        if rootNode == NullNode then
          // Root not found — return empty (sparse), matching go-ethereum's handler.go behaviour.
          TrieNodes(requestId, Seq.empty)
        else
          // Peer-supplied: read by index from an indexed copy. Decoded RLP lists are Vectors now; the decoder's old Queue
          // made paths(idx) O(idx) and this loop quadratic (#1672/#1675).
          val indexedPaths = paths.toIndexedSeq
          var idx = 0
          while idx < indexedPaths.size && (accumulated < maxBytes || collected.isEmpty) &&
            System.currentTimeMillis() < deadline
          do
            val pathSet = indexedPaths(idx)
            if pathSet.size == 1 then
              // Single-element path: account-trie node lookup (HP-encoded partial path).
              val nibbles = decodeHpPath(pathSet.head.toArray)
              orSkip(collectNodeAtPath(rootNode, accounts, nibbles)) match
                case Some(enc) =>
                  collected += enc; accumulated += enc.size
                case None =>
                  collected += ByteString.empty; accumulated += 1
            else
              // Multi-element path per geth handler.go:521-577 — pathSet(0) is the
              // account HASH (raw 32 bytes, used with GetAccountByHash); pathSet(1..)
              // are HP-encoded storage-trie paths inside that account's storage trie,
              // which is read under that account.
              val accountNibbles = hashToNibbles(pathSet.head)
              val storageNibblesList = pathSet.tail
              orSkip(resolveLeafAccount(rootNode, accounts, accountNibbles)) match
                case Some(account) if account.storageRoot != Account.EmptyStorageRootHash =>
                  val slots = storage.forOwner(Some(pathSet.head))
                  val storageRootNode =
                    try fetchRootNode(account.storageRoot.value, slots)
                    catch case _: Unservable => NullNode
                  if storageRootNode != NullNode then
                    storageNibblesList.foreach { storagePath =>
                      if accumulated < maxBytes || collected.isEmpty then
                        val sn = decodeHpPath(storagePath.toArray)
                        orSkip(collectNodeAtPath(storageRootNode, slots, sn)) match
                          case Some(enc) =>
                            collected += enc; accumulated += enc.size
                          case None =>
                            collected += ByteString.empty; accumulated += 1
                    }
                  else {
                    // Storage root not in our DB — skip (sparse), matching go-ethereum.
                    // TrieNodeHealingCoordinator matches by keccak256 hash, not position,
                    // so sparse responses are handled correctly.
                  }
                // Account missing or has no storage — skip (sparse), matching go-ethereum.
                case _ =>
            idx += 1
          TrieNodes(requestId, collected.toSeq)
    // else paths.nonEmpty

  /** Walk the state trie following `nibbles` to a leaf and decode that leaf's value as an `Account`. Returns None if
    * the path doesn't terminate at a leaf or the leaf isn't a valid account RLP.
    */
  private def resolveLeafAccount(
      root: MptNode,
      storage: MptStorage,
      nibbles: Array[Byte]
  ): Option[Account] =

    def descend(node: MptNode, walked: Int): Option[Account] =
      val remaining = nibbles.drop(walked)
      val resolved = resolve(node, storage, nibbles.take(walked))
      resolved match
        case NullNode => None
        case LeafNode(key, value, _, _, _) =>
          val k = key.toArray
          if remaining.sameElements(k) then
            try Some(value.toArray.toAccount)
            catch case _: Throwable => None
          else None
        case ExtensionNode(sharedKey, next, _, _, _) =>
          val sk = sharedKey.toArray
          if remaining.length >= sk.length && remaining.take(sk.length).sameElements(sk) then
            descend(next, walked + sk.length)
          else None
        case BranchNode(children, terminator, _, _, _) =>
          if remaining.isEmpty then
            // The account sits in the branch terminator slot.
            terminator.flatMap { v =>
              try Some(v.toArray.toAccount)
              catch case _: Throwable => None
            }
          else
            val nibble = remaining(0) & 0x0f
            val child = children(nibble)
            if child == NullNode then None else descend(child, walked + 1)
        case _: HashNode => None

    descend(root, 0)

  /** Decode an HP-encoded (Hex Prefix, EIP-2 / yellow-paper) path back to its nibble representation. Drops the
    * leaf/extension flag bit.
    */
  private def decodeHpPath(bytes: Array[Byte]): Array[Byte] =
    if bytes.isEmpty then Array.emptyByteArray
    else
      val firstByte = bytes(0) & 0xff
      val oddLen = (firstByte & 0x10) != 0
      val skipFirst = if oddLen then 0 else 1
      val firstNibble = if oddLen then Array((firstByte & 0x0f).toByte) else Array.empty[Byte]
      val rest = bytes.drop(1).flatMap { b =>
        Array(((b >>> 4) & 0x0f).toByte, (b & 0x0f).toByte)
      }
      firstNibble ++ rest.drop(skipFirst - 1).take(rest.length - (skipFirst - 1))

  /** Serve an incoming `GetByteCodes` request (SNAP/1).
    *
    * Looks up each requested code hash in `storage`, collecting bytecodes until the 2 MB byte budget is exhausted or
    * all hashes have been processed. At most 1024 hashes are processed regardless of request size (go-ethereum
    * `maxCodeLookups` defence). The empty-code hash (`Account.EmptyCodeHash`) is returned as `ByteString.empty` without
    * a DB lookup, matching go-ethereum's `handlers.go:360-361` special case. Missing hashes are silently skipped (not
    * inserted as empty placeholders) — there is no positional alignment requirement for bytecodes.
    */
  def serveByteCodes(
      requestId: BigInt,
      hashes: Seq[ByteString],
      responseBytes: BigInt,
      storage: EvmCodeStorage
  ): ByteCodes =
    val maxBytes = responseBytes.min(BigInt(2 * 1024 * 1024)).max(BigInt(0)).toInt
    val collected = scala.collection.mutable.ListBuffer.empty[ByteString]
    var totalBytes = 0
    val it = hashes.take(1024).iterator
    while it.hasNext && (totalBytes < maxBytes || collected.isEmpty) do
      val codeHash = it.next()
      if codeHash == Account.EmptyCodeHash.value then
        collected += ByteString.empty
        // empty code contributes 0 bytes; do not count toward budget
      else
        storage.get(codeHash).foreach { code =>
          collected += code
          totalBytes += code.size
        }
    ByteCodes(requestId = requestId, codes = collected.toList)

  /** Serve an incoming `GetAccessLists` request (snap/2 only — EIP-8189).
    *
    * fukuii has no EIP-7928 block-access-list storage, so every requested hash gets the RLP empty-string sentinel
    * (`0x80`) at its position — the honest "unavailable" answer per EIP-8189 (an empty *list* is itself a valid,
    * distinguishable BAL, so a skipped position is not an option). Positional order matches the request 1:1. Shares the
    * same response contract as ETH71's `GetBlockAccessLists` (BlockchainHostActor.handleBlockFastDownload) — both will
    * start returning real BALs the moment BAL storage exists, with no wire-format change on either side.
    */
  def serveAccessLists(requestId: BigInt, hashes: Seq[ByteString]): AccessLists =
    AccessLists(requestId, hashes.map(_ => RLPValue(Array.emptyByteArray)))

  /** Walk the trie following `nibbles` and return the encoded node found at that exact path (as a raw MPT node), or
    * None if the path doesn't terminate cleanly at a node.
    */
  private def collectNodeAtPath(
      root: MptNode,
      storage: MptStorage,
      nibbles: Array[Byte]
  ): Option[ByteString] =

    def descend(node: MptNode, walked: Int): Option[ByteString] =
      val remaining = nibbles.drop(walked)
      val resolved = resolve(node, storage, nibbles.take(walked))
      if remaining.isEmpty then Some(ByteString(MptTraversals.encodeNode(resolved)))
      else
        resolved match
          case NullNode => None
          case LeafNode(key, _, _, _, _) =>
            val k = key.toArray
            if remaining.sameElements(k) then Some(ByteString(MptTraversals.encodeNode(resolved)))
            else None
          case ExtensionNode(sharedKey, next, _, _, _) =>
            val sk = sharedKey.toArray
            if remaining.length >= sk.length && remaining.take(sk.length).sameElements(sk) then
              descend(next, walked + sk.length)
            else None
          case BranchNode(children, _, _, _, _) =>
            val nibble = remaining(0) & 0x0f
            val child = children(nibble)
            if child == NullNode then None
            else descend(child, walked + 1)
          case _: HashNode => None // resolve handles

    descend(root, 0)
