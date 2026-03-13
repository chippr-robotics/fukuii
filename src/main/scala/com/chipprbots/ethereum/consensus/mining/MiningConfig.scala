package com.chipprbots.ethereum.consensus.mining

import org.apache.pekko.util.ByteString

import com.typesafe.config.{Config => TypesafeConfig}

import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.utils.Logger

/** Provides generic mining configuration. Each consensus protocol implementation will use its own specific
  * configuration as well.
  *
  * @param protocol
  *   Designates the mining protocol.
  * @param miningEnabled
  *   Provides support for generalized "mining". The exact semantics are up to the specific mining protocol
  *   implementation.
  */
/** @param gasLimitTarget
  *   Target gas limit for mined blocks. The miner will gradually adjust the gas limit toward this target at ±1/1024 per
  *   block, matching the consensus-enforced bound divisor. ETC mainnet default: 8,000,000.
  */
final case class MiningConfig(
    protocol: Protocol,
    coinbase: Address,
    headerExtraData: ByteString, // only used in BlockGenerator
    blockCacheSize: Int, // only used in BlockGenerator
    miningEnabled: Boolean,
    gasLimitTarget: BigInt
)

object MiningConfig extends Logger {
  object Keys {
    final val Mining = "mining"
    final val Protocol = "protocol"
    final val Coinbase = "coinbase"
    final val HeaderExtraData = "header-extra-data"
    final val BlockCacheSize = "block-cashe-size"
    final val MiningEnabled = "mining-enabled"
    final val GasLimitTarget = "gas-limit-target"
  }

  final val AllowedProtocols: Set[String] = Set(
    Protocol.Names.PoW,
    Protocol.Names.MockedPow,
    Protocol.Names.RestrictedPoW
  )

  final val AllowedProtocolsError: String => String = (s: String) =>
    Keys.Mining +
      " is configured as '" + s + "'" +
      " but it should be one of " +
      AllowedProtocols.map("'" + _ + "'").mkString(",")

  private def readProtocol(miningConfig: TypesafeConfig): Protocol = {
    val protocol = miningConfig.getString(Keys.Protocol)

    // If the mining protocol is not a known one, then it is a fatal error
    // and the application must exit.
    if (!AllowedProtocols(protocol)) {
      val error = AllowedProtocolsError(protocol)
      throw new RuntimeException(error)
    }

    Protocol(protocol)
  }

  def apply(fukuiiConfig: TypesafeConfig): MiningConfig = {
    val config = fukuiiConfig.getConfig(Keys.Mining)

    val protocol = readProtocol(config)
    val coinbase = Address(config.getString(Keys.Coinbase))

    val headerExtraData = ByteString(config.getString(Keys.HeaderExtraData).getBytes)
      .take(BlockHeaderValidator.MaxExtraDataSize)
    val blockCacheSize = config.getInt(Keys.BlockCacheSize)
    val miningEnabled = config.getBoolean(Keys.MiningEnabled)
    val gasLimitTarget =
      if (config.hasPath(Keys.GasLimitTarget)) BigInt(config.getLong(Keys.GasLimitTarget))
      else BigInt(8_000_000) // ETC mainnet default

    new MiningConfig(
      protocol = protocol,
      coinbase = coinbase,
      headerExtraData = headerExtraData,
      blockCacheSize = blockCacheSize,
      miningEnabled = miningEnabled,
      gasLimitTarget = gasLimitTarget
    )
  }
}
