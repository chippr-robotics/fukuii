package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 021 F06 (forge C17): the opcode-table golden, so forge's S5 ("no change to `forBlock`, the ETC opcode tables or
  * the fork tables") has a test and not only a statement. S3 rewrites `OpCode.scala`, which also holds the tables.
  *
  * For each ETC fork (`forBlock` at its boundary block on the shipped ETC config, Olympia at EtcForkMatrix's test
  * block) and each ETH fork (`forBlock` on the shipped ETH mainnet config, by block and then by timestamp; Amsterdam
  * from Sepolia), it records the selected fee schedule and fork flags, then every opcode's (byte, name, delta, alpha,
  * constant gas). The golden is `src/test/resources/spec021/opcode-tables.golden`, captured on the pre-swap tree.
  */
// scalastyle:off magic.number
class OpCodeTableGoldenSpec extends AnyFunSuite with Matchers:
  import PinHarness.*

  private def etcForks: Seq[(String, BigInt)] =
    val f = EtcForkMatrix.shipped.forkBlockNumbers
    Seq(
      "Frontier" -> f.frontierBlockNumber,
      "Homestead" -> f.homesteadBlockNumber,
      "EIP-150" -> f.eip150BlockNumber,
      "EIP-155/EIP-160" -> f.eip160BlockNumber,
      "Atlantis" -> f.atlantisBlockNumber,
      "Agharta" -> f.aghartaBlockNumber,
      "Phoenix" -> f.phoenixBlockNumber,
      "Magneto" -> f.magnetoBlockNumber,
      "Mystique" -> f.mystiqueBlockNumber,
      "Spiral" -> f.spiralBlockNumber,
      "Olympia (test block)" -> EtcForkMatrix.OlympiaTestBlock
    )

  /** (name, block, timestamp) on ETH mainnet. Before Shanghai the timestamp is 0; from it, the fork's activation. */
  private def ethForks: Seq[(String, BigInt, Long)] =
    val f = ethMainnet.forkBlockNumbers
    Seq(
      ("Frontier", f.frontierBlockNumber, 0L),
      ("Homestead", f.homesteadBlockNumber, 0L),
      ("Tangerine Whistle (EIP-150)", f.eip150BlockNumber, 0L),
      ("Spurious Dragon (EIP-155/158/160)", f.eip160BlockNumber, 0L),
      ("Byzantium", f.byzantiumBlockNumber, 0L),
      ("Constantinople/Petersburg", f.petersburgBlockNumber, 0L),
      ("Istanbul", f.istanbulBlockNumber, 0L),
      ("Berlin", f.berlinBlockNumber, 0L),
      ("London", f.olympiaBlockNumber, 0L), // ETH maps London onto olympia-block-number (eth-chain.conf)
      ("Paris", 15_537_394, 0L),
      ("Shanghai", EthPostMergeBlock, ethTimestamp("shanghai")),
      ("Cancun", EthPostMergeBlock, ethTimestamp("cancun")),
      ("Prague", EthPostMergeBlock, ethTimestamp("prague")),
      ("Osaka", EthPostMergeBlock, ethTimestamp("osaka"))
    )

  private def flags(c: EvmConfig): String =
    Seq(
      s"subGasCapDivisor=${c.subGasCapDivisor.getOrElse("-")}",
      s"exceptionalFailedCodeDeposit=${c.exceptionalFailedCodeDeposit}",
      s"chargeSelfDestructForNewAccount=${c.chargeSelfDestructForNewAccount}",
      s"noEmptyAccounts=${c.noEmptyAccounts}",
      s"eip3541=${c.eip3541Enabled}",
      s"eip3651=${c.eip3651Enabled}",
      s"eip3860=${c.eip3860Enabled}",
      s"eip6049=${c.eip6049DeprecationEnabled}",
      s"eip6780=${c.eip6780Enabled}",
      s"eip7702=${c.eip7702Enabled}",
      s"amsterdam=${c.amsterdamEnabled}"
    ).mkString(" ")

  private def section(title: String, c: EvmConfig): String =
    val header =
      s"== $title: fee=${c.feeSchedule.getClass.getSimpleName} opcodes=${c.opCodes.size}\n   ${flags(c)}"
    val rows = c.opCodes
      .sortBy(_.code & 0xff)
      .map(op =>
        f"0x${op.code & 0xff}%02x $op%-14s delta=${op.delta}%d alpha=${op.alpha}%d gas=${op.baseGasFn(c.feeSchedule)}"
      )
    (header +: rows).mkString("\n")

  private def tables: String =
    val etc = etcForks.map { (name, block) =>
      section(s"ETC $name block=$block", EvmConfig.forBlock(block, EtcForkMatrix.blockchainConfig))
    }
    val eth = ethForks.map { (name, block, ts) =>
      section(s"ETH $name block=$block timestamp=$ts", EvmConfig.forBlock(block, Timestamp(ts), ethMainnet))
    }
    val (amsterdam, amsterdamHeader) = sepoliaAmsterdam
    val ams = section(
      s"ETH Amsterdam (Sepolia) block=${amsterdamHeader.number.value} timestamp=${amsterdamHeader.unixTimestamp.toLong}",
      amsterdam
    )
    ((
      "# spec 021 F06 (forge S5, C17): the opcode tables each ETC and ETH fork selects (OpCodeTableGoldenSpec).\n" +
        "# Captured on the pre-swap tree; never edited by a slice. Per opcode: byte, name, delta, alpha, constant gas."
    ) +: (etc ++ eth :+ ams)).mkString("", "\n\n", "\n")

  test("F06 every ETC and ETH fork selects the opcode table it selected before spec 021", UnitTest, VMTest) {
    assertGolden("spec021/opcode-tables.golden", tables)
  }
