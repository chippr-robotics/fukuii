package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.ImplicitSender
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus
import com.chipprbots.ethereum.testing.Tags._

class ServerActorSpec
    extends TestKit(ActorSystem("ServerActorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

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
    val actor = system.actorOf(ServerActor.testProps(holder, pm.ref, blacklist, tcpProbe.ref))

    val explicit = InetAddress.getByName("1.2.3.4")
    val localAddr = new InetSocketAddress("0.0.0.0", 30303)
    actor ! ServerActor.StartServer(localAddr, Some(explicit))
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! Tcp.Bound(localAddr)

    awaitCond(
      holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
      max = 1.second,
      interval = 50.millis,
      message = "ServerStatus should have transitioned to Listening"
    )
    holder.get().serverStatus match {
      case ServerStatus.Listening(address) => address.getAddress shouldBe explicit
      case other                           => fail(s"Expected Listening, got $other")
    }
  }

  it should "finalise advertisement via DetectedIP when bound to a wildcard address" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    val actor = system.actorOf(ServerActor.testProps(holder, pm.ref, blacklist, tcpProbe.ref))

    val localAddr = new InetSocketAddress("0.0.0.0", 30304)
    val detectedIp = InetAddress.getByName("5.6.7.8")
    actor ! ServerActor.StartServer(localAddr, None)
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! Tcp.Bound(localAddr)

    // Simulate the Future result returning from the async IP detection
    actor ! ServerActor.DetectedIP(Some(detectedIp))

    awaitCond(
      holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
      max = 2.seconds,
      interval = 50.millis,
      message = "ServerStatus should reach Listening after DetectedIP"
    )
    holder.get().serverStatus match {
      case ServerStatus.Listening(address) => address.getAddress shouldBe detectedIp
      case other                           => fail(s"Expected Listening, got $other")
    }
  }

  it should "fall back to loopback when DetectedIP carries None" taggedAs (UnitTest, NetworkTest) in {
    val holder = freshHolder()
    val pm = TestProbe()
    val tcpProbe = TestProbe()
    val actor = system.actorOf(ServerActor.testProps(holder, pm.ref, blacklist, tcpProbe.ref))

    val localAddr = new InetSocketAddress("0.0.0.0", 30305)
    actor ! ServerActor.StartServer(localAddr, None)
    tcpProbe.expectMsgType[Tcp.Bind]
    actor ! Tcp.Bound(localAddr)
    actor ! ServerActor.DetectedIP(None)

    awaitCond(
      holder.get().serverStatus.isInstanceOf[ServerStatus.Listening],
      max = 2.seconds,
      interval = 50.millis,
      message = "ServerStatus should reach Listening (loopback) after DetectedIP(None)"
    )
    holder.get().serverStatus match {
      case ServerStatus.Listening(address) => address.getAddress shouldBe InetAddress.getLoopbackAddress
      case other                           => fail(s"Expected Listening, got $other")
    }
  }
}
