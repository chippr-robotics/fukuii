package com.chipprbots.ethereum.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.testing.Tags._

/** Tests for BlockchainWriter.setCanonicalChainHead — the SYNC-FORK rollback mechanism (008c).
  *
  * Verifies: number→hash entries removed above target, best-block pointer updated, headers still accessible by hash
  * (soft delete only), no-op when already at or below target.
  */
class BlockchainWriterSetHeadSpec extends AnyFlatSpec with Matchers {

  "BlockchainWriter.setCanonicalChainHead" should "remove number→hash entries above the target block" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val chain = BlockHelpers.generateChain(5, BlockHelpers.genesis)
    chain.foreach { b =>
      blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number), saveAsBestBlock = true)
    }

    val targetBlock = chain(1) // block 2 (0-indexed)
    val currentBest = chain.last.number

    blockchainWriter.setCanonicalChainHead(targetBlock.number, targetBlock.hash, currentBest)

    // Blocks above target must no longer be canonical
    blockchainReader.getBlockHeaderByNumber(targetBlock.number + 1) shouldBe None
    blockchainReader.getBlockHeaderByNumber(chain.last.number) shouldBe None

    // Target itself is still the canonical head
    blockchainReader.getBestBlockNumber() shouldBe targetBlock.number
  }

  it should "update the best-block pointer to the target block" taggedAs (UnitTest, StateTest) in
    new EphemBlockchainTestSetup {
      val chain = BlockHelpers.generateChain(4, BlockHelpers.genesis)
      chain.foreach { b =>
        blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number), saveAsBestBlock = true)
      }

      val target = chain(0) // block 1
      blockchainWriter.setCanonicalChainHead(target.number, target.hash, chain.last.number)

      blockchainReader.getBestBlockNumber() shouldBe target.number
    }

  it should "leave block headers accessible by hash (soft delete — headers are not removed)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    chain.foreach { b =>
      blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number), saveAsBestBlock = true)
    }

    val target = chain.head
    val removed = chain.last
    blockchainWriter.setCanonicalChainHead(target.number, target.hash, chain.last.number)

    // number→hash mapping is gone for the removed block
    blockchainReader.getBlockHeaderByNumber(removed.number) shouldBe None

    // but the header is still retrievable by its hash
    blockchainReader.getBlockHeaderByHash(removed.hash) shouldBe Some(removed.header)
  }

  it should "be a no-op when currentBest equals targetNumber" taggedAs (UnitTest, StateTest) in
    new EphemBlockchainTestSetup {
      val chain = BlockHelpers.generateChain(3, BlockHelpers.genesis)
      chain.foreach { b =>
        blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number), saveAsBestBlock = true)
      }

      val best = chain.last
      // currentBest == targetNumber → no-op
      blockchainWriter.setCanonicalChainHead(best.number, best.hash, best.number)

      blockchainReader.getBestBlockNumber() shouldBe best.number
      blockchainReader.getBlockHeaderByNumber(best.number) shouldBe Some(best.header)
    }

  it should "be a no-op when currentBest is less than targetNumber" taggedAs (UnitTest, StateTest) in
    new EphemBlockchainTestSetup {
      val chain = BlockHelpers.generateChain(2, BlockHelpers.genesis)
      chain.foreach { b =>
        blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number), saveAsBestBlock = true)
      }

      val best = chain.last
      // Pass currentBest lower than targetNumber — guard must prevent any write
      blockchainWriter.setCanonicalChainHead(best.number + 5, best.hash, best.number)

      blockchainReader.getBestBlockNumber() shouldBe best.number
    }

  it should "remove all intermediate number→hash entries between target and currentBest" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val chain = BlockHelpers.generateChain(6, BlockHelpers.genesis)
    chain.foreach { b =>
      blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number), saveAsBestBlock = true)
    }

    val target = chain(1) // block 2
    val currentBest = chain.last.number
    blockchainWriter.setCanonicalChainHead(target.number, target.hash, currentBest)

    // Every block above target should have its number→hash mapping removed
    (target.number + 1 to currentBest).foreach { n =>
      blockchainReader.getBlockHeaderByNumber(n) shouldBe None
    }
  }
}
