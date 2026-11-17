package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.util.control.NonFatal

import com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedAt
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedDueToMissingNode
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.DuplicateBlock
import com.chipprbots.ethereum.blockchain.sync.regular.UnknownParent
import com.chipprbots.ethereum.consensus.Consensus.BranchExecutionFailure
import com.chipprbots.ethereum.consensus.Consensus.ConsensusError
import com.chipprbots.ethereum.consensus.Consensus.ConsensusErrorDueToMissingNode
import com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranchPartially
import com.chipprbots.ethereum.consensus.Consensus.KeptCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranchPartially
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.db.storage.pathstate.HeldStateIncomplete
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

/** This is a temporary class to isolate the real Consensus and extract responsibilities which should not be part of the
  * consensus in the final design, but are currently needed.
  */
class ConsensusAdapter(
    consensus: Consensus,
    blockchainReader: BlockchainReader,
    blockQueue: BlockQueue,
    blockValidation: BlockValidation,
    validationScheduler: IORuntime,
    // Same channel `ConsensusImpl` holds. Present here only so `BlockImporter` — which already depends on this
    // class and on nothing else in the consensus package — can report the ONE case `ConsensusImpl` deliberately
    // refuses to classify: the gas-used mismatch, which needs `findMissingContractCode` to disambiguate and so can
    // only be decided in `BlockImporter`. `None` on ETC/Mordor/Gorgoroth.
    invalidChainReporter: Option[InvalidChainReporter] = None,
    // What tells the escalation watch of proof-of-work branches this node's state could not take, and of heads that
    // moved (spec 018 C5a part 6b). Here because every result of an import passes here, typed, with the branch it is
    // of. `None` on the hash layout.
    escalationFeed: Option[PowEscalationFeed] = None
) extends Logger:

  /** Report a block the import path has PROVEN consensus-invalid, so `engine_newPayload` / `engine_forkchoiceUpdated`
    * can answer INVALID for it and its descendants.
    *
    * Deliberately not a general hook. The only caller is `BlockImporter`'s gas-used branch, on the arm where
    * `findMissingContractCode` returned `None` — i.e. after the recoverable "we are missing bytecode, fetch it over
    * SNAP and retry" reading has been positively excluded. Every other error type is classified upstream by
    * `ConsensusImpl.reportIfProvenInvalid`, which still has the typed `BlockExecutionError`; by the time a failure
    * reaches `BlockImporter` it is a `String` and cannot be classified safely.
    *
    * @param latestValidHash
    *   must be the last VALIDATED ancestor, which on the import path is the failing block's parent.
    */
  def reportInvalidChain(blockHash: ByteString, latestValidHash: ByteString): Unit =
    invalidChainReporter.foreach(_.reportInvalid(blockHash, latestValidHash))

  /** Would [[reportInvalidChain]] reach anyone? False on ETC/Mordor/Gorgoroth: there the reporter is either absent or a
    * `LateBound` nothing ever binds. `BlockImporter` uses this to skip work whose ONLY purpose is a report, so that
    * work cannot run — and cannot change anything, not even a log line — on a chain with no consensus layer.
    */
  def reportsInvalidChains: Boolean =
    invalidChainReporter.exists {
      case lateBound: InvalidChainReporter.LateBound => lateBound.isBound
      case _                                         => true
    }

  def evaluateBranchBlock(
      block: Block
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[BlockImportResult] =
    // Resolve the best block's header without requiring its body. Prefer the
    // full-block lookup (so the existing mock-based tests keep working) and fall
    // back to header-only when the body isn't persisted — exactly the state right
    // after PivotHeaderBootstrap completes. Without this fallback, post-bootstrap
    // imports dead-end on `getBestBlock() == None` and the consumer retries
    // forever with `BlockImportFailed("Couldn't find the current best block")`.
    val bestHeaderOpt =
      blockchainReader.getBestBlock.map(_.header).orElse(blockchainReader.getBestBlockHeader)
    bestHeaderOpt match
      case Some(bestHeader) =>
        if isBlockADuplicate(block.header, bestHeader.number.value) then
          log.debug("Ignoring duplicated block: {}", block.idTag)
          IO.pure(DuplicateBlock)
        else
          // If chain weight lookup fails, treat it as recoverable: log and continue.
          if blockchainReader.getChainWeightByHash(bestHeader.hash).isEmpty then
            log.warn(
              "Total chain weight for current best block {} is missing — continuing import (test harness may not provide chain weight)",
              bestHeader.hashAsHexString
            )

          // Skip pre-validation when the block directly extends the current best block.
          // During sequential sync, each block's parent was just saved by the previous iteration.
          // doBlockPreValidation runs on a different thread pool (validationScheduler) which can
          // race with the storage write, causing intermittent HeaderParentNotFoundError.
          //
          // Skipping here is safe ONLY because BlockExecution.executeAndValidateBlocks now passes
          // alreadyValidated = false, so every block is validated on the single-threaded @tailrec
          // execution loop before it executes. Until that change the claim below was aspirational
          // and this skip, together with `evaluateBranch`'s unconditional pass-through, was the
          // whole of the gap: peer-supplied blocks reached execution with unchecked headers.
          // Do not restore doBlockPreValidation in `evaluateBranch` to compensate -- that would
          // reintroduce the cross-thread race this skip exists to avoid, and duplicate work
          // BlockExecution already does on the right thread.
          val validated =
            if bestHeader.hash == block.header.parentHash then
              IO.pure(Right(BlockExecutionSuccess): Either[ValidationBeforeExecError, BlockExecutionSuccess])
            else doBlockPreValidation(block)
          // A fault of this node's own state met while the block was validated is no verdict on it (spec 018 C5a
          // part 2, B-283: its ommers are checked against ancestors this node does not hold whole). It is told as a
          // state fault, as when it is met in a batch, so that the importer tries the block again and blames no peer.
          validated
            .map(Right(_): Either[StateFault, Either[ValidationBeforeExecError, BlockExecutionSuccess]])
            .recover { case fault: StateFault => Left(fault) }
            .flatMap {
              case Left(fault) =>
                log.warn("Block {} was not judged, this node's own state could not: {}", block.idTag, fault.getMessage)
                IO.pure(BlockImportStateFault(fault))
              // A block whose parent this node does not have is an orphan, not a failed import (B-007, #1683): nothing is
              // wrong with it, the gap is ours, and the peer that sent it is not to blame. It was `UnknownParent` before
              // ETCM-1058 replaced block import by consensus; as a `BlockImportFailed` it blacklisted an honest peer that
              // announced a competing tip, and on a reorging chain that was a good peer lost each time. core-geth drops such
              // a block with a debug line (`eth/fetcher/block_fetcher.go`, importBlocks: "Unknown parent of propagated block").
              case Right(Left(ValidationBeforeExecError(HeaderParentNotFoundError))) =>
                log.debug("Block {} has no parent here: an orphan, not a failed import", block.idTag)
                IO.pure(UnknownParent)
              case Right(Left(error)) =>
                IO.pure(BlockImportFailed(error.describe))
              case Right(Right(BlockExecutionSuccess)) =>
                enqueueAndGetBranch(block, bestHeader.number.value)
                  .map(forwardAndTranslateConsensusResult(_)) // a new branch was created so we give it to consensus
                  .getOrElse(IO.pure(BlockEnqueued)) // the block was not rooted so it was simply enqueued
            }
      case None =>
        log.error("Couldn't find the current best block header")
        IO.pure(BlockImportFailed("Couldn't find the current best block header"))

  /** [[Consensus.refusedWithTheirParent]]: whether `blocks` hang from the consensus layer's branch refused while the
    * head is where it is (spec 018 C5a, B-297). Always false on a chain that follows no consensus layer.
    */
  def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean = consensus.refusedWithTheirParent(blocks)

  def evaluateBranch(blocks: NonEmptyList[Block])(implicit
      blockExecutionScheduler: IORuntime,
      blockchainConfig: BlockchainConfig
  ): IO[BlockImportResult] =
    forwardAndTranslateConsensusResult(blocks)

  /** [[evaluateBranch]] for a batch that arrived with peer-supplied EIP-7928 lists (prefetch hints only). */
  def evaluateBranchWithAccessLists(
      blocks: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit
      blockExecutionScheduler: IORuntime,
      blockchainConfig: BlockchainConfig
  ): IO[BlockImportResult] =
    forwardAndTranslateConsensusResult(blocks, accessLists)

  private def forwardAndTranslateConsensusResult(
      newBranch: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList] = Map.empty
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[BlockImportResult] =
    (if accessLists.isEmpty then consensus.evaluateBranch(newBranch)
     else consensus.evaluateBranchWithAccessLists(newBranch, accessLists))
      .map {
        // On the unified layout a node missing from the held state is a fault of this node, not a hole to fetch: the
        // fetch writes the hash-keyed store, which this layout never reads, and the same block would fail again
        // (spec 018 C5a part 10, forge). It is told as any state fault is, and counted towards the escalation. Missing
        // contract code is not: it is fetched by its hash into the code store, which both layouts share (vault).
        case ConsensusErrorDueToMissingNode(blocksToEnqueue, reason, imported)
            if blockchainReader.holdsStateInPlace && !reason.isInstanceOf[MissingCodeException] =>
          ConsensusStateFault(new HeldStateIncomplete(reason), imported, blocksToEnqueue)
        case other => other
      }
      .map {
        case SelectedNewBestBranch(oldBranch, adopted, weights) =>
          oldBranch.foreach(blockQueue.enqueueBlock(_))
          toldOfTheHead(adopted.lastOption)
          ChainReorganised(oldBranch, adopted, weights)
        case SelectedNewBestBranchPartially(
              oldBranch,
              adopted,
              weights,
              BranchExecutionFailure(_, failingBlockHash, error)
            ) =>
          // B-291: as a reorganisation that went through, and as a partial extension's failure: the invalid block's
          // descendants leave the queue
          oldBranch.foreach(blockQueue.enqueueBlock(_))
          blockQueue.removeSubtree(BlockHash(failingBlockHash))
          log.warn("reorganised onto a branch partially because of error: {}", error)
          toldOfTheHead(adopted.lastOption)
          ChainReorganised(oldBranch, adopted, weights)
        case ExtendedCurrentBestBranch(blockImportData) =>
          toldOfTheHead(blockImportData.lastOption.map(_.block))
          BlockImportedToTop(blockImportData)
        case ExtendedCurrentBestBranchPartially(
              blockImportData,
              BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error)
            ) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          blockQueue.removeSubtree(BlockHash(failingBlockHash))
          log.warn("extended best branch partially because of error: {}", error)
          toldOfTheHead(blockImportData.lastOption.map(_.block))
          BlockImportedToTop(blockImportData)
        case KeptCurrentBestBranch =>
          newBranch.toList.foreach(blockQueue.enqueueBlock(_))
          BlockEnqueued
        case BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          blockQueue.removeSubtree(BlockHash(failingBlockHash))
          // Carry WHICH block failed. Equal to, and matched exactly like, `BlockImportFailed(error)` everywhere else;
          // only BlockImporter's invalid-chain report reads the hash. See BlockImportFailedAt.
          new BlockImportFailedAt(error, failingBlockHash)
        case ConsensusError(blocksToEnqueue, error) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          // A branch hanging from a block this node does not have is an orphan too (see evaluateBranchBlock), whichever
          // way it arrived: right after SNAP hand-off a fetched batch can start above the best block (#1683).
          if Consensus.isUnknownParent(error) then UnknownParent else BlockImportFailed(error)
        case ConsensusErrorDueToMissingNode(blocksToEnqueue, reason, imported) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          BlockImportFailedDueToMissingNode(reason, imported)
        case ConsensusStateFault(fault, imported, blocksToEnqueue) =>
          // The blocks were not judged: they wait in the queue to be tried again, and none of them is dropped from it
          // as an invalid block's descendants are.
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          // the blocks of the batch that became the head before the fault moved it, and are no part of what is refused
          toldOfTheHead(imported.lastOption.map(_.block))
          toldTheFeed("a branch that was not judged")(
            _.notJudged(newBranch.toList.drop(imported.size), fault, blockValidation.firstInvalidHeader)
          )
          BlockImportStateFault(fault, imported)
      }

  /** A proof-of-work branch, known by its `headers` from the block above the one it leaves the chain at, that regular
    * sync refused before any of its blocks was fetched (B-300 g): it reaches further above that block than the fetcher
    * holds, or the head cannot be moved back there. Told to the escalation's feed, as a branch this node's state could
    * not take is ([[PowEscalationFeed.notJudgedHeaders]]), and with the header validation of an import.
    *
    * To be called under the import lock: the feed reads the head's weight, and is told one import at a time.
    */
  def branchOutOfReach(headers: List[BlockHeader], fault: StateFault)(implicit
      blockchainConfig: BlockchainConfig
  ): Unit =
    toldTheFeed("a branch out of reach")(_.notJudgedHeaders(headers, fault, blockValidation.firstInvalidHeaderOf))

  /** Whether the branch of `headers` outweighs the head, by the weights the escalation's feed counts a branch by
    * ([[PowEscalationFeed.outweighsTheHead]]). False with no feed, and where the weights cannot be read: a fetch of the
    * branch's headers then goes on to its other ends (B-300 g).
    */
  def branchOutweighsTheHead(headers: List[BlockHeader]): Boolean =
    escalationFeed.exists { feed =>
      try feed.outweighsTheHead(headers)
      catch
        case NonFatal(failed) =>
          log.warn("[STATE-ESCALATION] The weight of a branch out of reach could not be read", failed)
          false
    }

  private def toldOfTheHead(head: Option[Block]): Unit =
    head.foreach(block => toldTheFeed("a head that moved")(_.headMoved(block)))

  /** The escalation's feed is told after the import is done, and says only: whatever goes wrong in it is logged, and
    * the import's result stands (forge).
    */
  private def toldTheFeed(of: String)(tell: PowEscalationFeed => Unit): Unit =
    escalationFeed.foreach { feed =>
      try tell(feed)
      catch
        case NonFatal(failed) =>
          log.error(s"[STATE-ESCALATION] The escalation's feed could not be told of $of; the import stands", failed)
    }

  private def doBlockPreValidation(block: Block)(implicit
      blockchainConfig: BlockchainConfig
  ): IO[Either[ValidationBeforeExecError, BlockExecutionSuccess]] =
    IO
      .delay(blockValidation.validateBlockBeforeExecution(block))
      .flatTap {
        case Left(error) =>
          IO(
            log.debug(
              "Error while validating block with hash {} before execution: {}",
              Hex.toHexString(block.hash.toArray),
              error.describe
            )
          )
        case Right(_) => IO(log.debug("Block with hash {} validated successfully", Hex.toHexString(block.hash.toArray)))
      }
      .evalOn(validationScheduler.compute)

  private def isBlockADuplicate(block: BlockHeader, currentBestBlockNumber: BigInt): Boolean =
    val hash = block.hash
    (blockchainReader.getBlockByHash(hash).isDefined && block.number.value <= currentBestBlockNumber) ||
    blockQueue.isQueued(hash)

  private def enqueueAndGetBranch(block: Block, bestBlockNumber: BigInt): Option[NonEmptyList[Block]] =
    blockQueue
      .enqueueBlock(block, bestBlockNumber)
      .map(topBlock => blockQueue.getBranch(BlockHash(topBlock.hash), dequeue = true))
      .flatMap(NonEmptyList.fromList)
