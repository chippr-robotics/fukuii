package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalamock.handlers.CallHandler0
import org.scalamock.handlers.CallHandler1
import org.scalamock.handlers.CallHandler2
import org.scalamock.handlers.CallHandler4
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.BackfillCursors
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.branch.Branch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
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
      (new BranchResolutionTestSetupImpl:
        val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(1, 10)

        setBestBlockNumber(10)
        setHeaderInChain(headers.head.parentHash.value)
        setChainWeightByHash(headers.head.parentHash.value, ChainWeight.zero)

        val oldBlocks: List[Block] = getChain(1, 10, headers.head.parentHash.value, headers.head.difficulty.value - 1)
        oldBlocks.map(b => setBlockByNumber(b.header.number.value, Some(b)))

        branchResolution.resolveBranch(headers) shouldEqual NewBetterBranch(oldBlocks)
      )

    "report no need for a chain switch the headers do not have chain weight greater than currently known branch" in
      (new BranchResolutionTestSetupImpl:
        val headers: NonEmptyList[BlockHeader] = getChainHeadersNel(1, 10)

        setBestBlockNumber(10)
        setHeaderInChain(headers.head.parentHash.value)
        setChainWeightByHash(headers.head.parentHash.value, ChainWeight.zero)

        val oldBlocks: List[Block] = getChain(1, 10, headers.head.parentHash.value, headers.head.difficulty.value)
        oldBlocks.map(b => setBlockByNumber(b.header.number.value, Some(b)))

        branchResolution.resolveBranch(headers) shouldEqual NoChainSwitch
      )

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

  // Spec 018 C6 (forge): the header of the common block not stored. MESS weighs the seconds from it to the head; read
  // as time zero it took the age as decades, refused a heavier branch at the maximum antigravity (31 times its weight),
  // and the branch was remembered as not taken: the node stopped following the chain for a hole in its own datadir. The
  // hole is a fault, not a verdict: MESS does not refuse, the branch is judged by weight and handed to consensus, whose
  // reorganisation meets the hole as a state fault (StateUnavailable).
  "BranchResolution with MESS enabled, where the common block's header is not stored" should {

    "not refuse a heavier branch, even one MESS refuses when the header is there" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new MessTestSetup:
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      branchResolution.messConfig = Some(ETCMessConfig)
      Seq(200, 300, 500).foreach { difficulty =>
        setBestBlockNumber(10)
        setBlockByNumber(10, Some(oldBlock))
        expectAncestorHeaderMissing()
        withClue(s"proposed difficulty $difficulty: ") {
          val newHeader: BlockHeader =
            getBlock(number = 10, difficulty = difficulty, parent = commonParentHash).header
          branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
        }
      }

    "leave a lighter branch refused by weight: MESS is not asked" taggedAs (UnitTest, StateTest) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      // no header is read: the weight refuses it first
      blockchainReader.getBlockHeaderByHash.expects(*).never()
      branchResolution.messConfig = Some(ETCMessConfig)
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 99, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NoChainSwitch

    "take a branch not taken once it is the heavier, the header not being there to weigh" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new MessTestSetup:
      (1 to 2).foreach { _ =>
        setBestBlockNumber(10)
        setBlockByNumber(10, Some(oldBlock))
      }
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setChainWeightByHash(oldBlock.header.hash.value, ChainWeight.zero.increase(oldBlock.header))
      branchResolution.messConfig = Some(ETCMessConfig)
      val side10: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      val branch: BranchNotTaken = branchResolution.notTaken(NonEmptyList.one(side10)).get
      blockchainReader.getCanonicalHashByNumber
        .expects(BigInt(9))
        .anyNumberOfTimes()
        .returning(Some(BlockHash(commonParentHash)))
      // 200 against 100: MESS refuses it with the header there (25600 < 34100); without it the weight decides
      expectAncestorHeaderMissing()
      val side11: BlockHeader = getBlock(number = 11, difficulty = 150, parent = side10.hash.value).header
      branchResolution.extended(branch, NonEmptyList.one(side11)).map(_._2) shouldBe Some(true)

    "not read the header, nor refuse, where MESS is not active" taggedAs (UnitTest, StateTest) in new MessTestSetup:
      setBestBlockNumber(10)
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setBlockByNumber(10, Some(oldBlock))
      blockchainReader.getBlockHeaderByHash.expects(*).never()
      branchResolution.messConfig = Some(ETCMessConfig.copy(activationBlock = Some(BigInt(11))))
      val newHeader: BlockHeader = getBlock(number = 10, difficulty = 200, parent = commonParentHash).header
      branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual NewBetterBranch(List(oldBlock))
  }

  // With the header stored the verdicts are those MESS gave before the fault was split off: the threshold at an age of
  // 3600 s and a local weight of 100 is want = 341 * 100 = 34100, so 266 (34048) is refused and 267 (34176) taken.
  "BranchResolution with MESS enabled, the common block's header stored" should {
    "give the verdicts it gave before, either side of the threshold" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new MessTestSetup:
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      branchResolution.messConfig = Some(ETCMessConfig)
      val verdicts: Seq[(Int, NoChainSwitch.type | NewBetterBranch)] = Seq(
        200 -> NoChainSwitch,
        266 -> NoChainSwitch,
        267 -> NewBetterBranch(List(oldBlock)),
        300 -> NewBetterBranch(List(oldBlock)),
        500 -> NewBetterBranch(List(oldBlock))
      )
      verdicts.foreach { case (difficulty, verdict) =>
        setBestBlockNumber(10)
        setBlockByNumber(10, Some(oldBlock))
        expectAncestorHeader()
        withClue(s"proposed difficulty $difficulty: ") {
          val newHeader: BlockHeader =
            getBlock(number = 10, difficulty = difficulty, parent = commonParentHash).header
          branchResolution.compareBranch(NonEmptyList.one(newHeader)) shouldEqual verdict
        }
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

  // B-300 (f) (forge): on the unified layout the blocks of a branch that is not taken are not stored, so what follows it
  // hangs from a block this node does not know. The branch is remembered by its tip and weighed with what follows.
  // B-385: compareBranch answers NoChainSwitch, with an error, where the weight of the block a branch stands on is not
  // stored. The importer is to tell the escalation's feed of those headers, so they are named here; the verdict is the
  // one it was
  "A branch whose fork block's chain weight is not stored" should {

    "be named by its headers above this node's, with the verdict unchanged" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new MessTestSetup:
      blockchainReader.getChainWeightByHash.expects(BlockHash(commonParentHash)).anyNumberOfTimes().returning(None)
      branchResolution.messConfig = Some(ETCMessConfig)
      val side10: BlockHeader = getBlock(number = 10, difficulty = 500, parent = commonParentHash).header
      (1 to 2).foreach { _ =>
        setBestBlockNumber(10)
        setBlockByNumber(10, Some(oldBlock))
      }
      branchResolution.compareBranch(NonEmptyList.one(side10)) shouldEqual NoChainSwitch
      branchResolution.withoutForkWeight(NonEmptyList.one(side10)) shouldBe Some(NonEmptyList.one(side10))

    "not be named where the weight is stored, nor for this node's own blocks" taggedAs (UnitTest, StateTest) in
      (new MessTestSetup:
        setChainWeightByHash(commonParentHash, ChainWeight.zero)
        (1 to 2).foreach { _ =>
          setBestBlockNumber(10)
          setBlockByNumber(10, Some(oldBlock))
        }
        val side10: BlockHeader = getBlock(number = 10, difficulty = 500, parent = commonParentHash).header
        branchResolution.withoutForkWeight(NonEmptyList.one(side10)) shouldBe None
        branchResolution.withoutForkWeight(NonEmptyList.one(oldBlock.header)) shouldBe None
      )
  }

  "A branch that was weighed and not taken" should {

    "be remembered by its tip, its common block, its weight and its difficulty, and weighed again with what follows" taggedAs (
      UnitTest,
      StateTest
    ) in new MessTestSetup:
      // this node's chain is read once for each question asked below
      (1 to 5).foreach { _ =>
        setBestBlockNumber(10)
        setBlockByNumber(10, Some(oldBlock))
      }
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setChainWeightByHash(oldBlock.header.hash.value, ChainWeight.zero.increase(oldBlock.header))

      // this node's own block is no side branch
      branchResolution.notTaken(NonEmptyList.one(oldBlock.header)) shouldBe None

      // a block at the head's height with half its difficulty: not taken
      val side10: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      val branch: BranchNotTaken = branchResolution.notTaken(NonEmptyList.one(side10)).get
      branch shouldBe
        BranchNotTaken(side10, BigInt(9), commonParentHash, ChainWeight.zero.increase(side10), BigInt(50))
      // the block it leaves this chain at is on it still
      blockchainReader.getCanonicalHashByNumber
        .expects(BigInt(9))
        .anyNumberOfTimes()
        .returning(Some(BlockHash(commonParentHash)))

      // with the next block it is still the lighter: 80 against this node's 100
      val side11: BlockHeader = getBlock(number = 11, difficulty = 30, parent = side10.hash.value).header
      val (longer, takenAt11) = branchResolution.extended(branch, NonEmptyList.one(side11)).get
      takenAt11 shouldBe false
      (longer.tip, longer.commonBlock, longer.difficultyAboveTheCommonBlock) shouldBe (side11, BigInt(9), BigInt(80))
      longer.weight shouldBe ChainWeight.zero.increase(side10).increase(side11)

      // of the same weight as this node's chain it is not taken either: strictly heavier, as compareBranch asks
      val side11Even: BlockHeader = getBlock(number = 11, difficulty = 50, parent = side10.hash.value).header
      branchResolution.extended(branch, NonEmptyList.one(side11Even)).map(_._2) shouldBe Some(false)

      // and with the one after it is the heavier: 110
      val side12: BlockHeader = getBlock(number = 12, difficulty = 30, parent = side11.hash.value).header
      val (longest, takenAt12) = branchResolution.extended(longer, NonEmptyList.one(side12)).get
      takenAt12 shouldBe true
      longest.difficultyAboveTheCommonBlock shouldBe BigInt(110)

      // headers that do not stand on its tip say nothing of it
      branchResolution.extended(branch, NonEmptyList.one(side12)) shouldBe None

    "not be taken while MESS refuses it, however much heavier, and be taken once MESS lets it" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new MessTestSetup:
      (1 to 3).foreach { _ =>
        setBestBlockNumber(10)
        setBlockByNumber(10, Some(oldBlock))
      }
      setChainWeightByHash(commonParentHash, ChainWeight.zero)
      setChainWeightByHash(oldBlock.header.hash.value, ChainWeight.zero.increase(oldBlock.header))
      branchResolution.messConfig = Some(ETCMessConfig)

      val side10: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      val branch: BranchNotTaken = branchResolution.notTaken(NonEmptyList.one(side10)).get
      blockchainReader.getCanonicalHashByNumber
        .expects(BigInt(9))
        .anyNumberOfTimes()
        .returning(Some(BlockHash(commonParentHash)))

      // 200 against 100 is the heavier, and below the antigravity threshold (25600 < 34100): refused, as compareBranch
      // refuses a branch of that difficulty
      expectAncestorHeader()
      val side11: BlockHeader = getBlock(number = 11, difficulty = 150, parent = side10.hash.value).header
      val (longer, takenAt11) = branchResolution.extended(branch, NonEmptyList.one(side11)).get
      takenAt11 shouldBe false

      // 500 meets it (64000 > 34100)
      expectAncestorHeader()
      val side12: BlockHeader = getBlock(number = 12, difficulty = 300, parent = side11.hash.value).header
      branchResolution.extended(longer, NonEmptyList.one(side12)).map(_._2) shouldBe Some(true)
  }

  "The branches not taken that are remembered" should {
    "keep the last ones, forget the oldest first, and give a branch up when what follows it is weighed" taggedAs UnitTest in new MessTestSetup:
      def branch(tag: Int): BranchNotTaken =
        val tip = defaultHeader.copy(number = BlockNumber(100 + tag), extraData = ByteString(s"tip $tag"))
        BranchNotTaken(tip, BigInt(99), randomHash(), ChainWeight.totalDifficultyOnly(tag), BigInt(tag))
      val remembered = new BranchesNotTaken(kept = 2)
      val (first, second, third) = (branch(1), branch(2), branch(3))
      Seq(first, second, third).foreach(remembered.remember)
      remembered.size shouldBe 2
      remembered.get(first.tip.hash.value) shouldBe None
      remembered.get(second.tip.hash.value) shouldBe Some(second)
      // the same tip again counts as the newest
      remembered.remember(second)
      remembered.remember(branch(4))
      remembered.get(third.tip.hash.value) shouldBe None
      remembered.get(second.tip.hash.value) shouldBe Some(second)
      // taken out once what follows it is weighed: not found a second time
      remembered.take(second.tip.hash.value) shouldBe Some(second)
      remembered.take(second.tip.hash.value) shouldBe None
      remembered.size shouldBe 1
  }

  "A branch that was not taken, once the block it leaves this chain at is off it" should {
    "say nothing: MESS would be asked of another pair of branches" taggedAs (UnitTest, StateTest) in new MessTestSetup:
      val side10: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      val branch: BranchNotTaken =
        BranchNotTaken(side10, BigInt(9), commonParentHash, ChainWeight.zero.increase(side10), BigInt(50))
      blockchainReader.getCanonicalHashByNumber.expects(BigInt(9)).returning(Some(BlockHash(randomHash())))
      val side11: BlockHeader = getBlock(number = 11, difficulty = 1000, parent = side10.hash.value).header
      branchResolution.extended(branch, NonEmptyList.one(side11)) shouldBe None
  }

  // ─────────────────────────────────────────────────────────────────────────
  // B-395: a block of this node's chain not stored inside the compared range. Skipped, it moved every block above it
  // down one place against the headers: the shared prefix, the block weighed from and the blocks replaced were one
  // block out. Now the comparison stops at it: no verdict is given that it would make misaligned.
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution over a gap in this node's chain" should {

    "give the verdict on the batch where no block is missing (the control)" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None)
      weightsStored()
      // own 1..10; the batch is own 1..6 and a heavier branch 7..12 from own 6
      branchResolution.compareBranch(extendsFromSix) shouldEqual NewBetterBranch(own.drop(6))
      branchResolution.notTaken(extendsFromSix).map(b => (b.commonBlock, b.commonHash)) shouldBe
        Some((BigInt(6), own(5).hash.value))

    "make no fork choice where the gap is below the block the branch leaves this chain at" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(5))
      weightsStored()
      // skipped, block 5 set own 6 against header 5: shared 4, and own 6..10 'replaced' by the batch from 5
      branchResolution.compareBranch(extendsFromSix) shouldEqual NoChainSwitch
      // nor is a branch remembered as leaving this chain at block 4
      branchResolution.notTaken(extendsFromSix) shouldBe None

    "name no branch whose fork weight is missing where the gap leaves the block weighed from unknown" taggedAs (
      UnitTest,
      StateTest
    ) in new GapSetup:
      ownChain(missing = Some(5))
      weightsNotStored()
      // skipped, the block weighed from was block 5 itself (the parent of own 6), and headers 5..12 were named
      branchResolution.withoutForkWeight(extendsFromSix) shouldBe None

    "make no fork choice where the gap is above the block the branch leaves this chain at" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(8))
      weightsStored()
      // own 3..10 weigh 800 and the branch 720; skipped, block 8 left this node's 700 and the lighter branch was taken
      branchResolution.compareBranch(lighterFromTwo) shouldEqual NoChainSwitch
      // the block it leaves this chain at is below the gap, so it is known: remembered as it was
      branchResolution.notTaken(lighterFromTwo) shouldBe Some(
        BranchNotTaken(
          lighterFromTwo.last,
          BigInt(2),
          own(1).hash.value,
          lighterFromTwo.tail.tail.foldLeft(ChainWeight.zero)(_.increase(_)),
          BigInt(720)
        )
      )

    "still name the branch whose fork weight is missing where the block weighed from is below the gap" taggedAs (
      UnitTest,
      StateTest
    ) in new GapSetup:
      ownChain(missing = Some(8))
      weightsNotStored()
      branchResolution.withoutForkWeight(lighterFromTwo) shouldBe NonEmptyList.fromList(lighterFromTwo.toList.drop(2))

    "not take a branch not taken, weighed again, past a gap above the block it leaves this chain at" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new MessTestSetup:
      (() => blockchainReader.getBestBlockNumber).expects().anyNumberOfTimes().returning(BigInt(11))
      blockchainReader.getBlockByNumber.expects(*, BigInt(10)).anyNumberOfTimes().returning(Some(oldBlock))
      blockchainReader.getBlockByNumber.expects(*, BigInt(11)).anyNumberOfTimes().returning(None)
      blockchainReader.getHashByBlockNumber.expects(*, BigInt(11)).anyNumberOfTimes().returning(None)
      (() => blockchainReader.getSnapSyncPivotBlock).expects().anyNumberOfTimes().returning(None)
      blockchainReader.getCanonicalHashByNumber
        .expects(BigInt(9))
        .anyNumberOfTimes()
        .returning(Some(BlockHash(commonParentHash)))
      setChainWeightByHash(oldBlock.header.hash.value, ChainWeight.zero.increase(oldBlock.header))
      val side10: BlockHeader = getBlock(number = 10, difficulty = 50, parent = commonParentHash).header
      val branch: BranchNotTaken =
        BranchNotTaken(side10, BigInt(9), commonParentHash, ChainWeight.zero.increase(side10), BigInt(50))
      // 150 against block 10's 100; skipped, block 11 left block 10 read as the head, and the branch was taken
      val side11: BlockHeader = getBlock(number = 11, difficulty = 100, parent = side10.hash.value).header
      branchResolution.extended(branch, NonEmptyList.one(side11)).map { case (longer, taken) =>
        (longer.tip, longer.difficultyAboveTheCommonBlock, taken)
      } shouldBe Some((side11, BigInt(150), false))
  }

  // ─────────────────────────────────────────────────────────────────────────
  // B-395 (beacon): the shared prefix is matched by header, and a body is needed only of a block the branch would
  // displace. After a SNAP hand-off the pivot's header, index entry and weight are written and its body is left to the
  // backfill, which on PoS fetches it last. The UnknownBranch rewind goes back to the pivot, so the batch re-served
  // starts there: with the body required, every such batch was a gap, NoChainSwitch and an ERROR, on a healthy node.
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution where a body of this node's chain is not stored" should {

    "extend the head from a batch re-served from the SNAP pivot, its body not yet stored (PoS)" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup(difficulty = 0):
      ownChain(missing = None, withoutBody = Set(4), pivot = Some(4))
      weightsStored()
      setHeaderInChain(own(3).header.parentHash.value)
      val pos = new BranchResolution(blockchainReader, Some(DesignatedHead(() => None)))
      // own 4..10, then 11..12 on the head: the pivot 4 and 5..10 are this node's, nothing is displaced
      val (verdict, logged) = capturingLogs(pos.resolveBranch(fromPivotExtendingHead))
      verdict shouldEqual ExtendsCanonicalHead(7)
      logged shouldBe empty

    "extend the head from a batch re-served from the SNAP pivot, its body not yet stored (PoW)" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None, withoutBody = Set(4), pivot = Some(4))
      weightsStored()
      setHeaderInChain(own(3).header.parentHash.value)
      // the PoW verdict for an extension that re-presents canonical blocks, as with the body there: the importer drops
      // the canonical prefix (#1592) and imports 11..12 on the head
      val (verdict, logged) = capturingLogs(branchResolution.resolveBranch(fromPivotExtendingHead))
      verdict shouldEqual NewBetterBranch(Nil)
      logged shouldBe empty

    "give the verdict it gives with the body there, where the block without one is shared with the batch" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      // block 5 is below the block the branch leaves this chain at (6): matched by its header, not displaced
      ownChain(missing = None, withoutBody = Set(5))
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NewBetterBranch(own.drop(6))
      branchResolution.notTaken(extendsFromSix).map(b => (b.commonBlock, b.commonHash)) shouldBe
        Some((BigInt(6), own(5).hash.value))

    "make no fork choice where a block the branch would displace has no body" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None, withoutBody = Set(8))
      weightsStored()
      // heavier, and own 7..10 would be handed back to the pool: block 8 cannot be
      val (verdict, logged) = capturingLogs(branchResolution.compareBranch(extendsFromSix))
      verdict shouldEqual NoChainSwitch
      logged.map(_._1) shouldBe List(Level.ERROR)

    "make no fork choice where a block the branch would displace has no body (PoS, the designated head)" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup(difficulty = 0):
      ownChain(missing = None, withoutBody = Set(8))
      weightsStored()
      val side: List[BlockHeader] = getChain(7, 12, own(5).hash.value, difficulty = 0).map(_.header)
      val batch: NonEmptyList[BlockHeader] = NonEmptyList.fromListUnsafe(own.take(6).map(_.header) ++ side)
      // the consensus layer names the side's tip: with block 8 whole, this is NewBetterBranch(own 7..10)
      val pos = new BranchResolution(blockchainReader, Some(DesignatedHead(() => Some(side.last.hash.value))))
      pos.compareBranch(batch) shouldEqual NoChainSwitch

    "still find a gap where the index entry is stored and the header is not" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None, headerMissing = Set(5))
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NoChainSwitch
      branchResolution.notTaken(extendsFromSix) shouldBe None

    "say a gap at or below the SNAP pivot at WARN, once a minute at most, and above it at ERROR" taggedAs (
      UnitTest,
      StateTest
    ) in new GapSetup:
      ownChain(missing = Some(5), pivot = Some(6))
      weightsStored()
      val (_, atThePivot) = capturingLogs {
        branchResolution.compareBranch(extendsFromSix)
        branchResolution.compareBranch(extendsFromSix)
      }
      atThePivot.map(_._1) shouldBe List(Level.WARN)
      // the same gap with no pivot at or above it
      val noPivot: GapSetup = new GapSetup:
        ownChain(missing = Some(5), pivot = Some(4))
        weightsStored()
      val (_, abovePivot) = capturingLogs(noPivot.branchResolution.compareBranch(noPivot.extendsFromSix))
      abovePivot.map(_._1) shouldBe List(Level.ERROR)
  }

  // ─────────────────────────────────────────────────────────────────────────
  // B-407: where compareBranch answers NoChainSwitch for a gap in this node's chain above the SNAP pivot, the headers
  // that are not this node's are named, so the importer can tell the escalation's feed of them. Every verdict is
  // compareBranch's, unchanged.
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution naming a branch a gap in this node's chain leaves unweighed" should {

    "name nothing where there is no gap, the verdict unchanged (the control)" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None)
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NewBetterBranch(own.drop(6))
      branchResolution.acrossAGap(extendsFromSix) shouldBe None
      branchResolution.compareBranch(lighterFromTwo) shouldEqual NoChainSwitch
      branchResolution.acrossAGap(lighterFromTwo) shouldBe None

    "name the headers from the gap where its index entry is missing too" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(5))
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NoChainSwitch
      // whether header 5 is this node's is not known: named from it, on own 4
      branchResolution.acrossAGap(extendsFromSix) shouldBe
        Some(ChainGap(BigInt(5), bodyOnly = false, NonEmptyList.fromListUnsafe(extendsFromSix.toList.drop(4))))

    "name the headers the index does not name as this node's, past a gap where only the header is missing" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None, headerMissing = Set(6))
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NoChainSwitch
      // 6 is this node's by the index: the branch is named from 7, on this node's block 6 whose header is missing
      branchResolution.acrossAGap(extendsFromSix) shouldBe
        Some(ChainGap(BigInt(6), bodyOnly = false, NonEmptyList.fromListUnsafe(extendsFromSix.toList.drop(6))))

    "name a branch that leaves this chain below the gap from where it leaves it" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(8))
      weightsStored()
      branchResolution.compareBranch(lighterFromTwo) shouldEqual NoChainSwitch
      branchResolution.acrossAGap(lighterFromTwo) shouldBe
        Some(ChainGap(BigInt(8), bodyOnly = false, NonEmptyList.fromListUnsafe(lighterFromTwo.toList.drop(2))))

    "name the branch where a block it would displace has no body" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None, withoutBody = Set(8))
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NoChainSwitch
      branchResolution.acrossAGap(extendsFromSix) shouldBe
        Some(ChainGap(BigInt(8), bodyOnly = true, NonEmptyList.fromListUnsafe(extendsFromSix.toList.drop(6))))

    "name nothing where the batch is this node's own chain across a gap of a header alone" taggedAs (
      UnitTest,
      StateTest
    ) in new GapSetup:
      ownChain(missing = None, headerMissing = Set(5))
      weightsStored()
      val ownBatch: NonEmptyList[BlockHeader] = NonEmptyList.fromListUnsafe(own.map(_.header))
      branchResolution.compareBranch(ownBatch) shouldEqual NoChainSwitch
      branchResolution.acrossAGap(ownBatch) shouldBe None

    "name nothing where the gap is at or below the SNAP pivot: the backfill brings it" taggedAs (
      UnitTest,
      StateTest
    ) in new GapSetup:
      ownChain(missing = Some(5), pivot = Some(5))
      weightsStored()
      branchResolution.compareBranch(extendsFromSix) shouldEqual NoChainSwitch
      branchResolution.acrossAGap(extendsFromSix) shouldBe None
      // the same gap above the pivot is named
      val abovePivot: GapSetup = new GapSetup:
        ownChain(missing = Some(5), pivot = Some(4))
        weightsStored()
      abovePivot.branchResolution.acrossAGap(abovePivot.extendsFromSix).map(_.firstMissing) shouldBe Some(BigInt(5))

    // B-410: a gap at or below the pivot is left to the backfill only while the backfill may still fill it

    "leave a gap at or below the SNAP pivot to the backfill while its cursors move" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(5), pivot = Some(5))
      weightsStored()
      backfillAt(BackfillCursors(header = 3, body = 2, receipt = 2))
      timed.acrossAGap(extendsFromSix) shouldBe None
      advance(StallAfter - 1.second)
      timed.acrossAGap(extendsFromSix) shouldBe None
      // the header cursor moves to 4, still below the gap: the clock starts again
      backfillAt(BackfillCursors(header = 4, body = 2, receipt = 2))
      advance(2.seconds)
      timed.acrossAGap(extendsFromSix) shouldBe None
      // a receipt moving is a move too
      advance(StallAfter - 1.second)
      backfillAt(BackfillCursors(header = 4, body = 2, receipt = 3))
      advance(2.seconds)
      timed.acrossAGap(extendsFromSix) shouldBe None
      timed.compareBranch(extendsFromSix) shouldEqual NoChainSwitch

    "name a gap at or below the SNAP pivot once the backfill's header cursor has passed it" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(5), pivot = Some(5))
      weightsStored()
      backfillAt(BackfillCursors(header = 5, body = 5, receipt = 5))
      timed.acrossAGap(extendsFromSix) shouldBe
        Some(ChainGap(BigInt(5), bodyOnly = false, NonEmptyList.fromListUnsafe(extendsFromSix.toList.drop(4))))
      // the verdict is compareBranch's, unchanged: only what the importer tells the feed moves
      timed.compareBranch(extendsFromSix) shouldEqual NoChainSwitch

    "name a body gap at or below the SNAP pivot once the backfill's body cursor, not its header cursor, has passed it" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = None, withoutBody = Set(8), pivot = Some(10))
      weightsStored()
      // every header is stored, the bodies to 7: the body of 8 is the backfill's still
      backfillAt(BackfillCursors(header = 10, body = 7, receipt = 7))
      timed.acrossAGap(extendsFromSix) shouldBe None
      backfillAt(BackfillCursors(header = 10, body = 8, receipt = 7))
      timed.acrossAGap(extendsFromSix) shouldBe
        Some(ChainGap(BigInt(8), bodyOnly = true, NonEmptyList.fromListUnsafe(extendsFromSix.toList.drop(6))))

    "name a gap at or below the SNAP pivot once the backfill has stood still for the stall timeout" taggedAs (
      UnitTest,
      StateTest,
      ConsensusTest
    ) in new GapSetup:
      ownChain(missing = Some(5), pivot = Some(5))
      weightsStored()
      backfillAt(BackfillCursors(header = 3, body = 2, receipt = 2))
      timed.acrossAGap(extendsFromSix) shouldBe None
      advance(StallAfter - 1.nanosecond)
      timed.acrossAGap(extendsFromSix) shouldBe None
      advance(1.nanosecond)
      timed.acrossAGap(extendsFromSix).map(_.firstMissing) shouldBe Some(BigInt(5))
      timed.acrossAGap(extendsFromSix).map(_.firstMissing) shouldBe Some(BigInt(5))
      // the backfill moves again: left to it again
      backfillAt(BackfillCursors(header = 4, body = 2, receipt = 2))
      timed.acrossAGap(extendsFromSix) shouldBe None
  }

  /** The events BranchResolution logs at WARN and above while `body` runs: the level and the message. */
  private def capturingLogs[A](body: => A): (A, List[(Level, String)]) =
    val logger = LoggerFactory.getLogger(classOf[BranchResolution]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    val level = logger.getLevel
    logger.setLevel(Level.WARN)
    logger.addAppender(appender)
    try
      val result = body
      (result, appender.list.asScala.toList.map(e => (e.getLevel, e.getFormattedMessage)))
    finally
      logger.detachAppender(appender)
      logger.setLevel(level)

  /** This node's chain 1..10 (`difficulty` each) over `base`, the head at 10, and batches against it. */
  class GapSetup(difficulty: BigInt = 100) extends BranchResolutionTestSetupImpl:
    val base: ByteString = randomHash()
    val own: List[Block] = getChain(1, 10, base, difficulty = difficulty)

    /** Own 1..6, then a branch 7..12 from own 6 (difficulty 100 each): heavier than own 7..10. */
    val extendsFromSix: NonEmptyList[BlockHeader] =
      NonEmptyList.fromListUnsafe(own.take(6).map(_.header) ++ getChain(7, 12, own(5).hash.value).map(_.header))

    /** Own 1..2, then a branch 3..10 from own 2 of difficulty 90 each: 720 against own 3..10's 800. */
    val lighterFromTwo: NonEmptyList[BlockHeader] =
      NonEmptyList.fromListUnsafe(
        own.take(2).map(_.header) ++ getChain(3, 10, own(1).hash.value, difficulty = 90).map(_.header)
      )

    /** Own 4..10, then 11..12 on the head (`difficulty` each): a batch re-served from block 4. */
    val fromPivotExtendingHead: NonEmptyList[BlockHeader] =
      NonEmptyList.fromListUnsafe(
        own.drop(3).map(_.header) ++ getChain(11, 12, own.last.hash.value, difficulty = difficulty).map(_.header)
      )

    /** `missing`: no index entry and no header; `headerMissing`: the index entry and no header; `withoutBody`: the
      * header and its index entry, and no body. The SNAP pivot is `pivot`.
      */
    def ownChain(
        missing: Option[BigInt],
        withoutBody: Set[BigInt] = Set.empty,
        headerMissing: Set[BigInt] = Set.empty,
        pivot: Option[BigInt] = None
    ): Unit =
      (() => blockchainReader.getBestBlockNumber).expects().anyNumberOfTimes().returning(BigInt(10))
      (() => blockchainReader.getSnapSyncPivotBlock).expects().anyNumberOfTimes().returning(pivot)
      own.foreach { block =>
        val number = block.number.value
        val whole = !missing.contains(number) && !withoutBody(number) && !headerMissing(number)
        blockchainReader.getBlockByNumber
          .expects(*, number)
          .anyNumberOfTimes()
          .returning(Some(block).filter(_ => whole))
        // read by compareBranch where the block is not whole, and by acrossAGap above the headers matched (B-407)
        blockchainReader.getHashByBlockNumber
          .expects(*, number)
          .anyNumberOfTimes()
          .returning(Some(block.hash).filterNot(_ => missing.contains(number)))
        if !whole then
          blockchainReader.getBlockHeaderByHash
            .expects(block.hash)
            .anyNumberOfTimes()
            .returning(Some(block.header).filter(_ => withoutBody(number)))
      }

    def weightsStored(): Unit =
      blockchainReader.getChainWeightByHash.expects(*).anyNumberOfTimes().returning(Some(ChainWeight.zero))

    /** The backfill's cursors as read, and the clock its progress is timed by (B-410). */
    private var cursors: BackfillCursors = BackfillCursors(0, 0, 0)
    private var now: Long = 0L
    (() => blockchainReader.getBackfillCursors).expects().anyNumberOfTimes().onCall(() => cursors)

    def backfillAt(at: BackfillCursors): Unit = cursors = at
    def advance(by: FiniteDuration): Unit = now += by.toNanos

    val StallAfter: FiniteDuration = 10.minutes

    /** Resolution with the stall timeout `StallAfter`, on the clock above. */
    lazy val timed: BranchResolution = new BranchResolution(blockchainReader, None, StallAfter, () => now)

    def weightsNotStored(): Unit =
      blockchainReader.getChainWeightByHash.expects(*).anyNumberOfTimes().returning(None)

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

  // ─────────────────────────────────────────────────────────────────────────
  // ETC/Mordor: A POST-REWIND BATCH THROUGH THE WIRE DECODER (forge review of #1432).
  //
  // After a rewind the fetcher re-serves from below the head, so a batch re-presents canonical blocks before it
  // extends the head. compareBranch recognises that prefix by header EQUALITY between the stored header (boopickle)
  // and the one just decoded off the wire (RLP). If the two ever disagreed, the prefix would be taken for displaced
  // blocks and MESS would judge a harmless extension as a reorg. Pinned on the header shapes ETC actually carries —
  // 15 fields, and 16 with the Olympia base fee — with no designated head (SyncController builds none without a
  // terminal-total-difficulty) and MESS active. Real storage, no mocks: equality through the storage codec is the point.
  // ─────────────────────────────────────────────────────────────────────────

  "BranchResolution on an ETC chain (no designated head, MESS on)" should {

    "take a post-rewind batch of 15- and 16-field wire headers as a plain extension: NewBetterBranch(Nil)" taggedAs (
      UnitTest,
      StateTest
    ) in new EtcWireSetup:
      val resolution = new BranchResolution(blockchainReader)
      resolution.messConfig = Some(MESSConfig(enabled = true, activationBlock = Some(BigInt(1))))
      resolution.followsConsensusLayer shouldBe false

      // The re-presented prefix carries both header shapes (35..80: 15 fields; 81..100: 16), the extension 16.
      postRewindBatch.map(_.header.extraFields).collect { case HefEmpty => 15 } should not be empty
      postRewindBatch.map(_.header.extraFields).collect { case _: HefPostOlympia => 16 } should not be empty

      // Nothing displaced (Nil), so MESS is never asked; the whole batch goes on to consensus, as before #1432.
      resolution.resolveBranch(wireHeaders(postRewindBatch)) shouldEqual NewBetterBranch(Nil)
  }

  /** ETC-shaped PoW chain in real ephemeral storage: canonical 1..100, head N = 100, headers of 15 RLP fields below an
    * Olympia-style boundary at 81 and 16 (base fee) from it; the post-rewind batch is canonical N-65..N plus N+1..N+10.
    */
  class EtcWireSetup extends EphemBlockchainTestSetup:
    import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*

    private val genesisWeight = ChainWeight.totalDifficultyOnly(BlockHelpers.genesis.header.difficulty.value)
    blockchainWriter.save(BlockHelpers.genesis, Nil, genesisWeight, saveAsBestBlock = true)

    /** PoW difficulty kept; an 8-byte nonce (the strict decoder's size); base fee from block 81. */
    private def etcBlock(block: Block): Block =
      val nonce = ByteString(java.nio.ByteBuffer.allocate(8).putLong(block.number.value.toLong).array())
      val extra = if block.number.value >= 81 then HefPostOlympia(BigInt(1000000000)) else HefEmpty
      block.copy(header = block.header.copy(nonce = nonce, extraFields = extra))

    val canonical: List[Block] = BlockHelpers.generateChain(100, BlockHelpers.genesis, etcBlock)
    canonical.foldLeft(genesisWeight) { (w, b) =>
      val next = w.increase(b.header)
      blockchainWriter.save(b, Nil, next, saveAsBestBlock = true)
      next
    }

    val postRewindBatch: List[Block] =
      canonical.takeRight(66) ++ BlockHelpers.generateChain(10, canonical.last, etcBlock)

    /** RLP-encoded by the sender, decoded by us — the strict field-count/size decoder. */
    def wireHeaders(blocks: List[Block]): NonEmptyList[BlockHeader] =
      NonEmptyList.fromListUnsafe(blocks.map(_.header.toBytes.toBlockHeader))

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

    /** Expect blockchainReader.getBlockHeaderByHash(commonParentHash) once, returning nothing: the header not stored.
      */
    def expectAncestorHeaderMissing(): Unit =
      blockchainReader.getBlockHeaderByHash.expects(BlockHash(commonParentHash)).returning(None)

    /** Expect blockchainReader.getBlockHeaderByHash(commonParentHash) once, returning a header with `ts`. */
    def expectAncestorHeader(ts: Long = ancestorTs): Unit =
      blockchainReader.getBlockHeaderByHash
        .expects(BlockHash(commonParentHash))
        .returning(Some(defaultHeader.copy(number = BlockNumber(9), unixTimestamp = Timestamp(ts))))
