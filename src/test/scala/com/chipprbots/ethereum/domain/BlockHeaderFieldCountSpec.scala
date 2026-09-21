package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Unit coverage for [[BlockHeader.validateFieldCount]].
  *
  * Verifies that a decoded header whose ExtraFields shape is inconsistent with the fork timestamps active at its
  * timestamp is rejected early — before the full [[com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator]]
  * runs. ETC chains are unaffected (no timestamp forks).
  */
// scalastyle:off magic.number
class BlockHeaderFieldCountSpec
    extends AnyWordSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider:

  private val ShanghaiTs: Long = 1_000L
  private val CancunTs: Long = 2_000L
  private val PreShanghaiTs: Long = 500L
  private val PostCancunTs: Long = 3_000L
  private val AmsterdamTs: Long = 4_000L
  private val PostAmsterdamTs: Long = 5_000L

  private val withdrawalsRoot: ByteString = Fixtures.Blocks.ValidBlock.header.stateRoot.value
  private val beaconRoot: ByteString = Fixtures.Blocks.ValidBlock.header.parentHash.value

  private val ethConfig: BlockchainConfig = blockchainConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Some(ShanghaiTs),
      cancunTimestamp = Some(CancunTs),
      amsterdamTimestamp = Some(AmsterdamTs)
    )
  )

  private val etcConfig: BlockchainConfig = blockchainConfig.copy(networkType = NetworkType.ETC)

  private def baseHeader(ts: Long): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(unixTimestamp = Timestamp(ts))

  private val pragueShape: HefPostPrague = HefPostPrague(
    baseFee = BigInt(1),
    withdrawalsRoot = withdrawalsRoot,
    blobGasUsed = BigInt(0),
    excessBlobGas = BigInt(0),
    parentBeaconBlockRoot = beaconRoot,
    requestsHash = beaconRoot
  )

  private val amsterdamShape: HefPostAmsterdam = HefPostAmsterdam(
    baseFee = BigInt(1),
    withdrawalsRoot = withdrawalsRoot,
    blobGasUsed = BigInt(0),
    excessBlobGas = BigInt(0),
    parentBeaconBlockRoot = beaconRoot,
    requestsHash = beaconRoot,
    blockAccessListHash = beaconRoot,
    slotNumber = BigInt(42)
  )

  "BlockHeader.validateFieldCount" when {

    "chain is ETC (networkType = ETC)" should {
      "accept any field shape regardless of timestamp" taggedAs (UnitTest, ConsensusTest) in {
        // HefEmpty shape at a timestamp that would be Cancun-era on ETH — ETC has no timestamp forks.
        val header = baseHeader(PostCancunTs).copy(extraFields = HefEmpty)
        BlockHeader.validateFieldCount(header, etcConfig) shouldBe Right(())
      }

      "accept a 15-item shape at a timestamp that would be Amsterdam-era on ETH" taggedAs (UnitTest,
        ConsensusTest) in {
        // The ETC guard restated for the new fork: no ETC config declares amsterdam-timestamp,
        // and the networkType short-circuit fires before any timestamp is consulted.
        val header = baseHeader(PostAmsterdamTs).copy(extraFields = HefEmpty)
        BlockHeader.validateFieldCount(header, etcConfig) shouldBe Right(())
        etcConfig.forkTimestamps.amsterdamTimestamp shouldBe None
        etcConfig.isAmsterdamTimestamp(Timestamp(PostAmsterdamTs)) shouldBe false
      }
    }

    "chain is ETH and timestamp is pre-Shanghai" should {
      "accept HefEmpty (15-item RLP shape)" taggedAs (UnitTest, ConsensusTest) in {
        val header = baseHeader(PreShanghaiTs).copy(extraFields = HefEmpty)
        BlockHeader.validateFieldCount(header, ethConfig) shouldBe Right(())
      }
    }

    "chain is ETH and Shanghai is active but withdrawalsRoot is absent" should {
      "return Left with a descriptive message" taggedAs (UnitTest, ConsensusTest) in {
        // HefEmpty carries no withdrawalsRoot — decoded from 15-item RLP at a Shanghai-active timestamp.
        val header = baseHeader(ShanghaiTs).copy(extraFields = HefEmpty)
        val result = BlockHeader.validateFieldCount(header, ethConfig)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get should include("withdrawalsRoot")
      }
    }

    "chain is ETH and Cancun is active but blobGasUsed is absent (17-item RLP shape)" should {
      "return Left with a descriptive message" taggedAs (UnitTest, ConsensusTest) in {
        // HefPostShanghai carries withdrawalsRoot but no blobGasUsed — decoded from 17-item RLP
        // at a Cancun-active timestamp. This is the §ETH-T9-B motivating case.
        val header = baseHeader(PostCancunTs).copy(
          extraFields = HefPostShanghai(baseFee = BigInt(1), withdrawalsRoot = withdrawalsRoot)
        )
        val result = BlockHeader.validateFieldCount(header, ethConfig)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get should include("blobGasUsed")
      }
    }

    "chain is ETH and Cancun is active with all blob fields present" should {
      "accept HefPostCancun (20-item RLP shape)" taggedAs (UnitTest, ConsensusTest) in {
        val header = baseHeader(PostCancunTs).copy(
          extraFields = HefPostCancun(
            baseFee = BigInt(1),
            withdrawalsRoot = withdrawalsRoot,
            blobGasUsed = BigInt(0),
            excessBlobGas = BigInt(0),
            parentBeaconBlockRoot = beaconRoot
          )
        )
        BlockHeader.validateFieldCount(header, ethConfig) shouldBe Right(())
      }
    }

    "chain is ETH and timestamp is exactly at CancunTs boundary" should {
      "apply Cancun rules (boundary is inclusive)" taggedAs (UnitTest, ConsensusTest) in {
        val shanghaiShape = baseHeader(CancunTs).copy(
          extraFields = HefPostShanghai(baseFee = BigInt(1), withdrawalsRoot = withdrawalsRoot)
        )
        BlockHeader.validateFieldCount(shanghaiShape, ethConfig) shouldBe a[Left[?, ?]]
      }
    }

    "chain is ETH and Amsterdam is active" should {
      "accept HefPostAmsterdam (23-item RLP shape)" taggedAs (UnitTest, ConsensusTest) in {
        val header = baseHeader(PostAmsterdamTs).copy(extraFields = amsterdamShape)
        BlockHeader.validateFieldCount(header, ethConfig) shouldBe Right(())
      }

      "reject HefPostPrague (21-item RLP shape) with a descriptive message" taggedAs (UnitTest, ConsensusTest) in {
        // A Prague-shaped header satisfies every Cancun and Shanghai predicate, so without the
        // Amsterdam case it would pass. This is the shape-versus-active-fork check, separate from
        // decoder arity: the decoder would happily accept these 21 items.
        val header = baseHeader(PostAmsterdamTs).copy(extraFields = pragueShape)
        val result = BlockHeader.validateFieldCount(header, ethConfig)
        result shouldBe a[Left[?, ?]]
        result.left.toOption.get should include("blockAccessListHash")
        result.left.toOption.get should include("23")
      }

      "apply Amsterdam rules at exactly AmsterdamTs (boundary is inclusive)" taggedAs (UnitTest, ConsensusTest) in {
        val atBoundary = baseHeader(AmsterdamTs).copy(extraFields = pragueShape)
        BlockHeader.validateFieldCount(atBoundary, ethConfig) shouldBe a[Left[?, ?]]
      }

      "accept HefPostPrague one second before AmsterdamTs" taggedAs (UnitTest, ConsensusTest) in {
        val justBefore = baseHeader(AmsterdamTs - 1).copy(extraFields = pragueShape)
        BlockHeader.validateFieldCount(justBefore, ethConfig) shouldBe Right(())
      }
    }
  }
// scalastyle:on magic.number
