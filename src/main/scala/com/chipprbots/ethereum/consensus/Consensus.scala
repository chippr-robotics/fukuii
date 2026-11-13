package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.consensus.Consensus.ConsensusResult
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.utils.BlockchainConfig

/** This file documents the original interface that was designed at ETCM-1018 but implements a different one to be used
  * as a stepping stone to the new architecture still in progress
  */
trait Consensus:
  def evaluateBranch(
      block: NonEmptyList[Block]
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult]

  /** [[evaluateBranch]] with peer-supplied EIP-7928 lists keyed by block hash, used as execution prefetch hints only.
    * The default ignores them, which is always correct.
    */
  def evaluateBranchWithAccessLists(
      block: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
    val _ = accessLists
    evaluateBranch(block)

object Consensus:
  /* This return type for consensus is probably overcomplicated for now because some information is needed
   * to keep the compatibility with the current code (particularly for the block queue handling), and be able
   * to translate the values to BlockImportResult.
   * In particular:
   *  - `blockToEnqueue` fields won't be needed if the block are already stored in memory
   *  - The distinction between ExtendedCurrentBestBranch and SelectedNewBestBranch won't really be useful
   *  because there will be no need to put back the old branch into the block queue in case of reorganisation
   *  - `ConsensusErrorDueToMissingNode` and  `ConsensusError` would mean that the application is in an
   *  inconsistent state. Unless there is a reason to think that fukuii would self heal when that happens, I
   *  don't think there is a reason to add them here.
   */

  sealed trait ConsensusResult

  /** The new branch was selected and it extended the best branch. */
  case class ExtendedCurrentBestBranch(blockImportData: List[BlockData]) extends ConsensusResult

  /** The new branch was selected and it extended the best branch, but it did not execute completely. */
  case class ExtendedCurrentBestBranchPartially(blockImportData: List[BlockData], failureBranch: BranchExecutionFailure)
      extends ConsensusResult

  /** The new branch was selected but was not an extension of the best branch. */
  case class SelectedNewBestBranch(oldBranch: List[Block], newBranch: List[Block], weights: List[ChainWeight])
      extends ConsensusResult

  /** The proposed new branch was not better than the current best one. */
  case object KeptCurrentBestBranch extends ConsensusResult

  /** A block in the branch cannot be executed. */
  case class BranchExecutionFailure(blockToEnqueue: List[Block], failingBlockHash: ByteString, error: String)
      extends ConsensusResult

  /** What a [[BranchExecutionFailure]]'s `error` starts with when the branch was a reorganisation, ahead of the
    * execution error's own text. One constant for the text written and the text read (spec 022 T062): the importer
    * tells this node's own state faults from the block's by it, so a reworded copy would put the blame back on peers.
    */
  val ReorganiseFailurePrefix: String = "Error while trying to reorganise chain: "

  /** How a block execution's `MPTError` reads in a [[BranchExecutionFailure]]:
    * `BlockExecutionError.MPTError(e).toString` as it is when the branch extends the head, and after
    * [[ReorganiseFailurePrefix]] when it reorganises.
    */
  private val MptErrorText: String = "MPTError("

  /** True when a branch failed on a trie error that is not a missing node: this node's own state is bad (spec 022
    * T062), and neither the block nor the peer that sent it is. A missing node never arrives as text: it is a
    * [[ConsensusErrorDueToMissingNode]], and a stored entry that is not its node is one (`CorruptNodeException`).
    */
  def isLocalStateFault(error: String): Boolean =
    error.startsWith(MptErrorText) || error.startsWith(ReorganiseFailurePrefix + MptErrorText)

  /** What a [[ConsensusError]] starts with when the branch hangs from a block whose weight is not stored, that is, a
    * block this node does not have (B-007, #1683). One constant for the text written
    * (`ConsensusImpl.importToNewBranch`) and the text read ([[isUnknownParent]]), as [[ReorganiseFailurePrefix]] is, so
    * that a reworded copy cannot put the blame for it back on a peer.
    */
  val UnknownParentErrorPrefix: String = "Could not get weight for parent block "

  /** True when a branch failed for want of its parent: a gap on this node's side. Nothing is wrong with the blocks, nor
    * with the peer that sent them, and the branch is to be fetched, not penalised.
    */
  def isUnknownParent(error: String): Boolean = error.startsWith(UnknownParentErrorPrefix)

  /** An error external the the blocks in the branch occured, which prevents the branch from being executed. Usually
    * this is due to an inconsistency in the database.
    */
  case class ConsensusError(blockToEnqueue: List[Block], err: String) extends ConsensusResult

  /** This node's own state could not take a block of the branch (spec 018 Track C, C4b): the state the block is to be
    * executed on is not held, or is no longer, or the head could not be moved to it. '''Nothing is wrong with the
    * block, nor with the peer that sent it''': no block is reported invalid for it and no peer is penalised, and the
    * blocks that were not executed are tried again.
    *
    * `imported` is the prefix of the batch that was adopted before the fault, as for
    * [[ConsensusErrorDueToMissingNode]]: those blocks are the node's head already. `blocksToEnqueue` are the blocks
    * that were not executed, the one the fault was met at first.
    */
  case class ConsensusStateFault(
      fault: StateFault,
      imported: List[BlockData] = Nil,
      blocksToEnqueue: List[Block] = Nil
  ) extends ConsensusResult

  /** A block hit a missing state node. `imported` is the prefix of the batch that executed and validated BEFORE it:
    * those blocks are already adopted (state and best block), exactly as a successful shorter batch would have left
    * them, so the caller must account for them and retry from the failing block, not from the head of the batch.
    */
  case class ConsensusErrorDueToMissingNode(
      blockToEnqueue: List[Block],
      reason: MissingNodeException,
      imported: List[BlockData] = Nil
  ) extends ConsensusResult
