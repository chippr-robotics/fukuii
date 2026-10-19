package com.chipprbots.ethereum.vm

import java.io.File

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BlockchainsConfig

/** #1637: BLOBBASEFEE (EIP-7516) takes its update fraction from the chain's blob schedule, through the same resolver as
  * the block's blob base fee, so the two cannot disagree on any chain.
  */
class BlobBaseFeeScheduleSpec extends AnyFlatSpec with Matchers:

  private val owner = Address(0xcafe)

  private def header(ts: Long, excess: BigInt): BlockHeader =
    BlockFixtures.ValidBlock.header.copy(
      number = BlockNumber(1000),
      unixTimestamp = Timestamp(ts),
      extraFields = HefPostPrague(
        baseFee = BigInt(7),
        withdrawalsRoot = ByteString(new Array[Byte](32)),
        blobGasUsed = BigInt(0),
        excessBlobGas = excess,
        parentBeaconBlockRoot = ByteString(new Array[Byte](32)),
        requestsHash = ByteString(new Array[Byte](32))
      )
    )

  /** BLOBBASEFEE executed once against a block of `chain` at `ts`; the value it pushes. */
  private def blobBaseFeeOpcode(chain: BlockchainConfig, ts: Long, excess: BigInt): BigInt =
    val h = header(ts, excess)
    val world = MockWorldState().saveAccount(owner, Account(balance = UInt256(1000), nonce = 1))
    val context = ProgramContext[MockWorldState, MockStorage](
      callerAddr = owner,
      originAddr = owner,
      recipientAddr = Some(owner),
      gasPrice = 1,
      startGas = 1000000,
      inputData = ByteString.empty,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader = h,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = EvmConfig.forBlock(BigInt(1000), Timestamp(ts), chain),
      originalWorld = world,
      warmAddresses = Set(owner),
      warmStorage = Set.empty
    )
    val state = ProgramState(new MockWorldState.TestVM, context, ExecEnv(context, ByteString.empty, owner))
    val result = BLOBBASEFEE.execute(state)
    result.error shouldBe None
    result.stack.pop()._1.toBigInt

  private lazy val shippedRoot =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private lazy val mainnet: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.eth"))

  private lazy val ephemery: BlockchainConfig =
    val dir = new File(getClass.getResource("/ephemery/ephemery-164").toURI).getAbsolutePath
    BlockchainsConfig(
      ConfigFactory
        .empty()
        .withValue("network", ConfigValueFactory.fromAnyRef("ephemery"))
        .withValue("custom-chains-dir", ConfigValueFactory.fromAnyRef(dir))
    ).blockchainConfig

  private val excesses = Seq(BigInt(0), BigInt(2314058), BigInt(10_000_000), BigInt(50_000_000))

  "BLOBBASEFEE" should "equal BlobGasUtils.getBlobGasPrice at every mainnet blob fork boundary" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val ft = mainnet.forkTimestamps
    val ts = Seq(ft.cancunTimestamp, ft.pragueTimestamp, ft.osakaTimestamp, ft.bpo1Timestamp, ft.bpo2Timestamp).flatten
      .flatMap(t => Seq(t - 1, t))
    for t <- ts; e <- excesses do
      withClue(s"mainnet ts=$t excess=$e: ") {
        blobBaseFeeOpcode(mainnet, t, e) shouldBe BlobGasUtils.getBlobGasPrice(e, Timestamp(t), mainnet)
      }
  }

  it should "use Ephemery's own BPO fractions (6676955, 8346193), not mainnet's" taggedAs (UnitTest, VMTest) in {
    // Expected values computed outside fukuii (Python fake_exponential, see EphemeryBlobScheduleVectorsSpec).
    blobBaseFeeOpcode(ephemery, 1791063432L, BigInt(10_000_000)) shouldBe BigInt(4)
    blobBaseFeeOpcode(ephemery, 1791063432L, BigInt(50_000_000)) shouldBe BigInt(1787)
    blobBaseFeeOpcode(ephemery, 1791849864L, BigInt(10_000_000)) shouldBe BigInt(3)
    blobBaseFeeOpcode(ephemery, 1791849864L, BigInt(50_000_000)) shouldBe BigInt(399)
    // mainnet's BPO1 fraction 8346193 at 50,000,000 gives 399: the pre-#1637 answer on Ephemery BPO1.
    blobBaseFeeOpcode(mainnet, 1765290071L, BigInt(50_000_000)) shouldBe BigInt(399)
    for t <- Seq(1791063431L, 1791063432L, 1791849863L, 1791849864L); e <- excesses do
      withClue(s"ephemery ts=$t excess=$e: ") {
        blobBaseFeeOpcode(ephemery, t, e) shouldBe BlobGasUtils.getBlobGasPrice(e, Timestamp(t), ephemery)
      }
  }
