package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

class StateValidator(mptStorage: MptStorage) {

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
        case e: MerklePatriciaTrie.MissingNodeException =>
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

  /** Find all missing trie nodes with GetTrieNodes-compatible pathsets. */
  def findMissingNodesWithPaths(stateRoot: ByteString): Either[String, Seq[(Seq[ByteString], ByteString)]] = {
    val result = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()

    try {
      val rootNode = mptStorage.get(stateRoot.toArray)
      traverseAccountTrieWithPaths(rootNode, mptStorage, Array.empty[Byte], result, mutable.Set.empty)
      Right(result.toSeq)
    } catch {
      case e: MerklePatriciaTrie.MissingNodeException =>
        val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
        result += ((Seq(compactPath), e.hash))
        Right(result.toSeq)
      case e: Exception =>
        Left(s"Trie walk failed: ${e.getMessage}")
    }
  }

  /** Walk the account trie, tracking nibble paths for missing nodes. Also checks storage tries. */
  private def traverseAccountTrieWithPaths(
      node: MptNode,
      storage: MptStorage,
      currentNibblePath: Array[Byte],
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)],
      visited: mutable.Set[ByteString]
  ): Unit = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    node match {
      case leaf: LeafNode =>
        try {
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          if (account.storageRoot != com.chipprbots.ethereum.domain.Account.EmptyStorageRootHash) {
            val leafKeyNibbles = leaf.key.toArray
            val fullNibblePath = currentNibblePath ++ leafKeyNibbles
            val accountHashBytes = HexPrefix.nibblesToBytes(fullNibblePath)

            try {
              val storageRoot = storage.get(account.storageRoot.toArray)
              traverseStorageTrieWithPaths(
                storageRoot, storage, Array.empty[Byte], ByteString(accountHashBytes), result, mutable.Set.empty
              )
            } catch {
              case e: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                result += ((Seq(ByteString(accountHashBytes), compactPath), e.hash))
            }
          }
        } catch {
          case _: Exception => ()
        }

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          val sharedKeyNibbles = ext.sharedKey.toArray
          val newPath = currentNibblePath ++ sharedKeyNibbles
          traverseAccountTrieWithPaths(ext.next, storage, newPath, result, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull) {
              val newPath = currentNibblePath :+ i.toByte
              traverseAccountTrieWithPaths(child, storage, newPath, result, visited)
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            traverseAccountTrieWithPaths(resolvedNode, storage, currentNibblePath, result, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
              result += ((Seq(compactPath), ByteString(hash.hash)))
          }
        }

      case NullNode => ()
    }
  }

  /** Walk a storage trie, tracking nibble paths for missing nodes. */
  private def traverseStorageTrieWithPaths(
      node: MptNode,
      storage: MptStorage,
      currentNibblePath: Array[Byte],
      accountHash: ByteString,
      result: mutable.ArrayBuffer[(Seq[ByteString], ByteString)],
      visited: mutable.Set[ByteString]
  ): Unit =
    node match {
      case _: LeafNode | NullNode => ()

      case ext: ExtensionNode =>
        val nodeHash = ByteString(ext.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          val sharedKeyNibbles = ext.sharedKey.toArray
          val newPath = currentNibblePath ++ sharedKeyNibbles
          traverseStorageTrieWithPaths(ext.next, storage, newPath, accountHash, result, visited)
        }

      case branch: BranchNode =>
        val nodeHash = ByteString(branch.hash)
        if (!visited.contains(nodeHash)) {
          visited += nodeHash
          for (i <- 0 until 16) {
            val child = branch.children(i)
            if (!child.isNull) {
              val newPath = currentNibblePath :+ i.toByte
              traverseStorageTrieWithPaths(child, storage, newPath, accountHash, result, visited)
            }
          }
        }

      case hash: HashNode =>
        val hashKey = ByteString(hash.hash)
        if (!visited.contains(hashKey)) {
          try {
            val resolvedNode = storage.get(hash.hash)
            traverseStorageTrieWithPaths(resolvedNode, storage, currentNibblePath, accountHash, result, visited)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
              result += ((Seq(accountHash, compactPath), ByteString(hash.hash)))
          }
        }
    }
}
