package com.chipprbots.ethereum.consensus.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

/** B-511: on the hash layout `engine_newPayload` removes a block found invalid, or one that stands on such a block,
  * with the entry of the index of blocks by number that names it, in one write under the head's lock. The index names a
  * payload that extended it before a fork choice did (and a block the p2p import committed), so a removal of the block
  * alone left an entry naming a block that is gone.
  *
  * The chain is blocks 1 and 2, the head; block 3 extends it and is named by the index, ahead of the best block.
  */
class EngineApiInvalidBlockRemovalSpec extends AnyFlatSpec with Matchers:

  class Fixture extends EphemBlockchainTestSetup:
    blockchainWriter.save(BlockHelpers.genesis, Nil, ChainWeight.zero, saveAsBestBlock = true)
    val chain: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
    chain.take(2).foreach(block => blockchainWriter.save(block, Nil, ChainWeight.zero, saveAsBestBlock = true))
    blockchainWriter.storeBlock(chain(2)).commit()
    // a block at height 3 of another chain, known by its hash only
    val side: Block = BlockHelpers.generateBlock(chain(1))
    blockchainWriter.storeBlockByHashOnly(side).commit()
    blockchainReader.getBestBlockNumber shouldBe BigInt(2)
    blockchainReader.getCanonicalHashByNumber(3) shouldBe Some(chain(2).hash)

    val service = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      null,
      new ForkChoiceManager(blockchainReader, blockchainWriter),
      None
    )(using blockchainConfig, null)

  "The removal of an invalid block (the hash layout)" should
    "remove the entry of the index that names it, with the block" taggedAs (UnitTest, ConsensusTest) in (new Fixture:
      service.removeInvalidBlock(chain(2).hash)
      blockchainReader.getBlockHeaderByHash(chain(2).hash) shouldBe None
      blockchainReader.getCanonicalHashByNumber(3) shouldBe None
      // the head's chain stays
      blockchainReader.getCanonicalHashByNumber(2) shouldBe Some(chain(1).hash)
      blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(chain(1).hash)
    )

  it should "leave the entry at its height when the index names another block there" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Fixture:
    service.removeInvalidBlock(side.hash)
    blockchainReader.getBlockHeaderByHash(side.hash) shouldBe None
    blockchainReader.getCanonicalHashByNumber(3) shouldBe Some(chain(2).hash)
  )

  it should "wait for the head's lock, which a fork choice or the p2p import holds" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (
    new Fixture:
      @volatile var failed: Option[Throwable] = None
      val removal = new Thread(() =>
        try service.removeInvalidBlock(chain(2).hash)
        catch case t: Throwable => failed = Some(t)
      )
      val heldBack: Boolean = CanonicalHeadLock.held("the test's fork choice") {
        removal.start()
        val deadline = System.nanoTime() + 30_000_000_000L
        while !CanonicalHeadLock.isWaiting(removal) && removal.isAlive && System.nanoTime() < deadline do
          Thread.onSpinWait()
        val waited = CanonicalHeadLock.isWaiting(removal)
        // nothing removed while the lock is held
        blockchainReader.getCanonicalHashByNumber(3) shouldBe Some(chain(2).hash)
        waited
      }
      removal.join(30_000)
      failed.foreach(throw _)
      heldBack shouldBe true
      blockchainReader.getCanonicalHashByNumber(3) shouldBe None
  )
