package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** #1654: the floor that replaces the fixed 10^13-per-block anchor bound, and the exactness classification the timed TD
  * calibration consults before writing anything.
  */
class HeadChainWeightSpec extends AnyFlatSpec with Matchers:

  // Mordor: genesis difficulty 0x20000; the source node's head TD at ~15M blocks (#1654, `before=`).
  private val MordorGenesisWeight = BigInt(131072)
  private val MordorBlock = BigInt(15000000)
  private val MordorHeadTD = BigInt("174823251167868")
  // ETC mainnet genesis difficulty.
  private val EtcGenesisWeight = BigInt("17179869184")

  "HeadChainWeight.isPlausible" should "accept a real Mordor-scale weight that the 10^13-per-block floor rejected" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    HeadChainWeight.isPlausible(MordorBlock, MordorHeadTD, MordorGenesisWeight) shouldBe true
    // The replaced bound: 15M x 10^13 = 1.5e20, six orders of magnitude above Mordor's real TD.
    MordorHeadTD should be < MordorBlock * BigInt("10000000000000")
  }

  it should "reject the block-number and genesis proxies SNAP finalization can store, on Mordor and ETC" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // BLOCK_NUMBER_PROXY is `pivot.max(genesisWeight)`.
    HeadChainWeight.isPlausible(MordorBlock, MordorBlock.max(MordorGenesisWeight), MordorGenesisWeight) shouldBe false
    HeadChainWeight.isPlausible(MordorBlock, MordorGenesisWeight, MordorGenesisWeight) shouldBe false
    val etcPivot = BigInt(20000000)
    HeadChainWeight.isPlausible(etcPivot, etcPivot.max(EtcGenesisWeight), EtcGenesisWeight) shouldBe false
    HeadChainWeight.isPlausible(etcPivot, etcPivot, EtcGenesisWeight) shouldBe false
  }

  it should "sit exactly on the minimum-difficulty bound: genesis weight plus 131072 per block" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    HeadChainWeight.floor(0, MordorGenesisWeight) shouldBe MordorGenesisWeight
    HeadChainWeight.floor(10, MordorGenesisWeight) shouldBe MordorGenesisWeight + BigInt(10) * BigInt(131072)
    val bound = HeadChainWeight.floor(MordorBlock, MordorGenesisWeight)
    HeadChainWeight.isPlausible(MordorBlock, bound, MordorGenesisWeight) shouldBe true
    HeadChainWeight.isPlausible(MordorBlock, bound - 1, MordorGenesisWeight) shouldBe false
  }

  "HeadChainWeight.classify" should "call a weight exact when it is a plausible parent's weight plus its difficulty" taggedAs (
    UnitTest,
    SyncTest
  ) in new EphemBlockchainTestSetup:
    val (parent, head) = storeParentAndHead(this, parentTD = MordorHeadTD - BigInt(9000000), headDifficulty = 9000000)
    blockchainWriter.storeChainWeight(head.hash, ChainWeight.totalDifficultyOnly(MordorHeadTD)).commit()
    HeadChainWeight.classify(blockchainReader, head) shouldBe
      HeadChainWeight.Status.Exact(ChainWeight.totalDifficultyOnly(MordorHeadTD))
    HeadChainWeight.classify(blockchainReader, parent) shouldBe HeadChainWeight.Status.NoExact

  it should "call a zero-difficulty (post-merge) link exact when the parent's weight is plausible" taggedAs (
    UnitTest,
    SyncTest
  ) in new EphemBlockchainTestSetup:
    // A post-merge block adds 0, so its weight equals its parent's: the frozen terminal TD (Sepolia's, 1.7e16).
    val frozenTD: BigInt = BigInt("17000018015853232")
    val (_, head) = storeParentAndHead(this, parentTD = frozenTD, headDifficulty = 0)
    blockchainWriter.storeChainWeight(head.hash, ChainWeight.totalDifficultyOnly(frozenTD)).commit()
    HeadChainWeight.classify(blockchainReader, head) shouldBe
      HeadChainWeight.Status.Exact(ChainWeight.totalDifficultyOnly(frozenTD))

  it should "derive the exact weight when the stored one does not follow from a plausible parent" taggedAs (
    UnitTest,
    SyncTest
  ) in new EphemBlockchainTestSetup:
    val (_, head) = storeParentAndHead(this, parentTD = MordorHeadTD - BigInt(9000000), headDifficulty = 9000000)
    val estimate: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("174823332424315"))
    blockchainWriter.storeChainWeight(head.hash, estimate).commit()
    HeadChainWeight.classify(blockchainReader, head) shouldBe
      HeadChainWeight.Status.Derivable(Some(estimate), ChainWeight.totalDifficultyOnly(MordorHeadTD))

  it should "find nothing exact when the parent's weight is a block-number proxy" taggedAs (UnitTest, SyncTest) in
    new EphemBlockchainTestSetup:
      val (_, head) = storeParentAndHead(this, parentTD = MordorBlock - 1, headDifficulty = 9000000)
      blockchainWriter
        .storeChainWeight(head.hash, ChainWeight.totalDifficultyOnly(MordorBlock - 1 + BigInt(9000000)))
        .commit()
      HeadChainWeight.classify(blockchainReader, head) shouldBe HeadChainWeight.Status.NoExact

  it should "treat genesis as exact only at its own difficulty" taggedAs (UnitTest, SyncTest) in
    new EphemBlockchainTestSetup:
      val genesis = Fixtures.Blocks.Genesis.header
      val exact: ChainWeight = ChainWeight.totalDifficultyOnly(genesis.difficulty.value)
      blockchainWriter.storeBlockHeader(genesis).commit()
      HeadChainWeight.classify(blockchainReader, genesis) shouldBe HeadChainWeight.Status.Derivable(None, exact)
      blockchainWriter.storeChainWeight(genesis.hash, exact).commit()
      HeadChainWeight.classify(blockchainReader, genesis) shouldBe HeadChainWeight.Status.Exact(exact)

  /** Store a parent at block 15M-1 (whose own parent is unknown) with `parentTD`, and its child at 15M; return both.
    * The child's weight is left to the caller.
    */
  private def storeParentAndHead(
      setup: EphemBlockchainTestSetup,
      parentTD: BigInt,
      headDifficulty: Long
  ): (BlockHeader, BlockHeader) =
    import setup.blockchainWriter
    val parent = Fixtures.Blocks.Genesis.header.copy(
      number = BlockNumber(MordorBlock - 1),
      parentHash = BlockHash(ByteString(Array.fill(32)(0xee.toByte))),
      difficulty = Difficulty(BigInt(9000000)),
      extraData = ByteString(Array[Byte](1))
    )
    val head = Fixtures.Blocks.Genesis.header.copy(
      number = BlockNumber(MordorBlock),
      parentHash = parent.hash,
      difficulty = Difficulty(BigInt(headDifficulty)),
      extraData = ByteString(Array[Byte](2))
    )
    blockchainWriter.storeBlockHeader(parent).commit()
    blockchainWriter.storeBlockHeader(head).commit()
    blockchainWriter.storeChainWeight(parent.hash, ChainWeight.totalDifficultyOnly(parentTD)).commit()
    (parent, head)

// scalastyle:on magic.number
