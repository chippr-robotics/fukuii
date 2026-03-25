package com.chipprbots.ethereum.consensus.mining

import org.apache.pekko.util.ByteString

import com.typesafe.config.{Config => TypesafeConfig}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

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
/** @param notifyUrls
  *   HTTP URLs to POST new work packages to when a new block is available for mining. Matches geth's `miner.notify`
  *   config. Each URL receives a JSON array: [powHeaderHash, dagSeed, target, blockNumber].
  * @param notifyFull
  *   If true, POST full block header JSON instead of the work array. Matches geth's `miner.notify.full`.
  * @param recommitInterval
  *   Interval for regenerating mining work with latest pending transactions. Matches geth's `miner.recommit` (default
  *   2s). Set to Duration.Zero to disable.
  * @param noverify
  *   If true, skip PoW verification when accepting submitted work via eth_submitWork. Matches geth's `miner.noverify`.
  *   Use for trusted pool connections where the pool has already verified the solution.
  */
final case class MiningConfig(
    protocol: Protocol,
    coinbase: Address,
    headerExtraData: ByteString, // only used in BlockGenerator
    blockCacheSize: Int, // only used in BlockGenerator
    miningEnabled: Boolean,
    gasLimitTarget: BigInt,
    notifyUrls: Seq[String] = Seq.empty,
    notifyFull: Boolean = false,
    recommitInterval: FiniteDuration = 2.seconds,
    noverify: Boolean = false
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
    final val NotifyUrls = "notify"
    final val NotifyFull = "notify-full"
    final val RecommitInterval = "recommit-interval"
    final val Noverify = "noverify"
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

    val notifyUrls =
      if (config.hasPath(Keys.NotifyUrls)) config.getStringList(Keys.NotifyUrls).asScala.toSeq
      else Seq.empty

    val notifyFull =
      if (config.hasPath(Keys.NotifyFull)) config.getBoolean(Keys.NotifyFull)
      else false

    val recommitInterval =
      if (config.hasPath(Keys.RecommitInterval)) config.getDuration(Keys.RecommitInterval).toMillis.millis
      else 2.seconds

    val noverify =
      if (config.hasPath(Keys.Noverify)) config.getBoolean(Keys.Noverify)
      else false

    new MiningConfig(
      protocol = protocol,
      coinbase = coinbase,
      headerExtraData = headerExtraData,
      blockCacheSize = blockCacheSize,
      miningEnabled = miningEnabled,
      gasLimitTarget = gasLimitTarget,
      notifyUrls = notifyUrls,
      notifyFull = notifyFull,
      recommitInterval = recommitInterval,
      noverify = noverify
    )
  }
}
