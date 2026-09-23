package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.TestProbe

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.SpanSugar.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

class ServerActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private val keyPair = com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom)

  private def freshHolder() =
    new AtomicReference(NodeStatus(keyPair, ServerStatus.NotListening, ServerStatus.NotListening))

  private def blacklist: Blacklist = CacheBasedBlacklist.empty(100)

  // Every test injects a stub detector — never the real ExternalIPDetector — so no test in this file can
  // reach a non-loopback host. `neverCalled` additionally proves detect() is never invoked at all on the
  // configured-advertised-address path.
  private def neverCalled: DetectionMode => Option[InetAddress] =
    _ => fail("ExternalIPDetector.detect should not be called when an advertised address is configured")

  "ServerActor" should "transition to listening immediately when an explicit advertised-address is set" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    // TCP probe absorbs the Bind request so no real socket binding happens.
    val tcpProbe = TestProbe()
    val actor =
      testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, neverCalled), "server-test-1")

    val explicit = InetAddress.getByName("1.2.3.4")
    val localAddr = new InetSocketAddress("0.0.0.0", 30303)
    actor ! ServerActor.StartServer(localAddr, Some(explicit), DetectionMode.Full)
    // The Bind carries the bridge handler ref that receives the Bound/CommandFailed/Connected events.
    val bindHandler = tcpProbe.expectMsgType[Tcp.Bind].handler
    bindHandler ! Tcp.Bound(localAddr)

    eventually(timeout(1.second), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should have transitioned to Listening"
      )
    }
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe explicit
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "finalise advertisement via DetectedIP when bound to a wildcard address" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    // Stub detector: never called for real in this test (the manually-injected DetectedIP below always wins
    // the race against the actor's own pipeToSelf-driven result), but must never touch the network regardless.
    val actor = testKit.spawn(
      ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, _ => None),
      "server-test-2"
    )

    val localAddr = new InetSocketAddress("0.0.0.0", 30304)
    val detectedIp = InetAddress.getByName("5.6.7.8")
    actor ! ServerActor.StartServer(localAddr, None, DetectionMode.Upnp)
    // Confirm StartServer was processed, then inject TcpBound directly to preserve
    // same-sender ordering with the DetectedIP message that follows immediately.
    // (Using bindHandler would route through TcpEventBridge — a different sender —
    // breaking FIFO ordering guarantees with the subsequent DetectedIP send.)
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! ServerActor.TcpBound(localAddr)

    // Simulate the Future result returning from the async IP detection
    actor ! ServerActor.DetectedIP(Some(detectedIp))

    eventually(timeout(2.seconds), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should reach Listening after DetectedIP"
      )
    }
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe detectedIp
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "fall back to loopback when DetectedIP carries None" taggedAs (UnitTest, NetworkTest) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(
      ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, _ => None),
      "server-test-3"
    )

    val localAddr = new InetSocketAddress("0.0.0.0", 30305)
    actor ! ServerActor.StartServer(localAddr, None, DetectionMode.Upnp)
    // Inject TcpBound directly (same-sender ordering guarantee — see test 2 comment).
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! ServerActor.TcpBound(localAddr)
    actor ! ServerActor.DetectedIP(None)

    eventually(timeout(2.seconds), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should reach Listening (loopback) after DetectedIP(None)"
      )
    }
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe InetAddress.getLoopbackAddress
      case other                           => fail(s"Expected Listening, got $other")
  }

  // ---------------------------------------------------------------------
  // Periodic external-address refresh (RefreshExternalIP / RefreshedIP)
  //
  // Every test below drives RefreshExternalIP directly — no real 30-minute wait — and every detect() call it
  // triggers, including the eligible (auto-detected) path, goes through an injected stub. No test in this
  // file can reach a non-loopback host: the stub is the only thing `detect` ever calls.
  // ---------------------------------------------------------------------

  /** A stub detector whose result can be changed between calls (to simulate a value changing between the initial
    * startup detection and a later refresh) and which counts how many times it was invoked.
    */
  private class StubDetector(initial: Option[InetAddress]):
    private val current = new AtomicReference(initial)
    val callCount = new AtomicInteger(0)
    def set(result: Option[InetAddress]): Unit = current.set(result)
    val fn: DetectionMode => Option[InetAddress] = _ =>
      callCount.incrementAndGet()
      current.get()

  /** Spawns a ServerActor, starts it with a wildcard bind + no configured address (making the eventual address
    * auto-detected), and drives it to Listening using the given stub's initial result — via the actor's own
    * pipeToSelf-driven detect() call, not a manually-injected DetectedIP.
    */
  private def spawnListeningAutoDetected(
      name: String,
      mode: DetectionMode,
      stub: StubDetector,
      port: Int
  ): (org.apache.pekko.actor.typed.ActorRef[ServerActor.Command], TestProbe, AtomicReference[NodeStatus]) =
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, stub.fn), name)
    val localAddr = new InetSocketAddress("0.0.0.0", port)
    actor ! ServerActor.StartServer(localAddr, None, mode)
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! ServerActor.TcpBound(localAddr)
    eventually(timeout(2.seconds), interval(50.millis)) {
      holder.get().serverStatus.isInstanceOf[ServerStatus.Listening] shouldBe true
    }
    (actor, pm, holder)

  it should "update the advertised address end to end when RefreshExternalIP finds a new public candidate" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val initialIp = InetAddress.getByName("5.6.7.8")
    val refreshedIp = InetAddress.getByName("9.9.9.9")
    val stub = new StubDetector(Some(initialIp))
    val (actor, _, holder) = spawnListeningAutoDetected("server-test-4", DetectionMode.Upnp, stub, 30306)
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe initialIp
      case other                           => fail(s"Expected Listening, got $other")
    val callsBeforeRefresh = stub.callCount.get()

    stub.set(Some(refreshedIp))
    actor ! ServerActor.RefreshExternalIP

    eventually(timeout(2.seconds), interval(50.millis)) {
      holder.get().serverStatus match
        case ServerStatus.Listening(address) => address.getAddress shouldBe refreshedIp
        case other                           => fail(s"Expected Listening, got $other")
    }
    stub.callCount.get() should be > callsBeforeRefresh
  }

  it should "leave the advertised address unchanged when RefreshExternalIP's stub returns the same candidate" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val sameIp = InetAddress.getByName("5.6.7.8")
    val stub = new StubDetector(Some(sameIp))
    val (actor, pm, holder) = spawnListeningAutoDetected("server-test-5", DetectionMode.Upnp, stub, 30307)

    actor ! ServerActor.RefreshExternalIP
    // No further transition is expected; probe liveness with a message the actor does handle.
    actor ! ServerActor.TcpConnected(TestProbe().ref, new InetSocketAddress("203.0.113.5", 40000))
    pm.expectMsgType[PeerManagerActor.HandlePeerConnectionCmd]

    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe sameIp
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "leave the advertised address unchanged when RefreshExternalIP's stub returns a non-public candidate" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val initialIp = InetAddress.getByName("5.6.7.8")
    val privateIp = InetAddress.getByName("10.0.0.1")
    val stub = new StubDetector(Some(initialIp))
    val (actor, pm, holder) = spawnListeningAutoDetected("server-test-6", DetectionMode.Upnp, stub, 30308)

    stub.set(Some(privateIp))
    actor ! ServerActor.RefreshExternalIP
    actor ! ServerActor.TcpConnected(TestProbe().ref, new InetSocketAddress("203.0.113.5", 40000))
    pm.expectMsgType[PeerManagerActor.HandlePeerConnectionCmd]

    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe initialIp
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "leave the advertised address unchanged when RefreshExternalIP's stub returns None" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val initialIp = InetAddress.getByName("5.6.7.8")
    val stub = new StubDetector(Some(initialIp))
    val (actor, pm, holder) = spawnListeningAutoDetected("server-test-7", DetectionMode.Upnp, stub, 30309)

    stub.set(None)
    actor ! ServerActor.RefreshExternalIP
    actor ! ServerActor.TcpConnected(TestProbe().ref, new InetSocketAddress("203.0.113.5", 40000))
    pm.expectMsgType[PeerManagerActor.HandlePeerConnectionCmd]

    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe initialIp
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "never call detect again or change the address when it was explicitly configured" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    // neverCalled proves detect() is invoked zero times across the whole lifecycle, including any refresh
    // attempt — the configured-address path skips detection entirely at startup, and the guard on
    // RefreshExternalIP's eligible arm (wasAutoDetected=false here) keeps it that way.
    val actor =
      testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, neverCalled), "server-test-8")

    val explicit = InetAddress.getByName("1.2.3.4")
    val localAddr = new InetSocketAddress("0.0.0.0", 30310)
    // Full mode would make detection eligible if the address had been auto-detected — but it wasn't.
    actor ! ServerActor.StartServer(localAddr, Some(explicit), DetectionMode.Full)
    val bindHandler = tcpProbe.expectMsgType[Tcp.Bind].handler
    bindHandler ! Tcp.Bound(localAddr)

    eventually(timeout(1.second), interval(50.millis)) {
      holder.get().serverStatus.isInstanceOf[ServerStatus.Listening] shouldBe true
    }

    actor ! ServerActor.RefreshExternalIP
    actor ! ServerActor.TcpConnected(TestProbe().ref, new InetSocketAddress("203.0.113.5", 40000))
    pm.expectMsgType[PeerManagerActor.HandlePeerConnectionCmd]

    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe explicit
      case other                           => fail(s"Expected Listening, got $other")
  }

  it should "call detect only once at startup and never again when detection mode is none" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val stub = new StubDetector(None) // mirrors real ExternalIPDetector.detect(None): always None
    val (actor, pm, holder) = spawnListeningAutoDetected("server-test-9", DetectionMode.None, stub, 30311)
    val callsAfterStartup = stub.callCount.get()
    callsAfterStartup shouldBe 1

    // The handler's guard (detectionMode == None) makes this an unconditional no-op.
    actor ! ServerActor.RefreshExternalIP
    actor ! ServerActor.TcpConnected(TestProbe().ref, new InetSocketAddress("203.0.113.5", 40000))
    pm.expectMsgType[PeerManagerActor.HandlePeerConnectionCmd]

    stub.callCount.get() shouldBe callsAfterStartup
    holder.get().serverStatus match
      case ServerStatus.Listening(address) => address.getAddress shouldBe InetAddress.getLoopbackAddress
      case other                           => fail(s"Expected Listening, got $other")
  }
