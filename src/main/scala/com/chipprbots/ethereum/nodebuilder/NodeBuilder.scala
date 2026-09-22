package com.chipprbots.ethereum.nodebuilder

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.blockchain.data.ChainImporter
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
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
import com.chipprbots.ethereum.db.components.*
import com.chipprbots.ethereum.db.components.Storages.PruningModeComponent
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.*
import com.chipprbots.ethereum.jsonrpc.NetService.NetServiceConfig
import com.chipprbots.ethereum.jsonrpc.server.controllers.ApisBase
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStoreImpl
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.network.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.discovery.DiscoveryConfig
import com.chipprbots.ethereum.network.discovery.DiscoveryServiceBuilder
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.handshaker.NetworkHandshaker
import com.chipprbots.ethereum.network.handshaker.NetworkHandshakerConfiguration
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.rlpx.AuthHandshaker
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.security.SSLContextBuilder
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.utils.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off number.of.types
trait BlockchainConfigBuilder:
  self: InstanceConfigProvider =>
  protected lazy val initBlockchainConfig = instanceConfig.blockchains.blockchainConfig
  implicit def blockchainConfig: BlockchainConfig = initBlockchainConfig

trait VmConfigBuilder:
  self: InstanceConfigProvider =>
  lazy val vmConfig: VmConfig = VmConfig(instanceConfig.config)

trait SyncConfigBuilder:
  self: InstanceConfigProvider =>
  lazy val syncConfig: SyncConfig = SyncConfig(instanceConfig.config)

trait TxPoolConfigBuilder:
  self: InstanceConfigProvider =>
  lazy val txPoolConfig: TxPoolConfig = TxPoolConfig(instanceConfig.config)

trait FilterConfigBuilder:
  self: InstanceConfigProvider =>
  lazy val filterConfig: FilterConfig = FilterConfig(instanceConfig.config)

trait KeyStoreConfigBuilder:
  self: InstanceConfigProvider =>
  lazy val keyStoreConfig: KeyStoreConfig = KeyStoreConfig(instanceConfig.config)

trait NodeKeyBuilder:
  self: SecureRandomBuilder & InstanceConfigProvider =>
  lazy val nodeKey: AsymmetricCipherKeyPair = loadAsymmetricCipherKeyPair(instanceConfig.nodeKeyFile, secureRandom)

trait AsyncConfigBuilder:
  self: InstanceConfigProvider =>
  val asyncConfig: AsyncConfig = AsyncConfig(instanceConfig.config)

trait ActorSystemBuilder:
  self: InstanceConfigProvider =>
  // Physical Classic ActorSystem. Not implicit — callers use classicSystem directly.
  // Test builders inherit this without getting a Typed system: ActorSystem[Nothing] collision.
  lazy val classicSystem: org.apache.pekko.actor.ActorSystem =
    org.apache.pekko.actor.ActorSystem(s"fukuii_${instanceConfig.instanceId}", ConfigFactory.load())

// Mixed into production Node only. Wraps the Classic system as the Typed surface used by builders.
trait TypedActorSystemProvider:
  self: ActorSystemBuilder =>
  given system: ActorSystem[Nothing] = classicSystem.toTyped

// Dedicated Topic[T] pub/sub channels for blockchain events (replaces ActorSystem.eventStream, 7b).
// Producers (BlockImporter, PendingTransactionsManager) publish; SubscriptionManager subscribes.
// Single-JVM, local pub/sub — no serialization needed.
trait EventTopicsBuilder:
  self: ActorSystemBuilder =>

  lazy val pendingTxTopic: org.apache.pekko.actor.typed.ActorRef[org.apache.pekko.actor.typed.pubsub.Topic.Command[
    com.chipprbots.ethereum.jsonrpc.NewPendingTransaction
  ]] =
    classicSystem.spawn(
      org.apache.pekko.actor.typed.pubsub
        .Topic[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction]("pending-tx-topic"),
      "pending-tx-topic"
    )

  lazy val blockTopic: org.apache.pekko.actor.typed.ActorRef[org.apache.pekko.actor.typed.pubsub.Topic.Command[
    com.chipprbots.ethereum.jsonrpc.NewBlockImported
  ]] =
    classicSystem.spawn(
      org.apache.pekko.actor.typed.pubsub
        .Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported]("block-imported-topic"),
      "block-imported-topic"
    )

trait PruningConfigBuilder extends PruningModeComponent:
  self: InstanceConfigProvider =>
  override val pruningMode: PruningMode = PruningConfig(instanceConfig.config).mode

trait StorageBuilder:
  self: InstanceConfigProvider =>
  lazy val storagesInstance: DataSourceComponent & StoragesComponent & PruningModeComponent =
    instanceConfig.Db.dataSource match
      case "rocksdb" =>
        new RocksDbDataSourceComponent
          with PruningConfigBuilder
          with Storages.DefaultStorages
          with InstanceConfigProvider:
          override def instanceConfig: InstanceConfig = self.instanceConfig

trait DiscoveryConfigBuilder extends BlockchainConfigBuilder with StorageBuilder:
  self: InstanceConfigProvider & ActorSystemBuilder =>
  // Built lazily so blockchain storage is initialized before genesisHeader is read.
  // The filter rejects ENRs with `eth` key fork IDs that don't match the local chain —
  // stops cross-network peers (BSC, mainnet, etc.) from burning outbound dial slots when
  // shared DNS trees include mis-tagged entries. See PR #1249. Mirrors ForkIdTag (discv4).
  lazy val discoveryConfig: DiscoveryConfig =
    val reader = com.chipprbots.ethereum.domain.BlockchainReader(storagesInstance.storages)
    val enrFilter = new com.chipprbots.ethereum.network.discovery.DnsDiscovery.EnrForkIdFilter(
      genesisHash = () => reader.genesisHeader.hash.value,
      genesisTimestamp = () => reader.genesisHeader.unixTimestamp.toLong,
      blockchainConfig = blockchainConfig,
      currentBestBlock = () => reader.getBestBlockNumber
    )
    DiscoveryConfig(
      instanceConfig.config,
      blockchainConfig.bootstrapNodes,
      blockchainConfig.dnsDiscoveryDomains,
      enrForkIdFilter = Some(enrFilter)
    )

trait KnownNodesManagerBuilder:
  self: ActorSystemBuilder & StorageBuilder & InstanceConfigProvider =>

  lazy val knownNodesManagerConfig: KnownNodesManager.KnownNodesManagerConfig =
    KnownNodesManager.KnownNodesManagerConfig(instanceConfig.config)

  lazy val knownNodesManager: org.apache.pekko.actor.typed.ActorRef[KnownNodesManager.Command] =
    classicSystem.spawn(
      Behaviors
        .supervise(KnownNodesManager(knownNodesManagerConfig, storagesInstance.storages.knownNodesStorage))
        .onFailure[Throwable](SupervisorStrategy.restart),
      "known-nodes-manager"
    )

  // Alias kept for any caller that previously used the Typed-specific name during the bridge era.
  def knownNodesManagerTyped: org.apache.pekko.actor.typed.ActorRef[KnownNodesManager.Command] =
    knownNodesManager

trait PeerDiscoveryManagerBuilder:
  self: ActorSystemBuilder & NodeStatusBuilder & DiscoveryConfigBuilder & DiscoveryServiceBuilder & StorageBuilder &
    BlockchainBuilder & InstanceConfigProvider =>

  implicit lazy val ioRuntime: IORuntime = IORuntime.global

  // Typed ref — for in-scope callers and direct Typed wiring.
  lazy val peerDiscoveryManagerTyped: org.apache.pekko.actor.typed.ActorRef[PeerDiscoveryManager.Command] =
    classicSystem.spawn(
      Behaviors
        .supervise(
          PeerDiscoveryManager(
            localNodeId = ByteString(nodeStatusHolder.get.nodeId),
            discoveryConfig,
            storagesInstance.storages.knownNodesStorage,
            discoveryServiceResource(
              discoveryConfig,
              tcpPort = instanceConfig.Network.Server.port,
              nodeStatusHolder,
              storagesInstance.storages.knownNodesStorage,
              forkIdTag = Some(
                new com.chipprbots.ethereum.network.discovery.ForkIdTag(
                  genesisHash = () => blockchainReader.genesisHeader.hash.value,
                  genesisTimestamp = () => blockchainReader.genesisHeader.unixTimestamp.toLong,
                  blockchainConfig = blockchainConfig,
                  currentBestBlock = () => blockchainReader.getBestBlockNumber
                )
              )
            ),
            randomNodeBufferSize = instanceConfig.Network.peer.maxOutgoingPeers
          )
        )
        .onFailure[Throwable](SupervisorStrategy.restart),
      "peer-discovery-manager-typed"
    )

trait BlacklistBuilder:
  private val blacklistSize: Int = 1000
  lazy val blacklist: Blacklist = CacheBasedBlacklist.empty(blacklistSize)

trait NodeStatusBuilder:

  self: NodeKeyBuilder =>

  private val nodeStatus =
    NodeStatus(key = nodeKey, serverStatus = ServerStatus.NotListening, discoveryStatus = ServerStatus.NotListening)

  lazy val nodeStatusHolder = new AtomicReference(nodeStatus)

trait BlockchainBuilder:
  self: StorageBuilder =>

  lazy val blockchainReader: BlockchainReader = BlockchainReader(storagesInstance.storages)
  lazy val blockchainWriter: BlockchainWriter = BlockchainWriter(storagesInstance.storages)
  lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)

trait MESSBuilder:
  self: BlockchainConfigBuilder =>

  lazy val messConfigOpt: Option[MESSConfig] =
    val config = blockchainConfig.messConfig
    if config.activationBlock.isDefined then Some(config) else None

trait BlockQueueBuilder:
  self: BlockchainBuilder & SyncConfigBuilder =>

  lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)

trait ConsensusBuilder:
  self: BlockchainBuilder & BlockQueueBuilder & MiningBuilder & ActorSystemBuilder & StorageBuilder =>

  import com.chipprbots.ethereum.consensus.engine.DesignatedHead
  import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter

  lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
  lazy val blockExecution = new BlockExecution(
    blockchain,
    blockchainReader,
    blockchainWriter,
    storagesInstance.storages.evmCodeStorage,
    mining.blockPreparator,
    blockValidation
  )

  /** Late-bound channel from the p2p import path into the Engine API's invalid-block registry.
    *
    * Constructed unconditionally and inert until something binds it. `EngineApiBuilder.bindInvalidChainReporter()` is
    * the only binder and it is gated on `network.engine-api.enabled`, so on ETC/Mordor/Gorgoroth this stays unbound for
    * the life of the node and every `reportInvalid` is a no-op.
    *
    * It has to be late-bound rather than injected: `EngineApiBuilder` already depends on `ConsensusBuilder` for
    * `blockExecution`, so the reverse dependency would be a cake cycle.
    */
  lazy val invalidChainReporter: InvalidChainReporter.LateBound = new InvalidChainReporter.LateBound

  /** Late-bound read side of PoS fork choice, for `ConsensusImpl.importToNewBranch`.
    *
    * Same shape and same reason as `invalidChainReporter` above: constructed unconditionally, inert until bound, and
    * `EngineApiBuilder.bindDesignatedHead()` is the only binder. That binder is gated on `network.engine-api.enabled`
    * AND a configured terminal-total-difficulty, so on ETC/Mordor/Gorgoroth this stays unbound for the life of the
    * node, `headBlockHash` is always `None`, and `importToNewBranch` keeps its pre-merge weight comparison as the whole
    * decision.
    *
    * Late-bound for the cake, not by preference: `EngineApiBuilder` already depends on `ConsensusBuilder`, so
    * `ConsensusBuilder` cannot take a `ForkChoiceManager` at construction time. `SyncController` has no such problem
    * and builds its own `Option[DesignatedHead]` straight from the `ForkChoiceManager` it is already handed.
    */
  lazy val designatedHead: DesignatedHead.LateBound = new DesignatedHead.LateBound

  lazy val consensus: Consensus =
    new ConsensusImpl(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      Some(invalidChainReporter),
      Some(designatedHead)
    )

  lazy val chainImporter: ChainImporter =
    new ChainImporter(blockchainReader, blockchainWriter, blockExecution, blockValidation)

  lazy val consensusAdapter: ConsensusAdapter =
    new ConsensusAdapter(
      consensus,
      blockchainReader,
      blockQueue,
      blockValidation,
      IORuntime.global,
      Some(invalidChainReporter)
    )

trait ForkResolverBuilder:
  self: BlockchainConfigBuilder =>

  lazy val forkResolverOpt: Option[ForkResolver.IrregularStateChangeDaoForkResolver] =
    blockchainConfig.daoForkConfig.map(new ForkResolver.IrregularStateChangeDaoForkResolver(_))

trait HandshakerBuilder:
  self: BlockchainBuilder & NodeStatusBuilder & StorageBuilder & PeerManagerActorBuilder & ForkResolverBuilder &
    BlockchainConfigBuilder =>

  private val handshakerConfiguration: NetworkHandshakerConfiguration =
    new NetworkHandshakerConfiguration:
      override val forkResolverOpt: Option[ForkResolver] = self.forkResolverOpt
      override val nodeStatusHolder: AtomicReference[NodeStatus] = self.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = self.peerConfiguration
      override val blockchain: Blockchain = self.blockchain
      override val blockchainReader: BlockchainReader = self.blockchainReader
      override val appStateStorage: AppStateStorage = self.storagesInstance.storages.appStateStorage
      override val blockchainConfig: BlockchainConfig = self.blockchainConfig

  lazy val handshaker: Handshaker[PeerInfo] = NetworkHandshaker(handshakerConfiguration)

trait AuthHandshakerBuilder:
  self: NodeKeyBuilder & SecureRandomBuilder =>

  lazy val authHandshaker: AuthHandshaker = AuthHandshaker(nodeKey, secureRandom)

trait PeerEventBusBuilder:
  self: ActorSystemBuilder =>

  lazy val peerEventBus: TypedActorRef[PeerEventBusActor.Command] =
    val ref = classicSystem.spawn(PeerEventBusActor.behavior(), "peer-event-bus")
    // §7c-D1: STOP-AND-ALERT — restart would silently drop all subscribers.
    classicSystem.spawn(
      com.chipprbots.ethereum.network.CriticalActorAlerter(ref, "peer-event-bus"),
      "peer-event-bus-alerter"
    )
    ref

trait PeerStatisticsBuilder:
  self: ActorSystemBuilder & PeerEventBusBuilder & InstanceConfigProvider =>

  given clock: Clock = Clock.systemUTC()

  lazy val peerStatistics: org.apache.pekko.actor.typed.ActorRef[PeerStatisticsActor.Command] = classicSystem.spawn(
    Behaviors
      .supervise(
        PeerStatisticsActor(
          peerEventBus,
          // `slotCount * slotDuration` should be set so that it's at least as long
          // as any client of the `PeerStatisticsActor` requires.
          slotDuration = instanceConfig.Network.peer.statSlotDuration,
          slotCount = instanceConfig.Network.peer.statSlotCount
        )
      )
      .onFailure[Throwable](SupervisorStrategy.restart),
    "peer-statistics"
  )

trait PeerManagerActorBuilder:

  self: ActorSystemBuilder & HandshakerBuilder & PeerEventBusBuilder & AuthHandshakerBuilder &
    PeerDiscoveryManagerBuilder & DiscoveryConfigBuilder & StorageBuilder & KnownNodesManagerBuilder &
    PeerStatisticsBuilder & BlacklistBuilder & InstanceConfigProvider =>

  lazy val peerConfiguration: PeerConfiguration = instanceConfig.Network.peer

  lazy val peerManager: TypedActorRef[PeerManagerActor.Command] =
    val ref = classicSystem.spawn(
      PeerManagerActor.behavior(
        peerEventBus,
        peerDiscoveryManagerTyped,
        instanceConfig.Network.peer,
        knownNodesManager,
        peerStatistics,
        PeerManagerActor.peerFactory(
          instanceConfig.Network.peer,
          peerEventBus,
          knownNodesManager,
          handshaker,
          authHandshaker,
          instanceConfig.supportedCapabilities
        ),
        discoveryConfig,
        blacklist
      ),
      "peer-manager"
    )
    // §7c-D2: STOP-AND-ALERT — restart rebuilds the peer table from scratch and severs connections.
    classicSystem.spawn(
      com.chipprbots.ethereum.network.CriticalActorAlerter(ref, "peer-manager"),
      "peer-manager-alerter"
    )
    ref

trait NetworkPeerManagerActorBuilder:
  self: ActorSystemBuilder & PeerManagerActorBuilder & PeerEventBusBuilder & ForkResolverBuilder & StorageBuilder &
    BlockchainBuilder & BlockchainConfigBuilder =>

  lazy val networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command] =
    val ref = classicSystem
      .spawn(
        NetworkPeerManagerActor.behavior(
          peerManager,
          peerEventBus,
          storagesInstance.storages.appStateStorage,
          forkResolverOpt,
          evmCodeStorageOpt = Some(storagesInstance.storages.evmCodeStorage),
          mptStorageOpt = Some(storagesInstance.storages.stateStorage.getReadOnlyStorage),
          blockchainReader = Some(blockchainReader),
          isPoWChain = blockchainConfig.terminalTotalDifficulty.isEmpty
        ),
        "network-peer-manager"
      )
    // §7c-D3: STOP-AND-ALERT — network state cannot be safely reconstructed after a restart.
    classicSystem.spawn(
      com.chipprbots.ethereum.network.CriticalActorAlerter(ref, "network-peer-manager"),
      "network-peer-manager-alerter"
    )
    ref

trait BlockchainHostBuilder:
  self: ActorSystemBuilder & BlockchainBuilder & StorageBuilder & PeerManagerActorBuilder &
    NetworkPeerManagerActorBuilder & PeerEventBusBuilder & PendingTransactionsManagerBuilder =>

  val blockchainHost: org.apache.pekko.actor.typed.ActorRef[BlockchainHostActor.Command] = classicSystem.spawn(
    Behaviors
      .supervise(
        BlockchainHostActor(
          blockchainReader,
          storagesInstance.storages.evmCodeStorage,
          peerConfiguration,
          peerEventBus,
          networkPeerManager,
          pendingTransactionsManagerTyped
        )
      )
      .onFailure[Throwable](SupervisorStrategy.restart),
    "blockchain-host"
  )

trait ServerActorBuilder:

  self: ActorSystemBuilder & NodeStatusBuilder & BlockchainBuilder & PeerManagerActorBuilder & BlacklistBuilder &
    InstanceConfigProvider =>

  lazy val networkConfig = instanceConfig.Network

  lazy val server: org.apache.pekko.actor.typed.ActorRef[ServerActor.Command] =
    classicSystem.spawn(
      Behaviors
        .supervise(ServerActor(nodeStatusHolder, peerManager, blacklist))
        .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
      "server"
    )

trait Web3ServiceBuilder:
  lazy val web3Service = new Web3Service

trait NetServiceBuilder:
  this: PeerManagerActorBuilder & NodeStatusBuilder & BlacklistBuilder & InstanceConfigProvider & ActorSystemBuilder =>

  lazy val netServiceConfig: NetServiceConfig = NetServiceConfig(instanceConfig.config)

  lazy val netService = new NetService(nodeStatusHolder, peerManager, blacklist, netServiceConfig)(
    classicSystem.toTyped.scheduler
  )

trait PendingTransactionsManagerBuilder:
  def pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command]
  // Alias kept for callers that reference the Typed ref by the old name.
  def pendingTransactionsManagerTyped: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
    pendingTransactionsManager
object PendingTransactionsManagerBuilder:
  trait Default extends PendingTransactionsManagerBuilder:
    self: ActorSystemBuilder & PeerManagerActorBuilder & NetworkPeerManagerActorBuilder & PeerEventBusBuilder &
      TxPoolConfigBuilder & BlockchainBuilder & StorageBuilder & EventTopicsBuilder =>

    lazy val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
      classicSystem.spawn(
        Behaviors
          .supervise(
            PendingTransactionsManager(
              txPoolConfig,
              peerManager,
              networkPeerManager,
              peerEventBus,
              pendingTxTopic,
              blockchainReader,
              storagesInstance.storages.stateStorage
            )
          )
          .onFailure[Throwable](
            SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3)
          ),
        "pending-transactions-manager"
      )

trait TransactionHistoryServiceBuilder:
  def transactionHistoryService: TransactionHistoryService
object TransactionHistoryServiceBuilder:
  trait Default extends TransactionHistoryServiceBuilder:
    self: BlockchainBuilder & PendingTransactionsManagerBuilder & TxPoolConfigBuilder & ActorSystemBuilder =>
    lazy val transactionHistoryService =
      new TransactionHistoryService(
        blockchainReader,
        pendingTransactionsManager,
        txPoolConfig.getTransactionFromPoolTimeout,
        classicSystem.toTyped.scheduler
      )

trait FilterManagerBuilder:
  self: ActorSystemBuilder & BlockchainBuilder & StorageBuilder & KeyStoreBuilder & PendingTransactionsManagerBuilder &
    FilterConfigBuilder & TxPoolConfigBuilder & MiningBuilder =>

  lazy val filterManager: org.apache.pekko.actor.typed.ActorRef[FilterManager.Command] =
    classicSystem.spawn(
      Behaviors
        .supervise(
          FilterManager(
            blockchainReader,
            mining.blockGenerator,
            keyStore,
            pendingTransactionsManager,
            filterConfig,
            txPoolConfig
          )
        )
        .onFailure[Throwable](
          SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3)
        ),
      "filter-manager"
    )

trait DebugServiceBuilder:
  self: NetworkPeerManagerActorBuilder & PeerManagerActorBuilder & ActorSystemBuilder =>

  lazy val debugService = new DebugService(peerManager, networkPeerManager)(classicSystem.toTyped.scheduler)

trait EthProofServiceBuilder:
  self: StorageBuilder & BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder =>

  lazy val ethProofService: ProofService = new EthProofService(
    blockchain,
    blockchainReader,
    mining.blockGenerator,
    blockchainConfig.ethCompatibleStorage
  )

trait EthInfoServiceBuilder:
  self: StorageBuilder & BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder & StxLedgerBuilder &
    KeyStoreBuilder & SyncControllerBuilder & AsyncConfigBuilder & InstanceConfigProvider & ActorSystemBuilder =>

  lazy val ethInfoService = new EthInfoService(
    blockchain,
    blockchainReader,
    blockchainConfig,
    mining,
    stxLedger,
    keyStore,
    syncController,
    Capability.best(instanceConfig.supportedCapabilities),
    asyncConfig.askTimeout,
    classicSystem.toTyped.scheduler
  )

trait EthSimulateServiceBuilder:
  self: StorageBuilder & BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder =>

  lazy val ethSimulateService = new com.chipprbots.ethereum.jsonrpc.EthSimulateService(
    blockchain,
    blockchainReader,
    storagesInstance.storages.evmCodeStorage,
    mining.blockPreparator,
    mining,
    blockchainConfig
  )

trait EthMiningServiceBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder & JSONRpcConfigBuilder & OmmersPoolBuilder &
    SyncControllerBuilder & PendingTransactionsManagerBuilder & TxPoolConfigBuilder & ActorSystemBuilder =>

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
    classicSystem
  )
trait EthTxServiceBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & PendingTransactionsManagerBuilder & MiningBuilder &
    TxPoolConfigBuilder & StorageBuilder & ActorSystemBuilder =>

  lazy val ethTxService = new EthTxService(
    blockchain,
    blockchainReader,
    mining,
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    storagesInstance.storages.transactionMappingStorage,
    classicSystem.toTyped.scheduler
  )

trait EthBlocksServiceBuilder:
  self: BlockchainBuilder & MiningBuilder & BlockQueueBuilder & StorageBuilder =>

  /** Override in subtraits that have access to ForkChoiceManager (e.g. EngineApiBuilder) */
  def forkChoiceManagerForRpc: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] = None

  lazy val ethBlocksService =
    new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue,
      forkChoiceManagerForRpc,
      storagesInstance.pruningMode
    )

trait EthUserServiceBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder & StorageBuilder =>

  lazy val ethUserService = new EthUserService(
    blockchain,
    blockchainReader,
    mining,
    storagesInstance.storages.evmCodeStorage,
    this
  )

trait EthFilterServiceBuilder:
  self: FilterManagerBuilder & FilterConfigBuilder & BlockchainBuilder & ActorSystemBuilder =>

  lazy val ethFilterService = new EthFilterService(
    filterManager,
    filterConfig,
    blockchainReader
  )(classicSystem)

trait PersonalServiceBuilder:
  self: KeyStoreBuilder & BlockchainBuilder & BlockchainConfigBuilder & PendingTransactionsManagerBuilder &
    StorageBuilder & TxPoolConfigBuilder & EthTxServiceBuilder & ActorSystemBuilder =>

  lazy val personalService: PersonalServiceAPI = new PersonalService(
    keyStore,
    blockchainReader,
    pendingTransactionsManager,
    txPoolConfig,
    this,
    ethTxService,
    classicSystem.toTyped.scheduler
  )

trait QaServiceBuilder:
  self: MiningBuilder =>

  lazy val qaService =
    new QAService(
      mining
    )

trait SyncControllerRefBuilder:
  def syncController: org.apache.pekko.actor.typed.ActorRef[SyncController.Command]

trait FukuiiServiceBuilder:
  self: TransactionHistoryServiceBuilder & JSONRpcConfigBuilder & SyncControllerRefBuilder & ActorSystemBuilder =>

  lazy val fukuiiService = new FukuiiService(
    transactionHistoryService,
    jsonRpcConfig,
    syncController,
    classicSystem.toTyped.scheduler
  )

trait McpServiceBuilder:
  self: PeerManagerActorBuilder & SyncControllerBuilder & ActorSystemBuilder & BlockchainBuilder &
    BlockchainConfigBuilder & NodeStatusBuilder & StorageBuilder =>

  lazy val mcpService = new McpService(
    peerManager,
    syncController,
    blockchainReader,
    blockchainConfig,
    nodeStatusHolder,
    storagesInstance.storages.transactionMappingStorage
  )(classicSystem.dispatcher, classicSystem.toTyped.scheduler)

trait KeyStoreBuilder:
  self: SecureRandomBuilder & KeyStoreConfigBuilder =>
  lazy val keyStore: KeyStore = new KeyStoreImpl(keyStoreConfig, secureRandom)

trait ApisBuilder extends ApisBase:
  object Apis:
    val Eth = "eth"
    val Web3 = "web3"
    val Net = "net"
    val Personal = "personal"
    val Fukuii = "fukuii"
    val Mcp = "mcp"
    val Debug = "debug"
    val Rpc = "rpc"
    val Test = "test"
    // execution-apis `testing_*` block-production namespace. Deliberately NOT in `available`'s
    // shipped defaults beyond being selectable: the spec says it "MUST NOT be exposed on
    // public-facing RPC APIs" and "is strongly recommended to be disabled by default".
    // Opt in with -Dfukuii.network.rpc.apis=...,testing
    val Testing = "testing"
    val Qa = "qa"
    val Admin = "admin"
    val TxPool = "txpool"
    val Trace = "trace"
    val Subscribe = "subscribe"

  import Apis.*
  override def available: List[String] =
    List(Eth, Web3, Net, Personal, Fukuii, Mcp, Debug, Test, Testing, Qa, Admin, TxPool, Trace, Subscribe)

trait AdminServiceBuilder:
  this: PeerManagerActorBuilder & NodeStatusBuilder & BlockchainBuilder & BlockchainConfigBuilder &
    InstanceConfigProvider & ActorSystemBuilder =>

  lazy val blockedIPRegistry: BlockedIPRegistry = new BlockedIPRegistry(Set.empty)

  lazy val adminService: AdminService = new AdminService(
    nodeStatusHolder,
    peerManager,
    blockchainReader,
    blockchainConfig,
    instanceConfig.config.getConfig("network.rpc.net").getDuration("peer-manager-timeout").toMillis.millis,
    instanceConfig.config.getString("datadir"),
    blockedIPRegistry
  )(classicSystem.toTyped.scheduler)

trait TxPoolServiceBuilder:
  this: PendingTransactionsManagerBuilder & TxPoolConfigBuilder & ActorSystemBuilder =>

  lazy val txPoolService: TxPoolService = new TxPoolService(
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    txPoolConfig,
    classicSystem.toTyped.scheduler
  )

trait DebugTracingServiceBuilder:
  this: BlockchainBuilder & StxLedgerBuilder & StorageBuilder & MiningBuilder =>

  lazy val debugTracingService: com.chipprbots.ethereum.jsonrpc.DebugTracingService =
    new com.chipprbots.ethereum.jsonrpc.DebugTracingService(
      blockchain,
      blockchainReader,
      mining,
      stxLedger,
      storagesInstance.storages.transactionMappingStorage
    )

trait TraceServiceBuilder:
  this: BlockchainBuilder & StxLedgerBuilder & StorageBuilder & MiningBuilder =>

  lazy val traceService: com.chipprbots.ethereum.jsonrpc.TraceService =
    new com.chipprbots.ethereum.jsonrpc.TraceService(
      blockchain,
      blockchainReader,
      mining,
      stxLedger,
      storagesInstance.storages.transactionMappingStorage
    )

trait JSONRpcConfigBuilder:
  self: ApisBuilder & InstanceConfigProvider =>

  lazy val availableApis: List[String] = available
  lazy val jsonRpcConfig: JsonRpcConfig = JsonRpcConfig(instanceConfig.config, availableApis)

trait JSONRpcControllerBuilder:
  this: Web3ServiceBuilder & EthInfoServiceBuilder & EthProofServiceBuilder & EthSimulateServiceBuilder &
    EthMiningServiceBuilder & EthBlocksServiceBuilder & EthTxServiceBuilder & EthUserServiceBuilder &
    EthFilterServiceBuilder & NetServiceBuilder & PersonalServiceBuilder & DebugServiceBuilder & JSONRpcConfigBuilder &
    QaServiceBuilder & FukuiiServiceBuilder & McpServiceBuilder & AdminServiceBuilder & TxPoolServiceBuilder &
    DebugTracingServiceBuilder & TraceServiceBuilder & ActorSystemBuilder =>

  protected def testService: Option[TestService] = None

  /** execution-apis `testing_*` namespace. None by default — the spec requires it be off unless explicitly enabled.
    * Overridden in [[Node]], the only place where EngineApiBuilder's engineApiService and forkChoiceManager are both in
    * scope.
    */
  protected def testingService: Option[TestingService] = None

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
      testingService,
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
      jsonRpcConfig,
      classicSystem
    )

trait JSONRpcHealthcheckerBuilder:
  this: NetServiceBuilder & EthBlocksServiceBuilder & JSONRpcConfigBuilder & AsyncConfigBuilder &
    SyncControllerBuilder & ActorSystemBuilder =>
  lazy val jsonRpcHealthChecker: JsonRpcHealthChecker =
    new NodeJsonRpcHealthChecker(
      netService,
      ethBlocksService,
      syncController,
      jsonRpcConfig.healthConfig,
      asyncConfig,
      classicSystem.toTyped.scheduler
    )

trait EngineApiBuilder extends Logger:
  self: ActorSystemBuilder & BlockchainBuilder & BlockchainConfigBuilder & ConsensusBuilder & StorageBuilder &
    MiningBuilder & PendingTransactionsManagerBuilder & InstanceConfigProvider & JSONRpcControllerBuilder =>

  import com.chipprbots.ethereum.consensus.engine.*

  lazy val engineApiConfig: EngineApiHttpServer.Config =
    val engineConf = scala.util.Try(instanceConfig.config.getConfig("network.engine-api")).toOption
    EngineApiHttpServer.Config(
      enabled = engineConf.flatMap(c => scala.util.Try(c.getBoolean("enabled")).toOption).getOrElse(false),
      interface = engineConf.flatMap(c => scala.util.Try(c.getString("interface")).toOption).getOrElse("localhost"),
      port = engineConf.flatMap(c => scala.util.Try(c.getInt("port")).toOption).getOrElse(8551),
      jwtSecretPath = engineConf.flatMap(c => scala.util.Try(c.getString("jwt-secret-path")).toOption)
    )

  lazy val forkChoiceManager: ForkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

  lazy val engineApiService: EngineApiService =
    given typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler
    new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      forkChoiceManager,
      Some(pendingTransactionsManagerTyped)
    )(blockchainConfig, typedScheduler)

  lazy val engineApiController: EngineApiController = new EngineApiController(engineApiService, Some(jsonRpcController))

  /** Bind the p2p import path's invalid-chain channel to this node's Engine API registry. Called once, from
    * `StdNode.start()`, before sync begins.
    *
    * Gated on `engineApiConfig.enabled`, NOT on the existence of an `EngineApiService`: `Node` constructs one
    * unconditionally to back the `testing_*` JSON-RPC namespace, so on ETC/Mordor/Gorgoroth an instance exists but the
    * server is off. Binding there would let the import path write INVALID verdicts into a registry nothing reads, on a
    * chain family this feature has no business touching. With the config gate, ETC leaves the holder unbound and
    * `BlockImporter`/`ConsensusImpl` behave exactly as before this feature existed.
    */
  def bindInvalidChainReporter(): Unit =
    if engineApiConfig.enabled then
      invalidChainReporter.bind(engineApiService.invalidChainReporter)
      log.info("Engine API enabled: p2p import path can now report consensus-invalid chains to the CL")
    else log.debug("Engine API disabled: p2p import path invalid-chain reporting stays inert")

  /** Let `ConsensusImpl.importToNewBranch` see the CL's designated head. The mirror of [[bindInvalidChainReporter]],
    * and deliberately adjacent to it so the two channels between the Engine API and the p2p import path are reviewed
    * together.
    *
    * DOUBLE GATE, and both conjuncts are load-bearing for a different reason. `engineApiConfig.enabled` is the same
    * gate the reporter uses and is what keeps a node with the Engine API switched off on its pre-existing behaviour.
    * `terminalTotalDifficulty.isDefined` is the PoS predicate — the same one behind `SyncController.clPivotEnabled` —
    * and is what makes this structurally unreachable on ETC/Mordor/Gorgoroth even if someone later enables an Engine
    * API on a PoW chain for the `testing_*` namespace. Neither alone would be enough for both properties.
    */
  def bindDesignatedHead(): Unit =
    if engineApiConfig.enabled && blockchainConfig.terminalTotalDifficulty.isDefined then
      designatedHead.bind(DesignatedHead(() => forkChoiceManager.getHeadBlockHash))
      log.info("Post-merge chain with Engine API enabled: p2p branch resolution now follows the CL's designated head")
    else
      log.debug(
        "Not a post-merge chain with a live Engine API: p2p branch resolution keeps chain-weight fork choice"
      )

  lazy val maybeEngineApiServer: Option[EngineApiHttpServer] =
    if engineApiConfig.enabled then
      val jwtAuth = engineApiConfig.jwtSecretPath match
        case Some(path) => JwtAuthenticator.fromFile(path)
        case None       => JwtAuthenticator.generateRandom()
      Some(new EngineApiHttpServer(engineApiController, jwtAuth, engineApiConfig))
    else None

trait GraphQLServiceBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder & StorageBuilder & EthBlocksServiceBuilder &
    EthTxServiceBuilder & EthInfoServiceBuilder & EthUserServiceBuilder & EthFilterServiceBuilder &
    ActorSystemBuilder =>

  private lazy val graphQLConfig: com.chipprbots.ethereum.utils.GraphQLConfig =
    com.chipprbots.ethereum.utils.GraphQLConfig(com.chipprbots.ethereum.utils.Config.config)

  lazy val maybeGraphQLService: Option[com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService] =
    if !graphQLConfig.enabled then None
    else
      given ec: scala.concurrent.ExecutionContext = classicSystem.dispatcher
      given runtime: cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
      val ctx = com.chipprbots.ethereum.jsonrpc.graphql.GraphQLContext(
        blockchain = blockchain,
        blockchainReader = blockchainReader,
        mining = mining,
        evmCodeStorage = storagesInstance.storages.evmCodeStorage,
        blockchainConfig = blockchainConfig,
        ethBlocksService = ethBlocksService,
        ethTxService = ethTxService,
        ethInfoService = ethInfoService,
        ethUserService = ethUserService,
        ethFilterService = ethFilterService
      )
      Some(
        new com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService(
          ctx,
          maxQueryDepth = graphQLConfig.maxQueryDepth,
          executionTimeout = graphQLConfig.executionTimeout
        )
      )

trait JSONRpcHttpServerBuilder:
  self: ActorSystemBuilder & BlockchainBuilder & JSONRpcControllerBuilder & JSONRpcHealthcheckerBuilder &
    SecureRandomBuilder & JSONRpcConfigBuilder & SSLContextBuilder & GraphQLServiceBuilder =>

  lazy val maybeJsonRpcHttpServer: Either[String, JsonRpcHttpServer] =
    given org.apache.pekko.actor.ActorSystem = classicSystem
    JsonRpcHttpServer(
      jsonRpcController,
      jsonRpcHealthChecker,
      jsonRpcConfig.httpServerConfig,
      () => sslContext("fukuii.network.rpc.http"),
      maybeGraphQLService
    )

trait JSONRpcIpcServerBuilder:
  self: ActorSystemBuilder & JSONRpcControllerBuilder & JSONRpcConfigBuilder =>

  lazy val jsonRpcIpcServer = new JsonRpcIpcServer(jsonRpcController, jsonRpcConfig.ipcServerConfig)

trait SubscriptionManagerBuilder:
  self: ActorSystemBuilder & BlockchainBuilder & EventTopicsBuilder =>

  lazy val subscriptionManager: org.apache.pekko.actor.typed.ActorRef[SubscriptionManager.Command] =
    val ref = classicSystem.spawn(
      SubscriptionManager(blockchainReader, pendingTxTopic, blockTopic),
      "subscription-manager"
    )
    // §7c-D6: STOP-AND-ALERT — restart drops all active eth_subscribe subscriptions.
    classicSystem.spawn(
      com.chipprbots.ethereum.network.CriticalActorAlerter(ref, "subscription-manager"),
      "subscription-manager-alerter"
    )
    ref

trait JSONRpcWsServerBuilder:
  self: ActorSystemBuilder & JSONRpcControllerBuilder & JSONRpcConfigBuilder & SubscriptionManagerBuilder =>

  lazy val jsonRpcWsServer: com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer =
    new com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer(
      jsonRpcController,
      subscriptionManager,
      jsonRpcConfig.wsServerConfig
    )(classicSystem)

trait OmmersPoolBuilder:
  self: ActorSystemBuilder & BlockchainBuilder & MiningConfigBuilder =>

  lazy val ommersPoolSize: Int = 30
  lazy val ommersPool: org.apache.pekko.actor.typed.ActorRef[OmmersPool.Command] =
    classicSystem.spawn(
      Behaviors
        .supervise(OmmersPool(blockchainReader, ommersPoolSize))
        .onFailure[Throwable](SupervisorStrategy.restart),
      "ommers-pool"
    )

trait VmBuilder:
  self: ActorSystemBuilder & BlockchainConfigBuilder & VmConfigBuilder =>

  lazy val vm: VMImpl = VmSetup.vm(vmConfig)

trait StxLedgerBuilder:
  self: BlockchainConfigBuilder & BlockchainBuilder & StorageBuilder & SyncConfigBuilder & MiningBuilder &
    ActorSystemBuilder =>

  lazy val stxLedger: StxLedger =
    new StxLedger(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      this
    )

trait SyncControllerBuilder extends SyncControllerRefBuilder:

  self: ActorSystemBuilder & ServerActorBuilder & BlockchainBuilder & BlockchainConfigBuilder & ConsensusBuilder &
    NodeStatusBuilder & StorageBuilder & StxLedgerBuilder & PeerEventBusBuilder & PendingTransactionsManagerBuilder &
    OmmersPoolBuilder & NetworkPeerManagerActorBuilder & SyncConfigBuilder & ShutdownHookBuilder & MiningBuilder &
    BlacklistBuilder & MESSBuilder & EventTopicsBuilder =>

  /** Override in concrete builders that also mix in [[EngineApiBuilder]] to enable CL-driven SNAP pivot selection.
    * Defaults to `None` for setups without an Engine API (e.g. ETC mainnet pre-merge wiring). Closes #1207.
    */
  def forkChoiceManagerForSync: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] = None

  // SyncController is Pekko Typed (Group ROOT, narrowed) — a `Behavior[Command]`. Spawned via
  // classicSystem.spawn so it lives in the Classic system's guardian tree while exposing a fully-Typed
  // ActorRef[Command]. All callers now hold a TypedActorRef[SyncController.Command] (OQ-5 kill, 8k-G).
  lazy val syncController: org.apache.pekko.actor.typed.ActorRef[SyncController.Command] =
    val ref = classicSystem
      .spawn(
        SyncController(
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
          pendingTransactionsManagerTyped,
          blockTopic,
          ommersPool,
          networkPeerManager,
          blacklist,
          syncConfig,
          this,
          messConfigOpt,
          forkChoiceManagerForSync
        ),
        "sync-controller"
      )
    // §7c-D5: STOP-AND-ALERT — restart loses chain-sync progress and may trigger a re-org.
    classicSystem.spawn(
      com.chipprbots.ethereum.network.CriticalActorAlerter(ref, "sync-controller"),
      "sync-controller-alerter"
    )
    ref

trait PortForwardingBuilder:
  self: DiscoveryConfigBuilder & InstanceConfigProvider =>

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

  def startPortForwarding(): Future[Unit] =
    // Only allocate the resource if it hasn't been started yet
    // Use a placeholder to ensure only one thread performs the allocation
    val placeholder = IO.unit
    if portForwardingRelease.compareAndSet(None, Some(placeholder)) then
      // We won the race - allocate the resource and store the cleanup function
      portForwarding
        .flatMap { cleanup =>
          IO {
            portForwardingRelease.set(Some(cleanup))
            ()
          }
        }
        .unsafeToFuture()(ioRuntime)
    else
      // Resource was already started by another thread
      Future.unit

  def stopPortForwarding(): Future[Unit] =
    portForwardingRelease.getAndSet(None).fold(Future.unit)(_.unsafeToFuture()(ioRuntime))

trait ShutdownHookBuilder:
  self: Logger & InstanceConfigProvider =>
  def shutdown: () => Unit = () => {
    /* No default behaviour during shutdown. */
  }

  lazy val shutdownTimeoutDuration: Duration = instanceConfig.shutdownTimeout

  Runtime.getRuntime.addShutdownHook(
    new Thread():
      override def run(): Unit =
        shutdown()
  )

  def shutdownOnError[A](f: => A): A =
    Try(f) match
      case Success(v) => v
      case Failure(t) =>
        log.error(t.getMessage, t)
        shutdown()
        throw t

object ShutdownHookBuilder extends ShutdownHookBuilder with Logger with InstanceConfigProvider:
  override def instanceConfig: InstanceConfig = Config

trait GenesisDataLoaderBuilder:
  self: BlockchainBuilder & StorageBuilder =>

  lazy val genesisDataLoader =
    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    )

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
    with TypedActorSystemProvider
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
    with GraphQLServiceBuilder
    with JSONRpcHttpServerBuilder
    with JSONRpcIpcServerBuilder
    with EventTopicsBuilder
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
    with BlacklistBuilder:
  // Resolve conflicting ioRuntime from PeerDiscoveryManagerBuilder and PortForwardingBuilder
  implicit override lazy val ioRuntime: IORuntime = IORuntime.global

  // Wire ForkChoiceManager to RPC services for "safe"/"finalized" block tag resolution
  override def forkChoiceManagerForRpc: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] =
    Some(forkChoiceManager)

  // Wire ForkChoiceManager to the sync layer so SNAP can pivot off CL-driven heads on
  // post-merge chains. Closes #1207.
  override def forkChoiceManagerForSync: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] =
    Some(forkChoiceManager)

  // execution-apis `testing_*` block-production namespace. Constructed here because this is the
  // only assembly point where EngineApiBuilder (engineApiService, forkChoiceManager) and the
  // JSON-RPC controller meet. Constructing it does NOT expose it: JsonRpcBaseController only
  // dispatches namespaces present in `network.rpc.apis`, and `testing` is in none of the shipped
  // configs.
  override protected lazy val testingService: Option[TestingService] = Some(
    new TestingService(
      engineApiService,
      blockchainReader,
      blockchainWriter,
      forkChoiceManager,
      Some(pendingTransactionsManagerTyped),
      Some(blockTopic),
      // go-ethereum's --miner.gaslimit equivalent. hive's rpc-compat sets HIVE_TARGET_GAS_LIMIT
      // to 60000000 so every client converges to the same next-block gas limit; fukuii's default
      // mining.gas-limit-target is already 60000000.
      miningConfig.gasLimitTarget
    )(blockchainConfig)
  )
