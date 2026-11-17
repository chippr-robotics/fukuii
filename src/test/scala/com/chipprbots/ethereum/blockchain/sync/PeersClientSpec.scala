package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor3
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerScoringManager
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags.*

class PeersClientSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks with BeforeAndAfterAll:

  import Peers.*

  private val testKit: ActorTestKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  "PeerClient" should "determine the best peer based on total difficulty" taggedAs (
    UnitTest
  ) in {
    val table: TableFor3[Map[PeerId, PeerWithInfo], Option[Peer], String] =
      Table[Map[PeerId, PeerWithInfo], Option[Peer], String](
        ("PeerInfo map", "Expected best peer", "Scenario info (selected peer)"),
        (
          Map(),
          None,
          "No peers"
        ),
        (
          Map(peer1.id -> PeerWithInfo(peer1, peerInfo(100, fork = false))),
          None,
          "Single peer"
        ),
        (
          Map(
            peer1.id -> PeerWithInfo(peer1, peerInfo(100, fork = false)),
            peer2.id -> PeerWithInfo(peer2, peerInfo(50, fork = true))
          ),
          Some(peer2),
          "Peer2 with lower TD but following the ETC fork"
        ),
        (
          Map(peer1.id -> PeerWithInfo(peer1, peerInfo(100)), peer2.id -> PeerWithInfo(peer2, peerInfo(101))),
          Some(peer2),
          "Peer2 with higher TD"
        ),
        (
          Map(
            peer1.id -> PeerWithInfo(peer1, peerInfo(100)),
            peer2.id -> PeerWithInfo(peer2, peerInfo(101)),
            peer3.id -> PeerWithInfo(peer3, peerInfo(50))
          ),
          Some(peer2),
          "Peer2 with highest TD"
        ),
        (
          Map(
            peer1.id -> PeerWithInfo(peer1, peerInfo(100)),
            peer2.id -> PeerWithInfo(peer2, peerInfo(101)),
            peer3.id -> PeerWithInfo(peer3, peerInfo(50))
          ),
          Some(peer2),
          "Peer2 with higher TD among peers"
        )
      )
    forAll(table) { (peerInfoMap, expectedPeer, _) =>
      PeersClient.bestPeer(peerInfoMap) shouldEqual expectedPeer
    }
  }

  it should "exclude peers stuck at genesis even if their TD is higher" taggedAs (UnitTest) in {
    // Sepolia/ETC reproducer: bootnode at genesis with the original POW TD (131072)
    // and a real chain-head peer with a lower TD (e.g. ETH/69 block-number proxy).
    // Pre-fix #1201: the legacy `bestPeer` selected the genesis peer because
    // its TD was higher; PivotHeaderBootstrap then asked it for a chain-head
    // header and got `no header returned`, retrying until exhausted.
    val genesisOnly = peer1.id -> PeerWithInfo(peer1, peerInfoAtGenesis(td = 17_000_000_000_000_000L.toInt))
    val chainHead = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 100))
    PeersClient.bestPeer(Map(genesisOnly, chainHead)) shouldEqual Some(peer2)
  }

  it should "return None when every available peer is at genesis" taggedAs (UnitTest) in {
    val onlyGenesis = Map(
      peer1.id -> PeerWithInfo(peer1, peerInfoAtGenesis(td = 50)),
      peer2.id -> PeerWithInfo(peer2, peerInfoAtGenesis(td = 200))
    )
    PeersClient.bestPeer(onlyGenesis) shouldEqual None
  }

  it should "filter peers by maxBlockNumber for absolute-block requests" taggedAs (UnitTest) in {
    // Sepolia repro: PivotHeaderBootstrap asks for block 10789531. Among the
    // peer pool, only peers whose advertised maxBlockNumber is at least the
    // target should be selected. Peers reporting `latestBlock=9707885` (older
    // ETH/69 peer behind chain head) literally don't have block 10789531 and
    // would return an empty headers list — matching the failure in #1201.
    val peerBehind = peer1.id -> PeerWithInfo(peer1, peerInfo(td = 9707885).copy(maxBlockNumber = 9707885))
    val peerAhead = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 100).copy(maxBlockNumber = 10789600))
    val pool = Map(peerBehind, peerAhead)
    PeersClient.bestPeerWithMinBlock(pool, BigInt(10789531)) shouldEqual Some(peer2)
  }

  it should "fall back to maxBlockNumber=0 peers when no peer is known to be ahead" taggedAs (UnitTest) in {
    // ETH/64-68 peers post-merge have maxBlockNumber=0 (no STATUS field carries
    // the block number, no incoming block messages to update). They MIGHT have
    // the block; we just don't know. Better to try them than fail outright.
    val peerUnknown =
      peer1.id -> PeerWithInfo(peer1, peerInfo(td = 17_000_000_000_000_000L.toInt).copy(maxBlockNumber = 0))
    val peerBehind = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 50).copy(maxBlockNumber = 100))
    val pool = Map(peerUnknown, peerBehind)
    PeersClient.bestPeerWithMinBlock(pool, BigInt(10789531)) shouldEqual Some(peer1)
  }

  it should "exclude tried peers from BestPeerWithMinBlockExcluding" taggedAs (UnitTest) in {
    val peerAhead = peer1.id -> PeerWithInfo(peer1, peerInfo(td = 200).copy(maxBlockNumber = 10_789_600))
    val peerAlsoAhead = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 100).copy(maxBlockNumber = 10_789_600))
    // peer1 excluded (already tried) → peer2 selected despite lower TD
    PeersClient.bestPeerWithMinBlockExcluding(
      Map(peerAhead, peerAlsoAhead),
      BigInt(10_789_531),
      exclude = Set(peer1.id)
    ) shouldEqual Some(peer2)
  }

  it should "return None from BestPeerWithMinBlockExcluding when all eligible peers are excluded" taggedAs (
    UnitTest
  ) in {
    val onlyPeer = peer1.id -> PeerWithInfo(peer1, peerInfo(td = 200).copy(maxBlockNumber = 10_789_600))
    PeersClient.bestPeerWithMinBlockExcluding(
      Map(onlyPeer),
      BigInt(10_789_531),
      exclude = Set(peer1.id)
    ) shouldEqual None
  }

  // ---- spec 027 slice 4a: PeersClient feeds the peer scorer -------------------------------------------------------

  private def newScorer(): PeerScoringManager =
    new PeerScoringManager(java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC))

  "PeersClient peer scoring" should "record a response with the time the request took" taggedAs UnitTest in {
    val scorer = newScorer()
    PeersClient.recordPeerScore(scorer, PeerRequestHandler.ResponseReceived(1, peer1, "response", timeTaken = 250L))

    val score = scorer.getScore(peer1.id)
    score.responsesReceived shouldBe 1
    score.averageLatencyMs shouldBe Some(250.0)
    score.requestsTimedOut shouldBe 0
    scorer.getAllScores.keySet shouldBe Set(peer1.id)
  }

  it should "record a timeout when the request timed out" taggedAs UnitTest in {
    val scorer = newScorer()
    PeersClient.recordPeerScore(scorer, PeerRequestHandler.RequestFailed(1, peer1, "request timeout"))

    val score = scorer.getScore(peer1.id)
    score.requestsTimedOut shouldBe 1
    score.responsesReceived shouldBe 0
  }

  it should "record nothing when the connection closed, which is not the peer's fault" taggedAs UnitTest in {
    val scorer = newScorer()
    PeersClient.recordPeerScore(scorer, PeerRequestHandler.RequestFailed(1, peer1, "connection closed"))
    PeersClient.recordPeerScore(scorer, PeerRequestHandler.RequestFailed(2, peer1, "any other reason"))

    scorer.getAllScores shouldBe empty
  }

  /** The real PeersClient and PeerRequestHandler on a probe peer manager and a real event bus. */
  private class ScoredClient(responseTimeout: FiniteDuration):
    val scorer: PeerScoringManager = newScorer()
    val peerManager: TypedTestProbe[NetworkPeerManagerActor.Command] =
      testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val bus: TypedActorRef[PeerEventBusActor.Command] =
      testKit.spawn(PeerEventBusActor.behavior(), s"peers-client-bus-${java.util.UUID.randomUUID()}")
    val replies: TypedTestProbe[PeersClient.ResponseMessage] = testKit.createTestProbe[PeersClient.ResponseMessage]()
    private val syncConfig = new TestSyncConfig {}.defaultSyncConfig.copy(peerResponseTimeout = responseTimeout)
    val client: TypedActorRef[PeersClient.Command] = testKit.spawn(
      PeersClient.behavior(peerManager.ref, bus, CacheBasedBlacklist.empty(10), syncConfig, Some(scorer)),
      s"peers-client-${java.util.UUID.randomUUID()}"
    )
    // PeersClient asks for the handshaked peers when it starts; the reply is queued before any request below.
    peerManager.expectMessageType[NetworkPeerManagerActor.GetHandshakedPeersCmd].replyTo !
      NetworkPeerManagerActor.HandshakedPeers(Map(peer1 -> peerInfo(100)))

    val request: ETHPackets.GetBlockHeaders = ETHPackets.GetBlockHeaders(7, Left(1), 1, 0, reverse = false)
    client ! PeersClient.Request.create(request, PeersClient.BestPeer)(using
      ETHPackets.GetBlockHeaders.GetBlockHeadersEnc(_)
    )(replies.ref)
    // The handler subscribes to the bus before it sends the request, so a reply published after this is delivered.
    peerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd]

  it should "score the peer that answered a real request" taggedAs UnitTest in {
    val f = new ScoredClient(responseTimeout = 30.seconds)
    f.bus ! PeerEventBusActor.PublishCmd(
      PeerEventBusActor.PeerEvent.MessageFromPeer(ETHPackets.BlockHeaders(7, Seq.empty), peer1.id)
    )
    f.replies.expectMessageType[PeersClient.Response[?]]

    val score = f.scorer.getScore(peer1.id)
    score.responsesReceived shouldBe 1
    score.averageLatencyMs should not be empty
    score.requestsTimedOut shouldBe 0
  }

  it should "score the peer that let a real request time out" taggedAs UnitTest in {
    val f = new ScoredClient(responseTimeout = 300.millis)
    f.replies.expectMessageType[PeersClient.RequestFailed](10.seconds)

    val score = f.scorer.getScore(peer1.id)
    score.requestsTimedOut shouldBe 1
    score.responsesReceived shouldBe 0
  }

  it should "not score the peer whose connection closed during a real request" taggedAs UnitTest in {
    val f = new ScoredClient(responseTimeout = 30.seconds)
    f.bus ! PeerEventBusActor.PublishCmd(PeerEventBusActor.PeerEvent.PeerDisconnected(peer1.id))
    f.replies.expectMessageType[PeersClient.RequestFailed](10.seconds)

    f.scorer.getAllScores shouldBe empty
  }

  object Peers:
    implicit val system: ActorSystem = ActorSystem("PeersClient_System")

    val peer1: Peer =
      Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), TestProbe().ref.toTyped[PeerActor.Command], false)
    val peer2: Peer =
      Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 2), TestProbe().ref.toTyped[PeerActor.Command], false)
    val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.1", 3), TestProbe().ref, false)

    // Distinct bestHash and genesisHash so isAtGenesis returns false by default.
    // Tests that need a genesis-only peer can override via `peerInfoAtGenesis`.
    private val genesisHash = ByteString("genesis-hash")
    private val chainHeadHash = ByteString("chain-head-hash")

    private val peerStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.zero,
      bestHash = chainHeadHash,
      genesisHash = genesisHash
    )

    def peerInfo(td: Int, fork: Boolean = true): PeerInfo =
      PeerInfo(
        peerStatus,
        ChainWeight.totalDifficultyOnly(BigInt(td)),
        forkAccepted = fork,
        maxBlockNumber = 42,
        bestBlockHash = chainHeadHash
      )

    /** A peer that has handshaked but is stuck at genesis — bestHash == genesisHash. Common on Sepolia where bootnodes
      * are fresh-state and on ETC where some peers advertise SNAP/1 but never advance past genesis. They literally have
      * no chain data to serve.
      *
      * maxBlockNumber = 0: genesis peers have not processed any blocks beyond block 0, so no block number is known.
      * ETH/69 sets latestBlock=0 from STATUS; ETH/68 stays at the default 0 (no block number in STATUS).
      */
    def peerInfoAtGenesis(td: Int): PeerInfo =
      peerInfo(td).copy(
        remoteStatus = peerStatus.copy(bestHash = genesisHash),
        bestBlockHash = genesisHash,
        maxBlockNumber = 0
      )
