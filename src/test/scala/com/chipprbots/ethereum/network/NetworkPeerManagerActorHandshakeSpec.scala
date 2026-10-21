package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerEventCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeCmd
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

// Regression tests for Issues 6 and 8: dual-connection races between inbound and
// outbound TCP connections to the same peer. All assertions are behavioural: they
// observe Subscribe / Unsubscribe messages sent to the peerEventBus TestProbe rather
// than inspecting internal state. This means the tests are robust to internal
// refactors while still covering the critical paths.
class NetworkPeerManagerActorHandshakeSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

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
    bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
    genesisHash = Fixtures.Blocks.Genesis.header.hash.value
  )
  private val peerInfo = PeerInfo(peerStatus, forkAccepted = true)

  // ── helpers ──────────────────────────────────────────────────────────────────

  private def newNpma(): (ActorRef, TestProbe, TestProbe) =
    val pm = TestProbe()
    val bus = TestProbe()
    val ref = testKit
      .spawn(
        NetworkPeerManagerActor.behavior(
          pm.ref.toTyped[PeerManagerActor.Command],
          bus.ref.toTyped[PeerEventBusActor.Command],
          new AppStateStorage(EphemDataSource()),
          forkResolverOpt = None,
          isPoWChain = false
        ),
        s"npma-handshake-${java.util.UUID.randomUUID()}"
      )
      .toClassic
    (ref, pm, bus)

  // NPMA sends two Subscribes to peerEventBusActor at construction:
  //   1. Subscribe(PeerHandshaked)
  //   2. Subscribe(MessageClassifier(SNAP request codes, AllPeers))
  // Drain them before running per-test assertions.
  private def drainInitialSubscriptions(bus: TestProbe): Unit =
    bus.expectMsgType[SubscribeCmd]
    bus.expectMsgType[SubscribeCmd]

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

    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer(), peerInfo))

    bus.expectMsgType[SubscribeCmd] // PeerDisconnectedClassifier
    bus.expectMsgType[SubscribeCmd] // MessageClassifier
    bus.expectNoMessage(100.millis)
  }

  // A2 — inbound-wins swap: duplicate handshake must NOT produce a second pair of Subscribes.
  // Regression for Issue 8 — NPMA previously overwrote peersWithInfo with the duplicate ref,
  // causing stale outbound dispatches after the connection was swapped.
  it should "not send additional Subscribe messages when inbound wins over existing outbound (same PeerId)" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)

    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer(), peerInfo))
    bus.expectMsgType[SubscribeCmd]
    bus.expectMsgType[SubscribeCmd]

    npma ! PeerEventCmd(PeerHandshakeSuccessful(inboundPeer(), peerInfo)) // same PeerId, inbound wins
    bus.expectNoMessage(100.millis)
  }

  // B1 — suppression: the outbound PeerActor dies after inbound-wins and fires PeerDisconnected.
  // That disconnect must be suppressed (pendingInboundWinsDisconnects gate) so the inbound
  // entry is not evicted from peersWithInfo. Regression for Issue 8.
  it should "suppress PeerDisconnected for the dying outbound after inbound-wins" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer(), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]
    npma ! PeerEventCmd(PeerHandshakeSuccessful(inboundPeer(), peerInfo))
    bus.expectNoMessage(100.millis)

    npma ! PeerEventCmd(PeerDisconnected(peerId)) // outbound dying — should be suppressed
    bus.expectNoMessage(300.millis)
  }

  // B2 — retention: after the suppressed outbound disconnect, the inbound is still live.
  // A subsequent genuine disconnect must evict it normally.
  it should "retain the inbound peer after outbound PeerDisconnected is suppressed" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer(), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]
    npma ! PeerEventCmd(PeerHandshakeSuccessful(inboundPeer(), peerInfo))
    npma ! PeerEventCmd(PeerDisconnected(peerId)) // outbound dying — suppressed
    bus.expectNoMessage(200.millis)

    // Genuine disconnect from the inbound must now evict the peer normally
    npma ! PeerEventCmd(PeerDisconnected(peerId))
    bus.expectMsgType[UnsubscribeCmd]
    bus.expectMsgType[UnsubscribeCmd]
  }

  // C1 — drop: second outbound for the same PeerId must be silently dropped.
  it should "drop a second outbound for the same PeerId without adding a duplicate Subscribe" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer(), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]

    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer2(), peerInfo)) // duplicate outbound — DROPPED
    bus.expectNoMessage(100.millis)

    // Original peer is still retained
    npma ! PeerEventCmd(PeerDisconnected(peerId))
    bus.expectMsgType[UnsubscribeCmd]
    bus.expectMsgType[UnsubscribeCmd]
  }

  // C2 — drop: second inbound for the same PeerId must also be silently dropped.
  it should "drop a second inbound for the same PeerId without adding a duplicate Subscribe" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(inboundPeer(), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]

    npma ! PeerEventCmd(
      PeerHandshakeSuccessful(
        Peer(
          peerId,
          new InetSocketAddress("127.0.0.1", 55556),
          TestProbe().ref,
          incomingConnection = true,
          nodeId = Some(nodeIdBytes)
        ),
        peerInfo
      )
    )
    bus.expectNoMessage(100.millis)

    // Original peer is still retained
    npma ! PeerEventCmd(PeerDisconnected(peerId))
    bus.expectMsgType[UnsubscribeCmd]
    bus.expectMsgType[UnsubscribeCmd]
  }

  // D — eviction: genuine disconnect evicts the peer; a second PeerDisconnected is ignored.
  it should "evict a peer on genuine PeerDisconnected and ignore a subsequent one for the same PeerId" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(outboundPeer(), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]

    npma ! PeerEventCmd(PeerDisconnected(peerId))
    bus.expectMsgType[UnsubscribeCmd]
    bus.expectMsgType[UnsubscribeCmd]

    // Peer is now gone — second disconnect must be ignored (no Unsubscribes)
    npma ! PeerEventCmd(PeerDisconnected(peerId))
    bus.expectNoMessage(200.millis)
  }

  // ── Standby connections (death watch) ────────────────────────────────────────
  // The current entry's actor dying must hand the peer ID over to a live second connection (the outbound an
  // inbound-wins swap displaced, or a dropped duplicate) instead of dropping the peer, and a standby whose own actor
  // died must never take over.

  private def peerOn(probe: TestProbe, addr: InetSocketAddress, inbound: Boolean): Peer =
    Peer(peerId, addr, probe.ref, incomingConnection = inbound, nodeId = Some(nodeIdBytes))

  private def stopAndAwait(probe: TestProbe): Unit =
    val watcher = TestProbe()
    watcher.watch(probe.ref)
    classicSystem.stop(probe.ref)
    watcher.expectTerminated(probe.ref)

  private def expectPeerRemoved(bus: TestProbe): Unit =
    bus.expectMsgType[UnsubscribeCmd](3.seconds)
    bus.expectMsgType[UnsubscribeCmd](3.seconds)

  private def expectPeerKnown(npma: ActorRef): Unit =
    val reply = TestProbe()
    npma ! NetworkPeerManagerActor.PeerInfoRequestCmd(
      peerId,
      reply.ref.toTyped[NetworkPeerManagerActor.PeerInfoResponse]
    )
    reply.expectMsgType[NetworkPeerManagerActor.PeerInfoResponse].peerInfo shouldBe defined

  it should "restore the displaced outbound when the swapped-in inbound dies" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    val out = TestProbe(); val in = TestProbe()
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(out, outboundAddr, inbound = false), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(in, inboundAddr, inbound = true), peerInfo))

    stopAndAwait(in)
    bus.expectNoMessage(300.millis) // not removed: the outbound took over
    expectPeerKnown(npma)

    stopAndAwait(out)
    expectPeerRemoved(bus)
  }

  it should "not restore the displaced outbound if it died first" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    val out = TestProbe(); val in = TestProbe()
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(out, outboundAddr, inbound = false), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(in, inboundAddr, inbound = true), peerInfo))

    stopAndAwait(out)
    bus.expectNoMessage(300.millis) // the current entry is the inbound
    expectPeerKnown(npma)

    stopAndAwait(in)
    expectPeerRemoved(bus) // no standby left to take over
  }

  it should "keep the outbound when the swapped-in inbound is already dead on arrival" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    val out = TestProbe(); val in = TestProbe()
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(out, outboundAddr, inbound = false), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]

    stopAndAwait(in)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(in, inboundAddr, inbound = true), peerInfo))
    bus.expectNoMessage(300.millis)
    expectPeerKnown(npma)

    stopAndAwait(out)
    expectPeerRemoved(bus)
  }

  it should "hand over to a dropped duplicate when the kept entry's actor dies" taggedAs UnitTest in {
    val (npma, _, bus) = newNpma()
    drainInitialSubscriptions(bus)
    val first = TestProbe(); val second = TestProbe()
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(first, outboundAddr, inbound = false), peerInfo))
    bus.expectMsgType[SubscribeCmd]; bus.expectMsgType[SubscribeCmd]
    val secondAddr = new InetSocketAddress("127.0.0.1", 30305)
    npma ! PeerEventCmd(PeerHandshakeSuccessful(peerOn(second, secondAddr, inbound = false), peerInfo)) // dropped

    stopAndAwait(first)
    bus.expectNoMessage(300.millis)
    expectPeerKnown(npma)

    stopAndAwait(second)
    expectPeerRemoved(bus)
  }
