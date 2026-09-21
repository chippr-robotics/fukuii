package com.chipprbots.ethereum.utils

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** FR-004 as a test rather than a convention.
  *
  * Amsterdam (and every other timestamp fork) is safe on ETC *by omission*: `ForkTimestamps.amsterdamTimestamp` is an
  * `Option` defaulting to `None`, so `isAmsterdamTimestamp` is `false` for any config that does not declare
  * `amsterdam-timestamp`. That mechanism is only as good as the shipped config files, and nothing else in the suite
  * reads them — `BlockHeaderFieldCountSpec` asserts the property for a *synthetic* ETC config, which cannot catch a
  * stray key added to `etc-chain.conf`.
  *
  * This spec loads the SHIPPED resources and asserts the absence directly.
  */
class ChainConfigMatrixSpec extends AnyFlatSpec with Matchers:

  /** ETC-family shipped chain configs. Amsterdam must be absent from every one. */
  private val EtcFamilyConfigs: Seq[String] =
    Seq("etc-chain.conf", "mordor-chain.conf", "gorgoroth-chain.conf")

  private def loadShipped(name: String): TypesafeConfig =
    val resource = s"conf/base/chains/$name"
    val url = Option(getClass.getClassLoader.getResource(resource))
      .getOrElse(fail(s"shipped chain config not found on the classpath: $resource"))
    ConfigFactory.parseURL(url).resolve()

  "every ETC-family shipped chain config" should "leave amsterdamTimestamp empty" taggedAs (UnitTest) in {
    EtcFamilyConfigs.foreach { name =>
      val parsed = BlockchainConfig.fromRawConfig(loadShipped(name))
      withClue(s"$name declared an Amsterdam activation timestamp: ") {
        parsed.forkTimestamps.amsterdamTimestamp shouldBe None
      }
    }
  }

  it should "report false from isAmsterdamTimestamp at every timestamp probed" taggedAs (UnitTest) in {
    // Long.MaxValue is the strongest probe: if a timestamp were declared at all, this would activate.
    val probes = Seq(0L, 1L, 1700000000L, Long.MaxValue)
    EtcFamilyConfigs.foreach { name =>
      val parsed = BlockchainConfig.fromRawConfig(loadShipped(name))
      probes.foreach { ts =>
        withClue(s"$name activated Amsterdam at timestamp $ts: ") {
          parsed.isAmsterdamTimestamp(com.chipprbots.ethereum.domain.Timestamp(ts)) shouldBe false
        }
      }
    }
  }

  it should "declare no timestamp-gated ETH fork at all" taggedAs (UnitTest) in {
    // Amsterdam is the fork under change, but the safety argument is the same for every timestamp fork:
    // ETC dispatches on block number only. Assert the whole family, so the next ETH fork inherits the guard.
    EtcFamilyConfigs.foreach { name =>
      val ft = BlockchainConfig.fromRawConfig(loadShipped(name)).forkTimestamps
      withClue(s"$name: ") {
        ft.shanghaiTimestamp shouldBe None
        ft.cancunTimestamp shouldBe None
        ft.pragueTimestamp shouldBe None
        ft.osakaTimestamp shouldBe None
        ft.amsterdamTimestamp shouldBe None
        ft.bpo1Timestamp shouldBe None
        ft.bpo2Timestamp shouldBe None
      }
    }
  }

  // The positive control. Without it, the three assertions above would still pass if the reader
  // silently failed to parse `amsterdam-timestamp` at all — which is the failure mode that makes
  // safety-by-omission untestable.
  "the Amsterdam reader" should "parse amsterdam-timestamp when a config does declare it" taggedAs (UnitTest) in {
    val base = loadShipped("etc-chain.conf")
    val withAmsterdam = ConfigFactory
      .parseString("amsterdam-timestamp = 360")
      .withFallback(base)
      .resolve()
    val parsed = BlockchainConfig.fromRawConfig(withAmsterdam)
    parsed.forkTimestamps.amsterdamTimestamp shouldBe Some(360L)
    parsed.isAmsterdamTimestamp(com.chipprbots.ethereum.domain.Timestamp(359L)) shouldBe false
    parsed.isAmsterdamTimestamp(com.chipprbots.ethereum.domain.Timestamp(360L)) shouldBe true
  }
