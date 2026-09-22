package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.Consensus.BranchExecutionFailure
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranchPartially
import com.chipprbots.ethereum.consensus.Consensus.KeptCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranch
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

class ConsensusImplSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience with MockFactory:
  import ConsensusImplSpec.*
  "Consensus" should "extend the current best chain" taggedAs (UnitTest, ConsensusTest) in new ConsensusSetup:
    val chainExtension: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(chainExtension)).unsafeToFuture()) {
      _ shouldBe a[ExtendedCurrentBestBranch]
    }

    blockchainReader.getBestBlock shouldBe Some(chainExtension.last)

  it should "extends the branch partially if one block is invalid" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val chainExtension: List[Block] = BlockHelpers.generateChain(3, initialBestBlock)
    setFailingBlock(chainExtension(1))

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(chainExtension)).unsafeToFuture()) {
      _ shouldBe a[ExtendedCurrentBestBranchPartially]
    }
    blockchainReader.getBestBlock shouldBe Some(chainExtension.head)

  it should "keep the current best chain if the passed one is not better" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val chainWithLowWeight: List[Block] =
      BlockHelpers.generateChain(3, initialChain(2), b => b.copy(header = b.header.copy(difficulty = Difficulty(1))))

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(chainWithLowWeight)).unsafeToFuture()) {
      _ shouldBe KeptCurrentBestBranch
    }
    blockchainReader.getBestBlock shouldBe Some(initialBestBlock)

  it should "reorganise the chain if the new chain is better" taggedAs (UnitTest, ConsensusTest) in new ConsensusSetup:
    val newBetterBranch: List[Block] =
      BlockHelpers.generateChain(
        3,
        initialChain(2),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
      )

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(newBetterBranch)).unsafeToFuture()) {
      _ shouldBe a[SelectedNewBestBranch]
    }
    blockchainReader.getBestBlock shouldBe Some(newBetterBranch.last)

  // execute-first: chain advances to the last successfully executed block even on partial failure
  it should "return an error a block execution is failing during a reorganisation" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val newBetterBranch: List[Block] =
      BlockHelpers.generateChain(
        3,
        initialChain(2),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
      )

    // first block succeeds, second fails
    setFailingBlock(newBetterBranch(1))

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(newBetterBranch)).unsafeToFuture()) {
      _ shouldBe a[BranchExecutionFailure]
    }
    // chain advances to the first successful block, not reverted to pre-reorg tip
    blockchainReader.getBestBlock shouldBe Some(newBetterBranch.head)

  // execute-first: when ALL new-branch blocks fail, the old canonical chain is completely untouched
  it should "leave the old chain unchanged when all reorg blocks fail to execute" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val newBetterBranch: List[Block] =
      BlockHelpers.generateChain(
        3,
        initialChain(2),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
      )

    // first block fails immediately — no blocks execute
    setFailingBlock(newBetterBranch.head)

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(newBetterBranch)).unsafeToFuture()) {
      _ shouldBe a[BranchExecutionFailure]
    }
    // old chain untouched — no revertChainReorganisation, no bestBlock side-effects
    blockchainReader.getBestBlock shouldBe Some(initialBestBlock)

  // execute-first: old branch blocks remain accessible by hash after successful reorg (not deleted)
  it should "retain old branch blocks in the DB after a successful reorganisation" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    val oldTip = initialBestBlock // b4
    val oldBlock: Block = initialChain(3) // b3 (gets evicted)
    val newBetterBranch: List[Block] =
      BlockHelpers.generateChain(
        3,
        initialChain(2),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
      )

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(newBetterBranch)).unsafeToFuture()) {
      _ shouldBe a[SelectedNewBestBranch]
    }
    blockchainReader.getBestBlock shouldBe Some(newBetterBranch.last)
    // stale old-branch blocks are NOT deleted (reference client behaviour — GC'd by RocksDB)
    blockchainReader.getBlockByHash(oldTip.hash) shouldBe Some(oldTip)
    blockchainReader.getBlockByHash(oldBlock.hash) shouldBe Some(oldBlock)

  // execute-first eliminates the minority-tip loop: depth-1 reorg succeeds without pre-emptive SetHead
  it should "handle a depth-1 reorg where a minority block is at the canonical tip" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // single block at same height as initialBestBlock (b4), building on b3
    val newTip: List[Block] =
      BlockHelpers.generateChain(
        1,
        initialChain(3),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
      )

    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(newTip)).unsafeToFuture()) {
      _ shouldBe a[SelectedNewBestBranch]
    }
    // new block is canonical, old b4 is stale (still accessible, not deleted)
    blockchainReader.getBestBlock shouldBe Some(newTip.head)
    blockchainReader.getBlockByHash(initialBestBlock.hash) shouldBe Some(initialBestBlock)

  // ---------------------------------------------------------------------------------------------------------------
  // PoW fork choice under the PRODUCTION wiring of the PoS arm.
  //
  // In production ConsensusImpl is never built with `designatedHead = None`: NodeBuilder passes
  // `Some(designatedHead)`, a DesignatedHead.LateBound, on EVERY network. On ETC/Mordor/Gorgoroth that holder is
  // never bound (EngineApiBuilder.bindDesignatedHead requires the Engine API AND a terminal-total-difficulty), so its
  // `headBlockHash` is None. These cases pin that shape explicitly — an unbound LateBound — and show that the TD rule
  // is the whole decision under it.
  // ---------------------------------------------------------------------------------------------------------------

  it should "KEEP the current chain for an equal-TD competing PoW branch, under an unbound LateBound" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // Same height as the tip and same per-block difficulty (generateBlock copies the parent's), so equal total
    // difficulty. The TD rule is strict `>`, so a PoW node keeps its chain.
    val equalTdBranch: List[Block] = BlockHelpers.generateChain(2, initialChain(2))
    val etcShaped = consensusWith(Some(new DesignatedHead.LateBound))

    whenReady(etcShaped.evaluateBranch(NonEmptyList.fromListUnsafe(equalTdBranch)).unsafeToFuture()) {
      _ shouldBe KeptCurrentBestBranch
    }
    blockchainReader.getBestBlock shouldBe Some(initialBestBlock)

  it should "SELECT a heavier competing PoW branch, under an unbound LateBound" taggedAs (UnitTest, ConsensusTest) in
    new ConsensusSetup:
      val heavierBranch: List[Block] =
        BlockHelpers.generateChain(
          2,
          initialChain(2),
          b => b.copy(header = b.header.copy(difficulty = Difficulty(10000000)))
        )
      val etcShaped = consensusWith(Some(new DesignatedHead.LateBound))

      whenReady(etcShaped.evaluateBranch(NonEmptyList.fromListUnsafe(heavierBranch)).unsafeToFuture()) {
        _ shouldBe a[SelectedNewBestBranch]
      }
      blockchainReader.getBestBlock shouldBe Some(heavierBranch.last)

  it should "be sensitive to the binding — a BOUND holder does change the equal-TD outcome" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // The permanent form of the negative control for the two cases above. Same equal-TD branch, same constructor
    // shape, the only difference is that the holder is bound to a head on that branch — and the result flips. So
    // the unbound cases pass because the binding gate holds, not because nothing reaches the arm.
    val equalTdBranch: List[Block] = BlockHelpers.generateChain(2, initialChain(2))
    val holder = new DesignatedHead.LateBound
    holder.bind(DesignatedHead(() => Some(equalTdBranch.last.hash.value)))

    whenReady(consensusWith(Some(holder)).evaluateBranch(NonEmptyList.fromListUnsafe(equalTdBranch)).unsafeToFuture()) {
      _ shouldBe a[SelectedNewBestBranch]
    }

  it should "read the cake's own DesignatedHead holder — the wiring every other case in this spec runs under" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new ConsensusSetup:
    // `consensus` in this spec is NodeBuilder's ConsensusBuilder.consensus (StdTestMiningBuilder mixes it in), i.e.
    // `new ConsensusImpl(..., Some(invalidChainReporter), Some(designatedHead))`. Unbound, it keeps an equal-TD
    // branch; binding THAT holder flips it. This proves every pre-existing case above already ran under the
    // production shape, with an unbound LateBound.
    val equalTdBranch: List[Block] = BlockHelpers.generateChain(2, initialChain(2))
    designatedHead.isBound shouldBe false
    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(equalTdBranch)).unsafeToFuture()) {
      _ shouldBe KeptCurrentBestBranch
    }

    designatedHead.bind(DesignatedHead(() => Some(equalTdBranch.last.hash.value)))
    whenReady(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(equalTdBranch)).unsafeToFuture()) {
      _ shouldBe a[SelectedNewBestBranch]
    }

  // SCALA 3 MIGRATION: Moved ConsensusSetup inside class to access MockFactory context
  class ConsensusSetup extends EphemBlockchainTestSetup:
    override lazy val blockExecution: BlockExecution = stub[BlockExecution]

    // Set up stub behavior
    (blockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .when(*, *, *)
      .anyNumberOfTimes()
      .onCall { (blocks, _, _) =>
        val executedBlocks = blocks
          .takeWhile(b => !failingBlockHash.contains(b.hash))
          .map(b => BlockData(b, Nil, ChainWeight.zero))
        executedBlocks.foreach(b => blockchainWriter.save(b.block, b.receipts, b.weight, false))
        (
          executedBlocks,
          blocks.find(b => failingBlockHash.contains(b.hash)).map(_ => ValidationAfterExecError("test error"))
        )
      }

    // Initialize chain
    initialChain.foldLeft(ChainWeight.zero) { (previousWeight, block) =>
      val weight = previousWeight.increase(block.header)
      blockchainWriter.save(block, Nil, weight, saveAsBestBlock = true)
      weight
    }

    private var failingBlockHash: Option[ByteString] = None

    implicit val runtime: IORuntime = IORuntime.global

    def setFailingBlock(block: Block): Unit = failingBlockHash = Some(block.hash.value)

    def consensusWith(designatedHead: Option[DesignatedHead]): ConsensusImpl =
      new ConsensusImpl(blockchainReader, blockchainWriter, blockExecution, None, designatedHead)

object ConsensusImplSpec:
  val initialChain: List[Block] = BlockHelpers.genesis +: BlockHelpers.generateChain(4, BlockHelpers.genesis)
  val initialBestBlock = initialChain.last
