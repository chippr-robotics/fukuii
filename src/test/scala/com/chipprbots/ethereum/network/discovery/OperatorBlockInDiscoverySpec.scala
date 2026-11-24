package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.Node as ScNode
import com.chipprbots.scalanet.discovery.ethereum.v4
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryNetwork.Peer
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.BitVector

import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.testing.Tags.*

/** B-288: the operator's IP block list is read from `network.peer.blocked-ips` at start, and reaches discovery. */
class OperatorBlockInDiscoverySpec extends AnyFlatSpec with Matchers:

  given IORuntime = IORuntime.global

  private def address(ip: String): InetAddress = InetAddress.getByName(ip)
  private def peer(ip: String): Peer[InetMultiAddress] =
    Peer(PublicKey(BitVector.fill(512)(high = false)), InetMultiAddress(new InetSocketAddress(address(ip), 30303)))

  "BlockedIPRegistry.fromConfig" should "start from the configured addresses, in canonical form" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val config = ConfigFactory.parseString("""network.peer.blocked-ips = ["203.0.113.9", "2001:DB8:0:0:0:0:0:1"]""")
    val registry = BlockedIPRegistry.fromConfig(config).toOption.get
    registry.all shouldBe Set("203.0.113.9", "2001:db8:0:0:0:0:0:1")
    registry.isBlocked(address("203.0.113.9")) shouldBe true
    registry.isBlocked(address("2001:db8::1")) shouldBe true
    registry.isBlocked(address("203.0.113.10")) shouldBe false
  }

  it should "start empty without the key" taggedAs (UnitTest, NetworkTest) in {
    BlockedIPRegistry.fromConfig(ConfigFactory.empty()).map(_.all) shouldBe Right(Set.empty)
  }

  it should "refuse an entry that is not an IP address, naming it" taggedAs (UnitTest, NetworkTest) in {
    val config = ConfigFactory.parseString("""network.peer.blocked-ips = ["203.0.113.9", "bad.example.org"]""")
    BlockedIPRegistry.fromConfig(config) shouldBe Left("network.peer.blocked-ips: not an IP address: 'bad.example.org'")
  }

  "DiscoveryServiceBuilder.dropBlocked" should "claim a blocked sender's datagram and pass every other one" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val registry = new BlockedIPRegistry(Set("203.0.113.9"))
    val responder = DiscoveryServiceBuilder.dropBlocked(registry.isBlocked(_: InetAddress))
    responder(new InetSocketAddress(address("203.0.113.9"), 30303), BitVector.empty) shouldBe
      StaticUDPPeerGroup.SyncResult.Stop
    responder(new InetSocketAddress(address("203.0.113.10"), 30303), BitVector.empty) shouldBe
      StaticUDPPeerGroup.SyncResult.Pass
    // A block added while the node runs applies to the next datagram.
    registry.block("203.0.113.10")
    responder(new InetSocketAddress(address("203.0.113.10"), 30303), BitVector.empty) shouldBe
      StaticUDPPeerGroup.SyncResult.Stop
  }

  "DiscoveryServiceBuilder.OperatorBlockedNetwork" should "send no request to a blocked address" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val sent = new AtomicInteger(0)
    val underlying = new v4.DiscoveryNetwork[InetMultiAddress]:
      override def ping: Peer[InetMultiAddress] => Option[v4.DiscoveryRPC.ENRSeq] => IO[
        Option[Option[v4.DiscoveryRPC.ENRSeq]]
      ] = _ => _ => IO(sent.incrementAndGet()).as(Some(None))
      override def findNode: Peer[InetMultiAddress] => PublicKey => IO[Option[Seq[ScNode]]] =
        _ => _ => IO(sent.incrementAndGet()).as(Some(Seq.empty))
      override def enrRequest: Peer[InetMultiAddress] => Unit => IO[Option[EthereumNodeRecord]] =
        _ => _ => IO(sent.incrementAndGet()).as(None)
      override def startHandling(handler: v4.DiscoveryRPC[Peer[InetMultiAddress]]): IO[Deferred[IO, Unit]] =
        Deferred[IO, Unit]
    val registry = new BlockedIPRegistry(Set("203.0.113.9"))
    val network = DiscoveryServiceBuilder.OperatorBlockedNetwork(underlying, registry.isBlocked(_: InetAddress))
    val target = PublicKey(BitVector.fill(512)(high = true))

    network.ping(peer("203.0.113.9"))(None).unsafeRunSync() shouldBe None
    network.findNode(peer("203.0.113.9"))(target).unsafeRunSync() shouldBe None
    network.enrRequest(peer("203.0.113.9"))(()).unsafeRunSync() shouldBe None
    sent.get shouldBe 0

    network.ping(peer("203.0.113.10"))(None).unsafeRunSync() shouldBe Some(None)
    network.findNode(peer("203.0.113.10"))(target).unsafeRunSync() shouldBe Some(Seq.empty)
    sent.get shouldBe 2
  }
