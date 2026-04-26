package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import cats.effect.{Deferred, IO, Ref, Resource, Temporal}
import cats.implicits._
import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import scodec.Codec
import scodec.bits.ByteVector

/** discv5 service-level orchestration.
  *
  * Sits on top of [[DiscoveryNetwork]] and adds:
  *   - A flat ENR table keyed on nodeId (deliberately simpler than v4's
  *     `KBucketsWithSubnetLimits` — discv5's session-required handshake
  *     gives us comparable spoofing protection without the subnet-limit
  *     bookkeeping)
  *   - Bonding state — a peer is considered bonded when we have an active
  *     session with them; the [[Session.SessionCache]] is the source of truth
  *   - Periodic discovery loop — every `discoveryPeriod` we pick a random
  *     target node-id and run a kademlia lookup against the closest known
  *     peers, populating the table as new ENRs come back
  *   - Bootstrap node enrollment — on startup, ping every bootstrap node
  *     to seed the ENR table
  *
  * For hive devp2p discv5 the SERVICE LAYER isn't strictly required —
  * hive's tests are stateless inbound exchanges that the sync responder
  * handles end-to-end. The service layer is what makes v5 *active* —
  * outbound discovery, table maintenance, periodic refresh.
  */
trait DiscoveryService {

  /** All known peers from the ENR table, deduplicated. */
  def getNodes: IO[Set[Node]]

  /** Pick a random Node from the table. Used by the upper layer
    * (PeerDiscoveryManager) to suggest peers to dial. */
  def getRandomNode: IO[Option[Node]]

  /** Add a peer's ENR to the table. Called on inbound handshake completion
    * or when learning peers from FindNode responses. */
  def recordPeer(enr: EthereumNodeRecord): IO[Unit]

  /** Local ENR — exposed for the sync responder's [[Discv5SyncResponder.Handler]]
    * and for callers that want to advertise our identity. */
  def localEnr: IO[EthereumNodeRecord]
}

object DiscoveryService {

  /** Mutable service state. Just a flat ENR table by nodeId. */
  private[v5] final case class State(
      enr: EthereumNodeRecord,
      // nodeId (32 bytes) -> ENR
      nodes: Map[ByteVector, EthereumNodeRecord]
  )

  /** Build the service as a Resource so the periodic discovery fiber is
    * cancelled on resource release. The supplied `network` should already
    * be running its inbound consumer (caller invokes
    * `network.startHandling(...)` separately). */
  def apply(
      privateKey: PrivateKey,
      publicKey: PublicKey,
      localNode: Node,
      network: DiscoveryNetwork[InetSocketAddress],
      sessions: Session.SessionCache,
      bystanders: Discv5SyncResponder.BystanderEnrTable,
      bootstrapNodes: Set[Node],
      config: DiscoveryConfig
  )(implicit
      sigalg: SigAlg,
      enrContentCodec: Codec[EthereumNodeRecord.Content],
      temporal: Temporal[IO]
  ): Resource[IO, DiscoveryService] = {
    val localNodeId = Session.nodeIdFromPublicKey(publicKey.value.bytes)
    val initialEnr = EthereumNodeRecord.fromNode(localNode, privateKey, seq = 1).require

    val build = for {
      stateRef <- Ref[IO].of(State(initialEnr, Map.empty[ByteVector, EthereumNodeRecord]))
      cancel <- Deferred[IO, Unit]
    } yield new ServiceImpl(stateRef, localNodeId, network, sessions, bystanders, bootstrapNodes, config, cancel)

    Resource
      .make(build) { svc => svc.shutdown }
      .evalTap(_.start)
  }

  private final class ServiceImpl(
      stateRef: Ref[IO, State],
      localNodeId: ByteVector,
      network: DiscoveryNetwork[InetSocketAddress],
      sessions: Session.SessionCache,
      bystanders: Discv5SyncResponder.BystanderEnrTable,
      bootstrapNodes: Set[Node],
      config: DiscoveryConfig,
      cancel: Deferred[IO, Unit]
  )(implicit temporal: Temporal[IO]) extends DiscoveryService with LazyLogging {

    private val random = new SecureRandom()

    override def getNodes: IO[Set[Node]] = stateRef.get.flatMap { s =>
      val builder = Set.newBuilder[Node]
      s.nodes.values.foreach { enr =>
        enrToNode(enr).foreach(builder += _)
      }
      IO.pure(builder.result())
    }

    override def getRandomNode: IO[Option[Node]] = getNodes.map { all =>
      if (all.isEmpty) None
      else {
        val arr = all.toArray
        Some(arr(random.nextInt(arr.length)))
      }
    }

    override def recordPeer(enr: EthereumNodeRecord): IO[Unit] = enrToNode(enr) match {
      case None       => IO.unit
      case Some(node) =>
        val nodeIdBytes = node.id.value.bytes
        stateRef.update(s => s.copy(nodes = s.nodes + (nodeIdBytes -> enr))) *>
          IO(bystanders.add(nodeIdBytes, enr))
    }

    override def localEnr: IO[EthereumNodeRecord] = stateRef.get.map(_.enr)

    /** Start the periodic discovery loop + initial bootstrap enrollment. */
    private[v5] def start: IO[Unit] = for {
      _ <- enrollBootstrapNodes.start.void
      _ <- periodicDiscovery.start.void
    } yield ()

    private[v5] def shutdown: IO[Unit] = cancel.complete(()).void

    /** Send a ping to every bootstrap node so we have an initial set of
      * peers in the table. Errors are logged but don't propagate. */
    private def enrollBootstrapNodes: IO[Unit] =
      bootstrapNodes.toList.traverse_ { node =>
        val addr = new InetSocketAddress(node.address.ip, node.address.udpPort)
        val peer = DiscoveryNetwork.Peer(node.id.value.bytes, addr)
        stateRef.get
          .flatMap(s => network.ping(peer, s.enr.content.seq))
          .void
          .handleErrorWith { ex =>
            IO(logger.debug(s"discv5 bootstrap ping failed for ${node.address.ip}: ${ex.getMessage}"))
          }
      }

    /** Periodic discovery loop. Every `discoveryPeriod`, pick a random
      * 32-byte target node-id and ask the closest known peers for nodes
      * at distance `log_distance(target XOR self)`. Populates the ENR
      * table with new records over time. */
    private def periodicDiscovery: IO[Unit] = {
      Stream
        .awakeEvery[IO](config.discoveryInterval)
        .interruptWhen(cancel.get.attempt)
        .evalMap { _ =>
          discoverRandomTarget.handleErrorWith { ex =>
            IO(logger.debug(s"discv5 periodic discovery error: ${ex.getMessage}"))
          }
        }
        .compile
        .drain
    }

    private def discoverRandomTarget: IO[Unit] = {
      val targetBytes = new Array[Byte](32)
      random.nextBytes(targetBytes)
      val target = ByteVector.view(targetBytes)
      val distance = Discv5SyncResponder.logDistance(localNodeId, target)

      stateRef.get.flatMap { s =>
        // Pick the k peers with the smallest XOR distance to `target`.
        val sorted = s.nodes.values.toList
          .flatMap(enr => enrToNode(enr).map(_ -> enr))
          .sortBy { case (node, _) =>
            xorDistance(node.id.value.bytes, target)
          }
          .take(config.lookupParallelism)

        if (sorted.isEmpty) IO.unit
        else {
          sorted.traverse_ { case (node, _) =>
            val addr = new InetSocketAddress(node.address.ip, node.address.udpPort)
            val peer = DiscoveryNetwork.Peer(node.id.value.bytes, addr)
            network.findNode(peer, List(distance)).flatMap {
              case Some(enrs) => enrs.traverse_(recordPeer)
              case None       => IO.unit
            }
          }
        }
      }
    }

    /** Best-effort `EthereumNodeRecord` → `Node` extraction. Returns None
      * on missing fields or invalid pubkey encoding (the latter happens in
      * tests using mock sig algs whose "pubkey" isn't a real curve point). */
    private def enrToNode(enr: EthereumNodeRecord): Option[Node] = {
      val attrs = enr.content.attrs
      val opt = for {
        ipBytes <- attrs.get(EthereumNodeRecord.Keys.ip)
        udpBytes <- attrs.get(EthereumNodeRecord.Keys.udp)
        secpBytes <- attrs.get(EthereumNodeRecord.Keys.secp256k1)
      } yield (ipBytes, udpBytes, secpBytes)

      opt.flatMap { case (ipBytes, udpBytes, secpBytes) =>
        try {
          val inetAddr = java.net.InetAddress.getByAddress(ipBytes.toArray)
          val udpPort = udpBytes.toInt(signed = false)
          val tcpPort = attrs.get(EthereumNodeRecord.Keys.tcp).map(_.toInt(signed = false)).getOrElse(udpPort)
          val pubUncompressed = decompressSecp256k1(secpBytes)
          Some(
            Node(
              id = com.chipprbots.scalanet.discovery.crypto.PublicKey(pubUncompressed.bits),
              address = Node.Address(inetAddr, udpPort = udpPort, tcpPort = tcpPort)
            )
          )
        } catch {
          case scala.util.control.NonFatal(_) => None
        }
      }
    }

    /** Decompress a 33-byte compressed secp256k1 pubkey to 64-byte uncompressed
      * (X || Y, no `0x04` prefix). */
    private def decompressSecp256k1(compressed: ByteVector): ByteVector = {
      val params = org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256k1")
      val point = params.getCurve.decodePoint(compressed.toArray)
      val xBytes = point.getAffineXCoord.getEncoded
      val yBytes = point.getAffineYCoord.getEncoded
      ByteVector.view(xBytes ++ yBytes)
    }

    /** XOR distance between two 32-byte nodeIds, returned as a BigInt for
      * total-ordering. Smaller = closer in kademlia space. */
    private def xorDistance(a: ByteVector, b: ByteVector): BigInt = {
      require(a.size == 32L && b.size == 32L, "node IDs must be 32 bytes")
      BigInt(1, a.xor(b).toArray)
    }
  }
}
