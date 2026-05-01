package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.BLOBBASEFEE
import com.chipprbots.ethereum.vm.BLOBHASH
import com.chipprbots.ethereum.vm.CHAINID
import com.chipprbots.ethereum.vm.CREATE2
import com.chipprbots.ethereum.vm.EXTCODEHASH
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.OpCode
import com.chipprbots.ethereum.vm.PUSH0
import com.chipprbots.ethereum.vm.RETURNDATACOPY
import com.chipprbots.ethereum.vm.REVERT
import com.chipprbots.ethereum.vm.SAR
import com.chipprbots.ethereum.vm.SELFBALANCE
import com.chipprbots.ethereum.vm.SHL
import com.chipprbots.ethereum.vm.SHR

// scalastyle:off magic.number
/** L2 — ETC fork compliance: verify opcode availability at boundary blocks (N-1 disabled, N enabled).
  *
  * Pattern adapted from core-geth's etc_fork_compliance_test.go and besu's GenesisConfigClassicTest. Tests that the EVM
  * opcode set transitions correctly at each ETC hard fork for both mainnet (chainId=61) and Mordor testnet
  * (chainId=63).
  *
  * Fork → Canonical opcode marker: Atlantis (≈Byzantium) — REVERT, RETURNDATACOPY Agharta (≈Constantinople) — CREATE2,
  * EXTCODEHASH, SHL/SHR/SAR Phoenix (≈Istanbul) — CHAINID, SELFBALANCE Magneto (≈Berlin) — (same opcodes as Phoenix;
  * EIP-2929 access list gas only) Mystique (≈London) — eip3541Enabled (no new opcodes) Spiral (≈Shanghai) — PUSH0
  * No-fork — BLOBHASH, BLOBBASEFEE never active on ETC (no Cancun/Osaka EVM)
  *
  * ECIP-1099 (epoch doubling) and ECBP-1100 (MESS) have no opcode markers and are covered separately.
  */
class ETCForkComplianceSpec extends AnyFlatSpec with Matchers {

  private val fullConfig = ConfigFactory.load()
  private val etcConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.etc"))
  private val mordorConfig = BlockchainConfig.fromRawConfig(fullConfig.getConfig("fukuii.blockchains.mordor"))

  private def opcodes(blockNumber: BigInt, cfg: BlockchainConfig): Set[OpCode] =
    EvmConfig.forBlock(blockNumber, cfg).opCodes.toSet

  private def assertEnabled(cfg: BlockchainConfig, forkName: String, block: BigInt, marker: OpCode): Unit =
    withClue(
      s"$forkName opcode ${marker.getClass.getSimpleName.stripSuffix("$")} should be ENABLED at block $block: "
    ) {
      opcodes(block, cfg) should contain(marker)
    }

  private def assertDisabled(cfg: BlockchainConfig, forkName: String, block: BigInt, marker: OpCode): Unit =
    withClue(
      s"$forkName opcode ${marker.getClass.getSimpleName.stripSuffix("$")} should be DISABLED at block $block: "
    ) {
      opcodes(block, cfg) should not contain marker
    }

  // ── ETC Mainnet ─────────────────────────────────────────────────────────────

  "ETC mainnet Atlantis (8,772,000)" should "not have REVERT at block 8,771,999" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(etcConfig, "Atlantis", BigInt(8_771_999), REVERT)
  }

  it should "have REVERT at block 8,772,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Atlantis", BigInt(8_772_000), REVERT)
  }

  it should "not have RETURNDATACOPY at block 8,771,999" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(etcConfig, "Atlantis", BigInt(8_771_999), RETURNDATACOPY)
  }

  it should "have RETURNDATACOPY at block 8,772,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Atlantis", BigInt(8_772_000), RETURNDATACOPY)
  }

  "ETC mainnet Agharta (9,573,000)" should "not have CREATE2 at block 9,572,999" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(etcConfig, "Agharta", BigInt(9_572_999), CREATE2)
  }

  it should "have CREATE2 at block 9,573,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Agharta", BigInt(9_573_000), CREATE2)
  }

  it should "not have EXTCODEHASH at block 9,572,999" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(etcConfig, "Agharta", BigInt(9_572_999), EXTCODEHASH)
  }

  it should "have EXTCODEHASH at block 9,573,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Agharta", BigInt(9_573_000), EXTCODEHASH)
  }

  it should "have SHL/SHR/SAR at block 9,573,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Agharta", BigInt(9_573_000), SHL)
    assertEnabled(etcConfig, "Agharta", BigInt(9_573_000), SHR)
    assertEnabled(etcConfig, "Agharta", BigInt(9_573_000), SAR)
  }

  "ETC mainnet Phoenix (10,500,839)" should "not have CHAINID at block 10,500,838" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    assertDisabled(etcConfig, "Phoenix", BigInt(10_500_838), CHAINID)
  }

  it should "have CHAINID at block 10,500,839" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Phoenix", BigInt(10_500_839), CHAINID)
  }

  it should "not have SELFBALANCE at block 10,500,838" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(etcConfig, "Phoenix", BigInt(10_500_838), SELFBALANCE)
  }

  it should "have SELFBALANCE at block 10,500,839" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Phoenix", BigInt(10_500_839), SELFBALANCE)
  }

  "ETC mainnet Mystique (14,525,000)" should "have eip3541 disabled at block 14,524,999" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    EvmConfig.forBlock(BigInt(14_524_999), etcConfig).eip3541Enabled shouldBe false
  }

  it should "have eip3541 enabled at block 14,525,000" taggedAs (UnitTest, ConsensusTest) in {
    EvmConfig.forBlock(BigInt(14_525_000), etcConfig).eip3541Enabled shouldBe true
  }

  "ETC mainnet Spiral (19,250,000)" should "not have PUSH0 at block 19,249,999" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    assertDisabled(etcConfig, "Spiral", BigInt(19_249_999), PUSH0)
  }

  it should "have PUSH0 at block 19,250,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(etcConfig, "Spiral", BigInt(19_250_000), PUSH0)
  }

  "ETC mainnet" should "never have BLOBHASH (no Cancun on ETC EVM)" taggedAs (UnitTest, ConsensusTest) in {
    // At Spiral (highest ETC fork) BLOBHASH must NOT be available
    assertDisabled(etcConfig, "no-Cancun", BigInt(19_250_000), BLOBHASH)
    assertDisabled(etcConfig, "no-Cancun", BigInt(100_000_000), BLOBHASH)
  }

  it should "never have BLOBBASEFEE (no Cancun on ETC EVM)" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(etcConfig, "no-Cancun", BigInt(19_250_000), BLOBBASEFEE)
    assertDisabled(etcConfig, "no-Cancun", BigInt(100_000_000), BLOBBASEFEE)
  }

  // ── Mordor Testnet ───────────────────────────────────────────────────────────

  "Mordor Agharta (301,243)" should "not have CREATE2 at block 301,242" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(mordorConfig, "Agharta", BigInt(301_242), CREATE2)
  }

  it should "have CREATE2 at block 301,243" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(mordorConfig, "Agharta", BigInt(301_243), CREATE2)
  }

  "Mordor Phoenix (999,983)" should "not have CHAINID at block 999,982" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(mordorConfig, "Phoenix", BigInt(999_982), CHAINID)
  }

  it should "have CHAINID at block 999,983" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(mordorConfig, "Phoenix", BigInt(999_983), CHAINID)
  }

  "Mordor Spiral (9,957,000)" should "not have PUSH0 at block 9,956,999" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(mordorConfig, "Spiral", BigInt(9_956_999), PUSH0)
  }

  it should "have PUSH0 at block 9,957,000" taggedAs (UnitTest, ConsensusTest) in {
    assertEnabled(mordorConfig, "Spiral", BigInt(9_957_000), PUSH0)
  }

  "Mordor" should "never have BLOBHASH (no Cancun on Mordor EVM)" taggedAs (UnitTest, ConsensusTest) in {
    assertDisabled(mordorConfig, "no-Cancun", BigInt(9_957_000), BLOBHASH)
    assertDisabled(mordorConfig, "no-Cancun", BigInt(100_000_000), BLOBHASH)
  }

  // ── CHAINID value on ETC mainnet ────────────────────────────────────────────

  "CHAINID opcode" should "return 61 on ETC mainnet after Phoenix" taggedAs (UnitTest, ConsensusTest) in {
    etcConfig.chainId shouldBe 61
  }

  it should "return 63 on Mordor after Phoenix" taggedAs (UnitTest, ConsensusTest) in {
    mordorConfig.chainId shouldBe 63
  }
}
// scalastyle:on magic.number
