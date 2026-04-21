package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.Logger

/** Server-side SNAP/1 helpers — Merkle Patricia Trie range traversal and Merkle proof
  * generation for incoming `GetAccountRange`, `GetStorageRanges`, and `GetTrieNodes`
  * requests from peers.
  *
  * The traversal walks the trie in nibble (key) order and yields `(keyHash, valueRLP)`
  * pairs strictly between the requested `[origin, limit]` bounds, stopping once the
  * accumulated response size exceeds `responseBytes` (per SNAP/1 spec, the server may
  * truncate the response prefix early — but must always emit at least one item if any
  * exists in the range).
  *
  * For each non-empty range we also emit a proof: the path from the root to the first
  * returned key, and (if more than one item is emitted) the path from the root to the
  * last returned key. These let the requester rebuild a partial trie and verify the
  * range is contiguous against the claimed root hash.
  */
object SnapServer extends Logger {

  /** Convert a 32-byte hash into its 64-nibble key path. */
  def hashToNibbles(hash: ByteString): Array[Byte] = {
    val out = new Array[Byte](hash.size * 2)
    var i = 0
    while (i < hash.size) {
      val b = hash(i) & 0xff
      out(i * 2) = ((b >>> 4) & 0x0f).toByte
      out(i * 2 + 1) = (b & 0x0f).toByte
      i += 1
    }
    out
  }

  /** Convert a 64-nibble key path back to a 32-byte hash. Returns None if length is odd. */
  def nibblesToHash(nibbles: Array[Byte]): Option[ByteString] = {
    if (nibbles.length % 2 != 0) None
    else {
      val out = new Array[Byte](nibbles.length / 2)
      var i = 0
      while (i < out.length) {
        out(i) = (((nibbles(i * 2) & 0x0f) << 4) | (nibbles(i * 2 + 1) & 0x0f)).toByte
        i += 1
      }
      Some(ByteString(out))
    }
  }

  /** Compare two nibble arrays lexicographically (treating each nibble as a digit 0..15). */
  private def cmpNibbles(a: Array[Byte], b: Array[Byte]): Int = {
    val len = math.min(a.length, b.length)
    var i = 0
    while (i < len) {
      val cmp = (a(i) & 0xff) - (b(i) & 0xff)
      if (cmp != 0) return cmp
      i += 1
    }
    a.length - b.length
  }

  private def isEmptyRoot(root: ByteString): Boolean =
    root.toArray.sameElements(MerklePatriciaTrie.EmptyRootHash)

  /** Resolve a node (which may be a HashNode pointer) into its concrete form. */
  private def resolve(node: MptNode, storage: MptStorage): MptNode = node match {
    case HashNode(hash) =>
      try storage.get(hash)
      catch { case _: Throwable => NullNode }
    case other => other
  }

  /** Read the root node of a trie by its hash. Returns NullNode if missing. */
  private def fetchRootNode(rootHash: ByteString, storage: MptStorage): MptNode =
    try storage.get(rootHash.toArray)
    catch { case _: Throwable => NullNode }

  /** Slim-account RLP element per geth's snap protocol convention: the storageRoot and
    * codeHash fields are encoded as empty bytes when they equal the canonical empty-trie
    * root and empty-code hash respectively. Saves ~64 bytes per EOA — critical for the
    * SNAP byte-budget which counts the leaf body length toward the soft response limit.
    *
    * Our `AccountImplicits.toAccount` decoder already normalises empty bytes back to the
    * canonical defaults, so round-trips are lossless across our own and geth's clients.
    *
    * Returns the parsed RLP element so callers can either embed it (in `AccountRange`'s
    * RLP envelope) or serialise it (`rlp.encode(toSlimAccountRlp(...))`) for byte-budget
    * accounting.
    */
  def toSlimAccountRlp(account: Account): RLPList = {
    val nonceRlp: RLPEncodeable = RLPValue(ByteUtils.bigIntToUnsignedByteArray(account.nonce))
    val balanceRlp: RLPEncodeable = RLPValue(ByteUtils.bigIntToUnsignedByteArray(account.balance))
    val srRlp: RLPEncodeable =
      if (account.storageRoot == Account.EmptyStorageRootHash) RLPValue(Array.emptyByteArray)
      else RLPValue(account.storageRoot.toArray)
    val chRlp: RLPEncodeable =
      if (account.codeHash == Account.EmptyCodeHash) RLPValue(Array.emptyByteArray)
      else RLPValue(account.codeHash.toArray)
    RLPList(nonceRlp, balanceRlp, srRlp, chRlp)
  }

  /** RLP-encode a node and emit its keccak256 hash + encoded bytes. Used when collecting
    * proof nodes — peers verify by re-hashing each proof node and matching against
    * parent references.
    */
  private def encodeNodeWithHash(node: MptNode): (ByteString, ByteString) = {
    val encoded = MptTraversals.encodeNode(node)
    val hash = ByteString(kec256(encoded))
    (hash, ByteString(encoded))
  }

  /** Range walker — yields (keyHash, leafValue) pairs whose key falls in
    * [originNibbles, limitNibbles] (inclusive on both ends), traversing the trie in key
    * order. Caller stops consuming when their byte budget is exceeded.
    *
    * Pruning: for a partial nibble prefix `p` of length L, the minimum reachable full key
    * is `p ++ 0×(N-L)` and the maximum is `p ++ F×(N-L)` (where N is the full key length
    * in nibbles, 64 for an account hash). We skip a subtree only when its max < origin
    * OR its min > limit.
    */
  private val FullKeyNibbles = 64

  private def maxKeyWith(prefix: Array[Byte]): Array[Byte] =
    prefix ++ Array.fill(math.max(0, FullKeyNibbles - prefix.length))(15.toByte)

  private def minKeyWith(prefix: Array[Byte]): Array[Byte] =
    prefix ++ Array.fill(math.max(0, FullKeyNibbles - prefix.length))(0.toByte)

  /** Subtree-prune predicate. We only skip subtrees that lie strictly BELOW `origin`
    * (i.e. their max key is < origin). Subtrees ABOVE `limit` are intentionally not
    * pruned — the visitor's stop-on-`>=limit` rule needs to see the first leaf past the
    * limit (geth's serveAccountRange does the same: it iterates past the limit,
    * `break`s after emitting one). Limit-side pruning would cut off that extra leaf
    * and undercount in any range whose first available key extends past `limit`.
    */
  private def subtreeIntersectsRange(
      prefix: Array[Byte],
      originNibbles: Array[Byte],
      @scala.annotation.unused limitNibbles: Array[Byte]
  ): Boolean =
    cmpNibbles(maxKeyWith(prefix), originNibbles) >= 0

  /** Visit-style range walker — calls `visit(keyHash, valueRLP)` for every leaf whose key
    * falls in `[origin, limit]`, traversing the trie in key order. The visitor returns
    * `true` to continue or `false` to stop the walk early (used by the byte-budget gate).
    *
    * Iterative-style traversal via direct recursion (no Iterator.flatMap chain) — much
    * cheaper than the previous lazy iterator construction when the chain depth is 64.
    */
  private def walkRangeVisit(
      root: MptNode,
      storage: MptStorage,
      originNibbles: Array[Byte],
      limitNibbles: Array[Byte]
  )(visit: (ByteString, ByteString) => Boolean): Unit = {
    var stop = false

    def descend(node: MptNode, prefix: Array[Byte]): Unit = {
      if (stop) return
      if (!subtreeIntersectsRange(prefix, originNibbles, limitNibbles)) return
      resolve(node, storage) match {
        case NullNode => ()
        case LeafNode(key, value, _, _, _) =>
          // Geth semantics (eth/protocols/snap/handler.go:304-322): emit any leaf
          // whose key is `>= origin`, then `break` after emitting one with key
          // `>= limit`. This naturally handles the single-key (start == limit) case
          // (returns the matching leaf, then stops) and the "first at-or-after"
          // semantics for keys that don't exist.
          val fullKey = prefix ++ key.toArray
          if (cmpNibbles(fullKey, originNibbles) >= 0) {
            nibblesToHash(fullKey).foreach { h =>
              val keep = visit(h, value)
              val pastLimit = cmpNibbles(fullKey, limitNibbles) >= 0
              if (!keep || pastLimit) stop = true
            }
          }
        case ExtensionNode(sharedKey, next, _, _, _) =>
          descend(next, prefix ++ sharedKey.toArray)
        case BranchNode(children, terminator, _, _, _) =>
          terminator.foreach { value =>
            if (cmpNibbles(prefix, originNibbles) >= 0) {
              nibblesToHash(prefix).foreach { h =>
                val keep = visit(h, value)
                val pastLimit = cmpNibbles(prefix, limitNibbles) >= 0
                if (!keep || pastLimit) stop = true
              }
            }
          }
          var nibble = 0
          while (nibble < 16 && !stop) {
            val child = children(nibble)
            if (child != NullNode) descend(child, prefix :+ nibble.toByte)
            nibble += 1
          }
        case _: HashNode => () // resolve() above prevents reaching here
      }
    }

    descend(root, Array.emptyByteArray)
  }

  /** Compatibility wrapper retained for callers that want an Iterator. Internally uses
    * the visit-style walker but materialises results into a buffer first.
    */
  private def walkRange(
      root: MptNode,
      storage: MptStorage,
      originNibbles: Array[Byte],
      limitNibbles: Array[Byte]
  ): Iterator[(ByteString, ByteString)] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[(ByteString, ByteString)]
    walkRangeVisit(root, storage, originNibbles, limitNibbles) { (h, v) =>
      buf += ((h, v)); true
    }
    buf.iterator
  }

  /** Collect the proof path for a given key — the nodes traversed from root to the leaf
    * (or the deepest reachable node along the path if the key is absent). The proof is
    * returned as a sequence of RLP-encoded MPT nodes (with each node's keccak hash
    * available to the caller for de-duplication).
    */
  private def proofFor(
      root: MptNode,
      storage: MptStorage,
      keyNibbles: Array[Byte]
  ): Seq[ByteString] = {
    val acc = scala.collection.mutable.ArrayBuffer.empty[ByteString]

    def descend(node: MptNode, remaining: Array[Byte]): Unit = {
      val resolved = resolve(node, storage)
      resolved match {
        case NullNode => ()
        case _ =>
          acc += ByteString(MptTraversals.encodeNode(resolved))
          resolved match {
            case LeafNode(_, _, _, _, _) => ()
            case ExtensionNode(sharedKey, next, _, _, _) =>
              val sk = sharedKey.toArray
              if (remaining.length >= sk.length && remaining.take(sk.length).sameElements(sk)) {
                descend(next, remaining.drop(sk.length))
              }
            case BranchNode(children, _, _, _, _) =>
              if (remaining.nonEmpty) {
                val nibble = remaining(0) & 0x0f
                val child = children(nibble)
                if (child != NullNode) descend(child, remaining.drop(1))
              }
            case _ => ()
          }
      }
    }

    descend(root, keyNibbles)
    acc.toSeq
  }

  /** Build an `AccountRange` response by walking the state trie at `rootHash` between
    * `startingHash` and `limitHash`, emitting accounts whose RLP totals up to (but
    * generally not exceeding) `responseBytes`. Always emits at least one account if any
    * fall in the range.
    */
  def serveAccountRange(
      requestId: BigInt,
      rootHash: ByteString,
      startingHash: ByteString,
      limitHash: ByteString,
      responseBytes: BigInt,
      storage: MptStorage
  ): AccountRange = {
    if (isEmptyRoot(rootHash)) return AccountRange(requestId, Seq.empty, Seq.empty)

    val rootNode = fetchRootNode(rootHash, storage)
    if (rootNode == NullNode) {
      log.debug("SNAP serveAccountRange: root {} not in storage", rootHash.take(4))
      return AccountRange(requestId, Seq.empty, Seq.empty)
    }

    // SNAP "wrong-order" handling: when startingHash > limitHash hive's tests expect us
    // to return the FIRST available key at/after `startingHash`. With the geth-style
    // emit-on->=origin / stop-on->=limit semantics now in place, widening `limit` to
    // FF…FF gets us "first key at-or-after start" naturally — the walker emits one then
    // stops because the next key is >= origin >= limit.
    val isReversed = {
      val s = startingHash.toArray
      val l = limitHash.toArray
      var i = 0
      var ord = 0
      while (i < s.length && i < l.length && ord == 0) {
        ord = (s(i) & 0xff) - (l(i) & 0xff)
        i += 1
      }
      ord > 0
    }
    val effectiveLimit = if (isReversed) ByteString(Array.fill[Byte](32)(0xff.toByte)) else limitHash
    val originNibbles = hashToNibbles(startingHash)
    val limitNibbles = hashToNibbles(effectiveLimit)
    val maxBytes = math.max(responseBytes.toInt, 0)

    import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits._
    val collected = scala.collection.mutable.ArrayBuffer.empty[(ByteString, com.chipprbots.ethereum.domain.Account)]
    var accumulated = 0
    // Visit-style walk: visitor returns false to stop traversal as soon as the byte
    // budget is hit. Match go-ethereum's accounting: only (hash + slim-leaf bytes) count
    // toward the budget — slim format is what we'll emit on the wire (see
    // `toSlimAccountRlp`). Proofs aren't counted (they're a separate response header).
    walkRangeVisit(rootNode, storage, originNibbles, limitNibbles) { (keyHash, accountRlp) =>
      val account = accountRlp.toArray.toAccount
      val slimSize = rlp.encode(toSlimAccountRlp(account)).length
      collected += ((keyHash, account))
      accumulated += keyHash.size + slimSize
      // Wrong-order requests: stop after a single item. Otherwise continue while under
      // budget; the first item is always emitted (the visitor only sees this branch
      // after we add to `collected`).
      if (isReversed) false
      else accumulated < maxBytes
    }

    // Build proof: path to the first account (or to startingHash if none in range), and
    // path to the last account if more than one was emitted.
    val proof: Seq[ByteString] = {
      val firstKey = collected.headOption.map(_._1).getOrElse(startingHash)
      val lastKey = collected.lastOption.map(_._1).getOrElse(startingHash)
      val firstProof = proofFor(rootNode, storage, hashToNibbles(firstKey))
      if (firstKey == lastKey) firstProof
      else firstProof ++ proofFor(rootNode, storage, hashToNibbles(lastKey))
    }
    // De-duplicate proof nodes (some appear on both paths).
    val dedupedProof = proof.distinct

    AccountRange(requestId, collected.toSeq, dedupedProof)
  }

  /** Build a `StorageRanges` response — for each account, walk the per-account storage
    * trie between `startingHash` and `limitHash`. Only the first account's range is
    * proved (per SNAP/1 spec — subsequent accounts are returned in full, no proof).
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
  ): StorageRanges = {
    if (isEmptyRoot(rootHash)) return StorageRanges(requestId, Seq.empty, Seq.empty)

    val maxBytes = math.max(responseBytes.toInt, 0)
    var accumulated = 0
    val perAccount = scala.collection.mutable.ArrayBuffer.empty[Seq[(ByteString, ByteString)]]
    var firstProof: Seq[ByteString] = Seq.empty
    var done = false
    val it = accountHashes.iterator
    while (it.hasNext && !done) {
      val accountHash = it.next()
      accountRoot(accountHash) match {
        case None =>
          // Account or its storage root unknown — skip.
          perAccount += Seq.empty
        case Some(storageRoot) =>
          if (isEmptyRoot(storageRoot)) {
            perAccount += Seq.empty
          } else {
            val rootNode = fetchRootNode(storageRoot, storage)
            if (rootNode == NullNode) {
              perAccount += Seq.empty
            } else {
              // First account uses the requested [start, limit] range; subsequent
              // accounts are returned in FULL.
              val (originN, limitN) =
                if (perAccount.isEmpty)
                  (hashToNibbles(startingHash), hashToNibbles(limitHash))
                else
                  (
                    hashToNibbles(ByteString(new Array[Byte](32))),
                    hashToNibbles(ByteString(Array.fill[Byte](32)(0xff.toByte)))
                  )
              val collected = scala.collection.mutable.ArrayBuffer.empty[(ByteString, ByteString)]
              val rangeIt = walkRange(rootNode, storage, originN, limitN)
              while (rangeIt.hasNext && (accumulated < maxBytes || (perAccount.isEmpty && collected.isEmpty))) {
                val (k, v) = rangeIt.next()
                collected += ((k, v))
                accumulated += k.size + v.size + 4
              }
              if (perAccount.isEmpty)
                firstProof = {
                  val first = collected.headOption.map(_._1).getOrElse(startingHash)
                  val last = collected.lastOption.map(_._1).getOrElse(startingHash)
                  val pf = proofFor(rootNode, storage, hashToNibbles(first))
                  val pl = if (first == last) pf else pf ++ proofFor(rootNode, storage, hashToNibbles(last))
                  pl.distinct
                }
              perAccount += collected.toSeq
              if (accumulated >= maxBytes) done = true
            }
          }
      }
    }

    StorageRanges(requestId, perAccount.toSeq, firstProof)
  }

  /** Build a `TrieNodes` response — look up each requested HP-encoded path from `rootHash`
    * and return the raw RLP-encoded node found at that path. Missing nodes yield empty
    * bytes per SNAP/1 spec.
    */
  def serveTrieNodes(
      requestId: BigInt,
      rootHash: ByteString,
      paths: Seq[Seq[ByteString]],
      responseBytes: BigInt,
      storage: MptStorage
  ): TrieNodes = {
    val maxBytes = math.max(responseBytes.toInt, 0)
    var accumulated: Int = 0

    val rootNode = fetchRootNode(rootHash, storage)
    val collected = scala.collection.mutable.ArrayBuffer.empty[ByteString]

    if (rootNode == NullNode) {
      // Root not found — return one empty entry per request, truncated to budget.
      var idx = 0
      while (idx < paths.size && (accumulated < maxBytes || collected.isEmpty)) {
        collected += ByteString.empty
        accumulated = accumulated + 1
        idx += 1
      }
    } else {
      var idx = 0
      while (idx < paths.size && (accumulated < maxBytes || collected.isEmpty)) {
        val pathSet = paths(idx)
        if (pathSet.size == 1) {
          // Single-element path: account-trie node lookup.
          val nibbles = decodeHpPath(pathSet.head.toArray)
          collectNodeAtPath(rootNode, storage, nibbles) match {
            case Some(enc) =>
              collected += enc; accumulated += enc.size
            case None =>
              collected += ByteString.empty; accumulated += 1
          }
        } else if (pathSet.size >= 2) {
          // Multi-element path per geth handler.go:521-577 — pathSet(0) is the
          // account-trie path; pathSet(1..) are storage-trie paths inside that
          // account's storage trie. We resolve the account first, then walk each
          // storage path against `account.storageRoot` (same MptStorage works since
          // trie nodes are content-addressed by keccak256 hash).
          val accountNibbles = decodeHpPath(pathSet.head.toArray)
          val storageNibblesList = pathSet.tail
          resolveLeafAccount(rootNode, storage, accountNibbles) match {
            case Some(account) if account.storageRoot != Account.EmptyStorageRootHash =>
              val storageRootNode = fetchRootNode(account.storageRoot, storage)
              if (storageRootNode == NullNode) {
                storageNibblesList.foreach { _ =>
                  collected += ByteString.empty; accumulated += 1
                }
              } else {
                storageNibblesList.foreach { storagePath =>
                  if (accumulated < maxBytes || collected.isEmpty) {
                    val sn = decodeHpPath(storagePath.toArray)
                    collectNodeAtPath(storageRootNode, storage, sn) match {
                      case Some(enc) =>
                        collected += enc; accumulated += enc.size
                      case None =>
                        collected += ByteString.empty; accumulated += 1
                    }
                  }
                }
              }
            case _ =>
              // Account missing or empty storage — emit one empty entry per storage
              // path (per geth: an empty bytes entry signals "no node here").
              storageNibblesList.foreach { _ =>
                collected += ByteString.empty; accumulated += 1
              }
          }
        }
        idx += 1
      }
    }

    TrieNodes(requestId, collected.toSeq)
  }

  /** Walk the state trie following `nibbles` to a leaf and decode that leaf's value as
    * an `Account`. Returns None if the path doesn't terminate at a leaf or the leaf
    * isn't a valid account RLP.
    */
  private def resolveLeafAccount(
      root: MptNode,
      storage: MptStorage,
      nibbles: Array[Byte]
  ): Option[Account] = {
    import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits._

    def descend(node: MptNode, remaining: Array[Byte]): Option[Account] = {
      val resolved = resolve(node, storage)
      resolved match {
        case NullNode => None
        case LeafNode(key, value, _, _, _) =>
          val k = key.toArray
          if (remaining.sameElements(k)) {
            try Some(value.toArray.toAccount)
            catch { case _: Throwable => None }
          } else None
        case ExtensionNode(sharedKey, next, _, _, _) =>
          val sk = sharedKey.toArray
          if (remaining.length >= sk.length && remaining.take(sk.length).sameElements(sk))
            descend(next, remaining.drop(sk.length))
          else None
        case BranchNode(children, terminator, _, _, _) =>
          if (remaining.isEmpty) {
            // The account sits in the branch terminator slot.
            terminator.flatMap { v =>
              try Some(v.toArray.toAccount)
              catch { case _: Throwable => None }
            }
          } else {
            val nibble = remaining(0) & 0x0f
            val child = children(nibble)
            if (child == NullNode) None else descend(child, remaining.drop(1))
          }
        case _: HashNode => None
      }
    }

    descend(root, nibbles)
  }

  /** Decode an HP-encoded (Hex Prefix, EIP-2 / yellow-paper) path back to its nibble
    * representation. Drops the leaf/extension flag bit.
    */
  private def decodeHpPath(bytes: Array[Byte]): Array[Byte] = {
    if (bytes.isEmpty) return Array.emptyByteArray
    val firstByte = bytes(0) & 0xff
    val oddLen = (firstByte & 0x10) != 0
    val skipFirst = if (oddLen) 0 else 1
    val firstNibble = if (oddLen) Array((firstByte & 0x0f).toByte) else Array.empty[Byte]
    val rest = bytes.drop(1).flatMap { b =>
      Array(((b >>> 4) & 0x0f).toByte, (b & 0x0f).toByte)
    }
    firstNibble ++ rest.drop(skipFirst - 1).take(rest.length - (skipFirst - 1))
  }

  /** Walk the trie following `nibbles` and return the encoded node found at that exact
    * path (as a raw MPT node), or None if the path doesn't terminate cleanly at a node.
    */
  private def collectNodeAtPath(
      root: MptNode,
      storage: MptStorage,
      nibbles: Array[Byte]
  ): Option[ByteString] = {

    def descend(node: MptNode, remaining: Array[Byte]): Option[ByteString] = {
      val resolved = resolve(node, storage)
      if (remaining.isEmpty) {
        Some(ByteString(MptTraversals.encodeNode(resolved)))
      } else {
        resolved match {
          case NullNode => None
          case LeafNode(key, _, _, _, _) =>
            val k = key.toArray
            if (remaining.sameElements(k)) Some(ByteString(MptTraversals.encodeNode(resolved)))
            else None
          case ExtensionNode(sharedKey, next, _, _, _) =>
            val sk = sharedKey.toArray
            if (remaining.length >= sk.length && remaining.take(sk.length).sameElements(sk))
              descend(next, remaining.drop(sk.length))
            else None
          case BranchNode(children, _, _, _, _) =>
            val nibble = remaining(0) & 0x0f
            val child = children(nibble)
            if (child == NullNode) None
            else descend(child, remaining.drop(1))
          case _: HashNode => None // resolve handles
        }
      }
    }

    descend(root, nibbles)
  }
}
