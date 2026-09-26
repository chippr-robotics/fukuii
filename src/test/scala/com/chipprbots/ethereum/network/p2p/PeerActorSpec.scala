// §8a-retro batch E6: migrated to ActorTestKit + ManualTime (PeerActor Typed, Wave 3 gate met)
package com.chipprbots.ethereum.network.p2p

import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.language.postfixOps

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.*
import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerActor.GetStatus
import com.chipprbots.ethereum.network.PeerActor.Status.Handshaked
import com.chipprbots.ethereum.network.PeerActor.StatusResponse
import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.handshaker.NetworkHandshaker
import com.chipprbots.ethereum.network.handshaker.NetworkHandshakerConfiguration
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status68.Status68 as Status
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.*
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.DisconnectEnc
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong.PongEnc
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

class PeerActorSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  val manualTime: ManualTime = ManualTime()

  val remoteNodeKey: AsymmetricCipherKeyPair = generateKeyPair(new SecureRandom)
  val remoteNodeId: ByteString = ByteString(remoteNodeKey.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId)

  val blockchainConfig = Config.blockchains.blockchainConfig

  "PeerActor" should "create rlpx connection and send hello message" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]

  it should "retry failed rlpx connection" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    val deathProbe = testKit.createTestProbe[Any]()

    (0 to 3).foreach { _ =>
      manualTime.timePasses(5.seconds)
      rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
      peer ! RLPxConnectionHandler.ConnectionFailed
    }

    deathProbe.expectTerminated(peer)

  it should "try to reconnect on broken rlpx connection" taggedAs (UnitTest, NetworkTest) in new NodeStatusSetup
    with HandshakerSetup:
    override def protocol: Capability = Capability.ETH63

    // Test probes live under /system and cannot be stopped via testKit.stop(), which routes
    // through the /user guardian (can only stop its direct children). Spawning user actors
    // lets testKit.stop() work correctly. Spy probes capture messages PeerActor sends.
    val conn1Spy = testKit.createTestProbe[RLPxConnectionHandler.Command]()
    val conn2Spy = testKit.createTestProbe[RLPxConnectionHandler.Command]()
    val conn1: ActorRef[RLPxConnectionHandler.Command] = testKit.spawn(
      Behaviors.receiveMessage[RLPxConnectionHandler.Command] { msg =>
        conn1Spy.ref ! msg; Behaviors.same
      },
      s"rlpx-conn-1-${java.util.UUID.randomUUID()}"
    )
    val conn2: ActorRef[RLPxConnectionHandler.Command] = testKit.spawn(
      Behaviors.receiveMessage[RLPxConnectionHandler.Command] { msg =>
        conn2Spy.ref ! msg; Behaviors.same
      },
      s"rlpx-conn-2-${java.util.UUID.randomUUID()}"
    )
    val connQueue = new java.util.concurrent.LinkedBlockingQueue[ActorRef[RLPxConnectionHandler.Command]]()
    connQueue.add(conn1)
    connQueue.add(conn2)

    val peerMessageBus = testKit.spawn(PeerEventBusActor.behavior(), s"peer-event-bus-${java.util.UUID.randomUUID()}")
    val knownNodesManager: TestProbe[KnownNodesManager.Command] = testKit.createTestProbe()

    val peer: ActorRef[PeerActor.Command] = testKit.spawn(
      PeerActor.apply(
        new InetSocketAddress("127.0.0.1", 0),
        _ => connQueue.poll(),
        peerConf,
        peerMessageBus,
        knownNodesManager.ref,
        false,
        handshaker
      )
    )

    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    conn1Spy.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)
    conn1Spy.expectMessageType[RLPxConnectionHandler.SendMessage]

    // Stop conn1 (user actor) → PeerActor deathwatch fires RlpxTerminated → retry scheduled
    testKit.stop(conn1)
    manualTime.timePasses(2.seconds)
    // After timer fires, factory returns conn2 → PeerActor sends ConnectTo to conn2
    conn2Spy.expectMessageType[RLPxConnectionHandler.ConnectTo]

  it should "successfully connect to ETC peer" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    val uri = new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@localhost:9000")
    val completeUri = new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@127.0.0.1:9000?discport=9000")

    saveEtcChainAtDaoFork()

    peer ! PeerActor.ConnectTo(uri)
    peer ! PeerActor.ConnectTo(uri)

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68()
    )
    // ETH68+: ForkId validation replaces fork-block exchange — no GetBlockHeaders step

    // Check that peer is connected
    peer ! RLPxConnectionHandler.MessageReceived(Ping())
    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Pong()))

    knownNodesManager.expectMessage(KnownNodesManager.AddKnownNode(completeUri))
    knownNodesManager.expectNoMessage()

  it should "fail handshake with peer that has a wrong genesis hash" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    val uri = new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@localhost:9000")
    peer ! PeerActor.ConnectTo(uri)

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68(genesisHash = genesisHash.drop(2))
    )

    rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]

  it should "successfully connect to ETH peer with protocol 68" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    override def protocol: Capability = Capability.ETH68
    val uri = new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@localhost:9000")
    val completeUri = new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@127.0.0.1:9000?discport=9000")

    // Ensure local chain is past the fork so ForkId validation succeeds by persisting the DAO fork block as best
    val daoForkChainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(daoForkBlockChainTotalDifficulty)
    blockchainWriter.save(
      Fixtures.Blocks.DaoForkBlock.block,
      Seq.empty,
      daoForkChainWeight,
      saveAsBestBlock = true
    )

    peer ! PeerActor.ConnectTo(uri)
    peer ! PeerActor.ConnectTo(uri)

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    // Hello exchange
    val remoteHello: Hello =
      Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused"))
    rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]
    peer ! RLPxConnectionHandler.MessageReceived(remoteHello)

    val remoteStatus: ETHPackets.Status68.Status68 = ETHPackets.Status68.Status68(
      protocolVersion = Capability.ETH68.version,
      networkId = peerConf.networkId,
      totalDifficulty = daoForkBlockChainTotalDifficulty + 100000, // remote is after the fork
      bestHash = ByteString("blockhash"),
      genesisHash = genesisHash,
      forkId = ForkId.create(genesisHash, 0L, PeerActorSpec.this.blockchainConfig)(daoForkBlockNumber)
    )
    // Node status exchange
    expectStatusMessage()
    peer ! RLPxConnectionHandler.MessageReceived(remoteStatus)
    // Fork block exchange is skipped for ETH64+ peers per EIP-2124 (ForkId validation replaces it)
    rlpxConnection.expectNoMessage(200.milliseconds)

    // Check that peer is connected
    peer ! RLPxConnectionHandler.MessageReceived(Ping())
    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Pong()))

    knownNodesManager.expectMessage(KnownNodesManager.AddKnownNode(completeUri))
    knownNodesManager.expectNoMessage()

  it should "successfully connect to and IPv6 peer" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    val uri = new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@[::]:9000")
    val completeUri =
      new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray[Byte])}@[0:0:0:0:0:0:0:0]:9000?discport=9000")

    saveEtcChainAtDaoFork()

    peer ! PeerActor.ConnectTo(uri)

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68()
    )

    // Check that peer is connected
    peer ! RLPxConnectionHandler.MessageReceived(Ping())
    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Pong()))

    knownNodesManager.expectMessage(KnownNodesManager.AddKnownNode(completeUri))
    knownNodesManager.expectNoMessage()

  it should "disconnect from non-ETC peer" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    // ETH68+: ForkId validation detects non-ETC peers. A wrong genesis hash causes
    // immediate Disconnect(UselessPeer) at Status validation (ETH mainnet shares the
    // same genesis as ETC, but a clearly-wrong hash is sufficient for this test).
    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68(genesisHash = genesisHash.drop(2))
    )

    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Disconnect(Disconnect.Reasons.UselessPeer)))

  it should "disconnect from non-ETC peer (when node is before fork)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // ETH68+: even when our node is at genesis (before the fork), a wrong genesis hash
    // from a non-ETC peer triggers Disconnect(UselessPeer) at the Status validation step.
    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68(genesisHash = genesisHash.drop(2))
    )

    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Disconnect(Disconnect.Reasons.UselessPeer)))

  it should "disconnect on Hello timeout" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    val connection: TestProbe[Nothing] = testKit.createTestProbe()

    peer ! PeerActor.HandleConnection(connection.ref.toClassic, new InetSocketAddress("localhost", 9000))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.HandleConnection]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)
    rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]
    manualTime.timePasses(5.seconds)
    rlpxConnection.expectMessage(
      RLPxConnectionHandler.SendMessage(Disconnect(Disconnect.Reasons.TimeoutOnReceivingAMessage))
    )

  it should "be fully connected and respond to pings after ETH68 handshake (replaces ETH63 fork-block exchange)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // ETH68+: ForkId validation replaces the ETH63 fork-block exchange. After the Status
    // exchange completes, the peer is immediately in Handshaked state.
    saveEtcChainAtDaoFork()

    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68()
    )

    // Handshake complete — verify peer is Handshaked
    val probe: TestProbe[StatusResponse] = testKit.createTestProbe()
    peer ! GetStatus(probe.ref)
    probe.expectMessage(StatusResponse(Handshaked))

    // And responds to pings normally
    peer ! RLPxConnectionHandler.MessageReceived(Ping())
    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Pong()))

  it should "stash disconnect message until handshaked" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    saveEtcChainAtDaoFork()

    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))
    peer ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68()
    )
    // ETH68+: handshake is complete right after Status exchange. The stashed TooManyPeers
    // disconnect fires immediately — no fork-block exchange step.

    rlpxConnection.expectMessage(RLPxConnectionHandler.SendMessage(Disconnect(Disconnect.Reasons.TooManyPeers)))

  it should "stay connected to pre fork peer" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val remoteStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = peerConf.networkId,
      chainWeight =
        ChainWeight.totalDifficultyOnly(daoForkBlockChainTotalDifficulty - 200000), // remote is before the fork
      bestHash = ByteString("blockhash"),
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )

    val peerActor: ActorRef[PeerActor.Command] = testKit.spawn(
      PeerActor.apply(
        new InetSocketAddress("127.0.0.1", 0),
        _ => rlpxConnection.ref,
        peerConf,
        peerMessageBus,
        knownNodesManager.ref,
        false,
        Mocks.MockHandshakerAlwaysSucceeds(remoteStatus, 0, false)
      )
    )

    peerActor ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peerActor ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    peerActor ! RLPxConnectionHandler.MessageReceived(Ping())
    rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]

  it should "disconnect gracefully after handshake" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    saveEtcChainAtDaoFork()

    peer ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))

    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peer ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)

    eth68Handshake(
      remoteHello = Hello(4, "test-client", Seq(Capability.ETH68), 9000, ByteString("unused")),
      remoteStatus = etcStatus68()
    )

    // Test that the handshake succeeded
    val statusProbe: TestProbe[StatusResponse] = testKit.createTestProbe()
    peer ! GetStatus(statusProbe.ref)
    statusProbe.expectMessage(StatusResponse(Handshaked))

    // Test peer terminated after peerConf.disconnectPoisonPillTimeout
    val deathProbe = testKit.createTestProbe[Any]()

    peer ! RLPxConnectionHandler.MessageReceived(Disconnect(Reasons.Other))

    // No terminated message instantly
    deathProbe.expectNoMessage()

    // terminated only after peerConf.disconnectPoisonPillTimeout
    manualTime.timePasses(peerConf.disconnectPoisonPillTimeout)

    deathProbe.expectTerminated(peer)

  it should "stop when Disconnect(AlreadyConnected) is received during handshake" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // 8k-H removed context.toClassic.parent sends; PeerActor now stops immediately
    // on pre-handshake Disconnect. Verify via death-watch instead of parent message.
    val deathProbe = testKit.createTestProbe[Any]()

    val peerUnderTest: ActorRef[PeerActor.Command] = testKit.spawn(
      PeerActor.apply(
        new InetSocketAddress("127.0.0.1", 0),
        _ => rlpxConnection.ref,
        peerConf,
        peerMessageBus,
        knownNodesManager.ref,
        false,
        handshaker
      )
    )

    peerUnderTest ! PeerActor.ConnectTo(new URI("encode://localhost:9000"))
    rlpxConnection.expectMessageType[RLPxConnectionHandler.ConnectTo]
    peerUnderTest ! RLPxConnectionHandler.ConnectionEstablished(remoteNodeId)
    rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]

    peerUnderTest ! RLPxConnectionHandler.MessageReceived(Disconnect(Reasons.AlreadyConnected))

    deathProbe.expectTerminated(peerUnderTest)

  trait BlockUtils:

    val blockBody = new BlockBody(Seq(), Seq())

    val etcForkBlockHeader = Fixtures.Blocks.DaoForkBlock.header

    val nonEtcForkBlockHeader: BlockHeader =
      etcForkBlockHeader.copy(
        parentHash = BlockHash(ByteString("this")),
        ommersHash = BlockHash(ByteString("is")),
        beneficiary = ByteString("not"),
        stateRoot = TrieRoot(ByteString("an")),
        transactionsRoot = TrieRoot(ByteString("ETC")),
        receiptsRoot = TrieRoot(ByteString("fork")),
        logsBloom = BloomFilter(ByteString("block"))
      )

  trait NodeStatusSetup extends SecureRandomBuilder:
    lazy val nodeKey: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)

    private trait LocalPruningConfigBuilder extends PruningConfigBuilder with TestInstanceConfigProvider:
      override val pruningMode: PruningMode = ArchivePruning

    lazy val storagesInstance: EphemDataSourceComponent & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with LocalPruningConfigBuilder
        with Storages.DefaultStorages
        with TestInstanceConfigProvider

    lazy val blockchainReader: BlockchainReader = BlockchainReader(storagesInstance.storages)
    lazy val blockchainWriter: BlockchainWriter = BlockchainWriter(storagesInstance.storages)
    lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)
    val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

    val nodeStatus: NodeStatus =
      NodeStatus(key = nodeKey, serverStatus = ServerStatus.NotListening, discoveryStatus = ServerStatus.NotListening)

    val nodeStatusHolder = new AtomicReference(nodeStatus)

    val genesisBlock = Fixtures.Blocks.Genesis.block
    val genesisWeight: ChainWeight = ChainWeight.totalDifficultyOnly(genesisBlock.header.difficulty.value)

    blockchainWriter.save(genesisBlock, Nil, genesisWeight, saveAsBestBlock = true)

    val daoForkBlockNumber = 1920000

    val peerConf: PeerConfiguration = new PeerConfiguration:
      override val fastSyncHostConfiguration: FastSyncHostConfiguration = new FastSyncHostConfiguration:
        val maxBlocksHeadersPerMessage: Int = 200
        val maxBlocksBodiesPerMessage: Int = 200
        val maxReceiptsPerMessage: Int = 200
        val maxMptComponentsPerMessage: Int = 200
      override val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration:
        override val waitForTcpAckTimeout: FiniteDuration = Timeouts.normalTimeout
        override val waitForHandshakeTimeout: FiniteDuration = Timeouts.normalTimeout
      override val waitForHelloTimeout: FiniteDuration = 3 seconds
      override val waitForStatusTimeout: FiniteDuration = 30 seconds
      override val waitForChainCheckTimeout: FiniteDuration = 15 seconds
      override val connectMaxRetries: Int = 3
      override val connectRetryDelay: FiniteDuration = 1 second
      override val disconnectPoisonPillTimeout: FiniteDuration = 3 seconds
      override val minOutgoingPeers = 5
      override val maxOutgoingPeers = 10
      override val maxIncomingPeers = 5
      override val maxPendingPeers = 5
      override val pruneIncomingPeers = 0
      override val minPruneAge: FiniteDuration = 1.minute
      override val networkId: Long = 1L
      override val p2pVersion: Int = Config.Network.peer.p2pVersion

      override val updateNodesInitialDelay: FiniteDuration = 5.seconds
      override val updateNodesInterval: FiniteDuration = 20.seconds
      override val shortBlacklistDuration: FiniteDuration = 1.minute
      override val longBlacklistDuration: FiniteDuration = 3.minutes
      override val statSlotDuration: FiniteDuration = 1.minute
      override val statSlotCount: Int = 30

  trait HandshakerSetup extends NodeStatusSetup:
    self =>
    def protocol: Capability

    val handshakerConfiguration: NetworkHandshakerConfiguration = new NetworkHandshakerConfiguration:
      override val forkResolverOpt: Option[ForkResolver] = Some(
        new ForkResolver.IrregularStateChangeDaoForkResolver(self.blockchainConfig.daoForkConfig.get)
      )
      override val nodeStatusHolder: AtomicReference[NodeStatus] = self.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = self.peerConf
      override val blockchain: Blockchain = self.blockchain
      override val blockchainReader: BlockchainReader = self.blockchainReader
      override val appStateStorage: AppStateStorage = self.storagesInstance.storages.appStateStorage
      override val blockchainConfig: BlockchainConfig = self.blockchainConfig

    val handshaker: NetworkHandshaker = NetworkHandshaker(handshakerConfiguration)

  trait TestSetup extends NodeStatusSetup with BlockUtils with HandshakerSetup:
    override def protocol: Capability = Capability.ETH63

    val genesisHash = genesisBlock.hash.value

    val daoForkBlockChainTotalDifficulty: BigInt = BigInt("39490964433395682584")

    val rlpxConnection: TestProbe[RLPxConnectionHandler.Command] =
      testKit.createTestProbe[RLPxConnectionHandler.Command]()

    val peerMessageBus =
      testKit.spawn(PeerEventBusActor.behavior(), s"peer-event-bus-${java.util.UUID.randomUUID()}")

    val knownNodesManager: TestProbe[KnownNodesManager.Command] =
      testKit.createTestProbe[KnownNodesManager.Command]()

    val peer: ActorRef[PeerActor.Command] = testKit.spawn(
      PeerActor.apply(
        new InetSocketAddress("127.0.0.1", 0),
        _ => rlpxConnection.ref,
        peerConf,
        peerMessageBus,
        knownNodesManager.ref,
        false,
        handshaker
      )
    )

    def expectStatusMessage(): Unit =
      rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]

    /** Advance our chain to the DAO fork block so ForkId validation succeeds for ETC mainnet peers. */
    def saveEtcChainAtDaoFork(): Unit =
      val daoForkChainWeight = ChainWeight.totalDifficultyOnly(daoForkBlockChainTotalDifficulty)
      blockchainWriter.save(Fixtures.Blocks.DaoForkBlock.block, Seq.empty, daoForkChainWeight, saveAsBestBlock = true)

    /** Canonical ETH68 handshake with the test's local chain state. */
    def eth68Handshake(remoteHello: Hello, remoteStatus: Status): Unit =
      rlpxConnection.expectMessageType[RLPxConnectionHandler.SendMessage]
      peer ! RLPxConnectionHandler.MessageReceived(remoteHello)
      expectStatusMessage()
      peer ! RLPxConnectionHandler.MessageReceived(remoteStatus)

    /** Build a valid ETC mainnet ETH68 Status for the remote peer. */
    def etcStatus68(networkId: Long = peerConf.networkId, genesisHash: ByteString = this.genesisHash): Status =
      Status(
        protocolVersion = Capability.ETH68.version,
        networkId = networkId,
        totalDifficulty = daoForkBlockChainTotalDifficulty + 100000,
        bestHash = ByteString("blockhash"),
        genesisHash = genesisHash,
        forkId = ForkId.create(this.genesisHash, 0L, handshakerConfiguration.blockchainConfig)(daoForkBlockNumber)
      )
