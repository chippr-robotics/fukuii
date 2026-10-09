package com.chipprbots.ethereum.network.discovery

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.Codec
import scodec.codecs

import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.ethereum.Node
import com.chipprbots.scalanet.discovery.ethereum.v4
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryNetwork
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryRPC
import com.chipprbots.scalanet.discovery.ethereum.v4.Packet
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.peergroup.Channel
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.PeerGroup
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup

import com.chipprbots.ethereum.network.discovery.codecs.RLPCodecs
import com.chipprbots.ethereum.testing.Tags.*

/** Regression tests for #1519: a Sepolia heap dump showed 32k server-side discovery channels and 876k queued messages
  * because the server-event consumer had stopped draining and nothing bounded or evicted the channels. These live in
  * the root module because the scalanet modules' own tests are not run by CI (#1504).
  */
class DiscoveryServerChannelBoundSpec extends AnyFlatSpec with Matchers:

  private val loopback = InetAddress.getByName("127.0.0.1")

  // --- StaticUDPPeerGroup: cap and idle eviction ---------------------------------------------------------------

  private val textCodec: Codec[String] = codecs.utf8

  private def freePort(): Int =
    val s = new DatagramSocket(0, loopback)
    try s.getLocalPort
    finally s.close()

  private def udpConfig(port: Int, maxServerChannels: Int, idle: FiniteDuration): StaticUDPPeerGroup.Config =
    val bind = new InetSocketAddress(loopback, port)
    StaticUDPPeerGroup.Config(
      bindAddress = bind,
      processAddress = InetMultiAddress(bind),
      channelCapacity = 10,
      receiveBufferSizeBytes = 1024,
      syncResponder = StaticUDPPeerGroup.NoSyncResponder,
      maxServerChannels = maxServerChannels,
      serverChannelIdleTimeout = idle
    )

  /** Send one datagram from each of `senders` distinct source ports to `port`; return the sockets (caller closes). */
  private def sendFromDistinctSources(port: Int, senders: Int): IO[List[DatagramSocket]] =
    IO.blocking {
      List.fill(senders) {
        val socket = new DatagramSocket(0, loopback)
        val bytes = "hello".getBytes("UTF-8")
        socket.send(new DatagramPacket(bytes, bytes.length, loopback, port))
        socket
      }
    }

  private def eventually(check: IO[Boolean], what: String, timeout: FiniteDuration = 15.seconds): IO[Unit] =
    def loop: IO[Unit] = check.flatMap(ok => if ok then IO.unit else IO.sleep(25.millis) >> loop)
    loop.timeoutTo(timeout, IO.raiseError(new AssertionError(s"timed out waiting for: $what")))

  behavior.of("StaticUDPPeerGroup server channels (#1519)")

  it should "cap live server channels and count the refused ones when nothing consumes them" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val port = freePort()
    val program =
      StaticUDPPeerGroup[String](udpConfig(port, maxServerChannels = 3, idle = Duration.Zero))(using textCodec)
        .use { group =>
          Resource.make(sendFromDistinctSources(port, senders = 10))(sockets => IO(sockets.foreach(_.close()))).use {
            _ =>
              for
                _ <- eventually(
                  (group.serverChannelCount, group.droppedServerChannels).mapN(_.toLong + _ == 10L),
                  "all 10 datagrams to be admitted or refused"
                )
                live <- group.serverChannelCount
                dropped <- group.droppedServerChannels
              yield
                live shouldBe 3
                dropped shouldBe 7L
          }
        }
    program.unsafeRunSync()
  }

  it should "evict a server channel that was created but never handled, and accept the address again" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val port = freePort()
    val program =
      StaticUDPPeerGroup[String](udpConfig(port, maxServerChannels = 100, idle = 400.millis))(using textCodec)
        .use { group =>
          // No one ever calls `nextServerEvent`, i.e. the ChannelCreated events are never consumed.
          Resource.make(sendFromDistinctSources(port, senders = 2))(sockets => IO(sockets.foreach(_.close()))).use {
            _ =>
              for
                _ <- eventually(group.serverChannelCount.map(_ == 2), "two server channels to appear")
                _ <- eventually(group.serverChannelCount.map(_ == 0), "idle unconsumed channels to be evicted")
                dropped <- group.droppedServerChannels
                _ <- sendFromDistinctSources(port, senders = 1).flatMap(s => IO(s.foreach(_.close())))
                _ <- eventually(group.serverChannelCount.map(_ >= 1), "a fresh channel after eviction")
              yield dropped shouldBe 0L
          }
        }
    program.unsafeRunSync()
  }

  // --- DiscoveryNetwork: consumer supervision -----------------------------------------------------------------

  private given sigalg: SigAlg = new Secp256k1SigAlg
  private given packetCodec: Codec[Packet] = Packet.packetCodec(allowDecodeOverMaxPacketSize = true)
  private given payloadCodec: Codec[Payload] = RLPCodecs.payloadCodec

  private val remote = InetMultiAddress(new InetSocketAddress(loopback, 31000))

  private class ClosedChannel extends Channel[InetMultiAddress, Packet]:
    override def from: InetMultiAddress = remote
    override def to: InetMultiAddress = remote
    override def sendMessage(message: Packet): IO[Unit] = IO.unit
    // A closed channel: the handler's stream ends immediately and the consumer must release it.
    override def nextChannelEvent: IO[Option[Channel.ChannelEvent[Packet]]] = IO.pure(None)

  private val noopHandler: DiscoveryRPC[DiscoveryNetwork.Peer[InetMultiAddress]] =
    new DiscoveryRPC[DiscoveryNetwork.Peer[InetMultiAddress]]:
      override def ping: DiscoveryNetwork.Peer[InetMultiAddress] => Option[DiscoveryRPC.ENRSeq] => IO[
        Option[Option[DiscoveryRPC.ENRSeq]]
      ] = _ => _ => IO.pure(None)
      override def findNode
          : DiscoveryNetwork.Peer[InetMultiAddress] => com.chipprbots.scalanet.discovery.crypto.PublicKey => IO[
            Option[Seq[Node]]
          ] = _ => _ => IO.pure(None)
      override def enrRequest: DiscoveryNetwork.Peer[InetMultiAddress] => Unit => IO[
        Option[com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord]
      ] = _ => _ => IO.pure(None)

  private class FakePeerGroup(next: => IO[Option[ServerEvent[InetMultiAddress, Packet]]])
      extends PeerGroup[InetMultiAddress, Packet]:
    override def processAddress: InetMultiAddress = remote
    override def client(to: InetMultiAddress): Resource[IO, Channel[InetMultiAddress, Packet]] =
      Resource.eval(IO.raiseError(new UnsupportedOperationException))
    override def nextServerEvent: IO[Option[ServerEvent[InetMultiAddress, Packet]]] = next

  private def makeNetwork(peerGroup: PeerGroup[InetMultiAddress, Packet]): IO[DiscoveryNetwork[InetMultiAddress]] =
    val (_, privateKey) = sigalg.newKeyPair
    val localAddress = Node.Address(loopback, udpPort = 30303, tcpPort = 30303)
    DiscoveryNetwork[InetMultiAddress](
      peerGroup,
      privateKey,
      localAddress,
      _ => localAddress,
      v4.DiscoveryConfig.default
    )

  behavior.of("DiscoveryNetwork.startHandling (#1519)")

  it should "restart the server-event consumer after nextServerEvent fails and keep serving channels" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val calls = new AtomicInteger(0)

    val program = for
      released <- cats.effect.Deferred[IO, Unit]
      peerGroup = new FakePeerGroup(
        IO(calls.incrementAndGet()).flatMap {
          case 1 => IO.raiseError(new RuntimeException("boom: consumer dies on its first event"))
          case 2 => IO.pure(Option(ChannelCreated(new ClosedChannel, released.complete(()).void)))
          case _ => IO.never
        }
      )
      network <- makeNetwork(peerGroup)
      token <- network.startHandling(noopHandler)
      // The first call fails; only a supervised consumer ever reaches the second call and handles the channel.
      _ <- released.get.timeout(20.seconds)
      _ <- token.complete(())
    yield calls.get() should be >= 2

    program.unsafeRunSync()
  }

  it should "stop, not spin, once the peer group reports it is closed" taggedAs (UnitTest, NetworkTest) in {
    val calls = new AtomicInteger(0)

    val program = for
      network <- makeNetwork(new FakePeerGroup(IO(calls.incrementAndGet()).as(None)))
      _ <- network.startHandling(noopHandler)
      _ <- eventually(IO(calls.get() >= 1), "the consumer to poll once")
      _ <- IO.sleep(300.millis)
    yield calls.get() shouldBe 1

    program.unsafeRunSync()
  }
