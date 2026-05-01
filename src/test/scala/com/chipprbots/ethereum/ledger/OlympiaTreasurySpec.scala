package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** L8 — ECIP-1112 Olympia treasury address configuration tests.
  *
  * TreasuryBaseFeeSpec covers behavioral correctness (baseFee * gasUsed credited post-Olympia). This spec covers the
  * config-level address invariants: the treasury address must be set to the canonical deployed contract on all
  * ETC-family chains.
  *
  * Deployed treasury: 0xd6165F3aF4281037bce810621F62B43077Fb0e37 (identical across ETC mainnet and Mordor — cross-chain
  * consistency requirement).
  */
// scalastyle:off magic.number
class OlympiaTreasurySpec extends AnyFlatSpec with Matchers {

  private val fullConfig = ConfigFactory.load()
  private val etcConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
  private val mordorConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.mordor"))

  // The canonical treasury address deployed on ETC mainnet and Mordor.
  private val CanonicalTreasury: Address = Address("d6165F3aF4281037bce810621F62B43077Fb0e37")

  // ── ETC mainnet ──────────────────────────────────────────────────────────

  "ETC mainnet treasury" should "be set to the canonical Olympia treasury contract" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    etcConfig.treasuryAddress shouldBe CanonicalTreasury
  }

  it should "not be the zero address (fees would be silently burned)" taggedAs (UnitTest, OlympiaTest) in {
    etcConfig.treasuryAddress should not be Address(0)
  }

  // ── Mordor testnet ────────────────────────────────────────────────────────

  "Mordor treasury" should "be set to the canonical Olympia treasury contract" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    mordorConfig.treasuryAddress shouldBe CanonicalTreasury
  }

  it should "not be the zero address" taggedAs (UnitTest, OlympiaTest) in {
    mordorConfig.treasuryAddress should not be Address(0)
  }

  // ── Cross-chain consistency ───────────────────────────────────────────────

  "ETC mainnet and Mordor treasury addresses" should "be identical (same deployed contract)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    etcConfig.treasuryAddress shouldBe mordorConfig.treasuryAddress
  }

  // ── Olympia block number sanity ───────────────────────────────────────────

  "ETC mainnet Olympia block number" should "be set to a future block (not yet activated)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    // Olympia is pending activation; block number must be far in the future
    etcConfig.forkBlockNumbers.olympiaBlockNumber should be > BigInt("1000000000000")
  }

  "Mordor Olympia block number" should "be set to a future block (not yet activated)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    mordorConfig.forkBlockNumbers.olympiaBlockNumber should be > BigInt("1000000000000")
  }
}
// scalastyle:on magic.number
