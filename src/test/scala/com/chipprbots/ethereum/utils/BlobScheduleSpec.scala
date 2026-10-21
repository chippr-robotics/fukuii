package com.chipprbots.ethereum.utils

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*

/** #1637: per-chain blob schedule (EIP-7840 `blobSchedule`, EIP-7892 BPO forks).
  *
  * Two halves. Byte-identity: every shipped ETH-family chain conf resolves, at every fork boundary, to exactly the
  * parameters fukuii hard-coded before #1637 (the `legacy` ladder below is a verbatim transcript of the removed
  * `BlobGasUtils` / `OpCode.BLOBBASEFEE` constants, kept here as the reference). Validation: a schedule fukuii would
  * mis-execute refuses to load.
  */
class BlobScheduleSpec extends AnyFlatSpec with Matchers:

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private def shippedChain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  /** The pre-#1637 ladder, verbatim: BPO2 > BPO1 > Prague > Cancun, keyed on timestamps only (Osaka and Amsterdam
    * inherit), and the Cancun constants before Cancun.
    */
  private def legacy(c: BlockchainConfig, ts: Timestamp): (Int, Int, BigInt) =
    if c.isBpo2Timestamp(ts) then (14, 21, BigInt(11684671))
    else if c.isBpo1Timestamp(ts) then (10, 15, BigInt(8346193))
    else if c.isPragueTimestamp(ts) then (6, 9, BigInt(5007716))
    else (3, 6, BigInt(3338477))

  /** What the post-#1637 consumers compute: the schedule's entry, or the Cancun constants before the first blob fork.
    */
  private def resolved(c: BlockchainConfig, ts: Timestamp): (Int, Int, BigInt) =
    val p = c.blobParamsAt(ts).getOrElse(BlobParams.Cancun)
    (p.target, p.max, p.baseFeeUpdateFraction)

  private def probes(ft: ForkTimestamps): Seq[Long] =
    val forks = Seq(
      ft.shanghaiTimestamp,
      ft.cancunTimestamp,
      ft.pragueTimestamp,
      ft.osakaTimestamp,
      ft.bpo1Timestamp,
      ft.bpo2Timestamp,
      ft.amsterdamTimestamp
    ).flatten
    (Seq(0L, 1L, Long.MaxValue) ++ forks.flatMap(t => Seq(t - 1, t, t + 1)).filter(_ >= 0)).distinct.sorted

  private def assertLegacyEquivalent(name: String, c: BlockchainConfig): Unit =
    probes(c.forkTimestamps).foreach { ts =>
      withClue(s"$name at timestamp $ts: ") {
        resolved(c, Timestamp(ts)) shouldBe legacy(c, Timestamp(ts))
      }
    }

  "the shipped mainnet, Sepolia and Platåberget chain confs" should "resolve to the pre-#1637 blob parameters at every fork boundary" taggedAs (
    UnitTest
  ) in {
    Seq("eth", "sepolia", "plataberget").foreach { name =>
      val c = shippedChain(name)
      c.blobSchedule shouldBe BlobSchedule.GethDefaults
      assertLegacyEquivalent(name, c)
    }
  }

  they should "pin the mainnet and Sepolia values at each fork's own timestamp" taggedAs (UnitTest) in {
    val eth = shippedChain("eth")
    eth.blobParamsAt(Timestamp(1710338135L - 1)) shouldBe None
    eth.blobParamsAt(Timestamp(1710338135L)) shouldBe Some(BlobParams(3, 6, BigInt(3338477)))
    eth.blobParamsAt(Timestamp(1746612311L)) shouldBe Some(BlobParams(6, 9, BigInt(5007716)))
    eth.blobParamsAt(Timestamp(1764798551L)) shouldBe Some(BlobParams(6, 9, BigInt(5007716))) // Osaka inherits
    eth.blobParamsAt(Timestamp(1765290071L)) shouldBe Some(BlobParams(10, 15, BigInt(8346193)))
    eth.blobParamsAt(Timestamp(1767747671L)) shouldBe Some(BlobParams(14, 21, BigInt(11684671)))
    val sepolia = shippedChain("sepolia")
    sepolia.blobParamsAt(Timestamp(1761017184L)) shouldBe Some(BlobParams(10, 15, BigInt(8346193)))
    sepolia.blobParamsAt(Timestamp(1761607008L)) shouldBe Some(BlobParams(14, 21, BigInt(11684671)))
    sepolia.blobParamsAt(Timestamp(1791294816L)) shouldBe Some(BlobParams(14, 21, BigInt(11684671))) // Amsterdam
  }

  "the shipped hive chain conf" should "resolve to the pre-#1637 values under any fork schedule hive can set" taggedAs (
    UnitTest
  ) in {
    val raw = shippedRoot.getConfig("fukuii.blockchains.hive")
    shippedChain("hive").blobSchedule shouldBe BlobSchedule.GethDefaults
    // All at genesis, staggered, and the rpc-compat fixture's own schedule.
    val schedules = Seq(
      Seq(0L, 0L, 0L, 0L, 0L, 0L),
      Seq(10L, 20L, 30L, 40L, 50L, 60L),
      Seq(0L, 0L, 0L, 450L, 510L, 570L)
    )
    schedules.foreach { case Seq(cancun, prague, osaka, bpo1, bpo2, amsterdam) =>
      val c = BlockchainConfig.fromRawConfig(
        raw
          .withValue("shanghai-timestamp", ConfigValueFactory.fromAnyRef(0L))
          .withValue("cancun-timestamp", ConfigValueFactory.fromAnyRef(cancun))
          .withValue("prague-timestamp", ConfigValueFactory.fromAnyRef(prague))
          .withValue("osaka-timestamp", ConfigValueFactory.fromAnyRef(osaka))
          .withValue("bpo1-timestamp", ConfigValueFactory.fromAnyRef(bpo1))
          .withValue("bpo2-timestamp", ConfigValueFactory.fromAnyRef(bpo2))
          .withValue("amsterdam-timestamp", ConfigValueFactory.fromAnyRef(amsterdam))
      )
      assertLegacyEquivalent(s"hive $cancun/$prague/$osaka/$bpo1/$bpo2/$amsterdam", c)
    }
  }

  it should "take each value from the -D overrides hive/fukuii/fukuii.sh passes, as strings" taggedAs (UnitTest) in {
    val c = BlockchainConfig.fromRawConfig(
      shippedRoot
        .getConfig("fukuii.blockchains.hive")
        .withValue("cancun-timestamp", ConfigValueFactory.fromAnyRef(0L))
        .withValue("bpo1-timestamp", ConfigValueFactory.fromAnyRef(100L))
        .withValue("blob-schedule.bpo1.target", ConfigValueFactory.fromAnyRef("8"))
        .withValue("blob-schedule.bpo1.max", ConfigValueFactory.fromAnyRef("12"))
        .withValue("blob-schedule.bpo1.base-fee-update-fraction", ConfigValueFactory.fromAnyRef("6676955"))
    )
    c.blobParamsAt(Timestamp(100L)) shouldBe Some(BlobParams(8, 12, BigInt(6676955)))
    c.blobParamsAt(Timestamp(99L)) shouldBe Some(BlobParams.Cancun) // no prague-timestamp declared
  }

  "every ETC-family shipped chain conf" should "carry no blob schedule and resolve no blob parameters" taggedAs (
    UnitTest
  ) in {
    Seq("etc", "mordor", "gorgoroth").foreach { name =>
      val c = shippedChain(name)
      withClue(s"$name: ") {
        c.blobSchedule shouldBe BlobSchedule.Empty
        Seq(0L, 1710338135L, Long.MaxValue).foreach(ts => c.blobParamsAt(Timestamp(ts)) shouldBe None)
      }
    }
  }

  // --- validation ---------------------------------------------------------------------------------------------

  private val base: TypesafeConfig = shippedRoot.getConfig("fukuii.blockchains.hive")

  private def load(hocon: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(
      ConfigFactory.parseString(hocon).withFallback(base.withoutPath("blob-schedule")).resolve()
    )

  private def failure(hocon: String): String =
    intercept[BlobSchedule.InvalidBlobSchedule](load(hocon)).getMessage

  "loading a blob schedule" should "refuse a declared blob fork without an entry" taggedAs (UnitTest) in {
    failure("cancun-timestamp = 0") should include("cancun-timestamp is declared but blob-schedule has no cancun")
    failure(
      """cancun-timestamp = 0
               |bpo1-timestamp = 10
               |blob-schedule { cancun { target = 3, max = 6, base-fee-update-fraction = 3338477 } }""".stripMargin
    ) should
      include("no bpo1 entry")
  }

  it should "accept entries for forks the chain does not schedule" taggedAs (UnitTest) in {
    load("""blob-schedule { bpo2 { target = 1, max = 2, base-fee-update-fraction = 3 } }""").blobSchedule shouldBe
      BlobSchedule(bpo2 = Some(BlobParams(1, 2, BigInt(3))))
  }

  it should "refuse max < target, target 0 and a zero or missing fraction" taggedAs (UnitTest) in {
    failure("blob-schedule { cancun { target = 6, max = 3, base-fee-update-fraction = 1 } }") should
      include("cancun.max (3) must be >= target (6)")
    failure("blob-schedule { cancun { target = 0, max = 3, base-fee-update-fraction = 1 } }") should
      include("cancun.target must be > 0")
    failure("blob-schedule { cancun { target = 3, max = 6, base-fee-update-fraction = 0 } }") should
      include("base-fee-update-fraction must be > 0")
    failure("blob-schedule { cancun { target = 3, max = 6 } }") should
      include("cancun.base-fee-update-fraction is missing")
    failure("blob-schedule { cancun { target = 3, max = six, base-fee-update-fraction = 1 } }") should
      include("cancun.max is not an integer")
  }

  it should "refuse unknown fork and field keys, and bpo3+" taggedAs (UnitTest) in {
    failure("blob-schedule { bpo3 { target = 1, max = 2, base-fee-update-fraction = 3 } }") should
      include("unknown fork key(s) bpo3")
    failure("blob-schedule { cancun { target = 3, max = 6, base-fee-update-fraction = 1, maxBlobs = 9 } }") should
      include("unknown key(s) maxBlobs")
    failure("bpo3-timestamp = 100") should include("bpo3-timestamp is declared")
  }

  it should "refuse blob-fork timestamps out of fork order" taggedAs (UnitTest) in {
    val schedule =
      """blob-schedule {
        |  cancun { target = 3, max = 6, base-fee-update-fraction = 3338477 }
        |  prague { target = 6, max = 9, base-fee-update-fraction = 5007716 }
        |  bpo1 { target = 8, max = 12, base-fee-update-fraction = 6676955 }
        |  bpo2 { target = 10, max = 15, base-fee-update-fraction = 8346193 }
        |}""".stripMargin
    failure(s"""$schedule
               |cancun-timestamp = 0
               |prague-timestamp = 0
               |bpo1-timestamp = 200
               |bpo2-timestamp = 100""".stripMargin) should include(
      "bpo1-timestamp (200) is after bpo2-timestamp (100)"
    )
    failure(s"""$schedule
               |cancun-timestamp = 0
               |osaka-timestamp = 300
               |bpo1-timestamp = 200""".stripMargin) should include("osaka-timestamp (300) is after bpo1-timestamp")
    failure(s"""$schedule
               |cancun-timestamp = 0
               |bpo2-timestamp = 200
               |amsterdam-timestamp = 100""".stripMargin) should include("bpo2-timestamp (200) is after amsterdam")
  }

  it should "accept an osaka entry equal to Prague's and refuse one that differs" taggedAs (UnitTest) in {
    val head =
      """cancun-timestamp = 0
        |prague-timestamp = 0
        |osaka-timestamp = 0
        |blob-schedule.cancun { target = 3, max = 6, base-fee-update-fraction = 3338477 }
        |blob-schedule.prague { target = 6, max = 9, base-fee-update-fraction = 5007716 }
        |""".stripMargin
    load(head + "blob-schedule.osaka { target = 6, max = 9, base-fee-update-fraction = 5007716 }").blobSchedule shouldBe
      BlobSchedule(Some(BlobParams.Cancun), Some(BlobParams.Prague))
    failure(head + "blob-schedule.osaka { target = 9, max = 12, base-fee-update-fraction = 5007716 }") should
      include("osaka declares BlobParams(9,12,5007716) but inherits BlobParams(6,9,5007716)")
    failure(head + "blob-schedule.amsterdam { target = 9, max = 12, base-fee-update-fraction = 5007716 }") should
      include("amsterdam declares")
  }
