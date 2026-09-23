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

  // Regression guard for the Classic->Typed migration defect: `Bind` was sent with no sender,
  // so Pekko delivered `Bound` to dead letters and `TcpBound` never arrived. Every other test in
  // this file injects `TcpBound` into the actor by hand via a TestProbe standing in for the TCP
  // manager, which bypasses the real round-trip entirely — that is why the defect survived. This
  // one drives the REAL `IO(Tcp)` manager against a real ephemeral port and asserts only on the
  // observable outcome.
  "ServerActor" should "reach Listening via the real Pekko TCP manager, not just an injected TcpBound" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val realTcpManager = org.apache.pekko.io.IO(Tcp)(classicSystem)

    val actor = testKit.spawn(
      // Port 0 = let the OS choose a free port. Loopback so this never opens an external listener.
      // Explicit advertised address keeps the assertion about binding alone, with no dependency on
      // ExternalIPDetector or the network.
      ServerActor.testApply(holder, pm.ref, blacklist, realTcpManager, neverCalled),
      "server-real-tcp-bind"
    )

    actor ! ServerActor.StartServer(
      new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
      Some(InetAddress.getLoopbackAddress),
      DetectionMode.Full
    )

    // Before the fix this never becomes Listening: the socket binds, but `Bound` goes to dead
    // letters and the actor waits in `waitingForBindingResult` indefinitely.
    eventually(timeout(10.seconds), interval(50.millis)) {
      holder.get().serverStatus shouldBe a[ServerStatus.Listening]
    }

    val listening = holder.get().serverStatus.asInstanceOf[ServerStatus.Listening]
    // The OS assigned a real port, so it must be non-zero — proof an actual bind happened rather
    // than a status written from the requested address.
    listening.address.getPort should not be 0

    testKit.stop(actor)
  }

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

  it should "report Listening immediately on TcpBound, then refine the advertised address via DetectedIP " +
    "when bound to a wildcard address" taggedAs (UnitTest, NetworkTest) in {
      val holder = freshHolder()
      val pm = TestProbe()
      val tcpProbe = TestProbe()
      val detectedIp = InetAddress.getByName("5.6.7.8")
      // Held until the end of the test so the provisional-address assertion below cannot race the actor's own
      // background detection; released with the same address the test injects, so either arrival order agrees.
      val release = scala.concurrent.Promise[Option[InetAddress]]()
      val heldDetector: DetectionMode => Option[InetAddress] =
        _ => scala.concurrent.Await.result(release.future, 3.seconds)
      val actor =
        testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, heldDetector), "server-test-2")

      val localAddr = new InetSocketAddress("0.0.0.0", 30304)
      actor ! ServerActor.StartServer(localAddr, None, DetectionMode.Upnp)
      // Confirm StartServer was processed, then inject TcpBound directly to preserve
      // same-sender ordering with the DetectedIP message that follows immediately.
      // (Using bindHandler would route through TcpEventBridge — a different sender —
      // breaking FIFO ordering guarantees with the subsequent DetectedIP send.)
      tcpProbe.expectMsgType[Tcp.Bind]
      actor ! ServerActor.TcpBound(localAddr)

      // serverStatus must already be Listening here — readiness does not wait on address refinement.
      // The provisional address is the bound (wildcard) address itself.
      eventually(timeout(1.second), interval(50.millis)) {
        assert(
          holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
          "ServerStatus should be Listening immediately on TcpBound, before any DetectedIP is sent"
        )
      }
      holder.get().serverStatus match
        case ServerStatus.Listening(address) => address shouldBe localAddr
        case other                           => fail(s"Expected Listening, got $other")

      // Simulate the Future result returning from the async IP detection — this only refines the address.
      actor ! ServerActor.DetectedIP(Some(detectedIp))

      eventually(timeout(2.seconds), interval(50.millis)) {
        holder.get().serverStatus match
          case ServerStatus.Listening(address) => address.getAddress shouldBe detectedIp
          case other                           => fail(s"Expected Listening with refined address, got $other")
      }
      release.trySuccess(Some(detectedIp))
    }

  it should "fall back to loopback when DetectedIP carries None, without affecting prior Listening status" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    val actor =
      testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, _ => None), "server-test-3")

    val localAddr = new InetSocketAddress("0.0.0.0", 30305)
    actor ! ServerActor.StartServer(localAddr, None, DetectionMode.Upnp)
    // Inject TcpBound directly (same-sender ordering guarantee — see test 2 comment).
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! ServerActor.TcpBound(localAddr)

    eventually(timeout(1.second), interval(50.millis)) {
      assert(
        holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
        "ServerStatus should already be Listening before DetectedIP(None) arrives"
      )
    }

    actor ! ServerActor.DetectedIP(None)

    // Wait for the refinement itself (not merely "still Listening", which was already true above and would
    // make this eventually block a no-op race against the async DetectedIP handling).
    eventually(timeout(2.seconds), interval(50.millis)) {
      holder.get().serverStatus match
        case ServerStatus.Listening(address) => address.getAddress shouldBe InetAddress.getLoopbackAddress
        case other                           => fail(s"Expected Listening with loopback fallback, got $other")
    }
  }

  it should "report Listening immediately on TcpBound even when the IP detector hangs — the defect this " +
    "spec class guards against (net_listening/admin_nodeInfo stuck at false under hive)" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      val holder = freshHolder()
      val pm = TestProbe()
      val tcpProbe = TestProbe()

      // Simulates a network-isolated environment (e.g. a hive container with no route to UPnP/STUN/HTTP
      // hosts) where ExternalIPDetector.detect() blocks well past any reasonable readiness-probe window.
      // Bounded to 3s so the background thread this runs on doesn't outlive the test.
      val neverCompletes = scala.concurrent.Promise[Option[InetAddress]]()
      val hangingDetector: DetectionMode => Option[InetAddress] =
        _ => scala.concurrent.Await.result(neverCompletes.future, 3.seconds)

      val actor = testKit.spawn(
        ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, hangingDetector),
        "server-test-hang"
      )

      val localAddr = new InetSocketAddress("0.0.0.0", 30306)
      actor ! ServerActor.StartServer(localAddr, None, DetectionMode.Upnp)
      tcpProbe.expectMsgType[Tcp.Bind]
      actor ! ServerActor.TcpBound(localAddr)

      // No DetectedIP is ever sent in this test. Before the fix, ServerStatus would never leave
      // NotListening because finishBinding was gated behind external IP detection completing.
      eventually(timeout(500.millis), interval(20.millis)) {
        assert(
          holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
          "ServerStatus must be Listening promptly on TcpBound, independent of how long IP detection takes"
        )
      }
      holder.get().serverStatus match
        case ServerStatus.Listening(address) => address shouldBe localAddr
        case other                           => fail(s"Expected Listening, got $other")

      neverCompletes.trySuccess(None) // release the background thread promptly instead of waiting out its bound
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
    * auto-detected), and waits until the advertised address is refined using the given stub's initial result — via the
    * actor's own background detect() call, not a manually-injected DetectedIP.
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
    // Listening is reported on TcpBound with the provisional wildcard address; wait for the background
    // detection result to refine it before handing the actor to the refresh assertions.
    eventually(timeout(2.seconds), interval(50.millis)) {
      holder.get().serverStatus match
        case ServerStatus.Listening(address) => address.getAddress.isAnyLocalAddress shouldBe false
        case other                           => fail(s"Expected Listening, got $other")
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
