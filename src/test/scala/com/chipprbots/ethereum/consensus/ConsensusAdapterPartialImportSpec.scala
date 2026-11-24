package com.chipprbots.ethereum.consensus

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedAt
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.consensus.Consensus.BranchExecutionFailure
import com.chipprbots.ethereum.consensus.Consensus.ConsensusResult
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranchPartially
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranchPartially
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** What the adapter makes of a branch that stopped part of the way, on a fault of this node's own state or on an error
  * of a block (B-336): the queue it leaves, and the answer the importer acts on.
  */
class ConsensusAdapterPartialImportSpec extends AnyFlatSpec with Matchers with MockFactory:

  private val localFault: String = MPTError(new MPTException("Invalid Node")).toString
  private val invalidBlock: String = ValidationAfterExecError("Block has invalid state root").toString

  private class Setup extends EphemBlockchainTestSetup:
    blockchainWriter.save(BlockHelpers.genesis, Nil, ChainWeight.totalDifficultyOnly(1000), saveAsBestBlock = true)

    // b1 became the head; b2 failed; b3 stands on it. b2 and b3 are queued as blocks announced ahead of the head are
    val branch: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    val queue: BlockQueue = new BlockQueue(blockchainReader, 100, 100)
    branch.drop(1).foreach(queue.enqueueBlock(_))
    // the head moved to b1, as the import left it
    blockchainWriter.save(branch.head, Nil, ChainWeight.totalDifficultyOnly(2000), saveAsBestBlock = true)
    val prefix: List[BlockData] = List(BlockData(branch.head, Nil, ChainWeight.totalDifficultyOnly(2000)))

    def adapterAnswering(result: ConsensusResult): ConsensusAdapter =
      val consensus = new Consensus:
        override def evaluateBranch(
            block: NonEmptyList[Block]
        )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
          IO.pure(result)
      new ConsensusAdapter(consensus, blockchainReader, queue, null, IORuntime.global)

    def imported(result: ConsensusResult): Any =
      adapterAnswering(result).evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeRunSync()

    def queued(block: Block): Boolean = queue.isQueued(BlockHash(block.hash.value))

  "A branch that extended the head part of the way" should "keep the failing block queued, and answer the fault with the prefix, when this node's state faulted" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Setup:
    val answer: Any = imported(
      ExtendedCurrentBestBranchPartially(
        prefix,
        BranchExecutionFailure(branch.drop(1), branch(1).hash.value, localFault)
      )
    )
    answer match
      case at: BlockImportFailedAt =>
        // the importer escalates it, and blames no peer (spec 022 T062)
        Consensus.isLocalStateFault(at.error) shouldBe true
        at.failingBlockHash shouldBe branch(1).hash.value
        // the prefix is the head, and is told as imported
        at.imported shouldBe prefix
      case other => fail(s"not the fault, with the prefix: $other")
    withClue("neither the failing block nor the block on it is dropped as an invalid block's: ") {
      queued(branch(1)) shouldBe true
      queued(branch(2)) shouldBe true
    }
  )

  it should "drop the failing block and what stands on it, and answer the prefix imported, when the block was invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Setup:
    imported(
      ExtendedCurrentBestBranchPartially(prefix, BranchExecutionFailure(Nil, branch(1).hash.value, invalidBlock))
    ) shouldBe BlockImportedToTop(prefix)
    queued(branch(1)) shouldBe false
    queued(branch(2)) shouldBe false
  )

  "A branch whose first block failed" should "stay queued when this node's state faulted, and be dropped when the block was invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Setup:
    val faulted: Any = imported(BranchExecutionFailure(branch.drop(1), branch(1).hash.value, localFault))
    faulted shouldBe BlockImportFailed(localFault)
    BlockImportFailedAt.importedBefore(faulted.asInstanceOf[BlockImportFailed]) shouldBe Nil
    queued(branch(1)) shouldBe true
    queued(branch(2)) shouldBe true

    imported(BranchExecutionFailure(Nil, branch(1).hash.value, invalidBlock)) shouldBe BlockImportFailed(invalidBlock)
    queued(branch(1)) shouldBe false
    queued(branch(2)) shouldBe false
  )

  // B-337: a reorganisation whose prefix became the head before this node's state fault. `side` stands for a block the
  // branch stands on that became canonical with it (told ahead of the branch, never one of the batch's)
  private class Reorganised extends Setup:
    val side: Block = BlockHelpers
      .generateChain(1, BlockHelpers.genesis, b => b.copy(header = b.header.copy(difficulty = Difficulty(7))))
      .head
    val oldHead: Block = BlockHelpers
      .generateChain(1, BlockHelpers.genesis, b => b.copy(header = b.header.copy(difficulty = Difficulty(9))))
      .head
    val weights: List[ChainWeight] = List(ChainWeight.totalDifficultyOnly(1500), ChainWeight.totalDifficultyOnly(2000))
    def partially(blocksToEnqueue: List[Block], error: String): ConsensusResult =
      SelectedNewBestBranchPartially(
        List(oldHead),
        List(side, branch.head),
        weights,
        BranchExecutionFailure(blocksToEnqueue, branch(1).hash.value, error)
      )

  "A reorganisation whose prefix became the head" should "queue the old branch again, keep the failing block queued, and answer the fault with the prefix, when this node's state faulted" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Reorganised:
    imported(partially(branch.drop(1), s"${Consensus.ReorganiseFailurePrefix}$localFault")) match
      case at: BlockImportFailedAt =>
        Consensus.isLocalStateFault(at.error) shouldBe true
        at.failingBlockHash shouldBe branch(1).hash.value
        // the blocks of the batch that are the head, with their weights: what the importer counts the batch by
        at.imported shouldBe List(BlockData(branch.head, Nil, weights(1)))
      case other => fail(s"not the fault, with the prefix: $other")
    queued(oldHead) shouldBe true
    queued(branch(1)) shouldBe true
    queued(branch(2)) shouldBe true
  )

  it should "queue the old branch again and drop the failing block, as before, when the block was invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Reorganised:
    val error: String = s"${Consensus.ReorganiseFailurePrefix}$invalidBlock"
    imported(partially(Nil, error)) match
      case at: BlockImportFailedAt =>
        at shouldBe BlockImportFailed(error)
        at.failingBlockHash shouldBe branch(1).hash.value
        at.imported shouldBe Nil
      case other => fail(s"not the failure at the failing block: $other")
    queued(oldHead) shouldBe true
    queued(branch(1)) shouldBe false
    queued(branch(2)) shouldBe false
  )
