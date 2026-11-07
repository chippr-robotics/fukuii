package com.chipprbots.ethereum.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.testing.Tags.*

/** Tests for BlockchainWriter.setCanonicalChainHead — the SYNC-FORK rollback mechanism (008c).
  *
  * Verifies: number→hash entries removed above target, best-block pointer updated, headers still accessible by hash
  * (soft delete only), no-op when already at or below target.
  */
class BlockchainWriterSetHeadSpec extends AnyFlatSpec with Matchers:

  "BlockchainWriter.setCanonicalChainHead" should "remove number→hash entries above the target block" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup:
    val chain: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
    chain.foreach { b =>
      blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = true)
    }

    val targetBlock: Block = chain(1) // block 2 (0-indexed)
    val currentBest = chain.last.number

    blockchainWriter.setCanonicalChainHead(targetBlock.number.value, targetBlock.hash, currentBest.value)

    // Blocks above target must no longer be canonical
    blockchainReader.getBlockHeaderByNumber(targetBlock.number.value + 1) shouldBe None
    blockchainReader.getBlockHeaderByNumber(chain.last.number.value) shouldBe None

    // Target itself is still the canonical head
    blockchainReader.getBestBlockNumber shouldBe targetBlock.number.value

  it should "update the best-block pointer to the target block" taggedAs (UnitTest, StateTest) in
    (new EphemBlockchainTestSetup:
      val chain: List[Block] = BlockHelpers.generateChain(4, BlockHelpers.genesis)
      chain.foreach { b =>
        blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = true)
      }

      val target: Block = chain(0) // block 1
      blockchainWriter.setCanonicalChainHead(target.number.value, target.hash, chain.last.number.value)

      blockchainReader.getBestBlockNumber shouldBe target.number.value
    )

  it should "leave block headers accessible by hash (soft delete — headers are not removed)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup:
    val chain: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    chain.foreach { b =>
      blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = true)
    }

    val target = chain.head
    val removed = chain.last
    blockchainWriter.setCanonicalChainHead(target.number.value, target.hash, chain.last.number.value)

    // number→hash mapping is gone for the removed block
    blockchainReader.getBlockHeaderByNumber(removed.number.value) shouldBe None

    // but the header is still retrievable by its hash
    blockchainReader.getBlockHeaderByHash(removed.hash) shouldBe Some(removed.header)

  it should "be a no-op when currentBest equals targetNumber" taggedAs (UnitTest, StateTest) in
    (new EphemBlockchainTestSetup:
      val chain: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
      chain.foreach { b =>
        blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = true)
      }

      val best = chain.last
      // currentBest == targetNumber → no-op
      blockchainWriter.setCanonicalChainHead(best.number.value, best.hash, best.number.value)

      blockchainReader.getBestBlockNumber shouldBe best.number.value
      blockchainReader.getBlockHeaderByNumber(best.number.value) shouldBe Some(best.header)
    )

  it should "be a no-op when currentBest is less than targetNumber" taggedAs (UnitTest, StateTest) in
    (new EphemBlockchainTestSetup:
      val chain: List[Block] = BlockHelpers.generateChain(2, BlockHelpers.genesis)
      chain.foreach { b =>
        blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = true)
      }

      val best = chain.last
      // Pass currentBest lower than targetNumber — guard must prevent any write
      blockchainWriter.setCanonicalChainHead(best.number.value + 5, best.hash, best.number.value)

      blockchainReader.getBestBlockNumber shouldBe best.number.value
    )

  it should "remove all intermediate number→hash entries between target and currentBest" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup:
    val chain: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)
    chain.foreach { b =>
      blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = true)
    }

    val target: Block = chain(1) // block 2
    val currentBest = chain.last.number
    blockchainWriter.setCanonicalChainHead(target.number.value, target.hash, currentBest.value)

    // Every block above target should have its number→hash mapping removed
    (target.number.value + 1 to currentBest.value).foreach { n =>
      blockchainReader.getBlockHeaderByNumber(n) shouldBe None
    }
