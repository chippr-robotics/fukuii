package com.chipprbots.ethereum.consensus.engine

import java.io.File

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderBlobGasError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BlockchainsConfig

/** #1637: blob-gas header validation on Ephemery, whose BPO forks differ from mainnet's (BPO1 8/12/6676955, BPO2
  * 10/15/8346193 against mainnet's 10/15/8346193 and 14/21/11684671).
  *
  * The chain config is the generated `ephemery-164` fixture (`scripts/ephemery/fetch-genesis.sh` output), loaded
  * through `custom-chains-dir` as an operator runs it, so these vectors cover the script's mapping too. Osaka is active
  * from genesis, so EIP-7918's reserve-price branch applies.
  *
  * Every expected number was computed OUTSIDE fukuii, by an independent Python transcription of go-ethereum
  * `eip4844.calcExcessBlobGas` / `fakeExponential` (GAS_PER_BLOB 131072, BLOB_BASE_COST 8192):
  * {{{
  *   def excess(pe, pu, pbf, target, mx, frac):
  *       if pe + pu < target*G: return 0
  *       if 8192*pbf > fake_exp(1, pe, frac)*G: return pe + pu*(mx-target)//mx
  *       return pe + pu - target*G
  * }}}
  * Before #1637 fukuii used mainnet's BPO values here: at BPO1 an 8-blob parent (Ephemery's target) gave 10,000,000 -
  * 262,144 = 9,737,856 instead of 10,000,000 (INCORRECT_EXCESS_BLOB_GAS on import), and 13-15 blob blocks were
  * accepted.
  */
class EphemeryBlobScheduleVectorsSpec extends AnyFlatSpec with Matchers:

  private val G: BigInt = BigInt(131072)
  private val Bpo1Ts = 1791063432L
  private val Bpo2Ts = 1791849864L
  private val E: BigInt = BigInt(10_000_000) // parent excessBlobGas for the single-step vectors
  private val LowBaseFee: BigInt = BigInt(7) // 8192 * 7 = 57,344 < any blob price (>= 131,072): no EIP-7918 branch
  private val OneGwei: BigInt = BigInt(1_000_000_000) // 8192 gwei > blob price at E: EIP-7918 branch

  private given ephemery: BlockchainConfig =
    val dir = new File(getClass.getResource("/ephemery/ephemery-164").toURI).getAbsolutePath
    BlockchainsConfig(
      ConfigFactory
        .empty()
        .withValue("network", ConfigValueFactory.fromAnyRef("ephemery"))
        .withValue("custom-chains-dir", ConfigValueFactory.fromAnyRef(dir))
    ).blockchainConfig

  private def header(
      number: Long,
      ts: Long,
      baseFee: BigInt,
      blobGasUsed: BigInt,
      excessBlobGas: BigInt
  ): BlockHeader =
    BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(ByteString(new Array[Byte](32))),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(number),
      gasLimit = GasAmount(60_000_000),
      gasUsed = GasAmount(30_000_000), // exactly the EIP-1559 target: the child keeps the parent's base fee
      unixTimestamp = Timestamp(ts),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostPrague(
        baseFee = baseFee,
        withdrawalsRoot = BlockHeader.EmptyMpt,
        blobGasUsed = blobGasUsed,
        excessBlobGas = excessBlobGas,
        parentBeaconBlockRoot = ByteString(new Array[Byte](32)),
        requestsHash = ByteString(new Array[Byte](32))
      )
    )

  /** Full PoS header validation of a child at `childTs` over a parent one slot earlier. */
  private def validate(
      childTs: Long,
      parentUsedBlobs: Int,
      parentBaseFee: BigInt,
      childExcess: BigInt,
      childUsedBlobs: Int = 0
  ) =
    val parent = header(1000, childTs - 12, parentBaseFee, G * parentUsedBlobs, E)
    val child = header(1001, childTs, parentBaseFee, G * childUsedBlobs, childExcess)
    PoSBlockHeaderValidator.validate(child, parent)

  private def expected(childTs: Long, parentUsedBlobs: Int, parentBaseFee: BigInt): BigInt =
    BlobGasUtils.expectedExcessBlobGas(E, G * parentUsedBlobs, parentBaseFee, Timestamp(childTs), ephemery)

  "Ephemery BPO1 (8/12/6676955)" should "keep excessBlobGas unchanged after a block at the 8-blob target" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    expected(Bpo1Ts + 12, 8, LowBaseFee) shouldBe BigInt(10_000_000)
    validate(Bpo1Ts + 12, 8, LowBaseFee, BigInt(10_000_000)) shouldBe Right(BlockHeaderValid)
    // mainnet's BPO1 target (10) here is exactly the pre-#1637 failure
    validate(Bpo1Ts + 12, 8, LowBaseFee, BigInt(9_737_856)).left.toOption.get.toString should
      include("INCORRECT_EXCESS_BLOB_GAS: expected 10000000 got 9737856")
  }

  it should "add 4 blobs of excess after a block at the 12-blob max" taggedAs (UnitTest, ConsensusTest) in {
    expected(Bpo1Ts + 12, 12, LowBaseFee) shouldBe BigInt(10_524_288)
    validate(Bpo1Ts + 12, 12, LowBaseFee, BigInt(10_524_288)) shouldBe Right(BlockHeaderValid)
  }

  it should "take EIP-7918's reserve-price branch with BPO1's max and target" taggedAs (UnitTest, ConsensusTest) in {
    // 10,000,000 + 8*131072 * (12-8) / 12 = 10,349,525 (integer division)
    expected(Bpo1Ts + 12, 8, OneGwei) shouldBe BigInt(10_349_525)
    validate(Bpo1Ts + 12, 8, OneGwei, BigInt(10_349_525)) shouldBe Right(BlockHeaderValid)
  }

  it should "use BPO1's target for the first BPO1 block over a Prague-era parent" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Parent at Bpo1Ts - 12 (Prague/Osaka, max 9) used 9 blobs; the child's parameters decide: +9 - 8 blobs.
    expected(Bpo1Ts, 9, LowBaseFee) shouldBe BigInt(10_131_072)
    validate(Bpo1Ts, 9, LowBaseFee, BigInt(10_131_072)) shouldBe Right(BlockHeaderValid)
  }

  it should "accept 12 blobs in a block and reject 13" taggedAs (UnitTest, ConsensusTest) in {
    val ex = expected(Bpo1Ts + 12, 8, LowBaseFee)
    validate(Bpo1Ts + 12, 8, LowBaseFee, ex, childUsedBlobs = 12) shouldBe Right(BlockHeaderValid)
    validate(Bpo1Ts + 12, 8, LowBaseFee, ex, childUsedBlobs = 13) shouldBe
      Left(HeaderBlobGasError("blobGasUsed 1703936 exceeds max 1572864"))
  }

  it should "progress excessBlobGas by 4 blobs per full block from zero" taggedAs (UnitTest, ConsensusTest) in {
    val progression = Iterator
      .iterate(BigInt(0))(pe =>
        BlobGasUtils.expectedExcessBlobGas(pe, G * 12, LowBaseFee, Timestamp(Bpo1Ts + 12), ephemery)
      )
      .take(6)
      .toList
    progression shouldBe List(0, 524288, 1048576, 1572864, 2097152, 2621440).map(BigInt(_))
  }

  it should "price blobs with fraction 6676955" taggedAs (UnitTest, ConsensusTest) in {
    BlobGasUtils.getBlobGasPrice(E, Timestamp(Bpo1Ts), ephemery) shouldBe BigInt(4)
    BlobGasUtils.getBlobGasPrice(BigInt(50_000_000), Timestamp(Bpo1Ts), ephemery) shouldBe BigInt(1787)
  }

  "Ephemery BPO2 (10/15/8346193)" should "keep excess at the 10-blob target and add 5 blobs at the 15-blob max" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    expected(Bpo2Ts + 12, 10, LowBaseFee) shouldBe BigInt(10_000_000)
    validate(Bpo2Ts + 12, 10, LowBaseFee, BigInt(10_000_000)) shouldBe Right(BlockHeaderValid)
    expected(Bpo2Ts + 12, 15, LowBaseFee) shouldBe BigInt(10_655_360)
    validate(Bpo2Ts + 12, 15, LowBaseFee, BigInt(10_655_360)) shouldBe Right(BlockHeaderValid)
  }

  it should "take EIP-7918's reserve-price branch with BPO2's max and target" taggedAs (UnitTest, ConsensusTest) in {
    // 10,000,000 + 10*131072 * (15-10) / 15 = 10,436,906
    expected(Bpo2Ts + 12, 10, OneGwei) shouldBe BigInt(10_436_906)
    validate(Bpo2Ts + 12, 10, OneGwei, BigInt(10_436_906)) shouldBe Right(BlockHeaderValid)
  }

  it should "accept 15 blobs in a block and reject 16" taggedAs (UnitTest, ConsensusTest) in {
    val ex = expected(Bpo2Ts + 12, 10, LowBaseFee)
    validate(Bpo2Ts + 12, 10, LowBaseFee, ex, childUsedBlobs = 15) shouldBe Right(BlockHeaderValid)
    validate(Bpo2Ts + 12, 10, LowBaseFee, ex, childUsedBlobs = 16) shouldBe
      Left(HeaderBlobGasError("blobGasUsed 2097152 exceeds max 1966080"))
  }

  it should "progress excessBlobGas by 5 blobs per full block and price with fraction 8346193" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    Iterator
      .iterate(BigInt(0))(pe =>
        BlobGasUtils.expectedExcessBlobGas(pe, G * 15, LowBaseFee, Timestamp(Bpo2Ts + 12), ephemery)
      )
      .take(6)
      .toList shouldBe List(0, 655360, 1310720, 1966080, 2621440, 3276800).map(BigInt(_))
    BlobGasUtils.getBlobGasPrice(E, Timestamp(Bpo2Ts), ephemery) shouldBe BigInt(3)
    BlobGasUtils.getBlobGasPrice(BigInt(50_000_000), Timestamp(Bpo2Ts), ephemery) shouldBe BigInt(399)
  }

  "Ephemery before BPO1 (Prague/Osaka 6/9/5007716)" should "reject a 10-blob block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val ts = Bpo1Ts - 12
    val ex = expected(ts, 6, LowBaseFee)
    ex shouldBe E
    validate(ts, 6, LowBaseFee, ex, childUsedBlobs = 9) shouldBe Right(BlockHeaderValid)
    validate(ts, 6, LowBaseFee, ex, childUsedBlobs = 10) shouldBe
      Left(HeaderBlobGasError("blobGasUsed 1310720 exceeds max 1179648"))
  }
