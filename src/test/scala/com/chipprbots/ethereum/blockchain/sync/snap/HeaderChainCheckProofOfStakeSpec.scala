package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.consensus.engine.TransitionBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderNumberError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.*

/** The SNAP header download's check on chains with a terminal total difficulty ([[HeaderChainCheck.ProofOfStake]]),
  * against real Ethereum mainnet, Sepolia and Ephemery headers and the configs block import validates them with: both
  * merges, with the transition read from the parent's total difficulty; the first block of every timestamp fork, with
  * its parent; a run of Amsterdam-era Sepolia headers; Ephemery's difficulty-1 genesis under a terminal total
  * difficulty of 0. Then what it rejects, and its dispatch by chain.
  */
class HeaderChainCheckProofOfStakeSpec extends AnyFlatSpec with Matchers:

  import PosHeaderFixtures.*

  private def mainnetCheck = new HeaderChainCheck.ProofOfStake(mainnetConfig, mainnetConfig.terminalTotalDifficulty.get)
  private def sepoliaCheck = new HeaderChainCheck.ProofOfStake(sepoliaConfig, sepoliaConfig.terminalTotalDifficulty.get)
  private def ephemeryCheck =
    new HeaderChainCheck.ProofOfStake(ephemery165Config, ephemery165Config.terminalTotalDifficulty.get)

  private def td(value: BigInt): ChainWeight = ChainWeight.totalDifficultyOnly(value)

  /** The parent's total difficulty for each pair, as the download stores it: below the terminal block, the terminal
    * block's minus the difficulties in between; from it on, the terminal block's (proof-of-stake blocks add 0).
    */
  private def parentTd(parent: BlockHeader, terminalBlock: BigInt, terminalTd: BigInt, chain: IndexedSeq[BlockHeader]) =
    if parent.number.value >= terminalBlock then terminalTd
    else
      chain
        .filter(h => h.number.value > parent.number.value && h.number.value <= terminalBlock)
        .foldLeft(terminalTd)((td, h) => td - h.difficulty.value)

  private def checkPairs(
      check: HeaderChainCheck,
      chain: IndexedSeq[BlockHeader],
      terminalBlock: BigInt,
      terminalTd: BigInt
  ): Seq[(BigInt, Either[BlockHeaderError, BlockHeaderValid])] =
    PowHeaderFixtures.pairs(chain).map { case (parent, child) =>
      child.number.value -> check
        .checkLinked(child, parent, td(parentTd(parent, terminalBlock, terminalTd, chain)), false)
    }

  "HeaderChainCheck.ProofOfStake" should "accept mainnet's merge: the terminal block below TTD, then proof of stake" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    at(mainnet, MainnetTerminalBlock).hashAsHexString shouldBe
      "55b11b918355b1ef9c5db810302ebad0bf2544255b530cdce90674d5887bb286"
    at(mainnet, MainnetTerminalBlock).difficulty.value should be > BigInt(0)
    at(mainnet, MainnetTerminalBlock + 1).difficulty shouldBe Difficulty.Zero
    val ttd = mainnetConfig.terminalTotalDifficulty.get
    // The terminal block is the first at or above the terminal total difficulty; its parent is below it.
    MainnetTerminalTd should be >= ttd
    (MainnetTerminalTd - at(mainnet, MainnetTerminalBlock).difficulty.value) should be < ttd

    val merge = mainnet.filter(h => (15_537_392 to 15_537_395).contains(h.number.value.toInt))
    merge.size shouldBe 4
    checkPairs(mainnetCheck, merge, MainnetTerminalBlock, MainnetTerminalTd) shouldBe
      Seq(15_537_393, 15_537_394, 15_537_395).map(n => BigInt(n) -> Right(BlockHeaderValid))
  }

  it should "accept Sepolia's merge: the terminal block below TTD, then proof of stake" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val ttd = sepoliaConfig.terminalTotalDifficulty.get
    SepoliaTerminalTd should be >= ttd
    (SepoliaTerminalTd - at(sepolia, SepoliaTerminalBlock).difficulty.value) should be < ttd
    at(sepolia, SepoliaTerminalBlock + 1).difficulty shouldBe Difficulty.Zero

    val merge = sepolia.filter(h => (1_450_407 to 1_450_410).contains(h.number.value.toInt))
    merge.size shouldBe 4
    checkPairs(sepoliaCheck, merge, SepoliaTerminalBlock, SepoliaTerminalTd).filter(_._2.isLeft) shouldBe empty
  }

  // Every proof-of-stake header the download stores goes through the import validator's post-merge rules, so a rule that
  // rejects a real header stalls SNAP there. The rules change at the timestamp forks: withdrawals (Shanghai), blob gas and
  // the beacon root (Cancun), requests (Prague), the blob fee floor (Osaka), the blob schedule (BPO1, BPO2), the block
  // access list and slot (Amsterdam).
  it should "accept the first mainnet block of Shanghai, Cancun, Prague, Osaka, BPO1 and BPO2 on its real parent" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val boundaries = mainnet.filter(_.number.value > 15_537_395)
    val results = checkPairs(mainnetCheck, boundaries, MainnetTerminalBlock, MainnetTerminalTd)
    results.map(_._1) shouldBe Seq(17_034_870, 19_426_587, 22_431_084, 23_935_694, 23_975_778, 24_179_383).map(
      BigInt(_)
    )
    results.filter(_._2.isLeft) shouldBe empty
  }

  it should "accept the first Sepolia block of Shanghai through Amsterdam on its real parent" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val boundaries = sepolia.filter(_.number.value > 1_450_410)
    val results = checkPairs(sepoliaCheck, boundaries, SepoliaTerminalBlock, SepoliaTerminalTd)
    results.map(_._1) shouldBe
      Seq(2_990_908, 5_187_023, 7_836_331, 9_408_577, 9_456_501, 9_504_747, 11_856_337).map(BigInt(_))
    results.filter(_._2.isLeft) shouldBe empty
    at(sepolia, 11_856_337).extraFields shouldBe a[HefPostAmsterdam]
    at(sepolia, 11_856_336).extraFields shouldBe a[HefPostPrague]
  }

  it should "accept a run of 70 consecutive Amsterdam-era Sepolia headers" taggedAs (UnitTest, ConsensusTest) in {
    sepoliaRun.size shouldBe 70
    checkPairs(sepoliaCheck, sepoliaRun, SepoliaTerminalBlock, SepoliaTerminalTd).filter(_._2.isLeft) shouldBe empty
  }

  it should "accept Ephemery's first blocks on its difficulty-1 genesis under a terminal total difficulty of 0" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val genesis = ephemery165.head
    genesis.hashAsHexString shouldBe "9485bbe8e32e278194666d249590c9167057edeabaecc62e7a68355f68305ecc"
    genesis.difficulty shouldBe Difficulty(BigInt(1))
    ephemery165Config.terminalTotalDifficulty shouldBe Some(BigInt(0))
    // The genesis weight is its difficulty; every block after it adds 0.
    checkPairs(ephemeryCheck, ephemery165, BigInt(0), genesis.difficulty.value) shouldBe
      Seq(1, 2, 3).map(n => BigInt(n) -> Right(BlockHeaderValid))
  }

  it should "reject a proof-of-work header after a proof-of-stake parent" taggedAs (UnitTest, ConsensusTest) in {
    val parent = at(mainnet, MainnetTerminalBlock + 1)
    val powAfterPos = at(mainnet, MainnetTerminalBlock + 2).copy(difficulty = Difficulty(BigInt(1)))
    mainnetCheck.checkLinked(powAfterPos, parent, td(MainnetTerminalTd), checkSeal = false) shouldBe
      Left(HeaderDifficultyError)
    // Also without the weight (go-ethereum's rule reads the difficulties alone).
    mainnetCheck.check(powAfterPos, parent, checkSeal = false) shouldBe Left(HeaderDifficultyError)
  }

  it should "reject a proof-of-work header whose parent is at or above the terminal total difficulty" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val ttd = mainnetConfig.terminalTotalDifficulty.get
    val terminal = at(mainnet, MainnetTerminalBlock)
    val parent = at(mainnet, MainnetTerminalBlock - 1)
    // The real terminal block, on a parent whose weight already reached the terminal total difficulty.
    mainnetCheck.checkLinked(terminal, parent, td(ttd), checkSeal = false) shouldBe Left(HeaderDifficultyError)
  }

  it should "reject a proof-of-stake header whose parent is below the terminal total difficulty" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = at(mainnet, MainnetTerminalBlock)
    val firstPos = at(mainnet, MainnetTerminalBlock + 1)
    // The real first proof-of-stake block, on the terminal block weighed as if it had not reached the terminal TD.
    mainnetCheck.checkLinked(
      firstPos,
      parent,
      td(MainnetTerminalTd - parent.difficulty.value),
      checkSeal = false
    ) shouldBe
      Left(HeaderDifficultyError)
  }

  it should "reject a proof-of-work header whose number does not follow its parent's" taggedAs UnitTest in {
    val parent = at(mainnet, MainnetTerminalBlock - 1)
    val skipped = at(mainnet, MainnetTerminalBlock).copy(number = BlockNumber(MainnetTerminalBlock + 1))
    mainnetCheck.checkLinked(skipped, parent, td(BigInt(1)), checkSeal = false) shouldBe Left(HeaderNumberError)
  }

  it should "reject a proof-of-stake header with a nonce, or without its fork's fields" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = at(sepolia, 11_856_336)
    val amsterdam = at(sepolia, 11_856_337)
    val withNonce = amsterdam.copy(nonce = ByteString(Array.fill[Byte](8)(1)))
    sepoliaCheck.checkLinked(withNonce, parent, td(SepoliaTerminalTd), checkSeal = false).isLeft shouldBe true
    sepoliaCheck.validateHeaderOnly(withNonce).isLeft shouldBe true

    // An Amsterdam-era header with the Prague layout (no block access list hash, no slot).
    val pragueShaped = amsterdam.extraFields match
      case HefPostAmsterdam(fee, withdrawals, blobGasUsed, excessBlobGas, beaconRoot, requests, _, _) =>
        amsterdam.copy(extraFields = HefPostPrague(fee, withdrawals, blobGasUsed, excessBlobGas, beaconRoot, requests))
      case other => fail(s"expected an Amsterdam header, got $other")
    sepoliaCheck.checkLinked(pragueShaped, parent, td(SepoliaTerminalTd), checkSeal = false).isLeft shouldBe true
    sepoliaCheck.validateHeaderOnly(pragueShaped).isLeft shouldBe true
  }

  // A post-merge header has no seal: one with another state root on its real parent passes every rule. What the download
  // stores is authenticated by its link to the pivot hash the consensus layer chose (PivotAnchorSpec, the ancestry walk
  // in PivotHeaderBootstrapSpec), not by these rules.
  it should "pass a header with another state root on its real parent: the rules check shape, the hash authenticates" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = sepoliaRun(9)
    val altered = sepoliaRun(10).copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x5a))))
    sepoliaCheck.checkLinked(altered, parent, td(SepoliaTerminalTd), checkSeal = false) shouldBe Right(BlockHeaderValid)
    altered.hash should not be sepoliaRun(10).hash
    sepoliaRun(11).parentHash should not be altered.hash
  }

  it should "pass the header-only rules for a real proof-of-stake header, and check no proof-of-work rule" taggedAs UnitTest in {
    sepoliaCheck.validateHeaderOnly(at(sepolia, 11_856_337)) shouldBe Right(BlockHeaderValid)
    mainnetCheck.validateHeaderOnly(at(mainnet, 24_179_383)) shouldBe Right(BlockHeaderValid)
    mainnetCheck.validateHeaderOnly(at(mainnet, MainnetTerminalBlock)) shouldBe Right(BlockHeaderValid)
  }

  it should "check no seal, validate the chain, and trust the pinned pivot" taggedAs UnitTest in {
    mainnetCheck.sealPositions(sepoliaRun.take(5), BigInt(0)) shouldBe Seq.fill(5)(false)
    mainnetCheck.validatesChain shouldBe true
    mainnetCheck.trustsPinnedPivot shouldBe true
  }

  "HeaderChainCheck.forChain" should "give chains with a terminal total difficulty the post-merge check" taggedAs UnitTest in {
    Seq(mainnetConfig, sepoliaConfig, ephemery165Config).foreach { config =>
      Seq(
        TransitionBlockHeaderValidator,
        PoWBlockHeaderValidator,
        new Mocks.MockValidatorsAlwaysSucceed().blockHeaderValidator
      )
        .foreach { validator =>
          HeaderChainCheck.forChain(config, validator) match
            case pos: HeaderChainCheck.ProofOfStake =>
              pos.terminalTotalDifficulty shouldBe config.terminalTotalDifficulty.get
            case other => fail(s"expected the post-merge check, got $other")
        }
    }
  }

  it should "leave proof-of-work chains as they were" taggedAs UnitTest in {
    HeaderChainCheck
      .forChain(PowHeaderFixtures.mordorConfig, PoWBlockHeaderValidator) shouldBe a[HeaderChainCheck.Validated]
    HeaderChainCheck
      .forChain(PowHeaderFixtures.etcConfig, PoWBlockHeaderValidator) shouldBe a[HeaderChainCheck.Validated]
    HeaderChainCheck.forChain(
      PowHeaderFixtures.mordorConfig,
      new Mocks.MockValidatorsAlwaysSucceed().blockHeaderValidator
    ) shouldBe HeaderChainCheck.LinkOnly
    HeaderChainCheck.forChain(PowHeaderFixtures.mordorConfig, PoWBlockHeaderValidator).trustsPinnedPivot shouldBe false
    HeaderChainCheck.LinkOnly.trustsPinnedPivot shouldBe false
  }

  "HeaderChainCheck.headerHoldRequired" should "require the header hold wherever the download anchors the pivot" taggedAs UnitTest in {
    HeaderChainCheck.headerHoldRequired(sepoliaConfig, sepoliaCheck) shouldBe true
    HeaderChainCheck.headerHoldRequired(mainnetConfig, mainnetCheck) shouldBe true
    HeaderChainCheck.headerHoldRequired(PowHeaderFixtures.mordorConfig, HeaderChainCheck.LinkOnly) shouldBe true
    val off = SNAPSyncConfig(chainDownloadEnabled = false, deferChainBackfillUntilStateComplete = false)
    val (held, ignored) =
      HeaderChainCheck.enforceHeaderHold(off, HeaderChainCheck.headerHoldRequired(sepoliaConfig, sepoliaCheck))
    held.chainDownloadEnabled shouldBe true
    held.deferChainBackfillUntilStateComplete shouldBe true
    ignored shouldBe Seq("chain-download-enabled", "defer-chain-backfill-until-state-complete")
  }
