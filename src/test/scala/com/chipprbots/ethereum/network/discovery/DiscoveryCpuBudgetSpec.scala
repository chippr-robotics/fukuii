package com.chipprbots.ethereum.network.discovery

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.util.ByteString

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import com.chipprbots.scalanet.discovery.crypto.PrivateKey
import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.Node as ENode
import com.chipprbots.scalanet.discovery.ethereum.v4
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryNetwork
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryRPC
import com.chipprbots.scalanet.discovery.ethereum.v4.Packet
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.hash.Hash
import com.chipprbots.scalanet.peergroup.Channel
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.PeerGroup
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.Attempt
import scodec.Codec
import scodec.bits.BitVector

import com.chipprbots.ethereum.db.storage.KnownNodesStorage
import com.chipprbots.ethereum.network.discovery.codecs.RLPCodecs
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Task #52: discv4 ECDSA saturated `IORuntime.global` on a 4-core Ephemery host and starved block import and the
  * Engine API. These pin the fixes: discovery work runs on its own bounded pool, packets a channel would discard are
  * dropped before key recovery, inbound requests have a budget, and random-node requests share one lookup loop.
  */
class DiscoveryCpuBudgetSpec extends AnyFlatSpec with Matchers:

  private val loopback = InetAddress.getByName("127.0.0.1")
  private val realSigAlg = new Secp256k1SigAlg
  private given payloadCodec: Codec[Payload] = RLPCodecs.payloadCodec
  private given packetCodec: Codec[Packet] = Packet.packetCodec(allowDecodeOverMaxPacketSize = true)

  /** Delegates to the real algorithm, recording the thread of every sign and recovery. */
  private class RecordingSigAlg extends SigAlg:
    val threads = new ConcurrentLinkedQueue[String]()
    val recoveries = new AtomicInteger(0)
    private def record(): Unit = threads.add(Thread.currentThread.getName): Unit
    override def name: String = realSigAlg.name
    override def PrivateKeyBytesSize: Int = realSigAlg.PrivateKeyBytesSize
    override def PublicKeyBytesSize: Int = realSigAlg.PublicKeyBytesSize
    override def SignatureBytesSize: Int = realSigAlg.SignatureBytesSize
    override def newKeyPair: (PublicKey, PrivateKey) = realSigAlg.newKeyPair
    override def sign(privateKey: PrivateKey, data: BitVector): Signature =
      record(); realSigAlg.sign(privateKey, data)
    override def removeRecoveryId(signature: Signature): Signature = realSigAlg.removeRecoveryId(signature)
    override def verify(publicKey: PublicKey, signature: Signature, data: BitVector): Boolean =
      record(); realSigAlg.verify(publicKey, signature, data)
    override def recoverPublicKey(signature: Signature, data: BitVector): Attempt[PublicKey] =
      record(); recoveries.incrementAndGet(); realSigAlg.recoverPublicKey(signature, data)
    override def recoverPublicKeyFromHash(signature: Signature, messageHash: BitVector): Attempt[PublicKey] =
      record(); recoveries.incrementAndGet(); realSigAlg.recoverPublicKeyFromHash(signature, messageHash)
    override def toPublicKey(privateKey: PrivateKey): PublicKey = realSigAlg.toPublicKey(privateKey)
    override def compressPublicKey(publicKey: PublicKey): PublicKey = realSigAlg.compressPublicKey(publicKey)

  private def withRuntime[T](threads: Int)(f: IORuntime => T): T =
    val runtime = DiscoveryRuntime(threads)
    try f(runtime)
    finally runtime.shutdown()

  private def freePort(): Int =
    val s = new DatagramSocket(0, loopback)
    try s.getLocalPort
    finally s.close()

  private def nowSeconds: Long = System.currentTimeMillis() / 1000

  private def handler(
      onPing: Option[Option[DiscoveryRPC.ENRSeq]]
  ): DiscoveryRPC[DiscoveryNetwork.Peer[InetMultiAddress]] =
    new DiscoveryRPC[DiscoveryNetwork.Peer[InetMultiAddress]]:
      override def ping: DiscoveryNetwork.Peer[InetMultiAddress] => Option[DiscoveryRPC.ENRSeq] => IO[
        Option[Option[DiscoveryRPC.ENRSeq]]
      ] = _ => _ => IO.pure(onPing)
      override def findNode: DiscoveryNetwork.Peer[InetMultiAddress] => PublicKey => IO[Option[Seq[ENode]]] =
        _ => _ => IO.pure(None)
      override def enrRequest: DiscoveryNetwork.Peer[InetMultiAddress] => Unit => IO[Option[EthereumNodeRecord]] =
        _ => _ => IO.pure(None)

  behavior.of("DiscoveryRuntime")

  it should "run all its work on at most `computeThreads` named discovery threads" taggedAs (UnitTest, NetworkTest) in {
    withRuntime(threads = 1) { runtime =>
      val names = (1 to 16).toList
        .parTraverse(_ => IO.cede >> IO(Thread.currentThread.getName))
        .unsafeRunSync()(using runtime)
      names.toSet should have size 1
      names.head should startWith(DiscoveryRuntime.ComputeThreadPrefix)
    }
  }

  behavior.of("discv4 over UDP on the discovery runtime")

  it should "sign and recover only on discovery threads, never on the global io-compute pool" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    withRuntime(threads = 1) { runtime =>
      val recording = new RecordingSigAlg
      given SigAlg = recording
      val (_, privA) = realSigAlg.newKeyPair
      val (pubB, privB) = realSigAlg.newKeyPair
      val (portA, portB) = (freePort(), freePort())
      def address(port: Int) = new InetSocketAddress(loopback, port)
      def nodeAddress(port: Int) = ENode.Address(loopback, udpPort = port, tcpPort = port)
      val toNodeAddress = (a: InetMultiAddress) => nodeAddress(a.inetSocketAddress.getPort)

      def network(port: Int, key: PrivateKey) =
        for
          group <- StaticUDPPeerGroup[Packet](StaticUDPPeerGroup.Config(address(port)))
          net <- Resource.eval(
            DiscoveryNetwork[InetMultiAddress](group, key, nodeAddress(port), toNodeAddress, v4.DiscoveryConfig.default)
          )
        yield net

      val program = (for
        netA <- network(portA, privA)
        netB <- network(portB, privB)
        _ <- Resource.eval(netB.startHandling(handler(onPing = Some(None))))
      yield netA).use { netA =>
        netA.ping(DiscoveryNetwork.Peer(pubB, InetMultiAddress(address(portB))))(None)
      }

      program.unsafeRunSync()(using runtime) shouldBe Some(None)
      val threads = recording.threads.asScala.toList
      // Ping signed by A, recovered by B; Pong signed by B, recovered by A.
      threads.size should be >= 4
      all(threads) should startWith(DiscoveryRuntime.ComputeThreadPrefix)
    }
  }

  behavior.of("DiscoveryNetwork server channel")

  private val remote = InetMultiAddress(new InetSocketAddress(loopback, 31999))

  /** A server channel that delivers `packets` and then closes. */
  private class ScriptedChannel(packets: List[Packet], position: Ref[IO, Int])
      extends Channel[InetMultiAddress, Packet]:
    override def from: InetMultiAddress = remote
    override def to: InetMultiAddress = remote
    override def sendMessage(message: Packet): IO[Unit] = IO.unit
    override def nextChannelEvent: IO[Option[Channel.ChannelEvent[Packet]]] =
      position.getAndUpdate(_ + 1).map(i => packets.lift(i).map(Channel.MessageReceived(_)))

  /** Handle one server channel carrying `packets`; return how many key recoveries that cost. */
  private def recoveriesToHandle(packets: List[Packet], limits: DiscoveryNetwork.InboundRequestLimits): Int =
    val recording = new RecordingSigAlg
    given SigAlg = recording
    val (_, localKey) = realSigAlg.newKeyPair
    val local = ENode.Address(loopback, udpPort = 30303, tcpPort = 30303)
    val program =
      for
        released <- Deferred[IO, Unit]
        position <- Ref[IO].of(0)
        served <- Ref[IO].of(false)
        peerGroup = new PeerGroup[InetMultiAddress, Packet]:
          override def processAddress: InetMultiAddress = remote
          override def client(to: InetMultiAddress): Resource[IO, Channel[InetMultiAddress, Packet]] =
            Resource.eval(IO.raiseError(new UnsupportedOperationException))
          override def nextServerEvent: IO[Option[ServerEvent[InetMultiAddress, Packet]]] =
            served.getAndSet(true).flatMap {
              case false =>
                IO.pure(Some(ChannelCreated(new ScriptedChannel(packets, position), released.complete(()).void)))
              case true => IO.never
            }
        network <- DiscoveryNetwork[InetMultiAddress](
          peerGroup,
          localKey,
          local,
          _ => local,
          v4.DiscoveryConfig.default,
          inboundRequestLimits = limits
        )
        token <- network.startHandling(handler(onPing = None))
        _ <- released.get.timeout(20.seconds)
        _ <- token.complete(())
      yield recording.recoveries.get
    program.unsafeRunSync()(using IORuntime.global)

  private val (remotePub, remoteKey) = realSigAlg.newKeyPair
  private def packed(payload: Payload): Packet = Packet.pack(payload, remoteKey)(using payloadCodec, realSigAlg).require
  private def findNode(): Packet = packed(Payload.FindNode(remotePub, nowSeconds + 60))
  private def pong(i: Int): Packet =
    packed(Payload.Pong(ENode.Address(loopback, 1, 1), Hash(BitVector.fill(256)(i % 2 == 0)), nowSeconds + 60, None))

  private val unlimited = DiscoveryNetwork.InboundRequestLimits(10_000, 10_000, 10_000, 10_000)

  it should "discard responses replicated to it without recovering their keys" taggedAs (UnitTest, NetworkTest) in {
    recoveriesToHandle(List.tabulate(5)(pong), unlimited) shouldBe 0
    recoveriesToHandle(List.fill(3)(findNode()), unlimited) shouldBe 3
  }

  it should "drop requests from one address beyond its budget before recovering their keys" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val limits = DiscoveryNetwork.InboundRequestLimits(
      perAddressPerSecond = 1,
      perAddressBurst = 5,
      globalPerSecond = 10_000,
      globalBurst = 10_000
    )
    // 5 from the burst, plus at most one refill if the run straddles a second.
    recoveriesToHandle(List.fill(40)(findNode()), limits) should ((be >= 5).and(be <= 6))
  }

  it should "drop requests beyond the global budget" taggedAs (UnitTest, NetworkTest) in {
    val limits = DiscoveryNetwork.InboundRequestLimits(
      perAddressPerSecond = 10_000,
      perAddressBurst = 10_000,
      globalPerSecond = 1,
      globalBurst = 3
    )
    recoveriesToHandle(List.fill(40)(findNode()), limits) should ((be >= 3).and(be <= 4))
  }

  behavior.of("PeerDiscoveryManager random nodes")

  private class StubService(lookup: IO[Set[ENode]]) extends v4.DiscoveryService:
    override def getNode(nodeId: ENode.Id): IO[Option[ENode]] = IO.pure(None)
    override def getNodes: IO[Set[ENode]] = IO.pure(Set.empty)
    override def addNode(node: ENode): IO[Unit] = IO.unit
    override def removeNode(nodeId: ENode.Id): IO[Unit] = IO.unit
    override def updateExternalAddress(ip: InetAddress): IO[Unit] = IO.unit
    override def getLocalNode: IO[ENode] = IO.raiseError(new UnsupportedOperationException)
    override def getClosestNodes(target: ENode.Id): IO[Seq[ENode]] = IO.pure(Nil)
    override def getRandomNodes: IO[Set[ENode]] = lookup

  it should "serve concurrent requests from one lookup on the discovery pool, not one lookup each" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    withRuntime(threads = 1) { runtime =>
      val testKit = ActorTestKit()
      try
        val lookups = new AtomicInteger(0)
        val lookupThreads = new ConcurrentLinkedQueue[String]()
        val nodes: Set[ENode] = Set(1, 2).map { i =>
          ENode(PublicKey(BitVector(Array.fill[Byte](64)(i.toByte))), ENode.Address(loopback, 30300 + i, 30300 + i))
        }
        val lookup = IO {
          lookups.incrementAndGet()
          lookupThreads.add(Thread.currentThread.getName)
        } >> IO.sleep(50.millis).as(nodes)
        val config = DiscoveryConfig(Config.config, bootstrapNodes = Set.empty)
          .copy(discoveryEnabled = true, reuseKnownNodes = false)
        val manager = testKit.spawn(
          PeerDiscoveryManager(
            localNodeId = ByteString.fromString("local"),
            discoveryConfig = config,
            knownNodesStorage = null.asInstanceOf[KnownNodesStorage], // unused: reuseKnownNodes = false
            discoveryServiceResource = Resource.pure[IO, v4.DiscoveryService](new StubService(lookup)),
            randomNodeBufferSize = 4
          )(using runtime)
        )
        manager ! PeerDiscoveryManager.Start
        val probe = testKit.createTestProbe[PeerDiscoveryManager.RandomNodeInfo]()
        // Let the service start, then ask 20 times at once — PeerManager does this on churn.
        probe.expectNoMessage(300.millis)
        (1 to 20).foreach(_ => manager ! PeerDiscoveryManager.GetRandomNodeInfoReq(probe.ref))
        probe.receiveMessages(2, 5.seconds).map(_.node.udpPort).toSet shouldBe Set(30301, 30302)
        // One lookup served them; a second can only have started if a slow runner let MinDelay (2 s) pass. The old
        // per-request streams ran one lookup per request: 20 here.
        lookups.get should be <= 2
        all(lookupThreads.asScala.toList) should startWith(DiscoveryRuntime.ComputeThreadPrefix)
      finally testKit.shutdownTestKit()
    }
  }
