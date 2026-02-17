package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.Actor.Receive
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.NotInfluenceReceiveTimeout
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.ReceiveTimeout
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits._

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor.BroadcastBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressProtocol
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool.AddOmmers
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AddUncheckedTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.RemoveTransactions
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps._

class BlockImporter(
    fetcher: ActorRef,
    consensus: ConsensusAdapter,
    blockchainReader: BlockchainReader,
    stateStorage: StateStorage,
    branchResolution: BranchResolution,
    syncConfig: SyncConfig,
    ommersPool: ActorRef,
    broadcaster: ActorRef,
    pendingTransactionsManager: ActorRef,
    supervisor: ActorRef,
    configBuilder: BlockchainConfigBuilder
) extends Actor
    with ActorLogging {

  import BlockImporter._
  import configBuilder._

  implicit val runtime: IORuntime = IORuntime.global

  context.setReceiveTimeout(syncConfig.syncRetryInterval)

  override def receive: Receive = idle

  override def postRestart(reason: Throwable): Unit = {
    super.postRestart(reason)
    start()
  }

  private def idle: Receive = { case Start =>
    start()
  }

  private def running(state: ImporterState): Receive = {
    case ReceiveTimeout => self ! PickBlocks

    case BlockFetcher.PickedBlocks(blocks) =>
      SignedTransaction.retrieveSendersInBackGround(blocks.toList.map(_.body))
      importBlocks(blocks, DefaultBlockImport)(state)

    case MinedBlock(block) if !state.importing =>
      importBlock(
        block,
        new MinedBlockImportMessages(block),
        MinedBlockImport,
        informFetcherOnFail = false,
        internally = true
      )(state)

    // We don't want to lose a checkpoint
    case nc @ NewCheckpoint(_) if state.importing =>
      implicit val ec = context.dispatcher
      context.system.scheduler.scheduleOnce(1.second, self, nc)

    case NewCheckpoint(block) if !state.importing =>
      importBlock(
        block,
        new CheckpointBlockImportMessages(block),
        CheckpointBlockImport,
        informFetcherOnFail = false,
        internally = true
      )(state)

    case ImportNewBlock(block, peerId) if !state.importing =>
      importBlock(
        block,
        new NewBlockImportMessages(block, peerId),
        NewBlockImport,
        informFetcherOnFail = true,
        internally = false
      )(state)

    case ImportDone(newBehavior, importType) =>
      val newState = state.notImportingBlocks().branchResolved()
      val behavior: Behavior = getBehavior(newBehavior, importType)
      if (newBehavior == Running) {
        self ! PickBlocks
      }
      context.become(behavior(newState))

    case PickBlocks if !state.importing => pickBlocks(state)

    // Late-arriving state node from a previous resolvingMissingNode phase.
    // ReceiveTimeout may have moved us back to running before the fetch completed.
    // Save the node so the next import attempt finds it in storage.
    case BlockFetcher.FetchedStateNode(nodeData) if nodeData.values.nonEmpty =>
      val node = nodeData.values.head
      val hash = kec256(node)
      log.info("Saving late-arriving fetched state node {}", ByteStringUtils.hash2string(hash))
      stateStorage.saveNode(hash, node.toArray, blockchainReader.getBestBlockNumber())
  }

  private def resolvingMissingNode(blocksToRetry: NonEmptyList[Block], blockImportType: BlockImportType)(
      state: ImporterState
  ): Receive = {
    case BlockFetcher.FetchedStateNode(nodeData) =>
      val node = nodeData.values.head
      val hash = kec256(node)
      log.info("Received missing state node {}, saving and retrying block {}", ByteStringUtils.hash2string(hash), blocksToRetry.head.number)
      stateStorage.saveNode(hash, node.toArray, blocksToRetry.head.number)
      importBlocks(blocksToRetry, blockImportType)(state)

    case ReceiveTimeout =>
      log.warning("Timed out waiting for missing state node for block {}, retrying import", blocksToRetry.head.number)
      // Retry the same blocks directly — don't PickBlocks, which would fetch from wherever the
      // fetcher is now (potentially far beyond the pivot). After SNAP sync, only the pivot header
      // has a number→hash mapping, so branch resolution would fail for any other starting point.
      importBlocks(blocksToRetry, blockImportType)(state)
  }

  private def resolvingBranch(from: BigInt)(state: ImporterState): Receive =
    running(state.resolvingBranch(from))

  /** Walk the local trie from stateRoot to find the HP-encoded path to a missing node.
    * Returns the pathset suitable for SNAP GetTrieNodes: single-element for account trie nodes,
    * two-element [accountHash, storagePath] for storage trie nodes.
    * Limited to MaxTrieVisits node reads to avoid multi-minute DFS on large tries.
    */
  private val MaxTrieVisits = 50000

  /** Walk the specific path through the account trie for the given account hash.
    * Instead of DFS over millions of nodes, this follows the exact key path — O(depth) = ~12 hops.
    * Returns the HP-encoded SNAP pathset for the missing node.
    */
  private def walkAccountPath(stateRoot: ByteString, accountHash: ByteString, targetHash: ByteString): Option[Seq[ByteString]] =
    try {
      val mptStorage = stateStorage.getReadOnlyStorage
      if (mptStorage == null) return None
      val keyNibbles = HexPrefix.bytesToNibbles(accountHash.toArray)
      walkPath(mptStorage, stateRoot, keyNibbles, 0, targetHash)
    } catch {
      case _: Exception => None
    }

  /** Walk the trie following keyNibbles, checking each HashNode for the target hash. */
  private def walkPath(
      storage: com.chipprbots.ethereum.db.storage.MptStorage,
      nodeHash: ByteString,
      keyNibbles: Array[Byte],
      offset: Int,
      targetHash: ByteString
  ): Option[Seq[ByteString]] = {
    if (nodeHash == targetHash) {
      // This hash reference IS the missing node — return path up to current offset
      val path = keyNibbles.take(offset)
      val compactPath = ByteString(HexPrefix.encode(path, isLeaf = false))
      return Some(Seq(compactPath))
    }
    try {
      val node = storage.get(nodeHash.toArray)
      walkNode(storage, node, keyNibbles, offset, targetHash)
    } catch {
      case e: MissingNodeException if e.hash == targetHash =>
        val path = keyNibbles.take(offset)
        val compactPath = ByteString(HexPrefix.encode(path, isLeaf = false))
        Some(Seq(compactPath))
      case _: Exception => None
    }
  }

  private def walkNode(
      storage: com.chipprbots.ethereum.db.storage.MptStorage,
      node: MptNode,
      keyNibbles: Array[Byte],
      offset: Int,
      targetHash: ByteString
  ): Option[Seq[ByteString]] =
    node match {
      case ext: ExtensionNode =>
        val sharedKey = ext.sharedKey.toArray
        val remaining = keyNibbles.drop(offset)
        if (remaining.length >= sharedKey.length && remaining.take(sharedKey.length).sameElements(sharedKey)) {
          walkNodeChild(storage, ext.next, keyNibbles, offset + sharedKey.length, targetHash)
        } else None

      case branch: BranchNode =>
        if (offset < keyNibbles.length) {
          val childIdx = keyNibbles(offset)
          val child = branch.children(childIdx)
          walkNodeChild(storage, child, keyNibbles, offset + 1, targetHash)
        } else None

      case _: LeafNode => None // Reached the leaf without finding the missing node
      case NullNode     => None
      case hash: HashNode =>
        walkPath(storage, ByteString(hash.hash), keyNibbles, offset, targetHash)
    }

  private def walkNodeChild(
      storage: com.chipprbots.ethereum.db.storage.MptStorage,
      child: MptNode,
      keyNibbles: Array[Byte],
      offset: Int,
      targetHash: ByteString
  ): Option[Seq[ByteString]] =
    child match {
      case hash: HashNode =>
        walkPath(storage, ByteString(hash.hash), keyNibbles, offset, targetHash)
      case node => walkNode(storage, node, keyNibbles, offset, targetHash)
    }

  private def findPathForMissingNode(stateRoot: ByteString, targetHash: ByteString): Option[Seq[ByteString]] =
    try {
      val mptStorage = stateStorage.getReadOnlyStorage
      if (mptStorage == null) return None
      val visits = new java.util.concurrent.atomic.AtomicInteger(0)
      try {
        val rootNode = mptStorage.get(stateRoot.toArray)
        findInAccountTrie(rootNode, mptStorage, Array.empty[Byte], targetHash, visits)
      } catch {
        case e: MissingNodeException if ByteString(e.hash) == targetHash =>
          // The root itself is the missing node — return empty path
          val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
          Some(Seq(compactPath))
        case _: Exception => None
      }
    } catch {
      case _: Exception => None
    }

  private def findInAccountTrie(
      node: MptNode,
      storage: com.chipprbots.ethereum.db.storage.MptStorage,
      currentNibblePath: Array[Byte],
      targetHash: ByteString,
      visits: java.util.concurrent.atomic.AtomicInteger
  ): Option[Seq[ByteString]] = {
    if (visits.incrementAndGet() > MaxTrieVisits) return None
    node match {
      case ext: ExtensionNode =>
        val newPath = currentNibblePath ++ ext.sharedKey.toArray
        findInAccountTrie(ext.next, storage, newPath, targetHash, visits)

      case branch: BranchNode =>
        var result: Option[Seq[ByteString]] = None
        var i = 0
        while (i < 16 && result.isEmpty) {
          val child = branch.children(i)
          if (!child.isNull) {
            val newPath = currentNibblePath :+ i.toByte
            result = findInAccountTrie(child, storage, newPath, targetHash, visits)
          }
          i += 1
        }
        result

      case hash: HashNode =>
        val nodeHash = ByteString(hash.hash)
        if (nodeHash == targetHash) {
          // Found the missing node — return its path as HP-encoded compact path
          val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
          Some(Seq(compactPath))
        } else {
          try {
            val resolvedNode = storage.get(hash.hash)
            findInAccountTrie(resolvedNode, storage, currentNibblePath, targetHash, visits)
          } catch {
            case _: MissingNodeException => None // Different missing node, skip
          }
        }

      case leaf: LeafNode =>
        // Check storage tries for this account
        try {
          import com.chipprbots.ethereum.domain.Account.accountSerializer
          val account = accountSerializer.fromBytes(leaf.value.toArray)
          if (account.storageRoot != com.chipprbots.ethereum.domain.Account.EmptyStorageRootHash) {
            val leafKeyNibbles = leaf.key.toArray
            val fullNibblePath = currentNibblePath ++ leafKeyNibbles
            val accountHashBytes = HexPrefix.nibblesToBytes(fullNibblePath)
            try {
              val storageRoot = storage.get(account.storageRoot.toArray)
              findInStorageTrie(storageRoot, storage, Array.empty[Byte], ByteString(accountHashBytes), targetHash, visits)
            } catch {
              case e: MissingNodeException if ByteString(e.hash) == targetHash =>
                val compactPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                Some(Seq(ByteString(accountHashBytes), compactPath))
              case _: Exception => None
            }
          } else None
        } catch {
          case _: Exception => None
        }

      case NullNode => None
    }
  }

  private def findInStorageTrie(
      node: MptNode,
      storage: com.chipprbots.ethereum.db.storage.MptStorage,
      currentNibblePath: Array[Byte],
      accountHash: ByteString,
      targetHash: ByteString,
      visits: java.util.concurrent.atomic.AtomicInteger
  ): Option[Seq[ByteString]] = {
    if (visits.incrementAndGet() > MaxTrieVisits) return None
    node match {
      case ext: ExtensionNode =>
        val newPath = currentNibblePath ++ ext.sharedKey.toArray
        findInStorageTrie(ext.next, storage, newPath, accountHash, targetHash, visits)

      case branch: BranchNode =>
        var result: Option[Seq[ByteString]] = None
        var i = 0
        while (i < 16 && result.isEmpty) {
          val child = branch.children(i)
          if (!child.isNull) {
            val newPath = currentNibblePath :+ i.toByte
            result = findInStorageTrie(child, storage, newPath, accountHash, targetHash, visits)
          }
          i += 1
        }
        result

      case hash: HashNode =>
        val nodeHash = ByteString(hash.hash)
        if (nodeHash == targetHash) {
          val compactPath = ByteString(HexPrefix.encode(currentNibblePath, isLeaf = false))
          Some(Seq(accountHash, compactPath))
        } else {
          try {
            val resolvedNode = storage.get(hash.hash)
            findInStorageTrie(resolvedNode, storage, currentNibblePath, accountHash, targetHash, visits)
          } catch {
            case _: MissingNodeException => None
          }
        }

      case _: LeafNode => None
      case NullNode     => None
    }
  }

  private def start(): Unit = {
    log.debug("Starting Regular Sync, current best block is {}", bestKnownBlockNumber)
    fetcher ! BlockFetcher.Start(self, bestKnownBlockNumber)
    supervisor ! ProgressProtocol.StartingFrom(bestKnownBlockNumber)
    context.become(running(ImporterState.initial))
  }

  private def pickBlocks(state: ImporterState): Unit = {
    val msg = state.resolvingBranchFrom.fold[BlockFetcher.FetchCommand](
      BlockFetcher.PickBlocks(syncConfig.blocksBatchSize, self)
    )(from => BlockFetcher.StrictPickBlocks(from, bestKnownBlockNumber, self))

    fetcher ! msg
  }

  private def importBlocks(blocks: NonEmptyList[Block], blockImportType: BlockImportType): ImportFn = importWith(
    IO
      .pure {
        log.debug(
          "Attempting to import blocks starting from {} and ending with {}",
          blocks.head.number,
          blocks.last.number
        )
        resolveBranch(blocks)
      }
      .flatMap {
        case Right(blocksToImport) => handleBlocksImport(blocksToImport)
        case Left(resolvingFrom)   => IO.pure(ResolvingBranch(resolvingFrom))
      },
    blockImportType
  )

  private def handleBlocksImport(blocks: List[Block]): IO[NewBehavior] =
    tryImportBlocks(blocks)
      .map { value =>
        val (importedBlocks, errorOpt) = value
        importedBlocks.size match {
          case 0 => log.debug("Imported no blocks")
          case 1 => log.debug("Imported block {}", importedBlocks.head.number)
          case _ => log.debug("Imported blocks {} - {}", importedBlocks.head.number, importedBlocks.last.number)
        }

        errorOpt match {
          case None => Running
          case Some(err) =>
            log.error("Block import error {}", err)
            val notImportedBlocks = blocks.drop(importedBlocks.size)

            err match {
              case e: MissingAccountNodeException =>
                // Account trie node missing — walk the specific account path to find the node (O(12) hops)
                val failedBlock = notImportedBlocks.head
                val parentStateRoot = try {
                  Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten.map(_.stateRoot)
                } catch { case _: Exception => None }
                val accountHash = kec256(e.accountAddress)
                val pathset = parentStateRoot.flatMap { root =>
                  walkAccountPath(root, accountHash, e.hash)
                }
                log.info(
                  "Missing account trie node {} for account {} during import of block {}, pathFound={}",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  pathset.isDefined
                )
                fetcher ! BlockFetcher.FetchStateNode(e.hash, self, parentStateRoot, pathset)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case e: MissingStorageNodeException =>
                // Storage trie node missing — we know the account address, construct SNAP pathset directly
                val failedBlock = notImportedBlocks.head
                val parentStateRoot = try {
                  Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten.map(_.stateRoot)
                } catch { case _: Exception => None }
                val accountHash = kec256(e.accountAddress)
                val emptyPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                val pathset = Some(Seq(accountHash, emptyPath))
                log.info(
                  "Missing storage node {} for account {} during import of block {}, stateRoot={}",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  parentStateRoot.map(ByteStringUtils.hash2string)
                )
                fetcher ! BlockFetcher.FetchStateNode(e.hash, self, parentStateRoot, pathset)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case e: MissingNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot = try {
                  Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten.map(_.stateRoot)
                } catch { case _: Exception => None }
                val pathset = parentStateRoot.flatMap { root =>
                  findPathForMissingNode(root, e.hash)
                }
                log.info(
                  "Missing state node {} during import of block {}, stateRoot={}, pathFound={}",
                  ByteStringUtils.hash2string(e.hash),
                  failedBlock.number,
                  parentStateRoot.map(ByteStringUtils.hash2string),
                  pathset.isDefined
                )
                fetcher ! BlockFetcher.FetchStateNode(e.hash, self, parentStateRoot, pathset)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case _ =>
                val invalidBlockNr = notImportedBlocks.head.number
                fetcher ! BlockFetcher.InvalidateBlocksFrom(invalidBlockNr, err.toString)
                Running
            }
        }
      }

  private def tryImportBlocks(
      blocks: List[Block],
      importedBlocks: List[Block] = Nil
  ): IO[(List[Block], Option[Any])] =
    if (blocks.isEmpty) {
      importedBlocks.headOption.foreach(block =>
        supervisor ! ProgressProtocol.ImportedBlock(block.number, internally = false)
      )
      IO.pure((importedBlocks, None))
    } else {
      val restOfBlocks = blocks.tail
      consensus
        .evaluateBranchBlock(blocks.head)
        .flatMap {
          case BlockImportedToTop(_) =>
            tryImportBlocks(restOfBlocks, blocks.head :: importedBlocks)

          case ChainReorganised(_, newBranch, _) =>
            tryImportBlocks(restOfBlocks, newBranch.reverse ::: importedBlocks)

          case DuplicateBlock | BlockEnqueued =>
            tryImportBlocks(restOfBlocks, importedBlocks)

          case BlockImportFailedDueToMissingNode(missingNodeException) if syncConfig.redownloadMissingStateNodes =>
            IO.pure((importedBlocks, Some(missingNodeException)))

          case BlockImportFailedDueToMissingNode(missingNodeException) =>
            IO.raiseError(missingNodeException)

          case err @ (UnknownParent | BlockImportFailed(_)) =>
            log.error(
              "Block {} import failed, with hash {} and parent hash {}",
              blocks.head.number,
              blocks.head.header.hashAsHexString,
              ByteStringUtils.hash2string(blocks.head.header.parentHash)
            )
            IO.pure((importedBlocks, Some(err)))
        }
    }

  private def importBlock(
      block: Block,
      importMessages: ImportMessages,
      blockImportType: BlockImportType,
      informFetcherOnFail: Boolean,
      internally: Boolean
  ): ImportFn = {
    def doLog(entry: ImportMessages.LogEntry): Unit = log.log(entry._1, entry._2)
    importWith(
      IO(doLog(importMessages.preImport()))
        .flatMap(_ => consensus.evaluateBranchBlock(block))
        .tap((importMessages.messageForImportResult _).andThen(doLog))
        .tap {
          case BlockImportedToTop(importedBlocksData) =>
            val (blocks, weights) = importedBlocksData.map(data => (data.block, data.weight)).unzip
            broadcastBlocks(blocks, weights)
            updateTxPool(importedBlocksData.map(_.block), Seq.empty)
            supervisor ! ProgressProtocol.ImportedBlock(block.number, internally)
          case ChainReorganised(oldBranch, newBranch, weights) =>
            updateTxPool(newBranch, oldBranch)
            broadcastBlocks(newBranch, weights)
            newBranch.lastOption.foreach(block => supervisor ! ProgressProtocol.ImportedBlock(block.number, internally))
          case BlockImportFailedDueToMissingNode(missingNodeException) if syncConfig.redownloadMissingStateNodes =>
            // state node re-download will be handled when downloading headers
            doLog(importMessages.missingStateNode(missingNodeException))
          case BlockImportFailedDueToMissingNode(missingNodeException) =>
            IO.raiseError(missingNodeException)
          case BlockImportFailed(error) if informFetcherOnFail =>
            fetcher ! BlockFetcher.BlockImportFailed(block.number, BlacklistReason.BlockImportError(error))
          case BlockEnqueued | DuplicateBlock | UnknownParent | BlockImportFailed(_) => ()
        }
        .map(_ => Running),
      blockImportType
    )
  }

  private def broadcastBlocks(blocks: List[Block], weights: List[ChainWeight]): Unit = {
    val newBlocks = (blocks, weights).mapN(BlockToBroadcast.apply)
    broadcaster ! BroadcastBlocks(newBlocks)
  }

  private def updateTxPool(blocksAdded: Seq[Block], blocksRemoved: Seq[Block]): Unit = {
    blocksRemoved.foreach(block => pendingTransactionsManager ! AddUncheckedTransactions(block.body.transactionList))
    blocksAdded.foreach(block => pendingTransactionsManager ! RemoveTransactions(block.body.transactionList))
  }

  private def importWith(importTask: IO[NewBehavior], blockImportType: BlockImportType)(
      state: ImporterState
  ): Unit = {
    context.become(running(state.importingBlocks()))

    importTask
      .map(self ! ImportDone(_, blockImportType))
      .handleError { ex =>
        log.error(ex, "Block import failed unexpectedly: {}", ex.getMessage)
        self ! ImportDone(Running, blockImportType)
      }
      .timed
      .map { case (timeTaken, _) => blockImportType.recordMetric(timeTaken.toNanos) }
      .unsafeRunAndForget()
  }

  // Either block from which we try resolve branch or list of blocks to be imported
  private def resolveBranch(blocks: NonEmptyList[Block]): Either[BigInt, List[Block]] =
    branchResolution.resolveBranch(blocks.map(_.header)) match {
      case NewBetterBranch(oldBranch) =>
        val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
        pendingTransactionsManager ! PendingTransactionsManager.AddUncheckedTransactions(transactionsToAdd)
        // Add first block from branch as an ommer
        oldBranch.headOption.map(_.header).foreach(ommersPool ! AddOmmers(_))
        Right(blocks.toList)
      case NoChainSwitch =>
        // Add first block from branch as an ommer
        ommersPool ! AddOmmers(blocks.head.header)
        Right(Nil)
      case UnknownBranch =>
        val currentBlock = blocks.head.number.min(bestKnownBlockNumber)
        val goingBackTo = (currentBlock - syncConfig.branchResolutionRequestSize).max(0)
        val msg = s"Unknown branch, going back to block nr $goingBackTo in order to resolve branches"
        log.warning(msg)
        fetcher ! BlockFetcher.InvalidateBlocksFrom(goingBackTo, msg, shouldBlacklist = false)
        Left(goingBackTo)
      case InvalidBranch =>
        val goingBackTo = blocks.head.number
        val msg = s"Invalid branch, going back to $goingBackTo"
        log.warning(msg)
        fetcher ! BlockFetcher.InvalidateBlocksFrom(goingBackTo, msg)
        Right(Nil)
    }

  private def bestKnownBlockNumber: BigInt = blockchainReader.getBestBlockNumber()

  private def getBehavior(newBehavior: NewBehavior, blockImportType: BlockImportType): Behavior = newBehavior match {
    case Running =>
      context.setReceiveTimeout(syncConfig.syncRetryInterval)
      running
    case ResolvingMissingNode(blocksToRetry) =>
      // Give ample time for the SNAP GetTrieNodes fetch to complete
      context.setReceiveTimeout(30.seconds)
      resolvingMissingNode(blocksToRetry, blockImportType)
    case ResolvingBranch(from) =>
      context.setReceiveTimeout(syncConfig.syncRetryInterval)
      resolvingBranch(from)
  }
}

object BlockImporter {
  // scalastyle:off parameter.number
  def props(
      fetcher: ActorRef,
      consensus: ConsensusAdapter,
      blockchainReader: BlockchainReader,
      stateStorage: StateStorage,
      branchResolution: BranchResolution,
      syncConfig: SyncConfig,
      ommersPool: ActorRef,
      broadcaster: ActorRef,
      pendingTransactionsManager: ActorRef,
      supervisor: ActorRef,
      configBuilder: BlockchainConfigBuilder
  ): Props =
    Props(
      new BlockImporter(
        fetcher,
        consensus,
        blockchainReader,
        stateStorage,
        branchResolution,
        syncConfig,
        ommersPool,
        broadcaster,
        pendingTransactionsManager,
        supervisor,
        configBuilder
      )
    )

  type Behavior = ImporterState => Receive
  type ImportFn = ImporterState => Unit

  sealed trait ImporterMsg
  case object Start extends ImporterMsg
  case class MinedBlock(block: Block) extends ImporterMsg
  case class NewCheckpoint(block: Block) extends ImporterMsg
  case class ImportNewBlock(block: Block, peerId: PeerId) extends ImporterMsg
  case class ImportDone(newBehavior: NewBehavior, blockImportType: BlockImportType) extends ImporterMsg
  case object PickBlocks extends ImporterMsg
  case object PrintStatus extends ImporterMsg with NotInfluenceReceiveTimeout

  sealed trait NewBehavior
  case object Running extends NewBehavior
  case class ResolvingMissingNode(blocksToRetry: NonEmptyList[Block]) extends NewBehavior
  case class ResolvingBranch(from: BigInt) extends NewBehavior

  sealed trait BlockImportType {
    def recordMetric(nanos: Long): Unit
  }

  case object MinedBlockImport extends BlockImportType {
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordMinedBlockPropagationTimer(nanos)
  }

  case object CheckpointBlockImport extends BlockImportType {
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordImportCheckpointPropagationTimer(nanos)
  }

  case object NewBlockImport extends BlockImportType {
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordImportNewBlockPropagationTimer(nanos)
  }

  case object DefaultBlockImport extends BlockImportType {
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordDefaultBlockPropagationTimer(nanos)
  }

  case class ImporterState(
      importing: Boolean,
      resolvingBranchFrom: Option[BigInt]
  ) {
    def importingBlocks(): ImporterState = copy(importing = true)

    def notImportingBlocks(): ImporterState = copy(importing = false)

    def resolvingBranch(from: BigInt): ImporterState = copy(resolvingBranchFrom = Some(from))

    def branchResolved(): ImporterState = copy(resolvingBranchFrom = None)

    def isResolvingBranch: Boolean = resolvingBranchFrom.isDefined
  }

  object ImporterState {
    def initial: ImporterState = ImporterState(
      importing = false,
      resolvingBranchFrom = None
    )
  }
}
