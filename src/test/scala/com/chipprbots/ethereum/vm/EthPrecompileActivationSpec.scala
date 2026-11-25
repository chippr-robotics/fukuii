package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks

/** B-177: on an Ethereum chain the BLS12-381 precompiles (0x0b-0x11, EIP-2537) arrive at Prague and P256VERIFY (0x100,
  * EIP-7951) at Osaka, both by timestamp, whatever block `olympia-block-number` names.
  *
  * fukuii keeps London in `olympia-block-number` on ETH chains (`hive/fukuii/fukuii.sh` maps `HIVE_FORK_LONDON` to it,
  * and the shipped ETH configs set it to their London block). `PrecompiledContracts.getContracts` also answers ETC's
  * Olympia (ECIP-1121: the Osaka set at a block number), and that branch had no ETH guard, unlike ModExp's EIP-7823 and
  * EIP-7883 routing. With the ETC block ladder at Olympia on an ETH config, the whole Osaka set was live from London.
  * The shipped hive and ETH configs leave Atlantis to Spiral unscheduled, and `etcForkForBlockNumber` stops at the
  * first fork not reached, so they never reach Olympia; the second config below does, and is what the guard is for.
  *
  * go-ethereum: `core/vm/contracts.go` `ActivePrecompiledContracts` picks `PrecompiledContractsPrague` (adds 0x0b-0x11)
  * on `IsPrague` and `PrecompiledContractsOsaka` (adds 0x100) on `IsOsaka`; London, Shanghai and Cancun stop at 0x0a.
  */
// scalastyle:off magic.number
class EthPrecompileActivationSpec extends AnyFlatSpec with Matchers:

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private val CancunTs = 1_000L
  private val PragueTs = 2_000L
  private val OsakaTs = 3_000L
  private val LondonBlock = 5

  /** The blocks and timestamps fukuii.sh sets for a hive network: every block fork through Berlin at 0, London at
    * `LondonBlock` (as `olympia-block-number`), Shanghai at genesis and Cancun, Prague and Osaka by timestamp.
    */
  private val hiveOverrides: String =
    s"""homestead-block-number = 0
       |eip150-block-number = 0
       |eip155-block-number = 0
       |eip160-block-number = 0
       |eip161-block-number = 0
       |byzantium-block-number = 0
       |constantinople-block-number = 0
       |petersburg-block-number = 0
       |istanbul-block-number = 0
       |muir-glacier-block-number = 0
       |berlin-block-number = 0
       |olympia-block-number = $LondonBlock
       |shanghai-timestamp = 0
       |cancun-timestamp = $CancunTs
       |prague-timestamp = $PragueTs
       |osaka-timestamp = $OsakaTs
       |""".stripMargin

  private def hive(extra: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(
      ConfigFactory
        .parseString(hiveOverrides + extra)
        .withFallback(shippedRoot.getConfig("fukuii.blockchains.hive"))
    )

  /** The hive config as fukuii.sh builds it: Atlantis to Spiral unscheduled. */
  private lazy val hiveAsShipped: BlockchainConfig = hive("")

  /** An ETH config whose ETC block ladder is at genesis, so it reaches Olympia at London. */
  private lazy val ethWithEtcLadder: BlockchainConfig = hive(
    """atlantis-block-number = 0
      |agharta-block-number = 0
      |phoenix-block-number = 0
      |magneto-block-number = 0
      |mystique-block-number = 0
      |spiral-block-number = 0
      |""".stripMargin
  )

  private val zero32 = ByteString(new Array[Byte](32))

  private def header(number: BigInt, timestamp: Long, extra: HeaderExtraFields): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      unixTimestamp = Timestamp(timestamp),
      difficulty = Difficulty.Zero,
      extraFields = extra
    )

  private val Gwei = BigInt(1_000_000_000)
  private def london(number: BigInt) = header(number, 500L, HefPostShanghai(Gwei, BlockHeader.EmptyMpt))
  private def cancun(number: BigInt) =
    header(number, CancunTs, HefPostCancun(Gwei, BlockHeader.EmptyMpt, 0, 0, zero32))
  private def prague(number: BigInt) =
    header(number, PragueTs, HefPostPrague(Gwei, BlockHeader.EmptyMpt, 0, 0, zero32, zero32))
  private def osaka(number: BigInt) =
    header(number, OsakaTs, HefPostPrague(Gwei, BlockHeader.EmptyMpt, 0, 0, zero32, zero32))

  private def active(config: BlockchainConfig, h: BlockHeader): Set[Address] =
    PrecompiledContracts
      .getContracts(
        EvmConfig.forBlock(h.number.value, h.unixTimestamp, config),
        h
      )
      .keySet

  private val Bls = (0x0b to 0x11).map(Address(_)).toSet
  private val P256 = Address(0x100)
  private val Kzg = Address(0x0a)

  for (name, config) <- Seq(
      "the hive config" -> (() => hiveAsShipped),
      "an ETC-laddered ETH config" -> (() => ethWithEtcLadder)
    )
  do
    s"Precompile activation on $name with London as olympia-block-number" should
      "have no BLS12-381 or P256VERIFY at London" taggedAs (UnitTest, VMTest) in {
        val got = active(config(), london(LondonBlock))
        got.intersect(Bls) shouldBe empty
        got should not contain P256
        got should not contain Kzg
      }

    it should "have KZG, but no BLS12-381 or P256VERIFY, at Cancun" taggedAs (UnitTest, VMTest) in {
      val got = active(config(), cancun(LondonBlock + 10))
      got should contain(Kzg)
      got.intersect(Bls) shouldBe empty
      got should not contain P256
    }

    it should "add BLS12-381 at Prague, still without P256VERIFY" taggedAs (UnitTest, VMTest) in {
      val got = active(config(), prague(LondonBlock + 20))
      got should contain allElementsOf Bls
      got should not contain P256
    }

    it should "add P256VERIFY at Osaka" taggedAs (UnitTest, VMTest) in {
      val got = active(config(), osaka(LondonBlock + 30))
      got should contain allElementsOf Bls
      got should contain(P256)
    }

  "The ETC-laddered ETH config" should "reach Olympia on the ETC ladder at London (the case the guard covers)" taggedAs (
    UnitTest,
    VMTest
  ) in {
    BlockchainConfigForEvm(ethWithEtcLadder).etcForkForBlockNumber(LondonBlock) shouldBe EtcForks.Olympia
    BlockchainConfigForEvm(ethWithEtcLadder).isEthereum shouldBe true
  }

  "The native backends an ETH config needs" should "follow the precompile set: BLS12-381 from Prague, not from London" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val noPragueOrOsaka = hive(
      """atlantis-block-number = 0
        |agharta-block-number = 0
        |phoenix-block-number = 0
        |magneto-block-number = 0
        |mystique-block-number = 0
        |spiral-block-number = 0
        |prague-timestamp = null
        |osaka-timestamp = null
        |""".stripMargin
    )
    NativeBackends.required(noPragueOrOsaka) shouldBe Set(NativeBackends.Backend.Kzg)
    NativeBackends.required(ethWithEtcLadder) shouldBe NativeBackends.Backend.values.toSet
  }

  // ── ETC keeps ECIP-1121's set at Olympia (forge's; asserted so the guard cannot move it) ──

  private lazy val mordorWithOlympia: BlockchainConfig =
    BlockchainConfig.fromRawConfig(
      ConfigFactory
        .parseString("olympia-block-number = 20000000")
        .withFallback(shippedRoot.getConfig("fukuii.blockchains.mordor"))
    )

  "Precompile activation on Mordor" should "add BLS12-381 and P256VERIFY at Olympia, by block number" taggedAs (
    UnitTest,
    VMTest
  ) in {
    def at(n: BigInt) = active(mordorWithOlympia, header(n, 1_700_000_000L, HefPostOlympia(Gwei)))
    val before = at(19_999_999)
    before.intersect(Bls) shouldBe empty
    before should not contain P256
    val after = at(20_000_000)
    after should contain allElementsOf Bls
    after should contain(P256)
    after shouldBe PrecompiledContracts.osakaContracts.keySet
  }
