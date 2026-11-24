package com.chipprbots.ethereum.blockchain.sync.regular

import scala.annotation.tailrec

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolver
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

/** B-511: the hash layout's writers of the best block and the index of blocks by number that the import and fast sync
  * run, raced by a fork choice. Each holds [[CanonicalHeadLock]] over its look at the index and its write, so a fork
  * choice is before it or after it, never between; and whichever goes first, the best block and the index name one
  * chain at the end.
  *
  * The chain is blocks 1 to 10, the head; `side` leaves it after block 5 and is blocks 6 to 8, stored by hash, where
  * the fork choice moves the head while the writer waits for the lock.
  */
class BlockImporterHeadLockSpec extends AnyFlatSpec with Matchers:

  class Fixture extends EphemBlockchainTestSetup:
    private def weightOf(parent: Block, block: Block): ChainWeight =
      blockchainReader.getChainWeightByHash(parent.hash).getOrElse(ChainWeight.zero).increase(block.header)

    blockchainWriter.save(
      BlockHelpers.genesis,
      Nil,
      ChainWeight.zero.increase(BlockHelpers.genesis.header),
      saveAsBestBlock = true
    )
    val main: List[Block] = BlockHelpers.generateChain(12, BlockHelpers.genesis)
    main.take(10).foldLeft(BlockHelpers.genesis) { (parent, block) =>
      blockchainWriter.save(block, Nil, weightOf(parent, block), saveAsBestBlock = true)
      block
    }
    // blocks 11 and 12 of the chain, and the side chain, are known by their hash only, as the Engine API stores them
    private def storeByHash(blocks: List[Block], parent: Block): Unit =
      blocks.foldLeft(parent) { (p, block) =>
        blockchainWriter
          .storeBlockByHashOnly(block)
          .and(blockchainWriter.storeChainWeight(block.hash, weightOf(p, block)))
          .commit()
        block
      }: Unit
    storeByHash(main.drop(10), main(9))
    val side: List[Block] = BlockHelpers.generateChain(3, main(4))
    storeByHash(side, main(4))
    blockchainReader.getBestBlockNumber shouldBe BigInt(10)

    /** `write` on another thread while a fork choice to `forkChoiceTo` holds the lock: whether it waited for it. */
    def raced(forkChoiceTo: Block)(write: => Unit): Boolean =
      @volatile var failed: Option[Throwable] = None
      val writer = new Thread(() =>
        try write
        catch case t: Throwable => failed = Some(t)
      )
      val heldBack = CanonicalHeadLock.held("the test's fork choice") {
        writer.start()
        val deadline = System.nanoTime() + 30_000_000_000L
        while !CanonicalHeadLock.isWaiting(writer) && writer.isAlive && System.nanoTime() < deadline do
          Thread.onSpinWait()
        val waited = CanonicalHeadLock.isWaiting(writer)
        blockchainWriter.promoteToCanonicalHead(forkChoiceTo.header, blockchainReader): Unit
        waited
      }
      writer.join(30_000)
      failed.foreach(throw _)
      heldBack

    /** The best block and the index name one chain: the best block's ancestry at every height, nothing above it. */
    def oneChain(): Unit =
      val best = blockchainReader.getBestBlockHeader.get
      @tailrec def down(header: BlockHeader): Unit =
        withClue(s"height ${header.number}: ")(
          blockchainReader.getCanonicalHashByNumber(header.number.value) shouldBe Some(header.hash)
        )
        if header.number.value > 0 then down(blockchainReader.getBlockHeaderByHash(header.parentHash).get)
      down(best)
      // nothing above it, a gap included: every height the chain and the side chain reach
      (best.number.value + 1 to 16).foreach(n =>
        withClue(s"height $n: ")(blockchainReader.getCanonicalHashByNumber(n) shouldBe None)
      )

  "The import's rewind of a fork (the hash layout)" should
    "wait for a fork choice, and rewind nothing when the head is on another chain then" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture:
      raced(side.last)(
        BlockImporter.rewindCanonicalChain(
          blockchainReader,
          blockchainWriter,
          ReorgStateHandler.NoOp,
          target = 7,
          targetHash = main(6).hash,
          currentBest = 10
        )
      ) shouldBe true
      blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(side.last.hash)
      oneChain()
    )

  it should "delete what a fork choice wrote above the head it read, when the head is still on its chain" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new Fixture:
    // the fork choice moves the head up to block 12 after the importer read block 10 as the best
    raced(main.last)(
      BlockImporter.rewindCanonicalChain(
        blockchainReader,
        blockchainWriter,
        ReorgStateHandler.NoOp,
        target = 7,
        targetHash = main(6).hash,
        currentBest = 10
      )
    ) shouldBe true
    blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(main(6).hash)
    oneChain()
  )

  // Each removal takes the lock itself; the discard holds it over all of them, so a fork choice is not between two:
  // the next removal would read the index of the chain the fork choice moved to, and remove a block of it.
  "Fast sync's discard of the blocks above a common block (the hash layout)" should
    "hold a fork choice that comes between two of its removals until it has ended" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture:
      val storages = storagesInstance.storages
      @volatile var forkChoice: Option[Thread] = None
      @volatile var heldBack = false
      val removing: BlockchainImpl = new BlockchainImpl(
        storages.blockHeadersStorage,
        storages.blockBodiesStorage,
        storages.blockNumberMappingStorage,
        storages.receiptStorage,
        storages.chainWeightStorage,
        storages.transactionMappingStorage,
        storages.appStateStorage,
        storages.stateStorage,
        blockchainReader
      ):
        override def removeBlock(hash: BlockHash): Unit =
          super.removeBlock(hash)
          // after the first removal, block 10, a consensus client moves the head to the side chain
          if forkChoice.isEmpty then
            val thread =
              new Thread(() => blockchainWriter.promoteToCanonicalHead(side.last.header, blockchainReader): Unit)
            forkChoice = Some(thread)
            thread.start()
            val deadline = System.nanoTime() + 30_000_000_000L
            while !CanonicalHeadLock.isWaiting(thread) && thread.isAlive && System.nanoTime() < deadline do
              Thread.onSpinWait()
            heldBack = CanonicalHeadLock.isWaiting(thread)
      val reader = blockchainReader
      val resolver: FastSyncBranchResolver = new FastSyncBranchResolver:
        override protected def blockchain: Blockchain = removing
        override protected def blockchainReader: BlockchainReader = reader
      resolver.discardBlocksAfter(7).map(_._1) shouldBe Seq(10, 9, 8)
      forkChoice.foreach(_.join(30_000))
      heldBack shouldBe true
      // the discard went first, whole; the fork choice then moved the head to the side chain
      blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(side.last.hash)
      blockchainReader.getBlockHeaderByHash(side.last.hash).isDefined shouldBe true
      oneChain()
    )

  "The removal of a block (the hash layout)" should
    "wait for a fork choice, and leave the best block and the index on one chain" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture:
      raced(side.last)(blockchain.removeBlock(main(9).hash)) shouldBe true
      blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(side.last.hash)
      oneChain()
      blockchainReader.getBlockHeaderByHash(main(9).hash) shouldBe None
    )
