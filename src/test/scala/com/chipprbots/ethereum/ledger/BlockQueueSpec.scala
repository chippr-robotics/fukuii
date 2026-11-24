package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalamock.handlers.CallHandler0
import org.scalamock.handlers.CallHandler1
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.ledger.BlockQueue.Leaf
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

class BlockQueueSpec extends AnyFlatSpec with Matchers with MockFactory:

  "BlockQueue" should "ignore block if it's already in the queue" taggedAs (UnitTest, StateTest) in new TestConfig:
    val block: Block = getBlock(1)
    val parentWeight = ChainWeight.zero
    setBestBlockNumber(1).twice()
    setChainWeightForParent(block, Some(parentWeight))

    blockQueue.enqueueBlock(block) shouldEqual Some(Leaf(block.header.hash.value, parentWeight.increase(block.header)))
    blockQueue.enqueueBlock(block) shouldEqual None
    blockQueue.isQueued(block.header.hash) shouldBe true

  it should "ignore blocks outside of range" taggedAs (UnitTest, StateTest) in new TestConfig:
    val block1: Block = getBlock(1)
    val block30: Block = getBlock(30)
    setBestBlockNumber(15).twice()

    blockQueue.enqueueBlock(block1)
    blockQueue.isQueued(block1.header.hash) shouldBe false

    blockQueue.enqueueBlock(block30)
    blockQueue.isQueued(block30.header.hash) shouldBe false

  it should "remove the blocks that fall out of range" taggedAs (UnitTest, StateTest) in new TestConfig:
    val block1: Block = getBlock(1)
    setBestBlockNumber(1)
    setChainWeightForParent(block1)

    blockQueue.enqueueBlock(block1)
    blockQueue.isQueued(block1.header.hash) shouldBe true

    val block20: Block = getBlock(20)
    setBestBlockNumber(20)
    setChainWeightForParent(block20)

    blockQueue.enqueueBlock(block20)
    blockQueue.isQueued(block20.header.hash) shouldBe true
    blockQueue.isQueued(block1.header.hash) shouldBe false

  it should "enqueue a block with parent on the main chain updating its total difficulty" taggedAs (
    UnitTest,
    StateTest
  ) in new TestConfig:
    val block1: Block = getBlock(1, 13)
    val parentWeight: ChainWeight = ChainWeight.totalDifficultyOnly(42)
    setBestBlockNumber(1)
    setChainWeightForParent(block1, Some(parentWeight))

    blockQueue.enqueueBlock(block1) shouldEqual Some(
      Leaf(block1.header.hash.value, parentWeight.increase(block1.header))
    )

  it should "enqueue a block with queued ancestors rooted to the main chain updating its total difficulty" taggedAs (
    UnitTest,
    StateTest
  ) in new TestConfig:
    val block1: Block = getBlock(1, 101)
    val block2a: Block = getBlock(2, 102, block1.header.hash.value)
    val block2b: Block = getBlock(2, 99, block1.header.hash.value)
    val block3: Block = getBlock(3, 103, block2a.header.hash.value)

    val parentWeight: ChainWeight = ChainWeight.totalDifficultyOnly(42)

    setBestBlockNumber(1).anyNumberOfTimes()
    setChainWeightForParent(block1, Some(parentWeight))
    setChainWeightForParent(block2a, None)
    setChainWeightForParent(block2b, None)
    setChainWeightForParent(block3, None)

    blockQueue.enqueueBlock(block1)
    blockQueue.enqueueBlock(block2a)
    blockQueue.enqueueBlock(block2b)

    val expectedWeight: ChainWeight = List(block1, block2a, block3).map(_.header).foldLeft(parentWeight)(_.increase(_))
    blockQueue.enqueueBlock(block3) shouldEqual Some(Leaf(block3.header.hash.value, expectedWeight))

  it should "enqueue an orphaned block" in new TestConfig:
    val block1: Block = getBlock(1)
    setBestBlockNumber(1)
    setChainWeightForParent(block1)

    blockQueue.enqueueBlock(block1) shouldBe None
    blockQueue.isQueued(block1.header.hash) shouldBe true

  it should "remove a branch from a leaf up to the first shared ancestor" in new TestConfig:
    val block1: Block = getBlock(1)
    val block2a: Block = getBlock(2, parent = block1.header.hash.value)
    val block2b: Block = getBlock(2, parent = block1.header.hash.value)
    val block3: Block = getBlock(3, parent = block2a.header.hash.value)

    setBestBlockNumber(1).anyNumberOfTimes()
    setChainWeightForParent(block1)
    setChainWeightForParent(block2a)
    setChainWeightForParent(block2b)
    setChainWeightForParent(block3)

    blockQueue.enqueueBlock(block1)
    blockQueue.enqueueBlock(block2a)
    blockQueue.enqueueBlock(block2b)
    blockQueue.enqueueBlock(block3)

    blockQueue.getBranch(block3.header.hash, dequeue = true) shouldEqual List(block1, block2a, block3)

    blockQueue.isQueued(block3.header.hash) shouldBe false
    blockQueue.isQueued(block2a.header.hash) shouldBe false
    blockQueue.isQueued(block2b.header.hash) shouldBe true
    blockQueue.isQueued(block1.header.hash) shouldBe true

  it should "remove a whole subtree down from an ancestor to all its leaves" in new TestConfig:
    val block1a: Block = getBlock(1)
    val block1b: Block = getBlock(1)
    val block2a: Block = getBlock(2, parent = block1a.header.hash.value)
    val block2b: Block = getBlock(2, parent = block1a.header.hash.value)
    val block3: Block = getBlock(3, parent = block2a.header.hash.value)

    setBestBlockNumber(1).anyNumberOfTimes()
    setChainWeightForParent(block1a)
    setChainWeightForParent(block1b)
    setChainWeightForParent(block2a)
    setChainWeightForParent(block2b)
    setChainWeightForParent(block3)

    blockQueue.enqueueBlock(block1a)
    blockQueue.enqueueBlock(block1b)
    blockQueue.enqueueBlock(block2a)
    blockQueue.enqueueBlock(block2b)
    blockQueue.enqueueBlock(block3)

    blockQueue.isQueued(block3.header.hash) shouldBe true
    blockQueue.isQueued(block2a.header.hash) shouldBe true
    blockQueue.isQueued(block2b.header.hash) shouldBe true
    blockQueue.isQueued(block1a.header.hash) shouldBe true
    blockQueue.isQueued(block1b.header.hash) shouldBe true

    blockQueue.removeSubtree(block1a.header.hash)

    blockQueue.isQueued(block3.header.hash) shouldBe false
    blockQueue.isQueued(block2a.header.hash) shouldBe false
    blockQueue.isQueued(block2b.header.hash) shouldBe false
    blockQueue.isQueued(block1a.header.hash) shouldBe false
    blockQueue.isQueued(block1b.header.hash) shouldBe true

  // B-503: blocks that became the head's leave the queue, and only they: what hangs from them stays queued, with its
  // weight, and the branch of a block that hangs from it is resolved from it, not from the blocks of the head's chain
  it should "remove the blocks that became the head's, and keep what hangs from them rooted" taggedAs (
    UnitTest,
    StateTest
  ) in new TestConfig:
    val block1: Block = getBlock(1, 101)
    val block2a: Block = getBlock(2, 102, block1.header.hash.value)
    val block2b: Block = getBlock(2, 99, block1.header.hash.value)
    val block3: Block = getBlock(3, 103, block2a.header.hash.value)
    val block4: Block = getBlock(4, 104, block3.header.hash.value)
    val parentWeight: ChainWeight = ChainWeight.totalDifficultyOnly(42)

    setBestBlockNumber(1).anyNumberOfTimes()
    setChainWeightForParent(block1, Some(parentWeight))
    setChainWeightForParent(block2a, None)
    setChainWeightForParent(block2b, None)
    setChainWeightForParent(block3, None)
    setChainWeightForParent(block4, None)

    List(block1, block2a, block2b, block3).foreach(blockQueue.enqueueBlock(_))
    val weight3: Option[ChainWeight] = blockQueue.getChainWeightByHash(block3.header.hash)
    weight3 shouldBe Some(List(block1, block2a, block3).map(_.header).foldLeft(parentWeight)(_.increase(_)))

    blockQueue.removeImported(List(block1, block2a, getBlock(5)).map(_.header.hash))

    blockQueue.isQueued(block1.header.hash) shouldBe false
    blockQueue.isQueued(block2a.header.hash) shouldBe false
    blockQueue.isQueued(block2b.header.hash) shouldBe true
    blockQueue.isQueued(block3.header.hash) shouldBe true
    blockQueue.getChainWeightByHash(block3.header.hash) shouldBe weight3
    blockQueue.enqueueBlock(block4) shouldEqual Some(
      Leaf(block4.header.hash.value, weight3.get.increase(block4.header))
    )
    blockQueue.getBranch(block4.header.hash, dequeue = true) shouldEqual List(block3, block4)
    blockQueue.isQueued(block3.header.hash) shouldBe false
    blockQueue.isQueued(block2b.header.hash) shouldBe true

  // B-509: a block queued with no weight, because its queued parent had none, is weighed from the parent's stored
  // weight when the parent becomes the head's, and what hangs from it too: left weightless it is rooted on nothing, and
  // a block that hangs from it is never resolved as a branch
  it should "weigh a weightless block from its stored parent when the parent becomes the head's" taggedAs (
    UnitTest,
    StateTest
  ) in new TestConfig:
    val block1: Block = getBlock(1, 101)
    val block2: Block = getBlock(2, 102, block1.header.hash.value)
    val block3: Block = getBlock(3, 103, block2.header.hash.value)
    val block4: Block = getBlock(4, 104, block3.header.hash.value)
    val storedWeight: ChainWeight = ChainWeight.totalDifficultyOnly(4242)

    setBestBlockNumber(1).anyNumberOfTimes()
    // queued while block1's parent was unknown: the three are weightless
    setChainWeightForParent(block1, None)
    setChainWeightForParent(block2, None)
    setChainWeightForParent(block3, None)
    List(block1, block2, block3).foreach(blockQueue.enqueueBlock(_))
    blockQueue.getChainWeightByHash(block2.header.hash) shouldBe None

    // block1 became the head's: the store holds its weight now
    blockchainReader.getChainWeightByHash.expects(block1.header.hash).returning(Some(storedWeight))
    blockQueue.removeImported(List(block1.header.hash))

    val weight2: ChainWeight = storedWeight.increase(block2.header)
    val weight3: ChainWeight = weight2.increase(block3.header)
    blockQueue.getChainWeightByHash(block2.header.hash) shouldBe Some(weight2)
    blockQueue.getChainWeightByHash(block3.header.hash) shouldBe Some(weight3)
    setChainWeightForParent(block4, None)
    blockQueue.enqueueBlock(block4) shouldEqual Some(Leaf(block4.header.hash.value, weight3.increase(block4.header)))
    blockQueue.getBranch(block4.header.hash, dequeue = true) shouldEqual List(block2, block3, block4)

  it should "weigh a weightless block from its stored parent when the parent, never queued, becomes the head's" taggedAs (
    UnitTest,
    StateTest
  ) in new TestConfig:
    // block1 is fetched in a batch, never queued: block2 was announced ahead of it, and queued weightless
    val block1: Block = getBlock(1, 101)
    val block2: Block = getBlock(2, 102, block1.header.hash.value)
    val storedWeight: ChainWeight = ChainWeight.totalDifficultyOnly(4242)

    setBestBlockNumber(1).anyNumberOfTimes()
    setChainWeightForParent(block2, None)
    blockQueue.enqueueBlock(block2) shouldBe None

    blockchainReader.getChainWeightByHash.expects(block1.header.hash).returning(Some(storedWeight))
    blockQueue.removeImported(List(block1.header.hash))

    blockQueue.getChainWeightByHash(block2.header.hash) shouldBe Some(storedWeight.increase(block2.header))

  it should "leave a weightless block weightless when the store holds no weight for the parent that left" taggedAs (
    UnitTest,
    StateTest
  ) in new TestConfig:
    val block1: Block = getBlock(1, 101)
    val block2: Block = getBlock(2, 102, block1.header.hash.value)

    setBestBlockNumber(1).anyNumberOfTimes()
    setChainWeightForParent(block1, None)
    setChainWeightForParent(block2, None)
    List(block1, block2).foreach(blockQueue.enqueueBlock(_))

    blockchainReader.getChainWeightByHash.expects(block1.header.hash).returning(None)
    blockQueue.removeImported(List(block1.header.hash))

    blockQueue.isQueued(block2.header.hash) shouldBe true
    blockQueue.getChainWeightByHash(block2.header.hash) shouldBe None

  trait TestConfig:
    val syncConfig: SyncConfig =
      SyncConfig(Config.config).copy(maxQueuedBlockNumberAhead = 10, maxQueuedBlockNumberBehind = 10)
    val blockchainReader: BlockchainReader = mock[BlockchainReader]
    val blockchain: BlockchainImpl = mock[BlockchainImpl]
    val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)

    def setBestBlockNumber(n: BigInt): CallHandler0[BigInt] =
      (() => blockchainReader.getBestBlockNumber).expects().returning(n)

    def setChainWeightForParent(
        block: Block,
        weight: Option[ChainWeight] = None
    ): CallHandler1[BlockHash, Option[ChainWeight]] =
      blockchainReader.getChainWeightByHash.expects(block.header.parentHash).returning(weight)

    def randomHash(): ByteString =
      ObjectGenerators.byteStringOfLengthNGen(32).sample.get

    val defaultHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      difficulty = Difficulty(1000000),
      number = BlockNumber(1),
      gasLimit = GasAmount(1000000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(0)
    )

    def getBlock(
        number: BigInt,
        difficulty: BigInt = 1000000,
        parent: ByteString = randomHash(),
        salt: ByteString = randomHash()
    ): Block =
      Block(
        defaultHeader
          .copy(
            parentHash = BlockHash(parent),
            difficulty = Difficulty(difficulty),
            number = BlockNumber(number),
            extraData = salt
          ),
        BlockBody.empty
      )
