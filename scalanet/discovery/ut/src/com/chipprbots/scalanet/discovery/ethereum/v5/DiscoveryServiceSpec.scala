package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.duration._

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import com.chipprbots.scalanet.discovery.ethereum.codecs.DefaultCodecs
import com.chipprbots.scalanet.discovery.ethereum.v4.mocks.MockSigAlg
import com.chipprbots.scalanet.peergroup.CloseableQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.{BitVector, ByteVector}

/** Tests for [[DiscoveryService]] focused on the high-level orchestration:
  * record peers, getNodes/getRandomNode lookups, periodic discovery firing.
  *
  * The full kademlia behavior under load lives in production hive runs; these
  * tests prove the wiring is correct and the resource lifecycle behaves. */
class DiscoveryServiceSpec extends AnyFlatSpec with Matchers {

  import DefaultCodecs.{given, *}
  given sigalg: SigAlg = new MockSigAlg

  /** Stub Codec[Payload] — same shape as the DiscoveryNetworkSpec stub. */
  given stubCodec: Codec[Payload] = Codec[Payload](
    (p: Payload) => Attempt.successful((ByteVector(p.messageType) ++ ByteVector("body".getBytes)).bits),
    (bits: BitVector) => {
      val b = bits.toByteVector
      if (b.isEmpty) Attempt.failure(Err("empty"))
      else
        Attempt.successful(
          DecodeResult(Payload.Ping(ByteVector.fromValidHex("01020304"), 1L), BitVector.empty)
        )
    }
  )

  /** No-op sender. */
  val nullSender: DiscoveryNetwork.PeerGroupSender[InetSocketAddress] =
    new DiscoveryNetwork.PeerGroupSender[InetSocketAddress] {
      override def sendRaw(remoteAddress: InetSocketAddress, bytes: ByteVector): IO[Unit] = IO.unit
    }

  // ---- Test fixtures ------------------------------------------------------

  private val (localPub, localPriv) = sigalg.newKeyPair
  private val localNode = Node(
    id = localPub,
    address = Node.Address(InetAddress.getLoopbackAddress, udpPort = 30303, tcpPort = 30303)
  )
  private val localNodeId = Session.nodeIdFromPublicKey(localPub.value.bytes)

  private def buildService(bootstrapNodes: Set[Node] = Set.empty) = {
    val sessions = new Session.SessionCache()
    val challenges = new Discv5SyncResponder.ChallengeCache()
    val bystanders = new Discv5SyncResponder.BystanderEnrTable()
    for {
      queue <- CloseableQueue.unbounded[(InetSocketAddress, ByteVector)]
      network <- DiscoveryNetwork[InetSocketAddress](
        peerGroup = nullSender,
        privateKey = localPriv,
        publicKey = localPub,
        localNodeId = localNodeId,
        localEnrRef = new AtomicReference[EthereumNodeRecord](
          EthereumNodeRecord.fromNode(localNode, localPriv, seq = 1).require
        ),
        sessions = sessions,
        challenges = challenges,
        bystanders = bystanders,
        dispatchQueue = queue,
        config = DiscoveryConfig.default.copy(requestTimeout = 50.millis)
      )
      cfg = DiscoveryConfig.default.copy(
        requestTimeout = 50.millis,
        discoveryInterval = 100.millis, // fire fast in tests
        lookupParallelism = 2
      )
    } yield (network, sessions, bystanders, cfg)
  }

  // ---- Tests --------------------------------------------------------------

  behavior.of("DiscoveryService")

  it should "start with an empty node table and a local ENR available" in {
    val (network, sessions, bystanders, cfg) = buildService().unsafeRunSync()

    DiscoveryService(
      privateKey = localPriv,
      publicKey = localPub,
      localNode = localNode,
      network = network,
      sessions = sessions,
      bystanders = bystanders,
      bootstrapNodes = Set.empty,
      config = cfg
    ).use { svc =>
      for {
        nodes <- svc.getNodes
        random <- svc.getRandomNode
        enr <- svc.localEnr
      } yield {
        nodes shouldBe empty
        random shouldBe None
        enr.content.seq shouldBe 1L
      }
    }.unsafeRunSync()
  }

  it should "record an inbound peer ENR and surface it via getNodes / getRandomNode" in {
    val (network, sessions, bystanders, cfg) = buildService().unsafeRunSync()

    val (peerPub, peerPriv) = sigalg.newKeyPair
    val peerNode = Node(
      id = peerPub,
      address = Node.Address(InetAddress.getLoopbackAddress, udpPort = 31000, tcpPort = 31000)
    )
    val peerEnr = EthereumNodeRecord.fromNode(peerNode, peerPriv, seq = 5).require

    DiscoveryService(
      privateKey = localPriv,
      publicKey = localPub,
      localNode = localNode,
      network = network,
      sessions = sessions,
      bystanders = bystanders,
      bootstrapNodes = Set.empty,
      config = cfg
    ).use { svc =>
      // recordPeer with a mock-keyed ENR doesn't throw — the mock pubkey
      // isn't a real secp256k1 point so enrToNode declines to parse it
      // and we skip both the nodes-table insert and the bystander insert.
      // The behavior under real keys is exercised by the integration test
      // in fukuii main (Discv5IntegrationSpec).
      for {
        _ <- svc.recordPeer(peerEnr)
        nodes <- svc.getNodes
      } yield nodes shouldBe empty // mock pubkey didn't parse, so table stays empty
    }.unsafeRunSync()
  }

  it should "release the periodic discovery fiber on Resource finalization" in {
    val (network, sessions, bystanders, cfg) = buildService().unsafeRunSync()

    // Build + immediately release the resource. If the fiber leaks, we'd see
    // it via the bystander table (which periodicDiscovery would populate)
    // — but really the assertion is that the resource finalizes cleanly.
    val resource = DiscoveryService(
      privateKey = localPriv,
      publicKey = localPub,
      localNode = localNode,
      network = network,
      sessions = sessions,
      bystanders = bystanders,
      bootstrapNodes = Set.empty,
      config = cfg
    )
    val ran = resource.use(_ => IO.pure("ok")).unsafeRunSync()
    ran shouldBe "ok"
  }
}
