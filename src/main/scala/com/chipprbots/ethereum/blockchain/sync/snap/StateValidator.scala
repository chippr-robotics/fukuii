package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.utils.ByteStringUtils.toHex

/** Walks a state trie for what healing must fetch.
  *
  * A node the store does not hold is a hole, queued for healing. A node it holds that is not that node (a
  * `CorruptNodeException`, spec 022 T062) is not: under basic pruning, the ETC default, healing's write
  * (`storeRawNodes`, then `ReferenceCountNodeStorage.update`) keeps the stored entry and only counts a reference, so
  * the "healed" node would keep its bad bytes and the next walk queue it again, a quiet spin. So every walk here lets a
  * corrupt node through its hole arms, and its entry point returns `Left` for it.
  */
class StateValidator(mptStorage: MptStorage):

  private val log = LoggerFactory.getLogger(classOf[StateValidator])

  private def corruptFailure(e: MerklePatriciaTrie.CorruptNodeException): String =
    s"Corrupt trie node, not a hole: ${e.getMessage}"

  /** Validate the account trie by traversing it and detecting missing nodes.
    *
    * @param stateRoot
    *   The expected state root hash
    * @return
    *   Right with missing node hashes if any, or Left with error message
    */
  def validateAccountTrie(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try
      val missingNodes = mutable.ArrayBuffer[ByteString]()

      try
        val rootNode = mptStorage.get(stateRoot.toArray)
        traverseForMissingNodes(rootNode, mptStorage, missingNodes)

        if missingNodes.isEmpty then Right(Seq.empty)
        else Right(missingNodes.toSeq)
      catch
        case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
        case _: MerklePatriciaTrie.MissingNodeException =>
          Left(s"Missing root node: ${stateRoot.take(8).toHex}")
        case e: Exception =>
          Left(s"Failed to load root node: ${e.getMessage}")
    catch
      case e: Exception =>
        Left(s"Validation error: ${e.getMessage}")

  /** Validate all storage tries by walking through all accounts and checking their storage. */
  def validateAllStorageTries(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try
      val missingStorageNodes = mutable.ArrayBuffer[ByteString]()
      val accounts = mutable.ArrayBuffer[Account]()

      val accountTrieResult =
        try
          val rootNode = mptStorage.get(stateRoot.toArray)
          collectAccounts(rootNode, mptStorage, accounts)
          Right(())
        catch
          case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
          case e: Exception =>
            log.warn(s"Failed to traverse account trie for storage validation: ${e.getMessage}", e)
            Left("Cannot validate storage tries: failed to traverse account trie")

      accountTrieResult match
        case Left(err) => Left(err)
        case Right(_) =>
          accounts.foreach { account =>
            if account.storageRoot != Account.EmptyStorageRootHash then
              try
                val storageRootNode = mptStorage.get(account.storageRoot.toArray)
                traverseForMissingNodes(storageRootNode, mptStorage, missingStorageNodes)
              catch
                case e: MerklePatriciaTrie.CorruptNodeException => throw e
                case e: MerklePatriciaTrie.MissingNodeException =>
                  missingStorageNodes += e.hash
                case e: Exception =>
                  // A non-missing fault here means this storage trie was NOT fully
                  // validated. Conservatively flag the storage root for healing so the
                  // caller does not treat an un-walked trie as intact; log so corrupt
                  // storage data (not just a missing node) is visible.
                  log.warn(
                    s"Unexpected error walking storage trie ${account.storageRoot.value.take(8).toHex}; flagging for healing: ${e.getMessage}",
                    e
                  )
                  missingStorageNodes += account.storageRoot.value
          }

          if missingStorageNodes.isEmpty then Right(Seq.empty)
          else Right(missingStorageNodes.toSeq)
    catch
      case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
      case e: Exception =>
        Left(s"Storage validation error: ${e.getMessage}")

  /** Recursively traverse a trie and collect missing node hashes. */
  private def traverseForMissingNodes(
      node: MptNode,
      storage: MptStorage,
      missingNodes: mutable.ArrayBuffer[ByteString],
      visited: mutable.Set[ByteString] = mutable.Set.empty
  ): Unit =
    // Note: visited check is per-case, NOT at the top.
    // HashNode and its resolved node share the same hash (decodeNode sets
    // cachedHash = lookupKey), so a top-level visited check would skip the
    // resolved node immediately after adding the HashNode — truncating the
    // traversal at depth 1.
    node match
      case _: LeafNode | NullNode =>
        ()

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if !visited.contains(nodeHash) then
          visited += nodeHash
          traverseForMissingNodes(ext.next, storage, missingNodes, visited)

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if !visited.contains(nodeHash) then
          visited += nodeHash
          branch.children.foreach { child =>
            traverseForMissingNodes(child, storage, missingNodes, visited)
          }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if !visited.contains(hashKey) then
          try
            val resolvedNode = storage.get(hash.hash)
            traverseForMissingNodes(resolvedNode, storage, missingNodes, visited)
          catch
            case e: MerklePatriciaTrie.CorruptNodeException => throw e
            case _: MerklePatriciaTrie.MissingNodeException =>
              missingNodes += ByteString(hash.hash)
            case e: Exception =>
              // A read that fails for another reason (the store, not the bytes: a node that does not decode is a
              // CorruptNodeException, above) is queued as missing, so a later pass reads it again. Not because
              // healing would overwrite it: under basic pruning its write keeps an entry that is already there.
              // Logged so a persistent fault is visible rather than indistinguishable from an ordinary missing node.
              log.warn(
                s"Unexpected error resolving node ${ByteString(hash.hash).take(8).toHex}; treating as missing: ${e.getMessage}",
                e
              )
              missingNodes += ByteString(hash.hash)

  /** Recursively collect all accounts from a trie. */
  private def collectAccounts(
      node: MptNode,
      storage: MptStorage,
      accounts: mutable.ArrayBuffer[Account],
      visited: mutable.Set[ByteString] = mutable.Set.empty
  ): Unit =
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    node match
      case leaf: LeafNode =>
        try
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          accounts += account
        catch
          case e: Exception =>
            // A leaf that fails to decode as an Account means its storage trie will not
            // be validated. Log so corrupt account RLP is visible rather than silently
            // dropped (which would make storage validation report a false "intact").
            log.warn(s"Failed to decode account leaf during validation: ${e.getMessage}", e)

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if !visited.contains(nodeHash) then
          visited += nodeHash
          collectAccounts(ext.next, storage, accounts, visited)

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if !visited.contains(nodeHash) then
          visited += nodeHash
          branch.children.foreach { child =>
            collectAccounts(child, storage, accounts, visited)
          }
          branch.terminator.foreach { value =>
            try
              val account = accountSerializer.fromBytes(value.toArray)
              accounts += account
            catch
              case e: Exception =>
                // Same rationale as the LeafNode case: a non-decodable terminator account
                // would be silently skipped and its storage trie never validated.
                log.warn(s"Failed to decode account terminator during validation: ${e.getMessage}", e)
          }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if !visited.contains(hashKey) then
          try
            val resolvedNode = storage.get(hash.hash)
            collectAccounts(resolvedNode, storage, accounts, visited)
          catch
            // Either case means the account subtree below this hash is skipped, so its
            // accounts' storage tries are not validated. The account-trie completeness is
            // checked separately by validateAccountTrie/findMissingNodes; here we only need
            // observability so a non-missing fault (corrupt DB) is not silently masked. A corrupt node fails the
            // collection instead: the storage tries under it would go unvalidated.
            case e: MerklePatriciaTrie.CorruptNodeException => throw e
            case _: MerklePatriciaTrie.MissingNodeException =>
              log.debug(s"Missing account-trie node ${ByteString(hash.hash).take(8).toHex} during account collection")
            case e: Exception =>
              log.warn(
                s"Unexpected error resolving account-trie node ${ByteString(hash.hash).take(8).toHex} during collection: ${e.getMessage}",
                e
              )

      case NullNode => ()

  // ========================================
  // Path-tracking trie walk for GetTrieNodes healing
  // ========================================

  /** Find all missing trie nodes with GetTrieNodes-compatible pathsets.
    *
    * Uses iterative BFS queues instead of recursion to avoid StackOverflowError on large tries (ETC mainnet: ~90M
    * accounts, trie depth up to 64 nibbles). Each storage trie is walked with its own visited set (independent BFS),
    * matching the original per-storage-trie isolation.
    *
    * Mirrors Besu TrieNodeHealingRequest.getChildRequests() (java:94-125) and go-ethereum trie/sync.go:ProcessNode()
    * (line 419-448): every retrieved node enqueues ALL hash-referenced children before moving on.
    */
  def findMissingNodesWithPaths(stateRoot: ByteString): Either[String, Seq[(Seq[ByteString], ByteString)]] =
    val result = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()

    try
      val rootNode = mptStorage.get(stateRoot.toArray)
      walkAccountTrieDFS(rootNode, result)
      Right(result.toSeq)
    catch
      case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
      case _: MerklePatriciaTrie.MissingNodeException =>
        val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        result += ((Seq(compactPath), stateRoot))
        Right(result.toSeq)
      case e: Exception =>
        Left(s"Trie walk failed: ${e.getMessage}")

  private def walkAccountTrieDFS(
      rootNode: MptNode,
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)]
  ): Unit =
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    // Use a stack (DFS) instead of a queue (BFS). DFS uses O(trie_depth) space —
    // typically 8-9 levels for ETC mainnet — vs O(trie_width) for BFS which can
    // accumulate millions of BranchNodes simultaneously (OOM on 85.9M account tries).
    val stack = mutable.ArrayDeque[(MptNode, Array[Byte])]()
    val visited = mutable.Set[ByteString]()
    stack.prepend((rootNode, Array.emptyByteArray))

    var lastHeartbeat = System.currentTimeMillis()
    var nodesVisited = 0L

    while stack.nonEmpty do
      val (node, nibblePath) = stack.removeHead()
      nodesVisited += 1

      val now = System.currentTimeMillis()
      if now - lastHeartbeat >= 10_000L then
        log.info(s"[WALK-PULSE] visited=$nodesVisited missing-pending=${result.size} stack=${stack.size}")
        lastHeartbeat = now

      node match
        case NullNode => ()

        case leaf: LeafNode =>
          try
            val account = accountSerializer.fromBytes(leaf.value.toArray)
            if account.storageRoot != com.chipprbots.ethereum.domain.Account.EmptyStorageRootHash then
              val fullNibblePath = nibblePath ++ leaf.key.toArray
              val accountHashBytes = HexPrefix.nibblesToBytes(fullNibblePath)
              try
                val storageRoot = mptStorage.get(account.storageRoot.toArray)
                // Each storage trie gets its own DFS + visited set (independent walk)
                walkStorageTrieDFS(storageRoot, ByteString(accountHashBytes), result)
              catch
                case e: MerklePatriciaTrie.CorruptNodeException => throw e
                case _: MerklePatriciaTrie.MissingNodeException =>
                  val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                  result += ((Seq(ByteString(accountHashBytes), compactPath), account.storageRoot.value))
          catch
            // Not swallowed with the decode failures below: it ends the walk.
            case e: MerklePatriciaTrie.CorruptNodeException => throw e
            case e: Exception                               =>
              // A leaf that fails to decode as an Account means its storage trie is omitted
              // from the healing walk. Log so corrupt account data is visible rather than
              // silently producing a false "no missing nodes" completion signal.
              log.warn(s"Failed to decode account leaf during heal walk: ${e.getMessage}", e)

        case ext: ExtensionNode =>
          val nodeHash = ByteString(ext.hash)
          if !visited.contains(nodeHash) then
            visited += nodeHash
            stack.prepend((ext.next, nibblePath ++ ext.sharedKey.toArray))

        case branch: BranchNode =>
          val nodeHash = ByteString(branch.hash)
          if !visited.contains(nodeHash) then
            visited += nodeHash
            // Push in reverse order so child 0 is processed first (consistent ordering)
            for i <- 15 to 0 by -1 do
              val child = branch.children(i)
              if !child.isNull then stack.prepend((child, nibblePath :+ i.toByte))

        case hash: HashNode =>
          val hashKey = ByteString(hash.hash)
          if !visited.contains(hashKey) then
            try mptStorage.get(hash.hash) // proven subtree — discoverMissingChildren handles children
            catch
              case e: MerklePatriciaTrie.CorruptNodeException => throw e
              case _: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(nibblePath, isLeaf = false))
                result += ((Seq(compactPath), ByteString(hash.hash)))

  private def walkStorageTrieDFS(
      rootNode: MptNode,
      accountHash: ByteString,
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)]
  ): Unit =
    val stack = mutable.ArrayDeque[(MptNode, Array[Byte])]()
    val visited = mutable.Set[ByteString]()
    stack.prepend((rootNode, Array.emptyByteArray))

    while stack.nonEmpty do
      val (node, nibblePath) = stack.removeHead()

      node match
        case _: LeafNode | NullNode => ()

        case ext: ExtensionNode =>
          val nodeHash = ByteString(ext.hash)
          if !visited.contains(nodeHash) then
            visited += nodeHash
            stack.prepend((ext.next, nibblePath ++ ext.sharedKey.toArray))

        case branch: BranchNode =>
          val nodeHash = ByteString(branch.hash)
          if !visited.contains(nodeHash) then
            visited += nodeHash
            for i <- 15 to 0 by -1 do
              val child = branch.children(i)
              if !child.isNull then stack.prepend((child, nibblePath :+ i.toByte))

        case hash: HashNode =>
          val hashKey = ByteString(hash.hash)
          if !visited.contains(hashKey) then
            try
              val resolvedNode = mptStorage.get(hash.hash)
              stack.prepend((resolvedNode, nibblePath))
            catch
              case e: MerklePatriciaTrie.CorruptNodeException => throw e
              case _: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(nibblePath, isLeaf = false))
                result += ((Seq(accountHash, compactPath), ByteString(hash.hash)))
