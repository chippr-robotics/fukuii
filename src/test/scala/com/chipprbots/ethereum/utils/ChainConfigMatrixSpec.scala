package com.chipprbots.ethereum.utils

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*

/** FR-004 as a test rather than a convention.
  *
  * Amsterdam — like every timestamp-gated ETH fork before it — is safe on ETC *by omission*:
  * `ForkTimestamps.amsterdamTimestamp` is an `Option` defaulting to `None`, so `isAmsterdamTimestamp` returns `false`
  * for any config that does not declare `amsterdam-timestamp`. That mechanism is only as good as the shipped config
  * files, and nothing else in the suite reads them — `BlockHeaderFieldCountSpec` asserts the property for a *synthetic*
  * ETC config, which cannot catch a stray key added to `etc-chain.conf`.
  *
  * This spec loads the SHIPPED resources, assembled the same way the application assembles them (`blockchains.conf`
  * mounted at `fukuii`, so the `${fukuii.olympia.treasury-address}` substitutions and the `include required(...)`
  * directives resolve exactly as at runtime), and asserts the absence directly.
  */
class ChainConfigMatrixSpec extends AnyFlatSpec with Matchers:

  /** ETC-family shipped chain configs. Amsterdam must be absent from every one. */
  private val EtcFamilyChains: Seq[String] = Seq("etc", "mordor", "gorgoroth")

  /** The shipped `blockchains.conf` mounted under `fukuii`, resolved. Any failure to resolve — a missing include, a
    * dangling substitution — fails the whole spec loudly rather than silently skipping a chain.
    */
  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private def shippedChain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  "every ETC-family shipped chain config" should "leave amsterdamTimestamp empty" taggedAs (UnitTest) in {
    EtcFamilyChains.foreach { name =>
      withClue(s"$name-chain.conf declared an Amsterdam activation timestamp: ") {
        shippedChain(name).forkTimestamps.amsterdamTimestamp shouldBe None
      }
    }
  }

  it should "report false from isAmsterdamTimestamp at every timestamp probed" taggedAs (UnitTest) in {
    // Long.MaxValue is the strongest probe: if any timestamp were declared, this would activate.
    val probes = Seq(0L, 1L, 1700000000L, Long.MaxValue)
    EtcFamilyChains.foreach { name =>
      val config = shippedChain(name)
      probes.foreach { ts =>
        withClue(s"$name-chain.conf activated Amsterdam at timestamp $ts: ") {
          config.isAmsterdamTimestamp(Timestamp(ts)) shouldBe false
        }
      }
    }
  }

  it should "declare no timestamp-gated ETH fork at all" taggedAs (UnitTest) in {
    // Amsterdam is the fork under change, but the safety argument is identical for every timestamp fork:
    // ETC dispatches on block number only. Assert the whole family, so the next ETH fork inherits the guard.
    EtcFamilyChains.foreach { name =>
      val ft = shippedChain(name).forkTimestamps
      withClue(s"$name-chain.conf: ") {
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

  it should "be identified as ETC by networkType, so the family under test is the intended one" taggedAs (UnitTest) in {
    // Guards against the spec quietly testing the wrong three configs after a rename.
    EtcFamilyChains.foreach { name =>
      withClue(s"$name-chain.conf: ") {
        shippedChain(name).networkType shouldBe NetworkType.ETC
      }
    }
  }

  "the live ETH-family shipped configs" should "not declare Amsterdam either (T006: hive only)" taggedAs (UnitTest) in {
    // eth-chain.conf and sepolia-chain.conf must NOT declare it — neither network has scheduled Amsterdam,
    // and declaring it would activate untested consensus rules on a live chain.
    Seq("eth", "sepolia").foreach { name =>
      withClue(s"$name-chain.conf declared an Amsterdam activation timestamp: ") {
        shippedChain(name).forkTimestamps.amsterdamTimestamp shouldBe None
      }
    }
  }

  // The positive control. Without it, the assertions above would still pass if the reader silently
  // failed to parse `amsterdam-timestamp` at all — the failure mode that makes safety-by-omission
  // untestable rather than merely untested.
  "the Amsterdam reader" should "parse amsterdam-timestamp when a config does declare it" taggedAs (UnitTest) in {
    val etc = shippedRoot.getConfig("fukuii.blockchains.etc")
    val withAmsterdam = ConfigFactory.parseString("amsterdam-timestamp = 360").withFallback(etc)
    val parsed = BlockchainConfig.fromRawConfig(withAmsterdam)
    parsed.forkTimestamps.amsterdamTimestamp shouldBe Some(360L)
    parsed.isAmsterdamTimestamp(Timestamp(359L)) shouldBe false
    parsed.isAmsterdamTimestamp(Timestamp(360L)) shouldBe true
  }
