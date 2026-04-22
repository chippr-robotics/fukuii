package com.chipprbots.ethereum.nodebuilder

import java.net.URI
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.blockchain.data.{ChainImporter, GenesisDataLoader}
import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.BlockchainHostActor
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.mining.MiningBuilder
import com.chipprbots.ethereum.consensus.mining.MiningConfigBuilder
import com.chipprbots.ethereum.db.components.Storages.PruningModeComponent
import com.chipprbots.ethereum.db.components._
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.NetService.NetServiceConfig
import com.chipprbots.ethereum.jsonrpc._
import com.chipprbots.ethereum.jsonrpc.server.controllers.ApisBase
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStoreImpl
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network._
import com.chipprbots.ethereum.network.discovery.DiscoveryConfig
import com.chipprbots.ethereum.network.discovery.DiscoveryServiceBuilder
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.network.handshaker.NetworkHandshaker
import com.chipprbots.ethereum.network.handshaker.NetworkHandshakerConfiguration
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.rlpx.AuthHandshaker
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.security.SSLContextBuilder
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils._

// scalastyle:off number.of.types
trait BlockchainConfigBuilder {
  self: InstanceConfigProvider =>
  protected lazy val initBlockchainConfig = instanceConfig.blockchains.blockchainConfig
  implicit def blockchainConfig: BlockchainConfig = initBlockchainConfig
}

trait VmConfigBuilder {
  self: InstanceConfigProvider =>
  lazy val vmConfig: VmConfig = VmConfig(instanceConfig.config)
}

trait SyncConfigBuilder {
  self: InstanceConfigProvider =>
  lazy val syncConfig: SyncConfig = SyncConfig(instanceConfig.config)
}

trait TxPoolConfigBuilder {
  self: InstanceConfigProvider =>
  lazy val txPoolConfig: TxPoolConfig = TxPoolConfig(instanceConfig.config)
}

trait FilterConfigBuilder {
  self: InstanceConfigProvider =>
  lazy val filterConfig: FilterConfig = FilterConfig(instanceConfig.config)
}

trait KeyStoreConfigBuilder {
  self: InstanceConfigProvider =>
  lazy val keyStoreConfig: KeyStoreConfig = KeyStoreConfig(instanceConfig.config)
}

trait NodeKeyBuilder {
  self: SecureRandomBuilder with InstanceConfigProvider =>
  lazy val nodeKey: AsymmetricCipherKeyPair = loadAsymmetricCipherKeyPair(instanceConfig.nodeKeyFile, secureRandom)
}

trait AsyncConfigBuilder {
  self: InstanceConfigProvider =>
  val asyncConfig: AsyncConfig = AsyncConfig(instanceConfig.config)
}

trait ActorSystemBuilder {
  self: InstanceConfigProvider =>
  implicit lazy val system: ActorSystem = ActorSystem(s"fukuii_${instanceConfig.instanceId}", ConfigFactory.load())
}

trait PruningConfigBuilder extends PruningModeComponent {
  self: InstanceConfigProvider =>
  override val pruningMode: PruningMode = PruningConfig(instanceConfig.config).mode
}

trait StorageBuilder {
  self: InstanceConfigProvider =>
  lazy val storagesInstance: DataSourceComponent with StoragesComponent with PruningModeComponent =
    instanceConfig.Db.dataSource match {
      case "rocksdb" =>
        new RocksDbDataSourceComponent
          with PruningConfigBuilder
          with Storages.DefaultStorages
          with InstanceConfigProvider {
          override def instanceConfig: InstanceConfig = self.instanceConfig
        }
    }
}

trait DiscoveryConfigBuilder extends BlockchainConfigBuilder {
  self: InstanceConfigProvider =>
  lazy val discoveryConfig: DiscoveryConfig =
    DiscoveryConfig(instanceConfig.config, blockchainConfig.bootstrapNodes, blockchainConfig.dnsDiscoveryDomains)
}

trait KnownNodesManagerBuilder {
  self: ActorSystemBuilder with StorageBuilder with InstanceConfigProvider =>

  lazy val knownNodesManagerConfig: KnownNodesManager.KnownNodesManagerConfig =
    KnownNodesManager.KnownNodesManagerConfig(instanceConfig.config)

  lazy val knownNodesManager: ActorRef = system.actorOf(
    KnownNodesManager.props(knownNodesManagerConfig, storagesInstance.storages.knownNodesStorage),
    "known-nodes-manager"
  )
}

trait AutoBlockerBuilder {
  self: ActorSystemBuilder with DiscoveryConfigBuilder =>

  def blockedIPRegistry: BlockedIPRegistry

  lazy val autoBlocker: Option[AutoBlocker] =
    if (discoveryConfig.autoBlock.enabled)
      Some(new AutoBlocker(
        blockedIPRegistry,
        system.scheduler,
        system.dispatcher,
        discoveryConfig.autoBlock.udpFailureThreshold,
        discoveryConfig.autoBlock.udpFailureWindow,
        discoveryConfig.autoBlock.udpBlockDuration,
        discoveryConfig.autoBlock.hardFailureBlockDuration
      ))
    else None
}

trait PeerDiscoveryManagerBuilder {
  self: ActorSystemBuilder
    with NodeStatusBuilder
    with DiscoveryConfigBuilder
    with DiscoveryServiceBuilder
    with AutoBlockerBuilder
    with StorageBuilder
    with InstanceConfigProvider =>

  implicit lazy val ioRuntime: IORuntime = IORuntime.global

  lazy val peerDiscoveryManager: ActorRef = system.actorOf(
    PeerDiscoveryManager.props(
      localNodeId = ByteString(nodeStatusHolder.get.nodeId),
      discoveryConfig,
      storagesInstance.storages.knownNodesStorage,
      discoveryServiceResource(
        discoveryConfig,
        tcpPort = instanceConfig.Network.Server.port,
        nodeStatusHolder,
        storagesInstance.storages.knownNodesStorage,
        autoBlocker
      ),
      randomNodeBufferSize = instanceConfig.Network.peer.maxOutgoingPeers
    ),
    "peer-discovery-manager"
  )
}

trait BlacklistBuilder {
  private val blacklistSize: Int = 1000
  lazy val blacklist: Blacklist = CacheBasedBlacklist.empty(blacklistSize)
}

trait NodeStatusBuilder {

  self: NodeKeyBuilder =>

  private val nodeStatus =
    NodeStatus(key = nodeKey, serverStatus = ServerStatus.NotListening, discoveryStatus = ServerStatus.NotListening)

  lazy val nodeStatusHolder = new AtomicReference(nodeStatus)
}

trait BlockchainBuilder {
  self: StorageBuilder =>

  lazy val blockchainReader: BlockchainReader = BlockchainReader(storagesInstance.storages)
  lazy val blockchainWriter: BlockchainWriter = BlockchainWriter(storagesInstance.storages)
  lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)
}

trait MESSBuilder {
  self: BlockchainConfigBuilder =>

  lazy val messConfigOpt: Option[MESSConfig] = {
    val config = blockchainConfig.messConfig
    if (config.activationBlock.isDefined) Some(config) else None
  }
}

trait BlockQueueBuilder {
  self: BlockchainBuilder with SyncConfigBuilder =>

  lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
}

trait ConsensusBuilder {
  self: BlockchainBuilder with BlockQueueBuilder with MiningBuilder with ActorSystemBuilder with StorageBuilder =>

  lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
  lazy val blockExecution = new BlockExecution(
    blockchain,
    blockchainReader,
    blockchainWriter,
    storagesInstance.storages.evmCodeStorage,
    mining.blockPreparator,
    blockValidation
  )

  lazy val consensus: Consensus =
    new ConsensusImpl(
      blockchain,
      blockchainReader,
      blockchainWriter,
      blockExecution
    )

  lazy val chainImporter: ChainImporter =
    new ChainImporter(blockchainReader, blockchainWriter, blockExecution, blockValidation)

  lazy val consensusAdapter: ConsensusAdapter =
    new ConsensusAdapter(
      consensus,
      blockchainReader,
      blockQueue,
      blockValidation,
      IORuntime.global
    )
}

trait ForkResolverBuilder {
  self: BlockchainConfigBuilder =>

  lazy val forkResolverOpt: Option[ForkResolver.EtcForkResolver] =
    blockchainConfig.daoForkConfig.map(new ForkResolver.EtcForkResolver(_))

}

trait HandshakerBuilder {
  self: BlockchainBuilder
    with NodeStatusBuilder
    with StorageBuilder
    with PeerManagerActorBuilder
    with ForkResolverBuilder
    with BlockchainConfigBuilder =>

  private val handshakerConfiguration: NetworkHandshakerConfiguration =
    new NetworkHandshakerConfiguration {
      override val forkResolverOpt: Option[ForkResolver] = self.forkResolverOpt
      override val nodeStatusHolder: AtomicReference[NodeStatus] = self.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = self.peerConfiguration
      override val blockchain: Blockchain = self.blockchain
      override val blockchainReader: BlockchainReader = self.blockchainReader
      override val appStateStorage: AppStateStorage = self.storagesInstance.storages.appStateStorage
      override val blockchainConfig: BlockchainConfig = self.blockchainConfig
    }

  lazy val handshaker: Handshaker[PeerInfo] = NetworkHandshaker(handshakerConfiguration)
}

trait AuthHandshakerBuilder {
  self: NodeKeyBuilder with SecureRandomBuilder =>

  lazy val authHandshaker: AuthHandshaker = AuthHandshaker(nodeKey, secureRandom)
}

trait PeerEventBusBuilder {
  self: ActorSystemBuilder =>

  lazy val peerEventBus: ActorRef = system.actorOf(PeerEventBusActor.props, "peer-event-bus")
}

trait PeerStatisticsBuilder {
  self: ActorSystemBuilder with PeerEventBusBuilder with InstanceConfigProvider =>

  implicit val clock: Clock = Clock.systemUTC()

  lazy val peerStatistics: ActorRef = system.actorOf(
    PeerStatisticsActor.props(
      peerEventBus,
      // `slotCount * slotDuration` should be set so that it's at least as long
      // as any client of the `PeerStatisticsActor` requires.
      slotDuration = instanceConfig.Network.peer.statSlotDuration,
      slotCount = instanceConfig.Network.peer.statSlotCount
    ),
    "peer-statistics"
  )
}

trait PeerManagerActorBuilder {

  self: ActorSystemBuilder
    with HandshakerBuilder
    with PeerEventBusBuilder
    with AuthHandshakerBuilder
    with PeerDiscoveryManagerBuilder
    with DiscoveryConfigBuilder
    with AutoBlockerBuilder
    with StorageBuilder
    with KnownNodesManagerBuilder
    with PeerStatisticsBuilder
    with BlacklistBuilder
    with InstanceConfigProvider =>

  lazy val peerConfiguration: PeerConfiguration = instanceConfig.Network.peer

  lazy val blockedIPRegistry: BlockedIPRegistry =
    new BlockedIPRegistry(discoveryConfig.blockedIPs)

  lazy val staticNodeUris: Set[URI] =
    StaticNodesLoader.load(instanceConfig.config.getString("datadir")).toSet

  lazy val peerManager: ActorRef = system.actorOf(
    PeerManagerActor.props(
      peerDiscoveryManager,
      instanceConfig.Network.peer,
      peerEventBus,
      knownNodesManager,
      peerStatistics,
      handshaker,
      authHandshaker,
      discoveryConfig,
      blacklist,
      Config.supportedCapabilities,
      blockedIPRegistry,
      staticNodeUris,
      autoBlocker
    ),
    "peer-manager"
  )

}

trait NetworkPeerManagerActorBuilder {
  self: ActorSystemBuilder
    with PeerManagerActorBuilder
    with PeerEventBusBuilder
    with ForkResolverBuilder
    with StorageBuilder
    with BlockchainBuilder =>

  lazy val networkPeerManager: ActorRef = system.actorOf(
    NetworkPeerManagerActor
      .props(
        peerManager,
        peerEventBus,
        storagesInstance.storages.appStateStorage,
        forkResolverOpt,
        evmCodeStorage = Some(storagesInstance.storages.evmCodeStorage),
        mptStorageOpt = Some(storagesInstance.storages.stateStorage.getReadOnlyStorage),
        blockchainReader = Some(blockchainReader)
      ),
    "network-peer-manager"
  )

}

trait BlockchainHostBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with StorageBuilder
    with PeerManagerActorBuilder
    with NetworkPeerManagerActorBuilder
    with PeerEventBusBuilder
    with PendingTransactionsManagerBuilder =>

  val blockchainHost: ActorRef = system.actorOf(
    BlockchainHostActor.props(
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      peerConfiguration,
      peerEventBus,
      networkPeerManager,
      Some(pendingTransactionsManager)
    ),
    "blockchain-host"
  )

}

trait ServerActorBuilder {

  self: ActorSystemBuilder
    with NodeStatusBuilder
    with BlockchainBuilder
    with PeerManagerActorBuilder
    with InstanceConfigProvider =>

  lazy val networkConfig = instanceConfig.Network

  lazy val server: ActorRef = system.actorOf(ServerActor.props(nodeStatusHolder, peerManager), "server")

}

trait Web3ServiceBuilder {
  lazy val web3Service = new Web3Service
}

trait NetServiceBuilder {
  this: PeerManagerActorBuilder with NodeStatusBuilder with BlacklistBuilder with InstanceConfigProvider =>

  lazy val netServiceConfig: NetServiceConfig = NetServiceConfig(instanceConfig.config)

  lazy val netService = new NetService(nodeStatusHolder, peerManager, blacklist, netServiceConfig)
}

trait PendingTransactionsManagerBuilder {
  def pendingTransactionsManager: ActorRef
}
object PendingTransactionsManagerBuilder {
  trait Default extends PendingTransactionsManagerBuilder {
    self: ActorSystemBuilder
      with PeerManagerActorBuilder
      with NetworkPeerManagerActorBuilder
      with PeerEventBusBuilder
      with TxPoolConfigBuilder
      with BlockchainBuilder
      with StorageBuilder =>

    lazy val pendingTransactionsManager: ActorRef =
      system.actorOf(
        PendingTransactionsManager.props(
          txPoolConfig,
          peerManager,
          networkPeerManager,
          peerEventBus,
          blockchainReader,
          storagesInstance.storages.stateStorage
        )
      )
  }
}

trait TransactionHistoryServiceBuilder {
  def transactionHistoryService: TransactionHistoryService
}
object TransactionHistoryServiceBuilder {
  trait Default extends TransactionHistoryServiceBuilder {
    self: BlockchainBuilder with PendingTransactionsManagerBuilder with TxPoolConfigBuilder =>
    lazy val transactionHistoryService =
      new TransactionHistoryService(
        blockchainReader,
        pendingTransactionsManager,
        txPoolConfig.getTransactionFromPoolTimeout
      )
  }
}

trait FilterManagerBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with StorageBuilder
    with KeyStoreBuilder
    with PendingTransactionsManagerBuilder
    with FilterConfigBuilder
    with TxPoolConfigBuilder
    with MiningBuilder =>

  lazy val filterManager: ActorRef =
    system.actorOf(
      FilterManager.props(
        blockchainReader,
        mining.blockGenerator,
        keyStore,
        pendingTransactionsManager,
        filterConfig,
        txPoolConfig
      ),
      "filter-manager"
    )
}

trait DebugServiceBuilder {
  self: NetworkPeerManagerActorBuilder with PeerManagerActorBuilder =>

  lazy val debugService = new DebugService(peerManager, networkPeerManager)
}

trait EthProofServiceBuilder {
  self: StorageBuilder with BlockchainBuilder with BlockchainConfigBuilder with MiningBuilder =>

  lazy val ethProofService: ProofService = new EthProofService(
    blockchain,
    blockchainReader,
    mining.blockGenerator,
    blockchainConfig.ethCompatibleStorage
  )
}

trait EthInfoServiceBuilder {
  self: StorageBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with MiningBuilder
    with StxLedgerBuilder
    with KeyStoreBuilder
    with SyncControllerBuilder
    with AsyncConfigBuilder
    with InstanceConfigProvider =>

  lazy val ethInfoService = new EthInfoService(
    blockchain,
    blockchainReader,
    blockchainConfig,
    mining,
    stxLedger,
    keyStore,
    syncController,
    Capability.best(instanceConfig.supportedCapabilities),
    asyncConfig.askTimeout
  )
}

trait EthSimulateServiceBuilder {
  self: StorageBuilder with BlockchainBuilder with BlockchainConfigBuilder with MiningBuilder =>

  lazy val ethSimulateService = new com.chipprbots.ethereum.jsonrpc.EthSimulateService(
    blockchain,
    blockchainReader,
    storagesInstance.storages.evmCodeStorage,
    mining.blockPreparator,
    mining,
    blockchainConfig
  )
}

trait EthMiningServiceBuilder {
  self: BlockchainBuilder
    with BlockchainConfigBuilder
    with MiningBuilder
    with JSONRpcConfigBuilder
    with OmmersPoolBuilder
    with SyncControllerBuilder
    with PendingTransactionsManagerBuilder
    with TxPoolConfigBuilder
    with ActorSystemBuilder =>

  lazy val ethMiningService = new EthMiningService(
    blockchainReader,
    mining,
    jsonRpcConfig,
    ommersPool,
    syncController,
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    this,
    coinbaseProvider,
    system
  )
}
trait EthTxServiceBuilder {
  self: BlockchainBuilder
    with PendingTransactionsManagerBuilder
    with MiningBuilder
    with TxPoolConfigBuilder
    with StorageBuilder =>

  lazy val ethTxService = new EthTxService(
    blockchain,
    blockchainReader,
    mining,
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    storagesInstance.storages.transactionMappingStorage
  )
}

trait EthBlocksServiceBuilder {
  self: BlockchainBuilder with MiningBuilder with BlockQueueBuilder =>

  /** Override in subtraits that have access to ForkChoiceManager (e.g. EngineApiBuilder) */
  def forkChoiceManagerForRpc: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] = None

  lazy val ethBlocksService =
    new EthBlocksService(blockchain, blockchainReader, mining, blockQueue, forkChoiceManagerForRpc)
}

trait EthUserServiceBuilder {
  self: BlockchainBuilder with BlockchainConfigBuilder with MiningBuilder with StorageBuilder =>

  lazy val ethUserService = new EthUserService(
    blockchain,
    blockchainReader,
    mining,
    storagesInstance.storages.evmCodeStorage,
    this
  )
}

trait EthFilterServiceBuilder {
  self: FilterManagerBuilder with FilterConfigBuilder with BlockchainBuilder =>

  lazy val ethFilterService = new EthFilterService(
    filterManager,
    filterConfig,
    blockchainReader
  )
}

trait PersonalServiceBuilder {
  self: KeyStoreBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with PendingTransactionsManagerBuilder
    with StorageBuilder
    with TxPoolConfigBuilder =>

  lazy val personalService: PersonalServiceAPI = new PersonalService(
    keyStore,
    blockchainReader,
    pendingTransactionsManager,
    txPoolConfig,
    this
  )
}

trait QaServiceBuilder {
  self: MiningBuilder =>

  lazy val qaService =
    new QAService(
      mining
    )
}

trait SyncControllerRefBuilder {
  def syncController: ActorRef
}

trait FukuiiServiceBuilder {
  self: TransactionHistoryServiceBuilder with JSONRpcConfigBuilder with SyncControllerRefBuilder =>

  lazy val fukuiiService = new FukuiiService(transactionHistoryService, jsonRpcConfig, syncController)
}

trait McpServiceBuilder {
  self: PeerManagerActorBuilder
    with SyncControllerBuilder
    with ActorSystemBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with NodeStatusBuilder
    with StorageBuilder =>

  lazy val mcpService = new McpService(
    peerManager,
    syncController,
    blockchainReader,
    blockchainConfig,
    nodeStatusHolder,
    storagesInstance.storages.transactionMappingStorage
  )(system.dispatcher)
}

trait KeyStoreBuilder {
  self: SecureRandomBuilder with KeyStoreConfigBuilder =>
  lazy val keyStore: KeyStore = new KeyStoreImpl(keyStoreConfig, secureRandom)
}

trait ApisBuilder extends ApisBase {
  object Apis {
    val Eth = "eth"
    val Web3 = "web3"
    val Net = "net"
    val Personal = "personal"
    val Fukuii = "fukuii"
    val Mcp = "mcp"
    val Debug = "debug"
    val Rpc = "rpc"
    val Test = "test"
    val Iele = "iele"
    val Qa = "qa"
    val Admin = "admin"
    val TxPool = "txpool"
    val Trace = "trace"
    val Subscribe = "subscribe"
  }

  import Apis._
  override def available: List[String] =
    List(Eth, Web3, Net, Personal, Fukuii, Mcp, Debug, Test, Iele, Qa, Admin, TxPool, Trace, Subscribe)
}

trait AdminServiceBuilder {
  this: PeerManagerActorBuilder
    with NodeStatusBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with InstanceConfigProvider =>

  lazy val adminService: AdminService = new AdminService(
    nodeStatusHolder,
    peerManager,
    blockchainReader,
    blockchainConfig,
    instanceConfig.config.getConfig("network.rpc.net").getDuration("peer-manager-timeout").toMillis.millis,
    instanceConfig.config.getString("datadir"),
    blockedIPRegistry
  )
}

trait TxPoolServiceBuilder {
  this: PendingTransactionsManagerBuilder with TxPoolConfigBuilder =>

  lazy val txPoolService: TxPoolService = new TxPoolService(
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    txPoolConfig
  )
}

trait DebugTracingServiceBuilder {
  this: BlockchainBuilder with StxLedgerBuilder with StorageBuilder with MiningBuilder =>

  lazy val debugTracingService: com.chipprbots.ethereum.jsonrpc.DebugTracingService =
    new com.chipprbots.ethereum.jsonrpc.DebugTracingService(
      blockchain,
      blockchainReader,
      mining,
      stxLedger,
      storagesInstance.storages.transactionMappingStorage
    )
}

trait TraceServiceBuilder {
  this: BlockchainBuilder with StxLedgerBuilder with StorageBuilder with MiningBuilder =>

  lazy val traceService: com.chipprbots.ethereum.jsonrpc.TraceService =
    new com.chipprbots.ethereum.jsonrpc.TraceService(
      blockchain,
      blockchainReader,
      mining,
      stxLedger,
      storagesInstance.storages.transactionMappingStorage
    )
}

trait JSONRpcConfigBuilder {
  self: ApisBuilder with InstanceConfigProvider =>

  lazy val availableApis: List[String] = available
  lazy val jsonRpcConfig: JsonRpcConfig = JsonRpcConfig(instanceConfig.config, availableApis)
}

trait JSONRpcControllerBuilder {
  this: Web3ServiceBuilder
    with EthInfoServiceBuilder
    with EthProofServiceBuilder
    with EthSimulateServiceBuilder
    with EthMiningServiceBuilder
    with EthBlocksServiceBuilder
    with EthTxServiceBuilder
    with EthUserServiceBuilder
    with EthFilterServiceBuilder
    with NetServiceBuilder
    with PersonalServiceBuilder
    with DebugServiceBuilder
    with JSONRpcConfigBuilder
    with QaServiceBuilder
    with FukuiiServiceBuilder
    with McpServiceBuilder
    with AdminServiceBuilder
    with TxPoolServiceBuilder
    with DebugTracingServiceBuilder
    with TraceServiceBuilder =>

  protected def testService: Option[TestService] = None

  lazy val jsonRpcController =
    new JsonRpcController(
      web3Service,
      netService,
      ethInfoService,
      ethMiningService,
      ethBlocksService,
      ethTxService,
      ethUserService,
      ethFilterService,
      personalService,
      testService,
      debugService,
      qaService,
      fukuiiService,
      mcpService,
      ethProofService,
      ethSimulateService,
      adminService,
      txPoolService,
      debugTracingService,
      traceService,
      jsonRpcConfig
    )
}

trait JSONRpcHealthcheckerBuilder {
  this: NetServiceBuilder
    with EthBlocksServiceBuilder
    with JSONRpcConfigBuilder
    with AsyncConfigBuilder
    with SyncControllerBuilder =>
  lazy val jsonRpcHealthChecker: JsonRpcHealthChecker =
    new NodeJsonRpcHealthChecker(
      netService,
      ethBlocksService,
      syncController,
      jsonRpcConfig.healthConfig,
      asyncConfig
    )
}

trait EngineApiBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with ConsensusBuilder
    with StorageBuilder
    with MiningBuilder
    with PendingTransactionsManagerBuilder
    with InstanceConfigProvider
    with JSONRpcControllerBuilder =>

  import com.chipprbots.ethereum.consensus.engine._

  lazy val engineApiConfig: EngineApiHttpServer.Config = {
    val engineConf = scala.util.Try(instanceConfig.config.getConfig("network.engine-api")).toOption
    EngineApiHttpServer.Config(
      enabled = engineConf.flatMap(c => scala.util.Try(c.getBoolean("enabled")).toOption).getOrElse(false),
      interface = engineConf.flatMap(c => scala.util.Try(c.getString("interface")).toOption).getOrElse("localhost"),
      port = engineConf.flatMap(c => scala.util.Try(c.getInt("port")).toOption).getOrElse(8551),
      jwtSecretPath = engineConf.flatMap(c => scala.util.Try(c.getString("jwt-secret-path")).toOption)
    )
  }

  lazy val forkChoiceManager: ForkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

  lazy val engineApiService: EngineApiService = new EngineApiService(
    blockchainReader,
    blockchainWriter,
    blockExecution,
    forkChoiceManager,
    mining,
    storagesInstance.storages.evmCodeStorage,
    pendingTransactionsManager
  )(blockchainConfig)

  lazy val engineApiController: EngineApiController = new EngineApiController(engineApiService, Some(jsonRpcController))

  lazy val maybeEngineApiServer: Option[EngineApiHttpServer] =
    if (engineApiConfig.enabled) {
      val jwtAuth = engineApiConfig.jwtSecretPath match {
        case Some(path) => JwtAuthenticator.fromFile(path)
        case None       => JwtAuthenticator.generateRandom()
      }
      Some(new EngineApiHttpServer(engineApiController, jwtAuth, engineApiConfig))
    } else None
}

trait JSONRpcHttpServerBuilder {
  self: ActorSystemBuilder
    with BlockchainBuilder
    with JSONRpcControllerBuilder
    with JSONRpcHealthcheckerBuilder
    with SecureRandomBuilder
    with JSONRpcConfigBuilder
    with SSLContextBuilder =>

  lazy val maybeJsonRpcHttpServer: Either[String, JsonRpcHttpServer] =
    JsonRpcHttpServer(
      jsonRpcController,
      jsonRpcHealthChecker,
      jsonRpcConfig.httpServerConfig,
      () => sslContext("fukuii.network.rpc.http")
    )
}

trait JSONRpcIpcServerBuilder {
  self: ActorSystemBuilder with JSONRpcControllerBuilder with JSONRpcConfigBuilder =>

  lazy val jsonRpcIpcServer = new JsonRpcIpcServer(jsonRpcController, jsonRpcConfig.ipcServerConfig)
}

trait SubscriptionManagerBuilder {
  self: ActorSystemBuilder with BlockchainBuilder =>

  lazy val subscriptionManager: ActorRef =
    system.actorOf(
      com.chipprbots.ethereum.jsonrpc.SubscriptionManager.props(blockchainReader),
      "subscription-manager"
    )
}

trait JSONRpcWsServerBuilder {
  self: ActorSystemBuilder with JSONRpcControllerBuilder with JSONRpcConfigBuilder with SubscriptionManagerBuilder =>

  lazy val jsonRpcWsServer: com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer =
    new com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer(
      jsonRpcController,
      subscriptionManager,
      jsonRpcConfig.wsServerConfig
    )(system)
}

trait OmmersPoolBuilder {
  self: ActorSystemBuilder with BlockchainBuilder with MiningConfigBuilder =>

  lazy val ommersPoolSize: Int = 30
  lazy val ommersPool: ActorRef = system.actorOf(OmmersPool.props(blockchainReader, ommersPoolSize))
}

trait VmBuilder {
  self: ActorSystemBuilder with BlockchainConfigBuilder with VmConfigBuilder =>

  lazy val vm: VMImpl = VmSetup.vm(vmConfig, blockchainConfig, testMode = false)
}

trait StxLedgerBuilder {
  self: BlockchainConfigBuilder
    with BlockchainBuilder
    with StorageBuilder
    with SyncConfigBuilder
    with MiningBuilder
    with ActorSystemBuilder =>

  lazy val stxLedger: StxLedger =
    new StxLedger(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      this
    )
}

trait SyncControllerBuilder extends SyncControllerRefBuilder {

  self: ActorSystemBuilder
    with ServerActorBuilder
    with BlockchainBuilder
    with BlockchainConfigBuilder
    with ConsensusBuilder
    with NodeStatusBuilder
    with StorageBuilder
    with StxLedgerBuilder
    with PeerEventBusBuilder
    with PendingTransactionsManagerBuilder
    with OmmersPoolBuilder
    with NetworkPeerManagerActorBuilder
    with SyncConfigBuilder
    with ShutdownHookBuilder
    with MiningBuilder
    with BlacklistBuilder
    with MESSBuilder =>

  lazy val syncController: ActorRef = system.actorOf(
    SyncController.props(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.appStateStorage,
      storagesInstance.storages.blockNumberMappingStorage,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage,
      storagesInstance.storages.nodeStorage,
      storagesInstance.storages.flatSlotStorage,
      storagesInstance.storages.fastSyncStateStorage,
      consensusAdapter,
      mining.validators,
      peerEventBus,
      pendingTransactionsManager,
      ommersPool,
      networkPeerManager,
      blacklist,
      syncConfig,
      this,
      messConfigOpt
    ),
    "sync-controller"
  )

}

trait PortForwardingBuilder {
  self: DiscoveryConfigBuilder with InstanceConfigProvider =>

  implicit lazy val ioRuntime: IORuntime = IORuntime.global

  // protected for testing purposes - allows test fixtures to override with mock implementation
  protected lazy val portForwarding: IO[IO[Unit]] = PortForwarder
    .openPorts(
      Seq(instanceConfig.Network.Server.port),
      Seq(discoveryConfig.port).filter(_ => discoveryConfig.discoveryEnabled)
    )
    .whenA(instanceConfig.Network.automaticPortForwarding)
    .allocated
    .map(_._2)

  // reference to the cleanup IO for the port forwarding resource,
  // memoized to prevent running multiple port forwarders at once
  private val portForwardingRelease = new AtomicReference(Option.empty[IO[Unit]])

  def startPortForwarding(): Future[Unit] = {
    // Only allocate the resource if it hasn't been started yet
    // Use a placeholder to ensure only one thread performs the allocation
    val placeholder = IO.unit
    if (portForwardingRelease.compareAndSet(None, Some(placeholder))) {
      // We won the race - allocate the resource and store the cleanup function
      portForwarding
        .flatMap { cleanup =>
          IO {
            portForwardingRelease.set(Some(cleanup))
            ()
          }
        }
        .unsafeToFuture()(ioRuntime)
    } else {
      // Resource was already started by another thread
      Future.unit
    }
  }

  def stopPortForwarding(): Future[Unit] =
    portForwardingRelease.getAndSet(None).fold(Future.unit)(_.unsafeToFuture()(ioRuntime))
}

trait ShutdownHookBuilder {
  self: Logger with InstanceConfigProvider =>
  def shutdown: () => Unit = () => {
    /* No default behaviour during shutdown. */
  }

  lazy val shutdownTimeoutDuration: Duration = instanceConfig.shutdownTimeout

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit =
      shutdown()
  })

  def shutdownOnError[A](f: => A): A =
    Try(f) match {
      case Success(v) => v
      case Failure(t) =>
        log.error(t.getMessage, t)
        shutdown()
        throw t
    }
}

object ShutdownHookBuilder extends ShutdownHookBuilder with Logger with InstanceConfigProvider {
  override def instanceConfig: InstanceConfig = Config
}

trait GenesisDataLoaderBuilder {
  self: BlockchainBuilder with StorageBuilder =>

  lazy val genesisDataLoader =
    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    )

}

/** Provides the basic functionality of a Node, except the mining algorithm. The latter is loaded dynamically based on
  * configuration.
  *
  * @see
  *   [[com.chipprbots.ethereum.consensus.mining.MiningBuilder MiningBuilder]],
  *   [[com.chipprbots.ethereum.consensus.mining.MiningConfigBuilder ConsensusConfigBuilder]]
  */
trait Node
    extends InstanceConfigProvider
    with SecureRandomBuilder
    with NodeKeyBuilder
    with ActorSystemBuilder
    with StorageBuilder
    with BlockchainBuilder
    with MESSBuilder
    with BlockQueueBuilder
    with ConsensusBuilder
    with NodeStatusBuilder
    with ForkResolverBuilder
    with HandshakerBuilder
    with PeerStatisticsBuilder
    with PeerManagerActorBuilder
    with ServerActorBuilder
    with SyncControllerBuilder
    with Web3ServiceBuilder
    with EthInfoServiceBuilder
    with EthProofServiceBuilder
    with EthSimulateServiceBuilder
    with EthMiningServiceBuilder
    with EthBlocksServiceBuilder
    with EthTxServiceBuilder
    with EthUserServiceBuilder
    with EthFilterServiceBuilder
    with NetServiceBuilder
    with PersonalServiceBuilder
    with DebugServiceBuilder
    with QaServiceBuilder
    with FukuiiServiceBuilder
    with McpServiceBuilder
    with AdminServiceBuilder
    with TxPoolServiceBuilder
    with DebugTracingServiceBuilder
    with TraceServiceBuilder
    with KeyStoreBuilder
    with ApisBuilder
    with JSONRpcConfigBuilder
    with JSONRpcHealthcheckerBuilder
    with JSONRpcControllerBuilder
    with SSLContextBuilder
    with JSONRpcHttpServerBuilder
    with JSONRpcIpcServerBuilder
    with SubscriptionManagerBuilder
    with JSONRpcWsServerBuilder
    with EngineApiBuilder
    with ShutdownHookBuilder
    with Logger
    with GenesisDataLoaderBuilder
    with BlockchainConfigBuilder
    with VmConfigBuilder
    with PeerEventBusBuilder
    with PendingTransactionsManagerBuilder.Default
    with OmmersPoolBuilder
    with NetworkPeerManagerActorBuilder
    with BlockchainHostBuilder
    with FilterManagerBuilder
    with FilterConfigBuilder
    with TxPoolConfigBuilder
    with AuthHandshakerBuilder
    with PruningConfigBuilder
    with PeerDiscoveryManagerBuilder
    with DiscoveryServiceBuilder
    with DiscoveryConfigBuilder
    with KnownNodesManagerBuilder
    with SyncConfigBuilder
    with VmBuilder
    with MiningBuilder
    with MiningConfigBuilder
    with StxLedgerBuilder
    with KeyStoreConfigBuilder
    with AsyncConfigBuilder
    with TransactionHistoryServiceBuilder.Default
    with PortForwardingBuilder
    with AutoBlockerBuilder
    with BlacklistBuilder {
  // Resolve conflicting ioRuntime from PeerDiscoveryManagerBuilder and PortForwardingBuilder
  implicit override lazy val ioRuntime: IORuntime = IORuntime.global

  // Wire ForkChoiceManager to RPC services for "safe"/"finalized" block tag resolution
  override def forkChoiceManagerForRpc: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] =
    Some(forkChoiceManager)
}
