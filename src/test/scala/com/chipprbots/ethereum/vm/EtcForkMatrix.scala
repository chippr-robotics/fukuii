package com.chipprbots.ethereum.vm

import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 021 C14: the ETC (config, block) matrix.
  *
  * The ETC chain config exactly as shipped (`etc-chain.conf`), with one block in each era, and Olympia activated at a
  * test block because mainnet has not scheduled it. SSTORE's path and the EIP-2929 access costs are chosen from the
  * HEADER's block number (`etcForkForBlockNumber`), not from the `EvmConfig` builder, so a pin that must hold on ETC
  * runs each era as a pair: the config `forBlock` selects there, and a header at that number. The builders alone
  * (`Fixtures.blockchainConfig`) never reach the pre-Phoenix SSTORE path or the pre-Magneto access costs.
  *
  * Used by F03 to F05 (EtcOpCodeEdgeRowsSpec), F07 (OpCodeContractSpec), and from S3 on by W04 to W06.
  */
// scalastyle:off magic.number
object EtcForkMatrix:

  /** ETC mainnet as shipped: chain id 61, Olympia unscheduled (10^18). */
  val shipped: BlockchainConfig =
    BlockchainConfig.fromRawConfig(ConfigFactory.load().getConfig("fukuii.blockchains.etc"))

  /** Above Spiral (19,250,000). Any block past Spiral selects the same Olympia rules; this one is only a test value. */
  val OlympiaTestBlock: BigInt = 20_000_000

  /** The shipped config with Olympia at [[OlympiaTestBlock]]. Every other fork number is the shipped one. */
  val blockchainConfig: BlockchainConfigForEvm =
    BlockchainConfigForEvm(shipped).copy(olympiaBlockNumber = OlympiaTestBlock)

  /** Olympia's base fee in the test header, so BASEFEE has something to push. */
  val OlympiaBaseFee: BigInt = BigInt(1_000_000_000)

  /** One era: the config `forBlock` selects at `block`, and a header at that number. */
  final case class Era(name: String, block: BigInt):
    val evmConfig: EvmConfig = EvmConfig.forBlock(block, blockchainConfig)
    val header: BlockHeader =
      BlockFixtures.ValidBlock.header.copy(
        number = BlockNumber(block),
        extraFields = if block >= OlympiaTestBlock then HefPostOlympia(OlympiaBaseFee) else HefEmpty
      )
    override def toString: String = s"ETC $name (block $block)"

  private val forks = shipped.forkBlockNumbers

  val Frontier: Era = Era("Frontier", 1_000_000) // below Homestead, 1,150,000
  val Homestead: Era = Era("Homestead", 2_000_000) // 1,150,000 to 2,500,000: pre-EIP-150
  val PostEip150: Era = Era("EIP-150/EIP-160", 5_000_000) // 2,500,000 to 8,772,000
  val Atlantis: Era = Era("Atlantis", forks.atlantisBlockNumber)
  val Agharta: Era = Era("Agharta", forks.aghartaBlockNumber)
  val Phoenix: Era = Era("Phoenix", forks.phoenixBlockNumber)
  val Magneto: Era = Era("Magneto", forks.magnetoBlockNumber)
  val Mystique: Era = Era("Mystique", forks.mystiqueBlockNumber)
  val Spiral: Era = Era("Spiral", forks.spiralBlockNumber)
  val Olympia: Era = Era("Olympia", OlympiaTestBlock)

  /** Every era, oldest first. */
  val eras: Seq[Era] =
    Seq(Frontier, Homestead, PostEip150, Atlantis, Agharta, Phoenix, Magneto, Mystique, Spiral, Olympia)

  // The within-era picks above are literals; fail loudly if the shipped config ever moves a boundary past them.
  require(
    shipped.chainId.value == 61 &&
      Frontier.block < forks.homesteadBlockNumber &&
      Homestead.block >= forks.homesteadBlockNumber && Homestead.block < forks.eip150BlockNumber &&
      PostEip150.block >= forks.eip160BlockNumber && PostEip150.block < forks.atlantisBlockNumber &&
      forks.spiralBlockNumber < OlympiaTestBlock && OlympiaTestBlock < forks.olympiaBlockNumber,
    "EtcForkMatrix: the shipped ETC config no longer matches the matrix's era picks"
  )
