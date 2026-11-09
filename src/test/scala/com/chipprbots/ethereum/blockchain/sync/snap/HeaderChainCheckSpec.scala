package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.consensus.engine.TransitionBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderUnexpectedError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.*

/** The SNAP header download's per-header check ([[HeaderChainCheck]]) against real mined Mordor headers, with the
  * block-import validator (`PoWBlockHeaderValidator`) and the shipped Mordor config; its seal sampling; its dispatch by
  * chain.
  */
class HeaderChainCheckSpec extends AnyFlatSpec with Matchers:

  import PowHeaderFixtures.*

  private def validated(window: Int = 100, random: Int => Int = _ => 0): HeaderChainCheck.Validated =
    new HeaderChainCheck.Validated(PoWBlockHeaderValidator, mordorConfig, window, random)

  private val otherRoot = TrieRoot(ByteString(Array.fill(32)(7.toByte)))
  private def nonce(n: Int): ByteString = ByteString(
    BigInt(n).toByteArray.reverse.padTo(8, 0.toByte).reverse.takeRight(8)
  )

  private def checkRun(check: HeaderChainCheck, headers: IndexedSeq[BlockHeader], seal: Boolean) =
    (1 until headers.size).map(i => headers(i).number.value -> check.check(headers(i), headers(i - 1), seal))

  "HeaderChainCheck.Validated" should "accept real mined Mordor headers from genesis, every seal checked" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    fromGenesis.head.number.value shouldBe 0
    fromGenesis.head.hashAsHexString shouldBe "a68ebde7932eccb177d38d55dcc6461a019dd795a681e59b5a3e4f3a7259a3f1"
    checkRun(validated(), fromGenesis, seal = true).filter(_._2.isLeft) shouldBe empty
  }

  it should "accept real Mordor headers across ECIP-1099 (epoch length 30000 -> 60000), every seal checked" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    acrossEcip1099.map(_.number.value) should contain(BigInt(2_520_000))
    checkRun(validated(), acrossEcip1099, seal = true).filter(_._2.isLeft) shouldBe empty
  }

  it should "accept recent real Mordor headers, every seal checked" taggedAs (UnitTest, ConsensusTest) in {
    checkRun(validated(), recent, seal = true).filter(_._2.isLeft) shouldBe empty
  }

  // The header download validates every header from genesis with the import validator, so a rule that rejects a real
  // header anywhere on the chain stalls SNAP there. Fork boundaries are where the rules change (difficulty formula, bomb
  // delays, gas, extra data); the seal is the same Ethash at every one but ECIP-1099, covered above.
  it should "accept real Mordor headers on either side of every Mordor fork (the rules other than the seal)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val boundaryPairs = pairs(mordorForkBoundaries)
    boundaryPairs.size shouldBe 10
    boundaryPairs
      .map { case (p, c) => c.number.value -> validated().check(c, p, checkSeal = false) }
      .filter(_._2.isLeft) shouldBe empty
  }

  it should "accept real ETC mainnet headers on either side of every ETC fork and across the DAO range" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val etc = new HeaderChainCheck.Validated(PoWBlockHeaderValidator, etcConfig)
    etcForkBoundaries.find(_.number.value == 1_920_000).map(_.hash.value) shouldBe
      etcConfig.daoForkConfig.map(_.forkBlockHash)
    val boundaryPairs = pairs(etcForkBoundaries)
    boundaryPairs.size shouldBe 38
    boundaryPairs
      .map { case (p, c) => c.number.value -> etc.check(c, p, checkSeal = false) }
      .filter(_._2.isLeft) shouldBe empty
  }

  it should "check real ETC mainnet seals across ECIP-1099 (11,700,000)" taggedAs (UnitTest, ConsensusTest) in {
    val etc = new HeaderChainCheck.Validated(PoWBlockHeaderValidator, etcConfig)
    val ecip1099 = pairs(etcForkBoundaries).filter { case (_, c) =>
      (11_699_999 to 11_700_001).contains(c.number.value.toInt)
    }
    ecip1099.size shouldBe 2
    ecip1099
      .map { case (p, c) => c.number.value -> etc.check(c, p, checkSeal = true) }
      .filter(_._2.isLeft) shouldBe empty
  }

  it should "reject a header with difficulty 1 against its real parent, seal checked or not" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = fromGenesis(99)
    val cheap = sealFor(fromGenesis(100).copy(difficulty = Difficulty(BigInt(1)), stateRoot = otherRoot))
    // The difficulty is not the one the parent implies; that rule runs before the seal.
    validated().check(cheap, parent, checkSeal = false) shouldBe Left(HeaderDifficultyError)
    validated().check(cheap, parent, checkSeal = true) shouldBe Left(HeaderDifficultyError)
    // Alone, the seal check cannot evaluate difficulty 1 (its target, 2^256, does not fit its 32 bytes): the header is
    // rejected with the reason rather than thrown into the caller.
    validated().validateHeaderOnly(cheap).left.toOption.get shouldBe a[HeaderUnexpectedError]
  }

  it should "reject a cheaply mined low-difficulty header against its real parent, though it passes alone" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = fromGenesis(99)
    // Difficulty 2: about one nonce in two meets it.
    val cheap = (0 until 64).iterator
      .map(n =>
        sealFor(fromGenesis(100).copy(difficulty = Difficulty(BigInt(2)), stateRoot = otherRoot, nonce = nonce(n)))
      )
      .find(h => validated().validateHeaderOnly(h).isRight)
      .getOrElse(fail("no nonce in 64 met difficulty 2"))
    // The header-only check (the pivot gate) passes it: its seal meets its own difficulty.
    validated().validateHeaderOnly(cheap) shouldBe Right(BlockHeaderValid)
    // Against the parent it fails, which is what the header download checks on every header.
    validated().check(cheap, parent, checkSeal = true) shouldBe Left(HeaderDifficultyError)
  }

  it should "reject a header with a fabricated state root on its real parent when its seal is checked" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = fromGenesis(199)
    val altered = fromGenesis(200).copy(stateRoot = TrieRoot(ByteString(Array.fill(32)(9.toByte))))
    // Every other rule holds (it is the real header with another state root), so only the seal can tell.
    validated().check(altered, parent, checkSeal = false) shouldBe Right(BlockHeaderValid)
    validated().check(altered, parent, checkSeal = true) shouldBe Left(HeaderPoWError)
  }

  it should "check the seal on one random position per window, the last header, and every header near the target" taggedAs UnitTest in {
    val run = fromGenesis.slice(1, 251) // blocks 1..250
    // Positions the CSPRNG would pick, as a fixed sequence: window 0 -> 7, window 1 -> 99, window 2 -> 3.
    val picks = Iterator(7, 99, 3)
    val check = validated(
      window = 100,
      random = bound =>
        bound shouldBe 100; picks.next()
    )
    val seal = check.sealPositions(run, target = BigInt(10_000))
    seal.size shouldBe run.size
    seal.zipWithIndex.collect { case (true, i) => i } shouldBe Seq(7, 199, 203, 249)

    // Within one window of the target every header is checked.
    val nearTarget = validated(window = 100, random = _ => 0).sealPositions(run, target = BigInt(260))
    nearTarget.zipWithIndex.collect { case (true, i) => i } shouldBe
      (Seq(0, 100, 200) ++ run.indices.filter(i => run(i).number.value > 160)).distinct.sorted
  }

  it should "validate unsampled headers against every rule but the seal (a altered seal on a valid header passes)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val parent = fromGenesis(49)
    val badSeal = fromGenesis(50).copy(nonce = ByteString(Array.fill(8)(1.toByte)))
    validated().check(badSeal, parent, checkSeal = false) shouldBe Right(BlockHeaderValid)
    validated().check(badSeal, parent, checkSeal = true) shouldBe Left(HeaderPoWError)
  }

  "HeaderChainCheck.forChain" should "validate with the block-import validator on proof-of-work chains" taggedAs UnitTest in {
    HeaderChainCheck.forChain(mordorConfig, PoWBlockHeaderValidator) match
      case v: HeaderChainCheck.Validated =>
        v.validator shouldBe PoWBlockHeaderValidator
        v.sealSampleWindow shouldBe HeaderChainCheck.DefaultSealSampleWindow
        v.validatesChain shouldBe true
      case other => fail(s"expected a validating check on Mordor, got $other")
  }

  it should "keep the parent-link-only check on chains with a terminal total difficulty (ETH, Sepolia)" taggedAs UnitTest in {
    val postMerge = mordorConfig.copy(terminalTotalDifficulty = Some(BigInt(1)))
    HeaderChainCheck.forChain(postMerge, TransitionBlockHeaderValidator) shouldBe HeaderChainCheck.LinkOnly
    HeaderChainCheck.forChain(postMerge, PoWBlockHeaderValidator) shouldBe HeaderChainCheck.LinkOnly
  }

  it should "fall back to the parent-link-only check when the import validator has no separate seal (test mode)" taggedAs UnitTest in {
    HeaderChainCheck.forChain(mordorConfig, new Mocks.MockValidatorsAlwaysSucceed().blockHeaderValidator) shouldBe
      HeaderChainCheck.LinkOnly
  }

  "HeaderChainCheck.LinkOnly" should "accept every header and check no seal" taggedAs UnitTest in {
    val altered = fromGenesis(10).copy(difficulty = Difficulty(BigInt(1)))
    HeaderChainCheck.LinkOnly.check(altered, fromGenesis(9), checkSeal = true) shouldBe Right(BlockHeaderValid)
    HeaderChainCheck.LinkOnly.validateHeaderOnly(altered) shouldBe Right(BlockHeaderValid)
    HeaderChainCheck.LinkOnly.sealPositions(fromGenesis.take(5), BigInt(4)) shouldBe Seq.fill(5)(false)
  }

  /** `header` with the mixHash its nonce gives (Ethash light verification): sealed for its own difficulty when that
    * difficulty is 1.
    */
  private def sealFor(header: BlockHeader): BlockHeader =
    val ecip1099 = mordorConfig.forkBlockNumbers.ecip1099BlockNumber.toLong
    val number = header.number.value.toLong
    val epoch = EthashUtils.epoch(number, ecip1099)
    val cache = EthashUtils.makeCache(epoch, EthashUtils.seed(number, ecip1099))
    val pow = EthashUtils.hashimotoLight(
      kec256(BlockHeader.getEncodedWithoutNonce(header)),
      header.nonce.toArray,
      EthashUtils.dagSize(epoch),
      cache
    )
    header.copy(mixHash = BlockHash(pow.mixHash))
