package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import scala.concurrent.duration._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.Unsubscribe
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags._

// Regression tests for Issues 6 and 8: dual-connection races between inbound and
// outbound TCP connections to the same peer. All assertions are behavioural: they
// observe Subscribe / Unsubscribe messages sent to the peerEventBus TestProbe rather
// than inspecting internal state. This means the tests are robust to internal
// refactors while still covering the critical paths.
class NetworkPeerManagerActorHandshakeSpec
    extends TestKit(ActorSystem("NetworkPeerManagerActorHandshakeSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val nodeIdHex = "aa" * 64
  private val nodeIdBytes = ByteString(Hex.decode(nodeIdHex))
  private val peerId = PeerId(nodeIdHex)

  private val outboundAddr = new InetSocketAddress("127.0.0.1", 30304)
  private val inboundAddr = new InetSocketAddress("127.0.0.1", 55555)

  // ETH/69 skips the best-block probe (STATUS carries latestBlock), so the peerManager
  // TestProbe stays silent and assertions on the bus are unambiguous.
  private val peerStatus = RemoteStatus(
    capability = Capability.ETH69,
    networkId = 61L,
    chainWeight = ChainWeight.totalDifficultyOnly(10000),
    bestHash = Fixtures.Blocks.Block3125369.header.hash,
    genesisHash = Fixtures.Blocks.Genesis.header.hash
  )
  private val peerInfo = PeerInfo(peerStatus, forkAccepted = true)

  // ── helpers ──────────────────────────────────────────────────────────────────

  private def newNpma(): (TestActorRef[NetworkPeerManagerActor], TestProbe, TestProbe) = {
    val pm = TestProbe()
    val bus = TestProbe()
    val ref = TestActorRef[NetworkPeerManagerActor](
      NetworkPeerManagerActor.props(
        pm.ref,
        bus.ref,
        new AppStateStorage(EphemDataSource()),
        forkResolverOpt = None,
        isPoWChain = false
      )
    )
    (ref, pm, bus)
  }

  // NPMA sends two Subscribes to peerEventBusActor at construction:
  //   1. Subscribe(PeerHandshaked)
  //   2. Subscribe(MessageClassifier(SNAP request codes, AllPeers))
  // Drain them before running per-test assertions.
  private def drainInitialSubscriptions(bus: TestProbe): Unit = {
    bus.expectMsgType[Subscribe]
    bus.expectMsgType[Subscribe]
  }

  private def outboundPeer(): Peer =
    Peer(peerId, outboundAddr, TestProbe().ref, incomingConnection = false, nodeId = Some(nodeIdBytes))

  private def outboundPeer2(): Peer =
    Peer(
      peerId,
      new InetSocketAddress("127.0.0.1", 30305),
      TestProbe().ref,
      incomingConnection = false,
      nodeId = Some(nodeIdBytes)
    )

  private def inboundPeer(): Peer =
    Peer(peerId, inboundAddr, TestProbe().ref, incomingConnection = true, nodeId = Some(nodeIdBytes))

  // ── tests ────────────────────────────────────────────────────────────────────

  // A1 — baseline: first handshake registers the peer.
  "NetworkPeerManagerActor" should "send Subscribe messages for a new outbound peer on PeerHandshakeSuccessful" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)

    npma ! PeerHandshakeSuccessful(outboundPeer(), peerInfo)

    bus.expectMsgType[Subscribe] // PeerDisconnectedClassifier
    bus.expectMsgType[Subscribe] // MessageClassifier
    bus.expectNoMessage(100.millis)
  }

  // A2 — inbound-wins swap: duplicate handshake must NOT produce a second pair of Subscribes.
  // Regression for Issue 8 — NPMA previously overwrote peersWithInfo with the duplicate ref,
  // causing stale outbound dispatches after the connection was swapped.
  it should "not send additional Subscribe messages when inbound wins over existing outbound (same PeerId)" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)

    npma ! PeerHandshakeSuccessful(outboundPeer(), peerInfo)
    bus.expectMsgType[Subscribe]
    bus.expectMsgType[Subscribe]

    npma ! PeerHandshakeSuccessful(inboundPeer(), peerInfo) // same PeerId, inbound wins
    bus.expectNoMessage(100.millis)
  }

  // B1 — suppression: the outbound PeerActor dies after inbound-wins and fires PeerDisconnected.
  // That disconnect must be suppressed (pendingInboundWinsDisconnects gate) so the inbound
  // entry is not evicted from peersWithInfo. Regression for Issue 8.
  it should "suppress PeerDisconnected for the dying outbound after inbound-wins" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerHandshakeSuccessful(outboundPeer(), peerInfo)
    bus.expectMsgType[Subscribe]; bus.expectMsgType[Subscribe]
    npma ! PeerHandshakeSuccessful(inboundPeer(), peerInfo)
    bus.expectNoMessage(100.millis)

    npma ! PeerDisconnected(peerId) // outbound dying — should be suppressed
    bus.expectNoMessage(300.millis)
  }

  // B2 — retention: after the suppressed outbound disconnect, the inbound is still live.
  // A subsequent genuine disconnect must evict it normally.
  it should "retain the inbound peer after outbound PeerDisconnected is suppressed" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerHandshakeSuccessful(outboundPeer(), peerInfo)
    bus.expectMsgType[Subscribe]; bus.expectMsgType[Subscribe]
    npma ! PeerHandshakeSuccessful(inboundPeer(), peerInfo)
    npma ! PeerDisconnected(peerId) // outbound dying — suppressed
    bus.expectNoMessage(200.millis)

    // Genuine disconnect from the inbound must now evict the peer normally
    npma ! PeerDisconnected(peerId)
    bus.expectMsgType[Unsubscribe]
    bus.expectMsgType[Unsubscribe]
  }

  // C1 — drop: second outbound for the same PeerId must be silently dropped.
  it should "drop a second outbound for the same PeerId without adding a duplicate Subscribe" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerHandshakeSuccessful(outboundPeer(), peerInfo)
    bus.expectMsgType[Subscribe]; bus.expectMsgType[Subscribe]

    npma ! PeerHandshakeSuccessful(outboundPeer2(), peerInfo) // duplicate outbound — DROPPED
    bus.expectNoMessage(100.millis)

    // Original peer is still retained
    npma ! PeerDisconnected(peerId)
    bus.expectMsgType[Unsubscribe]
    bus.expectMsgType[Unsubscribe]
  }

  // C2 — drop: second inbound for the same PeerId must also be silently dropped.
  it should "drop a second inbound for the same PeerId without adding a duplicate Subscribe" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerHandshakeSuccessful(inboundPeer(), peerInfo)
    bus.expectMsgType[Subscribe]; bus.expectMsgType[Subscribe]

    npma ! PeerHandshakeSuccessful(
      Peer(
        peerId,
        new InetSocketAddress("127.0.0.1", 55556),
        TestProbe().ref,
        incomingConnection = true,
        nodeId = Some(nodeIdBytes)
      ),
      peerInfo
    )
    bus.expectNoMessage(100.millis)

    // Original peer is still retained
    npma ! PeerDisconnected(peerId)
    bus.expectMsgType[Unsubscribe]
    bus.expectMsgType[Unsubscribe]
  }

  // D — eviction: genuine disconnect evicts the peer; a second PeerDisconnected is ignored.
  it should "evict a peer on genuine PeerDisconnected and ignore a subsequent one for the same PeerId" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerHandshakeSuccessful(outboundPeer(), peerInfo)
    bus.expectMsgType[Subscribe]; bus.expectMsgType[Subscribe]

    npma ! PeerDisconnected(peerId)
    bus.expectMsgType[Unsubscribe]
    bus.expectMsgType[Unsubscribe]

    // Peer is now gone — second disconnect must be ignored (no Unsubscribes)
    npma ! PeerDisconnected(peerId)
    bus.expectNoMessage(200.millis)
  }
}
