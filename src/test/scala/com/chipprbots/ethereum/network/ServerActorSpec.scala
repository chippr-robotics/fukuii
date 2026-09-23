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
