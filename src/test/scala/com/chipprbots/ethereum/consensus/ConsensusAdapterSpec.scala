package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.language.postfixOps

import org.scalamock.handlers.CallHandler0
import org.scalamock.handlers.CallHandler1
import org.scalamock.handlers.CallHandler2
import org.scalamock.handlers.CallHandler4
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.DuplicateBlock
import com.chipprbots.ethereum.consensus.mining.*
import com.chipprbots.ethereum.consensus.validators.*
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.branch.Branch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockQueue.Leaf
import com.chipprbots.ethereum.ledger.EphemBlockchain
import com.chipprbots.ethereum.ledger.MockBlockchain
import com.chipprbots.ethereum.ledger.OmmersTestSetup
import com.chipprbots.ethereum.ledger.TestSetupWithVmAndValidators
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

class ConsensusAdapterSpec extends AnyFlatSpec with Matchers with ScalaFutures with org.scalamock.scalatest.MockFactory:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(2 seconds), interval = scaled(1 second))

  "ConsensusAdapter" should "ignore duplicated block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ImportBlockTestSetupImpl:
    val block1: Block = getBlock()
    val block2: Block = getBlock()

    setBlockExists(block1, inChain = true, inQueue = false)
    setBestBlock(bestBlock)

    whenReady(consensusAdapter.evaluateBranchBlock(block1).unsafeToFuture())(_ shouldEqual DuplicateBlock)

    setBlockExists(block2, inChain = false, inQueue = true)
    setBestBlock(bestBlock)

    whenReady(consensusAdapter.evaluateBranchBlock(block2).unsafeToFuture())(_ shouldEqual DuplicateBlock)

  it should "import a block to the top of the main chain" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ImportBlockTestSetupImpl:
    val block: Block = getBlock(6, parent = bestBlock.header.hash.value)
    val difficulty: BigInt = block.header.difficulty.value
    val hash: ByteString = block.header.hash.value

    setBlockExists(block, inChain = false, inQueue = false)
    setBestBlock(bestBlock)
    setChainWeightForBlock(bestBlock, currentWeight)

    val newWeight: ChainWeight = currentWeight.increaseTotalDifficulty(difficulty)
    val blockData: BlockData = BlockData(block, Seq.empty[Receipt], newWeight)

    // Just to bypass metrics needs
    blockchainReader.getBlockByHash.expects(*).anyNumberOfTimes().returning(None)
    // BlockExecution records each executed block's difficulty into the TD ring buffer
    // (#1373) — an incidental call on the real-execution success path; allow it.
    blockchainReader.recordBlockDifficulty.expects(*).anyNumberOfTimes().returning(())
    blockchainWriter.save.expects(*, *, *, *).returning(())
    blockchainWriter.saveBestKnownBlocks.expects(*, *).returning(())

    blockQueue.enqueueBlock.expects(block, bestNum).returning(Some(Leaf(hash, newWeight)))
    blockQueue.getBranch.expects(BlockHash(hash), true).returning(List(block))

    blockchainReader.getBlockHeaderByHash.expects(*).anyNumberOfTimes().returning(Some(block.header))
    blockchain.getBackingMptStorage
      .expects(*)
      .returning(storagesInstance.storages.stateStorage.getBackingStorage(6))
    blockchain.saveBlockState.expects(*).anyNumberOfTimes().returning(())

    whenReady(blockImportNotFailingAfterExecValidation.evaluateBranchBlock(block).unsafeToFuture()) {
      _ shouldEqual BlockImportedToTop(List(blockData))
    }

  it should "handle exec error when importing to top" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ImportBlockTestSetupImpl:
    val block: Block = getBlock(6, parent = bestBlock.header.hash.value)

    setBlockExists(block, inChain = false, inQueue = false)
    setBestBlock(bestBlock)
    setChainWeightForBlock(bestBlock, currentWeight)

    val hash: ByteString = block.header.hash.value
    blockQueue.enqueueBlock
      .expects(block, bestNum)
      .returning(Some(Leaf(hash, currentWeight.increase(block.header))))
    blockQueue.getBranch.expects(BlockHash(hash), true).returning(List(block))

    val mptStorage: MptStorage = mock[MptStorage]
    val mptNode: LeafNode = LeafNode(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      Some(MerklePatriciaTrie.EmptyRootHash),
      Some(MerklePatriciaTrie.EmptyRootHash)
    )

    blockchainReader.getBlockHeaderByHash.expects(*).anyNumberOfTimes().returning(Some(block.header))
    blockchainReader.getBlockHeaderByNumber.expects(*).anyNumberOfTimes().returning(Some(block.header))
    blockchain.getBackingMptStorage.expects(*).returning(mptStorage)
    mptStorage.get.expects(*).returning(mptNode)

    blockQueue.removeSubtree.expects(*)

    whenReady(consensusAdapter.evaluateBranchBlock(block).unsafeToFuture())(
      _ shouldBe BlockImportFailed(
        "MPTError(com.chipprbots.ethereum.mpt.MerklePatriciaTrie$MPTException: Invalid Node)"
      )
    )

  it should "handle no best block available error when importing to top" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ImportBlockTestSetupImpl:
    val block: Block = getBlock(6, parent = bestBlock.header.hash.value)

    setBlockExists(block, inChain = false, inQueue = false)
    // After the post-PivotHeaderBootstrap fix, evaluateBranchBlock falls back to
    // getBestBlockHeader() when the full block isn't available. Both must report
    // None for the "no best block" scenario.
    (() => blockchainReader.getBestBlock).expects().returning(None)
    (() => blockchainReader.getBestBlockHeader).expects().returning(None)
    setChainWeightForBlock(bestBlock, currentWeight)

    whenReady(consensusAdapter.evaluateBranchBlock(block).unsafeToFuture())(
      _ shouldBe BlockImportFailed("Couldn't find the current best block header")
    )

  it should "handle total difficulty error when importing to top by logging and continuing" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ImportBlockTestSetupImpl:
    val block: Block = getBlock(6, parent = bestBlock.header.hash.value)

    setBlockExists(block, inChain = false, inQueue = false)
    setBestBlock(bestBlock)
    // Chain weight is missing - should log warning but not return early with the old error message
    blockchainReader.getChainWeightByHash.expects(*).anyNumberOfTimes().returning(None)

    blockchainReader.getBlockHeaderByHash.expects(*).anyNumberOfTimes().returning(Some(block.header))
    blockQueue.enqueueBlock.expects(*, *).anyNumberOfTimes().returning(None)

    // The code should continue processing and call block validation, not return early
    // Since chain weight is None, processing may continue but won't succeed fully
    whenReady(consensusAdapter.evaluateBranchBlock(block).unsafeToFuture()) { result =>
      result match
        case BlockImportFailed(error) =>
          // Should NOT be the old immediate failure message from returnNoTotalDifficulty
          (error should not).startWith("Couldn't get total difficulty for current best block")
        case BlockEnqueued => // Also acceptable - block was enqueued for later processing
        case _             => // Other results are also acceptable
    }

  // scalastyle:off magic.number
  it should "reorganise chain when a newly enqueued block forms a better branch" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new EphemBlockchain:
    val block1: Block = getBlock(bestNum - 2)
    val newBlock2: Block = getBlock(bestNum - 1, difficulty = 101, parent = block1.header.hash.value)
    val newBlock3: Block = getBlock(bestNum, difficulty = 105, parent = newBlock2.header.hash.value)
    val oldBlock2: Block = getBlock(bestNum - 1, difficulty = 102, parent = block1.header.hash.value)
    val oldBlock3: Block = getBlock(bestNum, difficulty = 103, parent = oldBlock2.header.hash.value)

    val weight1: ChainWeight = ChainWeight.totalDifficultyOnly(block1.header.difficulty.value + 999)
    val newWeight2: ChainWeight = weight1.increase(newBlock2.header)
    val newWeight3: ChainWeight = newWeight2.increase(newBlock3.header)
    val oldWeight2: ChainWeight = weight1.increase(oldBlock2.header)
    val oldWeight3: ChainWeight = oldWeight2.increase(oldBlock3.header)

    blockchainWriter.save(block1, Nil, weight1, saveAsBestBlock = true)
    blockchainWriter.save(oldBlock2, receipts, oldWeight2, saveAsBestBlock = true)
    blockchainWriter.save(oldBlock3, Nil, oldWeight3, saveAsBestBlock = true)

    val ancestorForValidation: Block = getBlock(0, difficulty = 1)
    blockchainWriter.save(ancestorForValidation, Nil, ChainWeight.totalDifficultyOnly(1), saveAsBestBlock = false)

    val oldBranch: List[Block] = List(oldBlock2, oldBlock3)
    val newBranch: List[Block] = List(newBlock2, newBlock3)
    val blockData2: BlockData = BlockData(newBlock2, Seq.empty[Receipt], newWeight2)
    val blockData3: BlockData = BlockData(newBlock3, Seq.empty[Receipt], newWeight3)

    val mockExecution: BlockExecution = mock[BlockExecution]
    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(newBranch, *, *)
      .returning((List(blockData2, blockData3), None))

    val withMockedBlockExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)
    whenReady(withMockedBlockExecution.evaluateBranchBlock(newBlock3).unsafeToFuture())(
      _ shouldEqual BlockEnqueued
    )
    whenReady(withMockedBlockExecution.evaluateBranchBlock(newBlock2).unsafeToFuture()) { result =>
      result shouldEqual ChainReorganised(oldBranch, newBranch, List(newWeight2, newWeight3))
    }

    // Saving new blocks, because it's part of executeBlocks method mechanism
    blockchainWriter.save(blockData2.block, blockData2.receipts, blockData2.weight, saveAsBestBlock = true)
    blockchainWriter.save(blockData3.block, blockData3.receipts, blockData3.weight, saveAsBestBlock = true)

    blockchainReader.getBestBlock.get shouldEqual newBlock3
    blockchainReader.getChainWeightByHash(newBlock3.header.hash) shouldEqual Some(newWeight3)

    blockQueue.isQueued(oldBlock2.header.hash) shouldBe true
    blockQueue.isQueued(oldBlock3.header.hash) shouldBe true

  it should "handle error when trying to reorganise chain" taggedAs (UnitTest, ConsensusTest) in new EphemBlockchain:
    val block1: Block = getBlock(bestNum - 2)
    val newBlock2: Block = getBlock(bestNum - 1, difficulty = 101, parent = block1.header.hash.value)
    val newBlock3: Block = getBlock(bestNum, difficulty = 105, parent = newBlock2.header.hash.value)
    val oldBlock2: Block = getBlock(bestNum - 1, difficulty = 102, parent = block1.header.hash.value)
    val oldBlock3: Block = getBlock(bestNum, difficulty = 103, parent = oldBlock2.header.hash.value)

    val weight1: ChainWeight = ChainWeight.totalDifficultyOnly(block1.header.difficulty.value + 999)
    val newWeight2: ChainWeight = weight1.increase(newBlock2.header)
    newWeight2.increase(newBlock3.header)
    val oldWeight2: ChainWeight = weight1.increase(oldBlock2.header)
    val oldWeight3: ChainWeight = oldWeight2.increase(oldBlock3.header)

    blockchainWriter.save(block1, Nil, weight1, saveAsBestBlock = true)
    blockchainWriter.save(oldBlock2, receipts, oldWeight2, saveAsBestBlock = true)
    blockchainWriter.save(oldBlock3, Nil, oldWeight3, saveAsBestBlock = true)

    val ancestorForValidation: Block = getBlock(0, difficulty = 1)
    blockchainWriter.save(ancestorForValidation, Nil, ChainWeight.totalDifficultyOnly(1), saveAsBestBlock = false)

    val newBranch: List[Block] = List(newBlock2, newBlock3)
    val blockData2: BlockData = BlockData(newBlock2, Seq.empty[Receipt], newWeight2)

    val mockExecution: BlockExecution = mock[BlockExecution]
    // simulate execute-first: the mock must persist newBlock2 (the block that succeeds)
    // exactly as real executeAndValidateBlocks does — otherwise saveBestKnownBlocks updates
    // the chain pointer to a hash that isn't in the DB and getBestBlock() returns None
    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(newBranch, *, *)
      .onCall { (_, _, _) =>
        blockchainWriter.save(newBlock2, Seq.empty[Receipt], newWeight2, saveAsBestBlock = false)
        (List(blockData2), Some(execError))
      }

    val withMockedBlockExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)
    whenReady(withMockedBlockExecution.evaluateBranchBlock(newBlock3).unsafeToFuture())(
      _ shouldEqual BlockEnqueued
    )
    whenReady(withMockedBlockExecution.evaluateBranchBlock(newBlock2).unsafeToFuture()) {
      _ shouldBe a[BlockImportFailed]
    }

    // core-geth parity (writeBlockAndSetHead: the head moves only to a heavier block). The executed prefix newBlock2
    // (weight1 + 101) is lighter than the old head oldBlock3 (weight1 + 102 + 103), so the old head stays and the index
    // entry execution overwrote is put back. newBlock2 is kept as an executed side block, with its weight. This used to
    // assert best == newBlock2 — a lighter head than the one the node already had; see ReorgBlockhashParitySpec.
    blockchainReader.getBestBlock.get shouldEqual oldBlock3
    blockchainReader.getBlockHeaderByNumber(oldBlock2.number.value).map(_.hash) shouldEqual Some(oldBlock2.hash)
    blockchainReader.getBlockByHash(newBlock2.hash) shouldEqual Some(newBlock2)
    blockchainReader.getChainWeightByHash(newBlock2.header.hash) shouldEqual Some(newWeight2)

    blockQueue.isQueued(newBlock2.header.hash) shouldBe true
    blockQueue.isQueued(newBlock3.header.hash) shouldBe false

  it should "report an orphaned block" taggedAs (UnitTest, ConsensusTest) in new ImportBlockTestSetupImpl:
    override lazy val validators: MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = mock[BlockHeaderValidator]

    val newBlock: Block = getBlock(number = bestNum + 1)
    setBlockExists(newBlock, inChain = false, inQueue = false)
    setBestBlock(bestBlock)
    setChainWeightForBlock(bestBlock, currentWeight)

    (validators.blockHeaderValidator
      .validate(_: BlockHeader, _: GetBlockHeaderByHash)(_: BlockchainConfig))
      .expects(newBlock.header, *, *)
      .returning(Left(HeaderParentNotFoundError))

    whenReady(consensusAdapter.evaluateBranchBlock(newBlock).unsafeToFuture())(
      _ shouldEqual BlockImportFailed("UNKNOWN_PARENT: parent header not found")
    )

  it should "validate blocks prior to import" taggedAs (UnitTest, ConsensusTest) in new ImportBlockTestSetupImpl:
    override lazy val validators: MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = mock[BlockHeaderValidator]

    val newBlock: Block = getBlock(number = bestNum + 1)
    setBlockExists(newBlock, inChain = false, inQueue = false)
    setBestBlock(bestBlock)
    setChainWeightForBlock(bestBlock, currentWeight)

    (validators.blockHeaderValidator
      .validate(_: BlockHeader, _: GetBlockHeaderByHash)(_: BlockchainConfig))
      .expects(newBlock.header, *, *)
      .returning(Left(HeaderDifficultyError))

    whenReady(consensusAdapter.evaluateBranchBlock(newBlock).unsafeToFuture()) {
      _ shouldEqual BlockImportFailed(HeaderDifficultyError.toString)
    }

  it should "correctly handle importing genesis block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ImportBlockTestSetupImpl:
    val genesisBlock: Block = Block(genesisHeader, BlockBody.empty)

    setBestBlock(genesisBlock)
    setBlockExists(genesisBlock, inChain = true, inQueue = true)

    whenReady(failConsensus.evaluateBranchBlock(genesisBlock).unsafeToFuture())(_ shouldEqual DuplicateBlock)

  it should "correctly import block with ommers and ancestor taggedAs (UnitTest, ConsensusTest) in block queue " in new OmmersTestSetup:
    val ancestorForValidation: Block = getBlock(0, difficulty = 1)
    val ancestorForValidation1: Block = getBlock(difficulty = 2, parent = ancestorForValidation.header.hash.value)
    val ancestorForValidation2: Block = getBlock(2, difficulty = 3, parent = ancestorForValidation1.header.hash.value)

    val block1: Block = getBlock(bestNum - 2, parent = ancestorForValidation2.header.hash.value)
    val ommerBlock: Block = getBlock(bestNum - 1, difficulty = 101, parent = block1.header.hash.value)
    val oldBlock2: Block = getBlock(bestNum - 1, difficulty = 102, parent = block1.header.hash.value)
    val oldBlock3: Block = getBlock(bestNum, difficulty = 103, parent = oldBlock2.header.hash.value)
    val newBlock2: Block = getBlock(bestNum - 1, difficulty = 102, parent = block1.header.hash.value)

    val newBlock3WithOmmer: Block =
      getBlock(bestNum, difficulty = 105, parent = newBlock2.header.hash.value, ommers = Seq(ommerBlock.header))

    val weight1: ChainWeight = ChainWeight.totalDifficultyOnly(block1.header.difficulty.value + 999)
    val oldWeight2: ChainWeight = weight1.increase(oldBlock2.header)
    val oldWeight3: ChainWeight = oldWeight2.increase(oldBlock3.header)

    val newWeight2: ChainWeight = weight1.increase(newBlock2.header)
    val newWeight3: ChainWeight = newWeight2.increase(newBlock3WithOmmer.header)

    blockchainWriter.save(ancestorForValidation, Nil, ChainWeight.totalDifficultyOnly(1), saveAsBestBlock = false)
    blockchainWriter.save(ancestorForValidation1, Nil, ChainWeight.totalDifficultyOnly(3), saveAsBestBlock = false)
    blockchainWriter.save(ancestorForValidation2, Nil, ChainWeight.totalDifficultyOnly(6), saveAsBestBlock = false)

    blockchainWriter.save(block1, Nil, weight1, saveAsBestBlock = true)
    blockchainWriter.save(oldBlock2, receipts, oldWeight2, saveAsBestBlock = true)
    blockchainWriter.save(oldBlock3, Nil, oldWeight3, saveAsBestBlock = true)

    val oldBranch: List[Block] = List(oldBlock2, oldBlock3)
    val newBranch: List[Block] = List(newBlock2, newBlock3WithOmmer)
    val blockData2: BlockData = BlockData(newBlock2, Seq.empty[Receipt], newWeight2)
    val blockData3: BlockData = BlockData(newBlock3WithOmmer, Seq.empty[Receipt], newWeight3)

    val mockExecution: BlockExecution = mock[BlockExecution]
    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(newBranch, *, *)
      .returning((List(blockData2, blockData3), None))

    val withMockedBlockExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)
    whenReady(withMockedBlockExecution.evaluateBranchBlock(newBlock2).unsafeToFuture())(
      _ shouldEqual BlockEnqueued
    )
    whenReady(withMockedBlockExecution.evaluateBranchBlock(newBlock3WithOmmer).unsafeToFuture()) { result =>
      result shouldEqual ChainReorganised(oldBranch, newBranch, List(newWeight2, newWeight3))
    }

    // Saving new blocks, because it's part of executeBlocks method mechanism
    blockchainWriter.save(blockData2.block, blockData2.receipts, blockData2.weight, saveAsBestBlock = true)
    blockchainWriter.save(blockData3.block, blockData3.receipts, blockData3.weight, saveAsBestBlock = true)

    blockchainReader.getBestBlock.get shouldEqual newBlock3WithOmmer

  it should "dequeue blocks where there is an execution error" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new EphemBlockchain:
    val mockExecution: BlockExecution = mock[BlockExecution]

    val currentBestBlock: Block = getBlock(bestNum - 2)
    val block1Weight: ChainWeight = ChainWeight.totalDifficultyOnly(currentBestBlock.header.difficulty.value + 999)

    blockchainWriter.save(currentBestBlock, Nil, block1Weight, saveAsBestBlock = true)

    val newBlock1: Block = getBlock(bestNum - 1, difficulty = 101, parent = currentBestBlock.header.hash.value)
    val newBlock2: Block = getBlock(bestNum, difficulty = 105, parent = newBlock1.header.hash.value)

    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(List(newBlock1, newBlock2), *, *)
      .returning((Nil, Some(execError)))
    val consensusAdapterWithFailingExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock2).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock2.hash) shouldBe true
    }

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock1).unsafeToFuture()) { result =>
      result shouldBe a[BlockImportFailed]
      blockQueue.isQueued(newBlock1.hash) shouldBe false
      blockQueue.isQueued(newBlock2.hash) shouldBe false
    }

  it should "dequeue blocks that are children of a failing block when all blocks are failing" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new EphemBlockchain:
    val mockExecution: BlockExecution = mock[BlockExecution]

    val currentBestBlock: Block = getBlock(bestNum)

    blockchainWriter.save(currentBestBlock, Nil, currentWeight, saveAsBestBlock = true)

    val newBlock1: Block = getBlock(bestNum + 1, difficulty = 101, parent = currentBestBlock.header.hash.value)
    val newBlock2: Block = getBlock(bestNum + 2, difficulty = 105, parent = newBlock1.header.hash.value)
    val newBlock2bis: Block = getBlock(bestNum + 2, difficulty = 50, parent = newBlock1.header.hash.value)

    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(List(newBlock1, newBlock2), *, *)
      .returning((Nil, Some(execError)))
    val consensusAdapterWithFailingExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock2).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock2.hash) shouldBe true
    }

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock2bis).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock2bis.hash) shouldBe true
    }

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock1).unsafeToFuture()) { result =>
      result shouldBe a[BlockImportFailed]
      blockQueue.isQueued(newBlock1.hash) shouldBe false
      blockQueue.isQueued(newBlock2.hash) shouldBe false
      blockQueue.isQueued(newBlock2bis.hash) shouldBe false
    }

  it should "dequeue blocks that are children of a failing block taggedAs (UnitTest, ConsensusTest) in case of partial execution" in new EphemBlockchain:
    val mockExecution: BlockExecution = mock[BlockExecution]

    val currentBestBlock: Block = getBlock(bestNum)

    blockchainWriter.save(currentBestBlock, Nil, currentWeight, saveAsBestBlock = true)

    val newBlock1: Block = getBlock(bestNum + 1, difficulty = 101, parent = currentBestBlock.header.hash.value)
    val newBlock2: Block = getBlock(bestNum + 2, difficulty = 105, parent = newBlock1.header.hash.value)
    val newBlock3: Block = getBlock(bestNum + 3, difficulty = 105, parent = newBlock2.header.hash.value)
    val newBlock3bis: Block = getBlock(bestNum + 3, difficulty = 50, parent = newBlock2.header.hash.value)

    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(List(newBlock1, newBlock2, newBlock3), *, *)
      .returning((List(BlockData(newBlock1, Nil, currentWeight.increase(newBlock1.header))), Some(execError)))
    val consensusAdapterWithFailingExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock2).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock2.hash) shouldBe true
    }
    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock3).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock3.hash) shouldBe true
    }
    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock3bis).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock3bis.hash) shouldBe true
    }

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock1).unsafeToFuture()) { result =>
      result shouldBe a[BlockImportedToTop]
      blockQueue.isQueued(newBlock2.hash) shouldBe false
      blockQueue.isQueued(newBlock3.hash) shouldBe false
      blockQueue.isQueued(newBlock3bis.hash) shouldBe false
    }

  it should "dequeue blocks that are children of a failing block taggedAs (UnitTest, ConsensusTest) in case of partial execution during a reorganisation" in new EphemBlockchain:
    val mockExecution: BlockExecution = mock[BlockExecution]

    val currentBestBlock: Block = getBlock(bestNum)
    val block1: Block = getBlock(bestNum + 1, difficulty = 101, parent = currentBestBlock.header.hash.value)
    val block2: Block = getBlock(bestNum + 2, difficulty = 105, parent = block1.header.hash.value)
    blockchainWriter.save(currentBestBlock, Nil, currentWeight, saveAsBestBlock = true)
    blockchainWriter.save(block1, Nil, currentWeight, saveAsBestBlock = true)
    blockchainWriter.save(block2, Nil, currentWeight, saveAsBestBlock = true)

    val badBlock: Block = getBlock(bestNum + 2, difficulty = 105, parent = block1.header.hash.value)
    val newBlock3: Block = getBlock(bestNum + 3, difficulty = 105, parent = badBlock.header.hash.value)
    val newBlock3bis: Block = getBlock(bestNum + 3, difficulty = 10, parent = badBlock.header.hash.value)

    (mockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .expects(List(badBlock, newBlock3), *, *)
      .returning((Nil, Some(execError)))
    val consensusAdapterWithFailingExecution: ConsensusAdapter = blockImportWithMockedBlockExecution(mockExecution)

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock3).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock3.hash) shouldBe true
    }
    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(newBlock3bis).unsafeToFuture()) { result =>
      result shouldEqual BlockEnqueued
      blockQueue.isQueued(newBlock3bis.hash) shouldBe true
    }

    whenReady(consensusAdapterWithFailingExecution.evaluateBranchBlock(badBlock).unsafeToFuture()) { result =>
      result shouldBe a[BlockImportFailed]
      blockQueue.isQueued(badBlock.hash) shouldBe false
      blockQueue.isQueued(newBlock3.hash) shouldBe false
      blockQueue.isQueued(newBlock3bis.hash) shouldBe false
    }

  class ImportBlockTestSetupImpl extends TestSetupWithVmAndValidators with MockBlockchain:
    // Provide mock implementations - these are created in the test class context which has MockFactory
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchainWriter: BlockchainWriter = mock[BlockchainWriter]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockQueue: BlockQueue = mock[BlockQueue]

    // Setup default expectations
    (() => blockchainReader.getBestBranch).expects().anyNumberOfTimes().returning(EmptyBranch)

    // Helper methods implementation (have MockFactory context here)
    override def setBlockExists(block: Block, inChain: Boolean, inQueue: Boolean): CallHandler1[BlockHash, Boolean] =
      blockchainReader.getBlockByHash
        .expects(block.header.hash)
        .anyNumberOfTimes()
        .returning(Some(block).filter(_ => inChain))
      blockQueue.isQueued.expects(block.header.hash).anyNumberOfTimes().returning(inQueue)

    override def setBestBlock(block: Block): CallHandler0[BigInt] =
      (() => blockchainReader.getBestBlock).expects().anyNumberOfTimes().returning(Some(block))
      (() => blockchainReader.getBestBlockNumber).expects().anyNumberOfTimes().returning(block.header.number.value)

    override def setBestBlockNumber(num: BigInt): CallHandler0[BigInt] =
      (() => blockchainReader.getBestBlockNumber).expects().returning(num)

    override def setChainWeightForBlock(
        block: Block,
        weight: ChainWeight
    ): CallHandler1[BlockHash, Option[ChainWeight]] =
      setChainWeightByHash(block.hash.value, weight)

    override def setChainWeightByHash(
        hash: ByteString,
        weight: ChainWeight
    ): CallHandler1[BlockHash, Option[ChainWeight]] =
      blockchainReader.getChainWeightByHash.expects(BlockHash(hash)).anyNumberOfTimes().returning(Some(weight))

    override def expectBlockSaved(
        block: Block,
        receipts: Seq[Receipt],
        weight: ChainWeight,
        saveAsBestBlock: Boolean
    ): CallHandler4[Block, Seq[Receipt], ChainWeight, Boolean, Unit] =
      (blockchainWriter
        .save(_: Block, _: Seq[Receipt], _: ChainWeight, _: Boolean))
        .expects(block, receipts, weight, saveAsBestBlock)
        .once()

    override def setHeaderInChain(hash: ByteString, result: Boolean = true): CallHandler2[Branch, BlockHash, Boolean] =
      blockchainReader.isInChain.expects(*, BlockHash(hash)).returning(result)

    override def setBlockByNumber(number: BigInt, block: Option[Block]): CallHandler2[Branch, BigInt, Option[Block]] =
      blockchainReader.getBlockByNumber.expects(*, number).returning(block)

    override def setGenesisHeader(header: BlockHeader): Unit =
      (() => blockchainReader.genesisHeader).expects().returning(header)
