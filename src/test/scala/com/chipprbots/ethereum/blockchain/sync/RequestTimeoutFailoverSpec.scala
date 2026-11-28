package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher
import com.chipprbots.ethereum.blockchain.sync.regular.HeadersFetcher
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.BlockedIPRegistry
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
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** B-434: a regular-sync header request to a peer that never answers must not be retried against that peer. Peer A is
  * the best by total difficulty and never answers; peer B has the headers. The timeout comes from a real
  * PeerRequestHandler on a short `peer-response-timeout`, so no test waits on the production 90 s. The blacklist is the
  * node's production stack (operator block list over scoring over the cache), the scorer is wired as in the node, and
  * the second test drives the real HeadersFetcher. A blacklisting starts when the request fails, not when it was sent,
  * so the retry that follows the timeout by one retry interval finds the peer excluded whatever the 90 s timeout and
  * the 60 s default duration are.
  */
class RequestTimeoutFailoverSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  private def peer(name: String, port: Int): Peer =
    Peer(PeerId(name), new InetSocketAddress("127.0.0.1", port), TestInbox[PeerActor.Command]().ref, false)

  private val peerA = peer("peer-a", 1)
  private val peerB = peer("peer-b", 2)

  private def info(td: Int): PeerInfo =
    val status = RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(BigInt(td)),
      ByteString("head"),
      ByteString("genesis")
    )
    PeerInfo(status, status.chainWeight, forkAccepted = true, maxBlockNumber = 42, bestBlockHash = status.bestHash)

  private class Fixture(responseTimeout: FiniteDuration):
    val peerManager: TestProbe[NetworkPeerManagerActor.Command] = testKit.createTestProbe()
    val replies: TestProbe[PeersClient.ResponseMessage] = testKit.createTestProbe()
    val bus: ActorRef[PeerEventBusActor.Command] =
      testKit.spawn(PeerEventBusActor.behavior(), s"failover-bus-${java.util.UUID.randomUUID()}")
    val syncConfig: SyncConfig = new TestSyncConfig {}.defaultSyncConfig.copy(peerResponseTimeout = responseTimeout)
    private val productionBlacklist: Blacklist =
      new OperatorBlockingBlacklist(
        new ScoringBlacklist(CacheBasedBlacklist.empty(1000), new PeerScoringManager()),
        new BlockedIPRegistry(Set.empty)
      )
    val client: ActorRef[PeersClient.Command] = testKit.spawn(
      PeersClient.behavior(peerManager.ref, bus, productionBlacklist, syncConfig, Some(new PeerScoringManager())),
      s"failover-client-${java.util.UUID.randomUUID()}"
    )
    peerManager.expectMessageType[NetworkPeerManagerActor.GetHandshakedPeersCmd].replyTo !
      NetworkPeerManagerActor.HandshakedPeers(Map(peerA -> info(200), peerB -> info(100)))

    private var requestId = 0

    /** Issue a best-peer header request and return the peer it was sent to. */
    def requestHeaders(): PeerId =
      requestId += 1
      val request = ETHPackets.GetBlockHeaders(requestId, Left(1), 1, 0, reverse = false)
      client ! PeersClient.Request.create(request, PeersClient.BestPeer)(using
        ETHPackets.GetBlockHeaders.GetBlockHeadersEnc(_)
      )(replies.ref)
      awaitSend()

    def awaitSendTo(): PeerId = awaitSend()

    // The scan tick may re-ask for the handshaked peers before the request goes out; the reply is idempotent.
    @scala.annotation.tailrec
    private def awaitSend(): PeerId =
      peerManager.expectMessageType[NetworkPeerManagerActor.Command](10.seconds) match
        case NetworkPeerManagerActor.SendMessageCmd(_, peerId) => peerId
        case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
          replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peerA -> info(200), peerB -> info(100)))
          awaitSend()
        case other => fail(s"unexpected $other")

  "A header request that times out" should "make the next request go to another peer, not the same one" taggedAs UnitTest in {
    val f = new Fixture(responseTimeout = 200.millis)

    f.requestHeaders() shouldBe peerA.id
    // What FetchRequest does with the failed request: report the peer back to PeersClient as blacklisted.
    val failed = f.replies.expectMessageType[PeersClient.RequestFailed](10.seconds)
    failed.peer.id shouldBe peerA.id
    f.client ! PeersClient.BlacklistPeer(failed.peer.id, failed.reason)

    f.requestHeaders() shouldBe peerB.id
  }

  "The headers fetcher" should "send its retry after a timeout to another peer" taggedAs UnitTest in {
    val f = new Fixture(responseTimeout = 200.millis)
    val supervisor = testKit.createTestProbe[BlockFetcher.FetchCommand]()
    val fetcher = testKit.spawn(
      HeadersFetcher(f.client, f.syncConfig.copy(syncRetryInterval = 10.millis), supervisor.ref),
      s"failover-headers-${java.util.UUID.randomUUID()}"
    )

    fetcher ! HeadersFetcher.FetchHeadersByNumber(1, 1)
    f.awaitSendTo() shouldBe peerA.id
    supervisor.expectMessage(10.seconds, BlockFetcher.RetryHeadersRequest)
    fetcher ! HeadersFetcher.FetchHeadersByNumber(1, 1)
    f.awaitSendTo() shouldBe peerB.id
  }
