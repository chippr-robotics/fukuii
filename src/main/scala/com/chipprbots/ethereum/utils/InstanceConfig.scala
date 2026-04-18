package com.chipprbots.ethereum.utils

import java.net.InetSocketAddress

import scala.concurrent.duration._

import com.typesafe.config.{Config => TypesafeConfig}

import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration

/** Per-instance configuration for a Fukuii chain instance.
  *
  * This class mirrors every field from the legacy `object Config` singleton, but is instantiable per-chain-instance for
  * multi-network support.
  *
  * For backward compatibility, `object Config` extends `InstanceConfig` with the default
  * `ConfigFactory.load().getConfig("fukuii")` configuration.
  *
  * @param config
  *   the "fukuii" section of the HOCON configuration
  * @param instanceId
  *   optional instance identifier for multi-instance mode (e.g., "etc", "mordor", "sepolia")
  */
class InstanceConfig(val config: TypesafeConfig, val instanceId: String = "default") {

  val testmode: Boolean = config.getBoolean("testmode")

  val clientId: String =
    VersionInfo.nodeName(ConfigUtils.getOptionalValue(config, _.getString, "client-identity"))

  val clientVersion: String = VersionInfo.nodeName()

  val nodeKeyFile: String = config.getString("node-key-file")

  val shutdownTimeout: Duration = config.getDuration("shutdown-timeout").toMillis.millis

  val secureRandomAlgo: Option[String] =
    if (config.hasPath("secure-random-algo")) Some(config.getString("secure-random-algo"))
    else None

  import com.chipprbots.ethereum.network.p2p.messages.Capability
  val supportedCapabilities: List[Capability] = List(
    Capability.ETH65,
    Capability.ETH66,
    Capability.ETH67,
    Capability.ETH68,
    Capability.ETH69,
    Capability.SNAP1
  )

  val blockchains: BlockchainsConfig = BlockchainsConfig(config.getConfig("blockchains"))

  object Network {
    private val networkConfig = config.getConfig("network")

    val automaticPortForwarding: Boolean = networkConfig.getBoolean("automatic-port-forwarding")

    object Server {
      private val serverConfig = networkConfig.getConfig("server-address")

      val interface: String = serverConfig.getString("interface")
      val port: Int = serverConfig.getInt("port")
      val listenAddress = new InetSocketAddress(interface, port)
    }

    val peer: PeerConfiguration = new PeerConfiguration {
      private val peerConfig = networkConfig.getConfig("peer")
      private val blockchainConfig: BlockchainConfig = blockchains.blockchainConfig

      val connectRetryDelay: FiniteDuration = peerConfig.getDuration("connect-retry-delay").toMillis.millis
      val connectMaxRetries: Int = peerConfig.getInt("connect-max-retries")
      val disconnectPoisonPillTimeout: FiniteDuration =
        peerConfig.getDuration("disconnect-poison-pill-timeout").toMillis.millis
      val waitForHelloTimeout: FiniteDuration = peerConfig.getDuration("wait-for-hello-timeout").toMillis.millis
      val waitForStatusTimeout: FiniteDuration = peerConfig.getDuration("wait-for-status-timeout").toMillis.millis
      val waitForChainCheckTimeout: FiniteDuration =
        peerConfig.getDuration("wait-for-chain-check-timeout").toMillis.millis
      val minOutgoingPeers: Int = peerConfig.getInt("min-outgoing-peers")
      val maxOutgoingPeers: Int = peerConfig.getInt("max-outgoing-peers")
      val maxIncomingPeers: Int = peerConfig.getInt("max-incoming-peers")
      val maxPendingPeers: Int = peerConfig.getInt("max-pending-peers")
      val pruneIncomingPeers: Int = peerConfig.getInt("prune-incoming-peers")
      val minPruneAge: FiniteDuration = peerConfig.getDuration("min-prune-age").toMillis.millis
      val networkId: Long = blockchainConfig.networkId
      val p2pVersion: Int = if (peerConfig.hasPath("p2p-version")) peerConfig.getInt("p2p-version") else 5

      val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration {
        val waitForHandshakeTimeout: FiniteDuration =
          peerConfig.getDuration("wait-for-handshake-timeout").toMillis.millis
        val waitForTcpAckTimeout: FiniteDuration = peerConfig.getDuration("wait-for-tcp-ack-timeout").toMillis.millis
      }

      val fastSyncHostConfiguration: FastSyncHostConfiguration = new FastSyncHostConfiguration {
        val maxBlocksHeadersPerMessage: Int = peerConfig.getInt("max-blocks-headers-per-message")
        val maxBlocksBodiesPerMessage: Int = peerConfig.getInt("max-blocks-bodies-per-message")
        val maxReceiptsPerMessage: Int = peerConfig.getInt("max-receipts-per-message")
        val maxMptComponentsPerMessage: Int = peerConfig.getInt("max-mpt-components-per-message")
      }
      override val updateNodesInitialDelay: FiniteDuration =
        peerConfig.getDuration("update-nodes-initial-delay").toMillis.millis
      override val updateNodesInterval: FiniteDuration = peerConfig.getDuration("update-nodes-interval").toMillis.millis

      val shortBlacklistDuration: FiniteDuration = peerConfig.getDuration("short-blacklist-duration").toMillis.millis
      val longBlacklistDuration: FiniteDuration = peerConfig.getDuration("long-blacklist-duration").toMillis.millis

      val statSlotDuration: FiniteDuration = peerConfig.getDuration("stat-slot-duration").toMillis.millis
      val statSlotCount: Int = peerConfig.getInt("stat-slot-count")
    }
  }

  object Db {
    private val dbConfig = config.getConfig("db")
    private val rocksDbConfig = dbConfig.getConfig("rocksdb")

    val dataSource: String = dbConfig.getString("data-source")
    val periodicConsistencyCheck: Boolean = dbConfig.getBoolean("periodic-consistency-check")

    object RocksDb extends RocksDbConfig {
      override val createIfMissing: Boolean = rocksDbConfig.getBoolean("create-if-missing")
      override val paranoidChecks: Boolean = rocksDbConfig.getBoolean("paranoid-checks")
      override val path: String = rocksDbConfig.getString("path")
      override val maxThreads: Int = rocksDbConfig.getInt("max-threads")
      override val maxOpenFiles: Int = rocksDbConfig.getInt("max-open-files")
      override val verifyChecksums: Boolean = rocksDbConfig.getBoolean("verify-checksums")
      override val levelCompaction: Boolean = rocksDbConfig.getBoolean("level-compaction-dynamic-level-bytes")
      override val blockSize: Long = rocksDbConfig.getLong("block-size")
      override val blockCacheSize: Long = rocksDbConfig.getLong("block-cache-size")
    }
  }

  lazy val nodeCacheConfig: NodeCacheConfig = new NodeCacheConfig {
    private val cacheConfig = config.getConfig("node-caching")
    override val maxSize: Long = cacheConfig.getInt("max-size")
    override val maxHoldTime: FiniteDuration = cacheConfig.getDuration("max-hold-time").toMillis.millis
  }

  lazy val inMemoryPruningNodeCacheConfig: NodeCacheConfig = new NodeCacheConfig {
    private val cacheConfig = config.getConfig("inmemory-pruning-node-caching")
    override val maxSize: Long = cacheConfig.getInt("max-size")
    override val maxHoldTime: FiniteDuration = cacheConfig.getDuration("max-hold-time").toMillis.millis
  }
}

/** Cache configuration used by LruCache, MapCache, StateStorage. Defined at package level so it can be referenced as a
  * type from anywhere.
  */
trait NodeCacheConfig {
  val maxSize: Long
  val maxHoldTime: scala.concurrent.duration.FiniteDuration
}

/** Trait that provides access to an InstanceConfig. Mix this into cake pattern traits that need per-instance
  * configuration.
  */
trait InstanceConfigProvider {
  def instanceConfig: InstanceConfig
}
