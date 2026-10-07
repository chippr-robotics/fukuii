package com.chipprbots.ethereum.blockchain.sync.fast

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.*
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolutionFailed
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolutionFailed.NoCommonBlockFound
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolvedSuccessful
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.StartBranchResolver
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.*
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders as ETHBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Logger

class FastSyncBranchResolverActorSpec
    extends ScalaTestWithActorTestKit()
    with AnyFreeSpecLike
    with ScalaFutures
    with NormalPatience:
  self =>
  implicit override val timeout: Timeout = Timeout(30.seconds)

  import FastSyncBranchResolverActorSpec.*

  "FastSyncBranchResolver" - {
    "fetch headers from the new master peer" - {
      "the chain is repaired from the first request to the new master pair and then the last two blocks are removed" taggedAs (
        UnitTest,
        SyncTest
      ) in new TestSetup:
        implicit override lazy val system: ActorSystem = self.system.classicSystem
        implicit override lazy val ioRuntime: IORuntime = IORuntime.global

        val sender: TestProbe = TestProbe("sender")

        val commonBlocks: List[Block] = BlockHelpers.generateChain(
          5,
          BlockHelpers.genesis,
          block => block
        )

        val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(
          1,
          commonBlocks.last,
          block => block
        )

        val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(
          2,
          commonBlocks.last,
          block => block
        )

        val firstBatchBlockHeaders: List[Block] =
          blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

        val blocksSentFromPeer: Map[Int, List[Block]] = Map(1 -> firstBatchBlockHeaders)

        saveBlocks(blocksSaved)
        val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
        val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

        val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
          case r @ BranchResolvedSuccessful(num, _) if num == BigInt(5) => r
        }

        val response: BranchResolvedSuccessful = (for
          _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
          response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
          _ <- IO(stopController(fastSyncBranchResolver))
        yield response).unsafeRunSync()
        assert(getBestPeers.contains(response.masterPeer))

      "The chain is repaired doing binary searching with the new master peer and then remove the last invalid blocks" - {
        "highest common block is in the middle" taggedAs (UnitTest, SyncTest) in new TestSetup:
          implicit override lazy val system: ActorSystem = self.system.classicSystem
          implicit override lazy val ioRuntime: IORuntime = IORuntime.global

          val sender: TestProbe = TestProbe("sender")

          val commonBlocks: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
          val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(5, commonBlocks.last)
          val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(6, commonBlocks.last)

          val firstBatchBlockHeaders: List[Block] =
            blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

          val blocksSentFromPeer: Map[Int, List[Block]] = Map(
            1 -> firstBatchBlockHeaders,
            2 -> List(blocksSavedInPeer.get(5).get),
            3 -> List(blocksSavedInPeer.get(7).get),
            4 -> List(blocksSavedInPeer.get(5).get),
            5 -> List(blocksSavedInPeer.get(6).get)
          )

          saveBlocks(blocksSaved)
          val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
          val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
            creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

          val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
            case r @ BranchResolvedSuccessful(num, _) if num == BigInt(5) => r
          }

          val response: BranchResolvedSuccessful = (for
            _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
            response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
            _ <- IO(stopController(fastSyncBranchResolver))
          yield response).unsafeRunSync()
          assert(getBestPeers.contains(response.masterPeer))
        "highest common block is in the first half" taggedAs (UnitTest, SyncTest) in new TestSetup:
          implicit override lazy val system: ActorSystem = self.system.classicSystem
          implicit override lazy val ioRuntime: IORuntime = IORuntime.global

          val sender: TestProbe = TestProbe("sender")

          val commonBlocks: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
          val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(7, commonBlocks.last)
          val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(8, commonBlocks.last)

          val firstBatchBlockHeaders: List[Block] =
            blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

          val blocksSentFromPeer: Map[Int, List[Block]] = Map(
            1 -> firstBatchBlockHeaders,
            2 -> List(blocksSavedInPeer.get(5).get),
            3 -> List(blocksSavedInPeer.get(2).get),
            4 -> List(blocksSavedInPeer.get(3).get),
            5 -> List(blocksSavedInPeer.get(3).get),
            6 -> List(blocksSavedInPeer.get(4).get)
          )

          saveBlocks(blocksSaved)
          val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
          val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
            creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

          val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
            case r @ BranchResolvedSuccessful(num, _) if num == BigInt(3) => r
          }

          val response: BranchResolvedSuccessful = (for
            _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
            response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
            _ <- IO(stopController(fastSyncBranchResolver))
          yield response).unsafeRunSync()
          assert(getBestPeers.contains(response.masterPeer))

        "highest common block is in the second half" taggedAs (UnitTest, SyncTest) in new TestSetup:
          implicit override lazy val system: ActorSystem = self.system.classicSystem
          implicit override lazy val ioRuntime: IORuntime = IORuntime.global

          val sender: TestProbe = TestProbe("sender")

          val commonBlocks: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)
          val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(4, commonBlocks.last)
          val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(5, commonBlocks.last)

          val firstBatchBlockHeaders: List[Block] =
            blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

          val blocksSentFromPeer: Map[Int, List[Block]] = Map(
            1 -> firstBatchBlockHeaders,
            2 -> List(blocksSavedInPeer.get(5).get),
            3 -> List(blocksSavedInPeer.get(7).get),
            4 -> List(blocksSavedInPeer.get(5).get),
            5 -> List(blocksSavedInPeer.get(6).get)
          )

          saveBlocks(blocksSaved)
          val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
          val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
            creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

          val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
            case r @ BranchResolvedSuccessful(num, _) if num == BigInt(6) => r
          }

          val response: BranchResolvedSuccessful = (for
            _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
            response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
            _ <- IO(stopController(fastSyncBranchResolver))
          yield response).unsafeRunSync()
          assert(getBestPeers.contains(response.masterPeer))
      }

      "No common block is found" taggedAs (UnitTest, SyncTest) in new TestSetup:
        implicit override lazy val system: ActorSystem = self.system.classicSystem
        implicit override lazy val ioRuntime: IORuntime = IORuntime.global

        val sender: TestProbe = TestProbe("sender")

        // same genesis block but no common blocks
        val blocksSaved: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val blocksSavedInPeer: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)

        val firstBatchBlockHeaders: List[Block] =
          blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

        val blocksSentFromPeer: Map[Int, List[Block]] = Map(
          1 -> firstBatchBlockHeaders,
          2 -> List(blocksSavedInPeer.get(3).get),
          3 -> List(blocksSavedInPeer.get(1).get),
          4 -> List(blocksSavedInPeer.get(1).get)
        )

        saveBlocks(blocksSaved)
        val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
        val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

        log.debug(s"*** peers: ${handshakedPeers.map(p => (p._1.id, p._2.maxBlockNumber))}")
        (for
          _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
          response <- IO(sender.expectMsg(branchResolutionTimeout, BranchResolutionFailed(NoCommonBlockFound)))
          _ <- IO(stopController(fastSyncBranchResolver))
        yield response).unsafeRunSync()
    }
  }

  trait TestSetup extends EphemBlockchainTestSetup with TestSyncConfig with TestSyncPeers:

    protected val branchResolutionTimeout: FiniteDuration = syncConfig.peerResponseTimeout + 2.seconds

    def peerId(number: Int): PeerId = PeerId(s"peer_$number")
    def getPeer(id: PeerId): Peer =
      Peer(id, new InetSocketAddress("127.0.0.1", 0), TestProbe(id.value).ref, incomingConnection = false)
    def getPeerInfo(peer: Peer): PeerInfo =
      val status =
        RemoteStatus(
          Capability.ETH68,
          1,
          ChainWeight.totalDifficultyOnly(1),
          ByteString(s"${peer.id}_bestHash"),
          ByteString("unused")
        )
      PeerInfo(
        status,
        forkAccepted = true,
        chainWeight = status.chainWeight,
        maxBlockNumber = Random.between(1, 10),
        bestBlockHash = status.bestHash
      )

    val handshakedPeers: Map[Peer, PeerInfo] =
      (0 to 5).toList.map(peerId.andThen(getPeer)).fproduct(getPeerInfo(_)).toMap

    // A real bus, not a probe: the resolver's typed PeerRequestHandler children subscribe to it for the reply, and the
    // fake network peer manager publishes each response to it, as PeerActor publishes a decoded wire message.
    lazy val peerEventBus: TypedActorRef[PeerEventBusActor.Command] =
      self.testKit.spawn(PeerEventBusActor.behavior())

    def saveBlocks(blocks: List[Block]): Unit =
      blocks.foreach(block =>
        blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(1), saveAsBestBlock = true)
      )

    def createNetworkPeerManager(peers: Map[Peer, PeerInfo], blocks: Map[Int, List[Block]])(implicit
        ioRuntime: IORuntime
    ): ActorRef =
      val networkPeerManager = TestProbe("network_peer_manager")
      val autoPilot =
        new NetworkPeerManagerAutoPilot(
          peersConnectedDeferred,
          peers,
          blocks,
          peerEventBus
        )
      networkPeerManager.setAutoPilot(autoPilot)
      networkPeerManager.ref

    def creatFastSyncBranchResolver(
        fastSync: ActorRef,
        networkPeerManager: ActorRef,
        blacklist: Blacklist
    ): TypedActorRef[FastSyncBranchResolverActor.Command] =
      self.testKit.spawn(
        FastSyncBranchResolverActor(
          replyTo = fastSync.toTyped[FastSyncBranchResolverActor.BranchResolverResponse],
          peerEventBus = peerEventBus,
          networkPeerManager = networkPeerManager,
          blockchain = blockchain,
          blockchainReader = blockchainReader,
          blacklist = blacklist,
          syncConfig = syncConfig
        ),
        s"fast-sync-branch-resolver-${java.util.UUID.randomUUID()}"
      )

    def stopController(actorRef: TypedActorRef[FastSyncBranchResolverActor.Command]): Unit =
      self.testKit.stop(actorRef)

    def getBestPeers: List[Peer] =
      val maxBlock = handshakedPeers.toList.map { case (_, peerInfo) => peerInfo.maxBlockNumber }.max
      handshakedPeers.toList.filter { case (_, peerInfo) => peerInfo.maxBlockNumber == maxBlock }.map(_._1)

object FastSyncBranchResolverActorSpec extends Logger:

  private val BlacklistMaxElements: Int = 100

  private val peersConnectedDeferred = Deferred.unsafe[IO, Unit]

  class NetworkPeerManagerAutoPilot(
      peersConnected: Deferred[IO, Unit],
      peers: Map[Peer, PeerInfo],
      blocks: Map[Int, List[Block]],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command]
  )(implicit ioRuntime: IORuntime)
      extends AutoPilot:

    var blockIndex = 0
    lazy val blocksSetSize = blocks.size

    def run(sender: ActorRef, msg: Any): NetworkPeerManagerAutoPilot =
      msg match
        case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
          replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers)
          peersConnected.complete(()).handleError(_ => ()).unsafeRunSync()
        case NetworkPeerManagerActor.SendMessageCmd(rawMsg, peerId) =>
          val response = rawMsg.underlyingMsg match
            case req: ETHGetBlockHeaders if !req.reverse =>
              if blockIndex < blocksSetSize then blockIndex += 1
              ETHBlockHeaders(req.requestId, blocks.get(blockIndex).map(_.map(_.header)).getOrElse(Nil))
            case other =>
              throw new RuntimeException(s"Unexpected message sent to NetworkPeerManagerAutoPilot: $other")
          // A typed PeerRequestHandler sends SendMessageCmd with no sender and waits on the bus for the reply.
          peerEventBus ! PeerEventBusActor.PublishCmd(MessageFromPeer(response, peerId))
          if blockIndex == blocksSetSize then ()
      this
