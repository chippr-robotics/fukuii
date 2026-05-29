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
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor.BroadcastBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressProtocol
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.{EvmCodeStorage, StateStorage}
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
import com.chipprbots.ethereum.jsonrpc.NewBlockImported
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.RemoveTransactions
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps._

class BlockImporter(
    fetcher: ActorRef,
    consensus: ConsensusAdapter,
    blockchainReader: BlockchainReader,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
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

  private var pendingStateNodeHash: Option[ByteString] = None
  // Tracks account address + parent state root when the stuck node is a storage trie node.
  // Used to escalate to GetStorageRanges (Tier 2) before falling through to RegularSyncStuck.
  private var pendingStuckStorageAccount: Option[ByteString] = None
  private var pendingStuckStorageStateRoot: Option[ByteString] = None

  // Reset the companion-object exhaust counter on fresh actor creation.
  // On Pekko Restart, only postRestart() fires — preStart() is skipped — so
  // survivedExhausts is preserved across restarts and only zeroed for a new
  // BlockImporter actor (new regular-sync session).
  override def preStart(): Unit = {
    super.preStart()
    BlockImporter.survivedExhausts = 0
    BlockImporter.stuckSinceMs = 0L
  }

  override def receive: Receive = idle

  override def postRestart(reason: Throwable): Unit =
    // Intentionally skip super.postRestart() — that would call preStart() and
    // reset survivedExhausts, defeating its purpose of surviving Pekko Restarts.
    start()

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
      // Also save as contract code in case this was a bytecode fetch
      try evmCodeStorage.put(hash, node).commit()
      catch { case _: Exception => () }
  }

  private def resolvingMissingNode(blocksToRetry: NonEmptyList[Block], blockImportType: BlockImportType)(
      state: ImporterState
  ): Receive = {
    case BlockFetcher.FetchedStateNode(nodeData) if nodeData.values.isEmpty =>
      // StateNodeFetcher exhausted MaxStateNodeFetchRetries on this missing node — no current peer
      // can serve it via SNAP GetTrieNodes (typical: pivot fell out of the 128-block serve window
      // on every connected peer).
      val blockNum = blocksToRetry.head.number
      BlockImporter.survivedExhausts += 1
      if (BlockImporter.stuckSinceMs == 0L) BlockImporter.stuckSinceMs = System.currentTimeMillis()
      val missingHashStr = pendingStateNodeHash.map(ByteStringUtils.hash2string).getOrElse("<unknown>")
      val stuckTooLong = (System.currentTimeMillis() - BlockImporter.stuckSinceMs) >= BlockImporter.MaxStuckDurationMs

      // Storage path-mismatch: escalate to Tier 2 on the FIRST exhaust — no point waiting
      // 3 rounds when peers have already proven they can't serve this node structure.
      // Account/other nodes still need the full 3-exhaust threshold before giving up.
      val storagePathMismatch = pendingStuckStorageAccount.isDefined && BlockImporter.survivedExhausts >= 1
      if (storagePathMismatch || BlockImporter.survivedExhausts >= BlockImporter.StuckEscapeThreshold || stuckTooLong) {
        // Multiple consecutive exhausts mean peers genuinely don't have our parent state and
        // never will (we're far behind their snap-serve window).
        //
        // Tier 2: if this is a storage node that exhausted due to path mismatch (all canonical
        // state roots returned 0), escalate to GetStorageRanges for the stuck account before
        // giving up on RegularSync entirely.
        pendingStuckStorageAccount match {
          case Some(accountAddr) =>
            log.warning(
              "[STORAGE-HEAL] GetTrieNodes exhausted for storage node {} account {} block {} — all roots returned 0 (path mismatch). Escalating to GetStorageRanges.",
              missingHashStr,
              ByteStringUtils.hash2string(accountAddr),
              blockNum
            )
            // Keep survivedExhausts and stuckSinceMs — if storage range also fails we need them
            pendingStateNodeHash = None
            pendingStuckStorageAccount = None
            fetcher ! BlockFetcher.FetchAccountStorage(accountAddr, self, pendingStuckStorageStateRoot, stateStorage)
            pendingStuckStorageStateRoot = None
            context.setReceiveTimeout(5.minutes)
            context.become(resolvingStorageRange(blocksToRetry, blockImportType)(state))
          case None =>
            // Not a storage path-mismatch — fall through to Tier 3: RegularSyncStuck
            log.error(
              "Regular sync stuck on block {} after {} consecutive state-node exhausts (missing {}); requesting SNAP re-sync",
              blockNum,
              BlockImporter.survivedExhausts,
              missingHashStr
            )
            BlockImporter.survivedExhausts = 0
            BlockImporter.stuckSinceMs = 0L
            pendingStateNodeHash = None
            pendingStuckStorageStateRoot = None
            supervisor ! SyncProtocol.RegularSyncStuck(blockNum, missingHashStr)
          // Don't transition further — SyncController will PoisonPill regular sync.
        }
      } else {
        log.error(
          "State node recovery failed after max retries for block {} (consecutive exhausts: {}/{}) — backing off {}s before retry",
          blockNum,
          BlockImporter.survivedExhausts,
          BlockImporter.StuckEscapeThreshold,
          5.minutes.toSeconds
        )
        fetcher ! BlockFetcher.InvalidateBlocksFrom(
          blockNum,
          "state node unrecoverable after max retries",
          shouldBlacklist = false
        )
        // Don't self ! PickBlocks — that would immediately retry the same block.
        context.setReceiveTimeout(5.minutes)
        context.become(running(state))
      }

    case BlockFetcher.FetchedStateNode(nodeData) =>
      val node = nodeData.values.head
      val hash = kec256(node)
      log.info(
        "Received missing state node {}, saving and retrying block {}",
        ByteStringUtils.hash2string(hash),
        blocksToRetry.head.number
      )
      stateStorage.saveNode(hash, node.toArray, blocksToRetry.head.number)
      // Also save as contract code — if this was a code fetch, the hash is the codeHash
      // and the data is the bytecode. EvmCodeStorage is keyed by codeHash, same as the fetch.
      try evmCodeStorage.put(hash, node).commit()
      catch { case _: Exception => () }
      // Successful state-node delivery — reset stuck-counter and timer so a later transient
      // failure on a different block doesn't escalate to SNAP re-sync prematurely.
      BlockImporter.survivedExhausts = 0
      BlockImporter.stuckSinceMs = 0L
      pendingStateNodeHash = None
      pendingStuckStorageAccount = None
      pendingStuckStorageStateRoot = None
      importBlocks(blocksToRetry, blockImportType)(state)

    case ReceiveTimeout =>
      log.warning(
        "Timed out waiting for missing state node for block {} (consecutiveExhausts={}/{}), retrying",
        blocksToRetry.head.number,
        BlockImporter.survivedExhausts,
        BlockImporter.StuckEscapeThreshold
      )
      // Retry the same blocks directly — don't PickBlocks, which would fetch from wherever the
      // fetcher is now (potentially far beyond the pivot). After SNAP sync, only the pivot header
      // has a number→hash mapping, so branch resolution would fail for any other starting point.
      BlockImporter.survivedExhausts += 1
      importBlocks(blocksToRetry, blockImportType)(state)
  }

  private def resolvingStorageRange(blocksToRetry: NonEmptyList[Block], blockImportType: BlockImportType)(
      state: ImporterState
  ): Receive = {
    case BlockFetcher.FetchedAccountStorage(accountAddr, canonicalAccountOpt, success) =>
      val blockNum = blocksToRetry.head.number
      if (success) {
        canonicalAccountOpt.foreach { canonicalAccount =>
          updateMptAccountLeaf(accountAddr, canonicalAccount)
        }
        log.info(
          "[STORAGE-HEAL] Account {} storage re-downloaded — account leaf updated with canonical storageRoot, retrying block {}",
          ByteStringUtils.hash2string(accountAddr),
          blockNum
        )
        BlockImporter.survivedExhausts = 0
        BlockImporter.stuckSinceMs = 0L
        importBlocks(blocksToRetry, blockImportType)(state)
      } else {
        log.error(
          "[STORAGE-HEAL] Account {} storage range download failed for block {} — escalating to RegularSyncStuck",
          ByteStringUtils.hash2string(accountAddr),
          blockNum
        )
        BlockImporter.survivedExhausts = 0
        BlockImporter.stuckSinceMs = 0L
        supervisor ! SyncProtocol.RegularSyncStuck(
          blockNum,
          s"storage-range-unavailable:${ByteStringUtils.hash2string(accountAddr)}"
        )
      }

    case ReceiveTimeout =>
      val blockNum = blocksToRetry.head.number
      log.error(
        "[STORAGE-HEAL] Timeout waiting for account storage range download for block {} — escalating to RegularSyncStuck",
        blockNum
      )
      BlockImporter.survivedExhausts = 0
      BlockImporter.stuckSinceMs = 0L
      supervisor ! SyncProtocol.RegularSyncStuck(blockNum, "storage-range-timeout")
  }

  // Updates the MPT account leaf with the canonical account (which has the correct storageRoot).
  // Used after AccountStorageFetcher downloads canonical account + storage from peers.
  // After this call, block execution will traverse the canonical storage trie structure,
  // allowing GetTrieNodes to serve missing nodes (peers have the canonical structure).
  private def updateMptAccountLeaf(accountAddr: ByteString, canonicalAccount: Account): Unit =
    try {
      val bestBlockNum = blockchainReader.getBestBlockNumber()
      val mptStorage = stateStorage.getBackingStorage(bestBlockNum)
      val currentStateRoot = blockchainReader
        .getBestBlock()
        .flatMap(b => Option(b.header.stateRoot))
        .getOrElse(ByteString.empty)
      val getBlockHashByNumber: BigInt => Option[ByteString] = n =>
        blockchainReader.getBlockHeaderByNumber(n).map(_.hash)
      val worldState = InMemoryWorldStateProxy(
        evmCodeStorage,
        mptStorage,
        getBlockHashByNumber,
        UInt256.Zero,
        currentStateRoot,
        noEmptyAccounts = true,
        ethCompatibleStorage = true
      )
      val address = Address(accountAddr)
      val updated = worldState.saveAccount(address, canonicalAccount)
      InMemoryWorldStateProxy.persistState(updated)
      log.info(
        "[STORAGE-HEAL] MPT account leaf updated: account {} storageRoot → {}",
        ByteStringUtils.hash2string(accountAddr),
        ByteStringUtils.hash2string(canonicalAccount.storageRoot.take(4))
      )
    } catch {
      case ex: Exception =>
        log.warning(
          "[STORAGE-HEAL] Failed to update MPT account leaf for {} — {}: {}",
          ByteStringUtils.hash2string(accountAddr),
          ex.getClass.getSimpleName,
          ex.getMessage
        )
    }

  private def resolvingBranch(from: BigInt)(state: ImporterState): Receive =
    running(state.resolvingBranch(from))

  private def start(): Unit = {
    log.info("Starting Regular Sync, current best block is {}", bestKnownBlockNumber)
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
          case 1 =>
            val b = importedBlocks.head
            log.info(
              "Imported block {} ({}) txs={} gas={}",
              b.number,
              b.header.hashAsHexString.take(10),
              b.body.transactionList.size,
              b.header.gasUsed
            )
          case _ => log.info("Imported blocks {} - {}", importedBlocks.last.number, importedBlocks.head.number)
        }

        errorOpt match {
          case None => Running
          case Some(err) =>
            log.error("Block import error {}", err)
            val notImportedBlocks = blocks.drop(importedBlocks.size)

            err match {
              case e: MissingAccountNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot)
                  catch {
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during node recovery: {}", ex.getMessage); None
                  }
                val accountHash = kec256(e.accountAddress)
                val paths: Option[Seq[Seq[ByteString]]] = e.location
                  .map { loc =>
                    Seq(Seq(loc))
                  }
                  .orElse {
                    val nibbles = accountHash.toArray.flatMap(b => Array(((b >> 4) & 0xf).toByte, (b & 0xf).toByte))
                    Some((1 to 16).map { depth =>
                      Seq(ByteString(HexPrefix.encode(nibbles.take(depth), isLeaf = false)))
                    })
                  }
                log.info(
                  "Missing account trie node {} for account {} during import of block {}, locationKnown={}",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  e.location.isDefined
                )
                pendingStateNodeHash = Some(e.hash)
                fetcher ! BlockFetcher.FetchStateNode(e.hash, self, parentStateRoot, paths)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case e: MissingStorageNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot)
                  catch {
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during node recovery: {}", ex.getMessage); None
                  }
                val accountHash = kec256(e.accountAddress)
                val paths: Option[Seq[Seq[ByteString]]] = e.location
                  .map { loc =>
                    Seq(Seq(accountHash, loc))
                  }
                  .orElse {
                    val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                    Some(Seq(Seq(accountHash, emptyStoragePath)))
                  }
                log.info(
                  "Missing storage node {} for account {} during import of block {}, locationKnown={} — will escalate to GetStorageRanges if GetTrieNodes exhausts",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  e.location.isDefined
                )
                pendingStateNodeHash = Some(e.hash)
                // Save account address + parent state root so resolvingMissingNode can escalate to Tier 2 if needed
                pendingStuckStorageAccount = Some(e.accountAddress)
                pendingStuckStorageStateRoot = parentStateRoot
                fetcher ! BlockFetcher.FetchStateNode(e.hash, self, parentStateRoot, paths)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case e: MissingNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot)
                  catch {
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during node recovery: {}", ex.getMessage); None
                  }
                val paths: Option[Seq[Seq[ByteString]]] = e.location.map(loc => Seq(Seq(loc)))
                log.info(
                  "Missing state node {} during import of block {}, locationKnown={}",
                  ByteStringUtils.hash2string(e.hash),
                  failedBlock.number,
                  e.location.isDefined
                )
                pendingStateNodeHash = Some(e.hash)
                fetcher ! BlockFetcher.FetchStateNode(e.hash, self, parentStateRoot, paths)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case _ if err.toString.contains("Block has invalid gas used") =>
                // Gas mismatch after execution — likely missing contract code from
                // incomplete fast sync state. The EVM treated a contract as an EOA
                // because its code wasn't in EvmCodeStorage.
                val failedBlock = notImportedBlocks.head
                findMissingContractCode(failedBlock) match {
                  case Some(codeHash) =>
                    log.warning(
                      "Gas mismatch on block {} — missing contract code {}. Fetching via SNAP GetByteCodes.",
                      failedBlock.number,
                      ByteStringUtils.hash2string(codeHash)
                    )
                    val parentStateRoot =
                      try
                        Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                          .map(_.stateRoot)
                      catch { case _: Exception => None }
                    pendingStateNodeHash = Some(codeHash)
                    // isByteCode=true routes the fetch through SNAP GetByteCodes (works on ETH68+)
                    // instead of the legacy GetNodeData path (which has no peers on modern networks).
                    fetcher ! BlockFetcher.FetchStateNode(
                      codeHash,
                      self,
                      parentStateRoot,
                      paths = None,
                      isByteCode = true
                    )
                    ResolvingMissingNode(NonEmptyList(failedBlock, notImportedBlocks.tail))
                  case None =>
                    // No missing bytecode — the gas divergence likely comes from incomplete
                    // storage state (SLOAD returning 0 due to missing/wrong storage trie data).
                    // InvalidateBlocksFrom + Running would loop forever since the state doesn't
                    // change between retries. Escalate to SNAP re-sync so the MinPivotBlock
                    // guard heals the state at this pivot before retrying the block.
                    log.error(
                      "Gas mismatch on block {} — no missing code found, escalating to SNAP re-sync " +
                        "(likely incomplete storage state)",
                      failedBlock.number
                    )
                    BlockImporter.survivedExhausts = 0
                    BlockImporter.stuckSinceMs = 0L
                    pendingStateNodeHash = None
                    supervisor ! SyncProtocol.RegularSyncStuck(failedBlock.number, err.toString)
                    Running
                }
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
            blocks.foreach(b => context.system.eventStream.publish(NewBlockImported(b)))
            supervisor ! ProgressProtocol.ImportedBlock(block.number, internally)
          case ChainReorganised(oldBranch, newBranch, weights) =>
            updateTxPool(newBranch, oldBranch)
            broadcastBlocks(newBranch, weights)
            newBranch.foreach(b => context.system.eventStream.publish(NewBlockImported(b)))
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
        // Floor at best block number (SNAP pivot) — no headers exist below that after SNAP sync.
        val floor = blockchainReader.getBestBlockNumber()
        val goingBackTo = (currentBlock - syncConfig.branchResolutionRequestSize).max(floor)
        if (goingBackTo >= currentBlock) {
          // At the pivot floor after SNAP sync — skip branch resolution and import directly.
          // After SNAP sync only the pivot header exists, so branch resolution can never
          // find a known parent below the pivot. The blocks ARE valid (they continue from
          // the SNAP-validated pivot). Filter blocks to only those at or above the pivot.
          val validBlocks = blocks.filter(_.number > floor)
          if (validBlocks.nonEmpty) {
            log.info(s"Branch resolution at SNAP pivot floor ($floor), importing ${validBlocks.size} blocks directly")
            Right(validBlocks)
          } else {
            log.warning(s"Branch resolution hit floor at block $floor, no importable blocks in batch")
            fetcher ! BlockFetcher.InvalidateBlocksFrom(floor + 1, "branch resolution floor", shouldBlacklist = false)
            Left(floor + 1)
          }
        } else {
          val msg = s"Unknown branch, going back to block nr $goingBackTo in order to resolve branches"
          log.warning(msg)
          fetcher ! BlockFetcher.InvalidateBlocksFrom(goingBackTo, msg, shouldBlacklist = false)
          Left(goingBackTo)
        }
      case InvalidBranch =>
        val goingBackTo = blocks.head.number
        val msg = s"Invalid branch, going back to $goingBackTo"
        log.warning(msg)
        fetcher ! BlockFetcher.InvalidateBlocksFrom(goingBackTo, msg)
        Right(Nil)
    }

  /** Check if a block's transactions touch any contract whose code is missing from local storage. Returns the codeHash
    * of the first missing contract found.
    */
  private def findMissingContractCode(block: Block): Option[ByteString] = {
    // Use the parent block number — execution starts from the parent's state root.
    // The failing block hasn't been imported yet, so its state root doesn't exist locally.
    val parentBlockNumber = block.header.number - 1
    block.body.transactionList.iterator
      .flatMap { stx =>
        stx.tx.receivingAddress.flatMap { address =>
          try
            // Look up the account directly via blockchainReader
            blockchainReader
              .getAccount(blockchainReader.getBestBranch(), address, parentBlockNumber)
              .flatMap { account =>
                if (account.codeHash != Account.EmptyCodeHash) {
                  evmCodeStorage.get(account.codeHash) match {
                    case None =>
                      log.info(
                        "Found missing code for contract {} (codeHash={})",
                        address,
                        ByteStringUtils.hash2string(account.codeHash)
                      )
                      Some(account.codeHash)
                    case Some(_) => None
                  }
                } else None
              }
          catch {
            case ex: Exception =>
              log.warning(
                "Failed to check contract code for {} at block {}: {}",
                address,
                parentBlockNumber,
                ex.getMessage
              )
              None
          }
        }
      }
      .nextOption()
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
  // After this many consecutive state-node-fetch exhausts on the same block, regular sync
  // is deemed terminally stuck and we escalate to SNAP re-sync via SyncProtocol.RegularSyncStuck.
  // 3 × 5-min backoff = ~15 minutes of bounded retry before invoking the escape valve.
  val StuckEscapeThreshold: Int = 3

  // Time-based escape: if RegularSync has been blocked on any state-node fetch for longer than
  // this window, escalate regardless of survivedExhausts. Guards against the counter being reset
  // by transient successful imports of other blocks between retries of the stuck block.
  val MaxStuckDurationMs: Long = 20L * 60L * 1000L // 20 minutes

  // Exhaust counter that outlives individual actor instances so Pekko Restarts don't reset
  // the progress toward StuckEscapeThreshold. Zeroed by preStart() (fresh regular-sync session)
  // but NOT by postRestart() (same logical actor restarted after a crash).
  private[regular] var survivedExhausts: Int = 0

  // Timestamp of when this RegularSync session first entered resolvingMissingNode.
  // Zeroed on successful import or on fresh actor creation.
  private[regular] var stuckSinceMs: Long = 0L

  // scalastyle:off parameter.number
  def props(
      fetcher: ActorRef,
      consensus: ConsensusAdapter,
      blockchainReader: BlockchainReader,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
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
        evmCodeStorage,
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
