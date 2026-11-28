package com.chipprbots.ethereum.consensus.mining

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import com.typesafe.config.Config as TypesafeConfig

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
    gasLimitTarget: BigInt,
    notifyUrls: Seq[String],
    staleThreshold: Int,
    recommitInterval: FiniteDuration
)

object MiningConfig extends Logger:

  /** The coinbase `conf/base/mining.conf` ships, `0x0011…99`: no one's address, the coinbase of a node whose operator
    * set none ([[CoinbaseProvider.of]]). It must be the value that file sets (CoinbaseProviderSpec reads it).
    */
  val DefaultCoinbase: Address = Address("0x0011223344556677889900112233445566778899")
  object Keys:
    final val Mining = "mining"
    final val Protocol = "protocol"
    final val Coinbase = "coinbase"
    final val HeaderExtraData = "header-extra-data"
    final val BlockCacheSize = "block-cache-size"
    final val MiningEnabled = "mining-enabled"
    final val GasLimitTarget = "gas-limit-target"
    final val NotifyUrls = "notify-urls"
    final val StaleThreshold = "stale-threshold"
    final val RecommitInterval = "recommit-interval"

  final val AllowedProtocols: Set[String] = Protocol.KnownProtocolNames

  final val AllowedProtocolsError: String => String = (s: String) =>
    Keys.Mining +
      " is configured as '" + s + "'" +
      " but it should be one of " +
      AllowedProtocols.map("'" + _ + "'").mkString(",")

  private def readProtocol(miningConfig: TypesafeConfig): Protocol =
    val protocol = miningConfig.getString(Keys.Protocol)

    // If the mining protocol is not a known one, then it is a fatal error
    // and the application must exit.
    if !AllowedProtocols(protocol) then
      val error = AllowedProtocolsError(protocol)
      throw new RuntimeException(error)

    Protocol(protocol)

  def apply(fukuiiConfig: TypesafeConfig): MiningConfig =
    val config = fukuiiConfig.getConfig(Keys.Mining)

    val protocol = readProtocol(config)
    val coinbase = Address(config.getString(Keys.Coinbase))

    val headerExtraData = ByteString(config.getString(Keys.HeaderExtraData).getBytes)
      .take(BlockHeaderValidator.MaxExtraDataSize)
    val blockCacheSize = config.getInt(Keys.BlockCacheSize)
    val miningEnabled = config.getBoolean(Keys.MiningEnabled)
    val gasLimitTarget =
      if config.hasPath(Keys.GasLimitTarget) then BigInt(config.getLong(Keys.GasLimitTarget))
      else BigInt(8_000_000) // ETC mainnet default
    val notifyUrls =
      if config.hasPath(Keys.NotifyUrls) then config.getStringList(Keys.NotifyUrls).asScala.toSeq
      else Seq.empty
    val staleThreshold =
      if config.hasPath(Keys.StaleThreshold) then config.getInt(Keys.StaleThreshold)
      else 7 // core-geth default
    val recommitInterval =
      if config.hasPath(Keys.RecommitInterval) then
        FiniteDuration(config.getDuration(Keys.RecommitInterval).toNanos, "nanos")
      else FiniteDuration(0, "seconds")

    new MiningConfig(
      protocol = protocol,
      coinbase = coinbase,
      headerExtraData = headerExtraData,
      blockCacheSize = blockCacheSize,
      miningEnabled = miningEnabled,
      gasLimitTarget = gasLimitTarget,
      notifyUrls = notifyUrls,
      staleThreshold = staleThreshold,
      recommitInterval = recommitInterval
    )
