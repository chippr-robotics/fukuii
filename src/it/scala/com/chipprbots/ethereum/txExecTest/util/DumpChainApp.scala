package com.chipprbots.ethereum.txExecTest.util

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.typesafe.config
import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory

import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.db.components.RocksDbDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.components.Storages.PruningModeComponent
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.ProofService.EmptyStorageValueProof
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProof
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.ForkResolver
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.PeerStatisticsActor
import com.chipprbots.ethereum.network.discovery.DiscoveryConfig
import com.chipprbots.ethereum.network.handshaker.EtcHandshaker
import com.chipprbots.ethereum.network.handshaker.EtcHandshakerConfiguration
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.nodebuilder.AuthHandshakerBuilder
import com.chipprbots.ethereum.nodebuilder.NodeKeyBuilder
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

object DumpChainApp
    extends App
    with NodeKeyBuilder
    with SecureRandomBuilder
    with AuthHandshakerBuilder {
  val conf: config.Config = ConfigFactory.load("txExecTest/chainDump.conf")
  val node: String = conf.getString("node")
  val genesisHash: ByteString = ByteString(Hex.decode(conf.getString("genesisHash")))
  val privateNetworkId: Int = conf.getInt("networkId")
  val startBlock: Int = conf.getInt("startBlock")
  val maxBlocks: Int = conf.getInt("maxBlocks")

  val blockchainConfig = Config.blockchains.blockchainConfig
  val discoveryConfig: DiscoveryConfig = DiscoveryConfig(Config.config, blockchainConfig.bootstrapNodes)

  val peerConfig: PeerConfiguration = new PeerConfiguration {
    override val rlpxConfiguration: RLPxConfiguration = Config.Network.peer.rlpxConfiguration
    override val connectRetryDelay: FiniteDuration = Config.Network.peer.connectRetryDelay
    override val connectMaxRetries: Int = Config.Network.peer.connectMaxRetries
    override val disconnectPoisonPillTimeout: FiniteDuration = Config.Network.peer.disconnectPoisonPillTimeout
    override val waitForHelloTimeout: FiniteDuration = Config.Network.peer.waitForHelloTimeout
    override val waitForStatusTimeout: FiniteDuration = Config.Network.peer.waitForStatusTimeout
    override val waitForChainCheckTimeout: FiniteDuration = Config.Network.peer.waitForChainCheckTimeout
    override val fastSyncHostConfiguration: PeerManagerActor.FastSyncHostConfiguration =
      Config.Network.peer.fastSyncHostConfiguration
    override val minOutgoingPeers: Int = Config.Network.peer.minOutgoingPeers
    override val maxOutgoingPeers: Int = Config.Network.peer.maxOutgoingPeers
    override val maxIncomingPeers: Int = Config.Network.peer.maxIncomingPeers
    override val maxPendingPeers: Int = Config.Network.peer.maxPendingPeers
    override val pruneIncomingPeers: Int = Config.Network.peer.pruneIncomingPeers
    override val minPruneAge: FiniteDuration = Config.Network.peer.minPruneAge
    override val networkId: Int = privateNetworkId
    override val updateNodesInitialDelay: FiniteDuration = 5.seconds
    override val updateNodesInterval: FiniteDuration = 20.seconds
    override val shortBlacklistDuration: FiniteDuration = 1.minute
    override val longBlacklistDuration: FiniteDuration = 3.minutes
    override val statSlotDuration: FiniteDuration = 1.minute
    override val statSlotCount: Int = 30
  }

  val actorSystem: ActorSystem = ActorSystem("fukuii_system")
  trait PruningConfig extends PruningModeComponent {
    override val pruningMode: PruningMode = ArchivePruning
  }
  val storagesInstance: RocksDbDataSourceComponent with PruningConfig with Storages.DefaultStorages =
    new RocksDbDataSourceComponent with PruningConfig with Storages.DefaultStorages

  val blockchain: Blockchain = new BlockchainMock(genesisHash)
  // Create BlockchainReader using actual storages
  // The app uses it primarily for getHashByBlockNumber which will return the genesis hash
  val blockchainReader: BlockchainReader = BlockchainReader(storagesInstance.storages)

  val nodeStatus: NodeStatus =
    NodeStatus(key = nodeKey, serverStatus = ServerStatus.NotListening, discoveryStatus = ServerStatus.NotListening)

  lazy val nodeStatusHolder = new AtomicReference(nodeStatus)

  lazy val forkResolverOpt: Option[ForkResolver.EtcForkResolver] =
    blockchainConfig.daoForkConfig.map(new ForkResolver.EtcForkResolver(_))

  private val handshakerConfiguration: EtcHandshakerConfiguration =
    new EtcHandshakerConfiguration {
      override val forkResolverOpt: Option[ForkResolver] = DumpChainApp.forkResolverOpt
      override val nodeStatusHolder: AtomicReference[NodeStatus] = DumpChainApp.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = peerConfig
      // FIXME: Selecting value blockchain from object DumpChainApp, which extends scala.DelayedInit, is likely to yield an uninitialized value
      @annotation.nowarn
      override val blockchain: Blockchain = DumpChainApp.blockchain
      // FIXME: Selecting value blockchainReader from object DumpChainApp, which extends scala.DelayedInit, is likely to yield an uninitialized value
      @annotation.nowarn
      override val blockchainReader: BlockchainReader = DumpChainApp.blockchainReader
      override val appStateStorage: AppStateStorage = storagesInstance.storages.appStateStorage
      override val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
    }

  lazy val handshaker: Handshaker[PeerInfo] = EtcHandshaker(handshakerConfiguration)

  val peerMessageBus: ActorRef = actorSystem.actorOf(PeerEventBusActor.props)

  val peerStatistics: ActorRef =
    actorSystem.actorOf(PeerStatisticsActor.props(peerMessageBus, 1.minute, 30)(Clock.systemUTC()))

  val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)

  val peerManager: ActorRef = actorSystem.actorOf(
    PeerManagerActor.props(
      peerDiscoveryManager = actorSystem.deadLetters, // TODO: fixme
      peerConfiguration = peerConfig,
      peerMessageBus = peerMessageBus,
      peerStatistics = peerStatistics,
      knownNodesManager = actorSystem.deadLetters, // TODO: fixme
      handshaker = handshaker,
      authHandshaker = authHandshaker,
      discoveryConfig = discoveryConfig,
      blacklist = blacklist,
      capabilities = blockchainConfig.capabilities
    ),
    "peer-manager"
  )
  peerManager ! PeerManagerActor.StartConnecting

  actorSystem.actorOf(DumpChainActor.props(peerManager, peerMessageBus, maxBlocks, node), "dumper")
}

class BlockchainMock(genesisHash: ByteString) extends Blockchain {

  class FakeHeader()
      extends BlockHeader(
        ByteString.empty,
        ByteString.empty,
        ByteString.empty,
        ByteString.empty,
        ByteString.empty,
        ByteString.empty,
        ByteString.empty,
        0,
        0,
        0,
        0,
        0,
        ByteString.empty,
        ByteString.empty,
        ByteString.empty,
        HefEmpty
      ) {
    override lazy val hash: ByteString = genesisHash
  }

  override def getStorageProofAt(
      rootHash: NodeHash,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): StorageProof = EmptyStorageValueProof(StorageProofKey(position))

  override def removeBlock(hash: ByteString): Unit = ???

  override def getAccountStorageAt(rootHash: ByteString, position: BigInt, ethCompatibleStorage: Boolean): ByteString =
    ???

  override type S = InMemoryWorldStateProxyStorage
  override type WS = InMemoryWorldStateProxy

  def getBestBlockNumber(): BigInt = ???

  def getBestBlock(): Option[Block] = ???

  override def getBackingMptStorage(blockNumber: BigInt): MptStorage = ???

  override def getReadOnlyMptStorage(): MptStorage = ???

}
