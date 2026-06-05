package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalamock.handlers.CallHandler0
import org.scalamock.handlers.CallHandler1
import org.scalamock.handlers.CallHandler2
import org.scalamock.handlers.CallHandler4

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.branch.Branch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.testing.Tags._

class BranchResolutionSpec
    extends AnyWordSpec
    with Matchers
    with ObjectGenerators
    with ScalaFutures
    with ScalaCheckPropertyChecks
    with org.scalamock.scalatest.MockFactory {

  "BranchResolution" should {

    "check if headers are from chain" taggedAs (UnitTest, StateTest) in new BlockchainSetup {
      val branchResolution = new BranchResolution(blockchainReader)
      val parent: BlockHeader = defaultBlockHeader.copy(number = 1)
      val child: BlockHeader = defaultBlockHeader.copy(number = 2, parentHash = parent.hash)
      branchResolution.doHeadersFormChain(NonEmptyList.of(parent, child)) shouldBe true
    }

    "check if headers are not from chain" taggedAs (UnitTest, StateTest) in new BlockchainSetup {
      val branchResolution = new BranchResolution(blockchainReader)
      val parent: BlockHeader = defaultBlockHeader.copy(number = 1)
      val otherParent: BlockHeader = defaultBlockHeader.copy(number = 3)
      val child: BlockHeader = defaultBlockHeader.copy(number = 2, parentHash = parent.hash)
      branchResolution.doHeadersFormChain(NonEmptyList.of(otherParent, child)) shouldBe false
    }

    "report an invalid branch when headers do not form a chain" in new BranchResolutionTestSetupImpl {
      val headers = getChainHeadersNel(1, 10).reverse
      branchResolution.resolveBranch(headers) shouldEqual InvalidBranch
    }

    "report an unknown branch in the parent of the first header is unknown" in new BranchResolutionTestSetupImpl {
      val headers = getChainHeadersNel(5, 10)

      setGenesisHeader(genesisHeader) // Check genesis block
      setHeaderInChain(headers.head.parentHash, result = false)

      branchResolution.resolveBranch(headers) shouldEqual UnknownBranch
    }

    "report new better branch found when headers form a branch of higher chain weight than corresponding known headers" in
      new BranchResolutionTestSetupImpl {
        val headers = getChainHeadersNel(1, 10)

        setBestBlockNumber(10)
        setHeaderInChain(headers.head.parentHash)
        setChainWeightByHash(headers.head.parentHash, ChainWeight.zero)

        val oldBlocks = getChain(1, 10, headers.head.parentHash, headers.head.difficulty - 1)
        oldBlocks.map(b => setBlockByNumber(b.header.number, Some(b)))

        branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)
      }

    "report no need for a chain switch the headers do not have chain weight greater than currently known branch" in
      new BranchResolutionTestSetupImpl {
        val headers = getChainHeadersNel(1, 10)

        setBestBlockNumber(10)
        setHeaderInChain(headers.head.parentHash)
        setChainWeightByHash(headers.head.parentHash, ChainWeight.zero)

        val oldBlocks = getChain(1, 10, headers.head.parentHash, headers.head.difficulty)
        oldBlocks.map(b => setBlockByNumber(b.header.number, Some(b)))

        branchResolution.resolveBranch(headers) shouldEqual NoChainSwitch
      }

    "correctly handle a branch that goes up to the genesis block" in new BranchResolutionTestSetupImpl {
      val headers = genesisHeader :: getChainHeadersNel(1, 10, genesisHeader.hash)

      setHeaderInChain(genesisHeader.parentHash, result = false)
      setGenesisHeader(genesisHeader)
      setBestBlockNumber(10)
      setChainWeightByHash(genesisHeader.hash, ChainWeight.zero)
      setBlockByNumber(0, Some(Block(genesisHeader, BlockBody(Nil, Nil))))

      val oldBlocks: List[Block] = getChain(1, 10, genesisHeader.hash, headers.tail.head.difficulty - 1)
      oldBlocks.foreach(b => setBlockByNumber(b.header.number, Some(b)))

      branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)
    }

    "report an unknown branch if the included genesis header is different than ours" in new BranchResolutionTestSetupImpl {
      val differentGenesis: BlockHeader = genesisHeader.copy(extraData = ByteString("I'm different ;("))
      val headers = differentGenesis :: getChainHeadersNel(1, 10, differentGenesis.hash)

      setHeaderInChain(differentGenesis.parentHash, result = false)
      setGenesisHeader(genesisHeader)

      branchResolution.resolveBranch(headers) shouldEqual UnknownBranch
    }

    "not include common prefix as result when finding a new better branch" in new BranchResolutionTestSetupImpl {
      val headers = getChainHeadersNel(1, 10)
      val commonParent = headers.toList(1)

      setBestBlockNumber(8)
      setHeaderInChain(headers.head.parentHash)
      setChainWeightByHash(commonParent.hash, ChainWeight.zero)

      val oldBlocks = getChain(3, 8, commonParent.hash)
      oldBlocks.foreach(b => setBlockByNumber(b.header.number, Some(b)))

      setBlockByNumber(1, Some(Block(headers.head, BlockBody(Nil, Nil))))
      setBlockByNumber(2, Some(Block(headers.tail.head, BlockBody(Nil, Nil))))

      branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)
      assert(oldBlocks.map(_.header.number) == List[BigInt](3, 4, 5, 6, 7, 8))
    }

    "report a new better branch with higher chain weight even if its shorter than the current " in new BranchResolutionTestSetupImpl {
      val commonParent = getBlock(1, parent = genesisHeader.hash)
      val parentWeight = ChainWeight.zero.increase(commonParent.header)
      val longerBranchLowerWeight = getChain(2, 10, commonParent.hash, difficulty = 100)
      val shorterBranchHigherWeight = getChainNel(2, 8, commonParent.hash, difficulty = 200)

      setHeaderInChain(commonParent.hash)
      setChainWeightForBlock(commonParent, parentWeight)
      setBestBlockNumber(longerBranchLowerWeight.last.number)
      longerBranchLowerWeight.foreach(b => setBlockByNumber(b.number, Some(b)))

      branchResolution.resolveBranch(shorterBranchHigherWeight.map(_.header)) shouldEqual NewBetterBranch(
        longerBranchLowerWeight
      )
    }

  }

  // ─────────────────────────────────────────────────────────────────────────
  // MESS (ECBP-1100) tests
  //
  // MESS is ETC-only; ETH/Sepolia networks have no MESS config. The polynomial
  // multiplier peaks at ~31× TD requirement at ~7 hours. Our numeric scenario:
  //   timeDelta = 3600s → polynomialV = 341
  //   localSubchainTD = 100 → want = 341 × 100 = 34100
  //   proposedTD = 200 → got = 200 × 128 = 25600 < 34100  → REJECT
  //   proposedTD = 500 → got = 500 × 128 = 64000 > 34100  → ACCEPT
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution without MESS config (ETH / Sepolia — no MESS)" should {

    "accept a branch with higher TD when no MESS config is set" taggedAs (UnitTest, StateTest) in new BranchResolutionTestSetupImpl {
      val commonParentHash = randomHash()
      val oldBlock         = getBlock(number = 10, difficulty = 100, parent = commonParentHash)
      val newHeader        = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header

      setBestBlockNumber(10)
      setHeaderInChain(commonParentHash)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))

      // No messConfig set — MESS never fires
      branchResolution.resolveBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
    }
  }

  "BranchResolution with MESS enabled (ETC / Mordor)" should {

    "reject a reorg when proposed subchain TD is below the antigravity threshold" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(ETCMessConfig)

      // proposedTD=200 → got=25600 < want=34100 → REJECT
      val newHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NoChainSwitch
    }

    "accept a reorg when proposed subchain TD meets the antigravity threshold" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(ETCMessConfig)

      // proposedTD=500 → got=64000 > want=34100 → ACCEPT
      val newHeader = getBlock(number = 10, difficulty = 500, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
    }

    "not activate when block number is below the activation window" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      setBestBlockNumber(5)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      val earlyBlock = Block(oldBlock.header.copy(number = 5), oldBlock.body)
      setBlockByNumber(5, Some(earlyBlock))
      // Activation at block 1000 — block 5 is before it, so MESS should not fire
      branchResolution.messConfig = Some(
        MESSConfig(enabled = true, activationBlock = Some(BigInt(1000)), deactivationBlock = None, reactivationBlock = None)
      )

      // proposedTD=200 would be rejected IF MESS were active, but it isn't
      val newHeader = getBlock(number = 5, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(earlyBlock))
    }

    "not activate when block number is in the Spiral deactivation window" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      // ETC mainnet: MESS deactivated at block 19,250,000 (Spiral fork)
      val deactivatedConfig = MESSConfig(
        enabled           = true,
        activationBlock   = Some(BigInt(11_380_000)),
        deactivationBlock = Some(BigInt(19_250_000)),
        reactivationBlock = None
      )
      val deactivatedBlock = Block(oldBlock.header.copy(number = BigInt(20_000_000)), oldBlock.body)

      setBestBlockNumber(BigInt(20_000_000))
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(BigInt(20_000_000), Some(deactivatedBlock))
      branchResolution.messConfig = Some(deactivatedConfig)

      // proposedTD=200 — MESS deactivated at this block → ACCEPT
      val newHeader = getBlock(number = BigInt(20_000_000), difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(deactivatedBlock))
    }

    "reactivate in the Olympia window" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      val olympiaConfig = MESSConfig(
        enabled           = true,
        activationBlock   = Some(BigInt(11_380_000)),
        deactivationBlock = Some(BigInt(19_250_000)),
        reactivationBlock = Some(BigInt(25_000_000))  // Olympia reactivation
      )
      val olympiaBlock = Block(
        oldBlock.header.copy(number = BigInt(25_000_001), unixTimestamp = headTs),
        oldBlock.body
      )

      setBestBlockNumber(BigInt(25_000_001))
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(BigInt(25_000_001), Some(olympiaBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(olympiaConfig)

      // proposedTD=200 → rejected because MESS is active again in Olympia window
      val newHeader = getBlock(number = BigInt(25_000_001), difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NoChainSwitch
    }

    "not activate when enabled = false" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      branchResolution.messConfig = Some(MESSConfig(enabled = false, activationBlock = Some(BigInt(10))))

      // enabled=false → MESS never fires regardless of TD ratio
      val newHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
    }

    "accept a small reorg (≤ 2 blocks) without requiring MESS log output" taggedAs (UnitTest, StateTest) in new MessTestSetup {
      // 1-block reorg: currentHead.number - commonAncestorNumber = 10 - 9 = 1 ≤ 2
      // MESS increments accepted counter but does NOT emit an info log for trivial reorgs
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(ETCMessConfig)

      // proposedTD=500 → passes MESS → accepted (1-block reorg, no log)
      val newHeader = getBlock(number = 10, difficulty = 500, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // PoS / zero-difficulty tests (ETH post-merge — Sepolia, Holesky, mainnet)
  //
  // All blocks have difficulty=0 after the Merge. ChainWeight never increases,
  // so the standard TD comparison doesn't work. The special path at
  //   newWeight == oldWeight && newHeaders.nonEmpty && oldBlocks.isEmpty
  // accepts a chain extension where no canonical blocks are displaced.
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution with PoS zero-difficulty blocks" should {

    "accept a chain extension with zero-difficulty blocks (ETH post-merge sync)" taggedAs (UnitTest, StateTest) in new BranchResolutionTestSetupImpl {
      val parentHash = randomHash()
      // Best block is 5; new header extends at 6 — no old blocks displaced
      val newHeader = Block(defaultHeader.copy(number = 6, difficulty = 0, parentHash = parentHash), BlockBody(Nil, Nil)).header
      val parentWeight = ChainWeight.totalDifficultyOnly(1000)

      setBestBlockNumber(5)
      setHeaderInChain(parentHash)
      setChainWeightByHash(parentHash, parentWeight)
      // getBlockByNumber is NOT called — range (6 to 5) is empty

      branchResolution.resolveBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(Nil)
    }

    "not switch chain when a zero-difficulty branch conflicts with existing canonical blocks" taggedAs (UnitTest, StateTest) in new BranchResolutionTestSetupImpl {
      val parentHash = randomHash()
      val existingBlock = getBlock(number = 10, difficulty = 0, parent = parentHash)
      val competingHeader = getBlock(number = 10, difficulty = 0, parent = parentHash).header
      // Both have difficulty=0 → equal weight; but existingBlock IS in the canonical chain
      // → NoChainSwitch (equal weight with conflict = keep current)

      setBestBlockNumber(10)
      setHeaderInChain(parentHash)
      setChainWeightByHash(parentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(existingBlock))

      branchResolution.resolveBranch(NonEmptyList.one(competingHeader)) shouldEqual NoChainSwitch
    }
  }

  class BranchResolutionTestSetupImpl extends TestSetupWithVmAndValidators with MockBlockchain {
    // Provide mock implementations - these are created in the test class context which has MockFactory
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchainWriter: BlockchainWriter = mock[BlockchainWriter]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockQueue: BlockQueue = mock[BlockQueue]

    // Setup default expectations
    (blockchainReader.getBestBranch _).expects().anyNumberOfTimes().returning(EmptyBranch)

    val branchResolution = new BranchResolution(blockchainReader)

    // Helper methods implementation (have MockFactory context here)
    override def setBlockExists(block: Block, inChain: Boolean, inQueue: Boolean): CallHandler1[ByteString, Boolean] = {
      (blockchainReader.getBlockByHash _)
        .expects(block.header.hash)
        .anyNumberOfTimes()
        .returning(Some(block).filter(_ => inChain))
      (blockQueue.isQueued _).expects(block.header.hash).anyNumberOfTimes().returning(inQueue)
    }

    override def setBestBlock(block: Block): CallHandler0[BigInt] = {
      (blockchainReader.getBestBlock _).expects().anyNumberOfTimes().returning(Some(block))
      (blockchainReader.getBestBlockNumber _).expects().anyNumberOfTimes().returning(block.header.number)
    }

    override def setBestBlockNumber(num: BigInt): CallHandler0[BigInt] =
      (blockchainReader.getBestBlockNumber _).expects().returning(num)

    override def setChainWeightForBlock(
        block: Block,
        weight: ChainWeight
    ): CallHandler1[ByteString, Option[ChainWeight]] =
      setChainWeightByHash(block.hash, weight)

    override def setChainWeightByHash(
        hash: ByteString,
        weight: ChainWeight
    ): CallHandler1[ByteString, Option[ChainWeight]] =
      (blockchainReader.getChainWeightByHash _).expects(hash).anyNumberOfTimes().returning(Some(weight))

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

    override def setHeaderInChain(hash: ByteString, result: Boolean = true): CallHandler2[Branch, ByteString, Boolean] =
      (blockchainReader.isInChain _).expects(*, hash).returning(result)

    override def setBlockByNumber(number: BigInt, block: Option[Block]): CallHandler2[Branch, BigInt, Option[Block]] =
      (blockchainReader.getBlockByNumber _).expects(*, number).returning(block)

    override def setGenesisHeader(header: BlockHeader): Unit =
      (() => blockchainReader.genesisHeader).expects().returning(header)
  }

  /** Shared setup for MESS and PoS branch-resolution tests.
    *
    * Pre-constructs a single old block at height 10 with a specific timestamp so that timeDelta = 3600s when the mock
    * ancestor header returns ts=1000. polynomialV(3600) = 341 → want = 341 × 100 = 34100.
    */
  class MessTestSetup extends BranchResolutionTestSetupImpl {

    /** Hash of the common ancestor block (parent of `oldBlock`). */
    val commonParentHash: ByteString = randomHash()

    /** Common ancestor timestamp returned by the blockchainReader mock. */
    val ancestorTs: Long = 1000L

    /** Current-head timestamp on `oldBlock`; timeDelta = headTs - ancestorTs = 3600s. */
    val headTs: Long = 4600L

    /** Single canonical block at height 10 with difficulty=100 and a controlled timestamp. */
    val oldBlock: Block = {
      val b = getBlock(number = 10, difficulty = 100, parent = commonParentHash)
      Block(b.header.copy(unixTimestamp = headTs), b.body)
    }

    /** Standard ETC/Mordor MESS config: active from block 10, no deactivation. */
    val ETCMessConfig: MESSConfig = MESSConfig(
      enabled           = true,
      activationBlock   = Some(BigInt(10)),
      deactivationBlock = None,
      reactivationBlock = None
    )

    /** Expect blockchainReader.getBlockHeaderByHash(commonParentHash) once, returning a header with `ts`. */
    def expectAncestorHeader(ts: Long = ancestorTs): Unit =
      (blockchainReader.getBlockHeaderByHash _)
        .expects(commonParentHash)
        .returning(Some(defaultHeader.copy(number = 9, unixTimestamp = ts)))
  }

}
