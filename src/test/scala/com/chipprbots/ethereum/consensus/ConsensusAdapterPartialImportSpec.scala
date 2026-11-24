package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

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
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedDueToMissingNode
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.ImportedPartially
import com.chipprbots.ethereum.blockchain.sync.regular.MovedOff
import com.chipprbots.ethereum.consensus.Consensus.BranchExecutionFailure
import com.chipprbots.ethereum.consensus.Consensus.ConsensusErrorDueToMissingNode
import com.chipprbots.ethereum.consensus.Consensus.ConsensusResult
import com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranchPartially
import com.chipprbots.ethereum.consensus.Consensus.HeadLeftOnSideBlocks
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranchPartially
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
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

    // b1 became the head; b2 failed; b3 stands on it. All three are queued, as a batch a fault of this node's state
    // queued again is before it is fetched again (B-336, B-440); b1 leaves the queue as it becomes the head (B-503)
    val branch: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    val queue: BlockQueue = new BlockQueue(blockchainReader, 100, 100)
    branch.foreach(queue.enqueueBlock(_))
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
    withClue("the prefix is the head's, so it leaves the queue (B-503, B-509): ")(queued(branch.head) shouldBe false)
  )

  // B-338: the prefix imported AND the failing block's error, which the answer used to drop
  it should "drop the failing block and what stands on it, and answer the prefix imported and the block failed, when the block was invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Setup:
    imported(
      ExtendedCurrentBestBranchPartially(prefix, BranchExecutionFailure(Nil, branch(1).hash.value, invalidBlock))
    ) shouldBe ImportedPartially(BlockImportedToTop(prefix), branch(1).hash.value, invalidBlock)
    withClue("the prefix is the head's, so it leaves the queue (B-503, B-509): ")(queued(branch.head) shouldBe false)
    withClue("the failing block's subtree is dropped: ") {
      queued(branch(1)) shouldBe false
      queued(branch(2)) shouldBe false
    }
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
        // and the blocks the head left, which the pool takes back (B-460)
        at.movedOff shouldBe List(oldHead)
      case other => fail(s"not the fault, with the prefix: $other")
    queued(oldHead) shouldBe true
    queued(branch(1)) shouldBe true
    queued(branch(2)) shouldBe true
    withClue("the prefix is the head's, so it leaves the queue (B-503, B-509): ")(queued(branch.head) shouldBe false)
  )

  // B-338: answered as the reorganisation it is, with the blocks it moved off, and as the failing block's failure. Of
  // the blocks moved onto, only the batch's were executed by this call: `side` is a stored block the branch stands on
  it should "queue the old branch again, drop the failing block, and answer the reorganisation and the failure, when the block was invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Reorganised:
    val error: String = s"${Consensus.ReorganiseFailurePrefix}$invalidBlock"
    imported(partially(Nil, error)) shouldBe ImportedPartially(
      ChainReorganised(List(oldHead), List(side, branch.head), weights, branchBlocks = 1),
      branch(1).hash.value,
      error
    )
    queued(oldHead) shouldBe true
    withClue("the prefix is the head's, so it leaves the queue (B-503, B-509): ")(queued(branch.head) shouldBe false)
    withClue("the failing block's subtree is dropped: ") {
      queued(branch(1)) shouldBe false
      queued(branch(2)) shouldBe false
    }
  )

  // B-509: the other arms whose prefix, or side blocks, became the head take them out of the queue too, after the
  // blocks the head left are queued again
  "A batch a fault of this node's state stopped after a prefix became the head" should "take the prefix out of the queue and keep the rest queued" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Setup:
    imported(
      ConsensusStateFault(new StateUnavailable("the head moved under the batch"), prefix, branch.drop(1))
    ) shouldBe a[BlockImportStateFault]
    queued(branch.head) shouldBe false
    queued(branch(1)) shouldBe true
    queued(branch(2)) shouldBe true
  )

  "A batch that met a missing node after a prefix became the head" should "take the prefix out of the queue and keep the rest queued" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Setup:
    val missing = new MissingNodeException(ByteString(Array.fill[Byte](32)(7)))
    imported(ConsensusErrorDueToMissingNode(branch.drop(1), missing, prefix)) shouldBe
      BlockImportFailedDueToMissingNode(missing, prefix)
    queued(branch.head) shouldBe false
    queued(branch(1)) shouldBe true
    queued(branch(2)) shouldBe true
  )

  "A batch whose move back failed after a fault of this node's state" should "take the side blocks the head was left on out of the queue" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Reorganised:
    queue.enqueueBlock(side)
    queued(side) shouldBe true
    imported(
      new HeadLeftOnSideBlocks(
        new StateUnavailable("the move back failed"),
        branch,
        List(BlockData(side, Nil, weights.head))
      )
    ) shouldBe a[BlockImportStateFault]
    queued(side) shouldBe false
    branch.foreach(block => queued(block) shouldBe true)
  )

  // B-460: a fault of this node's state after a reorganisation's prefix became the head. ConsensusImpl queues the blocks
  // the head left again together with the blocks not judged; the answer names the ones the head left, which are not
  // of the branch evaluated, so that the pool takes them back as for a reorganisation that went through.
  "A missing node met after a reorganisation's prefix became the head" should "name the blocks the head left, and be the missing node as before" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Reorganised:
    val reason = new MissingNodeException(branch(1).hash.value)
    val adopted: List[BlockData] = List(BlockData(branch.head, Nil, weights(1)))
    val answer: Any = imported(ConsensusErrorDueToMissingNode(List(oldHead), reason, adopted))
    answer shouldBe BlockImportFailedDueToMissingNode(reason, adopted)
    MovedOff.by(answer.asInstanceOf[BlockImportResult]) shouldBe List(oldHead)
    queued(oldHead) shouldBe true
  )

  it should "name none when the prefix extended the head" taggedAs (UnitTest, ConsensusTest) in (new Setup:
    val reason = new MissingNodeException(branch(1).hash.value)
    val answer: Any = imported(ConsensusErrorDueToMissingNode(Nil, reason, prefix))
    answer shouldBe BlockImportFailedDueToMissingNode(reason, prefix)
    MovedOff.by(answer.asInstanceOf[BlockImportResult]) shouldBe Nil
  )

  "A state fault met after a reorganisation's prefix became the head" should "name the blocks the head left, not the blocks of the branch that were not judged" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Reorganised:
    val fault = new StateUnavailable("the head moved under the branch")
    val adopted: List[BlockData] = List(BlockData(branch.head, Nil, weights(1)))
    val answer: Any = imported(ConsensusStateFault(fault, adopted, List(oldHead) ++ branch.drop(1)))
    answer shouldBe BlockImportStateFault(fault, adopted)
    MovedOff.by(answer.asInstanceOf[BlockImportResult]) shouldBe List(oldHead)
    queued(oldHead) shouldBe true
    queued(branch(1)) shouldBe true
  )

  it should "name none when the prefix extended the head" taggedAs (UnitTest, ConsensusTest) in (new Setup:
    val fault = new StateUnavailable("the head moved under the branch")
    val answer: Any = imported(ConsensusStateFault(fault, prefix, branch.drop(1)))
    answer shouldBe BlockImportStateFault(fault, prefix)
    MovedOff.by(answer.asInstanceOf[BlockImportResult]) shouldBe Nil
  )
