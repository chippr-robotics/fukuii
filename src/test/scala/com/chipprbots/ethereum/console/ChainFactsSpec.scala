package com.chipprbots.ethereum.console

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps

/** The chain's identity and fork timeline, for display only (spec 027 FR-017, C-4, C-5, T062). Nothing here changes a
  * fork's configuration or how `forBlock` and `forTimestamp` dispatch.
  */
class ChainFactsSpec extends AnyFlatSpec with Matchers:

  private lazy val shipped = ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()
  private def chain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shipped.getConfig(s"fukuii.blockchains.$name"))

  "ChainFacts on ETC" should "list the block-number forks by MilestoneLog's names, scheduled ones only" taggedAs (
    UnitTest
  ) in {
    val etc = TuiFixtures.chainEtc
    etc.schedule shouldBe ForkSchedule.ByBlock
    etc.forks.map(_.name) shouldBe Seq(
      "Frontier",
      "Homestead",
      "EIP-150",
      "EIP-155",
      "EIP-160",
      "DiffBomb-Pause",
      "DiffBomb-Continue",
      "DiffBomb-Removal",
      "Atlantis",
      "Agharta",
      "Phoenix",
      "ECIP-1099",
      "Magneto",
      "Mystique",
      "Spiral"
    )
    etc.forks.map(_.at) shouldBe etc.forks.map(_.at).sorted
    etc.forks.find(_.name == "Spiral").map(_.at) shouldBe Some(19_250_000L)
  }

  it should "call a fork at Long.MaxValue after the last scheduled one 'not scheduled', and drop the ETH-only ones" taggedAs (
    UnitTest
  ) in {
    TuiFixtures.chainEtc.notScheduled shouldBe Seq("Olympia")
    // The shipped configs' far-future placeholder (etc-chain.conf's olympia-block-number) is not scheduled either.
    val placeholder = TuiFixtures.etcForks.copy(olympiaBlockNumber = BigInt("1000000000000000000"))
    ChainFacts.byBlock(BigInt(61), placeholder).notScheduled shouldBe Seq("Olympia")
    (TuiFixtures.chainEtc.forks.map(_.name) should contain).noneOf("Byzantium", "Berlin", "Muir Glacier", "Olympia")
  }

  it should "find the last and next fork against the current block, the distance in blocks" taggedAs (UnitTest) in {
    val etc = TuiFixtures.chainEtc
    etc.last(15_234_567L).map(_.name) shouldBe Some("Mystique")
    val next = etc.next(15_234_567L).get
    next.name shouldBe "Spiral"
    etc.when(next) shouldBe "19,250,000"
    etc.distance(next, 15_234_567L) shouldBe "4.0M away"
    etc.distance(next, 19_249_677L) shouldBe "323 away"
    etc.next(21_884_102L) shouldBe None
    etc.last(21_884_102L).map(_.name) shouldBe Some("Spiral")
    etc.last(-1L) shouldBe None
  }

  it should "come from the shipped ETC and Mordor configs, with their chain ids, as PoW (C-4)" taggedAs (UnitTest) in {
    val etc = ChainFacts.from(chain("etc"), "v")
    (etc.chainId, etc.family, etc.version) shouldBe ((BigInt(61), ChainFamily.PoW, "v"))
    (etc.forks.map(_.name) should contain).allOf("Atlantis", "Mystique", "Spiral")
    etc.notScheduled should contain("Olympia")
    ChainFacts.from(chain("mordor")).chainId shouldBe BigInt(63)
    ChainFacts.from(chain("mordor")).family shouldBe ChainFamily.PoW
  }

  "ChainFacts on ETH" should "list the timestamp forks by activation time, Amsterdam and the BPO forks included (C-5)" taggedAs (
    UnitTest
  ) in {
    val sepolia = TuiFixtures.chainSepolia
    sepolia.schedule shouldBe ForkSchedule.ByTimestamp
    sepolia.family shouldBe ChainFamily.PoS
    sepolia.forks.map(_.name) shouldBe Seq("Shanghai", "Cancun", "Prague", "Osaka", "BPO1", "BPO2", "Amsterdam")
    sepolia.notScheduled shouldBe empty
  }

  it should "name a fork with no timestamp 'not scheduled'" taggedAs (UnitTest) in {
    val mainnetLike = ChainFacts.byTimestamp(BigInt(1), TuiFixtures.sepoliaForks.copy(amsterdamTimestamp = None))
    mainnetLike.notScheduled shouldBe Seq("Amsterdam")
    mainnetLike.forks.map(_.name) should not contain "Amsterdam"
    ChainFacts.byTimestamp(BigInt(1), ForkTimestamps()).notScheduled shouldBe
      Seq("Shanghai", "Cancun", "Prague", "Osaka", "Amsterdam", "BPO1", "BPO2")
  }

  it should "find the next fork against the head timestamp, in UTC and as a time to go" taggedAs (UnitTest) in {
    val sepolia = TuiFixtures.chainSepolia
    val head = 1_791_294_816L - (3 * 86_400L + 4 * 3_600L)
    sepolia.last(head).map(_.name) shouldBe Some("BPO2")
    val next = sepolia.next(head).get
    next.name shouldBe "Amsterdam"
    sepolia.when(next) shouldBe "2026-10-06T13:53:36Z" // C-9
    ChainFacts.utc(1_791_294_816L) shouldBe "2026-10-06T13:53:36Z"
    sepolia.distance(next, head) shouldBe "in 3d 4h"
    sepolia.distance(next, 1_791_294_816L - 59) shouldBe "in 59s"
    sepolia.next(1_791_294_816L) shouldBe None // at the timestamp it is active
  }

  it should "come from the shipped Sepolia config, as PoS with chain id 11155111" taggedAs (UnitTest) in {
    val sepolia = ChainFacts.from(chain("sepolia"))
    (sepolia.chainId, sepolia.family) shouldBe ((BigInt(11_155_111), ChainFamily.PoS))
    sepolia.forks.find(_.name == "Amsterdam").map(_.at) shouldBe Some(1_791_294_816L)
    sepolia.forks.map(_.name).take(4) shouldBe Seq("Shanghai", "Cancun", "Prague", "Osaka")
  }

  "The family" should "come from networkType, not from whether timestamp forks exist (C-4)" taggedAs (UnitTest) in {
    val etcWithTimestamps = chain("etc").copy(forkTimestamps = TuiFixtures.sepoliaForks)
    ChainFacts.from(etcWithTimestamps).family shouldBe ChainFamily.PoW
    ChainFacts.from(etcWithTimestamps).schedule shouldBe ForkSchedule.ByBlock
  }
