package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.slf4j.LoggerFactory
import net.logstash.logback.argument.StructuredArguments.kv

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class StateValidator(mptStorage: MptStorage) {

  private val log = LoggerFactory.getLogger(classOf[StateValidator])

  /** Validate the account trie by traversing it and detecting missing nodes.
    *
    * @param stateRoot
    *   The expected state root hash
    * @return
    *   Right with missing node hashes if any, or Left with error message
    */
  def validateAccountTrie(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try {
      val missingNodes = mutable.ArrayBuffer[ByteString]()

      try {
        val rootNode = mptStorage.get(stateRoot.toArray)
        traverseForMissingNodes(rootNode, mptStorage, missingNodes)

        if (missingNodes.isEmpty) Right(Seq.empty)
        else Right(missingNodes.toSeq)
      } catch {
        case _: MerklePatriciaTrie.MissingNodeException =>
          Left(s"Missing root node: ${stateRoot.take(8).toHex}")
        case e: Exception =>
          Left(s"Failed to load root node: ${e.getMessage}")
      }
    } catch {
      case e: Exception =>
        Left(s"Validation error: ${e.getMessage}")
    }

  /** Validate all storage tries by walking through all accounts and checking their storage. */
  def validateAllStorageTries(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try {
      val missingStorageNodes = mutable.ArrayBuffer[ByteString]()
      val accounts = mutable.ArrayBuffer[Account]()

      try {
        val rootNode = mptStorage.get(stateRoot.toArray)
        collectAccounts(rootNode, mptStorage, accounts)
      } catch {
        case _: Exception =>
          return Left("Cannot validate storage tries: failed to traverse account trie")
      }

      accounts.foreach { account =>
        if (account.storageRoot != Account.EmptyStorageRootHash) {
          try {
            val storageRootNode = mptStorage.get(account.storageRoot.toArray)
            traverseForMissingNodes(storageRootNode, mptStorage, missingStorageNodes)
          } catch {
            case e: MerklePatriciaTrie.MissingNodeException =>
              missingStorageNodes += e.hash
            case _: Exception => ()
          }
        }
      }

      if (missingStorageNodes.isEmpty) Right(Seq.empty)
      else Right(missingStorageNodes.toSeq)
    } catch {
      case e: Exception =>
        Left(s"Storage validation error: ${e.getMessage}")
    }

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
    node match {
      case _: LeafNode | NullNode =>
        ()

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          traverseForMissingNodes(ext.next, storage, missingNodes, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          branch.children.foreach { child =>
            traverseForMissingNodes(child, storage, missingNodes, visited)
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            traverseForMissingNodes(resolvedNode, storage, missingNodes, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              missingNodes += ByteString(hash.hash)
            case _: Exception =>
              missingNodes += ByteString(hash.hash)
          }
        }
    }

  /** Recursively collect all accounts from a trie. */
  private def collectAccounts(
      node: MptNode,
      storage: MptStorage,
      accounts: mutable.ArrayBuffer[Account],
      visited: mutable.Set[ByteString] = mutable.Set.empty
  ): Unit = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    node match {
      case leaf: LeafNode =>
        try {
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          accounts += account
        } catch {
          case _: Exception => ()
        }

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          collectAccounts(ext.next, storage, accounts, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          branch.children.foreach { child =>
            collectAccounts(child, storage, accounts, visited)
          }
          branch.terminator.foreach { value =>
            try {
              val account = accountSerializer.fromBytes(value.toArray)
              accounts += account
            } catch {
              case _: Exception => ()
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            collectAccounts(resolvedNode, storage, accounts, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException => ()
            case _: Exception                               => ()
          }
        }

      case NullNode => ()
    }
  }

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
  def findMissingNodesWithPaths(stateRoot: ByteString): Either[String, Seq[(Seq[ByteString], ByteString)]] = {
    val result = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()

    try {
      val rootNode = mptStorage.get(stateRoot.toArray)
      walkAccountTrieDFS(rootNode, result)
      Right(result.toSeq)
    } catch {
      case _: MerklePatriciaTrie.MissingNodeException =>
        val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        result += ((Seq(compactPath), stateRoot))
        Right(result.toSeq)
      case e: Exception =>
        Left(s"Trie walk failed: ${e.getMessage}")
    }
  }

  /** Streaming variant: calls onBatch whenever batchSize missing nodes are found. Allows the healing coordinator to
    * start working before the full walk completes — critical for mainnet-scale tries where the full walk can take
    * hours. Returns total missing nodes found, or Left on fatal error.
    */
  def findMissingNodesStreaming(
      stateRoot: ByteString,
      batchSize: Int,
      onBatch: Seq[(Seq[ByteString], ByteString)] => Unit
  ): Either[String, Int] = {
    val result = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()
    var totalSent = 0

    val flushIfFull: () => Unit = () =>
      if (result.size >= batchSize) {
        onBatch(result.toSeq)
        totalSent += result.size
        result.clear()
      }

    try {
      val rootNode = mptStorage.get(stateRoot.toArray)
      walkAccountTrieDFS(rootNode, result, flushIfFull)
      if (result.nonEmpty) {
        onBatch(result.toSeq)
        totalSent += result.size
      }
      Right(totalSent)
    } catch {
      case _: MerklePatriciaTrie.MissingNodeException =>
        val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        onBatch(Seq((Seq(compactPath), stateRoot)))
        Right(totalSent + 1)
      case e: Exception =>
        Left(s"Trie walk failed: ${e.getMessage}")
    }
  }

  private def walkAccountTrieDFS(
      rootNode: MptNode,
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)],
      flushIfFull: () => Unit = () => ()
  ): Unit = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    // Use a stack (DFS) instead of a queue (BFS). DFS uses O(trie_depth) space —
    // typically 8-9 levels for ETC mainnet — vs O(trie_width) for BFS which can
    // accumulate millions of BranchNodes simultaneously (OOM on 85.9M account tries).
    val stack = mutable.ArrayDeque[(MptNode, Array[Byte])]()
    val visited = mutable.Set[ByteString]()
    stack.prepend((rootNode, Array.emptyByteArray))

    var lastHeartbeat = System.currentTimeMillis()
    var nodesVisited = 0L

    while (stack.nonEmpty) {
      val (node, nibblePath) = stack.removeHead()
      nodesVisited += 1

      val now = System.currentTimeMillis()
      if (now - lastHeartbeat >= 10_000L) {
        log.info(
          "[WALK-PULSE]",
          kv("visited", nodesVisited),
          kv("missingPending", result.size),
          kv("stack", stack.size)
        )
        lastHeartbeat = now
      }

      node match {
        case NullNode => ()

        case leaf: LeafNode =>
          try {
            val account = accountSerializer.fromBytes(leaf.value.toArray)
            if (account.storageRoot != com.chipprbots.ethereum.domain.Account.EmptyStorageRootHash) {
              val fullNibblePath = nibblePath ++ leaf.key.toArray
              val accountHashBytes = HexPrefix.nibblesToBytes(fullNibblePath)
              try {
                val storageRoot = mptStorage.get(account.storageRoot.toArray)
                // Each storage trie gets its own DFS + visited set (independent walk)
                walkStorageTrieDFS(storageRoot, ByteString(accountHashBytes), result, flushIfFull)
              } catch {
                case _: MerklePatriciaTrie.MissingNodeException =>
                  val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                  result += ((Seq(ByteString(accountHashBytes), compactPath), account.storageRoot))
                  flushIfFull()
              }
            }
          } catch { case _: Exception => () }

        case ext: ExtensionNode =>
          val nodeHash = ByteString(ext.hash)
          if (!visited.contains(nodeHash)) {
            visited += nodeHash
            stack.prepend((ext.next, nibblePath ++ ext.sharedKey.toArray))
          }

        case branch: BranchNode =>
          val nodeHash = ByteString(branch.hash)
          if (!visited.contains(nodeHash)) {
            visited += nodeHash
            // Push in reverse order so child 0 is processed first (consistent ordering)
            for (i <- 15 to 0 by -1) {
              val child = branch.children(i)
              if (!child.isNull)
                stack.prepend((child, nibblePath :+ i.toByte))
            }
          }

        case hash: HashNode =>
          val hashKey = ByteString(hash.hash)
          if (!visited.contains(hashKey)) {
            try
              mptStorage.get(hash.hash) // proven subtree — discoverMissingChildren handles children
            catch {
              case _: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(nibblePath, isLeaf = false))
                result += ((Seq(compactPath), ByteString(hash.hash)))
                flushIfFull()
            }
          }
      }
    }
  }

  private def walkStorageTrieDFS(
      rootNode: MptNode,
      accountHash: ByteString,
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)],
      flushIfFull: () => Unit
  ): Unit = {
    val stack = mutable.ArrayDeque[(MptNode, Array[Byte])]()
    val visited = mutable.Set[ByteString]()
    stack.prepend((rootNode, Array.emptyByteArray))

    while (stack.nonEmpty) {
      val (node, nibblePath) = stack.removeHead()

      node match {
        case _: LeafNode | NullNode => ()

        case ext: ExtensionNode =>
          val nodeHash = ByteString(ext.hash)
          if (!visited.contains(nodeHash)) {
            visited += nodeHash
            stack.prepend((ext.next, nibblePath ++ ext.sharedKey.toArray))
          }

        case branch: BranchNode =>
          val nodeHash = ByteString(branch.hash)
          if (!visited.contains(nodeHash)) {
            visited += nodeHash
            for (i <- 15 to 0 by -1) {
              val child = branch.children(i)
              if (!child.isNull)
                stack.prepend((child, nibblePath :+ i.toByte))
            }
          }

        case hash: HashNode =>
          val hashKey = ByteString(hash.hash)
          if (!visited.contains(hashKey)) {
            try {
              val resolvedNode = mptStorage.get(hash.hash)
              stack.prepend((resolvedNode, nibblePath))
            } catch {
              case _: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(nibblePath, isLeaf = false))
                result += ((Seq(accountHash, compactPath), ByteString(hash.hash)))
                flushIfFull()
            }
          }
      }
    }
  }
}
