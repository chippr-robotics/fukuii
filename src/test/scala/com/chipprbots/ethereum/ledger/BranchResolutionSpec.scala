package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import org.scalamock.handlers.CallHandler0
import org.scalamock.handlers.CallHandler1
import org.scalamock.handlers.CallHandler2
import org.scalamock.handlers.CallHandler4
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.branch.Branch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.testing.Tags.*

class BranchResolutionSpec
    extends AnyWordSpec
    with Matchers
    with ObjectGenerators
    with ScalaFutures
    with ScalaCheckPropertyChecks
    with org.scalamock.scalatest.MockFactory:

  "BranchResolution" should {

    "check if headers are from chain" taggedAs (UnitTest, StateTest) in new BlockchainSetup:
      val branchResolution = new BranchResolution(blockchainReader)
      val parent: BlockHeader = defaultBlockHeader.copy(number = BlockNumber(1))
      val child: BlockHeader = defaultBlockHeader.copy(number = BlockNumber(2), parentHash = parent.hash)
      branchResolution.doHeadersFormChain(NonEmptyList.of(parent, child)) shouldBe true

    "check if headers are not from chain" taggedAs (UnitTest, StateTest) in new BlockchainSetup:
      val branchResolution = new BranchResolution(blockchainReader)
      val parent: BlockHeader = defaultBlockHeader.copy(number = BlockNumber(1))
      val otherParent: BlockHeader = defaultBlockHeader.copy(number = BlockNumber(3))
      val child: BlockHeader = defaultBlockHeader.copy(number = BlockNumber(2), parentHash = parent.hash)
      branchResolution.doHeadersFormChain(NonEmptyList.of(otherParent, child)) shouldBe false

    "report an invalid branch when headers do not form a chain" in new BranchResolutionTestSetupImpl:
      val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(1, 10).reverse
      branchResolution.resolveBranch(headers) shouldEqual InvalidBranch

    "report an unknown branch in the parent of the first header is unknown" in new BranchResolutionTestSetupImpl:
      val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(5, 10)

      setGenesisHeader(genesisHeader) // Check genesis block
      setHeaderInChain(headers.head.parentHash.value, result = false)

      branchResolution.resolveBranch(headers) shouldEqual UnknownBranch

    "report new better branch found when headers form a branch of higher chain weight than corresponding known headers" in
      new BranchResolutionTestSetupImpl:
        val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(1, 10)

        setBestBlockNumber(10)
        setHeaderInChain(headers.head.parentHash.value)
        setChainWeightByHash(headers.head.parentHash.value, ChainWeight.zero)

        val oldBlocks: List[Block] = getChain(1, 10, headers.head.parentHash.value, headers.head.difficulty.value - 1)
        oldBlocks.map(b => setBlockByNumber(b.header.number.value, Some(b)))

        branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)

    "report no need for a chain switch the headers do not have chain weight greater than currently known branch" in
      new BranchResolutionTestSetupImpl:
        val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(1, 10)

        setBestBlockNumber(10)
        setHeaderInChain(headers.head.parentHash.value)
        setChainWeightByHash(headers.head.parentHash.value, ChainWeight.zero)

        val oldBlocks: List[Block] = getChain(1, 10, headers.head.parentHash.value, headers.head.difficulty.value)
        oldBlocks.map(b => setBlockByNumber(b.header.number.value, Some(b)))

        branchResolution.resolveBranch(headers) shouldEqual NoChainSwitch

    "correctly handle a branch that goes up to the genesis block" in new BranchResolutionTestSetupImpl:
      val headers: NonEmptyList[BlockHeader] = genesisHeader :: getChainHeadersNel(1, 10, genesisHeader.hash.value)

      setHeaderInChain(genesisHeader.parentHash.value, result = false)
      setGenesisHeader(genesisHeader)
      setBestBlockNumber(10)
      setChainWeightByHash(genesisHeader.hash.value, ChainWeight.zero)
      setBlockByNumber(0, Some(Block(genesisHeader, BlockBody(Nil, Nil))))

      val oldBlocks: List[Block] = getChain(1, 10, genesisHeader.hash.value, headers.tail.head.difficulty.value - 1)
      oldBlocks.foreach(b => setBlockByNumber(b.header.number.value, Some(b)))

      branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)

    "report an unknown branch if the included genesis header is different than ours" in new BranchResolutionTestSetupImpl:
      val differentGenesis: BlockHeader = genesisHeader.copy(extraData = ByteString("I'm different ;("))
      val headers: NonEmptyList[BlockHeader] =
        differentGenesis :: getChainHeadersNel(1, 10, differentGenesis.hash.value)

      setHeaderInChain(differentGenesis.parentHash.value, result = false)
      setGenesisHeader(genesisHeader)

      branchResolution.resolveBranch(headers) shouldEqual UnknownBranch

    "not include common prefix as result when finding a new better branch" in new BranchResolutionTestSetupImpl:
      val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(1, 10)
      val commonParent: BlockHeader = headers.toList(1)

      setBestBlockNumber(8)
      setHeaderInChain(headers.head.parentHash.value)
      setChainWeightByHash(commonParent.hash.value, ChainWeight.zero)

      val oldBlocks: List[Block] = getChain(3, 8, commonParent.hash.value)
      oldBlocks.foreach(b => setBlockByNumber(b.header.number.value, Some(b)))

      setBlockByNumber(1, Some(Block(headers.head, BlockBody(Nil, Nil))))
      setBlockByNumber(2, Some(Block(headers.tail.head, BlockBody(Nil, Nil))))

      branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)
      assert(oldBlocks.map(_.header.number) == List[BigInt](3, 4, 5, 6, 7, 8))

    "report a new better branch with higher chain weight even if its shorter than the current " in new BranchResolutionTestSetupImpl:
      val commonParent: Block = getBlock(1, parent = genesisHeader.hash.value)
      val parentWeight: ChainWeight = ChainWeight.zero.increase(commonParent.header)
      val longerBranchLowerWeight: List[Block] = getChain(2, 10, commonParent.hash.value, difficulty = 100)
      val shorterBranchHigherWeight: NonEmptyList[Block] = getChainNel(2, 8, commonParent.hash.value, difficulty = 200)

      setHeaderInChain(commonParent.hash.value)
      setChainWeightForBlock(commonParent, parentWeight)
      setBestBlockNumber(longerBranchLowerWeight.last.number.value)
      longerBranchLowerWeight.foreach(b => setBlockByNumber(b.number.value, Some(b)))

      branchResolution.resolveBranch(shorterBranchHigherWeight.map(_.header)) shouldEqual NewBetterBranch(
        longerBranchLowerWeight
      )

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

    "accept a branch with higher TD when no MESS config is set" taggedAs (
      UnitTest,
      StateTest
    ) in new BranchResolutionTestSetupImpl:
      val commonParentHash: ByteString = randomHash()
      val oldBlock: Block = getBlock(number = 10, difficulty = 100, parent = commonParentHash)
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header

      setBestBlockNumber(10)
      setHeaderInChain(commonParentHash)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))

      // No messConfig set — MESS never fires
      branchResolution.resolveBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
  }

  "BranchResolution with MESS enabled (ETC / Mordor)" should {

    "reject a reorg when proposed subchain TD is below the antigravity threshold" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(ETCMessConfig)

      // proposedTD=200 → got=25600 < want=34100 → REJECT
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NoChainSwitch

    "accept a reorg when proposed subchain TD meets the antigravity threshold" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(ETCMessConfig)

      // proposedTD=500 → got=64000 > want=34100 → ACCEPT
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 500, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))

    "not activate when block number is below the activation window" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      setBestBlockNumber(5)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      val earlyBlock: Block = Block(oldBlock.header.copy(number = BlockNumber(5)), oldBlock.body)
      setBlockByNumber(5, Some(earlyBlock))
      // Activation at block 1000 — block 5 is before it, so MESS should not fire
      branchResolution.messConfig = Some(
        MESSConfig(
          enabled = true,
          activationBlock = Some(BigInt(1000)),
          deactivationBlock = None,
          reactivationBlock = None
        )
      )

      // proposedTD=200 would be rejected IF MESS were active, but it isn't
      val newHeader: BlockHeader = getBlock(number = 5, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(earlyBlock))

    "not activate when block number is in the Spiral deactivation window" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      // ETC mainnet: MESS deactivated at block 19,250,000 (Spiral fork)
      val deactivatedConfig: MESSConfig = MESSConfig(
        enabled = true,
        activationBlock = Some(BigInt(11_380_000)),
        deactivationBlock = Some(BigInt(19_250_000)),
        reactivationBlock = None
      )
      val deactivatedBlock: Block = Block(oldBlock.header.copy(number = BlockNumber(BigInt(20_000_000))), oldBlock.body)

      setBestBlockNumber(BigInt(20_000_000))
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(BigInt(20_000_000), Some(deactivatedBlock))
      branchResolution.messConfig = Some(deactivatedConfig)

      // proposedTD=200 — MESS deactivated at this block → ACCEPT
      val newHeader: BlockHeader =
        getBlock(number = BigInt(20_000_000), difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(deactivatedBlock))

    "reactivate in the Olympia window" taggedAs (UnitTest, StateTest) in new MessTestSetup:
      val olympiaConfig: MESSConfig = MESSConfig(
        enabled = true,
        activationBlock = Some(BigInt(11_380_000)),
        deactivationBlock = Some(BigInt(19_250_000)),
        reactivationBlock = Some(BigInt(25_000_000)) // Olympia reactivation
      )
      val olympiaBlock: Block = Block(
        oldBlock.header.copy(number = BlockNumber(BigInt(25_000_001)), unixTimestamp = Timestamp(headTs)),
        oldBlock.body
      )

      setBestBlockNumber(BigInt(25_000_001))
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(BigInt(25_000_001), Some(olympiaBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(olympiaConfig)

      // proposedTD=200 → rejected because MESS is active again in Olympia window
      val newHeader: BlockHeader =
        getBlock(number = BigInt(25_000_001), difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NoChainSwitch

    "not activate when enabled = false" taggedAs (UnitTest, StateTest) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      branchResolution.messConfig = Some(MESSConfig(enabled = false, activationBlock = Some(BigInt(10))))

      // enabled=false → MESS never fires regardless of TD ratio
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))

    "accept a small reorg (≤ 2 blocks) without requiring MESS log output" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      // 1-block reorg: currentHead.number - commonAncestorNumber = 10 - 9 = 1 ≤ 2
      // MESS increments accepted counter but does NOT emit an info log for trivial reorgs
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()
      branchResolution.messConfig = Some(ETCMessConfig)

      // proposedTD=500 → passes MESS → accepted (1-block reorg, no log)
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 500, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
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

    "accept a chain extension with zero-difficulty blocks (ETH post-merge sync)" taggedAs (
      UnitTest,
      StateTest
    ) in new BranchResolutionTestSetupImpl:
      val parentHash: ByteString = randomHash()
      // Best block is 5; new header extends at 6 — no old blocks displaced
      val newHeader: BlockHeader =
        Block(
          defaultHeader.copy(number = BlockNumber(6), difficulty = Difficulty.Zero, parentHash = BlockHash(parentHash)),
          BlockBody(Nil, Nil)
        ).header
      val parentWeight: ChainWeight = ChainWeight.totalDifficultyOnly(1000)

      setBestBlockNumber(5)
      setHeaderInChain(parentHash)
      setChainWeightByHash(parentHash, parentWeight)
      // getBlockByNumber is NOT called — range (6 to 5) is empty

      branchResolution.resolveBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(Nil)

    "not switch chain when a zero-difficulty branch conflicts with existing canonical blocks" taggedAs (
      UnitTest,
      StateTest
    ) in new BranchResolutionTestSetupImpl:
      val parentHash: ByteString = randomHash()
      val existingBlock: Block = getBlock(number = 10, difficulty = 0, parent = parentHash)
      val competingHeader: BlockHeader = getBlock(number = 10, difficulty = 0, parent = parentHash).header
      // Both have difficulty=0 → equal weight; but existingBlock IS in the canonical chain
      // → NoChainSwitch (equal weight with conflict = keep current)

      setBestBlockNumber(10)
      setHeaderInChain(parentHash)
      setChainWeightByHash(parentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(existingBlock))

      branchResolution.resolveBranch(NonEmptyList.one(competingHeader)) shouldEqual NoChainSwitch
  }

  // ─────────────────────────────────────────────────────────────────────────
  // MISCONFIGURATION HAZARD — PINNED, NOT PREVENTED.
  //
  // The PoS fork-choice arm (DesignatedHead) is inert on ETC/Mordor/Gorgoroth only because those chains configure no
  // terminal-total-difficulty: SyncController supplies BranchResolution a `Some(designatedHead)` only when TTD is set.
  // An operator who force-sets `terminal-total-difficulty` on a PoW chain opens that gate. What these cases pin is
  // what happens THEN, so the hazard is visible in the suite rather than latent in the code:
  //
  //   - a LIGHTER (or equal-TD) branch the "CL" designates is ACCEPTED: the PoS arm sits after the TD comparison and
  //     overrides the TD rule, and MESS is never consulted on that path;
  //   - a HEAVIER branch that MESS rejects is still REFUSED: MESS lives inside the TD arm, which returns first.
  //
  // This is a deliberate misconfiguration that already switches on other PoS paths (BlockchainConfig.isPoS routes on
  // TTD). Behaviour is deliberately NOT changed here; if either assertion ever flips, the change needs a forge review.
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution with MESS enabled AND a designated head (misconfigured PoW chain)" should {

    "ACCEPT a LOWER-TD branch the designated head points at — the PoS arm overrides the TD rule" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))

      // proposedTD=50 < localTD=100. On a correctly configured PoW chain this is NoChainSwitch (see the control
      // case below). The designated head is the branch tip itself, so the ancestry walk needs no header lookup.
      val lighterHeader: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      val misconfigured = new BranchResolution(
        blockchainReader,
        Some(DesignatedHead(() => Some(lighterHeader.hash.value)))
      )
      misconfigured.messConfig = Some(ETCMessConfig)

      misconfigured.compareBranch(NonEmptyList.one(lighterHeader)) shouldEqual NewBetterBranch(List(oldBlock))

    "REFUSE the same LOWER-TD branch with no designated head — the correctly configured control" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      branchResolution.messConfig = Some(ETCMessConfig)

      val lighterHeader: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(lighterHeader)) shouldEqual NoChainSwitch

    "still REFUSE a HEAVIER branch MESS rejects, even when the designated head points at it" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      expectAncestorHeader()

      // proposedTD=200 > localTD=100, but got=25600 < want=34100 so MESS rejects (same numbers as the MESS cases
      // above). The TD arm returns NoChainSwitch before the PoS arm is ever evaluated.
      val heavierHeader: BlockHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header
      val misconfigured = new BranchResolution(
        blockchainReader,
        Some(DesignatedHead(() => Some(heavierHeader.hash.value)))
      )
      misconfigured.messConfig = Some(ETCMessConfig)

      misconfigured.compareBranch(NonEmptyList.one(heavierHeader)) shouldEqual NoChainSwitch
  }

  class BranchResolutionTestSetupImpl extends TestSetupWithVmAndValidators with MockBlockchain:
    // Provide mock implementations - these are created in the test class context which has MockFactory
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchainWriter: BlockchainWriter = mock[BlockchainWriter]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockQueue: BlockQueue = mock[BlockQueue]

    // Setup default expectations
    (() => blockchainReader.getBestBranch).expects().anyNumberOfTimes().returning(EmptyBranch)

    val branchResolution = new BranchResolution(blockchainReader)

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

  /** Shared setup for MESS and PoS branch-resolution tests.
    *
    * Pre-constructs a single old block at height 10 with a specific timestamp so that timeDelta = 3600s when the mock
    * ancestor header returns ts=1000. polynomialV(3600) = 341 → want = 341 × 100 = 34100.
    */
  class MessTestSetup extends BranchResolutionTestSetupImpl:

    /** Hash of the common ancestor block (parent of `oldBlock`). */
    val commonParentHash: ByteString = randomHash()

    /** Common ancestor timestamp returned by the blockchainReader mock. */
    val ancestorTs: Long = 1000L

    /** Current-head timestamp on `oldBlock`; timeDelta = headTs - ancestorTs = 3600s. */
    val headTs: Long = 4600L

    /** Single canonical block at height 10 with difficulty=100 and a controlled timestamp. */
    val oldBlock: Block =
      val b = getBlock(number = 10, difficulty = 100, parent = commonParentHash)
      Block(b.header.copy(unixTimestamp = Timestamp(headTs)), b.body)

    /** Standard ETC/Mordor MESS config: active from block 10, no deactivation. */
    val ETCMessConfig: MESSConfig = MESSConfig(
      enabled = true,
      activationBlock = Some(BigInt(10)),
      deactivationBlock = None,
      reactivationBlock = None
    )

    /** Expect blockchainReader.getBlockHeaderByHash(commonParentHash) once, returning a header with `ts`. */
    def expectAncestorHeader(ts: Long = ancestorTs): Unit =
      blockchainReader.getBlockHeaderByHash
        .expects(BlockHash(commonParentHash))
        .returning(Some(defaultHeader.copy(number = BlockNumber(9), unixTimestamp = Timestamp(ts))))
