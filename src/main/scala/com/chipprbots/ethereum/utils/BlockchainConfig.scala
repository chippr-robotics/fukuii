package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.{Config => TypesafeConfig}

import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.{Address, UInt256}
import com.chipprbots.ethereum.utils.NumericUtils._

/** Identifies whether the chain follows ETC (PoW indefinitely) or ETH (post-Merge PoS via CL). */
sealed trait NetworkType
object NetworkType {
  case object ETC extends NetworkType
  case object ETH extends NetworkType

  def fromString(s: String): NetworkType = s.toLowerCase match {
    case "etc" => ETC
    case "eth" => ETH
    case other => throw new IllegalArgumentException(s"Unknown network-type: $other (expected 'etc' or 'eth')")
  }
}

/** Timestamp-based fork activation for post-Merge Ethereum forks. */
case class ForkTimestamps(
    shanghaiTimestamp: Option[Long] = None,
    cancunTimestamp: Option[Long] = None,
    pragueTimestamp: Option[Long] = None,
    osakaTimestamp: Option[Long] = None,
    bpo1Timestamp: Option[Long] = None,
    bpo2Timestamp: Option[Long] = None
)

case class BlockchainConfig(
    powTargetTime: Option[Long] = None,
    forkBlockNumbers: ForkBlockNumbers,
    maxCodeSize: Option[BigInt],
    customGenesisFileOpt: Option[String],
    customGenesisJsonOpt: Option[String],
    daoForkConfig: Option[DaoForkConfig],
    accountStartNonce: UInt256,
    chainId: BigInt,
    networkId: Long,
    monetaryPolicyConfig: MonetaryPolicyConfig,
    gasTieBreaker: Boolean,
    ethCompatibleStorage: Boolean,
    bootstrapNodes: Set[String],
    dnsDiscoveryDomains: Seq[String] = Seq.empty,
    allowedMinersPublicKeys: Set[ByteString] = Set.empty,
    messConfig: MESSConfig = MESSConfig(),
    treasuryAddress: Address = Address(0),
    baseFeeFloor: BigInt = BigInt(0),
    minTip: BigInt = BigInt(1),
    networkType: NetworkType = NetworkType.ETC,
    terminalTotalDifficulty: Option[BigInt] = None,
    forkTimestamps: ForkTimestamps = ForkTimestamps()
) {
  def isPostMerge(totalDifficulty: BigInt): Boolean =
    terminalTotalDifficulty.exists(ttd => totalDifficulty >= ttd)

  def isShanghaiTimestamp(timestamp: Long): Boolean =
    forkTimestamps.shanghaiTimestamp.exists(ts => timestamp >= ts)

  def isCancunTimestamp(timestamp: Long): Boolean =
    forkTimestamps.cancunTimestamp.exists(ts => timestamp >= ts)

  def isPragueTimestamp(timestamp: Long): Boolean =
    forkTimestamps.pragueTimestamp.exists(ts => timestamp >= ts)

  def isOsakaTimestamp(timestamp: Long): Boolean =
    forkTimestamps.osakaTimestamp.exists(ts => timestamp >= ts)

  /** EIP-7892 Blob Parameter Only (BPO) fork activation. BPOs raise the blob target/max without other consensus
    * changes. Sepolia activated BPO1 on 2025-10-21.
    */
  def isBpo1Timestamp(timestamp: Long): Boolean =
    forkTimestamps.bpo1Timestamp.exists(ts => timestamp >= ts)

  /** EIP-7892 BPO2: second blob-target bump. Sepolia activated 2025-10-28. */
  def isBpo2Timestamp(timestamp: Long): Boolean =
    forkTimestamps.bpo2Timestamp.exists(ts => timestamp >= ts)

  def withUpdatedForkBlocks(update: (ForkBlockNumbers) => ForkBlockNumbers): BlockchainConfig =
    copy(forkBlockNumbers = update(forkBlockNumbers))
}

case class ForkBlockNumbers(
    frontierBlockNumber: BigInt,
    homesteadBlockNumber: BigInt,
    eip106BlockNumber: BigInt,
    eip150BlockNumber: BigInt,
    eip155BlockNumber: BigInt,
    eip160BlockNumber: BigInt,
    eip161BlockNumber: BigInt,
    difficultyBombPauseBlockNumber: BigInt,
    difficultyBombContinueBlockNumber: BigInt,
    difficultyBombRemovalBlockNumber: BigInt,
    byzantiumBlockNumber: BigInt,
    constantinopleBlockNumber: BigInt,
    istanbulBlockNumber: BigInt,
    atlantisBlockNumber: BigInt,
    aghartaBlockNumber: BigInt,
    phoenixBlockNumber: BigInt,
    petersburgBlockNumber: BigInt,
    ecip1099BlockNumber: BigInt,
    muirGlacierBlockNumber: BigInt,
    magnetoBlockNumber: BigInt,
    berlinBlockNumber: BigInt,
    mystiqueBlockNumber: BigInt,
    spiralBlockNumber: BigInt,
    olympiaBlockNumber: BigInt,
    // EIP-3675 / Sepolia post-Merge net-split block (1735371). Block-based fork that
    // must be in the EIP-2124 fork-id checksum chain — go-ethereum's params/config.go
    // lists this for Sepolia. Without it, our forkId hashes for Shanghai+ are off by
    // one CRC32 round and ForkIdValidator.checkSuperset rejects all chain-head peers.
    mergeNetsplitBlockNumber: BigInt = Long.MaxValue,
    // Gas limit targets embedded in the fork schedule (EIP-7935 / ECIP-1121).
    // When Some(target), the miner converges toward that target from the fork activation
    // block onward via the standard ±1/1024 mechanism — the schedule is authoritative
    // regardless of operator config. None → fall back to miningConfig.gasLimitTarget.
    spiralGasTarget: Option[BigInt] = None,
    olympiaGasTarget: Option[BigInt] = None
) {
  def all: List[BigInt] = this.productIterator.toList.collect { case i: BigInt =>
    i
  }

  /** Returns the convergence target that
    * [[com.chipprbots.ethereum.consensus.blocks.BlockGeneratorSkeleton.calculateGasLimit]] should aim for at the given
    * block number, based on the fork-embedded gas schedule. None means no fork schedule opinion for this era — caller
    * falls back to miningConfig.gasLimitTarget.
    */
  def gasLimitAdjustmentStartAt(blockNumber: BigInt): Option[BigInt] =
    if (blockNumber >= olympiaBlockNumber) olympiaGasTarget
    else if (blockNumber >= spiralBlockNumber) spiralGasTarget
    else None
}

object ForkBlockNumbers {
  val Empty: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = 0,
    homesteadBlockNumber = Long.MaxValue,
    difficultyBombPauseBlockNumber = Long.MaxValue,
    difficultyBombContinueBlockNumber = Long.MaxValue,
    difficultyBombRemovalBlockNumber = Long.MaxValue,
    eip106BlockNumber = Long.MaxValue,
    eip150BlockNumber = Long.MaxValue,
    eip160BlockNumber = Long.MaxValue,
    eip155BlockNumber = Long.MaxValue,
    eip161BlockNumber = Long.MaxValue,
    byzantiumBlockNumber = Long.MaxValue,
    constantinopleBlockNumber = Long.MaxValue,
    istanbulBlockNumber = Long.MaxValue,
    atlantisBlockNumber = Long.MaxValue,
    aghartaBlockNumber = Long.MaxValue,
    phoenixBlockNumber = Long.MaxValue,
    petersburgBlockNumber = Long.MaxValue,
    ecip1099BlockNumber = Long.MaxValue,
    muirGlacierBlockNumber = Long.MaxValue,
    magnetoBlockNumber = Long.MaxValue,
    berlinBlockNumber = Long.MaxValue,
    mystiqueBlockNumber = Long.MaxValue,
    spiralBlockNumber = Long.MaxValue,
    olympiaBlockNumber = Long.MaxValue,
    mergeNetsplitBlockNumber = Long.MaxValue
  )
}

object BlockchainConfig {

  // scalastyle:off method.length
  def fromRawConfig(blockchainConfig: TypesafeConfig): BlockchainConfig = {
    val powTargetTime: Option[Long] =
      ConfigUtils
        .getOptionalValue(blockchainConfig, _.getDuration, "pow-target-time")
        .map(_.getSeconds)
    val frontierBlockNumber: BigInt = BigInt(blockchainConfig.getString("frontier-block-number"))
    val homesteadBlockNumber: BigInt = BigInt(blockchainConfig.getString("homestead-block-number"))
    val eip106BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip106-block-number"))
    val eip150BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip150-block-number"))
    val eip155BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip155-block-number"))
    val eip160BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip160-block-number"))
    val eip161BlockNumber: BigInt = BigInt(blockchainConfig.getString("eip161-block-number"))
    val byzantiumBlockNumber: BigInt = BigInt(blockchainConfig.getString("byzantium-block-number"))
    val constantinopleBlockNumber: BigInt = BigInt(blockchainConfig.getString("constantinople-block-number"))
    val istanbulBlockNumber: BigInt = BigInt(blockchainConfig.getString("istanbul-block-number"))

    val atlantisBlockNumber: BigInt = BigInt(blockchainConfig.getString("atlantis-block-number"))
    val aghartaBlockNumber: BigInt = BigInt(blockchainConfig.getString("agharta-block-number"))
    val phoenixBlockNumber: BigInt = BigInt(blockchainConfig.getString("phoenix-block-number"))
    val petersburgBlockNumber: BigInt = BigInt(blockchainConfig.getString("petersburg-block-number"))
    val maxCodeSize: Option[BigInt] = Try(BigInt(blockchainConfig.getString("max-code-size"))).toOption
    val difficultyBombPauseBlockNumber: BigInt = BigInt(
      blockchainConfig.getString("difficulty-bomb-pause-block-number")
    )
    val difficultyBombContinueBlockNumber: BigInt = BigInt(
      blockchainConfig.getString("difficulty-bomb-continue-block-number")
    )
    val difficultyBombRemovalBlockNumber: BigInt = BigInt(
      blockchainConfig.getString("difficulty-bomb-removal-block-number")
    )
    val customGenesisFileOpt: Option[String] = Try(blockchainConfig.getString("custom-genesis-file")).toOption
    val customGenesisJsonOpt: Option[String] = Try(
      blockchainConfig.getObject("custom-genesis-file").render(ConfigRenderOptions.concise())
    ).toOption

    val daoForkConfig = Try(blockchainConfig.getConfig("dao")).toOption.map(DaoForkConfig(_))
    val accountStartNonce: UInt256 = UInt256(BigInt(blockchainConfig.getString("account-start-nonce")))

    val chainId: BigInt = {
      val s = blockchainConfig.getString("chain-id")
      parseHexOrDecNumber(s)
    }

    val networkId: Long = Try(blockchainConfig.getLong("network-id")).getOrElse {
      Try(BigInt(blockchainConfig.getString("network-id")).toLong).getOrElse(1L)
    }

    val monetaryPolicyConfig = MonetaryPolicyConfig(blockchainConfig.getConfig("monetary-policy"))

    val gasTieBreaker: Boolean = blockchainConfig.getBoolean("gas-tie-breaker")

    val ethCompatibleStorage: Boolean = blockchainConfig.getBoolean("eth-compatible-storage")

    val bootstrapNodes: Set[String] = blockchainConfig.getStringList("bootstrap-nodes").asScala.toSet
    val dnsDiscoveryDomains: Seq[String] = ConfigUtils
      .getOptionalValue(blockchainConfig, _.getStringList, "dns-discovery-domains")
      .map(_.asScala.toSeq)
      .getOrElse(Seq.empty)
    val allowedMinersPublicKeys = readPubKeySet(blockchainConfig, "allowed-miners")

    val ecip1099BlockNumber: BigInt = BigInt(blockchainConfig.getString("ecip1099-block-number"))
    val muirGlacierBlockNumber: BigInt = BigInt(blockchainConfig.getString("muir-glacier-block-number"))
    val magnetoBlockNumber: BigInt = BigInt(blockchainConfig.getString("magneto-block-number"))
    val berlinBlockNumber: BigInt = BigInt(blockchainConfig.getString("berlin-block-number"))
    val mystiqueBlockNumber: BigInt = BigInt(blockchainConfig.getString("mystique-block-number"))
    val spiralBlockNumber: BigInt = BigInt(blockchainConfig.getString("spiral-block-number"))
    val olympiaBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("olympia-block-number"))).getOrElse(BigInt(Long.MaxValue))
    val mergeNetsplitBlockNumber: BigInt =
      Try(BigInt(blockchainConfig.getString("merge-netsplit-block-number"))).getOrElse(BigInt(Long.MaxValue))
    val spiralGasTarget: Option[BigInt] =
      Try(BigInt(blockchainConfig.getString("spiral-gas-target"))).toOption
    val olympiaGasTarget: Option[BigInt] =
      Try(BigInt(blockchainConfig.getString("olympia-gas-target"))).toOption

    val treasuryAddress: Address =
      Try(Address(blockchainConfig.getString("treasury-address"))).getOrElse(Address(0))

    val baseFeeFloor: BigInt =
      Try(BigInt(blockchainConfig.getString("base-fee-floor"))).getOrElse(BigInt(0))

    val minTip: BigInt =
      Try(BigInt(blockchainConfig.getString("min-tip"))).getOrElse(BigInt(1))

    val networkType: NetworkType =
      Try(NetworkType.fromString(blockchainConfig.getString("network-type"))).getOrElse(NetworkType.ETC)

    val terminalTotalDifficulty: Option[BigInt] =
      Try(BigInt(blockchainConfig.getString("terminal-total-difficulty"))).toOption

    val forkTimestamps: ForkTimestamps = ForkTimestamps(
      shanghaiTimestamp = Try(blockchainConfig.getLong("shanghai-timestamp")).toOption,
      cancunTimestamp = Try(blockchainConfig.getLong("cancun-timestamp")).toOption,
      pragueTimestamp = Try(blockchainConfig.getLong("prague-timestamp")).toOption,
      osakaTimestamp = Try(blockchainConfig.getLong("osaka-timestamp")).toOption,
      bpo1Timestamp = Try(blockchainConfig.getLong("bpo1-timestamp")).toOption,
      bpo2Timestamp = Try(blockchainConfig.getLong("bpo2-timestamp")).toOption
    )

    val messConfig: MESSConfig = Try {
      val messConf = blockchainConfig.getConfig("mess")
      MESSConfig(
        enabled = Try(messConf.getBoolean("enabled")).getOrElse(false),
        activationBlock = Try(BigInt(messConf.getString("ecbp1100-block-number"))).toOption,
        deactivationBlock = Try(BigInt(messConf.getString("ecbp1100-deactivate-block-number"))).toOption,
        reactivationBlock = Try(BigInt(messConf.getString("ecbp1100-reactivate-block-number"))).toOption
          .orElse(Try(BigInt(blockchainConfig.getString("olympia-block-number"))).toOption)
      )
    }.getOrElse(MESSConfig())

    BlockchainConfig(
      powTargetTime = powTargetTime,
      forkBlockNumbers = ForkBlockNumbers(
        frontierBlockNumber = frontierBlockNumber,
        homesteadBlockNumber = homesteadBlockNumber,
        eip106BlockNumber = eip106BlockNumber,
        eip150BlockNumber = eip150BlockNumber,
        eip155BlockNumber = eip155BlockNumber,
        eip160BlockNumber = eip160BlockNumber,
        eip161BlockNumber = eip161BlockNumber,
        difficultyBombPauseBlockNumber = difficultyBombPauseBlockNumber,
        difficultyBombContinueBlockNumber = difficultyBombContinueBlockNumber,
        difficultyBombRemovalBlockNumber = difficultyBombRemovalBlockNumber,
        byzantiumBlockNumber = byzantiumBlockNumber,
        constantinopleBlockNumber = constantinopleBlockNumber,
        istanbulBlockNumber = istanbulBlockNumber,
        atlantisBlockNumber = atlantisBlockNumber,
        aghartaBlockNumber = aghartaBlockNumber,
        phoenixBlockNumber = phoenixBlockNumber,
        petersburgBlockNumber = petersburgBlockNumber,
        ecip1099BlockNumber = ecip1099BlockNumber,
        muirGlacierBlockNumber = muirGlacierBlockNumber,
        magnetoBlockNumber = magnetoBlockNumber,
        berlinBlockNumber = berlinBlockNumber,
        mystiqueBlockNumber = mystiqueBlockNumber,
        spiralBlockNumber = spiralBlockNumber,
        olympiaBlockNumber = olympiaBlockNumber,
        mergeNetsplitBlockNumber = mergeNetsplitBlockNumber,
        spiralGasTarget = spiralGasTarget,
        olympiaGasTarget = olympiaGasTarget
      ),
      maxCodeSize = maxCodeSize,
      customGenesisFileOpt = customGenesisFileOpt,
      customGenesisJsonOpt = customGenesisJsonOpt,
      daoForkConfig = daoForkConfig,
      accountStartNonce = accountStartNonce,
      chainId = chainId,
      networkId = networkId,
      monetaryPolicyConfig = monetaryPolicyConfig,
      gasTieBreaker = gasTieBreaker,
      ethCompatibleStorage = ethCompatibleStorage,
      bootstrapNodes = bootstrapNodes,
      dnsDiscoveryDomains = dnsDiscoveryDomains,
      allowedMinersPublicKeys = allowedMinersPublicKeys,
      messConfig = messConfig,
      treasuryAddress = treasuryAddress,
      baseFeeFloor = baseFeeFloor,
      minTip = minTip,
      networkType = networkType,
      terminalTotalDifficulty = terminalTotalDifficulty,
      forkTimestamps = forkTimestamps
    )
  }
  // scalastyle:on method.length
  private def readPubKeySet(blockchainConfig: TypesafeConfig, path: String): Set[ByteString] = {
    val keys: Seq[String] = ConfigUtils
      .getOptionalValue(blockchainConfig, _.getStringList, path)
      .map(_.asScala.toSeq)
      .getOrElse(Nil)
    keys.map(ByteStringUtils.string2hash).toSet
  }
}
