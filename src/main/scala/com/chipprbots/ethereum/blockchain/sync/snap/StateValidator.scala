package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
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
  *
  * Every read says where it is (spec 018 Track C): the account trie under no owner, a storage trie under its account's
  * hash, and the path the walk has come down. A store keyed by hash ignores all three, and each walk answers on it as
  * it always did, in the same order. Nor is a fault of the state a hole (a view the disk moved away from, a state that
  * is not held): like a corrupt node it ends the walk, and the entry point returns `Left` for it. Queued for healing,
  * it would have a node that is there fetched again.
  */
class StateValidator(mptStorage: MptStorage):

  private val log = LoggerFactory.getLogger(classOf[StateValidator])

  private def corruptFailure(e: MerklePatriciaTrie.CorruptNodeException): String =
    s"Corrupt trie node, not a hole: ${e.getMessage}"

  private def stateFault(fault: StateFault): String = s"State fault, not a hole: ${fault.getMessage}"

  private val accountTrie: MptStorage = mptStorage.forOwner(None)
  private def at(nibbles: Array[Byte]): NibblePath = NibblePath.fromNibbles(nibbles)

  /** The account a leaf of the account trie is, by the nibbles of its key: what names its storage trie. A key of a
    * state is 64 nibbles. One that is not (a value on a branch, a short key) is no state's. On a hash store the name is
    * not used, and the walk goes on as it always did. Where the name finds the trie it is a corrupt account trie, not a
    * hole to heal: `at` is the node that holds the account.
    */
  private def accountAt(nibbles: Array[Byte], at: MptNode): ByteString =
    if nibbles.length == 64 then ByteString(HexPrefix.nibblesToBytes(nibbles))
    else if aNodeIsItsHash then ByteString(nibbles)
    else throw notAStateKey(nibbles.length, at)

  private def notAStateKey(nibbles: Int, at: MptNode): MerklePatriciaTrie.CorruptNodeException =
    new MerklePatriciaTrie.CorruptNodeException(
      ByteString(at.hash),
      s"it holds an account under a key of $nibbles nibbles, where a key of the state has 64"
    )

  // A walk skips a node whose hash it has seen. Where a node is stored once whatever holds it, that is the node. Where
  // each position has a row of its own it is another row, which may be missing: and a walk of a tree comes to each
  // position once, so nothing is skipped there.
  private val aNodeIsItsHash: Boolean = mptStorage.nodesSharedAcrossTries
  private def firstVisit(visited: mutable.Set[ByteString], hash: ByteString): Boolean =
    !aNodeIsItsHash || visited.add(hash)

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
        val rootNode = accountTrie.getAt(NibblePath.Empty, stateRoot.toArray)
        traverseForMissingNodes(rootNode, accountTrie, Array.emptyByteArray, missingNodes)

        if missingNodes.isEmpty then Right(Seq.empty)
        else Right(missingNodes.toSeq)
      catch
        case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
        case _: MerklePatriciaTrie.MissingNodeException =>
          Left(s"Missing root node: ${stateRoot.take(8).toHex}")
        case fault: StateFault => Left(stateFault(fault))
        case e: Exception =>
          Left(s"Failed to load root node: ${e.getMessage}")
    catch
      case e: Exception =>
        Left(s"Validation error: ${e.getMessage}")

  /** Validate all storage tries by walking through all accounts and checking their storage. */
  def validateAllStorageTries(stateRoot: ByteString): Either[String, Seq[ByteString]] =
    try
      val missingStorageNodes = mutable.ArrayBuffer[ByteString]()
      val accounts = mutable.ArrayBuffer[(ByteString, Account)]()

      val accountTrieResult =
        try
          val rootNode = accountTrie.getAt(NibblePath.Empty, stateRoot.toArray)
          collectAccounts(rootNode, accountTrie, Array.emptyByteArray, accounts)
          Right(())
        catch
          case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
          case fault: StateFault                          => Left(stateFault(fault))
          case e: Exception =>
            log.warn(s"Failed to traverse account trie for storage validation: ${e.getMessage}", e)
            Left("Cannot validate storage tries: failed to traverse account trie")

      accountTrieResult match
        case Left(err) => Left(err)
        case Right(_) =>
          accounts.foreach { (accountHash, account) =>
            if account.storageRoot != Account.EmptyStorageRootHash then
              try
                // this account's storage trie: two accounts with one storage root hold two tries on a position store
                val slots = mptStorage.forOwner(Some(accountHash))
                val storageRootNode = slots.getAt(NibblePath.Empty, account.storageRoot.toArray)
                traverseForMissingNodes(storageRootNode, slots, Array.emptyByteArray, missingStorageNodes)
              catch
                case e: MerklePatriciaTrie.CorruptNodeException => throw e
                case fault: StateFault                          => throw fault
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
      case fault: StateFault                          => Left(stateFault(fault))
      case e: Exception =>
        Left(s"Storage validation error: ${e.getMessage}")

  /** Recursively traverse a trie and collect missing node hashes. `storage` is the storage of that one trie, and `path`
    * the nibbles from its root to `node`.
    */
  private def traverseForMissingNodes(
      node: MptNode,
      storage: MptStorage,
      path: Array[Byte],
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
        if firstVisit(visited, ByteString(ext.hash)) then
          traverseForMissingNodes(ext.next, storage, path ++ ext.sharedKey.toArray, missingNodes, visited)

      case branch: BranchNode =>
        if firstVisit(visited, ByteString(branch.hash)) then
          var i = 0
          while i < branch.children.length do
            traverseForMissingNodes(branch.children(i), storage, path :+ i.toByte, missingNodes, visited)
            i += 1

      case hash: HashNode =>
        // The resolved node below has this hash too, and is visited for it: the hash node is not marked, only asked.
        if !aNodeIsItsHash || !visited.contains(ByteString(hash.hash)) then
          try
            val resolvedNode = storage.getAt(at(path), hash.hash)
            traverseForMissingNodes(resolvedNode, storage, path, missingNodes, visited)
          catch
            case e: MerklePatriciaTrie.CorruptNodeException => throw e
            case fault: StateFault                          => throw fault
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

  /** Recursively collect all accounts from the account trie, each with the hash it is kept under (its key): what names
    * its storage trie. `path` is the nibbles from the root to `node`.
    */
  private def collectAccounts(
      node: MptNode,
      storage: MptStorage,
      path: Array[Byte],
      accounts: mutable.ArrayBuffer[(ByteString, Account)],
      visited: mutable.Set[ByteString] = mutable.Set.empty
  ): Unit =
    import com.chipprbots.ethereum.domain.Account.accountSerializer

    node match
      case leaf: LeafNode =>
        try
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          accounts += accountAt(path ++ leaf.key.toArray, leaf) -> account
        catch
          case e: MerklePatriciaTrie.CorruptNodeException => throw e
          case e: Exception                               =>
            // A leaf that fails to decode as an Account means its storage trie will not
            // be validated. Log so corrupt account RLP is visible rather than silently
            // dropped (which would make storage validation report a false "intact").
            log.warn(s"Failed to decode account leaf during validation: ${e.getMessage}", e)

      case ext: ExtensionNode =>
        if firstVisit(visited, ByteString(ext.hash)) then
          collectAccounts(ext.next, storage, path ++ ext.sharedKey.toArray, accounts, visited)

      case branch: BranchNode =>
        if firstVisit(visited, ByteString(branch.hash)) then
          var i = 0
          while i < branch.children.length do
            collectAccounts(branch.children(i), storage, path :+ i.toByte, accounts, visited)
            i += 1
          branch.terminator.foreach { value =>
            try
              val account = accountSerializer.fromBytes(value.toArray)
              accounts += accountAt(path, branch) -> account
            catch
              case e: MerklePatriciaTrie.CorruptNodeException => throw e
              case e: Exception                               =>
                // Same rationale as the LeafNode case: a non-decodable terminator account
                // would be silently skipped and its storage trie never validated.
                log.warn(s"Failed to decode account terminator during validation: ${e.getMessage}", e)
          }

      case hash: HashNode =>
        if !aNodeIsItsHash || !visited.contains(ByteString(hash.hash)) then
          try
            val resolvedNode = storage.getAt(at(path), hash.hash)
            collectAccounts(resolvedNode, storage, path, accounts, visited)
          catch
            // Either case means the account subtree below this hash is skipped, so its
            // accounts' storage tries are not validated. The account-trie completeness is
            // checked separately by validateAccountTrie/findMissingNodes; here we only need
            // observability so a non-missing fault (corrupt DB) is not silently masked. A corrupt node fails the
            // collection instead: the storage tries under it would go unvalidated.
            case e: MerklePatriciaTrie.CorruptNodeException => throw e
            case fault: StateFault                          => throw fault
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
      val rootNode = accountTrie.getAt(NibblePath.Empty, stateRoot.toArray)
      walkAccountTrieDFS(rootNode, result)
      Right(result.toSeq)
    catch
      case e: MerklePatriciaTrie.CorruptNodeException => Left(corruptFailure(e))
      case fault: StateFault                          => Left(stateFault(fault))
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
              if !aNodeIsItsHash && fullNibblePath.length != 64 then throw notAStateKey(fullNibblePath.length, leaf)
              val accountHashBytes = HexPrefix.nibblesToBytes(fullNibblePath)
              try
                val storageRoot = mptStorage
                  .forOwner(Some(ByteString(accountHashBytes)))
                  .getAt(NibblePath.Empty, account.storageRoot.toArray)
                // Each storage trie gets its own DFS + visited set (independent walk)
                walkStorageTrieDFS(storageRoot, ByteString(accountHashBytes), result)
              catch
                case e: MerklePatriciaTrie.CorruptNodeException => throw e
                case _: MerklePatriciaTrie.MissingNodeException =>
                  val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                  result += ((Seq(ByteString(accountHashBytes), compactPath), account.storageRoot.value))
          catch
            // Not swallowed with the decode failures below: either ends the walk.
            case e: MerklePatriciaTrie.CorruptNodeException => throw e
            case fault: StateFault                          => throw fault
            case e: Exception                               =>
              // A leaf that fails to decode as an Account means its storage trie is omitted
              // from the healing walk. Log so corrupt account data is visible rather than
              // silently producing a false "no missing nodes" completion signal.
              log.warn(s"Failed to decode account leaf during heal walk: ${e.getMessage}", e)

        case ext: ExtensionNode =>
          if firstVisit(visited, ByteString(ext.hash)) then
            stack.prepend((ext.next, nibblePath ++ ext.sharedKey.toArray))

        case branch: BranchNode =>
          if firstVisit(visited, ByteString(branch.hash)) then
            // Push in reverse order so child 0 is processed first (consistent ordering)
            for i <- 15 to 0 by -1 do
              val child = branch.children(i)
              if !child.isNull then stack.prepend((child, nibblePath :+ i.toByte))

        case hash: HashNode =>
          val hashKey = ByteString(hash.hash)
          if !aNodeIsItsHash || !visited.contains(hashKey) then
            try
              accountTrie.getAt(at(nibblePath), hash.hash) // proven subtree — discoverMissingChildren handles children
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
    val slots = mptStorage.forOwner(Some(accountHash))
    val stack = mutable.ArrayDeque[(MptNode, Array[Byte])]()
    val visited = mutable.Set[ByteString]()
    stack.prepend((rootNode, Array.emptyByteArray))

    while stack.nonEmpty do
      val (node, nibblePath) = stack.removeHead()

      node match
        case _: LeafNode | NullNode => ()

        case ext: ExtensionNode =>
          if firstVisit(visited, ByteString(ext.hash)) then
            stack.prepend((ext.next, nibblePath ++ ext.sharedKey.toArray))

        case branch: BranchNode =>
          if firstVisit(visited, ByteString(branch.hash)) then
            for i <- 15 to 0 by -1 do
              val child = branch.children(i)
              if !child.isNull then stack.prepend((child, nibblePath :+ i.toByte))

        case hash: HashNode =>
          val hashKey = ByteString(hash.hash)
          if !aNodeIsItsHash || !visited.contains(hashKey) then
            try
              val resolvedNode = slots.getAt(at(nibblePath), hash.hash)
              stack.prepend((resolvedNode, nibblePath))
            catch
              case e: MerklePatriciaTrie.CorruptNodeException => throw e
              case _: MerklePatriciaTrie.MissingNodeException =>
                val compactPath = ByteString(HexPrefix.encode(nibblePath, isLeaf = false))
                result += ((Seq(accountHash, compactPath), ByteString(hash.hash)))
