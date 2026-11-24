package com.chipprbots.ethereum.consensus

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.util.Try
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
import com.chipprbots.ethereum.consensus.Consensus.ConsensusResult
import com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranchPartially
import com.chipprbots.ethereum.consensus.Consensus.KeptCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranchPartially
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.db.storage.pathstate.HeldStateIncomplete
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.ChainGap
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
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
    escalationFeed: Option[PowEscalationFeed] = None,
    // What stops the node when a write of its store is refused (B-440, the user's decision of 2026-11-24): the node's
    // stop, then a non-zero exit (`StopSignal.stopThenExitAside`). Called on the import's thread, so it must return at
    // once: the database's close waits for the import to end.
    onFatalStoreFailure: Throwable => Unit = ConsensusAdapter.NoStopWired
) extends Logger:

  /** Set once a failure of the store has asked for the node's stop (B-440, forge C2), BEFORE the stop is asked for. The
    * stop runs on a thread of its own and the database's close waits for the import, so batches keep coming until the
    * actor system goes: the importer re-queues, the fetcher is rewound, and the import lock is free between batches.
    * From here on no batch is evaluated, and none can write: each is answered as not judged, not counted.
    */
  private val stopAskedFor = new AtomicBoolean(false)

  /** How many times the store answered a batch busy since a block last became the head (B-440, forge W1), whichever
    * block the batches stand on: batches that alternate between branches, on the same parent or not, count together.
    * Only a head that moved starts the count again ([[toldOfTheHead]]): a branch kept as it is, or another failure,
    * wrote nothing, and says nothing of a store that takes writes.
    */
  private val busySinceTheHeadMoved = new AtomicInteger(0)

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
    forwardAndTranslateConsensusResult(blocks, storeFailureIsAFault = true)

  /** [[evaluateBranch]] for a batch that arrived with peer-supplied EIP-7928 lists (prefetch hints only). */
  def evaluateBranchWithAccessLists(
      blocks: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit
      blockExecutionScheduler: IORuntime,
      blockchainConfig: BlockchainConfig
  ): IO[BlockImportResult] =
    forwardAndTranslateConsensusResult(blocks, accessLists, storeFailureIsAFault = true)

  /** @param storeFailureIsAFault
    *   a failure of this node's store thrown out of the evaluation is answered as a fault of this node's state
    *   ([[thrownByTheStore]], B-440). Regular sync's batches only: the other callers of [[evaluateBranchBlock]] (test
    *   mode, the chain-file import) answer what is thrown as they always did.
    */
  private def forwardAndTranslateConsensusResult(
      newBranch: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList] = Map.empty,
      storeFailureIsAFault: Boolean = false
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[BlockImportResult] =
    evaluated(newBranch, accessLists, storeFailureIsAFault)
      .map {
        // On the unified layout a node missing from the held state with no position to say is a fault of this node, not
        // a hole to fetch: the fetch writes the hash-keyed store, which this layout never reads, and the same block
        // would fail again (spec 018 C5a part 10, forge). It is told as any state fault is, and counted towards the
        // escalation. One that names its position is fetched by it and written there by `repairNodeAt` (C5b): a view of
        // the rows alone says it. A view with layers over the rows names none, on purpose (`PathMptStorage` throws a
        // `MerklePatriciaTrie.Unplaced` one, which the trie's traversal leaves unlocated), and that stays a fault.
        // One that is fetched and cannot be repaired does not loop through here for ever: the importer tells the feed
        // of it once its exhausts reach their threshold ([[holeNotRepaired]]). Missing contract code is fetched by its
        // hash into the code store, which both layouts share (vault).
        case ConsensusErrorDueToMissingNode(blocksToEnqueue, reason, imported)
            if blockchainReader.holdsStateInPlace && !reason.isInstanceOf[MissingCodeException] &&
              reason.location.isEmpty =>
          ConsensusStateFault(new HeldStateIncomplete(reason), imported, blocksToEnqueue)
        case other => other
      }
      .map {
        case SelectedNewBestBranch(oldBranch, adopted, weights, branchBlocks) =>
          oldBranch.foreach(blockQueue.enqueueBlock(_))
          toldOfTheHead(adopted.lastOption)
          ChainReorganised(oldBranch, adopted, weights, branchBlocks)
        case SelectedNewBestBranchPartially(
              oldBranch,
              adopted,
              weights,
              BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error)
            ) if Consensus.isLocalStateFault(error) =>
          // B-337: the prefix became the head, as above, and what stopped it is this node's own state fault (spec 022
          // T062). The blocks it replaced are queued again and the head's followers told, as for any reorganisation;
          // the failing block and those after it were not judged, so they wait in the queue and none is dropped. The
          // failure is answered as the fault the importer escalates, with the blocks of the batch that are the head
          // now as imported, so that the escalation starts at the failing block and blames no peer.
          oldBranch.foreach(blockQueue.enqueueBlock(_))
          queuedAgain(blocksToEnqueue)
          toldOfTheHead(adopted.lastOption)
          val ofTheBatch = newBranch.toList.map(_.header.hash).toSet
          val imported = adopted.zip(weights).collect {
            case (block, weight) if ofTheBatch(block.header.hash) => BlockData(block, Nil, weight)
          }
          new BlockImportFailedAt(error, failingBlockHash, imported)
        case SelectedNewBestBranchPartially(
              oldBranch,
              adopted,
              _,
              BranchExecutionFailure(_, failingBlockHash, error)
            ) =>
          // B-291: the prefix became the head, so the blocks it replaced are queued again and the head's followers
          // are told, as for a reorganisation that went through; and the failure is answered as one, with the block
          // that failed, as a partial extension's is not: the importer's invalid-chain report (the Engine API's
          // latestValidHash for a gas-used mismatch, hive's invalid-ancestor tests) and its blame read it. The pool's
          // update for the blocks moved off and onto is B-338's.
          oldBranch.foreach(blockQueue.enqueueBlock(_))
          blockQueue.removeSubtree(BlockHash(failingBlockHash))
          toldOfTheHead(adopted.lastOption)
          new BlockImportFailedAt(error, failingBlockHash)
        case ExtendedCurrentBestBranch(blockImportData) =>
          toldOfTheHead(blockImportData.lastOption.map(_.block))
          BlockImportedToTop(blockImportData)
        case ExtendedCurrentBestBranchPartially(
              blockImportData,
              BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error)
            ) if Consensus.isLocalStateFault(error) =>
          // B-336: a fault of this node's own state (spec 022 T062) after a prefix became the head. The prefix is
          // imported, and the head's followers are told of it; the failing block was not judged, so it and those after
          // it wait in the queue, none is dropped as an invalid block's descendants are, and the failure is answered
          // as the fault it is, with the prefix, so that the importer escalates it from the failing block and blames
          // no peer.
          queuedAgain(blocksToEnqueue)
          log.warn(
            "extended best branch by {} block(s), then met a fault of this node's own state: {}",
            blockImportData.size,
            error
          )
          toldOfTheHead(blockImportData.lastOption.map(_.block))
          new BlockImportFailedAt(error, failingBlockHash, blockImportData)
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
        case BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error) if Consensus.isLocalStateFault(error) =>
          // a fault of this node's own state judged no block: the failing block and what hangs from it stay queued
          // (B-336), as a block's own error does not leave them
          queuedAgain(blocksToEnqueue)
          new BlockImportFailedAt(error, failingBlockHash)
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
          // the blocks of the batch that became the head before the missing node (B-337): its followers are told
          toldOfTheHead(imported.lastOption.map(_.block))
          BlockImportFailedDueToMissingNode(reason, imported)
        case result @ ConsensusStateFault(fault, imported, blocksToEnqueue) =>
          // The blocks were not judged: they wait in the queue to be tried again, and none of them is dropped from it
          // as an invalid block's descendants are.
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          // A store that refused a write, or was busy, says nothing of this node's state: not counted (B-440), and no
          // feed call of a counted fault is made for it, the side blocks' head of B-461 included (forge N3)
          val counted = !result.isInstanceOf[ConsensusAdapter.NotCounted]
          // the blocks of the batch that became the head before the fault moved it, and are no part of what is refused;
          // or the side blocks the branch stands on that a move back that failed left the head on (B-461)
          toldOfTheHead(
            imported.lastOption
              .orElse(if counted then Consensus.headLeftOn(result).lastOption else None)
              .map(_.block)
          )
          if counted then
            toldTheFeed("a branch that was not judged")(
              _.notJudged(newBranch.toList.drop(imported.size), fault, blockValidation.firstInvalidHeaderNotJudged)
            )
          BlockImportStateFault(fault, imported)
      }

  private def evaluated(
      newBranch: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList],
      storeFailureIsAFault: Boolean
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
    IO.defer(
      if stopAskedFor.get then
        // the node stops (forge C2): nothing more is evaluated, so nothing more is written; the blocks wait, not judged
        IO.pure(
          new ConsensusAdapter.NotCounted(
            new StateUnavailable("this node's store failed and the node stops: no block is evaluated"),
            Nil,
            newBranch.toList
          )
        )
      else
        IO.defer(
          if accessLists.isEmpty then consensus.evaluateBranch(newBranch)
          else consensus.evaluateBranchWithAccessLists(newBranch, accessLists)
        ).recover(Function.unlift { (thrown: Throwable) =>
          Option
            .when(storeFailureIsAFault)(ConsensusAdapter.thrownFromTheStore(thrown))
            .flatten
            .map(thrownByTheStore(newBranch, thrown, _))
        })
    )

  /** A proof-of-work branch, known by its `headers` from the block above the one it leaves the chain at, that regular
    * sync refused before any of its blocks was fetched (B-300 g): it reaches further above that block than the fetcher
    * holds, or the head cannot be moved back there. Told to the escalation's feed, as a branch this node's state could
    * not take is ([[PowEscalationFeed.notJudgedHeaders]]), and with the header validation of an import, less the parent
    * rules of a first header that stands on this node's own block whose header is not stored
    * ([[BlockValidation.firstInvalidHeaderOfNotJudged]], B-391).
    *
    * To be called under the import lock: the feed reads the head's weight, and is told one import at a time.
    */
  def branchOutOfReach(headers: List[BlockHeader], fault: StateFault)(implicit
      blockchainConfig: BlockchainConfig
  ): Unit =
    toldTheFeed("a branch out of reach")(
      _.notJudgedHeaders(headers, fault, blockValidation.firstInvalidHeaderOfNotJudged)
    )

  /** A proof-of-work branch, known by its `headers` from the block above the one it stands on, whose fork choice could
    * not be made: the chain weight of that block is not stored (`BranchResolution.withoutForkWeight`, B-385). No
    * verdict was given on it, and it is a fault of this node's datadir: told to the escalation's feed as a branch this
    * node's state could not take is, with the header validation of an import less the parent rules of a first header
    * that stands on this node's own block whose header is not stored, as [[branchOutOfReach]] is
    * ([[BlockValidation.firstInvalidHeaderOfNotJudged]], B-392), and counted by how far it grows. The feed weighs it
    * from the head, by the head's ancestry down to the hash the first header names, without the header of that block; a
    * branch it cannot weigh, or that is not the heavier, is not counted.
    *
    * To be called under the import lock, as [[branchOutOfReach]] is.
    */
  def forkWeightNotStored(headers: List[BlockHeader])(implicit blockchainConfig: BlockchainConfig): Unit =
    headers.headOption.foreach { first =>
      val fault = new StateUnavailable(
        s"the chain weight of block ${first.number.value - 1} (${first.parentHash.toHexString.take(10)}), the block " +
          s"the branch from block ${first.number} stands on, is not stored: no fork choice can be made"
      )
      toldTheFeed("a branch whose fork weight is not stored")(
        // B-392 (the user's decision of 2026-11-19): the seal-only first header of B-391, as for a branch out of reach
        _.notJudgedHeaders(headers, fault, blockValidation.firstInvalidHeaderOfNotJudged)
      )
    }

  /** A proof-of-work branch, known by its headers above those of this node's chain, whose fork choice could not be
    * made: a block of this node's chain above the SNAP pivot is not stored, its header, its index entry, or the body of
    * a block the branch would displace (`BranchResolution.acrossAGap`, B-395, B-407). No verdict was given on it, and
    * it is a fault of this node's datadir: told to the escalation's feed as a branch whose fork weight is not stored is
    * ([[forkWeightNotStored]]), with the same header validation, the first header by its seal alone where it stands on
    * this node's block whose header is not stored (B-391), and counted by how far it grows. The feed weighs it from the
    * head and asks MESS of it; a branch it cannot weigh, that is not the heavier, or that MESS refuses, is not counted.
    *
    * To be called under the import lock, as [[branchOutOfReach]] is.
    */
  def chainGapNotStored(gap: ChainGap)(implicit blockchainConfig: BlockchainConfig): Unit =
    val first = gap.side.head
    val missing =
      if gap.bodyOnly then
        s"the body of block ${gap.firstMissing} of this node's chain, a block the branch would displace, is not stored"
      else s"block ${gap.firstMissing} of this node's chain is not stored (its header or its hash index entry)"
    val fault = new StateUnavailable(
      s"$missing: the branch from block ${first.number} cannot be set against this node's chain, and no fork choice " +
        "can be made"
    )
    toldTheFeed("a branch over a gap in this node's chain")(
      _.notJudgedHeaders(gap.side.toList, fault, blockValidation.firstInvalidHeaderOfNotJudged)
    )

  /** A hole in the held state that the importer fetched by its position and could not fill, as many times as its
    * exhausts allow (spec 018 C5b, B-291, forge): the repair is declined for good, or no peer serves the node. On the
    * unified layout nothing else moves it (the sync controller ignores `RegularSyncStuck` there), so it is told to the
    * escalation's feed as every state fault of this node is: `blocks` were not judged, and the fault is
    * [[HeldStateIncomplete]]. To be called under the import lock, as [[branchOutOfReach]] is.
    */
  def holeNotRepaired(blocks: List[Block], missing: MissingNodeException)(implicit
      blockchainConfig: BlockchainConfig
  ): Unit =
    toldTheFeed("a hole no repair fills")(
      _.notJudged(blocks, new HeldStateIncomplete(missing), blockValidation.firstInvalidHeaderNotJudged)
    )

  /** The first of `headers` that is not valid, with why: the header validation of an import, the seal with it
    * ([[BlockValidation.firstInvalidHeaderOf]]). Reads only; for a branch weighed below the fork recovery's floor on
    * the hash layout (B-346).
    */
  def firstInvalidHeaderOf(headers: List[BlockHeader])(implicit
      blockchainConfig: BlockchainConfig
  ): Option[(BlockHeader, String)] =
    blockValidation.firstInvalidHeaderOf(headers)

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

  /** A failure of this node's store thrown out of the evaluation of `newBranch` (B-440), as
    * [[ConsensusAdapter.thrownFromTheStore]] reads it. It is no verdict on any block. `ConsensusImpl` throws it (a
    * commit in place at the cap throws it once the head is back at the old head, as #2001 pins); answered by the throw,
    * the importer dropped the batch with an error line. It is answered here as a fault of this node's state is: the
    * blocks of the batch the head stands on now (read from the store, by ancestry: what threw kept no account of them)
    * are imported, and the others wait in the queue and are fetched again without blame. Only a fault of the state
    * (thrown as it is, or a store that reads as corrupt) is told to the feed as a branch not judged; a store that
    * failed stops the node, and a store that was busy is tried again, up to [[ConsensusAdapter.BusyBeforeStop]] times
    * with no block made the head between.
    *
    * The stop is asked for before the store is read (forge C1): a disk that refused a write may refuse the reads too,
    * and the stop must not hang on them. Otherwise the reads here run inside the evaluation's `recover`: one that
    * throws propagates out of it, and the batch is dropped by the importer with an error line, as before B-440.
    */
  private def thrownByTheStore(
      newBranch: NonEmptyList[Block],
      thrown: Throwable,
      failure: ConsensusAdapter.StoreFailure
  ): ConsensusResult =
    import ConsensusAdapter.StoreFailure
    val blocks = s"[${newBranch.head.number}-${newBranch.last.number}]"
    val busyInARow = failure match
      case StoreFailure.Busy => busySinceTheHeadMoved.incrementAndGet()
      // no reset here: a store corrupt or a fault of the state wrote nothing; only a head that moved starts the count
      // again, and a store that failed stops the node
      case _ => 0
    if failure == StoreFailure.Failed || busyInARow >= ConsensusAdapter.BusyBeforeStop then
      stopped(newBranch, thrown, blocks, busyInARow)
    else
      val imported = importedOf(newBranch)
      val queued = newBranch.toList.drop(imported.size)
      failure match
        case StoreFailure.OfTheState(fault) =>
          // the fault as it was thrown, never wrapped: its type is what the escalation's feed reads (B-462)
          log.warn(
            s"[STATE-FAULT] The import of blocks $blocks met a fault of this node's state, after ${imported.size} " +
              s"block(s) of it became the head: no block was judged, the others are queued again: ${fault.getMessage}"
          )
          ConsensusStateFault(fault, imported, queued)
        case StoreFailure.Corrupt =>
          log.error(
            s"[STATE-FAULT] The import of blocks $blocks found this node's store corrupt, after ${imported.size} " +
              "block(s) of it became the head: no block was judged, the others are queued again and no peer is blamed",
            thrown
          )
          ConsensusStateFault(
            new StateUnavailable(s"this node's store is corrupt: $thrown"),
            imported,
            queued
          )
        case _ =>
          log.warn(
            s"[STATE-FAULT] The import of blocks $blocks met this node's store busy (${ConsensusAdapter.statusOf(thrown)}, " +
              s"$busyInARow time(s) with no block made the head between; the node stops at " +
              s"${ConsensusAdapter.BusyBeforeStop}), after ${imported.size} block(s) of it became the head: the others " +
              s"are queued again and tried again, no peer is blamed: $thrown"
          )
          new ConsensusAdapter.NotCounted(
            new StateUnavailable(s"this node's store was busy: $thrown", askAgain = true),
            imported,
            queued
          )

  /** The node stops (the user's decision of 2026-11-24): go-ethereum stops at once (log.Crit) when a write of a block
    * or its state fails, and RocksDB takes no write after one it refused. No retry, no escalation, a loud stop. The
    * latch is set first, then the stop is asked for, and only then is the store read for what the head stands on (forge
    * C1, C2); a read that fails there leaves the answer with nothing imported.
    */
  private def stopped(
      newBranch: NonEmptyList[Block],
      thrown: Throwable,
      blocks: String,
      busyInARow: Int
  ): ConsensusResult =
    val why =
      if busyInARow >= ConsensusAdapter.BusyBeforeStop then
        s"was busy $busyInARow times with no block made the head between " +
          s"(${ConsensusAdapter.statusOf(thrown)}), a store that does not take the write"
      else s"failed (${ConsensusAdapter.statusOf(thrown)})"
    val first = stopAskedFor.compareAndSet(false, true)
    log.error(s"[STORE-FAILED] This node's store $why during the import of blocks $blocks: the node stops", thrown)
    if first then onFatalStoreFailure(thrown)
    val imported = Try(importedOf(newBranch)).getOrElse(Nil)
    new ConsensusAdapter.NotCounted(
      new StateUnavailable(s"this node's store $why, and the node stops: $thrown"),
      imported,
      newBranch.toList.drop(imported.size)
    )

  /** The blocks of `newBranch` the head stands on, read from the store by ancestry, with their weights and receipts. */
  private def importedOf(newBranch: NonEmptyList[Block]): List[BlockData] =
    val first = newBranch.head.number.value
    val onTheHeadsChain: Set[BlockHash] =
      Iterator
        .iterate(blockchainReader.getBestBlockHeader)(
          _.flatMap(h => blockchainReader.getBlockHeaderByHash(h.parentHash))
        )
        .takeWhile(_.exists(_.number.value >= first))
        .flatten
        .map(_.hash)
        .toSet
    newBranch.toList.iterator
      .takeWhile(block => onTheHeadsChain(block.header.hash))
      .map(block =>
        blockchainReader
          .getChainWeightByHash(block.header.hash)
          .map(BlockData(block, blockchainReader.getReceiptsByHash(block.header.hash).getOrElse(Nil), _))
      )
      .takeWhile(_.isDefined)
      .flatten
      .toList

  /** Blocks a fault of this node's own state left unjudged, queued again against the head as it is now (B-336). */
  private def queuedAgain(blocks: List[Block]): Unit =
    if blocks.nonEmpty then
      val bestBlockNumber = blockchainReader.getBestBlockNumber
      blocks.foreach(blockQueue.enqueueBlock(_, bestBlockNumber))

  /** A block became the head: the escalation's feed is told, and the store took a write, so the count of busy answers
    * starts again (forge W1).
    */
  private def toldOfTheHead(head: Option[Block]): Unit =
    head.foreach { block =>
      busySinceTheHeadMoved.set(0)
      toldTheFeed("a head that moved")(_.headMoved(block))
    }

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

object ConsensusAdapter extends Logger:

  /** What a failure thrown out of an import's evaluation is, read from its cause chain (B-440). */
  private[consensus] enum StoreFailure:

    /** A fault of this node's state, thrown rather than answered: answered as it is, and counted as any. */
    case OfTheState(fault: StateFault)

    /** RocksDB reads its store as corrupt (`Status.Code.Corruption`): something of this node's state, counted. */
    case Corrupt

    /** RocksDB refused for now (`Busy`, `TryAgain`, `TimedOut`, `Aborted`, `Incomplete`): the same write may well go
      * through at the next try, and says nothing of the state. Tried again, not counted.
      */
    case Busy

    /** The store failed (`IOError`, `NoSpace` among them, a `RocksDBException` without a status, or an `IOException`):
      * mostly a write the disk refused, but a read that failed lands here too. The node stops.
      */
    case Failed

  /** A [[ConsensusStateFault]] the escalation's feed is not told of: a store that was busy, or that refused a write
    * (B-440). Equal to, and matched exactly like, the fault it extends.
    */
  final private[consensus] class NotCounted(fault: StateFault, imported: List[BlockData], queued: List[Block])
      extends ConsensusStateFault(fault, imported, queued)

  /** An adapter built without the node's stop (a test rig, a tool): what would stop the node is said, and no more. */
  val NoStopWired: Throwable => Unit = thrown =>
    log.error(s"[STORE-FAILED] No stop is wired to this import: the node is not stopped ($thrown)")

  /** How many batches the store may answer busy, with no block made the head between, before the node stops as for a
    * store that failed (forge W1). On the same block the importer tries the first two again at once and spaces the
    * tries out from the third on (`BlockImporter.StateFaultBackoffAfter`, `exhaustBackoff`, 5 minutes), so the fifth
    * comes some ten minutes after the first. A write stall, a compaction behind or a lock timed out clears in seconds;
    * a store that stays busy takes no write, and a node that retries it for ever follows no chain while it looks alive.
    */
  private[consensus] val BusyBeforeStop: Int = 5

  /** RocksDB's status code (and sub-code) in `thrown`'s cause chain, "no status" for a `RocksDBException` without one,
    * else the class of the `IOException`: what the `[STORE-FAILED]` line names (forge N1).
    */
  private[consensus] def statusOf(thrown: Throwable): String =
    val chain = Iterator.iterate(thrown)(_.getCause).takeWhile(_ != null).take(16).toList
    chain
      .collectFirst { case rocks: org.rocksdb.RocksDBException =>
        Option(rocks.getStatus).fold("RocksDB, no status") { status =>
          val sub = Option(status.getSubCode).filter(_ != org.rocksdb.Status.SubCode.None).fold("")(c => s"/$c")
          s"RocksDB ${status.getCode}$sub"
        }
      }
      .orElse(chain.collectFirst { case io: java.io.IOException => io.getClass.getSimpleName })
      .getOrElse(thrown.getClass.getSimpleName)

  private val TransientCodes: Set[org.rocksdb.Status.Code] = Set(
    org.rocksdb.Status.Code.Busy,
    org.rocksdb.Status.Code.TryAgain,
    org.rocksdb.Status.Code.TimedOut,
    org.rocksdb.Status.Code.Aborted,
    org.rocksdb.Status.Code.Incomplete
  )

  /** What `thrown` is, if it is a failure of this node's store (B-440), read from its cause chain, so that the wrappers
    * (`RocksDbDataSourceException`, `UncheckedIOException`, `CompletionException`, `ExecutionException`) are seen
    * through. `None`, and it is thrown on as before, when it is not: a store closed under the import, or RocksDB
    * shutting down (the node is stopping); a `RocksDBException` whose status says the call itself was wrong
    * (`InvalidArgument`, `NotSupported`, ...), which is a defect; and anything with neither a `RocksDBException` nor an
    * `IOException` in its chain, which is a defect too and fails loudly.
    */
  private[consensus] def thrownFromTheStore(thrown: Throwable): Option[StoreFailure] =
    val chain = Iterator.iterate(thrown)(_.getCause).takeWhile(_ != null).take(16).toList
    def first[A](pick: PartialFunction[Throwable, A]): Option[A] = chain.collectFirst(pick)
    val stopping = chain.exists {
      case _: RocksDbDataSourceClosedException => true
      case rocks: org.rocksdb.RocksDBException =>
        Option(rocks.getStatus).exists(_.getCode == org.rocksdb.Status.Code.ShutdownInProgress)
      case _ => false
    }
    if stopping then None
    else
      first { case fault: StateFault => StoreFailure.OfTheState(fault) }.orElse(
        first { case rocks: org.rocksdb.RocksDBException => rocks } match
          // RocksDB's status decides; a call it calls wrong is a defect, whatever else the chain holds
          case Some(rocks) =>
            Option(rocks.getStatus).map(_.getCode) match
              case Some(org.rocksdb.Status.Code.Corruption)     => Some(StoreFailure.Corrupt)
              case Some(code) if TransientCodes(code)           => Some(StoreFailure.Busy)
              case Some(org.rocksdb.Status.Code.IOError) | None => Some(StoreFailure.Failed)
              case Some(_)                                      => None
          case None => first { case _: java.io.IOException => StoreFailure.Failed }
      )
