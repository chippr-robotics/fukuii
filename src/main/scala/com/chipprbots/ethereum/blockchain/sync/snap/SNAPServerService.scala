package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.math.Ordering.Implicits.infixOrderingOps
import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, MptStorage}
import com.chipprbots.ethereum.domain.{Account, BlockchainReader}
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.ByteStringUtils.{ByteStringOps, byteStringOrdering}

/** Server-side SNAP protocol service for serving state data to peers.
  *
  * This service handles incoming SNAP requests from peers and generates appropriate responses
  * by retrieving data from local storage (MPT, bytecode, etc.) and generating Merkle proofs.
  *
  * The implementation follows the SNAP/1 protocol specification:
  * https://github.com/ethereum/devp2p/blob/master/caps/snap.md
  *
  * @param blockchainReader Reader for accessing blockchain data
  * @param appStateStorage Storage for application state
  * @param mptStorage Storage for Merkle Patricia Trie nodes
  * @param evmCodeStorage Storage for EVM bytecode
  */
class SNAPServerService(
    blockchainReader: BlockchainReader,
    appStateStorage: AppStateStorage,
    mptStorage: MptStorage,
    evmCodeStorage: EvmCodeStorage,
    config: SNAPServerService.SNAPServerConfig = null
) extends Logger {

  import SNAPServerService._
  
  // Use default config if not provided
  private val effectiveConfig = if (config == null) SNAPServerConfig() else config

  /** Handle GetAccountRange request and generate AccountRange response.
    *
    * Retrieves accounts from the account trie between startingHash and limitHash,
    * up to the specified byte limit, and generates Merkle proofs for the range.
    *
    * @param request The GetAccountRange request
    * @return AccountRange response with accounts and proof
    */
  def handleGetAccountRange(request: GetAccountRange): AccountRange = {
    log.debug(
      s"Handling GetAccountRange: requestId=${request.requestId}, root=${request.rootHash.take(4).toHex}, " +
        s"start=${request.startingHash.take(4).toHex}, limit=${request.limitHash.take(4).toHex}, bytes=${request.responseBytes}"
    )

    try {
      // Verify we have the requested state root
      val rootNode = try {
        mptStorage.get(request.rootHash.toArray)
      } catch {
        case _: MerklePatriciaTrie.MissingNodeException =>
          log.debug(s"Requested state root not found: ${request.rootHash.take(8).toHex}")
          return AccountRange(request.requestId, Seq.empty, Seq.empty)
      }

      // Collect accounts in the requested range
      val accounts = mutable.ArrayBuffer[(ByteString, Account)]()
      var totalBytes = 0L
      val maxBytes = Math.min(request.responseBytes.toLong, effectiveConfig.maxResponseBytes)

      // Traverse the account trie and collect accounts in range
      collectAccountsInRange(
        rootNode,
        request.startingHash,
        request.limitHash,
        maxBytes,
        accounts
      )

      if (accounts.isEmpty) {
        log.debug(s"No accounts found in requested range")
        return AccountRange(request.requestId, Seq.empty, Seq.empty)
      }

      // Generate Merkle proof for the account range
      val proof = generateAccountRangeProof(
        rootNode,
        accounts.head._1, // First account hash
        accounts.last._1  // Last account hash
      )

      log.debug(
        s"Serving ${accounts.size} accounts with ${proof.size} proof nodes " +
          s"(total ${totalBytes} bytes) for request ${request.requestId}"
      )

      AccountRange(
        requestId = request.requestId,
        accounts = accounts.toSeq,
        proof = proof
      )

    } catch {
      case NonFatal(e) =>
        log.error(s"Error handling GetAccountRange request ${request.requestId}: ${e.getMessage}", e)
        AccountRange(request.requestId, Seq.empty, Seq.empty)
    }
  }

  /** Handle GetStorageRanges request and generate StorageRanges response.
    *
    * Retrieves storage slots for the specified accounts, between startingHash and limitHash,
    * up to the specified byte limit, and generates Merkle proofs for each account's storage.
    *
    * @param request The GetStorageRanges request
    * @return StorageRanges response with storage slots and proofs
    */
  def handleGetStorageRanges(request: GetStorageRanges): StorageRanges = {
    log.debug(
      s"Handling GetStorageRanges: requestId=${request.requestId}, root=${request.rootHash.take(4).toHex}, " +
        s"accounts=${request.accountHashes.size}, start=${request.startingHash.take(4).toHex}, " +
        s"limit=${request.limitHash.take(4).toHex}, bytes=${request.responseBytes}"
    )

    try {
      // Verify we have the requested state root
      val rootNode = try {
        mptStorage.get(request.rootHash.toArray)
      } catch {
        case _: MerklePatriciaTrie.MissingNodeException =>
          log.debug(s"Requested state root not found: ${request.rootHash.take(8).toHex}")
          return StorageRanges(request.requestId, Seq.empty, Seq.empty)
      }

      val allSlots = mutable.ArrayBuffer[Seq[(ByteString, ByteString)]]()
      val allProofs = mutable.ArrayBuffer[ByteString]()
      var totalBytes = 0L
      val maxBytes = Math.min(request.responseBytes.toLong, effectiveConfig.maxResponseBytes)

      // For each requested account, retrieve its storage slots
      for (accountHash <- request.accountHashes if totalBytes < maxBytes) {
        // Get the account to find its storage root
        val accountOpt = getAccount(rootNode, accountHash)

        accountOpt match {
          case Some(account) if account.storageRoot != Account.EmptyStorageRootHash =>
            // Account has storage - retrieve slots in the requested range
            val storageRootOpt = try {
              Some(mptStorage.get(account.storageRoot.toArray))
            } catch {
              case _: MerklePatriciaTrie.MissingNodeException =>
                log.debug(s"Storage root not found for account ${accountHash.take(8).toHex}")
                allSlots += Seq.empty
                None
            }

            storageRootOpt.foreach { root =>
              val slots = mutable.ArrayBuffer[(ByteString, ByteString)]()
              collectStorageSlotsInRange(
                root,
                request.startingHash,
                request.limitHash,
                maxBytes - totalBytes,
                slots
              )

              allSlots += slots.toSeq

              // Generate proof for this account's storage range
              if (slots.nonEmpty) {
                val proof = generateStorageRangeProof(root, slots.head._1, slots.last._1)
                allProofs ++= proof
              }

              totalBytes += slots.map { case (k, v) => k.size + v.size }.sum
            }

          case Some(_) =>
            // Account exists but has no storage
            allSlots += Seq.empty

          case None =>
            // Account not found
            log.debug(s"Account not found: ${accountHash.take(8).toHex}")
            allSlots += Seq.empty
        }
      }

      log.debug(
        s"Serving ${allSlots.map(_.size).sum} storage slots across ${allSlots.size} accounts " +
          s"with ${allProofs.size} proof nodes for request ${request.requestId}"
      )

      StorageRanges(
        requestId = request.requestId,
        slots = allSlots.toSeq,
        proof = allProofs.toSeq
      )

    } catch {
      case NonFatal(e) =>
        log.error(s"Error handling GetStorageRanges request ${request.requestId}: ${e.getMessage}", e)
        StorageRanges(request.requestId, Seq.empty, Seq.empty)
    }
  }

  /** Handle GetByteCodes request and generate ByteCodes response.
    *
    * Retrieves contract bytecodes for the specified code hashes,
    * up to the specified byte limit.
    *
    * @param request The GetByteCodes request
    * @return ByteCodes response with contract bytecodes
    */
  def handleGetByteCodes(request: GetByteCodes): ByteCodes = {
    log.debug(
      s"Handling GetByteCodes: requestId=${request.requestId}, hashes=${request.hashes.size}, " +
        s"bytes=${request.responseBytes}"
    )

    try {
      val codes = mutable.ArrayBuffer[ByteString]()
      var totalBytes = 0L
      val maxBytes = Math.min(request.responseBytes.toLong, effectiveConfig.maxResponseBytes)

      // Retrieve bytecodes for each requested hash
      val iterator = request.hashes.iterator
      var continue = true
      while (iterator.hasNext && continue) {
        // Stop if we've consumed significant portion of the byte budget (conservative approach)
        // to avoid unnecessary storage lookups for codes that likely won't fit
        if (totalBytes > maxBytes * SNAPServerService.BytecodeBudgetThreshold) {
          log.debug(s"Approaching byte limit (${totalBytes}/${maxBytes}), stopping bytecode retrieval")
          continue = false
        } else {
          val codeHash = iterator.next()
          evmCodeStorage.get(codeHash) match {
            case Some(code) =>
              val codeSize = code.size
              if (totalBytes + codeSize <= maxBytes) {
                codes += code
                totalBytes += codeSize
              } else {
                // Would exceed byte limit - stop here
                log.debug(s"Stopping bytecode retrieval at ${codes.size} codes (byte limit reached)")
                continue = false
              }

            case None =>
              log.debug(s"Bytecode not found for hash: ${codeHash.take(8).toHex}")
              // SNAP spec: missing bytecodes are omitted from response, not replaced with empty
          }
        }
      }

      log.debug(
        s"Serving ${codes.size} bytecodes (${totalBytes} bytes total) for request ${request.requestId}"
      )

      ByteCodes(
        requestId = request.requestId,
        codes = codes.toSeq
      )

    } catch {
      case NonFatal(e) =>
        log.error(s"Error handling GetByteCodes request ${request.requestId}: ${e.getMessage}", e)
        ByteCodes(request.requestId, Seq.empty)
    }
  }

  /** Handle GetTrieNodes request and generate TrieNodes response.
    *
    * Retrieves trie nodes for the specified paths, used for state healing.
    * Each path is a list of node hashes that forms a path from the root.
    *
    * @param request The GetTrieNodes request
    * @return TrieNodes response with requested trie nodes
    */
  def handleGetTrieNodes(request: GetTrieNodes): TrieNodes = {
    log.debug(
      s"Handling GetTrieNodes: requestId=${request.requestId}, root=${request.rootHash.take(4).toHex}, " +
        s"paths=${request.paths.size}, bytes=${request.responseBytes}"
    )

    try {
      // Verify we have the requested state root
      try {
        mptStorage.get(request.rootHash.toArray)
      } catch {
        case _: MerklePatriciaTrie.MissingNodeException =>
          log.debug(s"Requested state root not found: ${request.rootHash.take(8).toHex}")
          return TrieNodes(request.requestId, Seq.empty)
      }

      val nodes = mutable.ArrayBuffer[ByteString]()
      var totalBytes = 0L
      val maxBytes = Math.min(request.responseBytes.toLong, effectiveConfig.maxResponseBytes)

      // For each path, retrieve the terminal node
      for (path <- request.paths if totalBytes < maxBytes) {
        if (path.nonEmpty) {
          // The last hash in the path is the node we need to retrieve
          val nodeHash = path.last

          try {
            val node = mptStorage.get(nodeHash.toArray)
            val nodeEncoded = node.encode

            if (totalBytes + nodeEncoded.size <= maxBytes) {
              nodes += ByteString(nodeEncoded)
              totalBytes += nodeEncoded.size
            } else {
              // Would exceed byte limit - stop here
              log.debug(s"Stopping trie node retrieval at ${nodes.size} nodes (byte limit reached)")
            }

          } catch {
            case _: MerklePatriciaTrie.MissingNodeException =>
              log.debug(s"Trie node not found: ${nodeHash.take(8).toHex}")
              // SNAP spec: missing nodes are omitted from response
          }
        }
      }

      log.debug(
        s"Serving ${nodes.size} trie nodes (${totalBytes} bytes total) for request ${request.requestId}"
      )

      TrieNodes(
        requestId = request.requestId,
        nodes = nodes.toSeq
      )

    } catch {
      case NonFatal(e) =>
        log.error(s"Error handling GetTrieNodes request ${request.requestId}: ${e.getMessage}", e)
        TrieNodes(request.requestId, Seq.empty)
    }
  }

  /** Collect accounts from the trie within the specified hash range.
    *
    * @param node Current node being traversed
    * @param startHash Starting account hash (inclusive)
    * @param limitHash Ending account hash (exclusive)
    * @param maxBytes Maximum response size in bytes
    * @param accounts Buffer to collect accounts
    */
  private def collectAccountsInRange(
      node: MptNode,
      startHash: ByteString,
      limitHash: ByteString,
      maxBytes: Long,
      accounts: mutable.ArrayBuffer[(ByteString, Account)]
  ): Unit = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    def traverse(currentNode: MptNode, currentPath: ByteString): Unit = {
      if (accounts.map { case (h, a) => h.size + accountSerializer.toBytes(a).length }.sum.toLong >= maxBytes) {
        return // Reached byte limit
      }

      currentNode match {
        case leaf: LeafNode =>
          // Reconstruct the full key from the path
          val (decodedKey, _) = HexPrefix.decode(leaf.key.toArray)
          val fullKey = currentPath ++ ByteString(decodedKey)
          val accountHash = ByteString(Node.hashFn(fullKey.toArray))

          // Check if account hash is within range [startHash, limitHash)
          if (accountHash >= startHash && accountHash < limitHash) {
            try {
              val account = accountSerializer.fromBytes(leaf.value.toArray)
              accounts += ((accountHash, account))
            } catch {
              case NonFatal(e) =>
                log.debug(s"Failed to deserialize account at ${accountHash.take(8).toHex}: ${e.getMessage}")
            }
          }

        case ext: ExtensionNode =>
          // Continue down the extension
          val (decodedKey, _) = HexPrefix.decode(ext.sharedKey.toArray)
          val extPath = ByteString(decodedKey)
          traverse(ext.next, currentPath ++ extPath)

        case branch: BranchNode =>
          // Check if branch has a terminator (account at this node)
          branch.terminator.foreach { value =>
            val accountHash = ByteString(Node.hashFn(currentPath.toArray))
            if (accountHash >= startHash && accountHash < limitHash) {
              try {
                val account = accountSerializer.fromBytes(value.toArray)
                accounts += ((accountHash, account))
              } catch {
                case NonFatal(e) =>
                  log.debug(s"Failed to deserialize account at ${accountHash.take(8).toHex}: ${e.getMessage}")
              }
            }
          }

          // Traverse children
          branch.children.zipWithIndex.foreach { case (childNode, index) =>
            childNode match {
              case NullNode => // Skip null children
              case hash: HashNode =>
                // Resolve hash node and continue
                try {
                  val resolvedNode = mptStorage.get(hash.hash)
                  traverse(resolvedNode, ByteString(currentPath :+ index.toByte))
                } catch {
                  case _: MerklePatriciaTrie.MissingNodeException =>
                    // Node missing - skip this branch
                    ()
                }
              case _ =>
                traverse(childNode, ByteString(currentPath :+ index.toByte))
            }
          }

        case _: HashNode | NullNode =>
          // These should have been resolved by the caller
          ()
      }
    }

    traverse(node, ByteString.empty)
  }

  /** Collect storage slots from the storage trie within the specified hash range.
    *
    * @param node Current node being traversed
    * @param startHash Starting slot hash (inclusive)
    * @param limitHash Ending slot hash (exclusive)
    * @param maxBytes Maximum response size in bytes
    * @param slots Buffer to collect storage slots
    */
  private def collectStorageSlotsInRange(
      node: MptNode,
      startHash: ByteString,
      limitHash: ByteString,
      maxBytes: Long,
      slots: mutable.ArrayBuffer[(ByteString, ByteString)]
  ): Unit = {
    def traverse(currentNode: MptNode, currentPath: ByteString): Unit = {
      if (slots.map { case (k, v) => k.size + v.size }.sum >= maxBytes) {
        return // Reached byte limit
      }

      currentNode match {
        case leaf: LeafNode =>
          val (decodedKey, _) = HexPrefix.decode(leaf.key.toArray)
          val fullKey = currentPath ++ ByteString(decodedKey)
          val slotHash = ByteString(Node.hashFn(fullKey.toArray))

          if (slotHash >= startHash && slotHash < limitHash) {
            slots += ((slotHash, leaf.value))
          }

        case ext: ExtensionNode =>
          val (decodedKey, _) = HexPrefix.decode(ext.sharedKey.toArray)
          val extPath = ByteString(decodedKey)
          traverse(ext.next, currentPath ++ extPath)

        case branch: BranchNode =>
          branch.terminator.foreach { value =>
            val slotHash = ByteString(Node.hashFn(currentPath.toArray))
            if (slotHash >= startHash && slotHash < limitHash) {
              slots += ((slotHash, value))
            }
          }

          branch.children.zipWithIndex.foreach { case (childNode, index) =>
            childNode match {
              case NullNode =>
              case hash: HashNode =>
                try {
                  val resolvedNode = mptStorage.get(hash.hash)
                  traverse(resolvedNode, ByteString(currentPath :+ index.toByte))
                } catch {
                  case _: MerklePatriciaTrie.MissingNodeException => ()
                }
              case _ =>
                traverse(childNode, ByteString(currentPath :+ index.toByte))
            }
          }

        case _: HashNode | NullNode => ()
      }
    }

    traverse(node, ByteString.empty)
  }

  /** Get an account from the account trie by its hash.
    *
    * Performs a full MPT lookup by traversing the trie using the account hash nibbles.
    * This is production-ready and follows the standard MPT key lookup algorithm.
    *
    * @param rootNode Root of the account trie
    * @param accountHash Hash of the account to retrieve
    * @return Option containing the account if found
    */
  private def getAccount(rootNode: MptNode, accountHash: ByteString): Option[Account] = {
    import com.chipprbots.ethereum.domain.Account.accountSerializer
    
    // Convert account hash to nibbles for MPT traversal
    val keyNibbles = HexPrefix.bytesToNibbles(accountHash.toArray)

    /** Traverse the MPT using key nibbles to find the account.
      *
      * @param node Current node in traversal
      * @param remainingKey Remaining key nibbles to match
      * @return Option containing the account if found at this path
      */
    def traverse(node: MptNode, remainingKey: Array[Byte]): Option[Account] = {
      node match {
        case leaf: LeafNode =>
          // Decode the leaf key and check if it matches our remaining key
          val (decodedKey, _) = HexPrefix.decode(leaf.key.toArray)
          if (decodedKey.sameElements(remainingKey)) {
            try {
              Some(accountSerializer.fromBytes(leaf.value.toArray))
            } catch {
              case NonFatal(_) => None
            }
          } else {
            None
          }

        case ext: ExtensionNode =>
          // Decode the extension's shared key
          val (sharedKey, _) = HexPrefix.decode(ext.sharedKey.toArray)
          
          // Check if our remaining key starts with the shared key
          if (remainingKey.length >= sharedKey.length && 
              remainingKey.take(sharedKey.length).sameElements(sharedKey)) {
            // Continue traversal with the next node and remaining key
            val newRemainingKey = remainingKey.drop(sharedKey.length)
            traverse(ext.next, newRemainingKey)
          } else {
            None
          }

        case branch: BranchNode =>
          if (remainingKey.isEmpty) {
            // We've consumed all key nibbles, check for a value at this branch node
            branch.terminator.flatMap { value =>
              try {
                Some(accountSerializer.fromBytes(value.toArray))
              } catch {
                case NonFatal(_) => None
              }
            }
          } else {
            // Use the first nibble to select which child to traverse
            val childIndex = remainingKey(0) & SNAPServerService.NibbleMask
            val child = branch.children(childIndex)
            val newRemainingKey = remainingKey.drop(1)
            traverse(child, newRemainingKey)
          }

        case hash: HashNode =>
          // Resolve the hash node from storage and continue traversal
          try {
            val resolved = mptStorage.get(hash.hash)
            traverse(resolved, remainingKey)
          } catch {
            case _: MerklePatriciaTrie.MissingNodeException => None
          }

        case NullNode => 
          None
      }
    }

    traverse(rootNode, keyNibbles)
  }

  /** Generate Merkle proof for an account range.
    *
    * According to the SNAP spec, a range proof must include all nodes along the paths
    * to both the first and last account in the range (boundary proofs). This allows
    * the client to verify:
    * 1. The first account is at the correct position in the trie
    * 2. The last account is at the correct position in the trie
    * 3. All accounts between first and last are consecutive and complete
    *
    * @param rootNode Root of the account trie
    * @param firstAccountHash Hash of first account in range
    * @param lastAccountHash Hash of last account in range
    * @return Sequence of proof nodes (RLP-encoded)
    */
  private def generateAccountRangeProof(
      rootNode: MptNode,
      firstAccountHash: ByteString,
      lastAccountHash: ByteString
  ): Seq[ByteString] = {
    val proofNodes = mutable.LinkedHashSet[ByteString]() // Use LinkedHashSet to maintain order and avoid duplicates
    
    // Generate path proof for the first account (left boundary)
    val firstKeyNibbles = HexPrefix.bytesToNibbles(firstAccountHash.toArray)
    collectProofNodesForPath(rootNode, firstKeyNibbles, proofNodes)
    
    // Generate path proof for the last account (right boundary)
    val lastKeyNibbles = HexPrefix.bytesToNibbles(lastAccountHash.toArray)
    collectProofNodesForPath(rootNode, lastKeyNibbles, proofNodes)
    
    proofNodes.toSeq
  }

  /** Collect all nodes along the path to a specific key in the trie.
    *
    * This traverses the trie from root to leaf following the key nibbles,
    * adding each node encountered to the proof set.
    *
    * @param node Current node in traversal
    * @param keyNibbles Key nibbles to follow
    * @param proofNodes Set to accumulate proof nodes
    */
  private def collectProofNodesForPath(
      node: MptNode,
      keyNibbles: Array[Byte],
      proofNodes: mutable.LinkedHashSet[ByteString]
  ): Unit = {
    // Add the current node to the proof (unless it's NullNode)
    node match {
      case NullNode => 
        // Don't add null nodes to proof
        return
      case _ =>
        proofNodes += ByteString(node.encode)
    }

    // Recursively traverse based on node type
    node match {
      case leaf: LeafNode =>
        // Reached a leaf - proof path is complete
        ()

      case ext: ExtensionNode =>
        // Decode the extension's shared key
        val (sharedKey, _) = HexPrefix.decode(ext.sharedKey.toArray)
        
        // If our key starts with the shared key, continue down the extension
        if (keyNibbles.length >= sharedKey.length && 
            keyNibbles.take(sharedKey.length).sameElements(sharedKey)) {
          val remainingKey = keyNibbles.drop(sharedKey.length)
          collectProofNodesForPath(ext.next, remainingKey, proofNodes)
        }
        // If key doesn't match, stop here (path doesn't exist)

      case branch: BranchNode =>
        if (keyNibbles.isEmpty) {
          // Reached the target at this branch node
          ()
        } else {
          // Select child based on first nibble
          val childIndex = keyNibbles(0) & SNAPServerService.NibbleMask
          val child = branch.children(childIndex)
          val remainingKey = keyNibbles.drop(1)
          collectProofNodesForPath(child, remainingKey, proofNodes)
        }

      case hash: HashNode =>
        // Resolve hash node and continue
        try {
          val resolved = mptStorage.get(hash.hash)
          collectProofNodesForPath(resolved, keyNibbles, proofNodes)
        } catch {
          case _: MerklePatriciaTrie.MissingNodeException =>
            // Node missing - can't continue proof path
            ()
        }

      case NullNode =>
        // Already handled above
        ()
    }
  }

  /** Generate Merkle proof for a storage range.
    *
    * According to the SNAP spec, a range proof must include all nodes along the paths
    * to both the first and last storage slot in the range (boundary proofs). This allows
    * the client to verify:
    * 1. The first slot is at the correct position in the storage trie
    * 2. The last slot is at the correct position in the storage trie
    * 3. All slots between first and last are consecutive and complete
    *
    * @param rootNode Root of the storage trie
    * @param firstSlotHash Hash of first slot in range
    * @param lastSlotHash Hash of last slot in range
    * @return Sequence of proof nodes (RLP-encoded)
    */
  private def generateStorageRangeProof(
      rootNode: MptNode,
      firstSlotHash: ByteString,
      lastSlotHash: ByteString
  ): Seq[ByteString] = {
    val proofNodes = mutable.LinkedHashSet[ByteString]() // Use LinkedHashSet to maintain order and avoid duplicates
    
    // Generate path proof for the first storage slot (left boundary)
    val firstKeyNibbles = HexPrefix.bytesToNibbles(firstSlotHash.toArray)
    collectProofNodesForPath(rootNode, firstKeyNibbles, proofNodes)
    
    // Generate path proof for the last storage slot (right boundary)
    val lastKeyNibbles = HexPrefix.bytesToNibbles(lastSlotHash.toArray)
    collectProofNodesForPath(rootNode, lastKeyNibbles, proofNodes)
    
    proofNodes.toSeq
  }
}

object SNAPServerService {
  /** MPT nibble mask - used to extract 4-bit nibble values (0-15) */
  private[snap] val NibbleMask: Int = 0xf
  
  /** Conservative byte budget threshold for bytecode retrieval.
    * When we've consumed this fraction of the byte budget, we stop
    * attempting additional bytecode retrievals to avoid unnecessary
    * storage lookups that likely won't fit.
    */
  private[snap] val BytecodeBudgetThreshold: Double = 0.6
  
  /** Configuration for SNAP server behavior */
  case class SNAPServerConfig(
      maxResponseBytes: Long = 2 * 1024 * 1024, // 2MB max response size
      maxAccountsPerResponse: Int = 4096,
      maxStorageSlotsPerResponse: Int = 8192,
      maxByteCodesPerResponse: Int = 256,
      maxTrieNodesPerResponse: Int = 1024
  )
}
