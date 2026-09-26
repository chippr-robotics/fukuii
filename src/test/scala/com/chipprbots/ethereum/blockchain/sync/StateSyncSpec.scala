package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Random

import org.scalacheck.Gen
import org.scalactic.anyvals.PosInt
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.MptNodeData
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.TrieProvider
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.RestartRequested
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StartSyncingTo
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncFinished
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.StateSyncStats
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.WaitingForNewTargetBlock
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.*
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData.GetNodeDataEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class StateSyncSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with ScalaCheckPropertyChecks:

  // those tests are somewhat long running 3 successful evaluation should be fine
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(3))

  /** A generated world, reported by its size. On a failed property ScalaCheck shrinks the input, re-running a sync that
    * waits out its 20 s timeout at each step, then prints it. Up to 1000 accounts with up to 100 storage slots each
    * print as lines of several hundred KB, which the forked test JVM could not send back to sbt. With no Shrink
    * instance this type falls back to Shrink.shrinkAny, which does not shrink.
    */
  final case class GeneratedWorld(accounts: List[MptNodeData]):
    override def toString: String =
      s"GeneratedWorld(${accounts.size} accounts, ${accounts.map(_.accountStorage.size).sum} storage slots)"

  private val generatedWorlds: Gen[GeneratedWorld] = ObjectGenerators.genMultipleNodeData(1000).map(GeneratedWorld(_))

  "StateSync" should "sync state to different tries" taggedAs (UnitTest, SyncTest) in new TestSetup():
    forAll(generatedWorlds) { world =>
      val trieProvider = TrieProvider()
      val target = trieProvider.buildWorld(world.accounts)
      setAutoPilotWithProvider(trieProvider)
      syncStateSchedulerActor ! StartSyncingTo(TrieRoot(target), 1)
      syncInitResponse.expectMessage(20.seconds, StateSyncFinished)
    }

  it should "sync state to different tries when peers provide different set of data each time" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    forAll(generatedWorlds) { world =>
      val trieProvider1 = TrieProvider()
      val target = trieProvider1.buildWorld(world.accounts)
      setAutoPilotWithProvider(trieProvider1, partialResponseConfig)
      syncStateSchedulerActor ! StartSyncingTo(TrieRoot(target), 1)
      syncInitResponse.expectMessage(20.seconds, StateSyncFinished)
    }

  it should "sync state to different tries when peer provide mixed responses" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    forAll(generatedWorlds) { world =>
      val trieProvider1 = TrieProvider()
      val target = trieProvider1.buildWorld(world.accounts)
      setAutoPilotWithProvider(trieProvider1, mixedResponseConfig)
      syncStateSchedulerActor ! StartSyncingTo(TrieRoot(target), 1)
      syncInitResponse.expectMessage(20.seconds, StateSyncFinished)
    }

  it should "restart state sync when requested" taggedAs (UnitTest, SyncTest) in new TestSetup():
    forAll(generatedWorlds) { world =>
      val trieProvider1 = TrieProvider()
      val target = trieProvider1.buildWorld(world.accounts)
      setAutoPilotWithProvider(trieProvider1)
      syncStateSchedulerActor ! StartSyncingTo(TrieRoot(target), 1)
      syncStateSchedulerActor ! RestartRequested
      // Stats go to syncInitStats; responses go to syncInitResponse — wait directly for WaitingForNewTargetBlock.
      syncInitResponse.expectMessage(20.seconds, WaitingForNewTargetBlock)
    }

  it should "start state sync when receiving start signal while bloom filter is loading" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup():
    override def buildBlockChain(): (BlockchainReader, BlockchainImpl) =
      val storages = getNewStorages.storages
      val blockchainReader = BlockchainReader(storages)
      (blockchainReader, BlockchainImpl(storages, blockchainReader))

    val nodeData: IndexedSeq[MptNodeData] = (0 until 1000).map(i => MptNodeData(Address(i), None, Seq(), i))
    val trieProvider1: TrieProvider = TrieProvider()
    val target: ByteString = trieProvider1.buildWorld(nodeData)
    setAutoPilotWithProvider(trieProvider1)
    syncStateSchedulerActor ! StartSyncingTo(TrieRoot(target), 1)
    syncInitResponse.expectMessage(20.seconds, StateSyncFinished)

  class TestSetup extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = StateSyncSpec.this.system.classicSystem
    type PeerConfig = Map[PeerId, PeerAction]
    // Two Typed probes — SSA now sends responses and stats to separate typed refs.
    val syncInitResponse: TypedTestProbe[SyncStateSchedulerActor.SyncStateSchedulerActorResponse] =
      testKit.createTestProbe[SyncStateSchedulerActor.SyncStateSchedulerActorResponse]()
    val syncInitStats: TypedTestProbe[SyncStateSchedulerActor.StateSyncStats] =
      testKit.createTestProbe[SyncStateSchedulerActor.StateSyncStats]()

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = true,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number.value,
      bestBlockHash = peerStatus.bestHash
    )

    val trieProvider =
      new TrieProvider(blockchain, blockchainReader, getNewStorages.storages.evmCodeStorage, blockchainConfig)

    val peersMap: Map[Peer, PeerInfo] = (1 to 8).map { i =>
      (
        Peer(
          PeerId(s"peer$i"),
          new InetSocketAddress("127.0.0.1", i),
          TestProbe(i.toString).ref.toTyped[PeerActor.Command],
          incomingConnection = false
        ),
        initialPeerInfo
      )
    }.toMap

    val blacklist: Blacklist = CacheBasedBlacklist.empty(100)

    sealed trait PeerAction
    case object FullResponse extends PeerAction
    case object PartialResponse extends PeerAction
    case object NoResponse extends PeerAction

    val defaultPeerConfig: PeerConfig = peersMap.map { case (peer, _) =>
      peer.id -> FullResponse
    }

    val maxMptNodeRequest = 50
    val minMptNodeRequest = 20
    val partialResponseConfig: PeerConfig = peersMap.map { case (peer, _) =>
      peer.id -> PartialResponse
    }

    val mixedResponseConfig: PeerConfig = peersMap.map { case (peer, _) =>
      if peer.remoteAddress.getPort <= 3 then peer.id -> FullResponse
      else if peer.remoteAddress.getPort > 3 && peer.remoteAddress.getPort <= 6 then peer.id -> PartialResponse
      else peer.id -> NoResponse
    }

    val networkPeerManager: TestProbe = TestProbe()

    // A real bus, not a probe: each typed PeerRequestHandler subscribes to it for its reply and sends SendMessageCmd
    // with no sender, so the autopilot publishes each fake-peer response here, as PeerActor publishes a wire message.
    val peerEventBus: TypedActorRef[PeerEventBusActor.Command] = testKit.spawn(PeerEventBusActor.behavior())

    def setAutoPilotWithProvider(trieProvider: TrieProvider, peerConfig: PeerConfig = defaultPeerConfig): Unit =
      networkPeerManager.setAutoPilot(
        new AutoPilot:
          override def run(sender: ActorRef, msg: Any): AutoPilot =
            msg match
              case SendMessageCmd(msg: GetNodeDataEnc, peer) =>
                peerConfig(peer) match
                  case FullResponse =>
                    val responseMsg =
                      NodeData(trieProvider.getNodes(msg.underlyingMsg.mptElementsHashes.toList).map(_.data))
                    peerEventBus ! PeerEventBusActor.PublishCmd(MessageFromPeer(responseMsg, peer))
                    this
                  case PartialResponse =>
                    val random: ThreadLocalRandom = ThreadLocalRandom.current()
                    val elementsToServe = random.nextInt(minMptNodeRequest, maxMptNodeRequest + 1)
                    val toGet = msg.underlyingMsg.mptElementsHashes.toList.take(elementsToServe)
                    val responseMsg = NodeData(trieProvider.getNodes(toGet).map(_.data))
                    peerEventBus ! PeerEventBusActor.PublishCmd(MessageFromPeer(responseMsg, peer))
                    this
                  case NoResponse =>
                    this

              case GetHandshakedPeersCmd(replyTo) =>
                replyTo ! HandshakedPeers(peersMap)
                this
      )

    override lazy val syncConfig: Config.SyncConfig = defaultSyncConfig.copy(
      peersScanInterval = 0.5.second,
      nodesPerRequest = maxMptNodeRequest,
      blacklistDuration = 1.second,
      peerResponseTimeout = 1.second,
      syncRetryInterval = 50.milliseconds
    )

    def buildBlockChain(): (BlockchainReader, BlockchainImpl) =
      val storages = getNewStorages.storages
      (
        BlockchainReader(storages),
        BlockchainImpl(storages, BlockchainReader(storages))
      )

    def genRandomArray(): Array[Byte] =
      val arr = new Array[Byte](32)
      Random.nextBytes(arr)
      arr

    def genRandomByteString(): ByteString =
      ByteString.fromArrayUnsafe(genRandomArray())

    lazy val syncStateSchedulerActor: TypedActorRef[SyncStateSchedulerActor.Command] =
      val (blockchainReader, _) = buildBlockChain()
      testKit.spawn(
        SyncStateSchedulerActor.behavior(
          SyncStateScheduler(
            blockchainReader,
            getNewStorages.storages.evmCodeStorage,
            getNewStorages.storages.stateStorage,
            getNewStorages.storages.nodeStorage,
            syncConfig.stateSyncBloomFilterSize
          ),
          syncConfig,
          networkPeerManager.ref,
          peerEventBus,
          blacklist,
          syncInitResponse.ref,
          syncInitStats.ref
        )
      )
