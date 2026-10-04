package com.chipprbots.ethereum.ledger

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.nodebuilder.StdNode
import com.chipprbots.ethereum.testing.Tags.*

class BlockExecutionDiscardSpec extends AnyFlatSpec with Matchers with MockFactory:

  trait Setup:
    val blockchain: BlockchainImpl = mock[BlockchainImpl]
    val reader: BlockchainReader = mock[BlockchainReader]
    val execution = new BlockExecution(
      blockchain,
      reader,
      mock[BlockchainWriter],
      mock[EvmCodeStorage],
      null,
      null
    )
    val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis) // numbers 1, 2, 3

  "BlockExecution.discardUnadoptedState" should "roll back numbers above best, highest first" taggedAs (UnitTest) in new Setup:
    (() => reader.getBestBlockNumber).expects().returning(BigInt(0)).anyNumberOfTimes()
    chain.foreach(b => reader.getCanonicalHashByNumber.expects(b.number.value).returning(Some(b.hash)))
    inSequence {
      blockchain.rollbackBlockState.expects(BigInt(3))
      blockchain.rollbackBlockState.expects(BigInt(2))
      blockchain.rollbackBlockState.expects(BigInt(1))
    }
    execution.discardUnadoptedState(chain)

  it should "never touch a number at or below best" taggedAs UnitTest in new Setup:
    (() => reader.getBestBlockNumber).expects().returning(BigInt(3)).anyNumberOfTimes()
    blockchain.rollbackBlockState.expects(*).never()
    execution.discardUnadoptedState(chain)

  it should "leave a number alone when the canonical mapping names another block (Engine-applied)" taggedAs (
    UnitTest
  ) in new Setup:
    (() => reader.getBestBlockNumber).expects().returning(BigInt(0)).anyNumberOfTimes()
    val engineBlock = BlockHelpers
      .generateChain(1, BlockHelpers.genesis)
      .head
      .copy(header = chain(1).header.copy(extraData = org.apache.pekko.util.ByteString("engine")))
    reader.getCanonicalHashByNumber.expects(BigInt(1)).returning(Some(chain(0).hash))
    reader.getCanonicalHashByNumber.expects(BigInt(2)).returning(Some(engineBlock.hash)) // someone else's block 2
    reader.getCanonicalHashByNumber.expects(BigInt(3)).returning(None) // never saved: the failing block
    blockchain.rollbackBlockState.expects(BigInt(3))
    blockchain.rollbackBlockState.expects(BigInt(1))
    execution.discardUnadoptedState(chain)

  "The startup sweep gate" should "run on a PoW-style node only" taggedAs UnitTest in {
    StdNode.startupSweepAllowed(engineApiEnabled = false, hasTerminalTotalDifficulty = false) shouldBe true
    StdNode.startupSweepAllowed(engineApiEnabled = true, hasTerminalTotalDifficulty = false) shouldBe false
    StdNode.startupSweepAllowed(engineApiEnabled = false, hasTerminalTotalDifficulty = true) shouldBe false
    StdNode.startupSweepAllowed(engineApiEnabled = true, hasTerminalTotalDifficulty = true) shouldBe false
  }
