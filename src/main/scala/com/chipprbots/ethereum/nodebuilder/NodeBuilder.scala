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
import org.bouncycastle.crypto.params.ECPublicKeyParameters

import com.chipprbots.ethereum.blockchain.data.ChainImporter
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.BlockchainHostActor
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.OperatorBlockingBlacklist
import com.chipprbots.ethereum.blockchain.sync.ScoringBlacklist
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
import com.chipprbots.ethereum.network.discovery.DiscoveryRuntime
import com.chipprbots.ethereum.network.discovery.DiscoveryServiceBuilder
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.handshaker.NetworkHandshaker
import com.chipprbots.ethereum.network.handshaker.NetworkHandshakerConfiguration
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.rlpx.AuthHandshaker
import com.chipprbots.ethereum.ommers.OmmersPool
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
    val built = buildStorages()
    // The layout guard (spec 018 data-model 5.3), here so that whoever takes the storages has passed it: before the
    // state is reconciled, before a repair, before the genesis is loaded, and nothing was written to get here.
    com.chipprbots.ethereum.db.storage.StateLayoutGuard.checkOrClose(
      instanceConfig.statePathLive,
      built.storages.appStateStorage,
      built.storages.blockNumberMappingStorage
    )(built.dataSource.close())
    built

  private def buildStorages(): DataSourceComponent & StoragesComponent & PruningModeComponent =
    instanceConfig.Db.dataSource match
      case "rocksdb" =>
        new RocksDbDataSourceComponent
          with PruningConfigBuilder
          with Storages.DefaultStorages
          with InstanceConfigProvider:
          override def instanceConfig: InstanceConfig = self.instanceConfig
          // The guard as this instance's configuration has it, the value its capabilities are made from: the default
          // reads the process-wide configuration, which another instance of a multi-instance run does not share.
          override protected def statePathLive: Boolean = self.instanceConfig.statePathLive
          override protected def stateMayBeDestroyed: Boolean = self.instanceConfig.testmode
          override protected def stateConfig: com.chipprbots.ethereum.db.storage.pathstate.StateConfig =
            com.chipprbots.ethereum.db.storage.pathstate.StateConfig(self.instanceConfig.config)

/** The P2P node-ID allowlist (spec 030 FR-050): built from `network.peer.node-allowlist`, the static nodes and the
  * bootnodes. A static node or bootnode that is not listed is a startup error (FR-053), thrown as an
  * `IllegalStateException` when this is first forced; `BaseNode.start` forces it before anything binds.
  */
trait NodeAllowlistBuilder:
  self: InstanceConfigProvider & NodeKeyBuilder & BlockchainConfigBuilder =>

  lazy val nodeAllowlistStore: Option[NodeAllowlistStore] =
    val path = "network.peer.node-allowlist"
    val section = if instanceConfig.config.hasPath(path) then Some(instanceConfig.config.getConfig(path)) else None
    val ownId = Some(ByteString(nodeKey.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId))
    NodeAllowlistStore.fromConfig(
      section,
      StaticNodesLoader.load(instanceConfig.config.getString("datadir")),
      blockchainConfig.bootstrapNodes.toSeq,
      ownId
    ) match
      case Right(store) => store
      case Left(why)    => throw new IllegalStateException(s"Refusing to start: $why")

  /** Permits everything when the allowlist is off. */
  lazy val nodeAllowlist: NodeAllowlist = nodeAllowlistStore.map(_.allowlist).getOrElse(NodeAllowlist.Disabled)

  /** How often the allowlist file is checked for changes. */
  lazy val nodeAllowlistReloadInterval: FiniteDuration =
    val path = "network.peer.node-allowlist.reload-interval"
    if instanceConfig.config.hasPath(path) then instanceConfig.config.getDuration(path).toMillis.millis
    else NodeAllowlistStore.DefaultReloadInterval

trait DiscoveryConfigBuilder extends BlockchainConfigBuilder with StorageBuilder:
  self: InstanceConfigProvider & ActorSystemBuilder =>
  // Config load does no DNS (H9): it keeps the EIP-1459 domain names, and PeerDiscoveryManager walks them in the
  // background once the node is up.
  lazy val discoveryConfig: DiscoveryConfig =
    DiscoveryConfig(
      instanceConfig.config,
      blockchainConfig.bootstrapNodes,
      blockchainConfig.dnsDiscoveryDomains,
      // Read from config, not from the allowlist built at start, so this builder needs no more than it did.
      allowlistEnabled = NodeAllowlistStore.isEnabled(instanceConfig.config)
    )

  // Rejects ENRs with `eth` key fork IDs that don't match the local chain — stops cross-network peers (BSC, mainnet,
  // etc.) from burning outbound dial slots when shared DNS trees include mis-tagged entries. See PR #1249. Mirrors
  // ForkIdTag (discv4). It reads genesis and the head lazily, per ENR, so the DNS walk (which starts after genesis is
  // loaded) filters against the real chain.
  lazy val dnsEnrForkIdFilter: com.chipprbots.ethereum.network.discovery.DnsDiscovery.EnrForkIdFilter =
    val reader = com.chipprbots.ethereum.domain.BlockchainReader(storagesInstance.storages)
    new com.chipprbots.ethereum.network.discovery.DnsDiscovery.EnrForkIdFilter(
      genesisHash = () => reader.genesisHeader.hash.value,
      genesisTimestamp = () => reader.genesisHeader.unixTimestamp.toLong,
      blockchainConfig = blockchainConfig,
      currentBestBlock = () => reader.getBestBlockNumber,
      // Real head timestamp, not a hardcoded 0 — see ForkIdTag's identical comment (WI-13 / issue #1429).
      currentBestBlockTimestamp = () =>
        reader
          .getBlockHeaderByNumber(reader.getBestBlockNumber)
          .map(_.unixTimestamp.toLong)
          .getOrElse(reader.genesisHeader.unixTimestamp.toLong)
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
    BlockchainBuilder & InstanceConfigProvider & BlacklistBuilder =>

  implicit lazy val ioRuntime: IORuntime = IORuntime.global

  // One runtime for the node's life: the supervised PeerDiscoveryManager reuses it across restarts. Only shut it down if
  // something actually created it.
  private lazy val discoveryIoRuntimeCreated = new java.util.concurrent.atomic.AtomicBoolean(false)

  /** Discovery's own bounded runtime (task #52): discv4 ECDSA must not compete with block import and the sync fetchers
    * for `IORuntime.global`. Passed explicitly — the implicit `ioRuntime` above stays global for everyone else.
    */
  lazy val discoveryIoRuntime: IORuntime =
    discoveryIoRuntimeCreated.set(true)
    DiscoveryRuntime(discoveryConfig.computeThreads)

  /** Shut the discovery runtime down after PeerDiscoveryManager has released the discovery service — signalled by the
    * service resource's own finalizer setting the discovery status back to NotListening. Call after sending `Stop`.
    */
  def shutdownDiscoveryIoRuntime(timeout: scala.concurrent.duration.FiniteDuration): Unit =
    if discoveryIoRuntimeCreated.get then
      val released = DiscoveryRuntime.shutdownAfterRelease(
        discoveryIoRuntime,
        () => nodeStatusHolder.get.discoveryStatus == com.chipprbots.ethereum.utils.ServerStatus.NotListening,
        timeout
      )
      if !released then log.warn(s"Discovery did not release within $timeout; shut its runtime down anyway")

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
              isBlocked = address => blockedIPRegistry.isBlocked(address),
              forkIdTag = Some(
                new com.chipprbots.ethereum.network.discovery.ForkIdTag(
                  genesisHash = () => blockchainReader.genesisHeader.hash.value,
                  genesisTimestamp = () => blockchainReader.genesisHeader.unixTimestamp.toLong,
                  blockchainConfig = blockchainConfig,
                  currentBestBlock = () => blockchainReader.getBestBlockNumber,
                  // Real head timestamp, not a hardcoded 0 — see ForkIdTag's own comment (WI-13 / issue #1429).
                  currentBestBlockTimestamp = () =>
                    blockchainReader
                      .getBlockHeaderByNumber(blockchainReader.getBestBlockNumber)
                      .map(_.unixTimestamp.toLong)
                      .getOrElse(blockchainReader.genesisHeader.unixTimestamp.toLong)
                )
              )
            )(using discoveryIoRuntime),
            randomNodeBufferSize = instanceConfig.Network.peer.maxOutgoingPeers,
            dnsForkIdFilter = Some(dnsEnrForkIdFilter)
          )(using discoveryIoRuntime)
        )
        .onFailure[Throwable](SupervisorStrategy.restart),
      "peer-discovery-manager-typed"
    )

trait BlacklistBuilder:
  self: InstanceConfigProvider =>

  private val blacklistSize: Int = 1000

  /** Spec 027 slice 4a: the one peer scorer of the node, exporting its scores to [[NetworkMetrics]]. It observes: no
    * dial, peer selection, blacklist or disconnect decision reads it.
    */
  lazy val peerScoringManager: PeerScoringManager =
    new PeerScoringManager(exporter = NetworkMetrics.publishPeerScores)

  /** The node-wide blacklist; the scorer hears of every blacklisting of a peer (not of an address). */
  lazy val blacklist: Blacklist =
    new OperatorBlockingBlacklist(
      new ScoringBlacklist(CacheBasedBlacklist.empty(blacklistSize), peerScoringManager),
      blockedIPRegistry
    )

  /** The addresses the operator blocked: `network.peer.blocked-ips` at start, then `admin_blockIP` / the TUI's peer
    * block until the node restarts. The blacklist above refuses them, and discovery drops their packets (B-288). An
    * entry that is not an IP address refuses the start.
    */
  lazy val blockedIPRegistry: BlockedIPRegistry =
    BlockedIPRegistry.fromConfig(instanceConfig.config) match
      case Right(registry) => registry
      case Left(why)       => throw new IllegalStateException(s"Refusing to start: $why")

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
  self: BlockchainBuilder & BlockQueueBuilder & MiningBuilder & ActorSystemBuilder & StorageBuilder &
    BlockchainConfigBuilder & InstanceConfigProvider & SyncConfigBuilder =>

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

  /** Late-bound hand-off of the consensus layer's branch from `ConsensusImpl` to the fork choice's recovery (spec 018
    * C5a, B-297). Same shape and reason as `designatedHead`; bound by `EngineApiBuilder.bindBranchHandOff()` only on a
    * post-merge chain with the Engine API on and a recovery built (the unified layout). Unbound everywhere else.
    */
  lazy val branchHandOff: com.chipprbots.ethereum.consensus.engine.BranchHandOff.LateBound =
    new com.chipprbots.ethereum.consensus.engine.BranchHandOff.LateBound

  lazy val consensus: Consensus =
    new ConsensusImpl(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      Some(invalidChainReporter),
      Some(designatedHead),
      blockchain,
      Some(branchHandOff),
      // what of a reorganisation is told is what the block queue keeps behind the head (B-345)
      oldBranchToldAtMost = syncConfig.maxQueuedBlockNumberBehind
    )

  lazy val chainImporter: ChainImporter =
    new ChainImporter(
      blockchainReader,
      blockExecution,
      Some((block: Block, config: BlockchainConfig) =>
        consensusAdapter.evaluateBranchBlock(block)(using IORuntime.global, config)
      ),
      // on the unified layout, a block after the merge that is not built on the head is adopted on layers (B-220)
      Some(ChainImporter.adoptingOnLayers(blockchainReader, blockchainWriter, blockExecution, blockchain))
    )

  /** The count of what the node could not follow, on the unified layout (spec 018 C5a part 6): 64 refused fork choices
    * in a row for a fault of this node, and no less than 64 slots; at once for a chain that cannot be reached. '''It
    * says only''': an ERROR line and a count. The handler that gives the state up is part 7.
    */
  lazy val escalationWatch: Option[com.chipprbots.ethereum.blockchain.sync.EscalationWatch] =
    storagesInstance.storages.pathStateStorage.map { _ =>
      import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger
      import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
      import com.chipprbots.ethereum.db.storage.pathstate.StateMetrics
      val refusals =
        com.chipprbots.ethereum.db.storage.pathstate.StateConfig(instanceConfig.config).recovery.escalationRefusals
      new EscalationWatch(
        new EscalationTrigger(refusals, refusals * EscalationWatch.SlotMillis),
        () => System.currentTimeMillis(),
        StateMetrics.escalationTriggered,
        (kind, why) =>
          escalationLog.error(
            s"[STATE-ESCALATION] This node cannot follow the chain with the state it has (${kind.text}): $why. " +
              "Whether its state is given up for a sync from scratch is the sync controller's to say, while it runs " +
              "regular sync; a node whose sync controller was not started gives no state up."
          ),
        // handed on, never acted on here: it is told on the thread that fed the watch, inside the watch's monitor
        request => escalationRequests.get().apply(request)
      )
    }

  /** `state.recovery.escalation-cooldown`: the shortest time between two escalations. */
  lazy val escalationCooldownMillis: Long =
    com.chipprbots.ethereum.db.storage.pathstate.StateConfig(instanceConfig.config).recovery.escalationCooldown.toMillis

  /** Where the watch's verdicts go (spec 018 C5a part 7b): to the sync controller, once there is one. Late bound: the
    * watch is built with the consensus, the controller after it. Until it is bound a verdict is said and counted, and
    * no more.
    */
  lazy val escalationRequests: java.util.concurrent.atomic.AtomicReference[
    com.chipprbots.ethereum.blockchain.sync.EscalationWatch.Request => Unit
  ] = new java.util.concurrent.atomic.AtomicReference(_ => ())

  private lazy val escalationLog =
    org.slf4j.LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.sync.EscalationWatch")

  /** What the import of proof-of-work blocks tells the watch (spec 018 C5a part 6b). */
  lazy val powEscalationFeed: Option[com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed] =
    escalationWatch.map { watch =>
      new com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed(
        watch,
        blockchainReader.getChainWeightByHash,
        // exact only: a head weighed from an estimate is outweighed by any block below it (forge)
        () =>
          blockchainReader.getBestBlockHeader
            .map(com.chipprbots.ethereum.blockchain.sync.HeadChainWeight.classify(blockchainReader, _))
            .collect { case com.chipprbots.ethereum.blockchain.sync.HeadChainWeight.Status.Exact(weight) => weight },
        hash => blockchainReader.getBlockHeaderByHash(com.chipprbots.ethereum.domain.BlockHash(hash)),
        header => blockchainReader.getCanonicalHashByNumber(header.number.value).contains(header.hash),
        // where the chain has MESS: a branch it would refuse is no chain to follow (forge, B-306)
        Option.when(blockchainConfig.messConfig.enabled)(
          com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed.Mess(
            blockchainConfig.messConfig.isActiveAtBlock,
            () => blockchainReader.getBestBlockHeader,
            com.chipprbots.ethereum.consensus.mess.ArtificialFinality.shouldRejectReorg
          )
        ),
        left => escalationLog.debug("[STATE-ESCALATION] {}", left),
        // a block whose header is not stored is the node's own when the hash index names it (B-383)
        blockchainReader.getCanonicalHashByNumber,
        // the head `headWeight` weighs: a block whose weight is not stored is weighed from it (B-385)
        () => blockchainReader.getBestBlockHeader
      )
    }

  /** What stops the node when the store failed during an import (B-440): a write refused, a read that failed, or a
    * store busy [[ConsensusAdapter.BusyBeforeStop]] times with no block made the head, and for at least
    * [[ConsensusAdapter.BusyStopAfter]]. Nothing but a log line here; [[BaseNode]] wires its stop.
    */
  def onFatalStoreFailure: Throwable => Unit = ConsensusAdapter.NoStopWired

  lazy val consensusAdapter: ConsensusAdapter =
    new ConsensusAdapter(
      consensus,
      blockchainReader,
      blockQueue,
      blockValidation,
      IORuntime.global,
      Some(invalidChainReporter),
      powEscalationFeed,
      onFatalStoreFailure
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
    PeerStatisticsBuilder & BlacklistBuilder & InstanceConfigProvider & NodeAllowlistBuilder =>

  lazy val peerConfiguration: PeerConfiguration = instanceConfig.Network.peer

  lazy val snapGoodPeersOpt: Option[SnapGoodPeers] =
    val cfg = SnapGoodPeersConfig(instanceConfig.config, instanceConfig.config.getString("datadir"))
    if cfg.enabled then Some(new SnapGoodPeers(cfg)) else None

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
          instanceConfig.supportedCapabilities,
          Some(peerScoringManager),
          nodeAllowlist
        ),
        discoveryConfig,
        blacklist,
        snapGoodPeers = snapGoodPeersOpt,
        peerScoringManager = Some(peerScoringManager),
        allowlist = nodeAllowlist
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
          stateForRoot = blockchainReader.stateForRoot,
          blockchainReader = Some(blockchainReader),
          isPoWChain = blockchainConfig.terminalTotalDifficulty.isEmpty,
          genesisHeadEvictionGrace = com.chipprbots.ethereum.utils.Config.Network.peer.genesisHeadEvictionGrace
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
    InstanceConfigProvider & SecureRandomBuilder =>

  lazy val networkConfig = instanceConfig.Network

  /** The client context of the external-IP lookup's HTTPS step (spec 030 FR-044); a profile that does not load stops
    * the start.
    */
  lazy val externalIpTls: javax.net.ssl.SSLContext =
    com.chipprbots.ethereum.tls.OutboundTls
      .forSite(instanceConfig.config, "network.server-address.external-ip-tls-profile", secureRandom)
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** What the server actor found out about the node's address (how, when): written by its detection, read by the
    * dashboard (spec 029).
    */
  lazy val networkAddressInfo: NetworkAddressInfo = new NetworkAddressInfo

  lazy val server: org.apache.pekko.actor.typed.ActorRef[ServerActor.Command] =
    classicSystem.spawn(
      Behaviors
        .supervise(ServerActor(nodeStatusHolder, peerManager, blacklist, networkAddressInfo, externalIpTls))
        .onFailure[Throwable](SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1)),
      "server"
    )

trait Web3ServiceBuilder:
  lazy val web3Service = new Web3Service

trait NetServiceBuilder:
  this: PeerManagerActorBuilder & NodeStatusBuilder & BlacklistBuilder & InstanceConfigProvider & ActorSystemBuilder &
    NodeAllowlistBuilder =>

  lazy val netServiceConfig: NetServiceConfig = NetServiceConfig(instanceConfig.config)

  lazy val netService = new NetService(nodeStatusHolder, peerManager, blacklist, netServiceConfig, nodeAllowlist)(using
    classicSystem.toTyped.scheduler
  )

/** The node's [[com.chipprbots.ethereum.transactions.TxGossipGate]]: shared by sync (SNAP/recovery close it, regular
  * sync and mining mark the node synced), the Engine API (forkchoiceUpdated marks it synced) and the tx pool, which
  * reads it on every peer tx message.
  */
trait TxGossipGateBuilder:
  lazy val txGossipGate: com.chipprbots.ethereum.transactions.TxGossipGate =
    new com.chipprbots.ethereum.transactions.TxGossipGate()

trait PendingTransactionsManagerBuilder:
  def pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command]

  /** The head the pool was last reset to (B-515): one per node, shared by the Engine API's fork choice and regular
    * sync's importer, which both reset the pool.
    */
  lazy val txPoolHead: com.chipprbots.ethereum.transactions.TxPoolHead =
    new com.chipprbots.ethereum.transactions.TxPoolHead
  // Alias kept for callers that reference the Typed ref by the old name.
  def pendingTransactionsManagerTyped: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
    pendingTransactionsManager
object PendingTransactionsManagerBuilder:
  trait Default extends PendingTransactionsManagerBuilder:
    self: ActorSystemBuilder & PeerManagerActorBuilder & NetworkPeerManagerActorBuilder & PeerEventBusBuilder &
      TxPoolConfigBuilder & BlockchainBuilder & StorageBuilder & EventTopicsBuilder & TxGossipGateBuilder =>

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
              storagesInstance.storages.stateStorage,
              txGossipGate = txGossipGate
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

  lazy val debugService = new DebugService(peerManager, networkPeerManager)(using classicSystem.toTyped.scheduler)

trait EthProofServiceBuilder:
  self: StorageBuilder & BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder & EthBlocksServiceBuilder =>

  lazy val ethProofService: ProofService = new EthProofService(
    blockchain,
    blockchainReader,
    mining.blockGenerator,
    blockchainConfig.ethCompatibleStorage,
    forkChoiceManagerForRpc
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
    classicSystem.toTyped.scheduler,
    com.chipprbots.ethereum.jsonrpc.CallTxBuilder.gasCapFrom(instanceConfig.config),
    () => stateAdoptionForRpc()
  )

  /** The adoption of a long branch under way on the unified layout, for `eth_syncing` (spec 018 C4d part 4b).
    * Overridden where the state recovery is built.
    */
  def stateAdoptionForRpc: () => Option[com.chipprbots.ethereum.ledger.StateRecovery.Progress] = () => None

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
    SyncControllerBuilder & PendingTransactionsManagerBuilder & TxPoolConfigBuilder & ActorSystemBuilder &
    SecureRandomBuilder & InstanceConfigProvider =>

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
    classicSystem,
    // the work notifier's client context (spec 030 FR-044); a profile that does not load stops the start
    com.chipprbots.ethereum.tls.OutboundTls
      .forSite(instanceConfig.config, "mining.notify-tls-profile", secureRandom)
      .fold(e => throw new IllegalStateException(e.message), com.chipprbots.ethereum.tls.OutboundTls.pekkoHttpsContext)
  )

/** The node's one gas price oracle (go-ethereum `eth/gasprice`), shared by eth_gasPrice, eth_maxPriorityFeePerGas,
  * eth_feeHistory's limits and the send/sign/fill fee defaults, so they read a single per-head cache.
  */
trait GasPriceOracleBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & InstanceConfigProvider =>

  lazy val gasPriceOracle: com.chipprbots.ethereum.jsonrpc.GasPriceOracle =
    new com.chipprbots.ethereum.jsonrpc.GasPriceOracle(
      blockchainReader,
      com.chipprbots.ethereum.jsonrpc.GasPriceOracle.configFrom(instanceConfig.config)(using blockchainConfig)
    )

trait EthTxServiceBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & PendingTransactionsManagerBuilder & MiningBuilder &
    TxPoolConfigBuilder & StorageBuilder & ActorSystemBuilder & InstanceConfigProvider & GasPriceOracleBuilder =>

  lazy val ethTxService = new EthTxService(
    blockchain,
    blockchainReader,
    mining,
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    storagesInstance.storages.transactionMappingStorage,
    classicSystem.toTyped.scheduler,
    com.chipprbots.ethereum.jsonrpc.RpcTxSubmission.fromConfig(instanceConfig.config),
    gasPriceOracleOpt = Some(gasPriceOracle)
  )

trait EthBlocksServiceBuilder:
  self: BlockchainBuilder & BlockchainConfigBuilder & MiningBuilder & BlockQueueBuilder & StorageBuilder &
    GasPriceOracleBuilder =>

  /** Override in subtraits that have access to ForkChoiceManager (e.g. EngineApiBuilder) */
  def forkChoiceManagerForRpc: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] = None

  lazy val ethBlocksService =
    new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue,
      forkChoiceManagerForRpc,
      storagesInstance.pruningMode,
      // This node's chain config, not the constructor's global default: it renders every transaction object.
      blockchainConfig,
      gasPriceOracleOpt = Some(gasPriceOracle)
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
  )(using classicSystem)

trait PersonalServiceBuilder:
  self: KeyStoreBuilder & BlockchainBuilder & BlockchainConfigBuilder & PendingTransactionsManagerBuilder &
    StorageBuilder & TxPoolConfigBuilder & EthTxServiceBuilder & EthInfoServiceBuilder & ActorSystemBuilder =>

  lazy val personalService: PersonalServiceAPI = new PersonalService(
    keyStore,
    blockchainReader,
    pendingTransactionsManager,
    txPoolConfig,
    this,
    ethTxService,
    new com.chipprbots.ethereum.jsonrpc.EthInfoTxGasEstimator(ethInfoService),
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
  self: TransactionHistoryServiceBuilder & JSONRpcConfigBuilder & BlockchainConfigBuilder =>

  lazy val fukuiiService = new FukuiiService(transactionHistoryService, jsonRpcConfig)

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
  )(using classicSystem.dispatcher, classicSystem.toTyped.scheduler)

trait KeyStoreBuilder:
  self: SecureRandomBuilder & KeyStoreConfigBuilder =>
  lazy val keyStore: KeyStore = new KeyStoreImpl(keyStoreConfig, secureRandom)

trait ApisBuilder extends ApisBase:
  object Apis:
    val Eth = "eth"
    // Mining control (miner_start, miner_stop, miner_getStatus, miner_setEtherbase): opt-in, as in go-ethereum.
    val Miner = "miner"
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
    List(Eth, Miner, Web3, Net, Personal, Fukuii, Mcp, Debug, Test, Testing, Qa, Admin, TxPool, Trace, Subscribe)

trait AdminServiceBuilder:
  this: PeerManagerActorBuilder & NodeStatusBuilder & BlockchainBuilder & BlockchainConfigBuilder &
    InstanceConfigProvider & ActorSystemBuilder & ConsensusBuilder & SyncControllerRefBuilder & BlacklistBuilder &
    NodeAllowlistBuilder & NetworkPeerManagerActorBuilder =>

  lazy val adminService: AdminService = new AdminService(
    nodeStatusHolder,
    peerManager,
    blockchainReader,
    blockchainConfig,
    instanceConfig.config.getConfig("network.rpc.net").getDuration("peer-manager-timeout").toMillis.millis,
    instanceConfig.config.getString("datadir"),
    blockedIPRegistry,
    chainImporter,
    () =>
      import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
      syncController.askForTyped[com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status](replyTo =>
        SyncController.WrappedSyncProtocol(com.chipprbots.ethereum.blockchain.sync.SyncProtocol.GetStatus(replyTo))
      )(using
        timeout = org.apache.pekko.util.Timeout(
          instanceConfig.config.getConfig("network.rpc.net").getDuration("peer-manager-timeout").toMillis.millis
        ),
        scheduler = classicSystem.toTyped.scheduler
      )
    ,
    clientId = instanceConfig.clientId,
    networkPeerManager = Some(networkPeerManager),
    peerLimitsConfig = Some(
      AdminService.PeerLimits(
        minOutgoing = peerConfiguration.minOutgoingPeers,
        maxOutgoing = peerConfiguration.maxOutgoingPeers,
        maxIncoming = peerConfiguration.maxIncomingPeers
      )
    ),
    allowlist = nodeAllowlist,
    allowlistStore = nodeAllowlistStore
  )(using classicSystem.toTyped.scheduler)

trait TxPoolServiceBuilder:
  this: PendingTransactionsManagerBuilder & TxPoolConfigBuilder & ActorSystemBuilder & BlockchainConfigBuilder =>

  lazy val txPoolService: TxPoolService = new TxPoolService(
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    txPoolConfig,
    classicSystem.toTyped.scheduler
  )

trait DebugTracingServiceBuilder:
  this: BlockchainBuilder & StxLedgerBuilder & StorageBuilder & MiningBuilder & InstanceConfigProvider =>

  lazy val debugTracingService: com.chipprbots.ethereum.jsonrpc.DebugTracingService =
    new com.chipprbots.ethereum.jsonrpc.DebugTracingService(
      blockchain,
      blockchainReader,
      mining,
      stxLedger,
      storagesInstance.storages.transactionMappingStorage,
      com.chipprbots.ethereum.jsonrpc.CallTxBuilder.gasCapFrom(instanceConfig.config)
    )

trait TraceServiceBuilder:
  this: BlockchainBuilder & StxLedgerBuilder & StorageBuilder & MiningBuilder & InstanceConfigProvider =>

  lazy val traceService: com.chipprbots.ethereum.jsonrpc.TraceService =
    new com.chipprbots.ethereum.jsonrpc.TraceService(
      blockchain,
      blockchainReader,
      mining,
      stxLedger,
      storagesInstance.storages.transactionMappingStorage,
      com.chipprbots.ethereum.jsonrpc.CallTxBuilder.gasCapFrom(instanceConfig.config)
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

  // One controller per transport, each answering only the namespaces enabled for that transport
  // (`network.rpc.http.apis`, `ws.apis`, `ipc.apis`; each falls back to `network.rpc.apis`). `jsonRpcController` keeps
  // the shared set and serves the Engine API's eth_, net_ and web3_ pass-through.
  lazy val jsonRpcTransportControllers: JsonRpcController.PerTransport = jsonRpcController.perTransport
  lazy val jsonRpcHttpController: JsonRpcController = jsonRpcTransportControllers.http
  lazy val jsonRpcWsController: JsonRpcController = jsonRpcTransportControllers.ws
  lazy val jsonRpcIpcController: JsonRpcController = jsonRpcTransportControllers.ipc

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
    MiningBuilder & PendingTransactionsManagerBuilder & InstanceConfigProvider & JSONRpcControllerBuilder &
    TxGossipGateBuilder & SecureRandomBuilder & RpcIdentityBuilder =>

  import com.chipprbots.ethereum.consensus.engine.*

  lazy val engineApiConfig: EngineApiHttpServer.Config =
    val engineConf = scala.util.Try(instanceConfig.config.getConfig("network.engine-api")).toOption
    EngineApiHttpServer.Config(
      enabled = engineConf.flatMap(c => scala.util.Try(c.getBoolean("enabled")).toOption).getOrElse(false),
      interface = engineConf.flatMap(c => scala.util.Try(c.getString("interface")).toOption).getOrElse("localhost"),
      port = engineConf.flatMap(c => scala.util.Try(c.getInt("port")).toOption).getOrElse(8551),
      jwtSecretPath = engineConf.flatMap(c => scala.util.Try(c.getString("jwt-secret-path")).toOption)
    )

  lazy val forkChoiceManager: ForkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter, blockchain)

  /** The recovery of a state that is no longer held, on the unified layout only (spec 018 C4d part 3). It covers
    * `state.recovery.max-blocks`, and no more than half of `state.overlay.max-forward-layers`: its layers are kept
    * beside the others, which a recovery the size of the whole budget would push out (beacon), until the pins of a long
    * branch (C4d part 4).
    */
  lazy val stateRecovery: Option[com.chipprbots.ethereum.ledger.StateRecovery] =
    storagesInstance.storages.pathStateStorage.map { state =>
      val config = com.chipprbots.ethereum.db.storage.pathstate.StateConfig(instanceConfig.config)
      val layered = config.recovery.maxBlocks.min(config.overlay.maxForwardLayers / 2).max(1)
      new com.chipprbots.ethereum.ledger.StateRecovery(
        blockchainReader,
        blockchainWriter,
        blockExecution,
        state,
        maxBlocks = layered,
        // a fork choice's branch longer than that is committed in place, as far as `state.recovery.max-blocks` (C4d part 4)
        maxAdoptedBlocks = config.recovery.maxBlocks.max(layered),
        // a straight extension of the head, as after a sync's hand-off, as far as `state.recovery.max-catch-up` (C5a
        // part 8)
        catchUpBlocks = config.recovery.maxCatchUp
      )(using blockchainConfig)
    }

  lazy val engineApiService: EngineApiService =
    given typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler
    new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExecution,
      forkChoiceManager,
      Some(pendingTransactionsManagerTyped),
      getPayloadRebuildBudget = EngineApiService.GetPayloadRebuildBudget,
      // go-ethereum's --miner.gaslimit equivalent, as for the testing_* namespace: mining.gas-limit-target.
      builderGasCeil = mining.config.generic.gasLimitTarget,
      reorgState = blockchain,
      txGossipGate = Some(txGossipGate),
      stateHeldInPlace = storagesInstance.storages.pathStateStorage.map(state => () => state.anchor.map(_.hash)),
      stateRecovery = stateRecovery,
      escalationWatch = escalationWatch,
      txPoolHead = txPoolHead
    )(using blockchainConfig, typedScheduler)

  // The controller's fork gates read the same schedule its service executes with (not the process-global config, which
  // a ChainInstance with its own InstanceConfig does not share).
  lazy val engineApiController: EngineApiController =
    // The pass-through's calls are counted as the Engine port's, for the dashboard (spec 029 FR-038).
    new EngineApiController(
      engineApiService,
      Some(jsonRpcController.onTransport(com.chipprbots.ethereum.jsonrpc.RpcCallStats.Engine)),
      blockchainConfig
    )

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
      // getRequestedHeadBlockHash, NOT getHeadBlockHash: the latter only moves on EXECUTED heads, so it still names the
      // old canonical head while the CL points at the side-chain head this binding exists to follow. See
      // ForkChoiceManager.getRequestedHeadBlockHash.
      designatedHead.bind(DesignatedHead(() => forkChoiceManager.getRequestedHeadBlockHash))
      log.info("Post-merge chain with Engine API enabled: p2p branch resolution now follows the CL's designated head")
    else
      log.debug(
        "Not a post-merge chain with a live Engine API: p2p branch resolution keeps chain-weight fork choice"
      )

  /** Let `ConsensusImpl` hand the consensus layer's branch it does not adopt to the fork choice's recovery (B-297).
    * Under the gate of [[bindDesignatedHead]], whose branch it is, and only where a recovery is built (the unified
    * layout).
    */
  def bindBranchHandOff(): Unit =
    if engineApiConfig.enabled && blockchainConfig.terminalTotalDifficulty.isDefined && stateRecovery.isDefined then
      branchHandOff.bind((tip, why) => engineApiService.handOffBranch(tip, why))
      log.info(
        "Post-merge chain on the unified layout: the consensus layer's branch is handed to the fork choice's recovery"
      )

  /** The Engine API's authenticator, or `None` when the Engine API is off.
    *
    * Forced at the top of `StdNode.start()`, before anything binds, so a refused secret file stops the node there with
    * one clear line (B-246). No path configured: `<datadir>/jwt.hex`, created on the first start as go-ethereum does,
    * so a consensus client can be given the same file. A random in-memory secret would be one none can share. A path
    * the Ephemery startup resolver pinned is created the same way; an operator's path is read and never created.
    */
  lazy val engineApiJwtAuthenticator: Option[JwtAuthenticator] =
    if engineApiConfig.enabled then
      val pinned = com.chipprbots.ethereum.ephemery.EphemeryResolver.pinnedJwtSecretPath
      Some(
        JwtSecretFile.authenticator(
          engineApiConfig.jwtSecretPath,
          pathIsDefault = engineApiConfig.jwtSecretPath.isDefined && engineApiConfig.jwtSecretPath == pinned,
          instanceConfig.config.getString("datadir"),
          new java.security.SecureRandom()
        )
      )
    else None

  lazy val maybeEngineApiServer: Option[EngineApiHttpServer] =
    engineApiJwtAuthenticator.map(jwtAuth =>
      new EngineApiHttpServer(
        engineApiController,
        jwtAuth,
        engineApiConfig,
        tls = () =>
          com.chipprbots.ethereum.tls.TlsServerEngine
            .engineApi(instanceConfig.config, secureRandom)
            .map(_.map { ctx =>
              tlsReloader.register(com.chipprbots.ethereum.tls.ListenerId.EngineApi.label, ctx)
              ctx
            })
      )
    )

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

/** The client-certificate identities of the HTTP and WS listeners (spec 030 FR-020–FR-028). A listener resolves
  * identities only where its `tls.client-auth` is `want` or `need`; each rule's controller is built once, here.
  */
trait RpcIdentityBuilder:
  self: JSONRpcControllerBuilder & JSONRpcConfigBuilder & InstanceConfigProvider =>

  import com.chipprbots.ethereum.jsonrpc.server.http.RpcCallers
  import com.chipprbots.ethereum.tls.*

  // The start-up check (StdNode.checkTlsAtStart) refuses the start on a rule that does not parse, before any server
  // is built, so a Left here is never reached by a node that starts.
  private lazy val identityRules: IdentityRules =
    IdentityRules
      .parse(instanceConfig.config, availableApis)
      .fold(e => throw new IllegalStateException(e.message), identity)

  private def callersFor(
      listener: Either[TlsConfigError, Option[ResolvedListener]],
      controller: JsonRpcController,
      transportApis: Seq[String]
  ): Option[RpcCallers.Reloadable] =
    listener.toOption.flatten.filter(TlsStartupCheck.resolvesIdentities).map { _ =>
      RpcCallers(
        identityRules,
        transportApis,
        (apis, label) => controller.forApis(apis).forCaller(label),
        IdentityRules.graphQlEnabled(instanceConfig.config)
      )
    }

  lazy val rpcHttpCallers: Option[RpcCallers.Reloadable] =
    callersFor(TlsConfig.rpcHttp(instanceConfig.config), jsonRpcHttpController, jsonRpcConfig.httpApis)

  lazy val rpcWsCallers: Option[RpcCallers.Reloadable] =
    callersFor(TlsConfig.rpcWs(instanceConfig.config), jsonRpcWsController, jsonRpcConfig.wsApis)

  /** The node's one TLS reload loop (spec 030 FR-030–FR-033): each TLS listener registers its context when it binds,
    * and the identity-rules file, when one is set, is registered by [[startIdentityReload]].
    */
  lazy val tlsReloader: TlsReloader = new TlsReloader()

  /** A listener's TLS context for one bind, registered with the reload loop under the listener's label. */
  protected def reloading(
      listener: ListenerId,
      context: Either[TlsConfigError, TlsServerContext]
  ): Either[TlsConfigError, TlsServerContext] =
    context.foreach(c => tlsReloader.register(listener.label, c))
    context

  /** Re-reads `network.rpc.client-identities-file` on the shortest reload interval of the listeners that resolve
    * identities (one minute by default), and hands a valid change to both listeners' callers (FR-033). Nothing where
    * the rules are inline, or no listener resolves identities.
    */
  def startIdentityReload(): Unit =
    val callers = rpcHttpCallers.toList ++ rpcWsCallers.toList
    IdentityRules.file(instanceConfig.config).toOption.flatten.filter(_ => callers.nonEmpty).foreach { file =>
      val config = instanceConfig.config
      val intervals = (TlsConfig.rpcHttp(config).toOption.flatten.toList ++ TlsConfig.rpcWs(config).toOption.flatten)
        .filter(TlsStartupCheck.resolvesIdentities)
        .map(_.profile.reload)
        .filter(_.enabled)
        .map(_.interval)
      if intervals.nonEmpty then
        tlsReloader.registerRules(
          file,
          intervals.minBy(_.toMillis),
          () => IdentityRules.parse(config, availableApis),
          rules => callers.foreach(_.update(rules))
        )
    }

trait JSONRpcHttpServerBuilder:
  self: ActorSystemBuilder & BlockchainBuilder & JSONRpcControllerBuilder & JSONRpcHealthcheckerBuilder &
    SecureRandomBuilder & JSONRpcConfigBuilder & GraphQLServiceBuilder & InstanceConfigProvider & RpcIdentityBuilder =>

  lazy val maybeJsonRpcHttpServer: Either[String, JsonRpcHttpServer] =
    given org.apache.pekko.actor.ActorSystem = classicSystem
    JsonRpcHttpServer(
      jsonRpcHttpController,
      jsonRpcHealthChecker,
      jsonRpcConfig.httpServerConfig,
      () =>
        reloading(
          com.chipprbots.ethereum.tls.ListenerId.RpcHttp,
          com.chipprbots.ethereum.tls.TlsServerEngine.rpcHttp(instanceConfig.config, secureRandom)
        ),
      maybeGraphQLService,
      rpcHttpCallers
    )

trait JSONRpcIpcServerBuilder:
  self: ActorSystemBuilder & JSONRpcControllerBuilder & JSONRpcConfigBuilder =>

  lazy val jsonRpcIpcServer = new JsonRpcIpcServer(jsonRpcIpcController, jsonRpcConfig.ipcServerConfig)

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
  self: ActorSystemBuilder & JSONRpcControllerBuilder & JSONRpcConfigBuilder & SubscriptionManagerBuilder &
    SecureRandomBuilder & InstanceConfigProvider & RpcIdentityBuilder =>

  lazy val jsonRpcWsServer: com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer =
    new com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer(
      jsonRpcWsController,
      subscriptionManager,
      jsonRpcConfig.wsServerConfig,
      () =>
        com.chipprbots.ethereum.tls.TlsServerEngine
          .rpcWs(instanceConfig.config, secureRandom)
          .map(_.map { ctx =>
            tlsReloader.register(com.chipprbots.ethereum.tls.ListenerId.RpcWs.label, ctx)
            ctx
          }),
      rpcWsCallers
    )(using classicSystem)

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
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      this
    )

trait SyncControllerBuilder extends SyncControllerRefBuilder:

  self: ActorSystemBuilder & ServerActorBuilder & BlockchainBuilder & BlockchainConfigBuilder & ConsensusBuilder &
    NodeStatusBuilder & StorageBuilder & StxLedgerBuilder & PeerEventBusBuilder & PendingTransactionsManagerBuilder &
    OmmersPoolBuilder & NetworkPeerManagerActorBuilder & SyncConfigBuilder & ShutdownHookBuilder & MiningBuilder &
    BlacklistBuilder & MESSBuilder & EventTopicsBuilder & TxGossipGateBuilder & SecureRandomBuilder &
    InstanceConfigProvider =>

  /** Override in concrete builders that also mix in [[EngineApiBuilder]] to enable CL-driven SNAP pivot selection.
    * Defaults to `None` for setups without an Engine API (e.g. ETC mainnet pre-merge wiring). Closes #1207.
    */
  def forkChoiceManagerForSync: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] = None

  /** The recovery of a state that is no longer held, where the node has one: reset around a clear of the state (spec
    * 018 C5a, B-307). Overridden with the fork choice manager, where the Engine API is built.
    */
  def stateRecoveryForSync: Option[com.chipprbots.ethereum.ledger.StateRecovery] = None

  // SyncController is Pekko Typed (Group ROOT, narrowed) — a `Behavior[Command]`. Spawned via
  // classicSystem.spawn so it lives in the Classic system's guardian tree while exposing a fully-Typed
  // ActorRef[Command]. All callers now hold a TypedActorRef[SyncController.Command] (OQ-5 kill, 8k-G).
  /** What the sync controller gives the state up with, on the unified layout (spec 018 C5a part 7b): the handler over
    * the guard, the clear, and the count.
    */
  lazy val stateEscalationPort: Option[SyncController.StateEscalationPort] =
    storagesInstance.storages.pathStateStorage.map { pathState =>
      import com.chipprbots.ethereum.blockchain.sync.EscalationGuard
      import com.chipprbots.ethereum.blockchain.sync.EscalationHandler
      import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
      import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
      import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
      import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
      import com.chipprbots.ethereum.db.storage.pathstate.StateMetrics
      val appState = storagesInstance.storages.appStateStorage
      val escalationLog = org.slf4j.LoggerFactory.getLogger(classOf[EscalationHandler])
      val handler = new EscalationHandler(
        new EscalationGuard(
          escalationCooldownMillis,
          () => appState.getStateEscalationLast(),
          () => appState.getStateEscalationGivenUp()
        ),
        hash =>
          blockchainReader.getBlockHeaderByHash(com.chipprbots.ethereum.domain.BlockHash(hash)).map(_.number.value),
        () => StateMetrics.escalationSuppressed(),
        escalationLog.error(_),
        escalationLog.warn(_),
        EscalationHandler.RefusedClearHoldsMillis.min(escalationCooldownMillis)
      )
      new SyncController.StateEscalationPort:
        def decide(request: EscalationWatch.Request, nowMillis: Long): EscalationHandler.Outcome =
          handler.onRequest(request, nowMillis)
        def clear(record: StateEscalation, givenUpFor: Option[GivenUpBranch]): Boolean =
          // With no recovery running and none taken (beacon, B-307): one between two blocks it commits in place would
          // move the head back over a state that is gone, and one that then fails the state closed would keep the
          // sync that follows from ever handing off. The head the fork choice followed is of the state given up: left,
          // a recovery works out what to hold from it, and is refused as a chain that cannot be reached.
          def clearNow(): Unit =
            pathState.clearForResync(
              blockchainReader.genesisHeader.hash.value,
              record.atMillis,
              Some(record),
              givenUpFor
            )
            // after the clear, not before (beacon): a fork choice that lands in between would name again, for the hours
            // of the sync, a head whose state is then deleted
            forkChoiceManagerForSync.foreach(_.clear())
            // the refusals counted so far are of the state that is gone: the count begins again with the sync
            escalationWatch.foreach(_.stateCleared())
          // B-465: refused, with nothing written, while an adoption holds the records; asked for again after a while.
          // Looked at before the recoveries are reset, so that a refused clear leaves them as they were (vault)
          SyncController.StateEscalationPort.notClearedWhenRefused(pathState.recordsHeld, escalationLog.warn(_)) {
            stateRecoveryForSync.fold { clearNow(); true }(_.reset()(clearNow()))
          }
        def escalated(kind: EscalationKind): Unit = StateMetrics.escalated(kind)
        def regularSyncStarted(): Unit = handler.reset()
        def clearRefused(): Unit = handler.clearRefused(System.currentTimeMillis())
        def clearForBranchRetry(): Boolean =
          def clearNow(): Unit =
            val _ = pathState.clearForBranchRetry(blockchainReader.genesisHeader.hash.value, System.currentTimeMillis())
          SyncController.StateEscalationPort.notClearedWhenRefused(pathState.recordsHeld, escalationLog.warn(_)) {
            stateRecoveryForSync.fold { clearNow(); true }(_.reset()(clearNow()))
          }
    }

  /** A sync on the unified layout (spec 018 C5a part 10): the hand-off each SNAP sync ends with, over the rows SNAP
    * writes (the one data source), and the check before SNAP begins. None on the hash layout.
    */
  lazy val pathLiveSyncPort: Option[SyncController.PathLiveSyncPort] =
    storagesInstance.storages.pathStateStorage.map { pathState =>
      import com.chipprbots.ethereum.db.storage.PathNodeStorage
      import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
      import com.chipprbots.ethereum.db.storage.pathstate.SyncHandoff
      val storages = storagesInstance.storages
      val rowStorage = new PathNodeStorage(storages.flatSlotStorage.dataSource)
      val built = new SyncHandoff(
        pathState,
        rowStorage,
        storages.appStateStorage,
        storages.blockHeadersStorage,
        storages.blockNumberMappingStorage,
        storages.chainWeightStorage,
        storages.blockBodiesStorage,
        storages.evmCodeStorage
      )
      new com.chipprbots.ethereum.blockchain.sync.PathLiveSync(
        pathState,
        storages.appStateStorage,
        built,
        genesis = () =>
          val header = blockchainReader.genesisHeader
          BlockRef(header.hash.value, header.number.value, header.stateRoot.value)
        ,
        resetRecoveries = clear => stateRecoveryForSync.fold { clear(); true }(_.reset()(clear())),
        stateCleared = () =>
          forkChoiceManagerForSync.foreach(_.clear())
          escalationWatch.foreach(_.stateCleared())
        ,
        handedOffNow = () => escalationWatch.foreach(_.stateCleared()),
        checkpointTarget = new com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.UnifiedTarget(
          pathState,
          rowStorage,
          built,
          storages.appStateStorage,
          () =>
            val header = blockchainReader.genesisHeader
            BlockRef(header.hash.value, header.number.value, header.stateRoot.value)
        ),
        snapPeers = snapPeerPrecondition
      )
    }

  /** The peers the peer manager maintains, kept from the peer event bus for the snap-peer precondition (B-453). */
  private lazy val maintainedPeerIds: com.chipprbots.ethereum.network.MaintainedPeerIds =
    val ids = new com.chipprbots.ethereum.network.MaintainedPeerIds
    classicSystem.spawn(
      com.chipprbots.ethereum.network.MaintainedPeerIds.behavior(ids, peerEventBus),
      "maintained-peer-ids"
    ): Unit
    ids

  /** What the snap-peer precondition at SNAP's start counts against on the unified layout (spec 018 C6): the shipped
    * `min-snap-peers` and `pow-snap-peer-wait`, and the node ids of the peers the operator declared, in
    * `static-nodes.json` and `sync.snap-sync.snap-server-peers` (read here, once), and over `admin_addPeer` (the peer
    * manager's maintained set, read at each count; B-453).
    */
  private def snapPeerPrecondition: com.chipprbots.ethereum.blockchain.sync.PathLiveSync.SnapPeerPrecondition =
    import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
    // subscribed now, with the port, not at the first count
    val added = maintainedPeerIds
    val snapConfig = scala.util
      .Try(SNAPSyncConfig.fromConfig(com.chipprbots.ethereum.utils.Config.config.getConfig("sync")))
      .getOrElse(SNAPSyncConfig())
    val staticNodes = scala.util
      .Try(com.chipprbots.ethereum.utils.Config.config.getString("datadir"))
      .toOption
      .toSeq
      .flatMap(com.chipprbots.ethereum.network.StaticNodesLoader.load(_))
    val declared = (staticNodes ++ snapConfig.snapServerPeers).flatMap { uri =>
      scala.util
        .Try(org.apache.pekko.util.ByteString(org.bouncycastle.util.encoders.Hex.decode(uri.getUserInfo)))
        .toOption
    }
    com.chipprbots.ethereum.blockchain.sync.PathLiveSync.SnapPeerPrecondition(
      snapConfig.minSnapPeers,
      snapConfig.powSnapPeerWait,
      declared.filter(_.nonEmpty).toSet,
      added = () => added.current
    )

  lazy val syncController: org.apache.pekko.actor.typed.ActorRef[SyncController.Command] =
    val ref = classicSystem
      .spawn(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
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
          forkChoiceManagerForSync,
          reorgState = blockchain,
          txGossipGate = txGossipGate,
          peerScoringManager = Some(peerScoringManager),
          stateEscalation = stateEscalationPort,
          pathLiveSync = pathLiveSyncPort,
          checkpointHttpClient = () =>
            com.chipprbots.ethereum.tls.OutboundTls
              .forSite(instanceConfig.config, "sync.checkpoint-sync-tls-profile", secureRandom)
              .map(com.chipprbots.ethereum.blockchain.checkpoint.CheckpointDownloader.client)
              .left
              .map(_.message),
          txPoolHead = txPoolHead
        ),
        "sync-controller"
      )
    if stateEscalationPort.isDefined then
      escalationRequests.set(request =>
        ref ! SyncController.WrappedSyncProtocol(
          com.chipprbots.ethereum.blockchain.sync.SyncProtocol.StateEscalationRequested(request)
        )
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
  /** What became of the UPnP port mapping, for the TUI's `net` report (B-287). */
  lazy val portMappingState: com.chipprbots.ethereum.network.PortMappingState =
    new com.chipprbots.ethereum.network.PortMappingState

  protected lazy val portForwarding: IO[IO[Unit]] = PortForwarder
    .openPorts(
      Seq(instanceConfig.Network.Server.port),
      Seq(discoveryConfig.port).filter(_ => discoveryConfig.discoveryEnabled),
      portMappingState
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
        .unsafeToFuture()(using ioRuntime)
    else
      // Resource was already started by another thread
      Future.unit

  def stopPortForwarding(): Future[Unit] =
    portForwardingRelease.getAndSet(None).fold(Future.unit)(_.unsafeToFuture()(using ioRuntime))

trait ShutdownHookBuilder:
  self: Logger & InstanceConfigProvider =>
  def shutdown: () => Unit = () => {
    /* No default behaviour during shutdown. */
  }

  /** What the JVM's shutdown hook calls: [[shutdown]], unless a builder says otherwise (a node does not wait there for
    * a stop already under way; B-274, B-426).
    */
  def shutdownOnExit(): Unit = shutdown()

  lazy val shutdownTimeoutDuration: Duration = instanceConfig.shutdownTimeout

  /** Whether the JVM shutdown hook holds this builder only weakly. False (a strong reference) for every node: nothing
    * else need keep the builder reachable once `main` returns, and a collected builder would silently skip its
    * `shutdown()` on SIGTERM. A test that wires a builder per case overrides this with true: the JVM keeps a registered
    * hook for the life of the process, so a strong hook leaked every builder (actor system, storages, caches); the EEST
    * corpus replays built ~26,000 and ran the CI JVM out of heap. Overridden with a constant `def`, since it is read
    * while this trait initialises.
    */
  protected def shutdownHookHoldsBuilderWeakly: Boolean = false

  // The hook thread is built in the companion, where it cannot capture `this`: an anonymous Thread written here would
  // hold the builder through its outer reference whatever the targets below say.
  Runtime.getRuntime.addShutdownHook(
    ShutdownHooks.hookThread(this, shutdownHookHoldsBuilderWeakly)
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

/** Builds [[ShutdownHookBuilder]]'s JVM hook. A separate object, not the trait's companion: the companion is itself a
  * ShutdownHookBuilder that registers a hook while it initialises, and must not be called into from there.
  */
private[nodebuilder] object ShutdownHooks:
  /** The JVM shutdown hook of `builder`: it calls `builder.shutdown()`, holding the builder strongly unless `weakly`
    * (see [[ShutdownHookBuilder.shutdownHookHoldsBuilderWeakly]]), in which case a collected builder is simply skipped.
    * Defined here, not in the trait, so the thread captures no outer reference.
    */
  def hookThread(builder: ShutdownHookBuilder, weakly: Boolean): Thread =
    val strong: ShutdownHookBuilder = if weakly then null else builder
    val weak = new java.lang.ref.WeakReference[ShutdownHookBuilder](builder)
    new Thread():
      override def run(): Unit =
        val target = if strong != null then strong else weak.get
        if target != null then target.shutdownOnExit()

trait GenesisDataLoaderBuilder:
  self: BlockchainBuilder & StorageBuilder =>

  lazy val genesisDataLoader: GenesisDataLoader =
    GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages)

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
    with NodeAllowlistBuilder
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
    with GasPriceOracleBuilder
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
    with GraphQLServiceBuilder
    with RpcIdentityBuilder
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
    with TxGossipGateBuilder
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
  // The node's own `secure-random-algo`, not the process's (B-339).
  override protected def secureRandomAlgo: Option[String] = instanceConfig.secureRandomAlgo

  // Resolve conflicting ioRuntime from PeerDiscoveryManagerBuilder and PortForwardingBuilder
  implicit override lazy val ioRuntime: IORuntime = IORuntime.global

  // The adoption of a long branch, for eth_syncing (spec 018 C4d part 4b)
  override def stateAdoptionForRpc: () => Option[com.chipprbots.ethereum.ledger.StateRecovery.Progress] =
    () => stateRecovery.flatMap(_.adoption)

  // Wire ForkChoiceManager to RPC services for "safe"/"finalized" block tag resolution
  override def forkChoiceManagerForRpc: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] =
    Some(forkChoiceManager)

  // Wire ForkChoiceManager to the sync layer so SNAP can pivot off CL-driven heads on
  // post-merge chains. Closes #1207.
  override def forkChoiceManagerForSync: Option[com.chipprbots.ethereum.consensus.engine.ForkChoiceManager] =
    Some(forkChoiceManager)

  override def stateRecoveryForSync: Option[com.chipprbots.ethereum.ledger.StateRecovery] = stateRecovery

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
      Some(blockTopic),
      // go-ethereum's --miner.gaslimit equivalent. hive's rpc-compat sets HIVE_TARGET_GAS_LIMIT
      // to 60000000 so every client converges to the same next-block gas limit; fukuii's default
      // mining.gas-limit-target is already 60000000.
      miningConfig.gasLimitTarget,
      Option.when(blockchainReader.holdsStateInPlace)(
        TestingService.importInPlace(blockchainReader, blockExecution)(using blockchainConfig)
      )
    )(using blockchainConfig)
  )
