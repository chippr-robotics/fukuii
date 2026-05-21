package com.chipprbots.ethereum.consensus.mess

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** L8 — MESSConfig.reactivationBlock parsing: olympia-block-number fallback.
  *
  * BlockchainConfig.fromRawConfig derives reactivationBlock from (in priority order):
  *   1. mess.ecbp1100-reactivate-block-number (explicit, optional)
  *   2. olympia-block-number from the parent chain config (fallback)
  *
  * This means neither the ETC mainnet config nor the Mordor config needs a
  * separate reactivation key — MESS reactivation tracks the Olympia fork block
  * automatically. When the real Olympia block is chosen, only
  * olympia-block-number needs updating.
  */
// scalastyle:off magic.number
class MESSConfigParsingSpec extends AnyFlatSpec with Matchers {

  private val fullConfig = ConfigFactory.load()
  private val etcRaw     = fullConfig.getConfig("fukuii.blockchains.etc")
  private val mordorRaw  = fullConfig.getConfig("fukuii.blockchains.mordor")

  private val OlympiaSentinel: BigInt = BigInt("1000000000000000000")

  // ── fallback from olympia-block-number ──────────────────────────────────

  "MESSConfig.reactivationBlock for ETC mainnet" should
    "be derived from olympia-block-number when ecbp1100-reactivate-block-number is absent" taggedAs (
      UnitTest,
      OlympiaTest
    ) in {
    val config = BlockchainConfig.fromRawConfig(etcRaw)
    config.messConfig.reactivationBlock shouldBe Some(OlympiaSentinel)
  }

  "MESSConfig.reactivationBlock for Mordor" should
    "be derived from olympia-block-number when ecbp1100-reactivate-block-number is absent" taggedAs (
      UnitTest,
      OlympiaTest
    ) in {
    val config = BlockchainConfig.fromRawConfig(mordorRaw)
    config.messConfig.reactivationBlock shouldBe Some(OlympiaSentinel)
  }

  // ── explicit key takes priority ─────────────────────────────────────────

  "MESSConfig.reactivationBlock" should
    "use ecbp1100-reactivate-block-number when both explicit key and olympia-block-number are present" taggedAs (
      UnitTest,
      OlympiaTest
    ) in {
    val rawWithExplicit = etcRaw
      .withValue("mess.ecbp1100-reactivate-block-number", ConfigValueFactory.fromAnyRef("99999"))
    val config = BlockchainConfig.fromRawConfig(rawWithExplicit)
    config.messConfig.reactivationBlock shouldBe Some(BigInt(99999))
  }

  // ── None when neither key is present ────────────────────────────────────

  it should "be None when both ecbp1100-reactivate-block-number and olympia-block-number are absent" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    val rawNoOlympia = etcRaw
      .withoutPath("olympia-block-number")
    val config = BlockchainConfig.fromRawConfig(rawNoOlympia)
    config.messConfig.reactivationBlock shouldBe None
  }
}
