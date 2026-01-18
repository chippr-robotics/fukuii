package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Try

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.Logger

/** Server-side implementation for SNAP/1 protocol requests.
  *
  * This service handles incoming SNAP requests from peers and returns the requested state data with Merkle proofs. It
  * enables Fukuii to serve as a state provider for other nodes performing SNAP sync.
  *
  * The service implements all four SNAP request types:
  *   - GetAccountRange: Returns accounts in a hash range with boundary proofs
  *   - GetStorageRanges: Returns storage slots for accounts with proofs
  *   - GetByteCodes: Returns contract bytecodes by hash
  *   - GetTrieNodes: Returns specific trie nodes by path
  *
  * All responses respect byte budget limits to avoid oversized messages.
  *
  * @param blockchainReader
  *   For verifying state roots and accessing block data
  * @param stateStorage
  *   For accessing the Merkle Patricia Trie nodes
  * @param evmCodeStorage
  *   For retrieving contract bytecodes
  */
class SNAPServerService(
    blockchainReader: BlockchainReader,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage
) extends Logger {

  /** Mask for extracting nibble values (4 bits) */
  private val NibbleMask = 0x0f

  /** Conservative threshold for bytecode budget estimation (60%) */
  private val BytecodeBudgetThreshold = 0.6

  /** Default max accounts per response to limit memory usage */
  private val MaxAccountsPerResponse = 10000

  /** Default max storage slots per account */
  private val MaxStorageSlotsPerAccount = 10000

  /** Lazy proof generator - only created when first needed */
  private lazy val proofGenerator = MerkleProofGenerator(stateStorage)

  /** Handle a GetAccountRange request
    *
    * Retrieves accounts from the state trie within the specified hash range, respecting the byte budget. Includes
    * boundary proofs for the first and last accounts.
    *
    * @param request
    *   The GetAccountRange request from a peer
    * @return
    *   AccountRange response with accounts and proofs
    */
  def handleGetAccountRange(request: GetAccountRange): AccountRange = {
    val startTime = System.currentTimeMillis()

    // Validate that we have the requested state root
    val rootNode = stateStorage.getNode(request.rootHash)
    if (rootNode.isEmpty) {
      log.debug(
        s"GetAccountRange: State root not found: ${request.rootHash.take(8).toArray.map("%02x".format(_)).mkString}"
      )
      return AccountRange(request.requestId, Seq.empty, Seq.empty)
    }

    try {
      // Collect accounts in the range
      val accounts = mutable.ArrayBuffer.empty[(ByteString, Account)]
      var bytesCollected = 0L
      val byteLimit = request.responseBytes.toLong

      // Traverse the trie to collect accounts in range
      collectAccountsInRange(
        rootNode.get,
        Seq.empty,
        request.startingHash,
        request.limitHash,
        byteLimit,
        accounts,
        () => bytesCollected,
        bytes => bytesCollected += bytes
      )

      // Generate boundary proofs
      val proof = if (accounts.nonEmpty) {
        proofGenerator.generateAccountRangeProof(
          request.rootHash,
          Some(accounts.head._1),
          Some(accounts.last._1)
        )
      } else {
        // For empty response, generate proof for startingHash to prove absence
        proofGenerator.generateAccountRangeProof(
          request.rootHash,
          Some(request.startingHash),
          None
        )
      }

      val elapsed = System.currentTimeMillis() - startTime
      log.debug(
        s"GetAccountRange: Collected ${accounts.size} accounts, ${proof.size} proof nodes in ${elapsed}ms"
      )

      AccountRange(request.requestId, accounts.toSeq, proof)
    } catch {
      case e: Exception =>
        log.warn(s"Error handling GetAccountRange: ${e.getMessage}", e)
        AccountRange(request.requestId, Seq.empty, Seq.empty)
    }
  }

  /** Handle a GetStorageRanges request
    *
    * Retrieves storage slots for the specified accounts within the hash range.
    *
    * @param request
    *   The GetStorageRanges request from a peer
    * @return
    *   StorageRanges response with slots and proofs
    */
  def handleGetStorageRanges(request: GetStorageRanges): StorageRanges = {
    val startTime = System.currentTimeMillis()

    // Validate state root
    if (stateStorage.getNode(request.rootHash).isEmpty) {
      log.debug(
        s"GetStorageRanges: State root not found: ${request.rootHash.take(8).toArray.map("%02x".format(_)).mkString}"
      )
      return StorageRanges(request.requestId, Seq.empty, Seq.empty)
    }

    try {
      val allSlots = mutable.ArrayBuffer.empty[Seq[(ByteString, ByteString)]]
      val allProofs = mutable.ArrayBuffer.empty[ByteString]
      var bytesCollected = 0L
      val byteLimit = request.responseBytes.toLong

      // Process each account's storage
      for (accountHash <- request.accountHashes if bytesCollected < byteLimit) {
        // Look up the account to get its storage root
        val accountOpt = lookupAccount(request.rootHash, accountHash)

        accountOpt match {
          case Some(account) =>
            val storageRoot = account.storageRoot
            val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)

            if (storageRoot != emptyRoot) {
              // Collect storage slots for this account
              val slots = mutable.ArrayBuffer.empty[(ByteString, ByteString)]

              stateStorage.getNode(storageRoot) match {
                case Some(storageRootNode) =>
                  collectStorageSlotsInRange(
                    storageRootNode,
                    Seq.empty,
                    request.startingHash,
                    request.limitHash,
                    byteLimit - bytesCollected,
                    slots,
                    () => bytesCollected,
                    bytes => bytesCollected += bytes
                  )

                  if (slots.nonEmpty) {
                    // Generate proof for this account's storage
                    val storageProof = proofGenerator.generateStorageRangeProof(
                      storageRoot,
                      Some(slots.head._1),
                      Some(slots.last._1)
                    )
                    allProofs ++= storageProof
                  }

                case None =>
                  log.debug(s"Storage root not found for account: ${accountHash.take(8).toArray.map("%02x".format(_)).mkString}")
              }

              allSlots += slots.toSeq
            } else {
              // Empty storage - add empty slot list
              allSlots += Seq.empty
            }

          case None =>
            log.debug(s"Account not found: ${accountHash.take(8).toArray.map("%02x".format(_)).mkString}")
            allSlots += Seq.empty
        }
      }

      val elapsed = System.currentTimeMillis() - startTime
      log.debug(
        s"GetStorageRanges: Collected ${allSlots.map(_.size).sum} slots for ${allSlots.size} accounts in ${elapsed}ms"
      )

      StorageRanges(request.requestId, allSlots.toSeq, allProofs.distinct.toSeq)
    } catch {
      case e: Exception =>
        log.warn(s"Error handling GetStorageRanges: ${e.getMessage}", e)
        StorageRanges(request.requestId, Seq.empty, Seq.empty)
    }
  }

  /** Handle a GetByteCodes request
    *
    * Retrieves contract bytecodes by their code hashes.
    *
    * @param request
    *   The GetByteCodes request from a peer
    * @return
    *   ByteCodes response with the requested codes
    */
  def handleGetByteCodes(request: GetByteCodes): ByteCodes = {
    val startTime = System.currentTimeMillis()

    try {
      val codes = mutable.ArrayBuffer.empty[ByteString]
      var bytesCollected = 0L
      val byteLimit = (request.responseBytes.toLong * BytecodeBudgetThreshold).toLong

      for (codeHash <- request.hashes if bytesCollected < byteLimit) {
        evmCodeStorage.get(codeHash) match {
          case Some(code) =>
            // Check if adding this code would exceed budget
            if (bytesCollected + code.size <= byteLimit) {
              codes += code
              bytesCollected += code.size
            }
          case None =>
          // Code not found - skip it (common for deleted contracts)
        }
      }

      val elapsed = System.currentTimeMillis() - startTime
      log.debug(
        s"GetByteCodes: Retrieved ${codes.size}/${request.hashes.size} codes (${bytesCollected} bytes) in ${elapsed}ms"
      )

      ByteCodes(request.requestId, codes.toSeq)
    } catch {
      case e: Exception =>
        log.warn(s"Error handling GetByteCodes: ${e.getMessage}", e)
        ByteCodes(request.requestId, Seq.empty)
    }
  }

  /** Handle a GetTrieNodes request
    *
    * Retrieves specific trie nodes by their paths. Each path is a sequence of bytes where:
    *   - First element: compact-encoded path in the account trie
    *   - Subsequent elements: compact-encoded paths in the storage trie
    *
    * @param request
    *   The GetTrieNodes request from a peer
    * @return
    *   TrieNodes response with the requested nodes
    */
  def handleGetTrieNodes(request: GetTrieNodes): TrieNodes = {
    val startTime = System.currentTimeMillis()

    // Validate state root
    if (stateStorage.getNode(request.rootHash).isEmpty) {
      log.debug(
        s"GetTrieNodes: State root not found: ${request.rootHash.take(8).toArray.map("%02x".format(_)).mkString}"
      )
      return TrieNodes(request.requestId, Seq.empty)
    }

    try {
      val nodes = mutable.ArrayBuffer.empty[ByteString]
      var bytesCollected = 0L
      val byteLimit = request.responseBytes.toLong

      for (pathSet <- request.paths if bytesCollected < byteLimit && pathSet.nonEmpty) {
        // First path is in the account trie
        val accountPath = pathSet.head

        if (pathSet.size == 1) {
          // Just looking up an account trie node
          lookupNodeByPath(request.rootHash, accountPath) match {
            case Some(nodeBytes) if bytesCollected + nodeBytes.size <= byteLimit =>
              nodes += nodeBytes
              bytesCollected += nodeBytes.size
            case _ => // Node not found or would exceed budget
          }
        } else {
          // Looking up storage trie nodes
          // First, resolve the account to get its storage root
          val accountHash = pathToHash(accountPath)
          lookupAccount(request.rootHash, accountHash) match {
            case Some(account) =>
              // Process storage paths
              for (storagePath <- pathSet.tail if bytesCollected < byteLimit) {
                lookupNodeByPath(account.storageRoot, storagePath) match {
                  case Some(nodeBytes) if bytesCollected + nodeBytes.size <= byteLimit =>
                    nodes += nodeBytes
                    bytesCollected += nodeBytes.size
                  case _ => // Node not found or would exceed budget
                }
              }
            case None =>
            // Account not found
          }
        }
      }

      val elapsed = System.currentTimeMillis() - startTime
      log.debug(
        s"GetTrieNodes: Retrieved ${nodes.size} nodes (${bytesCollected} bytes) in ${elapsed}ms"
      )

      TrieNodes(request.requestId, nodes.toSeq)
    } catch {
      case e: Exception =>
        log.warn(s"Error handling GetTrieNodes: ${e.getMessage}", e)
        TrieNodes(request.requestId, Seq.empty)
    }
  }

  /** Collect accounts in a hash range by traversing the trie
    *
    * @param node
    *   Current trie node
    * @param prefix
    *   Accumulated key prefix (nibbles)
    * @param startHash
    *   Lower bound of range (inclusive)
    * @param limitHash
    *   Upper bound of range (exclusive)
    * @param byteLimit
    *   Maximum bytes to collect
    * @param accounts
    *   Buffer to collect accounts
    * @param getBytes
    *   Function to get current byte count
    * @param addBytes
    *   Function to add to byte count
    */
  private def collectAccountsInRange(
      node: MptNode,
      prefix: Seq[Int],
      startHash: ByteString,
      limitHash: ByteString,
      byteLimit: Long,
      accounts: mutable.ArrayBuffer[(ByteString, Account)],
      getBytes: () => Long,
      addBytes: Long => Unit
  ): Unit = {
    // Check byte budget
    if (getBytes() >= byteLimit || accounts.size >= MaxAccountsPerResponse) return

    node match {
      case leaf: LeafNode =>
        // Reconstruct the full key from prefix + leaf key
        val leafNibbles = leaf.key.map(_.toInt & NibbleMask)
        val fullNibbles = prefix ++ leafNibbles
        val keyHash = nibblesToHash(fullNibbles)

        // Check if key is in range
        if (compareHashes(keyHash, startHash) >= 0 && compareHashes(keyHash, limitHash) < 0) {
          Try(Account.accountSerializer.fromBytes(leaf.value.toArray)).toOption match {
            case Some(account) =>
              val accountSize = leaf.value.size + keyHash.size
              if (getBytes() + accountSize <= byteLimit) {
                accounts += ((keyHash, account))
                addBytes(accountSize)
              }
            case None =>
              log.debug(s"Failed to decode account at key: ${keyHash.take(8).toArray.map("%02x".format(_)).mkString}")
          }
        }

      case branch: BranchNode =>
        // Determine which children to visit based on range
        for (i <- 0 until 16 if getBytes() < byteLimit && accounts.size < MaxAccountsPerResponse) {
          val childPrefix = prefix :+ i
          val minKeyInSubtree = nibblesToHash(childPrefix.padTo(64, 0))
          val maxKeyInSubtree = nibblesToHash(childPrefix.padTo(64, 15))

          // Skip subtree if entirely outside range
          if (compareHashes(maxKeyInSubtree, startHash) >= 0 && compareHashes(minKeyInSubtree, limitHash) < 0) {
            branch.children(i) match {
              case hashNode: HashNode =>
                stateStorage.getNode(ByteString(hashNode.hash)) match {
                  case Some(child) =>
                    collectAccountsInRange(child, childPrefix, startHash, limitHash, byteLimit, accounts, getBytes, addBytes)
                  case None => // Node not found
                }
              case child if !child.isNull =>
                collectAccountsInRange(child, childPrefix, startHash, limitHash, byteLimit, accounts, getBytes, addBytes)
              case _ => // Null child
            }
          }
        }

        // Check branch terminator
        branch.terminator.foreach { value =>
          val keyHash = nibblesToHash(prefix.padTo(64, 0))
          if (compareHashes(keyHash, startHash) >= 0 && compareHashes(keyHash, limitHash) < 0) {
            Try(Account.accountSerializer.fromBytes(value.toArray)).toOption match {
              case Some(account) =>
                val accountSize = value.size + keyHash.size
                if (getBytes() + accountSize <= byteLimit) {
                  accounts += ((keyHash, account))
                  addBytes(accountSize)
                }
              case None =>
            }
          }
        }

      case ext: ExtensionNode =>
        val extNibbles = ext.sharedKey.map(_.toInt & NibbleMask)
        val newPrefix = prefix ++ extNibbles

        // Check if this subtree could contain keys in range
        val minKeyInSubtree = nibblesToHash(newPrefix.padTo(64, 0))
        val maxKeyInSubtree = nibblesToHash(newPrefix.padTo(64, 15))

        if (compareHashes(maxKeyInSubtree, startHash) >= 0 && compareHashes(minKeyInSubtree, limitHash) < 0) {
          ext.next match {
            case hashNode: HashNode =>
              stateStorage.getNode(ByteString(hashNode.hash)) match {
                case Some(child) =>
                  collectAccountsInRange(child, newPrefix, startHash, limitHash, byteLimit, accounts, getBytes, addBytes)
                case None =>
              }
            case child =>
              collectAccountsInRange(child, newPrefix, startHash, limitHash, byteLimit, accounts, getBytes, addBytes)
          }
        }

      case hashNode: HashNode =>
        stateStorage.getNode(ByteString(hashNode.hash)) match {
          case Some(child) =>
            collectAccountsInRange(child, prefix, startHash, limitHash, byteLimit, accounts, getBytes, addBytes)
          case None =>
        }

      case NullNode => // Nothing to collect
    }
  }

  /** Collect storage slots in a hash range by traversing the storage trie */
  private def collectStorageSlotsInRange(
      node: MptNode,
      prefix: Seq[Int],
      startHash: ByteString,
      limitHash: ByteString,
      byteLimit: Long,
      slots: mutable.ArrayBuffer[(ByteString, ByteString)],
      getBytes: () => Long,
      addBytes: Long => Unit
  ): Unit = {
    if (getBytes() >= byteLimit || slots.size >= MaxStorageSlotsPerAccount) return

    node match {
      case leaf: LeafNode =>
        val leafNibbles = leaf.key.map(_.toInt & NibbleMask)
        val fullNibbles = prefix ++ leafNibbles
        val slotHash = nibblesToHash(fullNibbles)

        if (compareHashes(slotHash, startHash) >= 0 && compareHashes(slotHash, limitHash) < 0) {
          val slotSize = leaf.value.size + slotHash.size
          if (getBytes() + slotSize <= byteLimit) {
            slots += ((slotHash, leaf.value))
            addBytes(slotSize)
          }
        }

      case branch: BranchNode =>
        for (i <- 0 until 16 if getBytes() < byteLimit && slots.size < MaxStorageSlotsPerAccount) {
          val childPrefix = prefix :+ i
          val minKeyInSubtree = nibblesToHash(childPrefix.padTo(64, 0))
          val maxKeyInSubtree = nibblesToHash(childPrefix.padTo(64, 15))

          if (compareHashes(maxKeyInSubtree, startHash) >= 0 && compareHashes(minKeyInSubtree, limitHash) < 0) {
            branch.children(i) match {
              case hashNode: HashNode =>
                stateStorage.getNode(ByteString(hashNode.hash)) match {
                  case Some(child) =>
                    collectStorageSlotsInRange(child, childPrefix, startHash, limitHash, byteLimit, slots, getBytes, addBytes)
                  case None =>
                }
              case child if !child.isNull =>
                collectStorageSlotsInRange(child, childPrefix, startHash, limitHash, byteLimit, slots, getBytes, addBytes)
              case _ =>
            }
          }
        }

        branch.terminator.foreach { value =>
          val slotHash = nibblesToHash(prefix.padTo(64, 0))
          if (compareHashes(slotHash, startHash) >= 0 && compareHashes(slotHash, limitHash) < 0) {
            val slotSize = value.size + slotHash.size
            if (getBytes() + slotSize <= byteLimit) {
              slots += ((slotHash, value))
              addBytes(slotSize)
            }
          }
        }

      case ext: ExtensionNode =>
        val extNibbles = ext.sharedKey.map(_.toInt & NibbleMask)
        val newPrefix = prefix ++ extNibbles
        val minKeyInSubtree = nibblesToHash(newPrefix.padTo(64, 0))
        val maxKeyInSubtree = nibblesToHash(newPrefix.padTo(64, 15))

        if (compareHashes(maxKeyInSubtree, startHash) >= 0 && compareHashes(minKeyInSubtree, limitHash) < 0) {
          ext.next match {
            case hashNode: HashNode =>
              stateStorage.getNode(ByteString(hashNode.hash)) match {
                case Some(child) =>
                  collectStorageSlotsInRange(child, newPrefix, startHash, limitHash, byteLimit, slots, getBytes, addBytes)
                case None =>
              }
            case child =>
              collectStorageSlotsInRange(child, newPrefix, startHash, limitHash, byteLimit, slots, getBytes, addBytes)
          }
        }

      case hashNode: HashNode =>
        stateStorage.getNode(ByteString(hashNode.hash)) match {
          case Some(child) =>
            collectStorageSlotsInRange(child, prefix, startHash, limitHash, byteLimit, slots, getBytes, addBytes)
          case None =>
        }

      case NullNode =>
    }
  }

  /** Look up an account by traversing the state trie */
  private def lookupAccount(stateRoot: ByteString, accountHash: ByteString): Option[Account] = {
    val nibbles = hashToNibbles(accountHash)

    def traverse(node: MptNode, path: Seq[Int]): Option[Account] = node match {
      case leaf: LeafNode =>
        val leafNibbles = leaf.key.map(_.toInt & NibbleMask)
        if (path == leafNibbles) {
          Try(Account.accountSerializer.fromBytes(leaf.value.toArray)).toOption
        } else {
          None
        }

      case branch: BranchNode =>
        if (path.isEmpty) {
          branch.terminator.flatMap { value =>
            Try(Account.accountSerializer.fromBytes(value.toArray)).toOption
          }
        } else {
          branch.children(path.head) match {
            case hashNode: HashNode =>
              stateStorage.getNode(ByteString(hashNode.hash)).flatMap(traverse(_, path.tail))
            case child if !child.isNull =>
              traverse(child, path.tail)
            case _ => None
          }
        }

      case ext: ExtensionNode =>
        val extNibbles = ext.sharedKey.map(_.toInt & NibbleMask)
        if (path.startsWith(extNibbles)) {
          ext.next match {
            case hashNode: HashNode =>
              stateStorage.getNode(ByteString(hashNode.hash)).flatMap(traverse(_, path.drop(extNibbles.length)))
            case child =>
              traverse(child, path.drop(extNibbles.length))
          }
        } else {
          None
        }

      case hashNode: HashNode =>
        stateStorage.getNode(ByteString(hashNode.hash)).flatMap(traverse(_, path))

      case NullNode => None
    }

    stateStorage.getNode(stateRoot).flatMap(traverse(_, nibbles))
  }

  /** Look up a trie node by path */
  private def lookupNodeByPath(root: ByteString, path: ByteString): Option[ByteString] = {
    val nibbles = hashToNibbles(path)

    def traverse(node: MptNode, remainingPath: Seq[Int]): Option[ByteString] = {
      if (remainingPath.isEmpty) {
        Some(ByteString(node.encode))
      } else {
        node match {
          case branch: BranchNode =>
            branch.children(remainingPath.head) match {
              case hashNode: HashNode =>
                stateStorage.getNode(ByteString(hashNode.hash)).flatMap(traverse(_, remainingPath.tail))
              case child if !child.isNull =>
                traverse(child, remainingPath.tail)
              case _ => None
            }

          case ext: ExtensionNode =>
            val extNibbles = ext.sharedKey.map(_.toInt & NibbleMask)
            if (remainingPath.startsWith(extNibbles)) {
              ext.next match {
                case hashNode: HashNode =>
                  stateStorage.getNode(ByteString(hashNode.hash)).flatMap(traverse(_, remainingPath.drop(extNibbles.length)))
                case child =>
                  traverse(child, remainingPath.drop(extNibbles.length))
              }
            } else {
              None
            }

          case hashNode: HashNode =>
            stateStorage.getNode(ByteString(hashNode.hash)).flatMap(traverse(_, remainingPath))

          case _ => None
        }
      }
    }

    stateStorage.getNode(root).flatMap(traverse(_, nibbles))
  }

  /** Convert a hash to nibbles */
  private def hashToNibbles(hash: ByteString): Seq[Int] =
    hash.flatMap { byte =>
      Seq((byte >> 4) & NibbleMask, byte & NibbleMask)
    }

  /** Convert nibbles back to a hash */
  private def nibblesToHash(nibbles: Seq[Int]): ByteString = {
    val bytes = nibbles.take(64).grouped(2).map { pair =>
      val high = pair.headOption.getOrElse(0)
      val low = pair.lift(1).getOrElse(0)
      ((high << 4) | low).toByte
    }.toArray
    ByteString(bytes)
  }

  /** Decode a compact-encoded trie path (hex-prefix encoding) into nibbles. */
  private def decodeCompactPath(path: ByteString): Seq[Int] = {
    if (path.isEmpty) {
      Seq.empty
    } else {
      val bytes = path.toArray
      val first = bytes(0) & 0xff
      val flagNibble = (first >> 4) & NibbleMask
      val isOddLength = (flagNibble & 0x1) != 0
      // val isLeaf = (flagNibble & 0x2) != 0 // Leaf/extension flag is not needed here

      val nibbles = mutable.ArrayBuffer.empty[Int]

      if (isOddLength) {
        // Low nibble of first byte is the first path nibble
        nibbles += (first & NibbleMask)
      }

      var i = 1
      while (i < bytes.length) {
        val b = bytes(i) & 0xff
        nibbles += ((b >> 4) & NibbleMask)
        nibbles += (b & NibbleMask)
        i += 1
      }

      nibbles.toSeq
    }
  }

  /** Convert a path to hash (for GetTrieNodes) */
  private def pathToHash(path: ByteString): ByteString = {
    val nibbles = decodeCompactPath(path)
    nibblesToHash(nibbles)
  }

  /** Compare two hashes lexicographically */
  private def compareHashes(a: ByteString, b: ByteString): Int = {
    val aArr = a.toArray
    val bArr = b.toArray
    var i = 0
    while (i < math.min(aArr.length, bArr.length)) {
      val ai = aArr(i) & 0xff
      val bi = bArr(i) & 0xff
      if (ai != bi) return ai - bi
      i += 1
    }
    aArr.length - bArr.length
  }
}

object SNAPServerService {

  /** Create a new SNAP server service
    *
    * @param blockchainReader
    *   For verifying state roots
    * @param stateStorage
    *   For accessing trie nodes
    * @param evmCodeStorage
    *   For retrieving bytecodes
    * @return
    *   New service instance
    */
  def apply(
      blockchainReader: BlockchainReader,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage
  ): SNAPServerService =
    new SNAPServerService(blockchainReader, stateStorage, evmCodeStorage)
}
