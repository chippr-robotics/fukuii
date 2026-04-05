package com.chipprbots.ethereum.utils

import java.io.File
import java.net.InetSocketAddress

import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

import com.typesafe.config.ConfigFactory
import com.typesafe.config.{Config => TypesafeConfig}

import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.utils.VmConfig.VmMode

import ConfigUtils._

/** Singleton Config for backward compatibility. All existing code that references `Config.xxx` continues to work
  * unchanged. For multi-instance mode, create new `InstanceConfig` instances instead.
  */
object Config extends InstanceConfig(ConfigFactory.load().getConfig("fukuii"), "default") {

  case class SyncConfig(
      doFastSync: Boolean,
      doSnapSync: Boolean,
      fastSyncRestartCooloff: FiniteDuration,
      peersScanInterval: FiniteDuration,
      blacklistDuration: FiniteDuration,
      criticalBlacklistDuration: FiniteDuration,
      startRetryInterval: FiniteDuration,
      syncRetryInterval: FiniteDuration,
      syncSwitchDelay: FiniteDuration,
      peerResponseTimeout: FiniteDuration,
      printStatusInterval: FiniteDuration,
      maxConcurrentRequests: Int,
      blockHeadersPerRequest: Int,
      blockBodiesPerRequest: Int,
      receiptsPerRequest: Int,
      nodesPerRequest: Int,
      minPeersToChoosePivotBlock: Int,
      peersToChoosePivotBlockMargin: Int,
      peersToFetchFrom: Int,
      pivotBlockOffset: Int,
      pivotBlockMaxTotalSelectionAttempts: Int,
      persistStateSnapshotInterval: FiniteDuration,
      blocksBatchSize: Int,
      maxFetcherQueueSize: Int,
      checkForNewBlockInterval: FiniteDuration,
      branchResolutionRequestSize: Int,
      blockChainOnlyPeersPoolSize: Int,
      fastSyncThrottle: FiniteDuration,
      maxQueuedBlockNumberAhead: Int,
      maxQueuedBlockNumberBehind: Int,
      maxNewBlockHashAge: Int,
      maxNewHashes: Int,
      redownloadMissingStateNodes: Boolean,
      fastSyncBlockValidationK: Int,
      fastSyncBlockValidationN: Int,
      fastSyncBlockValidationX: Int,
      maxTargetDifference: Int,
      maximumTargetUpdateFailures: Int,
      stateSyncBloomFilterSize: Int,
      stateSyncPersistBatchSize: Int,
      pivotBlockReScheduleInterval: FiniteDuration,
      maxPivotBlockAge: Int,
      fastSyncMaxBatchRetries: Int,
      maxPivotBlockFailuresCount: Int,
      maxRetryDelay: FiniteDuration,
      maxBodyFetchRetries: Int,
      maxSnapFastCycleTransitions: Int,
      useBootstrapCheckpoints: Boolean,
      bootstrapCheckpoints: Seq[(BigInt, String)] // (blockNumber, blockHash)
  )

  object SyncConfig {
    private val DefaultPivotBlockMaxTotalSelectionAttempts = 20
    private val DefaultFastSyncRestartCooloff = 10.minutes

    def apply(etcClientConfig: TypesafeConfig): SyncConfig = {
      val syncConfig = etcClientConfig.getConfig("sync")
      SyncConfig(
        doFastSync = syncConfig.getBoolean("do-fast-sync"),
        doSnapSync = syncConfig.getBoolean("do-snap-sync"),
        fastSyncRestartCooloff =
          if (syncConfig.hasPath("fast-sync-restart-cooloff"))
            syncConfig.getDuration("fast-sync-restart-cooloff").toMillis.millis
          else DefaultFastSyncRestartCooloff,
        peersScanInterval = syncConfig.getDuration("peers-scan-interval").toMillis.millis,
        blacklistDuration = syncConfig.getDuration("blacklist-duration").toMillis.millis,
        criticalBlacklistDuration = syncConfig.getDuration("critical-blacklist-duration").toMillis.millis,
        startRetryInterval = syncConfig.getDuration("start-retry-interval").toMillis.millis,
        syncRetryInterval = syncConfig.getDuration("sync-retry-interval").toMillis.millis,
        syncSwitchDelay = syncConfig.getDuration("sync-switch-delay").toMillis.millis,
        peerResponseTimeout = syncConfig.getDuration("peer-response-timeout").toMillis.millis,
        printStatusInterval = syncConfig.getDuration("print-status-interval").toMillis.millis,
        maxConcurrentRequests = syncConfig.getInt("max-concurrent-requests"),
        blockHeadersPerRequest = syncConfig.getInt("block-headers-per-request"),
        blockBodiesPerRequest = syncConfig.getInt("block-bodies-per-request"),
        receiptsPerRequest = syncConfig.getInt("receipts-per-request"),
        nodesPerRequest = syncConfig.getInt("nodes-per-request"),
        minPeersToChoosePivotBlock = syncConfig.getInt("min-peers-to-choose-pivot-block"),
        peersToChoosePivotBlockMargin = syncConfig.getInt("peers-to-choose-pivot-block-margin"),
        peersToFetchFrom = syncConfig.getInt("peers-to-fetch-from"),
        pivotBlockOffset = syncConfig.getInt("pivot-block-offset"),
        pivotBlockMaxTotalSelectionAttempts =
          if (syncConfig.hasPath("pivot-block-max-total-selection-attempts"))
            syncConfig.getInt("pivot-block-max-total-selection-attempts")
          else DefaultPivotBlockMaxTotalSelectionAttempts,
        persistStateSnapshotInterval = syncConfig.getDuration("persist-state-snapshot-interval").toMillis.millis,
        blocksBatchSize = syncConfig.getInt("blocks-batch-size"),
        maxFetcherQueueSize = syncConfig.getInt("max-fetcher-queue-size"),
        checkForNewBlockInterval = syncConfig.getDuration("check-for-new-block-interval").toMillis.millis,
        branchResolutionRequestSize = syncConfig.getInt("branch-resolution-request-size"),
        blockChainOnlyPeersPoolSize = syncConfig.getInt("fastsync-block-chain-only-peers-pool"),
        fastSyncThrottle = syncConfig.getDuration("fastsync-throttle").toMillis.millis,
        maxQueuedBlockNumberBehind = syncConfig.getInt("max-queued-block-number-behind"),
        maxQueuedBlockNumberAhead = syncConfig.getInt("max-queued-block-number-ahead"),
        maxNewBlockHashAge = syncConfig.getInt("max-new-block-hash-age"),
        maxNewHashes = syncConfig.getInt("max-new-hashes"),
        redownloadMissingStateNodes = syncConfig.getBoolean("redownload-missing-state-nodes"),
        fastSyncBlockValidationK = syncConfig.getInt("fast-sync-block-validation-k"),
        fastSyncBlockValidationN = syncConfig.getInt("fast-sync-block-validation-n"),
        fastSyncBlockValidationX = syncConfig.getInt("fast-sync-block-validation-x"),
        maxTargetDifference = syncConfig.getInt("max-target-difference"),
        maximumTargetUpdateFailures = syncConfig.getInt("maximum-target-update-failures"),
        stateSyncBloomFilterSize = syncConfig.getInt("state-sync-bloom-filter-size"),
        stateSyncPersistBatchSize = syncConfig.getInt("state-sync-persist-batch-size"),
        pivotBlockReScheduleInterval = syncConfig.getDuration("pivot-block-reschedule-interval").toMillis.millis,
        maxPivotBlockAge = syncConfig.getInt("max-pivot-block-age"),
        fastSyncMaxBatchRetries = syncConfig.getInt("fast-sync-max-batch-retries"),
        maxPivotBlockFailuresCount = syncConfig.getInt("max-pivot-block-failures-count"),
        maxRetryDelay =
          if (syncConfig.hasPath("max-retry-delay"))
            syncConfig.getDuration("max-retry-delay").toMillis.millis
          else 30.seconds,
        maxBodyFetchRetries =
          if (syncConfig.hasPath("max-body-fetch-retries"))
            syncConfig.getInt("max-body-fetch-retries")
          else 10,
        maxSnapFastCycleTransitions =
          if (syncConfig.hasPath("max-snap-fast-cycle-transitions"))
            syncConfig.getInt("max-snap-fast-cycle-transitions")
          else 3,
        useBootstrapCheckpoints =
          if (syncConfig.hasPath("use-bootstrap-checkpoints"))
            syncConfig.getBoolean("use-bootstrap-checkpoints")
          else false,
        bootstrapCheckpoints = if (syncConfig.hasPath("bootstrap-checkpoints")) {
          import scala.jdk.CollectionConverters._
          syncConfig.getStringList("bootstrap-checkpoints").asScala.toSeq.flatMap { entry =>
            // Format: "blockNumber:0xblockHash"
            entry.split(":") match {
              case Array(num, hash) =>
                try {
                  val blockNum = BigInt(num.trim)
                  val blockHash = hash.trim
                  Some((blockNum, blockHash))
                } catch {
                  case _: NumberFormatException => None
                }
              case _ => None
            }
          }
        } else Seq.empty
      )
    }
  }

  // SyncConfig remains here as it's a case class used as a type throughout the codebase.
  // Db, Network, and cache configs are inherited from InstanceConfig.
}

case class AsyncConfig(askTimeout: Timeout)
object AsyncConfig {
  def apply(fukuiiConfig: TypesafeConfig): AsyncConfig =
    AsyncConfig(fukuiiConfig.getConfig("async").getDuration("ask-timeout").toMillis.millis)
}

//user keystore
trait KeyStoreConfig {
  val keyStoreDir: String
  val minimalPassphraseLength: Int
  val allowNoPassphrase: Boolean
}

object KeyStoreConfig {
  def apply(etcClientConfig: TypesafeConfig): KeyStoreConfig = {
    val keyStoreConfig = etcClientConfig.getConfig("keyStore")

    new KeyStoreConfig {
      val keyStoreDir: String = keyStoreConfig.getString("keystore-dir")
      val minimalPassphraseLength: Int = keyStoreConfig.getInt("minimal-passphrase-length")
      val allowNoPassphrase: Boolean = keyStoreConfig.getBoolean("allow-no-passphrase")
    }
  }

  def customKeyStoreConfig(path: String): KeyStoreConfig =
    new KeyStoreConfig {
      val keyStoreDir: String = path
      val minimalPassphraseLength: Int = 7
      val allowNoPassphrase: Boolean = true
    }
}

trait FilterConfig {
  val filterTimeout: FiniteDuration
  val filterManagerQueryTimeout: FiniteDuration
}

object FilterConfig {
  def apply(etcClientConfig: TypesafeConfig): FilterConfig = {
    val filterConfig = etcClientConfig.getConfig("filter")

    new FilterConfig {
      val filterTimeout: FiniteDuration = filterConfig.getDuration("filter-timeout").toMillis.millis
      val filterManagerQueryTimeout: FiniteDuration =
        filterConfig.getDuration("filter-manager-query-timeout").toMillis.millis
    }
  }
}

trait TxPoolConfig {
  val txPoolSize: Int
  val pendingTxManagerQueryTimeout: FiniteDuration
  val transactionTimeout: FiniteDuration
  val getTransactionFromPoolTimeout: FiniteDuration
}

object TxPoolConfig {
  def apply(etcClientConfig: com.typesafe.config.Config): TxPoolConfig = {
    val txPoolConfig = etcClientConfig.getConfig("txPool")

    new TxPoolConfig {
      val txPoolSize: Int = txPoolConfig.getInt("tx-pool-size")
      val pendingTxManagerQueryTimeout: FiniteDuration =
        txPoolConfig.getDuration("pending-tx-manager-query-timeout").toMillis.millis
      val transactionTimeout: FiniteDuration = txPoolConfig.getDuration("transaction-timeout").toMillis.millis
      val getTransactionFromPoolTimeout: FiniteDuration =
        txPoolConfig.getDuration("get-transaction-from-pool-timeout").toMillis.millis
    }
  }
}

trait DaoForkConfig {

  val forkBlockNumber: BigInt
  val forkBlockHash: ByteString
  val blockExtraData: Option[ByteString]
  val range: Int
  val refundContract: Option[Address]
  val drainList: Seq[Address]
  val includeOnForkIdList: Boolean

  private lazy val extratadaBlockRange = forkBlockNumber until (forkBlockNumber + range)

  def isDaoForkBlock(blockNumber: BigInt): Boolean = forkBlockNumber == blockNumber

  def requiresExtraData(blockNumber: BigInt): Boolean =
    blockExtraData.isDefined && (extratadaBlockRange contains blockNumber)

  def getExtraData(blockNumber: BigInt): Option[ByteString] =
    if (requiresExtraData(blockNumber)) blockExtraData
    else None
}

object DaoForkConfig {
  def apply(daoConfig: TypesafeConfig): DaoForkConfig = {

    val theForkBlockNumber = BigInt(daoConfig.getString("fork-block-number"))

    val theForkBlockHash = ByteString(Hex.decode(daoConfig.getString("fork-block-hash")))

    new DaoForkConfig {
      override val forkBlockNumber: BigInt = theForkBlockNumber
      override val forkBlockHash: ByteString = theForkBlockHash
      override val blockExtraData: Option[ByteString] =
        Try(daoConfig.getString("block-extra-data")).toOption.map(ByteString(_))
      override val range: Int = Try(daoConfig.getInt("block-extra-data-range")).toOption.getOrElse(0)
      override val refundContract: Option[Address] =
        Try(daoConfig.getString("refund-contract-address")).toOption.map(Address(_))
      override val drainList: List[Address] =
        Try(daoConfig.getStringList("drain-list").asScala.toList).toOption.getOrElse(List.empty).map(Address(_))
      override val includeOnForkIdList: Boolean = daoConfig.getBoolean("include-on-fork-id-list")
    }
  }
}

case class BlockchainsConfig(network: String, blockchains: Map[String, BlockchainConfig]) {
  val blockchainConfig: BlockchainConfig = blockchains(network)
}
object BlockchainsConfig extends Logger {
  private val networkKey = "network"
  private val customChainsDirKey = "custom-chains-dir"

  def apply(rawConfig: TypesafeConfig): BlockchainsConfig = {
    // Get the network name first
    val network = rawConfig.getString(networkKey)

    // Load built-in blockchain configs
    val builtInBlockchains = keys(rawConfig)
      .filterNot(k => k == networkKey || k == customChainsDirKey)
      .map(name => name -> BlockchainConfig.fromRawConfig(rawConfig.getConfig(name)))
      .toMap

    // Check for custom chains directory
    val customBlockchains = if (rawConfig.hasPath(customChainsDirKey)) {
      val customChainsDir = rawConfig.getString(customChainsDirKey)
      val chainsDir = new File(customChainsDir)

      if (chainsDir.exists() && chainsDir.isDirectory) {
        log.info(s"Loading custom chain configurations from: $customChainsDir")
        val chainFiles = chainsDir.listFiles().filter { f =>
          f.isFile && f.getName.endsWith("-chain.conf")
        }

        // TODO: Future optimization - cache parsed configurations and check file modification
        // times to avoid re-parsing unchanged files on restart
        chainFiles.flatMap { chainFile =>
          val result = Try {
            val chainName = chainFile.getName.stripSuffix("-chain.conf")
            log.info(s"Loading custom chain config: $chainName from ${chainFile.getName}")
            val chainConfig = ConfigFactory.parseFile(chainFile)
            chainName -> BlockchainConfig.fromRawConfig(chainConfig)
          }

          result.failed.foreach { e =>
            log.error(s"Failed to load chain config from ${chainFile.getName}: ${e.getMessage}", e)
          }

          result.toOption
        }.toMap
      } else {
        if (chainsDir.exists()) {
          log.warn(s"Custom chains directory is not a directory: $customChainsDir")
        } else {
          log.warn(s"Custom chains directory does not exist: $customChainsDir")
        }
        Map.empty[String, BlockchainConfig]
      }
    } else {
      Map.empty[String, BlockchainConfig]
    }

    // Merge blockchains, with custom configs taking precedence
    val allBlockchains = builtInBlockchains ++ customBlockchains

    if (customBlockchains.nonEmpty) {
      log.info(
        s"Loaded ${customBlockchains.size} custom chain configuration(s): ${customBlockchains.keys.mkString(", ")}"
      )
    }

    BlockchainsConfig(network, allBlockchains)
  }
}

case class MonetaryPolicyConfig(
    eraDuration: Int,
    rewardReductionRate: Double,
    firstEraBlockReward: BigInt,
    firstEraReducedBlockReward: BigInt,
    firstEraConstantinopleReducedBlockReward: BigInt = 0
) {
  require(
    rewardReductionRate >= 0.0 && rewardReductionRate <= 1.0,
    "reward-reduction-rate should be a value in range [0.0, 1.0]"
  )
}

object MonetaryPolicyConfig {
  def apply(mpConfig: TypesafeConfig): MonetaryPolicyConfig =
    MonetaryPolicyConfig(
      mpConfig.getInt("era-duration"),
      mpConfig.getDouble("reward-reduction-rate"),
      BigInt(mpConfig.getString("first-era-block-reward")),
      BigInt(mpConfig.getString("first-era-reduced-block-reward")),
      BigInt(mpConfig.getString("first-era-constantinople-reduced-block-reward"))
    )
}

trait PruningConfig {
  val mode: PruningMode
}

object PruningConfig {
  def apply(etcClientConfig: com.typesafe.config.Config): PruningConfig = {
    val pruningConfig = etcClientConfig.getConfig("pruning")

    val pruningMode: PruningMode = pruningConfig.getString("mode") match {
      case "basic"    => BasicPruning(pruningConfig.getInt("history"))
      case "archive"  => ArchivePruning
      case "inmemory" => InMemoryPruning(pruningConfig.getInt("history"))
    }

    new PruningConfig {
      override val mode: PruningMode = pruningMode
    }
  }
}

case class VmConfig(mode: VmMode, externalConfig: Option[VmConfig.ExternalConfig])

object VmConfig {

  sealed trait VmMode
  object VmMode {
    case object Internal extends VmMode
    case object External extends VmMode
  }

  object ExternalConfig {
    val VmTypeIele = "iele"
    val VmTypeKevm = "kevm"
    val VmTypeFukuii = "fukuii"
    val VmTypeNone = "none"

    val supportedVmTypes: Set[String] = Set(VmTypeIele, VmTypeKevm, VmTypeFukuii, VmTypeNone)
  }

  case class ExternalConfig(vmType: String, executablePath: Option[String], host: String, port: Int)

  def apply(mpConfig: TypesafeConfig): VmConfig = {
    def parseExternalConfig(): ExternalConfig = {
      import ExternalConfig._

      val extConf = mpConfig.getConfig("vm.external")
      val vmType = extConf.getString("vm-type").toLowerCase
      require(
        supportedVmTypes.contains(vmType),
        "vm.external.vm-type must be one of: " + supportedVmTypes.mkString(", ")
      )

      ExternalConfig(
        vmType,
        Try(extConf.getString("executable-path")).toOption,
        extConf.getString("host"),
        extConf.getInt("port")
      )
    }

    mpConfig.getString("vm.mode") match {
      case "internal" => VmConfig(VmMode.Internal, None)
      case "external" => VmConfig(VmMode.External, Some(parseExternalConfig()))
      case other      => throw new RuntimeException(s"Unknown VM mode: $other. Expected one of: local, external")
    }
  }
}
