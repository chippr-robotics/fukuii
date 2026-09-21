package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.net.InetSocketAddress
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

  "ServerActor" should "transition to listening immediately when an explicit advertised-address is set" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    // TCP probe absorbs the Bind request so no real socket binding happens.
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref), "server-test-1")

    val explicit = InetAddress.getByName("1.2.3.4")
    val localAddr = new InetSocketAddress("0.0.0.0", 30303)
    actor ! ServerActor.StartServer(localAddr, Some(explicit))
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
      val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref), "server-test-2")

      val localAddr = new InetSocketAddress("0.0.0.0", 30304)
      val detectedIp = InetAddress.getByName("5.6.7.8")
      actor ! ServerActor.StartServer(localAddr, None)
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
    }

  it should "fall back to loopback when DetectedIP carries None, without affecting prior Listening status" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    val actor = testKit.spawn(ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref), "server-test-3")

    val localAddr = new InetSocketAddress("0.0.0.0", 30305)
    actor ! ServerActor.StartServer(localAddr, None)
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
      val hangingDetector: () => Option[InetAddress] =
        () => scala.concurrent.Await.result(neverCompletes.future, 3.seconds)

      val actor = testKit.spawn(
        ServerActor.testApply(holder, pm.ref, blacklist, tcpProbe.ref, hangingDetector),
        "server-test-4"
      )

      val localAddr = new InetSocketAddress("0.0.0.0", 30306)
      actor ! ServerActor.StartServer(localAddr, None)
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
